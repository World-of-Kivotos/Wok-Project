// ================= ANA Tactical M1 armored rig (OD Green / Olive) — EFT reference =================
// tarkov.dev render 5c0e722886f7740458316a57 (EFT: "ANA Tactical M1 plate carrier (OD Green)"). An armored rig: the
// pouches, PTT and cables are part of the item model. One fairly saturated olive-drab green with light worn highlights.
// Read off the render (x<0 = wearer's right = viewer's left):
//   wide padded shoulder straps with brown piping and a dark green lining; two thin grey cables along the top edge of the
//   front panel, a khaki webbing band under them, darker MOLLE rows below; four pistol-mag pouches in a row on the upper
//   chest (black mags standing up out of them, black snaps near the bottoms, blue-green bottom bands); a pale grey-green
//   small pouch on the wearer's right of them with a black square speaker-PTT hanging over the brown armhole mesh; on the
//   wearer's left a dark olive rod held by two elastic loops on a webbing strip;
//   across the belly four flapped rifle-mag pouches (a light tab at each flap's bottom, black mag spines showing in the
//   three gaps between the flaps); wearer's right: a radio pouch (PTT cable and a tan coiled cord run into its top, black clip on
//   its front) and a MOLLE-faced utility pouch further out; wearer's left: a tall zipped GP pouch (dark brown zipper
//   along its inner edge, MOLLE on its outer side) with a long drop pouch hanging below it, leaning outward.
ARMORS.ana_m1_olive = function (mode) {
  const K = kit('M');                                    // always the 2x style
  const { edge, G, isPanel, isSide, px } = K;
  const B = [];
  const add = (a, b, mat, tag, opt = {}) => B.push(box('body', a, b, mat, { tag, ...opt }));
  const md = (v, m) => ((v % m) + m) % m;

  // ---- palette (sampled off the render's lit faces; the render's darker lower pouches are lighting, not a second colour)
  // (a fresh, slightly yellow OD green - median hue of the render's fabric ~93 deg; the padded shoulder straps are the
  // yellowest parts)
  const OD = hex('#6d8a5a'), ODL = hex('#7e9c67'), PAD = hex('#869a68');
  const PIPE = hex('#5d4f40'), LINING = hex('#1f2a1f'), MESH = hex('#4b4338');
  const WEBK = hex('#a09e78'), TAB = hex('#929f6d'), HEM = hex('#3f5b45');
  const TEAL = hex('#7b937b'), ROD = hex('#5d5c47'), ELASTIC = hex('#8d9a68');
  const CABLE = hex('#3e4744'), CORD = hex('#8a7f66'), BLK = hex('#1f2123'), ZIP = hex('#56473a');
  const TOPCABLE = hex('#7c887c');                       // the two thin mid grey-green cables along the plate top
  const MAGB = hex('#222426'), MAGTOP = hex('#2c2f33'), LIPS = hex('#2e3134'), BRASS = hex('#caa24e');

  // Cordura: faint low-frequency mottling + per-cell grain
  const fab = (c, base, amt = 0.06) => {
    const q = snap(c.p, 0.5);
    const n = fbm(q[0] * 0.45 + 5, q[1] * 0.45 + 2, q[2] * 0.45 + 9);
    return mul(base, (0.96 + 0.08 * n) * G(c, amt));
  };
  const lin = (c, col = LINING) => mul(col, G(c, 0.1));
  const mesh = c => mul(MESH, (0.94 + 0.12 * fbm(c.p[0] * 1.1 + 3, c.p[1] * 1.1, c.p[2] * 1.1 + 5)) * G(c, 0.12));
  // MOLLE rows: on this rig the webbing is the DARKER band; one cell tall, rows every 1.5 px, a slit cell every 1.5 px
  // (h = horizontal coordinate along the face; slits symmetric about the centre line)
  const web = (c, col, y0, n, h) => {
    const dy = c.p[1] - y0;
    if (dy < 0) return null;
    const i = Math.floor(dy / 1.5);
    if (i >= n || dy - i * 1.5 >= 0.5) return null;
    return mul(col, md(Math.abs(h) + 0.75, 1.5) < 0.5 ? 0.56 : 0.74);
  };

  // ================= carrier =================
  // front plate bag: brown armhole mesh beside the panel on the wearer's right (behind the PTT, where the render shows
  // it; on the other side the render shows the panel edge); khaki webbing band under the cables; two darker MOLLE rows
  // (the lower one shows between the pistol pouches and the rifle-pouch flaps)
  const frontBag = c => {
    const x = c.p[0], y = c.p[1], ax = Math.abs(x);
    if (c.face === 'back') return mesh(c);
    if (c.face === 'top') return mul(fab(c, OD), 0.72);
    if (c.face === 'bottom') return mul(fab(c, OD), 0.6);
    if (isSide(c)) return y < 6.5 ? mesh(c) : mul(fab(c, OD), 0.8);
    if (x < -3.5 && y < 6.5) return mesh(c);
    if (y >= 1.75 && y < 2.75) return mul(fab(c, WEBK), md(ax + 0.75, 1.5) < 0.5 ? 0.62 : y < 2.25 ? 1.06 : 0.92);
    const col = fab(c, OD);
    return web(c, col, 4.5, 2, x) || (y < 1.75 ? mul(col, 1.06) : col);
  };
  add([-4.25, 1.25, -3.25], [4.25, 10.75, -2.5], frontBag, 'front plate bag');
  const backBag = c => {
    if (c.face === 'front') return mesh(c);
    if (c.face === 'top') return lin(c);
    const col = fab(c, OD);
    if (c.face === 'back') return edge(c, web(c, col, 2.5, 6, c.p[0]) || col, { stitch: false });
    return edge(c, mul(col, 0.9), { stitch: false });
  };
  add([-4.25, 0.75, 2.5], [4.25, 10.75, 3.25], backBag, 'back plate bag');
  // cummerbund (ends 0.25 above the bags' bottoms so no bottom faces share a plane), MOLLE on the sides
  const cumm = c => {
    const col = mul(fab(c, OD), 0.94);
    if (isSide(c) && Math.abs(c.p[2]) < 2.25) return edge(c, web(c, col, 6.5, 3, c.p[2]) || col, { stitch: false });
    return edge(c, col, { stitch: false });
  };
  add([-4.5, 5.75, -2.75], [4.5, 10.5, 2.75], cumm, 'cummerbund');
  add([-1.25, 0.5, 3.25], [1.25, 1.5, 3.75], c => edge(c, mul(fab(c, OD), 0.8), { stitch: false }), 'drag handle');

  // ================= padded shoulder straps =================
  // (the head hides the straps over the trapezius: a rounded pad on each shoulder outside the hat layer, plus the
  // front / back risers; brown piping along both edges of the front risers, dark green lining toward the neck)
  const hump = c => {
    if (c.face === 'bottom') return lin(c);
    const col = fab(c, PAD, 0.07);
    if (isPanel(c)) return mul(col, 0.9);
    if (c.face === 'top') return mul(col, 1.06);
    return c.ev > c.fh - px ? mix(col, PIPE, 0.6) : col;                  // piping along the pad's lower edge
  };
  const riser = inner => c => {
    if (c.face === 'bottom' || c.face === inner) return lin(c);
    let col = fab(c, PAD, 0.07);
    if (isPanel(c)) {
      const ax = Math.abs(c.p[0]);
      if (ax < 2.25 || ax > 3.75) col = mix(col, PIPE, 0.55);
      else if (c.p[1] > c.box.y + c.box.h - px) col = mul(col, 0.9);     // strap end
    }
    return col;
  };
  for (const s of [-1, 1]) {
    const X = (a, b) => (s < 0 ? [a, b] : [-b, -a]);
    const inner = s < 0 ? 'left' : 'right';
    const [b0, b1] = X(-5.75, -4.25), [h0, h1] = X(-5.5, -4.5), [r0, r1] = X(-4.25, -1.75), [k0, k1] = X(-4.5, -1.75);
    add([b0, -1.25, -3.5], [b1, -0.25, 3.5], hump, 'shoulder pad hump');
    add([h0, -1.75, -3.25], [h1, -0.75, 3.25], hump, 'shoulder pad crown');
    add([r0, -0.25, -3.75], [r1, 1.25, -2.5], riser(inner), 'strap front');
    add([k0, -0.25, 2.75], [k1, 1.5, 3.5], riser(inner), 'strap back');
  }

  // ================= upper chest =================
  // two thin mid grey-green cables along the top edge of the front panel (one line at this size; they barely stand out
  // from the green in the render), held by a small clip a little to the wearer's right of the centre
  add([-3.5, 1.25, -3.5], [3.25, 1.75, -3.25], c => mul(TOPCABLE, c.face === 'top' ? 1.25 : isPanel(c) ? G(c, 0.06) : 0.75), 'cables (top edge)');
  const CLIP = mix(TOPCABLE, BLK, 0.4);
  add([-1.5, 1, -3.75], [-1, 2, -3.25], c => mul(CLIP, c.face === 'top' ? 1.3 : c.face === 'back' ? 0.7 : isPanel(c) ? (c.ev < px ? 1.12 : 1) : 0.8), 'cable clip');
  // four pistol-mag pouches: open mouth (light front rim, dark inside), elastic top binding, a blue-green bottom band
  // about a sixth of the pouch tall with the snap in its middle
  const pPouch = c => {
    if (c.face === 'top') return c.ev > c.fh - px ? mul(fab(c, ODL), 1.1) : lin(c);
    if (c.face === 'back') return lin(c);
    const col = fab(c, ODL);
    if (c.face === 'bottom') return mul(col, 0.6);
    if (!isPanel(c)) return mul(col, 0.82);
    if (c.ev > c.fh - 2 * px) return fab(c, HEM);
    return c.ev < px ? mul(col, 1.1) : col;
  };
  // black pistol mags seated 2 px deep: body (lit feed-lip edge, darker spines) + narrower feed-lip block with the top
  // round showing brass
  const pmag = c => {
    if (c.face === 'bottom') return mul(MAGB, 0.6);
    if (c.face === 'top') return mul(MAGTOP, c.ex < px ? 1.3 : 1);
    const col = mul(MAGB, G(c, 0.04));
    if (!isPanel(c)) return mul(col, 0.8);
    return c.ev < px ? mul(col, 1.35) : col;
  };
  const lips = c => (c.face === 'top' ? BRASS : c.face === 'bottom' ? LIPS : mul(LIPS, isPanel(c) ? 1.2 : 0.9));
  // (black snap with a hint of the band colour, so it reads as a snap on the band, not a hole)
  const SNAP = mix(BLK, HEM, 0.22);
  const snapBtn = c => (c.face === 'back' ? null : mul(SNAP, c.face === 'top' ? 1.3 : isPanel(c) ? 1 : 0.85));
  for (let i = 0; i < 4; i++) {
    const x0 = -2.375 + i * 1.25, cx = x0 + 0.5;
    add([x0, 2.75, -4], [x0 + 1, 5.75, -3.25], pPouch, 'pistol mag pouch');
    add([cx - 0.375, 2.25, -3.875], [cx + 0.375, 4.75, -3.375], pmag, 'pistol mag');
    add([cx - 0.25, 1.75, -3.75], [cx + 0.25, 2.25, -3.5], lips, 'pistol mag feed lips');
    add([cx - 0.25, 5, -4.25], [cx + 0.25, 5.5, -4], snapBtn, 'pouch snap');
  }
  // pale grey-green small pouch on the wearer's right of the pistol pouches: flap, its shadowed edge, body
  const teal = c => {
    const col = fab(c, TEAL, 0.05);
    if (c.face === 'top') return mul(col, 1.08);
    if (c.face === 'back') return lin(c);
    if (c.face === 'bottom') return mul(col, 0.62);
    if (!isPanel(c)) return mul(col, 0.8);
    const j = Math.floor(c.ev / px);
    return mul(col, j === 0 ? 1.08 : j === 2 ? 0.82 : 1);
  };
  add([-3.5, 2.5, -3.75], [-2.5, 4.5, -3.25], teal, 'small pouch');
  // black square speaker-PTT hanging from the strap end over the armhole mesh, tilted like the render; it overlaps the
  // small pouch's outer edge (in front of it) and rests on the panel
  const ptt = c => {
    if (c.face === 'back') return null;
    const F = hex('#2c2f34');
    if (c.face === 'front') return c.ex >= px ? hex('#141518') : mul(F, c.ev < px ? 1.25 : 1);   // grille in a frame
    return mul(F, c.face === 'top' ? 1.3 : 0.9);
  };
  add([-4.5, 1.75, -4], [-3, 3.25, -3.25], ptt, 'PTT', { rot: [0, 0, 20], pivot: [-3.75, 2.5, -3.625] });
  // PTT cable straight down the panel into the radio pouch's mouth
  add([-4.375, 3, -3.5], [-4.125, 7.25, -3.25], c => mul(CABLE, c.face === 'top' ? 1.3 : G(c, 0.05)), 'PTT cable');
  // tan coiled cord: comes down from under the PTT beside the straight PTT cable (its top tucked behind the PTT), loops
  // over in front of the PTT cable just under the small pouch's outer corner (rising a little toward the bend, lying flat
  // on the panel, nothing past the plate edge), then drops into the radio pouch mouth. The arc stands one step proud of
  // the two runs so the loop passes in front of their ends (no shared face). Coils = 1 px period (light cell / shaded
  // cell) along the cord's length (x on the arc piece, y on the runs).
  const cord = along => c => {
    const col = fab(c, CORD, 0.05);
    const t = along === 'x' ? c.eu : c.ev;
    const k = md(t, 1) >= 0.5 ? 0.82 : 1;
    if (c.face === 'top') return mul(col, 1.15 * k);
    if (c.face === 'back') return mul(col, 0.7);
    return mul(col, (isPanel(c) ? 1 : 0.82) * k);
  };
  add([-4.125, 3, -3.75], [-3.875, 4.75, -3.25], cord('y'), 'coiled cord (from the PTT)');
  add([-4.35, 4.5, -4], [-3.35, 5, -3.5], cord('x'), 'coiled cord (arc)', { rot: [0, 0, -12], pivot: [-3.35, 4.75, -3.75] });
  add([-4, 5, -3.75], [-3.5, 7.5, -3.25], cord('y'), 'coiled cord (drop)');
  // wearer's left of the pistol pouches: dark olive rod held by two elastic loops on a webbing strip
  add([2.625, 1.75, -3.5], [3.375, 5.5, -3.25], c => edge(c, mul(fab(c, ODL), 0.88), { stitch: false }), 'webbing strip');
  const rod = c => {
    if (c.face === 'top') return mul(ROD, 1.35);
    const col = mul(ROD, G(c, 0.05));
    if (!isPanel(c)) return mul(col, 0.8);
    return c.ev < px ? mul(col, 1.2) : col;
  };
  add([2.75, 1.75, -4], [3.25, 5.25, -3.5], rod, 'rod');
  const band = c => {
    const col = fab(c, ELASTIC, 0.05);
    if (c.face === 'top') return mul(col, 1.1);
    return isPanel(c) ? col : mul(col, 0.82);
  };
  for (const y of [3, 4.5]) add([2.625, y, -4.25], [3.375, y + 0.5, -3.5], band, 'elastic loop');

  // ================= belly: four flapped rifle-mag pouches =================
  // Each flap leaves a gap beside it where the black spine of the magazine shows. As in the render the spines only show
  // in the three gaps between flaps: pouch 1 on its wearer's-left side, pouches 3 and 4 on their wearer's-right side,
  // pouch 2 none (its narrower flap leaves a strip of pouch fabric each side); flap 4 runs straight into the GP zipper.
  // The lid over the pouch mouth is the flap fabric, the gap shows the mag's dark top.
  const rifleBody = side => c => {
    const slot = side === 'hi' ? c.p[0] >= c.box.x + 1.25 : side === 'lo' ? c.p[0] < c.box.x + 0.5 : false;
    if (c.face === 'top') return slot ? mul(MAGTOP, 1.15) : mul(fab(c, ODL), 1.08);
    if (c.face === 'back') return lin(c);
    const col = fab(c, ODL);
    if (c.face === 'bottom') return mul(col, 0.6);
    if (c.face === 'front') {
      if (slot && c.p[1] < 8) return mul(MAGB, c.ev < px ? 1.4 : G(c, 0.04));
      if (c.ev > c.fh - px) return fab(c, HEM);
      return col;
    }
    return mul(col, 0.84);
  };
  const flap = c => {
    const col = fab(c, ODL);
    if (c.face === 'back') return lin(c);
    if (c.face === 'top') return mul(col, 1.1);
    if (c.face === 'bottom') return mul(col, 0.66);
    if (!isPanel(c)) return mul(col, 0.8);
    if (c.ev < px) return mul(col, 1.08);                                   // folded top edge
    return c.ev > c.fh - px ? mul(col, 0.86) : col;                         // shadowed lower edge
  };
  const tab = c => {
    const col = fab(c, TAB, 0.05);
    if (c.face === 'back') return null;
    if (c.face === 'top') return mul(col, 1.12);
    if (!isPanel(c)) return mul(col, 0.8);
    return c.ev < px ? mul(col, 1.06) : mul(col, 0.9);
  };
  const SLOT = ['hi', null, 'lo', 'lo'], FLAP = [[0, 1.25], [0.25, 1.5], [0.5, 1.75], [0.5, 1.75]];
  for (let i = 0; i < 4; i++) {
    const x0 = -3.5 + i * 1.75, f0 = x0 + FLAP[i][0], f1 = x0 + FLAP[i][1], fc = (f0 + f1) / 2;
    add([x0, 6.75, -4.25], [x0 + 1.75, 11.25, -3.25], rifleBody(SLOT[i]), 'rifle mag pouch');
    add([f0, 6.75, -4.5], [f1, 9.25, -4.25], flap, 'pouch flap');
    add([fc - 0.25, 8.75, -4.75], [fc + 0.25, 9.75, -4.25], tab, 'flap tab');
  }

  // ================= side pouches (front corners, pressed against the carrier) =================
  // wearer's right: radio pouch (open mouth taking the PTT cable and the coiled cord, black clip on its front) ...
  const sidePouch = (molleFront, molleSide) => c => {
    if (c.face === 'back') return lin(c);
    const col = fab(c, ODL, 0.06);
    if (c.face === 'bottom') return mul(col, 0.6);
    if (c.face === 'top') return molleFront ? mul(col, 1.08) : c.ev > c.fh - px ? mul(col, 1.1) : lin(c);
    if (c.face === 'front') {
      if (c.ev > c.fh - px) return fab(c, HEM);
      if (molleFront) { const w = web(c, col, 7.5, 3, c.p[0]); if (w) return w; }
      return c.ev < px ? mul(col, 1.08) : col;
    }
    if (c.face === molleSide) return mul(web(c, col, 7.5, 3, c.p[2]) || col, 0.86);
    return mul(col, 0.84);
  };
  add([-4.5, 7, -4.5], [-3.5, 11.5, -2.75], sidePouch(false, null), 'radio pouch');
  add([-4.25, 9, -4.75], [-3.75, 10.5, -4.5], c => (c.face === 'back' ? null : c.face === 'front' && c.ev < px ? hex('#474c46') : mul(BLK, isPanel(c) ? 1 : c.face === 'top' ? 1.4 : 0.8)), 'black clip');
  // ... and a MOLLE-faced utility pouch further out (MOLLE on its front and outer side)
  add([-5.5, 7, -4], [-4.5, 11.5, -2.75], sidePouch(true, 'right'), 'MOLLE utility pouch');
  // wearer's left: tall GP pouch, dark brown zipper along its inner edge and over the top, MOLLE on the outer side
  const gp = c => {
    const zip = c.p[0] < 4;
    if (c.face === 'top') return zip ? mul(ZIP, G(c, 0.05)) : mul(fab(c, ODL), 1.1);
    if (c.face === 'back') return lin(c);
    const col = fab(c, ODL, 0.06);
    if (c.face === 'bottom') return mul(col, 0.6);
    if (c.face === 'front') {
      if (zip) return mul(ZIP, G(c, 0.05));
      if (c.ev > c.fh - px) return fab(c, HEM);
      return c.ev < px ? mul(col, 1.1) : col;
    }
    if (c.face === 'left') return mul(web(c, col, 7.5, 3, c.p[2]) || col, 0.86);
    return mul(col, 0.84);
  };
  add([3.5, 6.25, -4.5], [5, 11.25, -2.75], gp, 'GP pouch');
  add([3.5, 6.5, -4.75], [4, 7.5, -4.5], c => (c.face === 'back' ? null : mul(hex('#3a3d3c'), c.face === 'top' ? 1.4 : isPanel(c) ? 1 : 0.85)), 'zip pull');
  // long drop pouch hanging under the GP pouch and the last rifle pouch: rolled cuff, then a bag that narrows on its
  // inner side and leans outward (shortened to end at mid-thigh)
  const cuff = c => {
    const col = fab(c, ODL, 0.06);
    if (c.face === 'back') return lin(c);
    if (c.face === 'top') return mul(col, 1.1);
    if (c.face === 'bottom') return mul(col, 0.62);
    if (!isPanel(c)) return mul(col, 0.8);
    return c.ev < px ? mul(col, 1.08) : mul(col, 0.84);
  };
  add([2.25, 11.25, -4.5], [5, 12.25, -2.75], cuff, 'drop pouch cuff');
  const DUMP = mul(OD, 0.92);
  const dump = c => {
    const col = fab(c, DUMP, 0.07);
    if (c.face === 'top') return mul(col, 1.05);
    if (c.face === 'bottom') return fab(c, HEM);
    if (!isPanel(c)) return mul(col, 0.82);
    if (c.face === 'back') return mul(col, 0.9);
    if (c.box.y > 14 && c.ev > c.fh - px) return fab(c, HEM);              // bound bottom
    return Math.abs(c.p[0] - 4) < 0.25 ? mul(col, 0.86) : col;             // one soft fold down the bag
  };
  // (the bag hangs against the thigh: its back sits just outside the leg's outer layer, no daylight behind it)
  const lean = { rot: [0, 0, -8], pivot: [3.625, 12.25, -3.125] };
  add([2.25, 12, -3.75], [4.75, 14.5, -2.5], dump, 'drop pouch', lean);
  add([2.75, 14.5, -3.75], [4.75, 16.5, -2.5], dump, 'drop pouch (lower)', lean);
  return { S: K.S, px: K.px, cell: K.cell, boxes: B };
};
