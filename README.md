# Echo Mock Server

![Java 17](https://img.shields.io/badge/Java-17-orange) ![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-green) ![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)

[中文版 README](README_zh-TW.md)

An enterprise-grade dual-protocol mock server supporting HTTP and JMS, designed for simulating API responses in development and testing environments.

## Features

- **Dual Protocol Support** – HTTP REST API and JMS (Artemis) message queues
- **JMS Proxy** – Forwards to TIBCO EMS or Artemis ESB when no matching rule is found
- **Condition Matching** – Returns different responses based on Body (JSON/XML), Query, and Header conditions
- **Tag-Based Organization** – Classify rules with JSON tags (`key:value`), batch enable/disable by tag
- **Response Management** – Manage response content independently; multiple rules can share a single response, with export/import support
- **SSE Streaming** – Server-Sent Events support with editable event sequences, loop modes, and live preview ([contract and playback](docs/sse-mock.md))
- **Dynamic Templates** – WireMock-style Handlebars template engine with conditionals, loops, JSONPath/XPath
- **Proxy Forwarding** – Automatically forwards to the original host when no matching rule is found
- **Visual Management** – Dark/Light theme Web UI with responsive design (RWD)
- **Batch Operations** – Optional export/import for rules and responses plus batch delete (ADMIN only; import/export disabled by default)
- **Excel Import** – Optional batch import via Excel with a downloadable template (disabled by default)
- **Audit Trail** – Track rule change history with automatic cleanup of expired records
- **Statistics & Monitoring** – Real-time request statistics and hit rate tracking with auto-refresh and idle detection
- **Measured Performance** – Reproducible JSON/XML benchmarks with explicit workload, latency, and error counts
- **Access Control** – Admin/User role separation with LDAP authentication support and built-in account management
- **Built-in Accounts** – Account CRUD, enable/disable, password reset, forgot password, self-registration
- **Remember Me** – Long-lived login sessions, synced with session timeout (default 180 days)
- **Rule Protection** – Mark rules as protected to prevent automatic cleanup
- **Rule Extension** – Extend retention period for rules/responses to avoid scheduled cleanup
- **Orphan Cleanup** – Detect and remove orphan responses not used by any rule
- **Auto Backup** – Scheduled SQLite backup, shutdown backup, and manual trigger
- **Stateful Scenarios** – Optional WireMock-style state machines for multi-step workflows (disabled by default)
- **Fault Injection** – Choose Fault Injection as a rule mode to simulate connection resets and empty responses for resilience testing
- **OpenAPI Import** – Optional preview/import of OpenAPI 3.x or Swagger 2.x JSON/YAML specifications (disabled by default)
- **Faker Data** – Built-in name, email, phone, address, and integer template helpers
- **Rule Testing** – Test rule matching directly from the admin UI
- **Static Analysis** – SpotBugs code analysis
- **Database Profiles** – SQLite (default), PostgreSQL, MySQL, MariaDB, SQL Server, and Oracle profiles
- **Intranet Friendly** – Frontend uses WebJars, no CDN required
- **Environment Identification** – Protocol aliases and environment labels for easy multi-environment deployment

## Quick Start

### Prerequisites

- Java 17+
- Gradle 8.14+ (or use the included wrapper)

### Start the Server

```bash
# Development mode
./gradlew dev

# Default mode (login required: admin/admin)
./gradlew bootRun

# Build JAR
./gradlew bootJar
java -jar build/libs/echo-server-*.jar
```

For a packaged Linux/macOS deployment, `start-echo.sh` also verifies a writable
SQLite temporary directory and applies private file permissions before Java starts.
Copy the built JAR beside the script as `echo.jar`, or set `ECHO_JAR_PATH`, then
pass normal Spring arguments:

```bash
./start-echo.sh
```

`SQLITE_TMPDIR` can override the default temporary path. This setting does not
change the configured database profile.

On a Windows development machine, run the equivalent commands in PowerShell:

```powershell
.\gradlew.bat dev
.\gradlew.bat bootRun
.\gradlew.bat bootJar
java -jar (Get-ChildItem build\libs\echo-server-*.jar | Select-Object -First 1).FullName
```

### Database profiles

The supported profiles are:

| Profile | Database | Current Docker smoke-test target |
|---------|----------|----------------------------------|
| no database profile (default) | SQLite WAL file `./data/mockdb.sqlite` | SQLite 3.x via the Xerial JDBC driver |
| `sqlite` | SQLite WAL file `./mockdb.sqlite` (path kept for existing deployments) | SQLite 3.x via the Xerial JDBC driver |
| `postgresql` | PostgreSQL | PostgreSQL 17 |
| `mysql` | MySQL | MySQL 8.4 |
| `mariadb` | MariaDB | MariaDB 11.8 |
| `sqlserver` | SQL Server | SQL Server 2022 |
| `oracle` | Oracle | Oracle Free 23 |

The listed versions are reproducible Docker smoke-test baselines, not a promise that every older or newer vendor release is certified. The Hibernate version used by Echo supports Oracle Database 19c or newer; older Oracle releases are not covered.

Use exactly one database profile for each process. `dev` and `test` are non-database profiles and may be combined with one database profile. If no database profile is active, SQLite is selected and the database file is `./data/mockdb.sqlite` (the Docker volume mount point). Startup rejects multiple database profiles and rejects a JDBC URL whose vendor does not match the selected profile.

Every supported path is currently documented and tested as starting with a new empty database. The profiles use Hibernate `ddl-auto=update` for convenience; this is not a general migration system and does not automatically move existing data between SQLite and an external database. The H2-to-SQLite utility (see [Migrating from H2](#migrating-from-h2)) is a separate, SQLite-specific offline tool, not a general multi-database converter. Review its table and version compatibility, take an independent backup, and validate the result before using it. For an existing production database, use a DBA/platform-controlled, versioned migration and then set `SPRING_JPA_HIBERNATE_DDL_AUTO=validate`.

### Migrating from H2

H2 is no longer supported and is not bundled in `echo-server.jar`. Echo refuses to start with the `h2` profile or a `jdbc:h2:` URL, and it also refuses to start when the legacy H2 data file `mockdb.mv.db` is present in the working directory but the SQLite database has not been created yet, so an upgrade never silently starts with an empty database.

Convert existing data while Echo is stopped:

```bash
python3 scripts/migrate-h2-to-sqlite.py --h2-path ./mockdb
./gradlew bootRun   # SQLite is the default; the migrated file is ./data/mockdb.sqlite
```

The script never modifies the H2 files, verifies row counts and per-table digests, smoke-tests Echo against the staged database, and only then publishes `./data/mockdb.sqlite`. Keep the original H2 files until the new deployment has run stably. Docker users: the previous H2 default stored data at `/app/mockdb`, outside the `/app/data` volume; copy it out of the container before recreating it. To deliberately start with an empty database despite a leftover `mockdb.mv.db`, set `ECHO_DATABASE_IGNORELEGACYH2=true`.

#### Standard environment variables

```bash
SPRING_PROFILES_ACTIVE=postgresql \
SPRING_DATASOURCE_URL='jdbc:postgresql://db-host:5432/appdb' \
SPRING_DATASOURCE_USERNAME='app_user' \
SPRING_DATASOURCE_PASSWORD='<password>' \
SPRING_JPA_HIBERNATE_DDL_AUTO=validate \
./gradlew bootRun
```

| Variable | Purpose |
|----------|---------|
| `SPRING_PROFILES_ACTIVE` | Select exactly one database profile (`sqlite`, `postgresql`, `mysql`, `mariadb`, `sqlserver`, or `oracle`), optionally with `dev`/`test` |
| `SPRING_DATASOURCE_URL` | Override the profile's JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | External database username |
| `SPRING_DATASOURCE_PASSWORD` | External database password; inject it through the deployment secret mechanism |
| `SPRING_JPA_HIBERNATE_DDL_AUTO` | Current profiles default to `update`; use `validate` after a controlled schema migration |
| `ECHO_REQUEST_LOG_ENABLED` | Set to `false` to stop creating request logs, the log agent, and the local spool; defaults to `true` |
| `ECHO_REQUEST_LOG_SPOOL_PATH` | Per-instance local SQLite request-log spool path; default `./data/request-log-spool.sqlite` |
| `ECHO_CACHE_TEMPLATE_MAX_WEIGHT_MB` | Compiled-template cache budget in MiB; default `16`; oversized templates still render without being retained |
| `ECHO_DB_POOL_MAX_SIZE` / `ECHO_DB_POOL_MIN_IDLE` | Hikari connection-pool sizing for external profiles |

The remaining Hikari timeout and lifetime settings use the `ECHO_DB_*` variables in the profile files. Do not put multiple database profiles in `SPRING_PROFILES_ACTIVE`.

The SQLite profile keeps a small connection pool for WAL readers and
automatically queues JPA write transactions inside Echo, so normal concurrent
writes do not race for SQLite's single-writer lock. `busy_timeout` remains a
fallback for locks held by another process. Run only one Echo process against a
given SQLite file; use a server database when multiple application instances
must share storage. See [SQLite recovery](docs/sqlite-recovery.md) for recovery,
writer metrics, and validation boundaries.

#### Backup and request-log spool boundaries

The application backup feature is for local SQLite files. External database profiles disable it; PostgreSQL, MySQL/MariaDB, SQL Server, and Oracle backups, point-in-time recovery, retention, and restore drills are owned by the DBA or platform backup service.

Request-log durability has a separate boundary: every Echo instance writes its local request-log spool to a SQLite file, including when the main database is external. Keep that file on a writable persistent volume, use one spool file per instance, and do not treat it as a shared database or cross-instance queue. The spool is not a substitute for the external database's backup policy.

Request logs are saved to the main database at up to `ECHO_AGENT_LOG_MAX_WRITE_RATE_PER_SECOND` (default 1,000) per second. When traffic stays above that rate, entries waiting in the spool beyond `echo.request-log.max-records` are skipped instead of queued: the record limit would delete them right after saving anyway, so the newest records are kept and mock requests are not slowed down by a full spool. Skipped entries are counted as dropped in the log agent status, with a warning at most every 30 seconds.

For a core matcher/response throughput benchmark, start Echo with
`ECHO_REQUEST_LOG_ENABLED=false`. This bypasses request-log object creation,
background processing, database writes, and the local spool while leaving mock
matching and replies enabled. It intentionally changes the observability and JMS
durable-log acceptance contract, so also run a production-profile benchmark with
request logging enabled before making a reliability or capacity claim. Existing
database logs remain queryable while new logging is disabled.

When using the bundled RPS script against that mode, add
`--request-log-disabled` so it does not wait for a log-agent queue that is
intentionally absent:

```bash
ECHO_REQUEST_LOG_ENABLED=false java -jar build/libs/echo-server-*.jar
python3 scripts/stress-test-rps.py http://localhost:8080 10 20 \
  --request-log-disabled
```

`ddl-auto=update` is suitable only for a new empty database or development bootstrap. It is not a versioned migration, and it cannot reliably express renames, backfills, type conversions, index/constraint changes, or vendor-specific identity/LOB/boolean/date/JSON behavior across all seven databases. It also requires DDL privileges and can fail when existing rows conflict with a new non-null column.

### Docker RDBMS smoke test

The disposable RDBMS Compose file exercises one database profile at a time, including readiness, API matching, large-response persistence, restart persistence, and log collection. Build the application artifact first so the Dockerfile can package it, then choose one profile per run:

```bash
./gradlew bootJar
python3 scripts/test-rdbms-matrix.py --databases postgresql
```

Replace `postgresql` with one of `sqlite`, `mysql`, `mariadb`, `sqlserver`, or `oracle`. The script can accept several names and runs them sequentially, but each individual Compose run still has only one active database profile. Results are written under `artifacts/rdbms-matrix/<run>/`, including `matrix-result.json`.

For a manual smoke test:

```bash
docker compose -f docker-compose.rdbms.yml --profile postgresql up --build --wait --detach echo-postgresql
curl -fsS http://localhost:18080/api/admin/status
docker compose -f docker-compose.rdbms.yml --profile postgresql logs -f echo-postgresql
docker compose -f docker-compose.rdbms.yml --profile postgresql down --volumes --remove-orphans
```

The Compose services use disposable test data. `down --volumes` removes those test volumes; never use this setup as an external database backup. SQL Server uses an amd64 image and Oracle/SQL Server may need additional Docker CPU and memory.

### Docker Deployment

```bash
# Build and start
./gradlew bootJar
docker compose up -d

# Or pull directly (if pushed to a registry)
docker compose pull && docker compose up -d

# View logs
docker compose logs -f

# Stop
docker compose down
```

Environment variables:
| Variable | Default | Description |
|----------|---------|-------------|
| `ECHO_ADMIN_USERNAME` | admin | Admin username |
| `ECHO_ADMIN_PASSWORD` | admin | Admin password |
| `ECHO_ENV_LABEL` | DOCKER | Environment label |
| `ECHO_UI_ACCENT` | teal | UI accent color: `teal` or `blue`, to tell deployments apart (e.g. SIT teal, UAT blue) |
| `ECHO_UI_EASTER_EGG` | true | Hidden easter egg (a small game opened with ↑↑↓↓←→←→AB); set `false` to turn it off in production |
| `TZ` | Asia/Taipei | Timezone |

JVM options are set in the Dockerfile (default `-Xms256m -Xmx512m`, with a heap dump and process exit on OOM). Override by adding `JAVA_OPTS` to docker-compose.yml `environment`.
For production `java -jar` deployments, also pass `-XX:+HeapDumpOnOutOfMemoryError -XX:+ExitOnOutOfMemoryError` and let systemd, Kubernetes, or another supervisor restart the process.

### Optional Features

The following user-facing features are intentionally disabled by default and can be enabled per deployment:

| Variable | Default | Description |
|----------|---------|-------------|
| `ECHO_ISSUE_REPORTING_ENABLED` | `false` | Shows the issue-reporting page and enables its APIs; existing reports are retained |
| `ECHO_BULK_IMPORT_EXPORT_ENABLED` | `false` | Shows and enables bulk import/export operations |
| `ECHO_SCENARIOS_ENABLED` | `false` | Enables stateful Scenario rules and Scenario administration |

Issue reporting can also be enabled with `echo.features.issue-reporting-enabled: true`
in the deployment configuration. Restart Echo to apply the setting. When disabled,
the navigation entry is hidden, direct `#/issues` links return to the rule list,
and authenticated issue API requests return 404. Existing data and enabled-mode
permissions are unchanged.

### Access the Service

| Service | URL | Description |
|---------|-----|-------------|
| Admin UI | http://localhost:8080/ | Mock rule management |
| Login Page | http://localhost:8080/login.html | User login |
| Mock Endpoint | http://localhost:8080/mock/** | Intercept HTTP requests |
| Database | — | SQLite (`./data/mockdb.sqlite`) is the default; select exactly one database profile for an external database |

## Rule Matching Priority

When multiple rules match the same request, the system selects based on the following order:

### Sorting Priority (higher number = higher priority)

1. **matchKey specificity** – Exact paths take priority over wildcard `*`
2. **priority field** – Higher number = higher priority (default 0)
3. **targetHost specificity** (HTTP) – Specified host takes priority over empty value
4. **Creation time** – Newer rules take priority

### Matching Logic

1. **Has conditions and matches** → Return immediately
2. **No conditions** → Record as fallback, take the first one after sorting
3. Finally return the fallback rule or null

### Example

| Rule | targetHost | matchKey | priority | Condition | Order |
|------|------------|----------|----------|-----------|-------|
| A | api.com | /users | 10 | type=vip | 1 |
| B | api.com | /users | 10 | (none) | 2 |
| C | (empty) | /users | 10 | (none) | 3 |
| D | api.com | * | 10 | (none) | 4 |
| E | api.com | /users | 1 | (none) | 5 |

- Request body contains `type=vip` → Matches A
- Request body does not contain `type=vip` → Matches B

Rules with disabled tags do not participate in matching.

## Tag-Based Organization

Tags are used to organize rules by version, feature, or environment:

- **JSON format** – e.g., `{"env":"prod","team":"payment"}`
- **Batch control** – Enable/disable rules by tag (`PUT /api/admin/rules/tag/{key}/{value}/enable|disable`)
- **Quick filter** – Filter rules by tag on the rules page, with group view toggle

## Mock Rule Configuration

### HTTP Rule

```json
{
  "protocol": "HTTP",
  "targetHost": "api.example.com",
  "matchKey": "/users",
  "method": "GET",
  "bodyCondition": "custId=K123",
  "queryCondition": "status=active",
  "responseBody": "{\"name\": \"VIP User\"}",
  "httpStatus": 200,
  "delayMs": 100
}
```

### JMS Rule

```json
{
  "protocol": "JMS",
  "queueName": "ORDER.QUEUE",
  "bodyCondition": "//OrderType=VIP",
  "responseBody": "<response><status>OK</status></response>",
  "delayMs": 50
}
```

## JMS Architecture

Echo can act as a JMS Proxy, intercepting JMS messages in development environments:

```
Application ──JMS──▶ Echo (Artemis)  ──JMS──▶ ESB (TIBCO/Artemis)
                     Queue: ECHO.REQUEST      Queue: TARGET.REQUEST
                     Match found → Mock Response
                     No match    → Forward to Target ESB
```

Administrators can create multiple Artemis/TIBCO profiles under **System Settings → Forward Connections → JMS**, test them, and select one database default. A valid, enabled `application.yml` target takes precedence; the database default is used only when that configuration is absent. This is configuration precedence, not automatic failover. Unmatched messages and rules selecting the default JMS connection use this outbound default; rules selecting a specific profile use that profile. Echo's inbound Embedded Artemis connection is unchanged. Passwords are stored with AES-GCM encryption and are never returned by the API. Set a stable `ECHO_JMS_CREDENTIAL_KEY` in production; changing it requires re-entering every stored password.

System Settings separates **Overview**, **Resource Monitoring**, **Forward Connections**, **Data & Backups**, and **Service Configuration**. Settings collects data on entry and explicit refresh/retry only, without polling. Resource details distinguish current usage, lifetime counters, and configured limits; see [resource snapshot semantics](docs/resource-monitoring.md) before interpreting them as health or leak evidence.

[Transaction diagnostics](docs/transaction-diagnostics.md) adds bounded anomaly-only file summaries
and Request Log correlation IDs. See the linked guide for configuration, quotas and evidence limits.

For Artemis Core clients that send XML, configure the sender URL as `tcp://echo-host:61616?minLargeMessageSize=524288`. Text payloads up to roughly 256 KB then use the regular-message path instead of creating one large-message file per request, while larger messages still spill to disk to protect the heap. This is a **sender-side** connection setting, not an Echo `application.yml` property.

### Receiving JMS from a Shared Broker (Multi-Instance)

By default each Echo instance receives JMS on its own embedded Artemis, so senders must pick one instance. To run several instances for load sharing and failover, let all of them listen on the same queue of an existing broker (for example the company ESB). The broker hands each message to only one instance; replies go to the message's `JMSReplyTo`, which is on the same broker.

```
Application ──JMS──▶ ESB queue ECHO.REQUEST ──▶ Echo A
                                            └─▶ Echo B
```

```yaml
echo:
  jms:
    enabled: true
    queue: ECHO.REQUEST            # queue on the broker that the instances share
    source:
      type: tibco                  # embedded (default) | artemis | tibco
      server-url: tcp://esb-server:7222
      username: echo               # ECHO_JMS_SOURCE_USERNAME
      password: ${ECHO_JMS_SOURCE_PASSWORD}
      reconnect-interval-ms: 5000  # retry interval while the broker is unreachable
```

- The embedded Artemis is not started and `echo.jms.port` is not opened.
- Echo starts even when the broker is down and keeps reconnecting; **System Settings → Service Configuration** shows whether JMS is receiving.
- Messages are consumed in a transaction: if an instance stops or crashes mid-message, the broker gives the message to another instance. A crash after the reply was sent but before the commit can produce a second reply (at-least-once delivery).
- Redelivery delays, attempt limits and dead-letter handling are the broker's own settings; `echo.jms.redelivery-*`, `max-delivery-attempts`, paging and disk settings only apply to the embedded broker.
- TIBCO EMS needs the EMS 10.3+ client (`tibjms.jar`, `jakarta.jms`) in `libs/`, the same as for forwarding.

## Condition Matching Syntax

### HTTP Body Conditions (JSON)

| Syntax | Description | Example |
|--------|-------------|---------|
| `field=value` | Simple field | `userId=123` |
| `a.b.c=value` | Nested field | `order.customer.id=VIP001` |
| `arr[0].field=value` | Array index | `items[0].sku=A001` |

### HTTP Query Conditions

| Syntax | Description | Matches |
|--------|-------------|---------|
| `status=active` | Query parameter | `?status=active` |
| `id=123` | Query parameter | `?id=123&other=x` |

### JMS Body Conditions (XML)

| Syntax | Description |
|--------|-------------|
| `element=value` | Simple element (auto-converts to `//element`) |
| `//CustomerId=K123` | XPath anywhere |
| `/root/order/id=123` | XPath absolute path |
| `order/id=123` | Relative path: any `id` whose parent is `order` |
| `//item/@sku=A1` | Attribute value |

Simple paths like these match element and attribute **local names**, so `CustomerId` also matches `<ns:CustomerId>`; `==` is treated as `=`. The same rules apply to HTTP and JMS and to `xpath` selectors in advanced conditions, and XML bodies whose conditions only use simple paths are scanned once without building a DOM. Paths with predicates, functions, wildcards or prefixed names (for example `//item[1]`, `//ns:item`) use XPath.

### Multiple Conditions

Multiple conditions separated by `;` must all match (AND logic):
- Body: `custId=K123;type=vip`
- Query: `status=active;page=1`
- Header: `X-Api-Key=abc123;Content-Type*=json`

### HTTP Header Conditions

| Syntax | Description | Example |
|--------|-------------|---------|
| `Header=value` | Exact match (case-insensitive) | `X-Api-Key=abc123` |
| `Header!=value` | Not equal | `Accept!=text/xml` |
| `Header*=value` | Contains | `Content-Type*=json` |
| `Header~=regex` | Regex match | `Authorization~=Bearer.*` |

### Advanced Conditions (`matchSpec`)

When `;`-joined AND conditions are not enough, a rule can carry a structured condition tree in
`matchSpec` (REST API, JSON import/export, or `echo.mock/v2` documents as `spec.match`). It supports
`all` / `any` / `not` groups and these operators: `equals`, `notEquals`, `contains`, `matches`,
`exists`, `absent`, `gt` / `gte` / `lt` / `lte` (numeric), `in`, and `equalToJson`.

```json
{
  "protocol": "HTTP", "matchKey": "/orders", "method": "POST", "responseBody": "{\"tier\":\"gold\"}",
  "matchSpec": {"all": [
    {"source": "header", "name": "X-Tenant", "op": "equals", "value": "acme"},
    {"any": [
      {"source": "body", "selector": {"jsonPath": "$.amount"}, "op": "gte", "value": 1000},
      {"source": "query", "name": "vip", "op": "exists"}
    ]},
    {"not": {"source": "body", "selector": {"jsonPath": "$.status"}, "op": "equals", "value": "CANCELLED"}}
  ]}
}
```

- Sources: HTTP rules use `body`, `query` and `header`; JMS rules use `body` (selectors `jsonPath`, `xpath`, `field`, `raw`).
- Invalid trees are rejected on save with the JSON path of the problem (for example `$.all[1].any[0].op`).
  Limits: 64 KiB, 16 levels, 256 nodes, 1,000 `in` values.
- `matchSpec` and `bodyCondition` / `queryCondition` / `headerCondition` are mutually exclusive. A tree that is only
  an `all` of `{"legacy": {"source": ..., "condition": ...}}` fragments is stored as the legacy fields.
- In the rule editor, switch the condition block from **Simple** to **Advanced** to build groups, NOT and the new
  operators; existing simple conditions become legacy fragments that keep their exact matching. Switching back is
  instant when only legacy fragments remain, otherwise the editor asks before clearing the advanced conditions.
  The editor checks the tree against the server while you type and points at the failing node.
- A request that omits both `matchSpec` and legacy conditions keeps a rule's structured conditions (so older clients
  cannot erase them by accident); send `{"all": []}` to clear them.

## Stateful Scenarios

Rules can participate in a named state machine using `scenarioName`, match only in
`requiredScenarioState`, and transition to `newScenarioState` after a successful match.
Every scenario starts in `Started`.

```json
{
  "protocol": "HTTP",
  "matchKey": "/orders/123/pay",
  "method": "POST",
  "scenarioName": "order-flow",
  "requiredScenarioState": "Started",
  "newScenarioState": "Paid",
  "responseBody": "{\"result\":\"payment-ok\"}"
}
```

Reset one scenario with `PUT /api/admin/scenarios/{name}/reset`, or all scenarios
with `PUT /api/admin/scenarios/reset`.

## Usage

### Direct Call

```bash
# Matching rule found → Return mock response
curl http://localhost:8080/mock/api/users \
  -H "X-Original-Host: api.example.com"

# No matching rule → Proxy forward to google.com
curl http://localhost:8080/mock/search?q=test \
  -H "X-Original-Host: google.com"
```

### Via Nginx Proxy

```nginx
location /api/ {
    proxy_set_header X-Original-Host api.example.com;
    proxy_pass http://echo-server:8080/mock/;
}
```

### Outbound HTTPS verification

HTTP forwarding keeps **intranet-compatible TLS behavior by default**: certificate-chain
and hostname verification are disabled so private or self-signed downstream services
continue to work. Saved HTTP connection profiles can opt into **Strict Certificate
Verification** in System Settings. Legacy `X-Original-Host` forwarding remains in
intranet-compatible mode.

Use the default only on a trusted internal network. For downstream services reached
through an untrusted network, create a saved HTTP connection and enable strict
verification.

## Configuration

### application.yml

```yaml
server:
  port: 8080
  servlet:
    session:
      timeout: 180d

echo:
  env-label:                    # Environment label (e.g., DEV, SIT, UAT)
  ui:
    accent: teal                # UI accent: teal or blue (ECHO_UI_ACCENT)
    easter-egg: true            # Hidden easter egg game (ECHO_UI_EASTER_EGG)
  remember-me:
    key: echo-remember-me-secret  # Remember Me encryption key
    validity: 180d                # Remember Me cookie validity
  admin:
    username: admin             # Admin username
    password: admin             # Admin password (supports {bcrypt} prefix)
  storage:
    mode: database              # database or file
  cache:
    body:
      max-size-mb: 50           # Response body cache limit (MB)
      threshold-kb: 5120        # Bodies larger than this are not cached (KB)
      expire-minutes: 720       # Cache expiration (minutes)
    sync-interval-ms: 5000      # Multi-instance cache sync interval (ms)
  jms:
    enabled: false              # Set to true to enable JMS
    credential-key: ${ECHO_JMS_CREDENTIAL_KEY} # Encrypts stored forwarding credentials
    port: 61616                 # Artemis listen port
    queue: ECHO.REQUEST         # Queue to listen on
    endpoint-field: ServiceName # Field to extract endpoint identifier from message body
    processing-memory-percent: 25 # Heap share available to in-flight JMS message parsing
    xml-memory-expansion-factor: 8 # Conservative estimate for temporary XML parsing memory
    broker-memory-percent: 15     # Artemis heap share before paging to disk
    persistent: true              # Keep true outside tests so large messages cannot exhaust the heap
    consumer-window-size: 65536   # Encoded bytes prefetched by each listener consumer
    data-directory: ./data/artemis # Artemis paging and large-message files
    target:
      enabled: false            # Legacy fallback used until a database profile exists
      type: tibco               # artemis or tibco
      server-url: tcp://esb-server:7222
      timeout-seconds: 30        # Valid range: 1–300 seconds; invalid values fail configuration binding
      queue: TARGET.REQUEST     # Target queue
  http:
    alias: HTTP                 # HTTP protocol display name
  stats:
    retention-days: 7           # Statistics retention days
  request-log:
    enabled: true                  # Set false only when intentionally disabling new request logs
    store: database             # memory or database
    max-records: 10000          # Maximum log records
    include-body: true          # Whether to log request/response body
    max-body-size: 65536        # Body log size limit (bytes)
  audit:
    retention-days: 30          # Audit log retention days
  cleanup:
    enabled: true               # Enable scheduled cleanup
    cron: "0 0 3 * * *"         # Daily at 3 AM
    rule-retention-days: 180    # Rule retention days
    response-retention-days: 180 # Response retention days
  backup:
    enabled: true               # Local-file backup for SQLite only
    cron: "0 0 3 * * *"         # Daily at 3 AM
    path: ./backups             # Backup directory
    retention-days: 7           # Backup retention days
    on-shutdown: true           # Backup on application shutdown
  builtin-account:
    self-registration: false    # Set to true to allow self-registration
  ldap:
    enabled: false
    url: ldap://ldap.example.com:389
    base-dn: dc=example,dc=com
    user-pattern: uid={0},ou=users
```

The backup settings above apply only to local SQLite files. External database profiles set application backup to false; use the DBA or platform backup service for those databases.

## Authentication

### Auth Modes

| Profile | Description | Use Case |
|---------|-------------|----------|
| `dev` | Dev mode, no login required, self-registration enabled | Local development |
| `default` | Login required, default account admin/admin | Test environments |
| LDAP | LDAP authentication (`echo.ldap.enabled=true`) | Production |

### Access Control

| Role | Permissions |
|------|-------------|
| ADMIN | System settings, batch operations (export/import/delete all), account management |
| USER | Manage mock rules, view statistics and logs, change own password |
| Guest | Read-only browsing of rules, responses, statistics, audit logs (no login required) |

### Built-in Account Management

ADMIN can manage built-in accounts via the admin UI:
- Create/delete accounts
- Enable/disable accounts
- Reset password (generates temporary password, forces change on first login)
- Users can change their own password (`PUT /api/account/change-password`)
- Forgot password request (public endpoint, rate-limited)
- Self-registration (requires `echo.builtin-account.self-registration=true`)

## API Endpoints

### Rule Management

| Method | Path | Description |
|--------|------|-------------|
| GET | /api/admin/rules | List all rules in one response (authenticated; the UI uses `/rules/page`) |
| GET | /api/admin/rules/page | Bounded rule page; supports protocol/enabled/isProtected/mode/expiring/keyword/page/size/sort/direction |
| GET | /api/admin/rules/groups | Rule group counts with the same filters |
| GET | /api/admin/rules/group | Bounded content for one rule group with the same filters |
| GET | /api/admin/rules/{id} | Get rule (with response body) |
| POST | /api/admin/rules | Create rule |
| PUT | /api/admin/rules/{id} | Update rule |
| DELETE | /api/admin/rules/{id} | Delete rule |
| POST | /api/admin/rules/{id}/test | Test rule matching |
| PUT | /api/admin/rules/{id}/enable | Enable rule |
| PUT | /api/admin/rules/{id}/disable | Disable rule |
| PUT | /api/admin/rules/{id}/protect | Protect rule |
| PUT | /api/admin/rules/{id}/unprotect | Unprotect rule |
| PUT | /api/admin/rules/{id}/extend | Extend rule retention |
| PUT | /api/admin/rules/batch/enable | Batch enable |
| PUT | /api/admin/rules/batch/disable | Batch disable |
| PUT | /api/admin/rules/batch/protect | Batch protect |
| PUT | /api/admin/rules/batch/unprotect | Batch unprotect |
| PUT | /api/admin/rules/batch/extend | Batch extend |
| PUT | /api/admin/rules/tag/{key}/{value}/enable | Enable by tag (ADMIN) |
| PUT | /api/admin/rules/tag/{key}/{value}/disable | Disable by tag (ADMIN) |
| GET | /api/admin/rules/export | Export all rules (ADMIN) |
| GET | /api/admin/rules/{id}/json | Export single rule |
| POST | /api/admin/rules/import | Import single rule (ADMIN) |
| POST | /api/admin/rules/import-batch | Batch import (ADMIN) |
| POST | /api/admin/rules/import-excel | Excel import (ADMIN) |
| GET | /api/admin/rules/import-template | Download Excel import template |
| DELETE | /api/admin/rules/batch | Batch delete (ADMIN) |
| DELETE | /api/admin/rules/all | Delete all (ADMIN) |

Rule responses include durable `createdBy` and `updatedBy` metadata. Existing rows remain readable after upgrade; an old row reports no operator until it is next modified.

### Response Management

| Method | Path | Description |
|--------|------|-------------|
| GET | /api/admin/responses | List responses with their bodies (authenticated; supports keyword search; the UI uses `/responses/summary`) |
| GET | /api/admin/responses/summary | Response summary (with usage count) |
| GET | /api/admin/responses/{id} | Get response |
| GET | /api/admin/responses/{id}/rules | Rules using this response |
| POST | /api/admin/responses | Create response |
| PUT | /api/admin/responses/{id} | Update response |
| DELETE | /api/admin/responses/{id} | Delete response (cascades to associated rules) |
| PUT | /api/admin/responses/{id}/extend | Extend response retention |
| PUT | /api/admin/responses/batch/extend | Batch extend responses |
| GET | /api/admin/responses/orphan-count | Orphan response count |
| DELETE | /api/admin/responses/orphans | Delete orphan responses |
| GET | /api/admin/responses/export | Export all responses (ADMIN) |
| POST | /api/admin/responses/import-batch | Batch import responses (ADMIN) |
| DELETE | /api/admin/responses/batch | Batch delete responses (ADMIN) |
| DELETE | /api/admin/responses/all | Delete all responses (ADMIN) |

### Log Queries

| Method | Path | Description |
|--------|------|-------------|
| GET | /api/admin/logs | Request logs (authenticated; filter by ruleId/protocol/matched/endpoint; from/to request time (local `YYYY-MM-DDTHH:mm:ss`, from inclusive, to exclusive); page/size/sort/direction; afterId for incremental refresh) |
| GET | /api/admin/logs/summary | Request log summary (authenticated) |
| GET | /api/admin/logs/traffic | Traffic overview of the retained request logs (authenticated; `range=1h\|24h\|7d`): per-bar counts of mock responses, forwards, unmatched requests and failed forwards, estimated response times and the most often unmatched requests. Counted in the database and shared for 5 seconds, so concurrent viewers trigger one computation |
| DELETE | /api/admin/logs/all | Delete all request logs (ADMIN) |
| GET | /api/admin/rules/{id}/audit | Audit logs for a rule |
| GET | /api/admin/audit | All audit logs |
| DELETE | /api/admin/audit/all | Delete all audit logs (ADMIN) |

### System Management

| Method | Path | Description |
|--------|------|-------------|
| GET | /api/admin/status | System status (JVM, DB, statistics) |
| GET | /api/admin/backup/status | Backup status and file list |
| POST | /api/admin/backup | Trigger manual backup |

### Account Management

| Method | Path | Description |
|--------|------|-------------|
| GET | /api/admin/builtin-users | List built-in accounts (ADMIN) |
| GET | /api/admin/builtin-users/page | Bounded account page; supports keyword/role/enabled/passwordResetRequested/page/size/sort/direction (ADMIN) |
| POST | /api/admin/builtin-users | Create account (ADMIN) |
| PUT | /api/admin/builtin-users/{id}/enable | Enable account (ADMIN) |
| PUT | /api/admin/builtin-users/{id}/disable | Disable account (ADMIN) |
| DELETE | /api/admin/builtin-users/{id} | Delete account (ADMIN) |
| POST | /api/admin/builtin-users/{id}/reset-password | Reset password (ADMIN) |
| POST | /api/admin/builtin-users/forgot-password | Forgot password (public) |
| POST | /api/admin/builtin-users/register | Self-register (public, must be enabled) |
| PUT | /api/account/change-password | Change own password (authenticated) |

### Issue Reports

| Method | Path | Description |
|--------|------|-------------|
| GET | /api/admin/issues | List issue reports (authenticated; legacy unpaged API) |
| GET | /api/admin/issues/page | Bounded issue page; supports status/keyword/page/size/sort/direction and returns the global open count |
| GET | /api/admin/issues/{id} | Get one issue report |
| POST | /api/admin/issues | Create an issue report (authenticated) |
| PUT | /api/admin/issues/{id}/reply | Reply to an issue (ADMIN) |
| PUT | /api/admin/issues/{id}/resolve | Resolve an issue (ADMIN) |
| PUT | /api/admin/issues/{id}/reopen | Reopen an issue (ADMIN) |
| DELETE | /api/admin/issues/{id} | Delete an issue (ADMIN) |

### JMS Testing

| Method | Path | Description |
|--------|------|-------------|
| POST | /api/admin/jms/test | Send test message to ECHO.REQUEST |

## Dynamic Response Templates

WireMock-style Handlebars template engine. Use `{{...}}` syntax in response content to generate dynamic content.

### Basic Syntax

```handlebars
{{request.path}}                              // Request path
{{request.method}}                            // HTTP method
{{request.query.xxx}}                         // Query parameter
{{request.headers.xxx}}                       // Header value
{{{request.body}}}                            // Request body
{{now format='yyyy-MM-dd'}}                   // Formatted time
{{randomValue type='UUID'}}                   // Random UUID
{{randomValue length=8 type='ALPHANUMERIC'}}  // Random string
```

### Conditions & Loops

```handlebars
{{#if (eq request.method 'POST')}}Created{{else}}Other{{/if}}
{{#each (split request.query.ids ',')}}{{this}}{{/each}}
```

Comparison operators: `eq`, `ne`, `gt`, `lt`, `contains`, `matches`

### JSONPath / XPath

```handlebars
{{jsonPath request.body '$.user.name'}}
{{xPath request.body '//name/text()'}}
```

## Multi-Instance Deployment

Database mode supports multi-instance deployment with DB-based cache invalidation.

### Cache Sync Mechanism

Each instance keeps rules, response bodies and HTTP forwarding profiles in a local Caffeine cache. Changes are announced through the `cache_events` table:

```
Instance A changes a rule → writes a cache_events row in the same transaction
                                    ↓
Instance B polls every N seconds → reads rows with a higher id → clears the affected local cache
```

- **What is synchronized**: rule changes (per protocol), edited or deleted responses, HTTP forwarding profile changes, and rules or responses removed by the nightly cleanup
- **Polling interval**: Default 5 seconds, configurable via `echo.cache.sync-interval-ms`; a change normally reaches the other instances within that time
- **Ordered by event id, not time**: instance clocks or time zones may differ. An id that is skipped (a transaction that commits later) is waited for up to 2 minutes
- **Fallbacks**: if an instance could not poll for more than 5 minutes, or the event ids restarted, it clears all of these caches once. Cached rules and HTTP forwarding profiles are also re-read at least every `echo.cache.rules.max-age-minutes` (default 10) and response bodies every `echo.cache.body.max-age-minutes` (default 60), even when a notice was missed
- **Event cleanup**: Events older than 10 minutes are removed every hour; the newest event is kept so ids never restart
- **Performance impact**: Minimal — each poll is a primary-key range query

Login sessions are kept in each instance's memory, so configure the load balancer to keep a user on the same instance (sticky sessions). For JMS across instances, see [Receiving JMS from a Shared Broker](#receiving-jms-from-a-shared-broker-multi-instance).

### Configuration Example

Set `SPRING_PROFILES_ACTIVE` to exactly one database profile before starting the instances; the JDBC URL alone does not select a vendor profile. For example, use `postgresql` here and provide the credentials through environment variables or the deployment secret mechanism.

```yaml
echo:
  storage:
    mode: database
  cache:
    sync-interval-ms: 10000  # Change to 10 seconds (default 5000)

spring:
  datasource:
    url: jdbc:postgresql://db-host:5432/echo  # Shared database
```

The main database can be shared for rules, responses, and cache events. The request-log spool remains a separate local SQLite file for each instance and must not be placed on a shared path.

### Activation Conditions

- `echo.storage.mode=database` → Enables cache sync
- `echo.storage.mode=file` → Disabled (not needed for single instance)

## Performance Benchmark

Benchmark numbers are workload-specific and should not be treated as universal product claims. The scripts under `scripts/` are the source of truth for reproducing them.

### Current no-request-log Echo vs WireMock A/B (2026-09-03)

Echo and WireMock 3.13.2 were run in the same JDK 21 Alpine container image
with a 512 MiB heap, a 1 GiB container limit, 50 concurrent clients, and 8
seconds per server/scenario. Echo used `ECHO_REQUEST_LOG_ENABLED=false`;
WireMock used `--no-request-journal --disable-request-logging`. Every non-2xx
response and transport failure counted as an error, and both sides completed
all four scenarios with zero errors and no OOM termination.

| Scenario | Echo RPS | WireMock RPS | Ratio | Echo p95 | WireMock p95 |
|----------|---------:|-------------:|------:|---------:|-------------:|
| Simple JSON, no condition | 7,304 | 6,532 | 1.12x | 12.0ms | 13.9ms |
| JSON, 10 candidate rules | 6,924 | 6,789 | 1.02x | 12.9ms | 12.1ms |
| XML ~1KB + XPath | 6,466 | 4,593 | 1.41x | 13.5ms | 17.7ms |
| XML ~80KB + XPath | 3,120 | 250 | 12.46x | 19.8ms | 312.8ms |

In this workload, Echo was roughly even with or slightly faster than WireMock
for JSON and materially faster for XML/XPath, especially for the 80KB body.
This is a local synthetic result, not a universal product claim. The matcher
implementations differ, and the short run is not a long-duration stability
test. Echo's HTTP and JMS XML mock/reply paths were also integration-tested
with request logging disabled; WireMock does not provide a directly comparable
JMS mock protocol.

### Request-log-enabled Echo vs WireMock A/B (2026-08-11)

Echo and WireMock 3.13.2 were run on the same Apple Silicon host with Java 22.0.2, a 512 MiB heap, request journals enabled, 50 concurrent clients, and 8 seconds per server/scenario. Both sides completed with zero HTTP 5xx or connection errors.

| Scenario | Echo RPS | WireMock RPS | Ratio | Echo p95 | WireMock p95 |
|----------|---------:|-------------:|------:|---------:|-------------:|
| Simple JSON, no condition | 6,327 | 7,068 | 0.90x | 13.1ms | 12.9ms |
| JSON, 10 candidate rules | 7,570 | 6,875 | 1.10x | 10.6ms | 13.0ms |
| XML ~1KB + XPath | 7,136 | 4,617 | 1.55x | 11.3ms | 24.4ms |
| XML ~80KB + XPath | 3,116 | 483 | 6.46x | 23.9ms | 180.6ms |

Simple JSON is still about 10% behind WireMock in this run; Echo leads once candidate matching or XML/XPath parsing becomes significant. This does not imply that every Echo feature is faster than its WireMock equivalent.

The focused benchmarks below were collected earlier on macOS/Apple Silicon with Java 17, 20 concurrent threads, and 10 seconds per scenario. Compare results only when the command and environment are identical.

### Rule Count Impact (1,600 rules)

| Metric | 6 rules | 1,600 rules |
|--------|---------|-------------|
| Cold cache match | 3 ms | 10 ms |
| Warm cache match | 3 ms | 6 ms |
| 10-request average | — | 5.0 ms |

Rule count has minimal impact on matching performance. Rules are cached by `host + path + method`, so only candidate rules for the same endpoint are evaluated — not all 1,600.

### XML Body Size Impact

| Body Size | XML Match | JSON Match | XML / JSON |
|-----------|-----------|------------|------------|
| ~1KB | 16.2 ms | 5.2 ms | 3.1x |
| ~10KB | 10.0 ms | 3.8 ms | 2.6x |
| ~50KB | 23.4 ms | 4.2 ms | 5.6x |
| ~100KB | 34.8 ms | 6.0 ms | 5.8x |

XML matching cost grows linearly with body size due to DOM parsing. JSON matching remains stable (~4-6ms) regardless of body size thanks to Jackson's O(1) field lookup.

### Cache Mechanism

- **Rule cache**: Caffeine, 10,000 entries, 12-hour expiration
- **Response body cache**: 50MB limit, 5MB threshold, 12-hour expiration
- Automatically invalidated on rule changes

### Historical High-Volume Rule Matching (2,000 rules, worst case)

Test: 2,000 HTTP rules with XPath conditions (`//ServiceName=xxx;//CustId=yyy`), target rule sorted last (worst case full traversal), 10 concurrent threads, 200 requests.

| Scenario | RPS | avg | p50 | p95 | p99 |
|----------|-----|-----|-----|-----|-----|
| Echo XML (2,000 rules, XPath) | 434 | 22.7ms | 22.6ms | 34.8ms | 47.0ms |
| Echo JSON (2,000 rules, field match) | 1,066 | 9.3ms | 8.0ms | 24.8ms | 28.7ms |
| WireMock XML (2,000 rules, XPath) | 31 | 311.9ms | 311.3ms | 400.7ms | 427.0ms |

In this historical 2,000-rule workload, Echo's XML/XPath path was **14x faster** than the tested WireMock build, thanks to the `getElementsByTagName` fast path for simple XPath patterns and pre-compiled `XPathExpression` caching. The result is specific to this worst-case rule set.

### Run Benchmarks

```bash
# Single scenario match time
python3 scripts/stress-test-scenario1.py

# 1,600 rules impact test
python3 scripts/stress-test-1600-rules.py

# XML vs JSON body size comparison
python3 scripts/stress-test-xml-body.py

# RPS throughput test
python3 scripts/stress-test-rps.py [URL] [DURATION] [CONCURRENCY]

# Echo vs WireMock comparison (start a separate WireMock instance first)
python3 scripts/stress-test-vs-wiremock.py [ECHO_URL] [WM_URL] [DURATION] [CONCURRENCY]

# 2,000 rules RPS test (Echo XML/JSON vs WireMock XML)
python3 scripts/bench-rps-xml.py

# 2,000 JMS rules match time
python3 scripts/bench-2000-jms.py

# JMS match stress test
python3 scripts/stress-test-jms-match.py [BASE_URL]

# Memory stress test
python3 scripts/stress-test-memory.py [BASE_URL]

# Log check
python3 scripts/check-logs.py

# Match scenario regression test (69 cases)
python3 scripts/test-match-scenarios.py
```

## Testing

```bash
# Run tests
./gradlew test

# Quick test (alias)
./gradlew t

# Test coverage report
./gradlew test jacocoTestReport
# Report location: build/reports/jacoco/test/html/index.html
```

## Tech Stack

| Category | Technology |
|----------|-----------|
| Framework | Spring Boot 3.5.16 |
| Web Server | Undertow |
| Database | SQLite WAL (default), PostgreSQL, MySQL, MariaDB, SQL Server, and Oracle profiles |
| Cache | Caffeine |
| Messaging | Artemis (Embedded) |
| Security | Spring Security |
| Template | Handlebars 4.5 |
| JSON Path | JsonPath 3.0 |
| Excel | Apache POI |
| Static Analysis | SpotBugs |
| Frontend | Vue.js 3.5 + Bootstrap 5.3 + Bootstrap Icons 1.13 + CodeMirror 5 (WebJars) |
| Build | Gradle 8.14.5 |

## License

MIT License
