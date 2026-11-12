"""Local MySQL/HTTP audit of game data model 2 (game-session API version 5) with isolated fixtures.

Creates a temporary administrator and temporary members' accounts, exercises the game-session, controller, member,
request, joker, timetable and status endpoints directly and, with ``--tool-source``, through Traccar Tool's model-2
services, and removes every fixture afterwards. Requires the full Docker profile with
``TRACCAR_GAME_SESSIONS_CREDENTIAL_KEY`` set in ``.env``; see ``game_notification_integration.py`` for the
environment variables. Pushes use the ``traccar`` notificator, and no fixture account has push tokens, so the
notificator sends nothing.
"""

import argparse
from datetime import datetime, timedelta, timezone
import json
import secrets
import sys
import time
from uuid import uuid4

import game_notification_integration as api

PRIVATE_LATITUDE = 46.987654
BREAK_UNAVAILABLE = "A break cannot start right now; try again later"
NO_SPEEDHUNT_JOKER = "No speedhunt joker is left; ask the operator for more"


def _iso(moment):
    return moment.isoformat().replace("+00:00", "Z")


def refusal(path, body, credentials=(api.EMAIL, api.PASSWORD), expected=409):
    """The message of a refused POST (409 by default)."""
    return api.refusal("POST", path, body, credentials=credentials, expected=expected).split(": ", 1)[-1].strip()


class Game:
    """A stored session with members, accounts, trackers and hunter mirrors, as the Tool's device setup leaves it."""

    def __init__(self, fixtures, label, settings, roster):
        self.fixtures = fixtures
        self.id = api.create_game_session(label, settings)
        self.label = label
        self.members, self.users, self.credentials, self.trackers, self.mirrors = {}, {}, {}, {}, {}
        for key, role, name in roster:
            self.add(key, role, name)

    def add(self, key, role, name):
        member = api.plan(self.id, role, name)
        user, credentials = self.fixtures.account(f"{api.PREFIX}-{self.label}-{key}", readonly=True)
        api.bind_account(self.id, member["id"], user["id"])
        if role != "spectator":
            tracker = self.fixtures.device(f"{api.PREFIX}-{self.label}-{key}")
            member = api.bind_tracker(self.id, member["id"], tracker["id"])
            self.trackers[key] = tracker
        if role in ("player", "supporter"):
            mirror = self.fixtures.device(f"{api.PREFIX}-{self.label}-{key}-to-hunters",
                                          attributes={"traccarToolMirror": True})
            api.add_mirror(self.id, mirror["id"], member["id"], destination_role="hunter")
            self.mirrors[key] = mirror
        self.members[key], self.users[key], self.credentials[key] = member, user, credentials
        return member

    def member_id(self, key):
        return self.members[key]["id"]

    def status(self, key, expected=200):
        return api.request("GET", "/game-status", credentials=self.credentials[key], expected=expected)

    def participant(self, viewer, key):
        participants = self.status(viewer)["status"]["participants"]
        return next(item for item in participants if item["memberId"] == self.member_id(key))

    def revision(self, key, viewer="hunter"):
        return self.participant(viewer, key)["speedhunt"]["revision"]

    def effects(self, expected=200, **parts):
        return api.effects(self.id, expected=expected, **parts)

    def ping(self, key, number, request_id=None, expected=200, **extra):
        operation = {"type": "pingDelivered", "targetMemberId": self.member_id(key), "number": number,
                     "mode": "static", "source": "real", "requestId": request_id}
        if number == 1:
            operation.update(toHunters=True, toPlayers=True, toSupporters=True)
        operation.update(extra)
        return self.effects(expected=expected, operations=[operation])

    def end_run(self, key, outcome="terminated"):
        return self.effects(operations=[{"type": "runEnded", "targetMemberId": self.member_id(key),
                                         "outcome": outcome, "reason": "audit"}])

    def change(self, key, joker, quantity, expected=200, **extra):
        member_id = None if key is None else self.member_id(key)
        return api.request("POST", f"/game-sessions/{self.id}/jokers/change", {
            "memberId": member_id, "type": joker, "quantity": quantity, "reason": "audit", **extra},
            expected=expected)

    def own_jokers(self, key):
        return api.request("GET", "/game-jokers", credentials=self.credentials[key])

    def inventory(self, key, joker):
        return next(item for item in self.own_jokers(key)["inventories"] if item["type"] == joker)

    def submit(self, key, body, expected=200):
        return api.request("POST", "/game-requests", body, credentials=self.credentials[key], expected=expected)

    def requests(self, key):
        return api.request("GET", "/game-requests", credentials=self.credentials[key])

    def operator_request(self, request_id):
        return next(item for item in api.request("GET", f"/game-sessions/{self.id}/requests?state=all")
                    if item["id"] == request_id)

    def claim(self, request_id):
        return api.request("POST", f"/game-sessions/{self.id}/requests/{request_id}/claim", {})

    def succeed(self, request_id, message="Done.", operations=(), **parts):
        return self.effects(requests=[{"id": request_id, "status": "succeeded", "message": message}],
                            operations=list(operations), **parts)

    def ledger(self, joker=None):
        entries = api.request("GET", f"/game-sessions/{self.id}/jokers/ledger")
        return [entry["kind"] for entry in entries if joker is None or entry["type"] == joker]

    def reconcile(self):
        assert api.request("GET", f"/game-sessions/{self.id}/jokers/reconcile") == [], "Balances differ from ledger"


# Sessions, documents and deletion -------------------------------------------------------------------------------

def audit_http(fixtures, user_credentials):
    """Admin-only access, revisions, encryption at rest, timetable identity by member, logs, cascading deletion."""
    api.request("GET", "/game-sessions", credentials=user_credentials, expected=403)
    api.request("GET", "/game-sessions/capabilities", credentials=user_credentials, expected=403)
    assert api.request("GET", "/game-sessions/capabilities") == {"apiVersion": 5}
    session_id = api.create_game_session("http", {"pings_per_speedhunt": 4})
    api.request("POST", "/game-sessions", {"name": api.PREFIX + "-http", "schemaVersion": 5, "config": {}},
                expected=409)
    stored = api.request("GET", f"/game-sessions/{session_id}")
    assert stored["revision"] == 1 and stored["settings"]["revision"] == 1, stored
    update = {"expectedRevision": 1, "schemaVersion": 5, "document": {"game_settings": {"pings_per_speedhunt": 5}}}
    assert api.request("PUT", f"/game-sessions/{session_id}/config", update)["settings"]["revision"] == 2
    assert api.request("PUT", f"/game-sessions/{session_id}/config", update)["settings"]["revision"] == 2
    api.request("PUT", f"/game-sessions/{session_id}/config",
                {**update, "document": {"game_settings": {"pings_per_speedhunt": 6}}}, expected=409)

    secret = "audit-" + secrets.token_hex(8)
    api.request("PUT", f"/game-sessions/{session_id}/credentials",
                {"expectedRevision": 0, "document": {"user_passwords": {"player": secret}}})
    ciphertext = api.sql(f"SELECT ciphertext FROM tc_game_session_credentials WHERE gamesessionid={session_id};")
    assert ciphertext.startswith("v1:") and secret not in ciphertext, "Credentials are not encrypted at rest"
    assert api.request("GET", f"/game-sessions/{session_id}/credentials")["document"]["user_passwords"][
        "player"] == secret

    # Timetable entries are keyed by time and member: a rename never moves them, and content edits keep IDs.
    anna = api.plan(session_id, "player", "Anna")
    api.plan(session_id, "player", "Ben")
    columns = ["Time", "Anna", "Ben"]
    entries = [{"scheduledAt": "2026-05-01T10:00:00Z", "participant": "Anna", "content": ""},
               {"scheduledAt": "2026-05-01T10:00:00Z", "participant": "Ben", "content": "b"}]
    timetable = f"/game-sessions/{session_id}/timetable"
    first = api.request("PUT", timetable, {"expectedRevision": 0, "columns": columns, "source": {},
                                           "entries": entries})
    assert {entry["memberId"] for entry in first["entries"]} >= {anna["id"]}
    entries[0]["content"] = "47.1,8.5|2H"
    second = api.request("PUT", timetable, {"expectedRevision": 1, "columns": columns, "source": {},
                                            "entries": entries})
    assert [entry["id"] for entry in first["entries"]] == [entry["id"] for entry in second["entries"]]
    assert not second["structureChanged"]
    api.request("PUT", f"/game-sessions/{session_id}/members/{anna['id']}/name", {"displayName": "Anna B."})
    renamed = api.request("PUT", timetable, {"expectedRevision": 2, "columns": ["Time", "Anna B.", "Ben"],
                                             "source": {}, "entries": [{**entries[0], "participant": "Anna B."},
                                                                       entries[1]]})
    assert [entry["id"] for entry in renamed["entries"]] == [entry["id"] for entry in first["entries"]]
    api.request("PUT", timetable, {"expectedRevision": 3, "columns": columns, "source": {},
                                   "entries": [{**entries[0], "participant": "Nobody"}]}, expected=400)

    logs = f"/game-sessions/{session_id}/logs"
    api.request("POST", logs, {"entries": [{"category": "operation", "occurredAt": api.iso(),
                                       "message": "[10:00:00] audit"}]},
                expected=204)
    assert [item["message"] for item in api.request("GET", logs)["items"]] == ["[10:00:00] audit"]
    generation = api.request("POST", f"/game-notifications/session/{session_id}", {})["generation"]
    api.request("POST", "/game-notifications", {"gameSessionId": session_id, "generation": generation, "alert": {
        "id": "audit-1", "type": "notificationTest", "audience": "session", "occurredAt": api.iso(),
        "timeZone": "UTC", "message": "Audit"}})
    api.request("DELETE", f"/game-sessions/{session_id}", expected=204)
    api.GAME_SESSION_IDS.remove(session_id)
    for table in ("tc_game_notifications", "tc_game_timetable_entries", "tc_game_session_members",
                  "tc_game_session_logs", "tc_game_session_credentials", "tc_game_session_settings"):
        assert api.sql(f"SELECT COUNT(*) FROM {table} WHERE gamesessionid={session_id};") == "0", table
    api.request("GET", f"/game-sessions/{session_id}", expected=404)


# Controller lease, fencing and effects -------------------------------------------------------------------------

def stored_device(device_id):
    return api.request("GET", f"/devices/{device_id}")


def audit_controller(fixtures, user_id, user_credentials):
    """Lease, fencing of game and standard endpoints, drift, takeover, effects, the push outbox and expiry."""
    lease = api.request("GET", "/game-controller")
    assert lease["active"] and lease["epoch"] == api.CONTROLLER["epoch"]
    epoch = api.CONTROLLER["epoch"]
    other = "audit-other-" + uuid4().hex[:16]
    api.request("POST", "/game-controller/acquire", {"instanceId": other, "label": "Other PC"},
                epoch=False, expected=423)

    session_id = api.create_game_session("controller")
    real = fixtures.device(api.PREFIX + "-ctl-real")
    mirror = fixtures.device(api.PREFIX + "-ctl-mirror", attributes={"traccarToolMirror": True})
    # Member and resource writes always need the epoch; configuration of a non-executing session does not.
    api.request("POST", f"/game-sessions/{session_id}/members", {"role": "player", "displayName": "Anna"},
                epoch=False, expected=423)
    anna = api.plan(session_id, "player", "Anna")
    api.request("PUT", f"/game-sessions/{session_id}/members/{anna['id']}/account", {"userId": user_id},
                epoch=False, expected=423)
    api.bind_account(session_id, anna["id"], user_id)
    api.bind_tracker(session_id, anna["id"], real["id"])
    api.request("POST", f"/game-sessions/{session_id}/resources/mirrors", {
        "deviceId": mirror["id"], "sourceMemberId": anna["id"], "destinationRole": "hunter"},
        epoch=False, expected=423)
    api.add_mirror(session_id, mirror["id"], anna["id"], destination_role="hunter")
    api.request("PUT", f"/game-sessions/{session_id}/config",
                {"expectedRevision": 1, "schemaVersion": 5, "document": {"game_settings": {}}}, epoch=False)
    api.execute(session_id)
    api.request("PUT", f"/game-sessions/{session_id}/config",
                {"expectedRevision": 2, "schemaVersion": 5, "document": {"game_settings": {"x": 1}}}, epoch=False, expected=423)
    api.request("PUT", f"/game-sessions/0{session_id}/name", {"name": api.PREFIX + "-renamed"}, epoch=False,
                expected=423)

    # Standard mutations of game-owned resources need the epoch while a lease is held.
    renamed = {**mirror, "name": mirror["name"] + "-renamed"}
    api.request("PUT", f"/devices/{mirror['id']}", renamed, epoch=False, expected=423)
    api.request("PUT", f"/devices/{mirror['id']}", renamed)
    api.request("DELETE", f"/positions?deviceId={mirror['id']}&from=2020-01-01T00:00:00Z"
                "&to=2030-01-01T00:00:00Z", epoch=False, expected=423)
    api.request("POST", "/permissions", {"userId": user_id, "deviceId": real["id"]}, epoch=False, expected=423)
    unowned = fixtures.device(api.PREFIX + "-ctl-unowned")
    api.request("PUT", f"/devices/{unowned['id']}", {**unowned, "name": unowned["name"] + "-x"}, epoch=False)
    # The fence judges the object the resource changes: a body naming an owned device under an unowned path is
    # refused, path numbers are read as Jersey reads them, and permission keys as the permission resource does.
    api.request("PUT", f"/devices/{unowned['id']}", {**renamed, "name": "smuggled"}, epoch=False, expected=400)
    api.request("PUT", f"/devices/{unowned['id']}", {**renamed, "name": "smuggled"}, expected=400)
    assert stored_device(mirror["id"])["name"] == renamed["name"]
    api.request("PUT", f"/devices/0{mirror['id']}", renamed, epoch=False, expected=423)
    api.request("POST", "/permissions", {"UserId": user_id, "DeviceId": real["id"]}, epoch=False, expected=423)
    api.request("POST", "/permissions", {"userId": str(user_id), "deviceId": str(real["id"])}, epoch=False,
                expected=423)
    # Members update their own account (preferences) while a Tool controls the game.
    own = api.request("GET", f"/users/{user_id}", credentials=user_credentials)
    updated = api.request("PUT", f"/users/{user_id}", {**own, "attributes": {
        **(own.get("attributes") or {}), "auditPreference": "kept"}}, credentials=user_credentials)
    assert updated["attributes"]["auditPreference"] == "kept"
    # A request carrying a wrong epoch is refused whatever it changes.
    api.request("POST", "/groups", {"name": api.PREFIX + "-stale"}, epoch=epoch + 1000, expected=423)
    # Pushes that reach members of the executing session, also sends without userId.
    message = {"subject": "Audit", "digest": "Audit", "body": "Audit", "priority": False}
    api.request("POST", f"/notifications/send/traccar?userId={user_id}", message, epoch=False, expected=423)
    api.request("POST", "/notifications/send/traccar", message, epoch=False, expected=423)
    assert "notificationTokens" not in (api.request("GET", f"/users/{user_id}").get("attributes") or {})

    # Effects: mirror positions become latest and the device shows as online; repeats are not applied.
    delivery = {"deliveryId": "audit-" + uuid4().hex,
                "positions": [{"deviceId": mirror["id"], "latitude": 47.1, "longitude": 8.5}],
                "pushes": [{"memberId": anna["id"], "subject": "Audit", "message": "Speedhunt started",
                            "notificator": "traccar"}]}
    first = api.request("POST", f"/game-sessions/{session_id}/effects", delivery)
    assert not first["replayed"] and all(item["latest"] for item in first["positions"]), first
    device = stored_device(mirror["id"])
    assert device["positionId"] == first["positions"][0]["positionId"] and device["status"] == "online"
    position = api.request("GET", f"/positions?id={device['positionId']}")[0]
    assert position["attributes"]["activity"] == "still" and position["protocol"] == "osmand"
    replay = api.request("POST", f"/game-sessions/{session_id}/effects", delivery)
    assert replay["replayed"] and replay["positions"] == first["positions"]
    api.request("POST", f"/game-sessions/{session_id}/effects",
                {**delivery, "positions": [{"deviceId": mirror["id"], "latitude": 47.2, "longitude": 8.5}]},
                expected=409)
    assert api.sql(f"SELECT COUNT(*) FROM tc_positions WHERE deviceid={mirror['id']}") == "1"
    deadline = time.monotonic() + 15
    while api.sql(f"SELECT status FROM tc_game_push_outbox WHERE gamesessionid={session_id}") in (
            "queued", "attempting"):
        assert time.monotonic() < deadline, "Outbox push was not attempted"
        time.sleep(0.5)
    pushed = api.sql(f"SELECT status, detail FROM tc_game_push_outbox WHERE gamesessionid={session_id}")
    assert pushed.split("\t")[0] == "sent", pushed
    older = (datetime.now(timezone.utc) - timedelta(minutes=5)).strftime("%Y-%m-%dT%H:%M:%S.000Z")
    late = api.effects(session_id, positions=[{"deviceId": mirror["id"], "latitude": 47.2, "longitude": 8.6,
                                               "fixTime": older}])
    assert not late["positions"][0]["latest"]
    assert stored_device(mirror["id"])["positionId"] == first["positions"][0]["positionId"]
    api.effects(session_id, positions=[{"deviceId": real["id"], "latitude": 1, "longitude": 1}], expected=400)
    api.request("POST", f"/game-sessions/{session_id}/effects", {**delivery, "deliveryId": "audit-" + uuid4().hex},
                epoch=False, expected=423)

    # Takeover: the previous holder's delayed requests are refused.
    api.request("PUT", "/game-controller/session", {"gameSessionId": None})
    takeover = api.request("POST", "/game-controller/takeover", {"instanceId": other, "label": "Other PC"},
                           epoch=False)
    assert takeover["lease"]["epoch"] == epoch + 1
    api.request("PUT", f"/game-sessions/{session_id}/members/{anna['id']}/name", {"displayName": "Late"},
                epoch=epoch, expected=423)
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
    api.request("PUT", f"/game-sessions/{session_id}/members/{anna['id']}/name", {"displayName": "Late"},
                epoch=expiring, expected=423)
    assert api.acquire_controller()["lease"]["epoch"] == expiring + 1
    events = [item["event"] for item in api.request("GET", "/game-controller/history?limit=20")["items"]]
    for event in ("expired", "acquired", "takeover", "takenOver", "released", "executing",
                  "stoppedExecuting", "unfencedEdit"):
        assert event in events, (event, events)
    # Deleting the session leaves the Traccar resources it managed.
    api.request("DELETE", f"/game-sessions/{session_id}", expected=204)
    api.GAME_SESSION_IDS.remove(session_id)
    assert stored_device(mirror["id"])["id"] == mirror["id"]


# Requests, jokers, breaks and fakes ----------------------------------------------------------------------------

def audit_requests(game, tool_source):
    """Hunters' requests: membership, role-filtered status, revisions, approval, claim, outcome, privacy."""
    api.execute(game.id)
    hunter_view = game.status("hunter")
    assert hunter_view["controllerActive"] and hunter_view["role"] == "hunter", hunter_view
    revision = game.revision("anna")
    player_view = game.status("anna")
    assert player_view["status"]["speedhunt"] == {"active": False}, player_view
    assert "participants" not in player_view["status"], "players must not learn the speedhunt target"
    api.request("GET", "/game-status", expected=403)

    submission = {"type": "speedhuntStaticPing", "targetMemberId": game.member_id("anna"), "baseRevision": revision}
    game.submit("anna", submission, expected=403)
    assert refusal("/game-requests", {**submission, "baseRevision": revision + 7}, game.credentials["hunter"]) \
        == "The speedhunt changed meanwhile; refresh and try again"
    assert refusal("/game-requests", submission, game.credentials["hunter"]) == NO_SPEEDHUNT_JOKER
    game.change(None, "speedhunt", 2)
    pending = game.submit("hunter", submission)
    # Hunters see participants by their game label (role and number), never their names.
    assert pending["status"] == "pending" and pending["targetLabel"] == "Player 1", pending
    game.submit("hunter", submission, expected=409)
    assert game.requests("anna") == [] and game.requests("ben") == []
    pool = game.inventory("hunter", "speedhunt")
    assert pool["reserved"] == 1 and pool["available"] == 1, pool

    base = f"/game-sessions/{game.id}/requests/{pending['id']}"
    api.request("POST", base + "/decision", {"decision": "await"}, epoch=False, expected=423)
    api.request("POST", base + "/decision", {"decision": "await", "message": "Waiting for the operator",
                                             "detail": "limit"})
    assert game.requests("hunter")[0]["approvalRequired"] is True
    api.request("POST", base + "/decision", {"decision": "approve"})
    assert game.claim(pending["id"])["status"] == "executing"
    api.request("POST", base + "/claim", {}, expected=409)
    outcome = game.effects(requests=[{"id": pending["id"], "status": "succeeded", "message": "Ping 1 of 4 sent.",
                                      "detail": "operator only"}],
                           operations=[{"type": "pingDelivered", "targetMemberId": game.member_id("anna"),
                                        "number": 1, "mode": "static", "source": "real", "toHunters": True,
                                        "toPlayers": True, "toSupporters": True, "requestId": pending["id"]}])
    assert outcome["requests"] == [{"id": pending["id"], "status": "succeeded"}], outcome
    assert outcome["operations"][0]["runId"] and outcome["operations"][0]["number"] == 1, outcome
    seen = game.requests("hunter")[0]
    # Hunters see the outcome; the Tool's message and the detail stay with the operators.
    assert seen["status"] == "succeeded" and seen["message"] is None and seen["detail"] is None, seen
    operator_view = game.operator_request(pending["id"])
    assert operator_view["detail"] == "operator only" and operator_view["message"] == "Ping 1 of 4 sent."
    pool = game.inventory("hunter", "speedhunt")
    assert pool["used"] == 1 and pool["reserved"] == 0 and pool["available"] == 1, pool
    anna = game.participant("hunter", "anna")["speedhunt"]
    assert anna["running"] and anna["pings"] == 1 and anna["revision"] > revision, anna
    for key in ("anna", "ben", "sue"):
        speedhunt = game.status(key)["status"]["speedhunt"]
        assert speedhunt["active"] and speedhunt["pingCount"] == 1 and speedhunt["pingLimit"] == 4, speedhunt
    history = api.request("GET", "/game-status/speedhunt-history", credentials=game.credentials["hunter"])
    assert history["runs"][0]["status"] == "inProgress" and history["runs"][0]["pings"][0]["mode"] == "static"
    # Ping 2 is the next number; the source stays with the operators.
    game.ping("anna", 3, expected=409)
    game.ping("anna", 2)
    snapshot = api.request("GET", f"/game-sessions/{game.id}/snapshot")
    assert "real" in json.dumps(snapshot["state"]), "operators read ping sources"
    for key in ("anna", "ben", "hunter", "spectator"):
        assert '"source"' not in json.dumps(game.status(key)), "ping sources must stay with the operators"
    game.end_run("anna", "terminated")
    assert not game.status("ben")["status"]["speedhunt"]["active"]
    if tool_source:
        audit_tool_requests(tool_source, game)


def audit_jokers(game):
    """Get hunter positions jokers: grants, reservations, consumption, over-allocation and release."""
    change = f"/game-sessions/{game.id}/jokers/change"
    grant = {"memberId": game.member_id("anna"), "type": "hunterPositions", "quantity": 1, "reason": "audit"}
    api.request("POST", change, grant, epoch=False, expected=423)
    api.request("POST", change, {**grant, "type": "teleport"}, expected=400)
    api.request("POST", change, grant)
    # Hunters read only the speedhunt jokers of their shared pool, never a participant's jokers.
    pool_view = game.own_jokers("hunter")
    assert [item["type"] for item in pool_view["inventories"]] == ["speedhunt"], pool_view
    assert game.inventory("anna", "hunterPositions")["available"] == 1
    joker = {"type": "jokerHunterPositions"}
    request = game.submit("anna", joker)
    assert request["targetMemberId"] == game.member_id("anna") and request["status"] == "pending", request
    inventory = game.inventory("anna", "hunterPositions")
    assert inventory["reserved"] == 1 and inventory["available"] == 0, inventory
    game.submit("anna", joker, expected=409)
    assert all(item["type"] != "jokerHunterPositions" for item in game.requests("hunter")), "jokers are private"

    # The Tool delivers each hunter's snapshot with its own fix time, together with the outcome.
    relay = game.fixtures.device(api.PREFIX + "-hunter-to-anna", attributes={"traccarToolMirror": True})
    api.add_mirror(game.id, relay["id"], game.member_id("hunter"), destination_member_id=game.member_id("anna"))
    game.claim(request["id"])
    fix_time = (datetime.now(timezone.utc) - timedelta(minutes=3)).strftime("%Y-%m-%dT%H:%M:%S.000Z")
    outcome = game.succeed(request["id"], "Positions of 1 hunter(s) sent.", positions=[
        {"deviceId": relay["id"], "latitude": 47.3, "longitude": 8.6, "fixTime": fix_time}])
    assert outcome["positions"][0]["latest"] and outcome["positions"][0]["fixTime"].startswith(fix_time[:19])
    position = api.request("GET", f"/positions?id={stored_device(relay['id'])['positionId']}")[0]
    assert position["fixTime"].startswith(fix_time[:19]), position["fixTime"]
    inventory = game.inventory("anna", "hunterPositions")
    assert inventory["used"] == 1 and inventory["available"] == 0, inventory

    # An operator relay without a joker left needs a confirmed use beyond the allocation.
    use = {"memberId": game.member_id("anna"), "type": "hunterPositions", "overAllocate": False,
           "detail": "Manual relay"}
    game.effects(jokers=[use], expected=409)
    over = game.effects(jokers=[{**use, "overAllocate": True}])
    assert over["jokers"][0]["overAllocated"] is True and over["jokers"][0]["memberId"] == game.member_id("anna")
    inventory = game.inventory("anna", "hunterPositions")
    assert inventory["overAllocated"] == 1 and inventory["available"] == 0, inventory
    game.submit("anna", joker, expected=409)

    # Caught: the open request ends and its reserved joker returns; released players play on.
    game.change("anna", "hunterPositions", 2)
    pending = game.submit("anna", joker)
    game.effects(operations=[{"type": "catch", "memberId": game.member_id("anna"), "actor": "audit"}])
    ended = next(item for item in game.requests("anna") if item["id"] == pending["id"])
    assert ended["status"] in ("cancelled", "failed", "expired"), ended
    assert game.inventory("anna", "hunterPositions")["reserved"] == 0
    assert refusal("/game-requests", joker, game.credentials["anna"]) == "You are out of the game"
    overview = game.status("ben")["status"]["participantOverview"]
    assert next(item for item in overview if item["memberId"] == game.member_id("anna"))["caught"] is True
    game.effects(operations=[{"type": "catch", "memberId": game.member_id("anna")}], expected=409)
    game.effects(operations=[{"type": "release", "memberId": game.member_id("anna")}])
    game.effects(operations=[{"type": "release", "memberId": game.member_id("anna")}], expected=409)
    kinds = game.ledger("hunterPositions")
    assert kinds[:7] == ["release", "reserve", "grant", "overuse", "use", "reserve", "grant"], kinds
    game.change("anna", "hunterPositions", -1)
    game.reconcile()


def audit_speedhunt_jokers(game):
    """The hunters' pool: used by a run's first ping, and beyond the allocation only when confirmed."""
    game.change("anna", "speedhunt", 1, expected=400)
    pool_before = game.inventory("hunter", "speedhunt")
    # Players never see the hunters' pool.
    assert all(item["type"] != "speedhunt" for item in game.own_jokers("anna")["inventories"])
    # A speedhunt the operator starts uses the pool; with none left only beyond the allocation.
    while game.inventory("hunter", "speedhunt")["available"] > 0:
        game.ping("ben", 1)
        game.end_run("ben")
    game.ping("ben", 1, expected=409)
    assert game.ping("ben", 1, overAllocate=True)["operations"][0]["runId"]
    pool = game.inventory("hunter", "speedhunt")
    assert pool["overAllocated"] == pool_before["overAllocated"] + 1 and pool["available"] == 0, pool
    game.end_run("ben", "completed")
    assert game.ledger("speedhunt")[0] == "overuse"
    # The following audits ping too: leave jokers in the pool.
    game.change(None, "speedhunt", 5)
    game.reconcile()


def audit_breaks(game, tool_source=None):
    """Break jokers: a configured duration, one refusal for everyone during speedhunts, the computed break."""
    grant = {"memberId": game.member_id("anna"), "type": "break", "quantity": 1, "reason": "audit"}
    change = f"/game-sessions/{game.id}/jokers/change"
    assert "break duration" in refusal(change, grant)
    api.config(game.id, {"pings_per_speedhunt": 4, "break_duration_minutes": 15})
    game.change("anna", "break", 2)

    pause = {"type": "jokerBreak"}
    # While any speedhunt runs, the answer is the same whether it targets the requester or someone else.
    game.ping("anna", 1)
    on_self = refusal("/game-requests", pause, game.credentials["anna"])
    game.end_run("anna")
    game.ping("ben", 1)
    on_other = refusal("/game-requests", pause, game.credentials["anna"])
    game.end_run("ben")
    assert on_self == on_other == BREAK_UNAVAILABLE, (on_self, on_other)
    assert game.inventory("anna", "break")["reserved"] == 0, "a refused break reserves nothing"

    request = game.submit("anna", pause)
    assert request["targetMemberId"] == game.member_id("anna") and request["type"] == "jokerBreak", request
    assert game.inventory("anna", "break")["reserved"] == 1
    if tool_source:
        run_break_through_tool(tool_source, game)
        active = [item for item in api.request("GET", f"/game-sessions/{game.id}/jokers")["reservations"]
                  if item["type"] == "break" and item["status"] == "reserved"]
        assert not active, active
    else:
        game.claim(request["id"])
        game.succeed(request["id"], "Your break runs for 15 min.",
                     operations=[{"type": "breakStarted", "requestId": request["id"]}])
    done = next(item for item in game.requests("anna") if item["id"] == request["id"])
    assert done["status"] == "succeeded", done
    inventory = game.inventory("anna", "break")
    assert inventory["used"] == 1 and inventory["reserved"] == 0 and inventory["available"] == 1, inventory

    # The participant sees their own break and its deadline; hunters see who is on a break.
    own = game.status("anna")["status"]
    assert own["break"]["active"] and own["break"]["endsAt"], own
    assert "participants" not in own
    assert not game.status("ben")["status"]["break"]["active"], "a break is private to its owner"
    hunter_view = game.participant("hunter", "anna")
    assert hunter_view["break"]["active"], hunter_view
    ping = {"type": "speedhuntStaticPing", "targetMemberId": game.member_id("anna"),
            "baseRevision": hunter_view["speedhunt"]["revision"]}
    assert refusal("/game-requests", ping, game.credentials["hunter"]) == "This participant is on a break"
    assert refusal("/game-requests", pause, game.credentials["anna"]) == "You are already on a break"
    game.ping("anna", 1, expected=409)

    # The Tool ends the break at its deadline: hunters may act on the participant again.
    snapshot = api.request("GET", f"/game-sessions/{game.id}/snapshot")
    active = [item for item in snapshot["state"]["breaks"] if item["state"] == "active"]
    assert len(active) == 1, snapshot["state"]["breaks"]
    game.effects(operations=[{"type": "breakEnded", "breakId": active[0]["id"]}])
    game.effects(operations=[{"type": "breakEnded", "breakId": active[0]["id"]}], expected=409)
    assert not game.status("anna")["status"]["break"]["active"]
    resumed = game.submit("hunter", {**ping, "baseRevision": game.revision("anna")})
    api.request("POST", f"/game-requests/{resumed['id']}/cancel", {}, credentials=game.credentials["hunter"])
    kinds = game.ledger("break")
    assert kinds[:3] == ["use", "reserve", "grant"], kinds
    game.change("anna", "break", -1)
    audit_planned_breaks(game, tool_source)


def audit_planned_breaks(game, tool_source=None):
    """Planned breaks: accepted during a speedhunt, private, never overlapping, cancelled, started or missed."""
    plan_endpoint = f"/game-sessions/{game.id}/jokers/breaks"
    game.change("anna", "break", 1)
    game.ping("ben", 1)
    later = datetime.now(timezone.utc).replace(microsecond=0) + timedelta(hours=1)
    planned = {"type": "jokerBreak", "parameters": {"startsAt": _iso(later)}}

    def plans():
        return game.status("anna")["status"]["plannedBreaks"]

    # While a speedhunt runs a break that starts now is refused for everyone, but a planned one is accepted.
    assert refusal("/game-requests", {"type": "jokerBreak"}, game.credentials["anna"]) == BREAK_UNAVAILABLE
    request = game.submit("anna", planned)
    assert request["parameters"]["startsAt"] == _iso(later), request
    game.claim(request["id"])
    game.succeed(request["id"], "Your break is planned.")
    planned_breaks = plans()
    assert len(planned_breaks) == 1 and planned_breaks[0]["startsAt"] == _iso(later), planned_breaks
    assert planned_breaks[0]["state"] == "planned" and planned_breaks[0]["plannedBy"] == "participant"
    inventory = game.inventory("anna", "break")
    assert inventory["reserved"] == 1 and inventory["available"] == 0, inventory
    # Private: hunters and other players see neither the plan nor the request.
    for key in ("hunter", "ben", "spectator"):
        assert _iso(later) not in json.dumps(game.status(key)), key
        assert all(item["type"] != "jokerBreak" for item in game.requests(key))
    # Breaks of one participant never overlap.
    overlapping = {"type": "jokerBreak", "parameters": {"startsAt": _iso(later + timedelta(minutes=5))}}
    assert refusal("/game-requests", overlapping, game.credentials["anna"]).startswith("This break would overlap")
    reservation = planned_breaks[0]["reservationId"]
    assert api.request("POST", f"/game-jokers/reservations/{reservation}/cancel", {},
                       credentials=game.credentials["anna"])["status"] == "released"
    assert plans() == [] and game.inventory("anna", "break")["available"] == 1

    # An operator plans breaks: with the available joker, then beyond the allocation only when confirmed.
    first = api.request("POST", plan_endpoint, {"memberId": game.member_id("anna"), "startsAt": _iso(later)})
    api.request("POST", plan_endpoint, {"memberId": game.member_id("anna"),
                                        "startsAt": _iso(later + timedelta(minutes=5)), "overAllocate": True},
                expected=409)
    second_start = later + timedelta(hours=1)
    api.request("POST", plan_endpoint, {"memberId": game.member_id("anna"), "startsAt": _iso(second_start)},
                expected=409)
    second = api.request("POST", plan_endpoint, {"memberId": game.member_id("anna"),
                                                 "startsAt": _iso(second_start), "overAllocate": True})
    api.request("POST", plan_endpoint, {"memberId": game.member_id("anna"), "startsAt": _iso(later)},
                credentials=game.credentials["anna"], expected=423)
    by_start = {item["startsAt"]: item for item in plans()}
    assert set(by_start) == {_iso(later), _iso(second_start)}, by_start
    assert all(item["plannedBy"] == "operator" for item in by_start.values())
    first_break, second_break = by_start[_iso(later)]["breakId"], by_start[_iso(second_start)]["breakId"]
    assert first and second
    # Operator plans cannot be cancelled by the participant (no reservation of their own request).
    # The planned start arrives while a speedhunt runs: the break waits, then starts when it ends.
    game.effects(operations=[{"type": "breakWaiting", "breakId": first_break}])
    assert next(item for item in plans() if item["breakId"] == first_break)["state"] == "waiting"
    game.end_run("ben")
    game.effects(operations=[{"type": "breakStarted", "breakId": first_break}])
    assert game.status("anna")["status"]["break"]["active"]
    game.effects(operations=[{"type": "breakCancelled", "breakId": second_break, "detail": "Cancelled by the operator"}])
    assert plans() == []
    inventory = game.inventory("anna", "break")
    assert inventory["reserved"] == 0 and inventory["overAllocated"] == 0, inventory
    game.effects(operations=[{"type": "breakEnded", "breakId": first_break}])
    if tool_source:
        sys.path.insert(0, tool_source)
        from traccar_game_manager.application.game_actions.planned_breaks import planned_breaks as tool_plans
        assert tool_plans(api.request("GET", f"/game-sessions/{game.id}/jokers")) == {}
    game.reconcile()


def audit_mirror_only_access(game):
    """With break or fake jokers in the session, hunters never get real participant trackers."""
    fixtures = game.fixtures
    parent = fixtures.group(api.PREFIX + "-real-parent")
    real = api.request("POST", "/groups", {"name": api.PREFIX + "-real", "groupId": parent["id"]})
    fixtures.groups.insert(0, real["id"])
    view = fixtures.group(api.PREFIX + "-hunter-view")
    tracker = game.trackers["anna"]
    hunter_id, player_id = game.users["hunter"]["id"], game.users["ben"]["id"]
    device = api.request("GET", f"/devices/{tracker['id']}")
    second_user, _ = fixtures.account(api.PREFIX + "-hunter2")
    try:
        api.request("PUT", f"/devices/{tracker['id']}", {**device, "groupId": real["id"]})
        # Direct access, access inherited from a parent group, and moves into a group a hunter sees are refused.
        api.request("POST", "/permissions", {"userId": hunter_id, "deviceId": tracker["id"]}, expected=422)
        api.request("POST", "/permissions", {"UserId": hunter_id, "DeviceId": tracker["id"]}, expected=422)
        api.request("POST", "/permissions/bulk", [{"USERID": str(hunter_id), "DEVICEID": str(tracker["id"])}],
                    expected=422)
        api.request("POST", "/permissions", {"userId": hunter_id, "groupId": parent["id"]}, expected=422)
        api.request("POST", "/permissions", {"userId": hunter_id, "groupId": view["id"]}, expected=204)
        group = api.request("GET", f"/groups/{real['id']}")
        api.request("PUT", f"/groups/{real['id']}", {**group, "groupId": view["id"]}, expected=422)
        api.request("PUT", f"/devices/{tracker['id']}", {**device, "groupId": view["id"]}, expected=422)
        # Other members keep their real access.
        api.request("POST", "/permissions", {"userId": player_id, "groupId": parent["id"]}, expected=204)
        api.request("DELETE", "/permissions", {"userId": player_id, "groupId": parent["id"]}, expected=204)

        # Access granted behind the server's back blocks new break jokers and bindings that add a hunter with it.
        api.sql(f"INSERT INTO tc_user_device(userid,deviceid) VALUES({hunter_id},{tracker['id']});")
        refusal(f"/game-sessions/{game.id}/jokers/change", {
            "memberId": game.member_id("anna"), "type": "break", "quantity": 1})
        refusal(f"/game-sessions/{game.id}/jokers/change", {
            "memberId": game.member_id("anna"), "type": "fakePing", "quantity": 1})
        game.change("anna", "hunterPositions", 1)
        game.change("anna", "hunterPositions", -1)
        api.sql(f"INSERT INTO tc_user_device(userid,deviceid) VALUES({second_user['id']},{tracker['id']});")
        hunter2 = api.plan(game.id, "hunter", "Hunter 2")
        api.bind_account(game.id, hunter2["id"], second_user["id"], expected=422)
        assert api.member(game.id, hunter2["id"])["userId"] is None, "a refused binding changes nothing"
        api.request("POST", f"/game-sessions/{game.id}/members/{hunter2['id']}/retire", {})
    finally:
        api.sql(f"DELETE FROM tc_user_device WHERE userid IN ({hunter_id},{second_user['id']}) "
                f"AND deviceid={tracker['id']};")
        api.request("PUT", f"/devices/{tracker['id']}", {**device, "groupId": 0})


def audit_timetable_fakes(game):
    """Timetable fakes: eligible pings, plans private to their owner, used with the hunters' delivery, else released."""
    game.change("anna", "fakePing", 2)
    base = (datetime.now(timezone.utc) + timedelta(hours=1)).replace(second=0, microsecond=0)
    names = {key: game.members[key]["displayName"] for key in ("anna", "ben")}
    cells = {"anna": ["", "2H", "b", "47.1,8.5|2H", "2P2H"], "ben": ["", "2H"]}
    timetable = f"/game-sessions/{game.id}/timetable"
    published = api.request("PUT", timetable, {
        "expectedRevision": api.request("GET", timetable)["revision"],
        "columns": ["Time", names["anna"], names["ben"]], "source": {},
        "entries": [{"scheduledAt": _iso(base + timedelta(minutes=10 * index)), "participant": names[key],
                     "content": cell} for key, row in cells.items() for index, cell in enumerate(row)]})
    ids = {(entry["memberId"], entry["content"]): entry["id"] for entry in published["entries"]}
    anna = game.member_id("anna")

    def arm(entry_id, expected=200):
        request = game.submit("anna", {"type": "jokerFakePing", "parameters": {
            "entryId": entry_id, "latitude": PRIVATE_LATITUDE, "longitude": 8.5}}, expected=expected)
        if expected != 200:
            return None
        game.claim(request["id"])
        game.succeed(request["id"], "Your fake ping is armed.")
        return next(item["reservationId"] for item in game.status("anna")["status"]["fakes"]
                    if item.get("entryId") == entry_id)

    own = game.own_jokers("anna")["timetableEntries"]
    # Blank cells and hunter target codes reach the hunters; an operator coordinate is not the player's to fake.
    assert {item["entryId"] for item in own} == {ids[(anna, "")], ids[(anna, "2H")], ids[(anna, "2P2H")]}, own
    game.submit("anna", {"type": "jokerFakePing", "parameters": {"entryId": ids[(anna, "47.1,8.5|2H")],
                                                                  "latitude": 47, "longitude": 8}}, expected=409)
    assert "is too close" not in json.dumps(own)
    game.submit("anna", {"type": "jokerFakePing", "parameters": {"entryId": ids[(anna, "b")],
                                                                  "latitude": 47, "longitude": 8}}, expected=409)
    game.submit("anna", {"type": "jokerFakePing", "parameters": {"entryId": ids[(game.member_id("ben"), "")],
                                                                  "latitude": 47, "longitude": 8}}, expected=400)
    first = arm(ids[(anna, "")])
    game.submit("anna", {"type": "jokerFakePing", "parameters": {"entryId": ids[(anna, "")],
                                                                  "latitude": 47, "longitude": 8}}, expected=409)
    # Spectators hold no jokers (403); the others read their own.
    api.request("GET", "/game-jokers", credentials=game.credentials["spectator"], expected=403)
    for key in ("ben", "hunter", "spectator"):
        text = json.dumps([game.status(key), game.own_jokers(key) if key != "spectator" else None,
                           game.requests(key), api.request("GET", "/game-timetable",
                                                           credentials=game.credentials[key])])
        assert str(PRIVATE_LATITUDE) not in text, f"Anna's fake plan leaked to {key}"
    # The Tool delivers the fake with the hunters' ping (fakeUsed) and the real coordinate as a guarded fallback.
    mirror = game.mirrors["anna"]
    used = game.effects(operations=[{"type": "fakeUsed", "reservationId": first}], positions=[
        {"deviceId": mirror["id"], "latitude": PRIVATE_LATITUDE, "longitude": 8.5, "fakeReservationId": first,
         "fallbackLatitude": 47.0, "fallbackLongitude": 8.0}])
    position = api.request("GET", f"/positions?id={used['positions'][0]['positionId']}")[0]
    assert position["latitude"] == PRIVATE_LATITUDE, position
    assert game.inventory("anna", "fakePing")["used"] == 1
    # A fake whose ping is skipped or no longer reaches the hunters is released with the real position.
    second = arm(ids[(anna, "2H")])
    released = game.effects(operations=[{"type": "fakeReleased", "reservationId": second, "detail": "skipped"}],
                            positions=[{"deviceId": mirror["id"], "latitude": 47.2, "longitude": 8.2}])
    assert released["fakes"] == [{"reservationId": second, "status": "released"}], released
    inventory = game.inventory("anna", "fakePing")
    assert inventory["used"] == 1 and inventory["reserved"] == 0 and inventory["available"] == 1, inventory
    # The owner cancels an unused fake while the Tool delivers it: the fake is skipped and the guarded position
    # carries the real coordinate, so a cancellation never races the delivery.
    third = arm(ids[(anna, "2P2H")])
    assert api.request("POST", f"/game-jokers/reservations/{third}/cancel", {},
                       credentials=game.credentials["anna"])["status"] == "released"
    raced = game.effects(operations=[{"type": "fakeUsed", "reservationId": third}], positions=[
        {"deviceId": mirror["id"], "latitude": PRIVATE_LATITUDE, "longitude": 8.5, "fakeReservationId": third,
         "fallbackLatitude": 47.3, "fallbackLongitude": 8.3}])
    assert raced["fakes"] == [{"reservationId": third, "status": "skipped"}], raced
    position = api.request("GET", f"/positions?id={raced['positions'][0]['positionId']}")[0]
    assert position["latitude"] == 47.3, position
    assert game.inventory("anna", "fakePing")["used"] == 1
    # A guarded position without its fake's settlement in the same delivery is refused.
    game.effects(positions=[{"deviceId": mirror["id"], "latitude": 1, "longitude": 1, "fakeReservationId": third,
                             "fallbackLatitude": 47.3, "fallbackLongitude": 8.3}], expected=400)
    game.reconcile()


def audit_speedhunt_fakes(game):
    """Speedhunt fakes: armed for the next run, bound at its first ping, applied to the hunters' mirror, private."""
    game.change("anna", "fakePing", 1)
    game.change("ben", "fakePing", 1)
    before = {key: game.inventory(key, "fakePing") for key in ("anna", "ben")}

    def arm(key, latitude, **parameters):
        request = game.submit(key, {"type": "jokerFakePing", "parameters": {
            "pingNumber": 2, "latitude": latitude, "longitude": 8.5, **parameters}})
        game.claim(request["id"])
        game.succeed(request["id"], "Your fake is armed.")
        return next(item for item in game.status(key)["status"]["fakes"] if item["use"] == "speedhunt")

    game.submit("anna", {"type": "jokerFakePing", "parameters": {"pingNumber": 5, "latitude": 1, "longitude": 1}},
                expected=409)
    anna_fake = arm("anna", PRIVATE_LATITUDE)
    ben_fake = arm("ben", 46.5)
    game.submit("anna", {"type": "jokerFakePing", "parameters": {"pingNumber": 3, "latitude": 1, "longitude": 1}},
                expected=409)
    # The run's first ping binds every armed speedhunt fake (both owners alike); ping 2 delivers the target's fake.
    sockets = [game.fixtures.socket(game.credentials[key]) for key in ("anna", "ben")]
    for socket in sockets:
        api.drain(socket)
    started = game.ping("anna", 1)
    announced = [sorted(set(api.invalidations(socket))) for socket in sockets]
    assert announced[0] == announced[1] and "game-jokers" in announced[0], announced
    assert {item["reservationId"]: item["status"] for item in started["fakes"]} == {
        anna_fake["reservationId"]: "bound", ben_fake["reservationId"]: "bound"}, started
    for key in ("anna", "ben"):
        assert all(item["use"] != "speedhunt" for item in game.status(key)["status"]["fakes"]), key
        bound = [item for item in game.own_jokers(key)["reservations"] if (item.get("fake") or {}).get("bound")]
        assert len(bound) == 1 and "boundRunId" not in bound[0]["fake"], bound
    mirror = game.mirrors["anna"]
    delivered = game.effects(operations=[{"type": "pingDelivered", "targetMemberId": game.member_id("anna"),
                                          "number": 2, "mode": "static", "source": "real"}],
                             positions=[{"deviceId": mirror["id"], "latitude": 47.0, "longitude": 8.0}])
    assert delivered["operations"][0]["source"] == "jokerFake", delivered
    position = api.request("GET", f"/positions?id={delivered['positions'][0]['positionId']}")[0]
    assert position["latitude"] == PRIVATE_LATITUDE, position
    for key in ("hunter", "spectator", "ben", "anna"):
        assert "jokerFake" not in json.dumps(game.status(key)), "the ping source stays with the operators"
    game.end_run("anna")
    # A bound fake is never refunded, also for the owner who was not the target.
    for key in ("anna", "ben"):
        inventory = game.inventory(key, "fakePing")
        assert inventory["available"] == before[key]["available"] - 1, (key, before[key], inventory)
        assert inventory["used"] + inventory["reserved"] == before[key]["used"] + before[key]["reserved"] + 1
    game.reconcile()


def audit_retired_target(game):
    """Retiring a speedhunt's target ends its run, the requests on it and its live uploads; nothing waits for it."""
    zoe = game.add("zoe", "player", "Zoe")
    game.change("ben", "break", 1)
    game.ping("zoe", 1, overAllocate=True)
    game.effects(operations=[{"type": "liveUploadStarted", "purpose": "manual", "sourceMemberId": zoe["id"],
                              "toHunters": True, "toPlayers": False, "toSupporters": False, "intervalSeconds": 15,
                              "deadline": api.iso(minutes=5), "reason": "audit"}])
    pending = game.submit("hunter", {"type": "speedhuntStaticPing", "targetMemberId": zoe["id"],
                                     "baseRevision": game.revision("zoe")})
    assert refusal("/game-requests", {"type": "jokerBreak"}, game.credentials["ben"]) == BREAK_UNAVAILABLE
    api.request("POST", f"/game-sessions/{game.id}/members/{zoe['id']}/retire", {})
    assert not game.status("ben")["status"]["speedhunt"]["active"]
    assert game.operator_request(pending["id"])["status"] == "expired"
    state = api.request("GET", f"/game-sessions/{game.id}/snapshot")["state"]
    run = next(item for item in state["runs"] if item["run"]["targetMemberId"] == zoe["id"])["run"] \
        if isinstance(state["runs"][0], dict) and "run" in state["runs"][0] else \
        next(item for item in state["runs"] if item["targetMemberId"] == zoe["id"])
    assert run["outcome"] == "terminated", run
    assert all(item["state"] != "active" for item in state["liveUploads"] if item["sourceMemberId"] == zoe["id"])
    request = game.submit("ben", {"type": "jokerBreak"})
    api.request("POST", f"/game-requests/{request['id']}/cancel", {}, credentials=game.credentials["ben"])
    game.change("ben", "break", -1)
    game.reconcile()


def audit_participant_privacy(fixtures):
    """Leakage between participants: own data only, nobody acts for another, nothing reveals a speedhunt target.

    Anna is the speedhunt target, Ben is not; both must see exactly the same thing apart from their own jokers.
    """
    game = Game(fixtures, "privacy", {"break_duration_minutes": 10, "pings_per_speedhunt": 4},
                [("hunter", "hunter", "Hunter"), ("anna", "player", "Anna"), ("ben", "player", "Ben"),
                 ("spectator", "spectator", "Spectator")])
    api.execute(game.id)
    game.change(None, "speedhunt", 3)
    for key in ("anna", "ben"):
        for joker in ("hunterPositions", "break", "fakePing"):
            game.change(key, joker, 1)

    def arm(key, latitude):
        request = game.submit(key, {"type": "jokerFakePing", "parameters": {
            "pingNumber": 2, "latitude": latitude, "longitude": 8.5}})
        game.claim(request["id"])
        game.succeed(request["id"], "Your fake is armed.")
        return next(item["id"] for item in game.own_jokers(key)["reservations"] if item["armed"])

    anna, ben = game.credentials["anna"], game.credentials["ben"]
    anna_request = game.submit("anna", {"type": "jokerHunterPositions"})
    # Acting for another participant: jokers are always the requester's own.
    ben_request = game.submit("ben", {"type": "jokerHunterPositions", "targetMemberId": game.member_id("anna")})
    assert ben_request["targetMemberId"] == game.member_id("ben"), ben_request
    anna_fake = arm("anna", PRIVATE_LATITUDE)
    ben_own = game.own_jokers("ben")
    assert {item["memberId"] for item in ben_own["inventories"] + ben_own["reservations"]} \
        == {game.member_id("ben")}, ben_own
    assert str(PRIVATE_LATITUDE) not in json.dumps(ben_own), "another participant's fake plan leaked"
    assert anna_request["id"] not in {item["id"] for item in game.requests("ben")}
    ben_status = game.status("ben")
    assert "participants" not in ben_status["status"] and ben_status["status"]["fakes"] == [], ben_status
    assert str(PRIVATE_LATITUDE) not in json.dumps(ben_status)
    api.request("POST", f"/game-requests/{anna_request['id']}/cancel", {}, credentials=ben, expected=403)
    # Another owner's reservation is answered like one that does not exist: probing tells nothing.
    assert refusal(f"/game-jokers/reservations/{anna_fake}/cancel", {}, ben, 404) \
        == refusal(f"/game-jokers/reservations/{anna_fake + 1000000}/cancel", {}, ben, 404)
    assert any(item["id"] == anna_fake and item["armed"] for item in game.own_jokers("anna")["reservations"])
    assert next(item for item in game.requests("anna") if item["id"] == anna_request["id"])["status"] == "pending"
    hunter_view = json.dumps([game.status("hunter"), game.requests("hunter"), game.own_jokers("hunter")])
    assert str(PRIVATE_LATITUDE) not in hunter_view and '"fakes"' not in hunter_view \
        and "jokerHunterPositions" not in hunter_view, hunter_view
    for credentials, request in ((anna, anna_request), (ben, ben_request)):
        api.request("POST", f"/game-requests/{request['id']}/cancel", {}, credentials=credentials)
    arm("ben", 46.5)

    # Nothing tells Anna that a speedhunt targets her: not the status, a refusal, her jokers or the timing of what
    # the server announces.
    def own_views():
        return {key: (game.own_jokers(key), game.requests(key)) for key in ("anna", "ben")}

    def member_views():
        views = {}
        for key in ("anna", "ben"):
            view = game.status(key)
            view["status"].pop("fakes")
            views[key] = {name: value for name, value in view.items()
                          if name not in ("serverTime", "memberId", "number", "displayName")}
        return views

    before = own_views()
    sockets = [fixtures.socket(anna), fixtures.socket(ben)]
    for socket in sockets:
        api.drain(socket)
    revision = game.revision("anna")
    hunter_request = game.submit("hunter", {"type": "speedhuntStaticPing", "targetMemberId": game.member_id("anna"),
                                            "baseRevision": revision})
    announced = [sorted(set(api.invalidations(socket))) for socket in sockets]
    assert announced[0] == announced[1], announced
    assert hunter_request["id"] not in {item["id"] for item in game.requests("anna")}
    game.claim(hunter_request["id"])
    game.succeed(hunter_request["id"], "Ping 1 of 4 sent.", operations=[{
        "type": "pingDelivered", "targetMemberId": game.member_id("anna"), "number": 1, "mode": "static",
        "source": "real", "toHunters": True, "toPlayers": True, "toSupporters": True,
        "requestId": hunter_request["id"]}])
    announced = [sorted(set(api.invalidations(socket))) for socket in sockets]
    assert announced[0] == announced[1] and "game-status" in announced[0], announced
    views = member_views()
    # The anonymous speedhunt status and its hash are the same for the target and everyone else.
    assert views["anna"]["status"] == views["ben"]["status"] and views["anna"]["hash"] == views["ben"]["hash"], views
    assert views["anna"]["status"]["speedhunt"]["active"] is True, views
    after = own_views()
    for key in ("anna", "ben"):
        assert after[key][1] == before[key][1], "a participant's requests changed when a speedhunt started"
        bound = [item for item in after[key][0]["reservations"] if (item.get("fake") or {}).get("bound")]
        assert len(bound) == 1 and "boundRunId" not in bound[0]["fake"], bound
    assert refusal("/game-requests", {"type": "jokerBreak"}, anna) \
        == refusal("/game-requests", {"type": "jokerBreak"}, ben) == BREAK_UNAVAILABLE
    game.end_run("anna")
    views = member_views()
    assert views["anna"]["status"] == views["ben"]["status"] and views["anna"]["hash"] == views["ben"]["hash"]
    audit_timetable_privacy(game, sockets)


def audit_timetable_privacy(game, sockets):
    """Timetable pings: own fake-eligible pings for each player, every hunter-facing ping for hunters and
    spectators, without cell content, so an operator coordinate or a fake looks exactly like a real ping."""
    game.change("anna", "fakePing", 1)
    base = (datetime.now(timezone.utc) + timedelta(hours=1)).replace(second=0, microsecond=0)
    names = {key: game.members[key]["displayName"] for key in ("anna", "ben")}
    cells = {"anna": ["", "2H", "b", "x", "47.1,8.5|2H", "2P2S"], "ben": ["", "2H"]}
    timetable = f"/game-sessions/{game.id}/timetable"
    published = api.request("PUT", timetable, {
        "expectedRevision": api.request("GET", timetable)["revision"],
        "columns": ["Time", names["anna"], names["ben"]], "source": {},
        "entries": [{"scheduledAt": _iso(base + timedelta(minutes=10 * index)), "participant": names[key],
                     "content": cell} for key, row in cells.items() for index, cell in enumerate(row)]})
    ids = {(entry["participant"], entry["content"]): entry["id"] for entry in published["entries"]}
    faked = ids[(names["anna"], "")]
    request = game.submit("anna", {"type": "jokerFakePing", "parameters": {
        "entryId": faked, "latitude": PRIVATE_LATITUDE, "longitude": 8.5}})
    game.claim(request["id"])
    game.succeed(request["id"], "Your fake ping is armed.")

    hunter_facing = {ids[(names["anna"], cell)] for cell in ("", "2H", "47.1,8.5|2H")} \
        | {ids[(names["ben"], cell)] for cell in ("", "2H")}
    # Hunters see only the distinct times of the pings that reach them: no entry, participant or state.
    hunter_pings = api.request("GET", "/game-timetable", credentials=game.credentials["hunter"])
    facing_times = sorted({_iso(base + timedelta(minutes=10 * index)) for index in (0, 1, 4)})
    assert [item["scheduledAt"][:16] for item in hunter_pings] == [value[:16] for value in facing_times], \
        hunter_pings
    assert all(sorted(item) == ["scheduledAt"] for item in hunter_pings), hunter_pings
    # Spectators see each of those pings with its state; the faked ping, the operator's coordinate and a real
    # ping differ only in entry, time and member.
    spectator_pings = api.request("GET", "/game-timetable", credentials=game.credentials["spectator"])
    assert {item["entryId"] for item in spectator_pings} == hunter_facing, spectator_pings
    text = json.dumps([hunter_pings, spectator_pings])
    assert "47.1" not in text and str(PRIVATE_LATITUDE) not in text and "content" not in text, text
    assert all(sorted(item) == sorted(spectator_pings[0]) and item.get("fakeAllowed") is None
               and item["state"] == "scheduled" and item.get("stateAt") is None for item in spectator_pings), \
        spectator_pings
    for key, expected in (("anna", [(ids[(names["anna"], "")], False), (ids[(names["anna"], "2H")], True)]),
                          ("ben", [(ids[(names["ben"], "")], True), (ids[(names["ben"], "2H")], True)])):
        own = api.request("GET", "/game-timetable", credentials=game.credentials[key])
        assert [(item["entryId"], item["fakeAllowed"]) for item in own] == expected, own
        assert {item["memberId"] for item in own} == {game.member_id(key)}, own

    # Progress is announced to every member alike; the faked ping is recorded like any other.
    sockets.append(game.fixtures.socket(game.credentials["hunter"]))
    for socket in sockets:
        api.drain(socket, 0.5)
    api.effects(game.id, operations=[{"type": "fakeUsed", "reservationId": next(
        item["reservationId"] for item in game.status("anna")["status"]["fakes"] if item.get("entryId") == faked)}])
    for socket in sockets:
        api.drain(socket, 0.5)
    api.request("PUT", f"{timetable}/progress", {"entries": [
        {"id": faked, "status": "sent"}, {"id": ids[(names["ben"], "")], "status": "sent"}]}, expected=204)
    announced = [sorted(set(api.invalidations(socket))) for socket in sockets]
    assert all(item == announced[0] for item in announced) and "game-timetable" in announced[0], announced
    sent = {item["entryId"]: item for item in api.request("GET", "/game-timetable",
                                                          credentials=game.credentials["spectator"])}
    anna_sent, ben_sent = sent[faked], sent[ids[(names["ben"], "")]]
    assert anna_sent["state"] == ben_sent["state"] == "sent" and anna_sent["stateAt"] == ben_sent["stateAt"]


# Traccar Tool against the server ------------------------------------------------------------------------------

def tool_api(tool_source):
    sys.path.insert(0, tool_source)
    import requests
    from traccar_game_manager.infrastructure.traccar.game_sessions import GameSessionApi

    class Transport:
        get, post, put, delete = (staticmethod(requests.get), staticmethod(requests.post),
                                  staticmethod(requests.put), staticmethod(requests.delete))

    return GameSessionApi(api.BASE, api.EMAIL, api.PASSWORD, transport=Transport(),
                          epoch_provider=lambda: api.CONTROLLER["epoch"])


def tool_gateway(tool_source, game):
    """The Tool's request gateway and effects for the audit's session, as ``ServerConnection.requests_for``."""
    sys.path.insert(0, tool_source)
    from traccar_game_manager.infrastructure.traccar.effects import SessionEffects
    from traccar_game_manager.infrastructure.traccar.game_requests import GameRequestGateway

    client = tool_api(tool_source)
    effects = SessionEffects(client, game.id)
    return client, GameRequestGateway(client, game.id, effects)


def audit_tool_requests(tool_source, game):
    """The Tool's request processor and gateway against the real server: revisions, approval, a delivered ping."""
    from traccar_game_manager.application.game_requests.processor import (
        GameRequestProcessor, RequestCheck, RequestResult)
    from traccar_game_manager.domain.game.settings import normalize_game_settings

    client, gateway = tool_gateway(tool_source, game)
    member_id = game.member_id("ben")
    executed, logs = [], []

    class Actions:
        def participant(self, target):
            return {"id": game.trackers["ben"]["id"], "name": "Ben", "caught": False} if target == member_id \
                else None

        def check(self, kind, item, parameters):
            return RequestCheck()

        def execute(self, kind, item, parameters):
            executed.append(kind)
            # As the speedhunt service: the ping's typed operation commits with the outcome.
            return RequestResult(True, "Ping 1 of 4 sent.", operations=({
                "type": "pingDelivered", "targetMemberId": member_id, "number": 1, "mode": "static",
                "source": "real", "toHunters": True, "toPlayers": True, "toSupporters": True,
                "requestId": int(parameters["requestId"])},))

    def revisions():
        return {member.id: member.state_revision for member in gateway.members()}

    settings = normalize_game_settings({})
    processor = GameRequestProcessor(gateway, Actions(), settings=lambda: settings, revisions=revisions,
                                     log=logs.append)
    # A request based on an old revision is rejected automatically with the stale message.
    revision = game.revision("ben")
    stale = game.submit("hunter", {"type": "speedhuntStaticPing", "targetMemberId": member_id,
                                   "baseRevision": revision})
    game.effects(operations=[{"type": "catch", "memberId": member_id}])
    game.effects(operations=[{"type": "release", "memberId": member_id}])
    processor.sync(gateway.list_open())
    assert game.operator_request(stale["id"])["status"] in ("rejected", "cancelled", "failed"), \
        game.operator_request(stale["id"])
    request = game.submit("hunter", {"type": "speedhuntStaticPing", "targetMemberId": member_id,
                                     "baseRevision": game.revision("ben")})
    processor.sync(gateway.list_open())
    awaiting = processor.awaiting()
    assert [item["id"] for item in awaiting] == [request["id"]], (awaiting, logs)
    assert game.requests("hunter")[0]["approvalRequired"] is True
    processor.approve(request["id"])
    done = game.operator_request(request["id"])
    assert done["status"] == "succeeded" and done["message"] == "Ping 1 of 4 sent.", (done, logs)
    assert executed == ["speedhuntStaticPing"]
    ben = game.participant("hunter", "ben")["speedhunt"]
    assert ben["running"] and ben["pings"] == 1, ben
    game.end_run("ben")


def run_break_through_tool(tool_source, game):
    """The Tool's request processor and break service approve the break, start it with the outcome and end it."""
    from traccar_game_manager.application.game_actions.breaks import BreakService
    from traccar_game_manager.application.game_requests.processor import (
        GameRequestProcessor, RequestCheck, RequestResult)
    from traccar_game_manager.domain.game.settings import normalize_game_settings

    class Clock:
        def now(self):
            return datetime.now(timezone.utc)

    client, gateway = tool_gateway(tool_source, game)
    settings = normalize_game_settings({"break_duration_minutes": 15})
    breaks = BreakService({}, clock=Clock())
    tracker = game.trackers["anna"]["id"]
    logs = []

    class Actions:
        def participant(self, member_id):
            return {"id": tracker, "name": "Anna", "caught": False} \
                if member_id == game.member_id("anna") else None

        def check(self, kind, item, parameters):
            return RequestCheck(error=breaks.activation_error(item["id"], settings=settings,
                                                              speedhunt_running=False))

        def execute(self, kind, item, parameters):
            record = breaks.start(item["id"], settings=settings)
            operation = {"type": "breakStarted", "requestId": int(parameters["requestId"]),
                         "deadline": _iso(record.ends_at)}

            def recorded(outcome):
                result = next(entry for entry in outcome["operations"] if entry["operation"] == "breakStarted")
                breaks.identify(item["id"], result["id"])

            return RequestResult(True, "Your break runs for 15 min.", operations=(operation,),
                                 on_outcome=recorded)

    def revisions():
        return {member.id: member.state_revision for member in gateway.members()}

    processor = GameRequestProcessor(gateway, Actions(), settings=lambda: settings, revisions=revisions,
                                     log=logs.append)
    processor.sync(gateway.list_open())
    awaiting = processor.awaiting()
    assert [item["type"] for item in awaiting] == ["jokerBreak"], (awaiting, logs)
    processor.approve(awaiting[0]["id"])
    record = breaks.get(tracker)
    assert record is not None and record.break_id, (record, logs)
    # The server keeps the Tool's deadline.
    own = game.status("anna")["status"]["break"]
    assert own["active"] and own["endsAt"].startswith(_iso(record.ends_at)[:19]), (own, record)
    return record


def audit_tool(tool_source):
    """Create, save, reload, plan members and delete a session through the Tool's repository."""
    sys.path.insert(0, tool_source)
    from traccar_game_manager.domain.session.document import validate_session_document
    from traccar_game_manager.domain.session.factory import default_session_template
    from traccar_game_manager.infrastructure.persistence.server_jobs import ServerJobQueue
    from traccar_game_manager.infrastructure.persistence.server_sessions import (
        ServerSessionLogStore,
        ServerSessionRepository,
    )
    from traccar_game_manager.infrastructure.persistence.session_preferences import JsonSessionPreferences
    import tempfile
    from pathlib import Path

    client = tool_api(tool_source)
    client.check_compatible()
    server = {"web_url": api.BASE, "client_url": api.UPLOAD, "username": api.EMAIL, "password": api.PASSWORD}
    jobs = ServerJobQueue()
    with tempfile.TemporaryDirectory() as tmp:
        repository = ServerSessionRepository(
            client, server=server, preferences=JsonSessionPreferences(Path(tmp) / "prefs.json"), jobs=jobs)
        name = api.PREFIX + "-tool"
        draft = default_session_template(name)
        draft["user_passwords"]["player"] = "player-" + secrets.token_hex(4)
        draft["ping_table_file"] = "/local/only.xlsx"
        session = repository.create(name, validate_session_document(draft, name))
        session_id = repository.session_id(name)
        api.GAME_SESSION_IDS.append(session_id)
        session["game_settings"]["pings_per_speedhunt"] = 7
        repository.save(name, session)
        repository.plan_members(name, {"anna-tracker": {"role": "player", "number": 1, "display_name": "Anna"},
                                       "ben-tracker": {"role": "player", "number": 2, "display_name": "Ben"},
                                       "hunter-tracker": {"role": "hunter", "number": 1, "display_name": "H"}})
        members = api.request("GET", f"/game-sessions/{session_id}/members")
        assert sorted((item["role"], item["number"], item["displayName"]) for item in members) == [
            ("hunter", 1, "H"), ("player", 1, "Anna"), ("player", 2, "Ben")], members
        logs = ServerSessionLogStore(client, jobs, repository.session_id)
        logs.append(name, "operation", "[10:00:00] audit")
        assert jobs.flush(30), "Background writes did not finish"
        assert jobs.drain_events() == []
        reloaded = ServerSessionRepository(
            client, server=server, preferences=JsonSessionPreferences(Path(tmp) / "other.json"),
            jobs=ServerJobQueue()).load(name)
        assert reloaded["game_settings"]["pings_per_speedhunt"] == 7
        assert reloaded["user_passwords"]["player"] == draft["user_passwords"]["player"]
        assert reloaded["ping_table_file"] == "", "Local paths must not be stored centrally"
        assert "game_status" not in reloaded
        stored = api.request("GET", f"/game-sessions/{session_id}")
        assert api.PASSWORD not in json.dumps(stored), "The Traccar admin password must not be stored centrally"
        logs.prefetch(name)
        assert logs.lines(name) == ("[10:00:00] audit\n",)
        assert repository.delete(name)
        api.GAME_SESSION_IDS.remove(session_id)
        jobs.close(5)


def main(tool_source=None):
    if tool_source:
        sys.path.insert(0, tool_source)
    api.wait_for_server()
    admin_id = api.create_admin()
    user_email = api.PREFIX + "-user@example.invalid"
    user_password = secrets.token_hex(24)
    user_id = api.create_user(api.PREFIX + "-user", user_email, user_password, False)
    fixtures = api.Fixtures()
    try:
        api.acquire_controller()
        audit_http(fixtures, (user_email, user_password))
        audit_controller(fixtures, user_id, (user_email, user_password))
        game = Game(fixtures, "game", {"pings_per_speedhunt": 4},
                    [("hunter", "hunter", "Hunter"), ("anna", "player", "Anna"), ("ben", "player", "Ben"),
                     ("sue", "supporter", "Sue"), ("spectator", "spectator", "Spectator")])
        audit_requests(game, tool_source)
        audit_jokers(game)
        audit_speedhunt_jokers(game)
        audit_breaks(game, tool_source)
        audit_mirror_only_access(game)
        audit_timetable_fakes(game)
        audit_speedhunt_fakes(game)
        audit_retired_target(game)
        # A change of control ends open requests: waiting ones expire.
        waiting = game.submit("hunter", {"type": "speedhuntTerminate", "targetMemberId": game.member_id("ben"),
                                         "baseRevision": game.revision("ben")}, expected=200)
        epoch = api.CONTROLLER["epoch"]
        api.request("POST", "/game-controller/takeover", {"instanceId": "audit-req-" + uuid4().hex[:16],
                                                          "label": "Other PC"}, epoch=False)
        expired = game.operator_request(waiting["id"])
        assert expired["status"] == "expired" and "Control of the game changed" in expired["message"], expired
        api.request("POST", "/game-controller/release", {}, epoch=epoch + 1)
        api.acquire_controller()
        assert not game.status("hunter")["controllerActive"]
        audit_participant_privacy(fixtures)
        if tool_source:
            audit_tool(tool_source)
        print("MySQL/HTTP game-session audit passed: admin-only access, API version 5, revision conflicts and "
              "idempotent retries, credential encryption at rest, timetable entries keyed by member, logs, "
              "cascading deletion, controller lease with fenced game and standard endpoints (path and body IDs, "
              "Jersey path numbers, permission keys as the resource reads them), push fencing and the outbox, drift reporting, takeover, expiry, idempotent effects with latest-position and online "
              "status, hunters' requests with role-filtered status, stale revisions, approval, claim, outcome and "
              "expiry on takeover, ping numbering and private sources, hunter positions, speedhunt pool and break "
              "jokers, catch and release, planned, waiting, started and cancelled breaks, mirror-only hunter "
              "access for permissions, group and device moves and bindings, timetable fakes (used, released, "
              "cancelled) with guarded positions, speedhunt fakes bound at a run's first ping and applied to the "
              "hunters' mirror, a retired speedhunt target ending its run, requests and live uploads, participant privacy (identical status, hash, refusals and announcements for a "
              "speedhunt's target), and timetable pings without cell content"
              + (", plus the Tool's request processor, break service and repository round trip." if tool_source
                 else "."))
    finally:
        fixtures.remove()
        api.delete_game_sessions()
        api.release_controller()
        api.sql(f"DELETE FROM tc_users WHERE id={user_id} AND email=" + api.literal(user_email) + ";")
        api.delete_admin(admin_id)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tool-source", help="Traccar Tool src directory for the Tool's services")
    main(parser.parse_args().tool_source)
