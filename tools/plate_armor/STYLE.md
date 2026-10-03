# Plate-armor remodel — modelling rules (折中 2× style)

The user chose the **middle style ("折中 2×", mode 'M')** for every armor. New armors implement ONLY this style:

```js
// armors/<key>.js
ARMORS.<key> = function (mode) {
  const K = kit('M');          // always the 2x style, whatever mode is asked for
  const { edge, molle, G, isPanel, isSide, px } = K;   // px = 0.5 (one art cell)
  const B = [];
  ...
  return { S: K.S, px: K.px, cell: K.cell, boxes: B };
};
```

Finished examples to learn from (read them fully): `armors/avs.js`, `armors/iotv.js`, `armors/thor.js`, `armors/redut.js`,
`armors/kirasa.js`, `armors/paca.js`, `armors/jaypc.js` (these also contain A/B branches — ignore those, look at the M paths).

## Source of truth: the original
- Every item follows its ORIGINAL: the Escape from Tarkov item render (`https://assets.tarkov.dev/<id>-512.webp`,
  also `<id>-image.webp` = inspect view). `items.json` lists each item's reference when we found it.
- Missing reference? Find it: in the browser, on `https://tarkov-stammtisch.de` run a same-origin
  `fetch('/en/wiki/items/<slug>')` for slug guesses (lower-case EFT name, spaces → `-`, drop dots/brackets, e.g.
  `firstspear-strandhogg-plate-carrier-ranger-green`) and read `assets.tarkov.dev/<24-hex id>` out of the HTML; try
  `https://tarkov-market.com/item/<name_with_underscores>`; use WebSearch ("<name> tarkov"). If the item is not in EFT
  (some FORT Gladiator-S / 6B45 variants may be newer or Arena-only), use real product photos (manufacturer/retailer)
  and set `"refSource"` in the meta to say so. Never invent a look without saying so in the notes.
- Colours, camo, pouches and attached gear come from the reference. In EFT many rigs carry their gear as part of the
  item model (mags, tourniquets, radios…) — those ARE part of the original. Loot inside grid slots is not.
- If the mod's name/colour contradicts the original (e.g. "Green" but the render is navy), follow the original and say
  so in the notes (suggest the rename; mention if the inventory icon must change too).

## Coordinates
MC model space, units = skin pixels. **y points DOWN** (0 = top of torso, 12 = hips). **Front = -z.** **x < 0 = the
wearer's RIGHT** (viewer's left when looking at the front). Torso box x -4..4, y 0..12, z -2..2. The skin OUTER layer
(jacket) is at x ±4.25, y -0.25..12.25, z ±2.25 — every visible surface sits ≥ 0.25 outside it.
Arms are separate parts in part-local coords: `right_arm` box x -3..1, y -2..10, z -2..2 (pivot at body (-5,2,0));
`left_arm` mirrored. Slim arms are 3 wide. The head (8×8×8 at y -8..0, hat to y 0.5) hides anything above y≈0.5
inside |x|,|z| < 4.5 → collars can only show at the sides and as a low front roll.
Straps, collars, flaps, pouches belong to part `body`; only shoulder/deltoid armor that must follow the arm goes on the
arm parts. Flaps / danglers / groin protectors end by y ≈ 16.5.

## Geometry rules (check.mjs enforces most of them)
- Unrotated box SIZES on the ¼ px grid (positions free). Rotations: `rot:[x,y,z]` degrees about `pivot`, applied Z then
  Y then X in MC axes — use them for flares, pointed flaps, angled straps. `octa()` for round things.
- No two faces in the same plane facing the same way where they overlap (z-fighting; armor renders without culling).
  Tuck the smaller piece 0.25 in or let it stand 0.25 proud, or make the hidden face transparent.
- Flat cut-out plates (rings, clips, D-rings, tape rolls) standing on another face: return `null` for their `back` face.
- Nothing floats: every piece touches the armor (check.mjs "floating" warning). Beyond that, **things must be SEATED,
  not perched**: magazines go 1–2 px down INTO their pouch (the pouch top face is an open mouth: light rim, dark inside),
  pistol mags sit in their own front sleeve, radios/tourniquets sit on or in a pouch/strap, never balanced on an edge.
  Look at every item from the side (`__cam(±1.3, 0.15, 32)`) — if you can see daylight under/behind it, fix it.
- Magazines must read as magazines: body + a narrower feed-lip block on top with one brass round (copper tip) lying
  across it; broad side faces forward; darker spine sides; one darker rib. See `armors/avs.js` (magPaint / lips).
- Side pouches: the arms occupy the torso sides, so pouches go on the carrier's front corners / front of the cummerbund,
  pressed against the carrier, not hanging in front of the arm with a gap.
- Keep the silhouette tier-appropriate: I–II thin and light; plate carriers IV–V medium; VI (and full-protection kits)
  the bulkiest. Box budget ≤ ~75.

## 2× painting rules (what made the finished ones score well)
- One art cell = 0.5 px. Any pattern that alternates every cell (1-cell checkers, 1-cell dotted stitches, 1-cell X
  lacing) reads as noise/checkerboard → use ≥ 1 px periods, keep tacks/stitches subtle (×0.8 not ×0.5).
- No stitch lines in M. Edges via `K.edge` (light top rim, darker sides/bottom).
- Don't over-darken: plain fabric should be about as bright as the reference; MOLLE webbing is usually the DARKER band
  on the reference (check it) at ~0.7× the panel, not near-black.
- Keep noise low: `G(c, 0.05–0.1)` grain plus a faint low-frequency fbm; camo blobs ≥ 1 px (sample camo on
  `snap(c.p, 0.5)` scaled so shapes are 1–3 px). MultiCam/A-TACS/EMR helpers exist or can be built with `layers()`.
- Colours sampled by eye from the reference render, keep its saturation (don't grey it out).
- Dark inner lining on inward-facing faces (armholes, collar insides, open pouch mouths).

## Notes (armors/<key>.meta.json, Chinese, for players/the server owner)
```json
{ "key": "<key>", "name": "<short zh button name>", "tier": "<tierLabel from items.json>", "title": "<zh name>：对照原图",
  "refs": [ { "label": "<short item name>", "url": "<reference url>" } ], "refSource": "<only if not an EFT render>",
  "notes": [ "3–5 bullets" ] }
```
One idea per bullet, plain words, no raw numbers, no jargon (no UV/texel/0.25). Directions always as
穿戴者右侧（正面看在左边）/ 穿戴者左侧（正面看在右边）. Say what the original has, what was wrong in the current
mod model (or its name/colour) and how it's fixed, and any Minecraft proportion limit. No bold topic labels.

## Verify (mandatory)
- `cd <dir>; & <node> check.mjs <key> M` → `"ok": true` and zero warnings.
- `& <node> build.mjs --only <key> --out preview-<key>.html`, then in the built-in browser (your OWN tab, pass its
  tabId; resize_window 1400×1000) open `http://localhost:5181/preview-<key>.html` and drive it with javascript:
  `__focus(['now','M'])`, `__view('front'|'side'|'back'|'close', {walk, jacket, slim})`,
  `__cam(yaw, pitch, dist, armsRad)` (yaw 0 = front, -1.57 = wearer's right side; armsRad e.g. -1.4 raises the arms
  out of the way). Screenshot with your tabId; if the pane is hidden and screenshots fail, read pixels from
  `document.querySelector('[data-variant=M] canvas').toDataURL()`. Compare with the reference image side by side.
  Close your tab when done.
- Only edit your own `armors/<key>.js` / `.meta.json` files.

## If the shared browser has no free tab
Many agents share one browser pane and it has a tab cap. If `tabs_create` fails ("tab cap reached") or screenshots
time out, use the headless Edge driver instead: `& <node> tools\shot.mjs <url> <outDir> <plan.json>` where plan.json is
`[{ "name": "front", "js": "__focus(['now','M']); __view('front')", "wait": 400, "card": "M" }, ...]` (`card` clips the
shot to that card's canvas; `sub: [fx,fy,fw,fh]` zooms into part of it). It writes `<outDir>/<name>.png`; look at them
with the Read tool. Keep outDir inside your own scratch folder. The reference image can be downloaded the same way
(navigate to the tarkov.dev URL) or opened with a plan step on that URL.

## If your files already exist
An earlier run may have been interrupted after writing `armors/<key>.js` (and maybe the meta). Do not start over:
read what is there, run check.mjs, look at it against the original, then finish and fix it (write the meta if missing).

## If http://localhost:5181 does not answer
Start it yourself in the background: `Start-Process -WindowStyle Hidden "<node>" -ArgumentList 'serve.mjs','5181' -WorkingDirectory <dir>` (a second copy just fails to bind the port, which is harmless).

## USER FEEDBACK (binding — check every armor against these)
- 2026-10-01: "6B13、6B23 有点短了". The lead lengthened them to match the originals' proportions: 6B13 runs down to
  y 15.5 (+0.5 hem) with the chest flap to 13.5; 6B23-1/-2 body to 13.5 (back 14 + turned-out tail), lower flap
  9.5..13.5, groin flap 13.25..16.5. **Do not shorten them again.**
- General rule from this: measure the ORIGINAL's length against its width (shoulder to hem, and to the bottom of any
  groin flap) and scale it onto the 8-px-wide torso, where the belt/hips are at y = 12. Many vests — especially the
  Russian body armors (6B5, 6B13, 6B23, 6B43, Gzhel-K, Korund, Kirasa, Redut, Defender) and full-protection kits — run
  well below the belt; a hem at y ≈ 12 is usually too short for them. Flaps may go to y ≈ 16.5.
- 2026-09-30: magazines must sit IN their pouches and read as magazines; gear must not perch or float (see above).
- 2026-10-03: the owner ACCEPTED the suggested renames and they are done in the mod: 丛林绿→游骑兵绿 (MMAC, RBAV-AF, Strandhogg RG, AVS, TacTec), TV-110 灰褐色→卡其色, Kirasa-N 绿色→藏青色 (icon redrawn navy), Gladiator-S 无惧死亡→死亡不可避免, Gladiator-S Viking moved to LIGHT (V · 轻型), TV-115 icon redrawn olive, 6B5-15 icon redrawn in Flora. Notes must state these as done, not as suggestions.
