// ================= FORT Gladiator-S plate carrier (Gray) — EFT reference =================
// tarkov.dev render 69d3708c8d8009073d0a9df4 (slug fort-gladiator-s-plate-carrier-gray; body armour, 3.31 kg, collar +
// side soft armour, no groin). A plain slick carrier in a slate grey-green with a slight teal cast, worn lighter on the
// raised edges. Render: a tall padded neck collar rolling round the neck in one piece, its front narrowing to a V-shaped
// point at the chest centre with a small webbing patch sewn on it and two loops hanging side by side onto the chest; padded shoulder straps with a flat black band across their tops under
// the collar (no buckles on the chest corners); the whole front covered in olive-brown MOLLE tape over the cooler
// blue-grey cloth: three rows on the upper chest, two small tabs, then - from about halfway down - the cummerbund's
// front flap split down the middle with four rows per half; big side panels (cummerbund wings) with MOLLE and a black buckle at their top front
// corner; brown padded mesh lining inside; two small tabs hanging under the hem.
ARMORS.gladiator_s_gray = function (mode) {
  const K = kit('M');                          // always the 2x style, whatever mode is asked for
  const { edge, G, isPanel, isSide, px } = K;
  const B = [];

  // ---------------- palette (albedo; lit front faces render darker) ----------------
  const GRY = hex('#6a7c7a'), WORN = hex('#879794'), DIRT = hex('#565f58');   // slate grey with a teal cast (Cordura)
  const TAPE = hex('#4d4c3c');                 // MOLLE webbing is olive-brown against the cooler cloth
  const LINING = hex('#333b37');               // collar / strap lining
  const MESH = hex('#6f6049');                 // brown padded mesh inside the panels
  const STRAP = hex('#25282c');                // black shoulder / cummerbund webbing and buckles
  const md = (v, m) => ((v % m) + m) % m;

  // fabric: slow tone drift + soft darker dirt blotches + lighter wear, sampled on the half-pixel grid (1-3 px shapes)
  const fab = (c, k = 1, amt = 0.06, o = 0) => {
    const q = snap([c.p[0] + o, c.p[1] + o * 0.6, c.p[2] - o * 0.4], px);
    const tone = 0.95 + 0.1 * fbm(q[0] * 0.3 + 7, q[1] * 0.3 + 3, q[2] * 0.3 + 1);
    const d = sm(clamp01((fbm(q[0] * 0.45 + 11, q[1] * 0.4 + 5, q[2] * 0.45 + 17) - 0.55) * 3));
    const w = sm(clamp01((fbm(q[0] * 0.7 + 31, q[1] * 0.7 + 9, q[2] * 0.7 + 3) - 0.64) * 3.5));
    return mul(mix(mix(GRY, DIRT, d * 0.6), WORN, w * 0.32), k * tone * G(c, amt) * (1.04 - 0.008 * Math.max(0, Math.min(16, c.p[1]))));
  };
  // MOLLE: the webbing is the DARKER, olive-brown band on the render - continuous tape rows a pixel tall (lit upper
  // half), the cooler blue-grey cloth showing in the half pixel between rows (period 1.5 px); the thread bar tacks are
  // only a faint lift of the tape tone so the rows stay solid bands instead of breaking into squares
  const webbing = (c, col, y0, rows, h0, h1, { side = false } = {}) => {
    if (!(isPanel(c) || (side && isSide(c)))) return null;
    const h = isPanel(c) ? c.p[0] : c.p[2];
    if (h < h0 || h > h1) return null;
    for (let i = 0; i < rows; i++) {
      const dy = c.p[1] - (y0 + i * 1.5);
      if (dy >= 0 && dy < 1) {
        const tape = mix(col, TAPE, 0.35);
        if (md(h - h0, 1.5) < 0.5) return mul(tape, dy < 0.5 ? 0.85 : 0.75);   // bar tacks (barely lighter)
        return mul(tape, dy < 0.5 ? 0.8 : 0.7);                                 // webbing tape
      }
    }
    return null;
  };

  // ---------------- carrier ----------------
  // front plate bag: three MOLLE rows on the upper chest, the top one tucked under the collar's V point and the loops
  // (the lower front is the flap); it runs down to the belt
  const bag = c => {
    if (c.face === 'top') return mul(MESH, G(c, 0.1));                         // padded mesh lining at the neckline
    if (c.face === 'back') return mul(MESH, 0.9);
    const col = fab(c);
    if (c.face === 'front') return edge(c, webbing(c, col, 1.5, 3, -4.25, 4.25) || col);
    return edge(c, mul(col, 0.9));
  };
  // (its top runs up behind the collar roll, so no slot under the collar shows the shirt from the side)
  B.push(box('body', [-4.25, 0.25, -3.25], [4.25, 11.5, -2.5], bag, { tag: 'front plate bag' }));
  // cummerbund front flap over the lower front: starts about halfway down and is a little taller than the upper chest,
  // split down the middle, four MOLLE rows per half
  const flap = c => {
    const col = fab(c, 1.02, 0.06, 2.9);
    if (c.face === 'back') return mul(col, 0.7);
    if (c.face !== 'front') return edge(c, mul(col, 0.85), { stitch: false });
    if (c.p[0] > -0.5 && c.p[0] < 0.5) return mul(col, 0.6);                   // the split between the two halves
    const w = c.p[0] < 0 ? webbing(c, col, 6, 4, -4, -0.5) : webbing(c, col, 6, 4, 0.5, 4);   // rows stop at the split
    return edge(c, w || col);
  };
  B.push(box('body', [-4, 5.5, -3.5], [4, 11.5, -3.25], flap, { tag: 'front flap' }));
  // small dark webbing tabs (the MOLLE tape's tone), lit along their top edge: two where the flap closes onto the upper
  // chest, two hanging under the hem
  const webTab = o => c => {
    const col = mix(fab(c, 1, 0.06, o), STRAP, 0.45);
    if (c.face === 'top') return mul(col, 1.25);
    if (c.face !== 'front') return mul(col, 0.8);
    return mul(col, c.ev < px ? 1.22 : 1);
  };
  for (const s of [-1, 1]) {
    const [t0, t1] = s < 0 ? [-1.75, -0.75] : [0.75, 1.75];
    B.push(box('body', [t0, 5, -3.75], [t1, 6, -3.25], webTab(4.4), { tag: 'flap tab' }));
  }
  for (const x0 of [-2, 1]) B.push(box('body', [x0, 11.5, -3.5], [x0 + 1, 12.25, -3], webTab(6.1), { tag: 'hem tab' }));
  const back = c => {
    if (c.face === 'top') return mul(MESH, G(c, 0.1));
    if (c.face === 'front') return mul(MESH, G(c, 0.08));                        // padded mesh inside the back panel
    const col = fab(c);
    return edge(c, (c.face === 'back' && webbing(c, col, 1.5, 7, -3.75, 3.75)) || col);
  };
  B.push(box('body', [-4.25, 0.5, 2.5], [4.25, 11.5, 3.25], back, { tag: 'back plate bag' }));
  // cummerbund wings: MOLLE all over the sides
  const cumm = c => {
    const col = fab(c, 0.97, 0.06, 1.3);
    return edge(c, webbing(c, col, 5, 4, -2.5, 2.5, { side: true }) || col, { stitch: false });
  };
  B.push(box('body', [-4.5, 4.5, -2.75], [4.5, 10.5, 2.75], cumm, { tag: 'cummerbund' }));
  // black buckle on the top front corner of each wing
  const buckle = c => mul(STRAP, c.face === 'top' ? 1.6 : c.face === 'front' ? (c.p[1] < 4.75 ? 1.35 : 1.05) : 0.9);
  for (const s of [-1, 1]) B.push(box('body', s < 0 ? [-4.75, 4.25, -3] : [4.25, 4.25, -3], s < 0 ? [-4.25, 5.25, -2.5] : [4.75, 5.25, -2.5], buckle, { tag: 'wing buckle' }));

  // ---------------- tall padded collar ----------------
  // The head hides most of it: what shows is the front of the roll under the chin (in front of the hat layer), the
  // side walls beside the head (flaring out a little) and the tall back roll. Outside grey-green with a lighter rolled
  // rim, lining toward the neck. The front is ONE continuous roll that narrows to a V-shaped point at the chest centre
  // (no opening), with the two-loop webbing tab sewn onto the point.
  const lin = c => mul(LINING, G(c, 0.08));
  const rim = c => mul(mix(fab(c, 1, 0.05, 7.7), WORN, 0.35), 1.05);
  const collarFab = c => fab(c, 0.96, 0.06, 7.7);
  const halfFront = s => c => {
    if (c.face === 'back' || c.face === 'bottom') return lin(c);
    if (c.face === 'top') return rim(c);
    const col = collarFab(c);
    if (c.face === (s < 0 ? 'left' : 'right')) return mul(col, 0.8);           // inner ends: a soft crease, not an opening
    if (c.face === 'front') {
      if (c.ev < px) return rim(c);                                              // lit rolled top edge
      if (c.ev > c.fh - px) return mul(col, 0.78);                              // shadow under the roll
      if ((s < 0 ? c.fw - c.eu : c.eu) < px) return col;                          // no dark edge where the halves meet
    }
    return edge(c, col, { stitch: false });
  };
  // the two front halves slope down toward the middle (turned about a point near their outer lower corner) so the
  // roll's front makes the V of the render; their outer ends run over the black strap's top out to the side walls,
  // so from the front the roll is one piece from the V point up past the strap to the side of the head
  for (const s of [-1, 1]) {
    const [f0, f1] = s < 0 ? [-4.5, -0.5] : [0.5, 4.5];
    B.push(box('body', [f0, -0.5, -4.75], [f1, 1.25, -3.25], halfFront(s), { tag: 'collar front half', rot: [0, 0, s * -14], pivot: [s * 3.5, 1.25, -4] }));
  }
  // the turned halves leave a wedge between their inner ends: close it with collar fabric set a quarter pixel behind
  // their fronts, so the roll runs on through the middle down to the point (rim / fabric / shadow bands at the same
  // heights as on the halves). A thin strip above it, between the chin and the dip of the roll's top edge, shows the
  // collar's dark inside instead of the shirt.
  const bridge = c => {
    if (c.face === 'back' || c.face === 'bottom') return lin(c);
    if (c.face === 'top') return rim(c);
    const col = collarFab(c);
    if (c.face !== 'front') return mul(col, 0.85);
    if (c.ev < px) return rim(c);
    if (c.ev > c.fh - px) return mul(col, 0.78);
    return col;
  };
  B.push(box('body', [-0.75, 0.25, -4.5], [0.75, 2, -3.5], bridge, { tag: 'collar front bridge' }));
  B.push(box('body', [-1.25, 0, -4.5], [1.25, 0.25, -3.5], c => (c.face === 'front' || c.face === 'top' ? mul(lin(c), 1.15) : lin(c)), { tag: 'collar front inside' }));
  // the shoulder straps are padded vest fabric (the front's top corners running up under the collar); the only black on
  // them is a flat strap band across the shoulder top under the collar, painted on the side walls' lower outer edge
  // below - there are no buckles on the chest corners
  // short two-loop webbing tab: a square patch sewn ON the roll's V point (standing a little proud of the halves' fronts)
  // with a vertical centre seam, and below it the two loops hanging SIDE BY SIDE onto the chest, down over the first
  // MOLLE row
  const tabCol = c => fab(c, 0.94, 0.06, 5.5);
  const tabCell = c => Math.floor((c.p[0] + 0.75) / px);                          // 0 | 1 (centre) | 2
  const patch = c => {
    const col = tabCol(c);
    if (c.face === 'top') return mul(col, 1.2);
    if (c.face === 'bottom') return mul(col, 0.6);
    if (c.face !== 'front') return mul(col, 0.8);
    const top = c.p[1] < 1 + px;
    const k = tabCell(c) === 1 ? (top ? 0.86 : 0.7) : 1;                           // centre seam between the two loops
    return mul(col, k * (top ? 1.14 : 0.9));                                       // lit top row, shaded lower row
  };
  B.push(box('body', [-0.75, 1, -5], [0.75, 2, -4.5], patch, { tag: 'collar tab patch' }));
  const loops = c => {
    const col = tabCol(c);
    if (c.face === 'top') return mul(col, 0.7);
    if (c.face !== 'front') return mul(col, 0.78);
    if (tabCell(c) === 1) return mul(col, 0.6);                                    // gap between the two straps
    return mul(col, c.p[1] > 3 - px ? 0.9 : 1);                                    // folded strap ends
  };
  // (as tall as the patch, ending just under the first MOLLE row like on the render)
  B.push(box('body', [-0.75, 2, -3.75], [0.75, 3, -3.25], loops, { tag: 'collar tab loops' }));
  const wall = s => c => {
    if (c.face === (s < 0 ? 'left' : 'right')) return lin(c);                    // toward the neck
    if (c.face === 'bottom') return mul(lin(c), 0.85);
    if (c.face === 'top') return rim(c);
    const col = collarFab(c);
    if (c.ev < px) return rim(c);
    // black shoulder strap running over the shoulder under the collar wall (its lower outer edge)
    if (c.face === (s < 0 ? 'right' : 'left') && c.ev > c.fh - 1 && Math.abs(c.p[2]) < 1) return mul(STRAP, G(c, 0.06));
    return edge(c, col, { stitch: false });
  };
  // side walls: their tops flare well out from the neck, their lower edges come in to the neck and down to meet the
  // front halves' outer ends
  for (const s of [-1, 1]) {
    const xa = s < 0 ? [-5.5, -4.5] : [4.5, 5.5];
    B.push(box('body', [xa[0], -2.25, -3.75], [xa[1], 0.25, 5.25], wall(s), { tag: 'collar side', rot: [0, 0, s * 12], pivot: [s * 4.5, -2.25, 0] }));
  }
  B.push(box('body', [-4.75, -3, 3.25], [4.75, 1, 5.5], c => {
    if (c.face === 'front' || c.face === 'bottom') return lin(c);
    if (c.face === 'top') return c.p[2] < 5 ? lin(c) : rim(c);
    const col = collarFab(c);
    if (c.face === 'back' && c.ev < px) return rim(c);
    return edge(c, col, { stitch: false });
  }, { tag: 'collar back' }));
  return { S: K.S, px: K.px, cell: K.cell, boxes: B };
};
