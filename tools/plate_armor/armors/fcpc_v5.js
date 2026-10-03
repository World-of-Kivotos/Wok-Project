// ================= Ferro Concepts FCPC V5 plate carrier (Black Division) — EFT reference (tarkov.dev 689479cb47e5acd1e10be986) =================
// Coyote-brown carrier, heavily soiled (dark grime stains). The EFT wiki gallery adds straight front / back / left / right
// views of the same item model. Read off them (x<0 = wearer's right):
//   shoulders: wide padded tan covers with two dark oval holes on top, dark teal mesh underneath, short straps down to the
//     bags with silver QD buckles just above the carrier top
//   upper chest: a big light-tan rugged phone case (Samsung in a Juggernaut PALS mount) with a coyote velcro patch in the
//     middle and a black triple-camera block top right (wearer's left; the plate runs down under its middle and outer
//     lenses, a small flash dot in that lower part); black
//     mount clamps at both ends, a black TEA PTT under the wearer's-right clamp; a small tan bracket under the phone
//   front: KTAR triple mag placard, laser-cut pouches with a V-notched mouth, black PMAGs standing about half their
//     pouch height out of them, each held by a tan shock cord with a brown pull tab hanging from under the phone mount;
//     a red-capped decompression needle down the wearer's-right pouch; a black CAT tourniquet lying across the middle
//     and wearer's-left pouches (grey "TIME" strap at its wearer's-left end)
//   below: a fat round tan Roll-1 trauma pouch hanging under the placard, short strap tails dangling from both ends
//   corners: green AN/PRC-152 (wearer's right) and black AN/PRC-148 (wearer's left) in the cummerbund wings, their lower
//     ends showing under the wings; grey connector stubs on top
//   cummerbund: ADAPT 3" — tan, the middle third a light grey elastic band full of round brown grommets
//   back: laser-cut back panel with a zipper down each edge, orange ASP restraint + black ASP restraint on the wearer's
//     left, a long black whip antenna on the wearer's right rising from the bottom of the panel to well above the
//     shoulder; two antennas (the outer one taller) rising behind the wearer's left shoulder
// Inner lining / edge binding is a dark teal-green.
ARMORS.fcpc_v5 = function (mode) {
  const K = kit('M');                          // always the 2x style
  const { edge, G, isPanel, isSide } = K;
  const cw = K.cell;                           // one art cell (0.5 px)
  const B = [];
  const add = (a, b, mat, tag, opt = {}) => B.push(box('body', a, b, mat, { tag, ...opt }));
  const md = (v, m) => ((v % m) + m) % m;
  const X = (s, a, b) => (s < 0 ? [a, b] : [-b, -a]);   // x-range on the wearer's right (s<0) or mirrored to the left

  // ---------- palette (lit parts of the render; set so the shaded model matches the render's brightness) ----------
  const CB = hex('#aa8c65');                   // carrier coyote (a light sandy tan in the render)
  const POUCH = hex('#a78a61');                // KTAR pouches
  const CUMM = hex('#a88a60');                 // cummerbund / wings
  const PAD = hex('#b99f7a');                  // shoulder pad covers
  const ROLL = hex('#a08663');                 // trauma roll (a little duller / greener than the pouches)
  const PHONE = hex('#d0b487'), VELC = hex('#957550');
  const GRIME = hex('#5a4630'), LINING = hex('#23302a'), MESH = hex('#2c3832'), BINDT = hex('#364031');
  const ELASTIC = hex('#b3b2ad'), GROM = hex('#6e4d2e');
  const MAG = hex('#43423f'), MAGTOP = hex('#232324'), DSTEEL = hex('#4a4f55'), BRASS = hex('#caa24e'), COPPER = hex('#b5703f');
  const CORD = hex('#ad9169'), TAB = hex('#7a5a3a');
  const RED = hex('#c8503b'), TUBE = hex('#b3afaa');
  const TQB = hex('#242625'), TIME = hex('#a3a4a0');
  const BLACK = hex('#2a2a2a'), GREEN = hex('#5d6e4b'), R148 = hex('#424343'), CONN = hex('#666866');
  const ORANGE = hex('#d85a38'), ZIP = hex('#6f5838'), QD = hex('#a9aaa5');

  // Cordura: slow tone drift + soft grime stains (1-3 px, sampled on the art grid); gr = stain strength, th = how much
  // of the surface is stained (lower = more)
  const fab = (c, base, amt = 0.06, gr = 1, th = 0.56) => {
    const q = snap(c.p, cw);
    const tone = 0.95 + 0.1 * fbm(q[0] * 0.35 + 7, q[1] * 0.35 + 3, q[2] * 0.35 + 1);
    const n = fbm(q[0] * 0.5 + q[1] * 0.12 + 11, q[1] * 0.4 + 5, q[2] * 0.5 + 17);
    return mul(mix(base, GRIME, sm(clamp01((n - th) * 3)) * 0.34 * gr), tone * G(c, amt));
  };
  const lining = c => mul(LINING, G(c, 0.08));
  const underside = (c, col) => mix(mul(col, 0.72), BINDT, 0.4);   // bottom faces: dark teal edge binding
  const plastic = col => c => mul(col, c.face === 'top' ? 1.45 : c.face === 'bottom' ? 0.7 : isPanel(c) ? (c.ev < cw ? 1.25 : 1) : 0.85);

  // cummerbund face: tan, a light grey elastic band (y 7.75..8.75, the middle third) with round brown grommets spaced
  // about their own width apart (period 2 px: 2 grommet cells + 2 light elastic cells), soft vertical stitch channels
  const cummPaint = (c, h) => {
    const y = c.p[1];
    const col = fab(c, CUMM);
    const k = Math.floor(md(h + 0.5, 2) / cw);
    if (y >= 7.75 && y < 8.75) return k >= 2 ? mul(ELASTIC, G(c, 0.06)) : mul(GROM, G(c, 0.06) * (y < 8.25 ? 1.08 : 0.94));
    return k === 3 ? mul(col, 0.88) : col;
  };

  // ================= carrier =================
  const frontBag = c => {
    if (c.face === 'top' || c.face === 'back') return lining(c);
    let col = fab(c, CB);
    if (c.face === 'bottom') return underside(c, col);
    if (c.face === 'front') {
      const y = c.p[1];
      if (y >= 4.25 && y < 4.75) col = mul(col, 1.06);                         // placard's top binding under the phone
      else if (y >= 4.75 && y < 7) col = mul(col, 0.8);                         // shadowed placard behind the mags
    }
    return edge(c, col);
  };
  add([-3.75, 0.5, -3.25], [3.75, 10.75, -2.5], frontBag, 'front plate bag');
  // back panel: laser-cut slit rows (1.5 px period, 1.5 px slits with 0.5 px bridges), a zipper down each edge, big
  // dark water stains like the render
  const backBag = c => {
    if (c.face === 'top' || c.face === 'front') return lining(c);
    const col = fab(c, CB, 0.06, 2.2, 0.42);
    if (c.face === 'bottom') return underside(c, col);
    if (c.face === 'back') {
      const x = c.p[0], y = c.p[1], ax = Math.abs(x);
      if (ax > 3.25) return mul(mix(col, ZIP, 0.6), G(c, 0.05));               // zipper tape
      if (y >= 2 && y < 10 && md(y - 2, 1.5) < cw && ax < 3 && md(x + 3, 2) < 1.5) return mix(col, LINING, 0.5);
    }
    return edge(c, col);
  };
  add([-3.75, 0.5, 2.5], [3.75, 10.75, 3.25], backBag, 'back plate bag');
  const cumm = c => {
    if (c.face === 'bottom') return mul(fab(c, CUMM), 0.6);
    if (c.face === 'top') return mul(fab(c, CUMM), 1.04);
    return cummPaint(c, isSide(c) ? c.p[2] : c.p[0]);
  };
  // ADAPT 3": 3 px tall, the elastic is its middle third
  add([-4.5, 6.75, -3], [4.5, 9.75, 3], cumm, 'cummerbund');
  // cummerbund wings at the front corners: the outer layer the radios tuck behind (their lower ends show under it)
  const wing = c => {
    if (c.face === 'back') return lining(c);
    if (c.face === 'top') return mul(fab(c, CUMM), 1.05);
    if (c.face === 'bottom') return mul(fab(c, CUMM), 0.62);
    const col = cummPaint(c, isSide(c) ? c.p[2] : c.p[0]);
    return isSide(c) ? mul(col, 0.86) : (c.ev > c.fh - cw ? mul(col, 0.84) : col);
  };
  for (const s of [-1, 1]) {
    const [w0, w1] = X(s, -5.5, -3.5);
    add([w0, 6.75, -4], [w1, 10, -3], wing, 'cummerbund wing');
  }

  // ================= shoulder straps =================
  const pad = c => {
    if (c.face === 'bottom') return mul(MESH, G(c, 0.08));                      // dark teal mesh underside
    let col = fab(c, PAD, 0.07, 0.8);
    if (c.face === 'top') {
      // two dark oval holes near the front end of the pad (centre column, 1 x 2 cells each)
      const z = c.p[2], mid = Math.abs(c.eu - c.fw / 2) < cw * 0.6;
      if (mid && ((z >= -2.25 && z < -1.25) || (z >= -0.75 && z < 0.25))) return mul(mix(PAD, GRIME, 0.55), G(c, 0.05));
      return mul(col, 1.06);
    }
    if (isPanel(c)) return mul(col, 0.9);
    return edge(c, col, { stitch: false });
  };
  // risers stop 0.25 into the head (deeper ones poke through the jaw / back of the head when it turns); the front ones
  // carry a silver QD buckle just above the carrier top and reach down behind the phone
  const riser = front => c => {
    if (c.face === 'bottom' || c.face === (front ? 'back' : 'front')) return lining(c);
    if (front && c.face === 'front') {
      const i = Math.floor(c.eu / cw), j = Math.floor(c.ev / cw);
      if (j === 1 && (i === 1 || i === 2)) return mul(QD, G(c, 0.04) * (i === 1 ? 1.1 : 0.92));
    }
    return edge(c, fab(c, mix(CB, PAD, 0.4), 0.07), { stitch: false });
  };
  for (const s of [-1, 1]) {
    const [p0, p1] = X(s, -5.75, -4.25), [r0, r1] = X(s, -4.5, -2.5);
    add([p0, -1.25, -3.25], [p1, -0.25, 3.25], pad, 'shoulder pad');
    add([r0, -0.25, -3.75], [r1, 1.5, -3.25], riser(true), 'strap front');
    add([r0, -0.25, 3.25], [r1, 1.25, 3.75], riser(false), 'strap back');
  }

  // ================= phone in its PALS mount =================
  // clean light-tan case (no grime); coyote velcro patch in the middle, black triple-camera block top right (top row:
  // three lenses; the plate runs down one more cell under the middle and outer lenses), rubber bumpers at both ends
  const LENS = hex('#151618');
  const phone = c => {
    if (c.face === 'back') return lining(c);
    const col = mul(PHONE, G(c, 0.04));
    if (c.face === 'top') return mul(col, 1.08);
    if (c.face === 'bottom') return mul(col, 0.66);
    if (!isPanel(c)) return mul(col, 0.8);
    const i = Math.floor(c.eu / cw), j = Math.floor(c.ev / cw), n = Math.round(c.fw / cw), m = Math.round(c.fh / cw);
    if (i >= 4 && i <= 7 && j >= 1 && j <= 4) return mul(VELC, G(c, 0.1) * (j === 4 ? 0.9 : 1));   // velcro patch
    if (j === 1 && i >= 8 && i <= 10) return mul(LENS, i === 9 ? 1.4 : 1);                        // three lenses
    // the plate runs down under the middle and outer lenses only (phone tan under the inner one); a faint flash dot
    // in its outer corner
    if (j === 2 && (i === 9 || i === 10)) return i === 10 ? mix(mul(BLACK, 1.25), hex('#a9a99c'), 0.3) : mul(BLACK, 1.25);
    if (j === 0) return mul(col, 1.08);
    if (j === m - 1) return mul(col, i >= 4 && i <= 7 ? 0.92 : 0.8);          // lower edge (the mount's middle stays lit)
    if (i === 0 || i === n - 1) return mul(col, 0.84);                          // bumpers
    return col;
  };
  add([-3, 1.25, -4], [3, 4.25, -3.25], phone, 'phone case');
  add([-1.25, 4.25, -3.75], [1.25, 4.75, -3.25], c => {
    if (c.face === 'back') return lining(c);
    return mul(PHONE, G(c, 0.05) * (c.face === 'front' ? 0.86 : c.face === 'bottom' ? 0.62 : 0.78));
  }, 'phone mount bracket');
  const clamp = c => mul(BLACK, G(c, 0.05) * (c.face === 'top' ? 1.4 : c.face === 'bottom' ? 0.7 : isPanel(c) ? (c.ev < cw ? 1.2 : 1) : 0.85));
  add([3, 1.75, -3.75], [3.75, 4.25, -3.25], clamp, 'mount clamp (left)');
  add([-3.5, 1.75, -3.75], [-3, 3.25, -3.25], clamp, 'mount clamp (right)');
  // TEA PTT under the wearer's-right clamp, resting on the radio: black body, big round button (2x2 cells, lit top-left)
  add([-4.25, 3, -4.25], [-3.25, 4.25, -3.25], c => {
    if (c.face !== 'front') return mul(BLACK, c.face === 'top' ? 1.35 : 0.82);
    const i = Math.floor(c.eu / cw), j = Math.floor(c.ev / cw);
    if (j >= 1 && i <= 1) return mul(hex('#3c3d3d'), j === 1 && i === 0 ? 1.2 : 1);
    return mul(BLACK, j === 0 ? 1.2 : 0.95);
  }, 'PTT');

  // ================= KTAR mag placard =================
  const pouch = c => {
    if (c.face === 'back') return lining(c);
    if (c.face === 'top') return c.ex < cw ? mul(POUCH, 1.1) : mul(LINING, 1.2);  // open mouth: light rim, dark inside
    const col = fab(c, POUCH);
    if (c.face === 'bottom') return underside(c, col);
    if (!isPanel(c)) return mul(col, 0.84);
    const i = Math.floor(c.eu / cw), j = Math.floor(c.ev / cw), n = Math.round(c.fw / cw);
    if (i === 0 || i === n - 1) return mul(col, 0.78);                          // pouch sides (keeps the three apart)
    if (j === 0) return i === 2 ? mix(col, LINING, 0.55) : mul(col, 1.1);       // bound top edge with the V notch
    if (j === 2 || j === 5) return mix(col, LINING, 0.35);                      // laser-cut slits
    if (j === 6) return mul(col, 0.84);
    return col;
  };
  // PMAGs: body + feed-lip block with one brass round; front: lit feed-lip edge, a darker centre rib (it shows on both
  // sides of the shock cord running down the middle); darker spine sides
  const magPaint = c => {
    if (c.face === 'bottom') return mul(MAG, 0.6);
    if (c.face === 'top') return mul(MAGTOP, c.ex < cw ? 1.5 : 1);
    let col = mul(MAG, G(c, 0.04));
    if (!isPanel(c)) return mul(col, 0.74);
    if (c.ev < cw) return mul(col, 1.3);
    const i = Math.floor(c.eu / cw), n = Math.round(c.fw / cw);
    if (i === n / 2 - 1 || i === n / 2) col = mul(col, 0.82);
    return col;
  };
  const lips = c => (c.face !== 'top' ? mul(DSTEEL, isPanel(c) ? 1.15 : 0.9) : c.eu > c.fw - cw ? COPPER : BRASS);
  // brown pull tab hanging from the phone mount's underside and draping over the middle of the mag's feed lips (the
  // brass shows on both sides of it): tilted 15 degrees about its lower front edge so its top is buried in the phone
  // (no cap, no post standing above the lips) and its face slants out from under the phone, past the lips, to just
  // proud of the mag front. A thin tan shock cord runs on straight down the mag front into the pouch mouth.
  const strap = col => c => mul(col, G(c, 0.05) * (c.face === 'bottom' ? 0.7 : isSide(c) ? 0.8 : 1));
  for (const cx of [-2.5, 0, 2.5]) {
    add([cx - 1.25, 7, -4.75], [cx + 1.25, 10.5, -3.25], pouch, 'mag pouch');
    // 3 px down in the pouch, 1.5 px + the feed lips stand out of it (about half the pouch height, like the render)
    add([cx - 1, 5.5, -4.25], [cx + 1, 10, -3.5], magPaint, 'PMAG');
    add([cx - 0.75, 5, -4.125], [cx + 0.75, 5.5, -3.625], lips, 'PMAG feed lips');
    add([cx - 0.25, 4, -4.375], [cx + 0.25, 5.75, -3.875], strap(TAB), 'pull tab', { rot: [-15, 0, 0], pivot: [cx, 5.75, -4.375] });
    // (narrower than the tab, so the two never share a side plane where they overlap at the bend)
    add([cx - 0.125, 5.75, -4.375], [cx + 0.125, 7.25, -3.875], strap(CORD), 'shock cord');
  }
  // decompression needle down the wearer's-right pouch: red cap, tan elastic loop, silver tube, khaki end
  add([-3.5, 5.75, -5], [-3, 9.5, -4.25], c => {
    const y = c.p[1];
    const col = y < 7.5 ? RED : y < 8 ? mul(POUCH, 1.1) : y < 9.25 ? TUBE : hex('#9a8a62');
    return mul(col, G(c, 0.05) * (c.face === 'top' ? 1.25 : isSide(c) ? 0.8 : 1));
  }, 'decompression needle');
  // CAT tourniquet lying across the middle and wearer's-left pouches, held by two tan shock cords
  add([-1.5, 8, -5.5], [4.25, 9.5, -4.75], c => {
    const x = c.p[0];
    const tied = (x >= 0.5 && x < 1) || (x >= 2 && x < 2.5);
    if (tied && c.face !== 'left' && c.face !== 'right') return mul(CORD, G(c, 0.05) * (c.face === 'top' ? 1.2 : 1));
    let col = mul(TQB, G(c, 0.05));
    if (c.face === 'top') return mul(col, 1.5);
    if (c.face === 'bottom') return mul(col, 0.8);
    if (!isPanel(c)) return mul(col, 1.1);
    if (c.ev < cw) return mul(col, 1.35);
    return col;
  }, 'tourniquet');
  add([2.75, 7.75, -5.75], [3.75, 9.75, -4.75], c => {
    const col = mul(TIME, G(c, 0.05));
    if (c.face === 'top') return mul(col, 1.15);
    if (c.face !== 'front') return mul(col, 0.8);
    const j = Math.floor(c.ev / cw);
    if ((j === 1 || j === 2) && Math.floor(c.eu / cw) === 1) return mul(col, 0.6); // the printed "TIME"
    return j === 3 ? mul(col, 0.88) : col;
  }, 'tourniquet TIME strap');

  // ================= Roll 1 trauma pouch under the placard: a fat round roll =================
  // Round section built from two crossed boxes (a tall one and a deep one, 1.5 x 1 and 1 x 1.5 px), so all four long
  // edges are chamfered and it reads as a fat sausage from every angle (two squares turned 45 degrees to each other
  // make an eight-pointed star with a sharp ridge down the front, not a roll). The deep box is 0.25 shorter at each end
  // so the end caps step in like a gathered roll and never share a plane. Nearly as wide as the placard, a touch toward
  // the wearer's right like the straight front view; its top tucks up against the pouch bottoms. A thin core pokes out
  // of both ends as the cinched drawstring ends, and a short tan strap tail dangles from each end.
  const rollBody = c => {
    const col = fab(c, ROLL, 0.06, 1.8, 0.5);
    if (isSide(c)) return mul(col, 0.84);                                       // gathered ends
    if (c.face === 'bottom') return mul(col, 0.8);
    const i = Math.floor(c.eu / cw), n = Math.round(c.fw / cw);
    let k = i === 0 || i === n - 1 ? 0.9 : 1;
    // the curve on the front: upper half catches the light, the lower half turns into shadow
    if (c.face === 'front') k *= c.p[1] < 11.1 ? 1.1 : c.p[1] < 11.4 ? 1 : 0.86;
    return mul(col, k);
  };
  add([-3.5, 10.5, -4.5], [3, 12, -3.5], rollBody, 'trauma roll (tall)');
  add([-3.25, 10.75, -4.75], [2.75, 11.75, -3.25], rollBody, 'trauma roll (deep)');
  add([-3.75, 10.875, -4.375], [3.25, 11.625, -3.625], c => {
    const col = mul(fab(c, ROLL, 0.06, 1.1), 0.72);
    return c.face === 'top' ? mul(col, 1.15) : col;
  }, 'trauma roll cinched ends');
  // strap tails hang from the roll's ends, swinging a little outward
  const strapTail = c => edge(c, mul(fab(c, mix(ROLL, CORD, 0.5), 0.05), c.face === 'bottom' ? 0.7 : 1), { stitch: false });
  add([-4, 11.25, -4.125], [-3.5, 12.5, -3.875], strapTail, 'roll strap tail (right)', { rot: [0, 0, 15], pivot: [-3.5, 11.25, -4] });
  add([3, 11.25, -4.125], [3.5, 12.5, -3.875], strapTail, 'roll strap tail (left)', { rot: [0, 0, -15], pivot: [3, 11.25, -4] });

  // ================= radios in the cummerbund wings =================
  // green AN/PRC-152 (wearer's right): dark display in the upper face, a tan retention cord across it; its lower end
  // shows under the wing
  add([-5.25, 4.25, -3.75], [-3.75, 10.5, -2.75], c => {
    let col = mul(GREEN, G(c, 0.05));
    if (c.face === 'top') return mul(col, 1.3);
    if (c.face === 'bottom') return mul(col, 0.6);
    if (!isPanel(c)) return mul(col, 0.82);
    const i = Math.floor(c.eu / cw), j = Math.floor(c.ev / cw);
    if (j === 0) return mul(col, 1.18);
    if (j === 3) return mul(CORD, G(c, 0.05));
    if (j === 1 && i >= 1) return mul(hex('#2c342a'), G(c, 0.05));
    return col;
  }, 'AN/PRC-152 radio');
  // antenna adapter (grey sleeve, black top) and a grey right-angle connector on the radio's top; the PTT's black
  // cable rises out of the adapter and turns over to the phone mount (the render's cable loop)
  const CABLE = hex('#1e1f1f');
  const cable = c => mul(CABLE, G(c, 0.05) * (c.face === 'top' ? 1.35 : isSide(c) ? 0.85 : 1));
  add([-5.25, 2, -3.5], [-4.75, 4.25, -3], c => (c.p[1] < 2.75 ? cable(c) : mul(c.p[1] < 3.25 ? BLACK : CONN, G(c, 0.05) * (c.face === 'top' ? 1.3 : isSide(c) ? 0.85 : 1))), 'radio antenna adapter + cable');
  add([-4.75, 2, -3.5], [-3.5, 2.5, -3], cable, 'PTT cable');
  add([-4.75, 3.25, -3.5], [-4.25, 4.25, -3], plastic(CONN), 'radio connector');
  // black AN/PRC-148 (wearer's left): grey keypad panel
  add([3.75, 4, -3.75], [5.25, 10.5, -2.75], c => {
    let col = mul(R148, G(c, 0.05));
    if (c.face === 'top') return mul(col, 1.3);
    if (c.face === 'bottom') return mul(col, 0.6);
    if (!isPanel(c)) return mul(col, 0.82);
    const j = Math.floor(c.ev / cw);
    if (j === 0) return mul(col, 1.2);
    if (j === 2 || j === 3) return mul(col, 1.28);
    return col;
  }, 'AN/PRC-148 radio');
  // antenna stub with the cable running from it over to the wearer's-left mount clamp
  add([4.25, 2.5, -3.5], [4.75, 4, -3], c => (c.p[1] < 3.5 ? cable(c) : plastic(BLACK)(c)), 'radio antenna stub + cable');
  add([3.75, 2.5, -3.5], [4.25, 3, -3], cable, 'radio cable');

  // ================= back =================
  // orange and black ASP restraints on the wearer's left (a tan shock cord across both)
  const asp = base => c => {
    const y = c.p[1];
    if (isPanel(c) && y >= 5.5 && y < 6) return mul(CORD, G(c, 0.05));
    return mul(base, G(c, 0.05) * (c.face === 'top' ? 1.25 : c.face === 'bottom' ? 0.7 : isSide(c) ? 0.82 : c.ev < cw ? 1.12 : 1));
  };
  add([2.25, 1.25, 3.25], [3.25, 10, 3.75], asp(ORANGE), 'ASP restraint (orange)');
  add([1, 4.5, 3.25], [2, 10.25, 3.75], c => (c.p[1] >= 9.75 ? mul(hex('#8b8d8e'), c.face === 'top' ? 1.2 : 1) : asp(BLACK)(c)), 'ASP restraint (black)');
  // antennas: black sticks with tan keeper bands. They stand 0.25 proud of the back risers (no shared planes) and lean
  // outward from their feet so their upper parts clear the head and hat and show above the shoulders.
  const stick = (base, bands) => c => {
    const y = c.p[1];
    if (bands.some(([a, b]) => y >= a && y < b)) return mul(CORD, G(c, 0.05) * (c.face === 'top' ? 1.3 : isSide(c) ? 0.85 : 1));
    return mul(base, G(c, 0.04) * (c.face === 'top' ? 1.4 : c.face === 'bottom' ? 0.7 : isSide(c) ? 0.85 : 1));
  };
  // long whip on the wearer's right: foot on the zipper at the bottom of the back panel's outer edge, rising past the
  // riser (keeper band there) to well above the shoulder pad, about as high as the pair on the other side
  add([-4, -3, 3.25], [-3.5, 10.75, 4], stick(hex('#2c2d2d'), [[0.25, 0.75], [9, 9.5]]), 'whip antenna (back)',
    { rot: [0, 0, -5.5], pivot: [-3.75, 10.75, 3.625] });
  // two antennas rising behind the wearer's left shoulder: the outer one taller and set a little further back, the two
  // interpenetrating (same pivot and lean, no faces in a common plane) so they read as two sticks tied together
  const pair = [[0.75, 1.25]];
  add([3.75, -3.5, 3.25], [4.25, 7.25, 4], stick(hex('#303131'), pair), 'antenna (short, inner)', { rot: [0, 0, 4.5], pivot: [4.25, 7.25, 3.75] });
  // (its foot ends 0.25 above the short one's, so their bottom faces never share a plane)
  add([4.125, -5, 3.875], [4.625, 7, 4.375], stick(hex('#262727'), pair), 'antenna (tall, outer)', { rot: [0, 0, 4.5], pivot: [4.25, 7.25, 3.75] });
  return { S: K.S, px: K.px, cell: K.cell, boxes: B };
};
