#!/usr/bin/env python3
"""Quiet local downstream used by Echo forwarding benchmarks.

Endpoints:
  /fast          immediate 200 JSON response
  /slow/<ms>     delayed 200 JSON response
  /status/<code> response with the requested status
  /bytes/<size>  response body with the requested number of bytes
"""

from __future__ import annotations

import argparse
import json
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class QuietThreadingHTTPServer(ThreadingHTTPServer):
    def handle_error(self, request: object, client_address: object) -> None:
        # Time-out benchmarks intentionally close sockets before delayed responses finish.
        return


class BenchmarkHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        self._respond()

    def do_POST(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        self._discard_request_body()
        self._respond()

    def do_PUT(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        self._discard_request_body()
        self._respond()

    def do_PATCH(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        self._discard_request_body()
        self._respond()

    def do_DELETE(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        self._respond()

    def log_message(self, format: str, *args: object) -> None:
        return

    def _discard_request_body(self) -> None:
        length = int(self.headers.get("Content-Length", "0"))
        if length > 0:
            self.rfile.read(length)

    def _respond(self) -> None:
        route = self.path.split("?", 1)[0]
        status = 200
        body: bytes

        if route.startswith("/slow/"):
            delay_ms = _bounded_int(route.removeprefix("/slow/"), 0, 30_000, 100)
            time.sleep(delay_ms / 1000)
            body = json.dumps({"ok": True, "delayMs": delay_ms}).encode()
        elif route.startswith("/status/"):
            status = _bounded_int(route.removeprefix("/status/"), 100, 599, 500)
            body = json.dumps({"status": status}).encode()
        elif route.startswith("/bytes/"):
            size = _bounded_int(route.removeprefix("/bytes/"), 0, 10 * 1024 * 1024, 1024)
            body = b"x" * size
        else:
            body = b'{"ok":true}'

        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def _bounded_int(value: str, minimum: int, maximum: int, fallback: int) -> int:
    try:
        return max(minimum, min(maximum, int(value)))
    except ValueError:
        return fallback


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=18081)
    args = parser.parse_args()
    server = QuietThreadingHTTPServer((args.host, args.port), BenchmarkHandler)
    print(f"benchmark downstream listening on http://{args.host}:{args.port}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
