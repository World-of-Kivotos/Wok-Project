// ================= Tasmanian Tiger Plate Carrier MKIII (Coyote) — EFT reference (tarkov.dev 66b6295a8ca68c6461709efa) =================
// The render is seen from the wearer's right front (the dark mesh left of the plate bag is the inside of the back bag,
// seen through the armhole). In EFT the gear is part of the item model. Read off the render (x<0 = wearer's right):
//   carrier: warm coyote Cordura with a lit, rounded top edge; three MOLLE rows of PALER, greyer beige webbing across the
//     upper chest (each with a dark shadow line under it); the middle of the top two rows is covered by a recessed
//     mustard loop (velcro) field, DARKER than the lit coyote round it, offset toward the wearer's left
//   proportions: bag top to flap top ~4.5 px; the flap pouches are ~2.5:1 tall, the wide pouch a little taller than
//     wide; the placard's band ends below the belt line, the bottom tab and a strap end hang under it
//   straps: thick padded shoulder pads (lighter, yellower coyote) with a pale loop patch and a light webbing strip on
//     top, taupe webbing straps running down into the bags; near-black lining underneath
//   upper chest (wearer's left): navy-grey PTT on the loop field, its cord arcing to a coiled cord that runs down the
//     bag's edge to the radio; black trauma shears (loop handle) hooked into the lowest MOLLE row
//   lower front: a placard (lit rim on top, dark olive webbing band along the bottom, a lighter coyote tab under it);
//     two flap pouches side by side (lit coyote fold on top, olive-khaki loop faces with a big X stitch, rounded bottom
//     tabs) and a wide open pouch (loop face with a big X stitch) with a dark olive ribbed inner sleeve rising out of it
//     (no magazines are visible in the render)
//   wearer's left corner: CAT tourniquet (black, grey strap tab and red tag on top, windlass rod, tan retaining bands)
//     in front of a radio pouch; black radio with a long whip antenna running up the bag's edge beside the coiled cord
//   wearer's right corner: olive M18 smoke grenade (pull ring on the fuse) held by two thin grey elastic loops; black
//     carabiner (red detail) through a bright blue tape roll (white inner wall, dark hole); a coyote strap tab dangling
//     under the bag
ARMORS.tt_mkiii_coyote = function (mode) {
  const K = kit('M');                          // always the 2x style
  const { edge, G, isPanel, isSide } = K;
  const cw = K.cell;                           // one art cell (0.5 px)
  const B = [];
  const add = (a, b, mat, tag, opt = {}) => B.push(box('body', a, b, mat, { tag, ...opt }));
  const md = (v, m) => ((v % m) + m) % m;
  const X = (s, a, b) => (s < 0 ? [a, b] : [-b, -a]);   // x-range on the wearer's right (s<0) or mirrored to the left

  // ---------- palette (sampled off the render's lit / mid faces) ----------
  const FAB = hex('#ad8f5f');                  // warm coyote carrier
  const WEB = hex('#b2a58c');                  // MOLLE webbing: a little lighter than the fabric and clearly greyer
  const LOOP = hex('#956f42');                // recessed mustard loop field on the chest: darker than the lit coyote, more saturated
  const PAD = hex('#bea372'), PATCH = hex('#d6c5a2'), PWEB = hex('#e2d7bc');
  const STRAP = hex('#8a7662'), LINING = hex('#1a1d1f');
  const PLOOP = hex('#8a7b55');                // loop-faced pouch fronts (darker and a little greener than the carrier)
  const TAB = hex('#93805c');                  // rounded flap tabs
  const BAND = hex('#4b4c3d');                 // dark olive webbing band under the pouches
  const CWEB = hex('#77725d');                 // cummerbund elastic rows (grey-olive)
  const SLEEVE = hex('#4a4a39');               // dark olive inner sleeve of the wide pouch
  const PTT = hex('#474b59'), CORD = hex('#2d3243'), BLACK = hex('#26272a');
  const TQB = hex('#2c2c2f'), TQTAB = hex('#8e9298'), TQRED = hex('#b8352c'), TQBAND = hex('#a88f68');
  const RADIO = hex('#2b2d31');
  const SMOKE = hex('#4b4e30'), SMOKETOP = hex('#2a2b27'), ELASTIC = hex('#8b8d85'), LABEL = hex('#d9d5c2'), RING = hex('#a3a49c');
  const TAPE = hex('#2f47cc'), CORE = hex('#d5d8de'), HOLE = hex('#1c1d20'), RED = hex('#c0322b');

  // Cordura: faint low-frequency mottling + per-cell grain
  const fab = (c, base, amt = 0.06) => {
    const q = snap(c.p, cw);
    const n = fbm(q[0] * 0.45 + 7, q[1] * 0.45 + 1, q[2] * 0.45 + 3);
    return mul(base, (0.97 + 0.06 * n) * G(c, amt));
  };
  // MOLLE rows: 1 px webbing (lit top cell, plainer lower cell) + one shadow cell of the fabric under it, period 1.5;
  // a slightly darker bar-tack column every 1.5 px (h = horizontal coordinate along the face). null outside the rows.
  const rows = (c, col, y0, n, h, tape = WEB, shadow = 0.78) => {
    const dy = c.p[1] - y0;
    if (dy < 0) return null;
    const i = Math.floor(dy / 1.5);
    if (i >= n) return null;
    const r = dy - i * 1.5;
    if (r >= 1) return mul(col, shadow);
    let t = mul(tape, G(c, 0.04));
    if (md(h, 1.5) >= 1) t = mul(t, 0.9);
    return mul(t, r < cw ? 1.05 : 0.95);
  };
  const plastic = col => c => mul(col, c.face === 'top' ? 1.45 : c.face === 'bottom' ? 0.7 : isPanel(c) ? (c.ev < cw ? 1.25 : 1) : 0.85);

  // ================= carrier =================
  const frontBag = c => {
    if (c.face === 'back') return mul(LINING, 1.2);
    if (c.face === 'top') return mul(fab(c, FAB), 1.12);                         // lit rounded top edge
    const col = fab(c, FAB);
    if (c.face === 'front') {
      const x = c.p[0], y = c.p[1];
      // recessed mustard loop field over the top two rows, offset toward the wearer's left; the rows only show as a faint
      // seam under each of them (no lit top cell: the field sits below the webbing, it is not lit)
      if (x >= -1.5 && x < 3 && y >= 1.75 && y < 4.25) {
        const r = md(y - 1.75, 1.5);
        return mul(fab(c, LOOP, 0.05), r >= 1 ? 0.88 : 1);
      }
      const m = rows(c, col, 1.75, 3, x + 4.25);
      if (m) return edge(c, m);
    }
    return edge(c, col);
  };
  add([-4.25, 1.25, -3.25], [4.25, 11.5, -2.5], frontBag, 'front plate bag');
  // back plate bag: the same webbing rows (the render only shows the front)
  const backBag = c => {
    if (c.face === 'front') return mul(LINING, 1.2);
    if (c.face === 'top') return mul(fab(c, FAB), 1.1);
    const col = fab(c, FAB);
    return edge(c, (c.face === 'back' && rows(c, col, 2.25, 6, 4.25 - c.p[0], WEB, 0.86)) || col);
  };
  add([-4.25, 0.75, 2.5], [4.25, 11.5, 3.25], backBag, 'back plate bag');
  add([-1.25, 0.25, 3.25], [1.25, 1.25, 3.75], c => edge(c, fab(c, STRAP), { stitch: false }), 'drag handle');
  // cummerbund: grey-olive elastic rows round the sides
  const cumm = c => {
    const col = mul(fab(c, FAB), 0.92);
    if (isSide(c) && Math.abs(c.p[2]) <= 2.25) { const m = rows(c, col, 5.5, 4, c.p[2] + 2.25, CWEB, 0.8); if (m) return m; }
    return edge(c, col, { stitch: false });
  };
  add([-4.5, 5, -2.75], [4.5, 11.25, 2.75], cumm, 'cummerbund');

  // ---------- shoulder straps: thick light pads on the shoulders (pale loop patch + webbing strip on top), taupe webbing
  // straps running down into the bags ----------
  const hump = s => c => {
    if (c.face === 'bottom') return mul(LINING, 1.2);
    const col = fab(c, PAD, 0.07);
    if (c.face === (s < 0 ? 'left' : 'right')) return mul(mix(col, LINING, 0.5), 0.9);   // lining side toward the neck
    if (isPanel(c)) return edge(c, mul(col, 0.92), { stitch: false });                        // pad ends
    return c.face === 'top' ? mul(col, 1.06) : edge(c, col, { stitch: false });
  };
  const crown = s => c => {
    if (c.face === 'bottom') return mul(LINING, 1.2);
    const col = fab(c, PAD, 0.07);
    if (c.face === 'top') {
      const z = c.p[2];
      if (z < -1.75 && z >= -2.25) return mul(PWEB, G(c, 0.05));                 // light webbing strip across the patch
      if (z < 0.25) return mul(fab(c, PATCH, 0.05), 1.0);                         // pale loop patch on the front half
      return mul(col, 1.08);
    }
    if (c.face === (s < 0 ? 'left' : 'right')) return mul(mix(col, LINING, 0.5), 0.9);
    if (isPanel(c)) return edge(c, mul(col, 0.95), { stitch: false });
    return edge(c, col, { stitch: false });
  };
  const riser = front => c => {
    if (c.face === 'bottom' || c.face === (front ? 'back' : 'front')) return mul(LINING, 1.2);
    return edge(c, fab(c, STRAP, 0.07), { stitch: false });
  };
  for (const s of [-1, 1]) {
    const [b0, b1] = X(s, -5.75, -4), [h0, h1] = X(s, -5.5, -4.5), [r0, r1] = X(s, -4, -2.5);
    add([b0, -1.25, -3.5], [b1, -0.25, 3.5], hump(s), 'shoulder pad');
    add([h0, -1.75, -3.25], [h1, -0.75, 3.25], crown(s), 'shoulder pad crown');
    add([r0, -0.25, -3.5], [r1, 1.5, -3], riser(true), 'strap front');
    add([r0, -0.25, 3], [r1, 2, 3.5], riser(false), 'strap back');
  }

  // ================= upper chest gear (wearer's left) =================
  // PTT on the loop field: lit top row, a darker fine-grille face (one even tone) between lighter rounded sides
  const ptt = c => {
    if (c.face === 'front') {
      if (c.ev < cw) return mul(PTT, 1.2);
      const i = Math.floor(c.eu / cw);
      return mul(PTT, (i === 0 || i === 3 ? 0.95 : 0.78) * G(c, 0.04));
    }
    return mul(PTT, c.face === 'top' ? 1.3 : c.face === 'bottom' ? 0.6 : 0.82);
  };
  add([-0.25, 1.25, -4], [1.75, 2.75, -3.25], ptt, 'PTT');
  // cord arcing from the PTT's upper corner down to the coiled cord
  add([1.75, 1.5, -3.75], [3.75, 2, -3.25], c => mul(CORD, c.face === 'top' ? 1.25 : G(c, 0.05)), 'PTT cord', { rot: [0, 0, 30], pivot: [1.75, 1.75, -3.5] });
  // coiled cord down the bag's edge to the radio (behind the tourniquet); soft 1 px coils
  add([3.5, 2.25, -3.75], [4, 5.75, -3.25], c => mul(CORD, (isPanel(c) || isSide(c)) ? (md(c.p[1], 1) < cw ? 1.12 : 0.9) : 1.2), 'coiled PTT cord');
  // trauma shears: loop handle standing on the bag, blades tucked down into the placard top
  const shearsRing = c => {
    if (c.face === 'back') return null;
    if (isPanel(c)) {
      const i = Math.floor(c.eu / cw), j = Math.floor(c.ev / cw);
      if (i === 1 && j === 1) return null;                                      // the finger hole
      return mul(BLACK, j === 0 ? 1.25 : 1);
    }
    return mul(BLACK, 0.8);
  };
  add([1.75, 3.75, -3.5], [3.25, 5.25, -3.25], shearsRing, 'shears handle');
  add([2.5, 5, -4], [3, 6, -3.5], c => mul(BLACK, c.face === 'top' ? 1.3 : isPanel(c) ? G(c, 0.05) : 0.8), 'shears body');

  // ================= front placard + pouches =================
  const placard = c => {
    if (c.face === 'back') return null;                                        // flush on the bag
    const col = fab(c, FAB);
    if (c.face === 'top') return mul(col, 1.14);                               // lit rim
    if (c.face === 'bottom') return mul(BAND, 0.7);
    if (c.p[1] >= 10.75) return mul(BAND, (isPanel(c) ? (md(c.p[0] + 3.75, 1.5) < cw ? 1.12 : 1) : 0.82) * G(c, 0.05));   // dark webbing band
    if (!isPanel(c)) return mul(col, 0.85);
    // the cell column behind the 0.25 px gap between the two flap pouches: a dark groove
    if (c.face === 'front' && c.p[0] > -2 && c.p[0] < -1.5) return mul(col, 0.42);
    if (c.ev < cw) return mul(col, 1.1);
    if (c.eu < cw || c.eu > c.fw - cw) return mul(col, 0.88);
    return col;
  };
  add([-4, 5.75, -3.75], [3.75, 11.5, -3.25], placard, 'placard');
  // lighter coyote tab under the placard (the bag's bottom flap)
  add([-1.5, 11.5, -3.75], [3.5, 12.5, -3.25], c => {
    if (c.face === 'back') return mul(LINING, 1.2);
    return edge(c, mul(fab(c, FAB), c.face === 'bottom' ? 0.7 : 1.02), { stitch: false });
  }, 'bottom tab');
  // the big X stitch all three pouches carry, on an n x n block of cells (same stroke everywhere)
  const XST = 0.86;
  // corner-to-corner X on an n-column x m-row block of cells: 45-degree strokes crossing in the middle, the extra rows of
  // a taller block run straight down into the corners (a stretched X instead of a dark bar down the middle)
  const onX = (i, j, n, m = n) => {
    const k = Math.max(0, Math.min(n - 1, j - (m - n) / 2));
    return i === k || i === n - 1 - k;
  };
  // two tall flap pouches with a 0.25 px groove between them (dark placard behind): lit rounded fold on top, loop face
  // with a tall X stitch, rounded bottom tab (4 x 10 cells)
  const flapPouch = c => {
    if (c.face === 'back') return mul(LINING, 1.2);
    const col = fab(c, FAB);
    if (c.face === 'top') return mul(col, 1.16);                               // the flap folding over the top
    if (c.face === 'bottom') return mul(col, 0.6);
    if (!isPanel(c)) return mul(col, 0.72);                                    // dark sides: the groove between the flaps
    const i = Math.min(3, Math.floor(c.eu / cw)), j = Math.min(9, Math.floor(c.ev / cw));
    const rim = i === 0 || i === 3;
    if (j === 0) return mul(col, rim ? 0.8 : 1.1);                             // rounded fold, its corners dark so the two flaps split
    if (j === 9) return rim ? mul(PLOOP, 0.55) : mul(TAB, 0.9 * G(c, 0.04));   // rounded tab corners
    if (j === 8) return mul(TAB, G(c, 0.04));
    let l = mul(PLOOP, G(c, 0.03));                                            // loop: flat, almost no grain
    if (j === 1) l = mul(l, 0.84);                                             // shadow under the fold
    else if (onX(i, j - 2, 4, 6)) l = mul(l, XST);                             // tall X stitch over j 2..7
    return l;
  };
  for (const x0 of [-3.75, -1.5]) add([x0, 5.75, -4.5], [x0 + 2, 10.75, -3.75], flapPouch, 'flap pouch');
  // wide open pouch (a little taller than wide): lit top binding, loop face with a big X stitch below it; the top is an
  // open mouth (light rim, dark inside)
  const wide = c => {
    if (c.face === 'back') return mul(LINING, 1.2);
    if (c.face === 'top') return c.ex < cw ? mul(FAB, 1.12) : mul(LINING, 1.3);
    const col = fab(c, FAB);
    if (c.face === 'bottom') return mul(col, 0.6);
    if (!isPanel(c)) return mul(col, 0.8);
    const i = Math.floor(c.eu / cw), j = Math.floor(c.ev / cw), n = Math.round(c.fw / cw);
    let l = mul(PLOOP, G(c, 0.03));
    if (j === 0) return mul(l, 1.1);                                           // lit top binding
    if (onX(i, j - 1, n)) l = mul(l, XST);                                     // X stitch on the n x n cells below it
    return l;
  };
  add([0.5, 7.25, -4.75], [3.5, 10.75, -3.75], wide, 'wide pouch');
  // dark olive ribbed inner sleeve rising out of the wide pouch (1 px down inside it)
  const sleeve = c => {
    if (c.face === 'top') return c.ex < cw ? mul(SLEEVE, 1.35) : mul(LINING, 1.2);
    if (c.face === 'bottom') return mul(SLEEVE, 0.6);
    let col = mul(SLEEVE, G(c, 0.04));
    if (!isPanel(c)) return mul(col, 0.75);
    if (c.ev < cw) return mul(col, 1.3);                                       // lit top edge
    if (Math.floor(c.eu / cw) === 2) col = mul(col, 0.8);                      // middle rib
    return col;
  };
  add([0.75, 6, -4.5], [3.25, 8.25, -4], sleeve, 'inner sleeve');

  // ================= wearer's left corner: radio pouch, radio + whip antenna, tourniquet =================
  const radioPouch = c => {
    if (c.face === 'top') return c.ex < cw ? mul(FAB, 1.1) : mul(LINING, 1.2);
    if (c.face === 'back') return mul(LINING, 1.1);
    return edge(c, fab(c, mul(FAB, 0.92)), { stitch: false });
  };
  add([3.75, 7, -3.75], [5, 11.25, -2.75], radioPouch, 'radio pouch');
  add([3.875, 5.75, -3.625], [4.875, 8.25, -2.875], plastic(RADIO), 'radio');           // 1.25 px down in its pouch
  // whip antenna: up along the bag's front edge, against the coiled cord, its foot on the radio
  add([4, 0.5, -3.75], [4.5, 5.75, -3.25], c => mul(RADIO, c.face === 'top' ? 1.5 : isPanel(c) ? (c.p[1] < 1 ? 1.25 : 0.95) : 0.75), 'whip antenna');
  // CAT tourniquet in front of the wide pouch's corner and the radio pouch: grey strap tab + red tag on top, tan
  // retaining bands, black windlass rod across its face
  const tq = c => {
    const x = c.p[0], y = c.p[1];
    if (c.face === 'back') return mul(TQB, 0.7);
    if (y < 7.75) {
      if (x >= 4.25) return mul(TQRED, c.face === 'top' ? 1.2 : isPanel(c) ? 1 : 0.8);
      return mul(TQTAB, (c.face === 'top' ? 1.2 : isPanel(c) ? (c.ev < cw ? 1.15 : 1) : 0.8) * G(c, 0.05));
    }
    if ((y >= 8.25 && y < 8.75) || (y >= 10 && y < 10.5)) return mul(TQBAND, isPanel(c) ? G(c, 0.05) : 0.82);
    return mul(TQB, (c.face === 'top' ? 1.3 : c.face === 'bottom' ? 0.7 : isPanel(c) ? 1 : 0.8) * G(c, 0.06));
  };
  add([3.25, 6.75, -5], [4.75, 11, -3.75], tq, 'tourniquet');
  add([3.75, 7.25, -5.25], [4.25, 10.75, -5], c => mul(hex('#3b3c40'), c.face === 'top' || c.face === 'bottom' ? 1.3 : isPanel(c) ? G(c, 0.04) : 0.8), 'TQ windlass rod', { rot: [0, 0, 15], pivot: [4, 9, -5] });

  // ================= wearer's right corner: M18 smoke, carabiner + tape roll, dangling strap =================
  const smoke = c => {
    const y = c.p[1];
    if (c.face === 'top') return mul(SMOKE, 1.18);
    if (c.face === 'bottom') return mul(SMOKE, 0.6);
    // two thin grey elastic loops with one cell of olive between them (rows y 6.75..7.25 and 7.75..8.25)
    if ((y >= 6.75 && y < 7.25) || (y >= 7.75 && y < 8.25)) return mul(ELASTIC, (y < 7.5 ? 1.05 : 0.95) * (isPanel(c) ? 1 : 0.85) * G(c, 0.05));
    let col = mul(SMOKE, G(c, 0.06));
    if (y < 6.25) col = mul(col, 1.1);                                          // lit top rim
    if (c.face === 'front' && !c.box.rot && y >= 6.5 && y < 7 && Math.abs(c.eu - c.fw / 2) < 0.5) col = mix(col, LABEL, 0.4);   // "M18" lettering
    if (c.face === 'front' && !c.box.rot && y >= 8.5 && y < 9 && c.eu > cw && c.eu < c.fw - cw) col = mix(col, LABEL, 0.3);   // lower lettering
    return col;
  };
  // kept close to the carrier's corner (the arm column starts right outside it); the turned copy sinks into the
  // bag / flap-pouch corner
  octa(B, 'body', [-4.75, 7.25, -3.5], [1.5, 3, 1.5], 'y', smoke, { tag: 'smoke grenade' });
  add([-5, 5.25, -3.75], [-4.5, 5.75, -3.25], c => mul(SMOKETOP, c.face === 'top' ? 1.4 : isPanel(c) ? 1.1 : 0.9), 'smoke fuse');
  // pull ring: a small flat metal ring on the wearer's-left side of the fuse, resting on the grenade's top
  add([-4.5, 5.25, -3.75], [-4, 5.75, -3.5], c => (c.face === 'back' ? null : mul(RING, c.face === 'top' ? 1.25 : isPanel(c) ? 1 : 0.8)), 'smoke pull ring');
  // carabiner: a black hook hanging beside the smoke (between it and the first flap pouch, in front of the placard's
  // edge) with a red band midway; its lower end crosses the tape roll's top rim into the core
  add([-4.25, 8.25, -4.125], [-3.75, 10.5, -3.625], c => {
    const y = c.p[1];
    if (y >= 9.25 && y < 9.75) return mul(RED, isPanel(c) ? 1 : 0.8);
    return mul(BLACK, c.face === 'top' ? 1.4 : isPanel(c) ? (y < 8.75 ? 1.25 : 1) : 0.8);
  }, 'carabiner');
  // tape roll: 4 x 4 cells with the corner cells cut away (round); the 2 x 2 core round the hole: lit white inner wall
  // on top, dark hole below, the carabiner's black body running down its column; the cut corners run through the
  // whole depth, so the side faces lose their corner cells too
  const tape = c => {
    const i = Math.min(3, Math.floor((c.p[0] + 5.25) / cw)), j = Math.min(3, Math.floor((c.p[1] - 10) / cw));
    const ci = i === 0 || i === 3, cj = j === 0 || j === 3;
    if (ci && cj) return null;
    if (c.face === 'back') return mul(TAPE, 0.6);
    if (c.face !== 'front') return mul(TAPE, c.face === 'top' ? 1.15 : c.face === 'bottom' ? 0.6 : 0.8);
    if (!ci && !cj) {
      if (i === 2) return mul(BLACK, j === 1 ? 1.1 : 0.95);                    // the carabiner through the hole
      return j === 1 ? CORE : HOLE;                                            // lit inner wall over the dark hole
    }
    return mul(TAPE, (j === 0 ? 1.15 : 1) * G(c, 0.05));
  };
  add([-5.25, 10, -4], [-3.25, 12, -3.25], tape, 'tape roll');
  add([-3.75, 11.5, -3.25], [-3.25, 13.75, -2.75], c => edge(c, mul(fab(c, FAB), c.p[1] > 13.25 ? 0.86 : 0.95), { stitch: false }), 'dangling strap');

  return { S: K.S, px: K.px, cell: K.cell, boxes: B };
};
