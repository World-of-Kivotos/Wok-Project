// 军火台四个原料的物品贴图 (底火 / 弹壳 / 弹头 / 发射药), 2026-09 图标重绘选定的方向 C「细像素」: 32×32 手绘感像素图标。
// 零依赖、确定性: 旋转体用「切片画家算法」投影成 3/4 视角, 光照量化进 5~6 阶色带,
// 再做外描边与孤立像素清理, 保证只有成簇的色块、alpha 只有 0/255。
// 直接写仓库里的 textures/item/<id>.png (物品模型是 item/generated, 不用改); 不要手改这四张图, 改这里再重跑。
//   node tools/munitions_icons/generate_input_icons.mjs [输出目录]   (默认 src/main/resources/assets/miningdim/textures/item)
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { writePng } from '../gunsmith_workstation/png.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const OUT = path.resolve(process.argv[2]
    || path.join(HERE, '..', '..', 'src', 'main', 'resources', 'assets', 'miningdim', 'textures', 'item'));
const N = 32;
let WITH_LABEL = true;

const hex = (h) => [parseInt(h.slice(1, 3), 16), parseInt(h.slice(3, 5), 16), parseInt(h.slice(5, 7), 16)];

// 每种材质一条色阶: [0] = 描边, 其余由暗到亮 (暗部偏暖/偏红, 亮部偏黄/偏冷, 与原版金/铜一致的色相偏移)。
export const PALETTES = {
    brass: ['#2b1505', '#5c360b', '#8c5a14', '#b9851f', '#dcaf3c', '#f3d673', '#fff6c8'],
    copper: ['#2a0c06', '#581e0f', '#853219', '#b24c24', '#d66e37', '#ef9b5e', '#ffd3a8'],
    lead: ['#16181c', '#33373e', '#50565f', '#727a84', '#98a0aa'],
    nickel: ['#1a1d23', '#3f4550', '#687180', '#98a2b0', '#c6ced8', '#eef3f8'],
    sealant: ['#2a0a08', '#5a1a14', '#8a2a1e', '#b4432c'],
    anvil: ['#2b1a05', '#86681a', '#b4922c', '#d9bb4c', '#f6e39a'],
    paper: ['#3a2c1a', '#8a7552', '#bfa983', '#e0cfa6', '#f6ecd0'],
    powder: ['#0b100d', '#18211b', '#26332a', '#38493b', '#566b57', '#8ea58c', '#d2e0cc'],
    glass: ['#1b262c', '#46606b', '#7b9ca8', '#b3d2da', '#eafaff'],
    cap: ['#0e0e0d', '#2a2a28', '#3a3a37', '#4a4a46', '#66665f', '#8a8a82'],
    flame: ['#3a0c08', '#8a1f14', '#cc3a1e', '#f07a2a', '#ffd04a'],
};
const PAL = Object.fromEntries(Object.entries(PALETTES).map(([k, v]) => [k, v.map(hex)]));

// ---------- 画布 ----------
function canvas() {
    return { mat: new Array(N * N).fill(null), shade: new Int8Array(N * N), lock: new Uint8Array(N * N) };
}
function put(cv, x, y, mat, shade, lock = false) {
    if (x < 0 || y < 0 || x >= N || y >= N) return;
    const i = y * N + x;
    cv.mat[i] = mat;
    cv.shade[i] = Math.max(0, Math.min(PAL[mat].length - 1, shade));
    if (lock) cv.lock[i] = 1;
}
function get(cv, x, y) {
    if (x < 0 || y < 0 || x >= N || y >= N) return null;
    const i = y * N + x;
    return cv.mat[i] ? { mat: cv.mat[i], shade: cv.shade[i], lock: cv.lock[i] } : null;
}

// ---------- 小向量工具 ----------
const norm = (v) => { const l = Math.hypot(...v) || 1; return v.map((c) => c / l); };
const dot = (a, b) => a.reduce((s, c, i) => s + c * b[i], 0);
const LIGHT = norm([-0.55, -0.75, 0.85]); // 左上前方
const HALF = norm([LIGHT[0], LIGHT[1], LIGHT[2] + 1]);

/**
 * 旋转体投影。轴从 base 沿 dir 长 L, 半径轮廓 r(t)。
 * k>0: t=L 端朝向观者 (看得到顶面), k<0: t=0 端朝向观者。|k| = 端面椭圆扁率。
 * 返回每个可见像素的 {t, u, kind, n}: u∈[-1,1] 越大越靠近受光边; kind = side|face|cap; n = 3D 法线。
 */
function revolve({ base, dir, L, r, k, dt = 0.02 }) {
    const d = norm(dir);
    let P = [-d[1], d[0]];
    if (-P[0] - P[1] < 0) P = [-P[0], -P[1]]; // P 指向左上 (受光侧)
    const kk = Math.abs(k), sgn = k >= 0 ? 1 : -1, cos = Math.sqrt(1 - kk * kk);
    const axis3 = [d[0] * cos, d[1] * cos, sgn * kk];
    const front3 = [-sgn * kk * d[0], -sgn * kk * d[1], cos];
    const P3 = [P[0], P[1], 0];
    const steps = Math.round(L / dt);
    const rec = new Map();
    for (let j = 0; j <= steps; j++) {
        const i = sgn > 0 ? j : steps - j;
        const t = (i / steps) * L;
        const rr = r(t);
        if (!(rr > 0)) continue;
        const cx = base[0] + d[0] * t, cy = base[1] + d[1] * t;
        const ea = Math.max(kk * rr, 0.01);
        const ext = Math.max(ea, rr) + 1;
        for (let y = Math.floor(cy - ext); y <= Math.ceil(cy + ext); y++) {
            for (let x = Math.floor(cx - ext); x <= Math.ceil(cx + ext); x++) {
                if (x < 0 || y < 0 || x >= N || y >= N) continue;
                const px = x + 0.5 - cx, py = y + 0.5 - cy;
                const a = (px * d[0] + py * d[1]) / (sgn * ea); // 近边 a≈-1
                const b = (px * P[0] + py * P[1]) / rr;
                const rho2 = a * a + b * b;
                if (rho2 <= 1) rec.set(y * N + x, { x, y, t, a, b, rho: Math.sqrt(rho2), rr, last: j === steps });
            }
        }
    }
    const out = [];
    const eps = dt * 1.5;
    for (const e of rec.values()) {
        const tNext = Math.max(0, Math.min(L, e.t + sgn * eps));
        const step = r(tNext) < e.rr * 0.93;
        let kind = 'side';
        if (e.rho < 0.86 && e.last) kind = 'cap';
        else if (e.rho < 0.86 && step) kind = 'face';
        let n;
        if (kind === 'side') {
            const b = Math.max(-1, Math.min(1, e.b));
            const slope = (r(Math.min(L, e.t + 0.25)) - r(Math.max(0, e.t - 0.25))) / 0.5;
            const w = Math.sqrt(1 - b * b);
            n = norm([0, 1, 2].map((c) => b * P3[c] + w * front3[c] - slope * axis3[c]));
        } else {
            n = axis3.map((c) => c * sgn);
        }
        out.push({ ...e, u: e.b, kind, n, P, d, axis3, front3, P3, sgn });
    }
    return out;
}

/** 金属光照: 漫反射 + 天空/地面反射 + 高光, 返回 0..1 */
function metalLight(n, { amb = 0.18, dif = 0.55, env = 0.3, spec = 0.9, shin = 18 } = {}) {
    const I = Math.max(0, dot(n, LIGHT));
    const s = Math.pow(Math.max(0, dot(n, HALF)), shin);
    // 反射向量的 y (屏幕向下为正): 朝上反射到亮天空, 朝下反射到暗地面
    const ry = 2 * n[2] * n[1];
    const e = ry < -0.15 ? 1 : ry < 0.25 ? 0.55 : 0.15;
    return amb + dif * I + env * e + spec * s;
}
const quant = (v, th) => 1 + th.filter((x) => v > x).length;

// ---------- 后处理 ----------
function cleanOrphans(cv) {
    const next = cv.shade.slice();
    for (let y = 0; y < N; y++) for (let x = 0; x < N; x++) {
        const c = get(cv, x, y);
        if (!c || c.lock || c.shade === 0) continue;
        let same = 0;
        const cnt = new Map();
        for (let dy = -1; dy <= 1; dy++) for (let dx = -1; dx <= 1; dx++) {
            if (!dx && !dy) continue;
            const o = get(cv, x + dx, y + dy);
            if (!o || o.mat !== c.mat) continue;
            if (o.shade === c.shade) same++;
            if (Math.abs(dx) + Math.abs(dy) === 1) cnt.set(o.shade, (cnt.get(o.shade) || 0) + 1);
        }
        if (same === 0 && cnt.size) {
            const best = [...cnt.entries()].sort((p, q) => q[1] - p[1] || p[0] - q[0])[0];
            if (best[1] >= 2) next[y * N + x] = best[0];
        }
    }
    cv.shade.set(next);
}

/** 在描边之前调用: 不贴边 (4 邻接都有像素) 的未锁定暗色 (shade<=from) 提到 to, 暗色只留在贴描边的 1px */
function liftInteriorDark(cv, mat, from, to) {
    const lift = [];
    for (let y = 0; y < N; y++) for (let x = 0; x < N; x++) {
        const c = get(cv, x, y);
        if (!c || c.mat !== mat || c.lock || c.shade > from) continue;
        if ([[0, 1], [1, 0], [0, -1], [-1, 0]].every(([dx, dy]) => get(cv, x + dx, y + dy))) lift.push(y * N + x);
    }
    for (const i of lift) cv.shade[i] = to;
}

/** 1px 外描边 (4 邻接), 颜色取相邻材质的 [0] 色。soft: 左上受光侧改用 [1] 色 */
function outline(cv, { soft = [] } = {}) {
    const add = [];
    for (let y = 0; y < N; y++) for (let x = 0; x < N; x++) {
        if (get(cv, x, y)) continue;
        const nb = [[0, 1], [1, 0], [0, -1], [-1, 0]].map(([dx, dy]) => [dx, dy, get(cv, x + dx, y + dy)]).filter((e) => e[2]);
        if (!nb.length) continue;
        const onlyLit = nb.every(([dx, dy]) => dx > 0 || dy > 0); // 形体在右/下 => 该点位于左上边
        const m = nb[0][2].mat;
        add.push([x, y, m, soft.includes(m) && onlyLit ? 1 : 0]);
    }
    for (const [x, y, m, s] of add) put(cv, x, y, m, s, true);
}

function toImage(cv) {
    const data = new Uint8ClampedArray(N * N * 4);
    for (let i = 0; i < N * N; i++) {
        if (!cv.mat[i]) continue;
        const c = PAL[cv.mat[i]][cv.shade[i]];
        data.set([c[0], c[1], c[2], 255], i * 4);
    }
    return { width: N, height: N, data };
}

// 确定性伪随机
function rng(seed) {
    let a = seed >>> 0;
    return () => {
        a = (a + 0x6d2b79f5) >>> 0;
        let t = a;
        t = Math.imul(t ^ (t >>> 15), t | 1);
        t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
        return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
}

// ---------- 弹壳: 黄铜瓶颈步枪弹壳, 底缘左下 → 口部右上 ----------
function casing() {
    const cv = canvas();
    const L = 27.6;
    // v10: 肩从 2.8 长的缓坡改成 1.1 长的硬台阶 (v9 连续收窄, 读成酒瓶 / 雪茄)
    const SH0 = 19.4, SH1 = 20.5;
    const r = (t) => {
        if (t < 1.7) return 4.7; // 底缘
        if (t < 3.1) return 3.55; // 抽壳槽
        if (t < 3.9) return 3.55 + (t - 3.1) * 1.0; // 槽后斜面
        if (t < SH0) return 4.4 - (t - 3.9) * 0.018; // 壳体 (微锥)
        if (t < SH1) return 4.12 - (t - SH0) * ((4.12 - 2.65) / (SH1 - SH0)); // 肩
        return 2.65; // 颈
    };
    const px = revolve({ base: [6.6, 25.4], dir: [1, -1], L, r, k: 0.62 });
    for (const p of px) {
        let s;
        if (p.kind === 'cap') {
            // 口部: 外圈唇口亮, 内部是黑洞 (远侧内壁留一线暗铜色)
            if (p.rho < 0.64) s = p.a > 0.25 ? 1 : 0;
            else s = p.b > 0.15 ? 6 : p.b > -0.45 ? 5 : 4;
            put(cv, p.x, p.y, 'brass', s, true);
            continue;
        }
        const v = metalLight(p.n, { amb: 0.1, dif: 0.55, env: 0.3, spec: 0.8, shin: 16 });
        s = quant(v, [0.36, 0.55, 0.76, 1.0, 1.3]);
        if (p.kind === 'face') s = p.t < 3 ? Math.min(Math.max(s, 3), 4) : Math.max(s, 4); // 底缘端面别太亮
        if (p.kind === 'side' && p.t >= 1.7 && p.t < 3.1) s = Math.min(s - 1, 3); // 抽壳槽压暗
        // 肩: 朝口部的斜面受光, 亮一档; 肩下 (壳体末端) 一条 1px 暗线把台阶切出来
        if (p.kind === 'side' && p.t >= SH0 && p.t < SH1) s = Math.max(s, p.u > -0.2 ? 5 : 4);
        if (p.kind === 'side' && p.t >= SH0 - 0.7 && p.t < SH0 && p.u < 0.75) { put(cv, p.x, p.y, 'brass', 1, true); continue; }
        // 壳体上一条连续的 1px 高光线
        if (p.kind === 'side' && p.t > 4.4 && p.t < SH0 - 0.8 && p.u > 0.42 && p.u < 0.66) { put(cv, p.x, p.y, 'brass', 6, true); continue; }
        put(cv, p.x, p.y, 'brass', s);
    }
    cleanOrphans(cv);
    // v10: 壳体暗部不再大片用 #5c360b (深色槽位上 48% 的内圈消失), 只在贴着描边的 1px 保留
    liftInteriorDark(cv, 'brass', 1, 2);
    outline(cv);
    return cv;
}

// ---------- 弹头: 铜被甲尖头弹, 竖立, 从略低处看得到铅芯底面 ----------
function bulletHead() {
    const cv = canvas();
    // v10: R 5.7 → 5.0 (x10~21, 长宽比 ≈2.6:1, 更像尖头弹而不是子弹形胶囊)
    const L = 27.6, R = 5.0, Ln = 15.5;
    const rho = (R * R + Ln * Ln) / (2 * R);
    const r = (t) => {
        if (t < 2.4) return 4.0 + (t / 2.4) * (R - 4.0); // 船尾
        if (t < L - Ln) return R;
        const x = L - t; // 距尖端
        return Math.max(0.55, Math.sqrt(rho * rho - (Ln - x) * (Ln - x)) + R - rho);
    };
    const BY = 29.3;
    const px = revolve({ base: [16, BY], dir: [0, -1], L, r, k: -0.36 });
    for (const p of px) {
        if (p.kind === 'cap') {
            // 底面: 铜被甲收口一圈, 中间露出铅芯
            if (p.rho < 0.64) put(cv, p.x, p.y, 'lead', p.b > 0.15 ? 3 : p.b > -0.4 ? 2 : 1, true);
            else put(cv, p.x, p.y, 'copper', p.b > 0 ? 3 : 2, true);
            continue;
        }
        const v = metalLight(p.n, { amb: 0.1, dif: 0.55, env: 0.28, spec: 0.7, shin: 16 });
        let s = Math.min(4, quant(v, [0.36, 0.55, 0.76, 1.0, 1.3]));
        // v9 的 3px 宽 #ffd3a8 让铜色发粉 (像三文鱼); 改成 1px 最亮 + 1px 次亮
        if (p.kind === 'side' && p.rr > 1.6) {
            if (p.u > 0.34 && p.u <= 0.56) s = 6;
            else if (p.u > 0.12 && p.u <= 0.34) s = 5;
        }
        put(cv, p.x, p.y, 'copper', s);
    }
    // 压环槽: v9 按 t 压暗, 暗带 (第 20 行) 和剪影内收 (第 22 行) 错开成了两道槽; v10 合并在同一行:
    // 这一行两端各内收 1px (描边随之内收), 其余整行压暗一档
    const GY = 21;
    const row = [...Array(N).keys()].filter((x) => get(cv, x, GY)?.mat === 'copper');
    if (row.length) {
        const x0 = Math.min(...row), x1 = Math.max(...row);
        cv.mat[GY * N + x0] = null; cv.mat[GY * N + x1] = null;
        for (let x = x0 + 1; x < x1; x++) { const c = get(cv, x, GY); put(cv, x, GY, 'copper', c.shade >= 5 ? 4 : Math.max(1, c.shade - 1), true); }
    }
    cleanOrphans(cv);
    outline(cv);
    return cv;
}

// ---------- 底火: 两枚镍色底火杯, 开口朝上, 看得到杯内凹陷、暗红密封药和黄铜击砧 ----------
// v10 (交叉评审后): v9 的黄色击发药齐平填满开口, 读成「罐头盖 / 茶蜡」。改为:
//   开口内 = 远侧内壁暗色月牙 (越靠杯底越暗, 右侧内壁受光) + 杯底;
//   杯底中央一小块暗红密封药 (≈8×3), 上面压一个黄铜击砧 (≥2px 宽, GUI 3 的 1.5 倍缩放下不丢)。
//   两杯多叠 3~4px, 剪影不再是上下两个球的「雪人」。
// v10 的取舍 (变体对比见 renders/iterations/v10_primer_variants_*.png):
//   - k: 0.56 → 0.60 (略俯视, 开口里多一行); 0.68 太俯视, 杯内暗区过大, 像两截黑管口
//   - 内壁: #687180 / 右侧受光 #98a2b0, 接触阴影 #3f4550 只在中段 (整片 #1a1d23 + 红带像「面罩 / 一张脸」)
//   - 击砧: 6px 宽 Y 形, 腿 1px、中心 2px; 小杯里 2×2 黄铜块。分开的 1px 腿会像两只眼睛
//   - 后一枚小杯往左下挪 (≈3px 更多重叠), 剪影从「雪人」变成一簇
export const PRIMER_DEFAULT = {
    K: 0.6,
    wall: [2, 3], // 远侧内壁 [左/中, 右] 色阶
    floor: [1, 2], // 杯底 [左/中, 右]
    contactShade: 1,
    cups: [
        // 后一枚小杯 (右上), 前一枚大杯 (左下); 真实底火高/径 ≈ 0.6, 这里略矮, 读成「小杯」而不是「罐头」
        { base: [19.8, 17.0], R: 5.9, H: 3.8, depth: 2.2, comp: [2.9, 1.25], anvil: ['BH', 'AB'], contact: false },
        { base: [13.6, 26.4], R: 8.0, H: 5.0, depth: 2.6, comp: [4.3, 1.6], anvil: ['B....B', '.BHHB.', '..BA..'] },
    ],
};
let PRIMER = PRIMER_DEFAULT;
export function primerVariant(opts) {
    PRIMER = { ...PRIMER_DEFAULT, ...opts };
    try { return toImage(primer()); } finally { PRIMER = PRIMER_DEFAULT; }
}

function primer() {
    const cv = canvas();
    const { K, wall, floor, contactShade } = PRIMER;
    const cos = Math.sqrt(1 - K * K);
    /** depth: 开口到杯底在屏幕上的下移 (px); comp: 密封药半宽/半高 (px); anvil: 击砧点阵 */
    const drawCup = ({ base, R, H, depth, comp, anvil, contact = true }) => {
        const ea = K * R;
        const lip = R < 6.5 ? 0.7 : 0.78;
        const ox = base[0], oy = base[1] - H; // 开口椭圆中心 (revolve 里轴长即屏幕长度)
        const fy0 = oy + depth * cos; // 杯底椭圆中心
        // 杯底的近半边被近侧唇口挡住; 密封药放在「看得见的那块杯底」中间, 而不是几何中心 (否则一半被挡)
        const cyc = (fy0 + oy) / 2;
        const px = revolve({ base, dir: [0, -1], L: H, r: () => R, k: K });
        for (const p of px) {
            if (p.kind !== 'cap') {
                // 竖直杯壁: 金属竖向色带 (亮边-窄高光-中-暗-2px 反光边), 不用平滑渐变。
                // v10: 暗带收窄、反光边加宽, 深色槽位上右侧内圈不再是 #3f4550 (与槽位同亮度)
                const u = p.u;
                const s = u > 0.86 ? 3 : u > 0.56 ? 5 : u > 0.22 ? 3 : u > -0.25 ? 2 : u > -0.62 ? 1 : u > -0.86 ? 2 : 3;
                put(cv, p.x, p.y, 'nickel', s);
                continue;
            }
            if (p.rho > lip) { put(cv, p.x, p.y, 'nickel', p.b > 0.25 ? 5 : p.b > -0.5 ? 4 : 3, true); continue; } // 唇口: 最亮
            const fx = (p.x + 0.5 - ox) / (lip * R), fy = (p.y + 0.5 - fy0) / (lip * ea);
            const fr = Math.hypot(fx, fy);
            if (fr > 1) {
                // 远侧内壁: 凹面受光方向与外壁相反 (右侧内壁朝左上, 亮一档); 贴近杯底的一圈最暗 (接触阴影, 只在中段)
                const s = fr < 1.16 && fx > -0.5 && fx < 0.2 && contact ? contactShade : fx > 0.3 ? wall[1] : wall[0];
                put(cv, p.x, p.y, 'nickel', s, true);
                continue;
            }
            const cx = (p.x + 0.5 - ox) / comp[0], cy = (p.y + 0.5 - cyc) / comp[1];
            if (cx * cx + cy * cy <= 1) {
                put(cv, p.x, p.y, 'sealant', cx < -0.1 && cy < 0.1 ? 2 : 1, true); // 暗红密封药, 左上略亮
            } else {
                put(cv, p.x, p.y, 'nickel', fx > 0.3 ? floor[1] : floor[0], true); // 杯底: 暗; 右侧被左上光照进来, 亮一档
            }
        }
        // 击砧: 手摆点阵, 以密封药中心为原点 (A=暗面 B=亮面 H=高光)
        const tone = { A: 2, B: 3, H: 4 };
        const ax = Math.round(ox - anvil[0].length / 2), ay = Math.round(cyc - anvil.length / 2);
        anvil.forEach((row, y) => [...row].forEach((ch, x) => { if (tone[ch]) put(cv, ax + x, ay + y, 'anvil', tone[ch], true); }));
    };
    for (const c of PRIMER.cups) drawCup(c);
    cleanOrphans(cv);
    outline(cv);
    return cv;
}

// ---------- 发射药: 玻璃药瓶装深灰绿片状无烟药, 近黑滚花盖, 标签上红色火焰, 瓶前洒出一小撮 ----------
function propellant() {
    const cv = canvas();
    // v10: 瓶身 R 8.2 → 7.4、整体变矮 (v9 占画布 55%, 比另外三个都重);
    // 盖子从 1/3 高的红色大盖改成矮的近黑滚花盖 (v9 读成果酱瓶 / 药瓶), 红色只留给标签上的火焰
    const BODY = 13.6, SHOULDER = 15.6, NECK = 16.8, L = 20.0;
    const r = (t) => {
        if (t < 0.7) return 6.7 + t;
        if (t < BODY) return 7.4;
        if (t < SHOULDER) return 7.4 - (t - BODY) * ((7.4 - 5.2) / (SHOULDER - BODY));
        if (t < NECK) return 5.2; // 瓶颈
        return 5.9; // 盖
    };
    const CX = 17.5;
    const px = revolve({ base: [CX, 28.9], dir: [0, -1], L, r, k: 0.3 });
    const rand = rng(1337);
    // 片状药纹理: 3x3 抖动网格, 每格一片 2~3 像素的药片 (1 = 片面, 0 = 片身, 2 = 石墨反光)
    const grain = new Map();
    for (let gy = 0; gy < N; gy += 3) for (let gx = 0; gx < N; gx += 3) {
        const ox = gx + Math.floor(rand() * 2), oy = gy + Math.floor(rand() * 2);
        const shape = [[[0, 0], [1, 0]], [[0, 0], [0, 1]], [[0, 0], [1, 0], [0, 1]], [[0, 0], [1, 1]]][Math.floor(rand() * 4)];
        const glint = rand() < 0.16;
        shape.forEach(([dx, dy], i) => grain.set((oy + dy) * N + ox + dx, i === 0 ? (glint ? 2 : 1) : 0));
    }
    const LABEL = WITH_LABEL ? [2.2, 8.8] : null;
    const labelPx = [];
    for (const p of px) {
        if (LABEL && p.kind === 'side' && p.t >= LABEL[0] && p.t < LABEL[1] && Math.abs(p.u) <= 0.86) {
            const edge = p.t < LABEL[0] + 0.9 || p.t >= LABEL[1] - 0.9;
            put(cv, p.x, p.y, 'paper', p.u > 0.45 ? 4 : p.u > -0.35 ? 3 : 2);
            if (edge) put(cv, p.x, p.y, 'paper', p.u > -0.35 ? 2 : 1, true);
            labelPx.push(p);
            continue;
        }
        if (p.t >= NECK) {
            // 盖子: 近黑滚花, 侧面竖纹, 顶面平 (顶面亮一档, 深色槽位上盖子轮廓不丢)
            if (p.kind === 'cap') {
                put(cv, p.x, p.y, 'cap', p.rho > 0.72 ? (p.b > 0 ? 5 : 4) : p.b > 0.2 ? 4 : 3, true);
            } else {
                const v = metalLight(p.n, { amb: 0.15, dif: 0.55, env: 0.25, spec: 0.6, shin: 10 });
                let s = quant(v, [0.36, 0.55, 0.78, 1.05]);
                const ang = Math.asin(Math.max(-1, Math.min(1, p.u)));
                if (Math.floor((ang + 2) * 3.2) % 2 === 0) s -= 1; // 滚花竖纹
                put(cv, p.x, p.y, 'cap', Math.max(2, s));
            }
            continue;
        }
        if (p.t >= SHOULDER - 0.8 || p.kind !== 'side') {
            put(cv, p.x, p.y, 'glass', p.u > 0 ? 3 : 2); // 瓶颈玻璃 (药面以上的空瓶; 药装到肩部, 空玻璃带只留 1~2 行)
            continue;
        }
        // 玻璃侧边: v9 受光侧 2px 宽的浅蓝边太抢眼, 改为 1px
        // 背光侧玻璃边用 #7b9ca8 而不是 #46606b: 深色槽位上瓶子右缘不丢
        if (p.u > 0.9 || p.u < -0.86) {
            put(cv, p.x, p.y, 'glass', p.u > 0 ? 3 : 2);
            continue;
        }
        if (p.u > 0.46 && p.u < 0.62 && p.t > 9.0 && p.t < 13.6) {
            put(cv, p.x, p.y, 'glass', 4, true); // 玻璃高光条 (标签上下各露一段)
            continue;
        }
        // 瓶内药粒: 受光侧亮一档
        // 区域: 受光 / 中间 / 背光; 每区 [药粒间隙, 药片侧面, 药片正面, 石墨反光]
        const zone = p.u > 0.3 ? [3, 4, 5, 6] : p.u > -0.5 ? [2, 3, 4, 5] : [1, 2, 3, 4];
        const g = grain.get(p.y * N + p.x);
        const s = g === 1 ? zone[2] : g === 2 ? zone[3] : g === 0 ? zone[1] : zone[0];
        put(cv, p.x, p.y, 'powder', s, true);
    }
    // 标签上的火焰标记 (易燃): 实心火焰, 全图唯一的红色; 不用菱形 / 十字 (小尺寸下会读成红十字 = 医疗包)
    if (labelPx.length) {
        const cxs = Math.round(CX - 0.6);
        const ys = labelPx.filter((p) => p.x === cxs).map((p) => p.y);
        const cyl = Math.round((Math.min(...ys) + Math.max(...ys)) / 2);
        const flame = [
            '..r..',
            '.rr..',
            '.rOr.',
            'rOOYr',
            'rOYYr',
            '.rrr.',
        ];
        const map = { r: 2, O: 3, Y: 4 };
        flame.forEach((row, y) => [...row].forEach((ch, x) => {
            if (map[ch]) put(cv, cxs - 2 + x, cyl - 3 + y, 'flame', map[ch], true);
        }));
    }
    // 洒出的一小撮 (瓶前左下); v10: 顶边加亮色药片反光, 深色槽位上不再只是一块暗斑
    const pile = [
        '...ggg...',
        '.g#g#g#..',
        '#########',
        '#########',
    ];
    const ox = 1, oy = 27;
    pile.forEach((row, y) => [...row].forEach((c, x) => {
        if (c === '.') return;
        if (c === 'g') { put(cv, ox + x, oy + y, 'powder', (x + y) % 3 === 0 ? 6 : 5, true); return; }
        const g = grain.get((oy + y) * N + ox + x);
        const base = [4, 4, 4, 3][y];
        const s = g === 1 ? base + 1 : g === 2 ? 5 : g === 0 ? base : base - 1;
        put(cv, ox + x, oy + y, 'powder', Math.max(2, s), true);
    }));
    outline(cv);
    return cv;
}

export const ICONS = { primer, casing, bullet_head: bulletHead, propellant };

export function buildAll() {
    return Object.fromEntries(Object.entries(ICONS).map(([id, fn]) => [id, toImage(fn())]));
}

/** 备选: 不贴标签的发射药瓶 (只在预览里对比, 不输出为正式贴图)。 */
export function buildAlternates() {
    WITH_LABEL = false;
    const res = { propellant_nolabel: toImage(propellant()) };
    WITH_LABEL = true;
    return res;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    fs.mkdirSync(OUT, { recursive: true });
    for (const [id, img] of Object.entries(buildAll())) {
        for (let i = 3; i < img.data.length; i += 4) {
            if (img.data[i] !== 0 && img.data[i] !== 255) {
                throw new Error(`${id}: semi-transparent pixel (alpha ${img.data[i]}) would break the item's 3D extrusion`);
            }
        }
        writePng(path.join(OUT, id + '.png'), img);
        console.log('wrote', path.join(OUT, id + '.png'));
    }
}
