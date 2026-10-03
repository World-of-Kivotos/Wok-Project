// Headless Edge + CDP screenshot driver (scratch tool; the shared browser pane had no free tab slot).
//   node shot.mjs <url> <outDir> <plan.json>
// plan.json: [{ "name": "front", "js": "__focus(['now','M']); __view('front')", "wait": 400, "clip": [x,y,w,h]? }]
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const here = path.dirname(fileURLToPath(import.meta.url));
const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
const [, , url, outDir, planFile] = process.argv;
const plan = JSON.parse(fs.readFileSync(planFile, 'utf8').replace(/^﻿/, ''));
fs.mkdirSync(outDir, { recursive: true });
const port = 9400 + Math.floor(Math.random() * 400);
const udd = path.join(here, 'edge-profile-' + port);
const W = 1400, H = 1000;
const proc = spawn(EDGE, ['--headless=new', `--remote-debugging-port=${port}`, `--user-data-dir=${udd}`, `--window-size=${W},${H}`,
  '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist', '--no-first-run', '--no-default-browser-check', 'about:blank'], { stdio: 'ignore' });
const sleep = ms => new Promise(r => setTimeout(r, ms));
let ws, id = 0; const pending = new Map(); const events = [];
async function main() {
  let targets;
  for (let i = 0; i < 60; i++) {
    try { targets = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json(); if (targets.some(t => t.type === 'page')) break; } catch { }
    await sleep(250);
  }
  const page = targets.find(t => t.type === 'page');
  ws = new WebSocket(page.webSocketDebuggerUrl);
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
  ws.onmessage = m => { const d = JSON.parse(m.data); if (d.id && pending.has(d.id)) { pending.get(d.id)(d); pending.delete(d.id); } else events.push(d); };
  const send = (method, params = {}) => new Promise(res => { const i = ++id; pending.set(i, res); ws.send(JSON.stringify({ id: i, method, params })); });
  await send('Page.enable'); await send('Runtime.enable');
  await send('Emulation.setDeviceMetricsOverride', { width: W, height: H, deviceScaleFactor: 1, mobile: false });
  await send('Page.navigate', { url });
  for (let i = 0; i < 80; i++) { if (events.some(e => e.method === 'Page.loadEventFired')) break; await sleep(250); }
  // wait until the viewer's helpers exist
  for (let i = 0; i < 80; i++) { const r = await send('Runtime.evaluate', { expression: 'typeof window.__focus', returnByValue: true }); if (r.result && r.result.result && r.result.result.value === 'function') break; await sleep(250); }
  await sleep(800);
  for (const step of plan) {
    const r = await send('Runtime.evaluate', { expression: step.js, awaitPromise: true, returnByValue: true });
    if (r.result && r.result.exceptionDetails) console.log('JS error in', step.name, JSON.stringify(r.result.exceptionDetails).slice(0, 400));
    await sleep(step.wait || 400);
    if (step.value) { console.log(step.name, JSON.stringify(r.result && r.result.result && r.result.result.value)); continue; }
    if (step.dataURL) {                                   // the js returned a canvas data URL (same-frame capture)
      const v = r.result && r.result.result && r.result.result.value;
      if (typeof v === 'string' && v.startsWith('data:image/png;base64,')) { fs.writeFileSync(path.join(outDir, step.name + '.png'), Buffer.from(v.slice(22), 'base64')); console.log('shot', step.name); }
      else console.log('no dataURL for', step.name, String(v).slice(0, 100));
      continue;
    }
    const p = { format: 'png' };
    if (step.card) {
      // clip to a card's canvas (document coords); step.sub = [fx, fy, fw, fh] fractions of the canvas
      const q = await send('Runtime.evaluate', { expression: `(() => { const c = document.querySelector('.card[data-variant="${step.card}"] canvas'); const r = c.getBoundingClientRect(); return [r.x + scrollX, r.y + scrollY, r.width, r.height]; })()`, returnByValue: true });
      const [x, y, w, h] = q.result.result.value, s = step.sub || [0, 0, 1, 1];
      p.clip = { x: x + w * s[0], y: y + h * s[1], width: w * s[2], height: h * s[3], scale: step.scale || 1 };
    } else if (step.clip) p.clip = { x: step.clip[0], y: step.clip[1], width: step.clip[2], height: step.clip[3], scale: step.scale || 1 };
    const s = await send('Page.captureScreenshot', p);
    fs.writeFileSync(path.join(outDir, step.name + '.png'), Buffer.from(s.result.data, 'base64'));
    console.log('shot', step.name);
  }
  const logs = events.filter(e => e.method === 'Runtime.exceptionThrown').map(e => JSON.stringify(e.params.exceptionDetails).slice(0, 300));
  if (logs.length) console.log('page exceptions:', logs.join('\n'));
}
main().catch(e => console.error('ERR', e)).finally(async () => { try { ws && ws.close(); } catch { } proc.kill(); await sleep(500); try { fs.rmSync(udd, { recursive: true, force: true }); } catch { } process.exit(0); });
