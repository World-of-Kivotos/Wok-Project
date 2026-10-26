// PNG encode/decode (RGBA8) + tiny pixel-canvas helpers. Node built-ins only (node:zlib, node:fs).
//
//   import { decodePNG, encodePNG, makeCanvas, setPx, getPx, fillRect, outline, write, read } from './lib/png.mjs';
//   const c = makeCanvas(16, 16);
//   fillRect(c, 0, 0, 16, 16, '#6b6f73'); outline(c, 0, 0, 16, 16, '#3a3d40'); setPx(c, 3, 4, [255, 200, 40, 255]);
//   write(c, 'textures/block/fryer_side.png');
//
// decodePNG accepts every non-exotic PNG Minecraft packs ship: colour types 0 (gray), 2 (RGB), 3 (palette),
// 4 (gray+alpha), 6 (RGBA); bit depths 1/2/4/8/16; tRNS; Adam7 interlacing. Output is always RGBA8.
import zlib from 'node:zlib';
import fs from 'node:fs';
import path from 'node:path';

const SIG = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);

const CRC_TABLE = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; t[n] = c >>> 0; }
  return t;
})();
function crc32(buf) { let c = 0xffffffff; for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; }

function chunk(type, data) {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
  const td = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(td));
  return Buffer.concat([len, td, crc]);
}

function paeth(a, b, c) { const p = a + b - c, pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c); return pa <= pb && pa <= pc ? a : pb <= pc ? b : c; }

/** Encode RGBA8 pixels (Uint8Array/Buffer of w*h*4) to a PNG Buffer (colour type 6, 8 bit, non-interlaced). */
export function encodePNG(width, height, rgba) {
  if (rgba.length !== width * height * 4) throw new Error(`encodePNG: expected ${width * height * 4} bytes, got ${rgba.length}`);
  const stride = width * 4, raw = Buffer.alloc((stride + 1) * height), cand = Buffer.alloc(stride);
  for (let y = 0; y < height; y++) {
    // pick the filter with the smallest sum of absolute residuals (standard heuristic)
    let best = 0, bestSum = Infinity, bestBuf = null;
    for (let f = 0; f < 5; f++) {
      let sum = 0;
      for (let i = 0; i < stride; i++) {
        const x = rgba[y * stride + i], a = i >= 4 ? rgba[y * stride + i - 4] : 0, b = y ? rgba[(y - 1) * stride + i] : 0, c = y && i >= 4 ? rgba[(y - 1) * stride + i - 4] : 0;
        const v = (f === 0 ? x : f === 1 ? x - a : f === 2 ? x - b : f === 3 ? x - ((a + b) >> 1) : x - paeth(a, b, c)) & 0xff;
        cand[i] = v; sum += v < 128 ? v : 256 - v;
      }
      if (sum < bestSum) { bestSum = sum; best = f; bestBuf = Buffer.from(cand); }
    }
    raw[y * (stride + 1)] = best; bestBuf.copy(raw, y * (stride + 1) + 1);
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0); ihdr.writeUInt32BE(height, 4); ihdr[8] = 8; ihdr[9] = 6; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
  return Buffer.concat([SIG, chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw, { level: 9 })), chunk('IEND', Buffer.alloc(0))]);
}

/** Decode a PNG Buffer to { width, height, data: Uint8Array RGBA8, info: { colorType, bitDepth, interlace, palette } }. */
export function decodePNG(buf) {
  buf = Buffer.isBuffer(buf) ? buf : Buffer.from(buf);
  if (buf.length < 8 || !buf.subarray(0, 8).equals(SIG)) throw new Error('decodePNG: not a PNG');
  let off = 8, w = 0, h = 0, depth = 0, ct = 0, interlace = 0, palette = null, trns = null;
  const idat = [];
  while (off + 8 <= buf.length) {
    const len = buf.readUInt32BE(off), type = buf.toString('ascii', off + 4, off + 8), data = buf.subarray(off + 8, off + 8 + len);
    off += 12 + len;
    if (type === 'IHDR') { w = data.readUInt32BE(0); h = data.readUInt32BE(4); depth = data[8]; ct = data[9]; interlace = data[12]; }
    else if (type === 'PLTE') palette = data;
    else if (type === 'tRNS') trns = data;
    else if (type === 'IDAT') idat.push(data);
    else if (type === 'IEND') break;
  }
  if (!w || !h) throw new Error('decodePNG: missing IHDR');
  const channels = { 0: 1, 2: 3, 3: 1, 4: 2, 6: 4 }[ct];
  if (!channels) throw new Error('decodePNG: unsupported colour type ' + ct);
  const bitsPP = channels * depth, bpp = Math.max(1, bitsPP >> 3);
  const inflated = zlib.inflateSync(Buffer.concat(idat));
  const out = new Uint8Array(w * h * 4);
  const max = (1 << depth) - 1;

  // unfilter one (sub)image of pw x ph starting at inflated[pos]; returns [rows, nextPos]
  function unfilter(pw, ph, pos) {
    const stride = Math.ceil(pw * bitsPP / 8), rows = [];
    let prev = new Uint8Array(stride);
    for (let y = 0; y < ph; y++) {
      const f = inflated[pos], line = new Uint8Array(inflated.subarray(pos + 1, pos + 1 + stride)); pos += 1 + stride;
      for (let i = 0; i < stride; i++) {
        const a = i >= bpp ? line[i - bpp] : 0, b = prev[i], c = i >= bpp ? prev[i - bpp] : 0;
        if (f === 1) line[i] = (line[i] + a) & 0xff; else if (f === 2) line[i] = (line[i] + b) & 0xff;
        else if (f === 3) line[i] = (line[i] + ((a + b) >> 1)) & 0xff; else if (f === 4) line[i] = (line[i] + paeth(a, b, c)) & 0xff;
        else if (f !== 0) throw new Error('decodePNG: bad filter ' + f);
      }
      rows.push(line); prev = line;
    }
    return [rows, pos];
  }
  function sample(line, x, ch) {             // ch-th channel of pixel x, scaled to 0..255
    if (depth === 8) return line[x * channels + ch];
    if (depth === 16) { const i = (x * channels + ch) * 2; return line[i]; }   // high byte
    const bit = (x * channels + ch) * depth, v = (line[bit >> 3] >> (8 - depth - (bit & 7))) & max;
    return ct === 3 ? v : Math.round(v * 255 / max);
  }
  function raw16(line, x, ch) { const i = (x * channels + ch) * 2; return (line[i] << 8) | line[i + 1]; }
  function put(line, x, ox, oy) {
    const o = (oy * w + ox) * 4;
    if (ct === 3) {
      const idx = sample(line, x, 0);
      out[o] = palette ? palette[idx * 3] : 0; out[o + 1] = palette ? palette[idx * 3 + 1] : 0; out[o + 2] = palette ? palette[idx * 3 + 2] : 0;
      out[o + 3] = trns && idx < trns.length ? trns[idx] : 255;
    } else if (ct === 0 || ct === 4) {
      const g = sample(line, x, 0); out[o] = out[o + 1] = out[o + 2] = g;
      out[o + 3] = ct === 4 ? sample(line, x, 1) : 255;
      if (ct === 0 && trns) { const key = trns.readUInt16BE(0), v = depth === 16 ? raw16(line, x, 0) : (depth === 8 ? line[x] : (sample(line, x, 0) * max / 255) | 0); if (v === key) out[o + 3] = 0; }
    } else {
      out[o] = sample(line, x, 0); out[o + 1] = sample(line, x, 1); out[o + 2] = sample(line, x, 2);
      out[o + 3] = ct === 6 ? sample(line, x, 3) : 255;
      if (ct === 2 && trns) {
        const k = [0, 1, 2].map(i => trns.readUInt16BE(i * 2)), v = [0, 1, 2].map(i => depth === 16 ? raw16(line, x, i) : line[x * 3 + i]);
        if (v[0] === k[0] && v[1] === k[1] && v[2] === k[2]) out[o + 3] = 0;
      }
    }
  }
  if (!interlace) {
    const [rows] = unfilter(w, h, 0);
    for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) put(rows[y], x, x, y);
  } else {
    const passes = [[0, 0, 8, 8], [4, 0, 8, 8], [0, 4, 4, 8], [2, 0, 4, 4], [0, 2, 2, 4], [1, 0, 2, 2], [0, 1, 1, 2]];
    let pos = 0;
    for (const [x0, y0, dx, dy] of passes) {
      const pw = Math.ceil((w - x0) / dx), ph = Math.ceil((h - y0) / dy);
      if (pw <= 0 || ph <= 0) continue;
      const [rows, next] = unfilter(pw, ph, pos); pos = next;
      for (let y = 0; y < ph; y++) for (let x = 0; x < pw; x++) put(rows[y], x, x0 + x * dx, y0 + y * dy);
    }
  }
  return { width: w, height: h, data: out, info: { colorType: ct, bitDepth: depth, interlace, palette: !!palette } };
}

// ---------------------------------------------------------------- canvas helpers
/** Parse '#rgb', '#rrggbb', '#rrggbbaa', [r,g,b], [r,g,b,a] or 0xRRGGBB to [r,g,b,a]. */
export function rgba(col) {
  if (Array.isArray(col)) return [col[0] | 0, col[1] | 0, col[2] | 0, col.length > 3 ? col[3] | 0 : 255];
  if (typeof col === 'number') return [(col >> 16) & 255, (col >> 8) & 255, col & 255, 255];
  let s = String(col).replace(/^#/, '');
  if (s.length === 3 || s.length === 4) s = [...s].map(c => c + c).join('');
  const n = parseInt(s, 16);
  return s.length === 8 ? [(n >>> 24) & 255, (n >>> 16) & 255, (n >>> 8) & 255, n & 255] : [(n >> 16) & 255, (n >> 8) & 255, n & 255, 255];
}
export const hex = ([r, g, b, a = 255]) => '#' + [r, g, b].map(v => v.toString(16).padStart(2, '0')).join('') + (a < 255 ? a.toString(16).padStart(2, '0') : '');

/** A w x h RGBA8 pixel canvas, initially fully transparent. */
export function makeCanvas(w, h, fill) { const c = { width: w, height: h, data: new Uint8Array(w * h * 4) }; if (fill != null) fillRect(c, 0, 0, w, h, fill); return c; }
export function setPx(c, x, y, col) {
  if (x < 0 || y < 0 || x >= c.width || y >= c.height) return;
  const [r, g, b, a] = rgba(col), o = (y * c.width + x) * 4;
  c.data[o] = r; c.data[o + 1] = g; c.data[o + 2] = b; c.data[o + 3] = a;
}
export function getPx(c, x, y) { const o = (y * c.width + x) * 4; return [c.data[o], c.data[o + 1], c.data[o + 2], c.data[o + 3]]; }
export function fillRect(c, x, y, w, h, col) { for (let j = y; j < y + h; j++) for (let i = x; i < x + w; i++) setPx(c, i, j, col); }
/** 1px rectangle border (inside the w x h box). */
export function outline(c, x, y, w, h, col) {
  for (let i = x; i < x + w; i++) { setPx(c, i, y, col); setPx(c, i, y + h - 1, col); }
  for (let j = y; j < y + h; j++) { setPx(c, x, j, col); setPx(c, x + w - 1, j, col); }
}
/** Copy src onto dst at (dx, dy); alpha 0 pixels in src are skipped unless opts.replace. */
export function blit(dst, src, dx, dy, opts = {}) {
  for (let y = 0; y < src.height; y++) for (let x = 0; x < src.width; x++) {
    const p = getPx(src, x, y); if (p[3] || opts.replace) setPx(dst, dx + x, dy + y, p);
  }
}
/** Stack equally sized frames vertically into an animation strip (Minecraft .png + .mcmeta layout). */
export function strip(frames) {
  const w = frames[0].width, h = frames[0].height, c = makeCanvas(w, h * frames.length);
  frames.forEach((f, i) => blit(c, f, 0, i * h, { replace: true }));
  return c;
}
/** Nearest-neighbour upscale (for contact sheets / reviews). */
export function scale(c, k) {
  const o = makeCanvas(c.width * k, c.height * k);
  for (let y = 0; y < o.height; y++) for (let x = 0; x < o.width; x++) { const i = ((y / k | 0) * c.width + (x / k | 0)) * 4, j = (y * o.width + x) * 4; o.data.set(c.data.subarray(i, i + 4), j); }
  return o;
}
export function toPNG(c) { return encodePNG(c.width, c.height, c.data); }
/** Encode and write the canvas; creates parent folders. */
export function write(c, file) { fs.mkdirSync(path.dirname(path.resolve(file)), { recursive: true }); fs.writeFileSync(file, toPNG(c)); return file; }
/** Read a PNG file into a canvas object. */
export function read(file) { const d = decodePNG(fs.readFileSync(file)); return { width: d.width, height: d.height, data: d.data, info: d.info }; }
