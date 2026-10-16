#!/usr/bin/env python3
"""Isolated startup probe. Existing one-rule SQLite seed; first real mock and UI assets checked."""
import argparse, hashlib, http.cookiejar, json, os, pathlib, shutil, subprocess, tempfile, time, urllib.request, urllib.error
p = argparse.ArgumentParser()
p.add_argument('jar')
p.add_argument('output')
p.add_argument('--java', required=True)
p.add_argument('--seed', required=True)
p.add_argument('--profile', action='store_true')
a = p.parse_args()
jar = pathlib.Path(a.jar).resolve()
out = pathlib.Path(a.output).resolve()
out.mkdir(parents=True, exist_ok=False)
seed = pathlib.Path(a.seed).resolve()
work = pathlib.Path(tempfile.mkdtemp(prefix='echo-startup-'))
(work / 'data').mkdir()
if seed.exists():
    shutil.copy2(seed, work / 'data/mockdb.sqlite')
base = 'http://127.0.0.1:18082'
env = {k: v for k, v in os.environ.items() if not k.startswith(('ECHO_', 'SPRING_')) and k not in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS')}
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

def req(path, data=None, headers=None):
    r = urllib.request.Request(base + path, data=data, headers=headers or {})
    with opener.open(r, timeout=3) as f:
        return (f.status, f.read(), dict(f.headers))
args = [a.java, '-Xms512m', '-Xmx512m', '-XX:+UseG1GC']
if a.profile:
    args += ['-XX:StartFlightRecording=settings=profile,filename=' + str(out / 'startup.jfr') + ',dumponexit=true']
args += ['-jar', str(jar), '--server.port=18082', '--spring.profiles.active=default', '--server.undertow.threads.io=2', '--echo.backup.enabled=false', '--echo.backup.on-shutdown=false', '--echo.cleanup.enabled=false']
if a.profile:
    args += ['--logging.level.org.springframework=INFO', '--logging.level.org.hibernate=INFO', '--logging.level.com.zaxxer.hikari=INFO']
(out / 'command.json').write_text(json.dumps(args, indent=2) + '\n')
result = {'jar_sha256': hashlib.sha256(jar.read_bytes()).hexdigest(), 'profile': a.profile, 'seeded': seed.exists(), 'java': subprocess.check_output([a.java, '-version'], stderr=subprocess.STDOUT, text=True).strip(), 'checks': []}
process = None
try:
    with (out / 'server.log').open('w') as log:
        start = time.monotonic()
        process = subprocess.Popen(args, cwd=work, env=env, stdout=log, stderr=subprocess.STDOUT)
        while True:
            if process.poll() is not None:
                raise RuntimeError('server exited before ready; inspect server.log')
            try:
                status, body, headers = req('/api/admin/status', headers={'Accept': 'application/json'})
                if status == 200:
                    break
            except (urllib.error.URLError, TimeoutError, ConnectionError):
                pass
            if time.monotonic() - start > 60:
                raise TimeoutError('startup exceeded 60s')
            time.sleep(0.025)
        result['health_ready_seconds'] = time.monotonic() - start
        if not seed.exists():
            req('/api/auth/login', data=b'username=admin&password=admin', headers={'Content-Type': 'application/x-www-form-urlencoded'})
            req('/api/admin/rules', data=json.dumps({'protocol': 'HTTP', 'targetHost': 'startup.mock', 'matchKey': '/startup', 'method': 'GET', 'status': 200, 'responseBody': 'startup-correct'}).encode(), headers={'Content-Type': 'application/json', 'Accept': 'application/json'})
        t = time.monotonic()
        status, body, headers = req('/mock/startup', headers={'X-Original-Host': 'startup.mock'})
        assert status == 200 and body == b'startup-correct', (status, body[:100])
        result['first_mock_ms'] = (time.monotonic() - t) * 1000
        result['mock_ready_seconds'] = time.monotonic() - start
        assets = ['/webjars/vue/dist/vue.global.prod.js', '/webjars/bootstrap/dist/css/bootstrap.min.css', '/webjars/bootstrap-icons/font/bootstrap-icons.min.css', '/webjars/bootstrap-icons/font/fonts/bootstrap-icons.woff2', '/webjars/codemirror/lib/codemirror.js', '/webjars/codemirror/lib/codemirror.css', '/webjars/codemirror/mode/javascript/javascript.js', '/webjars/codemirror/mode/xml/xml.js'] + ['/webjars/fontsource__jetbrains-mono/files/jetbrains-mono-latin-' + w + '-normal.woff2' for w in ('400', '500', '600')]
        for path in assets:
            t = time.monotonic()
            status, body, headers = req(path)
            assert status == 200 and len(body) > 100 and (headers.get('Cache-Control') == 'no-cache'), (path, status, headers)
            result['checks'].append({'path': path, 'bytes': len(body), 'sha256': hashlib.sha256(body).hexdigest(), 'first_ms': (time.monotonic() - t) * 1000})
        result['ui_ready_seconds'] = time.monotonic() - start
        result['proc_status'] = '\n'.join((x for x in pathlib.Path('/proc/' + str(process.pid) + '/status').read_text().splitlines() if x.startswith(('VmRSS', 'VmHWM', 'Threads'))))
        process.terminate()
        process.wait(timeout=30)
        if not seed.exists():
            shutil.copy2(work / 'data/mockdb.sqlite', seed)
    result['success'] = True
finally:
    if process is not None and process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=30)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
    (out / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
    shutil.rmtree(work)
print(json.dumps({k: v for k, v in result.items() if k not in ('checks', 'java')}), flush=True)
