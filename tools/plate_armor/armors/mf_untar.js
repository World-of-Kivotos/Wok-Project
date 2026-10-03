// ================= MF-UNTAR body armor — EFT reference (tarkov.dev 5ab8e4ed86f7742d8e50c7fa) =================
// Soft vest in bright UN blue satin nylon (broad soft sheen, a little grime), no plates, no arm armour. Reference render:
// wide shoulder straps rising above the shoulders, each with two short rows of grey loop webbing (each row two segments);
// a deep U neckline between them with a dark navy bound rim (the dark navy mesh lining shows inside the neckline and the
// big armholes); on the upper chest a short, even, duller grey-blue pocket flap with only a hairline crease on top, and a
// charcoal name patch with a thin silver border and the word "UNTAR" in silver capitals whose upper half covers the flap's
// lower edge (its lower half sits on the bright fabric, a thin strip of bright fabric under it); below that, on the front
// only, two rows of teal-blue slots in a thin black lattice, then four black webbing rows with bare blue fabric between
// them that wrap round the sides (the side panel under the armhole is plain above the first black row), vertical bar tacks
// through them all; a plain blue strip down to the rolled hem. The back is not visible in the render: plain blue, bound edges.
// The lettering is too fine for the 2x texture (the whole word is only ten texels wide), so it is built from small raised
// strokes on a quarter-pixel grid (a 3x4 pixel font, N four wide), like an embroidered/embossed patch.
ARMORS.mf_untar = function (mode) {
  const K = kit('M');                       // always the 2x style
  const { G, isPanel, isSide } = K;
  const B = [];
  // albedo (the viewer / MC light front faces at ~74 %): rendered targets from the reference - fabric ~#3e76a2,
  // loop flap ~#41607c (even, no dark band), slot rows ~#34586e, black webbing ~#34393a,
  // strap webbing ~#5e6163, patch ~#3b3d3e, lining ~#1c3b50
  const BLUE = hex('#5cade6'), LOOP = hex('#5781a6'), WEB = hex('#474d4f'), GREYWEB = hex('#7f8386'),
    LINING = hex('#26506c'), PATCH = hex('#4b4e50'), FRAME = hex('#dce1e4'), TEXT = hex('#eef1f3'),
    SLOT = hex('#4a7b98'), NAVY = hex('#1f3f5a');
  const md = (v, m) => ((v % m) + m) % m;
  const Y_ARM = 6.5, HEM = 12.5;            // armhole bottom (front panel sides), hem

  // satin nylon: a faint low-frequency sheen, a little brighter on the upper chest, a few soft grey grime smudges
  // (only the strongest fbm peaks, so a handful of larger soft smudges rather than a blotchy all-over pattern), grain
  const GRIME = hex('#3f5f78');
  const fab = (c, k = 1, base = BLUE) => {
    const q = c.p;
    const n = fbm(q[0] * 0.35 + 5, q[1] * 0.3 + 2, q[2] * 0.35 + 9);
    const g = sm(clamp01((fbm(q[0] * 0.6 + 21, q[1] * 0.5 + 4, q[2] * 0.6 + 13) - 0.64) * 4));
    const v = 1.04 - 0.08 * clamp01((q[1] - 2) / 10);
    return mul(mix(base, GRIME, 0.2 * g), k * (0.95 + 0.1 * n) * v * G(c, 0.05));
  };
  const lining = c => mul(LINING, G(c, 0.08));

  // ---------------- shell ----------------
  // front neckline: a deep U between the straps, cut per texel; one texel of dark navy binding runs round the cut
  const NW = 1.75, ND = 1.5;
  const neck = x => { const a = Math.abs(x); return a >= NW ? 0 : ND * Math.sqrt(1 - (a / NW) ** 2); };
  const cut = (x, y) => y < neck(x);
  const bound = (x, y) => [[0, -0.5], [-0.5, 0], [0.5, 0]].some(([dx, dy]) => cut(x + dx, y + dy));
  const front = c => {
    const x = c.p[0], y = c.p[1], ax = Math.abs(x);
    if (c.face === 'back') return null;                                        // lies on the body
    if (c.face === 'top') return neck(x) > 0 ? null : fab(c);                  // (under the strap tops / inside the head)
    if (cut(x, y)) return null;                                                // the neckline is cut through every face
    if (c.face === 'bottom') return mul(fab(c), 0.8);
    if (isSide(c)) return y < Y_ARM ? lining(c) : mul(fab(c), 0.9);            // armhole edge: navy lining
    const col = fab(c);
    if (ax < NW + 0.5 && bound(x, y)) return mix(mul(col, 0.65), NAVY, 0.5);   // bound neckline: dark navy rim
    if (y > HEM - 0.5) return mul(col, 0.82);                                  // rolled hem
    if (ax > 4 && y < Y_ARM) return mul(col, 0.88);                            // bound armhole
    if (ax >= 2 && y >= 2 && y < 2.5) return mul(col, 0.9);                    // seam where the strap joins the chest
    return col;
  };
  B.push(box('body', [-4.5, 0, -2.75], [4.5, HEM, -2], front, { tag: 'front panel' }));
  const back = c => {
    const x = c.p[0], y = c.p[1], ax = Math.abs(x);
    if (c.face === 'front') return null;
    if (c.face === 'top') return fab(c, 0.95);
    if (c.face === 'bottom') return mul(fab(c), 0.8);
    if (isSide(c)) return y < Y_ARM ? lining(c) : mul(fab(c), 0.9);
    const col = fab(c);
    if (y > HEM - 0.5) return mul(col, 0.82);
    if (y < 0.5 && ax < 2) return mul(col, 0.85);                              // bound neckline behind the neck
    if (ax > 4 && y < Y_ARM) return mul(col, 0.88);
    if (ax >= 2 && y >= 2 && y < 2.5) return mul(col, 0.9);
    return col;
  };
  B.push(box('body', [-4.5, 0, 2], [4.5, HEM, 2.75], back, { tag: 'back panel' }));
  // side walls under the armholes (front / back faces lie inside the panels): plain fabric above the first black row
  const lower = c => {
    if (isPanel(c)) return null;
    if (c.face === 'top') return lining(c);
    if (c.face === 'bottom') return mul(fab(c), 0.8);
    return mul(fab(c), c.p[1] > HEM - 0.5 ? 0.82 : 0.92);
  };
  B.push(box('body', [-4.5, 7, -2], [4.5, HEM, 2], lower, { tag: 'side walls' }));

  // ---------------- shoulder straps ----------------
  // over the shoulder: only the half-pixel just outside the head (no slab inside the head: it would poke out beside the
  // jaw when the head turns), from the front panel to the back panel; two short rows of grey loop webbing on each
  // strap under the chin
  const strapTop = c => {
    if (c.face === 'bottom') return null;
    const col = fab(c, 1.02);
    return c.face === 'top' ? col : mul(col, 0.9);
  };
  const strapWeb = c => {
    if (c.face === 'back') return null;                                        // sewn flat on the strap
    const col = mul(GREYWEB, G(c, 0.08));
    if (!isPanel(c)) return mul(col, c.face === 'top' ? 1 : 0.75);
    const ax = Math.abs(c.p[0]);
    return ax > 3 && ax < 3.5 ? mul(col, 0.72) : col;                          // seam between the two segments
  };
  for (const s of [-1, 1]) {
    const X = (a, b) => (s < 0 ? [-b, -a] : [a, b]);
    const [t0, t1] = X(4, 4.5);
    B.push(box('body', [t0, -0.5, -2.75], [t1, 0, 2.75], strapTop, { tag: 'shoulder strap top' }));
    const [a, b] = X(2, 4.5);
    for (const y0 of [0.5, 1.25]) B.push(box('body', [a, y0, -3], [b, y0 + 0.5, -2.75], strapWeb, { tag: 'strap webbing' }));
  }

  // ---------------- chest: pocket flap, silver-bordered name patch, UNTAR lettering ----------------
  // a short flap, one even dull grey-blue piece: only about three quarters of a pixel of it shows above the patch (its
  // lower edge disappears behind the patch's upper half); the fold is just a hairline crease on its top face, not a band
  const loopFlap = c => {
    if (c.face === 'back') return null;
    const col = mul(LOOP, G(c, 0.12));
    if (c.face === 'top') return mul(col, 0.8);                                // hairline crease along the folded top edge
    if (!isPanel(c)) return mul(col, 0.8);
    return col;
  };
  B.push(box('body', [-3.25, 2.75, -3], [3.25, 4.5, -2.75], loopFlap, { tag: 'loop flap' }));
  // silver border: a slightly larger plate behind the patch, so only a thin rim of it shows around the patch. Border and
  // patch reach back into the front panel (back faces hidden) so the part below the flap sits on the fabric instead of
  // hovering in front of it; each layer (flap, border, patch, letters) stands only an eighth of a pixel proud of the
  // one below: a flat sewn-on patch, not a stack
  const frame = c => (c.face === 'back' ? null : mul(FRAME, (isPanel(c) ? 1 : 0.8) * G(c, 0.04)));
  B.push(box('body', [-3, 3.5, -3.125], [3, 5.75, -2.625], frame, { tag: 'patch border' }));
  const patch = c => {
    if (c.face === 'back') return null;
    if (!isPanel(c)) return mul(FRAME, 0.8);                                   // the silver edge wraps round the patch
    return mul(PATCH, G(c, 0.08));
  };
  B.push(box('body', [-2.75, 3.75, -3.25], [2.75, 5.5, -2.75], patch, { tag: 'name patch' }));
  // UNTAR: 3x4 pixel font on a quarter-pixel grid (N four wide), one-cell gaps, strokes an eighth of a pixel proud
  const GLYPHS = [
    [3, [[0, 0, 1, 4], [2, 0, 3, 4], [1, 3, 2, 4]]],                          // U
    [4, [[0, 0, 1, 4], [3, 0, 4, 4], [1, 1, 2, 2], [2, 2, 3, 3]]],            // N
    [3, [[0, 0, 3, 1], [1, 1, 2, 4]]],                                         // T
    [3, [[1, 0, 2, 1], [0, 1, 1, 4], [2, 1, 3, 4], [1, 2, 2, 3]]],            // A
    [3, [[0, 0, 1, 4], [1, 0, 2, 1], [2, 1, 3, 2], [1, 2, 2, 3], [2, 3, 3, 4]]], // R
  ];
  const Q = 0.25, TX0 = -2.5, TY0 = 4.125;
  const letter = c => {
    if (c.face === 'back') return null;
    return mul(TEXT, c.face === 'front' || c.face === 'top' ? 1 : c.face === 'bottom' ? 0.6 : 0.75);
  };
  let cx = 0;
  for (const [w, rects] of GLYPHS) {
    for (const [c0, r0, c1, r1] of rects)
      B.push(box('body', [TX0 + (cx + c0) * Q, TY0 + r0 * Q, -3.375], [TX0 + (cx + c1) * Q, TY0 + r1 * Q, -3.125], letter, { tag: 'UNTAR lettering' }));
    cx += w + 1;
  }

  // ---------------- MOLLE field ----------------
  // bar tacks every 1.5 px: front cells centred on x 0, ±1.5, ±3, ±4.5 (boxes 19 texels wide, so the cells land on
  // those centres); side cells continue the rhythm round the corner
  const tack = c => (isPanel(c) ? md(Math.round(c.p[0] / 0.5), 3) === 0 : md(Math.round((c.p[2] + 3) / 0.5 - 0.5), 3) === 2);
  const webRow = c => {
    if (c.face === 'top') return mul(WEB, G(c, 0.06));
    if (c.face === 'bottom') return mul(WEB, 0.7);
    if (c.face === 'back') return mul(WEB, 0.8);
    return mul(WEB, (tack(c) ? 0.8 : 1) * G(c, 0.08));
  };
  // front only: two rows of teal-blue slots in a thin black lattice (the bar tacks only part-way to black so the grid
  // reads thin); its thin ends are the lattice's dark edge at the corners, the side panels stay plain fabric
  const slots = c => {
    if (c.face === 'bottom') return null;                                      // rests on the first black row
    if (c.face === 'top') return mul(WEB, G(c, 0.06));                         // top line of the lattice
    if (c.face === 'back') return mul(WEB, 0.7);                               // (only the corner lip shows)
    if (isSide(c)) return mul(WEB, 0.85 * G(c, 0.06));
    const col = fab(c, 1, SLOT);
    return tack(c) ? mix(col, WEB, 0.6) : col;
  };
  B.push(box('body', [-4.75, 6, -3], [4.75, 7.5, -2.75], slots, { tag: 'MOLLE slot rows' }));
  // thin black strap between the two slot rows closes the lattice (each slot row keeps a bit over half a pixel of blue)
  const latticeStrap = c => (c.face === 'back' ? null : mul(WEB, (c.face === 'bottom' ? 0.7 : c.face === 'top' ? 1 : 0.9) * G(c, 0.06)));
  B.push(box('body', [-4.625, 6.625, -3.125], [4.625, 6.875, -2.875], latticeStrap, { tag: 'MOLLE lattice strap' }));
  // four black webbing rows wrapping round the sides; they end inside the arm (short of the back panel and the arm's
  // back face) so no stubs show from behind
  for (const y0 of [7.5, 8.5, 9.5, 10.5]) B.push(box('body', [-4.75, y0, -3], [4.75, y0 + 0.5, 1.5], webRow, { tag: 'MOLLE row' }));

  return { S: K.S, px: K.px, cell: K.cell, boxes: B };
};
