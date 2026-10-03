// ================= IOTV Gen4 (Assault Kit, MultiCam) — EFT reference (tarkov.dev 5b44cf1486f77431723e3d05) =================
// Same carrier as the Full Protection kit (armors/iotv.js), same MultiCam and dark teal-green binding. The reference render
// shows the assault configuration: tall padded stand collar (darker, greener MultiCam, dark lining, teal rim), MultiCam
// shoulder-strap flaps with silver D-rings, pale grey-khaki loop ID patch (small grey tab under it), two MOLLE rows on the
// upper chest, proud front plate pocket with more rows, deep dark armholes down to about mid-torso, MOLLE cummerbund on the
// lower side only with the grey "XII" tab (stitched box + two bars) on the wearer's right coming round the front edge and a
// grey front tab beside the pocket on the wearer's left, and the big flared deltoid shells (deeper camo, pale loop band
// round the lower rim, dark webbing strap round the upper arm).
// NO groin protector and NO lower-back protector: only the short, empty attachment tabs hang a little below the hem, the
// grey-khaki groin-strap tab under the plate pocket and a darker one at the back centre.
ARMORS.iotv_gen4_assault = function (mode) {
  const K = kit('M'), { edge, G, isPanel, isSide, px } = K;
  const bw = px;                                               // one art cell: binding / frame width
  const LINING = hex('#2f3a2c'), DLIN = hex('#38352c'), BIND = hex('#3a5a4e'), WEB = hex('#8a8262'),
    LOOP = hex('#c4bca0'), VEL = hex('#bdb59a'), METAL = hex('#a3a39a'), LABEL = hex('#c9c3ad'), INK = hex('#2a2a26'),
    TAB = hex('#6e6857'), GAP = hex('#3f3d2d'), ARMHOLE = hex('#1f231d'), OLIVE = hex('#6f7448');
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

  // ---------- carrier: deep dark armholes, MOLLE cummerbund sides, "XII" tab on the wearer's right, bound hem ----------
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
  // the armhole (above the cummerbund, down to about mid-torso): the carrier's dark inside, framed by the teal binding
  // (seen when the arm swings)
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
  // D-ring: flat bar on top (through the flap's end), round bottom; cut out in the middle. Front face only: its back face
  // would lie in the plane of the flap / upper-MOLLE fronts and z-fight through the ring's holes
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
    // turned 10deg so it runs down toward the ID panel, as in the reference. The pivot is its bottom-outer corner, so the
    // outer edge leans in and never crosses x = 4, where the deltoid's front plate lies in the same plane (z-fighting)
    const [f0, f1] = X(1.5, 4);
    B.push(box('body', [f0, 1, -3.25], [f1, 3, -2.75], flap, { tag: 'shoulder strap flap', rot: [0, 0, -s * 10], pivot: [s * 4, 3, -3] }));
    const ds = 1.5, cx = s * 2.75;
    B.push(box('body', [cx - ds / 2, 2.75, -3.5], [cx + ds / 2, 2.75 + ds, -3.25], dring, { tag: 'D-ring' }));
  }

  // ---------- the empty strap tabs: grey-khaki webbing hanging a little below the hem, front (groin) and back centre ----------
  // (no groin / lower-back protector on this kit, so no buckle; the folded end is a shade darker, the back one darker overall)
  const strapTab = (k, inward) => c => {
    const col = mul(TAB, k * G(c, 0.05));
    if (c.face === inward) return mul(col, 0.55);
    if (c.face === 'top') return mul(col, 1.1);
    if (c.face === 'bottom') return mul(col, 0.6);
    if (!isPanel(c)) return mul(col, 0.78);
    if (c.ev > c.fh - bw) return mul(col, 0.72);                                 // folded end
    return c.ev < bw ? mul(col, 1.08) : col;
  };
  B.push(box('body', [-0.5, 10, -3.75], [0.5, 11.75, -3.5], strapTab(1, 'back'), { tag: 'groin strap tab' }));
  B.push(box('body', [-0.5, 9.5, 3.25], [0.5, 11.75, 3.5], strapTab(0.85, 'front'), { tag: 'back strap tab' }));

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
  // back: rises highest, behind the hat layer; its ends tuck into the flared side pieces
  const cBack = collar('front');
  B.push(box('body', [-4.75, -3, 3.25], [4.75, 1, 5.5], c => (c.face === 'top' ? (c.p[2] < 5 ? lin(c) : bind(c)) : cBack(c)), { tag: 'collar back' }));

  // ---------- deltoid protectors (follow the arms) ----------
  for (const s of [-1, 1]) {
    const part = s < 0 ? 'right_arm' : 'left_arm';
    const X = (a, b) => (s < 0 ? [a, b] : [-b, -a]);
    const out = s < 0 ? 'right' : 'left', inn = s < 0 ? 'left' : 'right';
    // wide pale loop band round the whole lower rim, just above the binding (outer face, front / back, armpit ends);
    // a narrow pale loop strip runs down the outer shell into it
    const e0 = bw, e1 = bw + 1.5;
    const velcro = c => {
      const z = c.p[2], e = c.fh - c.ev, h = c.p[0] + z;
      if (c.face === out && e >= e1 && z >= -0.5 && z < 0) return mul(VEL, 0.97 * (1 + (hash3(c.p[1] * 4, z * 4, 6) - 0.5) * 0.05));
      if (e < e0 || e >= e1) return null;
      return mul(VEL, 1 + (hash3(c.p[1] * 4, h * 4, 4) - 0.5) * 0.05);
    };
    // (the shells' camo a shade deeper and greener than the carrier's, as on the render, with a one-cell shadow line right
    // above the band, so the pale loop cuff stands out from the pale MultiCam ground)
    const shell = (inward, bottomLining, patch) => c => {
      if (c.face === inward) return lin(c, DLIN);
      if (c.face === 'bottom') return bottomLining ? lin(c, DLIN) : bind(c);
      if (c.face !== 'top' && c.ev > c.fh - bw) return bind(c);                          // dark teal-green binding
      const col = mul(mix(mc(c), OLIVE, 0.18), 0.92);
      if (patch && c.face !== 'top') {
        const v = velcro(c);
        if (v) return v;
        const e = c.fh - c.ev;
        if (e >= e1 && e < e1 + bw) return mul(col, 0.78);
      }
      return edge(c, col, { stitch: false });
    };
    // cap: its inner end meets the collar side, so the collar never passes through it and no gap shows from above
    const [c0, c1] = X(-4, -0.5);
    B.push(box(part, [c0, -2.75, -3], [c1, -2, 3], shell('bottom', true, false), { tag: 'deltoid cap' }));
    const [o0, o1] = X(-4.25, -3.5);
    B.push(box(part, [o0, -2.25, -3], [o1, 4.25, 3], shell(inn, false, true), { tag: 'deltoid outer', rot: [0, 0, s * -10], pivot: [s < 0 ? -3.9 : 3.9, -2.25, 0] }));
    for (const z of [[-3.25, -2.75], [2.75, 3.25]]) {
      const [f0, f1] = X(-3.75, 1);
      B.push(box(part, [f0, -2.25, z[0]], [f1, 2.5, z[1]], shell(z[0] < 0 ? 'back' : 'front', false, true), { tag: 'deltoid side' }));
    }
    // dark webbing strap round the upper arm, right under the shell's front / back edges (open top / bottom / armpit side)
    const [l0, l1] = X(-3.5, 0.5);
    B.push(box(part, [l0, 2.5, -2.5], [l1, 3.25, 2.5], c => (c.face === 'top' || c.face === 'bottom' || c.face === inn ? null : mul(BIND, G(c, 0.06) * (c.face === out ? 0.9 : 1))), { tag: 'deltoid arm strap' }));
  }
  return { S: K.S, px: K.px, cell: K.cell, boxes: B };
};
