// 备餐台 C「中式案台」—— 生成模型 JSON + 16×16 贴图 + meta.json。
//   cd <仓库>\tools\chef_stations
//   node designs-more/prep_c/gen.mjs
// 所有贴图都在这里逐像素画（lib/png.mjs），改了重跑即可；产物结构和主 MOD 的 assets/miningdim/ 一致。
// 每次运行先清空本方案的 models/ 和 textures/，不会留下旧方案的贴图。
//
// 和 C 家族（炸锅 C「中式炸灶」、烤炉 C「吊炉」）成套：柜身两侧和背面是和炸灶灶身同一种砖砌（同砖色、同砂浆缝、同一种错缝，
// 最上一皮砖压在黄泥压顶的阴影里），黄泥压顶和炸灶灶沿同两行写法，再压一块木案板；正面一对枣红漆木门
// （漆色取炸灶余火暗红 #8a3311、吊炉烤鸭的红 #a8442a、炸灶炭膛暗褐 #4a2116）；黑铁（菜墩铁箍、菜刀，农夫乐事锅具灰，
// 刃口最亮只到炸灶笊篱网丝那一档附近）；竹蒸笼（炸灶笊篱竹柄同一套竹色）。
// 2026-10-10 家族一致性收尾：原先是木框板柜身 + 朱漆（#cf4529）门，和炸灶 / 吊炉只共用 3px 台基，现在改成上面这样。
//
// 坐标约定（和农夫乐事炉灶一样）：正面 = north（z=0），从正面看 +x（东）在左手边。
// 各面默认 UV 的方向（写 uv 时照这个对）：
//   up    u↔x（u 小 = 西）  v↔z（v 小 = 北）        down  u↔x（u 小 = 西）  v↔z（v 小 = 南）
//   north u 小 = 东        south u 小 = 西        east  u 小 = 南        west  u 小 = 北      （侧面 v 小 = 上）
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { makeCanvas, setPx, strip, write } from '../../lib/png.mjs';

const dir = path.dirname(fileURLToPath(import.meta.url));
const NS = 'miningdim';
const KEY = 'prep_c';
const ID = n => `${NS}:block/${KEY}${n ? '_' + n : ''}`;
const T = n => path.join(dir, 'textures/block', `${KEY}_${n}.png`);
const json = (rel, o) => { const f = path.join(dir, rel); fs.mkdirSync(path.dirname(f), { recursive: true }); fs.writeFileSync(f, JSON.stringify(o, null, 2) + '\n'); };
/** draw rows of single-char colour keys at (x0, y0); '.' / ' ' = leave transparent */
const rows = (c, x0, y0, lines, pal) => lines.forEach((ln, j) => [...ln].forEach((ch, i) => {
  if (ch === '.' || ch === ' ') return;
  if (!(ch in pal)) throw new Error(`colour key "${ch}" missing (row ${j}: ${ln})`);
  setPx(c, x0 + i, y0 + j, pal[ch]);
}));
const full = (lines, w = 16) => { for (const l of lines) if (l.length !== w) throw new Error(`row width ${l.length} != ${w}: ${l}`); return lines; };

for (const sub of ['models', 'textures']) fs.rmSync(path.join(dir, sub), { recursive: true, force: true });

// ---------------------------------------------------------------- palette
// 木作（柜体、门芯、刀柄）：农夫乐事木勺 / 砧板 #55452e #6d5736 #86683c #997140，往暗处补两档、亮处补一档
const WD = { a: '#2e2318', b: '#3d3022', c: '#55452e', d: '#6d5736', e: '#7a613a', f: '#86683c', g: '#997140', h: '#a8814c' };
// 红砖 + 砂浆：农夫乐事炉灶，和炸灶、吊炉同色（r=5 最亮 … q=1 最暗，o / O = 砂浆亮 / 暗）
const BR = { r: '#b1624d', B: '#9b5643', n: '#8f503f', b: '#7c4536', q: '#733f31', o: '#a2867d', O: '#8b6e67' };
// 黄泥：炸灶 / 吊炉同五档（h 最亮 = 炸灶灶沿顶行的亮色）
const CL = { h: '#c5aa80', l: '#b4976e', m: '#a2845c', d: '#8a6c48', k: '#6e5437' };
// 枣红漆：全取自家族里已有的红——炸灶炭膛暗褐、炸灶余火暗红、吊炉烤鸭的红
const RD = { d: '#4a2116', m: '#8a3311', l: '#a8442a' };
// 竹蒸笼 / 黄铜面叶：农夫乐事篮子的金黄（炸灶笊篱的竹柄也是这一套）
const BA = { k: '#7d6e31', d: '#998741', m: '#bba84c', n: '#c8b14d', l: '#d3bb50', h: '#e3cc6a', s: '#efe6c4' };
// 黑铁：农夫乐事锅具冷灰
const FE = { A: '#1f1f21', B: '#27272b', C: '#2d2d32', D: '#343438', E: '#3f3e42', F: '#494848', G: '#4f4f4f', H: '#595858', I: '#656565', L: '#727272' };
// 菜刀开刃的钢口（锅具灰往亮处延伸）：磨亮的一条 = 炸灶笊篱网丝的亮色 #999897，刃口再亮一档；w 只在工作中滑过的那一点高光里出现
const SL = { l: '#999897', h: '#b4b2b0', w: '#e4e2de' };
// 菜墩端面：比台面浅一大截的木色，年轮一圈深一圈浅
const EW = { a: '#8a6a3e', b: '#a58250', c: '#bf9c64', d: '#d2b47c', e: '#e2c995' };
// 酱坛：酱釉
const GZ = { k: '#38211a', d: '#4a2c1f', m: '#6b4130', l: '#8a5a40', h: '#a8734f' };
const HEMP = '#cdbb8e';
// 青花碗
const PO = { s: '#cbc7bb', m: '#e6e3da', l: '#f6f4ee' };
const IN = { d: '#26396a', m: '#3a5a9a', l: '#6f8fc4' };
// 菜：葱花、胡萝卜、拍黄瓜
const VG = { G: '#2f6b24', g: '#4f9a34', y: '#7cc04a', p: '#cfe8a0', o: '#d8702b', O: '#ed8c3c', w: '#eeeadd' };
// 包子
const BUN = { s: '#cfc6ae', m: '#e6e0cf', l: '#f6f3ea' };

// ---------------------------------------------------------------- textures
// 侧面（东 / 西 / 南共用；第 c 列 = z 坐标），整面就是炸灶灶身那一套写法：
//   第 0–1 行 = 台面板侧边（三块木板的端头，第 5、11 列是拼缝，和台面贴图的拼缝对齐）
//   第 2–3 行 = 黄泥压顶（y 14→12），和炸灶灶沿同样上亮下暗两行
//   第 4–15 行 = 砖墙（y 12→0）：三皮砖，每皮 3 行砖 + 1 行砂浆，竖缝错开；第 4 行是压在泥顶下的那皮砖的暗面（炸灶同款）。
//   柜身元素用第 2–12 行，台基元素（y 0–3）用第 13–15 行，两个元素在侧面齐平，砖缝上下接得上。第 1 列给前立柱的侧面。
// 炸灶侧面第 8–15 行 + 第 4–5、2–3 行逐字换成本文件的色键（1→q 2→b 3→n 4→B 5→r，砂浆 g→O h→o）
const WOOD_EDGE = full(['hhghheghhhghehhg', 'ffeffdfeffefdffe']);   // 第 0–1 行，木
const CLAY_CAP = full(['hlhhlhhhlhhlhhhl', 'mlmmdmlmmmlmmdmm']);   // 第 2–3 行，黄泥（炸灶 'nmnn…' / 'lmll…' 两行）
const BRICK_WALL = full([
  'qbbObbqbbbqObqbb',   //  4 砖 A（压在泥顶阴影里的暗面，竖缝 3、11）
  'BBnoBrBBBBnonBBn',   //  5
  'nnbOnnnnBnbObnnb',   //  6
  'OooooOoooooOoooo',   //  7 砂浆
  'rBBBBBBoBBrBBBBo',   //  8 砖 B（竖缝错半块：7、15）
  'BBBnBnnOnBnnnnnO',   //  9
  'nnbbnbbObbnbbbqO',   // 10
  'oooOooooooOooooo',   // 11 砂浆
  'BBnoBBrBBBBoBnBB',   // 12 砖 C（竖缝 3、11）
  'nnnOnnnnnnnOnnnn',   // 13
  'bbnObbbbbnbObbbb',   // 14
  'ooOooooooooOoooo',   // 15 砂浆（落地）
]);
{
  const c = makeCanvas(16, 16);
  rows(c, 0, 0, WOOD_EDGE, WD);
  rows(c, 0, 2, CLAY_CAP, CL);
  rows(c, 0, 4, BRICK_WALL, BR);
  write(c, T('side'));
}

// 正面：第 0–1 行台面板前沿；第 2–12 行（y 14→3）= 第 0、15 列两根砖垛 + 第 1–14 列一对枣红漆对开门
//   砖垛 1px 宽：上两行黄泥压顶、下面是侧墙第 1 列（z 1–2）那一竖条砖和砂浆，和侧面的横缝对齐，看上去是侧墙转过来包住门洞
//   每扇门 7×11：枣红漆门框，上半方格窗棂（4 格，后面是暗的柜膛），中间腰枋，下半木板芯；
//   两扇门合缝处一块 2×2 黄铜面叶（门钮另做一个凸出的小元素）
{
  const c = makeCanvas(16, 16);
  rows(c, 0, 0, full(['hhghhhhghhhhhghh', 'ffefffffefffffef']), WD);
  const p = { ...WD, R: RD.l, M: RD.m, D: RD.d, Y: BA.h, y: BA.l, k: BA.k };
  // 左门（东，正面看左边）：受光边在左
  rows(c, 1, 2, [
    'MMMMMMM',   // r0 上抹头（台面板挑檐下，不提亮）
    'RaafaaM',   // r1 窗棂：a = 柜膛暗处，f = 棂条
    'RfffffM',
    'RaafaaM',
    'RMMMMMY',   // r4 腰枋 + 面叶
    'RcccccY',   // r5 板芯上沿阴影 + 面叶
    'RcfgfgM',
    'RcgffgM',
    'RcffgfM',
    'RceeeeM',
    'DDDDDDD',   // r10 下抹头
  ], p);
  // 右门（西，正面看右边）：合缝那一列压暗，和左门分开
  rows(c, 8, 2, [
    'MMMMMMM',
    'DaafaaM',
    'DfffffM',
    'DaafaaM',
    'yMMMMMM',
    'kcccccM',
    'DcgfgfM',
    'DcfggfM',
    'DcgffgM',
    'DceeeeM',
    'DDDDDDD',
  ], p);
  // 砖垛：第 2–3 行黄泥，第 4–12 行取侧墙第 1 列（立柱侧面也是这一列），横缝和侧面对齐
  for (const col of [0, 15]) {
    rows(c, col, 2, CLAY_CAP.map(r => r[1]), CL);
    rows(c, col, 4, BRICK_WALL.slice(0, 9).map(r => r[1]), BR);
  }
  rows(c, 0, 13, BRICK_WALL.slice(9), BR);   // 不贴到面上，只给破坏粒子
  write(c, T('front'));
}

// 台基（y 0–3）：两侧和背面直接用侧面贴图第 13–15 行（和柜身侧墙齐平、砖缝接得上），这张只管两处：
//   顶面：只露出门前 1.5px 的一道台阶（第 0–1 行），抹黄泥，和炸灶灶面、吊炉一样“砖上抹泥”
//   正面第 13–15 行：台阶立面，一皮亮面朝外的砖 + 落地砂浆
{
  const c = makeCanvas(16, 16, CL.l);
  rows(c, 0, 0, full(['hlhhlhhhlhhlhhhl', 'llmllllmllllmlll']), CL);
  rows(c, 0, 13, full(['BBnoBBrBBBBoBnBB', 'nnbOnnnnBnbObnnb', 'ooOooooooooOoooo']), BR);
  write(c, T('base'));
}

// 台面：三块木板顺着左右走，第 5、11 行是拼缝；第 0 行前沿受光，第 15 行后沿
{
  const c = makeCanvas(16, 16);
  rows(c, 0, 0, full([
    'hhhhhhhhhhhhhhhh',
    'gggfgggggggfgggg',
    'ffgfffffefffffgf',
    'fffffeffffHgffff',
    'effffffffeffffff',
    'cdcdddcdddcdddcd',
    'gggggfgggggggfgg',
    'ffefffffgffffeff',
    'fffffgfffbfeffff',
    'fgfffffefffffffg',
    'ffffeffffffgffef',
    'dcdddcddcdddcddd',
    'gggfgggggfgggggg',
    'ffffffefffffgfff',
    'fefffgffffffffef',
    'eeeeeeeeeeeeeeee',
  ]), { ...WD, H: '#b48d58' });
  write(c, T('top'));
}

// 菜墩（圆木墩，6×3×6，四角各切 1px；腰上一道铁箍）：
//   (0,0) 顶面 6×6：外圈边材，里面一圈浅一圈深的年轮，中间髓心；几道刀痕
//   (0,8) 侧面 16×3：第 0 行边材（y18–19），第 1 行铁箍，第 2 行树皮色（y16–17）
{
  const c = makeCanvas(16, 16);
  rows(c, 0, 0, [
    '.bbbb.',
    'bedeeb',
    'becdeb',
    'bedceb',
    'beeedb',
    '.bbbb.',
  ], EW);
  rows(c, 0, 8, full(['cbcbbcbbcbbcbbcb']), EW);
  rows(c, 0, 9, full(['DEDDGDDEDDDGDDED']), FE);
  rows(c, 0, 10, full(['abaabaababaabaab']), EW);
  write(c, T('block'));
}

// 方头菜刀（图集）：
//   (0,0) 刀面 5×4：第 0 列刀尖、第 4 列刀根（接柄）；第 0 行刀背，第 1 行黑铁刀身（刀尖上角一个挂孔），
//         第 2 行开刃磨亮的一条，第 3 行刃口
//   (0,4) 刀背顶 5×1（只用 0.5 行），(5,0) 刀头端面 1×4（只用 0.5 列）
//   (8,0) 刀柄侧面 3×1：第 8 列铁箍（接刀根），第 9–10 列木柄；(8,1) 刀柄顶面 3×1；(11,0) 柄尾端面
function knife(glint) {
  const c = makeCanvas(16, 16);
  const p = { ...FE, ...SL, x: WD.b, y: WD.c, z: WD.d };
  const blade = ['DDDDC', 'FAFFE', 'lllll', 'hhhhh'];
  if (glint != null) {
    const col = 4 - glint;   // 从刀根滑到刀尖（工作中刀平放，刀面朝上，高光走在磨亮的那条和刃口上）
    const r = blade[2].split('');
    r[col] = 'w';
    if (col + 1 <= 4) r[col + 1] = 'h';
    blade[2] = r.join('');
    const e = blade[3].split(''); e[col] = 'w'; blade[3] = e.join('');
  }
  rows(c, 0, 0, blade, p);
  rows(c, 0, 4, ['DDDDD'], p);
  rows(c, 5, 0, ['D', 'F', 'l', 'h'], p);
  rows(c, 8, 0, ['Lyx'], p);
  rows(c, 8, 1, ['Lzy'], p);
  rows(c, 11, 0, ['x'], p);
  return c;
}
write(knife(null), T('knife'));
{
  // 工作中：菜刀平放在切好的菜后面，刃口一道高光从刀根滑到刀尖（5 帧 × 2 tick），然后停 2 秒
  const frames = [0, 1, 2, 3, 4].map(g => knife(g));
  frames.push(knife(null));
  write(strip(frames), T('knife_on'));
  json(`textures/block/${KEY}_knife_on.png.mcmeta`, { animation: { frametime: 2, frames: [0, 1, 2, 3, 4, { index: 5, time: 40 }] } });
}

// 竹蒸笼（图集，竹色）：笼屉用竹色里最浅的几档（s/h/n），笼盖用深的几档（m/d/k）——浅笼屉配深笼盖，
// 一眼看得出是“一摞笼屉 + 一只盖”，不会读成一捆干草
//   (0,0) 侧面 12×5：第 0 行笼盖边，第 1–2 行上层笼屉（浅笼沿 + 笼身，暗点是藤条绑扎），第 3–4 行下层笼屉
//   (0,5) 笼盖顶 6×6（竹篾编织，外圈最深）   (6,5) 打开的笼屉 6×6（浅笼沿 + 深色竹箅子）
//   (12,0) 笼盖中间鼓起的盖顶侧面 4×1
{
  const c = makeCanvas(16, 16);
  rows(c, 0, 0, [
    'ddkdddddkddd',
    'shsssshssssh',
    'hhkhhhhhkhhn',
    'sssshssssshs',
    'hkhhhhhkhhhn',
  ], BA);
  rows(c, 0, 5, ['.kkkk.', 'kmdmdk', 'kdmdmk', 'kmdmdk', 'kdmdmk', '.kkkk.'], BA);
  rows(c, 6, 5, ['.ssss.', 'skkkks', 'sdddds', 'skkkks', 'sdddds', '.ssss.'], BA);
  rows(c, 12, 0, ['mdmk'], BA);
  write(c, T('steamer'));
}

// 酱坛（图集）：坛脚 3 → 坛腹 4 → 坛肩 3 → 扎口 2，侧面看是往上收口的坛子
//   (0,0) 坛腹正面 4×2（一张红纸斗方 + 一点金字）   (4,0) 坛腹其余三面 4×2   (8,0) 坛腹顶 4×4（只露外圈半格）
//   (0,2) 坛肩侧面 3×1（红布蒙住坛口、垂到坛肩）   (4,2) 坛肩顶 3×3（只露外圈半格）
//   (0,3) 坛脚侧面 3×1（不上釉，露黄泥色胎）   (0,4) 扎口侧面 2×1（麻绳）   (4,5) 扎口顶 2×2（红布鼓起来）
{
  const c = makeCanvas(16, 16);
  const p = { ...GZ, R: RD.m, r: RD.d, L: RD.l, Y: BA.h, t: HEMP, C: CL.l, M: CL.m, E: CL.d };
  rows(c, 0, 0, ['lRRm', 'hRYd'], p);
  rows(c, 4, 0, ['hlmm', 'lmmd'], p);
  rows(c, 8, 0, ['lhhl', 'hkkh', 'hkkh', 'lhhl'], p);
  rows(c, 0, 2, ['LRr'], p);
  rows(c, 4, 2, ['LRR', 'RRr', 'Rrr'], p);
  rows(c, 0, 3, ['CME'], p);
  rows(c, 0, 4, ['tt'], p);
  rows(c, 4, 5, ['LR', 'Rr'], p);
  write(c, T('jar'));
}

// 工作中的吃食（图集）：
//   (0,0) 青花碗身侧面 4×2（白釉口沿 + 一道青花带）   (4,0) 碗足 2×1   (0,2) 碗口顶 4×4   (4,2) 碗底 4×4
//   (8,2) 拍黄瓜堆顶 2×2   (8,4) 黄瓜堆侧 2×1
//   (0,6) 菜墩上的葱花胡萝卜堆顶 4×2   (0,8) 堆的前后面 4×1   (4,8) 堆的左右面 2×1
//   (8,6) 包子顶 2×2（收口褶子）   (8,8) 包子侧 2×1
{
  const c = makeCanvas(16, 16);
  const pb = { q: PO.l, p: PO.m, s: PO.s, i: IN.d, j: IN.m, k: IN.l, G: VG.G, g: VG.g };
  rows(c, 0, 0, ['qpqq', 'jkji'], pb);
  rows(c, 4, 0, ['ii'], pb);
  rows(c, 0, 2, ['qqqq', 'qGgq', 'qgGq', 'qqqq'], pb);
  rows(c, 4, 2, ['ssss', 'sjjs', 'sjjs', 'ssss'], pb);
  rows(c, 8, 2, ['Gp', 'pg'], VG);
  rows(c, 8, 4, ['Gg'], VG);
  rows(c, 0, 6, ['gyOw', 'woGy'], VG);
  rows(c, 0, 8, ['gGoy'], VG);
  rows(c, 4, 8, ['yg'], VG);
  rows(c, 8, 6, ['lm', 'ms'], BUN);
  rows(c, 8, 8, ['ml'], BUN);
  write(c, T('food'));
}

// ---------------------------------------------------------------- models
const f = (texture, uv, extra = {}) => ({ uv, texture, ...extra });
const box = (name, from, to, faces, rotation) => (rotation ? { name, from, to, rotation, faces } : { name, from, to, faces });

// 柜体：7 个元素（砖砌台基、砖砌柜身、两根前砖垛、对开门、门钮、台面板）
const cabinet = () => [
  box('plinth', [0, 0, 0], [16, 3, 16], {
    north: f('#base', [0, 13, 16, 16], { cullface: 'north' }),
    // 两侧和背面接着柜身侧墙往下画（侧面贴图第 13–15 行），uv 方向和柜身一致：第 c 列仍对 z=c（背面对 x）
    south: f('#side', [0, 13, 16, 16], { cullface: 'south' }),
    east: f('#side', [16, 13, 0, 16], { cullface: 'east' }),
    west: f('#side', [0, 13, 16, 16], { cullface: 'west' }),
    up: f('#base', [0, 0, 16, 16]),
    down: f('#base', [0, 0, 16, 16], { cullface: 'down' }),
  }),
  box('carcass', [0, 3, 2], [16, 14, 16], {
    east: f('#side', [16, 2, 2, 13], { cullface: 'east' }),   // 反着取，第 c 列仍对 z=c
    west: f('#side', [2, 2, 16, 13], { cullface: 'west' }),
    south: f('#side', [0, 2, 16, 13], { cullface: 'south' }),
  }),
  box('post east', [15, 3, 1], [16, 14, 2], {
    north: f('#front', [0, 2, 1, 13]),
    east: f('#side', [1, 2, 2, 13], { cullface: 'east' }),
    west: f('#side', [1, 2, 2, 13]),
  }),
  box('post west', [0, 3, 1], [1, 14, 2], {
    north: f('#front', [15, 2, 16, 13]),
    west: f('#side', [1, 2, 2, 13], { cullface: 'west' }),
    east: f('#side', [1, 2, 2, 13]),
  }),
  box('doors', [1, 3, 1.5], [15, 14, 2], { north: f('#front', [1, 2, 15, 13]) }),
  box('door pull', [7.5, 8, 1], [8.5, 9, 1.5], {
    north: f('#front', [7.5, 7, 8.5, 8]),
    east: f('#front', [7, 7, 7.5, 8]), west: f('#front', [8, 7, 8.5, 8]),
    up: f('#front', [7.5, 6, 8.5, 6.5]), down: f('#front', [7.5, 7.5, 8.5, 8]),
  }),
  box('worktop', [0, 14, 0], [16, 16, 16], {
    up: f('#top', [0, 0, 16, 16], { cullface: 'up' }),
    north: f('#front', [0, 0, 16, 2], { cullface: 'north' }),
    south: f('#front', [16, 0, 0, 2], { cullface: 'south' }),   // 后沿和前沿一样是顺纹，不用侧面的端头拼缝
    east: f('#side', [16, 0, 0, 2], { cullface: 'east' }),
    west: f('#side', [0, 0, 16, 2], { cullface: 'west' }),
    down: f('#top', [0, 0, 16, 16]),   // 只有前面挑出的 1px 看得见，1:1 取样
  }),
];

// 菜墩：台面正中偏前，x 4–10、z 2–8、y 16–19，三个元素拼成切角的圆墩
// covered = 工作中前沿那一条顶面被菜堆、后沿那一条被平放的刀整个盖住，不画
const blockEls = (covered = false) => [
  box('block core', [4, 16, 3], [10, 19, 7], {
    up: f('#block', [0, 1, 6, 5]),
    north: f('#block', [0, 8, 6, 11]), south: f('#block', [6, 8, 12, 11]),
    east: f('#block', [0, 8, 4, 11]), west: f('#block', [4, 8, 8, 11]),
  }),
  box('block front', [5, 16, 2], [9, 19, 3], {
    ...(covered ? {} : { up: f('#block', [1, 0, 5, 1]) }),
    north: f('#block', [8, 8, 12, 11]),
    east: f('#block', [12, 8, 13, 11]), west: f('#block', [13, 8, 14, 11]),
  }),
  box('block back', [5, 16, 7], [9, 19, 8], {
    ...(covered ? {} : { up: f('#block', [1, 5, 5, 6]) }),   // 工作中被平放的刀盖住
    south: f('#block', [2, 8, 6, 11]),
    east: f('#block', [14, 8, 15, 11]), west: f('#block', [15, 8, 16, 11]),
  }),
];

// 菜刀柄（沿 x 放，铁箍在东端接刀根，柄尾朝西 = 正面看右手边）
const handle = (from, to) => box('cleaver handle', from, to, {
  north: f('#knife', [8, 0, 11, 1]), south: f('#knife', [11, 0, 8, 1]),
  up: f('#knife', [11, 1, 8, 2]), down: f('#knife', [11, 1, 8, 2]),
  west: f('#knife', [11, 0, 12, 1]), east: f('#knife', [8, 0, 9, 1]),
});
// 工作中：菜刀平放在菜墩后半，刃口朝前挨着切好的菜，刀背和柄在后，柄伸出菜墩西沿；刀面朝上，高光动画俯视、斜视都看得见
const cleaverFlat = () => [
  box('cleaver blade', [4.5, 19, 4], [9.5, 19.5, 8], {
    up: f('#knife', [5, 4, 0, 0]),
    down: f('#knife', [5, 0, 0, 4]),   // 刀身两角挑在菜墩切角外 0.5px，低角度能看见底面
    north: f('#knife', [0, 3, 5, 3.5]), south: f('#knife', [5, 4, 0, 4.5]),
    east: f('#knife', [0, 4, 4, 4.5]), west: f('#knife', [0, 4, 4, 4.5]),
  }),
  handle([1.5, 19, 7], [4.5, 20, 8]),
];
// 待机：菜刀剁在干净的菜墩正中立着（收工时的放法），刀面朝正面，前后都露出年轮
const cleaverUp = () => [
  box('cleaver blade', [4.5, 18, 4.75], [9.5, 22, 5.25], {
    north: f('#knife', [0, 0, 5, 4]), south: f('#knife', [5, 0, 0, 4]),
    up: f('#knife', [0, 4, 5, 4.5]),
    east: f('#knife', [5, 0, 5.5, 4]), west: f('#knife', [5, 0, 5.5, 4]),
  }),
  handle([1.5, 21, 4.5], [4.5, 22, 5.5]),
];

// 竹蒸笼：左后（x 9–15、z 9–15），三个元素拼成切角的圆笼。
//   待机：两层笼屉 + 盖子一共 5px 高（y16–21），顶面是笼盖；工作中：笼盖掀开，笼身 4px（y16–20），顶面是打开的笼屉
const steamer = open => {
  const top = open ? 20 : 21, v0 = open ? 1 : 0, du = open ? 6 : 0;
  return [
    box('steamer core', [9, 16, 10], [15, top, 14], {
      up: f('#steamer', [du, 6, du + 6, 10]),
      north: f('#steamer', [0, v0, 6, 5]), south: f('#steamer', [6, v0, 12, 5]),
      east: f('#steamer', [2, v0, 6, 5]), west: f('#steamer', [8, v0, 12, 5]),
    }),
    box('steamer front', [10, 16, 9], [14, top, 10], {
      up: f('#steamer', [du + 1, 5, du + 5, 6]),
      north: f('#steamer', [1, v0, 5, 5]),
      east: f('#steamer', [0, v0, 1, 5]), west: f('#steamer', [5, v0, 6, 5]),
    }),
    box('steamer back', [10, 16, 14], [14, top, 15], {
      up: f('#steamer', [du + 1, 10, du + 5, 11]),
      south: f('#steamer', [7, v0, 11, 5]),
      east: f('#steamer', [6, v0, 7, 5]), west: f('#steamer', [11, v0, 12, 5]),
    }),
  ];
};
// 笼盖中间鼓起的一块（4×1×4），待机平放在笼盖上，工作中跟着笼盖一起翘
const domeEl = rot => box('lid dome', [10, 21, 10], [14, 22, 14], {
  up: f('#steamer', [1, 6, 5, 10]),
  north: f('#steamer', [12, 0, 16, 1]), south: f('#steamer', [12, 0, 16, 1]),
  east: f('#steamer', [12, 0, 16, 1]), west: f('#steamer', [12, 0, 16, 1]),
}, rot);
// 工作中掀开的笼盖：铰在东沿（x=15, y=20），西沿翘起 22.5°，正面和斜视都能从西边的缝里看见包子
const LID_ROT = { origin: [15, 20, 12], axis: 'z', angle: -22.5 };
const lidEls = () => [
  box('lid core', [9, 20, 10], [15, 21, 14], {
    up: f('#steamer', [0, 6, 6, 10]), down: f('#steamer', [0, 6, 6, 10]),
    north: f('#steamer', [0, 0, 6, 1]), south: f('#steamer', [6, 0, 12, 1]),
    east: f('#steamer', [2, 0, 6, 1]), west: f('#steamer', [8, 0, 12, 1]),
  }, LID_ROT),
  box('lid front', [10, 20, 9], [14, 21, 10], {
    up: f('#steamer', [1, 5, 5, 6]), down: f('#steamer', [1, 5, 5, 6]),
    north: f('#steamer', [1, 0, 5, 1]),
    east: f('#steamer', [0, 0, 1, 1]), west: f('#steamer', [5, 0, 6, 1]),
  }, LID_ROT),
  box('lid back', [10, 20, 14], [14, 21, 15], {
    up: f('#steamer', [1, 10, 5, 11]), down: f('#steamer', [1, 10, 5, 11]),
    south: f('#steamer', [7, 0, 11, 1]),
    east: f('#steamer', [6, 0, 7, 1]), west: f('#steamer', [11, 0, 12, 1]),
  }, LID_ROT),
  domeEl(LID_ROT),
];
// 包子：两只，挨着笼盖翘起的西边，盖子下沿在 x≤12 处高于 y21，不会穿模
const bun = (name, from, to) => box(name, from, to, {
  up: f('#food', [8, 6, 10, 8]),
  north: f('#food', [8, 8, 10, 9]), south: f('#food', [8, 8, 10, 9]),
  east: f('#food', [8, 8, 10, 9]), west: f('#food', [8, 8, 10, 9]),
});

// 酱坛：右后（x 2–6、z 10–14），坛脚 3×1×3 → 坛腹 4×2×4 → 坛肩 3×1×3（红布搭在肩上）→ 麻绳扎口 2×1×2
const jarEls = () => [
  box('jar foot', [2.5, 16, 10.5], [5.5, 17, 13.5], {
    north: f('#jar', [0, 3, 3, 4]), south: f('#jar', [0, 3, 3, 4]), east: f('#jar', [0, 3, 3, 4]), west: f('#jar', [0, 3, 3, 4]),
  }),
  box('jar belly', [2, 17, 10], [6, 19, 14], {
    north: f('#jar', [0, 0, 4, 2]),
    south: f('#jar', [4, 0, 8, 2]), east: f('#jar', [4, 0, 8, 2]), west: f('#jar', [4, 0, 8, 2]),
    up: f('#jar', [8, 0, 12, 4]), down: f('#jar', [8, 0, 12, 4]),
  }),
  box('jar shoulder', [2.5, 19, 10.5], [5.5, 20, 13.5], {
    up: f('#jar', [4, 2, 7, 5]),
    north: f('#jar', [0, 2, 3, 3]), south: f('#jar', [0, 2, 3, 3]), east: f('#jar', [0, 2, 3, 3]), west: f('#jar', [0, 2, 3, 3]),
  }),
  box('jar tie', [3, 20, 11], [5, 21, 13], {
    up: f('#jar', [4, 5, 6, 7]),
    north: f('#jar', [0, 4, 2, 5]), south: f('#jar', [0, 4, 2, 5]), east: f('#jar', [0, 4, 2, 5]), west: f('#jar', [0, 4, 2, 5]),
  }),
];

const textures = {
  particle: ID('side'),
  side: ID('side'), front: ID('front'), base: ID('base'), top: ID('top'),
  block: ID('block'), knife: ID('knife'), steamer: ID('steamer'), jar: ID('jar'),
};

// ---- 待机：菜墩干净、菜刀剁在上面立着；蒸笼盖着；酱坛封着。台面上只有三样东西
const idle = [
  ...cabinet(),
  ...blockEls(),
  ...cleaverUp(),
  ...steamer(false),
  domeEl(),
  ...jarEls(),
];

// ---- 工作中：菜墩上一堆切好的葱花胡萝卜、菜刀放平；蒸笼掀盖露出包子；左前多一碗拍黄瓜
const active = [
  ...cabinet(),
  ...blockEls(true),
  ...cleaverFlat(),
  box('chopped veg', [5, 19, 2], [9, 20, 4], {
    up: f('#food', [0, 6, 4, 8]),
    north: f('#food', [0, 8, 4, 9]), south: f('#food', [0, 8, 4, 9]),
    east: f('#food', [4, 8, 6, 9]), west: f('#food', [4, 8, 6, 9]),
  }),
  ...steamer(true),
  ...lidEls(),
  bun('bun a', [10, 20, 10], [12, 21, 12]),
  bun('bun b', [10, 20, 12.5], [12, 21, 14.5]),
  ...jarEls(),
  box('bowl foot', [12, 16, 3], [14, 17, 5], {
    north: f('#food', [4, 0, 6, 1]), south: f('#food', [4, 0, 6, 1]), east: f('#food', [4, 0, 6, 1]), west: f('#food', [4, 0, 6, 1]),
  }),
  box('bowl', [11, 17, 2], [15, 19, 6], {
    north: f('#food', [0, 0, 4, 2]), south: f('#food', [0, 0, 4, 2]), east: f('#food', [0, 0, 4, 2]), west: f('#food', [0, 0, 4, 2]),
    up: f('#food', [0, 2, 4, 6]), down: f('#food', [4, 2, 8, 6]),
  }),
  box('cucumber', [12, 19, 3], [14, 20, 5], {
    up: f('#food', [8, 2, 10, 4]),
    north: f('#food', [8, 4, 10, 5]), south: f('#food', [8, 4, 10, 5]), east: f('#food', [8, 4, 10, 5]), west: f('#food', [8, 4, 10, 5]),
  }),
];

// ambientocclusion false：和炸锅 C、吊炉一样两个模型都写（见 meta notes）
json(`models/block/${KEY}.json`, {
  parent: 'minecraft:block/block',
  ambientocclusion: false,
  render_type: 'minecraft:cutout',
  textures,
  elements: idle,
});
json(`models/block/${KEY}_on.json`, {
  parent: ID(''),
  ambientocclusion: false,
  render_type: 'minecraft:cutout',
  textures: { knife: ID('knife_on'), food: ID('food') },
  elements: active,
});
// 物品形态：台面上的东西顶到 y22，按 block/block 的默认显示会顶出物品栏格子上沿，缩小一点、往下挪
json(`models/item/${KEY}.json`, {
  parent: ID(''),
  display: {
    gui: { rotation: [30, 225, 0], translation: [0, -1.25, 0], scale: [0.55, 0.55, 0.55] },
    ground: { rotation: [0, 0, 0], translation: [0, 2.5, 0], scale: [0.25, 0.25, 0.25] },
    fixed: { rotation: [0, 0, 0], translation: [0, -1, 0], scale: [0.45, 0.45, 0.45] },
    thirdperson_righthand: { rotation: [75, 45, 0], translation: [0, 2, 0], scale: [0.35, 0.35, 0.35] },
    firstperson_righthand: { rotation: [0, 45, 0], translation: [0, -1, 0], scale: [0.36, 0.36, 0.36] },
    firstperson_lefthand: { rotation: [0, 225, 0], translation: [0, -1, 0], scale: [0.36, 0.36, 0.36] },
  },
});

json('meta.json', {
  key: KEY,
  tag: '备餐台 C',
  order: 23,
  name: '中式案台',
  tagline: '砖砌案台、黄泥压顶、枣红漆对开木门：台面上一只箍铁圈的圆菜墩配方头菜刀、一摞竹蒸笼、一只红布封口的酱坛，和中式炸灶、吊炉成套。',
  description: '整格方块，台面和农夫乐事炉灶、橱柜顶齐平（y=16）。柜身两侧和背面是和炸灶灶身同一种砖砌（同砖色、同砂浆缝、同一种错缝，' +
    '三皮砖到底），砖墙顶上抹一道 2px 黄泥压顶（和炸灶灶沿同样上亮下暗），门前一道 3px 高的砖台阶、台阶面抹黄泥；' +
    '正面两根 1px 砖垛夹着一对枣红漆对开木门（漆色取炸灶余火的暗红和吊炉烤鸭的红），上半方格窗棂、下半木板芯，合缝处一块黄铜面叶和一个门钮；' +
    '台面是 2px 厚的三拼木案板，往前挑出 1px。' +
    '台面上只放三样：正中偏前一只 6×3×6 的圆菜墩（端面年轮，腰上一道黑铁箍），一把方头菜刀剁在正中立着、柄朝右手边；' +
    '左后一摞两层竹蒸笼，浅色笼屉上扣一只深色鼓顶笼盖（竹色和炸灶笊篱的竹柄同一套）；右后一只酱釉坛子，贴红纸斗方，坛口蒙红布、麻绳扎口。' +
    '工作中：菜墩前半堆着切好的葱花和胡萝卜丁，菜刀放平在后半，刃口一道高光从刀根滑到刀尖；' +
    '蒸笼盖掀起 22.5°（铰在东沿），从翘起的西边能看见两只白包子；左前多出一只青花碗，盛着拍黄瓜。',
  notes: [
    '不需要热源，放哪都能用。台面高度和农夫乐事炉灶、橱柜、备餐台 A 一样，可以接成一排厨房台面。台面上的东西：待机最高到 y=22（蒸笼盖顶、立着的菜刀），工作中最高约 y=23.8（掀开的笼盖西沿的鼓顶）。',
    `待机 / 工作中两个模型：工作中模型 parent 待机模型（继承贴图），把 knife 换成动画版、多一张 food；菜刀换姿势、蒸笼掀盖、加菜，所以元素表整份重写（原版子模型的 elements 会整份覆盖父模型）。柜体 7 个元素两边完全一样，切换时只有台面上的东西变。元素：待机 ${idle.length} 个，工作中 ${active.length} 个（笼盖 4 个元素一起绕东沿转 -22.5°，是唯一的旋转）。`,
    '动画：prep_c_knife_on 6 帧（5 帧 × 2 tick 高光从刀根滑到刀尖，第 6 帧停 40 tick，一圈 2.5 秒），在工作中平放的刀面上，俯视和斜视看得见。蒸笼冒热气用粒子另加（从笼盖翘起的西沿 (10,21,12) 附近往上飘）。',
    '贴图 10 张全部 16×16，render_type cutout（菜墩、蒸笼切角处的空像素只在图集里，面上没有镂空；写 cutout 是为了以后换镂空零件不出黑块）。两个方块模型都写了 render_type，不靠继承。全部是整数或 0.5 的尺寸，每个面的 UV 和面一样大（1 像素 = 1 格）。',
    '和 C 家族成套：砖墙、砂浆缝、黄泥压顶的写法和颜色逐行照炸锅 C 灶身侧面（炸灶 / 吊炉 / 案台三件的砖色、砂浆色、黄泥五档完全一样，黄泥最亮只到 #c5aa80）；门漆不另起红色，三档全取自家族已有色（#4a2116 炸灶炭膛暗褐、#8a3311 炸灶余火暗红、#a8442a 吊炉烤鸭的红），酱坛红布同色；菜墩铁箍、菜刀用农夫乐事锅具的黑铁灰（对应炸灶的铁锅），刀上磨亮的一条用炸灶笊篱网丝的亮色 #999897；竹蒸笼和炸灶笊篱竹柄同一套竹色。',
    '朝向：正面（对开门）画在 north，facing=north 时 y=0，east 90，south 180，west 270。菜墩在正中偏前，蒸笼在东侧后方（正面看左后），酱坛在西侧后方（右后），工作中的青花碗在东侧前方（左前）；刀柄始终朝西（正面看右手边）。',
    '碰撞箱：整格 0–16，台面上的东西不进碰撞箱（和农夫乐事砧板上放东西一样）。选择框也建议整格；要更贴就用几个小盒的并集，不要整层平板——待机：菜墩加刀 [1,16,2]-[10,22,8]、蒸笼 [9,16,9]-[15,22,15]、酱坛 [2,16,10]-[6,21,14]；工作中：菜墩加刀 [1,16,2]-[10,20,8]、蒸笼加盖 [9,16,9]-[16,24,15]、酱坛同上、碗 [11,16,2]-[15,20,6]。',
    '方块要 noOcclusion()：对开门缩进 1.5px，台面上的摆件也不在方块边界上，按满方块不透光处理的话游戏会用 0 级光照把它们画黑。两个模型都写了 "ambientocclusion": false（和炸锅 C、吊炉一样）：台面小件、门缝不会被 AO 压出黑斑，游戏里和预览看到的一致；三件 C 家族挨着摆时墙根、转角的明暗写法也统一。',
    '物品模型 models/item/prep_c.json：parent 待机模型，gui 缩到 0.55、往下挪 1.25px，免得台面上的东西顶出物品栏格子；手持、展示框同样缩小。只是建议值，预览工具画不了物品形态，要进游戏看。',
  ],
  hires: false,
  namespace: NS,
  blocks: [
    { label: '中式案台', pos: [0, 0, 0], idle: { model: KEY, y: 0 }, active: { model: `${KEY}_on`, y: 0 } },
  ],
});
console.log(`prep_c written: idle ${idle.length} elements, active ${active.length} elements`);
