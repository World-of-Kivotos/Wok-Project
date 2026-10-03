// ================= 5.11 Tactical TacTec plate carrier (Ranger Green) — EFT reference (tarkov.dev 5b44cad286f77402a54ae7e5) =================
// In EFT this carrier's gear is part of the item model. Reference render (front, turned a little toward the wearer's
// right), x<0 = wearer's right = viewer's left:
//   weathered yellow-olive Ranger Green; thick padded shoulder straps in a much lighter yellow-green with a khaki band
//   near their front ends, teal mesh lining; the upper front is a lighter khaki laser-cut panel (rows of slots) inset in
//   the olive bag under a light admin band; outboard of it, on the wearer's right, a tall dark hex-mesh window in a thick
//   olive border; two green chemlights (pale caps) tucked behind the panel's top (one behind the tourniquet's top, one
//   behind the pistol mags);
//   on the panel's left edge: a vertical tourniquet holder (dark olive strap, steel rod, two clamp brackets) and a big
//   silver carabiner hooked over the panel edge, hanging over the mesh window, with a black tape roll on it;
//   a light khaki triple pistol-mag pouch (black pistol mags, baseplates up) on the wearer's left chest, a dark grey radio
//   behind it, a black PTT with coiled cord and a whip antenna at the wearer's-left edge;
//   middle: four rifle-mag pouches edge to edge in a green-brown camo (A-TACS-like) with light webbing edges, a tan top
//   binding and yellow shock cords across, each holding a dark blue-grey steel AR mag with a black retention loop behind
//   it and a pistol mag in front; yellow pull cords hang under each pouch, olive band below with two black G-hooks;
//   below: a brownish-olive dump pouch hanging under the band, a small camo pouch at the wearer's-left bottom corner;
//   wearer's right: a fixed-blade knife (steel pommel cap, riveted strap, knurled steel grip, riveted olive sheath that
//   reaches the dump pouch's bottom) hanging from the carrier's front corner beside the mag bank, and a big Ranger Green
//   zip pouch (silver zipper, yellow pull cord, MOLLE rows on its outer face) on the cummerbund side.
// Side pieces sit on the carrier's front corners (Minecraft arms cover the torso sides). The back is not in the render.
ARMORS.tactec_ranger_green = function (mode) {
  const K = kit('M');                                   // always the 2x style
  const { edge, G, isPanel, isSide, px } = K;
  const B = [];
  const add = (a, b, mat, tag, opt = {}) => B.push(box('body', a, b, mat, { tag, ...opt }));
  const flush = (mat, ...faces) => c => (faces.includes(c.face) ? null : mat(c));
  const nU = c => Math.max(1, Math.round(c.fw / px)), nV = c => Math.max(1, Math.round(c.fh / px));
  const iU = c => Math.min(nU(c) - 1, Math.floor(c.eu / px + 1e-6)), iV = c => Math.min(nV(c) - 1, Math.floor(c.ev / px + 1e-6));

  // ---------- palette (sampled off the lit faces of the render) ----------
  const RG = hex('#676d51'), LAS = hex('#8f9166'), ADM = hex('#a8a67a'), PAD = hex('#aaad78'), PADB = hex('#c2b88a');
  const LINING = hex('#2c4a46'), MESH = hex('#4a4737'), SLOT = hex('#3a3b2c'), HEXM = hex('#474b49');
  const PPCH = hex('#9c9f70'), WEB = hex('#86956a'), TANB = hex('#7d7556'), CORDY = hex('#b4ad4c'), CCORD = hex('#9fa347'), TAB = hex('#5e5139');
  const RIFLE = hex('#35414a'), PISTOL = hex('#2d363b'), BASEP = hex('#4d575d'), MAGTOP = hex('#22282c'), DSTEEL = hex('#43484e');
  const BRASS = hex('#caa24e'), COPPER = hex('#b5703f'), LOOPK = hex('#1f2224');
  const BAND = hex('#4f5841'), DUMP = hex('#5b5741'), GP = hex('#77825a'), ZIPT = hex('#8c9089'), ZIPD = hex('#2f3230');
  const KST = hex('#7f898c'), HND = hex('#3f4744'), SHEATH = hex('#3c433c'), THROAT = hex('#4c5650'), RIVET = hex('#a3aaac');
  const TQS = hex('#474f43'), ROD = hex('#6f7670'), SIL = hex('#aab0b0'), TAPE = hex('#2a2b2e');
  const RADIO = hex('#4b4c56'), PTTB = hex('#2e3036'), ANT = hex('#2b2d31'), COIL = hex('#34353d');
  const CHEM = hex('#6a8662'), CAP = hex('#cfcccb'), GHK = hex('#36474d');

  // Cordura: faint low-frequency mottling + per-cell grain
  const fab = (c, base, amt = 0.06) => {
    const q = snap(c.p, K.cell);
    const n = fbm(q[0] * 0.45 + 7, q[1] * 0.45 + 1, q[2] * 0.45 + 3);
    return mul(base, (0.97 + 0.06 * n) * G(c, amt));
  };
  const lin = c => mul(LINING, G(c, 0.08));
  // green-brown camo of the mag pouches (A-TACS-like, greener and darker than A-TACS FG); patches 2-4 px so the two
  // camo cells of each pouch column don't turn into per-cell noise
  const camo = c => mul(layers(snap(c.p, 0.5).map(v => v * 0.5), 0, '#5d6545',
    [['#7a825a', 0.9, 0.56, 9], ['#4a5236', 1.1, 0.58, 31], ['#625a42', 1.3, 0.62, 3], ['#3d4230', 2.0, 0.7, 71]]), G(c, 0.05));
  // laser-cut slot rows, 1 px apart: the slot is the lower cell of each row, 1.5 px long with 0.5 px bridges,
  // alternate rows offset by 1 px (brick pattern); h = coordinate along the face
  const laser = (c, col, y0, y1, h0) => {
    const y = c.p[1];
    if (y < y0 || y >= y1) return null;
    const r = Math.floor(y - y0);
    if (y - y0 - r < 0.5) return null;
    const h = isPanel(c) ? c.p[0] : c.p[2];
    const t = (((h - h0 + (r % 2)) % 2) + 2) % 2;
    return t < 1.5 ? mix(SLOT, col, 0.35) : null;
  };

  // ---------------- carrier ----------------
  // laser-cut panel (x FL0..FL1, proud of the bag) and, outboard of it on the wearer's right, the dark hex-mesh window
  const FL0 = -2.25, FL1 = 3.75, WX0 = -3.75, WY0 = 2.25, WY1 = 6.25;
  const meshWin = c => {
    const i = Math.floor((c.p[0] - WX0) / 0.5), j = Math.floor((c.p[1] - WY0) / 0.5);
    const ni = Math.round((FL0 - WX0) / 0.5), nj = Math.round((WY1 - WY0) / 0.5);
    let col = mul(HEXM, G(c, 0.08) * (j % 2 ? 0.86 : 1));                      // faint lattice rows, 1 px period
    if ((i === 0 || i === ni - 1) && (j === 0 || j === nj - 1)) col = mix(col, fab(c, RG), 0.55);   // rounded corners
    return col;
  };
  const frontBag = c => {
    if (c.face === 'top' || c.face === 'back') return lin(c);                     // teal mesh lining at the neckline
    if (c.face === 'bottom') return mul(fab(c, RG), 0.7);
    let col = fab(c, RG);
    if (c.face === 'front') {
      const x = c.p[0], y = c.p[1];
      if (y < 1.75) return edge(c, fab(c, ADM));                                  // light admin band on top
      if (x > WX0 && x < FL0 && y > WY0 && y < WY1) return meshWin(c);
      if (y < 6.75) return mul(col, x < WX0 ? 1.06 : 1);                          // thick olive border around window + panel
    }
    return edge(c, col);
  };
  add([-4.25, 1.25, -3.25], [4.25, 10.5, -2.5], frontBag, 'front plate bag');
  const flap = c => {
    if (c.face === 'back') return null;
    const col = fab(c, LAS);
    if (c.face === 'top') return mul(fab(c, ADM), 1.05);
    if (c.face !== 'front') return edge(c, mul(col, 0.8), { stitch: false });
    return edge(c, laser(c, col, 2.5, 7, -4.25) || col, { stitch: false });
  };
  add([FL0, 1.75, -3.75], [FL1, 6.75, -3.25], flap, 'laser-cut front panel');
  const backBag = c => {
    if (c.face === 'top' || c.face === 'front') return lin(c);
    if (c.face === 'bottom') return mul(fab(c, RG), 0.7);
    const col = fab(c, RG);
    if (c.face === 'back' && Math.abs(c.p[0]) < 3.75) return edge(c, laser(c, col, 2, 10, -4.25) || col);
    return edge(c, col);
  };
  add([-4.25, 0.75, 2.5], [4.25, 10.5, 3.25], backBag, 'back plate bag');
  // cummerbund: three webbing bands over the dark brown spacer mesh
  const cumm = c => {
    let col = mul(fab(c, RG), 0.96);
    if (c.face !== 'top' && c.face !== 'bottom') {
      const dy = c.p[1] - 5.5;
      if (dy >= 0 && dy < 4) {
        const k = dy % 1.5;
        col = k >= 1 ? mul(MESH, G(c, 0.06)) : mul(col, k < 0.5 ? 1.06 : 0.96);
      }
    }
    return edge(c, col, { stitch: false });
  };
  // ends above both bags' bottoms so no bottom faces share a plane
  add([-4.5, 5, -2.75], [4.5, 10.25, 2.75], cumm, 'cummerbund');
  add([-1.25, 0.5, 3.25], [1.25, 1.5, 3.75], flush(c => edge(c, mul(fab(c, RG), 0.72), { stitch: false }), 'front'), 'drag handle');

  // ---------------- padded shoulder straps ----------------
  // (the head hides the straps beside the neck: a thick pad on top of each shoulder, the strap ends on the front / back)
  const hump = c => {
    if (c.face === 'bottom') return lin(c);
    const col = fab(c, PAD, 0.07);
    if (isPanel(c)) return mul(col, 0.9);
    return c.face === 'top' ? mul(col, 1.08) : col;
  };
  // front strap end: the lower visible row is the khaki band across the strap
  const riser = c => {
    if (c.face === 'bottom' || c.face === 'back') return lin(c);
    const y = c.p[1];
    if (y >= 0.75) return mul(PADB, G(c, 0.05) * (c.face === 'front' ? 1 : 0.85));
    return edge(c, fab(c, PAD, 0.07), { stitch: false });
  };
  const riserBack = c => {
    if (c.face === 'bottom' || c.face === 'front') return lin(c);
    return edge(c, fab(c, PAD, 0.07), { stitch: false });
  };
  for (const s of [-1, 1]) {
    const X = (a, b) => (s < 0 ? [a, b] : [-b, -a]);
    const [b0, b1] = X(-5.75, -4.25), [h0, h1] = X(-5.5, -4.5);
    add([b0, -1.25, -3.5], [b1, -0.25, 3.5], hump, 'shoulder pad');
    add([h0, -1.75, -3.25], [h1, -0.75, 3.25], hump, 'shoulder pad crown');
    const [r0, r1] = X(-4.25, -2.25);
    add([r0, -0.25, -3.75], [r1, 1.25, -2.5], riser, 'strap front');
    const [k0, k1] = X(-4.5, -2.25);
    add([k0, -0.25, 2.75], [k1, 1.5, 3.5], riserBack, 'strap back');
  }

  // ---------------- upper chest ----------------
  // two green chemlights tucked behind the panel's top (pale caps up): one right behind the tourniquet's top, one behind
  // the pistol mags (it shows above them and in the gap between the first two)
  const chem = c => {
    if (c.face === 'top') return mul(CAP, 1.1);
    const k = isPanel(c) ? 1 : 0.82, v = c.p[1] - c.box.y;
    if (v < 0.5) return mul(CAP, k * G(c, 0.03));
    return mul(CHEM, k * G(c, 0.05) * (v < 1 ? 1.1 : 1));
  };
  add([-1, 0.75, -3.625], [-0.5, 2.5, -3.125], chem, 'chemlight');
  add([0.625, 0.25, -3.625], [1.125, 2.5, -3.125], chem, 'chemlight');
  // tourniquet holder on the panel's left part: dark olive strap, steel windlass rod standing vertically, two clamp
  // brackets across; the rod's lower end comes down beside the first rifle mag's feed lips, resting on its loop
  add([-1.5, 1.75, -4], [-0.5, 4.25, -3.75], flush(c => {
    const col = mul(TQS, G(c, 0.06));
    if (c.face === 'top') return mul(col, 1.3);
    if (c.face !== 'front') return mul(col, 0.8);
    return iV(c) === 0 ? mul(col, 1.15) : col;
  }, 'back'), 'TQ strap');
  add([-1.25, 1.25, -4.375], [-0.75, 4.75, -3.875], c => {
    if (c.face === 'top') return mul(ROD, 1.35);
    const col = mul(ROD, G(c, 0.04));
    if (isSide(c)) return mul(col, 0.8);
    return isPanel(c) && iV(c) === 0 ? mul(col, 1.2) : col;
  }, 'TQ windlass rod');
  const bracket = c => {
    const col = mul(TQS, 1.12 * G(c, 0.05));
    if (c.face === 'top') return mul(col, 1.25);
    if (c.face !== 'front') return mul(col, 0.8);
    return iV(c) === 0 ? mul(col, 1.1) : mul(col, 0.92);
  };
  for (const y0 of [2, 3.5]) add([-1.625, y0, -4.5], [-0.375, y0 + 0.5, -4], flush(bracket, 'back'), 'TQ clamp bracket');
  // silver carabiner hooked over the panel's left edge, hanging over the mesh window (frame one cell thick, darker
  // screw-lock sleeve on its spine); its gate side rests on the panel
  const carab = c => {
    const t = px, inU = c.eu > t && c.eu < c.fw - t, inV = c.ev > t && c.ev < c.fh - t;
    if (c.face === 'back') return null;
    if (c.face === 'front') {
      if (inU && inV) return null;                                              // the opening
      if (!inU && !inV) return null;                                            // rounded corners
      if (c.eu < t && c.ev > c.fh * 0.45 && c.ev < c.fh * 0.8) return mul(SIL, 0.68);   // lock sleeve
      return mul(SIL, c.ev < c.fh / 2 ? 1.1 : 0.88);
    }
    if ((c.face === 'top' || c.face === 'bottom') && !inU) return null;
    if ((c.face === 'left' || c.face === 'right') && !inV) return null;
    return mul(SIL, 0.75);
  };
  add([-3.25, 1.25, -4], [-1.75, 4.25, -3.75], carab, 'carabiner');
  // black tape roll on the carabiner's bottom bar (the bar shows through the hole); round-ish: darker corner cells
  add([-3.375, 3.25, -4.5], [-1.875, 4.75, -4], c => {
    if (c.face === 'back') return null;
    if (c.face === 'front') {
      const i = iU(c), j = iV(c);
      if (i === 1 && j === 1) return null;
      return mul(TAPE, (i !== 1 && j !== 1 ? 0.7 : j === 0 ? 1.6 : j === 1 ? 1.2 : 1) * G(c, 0.04));   // glossy top
    }
    return mul(TAPE, (c.face === 'top' ? 1.3 : 0.8) * G(c, 0.04));
  }, 'tape roll');

  // light khaki triple pistol-mag pouch, black pistol mags standing baseplate-up, dark grey radio behind them
  const ppouch = c => {
    if (c.face === 'back') return null;
    const col = fab(c, PPCH);
    if (c.face === 'top') return c.p[2] < -4.25 ? mul(col, 1.12) : mul(LINING, 0.9 * G(c, 0.08));   // open mouths
    if (c.face === 'bottom') return mul(col, 0.7);
    if (c.face !== 'front') return edge(c, mul(col, 0.82), { stitch: false });
    const j = iV(c), i = iU(c);
    if (j === 0) return mul(col, 1.12);                                          // elastic top binding
    if (j === nV(c) - 1) return mul(col, 0.85);
    // each pouch: a lit left half and a shaded right half, so the three columns read like the original
    return i % 2 === 1 ? mul(col, 0.84) : mul(col, 1.03);
  };
  // (high on the chest, its bottom right at the top of the rifle mags' retention loops, as in the render)
  add([0, 1.75, -4.75], [3, 3.75, -3.75], ppouch, 'triple pistol mag pouch');
  const pmagUp = c => {
    if (c.face === 'top') return mul(BASEP, 1.2);
    if (c.face === 'bottom') return mul(PISTOL, 0.6);
    const col = mul(PISTOL, G(c, 0.04));
    if (!isPanel(c)) return mul(col, 0.75);
    return iV(c) === 0 ? mul(BASEP, G(c, 0.04)) : col;                          // baseplate
  };
  for (const cx of [0.5, 1.5, 2.5]) add([cx - 0.375, 0.75, -4.5], [cx + 0.375, 2.75, -4], pmagUp, 'pistol mag (chest)');
  add([1, 0.5, -4], [2.5, 2.25, -3.25], flush(c => {
    if (c.face === 'top') return mul(RADIO, 1.3);
    const col = mul(RADIO, G(c, 0.05));
    if (c.face !== 'front') return mul(col, 0.8);
    return iV(c) === 0 ? mul(col, 1.15) : col;
  }, 'back'), 'radio');
  // coiled cord from the strap end down to the PTT; whip antenna rising behind the PTT
  // (cord and antenna reach back to the bag so no daylight shows behind them above the panel)
  add([3, 0.75, -4.25], [3.5, 2.25, -3.25], flush(c => mul(COIL, (Math.floor(c.p[1]) % 2 ? 0.75 : 1.2) * (isPanel(c) ? 1 : 0.85)), 'back'), 'coiled PTT cord');
  add([3, 2.25, -4.5], [4, 3.75, -3.75], flush(c => {
    if (c.face === 'top') return mul(PTTB, 1.3);
    if (c.face !== 'front') return mul(PTTB, 0.9);
    return iV(c) === 0 ? mul(PTTB, 1.3) : hex('#1b1c1f');                      // lit rim, speaker grille
  }, 'back'), 'PTT');
  add([3.625, 0.5, -4.25], [4.125, 3.25, -3.25], flush(K.plastic(ANT), 'back'), 'antenna');

  // ---------------- mag bank: four rifle-mag pouches edge to edge ----------------
  // As wide as on the render (about four fifths of the front, from just right of the mesh window to the carrier's
  // left edge), so the knife hangs in front of the carrier corner beside it. One box per pouch, 1.75 px each, so every
  // pouch gets the same texel pattern.
  const X0 = -2.5, PW = 1.75;                          // pouch i spans X0 + PW*i .. X0 + PW*(i+1)
  const bank = c => {
    if (c.face === 'back') return null;
    if (c.face === 'top') {
      if (c.p[2] < -4.25) return mul(mix(camo(c), TANB, 0.5), 1.15);              // front wall rim
      if (c.p[2] > -3.75) return mul(camo(c), 0.95);                               // back wall
      return mul(LINING, 0.9 * G(c, 0.08));                                        // open mouth
    }
    if (c.face === 'bottom') return mul(camo(c), 0.6);
    const col = camo(c);
    if (c.face !== 'front') return edge(c, mul(col, 0.82), { stitch: false });
    const k = Math.min(3, Math.floor((c.p[0] - c.box.x) / 0.5)), j = Math.floor((c.p[1] - 6.75) / 0.5);
    // per pouch: light webbing strip | camo | camo | narrow dark gap to the next pouch
    if (k === 3) return mul(col, 0.6);
    const web = mul(mix(col, WEB, 0.75), 1.02);
    if (j === 0) return mul(mix(col, TANB, 0.6), 1.12);                           // tan top binding
    let base = k === 0 ? web : col;
    if (j === 2 || j === 4) base = mix(base, CORDY, k === 0 ? 0.4 : 0.6);         // yellow shock cords laced across
    if (j >= 6) base = mul(base, 0.82);
    return base;
  };
  for (let i = 0; i < 4; i++) add([X0 + PW * i, 6.75, -4.75], [X0 + PW * (i + 1), 10.25, -3.25], bank, 'rifle mag pouch');
  // magazines go 1.5 px down into their pouches; broad side forward, darker spine, one dark rib, feed lips + brass round
  const magPaint = base => c => {
    if (c.face === 'bottom') return mul(base, 0.6);
    if (c.face === 'top') return mul(MAGTOP, c.ex < px ? 1.5 : 1);
    const col = mul(base, G(c, 0.04));
    if (!isPanel(c)) return mul(col, 0.74);
    if (c.ev < px) return mul(col, 1.25);                                         // feed-lip edge
    const i = iU(c);
    return i === 0 ? mul(col, 1.15) : i === 1 ? mul(col, 0.76) : col;           // lit edge, dark rib
  };
  const rifle = magPaint(RIFLE);
  const lips = c => (c.face !== 'top' ? mul(DSTEEL, isPanel(c) ? 1.15 : 0.9) : c.eu > c.fw - px ? COPPER : BRASS);
  const pmagFront = c => {
    if (c.face === 'top') return mul(BASEP, 1.25);
    if (c.face === 'bottom') return mul(PISTOL, 0.6);
    const col = mul(PISTOL, G(c, 0.04));
    if (!isPanel(c)) return mul(col, 0.75);
    return iV(c) === 0 ? mul(BASEP, 1.1 * G(c, 0.04)) : col;
  };
  // black retention loop rising behind each mag: an arch (the panel shows through its opening)
  const loop = c => {
    if (isPanel(c)) {
      const i = iU(c), j = iV(c);
      if (j === 1 && i === 1) return null;
      return mul(LOOPK, (j === 0 ? 1.3 : 1) * G(c, 0.05));
    }
    return mul(LOOPK, c.face === 'top' ? 1.4 : 0.9);
  };
  for (let i = 0; i < 4; i++) {
    const x0 = X0 + PW * i;
    // the rifle mag stands 1.5 px of body above the pouch, as in the render, the pistol mag (narrower, toward the
    // pouch's wearer's-left edge) only a little; the loop is a little wider than the mag so its arch reads
    add([x0 + 0.25, 5.25, -4.25], [x0 + 1.5, 9.5, -3.75], rifle, 'rifle mag');
    add([x0 + 0.5, 4.75, -4.125], [x0 + 1.25, 5.25, -3.875], lips, 'rifle mag feed lips');
    add([x0 + 0.875, 6, -4.625], [x0 + 1.625, 8.5, -4.125], pmagFront, 'pistol mag (front sleeve)');
    add([x0 + 0.125, 4.25, -3.875], [x0 + 1.625, 5.25, -3.375], loop, 'retention loop');
  }
  // olive band under the pouches, yellow pull cords with tan tabs, two black G-hooks on its lower edge
  add([X0, 10.25, -4.5], [X0 + 4 * PW, 11, -3.25], flush(c => {
    const col = fab(c, BAND);
    if (c.face === 'bottom') return mul(col, 0.7);
    if (c.face !== 'front') return mul(col, 0.82);
    return iV(c) === 0 ? mul(col, 1.08) : mul(col, 0.92);
  }, 'back'), 'pouch band');
  const cord = c => {
    const v = c.p[1] - c.box.y;
    if (c.face === 'top') return mul(TAB, 1.2);
    const k = isPanel(c) ? 1 : 0.8;
    if (v < 0.5) return mul(TAB, k * G(c, 0.05));
    return mul(CCORD, k * G(c, 0.06) * (v > c.box.h - 0.5 ? 0.82 : 1));
  };
  // (a brown pull tab at the pouch bottom, the cord swinging a little to one side, so it reads as a cord, not a round)
  // (cords a little wearer's-left of each pouch's middle; G-hooks under the first pouch's wearer's-left half and the
  // second pouch's wearer's-right half, as in the render)
  [[0.625, 14], [1, -12], [1, 12], [0.75, -14]].forEach(([o, a], i) => {
    const x = X0 + PW * i + o;
    add([x, 10, -5], [x + 0.5, 11.5, -4.5], flush(cord, 'back'), 'pull cord', { rot: [0, 0, a], pivot: [x + 0.25, 10, -4.75] });
  });
  for (const x of [-1.25, -0.25]) add([x, 11, -4.5], [x + 0.5, 12, -4], flush(c => {
    if (c.face === 'top') return mul(GHK, 1.3);
    return mul(GHK, (isPanel(c) ? (iV(c) === 0 ? 1.2 : 0.85) : 0.8) * G(c, 0.04));
  }, 'back'), 'G-hook');

  // ---------------- dump pouch + small camo pouch ----------------
  const dump = c => {
    const u = c.p[0] + 2, v = c.p[1] - 11;
    if (v > 3.5 && (u < 0.5 || u > 4.5)) return null;                            // soft rounded bottom corners
    if (isSide(c) && v > 3.5) return null;
    const col = fab(c, DUMP, 0.05);
    if (c.face === 'top') return mul(col, 0.85);
    if (c.face === 'back') return mul(col, 0.7);
    if (c.face === 'bottom') return mul(col, 0.65);
    const fold = mul(col, 0.9 + 0.2 * fbm(c.p[0] * 0.55 + 4, c.p[1] * 0.55 + 2, 5));   // soft creases
    if (isSide(c)) return mul(fold, 0.8);
    const j = iV(c);
    if (j === 0) return mul(fold, 1.12);                                          // gathered, lit top lip
    if (j === 1) return mul(fold, 0.9);
    if (v > 3.5 || (v > 3 && (u < 1 || u > 4))) return mul(fold, 0.82);
    return u < 0.5 || u > 4.5 ? mul(fold, 0.9) : fold;
  };
  add([-2, 11, -4], [3, 15, -2.75], dump, 'dump pouch');
  add([2.75, 10.75, -4.75], [4.25, 12.75, -3.75], flush(c => {
    const col = camo(c);
    if (c.face === 'top') return mul(mix(col, TANB, 0.4), 1.1);
    if (c.face === 'bottom') return mul(col, 0.6);
    if (c.face !== 'front') return edge(c, mul(col, 0.8), { stitch: false });
    const i = iU(c), j = iV(c);
    if (i === 0 && j > 0) return mul(hex('#26292a'), G(c, 0.05));               // dark open lid / insert
    if (j === 0) return mul(col, 1.12);
    return j === nV(c) - 1 ? mul(col, 0.8) : col;
  }, 'back'), 'small camo pouch');

  // ---------------- wearer's right: knife + zip pouch ----------------
  // the knife hangs in front of the carrier's front corner, right under the mesh window and beside the mag bank;
  // knife grip, top to bottom: steel pommel cap, dark riveted retention strap, long knurled steel grip
  add([-3.625, 6, -3.875], [-2.875, 8.5, -3.375], c => {
    const v = c.p[1] - 6;
    if (c.face === 'top') return mul(KST, 1.3);
    if (c.face === 'bottom') return mul(HND, 0.7);
    const k = isPanel(c) ? 1 : 0.8;
    if (v < 0.5) return mul(KST, k * 1.1 * G(c, 0.04));                          // pommel cap
    if (v < 1.5) {                                                                 // retention strap, two rivets
      const col = mul(HND, k * G(c, 0.05));
      return isPanel(c) && iU(c) === 0 ? mix(col, RIVET, 0.35) : col;
    }
    return mul(KST, k * ((v - 1.5) % 1 < 0.5 ? 1 : 0.9) * G(c, 0.04));          // knurled steel grip
  }, 'knife grip');
  // sheath: dark riveted throat, then the olive sheath down to the dump pouch's bottom, step to the tip piece
  add([-3.75, 8.5, -3.75], [-2.75, 15, -3.25], c => {
    const v = c.p[1] - 8.5;
    if (c.face === 'top') return mul(THROAT, 1.15);
    if (c.face === 'bottom') return mul(SHEATH, 0.7);
    let col = mul(v < 1 ? THROAT : SHEATH, G(c, 0.05));
    if (isPanel(c)) {
      if (v >= 0.5 && v < 1) col = mix(col, RIVET, 0.4);                         // rivets on the throat
      else if (v >= 3.5 && v < 4) col = mul(col, 1.15);                           // lit step onto the tip piece
      else if (v >= 4) col = mul(col, 0.94);
    } else col = mul(col, 0.8);
    return c.face === 'back' ? mul(col, 0.8) : col;
  }, 'knife sheath');
  const gp = c => {
    const col = fab(c, GP);
    if (c.face === 'top') return mul(col, 1.1);
    if (c.face === 'bottom') return mul(col, 0.7);
    // (its back face is cut where it lies inside the carrier's corner, so it never shares the bag's inner plane)
    if (c.face === 'back') return c.p[0] > -4.25 ? null : mul(col, 0.75);
    if (c.face === 'left') return mul(col, 0.85);
    if (c.face === 'right') {                                                     // outer face: MOLLE rows across it
      const v = c.p[1] - 6.75;
      let k = 1;
      if (v < 0.5) k = 1.1;                                                       // lit top rim
      else if (v > 5) k = 0.78;                                                   // darker bottom row
      else if ((v - 1) % 1.5 < 0.5 && v > 1) k = 0.72;                            // webbing rows, 1.5 px apart
      return mul(col, k * (c.p[2] > -3.25 ? 0.88 : 1));                           // darker back-edge column
    }
    const i = iU(c), j = iV(c);
    if (i === nU(c) - 1) return mix(j % 4 === 3 ? ZIPD : ZIPT, col, 0.15);        // zipper down the inner front edge
    if (j === 0) return mul(col, 1.1);
    return edge(c, col, { stitch: false });
  };
  // seated on the carrier's front corner (its inner edge sunk into the bag and cummerbund corner, pressed against the
  // knife sheath), 0.25 px in front of the sleeve; the arm still passes through it when it swings forward
  add([-5.25, 6.75, -4.25], [-3.75, 12.25, -2.5], gp, 'zip pouch');
  add([-4.25, 11.5, -4.5], [-3.75, 12.75, -4.25], flush(c => {
    const v = c.p[1] - 11.5;
    if (c.face === 'top') return mul(ZIPD, 1.4);
    if (v < 0.5) return mul(ZIPD, (isPanel(c) ? 1.2 : 0.9) * G(c, 0.04));
    return mul(CORDY, (isPanel(c) ? 1 : 0.8) * G(c, 0.06));
  }, 'back'), 'zip pull + cord');

  return { S: K.S, px: K.px, cell: K.cell, boxes: B };
};
