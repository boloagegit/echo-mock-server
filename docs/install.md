# Installation and Deployment

[← Documentation index](README.md)

How to run Echo: from a single JAR to Docker, external databases and several instances behind a load balancer, and how to send traffic to it.

## Prerequisites

- Java 17+ to run a packaged JAR; install JDK 17 for development and the Gradle toolchain
- Use the included Gradle wrapper (`gradlew.bat` on Windows, `./gradlew` on Linux)

## Start the Server

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

Windows development uses PowerShell and the same application as Linux deployment; WSL, Git Bash and a separate Gradle installation are not needed. Set `JAVA_HOME` and the IDE's Gradle JVM to JDK 17. Run from the repository root, preferably a short local path such as `C:\src\echo`:

```powershell
.\gradlew.bat dev
# Stop with Ctrl+C; the commands below are separate tasks.
.\gradlew.bat bootRun
.\gradlew.bat check -PtestForks=2
# Stop Echo before clean; Windows locks files still in use.
.\gradlew.bat clean bootJar
if ($LASTEXITCODE -ne 0) { throw 'Build failed' }
$echoJars = @(Get-ChildItem build/libs/echo-server-*.jar -File)
if ($echoJars.Count -ne 1) { throw 'Expected exactly one packaged Echo JAR' }
java -jar ($echoJars[0].FullName)
```

HTTP acceleration is enabled on Windows too. PowerShell uses `$env:ECHO_HTTP_TRANSPORT = 'servlet'` to switch back, then restart Echo. Remove the override with `Remove-Item Env:ECHO_HTTP_TRANSPORT -ErrorAction SilentlyContinue` before restarting to return to the default. Full limits and compatibility are in [HTTP acceleration](native-http.md).

Windows-built Java bytecode can run on Linux with a compatible Java runtime. Deployment uses the packaged JAR, Linux paths and deployment configuration; keep the local SQLite/Artemis data and `dev` profile out of the deployment. The Linux CI/container checks remain the deployment checks, and performance benchmarks run on Linux. The first Gradle build still needs access to the dependency repositories or an already populated company cache.

## Database profiles

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

## Migrating from H2

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
| `ECHO_REQUEST_LOG_WAIT_FOR_WRITE` | `false` (default): replies do not wait for the request log to be written, and the oldest waiting entries are dropped when writing falls behind. `true`: each reply waits until its log is in the spool |
| `ECHO_REQUEST_LOG_BUFFER_MAX_ENTRIES` / `ECHO_REQUEST_LOG_BUFFER_MAX_MB` | Limits for request logs waiting to be written when not waiting for writes; default `20000` entries / `64` MB |
| `ECHO_CACHE_TEMPLATE_MAX_WEIGHT_MB` | Compiled-template cache budget in MiB; default `16`; oversized templates still render without being retained |
| `ECHO_DB_POOL_MAX_SIZE` / `ECHO_DB_POOL_MIN_IDLE` | Hikari connection-pool sizing for external profiles |

The remaining Hikari timeout and lifetime settings use the `ECHO_DB_*` variables in the profile files. Do not put multiple database profiles in `SPRING_PROFILES_ACTIVE`.

The SQLite profile keeps a small connection pool for WAL readers and
automatically queues JPA write transactions inside Echo, so normal concurrent
writes do not race for SQLite's single-writer lock. `busy_timeout` remains a
fallback for locks held by another process. Run only one Echo process against a
given SQLite file; use a server database when multiple application instances
must share storage. See [SQLite recovery](sqlite-recovery.md) for recovery,
writer metrics, and validation boundaries.

#### Backup and request-log spool boundaries

The application backup feature is for local SQLite files. External database profiles disable it; PostgreSQL, MySQL/MariaDB, SQL Server, and Oracle backups, point-in-time recovery, retention, and restore drills are owned by the DBA or platform backup service.

By default a mock reply does not wait for its request log to be written. The entry waits in memory, at most `echo.request-log.buffer.max-entries` (20,000) entries and `echo.request-log.buffer.max-mb` (64 MB), and a background thread writes these entries to the spool in batches. When logs arrive faster than they can be written, the oldest waiting entries are dropped, so the newest stay. A warning gives the number dropped, at most every 30 seconds, and the log agent status counts them. In this mode request logging does not delay or fail mock replies. Entries that are still waiting are lost if the process stops unexpectedly; on a normal shutdown Echo writes them out first, waiting up to 5 seconds. The spool then commits without waiting for each write to reach the disk (SQLite `synchronous=NORMAL`): a crash of Echo loses nothing that was committed, but a power or operating-system failure can lose the last commits. Set `echo.request-log.wait-for-write=true` (`ECHO_REQUEST_LOG_WAIT_FOR_WRITE`) when every reply must wait until its log is in the spool. In that mode a reply fails with 503 (and a JMS message is rolled back) when the log cannot be written, as before; replies are slower.

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

## Docker RDBMS smoke test

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

## Docker Deployment

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
| `ECHO_UI_EASTER_EGG` | true | Hidden easter egg: a small game opened with ↑↑↓↓←→←→AB or by clicking the Echo logo five times quickly; set `false` to turn it off in production |
| `TZ` | Asia/Taipei | Timezone |

JVM options are set in the Dockerfile (default `-Xms256m -Xmx512m`, with a heap dump and process exit on OOM). Override by adding `JAVA_OPTS` to docker-compose.yml `environment`.
For production `java -jar` deployments, also pass `-XX:+HeapDumpOnOutOfMemoryError -XX:+ExitOnOutOfMemoryError` so that an out-of-memory error leaves a heap dump showing its cause.

## Health probes and recovery

Use `GET /actuator/health/liveness` to check process availability, and `GET /actuator/health/readiness` to check whether Echo can serve with its database, disk and JMS source listener available. Both endpoints are anonymous and return only status. Configure restart decisions against liveness; use readiness for traffic routing. An external broker outage makes readiness return 503 while liveness remains 200, so a reconnect does not create a restart loop. After broker recovery, readiness returns 200 without restarting Echo.

Docker's health check uses readiness. `restart: unless-stopped` restarts an exited process, including configured OOM/corruption exits; **Docker Compose does not restart a running container solely because it is unhealthy**. A deployment that must restart an unresponsive process needs a supervisor/orchestrator that checks liveness with a timeout and consecutive-failure threshold. Do not use readiness failures alone as the restart trigger. The application does not force-close transactions merely because a probe fails.

The container runs Java as PID 1, so stop signals reach the JVM. Compose allows 60 seconds for shutdown; deployments with longer transactions or large shutdown backups should choose an appropriate grace period. Abrupt termination can still interrupt a backup, and the admission-space check cannot reserve space against other processes. Keep the scheduled verified snapshots and test recovery; a shutdown backup is not a replacement for them. Configure the writable data mount with enough space for live data, spool, broker data, logs, a new snapshot and retained snapshots. See [backup limits and health settings](configuration.md#health-probes).

```bash
curl --fail http://localhost:8080/actuator/health/liveness
curl --fail http://localhost:8080/actuator/health/readiness
```

On Windows/PowerShell, use `Invoke-RestMethod http://localhost:8080/actuator/health/readiness`. Development uses the same endpoints and filesystem APIs; no Linux shell or Docker is required.

## Optional Features

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

## Access the Service

| Service | URL | Description |
|---------|-----|-------------|
| Admin UI | http://localhost:8080/ | Mock rule management |
| Login Page | http://localhost:8080/login.html | User login |
| Mock Endpoint | http://localhost:8080/mock/** | Intercept HTTP requests |
| Database | — | SQLite (`./data/mockdb.sqlite`) is the default; select exactly one database profile for an external database |

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

Login sessions are kept in each instance's memory, so configure the load balancer to keep a user on the same instance (sticky sessions). For JMS across instances, see [Receiving JMS from a Shared Broker](jms.md#receiving-jms-from-a-shared-broker-multi-instance).

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
