// ================= Eagle Industries MMAC plate carrier (Ranger Green) — EFT reference (tarkov.dev 61bc85697113f767765c7fe7) =================
// In EFT this is an armored vest whose item model carries its gear. Read off the render (x<0 = wearer's right):
//   one grey, low-saturation Ranger Green; front plate bag with shooter's-cut upper corners, a lit top rim and a small
//   grab tab at the top centre; upper chest = darker olive loop field (ending just under the patch, plain fabric behind
//   the mag tops) framed by two webbing rows (a thin one along its top, one across the whole bag)
//   with the dark strap ends running down either side of it; black diamond patch (grey wolf, orange "26-73") right of
//   centre; padded shoulder straps: light padded covers over the shoulders with dark elastic loops, dark webbing straps
//   down to the bag. Front: one wide triple open-top mag pouch with three MOLLE rows (webbing a shade darker than the
//   panel, bar-tacked) holding three black PMAGs, each with a light shock cord (knot on the pouch front, black pull tab
//   above the mag). Wearer's right: radio pouch in the same Ranger Green (two webbing straps) with a red handheld radio (grey LCD,
//   black upper-right cap, black stub antenna); outboard of it a grey-green kydex rifle-mag carrier (hole column)
//   holding a tall dark steel magazine. Wearer's left: slim olive pouch whose mouth is closed by a black kydex collar
//   (red pull tab) with a dark steel mag rising out; a bungee cord with a black toggle hangs under it. MOLLE cummerbund
//   and back; two dark strap tabs hang under the bag (a long one on the wearer's right, a short one near the centre).
ARMORS.mmac_ranger_green = function (mode) {
  const K = kit('M');                                   // always the 2x style
  const { edge, G, isPanel, isSide, px } = K;
  const B = [];
  const add = (a, b, mat, tag, opt = {}) => B.push(box('body', a, b, mat, { tag, ...opt }));

  // ---------- palette (sampled from the render; the grey Ranger Green keeps its low saturation) ----------
  const FAB = hex('#646859'), LOOP = hex('#515243'), WEBC = hex('#4c5244'), STRAP = hex('#4b4f43'), PAD = hex('#7b7d69');
  const RPC = mul(FAB, 0.96), LINING = hex('#1e211c'), ELASTIC = hex('#3d4036');
  const PMAG = hex('#313437'), STEEL = hex('#3c4448'), MAGTOP = hex('#25282b'), BRASS = hex('#caa24e'), COPPER = hex('#b5703f');
  const KYD = hex('#5a665e'), KHOLE = hex('#1c201e'), BLKK = hex('#232527');
  const RED = hex('#b3262b'), SCREEN = hex('#8f9d99'), RBLK = hex('#26292c');
  const CORD = hex('#6e7265'), TAB = hex('#2b2f2c');
  const DK = hex('#222222'), DG = hex('#8d8e89'), DO = hex('#d2693a');

  // Cordura: faint low-frequency mottling + per-cell grain
  const fab = (c, base, amt = 0.06) => {
    const q = snap(c.p, 0.5);
    const n = fbm(q[0] * 0.45 + 7, q[1] * 0.45 + 1, q[2] * 0.45 + 3);
    return mul(base, (0.97 + 0.06 * n) * G(c, amt));
  };
  const md = (v, m) => ((v % m) + m) % m;
  // MOLLE row pair: a 2-cell band a shade darker than the panel (lower cell darker), a bar tack every `tp` px;
  // rows every 1.5 px from y0 (one plain cell between rows). h = horizontal coordinate along the face.
  const molle = (c, col, y0, n, h, h0, tp = 1.5, lo = 0.84) => {
    const dy = c.p[1] - y0;
    if (dy < 0) return null;
    const i = Math.floor(dy / 1.5);
    if (i >= n) return null;
    const r = dy - i * 1.5;
    if (r >= 1) return null;
    const w = mix(col, WEBC, 0.55), tack = md(h - h0, tp) < 0.5;
    return mul(w, (r < 0.5 ? 0.97 : lo) * (tack ? 0.84 : 1));
  };

  // ================= carrier =================
  // front plate bag: lit rim, loop field + webbing rows, strap ends, diamond patch
  // black diamond (pointed tips), grey wolf mark on top, orange "26 | 73" split by the dark centre line
  // (2-cell tips: 1-cell tips turned the patch into a cross); it sits inside the loop field, centred on the webbing
  // row across the bag, clear of the strap ends. (Light tan tips for the thin border were tried: they left a dark
  // 4x3 block that read as a rectangle, so the tips stay dark.)
  const DIA = ['..KK..', '.KGGK.', 'KOOOOK', '.KKKK.', '..KK..'];  // 6x5 cells at x -0.25..2.75, y 2.25..4.75
  const diamond = c => {
    const i = Math.floor((c.p[0] + 0.25) / 0.5), j = Math.floor((c.p[1] - 2.25) / 0.5);
    if (i < 0 || i > 5 || j < 0 || j > 4) return null;
    const ch = DIA[j][i];
    return ch === '.' ? null : mul(ch === 'K' ? DK : ch === 'G' ? DG : DO, G(c, 0.04));
  };
  // the bag has the original's shooter's cut: a narrower top step on a full-width body, so the upper corners step in
  // (the outline follows the cut: lit top rims on the step and on the body's shoulders, dark sides)
  const bagF = step => c => {
    const x = c.p[0], y = c.p[1], ax = Math.abs(x);
    if (c.face === 'top') return step || ax < 3.75 ? mul(LINING, G(c, 0.1)) : mul(fab(c, FAB), 1.1);   // dark lining at the neckline, lit shoulders
    let col = fab(c, FAB);
    if (c.face !== 'front') return edge(c, col);
    const d = diamond(c);
    if (d) return d;
    if (y >= 3.25 && y < 3.75) col = mul(fab(c, mix(LOOP, FAB, 0.45)), md(x + 4.25, 1.5) < 0.5 ? 0.86 : 1);   // webbing row across the bag
    else if (ax < 2.75 && y >= 2.25 && y < 2.75) col = fab(c, mix(LOOP, FAB, 0.6));                            // thin row along the loop field top
    else if (ax < 2.75 && y >= 2.75 && y < 4.75) col = fab(c, LOOP);                                            // loop field (ends under the patch)
    else if (ax >= 2.75 && ax < 3.75 && y < 3.25) col = fab(c, STRAP);                                          // strap ends
    else if (y < 2.25) col = mul(col, 1.05);                                                                    // lit top of the bag
    if (step) return c.ev < px ? mul(col, 1.1) : ax > 3.25 ? mul(col, 0.72) : col;
    if (ax > 3.75 && c.ev < px) return mul(col, 1.1);
    return ax > 3.75 || c.ev > c.fh - px ? mul(col, 0.72) : col;
  };
  add([-3.75, 1.25, -3.25], [3.75, 2.25, -2.5], bagF(true), 'front plate bag (cut top)');
  // (the bag runs to just above the belt: the original is clearly taller than wide, a 9 px bag read as a square block)
  add([-4.25, 2.25, -3.25], [4.25, 11.25, -2.5], bagF(false), 'front plate bag');
  // grab tab at the top centre
  add([-0.5, 1.5, -3.5], [0.5, 2, -3.25], c => edge(c, mul(fab(c, STRAP), c.face === 'front' ? 1 : 0.85), { stitch: false }), 'grab tab');
  // the webbing row across the bag runs on past both side edges as short dark tabs (their inner faces sit on the bag's
  // side planes but face the other way)
  const sideTab = c => mul(fab(c, STRAP, 0.05), c.face === 'front' ? 0.95 : c.face === 'top' ? 1.05 : 0.8);
  add([-4.75, 3.25, -3.125], [-4.25, 3.75, -2.625], sideTab, 'webbing side tab (right)');
  add([4.25, 3.25, -3.125], [4.75, 3.75, -2.625], sideTab, 'webbing side tab (left)');

  const bagB = c => {
    if (c.face === 'top') return mul(LINING, G(c, 0.1));
    const col = fab(c, FAB);
    return edge(c, (c.face === 'back' && molle(c, col, 3.25, 5, -c.p[0], -3.5)) || col);
  };
  add([-4.25, 0.75, 2.5], [4.25, 11.25, 3.25], bagB, 'back plate bag');
  add([-1.25, 0.5, 3.25], [1.25, 1.5, 3.75], c => edge(c, fab(c, STRAP), { stitch: false }), 'drag handle');

  // cummerbund with MOLLE on the sides; ends 0.25 above the bags' bottoms
  const cumm = c => {
    const col = mul(fab(c, FAB), 0.94);
    return edge(c, (isSide(c) && Math.abs(c.p[2]) < 2.5 && molle(c, col, 5.5, 3, c.p[2], -2.5)) || col, { stitch: false });
  };
  add([-4.5, 4.75, -2.75], [4.5, 11, 2.75], cumm, 'cummerbund');
  // dark strap tabs hanging under the bag: a long one under the radio pouch (wearer's right corner), a short one just
  // to the wearer's left of centre
  const hang = c => edge(c, mul(STRAP, 0.7 * G(c, 0.06)), { stitch: false });
  add([-4, 11, -3], [-3.5, 12.5, -2.75], hang, 'hanging strap tab (long)');
  add([0.25, 11, -3], [0.75, 11.75, -2.75], hang, 'hanging strap tab (short)');

  // ================= shoulder straps =================
  // light padded covers on top of the shoulders (stepped dome, small dark olive elastic loops on the crown in two
  // groups); dark webbing risers below the chin and at the back (they stop 0.25 into the head)
  // (1 px loops with 1-cell gaps in a soft olive: 1-cell loops read as hazard-tape stripes from above)
  const LOOPS = [[-2.75, -1.75], [-1.25, -0.25], [0.75, 1.75]], ELOOP = mix(PAD, ELASTIC, 0.6);
  const hump = crown => c => {
    if (c.face === 'bottom') return mul(LINING, 1.2);
    const col = fab(c, PAD, 0.07);
    if (isPanel(c)) return mul(col, 0.88);
    if (c.face !== 'top') return col;
    if (crown) {
      const z = c.p[2];
      if (LOOPS.some(([a, b]) => z >= a && z < b)) return mul(ELOOP, G(c, 0.08));     // elastic loops (2 + 1)
    }
    return mul(col, 1.06);
  };
  const riser = c => {
    if (c.face === 'bottom') return mul(LINING, 1.2);
    return edge(c, fab(c, STRAP, 0.07), { stitch: false });
  };
  for (const s of [-1, 1]) {
    const X = (a, b) => (s < 0 ? [a, b] : [-b, -a]);
    const [b0, b1] = X(-5.75, -4.25), [h0, h1] = X(-5.5, -4.5), [r0, r1] = X(-3.75, -2.25), [k0, k1] = X(-4.5, -2.25);
    add([b0, -1.25, -3.5], [b1, -0.25, 3.5], hump(false), 'shoulder pad');
    add([h0, -1.75, -3.25], [h1, -0.75, 3.25], hump(true), 'shoulder pad crown');
    add([r0, -0.25, -3.5], [r1, 1.25, -2.5], riser, 'strap front');
    // (0.25 wider than the back bag and its inner face inside the bag, so no side / inner face shares the bag's planes)
    add([k0, -0.25, 2.75], [k1, 1.5, 3.5], riser, 'strap back');
  }

  // ================= triple mag pouch + three PMAGs =================
  const tri = c => {
    if (c.face === 'top') return c.ex < px ? mul(FAB, 1.1) : mul(LINING, 1.3);   // open mouth: light rim, dark inside
    let col = fab(c, FAB);
    if (c.face === 'front') {
      if (c.ev < px) return mul(col, 1.12);                                     // top binding
      // three full webbing rows under the binding, the last one ending just above the bottom rim; two tacks per row
      // (three equal segments), lighter lower cell
      col = molle(c, col, 7, 3, c.p[0], -1.25, 2, 0.9) || col;
    }
    return edge(c, col);
  };
  add([-2.75, 6.5, -4.75], [2.75, 11.25, -3.25], tri, 'triple mag pouch');
  // magazines: body + narrower feed-lip block with one brass round; broad side forward, darker spine sides, one rib
  const magPaint = (base, ribs, capped = false) => c => {
    if (c.face === 'bottom') return mul(base, 0.6);
    if (c.face === 'top' && capped) return mul(MAGTOP, c.ex < px ? 1.5 : 1);
    if (c.face === 'top') {
      const n = Math.max(1, Math.round(c.fw / px)), i = Math.min(n - 1, Math.floor(c.eu / px));
      const lo = Math.floor((n - 1) / 2), hi = Math.floor(n / 2);
      if (i >= lo && i <= hi) return i === hi && n >= 2 ? COPPER : BRASS;
      return mul(MAGTOP, c.ex < px ? 1.5 : 1);
    }
    let col = mul(base, G(c, 0.04));
    if (!isPanel(c)) return mul(col, 0.74);
    if (c.ev < px) return mul(col, 1.3);                                         // feed-lip edge
    if (ribs) { const n = Math.max(1, Math.round(c.fw / px)), i = Math.floor(c.eu / px); if (i === Math.floor(n / 2)) col = mul(col, 0.8); }
    return col;
  };
  const lips = c => (c.face !== 'top' ? mul(MAGTOP, isPanel(c) ? 1.2 : 0.95) : (c.eu > c.fw - px ? COPPER : BRASS));
  const lipsN = c => (c.face === 'top' ? BRASS : lips(c));                     // one-cell lips of the slim steel mags
  const cord = c => mul(CORD, (c.face === 'front' ? 1 : c.face === 'top' ? 1.1 : 0.8) * G(c, 0.05));
  for (const cx of [-1.75, 0, 1.75]) {
    add([cx - 0.75, 5.5, -4.5], [cx + 0.75, 8, -3.75], magPaint(PMAG, true, true), 'PMAG');          // 1 px shows, 1.5 px in the pouch
    add([cx - 0.5, 5, -4.375], [cx + 0.5, 5.5, -3.875], lips, 'PMAG feed lips');
    // shock cord down the middle of the mag: black pull tab on the bag, standing clear above the mag top; cord over
    // the top and down the mag and pouch front to a knot on the pouch
    const t0 = cx - 0.25, k0 = cx - 0.125;
    add([t0, 4, -3.5], [t0 + 0.5, 5.5, -3.25], K.plastic(TAB), 'pull tab');
    add([k0, 4.75, -4.75], [k0 + 0.25, 5, -3.5], cord, 'shock cord (over the top)');
    add([k0, 5, -4.75], [k0 + 0.25, 6.5, -4.5], cord, 'shock cord (on the mag)');
    add([k0, 6.5, -5], [k0 + 0.25, 7.75, -4.75], cord, 'shock cord (on the pouch)');
    add([t0, 7.75, -5.25], [t0 + 0.5, 8, -4.75], c => mul(CORD, c.face === 'top' ? 1.15 : isPanel(c) ? 1.08 : 0.85), 'cord knot');
  }

  // ================= wearer's right: radio pouch + red radio, kydex rifle-mag carrier =================
  const rpouch = c => {
    if (c.face === 'top') return c.ex < px ? mul(RPC, 1.1) : mul(LINING, 1.3);
    let col = fab(c, RPC);
    if (c.face === 'front') {
      if (c.ev < px) col = mul(col, 1.12);
      else if ((c.ev >= 0.5 && c.ev < 1) || (c.ev >= 2.5 && c.ev < 3)) col = mul(mix(col, WEBC, 0.5), 0.86);   // two webbing straps
    }
    return edge(c, col);
  };
  add([-4.75, 7.25, -4.25], [-2.75, 11, -3.25], rpouch, 'radio pouch');
  // radio: red body, grey LCD, black upper-right cap (3 cells wide; the lower part sits 1.5 px down in the pouch);
  // red stays the main colour, the screen is one row
  const RM = ['RRK', 'SSK', 'RRR'];
  const radio = c => {
    const i = Math.min(2, Math.floor((c.p[0] + 4.5) / 0.5)), j = Math.floor((c.p[1] - 5.75) / 0.5);
    if (c.face === 'top') return i === 2 ? mul(RBLK, 1.3) : mul(RED, 1.1);
    if (c.face === 'bottom') return mul(RED, 0.6);
    const ch = c.face === 'front' && j >= 0 && j < 3 ? RM[j][i] : (i === 2 && j < 2 ? 'K' : 'R');
    const col = ch === 'K' ? RBLK : ch === 'S' ? SCREEN : RED;
    return mul(col, (c.face === 'front' ? 1 : 0.8) * G(c, 0.04));
  };
  add([-4.5, 5.75, -4], [-3, 8.75, -3.5], radio, 'radio');
  add([-3.5, 3.75, -3.875], [-3, 5.75, -3.625], K.plastic(RBLK), 'radio antenna');
  // (the render's two thin dark bungee cords over the radio face are left out: at this size they covered a third of
  // the small radio and turned it into a striped block)
  // kydex carrier (grey-green plastic, a column of holes), outboard of the radio pouch and partly behind it; a tall
  // dark steel rifle mag stands in it
  const kydex = c => {
    let col = mul(KYD, G(c, 0.05));
    if (c.face === 'top') return c.ex < px ? mul(KYD, 1.15) : mul(LINING, 1.2);
    if (c.face === 'front') {
      const i = Math.floor((c.p[0] + 5.75) / 0.5), j = Math.floor((c.p[1] - 7) / 0.5);
      if (j === 0) return mul(col, 1.15);                                       // lit rim
      if (i === 1 && (j === 2 || j === 4 || j === 6)) return KHOLE;              // holes
      if (i === 1) col = mul(col, 0.8);                                          // recessed channel between them
    } else if (!isPanel(c)) col = mul(col, 0.82);
    return col;
  };
  // (pressed back against the sleeve and the cummerbund corner)
  add([-5.75, 7, -3.5], [-4.25, 11.25, -2.5], kydex, 'kydex mag carrier');
  add([-5.5, 4.5, -3.25], [-4.5, 8, -2.75], magPaint(STEEL, true, true), 'rifle mag (kydex)');
  add([-5.25, 4, -3.125], [-4.75, 4.5, -2.875], lipsN, 'rifle mag feed lips (kydex)');

  // ================= wearer's left: slim pouch, black kydex collar with red tab, steel mag =================
  const slim = c => {
    if (c.face === 'top') return c.ex < px ? mul(FAB, 1.1) : mul(LINING, 1.3);
    let col = fab(c, mul(FAB, 0.95));
    if (c.face === 'front') {
      if (c.ev < px) col = mul(col, 1.12);
      else if ((c.ev >= 1 && c.ev < 1.5) || (c.ev >= 2.5 && c.ev < 3)) col = mul(col, 0.72);   // shock cords across
    }
    return edge(c, col);
  };
  add([2.75, 7.25, -4.25], [4.25, 11.5, -3.25], slim, 'slim pouch');
  add([3, 5.25, -4], [4, 9.25, -3.5], magPaint(STEEL, true, true), 'rifle mag (slim pouch)');
  add([3.25, 4.75, -3.875], [3.75, 5.25, -3.625], lipsN, 'rifle mag feed lips (slim pouch)');
  const collar = c => {
    if (c.face === 'top') return mul(BLKK, c.ex < px ? 1.5 : 1.2);
    const j = Math.floor((c.p[1] - 7) / 0.5), inner = c.p[0] < 3.5;
    if (c.face === 'front' && j === 0 && inner) return mul(RED, G(c, 0.05));   // red pull tab
    return mul(BLKK, (isPanel(c) ? 1 : 0.85) * G(c, 0.05));
  };
  add([2.875, 7, -4.375], [4.125, 8, -3.375], collar, 'black kydex collar');
  add([3.5, 11.25, -3.875], [3.75, 11.75, -3.625], K.solid(RBLK, 0.05), 'bungee cord');
  add([3.375, 11.75, -4], [3.875, 12.25, -3.5], K.plastic(RBLK), 'cord toggle');   // just under the pouch, below the bag's bottom edge

  return { S: K.S, px: K.px, cell: K.cell, boxes: B };
};
