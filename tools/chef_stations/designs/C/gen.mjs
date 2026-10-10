// 方案 C「中式炸灶」：砖砌小灶 + 黄泥灶面 + 双耳深铁锅 + 竹柄笊篱，灶口见炭火。
//   node designs/C/gen.mjs        写出 designs/C/{meta.json, models/block/*.json, textures/block/*.png(.mcmeta)}
// 全部贴图 16×16（动画为 16 宽竖条），逐像素手绘；砖/砂浆/锅灰/火/草编色取农夫乐事原色，油的主色取随性乐事炸锅的油色，
// 另加一条黄泥色和几种炉膛暗色（新增色的完整清单见 meta.json 的 notes）。
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { makeCanvas, setPx, strip, write } from '../../lib/png.mjs';

const dir = path.dirname(fileURLToPath(import.meta.url));
const NS = 'miningdim';
const ID = n => `${NS}:block/${n}`;
const T = n => path.join(dir, 'textures/block', n + '.png');
const json = (rel, o) => { const f = path.join(dir, rel); fs.mkdirSync(path.dirname(f), { recursive: true }); fs.writeFileSync(f, JSON.stringify(o, null, 2) + '\n'); };

// ---------------------------------------------------------------- palette (one character per colour)
const PAL = {
  // brick, exactly Farmer's Delight stove bricks
  1: '#733f31', 2: '#7c4536', 3: '#8f503f', 4: '#9b5643', 5: '#b1624d',
  // mortar (FD)
  g: '#8b6e67', h: '#a2867d',
  // yellow clay plaster (黄泥), new ramp sitting between FD wood and FD mortar
  j: '#6e5437', k: '#8a6c48', l: '#a2845c', m: '#b4976e', n: '#c5aa80',
  // soot / firebox (FD stove front darks)
  o: '#190804', r: '#2a120b', p: '#4a2116', q: '#5f341f',
  // fire (FD stove_front_on) + one dim ember red
  w: '#8a3311', s: '#c35d1b', t: '#ed8c0e', u: '#ffd800',
  // ash
  8: '#4a4644', 9: '#6b6562',
  // iron, FD cookware cold-grey ramp
  A: '#1f1f21', B: '#27272b', C: '#2d2d32', D: '#343438', E: '#3f3e42', F: '#494848', G: '#4f4f4f', H: '#595858', I: '#5c5c5c', J: '#656565', K: '#676161', L: '#727272',
  M: '#999897', // strainer wire highlight = Casualness Delight's fry-basket steel
  // frying oil: R and S are exactly Casualness Delight's fryer oil; P (bubble shade) Q (wall edge) T U (froth / glints) are new
  P: '#a86f28', Q: '#c08734', R: '#cf944c', S: '#e0b05b', T: '#f0cc7c', U: '#fae7aa',
  // bamboo (FD basket straw)
  X: '#998741', Y: '#bba84c', Z: '#d3bb50', 7: '#e3cc6a',
};
/** Paint rows of palette characters at (x0, y0). '.' leaves the pixel alone, '_' makes it transparent. */
function paint(c, x0, y0, rows) {
  rows.forEach((r, j) => [...r].forEach((ch, i) => {
    if (ch === '.') return;
    if (ch === '_') return setPx(c, x0 + i, y0 + j, [0, 0, 0, 0]);
    if (!(ch in PAL)) throw new Error(`unknown colour '${ch}' in row "${r}"`);
    setPx(c, x0 + i, y0 + j, PAL[ch]);
  }));
}
const grid = (rows, fill) => { if (rows.length !== 16 || rows.some(r => r.length !== 16)) throw new Error('grid must be 16x16: ' + rows.map(r => r.length)); const c = makeCanvas(16, 16, fill); paint(c, 0, 0, rows); return c; };

// ---------------------------------------------------------------- stove: side / front / top / bottom
// side faces use rows 6..15 (the body is 10 px tall): clay cap (6,7), brick course (8-10), mortar (11), course (12-14), base mortar (15).
// rows 0..5 only show up in break particles, so they just carry on the brickwork.
const SIDE_ROWS = [
  '4454444h4444544h',
  '3343333g3334333g',
  '2232222g2223222g',
  'hhghhhhhhhhghhhh',
  '443h4454444h4344',
  '333g3333333g3333',
  'nmnnmnnnmnnmnnnm',
  'lmllklmlllmllkll',
  '122g2212221g2122',
  '443h4544443h3443',
  '332g3333432g2332',
  'ghhhhghhhhhghhhh',
  '5444444h4454444h',
  '4443433g3433333g',
  '3322322g2232221g',
  'hhhghhhhhhghhhhh',
];
write(grid(SIDE_ROWS), T('fryer_c_side'));

// front: same brick + clay, with a clay lintel and clay jambs framing the fire mouth (cols 5-10, rows 10-14 is the opening).
// soot climbs from the mouth up the lintel and into the clay cap.
const FRONT_ROWS = [
  ...SIDE_ROWS.slice(0, 6),
  'nmnnmnnnmnnmnnnm',
  'lmllllmllmmlllll',
  '1221lmlkklml2212',
  '4544mkjqqjkm4454',
  '3332m' + 'oooooo' + 'm3323',
  'hhghm' + 'oooooo' + 'mhghh',
  '4h54m' + 'oooooo' + 'm54h4',
  '3g44m' + 'oooooo' + 'm43g3',
  '2g33l' + 'oooooo' + 'l32g2',
  'hhhgk' + 'jkkkkj' + 'kghhh',
];
write(grid(FRONT_ROWS), T('fryer_c_front'));

// top: plaster margin (the wok covers 2..14); a soot ring right where the wok sits
const TOP_ROWS = [
  'nmmnmmmnmmnmmmmn',
  'mmlmmkmmmlmmmlmm',
  'mlkkkkkkkkkkkkml',
  'mmkjjjjjjjjjjkmm',
  'nmkjjjjjjjjjjkln',
  'mlkjjjjjjjjjjkmm',
  'mmkjjjjjjjjjjkmm',
  'mmkjjjjjjjjjjkmn',
  'nmkjjjjjjjjjjkmm',
  'mmkjjjjjjjjjjklm',
  'mlkjjjjjjjjjjkmm',
  'mmkjjjjjjjjjjkmm',
  'nmkjjjjjjjjjjkmm',
  'mmkkkkkkkkkkkklm',
  'mmmlmmmmkmmmmlmm',
  'nmmmnmmmmmnmmmmn',
];
write(grid(TOP_ROWS), T('fryer_c_top'));

const bottom = grid([
  'kkkkkkkkkkkkkkkk', 'kjjjjjjjjjjjjjjk', 'kjkkkkjjjjkkkkjk', 'kjkjjjjjjjjjjkjk',
  'kjkjjjjjjjjjjkjk', 'kjjjjjjjjjjjjjjk', 'kjjjjjjjjjjjjjjk', 'kjjjjjjjjjjjjjjk',
  'kjjjjjjjjjjjjjjk', 'kjjjjjjjjjjjjjjk', 'kjjjjjjjjjjjjjjk', 'kjkjjjjjjjjjjkjk',
  'kjkjjjjjjjjjjkjk', 'kjkkkkjjjjkkkkjk', 'kjjjjjjjjjjjjjjk', 'kkkkkkkkkkkkkkkk',
]);
write(bottom, T('fryer_c_bottom'));

// ---------------------------------------------------------------- firebox (灶口) sheet
// layout (all regions are mapped explicitly in the model):
//   (0,0) 2x2 lump top   (0,2) 2x1 lump sides   (4,0) 6x3 coal-bed top   (4,3) 6x1 coal-bed front   (10,0) 6x4 hearth floor
//   (0,6) 4x10 inner face of the east pillar (col 0 = front edge)   (12,6) 4x10 inner face of the west pillar (col 15 = front edge)
//   (5,6) 6x4 underside of the lintel   (5,10) 6x5 back wall seen through the mouth (rows 10-13 visible, 14 behind the coals)
function firebox(f) {
  const c = makeCanvas(16, 16, PAL.o);
  if (f == null) {   // idle: banked charcoal, only a few dull-red embers scattered irregularly (one brighter spark)
    paint(c, 0, 0, ['pw', 'rp']);
    paint(c, 0, 2, ['rq']);
    paint(c, 4, 0, ['r8orqo', 'opwrps', 'q9rorw']);
    paint(c, 4, 3, ['orqpro']);
    paint(c, 10, 0, ['98w889', '898988', '989889', '898988']);
    paint(c, 5, 6, ['rrorro', 'orrrro', 'rorror', 'oroorr']);
    paint(c, 5, 10, ['rorror', 'orrrro', 'rrqprr', 'rpqwpr', 'pqwwqp']);
    paint(c, 0, 6, ['lroo', 'lroo', 'lroo', 'lroo', 'lrro', 'lroo', 'lrrr', 'lrrq', 'lrqp', 'lroo']);
    paint(c, 12, 6, ['oorl', 'oorl', 'oorl', 'oorl', 'orrl', 'oorl', 'rrrl', 'qrrl', 'pqrl', 'oorl']);
    return c;
  }
  // working: bright coals, licking flames on the back wall, everything inside lit orange (6 frames, like the FD stove)
  const lumps = [['ts', 'su'], ['st', 'ut'], ['tu', 'st'], ['us', 'ts'], ['st', 'tu'], ['ts', 'ut']][f];
  paint(c, 0, 0, lumps);
  paint(c, 0, 2, [['st', 'ts', 'tt', 'ss', 'ts', 'st'][f]]);
  const coal = ['tswust', 'sutsws', 'wstsut', 'stusws', 'utswst', 'swstut'];
  paint(c, 4, 0, [0, 1, 2].map(k => coal[(f + k * 2) % 6]));
  paint(c, 4, 3, [['tsutst', 'stustu', 'ustsut', 'tsusts', 'sutsut', 'tustst'][f]]);
  paint(c, 10, 0, [['9w9ws9', 'w9sw9w', '9sw9w9', 'w9w9sw', '9ws9w9', 'sw9w9w'][f], '898988', '989889', '898988']);
  paint(c, 5, 6, ['qpqqpq', 'pqqpqp', 'qpsqpq', 'psqqsp']);
  // flames: per frame, height of the flame in each of the 6 columns (rows 13 upwards)
  const H = [[1, 3, 4, 2, 3, 1], [2, 2, 3, 4, 2, 1], [1, 3, 2, 3, 4, 2], [2, 4, 3, 2, 3, 1], [1, 2, 4, 3, 2, 2], [2, 3, 3, 4, 1, 1]][f];
  for (let col = 0; col < 6; col++) {
    for (let row = 10; row <= 14; row++) setPx(c, 5 + col, row, row <= 11 ? PAL.p : PAL.q);
    const h = H[col];
    for (let k = 0; k < h; k++) {
      const ch = k === h - 1 ? 's' : k === h - 2 ? 't' : 'u';
      setPx(c, 5 + col, 13 - k, PAL[ch]);
    }
    setPx(c, 5 + col, 14, PAL[(col + f) % 3 ? 'u' : 't']);
  }
  paint(c, 0, 6, ['lpqo', 'mpqo', 'mqpo', 'mqqp', 'mqpq', 'mqsq', 'mssq', 'mtss', 'msts', 'mqpo']);
  paint(c, 12, 6, ['oqpl', 'oqpm', 'opqm', 'pqqm', 'qpqm', 'qsqm', 'qssm', 'sstm', 'stsm', 'opqm']);
  if (f % 2) { setPx(c, 2, 13, PAL.t); setPx(c, 13, 14, PAL.t); } else { setPx(c, 3, 14, PAL.t); setPx(c, 14, 13, PAL.s); }
  return c;
}
write(firebox(null), T('fryer_c_mouth'));
write(strip([0, 1, 2, 3, 4, 5].map(firebox)), T('fryer_c_mouth_on'));
json('textures/block/fryer_c_mouth_on.png.mcmeta', { animation: { frametime: 3, interpolate: false } });

// ---------------------------------------------------------------- iron wok sheet
// the wok is stepped like a bowl: 8 wide (y 10-11) -> 10 wide (y 11-13, its top is the oil) -> 12 wide rim walls (y 12-15)
// the rim is octagonal: each wall is 10 long and the four 1 px corners are cut away.
// every outer row is shaded across its width (bright middle, dark ends) and each lower tier is one step darker, so the
// stepped profile reads as one curved belly rather than three stacked plates.
//   rows 0-2 (cols 3-12): rim walls outside: lip (row 0, also the rim's top face), dark band, body; col 3 also skins the cut corners
//   rows 3-4 (cols 3-12): inner bowl outside (row 3 sits behind the walls)    row 5 (cols 4-11): bottom bowl
//   rows 6-8 (cols 3-12): inner wall above the oil (row 8 is under the oil)
//   (0,9) 2x4 ear tab with a loop hole; (2,9) 4x1 ear end
//   row 13 (cols 3-12): rim underside, one flat colour (the bowl's underside stretches it to 10x10)
const iron = makeCanvas(16, 16);
paint(iron, 3, 0, ['GHIJJJJIHG', 'BCDEEEEDCB', 'EFGHHHHGFE']);
paint(iron, 3, 3, ['EFGGGGGGFE', 'DEFGGGGFED']);
paint(iron, 4, 5, ['CDEFFEDC']);
paint(iron, 3, 6, ['DEEDEEEDEE', 'CDDCDDDCDD', 'BCCBCCCBCC']);
paint(iron, 0, 9, ['KL', '_L', '_K', 'JK']);
paint(iron, 2, 9, ['KLLK']);
paint(iron, 3, 13, ['DDDDDDDDDD']);
write(iron, T('fryer_c_iron'));

// ---------------------------------------------------------------- oil (top face of the inner bowl, uv 3..13, 2 px below the rim)
// clear golden frying oil (main colour = Casualness Delight's oil): one 1 px amber edge where it meets the wall,
// a couple of glints and a 1 px sky reflection along the far (south) wall. Texture x = world x, texture y = world z.
const OIL_CALM = [
  'QQQQQQQQQQ',
  'QSSSSSSRSQ',
  'QSUTSSSSSQ',
  'QSTSSSTSSQ',
  'QSSSSTTSSQ',
  'QSSSSSTSSQ',
  'QSRSSSSSSQ',
  'QSSSSSSUSQ',
  'QSTTUTTSSQ',
  'QQQQQQQQQQ',
];
// working: the same oil, plain (no glints) so the bubbles stand out; its edge turns to froth (drawn per frame)
const OIL_BOIL = [
  'QQQQQQQQQQ',
  'QSSSSSSSSQ',
  'QSSSSSSSSQ',
  'QSSSSSSSSQ',
  'QSSSSSSSSQ',
  'QSSSSSSSSQ',
  'QSSSSSSSSQ',
  'QSSSSSSSSQ',
  'QSSSSSSSSQ',
  'QQQQQQQQQQ',
];
function oilCanvas(rows) { const c = makeCanvas(16, 16, PAL.Q); paint(c, 3, 3, rows); return c; }
write(oilCanvas(OIL_CALM), T('fryer_c_oil'));
// working: 8 frames x 2 ticks.
//  * froth: a broken 1 px pale line along the wall (pale beads, a white one now and then, gaps of plain oil), re-rolled
//    every frame so it fizzes;
//  * bubbles are little domes, white core with a 1 px dark shade at the lower right, so they read as raised;
//    each one buds, swells, pops into a small ring and then rests, phases staggered (about 5 alive in any frame);
//  * 7 of the 12 bubble sites crowd round the point where the strainer handle enters the oil (world x 7-9, z 7-9).
const SITES = [
  // round the strainer (the handle itself covers z 7.5-8.5 from x 1.5 to 8.7)
  [9, 6, 0], [9, 9, 2], [10, 8, 5], [7, 5, 6], [7, 9, 4], [6, 6, 3], [10, 5, 7],
  // elsewhere
  [4, 4, 1], [10, 11, 6], [4, 10, 2], [5, 9, 7], [11, 4, 3],
];
const FROTH = [];   // the 36 edge pixels, walked round the wall
for (let i = 3; i <= 12; i++) FROTH.push([i, 3]);
for (let j = 4; j <= 12; j++) FROTH.push([12, j]);
for (let i = 11; i >= 3; i--) FROTH.push([i, 12]);
for (let j = 11; j >= 4; j--) FROTH.push([3, j]);
function oilFrame(f) {
  const c = oilCanvas(OIL_BOIL);
  const put = (x, y, ch) => { if (x >= 3 && x <= 12 && y >= 3 && y <= 12) setPx(c, x, y, PAL[ch]); };
  FROTH.forEach(([x, y], k) => {
    const h = (k * 5 + f * 3 + ((k * 3 + f * 7) >> 2)) % 7;   // cheap scramble: 4 of 7 pale, 1 of 7 white, 2 of 7 gaps
    put(x, y, h === 0 ? 'U' : h <= 4 ? 'T' : 'S');
  });
  for (const [x, y, ph] of SITES) {
    const st = (f + ph) % 8;
    if (st === 0) put(x, y, 'T');                                                                       // bud
    else if (st === 1) { put(x, y, 'U'); put(x + 1, y + 1, 'P'); }                                     // small dome
    else if (st === 2) { put(x, y, 'U'); put(x + 1, y, 'T'); put(x, y + 1, 'T'); put(x + 1, y + 1, 'P'); } // full dome
    else if (st === 3) { put(x, y - 1, 'U'); put(x - 1, y, 'T'); put(x + 1, y, 'T'); put(x, y + 1, 'T'); } // popped ring
  }
  return c;
}
write(strip([0, 1, 2, 3, 4, 5, 6, 7].map(oilFrame)), T('fryer_c_oil_on'));
json('textures/block/fryer_c_oil_on.png.mcmeta', { animation: { frametime: 2, interpolate: false } });

// ---------------------------------------------------------------- strainer (笊篱): wire mesh head + bamboo handle
//   (0,0) 6x6 round mesh head (cut-out)     (0,12) 6x1 wire frame seen edge-on (the head's four sides; ends clear to match the round corners)
//   rows 8-10 (cols 0-6): 7 px handle top / sides / underside, col 6 = iron ferrule at the head, col 3 = a soft bamboo node
//   (10,8) handle's cut end
const strainer = makeCanvas(16, 16);
paint(strainer, 0, 0, [
  '_LMML_',
  'LJ_J_L',
  'M_J_JM',
  'MJ_J_M',
  'L_J_JL',
  '_LMML_',
]);
paint(strainer, 0, 12, ['_LMML_']);
paint(strainer, 0, 8, ['Y7ZXZ7J', 'XZYXYZI', 'XYXXXYH']);
paint(strainer, 10, 8, ['Y']);
write(strainer, T('fryer_c_strainer'));

// ---------------------------------------------------------------- models
const f = (uv, texture, extra = {}) => ({ uv, texture, ...extra });
const stove = [
  { name: 'stove body', from: [0, 0, 4], to: [16, 10, 16], faces: {
    north: f([0, 6, 16, 16], '#mouth'),
    east: f([0, 6, 12, 16], '#side', { cullface: 'east' }),
    south: f([0, 6, 16, 16], '#side', { cullface: 'south' }),
    west: f([4, 6, 16, 16], '#side', { cullface: 'west' }),
    up: f([0, 4, 16, 16], '#top'),
    down: f([0, 0, 16, 12], '#bottom', { cullface: 'down' }) } },
  { name: 'pillar east', from: [11, 0, 0], to: [16, 10, 4], faces: {
    north: f([0, 6, 5, 16], '#front', { cullface: 'north' }),
    east: f([12, 6, 16, 16], '#side', { cullface: 'east' }),
    west: f([0, 6, 4, 16], '#mouth'),
    up: f([11, 0, 16, 4], '#top'),
    down: f([11, 12, 16, 16], '#bottom', { cullface: 'down' }) } },
  { name: 'pillar west', from: [0, 0, 0], to: [5, 10, 4], faces: {
    north: f([11, 6, 16, 16], '#front', { cullface: 'north' }),
    east: f([12, 6, 16, 16], '#mouth'),
    west: f([0, 6, 4, 16], '#side', { cullface: 'west' }),
    up: f([0, 0, 5, 4], '#top'),
    down: f([0, 12, 5, 16], '#bottom', { cullface: 'down' }) } },
  { name: 'lintel', from: [5, 6, 0], to: [11, 10, 4], faces: {
    north: f([5, 6, 11, 10], '#front', { cullface: 'north' }),
    up: f([5, 0, 11, 4], '#top'),
    down: f([5, 6, 11, 10], '#mouth') } },
  { name: 'hearth sill', from: [5, 0, 0], to: [11, 1, 4], faces: {
    north: f([5, 15, 11, 16], '#front', { cullface: 'north' }),
    up: f([10, 0, 16, 4], '#mouth'),
    down: f([5, 12, 11, 16], '#bottom', { cullface: 'down' }) } },
  { name: 'coal bed', from: [5, 1, 1], to: [11, 2, 4], faces: {
    north: f([4, 3, 10, 4], '#mouth'),
    up: f([4, 0, 10, 3], '#mouth') } },
  { name: 'coal lump a', from: [6, 2, 2], to: [8, 3, 4], faces: {
    north: f([0, 2, 2, 3], '#mouth'), east: f([0, 2, 2, 3], '#mouth'), west: f([0, 2, 2, 3], '#mouth'),
    up: f([0, 0, 2, 2], '#mouth') } },
  { name: 'coal lump b', from: [8, 2, 3], to: [10, 3, 4], faces: {
    north: f([0, 2, 2, 3], '#mouth'), east: f([0, 2, 1, 3], '#mouth'),
    up: f([0, 0, 2, 1], '#mouth') } },
];
const SIDE4 = uv => ({ north: f(uv, '#iron'), east: f(uv, '#iron'), south: f(uv, '#iron'), west: f(uv, '#iron') });
// octagonal rim: north/south walls run x 3-13, east/west walls z 3-13, so each corner loses a 1x1 column.
// the ends of the walls (facing into those cut corners) are skinned with the dark end column (col 3) of the outer rows.
const CUT = [3, 0, 4, 3];
const wok = [
  { name: 'wok bottom', from: [4, 10, 4], to: [12, 11, 12], faces: SIDE4([4, 5, 12, 6]) },
  // the down face only shows as the 1 px ledge ring round the wok bottom, seen from very low; without it you look up
  // through the ledge into the sky (tried, see notes). It samples row 13, which is one flat colour, so the 10x1 -> 10x10
  // stretch cannot show; keep row 13 single-coloured.
  { name: 'wok bowl + oil', from: [3, 11, 3], to: [13, 13, 13], faces: {
    ...SIDE4([3, 3, 13, 5]),
    up: f([3, 3, 13, 13], '#oil'),
    down: f([3, 13, 13, 14], '#iron') } },
  { name: 'wall north', from: [3, 12, 2], to: [13, 15, 3], faces: {
    north: f([3, 0, 13, 3], '#iron'), south: f([3, 6, 13, 9], '#iron'), up: f([3, 0, 13, 1], '#iron'), down: f([3, 13, 13, 14], '#iron'),
    east: f(CUT, '#iron'), west: f(CUT, '#iron') } },
  { name: 'wall south', from: [3, 12, 13], to: [13, 15, 14], faces: {
    south: f([3, 0, 13, 3], '#iron'), north: f([3, 6, 13, 9], '#iron'), up: f([3, 0, 13, 1], '#iron'), down: f([3, 13, 13, 14], '#iron'),
    east: f(CUT, '#iron'), west: f(CUT, '#iron') } },
  { name: 'wall west', from: [2, 12, 3], to: [3, 15, 13], faces: {
    west: f([3, 0, 13, 3], '#iron'), east: f([3, 6, 13, 9], '#iron'),
    up: f([3, 0, 13, 1], '#iron', { rotation: 90 }), down: f([3, 13, 13, 14], '#iron', { rotation: 90 }),
    north: f(CUT, '#iron'), south: f(CUT, '#iron') } },
  { name: 'wall east', from: [13, 12, 3], to: [14, 15, 13], faces: {
    east: f([3, 0, 13, 3], '#iron'), west: f([3, 6, 13, 9], '#iron'),
    up: f([3, 0, 13, 1], '#iron', { rotation: 90 }), down: f([3, 13, 13, 14], '#iron', { rotation: 90 }),
    north: f(CUT, '#iron'), south: f(CUT, '#iron') } },
  { name: 'ear east', from: [14, 14, 6], to: [16, 15, 10], faces: {
    up: f([0, 9, 2, 13], '#iron'), down: f([0, 9, 2, 13], '#iron'), east: f([2, 9, 6, 10], '#iron', { cullface: 'east' }),
    north: f([0, 9, 2, 10], '#iron'), south: f([0, 9, 2, 10], '#iron') } },
  { name: 'ear west', from: [0, 14, 6], to: [2, 15, 10], faces: {
    up: f([2, 9, 0, 13], '#iron'), down: f([2, 9, 0, 13], '#iron'), west: f([2, 9, 6, 10], '#iron', { cullface: 'west' }),
    north: f([0, 9, 2, 10], '#iron'), south: f([0, 9, 2, 10], '#iron') } },
];
// strainer: 7 px bamboo handle along x (cut end towards west = viewer's right), ferrule + head towards east.
const handle = (from, to, extra = {}) => ({ name: 'strainer handle', from, to, ...extra, faces: {
  up: f([0, 8, 7, 9], '#strainer'), down: f([0, 10, 7, 11], '#strainer'),
  north: f([7, 9, 0, 10], '#strainer'), south: f([0, 9, 7, 10], '#strainer'),
  east: f([6, 8, 7, 9], '#strainer'), west: f([10, 8, 11, 9], '#strainer') } });
// idle: laid flat across the wok. Handle y 15-16 rests on the west ear and the west rim; the head is a 1 px tall wire ring
// (x 8-14, z 5-11, y 15-16) whose underside sits on the east rim. Mesh on top/bottom, the wire frame on its four sides.
const RING = [0, 12, 6, 13];
const strainerIdle = [
  handle([1, 15, 7.5], [8, 16, 8.5]),
  { name: 'strainer head', from: [8, 15, 5], to: [14, 16, 11], faces: {
    up: f([0, 0, 6, 6], '#strainer'), down: f([0, 0, 6, 6], '#strainer'),
    north: f(RING, '#strainer'), south: f(RING, '#strainer'), east: f(RING, '#strainer'), west: f(RING, '#strainer') } },
];
// working: dipped. Only the handle is modelled: the head would sit wholly under the opaque oil (and, tilted, its tip would
// poke out through the wok's side below the east wall), so it is left out. The ferrule end stands ~0.3 px proud of the oil
// at x 8.5; the cut end tops out at y 15.99, inside the block.
const strainerActive = [
  handle([1.5, 12.35, 7.5], [8.5, 13.35, 8.5], { rotation: { angle: -22.5, axis: 'z', origin: [8.5, 12.85, 8] } }),
];
const TEX = {
  particle: ID('fryer_c_side'), side: ID('fryer_c_side'), front: ID('fryer_c_front'), top: ID('fryer_c_top'), bottom: ID('fryer_c_bottom'),
  mouth: ID('fryer_c_mouth'), iron: ID('fryer_c_iron'), oil: ID('fryer_c_oil'), strainer: ID('fryer_c_strainer'),
};
// idle: strainer laid across the wok, handle on the west ear + rim, mesh head resting on the east rim
// ambientocclusion false in both: keeps idle and working shaded alike whatever light level the block gets
// (vanilla only applies AO when the block's light emission is 0), and matches what the preview shows.
json('models/block/fryer_c_idle.json', {
  parent: 'minecraft:block/block',
  ambientocclusion: false,
  render_type: 'minecraft:cutout',
  textures: TEX,
  elements: [...stove, ...wok, ...strainerIdle],
});
// working: strainer dipped into the oil (tilted 22.5°, head under the surface), oil frothing, fire roaring
json('models/block/fryer_c_active.json', {
  parent: ID('fryer_c_idle'),
  ambientocclusion: false,
  render_type: 'minecraft:cutout',
  textures: { mouth: ID('fryer_c_mouth_on'), oil: ID('fryer_c_oil_on') },
  elements: [...stove, ...wok, ...strainerActive],
});

// ---------------------------------------------------------------- meta
json('meta.json', {
  key: 'C',
  tag: '方案 C',
  order: 3,
  name: '中式炸灶',
  tagline: '砖砌小灶坐一口八角双耳铁锅，竹柄笊篱搭在锅沿，灶口里留着余火；自带火，不用垫炉灶。',
  description: '一整格的砖砌小灶：灶身 10 像素高，砖色和农夫乐事炉灶是同一套，顶面和灶沿抹一层黄泥。正面开一个凹进 4 像素的方灶口，黄泥门框，门楣被烟熏黑，洞里是炭床和两块木炭，待机时炭是黑的，只零星几点暗红余火。灶面中间坐一口双耳铁锅：锅沿切了四个角，俯视是八角形；锅底按 8→10→12 像素分三级收口，每一级的外壁都是中间亮、两头暗，往下一级再暗一档，平视时读成一个圆肚子，像圆底锅陷在灶眼里；锅沿高出灶面 5 像素，两侧各有一只带孔的锅耳。油面比锅沿低 2 像素，主色直接取随性乐事炸锅的油色，只有贴锅壁一圈 1 像素深一点，远侧锅壁下有一条浅色反光。待机时一把竹柄铁丝笊篱横搭在锅口：竹柄压在西侧锅耳和锅沿上，网头是 1 像素厚的铁丝圈，落在东侧锅沿上，侧面看得到一圈网框。工作中笊篱斜插进油里，只露出竹柄和铁箍；油面边上起一圈断续的白沫，笊篱周围冒泡最密，每个泡是“白芯 + 右下暗影”的小圆顶；灶口窜起火苗，炭床和门洞内壁被映红。',
  notes: [
    '朝向：正面（灶口）画在 north 面；facing 用 y 旋转 0/90/180/270。',
    '自带热源：不需要下面垫炉灶，也不认炉灶，不加 farmersdelight:heat_sources 标签。光照建议工作中 13（同农夫乐事炉灶点火），待机 3（余火；顺带让两个状态都不走 AO）。',
    '两个模型都写了 "ambientocclusion": false。原版只在方块光照为 0 时画 AO，待机给 0 的话，切到工作中时锅内、灶口一圈的明暗会跟着跳；关掉 AO 后无论光照给多少，两个状态的明暗都一致，也和预览看到的一样。',
    '待机 / 工作中两个模型。工作中模型 parent 待机模型，换油面和灶口两张贴图；笊篱换姿势（工作中只剩竹柄），所以元素表整份重写（原版子模型写了 elements 会整份替换）。',
    '动画贴图：fryer_c_oil_on 8 帧 × 2 tick（白沫闪动，油泡冒头、鼓起、破成小圈），fryer_c_mouth_on 6 帧 × 3 tick（和农夫乐事炉灶点火正面同节奏）。',
    '碰撞箱 / 选取框：getShape 和 getCollisionShape 都返回 Shapes.or(box(0,0,0,16,10,16), box(2,10,2,14,15,14))，前后左右对称，四个朝向共用；笊篱和锅耳不进碰撞箱。不要调 noOcclusion()，否则贴边的面（灶身侧墙、底面）的 cullface 就失效了。',
    '服务端切 active 要有滞后：状态真的变了才 setBlock，别每道菜开始、结束都来回切，免得反复重建区块网格和光照。',
    '镂空：笊篱网头、锅耳的孔用透明像素，模型写了 render_type cutout。',
    '新增色（不在农夫乐事 / 随性乐事贴图里的）：黄泥 5 色 #6e5437 #8a6c48 #a2845c #b4976e #c5aa80；炉膛 2 色 #2a120b #8a3311；炉膛底灰 2 色 #4a4644 #6b6562；油 4 色 #a86f28（泡影）#c08734（贴壁一圈）#f0cc7c #fae7aa（白沫 / 高光）。其余（砖、砂浆、锅灰、火、竹、网丝亮色、油主色）都直接取原色。',
  ],
  hires: false,
  namespace: NS,
  blocks: [
    { label: '中式炸灶', pos: [0, 0, 0], idle: { model: 'fryer_c_idle', y: 0 }, active: { model: 'fryer_c_active', y: 0 } },
  ],
});
console.log('design C written to', dir);
