// Headless sanity check for one armor design:  node check.mjs <key>
// Builds modes A / M / B, paints every texel (same face/UV math as viewer.js), audits clearance vs the skin layer.
import fs from 'node:fs';
const here = new URL('./', import.meta.url);
const key = process.argv[2];
if (!key) { console.error('usage: node check.mjs <key>'); process.exit(2); }
const r = f => fs.readFileSync(new URL(f, here), 'utf8').replace(/^﻿/, '');
const extra = fs.existsSync(new URL(`./armors/${key}.js`, here)) ? r(`./armors/${key}.js`) : '';
const tmp = new URL(`./.check_${key}.mjs`, here);
fs.writeFileSync(tmp, r('./designs.js') + '\n' + extra + '\n');
const mod = await import(tmp.href + '?t=' + process.hrtime.bigint());
fs.unlinkSync(tmp);
const ARMORS = mod.ARMORS;
if (typeof ARMORS[key] !== 'function') { console.log(JSON.stringify({ ok: false, error: `ARMORS.${key} is not registered` })); process.exit(1); }

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
const PART_OFF = { body: [0, 0, 0], right_arm: [-5, 2, 0], left_arm: [5, 2, 0] };
function pack(boxes) {
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
    if (ok && used <= tw) { let th = 16; while (th < used) th *= 2; return { tw, th }; }
    tw *= 2;
  }
}
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
const ENV = { body: [-4.25, -0.25, -2.25, 4.25, 12.25, 2.25], right_arm: [-3.25, -2.25, -2.25, 1.25, 10.25, 2.25], left_arm: [-1.25, -2.25, -2.25, 3.25, 10.25, 2.25] };

const report = { ok: true, key, modes: {} };
const MODES = process.argv[3] ? process.argv[3].split(',') : ['A', 'M', 'B'];   // e.g. node check.mjs <key> M
for (const mode of MODES) {
  const R = { errors: [], warnings: [] };
  report.modes[mode] = R;
  let d;
  try { d = ARMORS[key](mode); } catch (e) { R.errors.push('build threw: ' + e.stack.split('\n').slice(0, 3).join(' | ')); report.ok = false; continue; }
  if (!d || !Array.isArray(d.boxes) || !d.S) { R.errors.push('build must return { S, px, cell, boxes }'); report.ok = false; continue; }
  const boxes = d.boxes.map(b => ({ ...b }));
  R.boxes = boxes.length;
  for (const [i, b] of boxes.entries()) {
    const id = `#${i} ${b.tag || '(no tag)'}`;
    if (!PART_OFF[b.part]) R.errors.push(`${id}: part must be body/right_arm/left_arm, got ${b.part}`);
    if (![b.x, b.y, b.z, b.w, b.h, b.d].every(Number.isFinite)) R.errors.push(`${id}: non-finite geometry`);
    if (!(b.w > 0 && b.h > 0 && b.d > 0)) R.errors.push(`${id}: non-positive size ${b.w}x${b.h}x${b.d}`);
    if (typeof b.mat !== 'function') R.errors.push(`${id}: mat is not a function`);
    if (!b.tag) R.warnings.push(`${id}: missing tag`);
    if (!b.rot && [b.w, b.h, b.d].some(s => Math.abs(s * 4 - Math.round(s * 4)) > 1e-3)) R.warnings.push(`${id}: size ${b.w}x${b.h}x${b.d} not on 1/4 px grid`);
    const e = ENV[b.part];
    if (e && !b.rot) {
      const bb = [b.x, b.y, b.z, b.x + b.w, b.y + b.h, b.z + b.d];
      if (bb[0] >= e[0] - 1e-4 && bb[1] >= e[1] - 1e-4 && bb[2] >= e[2] - 1e-4 && bb[3] <= e[3] + 1e-4 && bb[4] <= e[4] + 1e-4 && bb[5] <= e[5] + 1e-4)
        R.errors.push(`${id}: entirely inside the skin outer layer (invisible on skins with a jacket/sleeve layer)`);
      // outward faces sitting on / just outside the skin outer layer (z-fight or <0.25 clearance)
      for (let a = 0; a < 3; a++) {
        const o1 = (a + 1) % 3, o2 = (a + 2) % 3;
        const overlap = Math.min(bb[o1 + 3], e[o1 + 3]) - Math.max(bb[o1], e[o1]) > 1e-4 && Math.min(bb[o2 + 3], e[o2 + 3]) - Math.max(bb[o2], e[o2]) > 1e-4;
        if (!overlap) continue;
        if (bb[a + 3] > e[a + 3] - 1e-4 && bb[a + 3] < e[a + 3] + 0.25 - 1e-4 && bb[a] < e[a + 3]) R.warnings.push(`${id}: +${'xyz'[a]} face at ${bb[a + 3]} is within 0.25px of the skin layer (${e[a + 3]})`);
        if (bb[a] < e[a] + 1e-4 && bb[a] > e[a] - 0.25 + 1e-4 && bb[a + 3] > e[a]) R.warnings.push(`${id}: -${'xyz'[a]} face at ${bb[a]} is within 0.25px of the skin layer (${e[a]})`);
      }
    }
  }
  // coplanar faces facing the same way that overlap -> z-fighting (armor renders without culling)
  for (let i = 0; i < boxes.length; i++) for (let j = i + 1; j < boxes.length; j++) {
    const p = boxes[i], q = boxes[j];
    if (p.rot || q.rot || p.part !== q.part) continue;
    const P = [p.x, p.y, p.z, p.x + p.w, p.y + p.h, p.z + p.d], Q = [q.x, q.y, q.z, q.x + q.w, q.y + q.h, q.z + q.d];
    for (let a = 0; a < 3; a++) for (const side of [0, 3]) {
      if (Math.abs(P[a + side] - Q[a + side]) > 1e-4) continue;
      const o1 = (a + 1) % 3, o2 = (a + 2) % 3;
      const ov1 = Math.min(P[o1 + 3], Q[o1 + 3]) - Math.max(P[o1], Q[o1]), ov2 = Math.min(P[o2 + 3], Q[o2 + 3]) - Math.max(P[o2], Q[o2]);
      if (!(ov1 > 1e-4 && ov2 > 1e-4)) continue;
      // visible? probe a grid of points just outside the shared face; skip if all are inside another box or the body
      const inside = (pt, B) => pt[0] > B[0] && pt[1] > B[1] && pt[2] > B[2] && pt[0] < B[3] && pt[1] < B[4] && pt[2] < B[5];
      const others = boxes.filter(b => !b.rot && b.part === p.part).map(b => [b.x, b.y, b.z, b.x + b.w, b.y + b.h, b.z + b.d]);
      const body = ENV[p.part] && ENV[p.part].map((v, k) => v + (k < 3 ? 0.001 : -0.001));
      const lo1 = Math.max(P[o1], Q[o1]), lo2 = Math.max(P[o2], Q[o2]);
      let visible = false;
      for (const f1 of [0.2, 0.5, 0.8]) for (const f2 of [0.2, 0.5, 0.8]) {
        const pt = [0, 0, 0]; pt[a] = P[a + side] + (side ? 0.01 : -0.01); pt[o1] = lo1 + ov1 * f1; pt[o2] = lo2 + ov2 * f2;
        if (!others.some(B => inside(pt, B)) && !(body && inside(pt, body))) visible = true;
      }
      if (visible) R.warnings.push(`#${i} ${p.tag} and #${j} ${q.tag}: coplanar ${side ? '+' : '-'}${'xyz'[a]} faces at ${P[a + side]} overlap (${ov1.toFixed(2)}x${ov2.toFixed(2)}) - z-fighting`);
    }
  }  // floating parts: connect boxes that touch or overlap (rotated boxes use their rotated AABB); every part's boxes must
  // form ONE connected piece, otherwise the smaller pieces hang in the air (visible from the side)
  const aabb = b => {
    if (!b.rot) return [b.x, b.y, b.z, b.x + b.w, b.y + b.h, b.z + b.d];
    const D = Math.PI / 180, [ax, ay, az] = b.rot.map(r => r * D), pv = b.pivot || [b.x + b.w / 2, b.y + b.h / 2, b.z + b.d / 2];
    const rx = v => [v[0], v[1] * Math.cos(ax) - v[2] * Math.sin(ax), v[1] * Math.sin(ax) + v[2] * Math.cos(ax)];
    const ry = v => [v[0] * Math.cos(ay) + v[2] * Math.sin(ay), v[1], -v[0] * Math.sin(ay) + v[2] * Math.cos(ay)];
    const rz = v => [v[0] * Math.cos(az) - v[1] * Math.sin(az), v[0] * Math.sin(az) + v[1] * Math.cos(az), v[2]];
    const lo = [Infinity, Infinity, Infinity], hi = [-Infinity, -Infinity, -Infinity];
    for (const X of [b.x, b.x + b.w]) for (const Y of [b.y, b.y + b.h]) for (const Z of [b.z, b.z + b.d]) {
      const q = rz(ry(rx([X - pv[0], Y - pv[1], Z - pv[2]]))).map((v, k) => v + pv[k]);
      for (let k = 0; k < 3; k++) { lo[k] = Math.min(lo[k], q[k]); hi[k] = Math.max(hi[k], q[k]); }
    }
    return [...lo, ...hi];
  };
  for (const part of Object.keys(PART_OFF)) {
    const idx = boxes.map((b, i) => (b.part === part ? i : -1)).filter(i => i >= 0);
    if (idx.length < 2) continue;
    const bb = Object.fromEntries(idx.map(i => [i, aabb(boxes[i])]));
    const par = Object.fromEntries(idx.map(i => [i, i]));
    const find = i => (par[i] === i ? i : (par[i] = find(par[i])));
    for (const i of idx) for (const j of idx) if (i < j) {
      const A = bb[i], Bb = bb[j];
      if ([0, 1, 2].every(k => A[k] <= Bb[k + 3] + 0.02 && Bb[k] <= A[k + 3] + 0.02)) par[find(i)] = find(j);
    }
    const groups = {};
    for (const i of idx) (groups[find(i)] ||= []).push(i);
    const list = Object.values(groups).sort((a, b) => b.length - a.length);
    for (const g of list.slice(1)) R.warnings.push(`floating on ${part}: ${g.map(i => `#${i} ${boxes[i].tag}`).join(', ')} - not touching the rest of the armor`);
  }  const { tw, th } = packFaces(boxes, d.S);
  R.texture = `${tw * d.S}x${th * d.S}`;
  let texels = 0, transparent = 0, badColor = 0, threw = 0; const throwMsgs = new Set();
  const cell = d.cell || 0, S = d.S;
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
        texels++;
        let col;
        try { col = b.mat(c); } catch (e) { threw++; throwMsgs.add(`${b.tag}: ${e.message}`); continue; }
        if (col == null) { transparent++; continue; }
        if (!Array.isArray(col) || col.length < 3 || !col.slice(0, 3).every(Number.isFinite)) badColor++;
      }
    }
  }
  R.texels = texels; R.transparentPct = Math.round(100 * transparent / Math.max(1, texels));
  if (threw) { R.errors.push(`painter threw on ${threw} texels: ${[...throwMsgs].slice(0, 5).join(' ; ')}`); }
  if (badColor) R.errors.push(`painter returned ${badColor} invalid colors (must be [r,g,b] finite or null)`);
  if (R.errors.length) report.ok = false;
  R.warnings = [...new Set(R.warnings)].slice(0, 40);
}
console.log(JSON.stringify(report, null, 1));
process.exit(report.ok ? 0 : 1);
