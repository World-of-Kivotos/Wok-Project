// ================= 6B45 armored rig (Medic, EMR) — EFT reference (tarkov.dev 68948aebd8f2b85fb705e2b0) =================
// Russian Ratnik body-armour vest (ceramic plates, class V in the mod) in EMR digital camo (tan-khaki ground, olive and dark-green clusters, a few brown bits).
// Both 6B45 rigs share the carrier: very tall padded ring collar, the vest's dominant feature (in MC the head hides its
// middle: a rolled front edge under the chin, tall side walls rising out of the shoulder rolls beside the head and a high
// back band behind the neck, so the ring still reads from the front); thick padded shoulder rolls that run over the shoulder and
// end in a rounded, open tube end on the upper chest; two light grey-green webbing strips across the chest; big open
// armholes with a black lining; a cummerbund with MOLLE rows and a light waist strap that runs through a dark
// side-release buckle on each front corner and across the lower front (the brightest band there); a proud lower belly
// section with a thick rolled edge and rounded corners, its top roll right under the waist strap. Scaled by its width,
// the vest runs well below the belt: front, sides and back all end on one line, about a pixel and a half under the hips.
// The EMR pouches' U flaps are the same green EMR as their pockets; a dark binding outlines them (it must stay darker
// than any camo cell or the U's vanish at Minecraft size, so the lids get a calmer camo). The double mag pouch is the
// darkest pouch on the render, about two thirds as bright as the chest.
// General Purpose loadout (render 68948ad72c87773b9f06d73f): a black tourniquet lying across the upper webbing strip
// (red tag on the wearer's left end) under a light grey hanging tab; an EMR radio pouch on the wearer's left upper chest
// with a black whip antenna rising out of it; a black knife grip beside it at the front corner (its sheath runs down
// behind the mag pouch); a plain grey-green IFAK pouch, a little darker than the vest fabric yet the lightest pouch
// (carry loop, tan buckle, centre strap, blue-grey red-cross patch at its lower wearer's-left corner) on the wearer's
// right lower front; a big EMR double mag pouch with two long
// U-shaped flaps and pull tabs on the wearer's left lower front; a small EMR flap pouch on the front of each side of the
// cummerbund (a black cord hangs down the wearer's-left one); a short diagonal webbing strap inside the wearer's-right
// shoulder roll (the Medic shows one on each side).
// Medic loadout (render 68948aebd8f2b85fb705e2b0): a tall EMR single mag pouch on the wearer's right; a big EMR IFAK in
// the middle (carry loop, dark buckle on a centre strap, red-cross patch at its lower wearer's-left corner) over two
// small flap pouches; at the wearer's left front corner a strapped radio pouch with the black radio and its whip antenna
// sticking out, a pink rubber tourniquet clipped to its outer side; no pouches on the cummerbund.
// The render shows no back: the back panel carries the same two webbing strips as the front.
// Shares its code with armors/b6b45_general.js (VARIANT = 'general'): the two must be identical apart from the first
// header line, this comment and VARIANT (edit one, copy the change to the other). THIS file is the current one (waist
// strap through the front-corner buckles and across the lower front, apron AT 11.5, new apron top). As of
// 2026-10-04 armors/b6b45_general.js still has the older waist area; its owner should drop in
// scratch-pol3-b6b45_medic/medic_merged.general-with-strap.js (or run merge.mjs there if General has changed since).
ARMORS.b6b45_medic = function (mode) {
  const VARIANT = 'medic';
  const K = kit('M');                          // always the 2x style, whatever mode is asked for
  const { G, isPanel, isSide, px } = K;
  const B = [];

  // ---------- palette (sampled off the renders' lit faces) ----------
  // (at Minecraft size the original's fine EMR averages out to a fairly even olive-khaki, so the clusters stay soft and
  // the pouch outlines / shadows carry the shapes)
  // (the dark-green layer sits a little closer to the ground than the raw sample, like the render's fine, low-contrast EMR)
  const EMR = { base: hex('#666144'), light: hex('#79734f'), green: hex('#58623f'), dark: hex('#4e533a'), brown: hex('#5d5339') };
  const C = {
    web: hex('#737565'),     // light grey-green webbing strips / MOLLE / waist strap
    bind: hex('#2f3b32'),    // dark teal-green binding along the rolled edges
    lbind: hex('#26302a'),   // the darker binding that outlines the pouch lids' U's (darker than any camo cell)
    lin: hex('#1c1c1e'),     // black armhole lining
    clin: hex('#303628'),    // dark green inside of the collar
    ifak: hex('#5c614f'),    // plain grey-green IFAK (General): a little darker than the carrier fabric and greener, yet the lightest pouch
    rstrap: hex('#6e674a'),  // khaki-olive straps round the Medic radio pouch (warmer than the grey-green chest webbing)
    rpouch: hex('#363125'),  // plain dark olive-brown Medic radio pouch (no camo): the darkest pouch on the render
    loop: hex('#5c5e4b'),    // olive webbing carry loop on the IFAK
    strap: hex('#484c3e'),   // darker olive centre strap on the General IFAK (about 0.78x the pouch)
    teal: hex('#4f7f7c'),    // dull teal accents on the pink tourniquet
    tan: hex('#aaa585'),     // tan side-release buckle (General IFAK)
    dbuck: hex('#3a3d3b'),   // dark grey buckle (Medic IFAK)
    sbuck: hex('#434a40'),   // dark olive plastic waist-strap buckles
    patch: hex('#65797c'), cross: hex('#a0302b'),
    tq: hex('#262728'), tql: hex('#403d38'), red: hex('#a3302a'), gtab: hex('#9a9fa0'),
    ant: hex('#2b2c2b'), knife: hex('#25282a'), radio: hex('#2b2d2f'),
    pink: hex('#b26875'),    // pink rubber tourniquet on the Medic radio pouch
    tab: hex('#5d5e4a'),     // grey-olive pull tabs (a little lighter than the dark mag pouch they hang on)
  };
  // EMR: the original's pixels are much finer than a Minecraft pixel, so the pattern is built from two scales on the 2x
  // art grid: soft macro regions (about 2 px) decide where the olive / dark-green clusters gather, a micro noise breaks
  // them into small clumps of half-pixel cells (never a one-cell checker)
  const camo = p => {
    const q = snap(p, 0.5);
    const m = fbm(q[0] * 0.42 + 3, q[1] * 0.42 + 7, q[2] * 0.42 + 1, 2);
    const n = vnoise(q[0] * 1.15 + 11, q[1] * 1.15 + 5, q[2] * 1.15 + 9), n2 = vnoise(q[0] * 1.25 + 31, q[1] * 1.25 + 13, q[2] * 1.25 + 2);
    if (n2 > 0.84) return EMR.light;
    if (m > 0.53 && n > 0.5) return n > 0.8 ? EMR.brown : EMR.dark;
    if (m > 0.44 && n2 < 0.42) return EMR.green;
    return EMR.base;
  };
  const lit = c => 1.03 - 0.01 * Math.max(0, c.p[1]);                           // soft top light
  const fab = (c, k = 1) => mul(camo(c.p), k * G(c, 0.06));
  const bind = (c, k = 1) => mul(C.bind, k * G(c, 0.06));
  const bound = (c, t = 0.5) => mix(fab(c), C.bind, t);
  const lin = (c, col = C.lin) => mul(col, G(c, 0.08));
  const flush = (mat, ...faces) => c => (faces.includes(c.face) ? null : mat(c));
  const nU = c => Math.max(1, Math.round(c.fw / px)), nV = c => Math.max(1, Math.round(c.fh / px));
  const iU = c => Math.min(nU(c) - 1, Math.floor(c.eu / px + 1e-6)), iV = c => Math.min(nV(c) - 1, Math.floor(c.ev / px + 1e-6));
  const webRow = (c, y0) => { const dy = c.p[1] - y0; return dy >= 0 && dy < 1 ? mul(C.web, G(c, 0.05) * (dy < 0.5 ? 1.06 : 0.9)) : null; };

  // ---------- carrier core: front + back panels, open armholes (black lining) above the cummerbund. Scaled by its width the
  // original runs well below the belt, its front, sides and back ending on one line (the belly section a little lower) ----------
  const STRIPS = [2, 4];                                                          // light webbing strips (front and back)
  const HEM = 13;
  const core = c => {
    const ax = Math.abs(c.p[0]), y = c.p[1], z = c.p[2];
    if (c.face === 'top') return lin(c, C.clin);                                  // (under the head)
    if (c.face === 'bottom') return Math.abs(z) > 2.75 || ax > 4 ? bind(c) : null;   // the panels' bound hems only (the legs pass through the middle)
    if (isSide(c)) {
      if (Math.abs(z) > 2.25) return bound(c, 0.5);                               // rolled front / back panel edge
      if (y < 6.75) return lin(c);                                                // deep armhole
      return y > HEM - px ? bound(c, 0.6) : bind(c, 0.9);                         // under the cummerbund, then the side's bound hem
    }
    let col = fab(c, lit(c));
    if (ax < 3.25) for (const s of STRIPS) { const w = webRow(c, s); if (w) return w; }
    if (VARIANT === 'medic' && c.face === 'front' && c.p[0] < -4)                   // Medic: short MOLLE tabs on the panel's edge
      for (const t0 of [3.5, 5.5, 7]) if (y >= t0 && y < t0 + 1) return mul(C.web, G(c, 0.05) * (y < t0 + 0.5 ? 1.06 : 0.9));   // beside the mag pouch
    if (ax > 4) col = mix(col, C.bind, 0.4);                                      // bound armhole edge
    if (c.face === 'back' && y > HEM - px) col = mix(col, C.bind, 0.55);          // bound back hem
    return col;
  };
  B.push(box('body', [-4.5, 0, -3.25], [4.5, HEM, 3.25], core, { tag: 'carrier (front + back panels)' }));

  // proud lower belly section: rolled, bound edge, rounded bottom corners (in front of the thighs, clear of a walking leg);
  // its top roll starts right under the waist strap (dark crease + binding along the top)
  const AX = 4.25, AT = 11.5, AB = 13.5, AR = 0.75;
  const inApron = (ax, y) => {
    const dx = ax - (AX - AR), dy = y - (AB - AR);
    return dx <= 0 || dy <= 0 ? Math.min(AX - ax, AB - y) : AR - Math.hypot(dx, dy);
  };
  const apron = c => {
    const ax = isSide(c) ? AX - px / 2 : Math.abs(c.p[0]), y = c.face === 'bottom' ? AB - px / 2 : c.p[1];
    const d = inApron(ax, y);
    if (d <= 0) return null;
    if (c.face === 'back') return y < HEM ? null : lin(c, C.clin);               // against the carrier, then its lining
    if (c.face === 'top') return mul(bound(c, 0.5), 0.9);                         // the top roll's bound ledge
    if (c.face === 'bottom' || isSide(c)) return bind(c);
    const col = fab(c, lit(c) * 0.96);
    if (y - AT < px) return mix(col, C.bind, 0.5);                                // crease under the waist strap, top binding
    if (d < px) return mix(col, C.bind, 0.62);                                    // thick rolled binding
    if (d < 2 * px) return mul(col, 1.06);                                        // the roll's lit inner side
    return col;
  };
  B.push(box('body', [-AX, AT, -3.75], [AX, AB, -3.25], apron, { tag: 'belly section' }));

  // cummerbund: from the bottom of the armhole down to just above the hem (the core's bound side hem shows under it), two
  // MOLLE rows, the light waist strap a little lower, plain side panel below it
  const CT = 6.75;
  const cumm = c => {
    const y = c.p[1], z = c.p[2];
    if (c.face === 'top') return mul(fab(c), 1.06);
    if (c.face === 'bottom') return Math.abs(c.p[0]) > 4.25 ? mul(fab(c), 0.7) : null;   // (the legs pass through the middle)
    if (!isSide(c)) return mul(fab(c), 0.85);                                     // (only its ends show beside the panels)
    const r = Math.floor((y - CT) / px + 1e-6);                                   // cell rows from the top
    if (r === 8) return mul(C.web, G(c, 0.05) * 1.04);                             // waist strap
    const col = fab(c, 0.95);
    if (r === 9) return mul(col, 0.84);                                           // shadow under the strap
    if (Math.abs(z) > 2.25) return col;
    if (r === 2 || r === 5) return (((z + 2.25) % 1.5) + 1.5) % 1.5 < 0.5 ? mul(C.web, 0.8 * G(c, 0.05)) : mul(C.web, G(c, 0.05));   // webbing with bar tacks
    if (r === 3 || r === 6) return mul(col, 0.74);                                // shadow under the webbing
    return col;
  };
  B.push(box('body', [-4.75, CT, -2.75], [4.75, HEM - 0.25, 2.75], cumm, { tag: 'cummerbund' }));
  // dark olive side-release buckle on the waist strap at the front end of each side, on the carrier's front corner in
  // front of the sleeve
  for (const s of [-1, 1]) {
    const inner = s < 0 ? 'left' : 'right';
    const buck = c => {
      if (c.face === inner) return null;
      if (c.face === 'top') return mul(C.sbuck, 1.35);
      if (!isSide(c)) return mul(C.sbuck, 0.8);
      const j = iV(c), i = iU(c);
      if (j === 0) return mul(C.sbuck, 1.25);                                      // lit rim
      return mul(C.sbuck, i === 1 && j === 1 ? 0.7 : 1.05);                         // release window
    };
    B.push(box('body', s < 0 ? [-5, 10.75, -3.25] : [4.5, 10.75, -3.25], s < 0 ? [-4.5, 11.75, -2.5] : [5, 11.75, -2.5], buck, { tag: 'waist strap buckle' }));
  }
  // the light waist strap leaves the buckles, wraps the front corners and runs across the lower front just above the
  // belly section (behind the low front pouches): the brightest band on the lower front, lit top edge
  B.push(box('body', [-4.75, 11, -3.5], [4.75, 11.5, -3.25], c => {
    if (c.face === 'back') return null;                                            // (on the carrier / the buckles)
    const col = mul(C.web, G(c, 0.05));
    if (c.face === 'top') return mul(col, 1.12);
    if (c.face === 'bottom') return mul(col, 0.7);
    return isPanel(c) ? mul(col, 1.06) : mul(col, 0.82);
  }, { tag: 'waist strap (front)' }));

  // ---------- collar: the MC head hides the middle of the tall ring, so it shows as a rolled edge under the chin, tall side
  // walls beside the head and a high back band behind the neck (dark green inside). Seen from the front the ring's
  // lower edge curves up toward the shoulders, so the roll is deepest in the middle and one step shallower at the sides;
  // that frees the band where the render's short diagonal webbing straps run (inside the shoulder rolls) ----------
  const rim = c => mul(mix(camo(c.p), EMR.light, 0.3), 1.06 * G(c, 0.05));
  const collarF = hide => c => {
    if (c.face === 'back' || c.face === hide) return null;
    if (c.face === 'top') return c.p[2] < -3.625 ? rim(c) : lin(c, C.clin);
    if (c.face === 'bottom') return mul(fab(c), 0.6);
    if (isSide(c)) return mul(fab(c), 0.8);
    const j = iV(c);
    return j === 0 ? rim(c) : j === nV(c) - 1 ? mul(fab(c), 0.8) : fab(c, 0.97);   // rolled top, shadow onto the chest
  };
  B.push(box('body', [-1.75, 0.25, -4], [1.75, 1.75, -3.25], collarF(null), { tag: 'collar front roll' }));
  for (const s of [-1, 1])                                                         // (inner end against the middle)
    B.push(box('body', s < 0 ? [-3.5, 0.25, -4] : [1.75, 0.25, -4], s < 0 ? [-1.75, 1.25, -3.25] : [3.5, 1.25, -3.25], collarF(s < 0 ? 'left' : 'right'), { tag: 'collar front roll side' }));
  const collarB = c => {
    if (c.face === 'front') return lin(c, C.clin);
    if (c.face === 'top') return c.p[2] < 4.5 ? lin(c, C.clin) : rim(c);
    if (c.face === 'bottom') return mul(fab(c), 0.6);
    if (isSide(c)) return mul(fab(c), 0.85);
    const j = iV(c);
    return j === 0 ? rim(c) : j >= nV(c) - 2 ? mul(fab(c), 0.84) : fab(c);
  };
  B.push(box('body', [-4.75, -3.25, 3.25], [4.75, 1, 5], collarB, { tag: 'collar back' }));
  // tall side walls beside the head (outside it, the face stays clear), rising out of the shoulder rolls from their
  // front end back into the back band: with the front roll under the chin they close the ring, so it reads from the
  // front and three-quarter views. Scaled by the vest's width the original ring stands about three pixels above the
  // shoulders. Two pieces per side: the front one ends inside the shoulder roll, the back one comes down onto the sleeve.
  const WT = -3, WZ = 1;                                                           // wall top; where the two pieces meet
  const wall = inner => c => {
    const y = c.p[1];
    if (c.face === 'top') return rim(c);
    if (c.face === 'bottom') return mul(fab(c), 0.6);
    if (c.face === 'front' || c.face === 'back') {
      if (Math.abs(c.p[2] - WZ) < 1e-3) return null;                               // (where the two pieces meet)
      if (c.face === 'back') return mul(fab(c), 0.85);
    }
    if (c.face === inner) return y < WT + px ? mul(fab(c), 0.8) : lin(c, C.clin);   // rolled lip, then the dark inside
    if (y < WT + px) return rim(c);                                                 // rolled top
    if (c.face !== 'front') return fab(c, 0.95);
    // front end: the ring curving round toward the chin, its dark inside showing next to the head
    return Math.abs(c.p[0]) < 5 ? mix(fab(c, 0.8), C.clin, 0.6) : fab(c, 0.9);
  };
  for (const s of [-1, 1]) {
    const X = (a, b) => (s < 0 ? [-b, -a] : [a, b]), [w0, w1] = X(4.5, 5.5), inner = s < 0 ? 'left' : 'right';
    B.push(box('body', [w0, WT, -3.5], [w1, -0.5, WZ], wall(inner), { tag: 'collar side (front)' }));
    B.push(box('body', [w0, WT, WZ], [w1, -0.25, 4.75], wall(inner), { tag: 'collar side (back)' }));
  }

  // ---------- padded shoulder rolls: a thick pad on top of the shoulder, reaching forward over the arm, and a riser down
  // the upper chest ending in the rounded open tube end (dark inside). Over the arm the pad stays clear of the sleeve
  // top; its front end, ahead of the sleeve, comes down onto the riser so no gap opens between them ----------
  const RZ = -2.5;                                                                 // where the front end meets the rest
  for (const s of [-1, 1]) {
    const X = (a, b) => (s < 0 ? [-b, -a] : [a, b]);
    const outer = s < 0 ? 'right' : 'left';
    const [h0, h1] = X(4.25, 6);
    const roll = c => {
      const y = c.p[1];
      if (c.face === 'bottom') return lin(c);
      if (c.face === 'top') return fab(c, 1.08);
      if (Math.abs(c.p[2] - RZ) < 1e-3) return c.face === 'back' && y > -0.25 ? lin(c) : null;   // (where the pieces meet)
      if (c.face === 'front') return y > -0.75 ? mul(fab(c), 0.72) : fab(c, 0.92);    // rolled front end
      if (c.face === outer) return y < -1 ? fab(c, 1.06) : fab(c, 0.92);
      return mul(fab(c), 0.85);
    };
    B.push(box('body', [h0, -1.5, -4], [h1, 0, RZ], roll, { tag: 'shoulder roll (front end)' }));
    B.push(box('body', [h0, -1.5, RZ], [h1, -0.25, 1], roll, { tag: 'shoulder roll' }));   // (clear of the sleeve top)
    const [r0, r1] = X(3.25, 4.75);
    B.push(box('body', [r0, 0, -4.25], [r1, 2.75, -3.25], c => {
      if (c.face === 'back') return null;
      if (c.face === 'bottom') return lin(c);                                       // open end of the padded tube
      if (c.face === 'top') return fab(c);
      const j = iV(c), n = nV(c);
      if (!isPanel(c)) return mul(fab(c), j === n - 1 ? 0.62 : 0.84);
      if (j === n - 1) return mix(fab(c), C.bind, 0.55);                            // rolled rim round the opening
      const i = iU(c), m = nU(c);
      return fab(c, i === 0 || i === m - 1 ? 0.92 : 1.06);                          // rounded: lit middle, shaded edges
    }, { tag: 'shoulder roll riser' }));
  }
  // short light webbing strap on the chest just inside each shoulder roll, rising toward the collar: its outer end tucks
  // under the shoulder roll, its inner end under the collar roll (General: only the wearer's-right one shows, the radio
  // pouch covers the other)
  for (const s of VARIANT === 'general' ? [-1] : [-1, 1]) {
    const [a0, a1] = s < 0 ? [-3.25, -1.75] : [1.75, 3.25];
    B.push(box('body', [a0, 1.5, -3.5], [a1, 2, -3.25], c => {
      if (c.face === 'back') return null;
      const col = mul(C.web, G(c, 0.05));
      return isPanel(c) ? col : mul(col, c.face === 'top' ? 1.08 : 0.78);
    }, { rot: [0, 0, s < 0 ? -20 : 20], pivot: [s < 0 ? a0 : a1, 2, -3.375], tag: 'chest strap (diagonal)' }));
  }

  // ---------- pouch painters ----------
  // lid tips are U's whose sides converge like the render's long flaps: how many px a lid column (i of n) stops short of
  // the tip (5 cells: 3/1/0/1/3, 4 cells: 1/0/0/1, 3 cells: 1/0/1)
  const uCut = (n, i) => {
    const e = Math.min(i, n - 1 - i);
    return (n >= 5 ? [3, 1, 0][Math.min(e, 2)] : n >= 3 ? (e === 0 ? 1 : 0) : 0) * px;
  };
  // EMR pouch fabric: the render's pouches are a shade greener than the carrier, lid and pocket alike. How much darker
  // differs per pouch on the render: the big double mag pouch reads clearly darker than the chest (about two thirds of
  // it), the side pouches a little darker, the radio pouch close to the carrier; the plain IFAK stays the lightest pouch.
  // tone = [how far toward EMR green, brightness]
  const pfab = ([t, k]) => c => mul(mix(camo(c.p), EMR.green, t), k * G(c, 0.05));
  // the lids are calmer and a touch lighter than their pockets: no dark-green / brown clusters land on them, so the dark
  // binding round each U is the darkest thing on the pouch and the U's read
  const lfab = ([t, k]) => c => {
    const m = camo(c.p), q = m === EMR.dark || m === EMR.brown ? EMR.green : m;
    return mul(mix(q, EMR.green, Math.max(0.4, t)), 1.05 * k * G(c, 0.05));
  };
  // EMR flap pouch body: the lid plate (below) covers its upper front; a shadow cell right under the lid's U edge, one
  // darker seam line between the pockets of a double pouch, dark bottom row; shaded sides
  const pouchBody = (flapLen, comps, inner, backed, fab, lidFab) => c => {
    if (c.face === inner) return null;
    if (c.face === 'back') return backed ? mul(fab(c), 0.55) : null;
    if (c.face === 'top') return mul(lidFab(c), 1.1);                                // the lid folded over the top
    const col = fab(c);
    if (c.face === 'bottom') return mul(col, 0.6);
    const v = c.p[1] - c.box.y;
    if (!isPanel(c)) return v < flapLen ? mix(mul(lidFab(c), 0.9), C.lbind, 0.3) : mul(col, 0.8);
    const w = c.fw / comps, k = Math.min(comps - 1, Math.floor(c.eu / w + 1e-6)), u = c.eu - k * w;
    const n = Math.max(1, Math.round(w / px)), i = Math.min(n - 1, Math.floor(u / px + 1e-6));
    const lb = flapLen - uCut(n, i);
    if (v >= lb - 1e-6 && v < lb + px) return mul(col, 0.8);                        // shadow under the lid's edge
    if (c.ev > c.fh - px) return mul(col, 0.76);
    if (comps > 1 && k > 0 && u < px) return mul(col, 0.74);                        // seam between the pockets
    return col;
  };
  // lid plate in front of the pouch: a long U like the original's flaps. The columns step up toward the sides (see
  // uCut), the plate is cut out below that rounded U, and a dark binding runs as one outline down both sides (from the
  // top fold) and round the tip, with a lit cell just inside it (the binding's rolled edge); the lit fold runs along the
  // top. seamL: this lid's first column is the darker gap where it meets the lid beside it.
  const lidPlate = (len, fab, seamL = false) => c => {
    if (c.face === 'back') return null;
    const b = c.box, n = Math.max(1, Math.round(b.w / px));
    const i = Math.max(0, Math.min(n - 1, Math.floor((c.p[0] - b.x) / px + 1e-6)));
    const v = c.p[1] - b.y, yb = len - uCut(n, i), j = Math.floor(v / px + 1e-6);
    const L = k => Math.round((len - uCut(n, k)) / px);                             // cells in column k
    const has = (k, r) => k >= 0 && k < n && r >= 0 && r < L(k);
    // on the outline: an edge column, the column's last cell (the tip), or a cell whose outer neighbour column has
    // already ended (the converging sides)
    const rimAt = (k, r) => {
      if (n < 3 || !has(k, r)) return false;
      if (Math.min(k, n - 1 - k) === 0 || r === L(k) - 1) return true;
      return !has(k < (n - 1) / 2 ? k - 1 : k + 1, r);
    };
    const bnd = k => mul(C.lbind, k * G(c, 0.05));
    if (c.face === 'bottom') return yb >= len - 1e-6 ? bnd(0.9) : null;
    if (v > yb + 1e-6) return null;
    const col = fab(c);
    if (c.face === 'top') return mul(col, 1.12);
    if (isSide(c)) return bnd(0.95);
    if (rimAt(i, j)) return seamL && i === 0 ? bnd(0.8) : bnd(j === 0 ? 1.2 : 1);   // binding (lit where it folds over the top)
    if (j === 0) return mul(col, 1.1);                                              // lit fold over the pouch top
    if (rimAt(i - 1, j) || rimAt(i + 1, j) || rimAt(i, j + 1)) return mul(col, 1.1); // lit edge inside the binding
    return col;
  };
  // pull tab hanging from a lid's tip (one cell wide, lit top cell)
  const pullTab = c => {
    if (c.face === 'back') return null;
    const col = mul(C.tab, G(c, 0.05));
    if (c.face === 'top') return mul(col, 1.2);
    if (!isPanel(c)) return mul(col, 0.8);
    return iV(c) === 0 ? mul(col, 1.15) : col;
  };
  const addTab = (cx, y0, zf) => B.push(box('body', [cx - 0.25, y0, zf - 0.25], [cx + 0.25, y0 + 1, zf], pullTab, { tag: 'pull tab' }));
  // a flap pouch: body [x0..x1] x [y0..y1] x [zf..zb], one lid per pocket (len long, from `lidTop`), a pull tab under
  // each lid's tip
  const addPouch = ({ x0, x1, y0, y1, zf, zb, len, comps = 1, lidTop = y0, inner = null, backed = false, tabs = true, tone = [0.2, 1], tag }) => {
    const fab = pfab(tone), lidFab = lfab(tone);
    B.push(box('body', [x0, y0, zf], [x1, y1, zb], pouchBody(len + lidTop - y0, comps, inner, backed, fab, lidFab), { tag }));
    const w = (x1 - x0) / comps;
    for (let k = 0; k < comps; k++) {
      const a = x0 + k * w;
      B.push(box('body', [a, lidTop, zf - 0.25], [a + w, lidTop + len, zf], lidPlate(len, lidFab, k > 0), { tag: tag + ' lid' }));
      if (tabs) addTab(a + w / 2, lidTop + len - 0.25, zf - 0.25);
    }
  };
  // flat plate with a see-through opening (carry loops): '#' = webbing; col is a colour or a painter (c => colour)
  const loopPlate = (pat, col) => c => {
    const base = typeof col === 'function' ? col(c) : col;
    if (!isPanel(c)) return mul(base, c.face === 'top' ? 1.12 : 0.8);
    const n = pat[0].length, m = pat.length;
    const i = Math.min(n - 1, Math.floor(c.eu / c.fw * n)), j = Math.min(m - 1, Math.floor(c.ev / c.fh * m));
    const ii = c.face === 'back' ? n - 1 - i : i;
    return pat[j][ii] === '#' ? mul(base, G(c, 0.05) * (j === 0 ? 1.1 : 0.95)) : null;
  };
  // blue-grey patch with a red cross (3 x 3 cells)
  const crossPatch = c => {
    if (c.face === 'back') return null;
    if (!isPanel(c)) return mul(C.patch, 0.75);
    const i = iU(c), j = iV(c);
    return (i === 1 || j === 1) ? mul(C.cross, G(c, 0.04)) : mul(C.patch, G(c, 0.04) * (j === 0 ? 1.08 : 1));
  };
  if (VARIANT === 'general') {
    // ---------- black tourniquet lying across the upper webbing strip, red tag on the wearer's left end ----------
    const tq = c => {
      if (c.face === 'back') return null;
      const x = c.p[0];
      if (x > 0) return mul(C.red, c.face === 'front' ? G(c, 0.05) : c.face === 'top' ? 1.15 : 0.78);
      if (c.face === 'top') return mul(C.tql, 1.2);
      if (!isPanel(c)) return mul(C.tq, 0.85);
      const j = iV(c);
      if (j === 0) return mul(C.tql, G(c, 0.05));                                   // windlass rod along the top
      return mul(C.tq, G(c, 0.05) * (x > -2 && x < -1.5 ? 1.35 : 1));               // strap band
    };
    B.push(box('body', [-3, 2, -3.75], [0.5, 3, -3.25], tq, { tag: 'tourniquet' }));
    // small light grey fabric tab coming out from under the collar roll (flush with its front) and ending just over the
    // tourniquet's top edge (its lower part rests on the tourniquet)
    B.push(box('body', [-1, 1.75, -4], [0, 2.5, -3.25], flush(c => {
      const col = mul(C.gtab, G(c, 0.04));
      if (!isPanel(c)) return mul(col, 0.78);
      return iV(c) === nV(c) - 1 ? mul(col, 0.8) : col;
    }, 'back', 'top'), { tag: 'grey tab' }));

    // ---------- radio pouch on the wearer's left upper chest, black whip antenna rising out of it ----------
    addPouch({ x0: 1.25, x1: 3.25, y0: 1.75, y1: 4.75, zf: -4.5, zb: -3.25, len: 1.5, tabs: false, tag: 'radio pouch' });
    // (it comes out of the pouch from under the lid)
    B.push(box('body', [1.5, -0.5, -4.5], [2, 2.25, -4], flush(K.plastic(C.ant), 'back'), { tag: 'radio antenna' }));
    // black knife grip at the front corner; it goes down into the mag pouch's top (its sheath runs down behind it)
    B.push(box('body', [3.5, 3.5, -4], [4, 6, -3.25], flush(c => {
      if (c.face === 'top') return mul(C.knife, 1.6);
      if (!isPanel(c)) return mul(C.knife, 0.85);
      const j = iV(c);
      return mul(C.knife, j === 0 ? 1.5 : j === 2 ? 1.25 : 1);                      // pommel, finger ridge
    }, 'back'), { tag: 'knife grip' }));

    // ---------- plain grey-green IFAK on the wearer's right lower front ----------
    const ifak = c => {
      if (c.face === 'back') return null;
      const col = mul(C.ifak, G(c, 0.05) * (0.96 + 0.08 * fbm(c.p[0] * 0.5 + 3, c.p[1] * 0.5 + 9, 4)));
      if (c.face === 'top') return mul(col, 1.1);
      if (c.face === 'bottom') return mul(col, 0.62);
      if (!isPanel(c)) return mul(col, 0.8);
      const i = iU(c), j = iV(c), n = nU(c), m = nV(c);
      if (j === 0 && (i === 0 || i === n - 1)) return mul(col, 0.72);                // rounded top corners
      if (i === 3 || i === 4) return mul(C.strap, G(c, 0.05) * (i === 3 ? 1.1 : 0.88) * (j === m - 1 ? 0.9 : 1));   // raised centre strap: lit left, shaded right, down to the bottom
      if (j === 0) return mul(col, 1.08);
      if (j === m - 1 || i === 0 || i === n - 1) return mul(col, 0.86);
      return col;
    };
    B.push(box('body', [-4.5, 6, -4.75], [-0.5, 11, -3.25], ifak, { tag: 'IFAK pouch' }));
    B.push(box('body', [-3.5, 4.75, -4.5], [-1.5, 6.25, -4.25], loopPlate(['####', '#..#', '####'], C.loop), { tag: 'IFAK carry loop' }));
    B.push(box('body', [-3.25, 5.75, -5], [-1.75, 6.75, -4.75], flush(c => {
      if (c.face === 'top') return mul(C.tan, 1.12);
      if (!isPanel(c)) return mul(C.tan, 0.78);
      const i = iU(c), j = iV(c);
      if (j === 1 && i === 1) return mul(C.tan, 0.62);                               // strap slot
      return mul(C.tan, G(c, 0.04) * (j === 0 ? 1.08 : 0.94));
    }, 'back'), { tag: 'IFAK buckle' }));
    // (set in from the pouch's edge so IFAK fabric frames it on the right and below)
    B.push(box('body', [-2.25, 9, -5], [-0.75, 10.5, -4.75], crossPatch, { tag: 'red cross patch' }));

    // ---------- EMR double mag pouch on the wearer's left lower front (two long U flaps, pull tabs), the lowest pouch ----------
    // (the darkest pouch on the render, about two thirds as bright as the chest)
    addPouch({ x0: -0.5, x1: 4.5, y0: 5.5, y1: 11.75, zf: -4.5, zb: -3.25, len: 3.5, comps: 2, tone: [0.35, 0.72], tag: 'double mag pouch' });

    // ---------- small EMR flap pouch on the front of each side of the cummerbund (wide enough for a 3-cell U flap),
    // pushed into the corner between the carrier's front edge and the cummerbund, a little clear of the sleeve ----------
    for (const s of [-1, 1]) {
      addPouch({ x0: s < 0 ? -6 : 4.5, x1: s < 0 ? -4.5 : 6, y0: 7, y1: 10, zf: -3.25, zb: -2.5, len: 1.5, inner: s < 0 ? 'left' : 'right', backed: true, tone: [0.25, 0.87], tag: 'side pouch' });
    }
    // black cord hanging down the outer side of the wearer's-left side pouch, past its bottom
    B.push(box('body', [6, 8.5, -3], [6.25, 10.5, -2.5], c => {
      if (c.face === 'right') return null;                                         // (against the pouch)
      const col = mul(C.tq, G(c, 0.05));
      if (c.face === 'top') return mul(col, 1.3);
      return c.face === 'left' ? mul(col, iV(c) === 0 ? 1.25 : 1.05) : mul(col, 0.85);
    }, { tag: 'side pouch cord' }));
  } else {
    // ---------- tall single mag pouch on the wearer's right, under the upper strip: from just inside the panel edge (the
    // edge strip with its short MOLLE tabs shows beside it) right up to the IFAK (about 0.6 of its width, a 5-cell
    // rounded U lid); dark like the render's (about two thirds as bright as the chest) ----------
    addPouch({ x0: -4, x1: -1.5, y0: 3.25, y1: 9.75, zf: -4.5, zb: -3.25, len: 3.5, inner: 'left', tone: [0.35, 0.72], tag: 'single mag pouch' });

    // ---------- big EMR IFAK in the middle (carry loop, dark buckle on a centre strap, red-cross patch) ----------
    // (darker than the carrier like the render's bag, but a touch lighter and warmer than the greener EMR pouches round
    // it; a deep shadow down its left edge and along its bottom makes the rounded bag stand off the mag pouch beside it
    // and the small pouches under it)
    const ifak = c => {
      if (c.face === 'back') return null;
      const col = mul(mix(camo(c.p), EMR.dark, 0.12), 0.86 * G(c, 0.05));
      if (c.face === 'top') return mul(col, 1.1);
      if (c.face === 'bottom') return mul(col, 0.55);
      if (c.face === 'right') return mul(col, 0.62);                                // (the side toward the mag pouch)
      if (!isPanel(c)) return mul(col, 0.8);
      const i = iU(c), j = iV(c), n = nU(c), m = nV(c);
      if (i === 0 || j === m - 1) return mul(col, 0.6);                              // shadow: left edge, bottom
      if (j === 0 && i === n - 1) return mul(col, 0.66);                             // rounded top corner
      // EMR webbing strap in the pouch's own tone, a little toward the patch, reading only through its shadow edges
      if (i === 5) return mul(col, 1.04);
      if (i === 4 || i === 6) return mul(col, i === 4 ? 0.8 : 0.72);
      if (j === 0) return mul(col, 1.1);
      if (i === n - 1) return mul(col, 0.8);
      return col;
    };
    B.push(box('body', [-1.5, 3, -5], [3, 8, -3.25], ifak, { tag: 'IFAK pouch' }));
    // EMR carry loop arching up over the upper strip (its bottom row seated in the IFAK's top), a little left of the strap:
    // calmer camo, a lit crown and shaded legs so the arch reads against the strip and the collar's shadow
    const loopCol = c => mul(mix(camo(c.p), EMR.base, 0.4), c.ev < px ? 1.12 : 0.86);
    B.push(box('body', [-0.75, 1.75, -4.75], [1.25, 3.25, -4.5], loopPlate(['####', '#..#', '#..#'], loopCol), { tag: 'IFAK carry loop' }));
    B.push(box('body', [0.5, 3.25, -5.25], [2, 4.25, -5], flush(c => {
      if (c.face === 'top') return mul(C.dbuck, 1.45);
      if (!isPanel(c)) return mul(C.dbuck, 0.8);
      const i = iU(c), j = iV(c);
      if (j === 1 && i === 1) return mul(C.dbuck, 0.6);
      return mul(C.dbuck, G(c, 0.04) * (j === 0 ? 1.35 : 1.05));
    }, 'back'), { tag: 'IFAK buckle' }));
    B.push(box('body', [1.375, 6.125, -5.25], [2.875, 7.625, -5], crossPatch, { tag: 'red cross patch' }));

    // ---------- two small flap pouches side by side under the IFAK, as wide together as the IFAK, reaching the lowest,
    // as dark as the mag pouch; built as one pair so a dark seam runs down between them (binding, then the pockets' gap)
    // (their tops and lids start under the IFAK, so only the lower part of each U shows) ----------
    addPouch({ x0: -1.25, x1: 2.75, y0: 7.5, y1: 11.75, zf: -4.5, zb: -3.25, len: 2.5, comps: 2, tone: [0.35, 0.72], tag: 'small pouches' });

    // ---------- radio at the wearer's left front corner: MOLLE radio pouch, black radio, whip antenna, pink tourniquet ----------
    // (a plain dark olive-brown pouch, no camo, the darkest on the vest; the lighter khaki straps wrap round it)
    const radioPouch = c => {
      if (c.face === 'back') return null;
      if (c.face === 'top') return c.ev > c.fh - px ? mul(C.rpouch, 1.12 * G(c, 0.06)) : lin(c);   // open mouth round the radio
      const col = mul(C.rpouch, G(c, 0.06));
      if (c.face === 'bottom') return mul(col, 0.6);
      const y = c.p[1];
      for (const s0 of [5, 6.75, 8.25]) {                                           // webbing straps round the pouch
        if (y >= s0 && y < s0 + 0.5) return mul(C.rstrap, G(c, 0.05) * (isPanel(c) ? 0.88 : 0.76));   // (muted, like the render's)
        if (y >= s0 + 0.5 && y < s0 + 1 && isPanel(c)) return mul(col, 0.8);
      }
      if (!isPanel(c)) return mul(col, 0.82);
      if (c.ev < px) return mul(col, 1.1);
      return c.ev > c.fh - px ? mul(col, 0.84) : col;
    };
    B.push(box('body', [2.75, 4, -4.75], [4.75, 9, -3.25], radioPouch, { tag: 'radio pouch' }));
    B.push(box('body', [3.5, 3, -4.5], [4.5, 5, -3.5], c => {
      if (c.face === 'top') return mul(C.radio, 1.5);
      if (!isPanel(c)) return mul(C.radio, 0.85);
      return mul(C.radio, iV(c) === 0 ? 1.3 : G(c, 0.04));
    }, { tag: 'radio' }));
    B.push(box('body', [3.875, -0.5, -4.75], [4.375, 3.25, -4.25], flush(K.plastic(C.ant), 'back'), { tag: 'radio antenna' }));
    B.push(box('body', [4.75, 4.5, -4.5], [5.25, 8.5, -3.5], flush(c => {
      const col = mul(C.pink, G(c, 0.05));
      if (c.face === 'top') return mul(col, 1.1);
      if (c.face === 'bottom') return mul(col, 0.6);
      const j = iV(c), n = nV(c);
      if (j === 1 || j === n - 2) return mul(C.teal, G(c, 0.05) * (isPanel(c) ? 0.9 : 1));   // teal accents near both ends
      return mul(col, j === 0 ? 1.08 : j === n - 1 ? 0.86 : (isPanel(c) ? 0.88 : 1));   // flat pink rubber
    }, 'right'), { tag: 'pink tourniquet' }));
  }
  return { S: K.S, px: K.px, cell: K.cell, boxes: B };
};
