// Headless Edge + Chrome DevTools Protocol screenshot driver.
//   node shot.mjs <page.html | http://...> <outDir> <plan.json> [--width 1400] [--height 1000] [--dpr 1] [--dark] [--keep]
// A local page is served from its own folder on a free port (module imports such as ./vendor/three need http://).
// plan.json: an array of steps, run in order:
//   { "name": "a-iso",  "js": "__focus(['a']); __view('iso')", "wait": 400 }          -> outDir/a-iso.png (viewport)
//   { "name": "a-view", "js": "...", "selector": ".card[data-key='a'] .view" }          -> clipped to that element
//   { "name": "page",   "full": true }                                                   -> the whole scrolled page
//   { "name": "keys",   "js": "__keys()", "value": true }                                -> prints the JS result, no image
//   { "name": "{key}-iso", "forEach": "__keys()", "js": "__focus(['{key}']); __view('iso','{key}')" }   -> one step per key
//   optional per step: "width"/"height"/"dpr" (viewport for this and later steps), "media": {"prefers-color-scheme": "dark"}
// Waits for window.__ready === true when the page defines it. Exit code 1 if a step's JS threw or the page logged errors.
import { spawn, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { startStatic } from './lib/static.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const EDGE = process.env.EDGE_PATH || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
const argv = process.argv.slice(2), VALUE_FLAGS = new Set(['--width', '--height', '--dpr']), pos = [];
for (let i = 0; i < argv.length; i++) { if (VALUE_FLAGS.has(argv[i])) i++; else if (!argv[i].startsWith('--')) pos.push(argv[i]); }
const opt = (k, d) => { const i = argv.indexOf(k); return i >= 0 ? argv[i + 1] : d; };
const [target, outDir, planFile] = pos;
if (!target || !outDir || !planFile) { console.log('usage: node shot.mjs <page.html|url> <outDir> <plan.json> [--width 1400] [--height 1000] [--dpr 1] [--dark] [--keep]'); process.exit(2); }
const plan = JSON.parse(fs.readFileSync(planFile, 'utf8').replace(/^\uFEFF/, ''));
let W = +opt('--width', 1400), H = +opt('--height', 1000), DPR = +opt('--dpr', 1);
fs.mkdirSync(outDir, { recursive: true });

const sleep = ms => new Promise(r => setTimeout(r, ms));
const scratch = path.join(here, '.scratch');
const udd = path.join(scratch, `edge-${process.pid}-${Date.now().toString(36)}`);
let proc = null, ws = null, srv = null, cleaned = false, failures = 0;

function killEdge() {
  if (!proc || proc.exitCode != null) return;
  if (process.platform === 'win32') spawnSync('taskkill', ['/PID', String(proc.pid), '/T', '/F'], { stdio: 'ignore' });
  else try { proc.kill('SIGKILL'); } catch { }
}
async function cleanup() {
  if (cleaned) return; cleaned = true;
  try { ws && ws.close(); } catch { }
  killEdge();
  try { srv && srv.server.close(); } catch { }
  if (!argv.includes('--keep')) {
    for (let i = 0; i < 10; i++) { try { fs.rmSync(udd, { recursive: true, force: true }); break; } catch { await sleep(300); } }
    try { fs.rmdirSync(scratch); } catch { }      // only succeeds when no other run is using it
  }
}
// profiles left behind by runs that were killed outright (no cleanup ran): owner pid gone and untouched for 15 minutes
function sweepStale() {
  let names = [];
  try { names = fs.readdirSync(scratch); } catch { return; }
  for (const n of names) {
    const m = /^edge-(\d+)-/.exec(n), d = path.join(scratch, n);
    if (!m || +m[1] === process.pid) continue;
    let alive = true;
    try { process.kill(+m[1], 0); } catch (e) { alive = e.code === 'EPERM'; }
    try { if (!alive && Date.now() - fs.statSync(d).mtimeMs > 15 * 60 * 1000) fs.rmSync(d, { recursive: true, force: true }); } catch { }
  }
}
process.on('SIGINT', async () => { await cleanup(); process.exit(130); });
process.on('exit', () => { killEdge(); });

async function main() {
  let url = target;
  if (!/^https?:\/\//.test(target)) {
    const file = path.resolve(target);
    if (!fs.existsSync(file)) throw new Error('no such page: ' + file);
    // pages inside this folder are served from here (so ../vendor resolves); anything else from its own folder
    const root = file.startsWith(here + path.sep) ? here : path.dirname(file);
    srv = await startStatic(root, 0, path.basename(file));
    url = srv.url(path.relative(root, file));
  }
  sweepStale();
  fs.mkdirSync(udd, { recursive: true });
  proc = spawn(EDGE, ['--headless=new', '--remote-debugging-port=0', `--user-data-dir=${udd}`, `--window-size=${W},${H}`,
    '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist', '--no-first-run', '--no-default-browser-check', '--disable-extensions',
    '--disable-sync', '--hide-scrollbars', '--mute-audio', '--disable-features=msEdgeSidebarV2,EdgeCollections', 'about:blank'], { stdio: 'ignore' });
  proc.on('error', e => console.error('could not start Edge:', e.message));

  // DevToolsActivePort is written into the profile once the browser listens
  let port = null;
  for (let i = 0; i < 120 && !port; i++) {
    try { port = +fs.readFileSync(path.join(udd, 'DevToolsActivePort'), 'utf8').split('\n')[0]; } catch { }
    if (!port) await sleep(250);
  }
  if (!port) throw new Error('Edge did not open a DevTools port (is it installed at ' + EDGE + '?)');
  let page = null;
  for (let i = 0; i < 80 && !page; i++) {
    try { page = (await (await fetch(`http://127.0.0.1:${port}/json/list`)).json()).find(t => t.type === 'page'); } catch { }
    if (!page) await sleep(250);
  }
  if (!page) throw new Error('no page target');

  ws = new WebSocket(page.webSocketDebuggerUrl);
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = () => rej(new Error('websocket failed')); });
  let id = 0;
  const waiting = new Map(), events = [];
  ws.onmessage = m => { const d = JSON.parse(m.data); if (d.id && waiting.has(d.id)) { waiting.get(d.id)(d); waiting.delete(d.id); } else events.push(d); };
  const send = (method, params = {}, timeout = 30000) => new Promise((res, rej) => {
    const i = ++id, t = setTimeout(() => { waiting.delete(i); rej(new Error(`${method} timed out`)); }, timeout);
    waiting.set(i, d => { clearTimeout(t); d.error ? rej(new Error(`${method}: ${d.error.message}`)) : res(d.result); });
    ws.send(JSON.stringify({ id: i, method, params }));
  });
  const evaluate = async expr => {
    const r = await send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true });
    if (r.exceptionDetails) throw new Error((r.exceptionDetails.exception && r.exceptionDetails.exception.description) || r.exceptionDetails.text);
    return r.result && r.result.value;
  };
  // mobile:false keeps the layout viewport equal to W (the built page has no viewport meta; the artifact host adds one)
  const metrics = () => send('Emulation.setDeviceMetricsOverride', { width: W, height: H, deviceScaleFactor: DPR, mobile: false });

  await send('Page.enable'); await send('Runtime.enable');
  await metrics();
  if (argv.includes('--dark')) await send('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-color-scheme', value: 'dark' }] });
  await send('Page.navigate', { url });
  for (let i = 0; i < 120 && !events.some(e => e.method === 'Page.loadEventFired'); i++) await sleep(250);
  // the viewer sets window.__ready once textures are decoded and the first frame is drawn
  for (let i = 0; i < 120; i++) {
    const r = await evaluate("typeof window.__ready === 'undefined' ? 'none' : String(window.__ready)").catch(() => 'err');
    if (r === 'true' || r === 'none') break;
    await sleep(250);
  }
  await sleep(300);

  const steps = [];
  for (const s of plan) {
    if (!s.forEach) { steps.push(s); continue; }
    const keys = await evaluate(s.forEach);
    for (const k of keys || []) { const c = JSON.parse(JSON.stringify(s).replace(/\{key\}/g, k)); delete c.forEach; steps.push(c); }
  }
  for (const s of steps) {
    try {
      if (s.width || s.height || s.dpr) { W = s.width || W; H = s.height || H; DPR = s.dpr || DPR; await metrics(); await sleep(200); }
      if (s.media) await send('Emulation.setEmulatedMedia', { features: Object.entries(s.media).map(([name, value]) => ({ name, value })) });
      const v = s.js ? await evaluate(s.js) : undefined;
      await sleep(s.wait ?? 400);
      if (s.value) { console.log(`${s.name}: ${JSON.stringify(v)}`); continue; }
      const p = { format: 'png', captureBeyondViewport: !!(s.full || s.selector) };
      if (s.selector) {
        const r = await evaluate(`(() => { const e = document.querySelector(${JSON.stringify(s.selector)}); if (!e) return null; const b = e.getBoundingClientRect(); return [b.x + scrollX, b.y + scrollY, b.width, b.height]; })()`);
        if (!r) throw new Error('selector not found: ' + s.selector);
        p.clip = { x: r[0], y: r[1], width: r[2], height: r[3], scale: 1 };
      } else if (s.clip) p.clip = { x: s.clip[0], y: s.clip[1], width: s.clip[2], height: s.clip[3], scale: 1 };
      else if (s.full) {
        const m = await send('Page.getLayoutMetrics'), c = m.cssContentSize || m.contentSize;
        p.clip = { x: 0, y: 0, width: Math.ceil(c.width), height: Math.ceil(c.height), scale: 1 };
      }
      const shot = await send('Page.captureScreenshot', p, 60000);
      const f = path.join(outDir, s.name + '.png');
      fs.writeFileSync(f, Buffer.from(shot.data, 'base64'));
      console.log('shot', path.relative(process.cwd(), f));
    } catch (e) { failures++; console.log(`step ${s.name} failed: ${e.message}`); }
  }
  const pageErrors = events.filter(e => e.method === 'Runtime.exceptionThrown').map(e => (e.params.exceptionDetails.exception && e.params.exceptionDetails.exception.description) || e.params.exceptionDetails.text);
  const consoleErrors = events.filter(e => e.method === 'Runtime.consoleAPICalled' && e.params.type === 'error').map(e => e.params.args.map(a => a.value ?? a.description).join(' '));
  const warns = events.filter(e => e.method === 'Runtime.consoleAPICalled' && e.params.type === 'warning').map(e => e.params.args.map(a => a.value ?? a.description).join(' '));
  if (warns.length) console.log('page warnings:\n  ' + warns.slice(0, 20).join('\n  '));
  if (pageErrors.length || consoleErrors.length) { failures++; console.log('page errors:\n  ' + [...pageErrors, ...consoleErrors].slice(0, 20).join('\n  ')); }
}

main().catch(e => { failures++; console.error('ERR', e.message); }).finally(async () => { await cleanup(); process.exit(failures ? 1 : 0); });
