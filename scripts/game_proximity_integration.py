"""Local MySQL/HTTP proximity audit with isolated fixtures and no native pushes."""

import argparse
from copy import deepcopy
from datetime import datetime, timedelta, timezone
import hashlib
import http.cookiejar
import json
import secrets
import subprocess
import sys
from threading import Thread
import time
import urllib.request
import urllib.parse
from uuid import uuid4

import websocket
import game_notification_integration as api


def audit_tool_delivery(session_device, users, devices, credentials, tool_source):
    """Exercise service events, the live handler, and its real ordered HTTP worker."""
    sys.path.insert(0, tool_source)
    import requests
    from traccar_game_manager.application.monitoring.service import MonitoringService
    from traccar_game_manager.domain.session.credentials import normalize_login_credentials
    from traccar_game_manager.domain.session.factory import default_session_template
    from traccar_game_manager.infrastructure.platform.notification_worker import OrderedNotificationWorker
    from traccar_game_manager.infrastructure.traccar.client import TraccarClient
    from traccar_game_manager.ui.live_session.window import LiveSessionWindow

    client = TraccarClient(api.BASE, "http://localhost:5056", api.EMAIL, api.PASSWORD, transport=requests)
    pushes, warnings, events, checkpoints = [], [], [], []

    class CapturedPushGateway:
        def __getattr__(self, name):
            return getattr(client, name)

        def send_announcement(self, user_ids, subject, message, *, notificator):
            assert notificator == "traccar"
            pushes.append((list(user_ids), subject, message))

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
    session["devices"] = {"game_state": {"id": session_device["id"], "unique_id": session_device["uniqueId"]},
                          "real": {device["uniqueId"]: device["id"] for device in devices.values()}}
    session["device_roster"] = {
        device["uniqueId"]: {"role": device["attributes"]["traccarToolRole"],
                             "number": device["attributes"]["traccarToolRoleNumber"], "display_name": key}
        for key, device in devices.items()}
    session["users"] = {user["email"]: user["id"] for user in users.values()}
    session["login_credentials"] = normalize_login_credentials({
        user["email"]: {"role": user["attributes"]["traccarToolUserRole"],
                        "number": devices[key]["attributes"]["traccarToolRoleNumber"] if key in devices else None}
        for key, user in users.items()})
    window = LiveSessionWindow.__new__(LiveSessionWindow)
    window.session_snapshot = lambda: deepcopy(session)
    window.devices = list(devices.values())
    window.live_operations = CapturedPushGateway()
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
    service = MonitoringService(window.live_operations, clock, ManualScheduler(), event_sink=receive,
                                persist=checkpoints.append, stream_enabled=False, thread_factory=Thread)
    members = [devices[key]["id"] for key in ("first", "second")]
    service.configure(participant_ids=members, roles={member: "player" for member in members},
                      participant_proximity={"player_player": True, "distance_m": 100.0,
                                             "waiting_minutes": 0.01, "access_grace_minutes": 0.01,
                                             "circle_diameter_m": 500.0})

    def observe():
        service.apply_positions({member: {"deviceId": member, "latitude": 47.0, "longitude": 8.0,
                                          "fixTime": clock.now().isoformat()} for member in members})
        drain()

    try:
        observe()
        clock.value += timedelta(seconds=1)
        observe()
        activations = [event for event in events if event["transition"] == "consequence"]
        assert len(activations) == 1 and set(activations[0]["newly_exposed_members"]) == set(members)
        episode = activations[0]["episode_id"]
        sharing_ids = {f"{episode}-sharing-{member}" for member in members}
        message = ("Your approximate location is now being shared with hunters and spectators "
                   "because you remained too close to another participant. Please move apart.")
        for key in credentials:
            history = api.request("GET", f"/game-notifications?sessionDeviceId={session_device['id']}",
                                  credentials=credentials[key])["items"]
            sharing = [item for item in history if item["id"] in sharing_ids]
            if key in {"first", "second"}:
                assert len(sharing) == 1, "Player did not receive the Tool sharing-start notification"
                alert = sharing[0]
                assert alert["type"] == "participantProximityConsequence" and alert["audience"] == "user"
                assert alert["recipientUserId"] == users[key]["id"]
                assert alert["sourceDeviceId"] == devices[key]["id"]
                assert alert["message"].endswith(message)
            else:
                assert not sharing, "Personal sharing-start notification leaked to another account"
            group = [item for item in history if item["id"] == f"{episode}-consequence"]
            assert len(group) == (1 if key in {"hunter", "spectator"} else 0)
        personal_pushes = [push for push in pushes if push[2].endswith(message)]
        assert {tuple(push[0]) for push in personal_pushes} == {(users[key]["id"],) for key in ("first", "second")}
        assert len(personal_pushes) == 2 and all(push[1] == "Participant proximity" for push in personal_pushes)
        state = client.get_proximity_state(session_device["id"])
        assert len(state["circles"]) == 1 and set(state["circles"][0]["participantDeviceIds"]) == set(members)
        assert checkpoints[-1]["participant_proximity"]["pairs"]
        count = len(pushes)
        clock.value += timedelta(seconds=30)
        observe()
        assert len(pushes) == count, "Circle refresh repeated a sharing-start notification"
        assert len([event for event in events if event["transition"] == "consequence"]) == 1
    finally:
        try:
            window._handle_monitoring_service_event("proximity_publication", {"pairs": [], "circles": []})
            drain()
        finally:
            worker.close()
            worker._thread.join(timeout=5)
    print("Tool delivery audit passed: live service, ordered worker, personal history, recipient isolation, "
          "captured native pushes, and silent circle refresh.")


def main(tool_source=None, skip_restart=False):
    api.wait_for_server()
    salt = secrets.token_bytes(24)
    digest = hashlib.pbkdf2_hmac("sha256", api.PASSWORD.encode(), salt, 200000, 24).hex().upper()
    api.sql("INSERT INTO tc_users(name,email,hashedpassword,salt,administrator,attributes) VALUES("
            + ",".join([api.literal(api.PREFIX), api.literal(api.EMAIL),
                        api.literal("PBKDF2WithHmacSHA256$200000$" + digest),
                        api.literal(salt.hex().upper()), "1", api.literal("{}")]) + ");")
    admin_id = int(api.sql("SELECT id FROM tc_users WHERE email=" + api.literal(api.EMAIL)))
    device_ids, user_ids, sockets = [], [], []
    try:
        session = api.request("POST", "/devices", {"name": api.PREFIX + "-session",
                              "uniqueId": "traccar-tool-state-" + hashlib.sha256(api.PREFIX.encode()).hexdigest()[:24],
                              "attributes": {"traccarToolGameState": True}})
        session_id = session["id"]
        device_ids.append(session_id)
        other = api.request("POST", "/devices", {"name": api.PREFIX + "-other",
                            "uniqueId": api.PREFIX + "-other", "attributes": {"traccarToolGameState": True}})
        device_ids.append(other["id"])
        generation = api.request("POST", f"/game-notifications/session/{session_id}", {})["generation"]
        endpoint = f"/game-proximity/session/{session_id}"
        users, devices, credentials = {}, {}, {}
        for key, role, number in (("first", "player", 1), ("second", "player", 2),
                                  ("supporter", "supporter", 1), ("hunter", "hunter", 1),
                                  ("spectator", "spectator", None)):
            attributes = {"traccarToolUserRole": role, "traccarToolSessionDeviceId": session_id}
            if number is not None:
                device = api.request("POST", "/devices", {"name": api.PREFIX + "-" + key,
                                     "uniqueId": api.PREFIX + "-" + key,
                                     "attributes": {"traccarToolRole": role, "traccarToolRoleNumber": number}})
                devices[key] = device
                device_ids.append(device["id"])
                attributes["traccarToolAssignedDeviceId"] = device["id"]
            # Share-account emails append the tracker's unique ID. Keep the
            # fixture email short enough for the existing 128-character column.
            email = "p-" + api.PREFIX[-16:] + "-" + key + "@example.invalid"
            user = api.request("POST", "/users", {"name": key, "email": email, "password": api.PASSWORD,
                               "readonly": True, "deviceReadonly": True, "attributes": attributes})
            user_ids.append(user["id"])
            users[key], credentials[key] = user, (email, api.PASSWORD)
            api.request("POST", "/permissions", {"userId": user["id"], "deviceId": session_id}, expected=204)
            if key in devices:
                api.request("POST", "/permissions", {"userId": user["id"], "deviceId": devices[key]["id"]}, expected=204)
        first, second = devices["first"]["id"], devices["second"]["id"]

        def snapshot(revision, seconds=120):
            expires = (datetime.now(timezone.utc) + timedelta(seconds=seconds)).isoformat().replace("+00:00", "Z")
            return {"generation": generation, "revision": revision,
                    "grants": [{"episodeId": "pair", "recipientUserId": users[key]["id"],
                                "sourceDeviceId": devices[key]["id"], "targetDeviceId": target,
                                "expiresAt": expires} for key, target in (("first", second), ("second", first))],
                    "circles": [{"id": "circle", "latitude": 47, "longitude": 8, "diameterMeters": 500,
                                 "playerCount": 2, "supporterCount": 0, "expiresAt": expires,
                                 "participantDeviceIds": [first, second]}]}

        for key in ("first", "second", "hunter", "spectator"):
            cookies = http.cookiejar.CookieJar()
            opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies))
            email, password = credentials[key]
            api.request("POST", "/session", urllib.parse.urlencode({"email": email, "password": password}).encode(),
                        credentials=None, opener=opener)
            socket = websocket.create_connection("ws://localhost:8083/api/socket", timeout=5, origin=api.BASE,
                                                 cookie="; ".join(cookie.name + "=" + cookie.value for cookie in cookies))
            sockets.append(socket)
        value = snapshot(1)
        assert api.request("PUT", endpoint, value)["revision"] == 1
        assert api.request("PUT", endpoint, value)["revision"] == 1
        for key, peer in (("first", second), ("second", first)):
            visible = {device["id"] for device in api.request("GET", "/devices", credentials=credentials[key])}
            assert peer in visible
            access = api.request("GET", endpoint + "/access", credentials=credentials[key])
            assert {grant["deviceId"] for grant in access} == {peer}
            assert all(set(grant) == {"deviceId", "expiresAt"} for grant in access)
        # A lease grants reads only, even if ordinary write restrictions are
        # relaxed on the generated account. All trackers here are fixtures.
        writable = {**users["first"], "readonly": False, "deviceReadonly": False}
        api.request("PUT", f"/users/{writable['id']}", writable)
        api.request("POST", "/commands/send", {"deviceId": second, "type": "custom",
                    "attributes": {"data": "proximity-audit"}}, credentials=credentials["first"], expected=400)
        api.request("PUT", f"/devices/{second}", devices["second"], credentials=credentials["first"], expected=400)
        api.request("DELETE", f"/devices/{second}", credentials=credentials["first"], expected=400)
        api.request("DELETE", f"/positions?deviceId={second}", credentials=credentials["first"], expected=400)
        api.request("POST", "/permissions", {"userId": writable["id"], "deviceId": second},
                    credentials=credentials["first"], expected=400)
        share_expiry = (datetime.now(timezone.utc) + timedelta(hours=1)).isoformat()
        api.request("POST", "/share/device", urllib.parse.urlencode({"deviceId": first,
                    "expiration": share_expiry}).encode(), credentials=credentials["first"])
        shared_email = users["first"]["email"] + ":" + devices["first"]["uniqueId"]
        user_ids.append(int(api.sql("SELECT id FROM tc_users WHERE email=" + api.literal(shared_email))))
        api.request("POST", "/share/device", urllib.parse.urlencode({"deviceId": second,
                    "expiration": share_expiry}).encode(), credentials=credentials["first"], expected=400)
        api.request("PUT", f"/users/{writable['id']}", users["first"])
        for key in ("hunter", "spectator"):
            circles = api.request("GET", endpoint + "/circles", credentials=credentials[key])
            assert len(circles) == 1 and circles[0]["playerCount"] == 2
            assert "participantDeviceIds" not in circles[0]
            api.request("GET", endpoint, credentials=credentials[key], expected=403)
        for key in ("first", "second", "supporter"):
            api.request("GET", endpoint + "/circles", credentials=credentials[key], expected=403)
            api.request("PUT", endpoint, value, credentials=credentials[key], expected=403)
        api.request("GET", f"/game-proximity/session/{other['id']}/circles", credentials=credentials["hunter"], expected=403)
        api.request("GET", endpoint, credentials=None, expected=401)
        api.request("PUT", endpoint, {**value, "generation": str(uuid4())}, expected=409)
        api.request("PUT", endpoint, {**value, "circles": [{**value["circles"][0], "diameterMeters": 600}]}, expected=409)
        occurred = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
        for alert_type in ("participantProximityEnter", "participantProximityExit"):
            api.request("POST", "/game-notifications", {"sessionDeviceId": session_id, "generation": generation,
                        "alert": {"id": alert_type, "type": alert_type, "audience": "user",
                                  "occurredAt": occurred, "timeZone": "UTC", "message": "Personal proximity notice",
                                  "recipientUserId": users["first"]["id"], "sourceDeviceId": first}})
        for key in ("first", "supporter"):
            personal = {"sessionDeviceId": session_id, "generation": generation,
                        "alert": {"id": "sharing-" + key, "type": "participantProximityConsequence", "audience": "user",
                                  "occurredAt": occurred, "timeZone": "UTC",
                                  "message": "Your approximate location is now being shared with hunters and spectators "
                                             "because you remained too close to another participant. Please move apart.",
                                  "recipientUserId": users[key]["id"], "sourceDeviceId": devices[key]["id"]}}
            assert api.request("POST", "/game-notifications", personal)["created"]
            assert not api.request("POST", "/game-notifications", personal)["created"]
        api.request("POST", "/game-notifications", {**personal, "alert": {
                    **personal["alert"], "id": "wrong-source", "sourceDeviceId": first}}, expected=403)
        api.request("POST", "/game-notifications", {"sessionDeviceId": session_id, "generation": generation,
                    "alert": {"id": "consequence", "type": "participantProximityConsequence", "audience": "hunters",
                              "occurredAt": occurred, "timeZone": "UTC", "message": "2 players are too close"}})
        for key in credentials:
            history = api.request("GET", f"/game-notifications?sessionDeviceId={session_id}", credentials=credentials[key])
            identifiers = {item["id"] for item in history["items"]}
            expected = ({"participantProximityEnter", "participantProximityExit", "sharing-first"} if key == "first"
                        else {"sharing-supporter"} if key == "supporter"
                        else {"consequence"} if key in {"hunter", "spectator"} else set())
            assert identifiers == expected, "Proximity notification audience isolation failed"
        for socket in sockets:
            deadline = time.monotonic() + 6
            while True:
                message = json.loads(socket.recv())
                if message.get("invalidate"):
                    break
                assert time.monotonic() < deadline
        if tool_source:
            audit_tool_delivery(session, users, devices, credentials, tool_source)
        current = api.request("GET", endpoint)["revision"]
        api.request("POST", "/permissions", {"userId": users["first"]["id"], "deviceId": second}, expected=204)
        short = snapshot(current + 1, 2)
        api.request("PUT", endpoint, short)
        assert api.request("GET", endpoint + "/access", credentials=credentials["first"]) == []
        deadline = time.monotonic() + 8
        while api.request("GET", endpoint)["grants"]:
            assert time.monotonic() < deadline, "Temporary proximity grants did not expire"
            time.sleep(0.2)
        assert second in {device["id"] for device in api.request("GET", "/devices", credentials=credentials["first"])}
        assert first not in {device["id"] for device in api.request("GET", "/devices", credentials=credentials["second"])}
        assert api.request("GET", endpoint + "/circles", credentials=credentials["hunter"]) == []
        revision = api.request("GET", endpoint)["revision"] + 1
        api.request("PUT", endpoint, snapshot(revision))
        membership = {"userId": users["second"]["id"], "deviceId": session_id}
        api.request("DELETE", "/permissions", membership, expected=204)
        assert api.request("GET", endpoint)["grants"] == []
        assert api.request("GET", endpoint)["circles"] == []
        api.request("POST", "/permissions", membership, expected=204)
        revision = api.request("GET", endpoint)["revision"] + 1
        api.request("PUT", endpoint, snapshot(revision))
        updated = {**users["second"], "attributes": {**users["second"]["attributes"], "traccarToolUserRole": "hunter"}}
        api.request("PUT", f"/users/{updated['id']}", updated)
        assert api.request("GET", endpoint)["grants"] == []
        assert api.request("GET", endpoint)["circles"] == []
        api.request("PUT", f"/users/{updated['id']}", users["second"])
        if not skip_restart:
            revision = api.request("GET", endpoint)["revision"] + 1
            api.request("PUT", endpoint, snapshot(revision, 2))
            subprocess.run(["docker", "compose", "--profile", "full", "restart", "traccar"], check=True,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            api.wait_for_server()
            assert api.request("GET", endpoint)["grants"] == []
            assert api.request("GET", endpoint)["circles"] == []
        revision = api.request("GET", endpoint)["revision"] + 1
        api.request("PUT", endpoint, snapshot(revision, 3600))
        session_state = api.request("GET", f"/devices/{session_id}")
        api.sql(f"UPDATE tc_devices SET attributes=JSON_REMOVE(attributes,'$.traccarToolGameState') WHERE id={session_id}")
        deadline = time.monotonic() + 8
        while first in {device["id"] for device in api.request("GET", "/devices", credentials=credentials["second"])}:
            assert time.monotonic() < deadline, "Invalid session retained a long-lived proximity lease"
            time.sleep(0.2)
        api.request("PUT", f"/devices/{session_id}", session_state)
        print("MySQL/HTTP proximity audit passed: scoped access, anonymous circles, independent permanent grants, "
              "read-only/non-shareable leases, revision/generation conflicts, expiry, and identity/session changes"
              + ("." if skip_restart else ", and server restart."))
    finally:
        for socket in sockets:
            socket.close()
        api.wait_for_server()
        for device_id in device_ids:
            api.request("DELETE", f"/devices/{device_id}", expected=204)
        for user_id in user_ids:
            api.request("DELETE", f"/users/{user_id}", expected=204)
        try:
            api.request("DELETE", f"/users/{admin_id}", expected=204)
        finally:
            api.sql(f"DELETE FROM tc_users WHERE id={admin_id} AND email=" + api.literal(api.EMAIL) + ";")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tool-source")
    parser.add_argument("--skip-restart", action="store_true", help="Leave a running development server uninterrupted")
    args = parser.parse_args()
    main(args.tool_source, args.skip_restart)
