// 烤炉 方案 B「铸铁烤箱灶」生成脚本：逐像素画贴图 + 写原版方块模型 / 方块状态 / 物品模型 JSON + meta.json。
//   cd <仓库>\tools\chef_stations && node designs-more/oven_b/gen.mjs
// 产出（路径与主 MOD assets/miningdim/ 一致，定稿后 blockstates/ models/ textures/ 可直接拷）：
//   blockstates/oven_b.json       facing × lit 共 8 个变体
//   models/block/oven_b.json      待机：观察窗里是空烤架，指示灯灭，炉温表指针在冷区，背板顶排气栅是黑的
//   models/block/oven_b_on.json   工作中：只有 parent=待机 + 三张贴图覆盖（glass→glass_on 6 帧火光、dial→dial_on、top→top_on），
//                                 不带 elements，几何 100% 继承待机，切换不会跳
//   models/item/oven_b.json       物品模型（parent=方块模型）
//   textures/block/oven_b_*.png (+ glass_on.png.mcmeta)
// 和炸锅 B「商用炸炉」（designs/B）同一家族：同一套不锈钢梯度 FAMILY（底色 #87878b，最亮的 #aeaeb2 只用在前沿 / 台沿这种
// 1 像素高光上）、同样的 2px 支脚（钢 + 深色橡胶脚）、y10–11 的前沿、y16 的背板顶和排气栅、背板正面同样是钢框里一块农夫乐事
// 深灰内凹面板、背板 x6–10 的圆形白盘表、同一张旋钮 / 指示灯 / 表盘贴图、同一张侧面贴图，强调色同为农夫乐事厨锅把手的铜红。
// 灶面、炉架用农夫乐事锅具的深灰（铸铁）。脚本最后会只读对照 designs/B 的色板、铜红和几张共用贴图，发现漂移就打 WARN。
// 坐标约定：正面 = north（z=0），从正面看 +x 在左边；顶面贴图行 = z、列 = x。
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { makeCanvas, setPx, getPx, strip, write, rgba, read, hex } from '../../lib/png.mjs';

const dir = path.dirname(fileURLToPath(import.meta.url));
const NS = 'miningdim', P = 'oven_b';
const texFile = n => path.join(dir, 'textures/block', `${P}_${n}.png`);
const tex = n => `${NS}:block/${P}_${n}`;
const json = (rel, o) => { const f = path.join(dir, rel); fs.mkdirSync(path.dirname(f), { recursive: true }); fs.writeFileSync(f, JSON.stringify(o, null, 2) + '\n'); };

// ---------------------------------------------------------------- palette
// FAMILY = 炸炉 B（designs/B/gen.mjs 的 PAL 1–6）现值，三台机器共用；改这里之前先看炸炉那边是不是也改了（脚本末尾会核对）。
// 用法同炸炉：4 是钢板底色，5 是受光的倒角 / 前沿，6 只给前沿、台沿这种 1 像素高光，3 / 2 是背光边和下沿，1 是最底下两行。
const FAMILY = { 1: '#4f4f53', 2: '#626266', 3: '#747478', 4: '#87878b', 5: '#9a9a9e', 6: '#aeaeb2' };
const PAL = {
  ...FAMILY,
  // FD cool dark greys: cast iron (hob, grate), backsplash recess, oven cavity
  a: '#1f1f21', b: '#27272b', c: '#2d2d32', d: '#343438', e: '#3f3e42', f: '#494848', g: '#595858', h: '#656565', i: '#727272',
  // baked goods (fryer B food / FD pie crust tones)
  s: '#d9a441', t: '#ecc76a', v: '#e8b548', w: '#b3702a', x: '#8c5220',
  // FD copper red (cooking pot handle) — the family accent, same four values as fryer B
  R: '#9b5643', S: '#b1624d', T: '#733f31', U: '#8f503f',
  // lamp / gauge (fryer B's: lamp off = dark amber o / p, lit = M / N)
  o: '#6b4118', p: '#8a5a22', M: '#ffe08a', N: '#f5a623', W: '#e4e1d8', K: '#1f1f21', H: '#c35d1b',
  // FD stove fire (stove_front_on)
  B: '#4a2116', C: '#5f341f', D: '#c35d1b', E: '#ed8c0e', F: '#ffd800',
};
const col = ch => { if (!(ch in PAL)) throw new Error('palette: ' + ch); return PAL[ch]; };
const C = () => makeCanvas(16, 16);
const px = (c, x, y, ch) => { if (ch !== '.') setPx(c, x, y, col(ch)); else setPx(c, x, y, [0, 0, 0, 0]); };
const rect = (c, x, y, w, h, ch) => { for (let j = y; j < y + h; j++) for (let i = x; i < x + w; i++) px(c, i, j, ch); };
const rows = (c, x, y, lines) => lines.forEach((l, j) => [...l].forEach((ch, i) => { if (ch !== ' ') px(c, x + i, y + j, ch); }));
function rng(seed) { let s = seed >>> 0; return () => { s = (s + 0x6d2b79f5) >>> 0; let t = s; t = Math.imul(t ^ (t >>> 15), t | 1); t ^= t + Math.imul(t ^ (t >>> 7), t | 61); return ((t ^ (t >>> 14)) >>> 0) / 4294967296; }; }
const same = (c, x, y, ch) => { const p = getPx(c, x, y), q = rgba(col(ch)); return p[0] === q[0] && p[1] === q[1] && p[2] === q[2] && p[3] === 255; };
function speck(c, x, y, w, h, from, to, density, seed) {
  const R = rng(seed);
  for (let j = y; j < y + h; j++) for (let i = x; i < x + w; i++) { const k = R(); if (k < density && same(c, i, j, from)) px(c, i, j, to); }
}

// ---------------------------------------------------------------- layout (shared by textures and geometry)
// Backsplash controls, as x ranges (north face as seen: u = 15 - x for the pixel [x, x+1]).
// The backsplash front is fryer B's: a steel frame round an FD dark-grey recess. On the recess, per side a knob (2×2,
// y11.5-13.5) with its lamp right above it (y14-15, two bezel pixels), and in the middle fryer B's round white gauge at the
// same x (x6-10, y11-15), so the strip reads "knob + lamp, gauge, knob + lamp" like the fryer's control panel.
const KNOBS = [[12, 'knob east'], [2, 'knob west']];        // x0 of the 2px knob
const LAMPS = [[13, 'lamp east'], [2, 'lamp west']];        // x0 of the 1px lamp (above the outer column of its knob)

// ---------------------------------------------------------------- textures
const T = {};

// FRONT (north faces, as seen: u = 16 - x, v = 16 - y)
//   v0-4  backsplash front (y 11-16): fryer B's backsplash rows — steel frame (lit top row / left column, shadow right column),
//         a shadow row, then the FD dark-grey recess the knobs, lamps and gauge sit on
//   v5    lip (y 10-11), fryer B's lip row
//   v6    dark vent slot under the lip (door top row y 9-10, behind the handle) — fryer B's shadow row under the lip
//   v7-13 oven door u1-14 (y 2-9): fryer B's door bevels (base 4, lit 5 top/left, 2 / 3 bottom/right), window hole u3-12 v8-12
//         (10×5, transparent; glass 0.5px behind); u0 / u15 are the cabinet's jambs (z = 1, behind the door)
{
  const c = C();
  // backsplash (exactly fryer B's backsplash front rows)
  rect(c, 0, 0, 16, 5, 'd'); speck(c, 1, 2, 14, 3, 'd', 'e', 0.25, 11);
  rect(c, 0, 0, 16, 1, '4'); rect(c, 1, 1, 14, 1, 'c');
  for (let v = 0; v <= 4; v++) { px(c, 0, v, '4'); px(c, 15, v, '3'); }
  for (const [x] of LAMPS) { const u = 15 - x; px(c, u - 1, 1, 'f'); px(c, u + 1, 1, 'f'); }        // lamp bezel (fryer's bezel grey)
  // lip (fryer B's lip row: bright 6 with a few 5)
  rect(c, 0, 5, 16, 1, '6'); speck(c, 0, 5, 16, 1, '6', '5', 0.3, 12);
  // vent slot (shadow under the lip, behind the handle)
  rect(c, 0, 6, 16, 1, 'b');
  // jambs
  for (let v = 7; v <= 13; v++) { px(c, 0, v, '2'); px(c, 15, v, '2'); }
  // door
  rect(c, 1, 7, 14, 7, '4');
  for (let u = 1; u <= 14; u++) { px(c, u, 7, '5'); px(c, u, 13, '2'); }      // top bevel lit, bottom bevel dark
  for (let v = 7; v <= 13; v++) { px(c, 1, v, '5'); px(c, 14, v, '3'); }      // left bevel lit, right bevel dark
  px(c, 14, 7, '4'); px(c, 1, 13, '3'); px(c, 14, 13, '2');
  // window: inner bevel (shadowed left / lit right as seen), then the hole (u3-12, v8-12 = x 3-13, y 3-8)
  for (let v = 8; v <= 12; v++) { px(c, 2, v, '3'); px(c, 13, v, '5'); }
  rect(c, 3, 8, 10, 5, '.');
  rect(c, 0, 14, 16, 2, '1');
  T.front = c;
}

// GLASS — window plane 12×8 (x 2-14 / y 2-10, u = 14 - x, v = 10 - y); visible through the hole: u1-10 v2-6.
function glassIdle() {
  const c = C();
  rect(c, 0, 0, 12, 8, 'a');
  rect(c, 1, 2, 10, 1, 'b');           // cavity roof
  rect(c, 1, 3, 10, 2, 'c');           // back wall
  rows(c, 1, 5, ['effffffffe']);       // empty rack
  rect(c, 1, 6, 10, 1, 'b');           // floor
  // glass glint: one faint diagonal streak plus a fainter parallel one, top left as seen (low contrast, so no "eyes")
  rows(c, 2, 2, ['  e d', ' e d', 'e']);
  return c;
}
T.glass = glassIdle();

// GLASS_ON — 6 frames × 3 tick (FD stove front rhythm). Gas oven: the cavity glows orange, a golden pie in its dish sits on a dark
// baking tray, a row of flames under the tray. Only the flames move (no pulsing element → no strobing).
{
  const frames = [];
  const flames = ['DEDFEDDEFD', 'EDFDDEDFDE', 'DFEDEDFEDD', 'EDDEFDDEDF', 'DEFDEDDFED', 'FDDEDFEDDE'];
  const tips = [[0, 'E'], [9, 'D'], [9, 'E'], [0, 'D'], [0, 'F'], [9, 'F']];   // a tongue curling up past a tray end
  for (let f = 0; f < 6; f++) {
    const c = C();
    rect(c, 0, 0, 12, 8, 'B');
    rect(c, 1, 1, 10, 6, 'C');
    rows(c, 1, 2, ['CDDEEEEDDC']);           // cavity back wall lit by the flames
    rows(c, 1, 3, ['CwDvttvDwC']);           // pie dome + dish rim
    rows(c, 1, 4, ['DxsvsvsvxD']);           // pie body
    rows(c, 1, 5, ['B11111111B']);           // baking tray
    rows(c, 1, 6, [flames[f]]);              // flames along the floor
    px(c, 1 + tips[f][0], 5, tips[f][1]);
    frames.push(c);
  }
  T.glass_on = strip(frames);
}

// DIAL — knob (2×2 front), lamp (1×1), oven-temperature gauge (4×4, corners cut = round); *_on = lamp lit, knob turned,
// needle in the red. Pixel-for-pixel the same as fryer B's fryer_b_dial(_on) (checked at the end of this script).
function dial(on) {
  const c = C();
  rows(c, 0, 0, [on ? 'g4' : '4g', 'ef']);
  rows(c, 2, 0, ['f', 'e']); rows(c, 0, 2, ['gg']);
  px(c, 4, 0, on ? 'M' : 'o'); px(c, 5, 0, on ? 'N' : 'p'); px(c, 4, 1, on ? 'N' : 'p');
  rows(c, 8, 0, on ? ['.NH.', 'WWKH', 'WeWW', '.WW.'] : ['.NH.', 'WWWH', 'KeWW', '.WW.']);
  rows(c, 8, 4, ['.ee.']);
  return c;
}
T.dial = dial(false);
T.dial_on = dial(true);

// TOP (u = x, v = z; row 0 = front). Lip v0; steel rim v1 / u0 / u15 / v12; cast-iron hob u1-14 v2-11 (darkest FD grey so the
// grate floats above it) with two burner heads centred under the grate holes (u4 / u11, v6); backsplash top v13-15 with the
// exhaust grille (fryer B's), which glows in top_on — the oven vents through it, so the hob itself stays unlit.
function top(on) {
  const c = C();
  rect(c, 0, 0, 16, 16, 'b');
  rect(c, 0, 0, 16, 1, '6'); speck(c, 0, 0, 16, 1, '6', '5', 0.35, 41);      // lip: fryer B's top lip row
  rect(c, 0, 1, 16, 1, '4');
  for (let v = 1; v <= 12; v++) { px(c, 0, v, '5'); px(c, 15, v, '5'); }
  rect(c, 0, 12, 16, 1, '4');
  px(c, 0, 1, '5'); px(c, 15, 1, '5');
  speck(c, 1, 2, 14, 10, 'b', 'c', 0.18, 42);
  for (const u0 of [2, 9]) {
    rows(c, u0, 4, [
      ' aaa ',
      'afgfa',
      'agiga',
      'afgfa',
      ' aaa ']);
  }
  // backsplash top: fryer B's rows (5 / grille / 3)
  rect(c, 0, 13, 16, 1, '5');
  rows(c, 0, 14, [on ? '4D2D2E2E2E2D2D24' : '4a2a2a2a2a2a2a24']);
  rect(c, 0, 15, 16, 1, '3');
  return c;
}
T.top = top(false);
T.top_on = top(true);

// GRATE — one continuous cast-iron pan support 14×7 (commercial range: two 7×7 sections sharing a centre bar), cutout.
// Up face u0-13 v0-6: bright frame (FD's lightest pot grey) + darker fingers, open over each burner head. Side strip u0-13 v7.
{
  const c = C();
  const half = [
    'iiiiiii',
    'i..h..i',
    'i.....i',
    'ih...hi',
    'i.....i',
    'i..h..i',
    'iiiiiii'];
  rows(c, 0, 0, half); rows(c, 7, 0, half);
  rows(c, 0, 7, ['fgfgfgfgfgfgfg']);
  T.grate = c;
}

// SIDE (west face as seen: u = z, front on the LEFT; east faces use mirrored uv)
//   v0-4 u13-15 backsplash side | v5 lip (u0) + hob rim band | v6-13 cabinet side (y 2-10)
// Pixel-for-pixel fryer B's fryer_b_side.png (checked at the end of this script): same rim band, lit front edge, shadowed back
// edge and bottom row, the two louvres low at the back.
{
  const c = C();
  rect(c, 0, 0, 16, 16, '4');
  // backsplash side
  rect(c, 13, 0, 3, 1, '5'); for (let v = 1; v <= 4; v++) px(c, 13, v, '5');
  speck(c, 14, 1, 2, 4, '4', '3', 0.3, 21);
  // rim band: the bright deck edge
  rect(c, 0, 5, 16, 1, '6'); speck(c, 0, 5, 16, 1, '6', '5', 0.3, 22);
  // cabinet side panel: light front edge, shadow back edge + bottom
  for (let v = 6; v <= 13; v++) { px(c, 1, v, '5'); px(c, 15, v, '3'); }
  rect(c, 1, 13, 15, 1, '2');
  speck(c, 2, 6, 13, 7, '4', '3', 0.14, 23);
  // louvre vents low at the back
  for (const v of [8, 10]) { rect(c, 10, v, 4, 1, '5'); rect(c, 10, v + 1, 4, 1, '1'); }
  rect(c, 0, 14, 16, 2, '1');
  T.side = c;
}

// BACK (south face: u = x). v0-4 backsplash back (two flue louvres, as fryer B) | v5 seam | v6-13 cabinet back
// (service panel + copper maker's plate, as fryer B). Same tones as fryer B's back: base 3, lit top row 4, specks 2.
{
  const c = C();
  rect(c, 0, 0, 16, 16, '3');
  rect(c, 0, 0, 16, 1, '4');
  speck(c, 0, 1, 16, 13, '3', '2', 0.12, 31);
  for (const v of [2, 4]) { rect(c, 3, v, 10, 1, 'a'); rect(c, 3, v - 1, 10, 1, '4'); }
  rect(c, 0, 5, 16, 1, '2');
  for (let u = 2; u <= 13; u++) { px(c, u, 7, '4'); px(c, u, 12, '2'); }
  for (let v = 7; v <= 12; v++) { px(c, 2, v, '4'); px(c, 13, v, '2'); }
  rows(c, 9, 9, ['Ta', 'UR']);
  rect(c, 0, 14, 16, 2, '1');
  T.back = c;
}

// BOTTOM (down face of the cabinet)
{
  const c = C();
  rect(c, 0, 0, 16, 16, 'd');
  speck(c, 0, 0, 16, 16, 'd', 'e', 0.2, 51);
  for (let i = 0; i < 16; i++) { px(c, i, 0, '2'); px(c, i, 15, '2'); px(c, 0, i, '2'); px(c, 15, i, '2'); }
  T.bottom = c;
}

// PARTS — one row per part, nothing shares a row:
//   v0 handle front u0-11 (u0 = east end) + end cap u12 + bracket top u13 + bracket sides u14 | v1 handle top | v2 handle bottom / back
//   v3 lip underside | v4-5 leg side u0-1, leg bottom u2-3 | v6 door bottom edge u0-13 | u15 v6-13 door sides
{
  const c = C();
  // copper bar: FD pot-handle base #9b5643 with only a pixel or two of the #b1624d highlight (fryer B's copper), dark ends
  rows(c, 0, 0, ['USRRRRRRRRRUU43']);
  rows(c, 0, 1, ['RSRRRRRRRRSR']);          // highlights right above the two brackets (x3 / x12)
  rect(c, 0, 2, 12, 1, 'T');
  rect(c, 0, 3, 16, 1, 'b');                // lip underside (fryer B's rim underside)
  rows(c, 0, 4, ['33bb', 'bbbb']);          // legs: fryer B's (steel over a dark rubber foot)
  rect(c, 0, 6, 14, 1, '2');
  for (let v = 6; v <= 13; v++) px(c, 15, v, '3');
  T.parts = c;
}

for (const [n, c] of Object.entries(T)) write(c, texFile(n));
json(`textures/block/${P}_glass_on.png.mcmeta`, { animation: { frametime: 3, interpolate: false } });

// ---------------------------------------------------------------- model helpers
// default (vanilla) uv per face; east faces mirrored so side textures keep "front on the left = front"
function uvOf(dir, f, t) {
  const [x1, y1, z1] = f, [x2, y2, z2] = t;
  switch (dir) {
    case 'north': return [16 - x2, 16 - y2, 16 - x1, 16 - y1];
    case 'south': return [x1, 16 - y2, x2, 16 - y1];
    case 'west': return [z1, 16 - y2, z2, 16 - y1];
    case 'east': return [z2, 16 - y2, z1, 16 - y1];
    case 'up': return [x1, z1, x2, z2];
    case 'down': return [x1, 16 - z2, x2, 16 - z1];
  }
}
// cullface only when the face lies on the block boundary AND stays inside the block on the other two axes
const inside = (f, t, axes) => axes.every(k => f[k] >= 0 && t[k] <= 16);
const CULL = {
  north: (f, t) => f[2] === 0 && inside(f, t, [0, 1]), south: (f, t) => t[2] === 16 && inside(f, t, [0, 1]),
  west: (f, t) => f[0] === 0 && inside(f, t, [1, 2]), east: (f, t) => t[0] === 16 && inside(f, t, [1, 2]),
  up: (f, t) => t[1] === 16 && inside(f, t, [0, 2]), down: (f, t) => f[1] === 0 && inside(f, t, [0, 2]),
};
function el(name, from, to, faces, extra = {}) {
  const out = { name, from, to, ...extra, faces: {} };
  for (const [d, spec] of Object.entries(faces)) {
    const s = typeof spec === 'string' ? { texture: spec } : Array.isArray(spec) ? { texture: spec[0], uv: spec[1] } : { ...spec };
    const face = { uv: s.uv || uvOf(d, from, to), texture: s.texture };
    if (s.rotation) face.rotation = s.rotation;
    // zero-thickness inner walls sit on x=1 / x=15 etc., never on the block boundary; boundary faces of real boxes get culled
    if (CULL[d](from, to) && !s.noCull) face.cullface = d;
    out.faces[d] = face;
  }
  return out;
}

// ---------------------------------------------------------------- geometry (one set; the active model inherits it)
function elements() {
  const E = [], lit = { shade: false };
  // legs (as fryer B)
  for (const [x, z, n] of [[1, 2, 'leg front west'], [13, 2, 'leg front east'], [1, 13, 'leg back west'], [13, 13, 'leg back east']])
    E.push(el(n, [x, 0, z], [x + 2, 2, z + 2], { north: ['#parts', [0, 4, 2, 6]], south: ['#parts', [0, 4, 2, 6]], west: ['#parts', [0, 4, 2, 6]], east: ['#parts', [0, 4, 2, 6]], down: ['#parts', [2, 4, 4, 6]] }));
  // oven cabinet: front inset to z=1, top is the hob (y=11)
  E.push(el('cabinet', [0, 2, 1], [16, 11, 16], { north: '#front', south: '#back', west: '#side', east: '#side', up: '#top', down: ['#bottom', [0, 0, 16, 15]] }));
  // front lip of the hob (same line as fryer B's deck lip)
  E.push(el('lip', [0, 10, 0], [16, 11, 1], { north: '#front', up: '#top', down: ['#parts', [0, 3, 16, 4]], west: '#side', east: '#side' }));
  // oven door slab with a window hole, glass plane 0.5px behind its face (glass always full-bright: dark when idle, fire when on)
  E.push(el('oven door', [1, 2, 0], [15, 10, 1], { north: '#front', down: ['#parts', [0, 6, 14, 7]], west: ['#parts', [15, 6, 16, 14]], east: ['#parts', [15, 6, 16, 14]] }));
  E.push(el('window glass', [2, 2, 0.5], [14, 10, 0.5], { north: ['#glass', [0, 0, 12, 8]] }, lit));
  // copper door handle on two steel brackets, in front of the vent slot along the top of the door
  E.push(el('door handle', [2, 9, -2], [14, 10, -1], { north: ['#parts', [0, 0, 12, 1]], south: ['#parts', [0, 2, 12, 3]], up: ['#parts', [0, 1, 12, 2]], down: ['#parts', [0, 2, 12, 3]], west: ['#parts', [12, 0, 13, 1]], east: ['#parts', [12, 0, 13, 1]] }));
  for (const [x, n] of [[12, 'handle bracket east'], [3, 'handle bracket west']])
    E.push(el(n, [x, 9, -1], [x + 1, 10, 0], { up: ['#parts', [13, 0, 14, 1]], down: ['#parts', [14, 0, 15, 1]], west: ['#parts', [14, 0, 15, 1]], east: ['#parts', [14, 0, 15, 1]] }));
  // one continuous cast-iron grate over both burners (x 1-15, z 3-10, 1px tall) ...
  const SIDE14 = ['#grate', [0, 7, 14, 8]], SIDE7 = ['#grate', [0, 7, 7, 8]];
  E.push(el('grate', [1, 11, 3], [15, 12, 10], { up: ['#grate', [0, 0, 14, 7]], north: SIDE14, south: SIDE14, west: SIDE7, east: SIDE7 }));
  // ... plus its four inward-facing walls: a ray entering a grate hole at a low angle now hits the inside of the frame instead of
  // leaving through the (back-face-culled) outer walls and showing whatever is beyond the block
  E.push(el('grate inner west', [1, 11, 3], [1, 12, 10], { east: SIDE7 }));
  E.push(el('grate inner east', [15, 11, 3], [15, 12, 10], { west: SIDE7 }));
  E.push(el('grate inner front', [1, 11, 3], [15, 12, 3], { south: SIDE14 }));
  E.push(el('grate inner back', [1, 11, 10], [15, 12, 10], { north: SIDE14 }));
  // backsplash: fryer B's steel frame round a dark recess
  E.push(el('backsplash', [0, 11, 13], [16, 16, 16], { north: ['#front', [0, 0, 16, 5]], south: ['#back', [0, 0, 16, 5]], west: '#side', east: '#side', up: '#top' }));
  for (const [x, n] of KNOBS)
    E.push(el(n, [x, 11.5, 12], [x + 2, 13.5, 13], { north: ['#dial', [0, 0, 2, 2]], west: ['#dial', [2, 0, 3, 2]], east: ['#dial', [2, 0, 3, 2]], up: ['#dial', [0, 2, 2, 3]], down: ['#dial', [0, 2, 2, 3]] }));
  for (const [x, n] of LAMPS)
    E.push(el(n, [x, 14, 12.5], [x + 1, 15, 13], { north: ['#dial', [4, 0, 5, 1]], west: ['#dial', [5, 0, 5.5, 1]], east: ['#dial', [5, 0, 5.5, 1]], up: ['#dial', [4, 1, 5, 1.5]], down: ['#dial', [4, 1, 5, 1.5]] }, lit));
  // oven thermometer: fryer B's round 4×4 gauge at the same spot (x6-10, y11-15), standing on the hob. Like the fryer's, no side
  // faces (the dial is round, its corners are cut out) and no down face (it sits on the hob).
  E.push(el('oven thermometer', [6, 11, 12.5], [10, 15, 13], { north: ['#dial', [8, 0, 12, 4]], up: ['#dial', [8, 4, 12, 4.5]] }));
  return E;
}

const textures = {
  particle: tex('side'), front: tex('front'), side: tex('side'), back: tex('back'), top: tex('top'), bottom: tex('bottom'),
  glass: tex('glass'), parts: tex('parts'), dial: tex('dial'), grate: tex('grate'),
};
const els = elements();
json(`models/block/${P}.json`, { parent: 'minecraft:block/block', render_type: 'minecraft:cutout', textures, elements: els });
json(`models/block/${P}_on.json`, { parent: `${NS}:block/${P}`, textures: { glass: tex('glass_on'), dial: tex('dial_on'), top: tex('top_on') } });
json(`models/item/${P}.json`, { parent: `${NS}:block/${P}` });
const variants = {};
for (const [facing, y] of [['east', 90], ['north', 0], ['south', 180], ['west', 270]])
  for (const lit of [false, true]) variants[`facing=${facing},lit=${lit}`] = { model: `${NS}:block/${P}${lit ? '_on' : ''}`, ...(y ? { y } : {}) };
json(`blockstates/${P}.json`, { variants });

// ---------------------------------------------------------------- meta
const nFaces = els.reduce((n, e) => n + Object.keys(e.faces).length, 0);
json('meta.json', {
  key: 'oven_b', tag: '烤炉 B', order: 12,
  name: '铸铁烤箱灶',
  tagline: '和商用炸炉同一条产品线的整格烤箱灶：下面是带观察窗的烤箱，上面一整块铸铁炉架，背板深色面板上两组旋钮 + 指示灯夹一只炉温表。',
  description: '整格方块的烤箱灶，骨架照搬商用炸炉 B：同样的四条 2 像素支脚（钢 + 深色橡胶脚）、y10–11 的前沿、顶到方块顶的背板和背板顶的排气栅，两台并排时台面、背板和炉温表都齐平。' +
    '下半是一扇 14×8 的不锈钢烤箱门，门上沿是一条深色排气缝（和炸炉前沿下的阴影行同一位置），门里开 10×5 的观察窗，四周留 1 像素钢框，玻璃比门面凹进半像素；' +
    '门顶横一根铜红色把手，架在两只钢托上、伸出正面 2 像素。灶面是铸铁黑板，上面一整块 14×7 的连体铸铁炉架（两段各罩一只炉头），炉架用农夫乐事锅具里最亮的灰，从灶面上浮出来。' +
    '背板正面和炸炉的一模一样：钢框里一块农夫乐事深灰的内凹面板；面板上左右各一个旋钮、上方一盏带灯座的琥珀指示灯，中间一只圆形白底炉温表，位置和炸炉的油温表完全相同。' +
    '待机时观察窗里黑着，只有一层空烤架和玻璃上两道反光；工作中观察窗整面发亮：炉膛后壁被火映成橙色，一只金黄的派坐在深色烤盘上，烤盘下一排火苗跳动（燃气烤箱，只有火苗在动）；' +
    '同时两盏指示灯亮、炉温表指针打进红区、旋钮转过去，背板顶的排气栅从缝里透出火光（从上往下看也能分清开没开）。' +
    '配色：不锈钢梯度、用法都和炸炉 B 相同（底色 #87878b，受光倒角 #9a9a9e，最亮的 #aeaeb2 只用在前沿 / 台沿 1 像素高光上）；侧面、旋钮 / 指示灯 / 表盘贴图和炸炉的逐像素相同；灶面、炉架用农夫乐事锅具的冷深灰；强调色是农夫乐事厨锅把手的铜红（门把手 + 背面铭牌，色值同炸炉）；火光用农夫乐事炉灶的火色。',
  notes: [
    '朝向与状态：正面（烤箱门）画在 north。随方案附 blockstates/oven_b.json：facing=north/east/south/west 对应 y=0/90/180/270，lit=false/true 对应 oven_b / oven_b_on（状态名用 lit，见最后一条）。另附 models/item/oven_b.json（parent=方块模型）。自带热源，直接放地上用。',
    '方块属性必须加 .noOcclusion()（可再加 .isViewBlocking((s,l,p)->false)）：模型不是满格（支脚下 y0–2、灶面上方 y12–16、门两侧凹口都是空的），不加的话邻格贴着它的面会被剔掉、模型内部的面在平滑光照下发黑。碰撞箱用整格 0–16；选择框可用 Shapes.or(box(0,0,0,16,12,16), box(0,12,13,16,16,16)) 贴着灶面和背板。门把手（伸出正面 2 像素）不进任何 shape。',
    '工作中模型 oven_b_on 只有 parent=oven_b 和三张贴图覆盖：glass→glass_on、dial→dial_on、top→top_on，不带 elements，几何完全继承待机模型，切换不会跳。观察窗玻璃和两盏指示灯在待机模型里就是 shade:false（待机时它们本来就暗，全亮看不出差别），所以工作中不用另写一套元素。',
    '观察窗 oven_b_glass_on：16×96 竖条，6 帧 × 3 tick，不插值（一圈 0.9 秒，和农夫乐事炉灶正面的火同一节奏）。只有底排火苗在动：每帧换位，每帧都有一舌火苗窜过烤盘的一端；炉膛后壁、派、烤盘是静止的。其余贴图都是静态的。',
    `元素 ${els.length} 个、${nFaces} 个面（4 支脚、柜体、前沿、门、玻璃、把手 + 2 托、1 整块炉架 + 4 片朝内的炉架内壁、背板、2 旋钮、2 指示灯、炉温表）；12 张 16×16 贴图（1 张动画）；render_type cutout（门上的窗洞、炉架镂空）。外形 宽16×深18×高16（含伸出正面 2 像素的把手），没有东西伸出方块顶。`,
    '发光：给玻璃和两盏指示灯加 Forge 的 forge_data block_light，或让方块在 lit=true 时 lightLevel 10–13。shade:false 只是不变暗，本身不发光。',
    '粒子在游戏里另加：工作中热气 / 烟从背板顶排气栅 (8, 16, 14.5) 出（和炸炉 B 同一个位置），门顶排气缝 (8, 9.5, -0.2) 偶尔冒一缕热气。',
    '炉架目前只是造型。若想让顶上能放农夫乐事厨锅 / 煎锅，可把方块加进 farmersdelight:heat_sources 标签：农夫乐事判断时，标签里的方块有 lit 属性就看 lit，没有就一直算热，所以状态属性必须叫 lit（否则待机也算热源）；另外锅放上去底面在 y16，比炉架顶 y12 悬空 4 像素。这条行为需要进游戏实测。',
  ],
  hires: false,
  namespace: NS,
  blocks: [
    { label: '铸铁烤箱灶', pos: [0, 0, 0], idle: { model: P, y: 0 }, active: { model: `${P}_on`, y: 0 } },
  ],
});
console.log('oven B written to', dir, ' elements', els.length, 'faces', nFaces);

// ---------------------------------------------------------------- family drift check (read-only look at fryer B)
{
  const fryerDir = path.resolve(dir, '../../designs/B');
  const warn = m => console.log('  WARN family drift: ' + m);
  try {
    const src = fs.readFileSync(path.join(fryerDir, 'gen.mjs'), 'utf8');
    // the fryer's stainless ramp line ("1: '#…', 2: '#…', …") and its copper line ("R: '#…', S: …")
    const line = src.split('\n').find(l => /^\s*[0-9]:\s*'#[0-9a-f]{6}',\s*[0-9]:/.test(l));
    const theirs = line ? Object.fromEntries([...line.matchAll(/(\d):\s*'(#[0-9a-f]{6})'/g)].map(m => [m[1], m[2]])) : null;
    if (!theirs) warn('could not find the stainless ramp line in designs/B/gen.mjs');
    else for (const k of new Set([...Object.keys(FAMILY), ...Object.keys(theirs)])) if (theirs[k] !== FAMILY[k]) warn(`stainless ${k}: oven ${FAMILY[k]} vs fryer ${theirs[k]}`);
    const cu = src.split('\n').find(l => /^\s*R:\s*'#[0-9a-f]{6}',\s*S:/.test(l));
    if (!cu) warn('could not find the copper line in designs/B/gen.mjs');
    else for (const m of cu.matchAll(/([RSTU]):\s*'(#[0-9a-f]{6})'/g)) if (PAL[m[1]] !== m[2]) warn(`copper ${m[1]}: oven ${PAL[m[1]]} vs fryer ${m[2]}`);
    for (const n of ['dial', 'dial_on', 'side', 'bottom']) {
      const f = path.join(fryerDir, 'textures/block', `fryer_b_${n}.png`);
      if (!fs.existsSync(f)) { warn(`missing ${f}`); continue; }
      const a = read(f), b = T[n]; let diff = 0;
      for (let y = 0; y < 16; y++) for (let x = 0; x < 16; x++) if (hex(getPx(a, x, y)) !== hex(getPx(b, x, y))) diff++;
      if (diff) warn(`${n}: ${diff} px differ from fryer_b_${n}.png`);
    }
    console.log('family check vs designs/B done');
  } catch (e) { warn(e.message); }
}
