# Project customizations

This Traccar fork runs location-based games with Traccar Tool and the customized
web app pinned by the `traccar-web` submodule.

## Game sessions and execution

- **Server-owned sessions.** Game data model 2 stores sessions, stable member
  identities, tracker and account bindings, managed resources, and game state in
  Traccar. The matching Tool requires game-session API version 12.
- **Game control.** One Tool instance holds the server-wide controller lease, and
  one session can be running at a time. The server runs timetables, live uploads,
  hunter relays, and break timers even if the Tool disconnects. The Tool sends
  one-off ping intents to the server, which applies the game rules and routes
  positions to the appropriate mirror devices.
- **Game actions.** The server tracks catches, speedhunts, requests, and break
  and fake-ping jokers. When break or fake-ping jokers are in use, hunters must
  see players and supporters through mirrors. Spectators can see real trackers.
- **Alerts and proximity.** The server archives game alerts by audience and
  queues native pushes. Proximity can temporarily grant players and supporters
  read access to nearby peers' trackers and show anonymous location circles to
  hunters and spectators.
- **Drawings.** Drawing access follows session membership. Players, supporters,
  and hunters can opt into sharing within their role. Spectators have read-only
  access to their session's drawings.

## Web app

- The **Game** page shows role-specific status, requests, jokers, and timetable
  pings. Game alerts appear in a banner and a history drawer.
- Role and number badges identify participants on the map and in the device list.
  Device Setup writes the marker attributes, including the session ID used by
  the live map to hide caught participants and a member's own outgoing mirrors.
  Run Device Setup to add session markers to trackers set up before they existed.
- Multi-participant replay plays tracks from selected devices or groups on a
  shared timeline, with optional routes and points.

See the [OpenAPI contract](openapi.yaml) for endpoint and payload details and
[development instructions](AGENTS.md) for local setup, verification, and deployment.
