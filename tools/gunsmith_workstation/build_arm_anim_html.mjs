#!/usr/bin/env node
// 机械臂动作改前/改后对比页 (单文件 HTML, 模型、贴图、Java 解析结果全部内联, 浏览器端用同一套 raster 核心实时渲染)。
// 用法: node build_arm_anim_html.mjs --repo <worktree 根> --out <page.html> [--base <提交>] [--overlay-dir <目录>]
// 左边 "改前": 先把 <base> 版本的组装台模型、贴图与渲染器 Java 用 git show 导出到临时 overlay 目录,
//        按旧版 applyPose 的时间窗动作 (80 tick 一轮) 播放; 右边 "改后": 工作区里的模型 + GunsmithArmProgram 关键帧程序。
// --base 默认是换成关键帧程序之前的最后一个提交 (不是 HEAD: 关键帧程序提交之后 HEAD 就已经是新动作了);
// 导出的渲染器里找不到旧版时间窗 (motionWindow / IDLE_UPPER_ARM_Z) 会直接报错, 不会拿新动作冒充 "改前"。
// 不给 --overlay-dir 时临时目录用完即删; 给了则保留, 方便排查。
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { AssetRoot, PART_OFFSETS } from './scene.mjs';
import { encodePng } from './png.mjs';

const LEGACY_MOTION_COMMIT = '92d21935';   // feat(gunsmith): 重做机械冲压机与枪械组装台建模 — 最后一版时间窗动作
const here = path.dirname(fileURLToPath(import.meta.url));
const args = Object.fromEntries(process.argv.slice(2).reduce((acc, v, i, a) => (v.startsWith('--') ? acc.concat([[v.slice(2), a[i + 1]]]) : acc), []));
if (!args.repo || !args.out) { console.error(`usage: node build_arm_anim_html.mjs --repo <root> --out <page.html> [--base ${LEGACY_MOTION_COMMIT}] [--overlay-dir <dir>]`); process.exit(2); }
const repo = path.resolve(args.repo);
const base = args.base || LEGACY_MOTION_COMMIT;
const keepOverlay = !!args['overlay-dir'];

// ---- 导出旧版资源 (二进制也要原样, 所以经 Node 的 Buffer 写盘, 不走 shell 重定向)
const RES = 'src/main/resources/assets/miningdim/';
const RENDERER = 'src/main/java/com/miningdim/job/munitions/client/GunsmithAssemblyBenchRenderer.java';
const oldFiles = [
    RENDERER,
    RES + 'textures/block/gunsmith_assembly_atlas.png',
    RES + 'textures/block/gunsmith_assembly_particle.png',
    RES + 'textures/entity/gunsmith_assembly_arm.png',
    RES + 'models/item/gunsmith_assembly_bench.json',
    ...Object.keys(PART_OFFSETS).flatMap((p) => [RES + `models/block/gunsmith_assembly_bench_${p}.json`, RES + `models/block/gunsmith_assembly_bench_${p}_active.json`]),
];

const overlay = path.resolve(args['overlay-dir'] || fs.mkdtempSync(path.join(os.tmpdir(), 'gunsmith-arm-base-')));
try {
    exportBase();
    const html = buildPage();
    fs.mkdirSync(path.dirname(path.resolve(args.out)), { recursive: true });
    fs.writeFileSync(args.out, html);
    console.log('wrote', args.out, (html.length / 1024).toFixed(0) + ' KB', '(old motion from', base + (keepOverlay ? ', assets kept in ' + overlay : '') + ')');
} finally {
    if (!keepOverlay) fs.rmSync(overlay, { recursive: true, force: true });
}

function exportBase() {
    for (const rel of oldFiles) {
        const buf = execFileSync('git', ['show', `${base}:${rel}`], { cwd: repo, encoding: 'buffer', maxBuffer: 64 << 20 });
        const dst = path.join(overlay, rel);
        fs.mkdirSync(path.dirname(dst), { recursive: true });
        fs.writeFileSync(dst, buf);
    }
    // overlay 里没有 GunsmithArmProgram.java, AssetRoot 会回落到工作区的那份; 旧版不会误用它,
    // 因为 scene.mjs 的 arm() 在渲染器里还留着旧姿态常量时根本不读程序。前提是 base 真的是旧版, 所以先确认:
    const oldRenderer = fs.readFileSync(path.join(overlay, RENDERER), 'utf8');
    if (!/motionWindow\(/.test(oldRenderer) || !/IDLE_UPPER_ARM_Z/.test(oldRenderer)) {
        throw new Error(`${base}: GunsmithAssemblyBenchRenderer.java has no legacy motionWindow / IDLE_UPPER_ARM_Z pose code; `
            + `pass --base <a commit before the keyframe program> (default ${LEGACY_MOTION_COMMIT})`);
    }
}

function buildPage() {
    const images = {};
    const collectModel = (root, id, prefix) => {
        const model = root.model(id);
        const textures = { ...(model.textures || {}) };
        const resolve = (ref, d = 0) => (ref.startsWith('#') && d < 8 ? resolve(textures[ref.slice(1)], d + 1) : ref);
        for (const el of model.elements || []) for (const f of Object.values(el.faces || {})) {
            const tid = resolve(f.texture);
            const key = prefix + '|' + tid;
            if (!images[key]) images[key] = 'data:image/png;base64,' + encodePng(root.image(tid)).toString('base64');
        }
        return { textures, elements: model.elements, prefix };
    };
    const variant = (id, roots, label, note) => {
        const root = new AssetRoot(roots);
        const parts = Object.entries(PART_OFFSETS).map(([part, offset]) => ({ offset, model: collectModel(root, `miningdim:block/gunsmith_assembly_bench_${part}_active`, id) }));
        const arm = root.arm();
        const key = id + '|' + arm.textureId;
        images[key] = 'data:image/png;base64,' + encodePng(root.image(arm.textureId)).toString('base64');
        return { id, label, note, parts, arm: { parts: arm.parts, texW: arm.texW, texH: arm.texH, consts: arm.consts, windows: arm.windows, program: arm.program, image: key } };
    };
    const data = {
        images,
        variants: [
            variant('old', [overlay, repo], '改前动作', `${base} 版: 转向/伸出/夹紧/焊接四个时间窗互相重叠, 80 tick 一轮 (装配 160 tick 内跑两轮), 焊接时手腕正弦抖 12 次, 夹爪始终空着。`),
            variant('new', [repo], '新动作', '关键帧程序: 竖直抬离换刀座 → 安全高度平移 → 竖直下探取件 → 夹紧后零件出现 → 竖直抬起 → 平移 → 竖直下探 → 两次点焊 → 松开装上 → 抬起; 两件零件 (枪机、枪托件), 最后竖直落回换刀座, 160 tick 一轮。'),
        ],
    };
    if (!data.variants[1].arm.program) throw new Error('the working tree has no GunsmithArmProgram keyframe table');
    if (data.variants[0].arm.program) throw new Error(`${base} already uses the keyframe program; pass --base <older commit> to compare against the old motion`);

    const rasterSrc = fs.readFileSync(path.join(here, 'raster.mjs'), 'utf8').replace(/^export\s+/gm, '');
    const template = fs.readFileSync(path.join(here, 'arm_anim_template.html'), 'utf8');
    return template
        .replaceAll('/*__TITLE__*/', '枪匠机械臂动作')
        .replace('/*__RASTER__*/', () => rasterSrc)
        .replace('/*__DATA__*/', () => JSON.stringify(data));
}
