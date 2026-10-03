// ================= NPP KlASS Korund-VM body armor (Black) — EFT reference (tarkov.dev 5f5f41476bdad616ad46d631) =================
// Heavy Russian soft-shell vest in black ripstop (dark charcoal, a little cool in the shadows). Reference render: very
// tall padded stand collar (highest behind the neck, a lower padded roll in front, a grey loop-velcro patch on its
// outer side); broad shoulder flaps whose front ends lie on the chest (the lightest fabric, bound lower edge); on each a
// dark webbing strap running down through a silver cam buckle at the flap's lower edge, then angling in to a stitched
// tab on the chest; a slightly lighter padded, stitched band across the upper chest with the plate-pocket zipper under it (two silver pulls at the
// wearer's-right end); big smooth padded front panel with deep armholes (dark quilted pads inside); from the waist a
// proud lower front flap with a stitched border that wraps round the sides (its side edge slants toward the front);
// side panels with a vertical seam and a narrow grey webbing tab; a wide groin protector with a rounded bottom hung
// from under the flap by two grey tabs; a longer back panel that reaches below the front hem.
// No shoulder armour, no pouches.
ARMORS.korund_vm_black = function (mode) {
  const K = kit('M');                                  // always the 2x style
  const { G, isPanel, isSide, px } = K;
  const B = [];
  const C = {
    // (sampled off the render: shoulder flaps the lightest, lower flap and groin clearly darker than the chest)
    fab: hex('#3f4144'), sh: hex('#4e5053'), flap: hex('#35373a'), groin: hex('#333538'), collar: hex('#35373a'),
    web: hex('#515356'), strap: hex('#45474a'), tab: hex('#5a5d60'), loop: hex('#54565a'), lining: hex('#191b1d'),
    pad: hex('#2a2e31'), silver: hex('#b1b5b7'), zip: hex('#c9cccd'),
  };
  const md = (v, m) => ((v % m) + m) % m;
  // ripstop: very faint low-frequency tone drift + per-cell grain (kept low: black fabric shows noise quickly)
  const fab = (c, base, k = 1, amt = 0.06) => {
    const q = snap(c.p, px);
    const n = fbm(q[0] * 0.35 + 5.3, q[1] * 0.3 + 2.1, q[2] * 0.35 + 8.7);
    return mul(base, k * (0.95 + 0.1 * n) * G(c, amt));
  };
  const lin = (c, k = 1) => mul(C.lining, k * G(c, 0.08));
  const nU = c => Math.max(1, Math.round(c.fw / px)), nV = c => Math.max(1, Math.round(c.fh / px));
  const iU = c => Math.min(nU(c) - 1, Math.floor(c.eu / px + 1e-6)), iV = c => Math.min(nV(c) - 1, Math.floor(c.ev / px + 1e-6));
  // faces pressed flat against another surface are never meant to be seen
  const flush = (mat, ...faces) => c => (faces.includes(c.face) ? null : mat(c));

  // ---------- layout (y down) ----------
  // (measured down the original's centre line: chest top -> flap seam -> flap bottom -> groin bottom ~ 172 : 123 : 107)
  const Y = { arm: 5.5, band0: 2, band1: 3, zip: 3.5, seam: 7.5, hem: 11.5, flapB: 12, groinB: 16, tail: 14.5, tab0: 9.5, tab1: 11 };

  // ---------- shell: front panel, sides (armholes above Y.arm), back panel ----------
  const chest = c => {
    const x = c.p[0], y = c.p[1], ax = Math.abs(x);
    let k = 1.08 - 0.014 * y;                                                  // padded panel lit from above
    k *= 1 - 0.12 * clamp01((ax - 2.75) / 1.5);                                // rolls away toward the armholes
    k *= 1 + 0.06 * (1 - clamp01(Math.hypot(x + 0.75, (y - 4.5) * 1.3) / 3.5)); // soft sheen on the padded chest
    let col = fab(c, C.fab, k);
    if (y >= Y.band1 && y < Y.zip && ax < 4) col = mul(col, 0.7);             // plate-pocket zipper under the band
    return col;
  };
  // armhole: dark quilted pads of the inner lining (2 px pads, soft seams)
  const quilt = c => {
    const s = md(c.p[1] - 0.5, 2) < px || md(c.p[2] + 1, 2) < px;
    return mul(C.pad, (s ? 0.72 : 1) * G(c, 0.08));
  };
  const side = c => {
    const y = c.p[1], z = c.p[2];
    let col = fab(c, C.fab, 0.92);
    if (y < Y.arm + px) return mul(col, 1.1);                                 // bound armhole bottom
    if (z > 0.5 && z < 1.5 && y > Y.tab0 && y < Y.tab1) {                     // narrow grey webbing tab (lower half of the flap's height)
      const t = mul(C.tab, G(c, 0.05));
      return y < Y.tab0 + px ? mul(t, 1.08) : y > Y.tab1 - px ? mul(t, 0.82) : t;
    }
    if (Math.abs(z + 0.25) < 0.1) col = mul(col, 0.8);                        // vertical side seam
    if (y > Y.hem - px) col = mul(col, 0.78);                                 // hem
    return col;
  };
  const backP = c => {
    const x = c.p[0], y = c.p[1], ax = Math.abs(x);
    let col = fab(c, C.fab, (1.04 - 0.01 * y) * (1 - 0.1 * clamp01((ax - 3) / 1.5)));
    if (Math.abs(y - (Y.seam + 0.25)) < 0.1) col = mul(col, 0.8);             // waist seam (continues the front flap line)
    return col;
  };
  const shell = c => {
    const y = c.p[1], z = c.p[2];
    if (c.face === 'front') return chest(c);
    if (c.face === 'back') return backP(c);
    if (isSide(c)) {
      if (y < Y.arm && Math.abs(z) < 2.5) return quilt(c);                    // deep armhole: padded lining
      if (y < Y.arm) return fab(c, C.fab, 0.9);                               // front / back edges of the armhole
      return side(c);
    }
    if (c.face === 'bottom') return lin(c, 0.9);
    return fab(c, C.fab, 0.8);                                                // top (inside the head)
  };
  B.push(box('body', [-4.5, -0.5, -3], [4.5, Y.hem, 3], shell, { tag: 'vest shell' }));

  // ---------- stand collar: low padded roll in front, sides beside the head, a higher roll behind the neck ----------
  // (the original's collar stands far above the shoulders, but in MC the head sweeps through anything above the
  // shoulder line when it turns, so the collar is kept to ~1.5-2 px above it. Like the original's ring it rises toward
  // the back: each side rim starts just behind the shoulder flap, a little above it, and climbs to the back roll's top)
  const collarFab = (c, k = 1) => fab(c, C.collar, k);
  const RIM = { t: 0.75, top: -1, bot: 0.5, z0: -2.5, z1: 5.25, tilt: 8, lean: 4 };
  const roll = c => {
    if (c.face === 'top' || c.face === 'back') return lin(c);
    if (c.face === 'bottom') return collarFab(c, 0.6);
    if (c.face !== 'front') return collarFab(c, 0.8);
    const j = iV(c), n = nV(c);
    return collarFab(c, j === n - 1 ? 0.8 : j === n - 2 ? 1.12 : 1);         // lit roll, darker underside
  };
  B.push(box('body', [-1.75, -0.5, -3.75], [1.75, 1.5, -3], flush(roll, 'back'), { tag: 'collar front roll' }));
  for (const s of [-1, 1]) {
    const inn = s < 0 ? 'left' : 'right';
    const cSide = c => {
      const y = c.p[1], z = c.p[2];
      if (c.face === inn) return lin(c, 1.15);
      if (c.face === 'bottom') return lin(c);
      if (c.face === 'top') return collarFab(c, 1.2);                          // rolled rim
      if (isPanel(c)) return collarFab(c, 0.85);                               // ends
      if (y < RIM.top + px) return collarFab(c, 1.2);                         // rolled top edge
      if (y < RIM.top + 2 * px && z > -2 && z < 0.5) return mul(C.loop, G(c, 0.05));   // grey loop-velcro patch
      return collarFab(c, y > RIM.top + 2 * px ? 0.85 : 1);
    };
    // sloped rim: pivot at its front-bottom edge, tilted so the back end climbs to the back roll's top, leaning out a
    // little at the top (clear of a hat layer)
    const xi = s * 4.5, xo = s * (4.5 + RIM.t);
    B.push(box('body', [Math.min(xi, xo), RIM.top, RIM.z0], [Math.max(xi, xo), RIM.bot, RIM.z1], cSide,
      { tag: 'collar side', rot: [RIM.tilt, 0, s * RIM.lean], pivot: [xi, RIM.bot, RIM.z0] }));
  }
  const collarBack = c => {
    const y = c.p[1];
    if (c.face === 'front') return lin(c, 1.15);
    if (c.face === 'bottom') return collarFab(c, 0.6);
    if (c.face === 'top') return c.p[2] < 4.5 ? lin(c, 1.15) : collarFab(c, 1.2);
    if (isSide(c)) return collarFab(c, 0.85);
    if (y < -2 + px) return collarFab(c, 1.2);                                // rolled rim
    if (y > 1 - px) return collarFab(c, 0.8);                                 // tucks into the back panel
    return collarFab(c);
  };
  B.push(box('body', [-4.5, -2, 3], [4.5, 1, 5], collarBack, { tag: 'collar back' }));

  // ---------- shoulder flaps (front ends on the chest, back ends under the collar) ----------
  const shFlap = outer => c => {
    let col = fab(c, C.sh);
    if (c.face === (outer === 'front' ? 'back' : 'front')) return lin(c);
    if (c.face === 'bottom') return mul(col, 0.62);
    if (c.face === 'top') return mul(col, 1.05);
    if (isSide(c)) return mul(col, 0.8);
    const j = iV(c), n = nV(c);
    if (outer === 'back' && Math.abs(c.p[0]) > 2 && Math.abs(c.p[0]) < 3) col = mul(fab(c, C.strap), iU(c) % 2 ? 0.92 : 1.06);   // strap running over
    return j === n - 1 ? mul(col, 0.78) : col;                                // bound lower edge
  };
  const web = (base, end = false) => c => {
    let col = fab(c, base, 1, 0.05);
    if (!isPanel(c)) return mul(col, c.face === 'top' ? 1.1 : 0.78);
    const i = iU(c), n = nU(c);
    col = mul(col, i === 0 ? 1.08 : i === n - 1 ? 0.92 : 1);
    if (end && c.ev > c.fh - px) col = mul(col, 0.8);                          // stitched end of the tab
    return col;
  };
  const buckle = c => {
    if (c.face !== 'front') return mul(C.silver, c.face === 'top' ? 1.15 : 0.7);
    const i = iU(c), j = iV(c), n = nU(c);
    if (j === 1 && i > 0 && i < n - 1) return mul(C.strap, 0.8);               // strap runs through the frame
    return mul(C.silver, j === 0 ? 1.08 : 0.9);
  };
  for (const s of [-1, 1]) {
    const X = (a, b) => (s < 0 ? [-b, -a] : [a, b]);
    const [f0, f1] = X(1.75, 4.75);
    B.push(box('body', [f0, -0.5, -3.5], [f1, 2, -3], shFlap('front'), { tag: 'shoulder flap (front)' }));
    B.push(box('body', [f0, 0, 3], [f1, 2, 3.5], shFlap('back'), { tag: 'shoulder flap (back)' }));
    const [s0, s1] = X(2, 3);
    B.push(box('body', [s0, -0.5, -3.75], [s1, 1, -3.5], flush(web(C.strap), 'back'), { tag: 'shoulder strap' }));
    const [b0, b1] = X(1.75, 3.25);
    B.push(box('body', [b0, 1, -4], [b1, 2, -3.5], flush(buckle, 'back'), { tag: 'cam buckle' }));
    // below the buckle the tail angles in toward the chest centre (the original's tabs end a little inside the buckles)
    B.push(box('body', [s0, 2, -3.5], [s1, 4, -3], flush(web(C.strap, true), 'back'), { tag: 'strap tail + tab', rot: [0, 0, s * 10], pivot: [s * 2.5, 2, -3.25] }));
  }

  // ---------- upper chest: webbing band, zipper pulls ----------
  const band = c => {
    const col = fab(c, C.web, 1, 0.05);
    if (c.face === 'top') return mul(col, 1.12);
    if (c.face === 'bottom') return mul(col, 0.6);
    if (!isPanel(c)) return mul(col, 0.8);
    return mul(col, iV(c) === 0 ? 1.1 : 0.9);
  };
  B.push(box('body', [-4.25, Y.band0, -3.25], [4.25, Y.band1, -3], flush(band, 'back'), { tag: 'chest band' }));
  // two small silver pulls side by side just outside the wearer's-right strap tail (the dark zipper shows between them)
  const pull = flush(c => mul(C.zip, c.face === 'top' ? 1.1 : c.face === 'front' ? 1 : 0.7), 'back');
  for (const x0 of [-4.25, -3.25]) B.push(box('body', [x0, Y.band1, -3.25], [x0 + px, Y.zip, -3], pull, { tag: 'zipper pull' }));

  // ---------- lower front flap (proud, stitched border) + its wraps round the sides ----------
  const lowFlap = c => {
    const y = c.p[1], ax = Math.abs(c.p[0]);
    const col = fab(c, C.flap, (1.04 - 0.012 * (y - Y.seam)) * (1 - 0.1 * clamp01((ax - 3) / 1.5)));
    if (c.face === 'top') return mul(col, 1.15);
    if (c.face === 'bottom') return mul(col, 0.62);
    if (c.face === 'back') return lin(c);
    if (isSide(c)) return mul(col, 0.8);
    const j = iV(c), n = nV(c);
    if (j === 0) return mul(col, 1.12);                                       // lit folded top edge
    if (j === 1) return mul(col, 0.9);                                        // stitched seam under it
    if (j === n - 1) return mul(col, 0.74);                                   // bound lower edge
    return col;
  };
  B.push(box('body', [-4.5, Y.seam, -3.25], [4.5, Y.flapB, -3], lowFlap, { tag: 'lower front flap' }));
  const WZ0 = -0.75, WZ1 = -2.25;                                             // slanted back edge of the wrap (top -> bottom)
  const zE = y => WZ0 + (WZ1 - WZ0) * clamp01((y - Y.seam) / (Y.flapB - Y.seam));
  for (const s of [-1, 1]) {
    const out = s < 0 ? 'right' : 'left', inn = s < 0 ? 'left' : 'right';
    const wrap = c => {
      const y = c.p[1], z = c.p[2];
      if (c.face === inn) return null;                                        // against the shell side
      const ye = c.face === 'bottom' ? Y.flapB - px / 2 : y;
      if (z > zE(ye) + 1e-6) return null;                                     // slanted back edge
      const col = fab(c, C.flap, 0.95);
      if (c.face === 'top') return mul(col, 1.15);
      if (c.face === 'bottom') return mul(col, 0.62);
      if (c.face !== out) return mul(col, 0.8);
      if (z > zE(y) - px || y > Y.flapB - px) return mul(col, 0.74);         // bound edges
      if (y < Y.seam + px) return mul(col, 1.1);
      return col;
    };
    B.push(box('body', s < 0 ? [-4.75, Y.seam, -3.25] : [4.5, Y.seam, -3.25], s < 0 ? [-4.5, Y.flapB, WZ0] : [4.75, Y.flapB, WZ0], wrap, { tag: 'flap side wrap' }));
  }

  // ---------- groin protector: wide, rounded bottom, hung from under the flap by two grey tabs ----------
  const GW0 = 3.5, GW1 = 3, GT = Y.hem, GB = Y.groinB, R = 1.5;          // nearly as wide as the flap above it
  const gHW = y => GW0 - (GW0 - GW1) * clamp01((y - GT) / (GB - GT));
  const inG = (ax, y) => {
    const ds = gHW(y) - ax, db = GB - y;
    return db < R && ds < R ? R - Math.hypot(R - db, R - ds) : Math.min(ds, db);
  };
  const groin = c => {
    const ax = Math.abs(c.p[0]), y = Math.min(c.p[1], GB - px / 2);
    if (c.face === 'top') return lin(c);
    const d = inG(isSide(c) ? ax - px / 2 : ax, y);
    if (d <= 0) return null;
    if (c.face === 'back') return lin(c);
    let col = fab(c, C.groin, 1.02 - 0.01 * (y - GT));
    if (c.face !== 'front') return mul(col, 0.75);
    if (d < px) return mul(col, 0.76);                                        // bound outline
    if (y < Y.flapB + px) col = mul(col, 0.78);                               // shadow under the proud flap
    return col;
  };
  B.push(box('body', [-GW0, GT, -3], [GW0, GB, -2.5], groin, { tag: 'groin protector' }));
  const tab = c => {
    const t = mul(C.tab, G(c, 0.05));
    if (!isPanel(c)) return mul(t, c.face === 'bottom' ? 0.6 : 0.8);
    return iV(c) === 0 ? mul(t, 0.92) : mul(t, 1.02);
  };
  for (const s of [-1, 1]) {
    const x = s < 0 ? [-3, -2] : [2, 3];
    B.push(box('body', [x[0], Y.flapB, -3.25], [x[1], Y.flapB + 1, -3], flush(tab, 'back'), { tag: 'groin tab' }));
  }

  // ---------- longer back panel: continues below the hem and hangs straight down over the seat (a hair of tilt only) ----------
  const tailCut = (ax, y) => { const r = 1.5, ds = 4.5 - ax, db = Y.tail - y; return db < r && ds < r && Math.hypot(r - db, r - ds) > r; };
  const tail = c => {
    const ax = Math.abs(c.p[0]), y = c.p[1];
    const xe = isSide(c) ? ax - px / 2 : ax, ye = c.face === 'bottom' ? y - px / 2 : y;
    if (tailCut(xe, ye)) return null;
    if (c.face === 'front' || c.face === 'top') return lin(c);
    const col = fab(c, C.fab, 0.95 - 0.01 * (y - Y.hem));
    if (c.face === 'bottom') return mul(col, 0.7);
    if (isSide(c)) return mul(col, 0.8);
    if (tailCut(ax, y + px) || tailCut(ax + px, y) || y > Y.tail - px) return mul(col, 0.76);   // bound lower edge
    return col;
  };
  B.push(box('body', [-4.5, Y.hem, 2.5], [4.5, Y.tail, 3], tail, { tag: 'back panel tail', rot: [3, 0, 0], pivot: [0, Y.hem, 3] }));

  return { S: K.S, px: K.px, cell: K.cell, boxes: B };
};
