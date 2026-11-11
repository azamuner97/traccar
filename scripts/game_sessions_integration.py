"""Local MySQL/HTTP audit of central game sessions with isolated fixtures.

Creates a temporary administrator and a temporary non-administrator, exercises
``/api/game-sessions`` directly and through the Tool's server repository
(``--tool-source``), and removes every fixture afterwards. Requires the full
Docker profile with ``TRACCAR_GAME_SESSIONS_CREDENTIAL_KEY`` set in ``.env``.
"""

import argparse
import base64
import hashlib
import json
import secrets
import sys
import tempfile
import time
import urllib.error
import urllib.request
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
    assert api.request("GET", "/game-sessions/capabilities")["apiVersion"] == 4
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


def audit_jokers(session_id, tracker_id, mirror_id, hunter, player):
    """Get hunter positions jokers: grants, reservations, consumption, over-allocation and release."""
    change = f"/game-sessions/{session_id}/jokers/change"
    grant = {"deviceId": tracker_id, "type": "hunterPositions", "quantity": 1, "reason": "audit"}
    api.request("POST", change, grant, epoch=False, expected=423)
    api.request("POST", change, {**grant, "type": "teleport"}, expected=400)
    api.request("POST", change, grant)
    # Hunters read only the speedhunt jokers of their shared pool, never a participant's jokers.
    pool_view = api.request("GET", "/game-jokers", credentials=hunter)
    assert [item["type"] for item in pool_view["inventories"]] == ["speedhunt"], pool_view

    def inventory():
        own = api.request("GET", "/game-jokers", credentials=player)
        return next(item for item in own["inventories"] if item["type"] == "hunterPositions")

    assert inventory()["available"] == 1
    joker = {"type": "jokerHunterPositions"}
    request = api.request("POST", "/game-requests", joker, credentials=player)
    assert request["targetDeviceId"] == tracker_id and request["status"] == "pending"
    assert inventory()["reserved"] == 1 and inventory()["available"] == 0
    api.request("POST", "/game-requests", joker, credentials=player, expected=409)
    assert all(item["type"] != "jokerHunterPositions"
               for item in api.request("GET", "/game-requests", credentials=hunter)), "jokers are private"

    # The Tool delivers each hunter's snapshot with its own fix time, together with the outcome.
    api.request("POST", f"/game-sessions/{session_id}/requests/{request['id']}/claim", {})
    fix_time = (datetime.now(timezone.utc) - timedelta(minutes=3)).strftime("%Y-%m-%dT%H:%M:%S.000Z")
    outcome = api.request("POST", f"/game-sessions/{session_id}/effects", {
        "deliveryId": "audit" + uuid4().hex,
        "positions": [{"deviceId": mirror_id, "latitude": 47.3, "longitude": 8.6, "fixTime": fix_time}],
        "requests": [{"id": request["id"], "status": "succeeded", "message": "Positions of 1 hunter(s) sent."}]})
    assert outcome["positions"][0]["latest"] and outcome["positions"][0]["fixTime"].startswith(fix_time[:19])
    position = api.request("GET", f"/positions?id={stored_device(mirror_id)['positionId']}")[0]
    assert position["fixTime"].startswith(fix_time[:19]), position["fixTime"]
    assert inventory()["used"] == 1 and inventory()["available"] == 0

    # An operator relay without a joker left needs a confirmed use beyond the allocation.
    use = {"deviceId": tracker_id, "type": "hunterPositions", "overAllocate": False, "detail": "Manual relay"}
    api.request("POST", f"/game-sessions/{session_id}/effects",
                {"deliveryId": "audit" + uuid4().hex, "jokers": [use]}, expected=409)
    over = api.request("POST", f"/game-sessions/{session_id}/effects",
                       {"deliveryId": "audit" + uuid4().hex, "jokers": [{**use, "overAllocate": True}]})
    assert over["jokers"] == [{"deviceId": tracker_id, "type": "hunterPositions", "overAllocated": True}]
    assert inventory()["overAllocated"] == 1 and inventory()["available"] == 0
    api.request("POST", "/game-requests", joker, credentials=player, expected=409)

    # Caught: the open request ends and its reserved joker returns.
    api.request("POST", change, {**grant, "quantity": 2})
    pending = api.request("POST", "/game-requests", joker, credentials=player)
    api.request("POST", f"/game-sessions/{session_id}/effects",
                {"deliveryId": "audit" + uuid4().hex, "caughtParticipants": [tracker_id]})
    ended = next(item for item in api.request("GET", "/game-requests", credentials=player)
                 if item["id"] == pending["id"])
    assert ended["status"] == "cancelled", ended
    assert inventory()["reserved"] == 0
    kinds = [entry["kind"] for entry in api.request("GET", f"/game-sessions/{session_id}/jokers/ledger")]
    assert kinds[:7] == ["release", "reserve", "grant", "overuse", "use", "reserve", "grant"], kinds
    api.request("POST", change, {**grant, "quantity": -1})


BREAK_UNAVAILABLE = "A break cannot start right now; try again later"


def audit_speedhunt_jokers(session_id, tracker_id, hunter, player):
    """Speedhunt jokers: one pool shared by the hunters, reserved by a ping that starts a speedhunt, used by the
    run's first ping, and used by the operator's speedhunts beyond the allocation only when confirmed."""
    change = f"/game-sessions/{session_id}/jokers/change"
    effects = f"/game-sessions/{session_id}/effects"
    revision = 40
    api.request("POST", effects, {"deliveryId": "audit" + uuid4().hex, "status": {
        "version": 1, "limits": {}, "participants": [
            {"deviceId": tracker_id, "name": "Anna", "role": "player", "caught": False,
             "speedhunt": {"revision": revision, "running": False, "pings": 0, "live": False},
             "break": {"active": False}}]}})

    def pool():
        own = api.request("GET", "/game-jokers", credentials=hunter)
        return next(item for item in own["inventories"] if item["type"] == "speedhunt")

    def start(over):
        return api.request("POST", effects, {"deliveryId": "audit" + uuid4().hex, "jokers": [
            {"deviceId": tracker_id, "type": "speedhunt", "overAllocate": over, "detail": "Speedhunt started"}]})

    # Speedhunt jokers belong to the hunters' pool (device 0) only.
    api.request("POST", change, {"deviceId": tracker_id, "type": "speedhunt", "quantity": 1}, expected=400)
    ping = {"type": "speedhuntStaticPing", "targetDeviceId": tracker_id, "baseRevision": revision}
    assert refusal("/game-requests", ping, hunter) == "No speedhunt joker is left; ask the operator for more"
    api.request("POST", change, {"deviceId": 0, "type": "speedhunt", "quantity": 2, "reason": "audit"})
    assert pool()["available"] == 2, pool()
    # Players never see the hunters' pool.
    own = api.request("GET", "/game-jokers", credentials=player)
    assert all(item["type"] != "speedhunt" for item in own["inventories"]), own

    # A hunter's ping that would start a speedhunt reserves a pool joker; the run's first ping uses it.
    request = api.request("POST", "/game-requests", ping, credentials=hunter)
    assert pool()["reserved"] == 1, pool()
    api.request("POST", f"/game-sessions/{session_id}/requests/{request['id']}/claim", {})
    used = start(True)
    assert used["jokers"] == [{"deviceId": 0, "type": "speedhunt", "overAllocated": False}], used
    api.request("POST", effects, {"deliveryId": "audit" + uuid4().hex, "requests": [
        {"id": request["id"], "status": "succeeded", "message": "Ping 1 of 4 sent."}]})
    assert pool()["used"] == 1 and pool()["reserved"] == 0 and pool()["available"] == 1, pool()

    # A speedhunt the operator starts uses the pool too; with none left only beyond the allocation.
    start(False)
    api.request("POST", effects, {"deliveryId": "audit" + uuid4().hex, "jokers": [
        {"deviceId": tracker_id, "type": "speedhunt", "overAllocate": False}]}, expected=409)
    assert start(True)["jokers"][0]["overAllocated"] is True
    assert pool()["overAllocated"] == 1 and pool()["available"] == 0, pool()
    kinds = [entry["kind"] for entry in api.request("GET", f"/game-sessions/{session_id}/jokers/ledger")
             if entry["type"] == "speedhunt"]
    assert kinds[:5] == ["overuse", "use", "use", "reserve", "grant"], kinds
    # The following audits ping too: leave a joker in the pool.
    api.request("POST", change, {"deviceId": 0, "type": "speedhunt", "quantity": 3, "reason": "audit"})


def refusal(path, body, credentials, expected=409):
    """The message of a refusal (409 by default): the first line of the server's error text."""
    message = urllib.request.Request(api.BASE + "/api" + path, data=json.dumps(body).encode(), method="POST",
                                     headers={"Content-Type": "application/json", "Authorization": "Basic "
                                              + base64.b64encode(":".join(credentials).encode()).decode()})
    try:
        urllib.request.urlopen(message, timeout=15)
    except urllib.error.HTTPError as error:
        assert error.code == expected, f"POST {path} returned {error.code}, expected {expected}"
        return error.read().decode().splitlines()[0].split(": ", 1)[-1].strip()
    raise AssertionError(f"POST {path} was accepted, expected {expected}")


def run_break_through_tool(tool_source, session_id, tracker_id, settings_minutes):
    """The Tool's request processor and break service approve and start the break."""
    sys.path.insert(0, tool_source)
    import requests
    from traccar_game_manager.application.game_actions.breaks import BreakService
    from traccar_game_manager.application.game_requests.processor import (
        GameRequestProcessor, RequestCheck, RequestResult, SpeedhuntStatusTracker)
    from traccar_game_manager.domain.game.settings import normalize_game_settings
    from traccar_game_manager.infrastructure.traccar.effects import SessionEffects
    from traccar_game_manager.infrastructure.traccar.game_requests import GameRequestGateway
    from traccar_game_manager.infrastructure.traccar.game_sessions import GameSessionApi

    class Transport:
        get, post, put, delete = (staticmethod(requests.get), staticmethod(requests.post),
                                  staticmethod(requests.put), staticmethod(requests.delete))

    class Clock:
        def now(self):
            return datetime.now(timezone.utc)

    client = GameSessionApi(api.BASE, api.EMAIL, api.PASSWORD, transport=Transport(),
                            epoch_provider=lambda: api.CONTROLLER["epoch"])
    gateway = GameRequestGateway(client, session_id, SessionEffects(client, session_id))
    settings = normalize_game_settings({"break_duration_minutes": settings_minutes})
    breaks = BreakService({}, clock=Clock())
    tracker = SpeedhuntStatusTracker()
    tracker.seed(gateway.published_status())

    def status():
        participant = {"deviceId": tracker_id, "name": "Anna", "role": "player", "caught": False,
                       "speedhunt": {"running": False, "pings": 1, "live": False},
                       "break": breaks.published(tracker_id)}
        return tracker.build([participant]), tracker

    class Actions:
        def participant(self, device_id):
            return {"id": device_id, "name": "Anna", "caught": False}

        def check(self, kind, item, parameters):
            return RequestCheck(error=breaks.activation_error(item["id"], settings=settings,
                                                              speedhunt_running=False))

        def execute(self, kind, item, parameters):
            breaks.start(item["id"], settings=settings)
            return RequestResult(True, "Your break runs for 15 min.")

    processor = GameRequestProcessor(gateway, Actions(), settings=lambda: settings, status=status,
                                     log=lambda _message: None)
    processor.sync(gateway.list_open())
    awaiting = processor.awaiting()
    assert [item["type"] for item in awaiting] == ["jokerBreak"], awaiting
    processor.approve(awaiting[0]["id"])
    return breaks.published(tracker_id)


def audit_breaks(session_id, tracker_id, hunter, player, tool_source=None):
    """Break jokers: a configured duration, one refusal for everyone during speedhunts, the published break."""
    change = f"/game-sessions/{session_id}/jokers/change"
    effects = f"/game-sessions/{session_id}/effects"
    grant = {"deviceId": tracker_id, "type": "break", "quantity": 1, "reason": "audit"}
    api.request("POST", change, grant, expected=409)  # no break duration configured yet
    stored = api.request("GET", f"/game-sessions/{session_id}")
    api.request("PUT", f"/game-sessions/{session_id}/config", {
        "expectedRevision": stored["configRevision"], "document": {"game_settings": {"break_duration_minutes": 15}}})
    api.request("POST", change, {**grant, "quantity": 2})

    def breaks():
        own = api.request("GET", "/game-jokers", credentials=player)
        return next(item for item in own["inventories"] if item["type"] == "break")

    def publish(running=None, pause=None, revision=2):
        participants = [{"deviceId": tracker_id, "name": "Anna", "role": "player", "caught": False,
                         "speedhunt": {"revision": revision, "running": running == "own", "pings": 1,
                                       "live": False},
                         "break": pause or {"active": False}}]
        if running == "other":
            participants.append({"deviceId": tracker_id + 1_000_000, "name": "Other", "role": "supporter",
                                 "caught": False, "speedhunt": {"revision": 1, "running": False, "live": True},
                                 "break": {"active": False}})
        api.request("POST", effects, {"deliveryId": "audit" + uuid4().hex, "status": {
            "version": 1, "limits": {}, "participants": participants}})

    pause = {"type": "jokerBreak"}
    # While any speedhunt runs, the answer is the same whether it targets the requester or someone else.
    publish(running="own")
    on_self = refusal("/game-requests", pause, player)
    publish(running="other")
    on_other = refusal("/game-requests", pause, player)
    assert on_self == on_other == BREAK_UNAVAILABLE, (on_self, on_other)
    assert breaks()["reserved"] == 0, "a refused break reserves nothing"

    publish()
    request = api.request("POST", "/game-requests", pause, credentials=player)
    assert request["targetDeviceId"] == tracker_id and request["type"] == "jokerBreak", request
    assert breaks()["reserved"] == 1
    if tool_source:
        published = run_break_through_tool(tool_source, session_id, tracker_id, 15)
        assert published["active"], published
    else:
        api.request("POST", f"/game-sessions/{session_id}/requests/{request['id']}/claim", {})
        now = datetime.now(timezone.utc).replace(microsecond=0)
        published = {"active": True, "startedAt": now.isoformat().replace("+00:00", "Z"),
                     "endsAt": (now + timedelta(minutes=15)).isoformat().replace("+00:00", "Z")}
        api.request("POST", effects, {
            "deliveryId": "audit" + uuid4().hex,
            "requests": [{"id": request["id"], "status": "succeeded", "message": "Your break runs for 15 min."}],
            "status": {"version": 1, "limits": {}, "participants": [
                {"deviceId": tracker_id, "name": "Anna", "role": "player", "caught": False,
                 "speedhunt": {"revision": 2, "running": False, "pings": 1, "live": False},
                 "break": published}]}})
    done = next(item for item in api.request("GET", "/game-requests", credentials=player)
                if item["id"] == request["id"])
    assert done["status"] == "succeeded", done
    assert breaks()["used"] == 1 and breaks()["reserved"] == 0 and breaks()["available"] == 1, breaks()

    # The participant sees their own break and its deadline; hunters see who is on a break.
    own = api.request("GET", "/game-status", credentials=player)["status"]
    assert own["break"]["active"] and own["break"]["endsAt"] == published["endsAt"], own
    assert "participants" not in own
    hunter_view = api.request("GET", "/game-status", credentials=hunter)["status"]["participants"][0]
    assert hunter_view["break"]["active"], hunter_view
    revision = hunter_view["speedhunt"]["revision"]
    ping = {"type": "speedhuntStaticPing", "targetDeviceId": tracker_id, "baseRevision": revision}
    assert refusal("/game-requests", ping, hunter) == "This participant is on a break"
    assert refusal("/game-requests", pause, player) == "You are already on a break"

    # The Tool publishes the end of the break: hunters may act on the participant again.
    publish(revision=revision)
    assert not api.request("GET", "/game-status", credentials=player)["status"]["break"]["active"]
    resumed = api.request("POST", "/game-requests", ping, credentials=hunter)
    api.request("POST", f"/game-requests/{resumed['id']}/cancel", {}, credentials=hunter)
    kinds = [entry["kind"] for entry in api.request("GET", f"/game-sessions/{session_id}/jokers/ledger")
             if entry["type"] == "break"]
    assert kinds[:3] == ["use", "reserve", "grant"], kinds
    api.request("POST", change, {**grant, "quantity": -1})
    audit_planned_breaks(session_id, tracker_id, hunter, player, publish, breaks, revision, tool_source)


def audit_planned_breaks(session_id, tracker_id, hunter, player, publish, breaks, revision, tool_source=None):
    """Planned breaks: accepted during a speedhunt, armed, private, never overlapping, cancelled or used."""
    change = f"/game-sessions/{session_id}/jokers/change"
    effects = f"/game-sessions/{session_id}/effects"
    plan_endpoint = f"/game-sessions/{session_id}/jokers/breaks"
    api.request("POST", change, {"deviceId": tracker_id, "type": "break", "quantity": 1, "reason": "audit"})
    publish(running="other", revision=revision)
    later = datetime.now(timezone.utc).replace(microsecond=0) + timedelta(hours=1)
    planned = {"type": "jokerBreak", "parameters": {"startsAt": _iso(later)}}

    def armed():
        return [item for item in api.request("GET", "/game-jokers", credentials=player)["reservations"]
                if item["armed"] and item["type"] == "break"]

    # While a speedhunt runs a break that starts now is refused for everyone, but a planned one is accepted.
    assert refusal("/game-requests", {"type": "jokerBreak"}, player) == BREAK_UNAVAILABLE
    request = api.request("POST", "/game-requests", planned, credentials=player)
    assert request["parameters"]["startsAt"] == _iso(later), request
    api.request("POST", f"/game-sessions/{session_id}/requests/{request['id']}/claim", {})
    api.request("POST", effects, {"deliveryId": "audit" + uuid4().hex, "requests": [
        {"id": request["id"], "status": "succeeded", "message": "Your break is planned."}]})
    plans = armed()
    assert len(plans) == 1 and plans[0]["plan"]["startsAt"] == _iso(later), plans
    assert breaks()["reserved"] == 1 and breaks()["used"] == 1, breaks()
    # Private: hunters see neither the plan nor the request; the owner's status lists no fake for it.
    hunter_view = json.dumps(api.request("GET", "/game-status", credentials=hunter))
    assert _iso(later) not in hunter_view, hunter_view
    assert all(item["type"] != "jokerBreak" for item in api.request("GET", "/game-requests", credentials=hunter))
    assert api.request("GET", "/game-status", credentials=player)["status"]["fakes"] == []
    # Breaks of one participant never overlap.
    overlapping = {"type": "jokerBreak", "parameters": {"startsAt": _iso(later + timedelta(minutes=5))}}
    assert refusal("/game-requests", overlapping, player) == "This break would overlap another break of yours"
    assert api.request("POST", f"/game-jokers/reservations/{plans[0]['id']}/cancel", {},
                       credentials=player)["status"] == "released"
    assert breaks()["reserved"] == 0 and breaks()["available"] == 1, breaks()

    # An operator plans breaks: with the available joker, then beyond the allocation only when confirmed.
    first = api.request("POST", plan_endpoint, {"deviceId": tracker_id, "startsAt": _iso(later)})
    assert first["armed"] and first["plan"]["plannedBy"] == "operator", first
    api.request("POST", plan_endpoint, {"deviceId": tracker_id, "startsAt": _iso(later + timedelta(minutes=5)),
                                        "overAllocate": True}, expected=409)
    second_start = later + timedelta(hours=1)
    api.request("POST", plan_endpoint, {"deviceId": tracker_id, "startsAt": _iso(second_start)}, expected=409)
    second = api.request("POST", plan_endpoint, {"deviceId": tracker_id, "startsAt": _iso(second_start),
                                                 "overAllocate": True})
    # Participants cannot plan for others: the endpoint is fenced, so their accounts are refused first.
    api.request("POST", plan_endpoint, {"deviceId": tracker_id, "startsAt": _iso(later)}, credentials=player,
                expected=423)
    if tool_source:
        sys.path.insert(0, tool_source)
        from traccar_game_manager.application.game_actions.planned_breaks import planned_breaks
        overview = api.request("GET", f"/game-sessions/{session_id}/jokers")
        known = planned_breaks(overview)
        assert sorted(known) == sorted([first["id"], second["id"]]), known
        # The break starts now and lasts its full duration (15 minutes in this session).
        used = known[first["id"]].used(None, timedelta(minutes=15))
        released = known[second["id"]].released("Cancelled by the operator")
    else:
        used = {"reservationId": first["id"], "outcome": "used", "detail": "The break started"}
        released = {"reservationId": second["id"], "outcome": "released", "detail": "Cancelled by the operator"}
    # The Tool starts the first break (its joker is used) and the operator cancels the second.
    outcome = api.request("POST", effects, {"deliveryId": "audit" + uuid4().hex, "fakes": [used, released]})
    assert [item["status"] for item in outcome["fakes"]] == ["consumed", "released"], outcome
    assert breaks()["used"] == 2 and breaks()["reserved"] == 0 and breaks()["overAllocated"] == 0, breaks()
    api.request("POST", f"/game-jokers/reservations/{first['id']}/cancel", {}, credentials=player, expected=409)
    kinds = [entry["kind"] for entry in api.request("GET", f"/game-sessions/{session_id}/jokers/ledger")
             if entry["type"] == "break"]
    assert kinds[:4] == ["release", "use", "arm", "arm"], kinds
    publish(revision=revision)


def audit_mirror_only_access(session_id, tracker_id, hunter_id, player_id):
    """With break jokers in the session, hunters never get real participant trackers, directly or through groups."""
    parent = api.request("POST", "/groups", {"name": api.PREFIX + "-real-parent"})
    real = api.request("POST", "/groups", {"name": api.PREFIX + "-real", "groupId": parent["id"]})
    view = api.request("POST", "/groups", {"name": api.PREFIX + "-hunter-view"})
    device = api.request("GET", f"/devices/{tracker_id}")
    second_id = create_user(api.PREFIX + "-hunter2", api.PREFIX + "-hunter2@example.invalid", secrets.token_hex(24),
                            False)
    try:
        api.request("PUT", f"/devices/{tracker_id}", {**device, "groupId": real["id"]})
        # Direct access, access inherited from a parent group, and moves into a group a hunter sees are refused.
        api.request("POST", "/permissions", {"userId": hunter_id, "deviceId": tracker_id}, expected=422)
        api.request("POST", "/permissions", {"userId": hunter_id, "groupId": parent["id"]}, expected=422)
        api.request("POST", "/permissions", {"userId": hunter_id, "groupId": view["id"]}, expected=204)
        group = api.request("GET", f"/groups/{real['id']}")
        api.request("PUT", f"/groups/{real['id']}", {**group, "groupId": view["id"]}, expected=422)
        api.request("PUT", f"/devices/{tracker_id}", {**device, "groupId": view["id"]}, expected=422)
        # Other members keep their real access.
        api.request("POST", "/permissions", {"userId": player_id, "groupId": parent["id"]}, expected=204)
        api.request("DELETE", "/permissions", {"userId": player_id, "groupId": parent["id"]}, expected=204)

        # Access granted behind the server's back blocks new break jokers and membership saves.
        api.sql(f"INSERT INTO tc_user_device(userid,deviceid) VALUES({hunter_id},{tracker_id});")
        change = f"/game-sessions/{session_id}/jokers/change"
        api.request("POST", change, {"deviceId": tracker_id, "type": "break", "quantity": 1}, expected=409)
        api.request("POST", change, {"deviceId": tracker_id, "type": "hunterPositions", "quantity": 1})
        api.request("POST", change, {"deviceId": tracker_id, "type": "hunterPositions", "quantity": -1})
        # A save that does not add such access is accepted; a new hunter who already has it is refused.
        stored = api.request("GET", f"/game-sessions/{session_id}")
        api.request("PUT", f"/game-sessions/{session_id}/resources", {
            "expectedRevision": stored["resourcesRevision"], "sessionDeviceId": stored["sessionDeviceId"],
            "resources": stored["resources"], "members": stored["members"]})
        api.sql(f"INSERT INTO tc_user_device(userid,deviceid) VALUES({second_id},{tracker_id});")
        stored = api.request("GET", f"/game-sessions/{session_id}")
        second = {"userId": second_id, "role": "hunter", "number": 2, "assignedDeviceId": None, "login": "hunter2"}
        api.request("PUT", f"/game-sessions/{session_id}/resources", {
            "expectedRevision": stored["resourcesRevision"], "sessionDeviceId": stored["sessionDeviceId"],
            "resources": stored["resources"], "members": stored["members"] + [second]}, expected=422)
        assert api.request("GET", f"/game-sessions/{session_id}")["resourcesRevision"] \
            == stored["resourcesRevision"], "a refused membership save changes nothing"
    finally:
        api.sql(f"DELETE FROM tc_user_device WHERE userid IN ({hunter_id},{second_id}) AND deviceid={tracker_id};")
        api.sql(f"DELETE FROM tc_users WHERE id={second_id} AND email LIKE " + api.literal(api.PREFIX + "-%") + ";")
        api.request("PUT", f"/devices/{tracker_id}", {**device, "groupId": 0})
        for group_id in (real["id"], parent["id"], view["id"]):
            api.request("DELETE", f"/groups/{group_id}", expected=204)


def _iso(moment):
    return moment.isoformat().replace("+00:00", "Z")


def member_socket(credentials):
    """A signed-in member's live-update WebSocket."""
    import http.cookiejar
    import urllib.parse
    import websocket
    cookies = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies))
    api.request("POST", "/session", urllib.parse.urlencode({"email": credentials[0], "password": credentials[1]})
                .encode(), credentials=None, opener=opener)
    return websocket.create_connection("ws://localhost:8083/api/socket", timeout=10, origin=api.BASE,
                                       cookie="; ".join(cookie.name + "=" + cookie.value for cookie in cookies))


def invalidations(socket, seconds=1.5):
    """Every resource the socket was told to reload within the given time."""
    import websocket
    found = set()
    deadline = time.monotonic() + seconds
    socket.settimeout(0.3)
    try:
        while time.monotonic() < deadline:
            try:
                found.update(json.loads(socket.recv()).get("invalidate", []))
            except websocket.WebSocketTimeoutException:
                pass
    finally:
        socket.settimeout(10)
    return found


def run_timetable_fake_through_tool(tool_source, session_id, tracker, mirror, name, while_armed):
    """The Tool's processor arms the fake, then a timetable row gives the hunters the fake and a real ping follows."""
    sys.path.insert(0, tool_source)
    import requests
    from traccar_game_manager.application.game_actions.fakes import armed_timetable_fakes, timetable_fake_error
    from traccar_game_manager.application.game_requests.processor import (
        GameRequestProcessor, RequestCheck, RequestResult, SpeedhuntStatusTracker)
    from traccar_game_manager.application.timetable.execution import TimetableRowExecutionService
    from traccar_game_manager.application.tracking.outgoing import OutgoingPositionPolicy, ParticipantPingSender
    from traccar_game_manager.application.tracking.positions import PositionUploadService, TargetResolutionContext
    from traccar_game_manager.domain.game.settings import normalize_game_settings
    from traccar_game_manager.infrastructure.traccar.effects import SessionEffects
    from traccar_game_manager.infrastructure.traccar.game_requests import GameRequestGateway
    from traccar_game_manager.infrastructure.traccar.game_sessions import GameSessionApi

    class Transport:
        get, post, put, delete = (staticmethod(requests.get), staticmethod(requests.post),
                                  staticmethod(requests.put), staticmethod(requests.delete))

    client = GameSessionApi(api.BASE, api.EMAIL, api.PASSWORD, transport=Transport(),
                            epoch_provider=lambda: api.CONTROLLER["epoch"])
    effects = SessionEffects(client, session_id)
    gateway = GameRequestGateway(client, session_id, effects)
    tracker_status = SpeedhuntStatusTracker()
    tracker_status.seed(gateway.published_status())

    def status():
        return tracker_status.build([{"deviceId": tracker["id"], "name": "Anna", "role": "player", "caught": False,
                                      "speedhunt": {"running": False, "pings": 1, "live": False},
                                      "break": {"active": False}}]), tracker_status

    class Actions:
        def participant(self, device_id):
            return {"id": device_id, "name": "Anna", "caught": False}

        def check(self, kind, item, parameters):
            return RequestCheck(error=timetable_fake_error(parameters, now=datetime.now(timezone.utc)))

        def execute(self, kind, item, parameters):
            return RequestResult(True, "Your fake ping is armed.")

    settings = normalize_game_settings({})
    processor = GameRequestProcessor(gateway, Actions(), settings=lambda: settings, status=status,
                                     log=lambda _message: None)
    processor.sync(gateway.list_open())
    awaiting = processor.awaiting()
    assert [item["type"] for item in awaiting] == ["jokerFakePing"], awaiting
    processor.approve(awaiting[0]["id"])
    armed = list(armed_timetable_fakes(gateway.joker_overview()).values())
    assert len(armed) == 1, armed
    while_armed()

    class Positions:
        def upload_position(self, *_args):
            raise AssertionError("game pings go through effects")

        def effects_available(self):
            return True

        def upload_positions(self, items, fakes=()):
            return effects.deliver(positions=items, fakes=fakes)

    context = TargetResolutionContext(
        session={"devices": {"mirroring": {"player_1_to_hunters": mirror["id"]}}},
        devices_by_id={mirror["id"]: {"uniqueId": mirror["uniqueId"]}},
        player_indexes={tracker["id"]: 1}, supporter_indexes={}, hunter_indexes={})

    def deliver(device_id, latitude, longitude, targets, **fake):
        return PositionUploadService(Positions()).upload_participant(device_id, latitude, longitude, targets,
                                                                     context, **fake)

    class Now:
        def call_later(self, _delay, callback):
            callback()

    released, events = [], []
    execution = TimetableRowExecutionService(
        Now(), lambda _id, *, callback, min_fix_time: callback({"latitude": 47.0, "longitude": 8.0}, True, None),
        ParticipantPingSender(OutgoingPositionPolicy(lambda _id: False), deliver),
        is_caught=lambda _id: False, event_sink=lambda message, _device: events.append(message),
        local_tz=timezone.utc, no_location_message=lambda label: f"No location for {label}",
        release_fake=lambda fake, reason: released.append((fake.reservation_id, reason)))
    device = {"id": tracker["id"], "name": "Anna"}
    execution.execute([(name, "")], {name: device}, None, lambda: None, is_current=lambda: True,
                      fakes={name: armed[0]})
    assert released == [], released
    fake_position = api.request("GET", f"/positions?id={stored_device(mirror['id'])['positionId']}")[0]
    execution.execute([(name, "")], {name: device}, None, lambda: None, is_current=lambda: True)
    real_position = api.request("GET", f"/positions?id={stored_device(mirror['id'])['positionId']}")[0]
    return armed[0].reservation_id, fake_position, real_position


def audit_timetable_fakes(session_id, tracker, mirror, hunter, player, tool_source=None):
    """Timetable fakes: eligible pings, plans private to their owner, used with the hunters' delivery, else released."""
    name = tracker["name"]
    base = (datetime.now(timezone.utc) + timedelta(hours=1)).replace(second=0, microsecond=0)
    cells = ["", "b", "47.1,8.5|2H", "2P2S", "2H"]
    entries = [{"scheduledAt": _iso(base + timedelta(minutes=10 * index)), "participant": name, "content": cell}
               for index, cell in enumerate(cells)]
    timetable = f"/game-sessions/{session_id}/timetable"
    stored = api.request("GET", timetable)
    published = api.request("PUT", timetable, {"expectedRevision": stored["revision"], "columns": ["Time", name],
                                               "source": {}, "entries": entries})
    ids = {entry["content"]: entry["id"] for entry in published["entries"]}
    change = f"/game-sessions/{session_id}/jokers/change"
    effects = f"/game-sessions/{session_id}/effects"
    api.request("POST", change, {"deviceId": tracker["id"], "type": "fakePing", "quantity": 3, "reason": "audit"})
    # The earlier fake types are gone: the one fake-ping joker is used on a timetable ping or the next speedhunt.
    api.request("POST", change, {"deviceId": tracker["id"], "type": "fakeTimetable", "quantity": 1}, expected=400)

    def inventory():
        own = api.request("GET", "/game-jokers", credentials=player)
        return next(item for item in own["inventories"] if item["type"] == "fakePing")

    def fake(entry_id):
        return {"type": "jokerFakePing", "parameters": {"entryId": entry_id, "latitude": 47.2, "longitude": 8.5}}

    def armed():
        return [item for item in api.request("GET", "/game-jokers", credentials=player)["reservations"]
                if item["armed"]]

    def arm(entry_id):
        request = api.request("POST", "/game-requests", fake(entry_id), credentials=player)
        api.request("POST", f"/game-sessions/{session_id}/requests/{request['id']}/claim", {})
        api.request("POST", effects, {"deliveryId": "audit" + uuid4().hex, "requests": [
            {"id": request["id"], "status": "succeeded", "message": "Your fake ping is armed."}]})
        return next(item["id"] for item in armed() if item["plan"].get("entryId") == entry_id)

    # A fake ping names either a timetable ping or a ping of the next speedhunt, never both.
    api.request("POST", "/game-requests", {"type": "jokerFakePing", "parameters": {
        "entryId": ids[""], "pingNumber": 1, "latitude": 47.2, "longitude": 8.5}}, credentials=player, expected=400)
    api.request("POST", "/game-requests", {"type": "jokerFakeTimetable", "parameters": {
        "entryId": ids[""], "latitude": 47.2, "longitude": 8.5}}, credentials=player, expected=400)
    # Only the owner's own upcoming pings that send the real position to hunters can carry a fake.
    offered = api.request("GET", "/game-jokers", credentials=player)["timetableEntries"]
    assert [item["entryId"] for item in offered] == [ids[""], ids["2H"]], offered
    for content in ("b", "47.1,8.5|2H", "2P2S"):
        api.request("POST", "/game-requests", fake(ids[content]), credentials=player, expected=409)
    request = api.request("POST", "/game-requests", fake(ids[""]), credentials=player)
    assert request["parameters"]["scheduledAt"].startswith(entries[0]["scheduledAt"][:16]), request
    assert inventory()["reserved"] == 1
    # Hunters must not learn when a fake is armed or used: joker changes reach only their owner (and operators).
    hunter_socket, player_socket = member_socket(hunter), member_socket(player)
    invalidations(hunter_socket, 0.5)
    invalidations(player_socket, 0.5)

    def private():
        # The plan is private: its owner sees it, hunters see neither the plan nor the request.
        own = api.request("GET", "/game-status", credentials=player)["status"]
        assert own["fakes"][0]["entryId"] == ids[""] and own["fakes"][0]["use"] == "timetable", own
        hunter_view = json.dumps(api.request("GET", "/game-status", credentials=hunter))
        assert "fakes" not in hunter_view and "47.2" not in hunter_view, hunter_view
        assert all(item["type"] != "jokerFakePing"
                   for item in api.request("GET", "/game-requests", credentials=hunter))

    if tool_source:
        reservation, fake_position, real_position = run_timetable_fake_through_tool(
            tool_source, session_id, tracker, mirror, name, private)
        assert (fake_position["latitude"], fake_position["longitude"]) == (47.2, 8.5), fake_position
        assert (real_position["latitude"], real_position["longitude"]) == (47.0, 8.0), real_position
        # Fake and real pings carry the same payload: only the coordinate differs.
        for key in ("attributes", "accuracy", "speed", "course", "altitude", "valid", "protocol"):
            assert fake_position[key] == real_position[key], key
    else:
        api.request("POST", f"/game-sessions/{session_id}/requests/{request['id']}/claim", {})
        api.request("POST", effects, {"deliveryId": "audit" + uuid4().hex, "requests": [
            {"id": request["id"], "status": "succeeded", "message": "Your fake ping is armed."}]})
        reservation = armed()[0]["id"]
        private()
        outcome = api.request("POST", effects, {
            "deliveryId": "audit" + uuid4().hex,
            "positions": [{"uniqueId": mirror["uniqueId"], "latitude": 47.2, "longitude": 8.5}],
            "fakes": [{"reservationId": reservation, "outcome": "used"}]})
        assert outcome["fakes"] == [{"reservationId": reservation, "status": "consumed"}], outcome
    assert inventory()["used"] == 1 and inventory()["reserved"] == 0, inventory()
    try:
        assert "game-jokers" in invalidations(player_socket), "the owner is told that their jokers changed"
        assert "game-jokers" not in invalidations(hunter_socket, 0.5), "a hunter learned that a joker changed"
    finally:
        hunter_socket.close()
        player_socket.close()

    # The owner may cancel an armed fake; a ping that stops reaching hunters releases its fake.
    cancelled = arm(ids["2H"])
    api.request("POST", f"/game-jokers/reservations/{cancelled}/cancel", {}, credentials=hunter, expected=403)
    assert api.request("POST", f"/game-jokers/reservations/{cancelled}/cancel", {},
                       credentials=player)["status"] == "released"
    replaced = arm(ids["2H"])
    entries[4]["content"] = "b"
    api.request("PUT", timetable, {"expectedRevision": published["revision"], "columns": ["Time", name],
                                   "source": {}, "entries": entries})
    assert armed() == [] and inventory()["used"] == 1 and inventory()["available"] == 2, inventory()
    kinds = [entry["kind"] for entry in api.request("GET", f"/game-sessions/{session_id}/jokers/ledger")
             if entry["type"] == "fakePing"]
    assert kinds[:7] == ["release", "arm", "reserve", "release", "arm", "reserve", "use"], kinds
    assert replaced != cancelled


def run_speedhunt_fake_through_tool(tool_source, session_id, tracker, mirror):
    """The Tool's speedhunt service binds the armed fake at the first ping and gives the hunters ping 2's fake."""
    sys.path.insert(0, tool_source)
    import requests
    from traccar_game_manager.application.game_actions.fakes import armed_speedhunt_fakes
    from traccar_game_manager.application.game_actions.speedhunt import SpeedhuntRuntimeState, SpeedhuntService
    from traccar_game_manager.application.tracking.outgoing import OutgoingPositionPolicy, ParticipantPingSender
    from traccar_game_manager.application.tracking.positions import PositionUploadService, TargetResolutionContext
    from traccar_game_manager.domain.game.settings import normalize_game_settings
    from traccar_game_manager.infrastructure.traccar.effects import SessionEffects
    from traccar_game_manager.infrastructure.traccar.game_requests import GameRequestGateway
    from traccar_game_manager.infrastructure.traccar.game_sessions import GameSessionApi

    class Transport:
        get, post, put, delete = (staticmethod(requests.get), staticmethod(requests.post),
                                  staticmethod(requests.put), staticmethod(requests.delete))

    client = GameSessionApi(api.BASE, api.EMAIL, api.PASSWORD, transport=Transport(),
                            epoch_provider=lambda: api.CONTROLLER["epoch"])
    effects = SessionEffects(client, session_id)
    gateway = GameRequestGateway(client, session_id, effects)

    class Positions:
        def upload_position(self, *_args):
            raise AssertionError("game pings go through effects")

        def effects_available(self):
            return True

        def upload_positions(self, items, fakes=()):
            return effects.deliver(positions=items, fakes=fakes)

    context = TargetResolutionContext(
        session={"devices": {"mirroring": {"player_1_to_hunters": mirror["id"]}}},
        devices_by_id={mirror["id"]: {"uniqueId": mirror["uniqueId"]}},
        player_indexes={tracker["id"]: 1}, supporter_indexes={}, hunter_indexes={})

    def deliver(device_id, latitude, longitude, targets, **fake):
        return PositionUploadService(Positions()).upload_participant(device_id, latitude, longitude, targets,
                                                                     context, **fake)

    class Clock:
        def now(self):
            return datetime.now(timezone.utc)

    class Scheduler:
        def call_later(self, _delay, callback):
            return None

        def cancel(self, _handle):
            return None

    class View:
        def run_state_changed(self, _id):
            pass

        totals_changed = history_changed = live_state_changed = run_state_changed

    class Live:
        def stop_live(self, participant, *, reason, log_stop):
            pass

        def release_live(self, _id):
            pass

    class Fakes:
        def armed_speedhunt_fakes(self):
            return list(armed_speedhunt_fakes(gateway.joker_overview()).values())

        def speedhunt_fakes_bound(self, _ids):
            pass

    events = []
    state = SpeedhuntRuntimeState()
    service = SpeedhuntService(
        state, clock=Clock(), scheduler=Scheduler(), local_tz=lambda: timezone.utc,
        settings=lambda: normalize_game_settings({"pings_per_speedhunt": 4}), persist=lambda: True,
        log_sink=lambda _participant, text, _metadata: events.append(text) or True, view=View(), live=Live(),
        sender=ParticipantPingSender(OutgoingPositionPolicy(lambda _id: False), deliver),
        targets=lambda _id: {"hunters": True, "players": False, "supporters": False},
        has_any_target=lambda _id, config: any(config.values()), describe_targets=lambda _config: "Hunters",
        keeps_operation_open=lambda _id: False,
        position_fetcher=lambda _id: ({"latitude": 47.0, "longitude": 8.0}, True),
        no_location_message=lambda label: f"No location for {label}", fakes=Fakes())
    participant = {"id": tracker["id"], "name": "Anna"}
    positions = []
    for _ping in range(3):
        outcome = service.send_static_ping(participant)
        assert outcome.status == "counted", (outcome, events)
        positions.append(api.request("GET", f"/positions?id={stored_device(mirror['id'])['positionId']}")[0])
    assert any("bound to this speedhunt" in line for line in events), events
    return positions


def audit_speedhunt_fakes(session_id, tracker, mirror, hunter, player, tool_source=None):
    """Speedhunt fakes: armed for the next run, bound for every owner at its first ping, private, never refunded."""
    stored = api.request("GET", f"/game-sessions/{session_id}")
    config = dict(stored["config"])
    config["game_settings"] = {**(config.get("game_settings") or {}), "pings_per_speedhunt": 4}
    api.request("PUT", f"/game-sessions/{session_id}/config",
                {"expectedRevision": stored["configRevision"], "document": config})
    change = f"/game-sessions/{session_id}/jokers/change"
    effects = f"/game-sessions/{session_id}/effects"
    api.request("POST", change, {"deviceId": tracker["id"], "type": "fakePing", "quantity": 2, "reason": "audit"})

    def inventory():
        own = api.request("GET", "/game-jokers", credentials=player)
        return next(item for item in own["inventories"] if item["type"] == "fakePing")

    # The same fake-ping inventory served the timetable fakes before: count from here.
    start = inventory()

    def fake(ping_number):
        return {"type": "jokerFakePing",
                "parameters": {"pingNumber": ping_number, "latitude": 47.3, "longitude": 8.6}}

    def arm(ping_number):
        request = api.request("POST", "/game-requests", fake(ping_number), credentials=player)
        api.request("POST", f"/game-sessions/{session_id}/requests/{request['id']}/claim", {})
        api.request("POST", effects, {"deliveryId": "audit" + uuid4().hex, "requests": [
            {"id": request["id"], "status": "succeeded", "message": "Your fake is armed."}]})
        own = api.request("GET", "/game-jokers", credentials=player)["reservations"]
        return next(item["id"] for item in own if item["armed"] and item["use"] == "speedhunt")

    api.request("POST", "/game-requests", fake(5), credentials=player, expected=409)
    api.request("POST", "/game-requests", fake(0), credentials=player, expected=400)
    reservation = arm(2)
    api.request("POST", "/game-requests", fake(3), credentials=player, expected=409)
    assert inventory()["reserved"] == start["reserved"] + 1 and inventory()["used"] == start["used"], inventory()
    own = api.request("GET", "/game-status", credentials=player)["status"]
    assert [(item["pingNumber"], item["use"]) for item in own["fakes"]] == [(2, "speedhunt")], own
    hunter_view = json.dumps(api.request("GET", "/game-status", credentials=hunter))
    assert "fakes" not in hunter_view and "47.3" not in hunter_view, hunter_view

    hunter_socket, player_socket = member_socket(hunter), member_socket(player)
    invalidations(hunter_socket, 0.5)
    invalidations(player_socket, 0.5)
    if tool_source:
        positions = run_speedhunt_fake_through_tool(tool_source, session_id, tracker, mirror)
        assert [(item["latitude"], item["longitude"]) for item in positions] \
            == [(47.0, 8.0), (47.3, 8.6), (47.0, 8.0)], positions
        for key in ("attributes", "accuracy", "speed", "course", "altitude", "valid", "protocol"):
            assert positions[0][key] == positions[1][key], key
    else:
        bound = api.request("POST", effects, {
            "deliveryId": "audit" + uuid4().hex,
            "positions": [{"uniqueId": mirror["uniqueId"], "latitude": 47.0, "longitude": 8.0}],
            "fakes": [{"reservationId": reservation, "outcome": "bound", "run": "audit-run"}]})
        assert bound["fakes"] == [{"reservationId": reservation, "status": "consumed"}], bound
    try:
        assert "game-jokers" in invalidations(player_socket), "the owner is told that their fake was bound"
        assert "game-jokers" not in invalidations(hunter_socket, 0.5), "a hunter learned that a fake was bound"
    finally:
        hunter_socket.close()
        player_socket.close()

    # Bound: used for good, shown to its owner as bound, never cancelled or refunded.
    assert inventory()["used"] == start["used"] + 1 and inventory()["reserved"] == start["reserved"], inventory()
    bound = [item for item in api.request("GET", "/game-jokers", credentials=player)["reservations"]
             if item["bound"]]
    assert bound and bound[0]["plan"]["pingNumber"] == 2, bound
    # The owner never learns which run bound it, so nothing tells them whom the speedhunt targets.
    assert "boundTo" not in bound[0]["plan"], bound
    # Operators see every owner's binding, with the run reference, in the overview.
    operator_bound = [item for item in api.request("GET", f"/game-sessions/{session_id}/jokers")["boundFakes"]
                      if item["id"] == reservation]
    assert operator_bound and operator_bound[0]["plan"].get("boundTo"), operator_bound
    api.request("POST", f"/game-jokers/reservations/{reservation}/cancel", {}, credentials=player, expected=409)
    # An unbound fake can be cancelled by its owner only.
    unbound = arm(1)
    api.request("POST", f"/game-jokers/reservations/{unbound}/cancel", {}, credentials=hunter, expected=403)
    assert api.request("POST", f"/game-jokers/reservations/{unbound}/cancel", {},
                       credentials=player)["status"] == "released"
    assert inventory()["used"] == start["used"] + 1 and inventory()["available"] == start["available"] - 1, \
        inventory()


PRIVATE_LATITUDE = 47.123457


def audit_participant_privacy():
    """Leakage between participants: own data only, nobody acts for another, nothing reveals a speedhunt target.

    Anna is the speedhunt target, Ben is not; both must see exactly the same thing apart from their own jokers.
    """
    password = secrets.token_hex(24)
    emails = {name: f"{api.PREFIX}-pv-{name}@example.invalid" for name in ("hunter", "anna", "ben", "spectator")}
    users = {name: create_user(f"{api.PREFIX}-pv-{name}", email, password, False) for name, email in emails.items()}
    hunter, anna, ben, spectator = ((emails[name], password) for name in ("hunter", "anna", "ben", "spectator"))
    session = api.request("POST", "/game-sessions", {"name": api.PREFIX + "-privacy", "schemaVersion": 4,
                                                     "config": {"game_settings": {"break_duration_minutes": 10,
                                                                                  "pings_per_speedhunt": 4}}})
    session_id = session["id"]
    api.GAME_SESSION_IDS.append(session_id)
    devices = {name: api.request("POST", "/devices", {"name": f"{api.PREFIX}-pv-{name}",
                                                       "uniqueId": f"{api.PREFIX}-pv-{name}", **extra})
               for name, extra in (("state", {"attributes": {"traccarToolGameState": True}}), ("anna", {}),
                                   ("ben", {}))}
    trackers = {"anna": devices["anna"]["id"], "ben": devices["ben"]["id"]}
    effects = f"/game-sessions/{session_id}/effects"
    sockets = []
    try:
        api.request("PUT", f"/game-sessions/{session_id}/resources", {
            "expectedRevision": 1, "sessionDeviceId": devices["state"]["id"],
            "resources": {"devices": {"real": dict(trackers)}},
            "members": [{"userId": users["hunter"], "role": "hunter", "number": 1, "assignedDeviceId": None,
                         "login": "pv-hunter"},
                        {"userId": users["anna"], "role": "player", "number": 1,
                         "assignedDeviceId": trackers["anna"], "login": "pv-anna"},
                        {"userId": users["ben"], "role": "player", "number": 2,
                         "assignedDeviceId": trackers["ben"], "login": "pv-ben"},
                        {"userId": users["spectator"], "role": "spectator", "number": None,
                         "assignedDeviceId": None, "login": "pv-spectator"}]})
        api.request("PUT", "/game-controller/session", {"gameSessionId": session_id})

        def publish(revision, target=None):
            api.request("POST", effects, {"deliveryId": "audit" + uuid4().hex, "status": {
                "version": 1, "limits": {"pingsPerSpeedhunt": 4}, "participants": [
                    {"deviceId": trackers[name], "name": name.title(), "role": "player", "caught": False,
                     "speedhunt": {"revision": revision, "running": name == target,
                                   "pings": 1 if name == target else 0, "live": False},
                     "break": {"active": False}} for name in ("anna", "ben")]}})

        publish(1)
        for name in ("anna", "ben"):
            for joker in ("hunterPositions", "break", "fakePing"):
                api.request("POST", f"/game-sessions/{session_id}/jokers/change",
                            {"deviceId": trackers[name], "type": joker, "quantity": 1, "reason": "audit"})

        def arm(credentials, latitude):
            request = api.request("POST", "/game-requests", {"type": "jokerFakePing", "parameters": {
                "pingNumber": 2, "latitude": latitude, "longitude": 8.5}}, credentials=credentials)
            api.request("POST", f"/game-sessions/{session_id}/requests/{request['id']}/claim", {})
            api.request("POST", effects, {"deliveryId": "audit" + uuid4().hex, "requests": [
                {"id": request["id"], "status": "succeeded", "message": "Your fake is armed."}]})
            return next(item["id"] for item in api.request("GET", "/game-jokers", credentials=credentials)
                        ["reservations"] if item["armed"])

        # One participant never reads another's jokers, plans, requests or break.
        anna_request = api.request("POST", "/game-requests", {"type": "jokerHunterPositions"}, credentials=anna)
        # Acting for another participant: jokers are always the requester's own.
        ben_request = api.request("POST", "/game-requests", {"type": "jokerHunterPositions",
                                                             "targetDeviceId": trackers["anna"]}, credentials=ben)
        assert ben_request["targetDeviceId"] == trackers["ben"], ben_request
        anna_fake = arm(anna, PRIVATE_LATITUDE)
        ben_own = api.request("GET", "/game-jokers", credentials=ben)
        assert {item["deviceId"] for item in ben_own["inventories"] + ben_own["reservations"]} == {trackers["ben"]}
        assert str(PRIVATE_LATITUDE) not in json.dumps(ben_own), "another participant's fake plan leaked"
        assert anna_request["id"] not in {item["id"] for item in api.request("GET", "/game-requests",
                                                                             credentials=ben)}
        ben_status = api.request("GET", "/game-status", credentials=ben)
        assert "participants" not in ben_status["status"] and ben_status["status"].get("fakes") == [], ben_status
        assert str(PRIVATE_LATITUDE) not in json.dumps(ben_status)
        api.request("POST", f"/game-requests/{anna_request['id']}/cancel", {}, credentials=ben, expected=403)
        # Another owner's reservation is answered like one that does not exist: probing tells nothing.
        assert refusal(f"/game-jokers/reservations/{anna_fake}/cancel", {}, ben, 404) \
            == refusal(f"/game-jokers/reservations/{anna_fake + 1000000}/cancel", {}, ben, 404)
        assert any(item["id"] == anna_fake and item["armed"]
                   for item in api.request("GET", "/game-jokers", credentials=anna)["reservations"])
        assert next(item for item in api.request("GET", "/game-requests", credentials=anna)
                    if item["id"] == anna_request["id"])["status"] == "pending"
        hunter_view = json.dumps([api.request("GET", "/game-status", credentials=hunter),
                                  api.request("GET", "/game-requests", credentials=hunter),
                                  api.request("GET", "/game-jokers", credentials=hunter)])
        assert str(PRIVATE_LATITUDE) not in hunter_view and '"fakes"' not in hunter_view \
            and "jokerHunterPositions" not in hunter_view, hunter_view
        for credentials, request in ((anna, anna_request), (ben, ben_request)):
            api.request("POST", f"/game-requests/{request['id']}/cancel", {}, credentials=credentials)
        ben_fake = arm(ben, 46.5)

        # Nothing tells Anna that a speedhunt targets her: not the status, a refusal, her jokers or the timing
        # of what the server announces.
        def own_views():
            return {name: (api.request("GET", "/game-jokers", credentials=credentials),
                           api.request("GET", "/game-requests", credentials=credentials))
                    for name, credentials in (("anna", anna), ("ben", ben))}

        before = own_views()
        sockets.extend([member_socket(anna), member_socket(ben)])
        publish(2, target="anna")
        announced = [invalidations(socket) for socket in sockets]
        assert announced[0] == announced[1] and "game-status" in announced[0], announced
        views = {}
        for name, credentials in (("anna", anna), ("ben", ben)):
            view = api.request("GET", "/game-status", credentials=credentials)
            view["status"].pop("fakes")
            views[name] = {key: value for key, value in view.items() if key != "serverTime"}
        # The anonymous speedhunt status (active, ping progress) is the same for the target and everyone else.
        assert views["anna"] == views["ben"] and views["anna"]["status"]["speedhunt"]["active"] is True, views
        assert own_views() == before, "a participant's jokers or requests changed when a speedhunt started"
        assert refusal("/game-requests", {"type": "jokerBreak"}, anna) \
            == refusal("/game-requests", {"type": "jokerBreak"}, ben) == BREAK_UNAVAILABLE
        # A hunter's request on Anna is announced to everyone alike and never listed for her.
        revision = api.request("GET", "/game-status", credentials=hunter)["status"]["participants"][0]
        hunter_request = api.request("POST", "/game-requests", {
            "type": "speedhuntStaticPing", "targetDeviceId": trackers["anna"],
            "baseRevision": revision["speedhunt"]["revision"]}, credentials=hunter)
        announced = [invalidations(socket) for socket in sockets]
        assert announced[0] == announced[1] and "game-requests" in announced[0], announced
        assert hunter_request["id"] not in {item["id"] for item in api.request("GET", "/game-requests",
                                                                               credentials=anna)}
        # The run's first ping binds both owners' fakes at once; both are told alike and see the same.
        api.request("POST", effects, {"deliveryId": "audit" + uuid4().hex, "fakes": [
            {"reservationId": anna_fake, "outcome": "bound", "run": "audit-privacy"},
            {"reservationId": ben_fake, "outcome": "bound", "run": "audit-privacy"}]})
        announced = [invalidations(socket) for socket in sockets]
        assert announced[0] == announced[1] and "game-jokers" in announced[0], announced
        bound = {}
        for name, credentials in (("anna", anna), ("ben", ben)):
            items = [item for item in api.request("GET", "/game-jokers", credentials=credentials)["reservations"]
                     if item["bound"]]
            assert len(items) == 1 and "boundTo" not in items[0]["plan"], items
            bound[name] = (sorted(items[0]), sorted(items[0]["plan"]), items[0]["status"])
        assert bound["anna"] == bound["ben"], bound
        audit_timetable_privacy(session_id, devices, trackers, sockets, hunter, anna, ben, spectator)
    finally:
        for socket in sockets:
            socket.close()
        for device in devices.values():
            api.request("DELETE", f"/devices/{device['id']}", expected=204)
        api.sql(f"DELETE FROM tc_users WHERE id IN ({','.join(str(value) for value in users.values())}) "
                "AND email LIKE " + api.literal(api.PREFIX + "-%") + ";")


def audit_timetable_privacy(session_id, devices, trackers, sockets, hunter, anna, ben, spectator):
    """Timetable pings: own fake-eligible pings for each player, every hunter-facing ping for hunters and
    spectators, without cell content, so an operator coordinate or a fake looks exactly like a real ping."""
    api.request("POST", f"/game-sessions/{session_id}/jokers/change",
                {"deviceId": trackers["anna"], "type": "fakePing", "quantity": 1, "reason": "audit"})
    base = (datetime.now(timezone.utc) + timedelta(hours=1)).replace(second=0, microsecond=0)
    names = {key: devices[key]["name"] for key in ("anna", "ben")}
    cells = {"anna": ["", "2H", "b", "x", "47.1,8.5|2H", "2P2S"], "ben": ["", "2H"]}
    timetable = f"/game-sessions/{session_id}/timetable"
    published = api.request("PUT", timetable, {
        "expectedRevision": api.request("GET", timetable)["revision"],
        "columns": ["Time", names["anna"], names["ben"]], "source": {},
        "entries": [{"scheduledAt": _iso(base + timedelta(minutes=10 * index)), "participant": names[key],
                     "content": cell} for key, row in cells.items() for index, cell in enumerate(row)]})
    ids = {(entry["participant"], entry["content"]): entry["id"] for entry in published["entries"]}
    faked = ids[(names["anna"], "")]
    request = api.request("POST", "/game-requests", {"type": "jokerFakePing", "parameters": {
        "entryId": faked, "latitude": PRIVATE_LATITUDE, "longitude": 8.5}}, credentials=anna)
    api.request("POST", f"/game-sessions/{session_id}/requests/{request['id']}/claim", {})
    api.request("POST", f"/game-sessions/{session_id}/effects", {"deliveryId": "audit" + uuid4().hex, "requests": [
        {"id": request["id"], "status": "succeeded", "message": "Your fake ping is armed."}]})

    hunter_facing = {ids[(names["anna"], cell)] for cell in ("", "2H", "47.1,8.5|2H")} \
        | {ids[(names["ben"], cell)] for cell in ("", "2H")}
    # Hunters see only the distinct times of the pings that reach them: no entry, participant or state.
    hunter_pings = api.request("GET", "/game-timetable", credentials=hunter)
    facing_times = sorted({_iso(base + timedelta(minutes=10 * index)) for index in (0, 1, 4)})
    assert [item["scheduledAt"][:16] for item in hunter_pings] == [time[:16] for time in facing_times], hunter_pings
    assert all(sorted(item) == ["scheduledAt"] for item in hunter_pings), hunter_pings
    # Spectators see each of those pings with its state; the faked ping, the operator's coordinate and a real
    # ping differ only in entry, time and tracker.
    spectator_pings = api.request("GET", "/game-timetable", credentials=spectator)
    assert {item["entryId"] for item in spectator_pings} == hunter_facing, spectator_pings
    text = json.dumps([hunter_pings, spectator_pings])
    assert "47.1" not in text and str(PRIVATE_LATITUDE) not in text and "content" not in text, text
    assert all(sorted(item) == sorted(spectator_pings[0]) and item["fakeAllowed"] is None
               and item["state"] == "scheduled" and item["stateAt"] is None for item in spectator_pings), \
        spectator_pings
    for credentials, key, expected in ((anna, "anna", [(ids[(names["anna"], "")], False),
                                                       (ids[(names["anna"], "2H")], True)]),
                                       (ben, "ben", [(ids[(names["ben"], "")], True),
                                                     (ids[(names["ben"], "2H")], True)])):
        own = api.request("GET", "/game-timetable", credentials=credentials)
        assert [(item["entryId"], item["fakeAllowed"]) for item in own] == expected, own
        assert {item["deviceId"] for item in own} == {trackers[key]}, own

    # Progress is announced to every member alike; the faked ping is recorded like any other.
    sockets.append(member_socket(hunter))
    for socket in sockets:
        invalidations(socket, 0.5)
    api.request("PUT", f"{timetable}/progress", {"entries": [
        {"id": faked, "status": "sent"}, {"id": ids[(names["ben"], "")], "status": "sent"}]}, expected=204)
    announced = [invalidations(socket) for socket in sockets]
    assert all(item == announced[0] for item in announced) and "game-timetable" in announced[0], announced
    sent = {item["entryId"]: item for item in api.request("GET", "/game-timetable", credentials=spectator)}
    anna_sent, ben_sent = sent[faked], sent[ids[(names["ben"], "")]]
    assert anna_sent["state"] == ben_sent["state"] == "sent" and anna_sent["stateAt"] == ben_sent["stateAt"]


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
    mirror = api.request("POST", "/devices", {"name": api.PREFIX + "-rq-h1-anna", "uniqueId": api.PREFIX + "-rq-h1-anna",
                                              "attributes": {"traccarToolMirror": True}})
    try:
        api.request("PUT", f"/game-sessions/{session_id}/resources", {
            "expectedRevision": 1, "sessionDeviceId": state["id"],
            "resources": {"devices": {"real": {"anna": tracker["id"]},
                                      "mirroring": {"hunter_1_to_player_1": mirror["id"]}}},
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
                     "speedhunt": {"revision": revision, "running": running, "pings": 1 if running else 0,
                                   "live": False}}]}})
        # A running speedhunt: hunters' pings on it need no joker of their pool.
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
        # Hunters see participants by their game label (group and number), never their names.
        assert pending["status"] == "pending" and pending["targetLabel"] == "Player 1", pending
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
        # Hunters see the outcome; the Tool's message and the detail stay with the operators.
        assert seen["status"] == "succeeded" and seen["message"] is None and seen["detail"] is None, seen
        operator_view = api.request("GET", f"/game-sessions/{session_id}/requests?state=all")[0]
        assert operator_view["detail"] == "operator only" and operator_view["message"] == "Ping 1 of 4 sent."

        audit_jokers(session_id, tracker["id"], mirror["id"], hunter, player)
        audit_speedhunt_jokers(session_id, tracker["id"], hunter, player)
        audit_breaks(session_id, tracker["id"], hunter, player, tool_source)
        audit_mirror_only_access(session_id, tracker["id"], hunter_id, player_id)
        audit_timetable_fakes(session_id, tracker, mirror, hunter, player, tool_source)
        audit_speedhunt_fakes(session_id, tracker, mirror, hunter, player, tool_source)

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
        expired = next(item for item in api.request("GET", f"/game-sessions/{session_id}/requests?state=all")
                       if item["id"] == third["id"])
        assert expired["status"] == "expired" and "Control of the game changed" in expired["message"]
        api.request("POST", "/game-controller/release", {}, epoch=epoch + 1)
        api.acquire_controller()
        assert not api.request("GET", "/game-status", credentials=hunter)["controllerActive"]
    finally:
        for device_id in (mirror["id"], tracker["id"], state["id"]):
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
        audit_participant_privacy()
        if tool_source:
            audit_tool(tool_source)
        print("MySQL/HTTP game-session audit passed: admin-only access, revision conflicts and idempotent retries, "
              "credential encryption at rest, stable timetable entry IDs, notification history after device "
              "deletion, cascading deletion of saved data, controller lease with fenced game and standard "
              "endpoints, push fencing, drift reporting, takeover, expiry, and idempotent effects with "
              "latest-position and online status, hunters' requests with role-filtered status, stale "
              "revisions, approval, claim, outcome, cancellation and invalidation on takeover, break jokers "
              "with a configured duration, one refusal for everyone during speedhunts and the published break, "
              "mirror-only hunter access for permissions, group and device moves, joker grants and membership, "
              "timetable fakes armed on eligible pings, private plans, used with the hunters' delivery and "
              "released on cancellation or a changed ping, speedhunt fakes bound at a run's first ping and "
              "given to the hunters at their ping, participant privacy (own jokers, plans, requests and break "
              "only, no acting for another, and the same status, refusals, jokers and announcements for a "
              "speedhunt's target as for everyone else), and timetable pings without cell content: own fake-"
              "eligible pings for players, every hunter-facing ping for hunters and spectators, fakes and "
              "operator coordinates looking like real pings"
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
