// 军火台运动件 (BlockEntityRenderer 画的 ModelPart) 的共用工具, 生成器与预览/对拍脚本都用它 (零依赖):
//   - 原版 1.20.1 ModelPart.Cube 的盒式 UV 与面顶点 (按 javap 反汇编逐条核对, 见 mcCubePolygons);
//   - 由场景里的运动件元素建 4 倍尺寸的 ModelPart 层、排运动件贴图、按候选方案的面贴图逐像素反推贴图;
//   - 写出 / 解析 MunitionsBenchParts.java (createBodyLayer + applyPose) 与 MunitionsBenchProgram.java 的关键帧表;
//   - MunitionsBenchProgram.sample / idle 与 MunitionsBenchParts.applyPose 的 JS 镜像 (必须与 Java 逐行一致, 见 README);
//   - 按渲染器的变换把运动件烘成 raster.mjs 的四边形。
// 坐标: 朝北整台局部像素, x 东、y 上、z 南, 原点在主格西北下角。ModelPart 用 4 倍单位、y 朝下:
//   世界像素 = (0.25 * (part.x + mx), -0.25 * (part.y + my), 0.25 * (part.z + mz))。
import { Canvas, BOTTOM, faceTexel, paintPlan, planSignature, r4, TIERS } from './core.mjs';

export const PART_SCALE = 0.25;
export const UNITS = 4;
/** ModelPart 面标签 (模型空间, y 朝下) → 渲染器 y 翻转之后的世界面。 */
export const WORLD_FACE = { DOWN: 'up', UP: 'down', NORTH: 'north', SOUTH: 'south', EAST: 'east', WEST: 'west' };
export const MC_DIR = Object.fromEntries(Object.entries(WORLD_FACE).map(([k, v]) => [v, k]));
const MC_ORDER = ['DOWN', 'UP', 'WEST', 'NORTH', 'EAST', 'SOUTH'];
const NORMALS = { north: [0, 0, -1], south: [0, 0, 1], east: [1, 0, 0], west: [-1, 0, 0], up: [0, 1, 0], down: [0, -1, 0] };

// ================================================================ 原版盒式 UV
/**
 * 与 1.20.1 ModelPart.Cube 构造函数逐条对应: 8 个顶点、6 个面 (标签是模型空间方向, y 朝下) 的顶点顺序与 uv 角点,
 * Polygon(verts, u1, v1, u2, v2) 把 verts[0..3] 映射到 (u2,v1) (u1,v1) (u1,v2) (u2,v2)。不支持 mirror / CubeDeformation。
 * cube: {u, v, x, y, z, w, h, d, faces: Set<'DOWN'|'UP'|...>}; 返回 [{dir, pts (模型坐标), uvs (贴图像素)}]。
 */
export function mcCubePolygons(c) {
    const x0 = c.x, y0 = c.y, z0 = c.z, x1 = c.x + c.w, y1 = c.y + c.h, z1 = c.z + c.d;
    const V = {
        v19: [x0, y0, z0], v20: [x1, y0, z0], v21: [x1, y1, z0], v22: [x0, y1, z0],
        v23: [x0, y0, z1], v24: [x1, y0, z1], v25: [x1, y1, z1], v26: [x0, y1, z1],
    };
    const { u, v, w, h, d } = c;
    const f27 = u, f28 = u + d, f29 = u + d + w, f30 = u + d + w + w, f31 = u + d + w + d, f32 = u + d + w + d + w;
    const f33 = v, f34 = v + d, f35 = v + d + h;
    const defs = {
        DOWN: [['v24', 'v23', 'v19', 'v20'], f28, f33, f29, f34],
        UP: [['v21', 'v22', 'v26', 'v25'], f29, f34, f30, f33],
        WEST: [['v19', 'v23', 'v26', 'v22'], f27, f34, f28, f35],
        NORTH: [['v20', 'v19', 'v22', 'v21'], f28, f34, f29, f35],
        EAST: [['v24', 'v20', 'v21', 'v25'], f29, f34, f31, f35],
        SOUTH: [['v23', 'v24', 'v25', 'v26'], f31, f34, f32, f35],
    };
    return MC_ORDER.filter((dir) => c.faces.has(dir)).map((dir) => {
        const [vs, u1, v1, u2, v2] = defs[dir];
        return { dir, pts: vs.map((k) => V[k]), uvs: [[u2, v1], [u1, v1], [u1, v2], [u2, v2]] };
    });
}
/** 盒式 UV 一个 cube 占的贴图块 (宽 2d + 2w, 高 d + h)。 */
export const boxBlock = (c) => ({ w: 2 * c.d + 2 * c.w, h: c.d + c.h });

// ================================================================ 由运动件元素建 ModelPart 层
/**
 * defs: [{name, pivot: [x,y,z] 静止位枢轴 (世界像素), els: [{name, from, to, plans: {世界面: planFace 结果}}], drive: {x, y, show}, fullBright}]
 * 元素已在静止位 (待机布局)。返回 {parts, texW, texH, blocks}; parts[i].cubes[j] = {u, v, x, y, z, w, h, d, faces: Set<MC 方向>, el, sig}。
 * 尺寸与各面外观完全相同的 cube 共用一块贴图。
 */
export function buildLayer(defs, errors) {
    const blocks = new Map();
    const parts = defs.map((def) => {
        const offset = [def.pivot[0] * UNITS, -def.pivot[1] * UNITS, def.pivot[2] * UNITS];
        const cubes = def.els.map((el) => {
            const size = [0, 1, 2].map((a) => (el.to[a] - el.from[a]) * UNITS);
            const pos = [(el.from[0] - def.pivot[0]) * UNITS, -(el.to[1] - def.pivot[1]) * UNITS, (el.from[2] - def.pivot[2]) * UNITS];
            for (const v of [...size, ...pos, ...offset]) if (Math.abs(v - Math.round(v)) > 1e-6) errors.push(`part ${def.name}/${el.name}: coordinate ${v / UNITS} px is off the 0.25 grid (4x units must be whole)`);
            const faces = new Set(Object.keys(el.plans).map((f) => MC_DIR[f]));
            const sig = JSON.stringify([size, MC_ORDER.map((dir) => (el.plans[WORLD_FACE[dir]] ? planSignature(el.plans[WORLD_FACE[dir]]) : null))]);
            if (!blocks.has(sig)) blocks.set(sig, { sig, size, cubes: [] });
            const c = { x: Math.round(pos[0]), y: Math.round(pos[1]), z: Math.round(pos[2]), w: Math.round(size[0]), h: Math.round(size[1]), d: Math.round(size[2]), faces, el, sig };
            blocks.get(sig).cubes.push(c);
            return c;
        });
        return { name: def.name, pivot: def.pivot, offset: offset.map(Math.round), cubes, drive: def.drive, fullBright: !!def.fullBright, doc: def.doc };
    });
    // 贴图排布: 块按高度从大到小一行行排, 取能放下的最小尺寸 (宽 64/128/256, 高 2 的幂)
    const list = [...blocks.values()].map((b) => ({ ...b, ...boxBlock(b.cubes[0]) })).sort((a, b) => b.h - a.h || b.w - a.w || a.sig.localeCompare(b.sig));
    let layout = null;
    for (const [W, H] of [[64, 32], [64, 64], [128, 64], [128, 128], [256, 128], [256, 256]]) {
        let x = 0, y = 0, rowH = 0, ok = true;
        const pos = [];
        for (const b of list) {
            if (x + b.w > W) { x = 0; y += rowH; rowH = 0; }
            if (b.w > W || y + b.h > H) { ok = false; break; }
            pos.push([x, y]); x += b.w; rowH = Math.max(rowH, b.h);
        }
        if (ok) { layout = { W, H, pos }; break; }
    }
    if (!layout) throw new Error('parts texture overflow (> 256x256)');
    list.forEach((b, i) => { b.u = layout.pos[i][0]; b.v = layout.pos[i][1]; for (const c of b.cubes) { c.u = b.u; c.v = b.v; } });
    return { parts, texW: layout.W, texH: layout.H, blocks: list };
}

/** 静止位 (偏移 0) 时一个 cube 的面在世界里的四个顶点。 */
function restWorld(part, p) {
    return [(part.offset[0] + p[0]) / UNITS, -(part.offset[1] + p[1]) / UNITS, (part.offset[2] + p[2]) / UNITS];
}

/**
 * 画运动件贴图 (某一档的调色板; 运动件应与档位无关, 生成器逐档画一遍比对)。
 * 每个面的每个贴图像素: 盒式 UV → 世界点 → 候选方案那个面在该点的贴图像素 (faceTexel), 纯色面直接填色。
 * 共用一块贴图的 cube 逐个画一遍, 画出来不一样就报错。
 */
export function paintLayer(layer, tier, errors) {
    const cv = new Canvas(layer.texW, layer.texH);
    cv.fill(BOTTOM);
    const painted = new Map();
    for (const part of layer.parts) for (const c of part.cubes) {
        for (const poly of mcCubePolygons(c)) {
            const face = WORLD_FACE[poly.dir];
            const plan = c.el.plans[face];
            const src = paintPlan(plan, tier);
            const us = poly.uvs.map((t) => t[0]), vs = poly.uvs.map((t) => t[1]);
            const uLo = Math.min(...us), uHi = Math.max(...us), vLo = Math.min(...vs), vHi = Math.max(...vs);
            const at = (uu, vv) => poly.pts[poly.uvs.findIndex((t) => t[0] === uu && t[1] === vv)];
            const A = restWorld(part, at(uLo, vLo)), B = restWorld(part, at(uHi, vLo)), Cc = restWorld(part, at(uLo, vHi));
            for (let j = vLo; j < vHi; j++) for (let i = uLo; i < uHi; i++) {
                const s = (i + 0.5 - uLo) / (uHi - uLo), t = (j + 0.5 - vLo) / (vHi - vLo);
                const p = [0, 1, 2].map((a) => A[a] + s * (B[a] - A[a]) + t * (Cc[a] - A[a]));
                let col;
                if (src.color) col = src.color;
                else {
                    const [tu, tv] = faceTexel(face, c.el, p, src.d);
                    col = src.canvas.get(Math.min(src.canvas.w - 1, Math.max(0, Math.floor(tu))), Math.min(src.canvas.h - 1, Math.max(0, Math.floor(tv))));
                }
                const k = i + ',' + j;
                const prev = painted.get(k);
                if (prev && prev.join() !== col.join() && errors) { errors.push(`parts texture: texel ${k} painted differently by two cubes (${part.name}/${c.el.name})`); }
                painted.set(k, col);
                cv.px(i, j, col);
            }
        }
    }
    return cv;
}

// ================================================================ Java: MunitionsBenchParts
const f1 = (v) => { const s = (Math.round(v * 10000) / 10000).toString(); return (s.includes('.') ? s : s + '.0') + 'F'; };
const constName = (n) => n.toUpperCase();
/** applyPose 里每个件的一行: place(root, NAME, dx, dy, visible)。drive.x / drive.y 是 Pose 字段名或 null, drive.show 见 SHOW_EXPR。 */
const SHOW_EXPR = {
    always: 'true',
    hot: 'hot', cold: '!hot',
    powderCharged: 'pose.powderCharged', '!powderCharged': '!pose.powderCharged',
    seated: 'pose.seated', '!seated': '!pose.seated',
    ramBulletVisible: 'pose.ramBulletVisible',
};
export function partsJavaSource(layer, meta) {
    const L = [];
    const I = '                        ';
    const faceSet = (c) => 'EnumSet.of(' + MC_ORDER.filter((d) => c.faces.has(d)).map((d) => 'Direction.' + d).join(', ') + ')';
    L.push('package com.miningdim.job.munitions.client;', '');
    L.push('import com.miningdim.core.MiningConstants;');
    L.push('import com.miningdim.job.munitions.block.MunitionsBenchProgram;');
    L.push('import net.minecraft.client.model.geom.ModelPart;');
    L.push('import net.minecraft.client.model.geom.PartPose;');
    L.push('import net.minecraft.client.model.geom.builders.CubeListBuilder;');
    L.push('import net.minecraft.client.model.geom.builders.LayerDefinition;');
    L.push('import net.minecraft.client.model.geom.builders.MeshDefinition;');
    L.push('import net.minecraft.client.model.geom.builders.PartDefinition;');
    L.push('import net.minecraft.core.Direction;');
    L.push('import net.minecraft.resources.ResourceLocation;', '');
    L.push('import java.util.EnumSet;', '');
    L.push(
        '/**',
        ' * 军火台 (WIDE 布局) 的运动件: 皮带上的弹、出弹、压弹头冲头 (连杆 / 冷热两个压模 / 夹着的弹头)、底火冲杆、装药管。',
        ' * 静态机身是区块网格里的 JSON 模型 (munitions_bench*_line_*.json); 这些件由主格的方块实体渲染器按',
        ' * {@link MunitionsBenchProgram} 的姿态每帧画, 待机时也画 (停在待机布局)。运动件与档位无关, 六档共用一张贴图 {@link #TEXTURE}。',
        ' * <p>',
        ' * 由 tools/munitions_bench/generate_munitions_bench.mjs 整个写出 (与方块模型同一份场景), 不要手改。',
        ' * <p>',
        ' * 坐标系: 朝北放置时的整台局部像素, x 东、y 上、z 南, 原点在主格西北下角 (主格 x 0..16 在玩家右手, 副格 x 16..32 在左手)。',
        ` * 件按 ${UNITS} 倍尺寸建 (运动件有 0.25 / 0.75 px 的尺寸、贴图 2-4 texel/px, 原版盒式 UV 按 1 texel/单位算), 渲染时缩回 {@link #PART_SCALE}。`,
        ' * 每个件的 PartPose 偏移 = 它在待机布局里的枢轴 (px) × {@link #UNITS_PER_PX}, y 取反 (ModelPart 的 y 朝下)。',
        ' * <p>',
        ' * 渲染器 (主格方块实体) 的变换:',
        ' * <pre>',
        ' * poseStack.translate(0.5, 0.0, 0.5);',
        ' * poseStack.mulPose(Axis.YP.rotationDegrees(rot));   // NORTH 0, EAST -90, SOUTH 180, WEST 90 (与方块状态的 y 旋转同向)',
        ' * poseStack.translate(-0.5, 0.0, -0.5);',
        ' * Matrix3f normal = new Matrix3f(poseStack.last().normal());',
        ' * poseStack.scale(PART_SCALE, -PART_SCALE, PART_SCALE);',
        ' * poseStack.last().normal().set(normal).scale(1.0F, -1.0F, 1.0F);   // 法线矩阵自己设, 见下',
        ' * MunitionsBenchParts.applyPose(root, pose);             // pose = MunitionsBenchProgram.sample(...) 或 idle(...)',
        ' * </pre>',
        ' * 1.20.1 的 {@code PoseStack.scale} 在三个缩放之积为负 (一个轴取负) 时把法线矩阵算坏: {@code Mth.fastInvCubeRoot} 不收负数,',
        ' * 法线被放大约 1e25 倍, 写进顶点时每个分量截成 ±1, 实体光照随视角乱跳 (三轴同为负也一样)。diag(s, -s, s) 的逆转置',
        ' * 与 diag(1, -1, 1) 同向, 所以把缩放前的法线矩阵右乘 diag(1, -1, 1) 即是正确的单位法线矩阵。',
        ' * 然后逐个画 {@link #PARTS} (root 本身不画, 隐藏的件 render 直接跳过): {@link #FULL_BRIGHT} 里的件用',
        ' * {@code LightTexture.pack(max(方块光, FULL_BRIGHT_LIGHT), max(天空光, FULL_BRIGHT_LIGHT))}, 其余用方块实体的 packedLight。',
        ' * y 翻转后面的绕序反了, 用 {@code RenderType.entityCutoutNoCull(TEXTURE)} (与组装台机械臂相同); 不剔除背面, 所以件上看得见的面',
        ' * 都要建上 (缺一个面就能看进件里, 对拍脚本的"背面检查"会报出来)。',
        ' */',
        'public final class MunitionsBenchParts {',
        '',
        '    public static final ResourceLocation TEXTURE = new ResourceLocation(MiningConstants.MODID, "textures/entity/munitions_bench_parts.png");',
        `    /** ModelPart 单位 → 像素的缩放 (件按 ${UNITS} 倍尺寸建)。 */`,
        `    public static final float PART_SCALE = ${f1(PART_SCALE)};`,
        `    public static final float UNITS_PER_PX = ${f1(UNITS)};`,
        `    public static final int TEXTURE_WIDTH = ${layer.texW};`,
        `    public static final int TEXTURE_HEIGHT = ${layer.texH};`,
        '    /** 压模热度 (Pose.dieHeat) 到这个值就画热压模 {@link #RAM_DIE_HOT}, 否则画冷压模 {@link #RAM_DIE}。 */',
        `    public static final float HOT_THRESHOLD = ${f1(meta.hotThreshold)};`,
        '    /** {@link #FULL_BRIGHT} 里的件至少按这个方块光 / 天空光等级画 (与方案预览里热压模的自发光等级相同)。 */',
        `    public static final int FULL_BRIGHT_LIGHT = ${meta.fullBrightLight};`,
        '',
    );
    for (const p of layer.parts) {
        L.push(`    /** ${p.doc} */`);
        L.push(`    public static final String ${constName(p.name)} = "${p.name}";`);
    }
    L.push('', '    /** 全部运动件 (画的顺序)。 */');
    L.push('    public static final String[] PARTS = {');
    for (const p of layer.parts) L.push(`            ${constName(p.name)},`);
    L.push('    };');
    L.push('    /** 自发光的件 (见 {@link #FULL_BRIGHT_LIGHT})。 */');
    L.push('    public static final String[] FULL_BRIGHT = {' + layer.parts.filter((p) => p.fullBright).map((p) => constName(p.name)).join(', ') + '};');
    L.push('', '    private MunitionsBenchParts() {', '    }', '');
    L.push('    public static LayerDefinition createBodyLayer() {');
    L.push('        MeshDefinition mesh = new MeshDefinition();');
    L.push('        PartDefinition root = mesh.getRoot();');
    for (const p of layer.parts) {
        L.push(`        root.addOrReplaceChild(${constName(p.name)},`);
        L.push('                CubeListBuilder.create()');
        p.cubes.forEach((c, i) => {
            L.push(`${I}.texOffs(${c.u}, ${c.v}).addBox(${[c.x, c.y, c.z, c.w, c.h, c.d].map(f1).join(', ')}, ${faceSet(c)})${i === p.cubes.length - 1 ? ',' : ''}`);
        });
        L.push(`                PartPose.offset(${p.offset.map(f1).join(', ')}));`);
    }
    L.push(`        return LayerDefinition.create(mesh, ${layer.texW}, ${layer.texH});`);
    L.push('    }', '');
    L.push(
        '    /**',
        '     * 按程序姿态摆放各运动件 (改 x / y 偏移与 visible), 每帧画之前调一次。root = {@code createBodyLayer().bakeRoot()}。',
        '     * 皮带上的弹与出弹沿 x 走 beltX; 出弹另沿 y 走 dropY; 冲头三件 + 夹着的弹头沿 y 走 ramY; 底火冲杆 / 装药管各走 primeY / powderY。',
        '     */',
        '    public static void applyPose(ModelPart root, MunitionsBenchProgram.Pose pose) {',
        '        boolean hot = pose.dieHeat >= HOT_THRESHOLD;',
    );
    for (const p of layer.parts) {
        const dx = p.drive.x ? `pose.${p.drive.x}` : '0.0F';
        const dy = p.drive.y ? `pose.${p.drive.y}` : '0.0F';
        const show = SHOW_EXPR[p.drive.show || 'always'];
        if (!show) throw new Error('unknown show rule ' + p.drive.show);
        L.push(`        place(root, ${constName(p.name)}, ${dx}, ${dy}, ${show});`);
    }
    L.push('    }', '');
    L.push(
        '    /** 把一个件从静止位平移 (dx, dy) px 并设显隐; ModelPart 的 y 朝下, 所以 y 取反。 */',
        '    private static void place(ModelPart root, String name, float dx, float dy, boolean visible) {',
        '        ModelPart part = root.getChild(name);',
        '        PartPose rest = part.getInitialPose();',
        '        part.x = rest.x + dx * UNITS_PER_PX;',
        '        part.y = rest.y - dy * UNITS_PER_PX;',
        '        part.z = rest.z;',
        '        part.visible = visible;',
        '    }',
        '}',
        '',
    );
    return L.join('\n');
}

const num = (s) => parseFloat(String(s).replace(/[FfDd]/g, ''));
/**
 * 解析 MunitionsBenchParts.java: 件的 cube / 偏移 / 贴图尺寸, applyPose 每一行的驱动与显隐表达式, 以及几个常量。
 * 预览与对拍以这份 Java 为唯一真源 (改了 Java 生成方式要同时改这里)。
 */
export function parsePartsJava(src) {
    const consts = {};
    for (const m of src.matchAll(/public static final String (\w+) = "(\w+)";/g)) consts[m[1]] = m[2];
    const numConst = (n) => { const m = new RegExp(`static final (?:float|int) ${n} = ([-\\d.]+)F?;`).exec(src); if (!m) throw new Error('parts parse: ' + n); return num(m[1]); };
    const tex = /LayerDefinition\.create\(mesh,\s*(\d+),\s*(\d+)\)/.exec(src);
    if (!tex) throw new Error('parts parse: LayerDefinition.create not found');
    const parts = [];
    const re = /root\.addOrReplaceChild\((\w+),\s*CubeListBuilder\.create\(\)([\s\S]*?),\s*PartPose\.offset\(([^)]*)\)\);/g;
    for (const m of src.matchAll(re)) {
        const name = consts[m[1]];
        if (!name) throw new Error('parts parse: unknown part constant ' + m[1]);
        const cubes = [];
        for (const c of m[2].matchAll(/\.texOffs\((\d+),\s*(\d+)\)\.addBox\(([^,]+),([^,]+),([^,]+),([^,]+),([^,]+),([^,]+),\s*EnumSet\.of\(([^)]*)\)\)/g)) {
            const n = c.slice(3, 9).map(num);
            cubes.push({ u: Number(c[1]), v: Number(c[2]), x: n[0], y: n[1], z: n[2], w: n[3], h: n[4], d: n[5], faces: new Set(c[9].split(',').map((s) => s.trim().replace('Direction.', ''))) });
        }
        parts.push({ name, offset: m[3].split(',').map(num), cubes });
    }
    const places = {};
    for (const m of src.matchAll(/place\(root, (\w+), ([^,]+), ([^,]+), ([^)]+)\);/g)) {
        const name = consts[m[1]];
        const ch = (e) => (e.trim() === '0.0F' ? null : /^pose\.(\w+)$/.exec(e.trim())[1]);
        places[name] = { x: ch(m[2]), y: ch(m[3]), show: m[4].trim() };
    }
    const fb = /String\[\] FULL_BRIGHT = \{([^}]*)\}/.exec(src);
    return {
        parts, places, texW: Number(tex[1]), texH: Number(tex[2]),
        unitsPerPx: numConst('UNITS_PER_PX'), partScale: numConst('PART_SCALE'), hotThreshold: numConst('HOT_THRESHOLD'),
        fullBrightLight: numConst('FULL_BRIGHT_LIGHT'),
        fullBright: new Set((fb ? fb[1] : '').split(',').map((s) => s.trim()).filter(Boolean).map((k) => consts[k])),
        textureId: 'miningdim:entity/munitions_bench_parts',
    };
}

// ================================================================ Java: MunitionsBenchProgram 的关键帧表
/** 关键帧表的列, 与 Java 手写部分的 COL_* 常量一一对应 (生成器会核对)。 */
export const PROGRAM_COLUMNS = [
    ['COL_TICK', 'tick'], ['COL_BELT_X', 'beltX'], ['COL_PRIME_Y', 'primeY'], ['COL_POWDER_Y', 'powderY'], ['COL_RAM_Y', 'ramY'],
    ['COL_DROP_Y', 'dropY'], ['COL_DIE_HEAT', 'dieHeat'], ['COL_RAM_BULLET', 'ramBulletVisible'], ['COL_POWDER_CHARGED', 'powderCharged'], ['COL_SEATED', 'seated'],
];
export const POSE_FLOATS = ['beltX', 'primeY', 'powderY', 'ramY', 'dropY', 'dieHeat'];
export const POSE_BOOLS = ['ramBulletVisible', 'powderCharged', 'seated'];

/** 生成区块正文 (两行 <generated> 注释之间)。rows: [{tick, ...Pose 字段, label}], idle: Pose 字段。 */
export function programBlock(meta) {
    const pad = (s, n) => (s.length >= n ? s : ' '.repeat(n - s.length) + s);
    const headers = PROGRAM_COLUMNS.map(([, k]) => k);
    const widths = headers.map((h, i) => (i === 0 ? 4 : Math.max(h.length, 7)));
    const cell = (row, k, i) => {
        if (k === 'tick') return pad(String(row.tick), widths[i]);
        if (POSE_BOOLS.includes(k)) return pad(row[k] ? '1' : '0', widths[i]);
        return pad(f1(row[k]), widths[i]);
    };
    const line = (row) => '{' + headers.map((k, i) => cell(row, k, i)).join(', ') + '}';
    return [
        '    /** 一个生产循环的长度 (tick): ' + meta.frames + ' 帧, 每帧 ' + meta.ticksPerFrame + ' tick。 */',
        `    public static final int CYCLE_TICKS = ${meta.cycleTicks};`,
        '    /** 冲头压到底 (弹头落到壳口上) 的时刻: 服务端在程序起点 + STRIKE_TICK + n × CYCLE_TICKS 播冲压音, 渲染器在同一时刻放火花。 */',
        `    public static final int STRIKE_TICK = ${meta.strikeTick};`,
        '    /** 皮带节距 (px): 一个循环皮带正好走一个节距; 循环接缝处 beltX 从 -BELT_PITCH 跳回 0, 同时弹位整体换一次号, 画面不变。 */',
        `    public static final float BELT_PITCH = ${f1(meta.pitch)};`,
        '    private static final float[][] KEYFRAMES = {',
        '            // ' + headers.map((h, i) => pad(h, widths[i])).join(', ').replace(/^\s+/, ''),
        ...meta.rows.map((r) => `            ${line(r)}, // ${r.label}`),
        '    };',
        '    /** 待机布局 (不推进相位, 方块实体不在工作时画它)。 */',
        `    private static final float[] IDLE = ${line({ ...meta.idle, tick: 0 })};`,
    ].join('\n') + '\n';
}
export const RE_GENERATED = /([ \t]*\/\/ <generated>[^\n]*\n)[\s\S]*?([ \t]*\/\/ <\/generated>[^\n]*\n)/;

/** 解析 MunitionsBenchProgram.java: 列号 (手写的 COL_*)、关键帧表、待机行与几个常量。 */
export function parseProgramJava(src) {
    const cols = {};
    for (const [cname, key] of PROGRAM_COLUMNS) {
        const m = new RegExp(`${cname} = (\\d+);`).exec(src);
        if (!m) throw new Error('program parse: ' + cname + ' not found');
        cols[key] = Number(m[1]);
    }
    const intConst = (n) => { const m = new RegExp(`static final int ${n} = (\\d+);`).exec(src); if (!m) throw new Error('program parse: ' + n); return Number(m[1]); };
    const pitch = /static final float BELT_PITCH = ([-\d.]+)F;/.exec(src);
    const block = /KEYFRAMES\s*=\s*\{([\s\S]*?)\n\s*\};/.exec(src);
    if (!block) throw new Error('program parse: KEYFRAMES not found');
    const toRow = (vals, label) => {
        const row = { label: label || '' };
        for (const [key, i] of Object.entries(cols)) row[key] = POSE_BOOLS.includes(key) ? num(vals[i]) !== 0 : num(vals[i]);
        return row;
    };
    const rows = [];
    for (const m of block[1].matchAll(/\{([^{}]*)\},?[ \t]*(?:\/\/[ \t]*([^\n]*))?/g)) rows.push(toRow(m[1].split(','), (m[2] || '').trim()));
    const idle = /float\[\] IDLE = \{([^}]*)\};/.exec(src);
    if (!idle) throw new Error('program parse: IDLE not found');
    return { cycleTicks: intConst('CYCLE_TICKS'), strikeTick: intConst('STRIKE_TICK'), pitch: pitch ? num(pitch[1]) : null, cols, rows, idle: toRow(idle[1].split(',')) };
}

// ================================================================ JS 镜像 (必须与 Java 逐行一致)
const copyRow = (r) => Object.fromEntries([...POSE_FLOATS, ...POSE_BOOLS].map((k) => [k, r[k]]));
/**
 * MunitionsBenchProgram.sample(float cycleTick, Pose) 的镜像: 按 CYCLE_TICKS 取模 (负数折回, NaN 与 0 取首行);
 * 连续量在相邻两行间线性插值 (候选方案的帧表本身就是按缓动取样写的), 布尔量取"到达的那一行"。
 */
export function sampleProgram(prog, cycleTick) {
    let t = cycleTick % prog.cycleTicks;
    if (t < 0) t += prog.cycleTicks;
    if (!(t > 0)) return copyRow(prog.rows[0]);
    let i = 1;
    while (prog.rows[i].tick < t) i++;
    const a = prog.rows[i - 1], b = prog.rows[i];
    const s = (t - a.tick) / (b.tick - a.tick);
    const out = {};
    for (const k of POSE_FLOATS) out[k] = a[k] + (b[k] - a[k]) * s;
    for (const k of POSE_BOOLS) out[k] = b[k];
    return out;
}
/** MunitionsBenchProgram.sample(long elapsedTicks, float partialTick, Pose) 的镜像。 */
export function sampleProgramAt(prog, elapsed, partial) {
    const within = ((elapsed % prog.cycleTicks) + prog.cycleTicks) % prog.cycleTicks;
    return sampleProgram(prog, within + partial);
}
export const idlePose = (prog) => copyRow(prog.idle);

/** MunitionsBenchParts.applyPose 的镜像: 名字 → {dx, dy (px), visible}。parsed = parsePartsJava 的结果。 */
export function applyPoseMirror(parsed, pose) {
    const hot = pose.dieHeat >= parsed.hotThreshold;
    const out = {};
    for (const p of parsed.parts) {
        const pl = parsed.places[p.name];
        if (!pl) throw new Error('applyPose has no place() line for ' + p.name);
        let visible;
        const s = pl.show;
        if (s === 'true') visible = true;
        else if (s === 'hot') visible = hot;
        else if (s === '!hot') visible = !hot;
        else {
            const m = /^(!?)pose\.(\w+)$/.exec(s);
            if (!m || !(m[2] in pose)) throw new Error('applyPose: unsupported visibility expression ' + s);
            visible = m[1] ? !pose[m[2]] : !!pose[m[2]];
        }
        out[p.name] = { dx: pl.x ? pose[pl.x] : 0, dy: pl.y ? pose[pl.y] : 0, visible };
    }
    return out;
}

// ================================================================ 烘成四边形 (预览)
/**
 * 按渲染器的变换烘运动件 (parsed = parsePartsJava 的结果, placed = applyPoseMirror 的结果)。
 * opts: {rotDeg: 渲染器的朝向角 (Axis.YP, NORTH 0 / EAST -90 / SOUTH 180 / WEST 90), 绕主格中心 (8, 8) 转;
 *        offset: 整体平移 (px); entity: true = 实体光照 (游戏里的样子), false = 方块面明暗 (与 JSON 模型逐像素对拍用)}
 * 返回 raster.mjs 的四边形 (uv 为贴图像素)。
 */
export function bakeParts(parsed, placed, image, opts = {}) {
    const quads = [];
    const a = ((opts.rotDeg || 0) * Math.PI) / 180, ca = Math.cos(a), sa = Math.sin(a);
    const off = opts.offset || [0, 0, 0];
    // Axis.YP 旋转 (右手系, 俯视逆时针为正): x' = x cos + z sin, z' = -x sin + z cos, 绕主格中心
    const rot = (p) => { const x = p[0] - 8, z = p[2] - 8; return [8 + x * ca + z * sa + off[0], p[1] + off[1], 8 - x * sa + z * ca + off[2]]; };
    const rotDir = (n) => [n[0] * ca + n[2] * sa, n[1], -n[0] * sa + n[2] * ca];
    for (const part of parsed.parts) {
        const pl = placed[part.name];
        if (!pl || !pl.visible) continue;
        const px = part.offset[0] + pl.dx * parsed.unitsPerPx, py = part.offset[1] - pl.dy * parsed.unitsPerPx, pz = part.offset[2];
        const light = parsed.fullBright.has(part.name) ? parsed.fullBrightLight : 0;
        for (const c of part.cubes) for (const poly of mcCubePolygons(c)) {
            const face = WORLD_FACE[poly.dir];
            const pts = poly.pts.map((p) => rot([(px + p[0]) * parsed.partScale, -(py + p[1]) * parsed.partScale, (pz + p[2]) * parsed.partScale]));
            quads.push({
                pts, uvs: poly.uvs, normal: rotDir(NORMALS[face]), image, uvScale: parsed.texW, uvScaleV: parsed.texH,
                // 对拍模式照 JSON 模型的规矩 (背面剔除、方块面明暗); 热压模的底面在方案里不发光, 所以元素不是 shade:false
                cull: !opts.entity, shade: null, entity: !!opts.entity, light, tag: 'part:' + part.name, part: part.name, face,
            });
        }
    }
    return quads;
}

/** 摆好之后每个可见 cube 的世界包围盒 (朝北, 像素): [{part, lo, hi}]。 */
export function placedBoxes(parsed, placed) {
    const out = [];
    for (const part of parsed.parts) {
        const pl = placed[part.name];
        if (!pl) continue;
        const px = part.offset[0] + pl.dx * parsed.unitsPerPx, py = part.offset[1] - pl.dy * parsed.unitsPerPx, pz = part.offset[2];
        for (const c of part.cubes) {
            const x0 = (px + c.x) * parsed.partScale, x1 = (px + c.x + c.w) * parsed.partScale;
            const yA = -(py + c.y) * parsed.partScale, yB = -(py + c.y + c.h) * parsed.partScale;
            const z0 = (pz + c.z) * parsed.partScale, z1 = (pz + c.z + c.d) * parsed.partScale;
            out.push({ part: part.name, visible: pl.visible, lo: [x0, Math.min(yA, yB), z0].map(r4), hi: [x1, Math.max(yA, yB), z1].map(r4) });
        }
    }
    return out;
}

export { TIERS };
