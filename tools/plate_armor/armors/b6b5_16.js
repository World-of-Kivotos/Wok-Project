// ================= 6B5-16 Zh-86 "Uley" armored rig (Khaki) — EFT reference (tarkov.dev 5c0e3eb886f7742015526062) =================
// The two Zh-86 "Uley" files (b6b5_16 khaki, b6b5_15_flora Flora) are identical except for the key and the FLORA switch:
// the two EFT items share one model and differ only in cloth. Reference render: soft vest cut long in front (a rounded
// U-shaped apron that hangs well below the pouches, past the belt), deep armholes; tall quilted stand collar, dark green
// outside (khaki) / darker quilted camo (Flora) with a light piped rim and a khaki / dark lining inside; at the throat the
// two ends cross in a V and the wearer's-LEFT end lies on top (its pipe runs unbroken down across the centre, the
// wearer's-right pipe runs down to meet it); padded shoulder rolls at the top corners (vest cloth, the collar sits inboard
// of them) whose front ends carry a light webbing adjuster strap each (a small dark gunmetal buckle on the pad, a bright
// metal tip at the end) running down beside the chest pockets; one tall narrow padded pocket high on the chest at each outer edge, just
// under the shoulder pad, with only a short lid; four closed double-mag pouches low on the belly in two pairs (flap over
// the top third, a stitched band near the bottom, teal grime at the bottom; no magazines show); side panels with
// horizontal adjuster straps under the armholes. The back is not visible in the render and is kept plain.
// Proportions measured on the render with the pouch row (= full 9 px panel width) as the scale: shoulder to hem ~14.5-14.8
// px (~3.3 px below the pouch bottoms), chest pockets ~1.6-5.4, pouch flaps from ~6.3, pouch bottoms ~11.5, side panels
// ~7-12, full width to ~12, then a ~45deg taper (about 8 / 7 / 6 / 5 / 4 px wide per half pixel) to a broad, slightly
// rounded ~3-4 px bottom. In MC the head hides most of the tall collar: only thin low walls beside / behind the head,
// the band behind the neck (its base slopes into the back panel) and a short strip under the chin (the V) show.
ARMORS.b6b5_16 = function (mode) {
  const K = kit('M');                                           // always the 2x style, whatever mode is asked for
  const { G, isPanel, isSide, px } = K;
  const FLORA = false;
  const B = [];
  // ---------- cloth ----------
  // khaki: the render's grey-green khaki (sampled between the lit chest and its mid tones), pouches a touch greener,
  // collar shell dark green; Flora (VSR-98): light sage ground with blue-green, dark green and olive-brown patches, all
  // stretched vertically
  const P = FLORA ? {
    pipe: hex('#b3bc94'), face: hex('#2f4236'), deep: hex('#1d2720'), lining: hex('#2c302d'), clin: hex('#34443a'),
    web: hex('#a3a279'), grime: hex('#2e3c30'), gr: 0.18, metal: hex('#7d8077'), metalHi: hex('#a9aca2'),
  } : {
    vest: hex('#a29e75'), pouch: hex('#a3a87f'), collar: hex('#3e5140'), quilt: hex('#4d6450'), side: hex('#8c8a64'),
    pipe: hex('#cdc69c'), face: hex('#34463a'), deep: hex('#26322a'), lining: hex('#6c6a4d'), clin: hex('#86835e'),
    web: hex('#b0b186'), grime: hex('#3d5a4c'), gr: 0.42, metal: hex('#7d8077'), metalHi: hex('#a9aca2'),
  };
  // sampled on whole pixels across (x, z) and on the art grid down (y), so every patch is at least a pixel wide (no
  // half-pixel slivers or 1-cell stripes); stretched about 3:1 vertically, two octaves only (a third one broke the
  // patches into specks). The brown class is stretched further (about 3.4:1), so it forms the render's upright streaks
  // and drips instead of square dots. The +4 / +5 offsets only pick the part of the noise field whose mix suits the vest
  // front (all four classes on the chest: sage ~36%, mid green ~34%, dark green ~15%, brown ~15%)
  const FL = { base: hex('#8c9a70'), mid: hex('#65805e'), dark: hex('#3f5945'), brown: hex('#66553f') };
  const flora = p => {
    const q = [Math.floor(p[0]) + 4.5, (Math.floor(p[1] / K.cell) + 0.5) * K.cell + 5, Math.floor(p[2]) + 0.5];
    const X = q[0] * 0.95, Y = q[1] * 0.32, Z = q[2] * 0.95;
    if (fbm(X * 1.15 + 61, Y * 1.15 + 3, Z * 1.15 + 11, 2) > 0.66) return FL.dark;
    if (fbm(X * 0.8 + 44, q[1] * 0.225 + 40.6, Z * 0.8 + 43, 2) > 0.655) return FL.brown;
    if (fbm(X + 3.1, Y + 1.7, Z + 5.3, 2) > 0.53) return FL.mid;
    return FL.base;
  };
  // the Flora collar is a clearly darker band than the vest: the camo pulled toward the dark blue-green class.
  // Pouch fronts sample the camo on the panel plane, so they carry the same class mix as the vest behind them
  const KIND = { vest: 1, pouch: 1.04, side: 0.86 };
  const camoAt = (c, kind) => (kind === 'pouch' && c.face === 'front' ? [c.p[0], c.p[1], -2.75] : c.p);
  const base = (c, kind) => (FLORA ? (kind === 'collar' ? mul(mix(flora(c.p), FL.dark, 0.35), 0.72) : mul(flora(camoAt(c, kind)), KIND[kind])) : P[kind]);
  const tone = c => { const q = snap(c.p, K.cell); return 0.95 + 0.1 * fbm(q[0] * 0.35 + 7, q[1] * 0.35 + 3, q[2] * 0.35 + 11); };
  const cloth = (c, kind, k = 1) => mul(base(c, kind), k * tone(c) * G(c, 0.06));
  const grime = (c, col, t) => {
    if (t <= 0) return col;
    const q = snap(c.p, K.cell), n = fbm(q[0] * 0.8 + 5, q[1] * 0.5 + 9, q[2] * 0.8 + 2);
    return mix(col, P.grime, Math.min(0.6, t * sm(clamp01((n - 0.4) * 2.5))));
  };
  const lining = c => mul(P.lining, G(c, 0.08));
  const clin = c => mul(P.clin, G(c, 0.08));
  const pipe = c => mul(P.pipe, G(c, 0.05));
  const deep = c => mul(P.deep, G(c, 0.06));                     // the dark inside of the collar, seen in the V
  const ridge = c => (FLORA ? cloth(c, 'collar', 1.18) : cloth(c, 'quilt'));   // quilting ridge on the collar shell
  const rimmed = col => mix(mul(col, 0.9), P.face, 0.3);          // rolled, dark-green-bound edge
  const metal = (c, k) => mul(P.metalHi, k * G(c, 0.04));          // bright strap tip: front x1.08, top x1.2, sides x0.72

  // ---------- front panel with the U-shaped apron ----------
  // full width down to the side panels' bottom (y 12), then a ~45deg taper to a broad 4 px bottom below the belt (the
  // last pixel keeps its width and its edge cells get the rolled rim, so it reads rounded, not pointed): rows of the
  // apron [row bottom, half-width]; texels outside are cut away
  const HEM = 15, Y_ARM = 7, Y_SIDE = 12;
  const HW = [[Y_SIDE, 4.5], [12.5, 4], [13, 3.5], [13.5, 3], [14, 2.5], [14.5, 2], [HEM, 2]];
  const hwAt = y => { for (const [yb, w] of HW) if (y < yb) return w; return -1; };
  const cut = (ax, y) => y >= HEM || ax > hwAt(y);
  // pouch footprints on the panel [x0, x1, y0, y1] (|x|); the panel darkens in the narrow gaps beside them and in a
  // row under them, like the deep shadows round the pouches in the render (the slit between the two pouches of a pair
  // only shows that shadow)
  const MAG0 = 6.5, MAGF = 8, MAG1 = 11.5, PK0 = 1.5, PKL = 2, PK1 = 5.5;
  const FOOT = [[0.75, 2.5, MAG0, MAG1], [2.75, 4.5, MAG0, MAG1], [3, 4.5, PK0, PK1]];
  const shaded = (ax, y) => FOOT.some(([a, b, t, u]) => ax > a - px && ax < b + px && y > t && y < u + px && !(ax > a && ax < b && y < u));
  const armhole = c => mix(lining(c), P.face, 0.4);
  B.push(box('body', [-4.5, 0, -2.75], [4.5, HEM, -2.25], c => {
    const y = c.p[1], ax = Math.abs(c.p[0]);
    if (isPanel(c)) { if (cut(ax, y)) return null; }
    else if (c.face === 'bottom') { if (cut(ax, HEM - px / 2)) return null; }
    else if (isSide(c) && y >= Y_SIDE) return null;
    if (c.face === 'back' || c.face === 'top') return lining(c);
    if (isSide(c)) return y < Y_ARM ? armhole(c) : rimmed(cloth(c, 'vest', 0.85));
    if (c.face === 'bottom') return grime(c, rimmed(cloth(c, 'vest', 0.8)), P.gr);
    let col = cloth(c, 'vest', 1.05 - 0.011 * y);
    col = grime(c, col, P.gr * clamp01((y - 10) / 4));                          // smudges across the lower apron
    if (y >= 1.5 && y < 2 && ax < 2.5) col = mul(col, 0.86);                   // shadow under the collar
    if (shaded(ax, y)) col = mul(col, 0.74);                                     // contact shadow round the pouches
    if (cut(ax + px, y) || cut(ax, y + px)) col = rimmed(col);
    return col;
  }, { tag: 'front panel + apron' }));

  // ---------- back panel (not visible in the render: kept plain, a little shorter than the apron) ----------
  B.push(box('body', [-4.5, 0, 2.25], [4.5, 13, 2.75], c => {
    const y = c.p[1];
    if (c.face === 'front' || c.face === 'top') return lining(c);
    if (isSide(c)) return y < Y_ARM ? armhole(c) : rimmed(cloth(c, 'vest', 0.85));
    if (c.face === 'bottom') return rimmed(cloth(c, 'vest', 0.8));
    let col = cloth(c, 'vest', 1 - 0.01 * y);
    col = grime(c, col, P.gr * 0.6 * clamp01((y - 9.5) / 3.5));
    if (c.ev > c.fh - px || c.eu < px || c.eu > c.fw - px) col = rimmed(col);
    return col;
  }, { tag: 'back panel' }));

  // ---------- side panels under the armholes: horizontal adjuster straps (mostly behind the arm) ----------
  // they end where the front panel is still full width, so both ends stay closed by the panels
  for (const s of [-1, 1]) {
    const inner = s < 0 ? 'left' : 'right';
    B.push(box('body', [s < 0 ? -4.5 : 4.25, Y_ARM, -2.25], [s < 0 ? -4.25 : 4.5, Y_SIDE, 2.25], c => {
      if (c.face === 'top') return lining(c);
      if (isPanel(c)) return null;                                                // ends: pressed between the panels
      if (c.face === inner || c.face === 'bottom') return cloth(c, 'side', 0.7);
      const r = (((c.p[1] - Y_ARM) % 1.5) + 1.5) % 1.5;
      const col = cloth(c, 'side');
      return r >= 1 ? mul(col, 0.72) : r < 0.5 ? mul(col, 1.06) : col;
    }, { tag: 'side panel' }));
  }

  // ---------- stand collar ----------
  // outer shell: piped top row, one lighter quilting ridge two rows down; the inside is the lining colour
  const collarOuter = c => {
    const r = Math.floor(c.ev / px + 1e-6);
    if (r <= 0) return pipe(c);
    return r === 2 ? ridge(c) : cloth(c, 'collar');
  };
  // thin low walls beside the head, from behind the shoulder rolls to the back band; they rise toward the back to meet
  // the band's top and lean out a little
  for (const s of [-1, 1]) {
    const inner = s < 0 ? 'left' : 'right';
    const xa = s < 0 ? [-5, -4.5] : [4.5, 5];
    B.push(box('body', [xa[0], -1, -1], [xa[1], 0.5, 4.75], c => {
      if (c.face === inner || c.face === 'top') return clin(c);                  // pipe = the outer face's top row
      if (c.face === 'bottom') return mul(clin(c), 0.8);
      return collarOuter(c);
    }, { tag: 'collar side', rot: [10, 0, s * 3.5], pivot: [s * 4.75, 0.5, -1] }));
  }
  B.push(box('body', [-4.5, -2, 2.75], [4.5, 0.5, 4.75], c => {
    if (c.face === 'front') return clin(c);
    if (c.face === 'bottom') return mul(clin(c), 0.8);
    if (c.face === 'top') return c.p[2] > 4.75 - px ? pipe(c) : clin(c);
    return collarOuter(c);
  }, { tag: 'collar back' }));
  // the band's base: a 45deg fillet from its lower back edge down into the back panel, so no flat shelf shows under it
  // (a square bar turned 45deg; only its lower-back face and the triangle of its ends are outside the band / body)
  const FC = 4.75 - 1.5 * Math.SQRT2;
  B.push(box('body', [-4.25, -1, FC - 1.5], [4.25, 2, FC + 1.5], c => {
    if (c.face === 'bottom') {                                                    // the slope (faces down and back, so it only
      const col = cloth(c, 'vest', 1.2);                                          // gets ambient light: painted a bit lighter)
      return c.ev > c.fh - px ? mul(col, 0.84) : col;                             // soft shadow just under the band
    }
    // the end faces lie in the jacket layer's side plane (x 4.25): keep only the cells that sit wholly behind its back
    // edge (turned z >= 2.25; the triangle behind the back panel), so nothing stays coplanar with the sleeve-side jacket
    if (isSide(c)) return (c.p[1] - 0.5) + (c.p[2] - FC) < -1e-6 ? null : cloth(c, 'vest', 0.8);
    return lining(c);                                                             // buried in the band / body
  }, { tag: 'collar base', rot: [45, 0, 0], pivot: [0, 0.5, FC] }));
  // front: the two collar ends cross under the chin in a V (only the strip below the head shows). Underneath, the
  // wearer's-right end: its piped top edge runs diagonally down toward the throat (mirroring the lapel) and meets the
  // other end's pipe; the dark inside of the collar shows in the V between the two pipes. Both pipes are two cells wide
  // and step one cell per row, so each row overlaps the one above and they read as continuous diagonal lines
  B.push(box('body', [-2.5, -0.5, -3.25], [2.5, 1.5, -2.75], c => {
    if (c.face === 'back' || c.face === 'top') return clin(c);
    if (c.face === 'bottom') return mul(clin(c), 0.8);
    const x = c.p[0], r = Math.floor(c.p[1] / px + 1e-6);
    if (isSide(c)) return r <= 0 ? mul(pipe(c), 0.85) : cloth(c, 'collar', 0.85);
    const xp = -2.5 + Math.max(0, r) * px;                                       // under-pipe's first cell in this row
    if (x >= xp && x < xp + 2 * px) return pipe(c);
    return x > xp ? deep(c) : cloth(c, 'collar');                               // V opening above it, its outer face below
  }, { tag: 'collar front' }));
  // on top, the wearer's-left end: its piped cut edge runs unbroken from top-centre down toward the wearer's right
  const xl = y => -(0.5 + Math.floor(y / px + 1e-6) * px);                     // lapel's left edge per row
  B.push(box('body', [-1.5, -0.5, -3.5], [2.5, 1.5, -3.25], c => {     // whole cells wide, so front and back cut alike
    const x = c.p[0], y = c.p[1];
    if (x < xl(y) - 1e-6) return null;                                          // cut along the diagonal
    if (c.face === 'back') return null;                                         // lies flat on the band (no z-fight at the open edge)
    if (c.face === 'top') return pipe(c);
    if (c.face === 'bottom') return cloth(c, 'collar', 0.8);
    if (isSide(c)) return x < 0 ? mul(pipe(c), 0.85) : cloth(c, 'collar', 0.85);
    if (x < xl(y) + 2 * px) return pipe(c);                                     // piped diagonal edge, two cells wide
    return cloth(c, 'collar');
  }, { tag: 'collar lapel' }));

  // ---------- shoulder rolls (pad in front), adjuster straps with buckle and metal tip ----------
  const WEBC = c => mul(P.web, G(c, 0.05));
  for (const s of [-1, 1]) {
    const X = (a, b) => (s < 0 ? [-b, -a] : [a, b]);
    // padded roll over the outer shoulder (vest cloth, rounded: lit upper part, bound lower edge) whose front end is the
    // strap pad, standing proud down to the chest pocket's lid (only the part below the head shows, so it runs down to
    // y 1.5 to read as the render's big corner roll); the roll runs back over the shoulder top to the collar wall
    const [r0, r1] = X(3, 4.5);
    const roll = c => {
      const col = cloth(c, 'vest', 1.06);
      if (c.face === 'top') return mul(col, Math.abs(c.p[0]) > 4.5 - px ? 1.04 : 1.12);
      if (c.face === 'bottom') return rimmed(col);
      if (c.face === 'back') return mul(col, 0.8);
      if (isSide(c)) return mul(col, c.ev < px ? 0.96 : 0.84);
      if (c.p[1] >= 1) return rimmed(col);                                      // bound lower edge
      return mul(col, c.p[1] < 0.5 ? 1.14 : 1.04);                              // lit top of the roll
    };
    B.push(box('body', [r0, -0.75, -3.5], [r1, 1.5, -2.75], c => (c.face === 'back' ? null : roll(c)), { tag: 'shoulder pad' }));
    B.push(box('body', [r0, -0.75, -2.75], [r1, 0, -1], c => (c.face === 'front' ? null : roll(c)), { tag: 'shoulder roll' }));
    const [s0, s1] = X(2.5, 3);
    B.push(box('body', [s0, 0, -3], [s1, 3, -2.75], c => {
      if (c.face === 'back') return lining(c);
      if (c.p[1] >= 2.5 || c.face === 'bottom') return metal(c, c.face === 'front' ? 1.08 : c.face === 'top' ? 1.2 : 0.72);
      const col = WEBC(c);
      if (c.face === 'front' || c.face === 'top') return col;
      return mul(col, 0.8);
    }, { tag: 'adjuster strap' }));
    // small dark gunmetal buckle across the strap and the pad's inner edge: its back lies on the strap, it stands a
    // little proud of the pad; lit only along its top edge (the bright metal is kept for the strap tip)
    const [b0, b1] = X(2.5, 3.25);
    B.push(box('body', [b0, 0.5, -3.75], [b1, 1, -3], c => {
      const k = c.face === 'top' ? 1.25 : c.face === 'front' ? 0.85 : c.face === 'bottom' ? 0.6 : 0.68;
      return mul(P.metal, k * G(c, 0.04));
    }, { tag: 'strap buckle' }));
  }

  // ---------- pouches: a proud flap / lid over the top, the recessed body below ----------
  const flap = c => {
    if (c.face === 'back') return lining(c);
    const col = cloth(c, 'pouch');
    if (c.face === 'top') return mul(col, 1.1);
    if (c.face === 'bottom') return mul(col, 0.7);
    if (isSide(c)) return mul(col, 0.74);
    if (c.ev < px) return mul(col, 1.1);                                        // folded top edge
    if (c.ev > c.fh - px) return mul(col, 0.94);
    return c.eu < px || c.eu > c.fw - px ? mul(col, 0.88) : col;
  };
  const pbody = c => {
    if (c.face === 'back') return lining(c);
    const v = c.p[1] - c.box.y, n = Math.round(c.box.h / px), j = Math.min(n - 1, Math.floor(v / px + 1e-6));
    const mag = c.box.tag === 'mag pouch';
    let col = cloth(c, 'pouch', 0.97);
    if (c.face === 'top') return lining(c);
    if (c.face === 'bottom') return grime(c, mix(mul(col, 0.62), P.face, 0.2), P.gr);
    if (isSide(c)) return grime(c, mul(col, 0.72), P.gr * 0.5);
    if (j === 0) col = mul(col, 0.82);                                           // shadow under the flap / lid
    else if (mag && j === n - 2) col = mul(col, 0.9);                            // stitched band near the bottom
    else if (j === n - 1) col = mul(col, 0.84);                                  // darker bottom edge
    // side seams on the khaki only; on the camo the dark side faces and the shadowed gaps already part the pouches
    if (!FLORA && (c.eu < px || c.eu > c.fw - px)) col = mul(col, 0.88);
    const g = clamp01((v - 1) / (c.box.h - 1));
    return grime(c, col, P.gr * (mag ? g * (j === n - 1 ? 1.25 : 1) : 0.5 * g)); // grime gathers along the bottoms
  };
  // one tall narrow padded pocket high on each outer edge of the chest, just under the shoulder pad, short lid
  for (const s of [-1, 1]) {
    const [a, b] = s < 0 ? [-4.5, -3] : [3, 4.5];
    B.push(box('body', [a, PK0, -3.5], [b, PKL, -2.75], flap, { tag: 'chest pocket lid' }));
    B.push(box('body', [a, PKL, -3.25], [b, PK1, -2.75], pbody, { tag: 'chest pocket' }));
  }
  // four double-mag pouches in two broad pairs, as in the render: the two pouches of a pair almost touch (a thin dark
  // slit), the gap between the pairs is a little narrower than a pouch
  for (const [a, b] of [[-4.5, -2.75], [-2.5, -0.75], [0.75, 2.5], [2.75, 4.5]]) {
    B.push(box('body', [a, MAG0, -4], [b, MAGF, -2.75], flap, { tag: 'mag pouch flap' }));
    B.push(box('body', [a, MAGF, -3.75], [b, MAG1, -2.75], pbody, { tag: 'mag pouch' }));
  }
  return { S: K.S, px: K.px, cell: K.cell, boxes: B };
};
