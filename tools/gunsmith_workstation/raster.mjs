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
 * 返回 {geo, rows, payloadParts: {code: ModelPart 名}}。
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
        rows.push({ tick: v[0], yaw: v[1], upper: v[2], fore: v[3], spin: v[4], claw: v[5], payload: v[6], spark: v[7] !== 0, linear: v[8] || 0, label: (m[2] || '').trim() });
    }
    if (rows.length < 2) throw new Error('arm program parse: fewer than two keyframes');
    const payloadParts = {};
    for (const [k, v] of Object.entries(c)) if (k.startsWith('PAYLOAD_') && k !== 'PAYLOAD_NONE') payloadParts[v] = 'payload_' + k.slice(8).toLowerCase();
    return {
        geo: { L1: c.UPPER_ARM_LENGTH, L2: c.FOREARM_LENGTH, jointUp: c.JOINT_UP, pivot: [c.PIVOT_X, c.PIVOT_Y, c.PIVOT_Z], tipDrop: c.TIP_DROP },
        rows, payloadParts,
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

/**
 * 与 GunsmithArmProgram.sample 逐行对应: 超过一轮取模; 连续量 smoothstep 插值; 携带件/火花取到达的那一行;
 * linear != 0 的段落在柱坐标里插值 (偏航、水平伸出、高度各自缓动) 并反解大臂/小臂角:
 * linear = 1 偏航不变 → 竖直直线 (MoveL); linear = 2 高度不变 → 在安全高度平移。program: {geo: {L1, L2}, rows}。
 */
export function sampleArmProgram(program, tick) {
    const rows = program.rows;
    const T = rows[rows.length - 1].tick;
    let t = tick % T;
    if (t < 0) t += T;
    if (t <= 0) return { ...rows[0], seg: 0 };
    let i = 1;
    while (rows[i].tick < t) i++;
    const a = rows[i - 1], b = rows[i];
    const s = (t - a.tick) / (b.tick - a.tick);
    const e = s * s * (3 - 2 * s);
    const L = (k) => a[k] + (b[k] - a[k]) * e;
    let upper = L('upper'), fore = L('fore');
    if (b.linear) {
        const { L1, L2 } = program.geo;
        const reach = (k) => -(L1 * Math.sin(k.upper) - L2 * Math.sin(k.upper + k.fore));
        const height = (k) => L1 * Math.cos(k.upper) - L2 * Math.cos(k.upper + k.fore);
        const k = planarIk(L1, L2, -(reach(a) + (reach(b) - reach(a)) * e), height(a) + (height(b) - height(a)) * e);
        if (k) { upper = k.u; fore = k.f; }
    }
    return { tick: t, yaw: L('yaw'), upper, fore, spin: L('spin'), claw: L('claw'), payload: b.payload, spark: b.spark, linear: b.linear, label: b.label, seg: i };
}

/** 零件底面中心 (= 点焊接触点), 朝北整台像素。与 GunsmithArmProgram.contactPosition 相同。 */
export function armProgramContact(geo, k) {
    const reach = geo.L1 * Math.sin(k.upper) - geo.L2 * Math.sin(k.upper + k.fore);
    const drop = -geo.jointUp - geo.L1 * Math.cos(k.upper) + geo.L2 * Math.cos(k.upper + k.fore);
    return [geo.pivot[0] + Math.cos(k.yaw) * reach, geo.pivot[1] - drop - geo.tipDrop, geo.pivot[2] - Math.sin(k.yaw) * reach];
}

/** 程序时间 tick (0..160) 的完整状态: bakeArm 用的姿态覆盖 (含携带件 visible)、火花、动作名、接触点。 */
export function armProgramState(arm, tick) {
    const k = sampleArmProgram(arm.program, tick);
    const pose = {
        shoulder: { yRot: k.yaw }, upper_arm: { zRot: k.upper }, forearm: { zRot: k.fore },
        wrist: { zRot: -(k.upper + k.fore) }, tool: { yRot: k.spin },
        left_claw: { zRot: k.claw }, right_claw: { zRot: -k.claw },
    };
    for (const [code, part] of Object.entries(arm.program.payloadParts)) pose[part] = { visible: Number(code) === k.payload };
    return { pose, spark: k.spark, label: k.label, payload: k.payload, contact: armProgramContact(arm.program.geo, k) };
}

export function armProgramPose(arm, tick) {
    return armProgramState(arm, tick).pose;
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
