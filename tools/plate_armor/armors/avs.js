// ================= Crye Precision AVS plate carrier (Ranger Green) — EFT reference =================
// tarkov.dev render 544a5caa4bdc2d1a388b4568. In EFT this is an armored rig: the gear is part of the item model.
// One flat grey-olive Ranger Green with soft shading. Thick rounded shoulder pads quilted lengthwise, khaki binding on
// their front ends and tan bungee knobs at the outer corners; blue-grey CAT tourniquet (light clip band on top, vertical
// windlass rod at the bottom) over the wearer's right pad end; olive admin flap with a light grey side-release buckle in
// its middle (light webbing above, dark strap below); tall silver carabiner (yellow gate) left of the black PTT, whose
// coiled cord runs down to the radio's stub antenna; front placard with three rifle + pistol mag pouches (grey steel
// AR mags, light olive sleeves with a dark webbing strip and two shock cords, slate-grey pull loops, dangling cords);
// zipped GP pouch + taller single mag pouch (hangs lowest) on the wearer's right, radio pouch (same light pouch tone,
// ending level with the band) on the left; MOLLE cummerbund and back bag; groin protector = an inverted trapezoid a shade
// darker than the carrier (straight sides tapering from right under the band to a flat, round-cornered bottom about half
// as wide) with four rows of four raised laser-cut tiles (first row on the band right under the pouches), every row the
// same width (1x: room for three rows only).
ARMORS.avs = function (mode) {
  const K = kit(mode), { hd, mid, px, edge, molle, G, isPanel, isSide } = K;
  const B4 = hd && !mid;                       // the 4x HD style
  const B = [];
  // three fabric tones only: base, light pouch tone, dark straps / bindings (Ranger Green: a grey-green with a little
  // yellow, sampled off the lit chest and pouch faces); the groin plate is a shade darker and cooler than the carrier
  const RG = hex('#5a6850'), RGL = hex('#687558'), RGD = hex('#3b4336');
  const PAD = mul(RG, 1.08), GPC = mul(RG, 0.95), GROIN = mix(RG, hex('#4a5848'), 0.7);
  const LINING = hex('#1b1d1a'), TAN = hex('#c2bd98'), BUNGEE = hex('#1c1f1d');
  const BIND = mix(TAN, PAD, 0.3);             // khaki binding on the pad ends (a little duller than the knobs)
  const STEEL = hex('#737c86'), PSTEEL = hex('#8d97a0'), DSTEEL = hex('#43484e');
  const BUCKLE = hex('#a6a79b'), BSLOT = hex('#4d4f47');
  // Cordura: very faint low-frequency mottling + per-cell grain (strongest in 4x, nearly flat in 2x)
  const fab = (c, base, amt = 0.06) => {
    const q = snap(c.p, K.cell);
    const n = fbm(q[0] * 0.45 + 7, q[1] * 0.45 + 1, q[2] * 0.45 + 3);
    return mul(base, (mid ? 0.97 + 0.06 * n : hd ? 0.93 + 0.14 * n : 0.96 + 0.08 * n) * G(c, amt));
  };
  // MOLLE webbing is the same fabric: 2x blends it part-way into the base (t), 4x uses the kit rows as they are,
  // 1x draws light webbing rows whose slots are only a shade darker (full-black 1x slots read as dice pips)
  const webbing = (c, col, y0, rows, h0, h1, { side = false, t = 0.5 } = {}) => {
    if (hd) { const m = molle(c, col, y0, rows, h0, h1, { side, web: RGL }); return m ? (mid ? mix(col, m, t) : m) : null; }
    if (!(isPanel(c) || (side && isSide(c)))) return null;
    const h = isPanel(c) ? c.p[0] : c.p[2];
    if (h < h0 || h > h1) return null;
    for (let i = 0; i < Math.ceil(rows / 2); i++) {
      const r = y0 + i * 2;
      if (c.p[1] >= r && c.p[1] < r + 1) return Math.floor(h - h0) % 3 === 2 ? mul(col, 0.82) : mul(mix(col, RGL, 0.7), 1.1);
    }
    return null;
  };
  const seam = (v, period) => ((v % period) + period) % period < px;

  // ---------------- carrier ----------------
  const bag = c => {
    if (c.face === 'top') return mul(LINING, G(c, 0.1));                       // black inner lining at the neckline
    let col = fab(c, RG);
    if (c.face === 'front') {
      if (B4 && c.p[0] > -3.6 && c.p[0] < -3.1 && c.p[1] > 3.25 && c.p[1] < 3.75) col = mul(RGD, 1.05);   // TQ mount strap
      col = webbing(c, col, 2.25, 2, -0.5, 4.25) || col;                        // MOLLE on the wearer's left upper chest
    }
    return edge(c, col);
  };
  B.push(box('body', [-4.25, 1.25, -3.25], [4.25, 10.25, -2.5], bag, { tag: 'front plate bag' }));
  const back = c => {
    if (c.face === 'top') return mul(LINING, G(c, 0.1));
    const col = fab(c, RG);
    return edge(c, (c.face === 'back' && webbing(c, col, 2.5, 6, -3.5, 3.5, { t: 0.75 })) || col);
  };
  B.push(box('body', [-4.25, 0.75, 2.5], [4.25, 10.25, 3.25], back, { tag: 'back plate bag' }));
  const cumm = c => {
    const col = mul(fab(c, RG), 0.95);
    return edge(c, webbing(c, col, 5.5, 3, -2.5, 2.5, { side: true, t: 0.6 }) || col, { stitch: false });
  };
  // ends 0.25 above the bags' bottoms so the bottom faces never share a plane
  B.push(box('body', [-4.5, 4.75, -2.75], [4.5, 10, 2.75], cumm, { tag: 'cummerbund' }));
  B.push(box('body', hd ? [-1.25, 0.5, 3.25] : [-1.5, 0.5, 3.25], hd ? [1.25, 1.5, 3.75] : [1.5, 1.5, 3.75], c => edge(c, fab(c, RGD), { stitch: false }), { tag: 'drag handle' }));

  // ---------------- padded shoulder straps ----------------
  // the straps over the trapezius sit inside the head; what shows is (a) a thick rounded pad on top of each shoulder,
  // just outside the hat layer, and (b) the front / back risers below the chin with the khaki-bound pad ends.
  // No slab inside the head (it would poke through the jaw when the head turns), and the risers stop 0.25 into it.
  const hump = c => {
    if (c.face === 'bottom') return mul(LINING, 1.2);
    const col = fab(c, PAD, 0.07);
    if (isPanel(c)) return mul(col, 0.9);                                      // pad ends
    if (c.face !== 'top') return col;
    // top: 4x gets a quilting groove running along the pad (the middle two texels of the crown)
    const i = Math.floor(c.eu / px), q = B4 && c.fw < 1.1 && (i === 1 || i === 2);
    return mul(col, q ? 0.86 : 1.08);
  };
  const riser = front => c => {
    if (c.face === 'bottom') return mul(LINING, 1.2);
    const y = c.p[1], yb = c.box.y + c.box.h, ax = Math.abs(c.p[0]);
    // khaki binding on the pad's front end (1x: only the outer corner texel, where the knobs are)
    if (front && y >= yb - (hd ? 0.5 : 1) && (hd || ax > 3.5)) return mul(BIND, G(c, 0.06) * (c.face === 'front' ? 1 : 0.85));
    let col = fab(c, PAD, 0.07);
    if (hd && isPanel(c) && seam(ax - 2.25, mid ? 1 : 0.75)) col = mul(col, mid ? 0.88 : 0.8);   // stitching along the strap
    return edge(c, col, { stitch: false });
  };
  for (const s of [-1, 1]) {
    const X = (a, b) => (s < 0 ? [a, b] : [-b, -a]);
    const [r0, r1] = hd ? X(-4.25, -1.75) : X(-4.5, -1.5), [h0, h1] = X(-5.5, -4.5), [k0, k1] = hd ? X(-4.5, -1.75) : X(-4.5, -1.5);
    // rounded pad lying on top of the shoulder, just outside the hat layer: a stepped dome (1.5-wide base + 1-wide
    // crown, the crown 0.25 shorter at each end so no two faces share a plane); 1x: one 1x1 block
    if (hd) {
      const [b0, b1] = X(-5.75, -4.25);
      B.push(box('body', [b0, -1.25, -3.5], [b1, -0.25, 3.5], hump, { tag: 'shoulder pad hump' }));
      B.push(box('body', [h0, -1.75, -3.25], [h1, -0.75, 3.25], hump, { tag: 'shoulder pad crown' }));
    } else B.push(box('body', [h0, -1.25, -3.5], [h1, -0.25, 3.5], hump, { tag: 'shoulder pad hump' }));
    // front risers end in the khaki binding just above the TQ / PTT; they reach down to the bag so no gap shows.
    // (1x: the riser overlaps the bag top, so its inner face stops at z -2.75, inside the bag, not on the bag's inner face.)
    // The back riser is 0.25 wider than the back bag so their side faces never share a plane, and its inner face sits
    // at z 2.75, inside the bag where the two overlap, so it never shares the bag's inner plane either.
    B.push(box('body', [r0, hd ? -0.25 : -0.5, hd ? -3.75 : -3.5], [r1, hd ? 1.25 : 1.5, hd ? -2.5 : -2.75], riser(true), { tag: 'strap front' }));
    B.push(box('body', [k0, hd ? -0.25 : -0.5, 2.75], [k1, 1.5, 3.5], riser(false), { tag: 'strap back' }));
  }
  // tan bungee knobs hanging from the pads' outer front corners (two on the right strap, one on the left)
  if (hd) {
    const knob = K.solid(TAN, 0.06);
    for (const [x0, y0] of [[-4.25, 1.25], [-4.75, 0.5], [3.75, 1.25]]) {
      B.push(box('body', [x0, y0, -3.75], [x0 + 0.5, y0 + 0.5, -3.25], knob, { tag: 'bungee knob' }));
      // cords run up the riser's front; the outer knob (beside the riser) gets its cord against the riser's side
      const out = x0 < -4.5;
      if (B4) B.push(box('body', out ? [-4.5, y0 - 0.5, -3.75] : [x0 + 0.125, y0 - 0.5, -4], out ? [-4.25, y0, -3.5] : [x0 + 0.375, y0, -3.75], K.solid(BUNGEE, 0.05), { tag: 'knob cord' }));
    }
  }

  // ---------------- upper chest gear ----------------
  // CAT tourniquet over the wearer's right pad end: medium blue-grey, clearly lighter than the near-black PTT; a lighter
  // clip band on top (silver clips in 4x), a dark strap edge under it, and the windlass rod standing vertically at the bottom
  const TQB = hex('#5c636c'), TQC = hex('#7f868f');
  const tq = c => {
    const v = c.p[1] - c.box.y;
    if (c.face === 'top') return mul(TQC, 1.1);
    let col = mul(TQB, G(c, 0.06));
    if (v < (hd ? 0.75 : 1)) {
      col = mul(TQC, G(c, 0.05));                                               // clip band
      if (B4 && isPanel(c) && v >= 0.25 && v < 0.5 && Math.floor(c.eu / px) % 3 === 1) col = hex('#b4b9be');   // silver clips
    } else if (hd && v < 1) col = mul(TQB, 0.78);                               // strap edge under the clips
    if (B4 && isPanel(c) && (c.eu < px || c.eu > c.fw - px)) col = mul(col, 1.15);
    return col;
  };
  // the TQ and its rod reach back to the bag (z -3.25) so no air gap shows from the side
  B.push(box('body', hd ? [-3.75, 0.25, -4] : [-3.5, 0.5, -4], hd ? [-2.25, 3.25, -3.25] : [-2.5, 3.5, -3.25], tq, { tag: 'tourniquet' }));
  const ROD = hex('#767c85');
  if (hd) B.push(box('body', [-3.25, 2.25, -4.25], [-2.75, mid ? 3.75 : 3.5, -3.25], c => mul(ROD, c.face === 'top' ? 1.2 : c.face === 'bottom' ? 0.75 : isSide(c) ? 0.85 : G(c, 0.04)), { tag: 'TQ windlass rod' }));
  // admin flap: light grey-olive webbing above the buckle, buckle in the middle, dark strap tail below
  const WEB = mix(RGL, BUCKLE, 0.3);
  const flap = c => {
    let col = fab(c, RGL);
    if (hd && c.face === 'front') {
      if (c.p[0] > -2 && c.p[0] < -1 && c.p[1] < 2.5) col = fab(c, WEB);          // webbing running up from the buckle
      else if (c.ev > c.fh - px) col = mul(col, 0.8);                            // shadow along the lower edge
    }
    return edge(c, col, { stitch: false });
  };
  // from just under the bag top down to the placard, buckle in its middle (2x/1x: kept on their own texel grids)
  B.push(box('body', [-2.5, B4 ? 1.75 : 1.5, -3.5], [-0.5, 4.5, -3.25], flap, { tag: 'admin flap' }));
  const buckle = c => {
    if (c.face !== 'front') return mul(BUCKLE, c.face === 'top' ? 1.1 : 0.8);
    if (B4 && Math.floor(c.ev / px) === 2 && c.eu > 0.25 && c.eu < c.fw - 0.25) return BSLOT;   // slot between the two halves
    return mul(BUCKLE, c.ev < c.fh / 2 ? 1.1 : 0.9);
  };
  B.push(box('body', [-2, 2.5, -3.75], [-1, 3.5, -3.5], buckle, { tag: 'buckle' }));
  // strap tail as wide as the buckle's strap slot (2x: the buckle's two texel columns, so it sits on the flap's grid)
  if (hd) B.push(box('body', mid ? [-2, 3.5, -3.625] : [-1.875, 3.5, -3.625], mid ? [-1, 4.5, -3.375] : [-1.125, 4.5, -3.375], c => edge(c, fab(c, RGD), { stitch: false }), { tag: 'buckle strap tail' }));
  // tall carabiner (silver, yellow gate on its left bar) left of the PTT, tilted bottom-outward like the render
  const SIL = hex('#aab0b4'), SILA = hex('#b9bdc0'), GATE = hex('#d4b020');
  const carab = c => {
    if (!hd) return c.face === 'front' ? (c.ev > 1 && c.ev < 2 ? GATE : SILA) : mul(SILA, 0.85);   // silver / gate / silver
    const t = px;                                                               // frame one texel thick
    const inU = c.eu > t && c.eu < c.fw - t, inV = c.ev > t && c.ev < c.fh - t;
    if (isPanel(c)) {
      if (inU && inV) return null;                                              // the opening
      if (!inU && !inV) return null;                                            // rounded corners
      if (c.eu < t && c.ev > c.fh * 0.35 && c.ev < c.fh * 0.8) return B4 && c.ev > c.fh * 0.47 && c.ev < c.fh * 0.63 ? hex('#8f959b') : GATE;
      return mul(SIL, c.ev < c.fh / 2 ? 1.1 : 0.85);
    }
    if ((c.face === 'top' || c.face === 'bottom') && !inU) return null;
    if ((c.face === 'left' || c.face === 'right') && !inV) return null;
    return mul(SIL, 0.75);
  };
  if (hd) {
    // 2x: 3 x 6 texels (frame, 1-texel opening, frame); 4x: 1.25 x 3 px. Its top tucks under the PTT's lower-left
    // corner (the PTT sits in front of it); a short webbing tab behind the top bar holds it to the bag.
    const [a, b] = mid ? [[0.5, 1.25, -3.875], [2, 4.25, -3.625]] : [[0.625, 1.25, -3.875], [1.875, 4.25, -3.625]];
    B.push(box('body', a, b, carab, { tag: 'carabiner', rot: [0, 0, -8], pivot: [1.25, 1.25, -3.75] }));
    B.push(box('body', [1, 1.5, -3.75], [1.5, 2, -3.25], c => edge(c, fab(c, RGD), { stitch: false }), { tag: 'carabiner webbing tab' }));
  } else {
    B.push(box('body', [1, 1.5, -3.75], [2, 4.5, -3.25], carab, { tag: 'carabiner' }));
  }
  // PTT hanging just under the wearer's left pad end (resting on the bag), coiled cord down to the radio's stub antenna
  const PTTB = hex('#2e3137');
  const ptt = c => {
    if (c.face === 'front') {
      const r = B4 ? 0.25 : 0.5;
      if (hd && c.ex >= r) return B4 && (Math.floor(c.ev / 0.25) % 2) ? hex('#242427') : hex('#17171a');   // speaker grille
      if (!hd) return hex('#1d1e21');
      return mul(PTTB, c.ev < r ? 1.25 : 1);
    }
    return mul(PTTB, c.face === 'top' ? 1.3 : 0.95);
  };
  B.push(box('body', hd ? [1.75, 1.25, -4] : [2, 1.5, -4], hd ? [3.75, 2.75, -3.25] : [4, 2.5, -3.25], ptt, { tag: 'PTT' }));
  // the cord sits 0.125 in front of the antenna so their front faces never share a plane where they meet
  const coil = c => mul(hex('#2c3035'), Math.floor((c.p[1] + c.p[0]) / px) % 2 ? 0.72 : 1.18);
  if (hd) B.push(box('body', [3.25, 2.75, -3.875], [3.75, 4.75, -3.375], coil, { tag: 'coiled PTT cord', rot: [0, 0, -12], pivot: [3.5, 2.75, -3.625] }));
  // stub antenna rising out of the radio pouch; kept in front of the bag (z -3.75..-3.25) so it shares no side plane with it
  const ANT = hex('#2b2d31');
  B.push(box('body', [3.75, mid ? 4 : 4.5, -3.75], [4.25, 6, -3.25], K.plastic(ANT), { tag: 'radio antenna' }));
  if (B4) B.push(box('body', [3.875, 3.5, -3.625], [4.125, 4.5, -3.375], K.plastic(ANT), { tag: 'antenna tip' }));

  // ---------------- front placard + mag bank ----------------
  const placard = c => {
    let col = fab(c, RG);
    if (c.face === 'top') col = mul(RGL, 1.05);
    return edge(c, col);
  };
  B.push(box('body', [-3.5, 4.5, -3.5], [3.5, 10, -3.25], placard, { tag: 'mag placard' }));
  const pouch = c => {
    if (c.face === 'top') return c.ex < px ? mul(RGL, 1.1) : mul(LINING, 1.3);        // open mouth: light rim, dark inside
    let col = mul(RGL, G(c, 0.05));
    if (c.face === 'front') {
      const u = c.eu, v = c.ev;
      if (v < px) return mul(col, 1.14);                                         // elastic top binding
      if (v > c.fh - px) return mul(col, mid ? 0.94 : 0.8);                     // MOLLE strap at the bottom
      if (!hd) return v < 2 ? mix(col, BUNGEE, 0.12) : col;                      // 1x: faces stay light olive
      if (mid) {
        // light rifle sleeve (2 texels) | dark webbing strip | light pistol sleeve, crossed by one soft shock cord:
        // the vertical strip does the separating, so the face stays light instead of turning into stripes
        const i = Math.floor(u / 0.5), j = Math.floor(v / 0.5);
        if (j === 3) return mix(col, BUNGEE, 0.3);
        return mul(col, i === 2 ? 0.8 : 1.04);
      }
      // 4x: one thin shock-cord X in the upper half, two horizontal cords below, dark side seams
      const vv = v - 0.3;
      if (vv >= 0 && vv < 1.2) {
        const h = 1.2, w = c.fw;
        const d1 = Math.abs(u * h - vv * w) / Math.hypot(w, h), d2 = Math.abs((w - u) * h - vv * w) / Math.hypot(w, h);
        if (Math.min(d1, d2) < 0.1) return mix(col, BUNGEE, 0.8);
      }
      if ((v >= 1.625 && v < 1.875) || (v >= 2.25 && v < 2.5)) return mix(col, BUNGEE, 0.75);
      if (u < 0.25 || u > c.fw - 0.25) col = mix(col, BUNGEE, 0.3);            // dark webbing edges
    }
    return edge(c, col, { stitch: false });
  };
  // Magazines read as magazines, and they sit IN the pouches: every mag goes 1-2 px down into its pouch (the pouch top is
  // an open mouth: light rim, dark inside), so from the side the mag clearly comes out of the pouch instead of standing
  // on its rim. Broad side faces forward (as in the reference); the top shows the feed lips with one brass round lying
  // across them (copper tip on the wearer's left), the front has a light feed-lip edge and a dark rib, the thin sides
  // (spine) are darker.
  const MAGTOP = hex('#2b2e33'), BRASS = hex('#caa24e'), COPPER = hex('#b5703f');
  const magPaint = (base, ribs, capped = false) => c => {
    if (c.face === 'bottom') return mul(base, 0.6);
    if (c.face === 'top' && capped) return mul(MAGTOP, c.ex < px ? 1.5 : 1);             // under the feed lips
    if (c.face === 'top') {
      const n = Math.max(1, Math.round(c.fw / px)), i = Math.min(n - 1, Math.floor(c.eu / px));
      const lo = Math.floor((n - 1) / 2) - (n >= 5 ? 1 : 0), hi = Math.floor(n / 2) + (n >= 5 ? 1 : 0);
      if (i >= lo && i <= hi) return i === hi && n >= 3 ? COPPER : BRASS;                  // the top round
      return mul(MAGTOP, c.ex < px ? 1.5 : 1);                                              // feed lips / follower
    }
    let col = mul(base, G(c, 0.04));
    if (!isPanel(c)) return mul(col, 0.74);                                                 // spine
    if (c.ev < px) return mul(col, 1.25);                                                   // feed-lip edge
    if (ribs && hd) {
      const n = Math.max(1, Math.round(c.fw / px)), i = Math.floor(c.eu / px);
      if (B4 ? (i === 1 || i === n - 2) : i === Math.floor(n / 2)) col = mul(col, 0.8);  // ribs
    }
    return col;
  };
  const rifle = magPaint(STEEL, true, true), pistol = magPaint(PSTEEL, false);
  // feed lips: dark steel sides, brass round lying across the top with a copper tip on the wearer's left
  const lips = c => {
    if (c.face !== 'top') return mul(DSTEEL, isPanel(c) ? 1.15 : 0.9);
    return c.eu > c.fw - Math.max(px, 0.25) ? COPPER : BRASS;
  };
  // slate-grey retention loop: a strap lying over the rifle mag's top and running down its front to the pistol mag,
  // touching the mag everywhere (no gap to see through from the side)
  const LOOP = hex('#60676b');
  const loop = c => mul(LOOP, c.face === 'top' ? 1.15 : isSide(c) ? 0.85 : G(c, 0.04));
  // dangling pull cords: 2x = a dark-olive cord texel over a lighter pull tab; 4x = a thin black cord
  const CORD = mid ? mul(RGD, 0.95) : BUNGEE, TAB = mix(CORD, RGL, 0.35);
  const cord = mid ? c => edge(c, mul(c.p[1] >= 10.5 ? TAB : CORD, G(c, 0.05)), { stitch: false }) : K.solid(CORD, 0.05);
  for (const cx of [-2.25, 0, 2.25]) {
    // pouch: 1.5 deep so there is a front wall in front of the pistol mag; the mouth is open (see pouch())
    B.push(box('body', [cx - 1, 7, -5], [cx + 1, 10, -3.5], pouch, { tag: 'mag pouch' }));
    if (hd) {
      // body + a narrower feed-lip block on top carrying the brass round: the stepped top is what reads as "magazine"
      B.push(box('body', [cx - 0.75, 4.75, -4.25], [cx + 0.75, 9, -3.75], rifle, { tag: 'rifle mag' }));          // 2.25 px shows
      B.push(box('body', [cx - 0.5, 4.25, -4.125], [cx + 0.5, 4.75, -3.875], lips, { tag: 'rifle mag feed lips' }));
      B.push(box('body', [cx - 0.25, 6, -4.75], [cx + 0.75, 8.5, -4.25], pistol, { tag: 'pistol mag' }));         // 1 px shows
      B.push(box('body', mid ? [cx - 0.25, 10, -4.5] : [cx - 0.125, 10, -4.5], mid ? [cx + 0.25, 11, -4] : [cx + 0.125, 11.5, -4.25], cord, { tag: 'bungee cord' }));
    } else {
      // inset 0.25 from the pouch sides so no faces coincide with the pouch walls
      B.push(box('body', [cx - 0.75, 5, -4.25], [cx + 0.25, 8, -3.75], rifle, { tag: 'rifle mag' }));
      B.push(box('body', [cx - 0.25, 6, -4.75], [cx + 0.75, 8, -4.25], pistol, { tag: 'pistol mag' }));
    }
  }
  // ---------------- side pouches ----------------
  // Kept as narrow as possible and 0.25+ px in front of the sleeve so the arms only pass through them mid-swing.
  // Their fronts sit at z -4 so they never share a plane with the groin protector's front (z -3.75), and their backs
  // rest on the cummerbund front (z -2.75) instead of sharing its side planes.
  // wearer's right: single mag pouch (the taller one, reaching lower) + zipped GP pouch (outer)
  const SMP = mul(RGL, 1.04);
  const smp = c => {
    if (c.face === 'top') return c.ex < px ? mul(SMP, 1.1) : mul(LINING, 1.3);
    let col = fab(c, SMP);
    if (c.face === 'front') {
      if (c.ev < px) col = mul(col, 1.12);
      else if (c.ev >= 2 && c.ev < 2 + px) col = mul(col, 0.7);                 // front pocket flap edge
    }
    return edge(c, col);
  };
  B.push(box('body', [-4.5, hd ? 6.5 : 6, -4], [B4 ? -3.25 : -3.5, 12, -2.75], smp, { tag: 'side mag pouch' }));
  // (2x/4x: in front of the bag, z -3.75..-3.25, so its outer side never shares the bag's side plane)
  // its magazine goes 1.5 px down into the pouch (inset from the pouch sides so no faces coincide)
  B.push(box('body', hd ? [-4.25, 5, -3.75] : [-4.25, 4.5, -3.75], hd ? [B4 ? -3.5 : -3.75, 8, -3.25] : [-3.75, 7, -3.25], magPaint(DSTEEL, true), { tag: 'side pouch mag' }));
  const ZIP = hex('#262a28'), TEETH = hex('#5b605c');
  const teeth = y => ((Math.floor(y / px) % 2) ? TEETH : ZIP);
  const gp = c => {
    const b = c.box, x = c.p[0], y = c.p[1], z = c.p[2];
    // 1x: olive front, the zipper is the whole (1-texel) outer side
    if (!hd) return c.face === 'right' ? mix(fab(c, GPC), ZIP, 0.5) : edge(c, fab(c, GPC));
    // 2x: one solid dark-olive zipper column along the inner edge of the front, carried over the top
    if (mid) return ((c.face === 'front' || c.face === 'top') && x >= b.x + b.w - px) ? mix(fab(c, GPC), ZIP, 0.4) : edge(c, fab(c, GPC));
    if (c.face === 'front') {
      // the big zipper faces the viewer along the inner edge of the front face
      if (x >= b.x + b.w - 0.5) return x < b.x + b.w - 0.25 ? ZIP : teeth(y);
    } else if ((c.face === 'right' && (z < b.z + px || y < b.y + px)) || (c.face === 'top' && x < b.x + px)) {
      return c.face === 'top' ? ZIP : teeth(c.face === 'right' && z < b.z + px ? y : z);   // zipper continues round the outer face
    }
    return edge(c, fab(c, GPC));
  };
  // rests against the single mag pouch's outer face (no air gap)
  B.push(box('body', [-5.5, 6, -3.5], [-4.5, 11, -2.5], gp, { tag: 'GP pouch' }));
  if (hd) B.push(box('body', mid ? [-5, 6.5, -3.75] : [-5, 6.25, -3.75], mid ? [-4.5, 7, -3.5] : [-4.75, 7, -3.5], K.plastic(hex('#3a3d3c')), { tag: 'zip pull' }));
  // wearer's left: tall radio pouch in the same light pouch tone as the single mag pouch
  const radioPouch = c => {
    if (c.face === 'top') return c.ev > c.fh - px ? mul(SMP, 1.1) : hex('#26292b');
    let col = fab(c, SMP);
    if (c.face === 'front') {
      if (c.ev < px) col = mul(col, 1.12);
      else if (hd && c.fw >= 4 * px && Math.abs(c.eu - c.fw / 2) < px * 0.75 && c.ev > 1) col = mul(col, 0.82); // centre seam only where the face is wide enough to keep light columns beside it
    }
    return edge(c, col);
  };
  // its light front ends about level with the band's tiles, clearly above the single mag pouch on the other side
  // (which hangs lowest); under it the darker lower edge of the cummerbund shows (behind the band, so the two
  // front faces never share a plane)
  B.push(box('body', [B4 ? 3.25 : 3.5, 5.5, -4], [4.5, hd ? 10.5 : 11, -2.75], radioPouch, { tag: 'radio pouch' }));
  if (hd) B.push(box('body', [B4 ? 3.25 : 3.5, 10.5, -3.25], [4.5, 11, -2.5], c => edge(c, mul(fab(c, RG), 0.78), { stitch: false }), { tag: 'cummerbund lower edge (left)' }));

  // ---------------- groin protector ----------------
  // band under the mag pouches (flush with the placard) + an inverted-trapezoid protector. Half-widths put the tile
  // rows on the texel grid (2x: 16 texels, rows of 8; 4x: 31 texels, rows of 15).
  const GT = 11, GB = hd ? 16.5 : 16;
  const HW0 = B4 ? 3.875 : mid ? 4 : 3.5;
  const SH = hd ? 0.5 : 1;
  const bandRows = B4 ? [10.25] : [10];
  const vRows = B4 ? [11.5, 12.75, 14] : hd ? [11.5, 13, 14.5] : [12, 14];
  const slots = (c, rows) => {
    const x = c.p[0], y = c.p[1];
    for (const y0 of rows) {
      if (B4) {
        // four raised 0.75-px tiles in a dark frame, thin dark slits between them
        const RW = 1.875;
        if (y < y0 - px || y >= y0 + SH + px || Math.abs(x) >= RW + px) continue;
        const dark = mul(RGD, 0.82);
        if (y < y0 || y >= y0 + SH || Math.abs(x) >= RW) return dark;
        if (((x + RW) % 1) >= 0.75) return dark;
        return mul(fab(c, RGL, 0.05), y < y0 + px ? 1.12 : 0.9);
      }
      if (mid) {
        // one texel row of four 2-texel tiles (alternating a shade so each tile reads): light raised tiles on the
        // darker plate, no shadow row (light/dark row pairs turned the whole lower front into stripes)
        if (y < y0 || y >= y0 + 0.5 || Math.abs(x) >= 2) continue;
        return mul(RGL, Math.floor(x + 2) % 2 ? 1.0 : 1.2);
      }
      // 1x: a light slot row (x -2..2) on the darker plate; the plate only has room for two rows under the band's
      if (y < y0 || y >= y0 + 1 || Math.abs(x) > 2.01) continue;
      return mul(RGL, Math.abs(Math.round(x)) === 1 ? 1.0 : 1.15);
    }
    return null;
  };
  const band = c => {
    let col = mid || !hd ? mul(RG, G(c, 0.04)) : fab(c, RG);
    if (c.face === 'front') col = slots(c, bandRows) || col;
    return edge(c, col, { stitch: false });
  };
  B.push(box('body', [-HW0, 10, -3.5], [HW0, 11, -3.25], band, { tag: 'groin band' }));
  // inverted trapezoid measured off both renders: the sides run straight in from right under the band to a flat bottom
  // about 45% of the top width; small rounded corners
  const R = mid ? 0.75 : 0.5;
  const hwS = y => HW0 * (1 - 0.55 * clamp01((y - GT) / (GB - GT)));
  const hwV = hd
    ? y => { const d = y - (GB - R); return d <= 0 ? hwS(y) : hwS(y) - R + Math.sqrt(Math.max(0, R * R - d * d)); }
    : y => [3.5, 3.5, 2.5, 2.5, 1.5][Math.min(4, Math.max(0, Math.floor(y - 11)))];            // 7,7,5,5,3 px
  const vp = c => {
    const x = c.p[0], y = c.p[1], hw = hwV(c.face === 'bottom' ? GB - px / 2 : y);   // bottom face follows the last texel row
    if (isPanel(c) || c.face === 'bottom') { if (Math.abs(x) > hw + 1e-6) return null; }
    else if (c.face !== 'top' && hw < HW0 - px / 2 - 1e-6) return null;          // side faces only where the front is full width
    const shade = 1 - 0.06 * clamp01((y - GT) / 5.5);
    let col = mid || !hd ? mul(GROIN, G(c, 0.04) * shade) : mul(fab(c, GROIN), shade);
    if (c.face === 'front') col = slots(c, vRows) || col;
    if (hd && isPanel(c) && (Math.abs(x) > hw - px || y > GB - px)) col = mul(col, mid ? 0.84 : 0.8);   // soft edge binding
    return col;
  };
  B.push(box('body', [-HW0, GT, -3.75], [HW0, GB, -3.25], vp, { tag: 'groin protector' }));
  return { S: K.S, px: K.px, cell: K.cell, boxes: B };
};
