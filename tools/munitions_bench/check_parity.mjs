#!/usr/bin/env node
// 游戏里的样子 vs 方案 B v2 的对拍 (零依赖):
//   游戏 = 仓库里的静态 JSON (两格) + 按 MunitionsBenchParts.java 烘出来、按 MunitionsBenchProgram.java 的 JS 镜像摆好的运动件;
//   方案 = 候选生成器自己写出的逐帧 / 待机 / 工作 JSON 模型 (运动件烤在 JSON 里)。
// 逐像素比较 (运动件按方块面明暗画, 与 JSON 同一套明暗): 普通档 f0..f7, 六档待机 / 工作 (工作 = 冲头到底那一刻), 六档物品图标;
// (相对方案有意补上的几个运动件面 DELIBERATE_FACES 在对拍时不画, 见下);
// 另外检查: 四个朝向下静态模型 (方块状态 y 旋转 + 副格在顺时针一侧) 与运动件 (渲染器的朝向角) 是否仍对齐;
// 游戏里运动件不剔除背面, 一个循环里各视角 (含仰视) 都不许有件的背面露出来 (parity-backfaces.png);
// 若本机有 JDK (JAVA_HOME 或 PATH 里的 javac), 单独编译 MunitionsBenchProgram.java, 逐 tick 与 JS 镜像对拍。
// 用法:
//   node tools/munitions_bench/check_parity.mjs --cand <候选输出目录 (generate.mjs --out 的目录)> --out-dir <目录> [--repo <仓库根>] [--no-java]
//   候选输出: cd .candidates; node munitions_b/generate.mjs --out <目录>
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
import { applyPoseMirror, bakeParts, idlePose, sampleProgram, sampleProgramAt, POSE_FLOATS, POSE_BOOLS } from './ber.mjs';
import {
    DEFAULT_REPO, loadGame, loadCandidate, gameQuads, candQuads, gameItem, candItem, renderRaw, newImage, blit, FRAMES, VIEWS, footer,
    motionStrip, tierSheet, tierStrip, closeup, CELL_OFFSETS,
} from './render_mb.mjs';
import { drawText } from '../gunsmith_workstation/font.mjs';

function parseArgs(argv) {
    const a = {};
    for (let i = 0; i < argv.length; i++) if (argv[i].startsWith('--')) { const n = argv[i + 1]; if (n === undefined || n.startsWith('--')) a[argv[i].slice(2)] = true; else { a[argv[i].slice(2)] = n; i++; } }
    return a;
}
const ARGS = parseArgs(process.argv.slice(2));
if (!ARGS.cand || !ARGS['out-dir']) { console.error('usage: node check_parity.mjs --cand <candidate out dir> --out-dir <dir> [--repo <root>] [--no-java]'); process.exit(2); }
const REPO = path.resolve(ARGS.repo || DEFAULT_REPO);
const OUT = path.resolve(ARGS['out-dir']);
fs.mkdirSync(OUT, { recursive: true });
const game = loadGame(REPO);
const cand = loadCandidate(ARGS.cand);
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

// ---- 普通档逐帧 + 运动件特写
const frameRows = [];
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
const tierRows = [];
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
const itemRows = [];
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
let javaResult = 'skipped (--no-java)';
if (!ARGS['no-java']) {
    const javac = [process.env.JAVA_HOME && path.join(process.env.JAVA_HOME, 'bin', os.platform() === 'win32' ? 'javac.exe' : 'javac'), 'javac'].find((p) => { try { execFileSync(p, ['-version'], { stdio: 'ignore' }); return true; } catch { return false; } });
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
            '        long[] es = {0L, 1L, 9L, 10L, 39L, 40L, 41L, 123457L, 2000000011L, 17179869183L, -1L, -41L};',
            '        for (long e : es) for (int j = 0; j < 4; j++) { float pt = j * 0.25F; sb.append(row("l " + e + " " + pt, MunitionsBenchProgram.sample(e, pt, p))).append("\\n"); }',
            '        sb.append(row("idle", MunitionsBenchProgram.idle(p))).append("\\n");',
            '        for (long e = -45; e <= 125; e++) sb.append("s " + e + " " + MunitionsBenchProgram.isStrikeTick(e) + " " + MunitionsBenchProgram.nextStrikeTickAfter(e)).append("\\n");',
            '        System.out.print(sb);',
            '    }',
            '}',
        ].join('\n');
        fs.writeFileSync(path.join(tmp, 'Harness.java'), harness);
        try {
            execFileSync(javac, ['-encoding', 'UTF-8', '-d', tmp, path.join(REPO, 'src', 'main', 'java', 'com', 'miningdim', 'job', 'munitions', 'block', 'MunitionsBenchProgram.java'), path.join(tmp, 'Harness.java')], { stdio: 'pipe' });
            const out = execFileSync(java, ['-cp', tmp, 'Harness'], { encoding: 'utf8' }).trim().split(/\r?\n/);
            const P = game.program;
            let bad = 0, n = 0;
            const cmp = (key, vals, js) => {
                n++;
                const ok = POSE_FLOATS.every((k, i) => Math.abs(Number(vals[i]) - js[k]) < 1e-5) && POSE_BOOLS.every((k, i) => (vals[POSE_FLOATS.length + i] === 'true') === js[k]);
                if (!ok && bad++ < 5) problems.push(`java vs js ${key}: java ${vals.join(' ')} / js ${JSON.stringify(js)}`);
            };
            for (const line of out) {
                const p = line.split(' ');
                if (p[0] === 'f') cmp(line.slice(0, 14), p.slice(2), sampleProgram(P, Number(p[1])));
                else if (p[0] === 'l') cmp(`l ${p[1]} ${p[2]}`, p.slice(3), sampleProgramAt(P, Number(p[1]), Number(p[2])));
                else if (p[0] === 'idle') cmp('idle', p.slice(1), idlePose(P));
                else if (p[0] === 's') {
                    n++;
                    const e = Number(p[1]), within = ((e % P.cycleTicks) + P.cycleTicks) % P.cycleTicks;
                    const strike = e >= 0 && within === P.strikeTick;
                    const cycleStart = Math.floor(e / P.cycleTicks) * P.cycleTicks, s = cycleStart + P.strikeTick;
                    const next = s > e ? s : s + P.cycleTicks;
                    if (String(strike) !== p[2] || String(next) !== p[3]) { if (bad++ < 5) problems.push(`java vs js strike ${e}: java ${p[2]} ${p[3]} / js ${strike} ${next}`); }
                }
            }
            javaResult = bad ? `FAIL (${bad} of ${n} samples differ)` : `OK (${n} samples: sample(float) at every 0.25 tick over -10..90, sample(long, partial) incl. 17179869183 ticks, idle(), isStrikeTick / nextStrikeTickAfter over -45..125)`;
        } catch (e) {
            javaResult = 'FAIL (compile/run): ' + String(e.stderr || e.message).slice(0, 400);
            problems.push('java harness: ' + javaResult);
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
report.push(`- 仓库: \`${REPO}\``, `- 方案输出: \`${cand.out}\``, `- 比较: 每个视角 ${W}x${H} 像素, 任一通道有差即算不同; 运动件按方块面明暗画 (与 JSON 同一套)。`, '');
report.push('## 普通档逐帧 (游戏 = 静态 line 模型 + 运动件摆在 t = 5f)', '');
report.push(table(['帧', 't', ...frameRows[0].rows.map((r) => r.view)], frameRows.map((r) => [`f${r.f}`, r.tick, ...r.rows.map((x) => x.n)])), '');
report.push('## 六档待机 / 工作 (工作 = STRIKE_TICK)', '');
report.push(table(['档', '状态', ...DIFF_VIEWS], tierRows.flatMap((r) => [[TIERS[r.t].key, 'idle', ...r.idle.map((x) => x.n)], [TIERS[r.t].key, 'active', ...r.active.map((x) => x.n)]])), '');
report.push('## 物品图标 (32 px)', '');
report.push(table(['档', '不同像素'], itemRows.map((r) => [TIERS[r.t].key, r.n])), '');
report.push('## 四个朝向', '');
report.push(table(['朝向', '四边形', '结果'], facingRows.map((r) => [r.facing, r.quads, r.ok ? 'OK' : 'MISMATCH'])), '');
report.push('## 运动件背面检查 (游戏里不剔除背面)', '',
    backfaceRows.length ? table(['状态', 't', '视角', '露出的像素'], backfaceRows.map((r) => [r.state, r.tick === null ? '-' : r.tick, r.view, r.n]))
        : '普通档待机 + 一个循环每 1.25 tick, 7 个俯/平视角 + 2 个仰视角: 没有背面露出。', '');
report.push(`与方案对拍时不画的有意补面: ${DELIBERATE_FACES.map(([p, f]) => p + '.' + f).join(', ')} (方案里省掉、游戏里不剔除背面时必须有的面)。`, '');
report.push('## Java ↔ JS 程序对拍', '', javaResult, '');
report.push('## 结论', '', problems.length ? `有 ${problems.length} 处问题:\n\n` + problems.map((p) => '- ' + p).join('\n') : '全部一致。', '');
fs.writeFileSync(path.join(OUT, 'parity-report.md'), report.join('\n'));
console.log(report.join('\n'));
if (problems.length) process.exitCode = 1;
