"""Fast, Docker-free checks of diagnostics evidence and cleanup boundaries."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / "test-container-diagnostics.py"
SPEC = importlib.util.spec_from_file_location("container_diagnostics", SCRIPT)
HARNESS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(HARNESS)


class ContainerDiagnosticsTest(unittest.TestCase):
    def test_cgroup_sample_retains_oom_and_throttling(self):
        state = HARNESS.parse_cgroup("123\n456\nlow 0\nhigh 0\nmax 0\noom 0\noom_kill 0\nusage_usec 300\nnr_throttled 5\nthrottled_usec 10\n")
        self.assertEqual(state["memory_current"], 123)
        self.assertEqual(state["memory_peak"], 456)
        self.assertEqual(state["oom_kill"], 0)
        self.assertEqual(state["nr_throttled"], 5)

    def test_cgroup_unknown_or_missing_not_zero(self):
        for sample in ("", "unavailable", "max\n456\noom 0"):
            with self.assertRaises(ValueError):
                HARNESS.parse_cgroup(sample)

    @staticmethod
    def summary(case="NO_REPLY"):
        return ("ECHO_TX trace=t-1 protocol=JMS event=END failure=" + case + " waitResult=" + case
                + " stage=RECEIVE_REPLY sendReturned=true callerReply=SEND_RETURNED inId=ID:in"
                + " outId=ID:out outReplyTo=temp-1 timeoutMs=30000 waitMs=30001"
                + " replyType=ActiveMQBytesMessage replyId=ID:reply endpoint=ECHO_DIAG_" + case)

    def verify(self, line, case="NO_REPLY", ids=None):
        return HARNESS.validate_summary(line, case, {"messageId": "ID:in", "elapsedMs": 30005},
                                        {"messageId": "ID:out", "replyTo": "temp-1"}, ids or {"t-1"})

    def test_summary_correlates_real_caller_downstream_and_request_log(self):
        self.assertEqual(self.verify(self.summary())["trace"], "t-1")
        self.assertEqual(self.verify(self.summary("INVALID_TYPE"), "INVALID_TYPE")["replyType"], "ActiveMQBytesMessage")

    def test_missing_correlation_bad_outcome_cleanup_and_early_wait_fail(self):
        line = self.summary()
        for broken in (line.replace("ID:out", "ID:wrong"), line.replace("SEND_RETURNED", "SEND_FAILED"),
                       line.replace("waitMs=30001", "waitMs=10"), line + " cleanupFailure=true",
                       line + " <Request>", line.replace("trace=t-1", "trace=other")):
            with self.assertRaises(AssertionError):
                self.verify(broken)

    def test_dry_run_has_finite_two_tier_default_enabled_plan(self):
        output = io.StringIO()
        with patch.object(sys, "argv", [str(SCRIPT), "--dry-run"]), contextlib.redirect_stdout(output):
            self.assertEqual(HARNESS.main(), 0)
        plan = json.loads(output.getvalue())
        self.assertEqual(len(plan["tiers"]), 2)
        self.assertEqual(plan["traffic_per_tier"], 472)
        self.assertEqual(plan["no_reply_seconds"], 30)
        self.assertIn("default enabled", plan["diagnostics"])

    def test_cleanup_refuses_nonowned_resource_before_stop_or_remove(self):
        with tempfile.TemporaryDirectory() as folder:
            harness = HARNESS.Harness(Path(folder), "fixture")
            harness.containers.append("unrelated")
            wrong = json.dumps([{"Config": {"Labels": {HARNESS.LABEL: "another-run"}}}])
            with patch.object(harness, "docker", return_value=wrong) as docker:
                with self.assertRaises(AssertionError):
                    harness.remove_container("unrelated")
                self.assertEqual(docker.call_count, 1)
                self.assertEqual(docker.call_args.args, ("container", "inspect", "unrelated"))

    def test_http_inspection_explicitly_negotiates_json(self):
        with tempfile.TemporaryDirectory() as folder:
            harness = HARNESS.Harness(Path(folder), "fixture")
            harness.api_container = "owned-client"
            with patch.object(harness, "docker", return_value="{}") as docker:
                self.assertEqual(harness.get("http://owned-echo:8080", "/api/admin/status", True), {})
                self.assertIn("Accept: application/json", docker.call_args.args)
                self.assertIn("http://owned-echo:8080/api/admin/status", docker.call_args.args)

    def test_invalid_tier_and_off_business_mode_reject_before_docker(self):
        for args in (["--tiers", "unrelated"], ["--tiers", "1cpu-1g,1cpu-1g"], ["--diagnostics-off"]):
            with patch.object(sys, "argv", [str(SCRIPT), *args]), patch.object(HARNESS.Harness, "command") as command:
                with self.assertRaises(AssertionError):
                    HARNESS.main()
                command.assert_not_called()

    def test_512m_control_is_explicit_not_hidden_in_successful_matrix(self):
        output = io.StringIO()
        with patch.object(sys, "argv", [str(SCRIPT), "--tiers", "half-cpu-512m", "--startup-only", "--diagnostics-off", "--dry-run"]), contextlib.redirect_stdout(output):
            self.assertEqual(HARNESS.main(), 0)
        plan = json.loads(output.getvalue())
        self.assertEqual(plan["tiers"][0][2], 512 * 1024 * 1024)
        self.assertEqual(plan["traffic_per_tier"], 0)
        self.assertEqual(plan["diagnostics"], "disabled startup control")


if __name__ == "__main__":
    unittest.main()
