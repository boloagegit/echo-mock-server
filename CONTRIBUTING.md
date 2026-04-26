# Contributing to Echo Mock Server

Thank you for your interest in contributing! Here's how to get started.

## Development Setup

### Prerequisites

- Java 17+
- Gradle 8+ (or use the included `./gradlew` wrapper)

### Getting Started

```bash
# Clone the repository
git clone https://github.com/boloagegit/echo-mock-server.git
cd echo-mock-server

# Run in development mode (no login required)
./gradlew dev

# Run tests
./gradlew test

# Build
./gradlew bootJar
```

The server starts at `http://localhost:8080`.

## Making Changes

1. Fork the repository
2. Create a feature branch: `git checkout -b feature/my-feature`
3. Make your changes
4. Run tests: `./gradlew test`
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

- New features should include unit tests
- Use `@ExtendWith(MockitoExtension.class)` for mocking
- Cover normal flow, edge cases, and error handling

### Commits

- Use clear, descriptive commit messages
- Prefix with action: `Add:`, `Fix:`, `Refactor:`, `Docs:`

## Project Structure

```
src/main/java/com/echo/
├── config/        # Spring configuration
├── controller/    # REST controllers
├── entity/        # JPA entities
├── repository/    # Data access
├── service/       # Business logic
├── jms/           # JMS messaging
└── protocol/      # Protocol handlers
```

## Reporting Issues

- Use GitHub Issues
- Include steps to reproduce, expected vs actual behavior
- Attach relevant logs or screenshots if possible

## License

By contributing, you agree that your contributions will be licensed under the MIT License.
