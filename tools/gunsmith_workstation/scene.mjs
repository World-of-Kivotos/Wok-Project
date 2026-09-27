// 从资源目录装配枪匠两台工作站的预览场景 (Node 侧)。
import fs from 'node:fs';
import path from 'node:path';
import { readPng } from './png.mjs';
import { bakeBlockModel, parseArmJava, bakeArm, armPose, armPhasePose } from './raster.mjs';

export const PART_OFFSETS = {
    // 朝北时: SIDE = 顺时针 = 东 (+x), BACK = 南 (+z)。见 GunsmithAssemblyBenchBlock.partPos。
    main: [0, 0, 0],
    side: [16, 0, 0],
    back: [0, 0, 16],
    back_side: [16, 0, 16],
};

export class AssetRoot {
    /**
     * @param roots 按优先级排列的根目录 (每个根下有 src/main/resources 与 src/main/java);
     *              候选方案目录放在前面, 仓库根兜底。
     */
    constructor(roots) {
        this.roots = roots;
        this.images = new Map();
    }

    find(rel) {
        for (const r of this.roots) {
            const p = path.join(r, rel);
            if (fs.existsSync(p)) return p;
        }
        throw new Error('asset not found in any root: ' + rel);
    }

    modelPath(id) {
        const [ns, p] = id.includes(':') ? id.split(':') : ['minecraft', id];
        return this.find(path.join('src', 'main', 'resources', 'assets', ns, 'models', p + '.json'));
    }

    texturePath(id) {
        const [ns, p] = id.includes(':') ? id.split(':') : ['minecraft', id];
        return this.find(path.join('src', 'main', 'resources', 'assets', ns, 'textures', p + '.png'));
    }

    /** 读模型并沿 parent 合并 textures/elements/display (跳过 minecraft: 内置父模型)。 */
    model(id) {
        const own = JSON.parse(fs.readFileSync(this.modelPath(id), 'utf8'));
        if (own.parent && !own.parent.startsWith('minecraft:') && !own.parent.startsWith('block/') && !own.parent.startsWith('item/')) {
            const parent = this.model(own.parent);
            return {
                ...parent,
                ...own,
                textures: { ...(parent.textures || {}), ...(own.textures || {}) },
                elements: own.elements || parent.elements,
                display: { ...(parent.display || {}), ...(own.display || {}) },
            };
        }
        return own;
    }

    image(id) {
        if (!this.images.has(id)) {
            const file = id.startsWith('/') || /^[A-Za-z]:/.test(id) ? id : this.texturePath(id);
            const img = readPng(file);
            // 动画贴图 (有 .mcmeta, 高 > 宽) 只取第一帧。
            if (img.height > img.width && fs.existsSync(file + '.mcmeta')) {
                img.data = img.data.slice(0, img.width * img.width * 4);
                img.height = img.width;
            }
            img.id = id;
            this.images.set(id, img);
        }
        return this.images.get(id);
    }

    armJavaPath() {
        return this.find(path.join('src', 'main', 'java', 'com', 'miningdim', 'job', 'munitions', 'client', 'GunsmithAssemblyBenchRenderer.java'));
    }

    arm() {
        const src = fs.readFileSync(this.armJavaPath(), 'utf8');
        const arm = parseArmJava(src);
        const tex = /new ResourceLocation\(\s*MiningConstants\.MODID\s*,\s*"textures\/([^"]+)\.png"\)/.exec(src);
        arm.textureId = 'miningdim:' + (tex ? tex[1] : 'entity/gunsmith_assembly_arm');
        return arm;
    }
}

/** 机械冲压机: 单格。 */
export function pressQuads(root, active) {
    const model = root.model('miningdim:block/gunsmith_press' + (active ? '_active' : ''));
    return bakeBlockModel(model, { textureLookup: (id) => root.image(id), tag: 'press' });
}

/** 枪械组装台: 2×2 四个部位 + 机械臂; armWork 0..1 为机械臂动作进度 (线性); armPhase 0..1 为 Java 真实时间线 (优先)。 */
export function assemblyQuads(root, active, armWork = active ? 1 : 0, armPhase = undefined) {
    const quads = [];
    for (const [part, offset] of Object.entries(PART_OFFSETS)) {
        const model = root.model('miningdim:block/gunsmith_assembly_bench_' + part + (active ? '_active' : ''));
        quads.push(...bakeBlockModel(model, { offset, textureLookup: (id) => root.image(id), tag: part }));
    }
    const arm = root.arm();
    const pose = armPhase !== undefined ? armPhasePose(arm, armPhase) : armPose(arm, armWork);
    quads.push(...bakeArm(arm, pose, root.image(arm.textureId)));
    return quads;
}

export function itemQuads(root, itemId) {
    const model = root.model('miningdim:item/' + itemId);
    return { quads: bakeBlockModel(model, { textureLookup: (id) => root.image(id), tag: 'item' }), display: model.display };
}
