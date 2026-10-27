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
