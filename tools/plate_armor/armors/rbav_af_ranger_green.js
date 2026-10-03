// ================= ECLiPSE RBAV-AF plate carrier (Ranger Green) — EFT reference (tarkov.dev 628dc750b910320f4c27a732) =================
// In EFT this is an armored rig: the gear is part of the item model. Reference render (front, turned toward the wearer's
// right), x<0 = wearer's right = viewer's left:
//   one flat grey-olive Ranger Green (much greyer than Crye's), mesh lining inside; broad padded shoulder straps whose
//   lighter front ends frame a recessed yoke with a loop patch and a short webbing tab under it;
//   upper chest: two elastic caddies of five 12ga shells each (lying across, brass heads / pale plastic ends mixed) on the
//   dark MOLLE, a double flap pouch with khaki pull tabs on the wearer's left;
//   middle: triple open-top mag pouch covered in webbing rows, a bar tack in the middle of each pocket (black AR mag on
//   the wearer's right, two tan PMAGs), black tourniquet with a grey "TIME:" strap and a polymer pistol-mag pouch
//   (shock-cord lacing, dangling pull cord) at the wearer's-right front corner, IFAK with a red medic cross, "A+ NKDA" and
//   a T pull handle on the wearer's-right side; wearer's-left front corner: pouch with two black pistol mags and a vertical
//   caddy of five shells seen end-on on its outer edge;
//   below: a wide drop pouch (zip across the top, pull on the left, deep lopsided U bottom) with a loop patch carrying five
//   more shells standing up (brass on top).
// Side pieces sit on the carrier's front corners (Minecraft arms cover the torso sides): the IFAK is seated on the
// wearer's-right front corner with the pistol pouch layered in front of its inboard edge, and the end-on shells sit on the
// front of the wearer's-left pistol pouch; nothing reaches past x = +-5.
ARMORS.rbav_af_ranger_green = function (mode) {
  const K = kit('M');                                   // always the 2x style
  const { edge, G, isPanel, isSide, px } = K;
  const B = [];
  const add = (a, b, mat, tag, opt = {}) => B.push(box('body', a, b, mat, { tag, ...opt }));
  const flush = (mat, ...faces) => c => (faces.includes(c.face) ? null : mat(c));
  const nU = c => Math.max(1, Math.round(c.fw / px)), nV = c => Math.max(1, Math.round(c.fh / px));
  const iU = c => Math.min(nU(c) - 1, Math.floor(c.eu / px + 1e-6)), iV = c => Math.min(nV(c) - 1, Math.floor(c.ev / px + 1e-6));

  // ---------- palette (sampled off the lit faces of the render) ----------
  const RG = hex('#6a6e64'), PAD = hex('#7b7f74'), YOKE = mul(RG, 0.86), PCH = mul(RG, 0.92), PLAC = mul(RG, 0.86), DUMP = mul(RG, 0.9);
  const LINING = hex('#232521'), WEBD = 0.72;
  const PATCH = hex('#6c6955'), DPATCH = hex('#57533f'), TABK = hex('#86775d'), TABG = hex('#767a73');
  // shell ends stay close to the grey hull like the render (muted brass heads, blue-grey crimp ends), so the caddies read
  // as grey shells with small coloured ends, not a white/gold checker; the elastic-wrapped hulls are dim blue-grey, only a
  // little lighter than the carrier; loose rounds (mag lips, pistol mags) keep the brighter brass
  const BRASS = hex('#9c8752'), RBRASS = hex('#ad904f'), COPPER = hex('#b5703f'), PLAST = hex('#7c8893'), SILVER = hex('#a9afb3'), ELAS = hex('#646c72');
  const ESTRAP = hex('#878984');                         // base of the dark elastic strip under the end-on shells
  const AR = hex('#34373b'), TAN = hex('#8f8377'), DSTEEL = hex('#43484e'), MAGTOP = hex('#2b2e33'), PBLK = hex('#2a2c2f');
  const TQB = hex('#242628'), TIME = hex('#8a9195'), POLY = hex('#5d6b62'), CORD = hex('#1d1f20');
  const RED = hex('#9b3a30'), INK = hex('#2a2e2c'), ZIP = hex('#3d3529');
  // Cordura: faint low-frequency mottling + per-cell grain
  const fab = (c, base, amt = 0.06) => {
    const q = snap(c.p, K.cell);
    const n = fbm(q[0] * 0.45 + 7, q[1] * 0.45 + 1, q[2] * 0.45 + 3);
    return mul(base, (0.97 + 0.06 * n) * G(c, amt));
  };
  const lin = c => mul(LINING, G(c, 0.08));
  const band = (y, y0, n) => { const dy = y - y0; return dy >= 0 && dy < n * 1.5 && (dy % 1.5) < 0.5; };   // dark MOLLE rows
  const tack = (h, h0) => (((h - h0) % 1.5) + 1.5) % 1.5 < 0.5;                // bar tacks break the rows into loops

  // ---------------- carrier ----------------
  const CADDY = [[-3.5, -1.5], [-1, 1]];               // chest shell caddies (x ranges)
  const frontBag = c => {
    if (c.face === 'top' || c.face === 'back') return lin(c);                    // mesh lining
    const x = c.p[0], y = c.p[1];
    let col = fab(c, RG);
    if (c.face === 'front') {
      if (y < 2.25) col = fab(c, YOKE);                                           // recessed yoke between the strap ends
      else if (Math.abs(x - 0.25) < 4 && band(y, 2.5, 4)) col = mul(col, tack(x, -3.25) ? 0.88 : WEBD);   // MOLLE rows (the darker bands)
      if (y >= 2.25 && y < 5.25 && CADDY.some(([a, b]) => x > a && x < b)) col = mul(col, 0.62);   // shadow behind the shells
      if (y >= 10.75) col = mul(col, 0.8);                                        // dark hem under the mag pouches
    }
    return edge(c, col);
  };
  add([-4.25, 0, -3.25], [4.25, 11, -2.5], frontBag, 'front plate bag');
  const backBag = c => {
    if (c.face === 'top' || c.face === 'front') return lin(c);
    let col = fab(c, RG);
    if (c.face === 'back' && Math.abs(c.p[0]) < 3.5 && band(c.p[1], 2.5, 5)) col = mul(col, tack(c.p[0], -3.25) ? 0.88 : WEBD);
    return edge(c, col);
  };
  add([-4.25, 0, 2.5], [4.25, 10.5, 3.25], backBag, 'back plate bag');
  const cumm = c => {
    let col = mul(fab(c, RG), 0.94);
    if (isSide(c) && Math.abs(c.p[2]) < 2.25 && band(c.p[1], 6, 3)) col = mul(col, WEBD);
    return edge(c, col, { stitch: false });
  };
  // ends above both bags' bottoms so no bottom faces share a plane
  add([-4.5, 5, -2.75], [4.5, 10.25, 2.75], cumm, 'cummerbund');
  add([-1.25, 0.75, 3.25], [1.25, 1.5, 3.75], flush(c => edge(c, mul(fab(c, RG), 0.7), { stitch: false }), 'front'), 'drag handle');

  // ---------------- padded shoulder straps ----------------
  // (the head hides the straps beside the neck: a thick pad on each shoulder, the strap ends on the front / back)
  const hump = c => {
    if (c.face === 'bottom') return lin(c);
    const col = fab(c, PAD, 0.07);
    if (isPanel(c)) return mul(col, 0.88);
    return c.face === 'top' ? mul(col, 1.08) : col;
  };
  for (const s of [-1, 1]) {
    const X = (a, b) => (s < 0 ? [a, b] : [-b, -a]);
    const [b0, b1] = X(-5.75, -4.25), [h0, h1] = X(-5.5, -4.5);
    add([b0, -1.25, -3.5], [b1, -0.25, 3.5], hump, 'shoulder pad');
    add([h0, -1.75, -3.25], [h1, -0.75, 3.25], hump, 'shoulder pad crown');
    // front strap ends: light padded panels framing the yoke; their inner edges step outward lower down (yoke widens)
    const inner = y => (y < 1.75 ? (s < 0 ? -1.25 : 1.75) : (s < 0 ? -1.75 : 2.25));
    const cut = (x, y) => (s < 0 ? x > inner(y) : x < inner(y));
    const innerFace = s < 0 ? 'left' : 'right';
    const riser = c => {
      const x = c.p[0], y = c.p[1];
      if (c.face === 'back') return y < 0 ? lin(c) : null;                       // flush on the bag below its top
      if (c.face === 'top') return fab(c, PAD);
      if ((c.face === 'front' || c.face === 'bottom') && cut(x, c.face === 'bottom' ? 2 : y)) return null;
      if (c.face === innerFace && y >= 1.75) return null;
      let col = fab(c, PAD, 0.07);
      if (c.face !== 'front') return mul(col, 0.78);
      if (y >= 1.75) col = mul(col, 0.8);                                         // bound lower edge
      else if (Math.abs(x - inner(y)) < 0.5) col = mul(col, 0.86);               // bound inner edge
      if (Math.abs(x) > 3.75) col = mul(col, 0.88);
      return col;
    };
    const [r0, r1] = s < 0 ? [-4.25, -1.25] : [1.75, 4.25];
    add([r0, -0.25, -3.5], [r1, 2.25, -3.25], riser, 'strap front');
    const [k0, k1] = s < 0 ? [-4.25, -1.75] : [1.75, 4.25];
    add([k0, -0.25, 3.25], [k1, 1.5, 3.5], c => (c.face === 'front' ? (c.p[1] < 0 ? lin(c) : null)
      : c.face === 'bottom' ? mul(fab(c, PAD), 0.7) : edge(c, fab(c, PAD, 0.07), { stitch: false })), 'strap back');
  }

  // ---------------- yoke: loop patch + webbing tab ----------------
  add([-1, 0.75, -3.5], [1.5, 1.75, -3.25], flush(c => {
    const col = mul(PATCH, G(c, 0.05));
    if (c.face !== 'front') return mul(col, 0.8);
    return c.ev < px ? mul(col, 1.06) : c.ev > c.fh - px ? mul(col, 0.88) : col;
  }, 'back'), 'loop patch');
  add([0, 1.25, -3.75], [0.5, 2.25, -3.25], flush(c => {
    const col = mul(TABG, G(c, 0.05));
    if (c.face === 'top') return mul(col, 1.12);
    if (c.face !== 'front') return mul(col, 0.78);
    return c.ev < px ? mul(col, 1.05) : mul(col, 0.8);                           // webbing loop, darker keeper below
  }, 'back'), 'patch tab');

  // ---------------- upper chest: two shell caddies ----------------
  // five 12ga shells per caddy lying across, grey elastic loop round the middle, brass head at one end and the pale
  // plastic crimp at the other (mixed like the render); a hair of dark MOLLE shows between the shells
  const shell = ends => c => {
    if (c.face === 'back') return null;
    const endCol = k => (k === 'B' ? BRASS : PLAST);
    if (isSide(c)) { const k = c.face === 'right' ? ends[0] : ends[1]; return mul(endCol(k), k === 'B' ? 0.95 : 0.88); }
    const u = c.p[0] - c.box.x;
    const col = u < 0.5 ? endCol(ends[0]) : u > c.box.w - 0.5 ? endCol(ends[1]) : ELAS;
    return mul(col, (c.face === 'front' || c.face === 'top' ? 1 : 0.7) * G(c, 0.04));   // no top boost: gaps stay dark
  };
  const ROWS = [['WB', 'BW', 'BW', 'BW', 'WB'], ['BW', 'BW', 'BW', 'WB', 'WB']];
  CADDY.forEach(([x0, x1], k) => ROWS[k].forEach((e, i) => {
    const y0 = 2.25 + i * 0.625;
    add([x0, y0, -3.75], [x1, y0 + 0.5, -3.25], shell(e), '12ga shell');
  }));
  // double flap pouch on the wearer's left upper chest, khaki pull tabs on the right half of each flap
  const dbl = c => {
    if (c.face === 'back') return null;
    const x = c.p[0], y = c.p[1];
    let col = fab(c, PCH);
    const crease = x > 2.25 && x < 2.75;
    if (c.face === 'top') return mul(col, crease ? 0.85 : 1.12);
    if (c.face !== 'front') return mul(col, c.face === 'bottom' ? 0.7 : 0.84);
    const j = iV(c);
    col = mul(col, [1.1, 1.0, 0.82, 1.0, 0.96, 0.8][j] ?? 1);
    if (crease) col = mul(col, j === 0 ? 0.68 : 0.8);                          // V notch between the two flaps, crease below
    else if (x > 2.75 && x < 3.25 && j > 0) col = mul(col, 1.04);
    if (x > 3.75) col = mul(col, 0.9);
    return col;
  };
  add([1.25, 2.25, -4.25], [4.25, 5.25, -3.25], dbl, 'double flap pouch');
  const tab = c => {
    if (c.face === 'back') return null;
    const col = mul(TABK, G(c, 0.05));
    if (c.face === 'top') return mul(col, 1.15);
    if (!isPanel(c)) return mul(col, 0.78);
    const j = iV(c);
    return j === 0 ? mul(col, 1.08) : j === nV(c) - 1 ? mul(col, 0.86) : col;
  };
  for (const x0 of [1.75, 3.5]) add([x0, 2.5, -4.5], [x0 + 0.5, 4, -4.25], tab, 'pull tab');   // one per flap, clear of the crease

  // ---------------- triple mag pouch + magazines ----------------
  // 11 cells wide: three 3-cell pockets with a shared seam cell between them (pocket centres x -1.5 / 0.5 / 2.5)
  // (the render's mid-pocket bar tacks are left out: at this size they would make a 1-cell light/dark grid)
  const PX0 = -2.25, PX1 = 3.25, PY0 = 7.75, PY1 = 10.75;
  const placard = c => {
    if (c.face === 'back') return null;
    const x = c.p[0], y = c.p[1];
    if (c.face === 'top') return c.p[2] > -3.75 ? mul(LINING, 1.3 * G(c, 0.08)) : mul(fab(c, PLAC, 0.04), 1.14);   // open mouths
    let col = fab(c, PLAC, 0.04);
    if (c.face !== 'front') return mul(col, c.face === 'bottom' ? 0.7 : 0.82);
    // lit top binding, then two light webbing rows with a clear dark gap under each
    const j = Math.floor((y - PY0) / 0.5 + 1e-6), i = Math.floor((x - PX0) / 0.5 + 1e-6);
    col = mul(col, [1.14, 1.0, 0.74, 1.0, 0.74, 0.9][j] ?? 1);
    if (j >= 1 && (i === 3 || i === 7)) col = mul(col, 0.82);                   // seams between the three pouches
    return col;
  };
  add([PX0, PY0, -4.25], [PX1, PY1, -3.25], placard, 'triple mag pouch');
  // magazines go 1.75 px down into their pouches; broad side forward, darker spine, feed lips + one brass round on top
  // (the outer two are nudged 1/8 px inboard so their sides stay clear of the pouch's side faces)
  const magPaint = (base, kind) => c => {
    if (c.face === 'bottom') return mul(base, 0.6);
    if (c.face === 'top') return mul(MAGTOP, c.ex < px ? 1.5 : 1);
    const col = mul(base, G(c, 0.04));
    if (!isPanel(c)) return mul(col, 0.74);
    const j = iV(c), i = iU(c);
    if (j === 0) return mul(col, kind === 'tan' ? 1.15 : 1.3);                  // feed-lip edge
    if (kind === 'ar') return i === 1 ? mul(col, 0.8) : col;                     // AR: one dark rib
    if (j === 1) return mul(col, 0.9);                                           // PMAG: grip-texture band
    return i === 1 ? mul(col, 0.86) : col;                                       //        recessed window
  };
  const lips = c => (c.face !== 'top' ? mul(DSTEEL, isPanel(c) ? 1.15 : 0.9) : c.eu > c.fw - px ? COPPER : RBRASS);
  for (const [cx, base, kind] of [[-1.375, AR, 'ar'], [0.5, TAN, 'tan'], [2.375, TAN, 'tan']]) {
    add([cx - 0.75, 6, -4], [cx + 0.75, 10, -3.5], magPaint(base, kind), kind === 'ar' ? 'rifle mag (black)' : 'rifle mag (tan PMAG)');
    add([cx - 0.5, 5.5, -3.875], [cx + 0.5, 6, -3.625], lips, 'mag feed lips');
  }

  // ---------------- wearer's-right front corner: IFAK seated on the corner, tourniquet + TIME strap between it and the
  // mag pouch, polymer pistol pouch layered proud in front of the IFAK's lower inboard edge (as in the render) ----------------
  // tourniquet: only its top peeks over the TIME strap; the black body shows between the strap and the pistol pouch (a
  // lighter windlass rod across it) and stands down in the back of the pouch's open mouth
  add([-3.25, 7, -4], [-2.5, 9, -3.25], flush(c => {
    if (c.face === 'top') return mul(TQB, 1.2);
    let col = mul(TQB, G(c, 0.06));
    if (isPanel(c) && c.p[1] >= 8.5 && c.p[1] < 9) col = mul(col, 2);           // windlass rod
    return isSide(c) ? mul(col, 0.85) : col;
  }, 'back'), 'tourniquet');
  // the strap runs from the IFAK's edge into the side of the mag pouch (looks like it carries on behind it)
  add([-3.25, 7.5, -4.25], [-2.25, 8, -3.25], flush(c => {
    const col = mul(TIME, G(c, 0.05));
    if (c.face !== 'front') return mul(col, c.face === 'top' ? 1.1 : 0.78);
    return c.p[0] < -2.75 ? mix(col, INK, 0.45) : col;                           // "TIME:" in dark print
  }, 'back'), 'TQ time strap');
  // Kydex pistol pouch: lit rim, then two dark crossings of the shock-cord lacing with the pouch body between them
  const pistol = c => {
    if (c.face === 'back') return null;                                          // flush on the bag / inside the IFAK
    if (c.face === 'top') return c.ex < px ? mul(POLY, 1.2) : mul(LINING, 1.2);
    const col = mul(POLY, G(c, 0.05));
    if (c.face !== 'front') return mul(col, c.face === 'bottom' ? 0.65 : 0.8);
    const i = iU(c), j = iV(c);
    if (j === 0) return mul(col, 1.15);                                          // lit rim
    if (j === 1 || j === 3) return mix(col, CORD, 0.62);                         // cord crossings
    return i === 1 ? mul(col, 1.06) : col;                                       // moulded ridge between them
  };
  add([-3.75, 9, -4.5], [-2.25, 11, -3.25], pistol, 'pistol mag pouch');
  add([-3.25, 11, -4], [-2.75, 12.5, -3.75], c => mul(c.p[1] > 12 ? mix(CORD, RG, 0.45) : CORD, isPanel(c) ? G(c, 0.05) : 0.8), 'pull cord');
  // IFAK on the front corner (back on the plate bag, outer part wraps the corner onto the cummerbund): flap top, red medic
  // cross, marker writing, T pull handle
  const ifak = c => {
    let col = fab(c, RG);
    if (c.face === 'top') return mul(col, 1.1);
    if (c.face === 'bottom') return mul(col, 0.68);
    if (c.face === 'back') return mul(col, 0.6);
    if (c.face !== 'front') return edge(c, mul(col, 0.86), { stitch: false });
    const i = iU(c), j = iV(c);
    const cross = ['.R.', 'RRR', '.R.'];
    if (j === 0) return mul(col, 1.1);
    if (j >= 1 && j <= 3 && cross[j - 1][i] === 'R') return mul(RED, G(c, 0.05));
    if (j === 5) return i < 2 ? mix(col, INK, 0.45) : col;                       // "A+ NKDA"
    if (j === 6) return mul(col, 1.1);                                           // T handle bar
    if (j >= 7) return i === 1 ? mul(col, 1.08) : mul(col, 0.82);               // T handle stem
    return col;
  };
  add([-4.75, 6.75, -4], [-3.25, 11.25, -2.75], ifak, 'IFAK');   // pulled in toward the carrier edge to cut the arm overlap

  // ---------------- wearer's-left front corner: pistol mag pouch + end-on shell caddy ----------------
  const lpouch = c => {
    if (c.face === 'back') return c.p[0] > 4.25 ? mul(LINING, 1.2) : null;
    if (c.face === 'top') return c.ex < px ? mul(PCH, 1.15) : mul(LINING, 1.3);
    const col = fab(c, PCH);
    if (c.face !== 'front') return mul(col, c.face === 'bottom' ? 0.68 : 0.84);
    const i = iU(c), j = iV(c);
    if (j === 0) return mul(col, 1.12);
    if (j === nV(c) - 1) return mul(col, 0.8);
    return i === 1 ? mul(col, 0.86) : col;
  };
  add([3.25, 8.25, -4], [4.75, 10.75, -3.25], lpouch, 'pistol mag pouch (left)');
  const pmag = c => {
    if (c.face === 'top') return RBRASS;
    const col = mul(PBLK, G(c, 0.04));
    if (!isPanel(c)) return mul(col, 0.8);
    return iV(c) === 0 ? mul(col, 1.35) : col;
  };
  for (const x0 of [3.375, 4.125]) add([x0, 7, -3.875], [x0 + 0.5, 9.5, -3.375], pmag, 'pistol mag');
  // end-on shell caddy on the pouch's outer front edge: a dark elastic strip on the pouch face (hanging a little below it)
  // with five shells in front of it, bases forward
  add([4.25, 8.25, -4.25], [4.75, 11.75, -4], c => {
    if (c.face === 'back') return c.p[1] > 10.75 ? mul(ESTRAP, 0.35) : null;    // flush on the pouch, free below it
    return mul(ESTRAP, (c.face === 'front' ? 0.45 : 0.6) * G(c, 0.05));
  }, 'shell caddy strap');
  for (let i = 0; i < 5; i++) {
    const y0 = 8.5 + i * 0.625;
    add([4.25, y0, -4.75], [4.75, y0 + 0.5, -4.25], flush(c => {
      const base = i === 3 ? SILVER : BRASS;                                     // one slug among the buckshot
      if (c.face === 'front') return mul(base, G(c, 0.05));                      // bases facing forward
      return mul(base, c.face === 'top' ? 0.85 : 0.68);                          // brass rim / hull sides
    }, 'back'), '12ga shell (end-on)');
  }

  // ---------------- drop pouch under the mag pouches ----------------
  // the render's deep, lopsided U: a full-width body (same width as the mag pouch above) with three narrower tiers stacked
  // under it, each a closed box of whole cells and the same depth, so the rounded corners have no see-through gaps; the
  // wearer's-right corner sweeps in the most, so the short flat bottom sits toward the wearer's left
  const DT = 11, DTIERS = [[11, 14.5, -2.25, 3.25], [14.5, 15, -1.75, 2.75], [15, 15.5, -1.25, 2.75], [15.5, 16, -0.25, 2.25]];
  const inDrop = (x, y) => DTIERS.some(([y0, y1, x0, x1]) => y > y0 && y < y1 && x > x0 && x < x1);
  const dump = k => c => {
    const x = c.p[0], y = c.p[1];
    let col = fab(c, DUMP);
    if (c.face === 'top') return k ? null : mul(col, c.p[2] < -3.25 ? 1.1 : 0.8);   // lower tiers' tops are inside the pouch
    if (c.face === 'bottom') return inDrop(x, DTIERS[k][1] + 0.25) ? null : mul(col, 0.66);   // only the exposed ledge
    if (c.face === 'back') return mul(col, 0.7);
    if (isSide(c)) return mul(col, 0.8);
    if (y < DT + 0.5) return mul(col, 1.1);                                      // lit top edge
    if (y < DT + 1 && x > -1.75 && x < 2.75) return mul(ZIP, G(c, 0.05));        // zip across the top
    if (!inDrop(x - px, y) || !inDrop(x + px, y) || !inDrop(x, y + px)) col = mul(col, 0.84);   // soft bound edge round the U
    return col;
  };
  DTIERS.forEach(([y0, y1, x0, x1], k) => add([x0, y0, -4], [x1, y1, -2.75], dump(k), k ? 'drop pouch (rounded bottom)' : 'drop pouch'));
  add([-1.75, 11.75, -4.25], [-1.25, 12.75, -4], flush(c => mul(hex('#2c2a27'), c.face === 'top' ? 1.4 : isPanel(c) && iV(c) === 0 ? 1.3 : 1), 'back'), 'zip pull');
  // loop patch off-centre toward the wearer's left, as in the render
  add([-0.5, 12.25, -4.25], [2.5, 15, -4], flush(c => {
    const col = mul(DPATCH, G(c, 0.05));
    return c.face === 'front' ? edge(c, col, { stitch: false }) : mul(col, 0.8);
  }, 'back'), 'loop patch (drop pouch)');
  // five shells standing in an elastic holder: each shell is its own grey cylinder — muted brass head on top, a light
  // ribbed elastic sleeve round the middle (lighter than the pouch, as in the render), pale plastic crimp end below; the
  // holder's backing strip sits behind them, so the narrow gaps between the shells read dark
  const DSLV = hex('#7b817c');                                                   // sleeve: ~1.3x the pouch, a touch bluer
  const vshell = c => {
    if (c.face === 'back') return null;                                          // on the backing strip / hidden
    const v = c.p[1] - c.box.y;
    const cap = mul(BRASS, 0.85);
    if (c.face === 'top') return mul(cap, 1.05);
    if (c.face === 'bottom') return mul(PLAST, 0.7);
    const col = v < 0.5 ? cap : v >= 1.5 ? mul(PLAST, 1.08) : v < 1 ? DSLV : mul(DSLV, 0.86);   // lower sleeve row darker = ribbing
    return mul(col, (isPanel(c) ? 1 : 0.78) * G(c, 0.04));
  };
  [12.75, 12.75, 12.75, 12.5, 12.75].forEach((t, i) => {                       // the fourth shell pushed up a little, as in the render
    const x0 = -0.5 + i * 0.625;
    add([x0, t, -5], [x0 + 0.5, t + 2, -4.5], vshell, '12ga shell (drop pouch)');
  });
  // holder backing: a strip of the same elastic in shadow, flush on the loop patch, a short tab showing left of the shells
  add([-0.875, 13.25, -4.5], [2.625, 14.25, -4.25], flush(c => {
    const col = mul(DSLV, 0.6 * G(c, 0.05));
    if (c.face === 'top') return mul(col, 1.1);
    return isPanel(c) ? col : mul(col, 0.85);
  }, 'back'), 'shell holder backing (drop pouch)');

  return { S: K.S, px: K.px, cell: K.cell, boxes: B };
};
