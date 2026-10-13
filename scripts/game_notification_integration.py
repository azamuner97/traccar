"""Local MySQL/HTTP integration audit. Creates and removes only its own fixtures.

Run after the full Docker build, using Python with websocket-client installed.
Never calls the push endpoint, reads existing accounts, or resets volumes.
Pass --tool-source /path/to/Traccar-Tool/src to also verify actual Tool monitoring.
"""

import argparse
import base64
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
import hashlib
import http.client
import http.cookiejar
import json
import secrets
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from uuid import uuid4

import websocket


BASE = "http://localhost:8083"
PREFIX = "notification-audit-" + uuid4().hex
PASSWORD = secrets.token_hex(24)
EMAIL = PREFIX + "@example.invalid"


def literal(value):
    return "CONVERT(0x" + str(value).encode().hex() + " USING utf8mb4)"


def sql(statement):
    result = subprocess.run([
        "docker", "compose", "exec", "-T", "database", "sh", "-c",
        'MYSQL_PWD="$MYSQL_PASSWORD" mysql -N -B -u "$MYSQL_USER" "$MYSQL_DATABASE"',
    ], input=statement, text=True, capture_output=True, check=False)
    if result.returncode:
        raise RuntimeError("Local integration fixture SQL failed (credentials/output withheld)")
    return result.stdout.strip()


def request(method, path, body=None, *, credentials=(EMAIL, PASSWORD), expected=200, opener=None):
    headers = {"Accept": "application/json"} if path.startswith("/reports/") else {}
    if credentials:
        headers["Authorization"] = "Basic " + base64.b64encode(":".join(credentials).encode()).decode()
    if isinstance(body, dict):
        headers["Content-Type"] = "application/json"
        body = json.dumps(body).encode()
    message = urllib.request.Request(BASE + "/api" + path, data=body, headers=headers, method=method)
    try:
        response = (opener.open if opener else urllib.request.urlopen)(message, timeout=15)
        status, data = response.status, response.read()
    except urllib.error.HTTPError as error:
        status, data = error.code, error.read()
    assert status == expected, f"{method} {path.split('?')[0]} returned {status}, expected {expected}"
    if not data or status != 200:
        return None
    try:
        return json.loads(data)
    except json.JSONDecodeError:
        return data.decode()


def wait_for_server():
    for attempt in range(60):
        try:
            request("GET", "/health", credentials=None)
            return
        except (OSError, http.client.HTTPException, AssertionError):
            if attempt == 59:
                raise
            time.sleep(1)


def audit_geofence_monitoring(session_id, source_ids, generated_users, sockets, geofence_ids, tool_source):
    """Generate real events without native subscriptions, optionally poll with the Tool."""
    geofence = request("POST", "/geofences", {
        "name": PREFIX + "-monitoring",
        "area": "POLYGON ((47.4 8.4, 47.4 8.6, 47.6 8.6, 47.6 8.4, 47.4 8.4))",
    })
    geofence_ids.append(geofence["id"])
    for device_id in source_ids.values():
        request("POST", "/permissions", {
            "deviceId": device_id, "geofenceId": geofence["id"]}, expected=204)
    for user in generated_users.values():
        request("POST", "/permissions", {
            "userId": user["id"], "geofenceId": geofence["id"]}, expected=204)
    assert sql("SELECT COUNT(*) FROM tc_device_notification WHERE deviceid IN ("
               + ",".join(map(str, source_ids.values())) + ")") == "0"
    assert sql("SELECT COUNT(*) FROM tc_user_notification WHERE userid IN ("
               + ",".join(str(user["id"]) for user in generated_users.values()) + ")") == "0"
    # MySQL's native eventTime column can round to seconds. Include that second
    # rather than excluding the first upload behind a microsecond query cutoff.
    started = datetime.now(timezone.utc) - timedelta(seconds=1)

    def native_events():
        parameters = urllib.parse.urlencode({
            "deviceId": list(source_ids.values()),
            "type": ["geofenceEnter", "geofenceExit"],
            "from": started.isoformat(),
            "to": datetime.now(timezone.utc).isoformat(),
        }, doseq=True)
        return request("GET", "/reports/events?" + parameters)

    def upload(latitude):
        for key in source_ids:
            parameters = urllib.parse.urlencode({
                "id": PREFIX + "-" + key,
                "lat": latitude, "lon": 8.5, "speed": 0,
                "timestamp": datetime.now(timezone.utc).isoformat(),
            })
            with urllib.request.urlopen("http://localhost:5056/?" + parameters, timeout=15) as response:
                assert response.status == 200

    for latitude, event_type in ((47.5, "geofenceEnter"), (48.5, "geofenceExit")):
        upload(latitude)
        deadline = time.monotonic() + 15
        while True:
            events = native_events()
            detected = {event["deviceId"] for event in events
                        if event["type"] == event_type and event["geofenceId"] == geofence["id"]}
            if detected == set(source_ids.values()):
                break
            assert time.monotonic() < deadline, f"Missing {event_type} reports without native links: {detected}"
            time.sleep(0.1)
    assert len(events) == 8 and all(event["id"] > 0 for event in events)
    # The hunter socket still receives positions, but no native events.
    deadline = time.monotonic() + 15
    while True:
        message = json.loads(sockets["hunter1"].recv())
        assert not message.get("events"), "Unexpected native geofence alert without subscription"
        if any(position["deviceId"] == source_ids["hunter1"] and position["latitude"] == 48.5
               for position in message.get("positions", [])):
            break
        assert time.monotonic() < deadline, "Hunter position websocket did not receive the exit position"

    if tool_source:
        sys.path.insert(0, tool_source)
        import requests
        from traccar_game_manager.application.monitoring.service import MonitoringService
        from traccar_game_manager.infrastructure.platform.runtime import SystemClock
        from traccar_game_manager.infrastructure.traccar.client import TraccarClient

        class Scheduler:
            def call_later(self, delay_ms, callback):
                return object()

            def cancel(self, handle):
                pass

        received = []
        with requests.Session() as transport:
            client = TraccarClient(BASE, "http://localhost:5056", EMAIL, PASSWORD, transport=transport)
            service = MonitoringService(
                client, SystemClock(), Scheduler(), stream_enabled=False,
                thread_factory=threading.Thread,
                event_sink=lambda kind, payload: received.append((kind, payload)),
                persist=lambda snapshot: None,
            )
            service.configure(
                participant_ids=(source_ids["player"], source_ids["supporter"]),
                hunter_ids=(source_ids["hunter1"], source_ids["hunter2"]),
                roles={device_id: generated_users[key]["attributes"]["traccarToolUserRole"]
                       for key, device_id in source_ids.items()},
                geofences=({**geofence, "linked_device_ids": list(source_ids.values())},),
            )
            service.running = service.connected = True
            service.last_event_poll_utc = started
            service.last_pong_utc = datetime.now(timezone.utc)
            service.apply_payload(message)
            admin_socket = None
            try:
                assert service.poll_rest(), "Healthy websocket must not suppress REST event polling"
                service._rest_thread.join(timeout=15)
                service.drain_queue()
                normalized = [payload for kind, payload in received if kind == "geofence_event"]
                assert {event["event_id"] for event in normalized} == {event["id"] for event in events}
                assert sum(event["event_type"] == "geofenceExit" for event in normalized) == 4
                service.apply_payload({"events": events})
                assert service.poll_rest()
                service._rest_thread.join(timeout=15)
                service.drain_queue()
                assert sum(kind == "geofence_event" for kind, payload in received) == 8
                subscription_ids = client.ensure_monitoring_notifications(
                    session_id, source_ids.values(), lambda message: None)
                assert len(subscription_ids) == 3
                assert client.ensure_monitoring_notifications(
                    session_id, source_ids.values(), lambda message: None) == subscription_ids
                admin = request("POST", "/session", urllib.parse.urlencode({
                    "email": EMAIL, "password": PASSWORD}).encode(), credentials=None)
                for notification_id in subscription_ids:
                    native = request("GET", "/notifications/" + str(notification_id))
                    assert native["notificators"] == "web" and not native["always"]
                    permissions = request("GET", "/permissions?userId=0&notificationId=" + str(notification_id))
                    assert [{key.casefold(): value for key, value in link.items()} for link in permissions] == [
                        {"userid": admin["id"], "notificationid": notification_id}]
                admin_socket = websocket.create_connection(
                    "ws://localhost:8083/api/socket", timeout=10, origin=BASE,
                    cookie=client.get_websocket_cookie_header())
                native_ids = set()
                for latitude, event_type in ((47.5, "geofenceEnter"), (48.5, "geofenceExit")):
                    upload(latitude)
                    detected = set()
                    deadline = time.monotonic() + 20
                    while detected != set(source_ids.values()):
                        message = json.loads(admin_socket.recv())
                        for event in message.get("events", []):
                            assert event["type"] == event_type
                            assert event["deviceId"] in source_ids.values()
                            native_ids.add(event["id"])
                            detected.add(event["deviceId"])
                        service.apply_payload(message)
                        assert time.monotonic() < deadline, "Admin websocket events were not delivered"
                assert len(native_ids) == 8
                assert sum(kind == "geofence_event" for kind, payload in received) == 16
                assert service.poll_rest()
                service._rest_thread.join(timeout=15)
                service.drain_queue()
                assert sum(kind == "geofence_event" for kind, payload in received) == 16
                for socket in sockets.values():
                    socket.settimeout(0.2)
                    try:
                        while True:
                            assert not json.loads(socket.recv()).get("events"), "Native monitoring leaked to a player"
                    except websocket.WebSocketTimeoutException:
                        pass
                    finally:
                        socket.settimeout(10)
            finally:
                service.stop()
                if admin_socket is not None:
                    admin_socket.close()
                client.delete_monitoring_notifications(session_id, lambda message: None)


def main(tool_source=None):
    wait_for_server()
    device_ids, group_ids, user_ids, sockets, geofence_ids = [], [], [], [], []
    salt = secrets.token_bytes(24)
    digest = hashlib.pbkdf2_hmac("sha256", PASSWORD.encode(), salt, 200000, 24).hex().upper()
    password_hash = "PBKDF2WithHmacSHA256$200000$" + digest
    sql("INSERT INTO tc_users(name,email,hashedpassword,salt,administrator,attributes) VALUES("
        + ",".join([literal(PREFIX), literal(EMAIL), literal(password_hash), literal(salt.hex().upper()),
                    "1", literal("{}")]) + ");")
    admin_id = int(sql("SELECT id FROM tc_users WHERE email=" + literal(EMAIL) + ";"))
    user_ids.append(admin_id)
    try:
        request("GET", "/health", credentials=None)
        request("GET", "/game-notifications?sessionDeviceId=1", credentials=None, expected=401)
        for suffix in ("session", "other-session"):
            device = request("POST", "/devices", {"name": PREFIX + suffix, "uniqueId": PREFIX + suffix,
                "attributes": {"traccarToolGameState": True}})
            device_ids.append(device["id"])
        session_id = device_ids[0]
        hunter_group = request("POST", "/groups", {"name": PREFIX + "-hunters"})
        group_ids.append(hunter_group["id"])
        source_ids = {}
        for key, group_id in (
                ("player", 0), ("supporter", 0),
                ("hunter1", hunter_group["id"]), ("hunter2", hunter_group["id"])):
            source = request("POST", "/devices", {
                "name": PREFIX + "-" + key,
                "uniqueId": PREFIX + "-" + key,
                "groupId": group_id,
            })
            source_ids[key] = source["id"]
            device_ids.append(source["id"])
        endpoint = "/game-notifications/session/" + str(session_id)
        initial = request("POST", endpoint, {})
        assert request("POST", endpoint, {}) == initial
        assert initial["version"] == 2
        generation = initial["generation"]
        generated, generated_users, socket_by_key = {}, {}, {}
        for key, role, source_id in (
                ("player", "player", source_ids["player"]),
                ("supporter", "supporter", source_ids["supporter"]),
                ("hunter1", "hunter", source_ids["hunter1"]),
                ("hunter2", "hunter", source_ids["hunter2"]),
                ("spectator", "spectator", None),
                ("unrelated", "unrelated", None)):
            email = PREFIX + "-" + key + "@example.invalid"
            attributes = {"traccarToolUserRole": role, "traccarToolSessionDeviceId": session_id}
            if source_id is not None:
                attributes["traccarToolAssignedDeviceId"] = source_id
            user = request("POST", "/users", {"name": PREFIX + key, "email": email, "password": PASSWORD,
                "readonly": True, "attributes": attributes})
            user_ids.append(user["id"])
            generated_users[key] = user
            request("POST", "/permissions", {"userId": user["id"], "deviceId": session_id}, expected=204)
            if key in ("hunter1", "hunter2"):
                request("POST", "/permissions", {
                    "userId": user["id"], "groupId": hunter_group["id"]}, expected=204)
            elif source_id is not None:
                request("POST", "/permissions", {
                    "userId": user["id"], "deviceId": source_id}, expected=204)
            credentials = (email, PASSWORD)
            request("GET", "/game-notifications?sessionDeviceId=" + str(session_id),
                credentials=credentials, expected=403 if role == "unrelated" else 200)
            request("GET", "/game-notifications?sessionDeviceId=" + str(device_ids[1]),
                credentials=credentials, expected=403)
            request("POST", endpoint, {}, credentials=credentials, expected=403)
            if role != "unrelated":
                generated[key] = credentials
                cookies = http.cookiejar.CookieJar()
                opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies))
                request("POST", "/session", urllib.parse.urlencode({"email": email, "password": PASSWORD}).encode(),
                    credentials=None, opener=opener)
                socket = websocket.create_connection("ws://localhost:8083/api/socket", timeout=10,
                    origin=BASE, cookie="; ".join(cookie.name + "=" + cookie.value for cookie in cookies))
                sockets.append(socket)
                socket_by_key[key] = socket
        for hunter_key in ("hunter1", "hunter2"):
            visible = request("GET", "/devices", credentials=generated[hunter_key])
            visible_ids = {device["id"] for device in visible}
            assert {source_ids["hunter1"], source_ids["hunter2"]} <= visible_ids
        audit_geofence_monitoring(
            session_id, source_ids, generated_users, socket_by_key, geofence_ids, tool_source)
        alert = {"id": "live", "type": "notificationTest", "audience": "session",
            "occurredAt": datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z"),
            "timeZone": "UTC", "message": "[12:00:00] Test notification: check that your phone plays a sound."}
        payload = {"sessionDeviceId": session_id, "generation": generation, "alert": alert}
        result = request("POST", "/game-notifications", payload)
        assert result["created"] and datetime.fromisoformat(result["item"]["occurredAt"]) == datetime.fromisoformat(alert["occurredAt"])
        for socket in sockets:
            while "game-notifications" not in json.loads(socket.recv()).get("invalidate", []):
                pass
        assert not request("POST", "/game-notifications", payload)["created"]
        request("POST", "/game-notifications", {**payload, "alert": {**alert, "message": "conflict"}}, expected=409)
        targeted_ids = {}
        for key, alert_type in (
                ("player", "liveTrackingStarted"), ("supporter", "liveTrackingStopped"),
                ("hunter1", "liveTrackingTargetsChanged"), ("hunter2", "geofenceExit")):
            target_id = "exit-" + key
            targeted_ids[key] = target_id
            targeted = {
                "id": target_id,
                "type": alert_type,
                "audience": "user",
                "occurredAt": datetime.now(timezone.utc).isoformat(
                    timespec="milliseconds").replace("+00:00", "Z"),
                "timeZone": "UTC",
                "message": "[12:00:00] " + key + " left geofence Audit.",
                "recipientUserId": generated_users[key]["id"],
                "sourceDeviceId": source_ids[key],
            }
            created = request("POST", "/game-notifications", {**payload, "alert": targeted})
            assert created["created"] and created["item"]["recipientUserId"] == generated_users[key]["id"]
            while "game-notifications" not in json.loads(socket_by_key[key].recv()).get("invalidate", []):
                pass
            for other_key, socket in socket_by_key.items():
                if other_key == key:
                    continue
                socket.settimeout(0.2)
                try:
                    message = json.loads(socket.recv())
                    assert "game-notifications" not in message.get("invalidate", [])
                except websocket.WebSocketTimeoutException:
                    pass
                finally:
                    socket.settimeout(10)
        for key in ("player", "supporter", "hunter1", "hunter2", "spectator"):
            private_page = request("GET", "/game-notifications?" + urllib.parse.urlencode({
                "sessionDeviceId": session_id, "generation": generation, "limit": 100,
            }), credentials=generated[key])
            private_ids = {record["id"] for record in private_page["items"]}
            expected_private = {targeted_ids[key]} if key in targeted_ids else set()
            assert private_ids.intersection(targeted_ids.values()) == expected_private
        # Reassignment, role changes and revoked tracker permission must never
        # return records the web client's current personal policy would reject.
        player = generated_users["player"]
        query = "/game-notifications?sessionDeviceId=" + str(session_id)
        for attributes in (
                {**player["attributes"], "traccarToolAssignedDeviceId": source_ids["supporter"]},
                {**player["attributes"], "traccarToolUserRole": "spectator"}):
            request("PUT", "/users/" + str(player["id"]), {**player, "attributes": attributes})
            page = request("GET", query, credentials=generated["player"])
            assert all(record["audience"] == "session" for record in page["items"])
        request("PUT", "/users/" + str(player["id"]), player)
        permission = {"userId": player["id"], "deviceId": source_ids["player"]}
        request("DELETE", "/permissions", permission, expected=204)
        page = request("GET", query, credentials=generated["player"])
        assert all(record["audience"] == "session" for record in page["items"])
        request("POST", "/permissions", permission, expected=204)
        page = request("GET", query, credentials=generated["player"])
        assert targeted_ids["player"] in {record["id"] for record in page["items"]}
        for audience in ("nonHunters", "hunters"):
            role_alert = {**alert, "id": "speedhunt-" + audience, "type": "speedhunt", "audience": audience,
                "message": "[12:00:00] Speedhunt: Ping 2 out of 4 sent."
                    + (" Participant: Älice speedhunt identity." if audience == "hunters" else "")}
            assert request("POST", "/game-notifications", {**payload, "alert": role_alert})["created"]
            assert not request("POST", "/game-notifications", {**payload, "alert": role_alert})["created"]
        for key in ("player", "supporter", "hunter1", "hunter2", "spectator"):
            page = request("GET", query, credentials=generated[key])
            role_records = [record for record in page["items"] if record["id"].startswith("speedhunt-")]
            assert len(role_records) == 1
            named = key.startswith("hunter") or key == "spectator"
            assert role_records[0]["audience"] == ("hunters" if named else "nonHunters")
            assert ("Älice speedhunt identity" in json.dumps(page, ensure_ascii=False)) == named
        received_ids = {}
        for key, audience, source_key in (
                ("player", "livePlayer", "supporter"), ("supporter", "liveSupporter", "player"),
                ("hunter1", "liveHunter", "player"), ("hunter2", "liveHunter", "supporter"),
                ("spectator", "liveSpectator", "player")):
            received_ids[key] = set()
            types = ("livePingReceived", "livePingEnded", "livePingTargetsChanged") if key == "spectator" else (
                "livePingReceived", "livePingEnded")
            for alert_type in types:
                received = {**alert, "id": "received-" + key + "-" + alert_type, "type": alert_type,
                    "audience": audience, "recipientUserId": generated_users[key]["id"],
                    "sourceDeviceId": source_ids[source_key],
                    "message": "[12:00:00] Live ping from Älice. Reason: Public transport."}
                received_ids[key].add(received["id"])
                assert request("POST", "/game-notifications", {**payload, "alert": received})["created"]
                assert not request("POST", "/game-notifications", {**payload, "alert": received})["created"]
                request("POST", "/game-notifications", {**payload, "alert": {
                    **received, "id": "received-invalid", "audience": "user"}}, expected=400)
            request("POST", "/game-notifications", {**payload, "alert": {
                **received, "id": "received-invalid-role", "audience": "livePlayer" if key != "player" else "liveHunter",
                "type": "livePingReceived"}}, expected=403)
            request("POST", "/game-notifications", {**payload, "alert": {
                **received, "id": "received-invalid-source", "sourceDeviceId": source_ids["hunter1"],
                "type": "livePingReceived"}}, expected=403)
        all_received_ids = set().union(*received_ids.values())
        for key in received_ids:
            private_page = request("GET", query, credentials=generated[key])
            assert {record["id"] for record in private_page["items"]}.intersection(all_received_ids) == received_ids[key]
        # Addressed destination history survives tracker reassignment but not a role change.
        player = generated_users["player"]
        request("PUT", "/users/" + str(player["id"]), {**player, "attributes": {
            **player["attributes"], "traccarToolAssignedDeviceId": source_ids["supporter"]}})
        assert received_ids["player"] <= {record["id"] for record in request("GET", query, credentials=generated["player"])["items"]}
        request("PUT", "/users/" + str(player["id"]), {**player, "attributes": {
            **player["attributes"], "traccarToolUserRole": "hunter"}})
        assert not received_ids["player"].intersection(record["id"] for record in request("GET", query, credentials=generated["player"])["items"])
        request("PUT", "/users/" + str(player["id"]), player)
        for invalid_type in ("geofenceEnter", "deviceOverspeed"):
            request("POST", "/game-notifications", {**payload, "alert": {
                **alert,
                "id": "invalid-" + invalid_type,
                "type": invalid_type,
                "audience": "user",
                "recipientUserId": generated_users["player"]["id"],
                "sourceDeviceId": source_ids["player"],
            }}, expected=400)
        # MySQL's default text collation must not merge opaque, case-only IDs.
        for alert_id in ("CaseId", "caseid"):
            case_payload = {**payload, "alert": {**alert, "id": alert_id}}
            assert request("POST", "/game-notifications", case_payload)["created"]
            assert not request("POST", "/game-notifications", case_payload)["created"]
        for zone in ("GMT+02:00", "+02:00", "SystemV/EST5EDT"):
            request("POST", "/game-notifications", {**payload, "alert": {**alert, "timeZone": zone}}, expected=400)
        request("POST", "/game-notifications", {**payload, "alert": {**alert, "occurredAt": "1900-01-01T00:00:00Z"}}, expected=400)
        # Full history exceeds both previous limits; listing never generates pushes.
        for index in range(205):
            record = {**alert, "id": "old-" + str(index), "occurredAt": "2026-01-01T12:00:00.123Z"}
            request("POST", "/game-notifications", {**payload, "alert": record})
        cursor, ids = "0", []
        while True:
            page = request("GET", "/game-notifications?" + urllib.parse.urlencode({
                "sessionDeviceId": session_id, "generation": generation, "after": cursor}))
            assert len(page["items"]) <= 100
            ids.extend(record["id"] for record in page["items"])
            cursor = page["nextCursor"]
            if not page["hasMore"]:
                break
        assert len(ids) == len(set(ids)) == 225
        request("DELETE", "/positions?" + urllib.parse.urlencode({"deviceId": session_id,
            "from": "2020-01-01T00:00:00Z", "to": "2030-01-01T00:00:00Z"}), expected=204)
        assert sql("SELECT COUNT(*) FROM tc_game_notifications WHERE sessiondeviceid=" + str(session_id)) == "225"
        for socket in sockets:
            socket.close()
        sockets.clear()
        subprocess.run(["docker", "compose", "--profile", "full", "restart", "traccar"],
            check=True, capture_output=True)
        wait_for_server()
        recovered = request("GET", "/game-notifications?" + urllib.parse.urlencode({
            "sessionDeviceId": session_id, "generation": generation, "limit": 500}), credentials=generated["player"])
        assert recovered["generation"] == generation and len(recovered["items"]) == 212
        supporter_id = generated_users["supporter"]["id"]
        request("DELETE", "/users/" + str(supporter_id), expected=204)
        user_ids.remove(supporter_id)
        assert sql("SELECT COUNT(*) FROM tc_game_notifications WHERE sessiondeviceid=" + str(session_id)) == "222"
        after_recipient_delete = request("GET", "/game-notifications?" + urllib.parse.urlencode({
            "sessionDeviceId": session_id, "generation": generation, "limit": 500}))
        assert "exit-supporter" not in {record["id"] for record in after_recipient_delete["items"]}
        assert "live" in {record["id"] for record in after_recipient_delete["items"]}
        request("GET", "/game-notifications?sessionDeviceId=" + str(session_id) + "&limit=501", expected=400)
        # A stale ordinary Device PUT must preserve the protected metadata.
        device = request("GET", "/devices?id=" + str(session_id))[0]
        device["attributes"] = {"traccarToolGameState": True, "operatorNote": "retained"}
        updated = request("PUT", "/devices/" + str(session_id), device)
        assert updated["attributes"]["traccarToolNotificationGeneration"] == generation
        invalid_device = {**updated, "uniqueId": PREFIX + "other-session",
            "attributes": {**updated["attributes"], "operatorNote": "must-rollback"}}
        request("PUT", "/devices/" + str(session_id), invalid_device, expected=400)
        unchanged = request("GET", "/devices?id=" + str(session_id))[0]
        assert unchanged["attributes"]["operatorNote"] == "retained"
        reset = {"expectedGeneration": generation, "replacementGeneration": str(uuid4())}
        race = {**payload, "alert": {**alert, "id": "racing"}}
        def insert_racing():
            try:
                return request("POST", "/game-notifications", race)
            except AssertionError as error:
                assert "returned 409" in str(error)
        with ThreadPoolExecutor(max_workers=2) as pool:
            insertion = pool.submit(insert_racing)
            replacement = pool.submit(request, "POST", endpoint + "/reset", reset)
            insertion.result()
            replacement.result()
        assert request("POST", endpoint + "/reset", reset)["generation"] == reset["replacementGeneration"]
        request("POST", "/game-notifications", payload, expected=409)
        assert not request("GET", "/game-notifications?sessionDeviceId=" + str(session_id))["items"]
        request("POST", "/game-notifications", {**payload, "generation": reset["replacementGeneration"]})
        request("DELETE", "/devices/" + str(session_id), expected=204)
        device_ids.remove(session_id)
        assert sql("SELECT COUNT(*) FROM tc_game_notifications WHERE sessiondeviceid=" + str(session_id)) == "0"
        print("MySQL/HTTP notification audit passed: targeting authorization, private role sockets, hunter-group "
              "visibility, real geofence events without native links, timestamps, case-sensitive IDs, "
              "hunter/spectator Speedhunt names, live recipients, 225-row pagination, GPS cleanup, server restart, "
              "idempotency, recipient/session deletion cascades, atomic device saves and reset races.")
        if tool_source:
            print("Tool monitoring audit passed: healthy position websocket, enter/exit polling for player, "
                  "supporter and two hunters, admin-only web subscriptions, no generated-user native events, "
                  "original event IDs, and report/websocket deduplication.")
    finally:
        for socket in sockets:
            socket.close()
        wait_for_server()
        for device_id in device_ids:
            request("DELETE", "/devices/" + str(device_id), expected=204)
        for geofence_id in geofence_ids:
            request("DELETE", "/geofences/" + str(geofence_id), expected=204)
        for group_id in group_ids:
            request("DELETE", "/groups/" + str(group_id), expected=204)
        for user_id in user_ids[1:]:
            request("DELETE", "/users/" + str(user_id), expected=204)
        # Exact test admin ID/email, never a broad production/user cleanup.
        try:
            request("DELETE", "/users/" + str(admin_id), expected=204)
        finally:
            sql("DELETE FROM tc_users WHERE id=" + str(admin_id) + " AND email=" + literal(EMAIL) + ";")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tool-source", help="Traccar Tool src directory for the real monitoring audit")
    main(parser.parse_args().tool_source)
