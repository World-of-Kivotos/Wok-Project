// ================= HighCom Trooper TFO body armor (MultiCam) — EFT reference (tarkov.dev 5c0e655586f774045612eeb2) =================
// A slick, light plate carrier. Reference render: MultiCam front and back plate bags, every edge bound in tan/khaki;
// the front bag's top edge runs straight between the shoulder straps, which come down onto its top corners and end in a
// short folded camo tab (no wider than the strap) lying on the bag inside its outline; wide MultiCam shoulder straps
// (tan-bound edges, grey spacer-mesh underside); upper chest: a tan-framed loop panel carrying a black patch with big worn
// white "USEC" letters (about two thirds of the bag's width in the render; about four fifths here so the letters read);
// lower front: three rows of MOLLE webbing in a yellower, greener printed MultiCam with thin cream bar tacks, about as
// bright as the plain bag between the rows (no dark bands; only a thin dark line along each tape's bottom); no pouches.
// The bag itself is a light neutral khaki MultiCam with frequent pale cream and pale blue-grey flecks. A narrow MultiCam
// elastic cummerbund strap runs from the back panel round the sides at the height of the lowest MOLLE row, closing with an
// olive-khaki side-release buckle next to the back panel and held on the front bag's side edge by a webbing keeper. The
// inside of both panels and of the straps is dark grey-blue spacer mesh. The back is not visible in the render: same bag,
// same camo (green included), same three webbing rows as the front, plain upper back. Proportions measured on the render's MOLLE (2" row period): the bag is ~1.28x as tall as wide -> hem at 12.
// Not modelled: the two small tan-bound tabs beside the patch frame (no room between the frame and the bag's binding).
ARMORS.trooper_tfo_multicam = function (mode) {
  const K = kit('M');                       // always the 2x style, whatever mode is asked for
  const { G, isPanel, isSide, px } = K;
  const B = [];
  const add = (a, b, mat, tag, opt = {}) => B.push(box('body', a, b, mat, { tag, ...opt }));

  // ---- palette (albedo; front faces render at about three quarters of it)
  const BIND = hex('#a59c78'), MESH = hex('#58636a'), WEB = hex('#a09b6a'), TACK = hex('#dcd6b4');
  const BLK = hex('#2f2f33'), WHT = hex('#e9e9e4'), BUCK = hex('#7f7d62');
  // MultiCam as in the render: a light, neutral khaki ground with green, olive-brown and dark-brown shapes and frequent
  // small pale cream and pale blue-grey flecks; sampled on the half-pixel grid, scaled so the shapes are 1-3 px
  // (colours binned off the render's upper chest: the plain bag is a light neutral khaki, not a dark yellow olive). The
  // dark browns are small twig-like shapes (higher frequency, ~10-15% of the bag like the render), the darkest is the
  // shared MultiCam '#4a3c2d', never near-black
  const GROUND = '#9c977f';
  const CAMO = [['#b3ae92', 0.55, 0.6, 3], ['#808a62', 0.62, 0.57, 17], ['#84705a', 0.7, 0.62, 41], ['#5a4c3a', 1.6, 0.64, 29],
    ['#4a3c2d', 2.0, 0.7, 59], ['#c4bfa3', 1.3, 0.66, 91], ['#adb7b3', 1.7, 0.67, 113]];
  // the back half (z > 0) reads the same camo slice as the front, shifted: the raw slice behind the back bag misses the
  // green layer almost entirely, which made front and back look like two different patterns. The front half is shifted
  // a little too: unshifted, a line of dark twigs ran right along the bag between the 2nd and 3rd webbing rows, so the
  // lower front read as light tapes over a dark band; the render's bag between the rows is about as light as the tapes,
  // with scattered flecks and blobs (the dark twigs are also a touch thinner: ~10 % of the front, green ~23 %)
  const BACK_OFF = [7.3, 3.1], FRONT_OFF = [-0.75, -0.4];
  const camoAt = p => {
    const q = snap(p, px);
    return layers((q[2] > 0 ? [q[0] + BACK_OFF[0], q[1] + BACK_OFF[1], -q[2]] : [q[0] + FRONT_OFF[0], q[1] + FRONT_OFF[1], q[2]])
      .map(v => v * 0.75), 0, GROUND, CAMO);
  };
  // lit from above like the render: brighter at the chest, darker and a little cooler (grey-green) toward the hem
  const SHADE = hex('#56685c');
  const light = (col, y) => mul(mix(col, SHADE, 0.22 * clamp01((y - 6.5) / 4.5)), 1.12 - 0.026 * y);
  const fabric = (c, k = 1) => mul(light(camoAt(c.p), c.p[1]), k * G(c, 0.05));
  const bind = (c, k = 1) => mul(BIND, k * G(c, 0.05));
  const mesh = (c, k = 1) => mul(MESH, k * G(c, 0.06));
  // printed webbing: the same camo, calmer, yellower / greener than the bag but about as bright (in the render the rows
  // stand apart by their print and the cream bar tacks, not by dark gaps between them)
  const PRINT = [0.88, 0.88, 0.75];                                             // x0.88, tinted yellower (b 0.85)
  const webCol = (c, k = 1) => mix(fabric(c), light(WEB, c.p[1]), 0.3).map((v, i) => v * PRINT[i] * k);

  // ---- layout (y down): front bag 1.25..12, back bag 0.5..12; webbing rows; cummerbund on the lowest row
  // webbing rows a little narrower than the gaps between them (as in the render, rows at 52 / 67 / 82 % of the bag);
  // the bag between the rows is not darkened (only each tape's own bottom edge is)
  const ROWS = [6.75, 8.5, 10.25], RH = 0.75;
  const BOT = 12;

  // ---- plate bags: front / back = camo, bound rim on the outline; top / bottom / sides = tan binding; inside = mesh.
  //      Each bag is a narrow top tier between the straps + the full-width bag under the straps' ends (swimmer cut).
  const bag = (back, top) => c => {
    const out = back ? 'back' : 'front', inn = back ? 'front' : 'back';
    if (c.face === inn) return mesh(c);
    const x = c.p[0], y = c.p[1], ax = Math.abs(x);
    if (isSide(c)) {                                                            // gusset: camo, the bound seam along the outer edge
      const zo = back ? c.box.z + c.box.d : c.box.z;
      return Math.abs(c.p[2] - zo) < px ? mul(mix(fabric(c), BIND, 0.55), 0.95) : fabric(c, 0.9);
    }
    if (c.face !== out) return bind(c, c.face === 'top' ? 1 : 0.95);
    let col = fabric(c);
    if (ax > 4.25 - px || y > BOT - px || (ax < 2.25 && y < top + px)) col = mix(col, BIND, 0.5);   // bound outline
    return col;
  };
  for (const [back, top, cut] of [[false, 1.25, 2.5], [true, 0.5, 2]]) {
    const z = back ? [2.5, 3.25] : [-3.25, -2.5];
    add([-2.25, top, z[0]], [2.25, cut, z[1]], bag(back, top), back ? 'back bag (top tier)' : 'front bag (top tier)');
    add([-4.25, cut, z[0]], [4.25, BOT, z[1]], bag(back, top), back ? 'back plate bag' : 'front plate bag');
  }

  // ---- shoulder straps: front and back risers over the bag corners + a band over the shoulder (the head hides the rest)
  //      camo with tan-bound long edges, tan binding on the thickness, grey mesh underneath
  const strapFace = c => {
    let col = fabric(c, 1.04);
    if (c.eu < px || c.eu > c.fw - px) col = mix(col, BIND, 0.45);
    return col;
  };
  for (const s of [-1, 1]) {
    const X = (a, b) => (s < 0 ? [-b, -a] : [a, b]);
    const [x0, x1] = X(2.25, 4.25);
    add([x0, -0.25, -3.5], [x1, 2.5, -2.5], c => (c.face === 'front' ? strapFace(c) : c.face === 'back' ? mesh(c) : bind(c, 0.95)), 'strap riser (front)');
    add([x0, -0.25, 2.5], [x1, 2, 3.5], c => (c.face === 'back' ? strapFace(c) : c.face === 'front' ? mesh(c) : bind(c, 0.95)), 'strap riser (back)');
    // over the shoulder: only its outer part shows beside the head; it ends just outside the jacket so the sleeve top
    // never swings up through it
    const [t0, t1] = X(2.25, 4.5), outF = s < 0 ? 'right' : 'left';
    add([t0, -0.75, -3.5], [t1, -0.25, 3.5], c => {
      if (c.face === 'top') { const e = Math.abs(c.p[0]); return e > 4.5 - px || e < 2.25 + px ? mix(fabric(c, 1.04), BIND, 0.45) : fabric(c, 1.04); }
      if (c.face === 'bottom' || (isSide(c) && c.face !== outF)) return mesh(c);
      if (c.face === outF) return mix(fabric(c), BIND, 0.4);                    // seen from the side: the camo strap, bound
      return bind(c, 0.95);
    }, 'strap over the shoulder');
    // folded camo tab at the strap's end: no wider than the strap (its outer side a quarter pixel inside the riser's, so
    // it stays inside the bag outline), lying on the bag's top corner and seated on the patch frame; its folded top is
    // the darker underside of the strap, not a light shelf
    const [e0, e1] = X(2, 4);
    add([e0, 1.5, -3.75], [e1, 2.5, -3.25], c => {
      if (c.face === 'back') return mesh(c, 0.8);
      if (c.face === 'top') return mul(mix(fabric(c), MESH, 0.4), 0.95);
      if (c.face !== 'front') return mul(mix(fabric(c), BIND, 0.4), 0.85);
      const col = fabric(c, 1.06);
      return c.ev > c.fh - px ? mix(col, BIND, 0.5) : col;
    }, 'strap end tab');
  }

  // ---- upper chest: tan-framed loop panel with the black USEC patch, letters in a 5-row pixel font ('L' white, '.' black)
  //      with one black cell all round and between the letters: 14 x 7 cells, the render's 2:1 patch. The 3-row font of
  //      round 1 left U, S and E as blank white tiles; 5 rows are the least that reads as USEC. Tested against the 13-cell
  //      (no side rim: U and C ran into the frame) and 15-cell (3-wide S: the frame then covered the whole bag width)
  //      layouts; this one keeps the black margin of the render with the frame half a pixel wider than before.
  //      Frame 2.5..6.5 (the render's patch is a little lower and smaller; the letters need the height)
  const GLYPH = { U: ['L.L', 'L.L', 'L.L', 'L.L', 'LLL'], S: ['LL', 'L.', 'LL', '.L', 'LL'], E: ['LL', 'L.', 'LL', 'L.', 'LL'], C: ['LL', 'L.', 'L.', 'L.', 'LL'] };
  const MASK = [0, 1, 2, 3, 4].map(j => '.' + [...'USEC'].map(k => GLYPH[k][j]).join('.') + '.');
  const PW = MASK[0].length * px / 2, P0 = 2.75, P1 = P0 + 7 * px;
  add([-PW - 0.25, P0 - 0.25, -3.5], [PW + 0.25, P1 + 0.25, -3.25], c => (c.face === 'back' ? mesh(c, 0.8) : bind(c, c.face === 'front' ? 1 : 0.9)), 'loop panel frame');
  const patch = c => {
    if (c.face !== 'front') return mul(BLK, c.face === 'top' ? 1.3 : 0.9);
    const i = Math.floor(c.eu / px), j = Math.floor(c.ev / px) - 1;
    const ch = j >= 0 && j < 5 ? MASK[j][i] : '.';
    if (ch === 'L') return mul(WHT, G(c, 0.14));
    return mul(BLK, G(c, 0.08));
  };
  add([-PW, P0, -3.75], [PW, P1, -3.5], patch, 'USEC patch');

  // ---- MOLLE: three rows of yellow-green printed webbing standing on the bag, cream bar tacks every 1.5 px; a light top
  //      edge and a darker bottom edge (the render's thin dark line under each row) frame the tape
  for (const back of [false, true]) {
    const z = back ? [3.25, 3.5] : [-3.5, -3.25];
    for (const r of ROWS) {
      add([-4, r, z[0]], [4, r + RH, z[1]], c => {
        if (c.face === (back ? 'front' : 'back')) return mesh(c, 0.7);
        const cc = { ...c, p: [c.p[0], r + RH / 2, c.p[2]] };               // one colour down the tape (it is 1.5 cells tall)
        if (c.face === 'bottom') return webCol(cc, 0.8);
        if (!isPanel(c)) return mix(webCol(cc, c.face === 'top' ? 1.1 : 0.85), BIND, 0.3);   // the tape's light edges
        const i = Math.floor((c.p[0] + 4) / px);
        const col = webCol(cc);
        return i % 3 === 0 ? mix(col, TACK, 0.55) : mul(col, c.ev < px ? 1.04 : 0.88);
      }, back ? 'back MOLLE row' : 'front MOLLE row');
    }
  }

  // ---- cummerbund: narrow MultiCam elastic strap on the lowest webbing row, keepers on the front bag's side edges,
  //      side-release buckles next to the back panel, behind the arm (the render shows the wearer's right; the left is
  //      mirrored)
  const band = c => {
    let col = webCol(c, 1.05);
    if (isSide(c)) col = mul(col, c.ev < px ? 1.06 : 0.9);
    return col;
  };
  add([-4.5, 10, -2.75], [4.5, 11, 2.75], band, 'cummerbund strap');
  for (const s of [-1, 1]) {
    const X = (a, b) => (s < 0 ? [-b, -a] : [a, b]);
    const outF = s < 0 ? 'right' : 'left';
    add([X(4, 4.5)[0], 9.75, -3.5], [X(4, 4.5)[1], 11.25, -2.75], c => {
      const col = mul(mix(fabric(c), BIND, 0.35), 0.86);
      return c.face === 'top' ? mul(col, 1.15) : col;
    }, 'cummerbund keeper');
    add([X(4.5, 5)[0], 9.75, 1.75], [X(4.5, 5)[1], 11.25, 3.25], c => {
      if (c.face === 'top') return mul(BUCK, 1.2);
      if (c.face !== outF) return mul(BUCK, 0.8);
      const i = Math.floor(c.eu / px), j = Math.floor(c.ev / px);
      if (j === 1 && i === 1) return mul(BUCK, 0.55);                         // release window (middle cell, behind the arm)
      return mul(BUCK, j === 0 ? 1.12 : j === 2 ? 0.9 : 1);
    }, 'side-release buckle');
  }
  return { S: K.S, px: K.px, cell: K.cell, boxes: B };
};
