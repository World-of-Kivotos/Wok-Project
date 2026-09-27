#!/usr/bin/env node
// 方案 press_a (v2, 按评审意见修改): 四柱液压冲压机 (机械冲压机 gunsmith_press) 的模型与图集生成器。
// 用法: node generate.mjs --out <根目录>
// 写出: <根>/src/main/resources/assets/miningdim/models/block/gunsmith_press{,_active}.json
//       <根>/src/main/resources/assets/miningdim/models/item/gunsmith_press.json
//       <根>/src/main/resources/assets/miningdim/textures/block/gunsmith_press_atlas.png
// 零依赖; 只 import 仓库 tools/gunsmith_workstation/png.mjs 做 PNG 编码。
// 校验在写文件之前做: 任何一项不过就不写任何文件, 退出码 1。
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const { encodePng } = await import(pathToFileURL(path.resolve(HERE, 'png.mjs')).href);

const TEX_ID = 'miningdim:block/gunsmith_press_atlas';

// ---------------------------------------------------------------- 调色板
const C = {
    panel: { hi: [246, 248, 250], base: [218, 223, 229], lo: [158, 166, 177], seam: [150, 158, 170] },
    panelB: { hi: [230, 234, 239], base: [196, 202, 210], lo: [140, 148, 160], seam: [132, 140, 152] },
    gun: { hi: [140, 150, 166], base: [90, 99, 115], lo: [58, 64, 76], seam: [44, 49, 58] },
    // 滑块侧面用的浅枪灰: 东西面在游戏里会被压到 ×0.6, 底色要比正面亮一档才不发黑
    gunL: { hi: [176, 184, 198], base: [126, 135, 150], lo: [86, 94, 108], seam: [70, 77, 90] },
    dark: { hi: [72, 78, 90], base: [46, 51, 60], lo: [30, 33, 40], seam: [24, 27, 33] },
    plate: { hi: [96, 104, 118], base: [20, 22, 28], lo: [96, 104, 118], seam: [10, 11, 14] },
    steel: { hi: [226, 232, 238], base: [176, 185, 195], lo: [118, 127, 138], seam: [98, 106, 117] },
    steelHi: { hi: [248, 250, 252], base: [214, 221, 228], lo: [140, 149, 160], seam: [98, 106, 117] },
    brass: { hi: [246, 214, 118], base: [206, 162, 60], lo: [140, 104, 34], seam: [112, 82, 26] },
    warnY: [244, 194, 44], warnK: [38, 38, 44],
    cyan: [96, 244, 240], cyanMid: [36, 200, 214], cyanDim: [44, 118, 130], cyanDark: [22, 58, 68], cyanOff: [70, 140, 150],
    cyanIdle: [63, 167, 176],
    hot0: [255, 244, 176], hot1: [255, 178, 56], hot2: [238, 102, 26], hot3: [160, 46, 14],
    red: [222, 52, 50], redHi: [255, 128, 118], redLo: [140, 22, 26],
    green: [64, 206, 96], greenHi: [170, 255, 178], greenLo: [28, 118, 52], greenOff: [52, 96, 66],
    amberOff: [140, 104, 40], redOff: [110, 44, 44],
    cavity: [34, 38, 46], cavityDeep: [18, 20, 25],
    ink: [34, 38, 46],
    // 发蓝枪钢 (成品手枪): 深色剪影衬在白色滑块面板/横梁前, 远看和物品栏里都清楚
    blued: { hi: [118, 128, 146], base: [46, 50, 60], lo: [24, 26, 32], seam: [14, 15, 19], mid: [84, 92, 106], grip: [58, 63, 75] },
};

// ---------------------------------------------------------------- 贴图画笔
// 每个面分到图集里的一个矩形; 画笔在局部坐标 (0..w-1, 0..h-1) 里作画。
// 侧面: 贴图上方 = 世界上方, 贴图左 = 从外面看的左边。顶面: 贴图上方 = 北, 左 = 西。
function mkCtx(img, rx, ry, w, h) {
    const set = (x, y, col) => {
        if (x < 0 || y < 0 || x >= w || y >= h || !col) return;
        const o = ((ry + y) * img.width + rx + x) * 4;
        img.data[o] = col[0]; img.data[o + 1] = col[1]; img.data[o + 2] = col[2]; img.data[o + 3] = 255;
    };
    const X = (v) => (v < 0 ? w + v : v);
    const Y = (v) => (v < 0 ? h + v : v);
    const rect = (x, y, rw, rh, col) => { for (let j = 0; j < rh; j++) for (let i = 0; i < rw; i++) set(X(x) + i, Y(y) + j, col); };
    return { w, h, set, rect, X, Y };
}

const mix = (a, b, t) => a.map((v, i) => Math.round(v + (b[i] - v) * t));
const colOf = (v) => (typeof v === 'string' ? C[v] : v);

function bevel(c, pal, opts = {}) {
    c.rect(0, 0, c.w, c.h, pal.base);
    if (opts.flat) return;
    // 只有 1 贴图像素宽/高的细面 (0.5px 元素的侧面): 保持底色, 只在两端点缀高光/暗边,
    // 否则整条会被下沿暗边盖成暗色 (v1 的"细件发黑"就是这个原因)
    if (c.h === 1 || c.w === 1) {
        if (c.w > 2 || c.h > 2) { c.set(0, 0, pal.hi); c.set(c.w - 1, c.h - 1, pal.lo); }
        return;
    }
    for (let x = 0; x < c.w; x++) { c.set(x, 0, pal.hi); c.set(x, c.h - 1, pal.lo); }
    for (let y = 0; y < c.h; y++) { c.set(0, y, pal.hi); c.set(c.w - 1, y, pal.lo); }
    if (c.w > 1 && c.h > 1) { c.set(c.w - 1, 0, pal.base); c.set(0, c.h - 1, pal.base); }
}

// ---- 下模顶面的手枪型腔 (2x 贴图, 15x6), 按"操作员视角"画: 枪口朝右, 握把朝下 (向后/左倾)。
// 画到顶面时旋转 180° (顶面贴图: 上 = 北 = 靠近操作员, 左 = 西 = 操作员的右边)。
const CAVITY = [
    '...############',
    '..#############',
    '..#####.#..#...',
    '.#####..####...',
    '.####..........',
    '####...........',
];

// 步枪剪影 20x5, 枪口朝右 (M4 式: 3px 机匣 + 2px 护木 + 1px 枪管, 空心枪托管, 2px 后倾握把, 前弯弹匣)
const RIFLE = [
    '......####...#......',
    '##...###########....',
    '####################',
    '###....##.##........',
    '##.....#...##.......',
];

// 装饰指令。参数为局部像素坐标, 负数表示从右/下边数。
const DECO = {
    hline(c, [y, col, x0 = 1, x1 = -2]) { for (let x = c.X(x0); x <= c.X(x1); x++) c.set(x, c.Y(y), colOf(col)); },
    vline(c, [x, col, y0 = 1, y1 = -2]) { for (let y = c.Y(y0); y <= c.Y(y1); y++) c.set(c.X(x), y, colOf(col)); },
    rect(c, [x, y, w, h, col]) { c.rect(x, y, w, h, colOf(col)); },
    seamV(c, [x, pal]) { const P = C[pal]; for (let y = 1; y < c.h - 1; y++) { c.set(c.X(x), y, P.seam); c.set(c.X(x) + 1, y, P.hi); } },
    seamH(c, [y, pal]) { const P = C[pal]; for (let x = 1; x < c.w - 1; x++) { c.set(x, c.Y(y), P.seam); c.set(x, c.Y(y) + 1, P.hi); } },
    bolts(c, [col = [120, 128, 140], inset = 1]) {
        for (const [x, y] of [[inset, inset], [c.w - 1 - inset, inset], [inset, c.h - 1 - inset], [c.w - 1 - inset, c.h - 1 - inset]]) c.set(x, y, colOf(col));
    },
    vents(c, [x0, y0, x1, y1, pal = 'dark', step = 2]) {
        const P = C[pal];
        for (let y = c.Y(y0); y <= c.Y(y1); y += step) for (let x = c.X(x0); x <= c.X(x1); x++) c.set(x, y, P.lo);
        for (let y = c.Y(y0) + 1; y <= c.Y(y1); y += step) for (let x = c.X(x0); x <= c.X(x1); x++) c.set(x, y, P.base);
    },
    warn(c, [x0 = 0, y0 = 0, w = null, h = null, period = 4]) {
        const W = w == null ? c.w : w, H = h == null ? c.h : h;
        for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
            const k = ((x + y) % period + period) % period;
            c.set(c.X(x0) + x, c.Y(y0) + y, k < period / 2 ? C.warnY : C.warnK);
        }
    },
    // 圆柱感竖条 (立柱 / 活塞杆 / 缸体): 横向亮暗渐变
    cyl(c, [pal = 'chrome']) {
        const ramp = pal === 'chrome'
            ? [[140, 150, 166], [236, 242, 246], [196, 204, 214], [152, 162, 176], [112, 120, 134], [88, 95, 108]]
            : pal === 'gunCyl'
                ? [[104, 114, 130], [178, 188, 202], [128, 138, 154], [98, 107, 123], [80, 88, 103], [62, 68, 81]]
                : pal === 'gunEdge'
                    ? [[70, 77, 90], [70, 77, 90]]
                    : [[140, 104, 34], [246, 214, 118], [206, 162, 60], [170, 130, 44], [140, 104, 34], [112, 82, 26]];
        for (let x = 0; x < c.w; x++) {
            const t = c.w === 1 ? 0.3 : x / (c.w - 1);
            const col = ramp[Math.min(ramp.length - 1, Math.floor(t * ramp.length))];
            for (let y = 0; y < c.h; y++) c.set(x, y, col);
        }
    },
    // 下模顶面 (2x): 枪灰模具 + 手枪型腔 (深色剪影 + 1 贴图像素黄铜描边)
    dieTop(c, [ox = 0, oy = 1]) {
        bevel(c, C.gun);
        const H = CAVITY.length, W = CAVITY[0].length;
        // 操作员视角 (vx, vy) -> 贴图 (ox + W-1-vx, oy + H-1-vy)
        const at = (tx, ty) => {
            const vx = W - 1 - (tx - ox), vy = H - 1 - (ty - oy);
            return vy >= 0 && vy < H && vx >= 0 && vx < W && CAVITY[vy][vx] === '#';
        };
        for (let ty = 0; ty < c.h; ty++) for (let tx = 0; tx < c.w; tx++) {
            if (at(tx, ty)) { c.set(tx, ty, C.cavityDeep); continue; }
            let near = false;
            for (let dy = -1; dy <= 1 && !near; dy++) for (let dx = -1; dx <= 1 && !near; dx++) near = at(tx + dx, ty + dy);
            if (near) c.set(tx, ty, C.brass.base);
        }
    },
    // 立着的手枪: 套筒侧面 (北面, 14x3; 贴图左 = 东 = 枪尾): 后部防滑纹 + 抛壳窗 + 下沿分模线
    // 剪影要干净: 整块亮钢, 只留抛壳窗和两道防滑纹, 不画整条暗线 (否则远看会碎成横条)
    slideSide(c) {
        c.rect(0, 0, c.w, c.h, C.blued.base);
        for (let x = 0; x < c.w; x++) c.set(x, 0, C.blued.hi);
        for (const x of [1, 3]) c.set(x, 1, C.blued.mid);
        for (const x of [6, 7, 8]) c.set(x, 1, C.blued.seam);
    },
    // 握把侧面 (4x2 每段): 略浅的防滑握片; bottom = 弹匣底板 (亮一档)
    gripSide(c, [bottom = false]) {
        c.rect(0, 0, c.w, c.h, C.blued.grip);
        c.set(1, 0, C.blued.mid); c.set(2, 1, C.blued.mid);
        if (bottom) for (let x = 0; x < c.w; x++) c.set(x, c.h - 1, C.gun.hi);
    },
    // 枪口 (套筒西端面)
    muzzle(c) { bevel(c, C.blued); c.set(Math.floor(c.w / 2), 1, C.blued.seam); },
    // 出料托盘里的套筒坯 (顶面 3x6, 长边沿 z): 抛壳窗 + 防滑纹
    slideBlank(c) {
        bevel(c, C.steelHi);
        c.set(1, 2, C.ink); c.set(1, 3, C.ink);
        c.set(0, c.h - 1, C.steel.lo); c.set(2, c.h - 1, C.steel.lo);
        c.set(1, 0, C.cavityDeep);
    },
    // 弹匣壳 (顶面 2x5): 供弹口 + 观察孔
    magBlank(c) {
        bevel(c, C.steelHi);
        c.set(0, 0, C.ink); c.set(1, 0, C.ink);
        c.set(1, 2, C.steel.lo); c.set(1, 3, C.steel.lo);
    },
    // 步枪剪影铭牌: 1px 边框 + 剪影 (20x5)
    rifle(c, [col, mirror = false, ox = 2, oy = 2]) {
        const cc = colOf(col);
        for (let y = 0; y < RIFLE.length; y++) for (let x = 0; x < RIFLE[0].length; x++) {
            if (RIFLE[y][x] !== '#') continue;
            c.set(ox + (mirror ? RIFLE[0].length - 1 - x : x), oy + y, cc);
        }
    },
    // 控制屏
    screen(c, [on]) {
        c.rect(0, 0, c.w, c.h, C.dark.lo);
        c.rect(1, 1, c.w - 2, c.h - 2, on ? [18, 64, 78] : C.cyanDark);
        if (on) {
            for (let x = 1; x < c.w - 1; x++) c.set(x, 1, C.cyanMid);
            c.set(c.w - 2, 1, C.green);
            [0.9, 0.55].forEach((f, i) => {
                const y = 3 + i * 1;
                const n = Math.max(1, Math.round((c.w - 3) * f));
                for (let x = 0; x < c.w - 3; x++) c.set(2 + x, y, x < n ? C.cyan : [30, 90, 104]);
            });
        } else {
            for (let x = 1; x < c.w - 1; x++) c.set(x, 1, C.cyanDim);
            c.set(2, 3, C.cyanDim); c.set(3, 3, C.cyanDim);
            c.set(c.w - 2, 1, [150, 120, 40]);
        }
    },
    halo(c, [on]) {
        const cx = (c.w - 1) / 2, cy = (c.h - 1) / 2;
        const r = Math.min(c.w, c.h) / 2 - 1.2;
        for (let y = 0; y < c.h; y++) for (let x = 0; x < c.w; x++) {
            const d = Math.hypot(x - cx, y - cy);
            if (Math.abs(d - r) < 0.75) c.set(x, y, on ? C.cyan : C.cyanOff);
            else if (d < r - 0.75) c.set(x, y, d < 1.2 ? C.brass.base : C.gun.lo);
        }
    },
    hot(c, [mode = 'v']) {
        for (let y = 0; y < c.h; y++) for (let x = 0; x < c.w; x++) {
            const t = mode === 'v' ? (c.h === 1 ? 0 : y / (c.h - 1)) : 0.3;
            const ramp = [C.hot0, C.hot1, C.hot2, C.hot3];
            const f = t * (ramp.length - 1);
            const i = Math.min(ramp.length - 2, Math.floor(f));
            c.set(x, y, mix(ramp[i], ramp[i + 1], f - i));
        }
    },
    hotPlate(c) {
        for (let y = 0; y < c.h; y++) for (let x = 0; x < c.w; x++) {
            const edge = Math.min(x, y, c.w - 1 - x, c.h - 1 - y);
            c.set(x, y, edge === 0 ? C.hot2 : edge === 1 ? C.hot1 : C.hot0);
        }
    },
    knob(c, [pal]) {
        const P = { red: [C.redHi, C.red, C.redLo], green: [C.greenHi, C.green, C.greenLo] }[pal];
        c.rect(0, 0, c.w, c.h, P[1]);
        for (let x = 0; x < c.w; x++) c.set(x, c.h - 1, P[2]);
        for (let y = 0; y < c.h; y++) c.set(c.w - 1, y, P[2]);
        c.set(0, 0, P[0]); c.set(1, 0, P[0]); c.set(0, 1, P[0]);
    },
    pix(c, [list]) { for (const [x, y, col] of list) c.set(c.X(x), c.Y(y), colOf(col)); },
};

function paintFace(c, spec) {
    if (spec.base && spec.base !== 'none') {
        const pal = typeof spec.base === 'string' ? C[spec.base] : spec.base;
        if (Array.isArray(pal)) c.rect(0, 0, c.w, c.h, pal);
        else bevel(c, pal, { flat: spec.flat });
    }
    for (const [name, ...args] of spec.deco || []) {
        if (!DECO[name]) throw new Error('unknown deco ' + name);
        DECO[name](c, args);
    }
}

// ---------------------------------------------------------------- 几何
// 元素: { name, from, to, den, faces: {all|north|...: spec}, skip: [...], rot, emit, emitFaces, noShade }
const FACES = ['north', 'south', 'east', 'west', 'up', 'down'];

function faceDims(face, from, to) {
    const dx = to[0] - from[0], dy = to[1] - from[1], dz = to[2] - from[2];
    if (face === 'north' || face === 'south') return [dx, dy];
    if (face === 'east' || face === 'west') return [dz, dy];
    return [dx, dz];
}

function S(base, ...deco) { return { base, deco }; }
const hotSide = { base: 'none', deco: [['hot', 'v']] };

// ---- 立着的手枪 (待机时放在下模后半的检具上)。部件坐标 u 从枪尾 (东) 量向枪口 (西), v 从底面向上;
//      世界坐标 x = X0 - u, y = Y0 + v, z 固定。握把用错开 0.5 的台阶拼出后倾 (不用旋转, 避免与机匣共面)。
const PISTOL_S = {
    X0: 11.5, Y0: 7.5, z: [4.5, 5.5], clamp: [-0.5, 2.5],
    parts: [
        ['slide', 0, 3.5, 7, 5], ['sight_f', 6, 5, 6.5, 5.5], ['sight_r', 0, 5, 1, 5.5],
        ['frame', 0.5, 3, 5.5, 3.5], ['guard_f', 5, 1.5, 5.5, 3], ['guard_b', 2.5, 1.5, 5, 2], ['trigger', 3.5, 2, 4, 3],
        ['grip_top', 1, 2, 3, 3], ['grip', 0.5, 1, 2.5, 2], ['grip_bot', 0, 0, 2, 1],
    ],
};
// 物品模型专用的大号手枪 (32px 图标里也要认得出)
const PISTOL_L = {
    X0: 13, Y0: 7.5, z: [4.5, 5.5], clamp: [0, 3.5],
    parts: [
        ['slide', 0, 3.5, 10, 5], ['sight_f', 9, 5, 9.5, 5.5], ['sight_r', 0, 5, 1, 5.5],
        ['frame', 1, 3, 8, 3.5], ['guard_f', 7, 1.5, 7.5, 3], ['guard_b', 3.5, 1.5, 7, 2], ['trigger', 5, 2, 5.5, 3],
        ['grip_top', 1.5, 2, 4, 3], ['grip', 1, 1, 3.5, 2], ['grip_bot', 0.5, 0, 3, 1],
    ],
};
function addPistol(add, P) {
    const hi = S('blued');
    const grip = (bottom) => ({ base: 'none', deco: [['gripSide', bottom]] });
    const kinds = {
        slide: [{ all: hi, north: { base: 'none', deco: [['slideSide']] }, west: { base: 'none', deco: [['muzzle']] } }, []],
        sight_f: [{ all: S('steel') }, ['down']],
        sight_r: [{ all: S('steel') }, ['down']],
        frame: [{ all: hi }, ['up']],
        guard_f: [{ all: hi }, ['up']],
        guard_b: [{ all: hi }, ['west', 'east']],
        trigger: [{ all: S('brass') }, ['up', 'down']],
        grip_top: [{ all: grip(false) }, ['up']],
        grip: [{ all: grip(false), up: S('steel') }, []],
        grip_bot: [{ all: grip(true), up: S('steel') }, ['down']],
    };
    for (const [kind, u0, v0, u1, v1] of P.parts) {
        const [faces, skip] = kinds[kind];
        add({ name: 'pistol_' + kind, from: [P.X0 - u1, P.Y0 + v0, P.z[0]], to: [P.X0 - u0, P.Y0 + v1, P.z[1]], den: 2, skip, faces });
    }
    // 检具立柱: 从下模顶面 (y 6) 托住握把底
    add({ name: 'pistol_fixture', from: [P.X0 - P.clamp[1], 6, P.z[0] - 0.5], to: [P.X0 - P.clamp[0], P.Y0, P.z[1] + 0.5], den: 2, skip: ['down'],
        faces: { all: S('gun', ['hline', 0, C.brass.base, 0, -1]), up: S('gun', ['bolts', C.brass.base, 0]) } });
}

// 立面高度 (模型像素)
const Y_COL_TOP = 11;   // 立柱顶 = 横梁底
const Y_CROWN_TOP = 13; // 横梁顶
const ZF = 6;           // 横梁/滑块前沿 (留出下模顶面 z 1..5.5 给俯视)

function buildElements(active, forItem = false) {
    const E = [];
    const add = (e) => { E.push(e); return e; };

    // ---- 底座脚框 (枪灰)
    add({ name: 'footing', from: [0.5, 0, 0.5], to: [15.5, 0.5, 15.5], den: 2, skip: ['down'],
        faces: { all: S('gun'), up: { base: 'gun', den: 1 } } });

    // ---- 床身 (浅灰白面板); 北面大部分被正面铭牌挡住
    add({ name: 'bed', from: [1, 0.5, 1], to: [15, 3.5, 15], den: 1, skip: ['down'],
        faces: {
            all: S('panel', ['seamV', 1, 'panel'], ['seamV', -3, 'panel']),
            south: S('panel', ['vents', 2, 1, -3, 1, 'gun']),
            up: S('panelB', ['seamH', 4, 'panelB']),
        } });

    // ---- 三块步枪铭牌 (24x8 贴图像素, 2x): 正面白底深色剪影; 两侧深底青色剪影 (工作时发光)
    add({ name: 'plate_front', from: [2.5, 0, 0], to: [14.5, 4, 1], den: 2, skip: ['down', 'south', 'west'],
        faces: {
            all: S('panel'),
            north: { base: 'panel', deco: [['rifle', 'ink', false]] },
        } });
    const rifleCol = active ? C.cyan : C.cyanIdle;
    for (const [side, x0] of [['w', 0], ['e', 15]]) {
        const outer = side === 'w' ? 'west' : 'east';
        add({ name: 'plate_' + side, from: [x0, 0, 2], to: [x0 + 1, 4, 14], den: 2,
            skip: ['down', side === 'w' ? 'east' : 'west'],
            faces: {
                all: S('gun'),
                [outer]: { base: 'plate', deco: [['rifle', rifleCol, side === 'w']] },
            },
            emitFaces: active ? { [outer]: 15 } : {} });
    }

    // ---- 滑轨 (移动工作台轨道)
    for (const x of [4.5, 10.5]) {
        add({ name: 'rail', from: [x, 3.5, 1], to: [x + 1, 4, 13.5], den: 2, skip: ['down'],
            faces: { all: S('steel'), up: S('steel', ['hline', 1, C.steel.hi, 0, -1]) } });
    }

    // ---- 移动工作台 + 下模 (待机拉出到前方, 工作推入滑块下)
    const z0 = active ? 5.5 : 0.5;
    add({ name: 'bolster', from: [4, 4, z0], to: [12, 5, z0 + 6.5], den: 2, skip: ['down', 'up'],
        faces: { all: S('gun', ['hline', 1, C.gun.hi]) } });
    add({ name: 'bolster_handle', from: [6, 4, z0 - 0.5], to: [10, 4.5, z0], den: 2, skip: ['down', 'south'],
        faces: { all: S('brass') } });
    // 下模: 顶面刻手枪型腔 (z 1..4), 后半放检具夹着一把刚冲好的手枪
    add({ name: 'lower_die', from: [4, 5, z0], to: [12, 6, z0 + 6.5], den: 2, skip: ['down'],
        faces: {
            all: active ? hotSide : S('steel', ['hline', 0, C.steel.hi, 0, -1]),
            up: { base: 'none', deco: [['dieTop', 1, 1]] },
        },
        emitFaces: active ? { north: 12, south: 12, east: 12, west: 12 } : {}, noShade: active });

    // ---- 待机: 刚冲好的手枪立在下模后半的检具上 (枪口朝西, 侧面正对操作员)
    //      亮钢剪影背后是深色上模和滑块, 正面/玩家视角/物品栏都能一眼认出
    if (!active) addPistol(add, forItem ? PISTOL_L : PISTOL_S);

    // ---- 上模 / 滑块 / 活塞杆
    const yU = active ? 6.5 : 7.5;
    const yR = yU + 1;
    if (active) {
        add({ name: 'blank_hot', from: [4, 6, 5.5], to: [12, 6.5, 12.5], den: 2, skip: ['down'],
            faces: { all: { base: 'none', deco: [['hotPlate']] } }, emit: 15, noShade: true });
    }
    add({ name: 'upper_die', from: [4.5, yU, 6], to: [11.5, yU + 1, 12], den: 2, skip: ['up'],
        faces: {
            all: active ? S('steel', ['hline', -1, C.hot0, 0, -1], ['hline', -2, C.hot1, 0, -1], ['hline', -3, C.hot2, 0, -1]) : S('steel'),
            down: S('steel', ['bolts', C.steel.lo]),
        } });
    add({ name: 'ram', from: [1.5, yR, ZF], to: [14.5, yR + 2, 14.5], den: 2,
        faces: {
            all: S('gunL', ['seamV', 6, 'gunL'], ['seamV', -8, 'gunL']),
            // 正面是白色护板, 警示条只留两端 (中段是待机时深色手枪的背景)
            north: S('panel', ['warn', 1, 1, 5, 2, 4], ['warn', -6, 1, 5, 2, 4]),
            up: S('gun'),
            down: S('dark'),
        } });
    // 滑块前沿灯条 + 两侧状态灯条 (单独元素, 只让灯条本身发光)
    const lightFace = { all: { base: active ? C.cyan : C.cyanDim, flat: true } };
    const lightEmit = active ? 15 : 0;
    add({ name: 'ram_light', from: [4.5, yR + 1, ZF - 0.5], to: [11.5, yR + 1.5, ZF], den: 2, skip: ['south'],
        faces: lightFace, emit: lightEmit, noShade: active });
    add({ name: 'ram_light_w', from: [1, yR + 1, 6.5], to: [1.5, yR + 1.5, 13], den: 2, skip: ['east'],
        faces: lightFace, emit: lightEmit, noShade: active });
    add({ name: 'ram_light_e', from: [14.5, yR + 1, 6.5], to: [15, yR + 1.5, 13], den: 2, skip: ['west'],
        faces: lightFace, emit: lightEmit, noShade: active });
    if (active) {
        add({ name: 'rod', from: [7, yR + 2, 9], to: [9, Y_COL_TOP - 0.5, 11], den: 2, skip: ['up', 'down'],
            faces: { all: { base: 'none', deco: [['cyl', 'chrome']] } } });
    }
    add({ name: 'rod_gland', from: [6.5, Y_COL_TOP - 0.5, 8.5], to: [9.5, Y_COL_TOP, 11.5], den: 2, skip: ['up'],
        faces: { all: S('brass') } });

    // ---- 四立柱 + 警示套环
    // 前立柱缩进滑块前沿 0.5 (避免与滑块北面共面); 前套环南沿止于 z 8, 给西侧托盘让位
    for (const x of [2.5, 12]) for (const z of [ZF + 0.5, 12.5]) {
        const cx0 = x < 8 ? x - 0.5 : x, cx1 = x < 8 ? x + 1.5 : x + 2;
        const front = z < 10;
        add({ name: 'column', from: [x, 5, z], to: [x + 1.5, Y_COL_TOP, z + 1.5], den: 2, skip: ['up', 'down'],
            faces: { all: { base: 'none', deco: [['cyl', 'chrome']] } } });
        add({ name: 'collar', from: [cx0, 3.5, z - 0.5], to: [cx1, 5, front ? z + 1.5 : z + 2], den: 2, skip: ['down'],
            faces: { all: front ? { base: 'none', deco: [['warn', 0, 0, null, null, 4]] } : S('gun', ['hline', 1, C.warnY, 0, -1]), up: S('gun') } });
    }

    // ---- 顶部横梁 (各面统一 1x)
    add({ name: 'crown', from: [1, Y_COL_TOP, ZF], to: [15, Y_CROWN_TOP, 15], den: 1,
        faces: {
            all: S('panel'),
            up: S('panel', ['vents', 1, 2, 2, 7, 'panelB'], ['vents', -3, 2, -2, 7, 'panelB'], ['bolts', C.panel.lo]),
            down: S('gun'),
        } });
    add({ name: 'crown_light', from: [2, Y_COL_TOP + 0.5, ZF - 0.5], to: [13.5, Y_COL_TOP + 1, ZF], den: 2, skip: ['south'],
        faces: { all: { base: active ? C.cyan : C.cyanDim, flat: true } },
        emit: active ? 15 : 0, noShade: active });

    // ---- 液压缸 (横梁上, 3px 高): 黄铜法兰 + 十字拼成的八角缸体 + 缸盖 (青色光环)
    const yc0 = Y_CROWN_TOP; // 13
    add({ name: 'cyl_flange', from: [4.5, yc0, 6.5], to: [11.5, yc0 + 0.5, 13.5], den: 2, skip: ['down'],
        faces: { all: S('brass'), up: S('brass', ['bolts', C.brass.lo, 1]) } });
    // 缸体: 中心块 A (x 5..11, z 8..12) + 前后凸块 B1/B2 (x 6..10); 三块互不重叠, 外轮廓为八角形
    const band = ['hline', 1, C.brass.base, 0, -1];
    const bandHi = ['hline', 2, C.brass.lo, 0, -1];
    const faceMid = { base: 'none', deco: [['cyl', 'gunCyl'], band, bandHi] };
    const faceEdge = { base: 'none', deco: [['cyl', 'gunEdge'], band, bandHi] };
    const yb0 = yc0 + 0.5, yb1 = yc0 + 2.5; // 13.5..15.5
    add({ name: 'cyl_body', from: [5, yb0, 8], to: [11, yb1, 12], den: 2, skip: ['down'],
        faces: { all: faceEdge, east: faceMid, west: faceMid, up: S('gun') } });
    add({ name: 'cyl_body_n', from: [6, yb0, 7], to: [10, yb1, 8], den: 2, skip: ['down', 'south'],
        faces: { all: faceEdge, north: faceMid, up: S('gun') } });
    add({ name: 'cyl_body_s', from: [6, yb0, 12], to: [10, yb1, 13], den: 2, skip: ['down', 'north'],
        faces: { all: faceEdge, south: faceMid, up: S('gun') } });
    add({ name: 'cyl_cap', from: [5.5, yb1, 7.5], to: [10.5, yb1 + 0.5, 12.5], den: 2, skip: ['down'],
        faces: { all: S('gun'), up: { base: 'none', deco: [['halo', active]] } },
        emitFaces: active ? { up: 15 } : {} });

    // ---- 液压站 (背面) + 黄铜油管
    add({ name: 'power_pack', from: [2.5, 3.5, 14.5], to: [13.5, 10, 15.5], den: 1, skip: ['down'],
        faces: {
            all: S('gun'),
            south: S('gun', ['vents', 2, 2, -3, 4, 'dark'], ['bolts', C.gun.hi]),
            up: S('gun', ['hline', 0, C.gun.hi, 0, -1]),
        } });
    add({ name: 'pack_led', from: [3.5, 4.5, 15.5], to: [5.5, 5, 16], den: 2, skip: ['north'],
        faces: { all: { base: active ? C.cyan : C.cyanMid, flat: true } }, emit: active ? 15 : 8, noShade: true });
    for (const x of [5.5, 10]) {
        // 竖管贴着横梁后面爬上来, 横管直接躺在横梁顶面上, 插进法兰侧面
        add({ name: 'pipe_v', from: [x, 10, 15], to: [x + 0.5, yc0, 15.5], den: 2, skip: ['down', 'north', 'up'],
            faces: { all: S('brass') } });
        add({ name: 'pipe_h', from: [x, yc0, 13.5], to: [x + 0.5, yc0 + 0.5, 15.5], den: 2, skip: ['down', 'north'],
            faces: { all: S('brass') } });
    }

    // ---- 西侧出料托盘 (不旋转, 贴在床身西侧两立柱之间) + 冲好的零件毛坯
    add({ name: 'tray_floor', from: [1, 3.5, 8], to: [4, 4, 12], den: 2, skip: ['down', 'west'],
        faces: { all: S('gun'), up: S('dark', ['vline', 2, C.dark.hi, 0, -1], ['vline', -3, C.dark.hi, 0, -1]) } });
    for (const z of [8, 11.5]) {
        add({ name: 'tray_lip', from: [0, 4, z], to: [4, 5, z + 0.5], den: 2, skip: ['down'],
            faces: { all: S('gun'), west: { base: 'none', deco: [['warn', 0, 0, null, null, 2]] } } });
    }
    add({ name: 'tray_lip_w', from: [0, 4, 8.5], to: [1, 4.5, 11.5], den: 2, skip: ['down', 'north', 'south'],
        faces: { all: S('gun'), west: S('gun', ['hline', 0, C.warnY, 0, -1]) } });
    add({ name: 'blank_slide', from: [2.5, 4, 8.5], to: [4, 4.5, 11.5], den: 2, skip: ['down'],
        faces: active ? { all: hotSide, up: { base: 'none', deco: [['hotPlate']] } } : { all: S('steelHi'), up: { base: 'none', deco: [['slideBlank']] } },
        emit: active ? 12 : 0, noShade: active });
    add({ name: 'blank_mag', from: [1, 4, 8.5], to: [2, 4.5, 11], den: 2, skip: ['down'],
        faces: { all: S('steelHi'), up: { base: 'none', deco: [['magBlank']] } } });

    // ---- 西前角: 双手启动柱 (黄黑柱身 + 绿色按钮)
    add({ name: 'start_post', from: [0.5, 0.5, 0.5], to: [2.5, 5, 2.5], den: 2, skip: ['down', 'up'],
        faces: { all: S('gun', ['warn', 0, 0, null, 3, 4], ['vline', 1, C.gun.hi, 3, -2]) } });
    add({ name: 'start_head', from: [0.5, 5, 0.5], to: [2.5, 6, 2.5], den: 2, skip: ['down'],
        faces: { all: S('gun'), up: S('gun') } });
    add({ name: 'start_btn_w', from: [1, 6, 1], to: [2, 6.5, 2], den: 2, skip: ['down'],
        faces: { all: { base: 'none', deco: [['knob', 'green']] } }, emit: active ? 10 : 0 });

    // ---- 东前角: 吊挂控制盒 (青色屏幕 + 绿色启动 + 红色急停)
    add({ name: 'pendant_arm', from: [13.5, Y_COL_TOP, 1.5], to: [14.5, Y_COL_TOP + 1, ZF], den: 2, skip: ['down', 'south'],
        faces: { all: S('gun') } });
    add({ name: 'pendant', from: [12.5, 7, 0.5], to: [15.5, 11, 2.5], den: 2,
        faces: {
            all: S('panel'),
            north: S('panel', ['rect', 1, 5, 4, 3, C.warnY]),
            down: S('gun'),
        } });
    add({ name: 'pendant_screen', from: [12.5, 8.5, 0], to: [15.5, 11, 0.5], den: 2, skip: ['south', 'down'],
        faces: { all: S('gun'), north: { base: 'none', deco: [['screen', active]] } },
        emitFaces: { north: active ? 15 : 7 } });
    add({ name: 'estop', from: [13, 7, 0], to: [14.5, 8.5, 0.5], den: 2, skip: ['south', 'up'],
        faces: { all: { base: 'none', deco: [['knob', 'red']] } } });
    add({ name: 'start_btn_e', from: [14.5, 7, 0], to: [15.5, 8, 0.5], den: 2, skip: ['south'],
        faces: { all: { base: 'none', deco: [['knob', 'green']] } }, emit: active ? 10 : 0 });

    // ---- 三色信号灯 (横梁东前角)
    add({ name: 'beacon_base', from: [12.5, yc0, ZF], to: [13.5, yc0 + 0.5, ZF + 1], den: 2, skip: ['down'], faces: { all: S('gun') } });
    const lamps = [
        ['green', active ? C.green : C.greenOff, active ? 15 : 0],
        ['amber', C.amberOff, 0],
        ['red', C.redOff, 0],
    ];
    lamps.forEach(([n, col, em], i) => {
        const y = yc0 + 0.5 + i * 0.5;
        add({ name: 'beacon_' + n, from: [12.5, y, ZF], to: [13.5, y + 0.5, ZF + 1], den: 2, skip: ['down'].concat(i < 2 ? ['up'] : []),
            faces: { all: { base: col, flat: true, deco: [['pix', [[0, 0, mix(col, [255, 255, 255], 0.35)]]]] } },
            emit: em, noShade: em > 0 });
    });

    return E;
}

// ---------------------------------------------------------------- 图集打包
function specKey(spec, w, h) { return JSON.stringify([spec.base, spec.flat || false, spec.deco || [], w, h]); }

function collectFaces(elementsByModel) {
    const faces = [];
    for (const [model, elements] of Object.entries(elementsByModel)) {
        for (const e of elements) {
            for (const face of FACES) {
                if ((e.skip || []).includes(face)) continue;
                const spec = (e.faces[face] || e.faces.all);
                if (!spec) throw new Error(`${model}/${e.name}: face ${face} 无画法`);
                const den = spec.den || e.den || 1;
                const [W, H] = faceDims(face, e.from, e.to);
                const w = Math.ceil(W * den - 1e-6), h = Math.ceil(H * den - 1e-6);
                faces.push({ model, e, face, spec, den, W, H, w, h, key: specKey(spec, w, h) });
            }
        }
    }
    return faces;
}

function pack(regions, size) {
    const sorted = [...regions].sort((a, b) => b.h - a.h || b.w - a.w);
    let x = 0, y = 0, shelfH = 0;
    for (const r of sorted) {
        if (r.w > size) return false;
        if (x + r.w > size) { x = 0; y += shelfH; shelfH = 0; }
        if (y + r.h > size) return false;
        r.x = x; r.y = y; x += r.w; shelfH = Math.max(shelfH, r.h);
    }
    return true;
}

// ---------------------------------------------------------------- 输出
function fmtNum(n) { return Number.isInteger(n) ? String(n) : String(+n.toFixed(4)); }

function modelJson(elements, placed, size, faceList) {
    const out = [];
    for (const e of elements) {
        const faces = {};
        for (const f of faceList.filter((f) => f.e === e)) {
            const r = placed.get(f.key);
            const k = 16 / size;
            const uv = [r.x * k, r.y * k, (r.x + f.W * f.den) * k, (r.y + f.H * f.den) * k].map((v) => +v.toFixed(4));
            const fj = { uv, texture: '#atlas' };
            if (f.spec.rotation) fj.rotation = f.spec.rotation;
            const em = (e.emitFaces && e.emitFaces[f.face] != null) ? e.emitFaces[f.face] : (e.emit || 0);
            if (em > 0) fj.forge_data = { block_light: em, sky_light: em };
            faces[f.face] = fj;
        }
        const ej = { name: e.name, from: e.from, to: e.to };
        if (e.rot) ej.rotation = { angle: e.rot.angle, axis: e.rot.axis, origin: e.rot.origin };
        if (e.noShade) ej.shade = false;
        ej.faces = faces;
        out.push(ej);
    }
    return out;
}

function stringifyModel(model) {
    const lines = ['{'];
    const keys = Object.keys(model);
    keys.forEach((k, i) => {
        const last = i === keys.length - 1;
        if (k === 'elements') {
            lines.push('  "elements": [');
            model.elements.forEach((el, j) => {
                lines.push('    ' + JSON.stringify(el) + (j < model.elements.length - 1 ? ',' : ''));
            });
            lines.push('  ]' + (last ? '' : ','));
        } else {
            lines.push(`  ${JSON.stringify(k)}: ${JSON.stringify(model[k], null, 2).replace(/\n/g, '\n  ')}` + (last ? '' : ','));
        }
    });
    lines.push('}');
    return lines.join('\n') + '\n';
}

// ---------------------------------------------------------------- 校验
function validate(models, size, regions, img) {
    const errors = [];
    const warns = [];
    const LEGAL = [-45, -22.5, 0, 22.5, 45];
    const onGrid = (v) => Math.abs(v * 2 - Math.round(v * 2)) < 1e-9;
    for (const [name, m] of Object.entries(models)) {
        if (!m.elements) continue; // 纯 parent 的物品模型
        if (!m.textures || !m.textures.particle) errors.push(`${name}: 缺 particle`);
        if (m.elements.length > 90) errors.push(`${name}: 元素数 ${m.elements.length} > 90`);
        for (const el of m.elements) {
            const id = `${name}/${el.name}`;
            for (let a = 0; a < 3; a++) {
                const lo = el.from[a], hi = el.to[a];
                if (lo < 0 || hi > 16) errors.push(`${id}: 坐标越出 [0,16] 轴 ${a}`);
                if (hi - lo < 0.5 - 1e-9) errors.push(`${id}: 厚度 < 0.5 轴 ${a}`);
                if (!onGrid(lo) || !onGrid(hi)) warns.push(`${id}: 坐标不在 0.5 网格`);
            }
            if ('shade' in el && typeof el.shade !== 'boolean') errors.push(`${id}: shade 必须是布尔值`);
            if (el.rotation) {
                if (!LEGAL.includes(el.rotation.angle)) errors.push(`${id}: 旋转角 ${el.rotation.angle} 非法`);
                if (!['x', 'y', 'z'].includes(el.rotation.axis)) errors.push(`${id}: 旋转轴非法`);
                if (el.rotation.rescale) warns.push(`${id}: rescale=true`);
                for (const p of corners(el.from, el.to)) {
                    const q = rot(p, el.rotation);
                    if (q.some((v) => v < -1e-6 || v > 16 + 1e-6)) { errors.push(`${id}: 旋转后顶点越出本格 ${q.map(fmtNum)}`); break; }
                }
            }
            if (!Object.keys(el.faces).length) errors.push(`${id}: 没有任何面`);
            for (const [face, f] of Object.entries(el.faces)) {
                if (!f.uv || f.uv.length !== 4) errors.push(`${id}.${face}: 缺 uv`);
                else if (f.uv.some((v) => v < 0 || v > 16)) errors.push(`${id}.${face}: uv 越界`);
                if (f.cullface) errors.push(`${id}.${face}: 不应写 cullface`);
                const key = f.texture.replace(/^#/, '');
                if (!m.textures[key]) errors.push(`${id}.${face}: 材质 #${key} 无法解析`);
                if (f.rotation && ![0, 90, 180, 270].includes(f.rotation)) errors.push(`${id}.${face}: 面旋转非法`);
                if (f.forge_data) {
                    const { block_light: bl, sky_light: sl } = f.forge_data;
                    if (!(bl >= 0 && bl <= 15 && sl >= 0 && sl <= 15)) errors.push(`${id}.${face}: forge_data 光照越界`);
                }
            }
        }
        // 同向共面重叠 (z-fighting) 检查: 只比较旋转相同的元素
        const planes = [];
        for (const el of m.elements) {
            const rk = el.rotation ? JSON.stringify(el.rotation) : '';
            for (const face of Object.keys(el.faces)) planes.push({ el, face, rk, ...facePlane(face, el.from, el.to) });
        }
        for (let i = 0; i < planes.length; i++) for (let j = i + 1; j < planes.length; j++) {
            const a = planes[i], b = planes[j];
            if (a.face !== b.face || a.rk !== b.rk || Math.abs(a.d - b.d) > 1e-9) continue;
            const ox = Math.min(a.r[2], b.r[2]) - Math.max(a.r[0], b.r[0]);
            const oy = Math.min(a.r[3], b.r[3]) - Math.max(a.r[1], b.r[1]);
            if (ox > 1e-9 && oy > 1e-9) errors.push(`${name}: 共面重叠 ${a.el.name}.${a.face} ↔ ${b.el.name}.${b.face}`);
        }
        // 旋转元素 vs 不旋转元素: 水平面 (up/down) 在同一高度且投影重叠也会 z-fighting (y 轴旋转不改变高度)
        for (const a of planes) for (const b of planes) {
            if (a === b || !a.rk || b.rk || a.face !== b.face || !['up', 'down'].includes(a.face) || Math.abs(a.d - b.d) > 1e-9) continue;
            const ra = JSON.parse(a.rk);
            if (ra.axis !== 'y') continue;
            const pts = corners(a.el.from, a.el.to).map((p) => rot(p, ra));
            const xs = pts.map((p) => p[0]), zs = pts.map((p) => p[2]);
            // 粗检: 旋转后包围盒与 b 相交, 再取样确认
            if (Math.min(...xs) >= b.r[2] || Math.max(...xs) <= b.r[0] || Math.min(...zs) >= b.r[3] || Math.max(...zs) <= b.r[1]) continue;
            let hit = false;
            for (let sx = 0.05; sx < 1 && !hit; sx += 0.1) for (let sz = 0.05; sz < 1 && !hit; sz += 0.1) {
                const lp = [a.el.from[0] + (a.el.to[0] - a.el.from[0]) * sx, a.d, a.el.from[2] + (a.el.to[2] - a.el.from[2]) * sz];
                const q = rot(lp, ra);
                if (q[0] > b.r[0] && q[0] < b.r[2] && q[2] > b.r[1] && q[2] < b.r[3]) hit = true;
            }
            if (hit) errors.push(`${name}: 旋转元素共面重叠 ${a.el.name}.${a.face} ↔ ${b.el.name}.${b.face}`);
        }
    }
    const list = [...regions.values()];
    for (const r of list) if (r.x < 0 || r.y < 0 || r.x + r.w > size || r.y + r.h > size) errors.push(`图集区域越界 ${r.key}`);
    for (let i = 0; i < list.length; i++) for (let j = i + 1; j < list.length; j++) {
        const a = list[i], b = list[j];
        if (a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h) errors.push(`图集区域重叠 ${i} ${j}`);
    }
    if (![64, 128, 256].includes(size)) errors.push('图集边长非 64/128/256');
    for (let i = 3; i < img.data.length; i += 4) if (img.data[i] !== 255) { errors.push('图集存在非不透明像素'); break; }
    return { errors, warns };
}

function corners(f, t) {
    const out = [];
    for (const x of [f[0], t[0]]) for (const y of [f[1], t[1]]) for (const z of [f[2], t[2]]) out.push([x, y, z]);
    return out;
}
function rot(p, r) {
    const a = r.angle * Math.PI / 180, c = Math.cos(a), s = Math.sin(a);
    const [x, y, z] = [p[0] - r.origin[0], p[1] - r.origin[1], p[2] - r.origin[2]];
    let o;
    if (r.axis === 'x') o = [x, y * c - z * s, y * s + z * c];
    else if (r.axis === 'y') o = [x * c + z * s, y, -x * s + z * c];
    else o = [x * c - y * s, x * s + y * c, z];
    return [o[0] + r.origin[0], o[1] + r.origin[1], o[2] + r.origin[2]];
}
function facePlane(face, f, t) {
    switch (face) {
        case 'north': return { d: f[2], r: [f[0], f[1], t[0], t[1]] };
        case 'south': return { d: t[2], r: [f[0], f[1], t[0], t[1]] };
        case 'west': return { d: f[0], r: [f[2], f[1], t[2], t[1]] };
        case 'east': return { d: t[0], r: [f[2], f[1], t[2], t[1]] };
        case 'down': return { d: f[1], r: [f[0], f[2], t[0], t[2]] };
        case 'up': return { d: t[1], r: [f[0], f[2], t[0], t[2]] };
    }
}

// ---------------------------------------------------------------- 物品模型
// 物品模型 = 待机方块模型 + 放大的手枪 (PISTOL_L), 其余 display 继承 minecraft:block/block。
// 物品栏 gui: 比原版 [30,225,0] 更低、更正对正面, 从西北方向看 (不让东侧吊挂控制盒挡住手枪握把), 放大到 0.75。
const ITEM_GUI = { rotation: [20, 160, 0], translation: [0, 0, 0], scale: [0.75, 0.75, 0.75] };

// ---------------------------------------------------------------- 主流程
function main() {
    const argv = process.argv.slice(2);
    const oi = argv.indexOf('--out');
    if (oi < 0 || !argv[oi + 1]) { console.error('usage: node generate.mjs --out <根目录>'); process.exit(2); }
    const root = path.resolve(argv[oi + 1]);

    const elementsByModel = { gunsmith_press: buildElements(false), gunsmith_press_active: buildElements(true), 'item:gunsmith_press': buildElements(false, true) };
    const faces = collectFaces(elementsByModel);
    const regions = new Map();
    for (const f of faces) if (!regions.has(f.key)) regions.set(f.key, { key: f.key, w: f.w, h: f.h, spec: f.spec });
    let size = 0;
    for (const s of [64, 128, 256]) { if (pack([...regions.values()], s)) { size = s; break; } }
    if (!size) throw new Error('图集 256 放不下');
    const img = { width: size, height: size, data: new Uint8ClampedArray(size * size * 4) };
    for (let i = 0; i < img.data.length; i += 4) { img.data[i] = 40; img.data[i + 1] = 44; img.data[i + 2] = 52; img.data[i + 3] = 255; }
    for (const r of regions.values()) paintFace(mkCtx(img, r.x, r.y, r.w, r.h), r.spec);

    const models = {};
    for (const [name, els] of Object.entries(elementsByModel)) {
        models[name] = {
            parent: 'minecraft:block/block',
            ambientocclusion: false,
            textures: { atlas: TEX_ID, particle: TEX_ID },
            elements: modelJson(els, regions, size, faces.filter((f) => f.model === name)),
        };
        if (name.startsWith('item:')) models[name].display = { gui: ITEM_GUI };
    }
    const { errors, warns } = validate(models, size, regions, img);
    for (const w of warns) console.warn('WARN', w);
    const counts = Object.entries(models).map(([n, m]) => `${n}=${m.elements.length}`).join(' ');
    console.log(`atlas ${size}x${size}, regions ${regions.size}, elements ${counts}`);
    // 校验不过就不写任何文件 (避免 --out 指向仓库时把坏文件覆盖进 src)
    if (errors.length) {
        for (const e of errors) console.error('ERROR', e);
        console.error('validation FAILED, 未写任何文件');
        process.exit(1);
    }

    const assets = path.join(root, 'src', 'main', 'resources', 'assets', 'miningdim');
    fs.mkdirSync(path.join(assets, 'models', 'block'), { recursive: true });
    fs.mkdirSync(path.join(assets, 'models', 'item'), { recursive: true });
    fs.mkdirSync(path.join(assets, 'textures', 'block'), { recursive: true });
    for (const [name, m] of Object.entries(models)) {
        const [kind, file] = name.startsWith('item:') ? ['item', name.slice(5)] : ['block', name];
        fs.writeFileSync(path.join(assets, 'models', kind, file + '.json'), stringifyModel(m));
    }
    fs.writeFileSync(path.join(assets, 'textures', 'block', 'gunsmith_press_atlas.png'), encodePng(img));
    console.log('validation OK ->', root);
}

main();
