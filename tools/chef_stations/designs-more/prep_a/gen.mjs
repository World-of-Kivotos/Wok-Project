// 备餐台 方案 A「木质备餐台」（农夫乐事田园风）—— 生成模型 JSON + 16×16 贴图 + meta.json。
//   cd <仓库>\tools\chef_stations
//   node designs-more/prep_a/gen.mjs
// 所有贴图都在这里逐像素画（lib/png.mjs），改了重跑即可；产物结构和主 MOD 的 assets/miningdim/ 一致。
//
// 坐标约定（世界坐标，单位 px）：正面 = north（z=0），站在正面看 +x（东）在左边。
// 顶面贴图按“u = x, v = z”直接对应世界坐标（第 0 行是正面一侧）；north 面 u = 16 - x；south 面 u = x。
//
// 第 2 版（按审查意见）：柜体换成农夫乐事橡木橱柜（oak_cabinet）的色阶、侧面改横向木板加框；
// 食材改用农夫乐事 tomato / cabbage / hamburger / beef_patty 的原色；汉堡压到 4×4×4；调料 5 → 3 只、最高 y=21；
// 工作中：东侧抽屉拉出 2px（正面能看出状态），菜刀改成竖直的刀身侧影动画（抬起 → 半落 → 落下），和番茄片同一张动画贴图同步。
//
// 第 3 版（家族 A 一致性，向炸锅 A 靠拢）：黄铜换成炸锅温度计的暗黄铜一组（去掉 #efd97e/#998741，朝上的面最亮）；
// 搪瓷盘藏青边 → 铸铁灰边；擦手巾红 → #c63d27；刀刃钢色封顶 #c3c1bf（去掉 #dedbd7）。
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { makeCanvas, fillRect, setPx, strip, write } from '../../lib/png.mjs';

const dir = path.dirname(fileURLToPath(import.meta.url));
const NS = 'miningdim';
const K = 'prep_a';
const ID = n => `${NS}:block/${K}_${n}`;
const T = n => path.join(dir, 'textures/block', `${K}_${n}.png`);
const json = (rel, o) => { const f = path.join(dir, rel); fs.mkdirSync(path.dirname(f), { recursive: true }); fs.writeFileSync(f, JSON.stringify(o, null, 2) + '\n'); };
/** draw rows of single-char colour keys; '.' or ' ' = leave transparent */
const rows = (c, x0, y0, lines, pal) => lines.forEach((ln, j) => [...ln].forEach((ch, i) => {
  if (ch === '.' || ch === ' ') return;
  if (!(ch in pal)) throw new Error(`colour key "${ch}" missing (row ${j}: ${ln})`);
  setPx(c, x0 + i, y0 + j, pal[ch]);
}));

// ---------------------------------------------------------------- palette
// 柜体：农夫乐事 oak_cabinet_front/side/top 的色阶（字母沿用 FD 贴图里的顺序：c 亮、b 主、a/i 次、f/g/e 暗、d 最深）
//   p/o = oak_cabinet_front_open 里柜膛的两档深色
const CAB = { h: '#b58d50', c: '#a6824d', b: '#967441', i: '#8b7141', a: '#866738', f: '#795c30', g: '#715333', e: '#67502c', d: '#513d24', p: '#584026', o: '#38260c' };
// 砧板：农夫乐事 cutting_board 的 5 档原色
const BRD = { a: '#997140', b: '#8b673c', c: '#805e36', d: '#755732', e: '#674c2c', k: '#4a3620' };
// 刀刃：和炸锅 A 的炸篮钢丝同一组钢色（最亮只到 #c3c1bf，和炸篮上沿一样，不另加更亮的一档）
const STEEL = { d: '#5c5c5c', m: '#868584', l: '#a6a4a2', h: '#c3c1bf' };
// 刀柄：农夫乐事木勺的深两档
const HDL = { l: '#6d5736', d: '#55452e' };
// 黄铜：和炸锅 A 温度计同一组暗黄铜（家族统一）：#bba84c / #d3bb50 只做受光面，端面、侧面用 #665a2c；
// 不再用 #efd97e / #998741（比炸锅的黄铜亮一档，放在一起像两种金属）
const BRS = { d: '#665a2c', m: '#7f7036', l: '#bba84c', h: '#d3bb50' };
// 搪瓷盘：奶白 + 铸铁灰边（和炸锅 A 锅身同一组冷灰；原来的藏青边是整套里唯一的冷色强调，去掉）
const ENM = { w: '#eee8da', s: '#d8d0bf', b: '#494848', n: '#3f3e42' };
// 农夫乐事 tomato.png：g 主红、f 亮红、h/i 果肉籽、d/e 紫调暗部、s/S 蒂
const TOM = { g: '#c0341f', f: '#cf4d3b', h: '#ff8073', i: '#ffc9c9', d: '#941e33', e: '#63203a', s: '#2e922b', S: '#097209' };
// 农夫乐事 cabbage.png 的 5 档绿（亮 → 暗）
const LEAF = { e: '#c7ff87', d: '#aadb74', a: '#7ab244', b: '#4d8c3b', c: '#3c713e' };
// 农夫乐事 hamburger.png 的面包焦金棕 + 一档浅芝麻
const BUN = { b: '#bc8927', c: '#a27924', d: '#8c661e', a: '#654b17', s: '#e9cf8f' };
// 农夫乐事 beef_patty.png
const PAT = { l: '#8e523c', m: '#673728', d: '#4e2719', k: '#3f2116' };
const CHS = { m: '#f0c437', d: '#d69a1e' };
// 调料
const MUS = { d: '#a8862a', m: '#d9b430', l: '#f2d35a' };
const GLS = { w: '#e8e4dc', g: '#c4c0b8' };
const PEP = { k: '#2d2d32', m: '#3f3e42', s: '#727272' };
const LBL = '#f0e9d8';
// 擦手巾：红白格。红用炸锅 A 温度计球泡的 #c63d27（烤炉 A 的莓果馅也是这个红），整套只有一种“家族红”
const TWL = { r: '#c63d27', p: '#e48c78', w: '#f0e9d8' };

// ---------------------------------------------------------------- textures: cabinet
// 正面（north）：整张就是站在正面看到的样子。第 0–1 行 = 台面板前沿（z=0），第 2–15 行 = 柜身（缩进 1px），
// 第 0 / 15 列 = 两根门框立柱（z=0）。左半（第 1–7 列）= 东侧抽屉/柜门，右半（第 8–14 列）= 西侧。
// 版式照农夫乐事橱柜：亮色 c 做框、b/a/i 做板面、中缝 c-g-f-c、门钮靠中缝；最深的 d 只用在台面下那 1px 阴影和中缝里。
const FRONT = [
  'abccccccccccccba',   // 0 台面前沿（受光）
  'baabibaaabiabaab',   // 1 台面前沿下半
  'gdefffeddefffedg',   // 2 台面挑出 1px 的阴影（1px）
  'cehhhhcggchhhhgc',   // 3 抽屉上沿受光
  'ceabibcfgcbibagc',   // 4 抽屉面（拉手在第 3–5 / 10–12 列）
  'ceaeeecgfceeeagc',   // 5 拉手下的投影
  'bfaaaabffbaaaagb',   // 6 抽屉下沿
  'cggeeegddgeeeggc',   // 7 抽屉和柜门之间的缝
  'cechhhcggchhhcgc',   // 8 柜门上沿受光
  'ceabbicffcbbiagc',   // 9
  'ceaibbcgfcbibagc',   // 10 门钮（第 6 / 9 列）
  'beabbieggebbbagb',   // 11 门钮下的投影
  'ceabiacffcabiagc',   // 12
  'cfaaaabffbaaaagc',   // 13 柜门下沿
  'bggeeegddgeeeggb',   // 14
  'agggeeeeeeeeggga',   // 15 踢脚
];
{
  const c = makeCanvas(16, 16);
  rows(c, 0, 0, FRONT, CAB);
  write(c, T('front'));
}
// 工作中的正面：东侧抽屉拉出去了，抽屉口（第 1–7 列、第 3–6 行）露出柜膛的深色（农夫乐事 oak_cabinet_front_open 的两档）。
// 拉出的抽屉只有 3px 高（y 9–12），所以第 3 行那 1px 深色缝在正面平视时也看得见。
{
  const c = makeCanvas(16, 16);
  const open = FRONT.map((r, j) => (j >= 3 && j <= 6 ? r[0] + (j === 3 ? 'ooooooo' : 'oppppppo'.slice(0, 7)) + r.slice(8) : r));
  rows(c, 0, 0, open, CAB);
  write(c, T('front_on'));
}
// 侧面 / 背面（east / west / south，默认 UV）：第 0–1 行台面侧沿，第 2 行台面下的缝，下面是农夫乐事橱柜那样的横向木板 + 一圈框
{
  const c = makeCanvas(16, 16);
  rows(c, 0, 0, [
    'abccccccccccccba',
    'baaibaabbiabaaib',
    'gfgffggfggffgfgg',
    'cbccbbccccbcccbc',
    'caibaaaibbbbiaac',
    'cabbbbibbbaabbac',
    'cfaaibbbaaaibafc',
    'cgffggfgfggffggc',
    'cbcccbbcccccbcbc',
    'cabbiaaabbibbbac',
    'cfaibbbbiaabbafc',
    'cggfggffggfgggfc',
    'cbccbcccbbcccbbc',
    'caabbbiaaabbiaac',
    'cfabbaabbbbiaafc',
    'abccccccccccccba',
  ], CAB);
  write(c, T('side'));
}
// 台面顶面（u = x, v = z；第 0 行是正面）：和农夫乐事橱柜顶面一样带一圈框，里面三条横向木板
{
  const c = makeCanvas(16, 16);
  rows(c, 0, 0, [
    'abccccccccccccba',
    'cbaabibbbaabbiac',
    'caibbbbaibbbbbac',
    'cbbbiabbbbbaibbc',
    'cabbbbbiabbbbbac',
    'cggffggfgffggfgc',
    'cbccbbcccbccccbc',
    'caibaabbbbiaabac',
    'cbbbbbiaabbbbiac',
    'cfabbbbbbaibbbfc',
    'cgfggfgffggfggfc',
    'cbcccbbcccccbcbc',
    'caabbiabbbbaibac',
    'cbbiabbbbiabbbac',
    'cfaabbbiabbbaafc',
    'abccccccccccccba',
  ], CAB);
  write(c, T('top'));
}
// 底面（down 默认 UV：v = 16 - z，第 15 行 = 正面挑出的那 1px 台面底）
{
  const c = makeCanvas(16, 16, CAB.g);
  fillRect(c, 1, 1, 14, 14, CAB.e);
  fillRect(c, 0, 15, 16, 1, CAB.f);
  write(c, T('bottom'));
}

// ---------------------------------------------------------------- textures: board atlas（砧板 / 待机菜刀 / 调料架板）
// 区域（u,v 像素）：
//   砧板顶 7×9 (0,0)  前后沿 7×1 (0,9)  左右沿 9×1 (0,10)
//   刀刃顶 2×5 (8,0)  刀背侧 5×1 (8,6)  刃口侧 5×1 (8,7，刀尖那格透明)  刀柄顶 1×3 (12,0)  刀柄侧 3×1 (13,0)  刀柄端 1×1 (12,3)
//   调料架顶 14×4 (0,11)  前沿 14×1 (0,15)  侧沿 4×1 (10,9)
{
  const c = makeCanvas(16, 16);
  rows(c, 0, 0, [               // 砧板顶：u = x-8，v = z-1（第 0 行靠正面）；木纹顺着 z
    'eddddde',
    'dbacbad',
    'dbacbbd',
    'dabcbad',
    'dbacabd',
    'dbbcbad',
    'dabcbad',
    'dbakbad',                  // 挂孔
    'eddddde',
  ], BRD);
  rows(c, 0, 9, ['eddddde'], BRD);
  rows(c, 0, 10, ['eddddddde'], BRD);
  // 刀刃顶：第 8 列 = 刀背（西），第 9 列 = 刃口（东）；第 0 行靠刀柄（护手），第 4 行刀尖（刃口那格透明）
  rows(c, 8, 0, ['dd', 'mh', 'lh', 'lh', 'l.'], STEEL);
  rows(c, 8, 6, ['mmmml'], STEEL);           // 西侧面 = 刀背（u 从护手到刀尖）
  rows(c, 8, 7, ['.hhhh'], STEEL);           // 东侧面 = 刃口（east 面 u0 对着刀尖，所以第一格透明）
  rows(c, 12, 0, ['l', 'm', 'l'], { l: HDL.l, m: BRS.l });   // 刀柄顶 + 黄铜铆钉
  rows(c, 13, 0, ['dmd'], { d: HDL.d, m: BRS.m });
  rows(c, 12, 3, ['d'], HDL);
  // 调料架（和台面同一套橡木色，前沿受光）
  rows(c, 0, 11, [
    'cccccccccccccc',
    'baabibbaabbiab',
    'abbiabbbbaibba',
    'gggfggggfggggg',
  ], CAB);
  rows(c, 0, 15, ['aaiaaaaaaiaaaa'], CAB);
  rows(c, 10, 9, ['bbba'], CAB);
  write(c, T('board'));
}

// ---------------------------------------------------------------- textures: drawer（工作中拉出来的东侧抽屉）
// 区域：抽屉面 7×3 (0,0)  抽屉顶 7×2 (0,3)（第 0 行 = 抽屉面板上沿，第 1 行 = 抽屉里：深色 + 一把汤勺、一卷白布）
//       两侧 2×3 (7,0)  底 7×2 (0,5)
{
  const c = makeCanvas(16, 16);
  rows(c, 0, 0, ['chhhhhg', 'cbibbag', 'faeeeag'], CAB);   // 第 2 行第 2–4 列 = 拉手投影
  rows(c, 0, 3, ['chhhhhg'], CAB);
  rows(c, 0, 4, ['ommowwo'], { o: CAB.o, m: STEEL.l, w: LBL });
  rows(c, 7, 0, ['cb', 'ba', 'ag'], CAB);
  rows(c, 0, 5, ['eeeeeee', 'eeeeeee'], CAB);
  write(c, T('drawer'));
}

// ---------------------------------------------------------------- textures: ware atlas（调料瓶 / 黄铜件 / 搪瓷盘 / 擦手巾）
// 区域：番茄酱 侧 2×3 (0,0) 顶 2×2 (0,3)；芥末 侧 (2,0) 顶 (2,3)；盐胡椒双格罐 正/背 (4,0) 东=胡椒 (6,0) 西=盐 (8,0) 顶 (4,3)
//       瓶嘴 番茄酱 侧/顶 (6,3)/(7,3)  芥末 (6,4)/(7,4)
//       拉手 正面 3×1 (12,0) 顶 3×1 (12,1) 端 1×1 (15,0)；门钮 正面 (12,2) 侧 (13,2)
//       盘子顶 6×6 (0,7) 盘沿 6×1 (0,13)；毛巾 3×5 (7,7) 搭边 3×1 (7,13) 厚度 1×5 (10,7)
{
  const c = makeCanvas(16, 16);
  rows(c, 0, 0, ['fg', 'ww', 'gd'], { ...TOM, w: LBL });          // 番茄酱瓶：农夫乐事番茄的红
  rows(c, 0, 3, ['fg', 'gd'], TOM);
  rows(c, 2, 0, ['lm', 'ww', 'md'], { ...MUS, w: LBL });          // 芥末瓶
  rows(c, 2, 3, ['lm', 'md'], MUS);
  // 盐胡椒双格罐：东半边胡椒、西半边盐；north 面 u0 对着东，所以左格是胡椒
  rows(c, 4, 0, ['hl', 'mw', 'kg'], { h: STEEL.h, l: STEEL.l, m: PEP.m, k: PEP.k, w: GLS.w, g: GLS.g });
  rows(c, 6, 0, ['hl', 'ms', 'km'], { h: STEEL.h, l: STEEL.l, m: PEP.m, s: PEP.s, k: PEP.k });
  rows(c, 8, 0, ['hl', 'ww', 'gg'], { h: STEEL.h, l: STEEL.l, w: GLS.w, g: GLS.g });
  rows(c, 4, 3, ['hd', 'lh'], STEEL);                              // 罐盖（带孔）
  rows(c, 6, 3, ['de'], TOM);                                      // 番茄酱瓶嘴 侧 / 顶
  rows(c, 6, 4, ['dm'], MUS);                                      // 芥末瓶嘴
  // 黄铜件按炸锅 A 温度计的写法：朝上的面最亮（温度计顶帽 #d3bb50/#bba84c），正面 #bba84c 中间 1px 受光，端面、侧面压暗到 #665a2c
  rows(c, 12, 0, ['lhld'], BRS);            // 拉手正面 + 端
  rows(c, 12, 1, ['hhhm'], BRS);            // 拉手顶/底
  rows(c, 12, 2, ['hd'], BRS);              // 门钮正面（也给门钮顶/底用）/ 侧
  rows(c, 0, 7, [                           // 搪瓷盘顶（八角圆盘）
    '.bbbb.',
    'bwwwwb',
    'bwsswb',
    'bwsswb',
    'bwwwwb',
    '.bbbb.',
  ], ENM);
  rows(c, 0, 13, ['.bbbb.'], ENM);          // 盘沿侧面：铸铁灰边（两端透明，和顶面的八角对上）
  rows(c, 7, 7, ['rpr', 'pwp', 'rpr', 'pwp', 'w.w'], TWL);   // 擦手巾 3×5（最下一行是流苏）
  rows(c, 7, 13, ['rpr'], TWL);
  rows(c, 10, 7, ['r', 'p', 'r', 'p', 'w'], TWL);
  write(c, T('ware'));
}

// ---------------------------------------------------------------- textures: food atlas（工作中）
// 区域：汉堡 正/背面 4×4 (0,0)  东/西面 4×4 (0,4)  顶 4×4 (4,0)
//       半个番茄 切面 3×2 (8,0)  背面 3×2 (8,2)  两侧 2×2 (11,0)  顶 3×2 (11,2)
//       砧板上的生菜 顶 2×3 (14,0)  前后 2×1 (14,3)  两侧 3×1 (13,4)
{
  const c = makeCanvas(16, 16);
  const bp = { c: BUN.c, b: BUN.b, d: BUN.d, a: BUN.a, s: BUN.s, l: LEAF.a, L: LEAF.b, e: LEAF.d, t: TOM.g, Y: CHS.m, y: CHS.d, m: PAT.m, p: PAT.l, k: PAT.d };
  // 汉堡侧面（自上而下：顶面包 / 生菜 / 肉饼 / 底面包，每层 1px 横向成条），芝士只在两个对角从生菜层垂到肉饼层。
  // north/south 共用一块、east/west 共用一块，芝士角落在 (东,前) 和 (西,后) 两个对角上，转过角也接得上。
  rows(c, 0, 0, ['cbbc', 'Ylle', 'ymmk', 'dccd'], bp);
  rows(c, 0, 4, ['cbbc', 'ellY', 'kmmy', 'dccd'], bp);
  rows(c, 4, 0, ['dccd', 'cbsc', 'csbc', 'dccd'], bp);   // 顶：圆角（四角暗一档）+ 两粒芝麻
  rows(c, 8, 0, ['gfg', 'fhf'], TOM);                    // 切面：外圈红皮，中间果肉 + 籽
  rows(c, 8, 2, ['fgg', 'gdd'], TOM);
  rows(c, 11, 0, ['fg', 'dd'], TOM);
  rows(c, 11, 2, ['fhf', 'gsd'], TOM);                   // 顶：靠切面一行露果肉，后面是皮和蒂
  rows(c, 14, 0, ['de', 'ab', 'ca'], LEAF);
  rows(c, 14, 3, ['ab'], LEAF);
  rows(c, 13, 4, ['bac'], LEAF);
  write(c, T('food'));
}

// ---------------------------------------------------------------- textures: chop animation（工作中）
// 一张动画贴图管两样东西，保证同步：
//   a) 番茄片层（俯视）：元素 [8,17,1]-[15,17.25,6] 的 up 面，uv [8,1,15,6]，u = x、v = z。
//   b) 菜刀侧影（竖直平面）：元素 [8,17,6]-[16,21,6]，north 面 uv [0,10,8,14]（u0 = 东端 = 刀柄），south 面左右翻转。
// 节奏：抬起 → 半落 → 落下（落下那帧多出一片，果肉亮一档）→ 回到半落 → 抬起，切 3 片后停一下，再把片子推到砧板西边（盘子那侧）。
// 番茄片：外圈用番茄的紫调暗红 d，果肉 f，两粒籽 h；刚落下的那片果肉亮一档（h + i）
const SLICE = ['.dd.', 'dfhd', 'dhfd', '.dd.'];
const SLICE_FRESH = ['.dd.', 'dhid', 'dihd', '.dd.'];
const SLICE_AT = [[11, 2], [8, 2], [9, 1]];         // 第 1、2、3 片左上角 (x, z)：前两片并排，第 3 片压在两片中间
const PUSHED_AT = [[10, 2], [8, 3], [8, 1]];        // 推向砧板西沿（盘子那侧），下一圈从 0 片开始
const KNIFE = {
  // 8×4，第 0 列 = 刀柄端（东），第 7 列 = 刀尖；第 3 行贴着砧板
  up:   ['hr......', '.hhkM...', '...kWMM.', '.....WWT'],   // 台阶两两相连（斜视时刀柄不会像飘在空中）
  mid:  ['........', 'hrhk....', '...kMMM.', '....WWWT'],
  down: ['........', '........', 'hrhkMMM.', '...kWWWT'],
};
const KPAL = { h: HDL.d, r: BRS.l, k: STEEL.d, M: STEEL.m, W: STEEL.l, T: STEEL.h };   // 刀柄用深的那档，压在木台面和生菜前面也分得清；刃 #a6a4a2、刀尖受光 #c3c1bf（炸篮钢丝的两档亮色）
const chopFrame = (pose, n, { fresh = false, pushed = false } = {}) => {
  const c = makeCanvas(16, 16);
  for (let i = 0; i < n; i++) {
    const [x, z] = (pushed ? PUSHED_AT : SLICE_AT)[i];
    rows(c, x, z, fresh && i === n - 1 ? SLICE_FRESH : SLICE, TOM);
  }
  rows(c, 0, 10, KNIFE[pose], KPAL);
  return c;
};
// 序列：[姿势, 片数, 选项, tick]
const SEQ = [
  ['up', 0, {}, 5], ['mid', 0, {}, 1], ['down', 1, { fresh: true }, 4], ['mid', 1, {}, 2],
  ['up', 1, {}, 3], ['mid', 1, {}, 1], ['down', 2, { fresh: true }, 4], ['mid', 2, {}, 2],
  ['up', 2, {}, 3], ['mid', 2, {}, 1], ['down', 3, { fresh: true }, 4], ['mid', 3, {}, 2],
  ['up', 3, {}, 9], ['up', 3, { pushed: true }, 4],
];
{
  const frames = [], keyIdx = new Map(), list = [];
  for (const [pose, n, opt, time] of SEQ) {
    const key = `${pose}/${n}/${!!opt.fresh}/${!!opt.pushed}`;
    if (!keyIdx.has(key)) { keyIdx.set(key, frames.length); frames.push(chopFrame(pose, n, opt)); }
    list.push({ index: keyIdx.get(key), time });
  }
  write(strip(frames), T('chop'));
  json(`textures/block/${K}_chop.png.mcmeta`, { animation: { frametime: 2, frames: list } });
  var CHOP_TICKS = list.reduce((s, f) => s + f.time, 0), CHOP_FRAMES = frames.length;
}

// ---------------------------------------------------------------- models
const f = (texture, uv, extra = {}) => (uv ? { uv, texture, ...extra } : { texture, ...extra });
const el = (name, from, to, faces, rotation) => (rotation ? { name, from, to, rotation, faces } : { name, from, to, faces });
const N = { cullface: 'north' };

const cabinet = [
  el('worktop', [0, 14, 0], [16, 16, 16], {
    north: f('#front', [0, 0, 16, 2], N),
    south: f('#side', [0, 0, 16, 2], { cullface: 'south' }),
    east: f('#side', [0, 0, 16, 2], { cullface: 'east' }),
    west: f('#side', [0, 0, 16, 2], { cullface: 'west' }),
    up: f('#top', [0, 0, 16, 16], { cullface: 'up' }),
    down: f('#bottom', [0, 0, 16, 16]),
  }),
  el('carcass', [0, 0, 1], [16, 14, 16], {
    north: f('#front', [0, 2, 16, 16]),
    south: f('#side', [0, 2, 16, 16], { cullface: 'south' }),
    east: f('#side', [0, 2, 15, 16], { cullface: 'east' }),
    west: f('#side', [1, 2, 16, 16], { cullface: 'west' }),
    down: f('#bottom', [0, 0, 16, 15], { cullface: 'down' }),
  }),
  el('stile east', [15, 0, 0], [16, 14, 1], {
    north: f('#front', [0, 2, 1, 16], N),
    east: f('#side', [15, 2, 16, 16], { cullface: 'east' }),
    west: f('#side', [0, 2, 1, 16]),
    down: f('#bottom', [15, 15, 16, 16], { cullface: 'down' }),
  }),
  el('stile west', [0, 0, 0], [1, 14, 1], {
    north: f('#front', [15, 2, 16, 16], N),
    west: f('#side', [0, 2, 1, 16], { cullface: 'west' }),
    east: f('#side', [15, 2, 16, 16]),
    down: f('#bottom', [0, 15, 1, 16], { cullface: 'down' }),
  }),
];

/** small brass bar sticking out of a front face that sits at z = zf (bar from zf-0.75 to zf) */
const pull = (name, x0, x1, y0, zf = 1) => el(name, [x0, y0, zf - 0.75], [x1, y0 + 1, zf], {
  north: f('#ware', [12, 0, 15, 1]), up: f('#ware', [12, 1, 12 + (x1 - x0), 1.75]),
  down: f('#ware', [12, 1, 12 + (x1 - x0), 1.75]),
  east: f('#ware', [15, 0, 15.75, 1]), west: f('#ware', [15, 0, 15.75, 1]),
});
const knob = (name, x0) => el(name, [x0, 5, 0.25], [x0 + 1, 6, 1], {
  north: f('#ware', [12, 2, 13, 3]), up: f('#ware', [12, 2, 13, 2.75]), down: f('#ware', [13, 2, 14, 2.75]),
  east: f('#ware', [13, 2, 13.75, 3]), west: f('#ware', [13, 2, 13.75, 3]),
});
const towel = [
  // 擦手巾搭在西侧抽屉拉手上
  el('towel', [2.5, 6.5, 0], [5.5, 11.5, 0.25], {
    north: f('#ware', [7, 7, 10, 12], N), south: f('#ware', [7, 7, 10, 12]),
    east: f('#ware', [10, 7, 10.25, 12]), west: f('#ware', [10, 7, 10.25, 12]),
  }),
  el('towel fold', [2.5, 11.5, 0], [5.5, 12.25, 1], {
    north: f('#ware', [7, 13, 10, 13.75], N), up: f('#ware', [7, 13, 10, 14]), down: f('#ware', [7, 13, 10, 14]),
    east: f('#ware', [10, 7, 11, 7.75]), west: f('#ware', [10, 7, 11, 7.75]),
  }),
];
const hardwareCommon = [pull('drawer pull west', 3, 6, 11), knob('door knob east', 9), knob('door knob west', 6), ...towel];
const hardwareIdle = [pull('drawer pull east', 10, 13, 11), ...hardwareCommon];
// 工作中：东侧抽屉拉出 2px（z -1..1，比台面前沿多出 1px），抽屉 3px 高，上面留出 1px 深色抽屉口
const hardwareActive = [
  el('drawer (pulled out)', [8, 9, -1], [15, 12, 1], {
    north: f('#drawer', [0, 0, 7, 3]),
    up: f('#drawer', [0, 3, 7, 5]),
    down: f('#drawer', [0, 5, 7, 7]),
    east: f('#drawer', [7, 0, 9, 3]), west: f('#drawer', [7, 0, 9, 3]),
  }),
  pull('drawer pull east (out)', 10, 13, 10, -1),
  ...hardwareCommon,
];

const board = el('cutting board', [8, 16, 1], [15, 17, 10], {
  up: f('#board', [0, 0, 7, 9]),
  north: f('#board', [0, 9, 7, 10]), south: f('#board', [0, 9, 7, 10]),
  east: f('#board', [0, 10, 9, 11]), west: f('#board', [0, 10, 9, 11]),
});
const plate = el('plate', [1, 16, 2], [7, 17, 8], {
  up: f('#ware', [0, 7, 6, 13]),
  north: f('#ware', [0, 13, 6, 14]), south: f('#ware', [0, 13, 6, 14]),
  east: f('#ware', [0, 13, 6, 14]), west: f('#ware', [0, 13, 6, 14]),
});
const shelf = el('spice shelf', [1, 16, 11], [15, 17, 15], {
  up: f('#board', [0, 11, 14, 15]),
  north: f('#board', [0, 15, 14, 16]), south: f('#board', [0, 15, 14, 16]),
  east: f('#board', [10, 9, 14, 10]), west: f('#board', [10, 9, 14, 10]),
});
// 调料：3 只，间隔 2px，罐高 3px（y 17–20），瓶嘴 1px（到 y 21）
const JY = 17, JH = 3;
const jar = (name, x, side, top, { east, west, south } = {}) => el(name, [x, JY, 12], [x + 2, JY + JH, 14], {
  north: f('#ware', side), south: f('#ware', south || side),
  east: f('#ware', east || side), west: f('#ware', west || side), up: f('#ware', top),
});
const nozzle = (name, x, u, v) => el(name, [x + 0.5, JY + JH, 12.5], [x + 1.5, JY + JH + 1, 13.5], {
  north: f('#ware', [u, v, u + 1, v + 1]), south: f('#ware', [u, v, u + 1, v + 1]),
  east: f('#ware', [u, v, u + 1, v + 1]), west: f('#ware', [u, v, u + 1, v + 1]), up: f('#ware', [u + 1, v, u + 2, v + 1]),
});
const jars = [
  jar('ketchup', 11, [0, 0, 2, 3], [0, 3, 2, 5]), nozzle('ketchup nozzle', 11, 6, 3),
  jar('mustard', 7, [2, 0, 4, 3], [2, 3, 4, 5]), nozzle('mustard nozzle', 7, 6, 4),
  // 盐胡椒双格罐：south 面左右翻转，让胡椒格在背面看也在东边
  jar('salt & pepper', 3, [4, 0, 6, 3], [4, 3, 6, 5], { south: [6, 0, 4, 3], east: [6, 0, 8, 3], west: [8, 0, 10, 3] }),
];

/** knife lying along +z (handle at z0, blade towards +z), centred on x; no down faces (it lies on the board) */
function knife(x, y, z0, rotation) {
  const bx0 = x - 1, hx0 = x - 0.5;
  return [
    el('knife handle', [hx0, y, z0], [hx0 + 1, y + 1, z0 + 3], {
      up: f('#board', [12, 0, 13, 3]),
      east: f('#board', [13, 0, 16, 1]), west: f('#board', [13, 0, 16, 1]),
      north: f('#board', [12, 3, 13, 4]), south: f('#board', [12, 3, 13, 4]),
    }, rotation),
    el('knife blade', [bx0, y, z0 + 3], [bx0 + 2, y + 0.5, z0 + 8], {
      up: f('#board', [8, 0, 10, 5]),
      west: f('#board', [8, 6, 13, 6.5]), east: f('#board', [8, 7, 13, 7.5]),
      north: f('#board', [8, 0, 10, 0.5]), south: f('#board', [8, 4, 10, 4.5]),
    }, rotation),
  ];
}
// 待机：砧板上斜放一把刀（绕 y 轴 -45°），盘子空着。旋转中心选在让 8 个角都落在砧板 x 8–15、z 1–10 里
const KX = 11.4, KO = [11.4, 17, 5.5];
const idleKnife = knife(KX, 17, 1, { origin: KO, axis: 'y', angle: -45 });
{ // 自检：旋转后刀的外框
  const a = -45 * Math.PI / 180, cs = Math.cos(a), sn = Math.sin(a);
  let x0 = 99, x1 = -99, z0 = 99, z1 = -99;
  for (const e of idleKnife) for (const X of [e.from[0], e.to[0]]) for (const Z of [e.from[2], e.to[2]]) {
    const dx = X - KO[0], dz = Z - KO[2], rx = KO[0] + dx * cs + dz * sn, rz = KO[2] - dx * sn + dz * cs;
    x0 = Math.min(x0, rx); x1 = Math.max(x1, rx); z0 = Math.min(z0, rz); z1 = Math.max(z1, rz);
  }
  console.log(`idle knife footprint x ${x0.toFixed(2)}..${x1.toFixed(2)}  z ${z0.toFixed(2)}..${z1.toFixed(2)}  (board x 8..15, z 1..10)`);
  if (x0 < 8 || x1 > 15 || z0 < 1 || z1 > 10) throw new Error('idle knife hangs off the board');
}

// 工作中
const burger = el('burger', [2, 17, 3], [6, 21, 7], {      // 4×4×4，放在盘子中间，四周露 1px 铸铁灰盘边
  north: f('#food', [0, 0, 4, 4]), south: f('#food', [0, 0, 4, 4]),
  east: f('#food', [0, 4, 4, 8]), west: f('#food', [0, 4, 4, 8]),
  up: f('#food', [4, 0, 8, 4]),
});
const prepped = [
  el('half tomato', [10, 17, 6.5], [13, 19, 8.5], {       // 切面朝前（对着刀），后面是皮和蒂
    north: f('#food', [8, 0, 11, 2]), south: f('#food', [8, 2, 11, 4]),
    east: f('#food', [11, 0, 13, 2]), west: f('#food', [11, 0, 13, 2]),
    up: f('#food', [11, 2, 14, 4]),
  }),
  el('lettuce', [13, 17, 6.5], [15, 18, 9.5], {
    up: f('#food', [14, 0, 16, 3]),
    north: f('#food', [14, 3, 16, 4]), south: f('#food', [14, 3, 16, 4]),
    east: f('#food', [13, 4, 16, 5]), west: f('#food', [13, 4, 16, 5]),
  }),
  el('tomato slices', [8, 17, 1], [15, 17.25, 6], { up: f('#chop', [8, 1, 15, 6]) }),
  // 菜刀侧影：0 厚竖直平面，正反两面（背面左右翻转，刀柄始终在东端）
  el('knife (rocking)', [8, 17, 6], [16, 21, 6], {
    north: f('#chop', [0, 10, 8, 14]), south: f('#chop', [8, 10, 0, 14]),
  }),
];

const RT = 'minecraft:cutout_mipped';
const textures = {
  particle: ID('front'),
  front: ID('front'), side: ID('side'), top: ID('top'), bottom: ID('bottom'),
  board: ID('board'), ware: ID('ware'),
};
const base = [...cabinet, board, plate, shelf, ...jars];
const idle = [...base, ...hardwareIdle, ...idleKnife];
const active = [...base, ...hardwareActive, burger, ...prepped];
const maxY = Math.max(...active.map(e => e.to[1]), ...idle.map(e => e.to[1]));

json(`models/block/${K}.json`, {
  parent: 'minecraft:block/block',
  render_type: RT,
  textures,
  // 物品图标：模型最高到 y=21，比方块高 5px，GUI 里往下挪一点、略缩，免得顶出格子
  display: {
    gui: { rotation: [30, 225, 0], translation: [0, -1.25, 0], scale: [0.58, 0.58, 0.58] },
  },
  elements: idle,
});
json(`models/block/${K}_on.json`, {
  parent: `${NS}:block/${K}`,
  render_type: RT,
  textures: { front: ID('front_on'), drawer: ID('drawer'), food: ID('food'), chop: ID('chop') },
  elements: active,
});

json('meta.json', {
  key: K,
  tag: '备餐台 A',
  order: 21,
  name: '木质备餐台',
  tagline: '橡木厨房台，柜体取农夫乐事橡木橱柜的色阶：台面上砧板、菜刀、搪瓷盘，后沿三只调料瓶；工作时抽屉拉开、菜刀摇切番茄、盘里摞起汉堡。',
  description: '整格方块，台面与炉灶顶面齐平（y=16）。台面板 2px 厚，正面挑出 1px；下面的柜身缩进 1px，两根门框立柱包住两侧。正面版式照农夫乐事橱柜：亮色框、中缝、门钮靠中间；上排两只抽屉，下排两扇柜门，配暗黄铜拉手和门钮（和炸锅 A 温度计同一组黄铜），西侧拉手上搭一条红白格擦手巾。侧面是橱柜那样的横向木板加一圈框。台面上：东侧（正面看左边）是一块农夫乐事砧板，西侧是一只铸铁灰边的白搪瓷盘，后沿一条加高的调料架上放着番茄酱、芥末两只挤酱瓶和一只盐胡椒双格罐。待机时砧板上斜放一把菜刀，盘子空着。工作中：东侧抽屉拉出 2px，上方露出 1px 深色抽屉口，正面平视也能看出状态；盘子上摞起一只 4×4×4 的农夫乐事配色汉堡；砧板后面放着半个番茄和一把生菜；菜刀竖起来，在番茄前面一抬一落地摇切，每落一次，前面就多一片番茄片，切满 3 片后被推向盘子那侧（菜刀和番茄片是同一张动画贴图）。',
  notes: [
    '不需要热源，放在任何地方都能用。台面高度和农夫乐事炉灶、橱柜一致（y=16），可以接成一排。',
    '台面上的摆件最高到 y=21（汉堡、瓶嘴、抬起的刀柄），比方块高 5px：上方一格要留空，放吊柜、架子或任何方块都会穿模。',
    `两个模型：待机 ${idle.length} 个元素，工作中 ${active.length} 个。工作中继承待机模型的贴图，再把 #front 换成带抽屉口的 prep_a_front_on，并加入 #drawer、#food、#chop 三张贴图。`,
    `动画：prep_a_chop 共 ${CHOP_FRAMES} 帧，mcmeta 用 frames 列表逐帧给时长，一圈 ${CHOP_TICKS} tick（约 ${(CHOP_TICKS / 20).toFixed(1)} 秒），不插值。菜刀侧影和番茄片在同一张贴图上，所以一定同步。动画相位是全局的，所有备餐台一起动；代码里只在开始和结束一段工作时切换 active，不要每出一道菜就切一次（每切一次都会重建区块网格）。`,
    '朝向：正面画在 north，facing=north 时 y=0，east 90，south 180，west 270。',
    '和炸锅 A、烤炉 A 同一套（家族 A）：金属只有三种——冷灰铸铁（盘边、胡椒罐，和炸锅锅身同一条农夫乐事锅具灰）、亮钢（刀、罐盖，和炸篮钢丝同一组，最亮 #c3c1bf）、暗黄铜（拉手、门钮、刀柄铆钉，和炸锅温度计同一组，朝上的面最亮）；木头都取农夫乐事橱柜 / 砧板 / 木勺；食材以外的强调红（擦手巾）统一用炸锅温度计的 #c63d27。',
    'render_type 用 cutout_mipped（工作中的模型也显式写了）：柜体整面不透明，远处和农夫乐事炉灶一样有 mipmap；镂空只有盘子八角、刀尖、菜刀侧影和番茄片。菜刀侧影只有 1–2px 粗，远处 mipmap 后可能变细、断开，进游戏如果觉得不好看就改回 cutout。',
    '方块要 noOcclusion()：主要是因为旋转过的待机菜刀面取的是方块自身位置的光，不透光的整格方块那里是 0 级光，会画成黑的。',
    '光照（进游戏要测）：1.20.1 里只要碰撞箱是严格整格，所有轴对齐的面都取它朝向那一侧邻格的光，包括缩进的柜门面和台面摆件。所以旁边是不透光整格（农夫乐事炉灶、橱柜）时，摆件朝那一侧的面会发暗或发黑；上方有方块时摆件顶面也会暗。如果实测发黑，把碰撞箱改成不是严格整格（例如整格再并上调料架那条 [1,16,11]-[15,21,15]），同时重写 propagatesSkylightDown 返回 false，免得天光直接穿过柜子。',
    '剔除：贴着方块边界的面都写了 cullface，但 noOcclusion 的方块不会遮挡邻居，所以两台备餐台并排时相贴的侧面都照画，只有贴着不透光整格时才会被剔除。在意面数可以重写 skipRendering（只对左右同朝向的同种方块剔除侧面，不能剔除缩进 1px 的正面）。',
    '物品模型：display.gui 往下挪 1.25px、缩到 0.58（模型比方块高 5px），预览里看不到，需要进游戏看物品栏图标。',
  ],
  hires: false,
  namespace: NS,
  blocks: [
    { label: '备餐台', pos: [0, 0, 0], idle: { model: K, y: 0 }, active: { model: `${K}_on`, y: 0 } },
  ],
});
console.log(`${K} written: idle ${idle.length} elements, active ${active.length} elements, max y ${maxY}, chop ${CHOP_FRAMES} frames / ${CHOP_TICKS} ticks`);
