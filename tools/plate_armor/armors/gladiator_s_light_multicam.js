// ================= FORT Gladiator-S lightweight plate carrier (MultiCam) — EFT reference =================
// tarkov.dev render 69d27ebeb855150a70092ba3 (tarkov.dev slug fort-gladiator-s-plate-carrier-lightweight-multicam,
// 2.77 kg, no groin / side soft armour). EFT also has a heavier "Gladiator-S (MultiCam)" (69d28cdc274c032dd804afe0,
// groin flap + four flapped mag pouches) that shows the SAME Chinese name; this item is the lightweight one.
// Render: green-leaning MultiCam carrier, green spacer-mesh lining at the neckline, tan padded mesh inside the back
// panel; padded shoulder straps wrapped in silver duct tape just above the chest; an olive loop band across the top of
// the front, then a MultiCam MOLLE field; a black handheld radio (grey tape round its lower third, orange / blue
// buttons, screen at the top) hanging at the wearer's left chest edge with a long whip antenna rising past the shoulder;
// a placard holding four black polymer rifle mags whose tops show above the pouches; in front of it a pale sage double
// mag pouch (two flaps, khaki pull tabs) on the wearer's right and a taller MultiCam admin/GP pouch with MOLLE rows and
// black trauma shears tucked into it (a light grey lanyard hanging from them) on the wearer's left; two olive tabs
// under the placard; cummerbund with a dark green strap and grey-green buckle on the side.
ARMORS.gladiator_s_light_multicam = function (mode) {
  const K = kit('M');                          // always the 2x style, whatever mode is asked for
  const { edge, G, isPanel, isSide, px } = K;
  const B = [];

  // ---------------- palette (albedo; lit front faces render darker) ----------------
  const LINING = hex('#2e3b2b');               // green spacer mesh (neckline, strap undersides)
  const MESH = hex('#8b7f5b');                 // tan padded mesh inside the back panel (seen through the armholes)
  const LOOP = hex('#667543');                 // olive loop band across the top of the front
  const WEB = hex('#a29f78');                  // printed MultiCam webbing
  const STRAP = hex('#34423a'), BUCK = hex('#7d8878');   // cummerbund strap + side-release buckle
  const TAPE = hex('#b4b8b6');                 // silver duct tape
  const SAGE = hex('#98a585');                 // pale sage double mag pouch
  const PULL = hex('#a9a67c');                 // khaki webbing pull tabs at the pouch bottoms
  const TAB = hex('#5c6a42');                  // olive webbing tabs under the placard
  const BLACK = hex('#2a2b2d');

  // MultiCam sampled on the half-pixel grid, scaled so the shapes are 1-3 px; the render's MultiCam leans green:
  // khaki-green ground, green and olive-brown mid shapes, dark-brown shapes, sparse light-khaki flecks
  const camoAt = p => layers(snap(p, px).map(v => v * 0.42), 0, '#9b9771',
    [['#b4ae88', 0.55, 0.58, 3], ['#6d7b4b', 0.62, 0.56, 17], ['#80704c', 0.7, 0.63, 41], ['#58603b', 0.9, 0.66, 77], ['#4d4231', 1.3, 0.75, 5], ['#c6bf9b', 1.4, 0.78, 91]]);
  // lit from above; `o` shifts the pattern so a pouch sewn on is its own piece of cloth
  const fab = (c, k = 1, amt = 0.05, o = 0) => mul(camoAt(o ? [c.p[0] + o, c.p[1] + o * 0.6, c.p[2] - o * 0.4] : c.p),
    k * G(c, amt) * (1.05 - 0.01 * Math.max(0, Math.min(16, c.p[1]))));
  // MOLLE: light webbing tape rows a pixel tall (camo shows through part-way) with bar tacks every 1.5 px, split by a
  // dark slot row, period 1.5 px - the render's field reads as light rows with dark lines between them
  const webbing = (c, col, y0, rows, h0, h1, { side = false, t = 0.55 } = {}) => {
    if (!(isPanel(c) || (side && isSide(c)))) return null;
    const h = isPanel(c) ? c.p[0] : c.p[2];
    if (h < h0 || h > h1) return null;
    for (let i = 0; i < rows; i++) {
      const dy = c.p[1] - (y0 + i * 1.5);
      if (dy >= 0 && dy < 1.5) {
        if (dy >= 1) return mul(col, 0.62);                                    // dark slot under the tape
        const tape = mul(mix(col, WEB, t), dy < 0.5 ? 1.1 : 1.0);
        return (((h - h0) % 1.5) + 1.5) % 1.5 < 0.5 ? mul(tape, 0.8) : tape;   // bar tacks
      }
    }
    return null;
  };

  // ---------------- carrier ----------------
  // front plate bag: olive loop band under the neckline, then three MOLLE rows down to the placard
  const bag = c => {
    if (c.face === 'top') return mul(LINING, G(c, 0.1));                       // spacer-mesh lining at the neckline
    if (c.face === 'back') return mul(LINING, 0.9);
    let col = fab(c);
    if (c.face === 'front') {
      const y = c.p[1];
      if (y < 2.5) col = mul(LOOP, G(c, 0.05) * (y >= 1.5 && y < 2 ? 0.9 : 1));  // loop band (a seam across its middle)
      else col = webbing(c, col, 2.5, 3, -4.25, 4.25) || col;
    }
    return edge(c, col);
  };
  B.push(box('body', [-4.25, 1, -3.25], [4.25, 11, -2.5], bag, { tag: 'front plate bag' }));
  const back = c => {
    if (c.face === 'top') return mul(LINING, G(c, 0.1));
    if (c.face === 'front') return mul(MESH, G(c, 0.08));                        // tan padded mesh inside the back panel
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
  // dark green cummerbund strap round each side with the grey-green side-release buckle near the front
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
    const col = fab(c, 1.04, 0.06, 2.3);
    if (isPanel(c)) return mul(col, 0.9);                                      // pad ends
    if (c.face !== 'top') return col;
    return mul(col, 1.1);                                                       // lit top of the pad
  };
  const riser = front => c => {
    if (c.face === 'bottom' || c.face === (front ? 'back' : 'front')) return mul(LINING, 1.2);
    const y = c.p[1];
    // duct tape wrapped round the strap just above the chest (two tones so it reads crinkled)
    if (front && y >= 0.5 && y < 1.5) {
      const k = (Math.floor(c.p[0] / px) + (y < 1 ? 0 : 1)) % 3 === 0 ? 0.88 : 1.02;
      return mul(TAPE, k * G(c, 0.04) * (c.face === 'front' ? 1 : 0.85));
    }
    return edge(c, fab(c, 1.04, 0.06, 2.3), { stitch: false });
  };
  for (const s of [-1, 1]) {
    const X = (a, b) => (s < 0 ? [a, b] : [-b, -a]);
    const [h0, h1] = X(-5.5, -4.5), [b0, b1] = X(-5.75, -4.25), [r0, r1] = X(-4.5, -2.25), [k0, k1] = X(-4.5, -2.25);
    // stepped dome on top of the shoulder, just outside the hat layer (crown 0.25 shorter at each end)
    B.push(box('body', [b0, -1.25, -3.5], [b1, -0.25, 3.5], hump, { tag: 'shoulder pad hump' }));
    B.push(box('body', [h0, -1.75, -3.25], [h1, -0.75, 3.25], hump, { tag: 'shoulder pad crown' }));
    // front riser runs down onto the bag (its inner face tucked into the bag, never on the bag's planes)
    B.push(box('body', [r0, -0.25, -3.75], [r1, 1.75, -2.75], riser(true), { tag: 'strap front' }));
    B.push(box('body', [k0, -0.25, 2.75], [k1, 1.5, 3.5], riser(false), { tag: 'strap back' }));
  }

  // ---------------- radio at the wearer's left chest edge ----------------
  // black handheld: screen at the top, orange + blue side buttons, keypad, grey duct tape round its lower third; it
  // hangs on the MOLLE and overhangs the carrier edge a little like the render
  const RAD = hex('#26272a'), SCREEN = hex('#51646c'), KEYS = hex('#3c3e42'), ORANGE = hex('#d8662c'), BLUE = hex('#3f78c8');
  const radio = c => {
    const y = c.p[1];
    if (y >= 4.25 && c.face !== 'top' && c.face !== 'bottom') {                 // tape band
      const k = (Math.floor((c.p[0] + c.p[2]) / px) + Math.floor(y / px)) % 3 === 0 ? 0.86 : 1.02;
      return mul(TAPE, k * G(c, 0.04) * (c.face === 'front' ? 1 : 0.85));
    }
    if (c.face === 'top') return mul(RAD, 1.5);
    if (c.face === 'bottom') return mul(TAPE, 0.7);
    if (c.face !== 'front') return mul(RAD, 0.9);
    const i = Math.floor((c.p[0] - 3) / px), j = Math.floor((y - 1.75) / px);
    if (j === 0) return mul(RAD, 1.35);                                         // lit top edge
    if (j <= 2) return i === 0 ? (j === 1 ? ORANGE : RAD) : mul(SCREEN, j === 1 ? 1.08 : 0.94);
    if (i === 0) return j === 3 ? BLUE : RAD;
    return mul(KEYS, j === 3 ? 1.08 : 0.94);                                    // keypad
  };
  B.push(box('body', [3, 1.75, -4.25], [4.5, 5.25, -3.25], radio, { tag: 'radio' }));
  B.push(box('body', [4, 1.25, -4], [4.75, 1.75, -3.5], K.plastic(BLACK), { tag: 'antenna base' }));
  // whip antenna rising past the shoulder, outboard of the hat layer and in front of the shoulder pad
  B.push(box('body', [4.5, -2.25, -3.875], [5, 1.5, -3.625], c => mul(hex('#1e1f21'), c.face === 'top' ? 1.6 : isSide(c) ? 0.85 : 1.1), { tag: 'radio whip antenna' }));

  // ---------------- placard with four black rifle mags ----------------
  const placard = c => {
    if (c.face === 'top') return c.p[2] < c.box.z + px ? mul(fab(c, 1, 0.05, 1.7), 1.12) : mul(LINING, 0.8);   // open mouth
    return edge(c, fab(c, 0.96, 0.05, 1.7));
  };
  B.push(box('body', [-3.75, 6.5, -4], [3.75, 11, -3.25], placard, { tag: 'mag placard' }));
  // black polymer mags: broad side forward, lit feed-lip edge, one darker rib, darker spine; feed-lip block on top
  // with one brass round lying across it (copper tip on the wearer's left); 1.25 px shows above the placard
  const MAG = hex('#3a3b3e'), MAGTOP = hex('#1f2022'), BRASS = hex('#caa24e'), COPPER = hex('#b5703f');
  const mag = c => {
    if (c.face === 'bottom') return mul(MAG, 0.6);
    if (c.face === 'top') return mul(MAGTOP, c.ex < px ? 1.5 : 1);
    let col = mul(MAG, G(c, 0.04));
    if (!isPanel(c)) return mul(col, 0.74);                                     // spine
    if (c.ev < px) return mul(col, 1.3);                                        // feed-lip edge
    if (Math.floor(c.eu / px) === 1) col = mul(col, 0.8);                      // rib
    return col;
  };
  const lips = c => (c.face !== 'top' ? mul(MAGTOP, isPanel(c) ? 1.3 : 1) : (c.p[0] > c.box.x + c.box.w - px ? COPPER : BRASS));
  for (const cx of [-2.625, -0.875, 0.875, 2.625]) {
    B.push(box('body', [cx - 0.75, 5.5, -3.875], [cx + 0.75, 9, -3.375], mag, { tag: 'rifle mag' }));
    B.push(box('body', [cx - 0.5, 5.25, -3.75], [cx + 0.5, 5.5, -3.5], lips, { tag: 'rifle mag feed lips' }));
  }
  // two olive tabs hanging under the placard
  for (const x0 of [-2, 1]) B.push(box('body', [x0, 11, -3.875], [x0 + 1, 11.75, -3.375], c => edge(c, mul(TAB, G(c, 0.05)), { stitch: false }), { tag: 'placard tab' }));

  // ---------------- front pouches ----------------
  // wearer's right: pale sage double mag pouch - two separate flaps (a thin dark gap between them) covering about two
  // thirds of each pouch, a khaki pull tab hanging from the middle of each flap's lower edge
  const sage = (c, k = 1) => mul(mix(fab(c, 1, 0.05, 3.3), SAGE, 0.78), k);   // faded sage, faint pattern showing
  const sageBody = c => {
    let col = sage(c);
    if (c.face === 'front') {
      const i = Math.floor(c.eu / px), y = c.p[1], n = Math.round(c.box.h / px);
      const j = Math.floor((y - c.box.y) / px);
      if (i === 1 && y >= 9.5 && y < 10.25) return mul(PULL, y < 10 ? 1.02 : 0.9);   // pull tab under the flap
      if (j === n - 1) return mul(col, 0.8);
      return col;
    }
    return edge(c, mul(col, 0.9), { stitch: false });
  };
  const sageFlap = c => {
    let col = sage(c, 1.06);
    if (c.face === 'top') return mul(col, 1.08);
    if (c.face !== 'front') return mul(col, 0.82);
    const j = Math.floor((c.p[1] - c.box.y) / px), n = Math.round(c.box.h / px);
    if (j === 0) return mul(col, 1.1);
    if (j === n - 1) return mul(col, 0.78);                                     // shadowed lower edge of the flap
    return col;
  };
  for (const cx of [-2.5, -0.5]) {
    B.push(box('body', [cx - 0.75, 7.25, -4.75], [cx + 0.75, 10.75, -4], sageBody, { tag: 'double mag pouch' }));
    B.push(box('body', [cx - 0.875, 7, -5], [cx + 0.875, 9.5, -4], sageFlap, { tag: 'mag pouch flap' }));
  }
  // wearer's left: MultiCam admin / GP pouch (taller than the mag pouches, its top level with the mag bodies), dark
  // zipper along its top, three MOLLE rows, trauma shears tucked in over them
  const ZIP = hex('#2a2c28');
  const gp = c => {
    if (c.face === 'top') return c.p[2] < c.box.z + 0.5 ? mul(ZIP, 1.3) : fab(c, 1.05, 0.05, 5.1);
    let col = fab(c, 1, 0.05, 5.1);
    if (c.face === 'front') {
      if (c.p[1] < c.box.y + px) return mix(mul(col, 1.1), ZIP, 0.5);           // zipper along the top edge
      const w = webbing(c, col, 7.25, 3, 0.75, 4.25);
      if (w) return w;
    }
    return edge(c, col);
  };
  B.push(box('body', [0.75, 6.25, -5], [4.25, 11.25, -4], gp, { tag: 'GP pouch' }));
  // trauma shears (flat cut-out on the pouch front): the big black finger ring up at the pouch's outer top corner, the
  // smaller ring below it toward the middle, touching at the pivot, and the grey blades running down from the pivot
  const shearsPaint = (black, blade) => {
    const SH = ['...###', '...#.#', '...###', '###B..', '#.#BB.', '###BB.', '....B.', '....B.'];
    return c => {
      if (c.face !== 'front') return null;
      const i = Math.min(5, Math.floor(c.eu / px)), j = Math.min(7, Math.floor(c.ev / px));
      const ch = SH[j][i];
      if (ch === '.') return null;
      if (ch === 'B') return mul(blade, (j === 3 ? 0.8 : 1) * (i === 3 ? 1.1 : 0.95));
      return mul(black, j === 0 || j === 3 ? 1.4 : 1.05);                      // lit top edges of the rings
    };
  };
  B.push(box('body', [1.25, 7, -5.25], [4.25, 11, -5], shearsPaint(BLACK, hex('#7d8285')), { tag: 'trauma shears' }));
  // light grey lanyard tied to the shears, hanging just past the pouch's lower edge
  B.push(box('body', [3.25, 10.75, -5.5], [3.75, 11.75, -5],c => (c.face === 'back' ? null : mul(hex('#b9bdb8'), G(c, 0.05) * (c.face === 'front' ? (c.p[1] >= 11.25 ? 0.9 : 1) : 0.8))), { tag: 'shears lanyard' }));
  return { S: K.S, px: K.px, cell: K.cell, boxes: B };
};
