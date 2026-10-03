// ================= Ars Arma CPC MOD.1 plate carrier (A-TACS FG) — EFT reference =================
// tarkov.dev render 5e4ac41886f77406a511c9a8 (x<0 = wearer's right = the render's left). In EFT this carrier wears its
// gear as part of the item model. A-TACS FG everywhere: soft grey-green khaki with green, olive and brown pebbles.
// Read off the render (and the -image inspect view), from the wearer's right to the left:
//   shoulders: thick padded A-TACS straps, near-black teal lining inside; a pale khaki webbing strap over each pad
//     (across the inner part of the wearer's right pad, down the outer edge of the wearer's left one)
//   wearer's right upper chest: a big black knee pad hangs under the strap (moulded rim, groove round an inner pad,
//     round dome in the middle) down to the lower placard
//   upper chest: camo admin panel with a light stitched border, tan loop strips, pale side-release buckles at both ends,
//     three chemlights (silver caps, pale green tubes): two tucked under the top elastic strip, the wearer's-left one
//     longest, slightly slanted, in front of the strips; black speaker-mic (PTT, horizontal grille) hanging over the
//     right-hand buckle, its cord dropping between the last mag and the radio
//   lower front: wide A-TACS placard with MOLLE; three black rifle mags stand in it, pale khaki-grey pull tabs beside
//     their tops. On its front: bulky IFAK (zipper down the middle, small red cross) at the wearer's right corner, a flap
//     pouch (stitched flap, pull tab), a black carabiner with a blue tape roll hanging on it and a small red-capped bottle
//     behind it, a black CAT tourniquet (black windlass ring and red tab on top, grey-teal holder strap across), a double
//     open-top mag pouch in the carrier camo (two tall mags, dark shock cords)
//   wearer's left corner: black radio in its pouch with a long antenna, right beside the double mag pouch
// The back is not visible in the render: MOLLE rows and a drag handle as on the real carrier.
ARMORS.cpc_mod1_atacs_fg = function (mode) {
  const K = kit('M');                                   // always the 2x style, whatever mode is asked for
  const { edge, G, isPanel, isSide, px } = K;
  const B = [];
  const add = (a, b, mat, tag, opt = {}) => B.push(box('body', a, b, mat, { tag, ...opt }));
  const md = (v, m) => ((v % m) + m) % m;
  const nU = c => Math.max(1, Math.round(c.fw / px)), nV = c => Math.max(1, Math.round(c.fh / px));
  const iU = c => Math.min(nU(c) - 1, Math.floor(c.eu / px + 1e-6)), iV = c => Math.min(nV(c) - 1, Math.floor(c.ev / px + 1e-6));

  // ---------- colours (sampled by eye off the render) ----------
  const LINING = hex('#1a2321');                        // near-black, slightly teal lining
  const KHAKI = hex('#c6c2a6');                         // pale webbing straps over the pads
  const LOOP = hex('#8b8364'), SEAM = hex('#5a553f'), FRAME = hex('#aeae90');
  const BUCKLE = hex('#b4b499'), SLOT = hex('#5c5c49');
  const CHEM = hex('#a5c07e'), CAP = hex('#c4ccd0');
  const KNEE = hex('#2a2c2f');
  const PTT = hex('#2c2e31'), GRILLE = hex('#121314');
  const MAG = hex('#2e3033'), MAGTOP = hex('#1d1f21'), BRASS = hex('#caa24e'), COPPER = hex('#b5703f');
  const TAB = hex('#a4a086');
  const TQ = hex('#26292b'), TQG = hex('#58696b'), RED = hex('#ad2c29');
  const CARAB = hex('#1c1e20'), TAPE = hex('#2f46bd'), CORE = hex('#c9cdcf');
  const RADIO = hex('#26282a'), CORD = hex('#34402e');
  const ZIP = hex('#3d4235'), CROSS = hex('#94302c');

  // A-TACS FG: soft 1-3 px pebbles, sampled on the half-pixel grid so no single-cell specks
  const fgAlb = p => layers(snap(p, 0.5).map(v => v * 0.62), 0, '#838b67',
    [['#a1a682', 0.55, 0.55, 9], ['#697a4f', 0.72, 0.58, 31], ['#7c7657', 0.85, 0.66, 3], ['#55623f', 1, 0.7, 57], ['#adb18e', 1.2, 0.75, 71]]);
  const fg = (c, k = 1) => mul(fgAlb(c.p), k * G(c, 0.06));
  const lining = (c, k = 1) => mul(LINING, k * G(c, 0.1));
  // MOLLE rows: a darker webbing band (one cell) every 1.5 px, a slightly darker bar-tack every 1.5 px along it
  const webRows = (c, col, y0, n, hw, h = c.p[0]) => {
    const dy = c.p[1] - y0;
    if (dy < 0 || Math.abs(h) > hw) return null;
    const i = Math.floor(dy / 1.5);
    if (i >= n || dy - i * 1.5 >= 0.5) return null;
    return mul(col, md(h + 0.25, 1.5) < 0.5 ? 0.66 : 0.76);
  };
  // open pouch mouth on a top face: light rim along the front edge, dark inside
  const mouth = (c, col) => (c.ev > c.fh - px ? mul(col, 1.12) : lining(c, 1.3));

  // ---------------- carrier ----------------
  const frontBag = c => {
    if (c.face === 'back') return lining(c);
    if (c.face === 'top') return fg(c, 1.1);
    let col = fg(c, isPanel(c) ? 1.04 : 0.9);
    if (c.face === 'front' && c.p[1] < 1.25) col = mul(col, 1.08);                   // lit rounded top of the bag
    return edge(c, col, { stitch: false });
  };
  add([-4.25, 0.5, -3.25], [4.25, 10, -2.5], frontBag, 'front plate bag');
  const backBag = c => {
    if (c.face === 'front') return lining(c);
    if (c.face === 'top') return fg(c, 1.08);
    const col = fg(c);
    return edge(c, (c.face === 'back' && webRows(c, col, 2.5, 5, 3.25)) || col, { stitch: false });
  };
  add([-4.25, 0.5, 2.5], [4.25, 10, 3.25], backBag, 'back plate bag');
  const cumm = c => {
    const col = fg(c, 0.95);
    return edge(c, (isSide(c) && webRows(c, col, 6.5, 2, 2, c.p[2])) || col, { stitch: false });
  };
  add([-4.5, 5.5, -2.75], [4.5, 10.25, 2.75], cumm, 'cummerbund');
  add([-1.25, 0.5, 3.25], [1.25, 1.5, 3.75], c => edge(c, fg(c, 0.8), { stitch: false }), 'drag handle');

  // ---------------- padded shoulder straps ----------------
  // the head hides the straps' upper run: a thick padded hump on top of each shoulder beside the head (lining
  // underneath) and the risers below the chin; the pale khaki webbing runs over the inner part of the wearer's right
  // riser and down the outer edge of the wearer's left one, and along the pads' tops
  // the arch's dark lining shows under the padding: lower half of the front/back faces, and the crown's inward face
  const hump = (s, crown = false) => c => {
    if (c.face === 'bottom') return lining(c, 1.2);
    if (crown && c.face === (s < 0 ? 'left' : 'right')) return lining(c, 1.1);
    let col = fg(c, 1.02);
    if (c.face === 'top') {
      const ax = Math.abs(c.p[0]);
      if (s > 0 ? ax > 5.25 : Math.abs(c.p[2] + 1) < 0.5) return mul(KHAKI, G(c, 0.05));
      return mul(col, 1.06);
    }
    if (isPanel(c)) {
      if (c.p[1] > c.box.y + c.box.h / 2) return lining(c, 1.15);
      col = mul(col, 0.88);
    }
    return col;
  };
  const riserF = s => c => {
    if (c.face === 'back') return lining(c);
    if (c.face === 'bottom') return lining(c, 1.3);
    if (c.face !== 'front') return edge(c, fg(c, 0.92), { stitch: false });
    const ax = Math.abs(c.p[0]);
    if (s < 0 ? ax < 3 : ax > 3.5) return mul(KHAKI, G(c, 0.05) * (c.ev > c.fh - px ? 0.88 : 1));
    return edge(c, fg(c, 1.04), { stitch: false });
  };
  const riserB = c => (c.face === 'front' ? lining(c) : edge(c, fg(c, 0.96), { stitch: false }));
  for (const s of [-1, 1]) {
    const X = (a, b) => (s < 0 ? [a, b] : [-b, -a]);
    add([X(-5.75, -4.25)[0], -1.25, -3.5], [X(-5.75, -4.25)[1], -0.25, 3.5], hump(s), 'shoulder pad');
    add([X(-5.5, -4.5)[0], -1.75, -3.25], [X(-5.5, -4.5)[1], -0.75, 3.25], hump(s, true), 'shoulder pad crown');
    const [r0, r1] = X(-4.5, -2);
    add([r0, -0.25, -3.75], [r1, 1.25, -2.75], riserF(s), 'strap front');
    add([r0, -0.25, 2.75], [r1, 1.25, 3.5], riserB, 'strap back');
  }

  // ---------------- black knee pad on the wearer's right upper chest ----------------
  // hangs right under the strap (top level with the bag's top, in front of the strap riser) and rests on the placard:
  // rounded top (a narrower cap), a plain charcoal moulded rim (only its top edge catches the light), a shallow seam
  // round the inner pad, and the inner pad itself as the glossiest part as on the original (lit top-left, its two
  // halves a shade apart either side of the centre seam), a round boss in its middle
  const knee = c => {
    const col = mul(KNEE, G(c, 0.06));
    if (c.face === 'top') return mul(col, 1.45);
    if (c.face === 'back') return lining(c);
    if (c.face === 'bottom') return mul(col, 0.7);
    const i = iU(c), j = iV(c), n = nU(c), m = nV(c);
    if (!isPanel(c)) return mul(col, j === 0 ? 1.15 : 0.88);
    if (j === 0) return mul(col, 1.55);                                               // lit rim under the cap
    if (i === 0) return mul(col, 1.15);                                               // moulded rim, a touch lit on the left
    if (i === n - 1 || j === m - 1) return mul(col, 1.05);
    if (i === 1 || i === n - 2 || j === 1 || j === m - 2) return mul(col, 0.72);      // seam round the inner pad
    return mul(col, 1.35 + (i === 2 ? 0.05 : -0.03) + (j === 2 ? 0.1 : j === m - 3 ? -0.08 : 0));   // glossy inner pad
  };
  add([-4.5, 1.25, -4.25], [-1.5, 5.75, -3.25], knee, 'knee pad');
  add([-4.25, 0.75, -4.25], [-1.75, 1.25, -3.25], c => {
    if (c.face === 'back' || c.face === 'bottom') return null;
    const col = mul(KNEE, G(c, 0.05));
    return mul(col, c.face === 'top' ? 1.5 : isPanel(c) ? 1.6 : 1.15);
  }, 'knee pad rounded top');
  const dome = c => {
    if (c.face === 'back') return null;
    const col = mul(KNEE, G(c, 0.05));
    const mid = iU(c) === 1;
    if (c.face === 'top') return mul(col, mid ? 1.8 : 1.1);
    if (c.face === 'bottom') return mul(col, mid ? 0.75 : 0.6);
    if (!isPanel(c)) return mul(col, iV(c) === 1 ? 1.1 : 0.8);
    // a plus of cells shaded like a ball lit from the upper left; its darker corners draw the ring round the boss
    const S = [[1.05, 2.1, 1.05], [1.9, 1.55, 1.05], [1.05, 0.95, 1.05]];
    return mul(col, S[iV(c)][iU(c)]);
  };
  add([-3.75, 2.75, -4.5], [-2.25, 4.25, -4.25], dome, 'knee pad dome');

  // ---------------- admin panel, chemlights, PTT ----------------
  const frame = c => {
    if (c.face === 'back') return null;
    const col = fg(c, 1.1);
    if (c.face !== 'front') return edge(c, mul(col, 0.9), { stitch: false });
    const i = iU(c), j = iV(c), n = nU(c), m = nV(c);
    if (i === 0 || i === n - 1 || j === 0 || j === m - 1) return mix(col, FRAME, 0.5);   // light stitched binding
    return col;
  };
  add([-1.25, 1.25, -3.5], [3.25, 4.25, -3.25], frame, 'admin panel');
  // tan loop field: its top half is covered by the raised elastic strip; below it one soft seam, then an even loop strip
  const loopField = c => {
    if (c.face === 'back') return null;
    const col = mul(LOOP, G(c, 0.08));
    if (c.face === 'top') return mul(col, 1.12);
    if (c.face !== 'front') return mul(col, 0.8);
    return iV(c) === 2 ? mul(col, 0.85) : col;
  };
  add([-0.75, 1.75, -3.75], [2.75, 4.25, -3.5], loopField, 'loop field');
  // chemlights: silver cap on top, pale green tube with darker sides so it reads round. Two stand proud of the lower
  // loop strip and are tucked under the elastic; the wearer's-left one is longest, slightly slanted, in front of both
  const chem = c => {
    if (c.face === 'top') return CAP;
    if (c.face === 'back' || c.face === 'bottom') return mul(CHEM, 0.6);
    const col = c.p[1] - c.box.y < 0.5 ? CAP : mul(CHEM, G(c, 0.04));
    return isPanel(c) ? col : mul(col, 0.75);
  };
  for (const x of [-0.5, 0.75]) add([x, 1.25, -4], [x + 0.5, 3.75, -3.5], chem, 'chemlight');
  add([1.75, 1.25, -4.5], [2.25, 4.5, -3.75], chem, 'chemlight (long, slanted)', { rot: [0, 0, -8], pivot: [2, 1.25, -4.125] });
  // the top loop strip is an elastic band standing proud of the field, holding the two short chemlights
  const elastic = c => {
    if (c.face === 'back') return null;
    const col = mul(LOOP, G(c, 0.07));
    if (c.face === 'top') return mul(col, 1.18);
    if (c.face === 'bottom') return mul(col, 0.6);
    return isPanel(c) ? mul(col, 1.06) : mul(col, 0.82);
  };
  add([-0.75, 1.75, -4.25], [2.75, 2.75, -3.75], elastic, 'elastic loop strip');
  // pale side-release buckles at both ends of the loop field (the PTT hangs over the right one)
  const buckle = c => {
    if (c.face === 'back') return null;
    const col = mul(BUCKLE, G(c, 0.04));
    if (c.face === 'top') return mul(col, 1.12);
    if (!isPanel(c)) return mul(col, 0.8);
    const j = iV(c), m = nV(c);
    if (j === 1) return mix(col, SLOT, 0.55);                                         // slot between the two halves
    return mul(col, j === 0 ? 1.1 : j === m - 1 ? 0.88 : 1);
  };
  for (const x of [-1.25, 2.75]) add([x, 2, -4], [x + 0.5, 4, -3.5], buckle, 'side-release buckle');
  // speaker-mic: rounded black body (lit top rim, darker top corners), a dark grille band right across under the rim
  const ptt = c => {
    const col = mul(PTT, G(c, 0.05));
    if (c.face === 'top') return mul(col, 1.55);
    if (c.face === 'back') return lining(c);
    if (c.face === 'bottom') return mul(col, 0.7);
    if (c.face !== 'front') return mul(col, 0.9);
    const i = iU(c), j = iV(c);
    if (j === 0) return mul(col, i === 1 ? 1.45 : 0.8);
    if (j === 1 || j === 2) return mix(mul(col, j === 1 ? 0.45 : 0.5), GRILLE, 0.3);
    return mul(col, 0.95);
  };
  add([2.625, 1, -4.5], [4.125, 3, -3.25], ptt, 'PTT');
  // its cord drops between the last placard mag and the radio, down to the placard
  add([4, 3, -3.5], [4.25, 5.75, -3.25], K.solid(hex('#1e2022'), 0.05), 'PTT cord');

  // ---------------- placard + its three rifle mags ----------------
  const placard = c => {
    if (c.face === 'top') return mouth(c, fg(c));
    if (c.face === 'back') return lining(c);
    const col = fg(c, 0.98);
    if (c.face === 'front') return edge(c, webRows(c, col, 6.5, 3, 4.5) || col, { stitch: false });
    return edge(c, mul(col, 0.9), { stitch: false });
  };
  add([-4.5, 5.75, -4], [4.5, 10.5, -3.25], placard, 'placard');
  // black PMAGs, broad side forward: lit top edge, a darker rib, dark spine; a narrower feed-lip block on top with one
  // brass round (copper tip on the wearer's left)
  const magPaint = c => {
    if (c.face === 'bottom') return mul(MAG, 0.6);
    if (c.face === 'top') return mul(MAGTOP, c.ex < px ? 1.5 : 1);
    const col = mul(MAG, G(c, 0.04));
    if (!isPanel(c)) return mul(col, 0.74);
    if (iV(c) === 0) return mul(col, 1.45);
    return iU(c) === 1 ? mul(col, 0.8) : col;
  };
  const lips = c => (c.face === 'top' ? (c.eu > c.fw - px ? COPPER : BRASS) : mul(MAGTOP, isPanel(c) ? 1.25 : 0.95));
  for (const x of [-1.5, 0.5, 2.5]) {
    add([x, 5, -3.75], [x + 1.5, 8, -3.25], magPaint, 'placard mag');
    add([x + 0.25, 4.5, -3.625], [x + 1.25, 5, -3.375], lips, 'placard mag feed lips');
  }
  // pale khaki-grey pull tabs standing on the placard's front edge beside the mag tops
  const tab = c => {
    if (c.face === 'back') return null;
    const col = mul(TAB, G(c, 0.05));
    if (c.face === 'top') return mul(col, 1.12);
    if (!isPanel(c)) return mul(col, 0.78);
    return iV(c) === 0 ? mul(col, 1.08) : iV(c) === 1 ? mul(col, 0.72) : col;          // loop opening under the top
  };
  for (const x of [0, 2, 4]) add([x, 4.75, -4], [x + 0.5, 5.75, -3.75], tab, 'pull tab');

  // ---------------- double open-top mag pouch with two tall mags ----------------
  // the carrier's A-TACS FG, a shade darker than the bag top as on the original
  const pouchFab = c => mul(mix(fgAlb(c.p), hex('#8e9571'), 0.15), 0.86 * G(c, 0.06));
  const magPouch = c => {
    if (c.face === 'top') return mouth(c, pouchFab(c));
    if (c.face === 'back') return lining(c);
    const col = pouchFab(c);
    if (c.face !== 'front') return edge(c, mul(col, 0.86), { stitch: false });
    const i = iU(c), j = iV(c), n = nU(c), m = nV(c);
    if (j === 0) return mul(col, 1.14);                                                // elastic top binding
    // two dark shock cords right across (a 1-cell X reads as a checkerboard at this size); the fabric bulges lighter
    // just above each cord, the pouch's side edges stay darker
    if (j === 2 || j === 5) return mix(col, CORD, 0.7);
    const side = i === 0 || i === n - 1;
    if (j === 1 || j === 4) return mul(col, side ? 0.92 : 1.1);
    if (side || j === m - 1) return mul(col, 0.84);
    return col;
  };
  for (const x of [0.75, 2.75]) {
    add([x, 7.25, -4.75], [x + 2, 10.5, -4], magPouch, 'mag pouch');
    add([x + 0.25, 5.5, -4.5], [x + 1.75, 9, -4], magPaint, 'rifle mag');
    add([x + 0.5, 5, -4.375], [x + 1.5, 5.5, -4.125], lips, 'rifle mag feed lips');
  }

  // ---------------- tourniquet ----------------
  // black CAT hanging on the placard: black ring and red tab on top, a grey-teal holder strap across, grey keepers lower
  const tq = c => {
    const col = mul(TQ, G(c, 0.05));
    if (c.face === 'top') return mul(col, 1.4);
    if (!isPanel(c)) return mul(col, 0.8);
    const y = c.p[1];
    if (y >= 9.5 && y < 10) return mul(TQG, 0.9 * G(c, 0.05));
    return iU(c) === 0 ? mul(col, 1.25) : col;
  };
  add([-1, 6.25, -4.5], [0, 10.75, -4], tq, 'tourniquet');
  const redTab = c => {
    const col = mul(RED, G(c, 0.05));
    if (c.face === 'top') return mul(col, 1.2);
    if (!isPanel(c)) return mul(col, 0.75);
    return iV(c) === 0 ? mul(col, 1.12) : mul(col, 0.92);
  };
  add([-1.25, 5.75, -4.625], [0.25, 6.5, -4.125], redTab, 'tourniquet red tab');
  // black windlass ring standing up out of the red tab's wearer's-left end as on the original (its lower edge tucked
  // into the tab), open in the middle so the pale pull tab behind it shows through
  const tqRing = c => {
    if (c.face === 'back') return null;
    if (!isPanel(c)) return mul(TQ, c.face === 'top' ? 1.3 : 0.85);
    const i = iU(c), j = iV(c);
    if (i === 1 && j === 1) return null;
    return mul(TQ, G(c, 0.05) * (j === 0 ? 1.8 : i === 0 ? 1.5 : 1.1));             // glossy black: lit top, lit left side
  };
  add([-0.5, 4.5, -4.5], [1, 6, -4.25], tqRing, 'tourniquet windlass ring');
  const hold = c => {
    const col = mul(TQG, G(c, 0.05));
    if (c.face === 'top') return mul(col, 1.2);
    if (!isPanel(c)) return mul(col, 0.8);
    return iV(c) === 0 ? mul(col, 1.1) : col;
  };
  add([-1.25, 7.25, -4.75], [0.25, 8, -4], hold, 'tourniquet holder strap');

  // ---------------- carabiner + blue tape roll ----------------
  const carab = c => {
    if (c.face === 'back') return null;
    if (!isPanel(c)) return mul(CARAB, 0.9);
    const i = iU(c), j = iV(c), m = nV(c);
    const endRow = j === 0 || j === m - 1;
    if (endRow ? i !== 1 : i === 1) return null;                                       // tall loop: rounded ends, open middle
    return mul(CARAB, j === 0 ? 2 : i === 0 ? 1.6 : 1.2);
  };
  add([-2.75, 7.25, -4.25], [-1.25, 10.25, -4], carab, 'carabiner');
  // small grey bottle with a red cap in the pouch slot behind the carabiner, showing through its open middle
  const bottle = c => {
    if (c.face === 'back') return null;
    const col = c.p[1] < 8.5 ? mul(RED, G(c, 0.05)) : mul(hex('#70757a'), G(c, 0.05));
    if (c.face === 'top') return mul(col, 1.2);
    return isPanel(c) ? col : mul(col, 0.8);
  };
  add([-2.25, 8, -4.125], [-1.75, 9, -3.875], bottle, 'small bottle');
  // tape roll hanging on the carabiner's lower bar: bright blue, light core, shaded corners so it reads round
  const tape = c => {
    if (c.face === 'back') return mul(TAPE, 0.6);
    if (!isPanel(c)) return mul(TAPE, c.face === 'top' ? 1.05 : 0.75);
    const i = iU(c), j = iV(c);
    if (i === 1 && j === 1) return CORE;
    const S = [[0.78, 1.2, 0.86], [1.1, 1, 0.92], [0.7, 0.86, 0.62]];
    return mul(TAPE, S[j][i] * G(c, 0.04));
  };
  add([-2.625, 9.25, -4.5], [-1.125, 10.75, -4], tape, 'tape roll');

  // ---------------- flap pouch ----------------
  // single pouch with a long lighter flap (stitched field), a pull tab under the flap's edge
  const flapPouch = c => {
    if (c.face === 'back') return lining(c);
    const col = mul(mix(fgAlb(c.p), hex('#959b77'), 0.5), 1.08 * G(c, 0.06));      // the flap catches the light
    if (c.face === 'top') return mul(col, 1.1);
    if (c.face === 'bottom') return mul(col, 0.7);
    if (c.face !== 'front') return edge(c, mul(col, 0.86), { stitch: false });
    const j = iV(c), i = iU(c), n = nU(c);
    if (j === 0) return mul(col, 1.14);
    if (j === 5) return mul(col, 0.62);                                                // shadow under the flap's edge
    if (j > 5) return mul(col, i === 0 || i === n - 1 ? 0.8 : 0.9);                   // pouch body under the flap
    if (i === 0 || i === n - 1) return mul(col, 0.9);
    return (j >= 2 && j <= 3 && i === 1) ? mul(col, 0.9) : mul(col, 1.04);            // stitched field on the flap
  };
  add([-4.5, 7, -4.75], [-2.75, 10.75, -4], flapPouch, 'flap pouch');
  add([-3.75, 9.75, -5], [-3.25, 10.5, -4.75], c => (c.face === 'back' ? null : mul(fg(c, 1.1), c.face === 'front' ? (iV(c) === 1 ? 0.8 : 1.02) : 0.8)), 'flap pull tab');

  // ---------------- IFAK (wearer's right front corner) ----------------
  // seated on the placard's corner (over the flap pouch's outer edge), at most 1 px past the carrier; a clearly dark
  // zipper runs down the middle of the bulge, the outer column a little darker so the pouch reads rounded
  const ifakFace = (c, col) => {
    const i = iU(c), j = iV(c), m = nV(c);
    if (i === 1) return mul(mix(col, ZIP, 0.75), j === 0 ? 1.12 : 1);                  // zipper
    if (c.box.h > 1 && i === 0 && j === m - 2) return mul(CROSS, G(c, 0.04));          // small red-cross patch, low outside
    return mul(col, (i === 0 ? 0.88 : 1) * (j === 0 ? 1.1 : j === m - 1 ? 0.8 : 1));
  };
  const ifak = c => {
    if (c.face === 'back') return lining(c);
    const col = fg(c, 1.06);
    if (c.face === 'top') return mul(col, 1.1);
    if (c.face === 'bottom') return mul(col, 0.7);
    if (c.face === 'front') return ifakFace(c, col);
    return edge(c, mul(col, 0.9), { stitch: false });
  };
  add([-5.5, 6, -5], [-4.25, 11, -3.5], ifak, 'IFAK');
  add([-5.5, 5.5, -4.75], [-4.5, 6, -3.75], c => {
    if (c.face === 'bottom') return null;
    const col = fg(c, 1.1);
    if (c.face === 'front') return ifakFace(c, mul(col, 0.94));
    return mul(col, isPanel(c) ? 0.94 : 1);
  }, 'IFAK rounded top');

  // ---------------- radio in its pouch (wearer's left front corner) ----------------
  // pulled in against the placard's side, right beside (its inner edge just behind) the double mag pouch
  const rPouch = c => {
    if (c.face === 'top') return mouth(c, fg(c));
    if (c.face === 'back') return lining(c);
    const col = fg(c);
    return edge(c, isPanel(c) ? col : mul(col, 0.88), { stitch: false });
  };
  add([4.5, 7, -4], [5.25, 10.25, -3], rPouch, 'radio pouch');
  const radio = c => {
    const col = mul(RADIO, G(c, 0.05));
    if (c.face === 'top') return mul(col, 1.5);
    if (!isPanel(c)) return mul(col, 0.85);
    const j = iV(c);
    return j === 0 ? mul(col, 1.4) : j === 2 ? mul(col, 0.75) : col;
  };
  add([4.625, 4.75, -3.875], [5.125, 8.5, -3.125], radio, 'radio');
  add([4.75, 2.25, -3.625], [5, 4.75, -3.375], K.plastic(hex('#1f2123')), 'radio antenna');
  return { S: K.S, px: K.px, cell: K.cell, boxes: B };
};
