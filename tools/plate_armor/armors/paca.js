// ================= PACA Soft Armor — EFT reference (tarkov.dev 5648a7494bdc2d9d488b4583) =================
// Black / charcoal concealable soft vest. Smooth nylon shell, fairly even, with a soft sheen just under the chest
// panel; teal honeycomb mesh lining on the inside (shows at the armhole edges). The front panel starts well below the
// shoulders (~1.5 px): its top edge is a shallow U between the two straps, so the wide worn dark-grey shoulder straps
// rise above it like suspenders before going over the shoulders to the taller back panel; between them a narrow strip
// of smooth shell with a light rolled rim sits above the chest loop panel (not a black binding). The straps end on the upper
// chest as separate, slightly lighter smooth tabs with rounded ends, lying proud on a fuzzy loop panel split into two
// bands by a stitched seam. A wide loop "belly band" in three equal stitched bands wraps the lower torso (y 7..11); on
// the wearer's right it becomes a smooth closure strap that covers the whole side and ends in a lighter flat tab with
// rounded inner corners on the front of the band. The band's seams are clearly lighter strips. Below it a short smooth
// strip, a touch lighter than the band, and a lighter rolled hem. No webbing, no
// pouches - kept thin, just outside the jacket layer. The back is not visible in the render: plain strap with
// pressed-through seams, bound edges, and the shoulder straps sewn onto the back panel.
// Layering: GeckoLib per-face UV gives every face its own texel rect, so 0.25 px layers are used in every style
// (loop panel / strap / tab / band steps of 0.25 px) without faces bleeding into their neighbours.
ARMORS.paca = function (mode) {
  const K = kit(mode), { hd, mid, G, isSide } = K;
  const f = K.f, fine = hd && !mid;
  const B = [];

  // ---- palette (albedo; MC lighting draws front faces at ~74 %). Rendered targets from the reference:
  //      shell ~#414545, chest loop ~0.75x the shell, straps ~#555554, tabs ~1.25x the strap, band loop ~#212426,
  //      band seams ~1.25x the band loop, closure tab end ~1.5x the band loop.
  const NY = hex('#52575a'), NYH = hex('#6a7072');                          // nylon shell: base / soft sheen
  const BIND = hex('#25282b');                                                // edge binding
  const LP0 = hex('#26292b'), LP1 = hex('#474b4e'), LINT = hex('#6a6e72');   // velcro loop (mean ~#333739) + lint
  const SEAM = hex('#3a3e41');                                                // stitched strip between loop bands
  const ST0 = hex('#5b5f61'), ST1 = hex('#777b7d'), ST2 = hex('#90949a');   // worn dark-grey straps
  const TAB = hex('#979b9d');                                                 // smooth tab ends (~1.25x the strap)
  const FLAP = hex('#3f4447'), FLAP_TAB = hex('#4a5053');                    // closure strap along the side / its tab end
  const MESH = hex('#467478'), HOLE = hex('#1e3033');                        // teal honeycomb lining
  const CHEST = 1.35, BAND = 0.9;                                             // loop brightness: chest panel (~0.72x the shell) / belly band

  const tex = (c, salt) => hash3(Math.floor(c.p[0] * f + 1000), Math.floor(c.p[1] * f + 1000), Math.floor(c.p[2] * f + 1000) + salt * 131);
  const bind = (c, k = 1) => mul(BIND, k * G(c, 0.06));
  // bound hem: a rolled rim, lighter than the strip above it (A: its one row is strip and rim together)
  const hemRim = (c, k = 1) => mul(NY, k * (hd ? 0.92 : 0.8) * G(c, 0.05));
  // shell: even, a soft sheen band just under the chest panel, darker in the band's shadow, faint grain
  const nylon = (c, k = 1) => {
    const y = c.p[1];
    let col = mix(NY, NYH, 0.6 * Math.exp(-(((y - (mid ? 5.8 : 5.4)) / 1.4) ** 2)));
    if (y > 11) col = mul(col, 0.72);                                         // strip under the band: dark, but a touch lighter than the band
    col = mul(col, 0.95 + 0.1 * fbm(c.p[0] * 0.45 + 11, c.p[1] * 0.3 + 5, c.p[2] * 0.45 + 2, 2));
    if (hd && tex(c, 5) > (mid ? 0.992 : 0.998)) col = mix(col, hex('#8d9295'), mid ? 0.18 : 0.15);   // rare light scuffs
    return mul(col, k * G(c, mid ? 0.06 : 0.05));
  };
  const loop = (c, k = 1, calm = false) => {                                  // calm: A belly band, so its stripes read
    const amp = calm ? 0.12 : hd ? (mid ? 0.3 : 0.28) : 0.25;
    let col = mix(LP0, LP1, 0.4 + (tex(c, 3) - 0.5) * amp);
    col = mul(col, (calm ? 0.96 : 0.92) + (calm ? 0.08 : 0.16) * fbm(c.p[0] * 0.6 + 3, c.p[1] * 0.6 + 8, c.p[2] * 0.6, 2));   // matted patches
    if (hd && tex(c, 9) > (mid ? 0.99 : 0.996)) col = mix(col, LINT, mid ? 0.3 : 0.25);               // lint / dust
    return mul(col, k);
  };
  // seam strip: smooth, a little lighter than the loop; B adds a faint continuous-looking stitch (long dashes)
  const seam = (c, k = 1, along = 0) => mul(SEAM, k * (fine && Math.floor(along * 4 + 1000) % 3 === 0 ? 0.94 : 1) * G(c, 0.04));
  const strapCol = (c, k = 1) => {
    const n = fbm(c.p[0] * 0.8 + 3, c.p[1] * 0.8 + 9, c.p[2] * 0.8 + 1, 2);
    // M: worn but even (a narrow blend, same mean) so the chunky straps are not a grey checker
    let col = mix(ST0, ST1, mid ? clamp01(0.58 + (n - 0.5) * 0.8) : clamp01((n - 0.25) * 2));
    if (hd && tex(c, 13) > (mid ? 0.975 : 0.98)) col = mix(col, ST2, 0.3);                            // scuffs
    return mul(col, k * G(c, 0.05));
  };
  const flapSide = (c, k = 1) => mul(FLAP, k * G(c, 0.05));
  const flapTab = (c, k = 1) => mul(FLAP_TAB, k * G(c, 0.03));
  const mesh = (c, k = 1) => {
    if (!hd) return mul(MESH, k * (0.9 + 0.2 * tex(c, 21)));
    const i = Math.floor(c.eu * f + 1e-6), j = Math.floor(c.ev * f + 1e-6);
    const hole = j % 2 === 0 && (i + (j % 4 === 0 ? 0 : 1)) % 2 === 0;      // offset dot rows = honeycomb at this scale
    return hole ? mul(HOLE, k) : mul(MESH, k * (0.92 + 0.16 * tex(c, 21)));
  };

  // ---- layout per style (y down, px). Front neckline ~1.5 below the shoulders, back panel taller.
  // belly: the loop band (three bands); flap: y range of the closure flap, as tall as the band in every style - its
  // top / bottom faces are level with the band's (beside it, never overlapping) and painted with the same constant colour.
  // loop[2]: top of the chest loop panel; M / B leave a strip of smooth shell above it at the neckline, as in the original.
  const L = fine
    ? { fTop: 1, bTop: 0.5, split: 5.5, pIn: -2.0, loop: [-4.25, 4.25, 2, 4.5], seamY: 3, seamH: 0.25, belly: [7, 11], tabY: 2, strapEnd: 4.25, R: 0.85, flap: [7, 11], hem: 0.25, backEnd: 3.5 }
    : mid
    ? { fTop: 1, bTop: 0.5, split: 5.5, pIn: -2.0, loop: [-4.25, 4.25, 2, 5], seamY: 3, seamH: 0.5, belly: [7, 11], tabY: 2, strapEnd: 4.5, R: 1, flap: [7, 11], hem: 0.5, backEnd: 3.5 }
    : { fTop: 1, bTop: 0, split: 6, pIn: -2.0, loop: [-4.5, 4.5, 2, 4], seamY: null, seamH: 0, belly: [7, 11], tabY: 2, strapEnd: 4, R: 0, flap: [7, 11], hem: 1, backEnd: 4 };
  const BW = fine ? 0.25 : 0.5;                                               // binding width
  // front top edge: shallow U between the straps (hidden under them at |x| 2..4), dropping into the armhole outside them
  const frontTop = x => {
    const a = Math.abs(x);
    if (!hd) return a < 2.1 || a > 3.9 ? 2 : 1;                             // A: 1-px columns centred on whole x; the strap covers the step
    if (a > 4) return 1.5;
    return a < 2 ? 1 + 0.5 * (1 - (x / 2) ** 2) : 1;
  };
  // every layer in front of the body is 0.25 px: panel -2.5, loop panel / strap -2.75, tab -3
  const Z = { loop: [-2.75, -2.5], strap: [-2.75, -2.5], tab: [-3, -2.75], over: 2.75 };

  // ---- shell: front / back panels (armholes open at the sides above `split`) + side walls below the armholes.
  //      The panels run from the neckline to the hem as one face each (no seam line across the shell); the lower-body
  //      box only closes the sides below the armholes.
  const panel = (outer, front) => c => {
    const x = c.p[0], y = c.p[1];
    const top = front ? frontTop(x) : L.bTop;
    if (y < top - 1e-6 || (c.face === 'top' && front && top > L.fTop)) return null;   // cut through every face: a real neckline
    if (c.face === outer) {
      // front, between the straps (M / B): the strip above the chest loop panel is smooth shell with a sheen under a
      // rolled rim - light, not a black binding. B: a thin rim outline, then a stitched light row.
      if (front && hd && Math.abs(x) <= 4 && y < L.loop[2]) {
        if (mid) return mul(NYH, (y < top + 0.5 ? 0.92 : 1) * G(c, 0.04));
        if (y < top + 0.25) return mul(NY, 0.72 * G(c, 0.04));
        if (y < top + 0.5) return mul(NYH, (Math.floor(x * 4 + 1000) % 3 === 0 ? 0.9 : 1.04) * G(c, 0.03));
        return mul(NYH, 0.96 * G(c, 0.04));
      }
      if (y < top + (hd ? BW + (mid ? 0.25 : 0) : 1)) return bind(c, 1.05);  // neckline binding (A; back panel; armhole corners)
      if (y > 12 - L.hem) return hemRim(c);                                   // bound hem
      if (Math.abs(x) > (hd ? 4.5 - BW : 3.9)) return hd ? bind(c, 0.95) : nylon(c, 0.8);   // bound armhole / side edge
      if (fine && y > 11.5) return nylon(c, 1.08);                           // B: hem stitch line
      return nylon(c);
    }
    if (c.face === 'top') return y < 0.01 ? null : bind(c, 1.1);            // A back panel: top face would sit on the head's bottom face
    if (c.face === 'bottom') return bind(c, 0.8);                             // hem
    if (isSide(c)) {
      if (y < L.split) return mesh(c);                                        // armhole edge: the teal lining shows
      return y > 12 - L.hem ? hemRim(c, 0.95) : nylon(c, 0.95);
    }
    return null;                                                              // inner face lies on the skin: left empty so it cannot z-fight the shirt through the neckline
  };
  B.push(box('body', [-4.5, L.fTop, -2.5], [4.5, 12, L.pIn], panel('front', true), { tag: 'front panel' }));
  B.push(box('body', [-4.5, L.bTop, -L.pIn], [4.5, 12, 2.5], panel('back', false), { tag: 'back panel' }));
  const LEDGE = mul(BIND, 1.1), UNDER = mul(BIND, 0.8);                       // constants: band top / bottom and the flap faces level with them
  const lower = c => {
    if (c.face === 'front' || c.face === 'back') return null;                 // on the panels' inner faces
    if (c.face === 'top') return nylon(c);                                    // ledge under the armhole
    if (c.face === 'bottom') return Math.abs(c.p[0]) < 4.25 ? null : bind(c, 0.8);   // only the hem ring, not on the body's bottom plane
    return c.p[1] > 12 - L.hem ? hemRim(c, 0.95) : nylon(c, 0.95);
  };
  B.push(box('body', [-4.5, L.split, L.pIn], [4.5, 12, -L.pIn], lower, { tag: 'lower body sides' }));

  // ---- upper-chest loop panel: two bands split by a stitched seam, bound frame (the neckline binding above it
  //      is its top edge in A / M, where the frame would double it)
  const chest = c => {
    if (c.face === 'back') return null;                                       // lies on the front panel
    if (c.face !== 'front') return bind(c, c.face === 'top' ? 1.1 : 0.85);
    const y = c.p[1];
    if (!hd) return loop(c, y > 3 ? CHEST * 0.92 : CHEST * 1.04);            // A: two 1 px bands, lower one in shade
    if (fine && c.ex < 0.25) return bind(c, c.ev < 0.25 ? 1.15 : 0.95);      // B: bound frame
    if (mid && c.ex < 0.5 && c.ev > 0.5) return bind(c, 0.9);                // M: bound sides and bottom edge
    if (y >= L.seamY && y < L.seamY + L.seamH) return seam(c, 1.5, c.p[0]);
    if (y >= L.seamY) return loop(c, CHEST * 0.92);                           // lower band in slight shade
    return loop(c, mid && c.ev < 0.5 ? CHEST * 1.05 : CHEST);                 // M: the top row catches the light
  };
  B.push(box('body', [L.loop[0], L.loop[2], Z.loop[0]], [L.loop[1], L.loop[3], Z.loop[1]], chest, { tag: 'chest loop panel' }));

  // ---- belly band: wraps the lower torso, three equal loop bands split by stitched seams. Row kinds, from the top:
  //      'loop' | 'seam' | 'bind' (B top and bottom rows) | 'hint' (A: a faintly lighter row - 4 rows cannot hold 3 bands)
  const bandRow = y => {
    const t = y - L.belly[0];
    if (!hd) { const r = Math.floor(t + 1e-6); return r === 1 || r === 3 ? 'hint' : 'loop'; }
    if (mid) { const r = Math.floor(t * 2 + 1e-6); return r === 2 || r === 5 ? 'seam' : 'loop'; }
    const r = Math.floor(t * 4 + 1e-6);
    if (r <= 0 || r >= 15) return 'bind';
    return r === 5 || r === 10 ? 'seam' : 'loop';
  };
  // smooth strap (closure side / back): the band seams press through as darker rows
  const smoothBand = (col, row, k = 0.78) => (row === 'seam' ? mul(col, k) : row === 'bind' ? mul(col, 0.88) : col);
  const belly = c => {
    if (c.face === 'top') return LEDGE;                                       // constant: the flap top shares this plane
    if (c.face === 'bottom') return UNDER;
    const row = bandRow(c.p[1]), along = isSide(c) ? c.p[2] : c.p[0];
    if (c.face === 'right') return smoothBand(flapSide(c), row);             // wearer's right: the smooth closure strap
    if (c.face === 'back') return smoothBand(nylon(c, 0.8), row, mid ? 0.8 : 0.75);   // back (not visible in the render): plain strap, seams lightly pressed through
    if (row === 'loop') {
      // B: the loop rows next to a seam sit in its shadow, so the stitched strip reads as a raised line
      const flank = fine && [4, 6, 9, 11].includes(Math.floor((c.p[1] - L.belly[0]) * 4 + 1e-6));
      return loop(c, flank ? BAND * 0.88 : BAND, !hd);
    }
    if (row === 'hint') return loop(c, BAND * 1.2, true);
    if (row === 'seam') return seam(c, hd ? 1.45 : 1, along);               // M / B: clearly lighter stitched strips, three even bands
    return bind(c, c.p[1] < L.belly[0] + 1 ? 1.05 : 0.9);
  };
  B.push(box('body', [-4.75, L.belly[0], -2.75], [4.75, L.belly[1], 2.75], belly, { tag: 'belly band' }));

  // ---- side closure flap (wearer's right): 1 px on the front, lies over the front half of the band's right side
  //      (the band's own right face carries the same smooth strap further back); the tab end on the front is a separate,
  //      lighter piece with rounded inner corners. Built from two boxes that only touch the band (a 0.25 tab end in
  //      front of it, a 0.25 strip beside it), so no flap face shares a plane with the band's top / bottom where they
  //      overlap - the flap stays exactly as tall as the band without z-fighting.
  const [fy0, fy1] = L.flap;
  const RC = 1;                                                                // B: corner radius (3 texels cut per corner)
  // rounded inner corners, cut out so the loop band behind shows: B a quarter circle, M the one inner corner cell
  const cutB = (x, y) => fine
    ? x > -4 - RC && (y < fy0 + RC || y > fy1 - RC) &&
      (x + 4 + RC) ** 2 + (y - (y < fy0 + RC ? fy0 + RC : fy1 - RC)) ** 2 > RC * RC - 0.02
    : mid && x > -4 - K.px && (y < fy0 + K.px || y > fy1 - K.px);
  const flap = c => {
    const x = c.p[0], y = c.p[1];
    if (c.face === 'top' || c.face === 'bottom') {                           // level with the band top / bottom, same constant colour
      // same texels as the front's cut (M: the front cell's centre lies on the band's plane, hence the tolerance)
      if (c.p[2] < -2.75 + 1e-3 && cutB(x, c.face === 'top' ? fy0 + K.px / 2 : fy1 - K.px / 2)) return null;
      return c.face === 'top' ? LEDGE : UNDER;
    }
    if (c.face === 'left') return cutB(-4 - K.px / 2, y) ? null : bind(c, 0.85);   // inner edge of the tab end
    if (c.face === 'back') return flapSide(c, 0.8);                           // end of the flap along the side
    if (c.face === 'right') {                                                 // outer side
      let col = smoothBand(flapSide(c), bandRow(y));
      const dz = c.p[2] + 3;                                                  // distance from the front corner
      if (mid && dz >= 0.5 && dz < 1) col = mul(col, 0.86);                  // seam where the tab end is sewn on
      if (fine && dz >= 0.5 && dz < 0.75) col = mul(col, 1.08);               // B: stitched line
      return col;
    }
    // front: the flat tab end
    if (cutB(x, y)) return null;                                              // M / B: rounded inner corners
    let col = flapTab(c);
    if (y < fy0 + K.px) return mul(col, 1.1);                                 // light top edge
    if (fine && x > -4.5 && x < -4.25 && y > fy0 + 0.75 && y < fy1 - 0.75) col = mul(col, 1.06);   // B: stitching along the free edge
    return col;
  };
  // tab end: its back face lies on the band front / the side strip; side strip: its front is under the tab end and
  // its inner face lies on the band's right side
  B.push(box('body', [-5, fy0, -3], [-4, fy1, -2.75], c => (c.face === 'back' ? null : flap(c)), { tag: 'closure tab end' }));
  B.push(box('body', [-5, fy0, -2.75], [-4.75, fy1, 0], c => (c.face === 'front' || c.face === 'left' ? null : flap(c)), { tag: 'closure strap side' }));

  // ---- shoulder straps: rise above the front panel, over the shoulder (under the head), down the back;
  //      the front end is a separate, slightly lighter tab with a rounded end, lying on the chest loop panel
  for (const s of [-1, 1]) {
    const [xa, xb] = s < 0 ? [-4, -2] : [2, 4];                              // tabs at the outer ends of the chest panel
    const yb = L.strapEnd, R = L.R;
    const inside = (x, y) => {
      if (!R || y < yb - R) return true;
      const cx = Math.max(xa + R, Math.min(xb - R, x));
      return (x - cx) ** 2 + (y - (yb - R)) ** 2 <= R * R + 1e-6;
    };
    const near = (x, y, d) => !inside(x - d, y) || !inside(x + d, y) || !inside(x, y + d) || !inside(x - d * 0.7, y + d * 0.7) || !inside(x + d * 0.7, y + d * 0.7);
    const tabCol = c => mul(mix(strapCol(c), TAB, 0.7), G(c, 0.03));
    const strap = c => {
      if (c.face === 'top' || c.face === 'bottom' || c.face === 'back') return null;   // top: under the head; bottom: on the tab; back: on the body
      if (c.face !== 'front') return strapCol(c, 0.72);
      const col = strapCol(c), y = c.p[1];
      if (fine) return K.edge(c, col, { stitch: false });                     // plain bound edges, sewn line at the bottom
      if (mid && y > L.tabY - K.px) return mul(col, 0.78);                    // M: shadow line where the tab is sewn on
      return (c.p[0] - xa < K.px || xb - c.p[0] < K.px) ? mul(col, 0.85) : col;
    };
    const tab = c => {
      const x = c.face === 'right' ? c.p[0] + 0.01 : c.face === 'left' ? c.p[0] - 0.01 : c.p[0];
      const y = c.face === 'bottom' ? c.p[1] - 0.01 : c.p[1];
      if (!inside(x, y) || c.face === 'back') return null;                    // back face lies on the loop panel
      if (c.face === 'top') return mul(tabCol(c), 0.85);                     // top lighting is stronger: keep the edge from glowing
      if (c.face !== 'front') return strapCol(c, 0.6);                        // thin edges in shade
      const col = tabCol(c);
      if (!hd) return y > yb - 1 ? mul(col, 0.85) : col;                      // A: bottom row hints at the rounded end
      if (mid) return y > yb - R && near(x, y, 0.5) ? mul(col, 0.84) : col;  // only the rounded end is edged
      if (y < L.tabY + K.px) return mul(col, 1.06);                           // raised top edge
      if (near(x, y, 0.25)) return mul(col, 0.72);                             // bound outline
      if (near(x, y, 0.5)) return mul(col, 1.07);                              // stitch ring
      return col;
    };
    B.push(box('body', [xa, 0, Z.strap[0]], [xb, L.tabY, Z.strap[1]], strap, { tag: 'shoulder strap front' }));
    B.push(box('body', [xa, L.tabY, Z.tab[0]], [xb, yb, Z.tab[1]], tab, { tag: 'velcro tab' }));
    // over the shoulder: inside the head and over the torso footprint only (so it never shares a plane with the head's
    // visible faces); it only shows when the head turns. Runs from the front strap to the back strap without a gap.
    const [ta, tb] = s < 0 ? [fine ? -3.75 : -3.5, -2] : [2, fine ? 3.75 : 3.5];
    B.push(box('body', [ta, -0.5, -Z.over], [tb, 0, Z.over], c => strapCol(c, c.face === 'top' ? 1.1 : 0.9), { tag: 'shoulder strap top' }));
    const back = c => {
      if (c.face === 'top' || c.face === 'front') return null;                // under the head / on the back panel
      if (c.face !== 'back') return strapCol(c, 0.72);
      const col = strapCol(c);
      if (c.ev > c.fh - K.px) return mul(col, 0.78);                          // sewn end
      if (fine) {
        if (c.ex < 0.25) return mul(col, c.ev < 0.25 ? 1.1 : 0.72);           // plain bound outline
        if (c.ev > c.fh - 0.75 && c.ev < c.fh - 0.5) return mul(col, 1.07);   // stitch line across the sewn end
        return col;
      }
      return (c.p[0] - xa < K.px || xb - c.p[0] < K.px) ? mul(col, 0.85) : col;
    };
    B.push(box('body', [xa, 0, 2.5], [xb, L.backEnd, Z.over], back, { tag: 'shoulder strap back' }));
  }

  return { S: K.S, px: K.px, cell: K.cell, boxes: B };
};
