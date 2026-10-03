// 插板护甲导出器：把 armors/<key>.js 的折中 2×（'M'）设计烘焙成游戏里直接画的四边形网格 + 贴图。
//   node export.mjs                    -> 导出 items.json 里的全部护甲
//   node export.mjs jaypc kirasa ...   -> 只导出这几件（写 key 或物品 id 都行）
// 产物（数据约定见 README.md，Java 端按同一份约定读取）：
//   src/main/resources/assets/miningdim/armor_meshes/plate_armor_<id>.json
//   src/main/resources/assets/miningdim/textures/models/armor/plate_armor_<id>_layer_1.png
// 面/顶点/UV/上色和预览页 viewer.js 的 polys()、packFaces()、geometryFor()、paintAtlas() 逐条对应；
// 改了 viewer.js 里这几段，这里要跟着改，再用 parity.mjs 对一遍。
import fs from 'node:fs';
import zlib from 'node:zlib';
import { fileURLToPath } from 'node:url';

const here = new URL('./', import.meta.url);
const ASSETS = new URL('../../src/main/resources/assets/miningdim/', here);
const MESH_DIR = new URL('./armor_meshes/', ASSETS);
const TEX_DIR = new URL('./textures/models/armor/', ASSETS);
const MODE = 'M';                          // 定稿画法：折中 2×（半像素网格、2 倍贴图）
const PARTS = ['body', 'right_arm', 'left_arm'];
const r = f => fs.readFileSync(new URL(f, here), 'utf8').replace(/^﻿/, '');

// ---------- 读设计：和 check.mjs 一样，designs.js + armors/<key>.js 拼成一个临时模块再 import ----------
async function loadDesign(key) {
  const file = new URL(`./armors/${key}.js`, here);
  if (!fs.existsSync(file)) throw new Error(`armors/${key}.js 不存在`);
  const tmp = new URL(`./.check_export_${key}_${process.pid}.mjs`, here);
  fs.writeFileSync(tmp, r('./designs.js') + '\n' + r(`./armors/${key}.js`) + '\n');
  try {
    const mod = await import(tmp.href + '?t=' + process.hrtime.bigint());
    if (typeof mod.ARMORS[key] !== 'function') throw new Error(`ARMORS.${key} 没有注册`);
    return mod.ARMORS[key](MODE);
  } finally {
    fs.rmSync(tmp, { force: true });
  }
}

// ---------- 与 viewer.js 相同的方块面（原版 ModelPart.Cube 的面顺序与顶点顺序） ----------
function polys(b) {
  const g = b.grow || 0;
  const x1 = b.x - g, y1 = b.y - g, z1 = b.z - g, x2 = b.x + b.w + g, y2 = b.y + b.h + g, z2 = b.z + b.d + g;
  const V = [[x1, y1, z1], [x2, y1, z1], [x2, y2, z1], [x1, y2, z1], [x1, y1, z2], [x2, y1, z2], [x2, y2, z2], [x1, y2, z2]];
  const { u, v, w, h, d } = b;
  const f4 = u, f5 = u + d, f6 = u + d + w, f7 = u + d + w + w, f8 = u + d + w + d, f9 = u + d + w + d + w, f10 = v, f11 = v + d, f12 = v + d + h;
  const P = (ids, u1, v1, u2, v2, face) => { const o = b.faceUV && b.faceUV[face]; if (o) [u1, v1, u2, v2] = o; return { face, verts: ids.map(i => V[i]), uv: [[u2, v1], [u1, v1], [u1, v2], [u2, v2]] }; };
  return [P([5, 4, 0, 1], f5, f10, f6, f11, 'top'), P([2, 3, 7, 6], f6, f11, f7, f10, 'bottom'), P([0, 4, 7, 3], f4, f11, f5, f12, 'right'),
    P([1, 0, 3, 2], f5, f11, f6, f12, 'front'), P([5, 1, 2, 6], f6, f11, f8, f12, 'left'), P([4, 5, 6, 7], f8, f11, f9, f12, 'back')];
}
// 每个面单独占一块从整像素起的贴图矩形（与 viewer.js packFaces 相同）
function packFaces(boxes, S) {
  const faces = [];
  for (const b of boxes) {
    const dims = { top: [b.w, b.d], bottom: [b.w, b.d], right: [b.d, b.h], left: [b.d, b.h], front: [b.w, b.h], back: [b.w, b.h] };
    b.faceUV = {};
    for (const [f, [fw, fh]] of Object.entries(dims)) faces.push({ b, f, fw, fh, pw: Math.max(1, Math.ceil(fw * S - 1e-6)), ph: Math.max(1, Math.ceil(fh * S - 1e-6)) });
  }
  faces.sort((p, q) => q.ph - p.ph || q.pw - p.pw);
  let W = 64 * S;
  for (;;) {
    let x = 0, y = 0, row = 0, ok = true;
    for (const F of faces) {
      if (F.pw > W) { ok = false; break; }
      if (x + F.pw > W) { x = 0; y += row; row = 0; }
      F.x = x; F.y = y; x += F.pw; row = Math.max(row, F.ph);
    }
    const used = y + row;
    if (ok && used <= W) {
      let H = 16 * S; while (H < used) H *= 2;
      for (const F of faces) F.b.faceUV[F.f] = [F.x / S, F.y / S, F.x / S + F.fw, F.y / S + F.fh];
      return { tw: W / S, th: H / S };
    }
    W *= 2;
  }
}

// 上色时取样点的部件偏移（只影响迷彩等花纹的取样位置，网格里不烘焙）
const PART_OFF = { body: [0, 0, 0], right_arm: [-5, 2, 0], left_arm: [5, 2, 0] };
// 面的贴图矩形（整像素，和上色循环同一套取整）
function texelRect(q, S) {
  const [u2, v1] = q.uv[0], [u1] = q.uv[1], [, v2] = q.uv[2];
  return {
    u1, v1, u2, v2,
    i0: Math.floor(Math.min(u1, u2) * S + 1e-6), i1: Math.ceil(Math.max(u1, u2) * S - 1e-6),
    j0: Math.floor(Math.min(v1, v2) * S + 1e-6), j1: Math.ceil(Math.max(v1, v2) * S - 1e-6),
  };
}
// 与 viewer.js paintAtlas 相同的逐像素上色；Uint8ClampedArray 与浏览器 ImageData 的取整一致。
// 透明像素写成 (0,0,0,0)：浏览器画布是预乘 alpha，导出的预览贴图里透明像素也是全 0。
function paintAtlas(boxes, W, H, S, cell) {
  const img = new Uint8ClampedArray(W * H * 4);
  for (const b of boxes) {
    const off = PART_OFF[b.part] || [0, 0, 0];
    for (const q of polys(b)) {
      const { u1, v1, u2, v2, i0, i1, j0, j1 } = texelRect(q, S);
      if (u1 === u2 || v1 === v2) continue;
      const V0 = q.verts[0], V1 = q.verts[1], V2 = q.verts[2];
      const du = V0.map((c, i) => c - V1[i]), dv = V2.map((c, i) => c - V1[i]);
      const fw = Math.hypot(...du), fh = Math.hypot(...dv);
      for (let j = j0; j < j1; j++) for (let i = i0; i < i1; i++) {
        const a = ((i + 0.5) / S - u1) / (u2 - u1), bb = ((j + 0.5) / S - v1) / (v2 - v1);
        if (a < 0 || a > 1 || bb < 0 || bb > 1) continue;
        let eu = a * fw, ev = bb * fh;
        if (cell) { eu = Math.min(fw, (Math.floor(eu / cell) + 0.5) * cell); ev = Math.min(fh, (Math.floor(ev / cell) + 0.5) * cell); }
        const ea = fw ? eu / fw : 0, eb = fh ? ev / fh : 0;
        const p = [0, 1, 2].map(k => V1[k] + ea * du[k] + eb * dv[k] + off[k]);
        const c = { p, face: q.face, eu, ev, fw, fh, ex: Math.min(eu, fw - eu, ev, fh - ev), px: cell || 1 / S, box: b };
        const col = b.mat(c);
        const o = (j * W + i) * 4;
        if (!col) { img[o] = img[o + 1] = img[o + 2] = img[o + 3] = 0; continue; }
        img[o] = Math.max(0, Math.min(255, col[0])); img[o + 1] = Math.max(0, Math.min(255, col[1])); img[o + 2] = Math.max(0, Math.min(255, col[2])); img[o + 3] = 255;
      }
    }
  }
  return img;
}

// ---------- 网格烘焙 ----------
// 方块旋转：MC 坐标里绕 pivot 先 X、再 Y、最后 Z（矩阵 Rz·Ry·Rx），和 THREE.Euler(rx,ry,rz,'ZYX')、原版 PartPose 一致
function rotMatrix(rot) {
  const D = Math.PI / 180;
  const [cx, cy, cz] = rot.map(a => Math.cos(a * D)), [sx, sy, sz] = rot.map(a => Math.sin(a * D));
  return [
    [cz * cy, cz * sy * sx - sz * cx, cz * sy * cx + sz * sx],
    [sz * cy, sz * sy * sx + cz * cx, sz * sy * cx - cz * sx],
    [-sy, cy * sx, cy * cx],
  ];
}
const apply = (M, v) => [0, 1, 2].map(k => M[k][0] * v[0] + M[k][1] * v[1] + M[k][2] * v[2]);
// 面朝外的轴向法线（MC 坐标，y 向下；与原版 Direction 对这几个 Polygon 的取值一致）
const FACE_NORMAL = { top: [0, -1, 0], bottom: [0, 1, 0], right: [-1, 0, 0], left: [1, 0, 0], front: [0, 0, -1], back: [0, 0, 1] };
const round = (v, k) => { const x = Math.round(v * k) / k; return x === 0 ? 0 : x; };   // 顺手把 -0 变成 0
const r4 = v => round(v, 1e4);
// UV 到 1e-8 并朝面内取整（小边向上、大边向下）：面的贴图矩形之间没有留缝，四舍五入会把恰好落在整像素上的边
// 往外推一点，边上的像素就可能采到隔壁面的颜色。贴图边长 <= 256 时整像素边 k/W 在 1e-8 下本来就精确，取整不动它；
// 不在整像素上的边只会往面里缩不到 1e-8。容差 1e-6 只吸收 v*1e8 的浮点误差，不会把精确值多推一格。
const UV_K = 1e8;
const uvIn = (v, up) => { const x = (up ? Math.ceil(v * UV_K - 1e-6) : Math.floor(v * UV_K + 1e-6)) / UV_K; return x === 0 ? 0 : x; };

function bake(design) {
  const S = design.S, cell = design.cell || 0;
  const boxes = design.boxes.map(b => ({ ...b }));
  for (const [i, b] of boxes.entries()) {
    if (!PARTS.includes(b.part)) throw new Error(`#${i} ${b.tag || ''}: part 只能是 ${PARTS.join('/')}，实际是 ${b.part}`);
    if (typeof b.mat !== 'function') throw new Error(`#${i} ${b.tag || ''}: mat 不是函数`);
  }
  const { tw, th } = packFaces(boxes, S);
  const W = tw * S, H = th * S;
  const img = paintAtlas(boxes, W, H, S, cell);
  const parts = Object.fromEntries(PARTS.map(p => [p, []]));
  const stats = { boxes: boxes.length, faces: 0, zeroArea: 0, transparent: 0 };
  for (const b of boxes) {
    const M = b.rot ? rotMatrix(b.rot) : null;
    const pv = b.pivot || [b.x + b.w / 2, b.y + b.h / 2, b.z + b.d / 2];
    for (const q of polys(b)) {
      stats.faces++;
      const verts = M ? q.verts.map(v => apply(M, [v[0] - pv[0], v[1] - pv[1], v[2] - pv[2]]).map((c, k) => c + pv[k])) : q.verts;
      // 零面积的面不出（viewer 同样跳过）
      const e1 = [0, 1, 2].map(k => verts[1][k] - verts[0][k]), e2 = [0, 1, 2].map(k => verts[2][k] - verts[0][k]);
      const cr = [e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2], e1[0] * e2[1] - e1[1] * e2[0]];
      if (cr[0] * cr[0] + cr[1] * cr[1] + cr[2] * cr[2] < 1e-12) { stats.zeroArea++; continue; }
      // 整块贴图矩形全透明的面画了也看不见，直接丢掉
      const t = texelRect(q, S);
      let seen = false;
      for (let j = t.j0; j < t.j1 && !seen; j++) for (let i = t.i0; i < t.i1; i++) if (img[(j * W + i) * 4 + 3]) { seen = true; break; }
      if (!seen) { stats.transparent++; continue; }
      const n = M ? apply(M, FACE_NORMAL[q.face]) : FACE_NORMAL[q.face];
      const nl = Math.hypot(...n);
      const quad = [];
      const ru = u => uvIn(u / tw, u === Math.min(t.u1, t.u2)), rv = v => uvIn(v / th, v === Math.min(t.v1, t.v2));
      for (let k = 0; k < 4; k++) quad.push(r4(verts[k][0]), r4(verts[k][1]), r4(verts[k][2]), ru(q.uv[k][0]), rv(q.uv[k][1]));
      quad.push(r4(n[0] / nl), r4(n[1] / nl), r4(n[2] / nl));
      parts[b.part].push(quad);
    }
  }
  // Java 端（PlateArmorMesh.parse）拒收空 body，整件会退回原版模型；在这里就报出来，不要导出一份进游戏才失效的网格
  if (!parts.body.length) throw new Error('body 部位没有任何可见的面（全透明或零面积），Java 端要求 body 非空');
  return { parts, W, H, img, stats };
}

// ---------- PNG 编码（RGBA 8 位，每行过滤字节 0） ----------
const CRC_TABLE = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; t[n] = c >>> 0; }
  return t;
})();
function crc32(buf) { let c = 0xffffffff; for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; }
function chunk(type, data) {
  const head = Buffer.alloc(8); head.writeUInt32BE(data.length, 0); head.write(type, 4, 'ascii');
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(Buffer.concat([head.subarray(4), data])), 0);
  return Buffer.concat([head, data, crc]);
}
function encodePNG(W, H, rgba) {
  const stride = W * 4, raw = Buffer.alloc((stride + 1) * H);
  for (let y = 0; y < H; y++) { raw[y * (stride + 1)] = 0; raw.set(rgba.subarray(y * stride, (y + 1) * stride), y * (stride + 1) + 1); }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(W, 0); ihdr.writeUInt32BE(H, 4); ihdr[8] = 8; ihdr[9] = 6; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
  return Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw, { level: 9 })), chunk('IEND', Buffer.alloc(0))]);
}

// 一个四边形一行，方便看 diff
function meshJSON(item, W, H, parts) {
  const body = PARTS.map(p => `  ${JSON.stringify(p)}: [${parts[p].length ? '\n' + parts[p].map(q => '   ' + JSON.stringify(q)).join(',\n') + '\n  ' : ''}]`).join(',\n');
  return `{\n "format": 1,\n "item": ${JSON.stringify(item)},\n "textureSize": [${W}, ${H}],\n "parts": {\n${body}\n }\n}\n`;
}

// ---------- 主流程 ----------
async function main() {
  const items = JSON.parse(r('./items.json')).items;
  const want = process.argv.slice(2);
  const unknown = want.filter(k => !items.some(it => it.key === k || it.id === k));
  if (unknown.length) { console.error('items.json 里没有：' + unknown.join(', ')); process.exit(2); }
  const todo = want.length ? items.filter(it => want.includes(it.key) || want.includes(it.id)) : items;
  fs.mkdirSync(MESH_DIR, { recursive: true });
  fs.mkdirSync(TEX_DIR, { recursive: true });
  const rows = [], failed = [];
  for (const it of todo) {
    const name = `plate_armor_${it.id}`;
    try {
      if (it.texture && it.texture !== `${name}_layer_1.png`) throw new Error(`items.json 的 texture 是 ${it.texture}，和 ${name}_layer_1.png 对不上`);
      const d = await loadDesign(it.key);
      if (!d || !Array.isArray(d.boxes) || !d.S) throw new Error('设计必须返回 { S, px, cell, boxes }');
      const { parts, W, H, img, stats } = bake(d);
      const json = meshJSON(name, W, H, parts);
      const png = encodePNG(W, H, img);
      fs.writeFileSync(new URL(`${name}.json`, MESH_DIR), json);
      fs.writeFileSync(new URL(`${name}_layer_1.png`, TEX_DIR), png);
      const q = PARTS.map(p => parts[p].length);
      rows.push({ key: it.key, id: it.id, boxes: stats.boxes, body: q[0], right_arm: q[1], left_arm: q[2], quads: q[0] + q[1] + q[2], dropped: stats.transparent + stats.zeroArea, texture: `${W}x${H}`, jsonKB: +(json.length / 1024).toFixed(1), pngKB: +(png.length / 1024).toFixed(1) });
    } catch (e) {
      failed.push(it.key);
      console.error(`[失败] ${it.key} (${it.id}): ${e.stack ? e.stack.split('\n').slice(0, 4).join(' | ') : e}`);
    }
  }
  if (rows.length) {
    console.table(rows);
    const sum = k => rows.reduce((s, x) => s + x[k], 0);
    console.log(`导出 ${rows.length} 件：四边形共 ${sum('quads')}（丢掉全透明/零面积面 ${sum('dropped')}），网格 ${sum('jsonKB').toFixed(0)} KB，贴图 ${sum('pngKB').toFixed(0)} KB`);
    console.log('网格 ->', fileURLToPath(MESH_DIR));
    console.log('贴图 ->', fileURLToPath(TEX_DIR));
  }
  if (failed.length) { console.error(`${failed.length} 件失败：${failed.join(', ')}`); process.exit(1); }
}

await main();
