// 运行灯效预览的光栅器 (零依赖; render_lights.mjs 用。由方案评选时的 .candidates/munitions_lights/lraster.mjs 原样搬来, 那边的预览页内联同一份)。
// 与 tools/gunsmith_workstation/raster.mjs 的 rasterize 同一套打光 (方块面明暗 / 实体两盏方向光 / 自发光 = max(环境, block_light / 15)),
// 另外加了三样灯效预览要用的东西:
//   - 透视相机 (玩家眼睛的位置 + 竖直视野 70°, 透视校正的 uv 插值) 与正交相机 (左前等方向视图);
//   - 顶点色四边形 {pts, cols: [[r,g,b,a] x 4], normal, fullBright, shaded}: 覆盖层 (RenderType.textBackground) 与计数屏的字;
//     a < 1 时按 src·a + dst·(1 - a) 混合 (TRANSLUCENT_TRANSPARENCY, 照样写深度); 片元 a < 0.1 丢弃; 批次按中心距离从远到近排序
//     (与游戏里 textBackground 的片元着色器和 sortOnUpload 一致, 所以叠层鬼影、截断在预览里也看得到);
//   - 分层: 静态层 (区块网格 + 地面) 画一次存颜色与深度, 每帧只在它的拷贝上画运动件、计数屏与覆盖层。
// 不做泛光 / bloom: 原版没有, 预览里看到的亮度就是游戏里的。

export function shadeFor(n) {
    // 原版方块面明暗 (上 1.0, 下 0.5, 南北 0.8, 东西 0.6), 与 raster.mjs 相同
    const [x, y, z] = n;
    const up = y > 0 ? y * y : 0, down = y < 0 ? y * y : 0;
    return Math.min(1, x * x * 0.6 + z * z * 0.8 + up * 1.0 + down * 0.5);
}
export function entityShade(n) {
    const a = [0.2, 1.0, -0.7], b = [-0.2, 1.0, 0.7];
    const la = Math.hypot(...a), lb = Math.hypot(...b);
    const d0 = Math.max(0, (n[0] * a[0] + n[1] * a[1] + n[2] * a[2]) / la);
    const d1 = Math.max(0, (n[0] * b[0] + n[1] * b[1] + n[2] * b[2]) / lb);
    return Math.min(1, 0.4 + 0.6 * (d0 + d1));
}
const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
const norm = (v) => { const l = Math.hypot(...v); return v.map((x) => x / l); };

/**
 * 相机。persp: {kind: 'persp', eye, target, fovY (度)}; ortho: {kind: 'ortho', yaw, pitch} (与 raster.mjs cameraFromAngles 同义)。
 * 返回基向量 {f (视线), right, up} 与 eye。
 */
export function makeCamera(spec) {
    let c;
    if (spec.kind === 'persp') c = norm([spec.eye[0] - spec.target[0], spec.eye[1] - spec.target[1], spec.eye[2] - spec.target[2]]);
    else { const y = spec.yaw * Math.PI / 180, p = spec.pitch * Math.PI / 180; c = [-Math.sin(y) * Math.cos(p), Math.sin(p), -Math.cos(y) * Math.cos(p)]; }
    const f = c.map((x) => -x);
    let right = [-f[2], 0, f[0]];
    if (Math.hypot(...right) < 1e-6) right = [1, 0, 0];
    right = norm(right);
    const up = [right[1] * f[2] - right[2] * f[1], right[2] * f[0] - right[0] * f[2], right[0] * f[1] - right[1] * f[0]];
    return { ...spec, c, f, right, up, eye: spec.eye || null };
}
/** 视空间 [x, y, z(深度)]。 */
function toView(cam, p) {
    if (cam.kind === 'persp') { const v = [p[0] - cam.eye[0], p[1] - cam.eye[1], p[2] - cam.eye[2]]; return [dot(v, cam.right), dot(v, cam.up), dot(v, cam.f)]; }
    return [dot(p, cam.right), dot(p, cam.up), dot(p, cam.f)];
}
/**
 * 取景: 把盒子 box {min, max} 的八个角放进 w x h (留 margin), 返回 view = {cam, F, cx, cy, w, h}。
 * trueScale = 按 1080p、竖直 70° 的真实屏幕像素 (透视时 F = 540 / tan 35°, 看的点在画面中心; 正交时忽略)。
 */
export function fitView(cam, box, w, h, opts = {}) {
    const zoom = opts.zoom || 1, margin = opts.margin || 0.94;
    if (cam.kind === 'persp' && opts.trueScale) {
        const F = (opts.screenH || 1080) / 2 / Math.tan((cam.fovY || 70) / 2 * Math.PI / 180) * zoom;
        const t = toView(cam, cam.target);
        return { cam, F, cx: w / 2 - F * t[0] / t[2], cy: h / 2 + F * t[1] / t[2], w, h };
    }
    let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
    for (const x of [box.min[0], box.max[0]]) for (const y of [box.min[1], box.max[1]]) for (const z of [box.min[2], box.max[2]]) {
        const v = toView(cam, [x, y, z]);
        const sx = cam.kind === 'persp' ? v[0] / v[2] : v[0], sy = cam.kind === 'persp' ? -v[1] / v[2] : -v[1];
        minX = Math.min(minX, sx); maxX = Math.max(maxX, sx); minY = Math.min(minY, sy); maxY = Math.max(maxY, sy);
    }
    const F = Math.min(w / (maxX - minX), h / (maxY - minY)) * margin * zoom;
    return { cam, F, cx: w / 2 - ((minX + maxX) / 2) * F, cy: h / 2 - ((minY + maxY) / 2) * F, w, h };
}
/** 模型像素 → 屏幕 (预览里标注用)。 */
export function projectPoint(view, p) {
    const v = toView(view.cam, p);
    if (view.cam.kind === 'persp') return [view.cx + view.F * v[0] / v[2], view.cy - view.F * v[1] / v[2], v[2]];
    return [view.cx + view.F * v[0], view.cy - view.F * v[1], v[2]];
}
/** 这一点附近 1 模型像素 ≈ 多少屏幕像素。 */
export function pxPerModelPx(view, p) {
    const v = toView(view.cam, p);
    return view.cam.kind === 'persp' ? view.F / v[2] : view.F;
}

export function newTarget(w, h, rgb) {
    const t = { width: w, height: h, data: new Uint8ClampedArray(w * h * 4), depth: new Float32Array(w * h) };
    clearTarget(t, rgb);
    return t;
}
export function clearTarget(t, rgb) {
    const d = t.data;
    for (let i = 0; i < d.length; i += 4) { d[i] = rgb[0]; d[i + 1] = rgb[1]; d[i + 2] = rgb[2]; d[i + 3] = 255; }
    t.depth.fill(Infinity);
}
export function copyTarget(src, dst) {
    if (!dst || dst.width !== src.width || dst.height !== src.height) dst = { width: src.width, height: src.height, data: new Uint8ClampedArray(src.data.length), depth: new Float32Array(src.depth.length) };
    dst.data.set(src.data); dst.depth.set(src.depth);
    return dst;
}

const NEAR = 0.5;   // px
/** 近平面裁剪 (视空间, z > NEAR), 顶点带属性数组。 */
function clipNear(vs) {
    if (vs.every((v) => v.p[2] > NEAR)) return vs;
    const out = [];
    for (let i = 0; i < vs.length; i++) {
        const a = vs[i], b = vs[(i + 1) % vs.length];
        const ina = a.p[2] > NEAR, inb = b.p[2] > NEAR;
        if (ina) out.push(a);
        if (ina !== inb) {
            const k = (NEAR - a.p[2]) / (b.p[2] - a.p[2]);
            out.push({ p: a.p.map((x, j) => x + (b.p[j] - x) * k), a: a.a.map((x, j) => x + (b.a[j] - x) * k) });
        }
    }
    return out;
}

/**
 * RenderType.textBackground 的上传排序 (sortOnUpload): 按四边形中心到相机的距离从远到近 (透视 = VertexSorting.DISTANCE_TO_ORIGIN,
 * 正交 = ORTHOGRAPHIC_Z, 按视深)。计数屏的字与灯效覆盖层在同一批里一起排。返回新数组。
 */
export function sortFarToNear(quads, view) {
    const cam = view.cam, persp = cam.kind === 'persp';
    const key = (q) => {
        const c = [0, 1, 2].map((k) => (q.pts[0][k] + q.pts[1][k] + q.pts[2][k] + q.pts[3][k]) / 4);
        return persp ? (c[0] - cam.eye[0]) ** 2 + (c[1] - cam.eye[1]) ** 2 + (c[2] - cam.eye[2]) ** 2 : dot(c, cam.f);
    };
    return quads.map((q) => [key(q), q]).sort((a, b) => b[0] - a[0]).map((e) => e[1]);
}
/** rendertype_text_background.fsh: if (color.a < 0.1) discard; (不写颜色, 不写深度)。 */
export const TEXT_BG_ALPHA_CUTOFF = 0.1;

/**
 * 画一组四边形到 target (带深度)。
 * 贴图四边形 (raster.mjs 的格式): {pts, uvs, normal, image, uvScale, uvScaleV?, cull, shade, entity, light}
 * 顶点色四边形: {pts, cols, normal, fullBright, shaded, cull}: 颜色 = col × (shaded ? 面明暗 : 1) × (fullBright ? 1 : 环境亮度);
 *   任一顶点 a < 1 → 半透明混合 (照样写深度, 同 textBackground); 片元 α < 0.1 丢弃 (同 textBackground 的片元着色器)。
 * opts.brightness: 环境亮度 (白天 1, 夜里 0.18); opts.sort: 先按 sortFarToNear 排序 (textBackground 批次);
 * opts.reverse: 反着排 (近到远, 只用于核对 "顺序不影响画面")。
 */
export function drawQuads(quads, target, view, opts = {}) {
    const { width, height, data, depth } = target;
    const cam = view.cam, persp = cam.kind === 'persp';
    const brightness = opts.brightness != null ? opts.brightness : 1;
    if (opts.sort) { quads = sortFarToNear(quads, view); if (opts.reverse) quads.reverse(); }
    for (const q of quads) {
        // 背面剔除: 面法线朝着相机
        const toEye = persp ? [cam.eye[0] - q.pts[0][0], cam.eye[1] - q.pts[0][1], cam.eye[2] - q.pts[0][2]] : cam.c;
        const facing = dot(q.normal, toEye);
        if (q.cull !== false && facing <= 1e-6) continue;
        const coloured = !!q.cols;
        let mul = 1, img = null, us = 1, vs = 1, uLo = 0, uHi = 0, vLo = 0, vHi = 0, blend = false;
        if (coloured) {
            mul = (q.shaded ? shadeFor(q.normal) : 1) * (q.fullBright ? 1 : brightness);
            blend = q.cols.some((c) => c[3] < 0.999);
        } else {
            const diffuse = q.shade != null ? q.shade : (q.entity ? entityShade(facing < 0 ? q.normal.map((x) => -x) : q.normal) : shadeFor(q.normal));
            mul = diffuse * Math.max(brightness, (q.light || 0) / 15);
            img = q.image;
            us = img.width / q.uvScale; vs = img.height / (q.uvScaleV || q.uvScale);
            uLo = Math.min(q.uvs[0][0], q.uvs[1][0], q.uvs[2][0], q.uvs[3][0]) * us;
            uHi = Math.max(uLo, Math.max(q.uvs[0][0], q.uvs[1][0], q.uvs[2][0], q.uvs[3][0]) * us - 1e-3);
            vLo = Math.min(q.uvs[0][1], q.uvs[1][1], q.uvs[2][1], q.uvs[3][1]) * vs;
            vHi = Math.max(vLo, Math.max(q.uvs[0][1], q.uvs[1][1], q.uvs[2][1], q.uvs[3][1]) * vs - 1e-3);
        }
        const bias = q.entity ? 0.002 : 0;
        let verts = q.pts.map((p, i) => ({ p: toView(cam, p), a: coloured ? q.cols[i].slice() : [q.uvs[i][0] * us, q.uvs[i][1] * vs] }));
        if (persp) { verts = clipNear(verts); if (verts.length < 3) continue; }
        // 屏幕坐标 + 透视校正 (属性 / z 与 1 / z 在屏幕上线性)
        const S = verts.map((v) => {
            const iz = persp ? 1 / v.p[2] : 1;
            const sx = persp ? view.cx + view.F * v.p[0] * iz : view.cx + view.F * v.p[0];
            const sy = persp ? view.cy - view.F * v.p[1] * iz : view.cy - view.F * v.p[1];
            return { x: sx, y: sy, iz, z: v.p[2], a: v.a.map((x) => x * iz) };
        });
        const na = S[0].a.length;
        for (let k = 1; k + 1 < S.length; k++) {
            const A = S[0], B = S[k], C = S[k + 1];
            const area = (B.x - A.x) * (C.y - A.y) - (B.y - A.y) * (C.x - A.x);
            if (Math.abs(area) < 1e-9) continue;
            const minX = Math.max(0, Math.floor(Math.min(A.x, B.x, C.x)));
            const maxX = Math.min(width - 1, Math.ceil(Math.max(A.x, B.x, C.x)));
            const minY = Math.max(0, Math.floor(Math.min(A.y, B.y, C.y)));
            const maxY = Math.min(height - 1, Math.ceil(Math.max(A.y, B.y, C.y)));
            const attr = new Array(na);
            for (let y = minY; y <= maxY; y++) {
                const py = y + 0.5;
                for (let x = minX; x <= maxX; x++) {
                    const px = x + 0.5;
                    const w0 = ((B.x - px) * (C.y - py) - (B.y - py) * (C.x - px)) / area;
                    const w1 = ((C.x - px) * (A.y - py) - (C.y - py) * (A.x - px)) / area;
                    const w2 = 1 - w0 - w1;
                    if (w0 < -1e-6 || w1 < -1e-6 || w2 < -1e-6) continue;
                    const iz = w0 * A.iz + w1 * B.iz + w2 * C.iz;
                    const z = (persp ? 1 / iz : w0 * A.z + w1 * B.z + w2 * C.z) - bias;
                    const idx = y * width + x;
                    if (z >= depth[idx]) continue;
                    const inv = persp ? 1 / iz : 1;
                    for (let j = 0; j < na; j++) attr[j] = (w0 * A.a[j] + w1 * B.a[j] + w2 * C.a[j]) * inv;
                    const o = idx * 4;
                    if (coloured) {
                        const r = attr[0] * mul, g = attr[1] * mul, b = attr[2] * mul, al = Math.max(0, Math.min(1, attr[3]));
                        if (al < TEXT_BG_ALPHA_CUTOFF) continue;
                        if (blend) {
                            data[o] = r * al + data[o] * (1 - al); data[o + 1] = g * al + data[o + 1] * (1 - al); data[o + 2] = b * al + data[o + 2] * (1 - al);
                        } else { data[o] = r; data[o + 1] = g; data[o + 2] = b; }
                        depth[idx] = z;   // textBackground 也写深度 (COLOR_DEPTH_WRITE)
                    } else {
                        const u = Math.min(uHi, Math.max(uLo, attr[0])), v = Math.min(vHi, Math.max(vLo, attr[1]));
                        const tx = Math.min(img.width - 1, Math.max(0, Math.floor(u))), ty = Math.min(img.height - 1, Math.max(0, Math.floor(v)));
                        const ti = (ty * img.width + tx) * 4;
                        if (img.data[ti + 3] < 128) continue;
                        depth[idx] = z;
                        data[o] = img.data[ti] * mul; data[o + 1] = img.data[ti + 1] * mul; data[o + 2] = img.data[ti + 2] * mul;
                    }
                    data[o + 3] = 255;
                }
            }
        }
    }
}
