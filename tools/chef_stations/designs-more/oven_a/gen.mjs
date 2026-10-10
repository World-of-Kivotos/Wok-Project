// 烤炉 A「砖砌烤炉」—— 生成模型 JSON + 16×16 贴图 + meta.json。
//   cd <仓库>\tools\chef_stations
//   node designs-more/oven_a/gen.mjs
// 贴图全部在这里逐像素画（lib/png.mjs）；产物结构和主 MOD 的 assets/miningdim/ 一致，定稿后 models/ textures/ 直接拷。
//
// 第 4 轮（按审查意见）：炉口收窄成 8 宽、顶部 8→6→4 三级收拱；拱圈压暗、只留一圈深色勾边；柴改成圆截面并码实；
// 烤铲改木铲、只伸出 1px；烟囱降到高出 4px；铸铁换成炸锅 A 的暗铸铁；炉膛顶棚单独一张贴图；
// render_type 改 cutout_mipped；伸出部件写 cullface；工作中火焰面加 Forge 的 forge_data 全亮。
//
// 第 5 轮（家族 A 一致性，向炸锅 A 靠拢）：炉口前的浅灰石台 → 铸铁搁板（和炸锅锅身、炉灶顶板同一种铸铁）；
// 搁板、托盘、烟囱帽沿都改成炸锅锅沿的高光写法：外沿一条连续受光线、边中间最亮，内沿压暗，不再用零散的单像素亮点。
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { makeCanvas, setPx, getPx, strip, write } from '../../lib/png.mjs';

const dir = path.dirname(fileURLToPath(import.meta.url));
const NS = 'miningdim';
const ID = n => `${NS}:block/${n}`;
const T = n => path.join(dir, 'textures/block', n + '.png');
const json = (rel, o) => { const f = path.join(dir, rel); fs.mkdirSync(path.dirname(f), { recursive: true }); fs.writeFileSync(f, JSON.stringify(o, null, 2) + '\n'); };
/** draw rows of single-char colour keys; '.' / ' ' = leave as is */
const rows = (c, x0, y0, lines, pal) => lines.forEach((ln, j) => [...ln].forEach((ch, i) => {
  if (ch === '.' || ch === ' ') return;
  if (!(ch in pal)) throw new Error(`colour key '${ch}' missing`);
  setPx(c, x0 + i, y0 + j, pal[ch]);
}));
const hash = (x, y, s = 0) => { let h = (x * 374761393 + y * 668265263 + s * 1013904223) | 0; h = Math.imul(h ^ (h >>> 13), 1274126177); return (h ^ (h >>> 16)) >>> 0; };

// ---------------------------------------------------------------- palette
// 砖：农夫乐事炉灶的砖色（refs/palette.json 里 stove_side / stove_front），暗 → 亮
const BR = ['#733f31', '#7c4536', '#8f503f', '#9b5643', '#b1624d', '#c66851'];
// 砖缝：炉灶的三档灰粉
const MO = ['#8b6e67', '#a2867d', '#a9948d'];
// 熏黑 / 炉膛：炉灶火口的暗褐
const SOOT = ['#190804', '#2a120a', '#4a2116', '#5f341f'];
// 火：炉灶点火贴图的火色
const FIRE = { r: '#c35d1b', o: '#ed8c0e', y: '#ffd800', w: '#fff3a0' };
// 铸铁：农夫乐事锅具冷灰梯度 = 炸锅 A 的锅身（炉口搁板、托盘、烟囱帽沿、铁钉都用它）；炉膛地上的灰烬也取这里的两档
const G = ['#1f1f21', '#27272b', '#2d2d32', '#343438', '#3f3e42', '#494848', '#4f4f4f', '#595858', '#5c5c5c', '#656565', '#676161', '#727272'];
const IRON = ['#2d2d32', '#343438', '#3f3e42', '#494848'];
// 铸铁件的高光写法照炸锅 A 的锅沿：外沿 c→d→e→f 一条连续受光线（边中间最亮），内沿 a/b，立面 c/d，底面 u/v
const CI = { a: G[4], b: G[5], c: G[6], d: G[7], e: G[10], f: G[11], u: G[2], v: G[3] };
// 木：砧板 / 木勺
const WD = { h: '#997140', l: '#8b673c', m: '#755732', d: '#674c2c', k: '#55452e' };
// 派：金黄酥皮（和炸锅 A 的油色同一条）+ 莓果馅
const PIE = { h: '#f6d78e', l: '#e0b05b', m: '#cf944c', s: '#b0762f', d: '#8a5520', r: '#c63d27', R: '#8e2a1c' };

// ---------------------------------------------------------------- brick wall
// 3 行砖 + 1 行砖缝一层，砖长 8（含 1px 竖缝），隔层错开 4 —— 和农夫乐事炉灶侧面同一个砌法（缝在第 3/7/11/15 行）。
// 色阶分布也照炉灶：大多是 #7c4536 / #9b5643 / #8f503f，最亮的 #c66851 只零星出现。
function brickWall(c, { x0 = 0, y0 = 0, w = 16, h = 16, phase = 1, seed = 1, cols = BR, mortar = MO } = {}) {
  for (let y = y0; y < y0 + h; y++) {
    const r = (y + 1) % 4;                 // 0 = 砖缝行（第 3、7、11、15 行）, 1..3 = 砖的上中下
    const course = Math.floor((y + 1) / 4);
    const shift = (course + phase) % 2 ? 4 : 0;
    for (let x = x0; x < x0 + w; x++) {
      const lx = (x + shift) % 8;
      if (r === 0) { setPx(c, x, y, lx === 7 ? mortar[2] : lx === 6 || lx === 0 ? mortar[1] : mortar[0]); continue; }
      if (lx === 7) { setPx(c, x, y, r === 3 ? mortar[2] : mortar[1]); continue; }
      const id = Math.floor((x + shift) / 8) + course * 7;
      const v = hash(id, course, seed) % 4;             // 0/1 普通 2 偏暗 3 偏亮
      let k = [[3, 3, 1], [3, 2, 1], [2, 1, 0], [4, 3, 2]][v][r - 1];
      if (r === 1 && v < 2 && hash(x, y, seed + 3) % 3 === 0) k = 4;   // 上沿零星受光
      if (lx === 0) k = Math.max(0, k - 1);                             // 砖左边一列略暗
      const n = hash(x, y, seed + 7) % 11;
      if (n === 0) k = Math.max(0, k - 1); else if (n === 1 && r === 2) k = Math.min(5, k + 2);
      setPx(c, x, y, cols[k]);
    }
  }
  return c;
}
// 顶上一层压顶砖（第 0..2 行）：竖砌，每 4px 一块。正面（soft）顶沿只留每块 1px 亮砖，和炉灶正面的亮砖比例对齐
function coping(c, seed, soft = false) {
  for (let x = 0; x < 16; x++) {
    const lx = x % 4;
    setPx(c, x, 0, lx === 3 ? MO[2] : BR[lx === 0 || (soft && lx === 2) ? 3 : 4]);
    setPx(c, x, 1, lx === 3 ? MO[1] : BR[hash(x, 1, seed) % 3 ? 3 : 2]);
    setPx(c, x, 2, lx === 3 ? MO[1] : BR[lx === 0 ? 0 : 1]);
    setPx(c, x, 3, lx === 3 ? MO[2] : MO[0]);
  }
}
const transpose = src => { const c = makeCanvas(16, 16); for (let y = 0; y < 16; y++) for (let x = 0; x < 16; x++) setPx(c, x, y, getPx(src, y, x)); return c; };

// ---------------------------------------------------------------- textures
// 侧面 / 背面（east / west / south，默认 UV）
const side = brickWall(makeCanvas(16, 16), { seed: 3 });
coping(side, 3);
write(side, T('oven_a_side'));

// 正面（north，默认 UV：列 u = 16 - x，行 v = 16 - y）
//   炉口 x 4..12 / y 5..12 → 列 4..11 / 行 4..10；顶上收三级：行 10..6 宽 8，行 5 宽 6（列 5..10），行 4 宽 4（列 6..9）
//   两侧列 0..3 / 12..15 是 4px 纯砖墩；拱圈只在收拱那三行里，紧贴洞口一圈，外面一圈深色勾边
//   柴火龛 x 3..13 / y 1..4 → 列 3..12 / 行 12..14（凹进 2px，画在 base 的正面上）
const isOpen = (u, v) => v >= 4 && v <= 10 && (v === 4 ? u >= 6 && u <= 9 : v === 5 ? u >= 5 && u <= 10 : u >= 4 && u <= 11);
function frontTex(lit) {
  const c = brickWall(makeCanvas(16, 16), { seed: 5 });
  coping(c, 5, true);
  // 拱圈主色 BR[3]/BR[2]，只有拱顶石和楔砖的上沿各 1px BR[4]；正面不用 #c66851。
  // g/G 是紧贴洞口的内圈：工作中被炉火映成橙色。
  const p = { a: SOOT[3], E: BR[4], K: BR[3], g: lit ? FIRE.r : BR[3], h: lit ? FIRE.r : BR[4], G: lit ? FIRE.o : BR[3] };
  // 拱圈 1px、沿 45° 斜着走，外面同样 1px 的深色勾边（不出现 2px 宽的黑块）
  rows(c, 0, 1, [
    '......aEEa......',  // 1  拱顶石高出拱圈两行
    '......aKKa......',  // 2
    '.....agGGga.....',  // 3
    '....ah....ha....',  // 4  ← 列 4,5 / 10,11 是上层拱角元素的正面
    '...ag......ga...',  // 5  ← 列 4 / 11 是下层拱角元素的正面
  ], p);
  for (let v = 0; v < 16; v++) for (let u = 0; u < 16; u++) if (isOpen(u, v)) setPx(c, u, v, SOOT[0]);   // 炉口里没有面，涂黑以防万一
  // 柴火龛：三根柴的圆截面（左右 3×3、中间一根粗些 4×3），四角熏黑显出圆形，一圈树皮，中心木色。两个状态一样（龛里不映火）。
  const q = { t: SOOT[1], k: WD.k, h: WD.h, l: WD.l };
  rows(c, 3, 12, [
    'tkttkkttkt',
    'khkkhlkkhk',
    'tkttkkttkt',
  ], q);
  return c;
}
write(frontTex(false), T('oven_a_front'));
write(frontTex(true), T('oven_a_front_on'));

// 顶面（up，默认 UV：列 = x，行 = z，第 0 行 = 正面那条边）：平铺的砖 + 一圈压顶；烟囱在后部正中（x 6..10, z 10..14），根部一圈熏黑
const top = brickWall(makeCanvas(16, 16), { seed: 11, phase: 0 });
for (let i = 0; i < 16; i++) for (const [x, y] of [[i, 0], [i, 15], [0, i], [15, i]]) setPx(top, x, y, (x + y) % 4 === 3 ? MO[1] : BR[hash(x, y, 2) % 2 ? 3 : 4]);
for (const [x, y] of [[6, 9], [9, 9], [5, 10], [10, 13], [5, 13], [10, 10]]) setPx(top, x, y, SOOT[3]);
for (const [x, y] of [[7, 9], [8, 9]]) setPx(top, x, y, SOOT[2]);
write(top, T('oven_a_top'));

// 底面（也给柴火龛的龛顶、龛底、龛侧用）
write(brickWall(makeCanvas(16, 16), { seed: 17, cols: [BR[0], BR[0], BR[1], BR[1], BR[2], BR[2]] }), T('oven_a_bottom'));

// 炉膛两侧内壁 + 拱角侧面：熏黑的砖。UV 统一成 列 = 深度 z（0 = 炉口，11 = 后墙），行 4..10 = y 12..5
function innerWall(lit) {
  const c = makeCanvas(16, 16);
  const cols = lit ? [SOOT[3], BR[0], BR[1], BR[2], BR[3], BR[4]] : [SOOT[0], SOOT[1], SOOT[1], SOOT[2], SOOT[2], SOOT[3]];
  const mortar = lit ? [SOOT[3], FIRE.r, FIRE.o] : [SOOT[0], SOOT[1], SOOT[2]];
  brickWall(c, { seed: 23, cols, mortar });
  if (lit) {
    // 火在后面：越靠后（列大）、越低（行大）越亮；靠炉口上沿仍是熏黑
    for (let v = 0; v < 16; v++) for (let u = 0; u < 16; u++) {
      const heat = u + (v - 4) * 0.8;
      if (heat < 5 && hash(u, v, 31) % 3) setPx(c, u, v, v % 4 === 3 ? SOOT[2] : SOOT[3]);
      else if (heat > 13 && (v % 4 === 3 || (u + v) % 8 === 7)) setPx(c, u, v, FIRE.o);
    }
  } else {
    for (let v = 0; v < 6; v++) for (let u = 0; u < 16; u++) if (hash(u, v, 41) % 3 === 0) setPx(c, u, v, SOOT[0]);
  }
  return c;
}
write(innerWall(false), T('oven_a_inner'));
write(innerWall(true), T('oven_a_inner_on'));

// 炉膛顶棚（crown 和四块拱角的 down 面，默认 UV：列 = x，行 = 15 - z，行 15 = 炉口，行 5 = 后墙）
// 筒拱的砖沿进深方向砌（砖缝竖着走）。工作中：靠后墙那端最亮，左右对称，靠炉口一截仍熏黑。
function ceiling(lit) {
  const cols = lit ? [SOOT[3], BR[0], BR[1], BR[2], BR[3], BR[4]] : [SOOT[0], SOOT[1], SOOT[1], SOOT[2], SOOT[2], SOOT[3]];
  const mortar = lit ? [SOOT[2], SOOT[3], BR[0]] : [SOOT[0], SOOT[1], SOOT[2]];
  const c = transpose(brickWall(makeCanvas(16, 16), { seed: 37, cols, mortar }));
  for (let v = 0; v < 16; v++) for (let u = 0; u < 16; u++) {
    const z = 15 - v, dx = Math.abs(u + 0.5 - 8);
    if (!lit) { if (z < 4 && hash(u, v, 43) % 2) setPx(c, u, v, SOOT[0]); continue; }
    const heat = z - dx * 0.6;
    if (z < 3 || (z < 5 && hash(u, v, 47) % 2)) setPx(c, u, v, (u + 1) % 4 === 0 ? SOOT[2] : SOOT[3]);
    else if (heat > 7.5 && ((u + 1) % 4 === 0 || hash(u, v, 53) % 3 === 0)) setPx(c, u, v, heat > 9 ? FIRE.o : FIRE.r);
  }
  return c;
}
write(ceiling(false), T('oven_a_ceiling'));
write(ceiling(true), T('oven_a_ceiling_on'));

// 炉膛底（base / lintel 的 up 面，默认 UV：列 = x，行 = z，行 0 = 炉口）：耐火砖铺地；柴堆（x 5..11, z 8..11）前面和两边落着灰 / 炭火
function hearth(lit) {
  const c = makeCanvas(16, 16);
  const cols = lit ? [BR[0], BR[1], BR[2], BR[3], BR[4], BR[5]] : [SOOT[3], BR[0], BR[0], BR[1], BR[2], BR[3]];
  const mortar = lit ? [SOOT[3], BR[0], FIRE.r] : [SOOT[2], SOOT[3], MO[0]];
  brickWall(c, { seed: 29, phase: 0, cols, mortar });
  for (const [x, y] of [[4, 8], [4, 10], [11, 9], [11, 8], [5, 7], [7, 7], [8, 7], [10, 7], [6, 6], [9, 6]]) setPx(c, x, y, lit ? FIRE.r : G[7]);
  for (const [x, y] of [[4, 9], [11, 10], [6, 7], [9, 7]]) setPx(c, x, y, lit ? FIRE.o : G[9]);
  if (lit) for (const [x, y] of [[7, 7], [8, 7]]) setPx(c, x, y, FIRE.y);
  return c;
}
write(hearth(false), T('oven_a_hearth'));
write(hearth(true), T('oven_a_hearth_on'));

// 后墙（back 元素的 north 面，默认 UV：列 4..11，行 4..10）：待机 = 熏黑砖墙
{
  const c = innerWall(false);
  for (let u = 4; u <= 11; u++) for (let v = 4; v <= 6; v++) if (hash(u, v, 51) % 2) setPx(c, u, v, SOOT[0]);
  write(c, T('oven_a_back'));
}

// 后墙火光（工作中，6 帧 × 3 tick，和炉灶火口同节奏）：火舌从柴堆后面往上舔
{
  const frames = [];
  for (let fr = 0; fr < 6; fr++) {
    const c = innerWall(true);
    for (let u = 4; u <= 11; u++) {
      const hgt = 2.6 + 1.6 * Math.sin(u * 1.7 + fr * 2.1) + 1.0 * Math.sin(u * 0.9 - fr * 1.3);
      const tip = Math.round(10 - Math.max(1, hgt));        // 火舌顶的行（行小 = 高）
      for (let v = 10; v >= 4; v--) {
        const d = v - tip;
        if (d >= 0) setPx(c, u, v, d === 0 ? FIRE.r : d === 1 ? FIRE.o : d <= 3 ? FIRE.y : FIRE.w);
      }
    }
    for (const [u, v, ph] of [[5, 4, 0], [9, 5, 2], [10, 4, 4], [7, 5, 3]]) if ((fr + ph) % 6 < 2) setPx(c, u, v, FIRE.o);   // 火星
    frames.push(c);
  }
  write(strip(frames), T('oven_a_fire'));
  json('textures/block/oven_a_fire.png.mcmeta', { animation: { frametime: 3 } });
}

// 火苗片（工作中，竖在柴堆前的镂空面片，6 帧 × 2 tick）：每帧 (0,0) 起 8×4；中间两列底下留空，露出顶上那根柴
{
  const shapes = [
    ['.r....r.', 'ror..ror', 'oyr..ryo', 'yyo..oyy'],
    ['..r...r.', '.ro..ror', 'royr.oyo', 'oyo..yyo'],
    ['.r.....r', '.or..roy', 'royr.ryo', 'yyo..oyy'],
    ['r....r..', 'or..rr..', 'oyr.oyr.', 'yyo..oyr'],
    ['..r....r', '.ror..ro', 'royo.ryo', 'oyy..yyo'],
    ['.r..r...', 'ro..or.r', 'oyr.ryro', 'yyo..oyy'],
  ];
  const frames = shapes.map(s => { const c = makeCanvas(16, 16); rows(c, 0, 0, s, { r: FIRE.r, o: FIRE.o, y: FIRE.y }); return c; });
  write(strip(frames), T('oven_a_flame'));
  json('textures/block/oven_a_flame.png.mcmeta', { animation: { frametime: 2 } });
}

// 零件图集 oven_a_parts（工作中换成 oven_a_parts_on：柴变炭火、搁板靠炉口一边被映亮）
//   (0,0)  搁板 顶 12×2（第 0 行外沿，第 1 行靠炉口）  (12,0) 搁板 端面 2×1   (0,2) 搁板 正面 12×1   (0,3) 搁板 底 12×2
//   (0,5)  托盘 顶 6×6（第 0 行靠炉口）  (6,5) 托盘 边 6×½
//   (6,6)  派 顶 5×5        (11,6) 派 侧 5×2
//   (0,12) 柴 截面 3×3      (3,12) 柴 侧 3×3        (6,12) 柴 顶 3×3
function parts(lit) {
  const c = makeCanvas(16, 16);
  const p = {
    h: PIE.h, l: PIE.l, m: PIE.m, s: PIE.s, D: PIE.d, R: PIE.R,                               // 派
    x: lit ? SOOT[2] : SOOT[1], k: lit ? FIRE.r : WD.k, n: lit ? SOOT[2] : WD.d,              // 柴：截面四角 / 树皮 / 暗树皮
    w: lit ? FIRE.y : WD.h, q: lit ? FIRE.o : WD.m, K: lit ? SOOT[3] : WD.k,                  // 柴：截面中心 / 侧面纹 / 侧面底
  };
  // 铸铁搁板：外沿一条连续受光线（和炸锅锅沿顶面外圈同一串 'cddeeffeeddc'），靠炉口的内沿压暗（锅沿内圈那串）；
  // 工作中内沿被炉火映红。正面 1px 立面 = 炸锅锅身侧面最上那行卷边（c/d，只断两处）；底面是锅底的两档暗灰
  const q = { ...CI, r: FIRE.r, o: FIRE.o };
  rows(c, 0, 0, ['cddeeffeeddc', lit ? 'abrrroorrrba' : 'abbbaabbbbab'], q);
  rows(c, 12, 0, ['dc'], q);
  rows(c, 0, 2, ['cdddddcddddc'], q);
  rows(c, 0, 3, ['vvuvvvvvuvvv', 'uuvuuuuuuvuu'], q);
  // 铸铁托盘：同一写法缩小——靠炉口的前沿受光（d），两侧 c，后沿 b，盘心 v/u
  rows(c, 0, 5, [
    'cddddc',
    'cvvvvc',
    'cvuvvc',
    'cvvuvc',
    'cvvvvc',
    'bbbbbb',
  ], q);
  rows(c, 6, 5, ['cccccc'], q);
  rows(c, 6, 6, [
    'DsssD',
    'sRhRs',
    'shhhs',
    'sRhRs',
    'DsssD',
  ], p);
  rows(c, 11, 6, ['lhhhl', 'smmms'], p);   // 派侧：上一行金黄酥皮边、下一行深一档，两端暗一点显圆，不画竖纹
  rows(c, 0, 12, ['xkx', 'kwk', 'xkx'], p);
  rows(c, 3, 12, lit ? ['nkn', 'kqK', 'KKk'] : ['nnn', 'kqk', 'KKK'], p);
  rows(c, 6, 12, lit ? ['nkn', 'knq', 'nqn'] : ['knk', 'knk', 'kqk'], p);
  return c;
}
write(parts(false), T('oven_a_parts'));
write(parts(true), T('oven_a_parts_on'));

// 配件图集 oven_a_fittings（两个状态共用）
//   (0,0) 木铲 铲面 west 5×5   (5,0) 铲面 前后边 1×5   (6,0) 铲面 顶/底边 1×5
//   (8,0) 铲柄 west 1×7（最上 1px 是铁钉）  (9,0) 铲柄 前后 1×7   (10,0) 铲柄 顶 1×1
//   (0,8) 烟囱身 4×3   (4,8) 烟囱帽 顶 6×6   (10,8) 帽 侧 6×1   (10,9) 帽 底 6×6
{
  const c = makeCanvas(16, 16);
  const p = { h: WD.h, l: WD.l, m: WD.m, d: WD.d, k: WD.k, N: IRON[3], y: IRON[1], Y: IRON[0],
    s: SOOT[2], z: SOOT[0], E: BR[3], c: BR[2], b: BR[1], j: MO[0] };
  rows(c, 0, 0, [
    'ddhdd',
    'dlmld',
    'dlmld',
    'dmlmd',
    'ddddd',
  ], p);
  rows(c, 5, 0, ['k', 'k', 'k', 'k', 'k'], p);
  rows(c, 6, 0, ['d', 'd', 'd', 'd', 'd'], p);
  rows(c, 8, 0, ['N', 'h', 'h', 'l', 'h', 'l', 'h'], p);
  rows(c, 9, 0, ['N', 'l', 'l', 'm', 'l', 'm', 'l'], p);
  rows(c, 10, 0, ['N'], p);
  rows(c, 0, 8, ['EcbE', 'jjjj', 'cEEb'], p);   // 烟囱身：两层砖夹一行缝，竖缝错开
  setPx(c, 2, 8, MO[1]); setPx(c, 1, 10, MO[1]);
  // 烟囱帽沿：炸锅锅沿的写法——外圈每条边中间最亮（c d e e d c），里面一圈熏黑、中间烟道口；立面一条连续的卷边受光线
  const cap = { ...CI, s: SOOT[2], z: SOOT[0] };
  rows(c, 4, 8, [
    'cdeedc',
    'dssssd',
    'eszzse',
    'eszzse',
    'dssssd',
    'cdeedc',
  ], cap);
  rows(c, 10, 8, ['cddddc'], cap);
  rows(c, 10, 9, ['yyyyyy', 'yYYYYy', 'yYYYYy', 'yYYYYy', 'yYYYYy', 'yyyyyy'], p);
  write(c, T('oven_a_fittings'));
}

// ---------------------------------------------------------------- models
const f = (texture, uv, extra = {}) => (uv ? { uv, texture, ...extra } : { texture, ...extra });
const S = (cull) => f('#side', null, { cullface: cull });
const cullAll = (el, dir) => { for (const k in el.faces) el.faces[k].cullface = dir; return el; };

// 炉身：一个整格。上半用 5 块围出炉膛（x 4..12, y 5..12, z 0..11），4 块拱角把炉口顶收成 8→6→4 的筒拱；
// 下半正面凹进 2px 的柴火龛（x 3..13, y 1..4），由勒脚、两墩、过梁围成，龛底的柴画在 base 正面上。
const shell = () => [
  { name: 'base', from: [0, 0, 2], to: [16, 5, 16], faces: {
    north: f('#front'), south: S('south'), east: f('#side', [0, 11, 14, 16], { cullface: 'east' }), west: f('#side', [2, 11, 16, 16], { cullface: 'west' }),
    up: f('#hearth', [0, 2, 16, 16]), down: f('#bottom', [0, 0, 16, 14], { cullface: 'down' }) } },
  { name: 'plinth', from: [0, 0, 0], to: [16, 1, 2], faces: {
    north: f('#front', null, { cullface: 'north' }), east: f('#side', [14, 15, 16, 16], { cullface: 'east' }), west: f('#side', [0, 15, 2, 16], { cullface: 'west' }),
    up: f('#bottom', [0, 12, 16, 14]), down: f('#bottom', [0, 14, 16, 16], { cullface: 'down' }) } },
  { name: 'pier east', from: [13, 1, 0], to: [16, 4, 2], faces: {
    north: f('#front', null, { cullface: 'north' }), east: f('#side', [14, 12, 16, 15], { cullface: 'east' }), west: f('#bottom', [0, 12, 2, 15]) } },
  { name: 'pier west', from: [0, 1, 0], to: [3, 4, 2], faces: {
    north: f('#front', null, { cullface: 'north' }), west: f('#side', [0, 12, 2, 15], { cullface: 'west' }), east: f('#bottom', [2, 12, 0, 15]) } },
  { name: 'lintel', from: [0, 4, 0], to: [16, 5, 2], faces: {
    north: f('#front', null, { cullface: 'north' }), east: f('#side', [14, 11, 16, 12], { cullface: 'east' }), west: f('#side', [0, 11, 2, 12], { cullface: 'west' }),
    up: f('#hearth', [0, 0, 16, 2]), down: f('#bottom', [0, 0, 16, 2]) } },
  { name: 'crown', from: [0, 12, 0], to: [16, 16, 16], faces: {
    north: f('#front', null, { cullface: 'north' }), south: S('south'), east: S('east'), west: S('west'),
    up: f('#top', null, { cullface: 'up' }), down: f('#ceiling') } },
  { name: 'jamb east', from: [12, 5, 0], to: [16, 12, 16], faces: {
    north: f('#front', null, { cullface: 'north' }), south: S('south'), east: S('east'), west: f('#inner', [0, 4, 16, 11]) } },
  { name: 'jamb west', from: [0, 5, 0], to: [4, 12, 16], faces: {
    north: f('#front', null, { cullface: 'north' }), south: S('south'), west: S('west'), east: f('#inner', [16, 4, 0, 11]) } },
  { name: 'back wall', from: [4, 5, 11], to: [12, 12, 16], faces: { north: f('#back'), south: S('south') } },
  // 拱角：下层 1px（y 10..11），上层 2px（y 11..12）；底面和顶棚同一张 #ceiling、默认 UV，砖缝对齐
  { name: 'vault west 1', from: [4, 10, 0], to: [5, 11, 11], faces: { north: f('#front', null, { cullface: 'north' }), down: f('#ceiling'), east: f('#inner', [11, 5, 0, 6]) } },
  { name: 'vault west 2', from: [4, 11, 0], to: [6, 12, 11], faces: { north: f('#front', null, { cullface: 'north' }), down: f('#ceiling'), east: f('#inner', [11, 4, 0, 5]) } },
  { name: 'vault east 1', from: [11, 10, 0], to: [12, 11, 11], faces: { north: f('#front', null, { cullface: 'north' }), down: f('#ceiling'), west: f('#inner', [0, 5, 11, 6]) } },
  { name: 'vault east 2', from: [10, 11, 0], to: [12, 12, 11], faces: { north: f('#front', null, { cullface: 'north' }), down: f('#ceiling'), west: f('#inner', [0, 4, 11, 5]) } },
];

// 炉口前的铸铁搁板（和炉膛底齐平，伸出 2px，压在柴火龛上）。全部面 cullface north：前面贴着整格不透明方块时整块不画。
const sill = () => [
  cullAll({ name: 'sill', from: [2, 4, -2], to: [14, 5, 0], faces: {
    up: f('#parts', [0, 0, 12, 2]), north: f('#parts', [0, 2, 12, 3]), down: f('#parts', [0, 3, 12, 5]),
    east: f('#parts', [12, 0, 14, 1]), west: f('#parts', [12, 0, 14, 1]) } }, 'north'),
];

// 炉膛里的暗铸铁托盘 + 后墙前码成品字的三根柴（截面朝炉口）：底下两根并拢，顶上一根压在接缝上
const LOG = { north: [0, 12, 3, 15], side: [3, 12, 6, 15], up: [6, 12, 9, 15] };
const log = (name, x0, y0, skip = []) => {
  const faces = { north: f('#parts', LOG.north), up: f('#parts', LOG.up), east: f('#parts', LOG.side), west: f('#parts', LOG.side) };
  for (const k of skip) delete faces[k];
  return { name, from: [x0, y0, 8], to: [x0 + 3, y0 + 3, 11], faces };
};
const inside = () => [
  { name: 'tray', from: [5, 5, 1], to: [11, 5.5, 7], faces: {
    up: f('#parts', [0, 5, 6, 11]), north: f('#parts', [6, 5, 12, 5.5]), east: f('#parts', [6, 5, 12, 5.5]), west: f('#parts', [6, 5, 12, 5.5]) } },
  log('log west', 5, 5, ['east']), log('log east', 8, 5, ['west']), log('log top', 6.5, 8),
];

// 烟囱：后部正中一截 4×4 砖烟囱 + 暗铸铁帽沿，只高出方块 4px。全部面 cullface up：上面放整格方块时整个不画。
const chimney = () => [
  cullAll({ name: 'chimney', from: [6, 16, 10], to: [10, 19, 14], faces: {
    north: f('#fittings', [0, 8, 4, 11]), south: f('#fittings', [0, 8, 4, 11]), east: f('#fittings', [0, 8, 4, 11]), west: f('#fittings', [0, 8, 4, 11]) } }, 'up'),
  cullAll({ name: 'chimney cap', from: [5, 19, 9], to: [11, 20, 15], faces: {
    up: f('#fittings', [4, 8, 10, 14]), down: f('#fittings', [10, 9, 16, 15]),
    north: f('#fittings', [10, 8, 16, 9]), south: f('#fittings', [10, 8, 16, 9]), east: f('#fittings', [10, 8, 16, 9]), west: f('#fittings', [10, 8, 16, 9]) } }, 'up'),
];

// 西侧墙上挂的木铲：平肩铲面朝下，木柄朝上，柄顶 1px 是铁钉。只伸出西面 1px，全部面 cullface west。
const peel = () => [
  cullAll({ name: 'peel blade', from: [-1, 2, 5], to: [0, 7, 10], faces: {
    west: f('#fittings', [0, 0, 5, 5]), north: f('#fittings', [5, 0, 6, 5]), south: f('#fittings', [5, 0, 6, 5]),
    up: f('#fittings', [6, 0, 7, 5]), down: f('#fittings', [6, 0, 7, 5]) } }, 'west'),
  cullAll({ name: 'peel handle', from: [-1, 7, 7], to: [0, 14, 8], faces: {
    west: f('#fittings', [8, 0, 9, 7]), north: f('#fittings', [9, 0, 10, 7]), south: f('#fittings', [9, 0, 10, 7]), up: f('#fittings', [10, 0, 11, 1]) } }, 'west'),
];

// 工作中：托盘上一只格子派 + 柴堆前一张火苗片
const baking = () => [
  { name: 'pie', from: [5.5, 5.5, 1.5], to: [10.5, 7.5, 6.5], faces: {
    up: f('#parts', [6, 6, 11, 11]), north: f('#parts', [11, 6, 16, 8]), east: f('#parts', [11, 6, 16, 8]), west: f('#parts', [11, 6, 16, 8]) } },
  { name: 'flames', from: [4, 7, 7.5], to: [12, 11, 7.5], shade: false, forge_data: { block_light: 15, sky_light: 15 }, faces: {
    north: f('#flame', [0, 0, 8, 4]) } },
];

const textures = {
  particle: ID('oven_a_side'),
  front: ID('oven_a_front'), side: ID('oven_a_side'), top: ID('oven_a_top'), bottom: ID('oven_a_bottom'),
  inner: ID('oven_a_inner'), ceiling: ID('oven_a_ceiling'), hearth: ID('oven_a_hearth'), back: ID('oven_a_back'),
  parts: ID('oven_a_parts'), fittings: ID('oven_a_fittings'),
};
const RENDER = 'minecraft:cutout_mipped';
const idle = [...shell(), ...sill(), ...inside(), ...chimney(), ...peel()];
// 工作中：元素表整份重写（子模型写了 elements 就整体覆盖父模型）。火光面加 Forge 1.20.1 的 forge_data（block/sky light 15 = 全亮，
// 不受炉膛暗处取光影响）；后墙只给炉膛那一面（north）加，south 外墙照常受光。预览工具不认这个字段。
const GLOW = { block_light: 15, sky_light: 15 };
const active = [...shell(), ...sill(), ...inside(), ...chimney(), ...peel(), ...baking()].map(el => {
  if (el.name === 'back wall') el.faces.north.forge_data = { ...GLOW, ambient_occlusion: false };
  if (el.name.startsWith('log ')) el.forge_data = { ...GLOW };
  return el;
});
const faceCount = els => els.reduce((n, el) => n + Object.keys(el.faces).length, 0);

json('models/block/oven_a.json', { parent: 'minecraft:block/block', render_type: RENDER, textures, elements: idle });
json('models/block/oven_a_on.json', {
  parent: ID('oven_a'),
  render_type: RENDER,
  textures: {
    front: ID('oven_a_front_on'), inner: ID('oven_a_inner_on'), ceiling: ID('oven_a_ceiling_on'), hearth: ID('oven_a_hearth_on'), back: ID('oven_a_fire'),
    parts: ID('oven_a_parts_on'), flame: ID('oven_a_flame'),
  },
  elements: active,
});

json('meta.json', {
  key: 'oven_a',
  tag: '烤炉 A',
  order: 11,
  name: '砖砌烤炉',
  tagline: '和农夫乐事炉灶同一套砖的面包炉：半圆拱炉口、铸铁搁板、柴火龛、后部短烟囱，侧面挂一把木铲。',
  description: '整格方块。砖色、砌法（3 行砖 + 1 行缝、隔层错 4）和色阶分布都照农夫乐事炉灶，顶上一圈竖砌压顶砖。正面是 8 宽的半圆拱炉口：直壁 5 行，顶上按 8→6→4 收拱，两侧各两块拱角元素沿整个炉膛深度收成筒拱；拱圈用炉灶的中暗砖、只在拱顶石和楔砖上沿各 1px 亮砖，外面一圈深色勾边；炉口两边各留 4px 纯砖墩。炉膛深 11px，熏黑的内壁和筒拱顶，地上放一只暗铸铁托盘，后墙前码着三根圆截面的柴（两根并排、一根压在接缝上）。炉口下伸出一块和炉膛底齐平的铸铁搁板（和炸锅 A 锅身、炉灶顶板同一种冷灰铸铁，外沿一条连续受光线），搁板下凹进 2px 是柴火龛，龛里三根圆截面的柴。后部正中一截 4×4 砖烟囱戴铸铁帽沿（锅沿那样外圈受光），只高出方块 4px；西侧墙上挂一把平肩木铲，柄顶 1px 铁钉，只伸出 1px。待机：炉膛熏黑、柴是冷的、托盘空着；工作中：后墙火舌翻动，柴堆前多一张火苗片，柴烧成炭火，炉膛内壁、顶棚（靠后墙一端）、地面、拱口内圈和搁板内沿被映橙，托盘上出现一只格子派。',
  notes: [
    `光照（必须照做）：方块属性加 .noOcclusion()，getShape 和 getCollisionShape 都用挖空的整格，照原版炼药锅：Shapes.join(Shapes.block(), Block.box(4,5,0,12,12,11), BooleanOp.ONLY_FIRST)（不想让掉落物滚进炉膛就只挖 1px：Block.box(4,5,0,12,12,1)）。原因：原版 ModelBlockRenderer.calculateShape 在碰撞箱是整格时，模型的每个面都按朝向去取邻格的光，炉膛顶棚、拱角底面这些朝下的面会取到地面方块的光 = 0，全黑；夹在两个方块中间时炉膛两壁和烟囱侧面也发黑。挖空后不贴方块边界的面都取本格光照。形状里不要并进烟囱（烟囱、搁板、木铲靠 cullface 剔除，要求本方块那一面的遮挡形状是整面）。`,
    '发光：lightLevel(s -> s.getValue(ACTIVE) ? 13 : 0)，和农夫乐事炉灶一致。工作中模型里后墙炉膛面、三根柴和火苗片另加 Forge 1.20.1 的 "forge_data": {"block_light": 15, "sky_light": 15}（后墙再关 AO），火在暗处也是全亮。预览工具不认 forge_data，要在测试端确认生效。',
    'render_type 用 minecraft:cutout_mipped（砖面要有 mipmap，不然中远距离闪、和炉灶质感不一样；贴图 alpha 只有 0/255，近处效果和 cutout 一样，远处火苗细尖会被吃掉）。两个模型都显式写了 render_type。要更讲究可以用 forge:composite：砖体 solid、火苗片 cutout。',
    '伸出方块的部件都写了 cullface：铸铁搁板向前 2px（cullface north）、木铲向西 1px（cullface west）、烟囱 + 帽沿向上 4px（cullface up）。邻格是不透明整格时整块剔掉，不穿模；邻格是半砖、玻璃这类时照样画，会有 1–4px 重叠。',
    '不需要下方热源：烤炉自带柴火（炉膛里那堆柴）。active 由方块实体在烘烤时打开（或沿用厨锅“烘烤中即 active”）；烟囱冒烟（facing=north 时出生点约在方块内 (0.5, 1.27, 0.75)，随朝向旋转）、炉口火星等粒子在 animateTick 里加。',
    `待机 / 工作中两个模型：oven_a_on 继承 oven_a 的贴图，换正面（拱口内圈映橙）、炉膛内壁、顶棚、炉膛底、后墙（6 帧 × 3 tick 火舌）、零件图集（柴变炭火、搁板内沿映亮），并多两个元素：派 + 火苗片（6 帧 × 2 tick，shade:false）。元素：待机 ${idle.length} 个 / ${faceCount(idle)} 个面，工作中 ${active.length} 个 / ${faceCount(active)} 个面；全部 16×16 贴图。`,
    '和炸锅 A、备餐台 A 同一套（家族 A）：砖 = 农夫乐事炉灶的砖（炸锅就坐在那台炉灶上）；所有金属件（搁板、托盘、烟囱帽沿、铁钉）都是炸锅锅身那条冷灰铸铁，高光照炸锅锅沿写——外沿一条连续受光线、边中间最亮、内沿压暗；木头取农夫乐事砧板 / 木勺；派的金黄和莓果红就是炸锅的油色和温度计红。',
    '朝向：炉口在 north，facing=north 时 y=0，east 90，south 180，west 270。炉里的派是固定元素；要按实际在烤的菜显示，用方块实体渲染器画在托盘位置（约 x 5..11, y 5.5, z 1..7），或者 blockstate 改 multipart 按“烤的是哪类”叠一个只含食物的小模型。',
  ],
  hires: false,
  namespace: NS,
  blocks: [
    { label: '烤炉', pos: [0, 0, 0], idle: { model: 'oven_a', y: 0 }, active: { model: 'oven_a_on', y: 0 } },
  ],
});
console.log(`oven A written: idle ${idle.length} elements / ${faceCount(idle)} faces, active ${active.length} elements / ${faceCount(active)} faces`);
