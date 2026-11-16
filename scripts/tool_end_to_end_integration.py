"""Headless Traccar Tool end-to-end audit against a model-2 server; creates and removes only its own fixtures.

Run against the full Docker profile with Traccar Tool's virtual environment:

    cd traccar/scripts && ../../Traccar-Tool/venv_traccar/bin/python tool_end_to_end_integration.py \\
        --tool-source ../../Traccar-Tool/src

It drives the Tool's own composition, not HTTP calls of its own: the server connection with the controller lease,
the session repository, device setup with member planning and the device-list import, the timetable editor's
document (changesets, server rejections, conflicts, Excel round trip, the execution toggle), the Traccar client with
the session's effects and game state, and the game services that need no window. See ``game_notification_integration``
for the environment and the temporary administrator. Pushes reach only fixture accounts without push tokens.
"""

import argparse
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import secrets
import sys
import tempfile
import time
from zoneinfo import ZoneInfo

import game_notification_integration as api


class Progress:
    def __init__(self):
        self.lines = []

    def write(self, message):
        self.lines.append(str(message))


def write_device_list(path, rows):
    import pandas as pd
    from traccar_game_manager.infrastructure.files.device_list import GROUP_COLUMN
    pd.DataFrame([{"name": name, "device identifier": unique_id, GROUP_COLUMN: role, "role number": number}
                  for name, unique_id, role, number in rows]).to_excel(path, index=False)


def main(tool_source):
    sys.path.insert(0, tool_source)
    from traccar_game_manager.app.connection import (
        ConnectionHolder, connect_to_server, effects_factory, fenced_transport_factory, game_state_factory)
    from traccar_game_manager.application.device_setup.models import DeviceSetupPlanningRequest
    from traccar_game_manager.domain.server_profiles import ServerProfile
    from traccar_game_manager.domain.session.document import validate_session_document
    from traccar_game_manager.domain.session.factory import default_session_template
    from traccar_game_manager.infrastructure.persistence.session_preferences import JsonSessionPreferences
    from traccar_game_manager.infrastructure.traccar.device_setup import create_device_setup_service
    from traccar_game_manager.infrastructure.traccar.factory import TraccarClientFactory
    from traccar_game_manager.infrastructure.traccar.transport import RequestsTransport

    api.wait_for_server()
    admin_id = api.create_admin()
    api.ADMIN_ID = [admin_id]
    fixtures = api.Fixtures()
    connection = None
    session_id = None
    tmp = tempfile.TemporaryDirectory()
    try:
        profile = ServerProfile(id="audit", name="Audit", web_url=api.BASE, client_url=api.UPLOAD,
                                username=api.EMAIL)
        connection = connect_to_server(profile, api.PASSWORD, transport_factory=RequestsTransport,
                                       preferences=JsonSessionPreferences(Path(tmp.name) / "prefs.json"))
        lease = connection.controller.snapshot()
        assert lease.controlling and lease.epoch, lease
        api.CONTROLLER["epoch"] = lease.epoch
        holder = ConnectionHolder()
        holder.connection = connection
        clients = TraccarClientFactory(fenced_transport_factory(holder, RequestsTransport), effects_factory(holder),
                                       game_state_factory(holder))

        name = api.PREFIX + "-tool"
        session = default_session_template(name)
        session["local_tz"] = "UTC"
        session["game_settings"].update({"pings_per_speedhunt": 4, "break_duration_minutes": 10})
        session["user_passwords"] = {role: role + "-" + secrets.token_hex(8)
                                     for role in ("player", "supporter", "hunter", "spectator")}
        session = connection.sessions.create(name, validate_session_document(session, name))
        session_id = connection.sessions.session_id(name)
        api.GAME_SESSION_IDS.append(session_id)
        session["traccar-server"] = profile.server_mapping(api.PASSWORD)

        # Device setup with member planning: the device list names the members by role and number.
        prefix = api.PREFIX[-12:]
        rows = [("Anna", prefix + "-anna", "player", 1), ("Ben", prefix + "-ben", "player", 2),
                ("Sue", prefix + "-sue", "supporter", 1), ("Hugo", prefix + "-hugo", "hunter", 1),
                ("Hanna", prefix + "-hanna", "hunter", 2)]
        device_list = Path(tmp.name) / "devices.xlsx"
        write_device_list(device_list, rows)
        groups = {"players": api.PREFIX + "-Players", "supporters": api.PREFIX + "-Supporters",
                  "hunters": api.PREFIX + "-Hunters"}

        def setup(current, path):
            request = DeviceSetupPlanningRequest(
                session_name=name, session=current, players_group_name=groups["players"],
                supporters_group_name=groups["supporters"], hunters_group_name=groups["hunters"],
                device_list_path=str(path), supporter_password="supporter-" + secrets.token_hex(6))
            client = clients.from_session(current)
            service = create_device_setup_service(
                client=client, request=request,
                session_saver=lambda session_name, document: connection.sessions.save(session_name, document),
                mirror_only_jokers_in_use=lambda: False,
                member_planner=lambda roster: connection.sessions.plan_members(name, roster))
            plan = service.prepare(request)
            progress = Progress()
            result = service.apply(plan, progress)
            connection.sessions.save(name, result.session)
            assert connection.jobs.flush(30)
            return result.session, progress, plan

        session, progress, _plan = setup(session, device_list)
        session["traccar-server"] = profile.server_mapping(api.PASSWORD)
        listed = api.request("GET", f"/game-sessions/{session_id}/members")
        if any(item["state"] != "active" for item in listed):
            print("MEMBERS", json.dumps(listed, indent=0))
            print("PROGRESS", "\n".join(progress.lines))
        members = {(item["role"], item["number"]): item for item in listed if item["state"] == "active"}
        assert {key: item["displayName"] for key, item in members.items() if key[0] != "spectator"} == {
            ("player", 1): "Anna", ("player", 2): "Ben", ("supporter", 1): "Sue", ("hunter", 1): "Hugo",
            ("hunter", 2): "Hanna"}, members
        devices = {device["uniqueId"]: device for device in api.request("GET", "/devices")}
        for display, unique_id, role, number in rows:
            member = members[(role, number)]
            assert member["trackerId"] == devices[unique_id]["id"] and member["userId"], member
            attributes = devices[unique_id]["attributes"]
            assert attributes.get("traccarToolRole") == role and attributes.get("traccarToolRoleNumber") == number
            # Role and number identify a member only within its session: the marker names the session.
            assert attributes.get("traccarToolGameSessionId") == session_id, attributes
        resources = api.request("GET", f"/game-sessions/{session_id}/resources")
        mirror_ids = {row["deviceId"] for row in resources["mirrors"] if row["state"] == "active"}
        assert mirror_ids and all(devices_by_id.get("attributes", {}).get("traccarToolMirror")
                                  for devices_by_id in api.request("GET", "/devices")
                                  if devices_by_id["id"] in mirror_ids)
        assert {row["purpose"] for row in resources["groups"]} >= {"player", "supporter", "hunter"}, resources
        assert all(device["attributes"].get("traccarToolGameSessionId") == session_id
                   for device in api.request("GET", "/devices") if device["id"] in mirror_ids)
        # Every generated account signs in and reads its own member's status; none is an administrator.
        logins = session["login_credentials"]
        assert logins, session.keys()
        accounts = {}
        for login, credential in logins.items():
            credentials = (login, credential["password"])
            status = api.request("GET", "/game-status", credentials=credentials)
            assert status["role"] == credential["role"], (login, status)
            if credential["role"] != "spectator":
                assert status["number"] == credential["number"], (login, status)
            accounts[(credential["role"], credential.get("number"))] = credentials
            user = api.request("GET", f"/users/{session['users'][login]}", credentials=credentials)
            assert not user["administrator"] and not any(
                key in (user.get("attributes") or {}) for key in ("traccarToolUserRole", "traccarToolSessionDeviceId",
                                                                  "traccarToolAssignedDeviceId"))
        # Hunters see the players and supporters through mirrors only; players see their own tracker.
        real = {devices[unique_id]["id"] for _, unique_id, role, _ in rows if role in ("player", "supporter")}
        for key, credentials in accounts.items():
            if key[0] == "hunter":
                visible = {device["id"] for device in api.request("GET", "/devices", credentials=credentials)}
                assert not visible & real, (key, visible & real)
                assert visible & mirror_ids, key
        anna = accounts[("player", 1)]
        assert devices[prefix + "-anna"]["id"] in {device["id"] for device in api.request(
            "GET", "/devices", credentials=anna)}

        # A second setup with the same device list changes nothing.
        revision = api.request("GET", f"/game-sessions/{session_id}")["revision"]
        session, progress, _plan = setup(session, device_list)
        session["traccar-server"] = profile.server_mapping(api.PASSWORD)
        again = {(item["role"], item["number"]): item
                 for item in api.request("GET", f"/game-sessions/{session_id}/members")}
        assert {key: (item["id"], item["userId"], item["trackerId"]) for key, item in again.items()} == {
            key: (item["id"], item["userId"], item["trackerId"]) for key, item in members.items()}
        # Device-list import with a replaced tracker: Ben keeps his member (and its history); the tracker rebinds.
        replaced = [row if row[1] != prefix + "-ben" else ("Ben", prefix + "-ben2", "player", 2) for row in rows]
        write_device_list(device_list, replaced)
        session, progress, _plan = setup(session, device_list)
        session["traccar-server"] = profile.server_mapping(api.PASSWORD)
        after = {(item["role"], item["number"]): item
                 for item in api.request("GET", f"/game-sessions/{session_id}/members")}
        new_ben = next(device for device in api.request("GET", "/devices") if device["uniqueId"] == prefix + "-ben2")
        assert after[("player", 2)]["id"] == members[("player", 2)]["id"]
        assert after[("player", 2)]["trackerId"] == new_ben["id"], after[("player", 2)]
        assert api.request("GET", "/game-status", credentials=accounts[("player", 2)])["memberId"] \
            == members[("player", 2)]["id"]
        print("Tool device setup passed: controller lease at connection, session creation, member planning from "
              "the device list, generated accounts bound to members (no model-1 markers, no administrators), "
              "trackers and mirrors with role and session markers, mirror and group rows, mirror-only hunter access, an idempotent "
              "second run, and a device-list import that rebinds a replaced tracker to the same member.")
        timetable_flow(connection, name, session_id, tmp.name)
        game_flow(connection, clients, session, name, session_id, accounts, profile, tmp.name)
        return connection, clients, session, name, session_id, accounts, members
    finally:
        try:
            if connection is not None and session_id is not None:
                cleanup_session(connection, clients, session, name)
        finally:
            fixtures.remove()
            api.delete_game_sessions()
            if connection is not None:
                connection.close(5)
            api.CONTROLLER["epoch"] = None
            api.delete_admin(admin_id)
            tmp.cleanup()


def timetable_flow(connection, name, session_id, tmp):
    """The timetable editor's document against the server: the Tool's own load, diff, save, rejection marking,
    reload-and-re-apply, Excel export and import, and the execution toggle with its events."""
    from zoneinfo import ZoneInfo

    from traccar_game_manager.app.live_session import create_timetable_document
    from traccar_game_manager.application.grid_documents import CONFLICT, REJECTED, SAVED
    from traccar_game_manager.domain.game.execution_events import describe

    zone = ZoneInfo("UTC")

    def document():
        made = create_timetable_document(connection.timetable_for(name), session_name=name,
                                         server_url=connection.api.web_url, time_zone=zone, controlling=lambda: True)
        made.apply_load(made.load_task()())
        return made

    def save(made):
        task = made.save_task()
        if not callable(task):
            return task
        try:
            result = task()
        except Exception as error:  # noqa: BLE001 - the document maps the port's errors to an outcome
            return made.save_failed(error)
        outcome = made.apply_save(result)
        made.apply_load(made.load_task()())
        return outcome

    editor = document()
    _participants, candidates = editor.roster()
    names = {label.split(" (")[0]: member_id for member_id, label in candidates}
    assert set(names) == {"Anna", "Ben", "Sue"}, candidates
    editor.set_participants([names["Anna"], names["Ben"], names["Sue"]])
    soon = editor.server_now().replace(microsecond=0) + timedelta(seconds=40)
    editor.insert_row(soon)
    start = (editor.server_now() + timedelta(minutes=10)).replace(second=0, microsecond=0)
    added, _skipped = editor.generate_rows(start.strftime("%Y-%m-%d %H:%M"),
                                           (start + timedelta(minutes=10)).strftime("%Y-%m-%d %H:%M"), 5)
    assert added == 3, added
    later = [row.key for row in editor.draft.rows if row.slot_id is None and row.scheduled_at >= start]
    editor.apply_edits({(later[0], names["Anna"]): " 2h ", (later[0], names["Ben"]): "", (later[1], names["Sue"]): "b"})
    outcome = save(editor)
    assert outcome.kind == SAVED, outcome
    # Saved rows are keyed by their slot from now on.
    by_time = {row.scheduled_at: row.key for row in editor.draft.rows}
    later = [by_time[start + timedelta(minutes=minutes)] for minutes in (0, 5, 10)]
    assert all(key.startswith("s") for key in later), later
    stored = connection.api.get_timetable(session_id)
    cells = {(slot["scheduledAt"][11:16], entry["memberId"]): entry["content"] for slot in stored["slots"]
             for entry in slot["entries"]}
    assert cells[(start.strftime("%H:%M"), names["Anna"])] == "2H", cells
    assert cells[(start.strftime("%H:%M"), names["Ben"])] == "", "a blank cell is stored as a real ping"
    assert len(stored["participants"]) == 3 and len(stored["slots"]) == 4, stored

    # A row that locked on the server while this Tool's clock lagged is rejected, marked, and nothing is saved.
    near = editor.draft.row_for_slot(min(slot["id"] for slot in stored["slots"]))
    time.sleep(max(0.0, (near.scheduled_at - editor.server_now()).total_seconds() - 12))
    editor._offset -= timedelta(minutes=2)
    editor.apply_edits({(near.key, names["Anna"]): "b", (later[2], names["Sue"]): "2S"})
    outcome = save(editor)
    assert outcome.kind == REJECTED and "frozen" in outcome.message, outcome
    assert (near.key, names["Anna"]) in editor.rejected, editor.rejected
    assert connection.api.get_timetable(session_id)["revision"] == stored["revision"]
    editor.apply_load(editor.load_task()())

    # Another editor saves first: this one reloads and re-applies its changes; a removed row's change is listed.
    other = document()
    other.delete_rows([later[1]])
    outcome = save(other)
    assert outcome.kind == SAVED, outcome
    editor.apply_edits({(later[1], names["Ben"]): "2P", (later[2], names["Anna"]): "47.1, 8.5 | 2p"})
    assert save(editor).kind == CONFLICT
    reapplied = editor.apply_reapply(editor.reapply_task()())
    assert len(reapplied.skipped) == 1 and "removed meanwhile" in reapplied.skipped[0], reapplied
    assert save(editor).kind == SAVED
    contents = {entry["content"] for slot in connection.api.get_timetable(session_id)["slots"]
                for entry in slot["entries"]}
    assert "47.1,8.5|2P" in contents, contents

    # Excel: the exported draft imports again onto the same slots, with nothing to save.
    path = Path(tmp) / "timetable.xlsx"
    editor.export_task(path)()
    imported = editor.import_task(path)()
    result = editor.apply_import(imported)
    assert not result.unmatched, result
    assert not editor.dirty(), (result, editor.status())

    # The toggle writes timetable events the upload log shows in the Tool's wording; leave it off and empty.
    timetable = connection.timetable_for(name)
    after = max((item["id"] for item in connection.api.events_after(session_id)), default=None)
    timetable.set_execution(True)
    timetable.set_execution(False)
    events = [item for item in connection.api.events_after(session_id, after) if item["category"] == "timetable"]
    shown = [describe(item, time_zone=zone).strip() for item in events]
    assert [line.split(" (")[0] for line in shown] == ["Automated upload started", "Automated upload stopped"], shown
    editor.apply_load(editor.load_task()())
    editor.delete_rows([row.key for row in editor.draft.rows])
    assert save(editor).kind == SAVED
    print("Tool timetable editor passed: participants and generated rows saved as one changeset with canonical "
          "cells, a server rejection (row frozen while the Tool's clock lagged) marked without saving anything, a "
          "conflict reloaded and re-applied with the change that no longer applies listed, an Excel export that "
          "imports onto the same slots, and the execution toggle's events in the upload log's wording.")


def game_flow(connection, clients, session, name, session_id, accounts, profile, tmp):
    """Catches, a Live Ping, live tracking, a hunter relay, a server restart and a controller handover through the
    Tool's client, effects, game state and controller lease."""
    from traccar_game_manager.app.connection import connect_to_server
    from traccar_game_manager.application.game_actions.catches import CatchService
    from traccar_game_manager.infrastructure.persistence.session_preferences import JsonSessionPreferences
    from traccar_game_manager.infrastructure.traccar.game_sessions import GameControllerLockedError
    from traccar_game_manager.infrastructure.traccar.transport import RequestsTransport

    started = connection.api.set_game_state(session_id, "running")
    assert started["state"] == "running", started
    client = clients.from_session(session)
    requests = connection.requests_for(name)
    state = client.load_game_state()
    real = session["devices"]["real"]
    trackers = {key: int(device_id) for key, device_id in real.items()}
    prefix = api.PREFIX[-12:]
    anna_tracker, ben_tracker = trackers[prefix + "-anna"], trackers[prefix + "-ben2"]
    anna = state.member_for_tracker(anna_tracker)
    assert anna is not None and anna.role == "player" and anna.number == 1, anna
    mirrors = {key: api.request("GET", f"/devices?id={device_id}")[0]["uniqueId"]
               for key, device_id in session["devices"]["mirroring"].items()}
    ben_view = accounts[("player", 2)]
    hunter_view = accounts[("hunter", 1)]

    def member_of(tracker_id):
        member = client.load_game_state().member_for_tracker(tracker_id)
        return member.id if member is not None else None

    # Catch and release through the catch service's typed operations.
    transition = CatchService().transition([], [anna_tracker], trackers.values())
    client.deliver_effects(operations=transition.operations(member_of, actor="Audit operator"))
    overview = api.request("GET", "/game-status", credentials=ben_view)["status"]["participantOverview"]
    assert next(item for item in overview if item["memberId"] == anna.id)["caught"] is True, overview
    release = CatchService().transition([anna_tracker], [], trackers.values())
    client.deliver_effects(operations=release.operations(member_of))
    overview = api.request("GET", "/game-status", credentials=ben_view)["status"]["participantOverview"]
    assert next(item for item in overview if item["memberId"] == anna.id)["caught"] is False, overview

    # A Live Ping: a live upload the server runs; its first delivered sample is ping 1 of a new run (a pool
    # joker). The Tool only starts and stops it, with the intents its live window builds.
    from traccar_game_manager.application.tracking.live_uploads import live_upload_ended, live_upload_started
    from traccar_game_manager.domain.game.execution_events import describe
    requests.change_jokers(None, "speedhunt", 2, "audit")
    after = connection.api.events_after(session_id, None)
    last_event = after[-1]["id"] if after else None
    started = client.deliver_effects(operations=[live_upload_started(
        "speedhuntPing", member_id=anna.id, audiences=("hunters",), interval_seconds=2,
        deadline=datetime.now(timezone.utc) + timedelta(minutes=2))])
    upload = next(item for item in started["operations"] if item["operation"] == "liveUploadStarted")["id"]
    hunters_mirror = mirrors["player_1_to_hunters"]
    anna_unique = api.request("GET", f"/devices?id={anna_tracker}")[0]["uniqueId"]
    api.upload(anna_unique, 47.37, 8.54)
    deadline = time.time() + 30
    target = {}
    while time.time() < deadline:
        target = next(item for item in api.request("GET", "/game-status", credentials=hunter_view)["status"][
            "participants"] if item["memberId"] == anna.id)
        if target["speedhunt"]["pings"] == 1:
            break
        time.sleep(1)
    assert target["speedhunt"]["running"] and target["speedhunt"]["live"] and target["speedhunt"]["pings"] == 1, target
    shared = api.request("GET", f"/devices?uniqueId={hunters_mirror}")[0]
    assert api.request("GET", f"/positions?id={shared['positionId']}")[0]["latitude"] == 47.37, shared
    zone = ZoneInfo(session.get("local_tz") or "UTC")
    lines = [describe(item, time_zone=zone).strip() for item in connection.api.events_after(session_id, last_event)
             if item["category"] == "live"]
    assert lines[0].startswith("Live Ping started for"), lines
    assert any(line.startswith("Ping 1") and "(Live Ping to hunters" in line for line in lines), lines
    for credentials in (ben_view, accounts[("player", 1)]):
        speedhunt = api.request("GET", "/game-status", credentials=credentials)["status"]["speedhunt"]
        assert speedhunt["active"] and speedhunt["pingCount"] == 1, speedhunt
    client.deliver_effects(operations=[live_upload_ended(upload, "stopped"),
                                       {"type": "runEnded", "targetMemberId": anna.id, "outcome": "terminated",
                                        "reason": "audit"}])
    history = api.request("GET", "/game-status/speedhunt-history", credentials=hunter_view)
    assert history["runs"][0]["pings"][0]["mode"] == "live", history

    # Manual live tracking: hunters see the share with the operator's reason; players never do.
    manual = client.deliver_effects(operations=[live_upload_started(
        "manual", member_id=anna.id, audiences=("hunters",), interval_seconds=15,
        deadline=datetime.now(timezone.utc) + timedelta(minutes=5), reason="Public transport")])
    manual_id = next(item for item in manual["operations"] if item["operation"] == "liveUploadStarted")["id"]
    shares = api.request("GET", "/game-status", credentials=hunter_view)["status"]["liveShares"]
    assert [(item["memberId"], item["reason"]) for item in shares] == [(anna.id, "Public transport")], shares
    assert "Public transport" not in json.dumps(api.request("GET", "/game-status", credentials=ben_view))
    client.deliver_effects(operations=[live_upload_ended(manual_id, "stopped")])

    # A one-off hunter relay is the Tool's relay intent: the server reads Hugo's tracker and writes his position to
    # Anna's per-hunter mirror, using her joker in the same transaction (the Tool sends no position).
    from traccar_game_manager.application.game_actions.relay import relay_intent
    from traccar_game_manager.application.tracking.pings import ping_outcomes
    requests.change_jokers(anna.id, "hunterPositions", 1, "audit")
    relay_key = next(key for key in mirrors if key.startswith("hunter_1_to_player_1"))
    hugo = trackers[prefix + "-hugo"]
    api.upload(api.request("GET", f"/devices?id={hugo}")[0]["uniqueId"], 47.2, 8.4)
    deadline = time.monotonic() + 15
    while (api.request("GET", f"/positions?deviceId={hugo}") or [{}])[-1].get("latitude") != 47.2:
        assert time.monotonic() < deadline, "Hugo's position was not stored"
        time.sleep(0.2)
    relayed = ping_outcomes(client.deliver_effects(pings=[relay_intent(anna.id)]))[0]
    assert relayed.delivered == ("hunters",) and relayed.jokers[0]["overAllocated"] is False, relayed
    shown = api.request("GET", f"/devices?uniqueId={mirrors[relay_key]}")[0]
    assert api.request("GET", f"/positions?id={shown['positionId']}")[0]["latitude"] == 47.2, shown
    own = api.request("GET", "/game-jokers", credentials=accounts[("player", 1)])
    assert next(item for item in own["inventories"] if item["type"] == "hunterPositions")["used"] == 1

    # A server restart ends the lease, not the game: the Tool loses control, re-acquires it with a new epoch
    # (nobody acquired control meanwhile), and the game state (running; catch history, the run with its live
    # ping, live uploads, jokers) is unchanged.
    before = api.request("GET", f"/game-sessions/{session_id}/snapshot")["state"]
    epoch = connection.controller.snapshot().epoch
    api.restart_server()
    connection.controller.tick()
    try:
        client.deliver_effects(operations=[])
        lost = False
    except Exception:
        lost = True
    assert connection.api.game_state(session_id)["state"] == "running"
    resumed = connection.controller.tick()
    if not resumed.controlling:
        resumed = connection.controller.tick()
    assert resumed.controlling and resumed.epoch == epoch + 1 and resumed.resumed >= 1, resumed
    api.CONTROLLER["epoch"] = resumed.epoch
    after = api.request("GET", f"/game-sessions/{session_id}/snapshot")["state"]
    for key in ("catches", "runs", "liveUploads", "breaks", "statistics"):
        assert before[key] == after[key], (key, before[key], after[key])
    assert len(before["catches"]) == 1 and before["catches"][0].get("releasedAt"), before["catches"]
    reloaded = client.load_game_state()
    assert reloaded.member_for_tracker(anna_tracker).id == anna.id

    # A controller handover: another Tool takes over; this Tool's writes are refused until it takes back control.
    other = connect_to_server(profile, api.PASSWORD, transport_factory=RequestsTransport,
                              preferences=JsonSessionPreferences(Path(tmp) / "other-prefs.json"))
    try:
        assert not other.controller.snapshot().controlling
        taken = other.controller.take_over()
        assert taken.controlling and taken.epoch == resumed.epoch + 1, taken
        refused = False
        try:
            client.deliver_effects(operations=[{"type": "catch", "memberId": anna.id}])
        except GameControllerLockedError:
            refused = True
        except Exception as error:  # noqa: BLE001 - the effects adapter may wrap the refusal
            refused = "423" in str(error) or "control" in str(error).lower()
        assert refused, "the previous holder's delivery must be refused after a takeover"
        assert next(item for item in api.request("GET", "/game-status", credentials=ben_view)["status"][
            "participantOverview"] if item["memberId"] == anna.id)["caught"] is False
        connection.controller.tick()
        back = connection.controller.take_over()
        assert back.controlling and back.epoch == taken.epoch + 1, back
        api.CONTROLLER["epoch"] = back.epoch
        assert connection.api.game_state(session_id)["state"] == "running"
    finally:
        other.close(5)
    print("Tool game flow passed: catch and release by member, a Live Ping the server runs (started with the "
          "Tool's intent, ping 1 delivered by the server's first sample of a real position, starting a run with a "
          "pool joker, its live events in the upload log's wording) and its history, manual live tracking with the "
          "reason for hunters only, a "
          "hunter relay using the participant's joker, a server restart (control lost%s, re-acquired "
          "automatically with a new epoch, game still running, game state unchanged), and a controller handover "
          "with the previous holder refused while the game keeps running."
          % (" and writes refused" if lost else ""))



def cleanup_session(connection, clients, session, name):
    """The Tool's own whole-session cleanup (accounts, mirrors, groups, trackers), then its saved data."""
    from traccar_game_manager.app.maintenance import create_session_deletion_service
    progress = Progress()

    def service():
        # A new service per action, as the cleanup tab does: each one loads the current session.
        return create_session_deletion_service(clients.from_session(session), name, progress.write,
                                               session_repository=connection.sessions)

    try:
        service().delete_whole_session()
        assert connection.jobs.flush(30)
        service().delete_saved_session_data()
    except Exception:
        print("CLEANUP PROGRESS", len(progress.lines),
              "\n".join(line for line in progress.lines if "assword" not in line)[-3000:])
        raise
    session_id = api.GAME_SESSION_IDS.pop() if api.GAME_SESSION_IDS else None
    leftovers = [device["uniqueId"] for device in api.request("GET", "/devices")
                 if api.PREFIX[-12:] in device["uniqueId"] or api.PREFIX in device["name"]]
    assert not leftovers, ("The Tool's cleanup left devices", leftovers, progress.lines[-20:])
    groups = [group["name"] for group in api.request("GET", "/groups") if api.PREFIX in group["name"]]
    assert not groups, ("The Tool's cleanup left groups", groups)
    # Only fixture accounts are inspected (their logins carry the audit prefix); nothing else is listed.
    users = [user["id"] for user in api.request("GET", "/users")
             if user["id"] != api.ADMIN_ID[0] and api.PREFIX[-12:] in (user.get("email") or "")]
    assert not users, ("The Tool's cleanup left fixture accounts", users)
    if session_id is not None:
        api.request("GET", f"/game-sessions/{session_id}", expected=404)
    print("Tool whole-session cleanup passed: generated accounts, mirrors, groups and trackers removed, then the "
          "saved session.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tool-source", required=True, help="Traccar Tool src directory")
    main(parser.parse_args().tool_source)
