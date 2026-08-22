import contextlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest
from unittest import mock


PROJECT_ROOT = Path(__file__).resolve().parents[2]


def load_script(module_name, filename):
    spec = importlib.util.spec_from_file_location(
        module_name, PROJECT_ROOT / "scripts" / filename
    )
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


class RdbmsMatrixPerformanceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.matrix = load_script("echo_rdbms_matrix", "test-rdbms-matrix.py")

    def test_arm_sqlserver_is_functionally_valid_but_not_comparable(self):
        comparison = self.matrix.performance_comparison(
            "sqlserver", {"platform": "darwin", "machine": "arm64"}
        )
        self.assertFalse(comparison["comparable"])
        self.assertEqual("valid", comparison["functional_validation"])
        self.assertIn("x86 emulation", comparison["reason"])

    def test_dry_run_contains_identical_performance_arguments(self):
        output = io.StringIO()
        with tempfile.TemporaryDirectory() as temp_dir, contextlib.redirect_stdout(output):
            status = self.matrix.main([
                "--databases", "h2,sqlite",
                "--performance",
                "--performance-duration", "1",
                "--performance-concurrency", "2",
                "--output-dir", temp_dir,
                "--dry-run",
            ])
        self.assertEqual(0, status)
        plan = json.loads(output.getvalue())
        self.assertEqual({"duration_seconds": 1, "concurrency": 2}, {
            key: plan["performance"]["parameters"][key]
            for key in ("duration_seconds", "concurrency")
        })
        commands = []
        for database in plan["databases_plan"]:
            self.assertEqual(plan["host"], database["host"])
            commands.append(next(
                step["command"] for step in database["steps"]
                if step["label"] == "performance-rps"
            ))
        self.assertEqual(commands[0][3:5], ["1", "2"])
        self.assertEqual(commands[0][3:5], commands[1][3:5])

    def test_performance_is_skipped_when_compose_up_fails(self):
        def fake_run_command(command, **kwargs):
            label = kwargs["label"]
            return {"label": label, "status": "failed" if label == "compose-up" else "passed"}

        with tempfile.TemporaryDirectory() as temp_dir, \
                mock.patch.object(self.matrix, "run_command", side_effect=fake_run_command), \
                mock.patch.object(self.matrix, "wait_for_http") as wait_for_http:
            result = self.matrix.execute_database(
                "h2",
                compose_file=PROJECT_ROOT / "docker-compose.rdbms.yml",
                project_name="echo-rdbms-matrix-test",
                ports=self.matrix.PortPair(18080, 17616),
                skip_build=True,
                keep=True,
                output_dir=Path(temp_dir),
                command_timeout=10,
                readiness_timeout=10,
                dry_run=False,
                host={"platform": "darwin", "machine": "arm64"},
                performance=True,
                performance_duration=1,
                performance_concurrency=1,
            )
        self.assertFalse(result["passed"])
        self.assertEqual("skipped", result["performance"]["status"])
        self.assertIn("compose-up", result["performance"]["reason"])
        wait_for_http.assert_not_called()

    def test_successful_performance_json_is_embedded_in_database_result(self):
        expected = {
            "passed": True,
            "parameters": {"duration_seconds": 1, "concurrency": 2},
            "total_requests": 42,
        }

        def fake_run_command(command, **kwargs):
            label = kwargs["label"]
            if label == "performance-rps":
                output_path = Path(command[command.index("--json-output") + 1])
                output_path.parent.mkdir(parents=True, exist_ok=True)
                output_path.write_text(json.dumps(expected), encoding="utf-8")
            return {"label": label, "status": "passed", "returncode": 0}

        def fake_readiness(_url, **kwargs):
            return {"label": kwargs["label"], "status": "passed", "http_status": 200}

        with tempfile.TemporaryDirectory() as temp_dir, \
                mock.patch.object(self.matrix, "run_command", side_effect=fake_run_command), \
                mock.patch.object(self.matrix, "wait_for_http", side_effect=fake_readiness):
            result = self.matrix.execute_database(
                "h2",
                compose_file=PROJECT_ROOT / "docker-compose.rdbms.yml",
                project_name="echo-rdbms-matrix-test",
                ports=self.matrix.PortPair(18080, 17616),
                skip_build=True,
                keep=True,
                output_dir=Path(temp_dir),
                command_timeout=10,
                readiness_timeout=10,
                dry_run=False,
                host={"platform": "linux", "machine": "x86_64"},
                performance=True,
                performance_duration=1,
                performance_concurrency=2,
            )

        self.assertTrue(result["passed"])
        self.assertEqual("passed", result["performance"]["status"])
        self.assertTrue(result["performance"]["passed"])
        self.assertEqual(expected, result["performance"]["result"])

    def test_matrix_performance_summary_keeps_parameters_and_comparison(self):
        summary = self.matrix.performance_result_summary({
            "database": "sqlserver",
            "performance": {
                "status": "skipped",
                "passed": None,
                "parameters": {"duration_seconds": 1, "concurrency": 2},
                "comparison": {"comparable": False, "functional_validation": "valid"},
            },
        })
        self.assertEqual("skipped", summary["status"])
        self.assertEqual({"duration_seconds": 1, "concurrency": 2}, summary["parameters"])
        self.assertFalse(summary["comparison"]["comparable"])


class StressRpsJsonTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.stress = load_script("echo_stress_rps", "stress-test-rps.py")

    def test_original_positional_arguments_are_still_parsed(self):
        args = self.stress.make_parser().parse_args([
            "http://127.0.0.1:18080", "7", "11"
        ])
        self.assertEqual("http://127.0.0.1:18080", args.base_url)
        self.assertEqual(7, args.duration)
        self.assertEqual(11, args.concurrency)
        self.assertFalse(args.json_output)

    def test_cleanup_retries_a_transient_server_error(self):
        with mock.patch.object(
                self.stress, "api", side_effect=[(500, None), (204, None)]), \
                mock.patch.object(self.stress.time, "sleep") as sleep:
            result = self.stress.cleanup_api(
                "/api/admin/logs/all", "http://127.0.0.1:18080")

        self.assertTrue(result["passed"])
        self.assertEqual(204, result["status"])
        self.assertEqual(2, result["attempts"])
        sleep.assert_called_once_with(self.stress.DEFAULT_CLEANUP_RETRY_DELAY)

    def test_waits_until_the_log_agent_queue_is_empty(self):
        responses = [
            (200, [{"name": "log-agent", "queueSize": 3}]),
            (200, [{"name": "log-agent", "queueSize": 0}]),
        ]
        with mock.patch.object(self.stress, "api", side_effect=responses), \
                mock.patch.object(self.stress.time, "sleep") as sleep:
            result = self.stress.wait_for_log_agent_drain(
                "http://127.0.0.1:18080", timeout=5, interval=0.1)

        self.assertTrue(result["passed"])
        self.assertEqual(0, result["queue_size"])
        sleep.assert_called_once_with(0.1)

    def test_http_non_2xx_makes_json_result_fail_and_exit_nonzero(self):
        class Non2xxHandler(BaseHTTPRequestHandler):
            def do_POST(self):
                self.send_response(400)
                self.end_headers()

            def do_DELETE(self):
                self.send_response(204)
                self.end_headers()

            def log_message(self, *_args):
                pass

        server = ThreadingHTTPServer(("127.0.0.1", 0), Non2xxHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            completed = subprocess.run(
                [
                    sys.executable,
                    str(PROJECT_ROOT / "scripts" / "stress-test-rps.py"),
                    f"http://127.0.0.1:{server.server_port}", "0", "1", "--json",
                ],
                cwd=PROJECT_ROOT,
                text=True,
                capture_output=True,
                timeout=30,
                check=False,
            )
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)
        result = json.loads(completed.stdout)
        self.assertNotEqual(0, completed.returncode)
        self.assertFalse(result["passed"])
        self.assertGreater(result["non_2xx"], 0)

    def test_machine_readable_output_and_failure_exit_for_unreachable_server(self):
        script = PROJECT_ROOT / "scripts" / "stress-test-rps.py"
        completed = subprocess.run(
            [sys.executable, str(script), "http://127.0.0.1:1", "0", "1", "--json"],
            cwd=PROJECT_ROOT,
            text=True,
            capture_output=True,
            timeout=30,
            check=False,
        )
        self.assertNotEqual(0, completed.returncode)
        result = json.loads(completed.stdout)
        self.assertFalse(result["passed"])
        self.assertGreater(result["request_errors"], 0)
        self.assertEqual(0, result["parameters"]["duration_seconds"])
        self.assertEqual(1, result["parameters"]["concurrency"])


if __name__ == "__main__":
    unittest.main()
