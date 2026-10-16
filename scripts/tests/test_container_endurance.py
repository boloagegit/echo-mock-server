"""Fast, Docker-free checks for the timed endurance harness."""

import contextlib
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[1] / "test-container-endurance.py"
SPEC = importlib.util.spec_from_file_location("container_endurance", SCRIPT)
HARNESS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(HARNESS)


class ContainerEnduranceTest(unittest.TestCase):
    def test_xml_body_has_exact_size_and_traceable_id(self):
        body = HARNESS.xml_body("test-001", 131072)
        self.assertEqual(len(body.encode()), 131072)
        self.assertEqual(HARNESS.ID.findall(body), ["test-001"])

    def test_json_body_has_exact_size_and_traceable_id(self):
        body = HARNESS.json_body("test-002", 131072)
        self.assertEqual(len(body.encode()), 131072)
        self.assertEqual(json.loads(body)["messageId"], "test-002")

    def test_large_xml_responses_have_exact_size_and_expected_markers(self):
        plain = HARNESS.large_xml_response("run-1", 1024 * 1024, templated=False)
        templated = HARNESS.large_xml_response("run-1", 1024 * 1024, templated=True)
        self.assertEqual(len(plain.encode()), 1024 * 1024)
        self.assertEqual(len(templated.encode()), 1024 * 1024)
        self.assertIn("static-large-xml", plain)
        self.assertIn("{{xPath request.body", templated)

    def test_default_plan_is_eight_hours_without_touching_docker(self):
        output = io.StringIO()
        with patch.object(sys, "argv", [str(SCRIPT), "--dry-run"]), contextlib.redirect_stdout(output):
            self.assertEqual(HARNESS.main(), 0)
        plan = json.loads(output.getvalue())
        self.assertEqual(plan["duration_seconds"], 28800)
        self.assertIn("disabled", plan["backup"])

    def test_24_hour_profile_uses_4_tps_with_3g_heap(self):
        output = io.StringIO()
        with patch.object(sys, "argv", [str(SCRIPT), "--uat-24h", "--dry-run"]), \
                contextlib.redirect_stdout(output):
            self.assertEqual(HARNESS.main(), 0)
        plan = json.loads(output.getvalue())
        self.assertEqual(plan["duration_seconds"], 86400)
        self.assertEqual(plan["requests_per_minute"], 240)
        self.assertEqual(plan["expected_requests"], 345600)
        self.assertEqual(plan["max_requests"], 400000)
        self.assertEqual(plan["heap"], "3g")
        self.assertEqual(plan["container_memory"], "6g")

    def test_high_rate_profile_uses_two_hours_at_40_tps(self):
        output = io.StringIO()
        with patch.object(sys, "argv", [str(SCRIPT), "--uat-2h-40tps", "--dry-run"]), \
                contextlib.redirect_stdout(output):
            self.assertEqual(HARNESS.main(), 0)
        plan = json.loads(output.getvalue())
        self.assertEqual(plan["duration_seconds"], 7200)
        self.assertEqual(plan["requests_per_minute"], 2400)
        self.assertEqual(plan["expected_requests"], 288000)
        self.assertEqual(plan["max_requests"], 300000)
        self.assertEqual(plan["heap"], "3g")
        self.assertEqual(plan["container_memory"], "6g")

    def test_sqlite_large_xml_plan_uses_realistic_rate_and_five_mib_responses(self):
        output = io.StringIO()
        with patch.object(sys, "argv", [str(SCRIPT), "--sqlite-large-xml", "--dry-run"]), \
                contextlib.redirect_stdout(output):
            self.assertEqual(HARNESS.main(), 0)
        plan = json.loads(output.getvalue())
        self.assertEqual(plan["duration_seconds"], 10800)
        self.assertEqual(plan["requests_per_minute"], 240)
        self.assertEqual(plan["expected_requests"], 43200)
        self.assertEqual(plan["large_xml_response_mib"], 5)
        self.assertEqual(plan["heap"], "3g")
        self.assertEqual(plan["container_memory"], "6g")

    def test_rejects_selecting_multiple_profiles(self):
        with patch.object(sys, "argv", [str(SCRIPT), "--uat-24h", "--sqlite-large-xml"]):
            with self.assertRaises(ValueError):
                HARNESS.main()

    def test_rejects_excessive_or_invalid_load_before_docker(self):
        for args in (["--duration-seconds", "9"],
                     ["--duration-seconds", "172801"],
                     ["--requests-per-minute", "0"],
                     ["--requests-per-minute", "600"],
                     ["--max-requests", "400001"],
                     ["--max-in-flight", "513"],
                     ["--heap", "0m"],
                     ["--heap", "1g", "--container-memory", "1g"]):
            with self.subTest(args=args), patch.object(sys, "argv", [str(SCRIPT), *args]):
                with self.assertRaises(ValueError):
                    HARNESS.main()

    def test_memory_trend_is_explicitly_not_a_heap_leak_verdict(self):
        with tempfile.TemporaryDirectory() as directory:
            samples = Path(directory) / "samples.jsonl"
            samples.write_text("\n".join(json.dumps({"docker_stats": {"MemPerc": f"{value}%"}})
                                         for value in (40, 41, 42, 43, 44, 45, 46, 47, 48, 49)))
            trend = HARNESS.memory_trend(samples)
        self.assertEqual(trend["first_decile_median_percent"], 40)
        self.assertEqual(trend["last_decile_median_percent"], 49)
        self.assertIn("not just Java heap", trend["assessment"])

    def test_hourly_report_writes_json_and_markdown(self):
        summary = HARNESS.hourly_summary(
            1,
            [{"accepted": True, "latency_ms": 10},
             {"accepted": True, "latency_ms": 20}],
            [{"docker_stats": {"MemUsage": "1GiB / 6GiB", "MemPerc": "16.7%"},
              "application": {"jvmHeapUsed": 100, "jvmHeapMax": 300},
              "db_bytes": 123, "wal_bytes": 45}],
            expected_slots=2, schedule_misses=0)
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            HARNESS.write_hourly_report(output, summary)
            report = json.loads((output / "hour-01.json").read_text())
            markdown = (output / "hour-01.md").read_text()
        self.assertEqual(report["accepted"], 2)
        self.assertEqual(report["p95_ms"], 20)
        self.assertEqual(report["response_bytes"], 0)
        self.assertIn("Schedule misses: 0", markdown)

    def test_schedule_attainment_allows_small_jitter_but_rejects_under_99_percent(self):
        results = HARNESS.schedule_window_results(
            {1: 12_000, 2: 12_000}, {1: 40, 2: 121}, 99.0)
        self.assertTrue(results[0]["passed"])
        self.assertEqual(results[0]["attainment_percent"], 99.6667)
        self.assertFalse(results[1]["passed"])
        self.assertEqual(results[1]["attainment_percent"], 98.9917)

    def test_load_cycle_validates_static_json_xml_and_jms(self):
        paths = {"static": "/static", "json-template": "/json",
                 "xml-template": "/xml"}

        def fake_request(_port, _username, _password, _method, path,
                         payload=None, timeout=20, content_type=None):
            del timeout, content_type
            message_id = (json.loads(payload)["messageId"] if path == "/json"
                          else HARNESS.ID.findall(payload)[0])
            if path == "/static":
                return 200, "run-1 static"
            if path == "/json" or path == "/xml":
                return 200, message_id
            return 200, {"sent": True}

        with patch.object(HARNESS, "request", side_effect=fake_request):
            records = [HARNESS.execute_load_request(
                index, "run-1", 0.0, 8080, "user", "password", paths, 50)
                for index in range(1, 5)]
        self.assertEqual([record["mode"] for record in records],
                         ["static", "json-template", "xml-template", "jms"])
        self.assertTrue(all(record["accepted"] for record in records))
        self.assertTrue(all(record["response_bytes"] > 0 for record in records))


if __name__ == "__main__":
    unittest.main()
