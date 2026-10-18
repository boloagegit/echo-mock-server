import importlib.util
from pathlib import Path
import unittest
from types import SimpleNamespace

SPEC = importlib.util.spec_from_file_location("memory_observability", Path(__file__).resolve().parents[1] / "test-memory-observability.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class MemoryObservationTest(unittest.TestCase):
    def test_scheduled_busy_rejection_is_reported_even_without_a_backup_failed_message(self):
        line = "2026-10-18 05:39:45.000 ERROR [echo-scheduled-3] TaskUtils : Unexpected error occurred in scheduled task"
        log = line + "\njava.lang.IllegalStateException: housekeeping is already running\n"
        self.assertEqual([line], MODULE.background_task_failures(log))
        self.assertNotIn("SQLite backup failed", log)

    def test_scheduled_error_scan_preserves_each_failure_and_ignores_normal_progress(self):
        errors = ["time ERROR [scheduler] Unexpected error occurred in scheduled task"] * 2
        log = "\n".join(["time INFO SQLite backup completed: one.sqlite", *errors,
                         "time DEBUG Unexpected error occurred in scheduled task"])
        self.assertEqual(errors, MODULE.background_task_failures(log))

    def test_histogram_growth_reports_sizes_counts_and_new_classes(self):
        baseline = MODULE.parse_histogram("1: 10 100 [B (java.base@17)\n 2: 2 40 com.echo.Example\n 3: 1 20 com.echo.Example")
        current = MODULE.parse_histogram("1: 25 400 [B (java.base@17)\n 2: 2 40 com.echo.Example\n 3: 1 50 com.echo.NewType")
        self.assertEqual({"instances": 3, "bytes": 60}, baseline["com.echo.Example"])
        result = MODULE.histogram_growth(baseline, current)
        self.assertEqual(["[B", "com.echo.NewType"], [row["class"] for row in result])
        self.assertEqual(300, result[0]["byteGrowth"])
        self.assertEqual(15, result[0]["instanceGrowth"])
        with self.assertRaises(ValueError):
            MODULE.parse_histogram("Attach failed")

    def test_deliberate_retention_growth_is_detected(self):
        points = [(40 + i * 8) * MODULE.MIB for i in range(10)]
        result = MODULE.assess_growth(points, 24 * MODULE.MIB)
        self.assertEqual("growth-detected", result["status"])
        self.assertEqual(8 * MODULE.MIB, result["slopePerCycle"])

    def test_bounded_warm_cache_noise_is_not_a_leak(self):
        result = MODULE.assess_growth([n * MODULE.MIB for n in [50, 53, 49, 54, 51, 52]], 8 * MODULE.MIB)
        self.assertEqual("no-growth-detected-in-window", result["status"])

    def test_insufficient_or_unknown_observations_cannot_pass(self):
        self.assertEqual("insufficient-data", MODULE.assess_growth([], 10)["status"])
        self.assertEqual("insufficient-data", MODULE.assess_growth([None] * 4, 10)["status"])

    def test_continuous_heap_uses_natural_gc_minute_medians_and_detects_growth(self):
        events = [{"loadElapsedSeconds": minute * 60 + offset, "heapAfterBytes": (50 + minute * 10) * MODULE.MIB,
                   "heapCoverageComplete": True} for minute in range(6) for offset in (1, 20, 40)]
        args = SimpleNamespace(allowed_growth_mib=24, allowed_native_growth_mib=128)
        result = MODULE.assess_continuous_resources([], events, [], args)
        self.assertEqual("growth-detected", result["naturalGcHeap"]["status"])
        self.assertEqual(6, result["naturalGcHeap"]["samples"])
        self.assertEqual("insufficient-data", result["nativeCommitted"]["status"])

    def test_continuous_missing_pool_coverage_cannot_pass(self):
        events = [{"loadElapsedSeconds": minute * 60, "heapAfterBytes": 50 * MODULE.MIB,
                   "heapCoverageComplete": minute != 2} for minute in range(6)]
        args = SimpleNamespace(allowed_growth_mib=24, allowed_native_growth_mib=128)
        result = MODULE.assess_continuous_resources([], events, [], args)
        self.assertEqual("insufficient-data", result["naturalGcHeap"]["status"])

    def test_missing_and_nonzero_lease_counts_cannot_pass_drain(self):
        with self.assertRaises((KeyError, AssertionError)):
            MODULE.assert_drained({"sections": {}})
        with self.assertRaises(AssertionError):
            MODULE.assert_drained({"sections": {"jms": {"state": "AVAILABLE", "values": {"listenerActive": 1}}}})


if __name__ == "__main__":
    unittest.main()
