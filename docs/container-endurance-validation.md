# Eight-hour Docker endurance check

`scripts/test-container-endurance.py` runs Echo with SQLite in one disposable,
uniquely named Docker Compose project. The default load lasts **8 hours** after
startup. It alternates HTTP and JMS XML requests, sends a 128 KiB body every
50th request, checks health and Docker resources every minute, and verifies
accepted request IDs and SQLite integrity after stopping. It then restarts
Echo and checks the rules and mock endpoint again.

The test does **not** mount SIT or repository databases, share their backup
directory, trigger a backup, or deliberately cause OOM. Backups are disabled
only inside this test fixture. Run the separate fault-injection checks for OOM
and kill/restart behavior; an endurance pass cannot establish those outcomes.

## Before running

- Check that Docker Compose v2 and Python 3 are available and that the host
  will not sleep during the run.
- Set `--heap` and `--container-memory` to match SIT if its limits differ from
  the defaults (512 MiB JVM heap, 1 GiB container memory). Record the deployed
  Echo version being compared; the default command builds the current checkout.
- Use only synthetic test data. The fixture creates disposable credentials and
  binds its random HTTP/JMS host ports to `127.0.0.1`.

```bash
python3 scripts/test-container-endurance.py --dry-run
python3 scripts/test-container-endurance.py
```

The rate defaults to 30 total requests per minute. Set a representative rate
without exceeding the 100,000-request safety cap:

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
- `gc.log` and any rotated `gc.log.*` files, `echo.log`, and Compose/build
  logs for investigation.

Exit 0 means every requested operation succeeded, Echo stayed healthy during
sampling, accepted IDs appeared in the stopped SQLite database, both sentinel
rules survived restart, and the generated containers, volumes, and image were
removed. Exit 1 means a required check failed. The memory trend is reported,
not classified as a proven leak: Docker memory includes filesystem cache, and
an eight-hour pass cannot rule out slower leaks or different SIT traffic.

The script handles interruption by cleaning up its own Compose project. It
does not run `docker system prune` or stop unrelated containers. Results and
logs remain available after cleanup; inspect them before sharing externally.
