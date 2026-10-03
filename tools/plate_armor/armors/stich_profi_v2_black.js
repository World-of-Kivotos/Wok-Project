// ================= Stich Profi Plate Carrier V2 (Black) — EFT reference (tarkov.dev 66b6296d7994640992013b17) =================
// Read off the render (x<0 = wearer's right). In EFT this is an armored rig: the gear is part of the item model.
//   worn charcoal Cordura (a little warm) - the lightest thing on the rig, all the gear sits a shade darker; dark teal
//   lining under the padded shoulder straps; black plastic buckles on the straps' front ends just above the plate's top
//   corners; small dark gunmetal D-ring on a webbing tab at the plate's top centre, hanging over the first of three dark
//   loop-velcro rows (framed and split by the plain fabric) across the upper chest
//   front: three open-top rifle mag pouches (dark grey, two wide webbing straps each, black shock cord '><' over the
//   seams between the straps) holding tall dark-maroon polymer magazines with silver feed lips, toward the wearer's left
//   wearer's right of the bank: a fixed-blade knife standing handle-up (grey grip ~40% of its length, brass lanyard
//   eyelet by the pommel, strap with a snap) in a dark kydex sheath with two tan wraps at the throat and a small tan X
//   wearer's left of the bank: black radio in a lighter grey pouch, long dark whip antenna up to the loop field
//   wearer's right front corner (on the cummerbund): IFAK with a red cross on its lid and a black buckle on the outer
//   edge, black trauma-shear loops sticking out of it
//   under the plate: a wide drop pouch (zip along the top with navy pulls at both ends, square loop patch in its lower
//   half), narrowing toward the bottom.
//   NOT on the render: the back plate's outside (only its padded inside shows). The cummerbund's outer edge beside the
//   IFAK shows rows of horizontal slots, so the cummerbund sides and the back exterior are ASSUMED to be the same
//   laser-cut panel (common on carriers of this kind), plus a drag handle. The meta note says so.
ARMORS.stich_profi_v2_black = function (mode) {
  const K = kit('M');                              // always the 2x style
  const { edge, G, isPanel, isSide, px } = K;
  const B = [];
  const add = (a, b, mat, tag, opt = {}) => B.push(box('body', a, b, mat, { tag, ...opt }));
  const md = (v, m) => ((v % m) + m) % m;
  const flush = (mat, ...faces) => c => (faces.includes(c.face) ? null : mat(c));
  const iU = c => Math.floor(c.eu / px + 1e-6), iV = c => Math.floor(c.ev / px + 1e-6);
  const nU = c => Math.max(1, Math.round(c.fw / px)), nV = c => Math.max(1, Math.round(c.fh / px));

  // ---- palette (sampled off the render; the carrier fabric is the lightest thing on it, the gear sits darker)
  const BK_D = hex('#434142'), BK_L = hex('#555251');          // worn charcoal (a little warm): body / faint scuffs
  const TEAL = hex('#1d2d32'), LIN = hex('#141516');            // strap underside / pouch insides
  const LOOP = hex('#373636'), SLIT = hex('#161718');          // loop velcro (~0.75x the fabric) + laser cuts
  const POUCH = hex('#313335'), PSTRAP = hex('#3a3c3e'), CORD = hex('#18191b');
  const MAG = hex('#483236'), STEEL = hex('#6d7276'), BRASS = hex('#caa24e'), COPPER = hex('#b5703f');
  const GRIP = hex('#43474a'), KYDEX = hex('#33373a'), TANC = hex('#87775a'), EYE = hex('#a68f52');
  const RADIO = hex('#26282a'), RPOUCH = hex('#404245'), ANT = hex('#2b2d30');
  const IFAK = hex('#303234'), RED = hex('#b3333b'), SHEAR = hex('#1e2224');
  const DROP = hex('#34383a'), ZIP = hex('#1c1e20'), NAVY = hex('#343d68'), METAL = hex('#565a5c'), BUCK = hex('#2d2d2e');

  // worn charcoal fabric: very faint low-frequency scuffing (within a few percent) + per-cell grain
  const alb = (p, d = BK_D, l = BK_L) => {
    const q = snap(p, 0.5);
    const n = fbm(q[0] * 0.2 + 5, q[1] * 0.2 + 9, q[2] * 0.2 + 13, 2);
    return mix(d, l, 0.4 + 0.35 * (n - 0.5));
  };
  const bk = (c, k = 1) => mul(alb(c.p), k * G(c, 0.08));
  const lining = c => mul(LIN, G(c, 0.08));
  const teal = c => mul(TEAL, G(c, 0.08));
  // laser-cut slot rows (back plate, cummerbund): a slot row every 1.5 px, 1.5 px slots with 0.5 px bridges
  const laser = (c, col, y0, y1, h) => {
    const y = c.p[1];
    if (y < y0 || y >= y1) return null;
    if (md(y - y0, 1.5) >= 0.5) return null;
    return md(h, 2) < 1.5 ? mix(SLIT, col, 0.4) : mul(col, 1.08);
  };

  // ================= carrier =================
  const frontBag = c => {
    if (c.face === 'top') return c.ev > c.fh / 2 ? lining(c) : bk(c, 1.05);   // neckline: lining toward the body
    if (c.face === 'back') return lining(c);
    return edge(c, bk(c));
  };
  add([-3.75, 1, -3.25], [3.75, 10, -2.5], frontBag, 'front plate bag');
  const backBag = c => {
    if (c.face === 'top') return c.ev < c.fh / 2 ? lining(c) : bk(c, 1.05);
    if (c.face === 'front') return lining(c);
    const col = bk(c);
    if (c.face === 'back') return edge(c, laser(c, col, 2.5, 9.5, c.p[0] + 3) || col);
    return edge(c, col);
  };
  add([-3.75, 0.75, 2.5], [3.75, 10, 3.25], backBag, 'back plate bag');
  // cummerbund: loop-field flaps on its front ends beside the plate, laser-cut rows on the sides; ends 0.25 above the
  // plate bags' bottoms so the bottom faces never share a plane
  const cumm = c => {
    const col = mul(bk(c), 0.95);
    if (c.face === 'front') return edge(c, mul(LOOP, 0.92 * G(c, 0.12)), { stitch: false });
    if (isSide(c)) return edge(c, laser(c, col, 5.5, 9.5, c.p[2] + 2) || col, { stitch: false });
    return edge(c, col, { stitch: false });
  };
  add([-4.5, 4.5, -2.75], [4.5, 9.75, 2.75], cumm, 'cummerbund');
  add([-1.25, 0.25, 3.25], [1.25, 1.25, 3.75], c => edge(c, bk(c, 0.85), { stitch: false }), 'drag handle');

  // ================= padded shoulder straps =================
  // Only what shows outside the MC head: a rounded pad on top of each shoulder, and the front / back risers below the
  // chin with a black buckle on their front end, just above the plate's top corner. Dark teal lining underneath.
  const hump = c => {
    if (c.face === 'bottom') return teal(c);
    const col = bk(c, 1.04);
    if (isPanel(c)) return mul(col, 0.88);                                     // pad ends
    return c.face === 'top' ? mul(col, 1.08) : col;
  };
  const riser = c => {
    if (c.face === 'bottom' || (c.face === 'back' && c.box.z < 0) || (c.face === 'front' && c.box.z > 0)) return teal(c);
    return edge(c, bk(c, 1.04), { stitch: false });
  };
  // black plastic buckle lying on the strap's front end (small highlight on top, dark strap slot)
  const buckle = c => {
    if (c.face === 'back') return null;
    if (c.face === 'top') return mul(BUCK, 1.4);
    if (c.face !== 'front') return mul(BUCK, 0.8);
    const i = iU(c), j = iV(c), n = nU(c);
    if (j === 1 && i > 0 && i < n - 1) return mul(BUCK, 0.6);                   // the strap slot
    return mul(BUCK, j === 0 ? 1.2 : 1.1);
  };
  for (const s of [-1, 1]) {
    const X = (a, b) => (s < 0 ? [a, b] : [-b, -a]);
    const [b0, b1] = X(-5.75, -4.25), [h0, h1] = X(-5.5, -4.5);
    add([b0, -1.25, -3.5], [b1, -0.25, 3.5], hump, 'shoulder pad');
    add([h0, -1.75, -3.25], [h1, -0.75, 3.25], hump, 'shoulder pad crown');
    const [r0, r1] = X(-4.25, -1.75), [k0, k1] = X(-4.25, -1.75);
    add([r0, -0.25, -3.75], [r1, 1, -2.5], riser, 'strap front');
    add([k0, -0.25, 2.75], [k1, 1.5, 3.5], riser, 'strap back');
    const [u0, u1] = X(-3.75, -2.25);
    add([u0, 0, -4], [u1, 1, -3.75], buckle, 'strap buckle');                  // lies wholly on the riser front
  }

  // ================= upper chest: loop field with laser cuts, D-ring on a webbing tab =================
  // 11 x 5 cells: three calm rows of dark loop velcro inside a frame of the plain plate fabric (one fabric-coloured
  // column at each end, the two rows between the velcro rows a shade lighter than the plate); a vertical seam every 2 px
  const field = c => {
    if (c.face === 'top') return bk(c, 1.05);
    if (c.face !== 'front') return mul(bk(c), 0.8);
    const i = iU(c), j = iV(c), n = nU(c);
    if (i === 0 || i === n - 1 || j % 2 === 1) return bk(c, 1.05);             // frame + separators: plain fabric
    const loop = mul(LOOP, G(c, 0.05));
    return i === 3 || i === n - 4 ? mul(loop, 0.85) : loop;                    // seams between the velcro blocks
  };
  add([-2.75, 1.25, -3.5], [2.75, 3.75, -3.25], flush(field, 'back'), 'loop field');
  add([-0.5, 0.75, -3.75], [0.5, 1.25, -3.25], c => edge(c, bk(c, 0.92), { stitch: false }), 'D-ring tab');
  // small dark gunmetal D-ring hanging from the tab over the first velcro row: legs + rounded bottom bar, the tab
  // closing it on top (3 x 2 cells, centre open). Legs catch the light over the dark velcro, the bar under them is in
  // shade, so it reads against the lighter fabric strip it lies on.
  const DP = ['#.#', '###'];
  add([-0.75, 1.25, -3.75], [0.75, 2.25, -3.5], c => {
    if (!isPanel(c)) return null;
    if (c.face === 'back') return null;
    const i = Math.min(2, Math.floor(c.eu / c.fw * 3)), j = Math.min(1, Math.floor(c.ev / c.fh * 2));
    return DP[j][i] === '#' ? mul(METAL, j === 0 ? 0.9 : 0.7) : null;
  }, 'D-ring');

  // ================= mag bank: three open-top pouches with maroon polymer mags =================
  const pouch = c => {
    if (c.face === 'top') return c.ex < px ? mul(POUCH, 1.2) : lining(c);    // open mouth: light rim, dark inside
    if (c.face === 'back') return lining(c);
    let col = mul(POUCH, G(c, 0.06));
    if (c.face === 'front') {
      const i = iU(c), n = nU(c);
      if (iV(c) === 0) return mul(col, 1.18);                                   // elastic top binding
      return i === 0 || i === n - 1 ? mul(col, 0.8) : col;                      // seam

    }
    return edge(c, mul(col, 0.9), { stitch: false });
  };
  // each pouch: two wide webbing straps standing a little proud, the dark seam showing between neighbours
  const band = c => {
    if (c.face === 'back') return null;
    const col = mul(PSTRAP, G(c, 0.05));
    if (c.face === 'top') return mul(col, 1.2);
    if (c.face === 'bottom') return mul(col, 0.6);
    if (!isPanel(c)) return mul(col, 0.75);
    return mul(col, iV(c) === 0 ? 1.1 : 0.9);
  };
  // magazines: broad side forward, darker spine, one soft rib; feed lips + one brass round on top; 2 px down in the pouch
  const mag = c => {
    if (c.face === 'bottom') return mul(MAG, 0.5);
    if (c.face === 'top') return mul(hex('#2b2d30'), c.ex < px ? 1.4 : 1);
    let col = mul(MAG, G(c, 0.05));
    if (!isPanel(c)) return mul(col, 0.72);                                    // spine
    if (c.ev < px) return mul(col, 1.28);                                      // lit top edge under the lips
    if (iU(c) === 1) col = mul(col, 0.85);                                     // rib
    return col;
  };
  const lips = c => {
    if (c.face !== 'top') return mul(STEEL, isPanel(c) ? 1.08 : 0.85);
    return c.eu > c.fw - px ? COPPER : BRASS;
  };
  // as on the render: upper strap right under the mouth, lower strap a little below the middle, plain pouch body under it
  const CX = [-1.75, 0.25, 2.25];
  for (const cx of CX) {
    add([cx - 1, 6.5, -4.5], [cx + 1, 10, -3.25], pouch, 'mag pouch');
    add([cx - 0.75, 4.5, -4.25], [cx + 0.75, 8.5, -3.25], mag, 'magazine');
    add([cx - 0.5, 4, -4], [cx + 0.5, 4.5, -3.5], lips, 'magazine feed lips');
    add([cx - 0.75, 6.75, -4.75], [cx + 0.75, 7.5, -4.5], band, 'pouch strap (upper)');
    add([cx - 0.75, 8.25, -4.75], [cx + 0.75, 9, -4.5], band, 'pouch strap (lower)');
  }
  // black shock cord ('><' on the render): an X over each inner seam between the straps and another under the lower
  // strap, half crosses ('<' / '>') on the bank's outer edges. Thin cords lying on the pouch fronts, kept clear of the
  // straps so they never share the straps' front plane.
  const scord = c => (c.face === 'back' ? null : mul(CORD, c.face === 'front' ? 1.15 * G(c, 0.05) : 0.9));
  for (const yc of [7.875, 9.5]) {
    for (const sx of [-0.75, 1.25]) for (const s of [-1, 1]) add([sx - 0.125, yc - 0.375, -4.75], [sx + 0.125, yc + 0.375, -4.5], scord, 'shock cord', { rot: [0, 0, s * 40], pivot: [sx, yc, -4.625] });
  }
  // half crosses on the outer edges, under the lower strap (the only gap tall enough for one): two short arms meeting
  // at the edge. rot > 0 tilts a cord to '/' seen from the front.
  for (const [ax, dir] of [[-2.49, 1], [2.99, -1]]) for (const up of [-1, 1]) {
    const cx = ax, cy = 9.5 + up * 0.19;
    add([cx - 0.125, cy - 0.25, -4.75], [cx + 0.125, cy + 0.25, -4.5], scord, 'shock cord (half cross)', { rot: [0, 0, -dir * up * 40], pivot: [cx, cy, -4.625] });
  }
  // short black bungee pull tabs standing up at the pouch mouths between the mags (and at the bank's outer corners)
  const tab = c => {
    if (c.face === 'back') return null;
    if (c.face === 'top') return mul(CORD, 1.6);
    return mul(CORD, c.face === 'front' ? (c.ev < px ? 1.35 : 1.1) * G(c, 0.05) : 0.85);
  };
  for (const tx of [-2.625, -0.75, 1.25, 3.125]) add([tx - 0.125, 6, -4.75], [tx + 0.125, 6.75, -4.5], tab, 'bungee tab');

  // ================= knife on the wearer's right of the bank =================
  // grip ~40% of the knife's length, as on the render
  const handle = c => {
    if (c.face === 'top') return mul(GRIP, 1.25);
    let col = mul(GRIP, G(c, 0.1));
    if (!isPanel(c)) return mul(col, 0.8);
    if (c.face === 'front' && iV(c) === 0 && iU(c) === 0) return mul(EYE, G(c, 0.05));   // brass lanyard eyelet by the pommel
    return iV(c) === 0 ? mul(col, 1.12) : col;
  };
  add([-3.625, 2.5, -3.75], [-2.875, 5.25, -3.25], handle, 'knife handle');
  add([-3.75, 4.5, -4], [-2.75, 5, -3.25], c => {
    if (c.face === 'back') return null;
    const col = mul(hex('#2a2c2e'), G(c, 0.06));
    if (c.face === 'front' && iU(c) === 1) return mul(hex('#131415'), 1);     // snap
    return c.face === 'top' ? mul(col, 1.3) : col;
  }, 'knife retention strap');
  const sheath = c => {
    if (c.face === 'back') return lining(c);
    if (c.face === 'top') return mul(KYDEX, 1.2);
    let col = mul(KYDEX, G(c, 0.06));
    if (!isPanel(c)) return mul(col, 0.8);
    if (c.face === 'front' && (iV(c) === 1 || iV(c) === 3)) return mul(TANC, G(c, 0.06));   // two thin wraps at the throat
    return iV(c) === nV(c) - 1 ? mul(col, 0.82) : col;
  };
  add([-3.75, 5.25, -3.75], [-2.75, 10, -3.25], sheath, 'knife sheath');
  // small tan cord X in the lower half of the sheath (two short thin cords standing on the sheath front)
  const cord = c => (c.face === 'back' ? null : mul(TANC, c.face === 'front' ? G(c, 0.06) : 0.8));
  for (const s of [-1, 1]) add([-3.375, 8.375, -4], [-3.125, 9.625, -3.75], cord, 'sheath lashing', { rot: [0, 0, s * 24], pivot: [-3.25, 9, -3.875] });

  // ================= radio on the wearer's left of the bank =================
  const rpouch = c => {
    if (c.face === 'top') return c.ex < px ? mul(RPOUCH, 1.2) : lining(c);
    if (c.face === 'back') return lining(c);
    const col = mul(RPOUCH, G(c, 0.06));
    if (c.face === 'front' && c.ev < px) return mul(col, 1.15);
    return edge(c, col, { stitch: false });
  };
  add([3.25, 7.5, -4.25], [4.75, 9.75, -2.75], rpouch, 'radio pouch');
  add([3.5, 6, -4], [4.5, 9.5, -3], c => {
    if (c.face === 'top') return mul(RADIO, 1.7);
    if (c.face === 'bottom') return mul(RADIO, 0.8);
    const col = mul(RADIO, G(c, 0.05));
    if (c.face !== 'front') return mul(col, 0.95);
    if (iV(c) === 0) return mul(col, 1.5);
    if (iV(c) === 2) return mul(hex('#55585b'), iU(c) === 0 ? 1 : 0.85);     // buttons
    return col;
  }, 'radio');
  add([3.75, 2.25, -3.75], [4.25, 6, -3.25], c => {
    if (c.face === 'top') return mul(ANT, 1.5);
    const col = mul(ANT, G(c, 0.05));
    if (!isPanel(c)) return mul(col, 0.8);
    if (c.p[1] > 5.25) return mul(col, 0.85);                                  // thicker base
    return c.p[1] < 2.75 ? mul(col, 1.25) : col;                               // lit tip
  }, 'radio antenna');

  // ================= IFAK on the wearer's right front corner =================
  // red cross straight on the grey-black lid; black side-release buckle below it on the outer edge (i 0 = wearer's right)
  const CROSS = ['...', '.R.', 'RRR', '.R.'];
  const ifak = c => {
    if (c.face === 'back') return lining(c);
    const col = mul(IFAK, G(c, 0.07));
    if (c.face === 'top') return mul(col, 1.15);
    if (c.face === 'front') {
      const i = iU(c), j = iV(c);
      if (j < CROSS.length && CROSS[j][i] === 'R') return mul(RED, G(c, 0.04));
      if (i === 0 && j >= 5 && j <= 7) return mul(col, j === 5 ? 0.9 : 0.5);    // buckle (lit top edge; the column around it is edge-shaded x0.72)
      return edge(c, col, { stitch: false });
    }
    return edge(c, mul(col, 0.92), { stitch: false });
  };
  // (a strip of the cummerbund's loop flap shows between it and the knife, as on the original)
  add([-5.5, 5.75, -4], [-4, 10.25, -2.75], ifak, 'IFAK');
  // trauma-shear loops sticking out of the IFAK behind its front
  const RING = ['###', '#.#', '###'];
  add([-5.375, 4.75, -3.5], [-4.125, 6.25, -3.25], c => {
    if (!isPanel(c)) return null;
    if (c.face === 'back') return null;
    const i = Math.min(2, Math.floor(c.eu / c.fw * 3)), j = Math.min(2, Math.floor(c.ev / c.fh * 3));
    return RING[j][i] === '#' ? mul(SHEAR, j === 0 ? 1.3 : 1) : null;
  }, 'trauma shears');

  // ================= drop pouch under the plate =================
  const drop = c => {
    if (c.face === 'back') return lining(c);
    const col = mul(alb(c.p, mul(DROP, 0.9), mul(DROP, 1.15)), G(c, 0.06));
    if (c.face === 'top') return mul(col, 1.1);
    if (c.face === 'bottom') return mul(col, 0.6);
    if (c.face === 'front' && c.box.y < 10.5) {
      const j = iV(c);
      if (j === 0) return mul(ZIP, G(c, 0.06));                                // zip along the top
      if (j === 1) return mul(col, 1.12);                                      // lid edge
    }
    return edge(c, col, { stitch: false });
  };
  // its back on the plate bag's back plane, right down against the hips / thighs (no daylight behind it from the side)
  add([-3.5, 10, -4.25], [3.5, 14, -2.5], drop, 'drop pouch');
  add([-3, 14, -4], [3, 15, -2.5], drop, 'drop pouch (bottom)');
  const patch = c => {
    if (c.face === 'back') return null;
    const col = mul(hex('#26292b'), G(c, 0.12));
    if (!isPanel(c)) return mul(col, 0.8);
    return c.ex < px ? mul(col, 1.25) : col;                                    // stitched border
  };
  // square loop patch in the lower half, a little toward the wearer's left as on the render
  add([-0.75, 11.75, -4.5], [1.75, 14, -4.25], patch, 'loop patch');
  for (const x0 of [-3, 2.5]) add([x0, 10, -4.5], [x0 + 0.5, 11.5, -4.25], c => (c.face === 'back' ? null : mul(NAVY, c.face === 'top' ? 1.3 : isPanel(c) ? (c.ev > 1 ? 1.15 : 1) : 0.8)), 'zip pull');

  return { S: K.S, px: K.px, cell: K.cell, boxes: B };
};
