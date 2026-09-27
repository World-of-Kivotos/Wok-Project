#!/usr/bin/env node
// 枪匠工作站多视角预览出图。
// 用法: node render.mjs --repo <仓库或 worktree 根> --block press|assembly --state idle|active --out x.png
//        [--overlay <候选方案根, 结构同仓库, 优先读取>] [--closeup FL|FR|BR|BL|F|S|T|EYE|NIGHT] [--views FL,FR,...]
//        [--tick 0..160 (组装台机械臂程序时间, 隐含工作态)] [--phase 0..1 (= tick / 160)]
//        [--contact EYE|T|... [--every 8] (组装台机械臂连拍表: 每隔 every tick 一格, 标 tick)]
//        [--gun <枪 id> [--packs <tacz 目录>]] (组装台: 台上放这把 TACZ 枪, 按摆放规则侧躺在枪床上; 组图、特写、连拍表都生效)
//        [--guns all|<id,id> [--view FEYE] [--frame bed]] (组装台: 每把蓝图枪一格, 同一视角, 标 id / 渲染长度 / 朝上一侧)
//        [--gun <枪 id> --fixed-frame] (只画这把枪, 留在 TACZ FIXED 定位系: 左侧 / 右侧 / 俯视 + 槽位图标, 查贴图镜像、上下颠倒)
//        [--lod] (与 --gun / --guns 连用: 画 LOD 模型 + LOD 贴图 (游戏里高模只在 8 格内且每帧最多 3 台, 其余用 LOD), 没有 LOD 的枪与游戏一样退回高模 (游戏里 32 格外不画))
//        [--drops auto|<枪机>,<枪托件>] (组装台机械臂的放件下沉量 px; 默认 auto = 按台上的枪算 (与游戏同一规则), 没枪 = MAX_PLACE_DROP; 0,0 = 关键帧表原样)
// 台上的枪一律按裸枪显示 stack 画 (TACZ 不画 attachment_adapter 的子节点, 见 raster.mjs taczBareDrawn), 摆放与下沉量也只算画出来的方块。
// 组图 5×2: 左前 / 右前 / 右后 / 左后 / 玩家视角 / 正前 / 侧面(东) / 俯视 / 夜间(自发光) / 物品栏图标。
import path from 'node:path';
import { AssetRoot, pressQuads, assemblyQuads, benchQuads, armQuads, itemQuads } from './scene.mjs';
import { rasterize, cameraFromAngles, cameraFromDir, fillBackground, groundQuads, guiTransform, projectBounds, benchGunPlaceDrops } from './raster.mjs';
import { writePng } from './png.mjs';
import { drawText } from './font.mjs';
import { TaczPacks, bakeBenchGun, readBlueprints, DEFAULT_PACKS } from './taczpack.mjs';
import { GUN_BED } from './generate_assembly_bench.mjs';

function parseArgs(argv) {
    const args = {};
    for (let i = 0; i < argv.length; i++) {
        if (argv[i].startsWith('--')) {
            const key = argv[i].slice(2);
            const next = argv[i + 1];
            if (next === undefined || next.startsWith('--')) args[key] = true;
            else { args[key] = next; i++; }
        }
    }
    return args;
}

const GROUND = (() => {
    const data = new Uint8ClampedArray(16 * 16 * 4);
    for (let y = 0; y < 16; y++) for (let x = 0; x < 16; x++) {
        const edge = x === 0 || y === 0;
        const v = edge ? 150 : ((x + y) & 1 ? 196 : 190);
        const o = (y * 16 + x) * 4;
        data[o] = v; data[o + 1] = v + 2; data[o + 2] = v + 6; data[o + 3] = 255;
    }
    return { width: 16, height: 16, data };
})();

// 固定取景盒 (像素), 新旧模型用同一比例尺, 便于对比。
const FRAMES = {
    press: { min: [-2, 0, -2], max: [18, 17, 18], ground: [-1, 2, -1, 2] },
    assembly: { min: [-2, 0, -2], max: [34, 22, 34], ground: [-1, 3, -1, 3] },
    // 连拍表只取机械臂的活动范围 (枪床东半、送料盘、设备台), 放大看清零件与夹爪
    assemblyArm: { min: [17, 10, 11], max: [31, 25, 27], ground: [-1, 3, -1, 3] },
    // 枪床特写: 整条枪床 (定位销到托底挡块) 与床上的枪
    bed: { min: [1, 9, 7], max: [31, 16, 17], ground: [-1, 3, -1, 3] },
    // 两个安装点特写: 枪床东半 (机匣后段到托底挡块) 与落到枪上的零件 / 爪子
    weld: { min: [16.5, 10, 7.5], max: [29.5, 18, 16], ground: [-1, 3, -1, 3] },
};

// 玩家站在台前 (北侧) 1.5 格、眼高 1.62 格, 看枪床中心 (x 16, 床面上 1 px, AXIS_Z)。预览是正交投影, 只取这条视线的方向。
export const PLAYER_EYE = { north: 24, height: 25.92 };
export function playerEyePitch(bed) {
    return Math.atan2(PLAYER_EYE.height - (bed.TOP_Y + 1), bed.AXIS_Z + PLAYER_EYE.north) * 180 / Math.PI;
}

export const VIEWS = {
    FL: { label: 'FRONT-LEFT', yaw: 38, pitch: 32 },
    FR: { label: 'FRONT-RIGHT', yaw: -38, pitch: 32 },
    BR: { label: 'BACK-RIGHT', yaw: 180 + 38, pitch: 32 },
    BL: { label: 'BACK-LEFT', yaw: 180 - 38, pitch: 32 },
    F: { label: 'FRONT', yaw: 0, pitch: 0 },
    S: { label: 'SIDE-EAST', yaw: -90, pitch: 0 },
    W: { label: 'SIDE-WEST', yaw: 90, pitch: 0 },
    T: { label: 'TOP', yaw: 0, pitch: 89.9 },
    EYE: { label: 'PLAYER-EYE', yaw: 22, pitch: 40 },
    NIGHT: { label: 'NIGHT (LIGHT 0)', yaw: 38, pitch: 32, brightness: 0.18 },
    FEYE: { label: 'PLAYER-EYE FRONT', yaw: 0, pitch: Math.round(playerEyePitch(GUN_BED) * 10) / 10 },
};

function frameCorners(frame) {
    const out = [];
    for (const x of [frame.min[0], frame.max[0]]) for (const y of [frame.min[1], frame.max[1]]) for (const z of [frame.min[2], frame.max[2]]) out.push([x, y, z]);
    return out;
}

function renderPanel(target, x0, y0, w, h, quads, frame, view) {
    const cam = cameraFromAngles(view.yaw, view.pitch);
    let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
    for (const p of frameCorners(frame)) {
        const sx = p[0] * cam.right[0] + p[1] * cam.right[1] + p[2] * cam.right[2];
        const sy = -(p[0] * cam.up[0] + p[1] * cam.up[1] + p[2] * cam.up[2]);
        minX = Math.min(minX, sx); maxX = Math.max(maxX, sx); minY = Math.min(minY, sy); maxY = Math.max(maxY, sy);
    }
    const pad = 18;
    const scale = Math.min((w - pad * 2) / (maxX - minX), (h - pad * 2 - 12) / (maxY - minY));
    const panel = { width: w, height: h, data: new Uint8ClampedArray(w * h * 4) };
    fillBackground(panel, [58, 63, 72]);
    const g = frame.ground;
    const all = quads.concat(groundQuads(g[0], g[1], g[2], g[3], GROUND));
    rasterize(all, panel, {
        cam, scale,
        brightness: view.brightness,
        cx: w / 2 - ((minX + maxX) / 2) * scale,
        cy: (h + 12) / 2 - ((minY + maxY) / 2) * scale,
    });
    drawText(panel, 6, 5, view.label, [235, 235, 235]);
    blit(target, panel, x0, y0);
}

function renderIcon(target, x0, y0, w, h, item, label) {
    // 物品栏原生 16px, GUI 缩放 2 时为 32 屏幕像素: 先按 32px 渲染, 再最近邻放大。
    const n = 32;
    const small = { width: n, height: n, data: new Uint8ClampedArray(n * n * 4) };
    fillBackground(small, [139, 139, 139]);
    const quads = guiTransform(item.quads, item.display);
    const cam = cameraFromDir([0, 0, 1]);
    rasterize(quads, small, { cam, scale: n / 16, cx: n / 2, cy: n / 2 });
    const panel = { width: w, height: h, data: new Uint8ClampedArray(w * h * 4) };
    fillBackground(panel, [58, 63, 72]);
    const k = Math.floor(Math.min(w - 20, h - 40) / n);
    const ox = Math.floor((w - n * k) / 2), oy = Math.floor((h - n * k) / 2) + 8;
    for (let y = 0; y < n * k; y++) for (let x = 0; x < n * k; x++) {
        const si = (Math.floor(y / k) * n + Math.floor(x / k)) * 4;
        const di = ((oy + y) * w + ox + x) * 4;
        panel.data[di] = small.data[si]; panel.data[di + 1] = small.data[si + 1]; panel.data[di + 2] = small.data[si + 2]; panel.data[di + 3] = 255;
    }
    // 右下角再放一个 1:1 实际大小。
    for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) {
        const si = (y * n + x) * 4;
        const di = ((h - n - 6 + y) * w + (w - n - 6) + x) * 4;
        panel.data[di] = small.data[si]; panel.data[di + 1] = small.data[si + 1]; panel.data[di + 2] = small.data[si + 2]; panel.data[di + 3] = 255;
    }
    drawText(panel, 6, 5, label, [235, 235, 235]);
    blit(target, panel, x0, y0);
}

function blit(target, src, x0, y0) {
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

/** 组装台机械臂连拍表: ticks 里每个时刻一格 (工作态模型), 左上角标 tick, 点焊中的格子标 WELD。 */
export function renderContactSheet(repo, viewKey, ticks, opts = {}) {
    const root = new AssetRoot([...(opts.overlay ? [opts.overlay] : []), repo]);
    const arm = root.arm();
    const cols = opts.cols || 7, pw = opts.cellW || 360, ph = opts.cellH || 300;
    const rows = Math.ceil(ticks.length / cols);
    const target = { width: cols * pw, height: rows * ph + 18, data: new Uint8ClampedArray(cols * pw * (rows * ph + 18) * 4) };
    fillBackground(target, [30, 32, 36]);
    const view = VIEWS[viewKey] || VIEWS.EYE;
    ticks.forEach((tick, i) => {
        const quads = assemblyQuads(root, true, { tick, drops: opts.drops }, opts.gun || null);
        const x0 = (i % cols) * pw + 1, y0 = Math.floor(i / cols) * ph + 1;
        renderPanel(target, x0, y0, pw - 2, ph - 2, quads, FRAMES.assemblyArm, { ...view, label: 'T ' + tick });
        const st = arm.program ? sampleLabel(arm, tick) : null;
        if (st && st.spark) drawText(target, x0 + pw - 60, y0 + 5, 'WELD', [255, 200, 90]);
        if (st && st.payload) drawText(target, x0 + pw - 60, y0 + 22, 'PART' + st.payload, [140, 230, 255]);
    });
    drawText(target, 6, rows * ph + 4, `ASSEMBLY ARM PROGRAM  VIEW=${view.label}  ${opts.tag || ''}`, [255, 220, 120]);
    return target;
}
function sampleLabel(arm, tick) {
    const r = arm.program.rows;
    let i = 1;
    const t = tick % r[r.length - 1].tick;
    if (t <= 0) return r[0];
    while (r[i].tick < t) i++;
    return r[i];
}

export function renderSheet(repo, block, state, opts = {}) {
    const root = new AssetRoot([...(opts.overlay ? [opts.overlay] : []), repo]);
    const active = state === 'active';
    const quads = block === 'press' ? pressQuads(root, active) : assemblyQuads(root, active, opts.tick !== undefined ? { tick: opts.tick, drops: opts.drops } : { work: active ? 1 : 0, drops: opts.drops }, opts.gun || null);
    const frame = FRAMES[block];
    const itemId = block === 'press' ? 'gunsmith_press' : 'gunsmith_assembly_bench';
    if (opts.closeup) {
        const w = 1100, h = 860;
        const target = { width: w, height: h, data: new Uint8ClampedArray(w * h * 4) };
        renderPanel(target, 0, 0, w, h, quads, (opts.frame && FRAMES[opts.frame]) || frame, VIEWS[opts.closeup] || VIEWS.FL);
        drawText(target, 6, h - 14, `${block.toUpperCase()} ${state.toUpperCase()} ${opts.tag || ''}`, [255, 220, 120]);
        return target;
    }
    const keys = (opts.views || 'FL,FR,BR,BL,EYE,F,S,T,NIGHT').split(',');
    const cols = 5, pw = 360, ph = 300;
    const rows = Math.ceil((keys.length + 1) / cols);
    const target = { width: cols * pw, height: rows * ph + 18, data: new Uint8ClampedArray(cols * pw * (rows * ph + 18) * 4) };
    fillBackground(target, [30, 32, 36]);
    keys.forEach((k, i) => renderPanel(target, (i % cols) * pw + 1, Math.floor(i / cols) * ph + 1, pw - 2, ph - 2, quads, frame, VIEWS[k]));
    const iconIndex = keys.length;
    renderIcon(target, (iconIndex % cols) * pw + 1, Math.floor(iconIndex / cols) * ph + 1, pw - 2, ph - 2, itemQuads(root, itemId), 'ITEM ICON (GUI x2)');
    drawText(target, 6, rows * ph + 4, `${block.toUpperCase()}  STATE=${state.toUpperCase()}  ${opts.tag || ''}`, [255, 220, 120]);
    return target;
}

/**
 * 组装台枪械表: guns 里每把枪一格 (bakeBenchGun 的结果), 同一视角、同一取景, 台子只烘一次。
 * opts: {view: VIEWS 键 (默认 FEYE), frame: FRAMES 键 (默认 bed), state: idle|active, tick 或 ticks (数组: 每把枪每个时刻一格), cols, cellW, cellH, overlay, tag}
 */
export function renderGunSheet(repo, guns, opts = {}) {
    const root = new AssetRoot([...(opts.overlay ? [opts.overlay] : []), repo]);
    const ticks = opts.ticks || (opts.tick !== undefined ? [opts.tick] : [undefined]);
    const active = ticks[0] !== undefined || opts.state === 'active';
    const arm = root.arm();
    // 台子只烘一次; 机械臂按每把枪自己的放件下沉量 (g.drops, 省略 = 不下沉) 各烘一次
    const table = benchQuads(root, active);
    const view = VIEWS[opts.view || 'FEYE'];
    if (!view) throw new Error('unknown view ' + opts.view);
    const frame = FRAMES[opts.frame || 'bed'];
    if (!frame) throw new Error('unknown frame ' + opts.frame);
    const cells = guns.flatMap((g) => ticks.map((tick) => ({ g, tick })));
    const cols = opts.cols || (ticks.length > 1 ? ticks.length : 3), pw = opts.cellW || 560, ph = opts.cellH || 250;
    const rows = Math.ceil(cells.length / cols);
    const target = { width: cols * pw, height: rows * ph + 18, data: new Uint8ClampedArray(cols * pw * (rows * ph + 18) * 4) };
    fillBackground(target, [30, 32, 36]);
    cells.forEach(({ g, tick }, i) => {
        const x0 = (i % cols) * pw + 1, y0 = Math.floor(i / cols) * ph + 1;
        const lay = g.layout;
        const label = lay ? `${g.gun.id}${g.model === 'lod' ? ' LOD' : ''}  L ${(lay.benchMax[0] - lay.benchMin[0]).toFixed(1)}  ${lay.leftSideUp ? 'LEFT' : 'RIGHT'} UP`
            + (tick !== undefined && ticks.length > 1 ? `  T ${tick}` : '') + (g.drops ? `  DROP ${g.drops[0].toFixed(2)}/${g.drops[1].toFixed(2)}` : '') : `${g.gun.id}  (NOT DRAWN)`;
        const armOpt = tick !== undefined ? { tick } : { work: active ? 1 : 0 };
        renderPanel(target, x0, y0, pw - 2, ph - 2, table.concat(g.quads, armQuads(root, arm, { ...armOpt, drops: g.drops })), frame, { ...view, label });
    });
    const tickTag = ticks[0] !== undefined ? ' TICK ' + ticks.join(',') : '';
    drawText(target, 6, rows * ph + 4, `ASSEMBLY BENCH GUNS  VIEW=${view.label}  STATE=${active ? 'ACTIVE' : 'IDLE'}${tickTag}  ${opts.tag || ''}`, [255, 220, 120]);
    return target;
}

/**
 * 查贴图方向用: 只画这把枪, 留在 TACZ FIXED 定位系 (枪口 -X, 枪顶 +Y, 枪的左侧 +Z), 左侧 / 右侧 / 俯视三格 + 槽位图标 (TACZ 的 2D 物品图)。
 * 贴图上的字、标记在侧视格里读起来是正的, 与槽位图标同一侧的样子一致, 才说明 uv 没有镜像或上下颠倒。
 */
export function renderGunFixedSheet(g) {
    const pw = 640, ph = 300;
    const target = { width: pw * 2, height: ph * 2 + 18, data: new Uint8ClampedArray(pw * 2 * (ph * 2 + 18) * 4) };
    fillBackground(target, [30, 32, 36]);
    const cams = [
        { label: 'LEFT SIDE (+Z), MUZZLE LEFT', dir: [0, 0, 1] },
        { label: 'RIGHT SIDE (-Z), MUZZLE RIGHT', dir: [0, 0, -1] },
        { label: 'TOP (+Y)', dir: [0, 1, 0.0001] },
    ];
    cams.forEach((c, i) => {
        const cam = cameraFromDir(c.dir);
        const b = projectBounds(g.quads, cam);
        const panel = { width: pw - 2, height: ph - 2, data: new Uint8ClampedArray((pw - 2) * (ph - 2) * 4) };
        fillBackground(panel, [58, 63, 72]);
        const scale = Math.min((panel.width - 40) / (b.maxX - b.minX), (panel.height - 50) / (b.maxY - b.minY));
        rasterize(g.quads, panel, { cam, scale, cx: panel.width / 2 - ((b.minX + b.maxX) / 2) * scale, cy: (panel.height + 12) / 2 - ((b.minY + b.maxY) / 2) * scale });
        drawText(panel, 6, 5, c.label, [235, 235, 235]);
        blit(target, panel, (i % 2) * pw + 1, Math.floor(i / 2) * ph + 1);
    });
    const slot = g.gun.slot && g.gun.slot.image;
    const panel = { width: pw - 2, height: ph - 2, data: new Uint8ClampedArray((pw - 2) * (ph - 2) * 4) };
    fillBackground(panel, [58, 63, 72]);
    if (slot) {
        // 能整数倍放大就整数倍 (像素图不糊), 放不下就按比例缩小
        const f = Math.min((panel.width - 20) / slot.width, (panel.height - 40) / slot.height);
        const s = f >= 1 ? Math.floor(f) : f;
        const w = Math.floor(slot.width * s), h = Math.floor(slot.height * s);
        const ox = Math.floor((panel.width - w) / 2), oy = Math.floor((panel.height - h) / 2) + 8;
        for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
            const si = (Math.min(slot.height - 1, Math.floor(y / s)) * slot.width + Math.min(slot.width - 1, Math.floor(x / s))) * 4;
            if (slot.data[si + 3] < 128) continue;
            const di = ((oy + y) * panel.width + ox + x) * 4;
            panel.data[di] = slot.data[si]; panel.data[di + 1] = slot.data[si + 1]; panel.data[di + 2] = slot.data[si + 2]; panel.data[di + 3] = 255;
        }
    }
    drawText(panel, 6, 5, slot ? 'TACZ SLOT ICON (GUI)' : 'NO SLOT ICON', [235, 235, 235]);
    blit(target, panel, pw + 1, ph + 1);
    drawText(target, 6, ph * 2 + 4, `${g.gun.id}  TACZ FIXED FRAME  MODEL ${g.gun.model.path}  TEXTURE ${g.texW}X${g.texH}`, [255, 220, 120]);
    return target;
}

/**
 * 放件下沉量 [枪机, 枪托件] (px): 与游戏同一规则 (raster.mjs benchGunPlaceDrops = GunsmithBenchGunLayout.placeDrop + 安装点枪顶),
 * gun 为 null / 不画 = 台上没枪, 两处都取 MAX_PLACE_DROP。关键帧表没有下沉参数 (旧版 Java) 时返回 null。
 */
export function previewDrops(program, gun, bed) {
    const r = benchGunPlaceDrops(program, gun, bed);
    return r ? r.drops : null;
}

if (import.meta.url === `file:///${process.argv[1].replace(/\\/g, '/')}` || process.argv[1].endsWith('render.mjs')) {
    const args = parseArgs(process.argv.slice(2));
    const gunsMode = args.guns !== undefined;
    if (!args.repo || !args.out || (!args.block && !gunsMode && !args['fixed-frame'])) {
        console.error('usage: node render.mjs --repo <root> --block press|assembly [--state idle|active] --out file.png [--overlay <候选方案根>] [--closeup FL|FR|BR|BL|F|S|T|EYE|FEYE] [--views FL,FR] [--tag text] [--tick 0..160] [--phase 0..1] [--contact EYE|T [--every 8]]'
            + ' [--gun <id> [--packs <tacz dir>]] | --guns all|<id,id> [--view FEYE] [--frame bed] | --gun <id> --fixed-frame  [--lod] [--drops auto|<bolt>,<stock>]');
        process.exit(2);
    }
    if (args.bare) throw new Error('--bare was removed: the bench always draws the bare display stack (raster.mjs taczBareDrawn)');
    const tick = args.tick !== undefined ? parseFloat(args.tick) : args.phase !== undefined ? parseFloat(args.phase) * 160 : undefined;
    if ((args.gun || gunsMode) && args.block && args.block !== 'assembly') throw new Error('--gun / --guns only apply to --block assembly');
    const packs = args.gun || gunsMode ? new TaczPacks(args.packs || DEFAULT_PACKS) : null;
    const lod = !!args.lod;
    const gun = args.gun ? bakeBenchGun(packs, args.gun, GUN_BED, { frame: args['fixed-frame'] ? 'fixed' : 'bench', lod }) : null;
    if (gun && !args['fixed-frame']) {
        const l = gun.layout;
        console.log(l ? `${args.gun} [${gun.model}]: L ${l.L.toFixed(2)} -> ${l.T.toFixed(2)} px (k ${l.k.toFixed(4)}), ${l.leftSideUp ? 'LEFT' : 'RIGHT'} side up, bench x ${l.benchMin[0].toFixed(2)}..${l.benchMax[0].toFixed(2)} y ${l.benchMin[1].toFixed(2)}..${l.benchMax[1].toFixed(2)} z ${l.benchMin[2].toFixed(2)}..${l.benchMax[2].toFixed(2)}` : `${args.gun}: not drawn (empty bbox)`);
    }
    // 放件下沉: 默认按台上的枪近似算 (auto); --drops b,s 手动指定, 0,0 = 关键帧表原样 (零件落在隐形包络上)
    const program = args.block === 'assembly' || gunsMode ? new AssetRoot([...(args.overlay ? [args.overlay] : []), args.repo]).arm().program : null;
    const dropsArg = args.drops === undefined || args.drops === true ? 'auto' : String(args.drops);
    const dropsFor = (g) => (dropsArg === 'auto' ? previewDrops(program, g, GUN_BED) : dropsArg.split(',').map(Number));
    const drops = program ? dropsFor(gun) : null;
    if (drops && !gunsMode) console.log(`place drops (${dropsArg}): bolt ${drops[0].toFixed(3)} px, stock ${drops[1].toFixed(3)} px`);
    let img;
    if (args['fixed-frame']) {
        if (!gun) throw new Error('--fixed-frame needs --gun <id>');
        img = renderGunFixedSheet(gun);
    } else if (gunsMode) {
        const ids = args.guns === true || args.guns === 'all' ? readBlueprints(args.repo).map((b) => b.id) : String(args.guns).split(',');
        const guns = ids.map((id) => bakeBenchGun(packs, id, GUN_BED, { lod }));
        for (const g of guns) {
            g.drops = program ? dropsFor(g) : null;
            if (g.drops) console.log(`${g.gun.id} [${g.model}]: place drops bolt ${g.drops[0].toFixed(3)} px, stock ${g.drops[1].toFixed(3)} px`);
        }
        // --guns 时 --tick 可以是逗号分隔的多个时刻 (每把枪每个时刻一格)
        const ticks = args.tick !== undefined && args.tick !== true ? String(args.tick).split(',').map(Number) : undefined;
        img = renderGunSheet(args.repo, guns, { view: args.view, frame: args.frame, state: args.state, tick, ticks, overlay: args.overlay, tag: args.tag !== undefined ? args.tag : lod ? 'LOD MODELS' : 'HIGH-POLY MODELS',
            cols: args.cols ? Number(args.cols) : undefined, cellW: args.cellW ? Number(args.cellW) : undefined, cellH: args.cellH ? Number(args.cellH) : undefined });
    } else if (args.contact) {
        const every = Number(args.every || 8);
        const ticks = [];
        for (let t = 0; t <= 160; t += every) ticks.push(t);
        img = renderContactSheet(args.repo, args.contact === true ? 'EYE' : args.contact, ticks, { overlay: args.overlay, tag: args.tag, gun, drops });
    } else {
        img = renderSheet(args.repo, args.block, tick !== undefined ? 'active' : args.state || 'idle', {
            closeup: args.closeup === true ? 'FL' : args.closeup,
            views: args.views,
            tag: args.tag !== undefined ? args.tag : tick !== undefined ? 'TICK ' + tick : '',
            overlay: args.overlay,
            frame: args.frame,
            tick,
            gun,
            drops,
        });
    }
    if (packs) packs.close();
    writePng(args.out, img);
    console.log('wrote', args.out, img.width + 'x' + img.height);
}
