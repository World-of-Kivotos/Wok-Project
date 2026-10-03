// ================= FORT Defender-2 body armor (Gehenna Spot Camo) — mod-only colourway =================
// EFT has only the plain olive Defender-2 (tarkov.dev 5e9dacf986f774054d6b89f4); this colourway exists only in the mod.
// Shape, straps, tags, band and groin protector follow that EFT render exactly (same code as defender_2.js); the
// colours follow the mod's own inventory icon / texture for this item: a warm olive ground scattered with small round
// spots (about a tenth of the vest's width, as on the icon) of brown, dark brown, a little khaki and a few near-black
// ones, with the ground showing between them so the vest's shapes still read. The
// padded roll is a shade lighter than the shell (as on the original); the loop fields stay a plain khaki olive (velcro
// is not printed); the FORT tags keep their navy / teal; the centre strap's bound edges and the stitching of its X-box
// are drawn in the dark binding colour so the strap still reads through the spots.
ARMORS.defender_2_spot_camo = function (mode) {
  const K = kit('M');                     // always the 2x style, whatever mode is asked for
  const { G, isPanel, isSide, px } = K;
  const B = [];

  // ---------- colourway (sampled off the mod's icon, x ~1.45 like the plain one) ----------
  const P = {
    ground: hex('#605b42'), loop: hex('#746d4a'), lining: hex('#1e1d18'),
    bind: hex('#39362a'), navy: hex('#18242c'), teal: hex('#2c7c89'),
  };
  // spot colours and their shares (cumulative): muted brown (as light as the ground, so it shows by its warmer hue, not
  // as a hole), dark brown, near-black (lifted, a few lone accents), and a khaki pulled toward the ground (a minority, so
  // it lights the pattern without fighting the dark spots). Same overall brightness as before; the icon is as bright as
  // the plain Defender-2's.
  const SPOTS = [[hex('#70583b'), 0.45], [hex('#574b32'), 0.75], [hex('#423e2b'), 0.85], [hex('#7f7753'), 1]];
  // Spot camo: a jittered grid of small round spots (about 1 px across, roughly a tenth of the vest's width as on the
  // icon) laid out in each face's own plane; about a third of the grid carries no spot, so the olive ground shows
  // between them and the roll / strap / band keep their shape. Where two spots overlap and leave a lone cell, that cell
  // takes its neighbours' colour (no one-cell specks). Faint low-frequency drift over everything.
  const rawK = (a, b, salt) => { const h = hash3(a, b, salt + 3); return SPOTS.findIndex(([, t]) => h < t); };
  // near-black spots are small lone accents: one that would touch another near-black spot turns brown instead (two of
  // them side by side read as a hole, not as camo)
  const NB8 = [[1, 0], [-1, 0], [0, 1], [0, -1], [1, 1], [-1, -1], [1, -1], [-1, 1]];
  const spotK = (a, b, salt) => {
    const k = rawK(a, b, salt);
    return k === 2 && NB8.some(([i, j]) => rawK(a + i, b + j, salt) === 2) ? 0 : k;
  };
  const spotAt = (u, v, salt) => {                                            // -1 = ground, else index into SPOTS
    const S = 1.1, gu = Math.floor(u / S), gv = Math.floor(v / S);
    let best = -1, bd = 1;
    for (let du = -1; du <= 1; du++) for (let dv = -1; dv <= 1; dv++) {
      const a = gu + du, b = gv + dv;
      if (hash3(a, b, salt + 5) < 0.3) continue;                               // about a third of the cells: ground
      const cu = (a + 0.15 + 0.7 * hash3(a, b, salt)) * S, cv = (b + 0.15 + 0.7 * hash3(a, b, salt + 1)) * S;
      const k = spotK(a, b, salt);
      const r = (0.45 + 0.15 * hash3(a, b, salt + 2)) * (k === 2 ? 0.9 : 1);   // near-black spots a little smaller
      const d = Math.hypot(u - cu, (v - cv) * 0.9) / r;
      if (d < bd) { bd = d; best = k; }
    }
    return best;
  };
  const cloth = c => {
    const p = c.p;
    const [u, v, salt] = c.face === 'front' ? [p[0], p[1], 11] : c.face === 'back' ? [-p[0], p[1], 13]
      : isSide(c) ? [p[2], p[1], 23] : [p[0], p[2], 37];
    let k = spotAt(u, v, salt);
    const nb = [spotAt(u - px, v, salt), spotAt(u + px, v, salt), spotAt(u, v - px, salt), spotAt(u, v + px, salt)];
    if (!nb.includes(k)) {                                                     // lone cell: take the commonest neighbour
      const cnt = new Map();
      for (const n of nb) cnt.set(n, (cnt.get(n) || 0) + 1);
      k = [...cnt.entries()].sort((x, y) => y[1] - x[1])[0][0];
    }
    const q = snap(p, 0.5);
    const drift = 0.97 + 0.06 * fbm(q[0] * 0.3 + 7, q[1] * 0.3 + 1, q[2] * 0.3 + 3);
    return mul(k >= 0 ? SPOTS[k][0] : P.ground, drift);
  };
  // the padded roll is a shade lighter than the shell
  const rollCloth = c => mul(cloth(c), 1.1);
  // loop (velcro) fields: plain khaki olive with a soft 1-px mottle (never a 1-cell checker)
  const loopCloth = c => {
    const n = hash3(Math.floor(c.p[0] + 300), Math.floor(c.p[1] + 300), Math.floor(c.p[2] + 300));
    return mul(P.loop, (0.96 + 0.08 * n) * G(c, 0.05));
  };
  // strap outline: the spots would swallow a plain darker shade, so the strap's bound edges and stitching are drawn in
  // the binding colour, and its side faces are a little darker
  const LOOK = { strapBind: 0.6, strapSide: 0.7 };

  // ======================================================================================================
  // (everything below is shared with defender_2.js; only the colourway above differs)
  const fab = (c, k = 1) => mul(cloth(c), k * G(c, 0.05));
  const lin = c => mul(P.lining, G(c, 0.08));
  const bind = (c, k = 1) => mul(mix(cloth(c), P.bind, 0.65), k * G(c, 0.05));
  const nU = c => Math.max(1, Math.round(c.fw / px)), nV = c => Math.max(1, Math.round(c.fh / px));
  const iU = c => Math.min(nU(c) - 1, Math.floor(c.eu / px + 1e-6)), iV = c => Math.min(nV(c) - 1, Math.floor(c.ev / px + 1e-6));
  const near = (a, b) => Math.abs(a - b) < 0.1;
  const Y = { band0: 7.5, band1: 11.5, hem1: 12.5 };

  // ---------- horseshoe roll: centre line (used by the roll boxes and by the seams that start under them) ----------
  // blunt tip beside the centre strap, a steep piece, then a flatter one running up into the shoulder corner (its cut end
  // runs from the top edge, a little inside the corner, to the armhole edge) and a short cap carrying it over the shoulder
  const R = Math.PI / 180, TIP = [1.2, 3.25], A1 = 50, L1 = 1.5, A2 = 40, L2 = 3;
  const J = [TIP[0] + L1 * Math.cos(A1 * R), TIP[1] - L1 * Math.sin(A1 * R)];
  // the shoulder piece's centre line ends where its lower corner meets the shell's side edge (|x| 4.5)
  const XEND = 4.5 - 0.5 * Math.sin(A2 * R);
  const rollLow = ax => {                                                     // lower edge of the roll at |x|
    if (ax < TIP[0]) return null;
    const a = ax <= J[0] ? A1 : A2;
    const yc = ax <= J[0] ? TIP[1] - (ax - TIP[0]) * Math.tan(A1 * R) : J[1] - (ax - J[0]) * Math.tan(A2 * R);
    return yc + 0.5 / Math.cos(a * R);
  };
  // "Λ" seams under each arm of the roll: one straight down, one slanting out (a clean 1:2 stair) to the armhole,
  // then down beside it; both start right under the roll and stop at the band
  const outerX = y => 2.25 + 0.5 * Math.ceil(Math.min(5, Math.max(0, Math.round((y - 2.75) / 0.5))) / 2);
  const seam = (ax, y) => {
    const lo = rollLow(ax);
    if (y >= 7 || lo == null || y < lo + 0.1) return false;
    return near(ax, 2.25) || near(ax, outerX(y));
  };

  // ---------- vest shell: front / back panels, deep lined armholes on the sides, stitched hem under the band ----------
  const shell = c => {
    const x = c.p[0], y = c.p[1], z = c.p[2], ax = Math.abs(x);
    if (c.face === 'bottom') return Math.abs(z) < 2.5 && ax < 4.25 ? lin(c) : fab(c, 0.62);
    if (c.face === 'top') return fab(c, 1.02);
    if (isSide(c)) {
      if (y < Y.band0 && Math.abs(z) < 2.25) return lin(c);                   // deep armhole: dark lining
      if (y < Y.band0) return fab(c, 0.98);                                    // rolled edges of the front / back panels
      return y > Y.hem1 - px ? bind(c) : fab(c, 0.88);                         // hem below the band
    }
    // front / back
    if (y >= Y.band1) return y >= Y.hem1 - px ? bind(c, 0.95) : fab(c, 0.9);  // stitched hem strip under the band
    const front = c.face === 'front';
    let k = 1.04 - 0.01 * y;                                                   // lit from above
    if (y < px) k *= 1.06;                                                     // lit top edge
    if (ax > 4.5 - px) k *= 1.05;                                              // rolled armhole edge
    if (y >= 7) k *= 0.88;                                                     // seam along the top of the band
    if (front ? seam(ax, y) : y > 1 && near(ax, 2.25)) k *= 0.86;             // double-stitched seams (back: two plain ones)
    return fab(c, k);
  };
  B.push(box('body', [-4.5, 0, -3], [4.5, Y.hem1, 3], shell, { tag: 'vest shell' }));

  // ---------- padded horseshoe roll on the upper chest ----------
  // each arm is a 1 px roll (lit upper row, shaded lower row) in two straight pieces: the steep piece from the tip, then
  // the flatter one up into the shoulder corner (it starts a little inside the steep one and sits 0.25 further back, so
  // their front faces never share a plane); a short cap on the shell top carries the roll back over the shoulder.
  // The steep piece is 0.75 deep (a whole number of half-pixel cells), so its narrow faces are painted edge to edge
  // instead of leaving an unpainted transparent sliver along one edge
  for (const s of [-1, 1]) {
    const tipFace = s < 0 ? 'left' : 'right', endFace = s < 0 ? 'right' : 'left';
    const roll = tip => c => {
      if (c.face === 'back') return null;                                      // lies on the chest
      const col = mul(rollCloth(c), G(c, 0.05));
      if (c.face === 'top') return mul(col, 1.16);
      if (c.face === 'bottom') return mul(col, 0.6);
      if (c.face === tipFace) return mul(col, tip ? 0.92 : 0.84);
      if (c.face === endFace) return mul(col, 0.84);
      return mul(col, iV(c) === 0 ? 1.12 : 0.92);                              // rounded roll: lit top, shaded bottom
    };
    // piece from (x, y) outward along the angle a (deg above horizontal), length L, front face at z
    const piece = (x, y, a, L, z, tip, tag) => {
      const x0 = s < 0 ? x - L : x, x1 = s < 0 ? x : x + L;
      B.push(box('body', [x0, y - 0.5, z], [x1, y + 0.5, -3], roll(tip), { tag, rot: [0, 0, -s * a], pivot: [x, y, -3.25] }));
    };
    piece(s * TIP[0], TIP[1], A1, L1, -3.75, true, 'collar roll (tip)');
    const back = L2 - (XEND - J[0]) / Math.cos(A2 * R);                      // ~0.37 px of overlap at the joint
    piece(s * (J[0] - back * Math.cos(A2 * R)), J[1] + back * Math.sin(A2 * R), A2, L2, -3.5, false, 'collar roll (shoulder)');
    // over the shoulder: 1 px wide, a little proud of the roll's front so the cut end tucks under it
    const cap = c => {
      if (c.face === 'bottom') return null;                                    // sits on the shell top
      const col = mul(rollCloth(c), G(c, 0.05));
      return mul(col, c.face === 'top' ? 1.16 : c.face === 'front' ? 1.04 : 0.84);
    };
    const [c0, c1] = s < 0 ? [-4.5, -3.5] : [3.5, 4.5];
    B.push(box('body', [c0, -0.25, -3.625], [c1, 0, -2.625], cap, { tag: 'collar roll (over the shoulder)' }));
  }

  // ---------- centre strap: X-box at the top, FORT tag, light loop patch at its foot ----------
  // (the X-box is 4x4 cells: an X that small turns into a checker, so it is drawn as its stitched square - a darker ring
  // of cells round a flat, slightly lit centre)
  const S0 = 1.5, SX = 3.5, SP = 6, S1 = 7;
  B.push(box('body', [-1, S0, -3.25], [1, S1, -3], c => {
    if (c.face === 'back') return null;                                        // lies on the chest
    const y = c.p[1];
    if (y >= SP) {                                                             // loop patch
      const col = loopCloth(c);
      if (c.face !== 'front') return mul(col, c.face === 'top' ? 1.05 : 0.75);
      return iV(c) === nV(c) - 1 ? mul(col, 0.88) : col;
    }
    const col = fab(c, 1.04);
    const rim = k => (LOOK.strapBind ? mix(col, P.bind, LOOK.strapBind) : mul(col, k));   // bound edges / stitching
    if (c.face === 'top') return mul(col, 1.12);
    if (c.face !== 'front') return mul(col, LOOK.strapSide);
    const i = iU(c), j = iV(c), edgeCol = i === 0 || i === 3;
    if (y < SX) return edgeCol || j === 0 || j === 3 ? rim(0.86) : mul(col, 1.04);   // X-box: stitched square
    if (j === 4) return rim(0.9);                                              // seam under the box
    return edgeCol ? rim(0.88) : mul(col, 1.05);                               // strap with bound edges
  }, { tag: 'centre strap' }));
  // small navy FORT tags with a teal logo (one teal cell, one navy cell): on the strap, and low on the wearer's left
  // chest (on the outer seam)
  const tagPaint = c => {
    if (c.face === 'back') return null;
    if (c.face !== 'front') return mul(P.navy, 0.9);
    return iU(c) === 0 ? mix(P.navy, P.teal, 0.7) : P.navy;
  };
  B.push(box('body', [-0.5, 4.5, -3.5], [0.5, 5, -3.25], tagPaint, { tag: 'FORT tag (strap)' }));
  B.push(box('body', [3.25, 5.25, -3.25], [4.25, 5.75, -3], tagPaint, { tag: 'FORT tag (chest)' }));

  // ---------- waist band (cummerbund) ----------
  // sides and back: three puffy elastic rows with shaded seams between them; front: the loop field (centre seam)
  const ROWK = [1.1, 0.98, 0.78, 1.08, 0.97, 0.78, 1.06, 0.86];
  const bandCloth = (c, k = 1) => mul(cloth(c), 0.96 * k * G(c, 0.05));
  const LX = 1.75;                                                             // half-width of the loop field
  B.push(box('body', [-4.75, Y.band0, -3.5], [4.75, Y.band1, 3.5], c => {
    if (c.face === 'top') return bandCloth(c, 1.05);
    if (c.face === 'bottom') return bandCloth(c, 0.6);
    const y = c.p[1], ax = Math.abs(c.p[0]);
    if (c.face === 'front') {
      if (ax < LX + 1 && y > 7.75 && y < 11.25) {                              // loop field (also under the flaps' cut corners)
        const col = loopCloth(c);
        if (ax < 0.2) return mul(col, 0.8);                                    // centre seam
        return y < 8.25 ? mul(col, 1.05) : y > 10.75 ? mul(col, 0.88) : col;
      }
      return bandCloth(c, y < 8 ? 1.08 : 0.8);                                 // band edges above / below the flaps
    }
    return bandCloth(c, ROWK[Math.min(7, Math.floor((y - Y.band0) / px + 1e-6))]);
  }, { tag: 'waist band' }));
  // closure flaps: darker ends of the band lying on the front, chamfered inner corners, lit inner end, stitch groove
  for (const s of [-1, 1]) {
    const FY0 = 7.75, FY1 = 11.25, nRows = (FY1 - FY0) / px;
    const flap = c => {
      if (c.face === 'back') return null;
      const ax = Math.abs(c.p[0]);
      const i = Math.floor((ax - LX) / px + 1e-6), jT = Math.min(nRows - 1, Math.floor((c.p[1] - FY0) / px + 1e-6)), jB = nRows - 1 - jT;
      const ch = i + Math.min(jT, jB);
      if (ch < 2) return null;                                                 // chamfered inner corners
      const col = bandCloth(c, 0.9);
      if (c.face === 'top') return mul(col, 1.12);
      if (c.face === 'bottom') return mul(col, 0.62);
      if (c.face !== 'front') return mul(col, 0.8);
      if (ch === 2 || i === 0) return mul(col, 1.12);                          // lit rim along the inner end / chamfers
      if (i === 3) return mul(col, 0.82);                                      // stitch groove
      return jT === 0 ? mul(col, 1.06) : jB === 0 ? mul(col, 0.86) : col;
    };
    const [a, b] = s < 0 ? [-4.75, -LX] : [LX, 4.75];
    B.push(box('body', [a, FY0, -3.75], [b, FY1, -3.5], flap, { tag: 'band closure flap' }));
  }

  // ---------- groin protector: about as wide as the vest front, straight sides for half its length, then rounding in to a
  // blunt bottom about half as wide; thin binding; a shade darker than the shell; tucked under the hem ----------
  const GR = [[12, 14.5, 4.25], [14.5, 15, 4], [15, 15.5, 3.5], [15.5, 16, 3], [16, 16.5, 2]];
  GR.forEach(([y0, y1, hw], r) => {
    const last = r === GR.length - 1;
    const groin = c => {
      const ax = Math.abs(c.p[0]), y = c.p[1];
      if (c.face === 'back') return lin(c);
      if (c.face === 'top') return fab(c, 0.9);
      if (c.face !== 'front') return bind(c, 0.85);
      const shade = 0.86 - 0.06 * clamp01((y - 12.5) / 4);
      // thin binding: the outermost cell of each row (so it follows the rounded outline) and the bottom row
      if (ax > hw - px + 1e-6 || (last && y > y1 - px)) return bind(c);
      if (y < 12.5 + px) return mul(mix(fab(c), P.bind, 0.25), 0.74);         // shadow / binding line under the hem
      return fab(c, shade);
    };
    B.push(box('body', [-hw, y0, -2.875], [hw, y1, -2.375], groin, { tag: r ? 'groin protector (rounded end)' : 'groin protector' }));
  });
  return { S: K.S, px: K.px, cell: K.cell, boxes: B };
};
