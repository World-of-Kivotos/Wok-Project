// ================= BNTI Gzhel-K body armor — EFT reference (tarkov.dev 5ab8e79e86f7742d8b372e78) =================
// One worn steel blue-grey fabric (light on the upper chest, a plainer mid grey-blue lower down). Tall padded roll-neck
// collar in a darker slate teal (near-black lining): in MC it is a closed ring round the head - low roll under the chin,
// stepping up through the front corners into tall side walls and a roll behind the neck. A flat flap on each shoulder's
// front slope beside the roll (the lightest parts, with a dark velcro strip along the lower edge); four long webbing rows
// across the chest (light strips, subtle bar tacks, shadow under each); huge armholes with near-black lining. At the
// waist two teal-grey wrap flaps come round from the sides onto the front (stepped chamfer on the top inner corner) and
// close over black velcro patches with a lighter stitched border — NOT symmetric: the wearer's-right flap reaches
// almost to the middle, the left one only covers the outer corner. A darker belt runs over them low round the waist with
// a black side-release buckle in the middle (male half + ladder bar on the wearer's right), the belt's lighter free end
// doubled back over it on the wearer's right, and a light keeper strap down each flap holding the belt.
// LENGTH: the original runs well below the belt (flap tops ~57 %, belt ~73-85 %, flap ends ~92 % of shoulder-to-hem),
// so the body goes to y 13 at the sides / back and a bound tongue to 13.5 in the middle front (rounded hem); the flaps,
// velcro and keepers continue a pixel below the belt. No arm armour, no pouches.
ARMORS.gzhel_k = function (mode) {
  const K = kit('M');                          // always the 2x style, whatever mode is asked for
  const { edge, G, isPanel, isSide, px } = K;
  const B = [];
  const FAB = hex('#858f97'), LOWF = hex('#5f6a71'), BIND = hex('#485c66'), LINING = hex('#191b1c');
  const PAD = hex('#aab3ba'), PADV = hex('#55554f');
  const COLLAR = hex('#44535a'), CHI = hex('#6a7982'), CLIN = hex('#161a1b');
  const WRAP = hex('#62737c'), BELT = hex('#455159'), TAIL = hex('#56656d'), KEEP = hex('#8a949b');
  const VELCRO = hex('#262a2b'), BLACK = hex('#232425');
  const md = (v, m) => ((v % m) + m) % m;
  // worn Cordura: a faint low-frequency mottle + per-cell grain; vest() adds the light-chest-to-darker-hem gradient
  const fab = (c, base, amt = 0.06) => {
    const q = snap(c.p, K.cell);
    const n = fbm(q[0] * 0.4 + 7, q[1] * 0.4 + 2, q[2] * 0.4 + 5);
    return mul(base, (0.965 + 0.07 * n) * G(c, amt));
  };
  const vest = (c, y) => fab(c, mix(mul(FAB, 1.03), LOWF, sm(clamp01((y - 2.5) / 8)) * 0.85));

  // ---------------- layout ----------------
  const ROWS = [2, 3.5, 5, 6.5];               // webbing strip tops (0.5 tall, shadow row under each)
  const MX = 3.5;                              // webbing half-width (the rows cover about three quarters of the front)
  const HEM = 13, HEM_C = 13.5, HX = 3.5;      // hem: y 13 at the corners, sides and back; a bound tongue to 13.5 for |x| < HX
  const W0 = 7.5, W1 = 12.5;                   // waist wrap flaps: from just under the armholes to a pixel below the belt
  // flap inner edges (x): the wearer's-right flap comes almost to the middle, the left one only over the corner
  const FR = -1, FL = 3;
  const CH = 1.4;                              // chamfer reach (shade in the gap the stepped corner leaves)
  const VL = [-1.5, 0], VR = [2, 3.5];         // black velcro patches the flaps close on (each runs half a pixel under its flap)
  const V0 = 8, V1 = 12.5;                     // velcro top (a cell under the flap tops) and bottom (with the flaps)
  const BY0 = 10, BY1 = 11.5;                  // belt: low on the waist, a pixel of flap / velcro shows under it
  const BK = -0.25;                            // buckle: male half + ladder bar left of BK, female housing right of it
  const RX = 2;                                // collar front roll half-width; the shoulder flaps start beside it

  // ---------------- velcro patch (painted on the shell front) ----------------
  // lighter stitched border on the ends and on the side clear of the flap; inside, a soft hook-and-loop tone in 1-px blocks
  const velcro = (c, v) => {
    const x = c.p[0], y = c.p[1];
    const open = v === VL ? x > v[1] - px : x < v[0] + px;
    if (open || y < V0 + px || y > V1 - px) return mul(VELCRO, 1.35 * G(c, 0.06));
    return mul(VELCRO, (0.94 + 0.1 * hash3(Math.floor(x + 50), Math.floor(y + 50), 7)) * G(c, 0.06));
  };

  // ---------------- shell (front, back, sides) ----------------
  const shell = c => {
    const x = c.p[0], y = c.p[1], z = c.p[2], ax = Math.abs(x), az = Math.abs(z);
    if (c.face === 'bottom') return ax < 4 && az < 2.5 ? mul(LINING, 1.2 * G(c, 0.08)) : mul(BIND, 0.7 * G(c, 0.06));
    if (c.face === 'top') return mul(vest(c, 0), 0.95);
    const hem = y >= HEM - px;
    if (isSide(c)) {
      // huge armhole: near-black lining, bound rim all round (rounded bottom); wrap flap colour round the waist
      const deep = az < 1.5 ? 7 : 6.5;
      if (y >= 0 && az < 2.5 && y < deep) return mul(LINING, G(c, 0.1));
      if (y < deep + 0.5) return mul(BIND, G(c, 0.06));
      if (hem) return mul(BIND, 0.92 * G(c, 0.06));                                // bound hem
      if (y >= W0) return edge(c, fab(c, mul(WRAP, 0.95)), { stitch: false });
      return mul(vest(c, y), 0.9);
    }
    let col = vest(c, y);
    if (c.face === 'front') {
      if (ax < RX && y >= 1 && y < 1.5) col = mul(col, 0.84);                      // shadow under the collar roll
      if (ax > RX && y >= 1.5 && y < 2) col = mul(col, 0.84);                      // shadow under the shoulder flaps
      for (const r of ROWS) if (ax < MX && y >= r + 0.5 && y < r + 1) col = mul(col, 0.84);  // shadow under each webbing strip
      // velcro patches (seen beside the flap edges, through the chamfered corners and under the belt)
      const v = x >= VL[0] && x < VL[1] ? VL : x >= VR[0] && x < VR[1] ? VR : null;
      if (v && y >= V0 && y < V1) return velcro(c, v);
      if (y >= W0 && y < W0 + 1 && ((x > FR - CH && x < FR) || (x > FL && x < FL + CH))) col = mul(col, 0.86);  // shade in the chamfer gaps
      if (hem && ax > HX) col = mix(col, BIND, 0.6);                               // bound hem at the corners (the middle runs on into the tongue)
    } else {
      if (y >= W0) col = fab(c, mul(WRAP, 0.95));                                  // the wrap panels start on the back
      if (hem) col = mix(col, BIND, 0.6);                                          // bound hem
    }
    if (ax > 4) col = mix(col, BIND, 0.55);                                        // bound armhole / side edges
    return col;
  };
  B.push(box('body', [-4.5, -0.5, -3], [4.5, HEM, 3], shell, { tag: 'vest shell' }));
  // rounded front hem: the middle hangs half a pixel lower than the corners (bound edge)
  const tongue = c => {
    if (c.face === 'top') return null;                                             // tucked under the shell
    if (c.face === 'back') return mul(LINING, 1.2 * G(c, 0.08));
    if (c.face === 'bottom') return mul(BIND, 0.7 * G(c, 0.06));
    if (isSide(c)) return mul(BIND, 0.8 * G(c, 0.06));
    return mul(BIND, (Math.abs(c.p[0]) > HX - px ? 0.88 : 1) * G(c, 0.06));
  };
  B.push(box('body', [-HX, HEM, -3], [HX, HEM_C, -2.5], tongue, { tag: 'hem tongue (front)' }));

  // ---------------- collar: a closed ring round the head (MC head hides the middle) ----------------
  // tall side walls beside the head, a roll behind the neck, a low roll under the chin and two front corners that step
  // up from the roll into the walls, so from the front it reads as one roll-neck (low in front, high at the sides)
  const wall = s => c => {
    const inn = s < 0 ? 'left' : 'right';
    if (c.face === inn || c.face === 'bottom') return mul(CLIN, G(c, 0.1));
    if (c.face === 'top') return Math.abs(c.p[0]) > 4.9 ? fab(c, CHI, 0.05) : mul(CLIN, 1.3);
    let col = fab(c, COLLAR, 0.05);
    if (c.ev < px) col = mix(col, CHI, 0.7);                                        // rolled, lit top edge
    else if (c.ev > c.fh - px) col = mul(col, 0.8);
    if (isPanel(c)) col = mul(col, 0.88);
    return col;
  };
  for (const s of [-1, 1]) {
    const xa = s < 0 ? [-5.25, -4.5] : [4.5, 5.25];
    B.push(box('body', [xa[0], -2.25, -5], [xa[1], 0, 5], wall(s), { tag: 'collar side', rot: [0, 0, s * 4], pivot: [s * 4.5, 0, 0] }));
  }
  // back roll: just clears the hat layer, ends inside the side walls (its top stays a quarter pixel under theirs)
  const backRoll = c => {
    if (c.face === 'front') return mul(CLIN, G(c, 0.1));
    if (c.face === 'bottom') return mul(COLLAR, 0.6 * G(c, 0.05));
    if (c.face === 'top') return c.p[2] > 4.25 ? fab(c, CHI, 0.05) : mul(CLIN, 1.3);
    let col = fab(c, COLLAR, 0.05);
    if (c.ev < px) col = mix(col, CHI, 0.7);                                        // rolled, lit top
    else if (c.ev > c.fh - px) col = mul(col, 0.72);                                // darker underside of the roll
    if (isSide(c)) col = mul(col, 0.88);
    return col;
  };
  B.push(box('body', [-4.75, -2, 3], [4.75, 0.5, 4.75], backRoll, { tag: 'collar back roll' }));
  // front: low roll under the chin + a corner on each side, one step higher, running into the side wall
  const frontRoll = (corner) => c => {
    if (c.face === 'back') return null;                                             // pressed on the vest / inside the head
    if (c.face === 'bottom') return mul(COLLAR, 0.62 * G(c, 0.05));
    if (c.face === 'top') return c.p[2] < -4.25 ? fab(c, CHI, 0.05) : mul(CLIN, 1.2);   // lit rim, lining inside the ring
    let col = fab(c, COLLAR, 0.05);
    if (c.face !== 'front') return mul(col, 0.85);
    if (c.ev < px) col = mix(col, CHI, 0.75);                                       // rolled top catches the light
    else if (c.ev > c.fh - px) col = mul(col, 0.78);
    return corner ? mul(col, 0.95) : col;
  };
  B.push(box('body', [-RX, -0.5, -4.75], [RX, 1, -3], frontRoll(false), { tag: 'collar front roll' }));
  for (const s of [-1, 1]) {
    const xa = s < 0 ? [-4.75, -RX] : [RX, 4.75];                                  // outer end tucked into the side wall
    B.push(box('body', [xa[0], -1.5, -4.75], [xa[1], 0, -3], frontRoll(true), { tag: 'collar front corner' }));
  }

  // ---------------- shoulder flaps on the front slope, beside the roll, under the collar corners ----------------
  const pad = c => {
    if (c.face === 'back') return null;
    if (c.face === 'bottom') return mul(PADV, 0.8);
    let col = fab(c, PAD, 0.05);
    if (c.face === 'top') return mul(col, 1.08);
    if (!isPanel(c)) return c.p[1] >= 1 ? mul(PADV, 0.85) : mul(col, 0.82);
    if (c.ev > c.fh - px) return mul(PADV, G(c, 0.08));                              // dark velcro strip along the lower edge
    if (c.eu < px || c.eu > c.fw - px) return mul(col, 0.88);                       // bound ends
    if (c.ev < px) return mul(col, 1.06);                                           // lit upper edge
    return col;
  };
  for (const s of [-1, 1]) {
    const xa = s < 0 ? [-4.5, -RX] : [RX, 4.5];
    B.push(box('body', [xa[0], 0, -3.5], [xa[1], 1.5, -3], pad, { tag: 'shoulder pad' }));
  }

  // ---------------- four webbing rows across the chest ----------------
  const strip = c => {
    if (c.face === 'back') return null;
    const col = fab(c, mul(FAB, 1.02), 0.05);
    if (c.face === 'top') return mul(col, 1.14);
    if (c.face === 'bottom') return mul(col, 0.6);
    if (isSide(c)) return mul(col, 0.8);
    return md(c.p[0] + MX, 1.5) >= 1 ? mul(col, 0.9) : mul(col, 1.07);   // bar tacks every 1.5 px (subtle), symmetric: LLD LLD LLD LLD LL
  };
  for (const r of ROWS) B.push(box('body', [-MX, r, -3.25], [MX, r + 0.5, -3], strip, { tag: 'webbing row' }));

  // ---------------- waist wrap flaps (from the sides onto the front, stepped chamfer on the top inner corner) ----------------
  // each flap is three boxes: the main panel, then two narrow strips by the inner edge starting one and two cells lower
  // (a 2-1 cell step), so every face of the chamfer is solid - no see-through slots from the side
  const flapPart = (s, k) => {
    const inner = s < 0 ? 'left' : 'right', outer = s < 0 ? 'right' : 'left';   // 'right' = min-x face, 'left' = max-x face
    const top = W0 + k * px;
    return c => {
      if (c.face === 'back') return null;                                           // pressed on the vest
      if (c.face === outer && k > 0) return null;                                   // against the next step out (inside the flap)
      if (c.face === inner && k < 2 && c.p[1] >= top + px) return null;             // covered by the next step in
      const col = fab(c, WRAP);
      if (c.face === 'top') return mul(col, 1.1);
      if (c.face === 'bottom') return mul(col, 0.62);
      if (c.face === inner) return mul(col, k < 2 ? 0.92 : 0.86);                   // chamfer step walls / rounded inner edge
      if (c.face === outer) return mul(col, 0.8);
      if (c.ev < px) return mul(col, 1.08);                                         // lit top edge, following the steps
      if (c.ev > c.fh - px) return mul(col, 0.78);                                  // darker bottom edge over the body's hem
      if (Math.abs(c.p[0]) > 4.5 - px) return mul(col, 0.88);                       // turning round the side
      return col;
    };
  };
  for (const s of [-1, 1]) {
    const e = s < 0 ? FR : FL, out = s < 0 ? -4.5 : 4.5;
    const xs = s < 0 ? [[out, e - 1], [e - 1, e - 0.5], [e - 0.5, e]] : [[e + 1, out], [e + 0.5, e + 1], [e, e + 0.5]];
    const name = s < 0 ? 'wide, wearer\'s right' : 'narrow, wearer\'s left';
    xs.forEach(([a, b], k) => B.push(box('body', [a, W0 + k * px, -3.5], [b, W1, -3], flapPart(s, k),
      { tag: k ? `waist wrap flap chamfer step ${k} (${name})` : `waist wrap flap (${name})` })));
  }

  // ---------------- belt round the waist, doubled free end, keepers, side-release buckle ----------------
  // the belt's lighter free end, doubled back over it on the wearer's right from the buckle's ladder bar, is painted on
  // its lower two cells (a separate strap box added one more depth step and made the waist read cluttered)
  const belt = c => {
    let col = fab(c, BELT, 0.05);
    if (c.face === 'top') return mul(col, 1.1);
    if (c.face === 'bottom') return mul(col, 0.62);
    if (c.face === 'front') {
      const x = c.p[0];
      if (x > -4.25 && x < BK && c.p[1] >= BY0 + px) {
        col = fab(c, TAIL, 0.05);
        if (x < -3.75) col = mul(col, 0.88);                                        // the fold at its end
        return c.p[1] >= BY1 - px ? mul(col, 0.86) : mul(col, 1.04);
      }
      if (Math.abs(x) > 4.25) col = mul(col, 0.88);                                 // turning round the sides
    }
    return edge(c, col, { stitch: false });
  };
  B.push(box('body', [-4.75, BY0, -3.75], [4.75, BY1, 3.25], belt, { tag: 'belt' }));
  // light keeper straps: down each flap from a little above the belt to the flap's lower edge, over the belt
  const keeper = c => {
    if (c.face === 'back') return null;
    const col = fab(c, KEEP, 0.05);
    if (c.face === 'top') return mul(col, 1.12);
    if (c.face === 'bottom') return mul(col, 0.6);
    if (isSide(c)) return mul(col, 0.78);
    if (c.ev < px) return mul(col, 1.08);                                           // lit top fold
    if (c.ev > c.fh - px) return mul(col, 0.86);                                    // bottom fold
    return col;
  };
  for (const xa of [[-2.25, -1.75], [3.5, 4]]) B.push(box('body', [xa[0], 9, -4], [xa[1], W1, -3.5], keeper, { tag: 'belt keeper' }));
  // black side-release buckle: male half with the ladder bar the free end runs through (wearer's right), female housing
  // beside it a quarter pixel taller than the belt at both edges; near-black with one lit rim so it stands off the belt
  const prong = c => {
    if (c.face === 'back') return null;
    if (c.face === 'top') return mul(BLACK, 1.6);
    if (!isPanel(c)) return mul(BLACK, 0.85);
    if (c.eu < px) return mul(BLACK, c.ev < px ? 1.75 : 1.5);                      // ladder bar
    return mul(BLACK, c.ev < px ? 1.45 : 1.05);                                     // prong going into the housing
  };
  B.push(box('body', [BK - 1, BY0 + 0.25, -4], [BK, BY1 - 0.25, -3.75], prong, { tag: 'buckle prong' }));
  const housing = c => {
    if (c.face === 'back') return null;                                              // lies flat on the belt
    if (c.face === 'top') return mul(BLACK, 1.6);
    if (!isPanel(c)) return mul(BLACK, 0.85);
    const i = Math.floor(c.eu / px), j = Math.floor(c.ev / px), n = Math.round(c.fw / px), m = Math.round(c.fh / px);
    if (j === 0 || i === 0) return mul(BLACK, 1.6);                                  // lit rim
    if (i === n - 1 || j === m - 1) return mul(BLACK, 0.85);
    return mul(BLACK, 1.1);
  };
  B.push(box('body', [BK, BY0 - 0.25, -4.25], [BK + 1.5, BY1 + 0.25, -3.75], housing, { tag: 'buckle housing' }));
  return { S: K.S, px: K.px, cell: K.cell, boxes: B };
};
