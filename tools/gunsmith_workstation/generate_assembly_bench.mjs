#!/usr/bin/env node
// 枪械组装台 · 方案 A「枪匠工坊工作台」生成脚本 (零依赖, Node 18+)。第 2 版 (按评审意见修改)。
// 用法: node generate.mjs --out <根目录>
// 产物 (相对 <根目录>):
//   src/main/resources/assets/miningdim/models/block/gunsmith_assembly_bench_{main,side,back,back_side}[_active].json
//   src/main/resources/assets/miningdim/models/item/gunsmith_assembly_bench.json
//   src/main/resources/assets/miningdim/textures/block/gunsmith_assembly_atlas.png
//   src/main/resources/assets/miningdim/textures/block/gunsmith_assembly_particle.png
//   src/main/resources/assets/miningdim/textures/entity/gunsmith_assembly_arm.png
//   src/main/java/com/miningdim/job/munitions/client/GunsmithAssemblyBenchRenderer.java
//     (只替换 姿态常量块 与 createBodyLayer() 方法; 文件其余部分原样保留。
//      底稿: <根目录> 里已有的该文件; 没有则取本脚本所在仓库 (../..) 里的; 都没有则报错。)
// 整台桌子先在 32x32 世界像素里建 (朝北, x 东, z 南, 正面 z=0), 再按格切成四个部位文件。
// 每个可见面在图集里分到自己的矩形 (默认 1 贴图像素 / 模型像素, 细节件 2 倍), 程序化绘制。
// 机械臂: 按 Java applyPose 的真实时间窗 (从底稿里解析) 扫整个动作周期, 任何臂段进入方块元素即校验失败。
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { fileURLToPath } from 'node:url';

// ------------------------------------------------------------ 参数
const IS_MAIN = !!process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
const argv = IS_MAIN ? process.argv.slice(2) : ['--out', '.'];
let OUT = null;
for (let i = 0; i < argv.length; i++) if (argv[i] === '--out') OUT = argv[++i];
if (!OUT) { console.error('usage: node generate.mjs --out <root>'); process.exit(2); }
OUT = path.resolve(OUT);
const SCRIPT_DIR = path.dirname(fileURLToPath(import.meta.url));
const RES = path.join(OUT, 'src', 'main', 'resources', 'assets', 'miningdim');
const JAVA_REL = path.join('src', 'main', 'java', 'com', 'miningdim', 'job', 'munitions', 'client', 'GunsmithAssemblyBenchRenderer.java');
const JAVA_OUT = path.join(OUT, JAVA_REL);
const ATLAS_ID = 'miningdim:block/gunsmith_assembly_atlas';
const PARTICLE_ID = 'miningdim:block/gunsmith_assembly_particle';

// ------------------------------------------------------------ PNG 编码
const CRC = (() => { const t = new Uint32Array(256); for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; t[n] = c >>> 0; } return t; })();
function crc32(b) { let c = 0xffffffff; for (let i = 0; i < b.length; i++) c = CRC[(c ^ b[i]) & 0xff] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; }
function chunk(type, data) {
    const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
    const td = Buffer.concat([Buffer.from(type, 'ascii'), data]);
    const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(td));
    return Buffer.concat([len, td, crc]);
}
function encodePng(w, h, data) {
    const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 6;
    const raw = Buffer.alloc((w * 4 + 1) * h);
    for (let y = 0; y < h; y++) { raw[y * (w * 4 + 1)] = 0; for (let i = 0; i < w * 4; i++) raw[y * (w * 4 + 1) + 1 + i] = data[y * w * 4 + i]; }
    return Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw, { level: 9 })), chunk('IEND', Buffer.alloc(0))]);
}

// ------------------------------------------------------------ 颜色与材质
const C = (s) => [parseInt(s.slice(1, 3), 16), parseInt(s.slice(3, 5), 16), parseInt(s.slice(5, 7), 16)];
const mix = (a, b, t) => a.map((v, i) => Math.round(v + (b[i] - v) * t));
const mat = (name, base, hi, lo, dk) => ({ name, base: C(base), hi: C(hi), lo: C(lo), dk: C(dk) });
const M = {
    frame: mat('frame', '#56606d', '#77818e', '#3f4752', '#2c323a'),      // 枪灰钢架
    steel: mat('steel', '#a9b2bc', '#cfd6dd', '#838d98', '#626b76'),      // 浅钢台面
    rack: mat('rack', '#6f7b87', '#8e99a5', '#56616c', '#414a54'),        // 中灰钢夹具板
    slate: mat('slate', '#5f7590', '#8198b2', '#475971', '#33404f'),      // 抽屉漆面
    orange: mat('orange', '#e8862c', '#ffb257', '#b8611b', '#7e3f10'),
    hot: mat('hot', '#ffa23c', '#ffe2a6', '#f07a1c', '#c85510'),          // 工作时发光的橙色
    yellow: mat('yellow', '#f0c23a', '#ffe07a', '#c2931c', '#8a6510'),
    cyan: mat('cyan', '#39bfd0', '#8fe8f2', '#23889a', '#155a66'),
    black: mat('black', '#30353c', '#555c66', '#22262b', '#16191d'),      // 枪身金属
    gunm: mat('gunm', '#434a54', '#646c77', '#323840', '#1f2328'),        // 护木导轨
    tan: mat('tan', '#a8885a', '#cdae7a', '#7e6543', '#58462e'),          // 沙色枪具
    brass: mat('brass', '#d9a93e', '#f6d57a', '#a57a26', '#6e4f15'),
    copper: mat('copper', '#c46f3a', '#e8955c', '#8f4a22', '#5e2e14'),
    olive: mat('olive', '#65733f', '#839357', '#4a552c', '#343c1f'),
    matg: mat('matg', '#2f5f4f', '#4f8a72', '#244a3d', '#183329'),        // 维修垫
    peg: mat('peg', '#cbc3ae', '#e2dcca', '#a39b86', '#6f6858'),          // 洞洞板
    chrome: mat('chrome', '#c9d1da', '#eef2f6', '#8e98a3', '#5f6873'),
    red: mat('red', '#d0482e', '#f07458', '#9a3020', '#651c12'),
    rubber: mat('rubber', '#2b2e33', '#3f434a', '#202327', '#15171a'),
};
const BOTTOM = C('#2a2f36');
const HAZ_Y = C('#f2c230'), HAZ_K = C('#26282c');
const LABEL = C('#ebe6d6');
const CYAN_ON = C('#62ecfa'), CYAN_OFF = C('#274850');

// ------------------------------------------------------------ 画布
class Canvas {
    constructor(w, h) { this.w = w; this.h = h; this.data = new Uint8ClampedArray(w * h * 4); }
    px(x, y, c) { if (x < 0 || y < 0 || x >= this.w || y >= this.h) return; const o = (y * this.w + x) * 4; this.data[o] = c[0]; this.data[o + 1] = c[1]; this.data[o + 2] = c[2]; this.data[o + 3] = 255; }
    get(x, y) { const o = (y * this.w + x) * 4; return [this.data[o], this.data[o + 1], this.data[o + 2]]; }
    rect(x, y, w, h, c) { for (let j = y; j < y + h; j++) for (let i = x; i < x + w; i++) this.px(i, j, c); }
    fill(c) { this.rect(0, 0, this.w, this.h, c); }
    hline(x, y, w, c) { this.rect(x, y, w, 1, c); }
    vline(x, y, h, c) { this.rect(x, y, 1, h, c); }
    bevel(m, x = 0, y = 0, w = this.w, h = this.h) {
        this.hline(x, y, w, m.hi); this.vline(x, y, h, m.hi);
        this.hline(x, y + h - 1, w, m.lo); this.vline(x + w - 1, y, h, m.lo);
    }
    // 凹陷面板: 上/左暗, 下/右亮
    inset(m, x, y, w, h) {
        this.hline(x, y, w, m.lo); this.vline(x, y, h, m.lo);
        this.hline(x, y + h - 1, w, m.hi); this.vline(x + w - 1, y, h, m.hi);
    }
    bolt(x, y, m) { this.px(x, y, m.hi); this.px(x + 1, y, m.base); this.px(x, y + 1, m.lo); this.px(x + 1, y + 1, m.dk); }
    hazard(x, y, w, h, period = 4, phase = 0) {
        for (let j = 0; j < h; j++) for (let i = 0; i < w; i++) this.px(x + i, y + j, (((i + j + phase) % period) + period) % period < period / 2 ? HAZ_Y : HAZ_K);
    }
}

// ------------------------------------------------------------ 面描述
// flat(col): 纯色小色块 (可任意拉伸不失真); paint(key, fn, {m, d}): 专属区域, fn(canvas) 程序化绘制。
const flat = (col) => ({ flat: true, col: typeof col === 'string' ? C(col) : col });
const paint = (key, fn, opt = {}) => ({ key, fn, m: opt.m || M.frame, d: opt.d || 1 });
const glow = (desc) => ({ ...desc, emit: true });
const itemOnly = (desc) => ({ ...desc, itemOnly: true });
const plate = (m, d = 1) => paint('plate:' + m.name, (c) => { c.fill(m.base); c.bevel(m); }, { m, d });
const fm = (m) => flat(m.base);

// ------------------------------------------------------------ 场景
const FACES = ['north', 'south', 'east', 'west', 'up', 'down'];

function faceDims(face, from, to) {
    const dx = to[0] - from[0], dy = to[1] - from[1], dz = to[2] - from[2];
    if (face === 'north' || face === 'south') return [dx, dy];
    if (face === 'east' || face === 'west') return [dz, dy];
    return [dx, dz];
}

// 枪架枢轴: 所有枪架/步枪元素绕 x 轴 +45° (剖面朝北上方)。枪内局部坐标: x 同世界, v = 剖面高度, w = 厚度 (+w 朝背板)。
const GUN = { py: 13.5, pz: 12.5, angle: 45 };
const GUN_ROT = { origin: [16, GUN.py, GUN.pz], axis: 'x', angle: GUN.angle };
function gunToWorld(x, v, w) {
    const a = GUN.angle * Math.PI / 180, c = Math.cos(a), s = Math.sin(a);
    return [x, GUN.py + v * c - w * s, GUN.pz + v * s + w * c];
}

// 机械臂主要尺寸 (世界像素)。shoulder = 肩座底面中心, 立在设备台 y=11.5 上。
const ARM = {
    shoulder: [26.5, 11.5, 23.5],
    jointUp: 4,          // 大臂关节在肩座底面上方
    L1: 7, L2: 8,        // 大臂 / 小臂
    gripY: 2, clawX: 1.5, clawY: 1,
};
// 换刀座 (待机时抓手停在它上方), 立在设备台西侧, 与肩部同一 z (待机偏航角为 0)。
const PED = { x: 20.25, z: ARM.shoulder[2], top: 14 };

/** 世界坐标建一台 (active: 工作态)。返回元素数组。 */
function buildScene(active) {
    const E = [];
    const box = (name, from, to, faces, opt = {}) => {
        const f = {};
        for (const k of FACES) {
            if (faces[k] !== undefined) f[k] = faces[k];
            else if (k === 'down') f[k] = flat(BOTTOM);
            else if (faces.all) f[k] = faces.all;
            else throw new Error('face missing ' + name + ' ' + k);
            if (f[k] === null) delete f[k];
        }
        const e = { name, from, to, faces: f, rot: opt.rot || null, item: opt.item !== false };
        E.push(e);
        return e;
    };

    // ---------------- 底座与柜体
    box('plinth', [1.5, 0, 1.5], [30.5, 1, 27.5], {
        all: flat('#2a2f36'),
        north: paint('plinth_n', (c) => {
            c.fill(C('#2c3138')); c.hline(0, 0, c.w, C('#3c424b'));
            const u0 = (30.5 - 19) * 2, u1 = (30.5 - 13) * 2;
            c.hazard(u0, 0, u1 - u0, c.h, 4);
        }, { d: 2 }),
    });
    box('ped_l', [1, 1, 1], [13, 8, 28], {
        all: plate(M.frame),
        west: paint('ped_side', pedSide, { m: M.frame }),
        east: paint('ped_inner', pedInner, { m: M.frame }),
    });
    box('drawers_l', [1.5, 1.5, 0.5], [12.5, 7.5, 1], {
        north: paint('drawers3', drawers3, { m: M.slate, d: 2 }),
        east: fm(M.slate), west: fm(M.slate), up: fm(M.slate), south: null, down: fm(M.slate),
    });
    for (const [i, y] of [[0, 6.5], [1, 4.5], [2, 2.5]]) {
        box('handle_l' + i, [4.5, y, 0], [8.5, y + 0.5, 0.5], { all: flat(M.chrome.hi), north: flat(M.chrome.base), down: flat(M.chrome.lo), south: null }, { item: false });
    }
    box('ped_r', [19, 1, 1], [31, 8, 28], {
        all: plate(M.frame),
        east: paint('ped_side', pedSide, { m: M.frame }),
        west: paint('ped_inner', pedInner, { m: M.frame }),
    });
    box('drawers_r', [19.5, 1.5, 0.5], [30.5, 7.5, 1], {
        north: paint('partsgrid', partsGrid, { m: M.slate, d: 2 }),
        east: fm(M.slate), west: fm(M.slate), up: fm(M.slate), south: null, down: fm(M.slate),
    });
    box('knee_back', [13, 1, 18], [19, 8, 19], {
        all: flat(M.frame.lo),
        north: paint('knee', (c) => {
            c.fill(M.frame.lo); c.bevel({ hi: M.frame.base, lo: M.frame.dk });
            for (let y = 1; y < c.h - 1; y += 2) c.hline(1, y, c.w - 2, M.frame.dk);
        }, { m: M.frame }),
    });
    box('back_panel', [1.5, 0, 29], [30.5, 10, 31], {
        all: plate(M.frame),
        south: paint('wall_back_lo', wallBackLo, { m: M.frame }),
    });
    for (const [nm, x0] of [['post_w', 0], ['post_e', 30.5]]) {
        box(nm, [x0, 0, 29], [x0 + 1.5, 15, 31.5], {
            all: paint('post', (c) => { c.fill(M.frame.base); c.bevel(M.frame); for (let y = 4; y < c.h - 2; y += 6) c.px(1, y, M.frame.hi); }, { m: M.frame, d: 2 }),
        });
    }

    // ---------------- 台面
    box('worktop', [0, 8, 0], [32, 10, 29], {
        up: paint('wt_top', worktopTop, { m: M.steel }),
        north: paint('wt_edge', worktopEdge, { m: M.steel, d: 2 }),
        east: paint('wt_edge', worktopEdge, { m: M.steel, d: 2 }),
        west: paint('wt_edge', worktopEdge, { m: M.steel, d: 2 }),
        south: flat(M.steel.lo),
        down: flat(M.frame.dk),
    });

    // ---------------- 左前: 维修垫与零件, 后沿一条贯通全宽的定位灯
    const MAT = { x0: 1.5, x1: 14.5, z0: 2, z1: 8.5 };
    const parts = {
        bcg: [3, 3, 7, 4.5], spring: [3, 5.5, 8, 6.5], mag: [9, 3, 12.5, 4.5],
        slot: [3, 7, 6.5, 7.5], b1: [9, 6, 10.5, 6.5], b2: [11, 6, 12.5, 6.5], b3: [9, 7, 10.5, 7.5], b4: [11, 7, 12.5, 7.5],
    };
    box('mat', [MAT.x0, 10, MAT.z0], [MAT.x1, 10.5, MAT.z1], {
        up: paint('mat_top', (c) => matTop(c, MAT, parts), { m: M.matg, d: 2 }),
        north: flat(M.matg.lo), east: flat(M.matg.lo), west: flat(M.matg.lo), south: null, down: null,
    });
    box('mat_led', [MAT.x0, 10, MAT.z1], [MAT.x1, 10.5, MAT.z1 + 1], {
        all: active ? glow(flat(CYAN_ON)) : flat(CYAN_OFF),
        up: active ? glow(paint('mat_led_on', (c) => ledStrip(c, true), { m: M.cyan, d: 2 })) : paint('mat_led_off', (c) => ledStrip(c, false), { m: M.cyan, d: 2 }),
        down: null,
    });
    box('bcg', [3, 10.5, 3], [7, 11.5, 4.5], {
        all: plate(M.black, 2),
        up: paint('bcg_top', (c) => { c.fill(M.black.base); c.bevel(M.black); c.hline(1, 1, c.w - 2, M.black.hi); c.rect(5, 1, 2, 1, M.brass.base); }, { m: M.black, d: 2 }),
        down: null,
    }, { item: false });
    box('bcg_bolt', [2.5, 10.5, 3.5], [3, 11, 4], { all: flat(M.chrome.base), down: null }, { item: false });
    box('spring', [3, 10.5, 5.5], [8, 11, 6.5], {
        all: flat(M.chrome.lo),
        up: paint('spring_top', (c) => { for (let x = 0; x < c.w; x++) c.vline(x, 0, c.h, x % 2 ? M.chrome.lo : M.chrome.hi); }, { m: M.chrome, d: 2 }),
        down: null,
    }, { item: false });
    box('mat_mag', [9, 10.5, 3], [12.5, 11, 4.5], {
        all: flat(M.tan.lo),
        up: paint('mag_top', (c) => { c.fill(M.tan.base); c.bevel(M.tan); for (let x = 2; x < c.w - 1; x += 2) c.vline(x, 1, c.h - 2, M.tan.lo); }, { m: M.tan, d: 2 }),
        down: null,
    }, { item: false });
    for (const k of ['b1', 'b2', 'b3', 'b4']) {
        const [x0, z0, x1, z1] = parts[k];
        box('case_' + k, [x0, 10.5, z0], [x1 - 0.5, 11, z1], { all: flat(M.brass.base), up: flat(M.brass.hi), down: null }, { item: false });
        box('tip_' + k, [x1 - 0.5, 10.5, z0], [x1, 11, z1], { all: flat(M.copper.base), up: flat(M.copper.hi), down: null, west: null }, { item: false });
    }

    // ---------------- 右前: 控制台
    box('console', [21, 10, 1], [30, 11, 5], {
        all: plate(M.frame, 2),
        north: paint(active ? 'console_n_on' : 'console_n', (c) => {
            c.fill(M.frame.base); c.hline(0, 0, c.w, M.frame.hi); c.hline(0, c.h - 1, c.w, M.frame.lo);
            c.px(2, 1, active ? C('#6ff08e') : C('#3a7a4a'));
            c.px(4, 1, active ? C('#ffb040') : C('#8a6a3a'));
            c.rect(c.w - 4, 0, 2, 2, M.red.base); c.px(c.w - 4, 0, M.red.hi);
        }, { m: M.frame, d: 2 }),
        down: null,
    });
    box('console_riser', [22, 11, 4], [29, 12.5, 5], { all: plate(M.frame, 2), down: null }, { item: false });
    box('console_screen', [21.5, 11, 1.5], [29.5, 11.5, 5], {
        all: flat(M.frame.lo),
        up: glow(paint(active ? 'screen_on' : 'screen_idle', (c) => screen(c, active), { m: M.frame, d: 2 })),
        north: flat(M.frame.base),
    }, { rot: { origin: [25.5, 11, 1.5], axis: 'x', angle: -22.5 } });

    // ---------------- 中央: 斜置夹具板 + 步枪
    for (const p of gunParts(active)) {
        const e = box(p.name, [p.x0, GUN.py + p.v0, GUN.pz + p.w0], [p.x1, GUN.py + p.v1, GUN.pz + p.w1], p.faces, { rot: GUN_ROT, item: false });
        e.rifle = p.rifle;
        e.gun = p;
    }

    // ---------------- 后右: 机械臂设备台 + 换刀座
    const S = ARM.shoulder;
    box('deck', [19.5, 10, 17], [31, 11.5, 29], {
        north: paint('deck_n', (c) => { c.hazard(0, 0, c.w, c.h, 6); c.hline(0, 0, c.w, mix(HAZ_Y, [255, 255, 255], 0.3)); }, { m: M.yellow, d: 2 }),
        east: paint('deck_side', deckSide, { m: M.frame, d: 2 }),
        west: paint('deck_side', deckSide, { m: M.frame, d: 2 }),
        up: paint('deck_top', (c) => deckTop(c, (S[0] - 19.5) * 2, (S[2] - 17) * 2), { m: M.frame, d: 2 }),
        south: null, down: null,
    });
    const leds = active ? ['#6ff08e', '#ffb040', '#ffb040'] : ['#4fd070', null, null];
    leds.forEach((col, i) => {
        const x0 = 23 + i;
        box('deck_led' + i, [x0, 11.5, 17.5], [x0 + 0.5, 12, 18], { all: col ? glow(flat(col)) : flat('#3a3f46'), down: null }, { item: false });
    });
    box('tc_post', [PED.x - 0.75, 11.5, PED.z - 0.75], [PED.x + 0.75, PED.top - 0.5, PED.z + 0.75], {
        all: paint('tc_post', (c) => { c.fill(M.frame.base); c.bevel(M.frame); c.hline(0, 1, c.w, M.yellow.base); c.hline(0, 2, c.w, HAZ_K); }, { m: M.frame, d: 2 }),
        down: null, up: null,
    }, { item: false });
    box('tc_cap', [PED.x - 2.25, PED.top - 0.5, PED.z - 1.5], [PED.x + 2.25, PED.top, PED.z + 1.5], {
        all: flat(M.frame.lo),
        north: flat(M.cyan.lo), south: flat(M.cyan.lo),
        up: active ? glow(paint('tc_top_on', (c) => dockPad(c, true), { m: M.frame, d: 2 })) : paint('tc_top_idle', (c) => dockPad(c, false), { m: M.frame, d: 2 }),
    }, { item: false });

    // ---------------- 后左: 弹药箱 (降矮) + 零件盒 (前移, 不再挡墙) + 立柱工作灯
    box('ammo', [3, 10, 17.5], [8.5, 12, 21], {
        all: paint('ammo_side', (c) => { c.fill(M.olive.base); c.bevel(M.olive); c.rect(2, 1, 2, 2, M.olive.dk); c.px(2, 1, M.olive.lo); }, { m: M.olive, d: 2 }),
        north: paint('ammo_n', ammoFront, { m: M.olive, d: 2 }),
        up: paint('ammo_top', (c) => { c.fill(M.olive.base); c.bevel(M.olive); c.hline(1, 2, c.w - 2, M.olive.lo); c.rect(c.w - 3, 3, 2, 2, M.olive.dk); }, { m: M.olive, d: 2 }),
        down: null,
    }, { item: false });
    box('ammo_handle', [4.75, 12, 19], [6.75, 12.5, 19.5], { all: flat(M.rubber.base), up: flat(M.rubber.hi), down: null }, { item: false });
    const bins = [[3, M.yellow], [7, M.cyan], [11, M.orange]];
    for (const [x0, m] of bins) {
        box('bin_' + m.name, [x0, 10, 23.5], [x0 + 3.5, 11.5, 26.5], {
            all: flat(m.lo),
            north: paint('bin_n_' + m.name, (c) => { c.fill(m.base); c.bevel(m); c.rect(1, 1, 4, 1, LABEL); c.px(2, 1, M.frame.lo); c.px(3, 1, M.frame.lo); }, { m, d: 2 }),
            up: paint('bin_top_' + m.name, (c) => binTop(c, m), { m, d: 2 }),
            down: null,
        }, { item: false });
    }
    // 工作灯: 西后角一根立柱, 顶上一只探照灯头 (绕 x -22.5°, 灯面朝北下方照向台面, 操作员正面就能看到灯面)
    box('lamp_base', [1, 10, 20.5], [3.5, 10.5, 23], { all: flat(M.frame.lo), up: plate(M.frame, 2), down: null }, { item: false });
    box('lamp_post', [1.75, 10.5, 21.25], [2.75, 14, 22.25], {
        all: paint('lamp_post', (c) => { c.fill(M.chrome.base); c.vline(0, 0, c.h, M.chrome.hi); c.vline(c.w - 1, 0, c.h, M.chrome.lo); }, { m: M.chrome, d: 2 }),
        down: null, up: null,
    }, { item: false });
    const LROT = { origin: [3.25, 14.75, 21.75], axis: 'x', angle: -22.5 };
    box('lamp_head', [0.75, 13.5, 19.5], [5.75, 15.75, 22.5], {
        all: paint('lamp_side', (c) => { c.fill(M.frame.base); c.bevel(M.frame); c.hline(0, 1, c.w, M.orange.base); for (let x = 3; x < c.w - 2; x += 2) c.px(x, c.h - 2, M.frame.dk); }, { m: M.frame, d: 2 }),
        up: paint('lamp_top', (c) => { c.fill(M.frame.base); c.bevel(M.frame); for (let y = 2; y < c.h - 2; y += 2) c.hline(2, y, c.w - 4, M.frame.dk); c.hline(0, 0, c.w, M.orange.base); }, { m: M.frame, d: 2 }),
        north: active ? glow(paint('lamp_on', (c) => lampFace(c, true), { m: M.steel, d: 2 })) : paint('lamp_off', (c) => lampFace(c, false), { m: M.steel, d: 2 }),
        down: flat(M.frame.dk),
    }, { rot: LROT, item: false });

    // ---------------- 后墙: 洞洞板、工具、顶梁、灯带
    const TOOLS = toolLayout();
    box('pegboard', [1.5, 10, 29], [30.5, 15, 31], {
        north: paint('peg_n', (c) => pegFace(c, TOOLS), { m: M.peg, d: 2 }),
        south: paint('wall_back_hi', wallBackHi, { m: M.frame }),
        up: flat(M.frame.base), down: null, east: null, west: null,
    });
    for (const t of TOOLS) {
        box('tool_' + t.name, [t.x0, t.y0, 28.5], [t.x1, t.y1, 29], {
            all: flat(t.m.lo), up: flat(t.m.hi), north: t.face ? paint('tool_' + t.key, t.face, { m: t.m, d: t.d || 2 }) : flat(t.m.base), south: null,
            down: flat(t.m.dk),
        }, { item: false, rot: t.rot || null });
    }
    box('cap', [0, 15, 28.5], [32, 16, 31.5], {
        all: plate(M.frame, 2),
        up: paint('cap_top', (c) => { c.fill(M.frame.base); c.bevel(M.frame); for (let x = 3; x < c.w - 2; x += 8) c.bolt(x, 1, M.frame); }, { m: M.frame }),
        north: paint('cap_n', (c) => { c.fill(M.frame.base); c.hline(0, 0, c.w, M.frame.hi); c.hline(0, c.h - 1, c.w, M.frame.lo); }, { m: M.frame, d: 2 }),
    });
    box('cap_led', [2, 15, 28], [30, 15.5, 28.5], { all: active ? glow(flat(CYAN_ON)) : flat(CYAN_OFF), up: flat(M.frame.lo), down: active ? glow(flat(CYAN_ON)) : flat(CYAN_OFF) }, { item: false });

    return E;
}

/** 夹具板与步枪 (枪内局部坐标)。rifle: true 的是枪本体 (物品模型会单独取出放大)。 */
function gunParts(active) {
    const P = [];
    const g = (name, x0, x1, v0, v1, w0, w1, faces, rifle = false) => P.push({ name, x0, x1, v0, v1, w0, w1, faces, rifle });
    const gp = (key, fn, m, d = 2) => paint(key, fn, { m, d });
    const back = itemOnly(flat(M.black.lo));   // 枪背面: 方块里贴着夹具板看不见, 只给物品模型用

    // 夹具板 (中灰钢, 网格 + 刻度), 橙色托沿, 前面一条全宽扫描灯带
    g('gun_board', 4, 28, -3.5, 1.5, 1, 1.5, {
        north: gp('gun_board_n', rackBoard, M.rack),
        up: flat(M.rack.hi), down: flat(M.rack.lo), east: flat(M.rack.lo), west: flat(M.rack.lo), south: flat(M.frame.base),
    });
    g('gun_lip', 4, 28, -4, -3.5, -1, 1.5, { all: flat(M.orange.base), up: flat(M.orange.hi), north: flat(M.orange.lo), down: flat(M.orange.dk) });
    g('gun_scan', 4, 28, -3.25, -2.25, 0.5, 1, {
        all: active ? glow(flat(CYAN_ON)) : flat(CYAN_OFF), south: null,
        north: active ? glow(gp('scan_on', (c) => scanBar(c, true), M.cyan)) : gp('scan_off', (c) => scanBar(c, false), M.cyan),
    });
    // V 形托块 (亮钢 + 橙色垫) 与压爪 (橙色; 工作时发光)
    for (const [nm, x0, v1] of [['gun_cradle_a', 10.5, -0.25], ['gun_cradle_b', 21, 0]]) {
        g(nm, x0, x0 + 2, v1 - 1, v1, -1, 1, {
            all: flat(M.chrome.lo), up: flat(M.orange.hi), south: null,
            north: gp('cradle_n', cradleFace, M.chrome, 4),
        });
    }
    for (const [nm, x0, v0] of [['gun_clamp_a', 11, 1.25], ['gun_clamp_b', 21.5, 1]]) {
        const m = active ? M.hot : M.orange;
        const f = (d) => (active ? glow(d) : d);
        g(nm, x0, x0 + 1.5, v0, v0 + 1, -1, 1, {
            all: f(flat(m.base)), up: f(flat(m.hi)), down: flat(m.lo), south: null,
            north: f(gp(active ? 'clamp_on' : 'clamp', (c) => clampFace(c, m), m, 4)),
        });
    }
    for (const [nm, x0] of [['gun_leg_a', 6], ['gun_leg_b', 25]]) {
        g(nm, x0, x0 + 1, -1.5, 1, 1.5, 3.4, { all: flat(M.frame.base), up: flat(M.frame.hi), north: null });
    }

    // 步枪 (枪口朝西)
    g('g_muzzle', 4.5, 5.5, 0, 1, -0.5, 0.5, { all: flat(M.black.base), south: back, north: gp('g_muzzle_n', (c) => { c.fill(M.black.base); c.hline(0, 0, c.w, M.black.hi); c.px(0, 1, M.black.dk); c.px(1, 1, M.black.dk); }, M.black) }, true);
    g('g_barrel', 5.5, 9, 0.25, 0.75, -0.25, 0.25, { all: flat(M.black.base), up: flat(M.black.hi), north: flat(M.black.hi), south: back }, true);
    g('g_handguard', 9, 14.5, -0.25, 1.25, -0.75, 0.75, {
        all: flat(M.gunm.base), up: gp('g_hg_top', (c) => { c.fill(M.gunm.base); for (let x = 0; x < c.w; x += 2) c.vline(x, 0, c.h, M.gunm.hi); }, M.gunm), down: flat(M.gunm.lo), south: back,
        north: gp('g_hg_n', (c) => { c.fill(M.gunm.base); c.bevel(M.gunm); for (let x = 1; x < c.w - 1; x += 3) c.rect(x, 1, 2, 1, M.gunm.dk); }, M.gunm),
        west: flat(M.gunm.lo),
    }, true);
    g('g_fsight', 9.5, 10, 1.25, 2.25, -0.25, 0.25, { all: flat(M.black.base), south: back }, true);
    g('g_receiver', 14.5, 20.5, -0.5, 1.5, -1, 1, {
        all: flat(M.black.base), up: flat(M.black.hi), down: flat(M.black.lo), south: back,
        north: active ? glow(gp('g_rcv_hot', (c) => receiverFace(c, true), M.black)) : gp('g_rcv_n', (c) => receiverFace(c, false), M.black),
    }, true);
    g('g_optic', 16, 19, 1.5, 2.5, -0.75, 0.75, {
        all: flat(M.black.base), up: flat(M.black.hi), south: back,
        north: gp('g_optic_n', (c) => { c.fill(M.black.base); c.hline(0, 0, c.w, M.black.hi); c.px(2, 1, M.cyan.base); c.px(3, 1, M.cyan.hi); }, M.black),
        west: flat(M.cyan.hi), east: flat(M.cyan.base),
    }, true);
    g('g_mag_a', 15.5, 17.5, -2.5, -0.5, -0.5, 0.5, {
        all: flat(M.tan.base), south: back, down: flat(M.tan.lo),
        north: gp('g_mag_n', (c) => { c.fill(M.tan.base); c.bevel(M.tan); for (let y = 1; y < c.h - 1; y += 2) c.hline(1, y, c.w - 2, M.tan.lo); }, M.tan),
    }, true);
    g('g_mag_b', 15, 17, -3.5, -2.5, -0.5, 0.5, {
        all: flat(M.tan.base), south: back, down: flat(M.tan.dk),
        north: gp('g_magb_n', (c) => { c.fill(M.tan.base); c.bevel(M.tan); c.hline(1, 1, c.w - 2, M.tan.lo); }, M.tan),
    }, true);
    g('g_grip_a', 19, 20, -1.5, -0.5, -0.5, 0.5, { all: flat(M.tan.base), south: back, north: gp('g_grip_n', (c) => { c.fill(M.tan.base); c.bevel(M.tan); }, M.tan) }, true);
    g('g_grip_b', 19.5, 20.5, -2.5, -1.5, -0.5, 0.5, { all: flat(M.tan.base), south: back, north: gp('g_grip_n', (c) => { c.fill(M.tan.base); c.bevel(M.tan); }, M.tan) }, true);
    g('g_guard', 17.5, 19, -1, -0.5, -0.25, 0.25, { all: flat(M.black.base), south: back }, true);
    g('g_tube', 20.5, 23, 0, 1, -0.5, 0.5, { all: flat(M.black.base), up: flat(M.black.hi), south: back }, true);
    g('g_stock', 23, 27, 0, 1.5, -0.75, 0.75, {
        all: flat(M.tan.base), up: flat(M.tan.hi), down: flat(M.tan.lo), south: back,
        north: gp('g_stock_n', (c) => { c.fill(M.tan.base); c.bevel(M.tan); c.rect(3, 1, 3, 1, M.tan.dk); }, M.tan),
    }, true);
    g('g_stock_toe', 25, 27, -1, 0, -0.75, 0.75, {
        all: flat(M.tan.base), down: flat(M.tan.lo), south: back,
        north: gp('g_stoe_n', (c) => { c.fill(M.tan.base); c.hline(0, c.h - 1, c.w, M.tan.lo); c.vline(c.w - 1, 0, c.h, M.tan.lo); c.vline(0, 0, c.h - 1, M.tan.hi); }, M.tan),
    }, true);
    g('g_buttpad', 27, 27.5, -1, 1.5, -0.75, 0.75, { all: flat(M.rubber.base), up: flat(M.rubber.hi), south: back }, true);
    if (active) {
        // 机匣顶面的焊点 (抓手下方), 2x1
        g('g_weld', 19, 20.5, 1.5, 2, -0.5, 0.5, { all: glow(flat('#ffb25a')), up: glow(flat('#fff4d6')), south: null, down: null });
    }
    return P;
}

// ------------------------------------------------------------ 画法
function pedSide(c) {
    const m = M.frame;
    c.fill(m.base); c.bevel(m);
    c.inset(m, 2, 1, c.w - 4, c.h - 2);
    for (const y of [2, 4]) { c.hline(4, y, 8, m.dk); c.hline(4, y + 1, 8, m.hi); }
    c.rect(c.w - 9, 2, 5, 2, LABEL); c.hline(c.w - 8, 2, 3, m.lo); c.px(c.w - 5, 3, M.orange.base);
    c.px(1, 1, m.hi); c.px(c.w - 2, 1, m.hi); c.px(1, c.h - 2, m.lo); c.px(c.w - 2, c.h - 2, m.lo);
}
function pedInner(c) { const m = M.frame; c.fill(m.lo); c.bevel({ hi: m.base, lo: m.dk }); }
function wallBack(c) {
    const m = M.frame;
    c.fill(m.base); c.bevel(m);
    for (let x = 7; x < c.w - 3; x += 8) { c.vline(x, 1, c.h - 2, m.lo); c.vline(x + 1, 1, c.h - 2, m.hi); }
}
function wallBackLo(c) {
    const m = M.frame;
    wallBack(c);
    for (let y = 2; y < 7; y += 2) { c.hline(10, y, 11, m.dk); c.hline(10, y + 1, 11, m.hi); }
    c.rect(2, 2, 3, 3, HAZ_Y); c.px(3, 3, HAZ_K); c.px(3, 2, HAZ_K);
    c.rect(24, 6, 3, 2, M.frame.dk); c.px(25, 6, M.cyan.base);
}
function wallBackHi(c) {
    const m = M.frame;
    wallBack(c);
    c.rect(2, 1, 5, 2, LABEL); c.hline(3, 1, 3, m.lo);
    c.hline(10, 2, 11, M.orange.base);
}
function drawers3(c) {
    const m = M.slate;
    c.fill(m.base);
    const tags = [M.cyan.base, M.orange.base, M.yellow.base];
    for (let i = 0; i < 3; i++) {
        const y0 = i * 4;
        c.hline(0, y0, c.w, m.hi);
        c.hline(0, y0 + 3, c.w, m.dk);
        c.vline(0, y0, 3, m.hi); c.vline(c.w - 1, y0, 3, m.lo);
        c.rect(2, y0 + 1, 4, 2, LABEL); c.hline(3, y0 + 1, 2, M.frame.lo);
        c.px(c.w - 3, y0 + 1, tags[i]);
    }
}
function partsGrid(c) {
    const m = M.slate;
    c.fill(m.base);
    const tags = [M.yellow.base, M.cyan.base, M.orange.base];
    const cw = c.w / 2;
    for (let r = 0; r < 3; r++) for (let k = 0; k < 2; k++) {
        const x0 = k * cw, y0 = r * 4;
        c.hline(x0, y0, cw, m.hi); c.vline(x0, y0, 4, m.hi);
        c.hline(x0, y0 + 3, cw, m.dk); c.vline(x0 + cw - 1, y0, 4, m.dk);
        c.rect(x0 + 2, y0 + 1, 2, 1, tags[(r + k) % 3]);
        c.hline(x0 + 5, y0 + 1, 4, m.lo); c.hline(x0 + 5, y0 + 2, 4, m.dk);
    }
}
function worktopTop(c) {
    const m = M.steel;
    c.fill(m.base);
    for (let y = 2; y < c.h - 1; y += 3) c.hline(1, y, c.w - 2, mix(m.base, m.hi, 0.35));
    c.bevel(m);
    c.vline(15, 1, c.h - 2, m.lo); c.vline(16, 1, c.h - 2, m.hi);
    for (let x = 2; x < c.w - 2; x++) { c.px(x, 1, x % 4 === 2 ? m.dk : m.lo); }
    for (const [x, y] of [[1, 3], [13, 3], [18, 3], [29, 3], [1, 26], [13, 26], [18, 26], [29, 26]]) c.bolt(x, y, m);
}
function worktopEdge(c) {
    const m = M.steel;
    c.fill(m.base);
    c.hline(0, 0, c.w, m.hi);
    c.hline(0, 2, c.w, M.orange.base);
    c.hline(0, c.h - 1, c.w, m.lo);
}
function matTop(c, MAT, parts) {
    const m = M.matg;
    c.fill(m.base);
    for (let x = 3; x < c.w; x += 4) c.vline(x, 0, c.h, mix(m.base, m.hi, 0.35));
    for (let y = 3; y < c.h; y += 4) c.hline(0, y, c.w, mix(m.base, m.hi, 0.35));
    c.bevel({ hi: m.hi, lo: m.dk });
    const line = C('#9fe6c6');
    const tex = (x, z) => [Math.round((x - MAT.x0) * 2), Math.round((z - MAT.z0) * 2)];
    const outline = (r, dashed) => {
        const [a0, b0] = tex(r[0], r[1]); const [a1, b1] = tex(r[2], r[3]);
        for (let x = a0 - 1; x <= a1; x++) { if (!dashed || x % 2 === 0) { c.px(x, b0 - 1, line); c.px(x, b1, line); } }
        for (let y = b0 - 1; y <= b1; y++) { if (!dashed || y % 2 === 0) { c.px(a0 - 1, y, line); c.px(a1, y, line); } }
    };
    outline([2.5, 3, 7, 4.5]); outline(parts.spring); outline(parts.mag);
    outline([9, 6, 12.5, 7.5]);
    outline(parts.slot, true);
    const [sa, sb] = tex(parts.slot[0], parts.slot[1]);
    c.hline(sa + 1, sb, 4, mix(line, m.base, 0.4));
}
function ledStrip(c, on) {
    // 定位灯条: 全宽灯管, 每 4 格一个定位刻度
    const a = on ? C('#b8fbff') : C('#35606a'), b = on ? CYAN_ON : CYAN_OFF, k = on ? C('#2fb8c8') : C('#1d3a40');
    c.fill(b); c.hline(0, 0, c.w, a);
    for (let x = 1; x < c.w; x += 4) c.px(x, 1, k);
}
function screen(c, on) {
    const bez = C('#1b2027');
    c.fill(bez);
    const bg = on ? C('#0f3a22') : C('#0b2c35');
    const fg = on ? C('#6cf59a') : C('#2f94a6');
    const fg2 = on ? C('#c8ffd8') : C('#1f6474');
    c.rect(1, 1, c.w - 2, c.h - 2, bg);
    const art = [
        '....##......',
        '#.########..',
        '###########.',
        '.....#.#....',
        '.....#......',
    ];
    art.forEach((row, y) => [...row].forEach((ch, x) => { if (ch === '#') c.px(2 + x, 1 + y, fg); }));
    if (on) {
        c.px(c.w - 2, 1, fg2); c.px(c.w - 2, 2, fg); c.px(c.w - 2, 3, fg);
        c.hline(2, c.h - 1, c.w - 4, fg);
    } else {
        c.px(c.w - 2, c.h - 2, fg2);
    }
}
function rackBoard(c) {
    const m = M.rack;
    c.fill(m.base);
    const gl = mix(m.base, m.hi, 0.45);
    for (let x = 3; x < c.w - 1; x += 4) c.vline(x, 1, c.h - 2, gl);
    for (let y = 3; y < c.h - 1; y += 4) c.hline(1, y, c.w - 2, gl);
    for (let x = 2; x < c.w - 2; x += 2) c.px(x, 1, x % 8 === 2 ? M.cyan.lo : m.lo);
    c.bevel(m);
    c.bolt(1, c.h - 3, m); c.bolt(c.w - 3, c.h - 3, m);
}
function scanBar(c, on) {
    const a = on ? C('#d8feff') : C('#3b6d77'), b = on ? CYAN_ON : CYAN_OFF;
    c.fill(b); c.hline(0, 0, c.w, a);
    if (on) for (let x = 3; x < c.w; x += 6) c.px(x, 1, C('#ffffff'));
}
function cradleFace(c) {
    const m = M.chrome;
    c.fill(m.base); c.bevel(m);
    c.hline(0, 0, c.w, M.orange.base);
    // V 形槽
    c.rect(2, 0, 4, 1, M.frame.dk); c.rect(3, 1, 2, 1, M.frame.dk);
    c.px(1, c.h - 2, m.lo); c.px(c.w - 2, c.h - 2, m.lo);
}
function clampFace(c, m) {
    c.fill(m.base); c.bevel(m);
    c.rect(2, 1, 2, 2, m.dk); c.px(2, 1, m.lo);
}
function receiverFace(c, hot) {
    const m = M.black;
    c.fill(m.base); c.bevel(m);
    // 北面贴图左端 = 东 (x=20.5, 枪托侧)
    c.rect(3, 1, 3, 1, m.dk);               // 抛壳窗
    c.px(1, 1, M.brass.lo);                   // 助推器
    c.vline(7, 2, 2, m.dk);                   // 弹匣井前沿
    c.px(2, 2, m.hi); c.px(4, 2, m.hi);       // 销钉
    c.hline(1, 2, 1, M.cyan.base);            // 快慢机点缀
    if (hot) {
        // 激光刻印中的炽热区 (约 2.5x2 模型像素): 白芯 + 黄 + 橙边
        const x0 = 3, w = 5;
        c.rect(x0, 0, w, 4, C('#f07a1c'));
        c.rect(x0 + 1, 0, w - 2, 4, C('#ffc24a'));
        c.rect(x0 + 1, 1, w - 2, 2, C('#fff6de'));
        c.px(x0 + 2, 1, C('#ffffff'));
    }
}
function deckSide(c) {
    const m = M.frame;
    c.fill(m.base); c.bevel(m);
    for (let x = 3; x < c.w - 3; x += 2) c.px(x, 1, m.dk);
}
function deckTop(c, cx, cy) {
    const m = M.frame;
    c.fill(m.base); c.bevel(m);
    c.inset(m, 2, 2, c.w - 4, c.h - 4);
    for (const [x, y] of [[1, 1], [c.w - 3, 1], [1, c.h - 3], [c.w - 3, c.h - 3]]) c.bolt(x, y, m);
    for (let y = 0; y < c.h; y++) for (let x = 0; x < c.w; x++) {
        const r = Math.hypot(x + 0.5 - cx, y + 0.5 - cy);
        if (r >= 6.4 && r < 7.4) c.px(x, y, ((Math.atan2(y + 0.5 - cy, x + 0.5 - cx) * 8 / Math.PI) | 0) % 2 ? HAZ_K : HAZ_Y);
        else if (r >= 7.4 && r < 8.1) c.px(x, y, m.dk);
    }
}
function dockPad(c, on) {
    const m = M.frame;
    c.fill(m.lo); c.bevel({ hi: m.base, lo: m.dk });
    const ring = on ? C('#6ff0ff') : C('#2f7e8a');
    // 两个爪位 (左右) + 中间定位槽
    c.rect(1, 2, 2, 2, ring); c.rect(c.w - 3, 2, 2, 2, ring);
    c.hline(3, 3, c.w - 6, on ? C('#dffcff') : m.dk);
    c.px(Math.floor(c.w / 2), 1, ring); c.px(Math.floor(c.w / 2), c.h - 2, ring);
}
function ammoFront(c) {
    const m = M.olive;
    c.fill(m.base); c.bevel(m);
    const y = C('#e8d25a');
    [...'##.#.###'].forEach((k, i) => { if (k === '#') c.px(2 + i, 1, y); });
    [...'###.##'].forEach((k, i) => { if (k === '#') c.px(2 + i, 2, y); });
    c.rect(c.w - 2, 1, 1, 2, m.dk);
}
function binTop(c, m) {
    c.fill(C('#2b2f36'));
    c.hline(0, 0, c.w, m.hi); c.hline(0, c.h - 1, c.w, m.base); c.vline(0, 0, c.h, m.base); c.vline(c.w - 1, 0, c.h, m.lo);
    const bits = [[2, 2, M.brass.hi], [4, 3, M.chrome.base], [5, 2, M.brass.base], [2, 4, M.chrome.hi], [5, 4, M.brass.lo], [3, 4, M.chrome.lo]];
    for (const [x, y, col] of bits) c.px(x, y, col);
}
function lampFace(c, on) {
    const rim = M.frame.dk;
    c.fill(rim);
    const a = on ? C('#fff3cf') : C('#77704f');
    const b = on ? C('#ffffff') : C('#8e8466');
    c.rect(1, 1, c.w - 2, c.h - 2, a);
    c.rect(3, 2, c.w - 6, c.h - 4, b);
    if (!on) for (let x = 2; x < c.w - 2; x += 2) c.px(x, 1, C('#5f5a42'));
}

// 洞洞板上的工具布局 (世界坐标, 北面 z=28.5)。可见带 y 11..15; 东段 (x>21) 会被机械臂挡住, 只放次要的备用弹匣与卡尺。
function toolLayout() {
    const T = [];
    const t = (name, key, x0, y0, x1, y1, m, face, rot, d) => T.push({ name, key, x0, y0, x1, y1, m, face, rot, d });
    const plain = (m) => (c) => { c.fill(m.base); c.bevel(m); };
    // 锤子: 深钢锤头 + 红柄
    t('hammer_head', 'hammer_head', 2.5, 13.75, 6, 14.75, M.gunm, (c) => { c.fill(M.gunm.base); c.bevel(M.gunm); c.rect(0, 0, 2, c.h, M.chrome.base); c.vline(0, 0, c.h, M.chrome.hi); });
    t('hammer_handle', 'hammer_handle', 3.75, 11, 4.75, 13.75, M.red, (c) => { c.fill(M.red.base); c.bevel(M.red); c.rect(0, c.h - 2, c.w, 2, M.rubber.base); });
    // 大号开口扳手, 斜挂 45°
    const WR = { origin: [8.75, 12.75, 28.75], axis: 'z', angle: 45 };
    t('wrench_shaft', 'wrench_shaft', 8.25, 10.75, 9.25, 13.75, M.chrome, (c) => { c.fill(M.chrome.base); c.vline(0, 0, c.h, M.chrome.hi); c.vline(c.w - 1, 0, c.h, M.chrome.lo); }, WR);
    t('wrench_jaw', 'wrench_jaw', 7.75, 13.75, 9.75, 14.75, M.chrome, (c) => { c.fill(M.chrome.base); c.bevel(M.chrome); c.rect(1, 0, 2, 1, M.frame.dk); }, WR);
    // 手枪剪影 (枪口朝西), 握把后倾 22.5°
    t('pistol_slide', 'pistol_slide', 10.5, 13.5, 15, 14.75, M.black, (c) => { c.fill(M.black.base); c.bevel(M.black); for (let x = 1; x < 5; x += 2) c.px(x, 1, M.black.dk); c.px(c.w - 2, 0, M.black.hi); });
    t('pistol_grip', 'pistol_grip', 13.25, 11.25, 14.75, 13.5, M.black, (c) => { c.fill(M.black.base); c.bevel(M.black); for (let y = 1; y < c.h - 1; y += 2) c.hline(1, y, c.w - 2, M.black.dk); }, { origin: [14, 13.5, 28.75], axis: 'z', angle: 22.5 });
    t('pistol_guard', 'pistol_guard', 12, 13, 13.25, 13.5, M.black, null);
    // 两把螺丝刀 (橙 / 青柄)
    t('sd_a_handle', 'sd_orange', 17, 13, 18, 14.75, M.orange, plain(M.orange));
    t('sd_a_shaft', 'sd_shaft', 17.25, 11, 17.75, 13, M.chrome, null);
    t('sd_b_handle', 'sd_cyan', 19, 13, 20, 14.75, M.cyan, plain(M.cyan));
    t('sd_b_shaft', 'sd_shaft', 19.25, 11, 19.75, 13, M.chrome, null);
    // 卡尺与备用弹匣 (东段)
    t('cal_beam', 'cal_beam', 22, 14, 27, 14.5, M.chrome, null);
    t('cal_jaw', 'cal_jaw', 22, 12.5, 22.5, 14, M.chrome, null);
    t('cal_slider', 'cal_slider', 24, 12.75, 25.5, 14, M.chrome, (c) => { c.fill(M.chrome.base); c.bevel(M.chrome); c.px(1, 1, C('#e8d060')); });
    t('mag_a', 'spare_mag', 28, 11.5, 29.25, 14.25, M.tan, (c) => { c.fill(M.tan.base); c.bevel(M.tan); for (let y = 1; y < c.h - 1; y += 2) c.px(1, y, M.tan.lo); }, null, 2);
    return T;
}
function pegFace(c, tools) {
    const m = M.peg;
    c.fill(m.base);
    // 工具轮廓影: 北面贴图 u = (30.5 - x) * 2, v = (15 - y) * 2; 旋转的工具按旋转后的矩形取点
    const shadow = C('#958b73');
    for (let j = 0; j < c.h; j++) for (let i = 0; i < c.w; i++) {
        const x = 30.5 - (i + 0.5) / 2, y = 15 - (j + 0.5) / 2;
        for (const t of tools) {
            let px = x, py = y;
            if (t.rot) {
                const a = -t.rot.angle * Math.PI / 180, dx = x - t.rot.origin[0], dy = y - t.rot.origin[1];
                px = t.rot.origin[0] + dx * Math.cos(a) - dy * Math.sin(a);
                py = t.rot.origin[1] + dx * Math.sin(a) + dy * Math.cos(a);
            }
            if (px > t.x0 - 0.4 && px < t.x1 + 0.4 && py > t.y0 - 0.4 && py < t.y1 + 0.4) { c.px(i, j, shadow); break; }
        }
    }
    for (let y = 2; y < c.h - 1; y += 4) for (let x = 1; x < c.w; x += 4) {
        const cur = c.get(x, y);
        if (cur[0] === m.base[0] && cur[1] === m.base[1]) { c.px(x, y, m.dk); c.px(x + 1, y + 1, m.hi); }
    }
    c.bevel(m);
}
function particleTex() {
    const c = new Canvas(16, 16);
    const m = M.frame;
    c.fill(m.base);
    for (let y = 3; y < 15; y += 4) c.hline(1, y, 14, mix(m.base, m.hi, 0.35));
    c.bevel(m);
    c.vline(7, 1, 14, m.lo); c.vline(8, 1, 14, m.hi);
    c.bolt(2, 2, m); c.bolt(12, 2, m); c.bolt(2, 12, m); c.bolt(12, 12, m);
    c.hline(1, 14, 14, M.orange.lo);
    return c;
}

// ------------------------------------------------------------ 可见面剔除 / 共面检查
const OUT_DIR = { north: [2, -1], south: [2, 1], west: [0, -1], east: [0, 1], down: [1, -1], up: [1, 1] };
function faceRect(face, e) {
    const [ax, sg] = OUT_DIR[face];
    const others = [0, 1, 2].filter((a) => a !== ax);
    return { ax, sg, v: sg > 0 ? e.to[ax] : e.from[ax], a: others[0], b: others[1], a0: e.from[others[0]], a1: e.to[others[0]], b0: e.from[others[1]], b1: e.to[others[1]] };
}
function cullHidden(E) {
    const eps = 1e-6;
    for (const e of E) {
        for (const face of Object.keys(e.faces)) {
            if (face === 'down' && e.from[1] <= 0 && !e.rot) { delete e.faces[face]; continue; }
            if (e.rot) continue;
            const r = faceRect(face, e);
            for (const b of E) {
                if (b === e || b.rot) continue;
                const inside = r.sg > 0 ? (b.from[r.ax] <= r.v + eps && b.to[r.ax] > r.v + eps) : (b.from[r.ax] < r.v - eps && b.to[r.ax] >= r.v - eps);
                if (!inside) continue;
                if (b.from[r.a] <= r.a0 + eps && b.to[r.a] >= r.a1 - eps && b.from[r.b] <= r.b0 + eps && b.to[r.b] >= r.b1 - eps) { delete e.faces[face]; break; }
            }
        }
    }
}
function zfightCheck(E, label) {
    const errs = [];
    const rk = (e) => (e.rot ? JSON.stringify(e.rot) : '');
    for (let i = 0; i < E.length; i++) for (let j = i + 1; j < E.length; j++) {
        const p = E[i], q = E[j];
        if (rk(p) !== rk(q)) continue;
        for (const face of Object.keys(p.faces)) {
            if (!q.faces[face]) continue;
            const r = faceRect(face, p), s = faceRect(face, q);
            if (Math.abs(r.v - s.v) > 1e-6) continue;
            const oa = Math.min(r.a1, s.a1) - Math.max(r.a0, s.a0), ob = Math.min(r.b1, s.b1) - Math.max(r.b0, s.b0);
            if (oa > 1e-6 && ob > 1e-6) errs.push(`${label}: z-fight ${p.name}.${face} / ${q.name}.${face}`);
        }
    }
    return errs;
}

// ------------------------------------------------------------ 图集
const PARTS = { main: [0, 0], side: [16, 0], back: [0, 16], back_side: [16, 16] };
const SPLIT = 16;

/** 为每个面决定贴图密度 d 与区域 key; 太小或无法整除时退化为纯色块。 */
function planFace(e, face, desc) {
    if (desc.flat) return { flat: true, col: desc.col, emit: !!desc.emit, itemOnly: !!desc.itemOnly };
    const [fw, fh] = faceDims(face, e.from, e.to);
    const cuts = [];
    if (e.from[0] < SPLIT && e.to[0] > SPLIT) cuts.push(SPLIT - e.from[0]);
    if (e.from[2] < SPLIT && e.to[2] > SPLIT) cuts.push(SPLIT - e.from[2]);
    const ok = (d) => [fw, fh, ...cuts].every((v) => Math.abs(v * d - Math.round(v * d)) < 1e-6);
    let d = desc.d;
    while (!ok(d) && d < 4) d *= 2;
    const tw = Math.round(fw * d), th = Math.round(fh * d);
    if (!ok(d) || tw < 2 || th < 2) return { flat: true, col: desc.m.base, emit: !!desc.emit, itemOnly: !!desc.itemOnly };
    return { flat: false, key: `${desc.key}|${tw}x${th}`, fn: desc.fn, tw, th, d, emit: !!desc.emit, itemOnly: !!desc.itemOnly };
}

function buildAtlas(scenes) {
    const regions = new Map();
    const swatches = new Map();
    for (const E of scenes) for (const e of E) {
        e.plan = {};
        for (const [face, desc] of Object.entries(e.faces)) {
            const p = planFace(e, face, desc);
            e.plan[face] = p;
            if (p.flat) {
                const k = p.col.join(',');
                if (!swatches.has(k)) swatches.set(k, { col: p.col, w: 4, h: 4 });
                p.region = swatches.get(k);
            } else {
                if (!regions.has(p.key)) regions.set(p.key, { key: p.key, fn: p.fn, w: p.tw, h: p.th });
                p.region = regions.get(p.key);
            }
        }
    }
    const items = [...regions.values(), ...swatches.values()].sort((a, b) => b.h - a.h || b.w - a.w || (a.key || '').localeCompare(b.key || ''));
    for (const size of [128, 256]) {
        let x = 0, y = 0, rowH = 0, fail = false;
        for (const it of items) {
            if (x + it.w > size) { x = 0; y += rowH; rowH = 0; }
            if (y + it.h > size || it.w > size) { fail = true; break; }
            it.x = x; it.y = y; x += it.w; rowH = Math.max(rowH, it.h);
        }
        if (!fail) {
            const atlas = new Canvas(size, size);
            atlas.fill(BOTTOM);
            for (const it of items) {
                const cv = new Canvas(it.w, it.h);
                if (it.col) cv.fill(it.col); else it.fn(cv, it);
                for (let j = 0; j < it.h; j++) for (let i = 0; i < it.w; i++) atlas.px(it.x + i, it.y + j, cv.get(i, j));
            }
            return { size, atlas, items, regions: regions.size, swatches: swatches.size };
        }
    }
    throw new Error('atlas overflow');
}

function faceLocal(face, e, s) {
    const F = e.from, T = e.to;
    switch (face) {
        case 'north': return [T[0] - s.to[0], T[1] - s.to[1], T[0] - s.from[0], T[1] - s.from[1]];
        case 'south': return [s.from[0] - F[0], T[1] - s.to[1], s.to[0] - F[0], T[1] - s.from[1]];
        case 'west': return [s.from[2] - F[2], T[1] - s.to[1], s.to[2] - F[2], T[1] - s.from[1]];
        case 'east': return [T[2] - s.to[2], T[1] - s.to[1], T[2] - s.from[2], T[1] - s.from[1]];
        case 'up': return [s.from[0] - F[0], s.from[2] - F[2], s.to[0] - F[0], s.to[2] - F[2]];
        case 'down': return [s.from[0] - F[0], T[2] - s.to[2], s.to[0] - F[0], T[2] - s.from[2]];
    }
}
const r4 = (v) => Math.round(v * 10000) / 10000;
function faceUv(p, face, e, s, A) {
    const R = p.region;
    if (p.flat) return [R.x + 1, R.y + 1, R.x + 3, R.y + 3].map((v) => r4(v * 16 / A));
    const [u0, v0, u1, v1] = faceLocal(face, e, s);
    return [R.x + u0 * p.d, R.y + v0 * p.d, R.x + u1 * p.d, R.y + v1 * p.d].map((v) => r4(v * 16 / A));
}
function faceJson(p, uv) {
    const f = { uv, texture: '#atlas' };
    if (p.emit) f.forge_data = { block_light: 15, sky_light: 15 };
    return f;
}

/** 把世界元素切到某一格, 返回局部坐标元素 JSON。 */
function partElements(E, part, A) {
    const [ox, oz] = PARTS[part];
    const out = [];
    for (const e of E) {
        const x0 = Math.max(e.from[0], ox), x1 = Math.min(e.to[0], ox + 16);
        const z0 = Math.max(e.from[2], oz), z1 = Math.min(e.to[2], oz + 16);
        if (x1 - x0 <= 1e-6 || z1 - z0 <= 1e-6) continue;
        if (e.rot && e.rot.axis !== 'x' && (x0 !== e.from[0] || x1 !== e.to[0])) throw new Error('rotated element split across x: ' + e.name);
        if (e.rot && e.rot.axis !== 'z' && (z0 !== e.from[2] || z1 !== e.to[2])) throw new Error('rotated element split across z: ' + e.name);
        const s = { from: [x0, e.from[1], z0], to: [x1, e.to[1], z1] };
        const faces = {};
        for (const [face, p] of Object.entries(e.plan)) {
            if (p.itemOnly) continue;
            if (face === 'east' && x1 < e.to[0]) continue;
            if (face === 'west' && x0 > e.from[0]) continue;
            if (face === 'south' && z1 < e.to[2]) continue;
            if (face === 'north' && z0 > e.from[2]) continue;
            faces[face] = faceJson(p, faceUv(p, face, e, s, A));
        }
        if (!Object.keys(faces).length) continue;
        const el = { from: [r4(x0 - ox), r4(e.from[1]), r4(z0 - oz)], to: [r4(x1 - ox), r4(e.to[1]), r4(z1 - oz)] };
        if (e.rot) {
            const o = [e.rot.origin[0] - ox, e.rot.origin[1], e.rot.origin[2] - oz];
            if (e.rot.axis === 'x') o[0] = Math.min(16, Math.max(0, o[0]));
            if (e.rot.axis === 'z') o[2] = Math.min(16, Math.max(0, o[2]));
            el.rotation = { angle: e.rot.angle, axis: e.rot.axis, origin: o.map(r4) };
        }
        el.faces = faces;
        el.__name = e.name;
        out.push(el);
    }
    return out;
}

// ------------------------------------------------------------ 机械臂: 盒子、正向运动学、反解、碰撞扫描
// [部件, 材质, 装饰, x, y, z, w, h, d] (ModelPart 坐标, y 向下)。全部整数尺寸, 贴图按整像素绘制。
function armBoxes() {
    return [
        ['shoulder', 'frame', 'base', -2.5, -1, -2.5, 5, 1, 5],
        ['shoulder', 'orange', 'turret', -2, -3, -2, 4, 2, 4],
        ['shoulder', 'orange', 'cheek', -1.5, -5, -2, 3, 2, 1],
        ['shoulder', 'orange', 'cheek', -1.5, -5, 1, 3, 2, 1],
        ['upper_arm', 'dark', 'hub', -1, -1, -1, 2, 2, 2],
        ['upper_arm', 'orange', 'upper', -1, -7, -1, 2, 6, 2],
        ['elbow', 'dark', 'joint', -1.5, -1.5, -1.5, 3, 3, 3],
        ['elbow', 'cyan', 'cap', -1, -1, -2, 2, 2, 4],
        ['forearm', 'orange', 'fore', -1, 1, -1, 2, 6, 2],
        ['wrist', 'dark', 'hub', -1, -1, -1, 2, 2, 2],
        ['tool', 'cyan', 'ring', -1.5, 1, -1.5, 3, 1, 3],
        ['gripper', 'dark', 'bar', -2, 0, -1, 4, 1, 2],
        ['left_claw', 'steel', 'claw', -0.5, 0, -0.5, 1, 2, 1],
        ['right_claw', 'steel', 'claw', -0.5, 0, -0.5, 1, 2, 1],
    ];
}
const ARM_TREE = [['shoulder', 'root'], ['upper_arm', 'shoulder'], ['elbow', 'upper_arm'], ['forearm', 'elbow'], ['wrist', 'forearm'], ['tool', 'wrist'], ['gripper', 'tool'], ['left_claw', 'gripper'], ['right_claw', 'gripper']];
function armOffsets() {
    const S = ARM.shoulder;
    return {
        shoulder: [S[0] - 8, 16 - S[1], S[2] - 8], upper_arm: [0, -ARM.jointUp, 0], elbow: [0, -ARM.L1, 0], forearm: [0, 0, 0],
        wrist: [0, ARM.L2, 0], tool: [0, 0, 0], gripper: [0, ARM.gripY, 0], left_claw: [-ARM.clawX, ARM.clawY, 0], right_claw: [ARM.clawX, ARM.clawY, 0],
    };
}
// 4x4 行主序仿射矩阵
const mMul = (a, b) => { const r = new Array(16).fill(0); for (let i = 0; i < 4; i++) for (let j = 0; j < 4; j++) for (let k = 0; k < 4; k++) r[i * 4 + j] += a[i * 4 + k] * b[k * 4 + j]; return r; };
const mT = (x, y, z) => [1, 0, 0, x, 0, 1, 0, y, 0, 0, 1, z, 0, 0, 0, 1];
const mS = (x, y, z) => [x, 0, 0, 0, 0, y, 0, 0, 0, 0, z, 0, 0, 0, 0, 1];
const mRx = (a) => { const c = Math.cos(a), s = Math.sin(a); return [1, 0, 0, 0, 0, c, -s, 0, 0, s, c, 0, 0, 0, 0, 1]; };
const mRy = (a) => { const c = Math.cos(a), s = Math.sin(a); return [c, 0, s, 0, 0, 1, 0, 0, -s, 0, c, 0, 0, 0, 0, 1]; };
const mRz = (a) => { const c = Math.cos(a), s = Math.sin(a); return [c, -s, 0, 0, s, c, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]; };
const mApply = (m, p) => [m[0] * p[0] + m[1] * p[1] + m[2] * p[2] + m[3], m[4] * p[0] + m[5] * p[1] + m[6] * p[2] + m[7], m[8] * p[0] + m[9] * p[1] + m[10] * p[2] + m[11]];
function mInv(m) {
    const a = m[0], b = m[1], c = m[2], d = m[4], e = m[5], f = m[6], g = m[8], h = m[9], i = m[10];
    const det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
    const r = [
        (e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det, 0,
        (f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det, 0,
        (d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det, 0,
        0, 0, 0, 1,
    ];
    const t = [m[3], m[7], m[11]];
    r[3] = -(r[0] * t[0] + r[1] * t[1] + r[2] * t[2]);
    r[7] = -(r[4] * t[0] + r[5] * t[1] + r[6] * t[2]);
    r[11] = -(r[8] * t[0] + r[9] * t[1] + r[10] * t[2]);
    return r;
}
/** pose: {part: [xRot, yRot, zRot]} → 每个部件的世界矩阵 (朝北, 世界像素)。与 render(): translate(0.5,1,0.5) + scale(1,-1,1) 一致。 */
function armMatrices(pose) {
    const off = armOffsets();
    const Mx = { root: mMul(mT(8, 16, 8), mS(1, -1, 1)) };
    for (const [p, parent] of ARM_TREE) {
        const [rx, ry, rz] = pose[p] || [0, 0, 0];
        Mx[p] = mMul(mMul(Mx[parent], mT(...off[p])), mMul(mRz(rz), mMul(mRy(ry), mRx(rx))));
    }
    return Mx;
}
function ik2(dx, dy) {
    const { L1, L2 } = ARM;
    const D = Math.hypot(dx, dy);
    if (D > L1 + L2 - 0.05 || D < Math.abs(L1 - L2) + 0.05) return null;
    const a = (L1 * L1 - L2 * L2 + D * D) / (2 * D);
    const hh = Math.sqrt(Math.max(0, L1 * L1 - a * a));
    const ux = dx / D, uy = dy / D;
    const c1 = [a * ux - hh * uy, a * uy + hh * ux], c2 = [a * ux + hh * uy, a * uy - hh * ux];
    const E = c1[1] > c2[1] ? c1 : c2;
    const u = Math.atan2(E[0], E[1]);
    const phi = Math.atan2(-(dx - E[0]), -(dy - E[1]));
    let f = phi - u;
    while (f > Math.PI) f -= 2 * Math.PI;
    while (f <= -Math.PI) f += 2 * Math.PI;
    return { u, f };
}
/** 腕部枢轴的世界位置 → 偏航 + 两个关节角。 */
function solveWrist(W) {
    const S = ARM.shoulder, J = [S[0], S[1] + ARM.jointUp, S[2]];
    const tx = W[0] - J[0], tz = W[2] - J[2];
    const r = Math.hypot(tx, tz);
    const k = ik2(-r, W[1] - J[1]);
    if (!k) return null;
    return { yaw: r < 1e-9 ? 0 : Math.atan2(tz, -tx), u: k.u, f: k.f, yawArgs: [-tz, -tx] };
}
const easedStep = (p, a, b) => { const t = Math.min(1, Math.max(0, (p - a) / (b - a))); return t * t * (3 - 2 * t); };
const lerp = (t, a, b) => a + (b - a) * t;
/** 与 applyPose 相同: phase < 0 表示 isAnimating() == false 的静止待机。 */
function javaPose(phase, K, WIN, over = {}) {
    let turn = 0, reach = 0, grip = 0, pulse = 0;
    if (phase >= 0) {
        const mw = (w) => easedStep(phase, w[0], w[1]) * (1 - easedStep(phase, w[2], w[3]));
        turn = mw(WIN.turn); reach = mw(WIN.reach); grip = mw(WIN.grip);
        pulse = Math.sin(phase * Math.PI * 2 * 12) * mw(WIN.weld);
    }
    if (over.turn !== undefined) turn = over.turn;
    if (over.reach !== undefined) reach = over.reach;
    if (over.grip !== undefined) grip = over.grip;
    if (over.pulse !== undefined) pulse = over.pulse;
    const u = lerp(reach, K.IDLE_UPPER_ARM_Z, K.WORK_UPPER_ARM_Z), f = lerp(reach, K.IDLE_FOREARM_Z, K.WORK_FOREARM_Z);
    return {
        shoulder: [0, K.WORK_BASE_YAW * turn, 0], upper_arm: [0, 0, u], forearm: [0, 0, f],
        wrist: [0, pulse * 0.07, -(u + f)], tool: [0, -pulse * 0.11, 0],
        left_claw: [0, 0, lerp(grip, -0.26, -0.08)], right_claw: [0, 0, lerp(grip, 0.26, 0.08)],
    };
}

// 方块元素的碰撞体 (世界坐标, 含旋转)
function collisionBodies(E) {
    return E.map((e) => {
        const corners = [];
        for (const x of [e.from[0], e.to[0]]) for (const y of [e.from[1], e.to[1]]) for (const z of [e.from[2], e.to[2]]) corners.push(rotPoint(e.rot, [x, y, z], 1));
        const lo = [0, 1, 2].map((a) => Math.min(...corners.map((c) => c[a]))), hi = [0, 1, 2].map((a) => Math.max(...corners.map((c) => c[a])));
        return { name: e.name, from: e.from, to: e.to, rot: e.rot, corners, lo, hi };
    });
}
function rotPoint(rot, p, sign) {
    if (!rot) return p;
    const a = sign * rot.angle * Math.PI / 180, c = Math.cos(a), s = Math.sin(a), o = rot.origin;
    const q = [p[0] - o[0], p[1] - o[1], p[2] - o[2]];
    let r;
    if (rot.axis === 'x') r = [q[0], q[1] * c - q[2] * s, q[1] * s + q[2] * c];
    else if (rot.axis === 'y') r = [q[0] * c + q[2] * s, q[1], -q[0] * s + q[2] * c];
    else r = [q[0] * c - q[1] * s, q[0] * s + q[1] * c, q[2]];
    return [r[0] + o[0], r[1] + o[1], r[2] + o[2]];
}
const insideBody = (b, p, m) => { const q = rotPoint(b.rot, p, -1); return q[0] > b.from[0] - m && q[0] < b.to[0] + m && q[1] > b.from[1] - m && q[1] < b.to[1] + m && q[2] > b.from[2] - m && q[2] < b.to[2] + m; };
const STEP = 0.25;
const ARM_SAMPLES = armBoxes().map((b) => {
    const [part, , , x, y, z, w, h, d] = b;
    const pts = [];
    const st = ['wrist', 'tool', 'gripper', 'left_claw', 'right_claw'].includes(part) ? STEP / 2 : STEP;   // 抓手小件取更密的点
    const n = [Math.ceil(w / st), Math.ceil(h / st), Math.ceil(d / st)];
    for (let i = 0; i <= n[0]; i++) for (let j = 0; j <= n[1]; j++) for (let k = 0; k <= n[2]; k++) pts.push([x + w * i / n[0], y + h * j / n[1], z + d * k / n[2]]);
    const corners = [];
    for (const cx of [x, x + w]) for (const cy of [y, y + h]) for (const cz of [z, z + d]) corners.push([cx, cy, cz]);
    return { part, box: [x, y, z, w, h, d], pts, corners };
});
/** 返回该姿态下所有穿入的 (臂段, 方块元素)。shoulder 允许贴面 (margin -0.02), 其余活动段要求 >= margin 的间隙。 */
function armHits(pose, bodies, margin, first = false) {
    const Mx = armMatrices(pose);
    const hits = [];
    for (const s of ARM_SAMPLES) {
        const m = s.part === 'shoulder' ? -0.02 : margin;
        const Mp = Mx[s.part];
        const wc = s.corners.map((p) => mApply(Mp, p));
        // 粗筛: margin 在旋转元素的局部坐标里扩张, 世界 AABB 上最多放大 sqrt(3) 倍, 所以按 1.75m 放宽
        const mm = Math.abs(m) * 1.75;
        const lo = [0, 1, 2].map((a) => Math.min(...wc.map((c) => c[a])) - mm), hi = [0, 1, 2].map((a) => Math.max(...wc.map((c) => c[a])) + mm);
        const cand = bodies.filter((b) => b.lo[0] < hi[0] && b.hi[0] > lo[0] && b.lo[1] < hi[1] && b.hi[1] > lo[1] && b.lo[2] < hi[2] && b.hi[2] > lo[2]);
        if (!cand.length) continue;
        const wp = s.pts.map((p) => mApply(Mp, p));
        const inv = mInv(Mp);
        const [bx, by, bz, bw, bh, bd] = s.box;
        for (const b of cand) {
            let hit = wp.some((p) => insideBody(b, p, m));
            if (!hit) hit = b.corners.some((c) => { const q = mApply(inv, c); return q[0] > bx - m && q[0] < bx + bw + m && q[1] > by - m && q[1] < by + bh + m && q[2] > bz - m && q[2] < bz + bd + m; });
            if (hit) { hits.push(`${s.part}->${b.name}`); if (first) return hits; }
        }
    }
    return hits;
}
/** 各部件在某姿态下离某组元素的最小间隙 (粗略: 逐 0.05 增大 margin 直到碰上)。 */
function clearance(pose, bodies, parts) {
    const S0 = ARM_SAMPLES.filter((s) => parts.includes(s.part));
    for (let m = 0; m < 3; m += 0.05) {
        const Mx = armMatrices(pose);
        for (const s of S0) {
            const wp = s.pts.map((p) => mApply(Mx[s.part], p));
            if (bodies.some((b) => wp.some((p) => insideBody(b, p, m)))) return r4(m);
        }
    }
    return 3;
}

function parseJavaBase(src) {
    const W = {};
    for (const nm of ['turn', 'reach', 'grip', 'weld']) {
        const re = new RegExp(`float\\s+${nm}\\s*=\\s*motionWindow\\(phase,\\s*([\\d.]+)F,\\s*([\\d.]+)F,\\s*([\\d.]+)F,\\s*([\\d.]+)F\\)`);
        const m = re.exec(src);
        if (!m) throw new Error('Java base: cannot find motionWindow for ' + nm + ' (applyPose changed? update the sweep in generate.mjs)');
        W[nm] = m.slice(1, 5).map(Number);
    }
    const must = ['wrist.yRot = precisionPulse * 0.07F', 'tool.yRot = -precisionPulse * 0.11F', 'Mth.lerp(grip, -0.26F, -0.08F)', 'Mth.lerp(grip, 0.26F, 0.08F)',
        'shoulder.yRot = WORK_BASE_YAW * turn', 'wrist.zRot = -(upperArm.zRot + forearm.zRot)', 'Mth.sin(phase * Mth.TWO_PI * 12.0F) * weld'];
    for (const s of must) if (!src.includes(s)) throw new Error('Java base: applyPose no longer contains "' + s + '" (update the sweep in generate.mjs)');
    return W;
}
function findJavaBase() {
    for (const p of [JAVA_OUT, path.resolve(SCRIPT_DIR, '..', '..', JAVA_REL)]) if (fs.existsSync(p)) { const raw = fs.readFileSync(p, 'utf8'); return { file: p, src: raw.replace(/\r\n/g, '\n'), crlf: raw.includes('\r\n') }; }
    throw new Error('GunsmithAssemblyBenchRenderer.java not found (neither in --out nor in the repo next to this script)');
}

/** 选工作点 + 待机点, 生成常量, 并按真实时间窗扫全周期。 */
function armSolve(sceneIdle, sceneActive, WIN) {
    const bodiesAll = collisionBodies([...sceneIdle, ...sceneActive.filter((e) => !sceneIdle.some((f) => f.name === e.name))]);
    const bodiesAct = collisionBodies(sceneActive);
    const gunBodies = collisionBodies(sceneActive.filter((e) => e.gun));
    const S = ARM.shoulder;
    const tip = ARM.gripY + ARM.clawY + 2;   // 腕部枢轴到爪尖
    const toK = (idle, work) => ({
        IDLE_UPPER_ARM_Z: r4(idle.u), IDLE_FOREARM_Z: r4(idle.f), WORK_UPPER_ARM_Z: r4(work.u), WORK_FOREARM_Z: r4(work.f),
        WORK_BASE_YAW: -Math.atan2(r4(work.yawArgs[0]), r4(work.yawArgs[1])),
    });
    // 待机: 抓手停在换刀座正上方 (偏航 0), 爪尖离座面 >= 0.35
    let idle = null, idleW = null;
    for (let wy = PED.top + tip; wy < PED.top + tip + 4; wy += 0.05) {
        const W = [PED.x, wy, PED.z];
        const sol = solveWrist(W);
        if (!sol) continue;
        const K = toK(sol, sol);
        if (!armHits(javaPose(-1, K, WIN), bodiesAll, 0.35, true).length) { idle = sol; idleW = W; break; }
    }
    if (!idle) throw new Error('arm: no idle pose above the tool-change pedestal');
    // 工作: 在机匣上方找最低可达点, 峰值姿态 (含 ±脉冲) 间隙 >= 0.35
    const peak = (K) => [-1, -0.5, 0, 0.5, 1].map((pulse) => javaPose(0.5, K, WIN, { turn: 1, reach: 1, grip: 1, pulse }));
    const cands = [];
    // 只在机匣顶面 (v=1.5, w -1..1) 的俯视投影内找: z 12.85..14.27
    const zTop = gunToWorld(0, 1.5, 0)[2];
    for (let tx = 15; tx <= 20.5 + 1e-9; tx += 0.25) for (let tz = 12.75; tz <= 14.25 + 1e-9; tz += 0.25) {
        const J = [S[0], S[1] + ARM.jointUp, S[2]];
        const rr = Math.hypot(tx - J[0], tz - J[2]), reachMax = ARM.L1 + ARM.L2 - 0.06;
        if (rr >= reachMax) continue;
        let lo = 15, hi = Math.min(26, J[1] + Math.sqrt(reachMax * reachMax - rr * rr));
        const okAt = (wy) => { const sol = solveWrist([tx, wy, tz]); if (!sol) return false; const K = toK(idle, sol); return peak(K).every((p) => !armHits(p, bodiesAct, 0.35, true).length); };
        if (!okAt(hi)) continue;
        for (let it = 0; it < 9; it++) { const mid = (lo + hi) / 2; if (okAt(mid)) hi = mid; else lo = mid; }
        const wy = Math.ceil(hi * 20) / 20;
        if (!okAt(wy)) continue;
        // 评分: 越低越好, 且尽量对准机匣中段 (抛壳窗 / 瞄具之间)
        cands.push({ W: [tx, wy, tz], score: wy + 0.25 * Math.abs(tx - 18) + 0.5 * Math.abs(tz - zTop) });
    }
    cands.sort((a, b) => a.score - b.score);
    for (const c of cands.slice(0, 40)) {
        const work = solveWrist(c.W);
        const K = toK(idle, work);
        const bad = sweep(K, WIN, bodiesAll, 0.3);
        if (bad.length) continue;
        const gunGap = Math.min(...peak(K).map((p) => clearance(p, gunBodies, ['gripper', 'left_claw', 'right_claw', 'tool', 'wrist'])));
        return { K, idle, work, idleW, workW: c.W, gunGap, tries: cands.indexOf(c) + 1 };
    }
    throw new Error('arm: no work pose passes the full-cycle sweep (' + cands.length + ' candidates)');
}
function sweep(K, WIN, bodies, margin, steps = 800) {
    const bad = [];
    for (let i = -1; i <= steps; i++) {
        const ph = i < 0 ? -1 : i / steps;
        const h = armHits(javaPose(ph, K, WIN), bodies, margin, true);
        if (h.length) bad.push(`phase ${ph < 0 ? 'static' : ph.toFixed(4)}: ${h.join(', ')}`);
    }
    return bad;
}

// ---- 机械臂贴图 (64x64 盒式 UV, 整像素面)
const ARM_MATS = {
    frame: M.frame, orange: M.orange, dark: mat('dark', '#3a4049', '#58606b', '#2a2f36', '#1c2025'), cyan: M.cyan, steel: M.chrome,
};
function armTexture() {
    const boxes = armBoxes();
    const cv = new Canvas(64, 64);
    cv.fill(C('#3a4049'));
    let x = 0, y = 0, rowH = 0;
    const offs = [];
    const shared = new Map();   // 同尺寸同装饰的盒子共用一块 (例如两片叉架、两只爪)
    for (const b of boxes) {
        const [, mname, deco, , , , w, h, d] = b;
        const sk = `${mname}|${deco}|${w}x${h}x${d}`;
        if (shared.has(sk)) { offs.push(shared.get(sk)); continue; }
        const nw = 2 * (d + w) + 1, nh = d + h + 1;
        if (x + nw > 64) { x = 0; y += rowH; rowH = 0; }
        if (y + nh > 64) throw new Error('arm texture overflow');
        offs.push([x, y]); shared.set(sk, [x, y]);
        const m = ARM_MATS[mname];
        const U = x, V = y;
        const rects = {
            up: [U + d, V, w, d], down: [U + d + w, V, w, d],
            west: [U, V + d, d, h], north: [U + d, V + d, w, h], east: [U + d + w, V + d, d, h], south: [U + d + w + d, V + d, w, h],
        };
        for (const [face, [rx, ry, rw, rh]] of Object.entries(rects)) {
            const side = face !== 'up' && face !== 'down';
            cv.rect(rx, ry, rw, rh, face === 'up' ? m.hi : face === 'down' ? m.dk : m.base);
            if (side && rh >= 2) { cv.hline(rx, ry, rw, m.hi); cv.hline(rx, ry + rh - 1, rw, m.lo); }
            if (side && rw >= 3) { cv.vline(rx, ry + 1, rh - 2, m.hi); cv.vline(rx + rw - 1, ry + 1, rh - 2, m.lo); }
            armDeco(cv, deco, face, rx, ry, rw, rh, m);
        }
        x += nw; rowH = Math.max(rowH, nh);
    }
    return { cv, offs };
}
function armDeco(c, deco, face, x, y, w, h, m) {
    const side = face !== 'up' && face !== 'down';
    const big = face === 'north' || face === 'south';
    switch (deco) {
        case 'base':   // 底座侧面黄黑警示刻度, 顶面四角螺栓
            if (side) for (let i = 0; i < w; i++) c.px(x + i, y, i % 2 ? HAZ_K : HAZ_Y);
            if (face === 'up') { c.px(x, y, M.chrome.hi); c.px(x + w - 1, y, M.chrome.hi); c.px(x, y + h - 1, M.chrome.lo); c.px(x + w - 1, y + h - 1, M.chrome.lo); }
            break;
        case 'turret':  // 转台: 深色底缝 + 青色编号灯
            if (side) { c.hline(x, y + h - 1, w, M.orange.dk); c.px(x + 1, y, M.cyan.hi); }
            break;
        case 'cheek':   // 叉架: 中心枢轴螺栓
            if (big) { c.px(x + 1, y, M.chrome.hi); c.px(x + 1, y + 1, M.chrome.lo); }
            break;
        case 'hub': case 'joint':
            if (side && w >= 2 && h >= 2) { const cx = x + Math.floor((w - 1) / 2), cy = y + Math.floor((h - 1) / 2); c.px(cx, cy, M.chrome.hi); if (w >= 3) c.px(cx + 1, cy + 1, M.chrome.lo); }
            break;
        case 'upper':   // 大臂: 中段深色条带 + 青色标记 + 黑色编号格
            if (side) { c.hline(x, y + 2, w, M.frame.dk); c.hline(x, y + 3, w, M.frame.dk); if (big) { c.px(x, y + 1, M.cyan.hi); c.px(x + 1, y + 4, HAZ_K); } }
            break;
        case 'fore':    // 小臂: 腕端警示刻度 + 青色标记
            if (side) { c.px(x, y + h - 1, HAZ_Y); c.px(x + w - 1, y + h - 1, HAZ_K); if (big) c.px(x + 1, y + 1, M.cyan.hi); c.hline(x, y + 3, w, M.orange.dk); }
            break;
        case 'cap':     // 肘盖: 两端亮青 + 白芯
            if (big) { c.rect(x, y, w, h, M.cyan.hi); c.px(x, y, C('#e6fdff')); }
            break;
        case 'ring':
            if (side) { c.hline(x, y, w, M.cyan.hi); }
            break;
        case 'bar':     // 夹爪横梁: 黄黑刻度
            if (big) for (let i = 0; i < w; i++) c.px(x + i, y, i % 2 ? HAZ_K : HAZ_Y);
            break;
        case 'claw':
            if (side) { c.vline(x, y, h, M.chrome.hi); c.px(x, y + h - 1, M.chrome.dk); }
            break;
    }
}

const f1 = (v) => { const s = (Math.round(v * 10000) / 10000).toString(); return (s.includes('.') ? s : s + '.0') + 'F'; };
function armJavaPatch(base, sol, offs) {
    const boxes = armBoxes();
    const S = ARM.shoulder;
    const off = armOffsets();
    const cubes = (part) => boxes.map((b, i) => [b, i]).filter(([b]) => b[0] === part)
        .map(([b, i]) => `.texOffs(${offs[i][0]}, ${offs[i][1]}).addBox(${[b[3], b[4], b[5], b[6], b[7], b[8]].map(f1).join(', ')})`);
    const I = '                        ';
    const cl = (part) => 'CubeListBuilder.create()\n' + cubes(part).map((s) => I + s).join('\n');
    const pp = (v) => (v.every((x) => x === 0) ? 'PartPose.ZERO' : `PartPose.offset(${v.map(f1).join(', ')})`);
    const K = sol.K;
    const consts = [
        `    // Idle: gripper parked above the tool-change pedestal at (${PED.x}, ${PED.z}). Work: gripper hovering over the rifle receiver.`,
        `    private static final float IDLE_UPPER_ARM_Z = ${f1(K.IDLE_UPPER_ARM_Z)};`,
        `    private static final float IDLE_FOREARM_Z = ${f1(K.IDLE_FOREARM_Z)};`,
        `    private static final float WORK_UPPER_ARM_Z = ${f1(K.WORK_UPPER_ARM_Z)};`,
        `    private static final float WORK_FOREARM_Z = ${f1(K.WORK_FOREARM_Z)};`,
        `    // The shoulder pivot is (${S[0]}, ${S[2]}); the work point above the rifle receiver is (${r4(sol.workW[0])}, ${r4(sol.workW[2])}).`,
        `    private static final float WORK_BASE_YAW = -(float) Math.atan2(${r4(sol.work.yawArgs[0])}D, ${r4(sol.work.yawArgs[1])}D);`,
        '',
    ].join('\n');
    const child = (v, parent, name) => `        ${v ? 'PartDefinition ' + v + ' = ' : ''}${parent}.addOrReplaceChild("${name}",\n                ${cl(name)},\n                ${pp(off[name])});`;
    const layer = [
        '    private static LayerDefinition createBodyLayer() {',
        '        MeshDefinition mesh = new MeshDefinition();',
        '        PartDefinition root = mesh.getRoot();',
        child('shoulder', 'root', 'shoulder'),
        child('upperArm', 'shoulder', 'upper_arm'),
        child('elbow', 'upperArm', 'elbow'),
        child('forearm', 'elbow', 'forearm'),
        child('wrist', 'forearm', 'wrist'),
        child('tool', 'wrist', 'tool'),
        child('gripper', 'tool', 'gripper'),
        child(null, 'gripper', 'left_claw'),
        child(null, 'gripper', 'right_claw'),
        '        return LayerDefinition.create(mesh, 64, 64);',
        '    }',
        '',
    ].join('\n');
    const reC = /(?:[ \t]*\/\/[^\n]*\n)*[ \t]*private static final float IDLE_UPPER_ARM_Z[\s\S]*?private static final float WORK_BASE_YAW[^\n]*\n/;
    const reL = /[ \t]*private static LayerDefinition createBodyLayer\(\) \{[\s\S]*?return LayerDefinition\.create\(mesh, \d+, \d+\);\n[ \t]*\}\n/;
    if (!reC.test(base)) throw new Error('Java base: pose constant block not found');
    if (!reL.test(base)) throw new Error('Java base: createBodyLayer() not found');
    // 只保留紧贴在常量块前的、属于本脚本的注释; 其余原样
    const out = base.replace(reC, (m) => {
        const lead = m.match(/^(?:[ \t]*\/\/[^\n]*\n)*/)[0];
        const keep = lead.split('\n').filter((l) => l && !/Idle: gripper|Idle:|pedestal|work point/.test(l)).map((l) => l + '\n').join('');
        return keep + consts;
    }).replace(reL, layer);
    return out;
}

// ------------------------------------------------------------ 物品模型: 台子 (0.5 缩放) + 放大的步枪 (主角) + 小号机械臂替身
// 32px 图标里要一眼认出枪: 去掉墙和小摆件, 台子只留柜体/台面/屏幕/维修垫作为浅色底座;
// 步枪从夹具上取下, 剖面朝北立在台面前沿, 绕 z 轴 -22.5° 斜放 (枪口朝右上), 长度铺满整格。
const ITEM_BENCH = /^(plinth|ped_l|ped_r|drawers_l|drawers_r|knee_back|worktop)$/;
const ITEM_K = 0.45;   // 台子缩放 (0.45 而非 0.5: 给步枪留出主角的比例, 见 NOTES)
const ITEM_RIFLE = { sx: 0.6875, sv: 0.85, yc: 8.35, zc: 2.25, angle: -22.5 };   // 剖面方向 (v/w) 略加粗, 32px 下更好认
function itemModel(E, A) {
    const k = ITEM_K;
    const P = (p) => [8 + (p[0] - 16) * k, p[1] * k, 8 + (p[2] - 16) * k];
    const els = [];
    const push = (e, from, to, rot, faces) => {
        const el = { from: from.map(r4), to: to.map(r4) };
        if (rot) el.rotation = { angle: rot.angle, axis: rot.axis, origin: rot.origin.map(r4) };
        el.faces = faces; el.__name = e.name; els.push(el);
    };
    for (const e of E) {
        if (!ITEM_BENCH.test(e.name) && !e.armStand) continue;
        if (e.swatchOnly) continue;
        const faces = {};
        for (const [face, p] of Object.entries(e.plan)) {
            if (p.itemOnly) continue;
            // 台面顶面在图标里用纯浅钢色 (缩小后刻度/螺栓只剩噪点, 而且要给黑色步枪当干净的底)
            const q = e.name === 'worktop' && face === 'up' ? e.itemTop.plan : p;
            faces[face] = faceJson(q, faceUv(q, face, e, e, A));
        }
        if (!Object.keys(faces).length) continue;
        push(e, P(e.from), P(e.to), e.rot && { ...e.rot, origin: P(e.rot.origin) }, faces);
    }
    const R = ITEM_RIFLE;
    const rot = { origin: [8, R.yc, R.zc], axis: 'z', angle: R.angle };
    for (const e of E.filter((x) => x.rifle)) {
        const g = e.gun;
        const faces = {};
        for (const [face, p] of Object.entries(e.plan)) faces[face] = faceJson({ ...p, emit: false }, faceUv(p, face, e, e, A));
        const X = (x) => 8 + (x - 16) * R.sx, Y = (v) => R.yc + v * R.sv, Z = (w) => R.zc + w * R.sv;
        push(e, [X(g.x0), Y(g.v0), Z(g.w0)], [X(g.x1), Y(g.v1), Z(g.w1)], rot, faces);
    }
    return els;
}
function armStandIn() {
    const S = ARM.shoulder, o = M.orange;
    const E = [];
    const box = (name, from, to, faces) => E.push({ name, from, to, faces, rot: null, armStand: true });
    const six = (top, side, lo) => ({ north: flat(side), south: flat(side), east: flat(lo), west: flat(lo), up: flat(top), down: flat(M.frame.dk) });
    box('arm_base', [S[0] - 2.5, S[1], S[2] - 2.5], [S[0] + 2.5, S[1] + 1, S[2] + 2.5], six(M.frame.hi, M.frame.base, M.frame.lo));
    box('arm_turret', [S[0] - 2, S[1] + 1, S[2] - 2], [S[0] + 2, S[1] + 3, S[2] + 2], six(o.hi, o.base, o.lo));
    box('arm_hub', [S[0] - 1, S[1] + 3, S[2] - 1], [S[0] + 1, S[1] + 5, S[2] + 1], six(M.cyan.hi, M.cyan.base, M.cyan.lo));
    // 仅用于在图集里登记一个纯色块 (物品模型台面顶面), 不输出为元素
    E.push({ name: 'swatch_item_top', from: [0, 0, 0], to: [1, 1, 1], faces: { up: flat(M.steel.base) }, rot: null, swatchOnly: true });
    return E;
}

// ------------------------------------------------------------ 校验
function validateModel(file, json, A, opts = {}) {
    const errs = [];
    const minT = opts.minThickness || 0.5;
    const tex = json.textures || {};
    if (!tex.particle) errs.push(file + ': missing particle');
    const resolve = (ref, depth = 0) => {
        if (!ref.startsWith('#')) return ref;
        if (depth > 8) return null;
        const v = tex[ref.slice(1)];
        return v === undefined ? null : resolve(v, depth + 1);
    };
    let maxY = 0;
    json.elements.forEach((el, i) => {
        const id = `${file}#${i}(${el.__name || ''})`;
        for (let a = 0; a < 3; a++) {
            if (el.from[a] < 0 || el.to[a] > 16) errs.push(`${id}: out of [0,16] ${el.from} ${el.to}`);
            if (el.to[a] - el.from[a] < minT - 1e-6) errs.push(`${id}: thinner than ${minT} on axis ${a}`);
        }
        if (el.rotation) {
            const r = el.rotation;
            if (!['x', 'y', 'z'].includes(r.axis)) errs.push(id + ': bad axis');
            if (![-45, -22.5, 0, 22.5, 45].includes(r.angle)) errs.push(id + ': bad angle ' + r.angle);
            if (r.rescale) errs.push(id + ': rescale');
            for (const x of [el.from[0], el.to[0]]) for (const y of [el.from[1], el.to[1]]) for (const z of [el.from[2], el.to[2]]) {
                const w = rotPoint(r, [x, y, z], 1);
                maxY = Math.max(maxY, w[1]);
                if (w.some((v) => v < -0.001 || v > 16.001)) errs.push(`${id}: rotated corner leaves the cell ${w.map(r4)}`);
            }
        } else maxY = Math.max(maxY, el.to[1]);
        for (const [face, f] of Object.entries(el.faces)) {
            if (!FACES.includes(face)) errs.push(`${id}: bad face ${face}`);
            if (!Array.isArray(f.uv) || f.uv.length !== 4) { errs.push(`${id}.${face}: missing uv`); continue; }
            if (f.uv.some((v) => v < 0 || v > 16)) errs.push(`${id}.${face}: uv out of 0..16`);
            if (f.cullface) errs.push(`${id}.${face}: cullface`);
            if (!resolve(f.texture)) errs.push(`${id}.${face}: unresolved texture ${f.texture}`);
            const px = f.uv.map((v) => v * A / 16);
            const lo = [Math.min(px[0], px[2]), Math.min(px[1], px[3])], hi = [Math.max(px[0], px[2]), Math.max(px[1], px[3])];
            if (!opts.regionOf(lo, hi)) errs.push(`${id}.${face}: uv not inside one atlas region`);
        }
    });
    return { errs, maxY };
}

// ------------------------------------------------------------ 主流程
function writeJson(p, obj) {
    fs.mkdirSync(path.dirname(p), { recursive: true });
    const clean = JSON.parse(JSON.stringify(obj, (k, v) => (k === '__name' ? undefined : v)));
    fs.writeFileSync(p, JSON.stringify(clean, null, 2) + '\n');
}

function main() {
    const idle = buildScene(false);
    const active = buildScene(true);
    const stand = armStandIn();
    const errors = [];
    for (const [E, label] of [[idle, 'idle'], [active, 'active']]) { cullHidden(E); errors.push(...zfightCheck(E, label)); }
    const { size: A, atlas, items, regions, swatches } = buildAtlas([idle, active, stand]);

    for (let i = 0; i < items.length; i++) {
        const a = items[i];
        if (a.x < 0 || a.y < 0 || a.x + a.w > A || a.y + a.h > A) errors.push('atlas region out of bounds ' + (a.key || 'swatch'));
        for (let j = i + 1; j < items.length; j++) {
            const b = items[j];
            if (a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h) errors.push(`atlas overlap ${a.key || 'swatch'} / ${b.key || 'swatch'}`);
        }
    }
    const regionOf = (lo, hi) => items.find((it) => lo[0] >= it.x - 1e-6 && lo[1] >= it.y - 1e-6 && hi[0] <= it.x + it.w + 1e-6 && hi[1] <= it.y + it.h + 1e-6);

    const textures = { atlas: ATLAS_ID, particle: PARTICLE_ID };
    const stats = {};
    let maxY = 0;
    for (const [E, suffix] of [[idle, ''], [active, '_active']]) {
        for (const part of Object.keys(PARTS)) {
            const name = `gunsmith_assembly_bench_${part}${suffix}`;
            const model = { parent: 'minecraft:block/block', ambientocclusion: false, textures, elements: partElements(E, part, A) };
            const v = validateModel(name, model, A, { regionOf });
            errors.push(...v.errs);
            maxY = Math.max(maxY, v.maxY);
            stats[name] = model.elements.length;
            if (model.elements.length > 90) errors.push(`${name}: ${model.elements.length} elements (> 90)`);
            writeJson(path.join(RES, 'models', 'block', name + '.json'), model);
        }
    }
    idle.find((e) => e.name === 'worktop').itemTop = { plan: stand.find((e) => e.swatchOnly).plan.up };
    const itemEls = itemModel([...idle, ...stand], A);
    const item = {
        parent: 'minecraft:block/block',
        ambientocclusion: false,
        textures,
        display: {
            gui: { rotation: [20, 200, 0], translation: [0, 0.5, 0], scale: [0.82, 0.82, 0.82] },
            ground: { rotation: [0, 0, 0], translation: [0, 3, 0], scale: [0.4, 0.4, 0.4] },
            fixed: { rotation: [0, 180, 0], translation: [0, 1, 0], scale: [0.8, 0.8, 0.8] },
            head: { rotation: [0, 0, 0], translation: [0, 0, 0], scale: [1, 1, 1] },
            thirdperson_righthand: { rotation: [75, 225, 0], translation: [0, 2.5, 0], scale: [0.5, 0.5, 0.5] },
            thirdperson_lefthand: { rotation: [75, 225, 0], translation: [0, 2.5, 0], scale: [0.5, 0.5, 0.5] },
            firstperson_righthand: { rotation: [0, 225, 0], translation: [0, 2, 0], scale: [0.55, 0.55, 0.55] },
            firstperson_lefthand: { rotation: [0, 225, 0], translation: [0, 2, 0], scale: [0.55, 0.55, 0.55] },
        },
        elements: itemEls,
    };
    const vi = validateModel('item/gunsmith_assembly_bench', item, A, { regionOf, minThickness: 0.2 });
    errors.push(...vi.errs);
    stats['item/gunsmith_assembly_bench'] = itemEls.length;
    if (itemEls.length > 90) errors.push(`item: ${itemEls.length} elements (> 90)`);
    writeJson(path.join(RES, 'models', 'item', 'gunsmith_assembly_bench.json'), item);

    // 贴图
    const texDir = path.join(RES, 'textures', 'block');
    fs.mkdirSync(texDir, { recursive: true });
    fs.writeFileSync(path.join(texDir, 'gunsmith_assembly_atlas.png'), encodePng(A, A, atlas.data));
    for (let i = 3; i < atlas.data.length; i += 4) if (atlas.data[i] !== 255) { errors.push('atlas has non-opaque pixels'); break; }
    const pt = particleTex();
    fs.writeFileSync(path.join(texDir, 'gunsmith_assembly_particle.png'), encodePng(16, 16, pt.data));

    // 机械臂: 按 Java 底稿的时间窗求解 + 全周期扫描, 再只替换常量块与 createBodyLayer
    const base = findJavaBase();
    const WIN = parseJavaBase(base.src);
    const sol = armSolve(idle, active, WIN);
    const { cv: armCv, offs } = armTexture();
    const java = armJavaPatch(base.src, sol, offs);
    for (const nm of ['shoulder', 'upper_arm', 'elbow', 'forearm', 'wrist', 'tool', 'gripper', 'left_claw', 'right_claw']) if (!java.includes(`addOrReplaceChild("${nm}"`)) errors.push('arm part missing ' + nm);
    // 除两处替换外, 其余文本必须与底稿一致
    const strip = (s) => s.replace(/(?:[ \t]*\/\/[^\n]*\n)*[ \t]*private static final float IDLE_UPPER_ARM_Z[\s\S]*?WORK_BASE_YAW[^\n]*\n/, '').replace(/[ \t]*private static LayerDefinition createBodyLayer\(\) \{[\s\S]*?return LayerDefinition\.create\(mesh, \d+, \d+\);\n[ \t]*\}\n/, '');
    if (strip(java) !== strip(base.src)) errors.push('Java patch touched text outside the constants / createBodyLayer');
    // 再按写出的常量 (4 位小数) 独立扫一遍
    const bodiesAll = collisionBodies([...idle, ...active.filter((e) => !idle.some((f) => f.name === e.name))]);
    const bad = sweep(sol.K, WIN, bodiesAll, 0.3, 1600);
    if (bad.length) errors.push('arm sweep: ' + bad.slice(0, 6).join(' | ') + (bad.length > 6 ? ` (+${bad.length - 6})` : ''));
    const entDir = path.join(RES, 'textures', 'entity');
    fs.mkdirSync(entDir, { recursive: true });
    fs.writeFileSync(path.join(entDir, 'gunsmith_assembly_arm.png'), encodePng(64, 64, armCv.data));
    fs.mkdirSync(path.dirname(JAVA_OUT), { recursive: true });
    fs.writeFileSync(JAVA_OUT, base.crlf ? java.replace(/\n/g, '\r\n') : java);

    console.log(`atlas ${A}x${A}: ${regions} painted regions, ${swatches} swatches`);
    console.log('elements:', JSON.stringify(stats));
    console.log(`max model height (block px): ${r4(maxY)}`);
    const deg = (r) => r4(r * 180 / Math.PI);
    console.log(`java base: ${base.file}; windows ${JSON.stringify(WIN)}`);
    console.log(`arm: idle wrist ${sol.idleW.map(r4)} (u ${deg(sol.K.IDLE_UPPER_ARM_Z)}°, f ${deg(sol.K.IDLE_FOREARM_Z)}°); work wrist ${sol.workW.map(r4)} (u ${deg(sol.K.WORK_UPPER_ARM_Z)}°, f ${deg(sol.K.WORK_FOREARM_Z)}°, yaw ${deg(sol.K.WORK_BASE_YAW)}°), candidate #${sol.tries}`);
    console.log(`arm: gripper/claw clearance to the rifle fixture at peak (incl. weld pulse): ${sol.gunGap}px; full-cycle sweep (1600 steps, 0.3px margin): ${bad.length ? 'FAIL' : 'clean'}`);
    if (errors.length) { console.error('VALIDATION FAILED:\n  ' + errors.join('\n  ')); process.exit(1); }
    console.log('validation OK');
}

if (IS_MAIN) main();
export { armMatrices, javaPose, armBoxes, ARM_SAMPLES, mApply, collisionBodies, buildScene, armHits, insideBody };
