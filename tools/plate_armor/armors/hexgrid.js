// ================= 5.11 Hexgrid Plate Carrier — EFT reference (tarkov.dev 5fd4c474dd870108a754b241) =================
// Minimal black slick carrier. Reference render: front plate bag with rounded top corners whose whole face is a laser-cut
// honeycomb panel (a grey lattice, top-lit and clearly lighter than the dark holes, of small flat-topped hexagon holes,
// about nine columns across, a solid rim round it, holes cut by the rim along the edges; 2x keeps five columns, the
// smallest hexagon that still reads as one); through the top two or three rows of holes black loop velcro shows, a
// seam, then dark navy plate-bag fabric; wide flat grey shoulder straps come out from behind the bag's top corners and
// run over the shoulders through thick padded shoulder covers (the lightest, bluish-grey parts of the vest) that sit
// down on the shoulders; the sides are open: two
// black elastic straps form a sideways V ('>') whose point sits on the front bag's side edge at chest height, one running
// back up to the back bag's top, the other down to the back bag's bottom where it meets the waist strap; a black waist
// strap runs round the hips along the bag's bottom edge (the bag reaches a little below it) with a side-release buckle
// near the back bag. The plate bag is clearly taller than wide (about 1.4 : 1 on the render). No cummerbund,
// no pouches, no groin protector. The render never shows the back; the real carrier has the same honeycomb panel there.
ARMORS.hexgrid = function (mode) {
  const K = kit('M');                                   // always the 2x style
  const { edge, G, isPanel, isSide, px } = K;
  const B = [];
  // brightness order of the render: pads > lattice (top-lit) ~ grey shoulder straps > holes / bag / black straps; the
  // holes are clearly darker than the frame (measured on the front view like the render: the frame's light third is
  // about 1.9x the holes' dark third on the navy part, about 2.8x on the velcro rows)
  const BK = hex('#2b2d31'), LAT = hex('#3c3f44'), NAVY = hex('#21252e'), VEL = hex('#202024'), LINING = hex('#151618');
  const STRAP = hex('#3d3f43'), PAD = hex('#5c5f68'), PADS = hex('#50535c'), XSTRAP = hex('#212225'), BELT = hex('#28292c');
  const BUCK = hex('#34373a');
  // fabric: faint low-frequency mottling + per-cell grain
  const fab = (c, base, amt = 0.06) => {
    const q = snap(c.p, 0.5);
    const n = fbm(q[0] * 0.45 + 5, q[1] * 0.45 + 2, q[2] * 0.45 + 9);
    return mul(base, (0.96 + 0.08 * n) * G(c, amt));
  };

  // ---------------- honeycomb panel layout (shared by front and back) ----------------
  // 18 x 23 art cells (x -4.5..4.5, y 0.75..12.25): about 1.3 times as tall as wide, close to the render's plate (its
  // hexagons are nearly regular, so its face is barely foreshortened: nine columns by eleven rows, ~1.4 : 1); the
  // bottom row of holes is cut flat by the rim like on the render. The top corners are rounded in two steps (1 px, then 0.5 px in).
  // Holes: small round-cornered hexagons 2 x 2 px (rows 2 / 4 / 4 / 2 cells wide) in columns 1.5 px apart, every other
  // column half a step lower, so the lattice between them is one cell (straight bars top / bottom, stair-stepped
  // slanted bars) and five columns fit inside a one-cell rim. (Flatter 2 / 4 / 2 holes turned into a diamond checker
  // at normal viewing distance; these read as dark hex holes in a grey frame, as on the render.) Hexagons cut by the
  // rim keep their part inside when at least half of them is left (like the laser-cut original), smaller fragments are
  // dropped (they would read as single dots).
  const X0 = 4.5, Y0 = 0.75, NI = 18, NJ = 23;
  const inset = j => (j === 0 ? 2 : j === 1 ? 1 : 0);                                  // cells cut off each side, per row
  const inside = (i, j) => j >= 0 && j < NJ && i >= inset(j) && i < NI - inset(j);
  const inner = (i, j) => inside(i, j) && inside(i - 1, j) && inside(i + 1, j) && inside(i, j - 1) && inside(i, j + 1);
  const HOLES = new Set();
  for (let k = -3; k <= 3; k++) for (let m = -1; m <= 4; m++) {
    const c0 = 9 + 3 * k, top = 1 + 6 * m + ((k & 1) ? 3 : 0);                        // centre on a cell boundary
    const cells = [];
    [2, 4, 4, 2].forEach((w, r) => { for (let i = c0 - w / 2; i < c0 + w / 2; i++) cells.push([i, top + r]); });
    const inn = cells.filter(([i, j]) => inner(i, j));
    if (inn.length * 2 >= cells.length) for (const [i, j] of inn) HOLES.add(i + ',' + j);
  }
  const hole = (i, j) => HOLES.has(i + ',' + j);
  const cellOf = (x, y) => [Math.floor((x + X0) / px), Math.floor((y - Y0) / px)];
  const state = (x, y) => { const [i, j] = cellOf(x, y); return !inside(i, j) ? 0 : hole(i, j) ? 2 : 1; };

  // laser-cut lattice: its own thin layer in front of the plate bag, the holes are real openings. Like the render, the
  // grey frame catches the light from above: brighter over the top rows, easing to its plain grey by mid-chest.
  const lit = y => 1 + 0.25 * (1 - sm(clamp01((y - 3) / 3)));
  const lattice = out => c => {
    const inw = out === 'front' ? 'back' : 'front';
    if (c.face === inw) return null;                                                      // pressed flat on the bag
    const x = c.p[0], y = c.p[1];
    if (c.face === out) {
      const [i, j] = cellOf(x, y);
      if (!inside(i, j) || hole(i, j)) return null;
      let col = mul(fab(c, LAT, 0.06), lit(y));
      if (!inside(i, j - 1)) col = mul(col, 1.14);                                        // lit top edge of the panel
      else if (!inside(i, j + 1)) col = mul(col, 0.78);
      else if (!inside(i - 1, j) || !inside(i + 1, j)) col = mul(col, 0.86);
      return col;
    }
    if (isSide(c)) return y >= Y0 + 2 * px ? mul(fab(c, LAT), 0.78 * lit(y)) : null;     // only where the panel is full width
    if (c.face === 'top') return Math.abs(x) < X0 - 2 * px ? mul(fab(c, LAT), 1.15) : null;
    return mul(fab(c, LAT), 0.7);
  };
  // plate bag behind it: what shows through the holes is black loop velcro behind the top rows (about the top quarter
  // of the panel, the seam crossing the second / third hex row as on the render), a seam, then navy fabric
  const VY = 3.75;
  const bag = out => c => {
    const inw = out === 'front' ? 'back' : 'front';
    if (c.face === inw) return mul(LINING, G(c, 0.08));
    const x = c.p[0], y = c.p[1];
    if (c.face === out) {
      let col = y < VY ? mul(VEL, G(c, 0.1)) : fab(c, NAVY, 0.06);
      if (y >= VY && y < VY + px) col = mul(col, 0.72);                                    // seam under the velcro field
      if (state(x, y - px) === 1) col = mul(col, 0.85);                                     // the lattice shades the hole's top row
      return col;
    }
    if (c.face === 'top') return mul(fab(c, BK), 1.12);
    if (c.face === 'bottom') return mul(fab(c, BK), 0.7);
    return edge(c, fab(c, BK), { stitch: false });
  };
  // stepped plate bags (rows 0 / 1 are narrower, matching the rounded corners of the lattice) + lattice layers
  for (const [out, z0, z1, l0, l1] of [['front', -3.25, -2.5, -3.5, -3.25], ['back', 2.5, 3.25, 3.25, 3.5]]) {
    B.push(box('body', [-X0 + 1, Y0, z0], [X0 - 1, Y0 + px, z1], bag(out), { tag: out + ' plate bag (top)' }));
    B.push(box('body', [-X0 + px, Y0 + px, z0], [X0 - px, Y0 + 2 * px, z1], bag(out), { tag: out + ' plate bag (shoulder)' }));
    B.push(box('body', [-X0, Y0 + 2 * px, z0], [X0, Y0 + NJ * px, z1], bag(out), { tag: out + ' plate bag' }));
    B.push(box('body', [-X0, Y0, l0], [X0, Y0 + NJ * px, l1], lattice(out), { tag: out + ' honeycomb panel' }));
  }

  // ---------------- shoulder straps + padded shoulder covers ----------------
  // the straps over the shoulders sit inside the MC head; what shows: the strap coming out from behind each rounded top
  // corner of the bags (a little recessed behind the bag) and running up under the end of the thick pad on top of each
  // shoulder beside the head. The pad wraps the strap: a strip of strap runs under it from front to back, and the pad
  // sits down on the arm top (slim arms: on that strip), so there is no daylight under it for any arm / sleeve setting.
  const strap = c => {
    if (!isPanel(c)) return mul(fab(c, STRAP), 0.8);
    return edge(c, fab(c, STRAP, 0.05), { stitch: false });
  };
  const hump = c => {
    if (c.face === 'bottom') return mul(LINING, 1.3);
    if (c.face === 'top') return mul(fab(c, PAD, 0.06), 1.06);
    if (isPanel(c)) return mul(fab(c, PADS), 0.9);                                         // pad ends
    return edge(c, mix(fab(c, PADS), fab(c, PAD), c.ev < px ? 0.9 : 0.55), { stitch: false });   // rounded outer sides
  };
  for (const s of [-1, 1]) {
    const X = (a, b) => (s < 0 ? [-b, -a] : [a, b]);
    const [r0, r1] = X(2, 4.75);
    B.push(box('body', [r0, -0.25, -3], [r1, Y0 + 2 * px, -2.75], strap, { tag: 'strap front' }));
    B.push(box('body', [r0, -0.25, 2.75], [r1, Y0 + 2 * px, 3], strap, { tag: 'strap back' }));
    const [w0, w1] = X(4.25, 5.25);
    B.push(box('body', [w0, 0.25, -2.75], [w1, 0.5, 2.75], strap, { tag: 'strap under shoulder pad' }));
    const [h0, h1] = X(4.25, 5.75), [k0, k1] = X(4.5, 5.5);
    B.push(box('body', [h0, -0.75, -3.5], [h1, 0.25, 3.5], hump, { tag: 'shoulder pad' }));
    B.push(box('body', [k0, -1.25, -3.25], [k1, -0.25, 3.25], hump, { tag: 'shoulder pad crown' }));
  }

  // ---------------- open sides: elastic straps in a sideways V + waist strap with a side-release buckle ----------------
  // the two straps are cut out of one thin side layer running from the front bag's inner face to the back bag's
  // (everything else transparent, so the shirt shows between them): both start together on the front bag's side edge
  // at chest height and spread backwards, the upper one up to the back bag's top, the lower one down to the back bag's
  // bottom where it runs in under the waist strap; they never cross in the open gap
  const YA = 2.5, YB = 10.75, YF = 5;                                                    // back top / back bottom / front point
  const UH = 0.5 * Math.hypot(1, (YF - 0.25 - YA) / 5), LH = 0.5 * Math.hypot(1, (YB - YF - 0.25) / 5);
  const onX = (z, y) => {
    const t = (z + 2.5) / 5;                                                             // 0 = front bag, 1 = back bag
    const yU = YF - 0.25 - (YF - 0.25 - YA) * t, yL = YF + 0.25 + (YB - YF - 0.25) * t;
    return Math.abs(y - yU) < UH ? 2 : Math.abs(y - yL) < LH ? 1 : 0;
  };
  const xside = s => c => {
    const out = s < 0 ? 'right' : 'left';
    if (c.face === (s < 0 ? 'left' : 'right')) return null;                               // pressed on the shirt
    const z = Math.min(2.25, Math.max(-2.25, c.p[2])), y = Math.min(YB - 0.25, Math.max(YA + 0.25, c.p[1]));
    const k = onX(z, y);
    if (!k) return null;
    const col = mul(XSTRAP, G(c, 0.06) * (k === 2 ? 1.12 : 1));                            // the upper strap a shade lighter
    return c.face === out ? col : mul(col, 0.8);
  };
  for (const s of [-1, 1]) {
    const X = (a, b) => (s < 0 ? [-b, -a] : [a, b]);
    const [a0, a1] = X(4.25, 4.5);
    B.push(box('body', [a0, YA, -2.5], [a1, YB, 2.5], xside(s), { tag: 'side straps (sideways V)' }));
  }
  const belt = c => {
    let col = mul(BELT, G(c, 0.06));
    if (c.face === 'top') return mul(col, 1.1);
    if (c.face === 'bottom') return mul(col, 0.7);
    if (c.ev < px) col = mul(col, 1.18);
    else if (c.ev > c.fh - px) col = mul(col, 0.82);
    return col;
  };
  // along the bags' bottom edge, like the render (the bags reach a little below it)
  B.push(box('body', [-4.75, 10.5, -2.75], [4.75, 12, 2.75], belt, { tag: 'waist strap' }));
  const buckle = c => {
    if (c.face === 'top') return mul(BUCK, 1.4);
    if (c.face === 'bottom') return mul(BUCK, 0.7);
    if (!isSide(c)) return mul(BUCK, 0.85);
    const v = c.ev;
    if (v < px) return mul(BUCK, 1.3);
    if (v > c.fh - px) return mul(BUCK, 0.8);
    return mul(BUCK, Math.abs(c.eu - c.fw / 2) < px ? 1.12 : 1);                         // centre ridge of the housing
  };
  for (const s of [-1, 1]) {
    const X = (a, b) => (s < 0 ? [-b, -a] : [a, b]);
    const [b0, b1] = X(4.75, 5);
    B.push(box('body', [b0, 10.25, 1], [b1, 12.25, 2.5], buckle, { tag: 'waist buckle' }));
  }
  return { S: K.S, px: K.px, cell: K.cell, boxes: B };
};
