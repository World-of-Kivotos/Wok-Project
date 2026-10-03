// ================= CQC Osprey MK4A armored rig (Protection, MTP) — EFT reference =================
// tarkov.dev render 60a3c68c37ea821725773ef5 (inspect view: -image.webp; grid image "Osprey MK4A (P)"). In EFT this is an
// armored rig: the gear is part of the item model. Same greenish British MTP carrier as the Assault colourway
// (armors/osprey_mk4a_assault.js, whose shared carrier code is copied below unchanged): tall padded collar with a
// webbing strap across its front and a snap tab at the back, the big pale sand-khaki pad on the wearer's right shoulder
// beside the collar, round MTP pouch with a light rim and a black PTT (red button) on the wearer's left collar front, big
// shoulder guards (lit MTP, light rolled lower edge, dark inside below it, khaki patch on the wearer's right shell, MTP
// arm cuffs with a light olive loop band), light grey-olive straps with dark snaps from the guards, and the dark olive
// belly pouch with the black red-tipped tourniquet under the mag bank. Its own loadout: the upper chest is mostly EMPTY
// MOLLE rows; a tan D-ring at the inner end of each strap (round arc up, flat bar at the bottom); ONE dark-olive double
// pistol-mag pouch (two flaps) on the wearer's left chest; FOUR dark olive-brown double rifle-mag pouches (a wide dark
// webbing band at the mouth, narrower ones across the middle and bottom, MTP strips between) each holding TWO black steel AR mags side by
// side; at the wearer's right corner a dark grey pouch with a black velcro strip down the middle, thin black shock cord
// zig-zagging across it, a grey webbing loop on top and a dark strap tab hanging below; at the wearer's left corner an
// MTP cummerbund side pouch whose top is level with the bank, a dark band across its top and two black buckle straps
// down its outer side (no tall flapped GP pouch and no rising strap there, unlike the Assault).
ARMORS.osprey_mk4a_protection = function (mode) {
  const K = kit('M');                          // always the 2x style, whatever mode is asked for
  const { edge, molle, G, isPanel, isSide, px } = K;
  const B = [];
  // Protection: the carrier is in shadow only between the mags above the mag bank (x0, x1, y0, y1, k)
  const SHADE = [[-4, 4, 5, 7, 0.55]];

  // ================= shared Osprey carrier (same code in both Osprey files) =================
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
  // walls beside and behind the head plus a front roll in front of the chin; dark lining toward the neck.
  const cFab = c => fab(c, 0.95, 0.05, 2.2);
  const cLin = c => mul(mix(fab(c, 0.55, 0.05, 2.2), LINING, 0.55), 1);
  B.push(box('body', [-4.5, -0.75, -4.75], [4.5, 1.25, -3.5], c => {
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
  B.push(box('body', [-3, -0.25, -5], [3, 0.75, -4.75], c => {
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
  // the lower row a shade darker where the padding rolls under
  const PADC = hex('#a69d78');
  B.push(box('body', [-5.75, -2.75, -5], [-4.25, -1.75, -1.5], c => {
    const q = snap(c.p, px);
    const col = mul(PADC, (0.88 + 0.16 * fbm(q[0] * 0.9 + 13, q[1] * 0.9 + 5, q[2] * 0.9 + 2)) * G(c, 0.06));
    if (c.face === 'top') return mul(col, 1.08);
    if (c.face === 'bottom') return mul(col, 0.66);
    if (c.face === 'left' || c.face === 'back') return mul(col, 0.8);                        // toward the neck / rear end
    return mul(col, cellV(c) === 0 ? 1.02 : 0.9);                                            // front and outer side
  }, { tag: 'shoulder pad' }));

  // round MTP pouch standing upright on the wearer's left collar front, built as an octa: a square plate plus the same
  // plate turned 45 degrees. Each plate drops its four corner cells (a plus shape), so together they make a near-round
  // disc instead of an eight-pointed star; the turned plate sits a quarter back so the two fronts never share a plane
  // and the pouch reads domed. Lit MTP in the middle, a lighter bound rim round it, dark lining at the back
  const PO = [3.25, 0, -5.125];
  const pouchCol = turned => c => {
    const lx = c.p[0] - PO[0], ly = c.p[1] - PO[1];                                         // in the plate's own frame
    if (isPanel(c)) {
      if (Math.abs(lx) > 0.5 && Math.abs(ly) > 0.5) return null;                            // corner cells
      const mid = Math.abs(lx) < 0.5 && Math.abs(ly) < 0.5;
      if (c.face === 'back') return turned && mid ? null : lin(c);
      const col = fab(c, 1.15, 0.05, 8.1);
      if (mid) return turned ? mul(col, 0.9) : col;                                          // dome (the turned one is hidden)
      return mul(mix(col, LOOP, 0.3), turned ? 0.98 : 1.06);                                 // bound rim
    }
    if (Math.abs(isSide(c) ? ly : lx) > 0.5) return null;                                    // only the arm ends close the edge
    return mul(mix(fab(c, 1, 0.05, 8.1), LOOP, 0.4), c.face === 'top' ? 1.05 : 0.86);
  };
  octa(B, 'body', PO, [2, 2, 0.75], 'z', pouchCol(false), { tag: 'round PTT pouch' });
  B[B.length - 1] = { ...B[B.length - 1], z: -5.25, d: 0.5, mat: pouchCol(true), tag: 'round PTT pouch (turned)' };
  // black PTT with a red button on top, hanging from the pouch's lower inner edge against the collar roll
  B.push(box('body', [2, 0, -5.75], [2.5, 1.5, -4.75], c => {
    if (c.face === 'top') return RED;
    if (isPanel(c) && cellV(c) === 0) return mul(RED, 0.9);
    return mul(BLK, c.face === 'front' ? 1.15 : 0.9);
  }, { tag: 'PTT' }));

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

  // ================= Protection loadout =================
  // straps to the guards: dark snap at the outer end (lying on the guard's front wall), the inner end runs into a D-ring
  for (const s of [-1, 1]) {
    const [a0, a1] = s < 0 ? [-5.75, -4.25] : [4.25, 5.75];
    B.push(box('body', [a0, 1.5, -3.5], [a1, 2, -3.25], strapPaint(false), { tag: 'shoulder-guard strap' }));
  }
  // tan-coated D-ring at the inner end of each strap, lying on the carrier's webbing: round arc up, flat bar at the
  // bottom; the wearer's left one just outside the top corner of the double pistol pouch. A shade lighter than the
  // light camo, and the carrier seen through the ring is in its shadow, or the ring melts into the MTP.
  const DTAN = hex('#b8ae80');
  const DP = ['.#.', '#o#', '###'];
  const dring = c => {
    if (!isPanel(c) || c.face === 'back') return null;
    const i = Math.min(2, cellU(c)), j = Math.min(2, cellV(c)), t = DP[j][i];
    if (t === 'o') return mul(fab(c), 0.55);                                                // shadowed webbing in the hole
    return t === '#' ? mul(DTAN, (j === 2 ? 1.08 : 0.96) * G(c, 0.04)) : null;
  };
  for (const s of [-1, 1])
    B.push(box('body', s < 0 ? [-4.25, 1.25, -3.5] : [2.75, 1.25, -3.5], s < 0 ? [-2.75, 2.75, -3.25] : [4.25, 2.75, -3.25], dring, { tag: 'D-ring' }));
  // dark-olive double pistol-mag pouch on the wearer's left chest: two flapped sleeves with a seam between them
  const PDK = hex('#4b4a3b');
  const dpouch = c => {
    const col = mix(fab(c, 1, 0.05, 3.3), PDK, 0.55);
    if (c.face === 'bottom') return mul(col, 0.62);
    if (c.face === 'top') return mul(col, 1.15);                                             // closed flaps
    if (c.face !== 'front') return mul(col, 0.8);
    const i = cellU(c), j = cellV(c);
    if (i === 2) return mul(col, 0.6);                                                       // seam between the sleeves
    if (j <= 1) return mul(col, j === 0 ? 1.14 : 1.04);                                      // flaps
    if (j === 2) return mul(col, 0.62);                                                      // shadow under the flaps
    return mul(col, 0.92);
  };
  B.push(box('body', [0.25, 1.5, -4], [2.75, 4.5, -3.25], dpouch, { tag: 'double pistol mag pouch' }));
  // four dark olive-brown double rifle-mag pouches, each holding two black steel AR mags side by side (the left one set
  // back a little, the right one in front and a hair lower): a wide dark brown webbing band at the mouth, narrower ones
  // across the middle and at the bottom, wider MTP strips between them (about as bright as the carrier at that height,
  // as in the render), and a dark gap column to the next pouch. Rows only, no seam column: a 2 px pouch has no cell on
  // its true centre line, and an off-centre seam plus the gap turned the bank into a plaid
  const DPK = hex('#3d3c2f'), WEBB = hex('#3b372c');
  const darkPouch = o => c => {
    if (c.face === 'top') return c.ex < px ? mul(DPK, 1.25) : mul(LINING, 1.2);             // open mouth
    const col = mix(fab(c, 1, 0.05, o), DPK, 0.4);
    if (c.face === 'front') {
      const j = cellV(c), i = cellU(c), n = cellsU(c);
      if (i === n - 1) return mul(col, 0.6);                                                 // gap to the next pouch
      if (j <= 1) return mul(mix(col, WEBB, 0.6), j === 0 ? 1.06 : 0.96);                     // mouth band, lit rim
      if (j === 4 || j >= 7) return mul(mix(col, WEBB, 0.6), 0.94);                           // middle and bottom bands
      return mul(col, 1.02);                                                                  // MTP strips
    }
    return edge(c, col, { stitch: false });
  };
  // (the pairs nearly fill the bank's width like the render; darker rib column on the right so the two mags separate)
  const STEEL = hex('#484c51'), steel = magPaint(STEEL, true, true);
  for (const cx of [-3, -1, 1, 3]) {
    // (7.5 rows tall: mouth band 2 rows, MTP 2, band 1, MTP 2, a half-row bottom band; the mags show above the mouth
    // about half the pouch's height, as in the render)
    B.push(box('body', [cx - 1, 6.75, -4.5], [cx + 1, 10.5, -3.25], darkPouch(5.1 + cx * 2.3), { tag: 'double rifle mag pouch' }));
    B.push(box('body', [cx - 0.875, 5.25, -4], [cx + 0.125, 9.75, -3.5], steel, { tag: 'steel mag (rear)' }));
    B.push(box('body', [cx - 0.625, 4.75, -3.875], [cx - 0.125, 5.25, -3.625], lips, { tag: 'steel mag feed lips (rear)' }));
    B.push(box('body', [cx - 0.125, 5.5, -4.25], [cx + 0.875, 9.75, -3.75], steel, { tag: 'steel mag (front)' }));
    B.push(box('body', [cx + 0.125, 5, -4.125], [cx + 0.625, 5.5, -3.875], lips, { tag: 'steel mag feed lips (front)' }));
  }
  // dark grey pouch at the wearer's right corner: a black velcro strip down the middle, thin black shock cord
  // zig-zagging across it from hooks at the edges (one-cell steps either side of the strip), a grey webbing loop on top
  // and a dark strap tab hanging below
  const SHK = hex('#46453a');
  const shock = c => {
    if (c.face === 'top') return mul(SHK, 1.25);
    const col = mul(SHK, G(c, 0.06));
    if (c.face === 'front') {
      const j = cellV(c), i = cellU(c);
      if (j === 0) return mul(col, 1.2);                                                     // bound top
      if (i === 1) return mul(col, 0.62);                                                    // velcro strip
      const k = Math.floor((j - 1) / 3), r = (j - 1) % 3;
      if ((r === 1 && i === (k & 1 ? 2 : 0)) || (r === 2 && i === (k & 1 ? 0 : 2))) return mul(col, 0.55);   // cord
      return col;
    }
    return edge(c, col, { stitch: false });
  };
  B.push(box('body', [-5.5, 6.25, -4.25], [-4, 11.25, -2.75], shock, { tag: 'shock-cord pouch' }));
  const LP = ['###', '#.#'], GREY = hex('#80827b');
  B.push(box('body', [-5.5, 5.25, -3.75], [-4, 6.25, -3.5], c => {
    if (!isPanel(c) || c.face === 'back') return null;
    const i = Math.min(2, cellU(c)), j = Math.min(1, cellV(c));
    return LP[j][i] === '#' ? mul(GREY, j === 0 ? 1.1 : 0.95) : null;
  }, { tag: 'grey webbing loop' }));
  const TABC = hex('#3a3329');
  B.push(box('body', [-5.5, 11.25, -3.75], [-5, 12.25, -3.25], c => mul(TABC, G(c, 0.05) * (c.face === 'front' ? (cellV(c) === 0 ? 0.9 : 1.05) : 0.8)), { tag: 'shock pouch strap tab' }));
  // MTP cummerbund side pouch at the wearer's left corner, its top level with the bank: a dark band across its top and
  // two black buckle straps down its outer side (one on the front's outer column, one on the outer face), pale buckles
  const BUCK = hex('#2e302c'), BUCKLE = hex('#8a8774');
  const sidePouch = c => {
    const col = fab(c, 1, 0.05, 9.2);
    if (c.face === 'top') return mul(col, 1.1);
    if (c.face === 'front' || c.face === 'left') {                                           // 'left' = the outer face
      const j = cellV(c);
      if (j === 0) return mul(col, 1.08);
      if (j === 1) return mul(mix(col, BUCK, 0.6), 1.05);                                    // dark band across the top
      const onStrap = c.face === 'front' ? cellU(c) === cellsU(c) - 1 : cellU(c) === 1;
      if (onStrap && j >= 2 && j <= 5) return j === 5 ? mul(BUCKLE, G(c, 0.05)) : mul(BUCK, G(c, 0.05));
    }
    return edge(c, col, { stitch: false });
  };
  B.push(box('body', [4, 7, -4.25], [5.5, 10.5, -2.75], sidePouch, { tag: 'side pouch' }));
  return { S: K.S, px: K.px, cell: K.cell, boxes: B };
};
