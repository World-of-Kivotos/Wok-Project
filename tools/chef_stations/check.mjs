// Validate a design (or the reference blocks) against vanilla 1.20.1 block-model rules.
//   node check.mjs designs/<key>      (or just <key>; a designs root other than ./designs works too: path/to/<key>)
//   node check.mjs --refs             check every block in refs/index.json (sanity check of the checker itself)
// Exit code 1 when any ERROR is found. Report lines: ERROR (fix before shipping), warn (probably wrong), info (FYI).
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { decodePNG } from './lib/png.mjs';
import { MC_DIRS, MC_LEGAL_ANGLES, mcResolveModel, mcResolveTexture, mcBakeModel, mcSplitId, mcFullId, mcAnimInfo, mcFaceVerts } from './lib/mcmodel.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const REFS = path.join(here, 'refs');
const RENDER_TYPES = ['solid', 'cutout', 'cutout_mipped', 'translucent', 'tripwire'];
const EPS = 1e-4;

// ---------------------------------------------------------------- report
let nErr = 0, nWarn = 0, nInfo = 0;
const lines = [];
const say = (lvl, where, msg) => {
  if (lvl === 'ERROR') nErr++; else if (lvl === 'warn') nWarn++; else nInfo++;
  lines.push(`    ${lvl.padEnd(5)} ${where ? where + ': ' : ''}${msg}`);
};
const head = s => lines.push(s);

// ---------------------------------------------------------------- resource access
function makeScope(designDir, ns) {
  const root = n => (designDir && n === ns ? designDir : path.join(REFS, n));
  const jsonCache = new Map(), pngCache = new Map();
  return {
    modelFile: id => { const { ns: n, path: p } = mcSplitId(id); return path.join(root(n), 'models', p + '.json'); },
    textureFile: id => { const { ns: n, path: p } = mcSplitId(id); return path.join(root(n), 'textures', p + '.png'); },
    getModel(id) {
      const f = this.modelFile(id);
      if (!jsonCache.has(f)) {
        let v = null;
        if (fs.existsSync(f)) { try { v = JSON.parse(fs.readFileSync(f, 'utf8').replace(/^﻿/, '')); } catch (e) { v = { __parseError: e.message }; } }
        jsonCache.set(f, v);
      }
      const v = jsonCache.get(f);
      return v && v.__parseError ? null : v;
    },
    parseError(id) { this.getModel(id); const v = jsonCache.get(this.modelFile(id)); return v && v.__parseError; },
    texture(id) {
      const f = this.textureFile(id);
      if (!pngCache.has(f)) {
        let t = null;
        if (fs.existsSync(f)) {
          t = { file: f };
          try { const d = decodePNG(fs.readFileSync(f)); Object.assign(t, { w: d.width, h: d.height, data: d.data }); } catch (e) { t.error = 'PNG decode failed: ' + e.message; }
          if (fs.existsSync(f + '.mcmeta')) { try { t.mcmeta = JSON.parse(fs.readFileSync(f + '.mcmeta', 'utf8').replace(/^﻿/, '')); } catch (e) { t.mcmetaError = e.message; } }
        }
        pngCache.set(f, t);
      }
      return pngCache.get(f);
    },
  };
}
const rel = f => path.relative(here, f).replace(/\\/g, '/');

// ---------------------------------------------------------------- geometry helpers
const sub = (a, b) => [a[0] - b[0], a[1] - b[1], a[2] - b[2]];
const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
const cross = (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
const norm = a => { const l = Math.hypot(...a); return a.map(c => c / l); };
function basis(n) { const a = norm(cross(n, Math.abs(n[1]) < 0.9 ? [0, 1, 0] : [1, 0, 0])); return [a, cross(n, a)]; }
/** Separating-axis test for two convex 2D polygons; true only when they share a positive area. */
function overlap2D(A, B) {
  for (const P of [A, B]) for (let i = 0; i < P.length; i++) {
    const p = P[i], q = P[(i + 1) % P.length], ax = p[1] - q[1], ay = q[0] - p[0], l = Math.hypot(ax, ay);
    if (l < 1e-12) continue;
    const proj = S => S.map(v => (v[0] * ax + v[1] * ay) / l);
    const a = proj(A), b = proj(B);
    if (Math.min(Math.max(...a), Math.max(...b)) - Math.max(Math.min(...a), Math.min(...b)) <= EPS) return false;
  }
  return true;
}
const fmt = v => +v.toFixed(3);
const elName = (el, i) => `#${i}${el && el.name ? ' "' + el.name + '"' : ''}`;

/** Texel rect a face samples (frame-local), for transparency checks. */
function texelsOf(uvRect, fw, fh) {
  const u0 = Math.min(uvRect[0], uvRect[2]) * fw / 16, u1 = Math.max(uvRect[0], uvRect[2]) * fw / 16;
  const v0 = Math.min(uvRect[1], uvRect[3]) * fh / 16, v1 = Math.max(uvRect[1], uvRect[3]) * fh / 16;
  const x0 = Math.min(fw - 1, Math.floor(u0 + EPS)), x1 = Math.max(x0 + 1, Math.min(fw, Math.ceil(u1 - EPS)));
  const y0 = Math.min(fh - 1, Math.floor(v0 + EPS)), y1 = Math.max(y0 + 1, Math.min(fh, Math.ceil(v1 - EPS)));
  return [x0, y0, x1, y1];
}
function alphaStats(tex, ai, rect) {
  let opaque = 0, clear = 0, partial = 0;
  for (let f = 0; f < ai.count; f++) {
    const ox = (f % ai.cols) * ai.fw, oy = Math.floor(f / ai.cols) * ai.fh;
    for (let y = rect[1]; y < rect[3]; y++) for (let x = rect[0]; x < rect[2]; x++) {
      const a = tex.data[((oy + y) * tex.w + ox + x) * 4 + 3];
      if (a === 0) clear++; else if (a === 255) opaque++; else partial++;
    }
  }
  return { opaque, clear, partial };
}

// ---------------------------------------------------------------- one model
function checkModel(scope, id, opts) {
  const where = mcFullId(id);
  const pe = scope.parseError(id);
  head(`  model ${where}  (${rel(scope.modelFile(id))})`);
  if (pe) { say('ERROR', '', 'JSON 解析失败: ' + pe); return; }
  const raw = scope.getModel(id);
  if (!raw) { say('ERROR', '', '模型文件不存在'); return; }
  const m = mcResolveModel(id, x => scope.getModel(x));
  for (const e of m.errors) say('ERROR', 'parent', e.replace('missing parent model', '父模型找不到').replace('missing model', '模型找不到') + (e.includes('minecraft:') ? '（原版父模型要先写进 refs/minecraft/models，见 extract-refs.mjs 的 VANILLA_MODELS）' : ''));
  if (!m.chain.includes('minecraft:block/block')) say('info', 'parent', '父链里没有 block/block：手持/物品栏显示用默认变换（FD 的 skillet 也这样，问题不大）');
  if (raw.render_type != null && !RENDER_TYPES.includes(m.renderType)) say('ERROR', 'render_type', `"${raw.render_type}" 不是合法值（solid / cutout / cutout_mipped / translucent）`);
  if (raw.texture_size) say('info', 'texture_size', 'Blockbench 字段，游戏忽略；UV 仍是 0..16');
  const els = m.elements;
  if (!els.length) { say('ERROR', 'elements', '没有任何元素'); return; }
  if (m.elementsFrom !== where) say('info', 'elements', '元素继承自 ' + m.elementsFrom);

  // --- elements & faces (structure)
  let faceCount = 0;
  els.forEach((el, i) => {
    const w = 'el ' + elName(el, i);
    if (!el || typeof el !== 'object') { say('ERROR', w, '不是对象'); return; }
    for (const k of ['from', 'to']) {
      const v = el[k];
      if (!Array.isArray(v) || v.length !== 3 || !v.every(Number.isFinite)) { say('ERROR', w, `${k} 必须是 3 个数字`); return; }
      if (v.some(c => c < -16 || c > 32)) say('ERROR', w, `${k} [${v}] 超出 -16..32（游戏直接拒绝加载这个模型）`);
    }
    for (let k = 0; k < 3; k++) if (el.from[k] > el.to[k]) say('ERROR', w, `from[${k}]=${el.from[k]} > to[${k}]=${el.to[k]}（面会反向，被背面剔除）`);
    const outside = [0, 1, 2].filter(k => el.from[k] < 0 || el.to[k] > 16);
    if (outside.length) say('info', w, `伸出方块格外（${outside.map(k => 'xyz'[k]).join('')} 轴）：[${el.from}]→[${el.to}]`);
    const r = el.rotation;
    if (r != null) {
      if (typeof r !== 'object' || Array.isArray(r)) say('ERROR', w, 'rotation 必须是 {origin, axis, angle}');
      else {
        const extra = Object.keys(r).filter(k => !['origin', 'axis', 'angle', 'rescale'].includes(k));
        if (extra.length) say('ERROR', w, `rotation 有多余字段 ${extra.join(',')}：1.20.1 只支持单轴旋转`);
        if (!['x', 'y', 'z'].includes(r.axis)) say('ERROR', w, `rotation.axis "${r.axis}" 必须是 x / y / z 之一（单轴）`);
        if (!MC_LEGAL_ANGLES.includes(r.angle)) say('ERROR', w, `rotation.angle ${r.angle} 不合法（只能 -45 / -22.5 / 0 / 22.5 / 45）`);
        if (!Array.isArray(r.origin) || r.origin.length !== 3 || !r.origin.every(Number.isFinite)) say('ERROR', w, 'rotation.origin 必须是 3 个数字');
        if (r.rescale && r.angle === 0) say('warn', w, 'angle 0 + rescale:true：原版会按 45° 的系数把两轴放大 1.414 倍');
        if (r.rescale != null && typeof r.rescale !== 'boolean') say('ERROR', w, 'rotation.rescale 必须是 true/false');
        // from/to inside the block does not mean the rotated box is: report where the corners really end up
        if (['x', 'y', 'z'].includes(r.axis) && MC_LEGAL_ANGLES.includes(r.angle) && (r.angle !== 0 || r.rescale) && !outside.length) {
          const lo = [Infinity, Infinity, Infinity], hi = [-Infinity, -Infinity, -Infinity];
          for (const d of MC_DIRS) for (const p of mcFaceVerts(el, d)) for (let k = 0; k < 3; k++) { lo[k] = Math.min(lo[k], p[k]); hi[k] = Math.max(hi[k], p[k]); }
          const out = [0, 1, 2].filter(k => lo[k] < -0.005 || hi[k] > 16.005), f = n => +n.toFixed(2);
          if (out.length) say('info', w, `旋转后伸出方块格外（${out.map(k => 'xyz'[k]).join('')} 轴）：实际范围 [${lo.map(f)}]→[${hi.map(f)}]`);
        }
      }
    }
    if (el.shade === false) say('info', w, 'shade:false，各面不分明暗（全亮）');
    if (!el.faces || typeof el.faces !== 'object') { say('ERROR', w, '没有 faces'); return; }
    const keys = Object.keys(el.faces);
    if (!keys.length) say('warn', w, 'faces 是空的，这个元素看不见');
    for (const dir of keys) {
      const fw = `${w} ${dir}`;
      if (!MC_DIRS.includes(dir)) { say('ERROR', fw, `面名 "${dir}" 不合法`); continue; }
      const f = el.faces[dir];
      faceCount++;
      if (typeof f.texture !== 'string') { say('ERROR', fw, '缺少 texture'); continue; }
      if (!f.texture.startsWith('#')) say('warn', fw, `texture "${f.texture}" 没写 #（能用，但约定写 #变量名）`);
      if (f.uv != null) {
        if (!Array.isArray(f.uv) || f.uv.length !== 4 || !f.uv.every(Number.isFinite)) say('ERROR', fw, 'uv 必须是 4 个数字');
        else if (f.uv.some(c => c < 0 || c > 16)) say('ERROR', fw, `uv [${f.uv}] 超出 0..16`);
        else if (f.uv[0] === f.uv[2] || f.uv[1] === f.uv[3]) say('warn', fw, `uv [${f.uv}] 面积为 0（只取到一条线的像素）`);
      }
      if (f.rotation != null && ![0, 90, 180, 270].includes(f.rotation)) say('ERROR', fw, `uv rotation ${f.rotation} 只能 0/90/180/270`);
      if (f.cullface != null && !MC_DIRS.includes(f.cullface)) say('ERROR', fw, `cullface "${f.cullface}" 不合法`);
      if (f.tintindex != null && !Number.isInteger(f.tintindex)) say('warn', fw, 'tintindex 应该是整数');
      // geometric zero area (element rotation does not change area)
      const ext = [0, 1, 2].map(k => Math.abs(el.to[k] - el.from[k])), axis = { down: 1, up: 1, north: 2, south: 2, west: 0, east: 0 }[dir];
      if ([0, 1, 2].filter(k => k !== axis).some(k => ext[k] < EPS)) say('warn', fw, '这个面面积为 0（元素在面内方向厚度为 0），删掉它');
    }
  });

  // --- textures used by faces
  const quads = mcBakeModel(m);
  const usedTex = new Map();     // texture id -> quads
  const reported = new Set();
  for (const q of quads) {
    const fw = `el ${elName(els[q.el], q.el)} ${q.dir}`;
    if (!q.tex) { say('ERROR', fw, `texture "${q.texRef}" 解析不到（游戏里是紫黑格子）`); continue; }
    if (!usedTex.has(q.tex)) usedTex.set(q.tex, []);
    usedTex.get(q.tex).push(q);
  }
  for (const [tid, qs] of usedTex) {
    const t = scope.texture(tid), tw = `texture ${tid}`;
    if (!t) { say('ERROR', tw, `PNG 不存在：${rel(scope.textureFile(tid))}`); continue; }
    if (t.error) { say('ERROR', tw, t.error); continue; }
    if (t.mcmetaError) say('ERROR', tw, '.mcmeta 解析失败: ' + t.mcmetaError);
    const ai = mcAnimInfo(t.w, t.h, t.mcmeta), anim = t.mcmeta && t.mcmeta.animation;
    if (t.mcmeta && !anim) say('warn', tw, '.mcmeta 里没有 animation 段');
    if (anim) {
      if (anim.frametime != null && !(Number.isInteger(anim.frametime) && anim.frametime >= 1)) say('ERROR', tw, `frametime ${anim.frametime} 必须是 ≥1 的整数（tick）`);
      if (t.w % ai.fw || t.h % ai.fh) say('ERROR', tw, `${t.w}×${t.h} 不能整除帧大小 ${ai.fw}×${ai.fh}`);
      if (Array.isArray(anim.frames)) for (const f of ai.frames) if (!(Number.isInteger(f.index) && f.index >= 0 && f.index < ai.count)) say('ERROR', tw, `frames 里的帧号 ${f.index} 超出 0..${ai.count - 1}`);
      if (anim.interpolate != null && typeof anim.interpolate !== 'boolean') say('ERROR', tw, 'interpolate 必须是 true/false');
      say('info', tw, `动画 ${ai.frames.length} 帧 × ${ai.frames[0].time} tick${ai.interpolate ? '，插值' : ''}（一圈 ${(ai.total / 20).toFixed(2)} 秒）`);
    } else if (t.w !== t.h) say('ERROR', tw, `${t.w}×${t.h} 不是正方形，又没有 .mcmeta 动画——会被当成一张拉伸的贴图`);
    if (!(ai.fw === 16 && ai.fh === 16) && (anim || t.w === t.h)) {
      const is32 = ai.fw === 32 && ai.fh === 32;
      if (is32 && opts.hires) say('warn', tw, '32×32 高清贴图（声明了 hires）：像素密度是农夫乐事方块的两倍，放在一起会显得更细');
      else if (is32) say('ERROR', tw, '32×32 贴图，但 meta.json 没声明 "hires": true；和农夫乐事放一起要 16×16');
      else say('ERROR', tw, `每帧 ${ai.fw}×${ai.fh}，应为 16×16`);
    }
    // transparency of the regions faces actually sample
    let anyClear = false, anyPartial = false;
    for (const q of qs) {
      const st = alphaStats(t, ai, texelsOf(q.uvRect, ai.fw, ai.fh));
      if (st.clear) anyClear = true;
      if (st.partial) anyPartial = true;
      if (!st.opaque && !st.partial) {
        const k = `${q.el}/${q.dir}`;
        if (!reported.has(k)) { reported.add(k); say('info', `el ${elName(els[q.el], q.el)} ${q.dir}`, '只取到全透明像素，这个面看不见，可以删掉'); }
      }
    }
    const rt = m.renderType;
    if (anyPartial && rt !== 'translucent') say('warn', tw, `有半透明像素，但 render_type 是 ${rt || '未设(=solid)'}：cutout 会把 alpha<0.1 的丢掉、其余按不透明画`);
    if (anyClear && (rt == null || rt === 'solid')) say('warn', tw, `面上用到了透明像素，但 render_type ${rt ? '是 solid' : '没写（游戏默认 solid）'}：透明处会变成黑/底色。要镂空写 "render_type": "minecraft:cutout"`);
  }
  if (!m.textures.particle) say('warn', 'textures', '没有 particle（破坏方块时的碎屑会是紫黑格子）');
  else {
    const p = mcResolveTexture('#particle', m.textures);
    if (!p) say('warn', 'textures', `particle "${m.textures.particle}" 解析不到`);
    else if (!scope.texture(p)) say('warn', 'textures', `particle 指向的 ${p} 不存在`);
  }
  const usedVars = new Set(els.flatMap(el => Object.values((el && el.faces) || {}).map(f => String(f.texture || '').replace(/^#/, ''))));
  for (const [k, v] of Object.entries(m.textures)) {
    if (k === 'particle' || typeof v !== 'string' || usedVars.has(k) || Object.values(m.textures).includes('#' + k)) continue;
    say('info', 'textures', `变量 ${k} → ${v} 没有面在用`);
  }

  // --- z-fighting: coplanar, same facing, overlapping faces of different elements
  const vis = quads.filter(q => q.normal && q.tex && !reported.has(`${q.el}/${q.dir}`));
  for (let i = 0; i < vis.length; i++) for (let j = i + 1; j < vis.length; j++) {
    const a = vis[i], b = vis[j];
    if (a.el === b.el || dot(a.normal, b.normal) < 0.9999) continue;
    if (Math.abs(dot(a.normal, sub(b.pos[0], a.pos[0]))) > EPS) continue;
    const [e1, e2] = basis(a.normal), P = q => q.pos.map(p => [dot(p, e1), dot(p, e2)]);
    const A = P(a), B = P(b);
    if (!overlap2D(A, B)) continue;
    const span = k => Math.min(Math.max(...A.map(p => p[k])), Math.max(...B.map(p => p[k]))) - Math.max(Math.min(...A.map(p => p[k])), Math.min(...B.map(p => p[k])));
    say('ERROR', 'z-fight', `el ${elName(els[a.el], a.el)} ${a.dir} 和 el ${elName(els[b.el], b.el)} ${b.dir} 共面重叠约 ${fmt(span(0))}×${fmt(span(1))} px，游戏里会闪`);
  }
  // --- faces buried inside another (unrotated) element: info
  els.forEach((el2, j) => {
    if (!el2 || (el2.rotation && el2.rotation.angle) || !el2.faces || !Object.keys(el2.faces).length || !Array.isArray(el2.from) || !Array.isArray(el2.to)) return;
    const lo = [0, 1, 2].map(k => Math.min(el2.from[k], el2.to[k])), hi = [0, 1, 2].map(k => Math.max(el2.from[k], el2.to[k]));
    if ([0, 1, 2].some(k => hi[k] - lo[k] < EPS)) return;
    const inside = p => [0, 1, 2].every(k => p[k] >= lo[k] - EPS && p[k] <= hi[k] + EPS);
    for (const q of quads) {
      if (q.el === j || !q.normal) continue;
      const c = [0, 1, 2].map(k => (q.pos[0][k] + q.pos[2][k]) / 2 + q.normal[k] * 0.01);
      if (q.pos.every(inside) && [0, 1, 2].every(k => c[k] > lo[k] && c[k] < hi[k]))
        say('info', 'hidden', `el ${elName(els[q.el], q.el)} ${q.dir} 被 el ${elName(el2, j)} 包住（它不镂空的话就看不见），可以删掉`);
    }
  });
  const nAnim = [...usedTex.keys()].filter(t => { const x = scope.texture(t); return x && x.mcmeta && x.mcmeta.animation; }).length;
  say('info', 'stats', `${els.length} 个元素、${faceCount} 个面、${usedTex.size} 张贴图${nAnim ? `（${nAnim} 张动画）` : ''}、render_type ${m.renderType || '未设(=solid)'}`);
  if (els.length > 64) say('warn', 'stats', `元素 ${els.length} 个，偏多（农夫乐事的厨锅 4 个、带托架 9 个）`);
}

// ---------------------------------------------------------------- design / refs
function checkDesign(dir) {
  const metaF = path.join(dir, 'meta.json');
  head(`check ${rel(dir)}`);
  let meta = null;
  if (!fs.existsSync(metaF)) say('ERROR', 'meta.json', '不存在');
  else { try { meta = JSON.parse(fs.readFileSync(metaF, 'utf8').replace(/^﻿/, '')); } catch (e) { say('ERROR', 'meta.json', '解析失败: ' + e.message); } }
  const ns = (meta && meta.namespace) || 'miningdim';
  const scope = makeScope(dir, ns);
  const ids = new Set();
  if (meta) {
    head(`  ${meta.tag || ''} ${meta.name || '(没有 name)'} — key ${meta.key}`);
    if (meta.key !== path.basename(dir)) say('warn', 'meta.json', `key "${meta.key}" 和目录名 "${path.basename(dir)}" 不一致`);
    for (const k of ['key', 'name', 'tagline', 'description']) if (!meta[k]) say(k === 'tagline' ? 'warn' : 'ERROR', 'meta.json', `缺少 ${k}`);
    if (!Array.isArray(meta.blocks) || !meta.blocks.length) say('ERROR', 'meta.json', 'blocks 至少要有一个');
    (meta.blocks || []).forEach((b, i) => {
      for (const st of ['idle', 'active']) {
        const s = b[st];
        if (!s || !s.model) { say('ERROR', `blocks[${i}].${st}`, '缺少 model'); continue; }
        const id = s.model.includes(':') ? s.model : `${ns}:block/${s.model.replace(/^block\//, '')}`;
        ids.add(mcFullId(id));
        for (const r of ['x', 'y']) if (s[r] != null && ![0, 90, 180, 270].includes(s[r])) say('ERROR', `blocks[${i}].${st}`, `${r} 只能 0/90/180/270`);
      }
      if (b.pos && !(Array.isArray(b.pos) && b.pos.length === 3 && b.pos.every(Number.isInteger))) say('ERROR', `blocks[${i}]`, 'pos 必须是 3 个整数');
    });
    if (meta.notes && !Array.isArray(meta.notes)) say('warn', 'meta.json', 'notes 应该是字符串数组');
  }
  // also check every model file shipped in the design folder
  const mdir = path.join(dir, 'models');
  const walk = d => fs.existsSync(d) ? fs.readdirSync(d, { withFileTypes: true }).flatMap(e => e.isDirectory() ? walk(path.join(d, e.name)) : e.name.endsWith('.json') ? [path.join(d, e.name)] : []) : [];
  const files = walk(mdir).map(f => `${ns}:${path.relative(mdir, f).replace(/\\/g, '/').replace(/\.json$/, '')}`);
  for (const f of files) if (!ids.has(f)) say('info', 'models', `${f} 没有在 meta.blocks 里用到（如果是父模型就没事）`);
  for (const id of new Set([...ids, ...files])) checkModel(scope, id, { hires: !!(meta && meta.hires) });
}

function checkRefs() {
  const idx = JSON.parse(fs.readFileSync(path.join(REFS, 'index.json'), 'utf8'));
  const scope = makeScope(null, null);
  head('check refs/ (reference blocks; 32×32 allowed as warning)');
  const ids = new Set();
  for (const b of idx.blocks) for (const v of [b.idle, b.active, ...(b.variants || [])]) ids.add(v.model);
  for (const id of ids) checkModel(scope, id, { hires: true });
}

const target = process.argv[2];
if (!target) { console.log('usage: node check.mjs <designDir|key> | --refs'); process.exit(2); }
if (target === '--refs') checkRefs();
else {
  let dir = path.resolve(target);
  if (!fs.existsSync(dir)) dir = path.join(here, 'designs', target);
  if (!fs.existsSync(dir)) { console.log('no such design: ' + target); process.exit(2); }
  checkDesign(dir);
}
console.log(lines.join('\n'));
console.log(`结果: ${nErr} error, ${nWarn} warn, ${nInfo} info${nErr ? '  -> 有 ERROR，先修' : '  -> 可以进游戏'}`);
process.exit(nErr ? 1 : 0);
