#!/usr/bin/env node
// 军火台 (WIDE 布局, 弹药流水线) 的预览出图 (零依赖): 仓库里的静态 JSON 模型 (区块网格) + 按 MunitionsBenchParts.java /
// MunitionsBenchProgram.java 摆好的运动件 (方块实体渲染器), 即游戏里看到的样子。光栅核心用 ../gunsmith_workstation 的
// raster.mjs / png.mjs / font.mjs / scene.mjs。由方案评选时的 .candidates/munitions_common/render_mb.mjs 移植。
// 用法 (PowerShell):
//   $env:Path = 'D:\DevTools\node-v22.23.3-win-x64;' + $env:Path
//   node tools/munitions_bench/render_mb.mjs --out-dir <目录> [--repo <仓库根, 默认本文件往上两级>]
//        [--mode all|sheets|tiers|motion|closeup] [--tier base,high,...] [--state idle,active] [--mark] [--block-light]
//        closeup: [--view EYE|FL|FR|F|BL|BR|T|S|W|NIGHT|NIGHT_EYE] [--tier base] [--state active] [--tick <循环时间>]
//                 [--w 1400 --h 1000] [--box focus|machine|x0,y0,z0,x1,y1,z1] [--name <文件名>]
//   工作态不给 --tick 时取冲头到底的那一刻 (STRIKE_TICK)。运动件默认按实体光照画 (游戏里的样子); --block-light 改用方块面明暗
//   (与方案预览的 JSON 逐帧模型逐像素可比, 对拍脚本 check_parity.mjs 用这个)。
// 输出 (all): mb-<tier>-<idle|active>.png (5x2: FL FR EYE FRONT BL / TOP SIDE-E NIGHT-IDLE NIGHT-ACTIVE ITEM),
//   mb-tiers.png (6 档并排: EYE 工作 / 夜间工作 / 物品图标), mb-motion.png (普通档一个循环, 每 2.5 tick 一列), mb-eye-active.png。
// 坐标: 朝北放置, x 向东, z 向南, 正面 z = 0; main 在 x 0..16 (站在正面的玩家右手), extension 在 x 16..32 (左手)。
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import * as R from '../gunsmith_workstation/raster.mjs';
import * as SCENE from '../gunsmith_workstation/scene.mjs';
import { writePng } from '../gunsmith_workstation/png.mjs';
import { drawText } from '../gunsmith_workstation/font.mjs';
import { TIERS, tierIndex } from './tiers.mjs';
import { parsePartsJava, parseProgramJava, sampleProgram, idlePose, applyPoseMirror, bakeParts } from './ber.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
function parseArgs(argv) {
    const a = {};
    for (let i = 0; i < argv.length; i++) if (argv[i].startsWith('--')) { const n = argv[i + 1]; if (n === undefined || n.startsWith('--')) a[argv[i].slice(2)] = true; else { a[argv[i].slice(2)] = n; i++; } }
    return a;
}
const IS_MAIN = !!process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
const ARGS = IS_MAIN ? parseArgs(process.argv.slice(2)) : {};
export const DEFAULT_REPO = path.resolve(HERE, '..', '..');

// ================================================================ 相机
// 玩家视角: 站在正面前 1.6 格 (25.6 px), 眼高 1.62 格 (25.92 px), 人略偏 main 一侧 (x 12), 看整台中心 (x 16, y 10, z 8)。
export const PLAYER = { x: 12, eye: 25.92, dist: 25.6, target: [16, 10, 8] };
export function playerEyeAngles(p = PLAYER) {
    const dx = p.target[0] - p.x, dz = p.target[2] + p.dist, dy = p.eye - p.target[1];
    return { yaw: Math.round(Math.atan2(dx, dz) * 180 / Math.PI * 10) / 10, pitch: Math.round(Math.atan2(dy, Math.hypot(dx, dz)) * 180 / Math.PI * 10) / 10 };
}
const EYE = playerEyeAngles();
export const VIEWS = {
    FL: { label: 'FRONT-LEFT', yaw: 38, pitch: 32 },
    FR: { label: 'FRONT-RIGHT', yaw: -38, pitch: 32 },
    BL: { label: 'BACK-LEFT', yaw: 180 - 38, pitch: 32 },
    BR: { label: 'BACK-RIGHT', yaw: 180 + 38, pitch: 32 },
    F: { label: 'FRONT', yaw: 0, pitch: 0 },
    S: { label: 'SIDE-EAST', yaw: -90, pitch: 0 },
    W: { label: 'SIDE-WEST', yaw: 90, pitch: 0 },
    T: { label: 'TOP', yaw: 0, pitch: 89.9 },
    EYE: { label: 'PLAYER-EYE', yaw: EYE.yaw, pitch: EYE.pitch },
    NIGHT: { label: 'NIGHT', yaw: 38, pitch: 32, brightness: 0.18 },
    NIGHT_EYE: { label: 'NIGHT EYE', yaw: EYE.yaw, pitch: EYE.pitch, brightness: 0.18 },
};
// 取景盒 (px): 同一个盒子 = 同一比例尺
export const FRAMES = {
    machine: { min: [-2, 0, -5], max: [34, 32, 19], ground: [-1, 3, -1, 2] },
    // 运动件特写: 皮带、装填塔、压机、弹药箱口
    focus: { min: [2, 2, 3], max: [31, 24, 13], ground: [-1, 3, -1, 2] },
};
const GROUND = (() => {
    const data = new Uint8ClampedArray(16 * 16 * 4);
    for (let y = 0; y < 16; y++) for (let x = 0; x < 16; x++) {
        const v = x === 0 || y === 0 ? 150 : ((x + y) & 1 ? 196 : 190);
        const o = (y * 16 + x) * 4;
        data[o] = v; data[o + 1] = v + 2; data[o + 2] = v + 6; data[o + 3] = 255;
    }
    return { width: 16, height: 16, data };
})();

// ================================================================ 场景
export const CELL_OFFSETS = { main: [0, 0, 0], extension: [16, 0, 0] };
const JAVA = (...p) => path.join('src', 'main', 'java', 'com', 'miningdim', 'job', 'munitions', ...p);

/** 仓库里的游戏资源: 静态 JSON 模型 + 运动件 (Java 源为唯一真源)。 */
export function loadGame(repo = DEFAULT_REPO) {
    const root = new SCENE.AssetRoot([repo]);
    const parts = parsePartsJava(fs.readFileSync(path.join(repo, JAVA('client', 'MunitionsBenchParts.java')), 'utf8'));
    const program = parseProgramJava(fs.readFileSync(path.join(repo, JAVA('block', 'MunitionsBenchProgram.java')), 'utf8'));
    return { repo, root, parts, program, partsImage: root.image(parts.textureId), id: 'game' };
}

/**
 * 游戏里某一档的整台四边形 (朝北)。state 'idle' = 待机静态模型 + 待机布局; 'active' = 工作静态模型 + 运动件摆在循环时间 tick
 * (省略 = STRIKE_TICK)。opts: {blockLight: 运动件用方块面明暗 (对拍), hideParts}
 */
export function gameQuads(game, tier, state, tick = null, opts = {}) {
    const sfx = TIERS[tierIndex(tier)].suffix;
    const quads = [];
    for (const [cell, offset] of Object.entries(CELL_OFFSETS)) {
        const id = `miningdim:block/munitions_bench${sfx}_line_${cell}${state === 'active' ? '_active' : ''}`;
        quads.push(...R.bakeBlockModel(game.root.model(id), { offset, textureLookup: (i) => game.root.image(i), tag: cell }));
    }
    if (!opts.hideParts) {
        const pose = state === 'active' ? sampleProgram(game.program, tick === null || tick === undefined ? game.program.strikeTick : Number(tick)) : idlePose(game.program);
        quads.push(...bakeParts(game.parts, applyPoseMirror(game.parts, pose), game.partsImage, { entity: !opts.blockLight }));
    }
    return quads;
}
export function gameItem(game, tier) {
    return SCENE.itemQuads(game.root, 'munitions_bench' + TIERS[tierIndex(tier)].suffix);
}

/** 方案评选时的候选输出目录 (含 src/main/resources 的那一层, 例如 generate.mjs --out 的目录)。 */
export function loadCandidate(dir) {
    const d = path.resolve(dir);
    const out = fs.existsSync(path.join(d, 'out', 'src')) ? path.join(d, 'out') : d;
    return { out, root: new SCENE.AssetRoot([out]), id: 'candidate' };
}
/** 候选某一档某一状态的整台四边形; frame != null 时取普通档逐帧模型 (忽略 tier)。 */
export function candQuads(cand, tier, state, frame = null) {
    const sfx = TIERS[tierIndex(tier)].suffix;
    const quads = [];
    for (const [cell, offset] of Object.entries(CELL_OFFSETS)) {
        const id = frame !== null && frame !== undefined
            ? `miningdim:block/munitions_bench_${cell}_active_f${frame}`
            : `miningdim:block/munitions_bench${sfx}_${cell}${state === 'active' ? '_active' : ''}`;
        quads.push(...R.bakeBlockModel(cand.root.model(id), { offset, textureLookup: (i) => cand.root.image(i), tag: cell }));
    }
    return quads;
}
export function candItem(cand, tier) {
    return SCENE.itemQuads(cand.root, 'munitions_bench' + TIERS[tierIndex(tier)].suffix);
}

// ================================================================ 画面
export function newImage(w, h, rgb = [30, 32, 36]) {
    const img = { width: w, height: h, data: new Uint8ClampedArray(w * h * 4) };
    R.fillBackground(img, rgb);
    return img;
}
export function blit(target, src, x0, y0) {
    for (let y = 0; y < src.height; y++) {
        const ty = y0 + y;
        if (ty < 0 || ty >= target.height) continue;
        for (let x = 0; x < src.width; x++) {
            const tx = x0 + x;
            if (tx < 0 || tx >= target.width) continue;
            const si = (y * src.width + x) * 4, di = (ty * target.width + tx) * 4;
            target.data[di] = src.data[si]; target.data[di + 1] = src.data[si + 1]; target.data[di + 2] = src.data[si + 2]; target.data[di + 3] = 255;
        }
    }
}
function frameFit(frame, cam, w, h, pad = 18) {
    let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
    for (const x of [frame.min[0], frame.max[0]]) for (const y of [frame.min[1], frame.max[1]]) for (const z of [frame.min[2], frame.max[2]]) {
        const sx = x * cam.right[0] + y * cam.right[1] + z * cam.right[2];
        const sy = -(x * cam.up[0] + y * cam.up[1] + z * cam.up[2]);
        minX = Math.min(minX, sx); maxX = Math.max(maxX, sx); minY = Math.min(minY, sy); maxY = Math.max(maxY, sy);
    }
    const scale = Math.min((w - pad * 2) / (maxX - minX), (h - pad * 2 - 12) / (maxY - minY));
    return { scale, cx: w / 2 - ((minX + maxX) / 2) * scale, cy: (h + 12) / 2 - ((minY + maxY) / 2) * scale };
}
const fitLabel = (s, w) => { const n = Math.max(4, Math.floor((w - 12) / 12)); s = String(s); return s.length > n ? s.slice(0, n - 1) + '.' : s; };
function project(p, cam, fit) {
    return [(p[0] * cam.right[0] + p[1] * cam.right[1] + p[2] * cam.right[2]) * fit.scale + fit.cx, -(p[0] * cam.up[0] + p[1] * cam.up[1] + p[2] * cam.up[2]) * fit.scale + fit.cy];
}
/**
 * 一格正交视图 (不带标签的原始画面, 对拍逐像素比较用)。jitter: 整幅画面的亚像素平移 [dx, dy];
 * 正视/侧视时像素中心会正好落在贴图像素的分界上, 两套等价的 uv 在分界上取到哪一格只看浮点误差, 对拍时错开它。
 */
export function renderRaw(w, h, quads, frame, view, bg, jitter = null) {
    const cam = R.cameraFromAngles(view.yaw, view.pitch);
    const fit = frameFit(frame, cam, w, h);
    if (jitter) { fit.cx += jitter[0]; fit.cy += jitter[1]; }
    const bright = view.brightness != null ? view.brightness : 1;
    const panel = newImage(w, h, bright < 1 ? [22, 25, 31] : (bg || [58, 63, 72]));
    const g = frame.ground;
    R.rasterize(quads.concat(R.groundQuads(g[0], g[1], g[2], g[3], GROUND)), panel, { cam, scale: fit.scale, cx: fit.cx, cy: fit.cy, brightness: bright });
    return { panel, cam, fit };
}
/** 一格正交视图。opts: {label, mark (标 MAIN / EXT), bg, boxes: [{lo, hi, rgb}] 线框} */
export function renderPanel(target, x0, y0, w, h, quads, frame, view, opts = {}) {
    const { panel, cam, fit } = renderRaw(w, h, quads, frame, view, opts.bg);
    if (opts.mark) {
        for (const [txt, p, col] of [['MAIN', [8, 1, -1.5], [255, 120, 90]], ['EXT', [24, 1, -1.5], [120, 200, 255]]]) {
            const [sx, sy] = project(p, cam, fit);
            drawText(panel, Math.round(sx - txt.length * 6), Math.round(sy + 4), txt, col);
        }
    }
    for (const b of opts.boxes || []) wireBox(panel, b.lo, b.hi, cam, fit, b.rgb || [255, 60, 200]);
    drawText(panel, 6, 5, fitLabel(opts.label || view.label, w), [235, 235, 235]);
    blit(target, panel, x0, y0);
    return { cam, fit };
}
/** 盒子线框 (碰撞箱预览)。 */
export function wireBox(img, lo, hi, cam, fit, rgb) {
    const P = (x, y, z) => project([x, y, z], cam, fit);
    const c = [];
    for (const x of [lo[0], hi[0]]) for (const y of [lo[1], hi[1]]) for (const z of [lo[2], hi[2]]) c.push(P(x, y, z));
    const edges = [[0, 1], [2, 3], [4, 5], [6, 7], [0, 2], [1, 3], [4, 6], [5, 7], [0, 4], [1, 5], [2, 6], [3, 7]];
    for (const [a, b] of edges) {
        const [x0, y0] = c[a], [x1, y1] = c[b];
        const n = Math.max(1, Math.ceil(Math.hypot(x1 - x0, y1 - y0)));
        for (let i = 0; i <= n; i++) {
            const x = Math.round(x0 + (x1 - x0) * i / n), y = Math.round(y0 + (y1 - y0) * i / n);
            if (x < 0 || y < 0 || x >= img.width || y >= img.height) continue;
            const o = (y * img.width + x) * 4; img.data[o] = rgb[0]; img.data[o + 1] = rgb[1]; img.data[o + 2] = rgb[2]; img.data[o + 3] = 255;
        }
    }
}
/** 物品栏图标: 32 px 渲染后整数倍放大, 右下角再放 1:1。 */
export function renderIcon(target, x0, y0, w, h, item, label) {
    const n = 32;
    const small = newImage(n, n, [139, 139, 139]);
    R.rasterize(R.guiTransform(item.quads, item.display), small, { cam: R.cameraFromDir([0, 0, 1]), scale: n / 16, cx: n / 2, cy: n / 2 });
    const panel = newImage(w, h, [58, 63, 72]);
    const k = Math.max(1, Math.floor(Math.min(w - 20, h - 40) / n));
    const ox = Math.floor((w - n * k) / 2), oy = Math.floor((h - n * k) / 2) + 8;
    for (let y = 0; y < n * k; y++) for (let x = 0; x < n * k; x++) {
        const si = (Math.floor(y / k) * n + Math.floor(x / k)) * 4, di = ((oy + y) * w + ox + x) * 4;
        panel.data[di] = small.data[si]; panel.data[di + 1] = small.data[si + 1]; panel.data[di + 2] = small.data[si + 2]; panel.data[di + 3] = 255;
    }
    blit(panel, small, w - n - 6, h - n - 6);
    drawText(panel, 6, 5, fitLabel(label, w), [235, 235, 235]);
    blit(target, panel, x0, y0);
    return small;
}

/** 朝向自检: EYE / FRONT 视图里 main 格中心必须在 extension 格中心的右边 (屏幕 x 更大)。 */
export function orientationCheck() {
    const out = [];
    for (const k of ['EYE', 'F', 'FL', 'NIGHT_EYE']) {
        const cam = R.cameraFromAngles(VIEWS[k].yaw, VIEWS[k].pitch);
        const sx = (p) => p[0] * cam.right[0] + p[1] * cam.right[1] + p[2] * cam.right[2];
        out.push({ view: k, ok: sx([8, 8, 8]) > sx([24, 8, 8]) });
    }
    return out;
}

// ================================================================ 组图
export const footer = (target, text) => drawText(target, 6, target.height - 14, text, [255, 220, 120]);

/** 一档一状态: 5x2 = FL FR EYE FRONT BL / TOP SIDE-E NIGHT-IDLE NIGHT-ACTIVE ITEM。 */
export function tierSheet(game, tier, state, opts = {}) {
    const t = tierIndex(tier);
    const q = gameQuads(game, t, state, null, opts);
    const qi = state === 'idle' ? q : gameQuads(game, t, 'idle', null, opts);
    const qa = state === 'active' ? q : gameQuads(game, t, 'active', null, opts);
    const cols = 5, pw = 440, ph = 370;
    const target = newImage(cols * pw, 2 * ph + 20);
    const cells = [
        [q, VIEWS.FL], [q, VIEWS.FR], [q, VIEWS.EYE, `PLAYER-EYE (YAW ${EYE.yaw} PITCH ${EYE.pitch})`], [q, VIEWS.F], [q, VIEWS.BL],
        [q, VIEWS.T], [q, VIEWS.S], [qi, VIEWS.NIGHT_EYE, 'NIGHT IDLE'], [qa, VIEWS.NIGHT_EYE, 'NIGHT ACTIVE'],
    ];
    cells.forEach(([qq, v, label], i) => renderPanel(target, (i % cols) * pw + 1, Math.floor(i / cols) * ph + 1, pw - 2, ph - 2, qq, FRAMES.machine, v, { label, mark: opts.mark }));
    renderIcon(target, 4 * pw + 1, ph + 1, pw - 2, ph - 2, gameItem(game, t), 'ITEM ICON (GUI X2)');
    footer(target, `IN-GAME (STATIC JSON + BER PARTS)  ${TIERS[t].en}  STATE=${state.toUpperCase()}${state === 'active' ? ' T=' + game.program.strikeTick : ''}  MAIN = X 0-16 (PLAYER RIGHT)`);
    return target;
}

/** 6 档并排: EYE 工作 / 夜间 EYE 工作 / 物品图标。 */
export function tierStrip(game, opts = {}) {
    const pw = 400, ph = 330;
    const target = newImage(6 * pw, 3 * ph + 20);
    for (let t = 0; t < 6; t++) {
        const qa = gameQuads(game, t, 'active', null, opts);
        renderPanel(target, t * pw + 1, 1, pw - 2, ph - 2, qa, FRAMES.machine, VIEWS.EYE, { label: `${TIERS[t].en} EYE ACTIVE`, mark: opts.mark });
        renderPanel(target, t * pw + 1, ph + 1, pw - 2, ph - 2, qa, FRAMES.machine, VIEWS.NIGHT_EYE, { label: `${TIERS[t].en} NIGHT ACTIVE` });
        renderIcon(target, t * pw + 1, 2 * ph + 1, pw - 2, ph - 2, gameItem(game, t), `${TIERS[t].en} ITEM`);
    }
    footer(target, 'IN-GAME  6 TIERS  (TRIM COLOUR + LIGHT COLOUR + CUMULATIVE ADD-ONS)');
    return target;
}

/** 普通档一个循环: 每个时刻一列, 上 = 玩家视角整台, 中 = 运动件特写 (左前), 下 = 运动件特写 (正面)。 */
export function motionStrip(game, ticks, opts = {}) {
    const pw = opts.pw || 300, ph = opts.ph || 260;
    const target = newImage(ticks.length * pw, 3 * ph + 20);
    ticks.forEach((tick, i) => {
        const q = gameQuads(game, 0, 'active', tick, opts);
        const tag = Number.isInteger(tick / 5) ? ` F${tick / 5}` : '';
        renderPanel(target, i * pw + 1, 1, pw - 2, ph - 2, q, FRAMES.machine, VIEWS.EYE, { label: `T ${tick}${tag}` });
        renderPanel(target, i * pw + 1, ph + 1, pw - 2, ph - 2, q, FRAMES.focus, VIEWS.FL, { label: `T ${tick} FOCUS FL` });
        renderPanel(target, i * pw + 1, 2 * ph + 1, pw - 2, ph - 2, q, FRAMES.focus, VIEWS.F, { label: `T ${tick} FOCUS FRONT` });
    });
    footer(target, `IN-GAME BASE TIER CYCLE  ${game.program.cycleTicks} TICKS  STRIKE AT ${game.program.strikeTick}  BER PARTS ${opts.blockLight ? 'BLOCK-SHADED' : 'ENTITY-LIT'}`);
    return target;
}

export function closeup(game, o = {}) {
    const t = tierIndex(o.tier || 0);
    const state = o.state || 'active';
    const q = gameQuads(game, t, state, o.tick === undefined || o.tick === true ? null : o.tick, o);
    const v = VIEWS[o.view || 'EYE'];
    if (!v) throw new Error('unknown view ' + o.view);
    const box = o.box === 'focus' ? FRAMES.focus : o.box && String(o.box).includes(',')
        ? (() => { const n = String(o.box).split(',').map(Number); return { min: n.slice(0, 3), max: n.slice(3, 6), ground: [-1, 3, -1, 2] }; })()
        : { min: [-1, 0, -5], max: [33, 25, 17], ground: [-1, 3, -1, 2] };
    const w = Number(o.w || 1400), h = Number(o.h || 1000);
    const target = newImage(w, h);
    renderPanel(target, 0, 0, w, h, q, box, v, { label: `IN-GAME ${TIERS[t].en} ${v.label} ${state.toUpperCase()}${state === 'active' ? ' T ' + (o.tick === undefined || o.tick === true ? game.program.strikeTick : o.tick) : ''}`, mark: o.mark, boxes: o.boxes });
    return target;
}

// ================================================================ CLI
async function main() {
    const mode = ARGS.mode || 'all';
    if (!ARGS['out-dir']) { console.error('usage: node render_mb.mjs --out-dir <dir> [--repo <root>] [--mode all|sheets|tiers|motion|closeup] [--tier ..] [--state ..] [--tick t] [--view V] [--block-light] [--mark]'); process.exit(2); }
    for (const o of orientationCheck()) if (!o.ok) throw new Error(`orientation: in view ${o.view} main (x 0..16) is not on the screen right of extension`);
    const game = loadGame(path.resolve(ARGS.repo || DEFAULT_REPO));
    const dir = path.resolve(ARGS['out-dir']);
    fs.mkdirSync(dir, { recursive: true });
    const save = (name, img) => { const p = path.join(dir, name); writePng(p, img); console.log('wrote', p, img.width + 'x' + img.height); };
    const opts = { mark: !!ARGS.mark, blockLight: !!ARGS['block-light'] };
    const tiers = ARGS.tier ? String(ARGS.tier).split(',').map(tierIndex) : [0, 1, 2, 3, 4, 5];
    const states = ARGS.state ? String(ARGS.state).split(',') : ['idle', 'active'];
    if (mode === 'closeup') {
        save(ARGS.name || `mb-${TIERS[tiers[0]].key}-${states[0]}-${String(ARGS.view || 'EYE').toLowerCase()}${ARGS.tick !== undefined ? '-t' + ARGS.tick : ''}.png`,
            closeup(game, { ...opts, tier: tiers[0], state: states[0], view: ARGS.view, tick: ARGS.tick, w: ARGS.w, h: ARGS.h, box: ARGS.box }));
        return;
    }
    if (mode === 'all' || mode === 'sheets') for (const t of tiers) for (const s of states) save(`mb-${TIERS[t].key}-${s}.png`, tierSheet(game, t, s, opts));
    if (mode === 'all' || mode === 'tiers') save('mb-tiers.png', tierStrip(game, opts));
    if (mode === 'all' || mode === 'motion') save('mb-motion.png', motionStrip(game, Array.from({ length: 16 }, (_, i) => i * 2.5), opts));
    if (mode === 'all') save('mb-eye-active.png', closeup(game, { ...opts, tier: 0, state: 'active', view: 'EYE' }));
}

if (IS_MAIN) await main();
