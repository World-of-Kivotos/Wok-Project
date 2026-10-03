// ================= FORT Gladiator-S lightweight plate carrier (Viking) — EFT reference =================
// tarkov.dev render 69d27591df93e6952a031c84 (slug fort-gladiator-s-plate-carrier-viking, 3.05 kg; soft armour incl. a
// small groin piece). The same lightweight carrier and gear layout as the MultiCam lightweight Gladiator-S
// (armors/gladiator_s_light_multicam.js), but in worn, dirty charcoal-black Cordura (brown grime, greyer scuffs) and
// dressed as a "viking": thin grey chalk runes (five over three, with chalk rules above, between and below the lines)
// scrawled across the dark-on-dark MOLLE field; silver duct tape on the shoulder straps; the black handheld radio (tape
// round its lower third, orange / blue buttons) with its whip antenna at the wearer's left chest edge; four black rifle
// mags in the placard; a charcoal double mag pouch (two separate flaps over most of each pouch, light grey pull tabs
// hanging from them) on the wearer's right; a charcoal admin/GP pouch with teal-grey worn MOLLE rows, black trauma
// shears and a light grey lanyard on the wearer's left; and a big black hanging dump pouch under the placard (zipper
// round its top, zipper pulls at the sides, rounded bottom corners) carrying a black Vegvisir (rune compass) patch:
// small pale ring, eight thin pale staves.
// At two cells per pixel, runes that are drawn with connected strokes and kept apart (so the chest reads as a chalk
// scrawl, not a lattice) leave room for three on line 1 between the bag edge and the radio and two on line 2, and the
// rule under line 2 would sit behind the mag tops, so the model has three over two with two rules.
ARMORS.gladiator_s_viking = function (mode) {
  const K = kit('M');                          // always the 2x style, whatever mode is asked for
  const { edge, G, isPanel, isSide, px } = K;
  const B = [];

  // ---------------- palette (albedo; lit front faces render darker) ----------------
  const BASE = hex('#403d38'), GRIME = hex('#54493a'), SCUFF = hex('#625e56');   // worn charcoal Cordura
  const LINING = hex('#1d1d1c');               // black spacer mesh (neckline, strap undersides)
  const MESH = hex('#3a3631');                 // dark padded mesh inside the back panel
  const LOOP = hex('#34322f');                 // black loop band across the top of the front
  const WEB = hex('#57554f');                  // webbing tape (a little greyer than the bag)
  const STRAP = hex('#2a2a28'), BUCK = hex('#5e605b');   // cummerbund strap + side-release buckle
  const TAPE = hex('#b2b6b4');                 // silver duct tape
  const PULL = hex('#bfc3be');                 // light grey pull tabs / lanyard
  const CHALK = hex('#dfe2dc');                // chalk runes / patch symbol
  const TEAL = hex('#5b6763');                 // worn teal-grey webbing on the GP pouch
  const BLACK = hex('#2a2b2d');

  // dirty charcoal: slow tone drift, soft brown grime blotches, a few greyer scuffs (1-3 px shapes on the half-pixel
  // grid); `o` shifts the pattern so a pouch sewn on is its own piece of cloth
  const fab = (c, k = 1, amt = 0.07, o = 0) => {
    const q = snap([c.p[0] + o, c.p[1] + o * 0.6, c.p[2] - o * 0.4], px);
    const tone = 0.94 + 0.12 * fbm(q[0] * 0.3 + 7, q[1] * 0.3 + 3, q[2] * 0.3 + 1);
    const g = sm(clamp01((fbm(q[0] * 0.5 + 11, q[1] * 0.45 + 5, q[2] * 0.5 + 17) - 0.52) * 3));
    const s = sm(clamp01((fbm(q[0] * 0.8 + 31, q[1] * 0.8 + 9, q[2] * 0.8 + 3) - 0.66) * 4));
    return mul(mix(mix(BASE, GRIME, g * 0.7), SCUFF, s * 0.6), k * tone * G(c, amt) * (1.04 - 0.008 * Math.max(0, Math.min(16, c.p[1]))));
  };
  // MOLLE: webbing tape rows a pixel tall with bar tacks every 1.5 px, split by a dark slot row (period 1.5 px).
  // `dark`: the chest's dark-on-dark webbing (tape a little darker than the cloth, no lighter tape colour) so the chalk
  // is the only bright mark there, as on the render
  const webbing = (c, col, y0, rows, h0, h1, { side = false, t = 0.5, web = WEB, dark = false } = {}) => {
    if (!(isPanel(c) || (side && isSide(c)))) return null;
    const h = isPanel(c) ? c.p[0] : c.p[2];
    if (h < h0 || h > h1) return null;
    for (let i = 0; i < rows; i++) {
      const dy = c.p[1] - (y0 + i * 1.5);
      if (dy >= 0 && dy < 1.5) {
        if (dy >= 1) return mul(col, dark ? 0.7 : 0.6);                        // dark slot under the tape
        const tack = (((h - h0) % 1.5) + 1.5) % 1.5 < 0.5;                     // bar tacks
        if (dark) return mul(col, tack ? 0.8 : dy < 0.5 ? 0.92 : 0.86);
        const tape = mul(mix(col, web, t), dy < 0.5 ? 1.12 : 1.0);
        return tack ? mul(tape, 0.8) : tape;
      }
    }
    return null;
  };
  // chalk runes on the chest left of the radio, like the render: a chalk rule, a line of runes hanging from it, a second
  // rule, then a shorter line of runes under the right part. Grey chalk scrawl, sparse on the dark cloth: every stroke is
  // edge-connected (diagonals drawn as two-cell stair steps, never single cells meeting at a corner) and the glyphs stand
  // two empty cells apart, so the chest never turns into a lattice. Picked from the render's runes (none a Latin capital),
  // 4 cells tall, 3 wide: line 1 ᛊ (a short "/" over a "\"), ᛏ (up-arrow), ᛉ (fork); line 2 ᚾ (stem crossed by a
  // slanted bar), ᛦ (4 wide: stem with a branch stepping up to the right from low on it).
  const GLYPH = {
    sow: ['.##', '##.', '.##', '..#'], tyr: ['.#.', '###', '.#.', '.#.'], alg: ['#.#', '###', '.#.', '.#.'],
    nau: ['.#.', '##.', '.##', '.#.'], yr: ['#..#', '#.##', '###.', '#...'],
  };
  // rows j (half-pixel cells from the bag top at y 1), columns k (half-pixel cells from the bag's edge at x -4.25).
  // The top rule runs along the loop band under the neckline (its ends pass under the taped straps) to free a row for
  // the 4-cell glyphs; line 2's stem feet run on behind the mag tops, as the render's strokes run down to the pouches.
  const RULES = [{ j: 0, k0: 1, k1: 13 }, { j: 5, k0: 1, k1: 13 }];
  const LINES = [{ j: 1, at: [['sow', 1], ['tyr', 6], ['alg', 11]] }, { j: 6, at: [['nau', 5], ['yr', 10]] }];
  const rune = c => {                                    // 0 none, 1 rule, 2 stroke
    if (c.face !== 'front') return 0;
    const j = Math.floor((c.p[1] - 1) / px), k0 = Math.floor((c.p[0] + 4.25) / px);
    for (const R of RULES) if (j === R.j && k0 >= R.k0 && k0 <= R.k1) return 1;
    for (const L of LINES) {
      const r = j - L.j;
      if (r < 0 || r > 3) continue;
      for (const [name, k] of L.at) {
        const g = GLYPH[name], i = k0 - k;
        if (i >= 0 && i < g[r].length) return g[r][i] === '#' ? 2 : 0;
      }
    }
    return 0;
  };

  // ---------------- carrier ----------------
  // front plate bag: black loop band under the neckline, then dark MOLLE rows down to the placard, runes chalked over
  // the top of them
  const bag = c => {
    if (c.face === 'top') return mul(LINING, G(c, 0.1));
    if (c.face === 'back') return mul(LINING, 0.9);
    let col = fab(c);
    if (c.face === 'front') {
      const y = c.p[1];
      // chalk runs unbroken over the webbing: drawn on the plain cloth colour, a little uneven
      const r = rune(c);
      if (r) return mul(mix(col, CHALK, r === 1 ? 0.25 : 0.45), G(c, 0.06));
      if (y < 2) col = mul(LOOP, G(c, 0.06) * (y >= 1.5 ? 0.88 : 1));          // loop band (a seam under it)
      else col = webbing(c, col, 2, 3, -4.25, 4.25, { dark: true }) || col;
    }
    return edge(c, col);
  };
  B.push(box('body', [-4.25, 1, -3.25], [4.25, 11, -2.5], bag, { tag: 'front plate bag' }));
  const back = c => {
    if (c.face === 'top') return mul(LINING, G(c, 0.1));
    if (c.face === 'front') return mul(MESH, G(c, 0.08));                        // padded mesh inside the back panel
    const col = fab(c);
    return edge(c, (c.face === 'back' && webbing(c, col, 2, 4, -3.5, 3.5)) || col);
  };
  B.push(box('body', [-4.25, 0.5, 2.5], [4.25, 11, 3.25], back, { tag: 'back plate bag' }));
  const cumm = c => {
    const col = fab(c, 0.95);
    return edge(c, webbing(c, col, 7.5, 2, -2.25, 2.25, { side: true }) || col, { stitch: false });
  };
  // ends 0.5 above the bags' bottoms so the bottom faces never share a plane
  B.push(box('body', [-4.5, 5, -2.75], [4.5, 10.5, 2.75], cumm, { tag: 'cummerbund' }));
  B.push(box('body', [-1.25, 0.25, 3.25], [1.25, 1.25, 3.75], c => edge(c, mul(STRAP, G(c, 0.06)), { stitch: false }), { tag: 'drag handle' }));
  for (const s of [-1, 1]) {
    const [a0, a1] = s < 0 ? [-4.75, -4.5] : [4.5, 4.75];
    B.push(box('body', [a0, 6, -2.5], [a1, 7, 2.5], c => {
      if (isSide(c) && c.p[2] < -1) return mul(BUCK, (c.p[2] < -2 || c.p[1] < 6.5 ? 1.08 : 0.9) * G(c, 0.04));
      return mul(STRAP, G(c, 0.06) * (isSide(c) ? 1 : 0.85));
    }, { tag: 'cummerbund strap + buckle' }));
  }

  // ---------------- padded shoulder straps, silver tape on the front risers ----------------
  const hump = c => {
    if (c.face === 'bottom') return mul(LINING, 1.2);
    const col = fab(c, 1.04, 0.07, 2.3);
    if (isPanel(c)) return mul(col, 0.9);
    if (c.face !== 'top') return col;
    return mul(col, 1.12);                                                      // lit top of the pad
  };
  // silver duct tape: soft crinkles a pixel or two across (no cell-sized check), the faces round the side a bit duller
  const tape = c => {
    const q = snap(c.p, px);
    const k = 0.94 + 0.1 * fbm(q[0] * 0.9 + 13, q[1] * 0.9 + 2, q[2] * 0.9 + 29);
    return mul(TAPE, k * G(c, 0.04) * (c.face === 'front' ? 1 : 0.85));
  };
  const riser = front => c => {
    if (c.face === 'bottom' || c.face === (front ? 'back' : 'front')) return mul(LINING, 1.2);
    const y = c.p[1];
    if (front && y >= 0.5 && y < 1.5) return tape(c);                          // duct tape round the strap
    return edge(c, fab(c, 1.04, 0.07, 2.3), { stitch: false });
  };
  for (const s of [-1, 1]) {
    const X = (a, b) => (s < 0 ? [a, b] : [-b, -a]);
    const [h0, h1] = X(-5.5, -4.5), [b0, b1] = X(-5.75, -4.25), [r0, r1] = X(-4.5, -2.25), [k0, k1] = X(-4.5, -2.25);
    B.push(box('body', [b0, -1.25, -3.5], [b1, -0.25, 3.5], hump, { tag: 'shoulder pad hump' }));
    B.push(box('body', [h0, -1.75, -3.25], [h1, -0.75, 3.25], hump, { tag: 'shoulder pad crown' }));
    // (the top chalk rule's ends run on under the tape)
    B.push(box('body', [r0, -0.25, -3.75], [r1, 1.5, -2.75], riser(true), { tag: 'strap front' }));
    B.push(box('body', [k0, -0.25, 2.75], [k1, 1.5, 3.5], riser(false), { tag: 'strap back' }));
  }

  // ---------------- radio at the wearer's left chest edge ----------------
  const RAD = hex('#26272a'), SCREEN = hex('#51646c'), KEYS = hex('#3c3e42'), ORANGE = hex('#d8662c'), BLUE = hex('#3f78c8');
  const radio = c => {
    const y = c.p[1];
    if (y >= 4.25 && c.face !== 'top' && c.face !== 'bottom') return tape(c);   // duct tape round its lower third
    if (c.face === 'top') return mul(RAD, 1.5);
    if (c.face === 'bottom') return mul(TAPE, 0.7);
    if (c.face !== 'front') return mul(RAD, 0.9);
    const i = Math.floor((c.p[0] - 3) / px), j = Math.floor((y - 1.75) / px);
    if (j === 0) return mul(RAD, 1.35);
    if (j <= 2) return i === 0 ? (j === 1 ? ORANGE : RAD) : mul(SCREEN, j === 1 ? 1.08 : 0.94);
    if (i === 0) return j === 3 ? BLUE : RAD;
    return mul(KEYS, j === 3 ? 1.08 : 0.94);
  };
  B.push(box('body', [3, 1.75, -4.25], [4.5, 5.25, -3.25], radio, { tag: 'radio' }));
  B.push(box('body', [4, 1.25, -4], [4.75, 1.75, -3.5], K.plastic(BLACK), { tag: 'antenna base' }));
  B.push(box('body', [4.5, -2.25, -3.875], [5, 1.5, -3.625], c => mul(hex('#1e1f21'), c.face === 'top' ? 1.6 : isSide(c) ? 0.85 : 1.1), { tag: 'radio whip antenna' }));

  // ---------------- placard with four black rifle mags ----------------
  const placard = c => {
    if (c.face === 'top') return c.p[2] < c.box.z + px ? mul(fab(c, 1, 0.07, 1.7), 1.15) : mul(LINING, 0.8);
    return edge(c, fab(c, 0.96, 0.07, 1.7));
  };
  B.push(box('body', [-3.75, 6.5, -4], [3.75, 11, -3.25], placard, { tag: 'mag placard' }));
  const MAG = hex('#3a3b3e'), MAGTOP = hex('#1f2022'), BRASS = hex('#caa24e'), COPPER = hex('#b5703f');
  const mag = c => {
    if (c.face === 'bottom') return mul(MAG, 0.6);
    if (c.face === 'top') return mul(MAGTOP, c.ex < px ? 1.5 : 1);
    let col = mul(MAG, G(c, 0.04));
    if (!isPanel(c)) return mul(col, 0.74);
    if (c.ev < px) return mul(col, 1.3);
    if (Math.floor(c.eu / px) === 1) col = mul(col, 0.8);
    return col;
  };
  const lips = c => (c.face !== 'top' ? mul(MAGTOP, isPanel(c) ? 1.3 : 1) : (c.p[0] > c.box.x + c.box.w - px ? COPPER : BRASS));
  // (a quarter pixel lower than on the MultiCam so the second rune line shows above the feed lips)
  for (const cx of [-2.625, -0.875, 0.875, 2.625]) {
    B.push(box('body', [cx - 0.75, 5.75, -3.875], [cx + 0.75, 9.25, -3.375], mag, { tag: 'rifle mag' }));
    B.push(box('body', [cx - 0.5, 5.5, -3.75], [cx + 0.5, 5.75, -3.5], lips, { tag: 'rifle mag feed lips' }));
  }

  // ---------------- front pouches ----------------
  // wearer's right: charcoal double mag pouch - two separate flaps covering most of each pouch, a light grey pull tab
  // hanging from the middle of each flap's lower edge
  const pouchBody = c => {
    let col = fab(c, 1.02, 0.07, 3.3);
    if (c.face === 'front') {
      const i = Math.floor(c.eu / px), y = c.p[1], n = Math.round(c.box.h / px);
      const j = Math.floor((y - c.box.y) / px);
      if (i === 1 && y >= 9.5 && y < 10.25) return mul(PULL, y < 10 ? 1.02 : 0.9);   // pull tab under the flap
      if (j === n - 1) return mul(col, 0.8);
      return col;
    }
    return edge(c, mul(col, 0.9), { stitch: false });
  };
  const pouchFlap = c => {
    let col = fab(c, 1.1, 0.07, 3.3);
    if (c.face === 'top') return mul(col, 1.1);
    if (c.face !== 'front') return mul(col, 0.82);
    const j = Math.floor((c.p[1] - c.box.y) / px), n = Math.round(c.box.h / px);
    if (j === 0) return mul(col, 1.14);
    if (j === n - 1) return mul(col, 0.72);
    return col;
  };
  for (const cx of [-2.5, -0.5]) {
    B.push(box('body', [cx - 0.75, 7.25, -4.75], [cx + 0.75, 10.75, -4], pouchBody, { tag: 'double mag pouch' }));
    B.push(box('body', [cx - 0.875, 7, -5], [cx + 0.875, 9.5, -4], pouchFlap, { tag: 'mag pouch flap' }));
  }
  // wearer's left: charcoal GP pouch (taller than the mag pouches), zipper along its top, three worn teal-grey MOLLE rows
  const ZIP = hex('#1c1d1c');
  const gp = c => {
    if (c.face === 'top') return c.p[2] < c.box.z + 0.5 ? mul(ZIP, 1.4) : fab(c, 1.05, 0.07, 5.1);
    const col = fab(c, 1, 0.07, 5.1);
    if (c.face === 'front') {
      if (c.p[1] < c.box.y + px) return mix(mul(col, 1.1), ZIP, 0.5);
      const w = webbing(c, col, 7.25, 3, 0.75, 4.25, { web: TEAL, t: 0.6 });
      if (w) return w;
    }
    return edge(c, col);
  };
  B.push(box('body', [0.75, 6.25, -5], [4.25, 11.25, -4], gp, { tag: 'GP pouch' }));
  // trauma shears (flat cut-out on the pouch front): the big black finger ring up at the pouch's outer top corner, the
  // smaller ring below it toward the middle, touching at the pivot, and the grey blades running down from the pivot
  const SH = ['...###', '...#.#', '...###', '###B..', '#.#BB.', '###BB.', '....B.', '....B.'];
  const shears = c => {
    if (c.face !== 'front') return null;
    const i = Math.min(5, Math.floor(c.eu / px)), j = Math.min(7, Math.floor(c.ev / px));
    const ch = SH[j][i];
    if (ch === '.') return null;
    if (ch === 'B') return mul(hex('#6f7477'), (j === 3 ? 0.8 : 1) * (i === 3 ? 1.1 : 0.95));
    return mul(BLACK, j === 0 || j === 3 ? 1.4 : 1.05);                        // lit top edges of the rings
  };
  B.push(box('body', [1.25, 7, -5.25], [4.25, 11, -5], shears, { tag: 'trauma shears' }));
  // light grey lanyard tied to the shears, hanging over the pouch's lower edge onto the dump pouch
  B.push(box('body', [3.25, 10.75, -5.5], [3.75, 12.25, -4.5], c => (c.face === 'back' ? null : mul(PULL, G(c, 0.05) * (c.face === 'front' ? (c.p[1] >= 11.75 ? 0.9 : 1) : 0.8))), { tag: 'shears lanyard' }));

  // ---------------- hanging dump pouch with the Vegvisir patch ----------------
  // big black pouch hung from the placard over the groin: zipper round its top, rounded bottom corners (cut out through
  // the whole box), zipper pulls on both sides
  const DUMP = hex('#2f2d2b');
  const dump = c => {
    const ax = Math.abs(c.p[0]), y = c.p[1];
    if (ax > 3 && y > 15) return null;                                         // rounded bottom corners
    if (c.face === 'back') return mul(LINING, 1.1);
    const col = mul(mix(fab(c, 1, 0.07, 8.9), DUMP, 0.55), 1 - 0.04 * clamp01((y - 11) / 4.5));
    if (c.face === 'top') return c.p[2] < c.box.z + px ? mul(ZIP, 1.3) : mul(col, 1.05);
    if (c.face === 'front') {
      if (y < 11.5) return mix(mul(col, 1.1), ZIP, 0.55);                       // zipper along the top
      if (y < 12) return mul(col, 1.1);                                         // lit fold under it
      if ((ax > 2.5 && y > 14.5) || y > 15) return mul(col, 0.8);               // shaded rounded bottom edge
      return col;
    }
    return edge(c, mul(col, 0.9), { stitch: false });
  };
  B.push(box('body', [-3.5, 11, -4.5], [3.5, 15.5, -3], dump, { tag: 'dump pouch' }));
  // Vegvisir: a mostly black patch, a small hollow white ring in the middle and eight thin staves running out from it
  // (straight up / down / left / right to the patch edge, a short diagonal stave off each corner of the ring)
  const VEG = ['...#...', '.#.#.#.', '..###..', '###.###', '..###..', '.#.#.#.', '...#...'];
  const patch = c => {
    if (c.face === 'back') return null;
    if (c.face !== 'front') return mul(BLACK, 0.8);
    const i = Math.min(6, Math.floor(c.eu / px)), j = Math.min(6, Math.floor(c.ev / px));
    return VEG[j][i] === '#' ? mul(CHALK, 0.7 * G(c, 0.04)) : mul(hex('#1f1f1e'), G(c, 0.05));
  };
  B.push(box('body', [-1.75, 11.875, -4.75], [1.75, 15.375, -4.5], patch, { tag: 'Vegvisir patch' }));
  for (const s of [-1, 1]) {
    const [p0, p1] = s < 0 ? [-3.75, -3.5] : [3.5, 3.75];
    B.push(box('body', [p0, 11.25, -4.25], [p1, 12.75, -3.75], c => mul(hex('#252524'), c.face === 'top' ? 1.4 : c.p[1] > 12.25 ? 1.25 : 1), { tag: 'zipper pull' }));
  }
  return { S: K.S, px: K.px, cell: K.cell, boxes: B };
};
