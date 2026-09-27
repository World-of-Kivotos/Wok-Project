// 军火台 (munitions_bench, WIDE 布局) 生成核心 (零依赖, Node 18+)。
// 由方案评选时的候选脚手架 (.candidates/munitions_common/core.mjs) 移植, 去掉候选专用的 out/ 与 manifest, 改成直接写进仓库。
// 场景函数 (generate_munitions_bench.mjs 的 scene) 在世界坐标里建整台:
//   朝北放置: x 向东, y 向上, z 向南, 正面 z = 0; 两格宽 x 0..32, 一格深 z 0..16, 高 y 0..32。
//   main = x 0..16 (站在正面的玩家右手边), extension = x 16..32 (左手边)。
// 本文件提供: 材质与档位调色板、画布与贴花、面描述、场景运行、被挡面剔除与共面检查、图集排布与绘制、按 x = 16 切两格、
// 模型校验、物品模型、把一个面描述画成像素 (BER 运动件贴图用, 见 ber.mjs)。写盘与 Java 在 generate_munitions_bench.mjs。
import { TIERS, ROLE_GROUPS, tierIndex, addOns as tierAddOns, atLeast as tierAtLeast } from './tiers.mjs';
import { encodePng as encodePngImage } from '../gunsmith_workstation/png.mjs';

export { TIERS };

// ================================================================ 颜色与材质
/** '#rrggbb' → [r, g, b] */
export const C = (s) => [parseInt(s.slice(1, 3), 16), parseInt(s.slice(3, 5), 16), parseInt(s.slice(5, 7), 16)];
/** 线性混色 (返回新数组, 不带角色标记)。 */
export const mix = (a, b, t) => [0, 1, 2].map((i) => Math.round(a[i] + (b[i] - a[i]) * t));
/** 材质: {name, base, hi, lo, dk}。 */
export const mat = (name, base, hi, lo, dk) => ({ name, base: C(base), hi: C(hi), lo: C(lo), dk: C(dk) });

/** 家族中性材质 (每档相同)。档位相关的颜色不在这里, 用 P.trim / P.band / P.light / P.glow。 */
export const M = {
    frame: mat('frame', '#5a6373', '#8c96a6', '#3a404c', '#2c323a'),     // 枪灰机架 (冲压机同色)
    frameL: mat('frameL', '#7e8796', '#b0b8c6', '#56606d', '#3a404c'),   // 朝东/西的枪灰面 (游戏里 ×0.6, 底色提一档)
    panel: mat('panel', '#dadfe5', '#f6f8fa', '#9ea6b1', '#7a838f'),     // 浅色钢板机身
    panelB: mat('panelB', '#c4cad2', '#e6eaef', '#8c94a0', '#6a727e'),   // 浅色机身的顶面
    steel: mat('steel', '#b0b9c3', '#d6dde4', '#767f8a', '#5a626c'),
    chrome: mat('chrome', '#c9d1da', '#eef2f6', '#8e98a3', '#5f6873'),
    dark: mat('dark', '#2e333c', '#484e5a', '#1e2128', '#16181d'),
    brass: mat('brass', '#cea23c', '#f6d676', '#8c6822', '#6e4f15'),     // 弹壳 / 黄铜件
    copper: mat('copper', '#c46f3a', '#e8955c', '#8f4a22', '#5e2e14'),
    lead: mat('lead', '#8a8f98', '#b8bdc5', '#62666e', '#45484e'),
    powder: mat('powder', '#4a4f3a', '#6c7256', '#34382a', '#23261c'),
    olive: mat('olive', '#65733f', '#839357', '#4a552c', '#343c1f'),     // 弹药箱
    rubber: mat('rubber', '#2b2e33', '#3f434a', '#202327', '#15171a'),
    orange: mat('orange', '#e8862c', '#ffb257', '#b8611b', '#7e3f10'),
    yellow: mat('yellow', '#f4c22c', '#ffe07a', '#c2931c', '#8a6510'),
    hot: mat('hot', '#ffb238', '#fff4b0', '#ee661a', '#a02e0e'),         // 工作时的炽热件 (hi→dk = 白热→暗红)
    red: mat('red', '#de3432', '#ff8076', '#8c161a', '#5a0e10'),         // 急停
    green: mat('green', '#40ce60', '#aaffb2', '#1c7634', '#0e3e1b'),     // 运行灯
    cyan: mat('cyan', '#24c8d6', '#60f4f0', '#2c7682', '#163a44'),       // 家族青 (不随档位变; 档位灯用 P.light)
    glass: mat('glass', '#3d5a66', '#7fa6b3', '#2a3f48', '#1b2a30'),
};
export const BOTTOM = C('#2a2f36');          // 默认底面色 + 图集底色
export const HAZ_Y = C('#f4c22c'), HAZ_K = C('#26262c');
export const LABEL = C('#ebe6d6'), INK = C('#22262c'), PLATE_BG = C('#14161c');
export const OFF = { green: C('#34603f'), amber: C('#8c6828'), red: C('#6e2c2c') };

function tagRole(col, role) {
    const a = [col[0], col[1], col[2]];
    Object.defineProperty(a, 'role', { value: role, enumerable: false });
    return a;
}

/**
 * 某一档的完整调色板: M 的全部中性材质 + 档位角色 (颜色数组带不可枚举的 .role 标记, flat() 据此按角色登记色块)。
 *   P.trim {name, base, hi, lo, dk}   P.band {同}   P.light {on, hi, mid, dim, dk, off, base(=mid), lo(=dim)}   P.glow {on, soft, base(=on)}
 *   P.tier = TIERS[i], P.tierIndex = i
 */
export function palette(tier) {
    const i = tierIndex(tier);
    const T = TIERS[i];
    const P = { ...M, tier: T, tierIndex: i };
    for (const [g, list] of Object.entries(ROLE_GROUPS)) {
        P[g] = { name: g };
        for (const s of list) P[g][s] = tagRole(T.roles[g][s], `${g}.${s}`);
    }
    P.light.base = P.light.mid; P.light.lo = P.light.dim;
    P.glow.base = P.glow.on; P.glow.hi = P.light.hi; P.glow.lo = P.glow.soft; P.glow.dk = P.light.dk;
    return P;
}
function resolveRole(P, role) {
    const [g, s] = role.split('.');
    const v = P[g] && P[g][s || 'base'];
    if (!v) throw new Error('unknown colour role ' + role);
    return v;
}

// ================================================================ 画布与贴花
// 一个面在图集里是一块矩形, 画笔在局部坐标 (0..w-1, 0..h-1) 里作画 (超出的像素自动裁掉)。
// 贴图方向: 侧面 上 = 世界上, 左 = 从外面看的左边; 顶面 上 = 北 (正面一侧), 左 = 西; 底面 上 = 南, 左 = 西。
// 注意北面 (正面) 的"左"是东 (extension 一侧), 南面 (背面) 的"左"是西。
const FONT3x5 = {
    0: [7, 5, 5, 5, 7], 1: [2, 6, 2, 2, 7], 2: [7, 1, 7, 4, 7], 3: [7, 1, 3, 1, 7], 4: [5, 5, 7, 1, 1],
    5: [7, 4, 7, 1, 7], 6: [7, 4, 7, 5, 7], 7: [7, 1, 1, 2, 2], 8: [7, 5, 7, 5, 7], 9: [7, 5, 7, 1, 7],
    A: [2, 5, 7, 5, 5], B: [6, 5, 6, 5, 6], C: [3, 4, 4, 4, 3], D: [6, 5, 5, 5, 6], E: [7, 4, 6, 4, 7], F: [7, 4, 6, 4, 4],
    G: [3, 4, 5, 5, 3], H: [5, 5, 7, 5, 5], I: [7, 2, 2, 2, 7], J: [1, 1, 1, 5, 2], K: [5, 5, 6, 5, 5], L: [4, 4, 4, 4, 7],
    M: [5, 7, 7, 5, 5], N: [6, 5, 5, 5, 5], O: [2, 5, 5, 5, 2], P: [6, 5, 6, 4, 4], Q: [2, 5, 5, 6, 3], R: [6, 5, 6, 5, 5],
    S: [3, 4, 2, 1, 6], T: [7, 2, 2, 2, 2], U: [5, 5, 5, 5, 7], V: [5, 5, 5, 5, 2], W: [5, 5, 7, 7, 5], X: [5, 5, 2, 5, 5],
    Y: [5, 5, 2, 2, 2], Z: [7, 1, 2, 4, 7],
    '.': [0, 0, 0, 0, 2], '-': [0, 0, 7, 0, 0], ' ': [0, 0, 0, 0, 0], '/': [1, 1, 2, 4, 4], ':': [0, 2, 0, 2, 0],
    '+': [0, 2, 7, 2, 0], x: [0, 5, 2, 5, 0], '#': [5, 7, 5, 7, 5], '>': [4, 2, 1, 2, 4], '<': [1, 2, 4, 2, 1],
};

export class Canvas {
    constructor(w, h) { this.w = w; this.h = h; this.data = new Uint8ClampedArray(w * h * 4); }
    px(x, y, c) {
        if (!c || x < 0 || y < 0 || x >= this.w || y >= this.h) return;
        x |= 0; y |= 0;
        const o = (y * this.w + x) * 4; this.data[o] = c[0]; this.data[o + 1] = c[1]; this.data[o + 2] = c[2]; this.data[o + 3] = 255;
    }
    get(x, y) { const o = (y * this.w + x) * 4; return [this.data[o], this.data[o + 1], this.data[o + 2]]; }
    rect(x, y, w, h, c) { for (let j = y; j < y + h; j++) for (let i = x; i < x + w; i++) this.px(i, j, c); }
    fill(c) { this.rect(0, 0, this.w, this.h, c); }
    hline(x, y, w, c) { this.rect(x, y, w, 1, c); }
    vline(x, y, h, c) { this.rect(x, y, 1, h, c); }
    /** 1 px 倒角: 上/左亮, 下/右暗。只有 1 像素宽/高的细面保持底色, 两端点缀。 */
    bevel(m, x = 0, y = 0, w = this.w, h = this.h) {
        if (w === 1 || h === 1) {
            if (w > 2 || h > 2) { this.px(x, y, m.hi); this.px(x + w - 1, y + h - 1, m.lo); }
            return this;
        }
        this.hline(x, y, w, m.hi); this.vline(x, y, h, m.hi);
        this.hline(x, y + h - 1, w, m.lo); this.vline(x + w - 1, y, h, m.lo);
        this.px(x + w - 1, y, m.base); this.px(x, y + h - 1, m.base);
        return this;
    }
    /** 整块板: 底色 + 倒角。 */
    plate(m) { this.fill(m.base); return this.bevel(m); }
    /** 凹陷面板: 上/左暗, 下/右亮。 */
    inset(m, x, y, w, h) {
        this.hline(x, y, w, m.lo); this.vline(x, y, h, m.lo);
        this.hline(x, y + h - 1, w, m.hi); this.vline(x + w - 1, y, h, m.hi);
        return this;
    }
    /** 2x2 螺栓。 */
    bolt(x, y, m) { this.px(x, y, m.hi); this.px(x + 1, y, m.base); this.px(x, y + 1, m.lo); this.px(x + 1, y + 1, m.dk); return this; }
    /** 四角 1 px 螺钉。 */
    bolts(col, inset = 1) {
        for (const [x, y] of [[inset, inset], [this.w - 1 - inset, inset], [inset, this.h - 1 - inset], [this.w - 1 - inset, this.h - 1 - inset]]) this.px(x, y, col);
        return this;
    }
    /** 黄黑警示斜纹。 */
    hazard(x = 0, y = 0, w = this.w, h = this.h, period = 4, phase = 0, a = HAZ_Y, b = HAZ_K) {
        for (let j = 0; j < h; j++) for (let i = 0; i < w; i++) this.px(x + i, y + j, ((((i + j + phase) % period) + period) % period) < period / 2 ? a : b);
        return this;
    }
    /** 百叶/散热缝: 每 step 行一道暗缝 + 下面一道亮唇。 */
    vent(x, y, w, h, m, step = 2) {
        for (let j = 0; j < h; j += step) { this.hline(x, y + j, w, m.dk); if (j + 1 < h) this.hline(x, y + j + 1, w, m.hi); }
        return this;
    }
    /** 3x5 点阵字 (大写字母、数字、少量符号), 返回宽度。 */
    text(x, y, str, col, gap = 1) {
        let cx = x;
        for (const ch of String(str).toUpperCase()) {
            const g = FONT3x5[ch] || FONT3x5[ch.toLowerCase()] || FONT3x5[' '];
            for (let r = 0; r < 5; r++) for (let c = 0; c < 3; c++) if (g[r] & (4 >> c)) this.px(cx + c, y + r, col);
            cx += 3 + gap;
        }
        return cx - x - gap;
    }
    /** 按色阶渐变填充 (dir 'v' 从上到下 / 'h' 从左到右)。 */
    gradient(x, y, w, h, ramp, dir = 'v') {
        for (let j = 0; j < h; j++) for (let i = 0; i < w; i++) {
            const t = dir === 'v' ? (h === 1 ? 0 : j / (h - 1)) : (w === 1 ? 0 : i / (w - 1));
            const f = t * (ramp.length - 1), k = Math.min(ramp.length - 2, Math.floor(f));
            this.px(x + i, y + j, ramp.length === 1 ? ramp[0] : mix(ramp[k], ramp[k + 1], f - k));
        }
        return this;
    }
    /** 圆柱感: 横向 (dir 'h', 竖直圆柱) 或纵向 ('v') 的亮暗过渡。m 取材质。 */
    cyl(m, dir = 'h', x = 0, y = 0, w = this.w, h = this.h) {
        const ramp = [m.lo, m.hi, m.base, m.base, m.lo, m.dk];
        const n = dir === 'h' ? w : h;
        for (let k = 0; k < n; k++) {
            const t = n === 1 ? 0.3 : k / (n - 1);
            const col = ramp[Math.min(ramp.length - 1, Math.floor(t * ramp.length))];
            if (dir === 'h') this.vline(x + k, y, h, col); else this.hline(x, y + k, w, col);
        }
        return this;
    }
    /**
     * 画一发子弹的侧视剪影 (贴花, 铭牌/标牌用)。dir: 弹头朝向 'right' | 'left' | 'up' | 'down'。
     * len × dia 的范围里: 弹壳 (黄铜, 带高光/暗边与底缘) + 收窄的弹头。opt: {case: M.brass, tip: M.copper}
     */
    round(x, y, len, dia, dir = 'right', opt = {}) {
        const cs = opt.case || M.brass, tp = opt.tip || M.copper;
        const caseLen = Math.max(1, Math.round(len * 0.62)), tipLen = len - caseLen;
        const put = (u, v, col) => {
            if (dir === 'right') this.px(x + u, y + v, col);
            else if (dir === 'left') this.px(x + len - 1 - u, y + v, col);
            else if (dir === 'up') this.px(x + v, y + len - 1 - u, col);
            else this.px(x + v, y + u, col);
        };
        for (let u = 0; u < len; u++) {
            for (let v = 0; v < dia; v++) {
                const edge = v === 0 ? 'hi' : v === dia - 1 ? 'lo' : 'base';
                if (u < caseLen) {
                    let col = cs[edge];
                    if (u === 0) col = cs.lo;
                    else if (u === 1 && len >= 6 && dia >= 3) col = cs.dk;   // 抽壳槽
                    put(u, v, col);
                } else {
                    const t = tipLen <= 1 ? 0 : (u - caseLen) / (tipLen - 1);
                    const half = (dia / 2) * (1 - 0.55 * t * t);
                    const d = Math.abs(v + 0.5 - dia / 2);
                    if (d <= half + 0.01) put(u, v, tp[edge]);
                }
            }
        }
        return this;
    }
}

// ================================================================ 面描述
/**
 * 纯色面 (图集里一块 4x4 色块, 可任意拉伸)。col:
 *   'trim.hi' / 'light.on' ...  档位角色 (六档各自取色, 布局相同)
 *   P.trim.hi 这样带角色标记的数组 (同上)
 *   '#rrggbb' 或 [r,g,b]         固定颜色 (每档相同)
 */
export function flat(col) {
    if (typeof col === 'string') {
        if (col.startsWith('#')) return { flat: true, col: C(col), role: null };
        return { flat: true, col: null, role: col.includes('.') ? col : col + '.base' };
    }
    if (!Array.isArray(col) || col.length < 3) throw new Error('flat(): bad colour ' + col);
    return { flat: true, col, role: col.role || null };
}
/**
 * 专属绘制区域: fn(canvas, P, info) 在这块区域里作画, P 是正在画的那一档的调色板 (一定要用参数 P, 不要闭包捕获场景里的 P)。
 * key 是区域名, 实际去重键 = key + 像素尺寸; 同一 key 同一尺寸的面共用一块 (校验会把同键不同画法的情况报错)。
 * opt: {m: 材质 (面太小画不下时退化成 m.base 纯色), d: 贴图密度 (贴图像素 / 模型像素, 默认 2)}
 */
export function paint(key, fn, opt = {}) {
    if (typeof key !== 'string' || !key) throw new Error('paint(): key required');
    if (typeof fn !== 'function') throw new Error('paint(): fn required for ' + key);
    return { key, fn, m: opt.m || M.frame, d: opt.d || 2 };
}
/** 自发光: 面写 forge_data {block_light: level, sky_light: level}。整个元素的面全部发光时自动 shade:false。 */
export const glow = (desc, level = 15) => ({ ...desc, emit: level });
/** 整块带倒角的板 (区域键按材质名)。 */
export const plate = (m, d = 2) => paint('plate:' + m.name, (c, P) => { const mm = m.name && P[m.name] && P[m.name].base ? P[m.name] : m; c.plate(mm); }, { m, d });

export const FACES = ['north', 'south', 'east', 'west', 'up', 'down'];
/** 六个面的描述: 没写的面取 all (down 默认是 BOTTOM 纯色), 写成 null 的面不要。 */
function faceSet(name, faces) {
    const f = {};
    for (const k of FACES) {
        if (faces[k] !== undefined) f[k] = faces[k];
        else if (k === 'down') f[k] = flat(BOTTOM);
        else if (faces.all !== undefined) f[k] = faces.all;
        else throw new Error(`element ${name}: face ${k} has no description (give it, or all, or null)`);
        if (f[k] === null) delete f[k];
        else if (!f[k] || (!f[k].flat && !f[k].fn)) throw new Error(`element ${name}: face ${k} is not a face description (use flat/paint/glow/plate)`);
    }
    return f;
}

export function faceDims(face, from, to) {
    const dx = to[0] - from[0], dy = to[1] - from[1], dz = to[2] - from[2];
    if (face === 'north' || face === 'south') return [dx, dy];
    if (face === 'east' || face === 'west') return [dz, dy];
    return [dx, dz];
}

// ================================================================ 小工具
export const r4 = (v) => Math.round(v * 10000) / 10000;
export const LEGAL_ANGLES = [-45, -22.5, 0, 22.5, 45];
export function rotPoint(rot, p, sign = 1) {
    if (!rot || !rot.angle) return p;
    const a = sign * rot.angle * Math.PI / 180, c = Math.cos(a), s = Math.sin(a), o = rot.origin;
    const q = [p[0] - o[0], p[1] - o[1], p[2] - o[2]];
    let r;
    if (rot.axis === 'x') r = [q[0], q[1] * c - q[2] * s, q[1] * s + q[2] * c];
    else if (rot.axis === 'y') r = [q[0] * c + q[2] * s, q[1], -q[0] * s + q[2] * c];
    else r = [q[0] * c - q[1] * s, q[0] * s + q[1] * c, q[2]];
    return [r[0] + o[0], r[1] + o[1], r[2] + o[2]];
}
export function corners(f, t) {
    const out = [];
    for (const x of [f[0], t[0]]) for (const y of [f[1], t[1]]) for (const z of [f[2], t[2]]) out.push([x, y, z]);
    return out;
}
/** 元素 (含旋转) 的世界包围盒 {lo, hi}。 */
export function worldBox(e) {
    const cs = corners(e.from, e.to).map((p) => rotPoint(e.rot, p));
    return { lo: [0, 1, 2].map((a) => Math.min(...cs.map((c) => c[a]))), hi: [0, 1, 2].map((a) => Math.max(...cs.map((c) => c[a]))) };
}

// ================================================================ 场景
/** 两格的切分: 朝北时 main 在西 (x 0..16, 站在正面的玩家的右手边), extension 在东 (x 16..32, 玩家左手边)。 */
export const CELLS = { main: [0, 0], extension: [16, 0] };
export const SPLIT = 16;
export const WORLD = { x: [0, 32], y: [0, 32], z: [0, 16] };

/**
 * 跑一次场景函数, 返回元素数组 (世界坐标)。
 * kind: 'idle' | 'active' | 'item' | 'hero'; frame: 工作态的帧号 (0..cycleFrames-1)。
 * cfg: {scene(ctx), hero(ctx), cycleFrames}
 * 元素: {name, from, to, faces, rot, move (运动件组名或 null), item (true | false)}
 */
export function runScene(cfg, tier, kind, frame) {
    const E = [];
    const P = palette(tier);
    const ran = [];
    const box = (name, from, to, faces, opt = {}) => {
        if (!Array.isArray(from) || !Array.isArray(to) || from.length !== 3 || to.length !== 3 || [...from, ...to].some((v) => !Number.isFinite(v))) throw new Error(`element ${name}: bad from/to`);
        for (let a = 0; a < 3; a++) if (!(to[a] > from[a])) throw new Error(`element ${name}: to must be > from on axis ${'xyz'[a]} (${from} / ${to})`);
        if (opt.rot && opt.rot.angle && !['x', 'y', 'z'].includes(opt.rot.axis)) throw new Error(`element ${name}: rotation axis`);
        const e = {
            name: String(name), from: from.slice(), to: to.slice(), faces: faceSet(name, faces),
            rot: opt.rot && opt.rot.angle ? { origin: opt.rot.origin.slice(), axis: opt.rot.axis, angle: opt.rot.angle } : null,
            move: opt.move || null, item: opt.item === undefined ? true : opt.item,
        };
        E.push(e);
        return e;
    };
    const ctx = {
        tier, T: TIERS[tier], P, M,
        kind, active: kind === 'active', idle: kind !== 'active',
        forItem: kind === 'item' || kind === 'hero',
        frame: kind === 'active' ? frame : null,
        cycleFrames: cfg.cycleFrames,
        box, flat, paint, glow, plate, mix, C,
        addOns: (map) => { const r = tierAddOns(tier, map); ran.push(...r); return r; },
        atLeast: (k) => tierAtLeast(tier, k),
    };
    (kind === 'hero' ? cfg.hero : cfg.scene)(ctx);
    let out = E;
    if (kind === 'item') out = E.filter((e) => e.item !== false);
    out.addOnsRan = [...new Set(ran)];
    return out;
}

// ================================================================ 可见面剔除 / 共面检查
const OUT_DIR = { north: [2, -1], south: [2, 1], west: [0, -1], east: [0, 1], down: [1, -1], up: [1, 1] };
function faceRect(face, e) {
    const [ax, sg] = OUT_DIR[face];
    const others = [0, 1, 2].filter((a) => a !== ax);
    return { ax, sg, v: sg > 0 ? e.to[ax] : e.from[ax], a: others[0], b: others[1], a0: e.from[others[0]], a1: e.to[others[0]], b0: e.from[others[1]], b1: e.to[others[1]] };
}
/**
 * 去掉被一个相邻 (不旋转) 元素整面盖住的面, 以及落地 (y <= 0) 的底面。
 * 运动件 (move 组) 与静态件互不遮挡: 运动件是 BlockEntityRenderer 的 ModelPart, 静态网格里被它挡住的面在它移开时要露出来。
 */
export function cullHidden(E) {
    const eps = 1e-6;
    for (const e of E) {
        for (const face of Object.keys(e.faces)) {
            if (face === 'down' && e.from[1] <= 0 && !e.rot) { delete e.faces[face]; continue; }
            if (e.rot) continue;
            const r = faceRect(face, e);
            for (const b of E) {
                if (b === e || b.rot || (b.move || null) !== (e.move || null)) continue;
                const inside = r.sg > 0 ? (b.from[r.ax] <= r.v + eps && b.to[r.ax] > r.v + eps) : (b.from[r.ax] < r.v - eps && b.to[r.ax] >= r.v - eps);
                if (!inside) continue;
                if (b.from[r.a] <= r.a0 + eps && b.to[r.a] >= r.a1 - eps && b.from[r.b] <= r.b0 + eps && b.to[r.b] >= r.b1 - eps) { delete e.faces[face]; break; }
            }
        }
    }
    return E;
}
/**
 * 同向共面重叠 (z-fighting): 旋转相同的两个元素同方向的面在同一平面上且面积重叠; 外加 y 轴旋转元素的水平面与不旋转元素的水平面同高且投影重叠。
 * E 的元素形如 {name, from, to, rot, faces}; 返回错误字符串数组。
 */
export function zfightCheck(E, label) {
    const errs = [];
    const rk = (e) => (e.rot ? JSON.stringify([e.rot.origin, e.rot.axis, e.rot.angle]) : '');
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
    for (const a of E) {
        if (!a.rot || a.rot.axis !== 'y') continue;
        for (const b of E) {
            if (b.rot) continue;
            for (const face of ['up', 'down']) {
                if (!a.faces[face] || !b.faces[face]) continue;
                const ya = face === 'up' ? a.to[1] : a.from[1], yb = face === 'up' ? b.to[1] : b.from[1];
                if (Math.abs(ya - yb) > 1e-6) continue;
                let hit = false;
                for (let sx = 0.05; sx < 1 && !hit; sx += 0.1) for (let sz = 0.05; sz < 1 && !hit; sz += 0.1) {
                    const q = rotPoint(a.rot, [a.from[0] + (a.to[0] - a.from[0]) * sx, ya, a.from[2] + (a.to[2] - a.from[2]) * sz]);
                    if (q[0] > b.from[0] && q[0] < b.to[0] && q[2] > b.from[2] && q[2] < b.to[2]) hit = true;
                }
                if (hit) errs.push(`${label}: z-fight (rotated y) ${a.name}.${face} / ${b.name}.${face}`);
            }
        }
    }
    return errs;
}

// ================================================================ 图集
/**
 * 为一个面决定贴图密度与区域 key; 画不下 (小于 2 贴图像素) 或无法对齐切口时退化为纯色块。
 * opt.noCut: 不考虑 x = 16 的切口 (BER 运动件不切两格)。
 */
export function planFace(e, face, desc, opt = {}) {
    const emit = desc.emit || 0;
    if (desc.flat) return { flat: true, role: desc.role, col: desc.col, emit };
    const [fw, fh] = faceDims(face, e.from, e.to);
    const cuts = [];
    if (!opt.noCut && e.from[0] < SPLIT && e.to[0] > SPLIT) cuts.push(SPLIT - e.from[0]);
    const ok = (d) => [fw, fh, ...cuts].every((v) => Math.abs(v * d - Math.round(v * d)) < 1e-6);
    let d = desc.d;
    while (!ok(d) && d < 4) d *= 2;
    const tw = Math.round(fw * d), th = Math.round(fh * d);
    if (!ok(d) || tw < 2 || th < 2) {
        const b = desc.m.base;
        return { flat: true, role: b.role || null, col: b, emit, degraded: desc.key };
    }
    return { flat: false, key: `${desc.key}|${tw}x${th}`, fn: desc.fn, m: desc.m, tw, th, d, emit };
}
/** 面的外观签名 (同签名 = 同一块贴图同样的发光): 纯色 → 颜色/角色, 绘制区 → key|尺寸。 */
export function planSignature(p) {
    return p.flat ? `flat:${p.role || p.col.join(',')}@${p.emit}` : `paint:${p.key}@${p.emit}`;
}

/** 把所有场景 (各档、各状态、物品) 的面规划进同一个布局。scenes: [{tier, E}] */
export function planAtlas(scenes) {
    const regions = new Map();
    const swatches = new Map();
    for (const { tier, E } of scenes) for (const e of E) {
        e.plan = {};
        for (const [face, desc] of Object.entries(e.faces)) {
            const p = planFace(e, face, desc);
            e.plan[face] = p;
            if (p.flat) {
                const k = p.role ? 'role:' + p.role : 'rgb:' + p.col.join(',');
                if (!swatches.has(k)) swatches.set(k, { key: k, swatch: true, role: p.role, col: p.col, w: 4, h: 4 });
                p.region = swatches.get(k);
            } else {
                if (!regions.has(p.key)) regions.set(p.key, { key: p.key, w: p.tw, h: p.th, d: p.d, fns: new Map() });
                const r = regions.get(p.key);
                if (!r.fns.has(tier)) r.fns.set(tier, new Set());
                r.fns.get(tier).add(p.fn);
                p.region = r;
            }
        }
    }
    const items = [...regions.values(), ...swatches.values()].sort((a, b) => b.h - a.h || b.w - a.w || a.key.localeCompare(b.key));
    for (const size of [128, 256]) {
        let x = 0, y = 0, rowH = 0, fail = false;
        for (const it of items) {
            if (x + it.w > size) { x = 0; y += rowH; rowH = 0; }
            if (y + it.h > size || it.w > size) { fail = true; break; }
            it.x = x; it.y = y; x += it.w; rowH = Math.max(rowH, it.h);
        }
        if (!fail) return { size, items, regions: regions.size, swatches: swatches.size };
    }
    const area = items.reduce((s, it) => s + it.w * it.h, 0);
    throw new Error(`atlas overflow: ${items.length} items, ${area} texels (> 256x256); lower paint densities or reuse paint keys`);
}

/** 按某一档的调色板画整张图集; 同时检查同一区域键在这一档里的所有画法是否画出同样的像素。 */
export function paintAtlas(layout, tier, errors) {
    const A = layout.size;
    const P = palette(tier);
    const atlas = new Canvas(A, A);
    atlas.fill(BOTTOM);
    for (const it of layout.items) {
        const cv = new Canvas(it.w, it.h);
        if (it.swatch) cv.fill(it.role ? resolveRole(P, it.role) : it.col);
        else {
            const own = it.fns.get(tier);
            const list = own ? [...own] : [[...it.fns.values()][0].values().next().value];
            const info = { key: it.key, w: it.w, h: it.h, d: it.d, tier, T: TIERS[tier] };
            list[0](cv, P, info);
            for (let i = 1; i < list.length && errors; i++) {
                const cv2 = new Canvas(it.w, it.h);
                list[i](cv2, P, info);
                if (!Buffer.from(cv2.data.buffer).equals(Buffer.from(cv.data.buffer))) { errors.push(`paint key collision: "${it.key}" is painted differently by two faces in tier ${TIERS[tier].key} (use distinct keys, e.g. add _on/_off)`); break; }
            }
        }
        for (let j = 0; j < it.h; j++) for (let i = 0; i < it.w; i++) atlas.px(it.x + i, it.y + j, cv.get(i, j));
    }
    return atlas;
}

/**
 * 一个面 (planFace 的结果) 在某一档里画出来的样子: 绘制区 → Canvas (tw x th, 贴图像素), 纯色 → {color}。
 * BER 运动件贴图按它逐像素取色 (ber.mjs)。
 */
export function paintPlan(p, tier) {
    const P = palette(tier);
    if (p.flat) return { color: p.role ? resolveRole(P, p.role) : p.col };
    const cv = new Canvas(p.tw, p.th);
    p.fn(cv, P, { key: p.key, w: p.tw, h: p.th, d: p.d, tier, T: TIERS[tier] });
    return { canvas: cv, d: p.d };
}

/**
 * 面上一点 (世界像素) 在该面贴图里的位置 (贴图像素, 未取整), 与 faceLocal / 原版 JSON uv 的朝向一致:
 * 北面 u 向西增大 (u = (T.x - x)), 南面 u = x - F.x, 西面 u = z - F.z, 东面 u = T.z - z, 侧面 v = T.y - y;
 * 顶面 u = x - F.x, v = z - F.z (上 = 北); 底面 u = x - F.x, v = T.z - z (上 = 南)。
 */
export function faceTexel(face, e, p, d) {
    const F = e.from, T = e.to;
    switch (face) {
        case 'north': return [(T[0] - p[0]) * d, (T[1] - p[1]) * d];
        case 'south': return [(p[0] - F[0]) * d, (T[1] - p[1]) * d];
        case 'west': return [(p[2] - F[2]) * d, (T[1] - p[1]) * d];
        case 'east': return [(T[2] - p[2]) * d, (T[1] - p[1]) * d];
        case 'up': return [(p[0] - F[0]) * d, (p[2] - F[2]) * d];
        case 'down': return [(p[0] - F[0]) * d, (T[2] - p[2]) * d];
        default: throw new Error('bad face ' + face);
    }
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
        default: throw new Error('bad face ' + face);
    }
}
export function faceUv(p, face, e, s, A) {
    const R = p.region;
    if (p.flat) return [R.x + 1, R.y + 1, R.x + 3, R.y + 3].map((v) => r4(v * 16 / A));
    const [u0, v0, u1, v1] = faceLocal(face, e, s);
    return [R.x + u0 * p.d, R.y + v0 * p.d, R.x + u1 * p.d, R.y + v1 * p.d].map((v) => r4(v * 16 / A));
}
export function faceJson(p, uv) {
    const f = { uv, texture: '#atlas' };
    if (p.emit > 0) f.forge_data = { block_light: p.emit, sky_light: p.emit };
    return f;
}

/** 元素的剩余面全部自发光 → shade:false。 */
export function autoShade(E) {
    for (const e of E) {
        const faces = Object.values(e.faces);
        e.noShade = faces.length > 0 && faces.every((d) => (d.emit || 0) > 0);
    }
}

/** 把世界元素切到某一格, 返回局部坐标元素 JSON (切口处的内面去掉)。 */
export function cellElements(E, cell, A, errors, label) {
    const [ox, oz] = CELLS[cell];
    const out = [];
    for (const e of E) {
        const x0 = Math.max(e.from[0], ox), x1 = Math.min(e.to[0], ox + 16);
        const z0 = Math.max(e.from[2], oz), z1 = Math.min(e.to[2], oz + 16);
        if (x1 - x0 <= 1e-6 || z1 - z0 <= 1e-6) continue;
        if (e.rot && e.rot.axis !== 'x' && (x0 !== e.from[0] || x1 !== e.to[0])) { errors.push(`${label}: rotated element ${e.name} (axis ${e.rot.axis}) straddles the cell seam x = 16; move it into one cell or rotate about x`); continue; }
        if (e.rot && e.rot.axis !== 'z' && (z0 !== e.from[2] || z1 !== e.to[2])) { errors.push(`${label}: rotated element ${e.name} leaves the cell in z`); continue; }
        const s = { from: [x0, e.from[1], z0], to: [x1, e.to[1], z1] };
        const faces = {};
        for (const [face, p] of Object.entries(e.plan)) {
            if (face === 'east' && x1 < e.to[0]) continue;
            if (face === 'west' && x0 > e.from[0]) continue;
            if (face === 'south' && z1 < e.to[2]) continue;
            if (face === 'north' && z0 > e.from[2]) continue;
            faces[face] = faceJson(p, faceUv(p, face, e, s, A));
        }
        if (!Object.keys(faces).length) continue;
        const el = { name: e.name + (x0 > e.from[0] || x1 < e.to[0] ? '~' + cell : ''), from: [r4(x0 - ox), r4(e.from[1]), r4(z0 - oz)], to: [r4(x1 - ox), r4(e.to[1]), r4(z1 - oz)] };
        if (e.rot) {
            const o = [e.rot.origin[0] - ox, e.rot.origin[1], e.rot.origin[2] - oz];
            if (e.rot.axis === 'x') o[0] = Math.min(16, Math.max(0, o[0]));
            if (e.rot.axis === 'z') o[2] = Math.min(16, Math.max(0, o[2]));
            el.rotation = { angle: e.rot.angle, axis: e.rot.axis, origin: o.map(r4) };
        }
        if (e.noShade) el.shade = false;
        el.faces = faces;
        out.push(el);
    }
    return out;
}

// ================================================================ 校验
/**
 * 校验一个模型 JSON。opts: {item: 物品模型 (坐标 [-16,32], 最小厚度 0.2), items: 图集布局, A: 图集边长}
 * 返回 {errs, maxY, minY, offGrid, lo, hi}; lo/hi 是全部元素 (含旋转角点) 的包围盒。
 */
export function validateModel(file, json, opts) {
    const errs = [];
    const { A, items } = opts;
    const isItem = !!opts.item;
    const minT = isItem ? 0.2 : 0.5;
    const lo = isItem ? [-16, -16, -16] : [0, 0, 0], hi = isItem ? [32, 32, 32] : [16, 32, 16];
    const tex = json.textures || {};
    const bLo = [Infinity, Infinity, Infinity], bHi = [-Infinity, -Infinity, -Infinity];
    if (!tex.particle) errs.push(file + ': missing particle texture');
    if (!Array.isArray(json.elements) || !json.elements.length) { errs.push(file + ': no elements'); return { errs, maxY: 0, offGrid: 0, lo: bLo, hi: bHi }; }
    if (json.elements.length > 90) errs.push(`${file}: ${json.elements.length} elements (> 90)`);
    const resolve = (ref, depth = 0) => {
        if (typeof ref !== 'string') return null;
        if (!ref.startsWith('#')) return ref;
        if (depth > 8) return null;
        const v = tex[ref.slice(1)];
        return v === undefined ? null : resolve(v, depth + 1);
    };
    const onGrid = (v, g) => Math.abs(v / g - Math.round(v / g)) < 1e-6;
    let offGrid = 0;
    const grow = (p) => { for (let a = 0; a < 3; a++) { bLo[a] = Math.min(bLo[a], p[a]); bHi[a] = Math.max(bHi[a], p[a]); } };
    json.elements.forEach((el, i) => {
        const id = `${file}#${i}(${el.name || ''})`;
        for (let a = 0; a < 3; a++) {
            if (el.from[a] < lo[a] - 1e-9 || el.to[a] > hi[a] + 1e-9) errs.push(`${id}: ${'xyz'[a]} out of [${lo[a]},${hi[a]}] (${el.from} / ${el.to})`);
            if (el.to[a] - el.from[a] < minT - 1e-6) errs.push(`${id}: thinner than ${minT} on ${'xyz'[a]}`);
            if (!isItem) {
                if (!onGrid(el.from[a], 0.25) || !onGrid(el.to[a], 0.25)) errs.push(`${id}: coordinate off the 0.25 grid on ${'xyz'[a]} (${el.from[a]} / ${el.to[a]})`);
                else if (!onGrid(el.from[a], 0.5) || !onGrid(el.to[a], 0.5)) offGrid++;
            }
        }
        if ('shade' in el && typeof el.shade !== 'boolean') errs.push(`${id}: shade must be boolean`);
        if (el.rotation) {
            const r = el.rotation;
            if (!['x', 'y', 'z'].includes(r.axis)) errs.push(id + ': bad rotation axis');
            if (!LEGAL_ANGLES.includes(r.angle)) errs.push(id + ': illegal rotation angle ' + r.angle);
            if (r.rescale) errs.push(id + ': rescale not allowed');
            if (!Array.isArray(r.origin) || r.origin.length !== 3) errs.push(id + ': rotation origin');
            for (const p of corners(el.from, el.to)) {
                const w = rotPoint(r, p);
                grow(w);
                if (w.some((v, a) => v < lo[a] - 0.001 || v > hi[a] + 0.001)) { errs.push(`${id}: rotated corner leaves the cell ${w.map(r4)}`); break; }
            }
        } else { grow(el.from); grow(el.to); }
        const faces = Object.entries(el.faces || {});
        if (!faces.length) errs.push(id + ': no faces');
        for (const [face, f] of faces) {
            if (!FACES.includes(face)) errs.push(`${id}: bad face ${face}`);
            if (!Array.isArray(f.uv) || f.uv.length !== 4) { errs.push(`${id}.${face}: missing uv`); continue; }
            if (f.uv.some((v) => !(v >= 0 && v <= 16))) errs.push(`${id}.${face}: uv out of 0..16`);
            if (f.cullface) errs.push(`${id}.${face}: cullface is not allowed`);
            if (!resolve(f.texture)) errs.push(`${id}.${face}: unresolved texture ${f.texture}`);
            if (f.rotation && ![0, 90, 180, 270].includes(f.rotation)) errs.push(`${id}.${face}: illegal face rotation`);
            if (f.forge_data) {
                for (const k of ['block_light', 'sky_light']) {
                    const v = f.forge_data[k];
                    if (!(Number.isInteger(v) && v >= 0 && v <= 15)) errs.push(`${id}.${face}: forge_data.${k} must be an integer 0..15`);
                }
            }
            const px = f.uv.map((v) => v * A / 16);
            const ulo = [Math.min(px[0], px[2]), Math.min(px[1], px[3])], uhi = [Math.max(px[0], px[2]), Math.max(px[1], px[3])];
            const n = items.filter((it) => ulo[0] >= it.x - 1e-6 && ulo[1] >= it.y - 1e-6 && uhi[0] <= it.x + it.w + 1e-6 && uhi[1] <= it.y + it.h + 1e-6).length;
            if (n !== 1) errs.push(`${id}.${face}: uv inside ${n} atlas items (must be exactly 1)`);
        }
    });
    return { errs, maxY: bHi[1], offGrid, lo: bLo, hi: bHi };
}

// ================================================================ 物品模型
export const ITEM_DISPLAY = {
    gui: { rotation: [24, 200, 0], translation: [0, 0.5, 0], scale: [0.78, 0.78, 0.78] },
    ground: { rotation: [0, 0, 0], translation: [0, 3, 0], scale: [0.4, 0.4, 0.4] },
    fixed: { rotation: [0, 180, 0], translation: [0, 1, 0], scale: [0.8, 0.8, 0.8] },
    head: { rotation: [0, 0, 0], translation: [0, 0, 0], scale: [1, 1, 1] },
    thirdperson_righthand: { rotation: [75, 225, 0], translation: [0, 2.5, 0], scale: [0.5, 0.5, 0.5] },
    thirdperson_lefthand: { rotation: [75, 225, 0], translation: [0, 2.5, 0], scale: [0.5, 0.5, 0.5] },
    firstperson_righthand: { rotation: [0, 225, 0], translation: [0, 2, 0], scale: [0.55, 0.55, 0.55] },
    firstperson_lefthand: { rotation: [0, 225, 0], translation: [0, 2, 0], scale: [0.55, 0.55, 0.55] },
};
/** 物品模型元素: 缩小的整台 (中心 x 16 z 8 → 物品 x 8 z 8, 高度从 y 0 起) + 主角道具 (hero, 物品坐标直接给)。 */
export function itemElements(itemCfg, machine, hero, A) {
    const k = itemCfg.scale, off = itemCfg.offset || [0, 0, 0];
    const P = (p) => [8 + (p[0] - 16) * k + off[0], p[1] * k + off[1], 8 + (p[2] - 8) * k + off[2]];
    const els = [];
    let dropped = 0;
    const push = (e, from, to, rot, faces) => {
        const el = { name: e.name, from: from.map(r4), to: to.map(r4) };
        if (rot) el.rotation = { angle: rot.angle, axis: rot.axis, origin: rot.origin.map(r4) };
        if (e.noShade) el.shade = false;
        el.faces = faces;
        els.push(el);
    };
    for (const e of machine) {
        const faces = {};
        for (const [face, p] of Object.entries(e.plan)) faces[face] = faceJson(p, faceUv(p, face, e, e, A));
        if (!Object.keys(faces).length) continue;
        const from = P(e.from), to = P(e.to);
        if ([0, 1, 2].some((a) => to[a] - from[a] < 0.2 - 1e-6)) { dropped++; continue; }
        push(e, from, to, e.rot && { ...e.rot, origin: P(e.rot.origin) }, faces);
    }
    for (const e of hero) {
        const faces = {};
        for (const [face, p] of Object.entries(e.plan)) faces[face] = faceJson(p, faceUv(p, face, e, e, A));
        if (!Object.keys(faces).length) continue;
        push(e, e.from, e.to, e.rot, faces);
    }
    return { els, dropped };
}

// ================================================================ 粒子贴图 / 文本 / PNG
/** 16x16 破坏粒子贴图: 枪灰板 + 档位饰色下沿 + 警示条。 */
export function defaultParticle(c, P) {
    const m = M.frame;
    c.fill(m.base);
    for (let y = 3; y < 13; y += 4) c.hline(1, y, 14, mix(m.base, m.hi, 0.35));
    c.bevel(m);
    c.bolt(2, 2, m); c.bolt(12, 2, m);
    c.rect(1, 12, 14, 3, P.trim.base); c.hline(1, 12, 14, P.trim.hi);
    c.hazard(1, 14, 14, 1, 4);
}

/** 模型 JSON 文本: 元素一行一个 (与冲压机/组装台生成器同一格式, 便于 diff)。 */
export function stringifyModel(model) {
    const lines = ['{'];
    const keys = Object.keys(model);
    keys.forEach((k, i) => {
        const last = i === keys.length - 1;
        if (k === 'elements') {
            lines.push('  "elements": [');
            model.elements.forEach((el, j) => lines.push('    ' + JSON.stringify(el) + (j < model.elements.length - 1 ? ',' : '')));
            lines.push('  ]' + (last ? '' : ','));
        } else lines.push(`  ${JSON.stringify(k)}: ${JSON.stringify(model[k])}` + (last ? '' : ','));
    });
    lines.push('}');
    return lines.join('\n') + '\n';
}

/** RGBA 8 位 PNG (编码器在 ../gunsmith_workstation/png.mjs)。 */
export const encodePng = (w, h, data) => encodePngImage({ width: w, height: h, data });
