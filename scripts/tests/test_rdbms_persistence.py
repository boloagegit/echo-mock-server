from datetime import datetime
from pathlib import Path
import runpy
import unittest


PROJECT_ROOT = Path(__file__).resolve().parents[2]


class RdbmsPersistenceTimestampTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.script = runpy.run_path(
            str(PROJECT_ROOT / "scripts" / "test-rdbms-persistence.py"),
            run_name="echo_rdbms_persistence",
        )

    def test_accepts_iso_timestamp(self):
        parsed = self.script["iso_timestamp"]("2026-09-04T18:01:02.123456")
        self.assertEqual(datetime(2026, 9, 4, 18, 1, 2, 123456), parsed)

    def test_truncates_java_nanoseconds_for_older_python(self):
        parsed = self.script["iso_timestamp"]("2026-09-04T18:01:02.123456789")
        self.assertEqual(datetime(2026, 9, 4, 18, 1, 2, 123456), parsed)

    def test_accepts_jackson_local_datetime_array(self):
        parsed = self.script["iso_timestamp"]([2026, 9, 4, 18, 1, 2, 123456789])
        self.assertEqual(datetime(2026, 9, 4, 18, 1, 2, 123456), parsed)

    def test_rejects_invalid_timestamp_array(self):
        self.assertIsNone(self.script["iso_timestamp"]([2026, 13, 40]))
        self.assertIsNone(self.script["iso_timestamp"]([2026, "09", 4]))


if __name__ == "__main__":
    unittest.main()
