// ===== Armor redesign v2: follows the in-game reference images (EFT item renders), GeckoLib-style cubes =====
// Geometry is MC model space (y down, front = -z, x<0 = wearer's right). Boxes may carry rot:[x,y,z] (deg) + pivot,
// which GeckoLib cubes support. Every unrotated visible surface sits >= 0.25px outside the skin outer layer.

// ---------- noise ----------
function hash3(x, y, z) {
  let h = Math.imul(x | 0, 374761393) ^ Math.imul(y | 0, 668265263) ^ Math.imul(z | 0, 1274126177);
  h = Math.imul(h ^ (h >>> 13), 1274126177);
  return ((h ^ (h >>> 16)) >>> 0) / 4294967295;
}
const sm = t => t * t * (3 - 2 * t);
const clamp01 = t => Math.max(0, Math.min(1, t));
function vnoise(x, y, z) {
  const xi = Math.floor(x), yi = Math.floor(y), zi = Math.floor(z);
  const fx = sm(x - xi), fy = sm(y - yi), fz = sm(z - zi);
  const L = (a, b, t) => a + (b - a) * t;
  const c = (i, j, k) => hash3(xi + i, yi + j, zi + k);
  return L(L(L(c(0, 0, 0), c(1, 0, 0), fx), L(c(0, 1, 0), c(1, 1, 0), fx), fy),
    L(L(c(0, 0, 1), c(1, 0, 1), fx), L(c(0, 1, 1), c(1, 1, 1), fx), fy), fz);
}
function fbm(x, y, z, o = 3) { let s = 0, a = 0.5, f = 1, n = 0; for (let i = 0; i < o; i++) { s += a * vnoise(x * f, y * f, z * f); n += a; a *= 0.5; f *= 2.03; } return s / n; }

// ---------- color ----------
export const hex = h => { if (!/^#[0-9a-fA-F]{6}$/.test(h)) throw new Error('hex() needs #rrggbb, got ' + h); return [parseInt(h.slice(1, 3), 16), parseInt(h.slice(3, 5), 16), parseInt(h.slice(5, 7), 16)]; };
const mul = (c, k) => [c[0] * k, c[1] * k, c[2] * k];
const mix = (a, b, t) => [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t];
const snap = (p, cell) => (cell ? p.map(v => (Math.floor(v / cell) + 0.5) * cell) : p);

// ---------- camo / fabrics (sampled in 3D so patterns run across faces) ----------
function layers(p, cell, base, ls) {
  const q = snap(p, cell);
  let col = hex(base);
  for (const [h, f, t, o] of ls) if (fbm(q[0] * f + o, q[1] * f * 0.9 - o, q[2] * f + o * 0.5) > t) col = hex(h);
  return col;
}
// MultiCam (Crye): khaki ground, green / olive-brown / dark-brown mid shapes, small cream highlights
const multicam = (p, cell) => layers(p, cell, '#a69c74', [['#c3b893', 0.55, 0.6, 3], ['#7a7f50', 0.62, 0.6, 17], ['#8a7050', 0.7, 0.62, 41], ['#5a5236', 0.9, 0.66, 77], ['#4a3c2d', 1.6, 0.72, 5], ['#cfc6a2', 1.8, 0.74, 91]]);
// A-TACS AU (sand / grey pebbles) and A-TACS FG (green-tan) used by the JayPC's add-on pouches
const atacsAU = (p, cell) => layers(p, cell, '#b3a888', [['#d3cab0', 0.9, 0.56, 7], ['#8e8670', 1.1, 0.6, 23], ['#6d6a58', 1.4, 0.64, 51], ['#8c8f76', 1.2, 0.66, 13], ['#57574a', 2.2, 0.72, 99]]);
const atacsFG = (p, cell) => layers(p, cell, '#9c9870', [['#bdb58f', 0.9, 0.57, 9], ['#6f7549', 1.1, 0.58, 31], ['#555c38', 1.4, 0.64, 57], ['#827356', 1.3, 0.66, 3], ['#3f4630', 2.2, 0.72, 71]]);
// Weathered bright OD of the Tac-Kek JayPC (reads yellow-green in the EFT render)
function odGreen(p, cell) {
  const q = snap(p, cell);
  const n = fbm(q[0] * 0.45 + 3, q[1] * 0.45, q[2] * 0.45 + 11);
  return mix(hex('#566b2c'), hex('#8aa24c'), sm(clamp01((n - 0.32) * 2.6)));
}

// ---------- style kit ----------
// mode: 'A' pixel (1 texel/px), 'M' middle (2 texels/px, half-pixel art cells), 'B' HD (4 texels/px)
function kit(mode) {
  const hd = mode !== 'A', mid = mode === 'M';
  const cell = mode === 'A' ? 1 : mid ? 0.5 : 0;
  const px = mode === 'A' ? 1 : mid ? 0.5 : 0.25;
  const isPanel = c => c.face === 'front' || c.face === 'back';
  const isSide = c => c.face === 'left' || c.face === 'right';
  const G = (c, amt) => (mid
    ? 1 + (hash3(Math.floor(c.p[0] * 2 + 500), Math.floor(c.p[1] * 2 + 500), Math.floor(c.p[2] * 2 + 500) + c.face.length * 97) - 0.5) * amt * 0.8
    : hd
    ? 1 + (vnoise(c.p[0] * 8, c.p[1] * 8, c.p[2] * 8) - 0.5) * amt + ((((Math.floor(c.p[0] * 4) + Math.floor(c.p[1] * 4) + Math.floor(c.p[2] * 4)) & 1) ? 0.012 : -0.012))
    : 1 + (hash3(Math.floor(c.p[0] + 500), Math.floor(c.p[1] + 500), Math.floor(c.p[2] + 500) + c.face.length * 97) - 0.5) * amt * 0.6);
  // outline / binding / stitch
  function edge(c, col, { stitch = true, thin = false } = {}) {
    const minDim = Math.min(c.fw, c.fh);
    if (!hd) {
      if (minDim <= 1.01 && !isPanel(c)) return mul(col, 0.8);
      if (c.ex < 1 && minDim > 2) return mul(col, c.ev < 1 ? 1.1 : 0.72);
      return col;
    }
    if (mid) {
      if (minDim <= 1.01 && !isPanel(c) && !thin) return mul(col, 0.82);
      if (c.ex < 0.5 && minDim > 1.01) return mul(col, c.ev < 0.5 ? 1.1 : 0.72);
      return col;
    }
    if (minDim <= 1.01 && !isPanel(c) && !thin) return mul(col, c.ex < 0.25 ? 0.72 : 0.84);
    if (c.ex < 0.25) return mul(col, c.ev < 0.25 ? 1.1 : 0.7);
    if (stitch && minDim > 1.5 && c.ex >= 0.5 && c.ex < 0.75) {
      const along = (c.ex === Math.min(c.eu, c.fw - c.eu)) ? c.ev : c.eu;
      if ((along % 0.5) < 0.25) return mul(col, 1.2);
    }
    return col;
  }
  // MOLLE rows; horizontal axis is x on front/back faces and z on side faces
  function molle(c, col, y0, rows, h0, h1, { period = 1, spacing = 1.25, web = null, side = false } = {}) {
    if (!(isPanel(c) || (side && isSide(c)))) return null;
    const h = isPanel(c) ? c.p[0] : c.p[2];
    if (h < h0 || h > h1) return null;
    const W = web ? mix(col, web, 0.7) : col;
    if (!hd) {
      const n = Math.ceil(rows / 2);
      for (let i = 0; i < n; i++) {
        const r = y0 + i * 2;
        if (c.p[1] >= r && c.p[1] < r + 1) return Math.floor(h - h0) % 3 === 2 ? mul(W, 0.5) : mul(W, 1.12);
      }
      return null;
    }
    if (mid) {
      const n = Math.ceil(rows * 2 / 3);
      for (let i = 0; i < n; i++) {
        const dy = c.p[1] - (y0 + i * 1.5);
        if (dy >= 0 && dy < 0.5) return (((h - h0) % 1.5) + 1.5) % 1.5 < 0.5 ? mul(W, 0.5) : mul(W, 1.12);
        if (dy >= 0.5 && dy < 1) return mul(col, 0.7);
      }
      return null;
    }
    for (let i = 0; i < rows; i++) {
      const dy = c.p[1] - (y0 + i * period);
      if (dy >= 0 && dy < 0.5) {
        const t = ((h - h0) % spacing + spacing) % spacing;
        if (t < 0.25) return mul(W, 0.5);
        return mul(W, dy < 0.25 ? 1.12 : 0.96);
      }
      if (dy >= 0.5 && dy < 0.75) return mul(col, 0.62);
    }
    return null;
  }
  // flat cut-out disc / ring on the front and back faces (two copies, one rotated 45deg, make an octagon)
  const ring = (rIn, rOut, col, { sides = false } = {}) => c => {
    if (!isPanel(c)) return sides ? mul(col, 0.7) : null;
    const r = Math.hypot(c.eu - c.fw / 2, c.ev - c.fh / 2);
    if (r > rOut || r < rIn) return null;
    return mul(col, (hd && !mid && r > rOut - 0.25) ? 0.8 : 1);
  };
  const solid = (col, k = 0.1) => c => edge(c, mul(col, G(c, k)), { stitch: false });
  const plastic = col => c => mul(col, c.face === 'top' ? 1.5 : (hd && c.ev < px) ? 1.3 : 1);
  return { hd, mid, cell, px, f: 1 / px, S: mode === 'B' ? 4 : 2, isPanel, isSide, G, edge, molle, ring, solid, plastic };
}

// ---------- geometry helpers ----------
const box = (part, a, b, mat, opt = {}) => ({ part, x: a[0], y: a[1], z: a[2], w: b[0] - a[0], h: b[1] - a[1], d: b[2] - a[2], mat, ...opt });
// octagonal prism around an axis: two boxes, the second turned 45deg about that axis
function octa(out, part, center, size, axis, mat, extra = {}) {
  const [cx, cy, cz] = center, [w, h, d] = size;
  const a = [cx - w / 2, cy - h / 2, cz - d / 2], b = [cx + w / 2, cy + h / 2, cz + d / 2];
  out.push(box(part, a, b, mat, extra));
  const rot = axis === 'y' ? [0, 45, 0] : axis === 'z' ? [0, 0, 45] : [45, 0, 0];
  out.push(box(part, a, b, mat, { ...extra, rot, pivot: center }));
}

// Registry: key -> (mode 'A' | 'M' | 'B') => { S, px, cell, boxes }. Every armor lives in armors/<key>.js and registers itself here.
export const ARMORS = {};

// ================= player skin (Kivotos student uniform), painted on the standard 64x64 layout =================
const SKIN = hex('#f2d2bf'), HAIR = hex('#2e2a3a'), SHIRT = hex('#e9e9ef'), BLAZER = hex('#2b3350'), TIE = hex('#3d7fd0'), SKIRT = hex('#4a5470'), SOCK = hex('#24222a');
export function playerBoxes(slim) {
  const P = [];
  const add = (part, u, v, a, s, grow, mat) => P.push({ part, u, v, x: a[0], y: a[1], z: a[2], w: s[0], h: s[1], d: s[2], grow, mat });
  const head = c => {
    if (c.face === 'top' || c.face === 'back') return HAIR;
    if (c.face === 'bottom') return SKIN;
    if (c.face === 'front') {
      if (c.ev < 2 || (c.ev < 3 && (c.eu < 1 || c.eu > 7))) return HAIR;
      if (c.ev >= 4 && c.ev < 6 && ((c.eu >= 1 && c.eu < 3) || (c.eu >= 5 && c.eu < 7))) return c.ev < 5 ? hex('#ffffff') : hex('#4c8fe0');
      if (c.ev >= 6.5 && c.ev < 7 && c.eu >= 3.5 && c.eu < 4.5) return hex('#c98f86');
      return SKIN;
    }
    return c.ev < 5 ? HAIR : SKIN;
  };
  const hat = c => (c.face === 'bottom' ? null : c.face === 'back' || (c.face !== 'front' && c.face !== 'top' && c.ev < 4) ? HAIR : null);
  const body = c => (c.face === 'front' && Math.abs(c.eu - 4) < 0.6 && c.ev < 7 ? TIE : c.ev >= 10 ? SKIRT : SHIRT);
  const jacket = c => {
    if (c.face === 'front' && c.ev < 7 && Math.abs(c.eu - 4) < 3.2 - c.ev * 0.42) return null;
    if (c.ev >= 10) return null;
    return mul(BLAZER, c.face === 'front' && Math.abs(c.eu - 4) < 0.5 && c.ev >= 7 ? 0.8 : 1);
  };
  const arm = c => (c.ev >= 10 || c.face === 'bottom' ? SKIN : SHIRT);
  const sleeve = c => (c.face === 'bottom' || c.ev >= 9 ? null : c.ev >= 8 ? mul(BLAZER, 1.35) : BLAZER);
  const leg = c => (c.ev >= 11 || c.face === 'bottom' ? hex('#3a2a24') : c.ev >= 4 ? SOCK : c.ev >= 3 ? SKIN : SKIRT);
  const pants = c => (c.face !== 'top' && c.face !== 'bottom' && c.ev < 3 ? mul(SKIRT, (Math.floor(c.eu) & 1) ? 0.85 : 1) : null);
  const aw = slim ? 3 : 4;
  add('head', 0, 0, [-4, -8, -4], [8, 8, 8], 0, head);
  add('head', 32, 0, [-4, -8, -4], [8, 8, 8], 0.5, hat);
  add('body', 16, 16, [-4, 0, -2], [8, 12, 4], 0, body);
  add('body', 16, 32, [-4, 0, -2], [8, 12, 4], 0.25, jacket);
  add('right_arm', 40, 16, [slim ? -2 : -3, -2, -2], [aw, 12, 4], 0, arm);
  add('right_arm', 40, 32, [slim ? -2 : -3, -2, -2], [aw, 12, 4], 0.25, sleeve);
  add('left_arm', 32, 48, [-1, -2, -2], [aw, 12, 4], 0, arm);
  add('left_arm', 48, 48, [-1, -2, -2], [aw, 12, 4], 0.25, sleeve);
  add('right_leg', 0, 16, [-2, 0, -2], [4, 12, 4], 0, leg);
  add('right_leg', 0, 32, [-2, 0, -2], [4, 12, 4], 0.25, pants);
  add('left_leg', 16, 48, [-2, 0, -2], [4, 12, 4], 0, leg);
  add('left_leg', 0, 48, [-2, 0, -2], [4, 12, 4], 0.25, pants);
  return P;
}