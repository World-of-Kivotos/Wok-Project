#!/usr/bin/env node
// 军火台 (munitions_bench) WIDE 布局「弹药流水线」的全部资源与生成的 Java (方案 B v2 的生产版)。
// 用法: node tools/munitions_bench/generate_munitions_bench.mjs --out <仓库根> [--check]
//   先在内存里生成并校验全部文件, 全部通过才写盘 (--check 只校验不写); 内容没变的文件不重写, 最后报告 "wrote X of Y"。
//
// 一条横跨两格的低矮流水线: 皮带沿 -x (从玩家左手的 extension 流向右手的 main) 一步一个弹位 (4 px),
// 弹壳依次经过 底火 → 装药 (合成一座"装填塔") → 压弹头 (一台小四柱压机, 全机唯一的高点 22.5 px), 最后从皮带末端掉进弹药箱。
//   extension (x 16..32, 玩家左手): 弹壳料斗 + 落壳管, 装填塔 (底火窗 + 药窗, 一根横梁带底火冲杆和装药管), 台前弹壳托盘 + 控制台
//   main (x 0..16, 玩家右手):       四柱压弹头机, 台前弹头托盘, 敞口弹药箱 (满箱子弹 + 掀开的箱盖), 箱后备用弹药箱
// 场景 (scene) 与方案评选时的方案 B v2 相同 (候选脚本只在本机 .candidates/munitions_b/, 不进版本库), 只有弹药箱换成了计数屏方案 C
// "弹药箱计数" (候选 .candidates/munitions_counter/, 同样不进版本库): 箱盖内面一块凹窗 (满度条 + 发数由方块实体渲染器画),
// 箱身正面留一块空标签给渲染器写口径; 布局与颜色在 counter.mjs, 写进 MunitionsBenchGeometry 的 COUNTER_* 常量。设计要点见同目录 README.md。
//
// 写出 (全部在 --out 下):
//   src/main/resources/assets/miningdim/
//     models/block/munitions_bench{档}_line_{main|extension}[_active].json   静态件 (运动件不进 JSON), 待机 / 工作
//     models/item/munitions_bench{档}.json                                    物品模型
//     textures/block/munitions_bench{档}_atlas.png / _particle.png            每档一张 128² 图集 + 破坏粒子
//     textures/entity/munitions_bench_parts.png                               运动件贴图 (六档共用)
//     blockstates/munitions_bench{档}.json                                    layout=legacy_depth → 旧 JSON 模型 (原样), layout=wide → 上面的新模型
//   src/main/java/com/miningdim/job/munitions/
//     block/MunitionsBenchProgram.java    只替换 "<generated>" 与 "</generated>" 两行注释之间 (循环常量 + 关键帧表 + 待机行)
//     block/MunitionsBenchGeometry.java   整个写出 (碰撞/轮廓箱、模型高度、运动件范围、火花位置、计数屏的布局与颜色、运行灯效的 LIGHT_* (lights.mjs))
//     client/MunitionsBenchParts.java     整个写出 (运动件 ModelPart 层 + applyPose)
// {档} = '' | _medium | _high | _superior | _transcendent | _radiant。
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
    M, HAZ_Y, HAZ_K, LABEL, INK, PLATE_BG, OFF, mix, C, TIERS, CELLS, WORLD, LEGAL_ANGLES, r4, worldBox,
    runScene, cullHidden, autoShade, zfightCheck, planFace, planSignature, planAtlas, paintAtlas, cellElements,
    validateModel, ITEM_DISPLAY, itemElements, defaultParticle, stringifyModel, encodePng, Canvas, palette,
} from './core.mjs';
import {
    buildLayer, paintLayer, partsJavaSource, parsePartsJava, programBlock, parseProgramJava, RE_GENERATED,
    PROGRAM_COLUMNS, sampleProgram, idlePose, applyPoseMirror, placedBoxes,
} from './ber.mjs';
import {
    DISPLAY, QT, counterColours, layoutFromDisplay, colourTable, counterJavaLines, parseCounterJava, windingProblems,
    formatCount, textWidth, FONT_4x7, FONT_3x5, CALIBER_LABELS, MAX_ROUNDS, contrast,
} from './counter.mjs';
import {
    lightsLayout, lightsJavaLines, parseLightsJava, layoutDiff, targetProblems, elementsOf, programProblems, frameProblems, overlayTopPx,
    MAX_DISTANCE_BLOCKS as LIGHT_MAX_DISTANCE_BLOCKS, EFFECTS as LIGHT_EFFECTS,
} from './lights.mjs';

const SCRIPT_DIR = path.dirname(fileURLToPath(import.meta.url));

// ================================================================ 本方案专用中性材质 (每档相同)
// 被甲: 比 core 的 copper 更亮更橙 (评审: 铜色在侧面阴影下发棕, 读成"棕色方块")
const JACKET = { name: 'jacket', base: C('#D9874C'), hi: C('#F4B27E'), lo: C('#A65B2C'), dk: C('#6E3A1A') };
// 发射药: 原版火药那种灰 (评审: 橄榄灰 + 抖动像脏迷彩窗)
const GPOWDER = { name: 'gpowder', base: C('#7A7A7A'), hi: C('#9A9A9A'), lo: C('#565656'), dk: C('#343434') };

// ================================================================ 流水线常量 (世界像素, 朝北: x 东, y 上, z 南, 正面 z = 0)
const BELT_Y = 9;                                    // 皮带面
const RZ = 7.5;                                      // 弹位中心 z (皮带 z 5.5..9.5)
const SLOT = { in: 26.5, prime: 22.5, powder: 18.5, seat: 14.5, inspect: 10.5 };   // 弹位中心 x, 节距 4
const PITCH = 4;
// 一发弹 (2026-09 用户嫌"子弹显得太大", 整体缩到约 2/3; 方案 B v2 原来是 2 x 2 x 3.5 壳 + 1.5 x 1.25 被甲 + 1 x 1 弹尖, 全高 5.75):
// 1.5 x 1.5 x 2.5 黄铜壳 + 1 宽 x 0.75 高被甲 + 0.5 宽 x 0.75 高弹尖, 全高 4 (皮带上: 底 9, 口 11.5, 顶 13)。
// 宽度只能取 0.5 的整数倍 (弹位中心在 .5 上, 半宽要落在 0.25 网格上), 所以壳 1.5 / 被甲 1 / 弹尖 0.5。
// 所有弹药道具共用这一个比例: 皮带上的弹、出弹、冲头夹着的弹头、弹药箱里冒出的弹头 (静态 JSON, 最薄 0.5 px 正好是弹尖),
// 以及箱顶 / 托盘 / 料斗顶画出来的弹 (贴图密度 RD: 1 贴图像素 = 0.25 px, 与运动件盒式 UV 的 4 texel/px 同一个分辨率)。
const R = { dia: 1.5, caseH: 2.5, bodyW: 1, bodyH: 0.75, noseW: 0.5, noseH: 0.75 };
const BULLET_H = R.bodyH + R.noseH;                 // 1.5
const ROUND_H = R.caseH + BULLET_H;                 // 4
const MOUTH_Y = BELT_Y + R.caseH;                   // 皮带上的壳口 = 11.5 (冲压火花的 y)
const RD = 4;                                       // 弹药道具的贴图密度 (贴图像素 / 模型像素)
const q4 = (v) => Math.round(v * 4) / 4;            // 取到 0.25 网格
// 弹药箱 (DISPLAY.stencil.el = 箱身 [1,3,1]-[8,8.5,10]) 敞口的顶面画一格格紧挨着直立的整发弹 (4 列 x 5 行, 中心间距 = 壳径 1.5),
// 其中 CAN_3D 这几格是立体的弹头 (被甲 + 弹尖, 静态 JSON)。
const CAN_TOP = DISPLAY.stencil.el.to[1];            // 8.5
const CAN_GRID = { x: [2.25, 3.75, 5.25, 6.75], z: [2.5, 4, 5.5, 7, 8.5] };
const CAN_3D = [[0, 0], [2, 0], [1, 1], [3, 1], [0, 2], [2, 3]];   // [列, 行]

// ---- 装填塔横梁上的底火冲杆 / 装药管 (横梁 charge_arm y 15..16): 顶端 16.5 (露出横梁顶 0.5), 静止时下端停在壳口上方 ROD_GAP,
//      下探 ROD_GAP 正好顶到壳口。弹缩小后壳口低了 1 px, 两根杆随之往下加长 1 px (行程不变; 只加大行程的话杆顶会离开横梁悬空)。
const ROD_TOP = 16.5;
const ROD_GAP = 1.5;
const ROD_REST_BOTTOM = MOUTH_Y + ROD_GAP;          // 13
// ---- 压弹头冲头: 压模静止时顶面贴着压机横梁底 (18.5), 夹着的弹头挂在压模底 (弹尖顶 = 压模底 16.75);
//      冲头下行 RAM_STROKE 正好把弹头放到压弹头位那只壳的壳口上 (f2 = STRIKE_TICK)。连杆长 = 行程: 压到底时连杆顶正好还在横梁底面,
//      静止时整根藏在横梁 / 法兰 / 液压缸里 (弹缩小后行程从 2 变成 3.75, 连杆从 2 加长到 3.75)。
const DIE = { from: [13.25, 16.75, 6.25], to: [15.75, 18.5, 8.75] };
const RAM_REST = DIE.from[1] - BULLET_H;             // 冲头夹着的弹头底面 (静止位) = 15.25
const RAM_STROKE = MOUTH_Y - RAM_REST;               // -3.75

// ---- 一个生产循环 8 帧 (40 tick = 2 s): f0 底火冲一下, f1 装药管下探, f1-f2 压弹头 (f2 到底, 压模发热),
//      f3-f5 皮带步进一个节距, f5-f7 末端那发掉进弹药箱。f5-f7 与下一轮 f0 的皮带位置等价 (差一整个节距)。
// 这些表同时驱动场景 (方案预览的逐帧) 与 MunitionsBenchProgram 的关键帧表 (游戏里的运动件), 两边读同一份数值。
const CYCLE = 8;
const TICKS_PER_FRAME = 5;
const BELT = [0, 0, 0, -1, -2.5, -4, -4, -4];        // 皮带上的弹 x 偏移
const PRIME = [-ROD_GAP, 0, 0, 0, 0, 0, 0, 0];       // 底火冲杆 y 偏移 (-1.5 = 顶到壳口)
const POWDER = [0, -ROD_GAP, 0, 0, 0, 0, 0, 0];      // 装药管 y 偏移
const RAM = [0, q4(RAM_STROKE / 2), RAM_STROKE, 0, 0, 0, 0, 0];   // 压弹头冲头 y 偏移 (0 / -1.75 / -3.75 = 弹头落到壳口上)
// 出弹 y 偏移: f5 沉进箱口约 1/4 弹高, f6 约 3/5, f7 整发没入 (弹尖顶比箱顶低 0.25, 避开与箱顶共面) = 0 / -1 / -2.5 / -4.75
const DROP_Y = [0, 0, 0, 0, 0, -1, -2.5, CAN_TOP - 0.25 - (BELT_Y + ROUND_H)];
const RAM_BULLET = [1, 1, 1, 0, 0, 1, 1, 1];         // 冲头夹着弹头吗 (评审 must 3: 刚回位的 f3-f4 不夹, 不再和刚压好的那发叠成两截铜头)
const POWDER_CHARGED = [0, 0, 1, 1, 1, 1, 1, 1];     // 装药位那只壳已装药 (f1 装药管下探之后)
const SEATED = [0, 0, 0, 1, 1, 1, 1, 1];             // 压弹头位那发已压上弹头 (f2 冲头到底之后)
const DIE_HEAT = [0, 0, 1, 0, 0, 0, 0, 0];           // 压模热度 (f2 到底时发热)
const ACTIVE_FRAME = 2;                              // 方案预览的静态 *_active 模型取 f2 (冲头到底); 游戏里的工作态静态模型不含运动件
const HOT_THRESHOLD = 0.5;                           // 压模热度到这个值就画热压模
const HOT_LIGHT = 12;                                // 热压模的自发光等级
const FRAME_LABELS = [
    'f0 底火冲杆下探, 入口位落下一只新壳',
    'f1 装药管下探, 冲头下行',
    'f2 冲头到底, 弹头落到壳口上 (压模发热)',
    'f3 皮带步进, 冲头回位 (不夹弹头)',
    'f4 皮带步进',
    'f5 皮带到位, 冲头夹上下一颗弹头, 出弹沉进箱口',
    'f6 出弹下沉',
    'f7 出弹没入箱中',
];

// ---- 四柱压机 (以压弹头弹位 x 14.5 / z 7.5 为中心)
const PRESS = {
    postX: [[12, 13], [16, 17]],                     // 东边两根整根落在 extension 里 (x 16..17), 不跨缝
    postZ: [[4.25, 5.25], [9.75, 10.75]],
    postTop: 18.5,
    crown: [[11.5, 18.5, 3.75], [17.5, 20.5, 11.25]],
};

// ================================================================ 贴图 (模块级, 纯函数; 用参数 P)
// 弹壳侧面 (1.5 x 2.5 px, RD = 4: 6 x 10): 左亮右暗的圆柱感, 壳口唇 / 肩线 / 抽壳槽 / 底缘各一行
const CASE_SIDE = { key: 'rcase3', fn: (c) => {
    const m = M.brass;
    const ramp = [m.hi, mix(m.hi, m.base, 0.5), m.base, m.base, mix(m.base, m.lo, 0.5), m.lo];
    for (let x = 0; x < c.w; x++) c.vline(x, 0, c.h, ramp[Math.min(ramp.length - 1, Math.floor((x * ramp.length) / c.w))]);
    c.hline(0, 0, c.w - 1, m.hi);                      // 壳口唇
    c.hline(1, 2, c.w - 2, mix(m.base, m.lo, 0.45));   // 肩线 (瓶颈形弹壳的收口)
    c.hline(0, c.h - 2, c.w, m.dk);                    // 抽壳槽
    c.hline(0, c.h - 1, c.w, m.lo);                    // 底缘
} };
// 被甲侧面 (1 x 0.75 px: 4 x 3): 受光侧亮带, 底下一行收口线
const TIP_SIDE = { key: 'rtip3', fn: (c) => {
    const m = JACKET;
    c.fill(m.base);
    c.vline(0, 0, c.h, m.hi); c.vline(1, 0, c.h, mix(m.hi, m.base, 0.5)); c.vline(c.w - 1, 0, c.h, m.lo);
    c.hline(1, c.h - 1, c.w - 1, m.lo);                                                   // 收口线
} };
// 弹尖侧面 (0.5 x 0.75 px: 2 x 3)
const NOSE_SIDE = { key: 'rnose3', fn: (c) => {
    const m = JACKET;
    c.fill(m.base); c.vline(0, 0, c.h, m.hi); c.px(c.w - 1, c.h - 1, m.lo);
} };
/** 壳口 (俯视, 6 x 6 贴图像素 = 1.5 px 的壳顶): 肩 (上/左亮、下/右暗) → 瓶颈一圈亮唇 → 中间 0.5 px 的口 (空 = 黑, 已装药 = 灰色发射药)。 */
function mouthTop(c, x, y, charged) {
    const m = M.brass;
    c.rect(x, y, 6, 6, m.base);
    c.hline(x, y, 5, m.hi); c.vline(x, y, 5, m.hi);
    c.hline(x + 1, y + 5, 5, m.lo); c.vline(x + 5, y + 1, 5, m.lo);
    c.rect(x + 1, y + 1, 4, 4, mix(m.hi, m.base, 0.4));
    c.px(x + 4, y + 4, m.base);
    if (charged) { c.rect(x + 2, y + 2, 2, 2, GPOWDER.base); c.px(x + 2, y + 2, GPOWDER.hi); }
    else { c.rect(x + 2, y + 2, 2, 2, M.dark.dk); }
}
function mouthFn(charged) {
    return (c) => mouthTop(c, 0, 0, charged);
}

/** 皮带上的一发 (直立): 壳 (+ 被甲 + 弹尖)。tip=false 时是未压弹头的壳, mouth 'empty' | 'charged'。 */
function roundAt(ctx, name, x, y, z, o = {}) {
    const { box, paint, flat } = ctx;
    const h = R.dia / 2;
    const opt = { move: o.move, item: o.item };
    const tip = o.tip !== false;
    const side = paint(CASE_SIDE.key, CASE_SIDE.fn, { m: M.brass, d: RD });
    const up = tip ? flat(M.brass.hi) : paint(o.mouth === 'charged' ? 'mouth_charged3' : 'mouth_empty3', mouthFn(o.mouth === 'charged'), { m: M.brass, d: RD });
    box(name + '_case', [x - h, y, z - h], [x + h, y + R.caseH, z + h], { all: side, up, down: null }, opt);
    if (tip) bulletAt(ctx, name, x, y + R.caseH, z, opt);
}
/** 一颗弹头 (被甲 + 弹尖, 弹尖朝上), y = 弹头底。base = 被甲底面 (悬空的弹头才要; 坐在壳口或箱里的看不到底, 不建)。 */
function bulletAt(ctx, name, x, y, z, opt = {}, base = null) {
    const { box, paint, flat } = ctx;
    const b = R.bodyW / 2, n = R.noseW / 2;
    box(name + '_tip', [x - b, y, z - b], [x + b, y + R.bodyH, z + b], { all: paint(TIP_SIDE.key, TIP_SIDE.fn, { m: JACKET, d: RD }), up: flat(JACKET.hi), down: base }, opt);
    box(name + '_nose', [x - n, y + R.bodyH, z - n], [x + n, y + BULLET_H, z + n], { all: paint(NOSE_SIDE.key, NOSE_SIDE.fn, { m: JACKET, d: RD }), up: flat(JACKET.hi), down: null }, opt);
}
/**
 * 俯视的一发 (6 x 6 贴图像素 = 1.5 px, RD = 4), 左上角在 (x, y), 四角压成 gap 色 (看上去是圆的):
 * full = 整发弹 (黄铜壳口一圈 + 1 px 被甲 + 0.5 px 亮弹尖), 否则空壳 (与皮带上空壳的壳口同一画法)。料斗顶、弹药箱顶用。
 */
function roundTop(c, x, y, full, gap) {
    const m = M.brass;
    if (full) {
        const J = JACKET;
        c.rect(x, y, 6, 6, m.base);
        c.hline(x, y, 5, m.hi); c.vline(x, y, 5, m.hi);
        c.hline(x + 1, y + 5, 5, m.lo); c.vline(x + 5, y + 1, 5, m.lo);
        c.rect(x + 1, y + 1, 4, 4, J.base);
        c.px(x + 1, y + 1, J.hi); c.px(x + 2, y + 1, J.hi); c.px(x + 1, y + 2, J.hi);
        c.px(x + 4, y + 4, J.lo); c.px(x + 3, y + 4, J.lo); c.px(x + 4, y + 3, J.lo);
        c.rect(x + 2, y + 2, 2, 2, J.hi); c.px(x + 3, y + 3, J.base);
    } else mouthTop(c, x, y, false);
    for (const [i, j] of [[0, 0], [5, 0], [0, 5], [5, 5]]) c.px(x + i, y + j, gap);
}

// ---- 大面贴图
function cabFront(c) {
    // 柜体正面 cab [8,1,1.5]-[31.5,6.5,15], d = 2, u = (31.5 - x) * 2 (贴图左 = 东 = extension)
    const m = M.frame;
    c.fill(m.base); c.bevel(m);
    for (const u of [13, 31]) { c.vline(u, 1, c.h - 2, m.dk); c.vline(u + 1, 1, c.h - 2, m.hi); }
    c.vent(2, 2, 10, 5, m, 2);
    c.rect(2, 8, 4, 2, HAZ_Y); c.px(3, 8, HAZ_K); c.px(4, 9, HAZ_K);
    c.rect(7, 8, 5, 2, m.dk); c.px(8, 8, M.cyan.base); c.px(10, 8, M.orange.base);
    c.rect(16, 2, 14, 8, m.dk); c.inset(m, 15, 1, 16, 10);
    for (const y0 of [1, 6]) {
        c.hline(34, y0, 12, m.hi); c.hline(34, y0 + 3, 12, m.dk); c.vline(34, y0, 4, m.hi); c.vline(45, y0, 4, m.lo);
        c.rect(36, y0 + 1, 3, 2, LABEL); c.hline(37, y0 + 1, 1, INK);
        c.hline(40, y0 + 2, 4, M.chrome.hi); c.px(43, y0 + 2, M.chrome.lo);
    }
}
function nameplateFn(on) {
    return (c, P) => {
        // 铭牌 [17,2,1]-[23.5,5.5,1.5] 北面, d = 4: 26 x 14。三发直立子弹图标 + 右侧 2x3 档位刻痕 (每格 2x2 贴图像素)
        c.fill(PLATE_BG);
        c.bevel({ hi: P.band.hi, lo: P.band.lo, base: P.band.base });
        c.hline(1, 1, c.w - 2, P.band.dk);
        const rad = P.tierIndex === 5;
        const ink = rad ? (on ? P.band.hi : P.band.base) : (on ? P.glow.on : P.light.dim);
        const inkLo = rad ? (on ? P.band.base : P.band.lo) : (on ? P.light.mid : P.light.dk);
        const im = { base: ink, hi: ink, lo: inkLo, dk: inkLo };
        for (let k = 0; k < 3; k++) c.round(3 + k * 5, 3, 9, 3, 'up', { case: im, tip: im });
        for (let i = 0; i < 6; i++) {
            const x = 18 + (i % 2) * 3, y = 3 + Math.floor(i / 2) * 3;
            c.rect(x, y, 2, 2, i <= P.tierIndex ? (on ? P.light.hi : (rad ? P.band.base : P.light.dim)) : P.band.dk);
        }
    };
}
function beltTop(c) {
    const m = M.rubber;
    c.fill(m.base);
    c.hline(0, 1, c.w, m.dk); c.hline(0, 8, c.w, m.dk);
    c.hline(0, 4, c.w, mix(m.base, m.hi, 0.35));
    for (let u = 2; u < c.w; u += 4) c.px(u, 2, m.lo);
}
function convFront(c, P) {
    // conveyor 北面 = 皮带侧板 (y 8..9), d = 2: 上行色带 (闪耀 = 金), 下行饰色 + 滚筒轴头
    c.fill(P.trim.base); c.hline(0, 0, c.w, P.band.hi);
    for (let u = 3; u < c.w - 1; u += 8) { c.px(u, 1, M.chrome.hi); c.px(u + 1, 1, M.dark.base); }
}
function hopperFront(c) {
    // 弹壳料斗 [24.5,8,10.5]-[31.5,16,15.5] 北面, d = 2: 14 x 16。u = (31.5 - x) * 2, v = (16 - y) * 2
    // 视窗在 u 1..6 (x 28.5..31), 不被落壳管挡住
    const m = M.panel;
    c.fill(m.base); c.bevel(m);
    c.rect(1, 4, 6, 8, M.glass.dk); c.inset(m, 0, 3, 8, 10);
    c.hline(1, 4, 6, mix(M.glass.hi, M.glass.dk, 0.4));
    for (const y of [6, 8, 10]) {
        c.rect(1, y, 6, 2, M.brass.base); c.hline(1, y, 6, M.brass.hi); c.px(6, y + 1, M.brass.lo);
        c.px(1, y, M.brass.lo); c.px(1, y + 1, M.brass.dk);
    }
    c.rect(9, 5, 4, 5, LABEL); c.hline(10, 6, 2, INK); c.hline(10, 8, 2, INK);
    c.hazard(8, 12, 5, 3, 4);
}
function hopperSide(c) {
    const m = M.panel;
    c.fill(m.base); c.bevel(m);
    c.vent(2, 3, c.w - 4, 6, m, 2);
    c.hazard(2, c.h - 4, c.w - 4, 2, 4);
}
function hopperTop(c) {
    // 料斗敞口顶面 (7 x 5 px), d = RD (28 x 20): 三排直立的空弹壳 (与皮带上的壳同径 1.5 px), 4 / 3 / 4 只错开半格, 挤在一起
    const m = M.panel;
    c.fill(M.dark.base);
    for (let r = 0; r < 3; r++) {
        const n = r === 1 ? 3 : 4, x0 = r === 1 ? 5 : 2;
        for (let k = 0; k < n; k++) roundTop(c, x0 + k * 6, 1 + r * 6, false, M.dark.base);
    }
    c.bevel(m);
}
function caseTube(c) {
    // 玻璃落壳管侧面: 上下各一道镀铬管箍, 中间一叠两行一只的黄铜壳。叠壳只画到管箍上方 (两行都要落在管箍之上,
    // 否则管子一加长, 最后一只壳的暗行会盖掉下管箍, 管子直接以一道暗铜色撞进下面的壳)。
    c.fill(M.glass.dk);
    c.hline(0, 0, c.w, M.chrome.hi); c.hline(0, c.h - 1, c.w, M.chrome.lo);
    for (let y = 1; y + 1 < c.h - 1; y += 3) { c.hline(0, y, c.w, M.brass.base); c.hline(0, y + 1, c.w, M.brass.lo); }
    c.vline(0, 1, c.h - 2, mix(M.glass.hi, M.glass.base, 0.3));
}
function turretFront(c) {
    // 装填塔 [17.5,8,10.5]-[24,16,14] 北面, d = 2: 13 x 16。u = (24 - x) * 2, v = (16 - y) * 2
    // 左窗 (u 1..5, x 21.5..23.5) = 一叠铜色底火; 右窗 (u 8..12, x 18..20) = 灰色发射药; 中间一条药量刻度
    const m = M.frame;
    c.fill(m.base); c.bevel(m);
    c.vent(1, 1, c.w - 2, 3, m, 2);
    c.rect(1, 5, 4, 7, M.glass.dk); c.inset(m, 0, 4, 6, 9);
    for (let y = 6; y < 11; y++) c.hline(1, y, 4, y % 2 ? M.copper.base : M.copper.lo);
    c.vline(1, 6, 5, M.copper.hi); c.hline(1, 5, 4, mix(M.glass.hi, M.glass.dk, 0.4));
    c.rect(7, 5, 5, 7, M.glass.dk); c.inset(m, 6, 4, 7, 9);
    for (let y = 7; y < 11; y++) for (let x = 7; x < 12; x++) c.px(x, y, (x * 2 + y * 3) % 7 === 0 ? GPOWDER.dk : (x + y) % 5 === 0 ? GPOWDER.hi : GPOWDER.base);
    c.hline(7, 7, 5, GPOWDER.hi); c.hline(7, 5, 5, mix(M.glass.hi, M.glass.dk, 0.4));
    for (let y = 5; y < 11; y += 2) c.px(12, y, m.hi);
    c.px(12, 7, M.orange.base);
    c.hazard(1, 13, c.w - 2, 2, 4);
}
function turretSide(c) {
    const m = M.frameL;
    c.fill(m.base); c.bevel(m);
    c.vent(2, 3, c.w - 4, 6, m, 2);
    c.hazard(1, c.h - 3, c.w - 2, 2, 4);
}
function turretTop(c) {
    // 装填塔顶面, d = 2: 13 x 7 (u = (x - 17.5) * 2, v = (z - 10.5) * 2)。西半边压着黄铜药斗盖, 东半边是底火弹匣玻璃窗
    const m = M.frame;
    c.plate(m);
    c.rect(8, 1, 4, 5, M.glass.dk); c.inset(m, 7, 0, 6, 7);
    for (const [x, y] of [[8, 1], [10, 1], [9, 3], [8, 5], [10, 5], [11, 3]]) { c.px(x, y, M.copper.hi); c.px(x + 1, y, M.copper.base); }
}
function capTop(c) {
    const m = M.brass;
    c.fill(m.base); c.bevel(m); c.rect(2, 2, c.w - 4, c.h - 4, m.lo); c.rect(3, 3, c.w - 6, c.h - 6, m.base); c.px(3, 3, m.hi);
}
function postSide(c) {
    // 压机立柱 (1 x 10.5 → 2 x 21): 镀铬两色 + 底部黄黑护套 + 顶部一道阴影
    c.vline(0, 0, c.h, M.chrome.hi); c.vline(1, 0, c.h, M.chrome.base);
    c.hline(0, 0, 2, M.chrome.lo);
    c.rect(0, c.h - 4, 2, 1, HAZ_Y); c.rect(0, c.h - 3, 2, 1, HAZ_K); c.rect(0, c.h - 2, 2, 1, HAZ_Y); c.hline(0, c.h - 1, 2, M.frame.lo);
}
function crownFront(c) {
    // 压机横梁正面 (6 x 2 → 12 x 4, u = (17.5 - x) * 2): 上半警示斜纹, 下半被档位灯条 crown_light 盖住
    const m = M.panel;
    c.fill(m.base);
    c.hazard(0, 0, c.w, 2, 4);
    c.hline(0, 2, c.w, m.lo); c.hline(0, 3, c.w, m.dk);
}
function crownTop(c) {
    const m = M.panelB;
    c.plate(m);
    c.bolts(M.panel.lo);
    c.vent(2, 11, c.w - 4, 3, m, 2);
}
function trimSide(c, P) {
    c.plate(P.trim);
    c.hline(1, 1, c.w - 2, P.trim.hi);
    c.hline(2, c.h - 2, c.w - 4, P.band.base);
}
function cylSide(c) {
    // 压机液压缸侧面: 枪灰圆柱感 + 底部一道黄铜环
    const m = M.frame;
    c.fill(m.base); c.vline(0, 0, c.h, m.hi); c.vline(c.w - 1, 0, c.h, m.lo);
    c.hline(0, 0, c.w, m.hi);
    c.hline(0, c.h - 1, c.w, M.brass.base);
}
function flangeTop(c) { const m = M.brass; c.plate(m); c.bolts(m.lo); }
function dieFn(hot) {
    return (c) => {
        if (hot) { c.gradient(0, 0, c.w, c.h, [M.chrome.base, M.hot.base, M.hot.lo], 'v'); c.hline(0, c.h - 1, c.w, M.hot.hi); }
        else { c.cyl(M.chrome, 'h'); c.hline(0, c.h - 1, c.w, M.steel.lo); }
    };
}
function rodSide(c) { c.vline(0, 0, c.h, M.chrome.hi); for (let x = 1; x < c.w; x++) c.vline(x, 0, c.h, x === c.w - 1 ? M.chrome.lo : M.chrome.base); }
function backlightFn(on) {
    return (c, P) => {
        // 皮带后护栏正面 (23 x 1.5 → 46 x 3): 顶上一行钢, 下面两行灯 (每 8 格一道分隔); 闪耀档每段中间嵌一块金
        c.fill(on ? P.light.on : P.light.dim);
        c.hline(0, 0, c.w, M.steel.hi);
        c.hline(0, 1, c.w, on ? P.light.hi : P.light.mid);
        for (let u = 7; u < c.w; u += 8) c.vline(u, 1, 2, on ? P.light.mid : P.light.dk);
        if (P.tierIndex === 5) for (let u = 2; u < c.w - 2; u += 8) { c.hline(u, 1, 3, P.band.hi); c.hline(u, 2, 3, P.band.base); }
    };
}
function canFront(c) {
    // 弹药箱正面 [1,3,1]-[8,8.5,10], d = 4: 28 x 22 (1 贴图像素 = 1 qt)。原来写死的 "7.62" 换成一块深一点的空标签区
    // (DISPLAY.stencil.label): 口径由方块实体渲染器画成黄漆模板字 (缓冲是别的口径时不会和箱子对不上), 缓冲空时不写
    const m = M.olive, L = DISPLAY.stencil.label;
    c.fill(m.base); c.bevel(m);
    c.hline(1, 2, c.w - 2, m.lo); c.hline(1, 3, c.w - 2, m.hi);
    c.rect(L.x, L.y, L.w, L.h, mix(m.base, m.lo, 0.55));
    c.hline(L.x, L.y, L.w, m.lo); c.hline(L.x, L.y + L.h - 1, L.w, mix(m.base, m.hi, 0.5));
    c.hline(4, 16, c.w - 8, HAZ_Y);
    c.hline(4, 18, 9, mix(HAZ_Y, m.base, 0.4)); c.hline(4, 20, 6, mix(HAZ_Y, m.base, 0.4));
    c.rect(c.w - 7, 16, 4, 4, m.dk); c.bolt(c.w - 6, 17, M.chrome);
}
function canSide(c) {
    const m = M.olive;
    c.fill(m.base); c.bevel(m);
    c.hline(1, 1, c.w - 2, m.lo); c.hline(1, 2, c.w - 2, m.hi);
    c.rect(4, 4, c.w - 8, 3, m.lo); c.hline(5, 5, c.w - 10, M.dark.base);
    c.hline(2, c.h - 3, c.w - 4, HAZ_Y);
}
function canTop(c) {
    // 敞口箱顶 (7 x 9 px), d = RD: 28 x 36。CAN_GRID 4 x 5 格紧挨着直立的整发弹 (与皮带上的弹同一比例, 俯视: 壳口圈 + 被甲 + 弹尖)
    const m = M.olive, F = DISPLAY.stencil.el.from;
    c.fill(m.dk); c.bevel(m);
    for (const z of CAN_GRID.z) for (const x of CAN_GRID.x) roundTop(c, Math.round((x - R.dia / 2 - F[0]) * RD), Math.round((z - R.dia / 2 - F[2]) * RD), true, m.dk);
}
// ---- 计数屏 (方案 C): 箱盖内面的静态底板 (d = 4, 1 贴图像素 = 1 qt, 布局常量全在 counter.mjs 的 DISPLAY)
// 屏底 (带档位色调 + 扫描线) / 满度条的槽 / 框是静态贴图; 数字与满度条由方块实体渲染器画 (MunitionsBenchCounter.rects)。
// 屏窗凹进 DISPLAY.recess (0.25 px): 箱盖 = 四条满厚的框条 + 窗后一块本体 (模型规则: 元素每向至少 0.5 px 厚, 所以不能用
// 0.25 px 的薄框条); 各块北面都从同一张整面设计图里裁出来 (subPaint), 接缝处的图案连续。
function screenWindow(c, W, col) {
    c.rect(W.x, W.y, W.w, W.h, col.bg);
    for (let y = W.y + 2; y < W.y + W.h; y += 2) c.hline(W.x, y, W.w, col.scan);   // 扫描线 (0.5 px 一周期): 待机也看得出是一块亮着的屏
    c.hline(W.x, W.y, W.w, col.rim);                                                // 上沿框条的影子
}
/**
 * 黄漆模板子弹 (横放, 弹头朝右, 高 5): 弹壳 caseLen 列 (左端底缘两角缺口), 1 列模板断笔, 弹头 tipLen 列 (尖头逐行收窄)。
 * 比 Canvas.round 更像子弹: 小尺寸下 round 的弹头几乎不收窄, 看上去是一根黄条。
 */
function stencilBullet(c, x, y, caseLen, tipLen, col) {
    for (let i = 0; i < caseLen; i++) for (let r = 0; r < 5; r++) if (!(i === 1 && (r === 0 || r === 4))) c.px(x + i, y + r, col);
    const tx = x + caseLen + 1;
    const w = [Math.ceil(tipLen * 0.34), Math.ceil(tipLen * 0.67), tipLen, Math.ceil(tipLen * 0.67), Math.ceil(tipLen * 0.34)];
    for (let r = 0; r < 5; r++) c.hline(tx, y + r, w[r], col);
}
/** 从 W x H 的整面设计图里裁出 (x0, y0) 起、与当前面同大的一块。 */
function subPaint(fullFn, W, H, x0, y0) {
    return (c, P, info) => {
        const full = new Canvas(W, H);
        fullFn(full, P, info);
        for (let j = 0; j < c.h; j++) for (let i = 0; i < c.w; i++) c.px(i, j, full.get(x0 + i, y0 + j));
    };
}
/**
 * 凹窗显示元素: 窗后本体 (窗面 → 窗面后 ≥ 0.5 px, 只占窗那一块) + 四条框条 (前沿 → el.to, 满厚)。
 * 框条的 qt 范围 = 窗外的四块 (t 整宽在上, b 整宽在下, l / r 在窗的左右; 左 = 从正面看的左 = 东)。
 */
function recessedDisplay(ctx, D, name, o) {
    const { box } = ctx;
    const F = D.el.from, T = D.el.to, W = D.window, rot = D.el.rot;
    const X = (u) => T[0] - u * QT, Y = (v) => T[1] - v * QT;          // 面上 qt → 世界像素 (北面: u 向 -x, v 向 -y)
    const zF = F[2], zW = F[2] + D.recess, zB = Math.max(T[2], zW + 0.5);
    const [SW, SH] = D.size;
    box(name, [X(W.x + W.w), Y(W.y + W.h), zW], [X(W.x), Y(W.y), zB], o.body, { rot, item: o.item });
    const strips = {
        t: [0, 0, SW, W.y], b: [0, W.y + W.h, SW, SH],
        l: [0, W.y, W.x, W.y + W.h], r: [W.x + W.w, W.y, SW, W.y + W.h],
    };
    for (const [k, [u0, v0, u1, v1]] of Object.entries(strips)) {
        if (u1 <= u0 || v1 <= v0) continue;
        box(`${name}_${k}`, [X(u1), Y(v1), zF], [X(u0), Y(v0), T[2]], o.strip(k, u0, v0, u1 - u0, v1 - v0), { rot, item: o.item });
    }
}
function lidFull(on) {
    return (c, P) => {
        // 箱盖内面 32 x 36 (箱盖 8 px 宽): 2 qt 色带密封框 → 橄榄绿; 上 AMMO, 中 凹窗 (满度条 + 4x7 大字发数), 下 黄漆子弹 + 黄条
        const D = DISPLAY, col = counterColours(P.light, on);
        const m = M.olive, b = P.band, W = D.window;
        c.fill(m.base);
        c.rect(0, 0, c.w, 2, b.base); c.rect(0, c.h - 2, c.w, 2, b.base); c.rect(0, 0, 2, c.h, b.base); c.rect(c.w - 2, 0, 2, c.h, b.base);
        c.bevel({ hi: b.hi, lo: b.dk, base: b.base });
        c.hline(2, 2, c.w - 4, m.lo); c.vline(2, 2, c.h - 4, m.lo);
        c.text(9, 3, 'AMMO', HAZ_Y);
        c.rect(W.x - 1, W.y - 1, W.w + 2, W.h + 2, m.dk);                         // 窗口一圈暗边 (在框条上)
        c.hline(W.x - 1, W.y + W.h, W.w + 2, m.hi);                                // 下沿受光
        screenWindow(c, W, col);
        c.rect(D.bar.x, D.bar.y, D.bar.w, D.bar.h, col.track);
        stencilBullet(c, 7, W.y + W.h + 2, 11, 7, HAZ_Y);
        c.hline(4, W.y + W.h + 8, 24, HAZ_Y);
        c.hline(4, W.y + W.h + 10, 12, mix(HAZ_Y, m.base, 0.4));
    };
}
function bulletTray(c) {
    // 弹头托盘顶面 (7 x 3.5 px), d = RD: 28 x 14。朝玩家倾斜, 屏幕上方 = v 大。两排平躺的弹头 (5 + 4 颗错开), 与皮带上的弹同一比例:
    // 被甲 1 px 宽 x 0.75 长 (4 x 3, 第一行是底缘) + 弹尖 0.5 px 宽 x 0.75 长 (2 x 3, 最后一格收尖), 尖朝屏幕上方
    const m = M.steel, J = JACKET;
    c.fill(M.dark.base); c.bevel(m);
    const bullet = (x, y) => {
        c.hline(x, y, 4, J.dk);
        for (let r = 1; r < 3; r++) { c.px(x, y + r, J.hi); c.px(x + 1, y + r, mix(J.hi, J.base, 0.5)); c.px(x + 2, y + r, J.base); c.px(x + 3, y + r, J.lo); }
        for (let r = 3; r < 5; r++) { c.px(x + 1, y + r, J.hi); c.px(x + 2, y + r, J.base); }
        c.px(x + 1, y + 5, J.hi);
    };
    for (let k = 0; k < 5; k++) bullet(2 + k * 5, 1);
    for (let k = 0; k < 4; k++) bullet(4 + k * 5, 7);
}
function caseTray(c) {
    // 弹壳托盘顶面 (6.5 x 3.5 px), d = RD: 26 x 14, 朝玩家倾斜: 屏幕上方 = v 大。3 只平躺的空弹壳 (与皮带上的壳同一比例 1.5 x 2.5 px = 6 x 10):
    // 底缘 / 抽壳槽 / 壳身 / 肩 / 瓶颈, 壳口 (黑) 朝屏幕上方
    const m = M.steel, b = M.brass;
    c.fill(M.dark.base); c.bevel(m);
    const ramp = [b.hi, mix(b.hi, b.base, 0.5), b.base, b.base, mix(b.base, b.lo, 0.5), b.lo];
    for (let k = 0; k < 3; k++) {
        const x = 2 + k * 8, y = 2;
        c.hline(x, y, 6, b.lo); c.hline(x, y + 1, 6, b.dk);
        for (let r = 2; r < 7; r++) for (let i = 0; i < 6; i++) c.px(x + i, y + r, ramp[i]);
        for (let r = 7; r < 10; r++) for (let i = 1; i < 5; i++) c.px(x + i, y + r, ramp[i]);
        c.hline(x + 1, y + 7, 4, mix(b.base, b.lo, 0.45));
        c.px(x + 2, y + 9, M.dark.dk); c.px(x + 3, y + 9, M.dark.dk);
    }
}
function screenFn(on) {
    return (c, P) => {
        c.fill(C('#1b2027'));
        const bg = on ? P.light.dk : mix(P.light.dk, [16, 21, 26], 0.5);
        c.rect(1, 1, c.w - 2, c.h - 2, bg);
        const fg = on ? P.light.on : P.light.dim;
        c.hline(1, 3, 4, fg); c.hline(1, 4, 4, fg); c.px(5, 3, on ? P.light.hi : fg); c.px(5, 4, on ? P.light.hi : fg); c.px(6, 4, fg);
        c.hline(1, 1, on ? 5 : 2, fg);
        if (on) c.px(7, 3, M.green.hi);
    };
}
function knobRed(c) { c.fill(M.red.base); c.px(0, 0, M.red.hi); c.px(1, 0, M.red.hi); c.hline(0, c.h - 1, c.w, M.red.lo); }
function trimEnd(c, P) { c.plate(P.trim); c.vent(3, 3, c.w - 6, 5, P.trim, 2); c.hline(2, c.h - 2, c.w - 4, P.band.base); }
function worktopTop(c) {
    const m = M.steel;
    c.fill(m.base);
    for (let y = 3; y < c.h - 1; y += 4) c.hline(1, y, c.w - 2, mix(m.base, m.hi, 0.3));
    c.bevel(m);
    for (const [x, y] of [[1, 1], [c.w - 3, 1], [1, c.h - 3], [c.w - 3, c.h - 3]]) c.bolt(x, y, m);
}
function edgeFn(c) { const m = M.steel; c.fill(m.base); c.hline(0, 0, c.w, m.hi); c.hline(0, c.h - 1, c.w, m.lo); }
function backPanel(c) {
    const m = M.frame;
    c.fill(m.base); c.bevel(m);
    for (let x = 7; x < c.w - 3; x += 12) { c.vline(x, 1, c.h - 2, m.lo); c.vline(x + 1, 1, c.h - 2, m.hi); }
    c.vent(18, 2, 12, 6, m, 2);
    c.rect(3, 3, 3, 3, m.dk); c.px(4, 4, M.cyan.base);
}
function cabBack(c) {
    // 柜体背面 cab [8..31.5] x [1..6.5], d = 2: 47 x 11, u = (x - 8) * 2 (背面贴图左 = 西)。电源口 + 黄漆子弹剪影 + AMMO 模板字
    const m = M.frame;
    c.fill(m.base); c.bevel(m);
    c.rect(2, 3, 3, 3, m.dk); c.px(3, 4, M.cyan.base);
    c.vline(7, 1, c.h - 2, m.lo); c.vline(8, 1, c.h - 2, m.hi);
    c.round(11, 3, 14, 5, 'right', { case: M.yellow, tip: M.yellow });
    c.vline(19, 3, 5, m.base);                                         // 模板字断笔
    c.text(28, 3, 'AMMO', HAZ_Y);
    c.hazard(1, c.h - 2, c.w - 2, 1, 4);
}
function shelfFront(c) { c.fill(M.frame.base); c.hline(0, 0, c.w, M.frame.hi); c.hazard(1, 1, c.w - 2, c.h - 2, 4); c.hline(0, c.h - 1, c.w, M.frame.lo); }
function spareSouth(c) {
    // 备用弹药箱背面 (6.5 x 3.5 → 13 x 7, 背面贴图左 = 西): 黄漆子弹 + 一道黄条
    const m = M.olive;
    c.plate(m);
    c.hline(1, 1, c.w - 2, m.lo);
    c.round(2, 3, 7, 3, 'right', { case: M.yellow, tip: M.yellow });
    c.hline(10, 4, 2, HAZ_Y);
}
function spareWest(c) {
    // 备用弹药箱西侧 (3.5 x 3.5 → 7 x 7): 提手
    const m = M.olive;
    c.plate(m);
    c.hline(1, 1, c.w - 2, m.lo);
    c.rect(2, 3, 3, 2, m.lo); c.hline(2, 3, 3, M.dark.base);
}
function spareTop(c) {
    // 备用弹药箱顶 (6.5 x 3.5 → 13 x 7): 箱盖接缝 + 锁扣
    const m = M.olive;
    c.plate(m);
    c.hline(1, 5, c.w - 2, m.lo);
    c.rect(5, 1, 3, 2, M.chrome.lo); c.px(5, 1, M.chrome.hi);
}

// ================================================================ 场景
// frame = null 是待机布局 (运动件停在静止位); 工作态逐帧按上面的帧表摆运动件。
function scene(ctx) {
    const { box, flat, paint, glow, plate, P, active, frame, addOns, forItem } = ctx;
    const f = active ? frame : null;
    const on = active;
    const at = (arr) => (f === null ? 0 : arr[f]);
    const lightFace = (lvl = 15) => (on ? glow(flat(P.light.on), lvl) : flat(P.light.dim));
    const bx = at(BELT);
    const hot = f !== null && DIE_HEAT[f] >= HOT_THRESHOLD;

    // ---------------- 底座 / 柜体 / 台面
    box('plinth', [0.5, 0, 1], [31.5, 1, 15.5], {
        all: flat(M.dark.base),
        north: paint('plinth_n', (c) => { c.fill(M.dark.base); c.hline(0, 0, c.w, M.dark.hi); c.hazard(2, 1, c.w - 4, 1, 4); }, { m: M.dark, d: 2 }),
        up: flat(M.dark.base),
    });
    box('shelf', [0.5, 1, 1.5], [8, 3, 10], {
        all: flat(M.frame.base),
        north: paint('shelf_n', shelfFront, { m: M.frame, d: 2 }),
        west: paint('shelf_w', (c) => { c.fill(M.frameL.base); c.bevel(M.frameL); }, { m: M.frameL, d: 2 }),
        east: null,
    });
    box('cab_back_main', [0.5, 1, 10], [8, 6.5, 15], {
        all: flat(M.frame.base),
        north: null, east: null,
        west: paint('cab_side_w', (c) => { c.fill(M.frameL.base); c.bevel(M.frameL); c.vent(2, 3, c.w - 4, 5, M.frameL, 2); }, { m: M.frameL, d: 2 }),
        south: paint('cab_back_w', backPanel, { m: M.frame, d: 2 }),
    });
    box('cab', [8, 1, 1.5], [31.5, 6.5, 15], {
        all: flat(M.frameL.base),
        north: paint('cab_n', cabFront, { m: M.frame, d: 2 }),
        south: paint('cab_back2', cabBack, { m: M.frame, d: 2 }),
        west: null,
    });
    box('end_e', [31.5, 1, 2], [32, 6.5, 14.5], { all: flat(P.trim.lo), east: paint('trim_end', trimEnd, { m: P.trim, d: 2 }), up: flat(P.trim.hi), west: null });
    box('end_w', [0, 1, 10.5], [0.5, 6.5, 14.5], { all: flat(P.trim.lo), west: paint('trim_end', trimEnd, { m: P.trim, d: 2 }), up: flat(P.trim.hi), east: null });
    box('worktop', [8, 6.5, 0.5], [32, 8, 16], {
        up: paint('wt_top', worktopTop, { m: M.steel, d: 2 }),
        north: paint('wt_edge', edgeFn, { m: M.steel, d: 2 }),
        east: paint('wt_edge', edgeFn, { m: M.steel, d: 2 }),
        west: flat(M.steel.lo), south: flat(M.steel.lo), down: flat(M.frame.dk),
    });
    box('worktop_main', [0.5, 6.5, 10], [8, 8, 16], {
        up: flat(M.steel.base), north: flat(M.steel.lo),
        west: paint('wt_edge', edgeFn, { m: M.steel, d: 2 }), south: flat(M.steel.lo), down: flat(M.frame.dk), east: null,
    });
    box('strip', [8.5, 7, 0], [31.5, 7.5, 0.5], { all: lightFace(), south: null });
    // 弹药箱底下货架前沿的一截灯带 (夜里箱子不再整块黑掉, 灯带横贯全宽)
    box('can_strip', [0.5, 2.5, 1], [8, 3, 1.5], { all: lightFace(), south: null }, { item: false });
    box('nameplate', [17, 2, 1], [23.5, 5.5, 1.5], {
        all: flat(P.band.lo),
        north: on ? glow(paint('nameplate2_on', nameplateFn(true), { m: M.dark, d: 4 }), 12) : paint('nameplate2', nameplateFn(false), { m: M.dark, d: 4 }),
        south: null,
    });

    // ---------------- 皮带输送线 (x 8..31, 皮带面 y 9, z 5.5..9.5)
    box('conveyor', [8, 8, 5], [31, 9, 10], {
        all: flat(M.frame.lo),
        up: paint('belt_top', beltTop, { m: M.rubber, d: 2 }),
        north: paint('conv_n2', convFront, { m: P.trim, d: 2 }),
        east: flat(M.frameL.base), west: flat(M.frameL.base), south: flat(M.frame.base),
    });
    box('rail_f', [8, 9, 5], [31, 9.5, 5.5], { all: flat(P.band.lo), north: flat(P.band.base), up: flat(P.band.hi), down: null });
    box('rail_b', [8, 9, 9.5], [31, 10.5, 10], {
        all: flat(M.steel.lo), up: flat(M.steel.hi), down: null,
        north: on ? glow(paint('backlight2_on', backlightFn(true), { m: P.light, d: 2 }), 11) : paint('backlight2', backlightFn(false), { m: P.light, d: 2 }),
    });

    // ---------------- 皮带上的弹 (运动件 'rounds': 4 个弹位一起步进; 装药后壳口换成药面, 压弹头后那一发多出弹头)
    roundAt(ctx, 'r_in', SLOT.in + bx, BELT_Y, RZ, { tip: false, mouth: 'empty', move: 'rounds', item: false });
    roundAt(ctx, 'r_prime', SLOT.prime + bx, BELT_Y, RZ, { tip: false, mouth: 'empty', move: 'rounds', item: false });
    roundAt(ctx, 'r_powder', SLOT.powder + bx, BELT_Y, RZ, { tip: false, mouth: f !== null && POWDER_CHARGED[f] ? 'charged' : 'empty', move: 'rounds', item: false });
    roundAt(ctx, 'r_seat', SLOT.seat + bx, BELT_Y, RZ, { tip: f !== null && !!SEATED[f], mouth: 'charged', move: 'rounds', item: false });
    // 皮带末端的整发弹 = 出弹 (运动件 'drop': 随皮带走到末端, 再掉进箱里)
    roundAt(ctx, 'r_out', SLOT.inspect + bx, BELT_Y + at(DROP_Y), RZ, { move: 'drop' });

    // ---------------- extension: 弹壳料斗 + 落壳管 (入口弹位 x 26.5), 顶面 16
    box('hopper', [24.5, 8, 10.5], [31.5, 16, 15.5], {
        all: plate(M.panel),
        north: forItem ? plate(M.panel) : paint('hopper_n2', hopperFront, { m: M.panel, d: 2 }),
        east: paint('hopper_side', hopperSide, { m: M.panel, d: 2 }),
        west: paint('hopper_side', hopperSide, { m: M.panel, d: 2 }),
        up: forItem ? plate(M.brass) : paint('hopper_top3', hopperTop, { m: M.brass, d: RD }),
    });
    box('hopper_light', [25, 15, 10], [31, 15.5, 10.5], { all: lightFace(), south: null });
    box('case_chute', [25.75, 15.5, 8.25], [27.25, 16, 10.5], { all: plate(M.panel), up: flat(M.panelB.base), down: flat(M.panel.lo), south: null }, { item: false });
    // 玻璃落壳管 (与壳同径 1.5 px): 下口正好落在入口位那只壳的壳口上, 循环接缝时新壳像是从管里落出来的
    box('case_tube', [25.75, MOUTH_Y, 6.75], [27.25, 16, 8.25], { all: paint('case_tube', caseTube, { m: M.glass, d: 2 }), up: flat(M.chrome.base), down: flat(M.chrome.lo), south: null }, { item: false });

    // ---------------- extension: 装填塔 (底火 x 22.5 + 装药 x 18.5 合成一座), 一根横梁同时带底火冲杆和装药管
    box('turret', [17.5, 8, 10.5], [24, 16, 14], {
        all: plate(M.frame),
        north: forItem ? plate(M.frame) : paint('turret_n', turretFront, { m: M.frame, d: 2 }),
        east: paint('turret_side', turretSide, { m: M.frameL, d: 2 }),
        west: paint('turret_side', turretSide, { m: M.frameL, d: 2 }),
        up: paint('turret_top', turretTop, { m: M.frame, d: 2 }),
        down: null,
    });
    box('turret_cap', [18, 16, 11], [21, 16.5, 13.5], { all: flat(M.brass.lo), north: flat(M.brass.base), up: paint('cap_top', capTop, { m: M.brass, d: 2 }), down: null });
    box('charge_arm', [17.75, 15, 6.25], [23.25, 16, 10.5], {
        all: plate(M.frame), east: flat(M.frameL.base), west: flat(M.frameL.base), up: plate(M.frame), down: flat(M.frame.lo), south: null,
    }, { item: false });
    // 横梁前沿通长灯条
    box('arm_light', [18, 15.5, 5.75], [23, 16, 6.25], { all: lightFace(13), south: null, down: flat(M.frame.lo) }, { item: false });
    box('prime_rod', [22, ROD_REST_BOTTOM + at(PRIME), 7], [23, ROD_TOP + at(PRIME), 8], { all: paint('rod2', rodSide, { m: M.chrome, d: 2 }), up: flat(M.chrome.base), down: flat(M.steel.hi) }, { move: 'prime', item: false });
    box('powder_tube', [18, ROD_REST_BOTTOM + at(POWDER), 7], [19, ROD_TOP + at(POWDER), 8], {
        all: paint('powder_tube', (c) => { c.vline(0, 0, c.h, M.dark.hi); c.vline(1, 0, c.h, M.dark.base); c.hline(0, c.h - 2, c.w, M.brass.base); }, { m: M.dark, d: 2 }),
        up: flat(M.dark.hi), down: flat(GPOWDER.base),
    }, { move: 'powder', item: false });

    // ---------------- main: 四柱压弹头机 (x 11.5..17.5, 全机唯一高点: 缸顶 22.5)
    for (const [i, [x0, x1]] of PRESS.postX.entries()) for (const [j, [z0, z1]] of PRESS.postZ.entries()) {
        box(`post_${j ? 'b' : 'f'}${i ? 'e' : 'w'}`, [x0, 8, z0], [x1, PRESS.postTop, z1], { all: paint('post', postSide, { m: M.chrome, d: 2 }), up: null, down: null });
    }
    box('crown', PRESS.crown[0], PRESS.crown[1], {
        all: plate(M.panel),
        north: paint('crown_n', crownFront, { m: M.panel, d: 2 }),
        east: paint('trim_crown', trimSide, { m: P.trim, d: 2 }),
        west: paint('trim_crown', trimSide, { m: P.trim, d: 2 }),
        up: paint('crown_top', crownTop, { m: M.panelB, d: 2 }),
        down: flat(M.frame.dk),
    });
    box('crown_light', [12, 19, 3.25], [17, 19.5, 3.75], { all: lightFace(14), south: null });
    box('flange', [13, 20.5, 5.5], [16, 21, 9.5], { all: flat(M.brass.lo), north: flat(M.brass.base), up: paint('flange_top', flangeTop, { m: M.brass, d: 2 }), down: null });
    box('cyl', [13.5, 21, 6], [15.5, 22.5, 9], { all: paint('cyl_side', cylSide, { m: M.frame, d: 2 }), up: plate(M.frame), down: null });
    // 绿色 OK 灯 (压机横梁顶的前角); 工作时常亮
    box('pass_lamp', [12, 20.5, 4.25], [13, 21.25, 5.25], { all: on ? glow(flat(M.green.hi), 12) : flat(OFF.green), down: null }, { item: false });
    // 冲头 (运动件 'ram'): 压模 + 连杆。静止时压模顶贴横梁底, 连杆 (长 = 行程 3.75) 整根藏在横梁 / 法兰 / 液压缸里
    // 压模要有顶面 (实装评审): 方案里静止时顶面贴着横梁底面、省掉了, 但冲头下行时 (f1-f2) 顶面露在横梁下的缝里;
    // 游戏里运动件用不剔除背面的渲染类型, 没有顶面就会从缝里看进压模、看到内壁。静止时它被横梁整个盖住, 看不到。
    const rm = at(RAM);
    box('ram_die', [DIE.from[0], DIE.from[1] + rm, DIE.from[2]], [DIE.to[0], DIE.to[1] + rm, DIE.to[2]], {
        all: hot ? glow(paint('die_hot', dieFn(true), { m: M.hot, d: 2 }), HOT_LIGHT) : paint('die', dieFn(false), { m: M.chrome, d: 2 }),
        up: hot ? glow(flat(M.chrome.lo), HOT_LIGHT) : flat(M.chrome.lo), down: flat(M.steel.lo),
    }, { move: 'ram', item: false });
    box('ram_rod', [14, DIE.to[1] + rm, 7], [15, DIE.to[1] - RAM_STROKE + rm, 8], { all: paint('rod2', rodSide, { m: M.chrome, d: 2 }), up: null, down: null }, { move: 'ram', item: false });
    // 冲头夹着的下一颗弹头 (运动件 'ram_bullet' = 随冲头平移 + 显隐): f3-f4 冲头刚回位, 不夹弹头。
    // 静止时弹尖顶贴着压模底, 悬在壳口上方 -RAM_STROKE (3.75 px); 要有底面 (实装评审: 台子放在高处、眼睛低于它时会看到空心的弹头)。
    if (f === null || RAM_BULLET[f]) bulletAt(ctx, 'ram_bullet', SLOT.seat, RAM_REST + rm, RZ, { move: 'ram_bullet', item: false }, flat(JACKET.lo));

    // ---------------- main: 弹药箱 (敞口, 满箱整发弹) + 掀开靠后的箱盖 + 箱后备用弹药箱
    // 箱身正面 = 计数屏的口径模板字面 (DISPLAY.stencil.el): 空标签, 口径由方块实体渲染器写
    box('can', [...DISPLAY.stencil.el.from], [...DISPLAY.stencil.el.to], {
        all: plate(M.olive),
        north: paint('can_nC', canFront, { m: M.olive, d: 4 }),
        west: paint('can_side', canSide, { m: M.olive, d: 2 }),
        up: paint('can_top3', canTop, { m: M.olive, d: RD }),
        east: flat(M.olive.lo), south: flat(M.olive.lo),
    });
    box('can_latch', [3.5, 7, 0.5], [5.5, 8.5, 1], { all: flat(M.chrome.lo), north: flat(M.chrome.base), up: flat(M.chrome.hi), south: null }, { item: false });
    {
        // 掀开的箱盖 = 计数屏 (DISPLAY.el, 8 px 宽、0.75 px 厚, 后仰 22.5°): 窗后本体 (凹窗, 工作时窗面自发光) + 四条框条
        // (AMMO / 黄漆子弹在框条上)。窗里的满度条与发数由方块实体渲染器画在窗面外 LIFT。
        const D = DISPLAY, [SW, SH] = D.size, W = D.window;
        const LIP = { t: 'down', b: 'up', l: 'west', r: 'east' };
        recessedDisplay(ctx, D, 'can_lid', {
            body: {
                south: flat(M.olive.base), east: null, west: null, up: null, down: null,
                north: on ? glow(paint('lidC2_w_on', subPaint(lidFull(true), SW, SH, W.x, W.y), { m: M.olive, d: 4 }), 12)
                    : paint('lidC2_w', subPaint(lidFull(false), SW, SH, W.x, W.y), { m: M.olive, d: 4 }),
            },
            strip: (k, x0, y0) => ({
                all: flat(M.olive.lo), south: flat(M.olive.base), ...(k === 't' ? { up: flat(M.olive.hi) } : {}),
                north: paint(`lidC2_${k}`, subPaint(lidFull(false), SW, SH, x0, y0), { m: M.olive, d: 4 }),
                [LIP[k]]: flat(k === 'b' ? M.olive.lo : M.olive.dk),
            }),
        });
    }
    // 箱顶几格画出来的整发弹换成立体的弹头 (被甲 + 弹尖, 坐在箱顶上, 与皮带上的弹同一尺寸; 弹尖 0.5 px 正好是模型规则的最薄)
    CAN_3D.forEach(([i, j], k) => bulletAt(ctx, 'can_b' + k, CAN_GRID.x[i], CAN_TOP, CAN_GRID.z[j], { item: false }));
    // 备用弹药箱 (合着盖), 箱盖正好靠在它上沿
    box('spare_can', [1, 8, 12], [7.5, 11.5, 15.5], {
        all: plate(M.olive), east: flat(M.olive.lo),
        south: paint('spare_s', spareSouth, { m: M.olive, d: 2 }),
        west: paint('spare_w', spareWest, { m: M.olive, d: 2 }),
        up: paint('spare_top', spareTop, { m: M.olive, d: 2 }),
        down: null,
    }, { item: false });

    // ---------------- 台面前沿 (从玩家左到右 = 配方顺序): 弹壳托盘 (extension) · 控制台 (居中) · 弹头托盘 (main) · 弹药箱
    box('ctray_stand', [25, 8, 2.5], [30.5, 9, 4], { all: flat(M.frame.lo), up: null, down: null }, { item: false });
    box('ctray', [24.5, 8, 1], [31, 8.5, 4.5], {
        all: flat(M.steel.lo), north: flat(M.steel.base),
        up: paint('ctray_top3', caseTray, { m: M.steel, d: RD }),
    }, { rot: { origin: [27.75, 8, 1], axis: 'x', angle: -22.5 } });
    box('btray_stand', [9.5, 8, 2.5], [15.5, 9, 4], { all: flat(M.frame.lo), up: null, down: null }, { item: false });
    box('btray', [9, 8, 1], [16, 8.5, 4.5], {
        all: flat(M.steel.lo), north: flat(M.steel.base),
        up: paint('btray_top3', bulletTray, { m: M.steel, d: RD }),
    }, { rot: { origin: [12.5, 8, 1], axis: 'x', angle: -22.5 } });
    box('console', [17, 8, 1], [23.5, 9, 4.5], {
        all: plate(M.frame),
        north: paint('console_n', (c) => { c.fill(M.frame.base); c.hline(0, 0, c.w, M.frame.hi); c.hline(0, c.h - 1, c.w, M.frame.lo); c.hazard(c.w - 5, 0, 4, 2, 4); }, { m: M.frame, d: 2 }),
        up: flat(M.frame.base),
    });
    box('screen', [17.5, 9, 1.5], [22, 9.5, 4.5], {
        all: flat(M.frame.lo), north: flat(M.frame.base),
        up: glow(paint(on ? 'screen_on' : 'screen', screenFn(on), { m: M.dark, d: 2 }), on ? 12 : 6),
    }, { rot: { origin: [19.75, 9, 1.5], axis: 'x', angle: -22.5 } });
    box('estop', [22.25, 9, 2], [23.25, 10, 3], { all: flat(M.red.lo), north: flat(M.red.base), up: paint('knob_red', knobRed, { m: M.red, d: 2 }), down: null }, { item: false });
    box('run_led', [17.75, 8.25, 0.5], [18.75, 8.75, 1], { all: on ? glow(flat(M.green.hi)) : flat(OFF.green), south: null }, { item: false });

    // ---------------- 逐档累加的小件
    addOns({
        medium: () => box('hopper_band', [24.25, 15.25, 10.25], [31.75, 15.75, 15.75], { all: flat(P.band.base), up: flat(P.band.hi), down: flat(P.band.lo) }),
        high: () => {
            box('beacon_base', [29.25, 16, 13.25], [30.75, 16.5, 14.75], { all: flat(M.dark.base), up: flat(M.dark.hi), down: null });
            box('beacon', [29.5, 16.5, 13.5], [30.5, 17.5, 14.5], { all: lightFace(), up: on ? glow(flat(P.light.hi)) : flat(P.light.mid), down: null });
        },
        superior: () => {
            box('hopper_post_w', [24.5, 9, 10], [25, 15, 10.5], { all: lightFace(13), south: null, down: null });
            box('hopper_post_e', [31, 9, 10], [31.5, 15, 10.5], { all: lightFace(13), south: null, down: null });
        },
        // 踢脚条用 band (超凡是红, 闪耀是深色底座上的一道金线)
        transcendent: () => box('kick_band', [8, 1, 1], [31.5, 1.5, 1.5], { all: flat(P.band.lo), north: flat(P.band.base), up: flat(P.band.hi), south: null }),
        // 闪耀: 压机缸顶的金座 + 发光宝石 (剪影和夜里都看得见)
        radiant: () => {
            box('gem_mount', [13.75, 22.5, 6.75], [15.25, 23, 8.25], { all: flat(P.band.lo), north: flat(P.band.base), up: flat(P.band.hi), down: null });
            box('gem', [14, 23, 7], [15, 24, 8], { all: on ? glow(flat(P.light.on), 15) : flat(P.light.mid), up: on ? glow(flat(P.light.hi), 15) : flat(P.light.on), down: null });
        },
    });
}

// ================================================================ 物品模型主角: 一发细高的整发弹立在档位色底板上, 高出机器
function hero(ctx) {
    // 物品坐标 (0..16)。整台缩到 0.46 后正面在 z ≈ 4.3; 大弹立在台前偏左 (玩家看是 extension 一侧, 不挡右边的压机和弹药箱)
    const { box, paint, flat, plate, P } = ctx;
    box('hero_plate', [10.5, 0.5, 0.5], [15.5, 1, 4.5], { all: flat(P.band.lo), north: flat(P.band.base), up: plate(P.band) });
    box('hero_case', [11.5, 1, 1], [14.5, 8, 4], { all: paint('hero_case2', (c) => {
        const m = M.brass; c.fill(m.base); c.vline(0, 0, c.h, m.hi); c.vline(1, 0, c.h, m.hi); c.vline(c.w - 1, 0, c.h, m.lo); c.vline(c.w - 2, 0, c.h, mix(m.base, m.lo, 0.5));
        c.hline(0, 0, c.w, m.hi); c.hline(0, 1, c.w, mix(m.base, m.lo, 0.35));
        c.hline(0, c.h - 3, c.w, m.dk); c.hline(0, c.h - 2, c.w, m.lo); c.hline(0, c.h - 1, c.w, m.base);
    }, { m: M.brass, d: 2 }), up: flat(M.brass.hi), down: null });
    box('hero_tip', [11.75, 8, 1.25], [14.25, 10.5, 3.75], { all: paint('hero_tip2', (c) => {
        const m = JACKET; c.fill(m.base); c.vline(0, 0, c.h, m.hi); c.vline(1, 0, c.h, m.hi); c.vline(c.w - 1, 0, c.h, m.lo); c.hline(0, c.h - 1, c.w, m.lo);
    }, { m: JACKET, d: 2 }), up: flat(JACKET.hi), down: null });
    box('hero_nose', [12.25, 10.5, 1.75], [13.75, 12.5, 3.25], { all: paint('hero_nose2', (c) => {
        const m = JACKET; c.fill(m.base); c.vline(0, 0, c.h, m.hi); c.vline(c.w - 1, 0, c.h, m.lo); c.hline(0, 0, c.w, m.hi);
    }, { m: JACKET, d: 2 }), up: flat(JACKET.hi), down: null });
}
const ITEM = {
    scale: 0.46,
    gui: { rotation: [26, 200, 0], translation: [0, 2, 0], scale: [0.9, 0.9, 0.9] },
};

// ================================================================ 运动件 (BER) 与关键帧程序
// 每个件取某个场景 (待机或某一帧) 里的几个运动件元素, 平移回待机布局; drive = 这个件跟哪一路姿态走 (x / y) 与显隐规则 (见 ber.mjs SHOW_EXPR)。
// 生成器逐帧核对: 按关键帧表摆好的件 == 那一帧场景里的运动件 (位置、尺寸、每个面的贴图完全相同)。
const PART_DEFS = [
    { name: 'round_in', doc: '入口位的空壳 (料斗落壳管正下方), 随皮带走。', src: 'idle', els: ['r_in_case'], drive: { x: 'beltX' } },
    { name: 'round_prime', doc: '底火位的空壳, 随皮带走。', src: 'idle', els: ['r_prime_case'], drive: { x: 'beltX' } },
    { name: 'round_powder', doc: '装药位的壳, 壳口还空着 (Pose.powderCharged 为假时画)。', src: 'idle', els: ['r_powder_case'], drive: { x: 'beltX', show: '!powderCharged' } },
    { name: 'round_powder_charged', doc: '装药位的壳, 壳口已是灰色发射药 (Pose.powderCharged 为真时画)。', src: 2, els: ['r_powder_case'], drive: { x: 'beltX', show: 'powderCharged' } },
    { name: 'round_seat', doc: '压弹头位的装药壳, 还没压弹头 (Pose.seated 为假时画)。', src: 'idle', els: ['r_seat_case'], drive: { x: 'beltX', show: '!seated' } },
    { name: 'round_seat_tipped', doc: '压弹头位那发, 已压上弹头 (Pose.seated 为真时画)。', src: 3, els: ['r_seat_case', 'r_seat_tip', 'r_seat_nose'], drive: { x: 'beltX', show: 'seated' } },
    { name: 'drop', doc: '皮带末端的整发弹 (出弹): 随皮带走到末端, 再沿 dropY 掉进弹药箱。', src: 'idle', els: ['r_out_case', 'r_out_tip', 'r_out_nose'], drive: { x: 'beltX', y: 'dropY' } },
    { name: 'ram_rod', doc: '压弹头冲头的连杆 (静止时整根藏在压机横梁里)。', src: 'idle', els: ['ram_rod'], drive: { y: 'ramY' } },
    { name: 'ram_die', doc: '压模, 冷 (镀铬)。', src: 'idle', els: ['ram_die'], drive: { y: 'ramY', show: 'cold' } },
    { name: 'ram_die_hot', doc: '压模, 热 (自发光): 冲头到底前后代替冷压模。', src: 2, els: ['ram_die'], drive: { y: 'ramY', show: 'hot' }, fullBright: true },
    { name: 'ram_bullet', doc: '冲头夹着的下一颗弹头 (被甲 + 弹尖), 刚压完回位的两帧不夹。', src: 'idle', els: ['ram_bullet_tip', 'ram_bullet_nose'], drive: { y: 'ramY', show: 'ramBulletVisible' } },
    { name: 'prime_rod', doc: '底火冲杆 (装填塔横梁东端, 弹位 x 22.5)。', src: 'idle', els: ['prime_rod'], drive: { y: 'primeY' } },
    { name: 'powder_tube', doc: '装药管 (装填塔横梁西端, 弹位 x 18.5)。', src: 'idle', els: ['powder_tube'], drive: { y: 'powderY' } },
];
// 循环接缝 (t = 40 → 0) 时画面只允许这些变化: 入口位落下一只新壳; 箱里那发 (已被箱体完全挡住) 消失。
const SEAM_APPEARS = new Set(['round_in']);

/** 关键帧表: f0..f7 各一行, 再加一行接缝 (tick 40)。接缝行 = 下一轮 f0 的机器姿态, 但皮带上的弹仍按这一轮编号。 */
function programRows() {
    const rows = [];
    for (let f = 0; f < CYCLE; f++) {
        rows.push({
            tick: f * TICKS_PER_FRAME, beltX: BELT[f], primeY: PRIME[f], powderY: POWDER[f], ramY: RAM[f], dropY: DROP_Y[f], dieHeat: DIE_HEAT[f],
            ramBulletVisible: !!RAM_BULLET[f], powderCharged: !!POWDER_CHARGED[f], seated: !!SEATED[f], label: FRAME_LABELS[f],
        });
    }
    const last = CYCLE - 1;
    rows.push({
        tick: CYCLE * TICKS_PER_FRAME,
        beltX: BELT[0] - PITCH, dropY: DROP_Y[last], powderCharged: !!POWDER_CHARGED[last], seated: !!SEATED[last],
        primeY: PRIME[0], powderY: POWDER[0], ramY: RAM[0], dieHeat: DIE_HEAT[0], ramBulletVisible: !!RAM_BULLET[0],
        label: '接缝 = 下一轮 f0 的机器姿态; 皮带上的弹仍按这一轮编号 (beltX = f0 - 节距, 箱里那发留在箱里)',
    });
    return rows;
}
const IDLE_POSE = { beltX: 0, primeY: 0, powderY: 0, ramY: 0, dropY: 0, dieHeat: 0, ramBulletVisible: true, powderCharged: false, seated: false };

/** 一个件在某个姿态下的位移与显隐 (生成器侧, 与 Java applyPose / ber.mjs applyPoseMirror 同一规则)。 */
function drivePart(def, pose) {
    const hot = pose.dieHeat >= HOT_THRESHOLD;
    const show = def.drive.show || 'always';
    const visible = show === 'always' ? true : show === 'hot' ? hot : show === 'cold' ? !hot : show.startsWith('!') ? !pose[show.slice(1)] : !!pose[show];
    return { dx: def.drive.x ? pose[def.drive.x] : 0, dy: def.drive.y ? pose[def.drive.y] : 0, visible };
}

// ================================================================ 轮廓箱 (碰撞是每格一整块实心柱, 由 MunitionsBenchBlock 按这些盒子的最高点取)
// 每组取组内元素在该格里那一段的包围盒 (旋转件取旋转后的角点), 向外取整到 0.25 px; 各档共用 (加件都在组的范围里或是饰件)。
// 饰件 (托盘、控制台、箱里冒出的弹头、各档加件的小灯/宝石) 不进轮廓箱; 新加的静态元素必须归进某一组或饰件, 否则校验失败。
// 运动件另有一组轮廓箱 (partShapeBoxes), 只进轮廓。
const SHAPE_GROUPS = [
    ['body', /^(plinth|shelf|cab_back_main|cab|end_e|end_w|worktop|worktop_main|strip|can_strip|nameplate|kick_band)$/, '底座 + 柜体 + 台面'],
    ['can', /^(can|can_latch)$/, '弹药箱'],
    ['lid', /^can_lid(_[tblr])?$/, '掀开的箱盖 (计数屏)'],
    ['spare', /^spare_can$/, '备用弹药箱'],
    ['belt', /^(conveyor|rail_f|rail_b)$/, '皮带 + 护栏'],
    ['press_posts_front', /^post_f(w|e)$/, '压机前立柱'],
    ['press_posts_back', /^post_b(w|e)$/, '压机后立柱'],
    ['press_head', /^(crown|crown_light|flange|cyl|pass_lamp)$/, '压机横梁 + 法兰 + 液压缸'],
    ['hopper', /^(hopper|hopper_light)$/, '弹壳料斗'],
    ['chute', /^(case_chute|case_tube)$/, '落壳管'],
    ['turret', /^(turret|turret_cap)$/, '装填塔'],
    ['arm', /^(charge_arm|arm_light)$/, '装填塔横梁'],
];
const ORNAMENTS = /^(can_b\d_(tip|nose)|btray|btray_stand|ctray|ctray_stand|console|screen|estop|run_led|hopper_band|hopper_post_[we]|beacon_base|beacon|gem_mount|gem)$/;

function shapeBoxes(tierScenes, errors) {
    const q = (v, up) => (up ? Math.ceil(r4(v) * 4 - 1e-6) / 4 : Math.floor(r4(v) * 4 + 1e-6) / 4);
    const out = { main: [], extension: [] };
    const seen = new Set();
    for (const E of tierScenes) for (const e of E) {
        if (e.move || seen.has(e.name)) continue;
        seen.add(e.name);
        if (!SHAPE_GROUPS.some(([, re]) => re.test(e.name)) && !ORNAMENTS.test(e.name)) errors.push(`collision shape: static element ${e.name} is in no shape group and is not an ornament (add it to SHAPE_GROUPS or ORNAMENTS)`);
    }
    for (const [cell, [ox, oz]] of Object.entries(CELLS)) {
        for (const [group, re, label] of SHAPE_GROUPS) {
            const lo = [Infinity, Infinity, Infinity], hi = [-Infinity, -Infinity, -Infinity];
            for (const E of tierScenes) for (const e of E) {
                if (e.move || !re.test(e.name)) continue;
                const b = worldBox(e);
                const x0 = Math.max(b.lo[0], ox), x1 = Math.min(b.hi[0], ox + 16);
                if (x1 - x0 <= 1e-6) continue;
                const cl = [x0 - ox, b.lo[1], Math.max(b.lo[2], oz) - oz], ch = [x1 - ox, b.hi[1], Math.min(b.hi[2], oz + 16) - oz];
                for (let a = 0; a < 3; a++) { lo[a] = Math.min(lo[a], cl[a]); hi[a] = Math.max(hi[a], ch[a]); }
            }
            if (!Number.isFinite(lo[0])) continue;
            const box = [q(lo[0], false), q(lo[1], false), q(lo[2], false), q(hi[0], true), q(hi[1], true), q(hi[2], true)];
            if (box[0] < 0 || box[2] < 0 || box[1] < 0 || box[3] > 16 || box[5] > 16 || box[4] > 32) errors.push(`collision shape ${cell}/${group} leaves the cell: ${box}`);
            out[cell].push({ group, label, box });
        }
    }
    return out;
}

/**
 * 运动件的轮廓箱 (只进轮廓, 不进碰撞): 每个件在整个循环与待机里扫过的包围盒, 切到各格、向外取整到 0.25 px;
 * 被同格另一个盒子整个包住的并进那个盒子。关键帧之间是线性插值, 所以各关键帧 (含接缝行) 的包围盒就是整段的包围盒。
 * sweeps: Map<件名, {lo, hi}> (整台像素, 朝北)。
 */
function partShapeBoxes(sweeps) {
    const q = (v, up) => (up ? Math.ceil(r4(v) * 4 - 1e-6) / 4 : Math.floor(r4(v) * 4 + 1e-6) / 4);
    const out = { main: [], extension: [] };
    for (const [cell, [ox, oz]] of Object.entries(CELLS)) {
        const list = [];
        for (const [name, s] of sweeps) {
            const x0 = Math.max(s.lo[0], ox), x1 = Math.min(s.hi[0], ox + 16);
            if (x1 - x0 <= 1e-6) continue;
            list.push({ names: [name], box: [q(x0 - ox, false), q(s.lo[1], false), q(Math.max(s.lo[2], oz) - oz, false), q(x1 - ox, true), q(s.hi[1], true), q(Math.min(s.hi[2], oz + 16) - oz, true)] });
        }
        const inside = (a, b) => [0, 1, 2].every((i) => a.box[i] >= b.box[i] - 1e-6 && a.box[i + 3] <= b.box[i + 3] + 1e-6);
        for (const a of list) {
            if (a.gone) continue;
            for (const b of list) if (b !== a && !b.gone && inside(b, a)) { a.names.push(...b.names); b.gone = true; }
        }
        out[cell] = list.filter((x) => !x.gone).map((x) => ({ group: x.names.join(' + '), label: '运动件扫过的范围', box: x.box }));
    }
    return out;
}

// ================================================================ 接触核对 (弹的尺寸或帧表一改, 这里保证杆 / 冲头 / 出弹仍与弹对得上)
const nearly = (a, b) => Math.abs(a - b) < 1e-6;
const elOf = (E, n) => E.find((e) => e.name === n);
/** inner 的 x / z 截面在 outer 的截面里。 */
const insideXZ = (inner, outer) => [0, 2].every((a) => inner.from[a] >= outer.from[a] - 1e-6 && inner.to[a] <= outer.to[a] + 1e-6);
const boxesOverlap = (a, b) => [0, 1, 2].every((k) => Math.min(a.hi[k], b.hi[k]) - Math.max(a.lo[k], b.lo[k]) > 1e-6);

/**
 * 流水线的设计尺寸 (写进 MunitionsBenchGeometry, GameTest 用它们核对程序与轮廓), 全部从待机场景的元素量出来, 不另写一份数字:
 * 皮带面、各弹位 x、弹位中心 z、壳高、弹头高、静止位时冲头夹着的弹头底 / 底火冲杆底 / 装药管底。
 */
function lineDesign(idle, errors) {
    const get = (n) => { const e = elOf(idle, n); if (!e) throw new Error('lineDesign: ' + n + ' not in the idle scene'); return e; };
    const cases = ['r_in_case', 'r_prime_case', 'r_powder_case', 'r_seat_case', 'r_out_case'].map(get);
    const cx = (e) => (e.from[0] + e.to[0]) / 2, cz = (e) => (e.from[2] + e.to[2]) / 2;
    const d = {
        beltTop: cases[0].from[1], slotX: cases.map(cx), slotZ: cz(cases[0]),
        caseH: cases[0].to[1] - cases[0].from[1], bulletH: get('r_out_nose').to[1] - get('r_out_tip').from[1],
        ramBulletRestBottom: get('ram_bullet_tip').from[1], primeRodRestBottom: get('prime_rod').from[1], powderTubeRestBottom: get('powder_tube').from[1],
    };
    if (cases.some((e) => !nearly(e.from[1], d.beltTop) || !nearly(cz(e), d.slotZ))) errors.push('line design: the belt rounds do not share one belt top / slot z');
    if (!d.slotX.every((x, i) => i === 0 || nearly(d.slotX[i - 1] - x, PITCH))) errors.push(`line design: slot x ${d.slotX} are not one pitch (${PITCH}) apart`);
    if (!nearly(d.beltTop + d.caseH, MOUTH_Y) || !nearly(d.caseH, R.caseH) || !nearly(d.bulletH, BULLET_H)) errors.push('line design: case / bullet heights do not match R');
    return d;
}

/**
 * 运动件与静态件允许的穿插 (其余一律报错): [运动件, 静态件, 说明, 限深 (x/y/z 方向穿进去的深度上限, 不给 = 不限)]。
 * 杆 / 连杆本来就在横梁、法兰、液压缸里滑动, 出弹沉进弹药箱 (没入深度另有最后一帧的核对);
 * 出弹从皮带末端翻下去的头两 tick (f4 → f5, 皮带与出弹都按线性插值) 壳底的东角会擦进皮带末端的角: 只许弹的中心已过皮带末端
 * (x 向深度 < 半个壳径) 且下沉不到 0.5 px。
 */
const MOVING_VS_STATIC = [
    [/^(prime_rod|powder_tube)$/, /^charge_arm$/, '杆在装填塔横梁里滑动'],
    [/^ram_rod$/, /^(crown|flange|cyl)$/, '冲头连杆在压机横梁 / 法兰 / 液压缸里滑动'],
    [/^drop$/, /^can$/, '出弹沉进弹药箱'],
    [/^drop$/, /^conveyor$/, '出弹从皮带末端翻下去时擦过末端的角', [R.dia / 2, 0.5, Infinity]],
];

/**
 * 场景逐帧 (与待机) 核对:
 *   皮带上的壳站在皮带面上, 出弹在 皮带面 + dropY; 两根杆顶一直在装填塔横梁里、杆底从不低于壳口; 连杆顶一直在压机横梁里、连杆底 = 压模顶;
 *   冲头夹着的弹头挂在压模底; 底火冲杆 (f0) / 装药管 (f1) 正好顶到壳口、落在壳的截面里; 冲压时刻夹着的弹头正好在装药壳的壳口上,
 *   与下一帧那发压好的弹头 (平移回同一皮带位置) 完全相同; 落壳管下口正好在入口位那只壳的壳口上; 最后一帧出弹整发没入弹药箱;
 *   出弹掉进箱子的一路 (相邻两帧的包围盒) 不碰箱里立着的弹头。
 * 再按写出的 Java (解析回来 + JS 镜像) 每 0.25 tick 摆一遍运动件 (连同待机): 两两只许贴面, 不许穿插;
 * 与 statics (各档待机 / 工作的静态件, 旋转件取包围盒) 也只许贴面, 除了 MOVING_VS_STATIC 列出的几对 (且不超过限深)。
 */
function contactChecks(idle, frames, partsJava, programJava, statics, errors) {
    const arm = elOf(idle, 'charge_arm'), crown = elOf(idle, 'crown'), can = elOf(idle, 'can');
    const beltCases = ['r_in_case', 'r_prime_case', 'r_powder_case', 'r_seat_case'];
    [...frames.map((E, f) => [E, `f${f}`, f]), [idle, 'idle', null]].forEach(([E, label, f]) => {
        for (const n of beltCases) { const e = elOf(E, n); if (!e || !nearly(e.from[1], BELT_Y)) errors.push(`contact ${label}: ${n} does not stand on the belt`); }
        const out = elOf(E, 'r_out_case');
        if (!out || !nearly(out.from[1], BELT_Y + (f === null ? 0 : DROP_Y[f]))) errors.push(`contact ${label}: r_out_case is not at the belt top + dropY`);
        for (const n of ['prime_rod', 'powder_tube']) {
            const rod = elOf(E, n);
            if (rod.to[1] < arm.from[1] - 1e-6) errors.push(`contact ${label}: ${n} top ${rod.to[1]} leaves the charge arm (bottom ${arm.from[1]}): the rod would float`);
            if (rod.from[1] < MOUTH_Y - 1e-6) errors.push(`contact ${label}: ${n} bottom ${rod.from[1]} dips below the case mouth ${MOUTH_Y}`);
        }
        const die = elOf(E, 'ram_die'), rod = elOf(E, 'ram_rod'), nose = elOf(E, 'ram_bullet_nose');
        if (f === null && !nearly(die.to[1], crown.from[1])) errors.push(`contact idle: the die top ${die.to[1]} is not flush with the press crown bottom ${crown.from[1]}`);
        if (rod.to[1] < crown.from[1] - 1e-6) errors.push(`contact ${label}: the ram rod top ${rod.to[1]} leaves the press crown (bottom ${crown.from[1]})`);
        if (!nearly(rod.from[1], die.to[1])) errors.push(`contact ${label}: the ram rod does not sit on the die`);
        if (nose && !nearly(nose.to[1], die.from[1])) errors.push(`contact ${label}: the held bullet (top ${nose.to[1]}) does not hang from the die (bottom ${die.from[1]})`);
    });
    for (const [n, arr, cn] of [['prime_rod', PRIME, 'r_prime_case'], ['powder_tube', POWDER, 'r_powder_case']]) {
        const f = arr.findIndex((v) => v !== 0);
        const rod = elOf(frames[f], n), c = elOf(frames[f], cn);
        if (!nearly(rod.from[1], c.to[1]) || !insideXZ(rod, c)) errors.push(`contact f${f}: ${n} (${rod.from} .. ${rod.to}) does not land on the mouth of ${cn} (${c.from} .. ${c.to})`);
    }
    {
        const S = frames[ACTIVE_FRAME], N = frames[ACTIVE_FRAME + 1], shift = BELT[ACTIVE_FRAME + 1] - BELT[ACTIVE_FRAME];
        const c = elOf(S, 'r_seat_case'), tip = elOf(S, 'ram_bullet_tip');
        if (!tip || !nearly(tip.from[1], c.to[1]) || !insideXZ(tip, c)) errors.push('contact strike: the held bullet does not land on the charged case mouth');
        for (const [a, b] of [['ram_bullet_tip', 'r_seat_tip'], ['ram_bullet_nose', 'r_seat_nose']]) {
            const x = elOf(S, a), y = elOf(N, b);
            const same = x && y && [0, 1, 2].every((i) => nearly(x.from[i], y.from[i] - (i === 0 ? shift : 0)) && nearly(x.to[i], y.to[i] - (i === 0 ? shift : 0)));
            if (!same) errors.push(`contact strike: ${a} at f${ACTIVE_FRAME} is not where ${b} sits right after the strike`);
        }
    }
    {
        const t = elOf(idle, 'case_tube'), c = elOf(idle, 'r_in_case');
        if (!nearly(t.from[1], c.to[1]) || !insideXZ(t, c) || !insideXZ(c, t)) errors.push('contact: the case tube does not end exactly on the entry-slot case mouth');
    }
    {
        const last = frames[CYCLE - 1];
        for (const n of ['r_out_case', 'r_out_tip', 'r_out_nose']) {
            const e = elOf(last, n);
            if (!insideXZ(e, can) || e.to[1] > can.to[1] - 0.25 + 1e-6 || e.from[1] < can.from[1] - 1e-6) errors.push(`contact f${CYCLE - 1}: ${n} (${e.from} .. ${e.to}) is not sunk fully inside the ammo can`);
        }
        const standing = idle.filter((e) => /^can_b\d+_(tip|nose)$/.test(e.name)).map((e) => ({ lo: e.from, hi: e.to, name: e.name }));
        for (let f = 0; f < CYCLE; f++) for (const n of ['r_out_case', 'r_out_tip', 'r_out_nose']) {
            const a = elOf(frames[f], n), b = elOf(frames[(f + 1) % CYCLE], n);
            const bx = f === CYCLE - 1 ? { lo: a.from, hi: a.to } : { lo: [0, 1, 2].map((k) => Math.min(a.from[k], b.from[k])), hi: [0, 1, 2].map((k) => Math.max(a.to[k], b.to[k])) };
            for (const s of standing) if (boxesOverlap(bx, s)) errors.push(`contact f${f}: the drop round (${n}) runs into ${s.name} on its way into the can`);
        }
    }
    {
        const parsed = parsePartsJava(partsJava), prog = parseProgramJava(programJava);
        const poses = [['idle', idlePose(prog)]];
        for (let t = 0; t < prog.cycleTicks; t += 0.25) poses.push([`t ${t}`, sampleProgram(prog, t)]);
        // 静态件: 各档待机 / 工作按名字 + 盒子去重 (档位加件只在高档里有)
        const solids = new Map();
        for (const E of statics) for (const e of E) {
            const b = worldBox(e), k = e.name + JSON.stringify([b.lo, b.hi]);
            if (!solids.has(k)) solids.set(k, { name: e.name, lo: b.lo, hi: b.hi });
        }
        const bad = new Set(), deep = new Map();
        for (const [at, pose] of poses) {
            const bs = placedBoxes(parsed, applyPoseMirror(parsed, pose)).filter((b) => b.visible);
            for (let i = 0; i < bs.length; i++) for (let j = i + 1; j < bs.length; j++) if (bs[i].part !== bs[j].part && boxesOverlap(bs[i], bs[j])) bad.add(`${bs[i].part} / ${bs[j].part}`);
            for (const b of bs) for (const s of solids.values()) {
                if (!boxesOverlap(b, s)) continue;
                const rule = MOVING_VS_STATIC.find(([p, st]) => p.test(b.part) && st.test(s.name));
                if (!rule) { bad.add(`${b.part} / static ${s.name}`); continue; }
                if (!rule[3]) continue;
                const depth = [0, 1, 2].map((k) => Math.min(b.hi[k], s.hi[k]) - Math.max(b.lo[k], s.lo[k]));
                if (depth.some((d, k) => d >= rule[3][k] - 1e-6)) deep.set(`${b.part} / static ${s.name}`, `${rule[2]}: depth ${depth.map(r4)} at ${at} reaches the limit ${rule[3]}`);
            }
        }
        for (const s of bad) errors.push(`moving parts interpenetrate somewhere in the cycle: ${s}`);
        for (const [k, v] of deep) errors.push(`moving part sinks too deep into a static part: ${k} (${v})`);
    }
}

// ================================================================ 方块状态
const FACING_Y = { north: 0, east: 90, south: 180, west: 270 };
/** 旧 (LEGACY_DEPTH) 变体的规则: 与当年 dist/_make_munitions_factory_model.py (已删) 写出的一致 (北不写 y)。 */
function legacyVariant(sfx, active, facing, part) {
    const v = { model: `miningdim:block/munitions_bench${sfx}_${part}${active ? '_active' : ''}` };
    if (FACING_Y[facing]) v.y = FACING_Y[facing];
    return v;
}
function blockstateJson(sfx, existing, errors, label) {
    // 先核对仓库里现有的旧变体 (没有 layout 键的, 或 layout=legacy_depth 的) 与规则逐条相同, 保证旧台子的模型与朝向不变
    if (existing && existing.variants) {
        let n = 0;
        for (const [key, v] of Object.entries(existing.variants)) {
            const kv = Object.fromEntries(key.split(',').map((s) => s.split('=')));
            if (kv.layout && kv.layout !== 'legacy_depth') continue;
            n++;
            const want = legacyVariant(sfx, kv.active === 'true', kv.facing, kv.part);
            if (JSON.stringify(v) !== JSON.stringify(want)) errors.push(`${label}: existing legacy variant "${key}" = ${JSON.stringify(v)} differs from the legacy rule ${JSON.stringify(want)}`);
        }
        if (n !== 16) errors.push(`${label}: expected 16 existing legacy variants, found ${n}`);
    }
    const variants = {};
    for (const layout of ['legacy_depth', 'wide']) for (const active of [false, true]) for (const facing of Object.keys(FACING_Y)) for (const part of Object.keys(CELLS)) {
        const key = `active=${active},facing=${facing},layout=${layout},part=${part}`;
        if (layout === 'legacy_depth') variants[key] = legacyVariant(sfx, active, facing, part);
        else {
            const v = { model: `miningdim:block/munitions_bench${sfx}_line_${part}${active ? '_active' : ''}` };
            if (FACING_Y[facing]) v.y = FACING_Y[facing];
            variants[key] = v;
        }
    }
    return JSON.stringify({ variants }, null, 2) + '\n';
}

// ================================================================ Java 文本
const f1 = (v) => { const s = (Math.round(v * 10000) / 10000).toString(); return (s.includes('.') ? s : s + '.0') + 'F'; };
const PROGRAM_REL = path.join('src', 'main', 'java', 'com', 'miningdim', 'job', 'munitions', 'block', 'MunitionsBenchProgram.java');
const GEOMETRY_REL = path.join('src', 'main', 'java', 'com', 'miningdim', 'job', 'munitions', 'block', 'MunitionsBenchGeometry.java');
const PARTS_REL = path.join('src', 'main', 'java', 'com', 'miningdim', 'job', 'munitions', 'client', 'MunitionsBenchParts.java');

function geometryJava(g) {
    const arr = (v) => '{' + v.map(f1).join(', ') + '}';
    const boxes = (list) => list.map((b) => `            ${arr(b.box)}, // ${b.group}: ${b.label}`);
    return [
        'package com.miningdim.job.munitions.block;',
        '',
        '/**',
        ' * 军火台 WIDE 布局 (弹药流水线) 的几何常量: 轮廓箱 (静态件 + 运动件)、模型高度、运动件的活动范围、冲压火花的位置,',
        ' * 弹药箱计数屏 (COUNTER_*) 的显示面、布局与每档颜色, 以及运行灯效 (LIGHT_*) 的目标面、时间、透明度与每档颜色。',
        ' * <p>',
        ' * 由 tools/munitions_bench/generate_munitions_bench.mjs 按方块模型的同一份场景整个写出, 不要手改。',
        ' * 刻意不依赖任何 Minecraft 类, 方块、方块实体、渲染器与 GameTest 都能直接用。',
        ' * <p>',
        ' * 坐标系: 朝北放置时的像素, x 东、y 上、z 南, 正面 z = 0。整台 (whole-machine) 坐标的原点在主格西北下角:',
        ' * 主格 x 0..16 在站在正面的玩家右手边, 副格 x 16..32 在左手边。每格的碰撞箱用它自己的局部坐标 (0..16),',
        ' * 副格局部 x = 整台 x - 16。别的朝向按 {@link #rotated} 绕格子中心转 (与方块状态的 y 旋转同向)。',
        ' */',
        'public final class MunitionsBenchGeometry {',
        '',
        '    /** 台面 (钢台面顶面) 的 y (px); 台面以上都是设备。 */',
        `    public static final float BODY_TOP_PX = ${f1(g.bodyTop)};`,
        '    /** 各档静态方块模型 (两格、待机与工作) 的最高点 (px), 下标 = 档位 0..5 (普通..闪耀), 与 MunitionsBenchAssets.TIER_IDS 同序。 */',
        `    public static final float[] MODEL_TOP_PX = ${arr(g.modelTops)};`,
        '    /** 静态轮廓箱 (两格) 的最高点 (px), 即主格碰撞柱的高度。 */',
        `    public static final float SHAPE_TOP_PX = ${f1(g.shapeTop)};`,
        '',
        '    /**',
        '     * 主格静态件的轮廓箱 (主格局部像素, 朝北), 每个 {x0, y0, z0, x1, y1, z1}。贴着静态的大块 (柜体台面、弹药箱与箱盖、皮带、',
        '     * 压机、料斗、装填塔); 台面上的托盘、控制台、箱里冒出的弹头、各档加件的小灯与宝石是饰件, 不进轮廓。',
        '     * 轮廓 (选择框 / 右键命中) = 这些盒子 + {@link #MAIN_PART_BOXES}; 碰撞是整格一块实心柱, 高到这些盒子的最高点',
        '     * (见 MunitionsBenchBlock: 台面只有 8 px, 贴着模型的碰撞会让玩家一步跨上台面、站进运动件中间)。',
        '     */',
        '    public static final float[][] MAIN_BOXES = {',
        ...boxes(g.boxes.main),
        '    };',
        '    /** 副格静态件的轮廓箱 (副格局部像素, 朝北; 副格局部 x = 整台 x - 16), 用法同 {@link #MAIN_BOXES}。 */',
        '    public static final float[][] EXTENSION_BOXES = {',
        ...boxes(g.boxes.extension),
        '    };',
        '',
        '    /**',
        '     * 运动件的轮廓箱 (主格局部像素, 朝北): 每个件在整个循环与待机里扫过的范围, 切到本格; 被别的盒子包住的已并进去。',
        '     * 只进轮廓不进碰撞, 让皮带上的弹、冲头、底火冲杆与装药管都点得中台子 (否则右键会穿过它们打到后面的方块)。',
        '     */',
        '    public static final float[][] MAIN_PART_BOXES = {',
        ...boxes(g.partBoxes.main),
        '    };',
        '    /** 副格的运动件轮廓箱 (副格局部像素, 朝北), 用法同 {@link #MAIN_PART_BOXES}。 */',
        '    public static final float[][] EXTENSION_PART_BOXES = {',
        ...boxes(g.partBoxes.extension),
        '    };',
        '',
        '    /** 运动件 (方块实体渲染器画的件) 在整个循环与待机里扫过的范围, 整台坐标 {x, y, z} (px)。 */',
        `    public static final float[] PARTS_MIN = ${arr(g.partsMin)};`,
        `    public static final float[] PARTS_MAX = ${arr(g.partsMax)};`,
        '    /**',
        '     * 方块实体渲染包围盒的顶 (px): 方块实体渲染器画的东西 —— 运动件 (最高 PARTS_MAX[1]) 与运行灯效的覆盖层 (闪耀宝石顶面浮出后',
        '     * 最高, 见 LIGHT_*) —— 的最高点, 向上取整到 0.25 px。低了的话画面里只剩宝石时整个方块实体被视锥剔掉, 那一帧的灯效就没了。',
        '     */',
        `    public static final float RENDER_TOP_PX = ${f1(g.renderTop)};`,
        '',
        '    /** 冲压火花的位置 (整台坐标 = 主格局部坐标, px): 压弹头位那发的壳口, 冲头在 MunitionsBenchProgram.STRIKE_TICK 压到这里。 */',
        `    public static final float SPARK_X = ${f1(g.spark[0])};`,
        `    public static final float SPARK_Y = ${f1(g.spark[1])};`,
        `    public static final float SPARK_Z = ${f1(g.spark[2])};`,
        '',
        '    /** 皮带面 (px): 皮带上的弹都站在这个高度上。 */',
        `    public static final float BELT_TOP_PX = ${f1(g.line.beltTop)};`,
        '    /** 弹位中心 x (整台坐标, px), 从入口到出弹: 入口 / 底火 / 装药 / 压弹头 / 出弹, 相邻两位差一个 MunitionsBenchProgram.BELT_PITCH。 */',
        `    public static final float[] SLOT_X_PX = ${arr(g.line.slotX)};`,
        '    /** 弹位中心 z (px)。 */',
        `    public static final float SLOT_Z_PX = ${f1(g.line.slotZ)};`,
        '    /** 一发弹的壳高 / 弹头 (被甲 + 弹尖) 高 (px); 壳口 = BELT_TOP_PX + ROUND_CASE_HEIGHT_PX = SPARK_Y。 */',
        `    public static final float ROUND_CASE_HEIGHT_PX = ${f1(g.line.caseH)};`,
        `    public static final float ROUND_BULLET_HEIGHT_PX = ${f1(g.line.bulletH)};`,
        '    /**',
        '     * 静止位 (程序的 y 偏移为 0) 时运动件的下端 (px): 冲头夹着的弹头底、底火冲杆底、装药管底;',
        '     * 加上 MunitionsBenchProgram 的 ramY / primeY / powderY 就是当时的位置 (冲压时刻弹头底 = 壳口, 两根杆下探到底 = 壳口)。',
        '     */',
        `    public static final float RAM_BULLET_REST_BOTTOM_PX = ${f1(g.line.ramBulletRestBottom)};`,
        `    public static final float PRIME_ROD_REST_BOTTOM_PX = ${f1(g.line.primeRodRestBottom)};`,
        `    public static final float POWDER_TUBE_REST_BOTTOM_PX = ${f1(g.line.powderTubeRestBottom)};`,
        '',
        '    /** 运动件贴图 textures/entity/munitions_bench_parts.png 的尺寸 (与 MunitionsBenchParts 的 LayerDefinition 相同)。 */',
        `    public static final int PARTS_TEXTURE_WIDTH = ${g.texW};`,
        `    public static final int PARTS_TEXTURE_HEIGHT = ${g.texH};`,
        '',
        ...counterJavaLines(g.counter.layout, g.counter.colours, f1),
        '',
        ...lightsJavaLines(g.lights),
        '',
        '    private MunitionsBenchGeometry() {',
        '    }',
        '',
        '    /**',
        '     * 把一个格子局部的朝北盒子 {x0, y0, z0, x1, y1, z1} 转到别的朝向: quarterTurns = 俯视顺时针转的 90° 次数',
        '     * (NORTH 0, EAST 1, SOUTH 2, WEST 3, 与方块状态的 "y": 90 / 180 / 270 相同), 绕格子中心 (8, 8) 转, 返回新数组。',
        '     */',
        '    public static float[] rotated(float[] box, int quarterTurns) {',
        '        float x0 = box[0];',
        '        float z0 = box[2];',
        '        float x1 = box[3];',
        '        float z1 = box[5];',
        '        for (int i = 0; i < Math.floorMod(quarterTurns, 4); i++) {',
        '            // (x, z) → (16 - z, x): 北面 (z = 0) 转到东面 (x = 16)',
        '            float nx0 = 16.0F - z1;',
        '            float nx1 = 16.0F - z0;',
        '            z0 = x0;',
        '            z1 = x1;',
        '            x0 = nx0;',
        '            x1 = nx1;',
        '        }',
        '        return new float[]{x0, box[1], z0, x1, box[4], z1};',
        '    }',
        '}',
        '',
    ].join('\n');
}

function findJavaBase(out, rel) {
    for (const p of [path.join(out, rel), path.resolve(SCRIPT_DIR, '..', '..', rel)]) if (fs.existsSync(p)) { const raw = fs.readFileSync(p, 'utf8'); return { file: p, src: raw.replace(/\r\n/g, '\n'), crlf: raw.includes('\r\n') }; }
    throw new Error(path.basename(rel) + ' not found (neither in --out nor in the repo next to this script)');
}

// ================================================================ 主流程
function parseArgs(argv) {
    const a = {};
    for (let i = 0; i < argv.length; i++) if (argv[i].startsWith('--')) { const n = argv[i + 1]; if (n === undefined || n.startsWith('--')) a[argv[i].slice(2)] = true; else { a[argv[i].slice(2)] = n; i++; } }
    return a;
}

function main() {
    const args = parseArgs(process.argv.slice(2));
    if (!args.out || args.out === true) { console.error('usage: node generate_munitions_bench.mjs --out <repo root> [--check]'); process.exit(2); }
    const OUT = path.resolve(args.out);
    const RES = path.join(OUT, 'src', 'main', 'resources', 'assets', 'miningdim');
    const errors = [], warns = [];
    const outputs = [];
    const emit = (p, data) => outputs.push([p, data]);
    const cfg = { scene, hero, cycleFrames: CYCLE };
    const stat = (E) => E.filter((e) => !e.move);

    // ---- 1. 场景: 每档 待机 / 工作 (f2) / 物品 / 主角; 普通档再逐帧 (只用来建运动件与核对, 不写逐帧模型)
    const byTier = TIERS.map((T, t) => ({
        idle: runScene(cfg, t, 'idle', null), active: runScene(cfg, t, 'active', ACTIVE_FRAME),
        item: runScene(cfg, t, 'item', null), hero: runScene(cfg, t, 'hero', null),
    }));
    const frames = Array.from({ length: CYCLE }, (_, f) => runScene(cfg, 0, 'active', f));
    const scenes = [];
    TIERS.forEach((T, t) => { for (const k of ['idle', 'active', 'item', 'hero']) scenes.push({ E: byTier[t][k], label: `${T.key}/${k}`, kind: k }); });
    frames.forEach((E, f) => scenes.push({ E, label: `base/f${f}`, kind: 'frame' }));

    // ---- 2. 世界范围、剔除、共面 (运动件与静态件一起查: 任何一帧的运动件都不许和静态件同向共面)
    for (const s of scenes) {
        if (s.kind !== 'hero') {
            for (const e of s.E) {
                const b = worldBox(e);
                for (let a = 0; a < 3; a++) {
                    const range = [WORLD.x, WORLD.y, WORLD.z][a];
                    if (b.lo[a] < range[0] - 1e-6 || b.hi[a] > range[1] + 1e-6) errors.push(`${s.label}: ${e.name} leaves the machine volume on ${'xyz'[a]} [${range}] (${r4(b.lo[a])}..${r4(b.hi[a])})`);
                }
                if (e.rot && !LEGAL_ANGLES.includes(e.rot.angle)) errors.push(`${s.label}: ${e.name} illegal rotation angle ${e.rot.angle}`);
            }
        }
        cullHidden(s.E);
        autoShade(s.E);
        errors.push(...zfightCheck(s.E, s.label));
    }

    // ---- 3. 图集: 各档 待机静态件 + 工作静态件 + 物品 + 主角 共用一个布局, 每档一张
    const layout = planAtlas(TIERS.flatMap((T, t) => [
        { tier: t, E: stat(byTier[t].idle) }, { tier: t, E: stat(byTier[t].active) }, { tier: t, E: byTier[t].item }, { tier: t, E: byTier[t].hero },
    ]));
    const A = layout.size;
    const atlases = TIERS.map((T, t) => paintAtlas(layout, t, errors));
    for (let i = 0; i < layout.items.length; i++) {
        const a = layout.items[i];
        if (a.x < 0 || a.y < 0 || a.x + a.w > A || a.y + a.h > A) errors.push('atlas item out of bounds ' + a.key);
        for (let j = i + 1; j < layout.items.length; j++) {
            const b = layout.items[j];
            if (a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h) errors.push(`atlas overlap ${a.key} / ${b.key}`);
        }
    }
    atlases.forEach((cv, t) => { for (let i = 3; i < cv.data.length; i += 4) if (cv.data[i] !== 255) { errors.push(`atlas ${TIERS[t].key} has non-opaque pixels`); break; } });
    {
        // 档位一致性: 同名元素的同一个面在各档应落在同一块图集区域
        const key = (E) => { const seen = new Map(); return stat(E).map((e) => { const n = (seen.get(e.name) || 0) + 1; seen.set(e.name, n); return [e.name + '#' + n, e]; }); };
        const refMap = new Map(key(byTier[0].idle));
        let n = 0;
        for (let t = 1; t < TIERS.length; t++) for (const [k, e] of key(byTier[t].idle)) {
            const r = refMap.get(k);
            if (!r) continue;
            for (const face of Object.keys(e.plan)) if (r.plan[face] && r.plan[face].region !== e.plan[face].region && n++ < 8) warns.push(`${k.split('#')[0]}.${face}: tier ${TIERS[t].key} uses a different atlas item than tier base`);
        }
    }

    // ---- 4. 方块模型 (静态件) + 物品模型 + 贴图
    const stats = [];
    const modelTops = [];
    // 运行灯效的前提核对用: 每档 待机 / 工作 两格 JSON (即写出去的那份), 平移到整台坐标
    const lightModels = TIERS.map(() => ({ idle: [], active: [] }));
    let offGrid = 0;
    TIERS.forEach((T, t) => {
        const sfx = T.suffix;
        const textures = { atlas: `miningdim:block/munitions_bench${sfx}_atlas`, particle: `miningdim:block/munitions_bench${sfx}_particle` };
        const st = { key: T.key, models: {}, addOns: byTier[t].idle.addOnsRan };
        let top = 0;
        for (const [state, E] of [['idle', byTier[t].idle], ['active', byTier[t].active]]) {
            for (const cell of Object.keys(CELLS)) {
                const name = `munitions_bench${sfx}_line_${cell}${state === 'active' ? '_active' : ''}`;
                const json = { parent: 'minecraft:block/block', ambientocclusion: false, textures, elements: cellElements(stat(E), cell, A, errors, `${T.key}/${state}`) };
                const v = validateModel(name, json, { A, items: layout.items });
                errors.push(...v.errs);
                top = Math.max(top, v.maxY); offGrid += v.offGrid;
                st.models[name] = json.elements.length;
                lightModels[t][state].push(...elementsOf([{ model: json, offset: [CELLS[cell][0], 0, CELLS[cell][1]] }]));
                emit(path.join(RES, 'models', 'block', name + '.json'), stringifyModel(json));
            }
        }
        modelTops.push(r4(top));
        const { els, dropped } = itemElements(ITEM, byTier[t].item, byTier[t].hero, A);
        const item = { parent: 'minecraft:block/block', ambientocclusion: false, textures, display: { ...ITEM_DISPLAY, gui: ITEM.gui }, elements: els };
        const vi = validateModel(`item/munitions_bench${sfx}`, item, { A, items: layout.items, item: true });
        errors.push(...vi.errs);
        errors.push(...zfightCheck(els.map((el) => ({ name: el.name, from: el.from, to: el.to, rot: el.rotation || null, faces: el.faces })), `${T.key}/item`));
        st.item = els.length; st.itemDropped = dropped;
        emit(path.join(RES, 'models', 'item', `munitions_bench${sfx}.json`), stringifyModel(item));
        emit(path.join(RES, 'textures', 'block', `munitions_bench${sfx}_atlas.png`), encodePng(A, A, atlases[t].data));
        const pc = new Canvas(16, 16);
        defaultParticle(pc, palette(t));
        for (let i = 3; i < pc.data.length; i += 4) pc.data[i] = 255;
        emit(path.join(RES, 'textures', 'block', `munitions_bench${sfx}_particle.png`), encodePng(16, 16, pc.data));
        // 方块状态: 旧变体原样保留 (layout=legacy_depth), 新变体指向上面的模型
        const bsPath = path.join(RES, 'blockstates', `munitions_bench${sfx}.json`);
        const existing = fs.existsSync(bsPath) ? JSON.parse(fs.readFileSync(bsPath, 'utf8')) : null;
        if (!existing) errors.push(`blockstates/munitions_bench${sfx}.json not found in --out (the legacy variants are kept from it)`);
        for (const active of ['', '_active']) for (const cell of Object.keys(CELLS)) {
            const legacy = path.join(RES, 'models', 'block', `munitions_bench${sfx}_${cell}${active}.json`);
            if (!fs.existsSync(legacy)) errors.push(`legacy model ${path.basename(legacy)} is missing (layout=legacy_depth still points at it)`);
        }
        emit(bsPath, blockstateJson(sfx, existing, errors, `blockstates/munitions_bench${sfx}.json`));
        stats.push(st);
    });

    // ---- 5. 关键帧程序
    const rows = programRows();
    const strikeRow = rows.slice(0, CYCLE).reduce((m, r) => (r.ramY < m.ramY ? r : m), rows[0]);
    const strikeTick = strikeRow.tick;
    if (rows.slice(0, CYCLE).filter((r) => r.ramY === strikeRow.ramY).length !== 1) errors.push('program: the ram bottoms out in more than one frame (STRIKE_TICK is ambiguous)');
    if (strikeTick !== ACTIVE_FRAME * TICKS_PER_FRAME || DIE_HEAT[ACTIVE_FRAME] !== 1) errors.push(`program: the strike (tick ${strikeTick}) is not the hot frame f${ACTIVE_FRAME}`);
    if (BELT[CYCLE - 1] !== BELT[0] - PITCH) errors.push('program: the belt must end the cycle exactly one pitch on (BELT[7] = BELT[0] - PITCH)');

    // ---- 6. 运动件: 取场景里的运动件元素 → 平移回待机布局 → 4 倍 ModelPart 层 + 共用贴图
    const planOf = (e, opt) => Object.fromEntries(Object.entries(e.faces).map(([face, desc]) => [face, planFace(e, face, desc, opt)]));
    const defs = PART_DEFS.map((def) => {
        const src = def.src === 'idle' ? byTier[0].idle : frames[def.src];
        const pose = def.src === 'idle' ? IDLE_POSE : rows[def.src];
        const { dx, dy, visible } = drivePart(def, pose);
        if (!visible) errors.push(`part ${def.name}: not visible in its source scene ${def.src}`);
        const els = def.els.map((n) => {
            const e = src.find((x) => x.name === n && x.move);
            if (!e) { errors.push(`part ${def.name}: moving element ${n} not found in scene ${def.src}`); return null; }
            const from = [e.from[0] - dx, e.from[1] - dy, e.from[2]], to = [e.to[0] - dx, e.to[1] - dy, e.to[2]];
            const moved = { name: e.name, from, to, faces: e.faces };
            moved.plans = planOf(moved, { noCut: true });
            return moved;
        }).filter(Boolean);
        const lo = [0, 1, 2].map((a) => Math.min(...els.map((e) => e.from[a]))), hi = [0, 1, 2].map((a) => Math.max(...els.map((e) => e.to[a])));
        const pivot = [Math.round(((lo[0] + hi[0]) / 2) * 4) / 4, lo[1], Math.round(((lo[2] + hi[2]) / 2) * 4) / 4];
        // BER 一个件只能用一种光照: 自发光件除了底面 (从上方看不到, 热压模的底面是钢色不发光) 都要发光, 其余件都不发光
        for (const e of els) for (const [face, p] of Object.entries(e.plans)) {
            const want = def.fullBright ? HOT_LIGHT : 0;
            if ((p.emit || 0) !== want && !(def.fullBright && face === 'down')) errors.push(`part ${def.name}/${e.name}.${face}: face emission ${p.emit || 0}, expected ${want} (a part is either emissive or not)`);
        }
        return { ...def, pivot, els };
    });
    const layer = buildLayer(defs, errors);
    const partsTex = paintLayer(layer, 0, errors);
    for (let t = 1; t < TIERS.length; t++) {
        const other = paintLayer(layer, t, null);
        if (!Buffer.from(other.data.buffer).equals(Buffer.from(partsTex.data.buffer))) errors.push(`parts texture differs in tier ${TIERS[t].key}: moving parts must be tier-neutral (one shared texture)`);
    }
    emit(path.join(RES, 'textures', 'entity', 'munitions_bench_parts.png'), encodePng(layer.texW, layer.texH, partsTex.data));

    // 逐帧核对: 按关键帧表摆好的件 (元素级: 位置、尺寸、每个面的贴图签名) == 那一帧场景里的运动件
    const sigOf = (from, to, plans) => JSON.stringify([from.map(r4), to.map(r4), Object.keys(plans).sort().map((f) => f + '=' + planSignature(plans[f]))]);
    const posedSigs = (pose) => {
        const out = [];
        defs.forEach((def) => {
            const { dx, dy, visible } = drivePart(def, pose);
            if (!visible) return;
            for (const e of def.els) out.push({ part: def.name, el: e, sig: sigOf([e.from[0] + dx, e.from[1] + dy, e.from[2]], [e.to[0] + dx, e.to[1] + dy, e.to[2]], e.plans) });
        });
        return out;
    };
    const sceneSigs = (E) => E.filter((e) => e.move).map((e) => sigOf(e.from, e.to, planOf(e, {})));
    const multisetDiff = (a, b) => { const m = new Map(); for (const s of b) m.set(s, (m.get(s) || 0) + 1); const out = []; for (const s of a) { const n = m.get(s) || 0; if (n) m.set(s, n - 1); else out.push(s); } return out; };
    const compare = (label, want, got) => {
        const miss = multisetDiff(want, got), extra = multisetDiff(got, want);
        if (miss.length || extra.length) errors.push(`${label}: posed parts differ from the scene's moving elements (missing ${miss.length}: ${miss.slice(0, 3).join(' | ')}; extra ${extra.length}: ${extra.slice(0, 3).join(' | ')})`);
    };
    compare('idle', sceneSigs(byTier[0].idle), posedSigs(IDLE_POSE).map((x) => x.sig));
    frames.forEach((E, f) => compare(`f${f}`, sceneSigs(E), posedSigs(rows[f]).map((x) => x.sig)));
    // 循环接缝: 接缝行 (t → 40) 与首行 (t = 0) 画面只允许 SEAM_APPEARS 的件出现, 以及消失的件被静态件完全挡住
    {
        const endS = posedSigs(rows[CYCLE]), startS = posedSigs(rows[0]);
        const gone = multisetDiff(endS.map((x) => x.sig), startS.map((x) => x.sig));
        const came = multisetDiff(startS.map((x) => x.sig), endS.map((x) => x.sig));
        for (const s of came) { const x = startS.find((y) => y.sig === s); if (!SEAM_APPEARS.has(x.part)) errors.push(`seam: ${x.part}/${x.el.name} pops in at the cycle seam`); }
        const solid = stat(byTier[0].idle).filter((e) => !e.rot);
        const inside = (p) => solid.some((e) => [0, 1, 2].every((a) => p[a] >= e.from[a] - 1e-6 && p[a] <= e.to[a] + 1e-6));
        for (const s of gone) {
            const [from, to] = JSON.parse(s);
            let hidden = true;
            for (let x = from[0]; x <= to[0] + 1e-6 && hidden; x += 0.25) for (let y = from[1]; y <= to[1] + 1e-6 && hidden; y += 0.25) for (let z = from[2]; z <= to[2] + 1e-6 && hidden; z += 0.25) if (!inside([x, y, z])) hidden = false;
            const x = endS.find((y) => y.sig === s);
            if (!hidden) errors.push(`seam: ${x.part}/${x.el.name} disappears at the cycle seam while still visible`);
        }
    }

    // ---- 7. Java: 运动件 (整个写出) + 程序 (只换生成区块) + 几何 (整个写出), 写完再解析回来用 JS 镜像核对
    const partsJava = partsJavaSource(layer, { hotThreshold: HOT_THRESHOLD, fullBrightLight: HOT_LIGHT });
    emit(path.join(OUT, PARTS_REL), partsJava);
    const pBase = findJavaBase(OUT, PROGRAM_REL);
    if (!RE_GENERATED.test(pBase.src)) errors.push('MunitionsBenchProgram base: "// <generated>" ... "// </generated>" block not found');
    for (const [cname] of PROGRAM_COLUMNS) {
        const i = PROGRAM_COLUMNS.findIndex(([c]) => c === cname);
        if (!new RegExp(`${cname} = ${i};`).test(pBase.src)) errors.push(`MunitionsBenchProgram base: hand-written ${cname} = ${i} not found (the table columns would be misread)`);
    }
    const program = pBase.src.replace(RE_GENERATED, (m, open, close) => open + programBlock({ frames: CYCLE, ticksPerFrame: TICKS_PER_FRAME, cycleTicks: CYCLE * TICKS_PER_FRAME, strikeTick, pitch: PITCH, rows, idle: IDLE_POSE }) + close);
    if (program.replace(RE_GENERATED, '') !== pBase.src.replace(RE_GENERATED, '')) errors.push('program patch touched text outside the generated block');
    emit(path.join(OUT, PROGRAM_REL), pBase.crlf ? program.replace(/\n/g, '\r\n') : program);
    {
        const prog = parseProgramJava(program);
        const parsed = parsePartsJava(partsJava);
        const boxesOf = (pose) => placedBoxes(parsed, applyPoseMirror(parsed, pose)).filter((b) => b.visible).map((b) => JSON.stringify([b.lo, b.hi])).sort();
        const want = (E) => E.filter((e) => e.move).map((e) => JSON.stringify([e.from.map(r4), e.to.map(r4)])).sort();
        const same = (a, b) => a.length === b.length && a.every((v, i) => v === b[i]);
        if (!same(boxesOf(idlePose(prog)), want(byTier[0].idle))) errors.push('Java round trip: idle() + applyPose do not reproduce the idle layout');
        frames.forEach((E, f) => { if (!same(boxesOf(sampleProgram(prog, f * TICKS_PER_FRAME)), want(E))) errors.push(`Java round trip: sample(${f * TICKS_PER_FRAME}) + applyPose do not reproduce frame f${f}`); });
        if (prog.cycleTicks !== 40 || prog.strikeTick !== strikeTick || prog.rows.length !== rows.length) errors.push('Java round trip: program constants/rows');
        if (parsed.texW !== layer.texW || parsed.texH !== layer.texH || parsed.parts.length !== layer.parts.length) errors.push('Java round trip: parts layer');
    }

    // ---- 8. 几何: 碰撞箱、高度、运动件范围、火花
    const boxes = shapeBoxes(byTier.map((s) => s.idle), errors);
    const partsLo = [Infinity, Infinity, Infinity], partsHi = [-Infinity, -Infinity, -Infinity];
    const sweeps = new Map();
    {
        const parsed = parsePartsJava(partsJava);
        for (const pose of [...rows, IDLE_POSE]) for (const b of placedBoxes(parsed, applyPoseMirror(parsed, pose))) {
            if (!sweeps.has(b.part)) sweeps.set(b.part, { lo: [Infinity, Infinity, Infinity], hi: [-Infinity, -Infinity, -Infinity] });
            const s = sweeps.get(b.part);
            for (let a = 0; a < 3; a++) {
                partsLo[a] = Math.min(partsLo[a], b.lo[a]); partsHi[a] = Math.max(partsHi[a], b.hi[a]);
                s.lo[a] = Math.min(s.lo[a], b.lo[a]); s.hi[a] = Math.max(s.hi[a], b.hi[a]);
            }
        }
    }
    const partBoxes = partShapeBoxes(sweeps);
    for (const [cell, list] of Object.entries(partBoxes)) for (const b of list) {
        if (b.box[0] < 0 || b.box[2] < 0 || b.box[1] < 0 || b.box[3] > 16 || b.box[5] > 16) errors.push(`part outline box ${cell}/${b.group} leaves the cell: ${b.box}`);
    }
    const worktop = byTier[0].idle.find((e) => e.name === 'worktop');
    const spark = [SLOT.seat, MOUTH_Y, RZ];
    const bulletAtStrike = frames[ACTIVE_FRAME].find((e) => e.name === 'ram_bullet_tip');
    if (!bulletAtStrike || Math.abs(bulletAtStrike.from[1] - spark[1]) > 1e-6) errors.push('spark: the ram bullet does not meet the case mouth at the strike');
    const line = lineDesign(byTier[0].idle, errors);
    contactChecks(byTier[0].idle, frames, partsJava, program, byTier.flatMap((s) => [stat(s.idle), stat(s.active)]), errors);
    // ---- 8b. 计数屏 (方案 C): 布局 + 每档颜色写进 Geometry 的 COUNTER_*; 核对场景里的箱盖 / 箱身与布局一致、顶点绕序、字放得下、待机对比度
    const counter = { layout: layoutFromDisplay(), colours: colourTable(TIERS) };
    {
        const L = counter.layout, W = L.window, S = L.stencil;
        const near = (a, b) => Math.abs(a - b) < 1e-6;
        const X = (u) => W.tl[0] - u * L.qt, Y = (v) => W.tl[1] - v * L.qt;
        errors.push(...windingProblems(L));
        for (const [t, T] of TIERS.entries()) for (const k of ['idle', 'active']) {
            const E = byTier[t][k], label = `counter ${T.key}/${k}`;
            const body = E.find((e) => e.name === 'can_lid');
            if (!body || !near(body.from[2], W.tl[2]) || !near(body.from[0], X(W.rect.x + W.rect.w)) || !near(body.to[0], X(W.rect.x))
                || !near(body.from[1], Y(W.rect.y + W.rect.h)) || !near(body.to[1], Y(W.rect.y))) errors.push(`${label}: the lid window body (can_lid) is not the window face of COUNTER_WINDOW_*`);
            else if (!body.rot || body.rot.axis !== 'x' || !near(body.rot.angle, W.rot.angle) || !W.rot.origin.every((v, i) => near(v, body.rot.origin[i]))) errors.push(`${label}: can_lid does not turn like COUNTER_LID_ROTATION_*`);
            const strips = E.filter((e) => /^can_lid_[tblr]$/.test(e.name));
            if (strips.length !== 4 || strips.some((e) => !near(e.from[2], W.tl[2] - L.recess) || !e.rot || !near(e.rot.angle, W.rot.angle))) errors.push(`${label}: the lid frame strips must start ${L.recess} px in front of the window face and turn with it`);
            else {
                const lo = [0, 1].map((a) => Math.min(...strips.map((e) => e.from[a]))), hi = [0, 1].map((a) => Math.max(...strips.map((e) => e.to[a])));
                if (!near((hi[0] - lo[0]) / L.qt, W.face[0]) || !near((hi[1] - lo[1]) / L.qt, W.face[1]) || !near(hi[0], W.tl[0]) || !near(hi[1], W.tl[1])) errors.push(`${label}: the lid face is not COUNTER_LID_FACE_QT from COUNTER_WINDOW_FACE_TOP_LEFT`);
            }
            const can = E.find((e) => e.name === 'can');
            if (!can || can.rot || !near(can.from[2], S.tl[2]) || !near(can.to[0], S.tl[0]) || !near(can.to[1], S.tl[1])
                || !near((can.to[0] - can.from[0]) / L.qt, S.face[0]) || !near((can.to[1] - can.from[1]) / L.qt, S.face[1])) errors.push(`${label}: the can front is not the stencil face of COUNTER_STENCIL_*`);
        }
        const inside = (r, R) => r.x >= R.x && r.y >= R.y && r.x + r.w <= R.x + R.w && r.y + r.h <= R.y + R.h;
        if (!inside(L.bar, W.rect)) errors.push('counter: the fill bar leaves the window');
        if (!inside({ x: L.count.x, y: L.count.y, w: L.count.w, h: FONT_4x7.h * L.count.texel }, W.rect)) errors.push('counter: the count box leaves the window');
        if (L.bar.y + L.bar.h > L.count.y) errors.push('counter: the fill bar overlaps the digits');
        // 每一种格式形状里最宽的那个都放得进发数框 (int 缓冲最多 "2.14G"; MAX_ROUNDS = "999G")
        for (const n of [1, 88, 888, 8888, 9999, 10000, 12480, 88800, 99999, 123456, 888000, 999999, 1e6, 1230000, 8880000, 12345678, 88800000, 123456789, 888000000, 1e9, 1.23e9, 2147483647, 88.8e9, 888e9, MAX_ROUNDS, 1e15]) {
            const t = formatCount(n), w = textWidth(FONT_4x7, t) * L.count.texel;
            if (t.length > 5 || w > L.count.w) errors.push(`counter: "${t}" (${n} rounds) is ${w} qt wide / ${t.length} chars; the count box is ${L.count.w} qt, 5 chars`);
        }
        const lab = DISPLAY.stencil.label;
        for (const label of CALIBER_LABELS) {
            const w = textWidth(FONT_3x5, label) * S.texel, x = Math.floor((S.face[0] - w) / 2);
            if (x < lab.x || x + w > lab.x + lab.w || S.y < lab.y || S.y + FONT_3x5.h * S.texel > lab.y + lab.h) errors.push(`counter: the calibre stencil "${label}" leaves the painted label on the can`);
        }
        // 评审的目标: 待机的字对比度 ≥ 4.5:1 (待机 ≈ 工作亮度的 0.7, 仍要读得出)。颜色是用户认可的方案原样, 只报警告
        // (超凡档待机 4.39:1, 工作 5.57:1)。
        TIERS.forEach((T) => {
            const c = counterColours(T.roles.light, false), k = contrast(c.count, c.bg);
            if (k < 4.5) warns.push(`counter: idle digits in tier ${T.key} have ${k.toFixed(2)}:1 contrast against the window (target 4.5:1)`);
        });
    }
    // ---- 8c. 运行灯效 (lights.mjs): 目标面落在每档刚生成的 JSON 的那个元素的那个面上 (shade / 自发光 / grow 的棱), 脉冲的峰与帧表对得上,
    //          任何时刻覆盖层不重叠、α 不落在 (0, 0.1)、不超过 LIGHT_MAX_QUADS; 常量写进 Geometry 的 LIGHT_*
    const lights = lightsLayout();
    let lightMax = [];
    {
        errors.push(...targetProblems(lightModels).map((p) => 'lights: ' + p));
        const prog = { cycleTicks: CYCLE * TICKS_PER_FRAME, strikeTick, pitch: PITCH, rows };
        errors.push(...programProblems(prog).map((p) => 'lights: ' + p));
        const fp = frameProblems(prog);
        errors.push(...fp.problems.map((p) => 'lights: ' + p));
        lightMax = fp.max;
        if (LIGHT_MAX_DISTANCE_BLOCKS !== counter.layout.maxDist) errors.push(`lights: MAX_DISTANCE_BLOCKS ${LIGHT_MAX_DISTANCE_BLOCKS} != the counter's ${counter.layout.maxDist} (they share one early return in the renderer)`);
    }
    // 方块实体渲染包围盒的顶: 运动件与灯效覆盖层 (宝石顶面浮出 1/32) 里高的那个, 向上取整到 0.25 px
    const renderTop = Math.ceil(Math.max(r4(partsHi[1]), overlayTopPx()) * 4) / 4;
    const geo = {
        bodyTop: worktop.to[1], modelTops, shapeTop: Math.max(...[...boxes.main, ...boxes.extension].map((b) => b.box[4])), boxes, partBoxes,
        partsMin: partsLo.map(r4), partsMax: partsHi.map(r4), renderTop, spark, line, texW: layer.texW, texH: layer.texH, counter, lights,
    };
    const geometrySrc = geometryJava(geo);
    {
        // 写出的 COUNTER_* 解析回来必须就是这份布局与颜色 (预览与对拍只读 Java)
        const canon = (v) => (Array.isArray(v) ? v.map(canon) : v && typeof v === 'object' ? Object.fromEntries(Object.keys(v).sort().map((k) => [k, canon(v[k])])) : v);
        let parsed = null;
        try { parsed = parseCounterJava(geometrySrc); } catch (e) { errors.push('Java round trip: ' + e.message); }
        if (parsed && JSON.stringify(canon(parsed)) !== JSON.stringify(canon({ ...counter.layout, colours: counter.colours }))) errors.push('Java round trip: COUNTER_* constants do not parse back to the counter layout / colours');
        // LIGHT_* 同样
        let pl = null;
        try { pl = parseLightsJava(geometrySrc); } catch (e) { errors.push('Java round trip: ' + e.message); }
        const d = pl && layoutDiff(pl, lights);
        if (d) errors.push('Java round trip: LIGHT_* constants do not parse back to lights.mjs: ' + d);
    }
    emit(path.join(OUT, GEOMETRY_REL), geometrySrc);

    // ---- 9. 汇报 + 写盘
    console.log(`atlas ${A}x${A}: ${layout.regions} painted regions, ${layout.swatches} swatches; parts texture ${layer.texW}x${layer.texH} (${layer.blocks.length} box-UV blocks for ${layer.parts.reduce((n, p) => n + p.cubes.length, 0)} cubes in ${layer.parts.length} parts)`);
    for (const s of stats) console.log(`  ${s.key.padEnd(13)} static ${Object.values(s.models).join('/')} elements (main/ext idle, main/ext active), item ${s.item}${s.itemDropped ? ` (${s.itemDropped} too thin, dropped)` : ''}, add-ons [${s.addOns.join(', ')}]`);
    console.log(`  model tops ${modelTops.join(' / ')} px; outline main ${boxes.main.length} + ${partBoxes.main.length} part boxes, extension ${boxes.extension.length} + ${partBoxes.extension.length} part boxes, top ${geo.shapeTop} px; parts sweep ${geo.partsMin} .. ${geo.partsMax}; render box top ${geo.renderTop} px`);
    console.log(`  program: ${rows.length} keyframes / ${CYCLE * TICKS_PER_FRAME} ticks, strike at tick ${strikeTick}; parts: ${layer.parts.map((p) => p.name).join(', ')}`);
    {
        const L = counter.layout;
        console.log(`  counter (option C): window face ${L.window.tl} px turned ${L.window.rot.angle} deg about x at ${L.window.rot.origin}, window ${Object.values(L.window.rect)} qt, bar ${Object.values(L.bar)}, count ${L.count.x},${L.count.y} w ${L.count.w}; stencil face ${L.stencil.tl} px row ${L.stencil.y}; lift ${L.lift} px`);
    }
    console.log(`  lights: ${lights.targets.length} target faces in ${lights.groups.length} groups; unlocks ${LIGHT_EFFECTS.map((e, i) => `${e.id}>=${lights.unlock[i]}`).join(' ')}; max quads per tier ${lightMax.join(' / ')} (cap ${lights.maxQuads})`);
    if (offGrid) console.log(`  note: ${offGrid} block-model coordinates on the 0.25 grid but not the 0.5 grid`);
    for (const w of warns) console.warn('  WARN ' + w);
    if (errors.length) {
        const uniq = [...new Set(errors)];
        console.error(`VALIDATION FAILED (nothing written), ${uniq.length} distinct problem(s):\n  ` + uniq.slice(0, 60).join('\n  ') + (uniq.length > 60 ? `\n  (+${uniq.length - 60} more)` : ''));
        process.exit(1);
    }
    if (args.check) { console.log(`validation OK (--check: nothing written, ${outputs.length} files would be generated)`); return; }
    let written = 0;
    for (const [p, data] of outputs) {
        const buf = Buffer.isBuffer(data) ? data : Buffer.from(data, 'utf8');
        if (fs.existsSync(p) && fs.readFileSync(p).equals(buf)) continue;
        fs.mkdirSync(path.dirname(p), { recursive: true });
        fs.writeFileSync(p, buf);
        written++;
    }
    console.log(`validation OK, wrote ${written} of ${outputs.length} files (the rest were unchanged) -> ${OUT}`);
}

main();
