// ================= 6B43 Zabralo-Sh body armor (Digital Flora / EMR) — EFT reference (tarkov.dev 545cdb794bdc2d3a198b456a) =================
// Heavy Russian full-protection vest, all-over EMR digital flora (mid olive-green ground, dark-green clusters, yellow-green
// flecks, a few brown bits; the lit chest reads grey-green, the shaded parts greener). Reference render: very tall padded
// stand collar (darker, greener, it hides the whole neck) whose front is a padded throat flap with a small dark strip on its
// lower edge and a stitched trapezoid tongue sewn onto the chest right under it; two short grey webbing tabs high on the
// chest near the armholes, level with the tongue's lower half; three nearly full-width grey webbing rows across the chest
// (lighter and greyer than the camo, bar-tacked); a fourth grey strap lower down that runs round the whole vest
// (cummerbund) with a teal-grey side-release buckle at the wearer's-right front corner; the vest body runs well below the
// belt line (front panel hem about 5 px under the strap, lower corners rounded); side panels under the open armholes with
// two more grey rows, reaching nearly as low; a groin flap right under the strap (lit top, double-stitched hem) over a long
// groin protector that tapers a little and ends in a broad rounded tip; dark teal binding on the edges; big padded shoulder
// protectors (open on the armpit side, pale grey-green lining inside, an oblique opening - longest on the outside of the
// arm - with dark teal rim binding, camo right down to it; a short grey webbing tab on the front with a dark teal arm strap
// hanging from it; the wearer's-right one has a thin tan strip just above the rim) and a short camo roll with a crimped
// silver end on top of each shoulder. The back is not visible in the render: plain camo with the same three grey rows.
ARMORS.b6b43_zabralo_sh = function (mode) {
  const K = kit('M');                          // always the 2x style, whatever mode is asked for
  const { G, isPanel, isSide, px } = K;
  const B = [];

  // ---------- palette (sampled off the render) ----------
  const C = {
    bind: hex('#34463d'),    // dark teal-green binding on the edges and sleeve rims
    inner: hex('#2c2f2d'),   // charcoal inside of the vest panels
    lining: hex('#7c8072'),  // pale grey-green lining inside the shoulder protectors
    web: hex('#9ba69c'),     // light grey webbing rows / tabs / cummerbund strap
    buckle: hex('#4f6b60'),  // teal-grey plastic side-release buckle
    strap: hex('#2f4642'),   // dark teal arm strap
    cap: hex('#a4a994'),     // crimped silver end of the shoulder rolls
    collar: hex('#4a6448'),  // the collar's shaded, greener tone
    tan: hex('#8a7a52'),     // thin tan strip above the rim of the wearer's-right shoulder protector
  };
  // Digital flora (EMR) on the 2x art grid: soft macro regions (about 2 px) decide where dark clumps or light flecks
  // gather, a micro noise breaks them into small clusters of half-pixel cells (never a one-cell checker)
  const EMR = { base: hex('#7e8868'), dark: hex('#5f6b53'), light: hex('#97a574'), bright: hex('#aebc85'), brown: hex('#766e58') };
  const camo = p => {
    const q = snap(p, 0.5);
    const m = fbm(q[0] * 0.45 + 3, q[1] * 0.45 + 7, q[2] * 0.45 + 1, 2);
    const n = vnoise(q[0] * 1.2 + 11, q[1] * 1.2 + 5, q[2] * 1.2 + 9), n2 = vnoise(q[0] * 1.3 + 31, q[1] * 1.3 + 13, q[2] * 1.3 + 2);
    if (n2 > 0.8) return EMR.bright;
    if (m < 0.55 && n2 > 0.5) return EMR.light;
    if (m > 0.52 && n > 0.5) return n > 0.88 ? EMR.brown : EMR.dark;
    return EMR.base;
  };
  const fab = (c, k = 1) => mul(camo(c.p), k * G(c, 0.06));
  const lit = c => 1.04 - 0.012 * Math.max(0, Math.min(12, c.p[1]));        // soft top light
  const inner = c => mul(C.inner, G(c, 0.08));
  const bind = (c, k = 1) => mul(C.bind, k * G(c, 0.06));
  const bound = (c, t) => mix(fab(c), C.bind, t);
  const flush = (mat, face) => c => (c.face === face ? null : mat(c));
  const cellIdx = (v, v0) => Math.floor((v - v0) / px + 1e-6);

  // grey webbing tape, 1 px tall (two art cells): lit top cell, slightly darker lower cell; a bar tack (a darker cell
  // column through both cells) every 1.5 px, counted from h0. j = cell row inside the tape (0 = top).
  const TAPE_H = 1;
  const tape = (c, h, h0, j) => {
    const col = mul(C.web, G(c, 0.05) * (j === 0 ? 1.06 : 0.9));
    return cellIdx(h, h0) % 3 === 2 ? mul(col, 0.82) : col;
  };
  // a row of tape on a face: rows = tops of the tapes, horizontal span h0..h1
  const rowAt = (c, rows, h, h0, h1) => {
    if (h < h0 || h > h1) return null;
    for (const r of rows) if (c.p[1] >= r && c.p[1] < r + TAPE_H) return tape(c, h, h0, cellIdx(c.p[1], r));
    return null;
  };
  const ROWS = [3.5, 5.5, 7.5];                                                 // same pitch as in the render (tape, 1 px gap)
  const RW = 4;                                                                 // the chest rows run nearly edge to edge
  const STRAP0 = 9.25, STRAP1 = 10.25;                                          // cummerbund strap: the fourth bold band

  // ---------- front / back panels (bound edges, three grey rows across the chest / back) ----------
  const panel = back => c => {
    const out = back ? 'back' : 'front';
    if (c.face === (back ? 'front' : 'back')) return inner(c);
    if (c.face === 'bottom') return inner(c);                                   // (the lower skirt carries on below it)
    if (isSide(c)) return bound(c, 0.6);                                        // bound armhole / side edge
    if (c.face === 'top') return mul(fab(c), 0.9);
    const t = c.face === out && rowAt(c, ROWS, c.p[0], -RW, RW);
    if (t) return t;
    let col = fab(c, lit(c));
    // soft shadow under each tape and under the cummerbund strap (the tapes are sewn on, a little proud)
    if (c.face === out && ((Math.abs(c.p[0]) < RW && ROWS.some(r => c.p[1] >= r + TAPE_H && c.p[1] < r + TAPE_H + px)) || (c.p[1] > STRAP1 - 0.25 && c.p[1] < STRAP1 + 0.25))) col = mul(col, 0.86);
    // narrow stitched dark band right under the collar tongue
    if (!back && c.face === out && c.p[1] >= 2.5 && c.p[1] < 3 && Math.abs(c.p[0]) < 1.25) col = mul(col, 0.8);
    if (Math.abs(c.p[0]) > 4) col = mix(col, C.bind, 0.45);                    // bound side edges
    return col;
  };
  // (tops at y -0.5, above the skin's jacket layer, so no strip of shirt shows on the shoulders beside the neck)
  B.push(box('body', [-4.5, -0.5, -3.25], [4.5, 12, -2.25], panel(false), { tag: 'front panel' }));
  B.push(box('body', [-4.5, -0.5, 2.25], [4.5, 12, 3.25], panel(true), { tag: 'back panel' }));

  // ---------- lower skirt of the panels: the vest runs well below the hips (front hem about 5 px under the strap). Own
  // boxes, a quarter pixel thinner, so their inner faces stay clear of the legs' outer layer. Front: the two lower outer
  // corners rounded by one cell (a narrower last row). ----------
  const skirt = (back, hw, yb, corner) => c => {
    if (c.face === 'top') return null;                                          // pressed against the panel / row above
    if (c.face === (back ? 'front' : 'back')) return inner(c);
    if (c.face === 'bottom') return bind(c);
    if (isSide(c)) return bound(c, 0.6);
    let col = fab(c, lit(c));
    const ax = Math.abs(c.p[0]);
    if (ax > hw - px || (!corner && c.p[1] > yb - px)) col = mix(col, C.bind, 0.45);   // bound sides and hem
    return col;
  };
  B.push(box('body', [-4.5, 12, -3.25], [4.5, 13, -2.5], skirt(false, 4.5, 13, true), { tag: 'front skirt' }));
  B.push(box('body', [-4, 13, -3.25], [4, 13.5, -2.5], skirt(false, 4, 13.5, false), { tag: 'front skirt hem (rounded corners)' }));
  B.push(box('body', [-4.5, 12, 2.5], [4.5, 13.5, 3.25], skirt(true, 4.5, 13.5, false), { tag: 'back skirt' }));

  // ---------- side panels under the open armholes: camo, two grey rows below the strap, bound top and hem. Front/back
  // faces stand a quarter pixel proud of the sleeve layer (no flicker against it); below the arm they show from the
  // side. ----------
  const SIDE_ROWS = [10.5, 12];
  for (const s of [-1, 1]) {
    const outer = s < 0 ? 'right' : 'left', inn = s < 0 ? 'left' : 'right';
    B.push(box('body', s < 0 ? [-4.75, 6.5, -2.5] : [4.25, 6.5, -2.5], s < 0 ? [-4.25, 13.5, 2.5] : [4.75, 13.5, 2.5], c => {
      if (c.face === inn) return inner(c);
      if (c.face === 'bottom' || c.face === 'top') return bind(c);
      if (c.face !== outer) return bound(c, 0.5);
      const y = c.p[1];
      if (y < 6.5 + px || y > 13.5 - px) return bound(c, 0.55);
      return rowAt(c, SIDE_ROWS, c.p[2], -2.5, 2.5) || fab(c, lit(c));
    }, { tag: 'side panel' }));
  }

  // ---------- cummerbund strap round the whole vest (as bold as the chest tapes) + side-release buckle centred on it at
  // the wearer's-right front corner ----------
  B.push(box('body', [-5, STRAP0, -3.5], [5, STRAP1, 3.5], c => {
    const col = mul(C.web, G(c, 0.05));
    if (c.face === 'top') return mul(col, 1.1);
    if (c.face === 'bottom') return mul(col, 0.7);
    const j = cellIdx(c.p[1], STRAP0);
    if (isPanel(c)) return tape(c, c.p[0], -5, j);
    return mul(tape(c, c.p[2], -3.5, j), 0.92);
  }, { tag: 'cummerbund strap' }));
  const BK0 = (STRAP0 + STRAP1) / 2 - 0.75;
  B.push(box('body', [-4.75, BK0, -3.75], [-3.25, BK0 + 1.5, -3.5], flush(c => {
    const col = mul(C.buckle, G(c, 0.05));
    if (c.face === 'top') return mul(col, 1.35);
    if (c.face === 'bottom') return mul(col, 0.7);
    if (c.face !== 'front') return mul(col, 0.85);
    const i = cellIdx(c.p[0], -4.75), j = cellIdx(c.p[1], BK0);
    if (i === 1) return mul(col, j === 1 ? 0.6 : 0.72);                         // slot between the two halves
    return mul(col, (j === 0 ? 1.25 : j === 2 ? 0.88 : 1) * (i === 0 ? 1.1 : 1));
  }, 'back'), { tag: 'side-release buckle' }));

  // ---------- chest details: collar throat flap, stitched tongue under it, two short webbing tabs ----------
  // The MC head hides the front of the tall stand collar, so its front shows as a padded throat flap under the chin
  // (lit rolled top edge, small dark strip on its lower edge); the height shows in the walls beside the head and the
  // roll behind the neck (below).
  // the collar is a darker, greener camo than the vest (it sits in shadow in the render)
  const collarFab = (c, k = 1) => mul(mix(fab(c), C.collar, 0.3), 0.92 * k);
  const FLAP = 2.5;
  B.push(box('body', [-FLAP, 0, -4.25], [FLAP, 1, -3.25], flush(c => {
    if (c.face === 'bottom') return collarFab(c, 0.65);
    if (c.face === 'top') return collarFab(c, 1.15);
    const col = collarFab(c);
    if (isSide(c)) return mix(col, C.bind, 0.4);
    if (cellIdx(c.p[1], 0) === 0) return mul(col, 1.14);                       // rolled top edge
    return Math.abs(c.p[0]) < 1 ? mix(col, C.bind, 0.75) : mix(col, C.bind, 0.35);   // lower seam + dark strip
  }, 'back'), { tag: 'collar throat flap' }));
  // tongue: a trapezoid starting right at the flap's lower edge (rows of 6, 5 and 4 cells) in the ordinary vest camo,
  // outlined only by a slightly darker outer cell on its slanted sides and bottom (the quarter-pixel step to the panel
  // draws the rest); one box per row, so the narrowing is geometry, not cut-out cells
  const tongue = (hw, last) => flush(c => {
    if (c.face === 'top') return null;                                          // under the flap / the row above
    const col = fab(c, lit(c) * 1.04);
    if (c.face === 'bottom') return mul(col, 0.7);
    if (c.face !== 'front') return mul(col, 0.78);                              // the step's thickness
    if (Math.abs(c.p[0]) > hw - px) return mul(col, 0.8);                       // outline: slanted sides
    return last ? mul(col, 0.86) : col;                                         // bottom row (the seam band follows)
  }, 'back');
  [[1.5, 1, 1.5], [1.25, 1.5, 2], [1, 2, 2.5]].forEach(([hw, y0, y1], i) =>
    B.push(box('body', [-hw, y0, -3.5], [hw, y1, -3.25], tongue(hw, i === 2), { tag: 'collar tongue ' + (i + 1) })));
  // short grey webbing tabs near the armholes, level with the tongue's lower part, as tall as the chest tapes
  for (const s of [-1, 1]) {
    B.push(box('body', s < 0 ? [-4.25, 1.5, -3.5] : [2.25, 1.5, -3.5], s < 0 ? [-2.25, 2.5, -3.25] : [4.25, 2.5, -3.25], flush(c => {
      const col = mul(C.web, G(c, 0.05));
      if (c.face === 'top') return mul(col, 1.15);
      if (c.face === 'bottom') return mul(col, 0.7);
      if (c.face !== 'front') return mul(col, 0.82);
      const j = cellIdx(c.p[1], 1.5);
      return mul(col, (c.eu < px || c.eu > c.fw - px ? 0.84 : 1) * (j === 0 ? 1.06 : 0.9));   // darker sewn-down ends
    }, 'back'), { tag: 'chest webbing tab' }));
  }

  // ---------- collar: tall stand collar, the vest's signature - roll behind the neck (rises highest) + tall side walls
  // beside the head, about three pixels above the shoulder line ----------
  // inside of the collar: the same camo deep in shadow
  const collarIn = c => mul(mix(collarFab(c), C.inner, 0.4), 0.8);
  const CT = -3.25;                                                             // top of the side walls
  B.push(box('body', [-4.75, CT - 0.25, 3.25], [4.75, 0.5, 4.75], c => {
    if (c.face === 'front') return collarIn(c);
    if (c.face === 'bottom') return collarFab(c, 0.6);
    if (c.face === 'top') return c.p[2] < 4.25 ? collarIn(c) : collarFab(c, 1.15);
    let col = collarFab(c);
    if (cellIdx(c.p[1], CT - 0.25) === 0) col = mul(col, 1.12);                // rolled top edge
    else if (c.p[1] > 0) col = mul(col, 0.88);
    return col;
  }, { tag: 'collar back' }));
  for (const s of [-1, 1]) {
    const inn = s < 0 ? 'left' : 'right';
    // bottom sits on the shoulder cap (whose inner end reaches the vest, so no strip of sleeve shows beside the neck);
    // turned 4deg about the inner top edge so the wall flares out a little toward the shoulder while its top stays
    // tight to the head. Lining on the inward face, lit rolled top.
    B.push(box('body', s < 0 ? [-5.25, CT, -3.25] : [4.5, CT, -3.25], s < 0 ? [-4.5, -0.75, 4.5] : [5.25, -0.75, 4.5], c => {
      if (c.face === inn) return collarIn(c);
      if (c.face === 'bottom') return collarFab(c, 0.6);
      if (c.face === 'top') return collarFab(c, 1.15);
      const j = cellIdx(c.p[1], CT);
      if (isPanel(c)) return collarFab(c, j === 0 ? 1.04 : 0.9);
      return collarFab(c, j === 0 ? 1.12 : c.p[1] > -1.25 ? 0.9 : 1);
    }, { tag: 'collar side', rot: [0, 0, s * 4], pivot: [s * 4.5, CT, 0] }));
  }

  // ---------- groin flap right under the strap (lit top edge, double-stitched hem) ----------
  const F0 = STRAP1, F1 = 12.25;
  B.push(box('body', [-3, F0, -3.75], [3, F1, -3.25], flush(c => {
    if (c.face === 'top') return mul(fab(c), 1.06);
    if (c.face === 'bottom') return bind(c, 0.9);
    if (isSide(c)) return bound(c, 0.5);
    let col = fab(c, 0.96);
    const j = cellIdx(c.p[1], F0);
    if (j === 0) col = mul(col, 1.1);
    else if (c.p[1] > F1 - px) col = mix(col, C.bind, 0.45);                   // double-stitched hem
    else if (Math.abs(c.p[0]) > 3 - px) col = mix(col, C.bind, 0.35);
    return col;
  }, 'back'), { tag: 'groin flap' }));
  // ---------- long groin protector: straight top, a little narrower lower down, broad rounded tip below a seam ----------
  // (the original reaches far down the thigh; shortened to the common flap length so the walking legs stay clear)
  const GT = 12, GROWS = [2.75, 2.75, 2.75, 2.75, 2.25, 2.25, 2.25, 1.75, 1.25], GSEAM = 6;   // half-width per cell row
  const gRow = y => Math.max(0, Math.min(GROWS.length - 1, cellIdx(y, GT)));
  B.push(box('body', [-2.75, GT, -3.5], [2.75, 16.5, -3], c => {
    const ax = Math.abs(c.p[0]), y = c.face === 'bottom' ? 16.5 - px / 2 : c.p[1], j = gRow(y), hw = GROWS[j];
    if (isSide(c)) { if (hw < 2.75) return null; return bind(c); }
    if (ax > hw) return null;
    if (c.face === 'top') return inner(c);
    if (c.face === 'back') return inner(c);
    if (c.face === 'bottom') return bind(c);
    let col = fab(c, 0.95);
    const below = j + 1 < GROWS.length ? GROWS[j + 1] : 0;
    if (ax > hw - px || ax > below) col = mix(col, C.bind, 0.5);              // bound outline (sides and exposed bottoms)
    else if (j === GSEAM) col = mul(col, 0.86);                                 // seam where the rounded tip piece starts
    return col;
  }, { tag: 'groin protector' }));

  // ---------- shoulder protectors (follow the arms), open on the armpit side ----------
  for (const s of [-1, 1]) {
    const part = s < 0 ? 'right_arm' : 'left_arm';
    const X = (a, b) => (s < 0 ? [a, b] : [-b, -a]);
    const inn = s < 0 ? 'left' : 'right';
    const ly = c => c.p[1] - 2;                                                 // arm-local y
    // camo right down to the rim binding (the last cell row above the opening; yb = that wall's bottom). The wearer's
    // right protector has a thin tan strip just above the binding, the left one only a faint seam parallel to it.
    const shellFace = (c, yb) => {
      const d = yb - ly(c);
      if (d < 0.5) return bind(c);
      if (s < 0 && d < 1) return mul(C.tan, G(c, 0.05));
      if (s > 0 && d >= 1 && d < 1.5) return fab(c, 1.05 * 0.85);
      return fab(c, 1.05);
    };
    const lining = c => mul(C.lining, G(c, 0.06) * (ly(c) > 2 ? 1 : 0.9));
    // cap over the shoulder
    const [c0, c1] = X(-3.5, 0.5);
    B.push(box(part, [c0, -2.75, -3], [c1, -2, 3], c => {
      if (c.face === 'bottom') return lining(c);
      if (c.face === 'top') return fab(c, 1.1);
      if (c.face === inn) return bound(c, 0.5);
      return fab(c, 1.05);
    }, { tag: 'shoulder cap' }));
    // outer wall (a step lower than the cap, so the shoulder reads rounded); the opening is longest here
    const [o0, o1] = X(-4, -3.5);
    B.push(box(part, [o0, -2.5, -3], [o1, 3.5, 3], c => {
      if (c.face === inn) return lining(c);
      if (c.face === 'bottom') return bind(c);
      if (c.face === 'top') return fab(c, 1.08);
      return shellFace(c, 3.5);
    }, { tag: 'shoulder outer wall' }));
    // front and back walls (inner face = lining, the armpit end bound): the opening is cut on a slant, longest on the
    // outside of the arm, shortest at the armpit - built as three segments stepping up toward the armpit (geometry,
    // not cut-out cells, so no dashed seam runs along the cut)
    const SEG = [[-3.5, -2, 3.5], [-2, -0.5, 3], [-0.5, 0.5, 2.5]];            // arm-local x span (right arm), bottom
    for (const zz of [[-3, -2.5], [2.5, 3]]) {
      const face = zz[0] < 0 ? 'front' : 'back', toArm = zz[0] < 0 ? 'back' : 'front';
      SEG.forEach(([a, b, yb], i) => {
        const [f0, f1] = X(a, b);
        B.push(box(part, [f0, -2, zz[0]], [f1, yb, zz[1]], c => {
          if (c.face === 'top') return fab(c, 1.05);
          if (c.face === toArm) return lining(c);
          if (c.face === 'bottom' || c.face === inn) return bind(c);           // bound opening edge, its steps, the armpit end
          if (c.face === face) return shellFace(c, yb);
          return fab(c);                                                        // pressed against the next segment (hidden)
        }, { tag: 'shoulder ' + face + ' wall ' + (i + 1) }));
      });
    }
    // short grey webbing tab on the front, just above the rim; the dark teal arm strap hangs from it
    const [g0, g1] = X(-1.75, -0.75);                                          // (clear of the wall's step at -0.5)
    B.push(box(part, [g0, 1.5, -3.25], [g1, 3, -3], flush(c => {
      const col = mul(C.web, G(c, 0.05));
      if (c.face === 'top') return mul(col, 1.12);
      if (c.face === 'bottom') return mul(col, 0.7);
      if (c.face !== 'front') return mul(col, 0.82);
      const j = cellIdx(ly(c), 1.5);
      return mul(col, j === 0 ? 1.08 : j === 2 ? 0.88 : 1);
    }, 'back'), { tag: 'shoulder webbing tab' }));
    // dark teal arm strap down the front of the upper arm (its top is inside the wall, right behind the tab)
    const [a0, a1] = X(-1.75, -0.75);
    B.push(box(part, [a0, 1, -2.875], [a1, 4.5, -2.375], c => {
      const col = mul(C.strap, G(c, 0.05));
      if (c.face === 'bottom') return mul(col, 0.8);
      if (c.face !== 'front') return mul(col, 0.85);
      return mul(col, ly(c) > 4.5 - px ? 0.9 : 1.08);
    }, { tag: 'arm strap' }));
    // short camo roll lying front-to-back on the shoulder, crimped silver end facing forward (octagon: square + a 45deg
    // copy 0.25 shorter at each end, so the two front faces never share a plane)
    const [k0, k1] = X(-1.5, -0.5), kc = (k0 + k1) / 2;
    const roll = c => {
      if (c.face === 'front') return mul(C.cap, G(c, 0.05) * (c.p[1] - 2 < -3.25 ? 1.1 : 0.92));
      if (c.face === 'back') return mul(fab(c), 0.8);
      return fab(c, c.face === 'top' ? 1.12 : c.face === 'bottom' ? 0.7 : 1);
    };
    B.push(box(part, [k0, -3.75, -1.5], [k1, -2.75, 1], roll, { tag: 'shoulder roll' }));
    B.push(box(part, [k0, -3.75, -1.25], [k1, -2.75, 0.75], roll, { tag: 'shoulder roll (45deg)', rot: [0, 0, 45], pivot: [kc, -3.25, 0] }));
  }
  return { S: K.S, px: K.px, cell: K.cell, boxes: B };
};
