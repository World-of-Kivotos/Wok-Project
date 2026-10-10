// Minimal read-only ZIP/JAR reader (stored + deflate entries), Node built-ins only.
//   const z = openZip('mod.jar'); z.names(); z.has(name); z.read(name) -> Buffer; z.text(name) -> string
import fs from 'node:fs';
import zlib from 'node:zlib';

export function openZip(file) {
  const buf = fs.readFileSync(file);
  // End of central directory: scan back over the (<= 64 KiB) comment
  let eocd = -1;
  for (let i = buf.length - 22; i >= Math.max(0, buf.length - 22 - 65535); i--) if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
  if (eocd < 0) throw new Error('not a zip: ' + file);
  const count = buf.readUInt16LE(eocd + 10), cdOff = buf.readUInt32LE(eocd + 16);
  const entries = new Map();
  let p = cdOff;
  for (let n = 0; n < count; n++) {
    if (buf.readUInt32LE(p) !== 0x02014b50) throw new Error('bad central directory in ' + file);
    const method = buf.readUInt16LE(p + 10), csize = buf.readUInt32LE(p + 20), usize = buf.readUInt32LE(p + 24);
    const nlen = buf.readUInt16LE(p + 28), xlen = buf.readUInt16LE(p + 30), clen = buf.readUInt16LE(p + 32), lho = buf.readUInt32LE(p + 42);
    const name = buf.toString('utf8', p + 46, p + 46 + nlen);
    entries.set(name, { name, method, csize, usize, lho });
    p += 46 + nlen + xlen + clen;
  }
  const read = name => {
    const e = entries.get(name);
    if (!e) return null;
    const l = e.lho;
    if (buf.readUInt32LE(l) !== 0x04034b50) throw new Error('bad local header for ' + name);
    const start = l + 30 + buf.readUInt16LE(l + 26) + buf.readUInt16LE(l + 28), data = buf.subarray(start, start + e.csize);
    if (e.method === 0) return Buffer.from(data);
    if (e.method === 8) return zlib.inflateRawSync(data);
    throw new Error(`unsupported zip method ${e.method} for ${name}`);
  };
  return {
    file,
    names: () => [...entries.keys()],
    has: name => entries.has(name),
    read,
    text: name => { const b = read(name); return b == null ? null : b.toString('utf8').replace(/^﻿/, ''); },
  };
}
