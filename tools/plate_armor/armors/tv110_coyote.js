// ================= Wartech TV-110 plate carrier ("Coyote") — EFT reference (tarkov.dev 5c0e746986f7741453628fe5) =================
// Same Wartech carrier family as the TV-115 (same bags, straps and vertical-loop cummerbund), but a pale grey-green khaki
// and a much heavier front. The render is seen from the wearer's right front: the darker mesh field left of the plate bag
// is the INSIDE of the back bag (through the armhole), not a side panel. Read off the render (x<0 = wearer's right):
//   straps: padded covers in the lightest khaki, a grey-green rubber patch on each pad's front end
//   upper chest: the plate bag's own MOLLE field, three broad brown tapes split by thin dark seams with plain khaki above
//     and below; grey-green elastic tabs at the bag's front corners
//   front: a kangaroo pouch holding three dark AK mags (their tops show above the flap pouches, the lower ends of two
//     hang out under the carrier's bottom band); in front of it two flap-covered double mag pouches with a dark strap
//     between them; on the wearer's left a narrow single flap pouch, then a radio pouch at the corner with a dark radio
//     and a long whip antenna
//   wearer's right corner: a big boxy GP pouch (face wider than a double mag pouch, about as tall as the mag pouches)
//     with a dark velcro patch covering most of its face, a black strap + buckle dangling
//     from its outer lower corner
ARMORS.tv110_coyote = function (mode) {
  const K = kit('M');                          // always the 2x style
  const { edge, G, isPanel, isSide } = K;
  const cw = K.cell;                           // one art cell (0.5 px)
  const B = [];
  const add = (a, b, mat, tag, opt = {}) => B.push(box('body', a, b, mat, { tag, ...opt }));
  const md = (v, m) => ((v % m) + m) % m;
  const X = (s, a, b) => (s < 0 ? [a, b] : [-b, -a]);   // x-range on the wearer's right (s<0) or mirrored to the left

  // ---------- palette (sampled off the lit parts of the render) ----------
  const KH = hex('#a39c7c');                   // carrier: pale grey-green khaki
  const PAD = hex('#c8bd91');                  // shoulder pad covers (the lightest parts)
  const PATCH = hex('#8f9a81');                // grey-green rubber patches / elastic tabs
  const MOL = hex('#8d7b5c');                  // brown MOLLE webbing
  const POUCH = hex('#959477');                // mag pouches (a little greener than the carrier)
  const GPC = hex('#8f9277');                  // GP pouch
  const VELD = hex('#66624f');                 // velcro patch on the GP pouch
  const SIDE = hex('#8c8a6e');                 // cummerbund
  const STRAPD = hex('#4d5046'), BAND = hex('#80876f'), LINING = hex('#25241d');
  const MAG = hex('#4a4c4c'), MAGTOP = hex('#232426'), DSTEEL = hex('#43484e'), BRASS = hex('#caa24e'), COPPER = hex('#b5703f');
  const RADIO = hex('#3d4144'), BLACK = hex('#232529');

  // ---------- shared painters (same set as the TV-115) ----------
  const fab = (c, base, amt = 0.06, mot = 1) => {
    const q = snap(c.p, cw);
    const n = fbm(q[0] * 0.45 + 7, q[1] * 0.45 + 1, q[2] * 0.45 + 3);
    return mul(base, (1 + 0.1 * mot * (n - 0.5)) * G(c, amt));
  };
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
  const magPaint = base => c => {
    if (c.face === 'bottom') return mul(base, 0.6);
    if (c.face === 'top') return mul(MAGTOP, c.ex < cw ? 1.5 : 1);
    let col = mul(base, G(c, 0.04));
    if (!isPanel(c)) return mul(col, 0.74);
    if (c.ev < cw) return mul(col, 1.3);
    const n = Math.max(1, Math.round(c.fw / cw)), i = Math.floor(c.eu / cw);
    if (i === Math.floor(n / 2)) col = mul(col, 0.8);
    return col;
  };
  const lips = c => (c.face !== 'top' ? mul(DSTEEL, isPanel(c) ? 1.15 : 0.9) : c.eu > c.fw - cw ? COPPER : BRASS);
  const plastic = col => c => mul(col, c.face === 'top' ? 1.5 : c.face === 'bottom' ? 0.7 : isPanel(c) ? (c.ev < cw ? 1.3 : 1) : 0.85);

  // ================= carrier =================
  const frontBag = c => {
    if (c.face === 'top') return mul(LINING, G(c, 0.1));
    if (c.face === 'back') return mul(LINING, 1.2);
    return edge(c, fab(c, KH));
  };
  add([-4.25, 1.25, -3.25], [4.25, 10.5, -2.5], frontBag, 'front plate bag');
  // back plate bag: brown MOLLE rows like the admin panel (the render only shows the front)
  const backBag = c => {
    if (c.face === 'top' || c.face === 'front') return mul(LINING, G(c, 0.1));
    const col = fab(c, KH);
    return edge(c, (c.face === 'back' && rows(c, col, 2.25, 5, -c.p[0], -3.5, 3.5, MOL)) || col);
  };
  add([-4.25, 0.75, 2.5], [4.25, 10.5, 3.25], backBag, 'back plate bag');
  add([-1.25, 0.5, 3.25], [1.25, 1.25, 3.75], c => edge(c, fab(c, mul(KH, 0.8)), { stitch: false }), 'drag handle');
  // elastic cummerbund: the TV-115's wide vertical loops (1 px loop, 0.5 px dark gap) all round the sides
  const cumm = c => {
    let col = fab(c, SIDE);
    if (isSide(c)) {
      const k = md(c.p[2] + 2.75, 1.5);
      col = k < 1 ? mul(fab(c, mix(SIDE, KH, 0.45)), k < cw ? 1.04 : 0.95) : mul(col, 0.68);
    }
    return edge(c, col, { stitch: false });
  };
  add([-4.5, 5, -2.75], [4.5, 10.25, 2.75], cumm, 'cummerbund');

  // ---------- shoulder straps: light padded covers with a grey-green patch on the front end, risers to the bags ----------
  const hump = c => {
    if (c.face === 'bottom') return mul(LINING, 1.2);
    if (c.face === 'front') return mul(PATCH, (c.ev > c.fh - cw ? 0.78 : 1) * G(c, 0.06));   // rubber patch, darker lower rim
    const col = fab(c, PAD, 0.07);
    if (c.face === 'back') return mul(col, 0.9);
    return c.face === 'top' ? mul(col, 1.06) : col;
  };
  const riser = front => c => {
    if (c.face === 'bottom' || c.face === (front ? 'back' : 'front')) return mul(LINING, 1.2);
    return edge(c, fab(c, mix(KH, PAD, 0.4), 0.07), { stitch: false });
  };
  for (const s of [-1, 1]) {
    const [b0, b1] = X(s, -5.75, -4), [h0, h1] = X(s, -5.5, -4.5), [r0, r1] = X(s, -4, -1.5);
    add([b0, -1.25, -3.5], [b1, -0.25, 3.5], hump, 'shoulder pad');
    add([h0, -1.75, -3.25], [h1, -0.75, 3.25], hump, 'shoulder pad crown');
    add([r0, -0.25, -3.5], [r1, 1.5, -3], riser(true), 'strap front');
    add([r0, -0.25, 3], [r1, 2, 3.5], riser(false), 'strap back');
    // grey-green elastic tabs at the plate bag's front corners (cummerbund attachment), a darker fold across the middle
    // (deep enough that the bit sticking out past the bag's side ends close to the sleeve)
    const [t0, t1] = X(s, -4.5, -3.5);
    add([t0, 3, -3.5], [t1, 5.5, -2.75], c => {
      if (c.face === 'back') return mul(LINING, 1.2);
      const col = mul(PATCH, G(c, 0.06));
      if (c.face === 'front' && Math.floor(c.ev / cw) === 2) return mul(col, 0.76);
      return edge(c, col, { stitch: false });
    }, 'cummerbund tab');
  }

  // ---------- MOLLE field on the upper front panel: plain khaki band, three broad brown tapes split by thin dark seams,
  // plain khaki band (7 cells: binding / tape / seam / tape / seam / tape / binding), soft bar tacks every 1.5 px ----------
  const admin = c => {
    if (c.face === 'back') return null;
    if (c.face === 'top') return mul(fab(c, KH), 1.06);
    const col = fab(c, MOL, 0.05, 0.6);
    if (!isPanel(c)) return mul(fab(c, KH), 0.84);
    if (c.ex < cw) return mul(fab(c, KH), c.ev < cw ? 1.06 : 0.96);            // khaki binding round the field
    const j = Math.floor((c.p[1] - c.box.y) / cw);
    let k = j % 2 === 1 ? 1.05 : 0.8;                                          // tape cell / seam cell
    if (j % 2 === 1 && md(c.p[0] + 3.25, 1.5) < cw) k = 0.92;
    return mul(col, k);
  };
  add([-3.25, 1.5, -3.5], [3.25, 5, -3.25], admin, 'MOLLE field');

  // ================= kangaroo pouch + magazines =================
  const kanga = c => {
    if (c.face === 'top') return c.p[2] - c.box.z < cw ? mul(KH, 1.1) : mul(LINING, 1.2);   // open mouth: lit front lip, dark inside
    if (c.face === 'bottom') return mul(LINING, 1.3);                         // open bottom (the mag ends come out)
    if (c.face === 'back') return mul(LINING, 1.1);
    if (c.face === 'front') {
      if (c.p[1] >= 10) return mul(BAND, G(c, 0.05));                         // grey-green bottom band
      if (Math.abs(c.p[0]) < 0.5) return mul(STRAPD, G(c, 0.06));             // dark strap between the flap pouches
    }
    return edge(c, fab(c, KH), { stitch: false });
  };
  add([-3, 5.5, -4], [3, 10.5, -3.25], kanga, 'kangaroo pouch');
  const mag = magPaint(MAG);
  for (const cx of [-1.75, 0, 1.75]) {
    add([cx - 0.75, 5, -3.875], [cx + 0.75, 8.5, -3.375], mag, 'AK magazine');
    add([cx - 0.5, 4.5, -3.75], [cx + 0.5, 5, -3.5], lips, 'mag feed lips');
  }
  // lower ends of two of the mags, curving forward out of the kangaroo's open bottom: lighter floor plate
  const magEnd = c => {
    if (c.face === 'top') return mul(MAGTOP, 1.2);
    if (c.face === 'bottom') return mul(MAG, 1.25);
    let col = mul(MAG, G(c, 0.04));
    if (!isPanel(c)) return mul(col, 0.74);
    if (c.ev > c.fh - cw) return mul(col, 1.35);                              // floor plate
    return col;
  };
  for (const cx of [0, 1.75]) add([cx - 0.75, 10.5, -3.875], [cx + 0.75, 12, -3.375], magEnd, 'magazine lower end', { rot: [-12, 0, 0], pivot: [cx, 10.5, -3.625] });

  // ================= flap pouches =================
  // body (open sides below the flap) + a separate flap plate in front of its upper part, so the flap's lower edge steps
  // soft rounded shading: side columns a little darker, lower edge darker, no inner frames (a stitched square on the
  // flaps stacked into concentric rectangles)
  const soft = (c, col) => {
    if (c.face !== 'front') return mul(col, c.face === 'top' ? 1.08 : 0.84);
    if (c.ev > c.fh - cw) return mul(col, 0.72);
    if (c.eu < cw || c.eu > c.fw - cw) return mul(col, 0.86);
    return col;
  };
  // body: a darker shadow row right under the flap's edge; on a double pouch a darker crease down the middle splits the
  // part below the flap into two mag bulges
  const body = (base, flapY, dbl) => c => {
    if (c.face === 'back') return mul(LINING, 1.1);
    const col = fab(c, base);
    if (c.face === 'top') return mul(col, 0.85);
    if (c.face === 'bottom') return mul(col, 0.62);
    if (c.face === 'front' && c.p[1] > flapY && c.ev <= c.fh - cw) {
      if (c.p[1] < flapY + cw) return mul(col, 0.78);                           // shadow under the flap
      if (dbl && Math.floor(c.eu / cw) === Math.floor(c.fw / cw / 2)) return mul(col, 0.8);   // crease between the mags
    }
    return soft(c, mul(col, c.face === 'front' ? 0.94 : 1));
  };
  const flap = base => c => {
    if (c.face === 'back') return mul(LINING, 1.1);
    const col = mul(fab(c, base), 1.08);                                         // flap face catches more light
    if (c.face === 'bottom') return mul(col, 0.66);
    if (c.face === 'front' && c.ev < cw) return mul(col, 1.1);                  // lit fold at the top
    return soft(c, col);
  };
  for (const [x0, x1] of [[-2.75, -0.25], [0.25, 2.75]]) {
    add([x0, 5.5, -4.75], [x1, 10, -4], body(POUCH, 8.5, true), 'double mag pouch');
    add([x0, 5.5, -5], [x1, 8.5, -4.75], flap(POUCH), 'pouch flap');
  }
  // narrow single pouch on the wearer's left, hanging a little lower
  add([2.75, 6, -4.5], [3.75, 11, -3.25], body(POUCH, 7.5, false), 'single mag pouch');
  add([2.75, 6, -4.75], [3.75, 7.5, -4.5], flap(POUCH), 'single pouch flap');

  // ================= radio pouch (wearer's left corner) =================
  const radioPouch = c => {
    if (c.face === 'top') return c.ex < cw ? mul(POUCH, 1.1) : mul(LINING, 1.2);
    if (c.face === 'back') return mul(LINING, 1.1);
    let col = fab(c, POUCH);
    if (c.face === 'front' && c.p[1] >= 8.5 && c.p[1] < 9) col = mul(col, 1.12);   // retention strap across the radio
    else if (c.face === 'front' && c.p[1] >= 9 && c.p[1] < 9.5) col = mul(col, 0.8);
    return edge(c, col, { stitch: false });
  };
  add([3.75, 8, -4.25], [4.75, 11.5, -2.5], radioPouch, 'radio pouch');          // reaches back to 0.25 in front of the sleeve
  add([3.875, 6.5, -4], [4.625, 9.5, -3], plastic(RADIO), 'radio');
  add([4.125, 4.5, -3.75], [4.625, 6.5, -3.25], plastic(mul(RADIO, 0.8)), 'whip antenna');

  // ================= GP pouch (wearer's right corner) =================
  // (the most prominent item on the render: a deep padded box whose face is wider than a double mag pouch and about as tall
  // as the mag pouches, flush with their flaps; it reaches back to 0.25 in front of the sleeve)
  const gp = c => {
    if (c.face === 'back') return mul(fab(c, GPC), 0.8);
    const col = fab(c, GPC);
    if (c.face === 'top') return mul(col, c.p[2] - c.box.z < cw ? 1.16 : 1.06);   // rolled front edge catches the light
    if (c.face === 'bottom') return mul(col, 0.62);
    if (c.face === 'front') {
      // 6 x 9 cells: lit rolled top, a lid band with the zipper seam, a 4 x 6 dark velcro patch, shadowed rolled bottom
      const i = Math.floor(c.eu / cw), j = Math.floor(c.ev / cw);
      const nj = Math.round(c.fh / cw);
      if (j === 0) return mul(col, 1.14);                                         // rolled top edge
      if (j === nj - 1) return mul(col, 0.8);                                     // rolled bottom edge, in shadow
      if (i === 0 || i === Math.round(c.fw / cw) - 1) return mul(col, 1.05);      // rolled side edges
      if (j === 1) return mul(col, 0.9);                                          // lid band with the zipper seam
      return mul(VELD, G(c, 0.08) * (j === 2 ? 0.9 : 1));                         // big dark velcro patch
    }
    // side walls: darker, a lit rolled edge along the front, shadowed bottom row
    if (c.p[1] > c.box.y + c.box.h - cw) return mul(col, 0.7);
    return mul(col, c.p[2] - c.box.z < cw ? 0.98 : 0.84);
  };
  add([-5.75, 6.5, -5], [-2.75, 11, -2.5], gp, 'GP pouch');
  // black strap + side-release buckle dangling from its outer lower corner
  add([-6, 8.5, -4], [-5.5, 12, -3.5], c => {
    const y = c.p[1];
    if (y >= 10 && y < 11) return mul(BLACK, c.face === 'top' ? 1.5 : y < 10.5 ? 1.35 : 1.05);
    return mul(STRAPD, (isPanel(c) ? 1 : 0.8) * G(c, 0.06));
  }, 'GP strap + buckle');
  return { S: K.S, px: K.px, cell: K.cell, boxes: B };
};
