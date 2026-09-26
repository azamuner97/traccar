# Development instructions

This repository contains the Traccar server and pins the customized web app as
the `traccar-web` Git submodule. Use the workflows below so local and CI testing
stay close to the deployed MySQL configuration.

## Required tools

- Docker Desktop with Docker Compose.
- OpenJDK 21 for local server builds and execution. The Homebrew `openjdk@21`
  formula is the default on this workstation; Temurin 21 is also compatible.
- Node.js 22 for the web app. Do not use a different globally installed Node
  version just because it is earlier on `PATH`.

On macOS with Homebrew, select the project runtimes with:

```shell
export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$(brew --prefix node@22)/bin:$PATH"
java -version
node --version
```

## Fast development loop

Start the persistent MySQL database and wait for it to become healthy:

```shell
docker compose up -d --wait database
```

The defaults are local-only credentials. If `TRACCAR_DB_PASSWORD` or
`TRACCAR_DB_ROOT_PASSWORD` is overridden, set it before the database volume is
created and keep it stable for the lifetime of that volume.

Run only one Traccar server against the shared development database at a time.
The different host ports prevent network conflicts, but two server instances
can still duplicate scheduled processing and notifications or retain stale
caches. Before starting local Java, stop the Docker server while leaving MySQL
running:

```shell
docker compose --profile full stop traccar
```

The Vite web server may also remain running while switching server modes.

Build and run the server locally against that database:

```shell
./gradlew assemble --no-daemon --stacktrace
CONFIG_USE_ENVIRONMENT_VARIABLES=true \
DATABASE_DRIVER=com.mysql.cj.jdbc.Driver \
DATABASE_URL="jdbc:mysql://127.0.0.1:3307/traccar?zeroDateTimeBehavior=round&serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false&allowMultiQueries=true&autoReconnect=true&useUnicode=yes&characterEncoding=UTF-8&sessionVariables=sql_mode=''" \
DATABASE_USER=traccar \
DATABASE_PASSWORD="${TRACCAR_DB_PASSWORD:-traccar-dev}" \
"$JAVA_HOME/bin/java" -jar target/tracker-server.jar debug.xml
```

In another terminal, run the web app:

```shell
cd traccar-web
npm ci
npm start
```

Use `http://localhost:3000` for the web UI. Configure Traccar Tool with server
URL `http://localhost:8082` and client/upload URL `http://localhost:5055`.

Stop the database without deleting its data with:

```shell
docker compose down
```

## Full Docker integration loop

Before starting the full Docker profile, stop any locally running Java server
with `Ctrl+C`. MySQL and the Vite web server may remain running.

Build and start the combined server and web image with the same MySQL settings:

```shell
docker compose --profile full up -d --build --wait
```

Use `http://localhost:8083` for the web/API and `http://localhost:5056` for
device uploads. Inspect failures with:

```shell
docker compose --profile full ps
docker compose --profile full logs --no-color database traccar
```

Stop the full stack while retaining database and log volumes with:

```shell
docker compose --profile full down
```

Never run `docker compose down -v`, `docker volume rm`, or otherwise reset the
persistent development database unless the user explicitly requests it. CI is
the only context where automatic removal of its disposable Compose volumes is
allowed.

## Required verification

For server changes, run:

```shell
./gradlew build --no-daemon --stacktrace
```

For web changes, run from `traccar-web`:

```shell
npm ci
npm test
npm run lint
npm run build
```

Before commits or releases that affect integration, packaging, persistence, or
server/web interaction, also run the full Docker integration loop and verify
`http://localhost:8083/api/health`.

## Repository and data safety

- Never copy production database contents, passwords, tokens, or other secrets
  into this repository, test fixtures, logs, or chat output.
- Treat the deployment host as read-only unless the user explicitly requests a
  production change.
- Preserve unrelated working-tree changes and persistent Docker volumes.
- When changing the web app, commit and push the `traccar-web` change first,
  then update and commit the submodule pointer in this server repository.
