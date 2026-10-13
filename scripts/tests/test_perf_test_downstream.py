from pathlib import Path
import runpy
import threading
import unittest
import urllib.request


PROJECT_ROOT = Path(__file__).resolve().parents[2]


class PerfTestDownstreamTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.script = runpy.run_path(
            str(PROJECT_ROOT / "scripts" / "perf-test-downstream.py"),
            run_name="echo_perf_test_downstream",
        )

    def test_listen_backlog_is_large_enough_for_reconnect_bursts(self):
        # The default backlog of 5 drops SYNs when 40 forwards reconnect at once,
        # which adds ~1 s to Echo's measured connect and read-timeout latency.
        self.assertGreaterEqual(self.script["QuietThreadingHTTPServer"].request_queue_size, 128)

    def test_fast_endpoint_answers(self):
        server = self.script["QuietThreadingHTTPServer"](("127.0.0.1", 0), self.script["BenchmarkHandler"])
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            port = server.server_address[1]
            with urllib.request.urlopen(f"http://127.0.0.1:{port}/fast", timeout=5) as response:
                self.assertEqual(200, response.status)
                self.assertEqual(b'{"ok":true}', response.read())
        finally:
            server.shutdown()
            server.server_close()


if __name__ == "__main__":
    unittest.main()
