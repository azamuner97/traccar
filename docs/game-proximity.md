# Participant proximity API

Deploy the additive `changelog-game-proximity.xml` migration with the server,
then the web app and Tool. Existing permanent permissions are not rewritten.

Managers with write access to a managed hidden session device use
`GET /api/game-proximity/session/{id}` and `PUT` on the same path. The snapshot
contains the current notification `generation`, increasing positive `revision`,
reciprocal `grants`, and `circles`. Initialize the notification generation through
the existing authenticated notification-session endpoint first.

A grant contains `episodeId`, `recipientUserId`, the recipient's assigned
`sourceDeviceId`, the peer's `targetDeviceId`, and UTC `expiresAt`. Both reciprocal
grants must share their episode and expiry. Sources must belong to unique enabled
player/supporter accounts in that session and have permanent owner access to real
devices. Expiring grants contribute to device reads and websocket positions, but
remain separate from ordinary direct/group permission inventories.
Temporary visibility cannot authorize device edits, position deletion, commands,
permission changes, or share tokens, even when an account has ordinary write
privileges.

A circle contains `id`, `latitude`, `longitude`, `diameterMeters`, `playerCount`,
`supporterCount`, UTC `expiresAt`, and internal `participantDeviceIds`. Only
generated hunters/spectators with session access can read
`GET /api/game-proximity/session/{id}/circles`; that response omits member IDs.
Participant accounts can read their own temporary device IDs and expiry times
at `/access`. Devices already visible through permanent direct/group permissions
are omitted so local browser expiry cannot remove independently authorized access.

The session device must be registered with a central game session
(`PUT /api/game-sessions/{id}/resources` with its `sessionDeviceId`); writes for an
unregistered session device return 409. Proximity rows carry the game-session ID
and are still deleted with the session device. Writes also need the current game
controller epoch in `X-Game-Controller-Epoch` (423 otherwise); see
"Game controller and effects" in `CUSTOMIZATIONS.md`.

Identical revisions are idempotent; obsolete/conflicting revisions and generations
return 409. Invalid snapshots return 400 and identity/scope violations return 403.
Lease expiry is checked by reads and device, position, event, and log delivery and reconciled every second,
including after startup. Account, device, and permanent-permission changes also
reconcile invalid identities. Scoped websocket invalidations refresh circles and
device inventories; browsers clear expired temporary markers even while offline.
Invalidated or deleted session devices revoke their remaining state without
interrupting cleanup for other sessions. Browsers retain known lease deadlines
until a successful inventory refresh so delayed packets cannot restore expired
peer markers or positions.

Run the focused MySQL audit with Python 3.14 and websocket-client:
`../Traccar-Tool/venv_traccar/bin/python scripts/game_proximity_integration.py --tool-source ../Traccar-Tool/src`.
It creates and removes isolated fixtures, restarts only the local server, preserves
Docker volumes, and never sends native pushes. With `--tool-source`, the audit
drives the actual monitoring service, live-window notification handler, and ordered
HTTP worker. It verifies the personal sharing-start notice in both players'
history, recipient isolation, captured push calls, circle publication, and silent
refreshes. Add `--skip-restart` when auditing alongside an active development
session; this skips only the server-restart check. The existing notification audit
and desktop/mobile browser audits remain required alongside this check.

After updating Tool source, fully quit and reopen the Tool application. Rebuilding
Docker updates the server and web app; a running Python Tool retains its imported
monitoring code. To verify a sharing-start notice after restarting, separate the
participants and then begin a new close episode. Restored active episodes do not
replay notices that have already been marked as emitted.
