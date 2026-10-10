// 备餐台方案 B「冷藏备餐台」生成脚本：逐像素画贴图 + 写原版方块模型 JSON + meta.json。
//   cd <仓库>\tools\chef_stations && node designs-more/prep_b/gen.mjs
// 产出（路径与主 MOD assets/miningdim/ 一致，定稿后 models/ textures/ 可直接拷）：
//   models/block/prep_b.json      待机：掀盖合上、砧板上平放一把刀、温度屏熄着、柜里不亮灯
//   models/block/prep_b_on.json   工作中：parent=待机（继承贴图），自带一套元素：掀盖绕后沿铰链翻起 45°、格栅框补 4 面内壁、
//                                 砧板上收起刀、换成一只汉堡 + 一条卷饼；覆盖 front→front_on（柜内灯亮）、display→display_on（亮蓝温度屏，
//                                 shade:false + forge_data 满光照，2 帧闪烁）
//   models/block/prep_b_on_closed.json  可选：工作中但上方 1 格有方块时用——掀盖合着，其余同工作中
//   textures/block/prep_b_*.png (+ display_on.png.mcmeta)
// 坐标约定：正面 = north（z=0），从正面看 +x 在左边；顶面贴图行 = z（第 0 行是正面）、列 = x。
// 和炸锅 B「商用炸炉」同一套：同样的不锈钢梯度和用法（底色 4，受光倒角 5，最亮的 6 只给前沿 / 盖沿这种 1 像素高光）、
// 四条支脚（钢 + 深色橡胶脚）、y10–11 的前沿圆边和下面一行阴影、和炸炉逐像素相同的柜体侧面、同一组铜红把手 / 铭牌色。
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { makeCanvas, setPx, getPx, strip, write, rgba } from '../../lib/png.mjs';

const dir = path.dirname(fileURLToPath(import.meta.url));
const NS = 'miningdim', P = 'prep_b';
const texFile = n => path.join(dir, 'textures/block', `${P}_${n}.png`);
const tex = n => `${NS}:block/${P}_${n}`;
const json = (rel, o) => { const f = path.join(dir, rel); fs.mkdirSync(path.dirname(f), { recursive: true }); fs.writeFileSync(f, JSON.stringify(o, null, 2) + '\n'); };

// ---------------------------------------------------------------- palette
// 不锈钢 1–6 与炸锅 B（designs/B/gen.mjs）完全相同（农夫乐事锅具冷灰往亮处延伸）；a–g 农夫乐事深灰；R–U 农夫乐事厨锅把手铜红（同炸锅 B）。
const PAL = {
  1: '#4f4f53', 2: '#626266', 3: '#747478', 4: '#87878b', 5: '#9a9a9e', 6: '#aeaeb2',
  a: '#1f1f21', b: '#27272b', c: '#2d2d32', d: '#343438', e: '#3f3e42', f: '#494848', g: '#595858',
  R: '#9b5643', S: '#b1624d', T: '#733f31', U: '#8f503f',
  // 刀刃口的一行亮光（只给刀用，柜体不用这么亮的钢）
  bl: '#c6c6c9',
  // 白色塑料砧板（偏暖的米白，和不锈钢拉开；比纯白压暗 1–2 档，免得在厨房场景里最抢眼）
  w: '#e2ddd0', W: '#ece8de', x: '#c9c3b4', X: '#b3ab9b', Y: '#d8d2c4',   // Y：极淡的磨痕点
  // 食材：生菜 / 番茄 / 芝士 / 洋葱 / 火腿 / 米饭
  l: '#4f8f2a', L: '#77b83f', m: '#3a6b20',
  t: '#c8352a', y: '#e8604a', z: '#8f241d',
  h: '#efc23c', H: '#fbe27c', j: '#cf9420',
  o: '#e7dbe8', O: '#b07ec0', q: '#82558f',
  p: '#e3898f', P: '#f4b9ba', Q: '#bf6670',
  r: '#f1eee5', s: '#d9d4c7',
  // 柜内（玻璃窗后）：待机暗、工作中开灯
  i: '#28333c', I: '#33414c', k: '#7fa3b8', K: '#a9c8d8',
  // 饮料瓶：橙汁 / 牛奶 / 草莓奶昔 / 可乐 / 瓶盖
  n: '#e8891e', N: '#f4b04a', v: '#f3f0e8', V: '#dcd8cf', u: '#e98fae', D: '#5a3018', E: '#d93a2b',
  // 温度屏：待机熄屏（比背板亮一截，左上角一点玻璃反光 M）、工作中亮蓝（shade:false）
  A: '#2a3a46', B: '#34495a', M: '#4d6878', C: '#2f86d6', F: '#62bdf5', G: '#b4ecff',
  // 汉堡 / 卷饼
  '1b': '#c9823a', '2b': '#e2a654', '3b': '#a8652a', se: '#f6e9c2', pa: '#5c3520', pb: '#7b4a2c', ch: '#f2c43c',
  tw: '#ead39d', tx: '#d4b06b', ty: '#b08447',
};
const col = ch => { if (!(ch in PAL)) throw new Error('palette: ' + ch); return PAL[ch]; };
const C = () => makeCanvas(16, 16);
const px = (c, x, y, ch) => { if (ch !== '.') setPx(c, x, y, col(ch)); else setPx(c, x, y, [0, 0, 0, 0]); };
const rect = (c, x, y, w, h, ch) => { for (let j = y; j < y + h; j++) for (let i = x; i < x + w; i++) px(c, i, j, ch); };
/** rows of single-char keys; ' ' = leave as is, '.' = transparent. Two-char keys in `{xx}`. */
const rows = (c, x, y, lines) => lines.forEach((l, j) => { let i = 0; for (const tok of l.match(/\{..\}|./g)) { const ch = tok.length > 1 ? tok.slice(1, 3) : tok; if (ch !== ' ') px(c, x + i, y + j, ch); i++; } });
function rng(seed) { let s = seed >>> 0; return () => { s = (s + 0x6d2b79f5) >>> 0; let t = s; t = Math.imul(t ^ (t >>> 15), t | 1); t ^= t + Math.imul(t ^ (t >>> 7), t | 61); return ((t ^ (t >>> 14)) >>> 0) / 4294967296; }; }
const same = (c, x, y, ch) => { const p = getPx(c, x, y), q = rgba(col(ch)); return p[0] === q[0] && p[1] === q[1] && p[2] === q[2] && p[3] === 255; };
function speck(c, x, y, w, h, from, to, density, seed) {
  const R = rng(seed);
  for (let j = y; j < y + h; j++) for (let i = x; i < x + w; i++) { const k = R(); if (k < density && same(c, i, j, from)) px(c, i, j, to); }
}

// ---------------------------------------------------------------- textures
const T = {};

// FRONT（north 面，正看：u=0 是 x=16 那一侧）
//   v0-1 掀盖正面（y14-16） | v2 格栅框正面（y13-14） | v3-4 冷藏槽正面（y11-13，v4 被砧板挡住）
//   v5 前沿圆边（y10-11） | v6 圆边下的阴影 | v7-13 柜体（y2-9）：u1-10 冷柜门（玻璃小窗里几瓶饮料），u11-14 温度屏 + 冷凝器百叶 | v14-15 不用
function front(lit) {
  const c = C();
  // 掀盖正面（上沿 1 像素高光，同炸锅 B 的前沿行）+ 格栅框 + 冷藏槽
  rect(c, 0, 0, 16, 1, '6'); speck(c, 0, 0, 16, 1, '6', '5', 0.3, 1);
  rect(c, 0, 1, 16, 1, '3');
  rect(c, 0, 2, 16, 1, '4');
  rect(c, 0, 3, 16, 1, '3'); rect(c, 0, 4, 16, 1, '2');
  // 前沿圆边 + 阴影（炸锅 B 的前沿行）
  rect(c, 0, 5, 16, 1, '6'); speck(c, 0, 5, 16, 1, '6', '5', 0.3, 12);
  rect(c, 0, 6, 16, 1, 'b');
  // 柜体底色 + 两侧立框（同炸锅 B 的门框：底色 4，立框 3）
  rect(c, 0, 7, 16, 7, '4');
  for (let v = 7; v <= 13; v++) { px(c, 0, v, '3'); px(c, 15, v, '3'); }
  // 冷柜门 u1-10：受光上沿/左沿 5，暗下沿 2 / 右沿 3（炸锅 B 大门的倒角写法）
  for (let u = 1; u <= 10; u++) { px(c, u, 7, '5'); px(c, u, 13, '2'); }
  for (let v = 7; v <= 13; v++) { px(c, 1, v, '5'); px(c, 10, v, '3'); }
  px(c, 10, 7, '4'); px(c, 1, 13, '3');
  speck(c, 2, 8, 8, 5, '4', '3', 0.18, 3);
  // 玻璃小窗 u3-7 v9-11，四边一圈深色胶条（u2/u8、v8/v12），里面几瓶饮料；u9 是门把手
  for (let v = 8; v <= 12; v++) { px(c, 2, v, 'c'); px(c, 8, v, 'c'); }
  for (let u = 2; u <= 8; u++) { px(c, u, 8, 'c'); px(c, u, 12, 'c'); }
  const bg = lit ? 'k' : 'i', bg2 = lit ? 'K' : 'I';
  rect(c, 3, 9, 5, 3, bg);
  rows(c, 3, 9, [
    `E${bg2}V${bg}E`,                // 瓶盖（第 2 列是玻璃反光）
    'nNvuD',                         // 瓶身：橙汁 / 牛奶 / 草莓奶昔 / 可乐
    'nnvuD',
  ]);
  // 右边：温度屏底板（屏是单独元素，凸出 0.5px）+ 铜铭牌 + 冷凝器百叶
  rect(c, 11, 7, 4, 2, 'c'); rect(c, 11, 9, 4, 1, 'd');
  rows(c, 12, 9, ['RS']);
  for (const v of [10, 12]) { rect(c, 11, v, 4, 1, '5'); rect(c, 11, v + 1, 4, 1, '1'); }   // 百叶：炸锅 B 侧面百叶的写法
  rect(c, 0, 14, 16, 2, '1');
  return c;
}
T.front = front(false);
T.front_on = front(true);

// SIDE（west 面正看：u = z，正面在左；east 面用镜像 uv，正面仍在正面）
//   v0-1 u7-15 掀盖侧面 | v2 格栅框 | v3-4 冷藏槽 | v5 前沿圆边 + 柜顶 | v6-13 柜体侧板（后部百叶） | v14-15 不用
//   v5-15 和炸锅 B 的 fryer_b_side.png 逐像素相同（同一个圆边、受光前沿 / 背光后沿、下沿、后部两条百叶）。
{
  const c = C();
  rect(c, 0, 0, 16, 16, '4');
  // 掀盖侧面：上沿 1 像素高光、正面那一列受光，后沿铰链
  rect(c, 7, 0, 9, 1, '6'); speck(c, 7, 0, 9, 1, '6', '5', 0.3, 11);
  rect(c, 7, 1, 9, 1, '3'); px(c, 7, 1, '4');
  px(c, 15, 0, '4'); px(c, 15, 1, '2');                     // 后沿铰链
  rect(c, 7, 2, 9, 1, '4'); px(c, 7, 2, '5');
  rect(c, 7, 3, 9, 1, '3'); rect(c, 7, 4, 9, 1, '2');
  px(c, 6, 4, '3');                                          // 小沟两端的封口（groove cap，y11-12 z6-7）
  // 以下照抄炸锅 B 的侧面：圆边、柜体侧板（受光前沿、背光后沿 + 下沿）、后部百叶、最底下两行
  rect(c, 0, 5, 16, 1, '6'); speck(c, 0, 5, 16, 1, '6', '5', 0.3, 22);
  for (let v = 6; v <= 13; v++) { px(c, 1, v, '5'); px(c, 15, v, '3'); }
  rect(c, 1, 13, 15, 1, '2');
  speck(c, 2, 6, 13, 7, '4', '3', 0.14, 23);
  for (const v of [8, 10]) { rect(c, 10, v, 4, 1, '5'); rect(c, 10, v + 1, 4, 1, '1'); }
  rect(c, 0, 14, 16, 2, '1');
  T.side = c;
}

// BACK（south 面：u = x）。v0-1 掀盖后沿 + 两只铰链 | v2-4 格栅框 / 冷藏槽 | v5-13 柜背：冷凝盘管格栅 + 电源口
// 色调和分区同炸锅 B 的背面：底色 3、受光上沿 4、斑点 2，y9–10 一道接缝 2，检修板 4 / 2 倒角、铜铭牌。
// 掀盖和冷藏槽之间只留一道 2 的接缝，不再一行一个色（背面是看得最少的面，越素越像炸炉）。
{
  const c = C();
  rect(c, 0, 0, 16, 16, '3');
  rect(c, 0, 0, 16, 1, '4');
  speck(c, 0, 1, 16, 13, '3', '2', 0.12, 21);
  rect(c, 0, 2, 16, 1, '2');                                  // 掀盖 / 冷藏槽接缝
  for (const u of [3, 11]) rows(c, u, 0, ['22', '11']);       // 后沿两只铰链
  rect(c, 0, 6, 16, 1, '2');                                  // 柜体接缝（同炸锅 B 背面 v6）
  // 冷凝盘管格栅（竖条）
  for (let u = 2; u <= 13; u++) { px(c, u, 7, '4'); px(c, u, 12, '2'); }
  for (let v = 7; v <= 12; v++) { px(c, 2, v, '4'); px(c, 13, v, '2'); }
  for (let v = 8; v <= 11; v++) for (let u = 3; u <= 12; u++) px(c, u, v, u % 2 ? 'b' : '3');
  rows(c, 13, 9, ['Ta', 'UR']);
  rect(c, 0, 14, 16, 2, '1');
  T.back = c;
}

// TOP（u = x，v = z，第 0 行是正面）。v0-5 被砧板盖住 | v6 砧板和冷藏槽之间的小沟 | v7-15 掀盖顶面（前沿受光、中间一道压筋、后沿铰链）
// 掀盖顶面按炸锅 B 的写法：钢板底色 4（细斑 3），前沿一行 6（夹几点 5，同炸锅的台沿高光），压筋 5 / 3，左右边 3、后沿 3、铰链 2。
{
  const c = C();
  rect(c, 0, 0, 16, 16, '4');
  rect(c, 0, 6, 16, 1, '2');
  rect(c, 0, 7, 16, 1, '6'); speck(c, 0, 7, 16, 1, '6', '5', 0.3, 31);
  rect(c, 0, 8, 16, 7, '4'); speck(c, 0, 8, 16, 7, '4', '3', 0.08, 32);
  rect(c, 1, 10, 14, 1, '5'); rect(c, 1, 11, 14, 1, '3');   // 压筋
  rect(c, 0, 15, 16, 1, '3');
  for (const u of [3, 11]) { px(c, u, 15, '2'); px(c, u + 1, 15, '2'); }
  for (let v = 8; v <= 15; v++) { px(c, 0, v, '3'); px(c, 15, v, '3'); }
  T.top = c;
}

// RAIL —— 冷藏槽上的不锈钢格栅框（cutout），只用 v7-15：前沿 v7、两排格子 v8-10 / v12-14、中梁 v11、后沿 v15；
// 列：边 u0 / u15，格子 u1-4 / u6-9 / u11-14，隔条 u5 / u10。格子处透明，露出下面的食材。
// 色调同炸锅 B 的台面边框：框 5（像炸锅的侧沿 / 隔板），前沿一行 6，左右边和后沿 4。
{
  const c = C();
  rect(c, 0, 7, 16, 9, '5');
  for (const v0 of [8, 12]) for (const u0 of [1, 6, 11]) rect(c, u0, v0, 4, 3, '.');
  rect(c, 0, 7, 16, 1, '6');
  speck(c, 0, 11, 16, 1, '5', '6', 0.3, 41);
  for (let v = 7; v <= 15; v++) { px(c, 0, v, '4'); px(c, 15, v, '4'); }
  rect(c, 0, 15, 16, 1, '4');
  T.rail = c;
}

// CELLS —— 冷藏槽顶面（y=13，比格栅框低 1px），六格食材。格栅正下方画成暗色，斜看时像格壁。
// 正面看贴图是倒的（+x 在左）：前排从左到右 生菜 / 番茄 / 芝士，后排 洋葱 / 火腿 / 米饭。
{
  const c = C();
  rect(c, 0, 0, 16, 16, '2');
  const cell = (u0, v0, lines) => rows(c, u0, v0, lines);
  // 前排（v8-10）：列 u11-14 在正看的左边
  cell(11, 8, ['LlmL', 'lLlm', 'mlLl']);                     // 生菜
  cell(6, 8, ['ytzt', 'tzyt', 'zyty']);                      // 番茄丁
  cell(1, 8, ['HhjH', 'hHhj', 'jhHh']);                      // 芝士丝
  // 后排（v12-14）
  cell(11, 12, ['oOqo', 'Ooqo', 'qoOo']);                    // 红洋葱
  cell(6, 12, ['pPpQ', 'PpQp', 'pQpP']);                     // 火腿片
  cell(1, 12, ['rsrr', 'rrsr', 'srrs']);                     // 米饭
  // v0：格栅框里侧的四面内壁（工作中才有，y13-14，挡住低角度看穿薄框）
  rect(c, 0, 0, 16, 1, '3');
  T.cells = c;
}

// BOTTOM（柜底）
{
  const c = C();
  rect(c, 0, 0, 16, 16, 'd');
  speck(c, 0, 0, 16, 16, 'd', 'e', 0.2, 51);
  for (let i = 0; i < 16; i++) { px(c, i, 0, '2'); px(c, i, 15, '2'); px(c, 0, i, '2'); px(c, 15, i, '2'); }
  T.bottom = c;
}

// BOARD —— 白色塑料砧板：v0-5 顶面（u = x，v = z，外圈一道导流槽）| v6 正面边 | v7 侧面边（6 长）| v8 背面边
//   顶面：左右两列 u0/u15 和后沿 v5 用 x 勾出板子外轮廓，前沿 v0 留一行 W 做倒角受光；导流槽 x；中间 3 个极淡的磨痕点 Y
//   正面边 v6 用 x（比顶面暗一档，看得出板子有厚度）
{
  const c = C();
  rect(c, 0, 0, 16, 6, 'w');
  for (let u = 1; u <= 14; u++) { px(c, u, 1, 'x'); px(c, u, 4, 'x'); }
  for (let v = 1; v <= 4; v++) { px(c, 1, v, 'x'); px(c, 14, v, 'x'); }
  rect(c, 1, 0, 14, 1, 'W');
  for (let v = 0; v <= 5; v++) { px(c, 0, v, 'x'); px(c, 15, v, 'x'); }
  rect(c, 0, 5, 16, 1, 'x');
  for (const [u, v] of [[4, 2], [9, 3], [12, 2]]) px(c, u, v, 'Y');
  rect(c, 0, 6, 16, 1, 'x'); speck(c, 0, 6, 16, 1, 'x', 'w', 0.2, 61);
  rect(c, 0, 7, 6, 1, 'x'); px(c, 0, 7, 'w');
  rect(c, 0, 8, 16, 1, 'X');
  T.board = c;
}

// PARTS —— 掀盖铜把手、门把手、支脚、圆边底面、掀盖内衬
//   u0-5 v0 把手正面 | u0-5 v1 把手顶 | u0-5 v2 把手底 | u6 v0 把手端面
//   u8 v0-4 门把手正面 | u9 v0-4 门把手侧面 | u10 v0 门把手端面
//   u0-3 v4-5 支脚（同炸锅 B）| v6 圆边底面 | v7-15 掀盖内衬（开盖后朝前）
{
  const c = C();
  // 铜把手：底色 R，高光 S 只点两下（同烤炉 B 门把手 / 炸锅 B 炸篮把手的写法），两端和底面暗
  rows(c, 0, 0, ['URRRRU', 'RSRRSR', 'TTTTTT']); px(c, 6, 0, 'U');
  // 门把手（竖杆）：炸锅 B 门把手的写法（杆身 5、两端 4、侧面 3）
  rows(c, 8, 0, ['43', '53', '53', '53', '43']); px(c, 10, 0, '3');
  rows(c, 0, 4, ['33bb', 'bbbb']);                           // 支脚：炸锅 B 的（钢 + 深色橡胶脚）
  rect(c, 0, 6, 16, 1, 'b');                                 // 圆边底面：同炸锅 B 前沿的底面
  // 掀盖内衬：浅色钢板 + 一圈深色密封胶条 + 中间一道凹筋（v11 / v12，正对顶面 z10-11 的压筋；开盖后 v12 在上、背光，v11 在下、受光）
  rect(c, 0, 7, 16, 9, '5');
  for (let u = 0; u < 16; u++) { px(c, u, 7, '3'); px(c, u, 15, '3'); }
  for (let v = 7; v <= 15; v++) { px(c, 0, v, '3'); px(c, 15, v, '3'); }
  for (let u = 1; u <= 14; u++) { px(c, u, 8, 'c'); px(c, u, 14, 'c'); }
  for (let v = 8; v <= 14; v++) { px(c, 1, v, 'c'); px(c, 14, v, 'c'); }
  rect(c, 2, 12, 12, 1, '4'); rect(c, 2, 11, 12, 1, '6');
  T.parts = c;
}

// DISPLAY —— 温度屏 4×3：正面 u0-3 v0-2，侧面 u4 v0-2，顶/底 u0-3 v3。待机熄屏，工作中亮蓝 + 制冷灯闪。
function display(on, blink) {
  const c = C();
  // 左边一位“3”（2×3，认不出数字也无所谓，只是一块屏）。待机：熄屏底色 A、残影 B、左上角玻璃反光 M。
  // 工作中：右边一整列（u3 v0-2，3 个像素）是制冷灯，两帧在 C / G 之间切换，1 秒一闪
  if (!on) rows(c, 0, 0, ['MBAB', 'ABAA', 'BBAA']);
  else rows(c, 0, 0, blink ? ['GGCG', 'CGCG', 'GGCG'] : ['GGCC', 'CGCC', 'GGCC']);
  rows(c, 4, 0, ['d', 'd', 'c']);
  rows(c, 0, 3, ['dddd']);
  return c;
}
T.display = display(false);
T.display_on = strip([display(true, false), display(true, true)]);

// FOOD —— 砧板上的东西：番茄酱 / 芥末酱挤瓶（一直在）；待机一把平放的刀；工作中换成一只汉堡、一条卷饼
//   汉堡身 4×3×4：侧面 u0-3 v0-2（面包 / 生菜芝士肉饼 / 底面包），顶 u4-7 v0-3；面包顶 2×1×2：侧面 u12-13 v0，顶 u12-13 v1-2
//   卷饼 6×2×2：长侧面 u0-5 v5-6（u0-2 饼皮、u3-5 包装纸），顶 u0-5 v7-8，露馅端面 u6-7 v5-6，纸包端面 u6-7 v7-8
//   挤瓶 2×3×2：红瓶侧面 u8-9 v5-7、黄瓶 u10-11 v5-7；瓶顶 u8-9 / u10-11 v8-9；尖嘴 1×1：红 u12、黄 u13，侧面 v5、顶 v6
//   刀身 5×0.5×2：顶 u0-4 v10-11（v10 刃口一侧、u0 刀尖缺一角做出尖头），刃口面 u0-4 v12，刀背面 u0-4 v13，刀尖端 u6-7 v12，护手端 u6-7 v13
//   刀柄 3×1×1：顶 u8-10 v10（中间一颗铜红铆钉），侧面 u8-10 v11，端面 u11 v10
{
  const c = C();
  rows(c, 0, 0, ['{2b}{1b}{2b}{2b}', 'L{pa}{ch}{pa}', '{3b}{1b}{3b}{1b}']);
  rows(c, 4, 0, ['{1b}{2b}{2b}{1b}', '{2b}{se}{2b}{2b}', '{2b}{2b}{2b}{se}', '{1b}{2b}{2b}{1b}']);
  rows(c, 12, 0, ['{2b}{2b}', '{se}{2b}', '{2b}{2b}']);
  // 饼皮统一 tw，顶面中间一道斜的 ty 烙痕，tx 只在侧面底边做阴影
  rows(c, 0, 5, ['{tw}{tw}{tw}vEv', '{tx}{tx}{tx}VEV']);
  rows(c, 0, 7, ['{tw}{tw}{tx}vEv', '{tw}{tx}{tw}vEv']);
  // 刀：刃口一行最亮（bl，只有刀用）、刀背一行 5，护手处 4；刀尖缺一角
  rows(c, 0, 10, ['.{bl}{bl}{bl}6', '55554', '6666.', '44444']);
  rows(c, 6, 12, ['.5', '44']);
  rows(c, 8, 10, ['cRbc', 'cbc']);
  rows(c, 6, 5, ['Lt', '{pb}h']);
  rows(c, 6, 7, ['vV', 'VV']);
  rows(c, 8, 5, ['yt', 'vV', 'tz']); rows(c, 10, 5, ['Hh', 'vV', 'hj']);
  rows(c, 8, 8, ['yt', 'tz']); rows(c, 10, 8, ['Hh', 'hj']);
  rows(c, 12, 5, ['zj', 'tH']);
  T.food = c;
}

for (const [n, c] of Object.entries(T)) write(c, texFile(n));
json(`textures/block/${P}_display_on.png.mcmeta`, { animation: { frametime: 10, interpolate: false } });

// ---------------------------------------------------------------- model helpers（与炸锅 B 相同的约定）
function uvOf(dir, f, t) {
  const [x1, y1, z1] = f, [x2, y2, z2] = t;
  switch (dir) {
    case 'north': return [16 - x2, 16 - y2, 16 - x1, 16 - y1];
    case 'south': return [x1, 16 - y2, x2, 16 - y1];
    case 'west': return [z1, 16 - y2, z2, 16 - y1];
    case 'east': return [z2, 16 - y2, z1, 16 - y1];          // 镜像：侧面贴图的“正面”始终在方块正面一侧
    case 'up': return [x1, z1, x2, z2];
    case 'down': return [x1, 16 - z2, x2, 16 - z1];
  }
}
const CULL = { north: f => f[2] === 0, south: (f, t) => t[2] === 16, west: f => f[0] === 0, east: (f, t) => t[0] === 16, up: (f, t) => t[1] === 16, down: f => f[1] === 0 };
function el(name, from, to, faces, extra = {}) {
  const out = { name, from, to, ...extra, faces: {} };
  for (const [dir, spec] of Object.entries(faces)) {
    const s = typeof spec === 'string' ? { texture: spec } : Array.isArray(spec) ? { texture: spec[0], uv: spec[1], ...(spec[2] || {}) } : { ...spec };
    const face = { uv: s.uv || uvOf(dir, from, to), texture: s.texture };
    if (s.rotation) face.rotation = s.rotation;
    if (s.shade === false) face.shade = false;
    if (CULL[dir](from, to) && !s.noCull && !extra.rotation) face.cullface = dir;
    out.faces[dir] = face;
  }
  return out;
}

// ---------------------------------------------------------------- geometry
/** idle=true：掀盖合着，冷藏槽和格栅框的两个 up 面完全被掀盖包住，不输出（少画 2 个 16×9 的大面）。 */
function body(idle) {
  const E = [];
  const up = (t) => (idle ? {} : { up: t });
  for (const [x, z, n] of [[1, 2, 'leg front west'], [13, 2, 'leg front east'], [1, 13, 'leg back west'], [13, 13, 'leg back east']])
    E.push(el(n, [x, 0, z], [x + 2, 2, z + 2], { north: ['#parts', [0, 4, 2, 6]], south: ['#parts', [0, 4, 2, 6]], west: ['#parts', [0, 4, 2, 6]], east: ['#parts', [0, 4, 2, 6]], down: ['#parts', [2, 4, 4, 6]] }));
  // 柜体：正面内收到 z=1（门把手、温度屏都不出方块）；顶面 y=11 只露出砧板后面的一条小沟
  E.push(el('cabinet', [0, 2, 1], [16, 11, 16], { north: '#front', south: '#back', west: '#side', east: '#side', up: '#top', down: ['#bottom', [0, 0, 16, 15]] }));
  E.push(el('lip', [0, 10, 0], [16, 11, 1], { north: '#front', down: ['#parts', [0, 6, 16, 7]], west: '#side', east: '#side' }));
  // 白色砧板（前沿，1px 厚）
  E.push(el('cutting board', [0, 11, 0], [16, 12, 6], { up: ['#board', [0, 0, 16, 6]], north: ['#board', [0, 6, 16, 7]], south: ['#board', [0, 8, 16, 9]], west: ['#board', [0, 7, 6, 8]], east: ['#board', [6, 7, 0, 8]] }));
  // 砧板和冷藏槽之间的小沟（y11-12，z6-7）两端封口，侧面低角度不再透光
  E.push(el('groove cap west', [0, 11, 6], [1, 12, 7], { west: '#side' }));
  E.push(el('groove cap east', [15, 11, 6], [16, 12, 7], { east: '#side' }));
  // 冷藏槽：顶面 y=13 是六格食材，上面压一圈 1px 的不锈钢格栅框（cutout）
  E.push(el('rail well', [0, 11, 7], [16, 13, 16], { ...up('#cells'), north: '#front', south: '#back', west: '#side', east: '#side' }));
  E.push(el('rail frame', [0, 13, 7], [16, 14, 16], { ...up('#rail'), north: '#front', south: '#back', west: '#side', east: '#side' }));
  // 门把手（竖杆）
  E.push(el('door handle', [6, 3, 0], [7, 8, 1], { north: ['#parts', [8, 0, 9, 5]], west: ['#parts', [9, 0, 10, 5]], east: ['#parts', [9, 0, 10, 5]], up: ['#parts', [10, 0, 11, 1]], down: ['#parts', [10, 0, 11, 1]] }));
  // 砧板左端（正看）一红一黄两只挤瓶：番茄酱在后、往右错开 1px（正看时黄瓶右边露出一条红边），芥末酱在前
  for (const [x, z, n, u] of [[12, 3, 'ketchup', 8], [13, 1, 'mustard', 10]]) {
    const side = ['#food', [u, 5, u + 2, 8]];
    E.push(el(`${n} bottle`, [x, 12, z], [x + 2, 15, z + 2], { north: side, south: side, west: side, east: side, up: ['#food', [u, 8, u + 2, 10]] }));
    const tip = u === 8 ? 12 : 13, ts = ['#food', [tip, 5, tip + 1, 6]];
    E.push(el(`${n} nozzle`, [x + 0.5, 15, z + 0.5], [x + 1.5, 16, z + 1.5], { north: ts, south: ts, west: ts, east: ts, up: ['#food', [tip, 6, tip + 1, 7]] }));
  }
  return E;
}
/** 格栅框里侧四面内壁（只在开盖时需要）：格栅框是个只有顶面镂空的 1px 薄盒，外侧四面是单面，
 *  低角度从格子里看进去，视线在落到 y=13 的食材面之前会先碰到外墙背面（被剔除）而穿出去。每块只留朝里的一面。 */
function railWalls() {
  const w = ['#cells', [0, 0, 16, 1]], s = ['#cells', [7, 0, 16, 1]];
  return [
    el('rail wall front', [0, 13, 7], [16, 14, 8], { south: w }),
    el('rail wall back', [0, 13, 15], [16, 14, 16], { north: w }),
    el('rail wall west', [0, 13, 7], [1, 14, 16], { east: s }),
    el('rail wall east', [15, 13, 7], [16, 14, 16], { west: s }),
  ];
}
/** 待机时砧板上平放的一把刀（刀尖朝 x=3，正看在右；刀背靠后），绕 y 轴转 22.5°：刀柄朝前、刀尖朝右后方，像随手放下的。
 *  工作中换成汉堡和卷饼。转完占 x≈2.9–11.1、z≈0.6–5.4，不碰挤瓶（x≥12），也不出砧板（z<6）。 */
function knife() {
  const rot = { rotation: { origin: [7, 12, 3], axis: 'y', angle: 22.5 } };
  return [
    el('knife blade', [3, 12, 2], [8, 12.5, 4], {
      up: ['#food', [0, 10, 5, 12]], north: ['#food', [0, 12, 5, 12.5]], south: ['#food', [0, 13, 5, 13.5]],
      west: ['#food', [6, 12, 8, 12.5]], east: ['#food', [6, 13, 8, 13.5]] }, rot),
    el('knife handle', [8, 12, 3], [11, 13, 4], {
      up: ['#food', [8, 10, 11, 11]], north: ['#food', [8, 11, 11, 12]], south: ['#food', [8, 11, 11, 12]],
      west: ['#food', [11, 10, 12, 11]], east: ['#food', [11, 10, 12, 11]] }, rot),
  ];
}
function screen(lit) {
  const sh = lit ? { shade: false } : {};
  // 工作中：Forge 的 forge_data 让这个元素按满光照画（暗处也亮；原版忽略这个键）
  const glow = lit ? { forge_data: { block_light: 15, sky_light: 15 } } : {};
  return [el('thermo display', [1, 7, 0.5], [5, 10, 1], {
    north: ['#display', [0, 0, 4, 3], sh], west: ['#display', [4, 0, 4.5, 3]], east: ['#display', [4, 0, 4.5, 3]],
    down: ['#display', [0, 3, 4, 3.5]] }, glow)];
}
/** 掀盖 + 铜把手；open 时绕后沿铰链 (y16, z16) 沿 x 轴翻起 45°。 */
function lid(open) {
  const rot = open ? { rotation: { origin: [8, 16, 16], axis: 'x', angle: 45 } } : {};
  const faces = { up: '#top', north: '#front', south: '#back', west: '#side', east: '#side' };
  if (open) faces.down = ['#parts', [0, 7, 16, 16]];
  return [
    el('lid', [0, 14, 7], [16, 16, 16], faces, rot),
    el('lid handle', [5, 14, 6], [11, 15, 7], { north: ['#parts', [0, 0, 6, 1]], up: ['#parts', [0, 1, 6, 2]], down: ['#parts', [0, 2, 6, 3]], west: ['#parts', [6, 0, 7, 1]], east: ['#parts', [6, 0, 7, 1]] }, rot),
  ];
}
function food() {
  const bs = ['#food', [0, 0, 4, 3]], cs = ['#food', [12, 0, 14, 1]];
  return [
    // 汉堡身 4×3×4 + 面包顶 2×1×2，一共 4px 高，比挤瓶（3 + 1 尖嘴）矮一点、收在方块格内；x 往右挪半格，和番茄酱瓶留出 0.5px 缝
    el('burger', [7.5, 12, 1], [11.5, 15, 5], { north: bs, south: bs, west: bs, east: bs, up: ['#food', [4, 0, 8, 4]] }),
    el('burger crown', [8.5, 15, 2], [10.5, 16, 4], { north: cs, south: cs, west: cs, east: cs, up: ['#food', [12, 1, 14, 3]] }),
    // 卷饼斜放 22.5°：露馅的一头（x=1）朝右前方，另一半裹着红条纹包装纸
    el('wrap', [1, 12, 2], [7, 14, 4], {
      north: ['#food', [6, 5, 0, 7]], south: ['#food', [0, 5, 6, 7]], up: ['#food', [0, 7, 6, 9]],
      west: ['#food', [6, 5, 8, 7]], east: ['#food', [6, 7, 8, 9]] }, { rotation: { origin: [4, 13, 3], axis: 'y', angle: -22.5 } }),
  ];
}

const textures = {
  particle: tex('side'), front: tex('front'), side: tex('side'), back: tex('back'), top: tex('top'), bottom: tex('bottom'),
  board: tex('board'), rail: tex('rail'), cells: tex('cells'), parts: tex('parts'), display: tex('display'), food: tex('food'),
};
const idleEls = [...body(true), ...knife(), ...screen(false), ...lid(false)];
const activeEls = [...body(false), ...railWalls(), ...screen(true), ...lid(true), ...food()];
// 可选变体：工作中但上方 1 格有方块（掀盖翻不起来）——掀盖合着，其余同工作中（屏亮、柜灯亮、砧板上汉堡 + 卷饼）
const closedEls = [...body(true), ...screen(true), ...lid(false), ...food()];

json(`models/block/${P}.json`, { parent: 'minecraft:block/block', render_type: 'minecraft:cutout', textures, elements: idleEls });
// render_type 显式再写一遍：不靠 Forge 从父模型继承，以后有人改 parent 也不会退回 solid
json(`models/block/${P}_on.json`, { parent: `${NS}:block/${P}`, render_type: 'minecraft:cutout', textures: { front: tex('front_on'), display: tex('display_on') }, elements: activeEls });
json(`models/block/${P}_on_closed.json`, { parent: `${NS}:block/${P}_on`, render_type: 'minecraft:cutout', elements: closedEls });

// ---------------------------------------------------------------- meta
json('meta.json', {
  key: P, tag: '备餐台 B', order: 22,
  name: '冷藏备餐台',
  tagline: '整格不锈钢三明治冷台：前沿一条砧板（待机放着刀），后面一排带掀盖的冷藏食材格，下面是能看见饮料的冷柜。',
  description: '和炸锅 B「商用炸炉」同一套商用厨房：同样的不锈钢梯度和用法（钢板底色 #87878b，受光倒角 #9a9a9e，最亮的 #aeaeb2 只给前沿圆边、掀盖上沿这种 1 像素高光）、四条带深色橡胶脚的支脚、y10–11 的前沿圆边、和炸炉逐像素相同的柜体侧面，铜红点缀用同一组色值。柜体正面左边是冷柜门（竖钢把手，门上一扇四边有胶条的玻璃小窗，里面看得见橙汁、牛奶、草莓奶昔、可乐几瓶冷饮），右边一列是温度屏、铜铭牌和冷凝器百叶。台面前沿是一条 16×6 的米白色塑料砧板（外圈勾边、一圈导流槽），左端前后错开立着芥末酱、番茄酱两只挤瓶；后面高出 2 像素的冷藏槽里嵌着 2 排 × 3 格食材——前排生菜 / 番茄丁 / 芝士丝，后排红洋葱 / 火腿片 / 米饭，上面压一圈不锈钢格栅框，再盖一块带铜把手的不锈钢掀盖。待机时掀盖合上、砧板上斜放一把黑柄铜铆钉的刀、温度屏熄着、柜里不亮灯；工作中掀盖绕后沿铰链翻起 45°（露出浅色内衬、密封胶条和一道凹筋）、六格食材全露出来，刀收起来，砧板上换成一只汉堡和一条半裹包装纸的卷饼，温度屏亮蓝、右边一列制冷灯 1 秒一闪，冷柜里的灯也亮了。',
  notes: [
    '朝向：正面画在 north，facing=north y=0 / east 90 / south 180 / west 270。不需要热源，直接放地上用。',
    '方块属性：这个模型不是实心整格（底下有支脚空隙、正面内收 1px、前半个台面只到 y=12、格栅镂空），Properties 必须加 .noOcclusion()，建议再加 .isViewBlocking((s,l,p)->false) 和 .isSuffocating((s,l,p)->false)。不加的话相邻方块贴着它的面会被剔除（从支脚缝、砧板上方、格栅里能看穿世界），内部的面取到自身位置的 0 光照，平滑光照下整片发黑。',
    '碰撞箱可以按整格；选择框（getShape）建议用 Shapes.or(Block.box(0,0,0,16,12,16), Block.box(0,12,7,16,16,16))，免得前半边的线框浮在砧板上方 4px；碰撞也可以用同一组形状。翻开的掀盖只是外观，形状不用跟着变。',
    `模型：待机 prep_b；工作中 prep_b_on（parent=待机，继承贴图，显式再写 render_type cutout）。工作中自带一套元素——掀盖和铜把手绕 (y16, z16) 沿 x 轴翻起 45°，格栅框补 4 面朝里的内壁（防低角度从食材格看穿薄框），刀收起，换成汉堡（2 个元素）和卷饼（1 个，绕 y 轴 -22.5° 斜放）；覆盖 front→front_on（柜内亮灯）、display→display_on。`,
    '翻开的掀盖和铜把手最高到 y≈22.4，会伸进上方那一格。厨房里柜台上方常放吊柜 / 抽油烟机，所以另备了 prep_b_on_closed（parent=prep_b_on）：工作中但掀盖合着，其余同工作中。用法：blockstate 加一个 lid_blocked（或在设置 active 时判断上方方块的碰撞形状是否为空），上方有东西就用这个模型；不想多一个属性的话，就在说明里写“上方 1 格请留空”。',
    '温度屏 prep_b_display_on：16×32 竖条，2 帧 × 10 tick（一圈 1 秒，右边一列 3 个像素在深蓝 / 浅蓝之间闪，是制冷灯）。工作中这一面 shade:false，元素上还写了 Forge 的 forge_data {block_light 15, sky_light 15}（暗处也按满光照画；原版忽略这个键）。屏只有 4×3，数字认不出，只当一块亮屏看。',
    `元素：待机 ${idleEls.length} 个，工作中 ${activeEls.length} 个，prep_b_on_closed ${closedEls.length} 个；全部 16×16 贴图。冷藏槽上的格栅框是镂空的，所以 render_type 写 cutout。待机时掀盖合着，冷藏槽和格栅框的 up 面完全被包住，不输出。`,
    '砧板上的刀（待机）和汉堡 / 卷饼（工作中）都是固定装饰；以后想显示正在做的那道菜，可以把汉堡和卷饼 3 个元素去掉，改用方块实体渲染器把成品画在砧板上（砧板顶面 y=12，挤瓶右边 x 1–11、z 1–5 这块空着）。',
    '粒子在游戏里另加：开盖时从食材格 (8,14,11) 冒一点白色冷气，冷柜门缝偶尔飘一缕。',
  ],
  blocks: [
    { label: '冷藏备餐台', pos: [0, 0, 0], idle: { model: P, y: 0 }, active: { model: `${P}_on`, y: 0 } },
  ],
});
console.log('prep B written to', dir, ' elements idle', idleEls.length, 'active', activeEls.length);
