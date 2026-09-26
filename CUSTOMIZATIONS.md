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

Live status, group, geofence, and map filters are scoped to the current
authenticated browser session. They survive navigation but reset on logout,
account changes, authentication expiry, and full reload. Device sort order
remains browser-persistent.

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

1. Deploy the combined server and web build.
2. Release the matching Traccar Tool.
3. Run Device Setup once for every existing session.
4. Confirm managed devices have category `person`, integer
   `traccarToolRoleNumber`, and the expected `traccarToolRole` while retaining
   unrelated attributes.

There is intentionally no legacy numeric-category fallback because this project
has not entered production. Keep the legacy `Traccar-Server` repository as a
read-only migration reference until the combined release has been verified.

If game-session state later moves into the server, introduce a dedicated
`GameSessionParticipant` entity and API as a separate migration. That future
model can become authoritative for assignments; role-marker presentation alone
does not justify adding it now.
