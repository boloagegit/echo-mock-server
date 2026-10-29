# Echo Mock Server

**Mock HTTP APIs and JMS queues from one self-hosted JAR, with a web console that shows why each request matched.**

[![CI](https://github.com/boloagegit/echo-mock-server/actions/workflows/ci.yml/badge.svg)](https://github.com/boloagegit/echo-mock-server/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/boloagegit/echo-mock-server)](https://github.com/boloagegit/echo-mock-server/releases)
![Java 17+](https://img.shields.io/badge/Java-17%2B-orange)
![Spring Boot 3.5](https://img.shields.io/badge/Spring%20Boot-3.5-green)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

**English** · [繁體中文](README_zh-TW.md)

![Echo rule list in dark theme: HTTP and JMS rules grouped by host and queue, with tags, conditions and hit counts](docs/images/rules.png)

Echo stands in for the services your application talks to during development and testing. It answers HTTP calls and JMS messages with responses you define, and it forwards anything it doesn't recognise to the real system. Your team manages the rules in a browser. Echo runs as a single `java -jar` process with an embedded database and message broker, and needs nothing from the internet.

## Why Echo

- **HTTP and JMS in one place.** REST mocks and Artemis queue mocks share the same rules, templates and console. Unmatched JMS messages can be forwarded to TIBCO EMS or an Artemis ESB.
- **See why a request matched.** Every logged request shows its processing flow and the full match chain: which rules were candidates, and which condition passed or failed.
- **Built for teams.** It has logins (built-in accounts or LDAP) with admin and user roles, an audit trail, tags for bulk enable and disable, and rule protection and retention. Several instances can share one database.
- **Fits a locked-down network.** Ship one JAR, start it with `java -jar`, and keep the data in SQLite. The UI assets are bundled through WebJars, so no CDN is needed.
- **Measured HTTP performance.** The latest Java 17 comparison at 512/1024 MiB shows higher fixed-response and JSON-condition throughput than WireMock 3.13.2 in the tested setup. Slow forwarding is roughly tied; RSS and latency vary by configuration. See [Performance](#performance) for all results and limits.

## Quick start

Requires Java 17 or later.

```bash
# Download echo-server-<version>.jar from the Releases page, then:
java -jar echo-server-<version>.jar
```

Or build from source:

```bash
./gradlew bootJar
java -jar build/libs/echo-server-*.jar
```

Open <http://localhost:8080> and sign in with **admin / admin**. Change this password before you share the server. Data is stored in `./data/mockdb.sqlite`.

> Docker, external databases (PostgreSQL, MySQL, MariaDB, SQL Server, Oracle), Windows and multi-instance setups are covered in the [installation guide](docs/install.md).

Spring Boot applications using OpenFeign can download the bundled client SDK after signing in: open the account menu at the bottom left and choose **SDK Center** (`/#/sdk`). The full ZIP includes installation instructions, a local Maven POM and a configuration example. The normal Echo build embeds these downloads; no separate SDK files or internet access are needed on the running server. See the [SDK guide](sdk/README.md).

### Your first mock in one minute

Create a rule. You can do this in the console, or through the REST API as shown here:

```bash
curl -u admin:admin -X POST http://localhost:8080/api/admin/rules \
  -H 'Content-Type: application/json' -H 'Accept: application/json' \
  -d '{
    "protocol": "HTTP",
    "targetHost": "api.example.com",
    "matchKey": "/users/1",
    "method": "GET",
    "status": 200,
    "responseBody": "{\"id\": 1, \"name\": \"Alice\"}"
  }'
```

Call it through `/mock`, and name the real host in `X-Original-Host`:

```bash
curl http://localhost:8080/mock/users/1 -H 'X-Original-Host: api.example.com'
# {"id": 1, "name": "Alice"}
```

To send traffic to Echo without changing your application code, point the client's base URL at Echo, or use an Nginx proxy that sets the header ([usage](docs/install.md#usage)). Requests that match no rule are forwarded to the original host.

Rules can also match on body, query and header conditions, and they can return templated responses:

```json
{
  "protocol": "HTTP",
  "targetHost": "api.example.com",
  "matchKey": "/orders",
  "method": "POST",
  "priority": 10,
  "bodyCondition": "customer.tier=VIP",
  "status": 201,
  "responseBody": "{\"orderId\": \"{{randomValue type='UUID'}}\", \"total\": \"{{jsonPath request.body '$.total'}}\"}"
}
```

## Features

**Matching**
- Conditions on JSON and XML bodies (JSONPath and XPath), query parameters and headers. Advanced expressions use `matchSpec`.
- Priorities, wildcard paths and a built-in rule tester.
- Optional stateful scenarios for multi-step flows, in the style of WireMock.

**Responses**
- Handlebars templates with conditions, loops, JSONPath and XPath, and Faker data. The same templates work for HTTP and JMS.
- Fixed or random delays, fault injection (connection reset or empty response) and Server-Sent Events streams.
- Shared responses that several rules can use.

**Protocols**
- HTTP mocking with proxy fallback to the original host.
- An embedded Artemis broker for JMS. Echo can also listen on a shared external broker, and it can forward unmatched messages to TIBCO EMS or Artemis.

**Console**
- Dark and light themes, and a responsive layout.
- Request logs with traffic charts, a processing-flow view and the match chain.
- Environment labels and accent colours to tell SIT and UAT apart, plus English and Traditional Chinese UI.

**Operations**
- Built-in accounts or LDAP, admin and user roles, remember-me, and an audit trail.
- Tags, batch enable and disable, rule protection, retention and extension, and orphan-response cleanup.
- Scheduled SQLite backups, multi-instance cache sync and resource monitoring.
- Optional OpenAPI and Swagger import, Excel import, and JSON export and import. These are off by default.

## Screenshots

| Request flow | Match chain |
|---|---|
| ![Request detail showing the processing flow from receive to response](docs/images/request-flow.png) | ![Match chain listing each candidate rule and which condition passed or failed](docs/images/match-chain.png) |
| **Rule editor** | **Request logs** |
| ![Rule editor with conditions, response body and template preview](docs/images/rule-editor.png) | ![Request logs with traffic overview, matched, forwarded and failed requests](docs/images/request-logs.png) |

There is also a light theme:

![Rule list in light theme](docs/images/rules-light.png)

## Performance

Fresh validation on **2026-10-29**, using [Echo 037d2d7](https://github.com/boloagegit/echo-mock-server/commit/037d2d7d82eed1be3330f3e7979518dbf5290fad) and actual **WireMock 3.13.2 standalone**. Both ran sequentially on Linux with Java 17.0.20.1+1, a shared four-CPU quota, two CPUs assigned to the server and two to the clients/downstream, G1, `-Xms=-Xmx` at **512 or 1024 MiB**, a 128 MiB direct-memory cap and NMT. Echo used its default native HTTP transport, configured with 80 workers; WireMock used 80 container workers plus 80 asynchronous-response threads. Both had HTTP/1.1 keep-alive, up to 64 connections per route and the same metrics agent. JMS, backups and periodic cleanup were disabled for this HTTP comparison. This is one disclosed configuration, not an exhaustive search for either product's best tuning.

**Short capacity samples, recording off.** All 106 stubs stayed installed, including 100 candidate conditions on one path. These are mean req/s from **three fresh JVMs** per product/heap: 20 s initial warmup, then 5 s warmup and 8 s measurement per case, rotating order. Every response was checked and client CPU headroom passed. These are observed capacities at 64 connections, **not absolute maxima or ten-minute sustained peak rates**.

| Scenario | Echo 512 | WireMock 512 | Echo 1024 | WireMock 1024 |
|---|---:|---:|---:|---:|
| Fixed response, 1 KiB request | 53,020 | 13,774 | 49,943 | 14,057 |
| 100 JSON conditions, 1 KiB request | 37,081 | 4,685 | 34,942 | 4,866 |
| One JSON condition, 64 KiB request | 12,638 | 6,610 | 12,645 | 7,014 |
| Forwarding, 50 ms downstream | 1,196 | 1,198 | 1,193 | 1,199 |

The 50 ms downstream dominates forwarding time, so its throughput is effectively tied. JSON rules use equivalent simple predicates on valid bodies; this is not a claim of identical JSONPath languages or malformed-input semantics. Ratios from these settings must not be treated as version-to-version improvements over earlier tests with different workloads or JVMs.

**Ten-minute mixed load at the same offered rate.** Each product/heap/recording combination used a fresh JVM, 60 s warmup and **600 s measurement at 200 req/s**: fixed/template/100-condition JSON/64 KiB JSON/forwarding at 50/50/80/10/10 req/s. JSON conditions included 10% misses and changing nonces; forwarding sent 64 KiB and received 16 KiB after 50 ms. All eight cells completed **120,000 correct replies each (960,000 total)**, with no response/socket errors, timeouts, OOM or natural full GC. There is one ten-minute run per configuration, not a statistical confidence interval.

Each pair below is **Echo / WireMock**. CPU is whole-process time per completed request; allocation and peak RSS are also process measurements. GC pause totals exclude diagnostic GC performed after traffic; post-GC heap is measured after draining logs and that diagnostic GC.

| Heap / recording | CPU µs/request | Allocation KiB/request | Peak RSS MiB | Post-GC heap MiB | GC pauses ms |
|---|---:|---:|---:|---:|---:|
| 512 / off | 399.3 / 515.0 | 40.4 / 688.2 | 718.9 / 462.1 | 50.9 / 11.6 | 85.7 / 408.6 |
| 512 / on | 1,465.9 / 2,217.8 | 213.7 / 689.1 | 804.6 / 658.8 | 53.3 / 146.2 | 277.0 / 2,435.8 |
| 1024 / off | 375.3 / 499.3 | 40.5 / 678.7 | 1,055.7 / 784.5 | 57.5 / 12.1 | 24.4 / 255.2 |
| 1024 / on | 1,439.8 / 2,216.9 | 214.3 / 674.5 | 1,119.4 / 1,195.1 | 64.9 / 145.9 | 414.7 / 2,371.5 |

P99 below is milliseconds **from scheduled arrival to response completion**, including client dispatch waiting, at the same offered rate; each pair is Echo / WireMock.

| Route | 512 off | 512 on | 1024 off | 1024 on |
|---|---:|---:|---:|---:|
| Fixed | 3.39 / 3.21 | 3.20 / 10.40 | 3.06 / 3.04 | 3.26 / 11.10 |
| Template | 3.47 / 3.28 | 3.33 / 10.37 | 3.07 / 3.20 | 3.42 / 11.04 |
| 100 JSON conditions | 3.27 / 3.42 | 3.47 / 10.66 | 3.05 / 3.28 | 3.53 / 11.68 |
| 64 KiB JSON | 4.67 / 4.43 | 4.35 / 12.93 | 4.66 / 3.87 | 4.40 / 13.82 |
| Forwarding | 58.50 / 56.70 | 58.75 / 67.33 | 57.86 / 56.58 | 58.24 / 67.33 |

Echo does not consistently use less RSS, and latency is workload-specific rather than uniformly better. Both journals were off or configured to retain 10,000 entries. Echo used asynchronous SQLite and a durable spool on disk (default writer cap 1,000/s, batched retention of 9,000–10,000); WireMock used an in-memory journal retaining 10,000. Echo accounted for all 132,000 warmup/measured logs per recorded cell with zero drops, and its shutdown database passed integrity and captured-body checks. Durability and overload policies differ: peak reply throughput does not imply complete logging at that rate. RSS excludes the OS page cache, and heap size is not a process-memory limit.

Fresh correctness checks passed **2,534 Java tests** (6 manual/external tests skipped), **183 JavaScript tests** and **67 Python tests**. [CI](https://github.com/boloagegit/echo-mock-server/actions/runs/40803779258) passed Java 17/21, Windows Java 17 development checks and the container build. Separately, a 512 MiB, ten-minute run combined **96,000 real Artemis JMS replies, 18,000 HTTP replies and eight concurrent admin log readers**, with logging enabled, zero reply/correlation errors or log drops, valid stored match chains and verified shutdown/temporary-queue cleanup. This fixture includes two brokers and JMS callers in the same heap; it is not an Echo-only memory benchmark or TIBCO EMS acceptance.

Ten minutes is a finite regression observation, not proof of indefinite stability or absence of memory leaks. Raw logs, profiles, databases and JARs are kept outside Git. Older comparisons remain in the [performance history](docs/performance.md) and [2026-10-16 validation](docs/native-http-validation-2026-10-16.md); do not mix their absolute numbers with this run. See the [HTTP acceleration guide](docs/native-http.md), [JMS correctness and resource reuse](docs/jms-performance-and-correctness.md), and [startup measurements](docs/performance-hardening-2026-10-16.md) for configuration and earlier evidence.

## Documentation

| Guide | Covers |
|-------|--------|
| [Installation and deployment](docs/install.md) | JAR, Docker, database profiles, multi-instance, Nginx |
| [Configuration and authentication](docs/configuration.md) | `application.yml`, LDAP, built-in accounts, roles |
| [Rules and responses](docs/rules.md) | Priority, conditions, tags, scenarios, templates |
| [JMS](docs/jms.md) | Embedded broker, ESB forwarding, shared-broker setup |
| [REST API](docs/api.md) | The main admin endpoints |
| [All documentation](docs/README.md) | SSE, monitoring, diagnostics, recovery and more |

## Contributing

Bug reports, ideas and pull requests are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) for the build and test steps, and [CHANGELOG.md](CHANGELOG.md) for what changed. Report security issues privately as described in [SECURITY.md](SECURITY.md).

Echo is built with Spring Boot 3.5, Undertow, Caffeine, embedded Artemis, Handlebars, Vue 3 and Bootstrap 5.

## License

[MIT](LICENSE)
