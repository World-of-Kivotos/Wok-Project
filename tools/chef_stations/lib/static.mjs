// Tiny static file server (used by serve.mjs and shot.mjs). Serves only files under root.
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';

const TYPES = { html: 'text/html; charset=utf-8', js: 'text/javascript; charset=utf-8', mjs: 'text/javascript; charset=utf-8', json: 'application/json; charset=utf-8',
  css: 'text/css; charset=utf-8', png: 'image/png', jpg: 'image/jpeg', webp: 'image/webp', svg: 'image/svg+xml', txt: 'text/plain; charset=utf-8', mcmeta: 'application/json; charset=utf-8' };

/** Start serving root on port (0 = any free port). Resolves to { server, port, url(rel) }. */
export function startStatic(root, port = 0, index = 'preview.html') {
  root = path.resolve(root);
  const server = http.createServer((req, res) => {
    let p = decodeURIComponent(req.url.split('?')[0]);
    if (p === '/') p = '/' + index;
    const f = path.resolve(root, '.' + p);
    if (f !== root && !f.startsWith(root + path.sep)) { res.writeHead(403); return res.end(); }
    fs.readFile(f, (e, d) => {
      if (e) { res.writeHead(404, { 'content-type': 'text/plain' }); return res.end('not found: ' + p); }
      res.writeHead(200, { 'content-type': TYPES[path.extname(f).slice(1).toLowerCase()] || 'application/octet-stream', 'cache-control': 'no-store' });
      res.end(d);
    });
  });
  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, '127.0.0.1', () => {
      const actual = server.address().port;
      resolve({ server, port: actual, url: rel => `http://127.0.0.1:${actual}/${String(rel).replace(/\\/g, '/').replace(/^\//, '')}` });
    });
  });
}
