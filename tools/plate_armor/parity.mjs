// 独立核对：导出的网格 / 贴图和预览页画出来的是否完全一样。
//   node parity.mjs [--three <three.module.min.js 路径>] [key ...]
// 不复用 export.mjs 的任何代码：直接从 viewer.js 源码里抠出 polys / packFaces / packDesign / geometryFor / paintAtlas /
// partsFromFlat，配上真的 three.js（和预览页同一个 r160）跑一遍，再和 armor_meshes/*.json、贴图 PNG 逐个比：
//   - 每个部件的四边形：数量、顺序、4 个顶点的位置（MC 坐标，像素）、UV（换算成贴图像素）、法线，误差都要 <= 1e-3；
//     预览里整块贴图透明的面导出时允许丢掉，比之前先从预览这边剔掉；
//   - 贴图：解码 PNG，和预览 paintAtlas 新画的一张逐像素全等（透明像素按浏览器预乘规则当作 0,0,0,0）。
// 全部通过打印 PASS，有一件不过就退出码 1。
import fs from 'node:fs';
import zlib from 'node:zlib';
import { pathToFileURL, fileURLToPath } from 'node:url';

const here = new URL('./', import.meta.url);
const ASSETS = new URL('../../src/main/resources/assets/miningdim/', here);
const r = f => fs.readFileSync(new URL(f, here), 'utf8').replace(/^\uFEFF/, '');
const TOL = 1e-3;

// ---------- 参数 ----------
const argv = process.argv.slice(2);
let threePath = null;
const want = [];
for (let i = 0; i < argv.length; i++) {
  if (argv[i] === '--three') threePath = argv[++i];
  else if (/three[^\\/]*\.js$/i.test(argv[i])) threePath = argv[i];
  else want.push(argv[i]);
}
// 默认用预览页本地构建同一份 vendor/three.module.min.js（不入库，见 README），别处的副本用 --three 指定
threePath = threePath || fileURLToPath(new URL('./vendor/three.module.min.js', here));
if (!fs.existsSync(threePath)) { console.error(`找不到 ${threePath}，先按 README 放一份 three@0.160.0 到 vendor/，或用 --three <路径> 指定`); process.exit(2); }
const threeURL = pathToFileURL(threePath).href;

// ---------- 从 viewer.js 抠函数 ----------
// 按名字找顶层的 function / const 声明，括号配平取完整源码（跳过字符串和注释）
function extract(src, name) {
  const m = new RegExp(`^(function\\s+${name}\\s*\\(|const\\s+${name}\\s*=)`, 'm').exec(src);
  if (!m) throw new Error(`viewer.js 里找不到 ${name}`);
  const isFn = m[1].startsWith('function');
  let i = m.index + m[0].length, depth = isFn ? 1 : 0, seenBrace = false;   // 函数从参数列表的 '(' 后开始数
  const closeAt = ch => (ch === ')' || ch === '}' || ch === ']');
  for (; i < src.length; i++) {
    const ch = src[i];
    if (ch === '/' && src[i + 1] === '/') { i = src.indexOf('\n', i); if (i < 0) break; continue; }
    if (ch === '/' && src[i + 1] === '*') { i = src.indexOf('*/', i) + 1; continue; }
    if (ch === "'" || ch === '"' || ch === '`') { for (i++; i < src.length && src[i] !== ch; i++) if (src[i] === '\\') i++; continue; }
    if (ch === '(' || ch === '{' || ch === '[') { depth++; if (ch === '{') seenBrace = true; continue; }
    if (closeAt(ch)) {
      depth--;
      if (depth === 0 && seenBrace && ch === '}') {
        let end = i + 1;
        if (!isFn) { while (end < src.length && /[ \t]/.test(src[end])) end++; if (src[end] === ';') end++; }
        return src.slice(m.index, end);
      }
    }
  }
  throw new Error(`viewer.js 里 ${name} 的括号没配平`);
}
const viewerSrc = r('./viewer.js');
const PICK = ['polys', 'geometryFor', 'PART_OFF', 'packFaces', 'packDesign', 'paintAtlas', 'partsFromFlat'];
const viewerPart = PICK.map(n => extract(viewerSrc, n)).join('\n\n');

// paintAtlas 要 document.createElement('canvas')：给一个只有 ImageData 的假画布
globalThis.document = {
  createElement: () => {
    const cv = { width: 0, height: 0, img: null };
    cv.getContext = () => ({ createImageData: (w, h) => ({ width: w, height: h, data: new Uint8ClampedArray(w * h * 4) }), putImageData: img => { cv.img = img; } });
    return cv;
  },
};

async function loadViewer(key) {
  const src = [`import * as THREE from ${JSON.stringify(threeURL)};`, r('./designs.js'), r(`./armors/${key}.js`), viewerPart,
    `export { ${PICK.join(', ')} };`].join('\n');
  const tmp = new URL(`./.check_parity_${key}_${process.pid}.mjs`, here);
  fs.writeFileSync(tmp, src);
  try { return await import(tmp.href + '?t=' + process.hrtime.bigint()); } finally { fs.rmSync(tmp, { force: true }); }
}

// ---------- PNG 解码（RGBA / RGB 8 位，五种行过滤都支持） ----------
function decodePNG(buf) {
  if (buf.readUInt32BE(0) !== 0x89504e47) throw new Error('不是 PNG');
  let p = 8, W = 0, H = 0, ct = 0, bd = 0, il = 0;
  const idat = [];
  while (p < buf.length) {
    const len = buf.readUInt32BE(p), type = buf.toString('ascii', p + 4, p + 8), data = buf.subarray(p + 8, p + 8 + len);
    if (type === 'IHDR') { W = data.readUInt32BE(0); H = data.readUInt32BE(4); bd = data[8]; ct = data[9]; il = data[12]; }
    else if (type === 'IDAT') idat.push(data);
    else if (type === 'IEND') break;
    p += 12 + len;
  }
  if (bd !== 8 || (ct !== 6 && ct !== 2) || il) throw new Error(`不支持的 PNG：位深 ${bd}，颜色类型 ${ct}，隔行 ${il}`);
  const bpp = ct === 6 ? 4 : 3, stride = W * bpp, raw = zlib.inflateSync(Buffer.concat(idat));
  const px = Buffer.alloc(stride * H), out = new Uint8Array(W * H * 4);
  for (let y = 0; y < H; y++) {
    const f = raw[y * (stride + 1)], src = raw.subarray(y * (stride + 1) + 1, (y + 1) * (stride + 1)), row = px.subarray(y * stride, (y + 1) * stride);
    const up = y ? px.subarray((y - 1) * stride, y * stride) : null;
    for (let x = 0; x < stride; x++) {
      const a = x >= bpp ? row[x - bpp] : 0, b = up ? up[x] : 0, c = up && x >= bpp ? up[x - bpp] : 0;
      let v = src[x];
      if (f === 1) v += a; else if (f === 2) v += b; else if (f === 3) v += (a + b) >> 1;
      else if (f === 4) { const pp = a + b - c, pa = Math.abs(pp - a), pb = Math.abs(pp - b), pc = Math.abs(pp - c); v += pa <= pb && pa <= pc ? a : pb <= pc ? b : c; }
      else if (f !== 0) throw new Error('未知的行过滤 ' + f);
      row[x] = v & 255;
    }
  }
  for (let i = 0; i < W * H; i++) for (let k = 0; k < 4; k++) out[i * 4 + k] = k < bpp ? px[i * bpp + k] : 255;
  return { W, H, data: out };
}

// ---------- 核对一件 ----------
const VANILLA_POSE = { body: [0, 0, 0], right_arm: [-5, 2, 0], left_arm: [5, 2, 0] };   // 原版 HumanoidModel 的部件原点
async function checkItem(it) {
  const errs = [], name = `plate_armor_${it.id}`;
  const meshFile = new URL(`./armor_meshes/${name}.json`, ASSETS), pngFile = new URL(`./textures/models/armor/${name}_layer_1.png`, ASSETS);
  if (!fs.existsSync(meshFile)) return { errs: [`缺网格 ${name}.json`] };
  if (!fs.existsSync(pngFile)) return { errs: [`缺贴图 ${name}_layer_1.png`] };
  const mesh = JSON.parse(fs.readFileSync(meshFile, 'utf8'));
  const V = await loadViewer(it.key);

  // 预览页 variant(key, 'M') 的同一套步骤
  const d = V.ARMORS[it.key]('M'), pk = V.packDesign(d);
  const cv = V.paintAtlas(pk.boxes, pk.tw, pk.th, d.S, d.cell);
  const W = cv.width, H = cv.height, fresh = cv.img.data;
  for (let i = 0; i < W * H; i++) if (fresh[i * 4 + 3] === 0) fresh[i * 4] = fresh[i * 4 + 1] = fresh[i * 4 + 2] = 0;   // 画布预乘：透明即全 0

  if (mesh.format !== 1) errs.push(`format = ${mesh.format}`);
  if (mesh.item !== name) errs.push(`item = ${mesh.item}`);
  if (!Array.isArray(mesh.textureSize) || mesh.textureSize[0] !== W || mesh.textureSize[1] !== H) errs.push(`textureSize ${JSON.stringify(mesh.textureSize)}，预览是 ${W}x${H}`);
  const keys = Object.keys(mesh.parts || {}).sort().join(',');
  if (keys !== 'body,left_arm,right_arm') errs.push(`parts 的键是 ${keys}`);

  // 贴图逐像素
  const png = decodePNG(fs.readFileSync(pngFile));
  if (png.W !== W || png.H !== H) errs.push(`PNG 是 ${png.W}x${png.H}，预览是 ${W}x${H}`);
  else {
    let bad = 0, first = null;
    for (let i = 0; i < W * H * 4; i++) if (png.data[i] !== fresh[i]) { bad++; if (!first) { const px = i >> 2; first = `(${px % W},${(px / W) | 0}) 通道${i & 3}: PNG ${png.data[i]} / 预览 ${fresh[i]}`; } }
    if (bad) errs.push(`贴图有 ${bad} 个通道值不同，第一个在 ${first}`);
  }

  // 网格：预览按部件分组（partsFromFlat）后各自 geometryFor
  const parts = V.partsFromFlat(pk.boxes, pk.tw, pk.th, false);
  let quads = 0, dropped = 0;
  const maxErr = { pos: 0, uv: 0, n: 0 };
  for (const [part, P] of Object.entries(parts)) {
    if (!VANILLA_POSE[part]) { if (P.boxes.length) errs.push(`${part} 上有 ${P.boxes.length} 个方块，网格不收这个部件`); continue; }
    if (P.pose.some((v, k) => Math.abs(v - VANILLA_POSE[part][k]) > 1e-9)) errs.push(`预览里 ${part} 的原点是 ${P.pose}，和原版 HumanoidModel 不同`);
    const expect = [];
    if (P.boxes.length) {
      const g = V.geometryFor(P.boxes, P.tw, P.th);
      const pos = g.attributes.position.array, nor = g.attributes.normal.array, uv = g.attributes.uv.array;
      for (let f = 0; f < pos.length / 18; f++) {
        // 每个面是两个三角形 [0,1,2] [0,2,3]，取第 0/1/2/5 个顶点还原四边形；three 空间 (x,-y,-z) 换回 MC 坐标
        const vi = [0, 1, 2, 5].map(k => f * 6 + k);
        const q = {
          pos: vi.map(k => [pos[k * 3], -pos[k * 3 + 1], -pos[k * 3 + 2]]),
          uv: vi.map(k => [uv[k * 2], uv[k * 2 + 1]]),
          n: [nor[f * 18], -nor[f * 18 + 1], -nor[f * 18 + 2]],
        };
        const us = q.uv.map(t => t[0] * W), vs = q.uv.map(t => t[1] * H);
        const i0 = Math.floor(Math.min(...us) + TOL), i1 = Math.ceil(Math.max(...us) - TOL), j0 = Math.floor(Math.min(...vs) + TOL), j1 = Math.ceil(Math.max(...vs) - TOL);
        let seen = false;
        for (let j = j0; j < j1 && !seen; j++) for (let i = i0; i < i1; i++) if (fresh[(j * W + i) * 4 + 3]) { seen = true; break; }
        if (seen) expect.push(q); else dropped++;
      }
    }
    const got = mesh.parts[part] || [];
    if (got.length !== expect.length) { errs.push(`${part}: 导出 ${got.length} 个四边形，预览（去掉全透明面）是 ${expect.length}`); continue; }
    for (let f = 0; f < got.length; f++) {
      const a = got[f], e = expect[f];
      if (!Array.isArray(a) || a.length !== 23 || !a.every(Number.isFinite)) { errs.push(`${part}#${f}: 不是 23 个有限数`); break; }
      let dp = 0, du = 0, dn = 0;
      for (let k = 0; k < 4; k++) {
        for (let c = 0; c < 3; c++) dp = Math.max(dp, Math.abs(a[k * 5 + c] - e.pos[k][c]));
        du = Math.max(du, Math.abs(a[k * 5 + 3] - e.uv[k][0]) * W, Math.abs(a[k * 5 + 4] - e.uv[k][1]) * H);
      }
      for (let c = 0; c < 3; c++) dn = Math.max(dn, Math.abs(a[20 + c] - e.n[c]));
      maxErr.pos = Math.max(maxErr.pos, dp); maxErr.uv = Math.max(maxErr.uv, du); maxErr.n = Math.max(maxErr.n, dn);
      if (dp > TOL || du > TOL || dn > TOL) {
        errs.push(`${part}#${f}: 位置差 ${dp.toExponential(2)}px，UV 差 ${du.toExponential(2)} 像素，法线差 ${dn.toExponential(2)}`);
        if (errs.length > 8) break;
      }
    }
    quads += got.length;
  }
  return { errs, quads, dropped, tex: `${W}x${H}`, maxErr };
}

// ---------- 主流程 ----------
const items = JSON.parse(r('./items.json')).items;
const todo = want.length ? items.filter(it => want.includes(it.key) || want.includes(it.id)) : items;
if (want.length && todo.length !== want.length) { console.error('items.json 里没有其中一些：' + want.join(', ')); process.exit(2); }
console.log(`three: ${threePath}`);
let failed = 0;
const worst = { pos: 0, uv: 0, n: 0 };
for (const it of todo) {
  let res;
  try { res = await checkItem(it); } catch (e) { res = { errs: ['核对时出错：' + (e.stack || e).toString().split('\n').slice(0, 3).join(' | ')] }; }
  if (res.maxErr) for (const k of Object.keys(worst)) worst[k] = Math.max(worst[k], res.maxErr[k]);
  if (res.errs.length) { failed++; console.log(`FAIL ${it.key} (${it.id})\n  - ${res.errs.join('\n  - ')}`); }
  else console.log(`PASS ${it.key.padEnd(28)} ${String(res.quads).padStart(5)} 个四边形  丢掉透明面 ${String(res.dropped).padStart(3)}  贴图 ${res.tex}  像素全等`);
}
console.log(`\n${todo.length - failed}/${todo.length} PASS；最大误差：位置 ${worst.pos.toExponential(2)} px，UV ${worst.uv.toExponential(2)} 贴图像素，法线 ${worst.n.toExponential(2)}`);
process.exit(failed ? 1 : 0);
