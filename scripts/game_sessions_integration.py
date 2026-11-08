"""Local MySQL/HTTP audit of central game sessions with isolated fixtures.

Creates a temporary administrator and a temporary non-administrator, exercises
``/api/game-sessions`` directly and through the Tool's server repository
(``--tool-source``), and removes every fixture afterwards. Requires the full
Docker profile with ``TRACCAR_GAME_SESSIONS_CREDENTIAL_KEY`` set in ``.env``.
"""

import argparse
import hashlib
import json
import secrets
import sys
import tempfile
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path
from uuid import uuid4

import game_notification_integration as api


def create_user(name, email, password, administrator):
    salt = secrets.token_bytes(24)
    digest = hashlib.pbkdf2_hmac("sha256", password.encode(), salt, 200000, 24).hex().upper()
    api.sql("INSERT INTO tc_users(name,email,hashedpassword,salt,administrator,attributes) VALUES("
            + ",".join([api.literal(name), api.literal(email),
                        api.literal("PBKDF2WithHmacSHA256$200000$" + digest),
                        api.literal(salt.hex().upper()), "1" if administrator else "0",
                        api.literal("{}")]) + ");")
    return int(api.sql("SELECT id FROM tc_users WHERE email=" + api.literal(email) + ";"))


def audit_http(user_credentials, device_ids):
    """Admin-only access, revisions, encryption at rest, timetable identity, history retention."""
    api.request("GET", "/game-sessions", credentials=user_credentials, expected=403)
    assert api.request("GET", "/game-sessions/capabilities")["apiVersion"] == 2
    session = api.request("POST", "/game-sessions", {
        "name": api.PREFIX + "-http", "schemaVersion": 4, "config": {"game_settings": {"pings": 4}}})
    session_id = session["id"]
    api.GAME_SESSION_IDS.append(session_id)
    api.request("POST", "/game-sessions", {"name": api.PREFIX + "-http", "schemaVersion": 4, "config": {}},
                expected=409)

    update = {"expectedRevision": 1, "schemaVersion": 4, "document": {"game_settings": {"pings": 5}}}
    assert api.request("PUT", f"/game-sessions/{session_id}/config", update)["revision"] == 2
    assert api.request("PUT", f"/game-sessions/{session_id}/config", update)["revision"] == 2
    api.request("PUT", f"/game-sessions/{session_id}/config",
                {**update, "document": {"game_settings": {"pings": 6}}}, expected=409)

    secret = "audit-" + secrets.token_hex(8)
    api.request("PUT", f"/game-sessions/{session_id}/credentials",
                {"expectedRevision": 0, "document": {"user_passwords": {"player": secret}}})
    stored = api.sql(f"SELECT ciphertext FROM tc_game_session_credentials WHERE gamesessionid={session_id};")
    assert stored.startswith("v1:") and secret not in stored, "Credentials are not encrypted at rest"
    assert api.request("GET", f"/game-sessions/{session_id}/credentials")["document"]["user_passwords"][
        "player"] == secret

    columns = ["Time", "Anna", "Ben"]
    entries = [{"scheduledAt": "2026-05-01T10:00:00Z", "participant": "Anna", "content": ""},
               {"scheduledAt": "2026-05-01T10:00:00Z", "participant": "Ben", "content": "b"}]
    first = api.request("PUT", f"/game-sessions/{session_id}/timetable",
                        {"expectedRevision": 0, "columns": columns, "source": {}, "entries": entries})
    entries[0]["content"] = "47.1,8.5|2H"
    second = api.request("PUT", f"/game-sessions/{session_id}/timetable",
                         {"expectedRevision": 1, "columns": columns, "source": {}, "entries": entries})
    assert [entry["id"] for entry in first["entries"]] == [entry["id"] for entry in second["entries"]]
    assert not second["structureChanged"]

    # Notification history survives deletion of the session device.
    device = api.request("POST", "/devices", {"name": api.PREFIX + "-state", "uniqueId": api.PREFIX + "-state",
                                              "attributes": {"traccarToolGameState": True}})
    api.request("PUT", f"/game-sessions/{session_id}/resources", {
        "expectedRevision": 1, "sessionDeviceId": device["id"], "resources": {}, "members": []})
    generation = api.request("POST", f"/game-notifications/session/{device['id']}", {})["generation"]
    api.request("POST", "/game-notifications", {"sessionDeviceId": device["id"], "generation": generation,
                "alert": {"id": "audit-1", "type": "notificationTest", "audience": "session",
                          "occurredAt": "2026-05-01T10:00:00Z", "timeZone": "UTC", "message": "Audit"}})
    api.request("DELETE", f"/devices/{device['id']}", expected=204)
    history = api.request("GET", f"/game-notifications/history?gameSessionId={session_id}")
    assert [item["id"] for item in history["items"]] == ["audit-1"]
    assert api.request("GET", f"/game-sessions/{session_id}")["sessionDeviceId"] is None

    api.request("DELETE", f"/game-sessions/{session_id}", expected=204)
    api.GAME_SESSION_IDS.remove(session_id)
    assert api.sql(f"SELECT COUNT(*) FROM tc_game_notifications WHERE gamesessionid={session_id};") == "0"
    assert api.sql(f"SELECT COUNT(*) FROM tc_game_timetable_entries WHERE gamesessionid={session_id};") == "0"


def audit_tool(tool_source):
    """Create, save, reload and delete a session through the Tool's repository."""
    sys.path.insert(0, tool_source)
    import requests
    from traccar_game_manager.domain.session.document import validate_session_document
    from traccar_game_manager.domain.session.factory import default_session_template
    from traccar_game_manager.infrastructure.persistence.server_jobs import ServerJobQueue
    from traccar_game_manager.infrastructure.persistence.server_sessions import (
        ServerSessionLogStore,
        ServerSessionRepository,
    )
    from traccar_game_manager.infrastructure.persistence.session_preferences import JsonSessionPreferences
    from traccar_game_manager.infrastructure.traccar.game_sessions import GameSessionApi

    class Transport:
        get, post, put, delete = (staticmethod(requests.get), staticmethod(requests.post),
                                  staticmethod(requests.put), staticmethod(requests.delete))

    server = {"web_url": api.BASE, "client_url": "http://localhost:5056",
              "username": api.EMAIL, "password": api.PASSWORD}
    client = GameSessionApi(api.BASE, api.EMAIL, api.PASSWORD, transport=Transport(),
                            epoch_provider=lambda: api.CONTROLLER["epoch"])
    client.check_compatible()
    jobs = ServerJobQueue()
    with tempfile.TemporaryDirectory() as tmp:
        repository = ServerSessionRepository(
            client, server=server, preferences=JsonSessionPreferences(Path(tmp) / "prefs.json"), jobs=jobs)
        name = api.PREFIX + "-tool"
        draft = default_session_template(name)
        draft["user_passwords"]["player"] = "player-" + secrets.token_hex(4)
        draft["ping_table_file"] = "/local/only.xlsx"
        session = repository.create(name, validate_session_document(draft, name))
        api.GAME_SESSION_IDS.append(repository.session_id(name))
        session["game_settings"]["pings_per_speedhunt"] = 7
        session["game_status"]["last_speedhunt_player"] = 3
        repository.save(name, session)
        logs = ServerSessionLogStore(client, jobs, repository.session_id)
        logs.append(name, "operation", "[10:00:00] audit")
        assert jobs.flush(30), "Background writes did not finish"
        assert jobs.drain_events() == []
        reloaded = ServerSessionRepository(
            client, server=server, preferences=JsonSessionPreferences(Path(tmp) / "other.json"),
            jobs=ServerJobQueue()).load(name)
        assert reloaded["game_settings"]["pings_per_speedhunt"] == 7
        assert reloaded["game_status"]["last_speedhunt_player"] == 3
        assert reloaded["user_passwords"]["player"] == draft["user_passwords"]["player"]
        assert reloaded["ping_table_file"] == "", "Local paths must not be stored centrally"
        stored = api.request("GET", f"/game-sessions/{repository.session_id(name)}")
        assert api.PASSWORD not in str(stored), "The Traccar admin password must not be stored centrally"
        logs.prefetch(name)
        assert logs.lines(name) == ("[10:00:00] audit\n",)
        session_id = repository.session_id(name)
        assert repository.delete(name)
        api.GAME_SESSION_IDS.remove(session_id)
        jobs.close(5)


def stored_device(device_id):
    return api.request("GET", f"/devices/{device_id}")


def outcome_pushes(session_id, delivery):
    return api.request("POST", f"/game-sessions/{session_id}/effects", delivery)["pushes"]


def audit_controller(user_id, user_login, user_credentials):
    """Lease, fencing of game and standard endpoints, drift, takeover, effects and expiry."""
    lease = api.request("GET", "/game-controller")
    assert lease["active"] and lease["epoch"] == api.CONTROLLER["epoch"]
    epoch = api.CONTROLLER["epoch"]
    other = "audit-other-" + uuid4().hex[:16]
    api.request("POST", "/game-controller/acquire", {"instanceId": other, "label": "Other PC"},
                epoch=False, expected=423)

    session = api.request("POST", "/game-sessions", {"name": api.PREFIX + "-controller", "schemaVersion": 4,
                                                     "config": {}}, epoch=False)
    session_id = session["id"]
    api.GAME_SESSION_IDS.append(session_id)
    state = api.request("POST", "/devices", {"name": api.PREFIX + "-ctl-state", "uniqueId": api.PREFIX + "-ctl-state",
                                             "attributes": {"traccarToolGameState": True}})
    mirror = api.request("POST", "/devices", {"name": api.PREFIX + "-ctl-mirror",
                                              "uniqueId": api.PREFIX + "-ctl-mirror",
                                              "attributes": {"traccarToolMirror": True}})
    real = api.request("POST", "/devices", {"name": api.PREFIX + "-ctl-real", "uniqueId": api.PREFIX + "-ctl-real"})
    devices = [mirror["id"], real["id"], state["id"]]
    try:
        # Execution writes need the epoch; configuration of a non-executing session does not.
        api.request("PUT", f"/game-sessions/{session_id}/resources", {
            "expectedRevision": 1, "sessionDeviceId": state["id"],
            "resources": {"devices": {"real": {"real-1": real["id"]},
                                      "mirroring": {"player_1_to_hunters": mirror["id"]}}},
            "members": [{"userId": user_id, "role": "player", "number": 1, "assignedDeviceId": real["id"],
                         "login": user_login}]}, epoch=False, expected=423)
        api.request("PUT", f"/game-sessions/{session_id}/resources", {
            "expectedRevision": 1, "sessionDeviceId": state["id"],
            "resources": {"devices": {"real": {"real-1": real["id"]},
                                      "mirroring": {"player_1_to_hunters": mirror["id"]}}},
            "members": [{"userId": user_id, "role": "player", "number": 1, "assignedDeviceId": real["id"],
                         "login": user_login}]})
        api.request("PUT", f"/game-sessions/{session_id}/config",
                    {"expectedRevision": 1, "document": {"game_settings": {}}}, epoch=False)
        api.request("PUT", "/game-controller/session", {"gameSessionId": session_id})
        api.request("PUT", f"/game-sessions/{session_id}/config",
                    {"expectedRevision": 2, "document": {"game_settings": {"x": 1}}}, epoch=False, expected=423)

        # Standard mutations of game-owned resources need the epoch while a lease is held.
        renamed = {**mirror, "name": mirror["name"] + "-renamed"}
        api.request("PUT", f"/devices/{mirror['id']}", renamed, epoch=False, expected=423)
        api.request("PUT", f"/devices/{mirror['id']}", renamed)
        api.request("DELETE", f"/positions?deviceId={mirror['id']}&from=2020-01-01T00:00:00Z"
                    "&to=2030-01-01T00:00:00Z", epoch=False, expected=423)
        api.request("POST", "/permissions", {"userId": user_id, "deviceId": real["id"]}, epoch=False, expected=423)
        # Members update their own account (push tokens, preferences) while a Tool controls the game.
        own = api.request("GET", f"/users/{user_id}", credentials=user_credentials)
        updated = api.request("PUT", f"/users/{user_id}", {**own, "attributes": {
            **(own.get("attributes") or {}), "notificationTokens": "audit-token"}}, credentials=user_credentials)
        assert updated["attributes"]["notificationTokens"] == "audit-token"
        # A request carrying a wrong epoch is refused whatever it changes.
        api.request("POST", "/groups", {"name": api.PREFIX + "-stale"}, epoch=epoch + 1000, expected=423)
        # Pushes that reach members of the executing session, also sends without userId.
        message = {"subject": "Audit", "digest": "Audit", "body": "Audit", "priority": False}
        api.request("POST", f"/notifications/send/traccar?userId={user_id}", message, epoch=False, expected=423)
        api.request("POST", "/notifications/send/traccar", message, epoch=False, expected=423)

        # Effects: mirror positions become latest and the device shows as online; repeats are not applied.
        delivery = {"deliveryId": "audit" + uuid4().hex, "positions": [
            {"deviceId": mirror["id"], "latitude": 47.1, "longitude": 8.5},
            {"uniqueId": api.PREFIX + "-ctl-state", "latitude": 0, "longitude": 0,
             "attributes": {"traccarToolCaught": "1"}}],
            "pushes": [{"userId": user_id, "subject": "Audit", "message": "Speedhunt started"}]}
        first = api.request("POST", f"/game-sessions/{session_id}/effects", delivery)
        assert not first["replayed"] and all(item["latest"] for item in first["positions"])
        device = stored_device(mirror["id"])
        assert device["positionId"] == first["positions"][0]["positionId"] and device["status"] == "online"
        position = api.request("GET", f"/positions?id={device['positionId']}")[0]
        assert position["attributes"]["activity"] == "still" and position["protocol"] == "osmand"
        replay = api.request("POST", f"/game-sessions/{session_id}/effects", delivery)
        assert replay["replayed"] and replay["positions"] == first["positions"]
        assert api.sql(f"SELECT COUNT(*) FROM tc_positions WHERE deviceid={mirror['id']}") == "1"
        deadline = time.monotonic() + 15
        while outcome_pushes(session_id, delivery)[0]["status"] in ("queued", "attempting"):
            assert time.monotonic() < deadline, "Outbox push was not attempted"
            time.sleep(0.5)
        assert outcome_pushes(session_id, delivery)[0]["status"] in ("sent", "failed")
        older = (datetime.now(timezone.utc) - timedelta(minutes=5)).strftime("%Y-%m-%dT%H:%M:%S.000Z")
        late = api.request("POST", f"/game-sessions/{session_id}/effects", {
            "deliveryId": "audit" + uuid4().hex,
            "positions": [{"deviceId": mirror["id"], "latitude": 47.2, "longitude": 8.6, "fixTime": older}]})
        assert not late["positions"][0]["latest"]
        assert stored_device(mirror["id"])["positionId"] == first["positions"][0]["positionId"]
        api.request("POST", f"/game-sessions/{session_id}/effects", {
            "deliveryId": "audit" + uuid4().hex,
            "positions": [{"deviceId": real["id"], "latitude": 1, "longitude": 1}]}, expected=400)
        api.request("POST", f"/game-sessions/{session_id}/effects", delivery, epoch=False, expected=423)

        # Takeover: the previous holder's delayed requests are refused.
        api.request("PUT", "/game-controller/session", {"gameSessionId": None})
        takeover = api.request("POST", "/game-controller/takeover", {"instanceId": other, "label": "Other PC"},
                               epoch=False)
        assert takeover["lease"]["epoch"] == epoch + 1
        api.request("PUT", f"/game-sessions/{session_id}/runtime",
                    {"expectedRevision": 1, "document": {}}, epoch=epoch, expected=423)
        api.request("POST", "/game-controller/renew", {"instanceId": api.CONTROLLER["instance"]},
                    epoch=epoch, expected=423)
        api.request("POST", "/game-controller/release", {}, epoch=epoch + 1)

        # Without a lease administrators may edit game resources; the next acquisition reports it.
        api.request("PUT", f"/devices/{mirror['id']}", {**renamed, "name": mirror["name"]}, epoch=False)
        acquired = api.acquire_controller()
        assert acquired["lease"]["epoch"] == epoch + 2
        assert any(item["detail"] == f"PUT /api/devices/{mirror['id']}" for item in acquired["drift"]), \
            acquired["drift"]

        # Expiry from the stored deadline: stop renewing, wait, and the epoch is refused.
        stop, api.CONTROLLER["stop"] = api.CONTROLLER["stop"], None
        stop.set()
        expiring = api.CONTROLLER["epoch"]
        time.sleep(api.request("GET", "/game-controller")["leaseSeconds"] + 2)
        assert api.request("GET", "/game-controller")["state"] == "expired"
        api.request("PUT", f"/game-sessions/{session_id}/runtime",
                    {"expectedRevision": 1, "document": {}}, epoch=expiring, expected=423)
        assert api.acquire_controller()["lease"]["epoch"] == expiring + 1
        events = [item["event"] for item in api.request("GET", "/game-controller/history?limit=20")["items"]]
        for event in ("expired", "acquired", "takeover", "takenOver", "released", "executing",
                      "stoppedExecuting", "unfencedEdit"):
            assert event in events, (event, events)
    finally:
        for device_id in devices:
            api.request("DELETE", f"/devices/{device_id}", expected=204)


def audit_tool_requests(tool_source, session_id, target, submit):
    """The Tool's request processor and gateway against the real server."""
    sys.path.insert(0, tool_source)
    import requests
    from traccar_game_manager.application.game_requests.processor import (
        GameRequestProcessor, RequestCheck, RequestResult, SpeedhuntStatusTracker)
    from traccar_game_manager.domain.game.settings import normalize_game_settings
    from traccar_game_manager.infrastructure.traccar.effects import SessionEffects
    from traccar_game_manager.infrastructure.traccar.game_requests import GameRequestGateway
    from traccar_game_manager.infrastructure.traccar.game_sessions import GameSessionApi

    class Transport:
        get, post, put, delete = (staticmethod(requests.get), staticmethod(requests.post),
                                  staticmethod(requests.put), staticmethod(requests.delete))

    client = GameSessionApi(api.BASE, api.EMAIL, api.PASSWORD, transport=Transport(),
                            epoch_provider=lambda: api.CONTROLLER["epoch"])
    gateway = GameRequestGateway(client, session_id, SessionEffects(client, session_id))
    tracker = SpeedhuntStatusTracker()
    tracker.seed(gateway.published_status())
    participant = {"deviceId": target, "name": "Anna", "role": "player", "caught": False,
                   "speedhunt": {"running": True, "pings": 1, "live": False}}
    # Like the live window: publish the Tool's own status first, then hunters act on it.
    gateway.publish_status(tracker.build([participant]))
    revision = tracker.revision(target)
    submit(revision)
    executed = []

    class Actions:
        def participant(self, device_id):
            return {"id": device_id, "name": "Anna", "caught": False}

        def check(self, kind, item, parameters):
            return RequestCheck()

        def execute(self, kind, item, parameters):
            executed.append(kind)
            participant["speedhunt"] = {**participant["speedhunt"], "pings": 2}
            return RequestResult(True, "Ping 2 of 4 sent.")

    settings = normalize_game_settings({})
    processor = GameRequestProcessor(gateway, Actions(), settings=lambda: settings,
                                     status=lambda: (tracker.build([participant]), tracker), log=lambda _m: None)
    processor.sync(gateway.list_open())
    awaiting = processor.awaiting()
    assert len(awaiting) == 1, awaiting
    processor.approve(awaiting[0]["id"])
    done = next(item for item in client.list_requests(session_id, state="all") if item["id"] == awaiting[0]["id"])
    assert done["status"] == "succeeded" and done["message"] == "Ping 2 of 4 sent.", done
    assert executed == ["speedhuntStaticPing"]
    published = gateway.published_status()["participants"][0]["speedhunt"]
    assert published["pings"] == 2 and published["revision"] == revision + 1, published
    return revision + 1


def audit_requests(tool_source=None):
    """Hunters' requests: membership, role-filtered status, revisions, lifecycle, invalidation."""
    password = secrets.token_hex(24)
    hunter_email, player_email = api.PREFIX + "-hunter@example.invalid", api.PREFIX + "-player@example.invalid"
    hunter_id = create_user(api.PREFIX + "-hunter", hunter_email, password, False)
    player_id = create_user(api.PREFIX + "-player", player_email, password, False)
    hunter, player = (hunter_email, password), (player_email, password)
    session = api.request("POST", "/game-sessions", {"name": api.PREFIX + "-requests", "schemaVersion": 4,
                                                     "config": {}})
    session_id = session["id"]
    api.GAME_SESSION_IDS.append(session_id)
    state = api.request("POST", "/devices", {"name": api.PREFIX + "-rq-state", "uniqueId": api.PREFIX + "-rq-state",
                                             "attributes": {"traccarToolGameState": True}})
    tracker = api.request("POST", "/devices", {"name": api.PREFIX + "-rq-anna", "uniqueId": api.PREFIX + "-rq-anna"})
    try:
        api.request("PUT", f"/game-sessions/{session_id}/resources", {
            "expectedRevision": 1, "sessionDeviceId": state["id"],
            "resources": {"devices": {"real": {"anna": tracker["id"]}}},
            "members": [{"userId": hunter_id, "role": "hunter", "number": 1, "assignedDeviceId": None,
                         "login": "hunter1"},
                        {"userId": player_id, "role": "player", "number": 1, "assignedDeviceId": tracker["id"],
                         "login": "player1"}]})
        submission = {"type": "speedhuntStaticPing", "targetDeviceId": tracker["id"], "baseRevision": 1}
        # Not running yet: no Tool executes this session.
        api.request("POST", "/game-requests", submission, credentials=hunter, expected=409)
        api.request("PUT", "/game-controller/session", {"gameSessionId": session_id})
        def publish(revision, running=False):
            return api.request("POST", f"/game-sessions/{session_id}/effects", {
                "deliveryId": "audit" + uuid4().hex,
                "status": {"version": 1, "limits": {"pingsPerSpeedhunt": 4}, "participants": [
                    {"deviceId": tracker["id"], "name": "Anna", "role": "player", "caught": False,
                     "speedhunt": {"revision": revision, "running": running, "pings": 0, "live": False}}]}})
        publish(1, running=True)

        hunter_view = api.request("GET", "/game-status", credentials=hunter)
        assert hunter_view["controllerActive"] and hunter_view["role"] == "hunter"
        assert hunter_view["status"]["participants"][0]["speedhunt"]["revision"] == 1
        player_view = api.request("GET", "/game-status", credentials=player)
        assert player_view["status"]["speedhunt"]["active"] is True
        assert "participants" not in player_view["status"], "players must not learn the speedhunt target"
        api.request("GET", "/game-status", expected=403)

        api.request("POST", "/game-requests", submission, credentials=player, expected=403)
        api.request("POST", "/game-requests", {**submission, "baseRevision": 0}, credentials=hunter, expected=409)
        pending = api.request("POST", "/game-requests", submission, credentials=hunter)
        assert pending["status"] == "pending" and pending["targetLabel"] == "Anna"
        api.request("POST", "/game-requests", submission, credentials=hunter, expected=409)
        assert api.request("GET", "/game-requests", credentials=player) == []

        # The controlling Tool: wait for approval, approve, claim, and commit the outcome with the status.
        base = f"/game-sessions/{session_id}/requests/{pending['id']}"
        api.request("POST", base + "/decision", {"decision": "await"}, epoch=False, expected=423)
        api.request("POST", base + "/decision", {"decision": "await", "message": "Waiting for the operator",
                                                 "detail": "limit"})
        assert api.request("GET", "/game-requests", credentials=hunter)[0]["approvalRequired"] is True
        api.request("POST", base + "/decision", {"decision": "approve"})
        assert api.request("POST", base + "/claim", {})["status"] == "executing"
        api.request("POST", base + "/claim", {}, expected=409)
        outcome = api.request("POST", f"/game-sessions/{session_id}/effects", {
            "deliveryId": "audit" + uuid4().hex,
            "requests": [{"id": pending["id"], "status": "succeeded", "message": "Ping 1 of 4 sent.",
                          "detail": "operator only"}],
            "status": {"version": 1, "participants": [
                {"deviceId": tracker["id"], "name": "Anna", "role": "player", "caught": False,
                 "speedhunt": {"revision": 2, "running": True, "pings": 1, "live": False}}]}})
        assert outcome["requests"] == [{"id": pending["id"], "status": "succeeded"}], outcome
        seen = api.request("GET", "/game-requests", credentials=hunter)[0]
        assert seen["status"] == "succeeded" and seen["message"] == "Ping 1 of 4 sent." and seen["detail"] is None
        assert api.request("GET", f"/game-sessions/{session_id}/requests?state=all")[0]["detail"] == "operator only"

        revision = 2
        if tool_source:
            revision = audit_tool_requests(tool_source, session_id, tracker["id"], lambda base: api.request(
                "POST", "/game-requests", {**submission, "baseRevision": base}, credentials=hunter))
        # Cancelling, and invalidation when another Tool takes over.
        submission["baseRevision"] = revision
        second = api.request("POST", "/game-requests", submission, credentials=hunter)
        assert api.request("POST", f"/game-requests/{second['id']}/cancel", {}, credentials=hunter)["status"] \
            == "cancelled"
        third = api.request("POST", "/game-requests", {**submission, "type": "speedhuntTerminate"},
                            credentials=hunter)
        epoch = api.CONTROLLER["epoch"]
        api.request("POST", "/game-controller/takeover", {"instanceId": "audit-req-" + uuid4().hex[:16],
                                                          "label": "Other PC"}, epoch=False)
        expired = next(item for item in api.request("GET", "/game-requests", credentials=hunter)
                       if item["id"] == third["id"])
        assert expired["status"] == "expired" and "Control of the game changed" in expired["message"]
        api.request("POST", "/game-controller/release", {}, epoch=epoch + 1)
        api.acquire_controller()
        assert not api.request("GET", "/game-status", credentials=hunter)["controllerActive"]
    finally:
        for device_id in (tracker["id"], state["id"]):
            api.request("DELETE", f"/devices/{device_id}", expected=204)
        api.sql(f"DELETE FROM tc_users WHERE id IN ({hunter_id},{player_id}) AND email LIKE "
                + api.literal(api.PREFIX + "-%") + ";")


def main(tool_source=None):
    api.wait_for_server()
    admin_id = create_user(api.PREFIX, api.EMAIL, api.PASSWORD, True)
    user_email = api.PREFIX + "-user@example.invalid"
    user_password = secrets.token_hex(24)
    user_id = create_user(api.PREFIX + "-user", user_email, user_password, False)
    try:
        api.acquire_controller()
        audit_http((user_email, user_password), [])
        audit_controller(user_id, api.PREFIX + "-user", (user_email, user_password))
        audit_requests(tool_source)
        if tool_source:
            audit_tool(tool_source)
        print("MySQL/HTTP game-session audit passed: admin-only access, revision conflicts and idempotent retries, "
              "credential encryption at rest, stable timetable entry IDs, notification history after device "
              "deletion, cascading deletion of saved data, controller lease with fenced game and standard "
              "endpoints, push fencing, drift reporting, takeover, expiry, and idempotent effects with "
              "latest-position and online status, hunters' requests with role-filtered status, stale "
              "revisions, approval, claim, outcome, cancellation and invalidation on takeover"
              + (", plus the Tool's repository round trip." if tool_source else "."))
    finally:
        api.delete_game_sessions()
        api.release_controller()
        api.sql(f"DELETE FROM tc_users WHERE id={user_id} AND email=" + api.literal(user_email) + ";")
        api.sql(f"DELETE FROM tc_users WHERE id={admin_id} AND email=" + api.literal(api.EMAIL) + ";")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tool-source", help="Traccar Tool src directory for the repository round trip")
    main(parser.parse_args().tool_source)
