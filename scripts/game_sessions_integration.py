"""Local MySQL/HTTP audit of central game sessions with isolated fixtures.

Creates a temporary administrator and a temporary non-administrator, exercises
``/api/game-sessions`` directly and through the Tool's server repository
(``--tool-source``), and removes every fixture afterwards. Requires the full
Docker profile with ``TRACCAR_GAME_SESSIONS_CREDENTIAL_KEY`` set in ``.env``.
"""

import argparse
import hashlib
import secrets
import sys
import tempfile
from pathlib import Path

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
    assert api.request("GET", "/game-sessions/capabilities")["apiVersion"] == 1
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
    client = GameSessionApi(api.BASE, api.EMAIL, api.PASSWORD, transport=Transport())
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


def main(tool_source=None):
    api.wait_for_server()
    admin_id = create_user(api.PREFIX, api.EMAIL, api.PASSWORD, True)
    user_email = api.PREFIX + "-user@example.invalid"
    user_password = secrets.token_hex(24)
    user_id = create_user(api.PREFIX + "-user", user_email, user_password, False)
    try:
        audit_http((user_email, user_password), [])
        if tool_source:
            audit_tool(tool_source)
        print("MySQL/HTTP game-session audit passed: admin-only access, revision conflicts and idempotent retries, "
              "credential encryption at rest, stable timetable entry IDs, notification history after device "
              "deletion, and cascading deletion of saved data"
              + (", plus the Tool's repository round trip." if tool_source else "."))
    finally:
        api.delete_game_sessions()
        api.sql(f"DELETE FROM tc_users WHERE id={user_id} AND email=" + api.literal(user_email) + ";")
        api.sql(f"DELETE FROM tc_users WHERE id={admin_id} AND email=" + api.literal(api.EMAIL) + ";")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tool-source", help="Traccar Tool src directory for the repository round trip")
    main(parser.parse_args().tool_source)
