// ================= NPP KlASS Kora-Kulon (Digital Flora) — EFT reference (tarkov.dev 64be79e2bf8412471d0d9bcc) =================
// Same vest as kora_kulon (identical geometry), in Russian digital flora (EMR): a grey-olive ground with darker olive-green
// pixel clusters gathering in large soft zones, light grey-olive and khaki-yellow flecks and a few dark brown clusters; dark
// green lining. The shoulder straps, their shoulder pads and folded tabs are a clearly paler, yellow-khaki print than the
// panels (they read as lighter strips on the back too). Everything that is black in the reference stays black: the elastic
// belly band with its closure flap and loop-velcro strip, and the hook-velcro sections of the straps. The band's front
// carries the same closure as the black one: a seam under the wearer's right strap tab, a loop-velcro strip across the
// centre line and the flap (the band's end) on the wearer's left with its free edge at the centre line. The render shows
// only the front: the back is inferred as a plain panel with the straps sewn to its top edge.
ARMORS.kora_kulon_digital = function (mode) {
  const K = kit('M');                                           // always the 2x style
  const { px, G, isPanel, isSide } = K;
  const DIGITAL = true;
  const B = [];

  // ---------- palette (albedo; the band, its flap and every velcro are black in both colourways) ----------
  const NY = hex('#434547'), NYS = hex('#454545'), DUST = hex('#57514b');   // black nylon (panel / neutral straps) + dusty scuffs
  const LINING = DIGITAL ? hex('#1c2a21') : hex('#16191b');
  const BAND = hex('#303437'), FLAP = mul(BAND, 1.12), LOOPV = hex('#282b2e'), HOOKV = hex('#3b3b3c');

  // ---------- fabrics ----------
  // Russian digital flora (EMR), built like the other two EMR vests (6B23-1, 6B43): soft macro regions (about 2 px) decide
  // where dark clusters or light flecks gather and a micro noise breaks them into clusters of half-pixel cells. Every
  // cluster is then cut back to whole 2x2-cell blocks (at least one Minecraft pixel), so no lone half-pixel speck is left,
  // and a ground cell walled in by one cluster joins it. Palette sampled from this reference.
  const EMR = { base: hex('#7d8060'), dark: hex('#5d6648'), light: hex('#979a72'), bright: hex('#aca97d'), brown: hex('#554d3b') };
  const PLANE = { front: [0, 1], back: [0, 1], left: [2, 1], right: [2, 1], top: [0, 2], bottom: [0, 2] };
  const emrKind = q => {                                        // X khaki fleck (counts as light), L light, D dark, B ground
    const m = fbm(q[0] * 0.45 + 3, q[1] * 0.45 + 7, q[2] * 0.45 + 1, 2);
    const n = vnoise(q[0] * 1.2 + 11, q[1] * 1.2 + 5, q[2] * 1.2 + 9), n2 = vnoise(q[0] * 1.3 + 31, q[1] * 1.3 + 13, q[2] * 1.3 + 2);
    if (n2 > 0.8 || (m < 0.5 && n2 > 0.62)) return 'X';
    if (n2 > 0.72 || (m < 0.5 && n2 > 0.48)) return 'L';
    if (m > 0.48 && n > 0.42) return 'D';
    return 'B';
  };
  const flora = c => {
    const q = snap(c.p, 0.5), [a, b] = PLANE[c.face], memo = {};
    const kind = (du, dv) => {
      const id = du * 8 + dv;
      if (!(id in memo)) { const r = q.slice(); r[a] += du * px; r[b] += dv * px; memo[id] = emrKind(r); }
      return memo[id];
    };
    const fam = k => (k === 'X' ? 'L' : k);
    const at = (du, dv) => {                                     // a ground cell walled in by one cluster joins it
      const k = kind(du, dv);
      if (k !== 'B') return k;
      const nb = [kind(du - 1, dv), kind(du + 1, dv), kind(du, dv - 1), kind(du, dv + 1)].map(fam);
      return nb.every(v => v === nb[0]) ? nb[0] : 'B';
    };
    const g = {};
    for (let du = -1; du <= 1; du++) for (let dv = -1; dv <= 1; dv++) g[du * 8 + dv] = at(du, dv);
    const k = g[0];
    // keep a cluster cell only where a whole 2x2 block around it belongs to the same cluster kind
    const block = test => [[-1, -1], [0, -1], [-1, 0], [0, 0]].some(([u, v]) =>
      test(g[u * 8 + v]) && test(g[(u + 1) * 8 + v]) && test(g[u * 8 + v + 1]) && test(g[(u + 1) * 8 + v + 1]));
    if (k === 'B' || !block(v => fam(v) === fam(k))) return EMR.base;
    if (fam(k) === 'L') return k === 'X' && block(v => v === 'X') ? EMR.bright : EMR.light;
    return vnoise(q[0] * 0.7 + 47, q[1] * 0.7 + 19, q[2] * 0.7 + 23) > 0.7 ? EMR.brown : EMR.dark;   // a few whole clusters brown
  };
  const drift = c => fbm(c.p[0] * 0.35 + 5, c.p[1] * 0.3 + 2, c.p[2] * 0.35 + 9);
  const nylon = (base, c, k) => {
    const g = sm(clamp01((fbm(c.p[0] * 0.5 + 11, c.p[1] * 0.45 + 3, c.p[2] * 0.5 + 7) - 0.55) * 3));
    return mul(mix(base, DUST, 0.35 * g), k * (0.94 + 0.12 * drift(c)) * G(c, 0.05));
  };
  const fab = (c, k = 1) => (DIGITAL ? mul(flora(c), k * (0.95 + 0.1 * drift(c)) * G(c, 0.04)) : nylon(NY, c, k));
  // straps and their tabs: the digital straps are a much paler, yellow-khaki print than the panel; the black ones are a
  // neutral grey nylon, the folded tab a step lighter still
  const strapFab = (c, k = 1) => (DIGITAL ? mul(mix(flora(c), hex('#b8b58a'), 0.6), k * 1.04 * G(c, 0.04)) : nylon(NYS, c, k * 1.07));
  const tabFab = (c, k = 1) => strapFab(c, DIGITAL ? k : k * 1.075);
  const fuzz = (col, c, k = 1) => mul(col, k * (0.97 + 0.06 * fbm(c.p[0] * 1.3 + 2, c.p[1] * 1.3 + 8, c.p[2] * 1.3)) * G(c, 0.14));
  const lining = c => mul(LINING, G(c, 0.08));

  // ---------- panel outlines (cell centres; the boxes below are exactly these shapes) ----------
  // front: very shallow stepped U neckline between the straps, armhole corner cut at the outer column, rounded lower corners
  const frontTop = ax => (ax < 1 ? 1.5 : ax < 1.5 ? 1 : ax < 4 ? 0.5 : 2.5);
  const backTop = ax => (ax < 4 ? 0.5 : 2.5);
  const bottomAt = ax => (ax < 3 ? 12 : ax < 4 ? 11.5 : 11);
  const shapeOf = top => (x, y) => { const ax = Math.abs(x); return ax < 4.5 && y > top(ax) && y < bottomAt(ax); };
  const FRONT = shapeOf(frontTop), BACK = shapeOf(backTop);
  const NUDGE = { top: [0, 1, 0], bottom: [0, -1, 0], right: [1, 0, 0], left: [-1, 0, 0], front: [0, 0, 1], back: [0, 0, -1] };
  const cellIn = c => c.p.map((v, k) => v + NUDGE[c.face][k] * px / 2);   // centre of the cell just inside this face
  const BAND_Y = [5.5, 9.5];                                                // mid-belly, as in the original
  const PANEL_K = DIGITAL ? 1.1 : 1.08;                                     // panel faces as bright as the original's chest
  // one painter for every box of a panel: the panel reads as one piece (joins between its boxes are never drawn)
  const panel = (shape, outer) => c => {
    const [x, y] = cellIn(c);
    if (c.face === outer) {
      let col = fab(c, PANEL_K);
      // below the belly band the panel curves in, in the band's shadow (clearly darker than the chest in the original)
      if (y > BAND_Y[1] && y < BAND_Y[1] + px) col = mul(col, 0.66);
      else if (y > BAND_Y[1] + px && y < BAND_Y[1] + 2 * px) col = mul(col, 0.76);
      else if (y > BAND_Y[1]) col = mul(col, 0.84);
      if (!shape(x, y - px)) return mul(col, 1.14);                              // bound neckline / top edge catches the light
      if (!shape(x, y + px)) return mul(col, 0.8);                               // rolled lower edge
      if (!shape(x - px, y) || !shape(x + px, y)) return mul(col, 0.85);         // bound side / armhole edge
      return col;
    }
    if (isPanel(c)) return null;                                                 // inner face lies on the skin layer
    const d = NUDGE[c.face];
    if (shape(x - d[0] * px, y - d[1] * px)) return null;                        // a join with the next box of the panel
    if (c.face === 'top') return mul(fab(c), 1.1);                               // neckline / shoulder ledge
    if (c.face === 'bottom') return mul(fab(c), 0.6);
    return mul(fab(c), 0.78);                                                    // panel thickness at the sides
  };
  const X = (s, a, b) => (s < 0 ? [-b, -a] : [a, b]);
  const FZ = [-2.75, -2.25], BZ = [2.25, 2.75];
  const pf = panel(FRONT, 'front'), pb = panel(BACK, 'back');
  // front panel: main body, the centre of the neckline, its step and the shoulder beside it (under the strap), two rounded hem rows
  B.push(box('body', [-4.5, 2.5, FZ[0]], [4.5, 11, FZ[1]], pf, { tag: 'front panel' }));
  B.push(box('body', [-4, 1.5, FZ[0]], [4, 2.5, FZ[1]], pf, { tag: 'front panel neckline' }));
  for (const s of [-1, 1]) {
    const [a, b] = X(s, 1, 4), [c0, c1] = X(s, 1.5, 4);
    B.push(box('body', [a, 1, FZ[0]], [b, 1.5, FZ[1]], pf, { tag: 'front panel neckline step' }));
    B.push(box('body', [c0, 0.5, FZ[0]], [c1, 1, FZ[1]], pf, { tag: 'front panel shoulder' }));
  }
  B.push(box('body', [-4, 11, FZ[0]], [4, 11.5, FZ[1]], pf, { tag: 'front panel hem' }));
  B.push(box('body', [-3, 11.5, FZ[0]], [3, 12, FZ[1]], pf, { tag: 'front panel hem (rounded)' }));
  // back panel: taller, straight top under the head, same armhole corners and rounded hem
  B.push(box('body', [-4.5, 2.5, BZ[0]], [4.5, 11, BZ[1]], pb, { tag: 'back panel' }));
  B.push(box('body', [-4, 0.5, BZ[0]], [4, 2.5, BZ[1]], pb, { tag: 'back panel top' }));
  B.push(box('body', [-4, 11, BZ[0]], [4, 11.5, BZ[1]], pb, { tag: 'back panel hem' }));
  B.push(box('body', [-3, 11.5, BZ[0]], [3, 12, BZ[1]], pb, { tag: 'back panel hem (rounded)' }));

  // ---------- side wings of the back panel: close the sides under the armholes, two vertical strap strips ----------
  const Y_ARM = 3.5, Y_SIDE = 11;
  for (const s of [-1, 1]) {
    const out = s < 0 ? 'right' : 'left';
    B.push(box('body', [s < 0 ? -4.5 : 4.25, Y_ARM, -2.25], [s < 0 ? -4.25 : 4.5, Y_SIDE, 2.25], c => {
      if (c.face === 'top') return lining(c);                                    // armhole bottom
      if (c.face === 'bottom') return mul(fab(c), 0.6);
      if (c.face !== out) return null;                                           // on the skin / behind the panels
      const z = c.p[2], y = c.p[1];
      let col = fab(c, 0.96);
      if (y < Y_ARM + px) return mul(col, 1.1);                                  // bound top edge
      if (Math.abs(Math.abs(z) - 1) < 0.5) col = mul(col, 0.84);                 // side strap strips
      return col;
    }, { tag: 'side wing' }));
  }

  // ---------- belly band: wraps the waist, 0.25 proud of the panels ----------
  const bandCol = c => mul(BAND, (0.96 + 0.08 * fbm(c.p[0] * 0.4 + 3, c.p[1] * 0.6 + 1, c.p[2] * 0.4 + 5)) * G(c, 0.05));
  B.push(box('body', [-4.75, BAND_Y[0], -3], [4.75, BAND_Y[1], 3], c => {
    const x = c.p[0], y = c.p[1];
    const col = bandCol(c);
    if (c.face === 'top') return mul(col, 1.18);
    if (c.face === 'bottom') return mul(col, 0.66);
    if (y < BAND_Y[0] + px) return mul(col, 1.16);                               // bound top edge
    if (y > BAND_Y[1] - px) return mul(col, 0.8);                                // lower edge
    if (c.face === 'front') {
      if (x > -0.75 && x < 0.25) return fuzz(LOOPV, c);                          // loop velcro strip across the centre line
      if (x > -2.25 && x < -1.75) return mul(col, 0.86);                         // seam under the wearer's right strap tab
    }
    if (c.face === 'back' && Math.abs(Math.abs(x) - 3.25) < 0.25) return mul(col, 0.82);   // sewn to the back panel
    return col;
  }, { tag: 'belly band' }));

  // ---------- closure flap: the band's end on the wearer's left, lying on the band ----------
  // 6 x 7 cells (taller than wide), its free edge right at the centre line next to the velcro, about 1.5 px of band
  // showing past its sewn edge. Standing 1 cell proud so its sides show; a step lighter than the band, with a flat
  // interior and a light bound edge all round (the X stitch is too fine for the 2x grid); the free-edge corners are
  // rounded off, so it is built as a main block plus a shorter column on the free edge.
  const FX = [0.25, 3.25], FY = [5.75, 9.25], FZ0 = -3.5;
  const inFlap = (x, y) => x > FX[0] && x < FX[1] && y > FY[0] && y < FY[1] && !(x < FX[0] + px && (y < FY[0] + px || y > FY[1] - px));
  const flap = c => {
    if (c.face === 'back') return null;                                          // lies on the band
    const [x, y] = cellIn(c), d = NUDGE[c.face];
    if (c.face !== 'front' && inFlap(x - d[0] * px, y - d[1] * px)) return null;  // the join between its two blocks
    const col = mul(FLAP, (0.97 + 0.06 * fbm(c.p[0] * 0.5 + 9, c.p[1] * 0.5, 1)) * G(c, 0.05));
    if (c.face === 'top') return mul(col, 1.2);
    if (c.face === 'bottom') return mul(col, 0.6);
    if (c.face === 'right') return mul(col, 0.88);                                // free edge (toward the centre)
    if (c.face === 'left') return mul(col, 0.76);                                 // sewn edge
    if (!inFlap(x - px, y) || !inFlap(x + px, y) || !inFlap(x, y - px) || !inFlap(x, y + px)) return mul(col, 1.15);   // bound edge
    return col;
  };
  B.push(box('body', [FX[0] + px, FY[0], FZ0], [FX[1], FY[1], -3], flap, { tag: 'closure flap' }));
  B.push(box('body', [FX[0], FY[0] + px, FZ0], [FX[0] + px, FY[1] - px, -3], flap, { tag: 'closure flap free edge' }));

  // ---------- shoulder straps ----------
  // Front: turned about the outer top corner so it runs down and in onto the chest; plain padded strap (mostly inside the
  // head), black hook velcro starting level with the panel's top, then the lighter folded tab standing a little proud,
  // ending high on the chest as in the original. Over the shoulder (inside the head) and down the back.
  const ANG = 12, TOP = -0.5, VEL0 = 0.5, TAB0 = 1.5, TIP = 2.5;
  for (const s of [-1, 1]) {
    const [a, b] = X(s, 2, 4), rot = { rot: [0, 0, s * ANG], pivot: [s * 4, TOP, -3] };
    const inU = c => c.eu < px || c.eu > c.fw - px;
    B.push(box('body', [a, TOP, -3.25], [b, TAB0, -2.75], c => {
      if (c.face === 'back' || c.face === 'bottom') return null;                 // on the panel / on the tab
      const vel = c.p[1] > VEL0;
      if (c.face === 'top') return strapFab(c, 1.1);
      if (c.face !== 'front') return vel ? fuzz(HOOKV, c, 0.8) : strapFab(c, 0.75);
      if (vel) return fuzz(HOOKV, c, c.p[1] < VEL0 + px ? 1.1 : 1);             // hook velcro, its top edge lit
      return strapFab(c, inU(c) ? 0.9 : 1);                                      // bound strap edges
    }, { tag: 'shoulder strap', ...rot }));
    B.push(box('body', [a, TAB0, -3.5], [b, TIP, -2.75], c => {
      if (c.face === 'back') return null;
      if (c.face === 'top') return c.p[2] < -3.25 ? tabFab(c, 1.18) : null;     // the proud fold; the rest is under the strap
      if (c.face === 'bottom') return tabFab(c, 0.6);
      if (c.face !== 'front') return tabFab(c, 0.75);
      const col = tabFab(c, 1.06);
      if (c.ev < px) return mul(col, 1.1);
      if (c.ev > c.fh - px) return mul(col, 0.8);
      return inU(c) ? mul(col, 0.88) : col;
    }, { tag: 'strap tab', ...rot }));
    // over the shoulder, inside the head (shows only when the head turns); front / back ends are covered by the straps.
    // Its outer side stops short of the head's side plane (x 4), which it would otherwise z-fight.
    const [t0, t1] = X(s, 2, 3.75);
    B.push(box('body', [t0, TOP, -2.75], [t1, 0.5, 2.75], c => {
      if (isPanel(c) || c.face === 'bottom') return null;
      return strapFab(c, c.face === 'top' ? 1.1 : 0.8);
    }, { tag: 'shoulder strap top' }));
    // thick padded hump on top of the shoulder, just outside the hat layer (the tallest part of the original's silhouette);
    // it runs from the front strap's back to the back strap's front. Its inner side lies on the head's side plane (left
    // out) and its underside is dark lining.
    const [h0, h1] = X(s, 4, 5.25), inner = s < 0 ? 'left' : 'right';
    B.push(box('body', [h0, -1.25, -2.75], [h1, -0.25, 2.75], c => {
      if (c.face === inner) return null;
      if (c.face === 'bottom') return lining(c);
      if (c.face === 'top') return strapFab(c, 1.12);
      if (isPanel(c)) return strapFab(c, 0.86);                                 // pad ends meeting the front / back straps
      return strapFab(c, c.ev < px ? 1.02 : 0.88);                              // rounded outer side
    }, { tag: 'shoulder pad hump' }));
    // back: from the head's lower edge down onto the back panel's top, a darker sewn end and soft bound edges, so the
    // strap reads as a lighter strip outlined on the back panel
    B.push(box('body', [a, 0, 2.75], [b, 2.5, 3.25], c => {
      if (c.face === 'front') return null;
      if (c.face === 'top') return strapFab(c, 1.1);
      if (c.face === 'bottom') return strapFab(c, 0.6);
      if (c.face !== 'back') return strapFab(c, 0.75);
      if (c.p[1] > 2.5 - px) return strapFab(c, 0.84);
      return strapFab(c, inU(c) ? 0.9 : 1.04);
    }, { tag: 'shoulder strap back' }));
  }

  return { S: K.S, px: K.px, cell: K.cell, boxes: B };
};
