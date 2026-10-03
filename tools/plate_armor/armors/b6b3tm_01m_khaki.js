// ================= 6B3TM-01M armored rig (Khaki) — EFT reference (tarkov.dev 5d5d646386f7742797261fd9) =================
// (EFT now lists it as "6B3TM-01 armored rig (Khaki)"; same item.)
// Soviet titanium-plate vest in a weathered, yellow-leaning olive khaki (Russian "khaki"), shiny with darker green grime.
// Reference render (seen from the wearer's right front): broad darker brown-olive shoulder yokes wrapping over the
// shoulders and down the outer upper chest (a lighter top flap, slanted inner edge, curved lower edge), each carrying a
// narrow light webbing strap with silver grommets that runs down to a small steel roller buckle; a big chest pocket
// right under the neckline with a lighter flap; very large open armholes with a dark lining, the open sides crossed by
// two grommet straps with steel frame buckles; a dark greyish-brown leather waist belt with a steel frame buckle (prong)
// in the middle and a grommet hole beside it; mag pouches either side of the buckle (tall, flaps over the upper part,
// the belt runs behind them): on the wearer's right two separate flapped pouches with a dark seam between them, the
// inner one with a black mag top peeking out at its outer corner; on the wearer's left an open pocket with a black AK
// mag standing in it, then a flapped pouch at the vest's corner; a grenade pouch hanging from the belt at the back of
// the side (fuse showing; only the wearer's right one is in view, the left one mirrors it; moved onto the back panel's
// lower outer corner here, out of the Minecraft arm's way); long front hem, a convex curve lowest in the middle, with a
// dark green binding. The render shows no back: the back panel is kept plain apart from the yokes, belt and grenade pouches.
ARMORS.b6b3tm_01m_khaki = function (mode) {
  const K = kit('M');                                     // always the 2x style
  const { G, isPanel, isSide, px } = K;
  const B = [];
  // colours sampled off the render (lit faces), keeping its yellow-olive saturation
  const KH = hex('#7c7a4a'), KHL = hex('#9a9462'), GRIME = hex('#56593a'), POUCH = hex('#787749'), PFL = hex('#88864f'),
    SH = hex('#5e5538'), SHL = hex('#696043'), WEB = hex('#9d976f'), METAL = hex('#b4b4ac'), STEEL = hex('#8e8b80'),
    BELT = hex('#40352d'), LIN = hex('#3b3729'), NLIN = hex('#27302b'), BIND = hex('#454e33'),
    MAG = hex('#232527'), MAGL = hex('#3a3d40'), BRASS = hex('#caa24e'), COPPER = hex('#b5703f'), FUSE = hex('#8d8f86');
  // khaki cloth: faint slow tone drift + soft darker-green grime blotches (1-3 px, on the art grid) + per-cell grain
  const fab = (c, base = KH, amt = 0.06, gr = 0.42) => {
    const q = snap(c.p, 0.5);
    const tone = 0.96 + 0.08 * fbm(q[0] * 0.3 + 7, q[1] * 0.3 + 3, q[2] * 0.3 + 1);
    const g = sm(clamp01((fbm(q[0] * 0.5 + 11, q[1] * 0.45 + 5, q[2] * 0.5 + 17) - 0.56) * 3));
    return mul(mix(base, GRIME, g * gr), tone * G(c, amt));
  };
  const lin = (c, col = LIN) => mul(col, G(c, 0.08));
  const fall = y => 1.03 - 0.012 * y;                     // lit from above: the belly is a little darker than the chest
  // steel frame buckle: a one-cell frame round a see-through opening (the belt shows through it); prong = the upper
  // opening cell is the buckle's tongue
  const frame = (col, prong = false) => c => {
    if (c.face === 'back') return null;
    if (c.face !== 'front') return mul(col, c.face === 'top' ? 1.2 : 0.75);
    const nU = Math.round(c.fw / px), nV = Math.round(c.fh / px);
    const i = Math.min(nU - 1, Math.floor(c.eu / px)), j = Math.min(nV - 1, Math.floor(c.ev / px));
    if (i > 0 && i < nU - 1 && j > 0 && j < nV - 1) return prong && j === 1 ? mul(col, 0.76) : null;
    return mul(col, j === 0 ? 1.12 : j === nV - 1 ? 0.85 : 1);
  };

  // ---------------- vest body: front + back panels, open sides under the arms ----------------
  const HEM = 11.5;
  const core = c => {
    const x = c.p[0], y = c.p[1], z = c.p[2], ax = Math.abs(x);
    if (c.face === 'top') return lin(c, NLIN);                                    // (under the head)
    if (c.face === 'bottom') return lin(c, BIND);
    if (isSide(c)) {
      // big open armhole / side: dark lining between the rolled front and back panel edges, crossed by two
      // grommet straps (only seen when the arm swings): grommets on the back part, a steel frame buckle in the
      // middle, a tail with one grommet running on toward the front panel
      if (z < -2.5 || z > 2.5 || y < 0.5 || y > 11) return mul(fab(c), 0.9);
      for (const s0 of [5.5, 7.5]) if (y >= s0 && y < s0 + 1) {
        const up = y < s0 + 0.5;
        if (z >= -0.5 && z < 0.5) return mul(up ? METAL : STEEL, (up ? 1.05 : 0.95) * G(c, 0.04));   // buckle
        if (up && ((z >= 1 && z < 1.5) || (z >= 2 && z < 2.5) || (z >= -2 && z < -1.5))) return mul(METAL, 0.82 * G(c, 0.04));
        return mul(WEB, G(c, 0.05) * (up ? 1 : 0.86));
      }
      return lin(c);
    }
    // (the rolled neckline sits under the head; only the plain panel shows between the chin and the pocket flap)
    const col = mul(fab(c), fall(y));
    return ax > 4 ? mul(col, 0.84) : col;                                          // rolled armhole edges
  };
  B.push(box('body', [-4.5, 0, -3], [4.5, HEM, 3], core, { tag: 'vest body' }));
  // long hem below the open sides (front and back), bottom corners rounded, dark green binding along the edge; the
  // front hem is a convex curve: a centre tongue hangs lowest, the corners are cut up
  const apron = (back, tongue = 2.75) => c => {
    const ax = Math.abs(c.p[0]), y = c.p[1];
    if (c.face === 'top') return null;                                             // against the body's underside
    if (c.face === 'bottom') return ax > 3.75 || ax < tongue ? null : mul(BIND, G(c, 0.06));
    if (isSide(c)) return y > 12 ? null : mul(fab(c), 0.8);
    if (ax > 3.75 && y > 12) return null;                                          // rounded corner
    if (c.face === (back ? 'front' : 'back')) return lin(c);                       // inner face
    const col = mul(fab(c), fall(y));
    return ((y > 12 && ax >= tongue) || ax > 3.75) ? mix(col, BIND, 0.55) : col;
  };
  B.push(box('body', [-4.25, HEM, -3], [4.25, 12.5, -2.5], apron(false), { tag: 'front hem' }));
  B.push(box('body', [-4.25, HEM, 2.5], [4.25, 12.5, 3], apron(true, 0), { tag: 'back hem' }));
  const hemTongue = c => {
    if (c.face === 'top') return null;                                             // against the hem's underside
    if (c.face === 'back') return lin(c);                                          // inner face
    if (c.face === 'bottom') return mul(BIND, G(c, 0.06));
    const col = mix(mul(fab(c), fall(c.p[1])), BIND, 0.55);                        // the binding runs along the edge
    return isSide(c) ? mul(col, 0.82) : col;
  };
  B.push(box('body', [-2.75, 12.5, -3], [2.75, 13, -2.5], hemTongue, { tag: 'front hem (centre, lowest)' }));

  // ---------------- chest pocket with a lighter flap, right under the neckline ----------------
  const pocket = c => {
    if (c.face === 'back') return null;                                           // sewn on the vest
    const y = c.p[1];
    const col = fab(c, mix(KH, KHL, 0.25), 0.05, 0.25);                            // a shade lighter than the vest
    if (c.face === 'top') return mul(col, 1.05);
    if (c.face === 'bottom') return mul(col, 0.7);
    if (isSide(c)) return mul(col, 0.8);
    if (y < 3) return mul(col, 0.76);                                              // shadow under the flap
    if (c.ev > c.fh - px) return mul(col, 0.8);                                    // bottom seam
    return c.eu < px || c.eu > c.fw - px ? mul(col, 0.86) : col;                   // soft side seams
  };
  B.push(box('body', [-2, 1, -3.5], [2, 6, -3], pocket, { tag: 'chest pocket' }));
  const flap = c => {
    if (c.face === 'back') return null;
    const col = fab(c, KHL, 0.05, 0.2);
    if (c.face === 'top') return mul(col, 1.05);
    if (c.face === 'bottom') return mul(col, 0.7);
    if (isSide(c)) return mul(col, 0.82);
    return c.ev > c.fh - px ? mul(col, 0.88) : col;                                // rolled lower edge
  };
  B.push(box('body', [-2, 1, -3.75], [2, 2.5, -3.5], flap, { tag: 'chest pocket flap' }));

  // ---------------- brown-olive shoulder yokes: outer upper chest (curved lower edge), over the shoulder, upper back ----------------
  // front lower edge: the inner edge slants away from the chest pocket (a khaki gap below the top flap), lowest over
  // the outer chest, rising a little again at the armhole
  const shBot = (ax, back) => (back ? (ax < 2.5 ? 2.5 : ax < 3 ? 3 : ax < 4 ? 3.5 : 3)
    : (ax < 2.5 ? 1.5 : ax < 3 ? 3 : ax < 3.5 ? 3.5 : ax < 4 ? 4 : 3.5));
  const yoke = back => c => {
    if (c.face === (back ? 'front' : 'back')) return null;                        // pressed on the vest
    const ax = Math.abs(c.p[0]), y = c.p[1], yb = shBot(ax, back);
    if (y > yb + 1e-6) return null;                                                // curved cut-out lower edge
    let col = fab(c, y < 1.5 ? SHL : SH, 0.06, 0.2);
    if (c.face === 'top') return mul(col, 1.08);
    if (c.face === 'bottom') return mul(col, 0.7);
    if (isSide(c)) return mul(col, 0.82);
    if (y >= 1.5 && y < 2) col = mul(col, 0.82);                                   // edge of the lighter top flap
    else if (yb > 2 && y > yb - px) col = mul(col, 0.84);                          // rolled lower edge
    return ax > 4 ? mul(col, 0.9) : col;                                           // turning round the shoulder
  };
  // padded roll over the shoulder, resting on the arm top: a stepped dome (wide base + narrower, shorter crown) in the
  // yoke's brown olive
  const hump = c => {
    if (c.face === 'bottom') return lin(c);
    const col = fab(c, SH, 0.06, 0.2);
    if (c.face === 'top') return mul(col, 1.04);
    if (isPanel(c)) return mul(col, 0.92);
    return col;
  };
  // narrow light webbing strap on the brown yoke: a silver grommet just under the chin, then the roller buckle, a
  // short tail below it
  const strap = c => {
    if (c.face === 'back') return null;
    const y = c.p[1];
    if (c.face === 'front' && y >= 0.5 && y < 1) return mul(METAL, 0.92 * G(c, 0.04));
    const col = mul(WEB, G(c, 0.05));
    if (!isPanel(c)) return mul(col, c.face === 'top' ? 1.05 : 0.8);
    return y >= 2 ? mul(col, 0.88) : col;                                          // tail below the buckle
  };
  // small steel roller buckle across the strap (a bright top edge, darker lower edge)
  const rollerBuckle = c => {
    if (c.face === 'back') return null;
    if (c.face === 'top') return mul(METAL, 1.05);
    if (c.face === 'bottom') return mul(STEEL, 0.7);
    if (isSide(c)) return mul(STEEL, 0.78);
    return mul(STEEL, 1.12 * G(c, 0.04));
  };
  for (const s of [-1, 1]) {
    const X = (a, b) => (s < 0 ? [-b, -a] : [a, b]);
    const [p0, p1] = X(2.5, 4.5), [q0, q1] = X(2, 2.5), [r0, r1] = X(2, 4.5);
    B.push(box('body', [p0, -0.5, -3.5], [p1, 4, -3], yoke(false), { tag: 'shoulder yoke (front)' }));
    B.push(box('body', [q0, -0.5, -3.5], [q1, 1.5, -3], yoke(false), { tag: 'shoulder yoke (front, inner top)' }));
    B.push(box('body', [r0, -0.5, 3], [r1, 3.5, 3.5], yoke(true), { tag: 'shoulder yoke (back)' }));
    // the base reaches down to the lowest arm top of any skin (slim arms hang half a pixel lower: their top is at y 0.5,
    // the wide arm's at y 0), so it rests on the arm with or without the jacket on wide and slim skins alike, no daylight
    // under it; over a wide arm its lower part hides inside the arm and only drapes a little over the arm's front and
    // back. It starts at the vest's shoulder line (x 4) so it joins the yokes and the vest over their whole depth.
    const [h0, h1] = X(4, 5.75), [k0, k1] = X(4.5, 5.5);
    B.push(box('body', [h0, -0.75, -3], [h1, 0.5, 3], hump, { tag: 'shoulder yoke (over the shoulder)' }));
    B.push(box('body', [k0, -1.25, -2.5], [k1, -0.75, 2.5], hump, { tag: 'shoulder yoke (crown)' }));
    const [s0, s1] = X(2.75, 3.25);
    B.push(box('body', [s0, -0.5, -3.75], [s1, 2.5, -3.5], strap, { tag: 'shoulder strap' }));
    const [b0, b1] = X(2.5, 3.5);
    B.push(box('body', [b0, 1.5, -4], [b1, 2, -3.75], rollerBuckle, { tag: 'shoulder strap buckle' }));
  }

  // ---------------- dark greyish-brown leather waist belt (runs behind the mag pouches), steel frame buckle ----------------
  const belt = c => {
    const y = c.p[1];
    let col = mul(BELT, G(c, 0.06));
    if (c.face === 'top') return mul(col, 1.2);
    if (c.face === 'bottom') return mul(col, 0.7);
    const j = Math.floor((y - 8.5) / px);
    if (j === 0) col = mul(col, 1.14); else if (j === 2) col = mul(col, 0.84);
    if (c.face === 'front' && j === 1 && Math.abs(c.p[0] - 1.5) < 0.25) return mul(METAL, 0.85 * G(c, 0.04));   // grommet hole
    return col;
  };
  B.push(box('body', [-4.75, 8.5, -3.25], [4.75, 10, 3.25], belt, { tag: 'waist belt' }));
  B.push(box('body', [-0.75, 8.25, -3.5], [0.75, 10.25, -3.25], frame(STEEL, true), { tag: 'belt buckle' }));

  // ---------------- AK mag pouches either side of the buckle ----------------
  // flaps over the upper part (lighter panel, darker rolled lower edge), a shadow row under them; the outer pockets
  // stand a little prouder than the inner ones
  const flapped = c => {
    if (c.face === 'back') return null;
    const y = c.p[1];
    const col = fab(c, y < 10 ? PFL : POUCH, 0.05, 0.25);
    if (c.face === 'top') return mul(col, 1.06);                                   // flap folded over the top
    if (c.face === 'bottom') return mul(col, 0.7);
    if (isSide(c)) return mul(col, 0.8);
    if (y >= 9.5 && y < 10) return mul(col, 0.8);                                  // flap's lower edge
    if (y >= 10 && y < 10.5) return mul(col, 0.9);                                 // soft shadow under the flap
    return c.ev > c.fh - px ? mul(col, 0.84) : col;                                // bottom seam
  };
  // the wearer's left inner pocket: flap open, a black AK mag standing in it (open mouth: light front rim, dark inside)
  const openPocket = c => {
    if (c.face === 'back') return null;
    const col = fab(c, POUCH, 0.05, 0.25);
    if (c.face === 'top') return c.ev > c.fh - px ? mul(col, 1.1) : lin(c, NLIN);
    if (c.face === 'bottom') return mul(col, 0.7);
    if (isSide(c)) return mul(col, 0.8);
    if (c.ev < px) return mul(col, 1.1);                                           // top binding
    return c.ev > c.fh - px ? mul(col, 0.84) : col;
  };
  // wearer's right: two separate pouches with a dark seam between them, the inner one reaching close to the buckle;
  // wearer's left: the open pocket beside the belt's grommet, then a flapped pouch at the vest's corner
  // (the open pocket's front wall ends lower, where the open flap was sewn on, so its mag tops out level with the flaps)
  for (const [x0, x1, y0, z0, mat, tag] of [[-4.25, -2.75, 7, -4, flapped, 'mag pouch (outer)'], [-2.5, -1, 7, -3.75, flapped, 'mag pouch (inner)'],
    [1.75, 3.25, 8, -3.75, openPocket, 'mag pouch (inner, open)'], [3.25, 4.5, 7, -4, flapped, 'mag pouch (outer)']])
    B.push(box('body', [x0, y0, z0], [x1, 12, -3], mat, { tag }));
  B.push(box('body', [-2.75, 7, -3.5], [-2.5, 12, -3], c => (c.face === 'back' ? null : mul(fab(c, KH, 0.05, 0.2), 0.5)), { tag: 'seam between the mag pouches' }));
  // black AK mag standing in the open pocket (back against the vest): its top pixel and the feed lips (brass round on
  // top) rise above the mouth, level with the neighbouring flaps and clear of the chest pocket above
  const mag = c => {
    if (c.face === 'bottom') return mul(MAG, 0.6);
    if (c.face === 'top') return mul(MAGL, 1.2);
    const col = mul(MAG, G(c, 0.04));
    if (!isPanel(c)) return mul(col, 0.8);                                         // spine
    return c.ev < px ? mul(MAGL, 1.1) : col;                                       // feed-lip edge
  };
  B.push(box('body', [2, 7, -3.5], [3, 11, -3], mag, { tag: 'AK mag' }));
  const lips = c => (c.face === 'top' ? (c.eu > c.fw - 0.25 ? COPPER : BRASS) : mul(MAGL, isPanel(c) ? 1.15 : 0.9));
  B.push(box('body', [2.25, 6.5, -3.375], [2.75, 7, -3.125], lips, { tag: 'AK mag feed lips' }));
  // the wearer's right inner pouch: the top of a black mag peeks out at its outer corner beside the flap (seated: it
  // runs on down inside the pouch)
  B.push(box('body', [-2.25, 6.5, -3.625], [-1.75, 8, -3.125], c => (c.face === 'top' ? mul(MAGL, 1.25)
    : c.face === 'bottom' ? mul(MAG, 0.6) : isPanel(c) ? mul(MAGL, 1.05 * G(c, 0.04)) : mul(MAG, 0.85)), { tag: 'mag top (inner right pouch)' }));

  // ---------------- grenade pouches hanging from the belt on the back panel's lower outer corners ----------------
  // The original hangs them from the belt at the back of each side, but in Minecraft the arms own the torso sides: there
  // a swinging sleeve swallowed the pouch and let it pop back out at every step. So each one hangs just inboard of the
  // arm's sweep (outer face at the sleeve's inner plane), pressed flat on the back panel / back hem, top under the belt.
  const grenade = c => {
    if (c.face === 'front') return null;                                           // pressed on the back panel
    const col = fab(c, POUCH, 0.05, 0.25);
    if (c.face === 'top') return c.ev < px ? mul(col, 1.1) : lin(c, NLIN);         // open mouth: light back rim, dark inside
    if (c.face === 'bottom') return mul(col, 0.7);
    const k = isSide(c) ? 0.84 : 1;                                                // the narrow sides a shade darker
    if (c.ev < px) return mul(col, 1.1 * k);                                       // top binding
    return mul(col, (c.ev > c.fh - px ? 0.84 : 1) * k);                            // bottom seam
  };
  for (const s of [-1, 1]) {
    const X = (a, b) => (s < 0 ? [-b, -a] : [a, b]);
    // the fuse stands in the mouth, behind the belt, its head rising above the belt's lower edge
    const [g0, g1] = X(2.25, 3.75), [f0, f1] = X(2.75, 3.25);
    B.push(box('body', [g0, 10, 3], [g1, 12.25, 4.25], grenade, { tag: 'grenade pouch' }));
    B.push(box('body', [f0, 9.5, 3.5], [f1, 10.5, 4], c => mul(FUSE, c.face === 'top' ? 1.25 : isSide(c) ? 0.8 : G(c, 0.04)), { tag: 'grenade fuse' }));
  }
  return { S: K.S, px: K.px, cell: K.cell, boxes: B };
};
