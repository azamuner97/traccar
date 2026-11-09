# Project customizations

This fork is based on Traccar 6.15.3 and pins the matching customized web app
through the `traccar-web` Git submodule. A server commit therefore identifies
the exact server and web source pair used for a build.

## Participant role markers

The Traccar Tool writes role-marker metadata into standard Traccar device
attributes for real and mirrored game participants:

```text
device.category = "person"
device.attributes.traccarToolRole = "player" | "supporter" | "hunter"
device.attributes.traccarToolRoleNumber = positive integer
device.attributes.traccarToolMirror = true    # mirrored devices only
```

The web app renders the role background and participant number on map markers
and in the device sidebar. The number is rendered as text, so it is not limited
to a fixed set of image assets. Missing or invalid role metadata uses the normal
Traccar category marker. Numeric device categories are not a compatibility
source for role numbers.

Traccar already persists arbitrary device attributes through its existing
device model and API. No Java model field, database migration, or dedicated
role endpoint is required for this feature. The Tool owns reconciliation of the
three managed values and preserves unrelated attributes.

## Participant-aware live visibility

The Tool creates an internal device marked with
`device.attributes.traccarToolGameState = true`, grants each generated game
account access to it, and publishes the complete current caught-participant
state through its latest position:

```text
position.attributes.traccarToolGameState = true
position.attributes.traccarToolGameStateVersion = 1
position.attributes.traccarToolCaughtParticipants = "player:1,supporter:2"
```

Generated player and supporter accounts carry
`traccarToolParticipantRole` and `traccarToolParticipantNumber` user
attributes. The web app combines those values with the device role metadata
and `traccarToolMirror: true` to remove the signed-in participant's outgoing
mirrors from the main live page while retaining the real device. For every
non-administrator account, a caught player or supporter and all matching
mirrors are removed from the live map, sidebar, counts, camera bounds, live
routes, and selected status card. Administrators bypass participant filtering,
but the internal state device remains hidden for every account.

Only positions belonging to a device independently marked as an internal
state device are trusted as state. Missing, malformed, or unsupported state
fails open so it cannot accidentally hide ordinary participants. Participant
identity comes exclusively from `traccarToolRoleNumber`; numeric categories
are not supported.

This filtering is live-page presentation, not an authorization boundary.
Traccar permissions remain authoritative, and replay, reports, exports, and
direct API access retain every ordinary device available to the signed-in
user. No Java endpoint or schema change is required. The contract assumes each
generated non-administrator account belongs to one game session; supporting a
single account across multiple simultaneous sessions would require adding a
session identifier to the state and participant identities.

## Speedhunt, player-caught and test alerts

The `tc_game_notifications` journal and authenticated `/api/game-notifications`
API are the single source for live alerts and full-session history. Each immutable
row contains a stable alert ID, session-device ID, generation, type, `session` or `user`
audience, UTC occurrence, IANA timezone, final timestamped message and server
creation time. A database sequence supplies stable cursors; a unique
session-device/alert-ID constraint prevents duplicate records. Alert IDs are
case-sensitive, including on MySQL's normally case-insensitive text collation.
The additive collation migration preserves existing rows. The new Liquibase
migration leaves previous migrations unchanged. Device deletion cascades to its
journal.

The hidden device's protected attributes `traccarToolNotificationGeneration` and
`traccarToolNotificationResetAt` track active history. Device-row locks serialize
initialization, insertion and reset. Generic device updates preserve these
attributes and commit their ordinary fields atomically. Writes require manager/admin authority and device access. Generated
users read only their eligible own session with a device permission. Administrators
may inspect history explicitly but never receive automatic game alerts.

API contract:

- `POST /api/game-notifications/session/{id}`: idempotent initialization.
- `POST /api/game-notifications`: `{sessionDeviceId,generation,alert}`. Identical
  reuse returns the existing record; conflicting IDs or stale generations return 409.
- `GET /api/game-notifications?sessionDeviceId=...&generation=...&after=...&limit=100`:
  version, active generation, reset timestamp, records, next cursor and has-more.
  The maximum page size is 500; cursor IDs are decimal strings.
- `POST /api/game-notifications/session/{id}/reset`:
  `{expectedGeneration,replacementGeneration}`. Retrying the same reset is safe.

These endpoints never send pushes. Successful inserts and resets emit an authorized
session-scoped websocket `game-notifications` invalidation. The web app fetches
records on invalidation, login, reconnect, foreground return and drawer opening.
Uploaded position content cannot create trusted notifications. No legacy position
payload parsing, recovery, historical import or local-cache migration remains.

The three session types—`speedhunt`, `playerCaught`, `notificationTest`—target generated
players, supporters, hunters and spectator; administrators and unrelated accounts
are excluded. The `playerCaught` type covers newly caught players and supporters,
using `<display name> was caught` for either role; unchanged saves and releases
do not alert, while re-catching a released participant does. The Tool captures
`[HH:mm:ss]` in the game timezone once before
enqueueing; archive and push receive identical final text. The ordered background
worker binds jobs to their generation, publishes caught visibility first, archives
each record, then makes the existing native relay request once per recipient.
Independent failures warn without gameplay rollback; explicit stale jobs do not
push. Identical already-archived submissions skip duplicate pushes while the
remaining batch continues. Ambiguous relay requests are not retried. No durable
push outbox is added, so an archive row is not proof that its push was attempted.

New Speedhunt pings archive two role variants at the same occurrence time:
`hunters` adds `Participant: <display name>.` to the timestamped message for hunters
and spectator; `nonHunters` preserves the anonymous message for players and supporters.
The wire audience identifiers remain stable.
Only Speedhunt supports these audiences. API pagination authorizes the viewer's
current role before returning either variant, and the web applies the same policy
to cached history/banners. Native relay recipients are checked against current
server roles and session markers; a name-bearing message is never broadcast as a
session alert. Each account receives one notification, with no new migration.

Version-2 personal types `geofenceExit`, `liveTrackingStarted`,
`liveTrackingStopped`, and `liveTrackingTargetsChanged` use `user` audience,
`recipientUserId`, and `sourceDeviceId`. Creation verifies the assigned generated
account, session, role, and tracker access. API history and socket invalidation
are private to that account. The live types extend existing validation without a
new migration. Live starts require the first positive successful upload count;
real stops are reported once, while temporary rebuilds stay silent.
Personal history additionally requires the account's current participant/hunter
role, matching assigned tracker and access to that tracker. Reassignment or revoked
tracker access cannot expose old private records through the API; session records
remain available. Browser role/assignment changes cancel obsolete requests and
re-read authorized history without replaying handled alerts.
Notification pages also include the server-authorized `personalSourceDeviceId`
(null when tracker access is absent). Browser synchronization uses it to remove
unauthorized personal cache entries and banners even when account markers remain
unchanged. Device/group permission changes invalidate the affected user's stream;
restoring access does not replay handled alarms. Receiving-group history does not
require this personal tracker grant.
The Tool's optional editable Reason dropdown includes `Usage of public transport.`;
reasons accompany that run's personal messages. Destination names come from the
configured real groups, not role labels or internal mirror groups. Geofence
automation changes the running service's actual routes and informs the affected
account on addition/removal without stopping manual tracking on re-entry.
Normal Tool exit drains pending delivery for at most 30 seconds with an Exit now
option; forced exit and native push delivery remain best-effort.
Queued personal alerts retain their original server connection. Partial location
batches preserve confirmed upload counts without retrying successful prefixes,
and resumed tracking synchronizes its effective routes and notification messages.
Warning/closing-widget failures cannot block tracking or final application exit.

Manual/geofence live pings also archive `livePingReceived`, `livePingEnded`, and
spectator-only `livePingTargetsChanged` records. `liveHunter`, `livePlayer`,
`liveSupporter`, and `liveSpectator` audiences bind existing recipient/source
fields to one receiving account's current role and session; no migration is
needed. Creation verifies a unique real player/supporter source owner, while
existing personal authorization is unchanged. The tracked account receives only
its existing personal alerts; administrators and unrelated accounts never receive
automatic recipient alerts.
The integer upload count now includes confirmed destination keys, including
partial successes. Starts wait for each destination's first success, removals and
real termination send stops, and re-added destinations begin a fresh episode.
Spectators get one run start/stop and one summary per confirmed destination change.
Refreshes and temporary rebuilds stay silent. Names, optional reason, geofence
context and stop causes are frozen for identical archive/push text. Native
recipient accounts are revalidated before delivery; failures do not stop tracking.
Explicit notification API authorization/validation rejections suppress native
push fallback. Network failures and server outages still allow independent push
delivery, while other eligible alerts in a rejected batch continue.

IndexedDB persists the full generation per origin, account and hidden session
device, including local read flags, handled IDs, deletion tombstones and clear
cutoffs. There is no 100-record or 24-hour retention limit. Storage denial falls
back to memory. Failed loading preserves valid cache. History hydration never
replays alarms; each complete sync can present only the newest previously unseen
record younger than 15 minutes. All older records are unread history only.

The 60-second dismissible top-center banner and immediate newest-alert replacement
remain. The existing “Ereignis” drawer merges game records and native events newest
first, rendering pages of 100 and reopening at the newest page; game records have
no server-event navigation. Its
bell is red for unread game alerts, including when native events are disabled.
A visible drawer marks game entries read; banner dismissal does not.

The Simulation tab's right-aligned **Test notifications** button sends
`[HH:mm:ss] Test notification: check that your phone plays a sound.` through the
same archive/banner/alarm/native paths without changing gameplay. Test Manager
both visible and separately backgrounded/display off. Request success does not
prove phone delivery or sound.

**Delete game status** rotates generation and clears the server journal; clients
discard old-generation history and presentation. **Delete all position data**
preserves notifications. **Delete whole session** removes the journal through
hidden-device deletion. Pause the Tool for cutover; deploy server migration/API,
then web, then Tool; reload Manager before testing.

`scripts/game_notification_integration.py` audits the local MySQL/HTTP API using
isolated fixture accounts/devices only. Run with Python and `websocket-client`
after the Docker build. It verifies pagination, transactional reset races,
authorization, websocket invalidation and cascade cleanup without sending pushes.

Local Compose sets `WEB_CACHE_CONTROL=no-cache` so Manager's persistent HTTP
cache revalidates web files on reopening instead of keeping an obsolete bundle
for the default hour. Unchanged files can still use conditional requests and
HTTP 304 responses. A restart or logout does not clear Manager's cache. Already
cached responses cannot be invalidated remotely by changing this header; after
their old cache lifetime expires, the new policy applies. On HTTPS installations
with an active service worker, also accept the web app's update-available prompt
to activate a waiting version. This does not change native Manager or its separate
bottom Snackbar, and does not delete local notification history.

Traccar Tool sends the matching native notification through Traccar's standard
announcement endpoint after publishing the map state. Production enables the
official relay with container environment variables:

```text
NOTIFICATOR_TYPES=web,mail,command,traccar
NOTIFICATOR_TRACCAR_KEY=<deployment secret>
```

Supply the key only through deployment secrets. If a deployment already uses
additional notificators, keep the complete existing list and append `traccar`.
The production Compose template reads these values from
`TRACCAR_NOTIFICATOR_TYPES` and `TRACCAR_NOTIFICATOR_TRACCAR_KEY`; the latter is
required and is never stored in this repository. Local Compose leaves the
relay disabled unless both values are supplied explicitly.

Live status, group, geofence, and map filters are scoped to the current
authenticated browser session. They survive navigation but reset on logout,
account changes, authentication expiry, and full reload. Device sort order
remains browser-persistent.

## Central game sessions

Traccar's database is the authoritative store for Traccar Tool sessions
(`changelog-game-sessions.xml`, implementation plan stage 1). Sessions have an
immutable numeric ID, a unique name, a schema version and one revision per
stored part. Every write names the revision it was based on; a stale revision
returns 409, while an identical retry of the last write returns the current
revision, so lost responses can be retried safely.

| Part | Table | Owner |
|---|---|---|
| Configuration (settings, roster, planned geofences with geometry, timezone) | `tc_game_sessions.config` | operator |
| Traccar resource mapping, hidden session device and membership | `tc_game_sessions.resources`, `tc_game_session_members` | Tool device setup |
| Tool runtime checkpoint (`game_status`) | `tc_game_sessions.runtime` | Tool |
| Generated-account passwords | `tc_game_session_credentials` (AES-GCM) | operator |
| Geofence planner document | `tc_game_geofence_plans` | Tool |
| Published timetable and per-entry progress | `tc_game_timetables`, `tc_game_timetable_entries` | Tool |
| Session log | `tc_game_session_logs` (append-only) | Tool |

All `/api/game-sessions` endpoints require a Traccar administrator; operators
are administrators in this version. `GET /api/game-sessions/capabilities`
returns the API version so mismatched Tool builds stop before writing. The
Traccar admin password and machine-specific paths (workbooks, KML imports) are
never stored centrally; the Tool keeps them in the OS credential store and in
local preferences.

Membership maps each generated user to one session, role, number and assigned
tracker; a user belongs to at most one session, and a session device to at most
one session. Timetable entries are identified by scheduled UTC time and
participant: matching entries keep their ID and progress across publications,
content edits update them in place, and entries that disappear are deactivated.

Generated passwords are encrypted with the Base64 256-bit key in
`gameSessions.credentialKey` (environment `GAME_SESSIONS_CREDENTIAL_KEY`;
Compose reads `TRACCAR_GAME_SESSIONS_CREDENTIAL_KEY` from `.env`). The key is
never stored in the database and must stay stable for the database's lifetime.
Without it, or with a different key, only credential reads and writes fail
(HTTP 503); everything else keeps working. Key rotation is not supported yet.

The notification journal and proximity tables carry the game-session ID.
Notification and proximity writes require a session device registered with a
game session (409 otherwise). Whole-session cleanup no longer deletes
notification history: deleting the session device or a recipient clears the
reference instead, and `recipientlabel` keeps the recipient's name.
Administrators read the complete history of a game session, across generations
and after cleanup, with `GET /api/game-notifications/history?gameSessionId=…`.
Proximity state, grants and circles remain live access data and are still
deleted with the session device. Deleting saved session data
(`DELETE /api/game-sessions/{id}`) removes every stored part, including its
notification history, but never touches managed Traccar resources.

The migration deletes notification and proximity rows created before central
sessions existed, because their local sessions are not migrated; nothing else in
the database is reset.

Audit the stored behaviour against the full Docker profile with
`../Traccar-Tool/venv_traccar/bin/python scripts/game_sessions_integration.py --tool-source ../Traccar-Tool/src`.
It creates and removes a temporary administrator and non-administrator and runs
the Tool's own server repository against the live API. The notification and
proximity audits register their fixture session devices with game sessions.

## Game controller and effects

One Traccar Tool instance controls the game server-wide at a time
(`changelog-game-controller.xml`, implementation plan stage 2). The lease is
bound to the operator and a unique app instance and lasts
`gameController.leaseSeconds` (default 30), renewed every
`gameController.renewSeconds` (default 5). Every acquisition gets a larger
`controllerEpoch`, stored in `tc_game_controller` so it keeps increasing across
restarts. `/api/game-controller` acquires, renews, releases and force-takes
over the lease, sets the session the controller executes, and lists the history
(`tc_game_controller_history`: acquisitions, releases, expiries, takeovers,
server restarts, execution changes and unfenced administrator edits). A server
restart ends an active lease, so Tool must resume explicitly. The API version
is 2.

Fenced requests carry the epoch in `X-Game-Controller-Epoch` and are refused
with HTTP 423 otherwise (`GameControllerFilter`):

- game-session runtime, resources, timetable progress and effects, and every
  write of the executing session; configuration of a session that is not
  executing needs only its revision check;
- writes to `/api/game-notifications` and `/api/game-proximity`;
- while a lease is held, standard mutations (devices, groups, users,
  permissions, geofences, `DELETE /api/positions`) of resources in any stored
  session's resource mapping, and `/api/notifications/send/*` reaching a member
  of the executing session, also without `userId`. While no lease is held,
  administrators may make them; each is recorded and reported to Tool on its
  next acquisition. Users may always update their own account, so participants
  can register push tokens and save preferences during a game;
- any request that carries an epoch must carry the current one.

A fenced request holds an in-process fence shared until its response;
acquisition, release and takeover take it exclusively, so a request that passed
the check completes before control changes hands. Only one server instance may
use the database. Tracker-endpoint uploads are not fenced (confirmed decision
8): Tool writes mirrors only through effects.

`POST /api/game-sessions/{id}/effects` applies one game operation in one
transaction under a durable `deliveryId` (`tc_game_deliveries`): positions for
the session's mirrors and game-state device, the runtime change, archived game
notifications, queued pushes and log entries. Repeating a delivery ID returns
the stored outcome without applying it again. Positions use the uniform mirror
payload (protocol `osmand`, accuracy/speed/course 0, activity `still`) plus the
caller's scalar attributes; the fix time defaults to the delivery time, and a
position older than the device's latest is stored without becoming latest.
After commit the latest positions reach the cache and WebSocket subscribers
like `PostProcessHandler`, and the destination devices show as online with a
new last-update time, as for tracker uploads. These positions skip filters and
event handlers.

Pushes are queued in `tc_game_push_outbox` and sent after commit, one attempt
per recipient, only to members of the session and, when they name an alert,
only if this delivery archived it first. Each row ends `sent` or `failed`;
after a restart, unattempted rows are sent and rows interrupted during their
attempt become `uncertain` and are never retried.

Administrators' WebSocket clients receive `{"invalidate": ["game-sessions"]}`
when a session is created, renamed or deleted or its configuration, resources,
credentials, geofence plans or timetable change (not on runtime saves or
effects, which follow every game action), and `["game-controller"]` when the
lease changes.

## Game requests and status

Session members submit self-service requests (`changelog-game-requests.xml`,
implementation plan stage 3). Membership authorizes them; in this stage
hunters control speedhunts on the players and supporters of their own session:
static ping, live ping (optional duration up to 240 minutes), stopping a live
ping, and ending a speedhunt (`POST /api/game-requests`). Requests are
refused while no Tool executes the session, when the participant is out, when
the speedhunt changed since the revision the hunter saw (409), and while
another request for the same participant is still open.

The controlling Tool processes them through fenced endpoints:
`GET /api/game-sessions/{id}/requests`, `POST …/requests/{requestId}/decision`
(`await`, `approve`, `reject`) and `POST …/requests/{requestId}/claim`.
Outcomes commit with the action's own effects (`requests` in the effects
delivery). Requests move through pending, approved, executing, succeeded,
failed, rejected, expired and cancelled; members see every step but never the
operator-only `detail`. The server expires requests awaiting approval or
execution after two minutes, and a change of control (acquisition, takeover,
release, expiry, server restart) invalidates every open request while keeping
its history.

The Tool publishes a status document with each effects delivery that changes
it (`status`, stored in `tc_game_session_status`). `GET /api/game-status`
returns the member's role-filtered view: hunters and spectators see each
player's and supporter's speedhunt state with its revision and break; players
and supporters only learn whether a speedhunt runs, never whom it targets, and
their own break.
Members' and administrators' WebSocket clients receive
`{"invalidate": ["game-requests"]}` and `["game-status"]` when these change.

## Jokers

Players and supporters hold jokers per type (`changelog-game-jokers.xml`,
implementation plan stage 4), keyed by their real tracker:
`tc_game_jokers` keeps the granted quantity and the uses (including uses beyond
the allocation), `tc_game_joker_reservations` the uses held by open requests,
and `tc_game_joker_ledger` every grant, revoke, reservation, release and use.
New sessions start without jokers. "Get hunter positions"
(`hunterPositions`), break (`break`), timetable fake (`fakeTimetable`) and
speedhunt fake (`fakeSpeedhunt`) jokers can be granted and used.

- Operators grant or revoke unused jokers with the fenced
  `POST /api/game-sessions/{id}/jokers/change`, and read the overview and
  ledger at `…/jokers` and `…/jokers/ledger`. Members read their own inventory
  at `GET /api/game-jokers`; unused inventories stay private.
- A participant's `jokerHunterPositions` request reserves one available joker.
  A succeeded request consumes it; any other end (rejected, cancelled,
  expired, failed, or invalidated by a change of control) releases it.
- An operator relay uses a joker inside its effects delivery (`jokers`). It
  never takes reserved uses; without an available joker it needs
  `overAllocate` and is recorded beyond the allocation. Available never goes
  below zero.
- `caughtParticipants` in an effects delivery ends the participants' open
  requests and releases their reservations.

### Breaks

A break protects a player or supporter from the hunters for the duration set
in the session's game settings (`game_settings.break_duration_minutes` in the
stored configuration). Break jokers can only be granted (409 otherwise) while a
positive duration is configured; revoking unused ones is always possible.
Breaks start only through a participant's `jokerBreak` request: operator
effects deliveries use only "Get hunter positions" jokers.

- The server refuses a `jokerBreak` request (409) when no duration is
  configured, when the requester is already on a break, and while any
  speedhunt runs according to the published status. The last refusal has the
  same message for every participant, so it never reveals whom a speedhunt
  targets, and reserves nothing.
- The Tool times breaks. It checks the request again, starts the break when it
  runs the request, and publishes it with the outcome; the succeeded outcome
  consumes the reserved joker. The break ends at its deadline (the Tool keeps
  active breaks in its runtime, so a restart keeps the deadline) or at once
  when the participant is caught.
- The published status gives each participant `break` (`active`, `startedAt`,
  `endsAt`). Hunters and spectators see every participant's break; a player
  or supporter sees only their own (`status.break` of `GET /api/game-status`).
  A break past `endsAt` no longer protects anyone, even before the Tool has
  published its end.
- Hunters' static and live ping requests on a participant on a break are
  refused (409). During the break the Tool withholds the hunter destinations
  from every position of that participant and keeps them out of proximity
  circles and anonymous proximity notifications.

Hunter relay snapshots carry the hunter position's own fix time, so a mirror
shows its age; an older fix is stored without becoming latest.

### Timetable fakes

A player or supporter requests `jokerFakeTimetable` with `parameters`
`entryId`, `latitude` and `longitude`. `GET /api/game-jokers` lists their
`timetableEntries`: their own active entries (the timetable column is the
assigned tracker's device name) at least 60 seconds ahead whose cell sends the
real position to hunters (blank, `2H`, `2P2H`, `2S2H`, `2P2S2H`). The server
refuses other entries, entries inside the requester's published break, and an
entry that already has a fake (409), adds `scheduledAt` to the parameters and
reserves one joker with the plan in the reservation's `details`.

- When the Tool records the request as succeeded, the server checks the entry
  again (a 400 makes the Tool record a failure, which returns the joker) and
  *arms* the fake instead of using the joker: the reservation keeps its status
  `reserved`, loses its request (moved into the plan) and the ledger records
  `arm`. Armed fakes are listed with `armed` and their `plan`.
- The Tool delivers the ping: hunter destinations get the fake, the cell's other
  audiences the real position, in one effects delivery whose `fakes` reports
  the reservation `used`. Pings that do not reach hunters report it `released`.
  A fake that is no longer armed is `skipped`.
- Owners cancel an armed fake with
  `POST /api/game-jokers/reservations/{id}/cancel` until 60 seconds before its
  ping. Publishing a timetable releases armed fakes whose entry disappeared or
  no longer reaches hunters, and reporting a catch releases the participant's
  armed fakes.
- Plans are private: owners see theirs in `/api/game-jokers` and as
  `status.fakes` in `/api/game-status`; operators in the overview. Hunters and
  spectators never see fake requests, plans or markers; the delivered positions
  carry the same payload as real pings.
- `{"invalidate": ["game-jokers"]}` reaches only the owners whose jokers
  changed and administrators (`updateGameUsers`), never the other members, so
  the moment a fake is used, armed, released or cancelled is not visible to
  hunters.
- Known limit: owners can cancel until 60 seconds before the ping and the Tool
  refreshes its armed fakes every 2 seconds. If those refreshes stall for more
  than a minute, the Tool may still send a cancelled fake; the server then
  ignores its use (`skipped`), so the joker is not used twice and nothing leaks.

### Speedhunt fakes

`jokerFakeSpeedhunt` takes `parameters` `pingNumber` (1 to the stored
`game_settings.pings_per_speedhunt`, or 1–1000 when it is blank), `latitude`
and `longitude`. A participant has at most one unbound speedhunt fake (an open
request or an armed fake; 409 otherwise). The succeeded request arms it like a
timetable fake.

- The Tool's delivery of a run's first ping lists every armed speedhunt fake it
  knows as `bound` (with `run`, an opaque reference of the run) in `fakes`.
  The server uses each joker in the same transaction as the ping's positions,
  whoever the run targets, records `boundAt`/`boundTo` in the plan and never
  refunds it. A fake that is no longer armed is `skipped`; the Tool then does
  not apply it. `boundTo` is never shown to owners, so a bound fake does not
  tell them whom the speedhunt targets.
- `pingNumber` and `entryId` must be whole numbers (400 otherwise).
- Known limit: a speedhunt fake cancelled in the seconds before the next run
  starts is skipped by the server; if it names ping 1, the Tool may already
  have sent it with that first ping.
- Owners cancel an unbound speedhunt fake through the same cancel endpoint; a
  bound one is no longer cancellable (409). A reported catch releases unbound
  fakes; bound ones stay used.
- The owner's `/api/game-jokers` also lists their latest bound speedhunt fakes
  (`bound` true, status `consumed`); `status.fakes` shows only armed ones.
- The Tool applies only the target's own fake, at its ping number, to hunter
  destinations; those positions carry no marker.

### Mirror-only hunter access

Break and fake-ping jokers only protect a participant while hunters see players
and supporters through mirrors. Once a session has break or fake jokers
allocated or active (`granted` or `used` above zero in `tc_game_jokers`), no
hunter of that session may access a real tracker of its players and
supporters, directly (`tc_user_device`) or through a group (`tc_user_group`, at
any nesting depth of `tc_groups`). `GameAccessRule` checks this wherever access
or jokers are granted:

- granting break or fake jokers (`…/jokers/change`) is refused with 409 while a
  hunter already has such access;
- `GameAccessFilter` refuses `POST /api/permissions` (and `/bulk`), and device
  or group moves (`PUT /api/devices/{id}`, `PUT /api/groups/{id}` with a new
  `groupId`) that would add such access, with 422 and a text naming the
  hunter. It applies to every caller allowed to make the change, the
  controlling Tool included; other callers get Traccar's own refusal, so the
  answer tells them nothing about the game's trackers or hunters. Access the
  request does not add never blocks it;
- saving session members (`PUT …/resources`) that would make a user with such
  access a hunter, or such a device a participant tracker, is refused with
  422 (409 there means a revision conflict). Access the new members do not add
  never blocks the save.

Spectators keep their access to real trackers; the Tool additionally refuses
device setup that would give hunters real trackers or the spectator's password.

Known limits: Traccar saves permissions without database transactions, so a
permission grant and a first break or fake joker grant made at the same moment
can both pass their checks. Making a hunter an administrator, or linking them
as manager of another user (`managedUserId`), is not checked; only
administrators can do either.

## Multi-participant replay

The replay page accepts individual devices, nested groups, or all devices
available to the signed-in user. It resolves the selection to at most 50 unique
devices and requests at most 48 hours of history from Traccar's existing
`GET /api/reports/route` endpoint using repeated `deviceId` query parameters.
These limits protect the replay UI; they do not change the standard report API.
The server continues to enforce device permissions, log report access, and
apply its configured `report.maxPositions` history limit.

All selected tracks share one time-based playback clock. A device appears when
its first fix is reached, remains visible for exactly two minutes after its
latest fix, and reappears at its next fix. Playback supports timeline seeking,
one- and ten-second steps, speed presets, and any positive finite custom speed.
Markers are unclustered and retain the participant role badges described above.

Routes are optional and disabled by default. When enabled, the web app renders
one continuous speed-gradient route per device against a combined speed scale.
Route points can be enabled separately and seek the shared replay clock when
selected. KML download remains available when the resolved selection contains
exactly one device.

Replay is frontend orchestration over existing persisted position history. It
does not require a Java endpoint, schema migration, or Traccar Tool change. A
dedicated backend would only be appropriate if replay later requires
server-managed sessions, authoritative game-state filtering, or paginated
global result limits.

## Build contract

The release workflow checks out this repository with submodules and builds
`traccar-web/` at the commit recorded by the server repository. It must not
select a separate web revision by a release tag. Project-branch CI runs all web
unit tests, lint, and the production build in addition to the server build.

When changing the web app:

1. Commit and push the web change first.
2. Update and commit the `traccar-web` submodule pointer in this repository.
3. Build the combined server release from that server commit.

## Local development environment

The repository includes `compose.yml` for a persistent local MySQL database and
an optional full-stack integration build. The database matches the deployed
MySQL 8.4.8 version, character set, JDBC options, and InnoDB configuration. It
uses local development credentials and named volumes; it does not contain or
copy production passwords or data.

Install Homebrew `openjdk@21` and `node@22` (Temurin 21 is also compatible).
Select the project versions for the current terminal with:

```shell
export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$(brew --prefix node@22)/bin:$PATH"
```

### Fast server and web development

Start MySQL in Docker:

```shell
docker compose up -d --wait database
```

Optional `TRACCAR_DB_PASSWORD` and `TRACCAR_DB_ROOT_PASSWORD` overrides must be
set before the named database volume is first created and then kept stable.

Run only one Traccar server against the shared development database at a time.
The different host ports prevent network conflicts, but two server instances
can still duplicate scheduled processing and notifications or retain stale
caches. Before starting local Java, stop the Docker server while leaving MySQL
running:

```shell
docker compose --profile full stop traccar
```

The Vite web server may also remain running while switching server modes.

Build and run Traccar locally so Java debugging and rebuilds stay fast:

```shell
./gradlew assemble --no-daemon --stacktrace
CONFIG_USE_ENVIRONMENT_VARIABLES=true \
DATABASE_DRIVER=com.mysql.cj.jdbc.Driver \
DATABASE_URL="jdbc:mysql://127.0.0.1:3307/traccar?zeroDateTimeBehavior=round&serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false&allowMultiQueries=true&autoReconnect=true&useUnicode=yes&characterEncoding=UTF-8&sessionVariables=sql_mode=''" \
DATABASE_USER=traccar \
DATABASE_PASSWORD="${TRACCAR_DB_PASSWORD:-traccar-dev}" \
"$JAVA_HOME/bin/java" -jar target/tracker-server.jar debug.xml
```

In another terminal, start the web app:

```shell
cd traccar-web
npm ci
npm start
```

Open `http://localhost:3000`. Configure Traccar Tool with server URL
`http://localhost:8082` and client URL `http://localhost:5055`.

### Full Docker integration test

Before starting the full Docker profile, stop any locally running Java server
with `Ctrl+C`. MySQL and the Vite web server may remain running.

Build and run the combined customized server and web app:

```shell
docker compose --profile full up -d --build --wait
```

Open `http://localhost:8083`. Configure Traccar Tool with server URL
`http://localhost:8083` and client URL `http://localhost:5056`. Stop the stack
without deleting its named database and log volumes:

```shell
docker compose --profile full down
```

Do not add `-v` to the shutdown command unless the local database is
intentionally being reset. See `AGENTS.md` for the complete verification and
repository-safety contract.

## Rollout order

1. Configure and deploy the server notification relay secret and append the
   `traccar` notificator.
2. Deploy the combined server and web build.
3. Release the matching Traccar Tool so alert emission begins only after web
   parsing and native push delivery are available.
4. Run Device Setup once for every existing session.
5. Confirm managed devices have category `person`, integer
   `traccarToolRoleNumber`, and the expected `traccarToolRole` while retaining
   unrelated attributes.

There is intentionally no legacy numeric-category fallback because this project
has not entered production. Keep the legacy `Traccar-Server` repository as a
read-only migration reference until the combined release has been verified.

If game-session state later moves into the server, introduce a dedicated
`GameSessionParticipant` entity and API as a separate migration. That future
model can become authoritative for assignments; role-marker presentation alone
does not justify adding it now.
