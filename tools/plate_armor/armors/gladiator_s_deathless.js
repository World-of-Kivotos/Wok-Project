// ================= FORT Gladiator-S plate carrier ("Death is inevitable" / 死亡不可避免) — EFT reference =================
// tarkov.dev render 69d26ff4b855150a70092b8c (slug fort-gladiator-s-plate-carrier-death-is-inevitable; 7.12 kg, arm,
// neck, side and groin soft armour). The Gray Gladiator-S carrier (armors/gladiator_s_gray.js: same slate grey-green
// Cordura, tall rolled collar narrowing to a V point at the front with its webbing tab, MOLLE cummerbund wings) built
// up into the full kit and splashed with dried blood: big flared shoulder protectors (silver tape strip on top, dark
// teal lining, a webbing loop round the upper arm); a silver-grey panel across the upper chest under the collar; three
// open-top mag pouches whose fronts are shotgun-shell elastic panels (orange and black 12-gauge shells, bright brass
// heads toward the wearer's left, some loops empty) holding two black ribbed AK mags and a bundle of banknotes; a red
// signal flare strapped at the wearer's right front corner beside the pouches; a rolled MultiCam bundle strapped across under the
// pouches; a long two-part groin protector (a broad upper flap with rounded corners, a narrower lower flap in front of it
// with a round bottom).
ARMORS.gladiator_s_deathless = function (mode) {
  const K = kit('M');                          // always the 2x style, whatever mode is asked for
  const { edge, G, isPanel, isSide, px } = K;
  const B = [];

  // ---------------- palette (albedo; lit front faces render darker) ----------------
  const GRY = hex('#6b7a72'), WORN = hex('#89968f'), DIRT = hex('#565f55');   // slate grey-green Cordura
  const BLOOD = hex('#6e2822');                // dried blood
  const LINING = hex('#333b37'), TEALIN = hex('#2b3c3c');   // collar lining / shoulder-shell lining
  const MESH = hex('#6f6049');                 // brown padded mesh inside the panels
  const STRAP = hex('#25282c');                // black webbing and buckles
  const TAPE = hex('#b7bcbb');                 // silver tape / light grey panel
  const md = (v, m) => ((v % m) + m) % m;

  // fabric: slow tone drift + soft darker dirt blotches + lighter wear; `o` shifts the pattern per piece of cloth
  const fab = (c, k = 1, amt = 0.06, o = 0) => {
    const q = snap([c.p[0] + o, c.p[1] + o * 0.6, c.p[2] - o * 0.4], px);
    const tone = 0.95 + 0.1 * fbm(q[0] * 0.3 + 7, q[1] * 0.3 + 3, q[2] * 0.3 + 1);
    const d = sm(clamp01((fbm(q[0] * 0.45 + 11, q[1] * 0.4 + 5, q[2] * 0.45 + 17) - 0.55) * 3));
    const w = sm(clamp01((fbm(q[0] * 0.7 + 31, q[1] * 0.7 + 9, q[2] * 0.7 + 3) - 0.64) * 3.5));
    return mul(mix(mix(GRY, DIRT, d * 0.6), WORN, w * 0.32), k * tone * G(c, amt) * (1.04 - 0.008 * Math.max(0, Math.min(16, c.p[1]))));
  };
  // dried blood: soft 1-3 px smears (amount per piece: the collar, groin and shoulders carry the most)
  const blood = (c, col, amt = 1) => {
    const q = snap(c.p, px);
    const n = fbm(q[0] * 0.55 + 41, q[1] * 0.5 + 13, q[2] * 0.55 + 29);
    const t = sm(clamp01((n - (0.66 - 0.06 * amt)) * 5));
    return t > 0 ? mix(col, mul(BLOOD, 0.9 + 0.2 * n), t * 0.7) : col;
  };
  const webbing = (c, col, y0, rows, h0, h1, { side = false } = {}) => {
    if (!(isPanel(c) || (side && isSide(c)))) return null;
    const h = isPanel(c) ? c.p[0] : c.p[2];
    if (h < h0 || h > h1) return null;
    for (let i = 0; i < rows; i++) {
      const dy = c.p[1] - (y0 + i * 1.5);
      if (dy >= 0 && dy < 1) {                                                 // darker webbing tape, cloth between rows
        if (md(h - h0, 1.5) < 0.5) return mul(col, dy < 0.5 ? 0.92 : 0.82);    // light thread bar tacks (subtle)
        return mul(col, dy < 0.5 ? 0.8 : 0.7);
      }
    }
    return null;
  };

  // ---------------- carrier ----------------
  // front plate bag: light grey panel across the upper chest under the collar, MOLLE below it (mostly behind the gear)
  const bag = c => {
    if (c.face === 'top') return mul(MESH, G(c, 0.1));
    if (c.face === 'back') return mul(MESH, 0.9);
    const col = fab(c);
    if (c.face === 'front') {
      // (it sits low enough to show under the collar's V, the mags standing in front of its lower edge)
      if (c.p[1] >= 1.5 && c.p[1] < 3 && Math.abs(c.p[0]) < 3.25) return mul(TAPE, G(c, 0.05) * (c.p[1] < 2 ? 1.06 : c.p[1] >= 2.5 ? 0.86 : 1));
      return edge(c, blood(c, webbing(c, col, 3, 5, -4.25, 4.25) || col, 0.3));
    }
    return edge(c, mul(col, 0.9));
  };
  B.push(box('body', [-4.25, 1, -3.25], [4.25, 11, -2.5], bag, { tag: 'front plate bag' }));
  const back = c => {
    if (c.face === 'top') return mul(MESH, G(c, 0.1));
    if (c.face === 'front') return mul(MESH, G(c, 0.08));
    const col = fab(c);
    return edge(c, blood(c, (c.face === 'back' && webbing(c, col, 2, 6, -3.75, 3.75)) || col, 0.15));
  };
  B.push(box('body', [-4.25, 0.5, 2.5], [4.25, 11, 3.25], back, { tag: 'back plate bag' }));
  const cumm = c => {
    const col = fab(c, 0.97, 0.06, 1.3);
    return edge(c, blood(c, webbing(c, col, 5, 4, -2.5, 2.5, { side: true }) || col, 0.3), { stitch: false });
  };
  B.push(box('body', [-4.5, 4.5, -2.75], [4.5, 10.5, 2.75], cumm, { tag: 'cummerbund' }));
  const buckle = c => mul(STRAP, c.face === 'top' ? 1.6 : c.face === 'front' ? (c.p[1] < 4.75 ? 1.35 : 1.05) : 0.9);
  for (const s of [-1, 1]) B.push(box('body', s < 0 ? [-4.75, 4.25, -3] : [4.25, 4.25, -3], s < 0 ? [-4.25, 5.25, -2.5] : [4.75, 5.25, -2.5], buckle, { tag: 'wing buckle' }));

  // ---------------- tall padded collar (as on the Gray, bloodied) ----------------
  const lin = c => mul(LINING, G(c, 0.08));
  const rim = c => blood(c, mul(mix(fab(c, 1, 0.05, 7.7), WORN, 0.35), 1.05), 1);
  const collarFab = c => blood(c, fab(c, 0.96, 0.06, 7.7), 1);
  const halfFront = s => c => {
    if (c.face === 'back' || c.face === 'bottom') return lin(c);
    if (c.face === 'top') return rim(c);
    // inner ends: only a thin fold shows in front of the bridge at the V's point
    if (c.face === (s < 0 ? 'left' : 'right')) return mul(collarFab(c), 0.74);
    const col = collarFab(c);
    if (c.face === 'front') {
      if (c.ev < px) return rim(c);
      if (c.ev > c.fh - px) return mul(col, 0.78);
    }
    return edge(c, col, { stitch: false });
  };
  // front halves slope down toward the middle (the V of the render); their outer ends run over the black strap's top
  // out to the side walls so the roll reads as one piece from the front
  for (const s of [-1, 1]) {
    const [f0, f1] = s < 0 ? [-4.5, -0.5] : [0.5, 4.5];
    B.push(box('body', [f0, -0.5, -4.75], [f1, 1.25, -3.25], halfFront(s), { tag: 'collar front half', rot: [0, 0, s * -14], pivot: [s * 3.5, 1.25, -4] }));
  }
  // bridge behind the halves' inner ends (a little back from their front) so the roll's front is one continuous piece
  // narrowing to a point under the chin, where the webbing tab comes out; it closes the gap up to the head
  B.push(box('body', [-1.25, 0, -4.5], [1.25, 1.5, -3.75], c => {
    if (c.face === 'back') return lin(c);
    if (c.face === 'top') return rim(c);
    const col = collarFab(c);
    if (c.face === 'bottom') return mul(col, 0.6);
    if (c.face !== 'front') return mul(col, 0.8);
    if (c.ev < px) return rim(c);
    return c.ev > c.fh - px ? mul(col, 0.78) : col;
  }, { tag: 'collar front bridge' }));
  // black shoulder straps coming out from under the collar roll onto the front's top corners, each with a side-release
  // buckle just below the roll
  const strap = c => mul(STRAP, G(c, 0.06) * (c.face === 'front' ? 1 : c.face === 'top' ? 1.3 : 0.85));
  const sbuckle = c => mul(STRAP, c.face === 'top' ? 1.7 : c.face === 'front' ? (c.ev < px ? 1.45 : 1.15) : 0.95);
  for (const s of [-1, 1]) {
    const X = (a, b) => (s < 0 ? [a, b] : [-b, -a]);
    const [a0, a1] = X(-4.5, -3.5), [u0, u1] = X(-4.625, -3.375);
    B.push(box('body', [a0, -0.25, -3.75], [a1, 2.5, -3.25], strap, { tag: 'shoulder strap' }));
    B.push(box('body', [u0, 1.25, -4], [u1, 2.25, -3.5], sbuckle, { tag: 'shoulder strap buckle' }));
  }
  const tab = c => {
    const col = fab(c, 0.92, 0.06, 5.5);
    if (c.face !== 'front') return mul(col, 0.8);
    const j = Math.floor((c.p[1] - c.box.y) / px);
    if (j === 2 || j === 4) return mul(col, 0.66);
    return mul(col, j === 1 || j === 3 ? 1.1 : 1);
  };
  // (it ends right on the middle mag's feed lips)
  B.push(box('body', [-0.5, 0.75, -3.75], [0.5, 2.75, -3.25], tab, { tag: 'collar tab' }));
  const wall = s => c => {
    if (c.face === (s < 0 ? 'left' : 'right')) return lin(c);
    if (c.face === 'bottom') return mul(lin(c), 0.85);
    if (c.face === 'top') return rim(c);
    if (c.ev < px) return rim(c);
    return edge(c, collarFab(c), { stitch: false });
  };
  // side walls flare out from the neck at the top and come down to meet the front halves (as on the Gray)
  for (const s of [-1, 1]) {
    const xa = s < 0 ? [-5.5, -4.5] : [4.5, 5.5];
    B.push(box('body', [xa[0], -2.25, -3.75], [xa[1], 0.25, 5.25], wall(s), { tag: 'collar side', rot: [0, 0, s * 12], pivot: [s * 4.5, -2.25, 0] }));
  }
  B.push(box('body', [-4.75, -3, 3.25], [4.75, 1, 5.5], c => {
    if (c.face === 'front' || c.face === 'bottom') return lin(c);
    if (c.face === 'top') return c.p[2] < 5 ? lin(c) : rim(c);
    if (c.face === 'back' && c.ev < px) return rim(c);
    return edge(c, collarFab(c), { stitch: false });
  }, { tag: 'collar back' }));

  // ---------------- three open-top mag pouches with shotgun-shell elastic fronts ----------------
  const POUCH = hex('#4f5954'), ELASTIC = hex('#3a403d');
  const HULL = { o: hex('#c46f2c'), k: hex('#232323') }, BRASSH = hex('#e2d23c');
  const pouch = c => {
    if (c.face === 'top') return c.ex < px ? mul(POUCH, 1.3) : mul(LINING, 0.6);   // open mouth: light rim, dark inside
    const col = mul(POUCH, G(c, 0.06));
    return edge(c, blood(c, c.face === 'front' ? col : mul(col, 0.9), 0.3), { stitch: false });
  };
  // shell loops: a shell row (hull toward the wearer's right, brass head on the left) then an elastic row, per pouch
  const LOAD = [['o', 'o', 'o', 'k'], ['o', 'o', '.', 'o'], ['o', 'o', 'k', '.']];
  const shells = load => c => {
    const j = Math.floor((c.p[1] - c.box.y) / px);
    const shell = j % 2 === 0 ? load[Math.min(3, j >> 1)] : '.';
    if (c.face === 'back') return null;
    if (c.face === 'top') return mul(ELASTIC, 1.25);
    if (c.face === 'bottom') return mul(ELASTIC, 0.7);
    if (isSide(c)) {                                                          // shell ends: brass heads on the left side
      if (shell === '.') return mul(ELASTIC, 0.85);
      return c.face === 'left' ? mul(BRASSH, 0.92) : mul(HULL[shell], 0.8);
    }
    if (shell === '.') return mul(ELASTIC, G(c, 0.05) * (j % 2 ? 0.92 : 1.12));
    const i = Math.floor(c.eu / px);
    return i === 2 ? BRASSH : mul(HULL[shell], G(c, 0.04) * (shell === 'k' ? 1.3 : 1));
  };
  // AK mags: black ribbed polymer, feed-lip block with a brass round (copper tip on the wearer's left); they stand about
  // as far out of the pouch mouth as they sit inside it, up to just under the silver panel
  const MAG = hex('#333336'), MAGTOP = hex('#1e1e20'), BRASS = hex('#caa24e'), COPPER = hex('#b5703f');
  const mag = c => {
    if (c.face === 'bottom') return mul(MAG, 0.6);
    if (c.face === 'top') return mul(MAGTOP, c.ex < px ? 1.5 : 1);
    let col = mul(MAG, G(c, 0.04));
    if (!isPanel(c)) return mul(col, 0.74);
    if (c.ev < px) return mul(col, 1.3);
    const i = Math.floor(c.eu / px), j = Math.floor(c.ev / px);
    if (i === 1) col = mul(col, 0.78);                                          // centre rib
    if (j % 3 === 2) col = mul(col, 0.9);                                       // moulded ribs across it
    return col;
  };
  const lips = c => (c.face !== 'top' ? mul(MAGTOP, isPanel(c) ? 1.3 : 1) : (c.p[0] > c.box.x + c.box.w - px ? COPPER : BRASS));
  // bundle of banknotes: pale green-beige face, darker rubber band, light paper edges on top
  const CASH = hex('#b9ba95');
  const cash = c => {
    if (c.face === 'top') return mul(hex('#d8d6bc'), G(c, 0.04));
    if (c.face === 'bottom') return mul(CASH, 0.6);
    const j = Math.floor((c.p[1] - c.box.y) / px);
    if (j === 2) return mul(hex('#4a3a2e'), 1.1);                               // rubber band
    const col = mul(CASH, G(c, 0.05) * (isPanel(c) ? 1 : 0.9));
    return isPanel(c) && Math.floor(c.eu / px) === 1 && j >= 1 && j !== 2 ? mul(col, 0.88) : col;   // printed middle
  };
  [-2.25, 0, 2.25].forEach((cx, k) => {
    B.push(box('body', [cx - 1, 5.75, -4.25], [cx + 1, 9.5, -3.25], pouch, { tag: 'mag pouch' }));
    B.push(box('body', [cx - 0.75, 6, -4.5], [cx + 0.75, 9.5, -4.25], shells(LOAD[k]), { tag: 'shell loops' }));
    if (k < 2) {
      B.push(box('body', [cx - 0.75, 3, -4], [cx + 0.75, 8.5, -3.5], mag, { tag: 'AK mag' }));
      B.push(box('body', [cx - 0.5, 2.75, -3.875], [cx + 0.5, 3, -3.625], lips, { tag: 'AK mag feed lips' }));
    } else B.push(box('body', [cx - 0.75, 3.5, -4], [cx + 0.75, 7.5, -3.5], cash, { tag: 'banknote bundle' }));
  });

  // ---------------- red signal flare strapped at the wearer's right front corner ----------------
  // a round red stick standing on the plate bag's front corner, pressed against the first shell pouch's side (out of
  // the arm's way): lighter ribbed cap on top, two black strap bands round it, shaded like a cylinder
  const RED = hex('#c3302a');
  const flare = c => {
    const j = Math.floor((c.p[1] - c.box.y) / px);
    if (c.face === 'top') return mul(RED, 0.8);
    if (c.face === 'bottom') return mul(RED, 0.55);
    if (j === 2 || j === 5) return mul(STRAP, c.face === 'front' ? (j === 2 ? 1.3 : 1.15) : 1);         // strap bands
    const roll = c.face === 'front' ? (Math.floor(c.eu / px) === 0 ? 1.06 : 0.9) : c.face === 'back' ? 0.6 : 0.78;
    if (j === 0) return mul(mix(RED, hex('#e0605a'), 0.35), roll);                                      // lighter cap
    return mul(RED, G(c, 0.04) * roll * (j === 6 ? 0.88 : 1));
  };
  B.push(box('body', [-4.25, 5.75, -4.25], [-3.25, 9.25, -3.25], flare, { tag: 'signal flare' }));

  // ---------------- rolled MultiCam bundle strapped across under the pouches ----------------
  const camoAt = p => layers(snap(p, px).map(v => v * 0.45), 0, '#9b9771',
    [['#b4ae88', 0.55, 0.58, 3], ['#6d7b4b', 0.62, 0.56, 17], ['#80704c', 0.7, 0.63, 41], ['#58603b', 0.9, 0.66, 77]]);
  const roll = c => {
    const x = c.p[0];
    if (x >= 0.5 && x < 1.5) return mul(STRAP, isSide(c) ? 0.9 : 1.15);        // strap round the roll
    const col = mul(camoAt(c.p), G(c, 0.05));
    if (isSide(c)) return mul(col, 0.72);                                       // rolled ends
    return mul(col, c.face === 'top' ? 1.1 : 1);
  };
  octa(B, 'body', [0, 10.25, -4], [5.5, 1.5, 1.5], 'x', roll, { tag: 'MultiCam roll' });

  // ---------------- two-part groin protector ----------------
  // broad upper flap (sides tapering a little, rounded lower corners) + a narrower lower flap in front of it with a
  // round bottom; darker bound edges, lots of dried blood
  const GT = 10.75, GM = 14, GB = 16.5;
  const groin = (hw0, hw1, y0, y1, r, bl) => c => {
    const ax = Math.abs(c.p[0]), y = Math.min(c.p[1], y1 - px / 2);
    const hw = hw0 + (hw1 - hw0) * clamp01((y - y0) / (y1 - y0));
    const dy = y - (y1 - r);
    const lim = dy > 0 ? hw - r + Math.sqrt(Math.max(0, r * r - dy * dy)) : hw;
    if (isPanel(c) || c.face === 'bottom') { if (ax > lim + 1e-6) return null; }
    else if (isSide(c) && lim < hw0 - px / 2) return null;                     // sides only where the flap is full width
    if (c.face === 'back') return mul(fab(c, 0.7), 0.8);
    let col = blood(c, fab(c, 0.98, 0.06, 3.7), bl);
    if (c.face === 'top') return mul(col, 1.05);
    if (c.face !== 'front') return mul(col, 0.8);
    if (ax > lim - px || y > y1 - px) col = mul(col, 0.8);                     // bound edge
    return col;
  };
  B.push(box('body', [-3.25, GT, -3.75], [3.25, GM, -3.25], groin(3.25, 2.75, GT, GM, 1, 1.2), { tag: 'groin upper flap' }));
  B.push(box('body', [-2.25, 12.5, -4], [2.25, GB, -3.5], groin(2.25, 2.25, 12.5, GB, 1.5, 1.4), { tag: 'groin lower flap' }));

  // ---------------- shoulder protectors (follow the arms) ----------------
  // flared shells: cap over the shoulder with a silver tape strip, outer shell turned out, front / back sides; dark
  // teal lining inside; a webbing loop round the upper arm under the shell
  for (const s of [-1, 1]) {
    const part = s < 0 ? 'right_arm' : 'left_arm';
    const X = (a, b) => (s < 0 ? [a, b] : [-b, -a]);
    const out = s < 0 ? 'right' : 'left', inn = s < 0 ? 'left' : 'right';
    const shell = (inward, bottomLining) => c => {
      if (c.face === inward) return mul(TEALIN, G(c, 0.08));
      if (c.face === 'bottom') return bottomLining ? mul(TEALIN, G(c, 0.08)) : mul(fab(c, 0.7, 0.06, 9.1), 0.9);
      let col = blood(c, fab(c, 1, 0.06, 9.1), 1);
      if (c.face !== 'top' && c.ev > c.fh - px) return mul(col, 0.72);           // bound lower edge
      if (c.face === 'top' && Math.abs(c.p[2]) < 1.5) return mul(TAPE, G(c, 0.05) * (c.p[2] < -0.75 ? 1.05 : 0.95));   // silver tape strip
      if (c.face === out && c.ev < 1 && Math.abs(c.p[2]) < 1.5) return mul(TAPE, G(c, 0.05) * 0.9);
      return edge(c, col, { stitch: false });
    };
    const [c0, c1] = X(-4, -0.5);
    B.push(box(part, [c0, -2.75, -3], [c1, -2, 3], shell('bottom', true), { tag: 'shoulder cap' }));
    const [o0, o1] = X(-4.25, -3.5);
    B.push(box(part, [o0, -2.25, -3], [o1, 4.25, 3], shell(inn, false), { tag: 'shoulder outer', rot: [0, 0, s * -10], pivot: [s < 0 ? -3.9 : 3.9, -2.25, 0] }));
    // front / back sides end at the carrier's edge (reaching into the plate bags would put their faces in the bags'
    // front and back planes)
    for (const z of [[-3.25, -2.75], [2.75, 3.25]]) {
      const [f0, f1] = X(-3.75, 0.75);
      B.push(box(part, [f0, -2.25, z[0]], [f1, 2.5, z[1]], shell(z[0] < 0 ? 'back' : 'front', false), { tag: 'shoulder side' }));
    }
    // webbing loop hanging round the upper arm from the shell's lower edge (its outer side tucked up under the flared
    // shell, loose of the sleeve; open top / bottom / armpit side)
    const [l0, l1] = X(-4.75, 0.5);
    B.push(box(part, [l0, 4, -2.5], [l1, 4.75, 2.5], c => (c.face === 'top' || c.face === 'bottom' || c.face === inn ? null : mul(fab(c, 0.78, 0.06, 6.6), c.face === out ? 0.9 : 1)), { tag: 'arm loop strap' }));
  }
  return { S: K.S, px: K.px, cell: K.cell, boxes: B };
};
