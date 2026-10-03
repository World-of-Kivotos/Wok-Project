// ================= NFM THOR Concealable Reinforced Vest — EFT reference (tarkov.dev 609e8540d5c319764c2bc2e9) =================
// Black Cordura concealable vest, dusty, lighter where the light catches it (upper chest, strap fronts). Reference render:
// wide shoulder straps rising above a shallow U neckline, each ending in a lighter velcro closure tab with a sewn edge that
// overlaps the front panel's top; black spacer-mesh lining inside the neckline and the deep armholes; a sewn-on name-tape
// loop patch (a shade darker, outlined) centred on the upper chest; otherwise a plain front panel; a wide, very dark
// elastic belly band wraps the lower third of the torso from the band top to the hem (a light ridge along its top edge, a
// faint seam along its middle); two elastic adjustment straps per side run round the band from the back and end on the
// front in outlined hook tabs - on the wearer's right the tabs reach further in than on the wearer's left. No MOLLE,
// pouches or arm armour. The back is not shown in the render: plain panel with the straps sewn on.
ARMORS.thor_concealable = function (mode) {
  const K = kit('M');                       // always the 2x style
  const { G, isSide, px } = K;
  const B = [];
  // albedo (the viewer / MC light front faces at ~74 %). Rendered targets from the reference: chest ~#2b2b2a (upper chest
  // up to ~#3b3936), strap fronts ~#454442, belly band ~#171817, hook tabs ~#232625 with ~1.3x lighter sewn edges,
  // mesh lining ~#070708
  const NY = hex('#3d3c39'), DUST = hex('#6c6a65'), STRAPF = hex('#56544f'), BAND = hex('#222322'), TAB = hex('#313534'),
    ELASTIC = hex('#333736'), MESH = hex('#161718'), BIND = hex('#2b2c2b');
  const NECK = 1.5, ARM = 6.5, BT = 8, HEM = 12.5; // neckline, armhole bottom, belly band top, band bottom (hem)

  // Cordura: faint low-frequency tone, soft grey dust blotches (1-3 px), per-cell grain
  const fab = (c, base = NY, k = 1) => {
    const p = snap(c.p, px);
    const tone = 0.95 + 0.1 * fbm(p[0] * 0.35 + 7, p[1] * 0.3 + 3, p[2] * 0.35 + 1);
    const dust = sm(clamp01((fbm(p[0] * 0.5 + 21, p[1] * 0.45 + 4, p[2] * 0.5 + 13) - 0.56) * 3.2));
    return mul(mix(base, DUST, 0.26 * dust), k * tone * G(c, 0.07));
  };
  const light = y => 1.1 - 0.18 * clamp01((y - NECK) / 6);            // the upper chest catches the light, as in the render
  const mesh = c => mul(MESH, G(c, 0.12));
  // loop velcro of the belly band: very dark, softly matted
  const loop = (c, k = 1) => {
    const p = snap(c.p, px);
    return mul(BAND, k * (0.93 + 0.14 * fbm(p[0] * 0.7 + 3, p[1] * 0.7 + 8, p[2] * 0.7 + 5, 2)) * G(c, 0.1));
  };

  // ---------------- shell: front / back panels, side walls under the deep armholes ----------------
  const front = c => {
    const x = c.p[0], y = c.p[1], ax = Math.abs(x);
    if (c.face === 'back') return null;                                        // lies on the skin
    if (c.face === 'top') return mul(fab(c), 1.05);                           // neckline edge (under the strap risers they are hidden)
    if (c.face === 'bottom') return mul(BIND, 0.8);                            // inside the belly band
    if (isSide(c)) return y < ARM ? mesh(c) : fab(c, NY, 0.9);               // armhole edge: the mesh lining shows
    const col = fab(c, NY, light(y));
    if (ax < 2.25 && y < NECK + px) return mul(col, 1.3);                      // rolled neckline binding catches the light
    if (ax > 4.5 - px && y < ARM) return mul(col, 0.88);                       // bound armhole edge
    return col;
  };
  B.push(box('body', [-4.5, NECK, -2.75], [4.5, 12.25, -2.25], front, { tag: 'front panel' }));
  const back = c => {
    const y = c.p[1], ax = Math.abs(c.p[0]);
    if (c.face === 'front') return null;
    if (c.face === 'top') return mul(fab(c), 1.05);
    if (c.face === 'bottom') return mul(BIND, 0.8);
    if (isSide(c)) return y < ARM ? mesh(c) : fab(c, NY, 0.9);
    const col = fab(c);
    if (y < 0.25) return mul(col, 1.1);                                        // bound top edge behind the neck
    if (ax > 4.5 - px && y < ARM) return mul(col, 0.88);
    return col;
  };
  B.push(box('body', [-4.5, -0.25, 2.25], [4.5, 12.25, 2.75], back, { tag: 'back panel' }));
  // (thin slabs outside the jacket layer, between the panels' inner faces)
  const walls = c => {
    if (c.face === 'top') return mesh(c);                                      // bottom of the armhole
    if (!isSide(c) || Math.abs(c.p[0]) < 4.4) return null;                     // front / back lie on the panels' inner faces
    return fab(c, NY, 0.92);
  };
  for (const s of [-1, 1]) B.push(box('body', s < 0 ? [-4.5, ARM, -2.25] : [4.25, ARM, -2.25], s < 0 ? [-4.25, 12.25, 2.25] : [4.5, 12.25, 2.25], walls, { tag: 'side wall' }));

  // ---------------- name-tape loop patch on the upper chest: a shade darker, its edges in shadow ----------------
  const patch = c => {
    if (c.face === 'back') return null;
    const col = mul(fab(c), 0.9 * light(c.p[1]));
    if (c.face !== 'front') return mul(col, c.face === 'top' ? 1.05 : 0.72);
    return c.ev > c.fh - px ? mul(col, 0.88) : col;
  };
  B.push(box('body', [-1.75, NECK + 1, -3], [1.75, NECK + 2, -2.75], patch, { tag: 'name tape patch' }));

  // ---------------- belly band: wraps the lower torso from the band top to the hem; the two elastic adjustment straps
  // per side are painted on its sides and back corners (on the band's own texel rows, so they meet the front tabs) ----------------
  const ROWS = [[8.5, 10], [10.5, 12]];
  const strapRow = y => ROWS.find(([a, b]) => y >= a && y < b);
  const elastic = (c, r, k = 1) => {                                           // light top edge, darker lower edge
    const col = mul(ELASTIC, k * G(c, 0.06));
    return c.p[1] < r[0] + px ? mul(col, 1.14) : c.p[1] > r[1] - px ? mul(col, 0.84) : col;
  };
  const band = c => {
    const y = c.p[1], ax = Math.abs(c.p[0]), az = Math.abs(c.p[2]);
    if ((c.face === 'top' || c.face === 'bottom') && ax < 4.5 && az < 2.75) return null;   // inside the vest
    if (c.face === 'top') return mul(mix(BAND, NY, 0.5), 1.1);                 // lit top ridge
    if (c.face === 'bottom') return mul(BIND, 0.7);
    const r = strapRow(y);
    if (r && (isSide(c) || (c.face === 'back' && ax > 3))) {                  // straps round the sides to the back corners
      if (c.face === 'back' && ax < 3.75) return elastic(c, r, 0.8);           // sewn end on the back
      return elastic(c, r);
    }
    let col = loop(c, c.face === 'front' ? 1 : 1.08);
    if (y < BT + px) return mul(mix(col, NY, 0.45), 1.25);                     // rolled top edge
    if (y >= 10 && y < 10 + px) col = mul(col, 1.16);                          // seam along the middle
    return col;
  };
  B.push(box('body', [-4.75, BT, -3], [4.75, HEM, 3], band, { tag: 'belly band' }));

  // ---------------- outlined hook tabs at the strap ends on the front ----------------
  const hookTab = s => c => {
    if (c.face === 'back') return null;                                        // on the band
    const col = mul(TAB, G(c, 0.08));
    if (c.face === 'top') return mul(col, 1.15);
    if (c.face === 'bottom') return mul(col, 0.6);
    const b = c.box, outer = s < 0 ? b.x : b.x + b.w;
    if (isSide(c)) return Math.abs(c.p[0] - outer) < 0.01 ? elastic(c, [b.y, b.y + b.h], 0.9) : mul(col, 0.8);   // outer end runs into the strap
    // lit sewn top edge and free end; the outer end runs on round the side (a light bottom row as well turned the
    // three-texel tab into stripes)
    const inEnd = s < 0 ? c.p[0] > b.x + b.w - px : c.p[0] < b.x + px;
    if (c.ev < px) return mul(col, 1.3);                                       // top edge catches the light
    if (inEnd) return mul(col, 1.18);                                          // sewn free end
    return c.ev > c.fh - px ? mul(col, 0.95) : col;
  };
  for (const s of [-1, 1]) {
    const X = (a, b) => (s < 0 ? [-b, -a] : [a, b]);
    const [t0, t1] = s < 0 ? X(2.25, 4.75) : X(2.75, 4.75);                     // wearer's right: the tabs reach further in
    for (const [y0, y1] of ROWS) B.push(box('body', [t0, y0, -3.25], [t1, y1, -3], hookTab(s), { tag: 'side strap hook tab' }));
  }

  // ---------------- shoulder straps: riser under the chin, lighter closure tab overlapping the panel top ----------------
  // (outer edge at 4.25: beside the head but inside the hat layer, never in the hat's side plane)
  for (const s of [-1, 1]) {
    const X = (a, b) => (s < 0 ? [-b, -a] : [a, b]);
    const [a, b] = X(2.25, 4.25);
    const riser = c => (c.face === 'back' || c.face === 'bottom' ? null : mul(fab(c, STRAPF), isSide(c) ? 0.8 : 1));
    B.push(box('body', [a, -0.25, -2.75], [b, NECK, -2.25], riser, { tag: 'shoulder strap riser' }));
    const tab = c => {
      if (c.face === 'back') return null;                                      // on the riser / panel
      const col = fab(c, STRAPF);
      if (c.face === 'bottom') return mul(col, 0.55);
      if (c.face !== 'front') return mul(col, c.face === 'top' ? 1 : 0.8);
      if (c.ev > c.fh - px) return mul(col, 0.8);                              // sewn lower edge of the tab
      if (c.eu < px || c.eu > c.fw - px) return mul(col, 0.9);                 // sewn side edges
      return col;
    };
    B.push(box('body', [a, -0.25, -3], [b, NECK + 0.25, -2.75], tab, { tag: 'shoulder closure tab' }));   // 4 texel rows
    // over the shoulder: under the head, only its outer edge shows beside it (all of it when the head turns); runs from
    // the front tab into the back strap
    B.push(box('body', [a, -0.75, -3], [b, -0.25, 3], c => mul(fab(c, STRAPF, 0.9), c.face === 'top' ? 1.05 : 0.8), { tag: 'shoulder strap top' }));
    const backStrap = c => {
      if (c.face === 'front') return null;                                     // on the back panel
      const col = fab(c, STRAPF, 0.85);
      if (c.face !== 'back') return mul(col, c.face === 'top' ? 1 : 0.78);
      if (c.ev > c.fh - px) return mul(col, 0.8);                              // sewn end
      return col;
    };
    B.push(box('body', [a, -0.25, 2.75], [b, 3.75, 3], backStrap, { tag: 'shoulder strap back' }));
  }
  return { S: K.S, px: K.px, cell: K.cell, boxes: B };
};
