// Minecraft JSON 方块模型的软件光栅预览核心 (浏览器与 Node 共用, 不依赖任何外部库)。
// 坐标一律用模型像素: x 向东, y 向上, z 向南; 一个方块 = 16。
// 只复刻预览需要的原版语义: 元素 from/to、单轴旋转与 rescale、面 uv/rotation/缺省 uv、
// 方块状态 y 旋转、原版面朝向明暗, 以及 BlockEntityRenderer 里 ModelPart 的层级变换与盒式 UV。

const FACE_NAMES = ['north', 'south', 'east', 'west', 'up', 'down'];

// 原版缺省 uv (BlockElement#uvsByFace)。
function defaultUv(face, from, to) {
    const [x1, y1, z1] = from;
    const [x2, y2, z2] = to;
    switch (face) {
        case 'down': return [x1, 16 - z2, x2, 16 - z1];
        case 'up': return [x1, z1, x2, z2];
        case 'north': return [16 - x2, 16 - y2, 16 - x1, 16 - y1];
        case 'south': return [x1, 16 - y2, x2, 16 - y1];
        case 'west': return [z1, 16 - y2, z2, 16 - y1];
        case 'east': return [16 - z2, 16 - y2, 16 - z1, 16 - y1];
        default: throw new Error('bad face ' + face);
    }
}

// 面的四角, 按贴图的 左上 / 右上 / 右下 / 左下 顺序 (与缺省 uv 的朝向一致)。
function faceCorners(face, from, to) {
    const [x1, y1, z1] = from;
    const [x2, y2, z2] = to;
    switch (face) {
        case 'north': return [[x2, y2, z1], [x1, y2, z1], [x1, y1, z1], [x2, y1, z1]];
        case 'south': return [[x1, y2, z2], [x2, y2, z2], [x2, y1, z2], [x1, y1, z2]];
        case 'west': return [[x1, y2, z1], [x1, y2, z2], [x1, y1, z2], [x1, y1, z1]];
        case 'east': return [[x2, y2, z2], [x2, y2, z1], [x2, y1, z1], [x2, y1, z2]];
        case 'up': return [[x1, y2, z1], [x2, y2, z1], [x2, y2, z2], [x1, y2, z2]];
        case 'down': return [[x1, y1, z2], [x2, y1, z2], [x2, y1, z1], [x1, y1, z1]];
        default: throw new Error('bad face ' + face);
    }
}

const FACE_NORMALS = {
    north: [0, 0, -1], south: [0, 0, 1], east: [1, 0, 0], west: [-1, 0, 0], up: [0, 1, 0], down: [0, -1, 0],
};

function rotAxis(p, axis, deg, origin) {
    const r = deg * Math.PI / 180;
    const c = Math.cos(r);
    const s = Math.sin(r);
    const x = p[0] - origin[0];
    const y = p[1] - origin[1];
    const z = p[2] - origin[2];
    let out;
    // 右手系旋转, 与 Quaternionf.rotationAxis 相同。
    if (axis === 'x') out = [x, y * c - z * s, y * s + z * c];
    else if (axis === 'y') out = [x * c + z * s, y, -x * s + z * c];
    else out = [x * c - y * s, x * s + y * c, z];
    return [out[0] + origin[0], out[1] + origin[1], out[2] + origin[2]];
}

function rescaleAround(p, axis, deg, origin) {
    const f = 1 / Math.cos(deg * Math.PI / 180);
    const q = [p[0] - origin[0], p[1] - origin[1], p[2] - origin[2]];
    if (axis !== 'x') q[0] *= f;
    if (axis !== 'y') q[1] *= f;
    if (axis !== 'z') q[2] *= f;
    return [q[0] + origin[0], q[1] + origin[1], q[2] + origin[2]];
}

function rotY90(p, times) {
    // 方块状态 "y": 90 = 俯视顺时针 (北 -> 东), 绕方块中心 (8, 8)。
    let [x, y, z] = p;
    for (let i = 0; i < times; i++) {
        const nx = 16 - z;
        const nz = x;
        x = nx;
        z = nz;
    }
    return [x, y, z];
}

function resolveTexture(ref, textures, depth = 0) {
    if (depth > 8) throw new Error('texture ref loop: ' + ref);
    if (!ref.startsWith('#')) return ref;
    const key = ref.slice(1);
    const next = textures[key];
    if (next === undefined) throw new Error('unresolved texture #' + key);
    return resolveTexture(next, textures, depth + 1);
}

/**
 * 把一个 JSON 方块模型烘成世界空间四边形。
 * @param model 已解析的模型 JSON (不跟 parent, 只取自身 textures/elements)
 * @param opts {offset:[x,y,z] 像素, yRot:0|90|180|270, textureLookup(id)->image, cull:true}
 */
export function bakeBlockModel(model, opts = {}) {
    const offset = opts.offset || [0, 0, 0];
    const turns = ((opts.yRot || 0) / 90) & 3;
    const textures = model.textures || {};
    const quads = [];
    for (const el of model.elements || []) {
        const rot = el.rotation;
        for (const face of FACE_NAMES) {
            const f = el.faces && el.faces[face];
            if (!f) continue;
            const texId = resolveTexture(f.texture, textures);
            const image = opts.textureLookup(texId);
            const uv = f.uv || defaultUv(face, el.from, el.to);
            const cornerUv = [[uv[0], uv[1]], [uv[2], uv[1]], [uv[2], uv[3]], [uv[0], uv[3]]];
            const k = (((f.rotation || 0) / 90) & 3);
            let pts = faceCorners(face, el.from, el.to);
            let normal = FACE_NORMALS[face];
            if (rot && rot.angle) {
                pts = pts.map((p) => rot.rescale ? rescaleAround(rotAxis(p, rot.axis, rot.angle, rot.origin), rot.axis, rot.angle, rot.origin) : rotAxis(p, rot.axis, rot.angle, rot.origin));
                normal = rotAxis(normal, rot.axis, rot.angle, [0, 0, 0]);
            }
            pts = pts.map((p) => rotY90(p, turns)).map((p) => [p[0] + offset[0], p[1] + offset[1], p[2] + offset[2]]);
            normal = rotY90([normal[0] + 8, normal[1], normal[2] + 8], turns);
            normal = [normal[0] - 8, normal[1], normal[2] - 8];
            // 面 rotation: 贴图顺时针转, 面上第 i 角显示贴图第 (i - k) 角。
            const uvs = [0, 1, 2, 3].map((i) => cornerUv[(i - k + 4) & 3]);
            const fd = { ...(el.forge_data || {}), ...(f.forge_data || {}) };
            quads.push({
                pts, uvs, normal, image, uvScale: 16, cull: opts.cull !== false,
                shade: el.shade === false ? 1 : null,
                // Forge ExtraFaceData: block_light 决定夜间亮度 (0..15)。
                light: Math.max(fd.block_light || 0, fd.sky_light || 0),
                tag: opts.tag || null,
            });
        }
    }
    return quads;
}

// ---------- BlockEntityRenderer 的 ModelPart ----------

function matMul(a, b) {
    const o = new Array(16).fill(0);
    for (let r = 0; r < 4; r++) for (let c = 0; c < 4; c++) {
        let s = 0;
        for (let k = 0; k < 4; k++) s += a[r * 4 + k] * b[k * 4 + c];
        o[r * 4 + c] = s;
    }
    return o;
}
const IDENT = [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1];
function mTranslate(x, y, z) { return [1, 0, 0, x, 0, 1, 0, y, 0, 0, 1, z, 0, 0, 0, 1]; }
function mScale(x, y, z) { return [x, 0, 0, 0, 0, y, 0, 0, 0, 0, z, 0, 0, 0, 0, 1]; }
function mRotX(a) { const c = Math.cos(a), s = Math.sin(a); return [1, 0, 0, 0, 0, c, -s, 0, 0, s, c, 0, 0, 0, 0, 1]; }
function mRotY(a) { const c = Math.cos(a), s = Math.sin(a); return [c, 0, s, 0, 0, 1, 0, 0, -s, 0, c, 0, 0, 0, 0, 1]; }
function mRotZ(a) { const c = Math.cos(a), s = Math.sin(a); return [c, -s, 0, 0, s, c, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]; }
function apply(m, p) {
    return [
        m[0] * p[0] + m[1] * p[1] + m[2] * p[2] + m[3],
        m[4] * p[0] + m[5] * p[1] + m[6] * p[2] + m[7],
        m[8] * p[0] + m[9] * p[1] + m[10] * p[2] + m[11],
    ];
}
function applyDir(m, p) {
    return [m[0] * p[0] + m[1] * p[1] + m[2] * p[2], m[4] * p[0] + m[5] * p[1] + m[6] * p[2], m[8] * p[0] + m[9] * p[1] + m[10] * p[2]];
}

/**
 * 从 GunsmithAssemblyBenchRenderer.java 源码里解析 createBodyLayer 与姿态常量。
 * 预览以 Java 源为唯一真源, 改机械臂时只改 Java, 预览自动跟随。
 */
export function parseArmJava(src) {
    const layer = src.slice(src.indexOf('createBodyLayer()'));
    const texSize = /LayerDefinition\.create\(mesh,\s*(\d+),\s*(\d+)\)/.exec(layer);
    const parts = {};
    const varToName = { root: 'root' };
    parts.root = { name: 'root', parent: null, pose: { x: 0, y: 0, z: 0, xRot: 0, yRot: 0, zRot: 0 }, cubes: [] };
    const re = /(?:PartDefinition\s+(\w+)\s*=\s*)?(\w+)\.addOrReplaceChild\(\s*"(\w+)"\s*,([\s\S]*?)\);/g;
    let m;
    while ((m = re.exec(layer))) {
        const [, varName, parentVar, name, body] = m;
        const parentName = varToName[parentVar];
        if (!parentName) throw new Error('arm parse: unknown parent var ' + parentVar);
        const cubes = [];
        let tex = [0, 0];
        const tokRe = /texOffs\(\s*(-?\d+)\s*,\s*(-?\d+)\s*\)|addBox\(([^)]*)\)|mirror\(\)/g;
        let t;
        while ((t = tokRe.exec(body))) {
            if (t[1] !== undefined) tex = [Number(t[1]), Number(t[2])];
            else if (t[3] !== undefined) {
                const n = t[3].split(',').map((s) => parseFloat(s.replace(/[FfDd]/g, '')));
                // addBox(..., new CubeDeformation(g)): 几何外扩 g, UV 仍按原尺寸 (tokRe 在 CubeDeformation 的右括号处截断, 所以这里只有左半)
                const grow = /CubeDeformation\(\s*(-?[\d.]+)/.exec(t[3]);
                cubes.push({ u: tex[0], v: tex[1], x: n[0], y: n[1], z: n[2], w: n[3], h: n[4], d: n[5], grow: grow ? parseFloat(grow[1]) : 0 });
            }
        }
        const pose = { x: 0, y: 0, z: 0, xRot: 0, yRot: 0, zRot: 0 };
        const off = /PartPose\.offset\(([^)]*)\)/.exec(body);
        const offRot = /PartPose\.offsetAndRotation\(([^)]*)\)/.exec(body);
        const num = (s) => parseFloat(s.replace(/[FfDd]/g, ''));
        if (offRot) {
            const n = offRot[1].split(',').map(num);
            Object.assign(pose, { x: n[0], y: n[1], z: n[2], xRot: n[3], yRot: n[4], zRot: n[5] });
        } else if (off) {
            const n = off[1].split(',').map(num);
            Object.assign(pose, { x: n[0], y: n[1], z: n[2] });
        }
        parts[name] = { name, parent: parentName, pose, basePose: { ...pose }, cubes };
        if (varName) varToName[varName] = name;
    }
    const consts = {};
    const cre = /static final float (\w+)\s*=\s*([^;]+);/g;
    let c;
    while ((c = cre.exec(src))) {
        const expr = c[2].trim();
        const atan = /-?\s*\(float\)\s*Math\.atan2\(\s*([-\d.]+)D?\s*,\s*([-\d.]+)D?\s*\)/.exec(expr);
        if (atan) consts[c[1]] = (expr.trim().startsWith('-') ? -1 : 1) * Math.atan2(parseFloat(atan[1]), parseFloat(atan[2]));
        else if (/^-?[\d.]+F?$/.test(expr)) consts[c[1]] = parseFloat(expr);
    }
    const windows = {};
    for (const nm of ['turn', 'reach', 'grip', 'weld']) {
        const w = new RegExp(`float\\s+${nm}\\s*=\\s*motionWindow\\(phase,\\s*([\\d.]+)F,\\s*([\\d.]+)F,\\s*([\\d.]+)F,\\s*([\\d.]+)F\\)`).exec(src);
        if (w) windows[nm] = w.slice(1, 5).map(Number);
    }
    return { parts, texW: texSize ? Number(texSize[1]) : 64, texH: texSize ? Number(texSize[2]) : 64, consts, windows: Object.keys(windows).length === 4 ? windows : null };
}

// 盒式 UV (ModelPart.Cube), 返回每面的像素矩形 [u1, v1, u2, v2]。
function boxUv(c) {
    const u = c.u, v = c.v, w = c.w, h = c.h, d = c.d;
    return {
        down: [u + d + w, v + d, u + d + w + w, v],
        up: [u + d, v, u + d + w, v + d],
        west: [u, v + d, u + d, v + d + h],
        north: [u + d, v + d, u + d + w, v + d + h],
        east: [u + d + w, v + d, u + d + w + d, v + d + h],
        south: [u + d + w + d, v + d, u + d + w + d + w, v + d + h],
    };
}

/**
 * 按渲染器姿态烘焙机械臂四边形 (朝北, 主格方块局部坐标, 像素)。
 * @param arm parseArmJava 的结果; pose: {name: {xRot,yRot,zRot,x,y,z}} 覆盖
 */
export function bakeArm(arm, poseOverrides, image, opts = {}) {
    const quads = [];
    // render(): translate(0.5, 1.0, 0.5) 方块 -> 像素 (8, 16, 8); 朝北不转; scale(1, -1, 1)。
    const rootM = matMul(mTranslate(8 + (opts.offset ? opts.offset[0] : 0), 16, 8 + (opts.offset ? opts.offset[2] : 0)), mScale(1, -1, 1));
    const children = {};
    for (const p of Object.values(arm.parts)) {
        if (p.parent) (children[p.parent] = children[p.parent] || []).push(p.name);
    }
    const visit = (name, parentM) => {
        const part = arm.parts[name];
        const pose = { ...part.pose, ...(poseOverrides[name] || {}) };
        // ModelPart.visible = false 时整棵子树都不画 (携带件)
        if (pose.visible === false) return;
        let m = matMul(parentM, mTranslate(pose.x, pose.y, pose.z));
        if (pose.xRot || pose.yRot || pose.zRot) {
            // Quaternionf().rotationZYX(z, y, x) = Rz * Ry * Rx
            m = matMul(m, matMul(mRotZ(pose.zRot), matMul(mRotY(pose.yRot), mRotX(pose.xRot))));
        }
        for (const cube of part.cubes) {
            const g = cube.grow || 0;
            const from = [cube.x - g, cube.y - g, cube.z - g];
            const to = [cube.x + cube.w + g, cube.y + cube.h + g, cube.z + cube.d + g];
            const uvr = boxUv(cube);
            for (const face of FACE_NAMES) {
                // ModelPart 的 y 朝下; 这里直接用同一套角点, 翻转由 rootM 的 scale(1,-1,1) 负责。
                const pts = faceCorners(face, from, to).map((p) => apply(m, p));
                let normal = applyDir(m, FACE_NORMALS[face]);
                const len = Math.hypot(normal[0], normal[1], normal[2]) || 1;
                normal = normal.map((x) => x / len);
                const r = uvr[face];
                const cornerUv = [[r[0], r[1]], [r[2], r[1]], [r[2], r[3]], [r[0], r[3]]];
                quads.push({ pts, uvs: cornerUv, normal, image, uvScale: arm.texW, uvScaleV: arm.texH, cull: false, shade: null, entity: true, tag: 'arm' });
            }
        }
        for (const child of children[name] || []) visit(child, m);
    };
    visit('root', rootM);
    return quads;
}

// ---------- 机械臂关键帧程序 (GunsmithArmProgram) ----------

/**
 * 从 GunsmithArmProgram.java 解析几何常量与关键帧表。每行尾部的 "// 动作名" 注释作为预览里的当前动作名。
 * 返回 {geo, rows, payloadParts: {code: ModelPart 名}, places}。
 * places: 两个安装点的放件下沉参数 (Java 的 BOLT_PLACE_* / STOCK_PLACE_*), {bolt|stock: {code, minX, maxX, minZ, maxZ, bottomY}};
 * 旧版 Java 没有这些常量时为 null。geo.maxPlaceDrop / geo.placeClearance 即 MAX_PLACE_DROP / PLACE_CLEARANCE。
 */
export function parseArmProgram(src) {
    const num = (s) => parseFloat(String(s).replace(/[FfDd]/g, ''));
    const c = {};
    for (const m of src.matchAll(/static final (?:float|int) (\w+)\s*=\s*(-?[\d.]+)[FfDd]?;/g)) c[m[1]] = num(m[2]);
    const block = /KEYFRAMES\s*=\s*\{([\s\S]*?)\n\s*\};/.exec(src);
    if (!block) throw new Error('arm program parse: KEYFRAMES not found');
    const rows = [];
    for (const m of block[1].matchAll(/\{([^{}]*)\},?[ \t]*(?:\/\/[ \t]*([^\n]*))?/g)) {
        const v = m[1].split(',').map(num);
        rows.push({ tick: v[0], yaw: v[1], upper: v[2], fore: v[3], spin: v[4], claw: v[5], payload: v[6], spark: v[7] !== 0, linear: v[8] || 0, station: v[9] || 0, label: (m[2] || '').trim() });
    }
    if (rows.length < 2) throw new Error('arm program parse: fewer than two keyframes');
    const payloadParts = {};
    for (const [k, v] of Object.entries(c)) if (k.startsWith('PAYLOAD_') && k !== 'PAYLOAD_NONE') payloadParts[v] = 'payload_' + k.slice(8).toLowerCase();
    let places = null;
    if (c.MAX_PLACE_DROP !== undefined) {
        places = {};
        for (const [key, code] of [['bolt', ARM_STATION_BOLT], ['stock', ARM_STATION_STOCK]]) {
            const p = key.toUpperCase() + '_PLACE_';
            places[key] = { code, minX: c[p + 'MIN_X'], maxX: c[p + 'MAX_X'], minZ: c[p + 'MIN_Z'], maxZ: c[p + 'MAX_Z'], bottomY: c[p + 'BOTTOM_Y'] };
            if (Object.values(places[key]).some((v) => v === undefined)) throw new Error(`arm program parse: ${p}* constants incomplete`);
        }
    }
    return {
        geo: {
            L1: c.UPPER_ARM_LENGTH, L2: c.FOREARM_LENGTH, jointUp: c.JOINT_UP, pivot: [c.PIVOT_X, c.PIVOT_Y, c.PIVOT_Z], tipDrop: c.TIP_DROP,
            maxPlaceDrop: c.MAX_PLACE_DROP, placeClearance: c.PLACE_CLEARANCE,
        },
        rows, payloadParts, places,
    };
}

/** 平面两连杆反解 (肘朝上), 与 GunsmithArmProgram.solveCylindrical 同一算法。dx = -水平伸出, dy = 相对大臂关节的高度。 */
export function planarIk(L1, L2, dx, dy) {
    const D = Math.hypot(dx, dy);
    if (D > L1 + L2 - 0.05 || D < Math.abs(L1 - L2) + 0.05) return null;
    const a = (L1 * L1 - L2 * L2 + D * D) / (2 * D);
    const hh = Math.sqrt(Math.max(0, L1 * L1 - a * a));
    const ux = dx / D, uy = dy / D;
    const c1 = [a * ux - hh * uy, a * uy + hh * ux], c2 = [a * ux + hh * uy, a * uy - hh * ux];
    const E = c1[1] > c2[1] ? c1 : c2;
    const u = Math.atan2(E[0], E[1]);
    let f = Math.atan2(E[0] - dx, E[1] - dy) - u;
    while (f > Math.PI) f -= 2 * Math.PI;
    while (f <= -Math.PI) f += 2 * Math.PI;
    return { u, f };
}

/** 关键帧表 station 列的取值 (与 GunsmithArmProgram.STATION_* 相同): 0 不下沉, 1 枪机安装点, 2 枪托件安装点。 */
export const ARM_STATION_BOLT = 1;
export const ARM_STATION_STOCK = 2;

/**
 * 某一行按所在安装点应下沉多少 (与 GunsmithArmProgram.stationDrop 相同): 不在安装点的行为 0;
 * 调用方给的下沉量再夹到 [0, MAX_PLACE_DROP] (非正、NaN → 0), 不会沉到生成器扫描没验证过的深度。
 */
function armStationDrop(row, boltDrop, stockDrop, maxDrop) {
    const drop = row.station === ARM_STATION_BOLT ? boltDrop : row.station === ARM_STATION_STOCK ? stockDrop : 0;
    return drop > 0 ? Math.min(drop, maxDrop) : 0;
}

/**
 * 与 GunsmithArmProgram.sample 逐行对应: 超过一轮取模; 连续量 smoothstep 插值; 携带件/火花取到达的那一行;
 * linear != 0 的段落在柱坐标里插值 (偏航、水平伸出、高度各自缓动) 并反解大臂/小臂角:
 * linear = 1 偏航不变 → 竖直直线 (MoveL); linear = 2 高度不变 → 在安全高度平移。program: {geo: {L1, L2, maxPlaceDrop}, rows}。
 * 放件下沉: station 列非 0 的行 (安装点的低位行) 的腕部高度按该安装点的下沉量 (boltDrop / stockDrop, 像素) 降低;
 * 两端各按自己的下沉量降低后再插值、反解, 所以 linear = 0 的行只要带下沉 (停顿、点焊、松开) 也走这条反解, 停在降低后的姿态。
 * 下沉量都为 0 时与不带下沉的旧版逐位相同。ikMiss: 该时刻反解不可达、退回了关节角插值 (生成器要求全程为 false)。
 */
export function sampleArmProgram(program, tick, boltDrop = 0, stockDrop = 0) {
    const rows = program.rows;
    const T = rows[rows.length - 1].tick;
    let t = tick % T;
    if (t < 0) t += T;
    if (t <= 0) return { ...rows[0], seg: 0, ikMiss: false };
    let i = 1;
    while (rows[i].tick < t) i++;
    const a = rows[i - 1], b = rows[i];
    const s = (t - a.tick) / (b.tick - a.tick);
    const e = s * s * (3 - 2 * s);
    const L = (k) => a[k] + (b[k] - a[k]) * e;
    let upper = L('upper'), fore = L('fore'), ikMiss = false;
    const maxDrop = program.geo.maxPlaceDrop === undefined ? Infinity : program.geo.maxPlaceDrop;
    const dropA = armStationDrop(a, boltDrop, stockDrop, maxDrop);
    const dropB = armStationDrop(b, boltDrop, stockDrop, maxDrop);
    if (b.linear || dropA !== 0 || dropB !== 0) {
        const { L1, L2 } = program.geo;
        const reach = (k) => -(L1 * Math.sin(k.upper) - L2 * Math.sin(k.upper + k.fore));
        const height = (k) => L1 * Math.cos(k.upper) - L2 * Math.cos(k.upper + k.fore);
        const heightA = height(a) - dropA, heightB = height(b) - dropB;
        const k = planarIk(L1, L2, -(reach(a) + (reach(b) - reach(a)) * e), heightA + (heightB - heightA) * e);
        if (k) { upper = k.u; fore = k.f; } else ikMiss = true;
    }
    return { tick: t, yaw: L('yaw'), upper, fore, spin: L('spin'), claw: L('claw'), payload: b.payload, spark: b.spark, linear: b.linear, station: b.station || 0, label: b.label, seg: i, ikMiss };
}

/** 零件底面中心 (= 点焊接触点), 朝北整台像素。与 GunsmithArmProgram.contactPosition 相同。 */
export function armProgramContact(geo, k) {
    const reach = geo.L1 * Math.sin(k.upper) - geo.L2 * Math.sin(k.upper + k.fore);
    const drop = -geo.jointUp - geo.L1 * Math.cos(k.upper) + geo.L2 * Math.cos(k.upper + k.fore);
    return [geo.pivot[0] + Math.cos(k.yaw) * reach, geo.pivot[1] - drop - geo.tipDrop, geo.pivot[2] - Math.sin(k.yaw) * reach];
}

/**
 * 程序时间 tick (0..160) 的完整状态: bakeArm 用的姿态覆盖 (含携带件 visible)、火花、动作名、接触点。
 * drops: [枪机安装点, 枪托件安装点] 的放件下沉量 (px, 见 sampleArmProgram), 省略 = 不下沉。
 */
export function armProgramState(arm, tick, drops = null) {
    const k = sampleArmProgram(arm.program, tick, drops ? drops[0] : 0, drops ? drops[1] : 0);
    const pose = {
        shoulder: { yRot: k.yaw }, upper_arm: { zRot: k.upper }, forearm: { zRot: k.fore },
        wrist: { zRot: -(k.upper + k.fore) }, tool: { yRot: k.spin },
        left_claw: { zRot: k.claw }, right_claw: { zRot: -k.claw },
    };
    for (const [code, part] of Object.entries(arm.program.payloadParts)) pose[part] = { visible: Number(code) === k.payload };
    return { pose, spark: k.spark, label: k.label, payload: k.payload, contact: armProgramContact(arm.program.geo, k) };
}

export function armProgramPose(arm, tick, drops = null) {
    return armProgramState(arm, tick, drops).pose;
}

const SPARK_COLORS = [[255, 250, 214], [255, 226, 122], [255, 186, 70]].map((c) => ({ width: 1, height: 1, data: new Uint8ClampedArray([...c, 255]) }));
/** 点焊火花的近似: 接触点附近几颗自发光小方块, 位置按整 tick 变化 (与游戏里每 tick 一簇粒子同节奏)。 */
export function sparkQuads(contact, tick) {
    const quads = [];
    let seed = (Math.floor(tick) * 2654435761) >>> 0;
    const rnd = () => { seed = (seed * 1664525 + 1013904223) >>> 0; return seed / 4294967296; };
    for (let i = 0; i < 6; i++) {
        const c = [contact[0] + (rnd() - 0.5) * 2.2, contact[1] + rnd() * 1.6, contact[2] + (rnd() - 0.5) * 2.2];
        const r = i === 0 ? 0.35 : 0.2;
        const from = [c[0] - r, c[1] - r, c[2] - r], to = [c[0] + r, c[1] + r, c[2] + r];
        const image = SPARK_COLORS[i % SPARK_COLORS.length];
        for (const face of FACE_NAMES) {
            quads.push({ pts: faceCorners(face, from, to), uvs: [[0, 0], [1, 0], [1, 1], [0, 1]], normal: FACE_NORMALS[face], image, uvScale: 1, cull: false, shade: 1, light: 15, tag: 'spark' });
        }
    }
    return quads;
}

/**
 * 旧版动作 (只为改前/改后对比保留): 与 HEAD 版 GunsmithAssemblyBenchRenderer.applyPose 相同的姿态; work = 0 为待机, 1 为工作峰值。
 * 新版 Java 里没有这些常量时退回关键帧程序, work 映射到程序时间。
 */
export function armPose(arm, work) {
    const k = arm.consts;
    if (k.IDLE_UPPER_ARM_Z === undefined && arm.program) return armProgramPose(arm, work * arm.program.rows[arm.program.rows.length - 1].tick);
    const lerp = (a, b, t) => a + (b - a) * t;
    const up = lerp(k.IDLE_UPPER_ARM_Z, k.WORK_UPPER_ARM_Z, work);
    const fore = lerp(k.IDLE_FOREARM_Z, k.WORK_FOREARM_Z, work);
    return {
        shoulder: { yRot: (k.WORK_BASE_YAW || 0) * work },
        upper_arm: { zRot: up },
        forearm: { zRot: fore },
        wrist: { zRot: -(up + fore) },
        left_claw: { zRot: lerp(-0.26, -0.08, work) },
        right_claw: { zRot: lerp(0.26, 0.08, work) },
    };
}

/**
 * 旧版动作 (只为改前/改后对比保留): 按 HEAD 版 Java applyPose 的时间线取姿态 (phase 0..1, 一个 80 tick 的 ASSEMBLY_CYCLE)。
 * 转向 / 伸出 / 夹紧 / 焊接各有自己的时间窗 (motionWindow), 焊接时手腕按 12 次正弦来回抖。
 * 时间窗优先从 Java 源里解析 (arm.windows), 解析不到用 HEAD 版的默认值。
 */
export function armPhasePose(arm, phase) {
    const k = arm.consts;
    const W = arm.windows || { turn: [0.04, 0.26, 0.72, 0.94], reach: [0.12, 0.32, 0.68, 0.88], grip: [0.28, 0.38, 0.62, 0.72], weld: [0.40, 0.46, 0.58, 0.64] };
    const eased = (p, a, b) => { const t = Math.min(1, Math.max(0, (p - a) / (b - a))); return t * t * (3 - 2 * t); };
    const mw = (w) => eased(phase, w[0], w[1]) * (1 - eased(phase, w[2], w[3]));
    const lerp = (a, b, t) => a + (b - a) * t;
    const turn = mw(W.turn), reach = mw(W.reach), grip = mw(W.grip);
    const pulse = Math.sin(phase * Math.PI * 2 * 12) * mw(W.weld);
    const up = lerp(k.IDLE_UPPER_ARM_Z, k.WORK_UPPER_ARM_Z, reach);
    const fore = lerp(k.IDLE_FOREARM_Z, k.WORK_FOREARM_Z, reach);
    return {
        shoulder: { yRot: (k.WORK_BASE_YAW || 0) * turn },
        upper_arm: { zRot: up },
        forearm: { zRot: fore },
        wrist: { zRot: -(up + fore), yRot: pulse * 0.07 },
        tool: { yRot: -pulse * 0.11 },
        left_claw: { zRot: lerp(-0.26, -0.08, grip) },
        right_claw: { zRot: lerp(0.26, 0.08, grip) },
    };
}

// ---------- 光栅化 ----------

function shadeFor(n) {
    // 原版 / Forge 对任意法线的方块明暗近似: 上 1.0, 下 0.5, 南北 0.8, 东西 0.6。
    const [x, y, z] = n;
    const up = y > 0 ? y * y : 0;
    const down = y < 0 ? y * y : 0;
    return Math.min(1, x * x * 0.6 + z * z * 0.8 + up * 1.0 + down * 0.5);
}

function entityShade(n) {
    // 实体光照近似: 两盏固定方向光 + 环境光 (Lighting.setupLevel)。
    const l0 = [0.2, 1.0, -0.7];
    const l1 = [-0.2, 1.0, 0.7];
    const norm = (v) => { const s = Math.hypot(...v); return v.map((x) => x / s); };
    const a = norm(l0), b = norm(l1);
    const d0 = Math.max(0, n[0] * a[0] + n[1] * a[1] + n[2] * a[2]);
    const d1 = Math.max(0, n[0] * b[0] + n[1] * b[1] + n[2] * b[2]);
    return Math.min(1, 0.4 + 0.6 * (d0 + d1));
}

export function cameraFromDir(dir) {
    const cl = Math.hypot(...dir);
    const c = dir.map((x) => x / cl);
    const f = c.map((x) => -x);
    let right = [f[1] * 0 - f[2] * 1, f[2] * 0 - f[0] * 0, f[0] * 1 - f[1] * 0];
    let rl = Math.hypot(...right);
    if (rl < 1e-6) { right = [1, 0, 0]; rl = 1; }
    right = right.map((x) => x / rl);
    const up = [right[1] * f[2] - right[2] * f[1], right[2] * f[0] - right[0] * f[2], right[0] * f[1] - right[1] * f[0]];
    return { c, f, right, up };
}

export function cameraFromAngles(yawDeg, pitchDeg) {
    // yaw 0 = 从北面 (-z) 看模型正面; 正 yaw 往西侧绕。
    const y = yawDeg * Math.PI / 180, p = pitchDeg * Math.PI / 180;
    return cameraFromDir([-Math.sin(y) * Math.cos(p), Math.sin(p), -Math.cos(y) * Math.cos(p)]);
}

export function projectBounds(quads, cam) {
    let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
    for (const q of quads) for (const p of q.pts) {
        const sx = p[0] * cam.right[0] + p[1] * cam.right[1] + p[2] * cam.right[2];
        const sy = -(p[0] * cam.up[0] + p[1] * cam.up[1] + p[2] * cam.up[2]);
        if (sx < minX) minX = sx; if (sx > maxX) maxX = sx;
        if (sy < minY) minY = sy; if (sy > maxY) maxY = sy;
    }
    return { minX, maxX, minY, maxY };
}

/**
 * 正交光栅化到 RGBA 缓冲。
 * @param target {width, height, data:Uint8ClampedArray, depth?:Float32Array}
 * @param view {cam, scale, cx, cy} 屏幕像素 = scale * 模型像素 + (cx, cy)
 */
export function rasterize(quads, target, view, opts = {}) {
    const { width, height, data } = target;
    const depth = target.depth || (target.depth = new Float32Array(width * height).fill(Infinity));
    const { cam, scale, cx, cy } = view;
    const brightness = view.brightness != null ? view.brightness : (opts.brightness != null ? opts.brightness : 1);
    for (const q of quads) {
        const facing = q.normal[0] * cam.c[0] + q.normal[1] * cam.c[1] + q.normal[2] * cam.c[2];
        if (q.cull && facing <= 1e-6) continue;
        const diffuse = q.shade != null ? q.shade : (q.entity ? entityShade(facing < 0 ? q.normal.map((x) => -x) : q.normal) : shadeFor(q.normal));
        // brightness 模拟环境光 (1 = 白天, 约 0.2 = 夜间); 自发光面按其 block_light 提亮。
        const lit = Math.max(brightness, (q.light || 0) / 15);
        const shade = diffuse * lit;
        const sp = q.pts.map((p) => [
            (p[0] * cam.right[0] + p[1] * cam.right[1] + p[2] * cam.right[2]) * scale + cx,
            -(p[0] * cam.up[0] + p[1] * cam.up[1] + p[2] * cam.up[2]) * scale + cy,
            p[0] * cam.f[0] + p[1] * cam.f[1] + p[2] * cam.f[2],
        ]);
        const img = q.image;
        const us = img.width / q.uvScale;
        const vs = img.height / (q.uvScaleV || q.uvScale);
        // 采样限制在该面的 uv 矩形内, 防止边缘像素因插值误差取到相邻贴图区域。
        const uLo = Math.min(...q.uvs.map((t) => t[0])) * us;
        const uHi = Math.max(uLo, Math.max(...q.uvs.map((t) => t[0])) * us - 1e-3);
        const vLo = Math.min(...q.uvs.map((t) => t[1])) * vs;
        const vHi = Math.max(vLo, Math.max(...q.uvs.map((t) => t[1])) * vs - 1e-3);
        for (const [a, b, c] of [[0, 1, 2], [0, 2, 3]]) {
            const A = sp[a], B = sp[b], C = sp[c];
            const area = (B[0] - A[0]) * (C[1] - A[1]) - (B[1] - A[1]) * (C[0] - A[0]);
            if (Math.abs(area) < 1e-9) continue;
            const minX = Math.max(0, Math.floor(Math.min(A[0], B[0], C[0])));
            const maxX = Math.min(width - 1, Math.ceil(Math.max(A[0], B[0], C[0])));
            const minY = Math.max(0, Math.floor(Math.min(A[1], B[1], C[1])));
            const maxY = Math.min(height - 1, Math.ceil(Math.max(A[1], B[1], C[1])));
            const ua = q.uvs[a], ub = q.uvs[b], uc = q.uvs[c];
            for (let y = minY; y <= maxY; y++) {
                const py = y + 0.5;
                for (let x = minX; x <= maxX; x++) {
                    const px = x + 0.5;
                    const w0 = ((B[0] - px) * (C[1] - py) - (B[1] - py) * (C[0] - px)) / area;
                    const w1 = ((C[0] - px) * (A[1] - py) - (C[1] - py) * (A[0] - px)) / area;
                    const w2 = 1 - w0 - w1;
                    if (w0 < -1e-6 || w1 < -1e-6 || w2 < -1e-6) continue;
                    const z = w0 * A[2] + w1 * B[2] + w2 * C[2] - (q.entity ? 0.002 : 0);
                    const idx = y * width + x;
                    if (z >= depth[idx]) continue;
                    const u = Math.min(uHi, Math.max(uLo, (w0 * ua[0] + w1 * ub[0] + w2 * uc[0]) * us));
                    const v = Math.min(vHi, Math.max(vLo, (w0 * ua[1] + w1 * ub[1] + w2 * uc[1]) * vs));
                    let tx = Math.floor(u);
                    let ty = Math.floor(v);
                    tx = Math.min(img.width - 1, Math.max(0, tx));
                    ty = Math.min(img.height - 1, Math.max(0, ty));
                    const ti = (ty * img.width + tx) * 4;
                    if (img.data[ti + 3] < 128) continue;
                    depth[idx] = z;
                    const o = idx * 4;
                    data[o] = img.data[ti] * shade;
                    data[o + 1] = img.data[ti + 1] * shade;
                    data[o + 2] = img.data[ti + 2] * shade;
                    data[o + 3] = 255;
                }
            }
        }
    }
}

/** 地面方块网格 (y = 0 平面, 覆盖 [x0,x1)×[z0,z1) 方块)。 */
export function groundQuads(x0, x1, z0, z1, image) {
    const quads = [];
    for (let bx = x0; bx < x1; bx++) for (let bz = z0; bz < z1; bz++) {
        const X = bx * 16, Z = bz * 16;
        quads.push({
            pts: [[X, -0.01, Z], [X + 16, -0.01, Z], [X + 16, -0.01, Z + 16], [X, -0.01, Z + 16]],
            uvs: [[0, 0], [16, 0], [16, 16], [0, 16]], normal: [0, 1, 0], image, uvScale: 16, cull: true, shade: 0.9,
        });
    }
    return quads;
}

export function fillBackground(target, rgb) {
    const { data } = target;
    for (let i = 0; i < data.length; i += 4) {
        data[i] = rgb[0]; data[i + 1] = rgb[1]; data[i + 2] = rgb[2]; data[i + 3] = 255;
    }
    if (target.depth) target.depth.fill(Infinity);
}

// ---------- 台上的 TACZ 枪 (Bedrock geo) ----------

/** TACZ 1.1.8 在非第一人称 (FIXED) 下不画的节点, 整棵子树都不画: 两只手 (只在第一人称画) 与默认状态下隐藏的部件。 */
export const TACZ_HAND_BONES = ['lefthand_pos', 'righthand_pos'];
export const TACZ_HIDDEN_BONES = ['muzzle_flash', 'bullet_in_barrel', 'bullet_in_mag', 'bullet_chain', 'mount', 'sight_folded',
    'mag_extended_1', 'mag_extended_2', 'mag_extended_3', 'handguard_tactical', 'additional_magazine', 'laser_beam'];
/** 配件转接节点: BedrockGunModel.attachmentAdapterNodeRender 只显示枪上配件点名的子节点, 裸枪一个都不显示。 */
export const TACZ_ADAPTER_BONE = 'attachment_adapter';
/** 枪口参考点: 按顺序取第一个存在的节点。 */
export const TACZ_MUZZLE_NODES = ['muzzle_pos', 'muzzle_flash', 'muzzle_default'];

/**
 * 裸枪显示 stack (台上的枪不带配件) 时 TACZ 1.1.8 画不画这个方块 —— 包围盒、枪口侧判定用的 B 与安装点枪顶都只算画出来的方块。
 * 纯结构判定, 不读运行时的 visible (与 Java GunsmithBenchGunRenderer 同一条规则, 改一边必须改另一边)。不画:
 *   (a) TACZ_HAND_BONES 的整棵子树; (b) TACZ_HIDDEN_BONES 的整棵子树;
 *   (c) attachment_adapter 下: 它的每个具名子节点的整棵子树 (裸枪的 adapterToRender 为空, BedrockGunModel.java:141-155, 244, 265-267),
 *       以及它自己带 rotation 的方块 (新格式加载时包成无名子节点, 无名子节点一律不显示; 旧格式不包, 照画)。
 *   attachment_adapter 自己不带 rotation 的方块照画。
 * @param anc [方块所在骨骼, 父, ..., 根] 的骨骼名
 * @param cube geo 里的方块 JSON; legacy: 旧格式 (1.10.0) 模型
 */
export function taczBareDrawn(anc, cube, legacy) {
    if (anc.some((n) => TACZ_HAND_BONES.includes(n) || TACZ_HIDDEN_BONES.includes(n))) return false;
    const i = anc.indexOf(TACZ_ADAPTER_BONE);
    if (i < 0) return true;
    if (i > 0) return false;
    return legacy || !cube.rotation;
}

/**
 * 安装点 s 下方的枪顶 (与 GunsmithBenchGunLayout 同一规则): 台上画出来的方块里, 台上 AABB 与安装点底面范围严格重叠
 * (aabb.maxX > MIN_X && aabb.minX < MAX_X && aabb.maxZ > MIN_Z && aabb.minZ < MAX_Z) 的那些的 AABB 最大 y; 没有重叠的方块时取 topY (= TOP_Y)。
 * @param aabbs [{lo, hi}] 每个画出来的方块的台上 AABB (bakeBedrockGeo 的 cubes[].aabb: 原始角点, 不含 inflate)
 * @param place 安装点 {minX, maxX, minZ, maxZ} (parseArmProgram 的 places.bolt / places.stock)
 */
export function gunStationTop(aabbs, place, topY) {
    let top = -Infinity;
    for (const b of aabbs) {
        if (b.hi[0] > place.minX && b.lo[0] < place.maxX && b.hi[2] > place.minZ && b.lo[2] < place.maxZ) top = Math.max(top, b.hi[1]);
    }
    return top === -Infinity ? topY : top;
}

/** 放件下沉量 (与 GunsmithBenchGunLayout.placeDrop 相同): clamp(bottomY - gunTop - PLACE_CLEARANCE, 0, MAX_PLACE_DROP), NaN 按 0。geo: parseArmProgram 的 geo。 */
export function placeDrop(bottomY, gunTop, geo) {
    const d = bottomY - gunTop - geo.placeClearance;
    return d > 0 ? Math.min(d, geo.maxPlaceDrop) : 0;
}

/**
 * 台上这把枪的两个安装点下沉量 (机械臂 sample 的 boltDrop / stockDrop)。gun: bakeBedrockGeo (frame 'bench') 的结果;
 * null、不画 (layout 为 null) 或没有画出来的方块 = 台上没枪 (空台、加载中、失败、没装 TaCZ), 两处都取 MAX_PLACE_DROP。
 * 返回 {drops: [枪机, 枪托件], tops: [枪机, 枪托件] (台上没枪时为 null)}; 关键帧表没有下沉参数 (旧版 Java) 时返回 null。
 */
export function benchGunPlaceDrops(program, gun, bed) {
    if (!program || !program.places) return null;
    const g = program.geo;
    if (!gun || !gun.layout || !gun.cubes || !gun.cubes.length) return { drops: [g.maxPlaceDrop, g.maxPlaceDrop], tops: [null, null] };
    const aabbs = gun.cubes.map((c) => c.aabb);
    const tops = [program.places.bolt, program.places.stock].map((p) => gunStationTop(aabbs, p, bed.TOP_Y));
    return { drops: [placeDrop(program.places.bolt.bottomY, tops[0], g), placeDrop(program.places.stock.bottomY, tops[1], g)], tops };
}

/**
 * 台上的枪的摆放规则 (POLICY)。与 Java com.miningdim.job.munitions.block.GunsmithBenchGunLayout 逐步对应, 改一边必须改另一边。
 * @param B 枪在 TACZ FIXED 定位系里画出来的方块 (taczBareDrawn) 的包围盒 {min:[x,y,z], max:[x,y,z]} (未缩放 px; 枪口 -X, 枪顶 +Y, 枪的左侧 +Z)
 * @param z0 枪口参考点 (TACZ_MUZZLE_NODES 中第一个存在的) 在同一坐标系里的 Z; 没有这些节点时传 null
 * @param bed 枪床常量 (生成器的 GUN_BED, 即 GunsmithGunBed.java 的同一组数值)
 * @returns null = 不画; 否则 {L, T, k, leftSideUp, R (3x3 行主序), t, benchMin, benchMax}, 台上坐标 bench = t + k * R * q
 */
export function layoutGunOnBed(B, z0, bed) {
    // 1. 长度 L; 非正或非有限值 → 不画
    const L = B.max[0] - B.min[0];
    if (!(L > 0) || !Number.isFinite(L)) return null;
    // 2. 渲染长度 T = min(MAX_LENGTH, SIZE_A * L^SIZE_P), k = T / L; 剖面高度 k * (B.maxY - B.minY) 超过 2 * HALF_WIDTH 时再压到正好
    const T = Math.min(bed.MAX_LENGTH, bed.SIZE_A * Math.pow(L, bed.SIZE_P));
    let k = T / L;
    if (k * (B.max[1] - B.min[1]) > 2 * bed.HALF_WIDTH) k = 2 * bed.HALF_WIDTH / (B.max[1] - B.min[1]);
    // 3. 朝上的一侧: 没有枪口节点时 z0 取 B 的 z 中心 (两侧厚度差为 0 → 右侧朝上)
    const zRef = z0 === null || z0 === undefined ? (B.min[2] + B.max[2]) / 2 : z0;
    const leftSideUp = (B.max[2] - zRef) - (zRef - B.min[2]) > bed.SIDE_UP_THRESHOLD;
    // 4. 定位系 → 台上坐标的旋转 R: 右侧朝上 (X, Y, Z) → (X, -Z, Y) (枪顶朝 +z 即机械臂, 握把/弹匣朝玩家); 左侧朝上 (X, Y, Z) → (X, Z, -Y)
    const R = leftSideUp ? [1, 0, 0, 0, 0, 1, 0, -1, 0] : [1, 0, 0, 0, 0, -1, 0, 1, 0];
    // 5. 平移 t: 变换后的包围盒最大 x = BUTT_X、最小 y = TOP_Y、z 中心 = AXIS_Z
    const lo = [Infinity, Infinity, Infinity], hi = [-Infinity, -Infinity, -Infinity];
    for (const x of [B.min[0], B.max[0]]) for (const y of [B.min[1], B.max[1]]) for (const z of [B.min[2], B.max[2]]) {
        for (let a = 0; a < 3; a++) {
            const v = k * (R[a * 3] * x + R[a * 3 + 1] * y + R[a * 3 + 2] * z);
            lo[a] = Math.min(lo[a], v);
            hi[a] = Math.max(hi[a], v);
        }
    }
    const t = [bed.BUTT_X - hi[0], bed.TOP_Y - lo[1], bed.AXIS_Z - (lo[2] + hi[2]) / 2];
    return { L, T, k, leftSideUp, R, t, benchMin: lo.map((v, a) => v + t[a]), benchMax: hi.map((v, a) => v + t[a]) };
}

function mInvAffine(m) {
    const [a, b, c, , d, e, f, , g, h, i] = m;
    const det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
    if (Math.abs(det) < 1e-12) throw new Error('singular matrix');
    const r = [
        (e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det, 0,
        (f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det, 0,
        (d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det, 0,
        0, 0, 0, 1,
    ];
    r[3] = -(r[0] * m[3] + r[1] * m[7] + r[2] * m[11]);
    r[7] = -(r[4] * m[3] + r[5] * m[7] + r[6] * m[11]);
    r[11] = -(r[8] * m[3] + r[9] * m[7] + r[10] * m[11]);
    return r;
}

/** BedrockVersion: format_version 次版本 >= 12 走新格式 (minecraft:geometry), "1.10.0" 走旧格式 (geometry.model), 其它 TACZ 不加载。 */
function readGeo(json) {
    const ver = String(json.format_version || '');
    const v = ver.split('.');
    if (v.length === 3 && Number(v[1]) >= 12) {
        const g = json['minecraft:geometry'] && json['minecraft:geometry'][0];
        if (!g || !g.description) throw new Error(`geo ${ver}: no minecraft:geometry[0].description`);
        return { legacy: false, bones: g.bones || [], texW: g.description.texture_width, texH: g.description.texture_height };
    }
    if (ver === '1.10.0') {
        const g = json['geometry.model'];
        if (!g) throw new Error('geo 1.10.0: no geometry.model');
        return { legacy: true, bones: g.bones || [], texW: g.texturewidth, texH: g.textureheight };
    }
    throw new Error(`geo format_version "${ver}" is neither new (1.12+) nor legacy (1.10.0): TACZ does not load it`);
}

// BedrockPolygon: 顶点 0..3 依次取 (u2,v1) (u1,v1) (u1,v2) (u2,v2); 镜像时顶点顺序整体反转。
function bedrockPolygon(verts, u1, v1, u2, v2, mirror) {
    const uvs = [[u2, v1], [u1, v1], [u1, v2], [u2, v2]];
    const vs = verts.map((p, i) => ({ p, uv: uvs[i] }));
    return mirror ? vs.reverse() : vs;
}

/**
 * 按 TACZ 1.1.8 BedrockModel 的加载约定, 把一个 Bedrock geo 枪模烘成光栅四边形 (Node 与浏览器共用)。
 * 加载约定: 根骨骼位置 (px, 24 - py, pz), 子骨骼相对父骨骼 (px - ppx, ppy - py, pz - ppz); 变换 T · Rz · Ry · Rx, 角度按原值只换成弧度;
 * 方块有 rotation 时包一层以方块 pivot 为原点的子节点; 盒式 UV (尺寸取整、mirror 交换 x 并反转顶点) 或逐面 UV
 * (east/west、up/down 与 JSON 里的键互换, 与 FaceUVsItem.getFace 相同); inflate 只放大几何; UV 按 geo 的 texture_width/height 计。
 * 然后是 TACZ FIXED 定位系 (scale(-1,-1,1) 再乘 fixed 骨骼链的逆: fixed 枢轴落在原点、它的旋转被抵消),
 * frame = 'bench' 时再按 layoutGunOnBed 摆到枪床上 (朝北整台局部像素)。
 * 画哪些方块: 裸枪显示 stack 下 TACZ 实际画的 (taczBareDrawn: 手、默认隐藏节点的子树与 attachment_adapter 的子节点不画);
 * 包围盒 B、摆放与安装点枪顶都只算这些方块 (与 GunsmithBenchGunLayout / GunsmithBenchGunRenderer 同一集合)。LOD 模型同一规则。
 * 名字以 _illuminated 结尾的骨骼 (含子树) 全亮, 与 BedrockPart 相同。
 * @param json 已解析的 geo JSON
 * @param image 贴图 {width, height, data} (可比 texture_width/height 大, 按比例采样); null = 只算几何, 不出四边形
 * @param opts {frame: 'bench' (默认) | 'fixed', bed: GUN_BED (frame = 'bench' 时必需), bbox: 'raw' (默认) | 'inflated', tag}
 *   bbox: 包围盒 B 取方块的原始边界 (BedrockCubeBox/PerFace 的 minX..maxZ 字段, 不含 inflate, Java 侧能读到的就是它) 还是含 inflate 的实际几何。
 * @returns {quads, cubes: [{m (方块局部 px → 输出坐标), lo, hi (含 inflate 的局部盒), inflate, aabb: {lo, hi} (输出坐标系的 AABB: 8 个原始角点
 *   (不含 inflate) 在 FIXED 定位系里的 AABB 经摆放变换后的像, 安装点枪顶用它), bone, illuminated}],
 *   bbox: {raw, inflated} (FIXED 定位系), B (layout 用的那个), z0, muzzleNode, layout (bench) | null, texW, texH, legacy,
 *   stats: {cubes (全部), hiddenCubes (手与默认隐藏子树), adapterCubes (attachment_adapter 下不画的), drawnCubes, quads}}
 */
export function bakeBedrockGeo(json, image, opts = {}) {
    const geo = readGeo(json);
    const frame = opts.frame || 'bench';
    if (frame !== 'bench' && frame !== 'fixed') throw new Error('bakeBedrockGeo: bad frame ' + frame);
    const bboxMode = opts.bbox || 'raw';
    if (bboxMode !== 'raw' && bboxMode !== 'inflated') throw new Error('bakeBedrockGeo: bad bbox mode ' + bboxMode);
    // 旧参数 adapterNodes: [] 就是现在唯一的画法 (裸枪), 照收; 点名转接件的画法已经去掉
    if (opts.adapterNodes !== undefined && !(Array.isArray(opts.adapterNodes) && !opts.adapterNodes.length)) {
        throw new Error('bakeBedrockGeo: adapterNodes was removed; the bench always draws the bare display stack (taczBareDrawn)');
    }
    if (!(geo.texW > 0) || !(geo.texH > 0)) throw new Error('geo: texture_width/height missing');
    const byName = new Map();
    for (const b of geo.bones) {
        if (!b.name) throw new Error('geo: bone without a name');
        // TACZ 用 putIfAbsent 按名字建部件, 同名骨骼会合并成一个部件并被挂到父节点两次; 预览不模拟这种情况
        if (byName.has(b.name)) throw new Error('geo: duplicate bone name ' + b.name);
        if (!Array.isArray(b.pivot)) throw new Error(`geo: bone ${b.name} has no pivot (TACZ fails to load such a model)`);
        byName.set(b.name, b);
    }
    for (const b of geo.bones) if (b.parent && !byName.has(b.parent)) throw new Error(`geo: bone ${b.name} has unknown parent ${b.parent}`);
    const DEG = Math.PI / 180;
    const rot = (r) => (r ? matMul(mRotZ(r[2] * DEG), matMul(mRotY(r[1] * DEG), mRotX(r[0] * DEG))) : IDENT);
    // 部件空间 (y 朝下, 单位 px) 的骨骼世界矩阵
    const world = new Map();
    const boneMatrix = (name, depth = 0) => {
        if (world.has(name)) return world.get(name);
        if (depth > 256) throw new Error('geo: bone parent loop at ' + name);
        const b = byName.get(name), p = b.pivot;
        let m;
        if (b.parent) {
            const pp = byName.get(b.parent).pivot;
            m = matMul(boneMatrix(b.parent, depth + 1), matMul(mTranslate(p[0] - pp[0], pp[1] - p[1], p[2] - pp[2]), rot(b.rotation)));
        } else {
            m = matMul(mTranslate(p[0], 24 - p[1], p[2]), rot(b.rotation));
        }
        world.set(name, m);
        return m;
    };
    const ancestors = (name) => {
        const out = [];
        for (let b = byName.get(name); b; b = b.parent ? byName.get(b.parent) : null) {
            if (out.length > 256) throw new Error('geo: bone parent loop at ' + name);
            out.push(b.name);
        }
        return out;
    };
    const hiddenSet = new Set([...TACZ_HAND_BONES, ...TACZ_HIDDEN_BONES]);

    // FIXED 定位系: renderStatic 的 -0.5、TACZ 的 (0.5, 2, 0.5) 与 scale(-1,-1,1)、定位节点逆变换合起来 = scale(-1,-1,1) · fixed 世界矩阵的逆
    // (没有 fixed 骨骼时 getPositioningNodeInverse 是单位阵, 化简后是 scale(-1,-1,1) · translate(0, -24, 0))
    const toFixed = matMul(mScale(-1, -1, 1), byName.has('fixed') ? mInvAffine(boneMatrix('fixed')) : mTranslate(0, -24, 0));

    // 方块: 局部盒 + 局部 → 部件空间矩阵 (只收裸枪显示 stack 下画出来的方块)
    const cubes = [];
    let total = 0, hiddenCubes = 0, adapterCubes = 0;
    for (const b of geo.bones) {
        if (!b.cubes || !b.cubes.length) continue;
        total += b.cubes.length;
        const anc = ancestors(b.name);
        if (anc.some((n) => hiddenSet.has(n))) { hiddenCubes += b.cubes.length; continue; }
        const illuminated = anc.some((n) => n.endsWith('_illuminated'));
        const M = boneMatrix(b.name), bp = b.pivot;
        for (const c of b.cubes) {
            if (!Array.isArray(c.origin) || !Array.isArray(c.size)) throw new Error(`geo: cube in ${b.name} without origin/size`);
            if (c.uv === undefined || c.uv === null) throw new Error(`geo: cube in ${b.name} without uv (TACZ fails to load such a model)`);
            if (!taczBareDrawn(anc, c, geo.legacy)) { adapterCubes++; continue; }
            const o = c.origin, s = c.size;
            let m, lo;
            if (!geo.legacy && c.rotation) {
                if (!Array.isArray(c.pivot)) throw new Error(`geo: rotated cube in ${b.name} without pivot`);
                const cp = c.pivot;
                m = matMul(M, matMul(mTranslate(cp[0] - bp[0], bp[1] - cp[1], cp[2] - bp[2]), rot(c.rotation)));
                lo = [o[0] - cp[0], cp[1] - o[1] - s[1], o[2] - cp[2]];
            } else {
                m = M;
                lo = [o[0] - bp[0], bp[1] - o[1] - s[1], o[2] - bp[2]];
            }
            if (geo.legacy && !Array.isArray(c.uv)) throw new Error(`geo 1.10.0: cube in ${b.name} has per-face uv (the legacy loader only reads box uv)`);
            cubes.push({ bone: b.name, illuminated, part: m, lo, size: s, inflate: c.inflate || 0,
                mirror: typeof c.mirror === 'boolean' ? c.mirror : !!b.mirror, uv: c.uv });
        }
    }

    // 包围盒 B (FIXED 定位系) 与枪口参考点; 每个方块另记原始角点 (不含 inflate) 的 FIXED 定位系 AABB
    const emptyBox = () => ({ min: [Infinity, Infinity, Infinity], max: [-Infinity, -Infinity, -Infinity] });
    const grow = (bb, p) => { for (let a = 0; a < 3; a++) { bb.min[a] = Math.min(bb.min[a], p[a]); bb.max[a] = Math.max(bb.max[a], p[a]); } };
    const bbox = { raw: emptyBox(), inflated: emptyBox() };
    for (const c of cubes) {
        const mf = matMul(toFixed, c.part);
        for (const [key, g] of [['raw', 0], ['inflated', c.inflate]]) {
            const own = emptyBox();
            for (const x of [c.lo[0] - g, c.lo[0] + c.size[0] + g]) for (const y of [c.lo[1] - g, c.lo[1] + c.size[1] + g]) for (const z of [c.lo[2] - g, c.lo[2] + c.size[2] + g]) {
                const p = apply(mf, [x, y, z]);
                grow(bbox[key], p);
                grow(own, p);
            }
            if (key === 'raw') c.fixedAabb = own;
        }
    }
    const muzzleNode = TACZ_MUZZLE_NODES.find((n) => byName.has(n)) || null;
    const z0 = muzzleNode ? apply(matMul(toFixed, boneMatrix(muzzleNode)), [0, 0, 0])[2] : null;
    const B = bbox[bboxMode];
    const stats = { cubes: total, hiddenCubes, adapterCubes, drawnCubes: cubes.length, quads: 0 };

    let out = toFixed, layout = null, place = null;
    if (frame === 'bench') {
        if (!opts.bed) throw new Error('bakeBedrockGeo: frame "bench" needs opts.bed (GUN_BED)');
        layout = cubes.length ? layoutGunOnBed(B, z0, opts.bed) : null;
        if (!layout) return { quads: [], cubes: [], bbox, B, z0, muzzleNode, layout: null, texW: geo.texW, texH: geo.texH, legacy: geo.legacy, stats: { ...stats, drawnCubes: 0 } };
        const { R, t, k } = layout;
        place = [k * R[0], k * R[1], k * R[2], t[0], k * R[3], k * R[4], k * R[5], t[1], k * R[6], k * R[7], k * R[8], t[2], 0, 0, 0, 1];
        out = matMul(place, toFixed);
    }
    // 方块 AABB 在输出坐标系里: FIXED 定位系 AABB 的 8 个角经摆放变换 (R 只换轴/取反, 所以像仍是精确的 AABB)
    const outAabb = (fa) => {
        if (!place) return { lo: fa.min.slice(), hi: fa.max.slice() };
        const bb = emptyBox();
        for (const x of [fa.min[0], fa.max[0]]) for (const y of [fa.min[1], fa.max[1]]) for (const z of [fa.min[2], fa.max[2]]) grow(bb, apply(place, [x, y, z]));
        return { lo: bb.min, hi: bb.max };
    };

    const quads = [];
    const outCubes = [];
    for (const c of cubes) {
        const m = matMul(out, c.part);
        const g = c.inflate;
        let x = c.lo[0] - g, y = c.lo[1] - g, z = c.lo[2] - g;
        let xE = c.lo[0] + c.size[0] + g;
        const yE = c.lo[1] + c.size[1] + g, zE = c.lo[2] + c.size[2] + g;
        outCubes.push({ m, lo: [x, y, z], hi: [xE, yE, zE], inflate: g, aabb: outAabb(c.fixedAabb), bone: c.bone, illuminated: c.illuminated });
        if (!image) continue;
        const box = Array.isArray(c.uv);
        const mirror = box && c.mirror;   // BedrockCubePerFace 不做镜像
        if (mirror) [x, xE] = [xE, x];
        const V = [null, [x, y, z], [xE, y, z], [xE, yE, z], [x, yE, z], [x, y, zE], [xE, y, zE], [xE, yE, zE], [x, yE, zE]];
        // [TACZ 方向, 顶点 (v1..v8 编号), 盒式 UV 矩形, 逐面 UV 用的 JSON 键]
        let faces;
        if (box) {
            const [u0, v0] = c.uv, dx = Math.trunc(c.size[0]), dy = Math.trunc(c.size[1]), dz = Math.trunc(c.size[2]);
            const p1 = u0 + dz, p2 = p1 + dx, p3 = p2 + dx, p4 = p2 + dz, p5 = p4 + dx, p6 = v0 + dz, p7 = p6 + dy;
            faces = [
                [[6, 5, 1, 2], [p1, v0, p2, p6]],   // DOWN
                [[3, 4, 8, 7], [p2, p6, p3, v0]],   // UP
                [[1, 5, 8, 4], [u0, p6, p1, p7]],   // WEST
                [[2, 1, 4, 3], [p1, p6, p2, p7]],   // NORTH
                [[6, 2, 3, 7], [p2, p6, p4, p7]],   // EAST
                [[5, 6, 7, 8], [p4, p6, p5, p7]],   // SOUTH
            ];
        } else {
            const rect = (key) => {
                const f = c.uv[key];
                if (!f) return null;   // FaceItem.EMPTY: 四个顶点都在原点, 不出面
                if (!Array.isArray(f.uv) || !Array.isArray(f.uv_size)) throw new Error(`geo: per-face uv "${key}" in ${c.bone} needs uv and uv_size`);
                return [f.uv[0], f.uv[1], f.uv[0] + f.uv_size[0], f.uv[1] + f.uv_size[1]];
            };
            faces = [[[6, 5, 1, 2], rect('up')], [[3, 4, 8, 7], rect('down')], [[1, 5, 8, 4], rect('east')], [[2, 1, 4, 3], rect('north')], [[6, 2, 3, 7], rect('west')], [[5, 6, 7, 8], rect('south')]];
        }
        for (const [idx, r] of faces) {
            if (!r) continue;
            const poly = bedrockPolygon(idx.map((i) => apply(m, V[i])), r[0], r[1], r[2], r[3], mirror);
            const pts = poly.map((v) => v.p);
            // 朝向取绕序 (游戏里剔除看的是屏幕绕序, 不看法线): 两条对角边的叉积, 零面积 (厚度为 0 的方块的侧面) 不出面
            const d1 = [pts[2][0] - pts[0][0], pts[2][1] - pts[0][1], pts[2][2] - pts[0][2]];
            const d2 = [pts[3][0] - pts[1][0], pts[3][1] - pts[1][1], pts[3][2] - pts[1][2]];
            const n = [d1[1] * d2[2] - d1[2] * d2[1], d1[2] * d2[0] - d1[0] * d2[2], d1[0] * d2[1] - d1[1] * d2[0]];
            const len = Math.hypot(n[0], n[1], n[2]);
            if (len < 1e-9) continue;
            quads.push({
                pts, uvs: poly.map((v) => v.uv), normal: n.map((q) => q / len), image, uvScale: geo.texW, uvScaleV: geo.texH,
                cull: true, shade: null, light: c.illuminated ? 15 : 0, entity: true, tag: opts.tag || 'gun',
            });
        }
    }
    return { quads, cubes: outCubes, bbox, B, z0, muzzleNode, layout, texW: geo.texW, texH: geo.texH, legacy: geo.legacy, stats: { ...stats, quads: quads.length } };
}

/** 物品栏图标: 按模型 display.gui 变换, 返回可直接光栅化的四边形 (中心在原点)。 */
export function guiTransform(quads, display) {
    const g = (display && display.gui) || { rotation: [30, 225, 0], translation: [0, 0, 0], scale: [0.625, 0.625, 0.625] };
    const rot = g.rotation || [0, 0, 0];
    const tr = g.translation || [0, 0, 0];
    const sc = g.scale || [1, 1, 1];
    const r = (d) => d * Math.PI / 180;
    // ItemTransform.apply: translate, rotate(XYZ 欧拉), scale; 模型先平移 -0.5 居中。
    let m = mTranslate(tr[0], tr[1], tr[2]);
    m = matMul(m, matMul(mRotX(r(rot[0])), matMul(mRotY(r(rot[1])), mRotZ(r(rot[2])))));
    m = matMul(m, mScale(sc[0], sc[1], sc[2]));
    m = matMul(m, mTranslate(-8, -8, -8));
    return quads.map((q) => {
        const pts = q.pts.map((p) => apply(m, p));
        let n = applyDir(m, q.normal);
        const l = Math.hypot(...n) || 1;
        n = n.map((x) => x / l);
        return { ...q, pts, normal: n };
    });
}
