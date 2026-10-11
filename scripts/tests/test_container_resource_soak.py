"""Docker-free checks of bounded soak timing, observations, and evidence parsing."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / "test-container-resource-soak.py"
SPEC = importlib.util.spec_from_file_location("resource_soak", SCRIPT)
SOAK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SOAK)


class ResourceSoakTest(unittest.TestCase):
    def test_process_rss_is_bytes_not_virtual_reservation(self):
        row = SOAK.parse_process("VmSize:\t999999 kB\nVmRSS:\t120 kB\nRssAnon:\t110 kB\nThreads:\t50\n")
        self.assertEqual(row, {"VmRSS": 120 * 1024, "RssAnon": 110 * 1024, "Threads": 50})

    def test_missing_process_observation_is_not_zero(self):
        for text in ("", "VmRSS: 42 kB", "Threads: 9"):
            with self.assertRaises(AssertionError):
                SOAK.parse_process(text)

    def test_plateau_and_linear_growth_are_described_not_declared_leaks(self):
        plateau = SOAK.growth_summary([(0, 100), (60, 100), (120, 100)])
        growth = SOAK.growth_summary([(0, 100), (60, 120), (120, 140)])
        self.assertEqual(plateau["slopePerMinute"], 0)
        self.assertEqual(growth["slopePerMinute"], 20)
        self.assertNotIn("leak", growth)
        with self.assertRaises(AssertionError):
            SOAK.growth_summary([(0, 1)])

    def test_traffic_requires_both_real_reply_counts_and_full_duration(self):
        result = {"durationSeconds": 600, "rate": 10, "elapsedMs": 600001,
                  "jms": {"completed": 3000}, "http": {"completed": 3000}}
        SOAK.validate_workload(result, 600, 10)
        result["http"]["completed"] = 2999
        with self.assertRaises(AssertionError):
            SOAK.validate_workload(result, 600, 10)
        result["http"]["completed"] = 3000
        result["elapsedMs"] = 599999
        with self.assertRaises(AssertionError):
            SOAK.validate_workload(result, 600, 10)

    def test_gc_parser_keeps_natural_and_explicit_causes_distinct(self):
        rows = SOAK.gc_floors("[2026-10-02T00:00:00.000+0000][40.001s][info][gc] GC(3) Pause Young (Normal) (G1 Evacuation Pause) 300M->40M(512M) 2.250ms\n"
                             "[2026-10-02T00:00:01.000+0000][41.001s][info][gc] GC(4) Pause Full (Diagnostic Command) 90M->38M(160M) 50.200ms\n")
        self.assertEqual(len(rows), 2)
        self.assertEqual(rows[0]["afterMiB"], 40)
        self.assertIn("Diagnostic Command", rows[1]["cause"])

    def test_retention_uses_existing_low_watermark_not_an_exact_ceiling(self):
        for count in (1800, 1879, 2000):
            SOAK.validate_retention({"maxRecords": 2000, "totalRequests": count}, 6100)
        SOAK.validate_retention({"maxRecords": 2000, "totalRequests": 100}, 100)
        for count in (1799, 2001):
            with self.assertRaises(AssertionError):
                SOAK.validate_retention({"maxRecords": 2000, "totalRequests": count}, 6100)

    def test_default_is_exact_ten_minutes_with_idle_and_finite_workload(self):
        stream = io.StringIO()
        with patch.object(sys, "argv", [str(SCRIPT), "--dry-run"]), contextlib.redirect_stdout(stream):
            self.assertEqual(SOAK.main(), 0)
        plan = json.loads(stream.getvalue())
        self.assertEqual((plan["seconds"], plan["requests"], plan["idleSeconds"]), (600, 6000, 120))
        self.assertEqual(plan["origins"], 4)

    def test_out_of_bounds_or_odd_rate_rejected_before_docker(self):
        for args in (["--seconds", "601"], ["--rate", "11"], ["--payload-bytes", "9999999"], ["--idle-seconds", "1"]):
            with patch.object(sys, "argv", [str(SCRIPT), *args]), patch.object(SOAK.HELPERS.Harness, "command") as command:
                with self.assertRaises(AssertionError):
                    SOAK.main()
                command.assert_not_called()


if __name__ == "__main__":
    unittest.main()
