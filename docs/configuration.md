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
