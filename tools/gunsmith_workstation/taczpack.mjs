// TACZ 枪包只读访问 (零依赖, 仅 Node): 文件夹包与 zip 包 (zip64 + deflate, 解压用 node:zlib), 不解压到磁盘。
// 按 TACZ 1.1.8 的索引链解析一把枪: 枪 id → data/<ns>/index/guns/<path>.json → display → geo 模型 + 贴图 (+ LOD、槽位图标)。
// 与侦察脚本 (tacz-models/analyze.js) 同一套规则: 包目录下每个文件夹、每个 .zip (包根 = gunpack.meta.json 所在目录) 各是一个包,
// 按目录顺序取第一个提供者; 同一文件有多个提供者时记进 notes。
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { decodePng } from './png.mjs';
import { bakeBedrockGeo } from './raster.mjs';

/** 测试端 1.20.1-Forge_47.4.20 实例的 tacz 包目录 (只读)。 */
export const DEFAULT_PACKS = 'D:\\WOK测试\\versions\\1.20.1-Forge_47.4.20\\tacz';

// ------------------------------------------------------------ zip
const UTF8 = new TextDecoder('utf-8', { fatal: true });
let GBK = null;
try { GBK = new TextDecoder('gbk'); } catch (e) { GBK = null; }
function decodeName(buf, flags) {
    if (flags & 0x800) return buf.toString('utf8');
    try { return UTF8.decode(buf); } catch (e) { /* 不是 UTF-8: 国内包常见 GBK 文件名 */ }
    return GBK ? GBK.decode(buf) : buf.toString('latin1');
}

/** 最小的只读 zip: 中央目录 + stored/deflate, 支持 zip64 EOCD 与条目扩展字段。 */
export class ZipFile {
    constructor(file) {
        this.file = file;
        this.fd = fs.openSync(file, 'r');
        this.size = fs.fstatSync(this.fd).size;
        this.entries = new Map();
        this.readCentral();
    }

    readAt(pos, len) {
        const b = Buffer.alloc(len);
        fs.readSync(this.fd, b, 0, len, pos);
        return b;
    }

    readCentral() {
        const tailLen = Math.min(this.size, 65557);
        const tail = this.readAt(this.size - tailLen, tailLen);
        let eocd = -1;
        for (let i = tail.length - 22; i >= 0; i--) if (tail.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
        if (eocd < 0) throw new Error('zip: end of central directory not found in ' + this.file);
        let count = tail.readUInt16LE(eocd + 10);
        let cdSize = tail.readUInt32LE(eocd + 12);
        let cdOff = tail.readUInt32LE(eocd + 16);
        if (cdOff === 0xffffffff || count === 0xffff || cdSize === 0xffffffff) {
            const loc = eocd - 20;
            if (loc < 0 || tail.readUInt32LE(loc) !== 0x07064b50) throw new Error('zip: zip64 locator missing in ' + this.file);
            const z = this.readAt(Number(tail.readBigUInt64LE(loc + 8)), 56);
            if (z.readUInt32LE(0) !== 0x06064b50) throw new Error('zip: bad zip64 end record in ' + this.file);
            count = Number(z.readBigUInt64LE(32));
            cdSize = Number(z.readBigUInt64LE(40));
            cdOff = Number(z.readBigUInt64LE(48));
        }
        const cd = this.readAt(cdOff, cdSize);
        let p = 0;
        for (let n = 0; n < count; n++) {
            if (p + 46 > cd.length || cd.readUInt32LE(p) !== 0x02014b50) throw new Error(`zip: bad central directory entry ${n} in ${this.file}`);
            const flags = cd.readUInt16LE(p + 8);
            const method = cd.readUInt16LE(p + 10);
            let csize = cd.readUInt32LE(p + 20);
            let usize = cd.readUInt32LE(p + 24);
            const nlen = cd.readUInt16LE(p + 28), xlen = cd.readUInt16LE(p + 30), clen = cd.readUInt16LE(p + 32);
            let lho = cd.readUInt32LE(p + 42);
            const name = decodeName(cd.subarray(p + 46, p + 46 + nlen), flags).replace(/\\/g, '/');
            for (let xp = p + 46 + nlen, xend = xp + xlen; xp + 4 <= xend;) {
                const id = cd.readUInt16LE(xp), sz = cd.readUInt16LE(xp + 2);
                if (id === 0x0001) {
                    let q = xp + 4;
                    if (usize === 0xffffffff) { usize = Number(cd.readBigUInt64LE(q)); q += 8; }
                    if (csize === 0xffffffff) { csize = Number(cd.readBigUInt64LE(q)); q += 8; }
                    if (lho === 0xffffffff) { lho = Number(cd.readBigUInt64LE(q)); q += 8; }
                }
                xp += 4 + sz;
            }
            this.entries.set(name, { flags, method, csize, usize, lho });
            p += 46 + nlen + xlen + clen;
        }
    }

    names() { return [...this.entries.keys()]; }
    has(name) { return this.entries.has(name); }

    read(name) {
        const e = this.entries.get(name);
        if (!e) throw new Error(`zip: ${name} not in ${this.file}`);
        if (e.flags & 1) throw new Error(`zip: ${name} in ${this.file} is encrypted`);
        const lh = this.readAt(e.lho, 30);
        if (lh.readUInt32LE(0) !== 0x04034b50) throw new Error(`zip: bad local header for ${name} in ${this.file}`);
        const raw = this.readAt(e.lho + 30 + lh.readUInt16LE(26) + lh.readUInt16LE(28), e.csize);
        let out;
        if (e.method === 0) out = raw;
        else if (e.method === 8) out = zlib.inflateRawSync(raw);
        else throw new Error(`zip: unsupported compression method ${e.method} for ${name} in ${this.file}`);
        if (out.length !== e.usize) throw new Error(`zip: ${name} in ${this.file} inflated to ${out.length} bytes, expected ${e.usize}`);
        return out;
    }

    close() { fs.closeSync(this.fd); }
}

// ------------------------------------------------------------ 包
/** 宽松 JSON: 先按标准解析, 不行再去掉串外注释与尾逗号 (枪包里常见)。 */
export function parseJsonLenient(buf) {
    const s = (Buffer.isBuffer(buf) ? buf.toString('utf8') : String(buf)).replace(/^\uFEFF/, '');
    try { return JSON.parse(s); } catch (e) { /* 继续宽松解析 */ }
    let out = '', i = 0, inStr = false;
    while (i < s.length) {
        const c = s[i];
        if (inStr) {
            out += c;
            if (c === '\\') { out += s[i + 1]; i += 2; continue; }
            if (c === '"') inStr = false;
            i++;
            continue;
        }
        if (c === '"') { inStr = true; out += c; i++; continue; }
        if (c === '/' && s[i + 1] === '/') { while (i < s.length && s[i] !== '\n') i++; continue; }
        if (c === '/' && s[i + 1] === '*') { i += 2; while (i < s.length && !(s[i] === '*' && s[i + 1] === '/')) i++; i += 2; continue; }
        out += c;
        i++;
    }
    return JSON.parse(out.replace(/,(\s*[}\]])/g, '$1'));
}

/** "ns:path" → {ns, path}; 没有命名空间时用 defNs。 */
export function splitId(id, defNs) {
    const i = id.indexOf(':');
    return i < 0 ? { ns: defNs || 'minecraft', path: id } : { ns: id.slice(0, i), path: id.slice(i + 1) };
}

function dirPack(name, root) {
    const files = new Map();
    (function walk(d, rel) {
        for (const e of fs.readdirSync(d, { withFileTypes: true })) {
            const r = rel ? rel + '/' + e.name : e.name;
            if (e.isDirectory()) walk(path.join(d, e.name), r);
            else files.set(r, path.join(d, e.name));
        }
    })(root, '');
    return { name, kind: 'dir', source: root, has: (p) => files.has(p), read: (p) => fs.readFileSync(files.get(p)), list: () => [...files.keys()], close() {} };
}

function zipPacks(file) {
    const z = new ZipFile(file);
    const metas = z.names().filter((n) => /(^|\/)gunpack\.meta\.json$/.test(n));
    const prefixes = metas.length ? metas.map((m) => m.replace(/gunpack\.meta\.json$/, '')) : [''];
    return prefixes.map((pre, i) => {
        const files = new Map();
        for (const n of z.names()) if (n.startsWith(pre) && !n.endsWith('/')) files.set(n.slice(pre.length), n);
        return {
            name: path.basename(file) + (pre ? '!' + pre : ''), kind: 'zip', source: file + (pre ? '!/' + pre : ''),
            has: (p) => files.has(p), read: (p) => z.read(files.get(p)), list: () => [...files.keys()],
            close: i === 0 ? () => z.close() : () => {},
        };
    });
}

/** 一个 tacz 目录下装着的全部枪包 (与游戏加载的集合相同: 每个文件夹、每个 .zip)。 */
export class TaczPacks {
    constructor(dir = DEFAULT_PACKS) {
        if (!fs.existsSync(dir)) throw new Error('tacz pack dir not found: ' + dir);
        this.dir = dir;
        this.packs = [];
        for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
            const full = path.join(dir, e.name);
            if (e.isDirectory()) this.packs.push(dirPack(e.name, full));
            else if (e.name.toLowerCase().endsWith('.zip')) this.packs.push(...zipPacks(full));
        }
        if (!this.packs.length) throw new Error('no gun packs in ' + dir);
        this.langCache = new Map();
    }

    providers(p) { return this.packs.filter((k) => k.has(p)).map((k) => k.name); }

    /** 第一个提供者的 {pack, buf}; 没有返回 null。 */
    find(p) {
        const k = this.packs.find((q) => q.has(p));
        return k ? { pack: k.name, buf: k.read(p) } : null;
    }

    /** 同上, 缺失即报错。 */
    require(p, what) {
        const f = this.find(p);
        if (!f) throw new Error(`${what}: ${p} is not in any pack under ${this.dir}`);
        return f;
    }

    /** 全部包里 assets/<任意 ns>/lang/<locale>.json 合并 (先出现的包优先)。 */
    lang(locale = 'zh_cn') {
        if (this.langCache.has(locale)) return this.langCache.get(locale);
        const out = {};
        const re = new RegExp(`^assets/[^/]+/lang/${locale}\\.json$`);
        for (const k of this.packs) for (const p of k.list()) {
            if (!re.test(p)) continue;
            let j;
            try { j = parseJsonLenient(k.read(p)); } catch (e) { continue; }   // 语言文件坏了只影响显示名
            for (const [key, v] of Object.entries(j)) if (!(key in out) && typeof v === 'string') out[key] = v;
        }
        this.langCache.set(locale, out);
        return out;
    }

    close() { for (const k of this.packs) k.close(); }
}

function assetRef(packs, id, defNs, dir, ext, what) {
    const r = splitId(id, defNs);
    const p = `assets/${r.ns}/${dir}/${r.path}.${ext}`;
    const f = packs.find(p);
    return { id, path: p, pack: f ? f.pack : null, providers: packs.providers(p), buf: f ? f.buf : null, what };
}

/**
 * 解析一把枪 (与侦察脚本相同的链): index → display → 高模 geo + 贴图, 以及 LOD 与槽位图标。
 * 高模/贴图缺失即报错 (游戏里会退回 2D 槽位图); LOD 文件缺失只记 notes (TACZ checkLod 静默置空, 改用高模)。
 * 返回 {id, name, nameKey, pack, index, display, fixedScale, model:{id,path,pack,geo}, texture:{id,path,pack,image},
 *       slot:{id,path,pack,image|null}, lod:{model:{...,geo},texture:{...,image}}|null, notes}
 * opts.lodGeo: 同时解析 LOD 的 geo 与贴图 (否则两者为 null, 只记路径)。
 */
export function resolveGun(packs, id, opts = {}) {
    const { ns, path: gp } = splitId(id, 'tacz');
    const notes = [];
    const indexPath = `data/${ns}/index/guns/${gp}.json`;
    const idx = packs.require(indexPath, 'gun index ' + id);
    const idxProviders = packs.providers(indexPath);
    if (idxProviders.length > 1) notes.push('index provided by several packs: ' + idxProviders.join(', '));
    const index = parseJsonLenient(idx.buf);
    if (!index.display) throw new Error(`gun index ${id}: no "display" in ${indexPath} (${idx.pack})`);
    const d = splitId(index.display, ns);
    const displayPath = `assets/${d.ns}/display/guns/${d.path}.json`;
    const disp = packs.require(displayPath, 'gun display ' + index.display);
    if (disp.pack !== idx.pack) notes.push('display comes from another pack: ' + disp.pack);
    const display = parseJsonLenient(disp.buf);
    if (!display.model || !display.texture) throw new Error(`gun display ${index.display}: needs "model" and "texture" (${disp.pack})`);

    const model = assetRef(packs, display.model, d.ns, 'geo_models', 'json', 'model');
    if (!model.buf) throw new Error(`gun display ${index.display}: model ${model.path} is not in any pack`);
    if (model.providers.length > 1) notes.push('model provided by several packs: ' + model.providers.join(', '));
    const texture = assetRef(packs, display.texture, d.ns, 'textures', 'png', 'texture');
    if (!texture.buf) throw new Error(`gun display ${index.display}: texture ${texture.path} is not in any pack`);
    const slot = display.slot ? assetRef(packs, display.slot, d.ns, 'textures', 'png', 'slot') : null;
    let lod = null;
    if (display.lod) {
        const lm = assetRef(packs, display.lod.model, d.ns, 'geo_models', 'json', 'lod model');
        const lt = assetRef(packs, display.lod.texture, d.ns, 'textures', 'png', 'lod texture');
        if (!lm.buf) notes.push(`LOD model ${lm.path} missing (TACZ leaves the LOD empty and uses the high-poly model)`);
        if (lm.buf && !lt.buf) notes.push(`LOD texture ${lt.path} missing`);
        lod = {
            model: { id: lm.id, path: lm.path, pack: lm.pack, geo: lm.buf && opts.lodGeo ? parseJsonLenient(lm.buf) : null },
            texture: { id: lt.id, path: lt.path, pack: lt.pack, image: lt.buf && opts.lodGeo ? decodePng(lt.buf) : null },
        };
    }
    const scale = display.transform && display.transform.scale;
    const lang = packs.lang(opts.locale || 'zh_cn');
    return {
        id, pack: idx.pack, nameKey: index.name || null, name: (index.name && lang[index.name]) || null,
        index, indexPath, displayId: index.display, displayPath, display,
        // 与 TACZ GunTransform 默认一致: 没写 fixed 时是 1.2
        fixedScale: scale && Array.isArray(scale.fixed) ? scale.fixed[0] : 1.2,
        model: { id: model.id, path: model.path, pack: model.pack, geo: parseJsonLenient(model.buf) },
        texture: { id: texture.id, path: texture.path, pack: texture.pack, image: decodePng(texture.buf) },
        slot: slot && { id: slot.id, path: slot.path, pack: slot.pack, image: slot.buf ? decodePng(slot.buf) : null },
        lod, notes,
    };
}

/**
 * 台上的那把枪: 解析 (resolveGun) + 按 TACZ 约定烘焙并按摆放规则放到枪床上 (bakeBedrockGeo: 裸枪显示 stack 下画出来的方块)。
 * 默认高模 + 高模贴图 (渲染器用 GunDisplayInstance.getGunModel() 画的同一个模型); opts.lod = true 时换成 LOD 模型 + LOD 贴图。
 * 游戏里高模只在 8 格内且每帧最多 3 台, 其余用 LOD; 这把枪没有 LOD 模型时与游戏一样退回高模 (返回的 model 字段说明实际用的哪个),
 * 游戏里 32 格外不画。
 * bed: 生成器的 GUN_BED。opts.frame = 'fixed' 时留在 TACZ FIXED 定位系。opts.gun: 已解析好的 resolveGun 结果 (须带 lodGeo), 省略则现解析。
 * 返回 bakeBedrockGeo 的结果 + gun (resolveGun 的结果) + model ('high' | 'lod')。
 */
export function bakeBenchGun(packs, id, bed, opts = {}) {
    const gun = opts.gun || resolveGun(packs, id, { lodGeo: !!opts.lod });
    const useLod = !!opts.lod && !!(gun.lod && gun.lod.model.geo);
    const geo = useLod ? gun.lod.model.geo : gun.model.geo;
    const image = useLod ? gun.lod.texture.image || gun.texture.image : gun.texture.image;
    return { gun, model: useLod ? 'lod' : 'high', ...bakeBedrockGeo(geo, image, { bed, frame: opts.frame || 'bench', tag: 'gun' }) };
}

// ------------------------------------------------------------ 仓库里的蓝图表
/**
 * GunsmithBlueprint.java 里的 21 张蓝图 (按枚举顺序): [{constant, id, platform, nameKey}]。
 * 两种构造: ("m4a1", GunsmithPlatform.AR) → tacz 命名空间; ("ccrp", "m1887_long", "<nameKey>", GunsmithPlatform.X)。
 */
export function readBlueprints(repo) {
    const file = path.join(repo, 'src', 'main', 'java', 'com', 'miningdim', 'job', 'munitions', 'gunsmith', 'GunsmithBlueprint.java');
    const src = fs.readFileSync(file, 'utf8');
    const body = src.slice(src.indexOf('enum GunsmithBlueprint'), src.indexOf(';', src.indexOf('enum GunsmithBlueprint')));
    const out = [];
    for (const m of body.matchAll(/([A-Z][A-Z0-9_]*)\(((?:\s*"[^"]*"\s*,)+)\s*GunsmithPlatform\.([A-Z_]+)\s*\)/g)) {
        const strs = [...m[2].matchAll(/"([^"]*)"/g)].map((x) => x[1]);
        if (strs.length === 1) out.push({ constant: m[1], id: 'tacz:' + strs[0], platform: m[3], nameKey: `tacz.gun.${strs[0]}.name` });
        else if (strs.length === 3) out.push({ constant: m[1], id: strs[0] + ':' + strs[1], platform: m[3], nameKey: strs[2] });
        else throw new Error(`GunsmithBlueprint.${m[1]}: unexpected constructor arguments ${m[2]}`);
    }
    if (!out.length) throw new Error('no blueprints parsed from ' + file);
    return out;
}

// ------------------------------------------------------------ 贴图
/**
 * 缩到 w×h (整数倍缩小时每块取平均; 颜色按 alpha 加权, 免得透明像素把边缘染黑)。
 * TACZ 的 uv 按 geo 的 texture_width/height 计, PNG 常是它的 2-4 倍。注意现有枪包的 PNG 并不是 geo 分辨率贴图的整数倍放大
 * (实测 21 把枪缩小后都有 50% 以上的不透明像素翻转镂空), 游戏里采样的是原图; 要不要缩用 downscaleIfLossless 判断。
 */
export function downscaleImage(img, w, h) {
    if (img.width === w && img.height === h) return img;
    if (img.width < w || img.height < h) throw new Error(`downscaleImage: ${img.width}x${img.height} is smaller than ${w}x${h}`);
    const data = new Uint8ClampedArray(w * h * 4);
    const fx = img.width / w, fy = img.height / h;
    for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
        const x0 = Math.floor(x * fx), x1 = Math.max(x0 + 1, Math.floor((x + 1) * fx));
        const y0 = Math.floor(y * fy), y1 = Math.max(y0 + 1, Math.floor((y + 1) * fy));
        let r = 0, g = 0, b = 0, a = 0, n = 0;
        for (let yy = y0; yy < y1; yy++) for (let xx = x0; xx < x1; xx++) {
            const o = (yy * img.width + xx) * 4, al = img.data[o + 3];
            r += img.data[o] * al; g += img.data[o + 1] * al; b += img.data[o + 2] * al; a += al; n++;
        }
        const o = (y * w + x) * 4;
        if (a > 0) { data[o] = r / a; data[o + 1] = g / a; data[o + 2] = b / a; }
        data[o + 3] = a / n;
    }
    return { width: w, height: h, data };
}

/**
 * 缩到 w×h, 但只在不丢信息时才缩: 原图每个像素与它所在块缩小后的像素颜色、镂空 (alpha >= 128, 与预览/entityCutout 的取舍一致) 都相同。
 * 返回 {image (缩小的或原图), downscaled, maskLoss (缩小会翻转镂空的不透明像素比例)}。
 */
export function downscaleIfLossless(img, w, h) {
    if (img.width === w && img.height === h) return { image: img, downscaled: false, maskLoss: 0 };
    if (img.width < w || img.height < h || img.width % w || img.height % h) return { image: img, downscaled: false, maskLoss: null };
    const small = downscaleImage(img, w, h);
    const fx = img.width / w, fy = img.height / h;
    let opaque = 0, flipped = 0, colorDiff = false;
    for (let y = 0; y < img.height; y++) for (let x = 0; x < img.width; x++) {
        const o = (y * img.width + x) * 4, q = (Math.floor(y / fy) * w + Math.floor(x / fx)) * 4;
        const a = img.data[o + 3] >= 128, b = small.data[q + 3] >= 128;
        if (a) opaque++;
        if (a !== b) flipped++;
        else if (a && (img.data[o] !== small.data[q] || img.data[o + 1] !== small.data[q + 1] || img.data[o + 2] !== small.data[q + 2])) colorDiff = true;
    }
    const maskLoss = opaque ? flipped / opaque : 0;
    return flipped || colorDiff ? { image: img, downscaled: false, maskLoss } : { image: small, downscaled: true, maskLoss: 0 };
}
