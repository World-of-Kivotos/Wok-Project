// generate_input_icons.mjs 产物的量化自检 (只读): alpha、覆盖率、军火台深色槽位对比、剪影 IoU、平均色差。
//   node tools/munitions_icons/check_input_icons.mjs [iconDir]   (默认仓库 textures/item)
// 与原版图标的剪影/色差对比需要原版客户端资源 jar: 用环境变量 MC_CLIENT_EXTRA_JAR 指定, 默认找 ForgeGradle 缓存; 找不到就跳过这部分。
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { fileURLToPath } from 'node:url';
import { readPng, decodePng } from '../gunsmith_workstation/png.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const DIR = path.resolve(process.argv[2]
    || path.join(HERE, '..', '..', 'src', 'main', 'resources', 'assets', 'miningdim', 'textures', 'item'));
const IDS = ['primer', 'casing', 'bullet_head', 'propellant'];
const JAR = process.env.MC_CLIENT_EXTRA_JAR
    || 'D:/GradleCache/caches/forge_gradle/minecraft_repo/versions/1.20.1/client-extra.jar';

function vanilla(ids) {
    if (!fs.existsSync(JAR)) {
        return null;
    }
    const buf = fs.readFileSync(JAR);
    let e = buf.length - 22;
    while (buf.readUInt32LE(e) !== 0x06054b50) e--;
    const count = buf.readUInt16LE(e + 10);
    let p = buf.readUInt32LE(e + 16);
    const want = new Map(ids.map((i) => [`assets/minecraft/textures/item/${i}.png`, i]));
    const out = {};
    for (let n = 0; n < count; n++) {
        const method = buf.readUInt16LE(p + 10), cs = buf.readUInt32LE(p + 20);
        const fl = buf.readUInt16LE(p + 28), xl = buf.readUInt16LE(p + 30), cl = buf.readUInt16LE(p + 32);
        const off = buf.readUInt32LE(p + 42), name = buf.toString('utf8', p + 46, p + 46 + fl);
        if (want.has(name)) {
            const s = off + 30 + buf.readUInt16LE(off + 26) + buf.readUInt16LE(off + 28);
            const d = buf.subarray(s, s + cs);
            out[want.get(name)] = decodePng(method === 8 ? zlib.inflateRawSync(d) : d);
        }
        p += 46 + fl + xl + cl;
    }
    return out;
}

const toLin = (v) => { v /= 255; return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); };
const Lstar = (r, g, b) => { const Y = 0.2126 * toLin(r) + 0.7152 * toLin(g) + 0.0722 * toLin(b); return Y > 0.008856 ? 116 * Math.cbrt(Y) - 16 : 903.3 * Y; };
function meanLab(t) {
    let r = 0, g = 0, b = 0, n = 0;
    for (let i = 0; i < t.data.length; i += 4) if (t.data[i + 3]) { r += toLin(t.data[i]); g += toLin(t.data[i + 1]); b += toLin(t.data[i + 2]); n++; }
    r /= n; g /= n; b /= n;
    const X = 0.4124 * r + 0.3576 * g + 0.1805 * b, Y = 0.2126 * r + 0.7152 * g + 0.0722 * b, Z = 0.0193 * r + 0.1192 * g + 0.9505 * b;
    const f = (q) => (q > 0.008856 ? Math.cbrt(q) : 7.787 * q + 16 / 116);
    return [116 * f(Y) - 16, 500 * (f(X / 0.9505) - f(Y)), 200 * (f(Y) - f(Z / 1.089))];
}
/** 把贴图最近邻采样成 32×32 的布尔剪影 */
function mask(t) {
    const m = new Uint8Array(32 * 32);
    for (let y = 0; y < 32; y++) for (let x = 0; x < 32; x++) {
        const tx = Math.floor(((x + 0.5) * t.width) / 32), ty = Math.floor(((y + 0.5) * t.height) / 32);
        m[y * 32 + x] = t.data[(ty * t.width + tx) * 4 + 3] > 25 ? 1 : 0;
    }
    return m;
}
const iou = (a, b) => { let i = 0, u = 0; for (let k = 0; k < a.length; k++) { i += a[k] & b[k]; u += a[k] | b[k]; } return i / u; };

const T = Object.fromEntries(IDS.map((id) => [id, readPng(path.join(DIR, id + '.png'))]));
const Ldark = Lstar(0x29, 0x2a, 0x35);
for (const id of IDS) {
    const t = T[id], w = t.width, h = t.height;
    const a = (x, y) => (x < 0 || y < 0 || x >= w || y >= h ? 0 : t.data[(y * w + x) * 4 + 3]);
    let semi = 0, opaque = 0, minx = w, miny = h, maxx = -1, maxy = -1, ring = 0, low = 0;
    const lowCols = new Map();
    for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
        const al = a(x, y);
        if (al && al < 255) semi++;
        if (!al) continue;
        opaque++;
        minx = Math.min(minx, x); maxx = Math.max(maxx, x); miny = Math.min(miny, y); maxy = Math.max(maxy, y);
        let dist = 99;
        for (let dy = -3; dy <= 3; dy++) for (let dx = -3; dx <= 3; dx++) if (!a(x + dx, y + dy)) dist = Math.min(dist, Math.max(Math.abs(dx), Math.abs(dy)));
        if (dist >= 2 && dist <= 3) {
            ring++;
            const o = (y * w + x) * 4;
            if (Math.abs(Lstar(t.data[o], t.data[o + 1], t.data[o + 2]) - Ldark) < 12) {
                low++;
                const hx = '#' + [0, 1, 2].map((c) => t.data[o + c].toString(16).padStart(2, '0')).join('');
                lowCols.set(hx, (lowCols.get(hx) || 0) + 1);
            }
        }
    }
    console.log(`${id.padEnd(11)} ${w}x${h} semi=${semi} cover=${((100 * opaque) / (w * h)).toFixed(0)}% bbox=x${minx}-${maxx} y${miny}-${maxy} (${maxx - minx + 1}x${maxy - miny + 1}) darkRingLow=${(low / ring).toFixed(2)} L*=${meanLab(t)[0].toFixed(1)}`);
    if (process.env.VERBOSE) console.log('   low ring colours:', [...lowCols].sort((p, q) => q[1] - p[1]).map(([k, v]) => `${k}x${v}`).join(' '));
}
const V = vanilla(['gunpowder', 'blaze_rod', 'beetroot_soup', 'carrot', 'iron_nugget']) || {};
const M = Object.fromEntries([...IDS.map((id) => [id, mask(T[id])]), ...Object.entries(V).map(([k, v]) => [k, mask(v)])]);
const pairs = [];
for (let i = 0; i < IDS.length; i++) for (let j = i + 1; j < IDS.length; j++) pairs.push([IDS[i], IDS[j]]);
console.log('in-set IoU:', pairs.map(([p, q]) => `${p}~${q} ${iou(M[p], M[q]).toFixed(2)}`).join(', '));
const dE = (p, q) => Math.hypot(p[0] - q[0], p[1] - q[1], p[2] - q[2]).toFixed(1);
if (V.gunpowder) {
    console.log('vs vanilla IoU:', IDS.map((id) => {
        const best = Object.keys(V).map((v) => [v, iou(M[id], M[v])]).sort((p, q) => q[1] - p[1])[0];
        return `${id}~${best[0]} ${best[1].toFixed(2)}`;
    }).join(', '));
    console.log('dE propellant~gunpowder', dE(meanLab(T.propellant), meanLab(V.gunpowder)));
} else {
    console.log('vanilla comparison skipped: client-extra jar not found (set MC_CLIENT_EXTRA_JAR)');
}
console.log('dE casing~bullet', dE(meanLab(T.casing), meanLab(T.bullet_head)));
