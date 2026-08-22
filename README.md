# Echo Mock Server

[![CI](https://github.com/boloagegit/echo-mock-server/actions/workflows/ci.yml/badge.svg)](https://github.com/boloagegit/echo-mock-server/actions/workflows/ci.yml)
[![Docker](https://github.com/boloagegit/echo-mock-server/actions/workflows/push-docker.yml/badge.svg)](https://github.com/boloagegit/echo-mock-server/actions/workflows/push-docker.yml)
![Java 17](https://img.shields.io/badge/Java-17-orange) ![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-green) ![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)

[中文版 README](README_zh-TW.md)

An enterprise-grade dual-protocol mock server supporting HTTP and JMS, designed for simulating API responses in development and testing environments.

## Features

- **Dual Protocol Support** – HTTP REST API and JMS (Artemis) message queues
- **JMS Proxy** – Forwards to TIBCO EMS or Artemis ESB when no matching rule is found
- **Condition Matching** – Returns different responses based on Body (JSON/XML), Query, and Header conditions
- **Tag-Based Organization** – Classify rules with JSON tags (`key:value`), batch enable/disable by tag
- **Response Management** – Manage response content independently; multiple rules can share a single response, with export/import support
- **SSE Streaming** – Server-Sent Events support with editable event sequences, loop modes, and live preview
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
- **Auto Backup** – Scheduled H2/SQLite backup, shutdown backup, and manual trigger
- **Stateful Scenarios** – Optional WireMock-style state machines for multi-step workflows (disabled by default)
- **Fault Injection** – Choose Fault Injection as a rule mode to simulate connection resets and empty responses for resilience testing
- **OpenAPI Import** – Optional preview/import of OpenAPI 3.x or Swagger 2.x JSON/YAML specifications (disabled by default)
- **Faker Data** – Built-in name, email, phone, address, and integer template helpers
- **Rule Testing** – Test rule matching directly from the admin UI
- **Static Analysis** – SpotBugs code analysis
- **No External Database Required** – Embedded H2 by default, with an optional SQLite WAL profile
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
./start-echo.sh --spring.profiles.active=sqlite
```

`SQLITE_TMPDIR` can override the default temporary path. This setting is harmless
when Echo runs with H2 and does not change the configured database profile.

On a Windows development machine, run the equivalent commands in PowerShell:

```powershell
.\gradlew.bat dev
.\gradlew.bat bootRun
.\gradlew.bat bootJar
java -jar (Get-ChildItem build\libs\echo-server-*.jar | Select-Object -First 1).FullName
```

### Migrate H2 to SQLite

The base configuration currently starts with H2. Stop Echo before migrating, and use a target SQLite path that does not already exist:

```bash
python3 scripts/migrate-h2-to-sqlite.py
SPRING_PROFILES_ACTIVE=sqlite ./gradlew bootRun
```

PowerShell:

```powershell
python scripts\migrate-h2-to-sqlite.py
$env:SPRING_PROFILES_ACTIVE="sqlite"
.\gradlew.bat bootRun
```

The migration verifies an H2 recovery backup, copies all application tables into a staged SQLite database in one transaction, compares row counts and per-table SHA-256 digests, runs SQLite integrity and foreign-key checks, and starts Echo for API smoke tests. It atomically publishes `mockdb.sqlite` only after every check passes and never deletes the H2 source. Run `python3 scripts/migrate-h2-to-sqlite.py --help` for non-default paths and automation options.

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
| `TZ` | Asia/Taipei | Timezone |

JVM options are set in the Dockerfile (default `-Xms256m -Xmx512m`, with a heap dump and process exit on OOM). Override by adding `JAVA_OPTS` to docker-compose.yml `environment`.
For production `java -jar` deployments, also pass `-XX:+HeapDumpOnOutOfMemoryError -XX:+ExitOnOutOfMemoryError` and let systemd, Kubernetes, or another supervisor restart the process.

### Optional Features

The following user-facing features are intentionally disabled by default and can be enabled per deployment:

| Variable | Default | Description |
|----------|---------|-------------|
| `ECHO_BULK_IMPORT_EXPORT_ENABLED` | `false` | Shows and enables bulk import/export operations |
| `ECHO_SCENARIOS_ENABLED` | `false` | Enables stateful Scenario rules and Scenario administration |
| `ECHO_RULE_DRAG_SORT_ENABLED` | `false` | Enables drag-and-drop priority ordering in the rule list |

### Access the Service

| Service | URL | Description |
|---------|-----|-------------|
| Admin UI | http://localhost:8080/ | Mock rule management |
| Login Page | http://localhost:8080/login.html | User login |
| Mock Endpoint | http://localhost:8080/mock/** | Intercept HTTP requests |
| Database | — | H2 is the default; enable the `sqlite` profile after migration to use SQLite WAL |

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

Administrators can create multiple Artemis/TIBCO profiles under **System Settings → JMS Forward Connections**, test them, and select one default. Only unmatched messages use this outbound default; Echo's inbound Embedded Artemis connection is unchanged. Once the first database profile is created, database profiles take precedence and the legacy `application.yml` target is used only while no database profile exists. Passwords are stored with AES-GCM encryption and are never returned by the API. Set a stable `ECHO_JMS_CREDENTIAL_KEY` in production; changing it requires re-entering every stored password.

For Artemis Core clients that send XML, configure the sender URL as `tcp://echo-host:61616?minLargeMessageSize=524288`. Text payloads up to roughly 256 KB then use the regular-message path instead of creating one large-message file per request, while larger messages still spill to disk to protect the heap. This is a **sender-side** connection setting, not an Echo `application.yml` property.

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
      timeout-seconds: 30
      queue: TARGET.REQUEST     # Target queue
  http:
    alias: HTTP                 # HTTP protocol display name
  stats:
    retention-days: 7           # Statistics retention days
  request-log:
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
    enabled: true               # Enable SQLite auto backup
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
| GET | /api/admin/rules | List all rules |
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

### Response Management

| Method | Path | Description |
|--------|------|-------------|
| GET | /api/admin/responses | List responses (supports keyword search) |
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
| GET | /api/admin/logs | Request logs (authenticated; filter by ruleId/protocol/matched/endpoint; page/size/sort/direction; afterId for incremental refresh) |
| GET | /api/admin/logs/summary | Request log summary (authenticated) |
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
| POST | /api/admin/builtin-users | Create account (ADMIN) |
| PUT | /api/admin/builtin-users/{id}/enable | Enable account (ADMIN) |
| PUT | /api/admin/builtin-users/{id}/disable | Disable account (ADMIN) |
| DELETE | /api/admin/builtin-users/{id} | Delete account (ADMIN) |
| POST | /api/admin/builtin-users/{id}/reset-password | Reset password (ADMIN) |
| POST | /api/admin/builtin-users/forgot-password | Forgot password (public) |
| POST | /api/admin/builtin-users/register | Self-register (public, must be enabled) |
| PUT | /api/account/change-password | Change own password (authenticated) |

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

Each instance uses local Caffeine cache, synchronized via the `cache_events` table:

```
Instance A modifies rule → Writes to cache_events (RULE_CHANGED)
                                    ↓
Instance B polls every N seconds → Finds new event → Clears local cache
```

- **Polling interval**: Default 5 seconds, configurable via `echo.cache.sync-interval-ms`
- **Event cleanup**: Automatically cleans events older than 10 minutes every hour
- **Performance impact**: Minimal — each poll is a simple timestamp query

### Configuration Example

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

### Activation Conditions

- `echo.storage.mode=database` → Enables cache sync
- `echo.storage.mode=file` → Disabled (not needed for single instance)

## Performance Benchmark

Benchmark numbers are workload-specific and should not be treated as universal product claims. The scripts under `scripts/` are the source of truth for reproducing them.

### Current Echo vs WireMock A/B (2026-08-11)

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
| Database | H2 (default), SQLite WAL profile available |
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
