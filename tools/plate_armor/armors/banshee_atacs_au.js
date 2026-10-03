// ================= Shellback Tactical Banshee plate carrier (A-TACS AU) — EFT reference =================
// tarkov.dev render 639343fce101f4caa40a4ef3 (x<0 = wearer's right = the render's left). In EFT this carrier wears its
// gear as part of the item model. A-TACS AU everywhere: grey-sand ground with pale sand, olive and grey-brown pebbles.
// (The render's cyan fill light puts a teal rim on every pouch bottom; that is lighting, not fabric, so it is not painted.)
// Proportions: the plate bag is about 1.33x taller than wide (bag y 0.5..11.5); the pouch row ends ~0.75 px above the
// bag bottom so the bag's dark brown hem shows under it, IFAK and placard ending level. Read off the render:
//   straps: tall padded straps, dark brown spacer mesh inside; the wearer's right strap carries a smooth light taupe
//     laminate with diagonal slots, the wearer's left strap a lighter laminate with a Garmin GPS (black body, olive face)
//   upper chest: coyote loop field with rounded top corners, a light khaki MOLLE strip on top and darker webbing rows
//     below; a black CAT tourniquet lies across it (tan keepers, windlass rod along its front, light grey "TIME:" strap
//     at its wearer's-left end, under the GPS strap side)
//   lower front: triple rifle-mag placard with three taupe PMAGs standing well out of it; the two wearer's-left pouch
//     fronts are laser cut (slot rows + two big windows each, a pointed tan pull tab hanging at each one's left seam);
//     in front of the wearer's-right one hang a tan lattice pistol-mag pouch (black pistol mag; a cord lock with two tan
//     pull cords below) and a narrow A-TACS flap pouch with a tan pull tab
//   sides: big A-TACS IFAK on the wearer's right (red-cross patch, pull strap, zipper on its inner edge, handle on top,
//     "B POS" blood-type patch), a big rounded zipped pouch on the cummerbund on the wearer's left
// The back is not visible in the render: MOLLE rows and a drag handle as on the real carrier.
ARMORS.banshee_atacs_au = function (mode) {
  const K = kit('M');                                   // always the 2x style, whatever mode is asked for
  const { edge, G, isPanel, isSide, px } = K;
  const B = [];
  const add = (a, b, mat, tag, opt = {}) => B.push(box('body', a, b, mat, { tag, ...opt }));
  const md = (v, m) => ((v % m) + m) % m;
  const iU = c => Math.floor(c.eu / px + 1e-6), iV = c => Math.floor(c.ev / px + 1e-6);

  // ---------- colours (sampled by eye off the render) ----------
  const LINING = hex('#2d271f');                        // dark brown spacer mesh
  const HEM = hex('#3b342a');                           // dark brown hem of the plate bag under the pouch row
  const LOOPF = hex('#8f7e64'), WEBK = hex('#a39479');  // coyote loop field / light khaki MOLLE strip on it
  const LAM_R = hex('#85786a'), LAM_L = hex('#9c9484'), SLOT = hex('#3a332a');
  const PMAG = hex('#928880'), PMAGD = hex('#655c55'), BRASS = hex('#caa24e'), COPPER = hex('#b5703f');
  const BLK = hex('#232427'), ROD = hex('#393b40'), KEEP = hex('#a08a62'), TAG = hex('#8f918c'), INK = hex('#34363a');
  const TACO = hex('#9c9479'), CORD = hex('#8c7c62'), CLOCK = hex('#6f624d'), PISTOL = hex('#2a2c30');
  const WIN = hex('#4a4037'), PANEL = hex('#888370');   // laser-cut windows (dark brown fabric behind) / panel tone
  const RED = hex('#c23a35'), PATCH = hex('#1f1f21'), POS = hex('#d2cfc6'), ZIP = hex('#2f302b');
  const GPSB = hex('#222725'), GPSF = hex('#6e7250'), GPSS = hex('#a3a887');
  const HANDLE = hex('#4a453c'), HWEB = hex('#8b8874');

  // A-TACS AU: 1-3 px pebbles sampled on the half-pixel grid (no single-cell specks); the last layer is the sparse
  // pale-sand pebbles that give A-TACS its light, pebbly look
  const auAlb = p => layers(snap(p, 0.5).map(v => v * 0.8), 0, '#7b7662',
    [['#8c876f', 0.6, 0.56, 7], ['#706f51', 0.8, 0.6, 23], ['#665f4e', 0.9, 0.66, 51], ['#97917a', 1.1, 0.7, 13], ['#a9a38a', 1.0, 0.68, 37]]);
  const au = (c, k = 1) => mul(auAlb(c.p), k * G(c, 0.06));
  const lining = (c, k = 1) => mul(LINING, k * G(c, 0.1));
  // MOLLE rows: darker webbing band (0.5 px) every 1.5 px, a darker slot every third cell
  const webRows = (c, col, y0, n, hw, h = c.p[0]) => {
    const dy = c.p[1] - y0;
    if (dy < 0 || Math.abs(h) > hw) return null;
    const i = Math.floor(dy / 1.5);
    if (i >= n || dy - i * 1.5 >= 0.5) return null;
    return mul(col, md(h + 0.25, 1.5) < 0.5 ? 0.7 : 0.8);
  };

  // ---------------- carrier ----------------
  // front plate bag: coyote loop field (rounded top corners, A-TACS border) on the upper chest with a light khaki MOLLE
  // strip on top and two darker webbing rows below; plain A-TACS behind the mags; dark brown hem along the bottom
  const BAG1 = 11.5;
  const frontBag = c => {
    if (c.face === 'top' || c.face === 'back') return lining(c);
    if (c.face !== 'front') return edge(c, au(c, 0.9), { stitch: false });
    const x = c.p[0], y = c.p[1], ax = Math.abs(x), slotCol = md(x + 0.25, 1.5) < 0.5;
    if (y > BAG1 - 1) return mul(HEM, G(c, 0.08) * (y > BAG1 - 0.5 ? 0.9 : 1));
    if (y > 1 && y < 5 && ax < 3.75 && !(y < 1.5 && ax > 3.25)) {
      if (y > 1.5 && y < 2 && ax < 2.5) return mul(WEBK, 1.05 * G(c, 0.05) * (slotCol ? 0.88 : 1));
      if ((y > 3 && y < 3.5) || (y > 4.5 && y < 5)) return ax < 3.5 ? mul(LOOPF, 0.85 * G(c, 0.05) * (slotCol ? 1.12 : 1)) : mul(LOOPF, G(c, 0.1));
      return mul(LOOPF, G(c, 0.1));
    }
    return edge(c, au(c), { stitch: false });
  };
  add([-4.25, 0.5, -3.25], [4.25, BAG1, -2.5], frontBag, 'front plate bag');
  const backBag = c => {
    if (c.face === 'top' || c.face === 'front') return lining(c);
    const col = au(c);
    return edge(c, (c.face === 'back' && webRows(c, col, 2, 6, 3.25)) || col, { stitch: false });
  };
  add([-4.25, 0.5, 2.5], [4.25, BAG1, 3.25], backBag, 'back plate bag');
  const cumm = c => {
    const col = au(c, 0.95);
    return edge(c, (isSide(c) && webRows(c, col, 5.5, 4, 2, c.p[2])) || col, { stitch: false });
  };
  // ends 0.25 above the bags' bottoms so the bottom faces never share a plane
  add([-4.5, 4.5, -2.75], [4.5, BAG1 - 0.25, 2.75], cumm, 'cummerbund');
  add([-1.25, 0.5, 3.25], [1.25, 1.5, 3.75], c => edge(c, mul(HANDLE, G(c, 0.06)), { stitch: false }), 'drag handle');

  // ---------------- padded shoulder straps ----------------
  // the head hides the straps' upper run: a padded hump on top of each shoulder beside the head, and the risers
  // below the chin carrying the laminates (the render's slotted taupe one / the lighter one with the GPS)
  const hump = c => {
    if (c.face === 'bottom') return lining(c, 1.2);
    const col = au(c, 1.02);
    if (isPanel(c)) return mul(col, 0.88);
    return c.face === 'top' ? mul(col, 1.08) : col;
  };
  const riserF = s => c => {
    if (c.face === 'back') return lining(c);
    if (c.face === 'bottom') return mul(au(c), 0.72);
    if (c.face !== 'front') return edge(c, au(c, 0.92), { stitch: false });
    const x = c.p[0], ax = Math.abs(x), j = iV(c);
    if (ax > 4 || ax < 2.5) return mul(au(c, 1.04), c.ev > c.fh - px ? 0.8 : 1);   // padded A-TACS edges of the strap
    if (s > 0) return mul(LAM_L, G(c, 0.05) * (j === 0 ? 1.08 : j === 3 ? 0.9 : 1));
    // wearer's right: smooth light taupe laminate, lit top row, two slot dashes offset diagonally (the lower one nearer
    // the middle, following the strap's run down the chest)
    const slot = (j === 1 && x < -3) || (j === 3 && x > -3.5);
    return slot ? mix(LAM_R, SLOT, 0.75) : mul(LAM_R, G(c, 0.04) * (j === 0 ? 1.1 : 1));
  };
  const riserB = c => {
    if (c.face === 'front') return lining(c);
    return edge(c, au(c, 0.96), { stitch: false });
  };
  for (const s of [-1, 1]) {
    const X = (a, b) => (s < 0 ? [a, b] : [-b, -a]);
    add([X(-5.75, -4.25)[0], -1.25, -3.5], [X(-5.75, -4.25)[1], -0.25, 3.5], hump, 'shoulder pad');
    add([X(-5.5, -4.5)[0], -1.75, -3.25], [X(-5.5, -4.5)[1], -0.75, 3.25], hump, 'shoulder pad crown');
    const [r0, r1] = X(-4.5, -2);
    add([r0, -0.25, -3.75], [r1, 1.75, -2.75], riserF(s), 'strap front');
    add([r0, -0.25, 2.75], [r1, 1.75, 3.5], riserB, 'strap back');
  }
  // Garmin GPS on the wearer's left laminate: black body, olive face, light screen; tilted, outer end lower
  const gps = c => {
    if (c.face === 'back') return null;
    if (c.face !== 'front') return mul(GPSB, c.face === 'top' ? 1.5 : 1);
    const i = iU(c), j = iV(c);
    if ((i === 1 || i === 2) && j === 0) return mul(GPSS, G(c, 0.04));
    return mul(GPSF, (i === 0 ? 1.08 : j === 1 ? 0.86 : 1) * G(c, 0.04));
  };
  add([2.25, 0.5, -4.25], [4.25, 1.5, -3.75], gps, 'GPS', { rot: [0, 0, 12], pivot: [3.25, 1, -4] });

  // ---------------- tourniquet across the upper chest ----------------
  // black CAT lying on the loop field, rising a little toward the wearer's left; tan keepers, the windlass rod along
  // its front runs through the light grey "TIME:" strap, which sits under the GPS strap side as in the render
  const TQ = { rot: [0, 0, -6], pivot: [0, 3, -3.5] };
  const tqBody = c => {
    const col = mul(BLK, G(c, 0.06));
    if (c.face === 'top') return mul(col, 1.4);
    if (c.face !== 'front') return mul(col, 0.85);
    const i = iU(c), j = iV(c);
    if (i === 3 || i === 6) return mul(KEEP, G(c, 0.05) * (j === 0 ? 1.08 : 0.92));
    return j === 0 ? mul(col, 1.3) : col;
  };
  add([-2.25, 2.5, -3.75], [1.75, 3.5, -3.25], tqBody, 'tourniquet', TQ);
  add([-1.75, 2.75, -4], [3.25, 3.25, -3.75], c => mul(ROD, c.face === 'top' ? 1.35 : c.face === 'bottom' ? 0.7 : G(c, 0.05)), 'windlass rod', TQ);
  const tag = c => {
    const col = mul(TAG, G(c, 0.05));
    if (c.face === 'top') return mul(col, 1.1);
    if (!isPanel(c)) return mul(col, 0.8);
    return c.face === 'front' && iU(c) === 1 && iV(c) >= 1 && iV(c) <= 2 ? mix(col, INK, 0.65) : col;
  };
  add([1.75, 2, -4.25], [2.75, 4, -3.25], tag, 'TIME strap', TQ);

  // ---------------- triple rifle-mag placard ----------------
  // 16 cells from x -3.25: cells 0-5 behind the two small pouches, then two laser-cut pouch fronts of 5 cells each
  // (dark seam, window, bar, window, frame): a short slot above and below each window, binding on top, open mouths
  const PX0 = -3.25, PY0 = 6.75, PY1 = 10.75;
  const placard = c => {
    if (c.face === 'top') return c.ex < px ? mul(au(c), 1.12) : lining(c, 1.2);
    if (c.face === 'back') return lining(c, 1.1);
    const col = au(c);
    if (c.face !== 'front') return edge(c, col, { stitch: false });
    const k = Math.floor((c.p[0] - PX0) / px + 1e-6), j = iV(c);
    if (k < 6) return j === 7 ? mul(col, 0.85) : col;
    // the laser-cut fronts are a flatter, lighter panel than the surrounding camo
    const i = (k - 6) % 5, pc = mix(col, mul(PANEL, G(c, 0.05)), 0.55), win = i === 1 || i === 3;
    if (i === 0) return mul(pc, 0.74);                                          // seam between the fronts
    if (j === 0) return mul(pc, 1.1);                                           // top binding
    if (j === 7) return mul(pc, 0.86);                                          // lower edge
    if (win && (j === 3 || j === 4)) return mul(WIN, G(c, 0.06) * (j === 3 ? 0.88 : 1));   // window (shaded top)
    if (win && (j === 1 || j === 6)) return mul(pc, 0.84);                      // short slots above / below it
    return pc;
  };
  add([PX0, PY0, -4], [4.75, PY1, -2.75], placard, 'mag placard');
  // pointed tan pull tabs hanging from the bottom of each laser-cut front's left seam
  const pullTab = c => {
    if (c.face === 'back') return null;
    const col = mul(TACO, G(c, 0.05));
    if (c.face !== 'front') return mul(col, 0.8);
    return mul(col, c.ev > c.fh - px ? 0.86 : 1.04);
  };
  for (const x0 of [-0.25, 2.25]) add([x0, PY1 - 0.25, -4.25], [x0 + 0.5, PY1 + 0.5, -4], pullTab, 'pouch pull tab');
  // taupe PMAGs, broad side forward, 1.75 px down in the placard: lit top edge, grip band, rib, recessed panel;
  // a narrower feed-lip block on top with one brass round (copper tip on the wearer's left)
  const pmag = c => {
    if (c.face === 'bottom') return mul(PMAG, 0.6);
    if (c.face === 'top') return mul(PMAG, c.ex < px ? 1.25 : 1.1);
    const col = mul(PMAG, G(c, 0.04));
    if (!isPanel(c)) return mul(col, 0.74);
    const i = iU(c), j = iV(c), n = Math.round(c.fw / px);
    if (j === 0) return mul(col, 1.22);
    if (j === 1) return mul(col, 0.84);
    if (j >= 3 && i > 0 && i < n - 1) return mul(col, 0.9);
    return col;
  };
  const lips = c => (c.face === 'top' ? (c.eu > c.fw - px ? COPPER : BRASS) : mul(PMAGD, isPanel(c) ? 1 : 0.85));
  for (const cx of [-1.75, 1, 3.5]) {
    add([cx - 1, 4.5, -3.75], [cx + 1, 8.5, -3.25], pmag, 'rifle mag');
    add([cx - 0.5, 4, -3.625], [cx + 0.5, 4.5, -3.375], lips, 'rifle mag feed lips');
  }

  // ---------------- small pouches in front of the first mag pouch ----------------
  // tan lattice pistol-mag pouch: a plain light tan frame (side rails, lit top row, darker bottom row) with stacked X
  // crossings only in the middle column (light crossing / dark gap, 1 px each); black pistol mag in it
  const taco = c => {
    if (c.face === 'top') return c.ex < px ? mul(TACO, 1.1) : lining(c, 1.3);
    const col = mul(TACO, G(c, 0.05));
    if (c.face === 'bottom') return mul(col, 0.72);
    if (!isPanel(c)) return mul(col, 0.78);
    const i = iU(c), j = iV(c);
    if (j === 0) return mul(col, 1.15);
    if (c.ev > c.fh - px) return mul(col, 0.86);
    if (i !== 1) return col;
    return mul(col, Math.floor((j - 1) / 2) % 2 === 0 ? 1.1 : 0.78);
  };
  add([-3.25, PY0 + 1, -4.75], [-1.75, PY1, -4], taco, 'pistol mag pouch');
  const pistol = c => {
    if (c.face === 'top') return c.eu > c.fw - px ? COPPER : BRASS;
    if (c.face === 'bottom') return mul(PISTOL, 0.6);
    const col = mul(PISTOL, G(c, 0.05));
    if (!isPanel(c)) return mul(col, 0.8);
    if (iV(c) === 0) return mul(col, 1.4);
    return iU(c) === 1 ? mul(col, 0.85) : col;
  };
  add([-3, 6, -4.625], [-2, 9, -4.125], pistol, 'pistol mag');
  // cord lock right under the pouch, two tan pull cords hanging from it (lighter knotted ends)
  add([-3, PY1, -4.625], [-2, PY1 + 0.5, -4.125], c => mul(CLOCK, (c.face === 'top' ? 1.1 : isPanel(c) ? 1 : 0.82) * G(c, 0.05)), 'cord lock');
  const cord = c => mul(CORD, (c.p[1] > 12 ? 1.12 : 1) * (isPanel(c) ? G(c, 0.05) : 0.8));
  add([-2.875, PY1 + 0.5, -4.5], [-2.625, 12.5, -4.25], cord, 'pull cord');
  add([-2.375, PY1 + 0.5, -4.5], [-2.125, 12.25, -4.25], cord, 'pull cord');
  // narrow A-TACS flap pouch: flap from the top down to its tan pull tab
  const flapPouch = c => {
    if (c.face === 'top') return mul(au(c), 1.12);
    if (c.face === 'back') return lining(c, 1.1);
    let col = au(c);
    if (c.face !== 'front') return edge(c, col, { stitch: false });
    const i = iU(c), j = iV(c);
    if (j === 0) col = mul(col, 1.1);
    else if (j === 7) col = mul(col, 0.74);
    else if (c.ev > c.fh - px) col = mul(col, 0.85);
    if (i === 2) col = mul(col, 0.88);
    return col;
  };
  // (hangs 0.25 below the placard so their bottoms never share a plane)
  add([-1.75, 5.75, -4.5], [-0.25, PY1 + 0.25, -3.75], flapPouch, 'flap pouch');
  add([-1.5, 8.75, -4.75], [-0.5, 9.75, -4.5], c => (c.face === 'back' ? null : mul(TACO, (c.face === 'front' ? (iV(c) === 1 ? 0.78 : 1.02) : 0.8) * G(c, 0.05))), 'flap pull tab');

  // ---------------- IFAK (wearer's right front corner) ----------------
  // red-cross patch, grey-green pull strap, zipper along the inner edge, lid seam under the patch; ends level with the
  // placard. The "B POS" patch (beside the cross on the render) goes on the outer side, the front is too narrow for both
  const IY0 = 5.25;
  const ifak = c => {
    if (c.face === 'back') return lining(c, 1.1);
    let col = au(c);
    if (c.face === 'top') return mul(col, 1.1);
    if (c.face === 'bottom') return mul(col, 0.72);
    const i = iU(c), j = iV(c);
    if (c.face === 'front') {
      if (i === 4) return mix(col, ZIP, j === 0 ? 0.4 : 0.62);
      if (i === 3) col = mul(mix(col, HWEB, 0.45), 0.92);
      else if (j >= 1 && j <= 3) return (i === 1 || j === 2) ? mul(RED, G(c, 0.04)) : mul(PATCH, G(c, 0.05));
      else if (j === 4) col = mul(col, 0.78);
      if (j === 0) col = mul(col, 1.1);
      else if (c.ev > c.fh - px) col = mul(col, 0.86);
      return col;
    }
    // outer side: black "B POS" patch one camo column back from the front edge, level with the red-cross patch
    // (black rows above and below; red blood drop + light "POS" text in its middle row)
    if (c.face === 'right' && (i === 1 || i === 2) && j >= 1 && j <= 3) {
      if (j !== 2) return mul(PATCH, G(c, 0.05));
      return i === 1 ? mul(RED, G(c, 0.04)) : mul(POS, G(c, 0.04));
    }
    return edge(c, col, { stitch: false });
  };
  add([-5.75, IY0, -4.5], [-3.25, PY1, -2.5], ifak, 'IFAK');
  const ifakHandle = c => {
    if (c.face === 'bottom') return null;
    const col = mul(HWEB, G(c, 0.06));
    if (isPanel(c)) return iV(c) === 1 && iU(c) === 1 ? null : mul(col, iV(c) === 0 ? 1.08 : 1);
    return mul(col, c.face === 'top' ? 1.12 : 0.8);
  };
  add([-5.25, IY0 - 1, -3.75], [-3.75, IY0, -3.25], ifakHandle, 'IFAK handle');

  // ---------------- big rounded pouch on the cummerbund (wearer's left) ----------------
  // about as tall as the IFAK, pressed against the placard and the cummerbund; rounded outer corners, a dark zip
  // binding along the front edge of its outer face
  const sideP = c => {
    if (c.face === 'back') return lining(c, 1.1);
    const col = au(c);
    if (c.face === 'top') return mul(col, 1.1);
    if (c.face === 'bottom') return mul(col, 0.72);
    const i = iU(c), j = iV(c), n = Math.round(c.fh / px);
    if (c.face === 'front') {
      const outer = i === 2, end = j === 0 || j === n - 1;
      if (outer && end) return mul(col, 0.78);
      if (j === 0) return mul(col, 1.1);
      if (j === n - 1) return mul(col, 0.86);
      return outer ? mul(col, 0.92) : col;
    }
    if (c.face === 'left' && i === 0) return mix(col, ZIP, j === 0 || j === n - 1 ? 0.35 : 0.5);
    return edge(c, col, { stitch: false });
  };
  // bottom 0.25 below the placard's so the two never share a plane where they overlap
  add([4.5, 6, -3.75], [6, PY1 + 0.25, -2.5], sideP, 'side pouch (left)');
  return { S: K.S, px: K.px, cell: K.cell, boxes: B };
};
