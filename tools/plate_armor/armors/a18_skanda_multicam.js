// ================= Ars Arma A18 Skanda plate carrier (MultiCam) — EFT reference (tarkov.dev 5d5d87f786f77427997cfaef) =================
// In EFT this is an armored rig: the gear below is part of the item model. MultiCam carrier (light khaki ground, olive /
// brown shapes); padded shoulder straps whose underside is a dark green 3D mesh; a brass-olive D-ring at the foot of the
// wearer's right strap and a side-release buckle high on the wearer's left strap; upper chest: THREE coyote-khaki MOLLE rows
// between two brassy quick-release buckles, a big black locking carabiner (red gate ring) hooked over the top row on the
// wearer's right of centre (its back bar shows only over the middle row), two pale-gold light tubes with silver caps woven
// through the rows (under the top row, over the middle one, under the bottom one) on the wearer's left of centre, a black
// speaker-mic (PTT) at the wearer's left top corner with its cord running down into the corner flap pouch, a black radio
// (stub antenna) in a coyote pouch on the wearer's left side; MultiCam placard with open-top laced pouches (darker,
// greener khaki, olive shock cord, two webbing straps): a single black polymer mag (a black CAT tourniquet with a red tab
// and a grey strap in front of it, windlass rod slanting across, a black buckle at the foot of its strap) and a double
// pouch with two black mags; a narrow sleeve with a black pen and a red canister (white cap) in its middle; wearer's right
// corner: a tall coyote flap pouch, a smaller coyote flap pouch hung in front of it at mid height with two black pistol-mag
// tops rising behind it, and the black finger loop of trauma shears standing up out of the sleeve; a big zipped coyote GP
// pouch on the wearer's right flank; wearer's left corner: a tall coyote flap pouch. No groin / arm armour.
ARMORS.a18_skanda_multicam = function (mode) {
  const K = kit('M');                                   // always the 2x style, whatever mode is asked for
  const { edge, G, isPanel, isSide, px } = K;          // px = 0.5 (one art cell)
  const B = [];
  const md = (v, m) => ((v % m) + m) % m;

  // ---------- colours (sampled off the EFT render) ----------
  const WEB = hex('#8e8261');                          // coyote-khaki webbing: browner and more even than the camo
  const LINING = hex('#1f231b'), MESH = hex('#222a1e'), MESHL = hex('#34422f');
  const COY = hex('#9a8159'), COYG = hex('#716b4a');   // coyote pouches (the render lights their shadows green)
  const CORD = hex('#4a5631'), OLV = hex('#7d7a55');   // olive shock cord; olive cast of the laced pouches
  const BUCK = hex('#9a9866'), SLOT = hex('#3b392c');  // brassy olive-khaki buckles, dark window
  const DRING = hex('#8f8a5a');                        // brass-olive D-ring
  const MAGC = hex('#2a2e30'), BLACK = hex('#202325'), MAGTOP = hex('#2b2e33');
  const RED = hex('#ab1d2c'), GREY = hex('#a2a6a5'), TUBE = hex('#e2c46e'), CAP = hex('#e8e8e2'), WHITE = hex('#dcdbd1');
  const BRASS = hex('#caa24e'), COPPER = hex('#b5703f');

  // MultiCam as the render shows it: the lit camo runs from dark brown to cream (khaki ground, clear green and brown
  // clouds of 1.5-3 px, olive / dark-brown shapes, cream highlights) in the same range as the sibling MultiCam armors;
  // the small high-frequency layers sit on high thresholds so no lone specks compete with the gear
  const camo = p => layers(snap(p, 0.5).map(v => v * 0.55), 0, '#a69c74', [
    ['#c2b993', 0.55, 0.6, 3], ['#76804f', 0.62, 0.58, 17], ['#84704c', 0.7, 0.62, 41],
    ['#5a5438', 0.9, 0.67, 77], ['#4a3c2d', 1.1, 0.76, 5], ['#cdc6a3', 1.2, 0.8, 91]]);
  const mc = (c, k = 1) => mul(camo(c.p), k * G(c, 0.05));
  // plain fabric: faint low-frequency mottling + grain
  const fab = (c, base, amt = 0.06) => {
    const q = snap(c.p, 0.5);
    const n = fbm(q[0] * 0.45 + 7, q[1] * 0.45 + 1, q[2] * 0.45 + 3);
    return mul(base, (0.95 + 0.1 * n) * G(c, amt));
  };
  // coyote pouch fabric: warm coyote on the lit faces, drifting green in the low-frequency shadows like the render
  const coy = (c, k = 1) => {
    const q = snap(c.p, 0.5);
    const n = fbm(q[0] * 0.35 + 3, q[1] * 0.35 + 9, q[2] * 0.35 + 5);
    return mul(mix(COY, COYG, sm(clamp01((n - 0.45) * 2.2)) * 0.7), k * G(c, 0.06));
  };
  const mesh = c => {                                   // dark green spacer mesh with faint lighter bands (2 px period)
    const q = snap(c.p, 0.5);
    return mul(md(q[0] + q[1] + q[2], 2) < 0.6 ? mix(MESH, MESHL, 0.6) : MESH, G(c, 0.08));
  };
  const lining = c => mul(LINING, G(c, 0.1));

  // MOLLE rows: a 1 px khaki webbing tape (lit upper half, a bar-tack channel every 3 px) with a shadowed half-pixel of
  // camo under it; h = horizontal coordinate along the face
  // (printed: the tape carries the camo toned toward the khaki webbing, so a panel covered in rows still reads MultiCam)
  const webRows = (c, col, rows, h0, h1, h, printed = false) => {
    if (h < h0 || h > h1) return null;
    const y = c.p[1];
    const W = printed ? mix(camo(c.p), WEB, 0.4) : WEB;
    for (const r of rows) {
      const dy = y - r;
      if (dy >= 0 && dy < 1) return mul(W, G(c, 0.04) * (md(h - h0 + 1, 3) < 0.5 ? 0.76 : dy < 0.5 ? 1.12 : 0.97));
      if (dy >= 1 && dy < 1.5) return mul(col, 0.52);
    }
    return null;
  };
  // upper chest: three 1 px rows stacked tight (lit upper cell, darker lower cell), one shadow cell under the last row
  const ROWS = [1.5, 2.5, 3.5];
  const rowAt = y => ROWS.find(r0 => y >= r0 && y < r0 + 1);
  const chestWeb = (c, y, k = 1) => mul(WEB, G(c, 0.04) * k * (y - rowAt(y) < 0.5 ? 1.1 : 0.86));
  const chestRows = (c, col) => {
    const h = c.p[0], y = c.p[1];
    if (h < -3 || h > 3) return null;
    if (rowAt(y) != null) return md(h + 4, 3) < 0.5 ? mul(WEB, 0.76 * G(c, 0.04)) : chestWeb(c, y);
    if (y >= 4.5 && y < 5) return mul(col, 0.52);
    return null;
  };

  // ---------------- carrier ----------------
  const frontBag = c => {
    if (c.face === 'top' || c.face === 'back') return lining(c);
    const col = mc(c);
    if (c.face === 'front') { const w = chestRows(c, col); if (w) return w; }
    return edge(c, col, { stitch: false });
  };
  // the original is a tall rig: measured against its width, the placard and the pouches hang down to the belt, so the plate
  // bags run to just above it (the placard and pouches then lie on the bag, not on a gap)
  B.push(box('body', [-4.25, 0.75, -3.25], [4.25, 11.5, -2.5], frontBag, { tag: 'front plate bag' }));
  const backBag = c => {
    if (c.face === 'top' || c.face === 'front') return lining(c);
    const col = mc(c);
    if (c.face === 'back') { const w = webRows(c, col, [2.5, 4, 5.5, 7, 8.5], -3.25, 3.25, -c.p[0], true); if (w) return w; }
    return edge(c, col, { stitch: false });
  };
  B.push(box('body', [-4.25, 0.75, 2.5], [4.25, 11.5, 3.25], backBag, { tag: 'back plate bag' }));
  // cummerbund: MOLLE on the sides, a dark coyote loop patch on the wearer's right side near the front
  const cumm = c => {
    const col = mc(c, 0.96);
    if (c.face === 'right' && c.p[2] < -1 && c.p[1] >= 5.5 && c.p[1] < 7) return edge(c, fab(c, hex('#4a4130')), { stitch: false });
    if (isSide(c)) { const w = webRows(c, col, [5.5, 7, 8.5], -2.25, 2.25, c.p[2], true); if (w) return w; }
    return edge(c, col, { stitch: false });
  };
  B.push(box('body', [-4.5, 5, -2.75], [4.5, 10, 2.75], cumm, { tag: 'cummerbund' }));
  // drag handle on the back bag's top edge (the camo loop seen between the straps)
  B.push(box('body', [-1.25, -0.25, 3.25], [1.25, 1.25, 3.75], c => (c.face === 'front' ? lining(c) : edge(c, mc(c), { stitch: false })), { tag: 'drag handle' }));

  // ---------------- padded shoulder straps (camo shell, dark green mesh underneath) ----------------
  const hump = c => {
    if (c.face === 'bottom') return mesh(c);
    if (c.face === 'top') return mul(mc(c), 1.06);
    if (isPanel(c)) return c.ev < c.fh / 2 ? mul(mc(c), 0.92) : mesh(c);        // pad end: camo shell over the mesh pad
    const inner = (c.p[0] < 0) === (c.face === 'left');                          // face turned toward the neck
    if (inner) return mesh(c);
    return c.ev > c.fh - px ? mesh(c) : edge(c, mc(c), { stitch: false });
  };
  const riser = c => {
    if (c.face === 'bottom') return mesh(c);
    const inner = (c.p[0] < 0) === (c.face === 'left');
    if (isSide(c) && inner) return mesh(c);
    return edge(c, mc(c), { stitch: false });
  };
  for (const s of [-1, 1]) {
    const X = (a, b) => (s < 0 ? [a, b] : [-b, -a]);
    const [b0, b1] = X(-5.75, -4.25), [h0, h1] = X(-5.5, -4.5);
    // (down to the slim sleeve's top so no daylight shows under the pad on a slim skin; on a wide arm the lower part
    // sits inside the sleeve)
    B.push(box('body', [b0, -1.25, -3.5], [b1, 0.25, 3.5], hump, { tag: 'shoulder pad' }));
    B.push(box('body', [h0, -1.75, -3.25], [h1, -0.75, 3.25], hump, { tag: 'shoulder pad crown' }));
    const [r0, r1] = X(-4.5, -1.75);
    B.push(box('body', [r0, -0.25, -3.75], [r1, 1.25, -2.75], riser, { tag: 'strap front' }));
    B.push(box('body', [r0, -0.25, 2.75], [r1, 1.5, 3.75], riser, { tag: 'strap back' }));   // a little proud of the pad end
  }
  // flat cut-out sprite: '#' cells are drawn, '.' cells are see-through; the side / top faces follow the outer cells, the
  // back face is left open (the sprite lies on whatever is behind it)
  const cutout = (P, colFn, sideK = 0.8) => c => {
    if (c.face === 'back') return null;
    const n = P[0].length, m = P.length;
    const ci = Math.min(n - 1, Math.max(0, Math.floor(c.eu / c.fw * n)));
    const cj = Math.min(m - 1, Math.max(0, Math.floor(c.ev / c.fh * m)));
    if (isPanel(c)) return P[cj][ci] === '#' ? colFn(ci, cj) : null;
    if (isSide(c)) { const i = c.face === 'right' ? 0 : n - 1; return P[cj][i] === '#' ? mul(colFn(i, cj), sideK) : null; }
    const j = c.face === 'top' ? 0 : m - 1;
    return P[j][ci] === '#' ? mul(colFn(ci, j), c.face === 'top' ? 1.3 : 0.6) : null;
  };
  // brass-olive D-ring on the foot of the wearer's right strap: straight bar on top, the ring's legs below it
  B.push(box('body', [-3.75, 0.25, -4], [-2.25, 1.25, -3.75], cutout(['###', '#.#'], (i, j) => mul(DRING, j === 0 ? 1.3 : 0.88)), { tag: 'D-ring' }));
  // brassy side-release buckle: bright frame (lit top bar, shaded bottom bar) round a dark centre window; on a 1 px buckle
  // the window becomes a shadowed slot row
  const buckle = c => {
    if (c.face === 'back') return null;
    if (!isPanel(c)) return mul(BUCK, c.face === 'top' ? 1.25 : 0.72);
    const n = Math.round(c.fw / px), m = Math.round(c.fh / px);
    const i = Math.min(n - 1, Math.floor(c.eu / px)), j = Math.min(m - 1, Math.floor(c.ev / px));
    if (n >= 3 && m >= 3 && i > 0 && i < n - 1 && j > 0 && j < m - 1) return SLOT;
    if (n < 3 && j === 1) return mix(BUCK, SLOT, 0.55);
    return mul(BUCK, G(c, 0.03) * (j === 0 ? 1.3 : j === m - 1 ? 0.96 : 1.16));
  };
  // the wearer's left strap buckle sits high on the strap, on top of the shoulder: in Minecraft that is the front end of
  // the shoulder pad (the head hides the strap itself)
  B.push(box('body', [4.5, -1.25, -3.75], [5.5, -0.25, -3.5], buckle, { tag: 'strap buckle' }));

  // ---------------- upper chest gear ----------------
  // quick-release buckles at both ends of the rows, from the middle row down past the bottom row
  for (const x0 of [-3.75, 2.25]) B.push(box('body', [x0, 3, -3.5], [x0 + 1.5, 4.5, -3.25], buckle, { tag: 'chest buckle' }));
  // big black locking carabiner hooked over the top row: one-texel frame round a one-texel opening, rounded corners, red
  // gate ring and a lighter gate sleeve on its left bar; its right (back) bar runs under the top and bottom rows and only
  // shows over the middle row, so those cells carry the webbing
  const carab = c => {
    const t = px, inU = c.eu > t && c.eu < c.fw - t, inV = c.ev > t && c.ev < c.fh - t;
    if (c.face === 'back') return null;                                          // lies on the bag (seen through the opening it flickered)
    const y = c.p[1], r = rowAt(y), woven = r != null && r !== ROWS[1];
    if (isPanel(c)) {
      if (inU && inV) return null;
      if (!inU && !inV) return null;
      const j = Math.floor(c.ev / px);
      if (c.eu > c.fw - t && woven) return chestWeb(c, y);                       // back bar under the top / bottom row
      if (c.face === 'front' && c.eu < t && j === 2) return RED;
      if (c.face === 'front' && c.eu < t && (j === 3 || j === 4)) return mul(BLACK, 1.5);   // gate sleeve
      return mul(BLACK, c.ev < c.fh / 2 ? 1.25 : 1);
    }
    if ((c.face === 'top' || c.face === 'bottom') && !inU) return null;
    if (isSide(c) && !inV) return null;
    if (c.face === 'left' && woven) return chestWeb(c, y, 0.72);                 // webbing wrapped over the back bar
    if (c.face === 'right' && Math.floor(c.ev / px) === 2) return mul(RED, 0.8);
    return mul(BLACK, c.face === 'top' ? 1.4 : 0.9);
  };
  B.push(box('body', [-1.75, 1, -3.75], [-0.25, 4.5, -3.25], carab, { tag: 'carabiner' }));
  // two pale-gold light tubes with silver caps, woven through the rows: under the top row, over the middle row, under the
  // bottom row (front and side cells inside the top / bottom row carry the webbing)
  const tube = c => {
    if (c.face === 'top') return mul(CAP, 1.06);
    const y = c.p[1], r = rowAt(y);
    if (c.face === 'bottom') return mul(WEB, 0.6);
    if (r != null && r !== ROWS[1]) return c.face === 'front' ? chestWeb(c, y) : chestWeb(c, y, 0.72);
    if (y < 1) return mul(CAP, c.face === 'front' ? 1 : 0.8);
    return mul(TUBE, (c.face === 'front' ? 1.0 : 0.6) * G(c, 0.04));          // bright front, dark sides: reads as a rod
  };
  for (const x0 of [0.25, 1.25]) B.push(box('body', [x0, 0.5, -3.75], [x0 + 0.5, 4.5, -3.25], tube, { tag: 'light tube' }));
  // speaker-mic (PTT) under the wearer's left strap; its cord drops from the mic's lower edge into the corner flap pouch
  const ptt = c => {
    if (c.face === 'front') return c.ex >= px ? hex('#17181a') : mul(BLACK, c.ev < px ? 1.4 : 1.15);
    return mul(BLACK, c.face === 'top' ? 1.35 : 0.95);
  };
  B.push(box('body', [2.75, 1, -4], [4.25, 3, -3.25], ptt, { tag: 'PTT' }));
  const PCORD = hex('#2e3236');
  B.push(box('body', [3.875, 3, -3.75], [4.125, 5.75, -3.25], c => mul(PCORD, isPanel(c) ? G(c, 0.04) : 0.68), { tag: 'PTT cord' }));

  // ---------------- placard ----------------
  const placard = c => {
    if (c.face === 'back') return lining(c);
    const col = mc(c, 0.92);
    // (no rows on the strip behind the shears and the sleeve: the loop's opening shows plain camo)
    if (c.face === 'front') { const w = webRows(c, col, [5.5, 7, 8.5, 10, 11.5], -2.5, 3.25, c.p[0]); if (w) return w; }
    return edge(c, col, { stitch: false });
  };
  B.push(box('body', [-3.75, 5, -3.5], [3.25, 12.25, -3.25], placard, { tag: 'placard' }));
  // open-top laced mag pouches (9 texel rows, down to the belt like the original): a darker, greener khaki-toned MultiCam
  // than the coyote flap pouches; like the render, a wide band at the mouth (light elastic binding over a webbing strap),
  // a second 1 px webbing strap across the middle, and the olive shock cord laced up both edges of every mag slot (at
  // half-pixel cells a laced X only turned into a checkerboard, so the cords show as olive columns that stay unbroken where
  // they cross the straps); dark open mouth
  const PFAB = c => mul(mix(mix(camo(c.p), WEB, 0.65), OLV, 0.2), 0.88 * G(c, 0.04));
  const magPouch = posts => c => {
    if (c.face === 'top') return c.ex < px ? mul(PFAB(c), 1.15) : mul(LINING, 1.2);
    const col = PFAB(c);
    if (c.face !== 'front') return edge(c, mul(col, 0.85), { stitch: false });
    const i = Math.floor(c.eu / px), j = Math.floor(c.ev / px);
    if (j === 0) return mul(col, 1.14);                                          // elastic top binding
    if (j === 1 || j === 4 || j === 5) {                                          // webbing straps (the cord runs over them)
      const s = mul(WEB, G(c, 0.04) * (j === 5 ? 0.96 : 1.12));
      return posts.includes(i) ? mul(mix(s, CORD, 0.4), 0.9) : s;
    }
    if (posts.includes(i)) return mix(col, CORD, 0.62);                          // olive cord columns (edges, between mags)
    return mul(col, j >= 8 ? 0.86 : 1);
  };
  // magazines: black polymer, broad side forward, one darker rib, darker spine; feed lips with a brass round on top
  const magPaint = c => {
    if (c.face === 'bottom') return mul(MAGC, 0.6);
    if (c.face === 'top') return mul(MAGTOP, c.ex < px ? 1.4 : 1);
    let col = mul(MAGC, G(c, 0.04));
    if (!isPanel(c)) return mul(col, 0.74);
    if (c.ev < px) return mul(col, 1.4);
    const n = Math.max(1, Math.round(c.fw / px)), i = Math.floor(c.eu / px);
    if (i === Math.floor(n / 2)) col = mul(col, 0.8);
    return col;
  };
  const lips = c => (c.face !== 'top' ? mul(hex('#3a3e42'), isPanel(c) ? 1.1 : 0.85) : (c.eu > c.fw - px ? COPPER : BRASS));
  const mag = (x0, x1, y0, y1, z0, z1, tag, paint = magPaint) => {
    B.push(box('body', [x0, y0, z0], [x1, y1, z1], paint, { tag }));
    const w = x1 - x0, l0 = x0 + (w - (w >= 1.5 ? 1 : 0.5)) / 2;
    B.push(box('body', [l0, y0 - 0.5, z0 + 0.125], [x1 - (l0 - x0), y0, z1 - 0.125], lips, { tag: tag + ' feed lips' }));
  };
  // the mags stand out of their pouches by a little less than the pouch height, as in the render (3 px plus the feed lips
  // show, the lower half sits in the pouch); tops just under the bottom chest row. Like the render, the two pouches are
  // about equally wide and each shows one big AK-style mag face (2 px, a 1 px feed-lip block on top)
  // single pouch (wearer's right of centre) + its mag (the tourniquet hangs in front of its inner half)
  B.push(box('body', [-2.75, 7.75, -4.5], [0, 12.25, -3.5], magPouch([0, 5]), { tag: 'single mag pouch' }));
  mag(-2.5, -0.5, 4.75, 10.5, -4.25, -3.75, 'mag (single)');
  // double pouch (wearer's left of centre): two mags staggered one behind the other; the back one sits a little deeper
  // and higher, so only its inner edge and its top show as a sliver beside the front mag (its back face rests on the
  // pouch back and is left open)
  B.push(box('body', [0.25, 7.75, -4.5], [3.25, 12.25, -3.5], magPouch([0, 5]), { tag: 'double mag pouch' }));
  mag(1, 3, 4.75, 10.5, -4.25, -3.75, 'mag (double, front)');
  mag(0.375, 2.375, 4.5, 10.25, -4, -3.5, 'mag (double, back)', c => (c.face === 'back' ? null : magPaint(c)));

  // ---------------- tourniquet on the single pouch ----------------
  // the red tab stands above the pouch mouth in front of the mag (the tourniquet is deep enough to rest against the mag
  // there), the grey strap wraps it at the pouch binding, and the black strap runs down the pouch front into a black
  // buckle that ends just below the pouch, as in the render
  const tq = c => {
    if (c.face === 'top') return mul(RED, 1.2);
    const v = c.p[1] - c.box.y;
    if (v < px) return mul(RED, c.face === 'front' ? 1.1 : 0.8);                  // red tab on top
    let col = mul(BLACK, G(c, 0.05));
    if (c.face === 'front' && (c.eu < px || c.eu > c.fw - px)) col = mul(col, 1.5); // strap edges catch the light
    return isPanel(c) ? col : mul(col, 0.85);
  };
  B.push(box('body', [-1.5, 7, -5], [-0.25, 11.25, -4.25], tq, { tag: 'tourniquet' }));
  B.push(box('body', [-2.5, 7.75, -4.75], [-1.5, 8.25, -4.5], c => mul(GREY, isPanel(c) ? G(c, 0.04) : 0.75), { tag: 'TQ strap (grey)' }));
  // windlass rod: deep enough that its lower end still rests on the double pouch where it runs past the tourniquet
  B.push(box('body', [-0.75, 8.25, -5.25], [-0.25, 11.25, -4.5], c => mul(hex('#2c3032'), isPanel(c) ? 1.2 : 0.85), { tag: 'TQ windlass rod', rot: [0, 0, -28], pivot: [-0.5, 8.25, -4.875] }));
  // black buckle at the foot of the tourniquet strap: lies on the pouch front, a little proud of the strap
  const hangBuckle = c => {
    if (!isPanel(c)) return mul(BLACK, c.face === 'top' ? 1.4 : 0.85);
    const j = Math.floor(c.ev / px);
    return j === 1 ? hex('#0f1011') : mul(BLACK, j === 0 ? 1.45 : 1.15);
  };
  B.push(box('body', [-1.5, 11.25, -5.25], [-0.25, 12.75, -4.5], hangBuckle, { tag: 'TQ buckle' }));

  // ---------------- wearer's right front corner ----------------
  // narrow sleeve beside the single pouch, as long as the mag pouches: elastic bands in line with the pouch binding and
  // middle strap, the black pen in its top, a red canister with a white cap in the middle (clear of the small flap pouch)
  const sleeve = c => {
    if (c.face === 'top') return c.ex < px ? mul(PFAB(c), 1.1) : mul(LINING, 1.2);
    let col = PFAB(c);
    if (c.face !== 'front') return mul(col, 0.8);
    const j = Math.floor(c.ev / px);
    if (j === 0 || j === 3 || j === 7 || j === 8) return mul(mix(col, WEB, 0.6), j === 8 ? 0.98 : 1.1); // elastic bands
    if (j === 1 || j === 2) return mul(BLACK, j === 1 ? 1.3 : 1.1);              // pen body
    if (j === 4) return WHITE;                                                    // canister cap
    if (j === 5 || j === 6) return mul(RED, j === 5 ? 1 : 0.86);                  // red canister
    return mul(col, j >= 11 ? 0.8 : 0.9);
  };
  B.push(box('body', [-3.5, 6.25, -4.25], [-2.75, 12.25, -3.5], sleeve, { tag: 'pen / canister sleeve' }));
  // coyote flap pouches: tall one at the corner (flap with a stitched loop panel and a dark pull tab), a smaller one hung
  // in front of it at mid height
  const coyPouch = (open = false) => c => {
    if (c.face === 'top') return open ? (c.ex < px ? coy(c, 1.1) : mul(LINING, 1.2)) : coy(c, 1.1);
    if (c.face === 'bottom') return coy(c, 0.6);
    return edge(c, coy(c, isPanel(c) ? 1 : 0.84), { stitch: false });
  };
  const flap = c => {
    if (c.face === 'back') return coy(c, 0.6);
    let col = coy(c, 1.08);
    if (c.face === 'top') return mul(col, 1.08);
    if (c.face !== 'front') return mul(col, 0.8);
    const i = Math.floor(c.eu / px), j = Math.floor(c.ev / px), n = Math.round(c.fw / px), m = Math.round(c.fh / px);
    if (j === m - 1) return mul(col, 0.82);                                       // folded lower edge
    if (n >= 3 && i >= 1 && i <= n - 2 && j >= 1 && j <= m - 3) col = mul(col, 0.9); // stitched loop panel
    return col;
  };
  const tab = c => mul(hex('#4d4633'), (isPanel(c) ? 1 : 0.8) * G(c, 0.06));
  // (lengths follow the render: the tall pouches reach below the belt, their flaps cover a bit over half of them)
  B.push(box('body', [-5.25, 5.25, -4.25], [-4.25, 13.25, -2.75], coyPouch(), { tag: 'flap pouch (right)' }));
  B.push(box('body', [-5.375, 5, -4.5], [-4.125, 9, -3.5], flap, { tag: 'flap (right)' }));
  B.push(box('body', [-5.125, 9, -4.5], [-4.625, 10, -4.25], tab, { tag: 'pull tab (right)' }));
  // two black pistol-mag tops rising from behind the small flap pouch, in front of the tall pouch's flap (staggered); their
  // lower ends go down into the small pouch
  const pmag = c => {
    if (c.face === 'top') return mul(MAGC, 1.5);
    if (c.face === 'bottom') return mul(MAGC, 0.6);
    if (!isPanel(c)) return mul(MAGC, 0.72);
    return mul(MAGC, (c.ev < px ? 1.45 : 1) * G(c, 0.04));
  };
  B.push(box('body', [-5, 6, -4.75], [-4.5, 7.5, -3.75], pmag, { tag: 'pistol mag (outer)' }));
  B.push(box('body', [-4.5, 6.25, -4.75], [-4, 7.5, -3.75], pmag, { tag: 'pistol mag (inner)' }));
  // the small flap pouch: its long flap (stitched panel) covers most of it, as in the render; it is deep enough to rest
  // against the tall pouch behind it (no slit of daylight from the side)
  B.push(box('body', [-4.75, 7, -5.25], [-3.5, 11.5, -4.25], coyPouch(), { tag: 'small flap pouch' }));
  B.push(box('body', [-4.875, 6.75, -5.5], [-3.375, 10.25, -5], flap, { tag: 'small pouch flap' }));
  B.push(box('body', [-4.375, 10.25, -5.5], [-3.875, 10.75, -5.25], tab, { tag: 'pull tab (small pouch)' }));
  // trauma shears: the tall black finger loop (rounded, one-texel frame) standing up out of the sleeve, left of the single
  // mag; its lower end and shank go more than a pixel down into the sleeve. (A second, smaller loop beside it was tried:
  // at half-pixel cells the two frames, the pistol mags and the camo between them broke up into a checkerboard.)
  const LOOP = ['.#.', '#.#', '#.#', '#.#', '.#.', '.#.', '.#.'];
  const shears = (i, j) => mul(BLACK, j === 0 ? 1.5 : 1.15);
  B.push(box('body', [-4, 5, -4], [-2.5, 8.5, -3.75], cutout(LOOP, shears), { tag: 'shears loop' }));
  // big zipped GP pouch on the flank, hanging lowest (zip down the visible inner front column, pull at the top)
  const gp = c => {
    const col = mix(coy(c, 1.08), COYG, 0.1);
    if ((c.face === 'front' || c.face === 'top') && c.p[0] >= c.box.x + px && c.p[0] < c.box.x + 2 * px) return mix(col, hex('#2e2c22'), 0.45);
    return edge(c, col, { stitch: false });
  };
  B.push(box('body', [-6.25, 6.5, -3.75], [-4.75, 13.5, -2.5], gp, { tag: 'GP pouch' }));
  B.push(box('body', [-5.75, 6.5, -4], [-5.25, 7.25, -3.75], K.plastic(hex('#3a3a33')), { tag: 'zip pull' }));

  // ---------------- wearer's left front corner ----------------
  B.push(box('body', [3.25, 5.75, -4.375], [4.75, 13, -3.125], coyPouch(), { tag: 'flap pouch (left)' }));
  B.push(box('body', [3.125, 5.5, -4.625], [4.875, 9, -3.625], flap, { tag: 'flap (left)' }));
  B.push(box('body', [3.75, 9, -4.625], [4.25, 10, -4.375], tab, { tag: 'pull tab (left)' }));
  // radio pouch on the flank with a black radio seated in it (its lower half inside) and a stub antenna
  B.push(box('body', [4.5, 6, -3.5], [5.5, 12.5, -2.5], coyPouch(true), { tag: 'radio pouch' }));
  B.push(box('body', [4.625, 4.5, -3.25], [5.375, 7.5, -2.75], c => (c.face === 'top' ? mul(hex('#1b1c1d'), 1.4) : mul(hex('#1b1c1d'), isPanel(c) ? G(c, 0.05) : 0.85)), { tag: 'radio' }));
  B.push(box('body', [4.75, 2.5, -3.125], [5.25, 4.5, -2.875], K.plastic(hex('#26282a')), { tag: 'radio antenna' }));

  return { S: K.S, px: K.px, cell: K.cell, boxes: B };
};
