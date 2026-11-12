"""Local MySQL/HTTP notification audit of game data model 2. Creates and removes only its own fixtures.

Run against the full Docker profile (``docker compose --profile full up -d --build --wait``) with Python that has
``websocket-client`` (and ``requests`` for ``--tool-source``), for example Traccar Tool's virtual environment.
``TRACCAR_AUDIT_BASE`` and ``TRACCAR_AUDIT_UPLOAD`` override the server (default ``http://localhost:8083``) and the
tracker upload endpoint (default ``http://localhost:5056``). Never calls the push endpoint, reads existing accounts,
or resets volumes. The server restart uses ``docker compose --profile full restart traccar``.

This module also holds the helpers the other audits import (``import game_notification_integration as api``):
an isolated temporary administrator, the controller lease, game sessions, members and bindings, effects deliveries,
member WebSockets and the server restart. Pass ``--tool-source /path/to/Traccar-Tool/src`` to also verify the
Tool's monitoring against the server.
"""

import argparse
import base64
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
import hashlib
import http.client
import http.cookiejar
import json
import os
from pathlib import Path
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


BASE = os.environ.get("TRACCAR_AUDIT_BASE", "http://localhost:8083").rstrip("/")
UPLOAD = os.environ.get("TRACCAR_AUDIT_UPLOAD", "http://localhost:5056").rstrip("/")
SOCKET_URL = ("wss" if BASE.startswith("https") else "ws") + "://" + BASE.split("://", 1)[1] + "/api/socket"
COMPOSE_DIRECTORY = Path(__file__).resolve().parent.parent
PREFIX = "notification-audit-" + uuid4().hex
PASSWORD = secrets.token_hex(24)
EMAIL = PREFIX + "@example.invalid"
SCHEMA_VERSION = 5


def literal(value):
    return "CONVERT(0x" + str(value).encode().hex() + " USING utf8mb4)"


def sql(statement):
    result = subprocess.run([
        "docker", "compose", "exec", "-T", "database", "sh", "-c",
        'MYSQL_PWD="$MYSQL_PASSWORD" mysql -N -B -u "$MYSQL_USER" "$MYSQL_DATABASE"',
    ], input=statement, text=True, capture_output=True, check=False, cwd=COMPOSE_DIRECTORY)
    if result.returncode:
        raise RuntimeError("Local integration fixture SQL failed (credentials/output withheld)")
    return result.stdout.strip()


def create_user(name, email, password, administrator):
    """A fixture account written directly, so the audit never needs an existing administrator's password."""
    salt = secrets.token_bytes(24)
    digest = hashlib.pbkdf2_hmac("sha256", password.encode(), salt, 200000, 24).hex().upper()
    sql("INSERT INTO tc_users(name,email,hashedpassword,salt,administrator,attributes) VALUES("
        + ",".join([literal(name), literal(email), literal("PBKDF2WithHmacSHA256$200000$" + digest),
                    literal(salt.hex().upper()), "1" if administrator else "0", literal("{}")]) + ");")
    return int(sql("SELECT id FROM tc_users WHERE email=" + literal(email) + ";"))


def create_admin():
    """The audit's own temporary administrator (``EMAIL``/``PASSWORD``)."""
    return create_user(PREFIX, EMAIL, PASSWORD, True)


def delete_admin(admin_id):
    """Removes exactly the audit's administrator, never a broad cleanup."""
    try:
        request("DELETE", "/users/" + str(admin_id), expected=204)
    finally:
        sql("DELETE FROM tc_users WHERE id=" + str(int(admin_id)) + " AND email=" + literal(EMAIL) + ";")


# The audit holds the game controller lease like a Tool instance; fenced admin
# requests carry its epoch.
EPOCH_HEADER = "X-Game-Controller-Epoch"
CONTROLLER = {"epoch": None, "instance": "audit-" + uuid4().hex[:24], "stop": None}


def request(method, path, body=None, *, credentials=(EMAIL, PASSWORD), expected=200, opener=None, epoch=None):
    headers = {"Accept": "application/json"} if path.startswith("/reports/") else {}
    if credentials:
        headers["Authorization"] = "Basic " + base64.b64encode(":".join(credentials).encode()).decode()
    if epoch is None and credentials == (EMAIL, PASSWORD):
        epoch = CONTROLLER["epoch"]
    if epoch is not None and epoch is not False:
        headers[EPOCH_HEADER] = str(epoch)
    if isinstance(body, (dict, list)):
        headers["Content-Type"] = "application/json"
        body = json.dumps(body).encode()
    message = urllib.request.Request(BASE + "/api" + path, data=body, headers=headers, method=method)
    try:
        response = (opener.open if opener else urllib.request.urlopen)(message, timeout=15)
        status, data = response.status, response.read()
    except urllib.error.HTTPError as error:
        status, data = error.code, error.read()
    if status != expected:
        detail = data.decode(errors="replace").strip().splitlines()[0][:300] if data else ""
        raise AssertionError(f"{method} {path.split('?')[0]} returned {status}, expected {expected}: {detail}")
    if not data or status != 200:
        return None
    try:
        return json.loads(data)
    except json.JSONDecodeError:
        return data.decode()


def refusal(method, path, body=None, *, expected=409, **kwargs):
    """The first line of a refusal's error text."""
    try:
        request(method, path, body, expected=-1, **kwargs)
    except AssertionError as error:
        text = str(error)
        assert f"returned {expected}," in text, text
        return text.split(": ", 1)[1] if ": " in text else ""
    raise AssertionError("unreachable")


def acquire_controller():
    """Acquire the lease (never by force) and keep renewing it in the background."""
    try:
        result = request("POST", "/game-controller/acquire",
                         {"instanceId": CONTROLLER["instance"], "label": PREFIX}, epoch=False)
    except AssertionError as error:
        raise RuntimeError("Another Tool instance controls the development server. Release control in "
                           "Tool (start window) before running the audit.") from error
    assert result["acquired"], result
    CONTROLLER["epoch"] = result["lease"]["epoch"]
    if CONTROLLER["stop"] is None:
        stop = threading.Event()
        CONTROLLER["stop"] = stop

        def renew():
            while not stop.wait(5):
                epoch = CONTROLLER["epoch"]
                if epoch is None:
                    continue
                try:
                    request("POST", "/game-controller/renew", {"instanceId": CONTROLLER["instance"]}, epoch=epoch)
                except (OSError, AssertionError, http.client.HTTPException):
                    pass
        threading.Thread(target=renew, daemon=True).start()
    return result


def release_controller():
    stop, CONTROLLER["stop"] = CONTROLLER["stop"], None
    if stop is not None:
        stop.set()
    epoch, CONTROLLER["epoch"] = CONTROLLER["epoch"], None
    if epoch is not None:
        try:
            request("POST", "/game-controller/release", {}, epoch=epoch)
        except (OSError, AssertionError, http.client.HTTPException):
            pass


class FencedRequests:
    """``requests``-compatible transport for Tool code that carries the audit's epoch."""

    def _kwargs(self, kwargs):
        if CONTROLLER["epoch"] is not None:
            kwargs["headers"] = {**(kwargs.get("headers") or {}), EPOCH_HEADER: str(CONTROLLER["epoch"])}
        return kwargs

    def get(self, url, **kwargs):
        import requests
        return requests.get(url, **self._kwargs(kwargs))

    def post(self, url, **kwargs):
        import requests
        return requests.post(url, **self._kwargs(kwargs))

    def put(self, url, **kwargs):
        import requests
        return requests.put(url, **self._kwargs(kwargs))

    def delete(self, url, **kwargs):
        import requests
        return requests.delete(url, **self._kwargs(kwargs))


GAME_SESSION_IDS = []


def create_game_session(label, game_settings=None):
    """A stored game session of model 2 (API version 5) with its settings; deleted by ``delete_game_sessions``."""
    session = request("POST", "/game-sessions", {
        "name": PREFIX + "-" + label, "schemaVersion": SCHEMA_VERSION,
        "config": {"game_settings": game_settings or {}}})
    GAME_SESSION_IDS.append(session["id"])
    return session["id"]


def delete_game_sessions():
    while GAME_SESSION_IDS:
        request("DELETE", f"/game-sessions/{GAME_SESSION_IDS.pop()}", expected=204)


def execute(session_id):
    """Makes the session the one the controller executes (requests and status need it)."""
    result = request("PUT", "/game-controller/session", {"gameSessionId": session_id})
    assert result["gameSessionId"] == session_id, result
    return result


def plan(session_id, role, name, number=None):
    body = {"role": role, "displayName": name}
    if number is not None:
        body["number"] = number
    return request("POST", f"/game-sessions/{session_id}/members", body)


def bind_account(session_id, member_id, user_id, expected=200):
    return request("PUT", f"/game-sessions/{session_id}/members/{member_id}/account", {"userId": user_id},
                   expected=expected)


def bind_tracker(session_id, member_id, device_id, expected=200):
    return request("PUT", f"/game-sessions/{session_id}/members/{member_id}/tracker", {"deviceId": device_id},
                   expected=expected)


def member(session_id, member_id):
    return request("GET", f"/game-sessions/{session_id}/members/{member_id}")


def add_mirror(session_id, device_id, source_member_id, destination_role=None, destination_member_id=None):
    return request("POST", f"/game-sessions/{session_id}/resources/mirrors", {
        "deviceId": device_id, "sourceMemberId": source_member_id, "destinationRole": destination_role,
        "destinationMemberId": destination_member_id})


def add_group(session_id, group_id, purpose, member_id=None):
    return request("POST", f"/game-sessions/{session_id}/resources/groups", {
        "groupId": group_id, "purpose": purpose, "memberId": member_id})


def effects(session_id, expected=200, **parts):
    """One effects delivery under a fresh delivery ID (or ``deliveryId``)."""
    body = {"deliveryId": parts.pop("deliveryId", "audit-" + uuid4().hex), **parts}
    return request("POST", f"/game-sessions/{session_id}/effects", body, expected=expected)


def config(session_id, game_settings):
    """Replaces the session's game settings."""
    current = request("GET", f"/game-sessions/{session_id}")["settings"]
    return request("PUT", f"/game-sessions/{session_id}/config", {
        "expectedRevision": current["revision"], "schemaVersion": SCHEMA_VERSION,
        "document": {**current["settings"], "game_settings": game_settings}})


def iso(moment=None, **delta):
    moment = (moment or datetime.now(timezone.utc)) + timedelta(**delta)
    return moment.isoformat(timespec="milliseconds").replace("+00:00", "Z")


def login_cookie(email, password):
    cookies = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies))
    request("POST", "/session", urllib.parse.urlencode({"email": email, "password": password}).encode(),
            credentials=None, opener=opener)
    return "; ".join(cookie.name + "=" + cookie.value for cookie in cookies)


def member_socket(credentials, timeout=10):
    """A signed-in account's live-update WebSocket."""
    return websocket.create_connection(SOCKET_URL, timeout=timeout, origin=BASE,
                                       cookie=login_cookie(*credentials))


def invalidations(socket, seconds=1.5):
    """Every resource the socket was told to reload within the given time."""
    names = []
    deadline = time.monotonic() + seconds
    try:
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                break
            socket.settimeout(remaining)
            try:
                message = json.loads(socket.recv())
            except websocket.WebSocketTimeoutException:
                break
            names.extend(message.get("invalidate", []))
    finally:
        socket.settimeout(10)
    return names


def drain(socket, seconds=0.3):
    invalidations(socket, seconds)


def wait_for_invalidation(socket, name, seconds=10):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if name in invalidations(socket, min(1.0, max(0.05, deadline - time.monotonic()))):
            return
    raise AssertionError(f"No {name} invalidation within {seconds} s")


def wait_for_server():
    for attempt in range(90):
        try:
            request("GET", "/health", credentials=None)
            return
        except (OSError, http.client.HTTPException, AssertionError):
            if attempt == 89:
                raise
            time.sleep(1)


def restart_server():
    """Restarts the Docker server; a restart ends the lease (the old epoch is refused until control resumes)."""
    subprocess.run(["docker", "compose", "--profile", "full", "restart", "traccar"], check=True,
                   capture_output=True, cwd=COMPOSE_DIRECTORY)
    wait_for_server()


def upload(unique_id, latitude, longitude, **extra):
    """One OsmAnd upload of a fixture tracker."""
    parameters = urllib.parse.urlencode({"id": unique_id, "lat": latitude, "lon": longitude, "speed": 0,
                                         "timestamp": datetime.now(timezone.utc).isoformat(), **extra})
    with urllib.request.urlopen(UPLOAD + "/?" + parameters, timeout=15) as response:
        assert response.status == 200


class Fixtures:
    """Traccar resources an audit creates, removed in reverse kinds at the end."""

    def __init__(self):
        self.devices, self.groups, self.users, self.geofences, self.sockets = [], [], [], [], []

    def device(self, name, **fields):
        device = request("POST", "/devices", {"name": name, "uniqueId": name, **fields})
        self.devices.append(device["id"])
        return device

    def group(self, name):
        group = request("POST", "/groups", {"name": name})
        self.groups.append(group["id"])
        return group

    def account(self, name, **fields):
        email = name + "@example.invalid"
        user = request("POST", "/users", {"name": name, "email": email, "password": PASSWORD, **fields})
        self.users.append(user["id"])
        return user, (email, PASSWORD)

    def socket(self, credentials):
        socket = member_socket(credentials)
        self.sockets.append(socket)
        return socket

    def close_sockets(self):
        for socket in self.sockets:
            try:
                socket.close()
            except Exception:  # noqa: BLE001 - closing a dead socket during cleanup
                pass
        self.sockets.clear()

    def remove(self):
        self.close_sockets()
        wait_for_server()
        for device_id in self.devices:
            request("DELETE", "/devices/" + str(device_id), expected=204)
        if self.devices:
            # Positions and events do not cascade with their device: remove the fixtures' own rows.
            ids = ",".join(str(int(device_id)) for device_id in self.devices)
            sql(f"DELETE FROM tc_positions WHERE deviceid IN ({ids}); DELETE FROM tc_events WHERE deviceid IN ({ids});")
        for geofence_id in self.geofences:
            request("DELETE", "/geofences/" + str(geofence_id), expected=204)
        for group_id in self.groups:
            request("DELETE", "/groups/" + str(group_id), expected=204)
        for user_id in self.users:
            request("DELETE", "/users/" + str(user_id), expected=204)


def page(session_id, credentials=(EMAIL, PASSWORD), expected=200, **parameters):
    query = urllib.parse.urlencode({"gameSessionId": session_id, **parameters})
    return request("GET", "/game-notifications?" + query, credentials=credentials, expected=expected)


def audit_geofence_monitoring(session_id, trackers, roles, sockets, fixtures, tool_source):
    """Real geofence events without native subscriptions, optionally polled by the Tool's monitoring."""
    geofence = request("POST", "/geofences", {
        "name": PREFIX + "-monitoring",
        "area": "POLYGON ((47.4 8.4, 47.4 8.6, 47.6 8.6, 47.6 8.4, 47.4 8.4))",
    })
    fixtures.geofences.append(geofence["id"])
    source_ids = {key: device["id"] for key, device in trackers.items()}
    for device_id in source_ids.values():
        request("POST", "/permissions", {"deviceId": device_id, "geofenceId": geofence["id"]}, expected=204)
    assert sql("SELECT COUNT(*) FROM tc_device_notification WHERE deviceid IN ("
               + ",".join(map(str, source_ids.values())) + ")") == "0"
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

    def upload_all(latitude):
        for device in trackers.values():
            upload(device["uniqueId"], latitude, 8.5)

    for latitude, event_type in ((47.5, "geofenceEnter"), (48.5, "geofenceExit")):
        upload_all(latitude)
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
    if not tool_source:
        return

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
        if CONTROLLER["epoch"] is not None:
            transport.headers[EPOCH_HEADER] = str(CONTROLLER["epoch"])
        client = TraccarClient(BASE, UPLOAD, EMAIL, PASSWORD, transport=transport)
        service = MonitoringService(
            client, SystemClock(), Scheduler(), stream_enabled=False,
            thread_factory=threading.Thread,
            event_sink=lambda kind, payload: received.append((kind, payload)),
            persist=lambda snapshot: None,
        )
        service.configure(
            participant_ids=(source_ids["player"], source_ids["supporter"]),
            hunter_ids=(source_ids["hunter1"], source_ids["hunter2"]),
            roles={source_ids[key]: roles[key] for key in source_ids},
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
                SOCKET_URL, timeout=10, origin=BASE, cookie=client.get_websocket_cookie_header())
            native_ids = set()
            for latitude, event_type in ((47.5, "geofenceEnter"), (48.5, "geofenceExit")):
                upload_all(latitude)
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
                        assert not json.loads(socket.recv()).get("events"), "Native monitoring leaked to a member"
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
    admin_id = create_admin()
    fixtures = Fixtures()
    try:
        acquire_controller()
        request("GET", "/health", credentials=None)
        request("GET", "/game-notifications?gameSessionId=1", credentials=None, expected=401)
        session_id = create_game_session("session")
        other_id = create_game_session("other-session")
        execute(session_id)
        hunter_group = fixtures.group(PREFIX + "-hunters")
        trackers = {}
        for key, group_id in (("player", 0), ("supporter", 0), ("hunter1", hunter_group["id"]),
                              ("hunter2", hunter_group["id"])):
            trackers[key] = fixtures.device(PREFIX + "-" + key, groupId=group_id)
        roles = {"player": "player", "supporter": "supporter", "hunter1": "hunter", "hunter2": "hunter",
                 "spectator": "spectator"}
        members, users, credentials, sockets = {}, {}, {}, {}
        for key, role in roles.items():
            members[key] = plan(session_id, role, "Älice " + key if key == "player" else key)
            users[key], credentials[key] = fixtures.account(PREFIX + "-" + key, readonly=True)
            bind_account(session_id, members[key]["id"], users[key]["id"])
            if key in trackers:
                bind_tracker(session_id, members[key]["id"], trackers[key]["id"])
            if role == "hunter":
                request("POST", "/permissions", {"userId": users[key]["id"], "groupId": hunter_group["id"]},
                        expected=204)
            elif key in trackers:
                request("POST", "/permissions", {"userId": users[key]["id"], "deviceId": trackers[key]["id"]},
                        expected=204)
        # A member of the other session, and an account without membership.
        other_member = plan(other_id, "player", "Other")
        other_user, other_credentials = fixtures.account(PREFIX + "-other")
        bind_account(other_id, other_member["id"], other_user["id"])
        unrelated_user, unrelated = fixtures.account(PREFIX + "-unrelated")

        endpoint = f"/game-notifications/session/{session_id}"
        initial = request("POST", endpoint, {})
        assert request("POST", endpoint, {}) == initial
        assert initial["gameSessionId"] == session_id and initial["generation"]
        generation = initial["generation"]
        other_generation = request("POST", f"/game-notifications/session/{other_id}", {})["generation"]
        for key in roles:
            page(session_id, credentials[key])
            page(other_id, credentials[key], expected=403)
            # Writes are fenced before authorization: without the controller lease they are refused (423).
            request("POST", endpoint, {}, credentials=credentials[key], expected=423)
            sockets[key] = fixtures.socket(credentials[key])
        page(session_id, unrelated, expected=403)
        page(other_id, other_credentials)
        page(session_id, other_credentials, expected=403)
        for hunter_key in ("hunter1", "hunter2"):
            visible = {device["id"] for device in request("GET", "/devices", credentials=credentials[hunter_key])}
            assert {trackers["hunter1"]["id"], trackers["hunter2"]["id"]} <= visible
        audit_geofence_monitoring(session_id, trackers, roles, sockets, fixtures, tool_source)
        for socket in sockets.values():
            drain(socket)

        alert = {"id": "live", "type": "notificationTest", "audience": "session", "occurredAt": iso(),
                 "timeZone": "UTC", "message": "[12:00:00] Test notification: check that your phone plays a sound."}
        payload = {"gameSessionId": session_id, "generation": generation, "alert": alert}
        result = request("POST", "/game-notifications", payload)
        assert result["created"] and datetime.fromisoformat(
            result["item"]["occurredAt"].replace("Z", "+00:00")) == datetime.fromisoformat(
            alert["occurredAt"].replace("Z", "+00:00"))
        for socket in sockets.values():
            wait_for_invalidation(socket, "game-notifications")
        assert not request("POST", "/game-notifications", payload)["created"]
        request("POST", "/game-notifications", {**payload, "alert": {**alert, "message": "conflict"}}, expected=409)
        request("POST", "/game-notifications", {**payload, "gameSessionId": other_id}, expected=409)
        assert request("POST", "/game-notifications", {
            "gameSessionId": other_id, "generation": other_generation, "alert": alert})["created"]
        assert "game-notifications" not in invalidations(sockets["player"], 0.3), "Another session's alert leaked"

        # Alerts are archived inside effects deliveries as well, under the same rules.
        delivered = effects(session_id, notifications={"generation": generation, "alerts": [
            {**alert, "id": "delivered", "message": "[12:00:01] Delivered with effects."}]})
        assert delivered["alerts"] == [{"alertId": "delivered", "created": True}], delivered
        for socket in sockets.values():
            wait_for_invalidation(socket, "game-notifications")

        targeted_ids = {}
        for key, alert_type in (("player", "liveTrackingStarted"), ("supporter", "liveTrackingStopped"),
                                ("hunter1", "liveTrackingTargetsChanged"), ("hunter2", "geofenceExit")):
            target_id = "exit-" + key
            targeted_ids[key] = target_id
            targeted = {"id": target_id, "type": alert_type, "audience": "user", "occurredAt": iso(),
                        "timeZone": "UTC", "message": "[12:00:00] " + key + " left geofence Audit.",
                        "recipientMemberId": members[key]["id"], "sourceMemberId": members[key]["id"]}
            created = request("POST", "/game-notifications", {**payload, "alert": targeted})
            assert created["created"] and created["item"]["recipientMemberId"] == members[key]["id"]
            wait_for_invalidation(sockets[key], "game-notifications")
            for other_key, socket in sockets.items():
                if other_key != key:
                    assert "game-notifications" not in invalidations(socket, 0.3), \
                        f"A targeted alert for {key} invalidated {other_key}"
        for key in roles:
            private_ids = {record["id"] for record in page(session_id, credentials[key], generation=generation,
                                                           limit=100)["items"]}
            expected_private = {targeted_ids[key]} if key in targeted_ids else set()
            assert private_ids.intersection(targeted_ids.values()) == expected_private, key
            assert {"live", "delivered"} <= private_ids
        request("POST", "/game-notifications", {**payload, "alert": {
            **alert, "id": "spectator-targeted", "type": "geofenceExit", "audience": "user",
            "recipientMemberId": members["spectator"]["id"], "sourceMemberId": members["spectator"]["id"]}},
            expected=400)
        request("POST", "/game-notifications", {**payload, "alert": {
            **alert, "id": "mismatched", "type": "geofenceExit", "audience": "user",
            "recipientMemberId": members["player"]["id"], "sourceMemberId": members["supporter"]["id"]}},
            expected=400)

        for audience in ("nonHunters", "hunters"):
            role_alert = {**alert, "id": "speedhunt-" + audience, "type": "speedhunt", "audience": audience,
                          "message": "[12:00:00] Speedhunt: Ping 2 out of 4 sent."
                          + (" Participant: Älice speedhunt identity." if audience == "hunters" else "")}
            assert request("POST", "/game-notifications", {**payload, "alert": role_alert})["created"]
            assert not request("POST", "/game-notifications", {**payload, "alert": role_alert})["created"]
        for key in roles:
            current = page(session_id, credentials[key])
            role_records = [record for record in current["items"] if record["id"].startswith("speedhunt-")]
            assert len(role_records) == 1, key
            named = key.startswith("hunter") or key == "spectator"
            assert role_records[0]["audience"] == ("hunters" if named else "nonHunters")
            assert ("Älice speedhunt identity" in json.dumps(current, ensure_ascii=False)) == named

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
                            "audience": audience, "recipientMemberId": members[key]["id"],
                            "sourceMemberId": members[source_key]["id"],
                            "message": "[12:00:00] Live ping from Älice. Reason: Public transport."}
                received_ids[key].add(received["id"])
                assert request("POST", "/game-notifications", {**payload, "alert": received})["created"]
                assert not request("POST", "/game-notifications", {**payload, "alert": received})["created"]
            request("POST", "/game-notifications", {**payload, "alert": {
                **received, "id": "received-invalid-role", "type": "livePingReceived",
                "audience": "livePlayer" if key != "player" else "liveHunter"}}, expected=400)
            request("POST", "/game-notifications", {**payload, "alert": {
                **received, "id": "received-invalid-source", "type": "livePingReceived",
                "sourceMemberId": members["hunter1"]["id"]}}, expected=400)
        all_received_ids = set().union(*received_ids.values())
        for key in received_ids:
            own = {record["id"] for record in page(session_id, credentials[key])["items"]}
            assert own.intersection(all_received_ids) == received_ids[key], key

        # Identity: a tracker replacement keeps the member's personal history; unbinding the account ends access;
        # a role change (a new member) does not carry the old member's personal history.
        replacement = fixtures.device(PREFIX + "-player-replacement")
        bind_tracker(session_id, members["player"]["id"], replacement["id"])
        own = {record["id"] for record in page(session_id, credentials["player"])["items"]}
        assert received_ids["player"] | {targeted_ids["player"]} <= own
        bind_tracker(session_id, members["player"]["id"], trackers["player"]["id"])
        request("DELETE", f"/game-sessions/{session_id}/members/{members['player']['id']}/account")
        page(session_id, credentials["player"], expected=403)
        bind_account(session_id, members["player"]["id"], users["player"]["id"])
        assert targeted_ids["player"] in {record["id"] for record in page(session_id, credentials["player"])["items"]}
        changed = request("POST", f"/game-sessions/{session_id}/members/{members['supporter']['id']}/role", {
            "role": "hunter", "displayName": "supporter as hunter"})
        assert changed["id"] != members["supporter"]["id"] and changed["userId"] == users["supporter"]["id"]
        own = {record["id"] for record in page(session_id, credentials["supporter"])["items"]}
        assert not own.intersection(received_ids["supporter"] | {targeted_ids["supporter"]})
        assert "speedhunt-hunters" in own and "speedhunt-nonHunters" not in own
        # Alerts to a retired member are refused.
        assert "retired" in refusal("POST", "/game-notifications", {**payload, "alert": {
            **alert, "id": "to-retired", "type": "geofenceExit", "audience": "user", "recipientMemberId": members["supporter"]["id"],
            "sourceMemberId": members["supporter"]["id"]}}, expected=409)

        for invalid_type in ("geofenceEnter", "deviceOverspeed"):
            request("POST", "/game-notifications", {**payload, "alert": {
                **alert, "id": "invalid-" + invalid_type, "type": invalid_type, "audience": "user",
                "recipientMemberId": members["player"]["id"], "sourceMemberId": members["player"]["id"]}},
                expected=400)
        # MySQL's default text collation must not merge opaque, case-only IDs.
        for alert_id in ("CaseId", "caseid"):
            case_payload = {**payload, "alert": {**alert, "id": alert_id}}
            assert request("POST", "/game-notifications", case_payload)["created"]
            assert not request("POST", "/game-notifications", case_payload)["created"]
        for zone in ("GMT+02:00", "+02:00", "SystemV/EST5EDT"):
            request("POST", "/game-notifications", {**payload, "alert": {**alert, "timeZone": zone}}, expected=400)
        request("POST", "/game-notifications", {**payload, "alert": {**alert, "occurredAt": "1900-01-01T00:00:00Z"}},
                expected=400)
        # Full history beyond one page; listing never generates pushes.
        for index in range(205):
            record = {**alert, "id": "old-" + str(index), "occurredAt": "2026-01-01T12:00:00.123Z"}
            request("POST", "/game-notifications", {**payload, "alert": record})
        total = int(sql(f"SELECT COUNT(*) FROM tc_game_notifications WHERE gamesessionid={session_id}"))
        cursor, ids = "0", []
        while True:
            current = page(session_id, generation=generation, after=cursor)
            assert len(current["items"]) <= 100
            ids.extend(record["id"] for record in current["items"])
            cursor = current["nextCursor"]
            if not current["hasMore"]:
                break
        assert len(ids) == len(set(ids)) == total > 225
        assert sql("SELECT COUNT(*) FROM tc_game_push_outbox WHERE gamesessionid=" + str(session_id)) == "0"
        player_count = len(page(session_id, credentials["player"], limit=500)["items"])

        for socket in sockets.values():
            socket.close()
        fixtures.sockets.clear()
        restart_server()
        # A server restart ends the lease; the old epoch is refused until control is resumed.
        stale = CONTROLLER["epoch"]
        request("PUT", f"/game-sessions/{session_id}/members/{members['player']['id']}/name",
                {"displayName": "stale"}, epoch=stale, expected=423)
        assert acquire_controller()["lease"]["epoch"] == stale + 1
        execute(session_id)
        recovered = page(session_id, credentials["player"], generation=generation, limit=500)
        assert recovered["generation"] == generation and len(recovered["items"]) == player_count

        # Deleting a recipient's account keeps the member and its history.
        request("DELETE", "/users/" + str(users["hunter2"]["id"]), expected=204)
        fixtures.users.remove(users["hunter2"]["id"])
        assert member(session_id, members["hunter2"]["id"])["userId"] is None
        assert int(sql(f"SELECT COUNT(*) FROM tc_game_notifications WHERE gamesessionid={session_id}")) == total
        by_id = {record["id"]: record for record in page(session_id, limit=500)["items"]}
        assert by_id["exit-hunter2"]["recipientMemberId"] == members["hunter2"]["id"]
        assert by_id["exit-hunter2"]["recipientLabel"]
        assert "live" in by_id
        page(session_id, limit=501, expected=400)

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
        assert not page(session_id)["items"]
        request("POST", "/game-notifications", {**payload, "generation": reset["replacementGeneration"]})
        assert page(other_id)["items"], "A reset must leave other sessions' history alone"
        history = request("GET", "/game-notifications/history?gameSessionId=" + str(session_id))
        assert [item["id"] for item in history["items"]] == ["live"]
        request("GET", "/game-notifications/history?gameSessionId=" + str(session_id), credentials=credentials["spectator"],
                expected=403)
        request("DELETE", f"/game-sessions/{session_id}", expected=204)
        GAME_SESSION_IDS.remove(session_id)
        assert sql(f"SELECT COUNT(*) FROM tc_game_notifications WHERE gamesessionid={session_id}") == "0"
        page(session_id, credentials["player"], expected=403)
        print("MySQL/HTTP notification audit passed: member and session authorization, fencing, private targeted "
              "invalidations, hunter-group visibility, real geofence events without native links, alerts in "
              "effects deliveries, timestamps, case-sensitive IDs, hunter/spectator speedhunt names, live "
              "recipients, tracker replacement, unbinding and role change, retired recipients, pagination, "
              "server restart, idempotency, history after account deletion, reset races and session deletion.")
        if tool_source:
            print("Tool monitoring audit passed: healthy position websocket, enter/exit polling for player, "
                  "supporter and two hunters, admin-only web subscriptions keyed by game session, no member "
                  "native events, original event IDs, and report/websocket deduplication.")
    finally:
        fixtures.remove()
        delete_game_sessions()
        release_controller()
        delete_admin(admin_id)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tool-source", help="Traccar Tool src directory for the real monitoring audit")
    main(parser.parse_args().tool_source)
