// ================= Eagle Allied Industries MBSS plate carrier — EFT reference (tarkov.dev 64a5366719bab53bd203bf33) =================
// In EFT this is an armored rig: the gear is part of the item model. Read off the render (x<0 = wearer's right):
//   worn khaki / coyote Cordura with an olive cast that turns moss-green toward the bottom hem; dark green lining;
//   padded shoulder straps with cream ladder-lock buckles, a cream webbing tail running down each strap beside the bag;
//   upper front = olive-khaki loop field: a raised, lighter admin flap (light binding round its edge) with a grey USEC
//   shield patch (left of centre) and a dried blood smear, then a darker lower loop strip with a small black square right
//   of centre; black trauma shears on the loop strip just inboard of the wearer's right strap (whose cream webbing tail
//   stays visible), both finger loops under the flap edge, blades down into the pouch row; black CAT tourniquet (red tip,
//   grey tag, vertical windlass rod, two khaki elastic bands) on the inner half of the wearer's left strap, cream webbing
//   outboard of it and a dark metal D-ring carabiner clipped on the strap's outer half, down to the upper stacked pouch;
//   bottom row: two flapped double mag pouches (lit flaps, pull tabs), an open pouch with two ribbed black rifle mags (the
//   wearer's right one a touch taller) and a loop retention flap pulled over the other one and down the pouch front, two
//   stacked flapped pouches with tan side-release buckles on the wearer's left corner; a purple marker (lighter cap) held
//   by a khaki loop on the first pouch's outer side, a short strap end hanging under the stack; cummerbund sides with a
//   darker loop patch and a dark strap + buckle under it. The back is not visible in the render: plain carrier, three
//   MOLLE rows, drag handle.
ARMORS.mbss = function (mode) {
  const K = kit('M');                                  // always the 2x style
  const { edge, G, isPanel, isSide, px } = K;
  const B = [];
  const add = (a, b, mat, tag, opt = {}) => B.push(box('body', a, b, mat, { tag, ...opt }));

  // ---------- colours (sampled off the lit faces of the render) ----------
  const KH = hex('#7b6d47'), LOOPC = hex('#6f6540'), POUCH = hex('#8a7951'), FLAPC = hex('#938159'), SIDEC = hex('#665d3e'),
    PAD = hex('#85775a'), CREAM = hex('#c4b78f'), BUCK = hex('#c2b28a'), SLOT = hex('#5a5440'), GREEN = hex('#5a6340'),
    GREEN2 = hex('#4c7f50'), LINING = hex('#1f2a21'), TAB = hex('#62573f'), STAIN = hex('#4a2219');
  const MAG = hex('#2b2c2e'), MAGTOP = hex('#1c1d1f'), BRASS = hex('#caa24e'), COPPER = hex('#b5703f');
  const TQK = hex('#1d1f21'), TQR = hex('#9a2a24'), TQG = hex('#7e8184'), TQB = hex('#b0a47e'), ROD = hex('#3d4044');
  const SHEAR = hex('#1f2023'), BLADE = hex('#6c7074'), PURP = hex('#6a2c61'), CARB = hex('#34363a');   // carabiner metal
  const FLAPL = mul(LOOPC, 1.2);                       // the raised admin flap is the lighter olive-khaki panel
  const USL = hex('#9ca1a3'), USD = hex('#46474a'), UST = hex('#b4b7b9');

  // ---------- fabrics ----------
  const fab = (c, base, amt = 0.06) => {
    const q = snap(c.p, 0.5);
    const n = fbm(q[0] * 0.45 + 7, q[1] * 0.45 + 1, q[2] * 0.45 + 3);
    return mul(base, (0.96 + 0.08 * n) * G(c, amt));
  };
  // loop (velcro) field: a little fuzzier than the Cordura, still low-frequency
  const loopFab = (c, base) => {
    const q = snap(c.p, 0.5);
    const n = fbm(q[0] * 0.6 + 21, q[1] * 0.6 + 5, q[2] * 0.6 + 2);
    return mul(base, (0.94 + 0.12 * n) * G(c, 0.1));
  };
  // yellow-moss grime: soft blotches (sampled on the art grid, 1-3 px shapes) that only take hold near the bottom hem.
  // In the render the pouches keep their khaki to about nine tenths of their height and only the last row turns moss
  // green, so the tint starts late and ramps in quadratically over `len` px (plain fabric stays as bright as the original);
  // the last cell row (from `hem` down) is the true moss green of the render (GREEN2, mixed strongly: the olive GREEN at a
  // half mix only turned the khaki into mud once the bottom shade and the lighting were on it)
  const grime = (c, col, k = 0.9, y0 = 9.75, len = 1, hem = 10.5) => {
    const q = snap(c.p, 0.5);
    const n = fbm(q[0] * 0.55 + 13, q[1] * 0.55 + 4, q[2] * 0.55 + 9);
    if (c.p[1] >= hem) return mix(col, GREEN2, clamp01((0.6 + 0.3 * n) * k / 0.9));
    const t = clamp01((c.p[1] - y0) / len);
    return mix(col, GREEN, clamp01(t * t * (0.3 + 0.5 * n) * k));
  };
  // dried blood smear on the admin flap, right of the patch (soft blob, never single specks)
  const stain = (c, col) => {
    const d = ((c.p[0] - 0.75) / 1.1) ** 2 + ((c.p[1] - 3) / 0.9) ** 2;
    if (d >= 1) return col;
    const q = snap(c.p, 0.5);
    const n = fbm(q[0] * 1.1 + 3, q[1] * 1.1 + 8, 1);
    return mix(col, STAIN, 0.38 * clamp01((1 - d) * 1.6) * sm(clamp01((n - 0.35) * 2.5)));
  };
  const lining = c => mul(LINING, G(c, 0.1));
  // pouch faces are only four cells wide: kit edge() would darken half of each (stripes), so pouch fronts get a soft
  // frame instead (lit top row, a shade darker at the sides, shadowed bottom row); other faces use edge()
  // (sides: [wearer's right column, wearer's left column] on the front; where two pouches meet both columns go darker,
  // so the pair reads as two pouches with a seam between them)
  const soft = (c, col, sides = [0.9, 0.9], bottom = 0.86) => {
    if (!isPanel(c)) return edge(c, col, { stitch: false });
    if (c.ev < px) return mul(col, 1.08);
    if (c.ev > c.fh - px) return mul(col, bottom);
    const lo = c.p[0] < c.box.x + px, hi = c.p[0] > c.box.x + c.box.w - px;
    return lo ? mul(col, sides[0]) : hi ? mul(col, sides[1]) : col;
  };
  // flat cut-out objects: rows of chars, one char per art cell ('.' = cut out); side faces reuse the nearest edge cell,
  // the back face is dropped (it lies on the carrier)
  const flat = (rows, pal, sideK = 0.78) => c => {
    if (c.face === 'back') return null;
    const b = c.box, hh = px / 2;
    const u = Math.min(b.w - hh, Math.max(hh, c.p[0] - b.x)), v = Math.min(b.h - hh, Math.max(hh, c.p[1] - b.y));
    const r = rows[Math.floor(v / px)], ch = r && r[Math.floor(u / px)];
    if (!ch || ch === '.') return null;
    return isPanel(c) ? pal[ch] : mul(pal[ch], sideK);
  };

  // ================= carrier =================
  // front plate bag: loop field between the straps from under the admin flap down to the open pouch's mouth
  const frontBag = c => {
    if (c.face === 'top' || c.face === 'back') return lining(c);
    const x = c.p[0], y = c.p[1];
    let col = fab(c, KH);
    if (c.face === 'front' && x > -2.25 && x < 2.5 && y < 7.5) {
      // small black square patch (1 px, a cell clear of the shears' right loop)
      if (x >= 0.75 && x < 1.75 && y >= 3.75 && y < 4.75) return mul(hex('#1c1c1e'), G(c, 0.05));
      col = loopFab(c, LOOPC);
    }
    return edge(c, grime(c, col, 0.8, 9.75, 1, 10.25), { stitch: false });             // bag hem row 10.25..10.75
  };
  add([-4.25, 0.75, -3.25], [4.25, 10.75, -2.5], frontBag, 'front plate bag');
  const backBag = c => {
    if (c.face === 'top' || c.face === 'front') return lining(c);
    let col = fab(c, KH);
    if (c.face === 'back') {
      const x = c.p[0], y = c.p[1];
      for (const r of [5.5, 7, 8.5]) {                  // three MOLLE rows: darker webbing, darker slots every 1.5 px
        if (y >= r && y < r + 0.5 && Math.abs(x) < 3.25) col = (((x + 3.25) % 1.5) + 1.5) % 1.5 < 0.5 ? mul(col, 0.62) : mul(col, 0.8);
      }
    }
    return edge(c, grime(c, col, 0.6, 9.75, 1, 10.25), { stitch: false });
  };
  add([-4.25, 0.25, 2.5], [4.25, 10.75, 3.25], backBag, 'back plate bag');
  add([-1, 0.75, 3.25], [1, 1.5, 3.75], c => edge(c, mul(fab(c, KH), 0.85), { stitch: false }), 'drag handle');
  // cummerbund: olive-khaki sides with a darker loop patch and, under it, a dark olive strap with a lighter buckle near
  // the front (its bottom sits above the bags' so no bottom planes meet)
  const cumm = c => {
    let col = fab(c, SIDEC);
    if (isSide(c)) {
      const y = c.p[1], z = c.p[2];
      if (y >= 6.5 && y < 8 && z > -2 && z < 1.5) col = mul(loopFab(c, SIDEC), 0.8);
      else if (y >= 8.5 && y < 9.5 && z > -2.25) col = z < -1.25 ? mul(col, 1.12) : mul(col, 0.75);
    }
    return edge(c, grime(c, col, 0.6, 9.75, 1, 10), { stitch: false });                // its hem row is 10..10.5
  };
  add([-4.5, 5, -2.75], [4.5, 10.5, 2.75], cumm, 'cummerbund');

  // ================= shoulder straps =================
  // Only what shows under the head: padded risers + a cream ladder-lock buckle, the strap running down beside the bag
  // with its cream webbing tail, a padded hump on top of each shoulder (just outside the hat layer) and the back ends.
  const riser = c => {
    if (c.face === 'back' || c.face === 'bottom') return lining(c);
    let col = fab(c, PAD);
    if (c.face === 'top') col = mul(col, 1.08);
    else if (c.face === 'front' && (c.eu < px || c.eu > c.fw - px)) col = mul(col, 0.86);   // rolled edges
    return c.face === 'front' ? col : edge(c, col, { stitch: false });
  };
  const strap = s => c => {
    if (c.face === 'back') return lining(c);
    let col = fab(c, PAD);
    if (c.face !== 'front') return edge(c, col, { stitch: false });
    const i = Math.min(3, Math.floor((c.p[0] - c.box.x) / px)), io = s < 0 ? i : 3 - i;   // io 0 = outer edge
    // left strap behind the carabiner (its outer two columns, y 3.25..5.25): the webbing tail runs into the clip, so
    // what shows through the D's opening (also off-axis) is the darker padded strap, never a framed cream cell
    if (s > 0 && io <= 1 && c.p[1] >= 3.25 && c.p[1] < 5.25) return mul(col, 0.86);
    // webbing tail: inner half on the wearer's right strap; on the left strap it runs out to the outer edge (the
    // tourniquet covers the strap's inner half, the cream shows outboard of it as in the render)
    if (io === 1 || io === 2 || (s > 0 && io === 0)) return mul(CREAM, G(c, 0.05) * (io === 2 ? 0.97 : 1.03));
    return mul(col, io === 0 ? 0.86 : 0.95);
  };
  const ladder = c => {
    if (c.face === 'top') return mul(BUCK, 1.1);
    if (!isPanel(c)) return mul(BUCK, 0.8);
    return Math.floor(c.eu / px) === 1 ? SLOT : mul(BUCK, G(c, 0.04));                 // bar | slot | bar
  };
  const hump = c => {
    if (c.face === 'bottom') return mul(LINING, 1.2);
    const col = fab(c, PAD, 0.07);
    if (isPanel(c)) return mul(col, 0.9);
    return c.face === 'top' ? mul(col, 1.08) : col;
  };
  const riserBack = c => (c.face === 'front' || c.face === 'bottom' ? lining(c) : edge(c, fab(c, PAD), { stitch: false }));
  for (const s of [-1, 1]) {
    const X = (a, b) => (s < 0 ? [a, b] : [-b, -a]);
    const [r0, r1] = X(-4.25, -2.25);
    add([r0, -0.5, -3.5], [r1, 0.75, -2.5], riser, 'strap riser');
    add([r0, 0.75, -3.5], [r1, 5.5, -3.25], strap(s), 'strap on bag');
    const [l0, l1] = X(-4, -2.5);
    add([l0, 0, -3.75], [l1, 0.5, -3.5], ladder, 'ladder-lock buckle');
    const [h0, h1] = X(-5.25, -4.25);
    add([h0, -1.25, -2.75], [h1, -0.25, 2.75], hump, 'shoulder pad hump');   // ends inside the risers' depth, no overhang
    const [k0, k1] = X(-4, -2.25);
    add([k0, -0.5, 2.75], [k1, 0.5, 3.5], riserBack, 'strap back');
  }

  // ================= upper chest =================
  // admin flap: the lighter, raised loop panel with the blood smear. Its front edge is a light binding (lit top row, sides
  // a shade down, bottom row in its own shadow) instead of kit edge()'s dark frame; the darker loop strip below it stays
  // on the bag's LOOPC, so the flap stands out as in the render
  const adminFlap = c => {
    if (c.face === 'back') return lining(c);
    let col = loopFab(c, FLAPL);
    if (c.face === 'top') return mul(col, 1.1);
    if (c.face !== 'front') return edge(c, col, { stitch: false });
    col = stain(c, col);
    if (c.ev < px) return mul(col, 1.1);
    if (c.ev > c.fh - px) return mul(col, 0.85);
    return c.eu < px || c.eu > c.fw - px ? mul(col, 0.95) : col;
  };
  add([-2.25, 0.75, -3.75], [2.25, 3.75, -3.25], adminFlap, 'admin flap');
  // USEC shield patch: grey shield, light "USEC" lettering band across the middle, pointed bottom (at this size the
  // thin light rim would only turn the dark field into a slot)
  add([-1.5, 1.25, -4], [0.5, 3.25, -3.75], flat(['LLLL', 'TTTT', 'DDDD', '.DD.'], { L: mix(USD, USL, 0.35), D: USD, T: UST }), 'USEC patch');
  // trauma shears: lying on the loop strip just inboard of the wearer's right strap (left loop starting at the strap's
  // inner edge = the flap's left edge), both finger loops right under the flap edge, the grey blade running down into
  // the pouch row (its lower half is inside the second pouch). Flat plate on the bag front, beside the strap: no shared
  // front planes, back face dropped.
  add([-2.25, 3.75, -3.5], [0.25, 5.75, -3.25], flat(['KKK..', 'K.KKK', 'KKK.K', '.SKK.'], { K: SHEAR, S: BLADE }), 'trauma shears');
  // CAT tourniquet on the inner half of the wearer's left strap, right beside the flap edge (the cream webbing shows
  // outboard of it, as in the render): red tip, black body, two light khaki elastic bands; the grey tag across its top
  // sticks out inboard over the flap's corner, and the dark windlass rod stands on its outer column, held by the bands
  const tq = flat(['RR', 'KK', 'KK', 'BB', 'KK', 'KK', 'BB', 'KK'], { R: TQR, K: TQK, B: TQB }, 0.8);
  add([2.25, 1, -4], [3.25, 5, -3.5], c => (c.face === 'back' ? TQK : tq(c)), 'tourniquet');
  add([1.75, 1.5, -4.25], [3.25, 2, -4], c => mul(TQG, c.face === 'top' ? 1.15 : isSide(c) ? 0.8 : G(c, 0.05)), 'TQ tag');
  const rodP = K.plastic(ROD);
  const rod = c => {
    const y = c.p[1], band = (y >= 2.5 && y < 3) || (y >= 4 && y < 4.5);
    return band && c.face !== 'top' && c.face !== 'bottom' ? mul(TQB, isPanel(c) ? 1 : 0.8) : rodP(c);
  };
  add([2.75, 2, -4.25], [3.25, 4.5, -4], rod, 'TQ windlass rod');
  // dark metal D-ring carabiner clipped on the left strap's outer half, right beside the tourniquet's lower end (in the
  // render it hangs off the strap over the side panel, which Minecraft does not have: the arm is there). Its spine and
  // opening lie on the strap's outer two columns (the strap is painted darker behind it, see strap()), only the outer
  // bar of the D stands half a pixel past the strap edge (no further out than the stacked pouches below), and its bottom
  // bar rests on the upper stacked pouch's top. Metal grey, not black: lit top bar, a lighter outer bar (the curved back
  // of the D), the gate side a shade darker, bottom bar. Square corners (cut corners turned it into a plus-shaped
  // checker). Flat cut-out plate on the strap: back face dropped.
  const carab = flat(['TTT', 'S.O', 'S.O', 'BBB'],
    { T: mul(CARB, 1.4), S: mul(CARB, 0.95), O: mul(CARB, 1.2), B: mul(CARB, 1.05) }, 0.8);
  add([3.25, 3.25, -3.75], [4.75, 5.25, -3.5], carab, 'carabiner');

  // ================= mag pouches =================
  const pouchBody = sides => c => {
    if (c.face === 'top') return mul(fab(c, FLAPC), 1.04);                              // closed under the flap
    return soft(c, grime(c, fab(c, POUCH)), sides);
  };
  const pouchFlap = sides => c => {
    let col = mul(fab(c, FLAPC), 1.08 - 0.1 * clamp01((c.p[1] - 5.5) / 4));             // lit at the top, like the render
    if (c.face === 'front' && c.ev >= 2 && c.ev < 3.5 && c.eu >= px && c.eu < c.fw - px) col = mul(col, 0.93);   // stitched velcro panel
    return soft(c, grime(c, col, 0.5), sides, 0.74);
  };
  const pullTab = c => mul(fab(c, TAB), c.face === 'front' ? 1 : 0.85);
  // the flaps are a quarter pixel narrower than their pouch on each side, so the pouch body shows as a darker seam
  // between neighbouring flaps (full-width flaps merged the pair into one block)
  for (const [x0, sides] of [[-4, [0.9, 0.78]], [-2, [0.78, 0.84]]]) {
    add([x0, 5.5, -4.75], [x0 + 2, 11, -3.25], pouchBody(sides), 'mag pouch');
    add([x0 + 0.25, 5.5, -5], [x0 + 1.75, 9.5, -4.75], pouchFlap([0.94, 0.94]), 'mag pouch flap');
    add([x0 + 0.75, 9.5, -5], [x0 + 1.25, 10, -4.75], pullTab, 'flap pull tab');
  }
  // open pouch: light rim round a dark mouth, elastic binding along the front edge
  const openPouch = c => {
    if (c.face === 'top') return c.ex < px ? mul(POUCH, 1.12) : mul(LINING, 1.2);
    return soft(c, grime(c, fab(c, POUCH)), [0.84, 0.84]);                              // (its lit top row = the elastic binding)
  };
  add([0, 7.5, -4.75], [2.5, 11, -3.25], openPouch, 'open mag pouch');
  // two black rifle mags, 1.5+ px down in the pouch. As in the render, the front one turns its broad ribbed side forward
  // (three cells wide, the middle one a darker rib, darker spine sides) and the second, taller one stands a quarter pixel
  // further back on the wearer's right, so only a narrow strip of it shows beside its neighbour and it rises above it.
  // Each has a narrower feed-lip block on top carrying the top round (brass case, copper tip on the wearer's left).
  const magPaint = (ribbed, k = 1) => c => {
    if (c.face === 'bottom') return mul(MAG, 0.6);
    if (c.face === 'top') return mul(MAGTOP, c.ex < px ? 1.4 : 1);                    // rim round the feed lips
    const col = mul(MAG, G(c, 0.05) * k);
    if (!isPanel(c)) return mul(col, 0.72);                                            // spines
    if (c.ev < px) return mul(col, 1.3);                                               // lit feed-lip edge
    if (ribbed && Math.floor(c.eu / px) === 1) return mul(col, 0.74);                 // rib (middle cell)
    return mul(col, 1.06);
  };
  const lips = c => {
    if (c.face !== 'top') return mul(MAGTOP, isPanel(c) ? 1.25 : 0.9);
    return c.fw > px && c.eu > c.fw - px ? COPPER : BRASS;
  };
  add([0.25, 5.5, -4], [1, 9, -3.25], magPaint(false, 0.92), 'rifle mag (back, taller)');
  add([0.375, 5, -3.875], [0.875, 5.5, -3.375], lips, 'mag feed lips (back)');
  add([0.75, 5.75, -4.25], [2.25, 9, -3.5], magPaint(true), 'rifle mag (front, broad side)');
  add([1, 5.25, -4.125], [2, 5.75, -3.625], lips, 'mag feed lips (front)');
  // loop-faced retention flap: lies on the front mag's broad side under its rib row (the mag's outer cell stays bare
  // beside it, the taller mag shows on its other side), folds over the pouch's elastic mouth and runs on down the pouch
  // front, where it closes (darker bottom row)
  const tab = c => {
    const col = loopFab(c, mul(LOOPC, 1.1));
    if (c.face === 'top') return mul(col, 1.08);
    if (c.face !== 'front') return mul(col, 0.82);
    return c.p[1] > 9.25 - px ? mul(col, 0.8) : col;
  };
  add([0.75, 6.75, -4.5], [1.75, 7.5, -4.25], tab, 'mag retention flap (on the mag)');
  add([0.75, 7.25, -5], [1.75, 7.5, -4.5], tab, 'mag retention flap (fold over the mouth)');
  add([0.75, 7.5, -5], [1.75, 9.25, -4.75], tab, 'mag retention flap (on the pouch)');

  // ================= wearer's left front corner: two stacked flapped pouches with tan buckles =================
  // (the stack's moss starts a little higher and spreads over its lower pouch's bottom half, as in the render)
  const stackBody = c => (c.face === 'top' ? mul(fab(c, FLAPC), 0.98) : soft(c, grime(c, fab(c, POUCH), 0.9, 9, 2), [0.84, 0.9]));
  const stackFlap = c => soft(c, grime(c, fab(c, FLAPC), 0.8, 9, 2), [0.84, 0.9], 0.74);
  const tanBuckle = c => {
    if (c.face === 'top') return mul(BUCK, 1.1);
    if (!isPanel(c)) return mul(BUCK, 0.78);
    return mul(BUCK, (c.ev < px ? 1.05 : 0.82) * G(c, 0.04));                          // female half above, prong below
  };
  for (const [y0, y1] of [[5.25, 8], [8, 11]]) {
    add([2.5, y0, -4.5], [4.5, y1, -3.25], stackBody, 'stacked pouch');
    add([2.5, y0, -4.75], [4.5, y0 + 1, -4.5], stackFlap, 'stacked pouch flap');
    add([3.25, y0 + 0.5, -5], [4.25, y0 + 1.5, -4.5], tanBuckle, 'side-release buckle');
  }
  add([3.25, 11, -4.25], [4.25, 11.75, -3.75], c => edge(c, fab(c, TAB), { stitch: false }), 'hanging strap end');

  // ================= purple marker held by a khaki loop on the first pouch's outer side =================
  // top level with the upper third of the pouch flap; a lighter purple cap (no black clip), darker tip at the bottom
  const marker = c => {
    const v = c.p[1] - c.box.y;
    if (c.face === 'top') return mul(PURP, 1.35);
    if (c.face === 'bottom') return mul(PURP, 0.7);
    const k = (isPanel(c) ? 1 : 0.8) * G(c, 0.05);
    if (v < px) return mul(PURP, 1.2 * k);                                             // cap
    return mul(PURP, k * (v > c.box.h - px ? 0.85 : 1));
  };
  add([-4.5, 7, -3.75], [-4, 11.5, -3.25], marker, 'marker');
  add([-4.75, 8, -4], [-4, 8.5, -3.5], c => edge(c, fab(c, TAB), { stitch: false }), 'marker loop');

  return { S: K.S, px: K.px, cell: K.cell, boxes: B };
};
