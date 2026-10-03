import * as THREE from 'https://cdn.jsdelivr.net/npm/three@0.160.0/build/three.module.js';
// CURRENT, DESIGNS, playerBoxes are inlined above by build.mjs

// ---------- MC ModelPart.Cube, verbatim face/UV layout ----------
function polys(b) {
  const g = b.grow || 0;
  const x1 = b.x - g, y1 = b.y - g, z1 = b.z - g, x2 = b.x + b.w + g, y2 = b.y + b.h + g, z2 = b.z + b.d + g;
  const V = [[x1, y1, z1], [x2, y1, z1], [x2, y2, z1], [x1, y2, z1], [x1, y1, z2], [x2, y1, z2], [x2, y2, z2], [x1, y2, z2]];
  const { u, v, w, h, d } = b;
  const f4 = u, f5 = u + d, f6 = u + d + w, f7 = u + d + w + w, f8 = u + d + w + d, f9 = u + d + w + d + w, f10 = v, f11 = v + d, f12 = v + d + h;
  // b.faceUV (GeckoLib per-face UV, set by packFaces) overrides the vanilla box-UV net: face -> [u1, v1, u2, v2] in texture units
  const P = (ids, u1, v1, u2, v2, face) => { const o = b.faceUV && b.faceUV[face]; if (o) [u1, v1, u2, v2] = o; return { face, verts: ids.map(i => V[i]), uv: [[u2, v1], [u1, v1], [u1, v2], [u2, v2]] }; };
  return [P([5, 4, 0, 1], f5, f10, f6, f11, 'top'), P([2, 3, 7, 6], f6, f11, f7, f10, 'bottom'), P([0, 4, 7, 3], f4, f11, f5, f12, 'right'),
    P([1, 0, 3, 2], f5, f11, f6, f12, 'front'), P([5, 1, 2, 6], f6, f11, f8, f12, 'left'), P([4, 5, 6, 7], f8, f11, f9, f12, 'back')];
}

function geometryFor(boxes, tw, th) {
  const pos = [], nor = [], uv = [];
  const cv = p => new THREE.Vector3(p[0], -p[1], -p[2]);
  const D = Math.PI / 180;
  for (const b of boxes) for (const q of polys(b)) {
    let verts = q.verts;
    if (b.rot) {
      // GeckoLib-style cube rotation about a pivot, MC axes, applied Z then Y then X (like PartPose)
      const pv = b.pivot || [b.x + b.w / 2, b.y + b.h / 2, b.z + b.d / 2];
      const m = new THREE.Matrix4().makeRotationFromEuler(new THREE.Euler(b.rot[0] * D, b.rot[1] * D, b.rot[2] * D, 'ZYX'));
      verts = verts.map(v => { const t = new THREE.Vector3(v[0] - pv[0], v[1] - pv[1], v[2] - pv[2]).applyMatrix4(m); return [t.x + pv[0], t.y + pv[1], t.z + pv[2]]; });
    }
    const vs = verts.map(cv);
    const n = new THREE.Vector3().subVectors(vs[1], vs[0]).cross(new THREE.Vector3().subVectors(vs[2], vs[0]));
    if (n.lengthSq() < 1e-12) continue;
    n.normalize();
    for (const i of [0, 1, 2, 0, 2, 3]) { pos.push(vs[i].x, vs[i].y, vs[i].z); nor.push(n.x, n.y, n.z); uv.push(q.uv[i][0] / tw, q.uv[i][1] / th); }
  }
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
  g.setAttribute('normal', new THREE.Float32BufferAttribute(nor, 3));
  g.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2));
  return g;
}

// ---------- atlas packing + field painting ----------
const PART_OFF = { body: [0, 0, 0], head: [0, 0, 0], right_arm: [-5, 2, 0], left_arm: [5, 2, 0], right_leg: [-1.9, 12, 0], left_leg: [1.9, 12, 0] };

// GeckoLib per-face UV: every face gets its own rect starting on a whole texel, so thin faces never share texels
// with their neighbours (the vanilla box-UV net bleeds side colours onto fronts when a size is not a multiple of 1/S).
function packFaces(boxes, S) {
  const faces = [];
  for (const b of boxes) {
    const dims = { top: [b.w, b.d], bottom: [b.w, b.d], right: [b.d, b.h], left: [b.d, b.h], front: [b.w, b.h], back: [b.w, b.h] };
    b.faceUV = {};
    for (const [f, [fw, fh]] of Object.entries(dims)) faces.push({ b, f, fw, fh, pw: Math.max(1, Math.ceil(fw * S - 1e-6)), ph: Math.max(1, Math.ceil(fh * S - 1e-6)) });
  }
  faces.sort((p, q) => q.ph - p.ph || q.pw - p.pw);
  let W = 64 * S;
  for (;;) {
    let x = 0, y = 0, row = 0, ok = true;
    for (const F of faces) {
      if (F.pw > W) { ok = false; break; }
      if (x + F.pw > W) { x = 0; y += row; row = 0; }
      F.x = x; F.y = y; x += F.pw; row = Math.max(row, F.ph);
    }
    const used = y + row;
    if (ok && used <= W) {
      let H = 16 * S; while (H < used) H *= 2;
      for (const F of faces) F.b.faceUV[F.f] = [F.x / S, F.y / S, F.x / S + F.fw, F.y / S + F.fh];
      return { tw: W / S, th: H / S };
    }
    W *= 2;
  }
}
function packDesign(design) {
  const boxes = design.boxes.map(b => ({ ...b }));
  if (design.S) { const r = packFaces(boxes, design.S); return { boxes, ...r }; }
  let tw = 64;
  for (;;) {
    let x = 0, y = 0, row = 0, ok = true;
    for (const b of boxes) {
      const W = Math.ceil(2 * (b.d + b.w) - 1e-6), H = Math.ceil(b.d + b.h - 1e-6);
      if (W > tw) { ok = false; break; }
      if (x + W > tw) { x = 0; y += row; row = 0; }
      b.u = x; b.v = y; x += W; row = Math.max(row, H);
    }
    const used = y + row;
    if (ok && used <= tw) { let th = 16; while (th < used) th *= 2; return { boxes, tw, th }; }
    tw *= 2;
  }
}

function paintAtlas(boxes, tw, th, S, cell) {
  const cv = document.createElement('canvas');
  cv.width = tw * S; cv.height = th * S;
  const ctx = cv.getContext('2d');
  const img = ctx.createImageData(cv.width, cv.height);
  for (const b of boxes) {
    const off = PART_OFF[b.part] || [0, 0, 0];
    for (const q of polys(b)) {
      const [u2, v1] = q.uv[0], [u1] = q.uv[1], [, v2] = q.uv[2];
      if (u1 === u2 || v1 === v2) continue;
      const V0 = q.verts[0], V1 = q.verts[1], V2 = q.verts[2];
      const du = V0.map((c, i) => c - V1[i]), dv = V2.map((c, i) => c - V1[i]);
      const fw = Math.hypot(...du), fh = Math.hypot(...dv);
      const i0 = Math.floor(Math.min(u1, u2) * S + 1e-6), i1 = Math.ceil(Math.max(u1, u2) * S - 1e-6);
      const j0 = Math.floor(Math.min(v1, v2) * S + 1e-6), j1 = Math.ceil(Math.max(v1, v2) * S - 1e-6);
      for (let j = j0; j < j1; j++) for (let i = i0; i < i1; i++) {
        const a = ((i + 0.5) / S - u1) / (u2 - u1), bb = ((j + 0.5) / S - v1) / (v2 - v1);
        if (a < 0 || a > 1 || bb < 0 || bb > 1) continue;
        let eu = a * fw, ev = bb * fh;
        if (cell) { eu = Math.min(fw, (Math.floor(eu / cell) + 0.5) * cell); ev = Math.min(fh, (Math.floor(ev / cell) + 0.5) * cell); }
        const ea = fw ? eu / fw : 0, eb = fh ? ev / fh : 0;
        const p = [0, 1, 2].map(k => V1[k] + ea * du[k] + eb * dv[k] + off[k]);
        const c = { p, face: q.face, eu, ev, fw, fh, ex: Math.min(eu, fw - eu, ev, fh - ev), px: cell || 1 / S, box: b };
        const col = b.mat(c);
        const o = (j * cv.width + i) * 4;
        if (!col) { img.data[o + 3] = 0; continue; }
        img.data[o] = Math.max(0, Math.min(255, col[0])); img.data[o + 1] = Math.max(0, Math.min(255, col[1])); img.data[o + 2] = Math.max(0, Math.min(255, col[2])); img.data[o + 3] = 255;
      }
    }
  }
  ctx.putImageData(img, 0, 0);
  return cv;
}

// ---------- audit (same rule as the offline audit of the 49 shipped models) ----------
const ENV = { body: [-4.25, -0.25, -2.25, 4.25, 12.25, 2.25], right_arm: [-3.25, -2.25, -2.25, 1.25, 10.25, 2.25], left_arm: [-1.25, -2.25, -2.25, 3.25, 10.25, 2.25] };
function audit(boxes) {
  let area = 0, hid = 0, thin = 0, off = 0;
  for (const b of boxes) {
    const g = b.grow || 0, bb = [b.x - g, b.y - g, b.z - g, b.x + b.w + g, b.y + b.h + g, b.z + b.d + g];
    const a = 2 * (b.w * b.h + b.w * b.d + b.h * b.d); area += a;
    const e = ENV[b.part];
    if (e && !b.rot && bb[0] >= e[0] - 1e-4 && bb[1] >= e[1] - 1e-4 && bb[2] >= e[2] - 1e-4 && bb[3] <= e[3] + 1e-4 && bb[4] <= e[4] + 1e-4 && bb[5] <= e[5] + 1e-4) hid += a;
    if (Math.min(b.w, b.h, b.d) < 0.5 - 1e-6) thin++;
    if (!b.rot && [b.w, b.h, b.d].some(s => Math.abs(s * 4 - Math.round(s * 4)) > 1e-3)) off++;
  }
  return { n: boxes.length, hiddenPct: Math.round(100 * hid / area), thin, off };
}

function flattenCurrent(model) {
  const out = [];
  const walk = (node, part, rot) => {
    for (const b of node.boxes) out.push({ part, rot, u: b[0], v: b[1], x: b[2], y: b[3], z: b[4], w: b[5], h: b[6], d: b[7], grow: b[8] });
    for (const c of node.children) walk(c, part || c.name, rot || c.pose.slice(3).some(r => r));
  };
  walk(model.root, null, false);
  return out;
}

// ---------- rendering ----------
const L0 = new THREE.Vector3(0.2, 1, -0.7).normalize(), L1 = new THREE.Vector3(-0.2, 1, 0.7).normalize();
function mcMaterial(tex) {
  return new THREE.ShaderMaterial({
    uniforms: { map: { value: tex }, L0: { value: L0 }, L1: { value: L1 } },
    vertexShader: 'varying vec2 vUv; varying vec3 vN; void main(){ vUv=uv; vN=normalize(mat3(modelMatrix)*normal); gl_Position=projectionMatrix*modelViewMatrix*vec4(position,1.0); }',
    fragmentShader: 'uniform sampler2D map; uniform vec3 L0; uniform vec3 L1; varying vec2 vUv; varying vec3 vN; void main(){ vec4 t=texture2D(map,vUv); if(t.a<0.1) discard; vec3 n=normalize(vN); if(!gl_FrontFacing) n=-n; float l=min(1.0,0.4+0.6*max(0.0,dot(n,L0))+0.6*max(0.0,dot(n,L1))); gl_FragColor=vec4(t.rgb*l,1.0); }',
    side: THREE.DoubleSide,
  });
}
function texFrom(src) {
  const t = new THREE.Texture(src);
  t.magFilter = t.minFilter = THREE.NearestFilter; t.generateMipmaps = false; t.flipY = false; t.colorSpace = THREE.NoColorSpace; t.needsUpdate = true;
  return t;
}

function buildRig(parts, tex) {
  // parts: {name: {pose:[x,y,z], boxes, children?}} -> group per MC part
  const root = new THREE.Group(), map = {};
  const mat = mcMaterial(tex);
  for (const [name, p] of Object.entries(parts)) {
    const g = new THREE.Group();
    g.position.set(p.pose[0], -p.pose[1], -p.pose[2]);
    g.rotation.order = 'ZYX';
    g.rotation.set(p.pose[3] || 0, -(p.pose[4] || 0), -(p.pose[5] || 0));
    if (p.boxes.length) g.add(new THREE.Mesh(geometryFor(p.boxes, p.tw, p.th), mat));
    for (const ch of p.children || []) g.add(buildRig({ [ch.name]: ch }, tex).root);
    root.add(g); map[name] = g;
  }
  return { root, map };
}

function currentParts(model, tw, th) {
  const conv = n => ({ pose: n.pose, tw, th, boxes: n.boxes.map(b => ({ u: b[0], v: b[1], x: b[2], y: b[3], z: b[4], w: b[5], h: b[6], d: b[7], grow: b[8] })), children: n.children.map(c => ({ name: c.name, ...conv(c) })) });
  const parts = {};
  for (const c of model.root.children) parts[c.name] = conv(c);
  return parts;
}
function partsFromFlat(boxes, tw, th, slim) {
  const parts = {};
  for (const [name, off] of Object.entries(PART_OFF)) parts[name] = { pose: [off[0], name.endsWith('arm') && slim ? off[1] + 0.5 : off[1], off[2]], tw, th, boxes: [] };
  for (const b of boxes) parts[b.part].boxes.push(b);
  return parts;
}

// ---------- variants ----------
const cache = {};
function variant(key, which) {
  const id = key + which;
  if (cache[id]) return cache[id];
  let v;
  if (which === 'now') {
    const cur = CURRENT[key], [tw, th] = cur.model.tex;
    const img = new Image(); img.src = cur.tex;
    const tex = texFrom(img); img.onload = () => { tex.needsUpdate = true; };
    v = { tex, parts: currentParts(cur.model, tw, th), atlas: cur.tex, texPx: `${tw}×${th}`, stats: audit(flattenCurrent(cur.model)) };
  } else {
    const d = ARMORS[key](which), pk = packDesign(d);
    const cv = paintAtlas(pk.boxes, pk.tw, pk.th, d.S, d.cell);
    v = { tex: texFrom(cv), flat: pk.boxes, tw: pk.tw, th: pk.th, atlas: cv.toDataURL(), texPx: `${pk.tw * d.S}×${pk.th * d.S}`, stats: audit(pk.boxes) };
  }
  return (cache[id] = v);
}

const skinCache = {};
function playerRig(slim) {
  if (!skinCache[slim]) {
    const boxes = playerBoxes(slim);
    skinCache[slim] = { boxes, tex: texFrom(paintAtlas(boxes, 64, 64, 1, 0)) };
  }
  const s = skinCache[slim];
  return buildRig(partsFromFlat(s.boxes, 64, 64, slim), s.tex);
}

// ---------- panels ----------
const state = { armor: (new URLSearchParams(location.search).get('armor') in ARMORS) ? new URLSearchParams(location.search).get('armor') : 'jaypc', yaw: -0.5, pitch: 0.12, dist: 64, walk: false, jacket: true, slim: false, auto: true, t: 0 };
const panels = [...document.querySelectorAll('[data-variant]')].map(el => {
  const canvas = el.querySelector('canvas');
  const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: true, preserveDrawingBuffer: true });
  renderer.setPixelRatio(Math.min(2, window.devicePixelRatio || 1));
  renderer.outputColorSpace = THREE.LinearSRGBColorSpace;
  const scene = new THREE.Scene();
  const camera = new THREE.PerspectiveCamera(26, 1, 1, 1000);
  const holder = new THREE.Group(); scene.add(holder);
  return { el, which: el.dataset.variant, canvas, renderer, scene, camera, holder, rigs: null };
});

function rebuild() {
  for (const P of panels) {
    P.holder.clear();
    // the 'now' card shows the old Java model from current_embed.js; hidden for armors without one (or builds without that file)
    P.off = P.which === 'now' && !CURRENT[state.armor];
    if (P.off) { P.rigs = null; continue; }
    const player = playerRig(state.slim);
    for (const [n, g] of Object.entries(player.map)) if (/jacket|sleeve/.test(n)) g.visible = state.jacket;
    const v = variant(state.armor, P.which);
    const armor = buildRig(v.parts || partsFromFlat(v.flat, v.tw, v.th, state.slim), v.tex);
    if (v.parts && state.slim) for (const n of ['right_arm', 'left_arm']) armor.map[n] && (armor.map[n].position.y -= 0.5);
    P.holder.add(player.root, armor.root);
    P.rigs = [player, armor];
    P.el.querySelector('.atlas img').src = v.atlas;
    const s = v.stats;
    P.el.querySelector('[data-k=n]').textContent = s.n;
    P.el.querySelector('[data-k=tex]').textContent = v.texPx;
    P.el.querySelector('[data-k=hid]').textContent = s.hiddenPct + '%';
    P.el.querySelector('[data-k=thin]').textContent = s.thin;
    P.el.querySelector('[data-k=off]').textContent = s.off;
    P.el.querySelector('[data-k=hid]').dataset.bad = s.hiddenPct > 0;
    P.el.querySelector('[data-k=off]').dataset.bad = s.off > 0;
  }
  applyVisibility();
  applyJacket();
  resize();
}
// a card is shown unless it has nothing to show (P.off) or __focus() left it out; one visible card gets the whole row
function applyVisibility() {
  for (const P of panels) P.el.hidden = !!P.off || (!!state.focus && !state.focus.includes(P.which));
  document.querySelector('.stage').classList.toggle('solo', panels.filter(P => !P.el.hidden).length < 2);
}
function applyJacket() {
  // skin overlay boxes share part groups with base boxes, so toggle by re-painting alpha: simpler to swap skin texture variants
  for (const P of panels) {
    if (!P.rigs) continue;
    const player = P.rigs[0];
    player.root.traverse(o => { if (o.isMesh) o.material.uniforms.map.value = skinTexture(state.slim, state.jacket); });
  }
}
const skinTexCache = {};
function skinTexture(slim, jacket) {
  const k = slim + '' + jacket;
  if (!skinTexCache[k]) {
    const boxes = playerBoxes(slim).map(b => (b.grow > 0 && !jacket ? { ...b, mat: () => null } : b));
    skinTexCache[k] = texFrom(paintAtlas(boxes, 64, 64, 1, 0));
  }
  return skinTexCache[k];
}

function resize() {
  for (const P of panels) {
    const r = P.canvas.getBoundingClientRect();
    P.renderer.setSize(r.width, r.height, false);
    P.camera.aspect = r.width / Math.max(1, r.height); P.camera.updateProjectionMatrix();
  }
}
new ResizeObserver(resize).observe(document.querySelector('.stage'));

const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches;
let last = performance.now();
function frame(now) {
  const dt = Math.min(0.05, (now - last) / 1000); last = now;
  renderAll(dt);
  requestAnimationFrame(frame);
}
// Renders synchronously; also exposed as window.__render() for screenshots of background tabs (rAF pauses there).
function renderAll(dt, keepArms = false) {
  if (state.auto && !reduce) state.yaw += dt * 0.35;
  if (state.walk) state.t += dt * 20 * 0.6;
  const amt = state.walk ? 0.8 : 0, ls = state.t;
  const ra = Math.cos(ls * 0.6662 + Math.PI) * amt, la = Math.cos(ls * 0.6662) * amt;
  const rl = Math.cos(ls * 0.6662) * 1.4 * amt, ll = Math.cos(ls * 0.6662 + Math.PI) * 1.4 * amt;
  for (const P of panels) {
    if (!P.rigs) continue;
    if (!keepArms) for (const rig of P.rigs) {
      if (rig.map.right_arm) rig.map.right_arm.rotation.x = ra;
      if (rig.map.left_arm) rig.map.left_arm.rotation.x = la;
      if (rig.map.right_leg) rig.map.right_leg.rotation.x = rl;
      if (rig.map.left_leg) rig.map.left_leg.rotation.x = ll;
    }
    P.holder.rotation.y = state.yaw;
    const ty = -6;
    P.camera.position.set(0, ty + Math.sin(state.pitch) * state.dist, Math.cos(state.pitch) * state.dist);
    P.camera.lookAt(0, ty, 0);
    P.renderer.render(P.scene, P.camera);
  }
}
window.__render = () => { resize(); renderAll(0); return true; };
// Review helper: show only some cards (e.g. __focus(['M']) or __focus(['now','M'])), large, then render. __focus() restores all.
window.__focus = (list) => { const st = document.querySelector('.stage'); state.focus = list || null; applyVisibility(); st.style.gridTemplateColumns = list ? 'repeat(' + list.length + ', minmax(0, 620px))' : ''; st.scrollIntoView(); resize(); renderAll(0); return true; };
// Free camera for reviews: yaw in radians (0 = front, -1.5708 = wearer's right side), pitch, distance; optional arm pose.
window.__cam = (yaw, pitch = 0.12, dist = 64, arms = null) => { state.auto = false; state.yaw = yaw; state.pitch = pitch; state.dist = dist; if (arms != null) { for (const P of panels) for (const rig of P.rigs || []) { if (rig.map.right_arm) rig.map.right_arm.rotation.x = arms; if (rig.map.left_arm) rig.map.left_arm.rotation.x = arms; } } syncUI(); resize(); renderAll(0, arms != null); return true; };
window.__view = (name, opts = {}) => { const b = document.getElementById('v-' + name); if (b) b.click(); if (opts.walk != null) { state.walk = !!opts.walk; } if (opts.jacket != null) { state.jacket = !!opts.jacket; applyJacket(); } if (opts.slim != null && opts.slim !== state.slim) { state.slim = !!opts.slim; rebuild(); } syncUI(); resize(); renderAll(0); return true; };

// ---------- controls ----------
let drag = null;
for (const P of panels) {
  P.canvas.addEventListener('pointerdown', e => { drag = { x: e.clientX, y: e.clientY, yaw: state.yaw, pitch: state.pitch }; state.auto = false; syncUI(); P.canvas.setPointerCapture(e.pointerId); });
  P.canvas.addEventListener('pointermove', e => { if (!drag) return; state.yaw = drag.yaw + (e.clientX - drag.x) * 0.01; state.pitch = Math.max(-0.6, Math.min(1.0, drag.pitch + (e.clientY - drag.y) * 0.006)); });
  P.canvas.addEventListener('pointerup', () => { drag = null; });
  P.canvas.addEventListener('wheel', e => { e.preventDefault(); state.dist = Math.max(26, Math.min(110, state.dist * (1 + Math.sign(e.deltaY) * 0.08))); }, { passive: false });
}
const $ = s => document.querySelector(s);
function syncUI() {
  document.querySelectorAll('[data-armor]').forEach(b => b.setAttribute('aria-pressed', b.dataset.armor === state.armor));
  document.querySelectorAll('[data-for]').forEach(el => { el.hidden = el.dataset.for !== state.armor; });
  $('#t-auto').checked = state.auto; $('#t-walk').checked = state.walk; $('#t-jacket').checked = state.jacket; $('#t-slim').checked = state.slim;
}
document.querySelectorAll('[data-armor]').forEach(b => b.addEventListener('click', () => { state.armor = b.dataset.armor; syncUI(); rebuild(); }));
document.querySelectorAll('[data-view]').forEach(b => b.addEventListener('click', () => {
  state.auto = false; state.yaw = +b.dataset.view; state.pitch = b.dataset.pitch ? +b.dataset.pitch : 0.12; state.dist = b.dataset.dist ? +b.dataset.dist : 64; syncUI();
}));
$('#t-auto').addEventListener('change', e => { state.auto = e.target.checked; });
$('#t-walk').addEventListener('change', e => { state.walk = e.target.checked; });
$('#t-jacket').addEventListener('change', e => { state.jacket = e.target.checked; applyJacket(); });
$('#t-slim').addEventListener('change', e => { state.slim = e.target.checked; rebuild(); });

syncUI(); rebuild(); resize(); requestAnimationFrame(frame);
