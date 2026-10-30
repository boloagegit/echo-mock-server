# REST API

[← Documentation index](README.md)

The main admin API endpoints behind the web console. ADMIN marks administrator-only endpoints.

**Access.** Writes (POST, PUT, DELETE) need a signed-in user, except sign-in itself (`/api/auth/**`) and the account requests `POST /api/admin/builtin-users/forgot-password` and `POST /api/admin/builtin-users/register`. Some read-only GET endpoints are public by default:
- `/api/admin/status`
- rule reads: `/api/admin/rules/{id}`, `/rules/page`, `/rules/groups`, `/rules/group`, `/rules/import-template`, `/rules/{id}/audit` and `/rules/{id}/order` (`/rules/export` stays ADMIN-only)
- `/api/admin/audit`
- `/api/admin/responses/{id}`, `/responses/{id}/rules` and `/responses/summary`

The full lists `GET /api/admin/rules` and `GET /api/admin/responses`, and the request logs, need sign-in. `src/main/java/com/echo/config/SecurityConfig.java` is the authority.

**Coverage.** This page does not list every route. The console also calls endpoints for declarative rules, scenarios, rule-order analysis, HTTP and JMS target connections, resource monitoring, and request-log details.

## Rule Management

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
| POST | /api/admin/rules/import/preview | Preview portable JSON rules and destination connection choices (ADMIN) |
| POST | /api/admin/rules/import/confirm | Atomically create new rules/responses with explicit connection mappings (ADMIN) |
| POST | /api/admin/rules/import-excel | Excel import (ADMIN) |
| GET | /api/admin/rules/import-template | Download Excel import template |
| DELETE | /api/admin/rules/batch | Batch delete (ADMIN) |
| DELETE | /api/admin/rules/all | Delete all (ADMIN) |

Rule responses include durable `createdBy` and `updatedBy` metadata. Existing rows remain readable after upgrade; an old row reports no operator until it is next modified.

## Response Management

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

## Log Queries

| Method | Path | Description |
|--------|------|-------------|
| GET | /api/admin/logs | Request logs (authenticated; filter by ruleId/protocol/matched/endpoint; from/to request time (local `YYYY-MM-DDTHH:mm:ss`, from inclusive, to exclusive); page/size/sort/direction; afterId for incremental refresh) |
| GET | /api/admin/logs/summary | Request log summary (authenticated) |
| GET | /api/admin/logs/traffic | Traffic overview of the retained request logs (authenticated; `range=1h\|24h\|7d`): per-bar counts of mock responses, forwards, unmatched requests and failed forwards, estimated response times and the most often unmatched requests. Counted in the database and shared for 5 seconds, so concurrent viewers trigger one computation |
| DELETE | /api/admin/logs/all | Delete all request logs (ADMIN) |
| GET | /api/admin/rules/{id}/audit | Audit logs for a rule |
| GET | /api/admin/audit | Audit logs. With `page`, returns one page of summaries without the before/after snapshots: `keyword` matches the rule or response ID, path, queue name or description; `type=RULE\|RESPONSE`; `ruleId` for one rule or response (`response-{id}`); `action`, `operator`, `size`, `sort`, `direction`. Each summary carries `targetName`, `targetProtocol` and a small `summary` of what changed. `detailId={id}` returns one log with its snapshots |
| DELETE | /api/admin/audit/all | Delete all audit logs (ADMIN) |

## System Management

Anonymous operational probes: `GET /actuator/health/liveness` checks process availability, and `GET /actuator/health/readiness` also checks DB, free disk space and the existing JMS source listener. Responses contain only `status`; unhealthy readiness returns 503. Other Actuator endpoints retain authentication. See [configuration](configuration.md#health-probes).

| Method | Path | Description |
|--------|------|-------------|
| GET | /api/admin/status | System status (JVM, DB, statistics) |
| GET | /api/admin/backup/status | Backup status and file list |
| POST | /api/admin/backup | Trigger manual backup |

## Account Management

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

## Issue Reports

Requires `ECHO_ISSUE_REPORTING_ENABLED`; while it is off these endpoints return 404.

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

## JMS Testing

| Method | Path | Description |
|--------|------|-------------|
| POST | /api/admin/jms/test | Send test message to ECHO.REQUEST |
