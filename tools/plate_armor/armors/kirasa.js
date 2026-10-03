// ================= BNTI Kirasa-N — EFT reference (5b44d22286f774172b0c9de8) =================
// Soft police-style vest in steel-navy coated fabric (the render is navy, not green). Tall padded stand collar: the
// collar fabric itself is dark, with channel quilting and dark mesh lining; what makes it read is the BRIGHT piping on
// its top rim and down the lap edge. At the throat the wearer's-LEFT end is the outer layer: it wraps to the front and
// its piped lap edge runs straight down (past a lit closure panel) to the proud rectangular chest flap on the wearer's
// left (stitched top seam, lit folded lower edge, a hard shadow under it). Yoke seam across the right chest level with
// the flap top, a long vertical seam on the wearer's right front with a narrow stitched side tab hugging it, a seam near
// the left side edge, deep armholes (the panels step in toward narrow shoulder straps; light bound edge, dark mesh
// lining behind the cut), a bound hem band on the front and sides, and a longer back panel that carries on below the
// front hem to a rounded, bound lower edge (its dark inside shows under the front hem, so it flares out a little).
// No MOLLE, no pouches. Stitching in the render is DARK (double rows of short dashes) next to a light fold.
ARMORS.kirasa = function (mode) {
  const K = kit(mode), { hd, mid, px, G, isPanel, isSide } = K;
  const A = !hd, HD = hd && !mid;
  const NAVY = hex('#475d78'), RIDGE = hex('#7d95b8'), PIPE = hex('#7086a3'), STITCH = hex('#121821');
  const LINING = hex('#242a33'), MESH = hex('#262d38'), COLLAR = hex('#394d69');
  const B = [];

  // ---------- fabric, seams, edges ----------
  // coated fabric: soft large-scale sheen and slow wrinkles; HD adds the faint square ripstop grid of the original
  const plane = c => (isPanel(c) ? [c.p[0], c.p[1]] : isSide(c) ? [c.p[2], c.p[1]] : [c.p[0], c.p[2]]);
  const gridLine = t => ((Math.floor(t * 4 + 1e-6) % 4) + 4) % 4 === 0;
  const grain = c => {
    if (!HD) return G(c, 0.07);
    const [u, v] = plane(c);
    return 1 + (vnoise(c.p[0] * 6, c.p[1] * 6, c.p[2] * 6) - 0.5) * 0.05 - (gridLine(u) || gridLine(v) ? 0.02 : 0);
  };
  const fab = (c, base = NAVY, k = 1) => {
    const n = fbm(c.p[0] * 0.3 + 5, c.p[1] * 0.24 + 2, c.p[2] * 0.3 + 9);
    const w = fbm(c.p[0] * 0.6 + 13, c.p[1] * 0.45 + 7, c.p[2] * 0.6 + 3);        // slow wrinkles
    return mul(base, k * (0.94 + 0.12 * n) * (0.93 + 0.14 * w) * grain(c));
  };
  // padded panels: the shoulder straps / yoke / shoulder blades catch the light, the hem and the side corners fall off
  const pad = c => (1.18 - 0.028 * c.p[1] + 0.07 * clamp01(1 - c.p[1] / 3)) * (1 - 0.1 * clamp01((Math.abs(c.p[0]) - 3) / 1.5));
  // HD stitch row: short dark dashes (two quarter-px on, one off) so a row reads as thread, not as rivets
  const dash = (col, t) => ((Math.floor(t * 4 + 1000) % 3) !== 2 ? mix(col, STITCH, 0.22) : col);
  const piped = (c, k = 0.75) => mix(fab(c, COLLAR), PIPE, k);
  // texel centre of the cell that contains v on a face that starts at o (keeps seams symmetric and one cell wide)
  const TC = (v, o) => o + (Math.floor((v - o) / px + 1e-6) + 0.5) * px;
  // seam: dark groove (k=0), light fold on side r (k=r); HD adds a DOUBLE stitch row on the other side (k=-r, -3r).
  // Returns [priority, colour]: a groove beats a fold, a fold beats stitching, so crossing seams stay clean.
  const seamAt = (col, d, t, r, aK, rows) => {
    const k = Math.round(d / px);
    if (k === 0) return [3, A ? mul(col, aK) : mix(col, STITCH, mid ? 0.45 : 0.5)];
    if (A) return null;
    if (r && k === r) return [2, mix(col, RIDGE, mid ? 0.38 : 0.42)];
    if (HD && (r ? (k === -r || (rows > 1 && k === -3 * r)) : Math.abs(k) === 1)) return [1, dash(col, t)];
    return null;
  };
  // list entries: [axis across, at, axis along, from, to, fold side, A groove shade, HD stitch rows]. Stitching starts
  // four cells past `from`, so a seam that begins on another seam stops short of that seam's stitch rows (no '+').
  const seams = (c, col, list) => {
    let best = null;
    for (const [ax, at, al, lo, hi, r, aK = 0.74, rows = 2] of list) {
      const t = c.p[al];
      if (t < lo || t > hi) continue;
      const v = seamAt(col, c.p[ax] - at, t, r, aK, rows);
      if (!v || (v[0] === 1 && t < lo + 3.5 * px)) continue;
      if (!best || v[0] > best[0]) best = v;
    }
    return best ? best[1] : col;
  };
  const mesh = c => {
    const col = mul(MESH, G(c, 0.1));
    return HD && ((Math.floor(c.p[0] * 8) + Math.floor(c.p[1] * 8) + Math.floor(c.p[2] * 8)) % 3 === 0) ? mul(col, 0.8) : col;
  };
  const lining = c => {
    const col = mul(LINING, G(c, 0.08));
    return HD && ((Math.floor(c.p[0] * 8) + Math.floor(c.p[1] * 8) + Math.floor(c.p[2] * 8)) % 3 === 0) ? mul(col, 0.82) : col;
  };
  const binding = c => mul(mix(fab(c), RIDGE, 0.25), 0.95);

  // ---------- layout ----------
  // Front, top to bottom: collar band (visible from the chin down to yCol), lit closure panel, flap (FL.y0..FL.y1)
  // whose top lines up with the yoke seam on the other side, side tab from the flap bottom down to the hem band.
  const yArm = A ? 4 : 5.5;          // armhole bottom (top of the side walls)
  const yLow = A ? 11.5 : 11.25;     // front shell / side wall bottom (tucked under the hem band)
  const yHem = A ? [11, 12] : [10.75, 11.75];   // hem band
  // the back panel ends at yLow; below it a separate tail hangs from its outer lower edge, turned TAIL_ROT degrees so
  // its lower edge swings out behind the seat: clear of the legs at rest and up to ~18 deg of back swing (it used to
  // clip from the first step); a full walking stride (~64 deg) still passes through, as with any vest that covers the seat
  const yTail = A ? 13.5 : 13.25, TAIL_ROT = 15;
  const xClose = -0.5;               // piped lap edge of the wearer's-left collar end -> lap strip -> flap edge (vest centre)
  const yCol = A ? 2 : 1.5;          // bottom of the collar front band (a neck ring, not a bib)
  // chest pocket flap (wearer's left); z0 = its front face
  const FL = { x0: xClose, x1: A ? 3.5 : 4, y0: A ? 3 : mid ? 3 : 2.75, y1: A ? 5 : mid ? 4.5 : 4.25, z0: A ? -3.75 : mid ? -3.5 : -3.25 };
  // deep armholes: the panels step in toward narrow shoulder straps (half width at height y; cut-outs above yArm).
  const wAt = A ? (y => (y < 4 ? 3.5 : 4.5))
    : mid ? (y => (y < 2.5 ? 3.5 : y < 4 ? 4 : 4.5))
    : (y => Math.round((3.5 + sm(clamp01((y - 0.5) / 4))) * 4) / 4);
  // rounded lower edge of the tail (unturned coordinates); the corners hang only a little below the front hem.
  // Texels below it are cut out.
  const tailY = A ? (x => (Math.abs(x) < 2.5 ? 13.5 : 12.5))
    : (x => 12.2 + 1.05 * (1 - Math.abs(x / 4.6) ** 3));
  const cutT = (x, y) => y > tailY(x) + 1e-6;
  // yoke seam: the same row as the flap's stitched top edge, so the two read as one seam line across the chest
  const yokeY = A ? 3.5 : TC(FL.y0 + px / 2, 0);
  const xLong = TC(-2.25, -4.5);     // long seam on the wearer's right front
  const TAB = [-3.5, -2.5];          // narrow stitched side tab, hugging the long seam
  const FRONT_SEAMS = [
    [1, yokeY, 0, -4.5, xClose, -1],              // yoke seam across the wearer's right chest, fold above
    [0, xLong, 1, yokeY, 11, 1],                  // long seam on the wearer's right front, fold toward the centre
  ];
  FRONT_SEAMS.push([0, TC(4.25, -4.5), 1, FL.y1, 11, -1, 0.82]);   // seam near the wearer's-left side edge (A: a softer shaded column)
  const bx = TC(-3.25, -4.5), bHi = A ? 12 : 12.25;
  // back (not shown in the render, kept quiet): yoke with a double stitch row, the two long seams with a single one
  const BACK_SEAMS = [[1, yokeY, 0, -4.5, 4.5, -1], [0, bx, 1, yokeY, bHi, 1, 0.74, 1], [0, -bx, 1, yokeY, bHi, -1, 0.74, 1]];
  // side seam down the middle of the side wall (its texel grid starts at the panels' inner faces; A: whole pixels
  // there have centres a quarter off the middle, and at 0 exactly one of them rounds onto the seam)
  const SIDE_SEAMS = [[2, A ? 0 : TC(0, -2.25), 1, 0, 12, 0]];
  const inFlapX = x => x >= FL.x0 && x <= FL.x1;
  const frontFace = c => {
    const x = c.p[0], y = c.p[1];
    let col = fab(c, NAVY, pad(c));
    if (y < yokeY && x < xClose) col = mul(col, 1.05);                               // yoke catches the light
    col = seams(c, col, FRONT_SEAMS);
    if (inFlapX(x) && y >= FL.y1 && y < FL.y1 + px) col = mul(col, HD ? 0.58 : mid ? 0.66 : 0.76);   // hard shadow under the flap
    else if (HD && inFlapX(x) && y >= FL.y1 && y < FL.y1 + 2 * px) col = mul(col, 0.84);
    if (x >= xClose - px && x < xClose && y >= yCol && y < FL.y1) col = mul(col, 0.82);   // shadow beside the lap edge
    return col;
  };
  const backFace = c => {
    const x = c.p[0], y = c.p[1], s = x < 0 ? -1 : 1, base = fab(c, NAVY, 0.97 * pad(c));
    if (y >= yLow) {
      // on the tail: n cells in from the rounded edge, below or toward the side (the steps of the curve are bound too);
      // the seams stop at the dark row
      const rim = n => cutT(x, y + n * px) || (Math.abs(x + s * n * px) < 4.5 && cutT(x + s * n * px, y));
      if (rim(1)) return mix(fab(c, NAVY, 0.95), RIDGE, 0.3);                                       // bound lower edge
      if (rim(2)) return mul(base, A ? 0.82 : 0.78);                                                // dark row inside the binding
    }
    return seams(c, base, BACK_SEAMS);
  };

  // ---------- shell: one full-height front panel and one back panel (no joins across the visible faces) ----------
  // Above yArm the armhole notches are cut out. The panels own their full width down to the hem, corner columns
  // included; below yArm a thin side wall fills the side between them (no face of one lies on a face of the other).
  const sideFace = c => {                                                                            // the vest's side below the armhole
    const col = seams(c, fab(c, NAVY, 0.96), SIDE_SEAMS);
    return c.p[1] < yArm + px ? mix(col, RIDGE, 0.3) : col;                                          // bound armhole edge
  };
  const shell = outer => c => {
    const back = outer === 'back', y = c.p[1], ax = Math.abs(c.p[0]);
    if (c.face !== 'bottom' && y < yArm && ax > wAt(y) + 1e-6) return null;                         // armhole notch
    if (y >= yArm && isSide(c)) return sideFace(c);                                                  // corner column's side
    if (c.face === outer) {
      const col = back ? backFace(c) : frontFace(c);
      // bound armhole edge: the column beside the cut and the row under each step (a plain light binding, no stitching)
      return hd && y < yArm && (ax + px > wAt(y) + 1e-6 || (y > px && ax > wAt(y - px) + 1e-6)) ? mix(col, RIDGE, 0.3) : col;
    }
    if (isPanel(c)) return lining(c);                                                               // inside
    if (c.face === 'top') return binding(c);                                                        // shoulder strap top
    if (c.face === 'bottom') return back ? mul(lining(c), 0.8) : mul(LINING, 0.9);
    return HD && Math.abs(c.p[2]) > 2.5 ? binding(c) : lining(c);                                    // armhole edge: dark inside
  };
  B.push(box('body', [-4.5, 0, -2.75], [4.5, yLow, -2.25], shell('front'), { tag: 'front panel' }));
  B.push(box('body', [-4.5, 0, 2.25], [4.5, yLow, 2.75], shell('back'), { tag: 'back panel' }));
  // the longer back tail: hinged on the back panel's outer lower edge and turned out behind the seat, rounded and bound
  B.push(box('body', [-4.5, yLow, 2.25], [4.5, yTail, 2.75], c => {
    const x = c.p[0], y = c.p[1], s = x < 0 ? -1 : 1;
    const xe = isSide(c) ? x - s * px / 2 : x, ye = c.face === 'bottom' ? y - (A ? 1e-3 : px / 2) : y;
    if (cutT(xe, ye)) return null;                                                                   // rounded lower edge
    if (c.face === 'back') return backFace(c);
    if (c.face === 'front') return mul(lining(c), 0.8);                                              // inside, facing the seat
    if (c.face === 'top') return lining(c);                                                          // hidden hinge
    if (c.face === 'bottom') return mix(fab(c, NAVY, 0.7), RIDGE, 0.2);                              // bound edge seen end-on
    const col = fab(c, NAVY, y > yHem[1] ? 0.6 : 0.95);                                              // sides
    return cutT(xe, y + px) ? mix(col, RIDGE, 0.25) : col;
  }, { tag: 'back tail (turned out behind the seat)', rot: [TAIL_ROT, 0, 0], pivot: [0, yLow, 2.75] }));
  // dark mesh lining just behind the armhole cut-outs (what the original shows inside its deep armholes). It only
  // spans the notched rows, starts a little below the shoulder top (hidden there by the collar) and sits a hair in
  // from the vest's side, so none of its faces lies on a face of the panel around it.
  let yN = 0;                                                                                        // bottom of the notched rows
  while (yN < yArm && wAt(yN + px / 2) < 4.5 - px / 2 - 1e-6) yN += px;
  for (const s of [-1, 1]) {
    const xs = s < 0 ? [-4.375, -3.375] : [3.375, 4.375];
    const lin = c => mul(lining(c), isPanel(c) || c.face === 'top' ? 1 : 0.8);
    B.push(box('body', [xs[0], 0.25, -2.5], [xs[1], yN, -2.25], lin, { tag: 'armhole lining (front)' }));
    B.push(box('body', [xs[0], 0.25, 2.25], [xs[1], yN, 2.5], lin, { tag: 'armhole lining (back)' }));
  }
  // side walls below the armholes: a thin skin between the two panels' corner columns, just outside the skin layer
  for (const s of [-1, 1]) {
    const inner = s < 0 ? 'left' : 'right';
    B.push(box('body', [s < 0 ? -4.5 : 4.25, yArm, -2.25], [s < 0 ? -4.25 : 4.5, yLow, 2.25], c => {
      if (c.face === 'top') return lining(c);                                                       // armhole bottom: lining
      if (isPanel(c)) return null;                                                                   // ends: behind the panels' insides
      if (c.face === inner || c.face === 'bottom') return fab(c, NAVY, 0.8);                       // hidden
      return sideFace(c);
    }, { tag: 'side wall' }));
  }

  // ---------- hem band: front and sides only, nearly flush, its ends tucked into the back panel ----------
  const hemW = A ? 4.75 : 4.625, hemZ = A ? -3 : -2.875;
  B.push(box('body', [-hemW, yHem[0], hemZ], [hemW, yHem[1], A ? 2.5 : 2.625], c => {
    const col = fab(c, NAVY, 0.92 * (1 - 0.1 * clamp01((Math.abs(c.p[0]) - 3) / 1.5)));
    if (c.face === 'back') return mul(col, 0.8);                                                     // end, seen beside the back panel
    if (c.face === 'bottom') return mul(col, 0.62);
    if (c.face === 'top') return mix(col, RIDGE, 0.2);
    if (A) return mul(col, 0.8);                                                                     // one row: darker than the panel
    const v = c.p[1] - yHem[0];
    if (v < px) return mix(col, RIDGE, 0.3);                                                        // folded top edge
    if (v >= 1 - px) return mul(col, 0.75);                                                         // dark lower rim
    if (HD && v >= 0.25 && v < 0.5) return dash(col, isPanel(c) ? c.p[0] : c.p[2]);
    return col;
  }, { tag: 'hem band (front + sides)' }));

  // ---------- narrow stitched side tab, wearer's right front, right beside the long seam ----------
  B.push(box('body', [TAB[0], FL.y1, A ? -3.25 : -3], [TAB[1], yHem[0], -2.75], c => {
    const col = fab(c, NAVY, isPanel(c) ? 1.04 * pad(c) : 0.9);
    if (c.face === 'left') return mul(col, 0.7);                                                     // inner long side, in shadow
    if (c.face !== 'front') return mul(col, A ? 0.84 : 0.9);
    if (c.ev < px) return mix(col, RIDGE, 0.4);                                                      // lit top edge
    if (A) return col;
    if (mid) return c.eu < px ? mul(col, 0.92) : col;                                                // (the long seam's groove is its inner edge)
    if (c.eu < px) return mul(col, 0.86);                                                            // bound outer edge
    if (c.eu < 2 * px || (c.ev >= px && c.ev < 2 * px && c.eu < c.fw - px)) return dash(col, c.eu < 2 * px ? c.ev : c.eu);   // outline stitching
    if (c.eu > c.fw - px) return mul(col, 0.88);
    return col;
  }, { tag: 'side tab' }));

  // ---------- wearer's-left chest: lit closure panel, lap strip (collar end -> flap) and the proud pocket flap ----------
  B.push(box('body', [xClose, 1, A ? -3.25 : -3], [A ? 3.5 : 4, FL.y0, -2.75], c => {
    if (c.p[1] < yArm && c.p[0] > wAt(c.p[1]) + 1e-6) return null;                                  // keep the armhole clear
    const col = fab(c, NAVY, pad(c));
    if (c.face === 'right') return mix(mul(col, 0.8), PIPE, 0.3);                                    // the lap's thickness
    if (c.face !== 'front') return mul(col, 0.85);
    if (A && c.eu < px) return mix(col, PIPE, 0.55);                                                 // A: the piped lap edge
    return col;
  }, { tag: 'closure panel' }));
  if (!A) {
    // the lap: the collar end's piped edge carries straight down to the flap
    B.push(box('body', [xClose, yCol, FL.z0], [xClose + 0.5, FL.y0, -3], c => {
      const col = fab(c, NAVY, 1.08);
      if (c.face === 'right') return mix(mul(col, 0.8), PIPE, 0.35);                                // the lap's thickness
      if (c.face !== 'front') return mul(col, 0.9);
      if (mid || c.eu < px) return mix(col, PIPE, 0.7);
      return dash(col, c.ev);
    }, { tag: 'lap strip' }));
  }
  B.push(box('body', [FL.x0, FL.y0, FL.z0], [FL.x1, FL.y1, -2.75], c => {
    const col = fab(c, NAVY, 1.3);                                                                   // a touch brighter than the panel above
    if (c.face === 'bottom') return mul(col, 0.45);
    if (c.face === 'top') return mix(col, RIDGE, 0.35);
    if (c.face === 'right') return mix(col, PIPE, 0.35);                                             // lap edge side
    if (c.face !== 'front') return mul(col, 0.75);
    const lastRow = c.ev > c.fh - px, lastCol = c.eu > c.fw - px;
    if (c.eu < px) return mix(col, PIPE, lastRow ? 0.25 : 0.4);                                     // lap edge runs down the flap
    if (c.ev < px) return mix(col, STITCH, A ? 0.1 : mid ? 0.3 : 0.32);                            // stitched top seam (A: both rows stay light)
    if (lastRow) return mix(col, RIDGE, A ? 0.18 : 0.3);                                            // lit folded lower edge
    if (lastCol) return mul(col, A ? 0.88 : 0.84);
    if (HD && (c.ev < 2 * px || (c.ev >= 3 * px && c.ev < 4 * px)) && c.eu < c.fw - 2 * px) return dash(col, c.eu);   // double stitch row
    return col;
  }, { tag: 'chest flap' }));

  // ---------- stand collar ----------
  // quilting channels (cell centres): A one, M two (quiet), B three with a faint padding puff above each
  const QY = A ? [-0.5] : mid ? [-0.75, 0.75] : [-1.25, -0.25, 0.75].map(v => TC(v, -2));
  const collarOuter = c => {
    const col = fab(c, COLLAR);
    if (c.p[1] < -2 + px) return mix(col, PIPE, A ? 0.6 : 0.75);                                    // piped top edge
    if (HD && c.p[1] < -2 + 2 * px) return dash(col, isPanel(c) ? c.p[0] : c.p[2]);
    for (const q of QY) {
      const k = Math.round((c.p[1] - q) / px);
      if (k === 0) return mix(col, STITCH, mid ? 0.28 : 0.38);
      if (HD && k === -1) return mix(col, RIDGE, 0.1);
    }
    return col;
  };
  // front band: a neck ring under the chin, ending at the vest edge (the collar sides carry the ring round the head).
  // The wearer's-LEFT end is the outer layer; its piped end is the top of the lap line.
  const band = over => c => {
    if (!over && c.face === 'left') return null;                                                     // butts against the lap edge
    if (c.face === 'bottom') return mul(mesh(c), 0.8);
    if (c.face === 'top' || c.face === 'back') return mesh(c);
    if (over && (c.face === 'right' || (c.face === 'front' && c.eu < px))) return piped(c, A ? 0.6 : 0.75);   // piped lap edge
    let col = collarOuter(c);
    if (c.face === 'front') {
      if (c.p[1] >= 0 && c.p[1] < px) col = piped(c, A ? 0.6 : 0.75);                              // piped top rim under the chin
      else if (c.ev > c.fh - px) col = mul(col, A ? 0.92 : 0.9);                                     // rolls under
    }
    return mul(col, (isSide(c) ? 0.85 : 1) * (over ? 1 : 0.92));
  };
  // (the band starts a little above y=0, inside the head, so its top face is never coplanar with the head's bottom face)
  // The under end stops at the lap edge: the over end is prouder, so its piped edge still shows, and the two ends
  // share no face (running the under end on beneath the over end put their tops, bottoms and backs in one plane).
  const yBand = A ? -1 : mid ? -0.5 : -0.25;
  B.push(box('body', [xClose, yBand, A ? -3.75 : -3.5], [4.5, yCol, -2.75], band(true), { tag: "collar front (wearer's left, over)" }));
  B.push(box('body', [-4.5, yBand, -3.25], [xClose, yCol, -2.75], band(false), { tag: "collar front (wearer's right, under)" }));
  // sides beside the head, slightly flared; they start behind the band fronts and stop at the collar back's plane.
  // Top: piped outer rim, mesh lining on the inner half. They reach down to y 0.5 so they rest on slim arms too
  // (the extra is hidden inside wide arms).
  for (const s of [-1, 1]) {
    const inner = s < 0 ? 'left' : 'right';
    const xa = s < 0 ? [-5.5, -4.5] : [4.5, 5.5];
    B.push(box('body', [xa[0], -2, -3], [xa[1], 0.5, 4.75], c => {
      if (c.face === inner) return mesh(c);
      if (c.face === 'bottom') return mul(mesh(c), 0.8);
      if (c.face === 'top') return A ? piped(c, 0.4) : Math.abs(c.p[0]) > 5.5 - px ? piped(c, 0.7) : mesh(c);
      return collarOuter(c);
    }, A ? { tag: 'collar side' } : { tag: 'collar side', rot: [0, 0, s * 3.5], pivot: [s * 5, 0, 0.75] }));
  }
  // back: between the sides (abuts them, no overlap), rolls under toward the back panel
  B.push(box('body', [-4.5, -2, 2.75], [4.5, A ? 1 : 1.25, 4.75], c => {
    if (c.face === 'front') return mesh(c);
    if (c.face === 'bottom') return mul(mesh(c), 0.8);
    if (c.face === 'top') return c.p[2] > 4.75 - px ? piped(c, A ? 0.5 : 0.7) : mesh(c);
    const col = collarOuter(c);
    return hd && c.face === 'back' && c.p[1] > 1.25 - px ? mul(col, 0.85) : col;
  }, { tag: 'collar back' }));

  return { S: K.S, px: K.px, cell: K.cell, boxes: B };
};
