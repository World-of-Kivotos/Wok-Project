#!/usr/bin/env node
// 组装台上真枪的摆放校验: GunsmithBlueprint 里每一把蓝图枪 (高模, 以及有 LOD 的 LOD 模型) 按摆放规则 (layoutGunOnBed, 与 Java
// GunsmithBenchGunLayout 同一套; 只算裸枪显示 stack 下画出来的方块, 见 raster.mjs taczBareDrawn) 侧躺在枪床上之后,
//   (a) 整把枪 (含 inflate 的实际几何) 在枪床留空范围内 (x 全长、y 留空高度、z = 隐形包络的 z 范围 AXIS_Z ± HALF_WIDTH),
//       xz 投影全部落在床面 (顶面正好在 TOP_Y 的 bed_* 元素) 上, 且不穿入任何方块元素 (生成器 buildScene 的待机 + 工作态元素);
//   (b) 按这把枪自己的放件下沉量 (benchGunPlaceDrops, 与 GunsmithBenchGunLayout.placeDrop 同一规则) 以生成器的扫描密度
//       (每 tick 8 份) 走完整段 160 tick 机械臂程序, 臂段/爪子/携带件 (含 CubeDeformation 外扩) 与枪的每个方块做 OBB 分离轴检测,
//       任何时刻任何部件都不许穿入 (携带件落到枪上也只许贴上, 间隙 >= 0);
//   (c) 两个安装点的点焊姿态: 携带件底面与它投影范围内枪的实际顶面的竖直间隙 (目标 = PLACE_CLEARANCE, 下沉量被夹住时除外)。
// 用法: node check_bench_guns.mjs [--repo <仓库或 worktree 根>] [--packs <tacz 目录>] [--guns id,id] [--lod | --high]
//        [--results <侦察的 results.json>] [--json out.json]
//   默认高模与 LOD 都查 (没有 LOD 的枪只有高模一行); --lod 只查 LOD, --high 只查高模。
//   --results: 把 FIXED 定位系包围盒 (含 inflate) 与枪口 z 和侦察脚本的 results.json 逐把对照 (两位小数; 侦察时还没有
//   attachment_adapter 规则, 所以有转接件方块被隐藏的枪不符只记 note)。
// 硬失败 (退出码 1): 不画、出留空范围 / 包络 z 范围 / 床面、穿入方块元素、机械臂任一部件 (含携带件) 穿入枪、反解不可达、与 results.json 不符。
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { benchGunPlaceDrops } from './raster.mjs';
import { AssetRoot } from './scene.mjs';
import { TaczPacks, resolveGun, bakeBenchGun, readBlueprints, DEFAULT_PACKS } from './taczpack.mjs';
import { GUN_BED, buildScene, armMatrices, armPose, ARM_SAMPLES, sampleProgram } from './generate_assembly_bench.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const TOL = 1e-3;            // 贴合容差 (px): 枪底贴床面、枪托贴挡块算接触不算穿入
const SAMPLES_PER_TICK = 8;  // 与生成器 sweepProgram 相同
const ARM_MARGIN = 0.3;      // 生成器对静态元素要求的间隙; 安装窗口外低于它只报 WARN
const NEAR = 2;              // 超过这个距离的间隙不再细算, 记作 ">= 2"
const BED_GRID = 0.25;       // 床面覆盖检查的网格 (与生成器 gunBedChecks 相同)

// ------------------------------------------------------------ OBB 与分离轴
const sub = (a, b) => [a[0] - b[0], a[1] - b[1], a[2] - b[2]];
const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
const cross = (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
const mApply = (m, p) => [m[0] * p[0] + m[1] * p[1] + m[2] * p[2] + m[3], m[4] * p[0] + m[5] * p[1] + m[6] * p[2] + m[7], m[8] * p[0] + m[9] * p[1] + m[10] * p[2] + m[11]];

/** 仿射矩阵 m 下的局部盒 [lo, hi] → OBB {c, u (3 条单位轴), e (半长), lo/hi (世界 AABB)}。m 的线性部分须是 (可带反射的) 旋转 × 均匀缩放。 */
function obb(m, lo, hi, name) {
    const c = mApply(m, [(lo[0] + hi[0]) / 2, (lo[1] + hi[1]) / 2, (lo[2] + hi[2]) / 2]);
    const u = [], e = [];
    for (let j = 0; j < 3; j++) {
        const col = [m[j], m[4 + j], m[8 + j]];
        const len = Math.hypot(...col);
        if (!(len > 0)) throw new Error('obb: degenerate matrix for ' + name);
        u.push(col.map((v) => v / len));
        e.push(Math.abs(hi[j] - lo[j]) / 2 * len);
    }
    const r = [0, 1, 2].map((a) => e[0] * Math.abs(u[0][a]) + e[1] * Math.abs(u[1][a]) + e[2] * Math.abs(u[2][a]));
    return { c, u, e, lo: c.map((v, a) => v - r[a]), hi: c.map((v, a) => v + r[a]), name };
}

/** 分离轴 (15 条): > 0 = 分开 (沿最佳轴的间隙, 欧氏距离的下界), <= 0 = 相交 (取负是最小穿入深度)。 */
function obbGap(A, B) {
    const d = sub(B.c, A.c);
    let best = -Infinity;
    const test = (L) => {
        const rA = A.e[0] * Math.abs(dot(A.u[0], L)) + A.e[1] * Math.abs(dot(A.u[1], L)) + A.e[2] * Math.abs(dot(A.u[2], L));
        const rB = B.e[0] * Math.abs(dot(B.u[0], L)) + B.e[1] * Math.abs(dot(B.u[1], L)) + B.e[2] * Math.abs(dot(B.u[2], L));
        best = Math.max(best, Math.abs(dot(d, L)) - rA - rB);
    };
    for (let i = 0; i < 3; i++) { test(A.u[i]); test(B.u[i]); }
    for (let i = 0; i < 3; i++) for (let j = 0; j < 3; j++) {
        const L = cross(A.u[i], B.u[j]);
        const len = Math.hypot(...L);
        if (len > 1e-6) test(L.map((v) => v / len));
    }
    return best;
}
const aabbNear = (a, b, m) => a.lo[0] < b.hi[0] + m && b.lo[0] < a.hi[0] + m && a.lo[1] < b.hi[1] + m && b.lo[1] < a.hi[1] + m && a.lo[2] < b.hi[2] + m && b.lo[2] < a.hi[2] + m;

/** OBB 集合 → 包围所有 AABB 的盒子 (粗筛用)。 */
function union(boxes) {
    const lo = [Infinity, Infinity, Infinity], hi = [-Infinity, -Infinity, -Infinity];
    for (const b of boxes) for (let a = 0; a < 3; a++) { lo[a] = Math.min(lo[a], b.lo[a]); hi[a] = Math.max(hi[a], b.hi[a]); }
    return { lo, hi };
}

/** 竖直线 (x, *, z) 穿过 OBB 的最高点; 不相交返回 -Infinity。 */
function topAlongVertical(b, x, z) {
    let yLo = -Infinity, yHi = Infinity;
    const p = [x - b.c[0], -b.c[1], z - b.c[2]];
    for (let i = 0; i < 3; i++) {
        const q0 = dot(b.u[i], p), dl = b.u[i][1];
        if (Math.abs(dl) < 1e-12) {
            if (Math.abs(q0) > b.e[i]) return -Infinity;
            continue;
        }
        let a = (-b.e[i] - q0) / dl, c = (b.e[i] - q0) / dl;
        if (a > c) [a, c] = [c, a];
        yLo = Math.max(yLo, a);
        yHi = Math.min(yHi, c);
        if (yLo > yHi) return -Infinity;
    }
    return yHi;
}

// ------------------------------------------------------------ 台子与机械臂程序
function rotElementPoint(rot, p) {
    if (!rot) return p;
    const a = rot.angle * Math.PI / 180, c = Math.cos(a), s = Math.sin(a), o = rot.origin;
    const q = sub(p, o);
    let r;
    if (rot.axis === 'x') r = [q[0], q[1] * c - q[2] * s, q[1] * s + q[2] * c];
    else if (rot.axis === 'y') r = [q[0] * c + q[2] * s, q[1], -q[0] * s + q[2] * c];
    else r = [q[0] * c - q[1] * s, q[0] * s + q[1] * c, q[2]];
    return [r[0] + o[0], r[1] + o[1], r[2] + o[2]];
}
/** 方块元素 (单轴旋转, 与原版 / 生成器 rotPoint 同一右手系) → OBB。 */
function elementObb(e) {
    const o = e.rot ? rotElementPoint(e.rot, [0, 0, 0]) : [0, 0, 0];
    const col = (v) => sub(rotElementPoint(e.rot, v), o);
    const [ux, uy, uz] = [col([1, 0, 0]), col([0, 1, 0]), col([0, 0, 1])];
    return obb([ux[0], uy[0], uz[0], o[0], ux[1], uy[1], uz[1], o[1], ux[2], uy[2], uz[2], o[2], 0, 0, 0, 1], e.from, e.to, e.name);
}

const CLAWS = new Set(['left_claw', 'right_claw']);

/**
 * 与枪无关的上下文: 枪床常量、留空范围、床面、静态元素 OBB、机械臂程序 (取仓库里的 GunsmithArmProgram.java, 与游戏一致)、
 * 两个安装点 (程序的 BOLT_PLACE_* / STOCK_PLACE_* 与 station 列), 以及不带下沉的整段采样 (不涉及安装点的程序段与下沉量无关, 各把枪共用)。
 */
export function benchContext(repo) {
    const gen = fs.readFileSync(path.join(HERE, 'generate_assembly_bench.mjs'), 'utf8');
    const cu = /const GUN_CLEAR_UP\s*=\s*([\d.]+)/.exec(gen);
    if (!cu) throw new Error('generate_assembly_bench.mjs: GUN_CLEAR_UP not found');
    const bed = GUN_BED;
    // 留空范围 = 生成器 gunBedChecks 的那块空间; z 范围就是隐形包络的 z 范围 (HALF_WIDTH = 包络 z 半宽)
    const clear = { lo: [bed.BUTT_X - bed.MAX_LENGTH, bed.TOP_Y, bed.AXIS_Z - bed.HALF_WIDTH], hi: [bed.BUTT_X, bed.TOP_Y + Number(cu[1]), bed.AXIS_Z + bed.HALF_WIDTH] };
    const byName = new Map();
    for (const e of [...buildScene(false), ...buildScene(true)]) if (!byName.has(e.name)) byName.set(e.name, e);
    const envelope = byName.get('gun_envelope');
    if (!envelope || !envelope.proxy) throw new Error('bench scene has no gun_envelope proxy');
    const statics = [...byName.values()].filter((e) => !e.proxy).map(elementObb);
    // 床面: 顶面正好在 TOP_Y 的枪床元素 (不旋转) 的 xz 矩形
    const support = [...byName.values()].filter((e) => !e.proxy && !e.rot && e.name.startsWith('bed_') && Math.abs(e.to[1] - bed.TOP_Y) < 1e-9)
        .map((e) => ({ name: e.name, x: [e.from[0], e.to[0]], z: [e.from[2], e.to[2]] }));
    if (!support.length) throw new Error('bench scene: no bed_* element with its top at TOP_Y');

    const arm = new AssetRoot([repo]).arm();
    const program = arm.program;
    if (!program) throw new Error('GunsmithArmProgram.java has no keyframe program');
    if (!program.places) throw new Error('GunsmithArmProgram.java has no BOLT_PLACE_* / STOCK_PLACE_* constants (place drop)');
    const rows = program.rows;
    const places = Object.entries(program.places).map(([key, p]) => {
        const tagged = rows.filter((r) => r.station === p.code);
        const weld = tagged.find((r) => r.spark && r.payload);
        if (!weld) throw new Error(`arm program: the ${key} station has no weld row carrying a part`);
        return { key, ...p, part: program.payloadParts[weld.payload], weldRow: weld, ticks: [tagged[0].tick, tagged[tagged.length - 1].tick] };
    });
    const T = rows[rows.length - 1].tick;
    const ctx = { bed, clear, envelope, statics, support, program, rows, places, T, base: [] };
    for (let i = 0; i <= T * SAMPLES_PER_TICK; i++) ctx.base.push(armSample(ctx, i / SAMPLES_PER_TICK, [0, 0]));
    return ctx;
}

/** 程序时间 t、下沉量 drops 下的机械臂: 各部件 OBB、所在程序段是否是安装点的下沉段 (station, 任一端带 station 列)、反解是否可达。 */
function armSample(ctx, t, drops) {
    const k = sampleProgram(ctx.rows, t, drops[0], drops[1], ctx.program.geo.maxPlaceDrop);
    const seg = Math.max(1, k.seg);
    const station = ctx.rows[seg - 1].station || ctx.rows[seg].station || 0;
    const pose = armPose(k);
    const Mx = armMatrices(pose);
    const boxes = [];
    for (const s of ARM_SAMPLES) {
        if (s.payload && s.payload !== pose.payload) continue;
        const [x, y, z, w, h, d] = s.box;
        boxes.push({ ...obb(Mx[s.part], [x, y, z], [x + w, y + h, z + d], s.part), kind: s.payload ? 'payload' : CLAWS.has(s.part) ? 'claw' : 'arm' });
    }
    return { t, station, ikMiss: k.ikMiss, boxes, all: union(boxes) };
}

/** 整段程序在这组下沉量下的采样: 不涉及安装点的程序段直接用共用的不下沉采样 (与下沉量无关)。 */
function samplesFor(ctx, drops) {
    if (!drops[0] && !drops[1]) return ctx.base;
    return ctx.base.map((s) => (s.station ? armSample(ctx, s.t, drops) : s));
}

/**
 * 一把已烘焙到台上的枪 (bakeBedrockGeo / bakeBenchGun, frame 'bench') 的全部检查结果。
 * drops 省略 = 按这把枪算 (benchGunPlaceDrops, 与游戏同一规则)。
 */
export function analyzeGun(ctx, baked, drops = null) {
    const { bed, clear } = ctx;
    const fails = [], warns = [];
    const lay = baked.layout;
    if (!lay) return { render: false, fails: ['layout: no render (L <= 0 or not finite)'], warns };
    // 画出来的几何 (含 inflate, 机械臂与它比) 与原始边界 (不含 inflate: 摆放规则、安装点枪顶只看它, 贴床面/挡块的约定按它查)
    const cubes = baked.cubes.map((c, i) => obb(c.m, c.lo, c.hi, c.bone + '#' + i));
    const rawCubes = baked.cubes.map((c, i) => obb(c.m, c.lo.map((v) => v + c.inflate), c.hi.map((v) => v - c.inflate), c.bone + '#' + i));
    const gunBox = union(cubes), rawBox = union(rawCubes);
    // (a) 留空范围 / 包络 z 范围 / 床面 / 静态元素
    for (let a = 0; a < 3; a++) {
        if (rawBox.lo[a] < clear.lo[a] - TOL || rawBox.hi[a] > clear.hi[a] + TOL) {
            fails.push(`leaves the ${a === 2 ? 'envelope z range' : 'bed space'} on ${'xyz'[a]}: ${f3(rawBox.lo[a])}..${f3(rawBox.hi[a])} vs ${f2(clear.lo[a])}..${f2(clear.hi[a])}`);
        }
    }
    const onBed = (x, z) => ctx.support.some((s) => x >= s.x[0] - TOL && x <= s.x[1] + TOL && z >= s.z[0] - TOL && z <= s.z[1] + TOL);
    const offBed = [];
    const steps = (lo, hi) => { const n = Math.max(1, Math.ceil((hi - lo) / BED_GRID)); return Array.from({ length: n + 1 }, (_, i) => lo + (hi - lo) * i / n); };
    for (const x of steps(gunBox.lo[0], gunBox.hi[0])) for (const z of steps(gunBox.lo[2], gunBox.hi[2])) if (!onBed(x, z)) offBed.push([x, z]);
    if (offBed.length) fails.push(`overhangs the bed top at ${offBed.length} grid points, e.g. x ${f2(offBed[0][0])} z ${f2(offBed[0][1])}`);
    const benchGap = (set) => {
        let best = { gap: Infinity, what: null };
        for (const s of ctx.statics) {
            if (!aabbNear(s, gunBox, NEAR)) continue;
            for (const c of set) {
                if (!aabbNear(s, c, NEAR)) continue;
                const g = obbGap(s, c);
                if (g < best.gap) best = { gap: g, what: `${c.name} / ${s.name}` };
            }
        }
        return best;
    };
    const bench = benchGap(rawCubes), benchDrawn = benchGap(cubes);
    if (bench.gap < -TOL) fails.push(`intersects the bench: ${bench.what} by ${f3(-bench.gap)} px`);
    else if (benchDrawn.gap < -TOL) warns.push(`inflate pokes ${f3(-benchDrawn.gap)} px into the bench (${benchDrawn.what}; the raw bounds the layout uses only touch it)`);
    // 原始边界比画出来的大 (负 inflate) 时画出来的枪离床面 / 挡块有缝
    const lift = gunBox.lo[1] - bed.TOP_Y, buttGap = bed.BUTT_X - gunBox.hi[0];
    if (lift > 0.05) warns.push(`the drawn gun floats ${f3(lift)} px above the bed (negative inflate: its raw bounds are larger than what is drawn)`);
    if (buttGap > 0.05) warns.push(`the drawn gun ends ${f3(buttGap)} px short of the butt stop (negative inflate)`);

    // (b) 这把枪自己的放件下沉量下的整段程序
    const pd = benchGunPlaceDrops(ctx.program, baked, bed);
    const useDrops = drops || pd.drops;
    const mins = {};
    for (const kind of ['arm', 'claw', 'payload']) for (const win of ['out', 'in']) mins[kind + '_' + win] = { gap: Infinity, what: null, t: null };
    let ikMiss = null;
    for (const s of samplesFor(ctx, useDrops)) {
        if (s.ikMiss && ikMiss === null) ikMiss = s.t;
        if (!aabbNear(s.all, gunBox, NEAR)) continue;
        for (const b of s.boxes) {
            if (!aabbNear(b, gunBox, NEAR)) continue;
            const acc = mins[b.kind + '_' + (s.station ? 'in' : 'out')];
            for (const c of cubes) {
                if (!aabbNear(b, c, NEAR)) continue;
                const g = obbGap(b, c);
                if (g < acc.gap) Object.assign(acc, { gap: g, what: `${b.name} / ${c.name}`, t: s.t });
            }
        }
    }
    if (ikMiss !== null) fails.push(`arm IK unreachable at tick ${ikMiss} with drops ${useDrops.map(f3).join('/')}`);
    for (const [key, m] of Object.entries(mins)) {
        const [kind, win] = key.split('_');
        const where = win === 'in' ? 'inside the place window' : 'outside the place window';
        if (m.gap < -TOL) fails.push(`${kind} penetrates the gun ${where}: ${m.what} by ${f3(-m.gap)} px at tick ${m.t}`);
        else if (win === 'out' && m.gap < ARM_MARGIN) warns.push(`${kind} passes ${f3(m.gap)} px from the gun ${where} (${m.what}, tick ${m.t}; generator margin ${ARM_MARGIN})`);
    }

    // (c) 安装点: 点焊姿态下携带件 (含外扩) 的底面 vs 它投影范围内枪的实际最高点 (竖直线逐 0.05 px 扫)
    const stations = {};
    ctx.places.forEach((p, idx) => {
        const drop = useDrops[idx];
        const k = sampleProgram(ctx.rows, p.weldRow.tick, useDrops[0], useDrops[1], ctx.program.geo.maxPlaceDrop);
        const pose = armPose(k);
        const Mx = armMatrices(pose);
        const parts = ARM_SAMPLES.filter((s) => s.payload === pose.payload).map((s) => {
            const [x, y, z, w, h, d] = s.box;
            return obb(Mx[s.part], [x, y, z], [x + w, y + h, z + d], s.part);
        });
        const foot = union(parts);
        const bottom = foot.lo[1];
        let top = -Infinity, sat = Infinity;
        const under = cubes.filter((c) => c.lo[0] < foot.hi[0] && c.hi[0] > foot.lo[0] && c.lo[2] < foot.hi[2] && c.hi[2] > foot.lo[2]);
        for (let x = foot.lo[0]; x <= foot.hi[0] + 1e-9; x += 0.05) for (let z = foot.lo[2]; z <= foot.hi[2] + 1e-9; z += 0.05) {
            for (const c of under) top = Math.max(top, topAlongVertical(c, Math.min(x, foot.hi[0]), Math.min(z, foot.hi[2])));
        }
        for (const q of parts) for (const c of cubes) if (aabbNear(q, c, NEAR)) sat = Math.min(sat, obbGap(q, c));
        const gunUnder = top > -Infinity;
        const gap = bottom - (gunUnder ? top : bed.TOP_Y);
        const clamped = drop <= 0 ? 'zero' : drop >= ctx.program.geo.maxPlaceDrop ? 'max' : null;
        stations[p.key] = { drop, ruleTop: pd.tops[idx], top: gunUnder ? top : null, gunUnder, bottom, gap, sat, clamped, tick: p.weldRow.tick,
            foot: { x: [foot.lo[0], foot.hi[0]], z: [foot.lo[2], foot.hi[2]] } };
        if (gap < -TOL) fails.push(`${p.key} part clips ${f3(-gap)} px into the gun at the weld pose (tick ${p.weldRow.tick}, drop ${f3(drop)})`);
        if (clamped === 'zero') warns.push(`${p.key}: the gun is taller than the envelope under the part (rule top ${f3(pd.tops[idx])}), drop clamped to 0`);
    });
    const envTop = bed.TOP_Y + bed.ENVELOPE_THICKNESS;
    const above = cubes.filter((c) => c.hi[1] > envTop + TOL);
    const aboveBox = above.length ? union(above) : null;
    return {
        render: true, model: baked.model || 'high', L: lay.L, T: lay.T, k: lay.k, length: gunBox.hi[0] - gunBox.lo[0], side: lay.leftSideUp ? 'LEFT' : 'RIGHT',
        box: gunBox, rawBox, thickness: gunBox.hi[1] - bed.TOP_Y, lift, buttGap, bench, benchDrawn, drops: useDrops, arm: mins, stations, aboveEnvelope: aboveBox,
        cubes: { drawn: baked.stats.drawnCubes, adapter: baked.stats.adapterCubes }, fails, warns,
    };
}

const f1 = (v) => (Number.isFinite(v) ? v.toFixed(1) : String(v));
const f2 = (v) => (Number.isFinite(v) ? v.toFixed(2) : String(v));
const f3 = (v) => (Number.isFinite(v) ? v.toFixed(3) : String(v));
const strip = (s) => (s || '').replace(/§./g, '');
const gapCell = (g) => (g >= NEAR ? '>=2' : f2(g));

function main() {
    const args = {};
    const av = process.argv.slice(2);
    for (let i = 0; i < av.length; i++) if (av[i].startsWith('--')) { const n = av[i + 1]; if (n === undefined || n.startsWith('--')) args[av[i].slice(2)] = true; else { args[av[i].slice(2)] = n; i++; } }
    if (args.lod && args.high) throw new Error('--lod and --high are exclusive (default: both)');
    const models = args.lod ? ['lod'] : args.high ? ['high'] : ['high', 'lod'];
    const repo = path.resolve(args.repo || path.join(HERE, '..', '..'));
    const packs = new TaczPacks(args.packs || DEFAULT_PACKS);
    const blueprints = readBlueprints(repo);
    const only = args.guns ? new Set(String(args.guns).split(',')) : null;
    const guns = only ? blueprints.filter((b) => only.has(b.id)) : blueprints;
    if (only && guns.length !== only.size) throw new Error('unknown blueprint gun in --guns: ' + [...only].filter((id) => !blueprints.some((b) => b.id === id)).join(', '));
    const results = args.results ? JSON.parse(fs.readFileSync(args.results, 'utf8')) : null;
    const t0 = Date.now();
    const ctx = benchContext(repo);
    const g0 = ctx.program.geo;
    console.log(`bench: BUTT_X ${ctx.bed.BUTT_X}, TOP_Y ${ctx.bed.TOP_Y}, AXIS_Z ${ctx.bed.AXIS_Z}, HALF_WIDTH ${ctx.bed.HALF_WIDTH}; bed space x ${f2(ctx.clear.lo[0])}..${f2(ctx.clear.hi[0])} y ${f2(ctx.clear.lo[1])}..${f2(ctx.clear.hi[1])} `
        + `z ${f2(ctx.clear.lo[2])}..${f2(ctx.clear.hi[2])} (= envelope z); bed top ${ctx.support.map((s) => `${s.name} x ${s.x.join('..')} z ${s.z.join('..')}`).join(', ')}; ${ctx.statics.length} static elements`);
    console.log(`arm program: ${ctx.base.length} samples (${SAMPLES_PER_TICK}/tick); MAX_PLACE_DROP ${g0.maxPlaceDrop}, PLACE_CLEARANCE ${g0.placeClearance}`);
    for (const p of ctx.places) console.log(`  place ${p.key.padEnd(5)} footprint x ${p.minX}..${p.maxX} z ${p.minZ}..${p.maxZ}, bottom y ${p.bottomY}; lowered rows ticks ${p.ticks[0]}..${p.ticks[1]}, weld pose tick ${p.weldRow.tick}`);

    const rowsOut = [], hard = [], xcheck = [];
    for (const bp of guns) {
        const gun = resolveGun(packs, bp.id, { lodGeo: true });
        for (const model of models) {
            if (model === 'lod' && !(gun.lod && gun.lod.model.geo)) continue;
            const baked = bakeBenchGun(packs, bp.id, ctx.bed, { gun, lod: model === 'lod' });
            const r = analyzeGun(ctx, baked);
            if (results && model === 'high') {
                const s = results.summary.find((q) => q.id === bp.id);
                const r2 = (v) => Math.round(v * 100) / 100;
                const B = baked.bbox.inflated;
                const bad = [];
                if (!s) bad.push('not in results.json');
                else {
                    for (let a = 0; a < 3; a++) {
                        if (r2(B.min[a]) !== s.fixedFrameBBox.min[a] || r2(B.max[a]) !== s.fixedFrameBBox.max[a]) bad.push(`bbox ${'xyz'[a]} ${r2(B.min[a])}..${r2(B.max[a])} vs ${s.fixedFrameBBox.min[a]}..${s.fixedFrameBBox.max[a]}`);
                    }
                    if (s.muzzleInFixedFrame && r2(baked.z0) !== s.muzzleInFixedFrame[2]) bad.push(`z0 ${r2(baked.z0)} vs ${s.muzzleInFixedFrame[2]}`);
                }
                const rawVsInfl = Math.max(...[0, 1, 2].flatMap((a) => [Math.abs(baked.bbox.raw.min[a] - B.min[a]), Math.abs(baked.bbox.raw.max[a] - B.max[a])]));
                // 侦察时的包围盒含 attachment_adapter 的子节点: 这些方块被隐藏的枪包围盒不同是预期的
                const expected = bad.length && s && baked.stats.adapterCubes > 0;
                xcheck.push({ id: bp.id, ok: !bad.length, expected, adapter: baked.stats.adapterCubes, bad, rawVsInfl });
                if (bad.length && !expected) r.fails.push('results.json mismatch: ' + bad.join('; '));
            }
            rowsOut.push({ bp, g: gun, model, baked, r });
            for (const f of r.fails) hard.push(`${bp.id} [${model}]: ${f}`);
        }
    }

    const keys = ctx.places.map((p) => p.key);
    const head = ['gun', 'model', 'L', 'len', 'k', 'side', 'bench x', 'y', 'z', 'bench', 'drop ' + keys.join('/'), ...keys.map((k) => k + ' gap'), 'part min', 'claw min', 'arm min', 'name'];
    const table = rowsOut.map(({ bp, g, model, r }) => {
        if (!r.render) return [bp.id, model, '-', '-', '-', '-', '-', '-', '-', '-', '-', ...keys.map(() => '-'), '-', '-', '-', strip(g.name)];
        const st = (k) => {
            const s = r.stations[k];
            return (s.gap >= 0 ? '+' : '') + f3(s.gap) + (s.clamped === 'max' ? (s.gunUnder ? ' max' : ' (bed)') : s.clamped === 'zero' ? ' zero' : '');
        };
        const mn = (kind) => Math.min(r.arm[kind + '_out'].gap, r.arm[kind + '_in'].gap);
        return [bp.id, model, f1(r.L), f2(r.length), f3(r.k), r.side,
            `${f2(r.box.lo[0])}..${f2(r.box.hi[0])}`, `${f2(r.box.lo[1])}..${f2(r.box.hi[1])}`, `${f2(r.box.lo[2])}..${f2(r.box.hi[2])}`,
            Math.abs(r.bench.gap) <= TOL ? 'touch' : gapCell(r.bench.gap), r.drops.map(f3).join('/'),
            ...keys.map(st), gapCell(mn('payload')), gapCell(mn('claw')), gapCell(mn('arm')), strip(g.name)];
    });
    const widths = head.map((h, i) => Math.max(h.length, ...table.map((row) => (i === head.length - 1 ? 0 : String(row[i]).length))));
    const line = (row) => row.map((c, i) => (i === row.length - 1 ? String(c) : String(c).padEnd(widths[i]))).join('  ');
    console.log('');
    console.log(line(head));
    console.log(line(widths.map((w, i) => (i === widths.length - 1 ? '----' : '-'.repeat(w)))));
    for (const row of table) console.log(line(row));
    console.log('');
    console.log('model = high-poly or LOD (in game: high-poly only within 8 blocks and for at most 3 benches per frame, LOD elsewhere; a gun without LOD is not drawn beyond 32 blocks);');
    console.log('L = unscaled length of the drawn cubes in the TACZ FIXED frame (px); len = rendered length on the bench;');
    console.log('bench x/y/z = the laid-out gun\'s box (drawn cubes incl. inflate); bench = closest approach to any static bench element (touch = resting on the bed / against the butt stop);');
    console.log(`drop = place drop per station (px, rule of GunsmithBenchGunLayout.placeDrop; max ${g0.maxPlaceDrop}); <station> gap = carried part bottom (incl. CubeDeformation) minus the gun's real top under`);
    console.log(`it at the weld pose (target PLACE_CLEARANCE ${g0.placeClearance}; "max" = drop clamped at MAX_PLACE_DROP, "(bed)" = no gun under the part, "zero" = gun taller than the envelope);`);
    console.log('part/claw/arm min = closest approach of the carried part / claws / other arm segments to the gun over the whole 160-tick program at this gun\'s drops (OBB separating axes).');
    for (const { bp, model, r } of rowsOut) {
        if (!r.render) continue;
        if (r.aboveEnvelope) console.log(`note ${bp.id} [${model}]: rises above the arm's gun envelope (y ${f2(ctx.bed.TOP_Y + ctx.bed.ENVELOPE_THICKNESS)}) at x ${f2(r.aboveEnvelope.lo[0])}..${f2(r.aboveEnvelope.hi[0])}, z ${f2(r.aboveEnvelope.lo[2])}..${f2(r.aboveEnvelope.hi[2])}, up to y ${f2(r.aboveEnvelope.hi[1])}`);
        for (const w of r.warns) console.log(`WARN ${bp.id} [${model}]: ${w}`);
    }
    const drawn = rowsOut.filter((q) => q.r.render);
    const minOf = (sel) => drawn.reduce((a, q) => { const v = sel(q.r); return v.gap < a.gap ? { ...v, id: q.bp.id, model: q.model } : a; }, { gap: Infinity });
    const worst = {
        part: minOf((r) => (r.arm.payload_out.gap < r.arm.payload_in.gap ? r.arm.payload_out : r.arm.payload_in)),
        claw: minOf((r) => (r.arm.claw_out.gap < r.arm.claw_in.gap ? r.arm.claw_out : r.arm.claw_in)),
        arm: minOf((r) => (r.arm.arm_out.gap < r.arm.arm_in.gap ? r.arm.arm_out : r.arm.arm_in)),
    };
    for (const [k, v] of Object.entries(worst)) if (Number.isFinite(v.gap)) console.log(`closest ${k}: ${f3(v.gap)} px (${v.id} [${v.model}], ${v.what}, tick ${v.t})`);
    const gaps = drawn.flatMap((q) => Object.values(q.r.stations).filter((s) => !s.clamped).map((s) => s.gap));
    if (gaps.length) console.log(`weld contact gap (unclamped stations): ${f3(Math.min(...gaps))} .. ${f3(Math.max(...gaps))} px over ${gaps.length} stations`);
    if (results && !xcheck.length) {
        console.log('results.json cross-check skipped: results.json only covers the high-poly models, and none was checked');
    } else if (results) {
        const bad = xcheck.filter((x) => !x.ok && !x.expected), exp = xcheck.filter((x) => x.expected);
        console.log(`results.json cross-check (FIXED-frame bbox incl. inflate + muzzle z, 2 decimals): ${xcheck.filter((x) => x.ok).length}/${xcheck.length} match, `
            + `${exp.length} differ as expected (attachment_adapter cubes hidden, the scout counted them); raw vs inflated bbox differ by at most ${f3(Math.max(...xcheck.map((x) => x.rawVsInfl)))} px`);
        for (const x of exp) console.log(`  expected ${x.id} (${x.adapter} adapter cubes hidden): ${x.bad.join('; ')}`);
        for (const x of bad) console.log(`  MISMATCH ${x.id}: ${x.bad.join('; ')}`);
    }
    if (args.json) {
        fs.writeFileSync(args.json, JSON.stringify(rowsOut.map(({ bp, model, r }) => ({ id: bp.id, platform: bp.platform, model, ...r })), null, 2));
        console.log('wrote', args.json);
    }
    console.log(`${rowsOut.length} models (${guns.length} guns) in ${((Date.now() - t0) / 1000).toFixed(1)} s`);
    packs.close();
    if (hard.length) {
        console.error('HARD FAILURES:\n  ' + hard.join('\n  '));
        process.exit(1);
    }
    console.log('OK: every gun lies inside the envelope z range on the bed, touches no bench element, and no arm part, claw or carried part enters it at its own place drops');
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main();
