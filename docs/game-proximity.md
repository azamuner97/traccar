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

A circle contains `id`, `latitude`, `longitude`, `diameterMeters`, `playerCount`,
`supporterCount`, UTC `expiresAt`, and internal `participantDeviceIds`. Only
generated hunters/spectators with session access can read
`GET /api/game-proximity/session/{id}/circles`; that response omits member IDs.
Participant accounts can read their own temporary device IDs and expiry times
at `/access`. Devices already visible through permanent direct/group permissions
are omitted so local browser expiry cannot remove independently authorized access.

Identical revisions are idempotent; obsolete/conflicting revisions and generations
return 409. Invalid snapshots return 400 and identity/scope violations return 403.
Lease expiry is checked by reads and position delivery and reconciled every second,
including after startup. Account, device, and permanent-permission changes also
reconcile invalid identities. Scoped websocket invalidations refresh circles and
device inventories; browsers clear expired temporary markers even while offline.

Run the focused MySQL audit with Python 3.14 and websocket-client:
`../Traccar-Tool/venv_traccar/bin/python scripts/game_proximity_integration.py --tool-source ../Traccar-Tool/src`.
It creates and removes isolated fixtures, restarts only the local server, preserves
Docker volumes, and never sends native pushes. The existing notification audit
and desktop/mobile browser audits remain required alongside this check.
