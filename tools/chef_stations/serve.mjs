// Local preview server: node serve.mjs [port] [page]   (defaults 5182, preview.html)
// The local build imports ./vendor/three.module.min.js as an ES module, which browsers refuse over file://, hence a server.
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { startStatic } from './lib/static.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const port = +process.argv[2] || 5182, page = process.argv[3] || 'preview.html';
const { url } = await startStatic(here, port, page);
console.log(`serving ${here}\n  ${url(page)}\n(Ctrl+C to stop)`);
