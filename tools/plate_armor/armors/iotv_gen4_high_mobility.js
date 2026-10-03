// ================= IOTV Gen4 (High Mobility Kit, MultiCam) — EFT reference (tarkov.dev 5b44d0de86f774503d30cba8) =================
// Same carrier as the Full Protection kit (armors/iotv.js), same MultiCam and dark teal-green binding, but WITHOUT the
// deltoid protectors: the reference render shows big open armholes (near-black inside, teal binding round the edge) from
// the broad MultiCam shoulder straps down to about mid-torso. Kept from the full kit: tall padded stand collar (darker,
// greener MultiCam, dark lining, teal rim), shoulder-strap flaps with silver D-rings, pale grey-khaki loop ID patch (small
// grey tab under it), two MOLLE rows on the upper chest, proud plate pocket with more rows, MOLLE cummerbund on the lower
// side only with the grey "XII" tab (stitched box + two bars) on the wearer's right coming round the front edge and a grey
// front tab beside the pocket on the wearer's left, one central strap with a metal ladder-lock buckle down to the groin
// shield (stitched top band, sides running in to a broad rounded tip, stitched seam across its lower third) and the broad
// lower-back protector (MultiCam outside; the render shows its black padded inner face below the back hem).
ARMORS.iotv_gen4_high_mobility = function (mode) {
  const K = kit('M'), { edge, G, isPanel, isSide, px } = K;
  const bw = px;                                               // one art cell: binding / frame width
  const LINING = hex('#2f3a2c'), DLIN = hex('#38352c'), BIND = hex('#3a5a4e'), WEB = hex('#8a8262'),
    LOOP = hex('#c4bca0'), METAL = hex('#a3a39a'), LABEL = hex('#c9c3ad'), INK = hex('#2a2a26'),
    PAD = hex('#1e211c'), PADST = hex('#3b4a3d'), STRAP = hex('#5e5b45'), GAP = hex('#3f3d2d'), ARMHOLE = hex('#1f231d'),
    TAB = hex('#6e6857');
  const md = (v, m) => ((v % m) + m) % m;
  // MultiCam on a 0.5px grid at a coarse scale (no single-cell specks), nudged toward the render's greener olive
  const tint = c => [c[0] * 0.97, c[1] * 1.01, c[2] * 1.06];
  const camoAt = p => tint(multicam(snap(p, 0.5).map(v => v * 0.65), 0));
  const mc = c => mul(camoAt(c.p), G(c, 0.04));
  const lin = (c, col = LINING) => mul(col, G(c, 0.08));
  const bind = c => mul(BIND, G(c, 0.06));
  const B = [];

  // MOLLE rows from y0 (n rows), h = horizontal coordinate along the face: chunky 1px tapes that are mostly webbing (the
  // camo only shows through faintly, its darkest shapes lifted) over one dark 0.5px shadow line, period 1.5px, tacks every 3px
  const LUM = v => v[0] * 0.3 + v[1] * 0.59 + v[2] * 0.11, BASE_L = 0.8 * LUM(tint(hex('#a69c74')));
  const molleRows = (c, col, y0, n, h) => {
    const dy = c.p[1] - y0;
    if (dy < 0) return null;
    const i = Math.floor(dy / 1.5);
    if (i >= n) return null;
    const r = dy - i * 1.5;
    if (r >= 1) return mix(mul(GAP, G(c, 0.08)), mul(col, 0.5), 0.2);
    const l = LUM(col), t = mix(l < BASE_L ? mul(col, BASE_L / l) : col, WEB, 0.65);
    if (md(h + 1.75, 3) < 0.5) return mul(t, 0.74);
    return mul(t, r < 0.5 ? 1.08 : 0.96);
  };

  // ---------- carrier: big dark armholes, MOLLE cummerbund sides, "XII" tab on the wearer's right, bound hem ----------
  // grey-khaki webbing tab over the cummerbund's middle row, reaching the front edge and running back about half the side;
  // its stitching in a mid tone, read from the back end toward the front ('#' = stitch, 'x' = the X's crossing): the stitched
  // box-X (a 1.5px square: at this size its outline and diagonals fill it), the two close bars merged into one 1px bar (two
  // 0.5px bars would alternate every cell), then a plain end at the front corner
  const LBL = { z: [-2.75, 0.75], y: [7.5, 9], pat: ['###.##.', '#x#.##.', '###.##.'] };
  const TABC = mix(LABEL, TAB, 0.2), STITCH = mix(LABEL, INK, 0.45);
  const label = c => {
    if (c.face !== 'right') return null;
    const z = c.p[2], y = c.p[1];
    if (z < LBL.z[0] || z >= LBL.z[1] || y < LBL.y[0] || y >= LBL.y[1]) return null;
    const n = LBL.pat[0].length, m = LBL.pat.length;
    const iFront = Math.floor((z - LBL.z[0]) / (LBL.z[1] - LBL.z[0]) * n), j = Math.floor((y - LBL.y[0]) / (LBL.y[1] - LBL.y[0]) * m);
    const g = LBL.pat[j][n - 1 - iFront], col = g === '#' ? STITCH : g === 'x' ? mul(STITCH, 0.86) : TABC;
    return mul(col, G(c, 0.04) * (j === 0 ? 1.05 : j === m - 1 ? 0.92 : 1));
  };
  // the armhole (above the cummerbund, from the shoulder down to about mid-torso): the carrier's dark inside, framed by the
  // teal binding (shows whenever the arm swings)
  const armhole = c => {
    const y = c.p[1], az = Math.abs(c.p[2]);
    if (y >= 5.5) return null;
    if (az >= 2.25 || y >= 5) return bind(c);
    return mul(ARMHOLE, G(c, 0.08));
  };
  const vest = c => {
    const col = mc(c);
    if (c.face === 'bottom') return bind(c);
    if (c.face === 'top') return edge(c, col, { stitch: false });
    if (c.ev > c.fh - bw) return bind(c);
    if (isPanel(c) && c.p[1] < 5.5 && Math.abs(c.p[0]) > 4.5 - bw) return bind(c);   // armhole binding round the corner
    if (isSide(c)) {
      const a = armhole(c);
      if (a) return a;
      const l = label(c);
      if (l) return l;
      if (Math.abs(c.p[2]) <= 2.25) { const r = molleRows(c, col, 6, 3, c.p[2]); if (r) return r; }
    }
    return edge(c, col, { stitch: false });
  };
  B.push(box('body', [-4.5, 0, -2.75], [4.5, 11, 2.75], vest, { tag: 'carrier / cummerbund' }));

  // ---------- front: two MOLLE rows across the upper chest, then the proud plate pocket with a bound outline ----------
  const FX = 3.25;
  const upper = c => {
    const col = mc(c);
    if (c.face !== 'front') return c.face === 'top' ? col : mul(col, 0.8);
    const r = molleRows(c, col, 3.5, 2, c.p[0]) || col;
    return Math.abs(c.p[0]) > FX - bw ? mul(r, 0.74) : r;
  };
  B.push(box('body', [-FX, 3.5, -3.25], [FX, 6, -2.75], upper, { tag: 'upper MOLLE' }));
  const pocket = c => {
    const col = mc(c);
    if (c.face === 'back') return lin(c, DLIN);
    if (c.face === 'bottom') return bind(c);
    if (c.face !== 'front') return mul(mix(col, BIND, 0.4), 0.9);
    if (c.ex < bw) return mix(col, BIND, 0.55);
    return molleRows(c, col, 6.5, 3, c.p[0]) || col;
  };
  B.push(box('body', [-FX, 6, -3.5], [FX, 11, -2.75], pocket, { tag: 'front plate pocket' }));
  // the cummerbund's grey-khaki front tab peeking out beside the pocket on the wearer's left, level with the "XII" tab
  B.push(box('body', [FX, 7.5, -3.25], [FX + 0.5, 9, -2.75], c => {
    const col = mul(TABC, G(c, 0.04));
    if (c.face === 'back' || c.face === 'right') return null;
    if (c.face === 'top') return mul(col, 1.06);
    if (c.face === 'bottom') return mul(col, 0.7);
    return c.face === 'front' ? col : mul(col, 0.86);
  }, { tag: 'cummerbund front tab' }));
  const backField = c => {
    const col = mc(c);
    if (c.face !== 'back') return c.face === 'top' ? col : mul(col, 0.8);
    return edge(c, molleRows(c, col, 1.5, 6, -c.p[0]) || col, { stitch: false });
  };
  B.push(box('body', [-FX, 1, 2.75], [FX, 10.5, 3.25], backField, { tag: 'back MOLLE' }));

  // ---------- chest: loop ID patch on a camo flap, shoulder-strap flaps with D-rings hanging from their lower ends ----------
  // (the flap's camo is lifted to at least the ground tone: MultiCam's darkest shapes in a one-cell border read as holes)
  const flapCamo = c => { const m = mc(c), l = LUM(m); return l < BASE_L ? mul(m, BASE_L / l) : m; };
  const idPanel = c => {
    if (c.face === 'back') return lin(c);
    if (c.face !== 'front') return mul(mix(flapCamo(c), BIND, 0.3), 0.9);
    if (c.ex < bw) return mul(mix(flapCamo(c), BIND, 0.35), 0.85);           // the camo flap's stitched border
    return mul(LOOP, 1 + (hash3(c.p[0] * 4, c.p[1] * 4, 9) - 0.5) * 0.05);
  };
  B.push(box('body', [-1.5, 1.5, -3.5], [1.5, 3.5, -3], idPanel, { tag: 'ID loop panel' }));
  // small grey-khaki webbing tab under the patch's centre, over the first MOLLE row
  B.push(box('body', [-0.5, 3.5, -3.5], [0.5, 4.25, -3.25], c => {
    const col = mul(mix(LABEL, TAB, 0.45), G(c, 0.05));
    if (c.face === 'back') return null;
    if (c.face === 'top') return mul(col, 1.08);
    if (!isPanel(c)) return mul(col, 0.78);
    return c.ev > c.fh - bw ? mul(col, 0.84) : col;
  }, { tag: 'ID tab' }));
  // D-ring: flat bar on top (through the flap's end), round bottom; cut out in the middle. Front face only: a back copy
  // would lie in the plane of the flaps' / upper MOLLE's front faces and shimmer through the ring's holes
  const DP = ['###', '#.#', '.#.'];
  const dring = c => {
    if (c.face !== 'front') return null;
    const n = DP.length, i = Math.min(n - 1, Math.floor(c.eu / c.fw * n)), j = Math.min(n - 1, Math.floor(c.ev / c.fh * n));
    return DP[j][i] === '#' ? mul(METAL, j === 0 ? 1.12 : 1) : null;
  };
  // flap: camo strap coming over the shoulder from under the collar; bound long edges and bound lower end
  const flap = c => {
    if (c.face === 'back') return lin(c);
    const col = mc(c);
    if (c.face === 'bottom') return bind(c);
    if (c.face === 'top') return col;
    if (!isPanel(c)) return mul(mix(col, BIND, 0.5), 0.85);
    if (c.ev > c.fh - bw || c.eu < bw || c.eu > c.fw - bw) return mix(col, BIND, 0.7);
    return col;
  };
  for (const s of [-1, 1]) {
    const X = (a, b) => (s < 0 ? [-b, -a] : [a, b]);
    // turned 10deg about its top-outer corner so it runs down toward the ID panel, as in the reference
    const [f0, f1] = X(1.5, 4);
    B.push(box('body', [f0, 1, -3.25], [f1, 3, -2.75], flap, { tag: 'shoulder strap flap', rot: [0, 0, -s * 10], pivot: [s * 4, 1, -3] }));
    const ds = 1.5, cx = s * 2.75;
    B.push(box('body', [cx - ds / 2, 2.75, -3.5], [cx + ds / 2, 2.75 + ds, -3.25], dring, { tag: 'D-ring' }));
  }

  // ---------- one central strap + ladder-lock buckle from the hem onto the groin protector's top band ----------
  B.push(box('body', [-0.5, 10.25, -3.75], [0.5, 12.75, -3.5], c => edge(c, mul(STRAP, G(c, 0.05)), { stitch: false }), { tag: 'hem strap' }));
  const BP = ['###', '#.#'];
  B.push(box('body', [-0.75, 11.25, -3.875], [0.75, 12.25, -3.625], c => {
    if (!isPanel(c)) return mul(METAL, c.face === 'top' ? 1.2 : 0.7);
    const n = BP[0].length, m = BP.length;
    const i = Math.min(n - 1, Math.floor(c.eu / c.fw * n)), j = Math.min(m - 1, Math.floor(c.ev / c.fh * m));
    return BP[j][i] === '#' ? mul(METAL, j === 0 ? 1.1 : 1) : mul(STRAP, 0.55);
  }, { tag: 'ladder-lock buckle' }));

  // ---------- collar: tall stand collar hugging the neck, highest behind it; lining toward the neck, bound rim ----------
  // (a separate roll in a darker, greener MultiCam than the carrier: the render's darkest block round the head)
  const collar = inward => c => {
    if (c.face === inward) return lin(c);
    if (c.face === 'bottom') return mul(lin(c), 0.85);
    if (c.face === 'top') return bind(c);
    const col = mul(mix(mc(c), LINING, 0.45), 0.78);
    if (c.ev < bw) return mix(col, BIND, 0.7);
    return edge(c, col, { stitch: false });
  };
  // front halves: only the strip below the chin shows; a V opening in the middle shows the lining
  const cFront = collar('back');
  B.push(box('body', [-4.5, -0.5, -3.5], [4.5, 1, -2.75], c => (c.face === 'front' && Math.abs(c.p[0]) < 1.25 - c.p[1] ? lin(c) : cFront(c)), { tag: 'collar front' }));
  // sides: flush against the hat layer beside the head, turned 4deg so the outside flares a little
  for (const s of [-1, 1]) {
    const xa = s < 0 ? [-5.5, -4.5] : [4.5, 5.5];
    B.push(box('body', [xa[0], -2.5, -3.75], [xa[1], 0, 5.25], collar(s < 0 ? 'left' : 'right'), { tag: 'collar side', rot: [0, 0, s * 4], pivot: [s * 4.5, -2.5, 0] }));
  }
  // shoulder straps: as on the render they end flush with the carrier's sides (the armholes cut in right below them, the
  // teal binding on the carrier's front / back edges is their outer edge), so nothing lies over the arm: no yoke box past
  // the collar side (it widened the shoulders and the sleeve cut through it on every step)
  // back: rises highest, behind the hat layer; its ends tuck into the flared side pieces
  const cBack = collar('front');
  B.push(box('body', [-4.75, -3, 3.25], [4.75, 1, 5.5], c => (c.face === 'top' ? (c.p[2] < 5 ? lin(c) : bind(c)) : cBack(c)), { tag: 'collar back' }));

  // ---------- groin protector: one shield cut out of a single panel (alpha outline, the binding follows the cut) ----------
  // Wide straight top band; below it the sides run steadily inward to the seam (about two thirds of the top width), then
  // a little more steeply down to a broad rounded tip, so the shield is about as long as it is wide.
  //   Y0: bottom of the top band; YS: the seam; HS / HB: half-width at the seam / at the bottom edge; RC: corner rounding
  const Y0 = 11.5, YS = 14.25, YT = 16.5, HS = 2, HB = 0.9, RC = 0.75;
  const hwAt = y => (y < Y0 ? 3 : y < YS ? 3 - (3 - HS) * (y - Y0) / (YS - Y0) : HS - (HS - HB) * (y - YS) / (YT - YS));
  const inShield = (ax, y) => {                                                            // distance inside the outline (<= 0: cut)
    const ds = hwAt(y) - ax, db = YT - y;
    return db < RC && ds < RC ? RC - Math.hypot(RC - db, RC - ds) : Math.min(ds, db);
  };
  B.push(box('body', [-3, 11, -3.5], [3, YT, -3.25], c => {
    const ax = Math.abs(c.p[0]), y = Math.min(c.p[1], YT - px / 2);                        // (the bottom face uses the last row)
    if (c.face === 'top') return bind(c);
    if (isSide(c)) return y < Y0 ? bind(c) : null;                                        // below the band the sides are cut in
    const d = inShield(ax, y);
    if (d <= 0) return null;
    if (c.face === 'bottom') return bind(c);
    if (c.face === 'back') return lin(c);
    const col = mc(c);
    if (d < bw) return mix(col, BIND, 0.75);                                                // bound outline (no line at the seam)
    if (y < Y0) return mix(col, BIND, 0.45);                                                // stitched top band
    return col;
  }, { tag: 'groin shield' }));
  // stitched seam across the shield's lower third: a straight band standing a hair proud, never past the outline
  const SY = YS - 0.25;
  B.push(box('body', [-2.5, SY, -3.625], [2.5, SY + 0.5, -3.375], c => {
    const yy = Math.min(Math.max(c.p[1], SY + px / 2), SY + 0.5 - px / 2);             // row centre (top / bottom faces use the edge row)
    if (Math.abs(c.p[0]) >= hwAt(yy) - bw * 0.6 || c.face === 'back') return null;
    const b = mix(mc(c), BIND, 0.5);
    if (c.face !== 'front') return mul(b, c.face === 'top' ? 1.1 : 0.8);
    return b;
  }, { tag: 'groin seam' }));

  // ---------- lower-back protector: MultiCam outside with bound edges; black padded inner face with green stitch rows ----------
  // (nearly the carrier's full width, its corners out to the back corners of the cummerbund, as on the render)
  B.push(box('body', [-4, 10.5, 2.75], [4, 14, 3.5], c => {
    if (c.face === 'front') return md(c.p[1] - 11.5, 1) < bw ? mul(PADST, G(c, 0.06)) : mul(PAD, G(c, 0.08));
    if (c.face !== 'back') return bind(c);
    if (c.eu < bw || c.eu > c.fw - bw || c.ev > c.fh - bw) return bind(c);
    return mc(c);
  }, { tag: 'lower back protector' }));
  return { S: K.S, px: K.px, cell: K.cell, boxes: B };
};
