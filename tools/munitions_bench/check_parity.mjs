#!/usr/bin/env node
// 游戏里的样子 vs 方案 B v2 的对拍 (零依赖):
//   游戏 = 仓库里的静态 JSON (两格) + 按 MunitionsBenchParts.java 烘出来、按 MunitionsBenchProgram.java 的 JS 镜像摆好的运动件;
//   方案 = 候选生成器自己写出的逐帧 / 待机 / 工作 JSON 模型 (运动件烤在 JSON 里)。
// 逐像素比较 (运动件按方块面明暗画, 与 JSON 同一套明暗): 普通档 f0..f7, 六档待机 / 工作 (工作 = 冲头到底那一刻), 六档物品图标;
// (相对方案有意补上的几个运动件面 DELIBERATE_FACES 在对拍时不画, 见下);
// 另外检查: 四个朝向下静态模型 (方块状态 y 旋转 + 副格在顺时针一侧) 与运动件 (渲染器的朝向角) 是否仍对齐;
// 游戏里运动件不剔除背面, 一个循环里各视角 (含仰视) 都不许有件的背面露出来 (parity-backfaces.png);
// 若本机有 JDK (JAVA_HOME 或 PATH 里的 javac), 单独编译 MunitionsBenchProgram.java, 逐 tick 与 JS 镜像对拍;
// 再单独编译 MunitionsBenchCounter.java + MunitionsBenchGeometry.java (计数屏), 数字格式、字形、排版、满度条、满仓、显示键、颜色
// 与渲染器摆角用的 benchCorners / blockCorners (四个朝向) 与 counter.mjs 逐值对拍, 布局常量与 parseCounterJava 解析出来的对拍,
// 口径标签与 MunitionsCaliber.java 对拍;
// 再单独编译 MunitionsBenchLights.java (运行灯效, 连同 Geometry / Counter / Program), 六档 × 工作 / 待机 / 满仓 × 每 0.25 tick 与 lights.mjs 逐值对拍,
// Geometry 的 LIGHT_* 与 lights.mjs 的 lightsLayout() 对拍。
// 用法:
//   node tools/munitions_bench/check_parity.mjs [--cand <候选输出目录 (generate.mjs --out 的目录)>] --out-dir <目录> [--repo <仓库根>] [--no-java]
//   候选输出: cd .candidates; node munitions_b/generate.mjs --out <目录>
//   不给 --cand 时跳过与方案 B v2 的逐像素对拍 (弹药箱换成计数屏方案 C 之后, 箱盖与箱身正面本来就与 B v2 不同), 其余照做。
// 输出: parity-report.md、parity-frames.png、parity-tiers.png、parity-items.png, 以及游戏样子的参考图 (实体光照):
//   game-motion.png (每 2.5 tick 一列)、game-base-active.png、game-base-idle.png、game-tiers.png、game-shapes.png (碰撞箱线框)。
// 退出码: 有像素差异、朝向不对齐或 Java 对拍失败时为 1。
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import * as R from '../gunsmith_workstation/raster.mjs';
import { writePng } from '../gunsmith_workstation/png.mjs';
import { TIERS } from './tiers.mjs';
import {
    applyPoseMirror, bakeParts, idlePose, sampleProgram, sampleProgramAt, programTick, cycleTicksOf, strikeTickOf, isStrikeTickMirror, nextStrikeTickAfterMirror,
    sampleRunningMirror, strikeBetweenMirror, POSE_FLOATS, POSE_BOOLS,
} from './ber.mjs';
import {
    DEFAULT_REPO, loadGame, loadCandidate, gameQuads, candQuads, gameItem, candItem, renderRaw, newImage, blit, FRAMES, VIEWS, footer,
    motionStrip, tierSheet, tierStrip, closeup, CELL_OFFSETS,
} from './render_mb.mjs';
import { drawText } from '../gunsmith_workstation/font.mjs';
import * as C from './counter.mjs';
import { CALIBER_LABELS } from './counter.mjs';
import * as LI from './lights.mjs';

function parseArgs(argv) {
    const a = {};
    for (let i = 0; i < argv.length; i++) if (argv[i].startsWith('--')) { const n = argv[i + 1]; if (n === undefined || n.startsWith('--')) a[argv[i].slice(2)] = true; else { a[argv[i].slice(2)] = n; i++; } }
    return a;
}
const ARGS = parseArgs(process.argv.slice(2));
if (!ARGS['out-dir'] || ARGS.cand === true) { console.error('usage: node check_parity.mjs [--cand <candidate out dir>] --out-dir <dir> [--repo <root>] [--no-java]'); process.exit(2); }
const REPO = path.resolve(ARGS.repo || DEFAULT_REPO);
const OUT = path.resolve(ARGS['out-dir']);
fs.mkdirSync(OUT, { recursive: true });
const game = loadGame(REPO);
const cand = ARGS.cand ? loadCandidate(ARGS.cand) : null;
const problems = [];
const report = [];

// ================================================================ 逐像素比较
const DIFF_VIEWS = ['EYE', 'FL', 'FR', 'F', 'T', 'BL', 'S', 'NIGHT_EYE'];
const W = 420, H = 340;
const JITTER = [0.137, 0.071];
/**
 * 相对方案 B v2 有意补上的运动件面 (实装评审): 方案里省掉了 (方案按 JSON 的规矩剔除背面, 缺面只是透过去), 游戏里运动件用
 * entityCutoutNoCull, 缺了就能看进件里。与方案逐像素对拍时不画这些面 (剔除背面时不画它们就是方案的样子); 游戏里有没有背面
 * 露出来由下面的"背面检查"单独兜住。[件, 世界面]
 */
const DELIBERATE_FACES = [['ram_die', 'up'], ['ram_die_hot', 'up'], ['ram_bullet', 'down']];
const asCandidate = (quads) => quads.filter((q) => !DELIBERATE_FACES.some(([part, face]) => q.part === part && q.face === face));
function diffImages(a, b) {
    let n = 0;
    const img = newImage(a.width, a.height);
    for (let i = 0; i < a.data.length; i += 4) {
        const d = Math.max(Math.abs(a.data[i] - b.data[i]), Math.abs(a.data[i + 1] - b.data[i + 1]), Math.abs(a.data[i + 2] - b.data[i + 2]));
        const g = Math.round((a.data[i] * 0.3 + a.data[i + 1] * 0.59 + a.data[i + 2] * 0.11) * 0.45);
        if (d > 0) { n++; img.data[i] = 255; img.data[i + 1] = 40; img.data[i + 2] = 40; } else { img.data[i] = g; img.data[i + 1] = g; img.data[i + 2] = g; }
        img.data[i + 3] = 255;
    }
    return { n, img };
}
/** 一个对拍用例: 每个视角渲染两边、数不同的像素。返回 {rows: [{view, n}], panels: {view: {a, b, d}}} */
function compareCase(label, qa, qb, views = DIFF_VIEWS, frame = FRAMES.machine) {
    const rows = [], panels = {};
    for (const v of views) {
        // 亚像素错开: 正视/侧视的像素中心会正好压在贴图像素分界上 (取哪一格只看浮点误差), 两边同样错开
        const a = renderRaw(W, H, qa, frame, VIEWS[v], null, JITTER).panel, b = renderRaw(W, H, qb, frame, VIEWS[v], null, JITTER).panel;
        const d = diffImages(a, b);
        rows.push({ view: v, n: d.n });
        panels[v] = { a, b, d: d.img };
        if (d.n) problems.push(`${label} ${v}: ${d.n} px differ`);
    }
    return { rows, panels };
}
const withLabel = (img, text) => { drawText(img, 6, 5, text, [235, 235, 235]); return img; };

// ---- 普通档逐帧 + 运动件特写 (以下三节只在给了 --cand 时做)
const frameRows = [];
const tierRows = [];
const itemRows = [];
if (cand) {
const frameSheet = newImage(6 * W, 8 * H + 20);
for (let f = 0; f < 8; f++) {
    const tick = f * 5;
    const qg = asCandidate(gameQuads(game, 0, 'active', tick, { blockLight: true }));
    const qc = candQuads(cand, 0, 'active', f);
    const c = compareCase(`base f${f} (t ${tick})`, qc, qg);
    const focus = compareCase(`base f${f} (t ${tick}) focus`, qc, qg, ['FL', 'F', 'T'], FRAMES.focus);
    frameRows.push({ f, tick, rows: [...c.rows, ...focus.rows.map((r) => ({ view: 'FOCUS ' + r.view, n: r.n }))] });
    const P = [[c.panels.EYE.a, `F${f} CANDIDATE EYE`], [c.panels.EYE.b, `F${f} IN-GAME T ${tick}`], [c.panels.EYE.d, `F${f} DIFF ${c.rows[0].n} PX`],
        [focus.panels.FL.a, `F${f} CANDIDATE FOCUS`], [focus.panels.FL.b, `F${f} IN-GAME FOCUS`], [focus.panels.FL.d, `F${f} DIFF ${focus.rows[0].n} PX`]];
    P.forEach(([img, text], i) => blit(frameSheet, withLabel(img, text), i * W, f * H));
}
footer(frameSheet, 'PARITY BASE TIER F0..F7: CANDIDATE B V2 FRAME JSON  VS  STATIC LINE JSON + BER PARTS (MunitionsBenchParts.java) POSED BY MunitionsBenchProgram (JS MIRROR), BLOCK-SHADED');
writePng(path.join(OUT, 'parity-frames.png'), frameSheet);

// ---- 六档 待机 / 工作
const tierSheetImg = newImage(6 * W, 6 * H + 20);
for (let t = 0; t < 6; t++) {
    const ci = compareCase(`${TIERS[t].key} idle`, candQuads(cand, t, 'idle'), asCandidate(gameQuads(game, t, 'idle', null, { blockLight: true })));
    const ca = compareCase(`${TIERS[t].key} active`, candQuads(cand, t, 'active'), asCandidate(gameQuads(game, t, 'active', game.program.strikeTick, { blockLight: true })));
    tierRows.push({ t, idle: ci.rows, active: ca.rows });
    const P = [[ci.panels.EYE.a, `${TIERS[t].en} CAND IDLE`], [ci.panels.EYE.b, `${TIERS[t].en} GAME IDLE`], [ci.panels.EYE.d, `DIFF ${ci.rows[0].n} PX`],
        [ca.panels.NIGHT_EYE.a, `${TIERS[t].en} CAND ACTIVE NIGHT`], [ca.panels.NIGHT_EYE.b, `${TIERS[t].en} GAME ACTIVE NIGHT`], [ca.panels.NIGHT_EYE.d, `DIFF ${ca.rows[ca.rows.length - 1].n} PX`]];
    P.forEach(([img, text], i) => blit(tierSheetImg, withLabel(img, text), i * W, t * H));
}
footer(tierSheetImg, 'PARITY 6 TIERS: IDLE (EYE) AND ACTIVE AT THE STRIKE (NIGHT EYE), CANDIDATE VS IN-GAME, BLOCK-SHADED');
writePng(path.join(OUT, 'parity-tiers.png'), tierSheetImg);

// ---- 物品图标 (32 px)
const itemSheet = newImage(6 * 3 * 100, 140);
for (let t = 0; t < 6; t++) {
    const icon = (item) => { const n = 32, img = newImage(n, n, [139, 139, 139]); R.rasterize(R.guiTransform(item.quads, item.display), img, { cam: R.cameraFromDir([0, 0, 1]), scale: n / 16, cx: n / 2, cy: n / 2 }); return img; };
    const a = icon(candItem(cand, t)), b = icon(gameItem(game, t));
    const d = diffImages(a, b);
    itemRows.push({ t, n: d.n });
    if (d.n) problems.push(`${TIERS[t].key} item icon: ${d.n} px differ`);
    const up = (img) => { const k = 3, out = newImage(96, 96); for (let y = 0; y < 96; y++) for (let x = 0; x < 96; x++) { const si = (Math.floor(y / k) * 32 + Math.floor(x / k)) * 4, di = (y * 96 + x) * 4; for (let c = 0; c < 4; c++) out.data[di + c] = img.data[si + c]; } return out; };
    [a, b, d.img].forEach((img, i) => blit(itemSheet, up(img), (t * 3 + i) * 100 + 2, 20));
    drawText(itemSheet, t * 300 + 4, 4, `${TIERS[t].en} ${d.n} PX`, [235, 235, 235]);
}
footer(itemSheet, 'ITEM ICONS 32 PX: CANDIDATE / IN-GAME / DIFF');
writePng(path.join(OUT, 'parity-items.png'), itemSheet);
}

// ================================================================ 四个朝向
// 期望: 朝北的整台四边形绕主格中心 (8, 8) 按方块状态的 y 旋转 (俯视顺时针) 转过去; 实际: 各格模型按 y 旋转烘、副格放在
// facing.getClockWise() 那一格, 运动件按渲染器的朝向角烘。两边的四边形集合必须相同。
const FACINGS = [['north', 0, 0, [16, 0, 0]], ['east', 1, -90, [0, 0, 16]], ['south', 2, 180, [-16, 0, 0]], ['west', 3, 90, [0, 0, -16]]];
const rotCW = (p, k) => { let [x, y, z] = p; for (let i = 0; i < k; i++) { const nx = 16 - z; z = x; x = nx; } return [x, y, z]; };
const rd = (v) => Math.round(v * 1000) / 1000 + 0;
const quadSig = (q) => JSON.stringify([q.image.id || '', q.pts.map((p, i) => JSON.stringify([p.map(rd), q.uvs[i].map(rd)])).sort()]);
const facingRows = [];
{
    const pose = sampleProgram(game.program, 17.5);   // 皮带走到一半、冲头回位: 所有通道都不在 0
    const placed = applyPoseMirror(game.parts, pose);
    const northStatic = [];
    for (const [cell, off] of Object.entries(CELL_OFFSETS)) northStatic.push(...R.bakeBlockModel(game.root.model(`miningdim:block/munitions_bench_line_${cell}_active`), { offset: off, textureLookup: (i) => game.root.image(i) }));
    const northParts = bakeParts(game.parts, placed, game.partsImage, { entity: true });
    for (const [facing, k, rotDeg, extOff] of FACINGS) {
        const expected = [...northStatic, ...northParts].map((q) => ({ ...q, pts: q.pts.map((p) => rotCW(p, k)) })).map(quadSig).sort();
        const actual = [];
        for (const [cell, off] of [['main', [0, 0, 0]], ['extension', extOff]]) actual.push(...R.bakeBlockModel(game.root.model(`miningdim:block/munitions_bench_line_${cell}_active`), { offset: off, yRot: k * 90, textureLookup: (i) => game.root.image(i) }));
        actual.push(...bakeParts(game.parts, placed, game.partsImage, { entity: true, rotDeg }));
        const got = actual.map(quadSig).sort();
        const same = got.length === expected.length && got.every((s, i) => s === expected[i]);
        facingRows.push({ facing, ok: same, quads: got.length });
        if (!same) problems.push(`facing ${facing}: static models / BER parts do not line up (block state y ${k * 90}, renderer rotation ${rotDeg})`);
    }
}

// ================================================================ 背面检查 (游戏里运动件用 entityCutoutNoCull, 不剔除背面)
// 普通档一个循环每 1.25 tick 一个时刻 + 待机, 7 个俯视/平视角 + 2 个仰视角 (台子放在高处时), 运动件按实体光照
// 各画一遍 "不剔除" (游戏) 与 "剔除": 有一个像素不同, 就是有件的背面露了出来 (缺面、或从缝里看进件里)。
// 仰视时台子下面垫着它所在的那一格方块 (x 0..32, z 0..16, 一格高): 原版方块模型贴地的底面本来就不建, 正交仰视会从机身底下
// 看进去, 那不是游戏里能看到的样子。
const SUPPORT_IMAGE = { width: 16, height: 16, data: new Uint8ClampedArray(16 * 16 * 4).map((_, i) => (i % 4 === 3 ? 255 : [104, 108, 116][i % 4])) };
function supportQuads() {
    const [x0, x1, y0, y1, z0, z1] = [0, 32, -16, -0.02, 0, 16];
    const face = (pts, normal) => ({ pts, uvs: [[0, 0], [16, 0], [16, 16], [0, 16]], normal, image: SUPPORT_IMAGE, uvScale: 16, cull: true, shade: 0.8 });
    return [
        face([[x0, y0, z0], [x1, y0, z0], [x1, y1, z0], [x0, y1, z0]], [0, 0, -1]),
        face([[x0, y0, z1], [x1, y0, z1], [x1, y1, z1], [x0, y1, z1]], [0, 0, 1]),
        face([[x0, y0, z0], [x0, y0, z1], [x0, y1, z1], [x0, y1, z0]], [-1, 0, 0]),
        face([[x1, y0, z0], [x1, y0, z1], [x1, y1, z1], [x1, y1, z0]], [1, 0, 0]),
        face([[x0, y0, z0], [x1, y0, z0], [x1, y0, z1], [x0, y0, z1]], [0, -1, 0]),
    ];
}
const backfaceRows = [];
{
    const views = { EYE: VIEWS.EYE, FL: VIEWS.FL, FR: VIEWS.FR, F: VIEWS.F, T: VIEWS.T, BL: VIEWS.BL, S: VIEWS.S,
        LOW_FL: { label: 'LOW FRONT-LEFT', yaw: 30, pitch: -25 }, LOW_FR: { label: 'LOW FRONT-RIGHT', yaw: -30, pitch: -25 } };
    const cases = [['idle', null], ...Array.from({ length: 32 }, (_, i) => ['active', i * 1.25])];
    let shown = 0;
    const sheet = newImage(4 * W, 2 * H + 20);
    for (const [state, tick] of cases) {
        const q = [...gameQuads(game, 0, state, tick, {}), ...supportQuads()];
        const qc = q.map((x) => (x.part ? { ...x, cull: true } : x));
        for (const [name, v] of Object.entries(views)) {
            const a = renderRaw(W, H, q, FRAMES.focus, v).panel, b = renderRaw(W, H, qc, FRAMES.focus, v).panel;
            const d = diffImages(a, b);
            if (!d.n) continue;
            backfaceRows.push({ state, tick, view: name, n: d.n });
            problems.push(`back faces of moving parts visible: ${state}${tick === null ? '' : ' t ' + tick} ${name}: ${d.n} px`);
            if (shown < 4) { blit(sheet, withLabel(a, `${state.toUpperCase()} T ${tick} ${name} GAME`), shown * W, 0); blit(sheet, withLabel(d.img, `${d.n} PX BACK FACE`), shown * W, H); shown++; }
        }
    }
    if (shown) { footer(sheet, 'MOVING-PART BACK FACES VISIBLE IN GAME (entityCutoutNoCull): GAME / DIFF VS CULLED'); writePng(path.join(OUT, 'parity-backfaces.png'), sheet); }
}

// ================================================================ Java 对拍 (MunitionsBenchProgram 单独编译, 与 JS 镜像逐 tick 比较)
const findJavac = () => [process.env.JAVA_HOME && path.join(process.env.JAVA_HOME, 'bin', os.platform() === 'win32' ? 'javac.exe' : 'javac'), 'javac'].find((p) => { try { execFileSync(p, ['-version'], { stdio: 'ignore' }); return true; } catch { return false; } });
let javaResult = 'skipped (--no-java)';
if (!ARGS['no-java']) {
    const javac = findJavac();
    if (!javac) javaResult = 'skipped (no javac: set JAVA_HOME)';
    else {
        const java = path.join(path.dirname(javac), os.platform() === 'win32' ? 'java.exe' : 'java');
        const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'mb-program-'));
        const harness = [
            'import com.miningdim.job.munitions.block.MunitionsBenchProgram;',
            'public class Harness {',
            '    static String row(String k, MunitionsBenchProgram.Pose p) {',
            '        return k + " " + p.beltX + " " + p.primeY + " " + p.powderY + " " + p.ramY + " " + p.dropY + " " + p.dieHeat + " " + p.ramBulletVisible + " " + p.powderCharged + " " + p.seated;',
            '    }',
            '    public static void main(String[] a) {',
            '        StringBuilder sb = new StringBuilder();',
            '        MunitionsBenchProgram.Pose p = new MunitionsBenchProgram.Pose();',
            '        for (int i = -40; i <= 360; i++) { float t = i * 0.25F; sb.append(row("f " + t, MunitionsBenchProgram.sample(t, p))).append("\\n"); }',
            '        // 每档的时间映射 (游戏时间 → 程序时间, 越界的档位 -1 / 6 按普通档): 很大的 long (连开几天 .. 2^52) × 0.25 倍数与任意的 float partialTick',
            '        long[] es = {0L, 1L, 9L, 10L, 19L, 20L, 39L, 40L, 41L, 123457L, 2000000011L, 17179869183L, 40L * 1000000007L + 38L, 10080L * 100000000000L + 7L, 4503599627370495L, -1L, -41L};',
            '        float[] ps = {0.0F, 0.25F, 0.5F, 0.75F, 0.1F, 0.15957803F, 0.4065408F, 0.8456556F, 0.9999F};',
            '        for (int tier = -1; tier <= 6; tier++) for (long e : es) for (float pt : ps) sb.append(row("l " + tier + " " + e + " " + pt + " " + MunitionsBenchProgram.programTick(e, pt, tier), MunitionsBenchProgram.sample(e, pt, tier, p))).append("\\n");',
            '        // 每档逐 tick 两个多循环 (含起点前), 同样的 partialTick',
            '        for (int tier = 0; tier <= 5; tier++) for (long e = -2L; e <= 2L * MunitionsBenchProgram.cycleTicks(tier) + 1L; e++) for (float pt : ps) sb.append(row("l " + tier + " " + e + " " + pt + " " + MunitionsBenchProgram.programTick(e, pt, tier), MunitionsBenchProgram.sample(e, pt, tier, p))).append("\\n");',
            '        sb.append(row("idle", MunitionsBenchProgram.idle(p))).append("\\n");',
            '        for (int tier = -1; tier <= 6; tier++) {',
            '            sb.append("c " + tier + " " + MunitionsBenchProgram.cycleTicks(tier) + " " + MunitionsBenchProgram.strikeTick(tier)).append("\\n");',
            '            for (long e = -45; e <= 125; e++) sb.append("s " + tier + " " + e + " " + MunitionsBenchProgram.isStrikeTick(e, tier) + " " + MunitionsBenchProgram.nextStrikeTickAfter(e, tier)).append("\\n");',
            '            // 渲染器每帧的决定: 工作时的姿态 (起点比本地时钟快时停在首帧) 与火花 ((上一帧, 这一帧] 里有没有冲压 tick; 隔几帧、隔一两个循环)',
            '            for (long e = -3L; e <= 2L * MunitionsBenchProgram.cycleTicks(tier) + 1L; e++) for (float pt : ps) sb.append(row("r " + tier + " " + e + " " + pt, MunitionsBenchProgram.sampleRunning(e, pt, tier, p))).append("\\n");',
            '            for (long since = -45; since <= 125; since++) for (long gap : new long[]{-1L, 0L, 1L, 2L, 3L, 4L, 19L, 20L, 21L, 41L}) sb.append("b " + tier + " " + since + " " + (since + gap) + " " + MunitionsBenchProgram.strikeBetween(since, since + gap, tier)).append("\\n");',
            '        }',
            '        System.out.print(sb);',
            '    }',
            '}',
        ].join('\n');
        fs.writeFileSync(path.join(tmp, 'Harness.java'), harness);
        try {
            execFileSync(javac, ['-encoding', 'UTF-8', '-d', tmp, path.join(REPO, 'src', 'main', 'java', 'com', 'miningdim', 'job', 'munitions', 'block', 'MunitionsBenchProgram.java'), path.join(tmp, 'Harness.java')], { stdio: 'pipe' });
            const out = execFileSync(java, ['-cp', tmp, 'Harness'], { encoding: 'utf8' }).trim().split(/\r?\n/);
            const P = game.program;
            let bad = 0, n = 0, mapped = 0, running = 0, between = 0;
            const cmp = (key, vals, js) => {
                n++;
                const ok = POSE_FLOATS.every((k, i) => Math.abs(Number(vals[i]) - js[k]) < 1e-5) && POSE_BOOLS.every((k, i) => (vals[POSE_FLOATS.length + i] === 'true') === js[k]);
                if (!ok && bad++ < 5) problems.push(`java vs js ${key}: java ${vals.join(' ')} / js ${JSON.stringify(js)}`);
            };
            // 每档的循环表: Java 解析出来的 = tiers.mjs (生成器也核对, 这里防手改)
            const tiersCycles = TIERS.map((T) => T.cycleTicks);
            if (P.cycleByTier.join() !== tiersCycles.join()) problems.push(`CYCLE_TICKS_BY_TIER ${P.cycleByTier} in MunitionsBenchProgram.java != tiers.mjs cycleTicks ${tiersCycles} (rerun the generator)`);
            for (const line of out) {
                const p = line.split(' ');
                if (p[0] === 'f') cmp(line.slice(0, 14), p.slice(2), sampleProgram(P, Number(p[1])));
                else if (p[0] === 'l') {
                    // 程序时间 (double) 逐位相同, 姿态 (Java 在 float 里插值) 容差 1e-5
                    const [tier, e, pt, jt] = [Number(p[1]), Number(p[2]), Number(p[3]), Number(p[4])];
                    const t = programTick(P, e, pt, tier);
                    n++; mapped++;
                    if (t !== jt && bad++ < 5) problems.push(`java vs js programTick tier ${tier} e ${e} + ${pt}: java ${jt} / js ${t}`);
                    cmp(`l ${tier} ${e} ${pt}`, p.slice(5), sampleProgramAt(P, e, pt, tier));
                } else if (p[0] === 'idle') cmp('idle', p.slice(1), idlePose(P));
                else if (p[0] === 'c') {
                    n++;
                    const tier = Number(p[1]);
                    if (Number(p[2]) !== cycleTicksOf(P, tier) || Number(p[3]) !== strikeTickOf(P, tier)) { if (bad++ < 5) problems.push(`java vs js tier ${tier}: java cycle ${p[2]} strike ${p[3]} / js ${cycleTicksOf(P, tier)} ${strikeTickOf(P, tier)}`); }
                } else if (p[0] === 's') {
                    n++;
                    const tier = Number(p[1]), e = Number(p[2]);
                    const strike = isStrikeTickMirror(P, e, tier), next = nextStrikeTickAfterMirror(P, e, tier);
                    if (String(strike) !== p[3] || String(next) !== p[4]) { if (bad++ < 5) problems.push(`java vs js strike tier ${tier} e ${e}: java ${p[3]} ${p[4]} / js ${strike} ${next}`); }
                } else if (p[0] === 'r') {
                    running++;
                    const [tier, e, pt] = [Number(p[1]), Number(p[2]), Number(p[3])];
                    cmp(`r ${tier} ${e} ${pt}`, p.slice(4), sampleRunningMirror(P, e, pt, tier));
                } else if (p[0] === 'b') {
                    n++; between++;
                    const [tier, since, e] = [Number(p[1]), Number(p[2]), Number(p[3])];
                    const js = strikeBetweenMirror(P, since, e, tier);
                    // 火花与冲压音同拍: (since, e] 里逐 tick 问服务端的 isStrikeTick, 有一个就该放
                    let beat = false;
                    for (let t = since + 1; t <= e; t++) if (isStrikeTickMirror(P, t, tier)) beat = true;
                    if (String(js) !== p[4] || js !== beat) { if (bad++ < 5) problems.push(`strikeBetween tier ${tier} (${since}, ${e}]: java ${p[4]} / js ${js} / server beats ${beat}`); }
                }
            }
            javaResult = bad ? `FAIL (${bad} of ${n} samples differ)` : `OK (${n} samples: sample(float) at every 0.25 tick over -10..90; ${mapped} tier time mappings (programTick bit-identical + sample(long, partial, tier)) over tiers -1..6 × 9 partialTicks (4 off the 0.25 grid) × every tick of two cycles + long clocks up to 2^52; idle(); cycleTicks / strikeTick and isStrikeTick / nextStrikeTickAfter per tier over -45..125; renderer decisions per tier: ${running} sampleRunning (hold before the start) + ${between} strikeBetween windows, each = the server's isStrikeTick beats in the window)`;
        } catch (e) {
            javaResult = 'FAIL (compile/run): ' + String(e.stderr || e.message).slice(0, 400);
            problems.push('java harness: ' + javaResult);
        } finally {
            fs.rmSync(tmp, { recursive: true, force: true });
        }
    }
}

// ================================================================ Java 对拍 (计数屏: MunitionsBenchCounter + MunitionsBenchGeometry 单独编译, 与 counter.mjs 逐值比较)
// 格式 (定点 + 各数量级 3000 个伪随机数)、两套字形、字宽、满度条、满仓、显示键、rects (发数 × 口径 × 上限 × 满仓)、颜色 (C.colourOf)、
// 角 (benchCorners / blockCorners × 四个朝向角, 渲染器画的就是 blockCorners 的输出)、
// 布局常量 (Java 运行时的值 vs parseCounterJava 从源码解析出来的值); 另外核对 CALIBER_LABELS 与 MunitionsCaliber.java 的 shortLabel。
let counterJavaResult = 'skipped (--no-java)';
{
    const src = fs.readFileSync(path.join(REPO, 'src', 'main', 'java', 'com', 'miningdim', 'job', 'munitions', 'MunitionsCaliber.java'), 'utf8');
    const labels = [];
    for (const m of src.matchAll(/^\s*\w+\((\d+), \d+, "[^"]*", Prices\.\w+, Category\.\w+, "([^"]+)"\)/gm)) labels[Number(m[1])] = m[2];
    if (labels.length !== CALIBER_LABELS.length || labels.some((l, i) => l !== CALIBER_LABELS[i])) problems.push(`counter.mjs CALIBER_LABELS ${JSON.stringify(CALIBER_LABELS)} differ from MunitionsCaliber.java shortLabels ${JSON.stringify(labels)}`);
}
if (!ARGS['no-java']) {
    const javac = findJavac();
    if (!javac) counterJavaResult = 'skipped (no javac: set JAVA_HOME)';
    else {
        const java = path.join(path.dirname(javac), os.platform() === 'win32' ? 'java.exe' : 'java');
        const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'mb-counter-'));
        const LABELS = ['-', '~', ...CALIBER_LABELS, '?Z9'];   // - = null, ~ = "", ?Z9 = 字表外的字 (按空格)
        const harness = [
            'import com.miningdim.job.munitions.block.MunitionsBenchCounter;',
            'import com.miningdim.job.munitions.block.MunitionsBenchGeometry;',
            'public class CounterHarness {',
            '    static final StringBuilder SB = new StringBuilder();',
            '    static void line(Object... parts) { for (int i = 0; i < parts.length; i++) { if (i > 0) SB.append(\' \'); SB.append(parts[i]); } SB.append(\'\\n\'); }',
            '    static String join(int[] v) { StringBuilder s = new StringBuilder(); for (int i = 0; i < v.length; i++) { if (i > 0) s.append(\',\'); s.append(v[i]); } return s.length() == 0 ? "_" : s.toString(); }',
            '    static String joinF(float[] v) { StringBuilder s = new StringBuilder(); for (int i = 0; i < v.length; i++) { if (i > 0) s.append(\',\'); s.append(v[i]); } return s.toString(); }',
            '    static void glyphs(String id, MunitionsBenchCounter.Font f) {',
            '        String cs = f.chars() + f.narrowChar() + "?";',
            '        for (int i = 0; i < cs.length(); i++) { char c = cs.charAt(i); int[] rows = new int[f.height]; for (int r = 0; r < f.height; r++) rows[r] = f.glyphRow(c, r); line("G", id, (int) c, f.glyphWidth(c), join(rows)); }',
            '    }',
            '    public static void main(String[] a) {',
            '        long[] fixed = {Long.MIN_VALUE, -1L, 0L, 1L, 9L, 10L, 99L, 347L, 999L, 1000L, 9999L, 10000L, 10001L, 10099L, 10100L, 12480L, 99999L, 100000L, 123456L, 999999L, 1000000L, 1000001L, 1009999L, 1010000L, 3200000L, 9999999L, 10000000L, 12345678L, 99999999L, 123456789L, 999999999L, 1000000000L, 2147483647L, 999999999999L, 1000000000000L, Long.MAX_VALUE};',
            '        for (long n : fixed) line("F", n, MunitionsBenchCounter.format(n));',
            '        long seed = 12345L;',
            '        for (int i = 0; i < 3000; i++) { seed = seed * 6364136223846793005L + 1442695040888963407L; long m = 1L; for (int k = 0; k < 1 + i % 13; k++) m *= 10L; long n = Math.floorMod(seed >>> 20, m); line("F", n, MunitionsBenchCounter.format(n)); }',
            '        glyphs("4x7", MunitionsBenchCounter.FONT_4X7); glyphs("3x5", MunitionsBenchCounter.FONT_3X5);',
            `        String[] labels = {${LABELS.map((l) => JSON.stringify(l)).join(', ')}};`,
            '        for (String t : new String[]{"0", "12.4K", "9999", "3.2M", "999G", "2.14G"}) line("T", "4x7", t, MunitionsBenchCounter.FONT_4X7.textWidth(t));',
            '        for (int i = 2; i < labels.length; i++) line("T", "3x5", labels[i], MunitionsBenchCounter.FONT_3X5.textWidth(labels[i]));',
            '        int[] rs = {-3, 0, 1, 2, 7, 21, 22, 23, 88, 347, 400, 460, 461, 500, 799, 800, 801, 4000, 9999, 10000, 12479, 12480, 123456, 1000000, 3200000, 2147483647};',
            '        int[] caps = {-1, 0, 1, 22, 30, 500, 790, 800, 4000, 16000, 2147483647};',
            '        for (int r : rs) for (int c : caps) line("B", r, c, MunitionsBenchCounter.barCells(r, c));',
            '        for (int r : rs) for (int c : caps) for (int b : new int[]{1, 40, 70}) line("U", r, c, b, MunitionsBenchCounter.isFull(r, c, b), MunitionsBenchCounter.cannotTakeBatch(r, c, b));',
            '        for (int r : rs) for (int cal : new int[]{-1, 0, 1, 9, 300, 2147483647}) for (int c : new int[]{0, 500, 800}) for (boolean f : new boolean[]{false, true}) line("K", r, cal, c, f, MunitionsBenchCounter.displayKey(r, cal, c, f));',
            '        int[] rr = {-5, 0, 1, 7, 88, 347, 4000, 9999, 10000, 12480, 123456, 1000000, 3200000, 2147483647};',
            '        for (int r : rr) for (String l : labels) for (int c : new int[]{0, 800, 4000}) for (boolean f : new boolean[]{false, true}) {',
            '            String label = l.equals("-") ? null : l.equals("~") ? "" : l;',
            '            line("R", r, l, c, f, join(MunitionsBenchCounter.rects(r, label, c, f)));',
            '        }',
            '        for (int r : new int[]{0, 347, 12480, 3200000}) for (String l : labels) for (boolean f : new boolean[]{false, true}) for (float rot : new float[]{0.0F, -90.0F, 180.0F, 90.0F}) {',
            '            String label = l.equals("-") ? null : l.equals("~") ? "" : l;',
            '            int[] rects = MunitionsBenchCounter.rects(r, label, 800, f);',
            '            line("Q", r, l, f, rot, joinF(MunitionsBenchCounter.blockCorners(rects, rot)));',
            '            if (rot == 0.0F) line("P", r, l, f, joinF(MunitionsBenchCounter.benchCorners(rects)));',
            '        }',
            '        for (int t = -1; t <= 6; t++) for (int role = 0; role < 5; role++) for (boolean w : new boolean[]{false, true}) line("C", t, role, w, MunitionsBenchCounter.colour(t, role, w));',
            '        line("L", "qt", MunitionsBenchGeometry.COUNTER_QT_PX); line("L", "lift", MunitionsBenchGeometry.COUNTER_LIFT_PX);',
            '        line("L", "recess", MunitionsBenchGeometry.COUNTER_RECESS_PX); line("L", "maxDist", MunitionsBenchGeometry.COUNTER_MAX_DISTANCE_BLOCKS);',
            '        line("L", "window.tl", joinF(MunitionsBenchGeometry.COUNTER_WINDOW_FACE_TOP_LEFT)); line("L", "window.face", join(MunitionsBenchGeometry.COUNTER_LID_FACE_QT));',
            '        line("L", "window.rot.origin", joinF(MunitionsBenchGeometry.COUNTER_LID_ROTATION_ORIGIN)); line("L", "window.rot.angle", MunitionsBenchGeometry.COUNTER_LID_ROTATION_X_DEGREES);',
            '        line("L", "window.rect", join(MunitionsBenchGeometry.COUNTER_WINDOW_QT)); line("L", "bar", join(MunitionsBenchGeometry.COUNTER_BAR_QT));',
            '        line("L", "count", join(MunitionsBenchGeometry.COUNTER_COUNT_QT)); line("L", "count.texel", MunitionsBenchGeometry.COUNTER_COUNT_TEXEL_QT);',
            '        line("L", "stencil.tl", joinF(MunitionsBenchGeometry.COUNTER_STENCIL_FACE_TOP_LEFT)); line("L", "stencil.face", join(MunitionsBenchGeometry.COUNTER_STENCIL_FACE_QT));',
            '        line("L", "stencil.y", MunitionsBenchGeometry.COUNTER_STENCIL_Y_QT); line("L", "stencil.texel", MunitionsBenchGeometry.COUNTER_STENCIL_TEXEL_QT);',
            '        System.out.print(SB);',
            '    }',
            '}',
        ].join('\n');
        fs.writeFileSync(path.join(tmp, 'CounterHarness.java'), harness);
        const J = (...p) => path.join(REPO, 'src', 'main', 'java', 'com', 'miningdim', 'job', 'munitions', 'block', ...p);
        try {
            execFileSync(javac, ['-encoding', 'UTF-8', '-d', tmp, J('MunitionsBenchCounter.java'), J('MunitionsBenchGeometry.java'), path.join(tmp, 'CounterHarness.java')], { stdio: 'pipe' });
            const out = execFileSync(java, ['-cp', tmp, 'CounterHarness'], { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 }).trim().split(/\r?\n/);
            const L = game.counter;   // 从 MunitionsBenchGeometry.java 源码解析出来的布局 (预览用的就是这份)
            const counts = {};
            let bad = 0;
            const check = (kind, ok, what) => { counts[kind] = (counts[kind] || 0) + 1; if (!ok && bad++ < 8) problems.push(`counter java vs js ${kind}: ${what}`); };
            const label = (l) => (l === '-' ? null : l === '~' ? '' : l);
            const jsRects = (r, l, c, f) => {
                const v = C.counterRects(L, { rounds: r, caliberLabel: label(l), cap: c, full: f }).flatMap((x) => [x.face, x.role, x.x0, x.y0, x.x1, x.y1]);
                return v.length ? v.join(',') : '_';
            };
            // 预览用的就是 C.colourOf (含档位越界的回退), 直接对拍它
            const jsColour = (t, role, w) => { const [r, g, b] = C.colourOf(L, t, role, w); return (r << 16) | (g << 8) | b; };
            // 角 (MunitionsBenchCounter.benchCorners / blockCorners vs counter.mjs rectCorners / blockCorners): Java 是 float, 容差 1e-5
            const cornersClose = (java, js) => java.length === js.length && java.every((v, i) => Math.abs(v - js[i]) < 1e-5 * Math.max(1, Math.abs(js[i])));
            const jsRectList = (r, l, f) => C.counterRects(L, { rounds: r, caliberLabel: label(l), cap: 800, full: f });
            const layoutValue = (k) => k.split('.').reduce((o, p) => o[p], L);
            for (const line of out) {
                const p = line.split(' ');
                const B = (s) => s === 'true';
                switch (p[0]) {
                    case 'F': { const js = C.formatCount(Number(p[1])); check('format', js === p[2], `${p[1]}: java ${p[2]} / js ${js}`); break; }
                    case 'G': {
                        const font = C.FONTS[p[1]], ch = String.fromCharCode(Number(p[2]));
                        const js = [C.glyphWidth(font, ch), C.glyphRows(font, ch).join(',')].join(' ');
                        check('glyph', js === `${p[3]} ${p[4]}`, `${p[1]} '${ch}': java ${p[3]} ${p[4]} / js ${js}`); break;
                    }
                    case 'T': { const js = C.textWidth(C.FONTS[p[1]], p[2]); check('textWidth', String(js) === p[3], `${p[1]} "${p[2]}": java ${p[3]} / js ${js}`); break; }
                    case 'B': { const js = C.barCells(L, Number(p[1]), Number(p[2])); check('barCells', String(js) === p[3], `${p[1]}/${p[2]}: java ${p[3]} / js ${js}`); break; }
                    case 'U': {
                        const [r, c, b] = [Number(p[1]), Number(p[2]), Number(p[3])];
                        const js = `${C.isFull(r, c, b)} ${C.cannotTakeBatch(r, c, b)}`;
                        check('full', js === `${p[4]} ${p[5]}`, `${r}/${c}/${b}: java ${p[4]} ${p[5]} / js ${js}`); break;
                    }
                    case 'K': { const js = C.displayKey(L, Number(p[1]), Number(p[2]), Number(p[3]), B(p[4])); check('displayKey', String(js) === p[5], `${p.slice(1, 5).join('/')}: java ${p[5]} / js ${js}`); break; }
                    case 'R': { const js = jsRects(Number(p[1]), p[2], Number(p[3]), B(p[4])); check('rects', js === p[5], `${p.slice(1, 5).join('/')}: java ${p[5]} / js ${js}`); break; }
                    case 'C': { const js = jsColour(Number(p[1]), Number(p[2]), B(p[3])); check('colour', String(js) === p[4], `${p.slice(1, 4).join('/')}: java ${p[4]} / js ${js}`); break; }
                    case 'Q': {
                        const js = C.blockCorners(L, jsRectList(Number(p[1]), p[2], B(p[3])), Number(p[4])).flat(2);
                        const jv = (p[5] || '').split(',').filter((s) => s !== '').map(Number);
                        check('blockCorners', cornersClose(jv, js), `${p.slice(1, 5).join('/')}: java ${jv.slice(0, 6).join(',')}.. (${jv.length}) / js ${js.slice(0, 6).map((v) => v.toFixed(6)).join(',')}.. (${js.length})`); break;
                    }
                    case 'P': {
                        const js = jsRectList(Number(p[1]), p[2], B(p[3])).flatMap((r) => C.rectCorners(L, r).flat());
                        const jv = (p[4] || '').split(',').filter((s) => s !== '').map(Number);
                        check('benchCorners', cornersClose(jv, js), `${p.slice(1, 4).join('/')}: java ${jv.slice(0, 6).join(',')}.. (${jv.length}) / js ${js.slice(0, 6).map((v) => v.toFixed(5)).join(',')}.. (${js.length})`); break;
                    }
                    case 'L': {
                        const v = layoutValue(p[1]);
                        const js = Array.isArray(v) ? v.join(',') : v && typeof v === 'object' ? [v.x, v.y, v.w, v.h].filter((x) => x !== undefined).join(',') : String(v);
                        const jv = p[2].split(',').map(Number).join(',');
                        check('layout', js.split(',').map(Number).join(',') === jv, `${p[1]}: java ${p[2]} / parsed ${js}`); break;
                    }
                    default: check('unknown', false, line);
                }
            }
            const total = Object.values(counts).reduce((a, b) => a + b, 0);
            counterJavaResult = bad ? `FAIL (${bad} of ${total} values differ)` : `OK (${total} values: ${Object.entries(counts).map(([k, n]) => `${k} ${n}`).join(', ')})`;
        } catch (e) {
            counterJavaResult = 'FAIL (compile/run): ' + String(e.stderr || e.message).slice(0, 400);
            problems.push('counter java harness: ' + counterJavaResult);
        } finally {
            fs.rmSync(tmp, { recursive: true, force: true });
        }
    }
}

// ================================================================ Java 对拍 (运行灯效: MunitionsBenchLights + Geometry + Counter + Program 单独编译, 与 lights.mjs 逐值比较)
// 常量: parseLightsJava(Geometry 源码) 与 lights.mjs 的 lightsLayout() 相同 (生成器也核对, 这里防手改);
// 每帧: 六档 (+ 越界的 -1 / 6) × 工作 / 待机 / 待机满仓 / 工作满仓 × 开工后 -2..170 每 0.25 tick × 轮换的相机距离,
// 另外六档 × 四种状态 × 开工后 -1..90 × 七个不是 0.25 倍数的 float partialTick (第一轮开工的头几 tick 在里面),
// 以及六档 × 几个很大的 long (到 2^52) × 0.25 倍数与七个任意的 partialTick —— 每档按自己的循环长度映射到程序时间 (闪耀 2 倍速):
// 时钟、各效果标量、四边形 (效果、目标、矩形、渐变轴、两端颜色与 α)、benchCorners (角 + 顶点色)、blockCorners (四个朝向轮换);
// 另外 effectMask (档位 -1..7)、worldFace (六个面 × 四个朝向角)。
let lightsJavaResult = 'skipped (--no-java)';
{
    const src = fs.readFileSync(path.join(REPO, 'src', 'main', 'java', 'com', 'miningdim', 'job', 'munitions', 'block', 'MunitionsBenchGeometry.java'), 'utf8');
    let d = null;
    try { d = LI.layoutDiff(LI.parseLightsJava(src), LI.lightsLayout()); } catch (e) { d = e.message; }
    if (d) problems.push('LIGHT_* constants in MunitionsBenchGeometry.java differ from lights.mjs (rerun the generator): ' + d);
}
if (!ARGS['no-java']) {
    const javac = findJavac();
    if (!javac) lightsJavaResult = 'skipped (no javac: set JAVA_HOME)';
    else {
        const java = path.join(path.dirname(javac), os.platform() === 'win32' ? 'java.exe' : 'java');
        const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'mb-lights-'));
        const harness = [
            'import com.miningdim.job.munitions.block.MunitionsBenchLights;',
            'public class LightsHarness {',
            '    static final StringBuilder SB = new StringBuilder();',
            '    static void line(Object... parts) { for (int i = 0; i < parts.length; i++) { if (i > 0) SB.append(\' \'); SB.append(parts[i]); } SB.append(\'\\n\'); }',
            '    static String joinF(float[] v, int n) { StringBuilder s = new StringBuilder(); for (int i = 0; i < n; i++) { if (i > 0) s.append(\',\'); s.append(v[i]); } return s.length() == 0 ? "_" : s.toString(); }',
            '    static final MunitionsBenchLights.Frame F = new MunitionsBenchLights.Frame();',
            '    static final float[] PTS = new float[MunitionsBenchLights.MAX_QUADS * MunitionsBenchLights.CORNER_FLOATS];',
            '    static final float[] COLS = new float[MunitionsBenchLights.MAX_QUADS * MunitionsBenchLights.COLOUR_FLOATS];',
            '    static final double[] DISTANCES = {0.0, 10.0, 20.5, 21.75, 23.5, 23.99, 30.0};',
            '    static final float[] ROTS = {0.0F, -90.0F, 180.0F, 90.0F};',
            '    static void frame(int tier, boolean active, boolean full, long e, float partial, long gameTime, double dist, float rot) {',
            '        MunitionsBenchLights.Frame f = F;',
            '        int n = MunitionsBenchLights.compute(f, tier, active, full, e, gameTime, partial, dist);',
            '        StringBuilder lv = new StringBuilder(); for (double v : f.leds) lv.append(v).append(\' \');',
            '        line("F", tier, active, full, e, partial, gameTime, dist, n, f.overflowed, f.cycleTick, f.elapsed, f.breathTick, f.clockTick, f.beltX,',
            '                lv.toString().trim(), f.strike, f.drop, f.full, f.gem, f.gemEcho, f.breath, f.chase);',
            '        for (int q = 0; q < n; q++) line("Q", f.effect[q], f.target[q], f.lo[q * 3], f.lo[q * 3 + 1], f.lo[q * 3 + 2], f.hi[q * 3], f.hi[q * 3 + 1], f.hi[q * 3 + 2], f.grad[q], f.rgb0[q], f.alpha0[q], f.rgb1[q], f.alpha1[q]);',
            '        MunitionsBenchLights.benchCorners(f, PTS, COLS);',
            '        line("B", joinF(PTS, n * MunitionsBenchLights.CORNER_FLOATS), joinF(COLS, n * MunitionsBenchLights.COLOUR_FLOATS));',
            '        MunitionsBenchLights.blockCorners(f, rot, PTS, COLS);',
            '        line("K", rot, joinF(PTS, n * MunitionsBenchLights.CORNER_FLOATS));',
            '    }',
            '    public static void main(String[] a) {',
            '        boolean[][] states = {{true, false}, {false, false}, {false, true}, {true, true}};',
            '        for (int tier = -1; tier <= 6; tier++) for (boolean[] s : states) for (int q = -8; q <= 680; q++) {',
            '            if ((tier < 0 || tier > 5) && q % 4 != 0) continue;',
            '            long e = Math.floorDiv(q, 4); float partial = Math.floorMod(q, 4) * 0.25F;',
            '            frame(tier, s[0], s[1], e, partial, e * 3L + 11L, DISTANCES[Math.floorMod(q * 7 + tier, DISTANCES.length)], ROTS[Math.floorMod(q + tier, 4)]);',
            '        }',
            '        // 任意 float 的 partialTick (不只 0.25 的倍数; 游戏里的 partialTick 什么值都有): 程序时间 -1..90 (含第一轮开工的头几 tick)',
            '        float[] odd = {0.1F, 0.15957803F, 0.3F, 0.4065408F, 0.7F, 0.8456556F, 0.9999F};',
            '        for (int tier = 0; tier <= 5; tier++) for (boolean[] s : states) for (long e = -1L; e <= 90L; e++) for (int j = 0; j < odd.length; j++) {',
            '            int q = (int) e * 7 + j;',
            '            frame(tier, s[0], s[1], e, odd[j], e * 3L + 11L, DISTANCES[Math.floorMod(q + tier, DISTANCES.length)], ROTS[Math.floorMod(q + tier, 4)]);',
            '        }',
            '        // 很大的 long (连开几天 .. 2^52; 每档的循环都在 long 里取模) × 0.25 倍数与任意的 float partialTick, 六档',
            '        long[] big = {123457L, 2000000011L, 17179869183L, 40L * 1000000007L + 38L, 10080L * 100000000000L + 7L, 4503599627370495L};',
            '        for (int tier = 0; tier <= 5; tier++) for (long e : big) {',
            '            for (int j = 0; j < 4; j++) frame(tier, true, false, e, j * 0.25F, e + 5L, 12.0, ROTS[j]);',
            '            for (int j = 0; j < odd.length; j++) frame(tier, true, false, e, odd[j], e + 5L, 12.0, ROTS[j % 4]);',
            '        }',
            '        for (int t = -1; t <= 7; t++) line("M", t, MunitionsBenchLights.effectMask(t));',
            '        for (int face = 0; face < 6; face++) for (float rot : ROTS) line("W", face, rot, MunitionsBenchLights.worldFace(face, rot));',
            '        System.out.print(SB);',
            '    }',
            '}',
        ].join('\n');
        fs.writeFileSync(path.join(tmp, 'LightsHarness.java'), harness);
        const J = (...p) => path.join(REPO, 'src', 'main', 'java', 'com', 'miningdim', 'job', 'munitions', 'block', ...p);
        try {
            execFileSync(javac, ['-encoding', 'UTF-8', '-d', tmp, J('MunitionsBenchLights.java'), J('MunitionsBenchGeometry.java'), J('MunitionsBenchCounter.java'), J('MunitionsBenchProgram.java'), path.join(tmp, 'LightsHarness.java')], { stdio: 'pipe' });
            const out = execFileSync(java, ['-cp', tmp, 'LightsHarness'], { encoding: 'utf8', maxBuffer: 512 * 1024 * 1024 }).trim().split(/\r?\n/);
            const counts = {};
            let bad = 0, frames = 0, quads = 0;
            const check = (kind, ok, what) => { counts[kind] = (counts[kind] || 0) + 1; if (!ok && bad++ < 8) problems.push(`lights java vs js ${kind}: ${what}`); };
            const near = (a, b, tol) => Math.abs(a - b) <= tol * Math.max(1, Math.abs(b));
            const B = (s) => s === 'true';
            const nums = (s) => (s === '_' ? [] : s.split(',').map(Number));
            let cur = null;   // 当前帧: {label, rects (JS), qi}
            const effectCount = {};
            for (const line of out) {
                const p = line.split(' ');
                switch (p[0]) {
                    case 'F': {
                        frames++;
                        const [tier, active, full, e, partial, gameTime, dist, n, overflowed] = [Number(p[1]), B(p[2]), B(p[3]), Number(p[4]), Number(p[5]), Number(p[6]), Number(p[7]), Number(p[8]), B(p[9])];
                        const s = LI.lightFrame({ tier, active, full, elapsedTicks: e, gameTime, partialTick: partial, distance: dist }, game.program);
                        const L = LI.levels(s);
                        const rects = LI.lightOverlays(s);
                        const label = `tier ${tier} ${active ? 'work' : 'idle'}${full ? '+full' : ''} e ${e}+${partial} d ${dist}`;
                        check('count', n === Math.min(rects.length, LI.MAX_QUADS) && overflowed === rects.length > LI.MAX_QUADS, `${label}: java ${n}${overflowed ? '+' : ''} / js ${rects.length}`);
                        const clocks = [s.cycleTick, s.elapsed, s.breathTick, s.clockTick, s.beltX];
                        const jc = p.slice(10, 15).map(Number);
                        check('clock', clocks.every((v, i) => near(jc[i], v, i === 4 ? 1e-5 : 1e-9)), `${label}: java ${jc} / js ${clocks}`);
                        const jl = p.slice(15).map(Number), js = [...L.leds, L.strike, L.drop, L.full, L.gem, L.gemEcho, L.breath, L.chase];
                        check('levels', jl.length === js.length && js.every((v, i) => near(jl[i], v, 1e-9)), `${label}: java ${jl.join(',')} / js ${js.join(',')}`);
                        cur = { label, rects: rects.slice(0, LI.MAX_QUADS), qi: 0 };
                        for (const r of cur.rects) effectCount[LI.EFFECTS[r.effect].id + '@' + tier] = (effectCount[LI.EFFECTS[r.effect].id + '@' + tier] || 0) + 1;
                        break;
                    }
                    case 'Q': {
                        quads++;
                        const r = cur.rects[cur.qi++];
                        const v = p.slice(1).map(Number);
                        const hex = (c) => (c[0] << 16) | (c[1] << 8) | c[2];
                        const ok = r && v[0] === r.effect && v[1] === r.target && [...r.lo, ...r.hi].every((x, i) => near(v[2 + i], x, 1e-5)) && v[8] === r.grad
                            && v[9] === hex(r.c0) && near(v[10], r.c0[3], 1e-6) && v[11] === hex(r.c1) && near(v[12], r.c1[3], 1e-6);
                        check('quad', ok, `${cur.label} #${cur.qi - 1}: java ${v.join(',')} / js ${r ? JSON.stringify(r) : 'none'}`);
                        break;
                    }
                    case 'B': {
                        const jp = nums(p[1]), jcol = nums(p[2]);
                        const cs = cur.rects.map((r) => LI.overlayCorners(r));
                        const pts = cs.flatMap((c) => c.pts.flat()), cols = cs.flatMap((c) => c.cols.flat());
                        check('benchCorners', jp.length === pts.length && pts.every((x, i) => near(jp[i], x, 1e-5)), `${cur.label}: java ${jp.slice(0, 6)}.. (${jp.length}) / js ${pts.slice(0, 6)}.. (${pts.length})`);
                        check('vertexColours', jcol.length === cols.length && cols.every((x, i) => (i % 4 === 3 ? near(jcol[i], x, 1e-6) : jcol[i] === x)), `${cur.label}: java ${jcol.slice(0, 8)}.. / js ${cols.slice(0, 8)}..`);
                        break;
                    }
                    case 'K': {
                        const rot = Number(p[1]), jp = nums(p[2]);
                        const pts = LI.blockCorners(cur.rects, rot).flat(2);
                        check('blockCorners', jp.length === pts.length && pts.every((x, i) => near(jp[i], x, 1e-5)), `${cur.label} rot ${rot}: java ${jp.slice(0, 6)}.. / js ${pts.slice(0, 6)}..`);
                        break;
                    }
                    case 'M': check('effectMask', Number(p[2]) === LI.effectMask(Number(p[1])), `tier ${p[1]}: java ${p[2]} / js ${LI.effectMask(Number(p[1]))}`); break;
                    case 'W': {
                        const js = LI.FACE_CODES[LI.worldFace(LI.FACE_BY_CODE[Number(p[1])], Number(p[2]))];
                        check('worldFace', Number(p[3]) === js, `face ${p[1]} rot ${p[2]}: java ${p[3]} / js ${js}`); break;
                    }
                    default: check('unknown', false, line);
                }
            }
            // 档位门的覆盖面 (对拍样本里确实出现过): 呼吸只在 ≥ 高级、追光只在 ≥ 极品、宝石只在闪耀
            const seen = (id, tier) => (effectCount[id + '@' + tier] || 0) > 0;
            const gate = LI.EFFECTS.every((e) => [0, 1, 2, 3, 4, 5].every((t) => seen(e.id, t) === t >= e.unlock));
            check('tierGate', gate, `effects seen per tier: ${JSON.stringify(effectCount)}`);
            const total = Object.values(counts).reduce((a, b) => a + b, 0);
            lightsJavaResult = bad ? `FAIL (${bad} of ${total} values differ)` : `OK (${total} values over ${frames} frames / ${quads} quads: ${Object.entries(counts).map(([k, n]) => `${k} ${n}`).join(', ')})`;
        } catch (e) {
            lightsJavaResult = 'FAIL (compile/run): ' + String(e.stderr || e.message).slice(0, 400);
            problems.push('lights java harness: ' + lightsJavaResult);
        } finally {
            fs.rmSync(tmp, { recursive: true, force: true });
        }
    }
}

// ================================================================ 参考图 (游戏样子: 运动件实体光照)
writePng(path.join(OUT, 'game-motion.png'), motionStrip(game, Array.from({ length: 16 }, (_, i) => i * 2.5)));
writePng(path.join(OUT, 'game-base-active.png'), tierSheet(game, 0, 'active'));
writePng(path.join(OUT, 'game-base-idle.png'), tierSheet(game, 0, 'idle'));
writePng(path.join(OUT, 'game-tiers.png'), tierStrip(game));
{
    // 轮廓箱线框: 读 MunitionsBenchGeometry.java 的静态件 / 运动件轮廓箱 (主格局部 = 整台; 副格 +16)
    const src = fs.readFileSync(path.join(REPO, 'src', 'main', 'java', 'com', 'miningdim', 'job', 'munitions', 'block', 'MunitionsBenchGeometry.java'), 'utf8');
    const boxes = [];
    for (const [name, dx, rgb] of [['MAIN_BOXES', 0, [255, 60, 200]], ['EXTENSION_BOXES', 16, [60, 220, 255]],
        ['MAIN_PART_BOXES', 0, [255, 230, 60]], ['EXTENSION_PART_BOXES', 16, [255, 230, 60]]]) {
        const block = new RegExp(`${name} = \\{([\\s\\S]*?)\\n\\s*\\};`).exec(src)[1];
        for (const m of block.matchAll(/\{([^{}]*)\}/g)) { const v = m[1].split(',').map((s) => parseFloat(s)); boxes.push({ lo: [v[0] + dx, v[1], v[2]], hi: [v[3] + dx, v[4], v[5]], rgb }); }
    }
    const sheet = newImage(3 * 700, 560 + 20);
    const views = ['FL', 'FR', 'BL'];
    views.forEach((v, i) => blit(sheet, closeup(game, { tier: 5, state: 'idle', view: v, w: 700, h: 560, boxes }), i * 700, 0));
    footer(sheet, 'OUTLINE BOXES (MunitionsBenchGeometry) OVER THE RADIANT IDLE MODEL: MAGENTA = MAIN, CYAN = EXTENSION, YELLOW = MOVING-PART SWEEP; COLLISION = ONE SOLID COLUMN PER CELL');
    writePng(path.join(OUT, 'game-shapes.png'), sheet);
}

// ================================================================ 报告
const table = (head, rows) => ['| ' + head.join(' | ') + ' |', '|' + head.map(() => '---').join('|') + '|', ...rows.map((r) => '| ' + r.join(' | ') + ' |')].join('\n');
report.push('# 军火台对拍报告', '');
report.push(`- 仓库: \`${REPO}\``, `- 方案输出: ${cand ? '`' + cand.out + '`' : '(没给 --cand, 跳过与方案 B v2 的逐像素对拍)'}`, `- 比较: 每个视角 ${W}x${H} 像素, 任一通道有差即算不同; 运动件按方块面明暗画 (与 JSON 同一套)。`, '');
if (cand) {
    report.push('## 普通档逐帧 (游戏 = 静态 line 模型 + 运动件摆在 t = 5f)', '');
    report.push(table(['帧', 't', ...frameRows[0].rows.map((r) => r.view)], frameRows.map((r) => [`f${r.f}`, r.tick, ...r.rows.map((x) => x.n)])), '');
    report.push('## 六档待机 / 工作 (工作 = STRIKE_TICK)', '');
    report.push(table(['档', '状态', ...DIFF_VIEWS], tierRows.flatMap((r) => [[TIERS[r.t].key, 'idle', ...r.idle.map((x) => x.n)], [TIERS[r.t].key, 'active', ...r.active.map((x) => x.n)]])), '');
    report.push('## 物品图标 (32 px)', '');
    report.push(table(['档', '不同像素'], itemRows.map((r) => [TIERS[r.t].key, r.n])), '');
}
report.push('## 四个朝向', '');
report.push(table(['朝向', '四边形', '结果'], facingRows.map((r) => [r.facing, r.quads, r.ok ? 'OK' : 'MISMATCH'])), '');
report.push('## 运动件背面检查 (游戏里不剔除背面)', '',
    backfaceRows.length ? table(['状态', 't', '视角', '露出的像素'], backfaceRows.map((r) => [r.state, r.tick === null ? '-' : r.tick, r.view, r.n]))
        : '普通档待机 + 一个循环每 1.25 tick, 7 个俯/平视角 + 2 个仰视角: 没有背面露出。', '');
report.push(`与方案对拍时不画的有意补面: ${DELIBERATE_FACES.map(([p, f]) => p + '.' + f).join(', ')} (方案里省掉、游戏里不剔除背面时必须有的面)。`, '');
report.push('## Java ↔ JS 程序对拍', '', javaResult, '');
report.push('## Java ↔ JS 计数屏对拍 (MunitionsBenchCounter / MunitionsBenchGeometry COUNTER_* vs counter.mjs)', '', counterJavaResult, '');
report.push('## Java ↔ JS 运行灯效对拍 (MunitionsBenchLights / MunitionsBenchGeometry LIGHT_* vs lights.mjs)', '', lightsJavaResult, '');
report.push('## 结论', '', problems.length ? `有 ${problems.length} 处问题:\n\n` + problems.map((p) => '- ' + p).join('\n') : '全部一致。', '');
fs.writeFileSync(path.join(OUT, 'parity-report.md'), report.join('\n'));
console.log(report.join('\n'));
if (problems.length) process.exitCode = 1;
