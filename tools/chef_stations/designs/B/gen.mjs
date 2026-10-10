// 方案 B「商用炸炉」生成脚本（第 2 版，按审查意见改）：逐像素画贴图 + 写原版方块模型 JSON + blockstate + 物品模型 + meta.json。
//   cd <仓库>\tools\chef_stations && node designs/B/gen.mjs
// 产出（路径与主 MOD assets/miningdim/ 一致，定稿后 blockstates/ models/ textures/ 可直接拷）：
//   models/block/fryer_b_idle.json     待机：两只炸篮斜挂在背板挂篮杆上沥油（绕挂钩 -22.5°，前低后高），油面静止，指示灯灭
//   models/block/fryer_b_active.json   工作中：parent=待机（继承贴图），自带一套元素：炸篮落进油缸、篮沿高出台沿 1 像素，
//                                      篮里冒出 3D 薯条 / 炸块；覆盖 oil→oil_on（6 帧油沫/气泡）、dial→dial_on（灯亮、旋钮转开、指针进红区）
//   models/item/fryer_b.json           物品模型（parent=待机）
//   blockstates/fryer_b.json           facing(4) × active(2) 共 8 个变体
//   textures/block/fryer_b_*.png (+ oil_on.png.mcmeta)
// 坐标约定：正面 = north（z=0），从正面看 +x 在左边；顶面贴图行 = z、列 = x。
// 正面贴图 u 和 x 的关系：u = 15 - x（x 这一列像素在正面贴图第 15-x 列）。
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { makeCanvas, setPx, getPx, strip, write, rgba } from '../../lib/png.mjs';

const dir = path.dirname(fileURLToPath(import.meta.url));
const NS = 'miningdim', P = 'fryer_b_', BLOCK = 'fryer_b';
const texFile = n => path.join(dir, 'textures/block', P + n + '.png');
const tex = n => `${NS}:block/${P}${n}`;
const json = (rel, o) => { const f = path.join(dir, rel); fs.mkdirSync(path.dirname(f), { recursive: true }); fs.writeFileSync(f, JSON.stringify(o, null, 2) + '\n'); };

// ---------------------------------------------------------------- palette
// 不锈钢：比第 1 版整体降一档（底色 #87878b、暗部 #626266），最亮的 #aeaeb2 只用在台沿 / 唇边 / 篮沿这 1 像素高光上。
// 控制面板、背板内凹面直接用农夫乐事深灰；把手用农夫乐事厨锅把手的铜红（#9b5643 / 高光 #b1624d）。
const PAL = {
  // stainless
  1: '#4f4f53', 2: '#626266', 3: '#747478', 4: '#87878b', 5: '#9a9a9e', 6: '#aeaeb2',
  // FD dark greys (panel, backsplash recess)
  a: '#1f1f21', b: '#27272b', c: '#2d2d32', d: '#343438', e: '#3f3e42', f: '#494848', g: '#595858',
  // oil (golden) / foam / food
  o: '#6b4118', p: '#8a5a22', r: '#c18a35', s: '#d9a441', t: '#ecc76a', u: '#f6e6b4', v: '#e8b548', w: '#b3702a',
  // FD copper red (cooking pot handle)
  R: '#9b5643', S: '#b1624d', T: '#733f31', U: '#8f503f',
  // lamp / gauge
  M: '#ffe08a', N: '#f5a623', W: '#e4e1d8', K: '#1f1f21', H: '#c35d1b',
};
const col = ch => { if (!(ch in PAL)) throw new Error('palette: ' + ch); return PAL[ch]; };
const C = () => makeCanvas(16, 16);
const px = (c, x, y, ch) => { if (ch !== '.') setPx(c, x, y, col(ch)); else setPx(c, x, y, [0, 0, 0, 0]); };
const rect = (c, x, y, w, h, ch) => { for (let j = y; j < y + h; j++) for (let i = x; i < x + w; i++) px(c, i, j, ch); };
const rows = (c, x, y, lines) => lines.forEach((l, j) => [...l].forEach((ch, i) => { if (ch !== ' ') px(c, x + i, y + j, ch); }));
// deterministic sprinkle: swap pixels that currently are `from` to `to` at the given density (FD-style 2-tone mottling)
function rng(seed) { let s = seed >>> 0; return () => { s = (s + 0x6d2b79f5) >>> 0; let t = s; t = Math.imul(t ^ (t >>> 15), t | 1); t ^= t + Math.imul(t ^ (t >>> 7), t | 61); return ((t ^ (t >>> 14)) >>> 0) / 4294967296; }; }
const same = (c, x, y, ch) => { const p = getPx(c, x, y), q = rgba(col(ch)); return p[0] === q[0] && p[1] === q[1] && p[2] === q[2] && p[3] === 255; };
function speck(c, x, y, w, h, from, to, density, seed) {
  const R = rng(seed);
  for (let j = y; j < y + h; j++) for (let i = x; i < x + w; i++) { const k = R(); if (k < density && same(c, i, j, from)) px(c, i, j, to); }
}

// ---------------------------------------------------------------- layout constants (block pixels)
const OIL_Y = 10, RIM_Y = 11;                 // oil surface / deck rim: wells are only 1 px deep, so the hot oil shows from most angles
const WELLS = [[1, 7], [9, 15]];              // x ranges of the two wells (z 2-12)
const BASKET_X = { west: 2, east: 10 };       // baskets are 4 wide (x0..x0+4), 7 long (z 4-11)
const LAMP_X = { west: 1, east: 9 };          // per vat: lamp, then knob 3 px further +x  → as seen: knob left, lamp right, both vats alike
const KNOB_X = { west: 4, east: 12 };
const ux = x => 15 - x;                       // front-texture column of block column x

// ---------------------------------------------------------------- textures
const T = {};

// FRONT (north face, as seen). v0-4 backsplash front (y 11-16): dark recessed panel in a steel frame
// | v5 lip (y 10-11) | v6-8 control panel (y 7-10) | v9-13 cabinet door (y 2-7) | v14-15 unused (legs are separate elements).
{
  const c = C();
  // backsplash: steel frame, FD dark-grey recess so the wire baskets and the white gauge stand out in front of it
  rect(c, 0, 0, 16, 5, 'd'); speck(c, 1, 2, 14, 3, 'd', 'e', 0.25, 11);
  rect(c, 0, 0, 16, 1, '4'); rect(c, 1, 1, 14, 1, 'c');
  for (let v = 0; v <= 4; v++) { px(c, 0, v, '4'); px(c, 15, v, '3'); }
  // lip: the one bright rounded edge of the deck
  rect(c, 0, 5, 16, 1, '6'); speck(c, 0, 5, 16, 1, '6', '5', 0.3, 12);
  // control panel: shadow row under the lip, then two rows; per vat only a lamp bezel (the knob is an element)
  rect(c, 0, 6, 16, 1, 'b'); rect(c, 0, 7, 16, 1, 'c'); rect(c, 0, 8, 16, 1, 'd');
  for (const x of Object.values(LAMP_X)) { const u = ux(x); rect(c, u - 1, 6, 3, 3, 'f'); }
  // one big door: steel frame columns, door panel with highlight top/left and shadow right/bottom
  rect(c, 0, 9, 16, 5, '4');
  for (let v = 9; v <= 13; v++) { px(c, 0, v, '3'); px(c, 15, v, '3'); }
  for (let u = 1; u <= 14; u++) { px(c, u, 9, '5'); px(c, u, 13, '2'); }
  for (let v = 9; v <= 13; v++) { px(c, 1, v, '5'); px(c, 14, v, '3'); }
  px(c, 14, 9, '4'); px(c, 1, 13, '3');
  speck(c, 2, 10, 12, 3, '4', '3', 0.18, 13);
  rect(c, 0, 14, 16, 2, '1');
  T.front = c;
}

// SIDE (west face as seen: u = z, front on the LEFT; east faces use mirrored uv so the front stays at the front)
// v0-4 u13-15 backsplash side | v5 rim band (y 10-11, all u) | v6-13 u1-15 cabinet side (y 2-10)
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

// BACK (south face: u = x). v0-5 backsplash back (y 10-16) | v6-13 cabinet back (y 2-10)
{
  const c = C();
  rect(c, 0, 0, 16, 16, '3');
  rect(c, 0, 0, 16, 1, '4');
  speck(c, 0, 1, 16, 13, '3', '2', 0.12, 31);
  // flue louvres high on the backsplash
  for (const v of [2, 4]) { rect(c, 3, v, 10, 1, 'a'); rect(c, 3, v - 1, 10, 1, '4'); }
  rect(c, 0, 6, 16, 1, '2');
  // service panel + copper gas/electric inlet
  for (let u = 2; u <= 13; u++) { px(c, u, 7, '4'); px(c, u, 12, '2'); }
  for (let v = 7; v <= 12; v++) { px(c, 2, v, '4'); px(c, 13, v, '2'); }
  rows(c, 9, 9, ['Ta', 'UR']);
  rect(c, 0, 14, 16, 2, '1');
  T.back = c;
}

// TOP (u = x, v = z; row 0 = front). Lip v0, front rim v1, side rims u0/u15 + divider u7-8 (v2-11), back rim v12,
// backsplash top v13-15 with the flue grille. Only rim / backsplash pixels are sampled (the wells have their own oil planes).
{
  const c = C();
  rect(c, 0, 0, 16, 16, 'c');
  rect(c, 0, 0, 16, 1, '6'); speck(c, 0, 0, 16, 1, '6', '5', 0.35, 41);
  rect(c, 0, 1, 16, 1, '4');
  for (let v = 2; v <= 11; v++) { px(c, 0, v, '5'); px(c, 15, v, '5'); px(c, 7, v, '5'); px(c, 8, v, '4'); }
  rect(c, 0, 12, 16, 1, '4');
  rect(c, 0, 13, 16, 1, '5');
  rows(c, 0, 14, ['4a2a2a2a2a2a2a24']);
  rect(c, 0, 15, 16, 1, '3');
  T.top = c;
}

// BOTTOM (down face of the cabinet)
{
  const c = C();
  rect(c, 0, 0, 16, 16, 'd');
  speck(c, 0, 0, 16, 16, 'd', 'e', 0.2, 51);
  for (let i = 0; i < 16; i++) { px(c, i, 0, '2'); px(c, i, 15, '2'); px(c, 0, i, '2'); px(c, 15, i, '2'); }
  T.bottom = c;
}

// OIL — up face of the two oil planes at y=10 (u = x, v = z). Wells u1-6 / u9-14, v2-11. Lowered baskets: u2-5 / u10-13, v4-10.
// Hot golden oil (#d9a441), 1 px darker edge along the walls, a few #ecc76a glints. ≤ 3 colours per vat.
const WELL_UV = WELLS.map(([x0, x1]) => [x0, x1 - 1]);
function oilBase(c) {
  rect(c, 0, 0, 16, 16, 'p');
  for (const [u0, u1] of WELL_UV) rect(c, u0, 2, u1 - u0 + 1, 10, 's');
}
{
  const c = C();
  oilBase(c);
  for (const [u0, u1] of WELL_UV) {
    for (let v = 2; v <= 11; v++) { px(c, u0, v, 'r'); px(c, u1, v, 'r'); }
    for (let u = u0; u <= u1; u++) px(c, u, 2, 'r');
    rows(c, u0 + 2, 4, ['t', ' t']);
    rows(c, u0 + 1, 8, [' t', 't ']);
  }
  T.oil = c;
}
// Active oil: 6 frames × 3 tick. Per vat ≤ 5 colours. Gap around the basket = golden oil with a cream foam dash marching round
// the ring (each frame shifts it one step, ~1/3 of the ring pixels change); inside the basket darker oil with the food;
// bubbles at fixed spots, never on food: forming (dim dot) → popping (cream) → gone.
const FRAMES = 6;
// food layout (texture cells). East basket (seen on the LEFT from the front) = fries; west basket (seen on the right) = chunks.
// 3D food elements stand on some cells (see food()); the 2D pieces lie between them.
// standing fries: [x, z, rotation axis, angle] — 1×1×3 sticks leaning 22.5° about their foot, poking 1 px over the basket rim
const FRIES_3D = [[11, 5, 'z', 22.5], [12, 7, 'x', -22.5], [12, 9, 'z', -22.5]];
// battered chunks: [centre x, centre z, top y, y-rotation] — 2×2 lumps turned ±22.5° about y so they read as lumps, not cubes
const CHUNKS_3D = [[4, 6, 12.5, 22.5], [4, 9, 12, -22.5]];
const FOOD_2D = [
  // lying fries: 1×3 pale-gold sticks, browned tip
  [10, 6, 't'], [10, 7, 't'], [10, 8, 'r'],
  [13, 8, 't'], [13, 9, 't'], [13, 10, 'r'],
  [12, 4, 'r'], [13, 4, 't'],
  // a small battered piece next to the chunks
  [5, 10, 'v'], [4, 10, 'w'],
];
const BUBBLES = [                                         // [u, v, first frame, forming colour]
  [12, 10, 0, 'r'], [10, 4, 3, 'r'],                      // fries vat
  [5, 4, 1, 'w'], [2, 7, 4, 'w'],                         // chunks vat
];
{
  const occupied = new Set([...FOOD_2D.map(([u, v]) => u + ',' + v),
    ...FRIES_3D.map(([x, z]) => x + ',' + z), ...CHUNKS_3D.flatMap(([x, z]) => [[x - 1, z - 1], [x, z - 1], [x - 1, z], [x, z]].map(p => p.join(',')))]);
  for (const [u, v] of BUBBLES) if (occupied.has(u + ',' + v)) throw new Error(`bubble ${u},${v} sits on food`);
  const frames = [];
  for (let f = 0; f < FRAMES; f++) {
    const c = C();
    oilBase(c);
    WELL_UV.forEach(([u0, u1], k) => {
      rect(c, u0 + 1, 4, 4, 7, 'p');                       // oil inside the basket reads darker (shadowed by the mesh)
      // foam dash: ring of the well minus its 4 corners (24 cells), pattern "uu····" shifted one cell per frame
      const ring = [];
      for (let u = u0 + 1; u < u1; u++) ring.push([u, 2]);
      for (let v = 3; v < 11; v++) ring.push([u1, v]);
      for (let u = u1 - 1; u > u0; u--) ring.push([u, 11]);
      for (let v = 10; v > 2; v--) ring.push([u0, v]);
      ring.forEach(([u, v], i) => { if (((i - f + k * 3) % 6 + 6) % 6 < 2) px(c, u, v, 'u'); });
    });
    for (const [u, v, ch] of FOOD_2D) px(c, u, v, ch);
    for (const [u, v, f0, dim] of BUBBLES) { const ph = (f - f0 + FRAMES) % FRAMES; if (ph === 0) px(c, u, v, dim); else if (ph === 1) px(c, u, v, 'u'); }
    frames.push(c);
  }
  T.oil_on = strip(frames);
}

// DIAL — knob (2×2 front), lamp (1×1), oil-temperature gauge (4×4, corners cut = round). *_on = lamp lit, knob turned, needle in the red.
function dial(on) {
  const c = C();
  // knob front u0-1 v0-1 (dark grey, 1 px mid-grey pointer: off = top-left, on = top-right; bottom row identical), sides u2 v0-1, top/bottom u0-1 v2
  rows(c, 0, 0, [on ? 'g4' : '4g', 'ef']);
  rows(c, 2, 0, ['f', 'e']); rows(c, 0, 2, ['gg']);
  // lamp front u4 v0, sides u5 v0, top/bottom u4 v1. Off = dark amber (still reads as a lamp inside its bezel), on = bright
  px(c, 4, 0, on ? 'M' : 'o'); px(c, 5, 0, on ? 'N' : 'p'); px(c, 4, 1, on ? 'N' : 'p');
  // gauge face u8-11 v0-3: white dial, orange→red arc over the top-right, grey pivot fixed at (1,2); one black needle pixel:
  // off → pointing left (cold), on → up-right into the red. Only one black pixel, so it never reads as a pair of eyes.
  rows(c, 8, 0, on ? ['.NH.', 'WWKH', 'WeWW', '.WW.'] : ['.NH.', 'WWWH', 'KeWW', '.WW.']);
  rows(c, 8, 4, ['.ee.']);                                         // top / bottom rim (0.5 px)
  return c;
}
T.dial = dial(false);
T.dial_on = dial(true);

// PARTS — hanger rod, well walls, legs, door handle, basket handle, hook, rim underside, food
{
  const c = C();
  // hanger rod (1×1×14): north v0, up v1, down v2, end u14 v0
  rect(c, 0, 0, 14, 1, '4'); rect(c, 0, 1, 14, 1, '5'); rect(c, 0, 2, 14, 1, '2'); px(c, 14, 0, '3');
  // inner well walls (1 px tall): steel, oil-darkened here and there
  rect(c, 0, 3, 16, 1, '3'); speck(c, 0, 3, 16, 1, '3', '2', 0.3, 61);
  // leg side 2×2 u0-1 v4-5 (steel over a dark rubber foot), leg bottom u2-3 v4-5
  rows(c, 0, 4, ['33bb', 'bbbb']);
  // door handle bar (6×1×1): north u4-9 v4, up u4-9 v5, down u4-9 v6, ends u10 v4
  rect(c, 4, 4, 6, 1, '5'); px(c, 4, 4, '4'); px(c, 9, 4, '4'); rect(c, 4, 5, 6, 1, '5'); rect(c, 4, 6, 6, 1, '2'); px(c, 10, 4, '3');
  // food (active only): fry side u11 v4-7 (3-tall fries use v5-7), fry top u10 v7; chunk side u12-13 v4-6 + top u14-15 v4-5
  rows(c, 11, 4, ['t', 't', 'v', 'r']); px(c, 10, 7, 't');          // sides: pale tip, golden middle, browned foot (stays golden in shade)
  rows(c, 12, 4, ['vvvv', 'vwvw', 'ww']);
  // basket handle (2 wide × 1 tall × 4 long; front half copper grip, back half steel rod):
  // up u0-1 v8-11, down u2-3 v8-11 (rows = z, front first), side u4-7 v8 (u = z, front first), front end u8-9 v8
  rows(c, 0, 8, ['SRUT', 'SRUT', '5422', '5422']);
  rows(c, 4, 8, ['RR33']);
  rows(c, 8, 8, ['UU']);
  // hook (2×1×2): up u10-11 v8-9, down u12-13 v8-9, sides u10-11 v10, front u12-13 v10
  rows(c, 10, 8, ['4422', '4422', '3333']);
  // front rim underside (overhang above the control panel)
  rect(c, 0, 12, 16, 2, 'b');
  T.parts = c;
}

// BASKET — wire mesh, cutout. End wall 4×3 at u0-3 v0-2, long wall 7×3 at u0-6 v4-6, floor 4×7 at u8-11 v0-6.
// Bright rim (the only light part), mid-grey wires every other pixel (corner wires on both ends), darker bottom rail.
{
  const c = C();
  rows(c, 0, 0, ['6666', '3..3', '2222']);
  rows(c, 0, 4, ['6666666', '3.3.3.3', '2222222']);
  rows(c, 8, 0, ['2222', '2..2', '2222', '2..2', '2222', '2..2', '2222']);
  T.basket = c;
}

for (const [n, c] of Object.entries(T)) write(c, texFile(n));
json(`textures/block/${P}oil_on.png.mcmeta`, { animation: { frametime: 3, interpolate: false } });

// ---------------------------------------------------------------- model helpers
// default (vanilla) uv per face for an element; east faces mirrored so side textures keep "front on the left = front"
function uvOf(dir, f, t) {
  const [x1, y1, z1] = f, [x2, y2, z2] = t;
  switch (dir) {
    case 'north': return [16 - x2, 16 - y2, 16 - x1, 16 - y1];
    case 'south': return [x1, 16 - y2, x2, 16 - y1];
    case 'west': return [z1, 16 - y2, z2, 16 - y1];
    case 'east': return [z2, 16 - y2, z1, 16 - y1];          // mirrored on purpose (see above)
    case 'up': return [x1, z1, x2, z2];
    case 'down': return [x1, 16 - z2, x2, 16 - z1];
  }
}
// cullface only when the face lies on the block boundary AND stays inside the 0..16 square on the other two axes
// (a face that sticks out past the block edge must not be culled by the neighbour), and never on rotated elements.
const inside = (f, t, axes) => axes.every(k => f[k] >= 0 && t[k] <= 16);
const CULL = {
  north: (f, t) => f[2] === 0 && inside(f, t, [0, 1]), south: (f, t) => t[2] === 16 && inside(f, t, [0, 1]),
  west: (f, t) => f[0] === 0 && inside(f, t, [1, 2]), east: (f, t) => t[0] === 16 && inside(f, t, [1, 2]),
  up: (f, t) => t[1] === 16 && inside(f, t, [0, 2]), down: (f, t) => f[1] === 0 && inside(f, t, [0, 2]),
};
/** el(name, from, to, faces, extra) — faces: { dir: '#tex' | ['#tex', uv] | {texture, uv, ...} }; auto uv + cullface on block boundaries. */
function el(name, from, to, faces, extra = {}) {
  const out = { name, from, to, ...extra, faces: {} };
  for (const [dir, spec] of Object.entries(faces)) {
    const s = typeof spec === 'string' ? { texture: spec } : Array.isArray(spec) ? { texture: spec[0], uv: spec[1] } : { ...spec };
    const face = { uv: s.uv || uvOf(dir, from, to), texture: s.texture };
    if (s.rotation) face.rotation = s.rotation;
    if (!extra.rotation && CULL[dir](from, to) && !s.noCull) face.cullface = dir;
    out.faces[dir] = face;
  }
  return out;
}
const round = a => a.map(v => +v.toFixed(4));

// ---------------------------------------------------------------- geometry (shared by both states)
const WALL = '#parts';
function body() {
  const E = [];
  // legs (adjustable feet), set back 1 px from the cabinet front
  const LEG = ['#parts', [0, 4, 2, 6]];
  for (const [x, z, n] of [[1, 2, 'leg front west'], [13, 2, 'leg front east'], [1, 13, 'leg back west'], [13, 13, 'leg back east']])
    E.push(el(n, [x, 0, z], [x + 2, 2, z + 2], { north: LEG, south: LEG, west: LEG, east: LEG, down: ['#parts', [2, 4, 4, 6]] }));
  // cabinet: front inset to z=1 so knobs / lamps / handles stay inside the block. No up face: the wells are oil planes, the rest is under the rims.
  E.push(el('cabinet', [0, 2, 1], [16, OIL_Y, 16], { north: '#front', south: '#back', west: '#side', east: '#side', down: ['#bottom', [0, 0, 16, 15]] }));
  // oil: one plane per well with its own texture variable (#oil_w / #oil_e both → #oil today; a later multipart can light one vat only)
  E.push(el('oil west', [WELLS[0][0], OIL_Y, 2], [WELLS[0][1], OIL_Y, 12], { up: '#oil_w' }));
  E.push(el('oil east', [WELLS[1][0], OIL_Y, 2], [WELLS[1][1], OIL_Y, 12], { up: '#oil_e' }));
  // deck (1 px above the oil): front rim with the rounded lip overhanging the control panel, side rims, divider, back rim
  E.push(el('front rim', [0, OIL_Y, 0], [16, RIM_Y, 2], { north: '#front', south: [WALL, [0, 3, 16, 4]], up: '#top', down: ['#parts', [0, 12, 16, 14]], west: '#side', east: '#side' }));
  E.push(el('rim west', [0, OIL_Y, 2], [1, RIM_Y, 12], { west: '#side', east: [WALL, [0, 3, 10, 4]], up: '#top' }));
  E.push(el('rim east', [15, OIL_Y, 2], [16, RIM_Y, 12], { east: '#side', west: [WALL, [0, 3, 10, 4]], up: '#top' }));
  E.push(el('divider', [7, OIL_Y, 2], [9, RIM_Y, 12], { west: [WALL, [0, 3, 10, 4]], east: [WALL, [0, 3, 10, 4]], up: '#top' }));
  E.push(el('rim back', [0, OIL_Y, 12], [16, RIM_Y, 13], { north: [WALL, [0, 3, 16, 4]], up: '#top', west: '#side', east: '#side' }));
  // backsplash / flue riser (front = dark recessed panel) with the basket hanger rod in front of it
  E.push(el('backsplash', [0, OIL_Y, 13], [16, 16, 16], { north: ['#front', [0, 0, 16, 6]], south: '#back', west: '#side', east: '#side', up: '#top' }));
  E.push(el('hanger rod', [1, 14, 12], [15, 15, 13], { north: ['#parts', [0, 0, 14, 1]], up: ['#parts', [0, 1, 14, 2]], down: ['#parts', [0, 2, 14, 3]], west: ['#parts', [14, 0, 15, 1]], east: ['#parts', [14, 0, 15, 1]] }));
  return E;
}
function panel(lit) {
  const E = [], sh = lit ? { shade: false } : {};
  for (const side of ['east', 'west']) {
    const k = KNOB_X[side], l = LAMP_X[side];
    E.push(el(`knob ${side}`, [k, 7, 0], [k + 2, 9, 1], { north: ['#dial', [0, 0, 2, 2]], west: ['#dial', [2, 0, 3, 2]], east: ['#dial', [2, 0, 3, 2]], up: ['#dial', [0, 2, 2, 3]], down: ['#dial', [0, 2, 2, 3]] }));
    E.push(el(`lamp ${side}`, [l, 8, 0.5], [l + 1, 9, 1], { north: ['#dial', [4, 0, 5, 1]], west: ['#dial', [5, 0, 5.5, 1]], east: ['#dial', [5, 0, 5.5, 1]], up: ['#dial', [4, 1, 5, 1.5]], down: ['#dial', [4, 1, 5, 1.5]] }, sh));
  }
  // round oil thermometer on the backsplash between the baskets, in front of the hanger rod (the rod reads as passing behind it).
  // No side faces: they would be coplanar with the hung baskets' long walls at x=6 / x=10.
  E.push(el('oil gauge', [6, 11, 11.5], [10, 15, 12], { north: ['#dial', [8, 0, 12, 4]], up: ['#dial', [8, 4, 12, 4.5]], down: ['#dial', [8, 4, 12, 4.5]] }));
  // one horizontal door handle
  E.push(el('door handle', [5, 5, 0], [11, 6, 1], { north: ['#parts', [4, 4, 10, 5]], up: ['#parts', [4, 5, 10, 6]], down: ['#parts', [4, 6, 10, 7]], west: ['#parts', [10, 4, 11, 5]], east: ['#parts', [10, 4, 11, 5]] }));
  return E;
}

// baskets ---------------------------------------------------------------------------------------------
const B = '#basket';
const HANDLE = { up: ['#parts', [0, 8, 2, 12]], down: ['#parts', [2, 12, 4, 8]], west: ['#parts', [4, 8, 8, 9]], east: ['#parts', [8, 8, 4, 9]], north: ['#parts', [8, 8, 10, 9]] };
const HANG = { angle: -22.5, pivotY: 16, pivotZ: 11 };     // hung baskets: rotate about the hook (back-top edge), front goes down
const rotPt = (y, z) => { const a = HANG.angle * Math.PI / 180, dy = y - HANG.pivotY, dz = z - HANG.pivotZ; return [HANG.pivotY + dy * Math.cos(a) - dz * Math.sin(a), HANG.pivotZ + dy * Math.sin(a) + dz * Math.cos(a)]; };
/** Hung basket: 4×3×7 wire box (z 4-11, y 13-16) tilted -22.5° about its back-top edge (the hook on the hanger rod) so it hangs
 *  front-down like a real fryer basket draining; the hook sits on the rod; the handle stays level (on a real basket it is bent up). */
function basketHung(side) {
  const x0 = BASKET_X[side], y0 = 13, y1 = 16, z0 = 4, z1 = 11, E = [];
  const rot = { rotation: { origin: [x0 + 2, HANG.pivotY, HANG.pivotZ], axis: 'x', angle: HANG.angle } };
  const END = [0, 0, 4, 3], END_M = [4, 0, 0, 3], LONG = [0, 4, 7, 7], LONG_M = [7, 4, 0, 7], FLOOR = [8, 0, 12, 7];
  E.push(el(`basket ${side} front`, [x0, y0, z0], [x0 + 4, y1, z0], { north: [B, END], south: [B, END_M] }, rot));
  E.push(el(`basket ${side} back`, [x0, y0, z1], [x0 + 4, y1, z1], { north: [B, END], south: [B, END_M] }, rot));
  E.push(el(`basket ${side} west`, [x0, y0, z0], [x0, y1, z1], { west: [B, LONG], east: [B, LONG_M] }, rot));
  E.push(el(`basket ${side} east`, [x0 + 4, y0, z0], [x0 + 4, y1, z1], { west: [B, LONG], east: [B, LONG_M] }, rot));
  E.push(el(`basket ${side} floor`, [x0, y0, z0], [x0 + 4, y0, z1], { up: [B, FLOOR], down: [B, FLOOR] }, rot));
  // level handle leaving the basket's (rotated) front-top edge
  const [fy, fz] = rotPt(y1, z0);                           // ≈ (13.32, 4.53)
  const hy = Math.round(fy * 2) / 2;                        // snap to half pixels → y 12.5-13.5
  E.push(el(`basket ${side} handle`, [x0 + 1, hy - 1, fz - 3.5], [x0 + 3, hy, fz + 0.5], HANDLE));
  E.push(el(`basket ${side} hook`, [x0 + 1, 15, z1], [x0 + 3, 16, 13], { up: ['#parts', [10, 8, 12, 10]], down: ['#parts', [12, 8, 14, 10]], west: ['#parts', [10, 10, 12, 11]], east: ['#parts', [10, 10, 12, 11]], north: ['#parts', [12, 10, 14, 11]] }));
  return E;
}
/** Lowered basket: only the 2 px above the oil are modelled (y 10-12, rim 1 px above the deck rim); handle rests on the front rim. */
function basketDown(side) {
  const x0 = BASKET_X[side], y0 = OIL_Y, y1 = OIL_Y + 2, z0 = 4, z1 = 11, E = [];
  const END = [0, 0, 4, 2], END_M = [4, 0, 0, 2], LONG = [0, 4, 7, 6], LONG_M = [7, 4, 0, 6];
  E.push(el(`basket ${side} front`, [x0, y0, z0], [x0 + 4, y1, z0], { north: [B, END], south: [B, END_M] }));
  E.push(el(`basket ${side} back`, [x0, y0, z1], [x0 + 4, y1, z1], { north: [B, END], south: [B, END_M] }));
  E.push(el(`basket ${side} west`, [x0, y0, z0], [x0, y1, z1], { west: [B, LONG], east: [B, LONG_M] }));
  E.push(el(`basket ${side} east`, [x0 + 4, y0, z0], [x0 + 4, y1, z1], { west: [B, LONG], east: [B, LONG_M] }));
  // handle on the deck: z 0-4 at y 11-12 (no south face: it would be coplanar with the basket's front wall at z=4)
  E.push(el(`basket ${side} handle`, [x0 + 1, RIM_Y, 0], [x0 + 3, RIM_Y + 1, z0], HANDLE));
  return E;
}
/** Food sticking out of the lowered baskets (decoration; does not follow the recipe): standing fries / battered chunks. */
function food() {
  const E = [];
  FRIES_3D.forEach(([x, z, axis, angle], i) => {
    const h = 3, side = ['#parts', [11, 8 - h, 12, 8]];
    E.push(el(`fry ${i + 1}`, [x, OIL_Y, z], [x + 1, OIL_Y + h, z + 1], { north: side, south: side, west: side, east: side, up: ['#parts', [10, 7, 11, 8]] },
      { rotation: { origin: [x + 0.5, OIL_Y, z + 0.5], axis, angle } }));
  });
  CHUNKS_3D.forEach(([cx, cz, top, angle], i) => {
    const h = top - OIL_Y, side = ['#parts', [12, 4, 14, 4 + h]];
    E.push(el(`chunk ${i + 1}`, [cx - 1, OIL_Y, cz - 1], [cx + 1, top, cz + 1], { north: side, south: side, west: side, east: side, up: ['#parts', [14, 4, 16, 6]] },
      { rotation: { origin: [cx, OIL_Y, cz], axis: 'y', angle } }));
  });
  return E;
}

const textures = {
  particle: tex('side'), front: tex('front'), side: tex('side'), back: tex('back'), top: tex('top'), bottom: tex('bottom'),
  oil: tex('oil'), oil_w: '#oil', oil_e: '#oil', parts: tex('parts'), dial: tex('dial'), basket: tex('basket'),
};
const idleEls = [...body(), ...panel(false), ...basketHung('east'), ...basketHung('west')];
const activeEls = [...body(), ...panel(true), ...basketDown('east'), ...basketDown('west'), ...food()];
for (const e of [...idleEls, ...activeEls]) { e.from = round(e.from); e.to = round(e.to); }

json(`models/block/${P}idle.json`, { parent: 'minecraft:block/block', render_type: 'minecraft:cutout', textures, elements: idleEls });
json(`models/block/${P}active.json`, { parent: `${NS}:block/${P}idle`, render_type: 'minecraft:cutout', textures: { oil: tex('oil_on'), dial: tex('dial_on') }, elements: activeEls });
json(`models/item/${BLOCK}.json`, { parent: `${NS}:block/${P}idle` });
{
  const variants = {};
  for (const [facing, y] of [['north', 0], ['east', 90], ['south', 180], ['west', 270]])
    for (const active of [false, true]) variants[`active=${active},facing=${facing}`] = { model: `${NS}:block/${P}${active ? 'active' : 'idle'}`, ...(y ? { y } : {}) };
  json(`blockstates/${BLOCK}.json`, { variants });
}

// ---------------------------------------------------------------- meta
json('meta.json', {
  key: 'B', tag: '方案 B', order: 2,
  name: '商用炸炉',
  tagline: '整格不锈钢双缸炸炉：自带加热，两只钢丝炸篮平时斜挂在背板上沥油，开炸时落进金黄的热油里。',
  description: '整格方块的商用油炸机。从下往上：四条带深色橡胶脚的支脚；一扇不锈钢大门，一根横拉手；一条农夫乐事深灰的控制面板，每只油缸一个旋钮 + 一盏带灯座的琥珀指示灯（两组同序排：从正面看旋钮在左、灯在右）；台面前沿一道亮色圆边。台面上两只并排的油缸（各 6×10），油面只比台沿低 1 像素，金黄的热油从各个角度都看得到；后面一块高背板，正面是深灰内凹面板，顶上一排黑色排烟格栅，背板前横着挂篮杆，杆前正中一块圆形白底油温表。待机时两只钢丝炸篮（亮色篮沿 + 中灰竖丝，镂空）用后挂钩挂在横杆上，绕挂钩前低后高斜 22.5°，像真炸炉那样斜挂沥油，篮底最低处离油面约 0.5 像素；铜红把手水平朝前，收在方块内。工作中两只炸篮落进油缸，篮沿高出台沿 1 像素，把手搭在台面前沿上；篮子四周一圈金黄热油，奶白油沫沿缝隙转圈，篮里深色油上冒着薯条（从正面看左篮）和挂糊炸块（右篮），立体的几根薯条和炸块从篮沿冒出来；指示灯亮、旋钮转到开、油温表指针打进红区。不锈钢用农夫乐事锅具冷灰往上一档（底色 #87878b），面板和背板凹面用农夫乐事深灰，把手用农夫乐事厨锅把手的铜红。',
  notes: [
    '朝向：正面画在 north，facing=north y=0 / east 90 / south 180 / west 270（blockstates/fryer_b.json 已写好 8 个变体）。自带加热，不需要下方热源，直接放地上用。',
    '碰撞箱 / 轮廓必须用非整格：Shapes.or(Block.box(0,0,0,16,11,16), Block.box(0,11,12,16,16,16))（台面 + 背板）。整格会让游戏把所有内凹面按贴边面取邻格的光，靠墙或头顶压方块时油面、缸壁、炸篮会发暗。方块属性必须 noOcclusion()。',
    '待机 / 工作中两个模型：工作中 parent=待机（继承全部贴图），自带一套元素（炸篮落进油缸、去掉挂钩和看不见的篮底、加 3D 食物），并覆盖 oil→oil_on、dial→dial_on。',
    '油面 fryer_b_oil_on：16×96 竖条，6 帧 × 3 tick，不插值（一圈 0.9 秒）。油沫是沿缝隙转圈的虚线，每帧只移一格；气泡固定位置，按“暗点 → 奶白 → 消失”冒，不压在食物上。每缸不超过 5 种颜色。',
    '两只油缸各是一块独立的油面（#oil_w / #oil_e，现在都指向 #oil）。以后想做“只有一只篮子在炸”，改 multipart 时只要单独覆盖其中一个变量。',
    '指示灯在工作中模型里 shade:false（各面全亮）；进游戏后可再给 lamp east / lamp west 加 Forge 的 "forge_data": {"block_light": 15, "sky_light": 15}，要实机确认。',
    '炸篮是钢丝网：每面一张双面薄片（cutout 镂空），模型写了 render_type cutout（不要用 cutout_mipped，1 像素钢丝远处会闪）。待机炸篮整组绕 x 轴 -22.5°（原版允许的角度，单轴），所有零件都在方块格内。',
    '粒子（油星、蒸汽）在游戏里另外加，这是平视时唯一的动态提示，一定要做：油星从两只炸篮的油面 (4,10.2,7.5)/(12,10.2,7.5)，热气从背板顶的排烟格栅 (8,16,14.5)。坐标按 facing 旋转。',
  ],
  blocks: [
    { label: '商用炸炉', pos: [0, 0, 0], idle: { model: `${P}idle`, y: 0 }, active: { model: `${P}active`, y: 0 } },
  ],
});
console.log('fryer B written to', dir, ' elements idle', idleEls.length, 'active', activeEls.length);
