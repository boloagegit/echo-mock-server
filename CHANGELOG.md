# Changelog

All notable changes to Echo Mock Server will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/), and this project adheres to [Calendar Versioning](https://calver.org/) (`YYYY.MM.DD`).

## [Unreleased]

### Added
- Response templates now work for JMS replies too: `{{xPath request.body ...}}`, `{{jsonPath ...}}`, time, random and fake-data helpers, plus `{{request.headers.JMSCorrelationID}}`, `JMSMessageID`, `JMSType`, `JMSDestination` and the message's own properties. Templates are rendered in one shared step of request handling for every protocol (HTTP, SSE and JMS use the same code), so a new protocol gets them by mapping its request into the shared form. Replies without `{{` are sent unchanged at no extra cost
- JMS across several instances: with `echo.jms.source.type` set to `artemis` or `tibco` (plus `server-url`, `username`, `password`), every instance listens on the same queue (`echo.jms.queue`) of a shared broker such as the company ESB instead of its own embedded Artemis. The broker gives each message to only one instance, so load is shared and a stopped or crashed instance's messages are processed by the others; replies go to `JMSReplyTo` on that broker. The embedded broker and `echo.jms.port` are not started in this mode. Echo starts while the broker is down and reconnects every `reconnect-interval-ms` (default 5 seconds); System Settings shows the JMS source, broker address and queue, and whether JMS is receiving or reconnecting. Default `embedded` behaves as before
- Request Log traffic overview above the list, compact by default and expandable or collapsible (remembered per browser): requests over the last hour, 24 hours or 7 days as stacked bars of mock responses, forwards, unmatched requests and failed forwards, with request count, match rate, forwards, unmatched requests and estimated typical / slowest-5% response time, plus the most often unmatched requests. Clicking a bar lists only that period (a new time filter, also `from`/`to` on `GET /api/admin/logs`); "Show requests" lists one unmatched request in the range. Bars older than the earliest retained record are marked as having no data when the record limit removed them. New endpoint `GET /api/admin/logs/traffic`: counted in the database from a new covering index `idx_log_traffic` (created automatically on startup), about 0.2 MB of short-lived memory per computation regardless of the number of records, results shared for 5 seconds and computed one at a time, so many people opening the page run one query; nothing is queried while the panel is collapsed
- Rule checking order: the rule detail lists, in the order matching uses, the rules considered for the same requests (rules with conditions first, then the first rule without conditions as the default), including rules that apply to only some of those requests. Rules that can never be reached are badged in the list (the badge explains why), a "Never reached" filter, with their count, lists only them, and saving a rule warns when it can never be reached or when it makes another rule unreachable (with a link to the checking order): same conditions as an earlier rule, or after another rule without conditions. Only certain cases are reported; differently written but overlapping conditions are not judged. New read-only endpoints `GET /api/admin/rules/order-issues` and `GET /api/admin/rules/{id}/order`; rule list queries accept `unreachable=true`
- Request Log "Flow" tab (now the first tab of a record): the path a request took drawn as steps (received → rule matching → mock response, SSE stream or forward, with the branch not taken dimmed → reply), the failing step marked, and a time bar splitting matching, building the response or waiting for the forward target, and the rule's simulated delay. Request logs now also record the candidate rule count, whether the default rule without conditions was used, the simulated delay (which runs after the logged handling time), the SSE event count and the rules skipped because of their scenario state; older records show what they have
- Request Log match chain shows each evaluated rule's conditions as a tree: groups (all / any / NOT) with how many of their conditions passed, every condition with the value actually received, and the conditions that made the rule fail marked; passed groups start collapsed and the raw condition text is one click away. Stored per analysed entry as a size-capped `detailTree`; older records display as before
- The rule editor warns about equality conditions whose value starts with `=` (usually written as `==`) where the extra `=` is part of the expected value: JSONPath (`$.`), query and header conditions
- Advanced condition editor: the rule editor switches between the existing simple rows and a tree editor for `all` / `any` groups, NOT and the new operators, validates against the server while typing, and round-trips `echo.mock/v2` documents; the rule list and detail summarize structured conditions
- Structured match conditions (`matchSpec`): `all` / `any` / `not` groups with `exists`, `absent`, numeric `gt`/`gte`/`lt`/`lte`, `in` and `equalToJson` in addition to the existing operators, validated on save with the JSON path of any error; available through the REST API, JSON import/export and new `echo.mock/v2` documents (`spec.match`). `echo.mock/v1` is unchanged, and manifests of rules that use structured conditions are emitted as v2
- Anomaly-only HTTP/JMS/SSE diagnostics: zero supplemental file bytes for healthy traffic, one bounded error summary, separate 10 MiB/day / 50 MiB retained files, rate limits, expiring opt-in detail, and nullable Request Log correlation IDs
- Fault Injection — rules can simulate CONNECTION_RESET (close connection) or EMPTY_RESPONSE (return empty body) for HTTP and JMS
- Faker Handlebars helpers — randomFirstName, randomLastName, randomFullName, randomEmail, randomPhoneNumber, randomCity, randomCountry, randomStreetAddress, randomInt
- OpenAPI/Swagger import — upload spec to auto-generate mock rules with preview
- Declarative rule authoring — create or update rules from a validated JSON document while retaining the form editor
- Selectable HTTP and JMS forwarding profiles with application configuration fallback
- Durable SQLite WAL request-log spool so transient primary-database failures delay records instead of silently dropping them
- Verified H2-to-SQLite migration script with row counts, SHA-256 digests, integrity checks, and startup smoke tests
- Agent Framework with Log Agent and match chain analysis (near-miss / shadowed detection)
- Built-in account management — CRUD, enable/disable, password reset, self-registration
- Error Prone compile-time static analysis
- Request log body search and formatted preview with CodeMirror highlighting
- Forwarding observability in request logs, including the actual safe downstream target label
- One-click maintainer release workflow with validated CalVer tags, release artifacts, checksums, generated notes, and GHCR images

### Changed
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
- Priority field semantics reversed to higher-number-higher-priority
- Split HTTP/JMS rule caches for better isolation
- Extract Template Method pipeline for HTTP/JMS mock processing
- WebJar paths changed to version-agnostic (no more HTML edits on upgrade)
- HTTP downstream forwarding now uses Reactor Netty with bounded response buffering, connection-pool limits, and explicit timeout budgets
- Request-log persistence is handled by a single background consumer rather than flushing the primary database on request threads
- Scenario and bulk import/export user interfaces are controlled by feature flags and are disabled by default
- Desktop UI surfaces, rule editing, request details, settings, accessibility, and light/dark design tokens were aligned without changing the main navigation model
- Authenticated rule editors can read password-free forwarding target lists; target management remains administrator-only
- Request logs require authentication because details can include request data and downstream topology
- Spring Boot updated to 3.5.16 and the build aligned with the supported Gradle 8.14.5 line

### Removed
- Drag-and-drop priority ordering in the rule list (`ECHO_RULE_DRAG_SORT_ENABLED` / `echo.features.rule-drag-sort-enabled`, off by default) was removed. It sent only the new priority to the full-rule update API, which rejected it, so reordering never saved. Set priorities in the rule editor; the status API no longer returns `ruleDragSortEnabled`

### Fixed
- Multi-instance deployments: changes made on one instance now reliably reach the others within the sync interval (5 seconds by default). Before, another instance could keep serving outdated data for as long as it kept being requested: an edited response body (response edits were not announced at all), an edited HTTP forwarding profile, and rules removed by the nightly cleanup; changes made on an instance whose clock or time zone was behind were never picked up (events were read by time). Events are now read by id in commit order (an id skipped by a later-committing transaction is waited for up to 2 minutes), response, forwarding-profile and cleanup changes are announced, and each instance clears all these caches once after it could not poll for over 5 minutes. As a fallback for a missed notice, cached rules and forwarding profiles are re-read at least every 10 minutes (`echo.cache.rules.max-age-minutes`) and response bodies every 60 minutes (`echo.cache.body.max-age-minutes`). Also on a single instance, cache entries cleared inside a transaction are cleared again after it commits, so a request that read the old data just before the commit can no longer put it back in the cache
- Sustained traffic above the request-log write rate (1,000 per second by default) no longer fills the spool until mock requests stall: entries waiting beyond `max-records`, which the record limit would delete right after saving, are skipped (checkpoint advanced in the same transaction, then removed from the spool), so the newest records are kept. In a scaled test (write rate 100/s, 8 MB spool limit, 600 requests/s) requests had dropped to 100/s with a 9.4 s 99th percentile; they now hold 600/s at about 13 ms with the spool steady near 3 MB. Skipped entries count as dropped, with a warning at most every 30 seconds; below the write rate nothing is skipped
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
- SpotBugs mutable array warnings
- Remember Me validity now uses dedicated `echo.remember-me.validity` setting
- Rule matching performance — removed MatchChain/Detail construction in hot loop
- Dynamic-path cache churn and unnecessary JSON/XML parsing in the matching hot path
- Request-log spool startup when a macOS temporary-directory path resolves through a symbolic link
- Backup filename handling for root-like paths reported by static analysis
- Docker Compose health check now targets the administrative status endpoint
- Docker health checks send the required JSON accept header and include the curl runtime dependency
- H2 response identity sequences are realigned after restoring an older database snapshot
- JMS rule previews and request-log rule creation now use protocol-correct forwarding and match fields
- Updated or deleted JMS forwarding profiles retire their cached connections after active messages finish
- Request-log candidate snapshots and HTTP forwarding metrics are released when their profiles are no longer referenced

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
