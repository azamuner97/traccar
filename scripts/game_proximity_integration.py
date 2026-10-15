"""Local MySQL/HTTP proximity audit with isolated fixtures and no native pushes."""

import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import http.cookiejar
import json
import secrets
import subprocess
import sys
import time
import urllib.request
import urllib.parse
from uuid import uuid4

import websocket
import game_notification_integration as api


def main(tool_source=None):
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
                              "uniqueId": api.PREFIX + "-session", "attributes": {"traccarToolGameState": True}})
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
            email = api.PREFIX + "-" + key + "@example.invalid"
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
        api.request("POST", "/game-notifications", {"sessionDeviceId": session_id, "generation": generation,
                    "alert": {"id": "consequence", "type": "participantProximityConsequence", "audience": "hunters",
                              "occurredAt": occurred, "timeZone": "UTC", "message": "2 players are too close"}})
        for key in credentials:
            history = api.request("GET", f"/game-notifications?sessionDeviceId={session_id}", credentials=credentials[key])
            identifiers = {item["id"] for item in history["items"]}
            expected = ({"participantProximityEnter", "participantProximityExit"} if key == "first"
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
            sys.path.insert(0, tool_source)
            import requests
            from traccar_game_manager.application.live_session.proximity import ProximityPublicationService
            from traccar_game_manager.application.monitoring.proximity import ParticipantProximityMonitor
            from traccar_game_manager.domain.session.credentials import normalize_login_credentials
            from traccar_game_manager.infrastructure.traccar.client import TraccarClient

            client = TraccarClient(api.BASE, "http://localhost:5056", api.EMAIL, api.PASSWORD, transport=requests)
            tool_session = {"devices": {"game_state": {"id": session_id}, "real": {
                device["uniqueId"]: device["id"] for device in devices.values()}},
                "device_roster": {devices[key]["uniqueId"]: {"role": "player", "number": number,
                                 "display_name": key} for key, number in (("first", 1), ("second", 2))},
                "users": {users[key]["email"]: users[key]["id"] for key in ("first", "second")},
                "login_credentials": normalize_login_credentials({users[key]["email"]: {
                    "role": "player", "number": number} for key, number in (("first", 1), ("second", 2))})}
            monitor = ParticipantProximityMonitor()
            monitor.configure({"player_player": True, "distance_m": 100.0, "waiting_minutes": 0.01,
                               "access_grace_minutes": 0.01, "circle_diameter_m": 500.0})
            now = datetime.now(timezone.utc)
            positions = {member: {"deviceId": member, "latitude": 47.0, "longitude": 8.0,
                                  "fixTime": now.isoformat()} for member in (first, second)}
            roles = {first: "player", second: "player"}
            monitor.evaluate(positions, roles, set(), now, timedelta(minutes=5))
            monitor.evaluate(positions, roles, set(), now + timedelta(seconds=1), timedelta(minutes=5))
            publisher = ProximityPublicationService(client)
            publisher.publish(tool_session, monitor.publication(now + timedelta(seconds=1)))
            assert len(client.get_proximity_state(session_id)["circles"]) == 1
            publisher.publish(tool_session, {"pairs": [], "circles": []})
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
        revision = api.request("GET", endpoint)["revision"] + 1
        api.request("PUT", endpoint, snapshot(revision, 2))
        subprocess.run(["docker", "compose", "--profile", "full", "restart", "traccar"], check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        api.wait_for_server()
        assert api.request("GET", endpoint)["grants"] == []
        assert api.request("GET", endpoint)["circles"] == []
        print("MySQL/HTTP proximity audit passed: scoped access, anonymous circles, independent permanent grants, "
              "revision/generation conflicts, real Tool publication, expiry, identity changes, and server restart.")
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
    main(parser.parse_args().tool_source)
