// Assemble designs plus refs/ into ONE self-contained HTML page (models + textures inlined).
//   node build.mjs                                 -> preview.html: every design under designs/, three.js from ./vendor (serve the folder: node serve.mjs)
//   node build.mjs --cdn                           -> preview-cdn.html, three.js from jsDelivr (pinned 0.160.0), Google Fonts kept: for publishing
//   node build.mjs --out x.html                    -> custom output file (relative to this folder)
//   node build.mjs --designs tools-test/designs    -> take designs from another root (e.g. the pipeline sample)
//   node build.mjs --designs designs,designs-more  -> several roots in one page (folder names = design keys must be unique across roots)
//   node build.mjs --kitchen [--cdn]               -> kitchen.html / publish-kitchen.html: the combined page to pick kitchen stations.
//       Roots default to designs,designs-more. Each design goes to a station and a style family:
//         station  meta.station if given, else the key prefix fryer_ / oven_ / prep_, else anything under designs/ is a fryer
//         family   meta.family if given, else the key's last letter (A, oven_a, prep_a -> A)
//       Page: intro + one switch for every view, one kitchen scene per family (FD stove + cooking pot for scale, then the
//       family's fryer / oven / prep station side by side; meta.kitchen.under is put under its block), a section of cards
//       per station, the reference row.
//   --posters  (any mode) also renders every view once in headless Edge (shot.mjs + plans/posters.json) and bakes the pictures
//       into the page, so it shows real renders before three.js has arrived, or in a browser without WebGL. Needs Edge; if the
//       render fails the page is built without pictures (a warning says so).
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { decodePNG } from './lib/png.mjs';
import { mcFullId } from './lib/mcmodel.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const argv = process.argv.slice(2);
const arg = k => { const i = argv.indexOf(k); return i >= 0 ? argv[i + 1] : null; };
const CDN = argv.includes('--cdn'), KITCHEN = argv.includes('--kitchen'), POSTERS = argv.includes('--posters');
const MODE = KITCHEN ? 'kitchen' : 'preview';
const OUT = path.resolve(here, arg('--out') || (KITCHEN ? (CDN ? 'publish-kitchen.html' : 'kitchen.html') : (CDN ? 'preview-cdn.html' : 'preview.html')));
const ROOTS = (arg('--designs') || (KITCHEN ? 'designs,designs-more' : 'designs')).split(',').map(s => s.trim()).filter(Boolean).map(r => path.resolve(here, r));
const THREE_CDN = 'https://cdn.jsdelivr.net/npm/three@0.160.0/build/three.module.min.js';
const read = f => fs.readFileSync(path.join(here, f), 'utf8').replace(/^\uFEFF/, '');
const rel = f => path.relative(here, f) || '.';
const esc = s => String(s ?? '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
const MOD_NAMES = { farmersdelight: '农夫乐事', casualness_delight: '随性乐事', minecraft: '原版' };

// kitchen page: stations in page order, style families (A/B/C) with the wording the page uses
const STATIONS = [
  { id: 'fryer', name: '炸锅', sub: '取代随性乐事的炸锅。A 要坐在炉灶上用（和农夫乐事厨锅一样，下面得有热源）；B 和 C 自带火，直接放地上。' },
  { id: 'oven', name: '烤炉', sub: '烤东西的炉子（预览里炉膛放着派或烤鸭）。三个都自带火，不用垫炉灶。' },
  { id: 'prep', name: '备餐台', sub: '切菜、装盘，冷饮也在这里做。不需要热源，放哪都能用。' },
];
const OTHER = { id: 'other', name: '其他方案', sub: '没认出是哪种台子的方案（meta.json 里可以写 "station": "fryer" / "oven" / "prep"）。' };
const FAMILIES = {
  A: { name: '农夫乐事田园风', blurb: '和农夫乐事炉灶同一套砖，木头取农夫乐事的砧板和橱柜，金属是冷灰铸铁和暗黄铜。' },
  B: { name: '商用不锈钢', blurb: '不锈钢柜体、深灰面板、铜红把手，像餐馆后厨；三台用同一套支脚和侧板。' },
  C: { name: '中式', blurb: '红砖灶身、黄泥抹面、木炭明火，配铁锅、竹器和枣红漆木门。' },
};
const stationOf = (key, meta, root) => {
  if (meta.station && STATIONS.some(s => s.id === meta.station)) return meta.station;
  const s = STATIONS.find(s => key.toLowerCase().startsWith(s.id + '_'));
  if (s) return s.id;
  return path.basename(root) === 'designs' ? 'fryer' : 'other';
};
const familyOf = (key, meta) => {
  if (meta.family) return String(meta.family).toUpperCase();
  const m = /(?:^|_)([a-z])$/i.exec(key);
  return m ? m[1].toUpperCase() : null;
};

// ---------------------------------------------------------------- assets
const assets = {};
let texBytes = 0;
const walk = d => fs.existsSync(d) ? fs.readdirSync(d, { withFileTypes: true }).flatMap(e => e.isDirectory() ? walk(path.join(d, e.name)) : [path.join(d, e.name)]) : [];
function addTree(dir, prefix) {
  for (const f of walk(path.join(dir, 'models')).filter(f => f.endsWith('.json'))) {
    const p = path.relative(path.join(dir, 'models'), f).replace(/\\/g, '/').replace(/\.json$/, '');
    try { assets[`${prefix}|models/${p}`] = JSON.parse(fs.readFileSync(f, 'utf8').replace(/^\uFEFF/, '')); }
    catch (e) { console.warn(`  ! ${rel(f)}: ${e.message} (left out; run check.mjs)`); }
  }
  for (const f of walk(path.join(dir, 'textures')).filter(f => f.endsWith('.png'))) {
    const p = path.relative(path.join(dir, 'textures'), f).replace(/\\/g, '/').replace(/\.png$/, '');
    const buf = fs.readFileSync(f);
    let w = 16, h = 16;
    try { const d = decodePNG(buf); w = d.width; h = d.height; } catch (e) { console.warn(`  ! ${rel(f)}: ${e.message}`); continue; }
    let mcmeta = null;
    if (fs.existsSync(f + '.mcmeta')) { try { mcmeta = JSON.parse(fs.readFileSync(f + '.mcmeta', 'utf8').replace(/^\uFEFF/, '')); } catch (e) { console.warn(`  ! ${rel(f)}.mcmeta: ${e.message}`); } }
    assets[`${prefix}|textures/${p}`] = { src: 'data:image/png;base64,' + buf.toString('base64'), w, h, mcmeta };
    texBytes += buf.length;
  }
}

const refsDir = path.join(here, 'refs');
if (!fs.existsSync(path.join(refsDir, 'index.json'))) throw new Error('refs/index.json missing: run node extract-refs.mjs first');
const refIndex = JSON.parse(read('refs/index.json'));
for (const ns of fs.readdirSync(refsDir, { withFileTypes: true }).filter(e => e.isDirectory()).map(e => e.name)) addTree(path.join(refsDir, ns), 'refs/' + ns);

const designs = [], seen = new Map();
for (const ROOT of ROOTS) {
  if (!fs.existsSync(ROOT)) { console.warn(`  ! ${rel(ROOT)}: no such folder`); continue; }
  for (const e of fs.readdirSync(ROOT, { withFileTypes: true })) {
    if (!e.isDirectory() || e.name.startsWith('.')) continue;
    const dir = path.join(ROOT, e.name), mf = path.join(dir, 'meta.json');
    if (!fs.existsSync(mf)) { console.warn(`  ! ${e.name}: no meta.json, skipped`); continue; }
    let meta;
    try { meta = JSON.parse(fs.readFileSync(mf, 'utf8').replace(/^\uFEFF/, '')); } catch (err) { console.warn(`  ! ${e.name}/meta.json: ${err.message}, skipped`); continue; }
    const key = e.name, ns = meta.namespace || 'miningdim';
    if (seen.has(key)) { console.warn(`  ! ${rel(dir)}: key "${key}" is already ${rel(seen.get(key))}, skipped (rename the folder)`); continue; }
    seen.set(key, dir);
    if (meta.key && meta.key !== key) console.warn(`  ! ${key}: meta.key "${meta.key}" differs from folder name; using "${key}"`);
    const norm = s => s && { model: mcFullId(String(s.model).includes(':') ? s.model : `${ns}:block/${String(s.model).replace(/^block\//, '')}`), x: s.x || 0, y: s.y || 0 };
    addTree(dir, 'design/' + key);
    designs.push({
      key, ns, order: meta.order ?? 999, tag: meta.tag || '', name: meta.name || key, tagline: meta.tagline || '', description: meta.description || '',
      notes: Array.isArray(meta.notes) ? meta.notes : meta.notes ? [String(meta.notes)] : [], hires: !!meta.hires, kitchen: meta.kitchen || null,
      blocks: (meta.blocks || []).map(b => ({ label: b.label || '', pos: b.pos || null, idle: norm(b.idle), active: norm(b.active || b.idle) })),
      ...(KITCHEN ? { station: stationOf(key, meta, ROOT), family: familyOf(key, meta) } : {}),
    });
  }
}
designs.sort((a, b) => a.order - b.order || a.key.localeCompare(b.key));

// ---------------------------------------------------------------- markup
const refBlock = k => refIndex.blocks.find(b => b.key === k);
const STRIP = ['fd_cooking_pot', 'fd_stove', 'cd_fryer'].map(refBlock).filter(Boolean);
const stationById = id => STATIONS.find(s => s.id === id) || OTHER;
const stateSeg = '<div class="seg" role="group" aria-label="状态"><button type="button" data-state="idle" aria-pressed="true">待机</button><button type="button" data-state="active">工作中</button></div>';
const viewSeg = '<div class="seg" role="group" aria-label="视角"><button type="button" data-preset="front">正面</button><button type="button" data-preset="iso" aria-pressed="true">斜视</button><button type="button" data-preset="top">俯视</button><button type="button" data-preset="side">侧面</button></div>';
// posters: { viewId: data URL } from --posters; a view without one just starts empty and fills in once three.js has drawn it
const posterImg = (posters, id) => posters[id] ? `<img class="poster" alt="" decoding="async" src="${posters[id]}">` : '';
const cardTag = d => {
  if (!KITCHEN) return d.tag || d.key.toUpperCase();
  const st = stationById(d.station), fam = d.family && FAMILIES[d.family];
  return st.id === 'other' ? (d.tag || d.key.toUpperCase()) : `${st.name} ${d.family || ''}${fam ? ' · ' + fam.name : ''}`;
};
const notesList = (d, pad) => d.notes.length ? `${pad}<ul class="notes">\n${d.notes.map(n => `${pad}  <li>${esc(n)}</li>`).join('\n')}\n${pad}</ul>` : '';
function cardHtml(d, posters) {
  if (!KITCHEN) return `    <article class="card" data-key="${esc(d.key)}">
      <header class="card-head"><span class="tag">${esc(cardTag(d))}</span><h2>${esc(d.name)}</h2>${d.tagline ? `<p class="tagline">${esc(d.tagline)}</p>` : ''}</header>
      <div class="view">${posterImg(posters, d.key)}<canvas aria-label="${esc(d.name)} 三维预览"></canvas><span class="chip">待机</span></div>
      <div class="controls">${stateSeg}${viewSeg}</div>
      <div class="body">
        <p class="desc">${esc(d.description)}</p>
${notesList(d, '        ')}
        <dl class="facts" data-facts></dl>
      </div>
    </article>`;
  return `      <article class="card" data-key="${esc(d.key)}" id="d-${esc(d.key)}">
        <header class="card-head"><span class="tag">${esc(cardTag(d))}</span><h3>${esc(d.name)}</h3>${d.tagline ? `<p class="tagline">${esc(d.tagline)}</p>` : ''}</header>
        <div class="view">${posterImg(posters, d.key)}<canvas aria-label="${esc(d.name)} 三维预览"></canvas><span class="chip">待机</span></div>
        <div class="controls">${stateSeg}${viewSeg}</div>
        <div class="body">
          <p class="desc">${esc(d.description)}</p>
          <details class="more"><summary>实现说明</summary>
${notesList(d, '            ')}
            <dl class="facts" data-facts></dl>
          </details>
        </div>
      </article>`;
}
// the size never breaks inside the numbers (the three reference figures are ~100px wide on a phone)
const fmtSize = s => s ? `<span class="nw">${[0, 2, 1].map(k => +s[k].toFixed(2)).join('×')}</span> px` : '';
const refCards = posters => STRIP.map(b => `      <figure class="ref" data-ref="${esc(b.key)}"><div class="view">${posterImg(posters, b.key)}<canvas aria-label="${esc(b.name)} 三维预览"></canvas></div><figcaption><b>${esc(b.name)}</b><span>${esc(MOD_NAMES[b.mod] || b.mod)} · ${fmtSize(b.size_px)}</span></figcaption></figure>`).join('\n');
const emptyNote = '    <p class="empty">还没有方案。每个方案一个文件夹（meta.json + models/ + textures/），写好后重新运行 node build.mjs。</p>';

// kitchen: stations that have designs (page order), families that have at least two stations
const stations = KITCHEN ? [...STATIONS, OTHER].map(s => ({ ...s, designs: designs.filter(d => d.station === s.id) })).filter(s => s.designs.length) : [];
const families = KITCHEN ? Object.keys(FAMILIES).concat([...new Set(designs.map(d => d.family).filter(f => f && !FAMILIES[f]))].sort())
  .map(id => ({ id, name: (FAMILIES[id] || {}).name || id, blurb: (FAMILIES[id] || {}).blurb || '',
    members: STATIONS.map(s => designs.find(d => d.station === s.id && d.family === id)).filter(Boolean).map(d => ({ key: d.key, station: d.station, label: stationById(d.station).name })) }))
  .filter(f => f.members.length >= 2) : [];
const underName = k => { const b = refBlock(k); return b ? `${MOD_NAMES[b.mod] || ''}${b.name}` : k; };
function sceneHtml(f, posters) {
  const parts = f.members.map(m => { const d = designs.find(x => x.key === m.key); return `${m.label}「${d.name}」${d.kitchen && d.kitchen.under ? `（坐在一台${underName(d.kitchen.under)}上）` : ''}`; });
  return `      <div class="wide scene" data-key="family-${esc(f.id)}" data-scene="${esc(f.id)}" id="family-${esc(f.id)}">
        <header class="scene-head"><h3>${esc(f.id)} 套 · ${esc(f.name)}</h3>${f.blurb ? `<p>${esc(f.blurb)}</p>` : ''}</header>
        <div class="view">${posterImg(posters, 'family-' + f.id)}<canvas aria-label="${esc(f.id)} 套厨房三维预览"></canvas><span class="chip">待机</span><div class="labels"></div></div>
        <div class="controls">${stateSeg}${viewSeg}</div>
        <p class="legend">从左到右：农夫乐事炉灶 + 厨锅（参照），空一格，${esc(parts.join('、'))}。</p>
      </div>`;
}
const stationHtml = (s, posters) => `  <section class="section station" data-key="${esc(s.id)}" id="sec-${esc(s.id)}">
    <h2>${esc(s.name)}</h2>
    <p class="sub">${esc(s.sub)}</p>
    <div class="designs">
${s.designs.map(d => cardHtml(d, posters)).join('\n')}
    </div>
  </section>`;

// ---------------------------------------------------------------- page
const now = new Date(), pad = n => String(n).padStart(2, '0');
const data = {
  mode: MODE,
  built: `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())} ${pad(now.getHours())}:${pad(now.getMinutes())}`,
  designs: designs.map(({ order, ...d }) => d),
  families,
  refs: { blocks: refIndex.blocks, floors: refIndex.floors, sources: refIndex.sources },
  assets,
};
const viewer = read('viewer.js'), nl = viewer.indexOf('\n');
const lib = read('lib/mcmodel.js').replace(/^export /gm, '');
const VENDOR = path.join(here, 'vendor', 'three.module.min.js');

/** The page for one output file: cdn decides three.js + fonts; local pages point at a vendor copy relative to `out`. */
function assemble({ cdn, out, posters }) {
  // local: inside this folder the page points back at ./vendor (serve this folder: --out tools-test/x.html -> ../vendor/...);
  // anywhere else (another folder or drive, e.g. a scratch dir) three.js is copied next to the page as ./vendor/
  let vendorRel = 'vendor/three.module.min.js';
  if (!cdn) {
    if (out.startsWith(here + path.sep)) vendorRel = path.relative(path.dirname(out), VENDOR).replace(/\\/g, '/');
    else {
      const copy = path.join(path.dirname(out), 'vendor', 'three.module.min.js');
      if (path.resolve(copy) !== VENDOR) { fs.mkdirSync(path.dirname(copy), { recursive: true }); fs.copyFileSync(VENDOR, copy); }
    }
  }
  const importLine = viewer.slice(0, nl).replace(/'https:[^']+'/, cdn ? `'${THREE_CDN}'` : `'${vendorRel.startsWith('.') ? vendorRel : './' + vendorRel}'`);
  const script = [importLine, lib, viewer.slice(nl + 1)].join('\n');
  let html = read('template.html')
    .replace(/<!--MODE (\w+)-->\r?\n?([\s\S]*?)<!--\/MODE-->\r?\n?/g, (m, mode, body) => mode === MODE ? body : '');
  if (KITCHEN) html = html.replace(/<title>[^<]*<\/title>/, '<title>厨房烹饪台</title>')
    .replace('<!--FAMILY_SCENES-->', () => families.map(f => sceneHtml(f, posters)).join('\n') || emptyNote)
    .replace('<!--STATION_SECTIONS-->', () => stations.map(s => stationHtml(s, posters)).join('\n\n') || emptyNote)
    .replace('<!--JUMP_LINKS-->', () => stations.map(s => `<a href="#sec-${esc(s.id)}">${esc(s.name)}</a>`).join(''));
  else html = html.replace('<!--DESIGN_CARDS-->', () => designs.length ? designs.map(d => cardHtml(d, posters)).join('\n') : emptyNote.replace('还没有方案', 'designs/ 下还没有方案'));
  html = html
    .replace(/<!--POSTER (\S+)-->/g, (m, id) => posterImg(posters, id))
    .replace('<!--REF_CARDS-->', () => refCards(posters))
    .replace('<!--DATA-->', () => `<script type="application/json" id="station-data">${JSON.stringify(data).replace(/</g, '\\u003c')}</script>`)
    .replace('/*SCRIPT*/', () => script);
  // local builds: no Google Fonts (a hanging stylesheet must not hold up the page offline); system fonts take over
  if (!cdn) html = html.replace(/<link rel="(preconnect|stylesheet)" href="https:\/\/fonts\.[^"]+"[^>]*>\n?/g, '');
  return html;
}

/** --posters: build a local copy of this page, let shot.mjs render it in headless Edge and read back every view as a picture. */
function renderPosters() {
  const dir = path.join(here, '.scratch-build'), tag = `${process.pid}-${Date.now().toString(36)}`;
  const page = path.join(dir, `posters-${tag}.html`), shots = path.join(dir, `shots-${tag}`);
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(page, assemble({ cdn: false, out: page, posters: {} }));
  console.log('  posters: rendering every view in headless Edge (shot.mjs, plans/posters.json) ...');
  const r = spawnSync(process.execPath, [path.join(here, 'shot.mjs'), page, shots, path.join(here, 'plans', 'posters.json')], { cwd: here, encoding: 'utf8', maxBuffer: 512 * 1024 * 1024 });
  try { fs.rmSync(page, { force: true }); fs.rmSync(shots, { recursive: true, force: true }); fs.rmdirSync(dir); } catch { }
  const line = (r.stdout || '').split(/\r?\n/).find(l => l.startsWith('posters: '));
  let posters = null;
  try { posters = line && JSON.parse(line.slice('posters: '.length)); } catch { }
  if (r.status !== 0 || !posters || typeof posters !== 'object') {
    console.warn(`  ! posters: render failed (exit ${r.status}), page built without baked pictures`);
    const log = ((r.stdout || '') + (r.stderr || '')).split(/\r?\n/).filter(l => l && !l.startsWith('posters: ')).slice(0, 12);
    if (log.length) console.warn('    ' + log.join('\n    '));
    return {};
  }
  for (const [k, v] of Object.entries(posters)) if (!/^data:image\/(webp|png);base64,[A-Za-z0-9+/=]+$/.test(String(v))) delete posters[k];
  const kb = Object.values(posters).reduce((s, v) => s + v.length, 0) / 1024;
  console.log(`  posters: ${Object.keys(posters).length} pictures, ${kb.toFixed(0)} KB`);
  return posters;
}

const posters = POSTERS ? renderPosters() : {};
const html = assemble({ cdn: CDN, out: OUT, posters });
fs.mkdirSync(path.dirname(OUT), { recursive: true });
fs.writeFileSync(OUT, html);
const groups = KITCHEN ? '  ' + stations.map(s => `${s.name}: ${s.designs.map(d => d.key).join(' ')}`).join(' | ') + `  scenes: ${families.map(f => f.id).join(' ') || '(none)'}` : '';
console.log(`ok ${rel(OUT)}  ${(Buffer.byteLength(html) / 1024).toFixed(0)} KB  designs: ${designs.map(d => d.key).join(', ') || '(none)'}${groups}  assets: ${Object.keys(assets).length} (${(texBytes / 1024).toFixed(0)} KB png)  three: ${CDN ? 'cdn 0.160.0' : './vendor'}${POSTERS ? `  posters: ${Object.keys(posters).length}` : ''}`);
