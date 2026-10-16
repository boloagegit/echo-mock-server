#!/usr/bin/env python3
"""Isolated mixed-load correctness/resource check; requires Python aiohttp, java and jcmd.

Usage: taskset -c 0-3 python3 soak.py ECHO_JAR NEW_OUTPUT_DIR [cycles=10] [seconds=60]
Uses 18082/18083, a temporary SQLite database, real asynchronous database request logging,
and deterministic downstream failures/recovery. This is not a throughput benchmark.
"""
import asyncio
import base64
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time

import aiohttp
from aiohttp import web

BASE = "http://127.0.0.1:18082"
ADMIN_HEADERS = {"Authorization": "Basic " + base64.b64encode(b"admin:admin").decode("ascii"),
                 "Accept": "application/json"}


async def main():
    if sys.platform != "linux":
        raise SystemExit("This soak harness reads Linux /proc metrics. Run it on the Linux deployment test host; Echo development itself supports Windows.")
    jar = Path(sys.argv[1]).resolve()
    out = Path(sys.argv[2]).resolve()
    cycles = int(sys.argv[3]) if len(sys.argv) > 3 else 10
    seconds = int(sys.argv[4]) if len(sys.argv) > 4 else 60
    out.mkdir(parents=True, exist_ok=False)
    for port in (18082, 18083):
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", port))
    fault = False

    async def downstream(request):
        await request.read()
        if request.path == "/forward-slow":
            await asyncio.sleep(.05)
        if fault:
            request.transport.close()
            return web.Response(status=503)
        return web.Response(status=503 if request.path == "/forward-error" else 200,
                            text="downstream:" + request.path)

    app = web.Application()
    app.router.add_route("*", "/{path:.*}", downstream)
    runner = web.AppRunner(app)
    await runner.setup()
    await web.TCPSite(runner, "127.0.0.1", 18083).start()
    clean_env = {k: v for k, v in os.environ.items() if not k.startswith(("SPRING_", "ECHO_"))}
    counts = Counter()
    errors = []
    large = "漢" * 4096
    rows = []
    with tempfile.TemporaryDirectory(prefix="echo-http-soak-") as work:
        with (out / "server.log").open("w") as log:
            process = subprocess.Popen([
                "java", "-Xms1g", "-Xmx1g", "-XX:+UseG1GC", "-jar", str(jar),
                "--server.port=18082", "--server.undertow.threads.io=2", "--server.undertow.threads.worker=8",
                "--echo.http.transport=native", "--echo.http.native.max-body-bytes=1048576",
                "--echo.http.request-timeout-ms=10000", "--echo.jms.enabled=false",
                "--echo.backup.enabled=false", "--echo.backup.on-shutdown=false", "--echo.cleanup.enabled=false",
                "--echo.request-log.enabled=true", "--echo.request-log.store=database",
                "--management.endpoints.web.exposure.include=health,metrics",
                "--spring.profiles.active=default",
            ], cwd=work, env=clean_env, stdout=log, stderr=subprocess.STDOUT)
            try:
                async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=8),
                                                 connector=aiohttp.TCPConnector(limit=100)) as client:
                    async def api(path, method="GET", body=None):
                        async with client.request(method, BASE + path, headers=ADMIN_HEADERS, json=body) as response:
                            value = await response.text()
                            assert 200 <= response.status < 300, (path, response.status, value[:300])
                            return json.loads(value) if value else None

                    for _ in range(180):
                        if process.poll() is not None:
                            raise AssertionError("server exited before ready")
                        try:
                            await api("/api/admin/status")
                            break
                        except (aiohttp.ClientError, AssertionError):
                            await asyncio.sleep(.5)
                    else:
                        raise AssertionError("readiness timeout")

                    async def rule(path, body, **kwargs):
                        return await api("/api/admin/rules", "POST", dict(
                            protocol="HTTP", targetHost="default", matchKey=path, method="GET",
                            status=200, responseBody=body, **kwargs))

                    await rule("/fixed", '{"ok":true}')
                    await rule("/tpl", '{"id":"{{request.query.id}}"}')
                    await rule("/large", large)
                    await rule("/delay", "delayed", delayMs=50)
                    await rule("/cancel", "late", delayMs=1000)
                    mutable = await rule("/mutable", "revision-0")
                    await api("/api/admin/rules", "POST", dict(protocol="HTTP", matchKey="/xml",
                              method="POST", status=200, bodyCondition="//Order/Type=VIP", responseBody="<ok/>"))
                    target = await api("/api/admin/http-target-connections", "POST", dict(
                        name="soak", baseUrl="http://127.0.0.1:18083", authType="NONE",
                        connectTimeoutSeconds=1, readTimeoutSeconds=1, enabled=True, tlsVerificationEnabled=True))
                    for name in ("forward-fast", "forward-slow", "forward-error"):
                        await api("/api/admin/rules", "POST", dict(protocol="HTTP", matchKey="/" + name,
                                  method="GET", action="FORWARD", forwardTargetMode="CONNECTION",
                                  httpTargetConnectionId=target["id"]))

                    async def verify(path, expected, status=200, method="GET", data=None, allow_failure=False):
                        async with client.request(method, BASE + "/mock" + path, data=data,
                                                  headers={"Content-Type": "application/xml"} if data else {}) as response:
                            body = await response.text()
                            if allow_failure:
                                assert response.status in (502, 503, 504), (path, response.status, body[:100])
                            else:
                                assert (response.status, body) == (status, expected), (path, response.status, body[:100])
                            counts[path.split("?")[0] + ":" + str(response.status)] += 1

                    async def check_one(index, injecting):
                        try:
                            which = index % 12
                            if which < 3:
                                await verify("/fixed", '{"ok":true}')
                            elif which == 3:
                                await verify("/tpl?id=7", '{"id":"7"}')
                            elif which == 4:
                                await verify("/xml", "<ok/>", method="POST", data="<Order><Type>VIP</Type></Order>")
                            elif which == 5:
                                await verify("/large", large)
                            elif which == 6:
                                await verify("/delay", "delayed")
                            elif which in (7, 8, 9):
                                path = ("/forward-fast", "/forward-slow", "/forward-error")[which - 7]
                                await verify(path, "downstream:" + path, 503 if which == 9 else 200,
                                             allow_failure=injecting)
                            else:
                                await verify("/fixed", '{"ok":true}')
                        except Exception as error:
                            if len(errors) < 30:
                                errors.append(repr(error))
                            counts["unexpected-errors"] += 1

                    async def load(duration, injecting=False):
                        # Fixed offered 400 requests/s, bounded outstanding work; no unbounded task buildup.
                        pending = set()
                        deadline = time.monotonic() + duration
                        index = 0
                        while time.monotonic() < deadline:
                            started = time.monotonic()
                            for _ in range(8):
                                task = asyncio.create_task(check_one(index, injecting))
                                pending.add(task)
                                task.add_done_callback(pending.discard)
                                index += 1
                            if len(pending) >= 96:
                                await asyncio.wait(pending, return_when=asyncio.FIRST_COMPLETED)
                            await asyncio.sleep(max(0, .02 - (time.monotonic() - started)))
                        if pending:
                            await asyncio.gather(*pending)

                    async def snapshot(label):
                        gauges = {}
                        for metric in ("echo.http.native.active", "echo.http.native.buffered.bytes", "echo.http.native.queued"):
                            value = await api("/actuator/metrics/" + metric)
                            gauges[metric] = value["measurements"][0]["value"]
                        assert all(v == 0 for v in gauges.values()), gauges
                        forwarding = await api("/api/admin/http-target-connections/metrics")
                        pool = forwarding["pool"]
                        assert forwarding["activeForwards"] == 0, forwarding
                        assert all(pool[k] == 0 for k in ("leased", "pending", "bufferedResponseBytes")), pool
                        await asyncio.to_thread(subprocess.run, ["jcmd", str(process.pid), "GC.run"],
                                                stdout=subprocess.DEVNULL, check=True)
                        heap = await asyncio.to_thread(subprocess.check_output,
                                                       ["jcmd", str(process.pid), "GC.heap_info"], text=True)
                        proc = Path(f"/proc/{process.pid}")
                        row = dict(label=label, time=time.time(), counts=dict(counts), gauges=gauges,
                                   forwarding=forwarding, agents=await api("/api/admin/agents"),
                                   heap=heap, fdCount=len(list((proc / "fd").iterdir())),
                                   process=[line for line in (proc / "status").read_text().splitlines()
                                            if line.startswith(("VmRSS:", "VmHWM:", "Threads:"))])
                        rows.append(row)
                        (out / "snapshots.json").write_text(json.dumps(rows, indent=2))
                        print(json.dumps(dict(cycle=label, requests=sum(counts.values()), errors=len(errors),
                                              resources=gauges)), flush=True)

                    await load(5)
                    await asyncio.sleep(2)
                    await snapshot("warm")
                    for cycle in range(cycles):
                        # Rule updates must be visible immediately, even while requests are in flight.
                        task = asyncio.create_task(load(seconds))
                        expected_revision = "revision-" + str(cycle + 1)
                        mutable["responseBody"] = expected_revision
                        mutable = await api("/api/admin/rules/" + str(mutable["id"]), "PUT", mutable)
                        await verify("/mutable", expected_revision)
                        # Abort real delayed exchanges; their capacity and timer reservations must recover.
                        for _ in range(8):
                            reader, writer = await asyncio.open_connection("127.0.0.1", 18082)
                            writer.write(b"GET /mock/cancel HTTP/1.1\r\nHost: localhost\r\n\r\n")
                            await writer.drain()
                            await asyncio.sleep(.01)
                            writer.transport.abort()
                        # Reject a body over this run's explicitly configured 1 MiB adapter limit.
                        async with client.post(BASE + "/mock/xml", data=b"x" * (1048576 + 1)) as response:
                            assert response.status == 413, response.status
                            await response.read()
                            counts["oversized:413"] += 1
                        await task
                        if cycle == cycles // 2:
                            fault = True
                            await load(5, injecting=True)
                            fault = False
                            await verify("/forward-fast", "downstream:/forward-fast")
                            await verify("/forward-slow", "downstream:/forward-slow")
                        await asyncio.sleep(2)
                        await snapshot(cycle + 1)
                    assert not errors, errors
                    (out / "summary.json").write_text(json.dumps(dict(
                        jarSha256=hashlib.sha256(jar.read_bytes()).hexdigest(), cycles=cycles,
                        secondsPerCycle=seconds, offeredRate=400, counts=dict(counts), errors=errors,
                        injectedDisconnects=cycles * 8,
                        notes="GC measurements happen between load cycles; not a latency benchmark."), indent=2))
            finally:
                process.terminate()
                try:
                    await asyncio.to_thread(process.wait, timeout=35)
                except subprocess.TimeoutExpired:
                    process.kill()
                    await asyncio.to_thread(process.wait)
                await runner.cleanup()


if __name__ == "__main__":
    asyncio.run(main())
