# Changelog

All notable changes to Echo Mock Server will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/), and this project adheres to [Calendar Versioning](https://calver.org/) (`YYYY.MM.DD`).

## [Unreleased]

### Added
- Request-log monitoring distinguishes retention skips, handoff overflow, unconfirmed writes and storage retries. The spool reserves 512 MiB of free disk space by default and checks for recovery in the background; already-attempted writes retain their uncertain-outcome retry behavior.
- Separate anonymous liveness/readiness health probes expose status only. Readiness checks the database, local free space and existing JMS listener state; liveness remains independent of external outages. Probes never open an extra JMS connection. Docker uses readiness, checks the data mount's free space, forwards stop signals directly to Java, and Compose persists backups in a separate named volume and allows 60 seconds for shutdown. CI can be run manually on a branch before opening a PR and tests container recreation with persisted data/backups.
- ADMIN-only per-GC history and post-GC heap chart in Settings, retaining at most 512 events / 24 hours by default with idle housekeeping and explicit eviction/expiry counts. Reads never force GC. Resource-counter comparisons help narrow possible sources of growth; an isolated Windows/Linux HTTP/JMS observation harness checks heap, native memory, threads and cleanup, with opt-in class histograms for further investigation.
- Windows development now has a JDK 17 CI build from a checkout path containing spaces, alongside the existing Linux deployment checks. Java source compilation explicitly uses UTF-8; checkout line endings, TLS test tools, SQLite backup paths and symlink test privileges account for Windows. SQLite integration tests close their Spring-managed connection pools before deleting temporary databases. PowerShell development/configuration examples are documented, while deployment performance tools explicitly require Linux.
- SQLite migration and crash-check scripts explicitly close their database connections, releasing Windows file handles before publishing or cleaning up database files. Migration opens files with the write access Windows requires to flush data to disk before publication.
- SQLite online backups now pass destination paths directly to the driver, so paths containing spaces or apostrophes work without the driver's limited command parser. Snapshot integrity checks and atomic publication remain in place.

### Fixed
- HTTP forwarding decodes response chunks through a bounded per-response scratch buffer and appends characters in bulk, reducing temporary allocations while preserving charset handling, response limits and cancellation behavior.
- Async database request-log handoff batches stop at 16 MiB estimated task bytes or 500 entries, releasing large-body batches sooner. An admitted task larger than the batch budget is written alone; buffer limits, log contents, retries and JMS ACK/transaction behavior are unchanged.
- Native HTTP reads request bodies in bounded blocks and decodes them on workers, avoiding the per-byte receiver loop and an extra whole-body copy. Cumulative upload limits, charset handling, deadlines, disconnect cleanup and decoded-length headers are preserved; JMS transaction and acknowledgement logic is unchanged.
- Request-log spool reads reuse one payload query per nonempty batch instead of preparing it per row. Per-row byte admission, corruption handling, read order and statement cleanup are preserved; idle polls do not prepare an extra query.
- Artemis now uses the official 2.57.0 bulk large-message read fix, a consistent BOM and Jakarta embedded-server modules; Netty is aligned to 4.1.137. Message type, transactions, ACK/retry and synchronous persistence settings are preserved. See the [large-message validation](docs/artemis-large-message-validation-2026-10-18.md) for application comparisons, compatibility checks and limits; this does not establish TIBCO EMS performance.
- SQLite online backups pin a read snapshot while copying so continuous request-log commits do not repeatedly restart the backup. Writers continue through WAL; the snapshot is released before verification and publication, with connection-pool settings restored. WAL can temporarily grow during the copy.
- Disabling Echo JMS now also disables Spring Boot's idle embedded Artemis broker. Native HTTP workers retire after 60 seconds of idle time by default; busy work and JMS workers are unaffected.
- Editing an already-used shared response through a rule immediately invalidates its cached body for HTTP and JMS, including empty replacements. A managed entity is no longer compared with its own mutated contents to decide whether invalidation is necessary; detached metadata-only updates still retain the warm cache.
- Scheduled SQLite backups wait interruptibly for ongoing backup maintenance instead of losing that scheduled copy on a busy-lock error. Manual duplicate backups still report busy, housekeeping skips an active copy, and verified atomic publication and retention guards remain unchanged.
- Health probes return JSON for plain curl/Kubernetes requests with missing or wildcard `Accept` headers, while preserving the original binary fallback for other routes and honoring explicit response formats.
- JMS target factory creation and connection retirement no longer hold the map shared by all outbound profiles. A stalled provider affects its own target without blocking unrelated new targets; active-use retirement, independent reply queues and existing source transaction/provider retry policies are preserved.
- SQLite backup housekeeping runs independently of backup success. Completed backups have age, byte and file-count limits (defaults: 7 days, 10 GiB, 100 files), while retaining the latest verified recovery point. Admission checks preserve a 512 MiB free-space reserve and refuse copies that cannot fit safely; backup and cleanup operations do not overlap.
- Concurrent readers of an expired HTTP/JMS rule snapshot no longer trigger another database rebuild after a fresh snapshot has already been installed. The rebuild slot rechecks freshness and still hands off changes arriving during that check; a deterministic race test reproduces the previous extra build.
- Native HTTP receives request bodies without holding application workers and enforces its request deadline through queueing and response delivery. Stalled uploads and downloads release their workers, admission leases and buffers; queued expired work is removed. Resource monitoring exposes busy native workers separately from admitted requests.
- JMS forwarding and target connection tests preserve existing provider retry/failover settings by default. Optional `echo.jms.forward.bounded-waits-enabled=true` bounds Artemis connect/call/failover waits and EMS connection attempts; this changes failure outcomes and requires actual ESB/client validation before use. Source recovery policies are preserved; licensed EMS send/ACK/failover remains unverified.
- Scheduled orphan-response cleanup selects at most 100 IDs per batch and deletes without loading response bodies. Each batch commits separately and rechecks expiry and rule references, bounding response-cleanup memory and releasing SQLite's writer between batches. A failed later batch leaves earlier committed progress for the next run.
- Background jobs (cache sync, SQLite health checks, nightly cleanup and backup, audit cleanup and summary backfill, GC history housekeeping) now run on their own `echo-scheduled-` threads. Since native HTTP became the default they had been borrowing the `native-http-timer` threads that time mock delays and request timeouts, because that executor bean made Spring Boot skip both its own scheduler and the `applicationTaskExecutor` used by `@Async`. A long backup could therefore hold a timer thread. The timer pool is now reserved for native HTTP, and Spring Boot's scheduler is back, with 4 threads by default (`ECHO_SCHEDULING_POOL_SIZE`) so a slow nightly job does not delay the 5-second cache sync. Servlet mode uses the same settings instead of Spring's single default thread.
- Repeated clicks no longer repeat the action. While an enable/disable toggle, delete, extend, batch protect or delete, response import, account password reset or status change, issue resolve/reopen, or "make default" is still waiting for the server, further clicks on the same item are ignored. Before, a double-click on a rule toggle sent two "disable" requests, so it ended disabled, and a double-clicked password reset issued two temporary passwords, so the one shown first no longer worked.
- Enabling, disabling or protecting a rule that is already in that state no longer counts as a change. Before, it still bumped the rule's version, which gave anyone editing that rule a false "changed by someone else" conflict. It also rebuilt the rules and sent a multi-instance sync event. Batch endpoints now report only the rules actually changed, and the single-rule protect/unprotect endpoints still return 200 for a rule that is already in that state. A single enable/disable now bumps the version once instead of twice. Deleting rules that no longer exist no longer triggers a rebuild or sync. Detached response updates that preserve content and type retain their body cache; already-managed entities are conservatively invalidated to avoid stale replies.
- Saving a response that someone else changed since you opened it is now rejected instead of silently overwriting their edit, as rules already were (HTTP 409 `OPTIMISTIC_LOCK_CONFLICT`). The console sends the version it loaded and explains what happened. API callers that send no version keep the previous behavior.

### Changed
- Background request-log spool reads reuse a bounded Jackson text buffer to reduce large-body decoding allocations while preserving the JSON format, read budgets and JMS delivery behavior. The [controlled experiment](docs/request-log-spool-read-experiment-2026-10-18.md) reports isolated savings, inconsistent whole-JMS throughput results and the soak's failed thread-growth gate separately.
- SQLite request-log inserts use JDBC batching by default within the existing Spring transaction, including checkpoint and retention updates. Non-SQLite databases and existing-ID updates retain JPA; `ECHO_REQUEST_LOG_JDBC_BATCH_ENABLED=false` restores the previous SQLite insert path. Recording rate and retention defaults are unchanged.
- The audit log detail panel shows long values, such as response bodies, as a line-by-line comparison of only the changed lines with a few lines of context and a "show full text" switch. Text over 200 KB, or with more than 2,000 changed lines, falls back to the before/after view. Tag changes are listed as added and removed `key=value` pairs instead of two JSON boxes. A timeline shows the latest changes to the same rule or response, and "Only this item" (also used by the rule page's history action) filters the list by that exact rule or response instead of a text search.
- The audit log list now shows what each change was about without opening it: the path, queue or response name, HTTP/JMS, method, host and description, and up to three changed fields with their old and new values (for example "Priority 0 → 5"), or a line count for response bodies. The search box also matches paths, queue names and descriptions, a Rule/Response filter was added, entries are grouped by day, and on phones each entry is a card instead of a table row with hidden columns. The summary is computed once when an entry is written and stored in four small columns, so the list still never reads the before/after snapshots. Entries written before the upgrade are filled in when they are first shown and by a background job of at most 500 entries a minute, one entry at a time.
- The benchmark scripts moved from `docs/performance/` to `scripts/bench/`, keeping their layout, and the raw measurement files (about 2,300 text and JSON files) were removed from the repository to keep it small. The performance documents keep the summarized numbers and point to the scripts for reruns; rerun output under `scripts/bench/**/results/` is ignored by git.

## [v2026.10.16] — 2026-10-16

### Added
- Native HTTP/1.1 acceleration is enabled by default, sharing the existing mock pipeline while retaining Servlet handling for special requests and custom extensions. Configurable defaults bound request/response bodies to 10 MiB, active requests to 512 and estimated buffering to 128 MiB. Set `ECHO_HTTP_TRANSPORT=servlet` and restart to switch back; TLS, compression, observations and graceful shutdown remain supported.
- JMS forwarding reuses up to 8 idle sessions per target by default (`echo.jms.forward.session-cache-size=0` disables reuse), while keeping temporary reply queues and consumers independent for every request. Source transactions and redelivery semantics are unchanged.
- Startup uses WebJars Lite metadata instead of a full classpath scan, preserving versionless asset URLs. The scripts that reproduce the HTTP/JMS, startup, capacity and recovery measurements are included under `docs/performance/` (now `scripts/bench/`); the results are summarized in the performance documents.
- The README is now a short landing page in English and Traditional Chinese, with screenshots of the console, a one-minute first-mock example and a performance summary. The detailed sections moved into topic guides: `docs/install.md`, `docs/configuration.md`, `docs/rules.md`, `docs/jms.md` and `docs/api.md` (Chinese versions under `docs/zh-TW/`), with an index at `docs/README.md`.
- The hidden game (Echo Strike) has much more to play and look at. It still downloads only when it is opened, so ordinary page loads are unchanged apart from about 0.7 KB more in the trigger script.
  - **Stages:** three stages (STAGING, UAT, PRODUCTION), each with a mid-stage heavy enemy, its own background and a boss. The bosses are 404 (fades out and reappears elsewhere), 429 (fires a stream, then cools down with its weak point open and a "Retry-After" countdown) and 500 (three phases, plus two side turrets that can be shot off first). After the third stage the game loops, faster.
  - **Enemies:** HEAD swarms curl in from the side, OPTIONS turrets slide in and fire bursts, and TIMEOUT mines burst into a ring of bullets when their countdown runs out. They join the existing GET, POST, PUT, DELETE and PATCH, all redrawn with their own shapes.
  - **Items:** shield (absorbs one hit), MOCK drones (up to two, firing alongside the ship) and 1UP join the weapons and bombs.
  - **Scoring:** chains of quick kills raise a multiplier, and each stage ends with a summary of kills, longest chain, stage bonus and no-miss bonus.
  - **Unlimited continues:** losing the last life offers a 10-second continue. Z (or a tap) resumes in the same place with three lives and bombs. The score starts again from 0, and the score reached so far is kept as the high score.
  - **Look:** a more detailed ship that banks while moving, a white flicker when an enemy is hit, ring explosions, and larger, readable bullets. Holding Shift moves slowly and shows the hitbox. The game now keeps its own dark colours in the light theme, where it used to pick up colours that were too dark to see.
  - **Bomb:** X, as before. There is now also a BOMB button at the bottom right that works with a mouse or a tap (double-tap still works on touch screens). It shows the key and the bombs left, and says so when none are left. First-time players are told about it after the stage card, and get a reminder once when the screen fills with bullets. A mouse click can also start the game, resume it from pause, or take the continue.
- The hidden game can also be opened by clicking the Echo logo at the top left five times quickly, which works with a mouse or on a phone. The logo winks now and then as a hint (not while the tab is hidden or with reduced motion), and the help dialog's Quick Start tab ends with a one-line hint. All of these follow `ECHO_UI_EASTER_EGG`: with `false` there is no logo trigger, no wink and no hint.
- `docs/performance.md` collects the current performance results in one place: a comparison with WireMock 3.13.2 on the same machine, the request-log modes, what changed since April 2026, known bottlenecks with a CPU breakdown, startup options and how to rerun everything. The README performance section is now a short summary that links to it, and no longer lists two scripts that do not exist.
- `scripts/bench-vs-wiremock.sh` runs the Echo vs WireMock comparison with wrk, and `scripts/bench-vs-wiremock-summary.py` turns its results into tables
- `echo.jms.target.name` (environment variable `ECHO_JMS_TARGET_NAME`) names the JMS forward target configured in `application.yml`, for example `ESB-UAT`. The name is shown in the connection settings, in the rule editor and in the request log's forward target. When it is blank, the target is shown as `application.yml` as before
- SSE events in the rule editor are now a timeline instead of a wide table.
  - **Layout:** each event takes one line: when it is sent, its name, the start of its data, its size and the gap after the previous event. Clicking an event opens it for editing, one at a time; the first event opens with the editor and a newly added event opens with the cursor in its data. Alt + ↑ / ↓ moves to the previous or next event.
  - **Summary:** the header shows the number of events and the total length (per loop in loop mode). An error or abort event shows where the connection ends, and the events after it are marked as not sent.
  - **Delays:** each event's delay can be typed in milliseconds or seconds. Switching the unit keeps the same time; the stored value is always milliseconds, up to 10 minutes.
  - **Tools:**
    - Search finds events by data, name or ID.
    - "Set all delays" sets every event's delay at once.
    - "Paste or edit all" replaces all events from a JSON array or from text copied from a real stream (`event:` / `data:` / `id:`). It checks the same rules as the server, and Esc or Cancel changes nothing.
    - "Edit larger" opens one event's data in the full editor area.
  - **Raw stream:** the raw stream is shown only on request.
  - **Saving:** saving is blocked while an event has no data or an invalid delay. These were previously rejected by the server only after saving.
  - **Size:** closed events are plain text, so rules with hundreds of events stay responsive.
  - **Keyboard:** "Paste or edit all" and "Edit larger" put the cursor in the editor, so Esc returns to the list instead of closing the dialog. On narrow screens the tool buttons wrap instead of scrolling sideways.
  - **Saving with "Paste or edit all" open:** the save buttons first apply the pasted text, so it is no longer dropped when the dialog closes. If the text cannot be read, the editor stays open with the error and nothing is saved. The rule editor's save buttons stay enabled while it is open, so pasting into a new SSE rule (whose blank event alone cannot be saved) saves directly.
- The Responses page edits SSE responses with the same event list as the rule editor (timeline, milliseconds or seconds, search, set all delays, paste or edit all, edit larger) instead of the old table with a side preview. Responses have no loop switch, since looping is a rule setting. Saving with an event that has no data or an invalid delay opens that event and says which one to fix.
- SSE rules show a one-line summary in the rule details: the number of events, the total length and whether it loops or plays once (for example "48 events · 24.3 s total · Loops"). When an error or abort event ends the stream, the line says at which event the connection closes and how many later events are never sent. The raw stream preview there now also notes loop playback and the skipped events.
- Response templates now work for JMS replies too: `{{xPath request.body ...}}`, `{{jsonPath ...}}`, time, random and fake-data helpers, plus `{{request.headers.JMSCorrelationID}}`, `JMSMessageID`, `JMSType`, `JMSDestination` and the message's own properties. Templates are rendered in one shared step of request handling for every protocol (HTTP, SSE and JMS use the same code), so a new protocol gets them by mapping its request into the shared form. Replies without `{{` are sent unchanged at no extra cost
- JMS across several instances: with `echo.jms.source.type` set to `artemis` or `tibco` (plus `server-url`, `username`, `password`), every instance listens on the same queue (`echo.jms.queue`) of a shared broker such as the company ESB instead of its own embedded Artemis. The broker gives each message to only one instance, so load is shared and a stopped or crashed instance's messages are processed by the others; replies go to `JMSReplyTo` on that broker. The embedded broker and `echo.jms.port` are not started in this mode. Echo starts while the broker is down and reconnects every `reconnect-interval-ms` (default 5 seconds); System Settings shows the JMS source, broker address and queue, and whether JMS is receiving or reconnecting. Default `embedded` behaves as before
- Request Log traffic overview above the list, compact by default and expandable or collapsible (remembered per browser): requests over the last hour, 24 hours or 7 days as stacked bars of mock responses, forwards, unmatched requests and failed forwards, with request count, match rate, forwards, unmatched requests and estimated typical / slowest-5% response time, plus the most often unmatched requests. Clicking a bar lists only that period (a new time filter, also `from`/`to` on `GET /api/admin/logs`); "Show requests" lists exactly that unmatched request (same method and path, new exact `method`/`path` filters on `GET /api/admin/logs`) in the range, so the list matches the count. Bars older than the earliest kept record are marked as having no kept records (possibly removed by the record limit), not as zero traffic. New endpoint `GET /api/admin/logs/traffic`: counted in the database from a new covering index `idx_log_traffic` (created automatically on startup), about 0.2 MB of short-lived memory per computation regardless of the number of records, results shared for 5 seconds and computed one at a time, so many people opening the page run one query; nothing is queried while the panel is collapsed
- Rule checking order: the rule detail lists, in the order matching uses, the rules considered for the same requests (rules with conditions first, then the first rule without conditions as the default), including rules that apply to only some of those requests. Rules that can never be reached are badged in the list (the badge explains why), a "Never reached" filter, with their count, lists only them, and saving a rule warns when it can never be reached or when it makes another rule unreachable (with a link to the checking order): same conditions as an earlier rule, or after another rule without conditions. Only certain cases are reported; differently written but overlapping conditions are not judged. New read-only endpoints `GET /api/admin/rules/order-issues` and `GET /api/admin/rules/{id}/order`; rule list queries accept `unreachable=true`
- Request Log "Flow" tab (now the first tab of a record): the path a request took drawn as steps (received → rule matching → mock response, SSE stream or forward, with the branch not taken dimmed → reply), the failing step marked, and a time bar splitting matching, building the response or waiting for the forward target, and the rule's simulated delay. Request logs now also record the candidate rule count, whether the default rule without conditions was used, the simulated delay (which runs after the logged handling time), the SSE event count and the rules skipped because of their scenario state; older records show what they have
- Request Log match chain shows each evaluated rule's conditions as a tree: groups (all / any / NOT) with how many of their conditions passed, every condition with the value actually received, and the conditions that made the rule fail marked; passed groups start collapsed and the raw condition text is one click away. Stored per analysed entry as a size-capped `detailTree`; older records display as before
- The rule editor warns about equality conditions whose value starts with `=` (usually written as `==`) where the extra `=` is part of the expected value: JSONPath (`$.`), query and header conditions
- Advanced condition editor: the rule editor switches between the existing simple rows and a tree editor for `all` / `any` groups, NOT and the new operators, validates against the server while typing, and round-trips `echo.mock/v2` documents; the rule list and detail summarize structured conditions
- Structured match conditions (`matchSpec`): `all` / `any` / `not` groups with `exists`, `absent`, numeric `gt`/`gte`/`lt`/`lte`, `in` and `equalToJson` in addition to the existing operators, validated on save with the JSON path of any error; available through the REST API, JSON import/export and new `echo.mock/v2` documents (`spec.match`). `echo.mock/v1` is unchanged, and manifests of rules that use structured conditions are emitted as v2
- Anomaly-only HTTP/JMS/SSE diagnostics: zero supplemental file bytes for healthy traffic, one bounded error summary, separate 10 MiB/day / 50 MiB retained files, rate limits, expiring opt-in detail, and nullable Request Log correlation IDs

### Changed
- Dated benchmark reports and the console-redesign hand-off notes moved to `docs/archive/`. CONTRIBUTING lists all three test suites (Gradle, `node --test`, Python), the current source layout and the tech stack. SECURITY no longer mentions the H2 console, which was removed. The H2 migration hint at startup points to `docs/install.md`.
- Many HTTP rules on the same host and method no longer slow down every request to that host. Before, each request compared its path with every rule of its host and method in turn. Now rules with an exact path are looked up directly, and only `*` and `/x/*` rules are still compared one by one; the order in which rules are tried is unchanged. With 2,000 GET rules on one host and request logging off, requests per second went from 10,008 to 13,178 for a fixed JSON reply (8 connections), from 8,835 to 11,955 for a template, and from 8,986 to 11,616 for one of the 2,000 rules, which is about the same as with 4 rules. Path patterns are now defined in one place (`HttpPathPattern`) shared by matching, the rule snapshot and the rule-order check
- Mock replies no longer wait for their request log to be written. A log entry now waits in memory and is written to the spool in batches by a background thread. Before, every reply waited for its own entry to be committed to disk, which capped simple mocks at about 2,000 replies per second with 8 connections. Measured on the same machine (requests per second, before → after): fixed JSON 1,170 → 7,700 with 4 connections, 2,170 → 10,960 with 8 and 4,720 → 15,260 with 20; templates 2,050 → 9,200; 100 KB XML 990 → 3,220. After the run the process size is unchanged (about 1.2 GB with a 1 GB heap) and the heap after a full collection is lower (about 58 MB instead of about 175 MB). When logs arrive faster than they can be written, the oldest waiting entries are dropped so the newest are kept: in that benchmark about 4% of the entries were dropped before reaching the spool. The rest reached the spool, and as before only the newest `echo.request-log.max-records` (10,000) end up in the database. A warning gives the number dropped (the first right away, then at most every 30 seconds), and the log agent status counts them. Waiting entries, including the batch being written, are limited by `echo.request-log.buffer.max-entries` (default 20,000) and `echo.request-log.buffer.max-mb` (default 64), so a burst of large bodies cannot exhaust memory. Entries still waiting are lost if the process stops unexpectedly; a normal shutdown writes them out first, for up to 5 seconds. In this mode the spool commits with SQLite `synchronous=NORMAL`: a crash of Echo loses nothing already committed, but a power or operating-system failure can lose the last commits. Set `echo.request-log.wait-for-write=true` to keep the previous behaviour: each reply waits for its log, the spool fsyncs every commit, and a reply fails with 503 (a JMS message is rolled back) when the log cannot be written
- Changing rules no longer makes mock requests wait. After a rule or response change, the rule list is rebuilt on a background thread while requests keep using the previous list; before, every request arriving during the rebuild waited for it (about 30–80 ms with 2,000 rules, longer with a slow database). The admin request that made the change still answers only once the new list is in use, so a mock request sent right after saving sees the change; a batch import waits once instead of once per rule. If a rebuild fails, the previous rules stay in use and the rebuild is retried. The rule list is also rebuilt every `echo.cache.rules.max-age-minutes` (default 10) in the background, and `echo.cache.rules.change-wait-ms` (default 5000) limits how long a change waits for the new list
- With several instances, an instance no longer clears its own caches again when it reads back the change events it published itself (it already cleared them when making the change), so each change rebuilds the rule list once instead of up to three times. The "Cleared all local caches" log line now gives the real reason: the scheduled check after startup or after cache sync resumes, or an event id gap that was too large to track. Before, it always blamed an id gap
- SSE event delays can be up to 10 minutes per event (was 30 seconds; larger values were silently lowered to 30 seconds and now to 10 minutes). The editors accept up to 600000 ms. A whole sequence, including the rule delay, is still limited to 24 hours
- Templates with several `{{xPath}}` or `{{jsonPath}}` fields parse the request body once per response instead of once per field, and reuse compiled XPath expressions (up to 256 characters, 64 per thread). Measured per response with 5 fields: JSON 50 KB 948 → 190 µs, XML 50 KB 7.1 → 4.6 ms, XML 2 KB 457 → 377 µs; results are unchanged, including `append()`, which still works on its own copy. Most of the remaining XML time is the `//...` search through the whole document; absolute paths such as `/order/header/no` are much faster (XML 50 KB, 5 fields: about 0.7 ms)
- `GET /api/admin/rules` (every rule in one response) and `GET /api/admin/responses` (every response with its body) now require login. Their size grows with the data, so repeated anonymous calls could exhaust memory. The UI uses the paged `/rules/page` and `/responses/summary`, which stay open, as do single-rule and single-response reads; scripts calling the two full lists must send credentials
- XML body conditions behave the same on HTTP and JMS, whichever way the body is parsed: simple paths (`Status`, `Order/Status`, `//Order/Status`, `/Order/Status`, `//Item/@sku`) match element and attribute local names (an unprefixed name also matches prefixed elements), relative paths match by path suffix, and `==` is treated as `=`. Before, these depended on whether JMS could stream the message, so a rule's result could change when an unrelated rule was added; HTTP never matched relative paths. Advanced conditions' simple `xpath` selectors follow the same rules. `scripts/check-xml-condition-changes.py` lists the rules on a running server that use the affected syntax (read-only)
- Internal: the admin API controller is split by area (system status, rules, rule documents, rule state, import/export, responses, request logs, audit, scenarios) with rule saving shared in `RuleEditService`; every `/api/admin` URL, parameter and response is unchanged
- Request-log spool records omit empty fields, saving about a hundred bytes per record; existing spool files are read as before
- Body preparation is decided once from the candidate rules' conditions for HTTP and JMS alike: no parsing when no condition reads the body, a single streaming scan without a DOM when every XML condition uses a simple path (now also for advanced `xpath` conditions and HTTP), otherwise one shared parse. A message that cannot be streamed (for example an undeclared namespace prefix) falls back to normal parsing instead of being treated as plain text
- Condition matching runs on the new `com.echo.core.match` engine. Existing string conditions (`bodyCondition`, `queryCondition`, `headerCondition`) keep their exact semantics and match-chain details through a compatibility layer verified by differential tests against the original implementation; stored rules need no changes. The one intentional difference: an expected value consisting of a single `"` is compared literally instead of failing the request with HTTP 500
- **Breaking:** H2 is no longer supported or bundled. SQLite is the default database (`./data/mockdb.sqlite`, inside the Docker `/app/data` volume); the `sqlite` profile keeps `./mockdb.sqlite`. Echo refuses to start with an H2 profile/URL or with unmigrated legacy `mockdb.mv.db` data; convert with `scripts/migrate-h2-to-sqlite.py`, which now runs from its own Gradle source set
- HTTP/JMS rule candidates come from immutable per-protocol snapshots invalidated by the existing rule-cache eviction paths; one shared rule selector replaces four copies of the selection loop
- Request handling is split into pluggable pieces without behaviour changes: responders (fault, forward, mock) produce the response plan, SSE playback lives in `protocol.http.sse` instead of the HTTP controller, and request logging receives a single `ServeEvent` through `ServeEventListener` (additional listeners cannot affect the response; request-log failures still return 503 or redeliver JMS)
- Rule list queries and multi-instance cache invalidation read per-protocol descriptors from the protocol registry instead of hard-coded HTTP/JMS branches; cache event names are unchanged
- Tests run on SQLite (one temporary database per Spring context) instead of embedded H2
- The test suite runs test classes in parallel JVM forks (half the CPU cores; `-PtestForks=1` for one JVM) and no longer waits on BCrypt at production strength, a 30 s JMS reply timeout or a 40 s SSE sequence: `./gradlew test` drops from about 330 s to about 190 s with the same 1,836 tests. Integration-test cleanup now fails the test when deleting data fails instead of leaving it to later tests
- System Settings now separates overview, resource details, forwarding targets, data/backups, and service configuration; shared target tables and six metric disclosures retain manual-refresh behavior

### Removed
- Drag-and-drop priority ordering in the rule list (`ECHO_RULE_DRAG_SORT_ENABLED` / `echo.features.rule-drag-sort-enabled`, off by default) was removed. It sent only the new priority to the full-rule update API, which rejected it, so reordering never saved. Set priorities in the rule editor; the status API no longer returns `ruleDragSortEnabled`

### Fixed
- Request logging no longer rejects a log that arrives right after a failed write was abandoned. The "temporarily unavailable" state is now cleared, and the batch's reserved capacity released, before the waiting caller is told about the result, so its next log is accepted and sees the freed capacity. Before, a log sent in that short gap could be turned away.
- The rule examples in the docs used field names the API ignores or rejects. JMS rules take the queue name in `matchKey` (not `queueName`), and the reply status is `status` (not `httpStatus`).
- Request logging keeps working after the JVM runs out of memory once. Before, an `OutOfMemoryError` could end the thread that writes request logs to the local spool, or the one that moves them into the database, for good: no new request logs appeared until a restart, and with `echo.request-log.wait-for-write=true` every reply then waited forever. Both threads now give up only the batch they were working on, log the error and continue
- The embedded JMS broker no longer runs out of memory when a client creates a new temporary reply queue for every request on one long-lived connection and never deletes it. Each such queue kept about 50 KB of heap until the client disconnected, so about 7,000 of them filled a 512 MB heap. Temporary queues that have had no consumer for `echo.jms.temporary-queue-idle-delete-ms` (default 10000, `0` turns this off) are now deleted by the broker, which checks every 5 seconds. Queues a client is still listening on are kept, a client that later listens on a deleted queue gets it recreated automatically, and other queues are not affected.
- SSE rules and their responses:
  - A response created by the rule editor for an SSE rule is now marked as SSE. The editor no longer warns that the rule's own response is "not SSE", and the Responses page SSE filter finds it.
  - Responses stored with the older `SSE_EVENTS` value are also treated as SSE. They are found by the SSE filter, left out of the general filter, and their events are checked on save. They are stored as `SSE` from then on.
  - Changing the events of an existing response from the rule editor no longer replaces the response's description with an empty one.
  - Those changes are now validated first and saved in the same transaction as the rule. Before, the response was written before the rule was checked, so a rule that failed to save still changed the shared response.
  - When the response is used by more than one rule, the SSE editor now shows how many rules share it.
  - `PUT /api/admin/responses/{id}` without a `description` keeps the existing one.
- SSE: eight or more clients that stay connected but stop reading no longer freeze every other SSE stream. Before, each such client blocked one of the eight playback threads indefinitely, so in a test a stream sending every 100 ms received nothing for 32 seconds while 12 such clients were connected, and the stuck connections were never closed. Writes now run on separate on-demand threads (one per stream at most, up to 256), and a write that has not finished after `echo.sse.stalled-write-timeout-ms` (default 2 minutes) closes only that connection; pauses between events do not count. With 12 such clients the stream keeps its 100 ms rhythm; with 260 (above the 256 limit) other streams pause until the stuck connections are closed instead of being stopped (measured with a 30-second timeout). Limits: a client that keeps reading but needs more than the timeout for a single event is also closed, and a closed connection's request objects (about 50 KB) stay in memory until the stream's own timeout (24 hours for looping rules); see docs/sse-mock.md
- Multi-instance deployments: changes made on one instance now reliably reach the others within the sync interval (5 seconds by default). Before, another instance could keep serving outdated data for as long as it kept being requested: an edited response body (response edits were not announced at all), an edited HTTP forwarding profile, and rules removed by the nightly cleanup; changes made on an instance whose clock or time zone was behind were never picked up (events were read by time). Events are now read by id in commit order (an id skipped by a later-committing transaction is waited for up to 2 minutes), response, forwarding-profile and cleanup changes are announced, and each instance clears all these caches once after it could not poll for over 5 minutes. As a fallback for a missed notice, cached rules and forwarding profiles are re-read at least every 10 minutes (`echo.cache.rules.max-age-minutes`) and response bodies every 60 minutes (`echo.cache.body.max-age-minutes`). Also on a single instance, cache entries cleared inside a transaction are cleared again after it commits, so a request that read the old data just before the commit can no longer put it back in the cache
- Sustained traffic above the request-log write rate (1,000 per second by default) no longer fills the spool until mock requests stall: entries waiting beyond `max-records`, which the record limit would delete right after saving, are skipped (checkpoint advanced in the same transaction, then removed from the spool), so the newest records are kept. In a scaled test (write rate 100/s, 8 MB spool limit, 600 requests/s) requests had dropped to 100/s with a 9.4 s 99th percentile; they now hold 600/s at about 13 ms with the spool steady near 3 MB. Skipped entries count as dropped, with a warning at most every 30 seconds; below the write rate nothing is skipped
- The warning for skipped request-log entries now reports every skipped entry. Before, entries skipped after the latest warning were only logged if another skip happened 30 or more seconds later, so the end of a burst was never logged (a test run logged 867 while about 22,000 had been skipped). The total waiting to be logged is now written once 30 seconds have passed even with no further skips, and when the application stops
- Benchmark downstream stub (`scripts/perf-test-downstream.py`): the listen backlog is raised from Python's default of 5 to 128. When 40 forwards reconnected at once, the kernel dropped the extra connections and retried after 1 second, so benchmarks showed Echo connect failures at about 1 s and 3-second read timeouts ending at about 4 s. With the larger backlog the 3-second timeout ends at 3.05–3.10 s and no connections are dropped
- Benchmark downstream stub: responses no longer wait ~40 ms on reused connections. The stub wrote headers and body separately without TCP_NODELAY, so every response after the first on a keep-alive connection waited for the client's delayed ACK (measured 42 ms vs 0.4 ms). Forwarding benchmarks through the stub were inflated by this, not by Echo
- On external databases (PostgreSQL, MySQL, MariaDB, SQL Server, Oracle) a single request with an over-long path, method, `X-Original-Host` or scenario value, or a NUL character in a body, no longer stops request logging: such values are shortened to the column size (NUL removed) before saving, and if the database still rejects a row, that one row is skipped with a warning and counted as dropped while the rest of the batch is saved. Before, the whole batch was retried forever and logs piled up in the spool until requests were refused. Database outages are still retried without skipping anything. SQLite was not affected
- Saving a rule in the editor no longer silently overwrites a change someone else made while it was open: `PUT /api/admin/rules/{id}` with an outdated `version` returns 409 `OPTIMISTIC_LOCK_CONFLICT` (with `currentVersion`), and the editor asks whether to load the latest version or keep the unsaved changes. The editor now always opens the latest rule, and enable/disable, protect and extend also advance the version so a form opened before them cannot undo them. Callers that send no `version` keep the previous behavior
- `GET /api/admin/rules/page` (and the group endpoints) no longer fail with HTTP 500 when called without a `sort` parameter
- The Request Log match chain now shows the pass/fail details of near-miss and skipped entries recorded without a condition tree; the page read a field the server never sent
- Opening an existing rule in the editor and saving it no longer changes or drops conditions: simple-mode condition rows, declarative documents and v1 document validation now split conditions exactly like matching does (first `=`, so `a=x!=y` is `a` equals `x!=y`), keep the leading `//` of XPath fields, and show conditions that cannot be represented as rows (for example one without an operator) unchanged in advanced mode instead of deleting them
- SSE zero-delay callback ownership race that could cancel the following delayed event; late future registration retains timeout/disconnect cleanup
- Request-log proxy error values are bounded to the existing 255-character column without altering transport responses
- `echo.matcher.max-field-value-length` is now applied; Spring previously used the no-argument constructor and ignored it
- Rule-cache rebuilds no longer send one `IN` list with every response ID (O(n²) on H2; over parameter limits on SQL Server/Oracle); with 20,000 rules the first request after a rule change dropped from 1.3 s to about 0.17 s
- Regex timeout protection reads the clock every 256 characters instead of on every character (10k-character values: 717 µs → 74 µs)
- The default Docker database now lives in the persisted `/app/data` volume
- Forwarding target dialogs now place and contain keyboard focus, support Escape, and restore the launch control; Settings action menus remain within the viewport
- JMS forwarding documentation now reflects configuration-first default selection and distinguishes it from automatic failover

## [v2026.09.12] — 2026-09-12

No changelog entries were recorded for this release. See the [release notes](https://github.com/boloagegit/echo-mock-server/releases/tag/v2026.09.12) for the pull requests it contains.

## [v2026.09.06] — 2026-09-06

No changelog entries were recorded for this release. See the [release notes](https://github.com/boloagegit/echo-mock-server/releases/tag/v2026.09.06) for the pull requests it contains.

## [v2026.08.22] — 2026-08-22

### Added
- Forwarding observability in request logs, including the actual safe downstream target label

### Changed
- Authenticated rule editors can read password-free forwarding target lists; target management remains administrator-only
- Request logs require authentication because details can include request data and downstream topology

### Fixed
- H2 response identity sequences are realigned after restoring an older database snapshot
- JMS rule previews and request-log rule creation now use protocol-correct forwarding and match fields
- Updated or deleted JMS forwarding profiles retire their cached connections after active messages finish
- Request-log candidate snapshots and HTTP forwarding metrics are released when their profiles are no longer referenced

## [v2026.08.13] — 2026-08-13

No changelog entries were recorded for this release. See the [release notes](https://github.com/boloagegit/echo-mock-server/releases/tag/v2026.08.13) for the pull requests it contains.

## [v2026.08.12] — 2026-08-12

### Added
- Declarative rule authoring — create or update rules from a validated JSON document while retaining the form editor
- Selectable HTTP and JMS forwarding profiles with application configuration fallback
- Durable SQLite WAL request-log spool so transient primary-database failures delay records instead of silently dropping them
- Verified H2-to-SQLite migration script with row counts, SHA-256 digests, integrity checks, and startup smoke tests
- One-click maintainer release workflow with validated CalVer tags, release artifacts, checksums, generated notes, and GHCR images

### Changed
- HTTP downstream forwarding now uses Reactor Netty with bounded response buffering, connection-pool limits, and explicit timeout budgets
- Request-log persistence is handled by a single background consumer rather than flushing the primary database on request threads
- Scenario and bulk import/export user interfaces are controlled by feature flags and are disabled by default
- Desktop UI surfaces, rule editing, request details, settings, accessibility, and light/dark design tokens were aligned without changing the main navigation model
- Spring Boot updated to 3.5.16 and the build aligned with the supported Gradle 8.14.5 line

### Fixed
- Dynamic-path cache churn and unnecessary JSON/XML parsing in the matching hot path
- Request-log spool startup when a macOS temporary-directory path resolves through a symbolic link
- Backup filename handling for root-like paths reported by static analysis
- Docker Compose health check now targets the administrative status endpoint
- Docker health checks send the required JSON accept header and include the curl runtime dependency

## [v2026.05.05] — 2026-05-05

### Added
- Fault Injection — rules can simulate CONNECTION_RESET (close connection) or EMPTY_RESPONSE (return empty body) for HTTP and JMS
- Faker Handlebars helpers — randomFirstName, randomLastName, randomFullName, randomEmail, randomPhoneNumber, randomCity, randomCountry, randomStreetAddress, randomInt
- OpenAPI/Swagger import — upload spec to auto-generate mock rules with preview
- Agent Framework with Log Agent and match chain analysis (near-miss / shadowed detection)
- Built-in account management — CRUD, enable/disable, password reset, self-registration
- Error Prone compile-time static analysis
- Request log body search and formatted preview with CodeMirror highlighting

### Changed
- Priority field semantics reversed to higher-number-higher-priority
- Split HTTP/JMS rule caches for better isolation
- Extract Template Method pipeline for HTTP/JMS mock processing
- WebJar paths changed to version-agnostic (no more HTML edits on upgrade)

### Fixed
- SpotBugs mutable array warnings
- Remember Me validity now uses dedicated `echo.remember-me.validity` setting
- Rule matching performance — removed MatchChain/Detail construction in hot loop

## [v2026.04.02] — 2026-04-02

### Added
- SSE (Server-Sent Events) streaming support with editable event sequences and live preview
- Response management — independent response entities, multiple rules can share one response
- Rule protection — mark rules as protected to prevent automatic cleanup
- Rule extension — extend retention period for rules/responses
- Orphan response cleanup with remaining-days display and one-click purge
- H2 database auto-backup (scheduled, on-shutdown, manual trigger)
- Excel import — batch import rules via Excel with downloadable template
- Header condition matching support
- Dynamic templates — WireMock-style Handlebars engine with JSONPath/XPath
- Rule testing from admin UI
- Dark/Light theme toggle
- Frontend i18n (English + Traditional Chinese)
- Skeleton loading effects and v-cloak anti-flash
- Group view with progressive rendering
- Request log expandable details with match chain visualization
- Near-miss analysis — show expected vs actual values on condition mismatch
- SpotBugs static analysis integration
- Docker support (Dockerfile + docker-compose)
- GitHub CI workflow, issue templates, PR template
- Accessibility improvements (WCAG contrast, aria-labels)

### Changed
- Frontend componentized — Vue 3 Composables, split into 11 composables
- Protocol Handler refactored to Strategy Pattern
- MockRule split into HttpRule and JmsRule entities
- Rule ID changed from Long to String (UUID)
- Request log default storage changed to database mode
- Cache synchronization mechanism for multi-instance deployment
- XPath matching with namespace-agnostic support and XXE protection
- Async delay implementation — no longer blocks Undertow threads

### Performance
- XPath expression caching — 14x faster than WireMock at 2,000 rules
- HikariCP connection pool tuning, RestTemplate optimization
- Pre-parse body for batch matching
- Bounded caches and N+1 query fixes
- 4K+ RPS for JSON matching

### Fixed
- JMS forwarding used source queue instead of target queue
- `@Cacheable` self-invocation causing cache miss in HttpRuleService and JmsRuleService
- Condition parsing — find first `=` then check previous char
- Audit records showing no changes due to JPA persistence context
- Non-HTTPS clipboard copy failure

## [v2026.01.08] — 2026-01-08

### Added
- Initial release of Echo Mock Server
- Dual-protocol support — HTTP REST API and JMS (Artemis)
- Condition matching — Body (JSON/XML), Query, Header
- Proxy forwarding to original host when no rule matches
- Web UI with responsive design
- Admin/User role separation with LDAP authentication
- Rule group management with batch export/import/delete
- Request logging with memory and database storage modes
- Audit trail for rule changes
- Real-time request statistics
- Configurable mock rule storage (file/database)
- Cache invalidation service for multi-instance sync
- JMS ESB forwarding (TIBCO EMS support)
- Feign Mock Interceptor for client-side integration
- Embedded H2 database, zero external dependencies
- Intranet-friendly — all frontend assets via WebJars (no CDN)
