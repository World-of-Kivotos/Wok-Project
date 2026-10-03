// ================= WARTECH TV-115 plate carrier (olive) — EFT reference (tarkov.dev 64a536392d2c4e6e970f4121) =================
// Light slick carrier in a mid olive green; the render is seen from the wearer's right front, so the big dark loop field
// on its left is the INSIDE of the back plate bag (seen through the armhole) - it has no place on a MC torso.
// Read off the render (x<0 = wearer's right):
//   straps: wide padded straps; the wearer's left one carries a dark slate-blue comfort pad on the shoulder
//   upper chest: a big grey steel carabiner hooked on the wearer's right strap, hanging toward the arm; a dark loop patch
//     with a tan 4-slot elastic (two magenta chem lights, a blue lighter) and a grey BEAR shield patch on the left
//   black side-release buckles at the plate bag's front corners (cummerbund attachment)
//   front: triple open-top AK pouch (MOLLE webbing face, elastic top) holding three black polymer AK mags, each held by a
//     green shock cord that runs over the mag and down the pouch to a knot; a black ring sticking up behind the third
//     mag; a black CAT tourniquet (two tan rubber bands, diagonal windlass rod, red tab at the bottom) strapped down the
//     pouch front on the wearer's right of centre; two small upright grey tubes low beside it
//   sides: elastic cummerbund made of wide vertical loops (the first loop shows at each front corner)
ARMORS.tv115 = function (mode) {
  const K = kit('M');                          // always the 2x style
  const { edge, G, isPanel, isSide } = K;
  const cw = K.cell;                           // one art cell (0.5 px)
  const B = [];
  const add = (a, b, mat, tag, opt = {}) => B.push(box('body', a, b, mat, { tag, ...opt }));
  const md = (v, m) => ((v % m) + m) % m;
  const X = (s, a, b) => (s < 0 ? [a, b] : [-b, -a]);   // x-range on the wearer's right (s<0) or mirrored to the left

  // ---------- palette (sampled off the lit parts of the render, saturation kept) ----------
  // (the render's lit fabric samples at a hue of about 80 deg: a slightly yellow olive drab, EFT's "Olive Drab")
  const OLV = hex('#5f6b48');                  // carrier Cordura
  const STRAP = hex('#6a7752');                // padded straps / loops (the most lit fabric)
  const PLAC = hex('#57653f');                 // mag pouch webbing (a little darker)
  const VEL = hex('#48533a');                  // loop-velcro patch
  const SLATE = hex('#434c57');                // comfort pad on the wearer's left strap
  const LINING = hex('#1e2019');
  // shock cord: a thin dark-olive line between the pouch and the mag in value (two cords per mag in the render, one
  // line at this size); only the knots catch the light
  const CORD = hex('#56613d'), KNOT = hex('#6e7a4d');
  const MAG = hex('#383b3d'), MAGTOP = hex('#1f2224'), DSTEEL = hex('#43484e'), BRASS = hex('#caa24e'), COPPER = hex('#b5703f');
  const STEEL = hex('#8b9093'), KNURL = hex('#b3b7b9');
  const PURPLE = hex('#9c4684'), TAN = hex('#806848'), BLUE = hex('#3e5ea8'), BADGE = hex('#8e908f'), INK = hex('#26272a');
  const TQ = hex('#1f2021'), TQROD = hex('#45484b'), TQBAND = hex('#80705a'), RED = hex('#a8322b');
  const BLACK = hex('#262a25'), GREY = hex('#76808a');

  // ---------- shared painters (same set as the TV-110) ----------
  // Cordura: faint low-frequency mottling + per-cell grain
  const fab = (c, base, amt = 0.06, mot = 1) => {
    const q = snap(c.p, cw);
    const n = fbm(q[0] * 0.45 + 7, q[1] * 0.45 + 1, q[2] * 0.45 + 3);
    return mul(base, (1 + 0.1 * mot * (n - 0.5)) * G(c, amt));
  };
  // MOLLE webbing rows: 1 px tape (lit upper cell) + 0.5 px dark seam, period 1.5; a soft bar tack every 1.5 px
  const rows = (c, col, y0, n, h, h0, h1, web) => {
    if (h < h0 || h > h1) return null;
    const dy = c.p[1] - y0;
    if (dy < 0) return null;
    const i = Math.floor(dy / 1.5);
    if (i >= n) return null;
    const r = dy - i * 1.5;
    if (r >= 1) return mul(col, 0.7);
    const t = web ? mix(col, web, 0.5) : col;
    if (md(h - h0, 1.5) < cw) return mul(t, 0.91);
    return mul(t, r < cw ? 1.06 : 0.96);
  };
  // flat cut-out plate painted from a cell mask (one char per art cell, '.' = cut out); side faces reuse the nearest
  // front cell, the back face (lying on the carrier) is dropped
  const flatMask = (mask, pal, sideK = 0.78) => c => {
    if (c.face === 'back') return null;
    const b = c.box, hh = cw / 2;
    const u = Math.min(b.w - hh, Math.max(hh, c.p[0] - b.x)), v = Math.min(b.h - hh, Math.max(hh, c.p[1] - b.y));
    const row = mask[Math.min(mask.length - 1, Math.floor(v / cw))], ch = row && row[Math.min(row.length - 1, Math.floor(u / cw))];
    if (!ch || ch === '.') return null;
    const col = pal[ch];
    return c.face === 'front' ? col : mul(col, c.face === 'top' ? 1.1 : sideK);
  };
  // magazine (broad side forward): lit feed-lip edge, darker spine sides, one darker rib; the top sits under the feed lips;
  // the shock cord is painted straight down the front in the art column starting at kx
  const onCord = (c, kx) => c.face === 'front' && c.p[0] > kx && c.p[0] < kx + cw;
  // (on the mag the cord is dropped a step toward the mag's black, so it reads as a thin line and not as a bright slat;
  // the darker rib sits in the far column, away from the cord, as on the render)
  const MCORD = mul(CORD, 0.86);
  const magPaint = (base, kx) => c => {
    if (c.face === 'bottom') return mul(base, 0.6);
    if (c.face === 'top') return mul(MAGTOP, c.ex < cw ? 1.5 : 1);
    if (onCord(c, kx)) return mul(MCORD, (c.ev < cw ? 1.1 : 1) * G(c, 0.05));
    let col = mul(base, G(c, 0.04));
    if (!isPanel(c)) return mul(col, 0.74);
    if (c.ev < cw) return mul(col, 1.3);
    const n = Math.max(1, Math.round(c.fw / cw)), i = Math.floor(c.eu / cw);
    if (i === n - 1) col = mul(col, 0.8);
    return col;
  };
  // feed lips: dark steel (lit rim on top); the cord comes down the lips' front in the same column. The top round is its
  // own thin bar lying on the lips behind the cord (a round painted on the lips' top was hidden by the cord crossing it)
  const lips = kx => c => (onCord(c, kx) ? MCORD : c.face === 'top' ? mul(DSTEEL, 1.3) : mul(DSTEEL, isPanel(c) ? 1.15 : 0.9));
  // brass round lying across the lips, copper tip on the wearer's left; its face under the cord is never seen
  const round = cx => c => {
    if (c.face === 'bottom') return null;
    const col = c.p[0] > cx ? COPPER : BRASS;
    return c.face === 'top' ? mul(col, 1.12) : c.face === 'front' ? col : mul(col, 0.8);
  };

  // ================= carrier =================
  const frontBag = c => {
    if (c.face === 'top') return mul(LINING, G(c, 0.1));                     // dark lining at the neckline
    if (c.face === 'back') return mul(LINING, 1.2);
    // behind the mag row, above the pouch mouth: shadow, so the gaps between the mags read as dark, not as olive stripes
    if (c.face === 'front' && c.p[1] >= 4.5 && c.p[1] < 6.25 && Math.abs(c.p[0]) <= 3.5)
      return mul(mix(VEL, LINING, c.p[1] < 5.25 ? 0.5 : 0.65), G(c, 0.05));
    return edge(c, fab(c, OLV));
  };
  add([-4.25, 1.25, -3], [4.25, 10.5, -2.5], frontBag, 'front plate bag');
  // back plate bag: five MOLLE rows (the render only shows the front; the back follows the carrier's own webbing)
  const backBag = c => {
    if (c.face === 'top' || c.face === 'front') return mul(LINING, G(c, 0.1));
    const col = fab(c, OLV);
    return edge(c, (c.face === 'back' && rows(c, col, 2.25, 5, -c.p[0], -3.5, 3.5, PLAC)) || col);
  };
  add([-4.25, 0.75, 2.5], [4.25, 10.5, 3], backBag, 'back plate bag');
  add([-1.25, 0.5, 3], [1.25, 1.25, 3.5], c => edge(c, fab(c, mul(OLV, 0.82)), { stitch: false }), 'drag handle');
  // elastic cummerbund: wide vertical loops (1 px loop, 0.5 px dark gap) all round the sides
  const cumm = c => {
    let col = fab(c, OLV);
    if (isSide(c)) {
      const k = md(c.p[2] + 2.75, 1.5);
      col = k < 1 ? mul(fab(c, STRAP), k < cw ? 1.04 : 0.95) : mul(col, 0.66);
    }
    return edge(c, col, { stitch: false });
  };
  add([-4.5, 5, -2.75], [4.5, 10.25, 2.75], cumm, 'cummerbund');
  // the first loop at each front corner, standing a little proud with its folded top above the cummerbund's edge
  const loop = c => {
    let col = fab(c, STRAP);
    if (c.face === 'top') return mul(col, 1.12);
    if (c.face === 'bottom') return mul(col, 0.7);
    if (c.face === 'back') return mul(LINING, 1.2);
    if (c.face === 'front') {
      if (c.ev < cw) return mul(col, 1.12);                                   // lit fold
      if (c.ev < 2 * cw) return mul(col, 0.8);                                // shadow under the folded top
      if (c.ev > c.fh - cw) return mul(col, 0.72);
      return mul(col, Math.floor(c.eu / cw) === 0 ? 1.03 : 0.93);
    }
    return mul(col, 0.8);
  };
  // (flat against the cummerbund's front end, so from the side it reads as a strap, not as a pouch; it reaches back to
  // 0.25 in front of the sleeve, so no daylight shows behind it)
  for (const s of [-1, 1]) { const [a, b] = X(s, -5, -4); add([a, 4.75, -3.25], [b, 10, -2.5], loop, 'cummerbund loop'); }

  // ---------- shoulder straps: pads over the shoulders (outside the hat layer) + risers down to the bags ----------
  const hump = base => c => {
    if (c.face === 'bottom') return mul(LINING, 1.2);
    const col = fab(c, base, 0.07);
    if (isPanel(c)) return mul(col, 0.9);
    return c.face === 'top' ? mul(col, 1.08) : col;
  };
  const riser = front => c => {
    if (c.face === 'bottom' || c.face === (front ? 'back' : 'front')) return mul(LINING, 1.2);
    return edge(c, fab(c, STRAP, 0.07), { stitch: false });
  };
  for (const s of [-1, 1]) {
    const [b0, b1] = X(s, -5.75, -4), [h0, h1] = X(s, -5.5, -4.5), [r0, r1] = X(s, -4, -1.5);
    add([b0, -1.25, -3.5], [b1, -0.25, 3.5], hump(STRAP), 'shoulder pad');
    // (wearer's left: the dark slate-blue comfort pad on top of the strap)
    add([h0, -1.75, -3.25], [h1, -0.75, 3.25], hump(s < 0 ? STRAP : SLATE), s < 0 ? 'shoulder pad crown' : 'slate comfort pad');
    add([r0, -0.25, -3.25], [r1, 2, -2.75], riser(true), 'strap front');
    add([r0, -0.25, 2.75], [r1, 2, 3.25], riser(false), 'strap back');
  }
  // black side-release buckles at the plate bag's front corners: lit rim, dark slot, prong
  const buckle = c => {
    if (c.face === 'top') return mul(BLACK, 1.5);
    if (c.face !== 'front') return mul(BLACK, 0.9);
    const j = Math.floor(c.ev / cw);
    return mul(BLACK, j === 0 ? 1.45 : j === 1 ? 0.8 : 1.12);
  };
  for (const s of [-1, 1]) { const [a, b] = X(s, -4, -3.25); add([a, 4, -3.25], [b, 5.5, -2.75], buckle, 'cummerbund buckle'); }

  // ================= upper chest =================
  // big steel carabiner hooked on the wearer's right strap (hook end on the strap, the loop hanging out toward the arm),
  // the lighter knurled lock sleeve along its lower bar
  // (a big D: 3 x 2 px with an open middle, tipped so its outer end hangs lower)
  const carab = flatMask(['.SSSS.', 'S....S', 'S....S', '.KKKK.'], { S: STEEL, K: KNURL }, 0.72);
  add([-5, 1.25, -3.5], [-2, 3.25, -3], carab, 'carabiner', { rot: [0, 0, -18], pivot: [-2.25, 1.5, -3.25] });
  // dark loop patch with a lighter bound edge; the elastic holder and the BEAR patch sit on it
  const patch = c => {
    if (c.face === 'back') return null;
    const col = mul(VEL, G(c, 0.1));
    if (!isPanel(c)) return mul(col, 0.85);
    return c.ex < cw ? mul(col, 1.18) : col;
  };
  add([-2.25, 2, -3.25], [2.75, 4.5, -3], patch, 'loop patch');
  // two magenta chem lights and a blue lighter standing behind the tan elastic, their lower ends hidden by the mags
  const stick = c => {
    if (c.face === 'back') return null;
    if (c.face === 'top') return mul(PURPLE, 1.3);
    const col = mul(PURPLE, c.p[1] - c.box.y < cw ? 1.22 : G(c, 0.04));
    return isPanel(c) ? col : mul(col, 0.78);
  };
  // (the first runs down behind the gap between the first two mags)
  add([-1.75, 1.25, -3.5], [-1.25, 4.75, -3], stick, 'chem light');
  add([-1, 1.75, -3.5], [-0.5, 4.25, -3], stick, 'chem light');
  const lighter = c => {
    if (c.face === 'back') return null;
    if (c.face === 'top') return mul(INK, 1.7);
    const col = c.p[1] - c.box.y < cw ? mul(INK, 1.5) : mul(BLUE, G(c, 0.05));
    return isPanel(c) ? col : mul(col, 0.8);
  };
  add([-0.25, 1.75, -3.5], [0.25, 4.25, -3], lighter, 'lighter');
  const elastic = c => {
    if (c.face === 'back') return null;
    const col = mul(TAN, G(c, 0.06));
    if (c.face === 'top') return mul(col, 1.15);
    if (!isPanel(c)) return mul(col, 0.8);
    return mul(col, Math.floor(c.ev / cw) === 0 ? 1.1 : 0.86);
  };
  // (a little above the mags, as on the render, where the sticks show between the elastic and the mag tops)
  add([-2, 2.5, -3.75], [0.5, 3.5, -3.25], elastic, 'chem-light elastic');
  // grey BEAR shield patch (dark emblem in the middle, pointed bottom)
  // (a dark-grey emblem, not black: a black centre cell read as an eye)
  add([0.75, 2.25, -3.5], [2.25, 3.75, -3.25], flatMask(['HGH', 'GKG', '.G.'], { H: mul(BADGE, 1.12), G: BADGE, K: mix(BADGE, INK, 0.65) }), 'BEAR patch');

  // ================= triple AK mag pouch =================
  // three shock cords: looped over each mag's feed lips, down its front, then down the pouch front to a knot with a
  // short tail. Everything below the lips is painted flat - raised strands (on the mags too) read as bright fence posts
  // standing in front of the mags.
  // (mags centred on the pouch's art grid, so each cord runs in ONE column from the lips down to its knot: the column
  // just left of each mag's middle, as in the render)
  const MAGS = [[-2.5, -3], [0, -0.5], [2.5, 2]];                              // [mag centre, cord x]
  const cordCell = (x, y) => {
    for (const [, kx] of MAGS) {
      if (y >= 6 && y < 7 && x >= kx && x < kx + cw) return 1;                 // strand
      if (y >= 7 && y < 7.5 && x >= kx && x < kx + 2 * cw) return 2;           // knot
      if (y >= 7.5 && y < 8.5 && x >= kx + cw && x < kx + 2 * cw) return 1;    // tail
    }
    return 0;
  };
  const placard = c => {
    if (c.face === 'top') return c.ex < cw ? mul(PLAC, 1.18) : mul(LINING, 1.2);   // open mouth: light rim, dark inside
    if (c.face === 'back') return mul(LINING, 1.1);
    let col = fab(c, PLAC, 0.05, 0.5);                                        // flatter: the webbing rows carry the texture
    if (c.face === 'front') {
      const k = cordCell(c.p[0], c.p[1]);
      if (k) return mul(k === 2 ? KNOT : CORD, G(c, 0.05));
      col = c.p[1] < 6.5 ? mul(col, 1.1) : rows(c, col, 6.5, 3, c.p[0], -4, 4, STRAP) || col;   // elastic top binding, then webbing
    }
    return edge(c, col, { stitch: false });
  };
  add([-4, 6, -4.25], [4, 10.5, -3], placard, 'mag pouch');
  // three black polymer AK mags, 1.5 px down in the pouch, the cord painted down each one's front
  const cord = c => (c.face === 'back' ? null : mul(CORD, (c.face === 'top' ? 1.15 : isPanel(c) ? 1 : 0.8) * G(c, 0.06)));
  for (const [cx, kx] of MAGS) {
    add([cx - 1, 4.5, -4], [cx + 1, 8, -3.25], magPaint(MAG, kx), 'AK magazine');
    add([cx - 0.5, 4, -3.875], [cx + 0.5, 4.5, -3.375], lips(kx), 'mag feed lips');
    // the only raised bit of cord: a thin bridge over the lips' front edge, so the cord visibly loops over the mag; the
    // brass round lies right behind it at the same height, so the cord never hides the round from above
    add([kx, 3.75, -4], [kx + 0.5, 4, -3.75], cord, 'shock cord (over the feed lips)');
    add([cx - 0.5, 3.75, -3.75], [cx + 0.5, 4, -3.5], round(cx), 'top round');
  }
  // black pull ring standing up behind the third mag's left half, under the BEAR patch (its lower half hidden by the mag)
  // (set a hair off the mag's back plane, which it would otherwise share, and its sides inside the mag's)
  add([1.75, 3.75, -3.45], [3.25, 5.25, -3.2], flatMask(['KKK', 'K.K', 'KKK'], { K: mul(BLACK, 0.85) }, 0.8), 'black ring');
  // CAT tourniquet strapped down the pouch front: folded strap, two tan rubber bands, a long diagonal windlass rod
  // between them (2-row steps, so it reads as one bar), red tab
  const tqMask = ['LLL', 'TTT', 'KKr', 'KKr', 'KrK', 'rKK', 'rKK', 'TTT', 'KKK', 'RRR'];
  add([-2, 5.75, -4.75], [-0.5, 10.75, -4.25], flatMask(tqMask, { L: mul(TQ, 1.5), K: TQ, r: TQROD, T: TQBAND, R: RED }, 0.8), 'tourniquet');
  // two small upright grey tubes on the lowest webbing row, against the tourniquet's side (darker split between them)
  add([-0.5, 9.25, -4.5], [0.5, 10.25, -4.25], flatMask(['gG', 'gG'], { g: GREY, G: mul(GREY, 0.8) }), 'grey tubes');
  return { S: K.S, px: K.px, cell: K.cell, boxes: B };
};
