# Configuration

[← Documentation index](README.md)

The main settings in `application.yml`, and how sign-in and permissions work. For every setting, with comments, see [`src/main/resources/application.yml`](../src/main/resources/application.yml).

## application.yml

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
    forward:
      session-cache-size: 8     # Idle outbound sessions per target; 0 disables reuse
      bounded-waits-enabled: false # Preserve provider retry/failover policy; opt in only after ESB fault testing
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
    temporary-queue-idle-delete-ms: 10000 # Delete temporary reply queues left without a consumer this long (0 = only when the client disconnects)
    target:
      name: ESB-UAT             # Downstream name shown in the UI and request logs (ECHO_JMS_TARGET_NAME); blank shows application.yml
      enabled: false            # Legacy fallback used until a database profile exists
      type: tibco               # artemis or tibco
      server-url: tcp://esb-server:7222
      timeout-seconds: 30        # Valid range: 1–300 seconds; invalid values fail configuration binding
      queue: TARGET.REQUEST     # Target queue
  http:
    alias: HTTP                 # HTTP protocol display name
    transport: native           # Default acceleration; ECHO_HTTP_TRANSPORT=servlet switches back
  stats:
    retention-days: 7           # Statistics retention days
  request-log:
    enabled: true                  # Set false only when intentionally disabling new request logs
    store: database             # memory or database
    max-records: 10000          # Maximum log records
    wait-for-write: false       # true: replies wait until their log is written (slower, 503 when it cannot be)
    buffer:
      max-entries: 20000        # Logs waiting to be written when not waiting; oldest dropped beyond this
      max-mb: 64                # Memory limit for those waiting logs
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
    max-total-bytes: 10737418240 # 10 GiB across completed Echo backups
    max-files: 100              # Maximum retained completed backups
    min-free-bytes: 536870912   # Leave 512 MiB free when admitting a copy
    housekeeping-interval-ms: 3600000 # Independent cleanup every hour
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

Backup housekeeping runs independently of successful backups, starting after 60 seconds and then hourly. Age, byte and file-count limits may shorten history, but cleanup preserves the newest backup that passes SQLite integrity and foreign-key verification, even if it is older than the retention period. A newer corrupt file does not replace that recovery point. If no copy can be verified, existing files are preserved. Only regular files with Echo's dated backup names are eligible; unrelated files, symlinks and directories are not deleted.

Before copying, Echo estimates the snapshot size from SQLite page metadata and checks both the backup capacity and usable space. It refuses a copy that cannot fit while preserving the last verified backup and the free-space reserve. The default environment variables are `ECHO_BACKUP_MAX_TOTAL_BYTES=10737418240`, `ECHO_BACKUP_MAX_FILES=100`, `ECHO_BACKUP_MIN_FREE_BYTES=536870912` and `ECHO_BACKUP_HOUSEKEEPING_INTERVAL_MS=3600000`. Concurrent backup/housekeeping operations do not overlap. A scheduled copy waits interruptibly for maintenance to finish, without borrowing a JDBC connection while waiting. Manual duplicate requests report busy immediately, and housekeeping skips an active copy. These are admission checks: database growth, other writers and files left by a killed process can still consume space after the check. The completed-file budget does not include unrelated files or crash-left partial files. A backup that has already been verified and published remains successful if subsequent housekeeping fails; the independent job retries cleanup.

## Health probes

Anonymous `GET /actuator/health/liveness` and `GET /actuator/health/readiness` return only status. Liveness checks application availability without SQL or broker connection tests. Readiness also checks the database, free disk space and whether the existing JMS listener is receiving; disabled JMS is healthy. A source reconnect returns HTTP 503 from readiness while liveness stays HTTP 200, then readiness returns 200 after recovery. Probes do not open extra TIBCO/Artemis connections or alter transactions, ACK or retry settings. Other Actuator paths retain authentication.

`ECHO_HEALTH_STORAGE_PATH` defaults to `.` for JAR deployments and `/app/data` in the Docker image; `ECHO_HEALTH_MIN_FREE_BYTES` defaults to 536870912 (512 MiB). Point the path at the filesystem holding Echo's writable data if it is a separate mount. Readiness database checks use the configured datasource timeouts; liveness remains independent. Listener state does not prove that every downstream target is responsive or detect every blocked in-flight request. Configure a supervisor's restart probe to use **liveness**, and traffic routing to use **readiness**; see [deployment](install.md#health-probes-and-recovery).

## HTTP acceleration

Overall health also includes `requestLog`: a stopped durable consumer or unavailable spool reports DOWN without deleting retained records. Admin agent statistics expose `durableConsumerRunning`, `lastSuccessfulWriteEpochMillis` (zero until the first persisted batch), and `stoppedAtSequence` (zero unless a corrupt/oversized row stopped consumption). Readiness keeps its existing default policy. To require durable logging before admitting traffic, set `management.endpoint.health.group.readiness.include=readinessState,db,diskSpace,jmsSource,requestLog`; keep request logging out of liveness.

HTTP target connection tests run asynchronously and have a complete deadline controlled by `ECHO_HTTP_FORWARD_TEST_TIMEOUT_SECONDS` (default 30). This includes waiting for pool capacity, redirects and reading a continuously dripping response; per-target connect/read timeouts remain active.

The native HTTP/1.1 path is enabled by default. Its configurable limits are 10 MiB per request or response body, 512 active requests and 128 MiB of estimated buffering. Special requests and custom Servlet extensions keep Servlet handling. Set `ECHO_HTTP_TRANSPORT=servlet` and restart to use the original entry point. See [HTTP acceleration](native-http.md) for limits, timeouts, observations and compatibility, and [HTTP I/O tuning](http-io-tuning.md) for the I/O thread setting.

## Management query capacity and JMS target settings

Request-log list queries (`GET` and `HEAD /api/admin/logs`) are protected after authentication: one active query, up to eight queued requests, and at most five seconds waiting for admission. A full queue or expired wait returns HTTP 429 with `Retry-After: 1`. The wait limit does not interrupt a query or response transfer already running. Pagination and the omitted-size default remain unchanged. Other management routes, HTTP mock requests and JMS do not use this queue. The built-in UI coalesces repeated list refreshes and retries capacity responses. Queue counters appear under `database.logQueryAdmission` in the resource snapshot. Set `ECHO_ADMIN_LOG_QUERY_ADMISSION_ENABLED=false` and restart to disable protection.

JMS target settings are cached by default for single-process SQLite deployments. Warm target selection needs no database connection; the first lookup and lookups after a committed configuration change still need the database. Rollbacks do not publish new settings, and in-flight exchanges retain their selected target version. Shared external databases keep per-exchange reads. Update SQLite target settings through Echo's UI/API; direct changes outside Echo are not watched. Set `ECHO_JMS_TARGET_CATALOG_ENABLED=false` and restart to restore per-exchange database resolution. This setting does not change JMS provider connection, ACK or transaction policies.

Concurrent callers waiting for the same cold lookup share its success or original failure, instead of each starting another database timeout in sequence. A failed load is not cached: later requests can try again after database recovery. This may return database errors sooner during a short outage; it does not resend failed business messages. A load invalidated by a committed settings change cannot overwrite a newer snapshot. Database connection/SQL timeouts and provider waits still apply; this is not an overall JMS deadline.

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
