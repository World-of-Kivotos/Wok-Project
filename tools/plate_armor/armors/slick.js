// ================= LBT 6094A Slick plate carrier (Black) — EFT reference (tarkov.dev 5e4abb5086f77406975c9342) =================
// A true "slick" carrier: no pouches and no gear on the item model. Read off the render and the inspect view (x<0 = wearer's
// right): black Cordura with a soft leather-like sheen (lit along the top, darker lower down); front plate bag with rounded
// top corners; wide padded shoulder straps that are the lightest part of the vest (warm grey, stitched boxes on the pad,
// teal-black lining underneath), their lower ends covered in grey loop velcro where they run into the bag's top corners;
// black ladder-lock buckles where the straps meet the back panel; a warm dark-grey loop-velcro field across the upper chest
// (a faint seam across its middle) with a webbing pull tab hanging from the bag's top edge in its middle; right under it a
// flat admin pocket (elastic binding on top, one stitched channel); the lower front is plain black: a sewn panel with one
// seam, then the slightly wider cummerbund closure flap (one seam, teal-black bound bottom edge); beside the loop field a
// short vertical keeper on each side, a webbing tab sticking out of each side seam and a lower keeper near the bag's edge;
// slate-grey laminate cummerbund at the waist, level with the flap; two short webbing tabs hang under the flap (one near
// the wearer's right edge, one just left of the middle) and a tab at each bottom corner splays outward.
// The back is not visible in the render: plain black with the front's sewn seams.
ARMORS.slick = function (mode) {
  const K = kit('M');                                   // always the 2x style
  const { G, isPanel, px } = K;
  const B = [];
  const add = (a, b, mat, tag, opt = {}) => B.push(box('body', a, b, mat, { tag, ...opt }));

  // ---------- palette (sampled from the render, lifted a little so the black keeps its shading in Minecraft) ----------
  // (keepers / side tabs / pull tab / strap-end velcro are lighter than the bag in the render, the hanging tabs stay dark)
  const BK = hex('#303132'), LOOPC = hex('#484542'), TABC = hex('#4e4945'), PAD = hex('#6c6864'), VEL = hex('#67625d'),
    CUMM = hex('#4f5459'), WEB = hex('#363636'), KEEP = hex('#4e4a47'), LIN = hex('#1a2628'), BUCK = hex('#1c1d1f');

  // Cordura with a soft sheen: slow low-frequency brightness drift + faint per-cell grain
  const fab = (c, base, amt = 0.06) => {
    const q = snap(c.p, px);
    const n = fbm(q[0] * 0.28 + 5, q[1] * 0.28 + 2, q[2] * 0.28 + 9);
    return mul(base, (0.93 + 0.14 * n) * G(c, amt));
  };
  const lin = c => mul(LIN, G(c, 0.08));
  // lit from above like the render: a sheen along the bag tops, the lower field a little darker
  const lit = y => 0.95 - 0.012 * (y - 5) + 0.35 * sm(clamp01((3 - y) / 2));
  // sewn seams: a soft dark cell row above the seam line, a lit cell row under it (one 1 px pair, kept gentle so the
  // smooth leather-like panels never read as ribbed strips)
  const band = (y, seams) => {
    for (const s of seams) {
      if (y >= s - 0.5 && y < s) return 0.86;
      if (y >= s && y < s + 0.5) return 1.08;
    }
    return 1;
  };

  // ================= front plate bag =================
  const bagF = c => {
    const y = c.p[1], ax = Math.abs(c.p[0]);
    if (c.face === 'back') return lin(c);
    if (c.face === 'top') return mul(fab(c, BK), 1.35);
    if (c.face === 'bottom') return mul(fab(c, BK), 0.6);
    if (c.face !== 'front') return mul(fab(c, BK), 0.8 * lit(y));
    // smooth lower panel under the admin pocket: one edge pair at its top (shadow beside the pocket, lit panel top),
    // a single fine seam cell across its middle, the rest plain - the sheen comes from lit() and the fbm drift
    const k = y >= 6 && y < 6.5 ? 0.84 : y >= 6.5 && y < 7 ? 1.08 : y >= 7.5 && y < 8 ? 0.92 : 1;
    let col = mul(fab(c, BK), lit(y) * k);
    if (ax > 3.25) col = mul(col, y < 2.5 ? 1.1 : 0.84);  // rounded outer edges (lit where they turn into the top)
    else if (ax > 2.75 && y < 2) col = mul(col, 1.1);     // the step of the rounded corner
    return col;
  };
  // rounded top corners: two narrower steps on top of the full-width bag
  // (bag top to flap bottom is ~1.4x the bag's width like the original: loop field 2..4.5, admin pocket 4.5..6.5,
  //  lower panel to 8.5, closure flap 8.5..11.5; the bag itself ends behind the flap)
  add([-3.75, 2, -3.25], [3.75, 11, -2.5], bagF, 'front plate bag');
  add([-3.25, 1.5, -3.25], [3.25, 2, -2.5], bagF, 'front plate bag (corner step)');
  add([-2.75, 1, -3.25], [2.75, 1.5, -2.5], bagF, 'front plate bag (top)');

  // warm dark-grey loop velcro: fine even speckle, lighter at the top like the render, a faint seam across its middle
  const loop = c => {
    const y = c.p[1];
    if (c.face === 'back') return lin(c);
    const col = mul(LOOPC, G(c, 0.1) * (1.08 - 0.07 * (y - 2)));
    if (c.face === 'top') return mul(col, 1.2);
    if (c.face === 'bottom') return mul(col, 0.6);
    if (c.face !== 'front') return mul(col, 0.74);
    if (c.eu < px || c.eu > c.fw - px) return mul(col, 0.86);             // bound side edges
    if (y >= 3 && y < 3.5) return mul(col, 0.88);                       // seam between the two loop panels
    return col;
  };
  add([-2.25, 2, -3.5], [2.25, 4.5, -3.25], loop, 'loop field');
  // flat admin pocket under the loop field: black like the bag, elastic binding on top, one stitched channel, bound sides
  const admin = c => {
    const y = c.p[1];
    if (c.face === 'back') return lin(c);
    const col = fab(c, BK);
    if (c.face === 'top') return mul(col, 1.45);
    if (c.face === 'bottom') return mul(col, 0.55);
    if (c.face !== 'front') return mul(col, 0.78);
    const j = Math.min(3, Math.floor((y - 4.5) / px));
    let k = [1.28, 1.04, 0.8, 1.0][j];
    if (c.eu < px || c.eu > c.fw - px) k *= 0.86;
    return mul(col, k);
  };
  add([-2.25, 4.5, -3.75], [2.25, 6.5, -3.25], admin, 'admin pocket');
  // webbing pull tab hanging from the bag's top edge over the loop field (lit folded end at the bottom)
  const pull = c => {
    let col = mul(fab(c, TABC, 0.05), c.face === 'top' ? 1 : lit(c.p[1]));    // lit leather-like tab, brighter at the top
    if (c.face === 'top') return mul(col, 1.3);
    if (c.face === 'bottom') return mul(col, 0.9);
    if (c.face === 'back') return lin(c);
    if (c.face !== 'front') return mul(col, 0.72);
    if (c.ev > c.fh - px) col = mul(col, 1.2);
    return col;
  };
  add([-0.5, 1, -3.75], [0.5, 3, -3.25], pull, 'pull tab');

  // ================= cummerbund closure flap: slightly wider than the bag, one seam, teal-black bound bottom =================
  const flap = c => {
    const y = c.p[1], ax = Math.abs(c.p[0]);
    if (c.face === 'back') return lin(c);
    if (c.face === 'top') return mul(fab(c, BK), 1.35);
    if (c.face === 'bottom') return mix(mul(fab(c, BK), 0.6), LIN, 0.4);
    if (c.face !== 'front') return mul(fab(c, BK), 0.78);
    // rows: lit top rim, plain, one fine seam near the middle (soft dark row + lit row), plain, teal-black bound hem
    const j = Math.min(5, Math.floor(c.ev / px));
    let col = mul(fab(c, BK), lit(y) * [1.12, 1.0, 0.9, 1.04, 1.0, 1][j]);
    if (j === 5) return mix(mul(col, 0.82), LIN, 0.35);
    if (ax > 3.5) col = mul(col, 0.84);
    return col;
  };
  add([-4, 8.5, -3.5], [4, 11.5, -2.75], flap, 'closure flap');

  // ================= slate cummerbund at the waist (level with the flap, goes in under it) =================
  const cumm = c => {
    const col = fab(c, CUMM, 0.07);
    if (c.face === 'top') return mul(col, 1.2);
    if (c.face === 'bottom') return mul(col, 0.6);
    if (c.face === 'front') return mul(mix(col, LOOPC, 0.5), 0.8);          // loop strip where it goes under the flap
    if (c.ev < px) return mul(col, 1.12);
    if (c.ev > c.fh - px) return mul(col, 0.82);
    return col;
  };
  add([-4.5, 8.75, -2.75], [4.5, 11.25, 2.75], cumm, 'cummerbund');

  // ================= back plate bag (taller than the front; plain, with the front's sewn seams) =================
  const bagB = c => {
    const y = c.p[1], ax = Math.abs(c.p[0]);
    if (c.face === 'front') return lin(c);
    if (c.face === 'top') return mul(fab(c, BK), 1.35);
    if (c.face === 'bottom') return mix(mul(fab(c, BK), 0.6), LIN, 0.4);
    if (c.face !== 'back') return mul(fab(c, BK), 0.8 * lit(y));
    let col = mul(fab(c, BK), lit(y) * band(y, [6.5, 9]));
    if (y > 11) return mix(mul(col, 0.82), LIN, 0.35);                         // teal-black bound bottom edge
    if (ax > 3.25) col = mul(col, y < 2 ? 1.1 : 0.84);
    else if (ax > 2.75 && y < 1.5) col = mul(col, 1.1);
    return col;
  };
  add([-3.75, 1.5, 2.5], [3.75, 11.5, 3.25], bagB, 'back plate bag');
  add([-3.25, 1, 2.5], [3.25, 1.5, 3.25], bagB, 'back plate bag (corner step)');
  add([-2.75, 0.5, 2.5], [2.75, 1, 3.25], bagB, 'back plate bag (top)');

  // ================= shoulder straps =================
  // Over the shoulders the straps sit inside the Minecraft head, so what shows is (a) a padded strap segment on top of each
  // shoulder just outside the hat layer - the lightest part of the vest, stitched boxes on top, teal lining underneath -
  // and (b) the risers below the chin and at the back, splayed outward like the render. Front risers run into the bag's
  // top corners behind its front face; back risers carry black ladder-locks.
  const pad = inner => c => {
    if (c.face === 'bottom' || c.face === inner) return lin(c);
    const col = fab(c, PAD, 0.06);
    if (c.face === 'top') {
      const ax = Math.abs(c.p[0]), az = Math.abs(c.p[2]);
      // a small stitched box over each end of the pad (two cross seams + one seam along), plain over the shoulder top
      const st = (ax > 4.75 && ax < 5.25 && az > 1.25 && az < 2.25) || (az > 0.75 && az < 1.25) || (az > 2.25 && az < 2.75);
      return mul(col, st ? 0.9 : 1.1);
    }
    if (c.ev > c.fh - px) return mix(mul(col, 0.7), LIN, 0.6);             // teal lining showing under the pad's edge (bottom cell only)
    return mul(col, isPanel(c) ? 1.08 : 0.94);                               // light grey ends (the lightest thing seen from the front/back) and outer side
  };
  const riser = (outFace, inFace) => c => {
    if (c.face === inFace || c.face === 'bottom') return lin(c);
    let col = mul(VEL, G(c, 0.12));                                           // grey loop velcro on the strap ends
    if (c.face === 'top') return mul(col, 1.15);
    if (c.face !== outFace) return mul(col, 0.8);
    if (c.eu < px || c.eu > c.fw - px) col = mul(col, 0.84);                 // bound strap edges
    return col;
  };
  const buckle = c => {
    if (c.face === 'top') return mul(BUCK, 1.7);
    if (c.face !== 'back') return mul(BUCK, 0.9);
    return mul(BUCK, c.ev < px ? 1.6 : 1.05);                                  // lit crossbar, dark slot under it
  };
  for (const s of [-1, 1]) {
    const X = (a, b) => (s < 0 ? [a, b] : [-b, -a]);
    const [p0, p1] = X(-5.75, -4.25);
    // the pad reaches down to the slim sleeve's top (with regular arms its lower part sits inside the sleeve)
    add([p0, -1.25, -3.25], [p1, 0.25, 3.25], pad(s < 0 ? 'left' : 'right'), 'shoulder pad');
    const [r0, r1] = X(-3.75, -1.75), cx = s * 2.75;
    const splay = { rot: [0, 0, s * 15] };
    // front riser runs down into the bag's rounded top corner (its lower end hidden behind the bag front)
    add([r0, -0.75, -3.125], [r1, 2, -2.625], riser('front', 'back'), 'strap front', { ...splay, pivot: [cx, 1.5, -2.875] });
    add([r0, -0.75, 2.75], [r1, 1.75, 3.5], riser('back', 'front'), 'strap back', { ...splay, pivot: [cx, 1.75, 3.125] });
    const [b0, b1] = X(-3.875, -1.625);
    add([b0, 0.25, 3.5], [b1, 1.25, 3.75], buckle, 'ladder-lock buckle', { ...splay, pivot: [cx, 1.75, 3.125] });
  }

  // ================= small webbing keepers and side tabs =================
  // keepers and side tabs: dark-grey webbing, clearly lighter than the black bag; the upper ones share the top sheen
  const web = sheen => c => {
    let col = mul(fab(c, KEEP, 0.05), sheen && c.face !== 'top' ? lit(c.p[1]) : 1);
    if (c.face === 'top') return mul(col, 1.4);
    if (c.face === 'bottom') return mul(col, 0.6);
    if (c.face === 'back') return lin(c);
    if (c.face !== 'front') return mul(col, 0.75);
    if (c.ev < px) return mul(col, 1.22);                                      // lit top fold
    if (c.ev > c.fh - px) return mul(col, 0.86);
    return col;
  };
  for (const s of [-1, 1]) {
    const X = (a, b) => (s < 0 ? [a, b] : [-b, -a]);
    const [u0, u1] = X(-3.25, -2.25), [t0, t1] = X(-4.5, -3.25), [l0, l1] = X(-3.75, -2.75);
    add([u0, 1.75, -3.5], [u1, 3.25, -3.25], web(true), 'upper keeper');
    // side tab coming out of the bag's side seam (starts inside the bag, sticks out past its edge)
    add([t0, 2.25, -3.125], [t1, 3.25, -2.625], web(true), 'side tab');
    add([l0, 3.5, -3.5], [l1, 5, -3.25], web(false), 'lower keeper');
  }

  // ================= short webbing tabs hanging under the flap =================
  // (their tops are tucked into the flap; the folded ends catch the light)
  const hang = c => {
    let col = fab(c, WEB, 0.05);
    if (c.face === 'bottom') return mul(col, 1.1);
    if (!isPanel(c)) return mul(col, 0.75);
    if (c.face === 'back') return mul(col, 0.6);
    if (c.ev > c.fh - px) col = mul(col, 1.2);
    return col;
  };
  add([-3, 11, -3.375], [-2, 13, -3.125], hang, 'hanging tab');
  add([0, 11, -3.375], [1, 13, -3.125], hang, 'hanging tab');
  for (const s of [-1, 1]) {
    const cx = s * 3.5;
    add([cx - 0.5, 11, -3.375], [cx + 0.5, 12.75, -3.125], hang, 'corner tab', { rot: [0, 0, -s * 35], pivot: [cx, 11.25, -3.25] });
  }

  return { S: K.S, px: K.px, cell: K.cell, boxes: B };
};
