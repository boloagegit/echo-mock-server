// Local deterministic HTTP target for the I/O configuration experiment.
import http from 'node:http';
const server = http.createServer((req, res) => {
  req.resume();
  req.on('end', () => {
    const path = new URL(req.url, 'http://localhost').pathname;
    const delay = path === '/forward-slow' ? 50 : 0;
    const status = path === '/forward-error' ? 503 : 200;
    const body = JSON.stringify({upstream: true, delayMs: delay});
    const send = () => {
      if (res.destroyed) return;
      res.writeHead(status, {'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body)});
      res.end(body);
    };
    if (delay) setTimeout(send, delay); else send();
  });
});
server.listen(18081, '127.0.0.1', () => console.log('ready'));
process.on('SIGTERM', () => { server.close(); server.closeAllConnections(); });
