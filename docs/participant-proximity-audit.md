# Participant proximity audit — 2026-10-15

Scope: Traccar Tool, Traccar server, and the customized web app. The audit started
from server `279d0e33c`, Tool `a7a0b8c`, and web `216d1eb1`.

## Issues fixed

| Finding | Result |
| --- | --- |
| Temporary device permission also satisfied mutating API checks. Share tokens could outlive the original lease. | Commands, edits, position deletion, permission changes, and share tokens require permanent direct/group access. Temporary devices do not consume the permanent device quota. |
| A stale member could cause an overdue connected group to publish a smaller moving circle. | Suspended overdue edges remain in the graph. Affected circles freeze, expire without renewal, and resume without replaying consequence alerts. |
| A delayed location response could overwrite a newer fix and falsely separate participants. | Older fixes are ignored before updating monitoring state. |
| Out-of-range coordinates could be treated as fresh; numeric coordinate strings could crash circle calculation. | Invalid geographic ranges suspend evaluation; numeric strings are converted before calculating the center. |
| A missing or invalid session device could abort reconciliation for every session. | Its grants and circles are cleared while reconciliation continues for other sessions. |
| A delayed websocket packet could restore a peer after local lease expiry. | The browser retains known expiry deadlines until an authoritative refresh replaces them and purges late device, position, route, and selection updates. |
| Socket event/log delivery relied on cached recipients until the next cleanup tick. | Every device socket payload checks current authorization before delivery, including events and raw tracker logs. |
| A failed startup capability check could leave proximity suspended after connectivity recovered. | Enabled proximity retries capability checks every 30 seconds, remaining paused until the server check succeeds. |
| With zero access grace, catching/removing a participant could leave a retiring circle visible because its pair record was already gone. | Circle cleanup validates members independently of remaining pair records, including while capability checks are paused. |

## Verification

- Python 3.14: 1,397 Tool tests passed, including all eight relationship combinations,
  threshold equality, separate pair timers, merge/split alert deduplication,
  stale-group expiry/recovery, invalid GPS, and delayed fixes.
- Java 21: complete Gradle build passed (649 tests, 27 skipped), including database-backed read/write permission,
  direct/group permission preservation, invalid session cleanup, and API tests.
- Node 22: clean dependency installation, 125 unit tests, lint, and production build passed.
- Desktop/mobile proximity browser audit passed: count-only circles, audience isolation,
  offline expiry, failed refreshes, revoked caches, and delayed peer packets.
- Desktop and mobile Manager notification browser audits passed.
- Local Docker/MySQL proximity and notification integration audits passed, including
  server restart recovery. Both services are healthy; `/api/health` returns `OK`.

Only isolated fixture accounts and trackers are used by integration tests. No
native pushes are sent. Development volumes and existing data are preserved;
there are no production changes. Physical-phone background push and sound delivery
still require device verification.
