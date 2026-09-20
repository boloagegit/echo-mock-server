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

    def test_default_plan_is_eight_hours_without_touching_docker(self):
        output = io.StringIO()
        with patch.object(sys, "argv", [str(SCRIPT), "--dry-run"]), contextlib.redirect_stdout(output):
            self.assertEqual(HARNESS.main(), 0)
        plan = json.loads(output.getvalue())
        self.assertEqual(plan["duration_seconds"], 28800)
        self.assertIn("disabled", plan["backup"])

    def test_rejects_excessive_or_invalid_load_before_docker(self):
        for args in (["--duration-seconds", "9"],
                     ["--duration-seconds", "172801"],
                     ["--requests-per-minute", "0"],
                     ["--requests-per-minute", "600"],
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


if __name__ == "__main__":
    unittest.main()
