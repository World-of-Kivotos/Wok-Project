// ================= FORT Redut-M body armor — EFT reference (tarkov.dev 5ca2151486f774244a3b8d30) =================
// Heavy Russian vest in a worn, teal-leaning mid green (rust stains, light specks). Tall padded collar (in MC the head
// hides it, so only a low rim beside the head and a roll behind the neck show) plus a padded throat pad in front; taupe
// harness straps run from the shoulders in a V down to a vertical centre tab (X-box stitch, blue FORT tag); dark
// blue-grey shoulder pads with olive loop patches; quilted upper chest (outline + two stitched channels per side) with a
// second blue tag low on the wearer's left; light-green segmented webbing band across the chest, dark-olive velcro strip
// on each side panel; narrow dark-olive velcro "T" between two lower panels with chamfered inner corners; black waist
// belt with side-release buckle, X-box and a light-green strap keeper on its front near the wearer's-right end; two-part groin protector (chamfered
// flap + stitched block, a row of elastic ammo loops edge to edge and a tapered, double-stitched pocket); dark teal-black
// lower-back protector that wraps around the hips; dark armhole lining.
// No arm armour: Redut-M has none (the shoulder protectors belong to the Redut-T5).
ARMORS.redut = function (mode) {
  const K = kit(mode), { hd, mid, G, edge, isPanel, isSide } = K;
  const A = !hd, HB = hd && !mid, px = K.px;
  const V = (a, m, b = m) => (A ? a : mid ? m : b);
  const B = [];
  const C = {
    grn: hex('#71947d'), soft: hex('#98b4a1'), band: hex('#99be9d'), velcro: hex('#4a5837'), loop: hex('#8fb393'),
    strap: hex('#968b7d'), grey: hex('#5d5f6b'), olive: hex('#66734f'), blue: hex('#5cb0ff'), ink: hex('#16325f'),
    belt: hex('#545351'), black: hex('#2c2e31'), lowback: hex('#223235'), lining: hex('#1b2124'), clin: hex('#1c342d'),
    rust: hex('#9a6440'), speck: hex('#e2ebe0'),
  };
  const SK = V(0.8, 0.78, 0.66);                                               // groove darkening
  const SKC = V(0.8, 0.86, 0.66);                                              // quilting channels (softer in M)
  // fabric grain: HD uses half the kit amplitude and no checkerboard (the checker read as salt-and-pepper perforation)
  const Gr = (c, amt) => (HB ? 1 + (vnoise(c.p[0] * 8, c.p[1] * 8, c.p[2] * 8) - 0.5) * amt * 0.5 : G(c, amt));
  const nU = c => Math.max(1, Math.round(c.fw / px)), nV = c => Math.max(1, Math.round(c.fh / px));
  const iU = c => Math.min(nU(c) - 1, Math.floor(c.eu / px + 1e-6)), iV = c => Math.min(nV(c) - 1, Math.floor(c.ev / px + 1e-6));
  const dash = v => Math.floor(v * 4 + 1e-6) % 2 === 0;                        // HD stitch dashes (0.25 px)
  // seam at coordinate t: 2 = groove cell, 1 = stitch row beside it (HD only, on the outer side only), 0 = none
  const seam = (v, t) => {
    const d = v - t;
    if (!HB) return Math.abs(d) < px / 2 - 1e-6 ? 2 : 0;
    if (d > -0.25 && d <= 1e-6) return 2;
    if (d > 1e-6 && d <= 0.25 + 1e-6) return 1;
    return 0;
  };
  const sew = (col, s, along, k = SK) => (s === 2 ? mul(col, k) : s === 1 && dash(along) ? mul(col, 0.84) : col);
  // wear: a few deliberate rust stains (front), low-frequency rust haze + sparse light specks, sampled on the art grid.
  // Pixel style only tints (solid brown 1-px squares read as dirt pixels); M keeps large soft stains but no haze on the
  // chest. Pixel/M keep only the reference's two clear smudges: beside the tab (wearer's left) and on the groin pocket.
  const STAINS = [[1.5, 4.5, 0.95, 0.8], [-0.6, 15.3, 0.9, 0.55]].concat(HB ? [[-2.5, 3.1, 0.85, 0.65]] : []);
  const SR = V(1.35, 1.3, 1), SMIX = V(0.2, 0.2, 0.42);
  const wear = (c, col, amt = 1, haze = true) => {
    const q = c.p;
    if (q[2] < -2.5 && amt >= 0.5) for (const [sx, sy, rx, ry] of STAINS) {
      const d = ((q[0] - sx) / (rx * SR)) ** 2 + ((q[1] - sy) / (ry * SR)) ** 2;
      if (d < 1) col = mix(col, C.rust, SMIX * clamp01((1 - d) * 1.8) * sm(clamp01((fbm(q[0] * 1.6 + 2.2, q[1] * 1.6 + 7.1, 3.3) - 0.3) * 2.4)));
    }
    const n = fbm(q[0] * 0.3 + 5.1, q[1] * 0.3 + 2.3, q[2] * 0.3 + 8.7);
    if (haze && n > 0.62) col = mix(col, C.rust, Math.min(V(0.12, 0.18, 0.22), (n - 0.62) * 1.2) * amt);
    const h = hash3(Math.floor(q[0] * 4 + 400), Math.floor(q[1] * 4 + 400), Math.floor(q[2] * 4 + 400) + c.face.length * 31);
    if (h > V(2, 2, 0.996)) col = mix(col, C.speck, 0.6 * Math.min(1, amt + 0.3));   // HD only: 1-px / half-px specks read as stray pixels
    return col;
  };

  // ---------- layout ----------
  const xe = y => 2.5 + clamp01((y - 1) / 5) * 1.75;                        // slanted outline of the quilted chest panel
  const eM = y => 3 + 0.5 * Math.floor(clamp01((y - 2.5) / 3.5) * 3);        // M: the same outline as three even whole-cell steps
  const yS = ax => (3.5 - ax) * (2.5 / 3);                                     // harness strap centre line (V)
  const Y = {
    top: V(-1, -0.5), band1: V(7, 7.5), pan0: V(7, 7.5), pan1: V(10, 10.5), belt0: 10, belt1: V(11, 11.5),
    flap1: V(12, 12.5), blk1: V(12, 13.5), g1: V(16, 16.5), lb0: V(11, 11.5), lb1: V(14, 14.5),
  };
  const LX = 4.5, LZ = V(3.5, 3.25);                                           // lower shell: flush with the chest, deeper belly

  // ---------- painters ----------
  const chest = (c, back) => {
    const x = c.p[0], y = c.p[1], ax = Math.abs(x), e = mid ? eM(y) : xe(y);
    // M front: two channels a full pixel apart; the outer one runs into the outline at the top like the reference's
    // double-stitched edge, then the outline steps away from it
    // pixel back: no channel (a 1-px dark column beside each 1-px back strap read as a second pair of straps)
    const seams = back ? V([], [2.25], [1.25, 2.75]) : V([2], [1.25, 2.75], [1.75, 3.25]);
    let k = 1.06 - 0.015 * y;                                                  // lit from above
    if (HB) {                                                                  // pillowed channels (HD only; at M cells it read as mottling)
      let d = Math.abs(ax - e);
      for (const t of seams) d = Math.min(d, Math.abs(ax - t));
      k *= 0.95 + 0.07 * sm(clamp01(d / 0.7));
    }
    if (ax > e) k *= A ? 0.88 : 0.92;
    let col = mul(C.grn, k * Gr(c, mid ? 0.015 : 0.035));
    if (y > 0.5) {
      // pixel/M: the shading step alone marks the outline. HD: a soft groove only, no stitch row, so the outer channel
      // runs cleanly into it instead of two staircases of stitches tangling where they meet
      if (HB && seam(ax, e) === 2) col = mul(col, 0.86);
      if (ax < e - px / 2 + 1e-6) for (const t of seams) col = sew(col, seam(ax, t), c.ev, SKC);
    }
    return col;
  };
  const upper = c => {
    if (isPanel(c)) return wear(c, chest(c, c.face === 'back'), 1, !mid);
    if (isSide(c)) {                                                          // deep armhole: dark lining, green rim front/back
      if (Math.abs(c.p[2]) < 2.25 && c.p[1] > 0) return mul(C.lining, G(c, 0.1));
      return wear(c, mul(C.grn, 0.88 * Gr(c, 0.05)));
    }
    return mul(C.grn, (c.face === 'top' ? 0.92 : 0.6) * G(c, 0.05));
  };
  const velcro = c => {
    const n = HB ? (hash3(Math.floor(c.p[0] * 4) + 50, Math.floor(c.p[1] * 4) + 50, 7) - 0.5) * 0.18
      : mid ? (hash3(Math.floor(c.p[0] * 2) + 50, Math.floor(c.p[1] * 2) + 50, 7) - 0.5) * 0.07 : 0;
    return wear(c, mul(C.velcro, 1 + n), 0.3);
  };
  const lower = c => {
    const ax = Math.abs(c.p[0]);
    if (c.face === 'front') return ax < 3 ? velcro(c) : wear(c, A ? mul(C.grn, 0.82) : edge(c, mul(C.grn, 0.84 * Gr(c, 0.05)), { stitch: false }));
    if (c.face === 'back') {
      let col = mul(C.grn, 0.92 * Gr(c, 0.05));
      for (const t of [1.5, 3.5]) col = sew(col, seam(ax, t), c.ev);
      return wear(c, col);
    }
    if (isSide(c)) {
      let col = mul(C.grn, 0.88 * Gr(c, 0.05));
      col = sew(col, seam(Math.abs(c.p[2]), 1.5), c.ev);
      return wear(c, col);
    }
    return mul(C.grn, (c.face === 'top' ? 0.92 : 0.6) * G(c, 0.05));
  };
  // segmented webbing band: flat blocks split by clear dark seams
  const band = c => {
    let col = mul(C.band, A ? G(c, 0.05) : mid ? 1 : Gr(c, 0.02));
    if (isPanel(c)) {
      const ax = Math.abs(c.p[0]), j = iV(c), n = nV(c);
      if (!A) { if (j === 0) col = mul(col, 1.08); else if (j === n - 1) col = mul(col, 0.84); }
      for (const t of A ? [1.5] : [0, 1.5, 3]) {
        const s = seam(ax, t);
        if (s === 2) col = mul(col, V(SK + 0.08, 0.8, 0.62));                  // thin stitched splits, band stays mostly light
      }
    } else col = mul(col, 0.86);
    return wear(c, col, 0.5);
  };
  // lower panels: short, shallow chamfers on the inner-top corners leave a velcro "T" (reference: ~4 px cap whose
  // shoulders slope ~1 px across / ~0.7 px down, then a 2 px stem to the belt). The chamfer edge is the panel's lit
  // rim (lighter), not a dark outline - a dark rim merged with the velcro and turned the T into a funnel.
  // M: CH 1.35 / SL 1.2 clears the rows to 4 -> 3 -> 2 px, a stepped funnel like the reference (a one-row cap read as a line)
  const panelTop = Y.pan0, CH = V(1.6, 1.35, 1.2), SL = V(1.57, 1.2, 1.57), RIM = V(0, 0.55, 0.4), PSEAM = V(0, 2.75, -9);
  const panel = c => {
    const ax = Math.abs(c.p[0]), y = c.p[1], cut = (ax - 1) + (y - panelTop) * SL;
    if (cut < CH) return null;
    let col = mul(C.grn, 0.86 * Gr(c, 0.05));                                 // below the band the original is clearly darker
    if (c.face !== 'front') return wear(c, mul(col, 0.82));
    if (A) return wear(c, c.ev > c.fh - 1 ? mul(col, 0.9) : col);             // pixel style: plain block, chamfer carries the shape
    if (cut < CH + RIM || ax < 1 + px) return wear(c, mul(col, 1.1));         // lit padded rim along the chamfer and down the stem
    if (HB && cut < CH + RIM + 0.3 && dash(c.ev)) col = mul(col, 0.84);        // stitch row just inside it
    col = sew(col, seam(ax, PSEAM), c.ev);
    if (HB && c.ex >= 0.5 && c.ex < 0.75 && dash(c.ex === c.ev || c.ex === c.fh - c.ev ? c.eu : c.ev)) col = mul(col, 0.93);   // HD: faint dark stitch inset
    return wear(c, edge(c, col, { stitch: false }));
  };
  const webbing = (col0, vert) => c => {
    let col = mul(col0, G(c, 0.07));
    if (!isPanel(c)) return wear(c, mul(col, 0.78), 0.4);
    const acr = vert ? c.eu : c.ev, along = vert ? c.ev : c.eu, w = vert ? c.fw : c.fh;
    const n = Math.max(1, Math.round(w / px)), j = Math.min(n - 1, Math.floor(acr / px + 1e-6));
    if (!A) {
      const soft = mid && n <= 2;                                              // 1-px strap in M: keep both rows light
      if (j === 0) col = mul(col, soft ? 1.08 : 1.1);
      else if (j === n - 1) col = mul(col, soft ? 0.95 : 0.8);
      else if (HB && (j === 1 || j === n - 2) && Math.floor(along * 4 + 1e-6) % 3 === 0) col = mul(col, 0.92);   // faint edge stitching
    }
    return wear(c, col, 0.4);
  };
  const bib = c => {
    if (c.face === 'bottom') return null;                                     // hidden edge; its texels made a stray dark line
    const ax = Math.abs(c.p[0]), y = c.p[1];
    if (y > yS(ax) + 0.2) return null;                                        // V-shaped lower edge under the straps
    if (c.face === 'back') return C.clin;
    const t = clamp01((yS(ax) - y) / 1.4);
    let col = mul(C.soft, (0.84 + 0.2 * sm(t)) * G(c, 0.05));
    if (y < 0.5) col = mul(col, 0.85);
    return wear(c, col, 0.8);
  };
  const padF = c => {
    const ax = Math.abs(c.p[0]), y = c.p[1];
    if (y < yS(ax) - 0.15 || ax < (mid ? eM(y) : xe(y) - 0.25)) return null; // tucked under the strap, stops at the chest outline
    let col = mul(C.grey, G(c, 0.08));
    if (A && ax > 3.5 && y < 1) col = mul(C.olive, G(c, 0.08));              // pixel style: olive loop patch painted in
    if (HB) col = mul(col, 1 + (hash3(Math.floor(c.p[0] * 4) + 9, Math.floor(c.p[1] * 4) + 9, 3) - 0.5) * 0.1);
    if (!isPanel(c)) return wear(c, mul(col, 0.8), 0.3);
    if ((!mid && c.ev > c.fh - px) || ax > 4.5 - px) col = mul(col, 0.8);     // M: flat pad, so the strap edge stands out
    return wear(c, col, 0.3);
  };
  const padBack = c => wear(c, edge(c, mul(C.grey, G(c, 0.08)), { stitch: false }), 0.3);
  const olive = c => {
    let col = mul(C.olive, G(c, 0.1));
    if (HB) col = mul(col, 1 + (hash3(Math.floor(c.p[0] * 4) + 3, Math.floor(c.p[1] * 4) + 3, 5) - 0.5) * 0.16);
    return wear(c, edge(c, col, { stitch: false }), 0.3);
  };
  const tab = c => {
    let col = mul(C.grn, 1.04 * G(c, 0.05));
    if (c.face !== 'front') return wear(c, mul(col, 0.8), 0.8);
    const i = iU(c), j = iV(c), n = nU(c);
    if (A) return wear(c, j === 0 ? mul(col, 0.8) : mul(col, 1.12 / 1.04), 0.8);   // pixel style: lighter strip so it reads down to the band
    const rim = i === 0 || i === n - 1 || j === 0 || j === n - 1;
    if (j < n) {                                                              // box stitch where the straps join
      if (mid) col = (i + j) % 2 === 0 ? mul(col, SK) : mul(col, 1.1);        // 3x3 cells: an X (corners + centre)
      else if (i === j || i === n - 1 - j) col = mul(col, SK);                // HD: a real X inside the box
      else if (rim) col = mul(col, 0.82);
    } else if (j === n && mid) col = mul(col, 0.8);
    else if (mid) col = mul(col, i === 0 || i === n - 1 ? 0.8 : 1.08);        // M: lighter strap with dark edges down to the band
    return wear(c, HB ? edge(c, col) : col, 0.8);
  };
  const logo = c => {
    if (c.face !== 'front') return mul(C.blue, 0.6);
    if (A) return C.blue;
    const i = iU(c), j = iV(c), n = nU(c);
    if (mid) return mul(C.blue, j === 0 ? 1.1 : 1);
    if (j === 0 || i === 0 || i === n - 1) return mul(C.blue, 1.1);           // light rim
    if (j === 2) return mix(C.ink, C.blue, 0.55);                             // small mid-blue FORT emblem, tag stays bright
    return j === n - 1 ? mul(C.blue, 0.95) : C.blue;
  };
  // belt X-box just on the wearer's right of the buckle (reference: ~1.5 px square centred near x -1.3)
  const XB0 = V(-2.75, -2.25), XB1 = -0.75;
  const belt = c => {
    let col = mul(C.belt, Gr(c, 0.06));
    if (c.face === 'top') return mul(col, 1.1);
    if (c.face === 'bottom') return mul(col, 0.7);
    const j = iV(c), n = nV(c);
    if (!A) { if (j === 0) col = mul(col, 1.15); else if (j === n - 1) col = mul(col, 0.82); }
    if (HB && (j === 1 || j === n - 2) && dash(c.eu)) col = mul(col, 1.1);    // faint edge stitching
    if (c.face === 'front') {
      const x = c.p[0];
      if (x > XB0 && x < XB1) {
        const m = Math.round((XB1 - XB0) / px), i = Math.min(m - 1, Math.floor((x - XB0) / px + 1e-6));
        const rim = i === 0 || i === m - 1 || j === 0 || j === n - 1;
        const t = n > 1 ? j * (m - 1) / (n - 1) : 0;
        if (A) col = mul(C.belt, 1.25);                                        // pixel style: 2x1 lighter patch
        else if (mid) { if ((i + j) % 2 === 0) col = mul(C.belt, 1.32); }      // 3x3 cells: an X of light stitching
        else if (Math.abs(i - t) < 0.6 || Math.abs(i - (m - 1 - t)) < 0.6) col = mul(C.belt, 1.38);   // HD: box + X
        else if (rim) col = mul(C.belt, 1.25);
        else col = mul(col, 1.04);
      }
    }
    return wear(c, col, 0.3);
  };
  // side-release buckle: female housing (proud) + male prong (a step lower); pixel style merges both into one block
  const buckle = part => c => {
    const b = C.black;
    if (c.face === 'top') return mul(b, 1.6);
    if (c.face !== 'front') return mul(b, 0.9);
    const i = iU(c), j = iV(c), n = nU(c), m = nV(c);
    if (A) return mul(b, i === 0 ? 1.3 : 1);
    if (part === 'prong') {
      if (i === n - 1 && !HB) return mul(b, 0.75);
      return mul(b, (HB ? j === 1 || j === 2 : j === 0) ? 1.45 : 1.05);       // centre ridge
    }
    if (j === 0 || i === 0) return mul(b, 1.5);                               // lit rim
    if (i === n - 1) return mul(b, 0.7);
    if (HB && j >= 2 && j <= m - 3 && i >= 1 && i <= 2) return mul(b, 0.7);   // release window
    return mul(b, 1.15);
  };
  // strap keeper: a light-green vertical loop on the belt FRONT near the wearer's-right end (reference: just inside
  // the belt end, crossing it from a little above to a little below); lit top fold, darker bottom fold
  const keeper = c => {
    let col = mul(C.loop, G(c, 0.05));
    if (c.face === 'top') return mul(col, 1.05);
    if (c.face === 'bottom' || c.face === 'back') return mul(col, 0.62);
    if (c.face !== 'front') return wear(c, mul(col, 0.8), 0.6);
    if (!A) { const j = iV(c), n = nV(c); if (j === 0) col = mul(col, 1.1); else if (j === n - 1) col = mul(col, 0.85); }
    if (HB && iU(c) === nU(c) - 1) col = mul(col, 0.9);                       // HD: shaded right column, so the loop reads round
    return wear(c, col, 0.6);
  };
  // groin flap: bottom corners chamfered through its whole depth, lit top row, darker outline along the chamfers
  const FW = V(3, 3.25);
  const flap = c => {
    const ax = Math.abs(c.p[0]), y = c.p[1], ch = (FW - ax) + (Y.flap1 - y);
    if (!A && ch < V(0, 0.6, 0.55)) return null;
    let col = mul(C.grn, 0.84 * Gr(c, 0.05));
    if (c.face === 'top') return mul(col, 1.05);
    if (c.face === 'bottom') return mul(col, 0.62);
    if (c.face !== 'front') return wear(c, mul(col, 0.78), 0.9);
    if (A) return wear(c, edge(c, col), 0.9);
    if (mid) {
      const i = iU(c), j = iV(c), m = nU(c);
      if (j === 0) col = mul(col, i === 0 || i === m - 1 ? 0.9 : 1.1);
      else col = mul(col, i <= 1 || i >= m - 2 ? 0.78 : 0.92);
      return wear(c, col, 0.9);
    }
    if (ch < 0.85) col = mul(col, 0.72);                                      // HD: dark rim along the chamfers
    return wear(c, edge(c, col), 0.9);
  };
  const PK = V({ top: 14, bot: 16, w0: 2, w1: 1 }, { top: 14.5, bot: 16, w0: 2.75, w1: 2 }, { top: 14.5, bot: 16, w0: 2.75, w1: 2.25 });   // gentle taper
  const pkW = y => PK.w0 + (PK.w1 - PK.w0) * clamp01((y - PK.top) / (PK.bot - PK.top));
  const groinHW = V(3.5, 3.75);
  const groin = c => {
    const ax = Math.abs(c.p[0]), y = c.p[1];
    if ((groinHW - ax) + (Y.g1 - y) < V(1.6, 0.9, 0.75)) return null;         // rounded bottom corners
    let col = mul(C.grn, 0.82 * Gr(c, 0.05));
    if (c.face !== 'front') return wear(c, mul(col, 0.82), 0.9);
    if (A && y > PK.top && y < PK.bot && ax < pkW(y) + 0.5) col = mul(col, 1.1);   // pixel style: pocket painted in
    if (!A && y < Y.blk1) {                                                   // second stitched block under the flap
      const r = y - Y.flap1;                                                  // (its top row is lit by edge())
      if (mid) { if (r > 0.5) col = mul(col, 0.84); }
      else if (r > 0.75) col = mul(col, 0.7);
      else if (r > 0.25 && r < 0.5 && ax < groinHW - 0.5 && dash(c.eu)) col = mul(col, 0.84);
    }
    return wear(c, edge(c, col), 0.9);
  };
  // tapered pocket (M/B): its own proud box, trapezoid cut out of the front face, dark outline + bottom edge
  const pocket = c => {
    const ax = Math.abs(c.p[0]), y = c.p[1], w = pkW(y);
    if (ax > w + 1e-6) return null;
    let col = mul(C.grn, 0.88 * Gr(c, 0.05));
    if (c.face === 'bottom') return mul(col, 0.62);
    if (c.face === 'top') return mul(col, 0.9);
    if (c.face !== 'front') return mul(col, 0.7);
    if (ax > w - px - 1e-6) col = mul(col, V(0.76, 0.68, 0.76));             // outline along the slanted sides
    else if (y > PK.bot - px) col = mul(col, V(0.76, 0.84, 0.76));            // bottom hem (M: softer, so the pocket is no dark bowl)
    else {
      col = mul(col, V(1.08, 1.12, 1.08));
      if (HB && ((ax > w - 0.75 && dash(c.ev)) || (y > PK.bot - 0.75 && dash(c.eu)))) col = mul(col, 0.86);   // inner stitch row
    }
    return wear(c, col, 0.9);
  };
  const loops = c => {
    let col = mul(C.loop, G(c, 0.05));
    if (c.face !== 'front') return wear(c, mul(col, 0.78), 0.4);
    const i = iU(c), j = iV(c), n = nV(c), per = V(2, 2, 3);
    if (i % per === per - 1) col = mul(col, V(0.72, 0.7, 0.62));              // soft shadow between loops
    else if (HB) col = mul(col, i % per === 0 ? 1.06 : 0.95);
    if (!A) { if (j === 0) col = mul(col, 1.1); else if (j === n - 1) col = mul(col, 0.84); }
    return wear(c, col, 0.4);
  };
  const lowBack = c => wear(c, edge(c, mul(C.lowback, G(c, 0.08))), 0.25);
  // side wings of the lower-back protector: thin plates wrapping the hips (clear of the legs at |x| 4.25). They end near
  // the body's mid-line with a plain square end (no notch: it made a hook-shaped fang), so from the front only a straight
  // hairline edge shows beside the thigh. The end stays closed: armour renders both sides of every face, so an open end
  // showed the inside of the wing as a streaky dark wedge.
  const WZ = V(0, -0.5);
  const lowWing = c => {
    if (c.face === 'top') return mul(C.lowback, 0.8);
    return wear(c, edge(c, mul(C.lowback, G(c, 0.08))), 0.25);
  };
  // back roll of the collar: inner (front) face and inner half of the top are dark lining, the underside plain green
  const collarBack = c => {
    if (c.face === 'front') return mul(C.clin, G(c, 0.1));
    if (c.face === 'bottom') return mul(C.grn, 0.6 * G(c, 0.05));
    // (the lining stops short of the ends, where the side rims' tops run over the roll's top)
    if (c.face === 'top') return c.ev / c.fh > 0.5 && Math.abs(c.p[0]) < 4.25 ? mul(C.clin, 1.2 * G(c, 0.1)) : wear(c, mul(C.grn, 0.96 * G(c, 0.05)), 0.5);
    const j = iV(c), n = nV(c);
    const k = j === 0 ? 1.0 : j === n - 1 ? 0.78 : 0.9;                       // rolled padding: darker than the chest
    const wrap = isPanel(c) && Math.abs(c.p[0]) > 4.5 ? 0.88 : 1;             // ends turn forward into the sides
    return wear(c, mul(C.grn, k * wrap * G(c, 0.05)), 0.5);
  };
  // low side rim of the collar beside the head (every style): outer face rolled, inner face lining, end faces shaded
  const collarSide = s => c => {
    if (c.face === (s < 0 ? 'left' : 'right')) return mul(C.clin, G(c, 0.1));
    if (c.face === 'bottom') return mul(C.grn, 0.6 * G(c, 0.05));
    if (c.face === 'top') return wear(c, mul(C.grn, 0.96 * G(c, 0.05)), 0.5);
    if (isPanel(c)) return mul(C.grn, 0.74 * G(c, 0.05));
    const j = iV(c), n = nV(c);
    return wear(c, mul(C.grn, (j === 0 ? 1.0 : j === n - 1 ? 0.8 : 0.9) * G(c, 0.05)), 0.5);
  };

  // faces pressed flat against another surface are never meant to be seen: drop them, otherwise they z-fight and peek
  // out as dark hairlines along cut-out edges
  const flush = (mat, face, where = () => true) => c => (c.face === face && where(c) ? null : mat(c));

  // ---------- shell ----------
  B.push(box('body', [-4.5, Y.top, -3], [4.5, 6, 3], upper, { tag: 'upper shell' }));
  B.push(box('body', [-LX, 6, -LZ], [LX, 12, LZ], lower, { tag: 'lower shell' }));
  // ---------- collar: the original's tall neck roll is hidden by the MC head, so it becomes a low lip that sits on the
  // shoulder line behind the neck (its back face just clears the hair, nothing hangs down the back plate), plus a low
  // side rim beside the head that rises toward the back and runs straight into the lip's top. The lip ends just inside
  // the rims, so the rim's outer face is the one collar line seen from the side. Padded throat bib in front. ----------
  B.push(box('body', [-4.75, -1.5, 3], [4.75, 0, 4.75], collarBack, { tag: 'collar back', rot: [-6, 0, 0], pivot: [0, 0, 3] }));
  for (const s of [-1, 1]) {
    const x = s < 0 ? [-5, -4.5] : [4.5, 5];
    B.push(box('body', [x[0], -1, 0.5], [x[1], 0.5, 4.75], collarSide(s), { tag: 'collar side', rot: [5, 0, -s * 3], pivot: [s * 4.75, 0.5, 0.5] }));
  }
  // throat bib: two boxes whose cut-out (V) edges all lie under the harness straps, so no transparent edge crosses the chest
  const flush2 = (mat, f1, f2) => c => (c.face === f1 || c.face === f2 ? null : mat(c));
  B.push(box('body', [V(-3, -2.75), Y.top, -3.5], [V(3, 2.75), V(1, 1.25), -3], flush(bib, 'back'), { tag: 'neck bib' }));
  B.push(box('body', [V(-1, -1.5), V(1, 1.25), -3.5], [V(1, 1.5), V(2, 2.25), -3], flush2(bib, 'back', 'top'), { tag: 'neck bib (lower)' }));
  // ---------- shoulders: grey pads, olive loop patches, taupe harness V ----------
  for (const s of [-1, 1]) {
    const X = (a, b) => (s < 0 ? [-b, -a] : [a, b]);
    const [p0, p1] = X(V(2.5, 3, 2), 4.5);
    B.push(box('body', [p0, 0, -3.25], [p1, V(2, 2.5), -3], flush(padF, 'back'), { tag: 'shoulder pad' }));
    if (hd) { const [o0, o1] = X(3.5, 4.5); B.push(box('body', [o0, 0, -3.5], [o1, 1, -3.25], flush(olive, 'back'), { tag: 'olive loop patch' })); }
    const ang = Math.atan2(2.5, 3) * 180 / Math.PI;
    B.push(box('body', [s * 2 - 2.25, 0.75, -3.7], [s * 2 + 2.25, 1.75, -3.45], webbing(C.strap, false), { tag: 'harness strap', rot: [0, 0, -s * ang], pivot: [s * 2, 1.25, -3.575] }));
    // back: pads over the shoulders (only the outer strip beside each strap; wider ones peeked out as stray dark
    // squares on both sides of the strap) and the straps running down the back to the webbing band
    const [q0, q1] = X(V(4, 3.75), 4.5);
    B.push(box('body', [q0, 0, 3], [q1, V(2, 2.5), 3.25], flush(padBack, 'front'), { tag: 'shoulder pad (back)' }));
    const [b0, b1] = X(V(3, 2.75), V(4, 3.75));
    B.push(box('body', [b0, Y.top, 3], [b1, 6, 3.5], flush(webbing(C.strap, true), 'front'), { tag: 'harness strap (back)' }));
  }
  // ---------- centre tab (sits on the chest, no gap behind it) + FORT tags ----------
  const TW = V(1, 0.75), TZ = V(-3.75, -3.75);
  B.push(box('body', [-TW, 2, TZ], [TW, 6, -3], flush(tab, 'back'), { tag: 'centre tab' }));
  B.push(box('body', [-0.5, V(3.5, 3.75), TZ - 0.25], [0.5, V(4.5, 4.75), TZ], flush(logo, 'back'), { tag: 'FORT tag (tab)' }));
  B.push(box('body', [3, V(4.5, 4.75), -3.25], [4, V(5.5, 5.75), -3], flush(logo, 'back'), { tag: 'FORT tag (chest)' }));
  // ---------- chest webbing band (front + back), nearly flat ----------
  const BW = V(4, 4.25), BD = V(0.5, 0.25);
  B.push(box('body', [-BW, 6, -LZ - BD], [BW, Y.band1, -LZ], flush(band, 'back'), { tag: 'chest band' }));
  B.push(box('body', [-BW, 6, LZ], [BW, Y.band1, LZ + BD], flush(band, 'front'), { tag: 'back band' }));
  // dark-olive velcro strip across each side panel under the armhole (M/B); mostly behind the arm, shows when it swings
  if (hd) for (const s of [-1, 1]) {
    const x = s < 0 ? [-4.75, -4.5] : [4.5, 4.75];
    B.push(box('body', [x[0], 6, -2.75], [x[1], V(7, 7, 6.75), 2.75], flush(velcro, s < 0 ? 'left' : 'right'), { tag: 'side velcro strip' }));
  }
  // ---------- lower front panels around the velcro T (pixel style: 3 px wide on the whole-pixel grid, so the T's cap
  // is symmetric; the 0.5 px strip outside them is plain shell) ----------
  for (const s of [-1, 1]) {
    const x = s < 0 ? [V(-4, -4.5), -1] : [1, V(4, 4.5)];
    B.push(box('body', [x[0], Y.pan0, -LZ - 0.25], [x[1], Y.pan1, -LZ], flush(panel, 'back'), { tag: 'lower panel' }));
  }
  // ---------- waist belt (flush with the vest sides) ----------
  const BX = 4.75, BZ = LZ + 0.5;
  B.push(box('body', [-BX, Y.belt0, -BZ], [BX, Y.belt1, BZ], belt, { tag: 'waist belt' }));
  if (A) B.push(box('body', [-0.25, 10, -BZ - 0.5], [1.75, 11, -BZ], flush(buckle('housing'), 'back'), { tag: 'belt buckle' }));
  else {
    B.push(box('body', [-0.25, 10, -BZ - 0.5], [0.75, 11.5, -BZ], flush(buckle('housing'), 'back'), { tag: 'buckle housing' }));
    B.push(box('body', [0.75, 10.25, -BZ - 0.25], [1.75, 11.25, -BZ], flush(buckle('prong'), 'back'), { tag: 'buckle prong' }));
  }
  // strap keeper on the belt front near the wearer's-right end: 0.25 px proud of the belt, its back resting on the lower
  // panel above the belt (that part of the back face is dropped); below the belt it hangs a hair in front of the shell,
  // so there the back face stays (dark) instead of opening a see-through slot
  B.push(box('body', [-4, V(9, 9.5), -BZ - 0.25], [V(-3, -3.5), 12, -LZ - 0.25], flush(keeper, 'back', c => c.p[1] < Y.belt1), { tag: 'strap keeper' }));
  // ---------- groin protector: chamfered flap, stitched block, ammo loops edge to edge, tapered pocket ----------
  B.push(box('body', [-FW, Y.belt1, -BZ], [FW, Y.flap1, -BZ + 0.5], flush(flap, 'back', c => c.p[1] < 12), { tag: 'groin flap' }));
  const GZ = V([-3.75, -3.25], [-3.625, -3.125]);
  B.push(box('body', [-groinHW, Y.flap1, GZ[0]], [groinHW, Y.g1, GZ[1]], groin, { tag: 'groin panel' }));
  const LW = groinHW;
  B.push(box('body', [-LW, V(12.5, 13.5), GZ[0] - 0.25], [LW, V(13.5, 14.5), GZ[0]], flush(loops, 'back'), { tag: 'ammo loops' }));
  if (hd) B.push(box('body', [-PK.w0, PK.top, GZ[0] - 0.25], [PK.w0, PK.bot, GZ[0]], flush(pocket, 'back'), { tag: 'groin pocket' }));
  // ---------- lower-back protector: back plate + side wings wrapping the hips ----------
  B.push(box('body', [-4.5, Y.lb0, LZ], [4.5, Y.lb1, LZ + 0.5], lowBack, { tag: 'lower-back protector' }));
  for (const s of [-1, 1]) {
    const x = s < 0 ? [-4.75, -4.5] : [4.5, 4.75];                   // thin in every style: only a hairline edge shows from the front
    B.push(box('body', [x[0], 12, WZ], [x[1], Y.lb1, LZ + 0.5], lowWing, { tag: 'lower-back wing' }));
  }
  return { S: K.S, px: K.px, cell: K.cell, boxes: B };
};
