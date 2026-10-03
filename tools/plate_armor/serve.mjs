import http from 'node:http';
import fs from 'node:fs';
const dir = new URL('./', import.meta.url);
http.createServer((req, res) => {
  const f = new URL('.' + (req.url.split('?')[0] === '/' ? '/armor-preview.html' : req.url.split('?')[0]), dir);
  fs.readFile(f, (e, d) => {
    if (e) { res.writeHead(404); return res.end(); }
    const ext = f.pathname.split('.').pop();
    const type = { html: 'text/html; charset=utf-8', js: 'text/javascript; charset=utf-8', mjs: 'text/javascript; charset=utf-8', json: 'application/json', png: 'image/png', webp: 'image/webp', jpg: 'image/jpeg' }[ext] || 'application/octet-stream';
    res.writeHead(200, { 'content-type': type, 'cache-control': 'no-store' });
    res.end(d);
  });
}).listen(+process.argv[2] || 5181);
