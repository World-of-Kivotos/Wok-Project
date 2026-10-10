#!/usr/bin/env node
// 掌勺小游戏 · 像素素材生成器（可重跑，不手改像素）
//
//   node gen.mjs                 在任何目录运行都行；只写本目录下的 sprites/ icons/ atlas/ preview/ manifest.json dishes.json
//   WOK_MODS=<dir> node gen.mjs  换 mods 目录（默认 D:\WOK测试\versions\1.20.1-Forge_47.4.22\mods）
//
// 规格：../spec.md（§13 四台换皮、§10 调料瓶、§11 Perfect、§17.4 界面元素）。
// 风格：原版容器界面（#c6c6c6 灰底、白/深灰斜边、1px 黑描边，像素图形状直接按原版 generic_54 / inventory 的边角逐像素写死）；
//       四台换皮全部取九台定稿烹饪台（station-preview/designs、designs-more）和农夫乐事的原色，见 palettes 段每行注释。
// 所有图都是 1x GUI 像素，只按整数倍放大（网页 image-rendering: pixelated；MC 用 GUI scale）。
// 菜品图标：按 ../../dish-stars.tsv 的物品 ID 从 mod jar 里只读取原物品贴图（16×16，动画取第 0 帧）。
// lib/png.mjs、lib/zip.mjs 是 station-preview/lib 的原样拷贝。
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { makeCanvas, setPx, getPx, fillRect, strip, scale, write, decodePNG, rgba, hex } from './lib/png.mjs';
import { openZip } from './lib/zip.mjs';

const ART = path.dirname(fileURLToPath(import.meta.url));
const REWORK = path.resolve(ART, '..', '..');
const MODS = process.env.WOK_MODS || 'D:/WOK测试/versions/1.20.1-Forge_47.4.22/mods';
const TSV = path.join(REWORK, 'dish-stars.tsv');
const rel = f => path.relative(ART, f).split(path.sep).join('/');
for (const d of ['sprites', 'icons', 'atlas', 'preview']) fs.rmSync(path.join(ART, d), { recursive: true, force: true });

// ================================================================ palettes
const CLEAR = [0, 0, 0, 0];
const V = { K: '#000000', W: '#ffffff', C: '#c6c6c6', D: '#555555', S: '#373737', G: '#8b8b8b' };   // 原版容器界面（generic_54 / inventory 实测）
const FE = { A: '#1f1f21', B: '#27272b', C: '#2d2d32', D: '#343438', E: '#3f3e42', F: '#494848', G: '#4f4f4f', H: '#595858', I: '#656565', L: '#727272', M: '#999897' };   // 农夫乐事锅具冷灰（炸锅 C、备餐台 C 黑铁同组）
const STEEL = { d: '#5c5c5c', m: '#868584', l: '#a6a4a2', h: '#c3c1bf', w: '#e4e2de' };   // 备餐台 A 刀刃 / 炸锅 A 炸篮钢丝；w = 备餐台 C 刀口高光
const OIL = { k: '#8a5a20', P: '#a86f28', Q: '#c08734', R: '#cf944c', S: '#e0b05b', T: '#f0cc7c', U: '#fae7aa' };   // 炸锅 C 热油（R/S = 随性乐事炸锅油原色）；k 新加一档深
const FIRE = { k: '#190804', r: '#2a120b', x: '#4a2116', y: '#5f341f', w: '#8a3311', o: '#c35d1b', O: '#ed8c0e', Y: '#ffd800', L: '#fff3b0' };   // 农夫乐事炉灶火色 + 炸锅 C 炭膛；L 新加火心高光
const BRICK = { r: '#b1624d', B: '#9b5643', n: '#8f503f', b: '#7c4536', q: '#733f31', o: '#a2867d', O: '#8b6e67' };   // 农夫乐事炉灶砖 + 砂浆（烤炉 C、炸锅 C 同色）
const SOOT = { a: '#1d1411', b: '#271b16', c: '#33241c', d: '#402d22' };   // 烤炉 C 炉膛烟熏黑
const BRASS = { d: '#665a2c', m: '#7f7036', l: '#bba84c', h: '#d3bb50' };   // 炸锅 A 温度计暗黄铜（备餐台 A 同组）
const BOARD = { a: '#997140', b: '#8b673c', c: '#805e36', d: '#755732', e: '#674c2c', k: '#4a3620' };   // 农夫乐事 cutting_board 五档 + 深缝
const CAB = { h: '#b58d50', c: '#a6824d', b: '#967441', a: '#866738', f: '#795c30', g: '#715333', e: '#67502c', d: '#513d24', o: '#38260c' };   // 备餐台 A 橱柜
const WOOD = { b: '#3d3022', c: '#55452e', d: '#6d5736', f: '#86683c', g: '#997140', h: '#a8814c' };   // 农夫乐事木勺 / 砧板（烤炉 C、备餐台 C）
const ENAMEL = { l: '#f6f4ee', w: '#eee8da', m: '#e6e3da', s: '#d8d0bf', k: '#cbc7bb' };   // 备餐台 A 搪瓷盘 + 备餐台 C 青花碗白
const RED = { d: '#8f241d', m: '#c63d27', l: '#e48c78', p: '#ffc9c9' };   // 家族红（炸锅 A 温度计球泡 / 备餐台 A 擦手巾）
const BLUE = { k: '#1f2d55', d: '#26396a', m: '#3a5a9a', l: '#6f8fc4' };   // 备餐台 C 青花；k 新加一档深
const GZ = { k: '#38211a', d: '#4a2c1f', m: '#6b4130', l: '#8a5a40', h: '#a8734f' };   // 备餐台 C 酱坛酱釉（调料瓶里的酱色）
const VG = { G: '#2f6b24', g: '#4f9a34', y: '#7cc04a', p: '#cfe8a0', o: '#d8702b' };   // 备餐台 C 葱花 / 胡萝卜
const GLASS = { k: '#3f3e42', e: '#c4c0b8', w: '#ffffff', i: [232, 240, 242, 120] };   // 备餐台 A 玻璃调料瓶（边色 #c4c0b8）+ 锅具灰描边
const GOLD = { L: '#fff3b0', Y: '#ffd800', g: '#ffaa00', d: '#c37a00', k: '#5f341f' };   // 原版 GOLD #ffaa00 + 炉灶火黄
const BROTH = { k: '#4a2a12', d: '#5a3418', m: '#6e4220', l: '#85532a', h: '#a8703a', f: '#d9a86a', e: '#e8c890' };   // 新：汤色（暖褐，落在锅具冷灰和炉火之间）
const MC = { gray: '#aaaaaa', green: '#55ff55', aqua: '#55ffff', purple: '#ff55ff', gold: '#ffaa00', red: '#ff5555', yellow: '#ffff55', blue: '#5555ff', white: '#ffffff' };   // 原版 ChatFormatting（ChefQuality 品质色）

// ================================================================ helpers
const A = (col, a) => { const c = rgba(col); return [c[0], c[1], c[2], a]; };
const mix = (c1, c2, t) => { const a = rgba(c1), b = rgba(c2); return [0, 1, 2, 3].map(i => Math.round(a[i] + (b[i] - a[i]) * t)); };
const lighten = (c, t) => mix(c, A('#ffffff', rgba(c)[3]), t);
const darken = (c, t) => mix(c, A('#000000', rgba(c)[3]), t);
const toGrey = c => { const [r, g, b, a] = rgba(c); const l = Math.round(0.299 * r + 0.587 * g + 0.114 * b); return [l, l, l, a]; };
/** deterministic 0..1 hash (no Math.random: reruns are byte-identical) */
function hash(x, y, s = 0) {
  let h = Math.imul(x | 0, 374761393) + Math.imul(y | 0, 668265263) + Math.imul(s | 0, 1442695041) | 0;
  h = Math.imul(h ^ (h >>> 13), 1274126177); h ^= h >>> 16;
  return (h >>> 0) / 4294967296;
}
const full = (lines, w) => { for (const l of lines) if (l.length !== w) throw new Error(`row width ${l.length} != ${w}: ${l}`); return lines; };
/** paint rows of single-char colour keys at (x0, y0); '.' / ' ' = leave the pixel alone; a key mapped to null/CLEAR clears it */
function rows(c, x0, y0, lines, pal) {
  lines.forEach((ln, j) => [...ln].forEach((ch, i) => {
    if (ch === '.' || ch === ' ') return;
    if (!(ch in pal)) throw new Error(`colour key '${ch}' missing (row ${j}: ${ln})`);
    setPx(c, x0 + i, y0 + j, pal[ch] ?? CLEAR);
  }));
}
const opaque = (c, x, y, min = 128) => x >= 0 && y >= 0 && x < c.width && y < c.height && getPx(c, x, y)[3] >= min;
/** src-over composite (blit in png.mjs replaces pixels; this one blends) */
function over(dst, src, dx, dy, opacity = 1) {
  for (let y = 0; y < src.height; y++) for (let x = 0; x < src.width; x++) {
    const X = dx + x, Y = dy + y;
    if (X < 0 || Y < 0 || X >= dst.width || Y >= dst.height) continue;
    const s = getPx(src, x, y), sa = s[3] / 255 * opacity;
    if (sa <= 0) continue;
    const d = getPx(dst, X, Y), da = d[3] / 255, oa = sa + da * (1 - sa);
    const ch = i => Math.round((s[i] * sa + d[i] * da * (1 - sa)) / oa);
    setPx(dst, X, Y, [ch(0), ch(1), ch(2), Math.round(oa * 255)]);
  }
}
function crop(src, x0, y0, w, h) { const c = makeCanvas(w, h); for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) setPx(c, x, y, getPx(src, x0 + x, y0 + y)); return c; }
function pad(src, p) { const c = makeCanvas(src.width + 2 * p, src.height + 2 * p); over(c, src, p, p); return c; }
/** 1px outline on transparent pixels touching opaque ones (4-neighbour, or 8 with diag) */
function outline(c, col, { diag = false, min = 128 } = {}) {
  const hits = [];
  for (let y = 0; y < c.height; y++) for (let x = 0; x < c.width; x++) {
    if (getPx(c, x, y)[3]) continue;
    const n = [[1, 0], [-1, 0], [0, 1], [0, -1], ...(diag ? [[1, 1], [1, -1], [-1, 1], [-1, -1]] : [])];
    if (n.some(([dx, dy]) => opaque(c, x + dx, y + dy, min))) hits.push([x, y]);
  }
  for (const [x, y] of hits) setPx(c, x, y, col);
  return c;
}
function dropShadow(c, col, dx = 1, dy = 1) {
  const hits = [];
  for (let y = 0; y < c.height; y++) for (let x = 0; x < c.width; x++) if (getPx(c, x, y)[3] && !opaque(c, x + dx, y + dy, 1) && x + dx < c.width && y + dy < c.height) hits.push([x + dx, y + dy]);
  for (const [x, y] of hits) setPx(c, x, y, col);
  return c;
}
function recolor(c, fn) { const o = makeCanvas(c.width, c.height); for (let y = 0; y < c.height; y++) for (let x = 0; x < c.width; x++) { const p = getPx(c, x, y); if (p[3]) setPx(o, x, y, fn(p, x, y)); } return o; }
/** fill pixels whose centre is inside an ellipse; fn(x, y, d) gets the normalised radius d (0 centre .. 1 edge) */
function ellipse(c, cx, cy, rx, ry, fn) {
  for (let y = 0; y < c.height; y++) for (let x = 0; x < c.width; x++) {
    const dx = (x + 0.5 - cx) / rx, dy = (y + 0.5 - cy) / ry, d = Math.hypot(dx, dy);
    if (d <= 1) { const col = fn(x, y, d, dx, dy); if (col) setPx(c, x, y, col); }
  }
}
/** pixels whose centre is inside polygon pts */
function polygon(c, pts, fn) {
  for (let y = 0; y < c.height; y++) for (let x = 0; x < c.width; x++) {
    const px = x + 0.5, py = y + 0.5; let inside = false;
    for (let i = 0, j = pts.length - 1; i < pts.length; j = i++) {
      const [xi, yi] = pts[i], [xj, yj] = pts[j];
      if ((yi > py) !== (yj > py) && px < (xj - xi) * (py - yi) / (yj - yi) + xi) inside = !inside;
    }
    if (inside) { const col = fn(x, y); if (col) setPx(c, x, y, col); }
  }
}
/** ring of pixels between r0 and r1 around (cx, cy); angle a: 0 = 12 o'clock, clockwise, 0..1 */
function ring(c, cx, cy, r0, r1, fn) {
  for (let y = 0; y < c.height; y++) for (let x = 0; x < c.width; x++) {
    const dx = x + 0.5 - cx, dy = y + 0.5 - cy, d = Math.hypot(dx, dy);
    if (d >= r0 && d < r1) { let a = Math.atan2(dx, -dy) / (2 * Math.PI); if (a < 0) a += 1; const col = fn(x, y, a, d); if (col) setPx(c, x, y, col); }
  }
}
const BAYER4 = [[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]];

// ---------------------------------------------------------------- pixel fonts（原版字体同尺寸：5×7；徽章小字 3×5 不用）
const F57 = {
  P: ['####.', '#...#', '#...#', '####.', '#....', '#....', '#....'],
  E: ['#####', '#....', '#....', '####.', '#....', '#....', '#####'],
  R: ['####.', '#...#', '#...#', '####.', '#.#..', '#..#.', '#...#'],
  F: ['#####', '#....', '#....', '####.', '#....', '#....', '#....'],
  C: ['.###.', '#...#', '#....', '#....', '#....', '#...#', '.###.'],
  T: ['#####', '..#..', '..#..', '..#..', '..#..', '..#..', '..#..'],
  '!': ['#', '#', '#', '#', '#', '.', '#'],
  1: ['..#..', '.##..', '..#..', '..#..', '..#..', '..#..', '.###.'],
  2: ['.###.', '#...#', '....#', '...#.', '..#..', '.#...', '#####'],
  3: ['.###.', '#...#', '....#', '..##.', '....#', '#...#', '.###.'],
  '✦': ['...#...', '...#...', '..###..', '#######', '..###..', '...#...', '...#...'],
};
function measure(str, k = 1, gap = 1) { let w = 0; for (const ch of str) w += (F57[ch][0].length + gap) * k; return w - gap * k; }
function glyphs(c, x, y, str, col, k = 1, gap = 1) {
  let cx = x;
  for (const ch of str) {
    const g = F57[ch]; if (!g) throw new Error('no glyph ' + ch);
    g.forEach((row, j) => [...row].forEach((b, i) => { if (b === '#') fillRect(c, cx + i * k, y + j * k, k, k, typeof col === 'function' ? col(j, ch) : col); }));
    cx += (g[0].length + gap) * k;
  }
}

// ================================================================ sprite registry
const SPR = new Map();
function add(group, name, canvas, meta = {}) {
  const key = `${group}/${name}`;
  if (SPR.has(key)) throw new Error('duplicate sprite ' + key);
  SPR.set(key, { key, group, name, canvas, ...meta });
  return canvas;
}
function addAnim(group, name, frames, meta = {}) {
  const { ms = 100, ...rest } = meta;
  return add(group, name, strip(frames), { ...rest, frames: { count: frames.length, w: frames[0].width, h: frames[0].height, layout: 'vertical', ms } });
}
const frameOf = (key, i) => { const s = SPR.get(key); return s.frames ? crop(s.canvas, 0, i * s.frames.h, s.frames.w, s.frames.h) : s.canvas; };
const STATIONS = { pot: '厨锅', fryer: '炸锅', oven: '烤炉', prep: '备餐台' };

// ================================================================ common · 原版容器部件
/** vanilla container panel; corners copied pixel-for-pixel from generic_54.png (top) / inventory.png (bottom) */
function vanillaPanel(w, h) {
  const c = makeCanvas(w, h);
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    let k = 'C';
    if (y === 1 || y === 2 || x === 1 || x === 2) k = 'W';
    if (x === w - 3 || x === w - 2 || y === h - 3 || y === h - 2) k = 'D';
    if (x === 0 || y === 0 || x === w - 1 || y === h - 1) k = 'K';
    setPx(c, x, y, V[k]);
  }
  const pal = { K: V.K, W: V.W, C: V.C, D: V.D, _: CLEAR };
  rows(c, 0, 0, ['__KK', '_KWW', 'KWWW', 'KWWW'], pal);
  rows(c, w - 4, 0, ['K___', 'WK__', 'WCK_', 'CDDK'], pal);
  rows(c, 0, h - 4, ['KWWC', '_KCD', '__KD', '___K'], pal);
  rows(c, w - 4, h - 4, ['DDDK', 'DDDK', 'DDK_', 'KK__'], pal);
  return c;
}
/** vanilla slot / inset well: #373737 top-left, #ffffff bottom-right, #8b8b8b on the two mixed corners */
function inset(w, h, fill = V.G) {
  const c = makeCanvas(w, h, fill);
  for (let x = 0; x < w; x++) { setPx(c, x, 0, V.S); setPx(c, x, h - 1, V.W); }
  for (let y = 0; y < h; y++) { setPx(c, 0, y, V.S); setPx(c, w - 1, y, V.W); }
  setPx(c, w - 1, 0, V.G); setPx(c, 0, h - 1, V.G);
  return c;
}
add('common', 'panel', vanillaPanel(16, 16), { use: '原版容器底板（整个小游戏界面 / 结算屏的底）。四角 4px 逐像素照原版 generic_54 / inventory。', slice: { left: 4, top: 4, right: 4, bottom: 4, mode: 'stretch' }, content: { left: 4, top: 4, right: 4, bottom: 4 } });
add('common', 'inset', inset(8, 8), { use: '原版凹槽（物品格同款斜边）。任意尺寸的凹陷底：结算屏数值框、练习模式面板等。', slice: { left: 1, top: 1, right: 1, bottom: 1, mode: 'stretch' }, content: { left: 1, top: 1, right: 1, bottom: 1 } });
add('common', 'slot', inset(18, 18), { use: '原版物品格 18×18（成品格：放 16×16 菜品图标于 (1,1)）。', content: { left: 1, top: 1, right: 1, bottom: 1 } });
function button(state) {
  const P = {
    normal: { body: '#6f6f6f', hi: '#aaaaaa', lo: '#565656', edge: V.K },
    hover: { body: '#7e88bf', hi: '#bcc6ff', lo: '#5a619e', edge: V.K },
    disabled: { body: '#2c2c2c', hi: '#3c3c3c', lo: '#202020', edge: V.K },
  }[state];
  const c = makeCanvas(20, 20, P.body);
  for (let x = 0; x < 20; x++) { setPx(c, x, 0, P.edge); setPx(c, x, 19, P.edge); setPx(c, x, 1, P.hi); setPx(c, x, 17, P.lo); setPx(c, x, 18, P.lo); }
  for (let y = 0; y < 20; y++) { setPx(c, 0, y, P.edge); setPx(c, 19, y, P.edge); }
  for (let y = 1; y < 17; y++) setPx(c, 1, y, P.hi);
  for (let y = 2; y < 19; y++) setPx(c, 18, y, P.lo);
  setPx(c, 1, 17, mix(P.hi, P.lo, 0.5)); setPx(c, 18, 1, mix(P.hi, P.lo, 0.5));
  return c;
}
for (const st of ['normal', 'hover', 'disabled'])
  add('common', `button_${st}`, button(st), { use: `按钮（${{ normal: '常态', hover: '悬停', disabled: '置灰（做不了，写明原因）' }[st]}）：掌勺 / 批量 / 快手 / 家常 / 再来一份 / 收取。字用游戏字体白字 + 阴影，置灰用 #a0a0a0。`, slice: { left: 2, top: 2, right: 2, bottom: 3, mode: 'stretch' }, content: { left: 2, top: 2, right: 2, bottom: 3 } });
{ // 原版提示框（tooltip）：0xF0100010 底 + 0x505000FF→0x5028007F 渐变内框
  const c = makeCanvas(9, 9), bg = A('#100010', 240);
  for (let y = 0; y < 9; y++) for (let x = 0; x < 9; x++) {
    if ((x === 0 || x === 8) && (y === 0 || y === 8)) continue;
    let col = bg;
    if (x >= 1 && x <= 7 && y >= 1 && y <= 7 && (x === 1 || x === 7 || y === 1 || y === 7)) {
      const t = (y - 1) / 6, b = mix(A('#5000ff', 80), A('#28007f', 80), t), a = b[3] / 255;
      col = [0, 1, 2].map(i => Math.round(b[i] * a + bg[i] * (1 - a))).concat(240);
    }
    setPx(c, x, y, col);
  }
  add('common', 'tooltip', c, { use: '原版物品提示框：结算屏扣分明细、负面原因、按钮置灰原因的悬浮说明。', slice: { left: 2, top: 2, right: 2, bottom: 2, mode: 'stretch' }, content: { left: 3, top: 3, right: 3, bottom: 3 } });
}

// ---------------------------------------------------------------- common · 小件
{ // 进度类竖槽（熟度条、厨锅火苗柱共用）：原版凹槽 + 深底
  add('common', 'gauge_frame', inset(8, 16, '#2e2e2e'), { use: '竖槽外框：熟度条、厨锅火苗柱共用。内容区宽 6px，填充用各台 progress_fill / heat_fill 平铺。', slice: { left: 1, top: 1, right: 1, bottom: 1, mode: 'stretch' }, content: { left: 1, top: 1, right: 1, bottom: 1 } });
  add('common', 'meter_frame', inset(5, 8, '#2e2e2e'), { use: '「生 / 焦」两根小槽的外框（欠火‰ / 过火‰ 实时值，§17.4）。内容区宽 3px。', slice: { left: 1, top: 1, right: 1, bottom: 1, mode: 'stretch' }, content: { left: 1, top: 1, right: 1, bottom: 1 } });
  const tile3 = cols => { const c = makeCanvas(3, 2); cols.forEach((col, x) => { setPx(c, x, 0, col); setPx(c, x, 1, col); }); return c; };
  add('common', 'meter_fill_raw', tile3(['#f6d0c8', '#f0b8b0', '#d89a90']), { use: '「生」槽填充（欠火‰，0–100‰ 对应满槽建议上限 200‰）。平铺。', slice: { left: 0, top: 0, right: 0, bottom: 0, mode: 'repeat' } });
  add('common', 'meter_fill_burnt', tile3([FIRE.y, FIRE.x, FIRE.r]), { use: '「焦」槽填充（过火‰）。备餐台把这根槽叫「多盐」，换 meter_fill_salt。平铺。', slice: { left: 0, top: 0, right: 0, bottom: 0, mode: 'repeat' } });
  add('common', 'meter_fill_salt', tile3([ENAMEL.l, ENAMEL.s, ENAMEL.k]), { use: '备餐台的「多盐」槽填充（过火方向在备餐台显示为多盐，§13.4）。平铺。', slice: { left: 0, top: 0, right: 0, bottom: 0, mode: 'repeat' } });
  add('common', 'meter_fill_alert', tile3(['#ff8b8b', MC.red, RED.m]), { use: '生 / 焦 / 多盐 任一槽过 80‰ 后整根换成这个红（§17.4「过 80 变红」）。平铺。', slice: { left: 0, top: 0, right: 0, bottom: 0, mode: 'repeat' } });
  const th = makeCanvas(3, 1, MC.red);
  add('common', 'meter_threshold', th, { use: '生 / 焦槽里 80‰ 门槛刻度线（画在内容区对应高度）。' });
}
{ // 星级 ★（9×9 形 + 1px 描边 = 11×11）
  const SHAPE = full(['....#....', '....#....', '...###...', '#########', '.#######.', '..#####..', '..#####..', '.###.###.', '.##...##.'], 9);
  const star = (fill, shade, edge) => {
    const c = makeCanvas(11, 11);
    rows(c, 1, 1, SHAPE.map((r, j) => r.replace(/#/g, j >= 6 ? 's' : 'f')), { f: fill, s: shade });
    return outline(c, edge);
  };
  add('common', 'star_on', star(GOLD.Y, GOLD.g, GOLD.k), { use: '星级 ★（亮）。界面标题行按菜的 ★ 数排，间距 10px（描边重叠 1px）。' });
  add('common', 'star_off', star(V.G, '#7a7a7a', V.S), { use: '星级 ☆（暗）：补齐到 7 颗时用。' });
}
{ // 出条箭头「火小了 ↑ / 火大了 ↓」（§8）
  const SHAPE = full(['....#....', '...###...', '..#####..', '.#######.', '#########', '...###...', '...###...', '...###...'], 9);
  const arrow = (fill, shade, edge, down) => {
    const c = makeCanvas(11, 10);
    rows(c, 1, 1, SHAPE.map((r, j) => r.replace(/#/g, (j >= 5) ? 's' : 'f')), { f: fill, s: shade });
    outline(c, edge);
    if (!down) return c;
    const d = makeCanvas(11, 10); for (let y = 0; y < 10; y++) for (let x = 0; x < 11; x++) setPx(d, x, 9 - y, getPx(c, x, y)); return d;
  };
  add('common', 'arrow_up', arrow(FIRE.O, FIRE.o, FIRE.x, false), { use: '「火小了 ↑」：食材在火候条上方（欠火）。画在轨道外侧、食材同高；字用游戏字体。' });
  add('common', 'arrow_down', arrow(BLUE.l, BLUE.m, BLUE.d, true), { use: '「火大了 ↓」：食材在火候条下方（过火）。备餐台同一个箭头配「料多了」。' });
}
{ // 性子图标（§7.1 / §13.6；§17.1 开局前面板「性子图标」）：9×9 形 + 1px 描边 = 11×11，和星级、箭头同一套做法
  const M = {
    mix: { name: '混', what: '随机大移动，偶尔小跳（骰子）', f: '#ffffff', s: '#d8d8d8', p: FE.A, shape: ['.#######.', '#########', '##p###p##', '#########', '####p####', '#########', '##p###p##', '#########', '.#######.'] },
    dart: { name: '窜', what: '频繁短距离急窜（闪电）', f: MC.yellow, s: GOLD.g, shape: ['....###..', '...###...', '..###....', '.#######.', '....###..', '...###...', '..###....', '.##......', '.#.......'] },
    steady: { name: '稳', what: '走到一个点停下再走，没有小跳（暂停）', f: MC.green, s: '#2fa02f', shape: ['.##...##.', '.##...##.', '.##...##.', '.##...##.', '.##...##.', '.##...##.', '.##...##.', '.##...##.', '.##...##.'] },
    sink: { name: '沉', what: '总往 30% 线沉（箭头压到线上）', f: BLUE.l, s: BLUE.m, shape: ['...###...', '...###...', '...###...', '.#######.', '..#####..', '...###...', '....#....', '.........', '#########'] },
    float: { name: '浮', what: '总往 70% 线浮（箭头顶到线下）', f: '#8fe8ff', s: '#3aa8c8', shape: ['#########', '.........', '....#....', '...###...', '..#####..', '.#######.', '...###...', '...###...', '...###...'] },
  };
  for (const [k, m] of Object.entries(M)) {
    const c = makeCanvas(11, 11);
    rows(c, 1, 1, full(m.shape, 9).map((r, j) => r.replace(/#/g, j >= 6 ? 's' : 'f')), { f: m.f, s: m.s, p: m.p ?? m.s });
    add('common', `motion_${k}`, outline(c, FE.A), { use: `性子图标「${m.name}」：${m.what}。用在开局前面板（§17.1）、练习模式选性子；字用游戏字体写「${m.name}」。` });
  }
}
{ // 键帽（动作键提示：F / 右键；可改键，字用游戏字体）
  const c = makeCanvas(11, 12);
  for (let y = 0; y < 12; y++) for (let x = 0; x < 11; x++) {
    if ((x === 0 || x === 10) && (y === 0 || y === 11)) continue;
    let col = '#e8e8e8';
    if (x === 0 || x === 10 || y === 0 || y === 11) col = V.S;
    else if (y >= 9) col = V.G;
    else if (y === 1) col = V.W;
    else if (x === 9) col = '#c6c6c6';
    setPx(c, x, y, col);
  }
  add('common', 'keycap', c, { use: '键帽：动作键提示（炸锅压火 / 烤炉翻面 / 备餐台摆料，默认 F / 右键，跟随改键）。字用游戏字体深灰 #373737，写在内容区。', slice: { left: 2, top: 2, right: 2, bottom: 3, mode: 'stretch' }, content: { left: 2, top: 2, right: 2, bottom: 3 } });
}
{ // 彩条标签（chip）：深底 + 品质色描边；字用同色游戏字体
  const chip = col => {
    const c = makeCanvas(8, 12);
    for (let y = 0; y < 12; y++) for (let x = 0; x < 8; x++) {
      if ((x === 0 || x === 7) && (y === 0 || y === 11)) continue;
      let p = FE.C;
      if (x === 0 || x === 7 || y === 0 || y === 11) p = darken(col, 0.15);
      else if (y === 1) p = FE.E;
      else if (y === 10) p = FE.A;
      setPx(c, x, y, p);
    }
    return c;
  };
  const CHIPS = {
    gray: [MC.gray, '品质「低」；糊锅/撂勺结算里的「家常菜」'], green: [MC.green, '品质「中」；烤炉「正好」'], aqua: [MC.aqua, '品质「高」；备餐台「可以」'],
    purple: [MC.purple, '品质「超凡」'], gold: [MC.gold, '品质「闪耀」；备餐台「正好」；调料瓶「抓到」'], red: [MC.red, '烤炉「自动翻」；备餐台「漏拍」；爆油'],
    yellow: [MC.yellow, '烤炉「稍晚」；到点'], blue: ['#7f8fff', '烤炉「翻早」'], brown: ['#c8823c', '烤炉「焦面」；备餐台「乱按」'], white: [MC.white, '中性提示（做法、份数、推荐等级）'],
  };
  for (const [k, [col, use]] of Object.entries(CHIPS))
    add('common', `chip_${k}`, chip(col), { use: `标签条：${use}。字用 ${hex(rgba(col))} 游戏字体，写在内容区（高 8px）。`, textColor: hex(rgba(col)), slice: { left: 2, top: 2, right: 2, bottom: 2, mode: 'stretch' }, content: { left: 2, top: 2, right: 2, bottom: 2 } });
}
{ // 落点虚影（§15.2 L1–3）与框住光圈
  const g = makeCanvas(18, 18);
  ring(g, 9, 9, 7.2, 8.4, (x, y, a) => (Math.floor(a * 24) % 2 === 0 ? A('#ffffff', 170) : null));
  add('common', 'target_ghost', g, { use: '新手落点虚影：L1–3 把食材当前目的地 tgt 画成虚线圈（中心对齐 tgt；tgt == NONE 不画）。也可另画半透明食材图标。', anchor: 'center' });
  const h = makeCanvas(22, 22);
  ring(h, 11, 11, 8.6, 10.6, (x, y, a, d) => A(FIRE.L, d < 9.6 ? 120 : 60));
  add('common', 'target_halo', h, { use: '框住光圈：食材在火候条内时画在食材图标下面（可随音调做 2 档呼吸）。', anchor: 'center' });
}
// 竖槽读数用的三角标 ◀（烤炉翻面点、备餐台摆料点、油温指针）
const TRI = full(['...#', '..##', '.###', '####', '.###', '..##', '...#'], 4);
function tri(fill, edge) { const c = makeCanvas(6, 9); rows(c, 1, 1, TRI.map(r => r.replace(/#/g, 'f')), { f: fill }); return outline(c, edge); }

// ---------------------------------------------------------------- common · 调料瓶（§10）
const BOTTLE = full([
  '................',
  '......kkkk......',
  '.....kPPPPk.....',
  '.....kpppPk.....',
  '......kwik......',
  '.....kwiiik.....',
  '....kwiiiiik....',
  '...kwhhhhhhgk...',
  '...kwLLLLLLgk...',
  '...kwLRRRRLgk...',
  '...kwLLLLLLgk...',
  '...kwmmmmmmgk...',
  '...kwmmmmmdgk...',
  '...kgmmmmddgk...',
  '....kkkkkkkk....',
  '................',
], 16);
const bottlePal = gold => ({
  k: GLASS.k, w: GLASS.w, g: GLASS.e, i: GLASS.i, P: WOOD.h, p: WOOD.f,
  L: '#f0e9d8', R: gold ? GOLD.g : RED.m,
  h: gold ? GOLD.L : GZ.h, m: gold ? GOLD.Y : GZ.l, d: gold ? GOLD.g : GZ.m,
});
function bottle(gold) { const c = makeCanvas(16, 16); rows(c, 0, 0, BOTTLE, bottlePal(gold)); return c; }
add('common', 'bottle', bottle(false), { use: '调料瓶（普通瓶，§10）：16×16 小玻璃瓶，软木塞、纸签、酱色调料。中心对齐瓶位 pos。金瓶在 Perfect 断掉后也画成这张（褪成普通色）。', anchor: 'center' });
add('common', 'bottle_gold', bottle(true), { use: '金调料瓶（闪耀的必要条件）。出现时配金光和弦。动画版见 bottle_gold_glint。', anchor: 'center' });
{
  const SPARK = [[2, 5], [3, 4], [12, 6], [11, 11]];
  const frames = [0, 1, 2, 3].map(f => {
    const c = bottle(true);
    const [x, y] = SPARK[f];
    for (const [dx, dy, a] of [[0, 0, 255], [1, 0, 150], [-1, 0, 150], [0, 1, 150], [0, -1, 150]]) over(c, (() => { const p = makeCanvas(1, 1); setPx(p, 0, 0, A('#ffffff', a)); return p; })(), x + dx, y + dy);
    return c;
  });
  addAnim('common', 'bottle_gold_glint', frames, { ms: 120, use: '金调料瓶闪光（4 帧循环，一颗星光沿瓶身绕一圈）。', anchor: 'center' });
}
{ // 抓瓶进度 K（0–1,000,000，§10）
  add('common', 'bottle_meter_frame', inset(8, 5, '#2e2e2e'), { use: '抓瓶进度条外框：画在瓶下方 1px，宽 18（与瓶居中）。内容区高 3px。', slice: { left: 1, top: 1, right: 1, bottom: 1, mode: 'stretch' }, content: { left: 1, top: 1, right: 1, bottom: 1 } });
  const t = (a, b) => { const c = makeCanvas(2, 3); for (let x = 0; x < 2; x++) { setPx(c, x, 0, a); setPx(c, x, 1, b); setPx(c, x, 2, b); } return c; };
  add('common', 'bottle_meter_fill', t(VG.p, VG.y), { use: '抓瓶进度填充（普通瓶）。横向平铺。', slice: { left: 0, top: 0, right: 0, bottom: 0, mode: 'repeat' } });
  add('common', 'bottle_meter_fill_gold', t(GOLD.L, GOLD.Y), { use: '抓瓶进度填充（金瓶）。横向平铺。', slice: { left: 0, top: 0, right: 0, bottom: 0, mode: 'repeat' } });
  const pops = [];
  for (let f = 0; f < 5; f++) {
    const c = makeCanvas(24, 24), r = 3 + f * 2.2;
    ring(c, 12, 12, r, r + (f < 3 ? 1.6 : 1.1), (x, y, a) => (Math.floor(a * 16 + f) % (f < 2 ? 1 : 2) === 0 ? A(f < 2 ? '#ffffff' : GOLD.L, 255 - f * 40) : null));
    for (let k = 0; k < 8; k++) {
      const ang = k / 8 * 2 * Math.PI + 0.3, d = 4 + f * 2.6;
      const x = Math.round(12 + Math.sin(ang) * d), y = Math.round(12 - Math.cos(ang) * d);
      if (f > 0) setPx(c, x, y, A(k % 2 ? GOLD.Y : '#ffffff', 255 - f * 35));
    }
    pops.push(c);
  }
  addAnim('common', 'bottle_pop', pops, { ms: 60, use: '抓到调料瓶：一圈粒子炸开（5 帧，播一次），中心对齐瓶位。', anchor: 'center' });
}

// ---------------------------------------------------------------- common · Perfect（§11）
function perfectBadge() {
  const tw = measure('PERFECT'), w = 7 + 2 + tw + 8, h = 13;
  const c = makeCanvas(w, h);
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    if ((x === 0 || x === w - 1) && (y === 0 || y === h - 1)) continue;
    let col = y <= 4 ? GOLD.Y : GOLD.g;
    if (x === 0 || x === w - 1 || y === 0 || y === h - 1) col = GOLD.k;
    else if (y === 1) col = GOLD.L;
    else if (y === h - 2) col = GOLD.d;
    else if (x === 1) col = GOLD.L;
    else if (x === w - 2) col = GOLD.d;
    setPx(c, x, y, col);
  }
  glyphs(c, 5, 4, '✦', GOLD.d);
  glyphs(c, 4, 3, '✦', '#ffffff');
  glyphs(c, 4 + 7 + 2 + 1, 4, 'PERFECT', GOLD.d);       // 1px 浅色投影感：先画深金偏移
  glyphs(c, 4 + 7 + 2, 3, 'PERFECT', GOLD.k);
  return c;
}
const BADGE = perfectBadge();
add('common', 'perfect_badge', BADGE, { use: '「✦ PERFECT」徽章：挂在界面左上，开局就在。整张不拉伸。' });
const CRACK_X = [30, 29, 30, 31, 31, 30, 29, 29, 30, 31, 30, 29, 30];   // 从 R 和 F 之间裂开
function brokenBadge() {
  const g = recolor(BADGE, p => { const q = toGrey(p); return mix(q, '#555555', 0.35); });
  const c = makeCanvas(g.width, g.height + 1);
  for (let y = 0; y < g.height; y++) for (let x = 0; x < g.width; x++) {
    const p = getPx(g, x, y); if (!p[3]) continue;
    const cx = CRACK_X[y];
    if (x === cx) continue;
    setPx(c, x, y + (x > cx ? 1 : 0), p);
  }
  for (let y = 0; y < g.height; y++) { setPx(c, CRACK_X[y] - 1, y, FE.C); setPx(c, CRACK_X[y] + 1, y + 1, FE.C); }
  return c;
}
const BROKEN = brokenBadge();
add('common', 'perfect_badge_broken', BROKEN, { use: '徽章碎了（第一次失误后停在这张）：灰掉、中间一道裂、右半边掉下 1px。', note: '比 perfect_badge 高 1px（右半边下沉），左上角对齐。' });
{
  const cuts = [0, 12, 24, 30, 42, 50, BROKEN.width];
  const frames = [];
  for (let f = 0; f < 5; f++) {
    const c = makeCanvas(BROKEN.width + 16, BROKEN.height + 22);
    for (let i = 0; i < cuts.length - 1; i++) {
      const piece = crop(BROKEN, cuts[i], 0, cuts[i + 1] - cuts[i], BROKEN.height);
      const mid = (cuts[i] + cuts[i + 1]) / 2 / BROKEN.width - 0.5;
      const dx = Math.round(mid * 10 * f), dy = Math.round(-2 * f + 1.4 * f * f * (0.8 + hash(i, 0, 5) * 0.4));
      over(c, piece, 8 + cuts[i] + dx, 2 + dy, Math.max(0, 1 - f * 0.2));
    }
    frames.push(c);
  }
  addAnim('common', 'perfect_shatter', frames, { ms: 70, use: '徽章碎裂动画（5 帧播一次，播完停在 perfect_badge_broken）。徽章画在帧内 (8,2)。', note: '帧 0 = 刚裂开；碎片左右散开、下坠、淡出。' });
}
{ // 结算屏大字「PERFECT!」
  const k = 2, w = measure('PERFECT!', k, 1) + 4, h = 7 * k + 4;
  const c = makeCanvas(w, h);
  glyphs(c, 1, 1, 'PERFECT!', j => (j < 2 ? GOLD.L : j < 4 ? GOLD.Y : GOLD.g), k, 1);
  outline(c, GOLD.k, { diag: true });
  dropShadow(c, A(FIRE.k, 170));
  add('common', 'perfect_stamp', c, { use: '出锅大字盖章旁弹出的「PERFECT!」（§17.4）。整张不拉伸，建议界面 ×2 再放大一档。' });
}
for (const n of ['3', '2', '1']) { // 倒计时 3·2·1（「下锅！」用游戏字体）
  const k = 3, c = makeCanvas(measure(n, k) + 3, 7 * k + 3);
  glyphs(c, 1, 1, n, j => (j < 3 ? '#ffffff' : j < 5 ? '#e8e8e8' : '#c6c6c6'), k);
  outline(c, V.K, { diag: true });
  dropShadow(c, A('#000000', 110));
  add('common', `countdown_${n}`, c, { use: `倒计时数字「${n}」（§17.3，20 tick 内 3→2→1，每个约 6–7 tick）。画在轨道正中。`, anchor: 'center' });
}

// ================================================================ station · 轨道（§1 竖轨道；u 0 = 锅底 … 1,000,000 = 顶）
// 源图 26×44：第 0 行 / 第 25 列…是原版凹槽 1px；2px 台子墙；内容区 20 宽。行：0 凹槽｜1–2 沿口｜3–5 顶段装饰｜6–37 中段（32 行平铺）｜38–40 底段｜41–42 锅底｜43 凹槽。
const TRK = { w: 26, h: 44 };
const midY = ly => 6 + (((ly % 32) + 32) % 32);
function buildTrack(theme) {
  const { w, h } = TRK, c = makeCanvas(w, h);
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    let col;
    if (y === 0) col = x === w - 1 ? V.G : V.S;
    else if (y === h - 1) col = x === 0 ? V.G : V.W;
    else if (x === 0) col = V.S;
    else if (x === w - 1) col = V.W;
    else {
      let region, i = 0, r = 0;
      if (y <= 2) { region = 'rim'; r = y - 1; }
      else if (y >= h - 3) { region = 'floor'; r = y - (h - 3); }
      else if (x <= 2) { region = 'wallL'; i = x - 1; }
      else if (x >= w - 3) { region = 'wallR'; i = x - (w - 3); }
      else if (y <= 5) { region = 'capTop'; r = y - 3; }
      else if (y >= h - 6) { region = 'capBot'; r = y - (h - 6); }
      else region = 'mid';
      col = theme.px({ region, i, r, x, y, ix: x - 3, ly: (((y - 6) % 32) + 32) % 32 });
    }
    setPx(c, x, y, col);
  }
  theme.decor?.(c);
  return c;
}
const ring3 = (c, x, ly, rc, hc) => { setPx(c, x, midY(ly - 1), hc); setPx(c, x - 1, midY(ly), rc); setPx(c, x + 1, midY(ly), rc); setPx(c, x, midY(ly + 1), rc); };
/** deterministic short horizontal dashes in the 20×32 middle tile: [[ix, ly, len]] (liquid shimmer / soot streaks) */
function dashList(n, seed, minLen = 2, maxLen = 4) {
  const out = [];
  for (let k = 0; k < n; k++) out.push([1 + Math.floor(hash(k, 1, seed) * 16), Math.floor(hash(k, 2, seed) * 32), minLen + Math.floor(hash(k, 3, seed) * (maxLen - minLen + 1))]);
  return out;
}
const dashes = (c, list, col) => { for (const [ix, ly, len] of list) for (let i = 0; i < len && ix + i <= 18; i++) setPx(c, 3 + ix + i, midY(ly), col); };
const pick = (arr, n) => arr[Math.min(arr.length - 1, Math.floor(n * arr.length))];

const THEMES = {
  pot: { // 铁锅侧剖面 + 汤：农夫乐事锅具冷灰的锅壁，锅底透出炉火
    px({ region, i, r, x, ix, ly }) {
      switch (region) {
        case 'rim': return r === 0 ? (x === 1 ? FE.I : x === 24 ? FE.F : FE.L) : (x === 1 ? FE.G : x === 24 ? FE.D : FE.H);
        case 'wallL': return i === 0 ? FE.G : FE.E;
        case 'wallR': return i === 0 ? FE.H : FE.D;
        case 'floor': return r === 0 ? FE.C : [FIRE.w, FE.A, FIRE.o, FE.A, FE.B, FIRE.w, FE.A, FIRE.O][x % 8];
        case 'capTop': return [hash(ix, 0, 1) < 0.35 ? BROTH.e : BROTH.f, hash(ix, 1, 1) < 0.5 ? BROTH.h : BROTH.l, hash(ix, 2, 1) < 0.3 ? BROTH.l : BROTH.m][r];
        case 'capBot': return [hash(ix, 0, 2) < 0.5 ? BROTH.d : BROTH.m, BROTH.d, BROTH.k][r];
        default: return ix === 0 ? BROTH.d : ix === 19 ? mix(BROTH.m, BROTH.d, 0.5) : BROTH.m;
      }
    },
    decor(c) {   // 汤：深浅两层横向暗流 + 4 个气泡 + 几粒胡萝卜丁 / 葱花
      dashes(c, dashList(7, 21), BROTH.d);
      dashes(c, dashList(5, 22), BROTH.l);
      dashes(c, dashList(2, 23, 2, 2), BROTH.h);
      for (const [ix, ly] of [[5, 4], [15, 12], [9, 21], [16, 27]]) ring3(c, 3 + ix, ly, BROTH.l, BROTH.f);
      for (const [ix, ly, col] of [[12, 2, VG.o], [13, 2, FIRE.o], [7, 15, VG.g], [3, 25, VG.o]]) setPx(c, 3 + ix, midY(ly), col);
    },
  },
  fryer: { // 油锅侧剖面 + 金黄油：炸锅 C 的油色
    px({ region, i, r, x, ix, ly }) {
      switch (region) {
        case 'rim': return r === 0 ? (x === 1 ? FE.H : x === 24 ? FE.E : FE.I) : (x === 1 ? FE.E : x === 24 ? FE.B : FE.E);
        case 'wallL': return i === 0 ? FE.E : FE.C;
        case 'wallR': return i === 0 ? FE.F : FE.B;
        case 'floor': return r === 0 ? FE.B : [FIRE.w, FE.A, FE.A, FIRE.o, FE.A, FIRE.w, FE.A, FE.A][x % 8];
        case 'capTop': return [hash(ix, 0, 4) < 0.45 ? OIL.U : OIL.T, hash(ix, 1, 4) < 0.5 ? OIL.S : OIL.T, hash(ix, 2, 4) < 0.35 ? OIL.S : OIL.R][r];
        case 'capBot': return [OIL.P, hash(ix, 1, 5) < 0.5 ? OIL.P : OIL.k, OIL.k][r];
        default: return ix === 0 || ix === 19 ? OIL.P : OIL.Q;
      }
    },
    decor(c) {   // 热油：亮暗两层油光 + 一串串小油泡
      dashes(c, dashList(6, 31), OIL.P);
      dashes(c, dashList(8, 32), OIL.R);
      dashes(c, dashList(3, 33, 2, 2), OIL.S);
      for (const [ix, ly] of [[4, 3], [14, 8], [9, 14], [16, 20], [6, 25], [12, 29]]) ring3(c, 3 + ix, ly, OIL.S, OIL.U);
      for (const [ix, ly] of [[10, 1], [7, 10], [17, 14], [3, 21], [15, 25], [11, 31], [5, 18]]) setPx(c, 3 + ix, midY(ly), OIL.T);
    },
  },
  oven: { // 砖拱炉膛：农夫乐事炉灶砖做墙、拱顶，烟熏黑炉膛，膛底一排炭火
    px({ region, i, r, x, ix, ly }) {
      switch (region) {
        case 'rim': return r === 0 ? (x % 5 === 2 ? BRICK.o : BRICK.r) : (x % 5 === 4 ? BRICK.O : BRICK.B);
        case 'wallL': { const k = ly % 4; return k === 3 ? (i ? BRICK.O : BRICK.o) : k === 0 ? (i ? BRICK.n : BRICK.r) : (i ? BRICK.b : BRICK.n); }
        case 'wallR': { const k = (ly + 2) % 4; return k === 3 ? (i ? BRICK.O : BRICK.O) : k === 0 ? (i ? BRICK.n : BRICK.B) : (i ? BRICK.q : BRICK.b); }
        case 'floor': return r === 0 ? (x % 6 === 0 ? BRICK.O : BRICK.n) : (x % 6 === 3 ? BRICK.O : BRICK.q);
        case 'capTop': {   // 拱顶：三行台阶，每行最里面那块砖被火映亮
          const reach = [2, 1, 0][r], edge = ix === reach || ix === 19 - reach;
          if (ix <= reach || ix >= 19 - reach) return edge ? BRICK.B : BRICK.b;
          return SOOT.a;
        }
        case 'capBot': {
          const n = hash(ix, r, 7);
          if (r === 0) return n < 0.5 ? SOOT.d : FIRE.y;
          if (r === 1) return pick([FIRE.x, FIRE.w, FIRE.w, FIRE.o, SOOT.c], n);
          return pick([FIRE.r, FIRE.o, FIRE.O, FIRE.w, FIRE.O, FIRE.o, FIRE.Y], n);
        }
        default: return SOOT.b;
      }
    },
    decor(c) {   // 炉膛：烟熏的深浅横纹 + 几点飘起的火星
      dashes(c, dashList(9, 41, 2, 5), SOOT.a);
      dashes(c, dashList(6, 42), SOOT.c);
      dashes(c, dashList(3, 43, 1, 1), FIRE.y);
    },
  },
  prep: { // 木案板（农夫乐事砧板木纹，竖纹）+ 备餐台 A 橱柜木框
    px({ region, i, r, x, ix, ly }) {
      const GR = 'abacbaabcabacbbacaba', dk = { a: 'b', b: 'c', c: 'd', d: 'e', e: 'k', k: 'k' };
      switch (region) {
        case 'rim': return r === 0 ? (x === 1 ? CAB.c : x === 24 ? CAB.a : CAB.h) : (x === 1 ? CAB.f : x === 24 ? CAB.e : CAB.b);
        case 'wallL': return i === 0 ? CAB.a : CAB.d;
        case 'wallR': return i === 0 ? CAB.c : CAB.e;
        case 'floor': return r === 0 ? CAB.e : CAB.d;
        case 'capTop': return [BOARD.e, BOARD[dk[GR[ix]]], BOARD[GR[ix]]][r];
        case 'capBot': return [BOARD[dk[GR[ix]]], BOARD.e, BOARD.k][r];
        default: { const key = GR[ix]; return BOARD[hash(ix, Math.floor(ly / 5), 9) < 0.22 ? dk[key] : key]; }
      }
    },
    decor(c) {
      for (const seg of [[[4, 3], [5, 4], [6, 5]], [[13, 12], [14, 13], [15, 14], [16, 15]], [[8, 24], [9, 25], [10, 26]], [[2, 18], [3, 19]]])
        for (const [ix, ly] of seg) setPx(c, 3 + ix, midY(ly), WOOD.h);
      for (const [dx, dy] of [[0, -1], [-1, 0], [1, 0], [0, 1]]) setPx(c, 3 + 16 + dx, midY(22 + dy), BOARD.e);
      setPx(c, 3 + 16, midY(22), BOARD.k);
    },
  },
};
const TRACK_USE = {
  pot: '厨锅轨道：汤锅侧剖面（铁锅壁 + 汤 + 锅底透火）。',
  fryer: '炸锅轨道：油锅侧剖面（铸铁壁 + 金黄热油 + 油泡）。',
  oven: '烤炉轨道：砖拱炉膛（砖墙、拱顶、烟熏黑炉膛、膛底炭火）。',
  prep: '备餐台轨道：木案板（竖向木纹 + 刀痕）+ 橱柜木框。',
};
for (const s of Object.keys(STATIONS))
  add(s, 'track', buildTrack(THEMES[s]), {
    use: `${TRACK_USE[s]}竖向九宫格：只拉高度（中段 32 行平铺），宽度固定 26。内容区 = 火候条和食材活动范围：u 0 = 内容区底边，1,000,000 = 内容区顶边。`,
    slice: { left: 3, top: 6, right: 3, bottom: 6, mode: 'repeat' }, content: { left: 3, top: 3, right: 3, bottom: 3 },
    note: '推荐 1x 尺寸 26×146（内容区 20×140）。像素映射：y = contentBottom − round(u × contentH / 1,000,000)；火候条高 = round(H × contentH / 1,000,000)。',
  });

// ---------------------------------------------------------------- station · 火候条（§6，玩家控制的区间）
// 源图 20×8：宽度 = 轨道内容区宽；竖向九宫格 top 2 / bottom 2，中段 4 行平铺。三种状态：normal 常态、miss 刚丢（条边闪红）、dead 顶死/熄火（火苗变灰）。
const BARS = {
  pot: { pattern: 'glow', what: '发光的火候区',
    normal: { edge: FIRE.Y, ring: FIRE.O, fill: FIRE.O, acc: FIRE.Y }, miss: { edge: MC.red, ring: RED.m, fill: RED.m, acc: '#ff8b8b' }, dead: { edge: FE.M, ring: '#6b6562', fill: '#6b6562', acc: FE.M } },
  fryer: { pattern: 'mesh', what: '漏勺（钢丝网）',
    normal: { edge: FE.C, ring: STEEL.h, fill: FE.A, acc: FE.M }, miss: { edge: RED.d, ring: RED.l, fill: '#4a1410', acc: RED.m }, dead: { edge: FE.B, ring: FE.L, fill: FE.A, acc: FE.H } },
  oven: { pattern: 'grill', what: '烤架上的火区（铁烤架 + 火光）',
    normal: { edge: FE.B, ring: FE.L, fill: FIRE.O, acc: FE.H, glow: FIRE.Y }, miss: { edge: '#4a1410', ring: RED.m, fill: RED.m, acc: RED.d, glow: '#ff8b8b' }, dead: { edge: FE.B, ring: FE.H, fill: FE.E, acc: FE.D, glow: FE.H } },
  prep: { pattern: 'blade', what: '刀面（不锈钢刀身，半透明）',
    normal: { edge: FE.E, ring: STEEL.w, ring2: STEEL.m, fill: STEEL.l, acc: '#ffffff' }, miss: { edge: RED.d, ring: RED.l, ring2: RED.m, fill: RED.m, acc: RED.p }, dead: { edge: FE.B, ring: FE.L, ring2: FE.G, fill: FE.H, acc: FE.L } },
};
function buildBar(pattern, p) {
  const c = makeCanvas(20, 8);
  for (let y = 0; y < 8; y++) for (let x = 0; x < 20; x++) {
    const top = y === 0, bot = y === 7, left = x === 0, right = x === 19;
    if ((top || bot) && (left || right)) continue;
    if (top || bot || left || right) { setPx(c, x, y, p.edge); continue; }
    const ly = (y - 2) & 3, isRing = y === 1 || y === 6 || x === 1 || x === 18;
    let col;
    switch (pattern) {
      case 'glow': col = isRing ? A(p.ring, 215) : ((x * 3 + ly * 5) % 11 === 0 ? A(p.acc, 140) : A(p.fill, 72)); break;
      case 'mesh': col = isRing ? p.ring : (((x - 2) % 4 === 1 || ly === 1) ? A(p.acc, 235) : A(p.fill, 50)); break;
      case 'grill': col = isRing ? p.ring : (ly === 2 ? p.acc : ly === 1 ? A(p.glow, 110) : A(p.fill, 75)); break;
      case 'blade': col = (x === 1 || y === 1) && !(y === 6) ? p.ring : (x === 18 || y === 6) ? p.ring2 : (x === 5 || x === 6) ? A(p.acc, 70) : A(p.fill, 85); break;
    }
    setPx(c, x, y, col);
  }
  return c;
}
for (const [s, b] of Object.entries(BARS)) for (const st of ['normal', 'miss', 'dead'])
  add(s, `bar_${st}`, buildBar(b.pattern, b[st]), {
    use: `${STATIONS[s]}火候条（${b.what}）· ${{ normal: '常态', miss: '刚丢食材：条边闪红（与常态交替 2–3 次）', dead: '顶死 / 熄火（贴边 ≥ 10 tick，这些 tick 不算框住）：变灰' }[st]}。宽度固定 20，只拉高度到 H。画在食材下面。`,
    slice: { left: 2, top: 2, right: 2, bottom: 2, mode: 'repeat' },
  });

// ---------------------------------------------------------------- station · 熟度条填充（§9）+ 底座装饰
const RAMPS = {
  pot: ['#d9a86a', '#b07a3c', '#a8703a', '#a8703a', '#8f5a2a', '#6e4220'],
  fryer: [OIL.U, OIL.S, OIL.R, OIL.R, OIL.Q, OIL.P],
  oven: ['#e8b36a', '#d08a42', '#c27c38', '#c27c38', '#a2632c', '#7a4520'],
  prep: [VG.p, VG.y, '#64ad3e', '#64ad3e', VG.g, VG.G],
};
const RAMP_USE = { pot: '一碗汤慢慢盛满', fryer: '炸到金黄', oven: '烤出焦糖色', prep: '摆得满满当当（鲜绿）' };
function rampTile(cols, hot) {
  const c = makeCanvas(6, 4);
  for (let y = 0; y < 4; y++) for (let x = 0; x < 6; x++) setPx(c, x, y, hot ? lighten(cols[x], 0.22) : cols[x]);
  if (hot) { setPx(c, 1, 1, '#ffffff'); setPx(c, 4, 3, lighten(cols[1], 0.5)); }
  else setPx(c, 1, 2, lighten(cols[1], 0.18));
  return c;
}
for (const [s, cols] of Object.entries(RAMPS)) {
  add(s, 'progress_fill', rampTile(cols, false), { use: `${STATIONS[s]}熟度条填充（${RAMP_USE[s]}）：在 common/gauge_frame 内容区里从底往上平铺到 P/1,000,000。`, slice: { left: 0, top: 0, right: 0, bottom: 0, mode: 'repeat' } });
  add(s, 'progress_fill_hot', rampTile(cols, true), { use: `${STATIONS[s]}熟度条「收汁」段：熟度 ≥ 75% 时整根换成这张（更亮 + 闪点，配音乐加快，§17.4）。`, slice: { left: 0, top: 0, right: 0, bottom: 0, mode: 'repeat' } });
  const top = makeCanvas(6, 2);
  for (let x = 0; x < 6; x++) { setPx(top, x, 0, lighten(cols[x], 0.55)); setPx(top, x, 1, lighten(cols[x], 0.28)); }
  add(s, 'progress_top', top, { use: `${STATIONS[s]}熟度条液面：画在填充顶端（填充高 ≥ 2px 时）。` });
}
const CAPS = {
  pot: { pal: { k: WOOD.b, w: WOOD.f, W: WOOD.h, d: WOOD.d, s: BROTH.h, S: BROTH.f }, rows: ['kkkkkkkkkkkk', 'kSSssssssssk', 'kWwwwwwwwwdk', '.kWwwwwwwdk.', '..kwwwwwdk..', '...kkkkkk...', '....kddk....', '....kkkk....'], what: '木碗（熟度 = 一碗慢慢盛满）' },
  fryer: { pal: { k: FE.D, h: STEEL.h, m: STEEL.d, b: BRASS.l, B: BRASS.d }, rows: ['............', 'kkkkkkkkkkkk', 'khhhhhhhhhhk', 'kmhmhmhmhmhk', '.khmhmhmhmk.', '.kmhmhmhmhk.', '..kkkkkkkk..', '............'], what: '小炸篮' },
  oven: { pal: { k: FE.D, c: '#c27c38', C: '#e8b36a', s: STEEL.l, m: FE.L }, rows: ['............', '...kkkkkk...', '.kkCCCCCCkk.', 'kCCcCCCCcCCk', 'kssssssssssk', '.kmmmmmmmmk.', '..kkkkkkkk..', '............'], what: '派盘' },
  prep: { pal: { k: FE.F, w: ENAMEL.w, s: ENAMEL.s, l: ENAMEL.l }, rows: ['............', '............', '..kkkkkkkk..', '.kwlllllwwk.', 'kwwssssssswk', '.kwwwwwwwwk.', '..kkkkkkkk..', '............'], what: '搪瓷盘' },
};
for (const [s, d] of Object.entries(CAPS)) { const c = makeCanvas(12, 8); rows(c, 0, 0, full(d.rows, 12), d.pal); add(s, 'progress_cap', c, { use: `${STATIONS[s]}熟度条底座装饰（${d.what}）：贴在 gauge_frame 正下方、水平居中（比槽宽 4px）。纯装饰。` }); }

// ---------------------------------------------------------------- 厨锅 · 火苗柱（§13.1 火候 h 0–1000）
function flame(w, h, f, pal, { lean = 0 } = {}) {
  const c = makeCanvas(w, h), cx0 = (w - 1) / 2;
  const sway = [0, 0.7, 0.2, -0.7][f % 4] + lean, stretch = [1, 0.9, 1.06, 0.94][f % 4];
  for (let y = 0; y < h; y++) {
    const t = (h - 1 - y) / Math.max(1, h - 1) / stretch; if (t > 1) continue;
    const half = (w / 2) * Math.pow(1 - t, 0.8) * (t < 0.2 ? 0.8 + t : 1);
    const cx = cx0 + sway * t * 2;
    for (let x = 0; x < w; x++) {
      const d = Math.abs(x - cx); if (d > half) continue;
      const u = d / Math.max(half, 0.5);
      setPx(c, x, y, u < 0.38 && t < 0.62 ? pal.core : u < 0.72 && t < 0.86 ? pal.mid : pal.outer);
    }
  }
  return c;
}
const HEAT = {
  low: { outer: FIRE.y, mid: FIRE.w, core: FIRE.o, cols: [FIRE.o, FIRE.o, FIRE.w, FIRE.w, FIRE.x, FIRE.y], what: '小火（h < 400）' },
  mid: { outer: FIRE.w, mid: FIRE.o, core: FIRE.O, cols: [FIRE.O, FIRE.O, FIRE.o, FIRE.o, FIRE.w, FIRE.x], what: '中火（400 ≤ h < 800）' },
  high: { outer: FIRE.o, mid: FIRE.O, core: FIRE.Y, cols: [FIRE.L, FIRE.Y, FIRE.O, FIRE.O, FIRE.o, FIRE.w], what: '旺火（h ≥ 800）' },
  dead: { outer: FE.H, mid: FE.L, core: FE.M, cols: [FE.M, FE.M, FE.L, FE.L, FE.H, FE.G], what: '熄火 / 顶死（火苗变灰）' },
};
for (const [k, p] of Object.entries(HEAT)) {
  const t = makeCanvas(6, 4); for (let y = 0; y < 4; y++) for (let x = 0; x < 6; x++) setPx(t, x, y, p.cols[x]);
  add('pot', `heat_fill_${k}`, t, { use: `火苗柱填充 · ${p.what}：在 common/gauge_frame 内容区从底往上平铺到 h/1000。`, slice: { left: 0, top: 0, right: 0, bottom: 0, mode: 'repeat' } });
  if (k === 'dead') add('pot', 'heat_tip_dead', flame(8, 10, 0, p), { use: '火苗柱顶的灰火苗（熄火 / 顶死时）。底边对齐填充顶。', anchor: 'bottom-center' });
  else addAnim('pot', `heat_tip_${k}`, [0, 1, 2, 3].map(f => flame(8, 10, f, p)), { ms: 110, use: `火苗柱顶的火苗 · ${p.what}（4 帧循环）。底边对齐填充顶、水平居中于槽。`, anchor: 'bottom-center' });
}
{ // 小火 / 中火 / 旺火 档位图标（一 / 两 / 三簇火）
  const tiers = { small: [[4, 5, 6]], mid: [[1, 5, 7], [5, 5, 9]], big: [[0, 5, 7], [3, 6, 10], [7, 5, 7]] };   // [x, w, h]，底边对齐
  const which = { small: HEAT.low, mid: HEAT.mid, big: HEAT.high };
  for (const [k, list] of Object.entries(tiers)) {
    const c = makeCanvas(14, 12);
    for (const [x, w, h] of list) over(c, flame(w, h, 0, which[k]), 1 + x, 1 + (10 - h));
    outline(c, FIRE.k);
    add('pot', `flame_${k}`, c, { use: `火候档位图标：${{ small: '小火', mid: '中火', big: '旺火' }[k]}（画在火苗柱旁，当前档亮、其余用 grey 版本或半透明）。` });
  }
}
{ // 汤面冒泡（h 满时，§13.1）
  const B = [[3, 0], [8, 2], [13, 1], [17, 3]];
  const frames = [0, 1, 2, 3].map(f => {
    const c = makeCanvas(20, 6);
    for (const [x, ph] of B) {
      const s = (f + ph) % 4;
      if (s === 0) setPx(c, x, 4, BROTH.e);
      if (s === 1) { setPx(c, x, 2, BROTH.e); setPx(c, x - 1, 3, BROTH.f); setPx(c, x + 1, 3, BROTH.f); setPx(c, x, 4, BROTH.f); setPx(c, x - 1, 2, A('#ffffff', 200)); }
      if (s === 2) { for (const [dx, dy] of [[-1, 1], [0, 1], [1, 1], [-2, 2], [2, 2], [-2, 3], [2, 3], [-1, 4], [0, 4], [1, 4]]) setPx(c, x + dx, dy, BROTH.e); setPx(c, x - 1, 2, A('#ffffff', 220)); }
      if (s === 3) { for (const [dx, dy] of [[-2, 0], [2, 0], [0, 1], [-3, 3], [3, 3]]) setPx(c, x + dx, dy, A(BROTH.e, 200)); }
    }
    return c;
  });
  addAnim('pot', 'bubbles', frames, { ms: 120, use: '汤面冒泡（h 满 1000 时，4 帧循环，配咕嘟声）：贴在轨道内容区顶端（y = contentTop）。' });
}

// ---------------------------------------------------------------- 炸锅 · 油温计（§13.2 T 0–1000；冷油 < 300 / 金黄 300–749 / 过热 750–999 / 爆油 1000）
{
  const W = 12, H = 24, c = makeCanvas(W, H);
  const COLS = [BRASS.d, BRASS.h, BRASS.l, BRASS.m, null, null, null, null, BRASS.m, BRASS.l, BRASS.m, BRASS.d];
  for (let y = 2; y < 12; y++) COLS.forEach((col, x) => setPx(c, x, y, col ?? FE.A));
  rows(c, 0, 0, full(['..dddddddd..', '.dhhhhhhhlmd'], 12), { d: BRASS.d, h: BRASS.h, l: BRASS.l, m: BRASS.m });
  rows(c, 0, 2, full(['dhlmmmmmmlmd'], 12), { d: BRASS.d, h: BRASS.h, l: BRASS.l, m: BRASS.m });   // 管顶封口
  // 球泡（底段 12 行）：黄铜托 + 红球
  ellipse(c, 6, 17.5, 5.6, 5.6, (x, y, d, dx, dy) => (d > 0.8 ? BRASS.d : d > 0.62 ? (dy < 0 ? BRASS.l : BRASS.m) : (dx < -0.15 && dy < -0.1 ? (d < 0.3 ? RED.p : RED.l) : dx + dy > 0.35 ? RED.d : RED.m)));
  for (let y = 9; y < 14; y++) for (let x = 4; x < 8; x++) if (getPx(c, x, y)[3] && y >= 12) setPx(c, x, y, x === 4 ? RED.l : RED.m);
  add('fryer', 'thermo_frame', c, {
    use: '油温计外壳（暗黄铜 + 玻璃管 + 红球泡）：竖向九宫格，只拉高度（推荐与轨道同高）。玻璃管内容区里先按比例铺三色带（thermo_band_*），再从底往上画油温柱 thermo_column。',
    slice: { left: 4, top: 3, right: 4, bottom: 12, mode: 'repeat' }, content: { left: 4, top: 3, right: 4, bottom: 12 },
    note: '内容区宽 4：带子铺满 4 列，油温柱画在中间 2 列（内容区 x+1..x+2）。T → 高度：y = contentBottom − round(T × contentH / 1000)。',
  });
  const band = (a, b) => { const t = makeCanvas(4, 4, a); for (let y = 0; y < 4; y++) setPx(t, 0, y, b); setPx(t, 3, (1), b); return t; };
  add('fryer', 'thermo_band_cold', band(BLUE.d, BLUE.m), { use: '油温计色带 · 冷油（T < 300，蓝）。玻璃管内容区里 0–30% 高度平铺。', slice: { left: 0, top: 0, right: 0, bottom: 0, mode: 'repeat' } });
  add('fryer', 'thermo_band_gold', band('#8a6428', '#a8803a'), { use: '油温计色带 · 金黄区（300 ≤ T < 750）。30–75% 高度平铺。', slice: { left: 0, top: 0, right: 0, bottom: 0, mode: 'repeat' } });
  add('fryer', 'thermo_band_hot', band('#6b1d17', RED.d), { use: '油温计色带 · 过热（750 ≤ T < 1000，红）。75–100% 高度平铺。', slice: { left: 0, top: 0, right: 0, bottom: 0, mode: 'repeat' } });
  const col = makeCanvas(2, 2); for (let y = 0; y < 2; y++) { setPx(col, 0, y, '#e8604a'); setPx(col, 1, y, RED.m); }
  add('fryer', 'thermo_column', col, { use: '油温柱（红）：内容区中间 2 列，从底往上平铺到 T。', slice: { left: 0, top: 0, right: 0, bottom: 0, mode: 'repeat' } });
  const ct = makeCanvas(2, 1); setPx(ct, 0, 0, RED.p); setPx(ct, 1, 0, RED.l);
  add('fryer', 'thermo_column_top', ct, { use: '油温柱顶端高光（1 行）。' });
  add('fryer', 'thermo_pointer', tri('#ffffff', V.S), { use: '油温指针 ◀：画在油温计右侧、尖端对准当前 T 高度（anchor = 左边中点）。', anchor: 'left-center' });
  const tick = makeCanvas(3, 1, BRASS.h);
  add('fryer', 'thermo_tick', tick, { use: '色带分界刻度（300 / 750）：画在油温计左外侧。' });
}
{ // 压火冷却环（§13.2 oilCd 25–34 tick）
  const frames = [];
  for (let k = 0; k <= 8; k++) {
    const c = makeCanvas(17, 17), rem = (8 - k) / 8;
    ring(c, 8.5, 8.5, 6.1, 8.3, (x, y, a) => (a < rem ? BLUE.l : FE.E));
    ring(c, 8.5, 8.5, 5.4, 6.1, (x, y, a) => (a < rem ? A(BLUE.m, 160) : A(FE.C, 120)));
    frames.push(c);
  }
  addAnim('fryer', 'cooldown_ring', frames, { ms: 0, use: '压火冷却环（9 帧，不自动播）：帧号 = round(8 × (1 − cd / oilCd))，帧 0 = 刚压过（满蓝），帧 8 = 冷却结束。中间放 common/keycap（11×12，偏移 (3,2)）。', note: 'ms = 0 表示按状态选帧，不按时间播放。' });
  const ready = makeCanvas(17, 17);
  ring(ready, 8.5, 8.5, 6.1, 8.3, () => GOLD.Y); ring(ready, 8.5, 8.5, 5.4, 6.1, () => A(GOLD.L, 170)); ring(ready, 8.5, 8.5, 8.3, 8.9, () => A(GOLD.L, 90));
  add('fryer', 'cooldown_ready', ready, { use: '压火可用：金圈（cd == 0）。' });
}
{ // 爆油：溅油花（§13.2 爆油 T ≥ 1000）
  const N = 14, frames = [];
  for (let f = 0; f < 5; f++) {
    const c = makeCanvas(28, 22), t = f + 1;
    if (f <= 1) for (let x = 5; x < 23; x++) { const h = Math.round(2.5 - Math.abs(x - 13.5) / 4) + (x % 3 === 0 ? 1 : 0) - f; for (let y = 0; y < h; y++) setPx(c, x, 21 - y, A(y === h - 1 ? OIL.U : OIL.S, 230 - f * 70)); }
    for (let i = 0; i < N; i++) {
      const vx = (hash(i, 1, 11) - 0.5) * 5.5, vy = -(2.8 + hash(i, 2, 11) * 3);
      const x = Math.round(14 + vx * t + (hash(i, 3, 11) - 0.5) * 6), y = Math.round(20 + vy * t + 0.55 * t * t);
      const col = [OIL.U, '#ffffff', OIL.T, OIL.U][i % 4], a = f < 3 ? 255 : 255 - (f - 2) * 75;
      if (y < 0 || y >= 21) continue;
      setPx(c, x, y, A(col, a));
      if (f < 3) { setPx(c, x + 1, y, A(OIL.T, a)); setPx(c, x, y + 1, A(OIL.T, a)); setPx(c, x + 1, y + 1, A(OIL.k, a)); }   // 右下压一个深油色，盖在油面上也分得出
      else setPx(c, x + 1, y + 1, A(OIL.k, a));
    }
    frames.push(c);
  }
  addAnim('fryer', 'oil_splash', frames, { ms: 60, use: '爆油：溅油花（5 帧播一次），底边中点对齐食材位置；同时界面抖一下、「滋啦」。', anchor: 'bottom-center' });
}

// ---------------------------------------------------------------- 烤炉 · 炉火、翻面（§13.3）
{
  const frames = [0, 1, 2, 3].map(f => {
    const c = makeCanvas(20, 8);
    const T = [[0, 4, 5], [4, 6, 8], [9, 4, 6], [12, 5, 7], [16, 4, 5]];
    T.forEach(([x, w, h], i) => { const hh = Math.max(3, h - ((f + i) % 3 === 0 ? 2 : (f + i) % 3 === 1 ? 0 : 1)); over(c, flame(w, hh, (f + i) % 4, HEAT.high), x, 8 - hh); });
    return c;
  });
  addAnim('oven', 'flames', frames, { ms: 120, use: '膛底火苗（4 帧循环）：贴在轨道内容区底边（底对齐 contentBottom），画在轨道之上、火候条之下。' });
  const g = makeCanvas(20, 24);
  for (let y = 0; y < 24; y++) for (let x = 0; x < 20; x++) { const t = Math.pow(y / 23, 1.6) * 0.75; if (BAYER4[y % 4][x % 4] / 16 < t) setPx(g, x, y, A(FIRE.O, 70)); }
  add('oven', 'glow', g, { use: '火光（抖动点阵的橙色半透明）：贴在轨道内容区底部，画在 flames 之下，让炉膛下半截被火映亮。宽度固定 20。' });
}
{
  const STATES = { pending: ['#ffffff', '还没到'], now: [GOLD.Y, '「翻面！」提示生效中（可与 pending 交替闪）'], good: [MC.green, '正好'], late: [MC.yellow, '稍晚'], early: ['#7f8fff', '翻早'], burnt: ['#c8823c', '焦面'], auto: [MC.red, '自动翻'] };
  for (const [k, [col, what]] of Object.entries(STATES)) add('oven', `flip_mark_${k}`, tri(col, V.S), { use: `翻面点三角 ◀ · ${what}：画在熟度条右侧，尖端对准 mark_k 高度（anchor = 左边中点）。`, anchor: 'left-center' });
}
{
  const c = makeCanvas(12, 13);
  for (let y = 0; y < 13; y++) for (let x = 0; x < 12; x++) {
    const corner = (x < 2 || x > 9) && (y < 2 || y > 10);
    if (corner && !((x === 1 || x === 10) && (y === 1 || y === 11))) continue;
    let col = FIRE.o;
    if (x === 0 || x === 11 || y === 0 || y === 12 || corner) col = FIRE.x;
    else if (y === 1) col = FIRE.Y;
    else if (y === 2) col = FIRE.O;
    else if (y >= 10) col = FIRE.w;
    setPx(c, x, y, col);
  }
  add('oven', 'flip_prompt', c, { use: '「翻面！」大字横幅底（字用游戏字体白字 + 阴影，可 ×2）：提示生效时画在轨道上方或右侧。', slice: { left: 3, top: 3, right: 3, bottom: 3, mode: 'stretch' }, content: { left: 3, top: 3, right: 3, bottom: 3 } });
}
{ // 锅铲 + 翻转弧箭头
  const c = makeCanvas(16, 16);
  // 铲面：沿铲柄方向的矩形（长 6.4、宽 5.2），两道镂空槽；铲柄：木柄斜向左下
  const u = [Math.SQRT1_2, -Math.SQRT1_2], v = [Math.SQRT1_2, Math.SQRT1_2], P0 = [9, 8], P1 = [13.5, 3.5], hw = 2.6;
  const at = (p, s, t) => [p[0] + v[0] * s + u[0] * t, p[1] + v[1] * s + u[1] * t];
  const st = (x, y) => { const d = [x + 0.5 - P0[0], y + 0.5 - P0[1]]; return [d[0] * v[0] + d[1] * v[1], d[0] * u[0] + d[1] * u[1]]; };
  polygon(c, [at(P0, -hw, 0), at(P1, -hw, 0), at(P1, hw, 0), at(P0, hw, 0)], (x, y) => {
    const [s, t] = st(x, y);
    if (Math.abs(Math.abs(s) - 1.1) < 0.5 && t > 1.4 && t < 5.4) return STEEL.d;
    return s < -1.6 || t > 5.6 ? STEEL.w : s > 1.8 ? STEEL.m : STEEL.l;
  });
  polygon(c, [at(P0, -1.1, 0.6), at(P0, 1.1, 0.6), at(P0, 1.1, -9.2), at(P0, -1.1, -9.2)], (x, y) => (st(x, y)[0] < 0 ? WOOD.f : WOOD.c));
  outline(c, FE.B);
  const ar = makeCanvas(16, 16);
  rows(ar, 1, 1, full(['.yyy..', 'y...y.', 'y...yyy', '.....y.'].map(r => r.padEnd(7, '.')), 7), { y: GOLD.Y });
  outline(ar, GOLD.k);
  over(c, ar, 0, 0);
  add('oven', 'flip_icon', c, { use: '翻面图标（锅铲 + 翻转箭头）：放在「翻面！」横幅左侧或键帽旁。' });
}
{ // 翻面计时环：正好窗口（绿）→ 稍晚窗口（黄）→ 焦（红）
  const PH = { good: MC.green, late: MC.yellow, burn: MC.red };
  for (const [k, col] of Object.entries(PH)) {
    const frames = [];
    for (let i = 0; i < 16; i++) {
      const c = makeCanvas(15, 15), rem = (16 - i) / 16;
      ring(c, 7.5, 7.5, 4.6, 7.0, (x, y, a, d) => (a < rem ? (d > 6.3 ? darken(col, 0.25) : col) : (d > 6.3 ? FE.B : FE.E)));
      frames.push(c);
    }
    addAnim('oven', `flip_ring_${k}`, frames, { ms: 0, use: `翻面计时环 · ${{ good: '正好窗口（dt ≤ flipGood）', late: '稍晚窗口（flipGood < dt ≤ flipGood+12）', burn: '焦面（再晚：开始烤焦，扣分逐 tick 涨）' }[k]}。16 帧按状态选：帧号 = floor(16 × 已过 / 窗口长)。`, note: 'ms = 0：按状态选帧。焦面阶段可停在帧 15 并闪。' });
  }
}
{ // 烤焦冒烟
  const frames = [0, 1, 2, 3].map(f => {
    const c = makeCanvas(16, 16);
    for (let i = 0; i < 3; i++) {
      const life = (f + i * 1.33) % 4, y = 13 - life * 3.2, x = 8 + Math.sin((life + i) * 1.7) * 2.2, r = 1.6 + life * 0.55, a = Math.round(220 - life * 50);
      ellipse(c, x, y, r, r * 0.9, (px, py, d) => A(d < 0.55 ? STEEL.w : d < 0.85 ? V.C : FE.M, a));
    }
    return c;
  });
  addAnim('oven', 'burn_smoke', frames, { ms: 140, use: '烤焦冒烟（4 帧循环）：焦面阶段画在食材上方，底边中点对齐食材。', anchor: 'bottom-center' });
}

// ---------------------------------------------------------------- 备餐台 · 节拍道（§13.4 / §13.5）
{
  const W = 32, H = 20, c = makeCanvas(W, H);
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
    let col;
    if (y === 0) col = x === W - 1 ? V.G : V.S;
    else if (y === H - 1) col = x === 0 ? V.G : V.W;
    else if (x === 0) col = V.S;
    else if (x === W - 1) col = V.W;
    else if (y === 1) col = CAB.d;
    else if (y === H - 2) col = CAB.e;
    else { const k = 'kekeekekkeek'[y % 12], n = hash(x % 24, y, 12); col = n < 0.18 ? BOARD.k : BOARD[k === 'k' ? 'e' : 'd']; if (y === 9 || y === 10) col = mix(col, '#000000', 0.18); }
    setPx(c, x, y, col);
  }
  add('prep', 'beat_lane', c, { use: '摆料段节拍道（横向，深色木条）：进入摆料段时出现在轨道正下方，图标从左往右滑向右端的盘子。横向九宫格，只拉宽度（中段 24 列平铺）。', slice: { left: 4, top: 4, right: 4, bottom: 4, mode: 'repeat' }, content: { left: 1, top: 2, right: 1, bottom: 2 }, note: '内容区高 16 = 节拍图标 16×16；图标中心线 = 内容区中线。' });
  const plate = makeCanvas(22, 22);
  ellipse(plate, 11, 11, 10, 10, (x, y, d, dx, dy) => (d > 0.9 ? FE.F : d > 0.68 ? (dx + dy < -0.6 ? ENAMEL.l : ENAMEL.w) : d > 0.6 ? ENAMEL.k : (dx + dy < -0.5 ? ENAMEL.l : ENAMEL.m)));
  add('prep', 'beat_plate', plate, { use: '节拍道终点的盘子（判定点）：中心 = 判定线，画在节拍道右端、竖直居中。汉堡等可在盘子上一层层摞图标。', anchor: 'center' });
  const shaker = makeCanvas(22, 22);
  polygon(shaker, [[6, 9], [16, 9], [14.6, 21], [7.4, 21]], (x, y) => [STEEL.w, STEEL.h, STEEL.h, STEEL.l, STEEL.l, STEEL.l, STEEL.m, STEEL.m, STEEL.d, STEEL.d][Math.max(0, Math.min(9, x - 6))]);
  polygon(shaker, [[7, 4], [15, 4], [16, 8], [6, 8]], (x, y) => (x < 9 ? STEEL.w : x < 13 ? STEEL.h : STEEL.m));
  polygon(shaker, [[9.5, 1], [12.5, 1], [13, 4], [9, 4]], (x) => (x < 11 ? STEEL.h : STEEL.l));
  for (let x = 6; x < 16; x++) if (getPx(shaker, x, 8)[3]) setPx(shaker, x, 8, STEEL.d);
  outline(shaker, FE.C);
  add('prep', 'beat_shaker', shaker, { use: '冷饮「摇杯」皮的判定点（雪克杯，§13.5）：替换 beat_plate；拍点就是摇一下（可左右晃 1px）。', anchor: 'center' });
  const RINGS = { perfect: [GOLD.Y, '正好（|d| ≤ 1）'], ok: ['#ffffff', '可以（|d| ≤ goodW）'], miss: [MC.red, '漏拍 / 乱按'] };
  for (const [k, [col, what]] of Object.entries(RINGS)) {
    const r = makeCanvas(26, 26);
    ring(r, 13, 13, 10.4, 12.6, (x, y, a, d) => A(col, d > 11.8 ? 120 : 240));
    add('prep', `beat_ring_${k}`, r, { use: `判定闪圈 · ${what}：盖在盘子上 4–6 tick（中心对齐盘子中心）。`, anchor: 'center' });
  }
  const bulb = on => { const b = makeCanvas(7, 7); ellipse(b, 3.5, 3.5, 3.5, 3.5, (x, y, d, dx, dy) => (d > 0.78 ? (on ? GOLD.d : FE.A) : (dx < -0.1 && dy < -0.1 && d < 0.6 ? (on ? '#ffffff' : FE.H) : on ? GOLD.Y : FE.E))); return b; };
  add('prep', 'beat_light_off', bulb(false), { use: '节拍灯（灭）：节拍道上方一排，每拍一个。' });
  add('prep', 'beat_light_on', bulb(true), { use: '节拍灯（亮）：拍点那一 tick 点亮（客户端按帧时钟，配「嗒」）。' });
  const cube = makeCanvas(16, 16);
  polygon(cube, [[8, 2], [14, 5], [8, 8], [2, 5]], () => A('#d8eef8', 235));
  polygon(cube, [[2, 5], [8, 8], [8, 14.5], [2, 11.5]], () => A('#9ccbe6', 235));
  polygon(cube, [[8, 8], [14, 5], [14, 11.5], [8, 14.5]], () => A('#6fa8d0', 235));
  outline(cube, BLUE.m);
  for (const [x, y] of [[4, 5], [5, 5], [6, 4], [3, 7], [3, 8]]) setPx(cube, x, y, '#ffffff');
  add('prep', 'ice_cube', cube, { use: '冷饮皮的节拍图标（冰块，§13.5）。其余菜的节拍图标用这道菜的食材图标。' });
  const dim = makeCanvas(4, 4, A('#000000', 110));
  add('prep', 'track_dim', dim, { use: '摆料段时盖在轨道内容区上的暗幕（食材、火候条冻结）。平铺。', slice: { left: 0, top: 0, right: 0, bottom: 0, mode: 'repeat' } });
  for (const [k, col, what] of [['pending', ENAMEL.w, '还没到'], ['done', VG.y, '摆完']]) add('prep', `seg_mark_${k}`, tri(col, V.S), { use: `摆料点三角 ◀ · ${what}：画在熟度条右侧，尖端对准 segMark_k 高度。`, anchor: 'left-center' });
}

// ---------------------------------------------------------------- 台子图标（标题行 / 练习模式选台）
{
  const P = { k: FE.A, L: FE.L, H: FE.H, G: FE.G, F: FE.F, E: FE.E, o: FIRE.o, O: FIRE.O, Y: FIRE.Y, s: BROTH.h, S: BROTH.f, Q: OIL.Q, U: OIL.U, R: OIL.R, m: STEEL.l, b: BRICK.B, n: BRICK.n, q: BRICK.q, r: BRICK.r, x: FIRE.x, a: BOARD.a, d: BOARD.d, e: BOARD.e, w: STEEL.w, h: WOOD.c, t: '#c0341f', c: '#7ab244' };
  const ICONS = {
    pot: ['................', '................', '.......kk.......', '......kLHk......', '...kkkkkkkkkk...', '..kLLLLLLLLLHk..', '..kkkkkkkkkkkk..', 'kkkHHHHHHHHHHkkk', 'kGkHGGGGGGGGHkGk', 'kkkGGGGGGGGGGkkk', '..kGFFFFFFFFGk..', '..kFFFFFFFFFFk..', '..kFEEEEEEEEFk..', '...kEEEEEEEEk...', '....kkkkkkkk....', '...oOoYoOoYoO...'],
    fryer: ['.........kk.....', '........kmk.....', '.......kmk......', '...kkkkmkkkkk...', '..kHmmmmmmmmHk..', 'kkkUUUUUUUUUUkkk', 'kGkQUQRQQRQUQkGk', 'kkkQRQQRQQRQQkkk', '..kFQQRQQQRQFk..', '..kFRQQQRQQQFk..', '..kFEEEEEEEEFk..', '...kEEEEEEEEk...', '....kkkkkkkk....', '................', '...oOoYoOoYoO...', '................'],
    oven: ['................', '.....kkkkkk.....', '...kkrrbbrrkk...', '..krrbbrrbbrrk..', '.krbbrkkkkrbbrk.', '.krrbkxxxxkbrrk.', 'krbbkxxxxxxkbbrk', 'krrbkxxOxxxkrrbk', 'kbbrkxOYOxxkbbrk', 'krrbkoYYOOokrrbk', 'kbbrkOYYYYOkbbrk', 'kqqqkkkkkkkkqqqk', 'knnnnnnnnnnnnnnk', 'kkkkkkkkkkkkkkkk', '................', '................'],
    prep: ['................', '................', '.........kk.....', '........kwk.....', '.......kwmk.....', '......kwmk......', '..kkkkhhkkkkkk..', '.kaaaahhaaaaaak.', '.kaddaaattaadak.', '.kaaaaaatttaaak.', '.kadaacccaaadak.', '.kaaaacccaaaaak.', '.kdaaaaaaaadaak.', '.keeeeeeeeeeeek.', '..kkkkkkkkkkkk..', '................'],
  };
  for (const [s, r] of Object.entries(ICONS)) { const c = makeCanvas(16, 16); rows(c, 0, 0, full(r, 16), P); add(s, 'icon', c, { use: `${STATIONS[s]}台子图标 16×16（标题行、练习模式选台、按钮前缀）。` }); }
}

// ================================================================ dishes（dish-stars.tsv → mod jar 原物品贴图）
const tsv = fs.readFileSync(TSV, 'utf8').replace(/^\uFEFF/, '').split(/\r?\n/).filter(Boolean).map(l => l.split('\t'));
const head = tsv.shift();
const COL = Object.fromEntries(head.map((h, i) => [h, i]));
const DISH = new Map(tsv.map(r => [r[COL['物品ID']], { star: +r[COL['星级']].replace('★', ''), score: +r[COL['分数']], name: r[COL['菜名(中文)']], id: r[COL['物品ID']], mod: r[COL['来源mod']], method: r[COL['制作方式']] }]));
// 每台 ★1–★7 各一道。note 写明「某台某星级没有菜就取最接近的」。
const PICKS = {
  pot: [
    { id: 'farmersdelight:bone_broth' }, { id: 'farmersdelight:beef_stew' }, { id: 'farmersdelight:fried_rice' },
    { id: 'dumplings_delight:pork_cabbage_wonton' }, { id: 'crabbersdelight:clam_bake' }, { id: 'culturaldelights:spicy_curry' },
    { id: 'brewinandchewin:fiery_fondue_pot', note: '厨锅（炼药锅作容器）· 盛宴' },
  ],
  fryer: [
    { id: 'oceansdelight:honey_fried_kelp', name: '蜂蜜炸海带', note: '炸物里没有 ★1：取最接近的 ★2 蜂蜜炸海带（stations.md 列为可从厨锅迁入炸锅）；TSV 里中文名缺，用 stations.md 的译名。' },
    { id: 'casualness_delight:spring_roll' }, { id: 'casualness_delight:fried_dumpling' },
    { id: 'casualness_delight:plate_of_fried_dumpling', note: '工作台 · 盛宴（子菜需炸锅）；spec §13.2 列为炸锅菜。' },
    { id: 'casualness_delight:spring_roll_medley', note: '炸物里没有 ★5：取最接近的 ★4 春卷拼盘。' },
    { id: 'delightful:crab_rangoon', note: '炸物里没有 ★6：取最接近的 ★4 炸蟹角（可从厨锅迁入）。' },
    { id: 'culturaldelights:empanada', note: '炸物里没有 ★7：取最接近的 ★4 恩潘纳达炸饺（可从厨锅迁入）。' },
  ],
  oven: [
    { id: 'farmersdelight:sweet_berry_cookie', flips: 1 }, { id: 'farmersdelight:barbecue_stick', flips: 3 }, { id: 'farmersdelight:roasted_mutton_chops', flips: 1 },
    { id: 'farmersdelight:apple_pie', flips: 2 }, { id: 'farmersdelight:roast_chicken_block', flips: 2 }, { id: 'brewinandchewin:pizza', flips: 2 }, { id: 'delightful:baklava', flips: 2 },
  ],
  prep: [
    { id: 'farmersdelight:egg_sandwich' }, { id: 'farmersdelight:salmon_roll' },
    { id: 'delightful:salmonberry_milkshake', skin: 'drink', note: '冷饮并进备餐台，用「摇杯」皮（§13.5）。' },
    { id: 'culturaldelights:hearty_salad' }, { id: 'moredelight:loaded_hamburger' }, { id: 'culturaldelights:chicken_taco' },
    { id: 'culturaldelights:exotic_roll_medley', note: '备餐台没有 ★7：取最接近的 ★6 热带寿司拼盘（工作台 · 盛宴）。' },
  ],
};
const JAR_RE = {
  farmersdelight: /^FarmersDelight-.*\.jar$/i, culturaldelights: /^culturaldelights-.*\.jar$/i, brewinandchewin: /^BrewinAndChewin-.*\.jar$/i,
  crabbersdelight: /^crabbersdelight-.*\.jar$/i, delightful: /^Delightful-.*\.jar$/i, moredelight: /^moredelight-.*\.jar$/i,
  oceansdelight: /^oceansdelight-.*\.jar$/i, casualness_delight: /^casualness_delight-.*\.jar$/i, dumplings_delight: /^Dumplings Delight-.*\.jar$/i,
};
const jarFiles = fs.readdirSync(MODS).filter(f => f.endsWith('.jar'));
const ZIPS = {};
function zipFor(ns) {
  if (ZIPS[ns]) return ZIPS[ns];
  const re = JAR_RE[ns]; if (!re) throw new Error('no jar rule for namespace ' + ns);
  const hits = jarFiles.filter(f => re.test(f));
  if (hits.length !== 1) throw new Error(`namespace ${ns}: expected 1 jar, found ${hits.length} (${hits.join(', ')})`);
  return (ZIPS[ns] = { zip: openZip(path.join(MODS, hits[0])), jar: hits[0] });
}
function loadTexture(z, ref, ns0) {
  const [ns, p] = ref.includes(':') ? ref.split(':') : ['minecraft', ref];
  if (ns !== ns0) throw new Error(`texture ${ref} lives outside ${ns0}`);
  const file = `assets/${ns}/textures/${p}.png`, buf = z.read(file);
  if (!buf) throw new Error('missing texture ' + file);
  const d = decodePNG(buf), img = { width: d.width, height: d.height, data: d.data };
  const animated = z.has(file + '.mcmeta') || d.height > d.width;
  const frame0 = animated ? crop(img, 0, 0, d.width, d.width) : img;     // 动画取第 0 帧（竖条最上面一格）
  if (frame0.width !== 16 || frame0.height !== 16) throw new Error(`${file} is ${frame0.width}×${frame0.height}, expected 16×16`);
  return { canvas: frame0, file, animated };
}
function dishIcon(id) {
  const [ns, name] = id.split(':');
  const { zip: z, jar } = zipFor(ns);
  let modelPath = `assets/${ns}/models/item/${name}.json`, layers = null, chain = [];
  for (let guard = 0; guard < 8 && !layers; guard++) {
    const txt = z.text(modelPath); if (!txt) break;
    chain.push(modelPath);
    const m = JSON.parse(txt), tex = m.textures || {};
    const ls = Object.keys(tex).filter(k => /^layer\d+$/.test(k)).sort((a, b) => +a.slice(5) - +b.slice(5));
    if (ls.length) { layers = ls.map(k => tex[k]); break; }
    const parent = m.parent || '';
    const [pns, pp] = parent.includes(':') ? parent.split(':') : ['minecraft', parent];
    if (pns !== ns || !pp.startsWith('item/')) break;
    modelPath = `assets/${ns}/models/${pp}.json`;
  }
  if (!layers) layers = [`${ns}:item/${name}`];    // 方块模型做物品时的兜底：同名物品贴图
  const c = makeCanvas(16, 16), files = [];
  let animated = false;
  for (const ref of layers) { const t = loadTexture(z, ref, ns); over(c, t.canvas, 0, 0); files.push(t.file); animated ||= t.animated; }
  return { canvas: c, jar, model: chain[0] || null, textures: files, animated };
}
const dishOut = { contentHash: null, source: { tsv: '../../dish-stars.tsv', modsDir: MODS }, note: '每台 ★1–★7 各一道示例菜。star = 这道菜在 dish-stars.tsv 里的真实星级；slotStar = 它在本台示例里代表的星级（不一致时见 note）。icon = 原物品贴图 16×16；trackIcon = 加 1px 深色描边的 18×18（画在轨道上更清楚）。', stations: {} };
const dishGrid = { raw: makeCanvas(7 * 16, 4 * 16), track: makeCanvas(7 * 18, 4 * 18) };
Object.entries(PICKS).forEach(([s, list], row) => {
  if (list.length !== 7) throw new Error(`${s}: need 7 picks`);
  dishOut.stations[s] = { name: STATIONS[s], dishes: [] };
  list.forEach((p, col) => {
    const d = DISH.get(p.id); if (!d) throw new Error(`${p.id} not in dish-stars.tsv`);
    const slotStar = col + 1;
    if (d.star !== slotStar && !p.note) throw new Error(`${p.id} is ★${d.star} but sits in ★${slotStar} without a note`);
    const ic = dishIcon(p.id);
    const base = p.id.replace(':', '__');
    const iconFile = path.join(ART, 'icons', 'dishes', base + '.png'), trackFile = path.join(ART, 'icons', 'track', base + '.png');
    write(ic.canvas, iconFile);
    const tr = outline(pad(ic.canvas, 1), A(FE.A, 210), { min: 96 });
    write(tr, trackFile);
    over(dishGrid.raw, ic.canvas, col * 16, row * 16); over(dishGrid.track, tr, col * 18, row * 18);
    const name = p.name || d.name;
    dishOut.stations[s].dishes.push({
      slotStar, star: d.star, id: p.id, name, mod: d.mod, method: d.method,
      icon: rel(iconFile), trackIcon: rel(trackFile),
      atlas: { raw: { file: 'atlas/dishes.png', rect: [col * 16, row * 16, 16, 16] }, track: { file: 'atlas/dishes_track.png', rect: [col * 18, row * 18, 18, 18] } },
      texture: { jar: ic.jar, model: ic.model, files: ic.textures, animated: ic.animated },
      ...(p.flips ? { flips: p.flips } : {}), ...(p.skin ? { skin: p.skin } : {}),
      ...(p.note ? { note: p.note } : {}),
    });
  });
});
write(dishGrid.raw, path.join(ART, 'atlas', 'dishes.png'));
write(dishGrid.track, path.join(ART, 'atlas', 'dishes_track.png'));

// ================================================================ write sprites + atlases
function packShelf(items, maxW = 256, gap = 1) {
  const sorted = [...items].sort((a, b) => b.canvas.height - a.canvas.height || b.canvas.width - a.canvas.width || (a.key < b.key ? -1 : 1));
  let x = 0, y = 0, rowH = 0, usedW = 0;
  const rects = new Map();
  for (const it of sorted) {
    const w = it.canvas.width, h = it.canvas.height;
    if (w > maxW) throw new Error(`${it.key} wider than atlas`);
    if (x + w > maxW) { x = 0; y += rowH + gap; rowH = 0; }
    rects.set(it.key, [x, y, w, h]);
    x += w + gap; rowH = Math.max(rowH, h); usedW = Math.max(usedW, x - gap);
  }
  const H = y + rowH, W = usedW;
  const round = v => Math.max(16, Math.ceil(v / 16) * 16);
  return { rects, w: round(W), h: round(H) };
}
const groups = ['common', ...Object.keys(STATIONS)];
const atlases = {};
for (const g of groups) {
  const items = [...SPR.values()].filter(s => s.group === g);
  for (const s of items) write(s.canvas, path.join(ART, 'sprites', g, s.name + '.png'));
  const { rects, w, h } = packShelf(items);
  const at = makeCanvas(w, h);
  for (const s of items) { const [x, y] = rects.get(s.key); over(at, s.canvas, x, y); s.rect = rects.get(s.key); }
  write(at, path.join(ART, 'atlas', g + '.png'));
  atlases[g] = { file: `atlas/${g}.png`, size: [w, h], sprites: items.length };
}

// ================================================================ preview（只给人看：mock 截图 + 图集放大）
/** draw a sliced sprite at any size (honours slice + mode) */
function drawSliced(dst, key, x, y, w, h, frame = 0) {
  const s = SPR.get(key), src = frameOf(key, frame), sl = s.slice || { left: 0, top: 0, right: 0, bottom: 0, mode: 'stretch' };
  const map = (d, dsize, ssize, a, b) => {
    if (d < a) return d;
    if (d >= dsize - b) return ssize - (dsize - d);
    const mid = ssize - a - b, dmid = dsize - a - b;
    if (mid <= 0) return a;
    return a + (sl.mode === 'repeat' ? (d - a) % mid : Math.floor((d - a) * mid / dmid));
  };
  const tmp = makeCanvas(w, h);
  for (let j = 0; j < h; j++) for (let i = 0; i < w; i++) setPx(tmp, i, j, getPx(src, map(i, w, src.width, sl.left, sl.right), map(j, h, src.height, sl.top, sl.bottom)));
  over(dst, tmp, x, y);
}
const put = (dst, key, x, y, frame = 0, op = 1) => over(dst, frameOf(key, frame), x, y, op);
const DEMO = { pot: 3, fryer: 2, oven: 4, prep: 4 };   // 每台 mock 里用哪一道（slotStar - 1）
const LAYOUTS = {};
function mock(s) {
  const prep = s === 'prep';
  const L = {
    panel: [0, 0, 176, prep ? 208 : 184],
    slot: [8, 8], stars: [30, 9], badge: [110, 10], chip: [30, 22, 34, 12],
    track: [62, 34, 26, 146], progress: [92, 36, 8, 132], progressCap: [90, 170],
    meters: [[146, 120, 5, 44], [154, 120, 5, 44]],
  };
  const c = makeCanvas(L.panel[2], L.panel[3]);
  drawSliced(c, 'common/panel', 0, 0, L.panel[2], L.panel[3]);
  const dish = dishOut.stations[s].dishes[DEMO[s]];
  put(c, 'common/slot', L.slot[0], L.slot[1]);
  over(c, decodeFile(dish.icon), L.slot[0] + 1, L.slot[1] + 1);
  for (let i = 0; i < 7; i++) put(c, i < dish.slotStar ? 'common/star_on' : 'common/star_off', L.stars[0] + i * 10, L.stars[1]);
  put(c, s === 'fryer' ? 'common/perfect_badge_broken' : 'common/perfect_badge', L.badge[0], L.badge[1]);
  drawSliced(c, s === 'fryer' ? 'common/chip_aqua' : 'common/chip_purple', ...L.chip);
  put(c, `${s}/icon`, 10, 30);
  put(c, `common/motion_${{ pot: 'sink', fryer: 'float', oven: 'sink', prep: 'mix' }[s]}`, 12, 48);   // 示例菜的默认性子（§13.6：馄饨沉、炸锅浮、整只烤鸡沉、汉堡混）
  const [tx, ty, tw, th] = L.track, cx = tx + 3, cy = ty + 3, cw = tw - 6, ch = th - 6, cb = cy + ch;
  drawSliced(c, `${s}/track`, tx, ty, tw, th);
  const uy = u => cb - Math.round(u * ch / 1e6);
  if (s === 'oven') { put(c, 'oven/glow', cx, cb - 24); put(c, 'oven/flames', cx, cb - 8, 1); }
  if (s === 'pot') put(c, 'pot/bubbles', cx, cy, 1);
  const H = 194000, yb = 420000, barH = Math.round(H * ch / 1e6);
  drawSliced(c, `${s}/bar_${s === 'oven' ? 'normal' : s === 'fryer' ? 'miss' : 'normal'}`, cx, uy(yb) - barH, cw, barH);
  const fishU = s === 'fryer' ? 660000 : 500000;
  if (s !== 'fryer') put(c, 'common/target_halo', cx + 10 - 11, uy(fishU) - 11);
  over(c, decodeFile(dish.trackIcon), cx + 10 - 9, uy(fishU) - 9);
  if (s === 'fryer') put(c, 'common/arrow_up', tx + tw + 2, uy(fishU) - 5);
  if (s === 'pot') put(c, 'common/target_ghost', cx + 10 - 9, uy(760000) - 9);
  // 调料瓶 + 抓取进度
  const bU = s === 'pot' ? 820000 : 250000, gold = s !== 'fryer';
  put(c, gold ? 'common/bottle_gold_glint' : 'common/bottle', cx + 2, uy(bU) - 8, 1);
  drawSliced(c, 'common/bottle_meter_frame', cx + 1, uy(bU) + 9, 18, 5);
  drawSliced(c, gold ? 'common/bottle_meter_fill_gold' : 'common/bottle_meter_fill', cx + 2, uy(bU) + 10, 9, 3);
  // 熟度条
  const [px, py, pw, ph] = L.progress, P = s === 'oven' ? 0.71 : 0.63, fillH = Math.round((ph - 2) * P);
  drawSliced(c, 'common/gauge_frame', px, py, pw, ph);
  drawSliced(c, `${s}/progress_fill`, px + 1, py + ph - 1 - fillH, 6, fillH);
  put(c, `${s}/progress_top`, px + 1, py + ph - 1 - fillH);
  put(c, `${s}/progress_cap`, L.progressCap[0], L.progressCap[1]);
  // 生 / 焦
  L.meters.forEach(([mx, my, mw, mh], i) => {
    drawSliced(c, 'common/meter_frame', mx, my, mw, mh);
    const v = [0.35, 0.9][i], fh = Math.round((mh - 2) * v);
    drawSliced(c, i === 1 ? 'common/meter_fill_alert' : 'common/meter_fill_raw', mx + 1, my + mh - 1 - fh, 3, fh);
    put(c, 'common/meter_threshold', mx + 1, my + mh - 1 - Math.round((mh - 2) * 0.4));
  });
  // 本台仪表
  if (s === 'pot') {
    const hx = 44, hy = 52, hh = 116, hf = Math.round((hh - 2) * 0.86);
    drawSliced(c, 'common/gauge_frame', hx, hy, 8, hh);
    drawSliced(c, 'pot/heat_fill_high', hx + 1, hy + hh - 1 - hf, 6, hf);
    put(c, 'pot/heat_tip_high', hx, hy + hh - 1 - hf - 10, 2);
    put(c, 'pot/flame_small', 28, 140); put(c, 'pot/flame_mid', 28, 112); put(c, 'pot/flame_big', 28, 84);
  }
  if (s === 'fryer') {
    const fx = 44, fy = 34, fh = 146, f = SPR.get('fryer/thermo_frame').content;
    drawSliced(c, 'fryer/thermo_frame', fx, fy, 12, fh);
    const ix = fx + f.left, it = fy + f.top, ih = fh - f.top - f.bottom, ib = it + ih;
    const ty = T => ib - Math.round(T * ih / 1000);
    drawSliced(c, 'fryer/thermo_band_cold', ix, ty(300), 4, ib - ty(300));
    drawSliced(c, 'fryer/thermo_band_gold', ix, ty(750), 4, ty(300) - ty(750));
    drawSliced(c, 'fryer/thermo_band_hot', ix, it, 4, ty(750) - it);
    const T = 812;
    drawSliced(c, 'fryer/thermo_column', ix + 1, ty(T), 2, ib - ty(T) + 2);
    put(c, 'fryer/thermo_column_top', ix + 1, ty(T));
    put(c, 'fryer/thermo_pointer', fx + 12, ty(T) - 4);
    put(c, 'fryer/thermo_tick', fx - 3, ty(300)); put(c, 'fryer/thermo_tick', fx - 3, ty(750));
    put(c, 'fryer/cooldown_ring', 116, 60, 3); drawSliced(c, 'common/keycap', 119, 62, 11, 12);
    put(c, 'fryer/cooldown_ready', 140, 60);
    put(c, 'fryer/oil_splash', cx - 4, uy(fishU) - 14, 1);
  }
  if (s === 'oven') {
    const flips = dish.flips, P0 = 0.25;
    for (let k = 1; k <= flips; k++) {
      const m = P0 + (1 - P0) * k / (flips + 1), my = py + ph - 1 - Math.round((ph - 2) * m);
      put(c, k === 1 ? 'oven/flip_mark_good' : 'oven/flip_mark_now', px + pw, my - 4);
    }
    drawSliced(c, 'oven/flip_prompt', 108, 60, 48, 15);
    put(c, 'oven/flip_icon', 110, 80);
    put(c, 'oven/flip_ring_good', 132, 81, 5);
    drawSliced(c, 'common/keycap', 112, 100, 11, 12);
    put(c, 'oven/burn_smoke', cx + 2, uy(fishU) - 24, 1);
  }
  if (prep) {
    const segs = 1, P0 = 0.25, m = P0 + (1 - P0) / (segs + 1), my = py + ph - 1 - Math.round((ph - 2) * m);
    put(c, 'prep/seg_mark_done', px + pw, my - 4);
    drawSliced(c, 'prep/track_dim', cx, cy, cw, ch);
    const lx = 8, ly = 186, lw = 160;
    drawSliced(c, 'prep/beat_lane', lx, ly, lw, 20);
    put(c, 'prep/beat_plate', lx + lw - 24, ly - 1);
    put(c, 'prep/beat_ring_perfect', lx + lw - 26, ly - 3);
    const icons = ['farmersdelight:egg_sandwich', 'culturaldelights:hearty_salad', 'moredelight:loaded_hamburger'];
    icons.forEach((id, i) => over(c, decodeFile(`icons/dishes/${id.replace(':', '__')}.png`), lx + 18 + i * 34, ly + 2));
    for (let i = 0; i < 6; i++) put(c, i < 3 ? 'prep/beat_light_on' : 'prep/beat_light_off', lx + 40 + i * 12, ly - 9);
    put(c, 'prep/ice_cube', 116, 96); put(c, 'prep/beat_shaker', 136, 92);
  }
  if (s === 'pot') { put(c, 'common/countdown_2', 120, 70); }
  LAYOUTS[s] = { ...L, demoDish: dish.id, note: 'mock 用的参考坐标（1x）；只是示意，实际排版由原型决定。' };
  return c;
}
function decodeFile(relPath) { const d = decodePNG(fs.readFileSync(path.join(ART, relPath))); return { width: d.width, height: d.height, data: d.data }; }
function checker(w, h) { const c = makeCanvas(w, h); for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) setPx(c, x, y, ((x >> 3) + (y >> 3)) % 2 ? '#b8b8b8' : '#a0a0a0'); return c; }
for (const s of Object.keys(STATIONS)) write(scale(mock(s), 3), path.join(ART, 'preview', `mock_${s}@3x.png`));
{ // 四台并排：轨道 + 三种火候条状态 + 熟度条 + 7 道示例菜
  const cols = Object.keys(STATIONS), W = cols.length * 96 + 8, Hh = 214, c = makeCanvas(W, Hh);
  drawSliced(c, 'common/panel', 0, 0, W, Hh);
  cols.forEach((s, i) => {
    const x0 = 8 + i * 96, tx = x0, ty = 8, th = 146, cx = tx + 3, ch = th - 6, cb = ty + 3 + ch;
    drawSliced(c, `${s}/track`, tx, ty, 26, th);
    if (s === 'oven') { put(c, 'oven/glow', cx, cb - 24); put(c, 'oven/flames', cx, cb - 8); }
    if (s === 'pot') put(c, 'pot/bubbles', cx, ty + 3, 2);
    const barH = 30;
    [['normal', 92], ['miss', 58], ['dead', 24]].forEach(([st, top]) => drawSliced(c, `${s}/bar_${st}`, cx, ty + 3 + top, 20, barH));
    over(c, decodeFile(dishOut.stations[s].dishes[3].trackIcon), cx + 1, ty + 3 + 92 + 6);
    put(c, 'common/bottle', cx + 2, ty + 3 + 8);
    drawSliced(c, `${s}/track`, tx + 30, ty, 26, 60);
    drawSliced(c, 'common/gauge_frame', tx + 60, ty, 8, th);
    const fh = Math.round((th - 2) * 0.8);
    drawSliced(c, `${s}/progress_fill`, tx + 61, ty + th - 1 - fh, 6, fh - 30);
    drawSliced(c, `${s}/progress_fill_hot`, tx + 61, ty + th - 1 - fh, 6, 30);
    put(c, `${s}/progress_top`, tx + 61, ty + th - 1 - fh);
    put(c, `${s}/progress_cap`, tx + 58, ty + th + 1);
    put(c, `${s}/icon`, tx + 72, ty);
    dishOut.stations[s].dishes.forEach((d, k) => { put(c, 'common/slot', x0 + (k % 4) * 20 - 2, 166 + Math.floor(k / 4) * 20); over(c, decodeFile(d.icon), x0 + (k % 4) * 20 - 1, 167 + Math.floor(k / 4) * 20); });
  });
  write(scale(c, 3), path.join(ART, 'preview', 'stations@3x.png'));
}
for (const g of [...groups, 'dishes', 'dishes_track']) {
  const d = decodeFile(`atlas/${g}.png`), bg = checker(d.width, d.height); over(bg, d, 0, 0);
  write(scale(bg, 4), path.join(ART, 'preview', `atlas_${g}@4x.png`));
}

// ================================================================ manifest.json + dishes.json
// 不写时间戳（重跑要逐字节一致）：改写内容指纹 = 全部 sprite + 菜品图标像素的 FNV-1a 32
const stamp = (() => {
  let h = 0x811c9dc5;
  const eat = buf => { for (let i = 0; i < buf.length; i++) { h ^= buf[i]; h = Math.imul(h, 0x01000193) >>> 0; } };
  for (const s of [...SPR.values()].sort((a, b) => (a.key < b.key ? -1 : 1))) { eat(Buffer.from(s.key)); eat(s.canvas.data); }
  eat(dishGrid.raw.data); eat(dishGrid.track.data);
  return 'fnv1a32:' + h.toString(16).padStart(8, '0');
})();
const sprites = {};
for (const s of SPR.values()) {
  sprites[s.key] = {
    file: `sprites/${s.group}/${s.name}.png`, size: [s.canvas.width, s.canvas.height],
    atlas: atlases[s.group].file, rect: s.rect,
    use: s.use,
    ...(s.slice ? { slice: s.slice } : {}), ...(s.content ? { content: s.content } : {}),
    ...(s.frames ? { frames: s.frames } : {}), ...(s.anchor ? { anchor: s.anchor } : {}),
    ...(s.textColor ? { textColor: s.textColor } : {}), ...(s.note ? { note: s.note } : {}),
  };
}
const manifest = {
  title: '掌勺小游戏 · 像素素材',
  contentHash: stamp,
  generator: 'gen.mjs（node gen.mjs 重跑；不要手改 PNG）',
  spec: '../spec.md',
  conventions: {
    pixels: '所有尺寸都是 1x GUI 像素。只按整数倍放大：网页 image-rendering: pixelated（canvas 关 imageSmoothingEnabled）；MC 端用 GuiGraphics.blit，跟随 GUI scale。',
    slice: '九宫格：left/top/right/bottom = 四边不拉伸的像素；mode = repeat（中段与四边按原像素平铺）或 stretch（拉伸；纯色区两者一样）。宽或高固定的条（轨道、火候条、油温计）同样按九宫格写，固定的那一维直接用源图尺寸。',
    content: '内容区内缩：在任意尺寸绘制后，按这几个像素往里缩就是可画内容的区域（轨道 = 火候条 / 食材活动范围；竖槽 = 填充范围）。',
    frames: '动画帧竖排（与 MC .png.mcmeta 同样排法）：frames.count 帧、每帧 w×h；ms = 建议每帧毫秒（0 = 不按时间播，按状态选帧）。',
    anchor: '没写 anchor 的按左上角放；center / bottom-center / left-center 是该点对齐目标坐标。',
    atlas: '每组另有一张拼好的图集 atlas/<组>.png，rect = [x, y, w, h]（1px 间隔，尺寸取 16 的倍数）。散图和图集内容完全一样，二选一用。',
    text: '中文字（下锅！、翻面！、火小了、品质名、按钮字）一律用游戏字体，不做成图片；PERFECT / 3·2·1 用 5×7 像素字（与原版字体同尺寸）。',
    colors: '原版界面色：#c6c6c6 底、#ffffff / #555555 斜边、#000000 描边、#373737 / #8b8b8b 凹槽。各台换皮色全部取自九台定稿烹饪台 gen.mjs 与农夫乐事原色（gen.mjs 的 palettes 段逐行注明出处）。',
  },
  drawOrder: ['common/panel', '<台>/track', 'oven/glow · oven/flames · pot/bubbles', '<台>/bar_*', 'common/target_halo', '食材图标（icons/track/*）', 'common/target_ghost', '调料瓶 + bottle_meter', '<台>/特效（oil_splash / burn_smoke）', 'prep/track_dim（摆料段）', 'prep/beat_lane …', '仪表与徽章'],
  mapping: {
    track: 'u（0–1,000,000）→ 像素：y = contentBottom − round(u × contentH / 1,000,000)。火候条下沿 yb、高 H 同样换算；食材 / 调料瓶图标中心对齐 yt / pos。',
    progress: '熟度 P → 填充高 = round(P × contentH / 1,000,000)，≥ 750,000 换 progress_fill_hot。烤炉翻面点 mark_k、备餐台摆料点 segMark_k 同样换算。',
    heat: '厨锅 h（0–1000）→ 火苗柱填充高 = round(h × contentH / 1000)；h < 400 小火、< 800 中火、其余旺火；顶死 / 熄火用 dead。',
    oil: '炸锅 T → 色带按 0–300 / 300–750 / 750–1000 铺；油温柱高 = round(T × contentH / 1000)。',
    stations: { pot: '厨锅（铁锅 / 火候）', fryer: '炸锅（油温计 / 金黄油）', oven: '烤炉（砖拱 / 火光）', prep: '备餐台（木案板 / 盘子）' },
  },
  referenceLayout: LAYOUTS,
  atlases: { ...atlases, dishes: { file: 'atlas/dishes.png', size: [112, 64], grid: '行 = pot/fryer/oven/prep，列 = slotStar 1–7，每格 16×16（原物品贴图）' }, dishes_track: { file: 'atlas/dishes_track.png', size: [126, 72], grid: '同上，每格 18×18（加 1px 深色描边，画在轨道上用）' } },
  dishes: 'dishes.json',
  previews: Object.fromEntries(fs.readdirSync(path.join(ART, 'preview')).map(f => [`preview/${f}`,
    f.startsWith('mock') ? '示意截图（×3）：零件按 referenceLayout 拼起来的样子，只给人看'
      : f.startsWith('stations') ? '四台并排（×3）：轨道（146 高 / 60 高）、火候条三态（自下而上 常态 / 刚丢 / 顶死）、熟度条（上段为收汁）、7 道示例菜'
        : '图集放大（×4，棋盘底），只给人看'])),
  sprites,
};
fs.writeFileSync(path.join(ART, 'manifest.json'), JSON.stringify(manifest, null, 2) + '\n');
dishOut.contentHash = stamp;
fs.writeFileSync(path.join(ART, 'dishes.json'), JSON.stringify(dishOut, null, 2) + '\n');
console.log(`sprites: ${SPR.size} in ${groups.length} atlases; dishes: ${Object.values(dishOut.stations).reduce((n, s) => n + s.dishes.length, 0)}`);
for (const [g, a] of Object.entries(atlases)) console.log(`  atlas/${g}.png ${a.size.join('×')} (${a.sprites})`);
