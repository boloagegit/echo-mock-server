# Echo documentation

[← Back to the project](../README.md) · [繁體中文](zh-TW/README.md)

## Getting started

| Guide | What you'll find |
|-------|------------------|
| [Installation and deployment](install.md) | Run the JAR, Windows, Docker, database profiles, migrating from H2, multi-instance setups, and sending traffic to Echo (direct calls, Nginx, outbound HTTPS) |
| [Configuration and authentication](configuration.md) | The main `application.yml` settings, auth modes, roles and built-in accounts |
| [Rules and matching](rules.md) | Priority, tags, HTTP and JMS rules, condition syntax, `matchSpec`, stateful scenarios and response templates |
| [JMS](jms.md) | The embedded broker, forwarding to TIBCO EMS or Artemis, and receiving from a shared broker |
| [REST API](api.md) | The main admin endpoints behind the console |

## Features in depth

| Guide | What you'll find |
|-------|------------------|
| [SSE mocks](sse-mock.md) | The Server-Sent Events contract, event sequences and playback |
| [Resource monitoring](resource-monitoring.md) | Resource snapshots and JMS policy diagnostics in Settings |
| [Transaction diagnostics](transaction-diagnostics.md) | Bounded per-transaction diagnostics for hard-to-reproduce problems |
| [SQLite recovery](sqlite-recovery.md) | How Echo checks and recovers a SQLite database at startup |

## Performance and validation

| Document | What you'll find |
|----------|------------------|
| [Performance](performance.md) | Echo vs WireMock, request-log modes, known bottlenecks and how to rerun the benchmarks (Traditional Chinese) |
| [HTTP acceleration](native-http.md) | Default native transport, limits, compatibility and rollback (Traditional Chinese) |
| [Native HTTP validation](native-http-validation-2026-10-16.md) | Current comparisons, latency, lifecycle checks and evidence (Traditional Chinese) |
| [JMS performance and correctness](jms-performance-and-correctness.md) | Session reuse, reply isolation and transaction behavior (Traditional Chinese) |
| [HTTP I/O tuning](http-io-tuning.md) | The Undertow I/O thread setting, and when changing it helps (Traditional Chinese) |
| [Performance and startup hardening](performance-hardening-2026-10-16.md) | JMS session reuse, a 15% shorter startup, and earlier HTTP experiments (Traditional Chinese) |
| [Native HTTP experiment](native-http-performance-2026-10-15.md) | The 2026-10-15 prototype compared with WireMock (Traditional Chinese) |
| [HTTP performance investigation](performance-investigation-2026-10-15.md) | CPU sampling, I/O threads and overload recovery from 2026-10-15 (Traditional Chinese) |
| [Container resilience validation](container-resilience-validation.md) | The JMS XML pressure, restart and storage harness |
| [Container endurance validation](container-endurance-validation.md) | The 8-hour and 24-hour endurance runs |
| [Archive](archive/README.md) | Older benchmarks and engineering notes |

## Project

| Document | What you'll find |
|----------|------------------|
| [Roadmap](ROADMAP.md) | Feature status and plans (Traditional Chinese) |
| [Engine extensibility design](design/engine-extensibility.md) | The matching-engine and protocol design (Traditional Chinese) |
| [Contributing](../CONTRIBUTING.md) | Build, test and pull-request steps |
| [Changelog](../CHANGELOG.md) | What changed in each release |
| [Scripts](../scripts/README.md) | Benchmark, migration and validation scripts |
