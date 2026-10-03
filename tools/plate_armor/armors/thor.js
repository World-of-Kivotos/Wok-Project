// ================= NFM THOR Integrated Carrier — EFT reference (tarkov.dev 60a283193cb70855c43a381d) =================
// Olive-khaki Cordura with dark grime stains; the shoulder pads are the lightest, most yellow parts. Reference render:
// very tall quilted neck protector whose thick, lighter rolled front edge runs from one side roll to the other and
// drops into a broad U-shaped bib (centre double seam + two side seams, dark teal-green binding along the U); front
// plate bag covered in short laser-cut slits over its lower two thirds, with a lighter top band of stitched webbing
// strips and two small vertical loops under the bib; cummerbund wings beside the bag with vertical slots, a khaki
// buckle and a short navy bungee loop with a tan toggle at its bottom; QD buckles at the shoulder-strap ends with navy
// bungee cords and tan toggles; big padded shoulder protectors (light tan dome; the open tube end shows a thick light
// rolled rim round a near-black lining; a tan webbing loop around the upper arm hanging from the outer shell, strap +
// buckle to the shoulder); dark teal-green hem binding; long two-piece groin protector (upper tapering, lower narrower,
// darker, chamfered).
ARMORS.thor = function (mode) {
  const K = kit(mode), { hd, mid, edge, G, isSide } = K;
  const B = [];
  const BASE = hex('#807a56'), LIGHT = hex('#a39871'), LINING = hex('#3a3426'), INNER = hex('#221f18'), BIND = hex('#3a4a3e'),
    SLIT = hex('#23211a'), GRIME = hex('#4a4935'), COLLAR = hex('#7a7654'), CLINING = hex('#4e4632'), PAD = hex('#b39c70'),
    PADHI = hex('#d1b67e'), PADGRIME = hex('#6e5f40'), WEB = hex('#9a835a'), GROIN = hex('#8a7d59'), BLACK = hex('#2a2925'),
    CORD = hex('#1f2436'), TAN = hex('#b8a06a'), BANDLT = hex('#c4b79c');   // BANDLT: light grey-khaki of the admin band
  const bw = hd ? (mid ? 0.5 : 0.25) : 1;                     // one art cell: width of bindings / rims
  const GR = hd ? (mid ? 0.35 : 0.55) : 0.25;                 // grime strength per style (weaker at coarse cells)

  // fabric: slow tone drift + soft, roughly isotropic grime blotches (pure function of the texel position)
  const fabric = (c, base = BASE, k = 0.06, grime = GRIME, gr = GR) => {
    const p = c.p;
    const tone = 0.93 + 0.14 * fbm(p[0] * 0.3 + 7, p[1] * 0.3 + 3, p[2] * 0.3 + 1);
    const n = fbm(p[0] * 0.45 + p[1] * 0.12 + 11, p[1] * 0.35 + 5, p[2] * 0.45 + 17);
    const g = sm(clamp01((n - 0.55) * 3.2));
    return mul(mix(base, grime, g * gr), tone * G(c, k));
  };
  const lining = c => mul(LINING, G(c, 0.08));
  const inner = c => mul(INNER, G(c, 0.08));
  const bind = c => mul(BIND, G(c, 0.06));
  const md = (v, m) => ((v % m) + m) % m;

  // laser-cut slits on the front (back = false) or back plate bag, rows from y0 to y1
  function laser(c, col, y0, y1, back) {
    const y = c.p[1], ax = Math.abs(c.p[0]);
    if (y < y0 || y >= y1) return null;
    if (!hd) {                                                  // 1px: a perforated row every 2px, single-cell holes at x = ±1, ±3
      if (md(Math.floor(y - y0 + 1e-6), 2)) return null;        //      (no continuous bars, no centre spine)
      const r = Math.round(ax);
      return (r === 1 || r === 3) ? mul(col, 0.78) : null;
    }
    if (mid) {                                                  // 0.5px: a slit row every 1px (its lower texel), 1px slits with 0.5px
      const cols = back ? [[0, 0.5], [1, 2], [2.5, 3.5]]        //        bridges: 4 columns on the front, 5 on the (wider) back
        : [[0.25, 1.25], [1.75, 2.75]];                         //        (the front's 15-cell grid has no room for an odd count)
      if (!cols.some(([a, b]) => ax > a && ax < b)) return null;
      // a cut here is a whole half-pixel tall (the original's are hair-thin), so it is kept lighter than B's: half of
      // every slit column is cut, and near-black cuts turned the field into a heavy vent grille
      if (md(y - y0, 1) >= 0.5) return mix(SLIT, col, 0.58);
      return y - y0 >= 0.5 ? mul(col, 1.02) : null;            // faint lit lower lip under the slit above
    }
    if (!(ax > 0.25 && ax < 3.0 && md(ax - 0.25, 1) < 0.75)) return null;   // 0.25px: 6 columns of short slits
    const dy = md(y - y0, 0.75);
    if (dy < 0.25) return mix(SLIT, mul(col, 0.5), 0.45);
    if (dy < 0.5) return mul(col, 1.12);                        // lit lower lip of the cut
    return null;
  }

  // ---------- carrier core: side faces = cummerbund (vertical slots); front strips beside the plate bag = wings ----------
  const HEM = hd ? 10.5 : 10;
  const BAG = hd ? 3.75 : 4.5;                                  // half-width of the front plate bag (1px: full width)
  const cumm = c => {
    if (c.face === 'bottom') return bind(c);
    const col = mul(fabric(c), 0.93);
    const y = c.p[1], ax = Math.abs(c.p[0]);
    if (isSide(c)) {
      const z = c.p[2];
      const bank = hd ? ((y >= 5.5 && y < 7) || (y >= 8 && y < 9.5)) : ((y >= 5 && y < 7) || (y >= 8 && y < 10));
      if (bank && Math.abs(z) < 2 && md(z, 1) < (hd ? (mid ? 0.5 : 0.25) : 1) && (hd || md(Math.floor(z + 10), 2) === 0)) return mul(SLIT, 1.15);
      if (y >= HEM) return bind(c);
    }
    if (c.face === 'front' && hd && ax > BAG - 0.01) {          // cummerbund wing seen from the front
      if (y >= HEM) return bind(c);
      if (ax > 4.0 && ax < (mid ? 4.5 : 4.25) && y >= 5 && md(y - 1, 1.5) < 1) return mul(col, 0.6);   // vertical slots (cummerbund zone only, clear of the shoulder buckles)
      return mul(col, 0.95);
    }
    return edge(c, col, { stitch: false });
  };
  B.push(box('body', [-4.5, 0, -2.5], [4.5, 11, 2.5], cumm, { tag: 'carrier / cummerbund' }));

  // ---------- front plate bag: light admin band (webbing strips) right under the bib + laser-cut field + dark hem ----------
  const frontBag = c => {
    const x = c.p[0], y = c.p[1], ax = Math.abs(x);
    if (c.face === 'bottom') return bind(c);
    if (c.face === 'back') return lining(c);
    if (isSide(c)) return y >= HEM ? bind(c) : mul(mix(fabric(c), BIND, 0.35), 0.95);
    if (c.face === 'top') return fabric(c);
    if (y >= HEM) return bind(c);                               // hem binding (no frame darkening: dark green, not black)
    const raw = fabric(c);
    const b0 = hd ? 1.5 : 2;                                    // the band starts where the bag shows beside the bib
    let col = raw;
    if (y >= b0 && y < 4) col = mix(raw, BANDLT, hd ? 0.5 : 0.4);   // the admin band is the lightest part of the bag
    else if (y < b0) col = mul(raw, 1.05);
    const strip = s => mul(mix(raw, BANDLT, hd ? 0.75 : 0.6), s);
    if (!hd) {
      if (y >= 3 && y < 4 && ax < 3.5) return strip(1);
    } else if (mid) {                                           // three webbing strips (the top one only beside the bib)
      if (ax < BAG - 0.5 && y >= 1.5 && y < 4) return md(y - 1.5, 1) < 0.5 ? strip(1) : mul(col, 0.88);
    } else if (ax < BAG - 0.25) {                                // three stitched webbing strips (the lowest double-stitched)
      for (const [s0, dbl] of [[1.75, false], [2.5, false], [3.25, true]]) {
        const dy = y - s0;
        if (dy >= 0 && dy < 0.5) {
          if ((dy < 0.25 || dbl) && ax < BAG - 0.5 && md(x, 0.5) < 0.25) return strip(1.14);   // stitch dashes
          return strip(dy < 0.25 ? 1 : 0.94);
        }
        if (dy >= 0.5 && dy < 0.75) return mul(col, 0.84);
      }
    }
    const s = laser(c, col, hd ? (mid ? 4 : 4.25) : 4, hd ? (mid ? 10.5 : 10.25) : 9.5, false);
    return edge(c, s || col);
  };
  B.push(box('body', [-BAG, 0, -3.5], [BAG, 11, -2.5], frontBag, { tag: 'front plate bag' }));

  const backBag = c => {
    const y = c.p[1];
    if (c.face === 'bottom') return bind(c);
    if (c.face === 'front') return lining(c);
    if (isSide(c)) return y >= HEM ? bind(c) : mul(mix(fabric(c), BIND, 0.35), 0.95);
    if (c.face === 'top') return fabric(c);
    if (y >= HEM) return bind(c);                               // hem binding (no frame darkening: dark green, not black)
    const col = fabric(c);
    const s = laser(c, col, 2, hd ? (mid ? 10.5 : 10.25) : 9.5, true);   // same slit rows as the front, from under the collar
    return edge(c, s || col);
  };
  B.push(box('body', [-4.5, 0, 2.5], [4.5, 11, 3.5], backBag, { tag: 'back plate bag' }));

  // two small vertical loops under the bib: webbing-toned, with a dark shadowed opening (M: a strip of the light band
  // shows between the bib and the loops, so they read as separate loops, not as points of the bib)
  if (hd) for (const s of [-1, 1]) {
    const X = (a, b) => (s < 0 ? [-b, -a] : [a, b]);
    const tab = c => {
      const col = mid ? mul(mix(fabric(c), BANDLT, 0.3), 0.9) : mix(fabric(c), BANDLT, 0.2);
      if (c.face !== 'front') return edge(c, mul(col, 0.8), { stitch: false });
      if (mid) return c.ev >= 0.5 && Math.abs(c.eu - c.fw / 2) < 0.25 ? mix(col, SLIT, 0.5) : col;   // a light loop round a shadowed opening
      if (c.ev > c.fh - bw) return mix(col, BIND, 0.5);
      if (Math.abs(Math.abs(c.p[0]) - 0.625) < 0.1 && c.p[1] > 2.75) return mul(col, 0.5);   // the loop's opening
      return col;
    };
    const [t0, t1] = mid ? X(0.25, 1.75) : X(0.25, 1);
    // B: starts right under the U's last row (tucked into it, its sides were coplanar with the row's and flickered)
    B.push(box('body', [t0, mid ? 3 : 2.5, -4], [t1, mid ? 4.5 : 3.75, -3.5], tab, { tag: 'bib tab' }));
  } else {                                                       // 1px: two dark cells with the light band showing between
    B.push(box('body', [-1.5, 2, -4], [1.5, 4, -3.5], c => {
      const col = fabric(c);
      if (c.face !== 'front') return mul(col, 0.7);
      return Math.abs(c.p[0]) < 0.5 ? mul(mix(col, BANDLT, 0.6), 0.96) : mul(col, 0.8);
    }, { tag: 'bib tabs' }));
  }

  // ---------- neck protector: flared side walls + back wall (tall, quilted) + rolled front edge dropping into a U bib ----------
  const seam = (v, period, off) => md(v - off, period) < (hd ? (mid ? 0.5 : 0.25) : 1);
  const collarFab = c => fabric(c, COLLAR, 0.05);
  const clining = c => mul(CLINING, G(c, 0.08));
  const rim = c => mul(mix(COLLAR, LIGHT, 0.35), G(c, 0.06));
  const CB = hd ? 1.5 : 1;                                      // collar bottom (1px: on the whole-pixel grid)
  for (const s of [-1, 1]) {
    const out = s < 0 ? 'right' : 'left', inn = s < 0 ? 'left' : 'right';
    const wall = c => {
      if (c.face === inn || c.face === 'bottom') return clining(c);
      if (c.face === 'top') return rim(c);
      if (c.face === 'front') return edge(c, mul(mix(collarFab(c), LIGHT, 0.35), 1.04), { stitch: false });   // highlighted side roll
      let col = collarFab(c);
      if (c.face === out) {
        if (hd && seam(c.p[2], 2, -0.75)) col = mul(col, mid ? 0.84 : 0.8);          // quilting channels
        if (!hd && seam(c.p[2], 3, 0)) col = mul(col, 0.86);
        if (c.ev < bw) col = mix(col, LIGHT, 0.25);                                  // rolled top edge
      }
      return edge(c, col, { stitch: false });
    };
    const flare = hd ? { rot: [0, 0, s * 5], pivot: [s * 4.5, 1.5, 0] } : {};        // tops lean out like a padded hood
    B.push(box('body', s < 0 ? [-5.5, -3, -4.5] : [4.5, -3, -4.5], s < 0 ? [-4.5, CB, 5] : [5.5, CB, 5], wall, { tag: 'collar side', ...flare }));
  }
  const back = c => {
    if (c.face === 'front' || c.face === 'bottom') return clining(c);
    if (c.face === 'top') return rim(c);
    let col = collarFab(c);
    if (c.face === 'back') {
      if (seam(c.p[0], hd ? 1.5 : 3, hd ? 0.75 : 1.5) && Math.abs(c.p[0]) < 4) col = mul(col, hd ? (mid ? 0.84 : 0.8) : 0.86);
      if (c.ev < bw) col = mix(col, LIGHT, 0.25);
    }
    return edge(c, col, { stitch: false });
  };
  // hd: 10 wide so its ends stay tucked into the flared side walls; 0.25 in front of their back ends
  B.push(box('body', hd ? [-5, -3, mid ? 3.25 : 3] : [-4.5, -3, 3], hd ? [5, CB, 4.75] : [4.5, CB, 5], back, { tag: 'collar back' }));

  // front: row 0 is the full-width rolled edge whose ends run into the side walls; the rows below form a broad U flap
  // (nearly straight sides, rounded bottom). The dark rolled binding follows only the exposed outline of the U.
  // (1px: the roll ends flush against the side walls' inner faces; tucked into them its bottom shared their bottom plane)
  const bibRows = !hd ? [[4.5, 0, 1], [3, 1, 3]]
    : mid ? [[4.75, 0, 0.75], [2.75, 0.75, 2], [2.25, 2, 2.5]]              // straight sides, one rounding step
    : [[4.75, 0, 0.75], [2.75, 0.75, 1.5], [2.5, 1.5, 1.75], [2.25, 1.75, 2], [1.75, 2, 2.25], [1, 2.25, 2.5]];
  bibRows.forEach(([w, y0, y1], i) => {
    const last = i === bibRows.length - 1, wn = last ? 0 : bibRows[i + 1][0];
    const bib = c => {
      const x = c.p[0], ax = Math.abs(x);
      if (!hd && last && c.p[1] >= 2 && ax > 2) return null;                  // 1px: rounded bottom corners of the U
      if (c.face === 'back') return lining(c);
      if (c.face === 'top') return rim(c);
      let col = collarFab(c);
      if (i === 0) col = mix(col, LIGHT, 0.2);                                  // the rolled edge catches more light
      if (c.face !== 'front') return i === 0 ? mul(col, 0.92) : mul(mix(col, BIND, 0.45), 0.9);   // thickness of the roll / binding
      if (hd && (ax < 0.25 || Math.abs(ax - (mid ? 1.5 : 1.625)) < (mid ? 0.25 : 0.125))) col = mul(col, mid ? 0.84 : 0.76);   // seams
      if (!mid && hd && Math.abs(ax - 1.375) < 0.125 && md(c.p[1], 0.5) < 0.25) col = mul(col, 1.15);                   // stitch beside the seam
      if (i === 0 && c.ev < bw) return mix(col, LIGHT, 0.35);               // lit top of the roll
      if (!hd) return last && c.ev > c.fh - bw ? mix(col, BIND, 0.3) : col;   // 1px: only the bottom row of the U is bound
      const edgeCol = mix(col, BIND, 0.45);
      if (c.ev > c.fh - bw && (last || ax > wn)) return edgeCol;              // exposed bottom of this step
      if (i > 0 && ax > w - bw) return edgeCol;                               // exposed sides of the lower steps
      return col;
    };
    // (front at z -4.75: in front of the hat layer, so a hat layer's bottom face can't poke through it)
    B.push(box('body', [-w, y0, -4.75], [w, y1, -3.75], bib, { tag: i === 0 ? 'collar front roll' : 'collar bib' }));
  });

  // ---------- QD buckles at the shoulder-strap ends (plate bag top corners, under the roll), navy bungee + tan toggle ----------
  for (const s of [-1, 1]) {
    const X = (a, b) => (s < 0 ? [-b, -a] : [a, b]);
    if (hd) {
      const [b0, b1] = X(3.25, 4);
      B.push(box('body', [b0, 0.75, -4], [b1, 1.5, -3.5], K.plastic(BLACK), { tag: 'QD buckle' }));
      // (M: the cord is as wide as the toggle, so it stops on the toggle's top instead of running into it)
      const [c0, c1] = mid ? X(3.375, 3.875) : X(3.5, 3.75);
      B.push(box('body', [c0, 1.5, mid ? -4 : -3.75], [c1, mid ? 2.5 : 2.75, -3.5], K.solid(CORD, 0.05), { tag: 'bungee cord' }));
      const [t0, t1] = X(3.375, 3.875);
      B.push(box('body', [t0, 2.5, mid ? -4.125 : -3.875], [t1, 3, -3.375], K.plastic(TAN), { tag: 'cord toggle' }));
    } else {
      const [b0, b1] = X(3.5, 4);
      B.push(box('body', [b0, 1, -4], [b1, 4, -3.5], c => (c.p[1] < 2 ? mul(BLACK, c.face === 'top' ? 1.5 : 1) : c.p[1] < 3 ? CORD : TAN), { tag: 'QD buckle + cord' }));
    }
  }
  // ---------- cummerbund: khaki buckle, short navy bungee loop, tan toggle at the bottom ----------
  const buckleCol = c => edge(c, mul(mix(BASE, LIGHT, 0.3), G(c, 0.06) * (c.face === 'front' ? 1 : 0.9)), { stitch: false });
  for (const s of [-1, 1]) {
    const X = (a, b) => (s < 0 ? [-b, -a] : [a, b]);
    if (hd) {                                                    // on the wing, beside the plate bag
      const [k0, k1] = X(3.875, 4.375);
      B.push(box('body', [k0, 5.5, -3], [k1, 7, -2.5], buckleCol, { tag: 'cummerbund buckle' }));
      const [c0, c1] = mid ? X(3.875, 4.375) : X(4, 4.25);
      B.push(box('body', [c0, 7, mid ? -3 : -2.75], [c1, 9, -2.5], K.solid(CORD, 0.05), { tag: 'cummerbund bungee' }));
      const [t0, t1] = mid ? X(3.75, 4.5) : X(3.875, 4.375);
      B.push(box('body', [t0, 9, mid ? -3.125 : -2.875], [t1, 9.75, mid ? -2.625 : -2.375], K.plastic(TAN), { tag: 'bungee toggle' }));
    } else {                                                     // 1px: on the side: buckle, cord, toggle
      const [c0, c1] = X(4.5, 5);
      B.push(box('body', [c0, 5, -3], [c1, 9, -2.5], c => (c.p[1] >= 8 ? TAN : c.p[1] >= 7 ? CORD : mul(mix(BASE, LIGHT, 0.3), G(c, 0.06))), { tag: 'cummerbund bungee' }));
    }
  }

  // ---------- groin protector: upper segment (tapering in B), lower narrower + darker with a chamfered bottom ----------
  const groin = (cut, taper, hw, base) => c => {
    const x = c.p[0], y = c.p[1], ax = Math.abs(x);
    if (cut && ax + y >= cut) return null;
    const lim = taper ? hw - (y - 10.5) * 0.15 : Infinity;      // upper plate: 0.5px narrower per side at the bottom
    if (Math.min(ax, hw - K.px / 2) > lim) return null;         // (side faces follow the outer front column)
    if (c.face === 'back' || c.face === 'top') return lining(c);
    if (c.face === 'bottom' || (cut && c.face !== 'front')) return bind(c);
    let col = fabric(c, base, 0.06);
    if (c.face !== 'front') return mul(col, 0.78);               // upper plate: soft shaded sides, no binding
    if (cut && hd && !mid) {                                      // faint grime streaks on the lower plate (B only: at
      const n = vnoise(x * 1.6 + 3, y * 1.6 + 9, 2);              // half-pixel cells they break up into noise)
      if (Math.abs(n - 0.5) < 0.02) col = mul(col, 0.88);
    }
    if (!hd) {                                                    // 1px: only the bottom row / chamfer is bound
      if ((cut && ax + y >= cut - 1.2) || c.ev > c.fh - 1) return mix(col, BIND, cut ? 0.6 : 0.45);
      return col;
    }
    if (c.ev > c.fh - bw) return mix(col, BIND, 0.7);             // bound bottom seam (both plates)
    if (cut && (ax + y >= cut - bw * 1.2 || c.eu < bw || c.eu > c.fw - bw)) return mix(col, BIND, 0.7);   // lower: bound sides + chamfer
    if (c.eu < bw || c.eu > c.fw - bw || ax > lim - bw) return mul(col, 0.88);   // upper: soft shaded side edges
    return col;
  };
  B.push(box('body', hd ? [-3, 10.5, -3.25] : [-3, 10, -3.25], hd ? [3, mid ? 13.5 : 13.75, -2.75] : [3, 13, -2.75], groin(0, hd && !mid, 3, GROIN), { tag: 'groin upper' }));
  B.push(box('body', hd ? [-2.5, 13.5, -3] : [-2, 13, -3], hd ? [2.5, 16.5, -2.5] : [2, 16, -2.5], groin(hd ? 18 : 17, false, hd ? 2.5 : 2, mul(GROIN, 0.85)), { tag: 'groin lower' }));

  // ---------- shoulder protectors (follow the arms): light padded shell + webbing loop hanging from the outer plate ----------
  for (const s of [-1, 1]) {
    const part = s < 0 ? 'right_arm' : 'left_arm';
    const X = (a, b) => (s < 0 ? [a, b] : [-b, -a]);
    const out = s < 0 ? 'right' : 'left', inn = s < 0 ? 'left' : 'right';
    const padFab = c => fabric(c, PAD, 0.06, PADGRIME, GR * 0.8);
    // dome cap
    const cap = c => {
      if (c.face === 'bottom') return inner(c);
      let col = padFab(c);
      if (c.face === 'top') {
        col = mul(col, 1.04);
        const az = Math.abs(c.p[2]);
        if (hd && (mid ? Math.abs(az - 1) < 0.1 : (az >= 1 && az < 1.25))) col = mul(col, mid ? 0.88 : 0.84);   // quilted channels across the dome
        return edge(c, col);
      }
      if (c.face === inn) return mix(col, BIND, 0.6);
      if (c.ev < bw) col = mix(col, PADHI, 0.3);                                   // lighter rolled top edge
      return edge(c, col, { stitch: false });
    };
    // inner end 0.75 from the arm edge in every style, clear of the flared collar wall; hd: the outer end meets the
    // bevel's top corner
    const [c0, c1] = X(hd ? -3.5 : -3.75, -0.75);
    B.push(box(part, [c0, -3.25, -3.25], [c1, -2.25, 3.25], cap, { tag: 'shoulder cap' }));
    // rounded outer shoulder: a 45deg-turned 1.5px square (top corner level with the cap top) whose outer face is the
    // bevel between cap and outer plate. (Its side stays on the texel grid: a 1.414 side left an unpainted, see-through
    // texel strip that showed as a black line along the shoulder.)
    if (hd) {
      const cx = s < 0 ? -3.5 : 3.5, cy = -3.25 + 1.5 * Math.SQRT1_2;
      B.push(box(part, [cx - 0.75, cy - 0.75, -3.125], [cx + 0.75, cy + 0.75, 3.125], c => (c.face === 'front' || c.face === 'back' ? padFab(c) : mul(mix(padFab(c), PADHI, 0.15), 1.03)), { tag: 'shoulder bevel', rot: [0, 0, 45], pivot: [cx, cy, 0] }));
    }
    // outer plate, flared out, dark rolled binding at the bottom, near-black lining inside; long enough that the
    // webbing loop below hangs from it (the loop's outer end is buried in this plate on normal and slim arms alike)
    const outer = c => {
      if (c.face === inn) return inner(c);
      if (c.face === 'bottom') return bind(c);
      let col = padFab(c);
      if (c.face !== 'top' && c.ev > c.fh - bw) return mix(col, BIND, 0.5);
      if (hd && c.face === out && c.ev >= 2 && c.ev < 2 + bw) col = mul(col, mid ? 0.88 : 0.84);   // quilted channel across the pad
      return edge(c, col, { stitch: false });
    };
    const [o0, o1] = X(-4.5, -3.5);
    B.push(box(part, [o0, -2.25, -3], [o1, 3.25, 3], outer, { tag: 'shoulder outer', rot: [0, 0, s * -8], pivot: [s < 0 ? -4.5 : 4.5, -2.25, 0] }));
    // front / back ends of the padded tube: the outward face shows the open end — a thick light rolled rim along the
    // top and the outer side around the dark lining (the arm fills the hole below it, the loop closes the ring)
    for (const zz of [[-3.25, -2.75], [2.75, 3.25]]) {
      const face = zz[0] < 0 ? 'front' : 'back', backF = zz[0] < 0 ? 'back' : 'front';
      const [f0, f1] = hd ? X(-4, -0.75) : X(-3.75, -0.75);                     // inner end = the cap's (clear of the collar wall)
      const outerX = s < 0 ? f0 : f1;                                              // arm-local x of the outer edge
      const side = c => {
        if (c.face === backF) return inner(c);
        const col = padFab(c);
        if (c.face === 'bottom') return mix(col, BIND, 0.5);
        if (c.face === face) {
          const lx = c.p[0] - (s < 0 ? -5 : 5);
          if (hd && c.ev > c.fh - bw) return mix(col, BIND, 0.5);                  // rolled binding at the bottom
          if (c.ev < bw || Math.abs(lx - outerX) < bw) return mix(col, PADHI, 0.3); // light rolled rim
          return mul(mix(INNER, LINING, 0.4), G(c, 0.08));                         // lining inside the tube
        }
        if (c.face === out && c.ev > c.fh - bw) return mix(col, BIND, 0.5);
        return edge(c, col, { stitch: false });
      };
      B.push(box(part, [f0, -2.25, zz[0]], [f1, hd && !mid ? 0.5 : 0.75, zz[1]], side, { tag: 'shoulder ' + face }));
    }
    // tan webbing loop ringing the upper arm below the shell: its outer end starts inside the flared outer plate (so on
    // slim arms it reads as a strap stretched from the shell to the arm, never as a free-floating bar), its inner end
    // stops at the arm's inner half (body |x| >= 4.5 at rest, so it never crosses the vest or pokes out of it when the
    // arm swings); open top/bottom and no armpit wall
    const ring = c => {
      if (c.face === 'top' || c.face === 'bottom' || c.face === inn) return null;
      const col = mul(WEB, G(c, 0.08) * (c.face === out ? 0.92 : 1));
      if (hd && (c.ev > c.fh - K.px || (!mid && c.ev < K.px))) return mul(col, 0.82);   // (M: only the lower edge, a 2-texel strap stays light)
      if (!mid && hd && Math.abs(c.ev - c.fh / 2) < 0.125 && md(c.eu, 0.5) < 0.25) return mul(col, 0.86);
      return col;
    };
    const [r0, r1] = X(hd ? -4.25 : -4.5, 0.5);
    B.push(box(part, [r0, hd && !mid ? 1.75 : 2, -2.75], [r1, 3, 2.75], ring, { tag: 'arm loop strap' }));
    // strap + buckle from the cap to the shoulder strap (kept outboard of the flared collar wall); the buckle is painted
    // flat onto the strap (a raised black block read as a hole / notch against the collar)
    if (hd) {
      const [t0, t1] = X(-2.25, -1);
      const strap = c => {
        const lx = Math.abs(c.p[0] - (s < 0 ? -5 : 5));
        const top = c.face === 'top';
        if (lx > 1.25 && lx < 1.75 && c.face !== 'bottom') return mul(BLACK, top ? 1.6 : 1.25);   // buckle wrapped round the strap
        return mul(WEB, G(c, 0.06) * (top ? 1 : 0.8));
      };
      B.push(box(part, [t0, mid ? -3.75 : -3.5, -0.5], [t1, -3.25, 0.5], strap, { tag: 'cap strap + buckle' }));
    }
  }
  return { S: K.S, px: K.px, cell: K.cell, boxes: B };
};
