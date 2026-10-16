# Contributing to Echo Mock Server

Thank you for your interest in contributing! Here's how to get started.

## Development Setup

### Prerequisites

- Java 17+
- Gradle 8.14+ (or use the included `./gradlew` wrapper)

### Getting Started

```bash
# Clone the repository
git clone https://github.com/boloagegit/echo-mock-server.git
cd echo-mock-server

# Run in development mode (no login required)
./gradlew dev

# Build
./gradlew bootJar
```

The server starts at `http://localhost:8080`. While `./gradlew dev` or `./gradlew bootRun` is running, static files are served from `src/main/resources/static`, so a browser refresh picks up UI changes.

## Running Tests

```bash
# Java tests, SpotBugs and the other Gradle checks (what CI runs)
./gradlew check

# Java tests only (`./gradlew t` is a shortcut)
./gradlew test

# Coverage report: build/reports/jacoco/test/html/index.html
./gradlew test jacocoTestReport

# Web console tests (Node.js 20+, no npm install needed)
node --test src/test/js/*.test.cjs

# Script tests (Python 3, standard library only)
python3 -m unittest discover -s scripts/tests -p 'test_*.py'
```

CI runs the Gradle build. Run the Node.js and Python tests yourself when you change the web console or the scripts. Benchmark and stress scripts are described in [scripts/README.md](scripts/README.md) and [docs/performance.md](docs/performance.md).

## Making Changes

1. Fork the repository
2. Create a feature branch: `git checkout -b feature/my-feature`
3. Make your changes
4. Run the tests in [Running Tests](#running-tests) that cover your change
5. Commit with a clear message: `git commit -m "Add: brief description"`
6. Push to your fork: `git push origin feature/my-feature`
7. Open a Pull Request

## Code Style

### Java

- Use braces `{}` for all `if`/`else`/`for`/`while` blocks — no single-line shortcuts
- Use `import` statements instead of fully qualified class names
- Follow standard Java naming conventions
- Add `@Transactional` for write operations
- Use `@CacheEvict` when modifying cached data

### Tests

- New features should include tests; web console logic is tested with `node --test` under `src/test/js/`
- Use `@ExtendWith(MockitoExtension.class)` for mocking
- Cover normal flow, edge cases, and error handling

### Documentation

- User-facing changes go in `CHANGELOG.md` under `[Unreleased]`
- The README is a short landing page; details live in `docs/` (see [docs/README.md](docs/README.md))
- Keep the English and Traditional Chinese pages (`README_zh-TW.md`, `docs/zh-TW/`) in step

### Commits

- Use clear, descriptive commit messages
- Prefix with action: `Add:`, `Fix:`, `Refactor:`, `Docs:`

## Project Structure

```
src/main/java/com/echo/
├── agent/         # Background agents (request-log writer and spool)
├── client/        # Sample Feign client that redirects calls to Echo
├── config/        # Spring configuration, security, startup guards
├── controller/    # REST and mock controllers
├── core/          # Matching engine (conditions, matchSpec, match explanations)
├── diagnostics/   # Bounded transaction diagnostics
├── dto/           # API data objects
├── entity/        # JPA entities
├── jms/           # Embedded broker, listeners, forwarding
├── pipeline/      # HTTP and JMS mock pipelines and responders
├── protocol/      # Protocol adapters
├── repository/    # Data access
├── service/       # Business logic
└── util/          # Shared helpers
src/main/resources/static/   # Web console (Vue 3 via WebJars, no build step)
src/test/java/               # JUnit tests
src/test/js/                 # Web console tests (node --test)
scripts/                     # Benchmarks, migration and validation scripts
docs/                        # Documentation (docs/README.md is the index)
```

### Tech Stack

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

## Reporting Issues

- Use GitHub Issues
- Include steps to reproduce, expected vs actual behavior
- Attach relevant logs or screenshots if possible

## Maintainer Release Process

Formal releases use the **Create Release** GitHub Actions workflow:

1. Ensure `main` is green and contains every intended release change.
2. Open **Actions → Create Release → Run workflow**.
3. Enter a version without the `v` prefix, using `YYYY.MM.DD` or
   `YYYY.MM.DD.N` for an additional release on the same day.
4. Start the workflow from `main`.

The workflow validates the version, runs the full build and static analysis,
smoke-tests the JAR, builds the container, creates an annotated `v<version>`
tag, publishes generated release notes with the JAR and SHA-256 checksum, and
publishes the versioned, commit-SHA, and `latest` container tags to GHCR.

The release version is supplied to Gradle as `-PreleaseVersion=<version>`.
Ordinary local and CI builds continue to derive their version from the nearest
Git tag, falling back to `0.0.0-SNAPSHOT` when no tag exists.

## License

By contributing, you agree that your contributions will be licensed under the MIT License.
