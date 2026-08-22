import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest import mock


PROJECT_ROOT = Path(__file__).resolve().parents[2]


def load_script(module_name: str, filename: str):
    spec = importlib.util.spec_from_file_location(module_name, PROJECT_ROOT / "scripts" / filename)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


class FakeProcess:
    def __init__(self, pid=4321):
        self.pid = pid

    def poll(self):
        return None

    def wait(self, timeout=None):
        return 0

    def send_signal(self, value):
        raise OSError("console signal unavailable")

    def kill(self):
        return None


class MigrationWindowsCompatibilityTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.migration = load_script("echo_h2_sqlite_migration", "migrate-h2-to-sqlite.py")

    def test_start_sqlite_app_uses_environment_for_paths_with_spaces(self):
        fake_process = FakeProcess()
        with tempfile.TemporaryDirectory(prefix="Echo Windows Path ") as temp_dir:
            base = Path(temp_dir)
            database = base / "data with spaces" / "mockdb.sqlite"
            database.parent.mkdir()
            log_path = base / "startup.log"
            formal_spool = base / "formal-request-log-spool.sqlite"
            with mock.patch.object(self.migration.sys, "platform", "win32"), \
                    mock.patch.object(self.migration, "free_port", return_value=18081), \
                    mock.patch.object(self.migration, "request_json", return_value=(200, {
                        "datasourceUrl": self.migration.sqlite_jdbc_url(database)
                    })), \
                    mock.patch.dict(self.migration.os.environ, {
                        "ECHO_REQUEST_LOG_SPOOL_PATH": str(formal_spool)
                    }), \
                    mock.patch.object(self.migration, "terminate_process"), \
                    mock.patch.object(self.migration.subprocess, "Popen", return_value=fake_process) as popen:
                self.migration.start_sqlite_app(database, log_path, expected=None)

        command = popen.call_args.args[0]
        options = popen.call_args.kwargs
        self.assertTrue(command[0].endswith("gradlew.bat"))
        self.assertNotIn("--args", " ".join(command))
        self.assertIn("data with spaces", options["env"]["SPRING_DATASOURCE_URL"])
        self.assertEqual("sqlite", options["env"]["SPRING_PROFILES_ACTIVE"])
        spool_path = Path(options["env"]["ECHO_REQUEST_LOG_SPOOL_PATH"])
        self.assertNotEqual(database, spool_path)
        self.assertNotEqual(
            self.migration.PROJECT_ROOT / "data" / "request-log-spool.sqlite", spool_path
        )
        self.assertNotEqual(formal_spool, spool_path)
        self.assertIn("request-log-spool-", spool_path.name)
        self.assertNotEqual(0, options["creationflags"])

    def test_each_staging_start_gets_a_distinct_request_log_spool(self):
        fake_process = FakeProcess()
        with tempfile.TemporaryDirectory() as temp_dir:
            base = Path(temp_dir)
            database = base / "staging.sqlite"
            log_path = base / "startup.log"
            with mock.patch.object(self.migration, "free_port", return_value=18081), \
                    mock.patch.object(self.migration, "request_json", return_value=(200, {
                        "datasourceUrl": self.migration.sqlite_jdbc_url(database)
                    })), \
                    mock.patch.object(self.migration, "terminate_process"), \
                    mock.patch.object(self.migration.subprocess, "Popen", return_value=fake_process) as popen:
                self.migration.start_sqlite_app(database, log_path, expected=None)
                self.migration.start_sqlite_app(database, log_path, expected=None)

        spool_paths = [
            Path(call.kwargs["env"]["ECHO_REQUEST_LOG_SPOOL_PATH"])
            for call in popen.call_args_list
        ]
        self.assertEqual(2, len(set(spool_paths)))

    def test_windows_publish_skips_unsupported_directory_fsync(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            base = Path(temp_dir)
            staging = base / "staging.sqlite"
            target = base / "published.sqlite"
            candidate = base / "candidate.sqlite"
            with self.migration.sqlite3.connect(staging) as connection:
                connection.execute("CREATE TABLE sample (id INTEGER PRIMARY KEY, value TEXT)")
                connection.execute("INSERT INTO sample(value) VALUES ('ok')")

            with mock.patch.object(self.migration.sys, "platform", "win32"), \
                    mock.patch.object(self.migration.os, "open", side_effect=AssertionError(
                        "Windows must not open a directory for fsync"
                    )):
                self.migration.publish_atomic_database(staging, target, candidate)

            self.assertTrue(target.is_file())
            with self.migration.sqlite3.connect(target) as connection:
                self.assertEqual("ok", connection.execute("SELECT value FROM sample").fetchone()[0])

    def test_windows_termination_falls_back_to_taskkill_for_process_tree(self):
        process = FakeProcess(pid=9876)
        with mock.patch.object(self.migration.sys, "platform", "win32"), \
                mock.patch.object(self.migration.signal, "CTRL_BREAK_EVENT", 1, create=True), \
                mock.patch.object(self.migration.subprocess, "run") as run:
            self.migration.terminate_process(process)

        self.assertEqual(
            ["taskkill", "/PID", "9876", "/T", "/F"],
            run.call_args.args[0],
        )


class CrashScriptWindowsCompatibilityTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.crash = load_script("echo_sqlite_crash_test", "test-sqlite-crash-resilience.py")

    def test_windows_start_uses_sqlite_temp_paths_and_new_process_group(self):
        fake_process = FakeProcess()
        with tempfile.TemporaryDirectory(prefix="Echo Crash Test ") as temp_dir:
            base = Path(temp_dir)
            with mock.patch.object(self.crash.sys, "platform", "win32"), \
                    mock.patch.object(self.crash, "DB_FILE", base / "database with spaces.sqlite"), \
                    mock.patch.object(self.crash, "SPOOL_FILE", base / "spool with spaces.sqlite"), \
                    mock.patch.object(self.crash, "JAR_FILE", base / "echo server.jar"), \
                    mock.patch.object(self.crash, "BASE_URL", "http://127.0.0.1:18082"), \
                    mock.patch.object(self.crash.subprocess, "Popen", return_value=fake_process) as popen:
                self.crash.start_server()

        command = popen.call_args.args[0]
        options = popen.call_args.kwargs
        self.assertIn("--spring.profiles.active=sqlite", command)
        self.assertTrue(any("database with spaces.sqlite" in item for item in command))
        self.assertTrue(any("spool with spaces.sqlite" in item for item in command))
        self.assertNotEqual(0, options["creationflags"])


if __name__ == "__main__":
    unittest.main()
