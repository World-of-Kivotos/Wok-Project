#!/usr/bin/env node
// 枪械组装台 · 方案 A「枪匠工坊工作台」生成脚本 (零依赖, Node 18+)。第 2 版 (按评审意见修改)。
// 用法: node generate.mjs --out <根目录> [--margins (另外打印各放件下沉组合下机械臂的最小间隙)]
// 产物 (相对 <根目录>):
//   src/main/resources/assets/miningdim/models/block/gunsmith_assembly_bench_{main,side,back,back_side}[_active].json
//   src/main/resources/assets/miningdim/models/item/gunsmith_assembly_bench.json
//   src/main/resources/assets/miningdim/textures/block/gunsmith_assembly_atlas.png
//   src/main/resources/assets/miningdim/textures/block/gunsmith_assembly_particle.png
//   src/main/resources/assets/miningdim/textures/entity/gunsmith_assembly_arm.png
//   src/main/java/com/miningdim/job/munitions/client/GunsmithAssemblyBenchRenderer.java
//     (只替换 createBodyLayer() 方法)
//   src/main/java/com/miningdim/job/munitions/block/GunsmithArmProgram.java
//     (只替换 "<generated>" 与 "</generated>" 两行注释之间的几何常量与关键帧表)
//     两个 Java 文件其余部分原样保留。底稿: <根目录> 里已有的该文件; 没有则取本脚本所在仓库 (../..) 里的; 都没有则报错。
//   src/main/java/com/miningdim/job/munitions/block/GunsmithGunBed.java (整个文件由本脚本写出: 枪床常量, 见 GUN_BED)
// 整台桌子先在 32x32 世界像素里建 (朝北, x 东, z 南, 正面 z=0), 再按格切成四个部位文件。
// 每个可见面在图集里分到自己的矩形 (默认 1 贴图像素 / 模型像素, 细节件 2 倍), 程序化绘制。
// 台上不再有静态的枪: 前排是一张平放的枪床, 运行时由 BlockEntityRenderer 把台里那把枪的真实 TACZ 模型侧躺在床面上
// (摆放规则见 GUN_BED); 方块模型只负责给它留出空位。
// 机械臂: 取放 + 点焊的关键帧程序由本脚本按真实几何反解 (料盘取件位、枪床上隐形枪体包络的安装点), 写进 GunsmithArmProgram;
// 写出前按 Java 同一套插值逐 tick 细分扫描整个 160 tick 程序 (含携带件), 任何臂段进入方块元素即校验失败。
// 运行时两个安装点再按台上的真枪下沉 (零件落到枪上而不是包络上): 扫描对两个安装点的下沉量各取 0 / MAX_PLACE_DROP 的四种组合
// (外加两处都取一半) 各走一遍。渲染器 easeToDock 的缓回待机 (手写代码, 从渲染器底稿读 RETURN_TICKS 与下沉收回比例)
// 也从两个安装点窗口内的每个时刻、各档下沉量扫一遍, 见 sweepEaseToDock。
// 全部校验通过后才落盘, 失败时仓库里的产物保持原样; 内容没变的文件不重写。
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { fileURLToPath } from 'node:url';
import { sampleArmProgram, ARM_STATION_BOLT, ARM_STATION_STOCK } from './raster.mjs';

// ------------------------------------------------------------ 参数
const IS_MAIN = !!process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
const argv = IS_MAIN ? process.argv.slice(2) : ['--out', '.'];
let OUT = null;
let REPORT_MARGINS = false;   // --margins: 另外二分出每种放件下沉组合下整段程序的最小间隙 (只打印, 不影响产物)
for (let i = 0; i < argv.length; i++) { if (argv[i] === '--out') OUT = argv[++i]; else if (argv[i] === '--margins') REPORT_MARGINS = true; }
if (!OUT) { console.error('usage: node generate.mjs --out <root>'); process.exit(2); }
OUT = path.resolve(OUT);
const SCRIPT_DIR = path.dirname(fileURLToPath(import.meta.url));
const RES = path.join(OUT, 'src', 'main', 'resources', 'assets', 'miningdim');
const JAVA_REL = path.join('src', 'main', 'java', 'com', 'miningdim', 'job', 'munitions', 'client', 'GunsmithAssemblyBenchRenderer.java');
const JAVA_OUT = path.join(OUT, JAVA_REL);
const PROGRAM_REL = path.join('src', 'main', 'java', 'com', 'miningdim', 'job', 'munitions', 'block', 'GunsmithArmProgram.java');
const GUN_BED_REL = path.join('src', 'main', 'java', 'com', 'miningdim', 'job', 'munitions', 'block', 'GunsmithGunBed.java');
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
    bedmat: mat('bedmat', '#3e6b72', '#5a8e95', '#2f5359', '#203a3e'),    // 枪床胶垫 (比维修垫亮、偏青: 黑色枪身躺在上面要看得清)
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

// 枪床: 运行时的真枪 (TACZ 模型) 侧躺在这里, 枪口朝西, 枪托抵住东端的托底挡块。
// 这组数值原样写进 GunsmithGunBed.java (Java 渲染器与 GameTest 经 GunsmithBenchGunLayout 使用), JS 预览 import 同一个对象。
// 摆放规则 (Java GunsmithBenchGunLayout 与 JS 预览逐条一致): 输入是枪在 TACZ FIXED 定位系里默认可见方块的包围盒 B
// (未缩放 px; 枪口 -X, 枪顶 +Y, 枪的左侧 +Z) 与枪口参考点的 z0。
//   1. L = B 的 x 长度; L <= 0 或非有限值 → 不画。
//   2. 渲染长度 T = min(MAX_LENGTH, SIZE_A * L^SIZE_P), 缩放 k = T / L; 剖面高度 k * (B 的 y 长度) 超过 2 * HALF_WIDTH 时再压到正好。
//   3. 枪口参考点两侧的厚度差 (B.maxZ - z0) - (z0 - B.minZ) > SIDE_UP_THRESHOLD → 左侧朝上, 否则右侧朝上。
//   4. 右侧朝上 (X, Y, Z) → (X, -Z, Y): 枪顶朝 +z (机械臂), 握把/弹匣朝玩家; 左侧朝上 (X, Y, Z) → (X, Z, -Y)。
//   5. 平移到: 包围盒最大 x = BUTT_X, 最小 y = TOP_Y, z 中心 = AXIS_Z。
// 床面上 x ∈ [BUTT_X - MAX_LENGTH, BUTT_X]、z ∈ AXIS_Z ± HALF_WIDTH、y ∈ [TOP_Y, TOP_Y + GUN_CLEAR_UP] 不许有任何方块元素 (main() 校验)。
// HALF_WIDTH 就是隐形枪体包络的 z 半宽: 第 2 步的宽度压缩保证任何枪都落在机械臂扫描时用的包络 z 范围里 (两者是同一个约定, 不许分开改)。
const GUN_BED = {
    BUTT_X: 27.5,              // 枪托端 (东) 抵住托底挡块胶垫的 x
    TOP_Y: 11,                 // 床面 (胶垫顶面) 的 y
    AXIS_Z: 11.75,             // 枪包围盒的 z 中心 (包络后沿 15.5: 再往后, 料盘取件时张开的爪子会擦到包络)
    MAX_LENGTH: 23.2,          // 渲染长度上限 (px), 尺寸曲线的封顶值: 最长的枪枪口在 x = 4.3
    HALF_WIDTH: 3.75,          // 平躺的枪 z 向可用的最大半宽 (枪的剖面高度的一半) = 隐形包络的 z 半宽
    SIZE_A: 1.864, SIZE_P: 0.657,
    SIDE_UP_THRESHOLD: 1.5,    // 未缩放 px
    ENVELOPE_THICKNESS: 2.25,  // 机械臂放件用的隐形枪体包络厚度: 关键帧表的安装点按它的顶面反解 (运行时再按台上的枪下沉, 见 PLACE_CLEARANCE)
};
const GUN_CLEAR_UP = 6;        // 留空高度: 司登冲锋枪的侧插弹匣朝上时高出床面约 5 px
const ENVELOPE_HALF_Z = GUN_BED.HALF_WIDTH;   // 隐形枪体包络的 z 半宽 (现有枪的剖面高度最大约 7.5 px)
// 枪床各件 (世界坐标): 钢底板上铺一条刻度扫描灯带 (前沿, 紧挨维修垫后沿, 兼做维修垫的定位灯) + 防滑胶垫,
// 东端是托底挡块 (胶垫朝西, 顶上一只状态灯)。底板与维修垫、柜体同宽 (x 1.5..30.5)。
// 床面 (灯带 + 胶垫) 在 z 向比枪能占的范围 AXIS_Z ± HALF_WIDTH 前后各宽出 BED_MARGIN, 最宽的枪也不会伸出床沿 (gunBedChecks 校验)。
// 后沿 15.75 仍在 y 11 以下, 料盘取件时爪子在 y >= 13 以上, 碰不到。
const BED_MARGIN = 0.25;
const BED = { x0: 1.5, x1: 30.5, z0: 7.75, z1: 15.75, y0: 10, base: 10.5, scanZ: 8.75, stop: { x1: 29.5, z0: 9.5, z1: 14.5, top: 13.5 } };

// 机械臂主要尺寸 (世界像素)。shoulder = 肩座底面中心, 立在设备台 y=11.5 上。
const ARM = {
    shoulder: [26.5, 11.5, 23.5],
    jointUp: 4,          // 大臂关节在肩座底面上方
    L1: 7, L2: 8,        // 大臂 / 小臂
    gripY: 2, clawX: 1.5, clawY: 1,
};
// 换刀座 (待机时抓手停在它上方), 立在设备台西侧, 与肩部同一 z (待机偏航角为 0)。
const PED = { x: 20.25, z: ARM.shoulder[2], top: 14 };

// 携带件 (gripper 局部坐标, ModelPart y 向下; 横梁 y 0..1, 两爪枢轴在 (±ARM.clawX, 1), 爪长 2, 爪尖 y 3)。
// 零件夹在两爪之间 (x ±1, 正好贴住伸直的爪), 底面比爪尖低 1: 放下时先碰到的是零件, 爪子与台面/枪身始终留着间隙。
// 盒子: [材质, 装饰, x, y, z, w, h, d]。颜色与台上/枪上的同类零件一致 (枪机 = 枪身黑钢, 枪托件 = 沙色聚合物 + 黑色橡胶托底)。
// 盒子末尾可带一个外扩量 (Java 里是 CubeDeformation, 只放大几何不动 UV): 夹起的瞬间携带件与送料位上的静态零件同形同位,
// 不外扩就是整面共面闪烁; 外扩 0.05 px 让携带件的面都在静态零件外面。枪托件两块外扩量不同, 免得两块在接缝处又互相共面。
// 外扩计入穿模扫描与取放点反解 (ARM_SAMPLES 用外扩后的盒子)。
const PAYLOAD_BOTTOM = 4;
const TIP_DROP = ARM.gripY + PAYLOAD_BOTTOM;   // 腕部枢轴 → 携带件底面 (工具竖直时, 不含外扩)
const PAYLOADS = [
    { code: 1, key: 'bolt', part: 'payload_bolt', label: '枪机', boxes: [['black', 'bolt', -1, 2, -0.5, 2, 2, 1, 0.05]] },
    { code: 2, key: 'stock', part: 'payload_stock', label: '枪托件', boxes: [['rubber', 'pad', -1, 2, -1, 2, 2, 1, 0.08], ['tan', 'stock', -1, 2, 0, 2, 2, 1, 0.05]] },
];
// 夹爪开合 (左爪 zRot; 右爪取反): 负 = 爪尖内收 (空载待机), 0 = 两爪平行正好夹住 2 宽的零件, 正 = 张开。
const CLAW = { pinch: -0.26, open: 0.22, hold: 0 };
// 携带时工具的世界朝向 (yaw + 工具自转): -90° 让两爪沿 z 横跨枪身, 零件厚度方向沿枪管 (x),
// 两爪都落在枪体包络的 z 范围内、不伸出枪床前后沿; 料盘上的零件按同一朝向摆放。
const CARRY_THETA = -Math.PI / 2;
// 送料盘: 设备台前沿一条浅盘, 一排零件。西端两个黄色角标格是送料位 (机械臂取件点), 东边是备件。
// 送料位上也摆着一件静态零件: 爪子合拢时夹的是看得见的东西, 抬起后盘上仍留一件 (静态模型, 相当于送料机又补了一件)。
// 两个送料位都放在西端: 离肩轴水平 >= 6 px, 取件发生在折起的大臂和肘外侧, 从台前看得见, 不会缩在肩座脚下。
// 零件底面 = 盘面; 世界坐标里零件占 x (厚 1 或 2) × z 2 (TRAY.row ± 1)。
const TRAY = { x0: 19.75, x1: 29.75, z0: 17.25, z1: 19.75, y0: 11.5, y1: 12, row: 18.5 };
const TRAY_SLOTS = [
    { payload: 'bolt', pick: 20.5, rest: [27.25, 28.75] },
    { payload: 'stock', pick: 22.75, rest: [25.25] },
];
const PICK_BODY = 'tray_pick_';   // 送料位静态零件的元素名前缀 (扫描时要区别对待, 见 sweepProgram)

/** 六个面的描述: 没写的面取 all (down 默认是底色), 写成 null 的面不要。 */
function faceSet(name, faces) {
    const f = {};
    for (const k of FACES) {
        if (faces[k] !== undefined) f[k] = faces[k];
        else if (k === 'down') f[k] = flat(BOTTOM);
        else if (faces.all) f[k] = faces.all;
        else throw new Error('face missing ' + name + ' ' + k);
        if (f[k] === null) delete f[k];
    }
    return f;
}

/** 世界坐标建一台 (active: 工作态)。返回元素数组。 */
function buildScene(active) {
    const E = [];
    const box = (name, from, to, faces, opt = {}) => {
        const f = faceSet(name, faces);
        const e = { name, from, to, faces: f, rot: opt.rot || null, item: opt.item !== false, proxy: !!opt.proxy };
        if (e.proxy && Object.keys(f).length) throw new Error('proxy element with visible faces: ' + name);
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

    // ---------------- 左前: 维修垫与零件 (后沿紧挨枪床前沿贯通全宽的刻度扫描灯带)
    // 枪床前沿前移到 7.75 后, 维修垫连同垫上的零件整体北移 0.25 (零件相对垫面的位置不变, 贴图仍按 2 倍整像素对齐)
    const MAT = { x0: 1.5, x1: 14.5, z0: 1.75, z1: BED.z0 };
    const MZ = MAT.z0 - 2;   // 维修垫上各件相对原位置 (垫子从 z 2 起) 的 z 偏移
    const parts = Object.fromEntries(Object.entries({
        bcg: [3, 3, 7, 4.5], spring: [3, 5.5, 8, 6.5], mag: [9, 3, 12.5, 4.5],
        slot: [3, 7, 6.5, 7.5], b1: [9, 6, 10.5, 6.5], b2: [11, 6, 12.5, 6.5], b3: [9, 7, 10.5, 7.5], b4: [11, 7, 12.5, 7.5],
    }).map(([k, [x0, z0, x1, z1]]) => [k, [x0, z0 + MZ, x1, z1 + MZ]]));
    box('mat', [MAT.x0, 10, MAT.z0], [MAT.x1, 10.5, MAT.z1], {
        up: paint('mat_top', (c) => matTop(c, MAT, parts), { m: M.matg, d: 2 }),
        north: flat(M.matg.lo), east: flat(M.matg.lo), west: flat(M.matg.lo), south: null, down: null,
    });
    box('bcg', [3, 10.5, 3 + MZ], [7, 11.5, 4.5 + MZ], {
        all: plate(M.black, 2),
        up: paint('bcg_top', (c) => { c.fill(M.black.base); c.bevel(M.black); c.hline(1, 1, c.w - 2, M.black.hi); c.rect(5, 1, 2, 1, M.brass.base); }, { m: M.black, d: 2 }),
        down: null,
    }, { item: false });
    box('bcg_bolt', [2.5, 10.5, 3.5 + MZ], [3, 11, 4 + MZ], { all: flat(M.chrome.base), down: null }, { item: false });
    box('spring', [3, 10.5, 5.5 + MZ], [8, 11, 6.5 + MZ], {
        all: flat(M.chrome.lo),
        up: paint('spring_top', (c) => { for (let x = 0; x < c.w; x++) c.vline(x, 0, c.h, x % 2 ? M.chrome.lo : M.chrome.hi); }, { m: M.chrome, d: 2 }),
        down: null,
    }, { item: false });
    box('mat_mag', [9, 10.5, 3 + MZ], [12.5, 11, 4.5 + MZ], {
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

    // ---------------- 中央: 平放的枪床 (真枪由 BlockEntityRenderer 侧躺在胶垫上, 见 GUN_BED)
    const G = GUN_BED, st = BED.stop;
    box('bed_base', [BED.x0, BED.y0, BED.z0], [BED.x1, BED.base, BED.z1], {
        all: flat(M.frame.lo),
        up: plate(M.frame, 2),
        north: paint('bed_base_n', (c) => { c.fill(M.frame.base); c.hline(0, 0, c.w, M.frame.hi); for (let x = 3; x < c.w - 2; x += 6) c.px(x, 0, M.orange.base); }, { m: M.frame, d: 2 }),
        down: null,
    }, { item: false });
    // 前沿: 刻度扫描灯带 (零点在托底挡块, 每 1 px 一小格、每 4 px 一长格), 工作时发光
    box('bed_scan', [BED.x0 + 0.5, BED.base, BED.z0], [G.BUTT_X, G.TOP_Y, BED.scanZ], {
        all: active ? glow(flat(CYAN_ON)) : flat(CYAN_OFF),
        up: active ? glow(paint('bed_scan_on', (c) => bedScale(c, true), { m: M.cyan, d: 2 })) : paint('bed_scan_off', (c) => bedScale(c, false), { m: M.cyan, d: 2 }),
        down: null,
    }, { item: false });
    // 防滑胶垫: 网格 + 两端零位角标
    box('bed_mat', [BED.x0 + 0.5, BED.base, BED.scanZ], [G.BUTT_X, G.TOP_Y, BED.z1], {
        all: flat(M.bedmat.lo),
        up: paint('bed_mat_top', bedMatTop, { m: M.bedmat, d: 2 }),
        down: null,
    }, { item: false });
    // 托底挡块: 朝西的一面是胶垫 (枪托抵在 x = BUTT_X), 顶上一只状态灯 (工作时亮橙)
    box('bed_stop', [G.BUTT_X, BED.base, st.z0], [st.x1, st.top, st.z1], {
        all: plate(M.orange, 2),
        west: paint('bed_stop_pad', stopPad, { m: M.rubber, d: 2 }),
        north: paint('bed_stop_n', stopSide, { m: M.orange, d: 2 }),
        south: paint('bed_stop_n', stopSide, { m: M.orange, d: 2 }),
        up: paint('bed_stop_top', (c) => { c.fill(M.orange.base); c.bevel(M.orange); c.bolt(1, 1, M.orange); c.bolt(1, c.h - 3, M.orange); }, { m: M.orange, d: 2 }),
        down: null,
    }, { item: false });
    const lz = (st.z0 + st.z1) / 2;
    box('bed_stop_lamp', [G.BUTT_X + 0.5, st.top, lz - 0.5], [st.x1 - 0.5, st.top + 0.5, lz + 0.5], {
        all: active ? glow(flat(M.hot.base)) : flat('#7a5a30'),
        up: active ? glow(flat(M.hot.hi)) : flat('#8a6a3a'),
        down: null,
    }, { item: false });
    // 西端一对定位销: 最长的枪 (封顶 MAX_LENGTH) 的枪口停在销前, 与东端挡块一起框出枪位
    const pinX = Math.floor((G.BUTT_X - G.MAX_LENGTH - 0.5) * 2) / 2;   // 销的东面, 对齐 0.5 px, 离最长的枪口 >= 0.5 px
    for (const [i, z] of [[0, G.AXIS_Z - 1.75], [1, G.AXIS_Z + 1.25]]) {
        box('bed_pin' + i, [pinX - 0.5, G.TOP_Y, z], [pinX, G.TOP_Y + 1, z + 0.5], { all: flat(M.chrome.base), up: flat(M.orange.hi), west: flat(M.chrome.lo), down: null }, { item: false });
    }
    // 隐形枪体包络: 不画、不进图集, 只当碰撞体。关键帧表里的安装点把零件放在它的顶面上 (见 PLACES), 其余段落按它避让台上的枪。
    // 运行时的枪 (除司登的侧插弹匣外厚度都 < 2.25 px) 都在它下面; 渲染器再按台上的枪让安装点下沉, 零件落到真枪上 (见 placeStations)。
    box('gun_envelope', [G.BUTT_X - G.MAX_LENGTH, G.TOP_Y, G.AXIS_Z - ENVELOPE_HALF_Z], [G.BUTT_X, G.TOP_Y + G.ENVELOPE_THICKNESS, G.AXIS_Z + ENVELOPE_HALF_Z],
        { north: null, south: null, east: null, west: null, up: null, down: null }, { item: false, proxy: true });

    // ---------------- 后右: 机械臂设备台 + 换刀座
    const S = ARM.shoulder;
    box('deck', [19.5, 10, 17], [31, 11.5, 29], {
        north: paint('deck_n', (c) => { c.hazard(0, 0, c.w, c.h, 6); c.hline(0, 0, c.w, mix(HAZ_Y, [255, 255, 255], 0.3)); }, { m: M.yellow, d: 2 }),
        east: paint('deck_side', deckSide, { m: M.frame, d: 2 }),
        west: paint('deck_side', deckSide, { m: M.frame, d: 2 }),
        up: paint('deck_top', (c) => deckTop(c, (S[0] - 19.5) * 2, (S[2] - 17) * 2), { m: M.frame, d: 2 }),
        south: null, down: null,
    });
    // 状态灯挂在设备台前立面上 (台顶前沿让给送料盘)
    const leds = active ? ['#6ff08e', '#ffb040', '#ffb040'] : ['#4fd070', null, null];
    leds.forEach((col, i) => {
        const x0 = 28 + i;
        box('deck_led' + i, [x0, 10.5, 16.5], [x0 + 0.5, 11, 17], { all: col ? glow(flat(col)) : flat('#3a3f46'), south: null }, { item: false });
    });
    // 送料盘 + 盘上的零件 (与机械臂携带件同形同色)
    box('tray', [TRAY.x0, TRAY.y0, TRAY.z0], [TRAY.x1, TRAY.y1, TRAY.z1], {
        all: flat(M.steel.lo),
        north: paint('tray_n', (c) => { c.fill(M.steel.lo); c.hline(0, 0, c.w, M.steel.hi); }, { m: M.steel, d: 2 }),
        up: paint('tray_top', trayTop, { m: M.frame, d: 2 }),
        down: null,
    }, { item: false });
    for (const g of TRAY_SLOTS) {
        for (const e of trayPart(g.payload, g.pick)) box(`${PICK_BODY}${g.payload}_${e.tag}`, e.from, e.to, e.faces, { item: false });
        g.rest.forEach((x, i) => {
            for (const e of trayPart(g.payload, x)) box(`tray_${g.payload}${i}_${e.tag}`, e.from, e.to, e.faces, { item: false });
        });
    }
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

/**
 * 物品图标里的步枪 (枪内局部坐标: x 同世界, v = 剖面高度, w = 厚度)。方块上的枪是运行时渲染的真枪, 这把只进物品模型,
 * 由 itemModel 放大、斜立在台面前沿; 这里只登记面 (进图集), 不进任何方块模型, 也不当碰撞体。
 */
function itemRifle() {
    const P = [];
    const g = (name, x0, x1, v0, v1, w0, w1, faces) => P.push({
        name, from: [x0, v0, w0], to: [x1, v1, w1], faces: faceSet(name, faces), rot: null, item: false, rifle: true, gun: { x0, x1, v0, v1, w0, w1 },
    });
    const gp = (key, fn, m, d = 2) => paint(key, fn, { m, d });
    const back = flat(M.black.lo);   // 枪背面 (图标里朝南, 基本看不到)

    // 步枪 (枪口朝西)
    g('g_muzzle', 4.5, 5.5, 0, 1, -0.5, 0.5, { all: flat(M.black.base), south: back, north: gp('g_muzzle_n', (c) => { c.fill(M.black.base); c.hline(0, 0, c.w, M.black.hi); c.px(0, 1, M.black.dk); c.px(1, 1, M.black.dk); }, M.black) });
    g('g_barrel', 5.5, 9, 0.25, 0.75, -0.25, 0.25, { all: flat(M.black.base), up: flat(M.black.hi), north: flat(M.black.hi), south: back });
    g('g_handguard', 9, 14.5, -0.25, 1.25, -0.75, 0.75, {
        all: flat(M.gunm.base), up: gp('g_hg_top', (c) => { c.fill(M.gunm.base); for (let x = 0; x < c.w; x += 2) c.vline(x, 0, c.h, M.gunm.hi); }, M.gunm), down: flat(M.gunm.lo), south: back,
        north: gp('g_hg_n', (c) => { c.fill(M.gunm.base); c.bevel(M.gunm); for (let x = 1; x < c.w - 1; x += 3) c.rect(x, 1, 2, 1, M.gunm.dk); }, M.gunm),
        west: flat(M.gunm.lo),
    });
    g('g_fsight', 9.5, 10, 1.25, 2.25, -0.25, 0.25, { all: flat(M.black.base), south: back });
    g('g_receiver', 14.5, 20.5, -0.5, 1.5, -1, 1, {
        all: flat(M.black.base), up: flat(M.black.hi), down: flat(M.black.lo), south: back,
        north: gp('g_rcv_n', receiverFace, M.black),
    });
    g('g_optic', 16, 19, 1.5, 2.5, -0.75, 0.75, {
        all: flat(M.black.base), up: flat(M.black.hi), south: back,
        north: gp('g_optic_n', (c) => { c.fill(M.black.base); c.hline(0, 0, c.w, M.black.hi); c.px(2, 1, M.cyan.base); c.px(3, 1, M.cyan.hi); }, M.black),
        west: flat(M.cyan.hi), east: flat(M.cyan.base),
    });
    g('g_mag_a', 15.5, 17.5, -2.5, -0.5, -0.5, 0.5, {
        all: flat(M.tan.base), south: back, down: flat(M.tan.lo),
        north: gp('g_mag_n', (c) => { c.fill(M.tan.base); c.bevel(M.tan); for (let y = 1; y < c.h - 1; y += 2) c.hline(1, y, c.w - 2, M.tan.lo); }, M.tan),
    });
    g('g_mag_b', 15, 17, -3.5, -2.5, -0.5, 0.5, {
        all: flat(M.tan.base), south: back, down: flat(M.tan.dk),
        north: gp('g_magb_n', (c) => { c.fill(M.tan.base); c.bevel(M.tan); c.hline(1, 1, c.w - 2, M.tan.lo); }, M.tan),
    });
    g('g_grip_a', 19, 20, -1.5, -0.5, -0.5, 0.5, { all: flat(M.tan.base), south: back, north: gp('g_grip_n', (c) => { c.fill(M.tan.base); c.bevel(M.tan); }, M.tan) });
    g('g_grip_b', 19.5, 20.5, -2.5, -1.5, -0.5, 0.5, { all: flat(M.tan.base), south: back, north: gp('g_grip_n', (c) => { c.fill(M.tan.base); c.bevel(M.tan); }, M.tan) });
    g('g_guard', 17.5, 19, -1, -0.5, -0.25, 0.25, { all: flat(M.black.base), south: back });
    g('g_tube', 20.5, 23, 0, 1, -0.5, 0.5, { all: flat(M.black.base), up: flat(M.black.hi), south: back });
    g('g_stock', 23, 27, 0, 1.5, -0.75, 0.75, {
        all: flat(M.tan.base), up: flat(M.tan.hi), down: flat(M.tan.lo), south: back,
        north: gp('g_stock_n', (c) => { c.fill(M.tan.base); c.bevel(M.tan); c.rect(3, 1, 3, 1, M.tan.dk); }, M.tan),
    });
    g('g_stock_toe', 25, 27, -1, 0, -0.75, 0.75, {
        all: flat(M.tan.base), down: flat(M.tan.lo), south: back,
        north: gp('g_stoe_n', (c) => { c.fill(M.tan.base); c.hline(0, c.h - 1, c.w, M.tan.lo); c.vline(c.w - 1, 0, c.h, M.tan.lo); c.vline(0, 0, c.h - 1, M.tan.hi); }, M.tan),
    });
    g('g_buttpad', 27, 27.5, -1, 1.5, -0.75, 0.75, { all: flat(M.rubber.base), up: flat(M.rubber.hi), south: back });
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
    outline([2.5, parts.bcg[1], parts.bcg[2], parts.bcg[3]]); outline(parts.spring); outline(parts.mag);   // 枪机框连同西端的枪机头
    outline([parts.b1[0], parts.b1[1], parts.b4[2], parts.b4[3]]);   // 四发子弹一组
    outline(parts.slot, true);
    const [sa, sb] = tex(parts.slot[0], parts.slot[1]);
    c.hline(sa + 1, sb, 4, mix(line, m.base, 0.4));
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
// 枪床前沿的刻度扫描灯带 (顶面贴图: u 沿 x 向东, v 沿 z 向南)。刻度零点在东端 (托底挡块, x = BUTT_X):
// 每 1 px 一小格 (后一行)、每 4 px 一长格 (两行), 读数就是枪从托底量起的长度。
function bedScale(c, on) {
    const a = on ? C('#b8fbff') : C('#35606a'), b = on ? CYAN_ON : CYAN_OFF, k = on ? C('#2a9fb0') : C('#1d3a40');
    c.fill(b); c.hline(0, 0, c.w, a);
    for (let u = 0; u < c.w; u++) {
        const fromButt = c.w - 1 - u;
        if (fromButt % 8 === 0) c.vline(u, 0, c.h, k);
        else if (fromButt % 2 === 0) c.px(u, c.h - 1, k);
    }
}
// 枪床胶垫顶面 (u 沿 x 向东, v 沿 z 向南): 淡色 2 px 网格, 竖线从托底端量起, 与前沿刻度尺的长格对齐;
// 两端各一对零位角标 (东 = 托底, 西 = 最长的枪的枪口)
function bedMatTop(c) {
    const m = M.bedmat;
    const x0 = BED.x0 + 0.5;
    c.fill(m.base);
    const grid = mix(m.base, m.hi, 0.22);
    for (let u = 0; u < c.w; u++) if ((c.w - 1 - u) % 4 === 0) c.vline(u, 0, c.h, grid);
    for (let y = 3; y < c.h - 1; y += 4) c.hline(0, y, c.w, grid);
    c.bevel({ hi: m.hi, lo: m.dk });
    const line = C('#9fe6d8');
    const xe = c.w - 2, xw = Math.round((GUN_BED.BUTT_X - GUN_BED.MAX_LENGTH - x0) * 2);
    for (const [xx, dir] of [[xe, -1], [xw, 1]]) {
        c.px(xx, 1, line); c.px(xx, 2, line); c.px(xx + dir, 1, line);
        c.px(xx, c.h - 2, line); c.px(xx, c.h - 3, line); c.px(xx + dir, c.h - 2, line);
    }
}
// 托底挡块朝西的胶垫: 橙色边框 + 黑色防滑棱
function stopPad(c) {
    c.fill(M.rubber.base);
    for (let y = 1; y < c.h - 1; y += 2) c.hline(1, y, c.w - 2, M.rubber.hi);
    c.hline(0, 0, c.w, M.orange.base); c.hline(0, c.h - 1, c.w, M.orange.lo);
    c.vline(0, 0, c.h, M.orange.base); c.vline(c.w - 1, 0, c.h, M.orange.lo);
}
function stopSide(c) {
    c.fill(M.orange.base); c.bevel(M.orange);
    c.hazard(1, c.h - 2, c.w - 2, 1, 2);
}
function receiverFace(c) {
    const m = M.black;
    c.fill(m.base); c.bevel(m);
    // 北面贴图左端 = 东 (x=20.5, 枪托侧)
    c.rect(3, 1, 3, 1, m.dk);               // 抛壳窗
    c.px(1, 1, M.brass.lo);                   // 助推器
    c.vline(7, 2, 2, m.dk);                   // 弹匣井前沿
    c.px(2, 2, m.hi); c.px(4, 2, m.hi);       // 销钉
    c.hline(1, 2, 1, M.cyan.base);            // 快慢机点缀
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
/** 料盘上的一件零件 (世界坐标, 朝向与 CARRY_THETA 下的携带件一致)。 */
function trayPart(key, x) {
    const y0 = TRAY.y1, y1 = TRAY.y1 + 2, z0 = TRAY.row - 1, z1 = TRAY.row + 1;
    if (key === 'bolt') {
        return [{
            tag: 'body', from: [x - 0.5, y0, z0], to: [x + 0.5, y1, z1],
            faces: {
                all: flat(M.black.base), up: paint('tray_bolt_up', boltTop, { m: M.chrome, d: 2 }),
                west: paint('tray_bolt_side', boltSide, { m: M.black, d: 2 }), east: paint('tray_bolt_side', boltSide, { m: M.black, d: 2 }), down: null,
            },
        }];
    }
    // 枪托件: 西半沙色托身, 东半黑色橡胶托底
    return [
        { tag: 'pad', from: [x, y0, z0], to: [x + 1, y1, z1], faces: { all: flat(M.rubber.base), up: flat(M.rubber.hi), down: null, west: null } },
        {
            tag: 'body', from: [x - 1, y0, z0], to: [x, y1, z1],
            faces: { all: flat(M.tan.base), up: flat(M.tan.hi), west: paint('tray_stock_side', stockSide, { m: M.tan, d: 2 }), down: null, east: null },
        },
    ];
}
// 零件侧面花纹 (x 向的面, 贴图宽 = z 2 格): 枪机 = 黑钢 + 亮钢导轨线 + 黄铜抛壳挺; 枪托件 = 沙色 + 防滑纹
function boltSide(c) { c.fill(M.black.base); c.hline(0, 0, c.w, M.chrome.lo); c.px(1, 1, M.brass.base); c.hline(0, c.h - 1, c.w, M.black.dk); }
// 枪机顶面镀镍: 抓手横梁与爪子都是深色, 黑色枪机夹在中间认不出来, 亮顶面让 "爪里有件东西" 从上方和斜上方都一眼可见。
// 贴图坐标里 x 沿零件厚度 (世界 x), y 沿夹持方向 (世界 z); 亮棱沿夹持方向。
function boltTop(c) { c.fill(M.chrome.lo); for (let y = 0; y < c.h; y++) c.px(0, y, M.chrome.hi); }
function stockSide(c) { c.fill(M.tan.base); c.bevel(M.tan); for (let x = 1; x < c.w - 1; x += 2) c.px(x, 2, M.tan.lo); }
// 浅钢盘面: 黑色枪机与深色设备台之间要有反差, 一眼看出 "盘里摆着零件"
function trayTop(c) {
    const m = M.steel;
    c.fill(m.base);
    c.bevel(m);
    // 取件位: 黄色角标 (与盘上零件同样大小的轮廓), 其余格位淡青色刻线
    const tex = (x) => Math.round((x - TRAY.x0) * 2);
    const zt = Math.round((TRAY.row - 1 - TRAY.z0) * 2), zb = Math.round((TRAY.row + 1 - TRAY.z0) * 2) - 1;
    for (const g of TRAY_SLOTS) {
        const half = g.payload === 'bolt' ? 0.5 : 1;
        const slot = (x, col, corners) => {
            const a = tex(x - half) - 1, b = tex(x + half);
            if (corners) {
                for (const [px, py] of [[a, zt - 1], [b, zt - 1], [a, zb + 1], [b, zb + 1]]) c.px(px, py, col);
                c.px(a + 1, zt - 1, col); c.px(b - 1, zb + 1, col);
            } else { c.hline(a, zb + 1, b - a + 1, col); }
        };
        slot(g.pick, HAZ_Y, true);
        for (const x of g.rest) slot(x, m.lo, false);
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
                if (b === e || b.rot || b.proxy) continue;   // 隐形碰撞体不遮挡任何面
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

// ------------------------------------------------------------ 机械臂: 盒子、正向运动学、反解
// [部件, 材质, 装饰, x, y, z, w, h, d] (ModelPart 坐标, y 向下)。全部整数尺寸, 贴图按整像素绘制。
// 携带件排在最后: 贴图按顺序装箱, 新增件只占用原有区域之后的空白, 不挪动已有盒子的 UV。
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
        ...PAYLOADS.flatMap((p) => p.boxes.map((b) => [p.part, ...b])),
    ];
}
const ARM_TREE = [['shoulder', 'root'], ['upper_arm', 'shoulder'], ['elbow', 'upper_arm'], ['forearm', 'elbow'], ['wrist', 'forearm'], ['tool', 'wrist'], ['gripper', 'tool'], ['left_claw', 'gripper'], ['right_claw', 'gripper'],
    ...PAYLOADS.map((p) => [p.part, 'gripper'])];
const PAYLOAD_OF_PART = Object.fromEntries(PAYLOADS.map((p) => [p.part, p.code]));
function armOffsets() {
    const S = ARM.shoulder;
    const o = {
        shoulder: [S[0] - 8, 16 - S[1], S[2] - 8], upper_arm: [0, -ARM.jointUp, 0], elbow: [0, -ARM.L1, 0], forearm: [0, 0, 0],
        wrist: [0, ARM.L2, 0], tool: [0, 0, 0], gripper: [0, ARM.gripY, 0], left_claw: [-ARM.clawX, ARM.clawY, 0], right_claw: [ARM.clawX, ARM.clawY, 0],
    };
    for (const p of PAYLOADS) o[p.part] = [0, 0, 0];
    return o;
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
/** 腕部枢轴的世界位置 → 偏航 + 两个关节角 (肘朝上的解)。 */
function solveWrist(W) {
    const S = ARM.shoulder, J = [S[0], S[1] + ARM.jointUp, S[2]];
    const tx = W[0] - J[0], tz = W[2] - J[2];
    const r = Math.hypot(tx, tz);
    const k = ik2(-r, W[1] - J[1]);
    if (!k) return null;
    return { yaw: r < 1e-9 ? 0 : Math.atan2(tz, -tx), u: k.u, f: k.f };
}
/** 关节角 → 腕部枢轴世界坐标 (与 GunsmithArmProgram.wristPosition 同一公式)。 */
function wristOf(k) {
    const px = ARM.L1 * Math.sin(k.upper) - ARM.L2 * Math.sin(k.upper + k.fore);
    const py = -ARM.jointUp - ARM.L1 * Math.cos(k.upper) + ARM.L2 * Math.cos(k.upper + k.fore);
    const S = ARM.shoulder;
    return [S[0] + Math.cos(k.yaw) * px, S[1] - py, S[2] - Math.sin(k.yaw) * px];
}
/** 关键帧 → 各部件旋转。与 Java applyPose 同一推导: 手腕抵消大臂 + 小臂保持工具竖直, 右爪与左爪镜像, 只显示当前携带件。 */
function armPose(k) {
    return {
        shoulder: [0, k.yaw, 0], upper_arm: [0, 0, k.upper], forearm: [0, 0, k.fore],
        wrist: [0, 0, -(k.upper + k.fore)], tool: [0, k.spin, 0],
        left_claw: [0, 0, k.claw], right_claw: [0, 0, -k.claw],
        payload: k.payload || 0,
    };
}
/** 腕部世界坐标 + 工具世界朝向 → 关键帧关节角 (工具自转 = 世界朝向 - 底座偏航)。 */
function keyAt(W, theta, claw, payload) {
    const s = solveWrist(W);
    if (!s) return null;
    return { yaw: s.yaw, upper: s.u, fore: s.f, spin: theta - s.yaw, claw, payload };
}

// ------------------------------------------------------------ 机械臂: 碰撞
function collisionBodies(E) {
    return E.map((e) => {
        const corners = [];
        for (const x of [e.from[0], e.to[0]]) for (const y of [e.from[1], e.to[1]]) for (const z of [e.from[2], e.to[2]]) corners.push(rotPoint(e.rot, [x, y, z], 1));
        const lo = [0, 1, 2].map((a) => Math.min(...corners.map((c) => c[a]))), hi = [0, 1, 2].map((a) => Math.max(...corners.map((c) => c[a])));
        // 棱上的点 (每 0.05 px): 细长元素或旋转元素的棱 (例如斜置的工具、屏幕) 可能从臂段表面采样点之间穿过去, 只查角点查不出来
        const edgePts = [];
        for (let i = 0; i < 8; i++) for (let j = i + 1; j < 8; j++) {
            if ([1, 2, 4].indexOf(i ^ j) < 0) continue;   // corners 按 x/y/z 三重循环排列: 下标只差一位的两个角共棱
            const a = corners[i], b = corners[j];
            const n = Math.max(1, Math.ceil(Math.hypot(b[0] - a[0], b[1] - a[1], b[2] - a[2]) / 0.05));
            for (let k = 1; k < n; k++) edgePts.push([0, 1, 2].map((ax) => a[ax] + (b[ax] - a[ax]) * k / n));
        }
        return { name: e.name, from: e.from, to: e.to, rot: e.rot, corners, edgePts, lo, hi };
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
const FINE_PARTS = ['wrist', 'tool', 'gripper', 'left_claw', 'right_claw', ...PAYLOADS.map((p) => p.part)];
const ARM_SAMPLES = armBoxes().map((b) => {
    const g = b[9] || 0;   // 外扩 (CubeDeformation): 碰撞按实际画出来的几何算
    const [part, , , x, y, z, w, h, d] = [b[0], b[1], b[2], b[3] - g, b[4] - g, b[5] - g, b[6] + 2 * g, b[7] + 2 * g, b[8] + 2 * g];
    const pts = [];
    const st = FINE_PARTS.includes(part) ? STEP / 2 : STEP;   // 抓手与携带件取更密的点
    const n = [Math.ceil(w / st), Math.ceil(h / st), Math.ceil(d / st)];
    for (let i = 0; i <= n[0]; i++) for (let j = 0; j <= n[1]; j++) for (let k = 0; k <= n[2]; k++) pts.push([x + w * i / n[0], y + h * j / n[1], z + d * k / n[2]]);
    const corners = [];
    for (const cx of [x, x + w]) for (const cy of [y, y + h]) for (const cz of [z, z + d]) corners.push([cx, cy, cz]);
    return { part, payload: PAYLOAD_OF_PART[part] || 0, box: [x, y, z, w, h, d], pts, corners };
});
/**
 * 返回该姿态下所有穿入的 (臂段, 方块元素)。只检查当前可见的携带件。
 * shoulder 允许贴面 (margin -0.02); 携带件用 opts.payloadMargin (取放接触时为 0: 贴上但不穿入); 其余活动段要求 >= margin 的间隙。
 */
function armHits(pose, bodies, margin, first = false, opts = {}) {
    const Mx = armMatrices(pose);
    const hits = [];
    for (const s of ARM_SAMPLES) {
        if (s.payload && (s.payload !== pose.payload || opts.skipPayload)) continue;
        const m = s.part === 'shoulder' ? -0.02 : s.payload ? (opts.payloadMargin ?? margin) : margin;
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
        const inArm = (c) => { const q = mApply(inv, c); return q[0] > bx - m && q[0] < bx + bw + m && q[1] > by - m && q[1] < by + bh + m && q[2] > bz - m && q[2] < bz + bd + m; };
        const nearArm = (c) => c[0] > lo[0] && c[0] < hi[0] && c[1] > lo[1] && c[1] < hi[1] && c[2] > lo[2] && c[2] < hi[2];
        for (const b of cand) {
            let hit = wp.some((p) => insideBody(b, p, m));
            if (!hit) hit = b.corners.some(inArm);
            if (!hit) hit = b.edgePts.some((c) => nearArm(c) && inArm(c));
            if (hit) { hits.push(`${s.part}->${b.name}`); if (first) return hits; }
        }
    }
    return hits;
}

// ------------------------------------------------------------ 机械臂: 取放程序
// 安装点 (世界坐标, 期望的腕部水平位置; 求解时在附近小范围内找最贴合的一点)。台上的枪是运行时按台里的枪换的,
// 这里放在隐形枪体包络 gun_envelope 的顶面上 (所有枪都在它下面), 两处都靠枪顶一侧 (右侧朝上时枪顶朝 +z, 朝着机械臂):
//   枪机 → 托底往西 7.5 px (步枪的机匣后段)
//   枪托件 → 托底往西 2.5 px (枪托)
const PLACES = {
    bolt: { x: GUN_BED.BUTT_X - 7.5, z: GUN_BED.AXIS_Z + 1.5, targets: ['gun_envelope'] },
    stock: { x: GUN_BED.BUTT_X - 2.5, z: GUN_BED.AXIS_Z + 1.5, targets: ['gun_envelope'] },
};
const DESCENT = 3;            // 悬停点比最低的接触点至少高这么多 (全部取放点共用一个安全高度)
const CONTACT_GAP = 0.02;     // 接触时携带件与目标的间隙 (>0: 贴上但不穿入)

/** 在 (x, z) 处从可达上限往下找最低可行腕部高度: 三种夹爪状态 (空载张开 / 带件张开 / 带件夹紧) 都不穿模。 */
function lowestWrist(xz, payload, bodies) {
    const J = [ARM.shoulder[0], ARM.shoulder[1] + ARM.jointUp, ARM.shoulder[2]];
    const r = Math.hypot(xz[0] - J[0], xz[1] - J[2]);
    const reachMax = ARM.L1 + ARM.L2 - 0.1;
    if (r >= reachMax) return null;
    const combos = [[CLAW.open, 0], [CLAW.open, payload], [CLAW.hold, payload]];
    const ok = (wy) => combos.every(([claw, pl]) => {
        const k = keyAt([xz[0], wy, xz[1]], CARRY_THETA, claw, pl);
        return k && !armHits(armPose(k), bodies, 0.3, true, { payloadMargin: CONTACT_GAP }).length;
    });
    const search = (pred) => {
        let hi = J[1] + Math.sqrt(reachMax * reachMax - r * r) - 0.05, lo = 10;
        if (!pred(hi)) return null;
        for (let i = 0; i < 16; i++) { const mid = (lo + hi) / 2; if (pred(mid)) hi = mid; else lo = mid; }
        let wy = Math.ceil(hi * 100) / 100;
        while (!pred(wy)) wy += 0.01;
        return r4(wy);
    };
    const wy = search((y) => ok(y));
    if (wy === null) return null;
    // 只看携带件时能到多低: 两者之差 = 零件离目标的实际间隙 (大了说明是爪子或别的臂段先碰到, 零件并没有贴上)
    const payloadOnlyHits = (y) => {
        const k = keyAt([xz[0], y, xz[1]], CARRY_THETA, CLAW.hold, payload);
        return k ? armHits(armPose(k), bodies, 1e9, false, { payloadMargin: 0 }).filter((h) => h.startsWith('payload')) : ['unreachable'];
    };
    let yp = wy;
    while (yp > wy - 3 && !payloadOnlyHits(yp - 0.01).length) yp -= 0.01;
    const blockers = [...new Set(payloadOnlyHits(yp - 0.02).map((h) => h.split('->')[1]))];
    return { wy, gap: r4(wy - yp), blockers };
}
function solveStations(bodies) {
    const st = {};
    for (const g of TRAY_SLOTS) {
        const p = PAYLOADS.find((q) => q.key === g.payload);
        const xz = [g.pick, TRAY.row];
        // 送料位上的静态零件就是要夹的那一件 (携带件与它同形同位), 反解取件高度时把它当成不存在, 零件应当正好落在盘面上
        const c = lowestWrist(xz, p.code, bodies.filter((b) => !b.name.startsWith(PICK_BODY + g.payload + '_')));
        if (!c) throw new Error(`arm: tray pick slot for ${g.payload} at ${xz} is unreachable`);
        if (c.gap > 0.1 || !c.blockers.includes('tray')) throw new Error(`arm: tray pick ${g.payload}: the part does not rest on the tray (gap ${c.gap}, blockers ${c.blockers})`);
        st[g.payload + '_pick'] = { xz, payload: p.code, label: p.label, pickSlot: g.payload, ...c };
    }
    for (const [key, P] of Object.entries(PLACES)) {
        const p = PAYLOADS.find((q) => q.key === key);
        const cands = [];
        for (let dx = -0.5; dx <= 0.5 + 1e-9; dx += 0.25) for (let dz = -1; dz <= 1 + 1e-9; dz += 0.25) {
            const xz = [P.x + dx, P.z + dz];
            const c = lowestWrist(xz, p.code, bodies);
            if (!c || c.gap > 0.1 || !c.blockers.some((b) => P.targets.includes(b))) continue;
            cands.push({ xz, ...c, score: Math.abs(dx) + Math.abs(dz) });
        }
        if (!cands.length) throw new Error(`arm: no contact point on the gun envelope for ${key} near (${P.x}, ${P.z})`);
        cands.sort((a, b) => a.score - b.score || a.wy - b.wy);
        st[key + '_place'] = { payload: p.code, label: p.label, place: key, ...cands[0] };
    }
    return st;
}
function solveIdle(bodies) {
    for (let wy = PED.top + ARM.gripY + ARM.clawY + 2; wy < PED.top + 10; wy += 0.05) {
        const W = [PED.x, r4(wy), PED.z];
        const k = keyAt(W, 0, CLAW.pinch, 0);
        if (k && !armHits(armPose(k), bodies, 0.35, true).length) return { W, k };
    }
    throw new Error('arm: no idle pose above the tool-change pedestal');
}

// 关键帧的插值方式 (Java 表里的 linear 列)。三种都按 smoothstep 缓动, 区别只在 "什么量走直线":
const MOVE_J = 0;         // 关节角直接插值: 只用于手臂不动的段落 (停顿、夹爪开合、点焊)
const MOVE_VERTICAL = 1;  // 柱坐标插值且偏航不变 → 腕部竖直直线 (下探/抬起)
const MOVE_TRANSFER = 2;  // 柱坐标插值且高度不变 → 在安全高度平移 (偏航与水平伸出同时缓动, 腕部不上浮)
// 放件下沉 (Java 表里的 station 列, 取值与 GunsmithArmProgram.STATION_* / raster.mjs ARM_STATION_* 相同):
// 安装点的低位行 (下探就位、停顿、点焊、松开) 标上所在安装点, 运行时这些行的腕部按该安装点的下沉量降低, 零件落到台上真枪的顶面。
const PLACE_STATION = { bolt: ARM_STATION_BOLT, stock: ARM_STATION_STOCK };
const PLACE_CLEARANCE = 0.02;   // 下沉后零件底面 (含外扩) 与真枪顶面的间隙 (同 CONTACT_GAP: 贴上但不穿入)

/**
 * 关键帧程序。每行: tick、关节角、夹爪、携带件、火花、插值方式、下沉安装点、动作名。
 * 连续量 (关节角、自转、夹爪) 在相邻两行之间缓动; 相同两行 = 停顿。
 * 节奏是 "动-停-做-动": 偏航只在安全高度改变 (MOVE_TRANSFER), 离开/落回任何低位 (换刀座、料盘、枪上) 都是竖直直线,
 * 每个动作前后停 2-3 tick。
 * 离散量 (携带件、火花) 属于 "到达本行为止的这一段"。所以夹取分两行: 爪子合拢那一行不带件 (爪子合拢到位前零件不出现),
 * 紧接着的停顿行才带上零件; 松开那一行不带件 → 爪子一张开零件就算装上了。
 */
function buildProgram(st, idle) {
    const hoverY = Math.max(...Object.values(st).map((s) => s.wy)) + DESCENT;
    const at = (s, level, claw, payload) => {
        const W = [s.xz[0], level === 'low' ? s.wy : hoverY, s.xz[1]];
        const k = keyAt(W, CARRY_THETA, claw, payload);
        if (!k) throw new Error(`arm: ${s.label} ${level} (${W.map(r4)}) is out of reach`);
        return { ...k, level, site: s };
    };
    // 待机点贴着换刀座 (爪尖离座面不到 0.4 px), 也算低位: 先竖直抬到安全高度再转, 回来时在安全高度转正再竖直落下。
    const dock = { label: '换刀座', xz: [idle.W[0], idle.W[2]] };
    const idleKey = { ...idle.k, level: 'idle', site: dock };
    const hoverKey = keyAt([idle.W[0], hoverY, idle.W[2]], 0, CLAW.pinch, 0);
    if (!hoverKey) throw new Error('arm: the hover point above the tool-change pedestal is out of reach');
    const idleHover = { ...hoverKey, level: 'hover', site: dock };
    const rows = [];
    let t = 0;
    const push = (label, key, extra = {}) => rows.push({ ...key, tick: t, label, spark: false, ...extra });
    const step = (dur, label, key, extra) => { t += dur; push(label, key, { linear: MOVE_J, ...extra }); };
    const hold = (dur, extra) => step(dur, extra && extra.spark ? '点焊' : '停顿', rows[rows.length - 1], extra);
    const vertical = (dur, label, key) => step(dur, label, key, { linear: MOVE_VERTICAL });
    const transfer = (dur, label, key) => step(dur, label, key, { linear: MOVE_TRANSFER });

    push('待机', idleKey);
    step(2, '待机', idleKey);
    vertical(4, '抬离换刀座', idleHover);
    hold(2);
    const op = (pickKey, placeKey, placeLabel) => {
        const pk = st[pickKey], pl = st[placeKey], code = pk.payload;
        transfer(8, `转向送料盘 · ${pk.label}`, at(pk, 'hover', CLAW.open, 0));
        hold(2);
        vertical(4, '下探取件', at(pk, 'low', CLAW.open, 0));
        hold(2);
        step(3, `夹取${pk.label}`, at(pk, 'low', CLAW.hold, 0));
        hold(2, { payload: code, label: `夹紧${pk.label}` });
        vertical(4, '竖直抬起', at(pk, 'hover', CLAW.hold, code));
        hold(2);
        transfer(8, `转运到${placeLabel}上方`, at(pl, 'hover', CLAW.hold, code));
        hold(3);
        vertical(4, '下探就位', at(pl, 'low', CLAW.hold, code));
        hold(2);
        hold(4, { spark: true });
        hold(2);
        hold(4, { spark: true });
        hold(2);
        step(3, `松开 · ${pk.label}已装上`, at(pl, 'low', CLAW.open, 0));
        hold(2);
        vertical(4, '竖直抬起', at(pl, 'hover', CLAW.open, 0));
    };
    op('bolt_pick', 'bolt_place', '机匣');
    hold(2);
    op('stock_pick', 'stock_place', '枪托');
    hold(2);
    transfer(8, '返回换刀座上方', idleHover);
    hold(2);
    vertical(4, '落回换刀座', idleKey);
    hold(160 - t);
    rows[rows.length - 1].label = '待机';
    // 下沉安装点: 只标安装点的低位行 (停顿行是复制上一行来的, 所以最后统一按 level/site 重标, 不从上一行继承)
    for (const r of rows) r.station = r.level === 'low' && r.site.place ? PLACE_STATION[r.site.place] : 0;
    return { rows, hoverY };
}
/** 写进 Java 的数值 (4 位小数) 与 Java 看到的完全一致: 预览、扫描都从这份取整后的表出发。 */
function roundRows(rows) {
    return rows.map((r) => ({ ...r, yaw: r4(r.yaw), upper: r4(r.upper), fore: r4(r.fore), spin: r4(r.spin), claw: r4(r.claw) }));
}
/**
 * 与 GunsmithArmProgram.sample 相同的插值 (实现在 raster.mjs, 预览与本脚本共用一份; 与 Java 的 float 差异 < 1e-4 rad)。
 * MOVE_J: 各关节角直接 smoothstep 插值 (只用于手臂不动的段落)。
 * MOVE_VERTICAL / MOVE_TRANSFER: 偏航、水平伸出、高度各自 smoothstep 缓动, 每个时刻反解大臂/小臂角;
 * 偏航不变时腕部走竖直直线, 高度不变时腕部在同一高度上绕肩轴平移。
 * boltDrop / stockDrop: 两个安装点的放件下沉量 (station 列非 0 的行腕部降低这么多), 夹到 [0, maxPlaceDrop]; 省略 = 不下沉。
 */
function sampleProgram(rows, tick, boltDrop = 0, stockDrop = 0, maxPlaceDrop = Infinity) {
    return sampleArmProgram({ rows, geo: { L1: ARM.L1, L2: ARM.L2, maxPlaceDrop } }, tick, boltDrop, stockDrop);
}
// 换刀座待机点也是低位 (爪尖贴着座面): 与料盘/枪上的接触点同样不许在这里转偏航。
const isLow = (r) => r.level === 'low' || r.level === 'idle';
/** 程序合理性: 总长、首尾待机、低位不转、离开/落回低位必须竖直、偏航只在安全高度改变。返回错误与统计。 */
function checkProgram(rows, hoverY) {
    const errs = [];
    const T = rows[rows.length - 1].tick;
    if (T !== 160) errs.push(`program length ${T} != 160`);
    for (let i = 1; i < rows.length; i++) if (rows[i].tick <= rows[i - 1].tick) errs.push(`program ticks not increasing at row ${i}`);
    const same = (a, b) => ['yaw', 'upper', 'fore', 'spin', 'claw'].every((k) => a[k] === b[k]) && a.payload === b.payload;
    if (!same(rows[0], rows[rows.length - 1])) errs.push('program does not end in the idle pose');
    let tail = 0;
    for (let i = rows.length - 1; i > 0 && same(rows[i], rows[i - 1]); i--) tail = T - rows[i - 1].tick;
    if (tail < 4) errs.push(`program ends with only ${tail} idle ticks`);
    let maxDev = 0, maxFloat = 0;
    const armMoves = (a, b) => ['yaw', 'upper', 'fore'].some((k) => Math.abs(a[k] - b[k]) > 1e-9);
    for (let i = 1; i < rows.length; i++) {
        const a = rows[i - 1], b = rows[i];
        const turns = Math.abs(a.yaw - b.yaw) > 1e-9;
        if ((isLow(a) || isLow(b)) && turns) errs.push(`row ${i}: yaw changes while the gripper is low`);
        if (b.linear === MOVE_J && armMoves(a, b)) errs.push(`row ${i}: the arm moves on raw joint interpolation (use a vertical or transfer move)`);
        if (b.linear === MOVE_VERTICAL && (turns || Math.abs(a.spin - b.spin) > 1e-9)) errs.push(`row ${i}: a vertical move must not turn`);
        if (b.linear === MOVE_TRANSFER && (a.level !== 'hover' || b.level !== 'hover')) errs.push(`row ${i}: a transfer must start and end at the hover height`);
        if (isLow(a) !== isLow(b) && a.site === b.site) {
            if (b.linear !== MOVE_VERTICAL) errs.push(`row ${i}: vertical move is not a straight-line segment`);
            // 悬停点与接触点在同一竖线上; 量腕部离这条竖线最远多少 (直线段理论上为 0, 只剩取整误差)
            const W0 = wristOf(a);
            for (let j = 1; j < 32; j++) {
                const w = wristOf(sampleProgram(rows, a.tick + (b.tick - a.tick) * j / 32));
                maxDev = Math.max(maxDev, Math.hypot(w[0] - W0[0], w[2] - W0[2]));
            }
        }
        if (b.linear === MOVE_TRANSFER) {
            // 平移段腕部必须一直在安全高度 (关节插值时这里会先上浮再落回)
            for (let j = 0; j <= 32; j++) maxFloat = Math.max(maxFloat, Math.abs(wristOf(sampleProgram(rows, a.tick + (b.tick - a.tick) * j / 32))[1] - hoverY));
        }
    }
    if (maxDev > 0.2) errs.push(`vertical moves drift ${r4(maxDev)}px sideways (> 0.2)`);
    if (maxFloat > 0.05) errs.push(`transfers leave the hover height by ${r4(maxFloat)}px (> 0.05)`);
    return { errs, maxDev: r4(maxDev), maxFloat: r4(maxFloat), tail };
}
/**
 * 全程序扫描: 每 tick 细分 sub 份。一律要求 >= margin, 例外只有取放的最终接触:
 * - 取放段 (任一端在料盘/枪上的低位) 携带件允许贴上目标 (间隙 >= 0, 不穿入);
 * - 送料位上的静态零件就是被夹的那一件: 在它自己的取件段里携带件与它重合 (本来就是同一件东西) 不算,
 *   爪子与它允许贴住 (>= 0, 容许 1e-3 的浮点误差), 其余时刻它和别的元素一样要 >= margin。
 * drops = [枪机, 枪托件] 安装点的放件下沉量: 任一端带下沉的程序段里零件要落到比包络低的真枪上, 这些段不查隐形包络 gun_envelope
 * (真枪由 check_bench_guns.mjs 逐把按各自的下沉量查), 只查台子本身的元素; 其余段落照旧按包络避让。
 */
function sweepProgram(rows, bodies, margin, sub = 8, drops = [0, 0], maxPlaceDrop = Infinity, onlyLowered = false) {
    const bad = [];
    const T = rows[rows.length - 1].tick;
    const pickOf = (r) => (r.level === 'low' && r.site && r.site.pickSlot ? r.site.pickSlot : null);
    const noEnvelope = bodies.filter((b) => b.name !== 'gun_envelope');
    const lowered = (r) => (r.station === ARM_STATION_BOLT ? drops[0] : r.station === ARM_STATION_STOCK ? drops[1] : 0) > 0;
    for (let i = 0; i <= T * sub; i++) {
        const t = i / sub;
        const k = sampleProgram(rows, t, drops[0], drops[1], maxPlaceDrop);
        if (k.ikMiss) { bad.push(`tick ${r4(t)}: IK unreachable`); continue; }
        const seg = Math.max(1, k.seg);
        const contact = rows[seg - 1].level === 'low' || rows[seg].level === 'low';
        const low = lowered(rows[seg - 1]) || lowered(rows[seg]);
        if (onlyLowered && !low) continue;
        const all = low ? noEnvelope : bodies;
        const slot = pickOf(rows[seg - 1]) || pickOf(rows[seg]);
        const own = slot ? all.filter((b) => b.name.startsWith(PICK_BODY + slot + '_')) : [];
        const rest = own.length ? all.filter((b) => !own.includes(b)) : all;
        const pose = armPose(k);
        const h = armHits(pose, rest, margin, true, { payloadMargin: contact ? 0 : margin });
        if (own.length) h.push(...armHits(pose, own, -1e-3, true, { skipPayload: true }));
        if (h.length) bad.push(`tick ${r4(t)}: ${h.join(', ')}`);
    }
    return bad;
}
/**
 * sweepProgram 干净的最大间隙 (二分到 0.005 px, 上限 hi); 取放接触段的携带件始终按 >= 0 算。只用于 --margins 报告。
 * onlyLowered: 只看带下沉的程序段 (安装点的下探、停顿、点焊、松开、抬起)。
 */
function sweepMargin(rows, bodies, drops, maxPlaceDrop, onlyLowered = false, hi = 2) {
    const clean = (m) => !sweepProgram(rows, bodies, m, 8, drops, maxPlaceDrop, onlyLowered).length;
    let lo = 0;
    if (!clean(lo)) return -1;
    if (clean(hi)) return hi;
    while (hi - lo > 0.005) { const mid = (lo + hi) / 2; if (clean(mid)) lo = mid; else hi = mid; }
    return r4(lo);
}

// 渲染器 easeToDock 的手写代码 (不在生成区块里): 这几行的形状变了, 下面的缓回扫描就不再对应游戏里的算法, 必须一起改
const EASE_RETURN_TICKS_RE = /private static final float RETURN_TICKS = (\d+(?:\.\d+)?)F;/;
const EASE_LIFT_RE = /float lift = Math\.max\(0\.0F, 1\.0F - s \/ (\d+(?:\.\d+)?)F\);/;
const EASE_SHAPE_RES = [
    /GunsmithArmProgram\.sample\(back\.fromProgramTick\(\), boltDrop \* lift, stockDrop \* lift, pose\);/,
    /float e = s \* s \* \(3\.0F - 2\.0F \* s\);/,
    ...['yaw', 'upperArm', 'forearm', 'toolSpin', 'claw'].map((k) => new RegExp(`pose\\.${k} \\+= \\(idlePose\\.${k} - pose\\.${k}\\) \\* e;`)),
];
/** 从渲染器源码读 easeToDock 的 RETURN_TICKS 与下沉收回比例 (lift = max(0, 1 - s / LIFT)); 读不到或算法形状变了就报错。 */
function easeToDockParams(src) {
    const errs = [];
    const ticks = EASE_RETURN_TICKS_RE.exec(src), lift = EASE_LIFT_RE.exec(src);
    if (!ticks) errs.push('ease to dock: RETURN_TICKS not found in GunsmithAssemblyBenchRenderer (update EASE_RETURN_TICKS_RE)');
    if (!lift) errs.push('ease to dock: the "float lift = Math.max(0.0F, 1.0F - s / <LIFT>F);" line not found in GunsmithAssemblyBenchRenderer.easeToDock');
    for (const re of EASE_SHAPE_RES) if (!re.test(src)) errs.push(`ease to dock: easeToDock no longer contains "${re.source.replace(/\\/g, '')}"; update sweepEaseToDock to the new blend`);
    return { returnTicks: ticks ? +ticks[1] : NaN, lift: lift ? +lift[1] : NaN, errs };
}
/**
 * 服务端在客户端程序停回待机前结束组装时, 渲染器 easeToDock 从停下的程序时刻 from 在 RETURN_TICKS 内按关节角 smoothstep 缓回
 * 待机 (与 idle 行逐个关节插值), 放件下沉在 s < LIFT 内线性收回。这段轨迹不在关键帧表里, sweepProgram 扫不到: 从下沉位置直接
 * 按关节角缓回会让爪子扫过料盘上的枪托件。这里对两个安装点的下探起点..抬起终点 (与 station 行相邻的两行之间) 每 fromStep tick
 * 一个 from、每档下沉量 (0..MAX 等分 dropLevels 档, 另一个安装点取 MAX, 不应起作用), 按每 tick sub 份扫整段缓回 (s = 0..1),
 * 任何臂段进入台子的元素即失败 (隐形包络不算: 那里是真枪, 由 check_bench_guns.mjs 逐把查)。缓回时不带件 (payload 0)。
 */
function sweepEaseToDock(rows, bodies, maxPlaceDrop, returnTicks, lift, { fromStep = 0.5, sub = 8, dropLevels = 8 } = {}) {
    const bad = [];
    const idle = rows[0];
    const noEnvelope = bodies.filter((b) => b.name !== 'gun_envelope');
    const steps = Math.max(1, Math.ceil(returnTicks * sub));
    const windows = [];
    for (const [key, code] of Object.entries(PLACE_STATION)) {
        const first = rows.findIndex((r) => r.station === code);
        let last = -1;
        rows.forEach((r, i) => { if (r.station === code) last = i; });
        if (first < 1 || last < 0 || last + 1 >= rows.length) { bad.push(`${key}: station rows not found`); continue; }
        windows.push({ key, code, lo: rows[first - 1].tick, hi: rows[last + 1].tick });
    }
    let samples = 0;
    for (const w of windows) {
        const n = Math.round((w.hi - w.lo) / fromStep);
        for (let di = 0; di <= dropLevels; di++) {
            const dv = maxPlaceDrop * di / dropLevels;
            const d = w.code === ARM_STATION_BOLT ? [dv, maxPlaceDrop] : [maxPlaceDrop, dv];
            let first = null, count = 0;
            for (let fi = 0; fi <= n; fi++) {
                const from = w.lo + (w.hi - w.lo) * fi / n;
                for (let si = 0; si <= steps; si++) {
                    const s = si / steps;
                    const f = Math.max(0, 1 - s / lift);
                    const k = sampleProgram(rows, from, d[0] * f, d[1] * f, maxPlaceDrop);
                    const e = s * s * (3 - 2 * s);
                    const L = (key) => k[key] + (idle[key] - k[key]) * e;
                    const h = armHits(armPose({ yaw: L('yaw'), upper: L('upper'), fore: L('fore'), spin: L('spin'), claw: L('claw'), payload: 0 }), noEnvelope, 0, true);
                    samples++;
                    if (k.ikMiss || h.length) { count++; first = first || `from tick ${r4(from)} s ${r4(s)}: ${k.ikMiss ? 'IK unreachable' : h.join(', ')}`; }
                }
            }
            if (count) bad.push(`${w.key} drop ${r4(dv)} (other station ${r4(maxPlaceDrop)}): ${count} poses, first ${first}`);
        }
    }
    return { bad, windows, steps, samples, fromStep, dropLevels };
}

/**
 * 两个安装点的放件下沉参数 (写进 GunsmithArmProgram 的 BOLT_PLACE_* / STOCK_PLACE_*): 未下沉的放件姿态下携带件 (含外扩) 的
 * 底面范围 (朝北整台像素, 向外取整到 1e-4) 与底面 y (向下取整, 算出的下沉量只会偏小, 零件不会压进枪里)。
 * 运行时: 台上的枪在这块范围里 (AABB 严格重叠) 的最高点 gunTop, 下沉量 = clamp(bottomY - gunTop - PLACE_CLEARANCE, 0, MAX_PLACE_DROP);
 * 范围里没有枪的方块时 gunTop = TOP_Y; 台上不画枪时下沉量 = MAX_PLACE_DROP。
 * MAX_PLACE_DROP = 空床时零件正好落在床面上方 PLACE_CLEARANCE 的下沉量 (两个安装点取小的那个)。
 */
function placeStations(rows) {
    const errs = [];
    const places = {};
    const floor4 = (v) => Math.floor(v * 10000 + 1e-7) / 10000, ceil4 = (v) => Math.ceil(v * 10000 - 1e-7) / 10000;
    for (const [key, code] of Object.entries(PLACE_STATION)) {
        const tagged = rows.filter((r) => r.station === code);
        if (!tagged.length) { errs.push(`place drop: no keyframe row carries station ${code} (${key})`); continue; }
        // 同一安装点的低位行手臂不动 (只有夹爪开合), 底面范围才是一个固定的矩形
        if (tagged.some((r) => ['yaw', 'upper', 'fore', 'spin'].some((k) => r[k] !== tagged[0][k]))) errs.push(`place drop: the ${key} station rows do not share one arm pose`);
        const payload = PAYLOADS.find((p) => p.key === key).code;
        const Mp = armMatrices(armPose({ ...tagged[0], payload }))['payload_' + key];
        const pts = ARM_SAMPLES.filter((s) => s.payload === payload).flatMap((s) => s.corners.map((p) => mApply(Mp, p)));
        const lo = [0, 1, 2].map((a) => Math.min(...pts.map((p) => p[a]))), hi = [0, 1, 2].map((a) => Math.max(...pts.map((p) => p[a])));
        places[key] = { code, minX: floor4(lo[0]), maxX: ceil4(hi[0]), minZ: floor4(lo[2]), maxZ: ceil4(hi[2]), bottomY: floor4(lo[1]), rows: tagged.length, ticks: [tagged[0].tick, tagged[tagged.length - 1].tick] };
    }
    const maxDrop = Math.floor(Math.min(...Object.values(places).map((p) => p.bottomY - GUN_BED.TOP_Y - PLACE_CLEARANCE)) * 10000 + 1e-7) / 10000;
    if (!(maxDrop >= GUN_BED.ENVELOPE_THICKNESS - 0.05)) errs.push(`place drop: MAX_PLACE_DROP ${maxDrop} cannot bring a part down to an empty bed (envelope thickness ${GUN_BED.ENVELOPE_THICKNESS})`);
    for (const r of [rows[0], rows[rows.length - 1]]) if (r.station) errs.push(`place drop: the idle row at tick ${r.tick} carries a station`);
    return { places, maxDrop, errs };
}
/** 带下沉的四种组合 (两个安装点各取 0 或 MAX_PLACE_DROP): 下探/抬起仍是竖直直线、低位行确实降低了下沉量、全程反解可达。 */
function checkPlaceDrops(rows, hoverY, maxDrop, combos) {
    const errs = [];
    let maxDev = 0, maxFloat = 0, maxLowErr = 0;
    for (const d of combos) {
        const S = (t) => sampleProgram(rows, t, d[0], d[1], maxDrop);
        for (let i = 0; i <= 160 * 8; i++) if (S(i / 8).ikMiss) { errs.push(`drops ${d}: IK unreachable at tick ${i / 8}`); break; }
        for (let i = 1; i < rows.length; i++) {
            const a = rows[i - 1], b = rows[i];
            if (b.linear === MOVE_VERTICAL) {
                const W0 = wristOf(S(a.tick));
                for (let j = 1; j <= 32; j++) { const w = wristOf(S(a.tick + (b.tick - a.tick) * j / 32)); maxDev = Math.max(maxDev, Math.hypot(w[0] - W0[0], w[2] - W0[2])); }
            }
            if (b.linear === MOVE_TRANSFER) for (let j = 0; j <= 32; j++) maxFloat = Math.max(maxFloat, Math.abs(wristOf(S(a.tick + (b.tick - a.tick) * j / 32))[1] - hoverY));
            if (b.station) {
                const want = wristOf(b), got = wristOf(S(b.tick)), drop = b.station === ARM_STATION_BOLT ? d[0] : d[1];
                maxLowErr = Math.max(maxLowErr, Math.hypot(got[0] - want[0], got[1] - (want[1] - drop), got[2] - want[2]));
            }
        }
    }
    if (maxDev > 0.2) errs.push(`place drop: vertical moves drift ${r4(maxDev)}px sideways (> 0.2)`);
    if (maxFloat > 0.05) errs.push(`place drop: transfers leave the hover height by ${r4(maxFloat)}px (> 0.05)`);
    if (maxLowErr > 0.002) errs.push(`place drop: lowered station rows miss their target by ${r4(maxLowErr)}px`);
    return { errs, maxDev: r4(maxDev), maxFloat: r4(maxFloat), maxLowErr: r4(maxLowErr) };
}
/** 点焊开始的 tick (火花段起点), 服务端按这些偏移播放焊接音。 */
function weldStarts(rows) {
    const out = [];
    for (let i = 1; i < rows.length; i++) if (rows[i].spark && !rows[i - 1].spark) out.push(rows[i - 1].tick);
    return out;
}

// ---- 机械臂贴图 (64x64 盒式 UV, 整像素面)
const ARM_MATS = {
    frame: M.frame, orange: M.orange, dark: mat('dark', '#3a4049', '#58606b', '#2a2f36', '#1c2025'), cyan: M.cyan, steel: M.chrome,
    black: M.black, tan: M.tan, rubber: M.rubber,
};
function armTexture() {
    const boxes = armBoxes();
    const cv = new Canvas(64, 64);
    cv.fill(C('#3a4049'));
    let x = 0, y = 0, rowH = 0;
    const offs = [];
    const rects = [];
    const shared = new Map();   // 同尺寸同装饰的盒子共用一块 (例如两片叉架、两只爪)
    for (const b of boxes) {
        const [, mname, deco, , , , w, h, d] = b;
        const sk = `${mname}|${deco}|${w}x${h}x${d}`;
        if (shared.has(sk)) { offs.push(shared.get(sk)); continue; }
        const nw = 2 * (d + w) + 1, nh = d + h + 1;
        if (x + nw > 64) { x = 0; y += rowH; rowH = 0; }
        if (y + nh > 64) throw new Error('arm texture overflow');
        offs.push([x, y]); shared.set(sk, [x, y]);
        rects.push({ key: sk, x, y, w: 2 * (d + w), h: d + h });
        const m = ARM_MATS[mname];
        const U = x, V = y;
        const faces = {
            up: [U + d, V, w, d], down: [U + d + w, V, w, d],
            west: [U, V + d, d, h], north: [U + d, V + d, w, h], east: [U + d + w, V + d, d, h], south: [U + d + w + d, V + d, w, h],
        };
        for (const [face, [rx, ry, rw, rh]] of Object.entries(faces)) {
            const side = face !== 'up' && face !== 'down';
            cv.rect(rx, ry, rw, rh, face === 'up' ? m.hi : face === 'down' ? m.dk : m.base);
            if (!['bolt', 'stock', 'pad'].includes(deco)) {
                if (side && rh >= 2) { cv.hline(rx, ry, rw, m.hi); cv.hline(rx, ry + rh - 1, rw, m.lo); }
                if (side && rw >= 3) { cv.vline(rx, ry + 1, rh - 2, m.hi); cv.vline(rx + rw - 1, ry + 1, rh - 2, m.lo); }
            }
            armDeco(cv, deco, face, rx, ry, rw, rh, m);
        }
        x += nw; rowH = Math.max(rowH, nh);
    }
    // 盒式 UV 区域两两不重叠 (共用的盒子本来就指向同一块)
    const errs = [];
    for (let i = 0; i < rects.length; i++) for (let j = i + 1; j < rects.length; j++) {
        const a = rects[i], b = rects[j];
        if (a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h) errs.push(`arm texture overlap ${a.key} / ${b.key}`);
    }
    return { cv, offs, errs, used: rects };
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
        // 携带件与料盘上的同款零件同一套花纹 (料盘零件的 x 向侧面 = 携带件的 z 向面 north/south)
        case 'bolt':
            if (big) { const t = new Canvas(w, h); boltSide(t); for (let j = 0; j < h; j++) for (let i = 0; i < w; i++) c.px(x + i, y + j, t.get(i, j)); }
            else if (face === 'up') { c.rect(x, y, w, h, M.chrome.lo); c.hline(x, y, w, M.chrome.hi); }   // 同 boltTop: 镀镍顶面
            break;
        case 'stock':
            if (big) { c.hline(x, y, w, M.tan.hi); c.px(x + 1, y + 1, M.tan.lo); }
            break;
        case 'pad':
            if (side) c.hline(x, y + h - 1, w, M.rubber.dk);
            break;
    }
}

const f1 = (v) => { const s = (Math.round(v * 10000) / 10000).toString(); return (s.includes('.') ? s : s + '.0') + 'F'; };
const RE_LAYER = /[ \t]*private static LayerDefinition createBodyLayer\(\) \{[\s\S]*?return LayerDefinition\.create\(mesh, \d+, \d+\);\n[ \t]*\}\n/;
const RE_GENERATED = /([ \t]*\/\/ <generated>[^\n]*\n)[\s\S]*?([ \t]*\/\/ <\/generated>[^\n]*\n)/;
function rendererPatch(base, offs) {
    const boxes = armBoxes();
    const off = armOffsets();
    const cubes = (part) => boxes.map((b, i) => [b, i]).filter(([b]) => b[0] === part)
        .map(([b, i]) => `.texOffs(${offs[i][0]}, ${offs[i][1]}).addBox(${[b[3], b[4], b[5], b[6], b[7], b[8]].map(f1).join(', ')}${b[9] ? `, new CubeDeformation(${f1(b[9])})` : ''})`);
    const I = '                        ';
    const cl = (part) => 'CubeListBuilder.create()\n' + cubes(part).map((s) => I + s).join('\n');
    const pp = (v) => (v.every((x) => x === 0) ? 'PartPose.ZERO' : `PartPose.offset(${v.map(f1).join(', ')})`);
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
        ...PAYLOADS.map((p) => child(null, 'gripper', p.part)),
        '        return LayerDefinition.create(mesh, 64, 64);',
        '    }',
        '',
    ].join('\n');
    if (!RE_LAYER.test(base)) throw new Error('renderer base: createBodyLayer() not found');
    return base.replace(RE_LAYER, layer);
}
function programPatch(base, rows, drop) {
    const S = ARM.shoulder;
    const pad = (s, n) => (s.length >= n ? s : ' '.repeat(n - s.length) + s);
    const placeConsts = (key) => {
        const p = drop.places[key], P = key.toUpperCase() + '_PLACE_', label = PAYLOADS.find((q) => q.key === key).label;
        return [
            `    /** ${label}安装点: 未下沉的放件姿态下携带件 (含外扩) 底面的范围与底面 y; 台上的枪在这块范围里的最高点决定下沉量。 */`,
            `    public static final float ${P}MIN_X = ${f1(p.minX)};`,
            `    public static final float ${P}MAX_X = ${f1(p.maxX)};`,
            `    public static final float ${P}MIN_Z = ${f1(p.minZ)};`,
            `    public static final float ${P}MAX_Z = ${f1(p.maxZ)};`,
            `    public static final float ${P}BOTTOM_Y = ${f1(p.bottomY)};`,
        ];
    };
    const body = [
        `    static final float PIVOT_X = ${f1(S[0])};`,
        `    static final float PIVOT_Y = ${f1(S[1])};`,
        `    static final float PIVOT_Z = ${f1(S[2])};`,
        `    static final float JOINT_UP = ${f1(ARM.jointUp)};`,
        `    static final float UPPER_ARM_LENGTH = ${f1(ARM.L1)};`,
        `    static final float FOREARM_LENGTH = ${f1(ARM.L2)};`,
        `    static final float TIP_DROP = ${f1(TIP_DROP)};`,
        ...PAYLOADS.map((p) => `    public static final int PAYLOAD_${p.key.toUpperCase()} = ${p.code};`),
        ...placeConsts('bolt'),
        ...placeConsts('stock'),
        '    /**',
        '     * 放件下沉量的上限 (px): 空床时零件正好落在床面上方 PLACE_CLEARANCE; 生成器对两个安装点各取 0 / 它的四种组合扫过整段程序。',
        '     * 下沉量 = clamp(BOTTOM_Y - 枪顶 - PLACE_CLEARANCE, 0, MAX_PLACE_DROP), 台上不画枪时取 MAX_PLACE_DROP。',
        '     */',
        `    public static final float MAX_PLACE_DROP = ${f1(drop.maxDrop)};`,
        '    /** 下沉后零件底面与枪顶的间隙 (px)。 */',
        `    public static final float PLACE_CLEARANCE = ${f1(PLACE_CLEARANCE)};`,
        '    private static final float[][] KEYFRAMES = {',
        '            // tick,     yaw, upperArm,  forearm, toolSpin,     claw, payload, spark, linear, station',
        ...rows.map((r) => `            {${pad(String(r.tick), 4)}, ${[r.yaw, r.upper, r.fore, r.spin, r.claw].map((v) => pad(f1(v), 8)).join(', ')}, ${pad(String(r.payload || 0), 7)}, ${pad(r.spark ? '1' : '0', 5)}, ${pad(String(r.linear || 0), 6)}, ${pad(String(r.station || 0), 7)}}, // ${r.label}`),
        '    };',
    ].join('\n') + '\n';
    if (!RE_GENERATED.test(base)) throw new Error('GunsmithArmProgram base: "// <generated>" ... "// </generated>" block not found');
    return base.replace(RE_GENERATED, (m, open, close) => open + body + close);
}
/** GunsmithGunBed.java 全文 (整个文件由本脚本写出)。 */
function gunBedJava() {
    const G = GUN_BED;
    const field = (doc, name) => [`    /** ${doc} */`, `    public static final float ${name} = ${f1(G[name])};`];
    return [
        'package com.miningdim.job.munitions.block;',
        '',
        '/**',
        ' * 枪械组装台的枪床: 运行时把台里那把枪的真实 TACZ 模型侧躺在前排胶垫上, 这里是摆放它要用的几何常量与尺寸曲线常量。',
        ' * <p>',
        ' * 由 tools/gunsmith_workstation/generate_assembly_bench.mjs 整个写出 (与方块模型、机械臂程序同一份几何), 不要手改;',
        ' * 生成器还把同一组数值导出给 JS 预览 (GUN_BED)。摆放规则在 {@link GunsmithBenchGunLayout}, 与 JS 预览逐条一致。',
        ' * 刻意不依赖任何 Minecraft 类, 客户端渲染器与 GameTest 都能直接用。',
        ' * <p>',
        ' * 坐标系: 朝北放置时的整台 (2x2) 局部像素, x 东、y 上、z 南, 原点在主格西北下角 (与 {@link GunsmithArmProgram} 相同)。',
        ` * 床面上 x ∈ [BUTT_X - MAX_LENGTH, BUTT_X]、z ∈ [AXIS_Z - HALF_WIDTH, AXIS_Z + HALF_WIDTH]、y ∈ [TOP_Y, TOP_Y + ${GUN_CLEAR_UP}]`,
        ` * 不放任何方块元素, 床面 (y = TOP_Y) 在 z 向前后各比这个范围宽出 ${BED_MARGIN}, 都由生成器校验。`,
        ' */',
        'public final class GunsmithGunBed {',
        '',
        ...field('枪托端 (东, +x) 抵住托底挡块胶垫处的 x: 枪的包围盒最大 x 放在这里, 枪口朝西。', 'BUTT_X'),
        ...field('床面 (胶垫顶面) 的 y: 枪的包围盒最小 y 放在这里。', 'TOP_Y'),
        ...field('枪的包围盒在 z 向 (横跨枪床) 的中心。', 'AXIS_Z'),
        ...field('渲染长度上限 (px), 即尺寸曲线的封顶值。', 'MAX_LENGTH'),
        ...field('平躺的枪在 z 向可用的最大半宽 (即枪剖面高度的一半, px); 剖面高度超过 2 * HALF_WIDTH 时整把枪再按它缩小。'
            + '等于机械臂扫描用的隐形枪体包络的 z 半宽, 所以任何枪都在包络的 z 范围里。', 'HALF_WIDTH'),
        ...field('尺寸曲线: 渲染长度 = min(MAX_LENGTH, SIZE_A * L^SIZE_P), L 为枪在 TACZ FIXED 定位系里的长度 (未缩放 px)。', 'SIZE_A'),
        ...field('尺寸曲线的指数, 见 {@link #SIZE_A}。', 'SIZE_P'),
        ...field('枪口参考点左侧比右侧厚出这么多 (未缩放 px) 就左侧朝上 (例如侧插弹匣), 否则右侧朝上。', 'SIDE_UP_THRESHOLD'),
        ...field('机械臂避让用的隐形枪体包络的厚度 (px): 关键帧表的安装点把零件放在 TOP_Y + ENVELOPE_THICKNESS 上,'
            + ' 运行时再按台上的枪下沉 (见 {@link GunsmithArmProgram#MAX_PLACE_DROP})。', 'ENVELOPE_THICKNESS'),
        '',
        '    private GunsmithGunBed() {',
        '    }',
        '}',
        '',
    ].join('\n');
}
/**
 * 枪床的不变量: 真枪要占的空间里没有任何可见元素, 枪躺的地方确实有床面托着、托底处确实有挡块, 隐形包络在留空范围内。
 * 常量与几何对不上 (例如只改了 TOP_Y 没改胶垫) 就报错, 不写任何文件。
 */
function gunBedChecks(E, label) {
    const G = GUN_BED, errs = [];
    const eps = 1e-6;
    const box = (e) => {
        const cs = [];
        for (const x of [e.from[0], e.to[0]]) for (const y of [e.from[1], e.to[1]]) for (const z of [e.from[2], e.to[2]]) cs.push(rotPoint(e.rot, [x, y, z], 1));
        return { lo: [0, 1, 2].map((a) => Math.min(...cs.map((c) => c[a]))), hi: [0, 1, 2].map((a) => Math.max(...cs.map((c) => c[a]))) };
    };
    const clear = { lo: [G.BUTT_X - G.MAX_LENGTH, G.TOP_Y, G.AXIS_Z - G.HALF_WIDTH], hi: [G.BUTT_X, G.TOP_Y + GUN_CLEAR_UP, G.AXIS_Z + G.HALF_WIDTH] };
    const overlaps = (b, r) => [0, 1, 2].every((a) => Math.min(b.hi[a], r.hi[a]) - Math.max(b.lo[a], r.lo[a]) > eps);
    const visible = E.filter((e) => !e.proxy && Object.keys(e.faces).length);
    for (const e of visible) if (overlaps(box(e), clear)) errs.push(`${label}: ${e.name} intrudes into the gun space ${JSON.stringify(clear)}`);
    // 床面: 枪能占的整块范围 (z 向前后再各宽出 BED_MARGIN) 每 0.25 px 都有一个顶面正好在 TOP_Y 的元素托着, 最宽的枪也不伸出床沿
    const holds = (x, z) => visible.some((e) => !e.rot && Math.abs(e.to[1] - G.TOP_Y) < eps && e.faces.up && e.from[0] <= x && e.to[0] >= x && e.from[2] <= z && e.to[2] >= z);
    bed: for (let x = clear.lo[0]; x <= clear.hi[0] + eps; x += 0.25) {
        for (let z = clear.lo[2] - BED_MARGIN; z <= clear.hi[2] + BED_MARGIN + eps; z += 0.25) {
            if (!holds(Math.min(x, clear.hi[0]), z)) { errs.push(`${label}: nothing holds the gun at x ${r4(x)} z ${r4(z)} (no top face at y ${G.TOP_Y})`); break bed; }
        }
    }
    if (ENVELOPE_HALF_Z !== G.HALF_WIDTH) errs.push(`${label}: the envelope half width ${ENVELOPE_HALF_Z} differs from HALF_WIDTH ${G.HALF_WIDTH}`);
    // 托底挡块: 西面正好在 BUTT_X, 挡住枪轴上贴着床面的那一段
    if (!visible.some((e) => !e.rot && Math.abs(e.from[0] - G.BUTT_X) < eps && e.faces.west && e.from[1] <= G.TOP_Y && e.to[1] >= G.TOP_Y + 1 && e.from[2] <= G.AXIS_Z && e.to[2] >= G.AXIS_Z)) {
        errs.push(`${label}: no butt stop face at x ${G.BUTT_X}`);
    }
    const env = E.find((e) => e.name === 'gun_envelope');
    if (!env || !env.proxy) errs.push(`${label}: gun_envelope proxy missing`);
    else if (env.from[1] < G.TOP_Y - eps || [0, 2].some((a) => env.from[a] < clear.lo[a] - eps || env.to[a] > clear.hi[a] + eps)) errs.push(`${label}: gun_envelope leaves the gun space`);
    return errs;
}

// ------------------------------------------------------------ 物品模型: 台子 (0.5 缩放) + 放大的步枪 (主角) + 小号机械臂替身
// 32px 图标里要一眼认出枪: 去掉墙和小摆件, 台子只留柜体/台面/屏幕/维修垫作为浅色底座;
// 方块上的枪是运行时渲染的真枪 (空台没有枪), 图标里放一把固定的步枪 (itemRifle, 只进物品模型),
// 剖面朝北立在台面前沿, 绕 z 轴 -22.5° 斜放 (枪口朝右上), 长度铺满整格。
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
const jsonText = (obj) => JSON.stringify(JSON.parse(JSON.stringify(obj, (k, v) => (k === '__name' ? undefined : v))), null, 2) + '\n';
function findJavaBase(rel) {
    for (const p of [path.join(OUT, rel), path.resolve(SCRIPT_DIR, '..', '..', rel)]) if (fs.existsSync(p)) { const raw = fs.readFileSync(p, 'utf8'); return { file: p, src: raw.replace(/\r\n/g, '\n'), crlf: raw.includes('\r\n') }; }
    throw new Error(path.basename(rel) + ' not found (neither in --out nor in the repo next to this script)');
}

function main() {
    const idle = buildScene(false);
    const active = buildScene(true);
    const stand = armStandIn();
    const rifle = itemRifle();
    const errors = [];
    const outputs = [];   // 校验全部通过后才写盘
    const emit = (p, data) => outputs.push([p, data]);
    for (const [E, label] of [[idle, 'idle'], [active, 'active']]) { cullHidden(E); errors.push(...zfightCheck(E, label), ...gunBedChecks(E, label)); }
    const { size: A, atlas, items, regions, swatches } = buildAtlas([idle, active, stand, rifle]);

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
            emit(path.join(RES, 'models', 'block', name + '.json'), jsonText(model));
        }
    }
    idle.find((e) => e.name === 'worktop').itemTop = { plan: stand.find((e) => e.swatchOnly).plan.up };
    const itemEls = itemModel([...idle, ...stand, ...rifle], A);
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
    emit(path.join(RES, 'models', 'item', 'gunsmith_assembly_bench.json'), jsonText(item));

    // 贴图
    const texDir = path.join(RES, 'textures', 'block');
    emit(path.join(texDir, 'gunsmith_assembly_atlas.png'), encodePng(A, A, atlas.data));
    for (let i = 3; i < atlas.data.length; i += 4) if (atlas.data[i] !== 255) { errors.push('atlas has non-opaque pixels'); break; }
    const pt = particleTex();
    emit(path.join(texDir, 'gunsmith_assembly_particle.png'), encodePng(16, 16, pt.data));

    // 机械臂: 反解取放点 → 关键帧程序 → 按写出的 (4 位小数) 数值逐 tick 细分扫描 → 只替换两个 Java 文件里的生成区块
    const bodiesAll = collisionBodies([...idle, ...active.filter((e) => !idle.some((f) => f.name === e.name))]);
    const idlePose = solveIdle(bodiesAll);
    const stations = solveStations(bodiesAll);
    const prog = buildProgram(stations, idlePose);
    const rows = roundRows(prog.rows);
    const chk = checkProgram(rows, prog.hoverY);
    errors.push(...chk.errs);
    // 放件下沉: 安装点底面参数 → 两个安装点各取 0 / MAX_PLACE_DROP 的四种组合 (再加两处都取一半), 每种都查竖直直线与整段程序的穿模
    const drop = placeStations(rows);
    errors.push(...drop.errs);
    const M_ = drop.maxDrop;
    const combos = [[0, 0], [M_, M_], [0, M_], [M_, 0], [r4(M_ / 2), r4(M_ / 2)]];   // 末一组: 中间值, 防两端都干净而中途擦到
    const dchk = checkPlaceDrops(rows, prog.hoverY, M_, combos);
    errors.push(...dchk.errs);
    const sweeps = combos.map((d) => ({ d, bad: sweepProgram(rows, bodiesAll, 0.3, 8, d, M_) }));
    for (const s of sweeps) if (s.bad.length) errors.push(`arm sweep (drops ${s.d.join('/')}): ` + s.bad.slice(0, 6).join(' | ') + (s.bad.length > 6 ? ` (+${s.bad.length - 6})` : ''));
    const { cv: armCv, offs, errs: texErrs, used } = armTexture();
    errors.push(...texErrs);
    const rBase = findJavaBase(JAVA_REL), pBase = findJavaBase(PROGRAM_REL);
    // 渲染器的缓回待机 (easeToDock): RETURN_TICKS 与下沉收回比例按渲染器底稿里的手写值扫, 改了哪个都按新值重扫
    const easeParams = easeToDockParams(rBase.src);
    errors.push(...easeParams.errs);
    const ease = easeParams.errs.length ? null : sweepEaseToDock(rows, bodiesAll, M_, easeParams.returnTicks, easeParams.lift);
    if (ease) for (const b of ease.bad) errors.push('ease to dock: ' + b);
    const renderer = rendererPatch(rBase.src, offs);
    const program = programPatch(pBase.src, rows, drop);
    // 手写的 sample() 按 station 列取下沉量: 列号与取值必须与生成器写的表一致
    for (const [re, what] of [[/COL_STATION = 9;/, 'COL_STATION = 9'], [new RegExp(`STATION_BOLT = ${ARM_STATION_BOLT};`), `STATION_BOLT = ${ARM_STATION_BOLT}`], [new RegExp(`STATION_STOCK = ${ARM_STATION_STOCK};`), `STATION_STOCK = ${ARM_STATION_STOCK}`]]) {
        if (!re.test(pBase.src)) errors.push(`GunsmithArmProgram base: hand-written ${what} not found (the station column would be misread)`);
    }
    for (const nm of ARM_TREE.map(([p]) => p)) if (!renderer.includes(`addOrReplaceChild("${nm}"`)) errors.push('arm part missing ' + nm);
    // 除生成区块外, 其余文本必须与底稿一致
    if (renderer.replace(RE_LAYER, '') !== rBase.src.replace(RE_LAYER, '')) errors.push('renderer patch touched text outside createBodyLayer');
    if (program.replace(RE_GENERATED, '') !== pBase.src.replace(RE_GENERATED, '')) errors.push('program patch touched text outside the generated block');
    emit(path.join(RES, 'textures', 'entity', 'gunsmith_assembly_arm.png'), encodePng(64, 64, armCv.data));
    emit(JAVA_OUT, rBase.crlf ? renderer.replace(/\n/g, '\r\n') : renderer);
    emit(path.join(OUT, PROGRAM_REL), pBase.crlf ? program.replace(/\n/g, '\r\n') : program);
    emit(path.join(OUT, GUN_BED_REL), gunBedJava());

    console.log(`atlas ${A}x${A}: ${regions} painted regions, ${swatches} swatches`);
    console.log('elements:', JSON.stringify(stats));
    console.log(`max model height (block px): ${r4(maxY)}`);
    const deg = (r) => r4(r * 180 / Math.PI);
    console.log(`arm texture: ${used.length} regions, lowest row ends at v=${Math.max(...used.map((u) => u.y + u.h))}`);
    console.log(`arm idle wrist ${idlePose.W.map(r4)}; safe hover height (wrist) ${r4(prog.hoverY)}`);
    for (const [k, s] of Object.entries(stations)) {
        const kk = keyAt([s.xz[0], s.wy, s.xz[1]], CARRY_THETA, CLAW.hold, s.payload);
        console.log(`  ${k.padEnd(12)} wrist (${r4(s.xz[0])}, ${s.wy}, ${r4(s.xz[1])}) part bottom y ${r4(s.wy - TIP_DROP)}, part gap ${s.gap}px on ${s.blockers.join('/')}; yaw ${deg(kk.yaw)}°, spin ${deg(kk.spin)}°`);
    }
    console.log(`program: ${rows.length} keyframes, ${rows[rows.length - 1].tick} ticks, end hold ${chk.tail}, weld starts ${JSON.stringify(weldStarts(rows))}, vertical drift ${chk.maxDev}px, transfer height drift ${chk.maxFloat}px`);
    for (const [k, p] of Object.entries(drop.places)) {
        console.log(`  place drop ${k.padEnd(5)} station ${p.code}: ${p.rows} rows (ticks ${p.ticks[0]}..${p.ticks[1]}), part footprint x ${p.minX}..${p.maxX} z ${p.minZ}..${p.maxZ}, bottom y ${p.bottomY} (incl. CubeDeformation)`);
    }
    console.log(`place drop: MAX_PLACE_DROP ${M_}, PLACE_CLEARANCE ${PLACE_CLEARANCE}; with drops: vertical drift ${dchk.maxDev}px, transfer height drift ${dchk.maxFloat}px, lowered rows off target ${dchk.maxLowErr}px`);
    for (const s of sweeps) console.log(`arm sweep drops ${s.d.join('/').padEnd(11)} (${rows[rows.length - 1].tick * 8} samples, 0.3px margin, part contact >= 0${s.d.some((v) => v > 0) ? ', envelope off in lowered segments' : ''}): ${s.bad.length ? 'FAIL' : 'clean'}`);
    if (ease) console.log(`ease to dock (RETURN_TICKS ${easeParams.returnTicks}, drops lifted out over s < ${easeParams.lift}; from ticks ${ease.windows.map((w) => `${w.lo}..${w.hi}`).join(' / ')} every ${ease.fromStep}, ${ease.steps} steps per blend, drops 0..MAX in ${ease.dropLevels + 1} levels with the other station at MAX, ${ease.samples} samples, no envelope): ${ease.bad.length ? 'FAIL' : 'clean'}`);
    if (REPORT_MARGINS && !errors.length) {
        // 最小间隙 (二分): 全程 / 只看带下沉的段落; 取放接触段的携带件按 >= 0 算, 不计入
        for (const s of sweeps) {
            const low = s.d.some((v) => v > 0) ? `, lowered segments ${sweepMargin(rows, bodiesAll, s.d, M_, true)} px` : '';
            console.log(`  margin drops ${s.d.join('/').padEnd(11)}: whole program ${sweepMargin(rows, bodiesAll, s.d, M_)} px${low}`);
        }
    }
    console.log(`gun bed: ${Object.entries(GUN_BED).map(([k, v]) => `${k}=${v}`).join(' ')}; gun space x ${r4(GUN_BED.BUTT_X - GUN_BED.MAX_LENGTH)}..${GUN_BED.BUTT_X}, z ${GUN_BED.AXIS_Z - GUN_BED.HALF_WIDTH}..${GUN_BED.AXIS_Z + GUN_BED.HALF_WIDTH}, y ${GUN_BED.TOP_Y}..${GUN_BED.TOP_Y + GUN_CLEAR_UP}; bed surface x ${BED.x0 + 0.5}..${GUN_BED.BUTT_X}, z ${BED.z0}..${BED.z1}`);
    if (errors.length) { console.error('VALIDATION FAILED (nothing written):\n  ' + errors.join('\n  ')); process.exit(1); }
    // 内容没变的文件不重写 (渲染器/方块实体这些 Java 文件可能正被别人同时改, 少碰一次是一次)
    let written = 0;
    for (const [p, data] of outputs) {
        const buf = Buffer.isBuffer(data) ? data : Buffer.from(data, 'utf8');
        if (fs.existsSync(p) && fs.readFileSync(p).equals(buf)) continue;
        fs.mkdirSync(path.dirname(p), { recursive: true }); fs.writeFileSync(p, buf); written++;
    }
    console.log(`validation OK, wrote ${written} of ${outputs.length} files (the rest were unchanged)`);
}

if (IS_MAIN) main();
export { armMatrices, armPose, armBoxes, ARM_SAMPLES, mApply, collisionBodies, buildScene, armHits, insideBody, sampleProgram, wristOf, PAYLOADS, TIP_DROP, GUN_BED };
