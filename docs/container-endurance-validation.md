# Docker endurance check

`scripts/test-container-endurance.py` runs Echo with SQLite in one disposable,
uniquely named Docker Compose project. The regular default remains an 8-hour
compatibility run. `--uat-24h` selects the 24-hour profile: **24
hours, 4 requests/second, `-Xmx3g`, a 6 GiB container limit, and a 400,000
request safety cap**. That profile schedules 345,600 requests.
`--uat-2h-40tps` selects the high-rate profile: **2 hours,
40 requests/second, `-Xmx3g`, a 6 GiB container limit, and a 300,000 request
safety cap**. That profile schedules 288,000 requests.
`--sqlite-large-xml` selects the response-pressure profile: **3 hours, 4
requests/second, `-Xmx3g`, a 6 GiB container limit, and alternating 5 MiB plain
and templated XML responses**. Two of every four requests receive a large XML
response; JSON-template and JMS traffic remain as controls.

The load rotates through a static HTTP response, a JSON `jsonPath` template,
an XML `xPath` template, and embedded JMS. A 128 KiB body is sent every 50th
request; other bodies are 8 KiB. The runner uses bounded concurrent clients and
fixed arrival timestamps. It records a schedule miss instead of lowering the
offered rate or sending a catch-up burst. Every five-minute window must submit
at least 99% of its scheduled slots; a lower window makes the run invalid.

The test does **not** mount host or repository databases, share their backup
directory, trigger a backup, or deliberately cause OOM. Backups are disabled
only inside this test fixture. Run the separate fault-injection checks for OOM
and kill/restart behavior; an endurance pass cannot establish those outcomes.

## Before running

- Check that Docker Compose v2 and Python 3 are available and that the host
  will not sleep during the run.
- Preset runs require Docker to provide the configured container memory in
  addition to host and Docker runtime overhead.
- Record the deployed Echo version being compared; the runner builds the
  current checkout and records its output in the run artifacts.
- Use only synthetic test data. The fixture creates disposable credentials and
  binds its random HTTP/JMS host ports to `127.0.0.1`.

```bash
python3 scripts/test-container-endurance.py --dry-run
python3 scripts/test-container-endurance.py --uat-24h --dry-run
python3 scripts/test-container-endurance.py --uat-24h
python3 scripts/test-container-endurance.py --uat-2h-40tps --dry-run
python3 scripts/test-container-endurance.py --uat-2h-40tps
python3 scripts/test-container-endurance.py --sqlite-large-xml --dry-run
python3 scripts/test-container-endurance.py --sqlite-large-xml
```

The compatibility profile defaults to 30 total requests per minute and a
100,000-request safety cap. Custom runs can raise `--max-requests`, but the
runner never allows more than 400,000:

```bash
python3 scripts/test-container-endurance.py \
  --duration-seconds 28800 --requests-per-minute 30 \
  --heap 512m --container-memory 1024m
```

For a short end-to-end smoke check, `--duration-seconds 30 --sample-seconds 10`
uses the same startup, load, SQLite audit, restart, and cleanup path. The older
`test-container-resilience.py --mode soak` is message-count-based and includes
an intentional crash; it is **not** this timed endurance test.

## Evidence and result

Each run writes under `artifacts/container-endurance/<run-id>/`:

- `result.json`: pass/fail, accepted and missing IDs, SQLite integrity and
  foreign-key checks, restart read/write result, and cleanup evidence.
- `requests.jsonl`: synthetic request IDs and acceptance status.
- `samples.jsonl`: health, Docker CPU/memory/PIDs, and SQLite/WAL sizes.
- `hour-NN.json` and `hour-NN.md`: hourly offered/completed load, misses,
  failures, p95/p99 latency, heap, container memory, and DB/WAL checkpoint.
- `gc.log` and any rotated `gc.log.*` files, `echo.log`, and Compose/build
  logs for diagnostics.

Every minute the runner captures the authenticated status payload (including
JVM heap and rule-cache statistics), agent/spool status, container resources,
and SQLite/WAL sizes. Request-log retention remains at 10,000. The stopped
database audit therefore verifies its retained lower bound, known synthetic
IDs, spool checkpoint/drain, integrity, and the four sentinel rules rather than
requiring all 345,600 old log rows to remain.

Exit 0 means every submitted operation succeeded, the fixed arrival schedule
was sustained, Echo stayed healthy during sampling, retained logs and the
durable checkpoint were valid, all sentinel rules survived restart, and the
generated containers, volumes, and image were removed. Exit 1 means a required
check failed. The memory trend is reported, not classified as a proven leak:
Docker memory includes filesystem cache, and a pass cannot rule out different
traffic patterns or host contention.

The script handles interruption by cleaning up its own Compose project. It
does not run `docker system prune` or stop unrelated containers. Results and
logs remain available after cleanup; inspect them before sharing externally.
