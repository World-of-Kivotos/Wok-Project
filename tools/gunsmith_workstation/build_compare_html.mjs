#!/usr/bin/env node
// 生成新旧方案并排对比的交互预览页 (单文件 HTML, 模型与贴图内联, 浏览器端用同一套 raster 核心实时渲染)。
// 用法: node build_compare_html.mjs --repo <worktree 根> --config <config.json> --out <page.html>
// config.json: { "title": "...", "blocks": { "press": [ {id,label,overlay?,summary,points:[...]} ], "assembly": [...] } }
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { AssetRoot, PART_OFFSETS } from './scene.mjs';
import { encodePng } from './png.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const args = Object.fromEntries(process.argv.slice(2).reduce((acc, v, i, a) => (v.startsWith('--') ? acc.concat([[v.slice(2), a[i + 1]]]) : acc), []));
const repo = path.resolve(args.repo);
const config = JSON.parse(fs.readFileSync(args.config, 'utf8'));

function collectModel(root, id, images, prefix) {
    const model = root.model(id);
    const textures = {};
    for (const [k, v] of Object.entries(model.textures || {})) textures[k] = v;
    // 解析出实际用到的贴图并内联。
    const resolve = (ref, d = 0) => (ref.startsWith('#') && d < 8 ? resolve(textures[ref.slice(1)], d + 1) : ref);
    for (const el of model.elements || []) for (const f of Object.values(el.faces || {})) {
        const tid = resolve(f.texture);
        const key = prefix + '|' + tid;
        if (!images[key]) images[key] = 'data:image/png;base64,' + encodePng(root.image(tid)).toString('base64');
    }
    return { textures, elements: model.elements, display: model.display || null, prefix };
}

function countElements(models) {
    return models.reduce((n, m) => n + (m.elements ? m.elements.length : 0), 0);
}

const data = { images: {}, blocks: {} };
for (const [block, variants] of Object.entries(config.blocks)) {
    data.blocks[block] = [];
    for (const v of variants) {
        const root = new AssetRoot([...(v.overlay ? [path.resolve(repo, v.overlay)] : []), repo]);
        const prefix = block + ':' + v.id;
        const entry = { id: v.id, label: v.label, summary: v.summary || '', points: v.points || [], states: {} };
        for (const state of ['idle', 'active']) {
            const suffix = state === 'active' ? '_active' : '';
            if (block === 'press') {
                entry.states[state] = [{ offset: [0, 0, 0], model: collectModel(root, 'miningdim:block/gunsmith_press' + suffix, data.images, prefix) }];
            } else {
                entry.states[state] = Object.entries(PART_OFFSETS).map(([part, offset]) => ({
                    offset, model: collectModel(root, 'miningdim:block/gunsmith_assembly_bench_' + part + suffix, data.images, prefix),
                }));
            }
        }
        entry.item = collectModel(root, 'miningdim:item/' + (block === 'press' ? 'gunsmith_press' : 'gunsmith_assembly_bench'), data.images, prefix);
        entry.elementCount = countElements(entry.states.idle.map((p) => p.model));
        if (block === 'assembly') {
            const arm = root.arm();
            const key = prefix + '|' + arm.textureId;
            data.images[key] = 'data:image/png;base64,' + encodePng(root.image(arm.textureId)).toString('base64');
            entry.arm = { parts: arm.parts, texW: arm.texW, texH: arm.texH, consts: arm.consts, image: key };
        }
        data.blocks[block].push(entry);
    }
}

const rasterSrc = fs.readFileSync(path.join(here, 'raster.mjs'), 'utf8').replace(/^export\s+/gm, '');
const template = fs.readFileSync(path.join(here, 'compare_template.html'), 'utf8');
const html = template
    .replaceAll('/*__TITLE__*/', config.title || '枪匠工作站')
    .replace('/*__RASTER__*/', () => rasterSrc)
    .replace('/*__DATA__*/', () => JSON.stringify(data));
fs.writeFileSync(args.out, html);
console.log('wrote', args.out, (html.length / 1024).toFixed(0) + ' KB');
