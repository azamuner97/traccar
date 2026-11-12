"""Local MySQL/HTTP drawing audit of game data model 2; creates and removes only its own fixtures.

Run against the full Compose stack with --tool-source /path/to/Traccar-Tool/src. Sharing follows membership rows:
members with the same role in the same session who share see and edit each other's drawings, the spectator sees its
session's drawings, and former members and other sessions see nothing. Uses the notification audit's isolated
administrator and HTTP helpers. No pushes, existing-account mutations, database resets, or volume deletion.
"""

import argparse
import json
import sys
import time

import game_notification_integration as api

# Traccar's generic SecurityException mapper returns 400 for resource denials.
ACCESS_DENIED = 400


def expect_drawing_invalidation(socket):
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        message = json.loads(socket.recv())
        if "drawings" in message.get("invalidate", []):
            return
    raise AssertionError("Drawing permissions were not invalidated on the peer websocket")


def main(tool_source):
    sys.path.insert(0, tool_source)
    from traccar_game_manager.application.device_setup.drawings import (
        apply_generated_user_drawings, prepare_generated_user_drawings,
    )
    from traccar_game_manager.domain.game.settings import normalize_game_settings
    from traccar_game_manager.domain.session.factory import default_session_template
    from traccar_game_manager.infrastructure.traccar.client import TraccarClient

    api.wait_for_server()
    admin_id = api.create_admin()
    fixtures = api.Fixtures()
    try:
        api.acquire_controller()
        session_id = api.create_game_session("drawing-session")
        other_id = api.create_game_session("drawing-other-session")
        session = default_session_template(api.PREFIX)
        accounts, members, drawings = {}, {}, {}

        def account(key, login, **fields):
            user = api.request("POST", "/users", {"name": login, "email": login, "password": api.PASSWORD,
                                                  "disableDrawings": False, **fields})
            fixtures.users.append(user["id"])
            accounts[key] = user
            return user

        for role in ("player", "supporter", "hunter"):
            group = fixtures.group(api.PREFIX + "-" + role)
            for number in (1, 2):
                unique_id = api.PREFIX + role + str(number)
                tracker = fixtures.device(unique_id, groupId=group["id"])
                login = unique_id + "@example.invalid"
                user = account((role, number), login, readonly=True, deviceReadonly=True, fixedEmail=True)
                member = api.plan(session_id, role, unique_id)
                api.bind_account(session_id, member["id"], user["id"])
                members[(role, number)] = api.bind_tracker(session_id, member["id"], tracker["id"])
                session["devices"]["real"][unique_id] = tracker["id"]
                session["device_roster"][unique_id] = {"role": role, "number": number, "display_name": unique_id}
                session["users"][login] = user["id"]
                session["login_credentials"][login] = {"role": role, "number": number, "password": api.PASSWORD,
                                                       "source": "shared"}
                api.request("POST", "/permissions", {"userId": user["id"], "groupId": group["id"]}, expected=204)
        spectator = account("spectator", api.PREFIX + "-spectator@example.invalid")
        members["spectator"] = api.plan(session_id, "spectator", "Spectator")
        api.bind_account(session_id, members["spectator"]["id"], spectator["id"])
        # Markers on the account grant nothing: only membership rows share drawings.
        account("other", api.PREFIX + "-other@example.invalid",
                attributes={"traccarToolUserRole": "hunter", "traccarToolShareGroupDrawings": True})
        other_member = api.plan(other_id, "hunter", "Other")
        api.bind_account(other_id, other_member["id"], accounts["other"]["id"])
        account("unrelated", api.PREFIX + "-unrelated@example.invalid",
                attributes={"traccarToolUserRole": "player", "traccarToolSessionDeviceId": session_id,
                            "traccarToolShareGroupDrawings": True})

        def credentials(key):
            return accounts[key]["email"], api.PASSWORD

        client = TraccarClient(api.BASE, api.UPLOAD, api.EMAIL, api.PASSWORD, transport=api.FencedRequests())

        def synchronize():
            session["game_settings"] = normalize_game_settings(session["game_settings"])
            apply_generated_user_drawings(client, session, prepare_generated_user_drawings(client, session))

        line = {"type": "line", "color": "#FF0000",
                "geometry": {"type": "LineString", "coordinates": [[7, 46], [8, 47]]}}
        text = {"type": "text", "color": "#00FF00", "text": "Owner text", "textSize": 16,
                "geometry": {"type": "Point", "coordinates": [7, 46]}}
        for role in ("player", "supporter", "hunter"):
            drawings[role] = [api.request("POST", "/drawings", payload, credentials=credentials((role, 1)))
                              for payload in (line, text)]
            assert api.request("GET", "/drawings", credentials=credentials((role, 2))) == []
            api.request("GET", "/drawings/" + str(drawings[role][0]["id"]), credentials=credentials((role, 2)),
                        expected=ACCESS_DENIED)
        # A new drawing records the session its owner takes part in.
        assert api.sql("SELECT DISTINCT gamesessionid FROM tc_drawings WHERE id IN ("
                       + ",".join(str(item["id"]) for items in drawings.values() for item in items) + ")") \
            == str(session_id)
        synchronize()
        for role in ("player", "supporter", "hunter"):
            for number in (1, 2):
                original = accounts[(role, number)]
                assert api.request("GET", "/users/" + str(original["id"]))["login"] == original["login"]
        hunter_visible = api.request("GET", "/drawings", credentials=credentials(("hunter", 2)))
        assert len(hunter_visible) == 2 and all(row["canEdit"] and row["canDelete"] for row in hunter_visible)
        for role in ("player", "supporter"):
            own = api.request("GET", "/drawings", credentials=credentials((role, 1)))
            assert len(own) == 2 and all(not row["canEdit"] and not row["canDelete"] for row in own)
            assert api.request("GET", "/drawings", credentials=credentials((role, 2))) == []
        socket = fixtures.socket(credentials(("hunter", 2)))
        for role in ("player", "supporter", "hunter"):
            session["game_settings"]["drawing_tools_enabled"][role] = True
            session["game_settings"]["drawing_group_sharing"][role] = True
        synchronize()
        for role in ("player", "supporter", "hunter"):
            peer = credentials((role, 2))
            visible = api.request("GET", "/drawings", credentials=peer)
            assert {row["id"] for row in visible} == {row["id"] for row in drawings[role]}
            assert all(row["canEdit"] and row["canDelete"] for row in visible)
            for drawing in drawings[role]:
                path = "/drawings/" + str(drawing["id"])
                fetched = api.request("GET", path, credentials=peer)
                changes = ({"geometry": {"type": "LineString", "coordinates": [[7.1, 46.1], [8.1, 47.1]]}}
                           if drawing["type"] == "line" else {
                               "geometry": {"type": "Point", "coordinates": [7.2, 46.2]},
                               "text": "Teammate text", "textSize": 24})
                updated = api.request("PUT", path, {**fetched, "ownerId": accounts[(role, 2)]["id"],
                                                    "ownerName": "forged", "color": "#0000FF", **changes},
                                      credentials=peer)
                assert updated["ownerId"] == accounts[(role, 1)]["id"]
                assert updated["ownerName"] == accounts[(role, 1)]["name"]
                assert updated["color"] == "#0000FF" and updated["canEdit"] and updated["canDelete"]
                assert all(updated[key] == value for key, value in changes.items())
                persisted = api.request("GET", path, credentials=credentials((role, 1)))
                assert all(persisted[key] == value for key, value in changes.items())
            for other in ("other", "unrelated", ("supporter", 2) if role == "hunter" else ("hunter", 2)):
                api.request("GET", path, credentials=credentials(other), expected=ACCESS_DENIED)
                api.request("PUT", path, text, credentials=credentials(other), expected=ACCESS_DENIED)
                api.request("DELETE", path, credentials=credentials(other), expected=ACCESS_DENIED)
                assert drawing["id"] not in {row["id"] for row in api.request(
                    "GET", "/drawings", credentials=credentials(other))}
        spectator_rows = api.request("GET", "/drawings", credentials=credentials("spectator"))
        assert len(spectator_rows) == 6 and all(not row["canEdit"] and not row["canDelete"]
                                                for row in spectator_rows)
        api.request("PUT", "/drawings/" + str(drawings["hunter"][0]["id"]), line,
                    credentials=credentials("spectator"), expected=ACCESS_DENIED)
        assert len(api.request("GET", "/drawings")) >= 6
        user = api.request("GET", "/users/" + str(accounts[("hunter", 2)]["id"]))
        api.request("PUT", "/users/" + str(user["id"]), {**user, "attributes": {
            **user["attributes"], "traccarToolShareGroupDrawings": False}},
            credentials=credentials(("hunter", 2)), expected=ACCESS_DENIED)

        # Drain pre-existing invalidations so the next assertion proves revocation.
        api.drain(socket, 0.3)
        session["game_settings"]["drawing_group_sharing"]["hunter"] = False
        synchronize()
        expect_drawing_invalidation(socket)
        assert api.request("GET", "/drawings", credentials=credentials(("hunter", 2))) == []
        api.request("PUT", "/drawings/" + str(drawings["hunter"][0]["id"]), line,
                    credentials=credentials(("hunter", 2)), expected=ACCESS_DENIED)
        api.request("PUT", "/drawings/" + str(drawings["hunter"][0]["id"]), line,
                    credentials=credentials(("hunter", 1)))
        session["game_settings"]["drawing_tools_enabled"]["player"] = False
        synchronize()
        assert api.request("GET", "/drawings", credentials=credentials(("player", 2))) == []
        api.request("POST", "/drawings", line, credentials=credentials(("player", 1)), expected=ACCESS_DENIED)
        own = api.request("GET", "/drawings", credentials=credentials(("player", 1)))
        assert len(own) == 2 and all(not row["canEdit"] and not row["canDelete"] for row in own)
        session["game_settings"]["drawing_tools_enabled"]["player"] = True
        session["game_settings"]["drawing_group_sharing"]["player"] = True
        session["game_settings"]["drawing_group_sharing"]["hunter"] = True
        synchronize()
        # A former member (retired) no longer shares; the drawings stay with their owner.
        api.request("POST", f"/game-sessions/{session_id}/members/{members[('supporter', 2)]['id']}/retire", {})
        assert api.request("GET", "/drawings", credentials=credentials(("supporter", 2))) == []
        assert len(api.request("GET", "/drawings", credentials=credentials(("supporter", 1)))) == 2
        for role in ("player", "hunter"):
            api.request("DELETE", "/drawings/" + str(drawings[role][1]["id"]), credentials=credentials((role, 2)),
                        expected=204)
        # Deleting the session deletes its drawings.
        api.request("DELETE", f"/game-sessions/{session_id}", expected=204)
        api.GAME_SESSION_IDS.remove(session_id)
        assert api.sql(f"SELECT COUNT(*) FROM tc_drawings WHERE gamesessionid={session_id}") == "0"
        print("Drawing integration passed: Tool defaults and account synchronization; drawings recorded with the "
              "owner's session; private and same-role shared line/text access by membership; ownership "
              "preservation; role/session isolation and markers that grant nothing; disabled tools; revocation "
              "websocket invalidation; protected sharing switch; spectator/admin permissions; former members; "
              "teammate deletion; deletion with the session.")
    finally:
        fixtures.remove()
        api.delete_game_sessions()
        api.release_controller()
        api.delete_admin(admin_id)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tool-source", required=True, help="Traccar Tool src directory")
    main(parser.parse_args().tool_source)
