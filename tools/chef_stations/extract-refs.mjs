// Pull the reference blocks out of the installed mod jars (read-only) into refs/<mod>/...
//   node extract-refs.mjs [--mods <mods dir>] [--vanilla <client jar>]
// Writes:
//   refs/<mod>/blockstates/*.json, refs/<mod>/models/block/*.json, refs/<mod>/textures/block/*.png(.mcmeta)
//   refs/minecraft/models/block/*.json   minimal vanilla parents (written here, not copied) + preview floor models
//   refs/minecraft/textures/block/*.png  floor textures (copied from the vanilla jar when present, else generated)
//   refs/index.json    every previewable reference block (model per state, textures, size in px)
//   refs/palette.json  colour counts per texture;  refs/contact-sheet.png  all reference textures at 8x
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { openZip } from './lib/zip.mjs';
import { decodePNG, makeCanvas, fillRect, setPx, blit, scale, write, hex, rgba } from './lib/png.mjs';
import { mcResolveModel, mcBakeModel, mcBounds, mcSplitId, mcFullId, mcAnimInfo } from './lib/mcmodel.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const arg = k => { const i = process.argv.indexOf(k); return i > 0 ? process.argv[i + 1] : null; };
const GAME = 'D:\\WOK测试\\versions\\1.20.1-Forge_47.4.22';
const MODS = arg('--mods') || path.join(GAME, 'mods');
const VANILLA = arg('--vanilla') || [path.join(GAME, '1.20.1-Forge_47.4.22.jar'),
  'D:\\WOK测试\\libraries\\net\\minecraft\\client\\1.20.1-20230612.114412\\client-1.20.1-20230612.114412-extra.jar'].find(f => fs.existsSync(f));
const REFS = path.join(here, 'refs');

const findJar = re => { const f = fs.readdirSync(MODS).find(n => re.test(n)); if (!f) throw new Error('jar not found in ' + MODS + ': ' + re); return path.join(MODS, f); };
const FD_JAR = findJar(/^FarmersDelight-1\.20\.1-.*\.jar$/i);
const CD_JAR = findJar(/^casualness_delight-.*\.jar$/i);
const jars = { farmersdelight: openZip(FD_JAR), casualness_delight: openZip(CD_JAR) };

fs.rmSync(REFS, { recursive: true, force: true });
const out = (rel, data) => { const f = path.join(REFS, rel); fs.mkdirSync(path.dirname(f), { recursive: true }); fs.writeFileSync(f, data); return f; };

// ---------------------------------------------------------------- vanilla parents (minimal copies, written by hand)
// Same elements/texture wiring as 1.20.1's assets/minecraft/models/block/*.json; display transforms trimmed to what
// matters for the inventory icon.
const BLOCK_DISPLAY = {
  gui: { rotation: [30, 225, 0], translation: [0, 0, 0], scale: [0.625, 0.625, 0.625] },
  ground: { rotation: [0, 0, 0], translation: [0, 3, 0], scale: [0.25, 0.25, 0.25] },
  fixed: { rotation: [0, 0, 0], translation: [0, 0, 0], scale: [0.5, 0.5, 0.5] },
  thirdperson_righthand: { rotation: [75, 45, 0], translation: [0, 2.5, 0], scale: [0.375, 0.375, 0.375] },
  firstperson_righthand: { rotation: [0, 45, 0], translation: [0, 0, 0], scale: [0.40, 0.40, 0.40] },
  firstperson_lefthand: { rotation: [0, 225, 0], translation: [0, 0, 0], scale: [0.40, 0.40, 0.40] },
};
const cubeFaces = (extra = {}) => Object.fromEntries(['down', 'up', 'north', 'south', 'west', 'east'].map(d => [d, { texture: '#' + d, cullface: d, ...(extra[d] || {}) }]));
const VANILLA_MODELS = {
  'block/block': { gui_light: 'side', display: BLOCK_DISPLAY },
  'block/thin_block': { parent: 'block/block', display: { thirdperson_righthand: { rotation: [75, 45, 0], translation: [0, 2.5, 2], scale: [0.375, 0.375, 0.375] } } },
  'block/cube': { parent: 'block/block', elements: [{ from: [0, 0, 0], to: [16, 16, 16], faces: cubeFaces() }] },
  'block/cube_all': { parent: 'block/cube', textures: { particle: '#all', down: '#all', up: '#all', north: '#all', east: '#all', south: '#all', west: '#all' } },
  'block/cube_bottom_top': { parent: 'block/cube', textures: { particle: '#side', down: '#bottom', up: '#top', north: '#side', east: '#side', south: '#side', west: '#side' } },
  'block/cube_column': { parent: 'block/cube', textures: { particle: '#side', down: '#end', up: '#end', north: '#side', east: '#side', south: '#side', west: '#side' } },
  'block/cube_column_horizontal': { parent: 'block/block', elements: [{ from: [0, 0, 0], to: [16, 16, 16], faces: cubeFaces({ up: { rotation: 180 } }) }], textures: { particle: '#side', down: '#end', up: '#end', north: '#side', east: '#side', south: '#side', west: '#side' } },
  'block/orientable_with_bottom': { parent: 'block/cube', textures: { particle: '#front', down: '#bottom', up: '#top', north: '#front', east: '#side', south: '#side', west: '#side' } },
  'block/orientable': { parent: 'block/orientable_with_bottom', textures: { bottom: '#top' } },
  'block/orientable_vertical': { parent: 'block/cube', textures: { particle: '#front', down: '#side', up: '#front', north: '#side', east: '#side', south: '#side', west: '#side' } },
  'block/cross': { ambientocclusion: false, textures: { particle: '#cross' }, elements: [
    { from: [0.8, 0, 8], to: [15.2, 16, 8], rotation: { origin: [8, 8, 8], axis: 'y', angle: 45, rescale: true }, shade: false, faces: { north: { uv: [0, 0, 16, 16], texture: '#cross' }, south: { uv: [0, 0, 16, 16], texture: '#cross' } } },
    { from: [8, 0, 0.8], to: [8, 16, 15.2], rotation: { origin: [8, 8, 8], axis: 'y', angle: 45, rescale: true }, shade: false, faces: { west: { uv: [0, 0, 16, 16], texture: '#cross' }, east: { uv: [0, 0, 16, 16], texture: '#cross' } } }] },
  // preview floors (oak planks as in vanilla; grass simplified: tinted top, pre-coloured side, no overlay layer)
  'block/oak_planks': { parent: 'block/cube_all', textures: { all: 'minecraft:block/oak_planks' } },
  'block/grass_block': { parent: 'block/block', textures: { particle: 'minecraft:block/dirt', bottom: 'minecraft:block/dirt', top: 'minecraft:block/grass_block_top', side: 'minecraft:block/grass_block_side' },
    elements: [{ from: [0, 0, 0], to: [16, 16, 16], faces: { down: { texture: '#bottom', cullface: 'down' }, up: { texture: '#top', cullface: 'up', tintindex: 0 }, north: { texture: '#side', cullface: 'north' }, south: { texture: '#side', cullface: 'south' }, west: { texture: '#side', cullface: 'west' }, east: { texture: '#side', cullface: 'east' } } }] },
};
for (const [p, j] of Object.entries(VANILLA_MODELS)) out(`minecraft/models/${p}.json`, JSON.stringify(j, null, 2) + '\n');

// floor textures
const FLOOR_TEX = ['oak_planks', 'grass_block_top', 'grass_block_side', 'dirt'];
let floorSource = 'generated';
if (VANILLA) {
  const z = openZip(VANILLA);
  if (FLOOR_TEX.every(t => z.has(`assets/minecraft/textures/block/${t}.png`))) {
    for (const t of FLOOR_TEX) out(`minecraft/textures/block/${t}.png`, z.read(`assets/minecraft/textures/block/${t}.png`));
    floorSource = path.basename(VANILLA);
  }
}
if (floorSource === 'generated') {
  // stand-ins in the same spirit (only used when no vanilla jar is around)
  const noise = (x, y, s) => { const n = Math.sin(x * 12.9898 + y * 78.233 + s * 37.719) * 43758.5453; return n - Math.floor(n); };
  const planks = makeCanvas(16, 16);
  for (let y = 0; y < 16; y++) for (let x = 0; x < 16; x++) {
    const seam = y % 4 === 3, k = noise(x, y >> 2, 1) * 0.12 + noise(x, y, 2) * 0.06;
    setPx(planks, x, y, seam ? '#6b5432' : rgba([Math.round(162 - 40 * k), Math.round(130 - 34 * k), Math.round(78 - 24 * k)]));
  }
  for (let r = 0; r < 4; r++) setPx(planks, (r * 7 + 3) % 16, r * 4 + 1, '#7d6339');
  write(planks, path.join(REFS, 'minecraft/textures/block/oak_planks.png'));
  const grass = makeCanvas(16, 16), dirt = makeCanvas(16, 16), side = makeCanvas(16, 16);
  for (let y = 0; y < 16; y++) for (let x = 0; x < 16; x++) {
    const g = Math.round(150 + 60 * noise(x, y, 3)); setPx(grass, x, y, [g, g, g]);
    const d = noise(x, y, 4); setPx(dirt, x, y, d > 0.8 ? '#5a3f2a' : d > 0.4 ? '#866043' : '#9a7656');
    setPx(side, x, y, y < 3 + (noise(x, 0, 5) > 0.5 ? 1 : 0) ? '#6e9a3e' : (d > 0.8 ? '#5a3f2a' : d > 0.4 ? '#866043' : '#9a7656'));
  }
  write(grass, path.join(REFS, 'minecraft/textures/block/grass_block_top.png'));
  write(dirt, path.join(REFS, 'minecraft/textures/block/dirt.png'));
  write(side, path.join(REFS, 'minecraft/textures/block/grass_block_side.png'));
}

// ---------------------------------------------------------------- mod blocks
const fdFryer = (() => {
  // Casualness Delight's fryer: find a blockstate whose name says fry/fryer/frying
  const names = jars.casualness_delight.names().filter(n => /^assets\/[^/]+\/blockstates\/[^/]*fr(y|ying|yer)[^/]*\.json$/.test(n));
  if (!names.length) throw new Error('no fryer blockstate in ' + CD_JAR);
  return path.basename(names[0], '.json');
})();

const TARGETS = [
  { key: 'fd_cooking_pot', ns: 'farmersdelight', block: 'cooking_pot', name: '厨锅', idle: 'facing=north,support=none', active: 'facing=north,support=none',
    extra: { 'support=tray': 'facing=north,support=tray', 'support=handle': 'facing=north,support=handle' },
    note: '工作中模型不变，沸腾靠粒子和声音。下面是火源时用 tray（带四条腿的托架，腿伸进下面一格），挂在上方时用 handle（提梁）。' },
  { key: 'fd_stove', ns: 'farmersdelight', block: 'stove', name: '炉灶', idle: 'facing=north,lit=false', active: 'facing=north,lit=true',
    note: '整格方块（orientable_with_bottom）。点火后换 stove_on：正面火口 3 tick 一帧，顶面火圈 8 tick 一帧并插值。' },
  { key: 'fd_skillet', ns: 'farmersdelight', block: 'skillet', name: '煎锅', idle: 'facing=north,support=false', active: 'facing=north,support=false',
    extra: { 'support=true': 'facing=north,support=true' }, note: '锅柄伸出方块 11px（到 z=27）。没有 render_type，按 solid 渲染。' },
  { key: 'fd_cutting_board', ns: 'farmersdelight', block: 'cutting_board', name: '砧板', idle: 'facing=north', active: 'facing=north', note: '1px 厚，一张贴图画完所有面。' },
  { key: 'fd_basket', ns: 'farmersdelight', block: 'basket', name: '篮子', idle: 'facing=up', active: 'facing=up', note: '六向（含 x 旋转）。' },
  { key: 'cd_fryer', ns: 'casualness_delight', block: fdFryer, name: '随性乐事炸锅', idle: 'facing=north,support=false', active: 'facing=north,support=false',
    extra: { 'support=true': 'facing=north,support=true' },
    note: '要被取代的现有炸锅。贴图 32×32（模型写了 texture_size 32），密度是农夫乐事方块的两倍；工作中没有单独模型。' },
];

const props = s => new Set(String(s).split(',').filter(Boolean));
const sameProps = (a, b) => { const A = props(a), B = props(b); return A.size === B.size && [...A].every(x => B.has(x)); };
function variantFor(bs, want) {
  if (bs.variants) {
    for (const [k, v] of Object.entries(bs.variants)) if (sameProps(k, want)) { const m = Array.isArray(v) ? v[0] : v; return { model: mcFullId(m.model), x: m.x || 0, y: m.y || 0 }; }
  }
  throw new Error('variant not found: ' + want);
}
function allVariantModels(bs) {
  const ms = new Set();
  if (bs.variants) for (const v of Object.values(bs.variants)) for (const m of [].concat(v)) ms.add(mcFullId(m.model));
  if (bs.multipart) for (const part of bs.multipart) for (const m of [].concat(part.apply)) ms.add(mcFullId(m.model));
  return [...ms];
}

const copied = new Set(), models = {}, index = [], textureList = new Set();
function copyFromJar(ns, kind, p, ext) {
  const rel = `${ns}/${kind}/${p}${ext}`;
  if (copied.has(rel)) return true;
  const z = jars[ns]; if (!z) return false;
  const name = `assets/${ns}/${kind}/${p}${ext}`;
  if (!z.has(name)) return false;
  out(rel, z.read(name)); copied.add(rel); return true;
}
function getModel(id) {
  if (models[id] !== undefined) return models[id];
  const { ns, path: p } = mcSplitId(id);
  let json = null;
  if (ns === 'minecraft') {
    const f = path.join(REFS, 'minecraft/models', p + '.json');
    json = fs.existsSync(f) ? JSON.parse(fs.readFileSync(f, 'utf8')) : null;
    if (!json) console.warn('  ! vanilla parent not embedded:', id, '(add it to VANILLA_MODELS)');
  } else if (copyFromJar(ns, 'models', p, '.json')) json = JSON.parse(fs.readFileSync(path.join(REFS, ns, 'models', p + '.json'), 'utf8').replace(/^\uFEFF/, ''));
  return (models[id] = json);
}
function copyTexturesOf(model) {
  const list = [];
  for (const v of Object.values(model.textures)) {
    if (typeof v !== 'string' || v.startsWith('#')) continue;
    const { ns, path: p } = mcSplitId(v);
    if (ns === 'minecraft') continue;
    if (copyFromJar(ns, 'textures', p, '.png')) { copyFromJar(ns, 'textures', p, '.png.mcmeta'); list.push(`${ns}/textures/${p}.png`); textureList.add(`${ns}:${p}`); }
    else console.warn('  ! texture missing in jar:', v);
  }
  return [...new Set(list)];
}

for (const t of TARGETS) {
  const bsName = `assets/${t.ns}/blockstates/${t.block}.json`;
  const bsText = jars[t.ns].text(bsName);
  if (!bsText) { console.warn('skip (no blockstate):', t.ns + ':' + t.block); continue; }
  out(`${t.ns}/blockstates/${t.block}.json`, jars[t.ns].read(bsName));
  const bs = JSON.parse(bsText);
  const modelIds = allVariantModels(bs), resolved = {}, texs = new Set(), modelFiles = new Set();
  for (const id of modelIds) {
    const m = mcResolveModel(id, getModel);
    if (m.errors.length) console.warn('  !', id, m.errors.join('; '));
    resolved[id] = m;
    for (const c of m.chain) { const { ns, path: p } = mcSplitId(c); modelFiles.add(`${ns}/models/${p}.json`); }
    copyTexturesOf(m).forEach(x => texs.add(x));
  }
  const idle = variantFor(bs, t.idle), active = variantFor(bs, t.active);
  const variants = Object.entries(t.extra || {}).map(([label, want]) => ({ label, ...variantFor(bs, want) }));
  const b = mcBounds(mcBakeModel(resolved[idle.model]));
  const size = b ? b[1].map((v, k) => +(v - b[0][k]).toFixed(3)) : null;
  index.push({
    key: t.key, mod: t.ns, id: `${t.ns}:${t.block}`, name: t.name, blockstate: `${t.ns}/blockstates/${t.block}.json`,
    idle, active, variants, models: [...modelFiles].sort(), textures: [...texs].sort(),
    renderType: resolved[idle.model].renderType, elements: resolved[idle.model].elements.length,
    bounds_px: b, size_px: size, note: t.note,
  });
  console.log(`${t.key.padEnd(17)} ${t.ns}:${t.block}  models ${modelIds.length}  textures ${texs.size}  idle ${idle.model}${idle.model !== active.model ? '  active ' + active.model : ''}  size ${size && size.join('x')}px`);
}

// ---------------------------------------------------------------- palette + contact sheet
const palette = { note: 'opaque colours per texture (alpha >= 128), most frequent first; frames = animation frames', textures: {} };
const sheetItems = [];
for (const tid of [...textureList].sort()) {
  const { ns, path: p } = mcSplitId(tid);
  const f = path.join(REFS, ns, 'textures', p + '.png');
  const img = decodePNG(fs.readFileSync(f));
  const metaF = f + '.mcmeta', meta = fs.existsSync(metaF) ? JSON.parse(fs.readFileSync(metaF, 'utf8')) : null;
  const ai = mcAnimInfo(img.width, img.height, meta);
  const counts = new Map(); let transparent = 0, partial = 0;
  for (let i = 0; i < img.data.length; i += 4) {
    const a = img.data[i + 3];
    if (a < 128) { transparent++; continue; }
    if (a < 255) partial++;
    const k = hex([img.data[i], img.data[i + 1], img.data[i + 2]]); counts.set(k, (counts.get(k) || 0) + 1);
  }
  const colours = [...counts.entries()].sort((a, b) => b[1] - a[1]);
  palette.textures[tid] = { size: [img.width, img.height], frame: [ai.fw, ai.fh], frames: ai.frames.length, frametime: meta?.animation?.frametime ?? null, interpolate: ai.interpolate,
    unique: colours.length, transparentPx: transparent, partialAlphaPx: partial, colours: colours.slice(0, 12).map(([c, n]) => `${c}×${n}`) };
  sheetItems.push({ tid, img, ai });
}
// sheet: frame 0 of every texture in a 256px cell (16x16 at 16x, 32x32 at 8x — same face size, so texel density is comparable), 6 per row
const CELL = 256 + 8, PER = 6, rows = Math.ceil(sheetItems.length / PER);
const sheet = makeCanvas(PER * CELL, rows * CELL);
fillRect(sheet, 0, 0, sheet.width, sheet.height, '#2b2f33');
sheetItems.forEach((it, i) => {
  const fr = makeCanvas(it.ai.fw, it.ai.fh);
  for (let y = 0; y < it.ai.fh; y++) for (let x = 0; x < it.ai.fw; x++) { const o = (y * it.img.width + x) * 4; setPx(fr, x, y, [...it.img.data.subarray(o, o + 4)]); }
  const big = scale(fr, Math.max(1, Math.floor((CELL - 8) / Math.max(it.ai.fw, it.ai.fh))));   // every face shown at the same on-screen size
  const cx = (i % PER) * CELL + 4, cy = Math.floor(i / PER) * CELL + 4;
  for (let y = 0; y < CELL - 8; y++) for (let x = 0; x < CELL - 8; x++) setPx(sheet, cx + x, cy + y, ((x >> 3) + (y >> 3)) & 1 ? '#3b4045' : '#33373b');
  blit(sheet, big, cx, cy);
});
write(sheet, path.join(REFS, 'contact-sheet.png'));
palette.sheet = { file: 'contact-sheet.png', scale: 'frame 0 of each texture in a 256px cell (16x16 at 16x, 32x32 at 8x)', columns: PER, order: sheetItems.map(s => s.tid) };
fs.writeFileSync(path.join(REFS, 'palette.json'), JSON.stringify(palette, null, 2) + '\n');

const idx = {
  generated: new Date().toISOString(),
  sources: { farmersdelight: path.basename(FD_JAR), casualness_delight: path.basename(CD_JAR), minecraft: 'parents written by extract-refs.mjs; floor textures: ' + floorSource },
  layout: 'refs/<mod>/{blockstates,models/block,textures/block}/... (same paths as assets/<mod>/ in the jar)',
  floors: { oak: { model: 'minecraft:block/oak_planks' }, grass: { model: 'minecraft:block/grass_block', tint: { 0: '#91bd59' } } },
  blocks: index,
};
fs.writeFileSync(path.join(REFS, 'index.json'), JSON.stringify(idx, null, 2) + '\n');
console.log(`refs written: ${copied.size} files from jars + ${Object.keys(VANILLA_MODELS).length} vanilla models; floor textures: ${floorSource}`);
