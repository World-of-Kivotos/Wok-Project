// ================= 6B13 assault armor (Flora) — EFT reference (tarkov.dev 5c0e51be86f774598e797894) =================
// Russian "Flora" (VSR-98) camo: a sage-green ground with lighter yellow-green patches, horizontally stretched
// muted brown blobs and a few darker teal-green ones. Reference render: tall padded stand collar (camo outside, dark
// olive lining, a closure tab on its front at the wearer's right) resting on the chest; wide shoulder straps quilted
// along their length, each ending in a short tab that juts out sideways and curls into a short roll (round end cap
// facing forward, a stitched mark on it); a proud "coffin" shaped plate flap covering nearly the whole chest (a narrow
// top about half the full width, long straight diagonal sides down to mid-chest, straight sides, chamfered bottom
// corners) framed by a bound edge, with a two-strip loop patch across its top and a small webbing tab to either side
// running from the flap's sloping edge out across the vest's edge; under the flap the vest carries on to a hem whose corners curve in, with two tan webbing straps
// near the ends of the flap's bottom edge. At the sides the armhole is open; below it a closed side panel with a bound
// hem is joined to the front panel by a tan side strap and a grey cam buckle. Bound edges throughout (M: shaded rims).
ARMORS.b6b13_flora = function (mode) {
  const K = kit('M');                     // always the 2x style, whatever mode is asked for
  const { edge, G, isPanel, isSide, px } = K;
  const B = [];

  // ---------- palette (albedo; MC draws front faces at ~74 %, sampled from the render's lit flap and hem) ----------
  // five Flora tones in about the render's proportions (olive 21 %, teal-green 27 %, brown 20 %, sage 12 %, light 19 %),
  // kept on the render's cool teal-green side and about as bright as the other tier-IV vests (not paler)
  const FL = [hex('#788d77'), hex('#90af93'), hex('#928972'), hex('#b0c49f'), hex('#cce8b7')];
  const LIN = hex('#4a4a3a'), TAN = hex('#c4ba8c'), BUCK = hex('#7f8a92'), PATCH = hex('#b7c6a0');
  const BIND = hex('#50604d'), LITB = hex('#b4c09a');          // rolled binding: dark teal-green, lit where it faces up
  const TOP = 0.8;                                             // camo on upward faces (full light in game: keep the pale tone from glaring)

  // Flora: a "family" field splits light (light / sage) from dark (olive / teal / brown) shapes, a second field picks
  // the tone inside each family and a third lays the brown blobs. Sampled in 3D on the art grid and stretched
  // horizontally (x / z) so the shapes run sideways like the original; a lone cell that none of its four neighbours
  // shares takes their colour (no single-cell specks).
  const floraCls = (x, y, z) => {
    const X = x * 0.48, Y = y * 0.88, Z = z * 0.48;
    const A = fbm(X + 3.1, Y + 1.7, Z + 7.3, 2), Bn = fbm(X * 1.2 + 13.3, Y * 1.2 + 5.2, Z * 1.2 + 2.9, 2);
    if (A > 0.56) return Bn > 0.5 ? 4 : 3;
    return fbm(X * 1.1 + 29.1, Y * 1.1 + 11.4, Z * 1.1 + 17.7, 2) > 0.58 ? 2 : Bn > 0.47 ? 1 : 0;
  };
  const flora = c => {
    const q = snap(c.p.map(v => v + 0.01), 0.5);   // tiny nudge: texels lying exactly on a cell boundary snap the same way every row
    const k = floraCls(q[0], q[1], q[2]);
    // neighbours along the face's own two axes
    const [a, b] = isPanel(c) ? [0, 1] : isSide(c) ? [2, 1] : [0, 2];
    const nb = [[a, -px], [a, px], [b, -px], [b, px]].map(([ax, d]) => { const r = q.slice(); r[ax] += d; return floraCls(r[0], r[1], r[2]); });
    let cl = k;
    if (nb.every(v => v !== k)) cl = nb.find(v => nb.filter(w => w === v).length >= 2) ?? nb[0];
    return mul(FL[cl], G(c, 0.05));
  };
  const lin = (c, k = 1) => mul(LIN, k * G(c, 0.08));
  const bound = (c, k = 1) => edge(c, mul(flora(c), k), { stitch: false });
  const bind = c => mix(flora(c), BIND, 0.75), litBind = c => mix(flora(c), LITB, 0.5);

  // ---------- vest shell: front and back panels; the lower corners curve in towards a narrower bound hem ----------
  // long assault vest: in the original the hem sits well below the belt (about twice as long as it is wide), so the
  // front and back run down to the groin. Full width down to the bottom of the side panels, then the corners step in
  // one art cell per pixel, so the straight bottom edge is only about two thirds of the vest's width.
  const YT = 1, YB = 15.5, YH = 16;
  const STEPS = [[YT, 13.5, 4.5], [13.5, 14.5, 4], [14.5, YB, 3.5], [YB, YH, 3]];    // [y0, y1, half-width]
  const pwAt = y => { for (const [a, b, w] of STEPS) if (y >= a && y < b) return w; return -1; };
  const inPanel = (x, y) => Math.abs(x) < pwAt(y);
  // outer face of either panel: bound outline along the sides, every corner step and the hem
  const shell = (c, col) => {
    const x = c.p[0], y = c.p[1], h = px;
    if (!inPanel(x - h, y) || !inPanel(x + h, y) || !inPanel(x, y + h)) return bind(c);
    return col;
  };
  // faces shared by both panels (inner face, top, bottom and the bound side edges of the armholes and corner steps)
  const shellEdges = (c, inner) => {
    if (c.face === inner || c.face === 'top') return lin(c);
    if (c.face === 'bottom') return inPanel(c.p[0], c.p[1] + 0.1) ? lin(c) : mul(bind(c), 0.7);
    return mul(bind(c), 0.85);                                                 // left / right: bound edges
  };
  const frontPanel = c => {
    if (c.face !== 'front') return shellEdges(c, 'back');
    const x = c.p[0], y = c.p[1], col = flora(c);
    // the proud chest flap throws a one-cell shadow onto the vest below and beside it (the vest body around the flap
    // is a shade darker than the flap itself, as in the render)
    if (!inFlap(x, y) && inFlap(x, y - px)) return shell(c, mul(col, 0.66));
    if (!inFlap(x, y) && (inFlap(x - px, y) || inFlap(x + px, y))) return shell(c, mul(col, 0.76));
    return shell(c, mul(col, 0.88));
  };
  const backPanel = c => (c.face !== 'back' ? shellEdges(c, 'front') : shell(c, flora(c)));
  for (const [y0, y1, w] of STEPS) {
    const tag = y0 === YT ? '' : y0 === YB ? ' hem' : ' corner step';
    B.push(box('body', [-w, y0, -3.25], [w, y1, -2.5], frontPanel, { tag: 'front panel' + tag }));
    B.push(box('body', [-w, y0, 2.5], [w, y1, 3.25], backPanel, { tag: 'back panel' + tag }));
  }
  // back plate pocket (the render does not show the back: a plain proud cover with bound edges)
  B.push(box('body', [-3.5, 2, 3.25], [3.5, 12.5, 3.5], c => (c.face === 'front' ? null : c.face === 'top' ? mul(flora(c), TOP) : bound(c)), { tag: 'back plate pocket' }));

  // ---------- chest plate flap: "coffin" shape - narrow top, long diagonal sides, straight sides, chamfered corners ----------
  // rows [y0, y1, half-width]. As in the original the narrow top is about half the full width (the loop patch's
  // width) and the sides run out as long straight diagonals - one art cell per side every 1.5 px - down to mid-chest
  // (the small notches), leaving triangles of vest beside the top where the chest tabs sit. Every half-width ends on
  // the same quarter (x.25 / x.75), so all rows share one art grid. The straight part stops a cell and a half short of
  // the vest's edge, so the vest's own bound edge shows beside the cover.
  const ROWS = [[1.5, 3, 1.75], [3, 4.5, 2.25], [4.5, 6, 2.75], [6, 7.5, 3.25], [7.5, 11.5, 3.75], [11.5, 12.5, 3.25], [12.5, 13.5, 2.75]];
  const hwAt = y => { for (const [a, b, w] of ROWS) if (y >= a && y < b) return w; return -1; };
  const inFlap = (x, y) => Math.abs(x) < hwAt(y);
  // the flap's own bound edge: dark teal-green like the vest's binding (darker than every camo tone, so it outlines
  // the cover against the vest even where a light or a dark blob meets it)
  const flapEdge = c => mul(mix(flora(c), BIND, 0.85), 0.92);
  const flap = c => {
    const x = c.p[0], y = c.p[1];
    if (c.face === 'back') return null;                                        // lies on the front panel
    const col = flora(c);
    if (c.face === 'top') return mul(col, TOP + 0.06);                         // lit ledges of the diagonal steps
    if (c.face === 'bottom') return mul(flapEdge(c), 0.75);
    if (c.face !== 'front') return mul(flapEdge(c), 0.9);                      // rolled binding seen from the side
    // one-cell bound outline following the whole shape (diagonal top, sides, chamfers, bottom); lit along the very top
    const h = px;
    if (!inFlap(x, y - h) && y < ROWS[0][0] + h) return litBind(c);
    if (!inFlap(x, y - h) || !inFlap(x - h, y) || !inFlap(x + h, y) || !inFlap(x, y + h)) return flapEdge(c);
    return col;
  };
  for (const [y0, y1, w] of ROWS) B.push(box('body', [-w, y0, -3.75], [w, y1, -3.25], flap, { tag: 'chest plate flap' }));
  // two-strip loop patch across the flap's top (a light, faintly camo-printed panel split by a seam), just inside the
  // narrow top so the flap's bound edge shows beside it
  const patch = c => {
    if (c.face === 'back') return null;
    const col = mul(mix(flora(c), PATCH, 0.85), G(c, 0.04));
    if (c.face === 'top') return mul(col, 1.04);
    if (c.face !== 'front') return mix(col, BIND, 0.5);
    const j = Math.floor(c.ev / px), n = Math.round(c.fh / px);
    if (c.eu < px || c.eu > c.fw - px || j === n - 1) return mix(col, BIND, 0.55);   // bound frame
    return j === 1 ? mix(col, BIND, 0.4) : j === 0 ? mul(col, 1.06) : col;         // two strips split by a seam
  };
  B.push(box('body', [-1.5, 1.75, -4], [1.5, 3.75, -3.75], patch, { tag: 'loop patch' }));
  // small webbing tabs beside the flap's narrow top: from under its sloping edge out across the vest's bound edge
  for (const s of [-1, 1]) {
    const X = (a, b) => (s < 0 ? [-b, -a] : [a, b]);
    const [t0, t1] = X(1.5, 4.5);                                              // tucked a little under the flap's edge
    B.push(box('body', [t0, 2, -3.5], [t1, 3, -3.25], c => {
      if (c.face === 'back') return null;
      const col = mul(mix(flora(c), PATCH, 0.5), G(c, 0.04));                  // light camo-printed webbing like the patch
      if (c.face === 'top') return mul(col, TOP + 0.1);
      if (c.face !== 'front') return mix(col, BIND, 0.5);
      return Math.floor(c.ev / px) === 0 ? mul(col, 1.06) : mix(col, BIND, 0.45);   // lit upper strip, bound lower edge
    }, { tag: 'chest tab' }));
  }
  // two tan webbing straps from under the flap's bottom edge (near its ends) down to the hem
  const strap = c => {
    if (c.face === 'back' || c.face === 'top') return null;                   // on the panel / under the flap
    const col = mul(TAN, G(c, 0.06));
    if (c.face !== 'front') return mul(col, 0.8);
    return c.p[1] > YB - px ? mul(col, 0.88) : col;
  };
  for (const s of [-1, 1]) {
    const X = (a, b) => (s < 0 ? [-b, -a] : [a, b]);
    const [a, b] = X(1.5, 2.5);
    B.push(box('body', [a, 13.25, -3.5], [b, YB, -3.25], strap, { tag: 'hem strap' }));
  }

  // ---------- sides: open armhole, below it a closed side panel buckled to the front panel ----------
  for (const s of [-1, 1]) {
    const X = (a, b) => (s < 0 ? [-b, -a] : [a, b]);
    const out = s < 0 ? 'right' : 'left', inn = s < 0 ? 'left' : 'right';
    // side panel from the bottom of the armhole down to its own bound hem (level with the flap's bottom)
    const side = c => {
      if (c.face === inn) return lin(c);
      if (c.face === 'top') return bind(c);                                   // bound bottom edge of the armhole
      if (c.face === 'bottom') return mul(bind(c), 0.7);
      if (c.face !== out) return mul(bind(c), 0.85);
      const j = Math.floor(c.ev / px), n = Math.round(c.fh / px);
      if (j === 0) return litBind(c);
      if (j === n - 1) return bind(c);
      return mul(flora(c), 0.88);
    };
    const [p0, p1] = X(4.5, 4.75);
    B.push(box('body', [p0, 10, -2.5], [p1, 13.5, 2.5], side, { tag: 'side panel' }));
    // tan side strap running forward along the panel (its sewn tail end a shade darker) into the cam buckle; both sit
    // a little lower than in the original so they show below the arm
    const [s0, s1] = X(4.75, 5);
    B.push(box('body', [s0, 11.75, -1.75], [s1, 12.75, 1.5], c => {
      if (c.face === inn) return null;                                         // lies on the side panel
      const col = mul(TAN, G(c, 0.06));
      if (c.face !== out) return mul(col, 0.8);
      return c.p[2] > 0.5 ? mul(col, 0.9) : col;
    }, { tag: 'side strap' }));
    // grey cam buckle at the front edge of the side panel, reaching over the junction with the front panel
    const [b0, b1] = X(4.75, 5.25);
    B.push(box('body', [b0, 11.5, -2.75], [b1, 13, -1.75], c => {
      if (c.face === inn) return mul(BUCK, 0.55);
      if (c.face === 'top') return mul(BUCK, 1.2);
      if (c.face !== out) return mul(BUCK, 0.78);
      const j = Math.floor(c.ev / px);
      return mul(BUCK, j === 0 ? 1.18 : j === 1 ? 0.62 : 1);                 // lit bar, dark slot, lower bar
    }, { tag: 'side cam buckle' }));
  }

  // ---------- stand collar ----------
  // front roll: in front of the chin, resting on the flap's top; lit rolled rim, dark olive lining on top
  B.push(box('body', [-4.75, -0.5, -4.75], [4.75, 1.5, -3.25], c => {
    if (c.face === 'back') return lin(c);
    if (c.face === 'top') return c.p[2] < -4.25 ? mul(flora(c), TOP + 0.06) : lin(c);
    if (c.face === 'bottom') return mul(flora(c), 0.6);
    const col = mul(flora(c), 0.95);
    if (c.face !== 'front') return mul(col, 0.8);
    const j = Math.floor(c.ev / px), n = Math.round(c.fh / px);
    return j === 0 ? mul(col, 1.1) : j === n - 1 ? mul(col, 0.76) : col;
  }, { tag: 'collar front roll' }));
  // closure tab on the roll's front at the wearer's right, folded over the rim
  B.push(box('body', [-4.25, -0.75, -5], [-3.25, 1.25, -4.75], c => {
    if (c.face === 'back') return c.p[1] < -0.5 ? lin(c) : null;
    if (c.face === 'top') return mul(flora(c), TOP + 0.06);
    return bound(c, c.face === 'front' ? 1.02 : 0.8);
  }, { tag: 'collar closure tab' }));
  // side walls beside the head, turned a little so the outside flares; outer face camo, inner face lining
  for (const s of [-1, 1]) {
    const inn = s < 0 ? 'left' : 'right';
    const wall = c => {
      if (c.face === inn || c.face === 'bottom') return lin(c);
      if (c.face === 'top') return Math.abs(c.p[0]) > 5.5 - px - 0.1 ? mul(flora(c), TOP + 0.06) : lin(c);
      const col = flora(c);
      if (isPanel(c)) return mul(col, 0.86);                                   // ends of the roll
      return Math.floor(c.ev / px) === 0 ? mul(col, 1.1) : col;              // rolled top edge
    };
    const xa = s < 0 ? [-5.5, -4.5] : [4.5, 5.5];
    B.push(box('body', [xa[0], -2.5, -4.5], [xa[1], 0.5, 5], wall, { tag: 'collar side', rot: [0, 0, s * 4], pivot: [s * 4.5, -2.5, 0] }));
  }
  // back: rises highest, just behind the hat layer, its ends tucked into the flared side walls
  B.push(box('body', [-5, -3, 3.25], [5, 1.5, 4.75], c => {
    if (c.face === 'front' || c.face === 'bottom') return lin(c);
    if (c.face === 'top') return c.p[2] > 4.75 - px ? mul(flora(c), TOP + 0.06) : lin(c);
    const col = flora(c);
    if (c.face !== 'back') return mul(col, 0.86);
    const j = Math.floor(c.ev / px), n = Math.round(c.fh / px);
    return j === 0 ? mul(col, 1.1) : j === n - 1 ? mul(col, 0.76) : col;
  }, { tag: 'collar back' }));

  // ---------- shoulders (follow the arms): strap end quilted along its length + a short roll at its outer end ----------
  for (const s of [-1, 1]) {
    const part = s < 0 ? 'right_arm' : 'left_arm', offX = s < 0 ? -5 : 5;
    const X = (a, b) => (s < 0 ? [a, b] : [-b, -a]);
    // strap end: runs out to the arm's outer edge (on slim arms it juts past it, like the original's tabs)
    const [p0, p1] = X(-3.25, -0.5);
    const outer = (s < 0 ? p0 : p1) + offX;                                     // outer end, model coords
    B.push(box(part, [p0, -2.75, -2.5], [p1, -2.25, 2.5], c => {
      if (c.face === 'bottom') return lin(c);
      const col = flora(c);
      // two stitched channels running front to back along the strap (a pixel apart)
      if (c.face === 'top') { const k = Math.floor(Math.abs(c.p[0] - outer) / px); return mul(col, TOP * (k === 2 || k === 4 ? 0.84 : 1)); }
      return mul(col, 0.8);
    }, { tag: 'shoulder strap pad' }));
    // short roll (about a pixel thick, half as long as the strap is deep) centred on the strap's outer end: its inner
    // half sits in the strap, its outer half curls over the edge of the shoulder with nothing under it to show
    // daylight. A square roll whose long edges are rounded off by a slightly smaller square turned 45deg (shorter, so
    // the end faces never share a plane); the square end facing forward is the round cap with a stitched mark
    const cx = s < 0 ? -3.25 : 3.25, cy = -2.75;
    const roll = cap => c => {
      if (c.face === 'front' || c.face === 'back') {
        const col = flora(c);
        if (!cap) return mul(col, 0.8);
        const i = Math.floor(c.eu / px), j = Math.floor(c.ev / px);
        return (i === j) === (s < 0) ? mul(col, 0.7) : mul(col, 0.92);      // a diagonal stitch across the cap
      }
      return mul(flora(c), c.face === 'top' ? TOP : c.face === 'bottom' ? 0.7 : 0.9);
    };
    B.push(box(part, [cx - 0.5, cy - 0.5, -1.25], [cx + 0.5, cy + 0.5, 1.25], roll(true), { tag: 'shoulder roll' }));
    B.push(box(part, [cx - 0.4375, cy - 0.4375, -1], [cx + 0.4375, cy + 0.4375, 1], roll(false), { tag: 'shoulder roll (turned)', rot: [0, 0, 45], pivot: [cx, cy, 0] }));
  }
  return { S: K.S, px: K.px, cell: K.cell, boxes: B };
};
