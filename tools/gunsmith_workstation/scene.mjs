// 从资源目录装配枪匠两台工作站的预览场景 (Node 侧)。
import fs from 'node:fs';
import path from 'node:path';
import { readPng } from './png.mjs';
import { bakeBlockModel, parseArmJava, parseArmProgram, bakeArm, armPose, armPhasePose, armProgramState, sparkQuads } from './raster.mjs';

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

    /** 机械臂关键帧程序源码; 旧版 (HEAD 之前的时间窗动作) 没有这个文件, 返回 null。 */
    armProgramPath() {
        try {
            return this.find(path.join('src', 'main', 'java', 'com', 'miningdim', 'job', 'munitions', 'block', 'GunsmithArmProgram.java'));
        } catch (e) {
            return null;
        }
    }

    arm() {
        const src = fs.readFileSync(this.armJavaPath(), 'utf8');
        const arm = parseArmJava(src);
        const tex = /new ResourceLocation\(\s*MiningConstants\.MODID\s*,\s*"textures\/([^"]+)\.png"\)/.exec(src);
        arm.textureId = 'miningdim:' + (tex ? tex[1] : 'entity/gunsmith_assembly_arm');
        // 渲染器里还留着旧版姿态常量时 (改前的 Java) 走旧的时间窗动作, 否则读关键帧程序
        const programFile = arm.consts.IDLE_UPPER_ARM_Z === undefined ? this.armProgramPath() : null;
        arm.program = programFile ? parseArmProgram(fs.readFileSync(programFile, 'utf8')) : null;
        return arm;
    }
}

/**
 * 机械臂在某一时刻的四边形 (含点焊火花)。armOpt: {tick} 为程序时间 0..160; 旧版 Java (无程序) 时 tick 按 80 tick 一轮
 * 映射到旧的 phase, 与当时游戏里的循环一致。
 */
export function armQuads(root, arm, armOpt = {}) {
    const image = root.image(arm.textureId);
    if (arm.program) {
        // 工作态但没给 tick: 取第一次点焊的时刻, 让静态组图也能看到零件、夹爪与火花
        const firstWeld = arm.program.rows.find((r) => r.spark);
        const tick = armOpt.tick !== undefined ? armOpt.tick : armOpt.work && firstWeld ? firstWeld.tick - 1 : 0;
        const st = armProgramState(arm, tick);
        const quads = bakeArm(arm, st.pose, image);
        if (st.spark) quads.push(...sparkQuads(st.contact, tick));
        return quads;
    }
    if (armOpt.tick === undefined) return bakeArm(arm, armPose(arm, armOpt.work || 0), image);
    return bakeArm(arm, armPhasePose(arm, ((armOpt.tick % 80) + 80) % 80 / 80), image);
}

/** 机械冲压机: 单格。 */
export function pressQuads(root, active) {
    const model = root.model('miningdim:block/gunsmith_press' + (active ? '_active' : ''));
    return bakeBlockModel(model, { textureLookup: (id) => root.image(id), tag: 'press' });
}

/** 枪械组装台: 2×2 四个部位 + 机械臂; armOpt.tick 为机械臂程序时间 (0..160, 省略 = 待机姿态)。 */
export function assemblyQuads(root, active, armOpt = {}) {
    const quads = [];
    for (const [part, offset] of Object.entries(PART_OFFSETS)) {
        const model = root.model('miningdim:block/gunsmith_assembly_bench_' + part + (active ? '_active' : ''));
        quads.push(...bakeBlockModel(model, { offset, textureLookup: (id) => root.image(id), tag: part }));
    }
    quads.push(...armQuads(root, root.arm(), armOpt));
    return quads;
}

export function itemQuads(root, itemId) {
    const model = root.model('miningdim:item/' + itemId);
    return { quads: bakeBlockModel(model, { textureLookup: (id) => root.image(id), tag: 'item' }), display: model.display };
}
