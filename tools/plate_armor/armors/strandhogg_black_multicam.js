// ================= FirstSpear Strandhogg plate carrier (MultiCam Black) — EFT reference =================
// tarkov.dev render 69d36347705756116e0a901c (EFT: "First Spear Strandhogg plate carrier (MultiCam Black)", an armored
// rig). Same carrier and pouch layout as the Ranger Green one, but: MultiCam Black everywhere
// (dark charcoal ground, small crisp khaki-olive shapes, mid-grey and near-black ones), greyer mag pouches, charcoal shoulder tubes, black QASM
// and cummerbund buckles, black bungees, and three short straight black polymer mags (waffle grid: two columns of
// recessed windows; darker than the camo bag behind them) instead of the tall AK mags; black pistol mag (shock-cord X on
// its pouch), dark radio (stitched X on the pouch flap), IFAK with a dull red cross on its top flap, a dark grey loop
// patch (a touch lighter than the dangler) with a box-X, black mesh lining. The charcoal pads are lighter than the
// carrier, as in the render.
// In MC the radio pouch and the IFAK (side by side on the cummerbund in the render) sit stacked on the front corner.
// Geometry is shared with strandhogg_ranger_green (same code, other palette).
ARMORS.strandhogg_black_multicam = function (mode) {
  const V = {
    ak: false, grid: true,
    // a dark charcoal ground with big, low-contrast charcoal mottling; on it small mid-grey and near-black shapes and, on
    // top, small crisp khaki-olive shapes (1-1.5 px, several of them over the open chest above the mags, as in the
    // render). Overall as bright as the render; the camo bag is still clearly lighter than the black PMAGs before it.
    // (the olive layer's offset is picked so its shapes spread over the chest's left, middle and right)
    camo: { scale: 0.5, base: hex('#40413c'), ls: [[hex('#4a4b45'), 0.55, 0.5, 3], [hex('#5a5b53'), 1.0, 0.63, 63], [hex('#2f302d'), 0.9, 0.62, 41], [hex('#7a7664'), 1.4, 0.6, 85]] },
    BAG: [hex('#474842'), 0], POUCH: [hex('#4a4d4f'), 0.5], DANG: [hex('#474842'), 0.1], COVER: [hex('#4a4b46'), 0.2],
    PAD: hex('#63635f'), PADMESH: hex('#1a1d1c'), METAL: hex('#2c2d2f'), CORD: hex('#1e1f20'), TOGGLE: hex('#2a2b2d'),
    MAG: hex('#2e3134'), MAGTOP: hex('#1c1d1f'), PISTOL: hex('#262729'), RADIO: hex('#383b3e'), ANT: hex('#202124'),
    CROSS: hex('#8e2e27'), VEL: hex('#55554f'), TAB: hex('#2c2c2a'), ZIP: hex('#1c1c1c'),
    LINING: hex('#151615'), MESH: hex('#1c1c1b'),
    // (the camo's khaki shapes would show as light slots between the black mags: their shadow is pulled toward black,
    // but only part way: in the render the camo still shows, shaded, in the slots between the mags)
    GAPDARK: 0.3,
  };
  const K = kit('M');                                   // always the 2x style, whatever mode is asked for
  const { edge, G, isPanel, isSide, px } = K;
  const B = [];
  const add = (a, b, mat, tag, opt = {}) => B.push(box('body', a, b, mat, { tag, ...opt }));
  const md = (v, m) => ((v % m) + m) % m;
  const flush = (mat, face) => c => (c.face === face ? null : mat(c));   // face pressed flat on another surface

  // ---------- fabric ----------
  // plain Cordura: a faint low-frequency mottle + per-cell grain; MultiCam Black: camo sampled on the 0.5 px grid at a
  // scale that keeps its shapes 1-3 px, optionally pulled toward a part's own tone (the mag pouches are greyer)
  const fab = (c, base, k = 1, amt = 0.06) => {
    const q = snap(c.p, K.cell);
    const n = fbm(q[0] * 0.45 + 7, q[1] * 0.45 + 1, q[2] * 0.45 + 3);
    return mul(base, k * (0.97 + 0.06 * n) * G(c, amt));
  };
  // (two-octave noise: the finer third octave broke the shape edges into single-cell specks)
  const camoRaw = p => {
    const q = snap(p, 0.5).map(v => v * V.camo.scale);
    let col = V.camo.base;
    for (const [h, f, t, o] of V.camo.ls) if (fbm(q[0] * f + o, q[1] * f * 0.9 - o, q[2] * f + o * 0.5, 2) > t) col = h;
    return col;
  };
  // (a cell whose four neighbours on its face all differ from it takes their most common colour, so the small shapes
  // never leave one-cell specks or one-cell holes)
  const camoAt = (p, face) => {
    const c0 = camoRaw(p);
    const axes = face === 'top' || face === 'bottom' ? [0, 2] : face === 'left' || face === 'right' ? [2, 1] : [0, 1];
    const nb = [];
    for (const a of axes) for (const s of [-0.5, 0.5]) { const q = p.slice(); q[a] += s; nb.push(camoRaw(q)); }
    if (nb.includes(c0)) return c0;
    let best = nb[0], nBest = 0;
    for (const c of nb) { const n = nb.filter(d => d === c).length; if (n > nBest) { best = c; nBest = n; } }
    return best;
  };
  const cloth = (c, [tone, t], k = 1) => (V.camo ? mul(mix(camoAt(c.p, c.face), tone, t), k * G(c, 0.04)) : fab(c, tone, k));
  const lining = c => mul(V.LINING, G(c, 0.1));
  const SLIT = 0.62;                                     // laser-cut slits: a clear dark cut, not a black hole

  // laser-cut slit rows on a plate bag face: four 1.5 px slits per row with 0.5 px bridges, one row every 1.5 px
  const slits = (c, col, rows) => {
    const y = c.p[1], ax = Math.abs(c.p[0]);
    if (!rows.some(r => y >= r && y < r + 0.5)) return null;
    return (ax > 0.25 && ax < 1.75) || (ax > 2.25 && ax < 3.75) ? mul(col, SLIT) : null;
  };

  // ---------- plate bags + cummerbund ----------
  // (the strip of bag right behind the magazines is in their shadow, so the webbing gaps between the mags read as
  // dark slots, as in the render, instead of showing whatever camo shape happens to lie there)
  // (GAPDARK: with camo the shadow also reaches the strips beside the outer mags and is pulled toward the lining tone)
  // (PMAGs: the shadow starts under the mags' light top band, so the feed lips and that band stand against the camo and
  // each mag top keeps its own outline from the front, as in the render; only the slots lower down stay dark)
  const MAGTOP_Y = V.ak ? 3 : 5.75, SHX = V.GAPDARK ? 3.25 : 2.75;
  const frontBag = c => {
    if (c.face === 'back') return mul(V.MESH, G(c, 0.1));                     // spacer-mesh lining
    let col = cloth(c, V.BAG);
    if (c.face === 'top') return mul(col, 1.06);
    if (c.face === 'front') {
      col = slits(c, col, [2, 3.5, 5, 6.5, 8, 9.5]) || col;
      if (Math.abs(c.p[0]) <= SHX && c.p[1] >= MAGTOP_Y && c.p[1] < 7.5) col = mix(mul(col, 0.7), V.LINING, V.GAPDARK);   // contact shadow
    }
    return edge(c, col);
  };
  add([-4.25, 1, -3.25], [4.25, 10.5, -2.5], frontBag, 'front plate bag');
  const backBag = c => {
    if (c.face === 'front') return mul(V.MESH, G(c, 0.1));
    let col = cloth(c, V.BAG);
    if (c.face === 'top') return mul(col, 1.06);
    if (c.face === 'back') col = slits(c, col, [2.75, 4.25, 5.75, 7.25, 8.75]) || col;   // (clear of the drag handle)
    return edge(c, col);
  };
  add([-4.25, 0.75, 2.5], [4.25, 10.5, 3.25], backBag, 'back plate bag');
  // cummerbund: laser-cut too (two slit rows on each side, seen when the arms swing)
  const cumm = c => {
    let col = cloth(c, V.BAG, 0.94);
    if (isSide(c)) {
      const y = c.p[1], az = Math.abs(c.p[2]);
      if ([6.5, 8].some(r => y >= r && y < r + 0.5) && az > 0.25 && az < 2.25) col = mul(col, SLIT);
    }
    return edge(c, col, { stitch: false });
  };
  add([-4.5, 5.5, -2.75], [4.5, 10, 2.75], cumm, 'cummerbund');
  // drag handle on the back bag
  add([-1.25, 1, 3.25], [1.25, 2, 3.75], flush(c => edge(c, cloth(c, V.COVER, 0.85), { stitch: false }), 'front'), 'drag handle');

  // ---------- shoulder straps: padded tubes on the shoulders, strap cover over their front half ----------
  // (the straps over the trapezius sit inside the head; what shows is the pad on each shoulder, just outside the hat
  // layer, and the short risers below the chin that end in the QASM buckles on the plate bag's top corners)
  const metal = (c, col) => {
    if (c.face === 'top') return mul(col, 1.22);
    if (!isPanel(c)) return mul(col, 0.8);
    return mul(col, c.ev < px ? 1.1 : 0.95);
  };
  for (const s of [-1, 1]) {
    const X = (a, b) => (s < 0 ? [a, b] : [-b, -a]);
    const out = s < 0 ? 'right' : 'left', inn = s < 0 ? 'left' : 'right';
    const pad = crown => c => {
      if (c.face === 'bottom' || c.face === inn) return mul(V.PADMESH, G(c, 0.08));   // mesh on the neck side / underside
      // the strap cover runs over the front half of the pad and down its front end
      const cover = c.p[2] < -0.75 && (c.face === 'top' || (crown && (c.face === out || c.face === 'front')));
      if (cover) return mul(cloth(c, V.COVER), c.face === 'top' ? 1.05 : 0.92);
      let col = fab(c, V.PAD, 1, 0.07);
      if (isPanel(c)) return mul(col, 0.95);                                      // pad ends
      if (c.face === 'top') return mul(col, 1.08);
      return c.face === out && !crown && c.ev > c.fh - px ? mul(col, 0.84) : col;
    };
    // a stepped dome: 1.5-wide base + 1-wide crown, the crown 0.25 shorter at each end so no two faces share a plane
    add([X(-5.75, -4.25)[0], -1.25, -3.5], [X(-5.75, -4.25)[1], -0.25, 3.5], pad(false), 'shoulder pad');
    add([X(-5.5, -4.5)[0], -1.75, -3.25], [X(-5.5, -4.5)[1], -0.75, 3.25], pad(true), 'shoulder pad crown');
    const riser = c => (c.face === 'bottom' ? mul(V.PADMESH, 1.2) : edge(c, cloth(c, V.COVER), { stitch: false }));
    // (0.25 wider than the bags on the outside, so their outer faces never share the bags' side planes)
    add([X(-4.5, -2.5)[0], -0.25, -3.75], [X(-4.5, -2.5)[1], 1.25, -3], riser, 'strap front');
    add([X(-4.5, -2.5)[0], -0.25, 3], [X(-4.5, -2.5)[1], 1.25, 3.75], riser, 'strap back');
    // QASM buckle on the bag's top corner: light upper half, dark slot in the middle of the lower half
    const qasm = c => {
      if (c.face === 'front') {
        const i = Math.floor(c.eu / px), j = Math.floor(c.ev / px);
        if (j === 1 && i === 1) return mul(V.METAL, 0.55);
        return mul(V.METAL, j === 0 ? 1.12 : 0.92);
      }
      return metal(c, V.METAL);
    };
    add([X(-4.25, -2.75)[0], 0.5, -4], [X(-4.25, -2.75)[1], 1.5, -3.5], qasm, 'QASM buckle');
    // short bungee cord hanging from the buckle over the bag's top corner, a knotted toggle at its end (the left one hangs
    // further out, clear of the bent tops of the Ranger Green version's AK mags; the right one sits a little inward, and a
    // strip of bag shows between it and the radio antenna below, as in the render, so the black cord, antenna and radio
    // read as separate things instead of one black column)
    const o = s > 0 ? 0.375 : 0.125;
    add([X(-3.75, -3.25)[0] + o, 1.5, -3.75], [X(-3.75, -3.25)[1] + o, 2.25, -3.25], flush(c => mul(V.CORD, isPanel(c) ? G(c, 0.05) : 0.8), 'back'), 'bungee cord');
    add([X(-3.875, -3.125)[0] + o, 2.25, -4], [X(-3.875, -3.125)[1] + o, 2.75, -3.25], flush(c => metal(c, V.TOGGLE), 'back'), 'cord toggle');
  }

  // ---------- magazines ----------
  // Magazines sit IN their pouches (1.5-2 px down into the open mouth), broad side forward, darker spine sides, one
  // darker rib, a narrower feed-lip block on top with one brass round (copper tip) lying across it.
  const BRASS = hex('#caa24e'), COPPER = hex('#b5703f');
  // (plain: a lower piece of a bent mag, no feed-lip edge, its top hidden inside the piece above)
  const magPaint = (base, { capped = false, ribs = true, grid = false, plain = false } = {}) => c => {
    if (c.face === 'bottom') return mul(base, 0.6);
    if (c.face === 'top') {
      if (plain) return mul(base, 0.8);
      if (capped) return mul(V.MAGTOP, 1.2);
      const n = Math.max(1, Math.round(c.fw / px)), i = Math.min(n - 1, Math.floor(c.eu / px));
      return i === n - 1 && n >= 2 ? COPPER : BRASS;                         // round lying across the lips
    }
    let col = mul(base, G(c, 0.04));
    if (!isPanel(c)) return mul(col, 0.74);                                   // spine
    if (c.ev < px && !capped && !plain) return mul(col, 1.3);                 // feed-lip edge
    const n = Math.max(1, Math.round(c.fw / px)), i = Math.floor(c.eu / px), j = Math.floor(c.ev / px);
    if (grid) {
      // PMAG waffle: two columns of recessed windows (1 x 2 cells) either side of the centre rib, a raised cross rib
      // every 1.5 px (the top band is a rib)
      if (j % 3 === 0) col = mul(col, 1.15);
      else if (i !== Math.floor(n / 2)) col = mul(col, 0.74);
    } else if (ribs && i === Math.floor(n / 2)) col = mul(col, 0.8);
    return col;
  };
  const lips = c => {
    if (c.face !== 'top') return mul(V.MAGTOP, isPanel(c) ? 1.25 : 0.95);
    return c.eu > c.fw - px ? COPPER : BRASS;
  };

  // ---------- front: triple rifle-mag pouch on the bag, pistol-mag pouch on the wearer's left ----------
  // three 1.5 px pockets, each as wide as its mag, with a 0.5 px webbing gap between them: x -3.25..3.25 (13 cells:
  // cells 0 / 4 / 8 / 12 are webbing, the outer two also get the bound edge)
  const P0 = -3.25;
  const tpouch = c => {
    const web = md(Math.floor((c.p[0] - P0) / px + 1e-6), 4) === 0;
    if (c.face === 'top') {
      const zi = Math.floor((c.p[2] + 4.75) / px);            // 0 front wall, 1 inside, 2 back wall
      if (web || zi !== 1) return mul(cloth(c, V.POUCH), 1.1);
      return lining(c);                                        // open mouth
    }
    let col = cloth(c, V.POUCH);
    if (c.face === 'front') {
      const y = c.p[1];
      if (y < 8) return mul(col, web ? 1.0 : 1.12);                        // elastic top binding
      if (web) col = mul(col, 0.84);                                        // webbing between the pockets
      else if ((y >= 8.5 && y < 9) || (y >= 9.5 && y < 10)) col = mul(col, 0.62);   // two laser-cut rows
    }
    return edge(c, col, { stitch: false });
  };
  add([-3.25, 7.5, -4.75], [3.25, 10.5, -3.25], tpouch, 'triple mag pouch');
  for (const cx of [-2, 0, 2]) {
    if (V.ak) {
      // AK mag, the banana curve bending toward the wearer's left as in the render: a straight piece in the pouch, a
      // middle piece turned 7deg about the joint, a top piece turned 14deg about the middle piece's (turned) top end
      const a1 = 7, a2 = 14, P1 = [cx, 6.75, -4], h1 = 2, h2 = 1.75, R = Math.PI / 180;
      const P2 = [cx + h1 * Math.sin(a1 * R), P1[1] - h1 * Math.cos(a1 * R), -4];
      add([cx - 0.75, 6.5, -4.5], [cx + 0.75, 9.5, -3.5], magPaint(V.MAG, { plain: true }), 'AK mag (in pouch)');
      add([cx - 0.75, P1[1] - h1, -4.5], [cx + 0.75, P1[1] + 0.25, -3.5], magPaint(V.MAG, { plain: true }), 'AK mag (middle)', { rot: [0, 0, a1], pivot: P1 });
      add([P2[0] - 0.75, P2[1] - h2, -4.5], [P2[0] + 0.75, P2[1] + 0.25, -3.5], magPaint(V.MAG, { capped: true }), 'AK mag (top)', { rot: [0, 0, a2], pivot: P2 });
      add([P2[0] - 0.5, P2[1] - h2 - 0.5, -4.375], [P2[0] + 0.5, P2[1] - h2, -3.625], lips, 'AK mag feed lips', { rot: [0, 0, a2], pivot: P2 });
    } else {
      // straight polymer mag with a waffle grid, shorter than the AK mags
      add([cx - 0.75, 5, -4.5], [cx + 0.75, 9.5, -3.5], magPaint(V.MAG, { capped: true, grid: V.grid }), 'PMAG');
      add([cx - 0.5, 4.5, -4.375], [cx + 0.5, 5, -3.625], lips, 'PMAG feed lips');
    }
  }
  // pistol-mag pouch at the wearer's left front corner, its black mag standing 1.5 px out of the mouth
  // (cordX: the shock cord crosses over the front below the straight band: its arms show on the two edge columns,
  // its crossing on the middle column, each step 1 px tall)
  const mouthPouch = (tone, cord, cordX = false) => c => {
    if (c.face === 'top') return c.ex < px ? mul(cloth(c, tone), 1.1) : lining(c);
    let col = cloth(c, tone);
    if (c.face === 'front') {
      const i = Math.floor(c.eu / px), j = Math.floor(c.ev / px);
      if (c.ev < px) col = mul(col, 1.12);
      else if (cord && c.ev >= cord && c.ev < cord + px) col = mix(col, V.CORD, 0.55);   // shock cord across the front
      else if (cordX && j >= 2 && j <= 5 && ((j === 2 || j === 5) ? i !== 1 : i === 1)) col = mix(col, V.CORD, 0.45);
    }
    return edge(c, col);
  };
  // (just outboard of the mag pouch, its outer edge over the cummerbund as in the render)
  add([3.25, 7.5, -4.25], [4.5, 10.5, -3.25], mouthPouch(V.POUCH, 0.5, true), 'pistol mag pouch');
  add([3.5, 6, -4], [4.25, 9, -3.5], magPaint(V.PISTOL, { ribs: false }), 'pistol mag');
  const pullTab = c => mul(V.CORD, isPanel(c) ? (c.ev > c.fh - px ? 1.25 : 1) : 0.8);
  add([3.625, 10.5, -3.875], [4.125, 11.5, -3.625], pullTab, 'pull tab');
  // cummerbund buckles on both edges of the front bag (the right one mostly behind the radio pouch)
  const cbuckle = c => {
    if (c.face === 'front') { const j = Math.floor(c.ev / px); return mul(V.METAL, j === 2 ? 0.6 : j < 2 ? 1.08 : 0.94); }
    return metal(c, V.METAL);
  };
  add([4.25, 6.75, -3.25], [4.75, 9.25, -2.5], cbuckle, 'cummerbund buckle');
  add([-4.75, 6.75, -3.25], [-4.25, 9.25, -2.5], cbuckle, 'cummerbund buckle (right)');

  // ---------- wearer's right front corner: radio pouch, the IFAK stacked under it ----------
  // (both tucked 0.25 behind the mag pouch's edge, so they stay inside the bag's width)
  // (pouch mouth at about two thirds of the bag's height and the antenna tip near its middle, as in the render)
  add([-4.5, 6, -4.5], [-3, 8.5, -3.25], mouthPouch(V.POUCH, 0), 'radio pouch');
  const radio = c => {
    if (c.face === 'top') return mul(V.RADIO, 1.3);
    if (c.face === 'front') return mul(V.RADIO, c.ev < px ? 1.18 : G(c, 0.05));
    return mul(V.RADIO, 0.82);
  };
  add([-4.25, 5, -4.25], [-3.25, 7.5, -3.5], radio, 'radio');
  add([-4.25, 3.75, -4], [-3.75, 5, -3.5], K.plastic(V.ANT), 'radio antenna');  // screwed into the radio's top, outer half
  // padded retention flap over the upper part of the pouch front, as wide as the pouch and standing 0.25 proud of the
  // mag pouch it meets at its lower inner corner; a stitched X across it (1 px steps: corners, then the crossing in the
  // middle column)
  add([-4.5, 6, -5], [-3, 8, -4.5], flush(c => {
    let col = cloth(c, V.POUCH, 1.08);
    if (!isPanel(c)) return mul(col, 0.8);
    const i = Math.floor(c.eu / px), j = Math.floor(c.ev / px);                 // 3 x 4 cells
    if ((j === 0 || j === 3) ? i !== 1 : i === 1) col = mul(col, 0.84);
    return col;
  }, 'back'), 'radio pouch flap');
  // IFAK: top flap carrying the red cross (on the face that points away from the body: its outer side in the render,
  // its front here), the pull-tab strap running down below the flap; it ends a little under the bag's hem
  const IFX = -4.5, IFY = 8.5;
  const ifak = c => {
    if (c.face === 'top') return mul(cloth(c, V.POUCH), 1.08);
    let col = cloth(c, V.POUCH, 1.02);
    if (c.face === 'front') {
      const iu = Math.floor((c.p[0] - IFX) / px), jv = Math.floor((c.p[1] - IFY) / px);   // 3 x 6 cells
      if ((iu === 1 && jv >= 1 && jv <= 3) || (jv === 2 && iu >= 0 && iu <= 2)) return mul(V.CROSS, G(c, 0.05));
      if (jv === 4) col = mul(col, 0.74);                                     // lower edge of the top flap
      else if (jv === 5 && iu === 1) col = mul(col, 0.86);                   // pull-tab strap
    }
    return edge(c, col);
  };
  add([IFX, IFY, -4.25], [-3, 11.5, -2.75], ifak, 'IFAK');
  add([-4, 11.5, -3.625], [-3.5, 12.5, -3.375], pullTab, 'IFAK pull tab');

  // ---------- dangler (dump pouch) under the mag pouch: zipper, hanger tabs, loop patch with an X ----------
  const DX = 3, DT = 10.5, DB = 15.5, DR = 1.25;
  const dhw = y => { const d = y - (DB - DR); return d <= 0 ? DX : DX - DR + Math.sqrt(Math.max(0, DR * DR - d * d)); };
  const dangler = c => {
    const x = c.p[0], ax = Math.abs(x), y = c.p[1], hw = dhw(c.face === 'bottom' ? DB - px / 2 : y);
    if (isPanel(c) || c.face === 'bottom') { if (ax > hw + 1e-6) return null; }
    else if (c.face !== 'top' && hw < DX - px / 2 - 1e-6) return null;          // side faces only where the front is full width
    if (c.face === 'back') return lining(c);
    const shade = 1 - 0.08 * clamp01((y - DT) / (DB - DT));
    let col = cloth(c, V.DANG, shade);
    if (c.face !== 'front') return mul(col, c.face === 'top' ? 0.9 : 0.8);
    if (y < DT + px) return (ax < 0.5 || Math.abs(ax - 2) < 0.5) ? mul(V.TAB, G(c, 0.05)) : mul(col, 0.86);   // hanger tabs
    if (y < DT + 2 * px) return x < -2 && x > -2.5 ? mul(V.METAL, 0.9) : mix(col, V.ZIP, 0.6);             // zipper + pull
    if (ax > hw - px || y > DB - px) col = mul(col, 0.84);                        // bound edge
    return col;
  };
  add([-DX, DT, -3.5], [DX, DB, -2.5], dangler, 'dangler');
  // loop patch: flat velcro with a light top rim and a darker stitched border, the box-X stitched inside it as two thin
  // one-cell diagonals only a shade darker (thin stitching as in the render, not a bold raised X)
  const patch = c => {
    let col = mul(V.VEL, G(c, 0.06));
    if (!isPanel(c)) return mul(col, 0.8);
    const i = Math.floor(c.eu / px), j = Math.floor(c.ev / px), n = Math.round(c.fw / px), m = Math.round(c.fh / px);
    if (j === 0) return mul(col, 1.08);
    if (i === 0 || i === n - 1 || j === m - 1) return mul(col, 0.88);
    return i === j || i + j === n - 1 ? mul(col, 0.85) : col;                 // 6 x 6 cells: X inside the border
  };
  add([-1, 11.75, -3.75], [2, 14.75, -3.5], flush(patch, 'back'), 'loop patch');
  return { S: K.S, px: K.px, cell: K.cell, boxes: B };
};
