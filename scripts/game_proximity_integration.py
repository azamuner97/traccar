"""Local MySQL/HTTP proximity audit of game data model 2 with isolated fixtures.

Grants are temporary read-only access between members of one session (by member, resolved to the recipient's
account and the target's tracker); circles are anonymous for hunters and the spectator. See
``game_notification_integration.py`` for the environment. Pushes go through the server's outbox with the ``traccar``
notificator to fixture accounts without push tokens, so nothing leaves the machine. ``--tool-source`` also drives
the Tool's monitoring service, live-window handler, ordered worker and proximity publication against the server.
"""

import argparse
from copy import deepcopy
from datetime import datetime, timedelta, timezone
import sys
from threading import Thread
import time
import urllib.parse

import game_notification_integration as api


def audit_tool_delivery(session_id, members, devices, credentials, tool_source):
    """Exercise service events, the live handler, its ordered HTTP worker and the proximity publication."""
    sys.path.insert(0, tool_source)
    import requests
    from traccar_game_manager.application.live_session.operations import LiveSessionOperations
    from traccar_game_manager.application.monitoring.service import MonitoringService
    from traccar_game_manager.domain.session.factory import default_session_template
    from traccar_game_manager.infrastructure.platform.notification_worker import OrderedNotificationWorker
    from traccar_game_manager.infrastructure.traccar.client import TraccarClient
    from traccar_game_manager.infrastructure.traccar.effects import SessionEffects
    from traccar_game_manager.infrastructure.traccar.game_sessions import GameSessionApi
    from traccar_game_manager.infrastructure.traccar.game_state import ServerGameStateRepository
    from traccar_game_manager.ui.live_session.window import LiveSessionWindow

    class Transport:
        get, post, put, delete = (staticmethod(requests.get), staticmethod(requests.post),
                                  staticmethod(requests.put), staticmethod(requests.delete))

    sessions = GameSessionApi(api.BASE, api.EMAIL, api.PASSWORD, transport=Transport(),
                              epoch_provider=lambda: api.CONTROLLER["epoch"])
    client = TraccarClient(api.BASE, api.UPLOAD, api.EMAIL, api.PASSWORD, transport=api.FencedRequests())
    client.effects = SessionEffects(sessions, session_id)
    client.game_state = ServerGameStateRepository(sessions, session_id)
    warnings, events, checkpoints = [], [], []

    class Clock:
        value = datetime.now(timezone.utc)

        def now(self):
            return self.value

        def monotonic(self):
            return self.value.timestamp()

    class ManualScheduler:
        def call_later(self, _delay, _callback):
            return None

        def cancel(self, _handle):
            pass

    session = default_session_template(api.PREFIX)
    session["local_tz"] = "UTC"
    session["traccar-server"]["web_url"] = api.BASE
    session["devices"] = {"real": {device["uniqueId"]: device["id"] for device in devices.values()},
                          "mirroring": {}}
    session["device_roster"] = {
        device["uniqueId"]: {"role": members[key]["role"], "number": members[key]["number"], "display_name": key}
        for key, device in devices.items()}
    window = LiveSessionWindow.__new__(LiveSessionWindow)
    window.session_snapshot = lambda: deepcopy(session)
    window.devices = [devices[key] for key in ("first", "second")]
    window.live_operations = LiveSessionOperations(client, client, client, mutation_log=lambda _message: None)
    window.game_state = client.game_state.load()
    window._safe_monitoring_log = warnings.append
    window._log_participant_proximity_event = lambda _event: None
    worker = window._notification_worker = OrderedNotificationWorker()

    def drain():
        deadline = time.monotonic() + 30
        while worker.pending:
            assert time.monotonic() < deadline, "Tool notification worker did not drain"
            time.sleep(0.05)
        worker.flush_warnings()
        assert not warnings, f"Tool notification delivery warnings: {warnings}"

    def receive(kind, payload):
        if kind in {"proximity", "proximity_publication"}:
            if kind == "proximity":
                events.append(deepcopy(payload))
            window._handle_monitoring_service_event(kind, payload)

    clock = Clock()
    service = MonitoringService(client, clock, ManualScheduler(), event_sink=receive,
                                persist=checkpoints.append, stream_enabled=False, thread_factory=Thread)
    trackers = [devices[key]["id"] for key in ("first", "second")]
    service.configure(participant_ids=trackers, roles={tracker: "player" for tracker in trackers},
                      participant_proximity={"player_player": True, "distance_m": 100.0,
                                             "waiting_minutes": 0.01, "access_grace_minutes": 0.01,
                                             "circle_diameter_m": 500.0})

    def observe():
        service.apply_positions({tracker: {"deviceId": tracker, "latitude": 47.0, "longitude": 8.0,
                                           "fixTime": clock.now().isoformat()} for tracker in trackers})
        drain()

    pushes_before = int(api.sql(f"SELECT COUNT(*) FROM tc_game_push_outbox WHERE gamesessionid={session_id}"))
    try:
        observe()
        clock.value += timedelta(seconds=1)
        observe()
        activations = [event for event in events if event["transition"] == "consequence"]
        assert len(activations) == 1 and set(activations[0]["newly_exposed_members"]) == set(trackers)
        episode = activations[0]["episode_id"]
        sharing_ids = {f"{episode}-sharing-{tracker}" for tracker in trackers}
        message = ("Your approximate location is now being shared with hunters and spectators "
                   "because you remained too close to another participant. Please move apart.")
        for key in credentials:
            history = api.page(session_id, credentials[key])["items"]
            sharing = [item for item in history if item["id"] in sharing_ids]
            if key in {"first", "second"}:
                assert len(sharing) == 1, "Player did not receive the Tool sharing-start notification"
                alert = sharing[0]
                assert alert["type"] == "participantProximityConsequence" and alert["audience"] == "user"
                assert alert["recipientMemberId"] == alert["sourceMemberId"] == members[key]["id"]
                assert alert["message"].endswith(message)
            else:
                assert not sharing, "Personal sharing-start notification leaked to another account"
            group = [item for item in history if item["id"] == f"{episode}-consequence"]
            assert len(group) == (1 if key in {"hunter", "spectator"} else 0), key
        # Each sharing start is pushed to its member through the outbox, once.
        rows = api.sql(f"SELECT recipientmemberid, subject, status FROM tc_game_push_outbox "
                       f"WHERE gamesessionid={session_id} AND message LIKE "
                       + api.literal("%" + message) + " ORDER BY id").splitlines()
        assert sorted(int(row.split("\t")[0]) for row in rows) == sorted(
            members[key]["id"] for key in ("first", "second")), rows
        assert all(row.split("\t")[1] == "Participant proximity" for row in rows), rows
        state = api.request("GET", f"/game-proximity/session/{session_id}")
        assert len(state["circles"]) == 1 and set(state["circles"][0]["memberIds"]) == {
            members[key]["id"] for key in ("first", "second")}, state
        assert {(grant["recipientMemberId"], grant["targetMemberId"]) for grant in state["grants"]} == {
            (members["first"]["id"], members["second"]["id"]), (members["second"]["id"], members["first"]["id"])}
        assert checkpoints[-1]["participant_proximity"]["pairs"]
        count = int(api.sql(f"SELECT COUNT(*) FROM tc_game_push_outbox WHERE gamesessionid={session_id}"))
        assert count > pushes_before
        clock.value += timedelta(seconds=30)
        observe()
        assert int(api.sql(f"SELECT COUNT(*) FROM tc_game_push_outbox WHERE gamesessionid={session_id}")) \
            == count, "Circle refresh repeated a sharing-start notification"
        assert len([event for event in events if event["transition"] == "consequence"]) == 1
    finally:
        try:
            window._handle_monitoring_service_event("proximity_publication", {"pairs": [], "circles": []})
            drain()
        finally:
            worker.close()
            worker._thread.join(timeout=5)
    assert api.request("GET", f"/game-proximity/session/{session_id}")["grants"] == []
    print("Tool delivery audit passed: live service, ordered worker, personal history by member, recipient "
          "isolation, outbox pushes, proximity publication by member, and silent circle refresh.")


def visible(credentials):
    return {device["id"] for device in api.request("GET", "/devices", credentials=credentials)}


def main(tool_source=None, skip_restart=False):
    api.wait_for_server()
    admin_id = api.create_admin()
    fixtures = api.Fixtures()
    try:
        api.acquire_controller()
        session_id = api.create_game_session("proximity")
        other_id = api.create_game_session("other")
        api.execute(session_id)
        generation = api.request("POST", f"/game-notifications/session/{session_id}", {})["generation"]
        endpoint = f"/game-proximity/session/{session_id}"
        members, users, devices, credentials = {}, {}, {}, {}
        for key, role in (("first", "player"), ("second", "player"), ("supporter", "supporter"),
                          ("hunter", "hunter"), ("spectator", "spectator")):
            members[key] = api.plan(session_id, role, key)
            # Share-account emails append the tracker's unique ID: keep fixture emails short.
            users[key], credentials[key] = fixtures.account("p-" + api.PREFIX[-16:] + "-" + key, readonly=True,
                                                            deviceReadonly=True)
            api.bind_account(session_id, members[key]["id"], users[key]["id"])
            if role != "spectator":
                devices[key] = fixtures.device(api.PREFIX + "-" + key, attributes={
                    "traccarToolRole": role, "traccarToolRoleNumber": members[key]["number"]})
                members[key] = api.bind_tracker(session_id, members[key]["id"], devices[key]["id"])
                api.request("POST", "/permissions", {"userId": users[key]["id"], "deviceId": devices[key]["id"]},
                            expected=204)
        outsider = api.plan(other_id, "hunter", "outsider")
        outsider_user, outsider_credentials = fixtures.account("p-" + api.PREFIX[-16:] + "-outsider")
        api.bind_account(other_id, outsider["id"], outsider_user["id"])
        first, second = devices["first"]["id"], devices["second"]["id"]
        first_member, second_member = members["first"]["id"], members["second"]["id"]

        def content(revision, seconds=120, recipient_keys=("first", "second")):
            expires = api.iso(seconds=seconds)
            grants = [{"episodeId": "pair", "recipientMemberId": members[key]["id"],
                       "targetMemberId": members[other]["id"], "expiresAt": expires}
                      for key, other in (("first", "second"), ("second", "first")) if key in recipient_keys]
            return {"expectedRevision": revision, "grants": grants,
                    "circles": [{"id": "circle", "latitude": 47, "longitude": 8, "diameterMeters": 500,
                                 "expiresAt": expires, "memberIds": [first_member, second_member]}]}

        def revision():
            return api.request("GET", endpoint)["revision"]

        sockets = {key: fixtures.socket(credentials[key]) for key in ("first", "second", "hunter", "spectator",
                                                                       "supporter")}
        value = content(0)
        assert api.request("PUT", endpoint, value)["revision"] == 1
        # An identical retry of the last write answers the current state.
        assert api.request("PUT", endpoint, value)["revision"] == 1
        for key, peer in (("first", second), ("second", first)):
            assert peer in visible(credentials[key])
            access = api.request("GET", "/game-proximity/access", credentials=credentials[key])
            assert [(item["memberId"], item["deviceId"]) for item in access] == [
                (members["second" if key == "first" else "first"]["id"], peer)], access
            assert all(set(item) == {"memberId", "deviceId", "expiresAt"} for item in access), access
        assert api.request("GET", "/game-proximity/access", credentials=credentials["supporter"]) == []
        assert first not in visible(credentials["supporter"])
        # Players and supporters learn of their access through a device refresh; hunters and the spectator are
        # told to reload circles.
        for key in ("first", "second"):
            api.wait_for_invalidation(sockets[key], "devices")
        for key in ("hunter", "spectator"):
            api.wait_for_invalidation(sockets[key], "game-proximity")
        assert "game-proximity" not in api.invalidations(sockets["supporter"], 0.5), \
            "players and supporters never receive the circles' invalidation"
        # A lease grants reads only, even if ordinary write restrictions are relaxed on the generated account.
        writable = {**users["first"], "readonly": False, "deviceReadonly": False}
        api.request("PUT", f"/users/{writable['id']}", writable)
        def refused(method, path, body=None):
            """Refused by the fence (423, the tracker is a game resource) or by Traccar's permissions (400)."""
            try:
                api.request(method, path, body, credentials=credentials["first"], expected=400)
            except AssertionError as error:
                assert "returned 423" in str(error), error

        refused("POST", "/commands/send", {"deviceId": second, "type": "custom",
                                            "attributes": {"data": "proximity-audit"}})
        refused("PUT", f"/devices/{second}", devices["second"])
        refused("DELETE", f"/devices/{second}")
        refused("DELETE", f"/positions?deviceId={second}")
        refused("POST", "/permissions", {"userId": writable["id"], "deviceId": second})
        share_expiry = (datetime.now(timezone.utc) + timedelta(hours=1)).isoformat()
        api.request("POST", "/share/device", urllib.parse.urlencode({"deviceId": first,
                    "expiration": share_expiry}).encode(), credentials=credentials["first"])
        shared_email = users["first"]["email"] + ":" + devices["first"]["uniqueId"]
        fixtures.users.append(int(api.sql("SELECT id FROM tc_users WHERE email=" + api.literal(shared_email))))
        api.request("POST", "/share/device", urllib.parse.urlencode({"deviceId": second,
                    "expiration": share_expiry}).encode(), credentials=credentials["first"], expected=400)
        api.request("PUT", f"/users/{writable['id']}", users["first"])
        for key in ("hunter", "spectator"):
            circles = api.request("GET", "/game-proximity/circles", credentials=credentials[key])
            assert len(circles) == 1 and circles[0]["playerCount"] == 2 and circles[0]["supporterCount"] == 0
            assert "memberIds" not in circles[0] and "participantDeviceIds" not in circles[0], circles
            api.request("GET", endpoint, credentials=credentials[key], expected=403)
        for key in ("first", "second", "supporter"):
            api.request("GET", "/game-proximity/circles", credentials=credentials[key], expected=403)
            # Fenced before authorization: participants never hold the controller lease.
            api.request("PUT", endpoint, value, credentials=credentials[key], expected=423)
        assert api.request("GET", "/game-proximity/circles", credentials=outsider_credentials) == []
        api.request("GET", "/game-proximity/access", credentials=None, expected=401)
        api.request("GET", "/game-proximity/circles", credentials=None, expected=401)
        api.request("PUT", endpoint, {**value, "circles": [{**value["circles"][0], "diameterMeters": 600}]},
                    expected=409)
        api.request("PUT", endpoint, content(1, recipient_keys=("first",)), expected=400)  # grants are reciprocal
        api.request("PUT", endpoint, {**content(1), "grants": [
            {"episodeId": "x", "recipientMemberId": members["hunter"]["id"], "targetMemberId": first_member,
             "expiresAt": api.iso(seconds=60)},
            {"episodeId": "x", "recipientMemberId": first_member, "targetMemberId": members["hunter"]["id"],
             "expiresAt": api.iso(seconds=60)}]}, expected=400)

        occurred = api.iso()
        payload = {"gameSessionId": session_id, "generation": generation}
        for alert_type in ("participantProximityEnter", "participantProximityExit"):
            api.request("POST", "/game-notifications", {**payload, "alert": {
                "id": alert_type, "type": alert_type, "audience": "user", "occurredAt": occurred,
                "timeZone": "UTC", "message": "Personal proximity notice",
                "recipientMemberId": first_member, "sourceMemberId": first_member}})
        for key in ("first", "supporter"):
            personal = {**payload, "alert": {
                "id": "sharing-" + key, "type": "participantProximityConsequence", "audience": "user",
                "occurredAt": occurred, "timeZone": "UTC",
                "message": "Your approximate location is now being shared with hunters and spectators "
                           "because you remained too close to another participant. Please move apart.",
                "recipientMemberId": members[key]["id"], "sourceMemberId": members[key]["id"]}}
            assert api.request("POST", "/game-notifications", personal)["created"]
            assert not api.request("POST", "/game-notifications", personal)["created"]
        api.request("POST", "/game-notifications", {**personal, "alert": {
            **personal["alert"], "id": "wrong-source", "sourceMemberId": first_member}}, expected=400)
        api.request("POST", "/game-notifications", {**payload, "alert": {
            "id": "nonhunters", "type": "participantProximityConsequence", "audience": "nonHunters",
            "occurredAt": occurred, "timeZone": "UTC", "message": "2 players are too close"}}, expected=400)
        api.request("POST", "/game-notifications", {**payload, "alert": {
            "id": "consequence", "type": "participantProximityConsequence", "audience": "hunters",
            "occurredAt": occurred, "timeZone": "UTC", "message": "2 players are too close"}})
        for key in credentials:
            identifiers = {item["id"] for item in api.page(session_id, credentials[key])["items"]}
            expected = ({"participantProximityEnter", "participantProximityExit"} if key == "first" else set()) \
                | ({"sharing-" + key} if key in ("first", "supporter") else set()) \
                | ({"consequence"} if key in {"hunter", "spectator"} else set())
            assert identifiers == expected, ("Proximity notification audience isolation failed", key, identifiers)
        if tool_source:
            for socket in sockets.values():
                api.drain(socket)
            audit_tool_delivery(session_id, members, devices, credentials, tool_source)

        # A permanent permission is independent of a short grant that expires and is purged.
        api.request("POST", "/permissions", {"userId": users["first"]["id"], "deviceId": second}, expected=204)
        api.request("PUT", endpoint, content(revision(), 2))
        # Trackers the account sees permanently anyway are left out of its temporary access.
        assert api.request("GET", "/game-proximity/access", credentials=credentials["first"]) == []
        assert [item["deviceId"] for item in api.request("GET", "/game-proximity/access",
                                                         credentials=credentials["second"])] == [first]
        deadline = time.monotonic() + 8
        while api.request("GET", endpoint)["grants"]:
            assert time.monotonic() < deadline, "Temporary proximity grants did not expire"
            time.sleep(0.2)
        assert api.request("GET", "/game-proximity/access", credentials=credentials["first"]) == []
        assert second in visible(credentials["first"])
        assert first not in visible(credentials["second"])
        assert api.request("GET", "/game-proximity/circles", credentials=credentials["hunter"]) == []
        api.request("DELETE", "/permissions", {"userId": users["first"]["id"], "deviceId": second}, expected=204)

        # Unbinding or rebinding a member revokes its grants (both directions) with the next revision.
        api.request("PUT", endpoint, content(revision()))
        before = revision()
        api.request("DELETE", f"/game-sessions/{session_id}/members/{second_member}/account")
        state = api.request("GET", endpoint)
        assert state["grants"] == [] and state["revision"] == before + 1, state
        assert second not in visible(credentials["first"])
        api.request("PUT", endpoint, content(before), expected=409)
        api.bind_account(session_id, second_member, users["second"]["id"])
        api.request("PUT", endpoint, content(revision()))
        replacement = fixtures.device(api.PREFIX + "-second-replacement")
        api.bind_tracker(session_id, second_member, replacement["id"])
        assert api.request("GET", endpoint)["grants"] == []
        api.bind_tracker(session_id, second_member, second)
        # A role change retires the member: its grants end, and circles may no longer name it.
        api.request("PUT", endpoint, content(revision()))
        changed = api.request("POST", f"/game-sessions/{session_id}/members/{second_member}/role",
                              {"role": "supporter", "displayName": "second"})
        assert api.request("GET", endpoint)["grants"] == []
        api.request("PUT", endpoint, content(revision()), expected=400)
        members["second"] = changed
        second_member = changed["id"]
        api.request("PUT", endpoint, content(revision()))
        assert first in visible(credentials["second"])
        # A reset publishes empty content with the next revision.
        current = revision()
        reset = api.request("POST", endpoint + "/reset", {})
        assert reset["revision"] == current + 1 and reset["grants"] == [] and reset["circles"] == [], reset
        assert first not in visible(credentials["second"])

        if not skip_restart:
            api.request("PUT", endpoint, content(revision(), 2))
            fixtures.close_sockets()
            api.restart_server()
            api.acquire_controller()
            api.execute(session_id)
            time.sleep(3)
            state = api.request("GET", endpoint)
            assert state["grants"] == [] and state["circles"] == [], state
            assert first not in visible(credentials["second"])
        # Deleting the session ends every grant at once.
        api.request("PUT", endpoint, content(revision(), 3600))
        assert first in visible(credentials["second"])
        api.request("DELETE", f"/game-sessions/{session_id}", expected=204)
        api.GAME_SESSION_IDS.remove(session_id)
        assert first not in visible(credentials["second"])
        assert api.sql(f"SELECT COUNT(*) FROM tc_game_proximity_grants WHERE gamesessionid={session_id}") == "0"
        print("MySQL/HTTP proximity audit passed: grants by member with scoped access, anonymous circles, "
              "independent permanent permissions, read-only/non-shareable leases, revision conflicts and "
              "idempotent retries, reciprocal grants between players and supporters, notification isolation, "
              "expiry and purge, revocation on unbinding, tracker replacement and role change, reset, session "
              "deletion" + ("." if skip_restart else ", and server restart."))
    finally:
        fixtures.remove()
        api.delete_game_sessions()
        api.release_controller()
        api.delete_admin(admin_id)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tool-source")
    parser.add_argument("--skip-restart", action="store_true", help="Leave a running development server uninterrupted")
    arguments = parser.parse_args()
    main(arguments.tool_source, arguments.skip_restart)
