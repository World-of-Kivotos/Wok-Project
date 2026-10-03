// ================= Crye Precision AVS plate carrier (MultiCam) — EFT reference =================
// tarkov.dev render 67ab49aab9c7a1e18c095686 (inspect view: -image.webp). Same carrier, same gear layout as the Ranger
// Green AVS (armors/avs.js, whose 2x geometry this reuses), but this colourway carries its own colours: MultiCam
// everywhere (khaki ground, olive-green / brown / dark-brown shapes, cream flecks), the shoulder pads lit almost cream on
// top; a near-black charcoal CAT tourniquet (lighter grey clip band, grey windlass rod) over the wearer's right pad end;
// a small MultiCam admin pouch with a SAND side-release buckle; a silver carabiner with a COPPER gate sleeve; the black
// PTT with its coiled cord down to the radio antenna; three rifle mags in dark gunmetal with lighter grey pistol mags in
// front (flat grey tops, no brass showing), in dark olive-brown open-top pouches laced with KHAKI shock cords; khaki
// pull cords dangling onto the groin band; on the wearer's right a MultiCam GP pouch (black zipper) and the taller single
// mag pouch (hangs lowest): a MultiCam flap pouch on its outer half and a khaki open-top sleeve on its inner half, with a
// dark magazine standing in the sleeve; on the left a MultiCam radio pouch with a khaki top binding; MOLLE cummerbund and
// back bag; a light camo band under the pouches and an inverted-trapezoid groin protector, about as bright as the chest,
// with four rows of raised laser-cut tiles (camo printed, top-lit, framed by crisp dark outlines and cuts; first row on
// the band) and a plain light camo bottom third.
ARMORS.avs_multicam = function (mode) {
  const K = kit('M');                          // always the 2x style, whatever mode is asked for
  const { edge, G, isPanel, isSide, px } = K;
  const B = [];

  // ---------------- palette (albedo; lit front faces render at about three quarters of it) ----------------
  const LINING = hex('#1c1d19');
  const TAN = hex('#c9c0a0');                  // light grey-khaki bungee knobs
  const LACE = hex('#aea57a');                 // khaki shock cords / pull cords
  const PDK = hex('#4c4430');                 // dark olive-brown of the laced mag pouches
  const KHAKI = hex('#a79d77');                // radio pouch top binding / single mag pouch's inner sleeve
  const STEEL = hex('#636a6e'), PSTEEL = hex('#868b86'), DSTEEL = hex('#3d4144');
  const BUCKLE = hex('#d0c29c');                // sand side-release buckle on the admin pouch

  // MultiCam sampled on the half-pixel grid, scaled so the shapes are 1-3 px: khaki ground, olive-green, olive-brown and
  // dark-brown mid shapes, sparse cream flecks (colours taken off the render's lit chest and groin plate)
  const camoAt = (p, s = 0.5) => layers(snap(p, px).map(v => v * s), 0, '#8d8561',
    [['#aca47f', 0.55, 0.6, 3], ['#6c7646', 0.62, 0.57, 17], ['#7b6646', 0.7, 0.62, 41], ['#534b32', 0.9, 0.66, 77], ['#423728', 1.15, 0.72, 5], ['#bfb996', 1.2, 0.76, 91]]);
  // lit from above like the render: a touch brighter at the chest, darker toward the hem. `o` shifts the pattern so a
  // pouch sewn onto the bag does not continue the bag's camo shapes (separate pieces of cloth)
  const fab = (c, k = 1, amt = 0.05, o = 0) => mul(camoAt(o ? [c.p[0] + o, c.p[1] + o * 0.6, c.p[2] - o * 0.4] : c.p),
    k * G(c, amt) * (1.05 - 0.01 * Math.max(0, Math.min(16, c.p[1]))));
  // MOLLE webbing is printed MultiCam nylon, calmer and lighter than the Cordura: a tape row every 1.5 px (the camo
  // shows through part-way) with bar tacks and a shadow row under it (the kit's rows alone vanished in the camo)
  const WEB = hex('#a29c76');
  const webbing = (c, col, y0, rows, h0, h1, { side = false, t = 0.65 } = {}) => {
    if (!(isPanel(c) || (side && isSide(c)))) return null;
    const h = isPanel(c) ? c.p[0] : c.p[2];
    if (h < h0 || h > h1) return null;
    for (let i = 0; i < rows; i++) {
      const dy = c.p[1] - (y0 + i * 1.5);
      if (dy >= 0 && dy < 1) {
        if (dy >= 0.5) return mul(mix(col, WEB, 0.3), 0.62);                  // shadow under the tape
        const tape = mul(mix(col, WEB, t), 1.08);
        return (((h - h0) % 1.5) + 1.5) % 1.5 < 0.5 ? mul(tape, 0.7) : tape;   // bar tacks
      }
    }
    return null;
  };
  const seam = (v, period) => ((v % period) + period) % period < px;

  // ---------------- carrier ----------------
  const bag = c => {
    if (c.face === 'top') return mul(LINING, G(c, 0.1));                       // black inner lining at the neckline
    let col = fab(c);
    if (c.face === 'front') col = webbing(c, col, 2.25, 2, -0.5, 4.25) || col;   // MOLLE on the wearer's left upper chest
    return edge(c, col);
  };
  B.push(box('body', [-4.25, 1.25, -3.25], [4.25, 10.25, -2.5], bag, { tag: 'front plate bag' }));
  const back = c => {
    if (c.face === 'top') return mul(LINING, G(c, 0.1));
    const col = fab(c);
    return edge(c, (c.face === 'back' && webbing(c, col, 2.5, 4, -3.5, 3.5)) || col);
  };
  B.push(box('body', [-4.25, 0.75, 2.5], [4.25, 10.25, 3.25], back, { tag: 'back plate bag' }));
  const cumm = c => {
    const col = fab(c, 0.95);
    return edge(c, webbing(c, col, 5.5, 3, -2.5, 2.5, { side: true }) || col, { stitch: false });
  };
  // ends 0.25 above the bags' bottoms so the bottom faces never share a plane
  B.push(box('body', [-4.5, 4.75, -2.75], [4.5, 10, 2.75], cumm, { tag: 'cummerbund' }));
  B.push(box('body', [-1.25, 0.5, 3.25], [1.25, 1.5, 3.75], c => edge(c, fab(c, 0.8), { stitch: false }), { tag: 'drag handle' }));

  // ---------------- padded shoulder straps ----------------
  // what shows beside the head: a thick rounded pad on top of each shoulder (the brightest fabric on the carrier in the
  // render: light khaki camo, lit almost cream on top) and the front / back risers below the chin with the tan-bound pad
  // ends; the risers stop 0.25 into the head
  const hump = c => {
    if (c.face === 'bottom') return mul(LINING, 1.2);
    const col = fab(c, 1.04, 0.06);
    if (c.face !== 'top') return mul(mix(col, TAN, 0.2), 1.05);               // pad ends and sides, light like the render
    return mul(mix(col, TAN, 0.25), 1.12);                                     // lit top of the pad
  };
  const BIND = TAN;
  const riser = front => c => {
    if (c.face === 'bottom') return mul(LINING, 1.2);
    const y = c.p[1], yb = c.box.y + c.box.h, ax = Math.abs(c.p[0]);
    if (front && y >= yb - 0.5) return mul(mix(BIND, fab(c), 0.35), G(c, 0.06) * (c.face === 'front' ? 1 : 0.85));   // bound pad end
    let col = fab(c, front && c.face === 'front' ? 1.09 : 1.04, 0.06);
    if (isPanel(c) && seam(ax - 2.25, 1)) col = mul(col, 0.9);                // stitching along the strap
    return edge(c, col, { stitch: false });
  };
  for (const s of [-1, 1]) {
    const X = (a, b) => (s < 0 ? [a, b] : [-b, -a]);
    const [r0, r1] = X(-4.25, -1.75), [h0, h1] = X(-5.5, -4.5), [k0, k1] = X(-4.5, -1.75), [b0, b1] = X(-5.75, -4.25);
    // stepped dome: 1.5-wide base + 1-wide crown (0.25 shorter at each end so no two faces share a plane)
    B.push(box('body', [b0, -1.25, -3.5], [b1, -0.25, 3.5], hump, { tag: 'shoulder pad hump' }));
    B.push(box('body', [h0, -1.75, -3.25], [h1, -0.75, 3.25], hump, { tag: 'shoulder pad crown' }));
    B.push(box('body', [r0, -0.25, -3.75], [r1, 1.25, -2.5], riser(true), { tag: 'strap front' }));
    B.push(box('body', [k0, -0.25, 2.75], [k1, 1.5, 3.5], riser(false), { tag: 'strap back' }));
  }
  // light grey-khaki bungee knobs at the pads' outer front corners (two on the right strap, one on the left)
  const knob = K.solid(TAN, 0.06);
  for (const [x0, y0] of [[-4.25, 1.25], [-4.75, 0.5], [3.75, 1.25]])
    B.push(box('body', [x0, y0, -3.75], [x0 + 0.5, y0 + 0.5, -3.25], knob, { tag: 'bungee knob' }));

  // ---------------- upper chest gear ----------------
  // CAT tourniquet over the wearer's right pad end: near-black charcoal with a lighter grey clip band on top and the
  // grey windlass rod standing vertically at the bottom
  const TQB = hex('#474b4c'), TQC = hex('#7b7e79');
  const tq = c => {
    const v = c.p[1] - c.box.y;
    if (c.face === 'top') return mul(TQC, 1.1);
    if (v < 0.75) return mul(TQC, G(c, 0.05));                                 // clip band
    if (v < 1) return mul(TQB, 0.8);                                           // strap edge under the clips
    return mul(TQB, G(c, 0.06));
  };
  // the TQ and its rod reach back to the bag (z -3.25) so no air gap shows from the side
  B.push(box('body', [-3.75, 0.25, -4], [-2.25, 3.25, -3.25], tq, { tag: 'tourniquet' }));
  const ROD = hex('#6c7173');
  B.push(box('body', [-3.25, 2.25, -4.25], [-2.75, 3.75, -3.25], c => mul(ROD, c.face === 'top' ? 1.2 : c.face === 'bottom' ? 0.75 : isSide(c) ? 0.85 : G(c, 0.04)), { tag: 'TQ windlass rod' }));
  // small MultiCam admin pouch: camo webbing above the sand buckle, buckle in the middle, dark camo strap tail below
  const flap = c => {
    let col = mul(mix(fab(c, 1, 0.05, 7.3), KHAKI, 0.2), 1.06);               // its own piece of camo, a little lighter
    if (c.face === 'front') {
      if (c.p[0] > -2 && c.p[0] < -1 && c.p[1] < 2.5) col = mix(col, BUCKLE, 0.3);   // webbing running up from the buckle
      else if (c.ev > c.fh - px) col = mul(col, 0.8);                          // shadow along the lower edge
    }
    return edge(c, col, { stitch: false });
  };
  // (a real little pouch, half a pixel deep, so it stands off the bag's camo like in the render)
  B.push(box('body', [-2.5, 1.5, -3.75], [-0.5, 4.5, -3.25], flap, { tag: 'admin pouch' }));
  const buckle = c => {
    if (c.face !== 'front') return mul(BUCKLE, c.face === 'top' ? 1.1 : 0.8);
    return mul(BUCKLE, c.ev < c.fh / 2 ? 1.08 : 0.9);
  };
  B.push(box('body', [-2, 2.5, -4], [-1, 3.5, -3.75], buckle, { tag: 'buckle' }));
  // strap tail lying on the pouch front under the buckle (flush with the buckle, so the pair reads as one strap)
  B.push(box('body', [-2, 3.5, -4], [-1, 4.5, -3.75], c => edge(c, fab(c, 0.72, 0.05, 7.3), { stitch: false }), { tag: 'buckle strap tail' }));
  // tall silver carabiner with a copper gate sleeve, left of the PTT, tilted bottom-outward like the render
  const SIL = hex('#aab0b4'), GATE = hex('#c27b48');
  const carab = c => {
    const t = px;                                                               // frame one texel thick
    const inU = c.eu > t && c.eu < c.fw - t, inV = c.ev > t && c.ev < c.fh - t;
    if (isPanel(c)) {
      if (inU && inV) return null;                                              // the opening
      if (!inU && !inV) return null;                                            // rounded corners
      if (c.eu < t && c.ev > c.fh * 0.4 && c.ev < c.fh * 0.7) return GATE;   // copper gate sleeve (two texels)
      return mul(SIL, c.ev < c.fh / 2 ? 1.1 : 0.85);
    }
    if ((c.face === 'top' || c.face === 'bottom') && !inU) return null;
    if ((c.face === 'left' || c.face === 'right') && !inV) return null;
    return mul(SIL, 0.75);
  };
  // 3 x 6 texels (frame, 1-texel opening, frame); its top tucks under the PTT's lower-left corner, a short camo webbing
  // tab behind the top bar holds it to the bag
  B.push(box('body', [0.5, 1.25, -3.875], [2, 4.25, -3.625], carab, { tag: 'carabiner', rot: [0, 0, -8], pivot: [1.25, 1.25, -3.75] }));
  B.push(box('body', [1, 1.5, -3.75], [1.5, 2, -3.25], c => edge(c, fab(c, 0.75), { stitch: false }), { tag: 'carabiner webbing tab' }));
  // PTT hanging just under the wearer's left pad end (resting on the bag), coiled cord down to the radio's stub antenna
  const PTTB = hex('#2e2f2d');
  const ptt = c => {
    if (c.face === 'front') {
      if (c.ex >= 0.5) return hex('#171816');                                  // speaker grille
      return mul(PTTB, c.ev < 0.5 ? 1.25 : 1);
    }
    return mul(PTTB, c.face === 'top' ? 1.3 : 0.95);
  };
  B.push(box('body', [1.75, 1.25, -4], [3.75, 2.75, -3.25], ptt, { tag: 'PTT' }));
  // the cord sits 0.125 in front of the antenna so their front faces never share a plane where they meet
  const coil = c => mul(hex('#2c2e2c'), Math.floor((c.p[1] + c.p[0]) / px) % 2 ? 0.72 : 1.18);
  B.push(box('body', [3.25, 2.75, -3.875], [3.75, 4.75, -3.375], coil, { tag: 'coiled PTT cord', rot: [0, 0, -12], pivot: [3.5, 2.75, -3.625] }));
  // stub antenna rising out of the radio pouch; kept in front of the bag so it shares no side plane with it
  B.push(box('body', [3.75, 4, -3.75], [4.25, 6, -3.25], K.plastic(hex('#2b2c2a')), { tag: 'radio antenna' }));

  // ---------------- front placard + mag bank ----------------
  const placard = c => {
    let col = fab(c);
    if (c.face === 'top') col = mul(col, 1.12);
    return edge(c, col);
  };
  B.push(box('body', [-3.5, 4.5, -3.5], [3.5, 10, -3.25], placard, { tag: 'mag placard' }));
  // open-top mag pouches: dark olive-brown body laced with khaki shock cords (two cord rows a pixel apart), a dark
  // webbing strip between the rifle and pistol sleeves, light binding on the mouth
  const pouch = c => {
    if (c.face === 'top') return c.ex < px ? mul(mix(fab(c), LACE, 0.4), 1.05) : mul(LINING, 1.3);   // open mouth
    let col = mul(mix(fab(c, 1, 0.05, 3.1), PDK, 0.7), G(c, 0.05));
    if (c.face === 'front') {
      const i = Math.floor(c.eu / px), j = Math.floor(c.ev / px);
      if (j === 0) return mul(mix(col, LACE, 0.45), 1.12);                     // elastic top binding
      if (j === 5) return mul(col, 0.86);                                      // MOLLE strap at the bottom
      if (j === 3) return mix(col, LACE, i === 2 ? 0.45 : 0.8);                // khaki shock cord
      return mul(col, i === 2 ? 0.78 : 1.04);                                  // dark webbing strip between the sleeves
    }
    return edge(c, col, { stitch: false });
  };
  // Magazines read as magazines and sit IN the pouches: feed-lip block with one brass round on top, broad side forward,
  // darker spine sides, one darker rib; dark gunmetal rifle mags, lighter grey pistol mags in front
  const MAGTOP = hex('#2b2e30'), BRASS = hex('#caa24e'), COPPER = hex('#b5703f');
  const magPaint = (base, ribs, capped = false) => c => {
    if (c.face === 'bottom') return mul(base, 0.6);
    if (c.face === 'top' && capped) return mul(MAGTOP, c.ex < px ? 1.5 : 1);             // under the feed lips
    if (c.face === 'top') {
      const n = Math.max(1, Math.round(c.fw / px)), i = Math.min(n - 1, Math.floor(c.eu / px));
      const lo = Math.floor((n - 1) / 2) - (n >= 5 ? 1 : 0), hi = Math.floor(n / 2) + (n >= 5 ? 1 : 0);
      if (i >= lo && i <= hi) return i === hi && n >= 3 ? COPPER : BRASS;                  // the top round
      return mul(MAGTOP, c.ex < px ? 1.5 : 1);                                              // feed lips / follower
    }
    let col = mul(base, G(c, 0.04));
    if (!isPanel(c)) return mul(col, 0.74);                                                 // spine
    if (c.ev < px) return mul(col, 1.25);                                                   // feed-lip edge
    if (ribs) {
      const n = Math.max(1, Math.round(c.fw / px)), i = Math.floor(c.eu / px);
      if (i === Math.floor(n / 2)) col = mul(col, 0.8);                                   // rib
    }
    return col;
  };
  // pistol mags: light grey with a flat grey top like the render (no brass shows on them)
  const rifle = magPaint(STEEL, true, true), pistolBody = magPaint(PSTEEL, false);
  const pistol = c => (c.face === 'top' ? mul(PSTEEL, 1.1 * G(c, 0.04)) : pistolBody(c));
  const lips = c => {
    if (c.face !== 'top') return mul(DSTEEL, isPanel(c) ? 1.15 : 0.9);
    return c.eu > c.fw - px ? COPPER : BRASS;
  };
  // khaki pull cords dangling onto the groin band: a cord texel over a lighter pull tab
  const cord = c => edge(c, mul(c.p[1] >= 10.5 ? mul(LACE, 1.1) : mul(LACE, 0.8), G(c, 0.05)), { stitch: false });
  for (const cx of [-2.25, 0, 2.25]) {
    B.push(box('body', [cx - 1, 7, -5], [cx + 1, 10, -3.5], pouch, { tag: 'mag pouch' }));
    B.push(box('body', [cx - 0.75, 4.75, -4.25], [cx + 0.75, 9, -3.75], rifle, { tag: 'rifle mag' }));          // 2.25 px shows
    B.push(box('body', [cx - 0.5, 4.25, -4.125], [cx + 0.5, 4.75, -3.875], lips, { tag: 'rifle mag feed lips' }));
    B.push(box('body', [cx - 0.25, 6, -4.75], [cx + 0.75, 8.5, -4.25], pistol, { tag: 'pistol mag' }));         // 1 px shows
    B.push(box('body', [cx - 0.25, 10, -4.5], [cx + 0.25, 11, -4], cord, { tag: 'pull cord' }));
  }

  // ---------------- side pouches ----------------
  // pressed against the carrier's front corners, 0.25+ px in front of the sleeve; fronts at z -4 (never the groin
  // protector's plane), backs resting on the cummerbund front
  // wearer's right: single mag pouch (the taller one, reaching lowest) + GP pouch (outer). As in the render the single
  // mag pouch is two pieces side by side: its outer half a MultiCam flap pouch (closed flap starting about level with the
  // GP pouch top and ending about halfway down), its inner half a khaki open-top sleeve starting lower, with a dark
  // magazine standing in it. From the top down: GP pouch top, flap top, magazine top, khaki sleeve top.
  const flapPouch = c => {
    if (c.face === 'top') return mul(fab(c, 1, 0.05, 3), 1.12);                // closed flap
    let col = fab(c, 1.02, 0.05, 3);
    if (c.face === 'front') {
      if (c.ev < px) col = mul(col, 1.12);                                     // lit fold of the flap
      else if (c.p[1] >= 9 && c.p[1] < 9.5) col = mul(col, 0.72);              // the flap's lower edge
    }
    return edge(c, col);
  };
  B.push(box('body', [-4.5, 6.25, -4], [-4, 12, -2.75], flapPouch, { tag: 'single mag pouch (flap half)' }));
  const sleeve = c => {
    let col = mul(mix(fab(c, 1, 0.05, 5.7), KHAKI, 0.7), 0.97);
    if (c.face === 'top' || (c.face !== 'bottom' && c.ev < px)) return mul(col, 1.15);   // light binding on the mouth
    if (c.face === 'front' && c.p[1] >= 9 && c.p[1] < 9.5) col = mul(col, 0.8);           // pocket seam, level with the flap edge
    return isPanel(c) ? col : mul(col, 0.85);
  };
  // the khaki sleeve is a thin front wall; its magazine stands behind it, 2.5 px down in the pouch, against the bag
  // (the wall's sides and the magazine's sides never overlap, so no faces coincide)
  B.push(box('body', [-4, 7.5, -4], [-3.5, 12, -3.75], sleeve, { tag: 'single mag pouch (khaki sleeve)' }));
  B.push(box('body', [-4, 6.5, -3.75], [-3.5, 10, -3.25], magPaint(DSTEEL, false), { tag: 'side pouch mag' }));
  const ZIP = hex('#262826');
  // one solid dark zipper column along the inner edge of the front, carried over the top
  const gp = c => (((c.face === 'front' || c.face === 'top') && c.p[0] >= c.box.x + c.box.w - px) ? mix(fab(c, 0.95, 0.05, 9.2), ZIP, 0.6) : edge(c, fab(c, 0.95, 0.05, 9.2)));
  B.push(box('body', [-5.5, 6, -3.5], [-4.5, 11, -2.5], gp, { tag: 'GP pouch' }));
  B.push(box('body', [-5, 6.5, -3.75], [-4.5, 7, -3.5], K.plastic(hex('#3a3c3a')), { tag: 'zip pull' }));
  // wearer's left: tall MultiCam radio pouch, a shade darker than the bag (it sits in shadow in the render); only its
  // top binding, where the antenna comes out, is light khaki
  const radioPouch = c => {
    if (c.face === 'top') return c.ev > c.fh - px ? mul(KHAKI, 1.1) : hex('#26282a');
    const col = fab(c, 0.95, 0.05, 4.4);
    if (c.face !== 'bottom' && c.face !== 'back' && c.ev < px) return mul(mix(col, KHAKI, 0.6), 1.1);   // khaki top binding
    return edge(c, col);
  };
  B.push(box('body', [3.5, 5.5, -4], [4.5, 10.5, -2.75], radioPouch, { tag: 'radio pouch' }));
  // under it the darker lower edge of the cummerbund shows (behind the band, so no two front faces share a plane)
  B.push(box('body', [3.5, 10.5, -3.25], [4.5, 11, -2.5], c => edge(c, fab(c, 0.8), { stitch: false }), { tag: 'cummerbund lower edge (left)' }));

  // ---------------- groin protector ----------------
  // band under the mag pouches (flush with the placard) + an inverted-trapezoid protector: sides running straight in
  // from right under the band to a flat, round-cornered bottom about 45% of the top width. Four rows of raised laser-cut
  // tiles, one texel row each, first row on the band: each tile 2 texels wide, camo printed and lifted a little toward
  // khaki and brighter than the camo around it (the render's tiles are a shade darker than the camo but carry a lit top
  // edge; at one texel tall a darker tile merged with its outline into a dark camo stripe, a lighter one reads as a
  // raised tile), a one-texel dark cut between neighbouring tiles and at the row's ends, and a crisp dark outline row
  // under the tile span only (about 60% as bright as the camo, as measured on the render). The render has four tiles a
  // row; on the 2x grid four tiles plus cuts (11 texels) cannot be centred and do not fit inside the tapered lower rows,
  // so every row carries three (x -2..2), the same width on every row like the render's. On the protector the rows sit
  // 1.5 px apart like the render (centres at about 10%, 35% and 60% of its height, the last ending about two thirds
  // down) and the bottom third stays plain light camo; the protector as a whole about as bright as the chest, its camo
  // calmed well toward an olive-khaki ground so the rows read through it from a normal distance.
  const GT = 11, GB = 16.5, HW0 = 4;
  // the band's row sits on its lower half (its upper half is under the pouches' overhang) and its outline is the dark
  // seam on the protector's top edge
  const bandRows = [10.5], vRows = [10.5, 11.5, 13, 14.5];
  const SHADOW = 0.62, CUT = 0.55;             // the tiles' crisp dark outline under each row / the cuts between them
  // lim: the tiles' cuts and outlines stay inside the protector's tapered edge binding (the lowest row has no room for
  // end cuts: the binding closes it); lit / k: how far the raised tiles are lifted toward khaki and brightened
  const slots = (c, rows, cam, { lim = 2.5, lit = 0.4, k = 1.1 } = {}) => {
    const x = c.p[0], y = c.p[1], ax = Math.abs(x);
    if (ax >= Math.min(2.5, lim)) return null;
    if (rows.some(y0 => y >= y0 + 0.5 && y < y0 + 1)) return mul(cam, SHADOW);           // outline under the tile span
    if (!rows.some(y0 => y >= y0 && y < y0 + 0.5)) return null;
    if (ax >= 2 || (ax >= 0.5 && ax < 1)) return mul(cam, CUT);                           // cuts between / beside the tiles
    return mul(mix(cam, KHAKI, lit), k);                                                   // three raised camo tiles
  };
  const PGROUND = hex('#878a62');               // olive-khaki ground the lower parts' camo is calmed toward
  // the band is the brightest of the lower parts in the render: its own camo, calmed toward the ground so its tile row
  // reads, without the carrier's top-down fade
  const band = c => {
    const col = mul(mix(camoAt([c.p[0] + 6.6, c.p[1] + 6.6 * 0.6, c.p[2] - 6.6 * 0.4]), PGROUND, 0.45), 1.12 * G(c, 0.04));
    return edge(c, (c.face === 'front' && slots(c, bandRows, col, { lit: 0.4, k: 1.08 })) || col, { stitch: false });
  };
  B.push(box('body', [-HW0, 10, -3.5], [HW0, 11, -3.25], band, { tag: 'groin band' }));
  const R = 0.75;
  const hwS = y => HW0 * (1 - 0.55 * clamp01((y - GT) / (GB - GT)));
  const hwV = y => { const d = y - (GB - R); return d <= 0 ? hwS(y) : hwS(y) - R + Math.sqrt(Math.max(0, R * R - d * d)); };
  // its own piece of camo, flat (no top-down fade: stacked with the carrier's fade it turned the bottom into a dark green
  // blob), lit about like the chest; printed a little finer, like the render's smaller shapes on the protector, with
  // the offset putting khaki ground with small green / brown shapes and cream flecks at the bottom
  const GO = 33;
  const gcam = c => mul(mix(camoAt([c.p[0] + GO, c.p[1] + GO * 0.6, c.p[2] - GO * 0.4], 0.7), PGROUND, 0.6), 1.06 * G(c, 0.04));
  const vp = c => {
    const x = c.p[0], y = c.p[1], hw = hwV(c.face === 'bottom' ? GB - px / 2 : y);   // bottom face follows the last texel row
    if (isPanel(c) || c.face === 'bottom') { if (Math.abs(x) > hw + 1e-6) return null; }
    else if (c.face !== 'top' && hw < HW0 - px / 2 - 1e-6) return null;          // side faces only where the front is full width
    let col = gcam(c);
    if (c.face === 'front') col = slots(c, vRows, col, { lim: hw - px }) || col;
    if (isPanel(c) && (Math.abs(x) > hw - px || y > GB - px)) col = mul(col, 0.84);   // soft edge binding
    return col;
  };
  B.push(box('body', [-HW0, GT, -3.75], [HW0, GB, -3.25], vp, { tag: 'groin protector' }));
  return { S: K.S, px: K.px, cell: K.cell, boxes: B };
};
