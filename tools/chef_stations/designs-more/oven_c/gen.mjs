// 烤炉 方案 C「吊炉」—— 生成模型 JSON + 16×16 贴图 + meta.json。
//   cd <仓库>\tools\chef_stations
//   node designs-more/oven_c/gen.mjs
// 贴图全部在这里逐像素画（lib/png.mjs），改了重跑即可；每次运行先清空 models/ 和 textures/，不会留下旧文件。
// 产物结构和主 MOD 的 assets/miningdim/ 一致。
//
// 造型：一层红砖台基上一座圆角黄泥炉身（四角倒 1px，底部一皮砖），顶上两级收分的炉肩（也倒角）像个矮圆顶，正中一只陶土烟口。
// 正面一个红砖拱券（带砖缝）的拱形炉口，拱顶上方被烟熏黑。炉膛是真的空腔：底下一层果木炭，
// 一只烤鸭用 S 钩吊在炉膛顶下。西侧（正面看在右边）一根挑杆斜靠在炉身上、杆头搭到炉身顶沿，炉身后半截脚下码着三段果木。
// 泥皮剥落、露出红砖的那块只在东面（正面看在左边）。正面在 north。
// 配色和炸锅 C「中式炸灶」同一套：农夫乐事炉灶的砖色和砂浆、炸灶的黄泥五档、炸灶灶口的炭膛色（炉膛黑、余火暗红、炉灰），
// 不另加亮色（2026-10-10 家族一致性收尾：去掉了原先比炸灶更亮的一档黄泥顶光和一档砖色，待机炭床改用炸灶灶口的暖黑 + 炉灰）。
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { makeCanvas, fillRect, setPx, getPx, strip, write } from '../../lib/png.mjs';

const dir = path.dirname(fileURLToPath(import.meta.url));
for (const sub of ['models', 'textures']) fs.rmSync(path.join(dir, sub), { recursive: true, force: true });
const NS = 'miningdim';
const P = 'oven_c';
const ID = n => `${NS}:block/${P}_${n}`;
const T = n => path.join(dir, 'textures/block', `${P}_${n}.png`);
const json = (rel, o) => { const f = path.join(dir, rel); fs.mkdirSync(path.dirname(f), { recursive: true }); fs.writeFileSync(f, JSON.stringify(o, null, 2) + '\n'); };
/** draw rows of single-char colour keys at (x0, y0); '.' = leave as is (transparent on a fresh canvas) */
function rows(c, x0, y0, lines, pal) {
  lines.forEach((ln, j) => [...ln].forEach((ch, i) => {
    if (ch === '.' || ch === ' ') return;
    if (!(ch in pal)) throw new Error(`colour key '${ch}' missing (row ${j}: ${ln})`);
    setPx(c, x0 + i, y0 + j, pal[ch]);
  }));
}
const full = (lines, w = 16) => { for (const l of lines) if (l.length !== w) throw new Error(`row width ${l.length} != ${w}: ${l}`); return lines; };

// ---------------------------------------------------------------- palette
// 黄泥：和炸锅 C 的灶面黄泥同五档（h 最亮 → k 最暗）。炉身顶沿、炉肩和炉顶每一级的外沿用 h（= 炸灶灶沿顶上那一行的亮色），
// 炉身底色是 l（= 炸锅 C 灶面的主色 #b4976e），m / d 只用在阴影和细节上。
const CL = { h: '#c5aa80', l: '#b4976e', m: '#a2845c', d: '#8a6c48', k: '#6e5437' };
// 农夫乐事炉灶的砖色和砖缝（refs/palette.json），和炸锅 C 的砖完全同色：r=5 最亮 … q=1 最暗，o / O = 砂浆亮 / 暗
const BR = { r: '#b1624d', B: '#9b5643', n: '#8f503f', b: '#7c4536', q: '#733f31', o: '#a2867d', O: '#8b6e67', x: '#4a2116' };
// 炸锅 C 灶口的炭膛色：o/r 炭黑、p/q 烧透的暗褐、8/9 炉灰、w 余火暗红、s 一点亮火星
const HB = { o: '#190804', r: '#2a120b', p: '#4a2116', q: '#5f341f', 8: '#4a4644', 9: '#6b6562', w: '#8a3311', s: '#c35d1b' };
// 炉膛内：烟熏黑
const SO = { a: '#1d1411', b: '#271b16', c: '#33241c', d: '#402d22' };
// 火：农夫乐事炉灶的火色；w 是炸锅 C 待机余火用的暗红
const FI = { k: '#190804', x: '#4a2116', y: '#5f341f', z: '#46261e', b: '#7c4536', w: '#8a3311', o: '#c35d1b', O: '#ed8c0e', Y: '#ffd800' };
// 木头：农夫乐事木勺 / 砧板（果木：树皮 m/d，年轮 L）
const WD = { L: '#997140', l: '#86683c', m: '#6d5736', d: '#55452e' };
// 铁件
const FE = { d: '#343438', m: '#595858', l: '#727272' };
// 烤鸭（枣红 / 红木色，挂炉鸭刷了糖水的亮皮）。待机和工作中是同一只烤好的鸭子，工作中只多一个往下淌的油光点
// （不随点火 / 熄火变生变熟）。整体压在深红褐，不用橙色，免得待机时被看成一簇火苗；暗档 d 比被火映红的内壁
// （#5f341f / #7c4536）更深更冷，下半身不会融进墙里。
const DUCK_PAL = { i: FE.l, h: '#d98352', l: '#a8442a', m: '#7a2a16', d: '#3e1006', W: '#ffe08a' };

// ---------------------------------------------------------------- textures
// 炉身侧面（默认 UV）。第 1、14 列是倒角面（压暗一档显得圆）；第 2..13 列是立面。
// 第 2 行给上层炉肩侧面（y 13..14，第 4、11 列是它的倒角），第 3 行给下层炉肩（y 12..13，第 2、13 列是倒角），
// 第 4..13 行给炉身（y 2..12）：第 4 行顶沿受光（h），第 5..11 行抹泥面，第 12..13 行是一皮砖（和炸锅 C 侧面同一种砖：
// 上亮下暗、竖缝用砂浆色，亮行配亮砂浆 o、暗行配暗砂浆 O）。倒角列（1、14）一律压暗一档。
// side：西、南两面和所有倒角；side_east：东面，多一块泥皮剥落、露出红砖的补丁（只这一面有，不再三面重复）。
const SIDE = full([
  'llllmlllllllmlll',   // 0..1 只出现在破坏粒子里
  'lllmllllllllllml',
  'lllllhhhhhhlllll',   // 2 上层炉肩（第 4、11 列倒角压暗）
  'lllhhhhlhhhhhlll',   // 3 下层炉肩（第 2、13 列倒角压暗）
  'llhhhhhlhhhhhhll',   // 4 炉身顶沿（受光；第 1、14 列倒角压暗）
  'lmllllllllhlllml',   // 5            草筋
  'lmlmmmmlllllhlml',   // 6 抹泥刮痕   草筋
  'lmllhhhhllllllml',   // 7 刮痕下沿的泥棱受光
  'lmlllllhllllllml',   // 8
  'lmlhlllllmmmmlml',   // 9 抹泥刮痕
  'lmllllllllhhhhml',   // 10
  'lmllllhlllllllml',   // 11
  'qnBBoBBrBBBoBBnq',   // 12 一皮砖：亮行（竖缝 4、11 列）
  'qbnbOnnbnnbOnnbq',   // 13 暗行
  'qBrBBBBoBBrBBBoq',   // 14..15 只出现在破坏粒子里
  'qnnbnnnOnnbnnnOq',
]);
function sideTex(patch) {
  const c = makeCanvas(16, 16);
  rows(c, 0, 0, SIDE, { ...CL, ...BR });
  if (patch) {
    // 剥落处：第 3..7 列（东面看是 z 8..13，后半截），第 6..10 行（y 5..10）。上沿泥皮投下阴影，中间两皮砖夹一道浅砖缝，下沿泥皮厚度受光。
    rows(c, 3, 6, [
      '.ddd.',
      'dBrBB',
      'dBooo',
      'dnonB',
      '.hhhh',
    ], { ...CL, ...BR });
  }
  return c;
}
write(sideTex(false), T('side'));
write(sideTex(true), T('side_east'));

// 正面贴面（元素 x 2..14，y 2..12 → 默认 UV 第 2..13 列，第 4..13 行）。拱形炉口镂空（第 6..13 行，最宽 8 像素），
// 一圈红砖拱券（X/Y/Z/W 四档砖色，最亮一档 = 炸灶最亮的砖 #b1624d；Q 是拱券上的砖缝），底下两行接着炉身那一皮砖。
// 工作中版本：拱券第 7..13 行被炭火映红，越靠下越亮；拱顶（第 5、6 行）保持砖色，不会点火后反而变暗。
function front(lit) {
  const c = makeCanvas(16, 16, CL.l);
  const pal = { ...CL, r: BR.r, B: BR.B, n: BR.n, b: BR.b, q: BR.q, o: BR.o, O: BR.O, X: BR.r, Y: BR.B, Z: BR.b, W: BR.q, Q: BR.o };
  const map = full([
    'llllllllllllllll',
    'llllllllllllllll',
    'llllllllllllllll',
    'llllllllllllllll',
    'llhhhmddddmhhhll',   // 4 顶沿（受光），拱顶正上方被炉口冒出的烟熏黑（和炸锅 C 的门楣同一个做法）
    'llllmZQXXQZmllll',   // 5 拱顶
    'llmlQY....YQlmll',   // 6
    'llmZX......XZlll',   // 7 拱肩
    'llmQ........Qhll',   // 8..13 拱脚
    'llhY........Ymll',
    'llmX........Xlll',
    'lllQ........Qlll',
    'llBY........YBll',   // 12..13 炉身底部那一皮砖绕到正面
    'llnW........Wnll',
    'llllllllllllllll',
    'llllllllllllllll',
  ]);
  rows(c, 0, 0, map, pal);
  if (lit) {
    const brick = [null, null, null, null, null, null, null, FI.b, FI.o, FI.o, FI.o, FI.O, FI.O, FI.O];
    const joint = [null, null, null, null, null, null, null, FI.x, FI.b, FI.b, FI.b, FI.o, FI.o, FI.o];
    map.forEach((ln, y) => [...ln].forEach((ch, x) => {
      if (!brick[y]) return;
      if ('XYZW'.includes(ch)) setPx(c, x, y, brick[y]);
      else if (ch === 'Q') setPx(c, x, y, joint[y]);
    }));
  }
  // 镂空：rows() 用 '.' 跳过了，这里真的挖成透明
  map.forEach((ln, y) => [...ln].forEach((ch, x) => { if (ch === '.') setPx(c, x, y, [0, 0, 0, 0]); }));
  return c;
}
write(front(false), T('front'));
write(front(true), T('front_on'));

// 顶面（默认 UV）：按离中心的“方环”上色。第 1 圈（列/行 1、14）是炉身顶沿，2..3 是下层炉肩，4..5 是上层炉肩，
// 6..9 被烟口盖住。每一级的外沿用亮色 h（炸灶灶面上的亮点同色），内圈 l；炉身顶沿那 1px 用 l，免得和下层炉肩外沿连成一圈；
// 上层炉肩靠烟口的一圈熏黑。
{
  const c = makeCanvas(16, 16, CL.l);
  const ring = { 6.5: CL.l, 5.5: CL.h, 4.5: CL.l, 3.5: CL.h, 2.5: CL.d };
  for (let y = 0; y < 16; y++) for (let x = 0; x < 16; x++) {
    const d = Math.max(Math.abs(x - 7.5), Math.abs(y - 7.5));
    setPx(c, x, y, ring[d] || (d < 2.5 ? CL.k : CL.l));
  }
  // 几点泥巴斑（只落在 l 的内圈上）
  for (const [x, y] of [[3, 6], [12, 9], [9, 3], [6, 12], [3, 10], [12, 5]]) setPx(c, x, y, CL.m);
  for (const [x, y] of [[5, 8], [10, 7]]) setPx(c, x, y, CL.k);
  write(c, T('top'));
}

// 陶土烟口：侧面第 0..1 行（第 6..9 列），顶面 [6,6,10,10] 一圈口沿 + 烟洞。工作中版本：烟洞透出火光（俯视也看得出点着了）。
function flue(lit) {
  const c = makeCanvas(16, 16, BR.n);
  rows(c, 6, 0, ['rBrB', 'nbnq'], BR);
  rows(c, 6, 6, ['qBBq', 'Bxxn', 'Bxxn', 'qnnq'], BR);
  if (lit) rows(c, 7, 7, ['bo', 'ob'], FI);
  return c;
}
write(flue(false), T('flue'));
write(flue(true), T('flue_on'));

// 红砖台基：侧面用第 14..15 行（一皮丁砖），顶面另一张：看得见的只有外圈 1px、四个倒角和炉口门槛。
// 北面和东面的 uv 在模型里左右镜像，转角处侧面的竖缝和顶面外沿的砂浆缝对得上（第 5、11 列）。
// 侧面和炸锅 C 的砖同一种写法：上行亮砖配亮砂浆缝 o，下行暗砖配暗砂浆缝 O。
// 顶面外圈那 1px 抹一道砂浆（o，转角和竖缝对上的地方 O），从斜上方看像炸灶砖墙上一道横向灰缝，
// 不再是一圈最亮的砖色（原先 100% 顶光下发粉，和炸灶的砖对不上）。炉口门槛（x 4..12）还是砖。
{
  const c = makeCanvas(16, 16, BR.n);
  rows(c, 0, 14, full([
    'BBrBBoBBBBBoBrBB',
    'nbnnbOnnbnnOnbnn',
  ]), BR);
  write(c, T('plinth'));
}
{
  const c = makeCanvas(16, 16, BR.n);
  rows(c, 0, 0, full([
    'OooooOoooooOoooO',
    'oOnnBnBBnBBnnnOO',   // 炉口门槛（x 4..12）；第 1、14 列是炉身倒角让出来的角，也抹砂浆
    'onnnnnnnnnnnnnnO',
    'onnnnnnnnnnnnnnO',
    'onnnnnnnnnnnnnnO',
    'OnnnnnnnnnnnnnnO',
    'onnnnnnnnnnnnnnO',
    'onnnnnnnnnnnnnnO',
    'onnnnnnnnnnnnnnO',
    'onnnnnnnnnnnnnnO',
    'onnnnnnnnnnnnnnO',
    'OnnnnnnnnnnnnnnO',
    'onnnnnnnnnnnnnnO',
    'onnnnnnnnnnnnnnO',
    'oOnnnnnnnnnnnnOO',
    'OooooOoooooOoooO',
  ]), BR);
  write(c, T('plinth_top'));
}

// 炉膛内壁：烟熏黑砖。墙面看得见的是第 7..12 行（y 3..10；第 13 行在炭床后面）。工作中：只有贴着炭火的第 9..12 行
// 被映红（最亮一档在第 12 行，正好是炭面上方 y 3..4），上半截保持黑，吊鸭的轮廓才衬得出来。
// 第 10..12 行有三簇 2 宽的火舌（橙色打底、亮橙做芯、最高那格尖上一点黄），4 帧里轮流蹿高 / 落下 1px。
// 拱顶天花板（vault 的 down 面）只取第 0..8 行，永远不发光。
const joint = (x, y) => y % 3 === 2 || (x + (Math.floor(y / 3) % 2) * 3) % 6 === 0;
function innerBase() {
  const c = makeCanvas(16, 16, SO.b);
  for (let y = 0; y < 16; y++) for (let x = 0; x < 16; x++) {
    if (joint(x, y)) setPx(c, x, y, SO.a);
    else if ((x * 7 + y * 3) % 11 === 0) setPx(c, x, y, SO.c);
  }
  return c;
}
write(innerBase(), T('inner'));
{
  const frames = [];
  const glow = [   // 第 9..12 行的亮度档
    [1, 2, 3, 4],
    [2, 2, 3, 4],
    [1, 2, 3, 4],
    [1, 1, 3, 4],
  ];
  const ramp = [null, [SO.b, SO.d], [FI.z, FI.x], [FI.x, FI.y], [FI.y, FI.b]];
  // 火舌：[左列, 起始相位, 最高几格]。第 4..5、10..11 列在后墙上落在吊鸭两边（x 10..12 / 4..6），侧墙上落在中段；
  // 第 7..8 列在后墙上藏在鸭子后面，侧墙上看得见，所以矮一点。
  const tongues = [[4, 0, 3], [10, 2, 3], [7, 1, 2]];
  const H = [[3, 2], [2, 3], [3, 1], [1, 2]];
  for (let f = 0; f < 4; f++) {
    const c = innerBase();
    for (let k = 0; k < 4; k++) {
      const y = 9 + k, g = glow[f][k];
      for (let x = 0; x < 16; x++) setPx(c, x, y, ramp[g][joint(x, y) ? 0 : 1]);
    }
    for (const [x0, ph, max] of tongues) {
      const hs = H[(f + ph) % 4];
      hs.forEach((h0, i) => {
        const h = Math.min(h0, max);
        for (let k = 0; k < h; k++) setPx(c, x0 + i, 12 - k, k === 0 ? FI.o : k === 1 ? FI.O : FI.Y);
      });
    }
    frames.push(c);
  }
  write(strip(frames), T('inner_on'));
  json(`textures/block/${P}_inner_on.png.mcmeta`, { animation: { frametime: 4 } });
}

// 炭床（元素 x 4..12，z 2..10 的顶面，默认 UV [4,2,12,10]；正面一行用 [4,13,12,14]）。
// 待机：和炸锅 C 待机灶口的炭床同一套颜色和比例——以炭黑（o/r）、烧透的暗褐（p/q）为主，零星几点暖灰炉灰（8/9），
// 埋着两截果木，几点暗红余火（w）和一点亮火星（s）；不用冷灰、也不让灰点连成格子，免得炭床看着像一块铁箅子。
// 工作中：通红的炭，4 帧 × 5 tick 插值（和农夫乐事炉灶顶面一样插值）。
{
  const c = makeCanvas(16, 16, HB.r);
  rows(c, 4, 2, full([
    'r8orqo9p',
    'oqwrpr8o',
    '9poroqrr',
    '8mmmmmlq',   // 果木
    'roq8pwro',
    'qrosrpo9',
    'pr9lmmmr',
    '8qorpowo',
  ], 8), { ...HB, m: WD.d, l: WD.m });
  rows(c, 4, 13, ['orqwpr8q'], HB);
  write(c, T('coal'));
}
{
  const frames = [];
  const base = [
    'yzbzyzoy',
    'zoOyboOz',
    'bOYobzbo',
    'zxxxxxbz',   // 果木（烧黑的芯）
    'ybOzoyOb',
    'oYbzoOYz',
    'zbozxxxx',
    'yzOboyzO',
  ];
  const pal = { y: FI.y, z: FI.z, b: FI.b, o: FI.o, O: FI.O, Y: FI.Y, x: FI.x };
  const cycle = { y: 'z', z: 'b', b: 'o', o: 'O', O: 'Y', Y: 'O', x: 'x' };   // 每帧有一半的点亮一档
  for (let f = 0; f < 4; f++) {
    const c = makeCanvas(16, 16, FI.z);
    rows(c, 4, 2, base.map((ln, j) => [...ln].map((ch, i) => ((i * 3 + j * 5 + f * 7) % 4 < 2 ? cycle[ch] : ch)).join('')), pal);
    rows(c, 4, 13, [[...'zboOobzo'].map((ch, i) => ((i + f) % 3 === 0 ? cycle[ch] : ch)).join('')], pal);
    frames.push(c);
  }
  write(strip(frames), T('coal_on'));
  json(`textures/block/${P}_coal_on.png.mcmeta`, { animation: { frametime: 5, interpolate: true } });
}

// 吊鸭（cutout，6×6 画在 [0,0,6,6]，元素 y 4..10）：最上一行是挂在炉膛顶下的 S 钩，下面是细脖子和下沉的水滴形鸭身，
// 左上受光、右下压暗；不画鸭掌（挂炉鸭本来就去掉了），也没有两侧单独凸出的像素。鸭底离炭面 1px，看得出是吊着的。
const DUCK = [
  '..i...',   // S 钩（从炉膛顶垂下来）
  '..lm..',   // 脖子
  '.hllm.',   // 肩
  'hhllmd',
  'hlllmd',   // 最宽处
  '.lmmd.',   // 鸭底
];
function duck(glint) {
  const c = makeCanvas(16, 16);
  rows(c, 0, 0, DUCK, DUCK_PAL);
  if (glint) setPx(c, glint[0], glint[1], DUCK_PAL.W);
  return c;
}
write(duck(null), T('duck'));
{
  const glints = [[2, 2], [2, 3], [2, 4], [1, 5]];   // 油光顺着鸭身往下淌
  write(strip(glints.map(duck)), T('duck_on'));
  json(`textures/block/${P}_duck_on.png.mcmeta`, { animation: { frametime: 6 } });
}

// 零件图集：
//   挑杆：第 0 列西面（11 长，顶上两格是铁钩），第 1 列其余侧面，[2,0] 端面
//   果木：[4,5,10,8] 并排两段的端面，[4,8,7,11] 单段端面（3×3：四角树皮、一圈浅色边材、深色芯，看上去是个圆截面），
//         [11,5,12,11] 树皮
{
  const c = makeCanvas(16, 16, WD.m);
  const pal = { I: FE.l, i: FE.m, F: FE.d, L: WD.L, l: WD.l, m: WD.m, d: WD.d };
  rows(c, 0, 0, [...'IilLlLmlLlm'].map((ch, i) => ch + 'iFmlmlmdmlm'[i]), pal);
  setPx(c, 2, 0, WD.d);
  rows(c, 4, 5, ['mLmmLm', 'LdLLdL', 'mLmmLm'], WD);
  rows(c, 4, 8, ['mLm', 'LdL', 'mLm'], WD);
  rows(c, 11, 5, ['m', 'd', 'm', 'm', 'd', 'm'], WD);
  write(c, T('parts'));
}

// ---------------------------------------------------------------- models
const f = (texture, uv, extra = {}) => (uv ? { uv, texture, ...extra } : { texture, ...extra });
const sides = (tex, extra = {}) => ({ north: f(tex, null, extra), south: f(tex, null, extra), west: f(tex, null, extra), east: f(tex, null, extra) });
/** a rounded slab: footprint [x0..x1]×[z0..z1] with its four corners cut by 1px — three boxes */
function roundSlab(name, x0, z0, x1, z1, y0, y1) {
  return [
    { name: name + ' core', from: [x0, y0, z0 + 1], to: [x1, y1, z1 - 1], faces: { ...sides('#side'), up: f('#top') } },
    { name: name + ' front', from: [x0 + 1, y0, z0], to: [x1 - 1, y1, z0 + 1], faces: { north: f('#side'), west: f('#side'), east: f('#side'), up: f('#top') } },
    { name: name + ' back', from: [x0 + 1, y0, z1 - 1], to: [x1 - 1, y1, z1], faces: { south: f('#side'), west: f('#side'), east: f('#side'), up: f('#top') } },
  ];
}

function elements(active) {
  // shade 是元素级属性（原版 BlockElement.shade），写在面上不生效。炉膛里的面都拆成只有一个面的元素，
  // 工作中整个元素 shade:false：不吃方向明暗，看起来被炭火照亮；外墙不受影响。
  const lit = active ? { shade: false } : {};
  return [
    { name: 'plinth', from: [0, 0, 0], to: [16, 2, 16], faces: {
      north: f('#plinth', [16, 14, 0, 16], { cullface: 'north' }), south: f('#plinth', [0, 14, 16, 16], { cullface: 'south' }),
      west: f('#plinth', [0, 14, 16, 16], { cullface: 'west' }), east: f('#plinth', [16, 14, 0, 16], { cullface: 'east' }),
      up: f('#plinth_top'), down: f('#plinth_top', null, { cullface: 'down' }) } },
    // 正面贴面（拱形炉口镂空）
    { name: 'front', from: [2, 2, 1], to: [14, 12, 2], faces: {
      north: f('#front'), up: f('#top'), west: f('#side'), east: f('#side') } },
    // 炉身外壳：西墙、东墙、后段、后贴面（炉膛那一侧的面不在这里，见下面的内衬）
    { name: 'wall west', from: [1, 2, 2], to: [4, 12, 10], faces: { west: f('#side'), north: f('#side'), up: f('#top') } },
    { name: 'wall east', from: [12, 2, 2], to: [15, 12, 10], faces: { east: f('#side_e'), north: f('#side'), up: f('#top') } },
    { name: 'back', from: [1, 2, 10], to: [15, 12, 14], faces: { west: f('#side'), east: f('#side_e'), south: f('#side'), up: f('#top') } },
    { name: 'back skin', from: [2, 2, 14], to: [14, 12, 15], faces: { south: f('#side'), west: f('#side'), east: f('#side'), up: f('#top') } },
    // 炉膛内衬（各一个面）。两侧内壁从 z=1 起：贴面镂空处那 1px 厚的拱脚侧面也由它们封住，贴着炉口斜看不会透到外面。
    { name: 'lining west', from: [3, 2, 1], to: [4, 12, 10], ...lit, faces: { east: f('#inner') } },
    { name: 'lining east', from: [12, 2, 1], to: [13, 12, 10], ...lit, faces: { west: f('#inner') } },
    { name: 'lining back', from: [4, 2, 10], to: [12, 12, 11], ...lit, faces: { north: f('#inner') } },
    // 拱顶天花板：也从 z=1 起，封住炉口最上一行往上看的视线；uv 只取内壁贴图不发光的第 0..8 行
    { name: 'vault', from: [4, 10, 1], to: [12, 12, 10], ...lit, faces: { down: f('#inner', [4, 0, 12, 9]) } },
    // 两级炉肩（倒角）+ 烟口
    ...roundSlab('shoulder', 2, 2, 14, 14, 12, 13),
    ...roundSlab('crown', 4, 4, 12, 12, 13, 14),
    { name: 'flue', from: [6, 13, 6], to: [10, 16, 10], faces: { ...sides('#flue'), up: f('#flue', null, { cullface: 'up' }) } },
    // 炉膛：炭床 + 吊鸭（离炉膛口 2px，离贴面正面 3px；斜着看也能看见）
    { name: 'coal bed', from: [4, 2, 2], to: [12, 3, 10], ...lit, faces: {
      up: f('#coal'), north: f('#coal', [4, 13, 12, 14]) } },
    { name: 'duck', from: [5, 4, 4], to: [11, 10, 4], ...lit, faces: { north: f('#duck', [0, 0, 6, 6]) } },
    // 西侧：挑杆（长 11，绕自己中点转 22.5°：最低角 y≈2.03 刚好落在台基上、不和台基侧面共面，上端搭到炉身顶沿 y≈12.2–12.6）
    { name: 'pole', from: [0, 1.8, 4.5], to: [1, 12.8, 5.5], rotation: { origin: [0.5, 7.3, 5], axis: 'x', angle: 22.5 }, faces: {
      west: f('#parts', [0, 0, 1, 11]), north: f('#parts', [1, 0, 2, 11]), south: f('#parts', [1, 0, 2, 11]), east: f('#parts', [1, 0, 2, 11]),
      up: f('#parts', [2, 0, 3, 1]), down: f('#parts', [2, 0, 3, 1]) } },
    // 西侧后半截：码在台基上的三段果木（两段并排 + 一段压在上面），年轮朝外
    { name: 'logs', from: [0, 2, 8], to: [1, 5, 14], faces: {
      west: f('#parts', [4, 5, 10, 8]), north: f('#parts', [11, 5, 12, 8]), south: f('#parts', [11, 5, 12, 8]), up: f('#parts', [11, 5, 12, 11]) } },
    { name: 'log top', from: [0, 5, 9.5], to: [1, 8, 12.5], faces: {
      west: f('#parts', [4, 8, 7, 11]), north: f('#parts', [11, 5, 12, 8]), south: f('#parts', [11, 5, 12, 8]), up: f('#parts', [11, 5, 12, 8]) } },
  ];
}

const textures = {
  particle: ID('side'),
  side: ID('side'), side_e: ID('side_east'), front: ID('front'), top: ID('top'), flue: ID('flue'), plinth: ID('plinth'), plinth_top: ID('plinth_top'),
  inner: ID('inner'), coal: ID('coal'), duck: ID('duck'), parts: ID('parts'),
};
const idle = elements(false), active = elements(true);
const faceCount = els => els.reduce((n, e) => n + Object.keys(e.faces).length, 0);
const litCount = active.filter(e => e.shade === false).length;
// ambientocclusion false：和炸锅 C 一样两个模型都写（见 meta notes）
json(`models/block/${P}.json`, { parent: 'minecraft:block/block', ambientocclusion: false, render_type: 'minecraft:cutout', textures, elements: idle });
json(`models/block/${P}_on.json`, {
  parent: `${NS}:block/${P}`,
  ambientocclusion: false,
  render_type: 'minecraft:cutout',
  textures: { front: ID('front_on'), inner: ID('inner_on'), coal: ID('coal_on'), duck: ID('duck_on'), flue: ID('flue_on') },
  elements: active,
});

json('meta.json', {
  key: P,
  tag: '烤炉 C',
  order: 13,
  name: '吊炉',
  tagline: '红砖台基上一座黄泥圆顶吊炉，拱形炉口里吊着一只烤鸭，侧面靠着挑杆、码着果木。',
  description: '中式挂炉 / 烤鸭炉，和炸锅 C「中式炸灶」同一套砖色和黄泥。一皮红砖台基（整格 16×2×16），上面是 14×14×10 的黄泥炉身，四角各倒 1px，炉身底部一皮带砖缝的红砖（和炸灶灶身同一种砖）；顶上两级收分的炉肩也倒角，每一级的外沿受光，看上去是个矮圆顶，正中一只陶土烟口（顶到 y=16，不出格）。正面是带砖缝的红砖拱券、拱形炉口，拱顶上方的泥面被烟熏黑。炉膛是真的空腔（8 宽 × 8 高 × 8 深）：底下一层果木炭，炉膛顶下一只 S 钩吊着烤鸭（水滴形鸭身，离炭面 1px）。东侧（正面看在左手边）一块泥皮剥落，露出里面的红砖；西侧（正面看在右手边）一根挑杆斜靠在炉身上、杆头搭到炉身顶沿，后半截脚下码着三段果木，年轮朝外。待机时炭灰里还有几点暗红余火；工作中炭火通红闪动、后墙底部蹿起火舌、炉膛下半截被映红（上半截和拱顶保持黑）、拱券下半圈映红、烟洞透出火光，鸭身上一个油光点往下淌。',
  notes: [
    '自带炭火，不需要下方热源（和需要炉灶的厨锅区分开，和炸锅 C 一样自带火）；可以考虑烧木炭 / 果木当燃料。待机留几点暗红余火，和炸锅 C 待机灶口同一条规则。',
    '和炸锅 C / 备餐台 C 成套的配色：砖、砂浆、黄泥、炭膛、火全部直接用炸锅 C 的颜色（黄泥最亮只到炸灶灶沿那一档 #c5aa80，砖最亮只到 #b1624d），木头用农夫乐事木勺色，铁件用农夫乐事锅具灰。本方案自己多出来的只有烤鸭五色、炉膛内壁四档烟熏黑和一档炉膛映光 #46261e。',
    `待机 / 工作中两个模型：工作中 parent 待机模型（也显式写了 render_type cutout），换五张贴图：炭床（4 帧 × 5 tick 插值）、炉膛内壁（4 帧 × 4 tick 火光 + 三簇火舌）、吊鸭（4 帧 × 6 tick 油光）、正面拱券（下半圈映红，静态）、烟口（烟洞透火光，静态）。元素表整份重写（几何不变），只把炉膛里 ${litCount} 个单面元素（两侧内衬、后内衬、拱顶、炭床、吊鸭）设成元素级 shade:false，看起来自己发亮。`,
    `元素：${idle.length} 个、${faceCount(idle)} 个面（只有挑杆 1 个 22.5° 旋转），待机和工作中一样；全部 16×16 贴图，render_type cutout（拱形炉口、吊鸭镂空），alpha 只有 0 / 255。`,
    '朝向：正面（炉口）画在 north，facing=north 时 y=0，east 90，south 180，west 270；blockstate 不要开 uvlock（台基门槛、东面补丁要跟着模型一起转）。挑杆和果木在模型西侧（正面看右手边），全部部件都在 0..16 以内。',
    '实装：Block.Properties 加 noOcclusion()（贴着它的邻居面不能被剔除，否则倒角和台基外圈那 1px 会透出世界）；lightLevel 写成 s -> s.getValue(LIT) ? 13 : 3（和炸锅 C 同一口径：工作中 13 同农夫乐事炉灶点火，待机 3 是余火）。烟口冒烟、炉口偶尔飘火星的粒子游戏里另加。',
    '两个模型都写了 "ambientocclusion": false（和炸锅 C 一样）：原版只在方块光照为 0 时画 AO，关掉后待机 / 工作中切换时炉膛、鸭子下沿的明暗不会跳，游戏里和预览看到的一致；三件 C 家族挨着摆时，墙根、转角的明暗写法也统一（炸灶不画 AO，这边也不画）。进游戏若仍嫌炉膛暗，可以再给工作中模型的 6 个炉膛元素加 Forge 的 forge_data {block_light: 15}（原版忽略这个键，要实机验证）。',
    '碰撞箱建议：Shapes.or(box(0,0,0,16,2,16), box(1,2,1,15,12,15), box(2,12,2,14,13,14), box(4,13,4,12,14,12), box(6,14,6,10,16,10))，中心对称，四个朝向共用；挑杆、果木不进碰撞箱。嫌麻烦也可以直接整格。',
  ],
  hires: false,
  namespace: NS,
  blocks: [
    { label: '吊炉', pos: [0, 0, 0], idle: { model: P, y: 0 }, active: { model: `${P}_on`, y: 0 } },
  ],
});
console.log(`oven C written: ${idle.length} elements, ${faceCount(idle)} faces, ${litCount} lit elements in _on`);
