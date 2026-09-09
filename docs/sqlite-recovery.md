# SQLite recovery

Echo verifies a file-backed SQLite database before Spring creates the
application DataSource. Normal WAL recovery still belongs to SQLite; Echo only
intervenes when SQLite reports corruption.

## Modes

The default is fail-safe and does not replace data:

```yaml
echo:
  sqlite:
    recovery:
      mode: fail-fast
```

- `fail-fast`: stop startup and preserve the live database when integrity or
  foreign-key verification fails.
- `restore-latest`: only for confirmed corruption, select the newest backup
  that passes both checks, quarantine the live database plus `-wal` and `-shm`
  sidecars, restore the backup, verify it again, and write a recovery receipt.

Set the opt-in mode with `ECHO_SQLITE_RECOVERY_MODE=restore-latest`. A lock,
permission failure, or unreadable path never triggers an automatic restore.

While Echo is running, a lightweight `PRAGMA quick_check` runs every 30
seconds. Temporary lock or connection failures are retried. Confirmed
corruption terminates Echo with exit code 70 so the external supervisor can
restart it and apply the selected startup mode. Configure the interval with
`ECHO_SQLITE_RUNTIME_CHECK_INTERVAL_MS`; the guard can be disabled with
`ECHO_SQLITE_RUNTIME_CHECK_ENABLED=false` only when another supervisor owns
equivalent corruption detection.

An automatic restore can lose commits made after the selected backup. The
quarantined files and `sqlite-recovery-*.properties` receipt are retained for
operator review.

## OOM behavior

The Docker runtime uses `-XX:+ExitOnOutOfMemoryError`. A JVM OOM therefore
terminates the complete Echo process instead of leaving a process alive with a
failed embedded database. Docker Compose uses `restart: unless-stopped`; after
restart, the SQLite startup verification runs before the DataSource opens.

## Validation

Both resilience scripts use disposable databases and never target repository
`mockdb.*` files:

```bash
./gradlew bootJar
python3 scripts/test-sqlite-crash-resilience.py
python3 scripts/test-sqlite-recovery-resilience.py
```

The second command records its result and process logs below
`artifacts/sqlite-resilience/`.
