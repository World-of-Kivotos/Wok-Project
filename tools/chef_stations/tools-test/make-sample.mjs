// Writes the pipeline sample design (2 elements, 1 animated texture) using lib/png.mjs.
//   node tools-test/make-sample.mjs [targetDir]      default: tools-test/designs/_sample
// Doubles as a worked example of the texture helpers: makeCanvas / fillRect / outline / setPx / strip / write.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { makeCanvas, fillRect, outline, setPx, strip, write } from '../lib/png.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const dir = path.resolve(process.argv[2] || path.join(here, 'designs', '_sample'));
const T = n => path.join(dir, 'textures/block', n + '.png');
const json = (rel, o) => { const f = path.join(dir, rel); fs.mkdirSync(path.dirname(f), { recursive: true }); fs.writeFileSync(f, JSON.stringify(o, null, 2) + '\n'); };

// palette borrowed from Farmer's Delight's pot/stove textures (refs/palette.json)
const P = { d0: '#27272b', d1: '#2d2d32', d2: '#343438', d3: '#3f3e42', m0: '#494848', m1: '#4f4f4f', m2: '#595858', l0: '#676161', l1: '#727272',
  oil0: '#a8702a', oil1: '#c18a35', oil2: '#d9a441', oil3: '#efc767', oil4: '#f8e3a0', fire: '#c35d1b', fire2: '#ed8c0e' };

// side: steel wall, rim on top, rivets
const side = makeCanvas(16, 16, P.m2);
fillRect(side, 0, 7, 16, 1, P.l0);                 // rim highlight (face uv starts at v=7)
fillRect(side, 0, 8, 16, 1, P.m0);
fillRect(side, 0, 15, 16, 1, P.d3);                // foot shadow
for (const x of [3, 12]) { setPx(side, x, 10, P.l1); setPx(side, x, 13, P.l1); }
for (let x = 0; x < 16; x += 4) setPx(side, x, 12, P.m1);
write(side, T('sample_side'));

const bottom = makeCanvas(16, 16, P.d2); outline(bottom, 2, 2, 12, 12, P.d1); write(bottom, T('sample_bottom'));

// oil surface inside a 1px steel rim (face uv is [2,2,14,14])
function oil(bubbles) {
  const c = makeCanvas(16, 16, P.m1);
  outline(c, 2, 2, 12, 12, P.l0);
  fillRect(c, 3, 3, 10, 10, P.oil2);
  for (let i = 3; i < 13; i++) { setPx(c, i, 3, P.oil1); setPx(c, 3, i, P.oil1); }
  for (const [x, y, r] of bubbles) {
    setPx(c, x, y, P.oil4);
    if (r) { setPx(c, x + 1, y, P.oil3); setPx(c, x, y + 1, P.oil3); setPx(c, x + 1, y + 1, P.oil0); }
  }
  return c;
}
write(oil([[6, 9, 0], [10, 6, 0]]), T('sample_oil'));
const frames = [
  [[5, 5, 1], [9, 9, 0], [11, 4, 0]],
  [[5, 6, 0], [9, 8, 1], [7, 11, 0]],
  [[10, 5, 1], [6, 9, 0], [4, 11, 0]],
  [[10, 6, 0], [6, 8, 1], [11, 10, 1]],
].map(oil);
write(strip(frames), T('sample_oil_on'));
json('textures/block/sample_oil_on.png.mcmeta', { animation: { frametime: 3, interpolate: false } });

// control panel: knob + lamp; "on" lights the lamp
function panel(lit) {
  const c = makeCanvas(16, 16);
  fillRect(c, 0, 0, 6, 4, P.m1); outline(c, 0, 0, 6, 4, P.d3);
  setPx(c, 1, 1, P.d1); setPx(c, 2, 1, P.d1); setPx(c, 1, 2, P.d1); setPx(c, 2, 2, P.l1);   // knob
  setPx(c, 4, 1, lit ? P.fire2 : P.d0); setPx(c, 4, 2, lit ? P.fire : P.d0);                 // lamp
  fillRect(c, 0, 4, 2, 4, P.m0);   // sides (1x4 each)
  fillRect(c, 0, 8, 6, 1, P.l0);   // top
  fillRect(c, 0, 9, 6, 1, P.d3);   // bottom
  return c;
}
write(panel(false), T('sample_panel'));
write(panel(true), T('sample_panel_on'));

json('models/block/sample_fryer.json', {
  parent: 'minecraft:block/block',
  render_type: 'minecraft:cutout',
  textures: { particle: 'miningdim:block/sample_side', side: 'miningdim:block/sample_side', top: 'miningdim:block/sample_oil', bottom: 'miningdim:block/sample_bottom', panel: 'miningdim:block/sample_panel' },
  elements: [
    { name: 'tank', from: [2, 0, 2], to: [14, 9, 14], faces: {
      north: { uv: [2, 7, 14, 16], texture: '#side' }, east: { uv: [2, 7, 14, 16], texture: '#side' },
      south: { uv: [2, 7, 14, 16], texture: '#side' }, west: { uv: [2, 7, 14, 16], texture: '#side' },
      up: { uv: [2, 2, 14, 14], texture: '#top' }, down: { uv: [2, 2, 14, 14], texture: '#bottom', cullface: 'down' } } },
    { name: 'panel', from: [5, 2, 1], to: [11, 6, 2], faces: {
      north: { uv: [0, 0, 6, 4], texture: '#panel' }, east: { uv: [0, 4, 1, 8], texture: '#panel' }, west: { uv: [1, 4, 2, 8], texture: '#panel' },
      up: { uv: [0, 8, 6, 9], texture: '#panel' }, down: { uv: [0, 9, 6, 10], texture: '#panel' } } },
  ],
});
json('models/block/sample_fryer_on.json', {
  parent: 'miningdim:block/sample_fryer',
  textures: { top: 'miningdim:block/sample_oil_on', panel: 'miningdim:block/sample_panel_on' },
});
json('meta.json', {
  key: path.basename(dir), tag: '示例', order: 0, name: '示例油槽',
  tagline: '两个元素加一张 4 帧油面动画，只用来验证工具链。',
  description: '一个 12×9×12 的钢槽，正面一块旋钮面板。工作中模型继承待机模型，只把油面换成动画贴图、面板指示灯点亮。',
  notes: ['待机 / 工作中两个模型，工作中靠 parent 继承，只覆盖两张贴图。', '油面 sample_oil_on：16×64 竖条，4 帧，每帧 3 tick，不插值。'],
  blocks: [{ label: '油槽', idle: { model: 'sample_fryer', y: 0 }, active: { model: 'sample_fryer_on', y: 0 } }],
});
console.log('sample written to', dir);
