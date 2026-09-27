#!/usr/bin/env node
// 组装台 TACZ 真枪预览页 (单文件 HTML, 按 claude.ai Artifact 的页面约定写: 没有 <html>/<head>/<body>, 发布时套上):
// GunsmithBlueprint 的全部蓝图枪、台子待机/工作两套模型、机械臂关键帧程序与贴图全部内联,
// 浏览器端用同一套 raster 核心实时渲染 (bakeBedrockGeo + layoutGunOnBed 摆枪, benchGunPlaceDrops 算放件下沉, bakeBlockModel / bakeArm 画台子与机械臂)。
// 每把枪附上 check_bench_guns.mjs 的检查结果 (高模: 长度、朝上一侧、台上包围盒、下沉量、点焊间隙、机械臂最近距离)。
// 用法: node build_gun_gallery_html.mjs [--repo <仓库或 worktree 根>] [--packs <tacz 目录>] --out <page.html>
// 枪模只保留加载需要的键, 裸枪显示 stack 下不画的方块 (taczBareDrawn: 手、默认隐藏子树、attachment_adapter 的子节点) 去掉。
// 贴图: 能无损缩到 geo 的 texture_width/height 就缩 (颜色与镂空逐像素不变), 否则内联原图 —— 现有枪包的 PNG 不是 geo 分辨率的
// 整数倍放大, 平均缩小会翻转一半以上不透明像素的镂空 (司登枪管护套的散热孔整片消失), 原图每张也只有 8-43 KB。
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { AssetRoot, PART_OFFSETS } from './scene.mjs';
import { encodePng } from './png.mjs';
import { bakeBedrockGeo, taczBareDrawn } from './raster.mjs';
import { TaczPacks, resolveGun, readBlueprints, downscaleIfLossless, splitId, DEFAULT_PACKS } from './taczpack.mjs';
import { benchContext, analyzeGun } from './check_bench_guns.mjs';
import { playerEyePitch } from './render.mjs';
import { GUN_BED } from './generate_assembly_bench.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const args = {};
{
    const av = process.argv.slice(2);
    for (let i = 0; i < av.length; i++) if (av[i].startsWith('--')) { const n = av[i + 1]; if (n === undefined || n.startsWith('--')) args[av[i].slice(2)] = true; else { args[av[i].slice(2)] = n; i++; } }
}
if (!args.out) { console.error('usage: node build_gun_gallery_html.mjs [--repo <root>] [--packs <tacz dir>] --out <page.html>'); process.exit(2); }
const repo = path.resolve(args.repo || path.join(here, '..', '..'));
const SIZE_LIMIT = 12 * 1024 * 1024;
const TITLE = '组装台真枪预览';

const images = {};
const pngUri = (img) => 'data:image/png;base64,' + encodePng(img).toString('base64');

/** 只留 TACZ 加载会读的键; 裸枪不画的方块 (taczBareDrawn) 不会画也不进包围盒, 去掉 (骨骼本身保留: 枪口参考点可能就是隐藏节点)。 */
function stripGeo(json) {
    const g = json['minecraft:geometry'] && json['minecraft:geometry'][0];
    if (!g) return json;   // 旧格式原样带上
    const byName = new Map(g.bones.map((b) => [b.name, b]));
    const ancestors = (b) => { const out = []; for (let q = b, n = 0; q && n < 256; q = q.parent ? byName.get(q.parent) : null, n++) out.push(q.name); return out; };
    const pick = (o, keys) => Object.fromEntries(keys.filter((k) => o[k] !== undefined).map((k) => [k, o[k]]));
    return {
        format_version: json.format_version,
        'minecraft:geometry': [{
            description: pick(g.description, ['texture_width', 'texture_height']),
            bones: g.bones.map((b) => {
                const out = pick(b, ['name', 'parent', 'pivot', 'rotation', 'mirror']);
                const anc = ancestors(b);
                const cubes = (b.cubes || []).filter((c) => taczBareDrawn(anc, c, false));
                if (cubes.length) out.cubes = cubes.map((c) => pick(c, ['origin', 'size', 'uv', 'inflate', 'pivot', 'rotation', 'mirror']));
                return out;
            }),
        }],
    };
}

function collectModel(root, id, prefix) {
    const model = root.model(id);
    const textures = { ...(model.textures || {}) };
    const resolve = (ref, d = 0) => (ref.startsWith('#') && d < 8 ? resolve(textures[ref.slice(1)], d + 1) : ref);
    for (const el of model.elements || []) for (const f of Object.values(el.faces || {})) {
        const tid = resolve(f.texture);
        const key = prefix + '|' + tid;
        if (!images[key]) images[key] = pngUri(root.image(tid));
    }
    return { textures, elements: model.elements, prefix };
}

const t0 = Date.now();
const root = new AssetRoot([repo]);
const bench = {};
for (const state of ['idle', 'active']) {
    bench[state] = Object.entries(PART_OFFSETS).map(([part, offset]) => ({ offset, model: collectModel(root, `miningdim:block/gunsmith_assembly_bench_${part}${state === 'active' ? '_active' : ''}`, 'bench') }));
}
const arm = root.arm();
if (!arm.program || !arm.program.places) throw new Error('GunsmithArmProgram.java has no keyframe program with place drop constants');
images['arm|' + arm.textureId] = pngUri(root.image(arm.textureId));
const weldRow = arm.program.rows.find((r) => r.spark);

const lang = JSON.parse(fs.readFileSync(path.join(repo, 'src', 'main', 'resources', 'assets', 'miningdim', 'lang', 'zh_cn.json'), 'utf8'));
const blueprints = readBlueprints(repo);
const platforms = [...new Set(blueprints.map((b) => b.platform))].map((key) => ({ key, name: lang['gunsmith.platform.' + key.toLowerCase()] || key }));
const ctx = benchContext(repo);
const packs = new TaczPacks(args.packs || DEFAULT_PACKS);
const guns = [];
const failed = [];
const texReport = [];
const tokens = new Set();
for (const bp of blueprints) {
    const g = resolveGun(packs, bp.id);
    const geo = stripGeo(g.model.geo);
    const baked = bakeBedrockGeo(geo, null, { bed: GUN_BED });
    const check = analyzeGun(ctx, baked);
    if (check.fails.length) failed.push(bp.id + ': ' + check.fails.join('; '));
    const tex = downscaleIfLossless(g.texture.image, baked.texW, baked.texH);
    texReport.push(`${bp.id} ${g.texture.image.width}x${g.texture.image.height}` + (tex.downscaled ? ` -> ${baked.texW}x${baked.texH}`
        : ` kept (geo ${baked.texW}x${baked.texH}${tex.maskLoss == null ? ', not an integer multiple' : tex.maskLoss ? `, downscaling flips ${(tex.maskLoss * 100).toFixed(0)}% of the opaque texels` : ''})`));
    const key = 'gun|' + g.texture.path;
    images[key] = pngUri(tex.image);
    // 深链记号: 枪 id 的路径部分 (#m4a1); 页面只收得到裸 #记号
    const token = splitId(bp.id, 'tacz').path;
    if (!/^[A-Za-z0-9._~-]+$/.test(token) || tokens.has(token)) throw new Error(`gun ${bp.id}: "${token}" is not a unique bare #anchor token`);
    tokens.add(token);
    // 原始 (未去隐藏方块) 模型里 attachment_adapter 下不画的方块数, 页面上作说明
    check.cubes.adapter = bakeBedrockGeo(g.model.geo, null, { bed: GUN_BED }).stats.adapterCubes;
    guns.push({
        id: bp.id, token, platform: bp.platform, platformName: platforms.find((p) => p.key === bp.platform).name,
        name: (g.name || '').replace(/§./g, '').trim() || null, pack: g.pack, geo, image: key,
        check: JSON.parse(JSON.stringify(check, (k, v) => (typeof v === 'number' && !Number.isFinite(v) ? null : typeof v === 'number' ? Math.round(v * 1e4) / 1e4 : v))),
        notes: g.notes,
    });
}
packs.close();

const eye = Math.round(playerEyePitch(GUN_BED) * 10) / 10;
const data = {
    bed: GUN_BED, platforms, guns, defaultGun: 'tacz:m4a1', weldTick: weldRow ? weldRow.tick : 0, images, bench,
    arm: { parts: arm.parts, texW: arm.texW, texH: arm.texH, consts: arm.consts, windows: arm.windows, program: arm.program, image: 'arm|' + arm.textureId },
    presets: [
        { label: '玩家视角', title: `站在台前 (北侧) 1.5 格、眼高 1.62 格, 看枪床中心 (俯角 ${eye}°; 预览是正交投影)`, yaw: 0, pitch: eye },
        { label: '斜上', title: '从左前上方看', yaw: 22, pitch: 40 },
        { label: '左前', yaw: 38, pitch: 32 },
        { label: '右前', yaw: -38, pitch: 32 },
        { label: '俯视', yaw: 0, pitch: 89.9 },
        { label: '侧面', title: '从东侧看 (托底挡块一侧)', yaw: -90, pitch: 12 },
    ],
};
const rasterSrc = fs.readFileSync(path.join(here, 'raster.mjs'), 'utf8').replace(/^export\s+/gm, '');
if (/^\s*import\s/m.test(rasterSrc)) throw new Error('raster.mjs imports another module: it cannot be inlined into the page');
const template = fs.readFileSync(path.join(here, 'gun_gallery_template.html'), 'utf8');
const html = template
    .replaceAll('/*__TITLE__*/', TITLE)
    .replace('/*__RASTER__*/', () => rasterSrc)
    .replace('/*__DATA__*/', () => JSON.stringify(data));
// Artifact 页面约定: 外层文档结构由发布时套上, 页面自己不许带
if (/<!doctype|<html[\s>]|<head[\s>]|<body[\s>]/i.test(template)) throw new Error('gun_gallery_template.html must not contain <!doctype>/<html>/<head>/<body> (added at publish time)');
if (!html.trimStart().startsWith('<title>')) throw new Error('the page must start with its <title>');
if (Buffer.byteLength(html) > SIZE_LIMIT) throw new Error(`page is ${(Buffer.byteLength(html) / 1048576).toFixed(1)} MB (> ${SIZE_LIMIT / 1048576} MB)`);
fs.mkdirSync(path.dirname(path.resolve(args.out)), { recursive: true });
fs.writeFileSync(args.out, html);
console.log(`wrote ${args.out} ${(Buffer.byteLength(html) / 1048576).toFixed(2)} MB: ${guns.length} guns, ${Object.keys(images).length} images, ${((Date.now() - t0) / 1000).toFixed(1)} s`);
console.log('gun textures:\n  ' + texReport.join('\n  '));
if (failed.length) console.warn('placement check failures (shown on the page):\n  ' + failed.join('\n  '));
