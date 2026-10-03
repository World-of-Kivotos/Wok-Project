// ================= Tac-Kek JayPC (OD Green) — EFT reference =================
// Cheap JPC copy whose EFT model carries its junk. Layout read off the tarkov.dev render (x<0 = wearer's right):
//   upper chest (loop field): F-1 grenade (outboard) | radio near the centre line, antenna on its outer edge | grey tape patch;
//   the radio hangs clear above the pocket, with loop field and a slit row between it and the shears
//   lower MOLLE panel: leather card + trauma shears (ring over the card, blade down the webbing) | pink cable tied under the
//   shears | small silver clip | HOT ROD energy-drink can standing in the pocket | marker | J-hook + blue tape roll;
//   the first webbing row is the pocket front and passes in front of the gear (M: its own proud strap)
//   sides: sand/teal GP pouch (wearer's right), vest-green tourniquet pouch with a black TQ and an orange-brown AK magazine seated in its mouth (wearer's left)
//   dangler: dark hook-and-loop band on hanger straps, 3 + 2 red shells (+1 empty loop) leaning to the centre, loop patch
// Side pouches sit a little further in than on the original so the swinging arms mostly miss them.
ARMORS.jaypc = function jaypc(mode) {
  const K = kit(mode), { hd, mid, edge, G, isPanel } = K;
  const lo = !hd;                                  // mode A
  const cw = K.cell || 0.25;                       // paint cell: A 1, M 0.5, B 0.25
  const P3 = (a, m, b) => (lo ? a : mid ? m : b);  // per-mode value
  const B = [];
  const add = (a, b, mat, tag, opt = {}) => B.push(box('body', a, b, mat, { tag, ...opt }));
  // ---- colours: slightly grey mid-olive, soft low-frequency wear, lighter where the light hits (straps, loop field)
  const OD_D = hex('#556441'), OD_L = hex('#7a8964');
  const odAlb = p => { const q = snap(p, K.cell); const n = fbm(q[0] * 0.3 + 3, q[1] * 0.3, q[2] * 0.3 + 11); return mix(OD_D, OD_L, sm(clamp01((n - 0.5) * 1.5 + 0.5))); };
  const od = c => mul(odAlb(c.p), G(c, 0.08));
  const odLite = c => mix(od(c), hex('#909b76'), 0.3);
  const odD = c => mul(od(c), 0.72);
  // ---- flat cut-out objects: fn(u, v, w, h) paints the front in box-local units; side faces reuse the nearest front edge cell
  const flat = (fn, sideK = 0.78) => c => {
    const b = c.box, hh = cw / 2;
    const u = Math.min(b.w - hh, Math.max(hh, c.p[0] - b.x)), v = Math.min(b.h - hh, Math.max(hh, c.p[1] - b.y));
    const col = fn(u, v, b.w, b.h, c);
    if (!col) return null;
    return isPanel(c) ? col : mul(col, sideK);
  };
  // text mask: rows of chars, one char per paint cell; '.' = nothing
  const cellAt = (rows, u, v) => { const r = rows[Math.floor(v / cw)]; return r ? r[Math.floor(u / cw)] : undefined; };
  const maskPaint = (rows, pal) => flat((u, v) => { const ch = cellAt(rows, u, v); return ch && ch !== '.' ? pal[ch] : null; });

  // ================= carrier =================
  const panelTop = lo ? 4 : 4.5;
  // --- plate bags (swimmer cut in tiers); the front shows only its upper loop field above the MOLLE panel
  const frontBag = c => {
    let col = odLite(c);
    if (hd && c.face === 'front' && c.p[1] >= 4 && c.p[1] < panelTop && Math.abs(c.p[0]) < 3) {   // one MOLLE slit row under the loop field
      const slit = (((c.p[0] + 3) % 1.5) + 1.5) % 1.5 < cw;
      col = slit ? mul(col, 0.6) : mul(mix(col, hex('#7c8a5e'), 0.4), mid ? 0.95 : (c.p[1] < 4.25 ? 1.02 : 0.9));
    }
    return edge(c, col);
  };
  const backBag = c => { const col = od(c); return edge(c, K.molle(c, col, 3.5, 5, -2.75, 2.75) || col); };
  const tiers = hd ? [[-3.25, 3.25, 2.75, 9.25], [-2.75, 2.75, 2, 2.75], [-2, 2, 1.5, 2]] : [[-3, 3, 3, 9], [-2, 2, 2, 3]];
  for (const [x0, x1, y0, y1] of tiers) {
    add([x0, y0, -3.25], [x1, y1, -2.25], frontBag, 'front plate bag');
    add([x0, y0, 2.25], [x1, y1, 3.25], backBag, 'back plate bag');
  }
  // --- lower front MOLLE panel: pocket mouth at the top edge, then three webbing rows. On the original the webbing rows
  //     are the DARK bands (about half as bright as the plain panel between them), crossed by small light bar-tacks.
  //     M: the first row is its own proud strap (below), so only the lower two rows are painted.
  const webRows = P3([5, 7], [6.5, 8], [5.5, 6.75, 8]);
  const MOUTH = hex('#252b1d');
  const frontPanel = c => {
    let col = mix(od(c), hex('#4a6446'), 0.1);                                      // plain panel about as bright as the loop field above
    if (mid && c.face === 'top') return mul(MOUTH, G(c, 0.08));                     // M: open pocket mouth the gear stands in
    if (c.face === 'front') {
      const x = c.p[0], y = c.p[1], wh = lo ? 1 : 0.5;
      const web = webRows.find(r => y >= r && y < r + wh);
      if (web != null) {
        const tack = lo ? Math.floor(x + 3.5) % 3 === 2 : (((x + 3.5) % 1.5) + 1.5) % 1.5 < cw;
        col = tack ? (mid ? mul(col, 0.8) : mul(mix(col, hex('#8e9a70'), 0.35), 0.95))              // M: half-pixel tacks stay subtle so the band still reads
          : mul(col, !mid && hd && y - web < 0.25 ? 0.8 : 0.7);                                         // darker webbing band; B: one lighter texel along the top
      }
    }
    return edge(c, col);
  };
  add(lo ? [-3, 4, -4] : [-3.5, 4.5, -4], lo ? [3, 9, -3.25] : [3.5, 9.25, -3.25], frontPanel, 'front MOLLE panel');
  // --- M: first webbing row = the pocket front, a strap standing proud of the panel so the pocket gear goes down behind
  //     it (as on the black JayPC). It runs from the GP pouch flap's inner edge to the TQ pouch's inner wall, so it never
  //     sits in front of either pouch. Lit top cell, darker webbing below it with faint bar-tacks; open mouth behind its
  //     front lip on top, dark lining on its back.
  if (mid) {
    const strap = c => {
      const col = mix(od(c), hex('#4a6446'), 0.1);
      if (c.face === 'back') return mul(MOUTH, G(c, 0.08));
      if (c.face === 'top') return c.p[2] < -4.5 ? mul(mix(col, hex('#8e9a70'), 0.3), 1.12) : mul(MOUTH, G(c, 0.08));
      if (c.face === 'bottom') return mul(col, 0.5);
      if (c.face !== 'front') return mul(col, 0.62);
      if (c.ev < cw) return mul(mix(col, hex('#8e9a70'), 0.3), 1.08);
      return mul(col, (((c.p[0] + 3.5) % 1.5) + 1.5) % 1.5 < cw ? 0.8 : 0.66);
    };
    add([-2.25, 5, -4.75], [2.75, 5.75, -4], strap, 'pocket webbing strap');
  }
  // --- cummerbund with side MOLLE
  const cumm = c => { const col = mul(od(c), 0.9); return edge(c, K.molle(c, col, 5.5, 3, -2.25, 2.25, { side: true }) || col, { stitch: false }); };
  add([-4.5, lo ? 4 : 5, -2.75], [4.5, lo ? 8 : 9, 2.75], cumm, 'cummerbund');
  // --- shoulder straps. The head hides everything above the collar line, so the window sits low and fully framed;
  //     a strip of grey tape runs down the inner side of each front window as on the original.
  //     M/B: the window is cut through the front and the riser's back face is painted as a dark lining, so no skin shows.
  for (const s of [-1, 1]) {
    const xa = lo ? (s < 0 ? -3 : 1) : (s < 0 ? -3.25 : 1.25), xb = xa + 2, cx = xa + 1;
    const inWin = (x, y) => (lo ? (s < 0 ? x < cx : x >= cx) && y >= 1 && y < 2
      : Math.abs(x - cx) < 0.5 && y >= 0.5 && y < 1.5 && !(!mid && y < 0.75 && s * (cx - x) > 0.25));   // B: inner top corner cut
    const riser = c => {
      if (isPanel(c) && inWin(c.p[0], c.p[1])) {
        if (lo) return c.face === 'front' ? mul(odD(c), 0.6) : odD(c);
        return c.face === 'front' ? null : mul(odD(c), 0.45);
      }
      let col = mix(od(c), hex('#98a27c'), 0.35);
      const tapeX = s < 0 ? c.p[0] >= cx + 0.5 : c.p[0] < cx - 0.5;
      if (hd && c.face === 'front' && tapeX && c.p[1] < 1.5) col = mul(hex('#6d6e6a'), G(c, 0.1));
      return edge(c, col, { stitch: false });
    };
    add([xa, lo ? -1 : -0.5, -3], [xb, lo ? 3 : 2.75, -2.5], riser, 'strap riser (front)');
    add([xa, lo ? -1 : -0.5, 2.5], [xb, lo ? 3 : 2.75, 3], c => edge(c, mix(od(c), hex('#98a27c'), 0.3), { stitch: false }), 'strap riser (back)');
  }
  // --- drag handle, sewn onto the back face of the top back tier
  add(lo ? [-1.25, 2, 3.25] : [-1.25, 1.5, 3.25], lo ? [1.25, 2.5, 3.75] : [1.25, 2, 3.75], odD, 'drag handle');

  // ================= upper chest: grenade | radio | tape patch =================
  // radio (Baofeng-style): screen in a black bezel, blue and orange buttons, keypad; antenna on its wearer's-right edge
  const RB = hex('#1c1d20'), SCR = hex('#6a7a6e'), KEY = hex('#4d5055'), BTN_B = hex('#3f6fb0'), BTN_O = hex('#c0662e');
  const radio = c => {
    if (c.face !== 'front') return mul(RB, c.face === 'top' ? 1.45 : 1);
    const u = c.p[0] - c.box.x, v = c.p[1] - c.box.y, i = Math.floor(u / cw), j = Math.floor(v / cw);
    if (lo) return j === 0 ? (i === 0 ? SCR : mul(RB, 1.3)) : i === 1 ? hex('#4a4d52') : RB;
    if (mid) {                                                                     // 4 x 5 cells
      if (j === 0) return mul(RB, 1.3);
      if (j === 1) return i === 1 || i === 2 ? SCR : RB;                           // screen inside the bezel
      if (j === 2) return i === 0 ? BTN_B : i === 3 ? BTN_O : RB;                  // buttons
      return i === 1 || i === 2 ? mul(KEY, (i + j) % 2 ? 1 : 0.82) : RB;           // keypad block, framed black so the outline stays clean
    }
    if (j >= 1 && j <= 3 && i >= 1 && i <= 6) return j === 3 ? mul(RB, 1.25) : hex('#6d7e70');   // screen with bezel
    if (j === 5) return i === 1 ? BTN_B : i === 6 ? BTN_O : RB;                                  // blue and orange buttons
    if (j >= 6 && j <= 9 && i >= 1 && i <= 6 && (i + j) % 2 === 0) return KEY;                   // keypad
    return j === 0 ? mul(RB, 1.3) : RB;
  };
  // (M: hung half a pixel higher so a band of loop field and the slit row show between it and the shears ring in the
  //  pocket; where its top rises above the plate bag, its belt clip bridges the gap to the strap riser - no daylight
  //  behind it from the side)
  add(lo ? [-2, 2, -4] : mid ? [-2, 1, -4] : [-2, 1.5, -4], [0, mid ? 3.5 : 4, -3.25], radio, 'radio');
  if (mid) add([-1.75, 1, -3.25], [-1.25, 1.5, -3], K.plastic(RB), 'radio belt clip');
  add(lo ? [-2, 0, -3.75] : mid ? [-2, -1, -3.75] : [-2, -0.5, -3.75], lo ? [-1.75, 2, -3.5] : mid ? [-1.75, 1, -3.5] : [-1.75, 1.5, -3.5], K.plastic(RB), 'antenna');
  if (hd) add([-0.5, mid ? 0.5 : 1, -3.75], [0, mid ? 1 : 1.5, -3.5], K.plastic(RB), 'radio knob');
  // F-1 grenade: lighter olive than the vest, horizontal segment grooves, grey fuze
  const GR = hex('#7d8c3c');
  const gren = c => {
    const u = c.p[0] - c.box.x, v = c.p[1] - c.box.y;
    if (c.face === 'top' || c.face === 'bottom') return mul(GR, 0.9);
    if (lo) return mul(GR, G(c, 0.1));
    if (mid) return Math.floor(v / 0.5) % 2 ? mul(GR, 0.72) : mul(GR, G(c, 0.06));
    return (Math.floor(v / 0.25) % 2 || Math.floor((isPanel(c) ? u : c.p[2]) / 0.25) % 2) ? mul(GR, 0.85) : mul(GR, 1.04);
  };
  if (lo) add([-3, 3, -3.75], [-2, 4, -3.25], gren, 'grenade');
  else if (mid) add([-3, 2, -4], [-2, 3.5, -3.25], gren, 'grenade');
  else {
    add([-3, 2, -4], [-2, 3.75, -3.25], gren, 'grenade');
    add([-3, 2.125, -4], [-2, 3.625, -3.25], gren, 'grenade (rounding)', { rot: [0, 45, 0], pivot: [-2.5, 2.875, -3.625] });
  }
  add(lo ? [-2.75, 2.5, -3.75] : [-2.75, 1.5, -3.75], lo ? [-2.25, 3, -3.25] : [-2.25, 2, -3.5], K.plastic(hex('#7c7a70')), 'grenade fuze');
  // grey tape patch with one line of black hand-written letters (kept clear of the can below)
  const TG = hex('#8f918b'), TK = hex('#2a2a2e');
  // (A and M: soft grey-brown ink, one uneven stroke line, so the patch never reads as a pair of eyes)
  const tapeMask = P3(['.K.'], ['.k.kk', '..k..'], ['...........', '.K.K.K.KKK.', '..K..K.K.K.', '.K.K.K.KKK.', '...........']);
  const tape = c => {
    if (!isPanel(c)) return mul(TG, 0.8);
    const ch = cellAt(tapeMask, c.p[0] - c.box.x, c.p[1] - c.box.y);
    return ch === 'K' ? (lo ? mul(TG, 0.6) : TK) : ch === 'k' ? mul(TG, 0.55) : mul(TG, G(c, 0.1));
  };
  add(P3([0, 2, -3.5], [0, 2, -3.5], [-0.25, 2, -3.5]), P3([3, 3, -3.25], [2.5, 3, -3.25], [2.5, 3.25, -3.25]), tape, 'tape patch');

  // ================= lower panel contents =================
  // leather card standing in the pocket, dark reddish-brown
  const CARD = hex('#7e4a30');
  const card = c => {
    const u = c.p[0] - c.box.x, v = c.p[1] - c.box.y;
    if (!isPanel(c)) return mul(CARD, 0.75);
    if (hd && !mid && u < cw) return mul(CARD, 0.72);                              // B only: M keeps the card one flat colour
    return mul(CARD, (v < cw ? 1.12 : 1) * G(c, 0.1));
  };
  add(lo ? [-2, 4, -4.25] : [-2.5, 4, -4.25], lo ? [-1, 5, -4] : [-1, 5.5, -3.5], card, 'leather card');
  // HOT ROD energy-drink can standing in the pocket: cream body, silver rim, red lettering in separate lines
  // (red cells never touch vertically, so the lettering cannot form a glyph)
  const canPal = { S: hex('#b5b7b3'), W: hex('#e6e2d8'), R: hex('#c4282b'), r: mix(hex('#c4282b'), hex('#e6e2d8'), 0.55) };
  // (M: only the upper two rows show above the pocket strap, so the front starts cream - the silver lid is its top face -
  //  and the can still reads cream with red lettering instead of a grey box)
  const canMask = P3(['WW', 'RW'], ['WWW', 'RRW', 'WWW'], ['SSSSSS', 'WrrrrW', 'WWWWWW', 'RRWRRR', 'WWWWWW', 'RRRWRW']);   // B: faint small print, then HOT / ROD (uneven, left-aligned)
  const can = c => {
    if (c.face === 'top') return canPal.S;
    if (c.face === 'bottom') return mul(canPal.S, 0.7);
    if (c.face !== 'front') { const v = c.p[1] - c.box.y; return mul(v < cw && hd ? canPal.S : canPal.W, 0.85); }
    const ch = cellAt(canMask, c.p[0] - c.box.x, c.p[1] - c.box.y) || 'W';
    return canPal[ch];
  };
  add(lo ? [0, 3.5, -4.75] : [0.5, 4, -4.5], lo ? [2, 5.5, -4] : [2, 5.5, -3.5], can, 'HOT ROD can'); // A: on the pixel grid, wholly in front of the panel (its top never shares the panel's top plane)
  const PK = hex('#d06a9c'), PKd = mul(PK, 0.78);
  if (lo) {
    add([-2, 5, -4.25], [-1, 8, -4], K.solid(PK, 0.05), 'cable coil');
    add([1, 8, -4.75], [2, 9, -4.25], K.solid(hex('#3b40a8'), 0.05), 'tape roll');
  } else {
    // trauma shears: dark ring handle over the card's right half, grey blade down the webbing
    const RING = hex('#26262a');
    const ring = mid ? maskPaint(['.K.', 'K.K', 'K.K'], { K: RING })            // rounded loop, corners open onto the card; its lower part goes down behind the strap
      : flat((u, v, w, h) => { const a = ((u - w / 2) / 0.62) ** 2 + ((v - h / 2) / 0.75) ** 2, b = ((u - w / 2) / 0.32) ** 2 + ((v - h / 2) / 0.45) ** 2; return a <= 1 && b > 1 ? RING : null; });
    // (M: a thin plate standing in front of the card and the cable, so its outer faces never share a plane with theirs)
    add([mid ? -1.5 : -2, mid ? 4 : 4.5, -4.5], [mid ? 0 : -0.75, mid ? 5.5 : 6, mid ? -4.25 : -4], ring, 'shears handle');   // M: centred over the blade, so the card shows beside it
    // (M: the blade comes out from under the strap)
    add(mid ? [-1, 5.5, -4.25] : [-1.5, 6, -4.25], mid ? [-0.5, 8, -4] : [-1, 8.25, -4], flat((u, v, w, h) => mul(hex('#55585c'), !mid && v > h - 0.25 ? 0.8 : 1)), 'shears blade');
    // pink cable: one long narrow loop hanging under the shears, tied with a strip of vest webbing
    const pinkMask = mid ? ['.P', 'P.', 'Q.', '.P', '.Q', 'P.']                  // M: one coiled strand, not a solid block
      : ['.PP.', 'P..Q', 'P..Q', 'P..Q', 'P..Q', 'P..Q', 'P..Q', '.PQ.', '.QP.', 'P..Q', 'P..Q', '.PQ.'];
    add(mid ? [-2, 5.5, -4.25] : [-2.5, 5.5, -4.25], mid ? [-1, 8.5, -4] : [-1.5, 8.5, -4], maskPaint(pinkMask, { P: PK, Q: PKd }), 'cable coil');
    add(mid ? [-2, 6.5, -4.5] : [-2.5, 6.75, -4.5], mid ? [-1, 7, -4.25] : [-1.5, 7, -4], c => edge(c, mul(odLite(c), c.face === 'front' ? 1 : 0.8), { stitch: false }), 'cable tie');
    // small silver clip hanging from the pocket mouth between the shears and the can (B only: in M it was one more
    // grey speck in an already busy spot)
    const CL = hex('#b8bcc0');
    if (!mid) add([-0.5, 4.5, -4.25], [0, 5.75, -4], maskPaint(['SS', 'S.', 'S.', 'S.', 'SS'], { S: CL }), 'silver clip');
    // marker: white cap, pale body; it stands in the pocket beside the can and runs down behind the J-hook
    add(mid ? [2, 4.5, -4.25] : [2, 4.75, -4.25], [2.5, mid ? 7 : 8, -4], flat((u, v) => (v < 0.5 ? hex('#ecebe4') : !mid && v < 0.75 ? hex('#5a5c5e') : hex('#cfc6bd'))), 'marker');
    // J-hook: grey hook with a red lock on its right leg; the blue tape roll hangs from it
    const JG = hex('#8d8e92'), JR = hex('#b3262a');
    const jMask = mid ? ['GGG', 'G.R', '..R', '..G'] : ['.GGG..', 'G...G.', 'G...G.', '....RR', '....RR', '....RR', '....G.', '....G.'];
    add([1.25, 6.5, -4.5], [2.75, 8.5, -4], maskPaint(jMask, { G: JG, R: JR }), 'J-hook');
    const BL = hex('#3b40a8');
    const roll = mid ? maskPaint(['.B.', 'B.B', '.B.'], { B: BL })                 // open corners so it reads round, not as a square
      : flat((u, v, w, h) => { const r = Math.hypot(u - w / 2, v - h / 2); return r <= 0.75 && r > 0.45 ? mul(BL, r > 0.62 ? 0.9 : 1) : null; });
    add([1.25, 8.5, -4.5], [2.75, 10, -4], roll, 'tape roll');
  }

  // ================= side pouches =================
  // GP pouch (wearer's right): pale sand-grey A-TACS AU with the teal cast of the EFT texture, strongest low down and on the edges
  const TEAL = hex('#5f9a90');
  const au = (c, k = 1) => {
    let col;
    if (mid) {   // M: 1-px blotches, softened, and a smooth teal wash instead of scattered patches
      col = mix(atacsAU(c.p, 1), hex('#a3a08a'), 0.25);
      col = mix(col, TEAL, Math.min(0.6, clamp01((c.p[1] - 8) / 1.5) * 0.5 + (c.ex < 0.5 ? 0.15 : 0)));
    } else {
      const q = snap(c.p, K.cell), n = fbm(q[0] * 0.9 + 41, q[1] * 0.9, q[2] * 0.9 + 7);
      col = atacsAU(c.p, K.cell);
      if (n > (lo ? 0.64 : 0.6) - 0.12 * clamp01((c.p[1] - 7) / 2.25)) col = mix(col, TEAL, 0.55);   // teal patches, more of them low down
    }
    return mul(col, 1.08 * k * G(c, 0.08));
  };
  // (pouch bottoms sit below the carrier's so no two bottom faces share a plane)
  const flapTab = c => c.face === 'front' && c.p[0] >= (lo ? -4 : -3.75) && c.p[0] < (lo ? -3 : -3.25);
  add(lo ? [-4, 5, -4.25] : [-4.25, 5.5, -4.25], lo ? [-2, 10, -2.75] : [-2.5, 9.5, -2.75],
    c => edge(c, lo && c.face === 'front' && c.p[1] < 6 ? au(c, flapTab(c) ? 0.72 : 0.9) : au(c)), 'GP pouch');   // A: flap painted on the top row
  if (hd) add([-4.5, 5.25, -4.5], [-2.25, 6.75, -2.75], c => edge(c, au(c, flapTab(c) ? 0.72 : 0.9)), 'GP pouch flap');
  add(lo ? [-4, 6, -4.5] : [-3.75, 6.75, -4.5], lo ? [-3, 7, -4.25] : [-3.25, 7.5, -4.25], K.plastic(hex('#161718')), 'GP buckle');
  // tourniquet pouch (wearer's left): vest green, black tourniquet strapped down its front, the top of an orange-brown
  // AK magazine rising out of its open mouth (M: the top face is the mouth - lit rim, dark lining inside)
  const POUCH_LIN = hex('#252b1d');
  add(lo ? [2, 5, -4.5] : [2.75, 5.5, -4.5], lo ? [4, 10, -2.75] : [4.25, 9.5, -2.75], c => {
    if (mid && c.face === 'top') return c.ex < cw ? mul(od(c), 0.88 * 1.25) : mul(POUCH_LIN, G(c, 0.08));
    return edge(c, mul(od(c), 0.88));
  }, 'TQ pouch');
  const TQ = hex('#1b1c1e'), TQg = hex('#6a6c6e'), TQs = hex('#3c3e40');
  const tqMask = P3(['K', 'K', 's', 'K'], ['KK', 'KK', 'KK', 'ss', 'KK', 'gg', 'KK'],
    ['KKKK', 'KggK', 'KKKK', 'KKKK', 'KKKK', 'KKKK', 'sKKs', 'KssK', 'KssK', 'sKKs', 'KKKK', 'KggK', 'KggK', 'KKKK']);
  const tq = c => { const ch = cellAt(tqMask, c.p[0] - c.box.x, c.p[1] - c.box.y) || 'K'; const col = ch === 'g' ? TQg : ch === 's' ? TQs : TQ; return isPanel(c) ? col : mul(col, 0.8); };
  add(lo ? [3, 6, -4.75] : [3, 6, -4.75], lo ? [4, 10, -4.5] : [4, 9.5, -4.5], tq, 'tourniquet');   // A: one column of pouch shows beside it
  if (hd) add([2.75, 6.5, -5], [4.25, 7, -4.75], c => mul(hex('#202224'), c.face === 'top' ? 1.4 : (c.p[0] > 4 || c.p[0] < 3) ? 1.25 : 1), 'tourniquet windlass');
  // magazine, tilted so its outer end sits lower; only a short, wider-than-tall stub shows above the pouch mouth.
  // M (as on the black JayPC): orange-brown bakelite, broad side forward, lit feed-lip edge, one darker rib, darker spine
  // sides, and a narrower feed-lip block on top with one brass round (copper tip toward the outer end).
  const BR = hex('#94522f'), MT = hex('#2a2826');
  const mag = mid ? c => {
    if (c.face === 'top') return mul(MT, 1.3);
    if (c.face === 'bottom') return mul(BR, 0.5);
    let col = mul(BR, G(c, 0.06));
    if (!isPanel(c)) return mul(col, 0.7);                                             // spine sides
    if (c.ev < cw) return mul(col, 1.18);                                              // lit feed-lip edge
    if (Math.floor(c.eu / cw) === 1) col = mul(col, 0.84);                             // rib
    return col;
  } : c => {
    if (c.face === 'top') return MT;
    if (c.face === 'bottom') return mul(BR, 0.5);
    if (hd && c.ev < cw) return MT;                                                    // B: top rim (A: the top face alone)
    if (!isPanel(c)) return mul(BR, 0.7);
    if (hd && (c.eu < cw || c.eu > c.fw - cw)) return mul(BR, 0.72);                   // B: darker side rims
    return mul(BR, G(c, 0.1));
  };
  // (tilted 18 degrees, the full-width body cannot go deeper without its inner lower corner leaving the pouch beside the
  //  panel, so in M a narrower lower body carries the magazine on down into the pouch, more than 1 px below the rim even at
  //  the shallow inner end; every corner stays between the pouch walls)
  const MAGROT = { rot: [0, 0, 18], pivot: lo ? [3, 5, -3.875] : [3.5, 5.5, -3.875] };
  add(lo ? [2.125, 4, -4.25] : [2.875, 4.5, -4.25], lo ? [3.875, 5.5, -3.5] : [4.125, 6, -3.5], mag, 'AK magazine', MAGROT);
  if (mid) {
    const BRASS = hex('#caa24e'), COPPER = hex('#b5703f');
    add([3.125, 5.75, -4.125], [3.875, 6.75, -3.625], mag, 'AK magazine (lower body)', MAGROT);
    add([3, 4.25, -4.125], [4, 4.5, -3.625], c => (c.face !== 'top' ? mul(MT, isPanel(c) ? 1.2 : 0.9) : c.eu > c.fw - cw ? COPPER : BRASS), 'magazine feed lips', MAGROT);
  }

  // ================= dangler =================
  const fgCol = c => mix(atacsFG(c.p, K.cell), hex('#7a7a70'), 0.3);
  const fg = c => {
    let col = mul(fgCol(c), G(c, 0.08));
    if (c.face === 'front') {
      const x = c.p[0], y = c.p[1];
      if (lo) {
        if (y < 10) return hex('#454a47');                                             // hook-and-loop band
        if (y < 12 && (x < -1 || (x >= 1 && x < 2))) return y < 11 ? hex('#e2cc80') : hex('#b3262a');   // shells 2 + 1, brass heads up
        if (y < 12 && x >= 2) return hex('#3a3d3c');                                   // empty shell loop
        if (y >= 12 && y < 13 && Math.abs(x) < 1) return hex('#3a403d');               // loop patch
      } else {
        if (y >= 9.75 && y < 10.25) col = mul(hex('#454a47'), G(c, 0.1));
        else if (Math.abs(x) < 0.75 && y >= 12.25 && y < 13.25) col = mul(hex('#3a403d'), !mid && y >= 12.75 && y < 13 ? 1.35 : G(c, 0.08));
      }
    }
    return edge(c, col);
  };
  // nearly as wide as the panel, straight sides, then rounding in to a short flat bottom
  const dang = P3([[-3, 3, 9, 12], [-2, 2, 12, 13], [-1, 1, 13, 14]],
    [[-3.25, 3.25, 9.25, 11.75], [-2.75, 2.75, 11.75, 12.75], [-2, 2, 12.75, 13.25], [-1.25, 1.25, 13.25, 13.75]],
    [[-3.25, 3.25, 9.25, 11.5], [-3, 3, 11.5, 12.25], [-2.75, 2.75, 12.25, 12.75], [-2.25, 2.25, 12.75, 13.25], [-1.75, 1.75, 13.25, 13.75], [-1.25, 1.25, 13.75, 14]]);
  for (const [x0, x1, y0, y1] of dang) add([x0, y0, -3.75], [x1, y1, -3.25], fg, 'dangler');
  if (hd) {
    // hanger straps from the panel over the band
    for (const x of [-2, 0, 2]) add([x - 0.5, 9.25, -4], [x + 0.5, 10.25, -3.75], c => edge(c, mul(hex('#6e726e'), G(c, 0.1)), { stitch: false }), 'hanger strap');
    // two banks of 12ga shells, brass up, tops leaning to the centre line; two dark elastic bands per bank
    const shell = c => {
      if (c.face === 'top') return hex('#cdb878');
      const v = c.p[1] - c.box.y;
      if (v < (mid ? 0.5 : 0.25)) return mul(hex('#cdb878'), isPanel(c) ? 1 : 0.8);
      return mul(hex('#b3262a'), isPanel(c) ? 1 : 0.75);
    };
    const bands = c => mul(hex('#3a3d3c'), c.face === 'top' ? 1.3 : G(c, 0.08));
    for (const [bx, n, s] of [[-1.25, 3, 1], [2, 2, -1]]) {
      const cy = 11.25, rot = [0, 0, s * 18], pivot = [bx, cy, -4];
      for (let i = 0; i < n; i++) {
        const sx = bx + (i - 1) * 0.75;
        add([sx - 0.25, cy - 0.75, -4.25], [sx + 0.25, cy + 0.75, -3.75], shell, '12ga shell', { rot, pivot });
      }
      for (const y of [-0.5, 0]) add([bx - 1.125, cy + y, -4.5], [bx + 1.125, cy + y + 0.25, -4.25], bands, 'shell elastic', { rot, pivot });
    }
  }
  return { S: K.S, px: K.px, cell: K.cell, boxes: B };
};
