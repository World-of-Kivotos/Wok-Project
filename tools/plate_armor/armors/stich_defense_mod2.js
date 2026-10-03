// ================= Stich Profi Stich Defense mod.2 plate carrier (MultiCam) — EFT reference (tarkov.dev 66b6295178bbc0200425f995) =================
// Read off the render and its inspect view (x<0 = wearer's right = viewer's left). In EFT this is an armored rig: the gear
// is part of the item model.
//   bright, slightly green MultiCam everywhere; padded shoulder straps (lit almost cream on top, dark teal-green lining
//   inside the arch) ending in big tan plastic cam buckles at the bag's top corners, a tan shock cord hanging from each
//   upper chest: light khaki laser-cut loop panel (bands split by dark slits, short cuts between blocks) with a yellow
//   triangular "EXCESS" patch left of centre as seen from the front (wearer's right); two MultiCam laser-cut MOLLE rows
//   under it; a tan side-release buckle on a strap on the wearer's right
//   sides: dark brown perforated (laser-cut / mesh) cummerbund
//   placard across the lower front (tan webbing top), left to right as seen from the front:
//     wearer's right front corner: MultiCam pouch with black trauma-shear finger rings sticking out, tan buckle outside
//     tan ladder-lock buckle
//     TQ pouch: tan shock-cord frame (inside the olive edge) + X, a black CAT tourniquet standing in it (white "CAT"
//     print, grey band, red tab)
//     mag pouch with an olive PMAG (tilted), olive velcro retention flap down its front, tan elastic band
//     second PMAG (tilted) behind a MultiCam elastic holder with two purple sticks (their ends stick out below it),
//     tan shock cords + a short MultiCam pull tab at its top-left corner
//     wearer's left front corner: MultiCam utility pouch, tan buckle outside, blue tape roll on a black strap
//   the pouch bottoms carry a teal-green sheen; two tan webbing tabs hang under the placard.
//   Proportions: the front runs about 1.2x as tall as it is wide, so the bags / placard reach y 11 (pouch row stretched).
//   The render never shows the back: it follows the front (MultiCam, MOLLE rows, drag handle).
ARMORS.stich_defense_mod2 = function (mode) {
  const K = kit('M');                              // always the 2x style
  const { edge, G, isPanel, isSide, px } = K;
  const B = [];
  const add = (a, b, mat, tag, opt = {}) => B.push(box('body', a, b, mat, { tag, ...opt }));
  const md = (v, m) => ((v % m) + m) % m;
  const iU = c => Math.floor(c.eu / px + 1e-6), iV = c => Math.floor(c.ev / px + 1e-6);
  const nU = c => Math.max(1, Math.round(c.fw / px)), nV = c => Math.max(1, Math.round(c.fh / px));

  // ---------------- palette (albedo, sampled off the render) ----------------
  const LINING = hex('#1f322e');                                   // dark teal-green strap lining / pouch insides
  const LOOPK = hex('#cdb48a'), SLIT = hex('#5a4a36');             // light khaki loop panel + its laser cuts
  const WEBT = hex('#bba67a');                                     // tan webbing
  const BUCK = hex('#cbb68b');                                     // tan plastic buckles
  const CORD = hex('#b8a676');                                     // tan shock cord
  const MESH = hex('#4a3f30'), HOLE = hex('#1f1a14');              // dark brown perforated cummerbund
  const PMAG = hex('#585c47'), MAGTOP = hex('#2d2f29'), LIPS = hex('#44463b'), BRASS = hex('#caa24e'), COPPER = hex('#b5703f');
  const TQK = hex('#2a2a2a'), TXT = hex('#d6d6d2'), TQG = hex('#6c7075'), RED = hex('#b3312c');
  const VEL = hex('#9e9761');                                     // olive velcro flap
  const PURP = hex('#852a66');
  const BLUE = hex('#2f55cc');
  const TEAL = hex('#4a8a6e');                                     // teal-green sheen on the pouch bottoms
  const YEL = hex('#f2ae1e'), INK = hex('#1f1d1a');
  const BLACK = hex('#1f1f20');
  const PADTOP = hex('#e6d8a8');

  // MultiCam on the half-pixel grid, shapes 1-3 px: light khaki ground, yellow-green, pinkish brown and dark-brown
  // shapes, sparse cream flecks (the render's MultiCam is bright and a little green). `o` shifts the pattern so a pouch
  // does not continue the bag's shapes (separate cloth)
  const camoAt = p => layers(snap(p, px).map(v => v * 0.5), 0, '#b9b284',
    [['#d0c99f', 0.55, 0.6, 3], ['#8e9a56', 0.62, 0.58, 17], ['#9f8062', 0.7, 0.63, 41], ['#726a49', 0.9, 0.69, 77], ['#5a4d3c', 1.15, 0.75, 5], ['#e4dec0', 1.2, 0.76, 91]]);
  const fab = (c, k = 1, amt = 0.05, o = 0) => mul(camoAt(o ? [c.p[0] + o, c.p[1] + o * 0.6, c.p[2] - o * 0.4] : c.p),
    k * G(c, amt) * (1.05 - 0.01 * Math.max(0, Math.min(16, c.p[1]))));
  const lining = c => mul(LINING, G(c, 0.08));
  // MOLLE: printed MultiCam webbing, a tape row every `step` px (camo shows through part-way), bar tacks, shadow row under it
  const WEB = hex('#a29c76');
  const webbing = (c, col, y0, rows, h0, h1, t = 0.65, step = 1.5) => {
    if (!isPanel(c)) return null;
    const h = c.p[0];
    if (h < h0 || h > h1) return null;
    for (let i = 0; i < rows; i++) {
      const dy = c.p[1] - (y0 + i * step);
      if (dy >= 0 && dy < 1) {
        if (dy >= 0.5) return mul(mix(col, WEB, 0.3), 0.62);
        const tape = mul(mix(col, WEB, t), 1.08);
        return md(h - h0, 1.5) < 0.5 ? mul(tape, 0.7) : tape;
      }
    }
    return null;
  };
  // teal-green sheen along a pouch's bottom edge
  const sheen = col => mix(col, TEAL, 0.42);

  // ================= carrier =================
  // front: shadow under the loop panel, then the two laser-cut MOLLE rows (tape + seam each) down to the placard
  const bag = c => {
    if (c.face === 'top') return lining(c);                                    // neckline
    let col = fab(c);
    if (c.face === 'front') {
      if (c.p[1] >= 2.75 && c.p[1] < 3.25 && Math.abs(c.p[0]) < 3.25) col = mul(col, 0.84);   // shadow under the loop panel
      col = webbing(c, col, 3.25, 2, -3.25, 3.25, 0.65, 1) || col;
    }
    return edge(c, col);
  };
  add([-4.25, 0.75, -3.25], [4.25, 11, -2.5], bag, 'front plate bag');
  const back = c => {
    if (c.face === 'top') return lining(c);
    const col = fab(c);
    return edge(c, (c.face === 'back' && webbing(c, col, 2.5, 5, -3.5, 3.5)) || col);
  };
  add([-4.25, 0.5, 2.5], [4.25, 11, 3.25], back, 'back plate bag');
  // cummerbund: dark brown perforated panel on the sides (staggered holes 1 px apart in rows, 2 px along a row, so no
  // one-cell checker), camo-bound front / back ends; ends 0.25 above the bags' bottoms
  const mesh = c => {
    const i = Math.floor((c.p[2] + 8) / px), j = Math.floor((c.p[1] + 8) / px);
    const col = mul(MESH, G(c, 0.08));
    return j % 2 === 0 && md(i + ((j >> 1) % 2) * 2, 4) === 0 ? mix(col, HOLE, 0.55) : col;
  };
  const cumm = c => {
    if (isSide(c)) return Math.abs(c.p[2]) > 2.25 ? edge(c, fab(c, 0.9), { stitch: false }) : edge(c, mesh(c), { stitch: false });
    if (c.face === 'top' || c.face === 'bottom') return mul(MESH, c.face === 'top' ? 1.1 : 0.75);
    return edge(c, fab(c, 0.95), { stitch: false });
  };
  add([-4.5, 3, -2.75], [4.5, 10.75, 2.75], cumm, 'cummerbund');
  add([-1.25, 0.25, 3.25], [1.25, 1.25, 3.75], c => edge(c, fab(c, 0.8), { stitch: false }), 'drag handle');

  // ================= padded shoulder straps =================
  // beside the head: a rounded pad on top of each shoulder (lit almost cream on top); below the chin the front risers
  // (inner edge = the dark lining of the arch) carrying the big tan cam buckles; the back risers on the back bag
  const hump = c => {
    if (c.face === 'bottom') return lining(c);
    const col = fab(c, 1.04, 0.06);
    if (isPanel(c)) return mul(col, 0.9);                                      // pad ends
    if (c.face !== 'top') return col;
    return mul(mix(col, PADTOP, 0.3), 1.1);                                    // lit top of the pad
  };
  const riser = inner => c => {
    if (c.face === 'bottom' || c.face === inner) return lining(c);
    return edge(c, fab(c, 1.04, 0.06), { stitch: false });
  };
  // cam buckle: lit top bar, a dark strap slot in the middle, darker lower bar
  const roc = c => {
    if (c.face === 'back') return null;
    if (c.face === 'top') return mul(BUCK, 1.3);
    if (c.face !== 'front') return mul(BUCK, 0.78);
    const i = iU(c), j = iV(c), n = nU(c);
    const col = mul(BUCK, G(c, 0.05));
    if (j === 0) return mul(col, 1.15);
    if (j === 1 && i > 0 && i < n - 1) return mul(col, 0.74);                  // strap slot (soft: a dark one read as a hole)
    return mul(col, j === 1 ? 1 : 0.9);
  };
  const cord = c => (c.face === 'back' ? null : mul(CORD, (c.face === 'front' ? 1 : 0.8) * G(c, 0.05)));
  for (const s of [-1, 1]) {
    const X = (a, b) => (s < 0 ? [a, b] : [-b, -a]);
    const inner = s < 0 ? 'left' : 'right';
    const [b0, b1] = X(-5.75, -4.25), [h0, h1] = X(-5.5, -4.5);
    add([b0, -1.25, -3.5], [b1, -0.25, 3.5], hump, 'shoulder pad');
    add([h0, -1.75, -3.25], [h1, -0.75, 3.25], hump, 'shoulder pad crown');
    // front riser about as wide as the cam buckle (the render's strap is narrow at the buckle)
    const [r0, r1] = X(-4.25, -2.25), [k0, k1] = X(-4.5, -1.75);
    add([r0, -0.25, -3.75], [r1, 2, -3.25], riser(inner), 'strap front');
    add([k0, -0.25, 2.75], [k1, 1.5, 3.5], riser(inner), 'strap back');
    const [u0, u1] = X(-3.75, -2.25);
    add([u0, 0.5, -4], [u1, 2, -3.75], roc, 'cam buckle');
    const [c0, c1] = X(-2.75, -2.5);
    add([c0, 2, -3.75], [c1, 3, -3.5], cord, 'buckle shock cord');
  }

  // ================= upper chest =================
  // light khaki laser-cut loop panel (the original has three thin bands; at half-pixel cells two bands keep it a light
  // panel with lines instead of stripes), dark border down its ends, short cuts between blocks, symmetric
  const loopPanel = c => {
    if (c.face === 'back') return null;
    const col = mul(LOOPK, G(c, 0.08));
    if (c.face !== 'front') return mul(col, c.face === 'top' ? 1.05 : 0.72);
    const i = iU(c), j = iV(c), n = nU(c);
    if (i === 0 || i === n - 1) return mix(SLIT, col, 0.62);                   // dark stitched border down the ends
    if (j === 2) return mix(SLIT, col, 0.5);                                   // slit between the two bands
    if (i === 4 || i === n - 5) return mix(SLIT, col, 0.78);                   // cuts between the blocks (symmetric)
    return j === 0 ? mul(col, 1.07) : j === nV(c) - 1 ? mul(col, 0.93) : col;
  };
  add([-3, 1, -3.5], [3, 3, -3.25], loopPanel, 'loop panel');
  // yellow triangular EXCESS patch (black logo in the middle, black edges), left of centre as seen from the front
  // (wearer's right), about 0.9 px off the panel's centre like on the render
  const PAT = ['..Y..', '.YKY.', 'YYYYY'];
  const patch = c => {
    if (c.face === 'back') return null;
    const cx = c.box.x + c.box.w / 2;
    if (c.face === 'top') return Math.abs(c.p[0] - cx) < 0.25 ? INK : null;
    if (isSide(c)) return c.p[1] >= c.box.y + 1 ? INK : null;
    if (c.face === 'bottom') return INK;
    const ch = PAT[Math.min(2, iV(c))][Math.min(4, iU(c))];
    if (ch === '.') return null;
    return ch === 'K' ? INK : mul(YEL, iV(c) === 2 ? 0.9 : 1.02);
  };
  add([-2.125, 1.25, -3.75], [0.375, 2.75, -3.5], patch, 'EXCESS patch');
  // tan side-release buckle on its strap on the wearer's right, between the loop panel and the placard
  add([-3.25, 3, -3.5], [-2.25, 5.25, -3.25], c => (c.face === 'back' ? null : edge(c, mul(mix(fab(c, 1, 0.05, 4.1), WEBT, 0.45), 0.92), { stitch: false })), 'buckle strap');
  add([-3.25, 3.5, -3.75], [-2.25, 5, -3.5], c => {
    if (c.face === 'back') return null;
    if (c.face === 'top') return mul(BUCK, 1.3);
    if (c.face !== 'front') return mul(BUCK, 0.78);
    const i = iU(c), j = iV(c);
    const col = mul(BUCK, G(c, 0.05));
    if (j === 0) return mul(col, 1.15);
    return i === 1 ? mul(col, 0.62) : col;                                     // prong between the two arms
  }, 'side-release buckle');

  // ================= placard across the lower front =================
  const placard = c => {
    if (c.face === 'back') return null;
    let col = fab(c);
    if (c.face === 'top') return mul(WEBT, 1.05 * G(c, 0.05));
    if (c.face === 'front' && c.ev < px) col = mix(col, WEBT, 0.7);            // tan webbing along its top
    return edge(c, col);
  };
  add([-4, 5.25, -3.5], [4, 11, -3.25], placard, 'placard');
  const tab = c => (c.face === 'back' ? null : edge(c, mul(mix(fab(c, 1, 0.05, 8.2), WEBT, 0.55), c.face === 'front' ? 1 : 0.8), { stitch: false }));
  add([-4, 11, -3.5], [-3.5, 11.75, -3.25], tab, 'hanging tab');
  add([3.5, 11, -3.5], [4, 11.75, -3.25], tab, 'hanging tab');

  // generic open-top MultiCam pouch: open mouth (light rim, dark inside), lighter binding on top, teal sheen at the bottom
  const pouchCol = (c, o) => {
    if (c.face === 'top') return c.ex < px ? mul(fab(c, 1.08, 0.05, o), 1.05) : mul(LINING, 1.2);
    if (c.face === 'back') return lining(c);
    const col = fab(c, 1, 0.05, o);
    if (c.face === 'bottom') return sheen(mul(col, 0.8));
    if (c.ev > c.fh - px) return sheen(col);
    return null;
  };

  // ---------- TQ pouch: shock-cord frame (inset one cell from the olive edge) + X, black CAT tourniquet in it ----------
  const tqPouch = c => {
    const pc = pouchCol(c, 2.3);
    if (pc) return pc;
    const col = fab(c, 1, 0.05, 2.3);
    if (c.face !== 'front') return edge(c, col, { stitch: false });
    const i = iU(c), j = iV(c), n = nU(c), m = nV(c);
    const fc = i === 1 || i === n - 2, fr = j === 1 || j === m - 2;            // cord frame column / row
    const inX = i >= 1 && i <= n - 2, inY = j >= 1 && j <= m - 2;
    if (inX && inY && fc !== fr) return mul(CORD, G(c, 0.05));                 // cord frame, rounded corners
    if (i >= 2 && i <= n - 3 && j >= 2 && j <= m - 3) {                        // the tourniquet seen through the open front
      const k = mul(TQK, G(c, 0.06));
      if (j <= 3 && i <= 3) return mul(TXT, (i + j) % 3 === 0 ? 0.86 : 1);     // white "CAT" print
      if (j === m - 4) return mul(TQG, G(c, 0.05));                            // grey band
      if (j === m - 3) return mul(RED, G(c, 0.05));                            // red tab
      return i === 4 ? mul(k, 1.3) : k;
    }
    return col;
  };
  add([-3.5, 6.25, -4.25], [0, 10.75, -3.5], tqPouch, 'TQ pouch');
  // the tourniquet's top sticks out of the pouch: black, lit top edge, a strap wrap
  add([-2.5, 5.5, -4], [-1, 8.5, -3.5], c => {
    if (c.face === 'top') return mul(TQK, 1.6);
    const col = mul(TQK, G(c, 0.06));
    if (!isPanel(c)) return mul(col, 0.85);
    return iV(c) === 0 ? mul(col, 1.35) : iV(c) === 1 ? mul(col, 0.8) : col;
  }, 'CAT tourniquet');
  // tan shock-cord X across the pouch front (two thin cords standing on it) + cord lock by the red tab
  for (const s of [-1, 1]) add([-1.875, 6.375, -4.5], [-1.625, 10.125, -4.25], cord, 'TQ pouch cord', { rot: [0, 0, s * 40], pivot: [-1.75, 8.25, -4.375] });
  add([-1.25, 9.5, -4.5], [-0.75, 10, -4.25], c => (c.face === 'back' ? null : mul(BUCK, c.face === 'top' ? 1.25 : isPanel(c) ? 1 : 0.8)), 'cord lock');

  // ---------- magazines (olive PMAGs, tilted like the render) ----------
  // about 3 px of each PMAG stands above its pouch like on the render: broad side forward, darker spine, a darker grip
  // band under the lit top edge, the raised cross rib under it, then the two windows split by a darker rib;
  // feed lips + one brass round
  const mag = c => {
    if (c.face === 'bottom') return mul(PMAG, 0.55);
    if (c.face === 'top') return mul(MAGTOP, c.ex < px ? 1.5 : 1);
    let col = mul(PMAG, G(c, 0.05));
    if (!isPanel(c)) return mul(col, 0.74);
    const i = iU(c), j = iV(c), n = nU(c);
    if (j === 0) return mul(col, 1.28);                                        // lit edge under the feed lips
    if (j <= 2) return mul(col, 0.84);                                         // grip texture band
    if (j === 3) return mul(col, 1.08);                                        // raised cross rib
    if (i === Math.floor(n / 2)) col = mul(col, 0.84);                         // rib between the windows
    return col;
  };
  // feed lips: dark steel sides, one brass round lying across the top with a copper tip
  const lips = c => {
    if (c.face !== 'top') return mul(LIPS, isPanel(c) ? 1.15 : 0.9);
    return c.eu > c.fw - px ? COPPER : BRASS;
  };
  // the mag pouches start half a pixel under the TQ pouch top (as on the render); each mag still goes over 2 px down into
  // its pouch
  const magAt = (x0, tag) => {
    const cx = x0 + 1, R = { rot: [0, 0, 6], pivot: [cx, 5.75, -3.625] };
    add([x0, 4, -4], [x0 + 2, 9, -3.25], mag, tag, R);
    add([cx - 0.75, 3.5, -3.875], [cx + 0.75, 4, -3.375], lips, tag + ' feed lips', R);
    return R;
  };

  // ---------- mag pouch with an olive velcro retention flap ----------
  const magPouch = o => c => {
    const pc = pouchCol(c, o);
    if (pc) return pc;
    const col = fab(c, 1, 0.05, o);
    if (c.face !== 'front') return edge(c, col, { stitch: false });
    const j = iV(c);
    if (j === 0) return mul(col, 1.1);                                         // top binding
    if (j === 1) return mul(mix(col, WEBT, 0.75), 0.95);                       // tan elastic band just under it
    return edge(c, col, { stitch: false });
  };
  add([0, 6.75, -4.25], [2.25, 10.75, -3.5], magPouch(3.7), 'mag pouch');
  magAt(0.125, 'magazine');
  // olive velcro flap: its top lies on the exposed mag front, the rest on the pouch front
  add([0.375, 5.75, -4.5], [1.875, 9.25, -4], c => {
    if (c.face === 'back') return null;
    const col = mul(VEL, G(c, 0.1));
    if (c.face === 'top') return mul(col, 1.15);
    if (!isPanel(c)) return mul(col, 0.78);
    return iV(c) === nV(c) - 1 ? mul(col, 0.82) : (iU(c) === 0 || iU(c) === nU(c) - 1) ? mul(col, 0.9) : col;
  }, 'velcro flap');

  // ---------- second mag behind a MultiCam elastic holder with two purple sticks ----------
  // the second pouch sits behind the stick holder (which is its front panel); the holder starts half a pixel higher and,
  // where it stands above the pouch, its back lies on the mag front
  add([2.25, 6.75, -4], [4.25, 10.75, -3.5], magPouch(5.9), 'mag pouch (2)');
  const R2 = magAt(2.25, 'magazine (2)');
  // short MultiCam pull tab at the mag's top-left corner (seated on the mag front, its back against the feed lips),
  // shock cords running down the mag's front from it into the holder
  add([2.25, 3.25, -4.25], [2.75, 4.25, -3.75], c => (c.face === 'back' ? null : edge(c, mul(fab(c, 0.97, 0.05, 13.3), c.face === 'front' ? 1 : 0.8), { stitch: false })), 'pull tab', R2);
  for (const x0 of [2.5, 3.5]) add([x0, 4.25, -4.25], [x0 + 0.25, 6.75, -4], cord, 'mag shock cord', R2);
  add([2.25, 6.25, -4.5], [4.25, 10.75, -4], c => (c.face === 'back' ? null : edge(c, fab(c, 0.9, 0.05, 6.4), { stitch: false })), 'stick holder');
  const stick = c => {
    if (c.face === 'back') return null;
    if (c.face === 'top' || c.face === 'bottom') return mul(PURP, c.face === 'top' ? 1.3 : 0.7);
    return mul(PURP, (isPanel(c) ? 1 : 0.78) * G(c, 0.05));
  };
  // the stick ends poke a little out of the top loop
  for (const x0 of [2.5, 3.5]) add([x0, 6, -4.75], [x0 + 0.5, 11.25, -4.5], stick, 'purple stick');
  // elastic loops over the sticks: light MultiCam, a darker split between the two loops
  const band = c => {
    if (c.face === 'back') return null;
    const col = mul(fab(c, 1.1, 0.05, 9.3), 1.04);
    if (c.face === 'top') return mul(col, 1.08);
    if (c.face === 'bottom') return mul(col, 0.7);
    if (!isPanel(c)) return mul(col, 0.8);
    const i = iU(c), n = nU(c);
    if (i === n / 2 - 1 || i === n / 2) return mul(col, i === n / 2 ? 0.8 : 0.92);
    return col;
  };
  for (const [y0, y1] of [[6.25, 7], [7.75, 8.5], [9.5, 10.25]]) add([2.25, y0, -5], [4.25, y1, -4.5], band, 'elastic loop band');

  // ---------- wearer's right front corner: pouch with trauma shears, tan buckle on its outer face ----------
  const corner = (o, outer) => c => {
    const pc = pouchCol(c, o);
    if (pc) return pc;
    let col = fab(c, 1, 0.05, o);
    if (c.face === outer) {
      const z = c.p[2], y = c.p[1];
      if (z > -3.75 && z < -3 && y > c.box.y + 0.5 && y < c.box.y + 2) {       // tan buckle on the side strap
        const lit = y < c.box.y + 1;
        return mul(BUCK, (lit ? 1.05 : 0.85) * G(c, 0.05));
      }
    }
    if (c.face === 'front' && c.ev < px) col = mul(col, 1.1);
    return edge(c, col, { stitch: false });
  };
  add([-5.5, 6.25, -4], [-4, 10.75, -2.75], corner(7.7, 'right'), 'shears pouch');
  // black finger rings: thin loops (a one-cell frame round a big opening, open corners so they read round) standing just
  // above the pouch mouth, each with a one-cell shank running down into the pouch. The tall one is upright, toward the
  // centre; the small one is lower, in front, and fanned out toward the wearer's right like the render's handles.
  // Cells are looked up on the box's own half-pixel grid (c.p is unrotated), so side / top faces follow the same mask
  const ringPaint = solid => c => {
    if (c.face === 'back') return null;
    const b = c.box, n = Math.round(b.w / px), m = Math.round(b.h / px);
    const i = Math.max(0, Math.min(n - 1, Math.floor((c.p[0] - b.x) / px + 1e-6)));
    const j = Math.max(0, Math.min(m - 1, Math.floor((c.p[1] - b.y) / px + 1e-6)));
    if (!solid(i, j)) return null;
    if (isPanel(c)) return mul(BLACK, j === 0 ? 1.5 : 1.1);
    return mul(BLACK, c.face === 'top' ? 1.6 : 0.9);
  };
  // loop of n x m cells (corners and the inside left open) + shank cells in column `sc` below it
  const loop = (n, m, sc) => (i, j) => {
    if (j >= m) return i === sc;
    if ((i === 0 || i === n - 1) && (j === 0 || j === m - 1)) return false;
    return !(i > 0 && i < n - 1 && j > 0 && j < m - 1);
  };
  add([-5.25, 3.25, -3.75], [-3.25, 7.25, -3.5], ringPaint(loop(4, 6, 1)), 'shear ring (tall)');
  // the small ring leans out about its bottom (on the pouch mouth); its shank is a separate solid strip so no empty
  // part of the ring's box hangs beside the pouch
  const RS = { rot: [0, 0, -15], pivot: [-5.25, 6.25, -3.75] };
  add([-6.25, 4.25, -3.875], [-4.25, 6.25, -3.625], ringPaint(loop(4, 4, -1)), 'shear ring', RS);
  add([-5.25, 6.25, -3.875], [-4.75, 7.25, -3.625], c => (c.face === 'back' ? null : mul(BLACK, isPanel(c) ? 1.1 : 0.9)), 'shear ring shank', RS);
  // tan ladder-lock buckle between the shears pouch and the TQ pouch, flush with the TQ pouch front: frame + two dark bars
  add([-4, 6.5, -4.25], [-3.5, 9, -3.5], c => {
    if (c.face === 'back') return null;
    if (!isPanel(c)) return mul(BUCK, c.face === 'top' ? 1.25 : 0.78);
    const j = iV(c);
    if (j === 1 || j === 3) return mul(BUCK, 0.5);                             // the ladder's slots
    return mul(BUCK, (j === 0 ? 1.12 : j === 4 ? 0.88 : 1) * G(c, 0.05));
  }, 'ladder-lock buckle');

  // ---------- wearer's left front corner: utility pouch, blue tape roll on a black strap ----------
  add([4.25, 6.5, -4], [5.5, 10.75, -2.75], corner(11.1, 'left'), 'utility pouch');
  add([4.875, 9.25, -4.25], [5.125, 10.25, -4], c => (c.face === 'back' ? null : mul(BLACK, isPanel(c) ? 1.2 : 0.9)), 'tape strap');
  // the roll hangs nearly edge-on like in the render: axis left-right, so the front shows the blue outer wrap and the
  // sides show the ring with its dark core
  const tape = c => {
    if (isSide(c)) {
      const r = Math.hypot(c.eu - c.fw / 2, c.ev - c.fh / 2);
      if (r < 0.3) return hex('#1c2238');                                      // core hole
      return mul(BLUE, (r > 0.5 ? 0.85 : 1.05) * G(c, 0.04));
    }
    return mul(BLUE, (c.face === 'top' ? 1.2 : c.face === 'bottom' ? 0.7 : c.ev < c.fh / 2 ? 1.1 : 0.95) * G(c, 0.04));
  };
  octa(B, 'body', [5, 10.35, -4.575], [0.75, 1.25, 1.25], 'x', tape, { tag: 'blue tape roll' });

  return { S: K.S, px: K.px, cell: K.cell, boxes: B };
};
