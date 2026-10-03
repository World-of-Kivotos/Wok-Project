// ================= Hexatac HPC Plate Carrier (MultiCam Black) — EFT reference =================
// tarkov.dev render 63737f448b28897f2802b874 (EFT: "Hexatac HPC Plate Carrier (Multicam Black)", an armor vest: no
// pouches or gear on it). Slick low-profile carrier, all MultiCam Black (charcoal ground with a green cast, dull
// grey-olive and khaki-grey shapes). Front plate bag with rounded top corners; on the upper chest a loop panel with its
// own darker print (mostly navy-black, low-contrast grey-olive / grey-khaki patches) in a plain binding, cut into rows of
// slots (four in the render, three fit at 2x), a vertical webbing loop right beside it on each side; the lower front (from
// about half way down) is one black loop (velcro) field onto which the two black hook flaps of the
// cummerbund are pressed (the wearer's right one wider and tilted up toward the middle), each with a grey pull tab (top
// inner corner / bottom inner corner); under them a black webbing row with bar tacks and a camo bottom band. Padded camo
// shoulder straps (black mesh underside) end in black side-release buckles at the bag's top corners. Skeletal cummerbund:
// two camo straps with a grey elastic panel between them near the back. Back plate bag (not visible in the render: four
// darker webbing rows added as on the usual carrier), black mesh inside.
ARMORS.hexatac_hpc_black_multicam = function (mode) {
  const K = kit('M');                                   // always the 2x style, whatever mode is asked for
  const { edge, G, isPanel, isSide, px } = K;
  const B = [];
  const add = (a, b, mat, tag, opt = {}) => B.push(box('body', a, b, mat, { tag, ...opt }));
  const flush = (mat, face) => c => (c.face === face ? null : mat(c));   // face pressed flat on another surface

  // ---------- colours (sampled off the render, lifted a little so the dark camo still reads in Minecraft) ----------
  const BASE = hex('#2e3230');
  const CAMO = [[hex('#394036'), 0.55, 0.5, 3], [hex('#4b4e43'), 0.7, 0.6, 17], [hex('#212423'), 0.8, 0.6, 41], [hex('#58594b'), 1.0, 0.66, 77]];
  // the loop panel: its own darker print, navy-black (the bulk of it), dull grey-olive and (only a shade lighter)
  // grey-khaki patches; dark slot lines
  const ADMD = hex('#25252f'), ADMM = hex('#35362f'), ADML = hex('#45463d'), SLOT = hex('#131317'), PTAPE = hex('#474a40');
  const LOOP = hex('#27282b'), HOOK = hex('#202124'), MESH = hex('#17191a'), PLASTIC = hex('#1d1e20');
  const TABG = hex('#3d4144'), ELASTIC = hex('#464b4e'), WEBL = hex('#50534a'), WEBD = hex('#2f332f');

  // MultiCam Black sampled on the 0.5 px grid at a scale that keeps its shapes 1-3 px (two octaves: no single-cell specks)
  const camo = (p, base = BASE, ls = CAMO, scale = 0.5) => {
    const q = snap(p, 0.5).map(v => v * scale);
    let col = base;
    for (const [h, f, t, o] of ls) if (fbm(q[0] * f + o, q[1] * f * 0.9 - o, q[2] * f + o * 0.5, 2) > t) col = h;
    return col;
  };
  const cloth = (c, k = 1) => mul(camo(c.p), k * G(c, 0.05));
  const mesh = c => mul(MESH, G(c, 0.1));

  // ---------- front plate bag: three boxes stepping in at the top = the rounded top corners ----------
  const TOP = 0.5, BOT = 11.25;
  const halfW = y => (y < 1 ? 3.25 : y < 1.5 ? 3.75 : 4.25);
  const inBag = (x, y) => y > TOP && y < BOT && Math.abs(x) < halfW(y);
  const frontBag = c => {
    if (c.face === 'back') return mesh(c);                                   // spacer-mesh lining
    const col = cloth(c);
    if (c.face === 'top') return mul(col, 1.08);
    if (c.face === 'bottom') return mul(col, 0.7);
    if (isSide(c)) return mul(col, 0.86);
    const x = c.p[0], y = c.p[1], ax = Math.abs(x);
    if (ax < 3.75 && y >= 6 && y < 10.5) {
      // black loop field the cummerbund flaps stick to (stitched, slightly lighter top edge)
      if (y < 9.5) return mul(LOOP, G(c, 0.08) * (y < 6.5 ? 1.22 : 1));
      // black webbing row with vertical bar tacks
      if (ax % 1.5 < 0.25) return mul(LOOP, 0.76);
      return mul(LOOP, G(c, 0.06) * (y < 10 ? 1.12 : 0.92));
    }
    // outline that follows the rounded corners: light binding along the top, darker sides and bottom
    if (!inBag(x, y - px)) return mul(col, 1.12);
    if (!inBag(x - px, y) || !inBag(x + px, y)) return mul(col, 0.8);
    if (!inBag(x, y + px)) return mul(col, 0.72);
    return col;
  };
  add([-3.25, TOP, -3.25], [3.25, 1, -2.5], frontBag, 'front plate bag (top)');
  add([-3.75, 1, -3.25], [3.75, 1.5, -2.5], frontBag, 'front plate bag (corners)');
  add([-4.25, 1.5, -3.25], [4.25, BOT, -2.5], frontBag, 'front plate bag');

  // loop panel on the upper chest: a darker print of its own (navy-black ground, dull grey-olive patches with a few
  // grey-khaki ones nested inside them, all low contrast), sampled with the same camo function as the carrier on the
  // loop rows; any one-cell run is merged into its darker neighbour so every patch is at least 1 px wide (no checkers).
  // Three rows of loop cut by two continuous dark slot lines (the render's four rows don't fit at 2x; each line is the
  // row above darkened, so the print carries across it as in the render), plain olive tape binding round it (lit top
  // edge, darker sides and bottom).
  const PRINT = [[ADMM, 0.9, 0.48, 206], [ADML, 0.9, 0.59, 206]];             // nested: grey-khaki only inside grey-olive
  const RANK = new Map([[ADMD, 0], [ADMM, 1], [ADML, 2]]);
  const printRow = (b, y) => {
    const a = [];
    for (let i = 1; i < Math.round(b.w / px) - 1; i++) a.push(camo([b.x + (i + 0.5) * px, y, b.z], ADMD, PRINT));
    for (let i = 0; i < a.length; i++) {
      const l = a[i - 1], r = a[i + 1];
      if (a[i] !== l && a[i] !== r) a[i] = !l ? r : !r ? l : RANK.get(l) <= RANK.get(r) ? l : r;
    }
    return a;
  };
  const admin = c => {
    const tape = mul(PTAPE, G(c, 0.05));                                       // plain tape: reads as a crisp frame on the camo
    if (!isPanel(c)) return mul(tape, c.face === 'top' ? 1.1 : 0.72);
    const b = c.box, i = Math.floor((c.p[0] - b.x) / px), j = Math.floor((c.p[1] - b.y) / px);
    const n = Math.round(b.w / px), m = Math.round(b.h / px);
    if (j === 0) return mul(tape, 1.14);                                       // lit top binding
    if (j === m - 1) return mul(tape, 0.78);                                   // bottom binding
    if (i === 0 || i === n - 1) return mul(tape, 0.92);                        // side binding
    // slot line between the loop rows: the shadow under the row above, darker than both rows all the way across
    if (j % 2 === 0) return mul(mix(printRow(b, b.y + (j - 0.5) * px)[i - 1], SLOT, 0.55), G(c, 0.04));
    return mul(printRow(b, b.y + (j + 0.5) * px)[i - 1], G(c, 0.06));
  };
  add([-2.75, 1.5, -3.5], [2.75, 5, -3.25], flush(admin, 'back'), 'loop panel');
  // vertical webbing loop right beside the panel on each side
  const loopStrip = c => {
    const col = mul(mix(camo(c.p), WEBL, 0.4), G(c, 0.05));
    if (!isPanel(c)) return mul(col, 0.78);
    const j = Math.floor(c.ev / px), m = Math.round(c.fh / px);
    return j === 0 ? mul(col, 1.1) : j === m - 1 ? mul(col, 0.8) : col;
  };
  for (const s of [-1, 1]) add(s < 0 ? [-4, 2.5, -3.5] : [3, 2.5, -3.5], s < 0 ? [-3, 5.5, -3.25] : [4, 5.5, -3.25], flush(loopStrip, 'back'), 'webbing loop');

  // ---------- back plate bag: MOLLE rows outside, mesh inside ----------
  const backBag = c => {
    if (c.face === 'front') return mesh(c);
    let col = cloth(c);
    if (c.face === 'top') return mul(col, 1.08);
    if (c.face === 'bottom') return mul(col, 0.7);
    if (c.face === 'back' && Math.abs(c.p[0]) < 3.25 && c.p[1] >= 2.5 && c.p[1] < 8.5) {
      // four MOLLE rows: a darker webbing band (a bar tack every 1.5 px, only a shade darker) over a shadow row.
      // (The kit rows' near-black tacks over the camo turned into a checkerboard.)
      const dy = (c.p[1] - 2.5) % 1.5;
      if (dy < 0.5) col = mul(mix(col, WEBD, 0.7), ((c.p[0] + 3.25) % 1.5) < 0.5 ? 0.82 : 1);
      else if (dy < 1) col = mul(col, 0.74);
    }
    return edge(c, col, { stitch: false });
  };
  add([-4.25, TOP, 2.5], [4.25, BOT, 3.25], backBag, 'back plate bag');

  // ---------- shoulder straps: flat padded strap on each shoulder, short risers to the bags, black buckles ----------
  // (the straps over the trapezius sit inside the head; what shows is the strap on each shoulder, just outside the hat
  // layer, and the risers under the chin that end in the buckles on the bag's rounded top corners)
  for (const s of [-1, 1]) {
    const X = (a, b) => (s < 0 ? [-b, -a] : [a, b]);
    const inn = s < 0 ? 'left' : 'right';
    const strap = c => {
      if (c.face === 'bottom' || c.face === inn) return mesh(c);             // black mesh underside
      const col = cloth(c, 1.04);
      if (c.face === 'top') return mul(col, 1.1);
      if (isPanel(c)) return mul(col, 0.88);
      return edge(c, col, { stitch: false });
    };
    add([X(4.25, 5.75)[0], -1.25, -3.5], [X(4.25, 5.75)[1], -0.25, 3.5], strap, 'shoulder strap');
    const riser = inward => c => (c.face === 'bottom' || c.face === inward ? mesh(c) : edge(c, cloth(c), { stitch: false }));
    // front riser reaches down behind the buckle (the bag's corner is rounded away there); the back one is 0.25 wider
    // than the back bag so their side faces never share a plane
    add([X(2.75, 4.25)[0], -0.25, -3.5], [X(2.75, 4.25)[1], 1, -2.75], riser('back'), 'strap front');
    add([X(2.75, 4.5)[0], -0.25, 2.75], [X(2.75, 4.5)[1], 1, 3.5], riser('front'), 'strap back');
    // side-release buckle: lit top edge of the female half, body, darker prong end pressed on the bag
    const buckle = c => {
      if (c.face === 'top') return mul(PLASTIC, 1.6);
      if (!isPanel(c)) return mul(PLASTIC, 0.85);
      const j = Math.floor(c.ev / px);
      return mul(PLASTIC, j === 0 ? 1.5 : j === 1 ? 1.12 : 0.9);
    };
    add([X(3, 4)[0], 0.5, -3.75], [X(3, 4)[1], 2, -3.25], buckle, 'side-release buckle');
  }

  // ---------- skeletal cummerbund: two camo straps round the sides, grey elastic panel between them near the back ----------
  const cummStrap = c => {
    const col = cloth(c, 0.95);
    if (isSide(c)) return mul(col, Math.floor(c.ev / px) === 0 ? 1.1 : 0.9);
    return mul(col, c.face === 'top' ? 1.05 : 0.75);
  };
  add([-4.5, 7, -2.75], [4.5, 8, 2.75], cummStrap, 'cummerbund strap (upper)');
  add([-4.5, 9, -2.75], [4.5, 10, 2.75], cummStrap, 'cummerbund strap (lower)');
  const elastic = c => {
    if (!isSide(c)) return mul(ELASTIC, 0.7);
    const z = c.p[2];
    return mul(ELASTIC, G(c, 0.06) * (z > 1 && z < 2 ? 1.14 : 0.86));        // lighter centre bar
  };
  add([-4.5, 8, 0.5], [4.5, 9, 2.5], elastic, 'cummerbund elastic');

  // ---------- cummerbund ends: black hook flaps pressed on the loop field, bound edges, grey pull tabs ----------
  const flap = c => {
    if (c.face === 'back') return null;
    const col = mul(HOOK, G(c, 0.08));
    if (!isPanel(c)) return mul(col, 1.15);
    return c.ex < px ? mul(col, 1.38) : col;                                  // stitched binding round the flap
  };
  // wearer's right flap: the wider one, tilted up toward the middle; wearer's left flap: slightly tilted the other way
  add([-4.75, 7, -3.5], [-0.25, 9.5, -3.25], flap, 'cummerbund flap (right)', { rot: [0, 0, -4], pivot: [-2.5, 8.25, -3.375] });
  add([1.25, 7, -3.5], [4.75, 9.5, -3.25], flap, 'cummerbund flap (left)', { rot: [0, 0, 2], pivot: [3, 8.25, -3.375] });
  // where the flaps wrap round the bag's corners into the side straps
  const wrap = c => mul(HOOK, G(c, 0.08) * (isSide(c) ? 1.1 : 0.9));
  for (const s of [-1, 1]) add(s < 0 ? [-4.75, 7, -3.25] : [4.25, 7, -3.25], s < 0 ? [-4.25, 9.5, -2.75] : [4.75, 9.5, -2.75], wrap, 'flap wrap');
  const tab = c => {
    const col = mul(TABG, G(c, 0.06));
    if (c.face === 'top') return mul(col, 1.2);
    if (!isPanel(c)) return mul(col, 0.78);
    return Math.floor(c.ev / px) === 0 ? mul(col, 1.12) : col;
  };
  // (both stick out from under the flap's inner edge onto the loop field, as in the render)
  add([-0.5, 6.5, -3.75], [0.5, 7.5, -3.25], tab, 'pull tab (right flap)');
  add([0.5, 8.5, -3.75], [1.5, 9.5, -3.25], tab, 'pull tab (left flap)');
  return { S: K.S, px: K.px, cell: K.cell, boxes: B };
};
