import * as THREE from 'https://cdn.jsdelivr.net/npm/three@0.160.0/build/three.module.min.js';
// build.mjs puts lib/mcmodel.js (MC_* constants, mc* functions) right after the import line above, then this file.
// Renders vanilla 1.20.1 block models: per-quad direction shade (up 1 / N,S .8 / E,W .6 / down .5), no AO, flat light,
// nearest sampling, cutout/translucent/solid per the model's render_type, animated .mcmeta strips at 20 tps.
// One shared WebGL renderer draws every view and blits into each view's 2D canvas (no WebGL-context limit: a page with
// 9 cards + 3 scenes + refs still uses ONE context; a 2D canvas keeps its last picture even if that context is lost).
// DATA.mode 'preview' = design cards + one kitchen strip; 'kitchen' = family scenes + station sections (build.mjs --kitchen).

const DATA = JSON.parse(document.getElementById('station-data').textContent);
const KITCHEN = DATA.mode === 'kitchen';
const reduceMotion = matchMedia('(prefers-reduced-motion: reduce)').matches;
const PRESETS = { front: { yaw: 0, pitch: 0.1 }, iso: { yaw: -0.68, pitch: 0.52 }, top: { yaw: 0, pitch: 1.48 }, side: { yaw: -Math.PI / 2, pitch: 0.1 } };
const FOV = 30, UP = new THREE.Vector3(0, 1, 0);
const errors = (window.__errors = []);
const $$ = (s, el = document) => [...el.querySelectorAll(s)];

// ---------------------------------------------------------------- resources
function scopeFor(designKey, ns) {
  const root = n => (designKey != null && n === ns ? 'design/' + designKey : 'refs/' + n);
  return {
    key: designKey != null ? 'design/' + designKey : 'refs',
    model: id => { const s = mcSplitId(id); return DATA.assets[root(s.ns) + '|models/' + s.path] || null; },
    tex: id => { const s = mcSplitId(id); return root(s.ns) + '|textures/' + s.path; },
  };
}
const REF_SCOPE = scopeFor(null, null);
const refBlock = key => DATA.refs.blocks.find(b => b.key === key);
const designs = DATA.designs.map(d => ({ ...d, scope: scopeFor(d.key, d.ns) }));

const modelCache = new Map();
function model(scope, id) {
  const k = scope.key + '>' + id;
  if (!modelCache.has(k)) {
    const m = mcResolveModel(id, x => scope.model(x));
    if (m.errors.length) { errors.push(`[${scope.key}] ${id}: ${m.errors.join('; ')}`); console.warn(scope.key, id, m.errors); }
    modelCache.set(k, m);
  }
  return modelCache.get(k);
}
const bakeCache = new Map();
function bake(scope, spec) {
  const k = `${scope.key}>${spec.model}>${spec.x || 0}>${spec.y || 0}`;
  if (!bakeCache.has(k)) bakeCache.set(k, mcBakeModel(model(scope, spec.model), { x: spec.x || 0, y: spec.y || 0 }));
  return bakeCache.get(k);
}

function setupTex(t) {
  t.magFilter = t.minFilter = THREE.NearestFilter; t.generateMipmaps = false; t.flipY = false;
  t.wrapS = t.wrapT = THREE.ClampToEdgeWrapping; t.colorSpace = THREE.NoColorSpace;
  return t;
}
const MISSING = (() => {            // the game's purple/black "missing texture"
  const d = new Uint8Array(16 * 16 * 4);
  for (let y = 0; y < 16; y++) for (let x = 0; x < 16; x++) { const o = (y * 16 + x) * 4, on = ((x >> 3) ^ (y >> 3)) & 1; d.set(on ? [0, 0, 0, 255] : [248, 0, 248, 255], o); }
  const t = setupTex(new THREE.DataTexture(d, 16, 16)); t.needsUpdate = true; return t;
})();
const texEntries = new Map(), pending = [];
function texEntry(texKey) {
  if (texEntries.has(texKey)) return texEntries.get(texKey);
  const a = texKey && DATA.assets[texKey];
  let e;
  if (!a) {
    if (texKey) errors.push('missing texture ' + texKey);
    e = { tex: MISSING, info: mcAnimInfo(16, 16, null), w: 16, h: 16, missing: true };
  } else {
    const img = new Image(), tex = setupTex(new THREE.Texture(img));
    pending.push(new Promise(res => {
      img.onload = () => { tex.needsUpdate = true; markAll(); res(); };
      img.onerror = () => { errors.push('texture failed to load ' + texKey); res(); };
    }));
    img.src = a.src;
    e = { tex, info: mcAnimInfo(a.w, a.h, a.mcmeta), w: a.w, h: a.h };
  }
  Object.assign(e, { key: texKey, mats: {}, frame: '', uA: { value: new THREE.Vector2() }, uB: { value: new THREE.Vector2() }, uMix: { value: 0 } });
  applyFrame(e, tick);
  texEntries.set(texKey, e);
  return e;
}
function applyFrame(e, t) {
  const f = mcAnimFrame(e.info, t), k = `${f.a}:${f.b}:${f.t}`;
  if (k === e.frame) return false;
  e.frame = k;
  const sx = e.info.fw / e.w, sy = e.info.fh / e.h, cols = e.info.cols;
  e.uA.value.set((f.a % cols) * sx, Math.floor(f.a / cols) * sy);
  e.uB.value.set((f.b % cols) * sx, Math.floor(f.b / cols) * sy);
  e.uMix.value = f.t;
  return true;
}

const VS = 'attribute vec3 shadeCol; varying vec2 vUv; varying vec3 vCol;\n'
  + 'void main(){ vUv = uv; vCol = shadeCol; gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0); }';
const FS = 'uniform sampler2D uMap; uniform vec2 uA; uniform vec2 uB; uniform vec2 uSize; uniform vec2 uHalf; uniform float uMix; uniform float uCut; uniform float uSolid;\n'
  + 'varying vec2 vUv; varying vec3 vCol;\n'
  + 'void main(){\n'
  + '  vec2 l = clamp(vUv, uHalf, vec2(1.0) - uHalf);\n'           // stay inside the frame (vanilla shrinks UVs a hair too)
  + '  vec4 c = texture2D(uMap, uA + l * uSize);\n'
  + '  if (uMix > 0.0) c = mix(c, texture2D(uMap, uB + l * uSize), uMix);\n'
  + '  if (uCut >= 0.0 && c.a < uCut) discard;\n'
  + '  gl_FragColor = vec4(c.rgb * vCol, uSolid > 0.5 ? 1.0 : c.a);\n'
  + '}';
function material(e, rt) {
  if (e.mats[rt]) return e.mats[rt];
  const cut = rt === 'cutout' ? 0.1 : rt === 'cutout_mipped' ? 0.5 : rt === 'translucent' ? 0.004 : -1;
  return (e.mats[rt] = new THREE.ShaderMaterial({
    uniforms: {
      uMap: { value: e.tex }, uA: e.uA, uB: e.uB, uMix: e.uMix, uCut: { value: cut }, uSolid: { value: rt === 'solid' ? 1 : 0 },
      uSize: { value: new THREE.Vector2(e.info.fw / e.w, e.info.fh / e.h) }, uHalf: { value: new THREE.Vector2(0.5 / e.info.fw, 0.5 / e.info.fh) },
    },
    vertexShader: VS, fragmentShader: FS, side: THREE.FrontSide, transparent: rt === 'translucent',
  }));
}

// ---------------------------------------------------------------- scene building
const hexRgb = h => { const n = parseInt(String(h).replace('#', ''), 16); return [(n >> 16 & 255) / 255, (n >> 8 & 255) / 255, (n & 255) / 255]; };
const pkey = (x, y, z) => x + ',' + y + ',' + z;
/** placements: [{ scope, spec: {model, x, y}, pos: [x,y,z], tint?, floor? }] -> { group, animKeys, lo, hi } */
function buildGroup(placements) {
  const occupied = new Set();
  for (const p of placements) if (mcIsFullCube(model(p.scope, p.spec.model))) occupied.add(pkey(...p.pos));
  const buckets = new Map(), animKeys = new Set(), lo = [Infinity, Infinity, Infinity], hi = [-Infinity, -Infinity, -Infinity];
  for (const p of placements) {
    const m = model(p.scope, p.spec.model), rt = m.renderType || 'solid';
    for (const q of bake(p.scope, p.spec)) {
      if (q.cull) { const d = MC_DIR_VEC[q.cull]; if (occupied.has(pkey(p.pos[0] + d[0], p.pos[1] + d[1], p.pos[2] + d[2]))) continue; }
      const e = texEntry(q.tex ? p.scope.tex(q.tex) : null);
      if (e.info.animated) animKeys.add(e.key);
      const mat = material(e, rt);
      let b = buckets.get(mat);
      if (!b) buckets.set(mat, (b = { pos: [], uv: [], col: [] }));
      const s = q.shade ? MC_FACE_SHADE[q.facing] : 1, tc = q.tint >= 0 && p.tint && p.tint[q.tint] ? hexRgb(p.tint[q.tint]) : [1, 1, 1];
      for (const i of [0, 1, 2, 0, 2, 3]) {
        const v = q.pos[i], w = [v[0] / 16 + p.pos[0], v[1] / 16 + p.pos[1], v[2] / 16 + p.pos[2]];
        b.pos.push(...w); b.uv.push(q.uv[i][0] / 16, q.uv[i][1] / 16); b.col.push(s * tc[0], s * tc[1], s * tc[2]);
        if (!p.floor) for (let k = 0; k < 3; k++) { lo[k] = Math.min(lo[k], w[k]); hi[k] = Math.max(hi[k], w[k]); }
      }
    }
  }
  const group = new THREE.Group();
  for (const [mat, b] of buckets) {
    const g = new THREE.BufferGeometry();
    g.setAttribute('position', new THREE.Float32BufferAttribute(b.pos, 3));
    g.setAttribute('uv', new THREE.Float32BufferAttribute(b.uv, 2));
    g.setAttribute('shadeCol', new THREE.Float32BufferAttribute(b.col, 3));
    const mesh = new THREE.Mesh(g, mat);
    if (mat.transparent) mesh.renderOrder = 1;
    group.add(mesh);
  }
  return { group, animKeys, lo, hi };
}
let floorType = 'oak';
function floorFor(placements, pad) {
  if (!placements.length) return [];
  const xs = placements.map(p => p.pos[0]), zs = placements.map(p => p.pos[2]), y = Math.min(...placements.map(p => p.pos[1])) - 1;
  const f = DATA.refs.floors[floorType], out = [];
  for (let x = Math.min(...xs) - pad; x <= Math.max(...xs) + pad; x++) for (let z = Math.min(...zs) - pad; z <= Math.max(...zs) + pad; z++)
    out.push({ scope: REF_SCOPE, spec: { model: f.model }, pos: [x, y, z], tint: f.tint, floor: true });
  return out;
}

// ---------------------------------------------------------------- views
let renderer = null;
try {
  renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
  renderer.setPixelRatio(1);
  renderer.outputColorSpace = THREE.LinearSRGBColorSpace;
  renderer.setClearColor(0x000000, 0);
  renderer.autoClear = false;
  // three.js rebuilds its GPU state after a restore; every view just has to draw again (the 2D canvases keep the old picture meanwhile)
  renderer.domElement.addEventListener('webglcontextrestored', () => markAll());
} catch (e) {
  renderer = null;
  errors.push('WebGL unavailable: ' + e.message);
}
let RW = 0, RH = 0;
const views = [];

function makeView(canvas, opts) {
  const box = canvas.parentElement;
  if (!canvas.hasAttribute('tabindex')) canvas.tabIndex = 0;      // keyboard: arrows turn, +/- zoom (attachControls)
  if (!renderer) {                                                   // keep the baked picture if there is one, say why nothing moves
    const p = document.createElement('p'); p.className = 'nogl';
    p.textContent = box.querySelector('.poster') ? '这个浏览器打不开 3D（WebGL），只能看这张静态图。' : '这个浏览器打不开 3D（WebGL），画不出来。';
    box.appendChild(p);
  }
  const v = {
    id: opts.id, group: opts.group, canvas, ctx: canvas.getContext('2d'), chip: box.querySelector('.chip'), labelsEl: box.querySelector('.labels'), poster: box.querySelector('.poster'),
    controls: opts.controls || null, placements: opts.placements, labelsFor: opts.labels || null, pad: opts.pad ?? 2, tight: !!opts.tight,
    scene: new THREE.Scene(), camera: new THREE.PerspectiveCamera(FOV, 1, 0.05, 400),
    presets: { ...PRESETS, ...(opts.presets || {}) },
    state: 'idle', preset: 'iso', yaw: 0, pitch: 0, zoom: 1, dirty: true, visible: true, built: {},
  };
  v.yaw = v.presets.iso.yaw; v.pitch = v.presets.iso.pitch;
  views.push(v);
  rebuild(v);
  attachControls(v);
  new ResizeObserver(() => sizeCanvas(v)).observe(canvas);
  new IntersectionObserver(es => { for (const e of es) { v.visible = e.isIntersecting; if (v.visible) v.dirty = true; } }).observe(canvas);
  sizeCanvas(v);
  return v;
}
function rebuild(v) {
  v.scene.clear();
  const lo = [Infinity, Infinity, Infinity], hi = [-Infinity, -Infinity, -Infinity];
  for (const st of ['idle', 'active']) {
    const pl = v.placements(st);
    const r = buildGroup([...pl, ...floorFor(pl, v.pad)]);
    r.group.visible = st === v.state;
    v.scene.add(r.group);
    v.built[st] = r;
    for (let k = 0; k < 3; k++) { lo[k] = Math.min(lo[k], r.lo[k]); hi[k] = Math.max(hi[k], r.hi[k]); }
  }
  if (!isFinite(lo[0])) { lo.splice(0, 3, 0, 0, 0); hi.splice(0, 3, 1, 1, 1); }
  v.lo = lo; v.hi = hi; v.center = new THREE.Vector3((lo[0] + hi[0]) / 2, (lo[1] + hi[1]) / 2, (lo[2] + hi[2]) / 2);
  if (v.labelsEl) {
    v.labelsEl.textContent = '';
    v.labels = (v.labelsFor ? v.labelsFor() : []).map(l => { const s = document.createElement('span'); s.textContent = l.text; v.labelsEl.appendChild(s); return { el: s, at: new THREE.Vector3(...l.at) }; });
  } else v.labels = [];
  // tight fit (rows of blocks): frame the corners of each block's own box (both states) plus some room above every label,
  // not the corners of the whole row's box, whose empty top-back corners would push the camera far away
  if (v.tight) {
    v.pts = [];
    for (const st of ['idle', 'active']) for (const p of v.placements(st)) {
      const b = boundsOf([p]);
      if (isFinite(b.lo[0])) for (let i = 0; i < 8; i++) v.pts.push(new THREE.Vector3(i & 1 ? b.hi[0] : b.lo[0], i & 2 ? b.hi[1] : b.lo[1], i & 4 ? b.hi[2] : b.lo[2]));
    }
    for (const l of v.labels) v.pts.push(l.at.clone().add(new THREE.Vector3(0, 0.3, 0)));
    if (!v.pts.length) v.tight = false;
  }
  v.dirty = true;
}
function sizeCanvas(v) {
  const r = v.canvas.getBoundingClientRect(), dpr = Math.min(2, window.devicePixelRatio || 1);
  const w = Math.max(1, Math.round(r.width * dpr)), h = Math.max(1, Math.round(r.height * dpr));
  if (v.canvas.width !== w || v.canvas.height !== h) { v.canvas.width = w; v.canvas.height = h; v.dirty = true; }
}
function camDir(yaw, pitch) { return new THREE.Vector3(Math.sin(yaw) * Math.cos(pitch), Math.sin(pitch), -Math.cos(yaw) * Math.cos(pitch)); }
/** Distance at which the view's bounding box fits the frame for this yaw/pitch/aspect (exact for a pinhole camera). */
function fitDistance(v, dir, aspect) {
  const f = dir.clone().negate(), right = new THREE.Vector3().crossVectors(f, UP).normalize(), up = new THREE.Vector3().crossVectors(right, f).normalize();
  const tv = Math.tan(FOV * Math.PI / 360) * 0.86, th = tv * aspect;
  let D = 0.5;
  for (let i = 0; i < 8; i++) {
    const c = new THREE.Vector3(i & 1 ? v.hi[0] : v.lo[0], i & 2 ? v.hi[1] : v.lo[1], i & 4 ? v.hi[2] : v.lo[2]).sub(v.center);
    const z = c.dot(dir);
    D = Math.max(D, z + Math.abs(c.dot(right)) / th, z + Math.abs(c.dot(up)) / tv);
  }
  return D;
}
/** Tight fit: distance for v.pts, with the look-at point moved to the middle of their picture (a few rounds; perspective). */
function tightFit(v, dir, aspect) {
  const f = dir.clone().negate(), right = new THREE.Vector3().crossVectors(f, UP).normalize(), up = new THREE.Vector3().crossVectors(right, f).normalize();
  const tv = Math.tan(FOV * Math.PI / 360) * 0.86, th = tv * aspect, c = v.center.clone(), q = new THREE.Vector3();
  let D = 0.5;
  for (let round = 0; round < 4; round++) {
    D = 0.5;
    for (const p of v.pts) { q.copy(p).sub(c); const z = q.dot(dir); D = Math.max(D, z + Math.abs(q.dot(right)) / th, z + Math.abs(q.dot(up)) / tv); }
    if (round === 3) break;
    let x0 = Infinity, x1 = -Infinity, y0 = Infinity, y1 = -Infinity;
    for (const p of v.pts) {
      q.copy(p).sub(c);
      const z = Math.max(1e-3, D - q.dot(dir)), sx = q.dot(right) / (z * th), sy = q.dot(up) / (z * tv);
      x0 = Math.min(x0, sx); x1 = Math.max(x1, sx); y0 = Math.min(y0, sy); y1 = Math.max(y1, sy);
    }
    c.addScaledVector(right, (x0 + x1) / 2 * D * th).addScaledVector(up, (y0 + y1) / 2 * D * tv);
  }
  return { D, center: c };
}
function renderView(v) {
  const w = v.canvas.width, h = v.canvas.height;
  if (w < 2 || h < 2 || !renderer || renderer.getContext().isContextLost()) return;
  if (w > RW || h > RH) { RW = Math.max(RW, w); RH = Math.max(RH, h); renderer.setSize(RW, RH, false); }
  const aspect = w / h, dir = camDir(v.yaw, v.pitch);
  v.camera.aspect = aspect; v.camera.updateProjectionMatrix();
  const fit = v.tight ? tightFit(v, dir, aspect) : { D: fitDistance(v, dir, aspect), center: v.center };
  v.camera.position.copy(fit.center).addScaledVector(dir, fit.D * v.zoom);
  v.camera.up.copy(UP); v.camera.lookAt(fit.center);
  renderer.setViewport(0, 0, w, h); renderer.setScissor(0, 0, w, h); renderer.setScissorTest(true);
  renderer.clear();
  renderer.render(v.scene, v.camera);
  v.ctx.clearRect(0, 0, w, h);
  v.ctx.drawImage(renderer.domElement, 0, RH - h, w, h, 0, 0, w, h);
  if (v.poster) { v.poster.remove(); v.poster = null; }           // the live picture takes over from the baked one
  if (v.labels.length) placeLabels(v);
  v.dirty = false;
}
/** Labels sit above their anchor (CSS: translate(-50%,-100%)). Neighbours that would overlap (narrow views, blocks side by side)
 *  are lifted above the label already placed there, with a thin leader line down to the anchor; all stay inside the view. */
function placeLabels(v) {
  const cw = v.canvas.clientWidth, ch = v.canvas.clientHeight, GAP = 3, EDGE = 4;
  const items = v.labels.map(l => { const p = l.at.clone().project(v.camera); return { l, show: p.z <= 1 && p.z >= -1, x: (p.x + 1) / 2 * cw, y: (1 - p.y) / 2 * ch }; });
  for (const it of items) if (it.show && it.l.el.hidden) it.l.el.hidden = false;
  for (const it of items) if (it.show) { it.w = it.l.el.offsetWidth; it.h = it.l.el.offsetHeight; }   // all reads before any write
  // the state chip in the top-left corner is in the way too: a label that would sit on it moves to its right
  const chip = v.chip && v.chip.offsetWidth ? { l: v.chip.offsetLeft, t: v.chip.offsetTop, r: v.chip.offsetLeft + v.chip.offsetWidth, b: v.chip.offsetTop + v.chip.offsetHeight } : null;
  const onChip = (x, y, it) => chip && x - it.w / 2 < chip.r + GAP && x + it.w / 2 > chip.l - GAP && y > chip.t - GAP && y - it.h < chip.b + GAP;
  const placed = [];
  for (const it of items.filter(i => i.show).sort((a, b) => a.x - b.x || b.y - a.y)) {
    let x = Math.max(it.w / 2 + EDGE, Math.min(cw - it.w / 2 - EDGE, it.x));
    let y = it.y;                                          // y = the label's bottom edge
    for (let guard = 0; guard < placed.length; guard++) {
      const hit = placed.find(r => Math.abs(r.x - x) < (r.w + it.w) / 2 + GAP && y > r.y - r.h - GAP && y - it.h < r.y + GAP);
      if (!hit) break;
      y = hit.y - hit.h - GAP;
    }
    y = Math.min(ch - EDGE, Math.max(it.h + EDGE, y));
    if (onChip(x, y, it)) x = Math.min(cw - it.w / 2 - EDGE, chip.r + GAP + it.w / 2);
    // no room left above (a row seen end-on in a short view): leave this label out rather than stack it on another
    if (placed.some(r => Math.abs(r.x - x) < (r.w + it.w) / 2 + GAP && y > r.y - r.h - GAP && y - it.h < r.y + GAP)) { it.show = false; continue; }
    placed.push({ x, y, w: it.w, h: it.h });
    it.pos = [x, y, Math.max(0, it.y - y)];
  }
  for (const it of items) {
    if (!it.show) { it.l.el.hidden = true; continue; }
    const s = it.l.el.style;
    s.left = it.pos[0].toFixed(1) + 'px'; s.top = it.pos[1].toFixed(1) + 'px';
    s.setProperty('--lead', it.pos[2] > 1 ? it.pos[2].toFixed(1) + 'px' : '0px');
  }
}
function markAll() { for (const v of views) v.dirty = true; }

function setPreset(v, name) {
  const p = v.presets[name]; if (!p) return;
  v.preset = name; v.yaw = p.yaw; v.pitch = p.pitch; v.zoom = 1; v.dirty = true; syncButtons();
}
function setState(v, st) {
  if (st !== 'idle' && st !== 'active') return;
  v.state = st;
  for (const s of ['idle', 'active']) v.built[s].group.visible = s === st;
  if (v.chip) { v.chip.textContent = st === 'active' ? '工作中' : '待机'; v.chip.dataset.on = st === 'active'; }
  v.dirty = true; syncButtons();
}
const clampPitch = p => Math.max(-0.35, Math.min(1.55, p)), clampZoom = z => Math.max(0.25, Math.min(4, z));
function attachControls(v) {
  const c = v.canvas, pts = new Map();
  let start = null;
  const snap = () => { const p = [...pts.values()]; return { yaw: v.yaw, pitch: v.pitch, zoom: v.zoom, p, d: p.length > 1 ? Math.hypot(p[0][0] - p[1][0], p[0][1] - p[1][1]) : 0, cy: p.length > 1 ? (p[0][1] + p[1][1]) / 2 : 0 }; };
  c.addEventListener('pointerdown', e => { pts.set(e.pointerId, [e.clientX, e.clientY]); try { c.setPointerCapture(e.pointerId); } catch { } start = snap(); });
  c.addEventListener('pointermove', e => {
    if (!pts.has(e.pointerId)) return;
    pts.set(e.pointerId, [e.clientX, e.clientY]);
    const p = [...pts.values()];
    if (p.length === 1 && start.p.length === 1) {
      v.yaw = start.yaw + (p[0][0] - start.p[0][0]) * 0.012;
      v.pitch = clampPitch(start.pitch + (p[0][1] - start.p[0][1]) * 0.009);
    } else if (p.length >= 2 && start.p.length >= 2) {
      v.zoom = clampZoom(start.zoom * start.d / Math.max(1, Math.hypot(p[0][0] - p[1][0], p[0][1] - p[1][1])));
      v.pitch = clampPitch(start.pitch + ((p[0][1] + p[1][1]) / 2 - start.cy) * 0.006);
    } else return;
    if (v.preset) { v.preset = null; syncButtons(); }
    v.dirty = true;
  });
  const end = e => { pts.delete(e.pointerId); start = snap(); };
  c.addEventListener('pointerup', end); c.addEventListener('pointercancel', end);
  c.addEventListener('wheel', e => { e.preventDefault(); v.zoom = clampZoom(v.zoom * Math.exp(e.deltaY * 0.0015)); v.dirty = true; }, { passive: false });
  c.addEventListener('dblclick', () => setPreset(v, v.preset || 'iso'));
  c.addEventListener('keydown', e => {
    if (e.altKey || e.ctrlKey || e.metaKey) return;
    const k = e.key;
    if (k === 'ArrowLeft' || k === 'ArrowRight') v.yaw += (k === 'ArrowRight' ? 1 : -1) * 0.18;
    else if (k === 'ArrowUp' || k === 'ArrowDown') v.pitch = clampPitch(v.pitch + (k === 'ArrowDown' ? 1 : -1) * 0.12);
    else if (k === '+' || k === '=' || k === '-' || k === '_') v.zoom = clampZoom(v.zoom * (k === '-' || k === '_' ? 1.15 : 1 / 1.15));
    else if (k === 'Home') { e.preventDefault(); setPreset(v, 'iso'); return; }
    else return;
    e.preventDefault();
    if (v.preset) { v.preset = null; syncButtons(); }
    v.dirty = true;
  });
}

// ---------------------------------------------------------------- the page's views
const specOf = (b, st) => b[st] || b.idle;
const refPl = (key, st, pos) => { const b = refBlock(key); return b ? [{ scope: REF_SCOPE, spec: specOf(b, st), pos }] : []; };
/** A design's blocks at origin; withUnder adds the meta.kitchen.under block (e.g. a stove) beneath each one. */
function designPl(d, st, origin = [0, 0, 0], withUnder = false) {
  const out = [];
  d.blocks.forEach((b, i) => {
    const o = b.pos || [i, 0, 0], pos = [origin[0] + o[0], origin[1] + o[1], origin[2] + o[2]];
    out.push({ scope: d.scope, spec: specOf(b, st), pos });
    if (withUnder && d.kitchen && d.kitchen.under) out.push(...refPl(d.kitchen.under, st, [pos[0], pos[1] - 1, pos[2]]));
  });
  return out;
}
function boundsOf(pl) {
  const lo = [Infinity, Infinity, Infinity], hi = [-Infinity, -Infinity, -Infinity];
  for (const p of pl) for (const q of bake(p.scope, p.spec)) for (const v of q.pos) for (let k = 0; k < 3; k++) { const w = v[k] / 16 + p.pos[k]; lo[k] = Math.min(lo[k], w); hi[k] = Math.max(hi[k], w); }
  return { lo, hi };
}
// kitchen, read left to right from the front: [stove + pot] [design] gap ... [stove + Casualness fryer].
// Facing a block's north side, east (+X) is on your LEFT, so the row runs towards -X.
function kitchenLayout(st) {
  const pl = [], labels = [];
  let x = 0;                                     // x of the next stove
  for (const d of designs) {
    pl.push(...refPl('fd_stove', st, [x, 0, 0]), ...refPl('fd_cooking_pot', st, [x, 1, 0]));
    if (x === 0) labels.push({ text: '炉灶 + 厨锅', at: [x + 0.5, 2.05, 0.5] });
    const bx = d.blocks.map((b, i) => (b.pos || [i, 0, 0])[0]), minX = Math.min(...bx), maxX = Math.max(...bx);
    const ox = x - 1 - maxX;                     // the design's blocks end right next to the stove
    const lift = d.kitchen && d.kitchen.under ? 1 : 0, mine = designPl(d, st, [ox, lift, 0], true);
    pl.push(...mine);
    labels.push({ text: d.tag || d.name, at: [ox + (minX + maxX + 1) / 2, boundsOf(mine).hi[1] + 0.12, 0.5] });
    x = ox + minX - 2;
  }
  if (!designs.length) { pl.push(...refPl('fd_stove', st, [0, 0, 0]), ...refPl('fd_cooking_pot', st, [0, 1, 0])); labels.push({ text: '炉灶 + 厨锅', at: [0.5, 2.05, 0.5] }); x = -2; }
  pl.push(...refPl('fd_stove', st, [x, 0, 0]), ...refPl('cd_fryer', st, [x, 1, 0]));
  labels.push({ text: '随性乐事炸锅', at: [x + 0.5, boundsOf(refPl('cd_fryer', st, [x, 1, 0])).hi[1] + 0.12, 0.5] });
  return { pl, labels };
}
// family scene (kitchen page), left to right from the front: [FD stove + cooking pot] (one empty column) [fryer] [oven] [prep],
// the family's blocks touching like a kitchen counter; a design with meta.kitchen.under stands on that block.
function familyLayout(fam, st) {
  const pl = [], labels = [];
  const pot = refPl('fd_cooking_pot', st, [0, 1, 0]);
  pl.push(...refPl('fd_stove', st, [0, 0, 0]), ...pot);
  labels.push({ text: '炉灶 + 厨锅', at: [0.5, (pot.length ? boundsOf(pot).hi[1] : 1) + 0.12, 0.5] });
  let edge = -1;                                 // lowest x taken so far; the gap column is x = -1
  for (const m of fam.members) {
    const d = designs.find(x => x.key === m.key);
    if (!d || !d.blocks.length) continue;
    const bx = d.blocks.map((b, i) => (b.pos || [i, 0, 0])[0]), minX = Math.min(...bx), maxX = Math.max(...bx);
    const ox = edge - 1 - maxX;
    const lift = d.kitchen && d.kitchen.under ? 1 : 0, mine = designPl(d, st, [ox, lift, 0], true);
    pl.push(...mine);
    labels.push({ text: m.label, at: [ox + (minX + maxX + 1) / 2, boundsOf(mine).hi[1] + 0.12, 0.5] });
    edge = ox + minX;
  }
  return { pl, labels };
}
/** Labels sit above the taller of the two states (a lid that opens when working must not cover its label). */
function labelsBothStates(layout) {
  const a = layout('idle').labels, b = layout('active').labels;
  return a.map((l, i) => ({ ...l, at: [l.at[0], Math.max(l.at[1], b[i] ? b[i].at[1] : -Infinity), l.at[2]] }));
}

const fmtPx = n => +n.toFixed(2);
function facts(d) {
  const rows = [], texs = new Map(), anims = [];
  for (const st of ['idle', 'active']) for (const b of d.blocks) {
    const m = model(d.scope, specOf(b, st).model);
    for (const q of bake(d.scope, specOf(b, st))) if (q.tex) texs.set(q.tex, d.scope.tex(q.tex));
    b['_n_' + st] = m.elements.length;
  }
  const idleN = d.blocks.reduce((s, b) => s + b._n_idle, 0), actN = d.blocks.reduce((s, b) => s + b._n_active, 0);
  rows.push(['元素', idleN === actN ? `${idleN} 个` : `待机 ${idleN} · 工作中 ${actN}`]);
  const r = boundsOf(designPl(d, 'idle'));
  if (isFinite(r.lo[0])) rows.push(['尺寸', [0, 2, 1].map(k => fmtPx((r.hi[k] - r.lo[k]) * 16)).join(' × ') + ' px（宽×深×高）']);
  const sizes = new Map();
  for (const [tid, key] of texs) {
    const a = DATA.assets[key];
    if (!a) { sizes.set('缺失', (sizes.get('缺失') || 0) + 1); continue; }
    const ai = mcAnimInfo(a.w, a.h, a.mcmeta), sz = `${ai.fw}×${ai.fh}`;
    sizes.set(sz, (sizes.get(sz) || 0) + 1);
    if (ai.animated) anims.push(`${mcSplitId(tid).path.split('/').pop()} ${ai.frames.length} 帧 × ${ai.frames[0].time} tick${ai.interpolate ? '（插值）' : ''}`);
  }
  rows.push(['贴图', [...sizes].map(([s, n]) => `${n} 张 ${s}`).join('，') || '无']);
  rows.push(['动画贴图', anims.join('；') || '无']);
  return rows;
}

function init() {
  // design cards
  for (const d of designs) {
    const card = document.querySelector(`.card[data-key="${CSS.escape(d.key)}"]`);
    if (!card) continue;
    const v = makeView(card.querySelector('canvas'), { id: d.key, group: 'design', placements: st => designPl(d, st), pad: 2 });
    v.controls = card;
    const dl = card.querySelector('[data-facts]');
    if (dl) for (const [k, val] of facts(d)) { const dt = document.createElement('dt'), dd = document.createElement('dd'); dt.textContent = k; dd.textContent = val; dl.append(dt, dd); }
    const errs = errors.filter(e => e.startsWith(`[design/${d.key}]`) || e.includes('design/' + d.key + '|'));
    if (errs.length) { const p = document.createElement('p'); p.className = 'card-err'; p.textContent = '模型有问题：' + errs.join('；'); card.querySelector('.body').prepend(p); }
  }
  // family scenes (kitchen page)
  for (const el of $$('[data-scene]')) {
    const fam = (DATA.families || []).find(f => f.id === el.dataset.scene), c = el.querySelector('canvas');
    if (!fam || !c) continue;
    const v = makeView(c, { id: 'family-' + fam.id, group: 'family', placements: st => familyLayout(fam, st).pl, labels: () => labelsBothStates(st => familyLayout(fam, st)), pad: 1, tight: true,
      // a row seen exactly end-on is one block with the rest hidden behind it: the scene's 侧面 looks along the row at a slant instead
      presets: { iso: { yaw: -0.42, pitch: 0.45 }, side: { yaw: -1.15, pitch: 0.42 } } });
    v.controls = el;
  }
  // kitchen strip (preview page)
  const kc = document.getElementById('v-kitchen');
  if (kc) {
    // a long row reads better from a shallower three-quarter angle
    const kv = makeView(kc, { id: 'kitchen', group: 'kitchen', placements: st => kitchenLayout(st).pl, labels: () => kitchenLayout('idle').labels, pad: 1, presets: { iso: { yaw: -0.42, pitch: 0.45 } } });
    kv.controls = document.querySelector('[data-key="kitchen"]');
    const names = ['炉灶 + 厨锅', ...designs.map(d => `${d.tag ? d.tag + ' ' : ''}${d.name}`), '随性乐事炸锅（放在炉灶上）'];
    const lg = document.getElementById('kitchen-legend');
    if (lg) lg.textContent = designs.length ? '从左到右：' + designs.map(d => `炉灶 + 厨锅、${d.tag ? d.tag + ' ' : ''}${d.name}`).join('，') + '，最后是随性乐事炸锅（放在炉灶上）。' : '还没有方案，先看现有的：' + names.join('、') + '。';
  }
  // reference strip
  const refSec = document.querySelector('[data-key="refs"]');
  for (const fig of $$('[data-ref]')) {
    const key = fig.dataset.ref;
    const v = makeView(fig.querySelector('canvas'), { id: key, group: 'refs', placements: st => refPl(key, st, [0, 0, 0]), pad: 1 });
    v.controls = refSec;
  }
  // buttons
  for (const sec of new Set(views.map(v => v.controls).filter(Boolean))) {
    const mine = views.filter(v => v.controls === sec);
    for (const b of $$('[data-state]', sec)) if (!b.closest('[data-ref]')) b.addEventListener('click', () => mine.forEach(v => setState(v, b.dataset.state)));
    for (const b of $$('[data-preset]', sec)) b.addEventListener('click', () => mine.forEach(v => setPreset(v, b.dataset.preset)));
  }
  for (const b of $$('[data-floor]')) b.addEventListener('click', () => setFloor(b.dataset.floor));
  for (const b of $$('[data-all-state]')) b.addEventListener('click', () => views.forEach(v => setState(v, b.dataset.allState)));
  const anim = document.getElementById('t-anim');
  if (anim) anim.addEventListener('click', () => setPlaying(!playing));
  const bi = document.getElementById('build-info');
  if (bi) bi.textContent = `生成于 ${DATA.built} · ${designs.length} 个方案 · 参照来自 ${Object.values(DATA.refs.sources).slice(0, 2).join('、')}`;
  syncButtons();
}
function syncButtons() {
  for (const sec of new Set(views.map(v => v.controls).filter(Boolean))) {
    const v = views.find(x => x.controls === sec);
    for (const b of $$('[data-state]', sec)) b.setAttribute('aria-pressed', String(b.dataset.state === v.state));
    for (const b of $$('[data-preset]', sec)) b.setAttribute('aria-pressed', String(b.dataset.preset === v.preset));
  }
  // the global switch shows a state only while every view is in it (mixed = neither pressed)
  for (const b of $$('[data-all-state]')) b.setAttribute('aria-pressed', String(views.length > 0 && views.every(v => v.state === b.dataset.allState)));
  for (const b of $$('[data-floor]')) b.setAttribute('aria-pressed', String(b.dataset.floor === floorType));
  const anim = document.getElementById('t-anim');
  if (anim) { anim.setAttribute('aria-pressed', String(playing)); anim.textContent = playing ? '播放中' : '已暂停'; }
}
function setFloor(f) { if (!DATA.refs.floors[f] || f === floorType) return; floorType = f; for (const v of views) rebuild(v); syncButtons(); }

// ---------------------------------------------------------------- animation clock (20 ticks per second)
let tick = 0, playing = !reduceMotion, t0 = performance.now(), tickBase = 0;
// reduced motion: animated textures start paused (the 动画 button still plays them); follow the setting if it changes later
try { matchMedia('(prefers-reduced-motion: reduce)').addEventListener('change', e => setPlaying(!e.matches)); } catch { }
function setPlaying(p) { if (p === playing) return; playing = p; t0 = performance.now(); tickBase = tick; syncButtons(); }
function setTick(t) {
  tick = t;
  const changed = new Set();
  for (const e of texEntries.values()) if (e.info.animated && applyFrame(e, t)) changed.add(e.key);
  if (changed.size) for (const v of views) { const a = v.built[v.state] && v.built[v.state].animKeys; if (a && [...a].some(k => changed.has(k))) v.dirty = true; }
}
function frame(now) {
  if (playing) { const t = tickBase + Math.floor((now - t0) / 50); if (t !== tick) setTick(t); }
  for (const v of views) if (v.dirty && v.visible) renderView(v);
  requestAnimationFrame(frame);
}

// ---------------------------------------------------------------- review hooks (shot.mjs plans use these)
// key: a view id (design key, 'family-A', 'kitchen', a reference key such as 'fd_stove'), a group ('design', 'family', 'refs'), or '*'
const viewsFor = key => key == null || key === '*' ? views : views.filter(v => v.id === key || v.group === key || (KITCHEN && key === 'kitchen' && v.group === 'family'));
function renderAll() { for (const v of views) { sizeCanvas(v); if (v.canvas.offsetParent !== null) renderView(v); } return true; }
window.__keys = () => designs.map(d => d.key);
window.__scenes = () => views.filter(v => v.group === 'family' || v.group === 'kitchen').map(v => v.id);
/** Every view's current picture ({ id: data URL }); build.mjs --posters bakes these into the page. */
window.__posters = (type = 'image/webp', quality = 0.9) => { renderAll(); return Object.fromEntries(views.filter(v => v.canvas.width > 2).map(v => [v.id, v.canvas.toDataURL(type, quality)])); };
window.__view = (name, key) => { if (!PRESETS[name]) return false; viewsFor(key).forEach(v => setPreset(v, name)); return renderAll(); };
window.__cam = (yaw, pitch, zoom = 1, key) => { viewsFor(key).forEach(v => { v.preset = null; v.yaw = yaw; v.pitch = clampPitch(pitch); v.zoom = clampZoom(zoom); }); syncButtons(); return renderAll(); };
window.__state = (key, st) => { viewsFor(key).forEach(v => setState(v, st)); return renderAll(); };
window.__floor = f => { setFloor(f); return renderAll(); };
window.__anim = t => { if (t == null) setPlaying(true); else { setPlaying(false); setTick(t | 0); tickBase = tick; } renderAll(); return tick; };
/** Show only these cards/sections/scenes (anything with data-key: design keys, 'kitchen', 'refs', 'family-A', a station such as
 *  'oven'), big, scrolled to top; whatever contains or sits inside a chosen element stays too. __focus() restores the page. */
window.__focus = keys => {
  const show = keys && keys.length ? new Set(keys) : null;
  document.body.toggleAttribute('data-focus', !!show);
  const els = $$('[data-key]'), on = show ? els.filter(el => show.has(el.dataset.key)) : [];
  for (const el of els) el.hidden = !!show && !on.some(o => o === el || o.contains(el) || el.contains(o));
  for (const ds of $$('.designs')) ds.hidden = !!show && !$$('.card[data-key]', ds).some(c => !c.hidden);
  window.scrollTo(0, 0);
  return renderAll();
};
window.__render = renderAll;
window.__ready = false;

init();
Promise.all(pending).then(() => { setTick(tick); renderAll(); window.__ready = true; requestAnimationFrame(frame); });
