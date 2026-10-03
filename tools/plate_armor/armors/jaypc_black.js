// ================= Tac-Kek JayPC (Black) - EFT reference (tarkov.dev 693fd1200ec97e98040bd3f9) =================
// Same cheap JPC copy as the olive one, but the black colourway carries different junk. Read off the render
// (x<0 = wearer's right):
//   worn black Cordura with grey scuffs; bright silver tape strips on the inner edge of both strap windows
//   upper chest: a big grey tape patch with black hand-written marks, laser-cut slots showing under it (no radio/grenade)
//   lower panel: everything stands in the pocket behind the first webbing band - leather card + black shears ring
//   (blade down the webbing), a small black binder clip, the HOT ROD can, a marker with a mint cap; a pink cable bundle
//   hangs over the webbing on the wearer's right; grey J-hook with an olive-green lock crossing in front of the marker,
//   purple tape roll (pale inner core) hanging off it
//   wearer's left: black pouch with its tall flap folded up behind an orange-brown AK magazine, black tourniquet strapped
//   down its front. No GP pouch on the wearer's right, no dangler.
ARMORS.jaypc_black = function (mode) {
  const K = kit('M');                              // always the 2x style
  const { edge, molle, G, isPanel, isSide, px } = K;
  const B = [];
  const add = (a, b, mat, tag, opt = {}) => B.push(box('body', a, b, mat, { tag, ...opt }));

  // ---- worn black: soft low-frequency grey scuffing (the render's black is never flat), cool neutral
  const BK_D = hex('#26282a'), BK_L = hex('#474a4d'), LIN = hex('#141516');
  const bkAlb = p => {
    const q = snap(p, 0.5);
    const n = fbm(q[0] * 0.32 + 3, q[1] * 0.32 + 7, q[2] * 0.32 + 11);
    return mix(BK_D, BK_L, sm(clamp01((n - 0.5) * 1.7 + 0.4)));
  };
  const bk = (c, k = 1) => mul(bkAlb(c.p), k * G(c, 0.08));
  const lining = c => mul(LIN, G(c, 0.08));
  // webbing band: worn lighter top cell, darker body (black on black: the lit top edge is what reads)
  const WEBROWS = [7, 8.5];                        // painted rows on the lower panel (the first band is its own box)
  const webPaint = (c, col, rows) => {
    for (const r of rows) {
      const dy = c.p[1] - r;
      if (dy >= 0 && dy < 0.5) return mul(col, 1.22);
      if (dy >= 0.5 && dy < 1) return mul(col, 0.86);
      if (dy >= -0.5 && dy < 0) return mul(col, 0.66);   // shadow under the band above
    }
    return null;
  };

  // ================= carrier =================
  // --- plate bags (swimmer cut in tiers). Front: laser-cut slot rows (slots 1 px, bridges 0.5 px): one beside the tape
  //     patch and one in the strip between the tape and the tops of the pocket gear
  const frontBag = c => {
    let col = bk(c, 1.02);
    if (c.face === 'front') {
      const y = c.p[1], ax = Math.abs(c.p[0]);
      if (ax < 3 && ((y >= 2.75 && y < 3.25) || (y >= 3.75 && y < 4.25))) {
        const t = (((c.p[0] + 3) % 1.5) + 1.5) % 1.5;
        col = t < 1 ? mul(col, 0.55) : mul(col, 1.1);
      }
    }
    return edge(c, col);
  };
  const backBag = c => { const col = bk(c); return edge(c, molle(c, col, 3.5, 5, -2.75, 2.75) || col); };
  for (const [x0, x1, y0, y1] of [[-3.25, 3.25, 2.75, 9.25], [-2.75, 2.75, 2, 2.75], [-2, 2, 1.5, 2]]) {
    add([x0, y0, -3.25], [x1, y1, -2.25], frontBag, 'front plate bag');
    add([x0, y0, 2.25], [x1, y1, 3.25], backBag, 'back plate bag');
  }
  // --- lower front panel: plain upper strip (the pocket's back wall), then two painted webbing rows
  const frontPanel = c => {
    const col = bk(c);
    if (c.face === 'top') return mul(col, 1.15);
    if (c.face === 'front') return edge(c, webPaint(c, col, WEBROWS) || col);
    return edge(c, col);
  };
  // (it ends where the side pouch starts, so the pouch's folded-up flap stands clear above the pouch mouth)
  add([-3.5, 4.5, -4], [2.75, 9.25, -3.25], frontPanel, 'front panel');
  // --- first webbing band: its own strap standing proud of the panel, so the gear in the pocket goes down behind it.
  //     It ends at the side pouch's inner wall (the pouch sits outboard of the pocket panel, not tucked behind it).
  const band = c => {
    const col = bk(c);
    if (c.face === 'top') return mul(col, 1.3);
    if (c.face === 'bottom') return mul(col, 0.6);
    if (c.face === 'back') return lining(c);
    if (c.face !== 'front') return mul(col, 0.82);
    return c.ev < 0.5 ? mul(col, 1.22) : mul(col, 0.86);
  };
  add([-3.5, 5.5, -4.75], [2.75, 6.5, -4], band, 'pocket webbing band');
  // --- cummerbund: MOLLE on the sides and on the front wings beside the panel (no pouch on the wearer's right)
  const cumm = c => {
    const col = mul(bk(c), 0.95);
    if (isSide(c) && Math.abs(c.p[2]) < 2.25) return edge(c, webPaint(c, col, [5.5, 7, 8.5]) || col, { stitch: false });
    if (c.face === 'front' && Math.abs(c.p[0]) > 3.5) return edge(c, webPaint(c, col, [5.5, 7, 8.5]) || col, { stitch: false });
    return edge(c, col, { stitch: false });
  };
  add([-4.5, 5, -2.75], [4.5, 9, 2.75], cumm, 'cummerbund');
  // --- shoulder straps: low, fully framed window (the head hides everything higher); bright silver tape strip along the
  //     inner side of each front window (the brightest thing on the upper carrier in the render). The window is cut
  //     through the front, the riser's back face is painted as dark lining so no skin shows.
  const TAPE = hex('#a3a5a7');
  for (const s of [-1, 1]) {
    const xa = s < 0 ? -3.25 : 1.25, xb = xa + 2, cx = xa + 1;
    const inWin = (x, y) => Math.abs(x - cx) < 0.5 && y >= 0.5 && y < 1.5;
    const riser = c => {
      if (isPanel(c) && inWin(c.p[0], c.p[1])) return c.face === 'front' ? null : lining(c);
      let col = mix(bk(c), hex('#5d6062'), 0.25);
      const tapeX = s < 0 ? c.p[0] >= cx + 0.5 : c.p[0] < cx - 0.5;
      if (c.face === 'front' && tapeX && c.p[1] < 1.5) col = mul(TAPE, G(c, 0.1));
      return edge(c, col, { stitch: false });
    };
    add([xa, -0.5, -3], [xb, 2.75, -2.5], riser, 'strap riser (front)');
    add([xa, -0.5, 2.5], [xb, 2.75, 3], c => edge(c, mix(bk(c), hex('#5d6062'), 0.2), { stitch: false }), 'strap riser (back)');
  }
  add([-1.25, 1.5, 3.25], [1.25, 2, 3.75], c => edge(c, bk(c, 0.8), { stitch: false }), 'drag handle');

  // ================= upper chest: big grey tape patch with black marks =================
  // (one uneven stroke line with a descender, in a mid-grey ink: the thin black letters average out to that at 2x, and
  //  a solid black bar under the two strap windows read as a face)
  const TG = hex('#8a8883'), INK = hex('#1f1f21');
  const tapeMask = ['........', '..kkk.kk', '..k.....'];
  const tape = c => {
    if (!isPanel(c)) return mul(TG, 0.78);
    const u = c.p[0] - c.box.x, v = c.p[1] - c.box.y;
    const r = tapeMask[Math.floor(v / px)], ch = r && r[Math.floor(u / px)];
    if (ch === 'k') return mul(mix(INK, TG, 0.42), G(c, 0.1));
    return mul(TG, (1 - 0.1 * (u / c.box.w)) * G(c, 0.1));      // a shade darker toward the wearer's-left end
  };
  add([-1.25, 2, -3.5], [2.75, 3.5, -3.25], tape, 'tape patch');

  // ================= lower panel: gear standing in the pocket behind the band =================
  // flat cut-outs: fn(u, v, w, h) paints the front in box-local units; side faces reuse the nearest front cell, darker
  const flat = (fn, sideK = 0.78) => c => {
    const b = c.box, hh = px / 2;
    const u = Math.min(b.w - hh, Math.max(hh, c.p[0] - b.x)), v = Math.min(b.h - hh, Math.max(hh, c.p[1] - b.y));
    const col = fn(u, v, b.w, b.h, c);
    if (!col) return null;
    return isPanel(c) ? col : mul(col, sideK);
  };
  const cellAt = (rows, u, v) => { const r = rows[Math.floor(v / px)]; return r ? r[Math.floor(u / px)] : undefined; };
  const maskPaint = (rows, pal, sideK) => flat((u, v) => { const ch = cellAt(rows, u, v); return ch && ch !== '.' ? pal[ch] : null; }, sideK);
  // cut-outs with holes lying flat on another face: no back face (it would z-fight with that face through the holes)
  const noBack = f => c => (c.face === 'back' ? null : f(c));

  // leather card, orange-brown, lit top edge
  const CARD = hex('#8c5238');
  add([-2.75, 4, -4.25], [-0.25, 6, -4], c => {
    if (!isPanel(c)) return mul(CARD, c.face === 'top' ? 1.1 : 0.72);
    return mul(CARD, (c.p[1] - c.box.y < px ? 1.14 : 1) * G(c, 0.1));
  }, 'leather card');
  // trauma shears: black ring handle lying on the card's right part - a rounded loop with a 2-cell-wide orange inside, so
  // it reads as a ring (a 1-cell loop made an orange/black checker); an orange margin stays on the wearer's-right side.
  // Its bottom row goes down behind the band; grey blade below the band
  const RING = hex('#18191b');
  add([-2.25, 4, -4.5], [-0.25, 6, -4.25], noBack(maskPaint(['.KK.', 'K..K', 'K..K', 'K..K'], { K: RING })), 'shears handle');
  add([-1.75, 6.5, -4.25], [-1.25, 8.5, -4], flat((u, v, w, h) => mul(hex('#8b9094'), v < 0.5 ? 1.08 : 1)), 'shears blade');
  // small black binder clip threaded under the band between the shears and the can: silver wire handle on top, black
  // body above the band (a shade greyer than the shears ring beside it, so the ring's right side stays a line of its
  // own), a little wire loop showing below it
  add([-0.25, 4.25, -4.25], [0.25, 7, -4], flat((u, v) => (v < 0.5 ? hex('#b9bec2') : v < 1.75 ? hex('#2e3033') : hex('#8b9094'))), 'binder clip');
  // HOT ROD can: cream body, silver rim, red lettering on separate rows (red cells never touch vertically)
  const canPal = { S: hex('#b8bab6'), W: hex('#e4e0d6'), R: hex('#c4282b') };
  const canMask = ['SSS', 'RRW', 'WWW', 'RWR'];
  add([0.25, 4, -4.5], [1.75, 6, -4], c => {
    if (c.face === 'top') return canPal.S;
    if (c.face === 'bottom') return mul(canPal.S, 0.7);
    if (c.face !== 'front') { const v = c.p[1] - c.box.y; return mul(v < px ? canPal.S : canPal.W, 0.8); }
    return canPal[cellAt(canMask, c.p[0] - c.box.x, c.p[1] - c.box.y) || 'W'];
  }, 'HOT ROD can');
  // marker: mint cap just above the band, pale pink body showing again below it (between the J-hook's legs)
  add([1.7, 5, -4.25], [2.2, 8, -4], flat((u, v) => (v < 0.5 ? hex('#c3d8cf') : v < 1 ? hex('#8a9591') : hex('#dcbcb6'))), 'marker');
  // pink cable bundle hanging over the webbing on the wearer's right: two strands with a twist, looped at the bottom,
  // black tie at the top, ending a little above the panel's bottom edge; tilted so it lies over the band's front and
  // its bottom rests on the panel (no gap behind it from the side)
  const PK = hex('#d77a98'), PKd = hex('#a85a76');
  const pinkMask = ['.P.', 'PKQ', 'P.Q', 'Q.P', 'P.Q', '.PQ'];
  add([-3.25, 5.25, -4.755], [-1.75, 8.25, -4.505], maskPaint(pinkMask, { P: PK, Q: PKd, K: hex('#1c1d1f') }), 'pink cable', { rot: [17, 0, 0], pivot: [-2.5, 6.5, -4.63] });
  // J-hook: grey hook whose arc and olive-green lock cross in front of the marker, right against the side pouch; the
  // purple tape roll (pale inner core) hangs from it. Its top face is tucked under the band, so it is left open.
  const JG = hex('#9a9ea2'), JL = hex('#6d7d3a');
  const hook = maskPaint(['GGG', 'G.G', '..L', '..G'], { G: JG, L: JL });
  add([1.2, 6.5, -4.5], [2.7, 8.5, -4], c => (c.face === 'top' || c.face === 'back' ? null : hook(c)), 'J-hook');
  const PU = hex('#4b3d9e'), CORE = mul(hex('#b9b9b4'), 0.85);
  add([0.7, 8.5, -4.5], [2.7, 10.5, -4], noBack(maskPaint(['.PP.', 'PCCP', 'PCCP', '.PP.'], { P: PU, C: CORE })), 'tape roll');

  // ================= side pouch (wearer's left): black pouch, AK magazine, tourniquet =================
  const pouch = c => {
    if (c.face === 'top') return c.ex < px ? bk(c, 1.25) : lining(c);             // open mouth: lit rim, dark inside
    return edge(c, bk(c, 1.05));
  };
  add([2.75, 6, -4.5], [4.25, 9.75, -2.75], pouch, 'mag pouch');
  // its tall flap, folded up behind the magazine on the inner side into a black loop that rises to about the can's top:
  // worn black with a lit fold on top, the inner leg a shade lighter than the plate bag behind it and the loop's dark
  // inside beside it (the outer leg goes down behind the magazine). It stands proud of the plate bag (the front panel
  // ends where the pouch starts) and shows above and inboard of the magazine
  add([2.75, 3.75, -3.5], [3.75, 6, -3], c => {
    if (c.face === 'top') return bk(c, 1.2);
    if (c.face !== 'front') return edge(c, bk(c), { stitch: false });
    const u = c.p[0] - c.box.x, v = c.p[1] - c.box.y;
    if (v < px) return bk(c, 1.3);                                               // lit fold on top
    return u < px ? bk(c, 1.12) : bk(c, 0.5);                                    // inner leg | the loop's dark inside
  }, 'pouch flap');
  // AK magazine: orange-brown bakelite, broad side forward, darker spine; leaning outward about 24 degrees so its outer
  // end drops to the pouch's outer lip. Only a short, wider-than-tall piece stands above the mouth; the rest goes down
  // into the pouch (about 1 px at the outer end; the inner end runs into the pouch wall behind the webbing band).
  // Thin feed lips (the render shows only a narrow dark top edge over the orange) with one brass round on top.
  const BR = hex('#a05c3a'), MT = hex('#2a2826'), BRASS = hex('#caa24e'), COPPER = hex('#b5703f');
  const mag = c => {
    if (c.face === 'top') return mul(MT, 1.3);
    if (c.face === 'bottom') return mul(BR, 0.5);
    let col = mul(BR, G(c, 0.06));
    if (!isPanel(c)) return mul(col, 0.7);
    if (c.ev < px) return mul(col, 1.18);                                         // lit feed-lip edge
    if (Math.floor(c.eu / px) === 1) col = mul(col, 0.84);                        // rib
    return col;
  };
  const MAGROT = { rot: [0, 0, 24], pivot: [3.488, 6.125, -3.875] };
  add([2.863, 5.25, -4.25], [4.113, 6.75, -3.5], mag, 'AK magazine', MAGROT);
  add([2.988, 5, -4.125], [3.988, 5.25, -3.625], c => {
    if (c.face !== 'top') return mul(MT, isPanel(c) ? 1.2 : 0.9);
    return c.eu > c.fw - px ? COPPER : BRASS;
  }, 'magazine feed lips', MAGROT);
  // tourniquet strapped down the pouch front: near-black body, grey clip strap across it, lighter buckle at the bottom
  const TQ = hex('#18191b');
  const tqMask = ['KK', 'KK', 'gg', 'KK', 'KK', 'bb'];
  add([3, 6.75, -4.75], [4, 9.75, -4.5], c => {
    const ch = cellAt(tqMask, c.p[0] - c.box.x, c.p[1] - c.box.y) || 'K';
    const col = ch === 'g' ? hex('#4a4e52') : ch === 'b' ? hex('#34373a') : TQ;
    return isPanel(c) ? mul(col, G(c, 0.08)) : mul(col, 0.8);
  }, 'tourniquet');
  add([2.75, 7, -5], [4.25, 7.5, -4.5], c => mul(hex('#2e3033'), c.face === 'top' ? 1.35 : isPanel(c) ? G(c, 0.08) : 0.8), 'tourniquet strap');

  return { S: K.S, px: K.px, cell: K.cell, boxes: B };
};
