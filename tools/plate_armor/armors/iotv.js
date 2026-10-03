// ================= IOTV Gen4 (Full Protection Kit, MultiCam) — EFT reference (tarkov.dev 5b44cd8b86f774503d30cba2) =================
// Reference render: very tall padded stand collar (MultiCam outside, dark green lining, dark teal-green binding on the rim;
// highest behind the neck, hugging it, the two front halves meet in a V under the chin); two MultiCam shoulder-strap flaps
// with bound edges running diagonally down toward the chest centre, big silver D-rings hanging from their lower ends; a pale
// grey-khaki loop ID patch on a camo flap between them; two MOLLE rows across the upper chest, then a proud front plate
// pocket (stitched outline) with more rows; cummerbund sides with MOLLE and a grey tab (box-X stitch + two bars, read as
// "XII") on the wearer's right near the front edge; big flared deltoid shells (dark brown-grey lining, teal binding, a narrow
// pale loop strip down the outer shell into a wide pale loop band on the lower outer face, a dark webbing strap round the
// upper arm); ONE central strap with a metal ladder-lock buckle from the hem to the groin protector's stitched top band; the
// groin shield is one piece (stitched straight top band, sides running steadily in to a broad rounded tip, a stitched seam
// across its lower third, about as long as it is wide); the collar is a darker, greener MultiCam than the carrier; the
// deltoids' pale loop band runs round the whole lower rim (outer face and front / back), just above the binding;
// the lower-back protector hangs below the back hem (MultiCam outside; the render only shows its black padded inner face).
ARMORS.iotv = function iotv(mode) {
  const K = kit(mode), { hd, mid, edge, G, isPanel, isSide } = K;
  const A = !hd, bw = A ? 1 : K.px;                           // bw: one art cell (binding / frame width)
  const LINING = hex('#2f3a2c'), DLIN = hex('#38352c'), BIND = hex('#3a5a4e'), WEB = hex('#8a8262'), WEB_A = hex('#9c9474'),
    LOOP = hex('#c4bca0'), VEL = hex('#bdb59a'), METAL = hex('#a3a39a'), LABEL = hex('#c9c3ad'), INK = hex('#2a2a26'),
    PAD = hex('#1e211c'), PADST = hex('#3b4a3d'), STRAP = hex('#5e5b45'), GAP = hex('#3f3d2d');
  const md = (v, m) => ((v % m) + m) % m;
  // MultiCam, nudged from warm brown toward the reference's greener olive. A samples a 1px grid, M a 0.5px grid at a coarser
  // scale (no single-texel specks), B full resolution with the brown layers pulled toward olive (full-res B otherwise reads desert).
  const tint = c => [c[0] * 0.97, c[1] * 1.01, c[2] * 1.06];
  // (A drops MultiCam's two smallest layers: at one texel per pixel they only add dark / cream specks)
  const camoA = p => layers(p, 0, '#a69c74', [['#c3b893', 0.55, 0.6, 3], ['#7a7f50', 0.62, 0.6, 17], ['#8a7050', 0.7, 0.62, 41], ['#5a5236', 0.9, 0.68, 77]]);
  const camoB = p => layers(p, 0, '#a69c74', [['#c3b893', 0.55, 0.6, 3], ['#7a7f50', 0.62, 0.6, 17], ['#826e4e', 0.7, 0.65, 41], ['#5a5236', 0.9, 0.66, 77], ['#463e2d', 1.6, 0.72, 5], ['#cfc6a2', 1.8, 0.74, 91]]);
  const camoAt = p => tint(A ? camoA(snap(p, 1).map(v => v * 0.6)) : mid ? multicam(snap(p, 0.5).map(v => v * 0.65), 0) : camoB(p));
  const mc = c => mul(camoAt(c.p), G(c, mid ? 0.04 : 0.07));
  const lin = (c, col = LINING) => mul(col, G(c, 0.08));
  const bind = c => mul(BIND, G(c, 0.06));
  const B = [];

  // MOLLE rows from y0 (n rows), h = horizontal coordinate along the face.
  //   A: 1px light tape + 1px shadow row;  M: chunky 1px tapes that are mostly webbing (camo only shows through faintly, its
  //   darkest shapes lifted) over one fixed dark 0.5px shadow line, period 1.5px, bar tacks every 3px;
  //   B: 0.5px tapes, 0.25px shadow, period 1px, tacks every 1.25px.
  const LUM = v => v[0] * 0.3 + v[1] * 0.59 + v[2] * 0.11, BASE_L = 0.8 * LUM(tint(hex('#a69c74')));
  const molleRows = (c, col, y0, n, h) => {
    const dy = c.p[1] - y0;
    if (dy < 0) return null;
    if (A) {
      const i = Math.floor(dy / 2);
      if (i >= n) return null;
      if (dy - i * 2 >= 1) return mul(col, 0.76);                                         // shadow row under the tape
      return mul(mix(col, WEB_A, 0.65), 1.06);                                           // (no tacks: a 6px field keeps 4 light texels)
    }
    if (mid) {
      const i = Math.floor(dy / 1.5);
      if (i >= n) return null;
      const r = dy - i * 1.5;
      if (r >= 1) return mix(mul(GAP, G(c, 0.08)), mul(col, 0.5), 0.2);
      const l = LUM(col), t = mix(l < BASE_L ? mul(col, BASE_L / l) : col, WEB, 0.65);
      if (md(h + 1.75, 3) < 0.5) return mul(t, 0.74);
      return mul(t, r < 0.5 ? 1.08 : 0.96);
    }
    const i = Math.floor(dy);
    if (i >= n) return null;
    const r = dy - i;
    if (r >= 0.5) return r < 0.75 ? mul(col, 0.62) : null;
    if (md(h + 3.125, 1.25) < 0.25) return mul(col, 0.5);
    return mul(col, r < 0.25 ? 1.12 : 0.96);
  };

  // ---------- carrier: cummerbund sides with MOLLE, "XII" tab on the wearer's right near the front edge, bound hem ----------
  // tab rect (z = front..back, y) and its glyphs, read from the back edge toward the front ('#' = ink); A: a plain light tab
  const LBL = A ? { z: [-2, 0], y: [7, 8], pat: ['..'] }
    : mid ? { z: [-2.25, 1.25], y: [7, 8.5], pat: ['#.#.#.#', '.#..#.#', '#.#.#.#'] }
    : { z: [-2.25, -0.25], y: [6.75, 7.75], pat: ['#..#.#.#', '.##..#.#', '.##..#.#', '#..#.#.#'] };
  const label = c => {
    if (c.face !== 'right') return null;
    const z = c.p[2], y = c.p[1];
    if (z < LBL.z[0] || z >= LBL.z[1] || y < LBL.y[0] || y >= LBL.y[1]) return null;
    const n = LBL.pat[0].length, m = LBL.pat.length;
    const iFront = Math.floor((z - LBL.z[0]) / (LBL.z[1] - LBL.z[0]) * n), j = Math.floor((y - LBL.y[0]) / (LBL.y[1] - LBL.y[0]) * m);
    return mul(LBL.pat[j][n - 1 - iFront] === '#' ? INK : LABEL, G(c, 0.04));
  };
  const SIDE = A ? [4, 3] : mid ? [4, 4] : [4, 6];
  const vest = c => {
    const col = mc(c);
    if (c.face === 'bottom') return bind(c);
    if (c.face === 'top') return edge(c, col, { stitch: false });
    if (hd && c.ev > c.fh - bw) return bind(c);
    if (isSide(c)) {
      const l = label(c);
      if (l) return l;
      if (Math.abs(c.p[2]) <= 2.25) { const r = molleRows(c, col, SIDE[0], SIDE[1], c.p[2]); if (r) return r; }
    }
    return edge(c, col, { stitch: false });
  };
  B.push(box('body', [-4.5, 0, -2.75], [4.5, 11, 2.75], vest, { tag: 'carrier / cummerbund' }));

  // ---------- front: two MOLLE rows across the upper chest, then the proud plate pocket with a bound outline ----------
  const FX = A ? 3 : 3.25;
  const UP = A ? [4, 1] : mid ? [3.5, 2] : [3.5, 3], PK = A ? [7, 2] : mid ? [6.5, 3] : [6.5, 4], BK = A ? [2, 4] : mid ? [1.5, 6] : [1.5, 9];
  const upper = c => {
    const col = mc(c);
    if (c.face !== 'front') return c.face === 'top' ? col : mul(col, 0.8);
    const r = molleRows(c, col, UP[0], UP[1], c.p[0]) || col;
    return Math.abs(c.p[0]) > FX - bw ? mul(r, 0.74) : r;
  };
  B.push(box('body', [-FX, A ? 3 : 3.5, -3.25], [FX, 6, -2.75], upper, { tag: 'upper MOLLE' }));
  const pocket = c => {
    const col = mc(c);
    if (c.face === 'back') return lin(c, DLIN);
    if (c.face === 'bottom') return bind(c);
    if (c.face !== 'front') return mul(mix(col, BIND, 0.4), 0.9);
    if (c.ex < bw) return mix(col, BIND, 0.55);
    return molleRows(c, col, PK[0], PK[1], c.p[0]) || col;
  };
  B.push(box('body', [-FX, 6, -3.5], [FX, 11, -2.75], pocket, { tag: 'front plate pocket' }));
  const backField = c => {
    const col = mc(c);
    if (c.face !== 'back') return c.face === 'top' ? col : mul(col, 0.8);
    return edge(c, molleRows(c, col, BK[0], BK[1], -c.p[0]) || col, { stitch: false });
  };
  B.push(box('body', [-FX, 1, 2.75], [FX, A ? 10 : 10.5, 3.25], backField, { tag: 'back MOLLE' }));

  // ---------- chest: loop ID patch on a camo flap, shoulder-strap flaps with D-rings hanging from their lower ends ----------
  const idPanel = c => {
    if (c.face === 'back') return lin(c);
    if (c.face !== 'front') return mul(mix(mc(c), BIND, 0.3), 0.9);
    if (c.ex < bw) return mul(mix(mc(c), BIND, 0.35), 0.85);                 // the camo flap's stitched border
    return mul(LOOP, 1 + (hash3(c.p[0] * 4, c.p[1] * 4, 9) - 0.5) * (A ? 0.03 : mid ? 0.05 : 0.12));
  };
  B.push(box('body', A ? [-2, 1, -3.5] : [-1.5, 1.5, -3.5], A ? [2, 4, -3.25] : [1.5, 3.5, -3], idPanel, { tag: 'ID loop panel' }));
  // D-ring: flat bar on top (through the flap's end), round bottom; cut out in the middle
  const DP = mid ? ['###', '#.#', '.#.'] : ['#####', '#...#', '#...#', '##.##', '.###.'];
  const dring = c => {
    if (!isPanel(c)) return null;
    const n = DP.length, i = Math.min(n - 1, Math.floor(c.eu / c.fw * n)), j = Math.min(n - 1, Math.floor(c.ev / c.fh * n));
    return DP[j][i] === '#' ? mul(METAL, j === 0 ? 1.12 : 1) : null;
  };
  // flap: camo strap coming over the shoulder from under the collar; bound long edges (inner / outer ends) and bound lower end
  const flap = c => {
    if (c.face === 'back') return lin(c);
    const col = mc(c);
    if (c.face === 'bottom') return bind(c);
    if (c.face === 'top') return col;
    if (!isPanel(c)) return mul(mix(col, BIND, 0.5), 0.85);
    if (A) return c.ev >= 1 ? bind(c) : col;
    if (c.ev > c.fh - bw || c.eu < bw || c.eu > c.fw - bw) return mix(col, BIND, 0.7);
    return col;
  };
  for (const s of [-1, 1]) {
    const X = (a, b) => (s < 0 ? [-b, -a] : [a, b]);
    if (A) {
      const [f0, f1] = X(2, 4), [r0, r1] = X(2.5, 3.5);
      B.push(box('body', [f0, 1, -3.25], [f1, 3, -2.75], flap, { tag: 'shoulder strap flap' }));
      B.push(box('body', [r0, 3, -3.5], [r1, 4, -3.25], K.solid(METAL, 0.04), { tag: 'D-ring' }));
      continue;
    }
    // (M/B) turned 10deg about its top-outer corner so it runs down toward the ID panel, as in the reference
    const [f0, f1] = X(1.5, 4);
    B.push(box('body', [f0, 1, -3.25], [f1, 3, -2.75], flap, { tag: 'shoulder strap flap', rot: [0, 0, -s * 10], pivot: [s * 4, 1, -3] }));
    const ds = mid ? 1.5 : 1.25, cx = s * 2.75;
    B.push(box('body', [cx - ds / 2, 2.75, -3.5], [cx + ds / 2, 2.75 + ds, -3.25], dring, { tag: 'D-ring' }));
  }

  // ---------- one central strap + ladder-lock buckle from the hem onto the groin protector's top band ----------
  if (A) {
    B.push(box('body', [-0.5, 10, -3.75], [0.5, 12.5, -3.5], c => (c.p[1] >= 11 && c.p[1] < 12 ? mul(METAL, c.face === 'top' ? 1.3 : 1) : edge(c, mul(STRAP, G(c, 0.05)), { stitch: false })), { tag: 'hem strap + buckle' }));
  } else {
    B.push(box('body', [-0.5, 10.25, -3.75], [0.5, 12.75, -3.5], c => edge(c, mul(STRAP, G(c, 0.05)), { stitch: false }), { tag: 'hem strap' }));
    const BP = mid ? ['###', '#.#'] : ['######', '#....#', '######', '#....#', '######'];
    B.push(box('body', [-0.75, 11.25, -3.875], [0.75, mid ? 12.25 : 12.5, -3.625], c => {
      if (!isPanel(c)) return mul(METAL, c.face === 'top' ? 1.2 : 0.7);
      const n = BP[0].length, m = BP.length;
      const i = Math.min(n - 1, Math.floor(c.eu / c.fw * n)), j = Math.min(m - 1, Math.floor(c.ev / c.fh * m));
      return BP[j][i] === '#' ? mul(METAL, j === 0 ? 1.1 : 1) : mul(STRAP, 0.55);
    }, { tag: 'ladder-lock buckle' }));
  }

  // ---------- collar: tall stand collar hugging the neck, highest behind it; lining toward the neck, bound rim ----------
  // The collar is a separate roll in a darker, greener MultiCam than the carrier (the reference's darkest block round the head)
  const collar = inward => c => {
    if (c.face === inward) return lin(c);
    if (c.face === 'bottom') return mul(lin(c), 0.85);
    if (c.face === 'top') return bind(c);
    const col = mul(mix(mc(c), LINING, 0.45), 0.78);
    if (hd && c.ev < bw) return mix(col, BIND, 0.7);
    return edge(c, col, { stitch: false });
  };
  // front halves (one box; only the strip below the chin shows, down to where the strap flaps start): a V opening in the
  // middle shows the lining. Its ends stop at the carrier's width, so an arm raised forward (aiming) barely touches it.
  const cFront = collar('back');
  B.push(box('body', [-4.5, A ? -1 : -0.5, -3.5], [4.5, 1, -2.75], c => (c.face === 'front' && Math.abs(c.p[0]) < (1.25 - c.p[1]) * (A ? 0.9 : 1) ? lin(c) : cFront(c)), { tag: 'collar front' }));
  // sides: flush against the hat layer, only beside the head (down to the shoulder line, out of a raised arm's way);
  // (M/B) turned 4deg about the inner top edge so the outside flares a little while the top stays tight to the head;
  // front ends stand 0.25 proud of the front halves
  for (const s of [-1, 1]) {
    const xa = s < 0 ? [-5.5, -4.5] : [4.5, 5.5], top = A ? -2 : -2.5;
    B.push(box('body', [xa[0], top, -3.75], [xa[1], 0, 5.25], collar(s < 0 ? 'left' : 'right'),
      A ? { tag: 'collar side' } : { tag: 'collar side', rot: [0, 0, s * 4], pivot: [s * 4.5, top, 0] }));
  }
  // back: rises highest, behind the hat layer; its ends meet (A) / tuck into (M/B, the sides flare) the side pieces
  const cBack = collar('front');
  B.push(box('body', [A ? -4.5 : -4.75, -3, 3.25], [A ? 4.5 : 4.75, A ? 0.5 : 1, 5.5], c => (c.face === 'top' ? (c.p[2] < 5 ? lin(c) : bind(c)) : cBack(c)), { tag: 'collar back' }));

  // ---------- groin protector: one shield cut out of a single panel (alpha outline, the binding follows the cut) ----------
  // Wide straight top band; below it the sides run steadily inward to the seam (about two thirds of the top width), then
  // a little more steeply down to a broad rounded tip at y 16.5, so the shield is about as long as it is wide.
  //   Y0: bottom of the top band; YS: the seam; HS / HB: half-width at the seam / at the bottom edge; RC: corner rounding
  const Y0 = A ? 12 : 11.5, YS = A ? 14 : 14.25, YT = 16.5, HS = A ? 2.2 : 2, HB = A ? 1.2 : 0.9, RC = A ? 1 : mid ? 0.75 : 1;
  const hwAt = y => (y < Y0 ? 3 : y < YS ? 3 - (3 - HS) * (y - Y0) / (YS - Y0) : HS - (HS - HB) * (y - YS) / (YT - YS));
  const inShield = (ax, y) => {                                                            // distance inside the outline (<= 0: cut)
    const ds = hwAt(y) - ax, db = YT - y;
    return db < RC && ds < RC ? RC - Math.hypot(RC - db, RC - ds) : Math.min(ds, db);
  };
  // (a thin panel: seen edge-on below the band, where its sides are cut in, the front and back faces nearly meet)
  B.push(box('body', [-3, 11, -3.5], [3, YT, -3.25], c => {
    const ax = Math.abs(c.p[0]), y = Math.min(c.p[1], YT - K.px / 2);                     // (the bottom face uses the last row)
    if (c.face === 'top') return bind(c);
    if (isSide(c)) return y < Y0 ? bind(c) : null;                                        // below the band the sides are cut in
    const d = inShield(ax, y);
    if (d <= 0) return null;
    if (c.face === 'bottom') return bind(c);
    if (c.face === 'back') return lin(c);
    const col = mc(c);
    if (d < bw) return mix(col, BIND, 0.75);                                                // bound outline (no line at the seam)
    if (y < Y0) {                                                                           // stitched top band
      const b = mix(col, BIND, 0.45);
      return (!mid && hd && y >= 11.25 && md(c.p[0], 0.5) < 0.25) ? mul(b, 1.25) : b;
    }
    return col;
  }, { tag: 'groin shield' }));
  // (M/B) stitched seam across the shield's lower third, where the lower piece joins: a straight band standing a hair
  // proud of the shield, running into the bound edges but never past the outline
  if (hd) {
    const SY = YS - 0.25;
    B.push(box('body', [-2.5, SY, -3.625], [2.5, SY + 0.5, -3.375], c => {
      const yy = Math.min(Math.max(c.p[1], SY + K.px / 2), SY + 0.5 - K.px / 2);        // row centre (top / bottom faces use the edge row)
      if (Math.abs(c.p[0]) >= hwAt(yy) - bw * 0.6 || c.face === 'back') return null;
      const b = mix(mul(camoAt(c.p), G(c, mid ? 0.04 : 0.07)), BIND, 0.5);
      if (c.face !== 'front') return mul(b, c.face === 'top' ? 1.1 : 0.8);
      return !mid && c.p[1] < SY + 0.25 && (Math.floor(c.eu * 4) & 1) ? mul(b, 1.3) : b;
    }, { tag: 'groin seam' }));
  }

  // ---------- lower-back protector: MultiCam outside with bound edges; black padded inner face with green stitch rows ----------
  B.push(box('body', [-3.25, A ? 10 : 10.5, 2.75], [3.25, 14, 3.5], c => {
    if (c.face === 'front') return hd && md(c.p[1] - 11.5, 1) < bw ? mul(PADST, G(c, 0.06)) : mul(PAD, G(c, 0.08));
    if (c.face !== 'back') return bind(c);
    if (c.eu < bw || c.eu > c.fw - bw || c.ev > c.fh - bw) return bind(c);
    return mc(c);
  }, { tag: 'lower back protector' }));

  // ---------- deltoid protectors (follow the arms) ----------
  for (const s of [-1, 1]) {
    const part = s < 0 ? 'right_arm' : 'left_arm';
    const X = (a, b) => (s < 0 ? [a, b] : [-b, -a]);
    const out = s < 0 ? 'right' : 'left', inn = s < 0 ? 'left' : 'right';
    // wide pale loop band round the whole lower rim, just above the binding: outer face, front / back and the armpit ends
    // (one clean strip, framed only by the binding below; B: a faint stitch line along its top); (M/B) a narrow pale loop
    // strip runs down the outer shell into it
    const e0 = bw, e1 = bw + (A ? 1 : mid ? 1.5 : 1.75);
    const velcro = c => {
      const z = c.p[2], e = c.fh - c.ev, h = c.p[0] + z;
      if (hd && c.face === out && e >= e1 && z >= -0.5 && z < 0)
        return mul(VEL, 0.97 * (1 + (hash3(c.p[1] * 4, z * 4, 6) - 0.5) * (mid ? 0.05 : 0.12)));
      if (e < e0 || e >= e1) return null;
      const v = mul(VEL, (A ? 1.04 : 1) * (1 + (hash3(c.p[1] * 4, h * 4, 4) - 0.5) * (A ? 0.03 : mid ? 0.05 : 0.12)));
      return !mid && hd && e >= e1 - K.px ? mul(v, 0.86) : v;
    };
    const shell = (inward, bottomLining, patch) => c => {
      if (c.face === inward) return lin(c, DLIN);
      if (c.face === 'bottom') return bottomLining ? lin(c, DLIN) : bind(c);
      if (c.face !== 'top' && c.ev > c.fh - bw) return bind(c);                          // dark teal-green binding
      if (patch && c.face !== 'top') { const v = velcro(c); if (v) return v; }
      return edge(c, mc(c), { stitch: false });
    };
    // cap: its inner end meets the collar side, so the collar never passes through it and no gap shows from above
    const [c0, c1] = X(A ? -3.5 : -4, -0.5);
    B.push(box(part, [c0, -2.75, -3], [c1, -2, 3], shell('bottom', true, false), { tag: 'deltoid cap' }));
    const [o0, o1] = X(-4.25, -3.5);
    B.push(box(part, [o0, -2.25, -3], [o1, A ? 3.75 : 4.25, 3], shell(inn, false, true), { tag: 'deltoid outer', rot: [0, 0, s * -10], pivot: [s < 0 ? -3.9 : 3.9, -2.25, 0] }));
    for (const z of [[-3.25, -2.75], [2.75, 3.25]]) {
      const [f0, f1] = X(-3.75, 1);
      B.push(box(part, [f0, -2.25, z[0]], [f1, 2.5, z[1]], shell(z[0] < 0 ? 'back' : 'front', false, true), { tag: 'deltoid side' }));
    }
    // dark webbing strap round the upper arm, right under the shell's front / back edges (open top / bottom / armpit side)
    const [l0, l1] = X(-3.5, 0.5);
    B.push(box(part, [l0, 2.5, -2.5], [l1, A ? 3.5 : 3.25, 2.5], c => (c.face === 'top' || c.face === 'bottom' || c.face === inn ? null : mul(BIND, G(c, 0.06) * (c.face === out ? 0.9 : 1))), { tag: 'deltoid arm strap' }));
  }
  return { S: K.S, px: K.px, cell: K.cell, boxes: B };
};
