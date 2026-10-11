"""Local MySQL/HTTP integration audit. Creates and removes only its own fixtures.

Run after the full Docker build, using Python with websocket-client installed.
Never calls the push endpoint, reads existing accounts, or resets volumes.
"""

import base64
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
import http.client
import http.cookiejar
import json
import secrets
import subprocess
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
    headers = {}
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


def main():
    device_ids, user_ids, sockets = [], [], []
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
        endpoint = "/game-notifications/session/" + str(session_id)
        initial = request("POST", endpoint, {})
        assert request("POST", endpoint, {}) == initial
        generation = initial["generation"]
        generated = []
        for role in ("player", "supporter", "hunter", "spectator", "unrelated"):
            email = PREFIX + "-" + role + "@example.invalid"
            user = request("POST", "/users", {"name": PREFIX + role, "email": email, "password": PASSWORD,
                "readonly": True, "attributes": {"traccarToolUserRole": role,
                    "traccarToolSessionDeviceId": session_id}})
            user_ids.append(user["id"])
            request("POST", "/permissions", {"userId": user["id"], "deviceId": session_id}, expected=204)
            credentials = (email, PASSWORD)
            request("GET", "/game-notifications?sessionDeviceId=" + str(session_id),
                credentials=credentials, expected=403 if role == "unrelated" else 200)
            request("GET", "/game-notifications?sessionDeviceId=" + str(device_ids[1]),
                credentials=credentials, expected=403)
            request("POST", endpoint, {}, credentials=credentials, expected=403)
            if role != "unrelated":
                generated.append(credentials)
                cookies = http.cookiejar.CookieJar()
                opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies))
                request("POST", "/session", urllib.parse.urlencode({"email": email, "password": PASSWORD}).encode(),
                    credentials=None, opener=opener)
                socket = websocket.create_connection("ws://localhost:8083/api/socket", timeout=10,
                    origin=BASE, cookie="; ".join(cookie.name + "=" + cookie.value for cookie in cookies))
                sockets.append(socket)
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
        assert len(ids) == len(set(ids)) == 208
        request("DELETE", "/positions?" + urllib.parse.urlencode({"deviceId": session_id,
            "from": "2020-01-01T00:00:00Z", "to": "2030-01-01T00:00:00Z"}), expected=204)
        assert sql("SELECT COUNT(*) FROM tc_game_notifications WHERE sessiondeviceid=" + str(session_id)) == "208"
        for socket in sockets:
            socket.close()
        sockets.clear()
        subprocess.run(["docker", "compose", "--profile", "full", "restart", "traccar"],
            check=True, capture_output=True)
        wait_for_server()
        recovered = request("GET", "/game-notifications?" + urllib.parse.urlencode({
            "sessionDeviceId": session_id, "generation": generation, "limit": 500}), credentials=generated[0])
        assert recovered["generation"] == generation and len(recovered["items"]) == 208
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
        print("MySQL/HTTP notification audit passed: existing-data migration, authorization, four role sockets, "
              "timestamps, case-sensitive IDs, 208-row pagination, GPS cleanup, server restart, idempotency, atomic device saves, reset races and deletion cascade.")
    finally:
        for socket in sockets:
            socket.close()
        wait_for_server()
        for device_id in device_ids:
            request("DELETE", "/devices/" + str(device_id), expected=204)
        for user_id in user_ids[1:]:
            request("DELETE", "/users/" + str(user_id), expected=204)
        # Exact test admin ID/email, never a broad production/user cleanup.
        try:
            request("DELETE", "/users/" + str(admin_id), expected=204)
        finally:
            sql("DELETE FROM tc_users WHERE id=" + str(admin_id) + " AND email=" + literal(EMAIL) + ";")


if __name__ == "__main__":
    main()
