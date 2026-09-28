#!/usr/bin/env node
// 军火台运行灯效的预览出图 (零依赖): 仓库里的静态 JSON (两格) + 按 MunitionsBenchParts / MunitionsBenchProgram (JS 镜像) 摆好的运动件
// + 计数屏的字 + 灯效覆盖层 (lights.mjs = MunitionsBenchLights 的 JS 镜像), 用 lraster.mjs 画: 玩家眼睛的透视, textBackground 批次
// (计数屏的字 + 覆盖层) 按距离从远到近排序、写深度、α < 0.1 丢弃, 顶点 α 按 Java 的 alphaByte 取整 —— 即游戏里的样子 (没有泛光)。
// 画之前先核对: MunitionsBenchGeometry 的 LIGHT_* 与 lights.mjs 相同; 仓库里的 JSON 满足 targetProblems (与生成器同一套前提)。
// 用法 (PowerShell):
//   $env:Path = 'D:\DevTools\node-v22.23.3-win-x64;' + $env:Path
//   node tools/munitions_bench/render_lights.mjs --out-dir <目录> [--repo <仓库根>] [--mode all|cycle|ladder|full|close] [--tier base,high,superior,radiant]
// 输出: lights-cycle-<档>.png (夜里玩家视角一个循环 10 帧, 默认 普通 / 高级 / 极品 / 闪耀), lights-ladder.png (六档 × t 11 / 17 / 35),
//   lights-full.png (待机满仓的闪烁各相位, 普通 / 闪耀), lights-close.png (各灯效特写: 上 = 开, 下 = 同一刻不画灯效)。
import fs from 'node:fs';
import path from 'node:path';
import * as RA from '../gunsmith_workstation/raster.mjs';
import { writePng } from '../gunsmith_workstation/png.mjs';
import { drawText } from '../gunsmith_workstation/font.mjs';
import { TIERS, tierIndex } from './tiers.mjs';
import { sampleProgram, idlePose, applyPoseMirror, bakeParts } from './ber.mjs';
import * as CT from './counter.mjs';
import * as LI from './lights.mjs';
import * as X from './lraster.mjs';
import { loadGame, CELL_OFFSETS, DEFAULT_REPO } from './render_mb.mjs';

const args = {};
{ const av = process.argv.slice(2); for (let i = 0; i < av.length; i++) if (av[i].startsWith('--')) { const n = av[i + 1]; if (n === undefined || n.startsWith('--')) args[av[i].slice(2)] = true; else { args[av[i].slice(2)] = n; i++; } } }
if (!args['out-dir']) { console.error('usage: node render_lights.mjs --out-dir <dir> [--repo <root>] [--mode all|cycle|ladder|full|close] [--tier base,high,superior,radiant]'); process.exit(2); }
const OUT = path.resolve(args['out-dir']);
const REPO = path.resolve(args.repo || DEFAULT_REPO);
const MODE = args.mode || 'all';
fs.mkdirSync(OUT, { recursive: true });

// ================================================================ 前提
const game = loadGame(REPO);
{
    const P = [];
    const geo = fs.readFileSync(path.join(REPO, 'src', 'main', 'java', 'com', 'miningdim', 'job', 'munitions', 'block', 'MunitionsBenchGeometry.java'), 'utf8');
    const d = LI.layoutDiff(LI.parseLightsJava(geo), LI.lightsLayout());
    if (d) P.push('LIGHT_* in MunitionsBenchGeometry.java differ from lights.mjs (rerun the generator): ' + d);
    const models = TIERS.map((T) => Object.fromEntries(['idle', 'active'].map((state) => [state, LI.elementsOf(Object.entries(CELL_OFFSETS).map(([cell, offset]) => ({
        model: game.root.model(`miningdim:block/munitions_bench${T.suffix}_line_${cell}${state === 'active' ? '_active' : ''}`), offset,
    })))])));
    P.push(...LI.targetProblems(models), ...LI.programProblems(game.program));
    if (P.length) { console.error('PREMISE PROBLEMS:\n  ' + P.join('\n  ')); process.exit(1); }
    console.log('premises ok (LIGHT_* = lights.mjs, targets on the repo JSON, program beats)');
}

// ================================================================ 一帧
const GROUND = (() => {
    const data = new Uint8ClampedArray(16 * 16 * 4);
    for (let y = 0; y < 16; y++) for (let x = 0; x < 16; x++) {
        const v = x === 0 || y === 0 ? 150 : ((x + y) & 1 ? 196 : 190);
        const o = (y * 16 + x) * 4; data[o] = v; data[o + 1] = v + 2; data[o + 2] = v + 6; data[o + 3] = 255;
    }
    return { width: 16, height: 16, data };
})();
const groundQ = RA.groundQuads(-1, 3, -1, 2, GROUND);
const staticCache = new Map();
function staticQuads(tier, active) {
    const key = tier + '|' + active;
    if (!staticCache.has(key)) {
        const q = [];
        for (const [cell, offset] of Object.entries(CELL_OFFSETS)) {
            q.push(...RA.bakeBlockModel(game.root.model(`miningdim:block/munitions_bench${TIERS[tier].suffix}_line_${cell}${active ? '_active' : ''}`), { offset, textureLookup: (id) => game.root.image(id) }));
        }
        staticCache.set(key, q.concat(groundQ));
    }
    return staticCache.get(key);
}
/** 计数屏样例 (工作 / 待机 = 347 / 800 发; 满仓 = 790 / 800)。 */
const COUNTER = { work: { rounds: 347, cap: 800, caliber: 1, full: false }, idle: { rounds: 347, cap: 800, caliber: 1, full: false }, full: { rounds: 790, cap: 800, caliber: 1, full: true } };
function counterQuads(tier, sample, working) {
    const L = game.counter;
    return CT.counterRects(L, { rounds: sample.rounds, caliberLabel: CT.CALIBER_LABELS[sample.caliber], cap: sample.cap, full: sample.full }).map((r) => {
        const pts = CT.rectCorners(L, r), col = CT.colourOf(L, tier, r.role, working), stencil = r.face === CT.FACE_STENCIL;
        return { pts, cols: pts.map(() => [col[0], col[1], col[2], 1]), normal: CT.windingNormal(pts), fullBright: !stencil, shaded: stencil, cull: true };
    });
}
/** 游戏里顶点 α 是字节 (MunitionsBenchLights.alphaByte = round(a × 255))。 */
const alphaByte = (a) => Math.max(0, Math.min(255, Math.round(Math.fround(a) * 255)));
function overlayQuads(rects) {
    return rects.map((o) => {
        const c = LI.overlayCorners(o);
        return { pts: c.pts, cols: c.cols.map((k) => [k[0], k[1], k[2], alphaByte(k[3]) / 255]), normal: c.normal, fullBright: true, shaded: LI.TARGETS[o.target].shade, cull: true };
    });
}
/** 很早就开工了 (不看第一轮不画的接缝尾巴); 4000 是 40 与 80 的倍数, 所以循环 tick = 呼吸相位 = t。 */
const LONG_RUNNING = 4000;
/** 玩家眼睛 (主格正前方 1.6 格, 眼高 1.62 格) 到主格中心的距离 (格): 呼吸的距离渐隐用。 */
const EYE = { kind: 'persp', eye: [12, 25.92, -25.6], target: [16, 10, 8], fovY: 70 };
const EYE_DIST = Math.hypot(12 - 8, 25.92 - 8, -25.6 - 8) / 16;
/**
 * ui: {tier, mode ('work' | 'idle' | 'full'), t (工作: 循环 tick; 待机: 客户端时钟), lights (默认 true)}。
 * 返回 {static, dynamic (运动件), textBg (计数屏 + 覆盖层), rects}。
 */
function frame(ui) {
    const active = ui.mode === 'work', full = ui.mode === 'full';
    const whole = Math.floor(ui.t), partial = ui.t - whole;
    const s = LI.lightFrame({ active, full, elapsedTicks: LONG_RUNNING + whole, gameTime: LONG_RUNNING + whole, partialTick: partial, distance: EYE_DIST }, game.program);
    const pose = active ? sampleProgram(game.program, s.cycleTick) : idlePose(game.program);
    const rects = ui.lights === false ? [] : LI.lightOverlays(s, ui.tier);
    return {
        static: staticQuads(ui.tier, active),
        dynamic: bakeParts(game.parts, applyPoseMirror(game.parts, pose), game.partsImage, { entity: true }),
        textBg: counterQuads(ui.tier, COUNTER[ui.mode], active).concat(overlayQuads(rects)),
        rects,
    };
}
const MACHINE_BOX = { min: [-0.5, 0, -0.5], max: [32.5, 24.5, 16] };
const NIGHT = { bg: [16, 19, 25], brightness: 0.18 }, DAY = { bg: [58, 63, 72], brightness: 1 }, INDOOR = { bg: [40, 37, 34], brightness: 0.8 };
function panel(ui, cam, w, h, light, box = MACHINE_BOX, zoom = 1) {
    const view = X.fitView(X.makeCamera(cam), box, w, h, { zoom });
    const t = X.newTarget(w, h, light.bg);
    const f = frame(ui);
    X.drawQuads(f.static, t, view, { brightness: light.brightness });
    X.drawQuads(f.dynamic, t, view, { brightness: light.brightness });
    X.drawQuads(f.textBg, t, view, { brightness: light.brightness, sort: true });
    return { img: t, rects: f.rects };
}

// ================================================================ 组图
function sheet(cols, rows, pw, ph, extraH = 24) {
    const img = { width: cols * pw, height: rows * ph + extraH, data: new Uint8ClampedArray(cols * pw * (rows * ph + extraH) * 4) };
    for (let i = 0; i < img.data.length; i += 4) { img.data[i] = 30; img.data[i + 1] = 32; img.data[i + 2] = 36; img.data[i + 3] = 255; }
    return img;
}
function blit(dst, src, x0, y0) {
    for (let y = 0; y < src.height; y++) for (let x = 0; x < src.width; x++) {
        const tx = x0 + x, ty = y0 + y; if (tx < 0 || ty < 0 || tx >= dst.width || ty >= dst.height) continue;
        const si = (y * src.width + x) * 4, di = (ty * dst.width + tx) * 4;
        dst.data[di] = src.data[si]; dst.data[di + 1] = src.data[si + 1]; dst.data[di + 2] = src.data[si + 2]; dst.data[di + 3] = 255;
    }
}
const label = (img, x, y, s, col = [235, 235, 235]) => drawText(img, x, y, String(s).toUpperCase(), col);
const save = (name, img) => { const p = path.join(OUT, name); writePng(p, img); console.log('wrote', p, img.width + 'x' + img.height); };
const effectsIn = (rects) => [...new Set(rects.map((r) => LI.EFFECTS[r.effect].id))].join(' ') || '-';
const unlocked = (tier) => LI.EFFECTS.filter((e) => tier >= e.unlock).map((e) => e.id).join(' ');

const TICKS = [38, 0, 5, 10, 11.5, 16, 20, 25, 35, 36.5];
const PHASE = { 38: 'FEED LED', 0: 'PRIME LED', 5: 'POWDER LED', 10: 'STRIKE', 11.5: 'FLASH DECAY', 16: 'BELT STEP', 20: 'BELT STEP', 25: 'BELT STOP', 35: 'DROP LANDS', 36.5: 'PULSE DECAY' };
const tiers = args.tier ? String(args.tier).split(',').map(tierIndex) : [0, 2, 3, 5];

if (MODE === 'all' || MODE === 'cycle') {
    for (const tier of tiers) {
        const pw = 480, ph = 320, cols = 5;
        const img = sheet(cols, 2, pw, ph);
        TICKS.forEach((t, i) => {
            const { img: p, rects } = panel({ tier, mode: 'work', t }, EYE, pw - 2, ph - 2, NIGHT, MACHINE_BOX, 1.12);
            const x = (i % cols) * pw + 1, y = Math.floor(i / cols) * ph + 1;
            blit(img, p, x, y);
            label(img, x + 6, y + 5, `T ${t}  ${PHASE[t]}`);
            label(img, x + 6, y + ph - 20, `${rects.length} QUADS: ${effectsIn(rects)}`, [150, 220, 255]);
        });
        label(img, 6, img.height - 18, `NIGHT  EYE 1.6 BLK  ${TIERS[tier].en}  SHIPPED TIER LADDER: ${unlocked(tier)}`, [255, 220, 120]);
        save(`lights-cycle-${TIERS[tier].key}.png`, img);
    }
}

if (MODE === 'all' || MODE === 'ladder') {
    const pw = 400, ph = 270, ticks = [11, 17, 35];
    const img = sheet(6, ticks.length, pw, ph);
    for (let tier = 0; tier < 6; tier++) ticks.forEach((t, r) => {
        const { img: p, rects } = panel({ tier, mode: 'work', t }, EYE, pw - 2, ph - 2, NIGHT);
        blit(img, p, tier * pw + 1, r * ph + 1);
        label(img, tier * pw + 7, r * ph + 6, `${TIERS[tier].en} T ${t}`);
        label(img, tier * pw + 7, r * ph + ph - 20, effectsIn(rects), [150, 220, 255]);
    });
    label(img, 6, img.height - 18, 'TIER LADDER: LEDS STRIKE DROP FULL EVERY TIER; BREATH FROM HIGH; CHASE FROM SUPERIOR; GEM RADIANT', [255, 220, 120]);
    save('lights-ladder.png', img);
}

if (MODE === 'all' || MODE === 'full') {
    const pw = 400, ph = 270;
    const cols = [
        ['IDLE NOT FULL', 'idle', 10, NIGHT], ['FULL C 2 RISING', 'full', 2, NIGHT], ['FULL C 10 ON', 'full', 10, NIGHT],
        ['FULL C 30 OFF', 'full', 30, NIGHT], ['FULL C 10 INDOOR', 'full', 10, INDOOR], ['FULL C 10 DAY', 'full', 10, DAY],
    ];
    const img = sheet(cols.length, 2, pw, ph);
    [0, 5].forEach((tier, r) => cols.forEach(([name, mode, t, light], i) => {
        const { img: p, rects } = panel({ tier, mode, t }, EYE, pw - 2, ph - 2, light);
        blit(img, p, i * pw + 1, r * ph + 1);
        label(img, i * pw + 7, r * ph + 6, `${TIERS[tier].en} ${name}`);
        label(img, i * pw + 7, r * ph + ph - 20, `${rects.length} QUADS: ${effectsIn(rects)}`, [150, 220, 255]);
    }));
    label(img, 6, img.height - 18, 'IDLE + BUFFER FULL: CAN STRIP AMBER BLINK 0.5 HZ (CLIENT CLOCK); IDLE WITHOUT FULL = NO OVERLAY', [255, 220, 120]);
    save('lights-full.png', img);
}

if (MODE === 'all' || MODE === 'close') {
    // 特写 (玩家视角或正交), 每格一对: 上 = 灯效开, 下 = 同一刻不画灯效
    const pw = 520, ph = 300;
    const eyeAt = (x, y, z) => ({ kind: 'persp', eye: [12, 25.92, -25.6], target: [x, y, z], fovY: 70 });
    const shots = [
        ['LED PRIME T 0.3', 0, 0.3, eyeAt(22.5, 7.25, 0), { min: [19, 6.5, -0.5], max: [28, 8, 0.8] }],
        ['LED+BREATH T 5 HIGH', 2, 5, eyeAt(18.5, 7.25, 0), { min: [13, 6.5, -0.5], max: [24, 8, 0.8] }],
        ['CROWN T 10.3 BASE', 0, 10.3, eyeAt(14.5, 19.25, 3.5), { min: [11.5, 18.5, 3], max: [17.5, 20, 4] }],
        ['CHASE T 17 SUPERIOR', 3, 17, { kind: 'ortho', yaw: 20, pitch: 40 }, { min: [7.5, 8.5, 5], max: [31.5, 11, 10.5] }],
        ['CAN T 35.3 BASE', 0, 35.3, eyeAt(4, 2.75, 1), { min: [0, 2, 0.5], max: [8.8, 3.6, 1.6] }],
        ['GEM T 10.3 RADIANT', 5, 10.3, { kind: 'ortho', yaw: 38, pitch: 32 }, { min: [12.5, 21.5, 5.5], max: [16.5, 25, 9.5] }],
    ];
    const img = sheet(3, 4, pw, ph);
    shots.forEach(([name, tier, t, cam, box], i) => {
        for (const [row, lights] of [[0, true], [1, false]]) {
            const { img: p } = panel({ tier, mode: 'work', t, lights }, cam, pw - 2, ph - 2, NIGHT, box);
            const x = (i % 3) * pw + 1, y = (Math.floor(i / 3) * 2 + row) * ph + 1;
            blit(img, p, x, y);
            label(img, x + 6, y + 5, `${name} ${lights ? 'ON' : 'OFF'}`);
        }
    });
    label(img, 6, img.height - 18, 'NIGHT CLOSE-UPS: ROW PAIRS = LIGHTS ON / OFF AT THE SAME MOMENT', [255, 220, 120]);
    save('lights-close.png', img);
}
