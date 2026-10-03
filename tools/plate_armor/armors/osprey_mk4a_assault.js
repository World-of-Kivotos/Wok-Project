// ================= CQC Osprey MK4A armored rig (Assault, MTP) — EFT reference =================
// tarkov.dev render 60a3c70cde5f453f634816a3 (inspect view: -image.webp; grid image "Osprey MK4A (A)"). In EFT this is an
// armored rig: the gear is part of the item model. Greenish British MTP (multi-terrain pattern) everywhere. Tall padded
// collar ringing the neck (dark lining inside, an MTP webbing strap across its front); a small MTP tab with a black snap
// rising from the back of the collar; a big pale sand-khaki padded panel on the wearer's right shoulder beside the
// collar; a round MTP pouch with a light bound rim and a black PTT (red button) on the wearer's left collar front; big
// shoulder guards: a padded shell over the shoulder and outer arm, lit MTP from the front with a lighter rolled lower
// edge and the dark open inside below it, and an MTP arm cuff underneath whose lower half is a light olive loop-velcro
// band (a khaki patch on the front-outer corner of the wearer's right shell); light grey-olive straps with dark snaps
// from the guards to the upper chest; FIVE flapped MTP pistol-mag pouches in a row on the upper chest with light khaki
// pull tabs hanging below them; FOUR open MTP rifle-mag pouches (laser-cut slots) holding warm grey-tan PMAGs; a dark
// olive double-mag pouch with two black steel mags at the wearer's right corner; a tall MTP GP pouch (flap, strap
// loop) at the wearer's left corner; under the mag bank a dark olive horizontal pouch with a small patch on its rounded
// flap and a black tourniquet through it (red tip and grey cap out at the wearer's right, plain black end at the left).
// The Protection colourway (armors/osprey_mk4a_protection.js) uses the same carrier, collar walls, shoulder pad, guards
// and belly pouch. The neck front here (low collar roll y 0..1.5 with the strap at y 0.25..1.25 under the chin, and the
// lowered oval pouch + core + PTT at y 0.5..2) is the layout both colourways should use, so nothing covers the face;
// as of 2026-10-04 the Protection file still has the older higher roll (from y -0.75) and an upright octa pouch.
ARMORS.osprey_mk4a_assault = function (mode) {
  const K = kit('M');                          // always the 2x style, whatever mode is asked for
  const { edge, molle, G, isPanel, isSide, px } = K;
  const B = [];
  // Assault: the carrier is in shadow behind the row of pistol-mag pouches and between the PMAGs (x0, x1, y0, y1, k)
  const SHADE = [[-3, 3, 1.5, 4.5, 0.5], [-4, 4, 5, 7, 0.55]];

  // ================= shared Osprey carrier (keep in step with osprey_mk4a_protection.js; see the header) =================
  const LINING = hex('#23261d');
  const LOOP = hex('#88886b');                 // light olive loop velcro (arm cuffs, rolled shell edges)
  const STRAP = hex('#8e917a');                // light grey-olive webbing straps to the shoulder guards
  const SNAP = hex('#2a2c2a');
  const OLIVE = hex('#5a5a45');                // dark olive belly pouch
  const RED = hex('#a8342c'), BLK = hex('#27292b'), CAPG = hex('#6f7270');
  const PATCH = hex('#bcae66'), PATCHIN = hex('#6f6e3a');   // yellow-khaki patch on the wearer's right shoulder shell
  const MAGTOP = hex('#2b2e30'), BRASS = hex('#caa24e'), COPPER = hex('#b5703f');

  // greenish MTP sampled on the half-pixel grid, scaled so the shapes are 1-3 px: khaki ground, olive-green,
  // brown and dark-brown shapes, sparse cream flecks (taken off the render's lit chest, shells and collar; the shapes
  // pulled a quarter of the way toward the ground so the half-pixel cells do not turn into speckle)
  const camoAt = p => layers(snap(p, px).map(v => v * 0.5), 0, '#8a8660',
    [['#a49f76', 0.55, 0.6, 3], ['#717849', 0.62, 0.56, 17], ['#806c4b', 0.7, 0.62, 41], ['#5e5739', 0.9, 0.66, 77], ['#4f4533', 1.4, 0.72, 5], ['#b8b28e', 1.5, 0.76, 91]]);
  // lit from above like the render; `o` shifts the pattern so a pouch sewn on does not continue the carrier's shapes
  const fab = (c, k = 1, amt = 0.05, o = 0) => mul(camoAt(o ? [c.p[0] + o, c.p[1] + o * 0.6, c.p[2] - o * 0.4] : c.p),
    k * G(c, amt) * (1.05 - 0.01 * Math.max(0, Math.min(16, c.p[1]))));
  const lin = c => mul(LINING, G(c, 0.1));
  // MOLLE webbing rows 1.5 px apart: a printed-MTP tape row (camo toned down toward the ground) over a darker
  // shadow row; no slot cells (on camo they turned the back into speckle)
  const GROUND = hex('#8a8660');
  const molleRows = (c, col, y0, n, h, h0, h1, t = 0.4, k = 1.05) => {
    const dy = c.p[1] - y0;
    if (h < h0 || h > h1 || dy < 0) return null;
    const i = Math.floor(dy / 1.5), r = dy - i * 1.5;
    if (i >= n || r >= 1) return null;
    return r < 0.5 ? mul(mix(col, mul(GROUND, 1.02), t), k) : mul(col, 0.7);
  };
  const inShade = c => { for (const [x0, x1, y0, y1, k] of SHADE) if (c.p[0] > x0 && c.p[0] < x1 && c.p[1] > y0 && c.p[1] < y1) return k; return 0; };
  const cellU = c => Math.floor(c.eu / px), cellV = c => Math.floor(c.ev / px), cellsU = c => Math.round(c.fw / px);

  // magazines sit IN their pouches: feed-lip block with one brass round on top, broad side forward, darker spine
  const magPaint = (base, ribs, capped = false) => c => {
    if (c.face === 'bottom') return mul(base, 0.6);
    if (c.face === 'top' && capped) return mul(MAGTOP, c.ex < px ? 1.5 : 1);             // under the feed lips
    if (c.face === 'top') {
      const n = Math.max(1, cellsU(c)), i = Math.min(n - 1, cellU(c));
      const lo = Math.floor((n - 1) / 2), hi = Math.floor(n / 2);
      if (i >= lo && i <= hi) return i === hi && n >= 3 ? COPPER : BRASS;
      return mul(MAGTOP, 1.5);
    }
    let col = mul(base, G(c, 0.04));
    if (!isPanel(c)) return mul(col, 0.74);                                                 // spine
    if (c.ev < px) return mul(col, 1.25);                                                   // feed-lip edge
    if (ribs && cellU(c) === Math.floor(cellsU(c) / 2)) col = mul(col, 0.8);              // rib
    return col;
  };
  const lipsPaint = side => c => {
    if (c.face !== 'top') return mul(side, isPanel(c) ? 1.15 : 0.9);
    const n = cellsU(c);
    return n >= 2 && cellU(c) === n - 1 ? COPPER : BRASS;                                  // round, copper tip on the wearer's left
  };
  const lips = lipsPaint(MAGTOP);

  // ---------------- carrier ----------------
  const bag = c => {
    if (c.face === 'top') return lin(c);                                                   // inner lining at the neckline
    let col = fab(c);
    if (c.face === 'front') {
      const k = inShade(c);
      if (k) return mul(col, k);                                                           // in shadow behind the gear
      // (the chest webbing is lighter tape than the back's, as in the render)
      col = molleRows(c, col, 1.75, 3, c.p[0], -3.75, 3.75, 0.55, 1.1) || molleRows(c, col, 10.25, 1, c.p[0], -3.75, 3.75) || col;
    }
    return edge(c, col);
  };
  B.push(box('body', [-4.25, 0.75, -3.25], [4.25, 11, -2.5], bag, { tag: 'front plate bag' }));
  const back = c => {
    if (c.face === 'top') return lin(c);
    const col = fab(c);
    return edge(c, (c.face === 'back' && molleRows(c, col, 1.75, 6, -c.p[0], -3.5, 3.5)) || col);
  };
  B.push(box('body', [-4.25, 0.5, 2.5], [4.25, 11, 3.25], back, { tag: 'back plate bag' }));
  // pleated side panels: soft vertical ribs every 1.5 px, a shade darker than the bags
  const cumm = c => {
    let col = fab(c, 0.86);
    if (isSide(c) && (((c.p[2] + 3) % 1.5) + 1.5) % 1.5 < px) col = mul(col, 0.74);
    return edge(c, col, { stitch: false });
  };
  // ends 0.25 above the bags' bottoms so the bottom faces never share a plane
  B.push(box('body', [-4.5, 4.75, -2.75], [4.5, 10.75, 2.75], cumm, { tag: 'cummerbund' }));

  // ---------------- collar ----------------
  // tall padded ring round the neck. The MC head hides everything above the chin inside the hat layer, so it shows as
  // walls beside and behind the head plus a low front roll UNDER the chin (its top at the head's bottom, so nothing
  // covers the face); dark lining toward the neck.
  const cFab = c => fab(c, 0.95, 0.05, 2.2);
  const cLin = c => mul(mix(fab(c, 0.55, 0.05, 2.2), LINING, 0.55), 1);
  B.push(box('body', [-4.5, 0, -4.75], [4.5, 1.5, -3.5], c => {
    if (c.face === 'back' || c.face === 'bottom') return cLin(c);
    if (c.face === 'top') return c.p[2] > -4.25 ? cLin(c) : mul(cFab(c), 1.1);           // rim, lining toward the neck
    return edge(c, cFab(c), { stitch: false });
  }, { tag: 'collar front' }));
  for (const s of [-1, 1]) {
    const inward = s < 0 ? 'left' : 'right';
    B.push(box('body', s < 0 ? [-5.5, -2.5, -4.75] : [4.5, -2.5, -4.75], s < 0 ? [-4.5, 0.25, 5.5] : [5.5, 0.25, 5.5], c => {
      if (c.face === inward || c.face === 'bottom') return cLin(c);
      if (c.face === 'top') return Math.abs(c.p[0]) < 5 ? cLin(c) : mul(cFab(c), 1.1);
      return edge(c, cFab(c), { stitch: false });
    }, { tag: 'collar side' }));
  }
  // back wall rises highest; it reaches into the back bag so it sits on the carrier
  B.push(box('body', [-4.5, -3, 3], [4.5, 1, 5.5], c => {
    if (c.face === 'front' || c.face === 'bottom') return cLin(c);
    if (c.face === 'top') return c.p[2] < 4.75 ? cLin(c) : mul(cFab(c), 1.1);
    return edge(c, cFab(c), { stitch: false });
  }, { tag: 'collar back' }));
  // MTP webbing strap across the collar front, a dark keeper slot left of the middle (its wearer's-left end runs under
  // the round pouch)
  B.push(box('body', [-3, 0.25, -5], [3, 1.25, -4.75], c => {
    if (c.face === 'back') return null;
    const col = mul(mix(fab(c, 1, 0.05, 6.3), GROUND, 0.45), 1.12);                       // printed webbing, lighter than the roll
    if (!isPanel(c)) return mul(col, 0.78);
    if (c.p[0] > -0.5 && c.p[0] < 0) return mul(col, 0.5);                                 // keeper slot
    return cellV(c) === 0 ? mul(col, 1.06) : mul(col, 0.86);
  }, { tag: 'collar strap' }));
  // small MTP tab with a black snap rising from the back of the collar
  B.push(box('body', [-0.5, -4, 4.75], [0.5, -2.75, 5.25], c => {
    const col = fab(c, 1, 0.05, 4.4);
    if (c.face === 'back' && cellV(c) === 1) return SNAP;
    return edge(c, col, { stitch: false });
  }, { tag: 'collar back tab' }));

  // big pale sand-khaki padded panel on the wearer's right shoulder beside the collar (the render's brightest patch).
  // The MC head takes the top of the shoulder, so it is a thick pad capping the top outer corner of the collar's side
  // wall on that side: seen beside the cheek from the front and along the shoulder from the side; faint dirt, lit top,
  // the lower row a shade darker where the padding rolls under (same pad as osprey_mk4a_protection.js)
  const PADC = hex('#a69d78');
  B.push(box('body', [-5.75, -2.75, -5], [-4.25, -1.75, -1.5], c => {
    const q = snap(c.p, px);
    const col = mul(PADC, (0.88 + 0.16 * fbm(q[0] * 0.9 + 13, q[1] * 0.9 + 5, q[2] * 0.9 + 2)) * G(c, 0.06));
    if (c.face === 'top') return mul(col, 1.08);
    if (c.face === 'bottom') return mul(col, 0.66);
    if (c.face === 'left' || c.face === 'back') return mul(col, 0.8);                        // toward the neck / rear end
    return mul(col, cellV(c) === 0 ? 1.02 : 0.9);                                            // front and outer side
  }, { tag: 'shoulder pad' }));

  // round MTP pouch on the wearer's left collar front: a lit oval with a light bound rim, leaning out, a black PTT with
  // a red button at its inner edge. Its top sits at the head's bottom (beside the chin, not over the face); the lower
  // half hangs over the upper chest, so a smaller padded core inside the oval carries it back onto the pistol-mag pouch
  // (it also fills the oval's edge seen from the side)
  const oval = (c, u, v) => u * u + v * v;
  B.push(box('body', [2.75, 0.375, -5], [4.25, 2.875, -4], c => {
    const col = fab(c, 0.95, 0.05, 8.1);
    if (c.face === 'front') return mul(mix(fab(c, 1.15, 0.05, 8.1), LOOP, 0.45), 1.1);      // rim, under the oval panel
    return mul(col, c.face === 'top' ? 0.9 : c.face === 'back' ? 0.62 : 0.74);
  }, { tag: 'round pouch core', rot: [0, 0, 12], pivot: [3.5, 3.125, -5] }));
  B.push(box('body', [2.25, 0.125, -5.25], [4.75, 3.125, -4.75], c => {
    const u = (c.eu / c.fw - 0.5) * 2, v = (c.ev / c.fh - 0.5) * 2;
    if (isPanel(c)) {
      const r = oval(c, u, v);
      if (r > 1.05) return null;
      if (c.face === 'back') return lin(c);
      const col = fab(c, 1.15, 0.05, 8.1);
      return r > 0.6 ? mul(mix(col, LOOP, 0.45), 1.1) : col;                                 // light bound rim
    }
    if (isSide(c) && Math.abs(v) > 0.7) return null;                                        // follow the oval outline
    if (!isSide(c) && Math.abs(u) > 0.5) return null;
    return mul(fab(c, 0.95, 0.05, 8.1), 0.8);
  }, { tag: 'round PTT pouch', rot: [0, 0, 12], pivot: [3.5, 3.125, -5] }));
  B.push(box('body', [2, 0.5, -5.5], [2.5, 2, -5], c => {
    if (c.face === 'top') return RED;
    if (isPanel(c) && cellV(c) === 0) return mul(RED, 0.9);
    return mul(BLK, c.face === 'front' ? 1.15 : 0.9);
  }, { tag: 'PTT', rot: [0, 0, 18], pivot: [2.25, 2, -5.25] }));

  // light grey-olive straps from the shoulder guards to the upper chest, a dark snap at each end (the outer snap lies on
  // the guard's front wall)
  const strapPaint = inner => c => {
    const col = mul(STRAP, G(c, 0.05));
    if (!isPanel(c)) return mul(col, 0.8);
    const ax = Math.abs(c.p[0]);
    if (ax > 5 || (inner && ax < 3.5)) return mul(SNAP, cellV(c) === 0 ? 1.4 : 1);
    return cellV(c) === 0 ? mul(col, 1.06) : mul(col, 0.9);
  };

  // ---------------- shoulder guards (follow the arms) ----------------
  // padded shell over the shoulder and down the outer arm (flared, rolled light lower edge, dark lining inside), front
  // and back walls, and an MTP cuff round the upper arm whose lower half is a light olive loop band
  for (const s of [-1, 1]) {
    const part = s < 0 ? 'right_arm' : 'left_arm';
    const X = (a, b) => (s < 0 ? [a, b] : [-b, -a]);
    const out = s < 0 ? 'right' : 'left', inn = s < 0 ? 'left' : 'right';
    const shellFab = c => fab(c, 1.1, 0.06, 1.7);
    const rolled = c => mul(mix(shellFab(c), LOOP, 0.55), 0.95);
    const [c0, c1] = X(-4, -0.5);
    B.push(box(part, [c0, -3, -3], [c1, -2, 3], c => {
      if (c.face === 'bottom') return lin(c);
      const col = shellFab(c);
      if (c.face === 'top') return mul(col, 1.04);
      if (c.face === inn) return mul(col, 0.8);
      return edge(c, col, { stitch: false });
    }, { tag: 'shoulder cap' }));
    const [o0, o1] = X(-4.5, -3.5);
    B.push(box(part, [o0, -2.5, -3], [o1, 3, 3], c => {
      if (c.face === inn) return lin(c);
      const col = shellFab(c);
      if (c.face === 'bottom') return rolled(c);
      if (c.face === 'top') return col;
      if (c.ev > c.fh - px) return rolled(c);                                               // rolled lower edge
      if (s < 0 && c.face === out && c.ev >= 0.5 && c.ev < 2 && c.p[2] > -3 && c.p[2] < -1) // khaki patch, olive flag inside
        return c.ev >= 1 && c.ev < 1.5 && c.p[2] > -2.5 && c.p[2] < -1.5 ? mul(PATCHIN, G(c, 0.05)) : mul(PATCH, G(c, 0.05));
      return edge(c, col, { stitch: false });
    }, { tag: 'shoulder shell', rot: [0, 0, -s * 10], pivot: [s * 4, -2.5, 0] }));
    for (const [z0, z1] of [[-3.25, -2.75], [2.75, 3.25]]) {
      const face = z0 < 0 ? 'front' : 'back', backF = z0 < 0 ? 'back' : 'front';
      // reaches in to the carrier's edge, so no sleeve shows between the guard and the carrier
      const [w0, w1] = X(-3.75, 0.75);
      // seen from the front (and back) the pauldron is lit MTP over the shoulder, then its light rolled lower edge, then
      // the dark open inside of the shell (the arm fills it, the cuff's light loop band closes it below); the outer
      // column stays camo. The wearer's right one carries the khaki patch on its front-outer corner.
      const outerX = s < 0 ? w0 : w1;
      B.push(box(part, [w0, -2.5, z0], [w1, 2.5, z1], c => {
        if (c.face === backF) return lin(c);
        if (c.face === 'bottom') return rolled(c);
        if (c.face !== face) return edge(c, shellFab(c), { stitch: false });
        const dOut = Math.abs(c.p[0] - (s < 0 ? -5 : 5) - outerX);                         // distance to the outer edge
        if (c.ev < 2) {
          if (s < 0 && face === 'front' && dOut < 1.5 && c.ev >= 0.5 && c.ev < 1.5)        // khaki patch, olive flag inside
            return dOut >= 0.5 && c.ev >= 1 ? mul(PATCHIN, G(c, 0.05)) : mul(PATCH, G(c, 0.05));
          return mul(shellFab(c), c.ev < px ? 1.1 : 1.05);                                  // lit shell
        }
        if (c.ev < 2.5) return rolled(c);                                                    // rolled lower edge
        if (dOut < px) return mul(shellFab(c), 0.86);
        return mul(mix(fab(c, 0.62, 0.05, 1.7), LINING, 0.5), 1);                           // dark inside
      }, { tag: 'shoulder shell ' + face }));
    }
    const [k0, k1] = X(-3.75, 0.5);
    B.push(box(part, [k0, 2.25, -2.75], [k1, 4.75, 2.75], c => {
      if (c.face === 'top' || c.face === 'bottom' || c.face === inn) return null;
      const j = cellV(c), col = fab(c, 1, 0.05, 1.7);
      if (j <= 1) return mul(col, j === 0 ? 1.05 : 0.95);                                   // MTP upper half
      if (j >= 4) return mul(mix(col, LOOP, 0.3), 0.66);                                   // dark bound bottom edge
      return mul(LOOP, G(c, 0.05) * (c.face === out ? 0.95 : 1) * (j === 2 ? 1.05 : 0.95)); // light loop band
    }, { tag: 'arm cuff' }));
  }

  // ---------------- belly pouch + tourniquet under the mag bank ----------------
  const olive = c => {
    let col = mul(OLIVE, G(c, 0.06));
    if (c.face === 'top') return mul(col, 1.1);
    if (c.face !== 'front') return mul(col, 0.8);
    const y = c.p[1], i = cellU(c), n = cellsU(c);
    if (y < 10.75) return mul(col, 1.1);                                                     // lit top of the rounded flap
    if (y < 11.25) return i === (n >> 1) ? mul(col, 0.66) : col;                             // small patch in the middle
    if (y < 11.75) return (i === 0 || i === n - 1) ? mul(col, 0.8) : mul(col, 0.68);         // flap's rounded lower edge
    return edge(c, mul(col, 0.95), { stitch: false });
  };
  B.push(box('body', [-1, 10.25, -3.75], [1.5, 12.25, -3.25], olive, { tag: 'belly pouch' }));
  // a black tourniquet runs through it: the red-tipped end with a grey cap sticks out at the wearer's right, the plain
  // black end at the wearer's left
  B.push(box('body', [-2, 10.75, -4], [-1, 12, -3.25], c => {
    if (c.face === 'top') return CAPG;
    if (c.face === 'front') {
      if (cellV(c) === 0) return mul(CAPG, 0.9);                                             // grey cap
      return cellU(c) === 0 ? mul(RED, cellV(c) === 1 ? 1.1 : 1) : BLK;                      // red tip | black body
    }
    return mul(BLK, 0.9);
  }, { tag: 'tourniquet (red tip)' }));
  B.push(box('body', [1.5, 11, -4], [2, 12, -3.25], c => mul(BLK, c.face === 'top' ? 1.25 : c.face === 'front' ? (cellV(c) === 0 ? 1.2 : 1.05) : 0.85),
    { tag: 'tourniquet end' }));

  // ================= Assault loadout =================
  // tall MTP GP pouch at the wearer's left front corner (closed flap), rising above the mag bank, with a light
  // grey-olive webbing strap rising from its inner top corner toward the shoulder guard
  const gp = c => {
    let col = fab(c, 1, 0.05, 9.2);
    if (c.face === 'top') return mul(col, 1.1);                                              // closed flap
    if (c.face === 'front') {
      const j = cellV(c), i = cellU(c);
      if (j <= 1) col = mul(col, j === 0 ? 1.12 : 1.02);                                     // flap
      else if (j === 2) col = mul(col, 0.68);                                                // shadow under the flap
      else if (i === 0) col = mul(col, 0.8);                                                 // seam along the inner edge
    }
    return edge(c, col, { stitch: false });
  };
  // (its inner edge tucked behind the last rifle pouch, the outer edge only a little past the carrier's)
  B.push(box('body', [3.25, 6, -4.25], [4.75, 10.5, -2.75], gp, { tag: 'GP pouch' }));
  B.push(box('body', [3.25, 5, -3.75], [3.75, 6.25, -3.25], K.solid(mul(STRAP, 0.88), 0.05), { tag: 'GP strap loop', rot: [0, 0, 20], pivot: [3.5, 6.25, -3.5] }));
  // straps to the guards: dark snap at the outer end (lying on the guard's front wall) and one at the inner end, right
  // beside the pistol-mag pouches
  for (const s of [-1, 1]) {
    const [a0, a1] = s < 0 ? [-5.5, -3] : [3, 5.5];
    B.push(box('body', [a0, 1.5, -3.5], [a1, 2, -3.25], strapPaint(true), { tag: 'shoulder-guard strap' }));
  }
  // five flapped MTP pistol-mag pouches in a row, light khaki pull tabs hanging below them
  const TAB = hex('#8f866f');
  // (two texels wide, so the camo is toned down toward the ground or each pouch turns into speckle; lit left column)
  const upPouch = c => {
    const col = mix(fab(c, 0.97, 0.05, 3.3), GROUND, 0.4);
    if (c.face === 'bottom') return mul(col, 0.62);
    if (c.face === 'top') return mul(col, 1.12);                                             // closed flap
    if (c.face === 'front') {
      const j = cellV(c), i = cellU(c);
      if (j <= 1) return mul(col, j === 0 ? 1.14 : 1.04);                                    // flap
      if (j === 2) return mul(col, 0.66);                                                    // shadow under the flap
      return mul(col, i === 0 ? 1 : 0.88);
    }
    return mul(col, 0.78);
  };
  for (const x0 of [-3, -1.75, -0.5, 0.75, 2]) {
    B.push(box('body', [x0, 1.5, -4], [x0 + 1, 4.5, -3.25], upPouch, { tag: 'pistol mag pouch' }));
    // short pull tab from the lower half of the pouch to just below it
    B.push(box('body', [x0 + 0.25, 3.5, -4.25], [x0 + 0.75, 4.75, -4], c => mul(TAB, G(c, 0.05) * (c.face === 'front' ? (c.ev < px ? 1.08 : 1) : 0.78)), { tag: 'pull tab' }));
  }
  // four open MTP rifle-mag pouches with laser-cut slot rows, warm grey-tan PMAGs standing in them
  const PMAG = hex('#938b7e');
  // (each pouch is its own piece of cloth: its own camo offset; lit left edge, dark seam on the right)
  const bankPouch = o => c => {
    if (c.face === 'top') return c.ex < px ? mul(fab(c, 0.95, 0.05, o), 1.1) : mul(LINING, 1.3);   // open mouth
    const col = mix(fab(c, 0.95, 0.05, o), mul(GROUND, 0.9), 0.4);                            // subdued camo, as in the render
    if (c.face === 'front') {
      const j = cellV(c), i = cellU(c), n = cellsU(c);
      if (j === 0) return mul(col, 1.15);                                                    // elastic binding of the mouth
      if (i === n - 1) return mul(col, 0.66);                                                // seam to the next pouch
      if (j === 2 || j === 4) return mul(col, i === 0 ? 0.8 : 0.68);                         // laser-cut slots
      return mul(col, i === 0 ? 1.08 : 1);
    }
    return edge(c, col, { stitch: false });
  };
  // polymer feed lips in a darker shade of the mag (near-black lips read as a black cap on the tan PMAGs)
  const rifle = magPaint(PMAG, true, true), pmagLips = lipsPaint(mul(PMAG, 0.55));
  // (the bank is a little narrower than the carrier so the two corner pouches can sit inboard, in front of the carrier
  // and not out in front of the arms)
  for (const cx of [-2.625, -0.875, 0.875, 2.625]) {
    B.push(box('body', [cx - 0.875, 7, -4.5], [cx + 0.875, 10.25, -3.25], bankPouch(5.1 + cx * 2.3), { tag: 'rifle mag pouch' }));
    B.push(box('body', [cx - 0.625, 5.5, -4.25], [cx + 0.625, 9.75, -3.5], rifle, { tag: 'PMAG' }));
    B.push(box('body', [cx - 0.375, 5, -4.125], [cx + 0.375, 5.5, -3.625], pmagLips, { tag: 'PMAG feed lips' }));
  }
  // dark olive double-mag pouch at the wearer's right corner (its inner edge tucked behind the first rifle pouch, the
  // outer edge only a little past the carrier's), two black steel mags side by side in it, level with the PMAGs (the
  // inner one a hair further forward)
  const DOL = hex('#4a4839');
  const corner = c => {
    if (c.face === 'top') return c.ex < px ? mul(DOL, 1.3) : mul(LINING, 1.2);
    const col = mul(DOL, G(c, 0.06));
    if (c.face === 'front') {
      const j = cellV(c);
      if (j === 0) return mul(col, 1.2);
      if (j % 3 === 2) return mul(col, 0.72);                                                // MOLLE rows
    }
    return edge(c, col, { stitch: false });
  };
  B.push(box('body', [-5, 7.25, -4.25], [-3.25, 11.25, -2.75], corner, { tag: 'double mag pouch' }));
  const STEEL = hex('#474b4e'), black = magPaint(STEEL, false, true);
  B.push(box('body', [-4.875, 5.5, -3.75], [-4.125, 9.75, -3.25], black, { tag: 'black mag (outer)' }));
  B.push(box('body', [-4.75, 5, -3.625], [-4.25, 5.5, -3.375], lips, { tag: 'black mag feed lips (outer)' }));
  B.push(box('body', [-4.125, 5.5, -4], [-3.375, 9.75, -3.5], black, { tag: 'black mag (inner)' }));
  B.push(box('body', [-4, 5, -3.875], [-3.5, 5.5, -3.625], lips, { tag: 'black mag feed lips (inner)' }));
  return { S: K.S, px: K.px, cell: K.cell, boxes: B };
};
