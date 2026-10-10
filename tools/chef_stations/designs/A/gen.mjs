// 方案 A「炉上油锅」v2 —— 生成模型 JSON + 16×16 贴图 + meta.json（+ 物品模型、blockstate 草稿）。
//   cd <仓库>\tools\chef_stations
//   node designs/A/gen.mjs
// 所有贴图都在这里逐像素画（lib/png.mjs），改了重跑即可；产物结构和主 MOD 的 assets/miningdim/ 一致。
//
// v2 按审查意见改：
//  - 油面两种状态都在 y7（比锅沿低 1px）；工作中炸篮 y6..9，篮口比锅沿高 1px；木柄改水平，钢杆平搭在前锅沿上。
//  - 待机炸篮挂到后锅沿沥油（z7..14、篮底 y9，两只钢钩跨过后锅沿），前面 4px 油面露出来；木柄水平前伸。
//  - 翻泡贴图重画：底色和待机同为 #cf944c，气泡在原地走“点 → 亮点 → 空心圈 → 淡圈 → 破”，8 帧 × 3 tick。
//  - 温度计缩成 2×7 挪到正面右角，暗黄铜 + 1px 红球泡，补背面；红柱只升到橙色刻度。
//  - 锅耳改成和锅沿齐平的环形耳（横梁 + 两根立柱，中间透空），两端铜色端帽；钢杆改成 U 形双股钢丝。
//  - 侧面贴图：锅沿连续受光线、去掉孤立亮斑；空白区填满铸铁色（破坏粒子不再透明）。
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { makeCanvas, fillRect, setPx, getPx, strip, write } from '../../lib/png.mjs';

const dir = path.dirname(fileURLToPath(import.meta.url));
const NS = 'miningdim';
const ID = n => `${NS}:block/${n}`;
const T = n => path.join(dir, 'textures/block', n + '.png');
const json = (rel, o) => { const f = path.join(dir, rel); fs.mkdirSync(path.dirname(f), { recursive: true }); fs.writeFileSync(f, JSON.stringify(o, null, 2) + '\n'); };
/** draw rows of single-char colour keys; '.' = leave transparent */
const rows = (c, x0, y0, lines, pal) => lines.forEach((ln, j) => [...ln].forEach((ch, i) => { if (ch !== '.' && ch !== ' ') setPx(c, x0 + i, y0 + j, pal[ch]); }));

// ---------------------------------------------------------------- palette
// 农夫乐事锅具的冷灰梯度（refs/palette.json），铸铁整体压暗一档
const G = ['#1f1f21', '#27272b', '#2d2d32', '#343438', '#3f3e42', '#494848', '#4f4f4f', '#595858', '#5c5c5c', '#656565', '#676161', '#727272'];
// 热油：随性乐事的油色 #cf944c / #e0b05b 为中间两档，上下各补一档。两种状态的底色都是 m
const OIL = { d: '#8a5520', s: '#b0762f', m: '#cf944c', l: '#e0b05b', h: '#f6d78e' };
// 炸篮钢丝（比锅亮，和铸铁拉开）
const W = { d: '#5c5c5c', m: '#868584', l: '#a6a4a2', h: '#c3c1bf' };
// 木柄：农夫乐事木勺的三档
const WD = { l: '#86683c', m: '#6d5736', d: '#55452e' };
// 温度计：暗黄铜为主（#7f7036），农夫乐事篮子的金黄 #bba84c/#d3bb50 只做顶端高光
const BR = { k: '#4f4626', d: '#665a2c', m: '#7f7036', l: '#bba84c', h: '#d3bb50' };
const RED = { m: '#c63d27', l: '#ee6a43' };
const MARK = '#ed8c0e';   // 农夫乐事炉火的橙色，用作“油炸温度”刻度
// 农夫乐事厨锅把手上的铜色（cooking_pot_parts），用作锅耳端帽
const CU = { m: '#9b5643', d: '#733f31' };

// ---------------------------------------------------------------- geometry
const RIM = 8;     // 锅沿顶面
const OIL_Y = 7;   // 油面：两种状态都比锅沿低 1px
// 待机炸篮：挂在后锅沿沥油
const IDLE = { y0: 9, y1: 13, z0: 7 };   // x 4..12, z 7..14, y 9..13（篮底比锅沿高 1px，后端压在后锅沿上方）
// 工作中炸篮：沉进油里，篮口比锅沿高 1px
const ACTIVE = { y0: 6, y1: 9, z0: 5 };  // x 4..12, z 5..12, y 6..9（y7 以下在油里）
const GRIP_Z = [-2, 1];                  // 木握把在两种状态下 z 位置相同，只换高度

// ---------------------------------------------------------------- textures
// 锅外壁：默认 UV，墙 y1..8 → 第 8..14 行，锅脚 y0..1 → 第 15 行；列 1..14 = 宽 14 的锅身（四面通用）
// 其余没被模型用到的像素也填满铸铁色：particle 用这张图，破坏/奔跑粒子不会再取到透明块
{
  const c = makeCanvas(16, 16);
  const p = { a: G[2], b: G[3], c: G[4], d: G[5], e: G[6], f: G[7] };
  rows(c, 1, 8, [
    'effffffeffffff',   // 8  卷边顶：连续的 #595858 受光线，只断 1 处
    'bbbcbbbbbbbcbb',   // 9  卷边下的阴影槽
    'dddddddedddddd',   // 10
    'cdddddefddddcd',   // 11 斜向铸铁反光（3 行 × 2px，代替原来的孤立亮斑）
    'cddddefddddddc',   // 12
    'ccdcdedddcddcc',   // 13
    'bcccccbccccccb',   // 14 锅底阴影
  ], p);
  rows(c, 2, 15, ['aabaaaaaabaa'], p);   // 15 锅脚（内收 1px）
  for (let y = 0; y < 16; y++) for (let x = 0; x < 16; x++)
    if (!getPx(c, x, y)[3]) setPx(c, x, y, y === 15 ? G[2] : getPx(c, 1 + ((x + 13) % 14), 10 + (y % 4)));
  write(c, T('fryer_a_side'));
}
// 锅内壁（油面以上只露 1 行：y 7..8 → 第 8 行）
{
  const c = makeCanvas(16, 16);
  fillRect(c, 1, 8, 14, 1, G[3]);
  fillRect(c, 1, 9, 14, 1, G[2]);
  fillRect(c, 1, 10, 14, 6, G[1]);
  for (const x of [4, 9, 12]) setPx(c, x, 8, G[4]);
  write(c, T('fryer_a_inner'));
}
// 锅沿（墙的顶面，默认 UV）：外圈 1..14 受光，内圈 2..13 偏暗；中间不用
{
  const c = makeCanvas(16, 16);
  const p = { a: G[4], b: G[5], c: G[6], d: G[7], e: G[10], f: G[11] };
  const outer = 'cddeeffeeddddc', inner = 'abbbaabbbbab';
  for (let i = 0; i < 14; i++) {
    setPx(c, 1 + i, 1, p[outer[i]]); setPx(c, 1 + i, 14, p[outer[13 - i]]);
    setPx(c, 1, 1 + i, p[outer[i]]); setPx(c, 14, 1 + i, p[outer[13 - i]]);
  }
  for (let i = 0; i < 12; i++) {
    setPx(c, 2 + i, 2, p[inner[i]]); setPx(c, 2 + i, 13, p[inner[11 - i]]);
    setPx(c, 2, 2 + i, p[inner[i]]); setPx(c, 13, 2 + i, p[inner[11 - i]]);
  }
  write(c, T('fryer_a_top'));
}
// 锅底（锅脚 2..13 + 墙底面外圈 1/14）
{
  const c = makeCanvas(16, 16);
  fillRect(c, 1, 1, 14, 14, G[3]);
  fillRect(c, 2, 2, 12, 12, G[2]);
  fillRect(c, 4, 4, 8, 8, G[1]);
  for (const [x, y] of [[5, 6], [9, 5], [7, 9], [10, 10], [4, 11]]) setPx(c, x, y, G[2]);
  write(c, T('fryer_a_bottom'));
}

// 油面（元素 [3..13]×[3..13] 的顶面，默认 UV → 像素 3..12）。
// 顶面贴图的第 3 行是锅的正面（北）一侧。两种状态同一个底：#cf944c，外圈 1px #8a5520 弯月面，内侧一圈零星 #b0762f。
function oilBase() {
  const c = makeCanvas(16, 16);
  fillRect(c, 3, 3, 10, 10, OIL.m);
  for (let i = 3; i <= 12; i++) { setPx(c, i, 3, OIL.d); setPx(c, i, 12, OIL.d); setPx(c, 3, i, OIL.d); setPx(c, 12, i, OIL.d); }
  for (const [x, y] of [[4, 4], [5, 4], [9, 4], [11, 4], [4, 7], [11, 6], [11, 9], [4, 10], [6, 11], [10, 11]]) setPx(c, x, y, OIL.s);
  return c;
}
// 待机：静止的热油。待机炸篮挂在后面（贴图第 7..12 行上方），前面第 4..6 行露出来：
// 在露出的前半区画两道斜向镜面高光（#f6d78e 3px + 旁边 1px #e0b05b 过渡），篮子底下再留一小道，从网眼和俯视能看到
{
  const c = oilBase();
  const hl = [
    [7, 4, 'h'], [6, 5, 'h'], [5, 6, 'h'], [8, 4, 'l'], [7, 5, 'l'], [4, 6, 'l'],   // 长高光
    [10, 5, 'h'], [9, 6, 'h'], [10, 6, 'l'],                                        // 短高光
    [8, 9, 'l'], [7, 10, 'l'],                                                      // 篮底下的一点反光
  ];
  for (const [x, y, k] of hl) setPx(c, x, y, OIL[k]);
  write(c, T('fryer_a_oil'));
}
// 工作中：8 帧 × 3 tick（一圈 1.2 秒）。
//  - 4 个大气泡位置固定、互不重叠，各自在原地走 5 帧：点(l) → 亮点(h) → 3×3 空心圈(h，中心留油色) → 淡圈(l，缺上下) → 破(四角 l)
//  - 3 个小泡：点(l) → 亮点(h) → 点(l)
//  - 炸篮内壁 1px 一圈油花：每个像素按固定相位亮 2 帧（#e0b05b），不随机跳
//  每帧亮像素约 15–20 个（油面 100 px）
{
  const rings = [[6, 7, 0], [9, 9, 2], [6, 10, 4], [9, 6, 6]];
  const smalls = [[5, 4, 1], [10, 4, 5], [8, 8, 3]];
  const foam = [];
  for (let y = 5; y <= 11; y++) foam.push([4, y], [11, y]);
  for (let x = 5; x <= 10; x++) foam.push([x, 5], [x, 11]);
  // 相位：0..7 各用 3 次左右，打散顺序（固定种子，重跑结果不变），避免某一帧扎堆亮
  let seed = 11; const rnd = n => ((seed = (seed * 1103515245 + 12345) & 0x7fffffff) >> 16) % n;
  const pool = foam.map((_, i) => i % 8);
  for (let i = pool.length - 1; i > 0; i--) { const j = rnd(i + 1); [pool[i], pool[j]] = [pool[j], pool[i]]; }
  const foamPh = pool;
  const put = (c, x, y, k) => { if (x >= 3 && x <= 12 && y >= 3 && y <= 12) setPx(c, x, y, OIL[k]); };
  const N = 8, frames = [];
  for (let f = 0; f < N; f++) {
    const c = oilBase();
    foam.forEach(([x, y], i) => { if ((f - foamPh[i] + N) % N < 2) put(c, x, y, 'l'); });
    for (const [x, y, ph] of smalls) {
      const s = (f - ph + N) % N;
      if (s === 0 || s === 2) put(c, x, y, 'l'); else if (s === 1) put(c, x, y, 'h');
    }
    for (const [x, y, ph] of rings) {
      const s = (f - ph + N) % N;
      if (s === 0) put(c, x, y, 'l');
      else if (s === 1) put(c, x, y, 'h');
      else if (s === 2) { for (const [dx, dy] of [[-1, -1], [0, -1], [1, -1], [-1, 0], [1, 0], [-1, 1], [0, 1], [1, 1]]) put(c, x + dx, y + dy, 'h'); put(c, x, y, 'm'); }
      else if (s === 3) { for (const [dx, dy] of [[-1, -1], [1, -1], [-1, 0], [1, 0], [-1, 1], [1, 1]]) put(c, x + dx, y + dy, 'l'); }
      else if (s === 4) { for (const [dx, dy] of [[-1, -1], [1, -1], [-1, 1], [1, 1]]) put(c, x + dx, y + dy, 'l'); }
    }
    frames.push(c);
  }
  write(strip(frames), T('fryer_a_oil_on'));
  json('textures/block/fryer_a_oil_on.png.mcmeta', { animation: { frametime: 3 } });
}

// 炸篮钢丝网（cutout）：前后网 8×4 在 (0,0)，左右网 7×4 在 (8,0)，底网 8×7 在 (0,8)
// 网格是竖丝 + 2px 高的缝；工作中只用上 3 行（篮口 + 一行缝），待机用全 4 行
{
  const c = makeCanvas(16, 16);
  const p = { d: W.d, m: W.m, l: W.l, h: W.h };
  rows(c, 0, 0, ['lhhhhhhl', 'm.m.m.m.', 'm.m.m.m.', 'dmdmdmdm'], p);
  rows(c, 8, 0, ['lhhhhhl', 'm.m.m.m', 'm.m.m.m', 'dmdmdmd'], p);
  rows(c, 0, 8, ['mmmmmmmm', 'm.m.m.m.', 'mmmmmmmm', 'm.m.m.m.', 'mmmmmmmm', 'm.m.m.m.', 'mmmmmmmm'], p);
  write(c, T('fryer_a_basket'));
}

// 零件图集
const P = {
  gripSide: [0, 0, 3, 2], gripSideM: [3, 0, 0, 2], gripTop: [4, 0, 6, 3], gripBot: [4, 3, 6, 0], gripEnd: [7, 0, 9, 2],
  rodSide: L => [0, 3, L, 4], rodEnd: [0, 4, 4, 5], rodTop: L => [10, 0, 14, L], rodBot: L => [10, L, 14, 0],
  lugOut: [0, 6, 6, 7], lugTop: [15, 0, 16, 6], lugBot: [14, 0, 15, 6], lugCap: [0, 7, 1, 8], postOut: [1, 7, 2, 8], postSide: [2, 7, 3, 8], postBot: [3, 7, 4, 8],
  clipSide: [0, 8, 3, 9], clipTop: [13, 7, 14, 10], clipEnd: [14, 7, 15, 8],
  probe: [15, 7, 16, 12], probeTop: [15, 12, 16, 13],
  hookSide: [0, 10, 2, 11], hookTop: [3, 10, 4, 12], hookEnd: [0, 10, 1, 11], hookDrop: [5, 10, 6, 12], hookDropBot: [5, 12, 6, 13],
};
{
  const c = makeCanvas(16, 16);
  const p = { L: WD.l, M: WD.m, D: WD.d, s: W.m, t: W.l, h: W.h, d: W.d, a: G[5], b: G[6], e: G[9], f: G[10], g: G[11], C: CU.m, K: CU.d, o: BR.d, n: BR.m, q: BR.l };
  rows(c, 0, 0, ['tLM', 'sMD'], p);                      // 木握把侧面 3×2（第 0 列是靠钢杆的金属箍）
  rows(c, 4, 0, ['LM', 'LM', 'ts'], p);                   // 握把顶面 2×3（第 2 行是箍）
  rows(c, 7, 0, ['LM', 'MD'], p);                         // 握把端面 2×2
  rows(c, 0, 3, ['tttttt'], p);                           // 钢丝侧面 6×1
  rows(c, 0, 4, ['tttt'], p);                             // U 形钢杆端面 4×1
  rows(c, 10, 0, ['tttt', 't..t', 't..t', 't..t', 't..t', 't..t'], p);   // U 形钢杆顶面 4×6：第 0 行横梁（握把一端），下面两股钢丝
  rows(c, 0, 6, ['fggggf'], p);                           // 锅耳横梁外侧 6×1（比锅壁亮两档，侧面看能从暗槽里跳出来）
  rows(c, 15, 0, ['e', 'g', 'g', 'g', 'g', 'e'], p);      // 横梁顶面 1×6（和锅沿一起受光）
  rows(c, 14, 0, ['a', 'a', 'a', 'a', 'a', 'a'], p);      // 横梁底面
  rows(c, 0, 7, ['CfKa'], p);                             // 横梁端帽（铜） / 立柱外侧 / 立柱前后面（暗铜） / 立柱底
  rows(c, 0, 8, ['qnn'], p);                              // 夹子侧面 3×1
  rows(c, 13, 7, ['q', 'n', 'n'], p);                     // 夹子顶面 1×3
  rows(c, 14, 7, ['o'], p);                               // 夹子端面
  rows(c, 15, 7, ['t', 's', 's', 's', 'd'], p);           // 探针 1×5
  rows(c, 15, 12, ['t'], p);                              // 探针顶
  rows(c, 0, 10, ['tt'], p);                              // 挂钩横杆侧面 2×1
  rows(c, 3, 10, ['h', 't'], p);                          // 挂钩横杆顶面 1×2
  rows(c, 5, 10, ['s', 'd', 'd'], p);                     // 挂钩下垂段 1×2 + 底 1×1
  write(c, T('fryer_a_parts'));
}

// 温度计 2×7：正面 (0,0)，背板 (3,0)，侧面 1×7 (6,0)，顶 2×1 (0,8)，底 2×1 (3,8)
// 正面贴图第 0 列 = x 2..3（靠锅中间，玻璃管），第 1 列 = x 1..2（锅角，刻度）
function gauge(hot) {
  const c = makeCanvas(16, 16);
  const p = { k: BR.k, o: BR.d, n: BR.m, q: BR.l, h: BR.h, g: G[5], G: G[9], r: RED.m, R: RED.l, X: MARK };
  const tube = hot ? ['G', 'R', 'r', 'r', 'r'] : ['G', 'g', 'g', 'g', 'g'];   // 工作中红柱顶正好平齐橙色刻度
  rows(c, 0, 0, [
    'hq',                   // 顶帽高光
    tube[0] + 'n',
    tube[1] + 'X',          // 唯一的橙色刻度 = 油炸温度
    tube[2] + 'n',
    tube[3] + 'k',
    tube[4] + 'n',
    'ro',                   // 球泡：只有 1px 红
  ], p);
  rows(c, 3, 0, ['qn', 'nn', 'nn', 'no', 'nn', 'no', 'oo'], p);   // 背板（从后面看不再是空的）
  rows(c, 6, 0, ['q', 'n', 'n', 'n', 'o', 'n', 'o'], p);
  rows(c, 0, 8, ['hq'], p);
  rows(c, 3, 8, ['oo'], p);
  return c;
}
write(gauge(false), T('fryer_a_gauge'));
write(gauge(true), T('fryer_a_gauge_on'));

// ---------------------------------------------------------------- models
const f = (texture, uv, extra = {}) => (uv ? { uv, texture, ...extra } : { texture, ...extra });

/** ring lug flush with the rim: top beam 1×1×6 + two 1×1×1 posts, open in between (cutout shows the pot wall behind) */
function lug(side) {
  const x0 = side === 'east' ? 15 : 0, out = side;
  const beam = { name: `lug ${side} beam`, from: [x0, 7, 5], to: [x0 + 1, 8, 11], faces: {
    [out]: f('#parts', P.lugOut), up: f('#parts', P.lugTop), down: f('#parts', P.lugBot),
    north: f('#parts', P.lugCap), south: f('#parts', P.lugCap) } };
  const post = (z, name) => ({ name: `lug ${side} ${name}`, from: [x0, 6, z], to: [x0 + 1, 7, z + 1], faces: {
    [out]: f('#parts', P.postOut), north: f('#parts', P.postSide), south: f('#parts', P.postSide), down: f('#parts', P.postBot) } });
  return [beam, post(5, 'post front'), post(10, 'post back')];
}

const potElements = () => [
  { name: 'foot', from: [2, 0, 2], to: [14, 1, 14], faces: {
    north: f('#side'), south: f('#side'), west: f('#side'), east: f('#side'), down: f('#bottom', null, { cullface: 'down' }) } },
  { name: 'wall north', from: [1, 1, 1], to: [15, RIM, 3], faces: {
    north: f('#side'), south: f('#inner'), west: f('#side'), east: f('#side'), up: f('#top'), down: f('#bottom') } },
  { name: 'wall south', from: [1, 1, 13], to: [15, RIM, 15], faces: {
    south: f('#side'), north: f('#inner'), west: f('#side'), east: f('#side'), up: f('#top'), down: f('#bottom') } },
  { name: 'wall west', from: [1, 1, 3], to: [3, RIM, 13], faces: {
    west: f('#side'), east: f('#inner'), up: f('#top'), down: f('#bottom') } },
  { name: 'wall east', from: [13, 1, 3], to: [15, RIM, 13], faces: {
    east: f('#side'), west: f('#inner'), up: f('#top'), down: f('#bottom') } },
  { name: 'oil', from: [3, 1, 3], to: [13, OIL_Y, 13], faces: { up: f('#oil') } },
  ...lug('east'), ...lug('west'),
  // 温度计 2×7，正面右角（从正面看 +x 在左，所以 x 1..3 在右边），不再和炸篮重叠
  { name: 'thermometer', from: [1, 4, 0], to: [3, 11, 1], faces: {
    north: f('#gauge', [0, 0, 2, 7]), south: f('#gauge', [3, 0, 5, 7]), west: f('#gauge', [6, 0, 7, 7]), east: f('#gauge', [6, 0, 7, 7]),
    up: f('#gauge', [0, 8, 2, 9]), down: f('#gauge', [3, 8, 5, 9]) } },
  // 夹子从温度计背后跨过锅沿角，探针在锅内角落插进油里（从上面看是个 L 形钩）
  { name: 'thermometer clip', from: [2, RIM, 1], to: [3, RIM + 1, 4], faces: {
    up: f('#parts', P.clipTop), west: f('#parts', P.clipSide), east: f('#parts', P.clipSide), south: f('#parts', P.clipEnd) } },
  { name: 'thermometer probe', from: [3, 4, 3], to: [4, RIM + 1, 4], faces: {
    north: f('#parts', P.probe), south: f('#parts', P.probe), east: f('#parts', P.probe), up: f('#parts', P.probeTop) } },
];

/** wire basket: x 4..12, z z0..z0+7, from y0 up to y1; zero-thickness walls with faces on both sides
 *  (the inner side uses a mirrored uv so each wire panel looks the same from both sides) */
function basket({ y0, y1, z0 }, withBottom) {
  const h = y1 - y0, z1 = z0 + 7, fb = [0, 0, 8, h], fbM = [8, 0, 0, h], lr = [8, 0, 15, h], lrM = [15, 0, 8, h];
  const els = [
    { name: 'basket front', from: [4, y0, z0], to: [12, y1, z0], faces: { north: f('#basket', fb), south: f('#basket', fbM) } },
    { name: 'basket back', from: [4, y0, z1], to: [12, y1, z1], faces: { north: f('#basket', fb), south: f('#basket', fbM) } },
    { name: 'basket right', from: [4, y0, z0], to: [4, y1, z1], faces: { west: f('#basket', lr), east: f('#basket', lrM) } },
    { name: 'basket left', from: [12, y0, z0], to: [12, y1, z1], faces: { west: f('#basket', lr), east: f('#basket', lrM) } },
  ];
  if (withBottom) els.push({ name: 'basket bottom', from: [4, y0, z0], to: [12, y0, z1], faces: { up: f('#basket', [0, 8, 8, 15]), down: f('#basket', [0, 8, 8, 15]) } });
  return els;
}
/** horizontal handle: U-shaped double steel wire (4 wide, middle 2 px see-through) from the basket's front rim to z 1,
 *  then a 2×2×3 wooden grip at z -2..1. yRod = bottom of the wire (top = basket rim). No rotation. */
function handle(yRod, zBasket) {
  const L = zBasket - 1;
  return [
    { name: 'handle wire (U)', from: [6, yRod, 1], to: [10, yRod + 1, zBasket], faces: {
      up: f('#parts', P.rodTop(L)), down: f('#parts', P.rodBot(L)), west: f('#parts', P.rodSide(L)), east: f('#parts', P.rodSide(L)), north: f('#parts', P.rodEnd) } },
    { name: 'handle grip', from: [7, yRod, GRIP_Z[0]], to: [9, yRod + 2, GRIP_Z[1]], faces: {
      north: f('#parts', P.gripEnd), south: f('#parts', P.gripEnd),
      east: f('#parts', P.gripSide), west: f('#parts', P.gripSideM),
      up: f('#parts', P.gripTop), down: f('#parts', P.gripBot) } },
  ];
}
/** idle only: two steel hooks on the basket's back bottom edge, over the back rim and down its outer face */
function hooks() {
  return [5, 10].flatMap(x => [
    { name: `hook ${x} bar`, from: [x, RIM, 14], to: [x + 1, RIM + 1, 16], faces: {
      up: f('#parts', P.hookTop), down: f('#parts', P.hookTop), east: f('#parts', P.hookSide), west: f('#parts', P.hookSide),
      north: f('#parts', P.hookEnd), south: f('#parts', P.hookEnd) } },
    { name: `hook ${x} drop`, from: [x, RIM - 2, 15], to: [x + 1, RIM, 16], faces: {
      east: f('#parts', P.hookDrop), west: f('#parts', P.hookDrop), south: f('#parts', P.hookDrop), down: f('#parts', P.hookDropBot) } },
  ]);
}

const textures = {
  particle: ID('fryer_a_side'),
  side: ID('fryer_a_side'), inner: ID('fryer_a_inner'), top: ID('fryer_a_top'), bottom: ID('fryer_a_bottom'),
  oil: ID('fryer_a_oil'), basket: ID('fryer_a_basket'), parts: ID('fryer_a_parts'), gauge: ID('fryer_a_gauge'),
};
// 待机：炸篮挂在后锅沿上沥油，木柄水平前伸；工作中：炸篮沉进油里，篮口高出锅沿 1px，木柄水平平搭在前锅沿上
const idle = [...potElements(), ...basket(IDLE, true), ...handle(IDLE.y1 - 1, IDLE.z0), ...hooks()];
const active = [...potElements(), ...basket(ACTIVE, false), ...handle(ACTIVE.y1 - 1, ACTIVE.z0)];

json('models/block/fryer_a_idle.json', {
  parent: 'minecraft:block/block',
  render_type: 'minecraft:cutout',
  textures,
  elements: idle,
});
json('models/block/fryer_a_active.json', {
  parent: ID('fryer_a_idle'),
  textures: { oil: ID('fryer_a_oil_on'), gauge: ID('fryer_a_gauge_on') },
  elements: active,
});

// 放在营火上（农夫乐事的 tray 热源）时：照抄厨锅 cooking_pot_tray 的托架 + 四条腿（腿伸到下面一格 y -16）
const trayEls = () => {
  const leg = (name, x, z, a, b) => ({ name, from: [x, -16, z], to: [x + 1, -1, z + 1], faces: {
    north: f('#tray_side', a.n), east: f('#tray_side', a.e), south: f('#tray_side', b.s), west: f('#tray_side', b.w),
    up: f('#tray_side', [0, 0, 0.5, 0.5]), down: f('#tray_side', [0, 0, 0.5, 0.5]) } });
  const L = [0, 1, 1, 16], R = [15, 1, 16, 16];
  return [
    { name: 'grate', from: [0, -1, 0], to: [16, 0, 16], faces: {
      north: f('#tray_side', [0, 0, 16, 1]), east: f('#tray_side', [0, 0, 16, 1]), south: f('#tray_side', [0, 0, 16, 1]), west: f('#tray_side', [0, 0, 16, 1]),
      up: f('#tray_top', [0, 0, 16, 16]), down: f('#tray_top', [0, 0, 16, 16]) } },
    leg('leg SE', 15, 15, { n: L, e: L }, { s: R, w: R }),
    leg('leg SW', 0, 15, { n: R, e: L }, { s: L, w: R }),
    leg('leg NE', 15, 0, { n: L, e: R }, { s: R, w: L }),
    leg('leg NW', 0, 0, { n: R, e: R }, { s: L, w: L }),
  ];
};
// 托架贴图是农夫乐事 1.2.9 cooking_pot_tray_top/_side 的逐字节拷贝 (MIT), 放在本方案自己的命名空间里:
// 跨 MOD 引用会在农夫乐事 1.3.x (已改名 heating_tray_*) 下变成紫黑格。
const trayTex = { tray_top: ID('fryer_a_tray_top'), tray_side: ID('fryer_a_tray_side') };
json('models/block/fryer_a_idle_tray.json', { parent: ID('fryer_a_idle'), textures: trayTex, elements: [...idle, ...trayEls()] });
json('models/block/fryer_a_active_tray.json', { parent: ID('fryer_a_active'), textures: trayTex, elements: [...active, ...trayEls()] });

// 物品模型：直接继承待机模型，沿用 minecraft:block/block 的 display（包围盒中心离方块中心不到 1px，先不调，进游戏再看）
json('models/item/fryer_a.json', { parent: ID('fryer_a_idle') });

// blockstate 草稿：facing × active × support 共 16 个变体（照 FD cooking_pot 的写法，不需要 uvlock）
{
  const variants = {};
  for (const [facing, y] of [['north', 0], ['east', 90], ['south', 180], ['west', 270]])
    for (const act of [false, true]) for (const sup of ['none', 'tray']) {
      const model = ID(`fryer_a_${act ? 'active' : 'idle'}${sup === 'tray' ? '_tray' : ''}`);
      variants[`active=${act},facing=${facing},support=${sup}`] = y ? { model, y } : { model };
    }
  json('blockstates/fryer_a.json', { variants });
}

const n = a => a.length;
json('meta.json', {
  key: 'A',
  tag: '方案 A',
  order: 1,
  name: '炉上油锅',
  tagline: '坐在炉灶上的厚铸铁油锅：挂一只木柄铁丝炸篮，右角夹一支黄铜油温计。',
  description: '体量参照农夫乐事厨锅：锅身 14×8×14 px（厨锅 12×10×12，更宽更矮），2px 厚的铸铁锅壁带一圈卷边，锅底内收 1px 的锅脚，左右各一只和锅沿齐平的环形铸铁耳（两端铜色端帽）。油面比锅沿低 1px，是带弯月面和镜面高光的热油。铁丝炸篮 8×7，木柄是 U 形双股钢丝加 2×2 木握把，水平前伸，握把伸出方块正面 2px。正面右角夹一支 2×7 的暗黄铜温度计，夹子跨过锅沿角、探针插进油里。待机：炸篮挂在后锅沿上沥油（两只钢钩），前面 4px 油面露出来，油面静止。工作中：炸篮沉进油里，篮口比锅沿高 1px，木柄平搭在前锅沿上；篮里篮外气泡在原地冒起、成圈、破掉，红柱升到橙色的油炸刻度。',
  notes: [
    '和农夫乐事厨锅一样要下方热源（炉灶等）才工作；放在营火上时换带托架的 fryer_a_idle_tray / fryer_a_active_tray（照抄厨锅的托架和四条腿，本页不展示）。',
    '待机 → 工作中：工作中模型继承待机模型的贴图，只换油面（翻泡动画 8 帧 × 3 tick，一圈 1.2 秒）和温度计（红柱升到橙色刻度）；炸篮从后锅沿上方落进油里，木柄从 y12 落到 y8 平搭在前锅沿上，握把位置只变高度。',
    `元素：待机 ${n(idle)} 个，工作中 ${n(active)} 个（托架变体各 +5）；贴图全部 16×16，颜色取自农夫乐事锅具的冷灰梯度、木勺木色、篮子金黄和厨锅把手的铜色；render_type cutout（炸篮、钢丝、环形耳镂空）。`,
    '朝向：正面（温度计、木柄）画在 north，facing=north 时 y=0，east 90，south 180，west 270。没有任何旋转元素，整体最高 y14（待机握把顶）。',
    '工作中必须加粒子：油面 y≈7/16 冒油星（小、亮黄）和白色蒸汽，炸篮范围里多一些——平视时锅里能看到的东西有限，粒子是远处认出“正在炸”的主要信号。',
  ],
  hires: false,
  namespace: NS,
  kitchen: { under: 'fd_stove' },
  blocks: [
    { label: '炸锅', pos: [0, 0, 0], idle: { model: 'fryer_a_idle', y: 0 }, active: { model: 'fryer_a_active', y: 0 } },
  ],
});
console.log(`fryer A written: idle ${idle.length} elements, active ${active.length} elements`);
