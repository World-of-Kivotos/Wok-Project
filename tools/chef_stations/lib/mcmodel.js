// Vanilla Minecraft 1.20.1 block-model maths, shared by check.mjs (Node) and the page (build.mjs inlines this file
// with the `export` keywords stripped). Plain ES, no imports, no DOM.
//
// Follows net.minecraft.client.renderer.block.model.{BlockModel, BlockElement, BlockFaceUV, FaceBakery} and
// BlockModelRotation: default UVs, FaceInfo vertex order, UV rotation, element rotation (+rescale quirk),
// blockstate x/y rotation about the block centre, quad facing (for shading/culling), texture #reference chains.
// All positions are in model pixels (0..16 = one block).

export const MC_DIRS = ['down', 'up', 'north', 'south', 'west', 'east'];
export const MC_DIR_VEC = { down: [0, -1, 0], up: [0, 1, 0], north: [0, 0, -1], south: [0, 0, 1], west: [-1, 0, 0], east: [1, 0, 0] };
/** Level.getShade(direction, shade=true) for a normal overworld: up 1.0, down 0.5, N/S 0.8, E/W 0.6. */
export const MC_FACE_SHADE = { up: 1, down: 0.5, north: 0.8, south: 0.8, west: 0.6, east: 0.6 };
export const MC_LEGAL_ANGLES = [-45, -22.5, 0, 22.5, 45];
const RESCALE_22_5 = 1 / Math.cos(Math.PI / 8) - 1, RESCALE_45 = 1 / Math.cos(Math.PI / 4) - 1;

/** 'farmersdelight:block/stove' -> {ns, path}; a bare 'block/stove' is minecraft (vanilla ResourceLocation rule). */
export function mcSplitId(id, defNs = 'minecraft') {
  const s = String(id), i = s.indexOf(':');
  return i < 0 ? { ns: defNs, path: s } : { ns: s.slice(0, i), path: s.slice(i + 1) };
}
export function mcFullId(id, defNs = 'minecraft') { const { ns, path } = mcSplitId(id, defNs); return ns + ':' + path; }

/** 'minecraft:cutout' / 'cutout' / undefined -> 'cutout' | 'solid' | ... ; null when the model chain sets nothing. */
export function mcRenderType(rt) { return rt == null ? null : String(rt).replace(/^minecraft:/, ''); }

/**
 * Walk the parent chain. getModel(fullId) -> parsed JSON or null.
 * Returns { id, chain, textures (merged, child wins), elements (nearest non-empty list), elementsFrom, ao, renderType, errors }.
 */
export function mcResolveModel(id, getModel) {
  const chain = [], errors = [], seen = new Set();
  let cur = mcFullId(id);
  while (cur) {
    if (seen.has(cur)) { errors.push('parent loop at ' + cur); break; }
    seen.add(cur);
    const json = getModel(cur);
    if (!json) { errors.push((chain.length ? 'missing parent model ' : 'missing model ') + cur); break; }
    chain.push({ id: cur, json });
    cur = json.parent ? mcFullId(json.parent) : null;
  }
  const textures = {};
  for (let i = chain.length - 1; i >= 0; i--) Object.assign(textures, chain[i].json.textures || {});
  let elements = [], elementsFrom = null, ao = true, renderType = null;
  for (const c of chain) if (Array.isArray(c.json.elements) && c.json.elements.length) { elements = c.json.elements; elementsFrom = c.id; break; }
  for (const c of chain) if (typeof c.json.ambientocclusion === 'boolean') { ao = c.json.ambientocclusion; break; }
  for (const c of chain) if (typeof c.json.render_type === 'string') { renderType = c.json.render_type; break; }
  return { id: mcFullId(id), chain: chain.map(c => c.id), textures, elements, elementsFrom, ao, renderType: mcRenderType(renderType), errors };
}

/**
 * Resolve a face's "texture" ('#side' or 'side') through the merged texture map, following '#ref' values.
 * Returns the texture resource id ('ns:block/name') or null (in game: the purple/black missing texture).
 */
export function mcResolveTexture(faceTex, textures) {
  let key = String(faceTex).replace(/^#/, '');
  for (let n = 0; n < 32; n++) {
    const v = textures[key];
    if (typeof v !== 'string') return null;
    if (v.startsWith('#')) { key = v.slice(1); continue; }
    return mcFullId(v);
  }
  return null;
}

/** BlockElement.uvsByFace: the UV a face gets when "uv" is omitted. */
export function mcDefaultUV(dir, f, t) {
  switch (dir) {
    case 'down': return [f[0], 16 - t[2], t[0], 16 - f[2]];
    case 'up': return [f[0], f[2], t[0], t[2]];
    case 'north': return [16 - t[0], 16 - t[1], 16 - f[0], 16 - f[1]];
    case 'south': return [f[0], 16 - t[1], t[0], 16 - f[1]];
    case 'west': return [f[2], 16 - t[1], t[2], 16 - f[1]];
    case 'east': return [16 - t[2], 16 - t[1], 16 - f[2], 16 - f[1]];
  }
  return [0, 0, 16, 16];
}

// FaceInfo: vertex order per face, 0 = min / 1 = max on each axis.
const FACE_VERTS = {
  down: [[0, 0, 1], [0, 0, 0], [1, 0, 0], [1, 0, 1]],
  up: [[0, 1, 0], [0, 1, 1], [1, 1, 1], [1, 1, 0]],
  north: [[1, 1, 0], [1, 0, 0], [0, 0, 0], [0, 1, 0]],
  south: [[0, 1, 1], [0, 0, 1], [1, 0, 1], [1, 1, 1]],
  west: [[0, 1, 0], [0, 0, 0], [0, 0, 1], [0, 1, 1]],
  east: [[1, 1, 1], [1, 0, 1], [1, 0, 0], [1, 1, 0]],
};

function rotAxis(v, axis, deg) {
  const a = deg * Math.PI / 180, c = Math.cos(a), s = Math.sin(a), [x, y, z] = v;
  if (axis === 'x') return [x, y * c - z * s, y * s + z * c];
  if (axis === 'y') return [x * c + z * s, y, -x * s + z * c];
  return [x * c - y * s, x * s + y * c, z];
}
/** BlockModelRotation(x, y): rotateYXZ(-y, -x, 0) about the block centre — rotate X first, then Y. */
function blockRot(v, rx, ry, centre = 8) {
  let p = [v[0] - centre, v[1] - centre, v[2] - centre];
  if (rx) p = rotAxis(p, 'x', -rx);
  if (ry) p = rotAxis(p, 'y', -ry);
  return p.map(c => Math.round((c + centre) * 1e6) / 1e6);
}
function dirOfVec(n) {
  let best = 'up', bd = -Infinity;
  for (const d of MC_DIRS) { const v = MC_DIR_VEC[d], dot = v[0] * n[0] + v[1] * n[1] + v[2] * n[2]; if (dot > bd) { bd = dot; best = d; } }
  return best;
}
/** Unit normal of a quad from FaceBakery.calculateFacing's cross product; null when degenerate. */
export function mcQuadNormal(p) {
  const a = [p[0][0] - p[1][0], p[0][1] - p[1][1], p[0][2] - p[1][2]], b = [p[2][0] - p[1][0], p[2][1] - p[1][1], p[2][2] - p[1][2]];
  const n = [b[1] * a[2] - b[2] * a[1], b[2] * a[0] - b[0] * a[2], b[0] * a[1] - b[1] * a[0]], l = Math.hypot(...n);
  return l < 1e-9 ? null : n.map(c => c / l);
}
export function mcRotateDir(dir, rx, ry) { return dirOfVec(blockRot(MC_DIR_VEC[dir].map(c => c + 8), rx, ry).map(c => c - 8)); }

/** The 4 vertices (px) of an element face after the element's own rotation (no blockstate rotation). */
export function mcFaceVerts(el, dir) {
  const f = el.from, t = el.to, lo = [Math.min(f[0], t[0]), Math.min(f[1], t[1]), Math.min(f[2], t[2])], hi = [Math.max(f[0], t[0]), Math.max(f[1], t[1]), Math.max(f[2], t[2])];
  let verts = FACE_VERTS[dir].map(m => [m[0] ? hi[0] : lo[0], m[1] ? hi[1] : lo[1], m[2] ? hi[2] : lo[2]]);
  const r = el.rotation;
  if (r && r.axis && r.angle != null) {
    const o = r.origin || [8, 8, 8];
    // FaceBakery.applyElementRotation: rotate about origin, then scale the two perpendicular axes when rescale is set.
    // Vanilla quirk: |angle| == 22.5 uses 1/cos(22.5deg), every other angle (45 AND 0) uses 1/cos(45deg).
    let sc = [1, 1, 1];
    if (r.rescale) {
      const k = Math.abs(r.angle) === 22.5 ? RESCALE_22_5 : RESCALE_45;
      sc = [r.axis === 'x' ? 1 : 1 + k, r.axis === 'y' ? 1 : 1 + k, r.axis === 'z' ? 1 : 1 + k];
    }
    verts = verts.map(v => { const q = rotAxis([v[0] - o[0], v[1] - o[1], v[2] - o[2]], r.axis, r.angle); return [q[0] * sc[0] + o[0], q[1] * sc[1] + o[1], q[2] * sc[2] + o[2]]; });
  }
  return verts;
}

/** BlockFaceUV.getU/getV with "rotation": per-vertex [u, v] in 0..16 texture units. */
export function mcFaceUVs(uv, rotation = 0) {
  const out = [];
  for (let i = 0; i < 4; i++) {
    const s = (i + ((rotation / 90) | 0)) % 4;
    out.push([uv[s === 0 || s === 1 ? 0 : 2], uv[s === 0 || s === 3 ? 1 : 3]]);
  }
  return out;
}

/**
 * Bake a resolved model into quads, like ModelBakery/FaceBakery do.
 * opts: { x, y } blockstate rotation in degrees (multiples of 90).
 * Quad: { el, elName, dir (face key in JSON), facing (after all rotation; drives shading), cull (rotated cullface or null),
 *         texRef, tex (resolved id or null), uv [[u,v]x4], pos [[x,y,z]x4] (px), normal, shade (bool), tint (tintindex, -1 none) }
 */
export function mcBakeModel(model, opts = {}) {
  const rx = opts.x || 0, ry = opts.y || 0, quads = [];
  model.elements.forEach((el, ei) => {
    if (!el || !Array.isArray(el.from) || !Array.isArray(el.to) || !el.faces) return;
    for (const dir of MC_DIRS) {
      const face = el.faces[dir];
      if (!face) continue;
      const uv = Array.isArray(face.uv) && face.uv.length === 4 ? face.uv : mcDefaultUV(dir, el.from, el.to);
      let pos = mcFaceVerts(el, dir);
      if (rx || ry) pos = pos.map(v => blockRot(v, rx, ry));
      const n = mcQuadNormal(pos);
      quads.push({
        el: ei, elName: el.name || null, dir, facing: n ? dirOfVec(n) : 'up', normal: n,
        cull: face.cullface && MC_DIR_VEC[face.cullface] ? mcRotateDir(face.cullface, rx, ry) : null,
        texRef: face.texture, tex: face.texture != null ? mcResolveTexture(face.texture, model.textures) : null,
        uv: mcFaceUVs(uv, face.rotation || 0), uvRect: uv, pos, shade: el.shade !== false,
        tint: Number.isInteger(face.tintindex) ? face.tintindex : -1,
      });
    }
  });
  return quads;
}

/** Axis-aligned bounds (px) of baked quads: [[minX,minY,minZ],[maxX,maxY,maxZ]] or null. */
export function mcBounds(quads) {
  if (!quads.length) return null;
  const lo = [Infinity, Infinity, Infinity], hi = [-Infinity, -Infinity, -Infinity];
  for (const q of quads) for (const p of q.pos) for (let k = 0; k < 3; k++) { lo[k] = Math.min(lo[k], p[k]); hi[k] = Math.max(hi[k], p[k]); }
  return [lo, hi];
}

/** True when the model is a plain full cube (one unrotated 0..16 element with all six faces) — such a block culls neighbours' cullfaces. */
export function mcIsFullCube(model) {
  return model.elements.some(el => !el.rotation && el.from && el.to && el.from.every(v => v === 0) && el.to.every(v => v === 16) && MC_DIRS.every(d => el.faces && el.faces[d]))
    && (model.renderType == null || model.renderType === 'solid');
}

/**
 * Animation info for a texture of size w x h with optional .mcmeta JSON (AnimationMetadataSection rules):
 * frames are square (min(w,h)) unless width/height are given; frame list defaults to 0..n-1; frametime defaults to 1.
 */
export function mcAnimInfo(w, h, mcmeta) {
  const a = mcmeta && mcmeta.animation;
  if (!a) return { animated: false, fw: w, fh: h, cols: 1, count: 1, frames: [{ index: 0, time: 1 }], total: 1, interpolate: false };
  let fw = a.width, fh = a.height;
  if (fw == null && fh == null) fw = fh = Math.min(w, h); else { fw = fw ?? w; fh = fh ?? h; }
  const cols = Math.max(1, Math.floor(w / fw)), rows = Math.max(1, Math.floor(h / fh)), count = cols * rows;
  const ft = Number.isInteger(a.frametime) && a.frametime > 0 ? a.frametime : 1;
  const frames = Array.isArray(a.frames) && a.frames.length
    ? a.frames.map(f => (typeof f === 'number' ? { index: f, time: ft } : { index: f.index, time: f.time ?? ft }))
    : Array.from({ length: count }, (_, i) => ({ index: i, time: ft }));
  return { animated: frames.length > 1, fw, fh, cols, count, frames, total: frames.reduce((s, f) => s + f.time, 0), interpolate: !!a.interpolate };
}
/** Frame pair at an integer game tick: { a, b, t } where t is the vanilla interpolation weight of frame b (0 unless interpolate). */
export function mcAnimFrame(info, tick) {
  if (!info.animated) return { a: 0, b: 0, t: 0 };
  let r = ((tick % info.total) + info.total) % info.total;
  for (let i = 0; i < info.frames.length; i++) {
    const f = info.frames[i];
    if (r < f.time) {
      const next = info.frames[(i + 1) % info.frames.length];
      return { a: f.index, b: next.index, t: info.interpolate ? r / f.time : 0 };
    }
    r -= f.time;
  }
  return { a: info.frames[0].index, b: info.frames[0].index, t: 0 };
}
