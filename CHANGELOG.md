# Changelog

All notable changes to Echo Mock Server will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/), and this project adheres to [Calendar Versioning](https://calver.org/) (`YYYY.MM.DD`).

## [Unreleased]

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
