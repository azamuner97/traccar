"""Outage drill of game execution (Claude outputs/game-execution-plan.md, phases 0, 2 and 3), automated with the
Tool's code.

A Tool (the Tool's own ``ControllerLease``, ``GameSessionApi`` and ``GameExecutionGateway``) controls a running game
with an open request, a hunter circle, a speedhunt Live Ping (the speedhunt's only ping, ending 32 s into the gap),
a live hunter relay (both started with the Tool's ``liveUploadStarted`` intents) and a break of Ben planned for
22 s into the gap. Its network then drops for 60 seconds: it stops renewing, so the lease expires. During the gap a
member submits another request, an administrator writes a test event, and the participants' real trackers send
positions. After the gap the Tool reconnects and is expected to

- find the game still running,
- re-acquire control on its own (``resumeEpoch``, no takeover),
- find every open request untouched (nothing expired or ended),
- find the hunter circle still published (120 s lifetime),
- find that the server kept running the Live Ping and the relay without it: the Live Ping counted ping 1 with
  its first sample of a real position and completed the speedhunt at its deadline, the hunters' mirror and the
  relay mirror kept receiving positions, the relay used its joker once,
- find that the server ran Ben's planned break: it waited while the Live Ping ran, started once it ended (after
  the lease had expired) with its full 15 s and ended at its deadline, its joker used,
- show the test event and the live, relay and break events exactly once in its upload log, from the catch-up by
  event ID and a duplicate socket push.

It also checks that ``gameEvents`` reach administrators only. Run against the full Docker profile with Python that
has ``websocket-client`` and ``requests``, for example Traccar Tool's virtual environment::

    python scripts/game_outage_drill.py --tool-source ../Traccar-Tool/src

Creates and removes only its own fixtures; never resets volumes. ``--gap`` shortens the drop (at least 50 s, so the
30 s lease expires and the planned break ends within the gap).
"""

import argparse
from datetime import datetime, timedelta, timezone
import json
import sys
import time
from types import SimpleNamespace
from zoneinfo import ZoneInfo

import game_notification_integration as api


def game_events(socket, seconds):
    """The execution events a socket received within the given time."""
    events = []
    deadline = time.monotonic() + seconds
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            return events
        socket.settimeout(remaining)
        try:
            message = socket.recv()
        except Exception:
            return events
        try:
            data = json.loads(message)
        except (TypeError, ValueError):
            continue
        events.extend(data.get("gameEvents") or ())


def main(tool_source, gap):
    sys.path.insert(0, tool_source)
    from traccar_game_manager.infrastructure.traccar.controller_lease import CONTROLLING, LOST, ControllerLease
    from traccar_game_manager.infrastructure.traccar.game_execution import GameExecutionGateway
    from traccar_game_manager.infrastructure.traccar.game_sessions import GameSessionApi
    from traccar_game_manager.infrastructure.traccar.transport import RequestsTransport
    from traccar_game_manager.application.tracking.live_uploads import live_upload_ended, live_upload_started
    from traccar_game_manager.ui.live_session.game_state import LiveGameStateController

    api.wait_for_server()
    admin_id = api.create_admin()
    fixtures = api.Fixtures()
    tool = GameSessionApi(api.BASE, api.EMAIL, api.PASSWORD, transport=RequestsTransport())
    lease = ControllerLease(tool, instance_id="drill-" + api.PREFIX[-24:], label="Outage drill", start=False)
    tool.epoch_provider = lease.epoch_for_requests
    session_id = None
    try:
        running = api.sql("SELECT gamesessionid FROM tc_game_session_state WHERE runningkey = 1")
        if running:
            raise RuntimeError("A game is running on the development server; stop it in Tool before the drill.")
        snapshot = lease.acquire()
        if snapshot.state != CONTROLLING:
            raise RuntimeError("Another Tool controls the development server; release control before the drill.")
        api.CONTROLLER["epoch"] = snapshot.epoch
        session_id = api.create_game_session("drill", {"pings_per_speedhunt": 1, "break_duration_minutes": 0.25})
        members, credentials, trackers = {}, {}, {}
        for key, role in (("anna", "player"), ("ben", "player"), ("hunter", "hunter")):
            members[key] = api.plan(session_id, role, key)
            user, credentials[key] = fixtures.account("d-" + api.PREFIX[-16:] + "-" + key, readonly=True)
            api.bind_account(session_id, members[key]["id"], user["id"])
            trackers[key] = fixtures.device(api.PREFIX + "-" + key)
            members[key] = api.bind_tracker(session_id, members[key]["id"], trackers[key]["id"])
        # Anna's hunters' mirror and the hunter's mirror relayed to Ben.
        to_hunters = fixtures.device(api.PREFIX + "-anna-to-hunters", attributes={"traccarToolMirror": True})
        api.add_mirror(session_id, to_hunters["id"], members["anna"]["id"], destination_role="hunter")
        to_ben = fixtures.device(api.PREFIX + "-hunter-to-ben", attributes={"traccarToolMirror": True})
        api.add_mirror(session_id, to_ben["id"], members["hunter"]["id"], destination_member_id=members["ben"]["id"])
        for member_id, joker in ((None, "speedhunt"), (members["ben"]["id"], "hunterPositions"),
                                 (members["ben"]["id"], "break")):
            api.request("POST", f"/game-sessions/{session_id}/jokers/change", {
                "memberId": member_id, "type": joker, "quantity": 1, "reason": "drill"})

        gateway = GameExecutionGateway(tool, session_id)
        assert gateway.start()["state"] == "running"
        admin_socket = fixtures.socket((api.EMAIL, api.PASSWORD))
        member_socket = fixtures.socket(credentials["hunter"])

        def revision(key):
            status = api.request("GET", "/game-status", credentials=credentials["hunter"])
            return next(item for item in status["status"]["participants"]
                        if item["memberId"] == members[key]["id"])["speedhunt"]["revision"]

        waiting = api.request("POST", "/game-requests", {"type": "speedhuntTerminate",
                                                         "targetMemberId": members["anna"]["id"],
                                                         "baseRevision": revision("anna")},
                              credentials=credentials["hunter"])
        assert waiting["status"] == "pending", waiting
        circle = {"expectedRevision": 0, "grants": [], "circles": [{
            "id": "drill-circle", "latitude": 47, "longitude": 8, "diameterMeters": 500,
            "expiresAt": api.iso(seconds=120), "memberIds": [members["anna"]["id"], members["ben"]["id"]]}]}
        api.request("PUT", f"/game-proximity/session/{session_id}", circle)
        before = gateway.events_after()
        last_shown = before[-1]["id"]
        # A Live Ping on Anna and a live relay to Ben, started with the Tool's intents; the server runs both. Ben's
        # break is planned for 22 s into the gap: it waits while the Live Ping runs and starts once it ended.
        start = datetime.now(timezone.utc)
        planned = api.request("POST", f"/game-sessions/{session_id}/jokers/breaks", {
            "memberId": members["ben"]["id"], "startsAt": api.iso(start, seconds=22)})
        break_id = planned["plannedBreak"]["breakId"]
        started = api.effects(session_id, operations=[
            live_upload_started("speedhuntPing", member_id=members["anna"]["id"], audiences=("hunters",),
                                interval_seconds=3, deadline=start + timedelta(seconds=32)),
            live_upload_started("hunterRelay", member_id=members["ben"]["id"], interval_seconds=3, use_joker=True)])
        live_id, relay_id = [item["id"] for item in started["operations"]]
        game_events(admin_socket, 0.5)
        game_events(member_socket, 0.5)

        # The drop: no renewal from now on. The lease expires after 30 s; the game keeps running.
        expires = api.request("GET", "/game-controller")["expiresAt"]
        dropped = time.monotonic()
        gap_started = datetime.now(timezone.utc)
        api.CONTROLLER["epoch"] = None
        api.upload(trackers["anna"]["uniqueId"], 47.01, 8.01)
        api.upload(trackers["hunter"]["uniqueId"], 47.11, 8.11)
        time.sleep(15)
        api.upload(trackers["anna"]["uniqueId"], 47.02, 8.02)
        time.sleep(17)
        assert api.request("GET", "/game-controller")["active"] is False
        assert api.request("GET", f"/game-sessions/{session_id}/state")["state"] == "running"
        second = api.request("POST", "/game-requests", {"type": "speedhuntTerminate",
                                                        "targetMemberId": members["ben"]["id"],
                                                        "baseRevision": revision("ben")},
                             credentials=credentials["hunter"])
        test = api.request("POST", f"/game-sessions/{session_id}/events/test", {"message": "Outage drill"},
                           epoch=False)
        pushed = game_events(admin_socket, 3)
        assert test["id"] in [item["id"] for item in pushed], pushed
        assert game_events(member_socket, 1) == [], "members never receive execution events"
        time.sleep(max(0.0, gap - (time.monotonic() - dropped)))

        # Reconnect: the heartbeat finds the lease gone and re-acquires it without a takeover.
        lost = lease.tick()
        assert lost.state == LOST and lost.resume_epoch == snapshot.epoch, lost
        resumed = lease.tick()
        assert resumed.state == CONTROLLING and resumed.epoch == snapshot.epoch + 1 and resumed.resumed == 1, \
            resumed
        api.CONTROLLER["epoch"] = resumed.epoch
        history = api.sql("SELECT event FROM tc_game_controller_history ORDER BY id DESC LIMIT 5").split()
        assert "takeover" not in history[:2], history

        assert gateway.state()["state"] == "running"
        open_requests = {item["id"]: item["status"] for item in
                         api.request("GET", f"/game-sessions/{session_id}/requests")}
        assert open_requests == {waiting["id"]: "pending", second["id"]: "pending"}, open_requests
        circles = api.request("GET", f"/game-proximity/session/{session_id}")["circles"]
        assert [item["id"] for item in circles] == ["drill-circle"], circles

        # The server ran the Live Ping and the relay through the gap.
        def positions(device_id, since):
            return api.request("GET", f"/positions?deviceId={device_id}&from={api.iso(since)}"
                                      f"&to={api.iso(minutes=1)}")
        shared = positions(to_hunters["id"], gap_started)
        assert [item["latitude"] for item in shared][:1] == [47.01] and shared[-1]["latitude"] == 47.02, shared
        assert len(shared) >= 5, len(shared)
        # A relayed position keeps the hunter's own fix time (whole seconds), sent at the start of the gap.
        relayed = positions(to_ben["id"], gap_started - timedelta(seconds=5))
        assert relayed and all(item["latitude"] == 47.11 for item in relayed), relayed
        status = api.request("GET", "/game-status", credentials=credentials["hunter"])["status"]
        anna = next(item for item in status["participants"] if item["memberId"] == members["anna"]["id"])
        assert not anna["speedhunt"]["running"] and not anna["speedhunt"]["live"], anna
        ben_jokers = api.request("GET", "/game-jokers", credentials=credentials["ben"])["inventories"]
        assert next(item for item in ben_jokers if item["type"] == "hunterPositions")["used"] == 1, ben_jokers
        assert next(item for item in ben_jokers if item["type"] == "break")["used"] == 1, ben_jokers
        assert not api.request("GET", "/game-status", credentials=credentials["ben"])["status"]["break"]["active"]
        # The server ended the Live Ping at its deadline (completing the speedhunt) and ran Ben's break.
        events = gateway.events_after(last_shown)
        live_end = next(item for item in events if item["data"].get("liveUploadId") == live_id
                        and item["data"]["type"] == "ended")
        assert live_end["data"]["endReason"] == "deadline" and live_end["data"].get("runCompleted"), live_end
        breaks = [item for item in events if item["category"] == "break"]
        assert [item["data"]["type"] for item in breaks] == ["waiting", "started", "ended"], breaks
        waited, began, ended = breaks
        assert waited["data"]["reason"] == "speedhunt" and began["data"]["waited"], breaks
        assert began["occurredAt"] >= live_end["occurredAt"], (began, live_end)
        began_at = datetime.fromisoformat(began["occurredAt"].replace("Z", "+00:00"))
        assert began_at > datetime.fromisoformat(expires.replace("Z", "+00:00")), (began, expires)
        assert ended["data"]["deadline"] == began["data"]["deadline"], ended
        api.effects(session_id, operations=[live_upload_ended(relay_id, "stopped")])

        # The upload log: the catch-up by ID and a duplicate socket push show the test event once.
        shown = []
        owner = SimpleNamespace(
            local_tz=ZoneInfo("Europe/Zurich"), root=None,
            controller=SimpleNamespace(controller_snapshot=lease.snapshot),
            coordinator=SimpleNamespace(show_server_log=lambda category, line, moment: None),
            _append_main_log_line=lambda category, text, timestamp=None: shown.append(text),
        )
        window = LiveGameStateController(owner)
        window.attach(gateway, gateway.state(), last_event_id=last_shown)
        window.on_events([], reconnected=True)
        window.on_events(pushed, reconnected=False)
        test_line = "Server test event: Outage drill (sent by " + test["data"]["sentBy"] + ")."
        assert shown.count(test_line) == 1, shown
        assert sum(line.startswith("Ping 1 of 1 at ") and "(Live Ping to hunters" in line for line in shown) == 1, \
            shown
        assert sum(line.startswith("Live ping of anna stopped at ") and line.endswith("Speedhunt is over.")
                   for line in shown) == 1, shown
        assert sum(line.startswith("The planned break of ben for ") and line.endswith(
            " waits: a speedhunt is running.") for line in shown) == 1, shown
        assert sum(line.startswith("ben is on a planned break until ") and "(0.2 min)" in line
                   for line in shown) == 1, shown
        assert shown.count("The break of ben ended (time is up).") == 1, shown
        assert sum(line.startswith("\nHunter live relay enabled for ben") for line in shown) == 1, shown
        assert sum("One Get hunter positions joker of ben used." in line for line in shown) == 1, shown
        assert len(shown) == len(set(shown)), shown
        print(f"Outage drill passed ({round(time.monotonic() - dropped)} s without the Tool): the game kept "
              "running, control was re-acquired automatically with a new epoch, both requests stayed open (one "
              "submitted during the gap), the hunter circle survived, the server kept running the Live Ping (ping 1 "
              f"counted from a real position sent during the gap, {len(shared)} samples to the hunters' mirror, the "
              "speedhunt completed at its deadline) and the live relay (its joker used once), Ben's planned break "
              "waited for the Live Ping, started after the lease had expired and ended at its deadline, and the "
              "test event and the live, relay and break events written during the gap appeared in the upload log "
              "exactly once; execution events reached administrators only.")
    finally:
        fixtures.close_sockets()
        if session_id is not None:
            try:
                if lease.snapshot().state == CONTROLLING:
                    api.CONTROLLER["epoch"] = lease.snapshot().epoch
                    tool.set_game_state(session_id, "stopped")
                else:
                    api.request("PUT", f"/game-sessions/{session_id}/state", {"state": "stopped"}, epoch=False)
            except Exception as error:  # noqa: BLE001 - cleanup continues
                print(f"Stopping the drill game failed: {error}")
        fixtures.remove()
        api.delete_game_sessions()
        lease.release()
        api.CONTROLLER["epoch"] = None
        api.delete_admin(admin_id)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--tool-source", required=True, help="Traccar Tool src directory")
    parser.add_argument("--gap", type=float, default=60.0, help="Seconds without the Tool (at least 50)")
    arguments = parser.parse_args()
    main(arguments.tool_source, max(50.0, arguments.gap))
