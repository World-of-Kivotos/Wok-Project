// ================= 6B23-2 body armor (Mountain Flora) — EFT reference (tarkov.dev 5c0e57ba86f7747fa141986d) =================
// Same vest as the 6B23-1 (b6b23_1_digital_flora), in Russian "Mountain Flora": a pale yellow-green ground with big
// sideways-stretched rust-brown blotches (darker brown cores, lighter tan halos) and a few sage-green patches; more than
// half of it reads warm. Reference render: tall padded collar cup in front of the neck (dark grey lining) with an
// X-stitched tab on its back; thick padded shoulder straps each carrying a long sage-olive velcro patch (raised, light
// rim) and a small olive keeper loop at the crest; big plain front panel with dark green-grey bound edges; horizontal seam
// at about two thirds of the height, below it a proud lower flap (wearer's-right edge inset, olive closure strap down the
// front corner beside it); deep open armholes down to a camo side band with olive belt loops; narrower groin flap hanging
// from two olive tabs (small rounded bottom corners); the back panel hangs about two pixels lower than the front
// (near-black green inside).
ARMORS.b6b23_2_mountain_flora = function (mode) {
  const K = kit('M');                          // always the 2x style, whatever mode is asked for
  const { G, isPanel, isSide, px } = K;
  const B = [];

  // ---------- palette (sampled off the render's lit faces) ----------
  const C = {
    bind: hex('#3a4436'),    // dark green-grey binding on every edge
    lining: hex('#505857'),  // dark grey collar lining
    inner: hex('#262d25'),   // near-black green inside of the panels (shows under the back hem)
    patch: hex('#9ca870'),   // sage-olive velcro patches on the shoulder straps
    olive: hex('#65724f'),   // olive webbing: closure strap, groin tabs, belt loops, strap keepers
    tab: hex('#bdb67a'),     // the collar tab's ground (camo's khaki tone, kept flat)
  };
  // Mountain Flora: blotches stretched sideways (x / z sampled at about half the rate of y), sampled on the 2x art grid
  // at a scale that keeps every blotch one to three pixels across (no single-cell specks). Like the original, more than
  // half of it is warm: big rust-brown blotches with darker cores, each ringed by a lighter tan halo, on a pale
  // yellow-green ground with a few sage-green patches (front: about base 34%, tan 19%, brown 25%, dark 10%, sage 11%)
  const FL = { base: hex('#d3d08f'), sage: hex('#b3b375'), tan: hex('#c3a36a'), brown: hex('#a28052'), dark: hex('#7a6341') };
  const camo = p => {
    const q = snap(p, 0.5);
    const f = (sx, sy, o) => fbm(q[0] * sx + o, q[1] * sy - o * 0.7, q[2] * sx + o * 0.4, 2);
    const br = f(0.42, 0.7, 53), sg = f(0.4, 0.75, 61);
    if (br > 0.66) return FL.dark;
    if (br > 0.545) return FL.brown;
    if (br > 0.485) return FL.tan;
    if (sg > 0.64) return FL.sage;
    return FL.base;
  };
  // top of the collar roll under the chin: a thin dark binding along the edge, then the lit camo fold (the grey lining
  // only shows on the roll's top and inner faces, as in the original, where it is seen inside the cup)
  const rollTop = c => (c.p[1] < 0 ? mul(C.bind, 1.15 * G(c, 0.05)) : fab(c, 1.08));

  // ---------- shared build (identical in b6b23_1_digital_flora) ----------
  const fab = (c, k = 1) => mul(camo(c.p), k * G(c, 0.06));
  const lit = c => 1.02 - 0.009 * Math.max(0, c.p[1]);                          // soft top light
  const lining = (c, col = C.inner) => mul(col, G(c, 0.08));
  const bind = (c, k = 1) => mul(C.bind, k * G(c, 0.06));
  const bound = (c, t) => mix(fab(c), C.bind, t);
  const flush = (mat, face) => c => (c.face === face ? null : mat(c));

  // front panel: plain camo, bound side edges; a soft shadow row right above the proud lower flap
  const front = c => {
    if (c.face === 'back') return lining(c);
    if (c.face === 'bottom') return bind(c);
    if (isSide(c)) return bound(c, 0.6);                                        // bound armhole / side edge
    if (c.face === 'top') return mul(fab(c), 0.9);
    let col = fab(c, lit(c));
    if (Math.abs(c.p[0]) > 4) col = mix(col, C.bind, 0.45);
    if (c.p[1] > 9 && c.p[1] < 9.5 && c.p[0] > -3.5) col = mul(col, 0.86);
    return col;
  };
  // the vest runs well below the belt in the original (body to about 5/6 of the length, groin flap below)
  B.push(box('body', [-4.5, 1.5, -3.25], [4.5, 13.5, -2.25], front, { tag: 'front panel' }));
  // lower flap below the seam: proud, lit top fold, bound hem; its wearer's-right edge stops short of the corner
  const flap = c => {
    if (c.face === 'top') return mul(fab(c), 1.06);
    if (c.face === 'bottom') return bind(c, 0.9);
    if (isSide(c)) return bound(c, 0.5);
    let col = fab(c, lit(c));
    if (c.ev < px) col = mul(col, 1.08);
    else if (c.ev > c.fh - px) col = mix(col, C.bind, 0.5);
    else if (c.eu < px || c.eu > c.fw - px) col = mix(col, C.bind, 0.4);
    return col;
  };
  B.push(box('body', [-3.5, 9.5, -3.75], [4.5, 13.5, -3.25], flush(flap, 'back'), { tag: 'lower front flap' }));
  // olive closure strap down the wearer's-right front corner, beside the flap's inset edge
  const strap = c => {
    const col = mul(C.olive, G(c, 0.06));
    if (c.face === 'top') return mul(col, 1.15);
    if (c.face === 'bottom') return mul(col, 0.7);
    if (!isPanel(c)) return mul(col, 0.8);
    return mul(col, c.eu < px ? 1.1 : 0.94);                                   // lit outer column, shaded inner one
  };
  B.push(box('body', [-4.5, 8.5, -3.5], [-3.5, 13.5, -3.25], flush(strap, 'back'), { tag: 'side closure strap' }));

  // ---------- collar: the tall cup is hidden by the MC head, so it shows as a roll under the chin, low side pieces beside
  // the head and a high back band behind the neck with the X-stitched tab ----------
  const collarF = c => {
    if (c.face === 'back' || c.face === 'top') return lining(c, C.lining);
    if (c.face === 'bottom') return mul(fab(c), 0.55);
    if (c.p[1] < 0.5) return rollTop(c);                                        // bound, rolled top edge
    let col = fab(c, 0.94);
    if (isSide(c)) return mul(col, 0.8);
    if (c.p[1] > 1.25) col = mul(col, 0.84);                                    // seam onto the front panel
    return col;
  };
  B.push(box('body', [-2, -0.5, -3.75], [2, 1.75, -2.25], collarF, { tag: 'collar front roll' }));
  const collarB = c => {
    if (c.face === 'front') return lining(c, C.lining);
    if (c.face === 'bottom') return mul(fab(c), 0.55);
    if (c.face === 'top') return c.p[2] < 4.5 ? lining(c, C.lining) : mul(fab(c), 1.1);
    let col = fab(c, 0.95);
    if (c.p[1] < -1) col = mul(col, 1.1);                                       // rolled top edge
    else if (c.p[1] > 0.75) col = mul(col, 0.84);
    return col;
  };
  B.push(box('body', [-4.75, -1.5, 3.25], [4.75, 1.25, 4.75], collarB, { tag: 'collar back' }));
  // the ring carries on forward beside the head, behind the shoulder straps (the pads hide its lower part)
  for (const s of [-1, 1]) {
    const inner = s < 0 ? 'left' : 'right';
    B.push(box('body', s < 0 ? [-4.75, -1.5, 0.5] : [4.25, -1.5, 0.5], s < 0 ? [-4.25, -0.5, 3.25] : [4.75, -0.5, 3.25], c => {
      if (c.face === inner || c.face === 'bottom') return lining(c, C.lining);
      if (c.face === 'top') return mul(fab(c), 1.1);
      if (c.face === 'front') return mul(fab(c), 0.82);
      return fab(c, c.p[1] < -1 ? 1.05 : 0.95);
    }, { tag: 'collar side' }));
  }
  // (a flat, slightly lighter camo ground so the dark X stitch reads through the busy pattern; an odd 5 x 5 cell grid, so
  // the two diagonals cross in one centre cell and read as an X, not as a dark blob)
  const xtab = c => {
    const col = mul(C.tab, G(c, 0.05));
    if (c.face !== 'back') return mul(col, 0.8);
    const n = Math.round(c.fw / px), i = Math.min(n - 1, Math.floor(c.eu / px)), j = Math.min(n - 1, Math.floor(c.ev / px));
    return i === j || i === n - 1 - j ? mix(col, C.bind, 0.55) : col;         // X stitch
  };
  B.push(box('body', [-4.25, -1.25, 4.75], [-1.75, 1.25, 5], flush(xtab, 'front'), { tag: 'collar X-stitched tab' }));

  // ---------- shoulder straps: front risers beside the collar with the raised olive patches; a thick pad on top of each
  // shoulder, reaching out over the arm (the patch runs over its top and front end) with a small keeper loop at the crest.
  // The pad is a full pixel thick and reaches down to the top of a slim sleeve (on a wide arm its lower half sits inside
  // the sleeve), so it never hovers over the arm; its front / back ends stay a little behind the riser and the back panel
  const riser = c => {
    if (c.face === 'back') return lining(c);
    if (c.face === 'bottom') return mul(fab(c), 0.6);
    if (isSide(c)) return bound(c, 0.55);
    return fab(c);
  };
  const patch = c => {
    const col = mul(C.patch, G(c, 0.05));
    if (c.face === 'top') return mul(col, 1.12);
    if (c.face === 'bottom') return mul(col, 0.65);
    if (isSide(c)) return mul(col, 0.8);
    if (c.ev < px) return mul(col, 1.08);                                       // lit rim
    if (c.ev > c.fh - px) return mul(col, 0.82);
    if (c.eu < px || c.eu > c.fw - px) return mul(col, 0.92);
    return col;
  };
  for (const s of [-1, 1]) {
    const X = (a, b) => (s < 0 ? [-b, -a] : [a, b]);
    const [r0, r1] = X(2, 4.5), [p0, p1] = X(2.5, 4.5), [h0, h1] = X(4, 5.5);
    B.push(box('body', [r0, -0.25, -3.25], [r1, 1.5, -2.25], riser, { tag: 'shoulder strap riser' }));
    B.push(box('body', [p0, 0, -3.5], [p1, 2, -3.25], flush(patch, 'back'), { tag: 'velcro patch' }));
    const outer = s < 0 ? 'right' : 'left';
    B.push(box('body', [h0, -0.75, -3], [h1, 0.25, 3], c => {
      if (c.face === 'bottom') return lining(c);
      if (c.face === 'top') return c.p[2] < 0.25 ? patch(c) : mul(fab(c), 1.05);  // the patch runs up to the crest
      const crown = c.p[1] < -0.25;                                             // upper row: padded crown, lower row: bound edge
      if (c.face === 'front') return crown ? mul(C.patch, 0.92 * G(c, 0.05)) : bound(c, 0.55);   // the patch wraps over the strap end
      if (c.face === outer || c.face === 'back') return crown ? fab(c, 1.04) : bound(c, 0.55);
      return mul(fab(c), 0.9);
    }, { tag: 'shoulder strap pad' }));
    // small olive keeper loop standing on the crest of the strap, at the upper end of the patch (just in front of the
    // collar side piece)
    const [k0, k1] = X(4.25, 5.25);
    B.push(box('body', [k0, -1.25, 0.25], [k1, -0.75, 0.5], c => {
      if (c.face === 'bottom') return null;
      const col = mul(C.olive, G(c, 0.05));
      if (c.face === 'top') return mul(col, 1.2);
      if (c.face === 'front') return mul(col, 1.05);
      return mul(col, c.face === 'back' ? 0.85 : 0.8);
    }, { tag: 'strap keeper loop' }));
  }

  // ---------- back panel (a little longer than the front) ----------
  const back = c => {
    if (c.face === 'front') return lining(c);
    if (c.face === 'bottom') return bind(c);
    if (isSide(c)) return bound(c, 0.6);
    if (c.face === 'top') return bound(c, 0.35);
    let col = fab(c, lit(c));
    if (Math.abs(c.p[0]) > 4) col = mix(col, C.bind, 0.45);
    if (c.p[1] >= 9.5 && c.p[1] < 10) col = mul(col, 0.86);                      // seam, level with the front one
    return col;
  };
  B.push(box('body', [-4.5, 0, 2.25], [4.5, 14, 3.25], back, { tag: 'back panel' }));
  // its lower part hangs below the front hem: hinged on the panel's outer lower edge and turned out a little behind the
  // seat, so the legs clear it at rest and in a normal step
  const tail = c => {
    if (c.face === 'front' || c.face === 'top') return lining(c);
    if (c.face === 'bottom') return bind(c);
    if (isSide(c)) return bound(c, 0.6);
    let col = fab(c, lit(c));
    if (c.ev > c.fh - px) col = mix(col, C.bind, 0.55);
    else if (Math.abs(c.p[0]) > 4) col = mix(col, C.bind, 0.45);
    return col;
  };
  B.push(box('body', [-4.5, 14, 2.25], [4.5, 15.5, 3.25], tail, { tag: 'back panel lower part', rot: [12, 0, 0], pivot: [0, 14, 3.25] }));

  // ---------- side bands under the open armholes (camo, olive belt loops, bound lower edge) ----------
  for (const s of [-1, 1]) {
    const outer = s < 0 ? 'right' : 'left';
    B.push(box('body', s < 0 ? [-4.75, 9.5, -2.25] : [4.25, 9.5, -2.25], s < 0 ? [-4.25, 13.5, 2.25] : [4.75, 13.5, 2.25], c => {
      if (c.face === 'top') return bind(c, 1.1);
      if (c.face === 'bottom') return bind(c);
      if (c.face !== outer) return mul(fab(c), 0.8);
      const z = c.p[2], y = c.p[1];
      if (y > 13) return bound(c, 0.5);
      if (y >= 11 && (Math.abs(z + 1) < 0.5 || Math.abs(z - 1) < 0.5)) return mul(C.olive, G(c, 0.05) * (y < 11.5 ? 1.18 : 0.78));   // lit fold, shaded strap
      return fab(c, 0.95);
    }, { tag: 'side band' }));
  }

  // ---------- groin flap: narrower than the vest, hanging from two olive tabs just under the lower flap; it lies close
  // in front of the thighs (its top tucked into the front panel), the tabs come out from under the proud lower flap ----------
  const GX = 2.75, GT = 13.25, GB = 16.5;
  const groin = c => {
    const ax = Math.abs(c.p[0]), y = c.p[1];
    const xx = isSide(c) ? GX - px / 2 : ax, yy = c.face === 'bottom' ? GB - px / 2 : y;
    if ((GX - xx) + (GB - yy) < 0.9) return null;                                // small rounded bottom corners
    if (c.face === 'back' || c.face === 'top') return lining(c);
    if (c.face === 'bottom') return bind(c);
    if (isSide(c)) return bound(c, 0.6);
    let col = fab(c, 0.96);
    if (y > GB - px || (GX - ax) + (GB - y) < 1.4) col = mix(col, C.bind, 0.55);   // bound lower edge and corners
    else if (ax > GX - px) col = mix(col, C.bind, 0.45);
    else if (y < GT + px) col = mul(col, 0.84);                                  // shadow under the lower flap
    return col;
  };
  B.push(box('body', [-GX, GT, -3], [GX, GB, -2.5], groin, { tag: 'groin flap' }));
  const gtab = c => {
    const col = mul(C.olive, G(c, 0.05));
    if (c.face === 'top') return mul(col, 0.7);
    if (c.face === 'bottom') return mul(col, 0.75);
    if (!isPanel(c)) return mul(col, 0.8);
    return mul(col, c.ev < px ? 1.12 : 0.92);
  };
  for (const s of [-1, 1]) {
    const x = s < 0 ? [-2.5, -1.5] : [1.5, 2.5];
    B.push(box('body', [x[0], 13.5, -3.25], [x[1], 14.5, -3], c => (c.face === 'back' || c.face === 'top' ? null : gtab(c)), { tag: 'groin tab' }));
  }
  return { S: K.S, px: K.px, cell: K.cell, boxes: B };
};
