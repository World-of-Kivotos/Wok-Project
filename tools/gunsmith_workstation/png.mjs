// 零依赖 PNG 编解码 (Node), 只覆盖本工具需要的非隔行 PNG。
import zlib from 'node:zlib';
import fs from 'node:fs';

const CRC_TABLE = (() => {
    const t = new Uint32Array(256);
    for (let n = 0; n < 256; n++) {
        let c = n;
        for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
        t[n] = c >>> 0;
    }
    return t;
})();

function crc32(buf) {
    let c = 0xffffffff;
    for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
    return (c ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
    const len = Buffer.alloc(4);
    len.writeUInt32BE(data.length);
    const td = Buffer.concat([Buffer.from(type, 'ascii'), data]);
    const crc = Buffer.alloc(4);
    crc.writeUInt32BE(crc32(td));
    return Buffer.concat([len, td, crc]);
}

/** RGBA 8 位编码。image: {width, height, data} */
export function encodePng(image) {
    const { width, height, data } = image;
    const ihdr = Buffer.alloc(13);
    ihdr.writeUInt32BE(width, 0);
    ihdr.writeUInt32BE(height, 4);
    ihdr[8] = 8;
    ihdr[9] = 6;
    const raw = Buffer.alloc((width * 4 + 1) * height);
    for (let y = 0; y < height; y++) {
        raw[y * (width * 4 + 1)] = 0;
        for (let i = 0; i < width * 4; i++) raw[y * (width * 4 + 1) + 1 + i] = data[y * width * 4 + i];
    }
    return Buffer.concat([
        Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
        chunk('IHDR', ihdr),
        chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
        chunk('IEND', Buffer.alloc(0)),
    ]);
}

export function writePng(path, image) {
    fs.writeFileSync(path, encodePng(image));
}

/** 解码为 RGBA 8 位。支持色彩类型 0/2/3/4/6, 位深 1/2/4/8/16。 */
export function decodePng(buf) {
    if (buf.readUInt32BE(0) !== 0x89504e47) throw new Error('not a png');
    let pos = 8;
    let width, height, bitDepth, colorType, interlace;
    let palette = null, trns = null;
    const idat = [];
    while (pos < buf.length) {
        const len = buf.readUInt32BE(pos);
        const type = buf.toString('ascii', pos + 4, pos + 8);
        const data = buf.subarray(pos + 8, pos + 8 + len);
        if (type === 'IHDR') {
            width = data.readUInt32BE(0);
            height = data.readUInt32BE(4);
            bitDepth = data[8];
            colorType = data[9];
            interlace = data[12];
        } else if (type === 'PLTE') palette = data;
        else if (type === 'tRNS') trns = data;
        else if (type === 'IDAT') idat.push(data);
        else if (type === 'IEND') break;
        pos += 12 + len;
    }
    if (interlace) throw new Error('interlaced png not supported');
    const channels = { 0: 1, 2: 3, 3: 1, 4: 2, 6: 4 }[colorType];
    const bpp = Math.max(1, (channels * bitDepth) >> 3);
    const stride = Math.ceil((width * channels * bitDepth) / 8);
    const raw = zlib.inflateSync(Buffer.concat(idat));
    const px = Buffer.alloc(stride * height);
    let prev = Buffer.alloc(stride);
    for (let y = 0; y < height; y++) {
        const filter = raw[y * (stride + 1)];
        const line = raw.subarray(y * (stride + 1) + 1, (y + 1) * (stride + 1));
        const out = px.subarray(y * stride, (y + 1) * stride);
        for (let i = 0; i < stride; i++) {
            const a = i >= bpp ? out[i - bpp] : 0;
            const b = prev[i];
            const c = i >= bpp ? prev[i - bpp] : 0;
            let v = line[i];
            if (filter === 1) v += a;
            else if (filter === 2) v += b;
            else if (filter === 3) v += (a + b) >> 1;
            else if (filter === 4) {
                const p = a + b - c;
                const pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
                v += pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
            }
            out[i] = v & 0xff;
        }
        prev = out;
    }
    const rgba = new Uint8ClampedArray(width * height * 4);
    const sample = (row, idx) => {
        if (bitDepth === 8) return px[row * stride + idx];
        if (bitDepth === 16) return px[row * stride + idx * 2];
        const perByte = 8 / bitDepth;
        const byte = px[row * stride + Math.floor(idx / perByte)];
        const shift = 8 - bitDepth * (1 + (idx % perByte));
        return (byte >> shift) & ((1 << bitDepth) - 1);
    };
    const scaleGray = (v) => (bitDepth < 8 ? Math.round((v * 255) / ((1 << bitDepth) - 1)) : v);
    for (let y = 0; y < height; y++) for (let x = 0; x < width; x++) {
        const o = (y * width + x) * 4;
        if (colorType === 3) {
            const i = sample(y, x);
            rgba[o] = palette[i * 3]; rgba[o + 1] = palette[i * 3 + 1]; rgba[o + 2] = palette[i * 3 + 2];
            rgba[o + 3] = trns && i < trns.length ? trns[i] : 255;
        } else if (colorType === 0 || colorType === 4) {
            const g = scaleGray(sample(y, x * channels));
            rgba[o] = rgba[o + 1] = rgba[o + 2] = g;
            rgba[o + 3] = colorType === 4 ? sample(y, x * channels + 1) : 255;
        } else {
            rgba[o] = sample(y, x * channels);
            rgba[o + 1] = sample(y, x * channels + 1);
            rgba[o + 2] = sample(y, x * channels + 2);
            rgba[o + 3] = colorType === 6 ? sample(y, x * channels + 3) : 255;
        }
    }
    return { width, height, data: rgba };
}

export function readPng(path) {
    return decodePng(fs.readFileSync(path));
}
