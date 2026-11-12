"""Headless Traccar Tool end-to-end audit against a model-2 server; creates and removes only its own fixtures.

Run against the full Docker profile with Traccar Tool's virtual environment:

    cd traccar/scripts && ../../Traccar-Tool/venv_traccar/bin/python tool_end_to_end_integration.py \\
        --tool-source ../../Traccar-Tool/src

It drives the Tool's own composition, not HTTP calls of its own: the server connection with the controller lease,
the session repository, device setup with member planning and the device-list import, the Traccar client with the
session's effects and game state, and the game services that need no window. See ``game_notification_integration``
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
        resources = api.request("GET", f"/game-sessions/{session_id}/resources")
        mirror_ids = {row["deviceId"] for row in resources["mirrors"] if row["state"] == "active"}
        assert mirror_ids and all(devices_by_id.get("attributes", {}).get("traccarToolMirror")
                                  for devices_by_id in api.request("GET", "/devices")
                                  if devices_by_id["id"] in mirror_ids)
        assert {row["purpose"] for row in resources["groups"]} >= {"player", "supporter", "hunter"}, resources
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
              "trackers with role markers, mirror and group rows, mirror-only hunter access, an idempotent "
              "second run, and a device-list import that rebinds a replaced tracker to the same member.")
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


def game_flow(connection, clients, session, name, session_id, accounts, profile, tmp):
    """Catches, a Live Ping, live tracking, a hunter relay, a server restart and a controller handover through the
    Tool's client, effects, game state and controller lease."""
    from traccar_game_manager.app.connection import connect_to_server
    from traccar_game_manager.application.game_actions.catches import CatchService
    from traccar_game_manager.infrastructure.persistence.session_preferences import JsonSessionPreferences
    from traccar_game_manager.infrastructure.traccar.game_sessions import GameControllerLockedError
    from traccar_game_manager.infrastructure.traccar.transport import RequestsTransport

    connection.controller.set_executing(session_id)
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

    # A Live Ping: a live upload whose first delivered sample is ping 1 of a new run (a pool joker).
    requests.change_jokers(None, "speedhunt", 2, "audit")
    deadline = api.iso(minutes=2)
    started = client.deliver_effects(operations=[{
        "type": "liveUploadStarted", "purpose": "speedhuntPing", "sourceMemberId": anna.id, "toHunters": True,
        "toPlayers": False, "toSupporters": False, "intervalSeconds": 10, "deadline": deadline}])
    upload = next(item for item in started["operations"] if item["operation"] == "liveUploadStarted")["id"]
    hunters_mirror = mirrors["player_1_to_hunters"]
    first = client.upload_positions([{"uniqueId": hunters_mirror, "latitude": 47.37, "longitude": 8.54}],
                                    operations=[{"type": "pingDelivered", "targetMemberId": anna.id, "number": 1,
                                                 "mode": "live", "source": "real", "toHunters": True,
                                                 "toPlayers": False, "toSupporters": False,
                                                 "liveUploadId": upload}])
    assert first["operations"][0]["runId"], first
    client.upload_positions([{"uniqueId": hunters_mirror, "latitude": 47.371, "longitude": 8.541}],
                            operations=[{"type": "liveUploadSample", "liveUploadId": upload}])
    target = next(item for item in api.request("GET", "/game-status", credentials=hunter_view)["status"][
        "participants"] if item["memberId"] == anna.id)
    assert target["speedhunt"]["running"] and target["speedhunt"]["live"] and target["speedhunt"]["pings"] == 1, target
    for credentials in (ben_view, accounts[("player", 1)]):
        speedhunt = api.request("GET", "/game-status", credentials=credentials)["status"]["speedhunt"]
        assert speedhunt["active"] and speedhunt["pingCount"] == 1, speedhunt
    client.deliver_effects(operations=[{"type": "liveUploadEnded", "liveUploadId": upload, "endReason": "stopped"},
                                       {"type": "runEnded", "targetMemberId": anna.id, "outcome": "terminated",
                                        "reason": "audit"}])
    history = api.request("GET", "/game-status/speedhunt-history", credentials=hunter_view)
    assert history["runs"][0]["pings"][0]["mode"] == "live", history

    # Manual live tracking: hunters see the share with the operator's reason; players never do.
    manual = client.deliver_effects(operations=[{
        "type": "liveUploadStarted", "purpose": "manual", "sourceMemberId": anna.id, "toHunters": True,
        "toPlayers": False, "toSupporters": False, "intervalSeconds": 15, "deadline": api.iso(minutes=5),
        "reason": "Public transport"}])
    manual_id = next(item for item in manual["operations"] if item["operation"] == "liveUploadStarted")["id"]
    shares = api.request("GET", "/game-status", credentials=hunter_view)["status"]["liveShares"]
    assert [(item["memberId"], item["reason"]) for item in shares] == [(anna.id, "Public transport")], shares
    assert "Public transport" not in json.dumps(api.request("GET", "/game-status", credentials=ben_view))
    client.deliver_effects(operations=[{"type": "liveUploadEnded", "liveUploadId": manual_id,
                                        "endReason": "stopped"}])

    # A hunter relay: the hunters' positions reach Anna through her hunter mirror, using her joker.
    requests.change_jokers(anna.id, "hunterPositions", 1, "audit")
    relay_key = next(key for key in mirrors if key.startswith("hunter_1_to_player_1"))
    relayed = client.upload_positions([{"uniqueId": mirrors[relay_key], "latitude": 47.2, "longitude": 8.4}],
                                      jokers=[{"memberId": anna.id, "type": "hunterPositions",
                                               "overAllocate": False, "detail": "Audit relay"}])
    assert relayed["jokers"][0]["overAllocated"] is False, relayed
    own = api.request("GET", "/game-jokers", credentials=accounts[("player", 1)])
    assert next(item for item in own["inventories"] if item["type"] == "hunterPositions")["used"] == 1

    # A server restart ends the lease: the Tool loses control, resumes with a new epoch, and the game state
    # (catch history, the run with its live ping, live uploads, jokers) is unchanged.
    before = api.request("GET", f"/game-sessions/{session_id}/snapshot")["state"]
    epoch = connection.controller.snapshot().epoch
    api.restart_server()
    connection.controller.tick()
    try:
        client.deliver_effects(operations=[])
        lost = False
    except Exception:
        lost = True
    resumed = connection.controller.resume()
    assert resumed.controlling and resumed.epoch == epoch + 1, resumed
    api.CONTROLLER["epoch"] = resumed.epoch
    connection.controller.set_executing(session_id)
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
        connection.controller.set_executing(session_id)
    finally:
        other.close(5)
    print("Tool game flow passed: catch and release by member, a Live Ping as a live upload starting a run with "
          "a pool joker, live samples and history, manual live tracking with the reason for hunters only, a "
          "hunter relay using the participant's joker, a server restart (control lost%s, resumed with a new "
          "epoch, game state unchanged), and a controller handover with the previous holder refused."
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
