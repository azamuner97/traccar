"""Local MySQL/HTTP drawing audit; creates and removes only its own fixtures.

Run against the full Compose stack with --tool-source /path/to/Traccar-Tool/src.
Uses the notification audit's isolated administrator and HTTP helpers. No pushes,
production data changes, existing-account mutations, database resets, or volume deletion.
"""

import argparse
import hashlib
import http.cookiejar
import json
import secrets
import sys
import time
import urllib.parse
import urllib.request

import websocket

from game_notification_integration import EMAIL, PASSWORD, PREFIX, literal, request, sql

# Traccar's generic SecurityException mapper returns 400 for resource denials.
ACCESS_DENIED = 400


def socket_for(email):
    cookies = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies))
    request("POST", "/session", urllib.parse.urlencode({"email": email, "password": PASSWORD}).encode(),
            credentials=None, opener=opener)
    return websocket.create_connection("ws://localhost:8083/api/socket", timeout=10,
                                       cookie="; ".join(cookie.name + "=" + cookie.value for cookie in cookies))


def expect_drawing_invalidation(socket):
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        message = json.loads(socket.recv())
        if "drawings" in message.get("invalidate", []):
            return
    raise AssertionError("Drawing permissions were not invalidated on the peer websocket")


def main(tool_source):
    import requests
    sys.path.insert(0, tool_source)
    from traccar_game_manager.application.device_setup.drawings import (
        apply_generated_user_drawings, prepare_generated_user_drawings,
    )
    from traccar_game_manager.domain.game.settings import normalize_game_settings
    from traccar_game_manager.domain.session.factory import default_session_template
    from traccar_game_manager.infrastructure.traccar.client import TraccarClient

    salt = secrets.token_bytes(24)
    digest = hashlib.pbkdf2_hmac("sha256", PASSWORD.encode(), salt, 200000, 24).hex().upper()
    password_hash = "PBKDF2WithHmacSHA256$200000$" + digest
    sql("INSERT INTO tc_users(name,email,hashedpassword,salt,administrator,attributes) VALUES("
        + ",".join([literal(PREFIX), literal(EMAIL), literal(password_hash), literal(salt.hex().upper()),
                    "1", literal("{}")]) + ");")
    admin_id = int(sql("SELECT id FROM tc_users WHERE email=" + literal(EMAIL) + ";"))
    user_ids, device_ids, group_ids, sockets = [], [], [], []
    try:
        request("GET", "/health", credentials=None)
        scopes = []
        for suffix in ("drawing-session", "drawing-other-session"):
            scopes.append(request("POST", "/devices", {
                "name": PREFIX + suffix, "uniqueId": PREFIX + suffix,
                "attributes": {"traccarToolGameState": True}})["id"])
            device_ids.append(scopes[-1])
        session = default_session_template(PREFIX)
        session["devices"]["game_state"] = {"id": scopes[0]}
        accounts, drawings = {}, {}
        for role in ("player", "supporter", "hunter"):
            group = request("POST", "/groups", {"name": PREFIX + "-" + role})
            group_ids.append(group["id"])
            for number in (1, 2):
                unique_id = PREFIX + role + str(number)
                source = request("POST", "/devices", {
                    "name": unique_id, "uniqueId": unique_id, "groupId": group["id"]})
                device_ids.append(source["id"])
                session["devices"]["real"][unique_id] = source["id"]
                session["device_roster"][unique_id] = {"role": role, "number": number, "display_name": unique_id}
                login = unique_id + "@example.invalid"
                user = request("POST", "/users", {
                    "name": unique_id, "email": login, "password": PASSWORD,
                    "login": unique_id + "-alias",
                    "readonly": True, "deviceReadonly": True, "fixedEmail": True, "disableDrawings": False,
                    "attributes": {"traccarToolUserRole": role, "traccarToolSessionDeviceId": scopes[0],
                                   "traccarToolAssignedDeviceId": source["id"]}})
                user_ids.append(user["id"])
                accounts[(role, number)] = user
                session["users"][login] = user["id"]
                session["login_credentials"][login] = {"role": role, "number": number, "password": PASSWORD, "source": "shared"}
                request("POST", "/permissions", {"userId": user["id"], "groupId": group["id"]}, expected=204)
                request("POST", "/permissions", {"userId": user["id"], "deviceId": scopes[0]}, expected=204)
        for key, role, scope in (("spectator", "spectator", scopes[0]),
                                 ("other", "hunter", scopes[1]), ("unrelated", "unknown", scopes[0])):
            user = request("POST", "/users", {
                "name": PREFIX + key, "email": PREFIX + key + "@example.invalid", "password": PASSWORD,
                "disableDrawings": False, "attributes": {"traccarToolUserRole": role,
                    "traccarToolSessionDeviceId": scope, "traccarToolShareGroupDrawings": True}})
            accounts[key] = user
            user_ids.append(user["id"])

        def credentials(key):
            return accounts[key]["email"], PASSWORD

        def synchronize():
            session["game_settings"] = normalize_game_settings(session["game_settings"])
            apply_generated_user_drawings(client, session, prepare_generated_user_drawings(client, session))

        line = {"type": "line", "color": "#FF0000",
                "geometry": {"type": "LineString", "coordinates": [[7, 46], [8, 47]]}}
        text = {"type": "text", "color": "#00FF00", "text": "Owner text", "textSize": 16,
                "geometry": {"type": "Point", "coordinates": [7, 46]}}
        for role in ("player", "supporter", "hunter"):
            drawings[role] = [request("POST", "/drawings", payload, credentials=credentials((role, 1)))
                              for payload in (line, text)]
            assert request("GET", "/drawings", credentials=credentials((role, 2))) == []
            request("GET", "/drawings/" + str(drawings[role][0]["id"]), credentials=credentials((role, 2)), expected=ACCESS_DENIED)

        client = TraccarClient("http://localhost:8083", "http://localhost:5056", EMAIL, PASSWORD,
                               transport=requests)
        synchronize()
        for role in ("player", "supporter", "hunter"):
            for number in (1, 2):
                original = accounts[(role, number)]
                current = request("GET", "/users/" + str(original["id"]))
                assert current["login"] == original["login"]
        hunter_visible = request("GET", "/drawings", credentials=credentials(("hunter", 2)))
        assert len(hunter_visible) == 2 and all(row["canEdit"] and row["canDelete"] for row in hunter_visible)
        for role in ("player", "supporter"):
            own = request("GET", "/drawings", credentials=credentials((role, 1)))
            assert len(own) == 2 and all(not row["canEdit"] and not row["canDelete"] for row in own)
            assert request("GET", "/drawings", credentials=credentials((role, 2))) == []
        socket = socket_for(accounts[("hunter", 2)]["email"])
        sockets.append(socket)
        for role in ("player", "supporter", "hunter"):
            session["game_settings"]["drawing_tools_enabled"][role] = True
            session["game_settings"]["drawing_group_sharing"][role] = True
        synchronize()
        for role in ("player", "supporter", "hunter"):
            peer = credentials((role, 2))
            visible = request("GET", "/drawings", credentials=peer)
            assert {row["id"] for row in visible} == {row["id"] for row in drawings[role]}
            assert all(row["canEdit"] and row["canDelete"] for row in visible)
            for drawing in drawings[role]:
                path = "/drawings/" + str(drawing["id"])
                fetched = request("GET", path, credentials=peer)
                changes = ({"geometry": {"type": "LineString", "coordinates": [[7.1, 46.1], [8.1, 47.1]]}}
                           if drawing["type"] == "line" else {
                               "geometry": {"type": "Point", "coordinates": [7.2, 46.2]},
                               "text": "Teammate text", "textSize": 24})
                updated = request("PUT", path, {**fetched, "ownerId": accounts[(role, 2)]["id"],
                                              "ownerName": "forged", "color": "#0000FF", **changes}, credentials=peer)
                assert updated["ownerId"] == accounts[(role, 1)]["id"]
                assert updated["ownerName"] == accounts[(role, 1)]["name"]
                assert updated["color"] == "#0000FF" and updated["canEdit"] and updated["canDelete"]
                assert all(updated[key] == value for key, value in changes.items())
                persisted = request("GET", path, credentials=credentials((role, 1)))
                assert all(persisted[key] == value for key, value in changes.items())
            for other in ("other", "unrelated", ("supporter", 2) if role == "hunter" else ("hunter", 2)):
                request("GET", path, credentials=credentials(other), expected=ACCESS_DENIED)
                request("PUT", path, text, credentials=credentials(other), expected=ACCESS_DENIED)
                request("DELETE", path, credentials=credentials(other), expected=ACCESS_DENIED)
                assert drawing["id"] not in {row["id"] for row in request("GET", "/drawings", credentials=credentials(other))}
        spectator_rows = request("GET", "/drawings", credentials=credentials("spectator"))
        assert len(spectator_rows) == 6 and all(not row["canEdit"] and not row["canDelete"] for row in spectator_rows)
        request("PUT", "/drawings/" + str(drawings["hunter"][0]["id"]), line,
                credentials=credentials("spectator"), expected=ACCESS_DENIED)
        assert len(request("GET", "/drawings")) >= 6
        user = accounts[("hunter", 2)]
        request("PUT", "/users/" + str(user["id"]), {**user, "attributes": {
            **user["attributes"], "traccarToolShareGroupDrawings": False}}, credentials=credentials(("hunter", 2)), expected=ACCESS_DENIED)

        # Drain pre-existing invalidations so the next assertion proves revocation.
        socket.settimeout(0.1)
        try:
            while True:
                socket.recv()
        except websocket.WebSocketTimeoutException:
            pass
        socket.settimeout(10)
        session["game_settings"]["drawing_group_sharing"]["hunter"] = False
        synchronize()
        expect_drawing_invalidation(socket)
        assert request("GET", "/drawings", credentials=credentials(("hunter", 2))) == []
        request("PUT", "/drawings/" + str(drawings["hunter"][0]["id"]), line,
                credentials=credentials(("hunter", 2)), expected=ACCESS_DENIED)
        request("PUT", "/drawings/" + str(drawings["hunter"][0]["id"]), line,
                credentials=credentials(("hunter", 1)))
        session["game_settings"]["drawing_tools_enabled"]["player"] = False
        synchronize()
        assert request("GET", "/drawings", credentials=credentials(("player", 2))) == []
        request("POST", "/drawings", line, credentials=credentials(("player", 1)), expected=ACCESS_DENIED)
        own = request("GET", "/drawings", credentials=credentials(("player", 1)))
        assert len(own) == 2 and all(not row["canEdit"] and not row["canDelete"] for row in own)
        session["game_settings"]["drawing_tools_enabled"]["player"] = True
        session["game_settings"]["drawing_group_sharing"]["player"] = True
        session["game_settings"]["drawing_group_sharing"]["hunter"] = True
        synchronize()
        for role in ("player", "supporter", "hunter"):
            request("DELETE", "/drawings/" + str(drawings[role][1]["id"]), credentials=credentials((role, 2)), expected=204)
        print("Drawing integration passed: Tool defaults and account synchronization; private and same-role "
              "shared line/text access; ownership preservation; role/session isolation; disabled tools; "
              "revocation websocket invalidation; protected markers; spectator/admin permissions; teammate deletion.")
    finally:
        for socket in sockets:
            socket.close()
        for user_id in user_ids:
            request("DELETE", "/users/" + str(user_id), expected=204)
        for device_id in device_ids:
            request("DELETE", "/devices/" + str(device_id), expected=204)
        for group_id in group_ids:
            request("DELETE", "/groups/" + str(group_id), expected=204)
        try:
            request("DELETE", "/users/" + str(admin_id), expected=204)
        finally:
            sql("DELETE FROM tc_users WHERE id=" + str(admin_id) + " AND email=" + literal(EMAIL) + ";")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tool-source", required=True, help="Traccar Tool src directory")
    main(parser.parse_args().tool_source)
