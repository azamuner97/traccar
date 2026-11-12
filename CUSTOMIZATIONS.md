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
device.attributes.traccarToolGameSessionId = stored game session ID
```

Role and number identify a member only within its session, so the Tool also
writes the stored session a device belongs to. A real tracker bound in several
sessions carries the markers of the session set up last.

The web app renders the role background and participant number on map markers
and in the device sidebar. The number is rendered as text, so it is not limited
to a fixed set of image assets. Missing or invalid role metadata uses the normal
Traccar category marker. Numeric device categories are not a compatibility
source for role numbers.

Traccar already persists arbitrary device attributes through its existing
device model and API. No Java model field, database migration, or dedicated
role endpoint is required for this feature. The Tool owns reconciliation of the
managed values and preserves unrelated attributes.

## Game data model 2

Traccar's database is the single store of game sessions and game state
(`schema/changelog-game-model-2.xml`, included last in `changelog-master.xml`).
The game-session API version is **5**; `GET /api/game-sessions/capabilities`
answers it, and a Tool built for another version stops at connection.

- **Members.** Each participant is a member with a stable ID
  (`tc_game_session_members`): role (`player`, `supporter`, `hunter`,
  `spectator`), number, display name, and the bound account and tracker. A
  member's role never changes (a role change retires the member and plans a new
  one). Retiring a member ends its running speedhunt (terminated), its live
  uploads and hunters' open requests on it, so nothing waits for a member that
  can no longer act; its catch, breaks and history stay. Numbers are never
  reused in a session, also after retirement. A session
  has at most one spectator (number 1, no tracker). An account is bound to at
  most one active member of all sessions; a real tracker to at most one member
  per session, and it may be bound in several sessions.
- **Computed status.** The server computes every member's status from typed
  tables: catches (`tc_game_catches`), speedhunt runs and pings
  (`tc_game_speedhunts`, `tc_game_speedhunt_pings`), breaks (`tc_game_breaks`),
  live uploads (`tc_game_live_uploads`) and per-member revisions
  (`tc_game_member_state`). Nothing writes a status document, and there is no
  hidden game-state device.
- **Typed operations.** The Tool stays the scheduler. It sends typed operations
  (`catch`, `release`, `pingDelivered`, `pingFailed`, `runEnded`,
  `liveUploadStarted`, `liveUploadSample`, `liveUploadEnded`, `breakStarted`,
  `breakWaiting`, `breakEnded`, `breakCancelled`, `breakMissed`, `fakeUsed`,
  `fakeReleased`, `checkpoint`) through `POST /api/game-sessions/{id}/effects`.
  The server validates every lifecycle transition (409 for an illegal one) and
  never schedules anything itself.
- **Resource rows.** The Traccar resources a session owns are rows: members'
  accounts and trackers, mirrors (`tc_game_mirrors`), groups
  (`tc_game_session_groups`) and managed geofences
  (`tc_game_session_geofences`). Rows in state `retiring` stay owned until the
  Tool's cleanup removes them. Traccar permissions are derived by the Tool and
  not stored.
- **Code.** Services and repositories live in `org.traccar.game`, the REST
  resources in `org.traccar.api.game` (registered by `WebServer` next to
  `org.traccar.api.resource`). Timestamps are UTC `DATETIME(3)` (`GameTime`).
  A write transaction validates, locks the session row and then member states
  in ascending order, applies, advances revisions, commits, and only then
  publishes (`GamePublisher`, implemented by `GameServerPublisher`).

The model-2 changelog drops every model-1 game table with its rows and model-1
game drawings, keeps the lease row of `tc_game_controller` (its epoch keeps
increasing) and replaces `tc_drawings.sessiondeviceid` with `gamesessionid`.
The drop is one changeset per statement, each run only while its object exists
and the model-2 tables do not: MySQL commits every DDL statement on its own, so
a cutover that stops halfway resumes at the next start. A database migrated by
the former single drop changeset runs none of them.
It does not touch Traccar users, devices, groups or positions: Traccar resources
that model-1 sessions created (the hidden state device, generated accounts with
model-1 membership markers, mirrors, groups) stay until they are removed.

On H2, Liquibase's migration session stays open while the server runs
(`DatabaseModule`): H2 2.4.240 binds a CHECK constraint to the session that
created it, and the model-2 tables use CHECK constraints.

### Participant role markers and live visibility

Membership is never read from account attributes. The model-1 user markers
(`traccarToolUserRole`, `traccarToolSessionDeviceId`,
`traccarToolAssignedDeviceId`, `traccarToolParticipantRole`/`Number`) grant
nothing any more; the Tool strips them from generated accounts. The device
markers above stay as presentation that the Tool writes from member and mirror
rows.

The web app reads membership from `GET /api/game-status` (403 means no
membership) and filters the live map from the computed status: for players and
supporters, caught players and supporters are hidden with their mirrors, and
their own outgoing mirrors are hidden. Members map to devices through the role
markers, because role and number identify a member within a session; a device
marked with another session never stands for a member of this one (devices set
up before the session marker existed count for any session).
Administrators, accounts without membership and members before their first
status see the map unfiltered (fail-open). This filtering is live-page
presentation, not an authorization boundary; replay, reports, exports and
direct API access keep every device the account may read.

### Game sessions and documents

All `/api/game-sessions` endpoints require a Traccar administrator; operators
are administrators in this version.

| Part | Table | Written by |
|---|---|---|
| Identity and aggregate revision | `tc_game_sessions` | operator |
| Settings (configuration, roster, timezone, game settings) | `tc_game_session_settings` | operator (`PUT …/config`) |
| Members and bindings | `tc_game_session_members` | Tool (`…/members/*`) |
| Mirrors, groups, managed geofences | `tc_game_mirrors`, `tc_game_session_groups`, `tc_game_session_geofences` | Tool (`…/resources/*`) |
| Generated-account passwords | `tc_game_session_credentials` (AES-GCM) | operator |
| Geofence planner document | `tc_game_geofence_plans` | Tool |
| Recovery checkpoints (monitoring cursors, relay counters) | `tc_game_session_checkpoints` | Tool (`…/checkpoints/*`) |
| Published timetable and per-entry progress | `tc_game_timetables`, `tc_game_timetable_entries` | Tool |
| Session log (including speedhunt events) | `tc_game_session_logs` | Tool |

Every revisioned write names the revision it was based on; a stale revision
answers 409, and an identical retry of the last write answers the current state.
`GET /api/game-sessions/{id}/snapshot` is one consistent read of everything the
Tool loads and recovers from (MySQL `REPEATABLE READ`, H2 `SERIALIZABLE`).
Checkpoints never hold game outcomes: deleting every checkpoint only makes
monitoring scan again. Deleting a session (`DELETE /api/game-sessions/{id}`)
removes every game record of it, including its drawings, but never the Traccar
resources it manages; the Tool's cleanup removes those first.

Generated passwords are encrypted with the Base64 256-bit key in
`gameSessions.credentialKey` (environment `GAME_SESSIONS_CREDENTIAL_KEY`;
Compose reads `TRACCAR_GAME_SESSIONS_CREDENTIAL_KEY` from `.env`). The key is
never stored in the database and must stay stable for the database's lifetime.
Without it, or with a different key, only credential reads and writes fail
(HTTP 503). Key rotation is not supported yet.

Timetable publishing resolves each participant header to an active player or
supporter by display name, or else by the name of the member's tracker; a header
that names nobody or several members answers 400. Entries are keyed by time and
member, so a rename never moves them.

### Game controller, fencing and effects

One Traccar Tool instance controls the game server-wide at a time. The lease is
bound to the operator and a unique app instance and lasts
`gameController.leaseSeconds` (default 30), renewed every
`gameController.renewSeconds` (default 5). Every acquisition gets a larger
`controllerEpoch`, stored in `tc_game_controller`, so it keeps increasing across
restarts. `/api/game-controller` acquires, renews, releases and force-takes over
the lease, sets the session the controller executes, and lists the history
(`tc_game_controller_history`). A server restart ends an active lease, so the
Tool must resume explicitly. A change of control (acquisition, takeover,
release, expiry, server restart) ends every open game request after its commit:
waiting ones expire and executing ones fail.

Fenced requests carry the epoch in `X-Game-Controller-Epoch` and are refused
with HTTP 423 otherwise (`GameControllerFilter`):

- for any session: effects, timetable progress, `members/*`, `resources/*`,
  `checkpoints/*`, `requests/*` and `jokers/*`; every write of the executing
  session. Configuration of a session that is not executing (name, settings,
  credentials, geofence plans, timetable, logs) needs only its revision check;
- writes to `/api/game-notifications` and `/api/game-proximity`;
- while a lease is held, standard mutations (devices, groups, users,
  permissions, geofences, `DELETE /api/positions`) of the Traccar resources any
  session owns (`GameOwnershipService`), and `/api/notifications/send/*`
  reaching a member of the executing session. While no lease is held,
  administrators may make them; each is recorded and reported to the Tool on its
  next acquisition. Users may always update their own account;
- the filter judges the object the resource will change: path IDs are read as
  Jersey reads them (`07` is 7), an object update must name its path's object in
  its body (400 otherwise), and permission bodies are read as the permission
  resource reads them (keys matched case-insensitively by class name, values
  also from strings);
- any request that carries an epoch must carry the current one.

Member writes (`/api/game-requests`, `/api/game-jokers`) are never fenced; they
check again under the session lock that the member is still active and bound to
the calling account, so a former account never acts for its member. A
fenced request holds an in-process fence shared until its response; acquisition,
release and takeover take it exclusively. Only one server instance may use the
database. Tracker-endpoint uploads are not fenced.

An effects delivery commits under a durable `deliveryId`
(`tc_game_deliveries`), in one transaction: request outcomes, operations,
joker uses, mirror positions, archived alerts, queued pushes, logs and revisions.
Repeating a delivery ID returns the stored outcome and publishes nothing; the
same ID with other content answers 409. Writable devices are the session's
active mirrors. Positions use the uniform mirror payload (protocol `osmand`,
accuracy/speed/course 0, activity `still`); fix times are truncated to whole
seconds, and a fix older than the device's latest is stored without becoming
latest. After commit the latest positions reach the cache and WebSocket
subscribers, and the destination devices show as online with a new last-update
time, as for tracker uploads. These positions skip filters and event handlers.

Pushes are queued by member in `tc_game_push_outbox` and sent after commit, one
attempt per recipient, through the recipient account's notificator. Each row
ends `sent` or `failed`; at startup, rows interrupted during their attempt become
`uncertain` and are never retried, and rows still queued are sent.

### Publishing

After commit, `GameServerPublisher` sends WebSocket invalidations under model
1's names (`{"invalidate": ["game-status"]}` and `game-speedhunt-history`,
`game-requests`, `game-jokers`, `game-timetable`, `game-notifications`,
`game-proximity`). It resolves members to their accounts; administrators always
receive them.

- Session-wide changes reach every active member of the session; private
  changes (a joker grant or use, a fake, a planned break) reach only their
  owners. `game-proximity` reaches only hunters and the spectator; players and
  supporters learn about their own temporary access through a device-access
  refresh (`{"invalidate": ["devices", "positions"]}`).
- A binding change tells the session's members and both accounts, the one it
  bound and the one it removed, so an account learns at once that it gained or
  lost membership. Deleting a session tells its accounts. A settings change and a
  change of the executing session or lease tell the session's members.
- Operators receive `game-sessions` (create, rename, delete) and
  `game-controller` (lease changes).

### Requests, jokers and breaks

Members submit self-service requests (`POST /api/game-requests`) that target
members. Hunters' speedhunt requests carry the target's member state revision
(`tc_game_member_state.revision`), which the server advances with every change
hunters can observe; players and supporters never receive it. The controlling
Tool decides (`await`, `approve`, `reject`) and claims them through fenced
endpoints and records their outcomes in effects deliveries. The server expires
requests awaiting approval or execution after two minutes
(`TaskGameController`).

Jokers are held per member (`tc_game_jokers`); the hunters' speedhunt pool has
its own table (`tc_game_joker_pools`). Reservations (`tc_game_joker_reservations`)
are accounting only, and the append-only ledger (`tc_game_joker_ledger`)
records every grant, revoke, reservation, arm, use, overuse and release.
`GET /api/game-sessions/{id}/jokers/reconcile` checks balances against the
ledger.

- A speedhunt run begins with its first delivered ping (`pingDelivered`),
  which uses the pool joker a hunter's request holds or armed for that target,
  or else one more pool joker (beyond the allocation only with
  `overAllocate`). The run's creation binds every armed speedhunt fake; the
  server applies the target's own fake to the target's hunter-mirror positions
  and records the ping source (`real`, `operatorFake`, `jokerFake`), which only
  operators read.
- Breaks have their own lifecycle (`planned`, `waiting`, `active`, `ended`,
  `cancelled`, `missed`). A break that starts now is started in the delivery of
  its request's success. A break is refused while any speedhunt runs, while the
  member is caught and during another break. Planned breaks are private to their
  owners (`status.plannedBreaks`) and operators.
- Fake plans have their own table (`tc_game_fake_ping_plans`): exactly one of a
  timetable entry or a speedhunt ping number, at most one fake per timetable
  entry and one unbound speedhunt fake per member. Owners cancel unbound fakes
  until they are used; another owner's reservation answers like a missing one
  (404).

### Mirror-only hunter access

Break and fake-ping jokers only protect a participant while hunters see players
and supporters through mirrors. Once a session has break or fake jokers
allocated, held (a reservation, such as a break planned beyond the allocation)
or active, no active hunter member of that session may access the
tracker of an active player or supporter member, directly (`tc_user_device`) or
through a group (`tc_user_group`, at any nesting depth). `GameAccessRule`
checks this wherever access or jokers are granted:

- granting break or fake jokers is refused with 409 while a hunter already has
  such access;
- `GameAccessFilter` refuses `POST /api/permissions` (and `/bulk`), and device
  or group moves (`PUT /api/devices/{id}`, `PUT /api/groups/{id}` with a new
  `groupId`) that would add such access, with 422. It applies to every caller
  allowed to make the change, the controlling Tool included; other callers get
  Traccar's own refusal;
- binding a member's account or tracker that would add such access is refused
  with 422 (a binding hook);
- a break does not start (409) while a hunter could see the participant's
  tracker, whatever gave that access.

Spectators keep their access to real trackers. Known limits: Traccar saves
permissions without database transactions, so a permission grant and a first
break or fake joker grant made at the same moment can both pass their checks.
Making a hunter an administrator or a manager of another user is not checked.

Binding hooks (`GameBindingHook`) run inside every binding change: besides the
access rule, `GamePlanInvalidation` ends the member's open requests, releases
its armed fakes and cancels its planned and waiting breaks, and the member's
proximity grants are revoked.

### Notifications

The notification journal (`tc_game_notifications`, with stream metadata in
`tc_game_notification_state`: generation, reset time, revision) holds every
alert of a session: a stable case-sensitive alert ID, generation, type, audience,
recipient and source members with label snapshots, UTC occurrence, IANA timezone
and the final timestamped message. Members read their own session's journal with
`GET /api/game-notifications?gameSessionId=…` (another session answers 403);
an administrator who names `gameSessionId` gets the operator view.
`POST /api/game-notifications/session/{id}` initializes the stream, `…/reset`
starts a new generation and deletes the history, and
`GET /api/game-notifications/history?gameSessionId=…` lists the full history
for operators. Alerts are archived inside effects deliveries. Device saves no
longer intercept notification attributes: the stream metadata is not a device
attribute.

Audiences keep model 1's semantics with members: `session` alerts reach every
member; targeted alerts have recipient = source (a player, supporter or hunter);
live-recipient alerts (`liveHunter`, `livePlayer`, `liveSupporter`,
`liveSpectator`) match the recipient's current role and name another active
player or supporter as source. A tracker replacement keeps a member's personal
history; a role change (a new member) does not.

The web app caches history in IndexedDB per origin, account and game session,
never replays history as alarms, and shows only the newest previously unseen
record younger than 15 minutes as a banner. Traccar Tool sends native pushes
through the outbox above; production enables the official relay with container
environment variables:

```text
NOTIFICATOR_TYPES=web,mail,command,traccar
NOTIFICATOR_TRACCAR_KEY=<deployment secret>
```

Supply the key only through deployment secrets. If a deployment already uses
additional notificators, keep the complete existing list and append `traccar`.
The production Compose template reads these values from
`TRACCAR_NOTIFICATOR_TYPES` and `TRACCAR_NOTIFICATOR_TRACCAR_KEY`; the latter is
required and is never stored in this repository. Local Compose leaves the relay
disabled unless both values are supplied explicitly.

Local Compose sets `WEB_CACHE_CONTROL=no-cache` so Manager's persistent HTTP
cache revalidates web files on reopening instead of keeping an obsolete bundle.

Live status, group, geofence, and map filters are scoped to the current
authenticated browser session. They survive navigation but reset on logout,
account changes, authentication expiry, and full reload. Device sort order
remains browser-persistent.

### Proximity

The Tool publishes a session's whole proximity content with an expected
revision (`PUT /api/game-proximity/session/{id}`): grants by recipient and target
member (`tc_game_proximity_grants`, with the resolved recipient account and
target tracker) and circles with their members. Proximity has no generation; a
reset (`…/reset`) publishes empty content with the next revision. Hunters and the
spectator read `GET /api/game-proximity/circles`, players and supporters their
own grants at `GET /api/game-proximity/access`.

Grants are temporary read-only device access: Traccar's permission union
(`DatabaseStorage`) adds `tc_game_proximity_grants` rows whose `expiresat` (UTC
`DATETIME(3)`) lies ahead, for enabled non-administrator accounts. Write checks
and `excludeTemporary()` reads leave them out. `TaskGameProximity` deletes
expired grants and circles every second and refreshes the recipients' device
access; rebinding a member revokes its grants in the same transaction.

### Drawings

`tc_drawings.gamesessionid` records the game session the owner took part in when
drawing. Sharing reads membership rows (`DrawingUtil`, `DrawingResource`):
owners see their drawings; the spectator sees every drawing of its session and
edits none; players, supporters and hunters with sharing switched on
(`traccarToolShareGroupDrawings`, an account attribute only administrators
change) see and edit the drawings of the members with the same role in the same
session who also share. Administrators see and delete every drawing. Deleting a
session deletes its drawings.

### Audits

`scripts/game_sessions_integration.py`, `game_notification_integration.py`,
`game_proximity_integration.py` and `drawing_integration.py` were written for
model 1 and are ported with the integration audit of step 7 of the data
architecture plan. The model-2 service suites run on H2 in every build
(`org.traccar.game`, `org.traccar.api.game`) and on MySQL when
`TRACCAR_TEST_MYSQL_URL`, `TRACCAR_TEST_MYSQL_USER` and
`TRACCAR_TEST_MYSQL_PASSWORD` name a throwaway server.

## Game page

Every generated game account opens the **Game** page (`/game`) from the bottom
menu; it is laid out for phones and also works in the desktop sidebar menu. It
reads only the member endpoints, so it shows nothing the server did not
already filter for the signed-in member:

- `GET /api/game-status`: the caller's membership (`gameSessionId`,
  `memberId`, `role`, `number`, `displayName`), whether a Tool runs the game
  (`controllerActive`), the server's clock (`serverTime`), a `hash` of the
  projection, and the role-filtered status (players and supporters: the
  anonymous speedhunt status, the catch roster, the own break, the own armed
  fakes and planned breaks; hunters: every participant's speedhunt state with
  its revision, break, limits, the last target and live shares; the spectator:
  the participants with display names and trackers);
- `GET /api/game-requests`: the member's own requests (hunters and spectators:
  every speedhunt request);
- `GET /api/game-jokers` (players and supporters): the own jokers,
  reservations and plans, the timetable pings a fake can replace and the
  session's `pingsPerSpeedhunt` (null when unlimited);
- `GET /api/game-timetable`: the timetable pings the member may see (the 20
  most recent of the last hour and at most 200 upcoming ones), each with `entryId`, `scheduledAt`, the
  participant's member (`memberId`) and its `state` (`scheduled`,
  `sent`, `skipped` or `failed`, with `stateAt`). Players and supporters see
  their own pings that can carry a fake, with `fakeAllowed`; hunters and
  spectators see every ping that reaches hunters (blank cells, target codes
  with hunters and operator coordinates). The cell content is never included,
  so an operator coordinate or a fake looks exactly like a real ping. Members
  receive `{"invalidate": ["game-timetable"]}` when a timetable is published
  or its progress changes.

The Tool records each entry's progress from what became of that
participant's ping: `sent`, `skipped` (an `x` cell, an overdue row, the
participant out, or a break that withheld the hunters) or `failed` (no
position, an upload error or no recipient).

Hunters see every player and supporter with the speedhunt state (open,
running with its ping count, live until when), who is protected by a break and
until when, the hunter-facing pings with their states, and the session's
speedhunt requests. They request static and live pings, stop live pings and
end speedhunts (after a confirmation) with the participant's current
speedhunt revision; the buttons follow the Tool's own checks. Spectators see
the same without controls. Players and supporters plan fakes on a map: a tap
chooses the coordinate (converted to WGS84 whatever the map style uses), then
one of their own upcoming pings that can carry a fake or a ping number of the
next speedhunt (1 to `pingsPerSpeedhunt`).

The page shows whether the game runs, is paused (no Tool controls it) or the
page is disconnected; the anonymous speedhunt status; the own break with its
absolute end and a countdown computed from the server's clock, shown as
ending once its deadline has passed but no Tool has ended it yet; jokers with
available, reserved and used (including uses beyond the allocation); held
reservations, armed fakes and fakes bound to a speedhunt; and the own requests
with their state, message and expiry countdown. Players and supporters use
"Get hunter positions" and break jokers there (after a confirmation), cancel
their open requests and cancel unbound fakes. Buttons are disabled only for
reasons every participant sees alike (no Tool, no joker left, an open request
of the same joker, the own break, any speedhunt running, no ping that can
carry a fake, an unbound speedhunt fake); the server checks everything again.
Pings past their time that are still scheduled show as due while a Tool runs
and as not sent while none runs or the page is offline; hunters also see
pings inside a participant's break as protected.

The `game` store slice belongs to one member (session, member and role, as the
status names it): data loaded for another is discarded. `GameController` (mounted by
`SocketController`) reads the endpoints on sign-in, on the `game-status`,
`game-requests`, `game-jokers` and `game-timetable` invalidations, after every WebSocket
reconnect, when the app returns to the foreground, and every 30 seconds
(every 5 seconds while disconnected). Game texts exist in English and German;
other languages fall back to English.

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
2. Stop the Tool and release the lease. Remove model-1 sessions' Traccar
   resources (the hidden state device, generated accounts with model-1 markers,
   mirrors, groups), or start from a fresh database: the model-2 Tool no longer
   knows the state device.
3. Deploy the combined server and web build. Its migration drops the model-1
   game tables and their data; it keeps Traccar users, devices, ordinary
   drawings and the controller epoch.
4. Release the matching Traccar Tool (game-session API version 5) and run
   Device Setup for every new session: it plans members before it creates
   devices and accounts.
5. Confirm managed devices have category `person`, integer
   `traccarToolRoleNumber`, and the expected `traccarToolRole` while retaining
   unrelated attributes.

There is intentionally no legacy compatibility because this project has not
entered production: model-1 game data is dropped, and model-2 session IDs start
again at 1.
