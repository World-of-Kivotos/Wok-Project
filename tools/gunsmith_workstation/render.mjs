#!/usr/bin/env node
// 枪匠工作站多视角预览出图。
// 用法: node render.mjs --repo <仓库或 worktree 根> --block press|assembly --state idle|active --out x.png
//        [--overlay <候选方案根, 结构同仓库, 优先读取>] [--closeup FL|FR|BR|BL|F|S|T|EYE|NIGHT] [--views FL,FR,...] [--arm 0..1] [--phase 0..1]
// 组图 5×2: 左前 / 右前 / 右后 / 左后 / 玩家视角 / 正前 / 侧面(东) / 俯视 / 夜间(自发光) / 物品栏图标。
import path from 'node:path';
import { AssetRoot, pressQuads, assemblyQuads, itemQuads } from './scene.mjs';
import { rasterize, cameraFromAngles, cameraFromDir, fillBackground, groundQuads, guiTransform } from './raster.mjs';
import { writePng } from './png.mjs';
import { drawText } from './font.mjs';

function parseArgs(argv) {
    const args = {};
    for (let i = 0; i < argv.length; i++) {
        if (argv[i].startsWith('--')) {
            const key = argv[i].slice(2);
            const next = argv[i + 1];
            if (next === undefined || next.startsWith('--')) args[key] = true;
            else { args[key] = next; i++; }
        }
    }
    return args;
}

const GROUND = (() => {
    const data = new Uint8ClampedArray(16 * 16 * 4);
    for (let y = 0; y < 16; y++) for (let x = 0; x < 16; x++) {
        const edge = x === 0 || y === 0;
        const v = edge ? 150 : ((x + y) & 1 ? 196 : 190);
        const o = (y * 16 + x) * 4;
        data[o] = v; data[o + 1] = v + 2; data[o + 2] = v + 6; data[o + 3] = 255;
    }
    return { width: 16, height: 16, data };
})();

// 固定取景盒 (像素), 新旧模型用同一比例尺, 便于对比。
const FRAMES = {
    press: { min: [-2, 0, -2], max: [18, 17, 18], ground: [-1, 2, -1, 2] },
    assembly: { min: [-2, 0, -2], max: [34, 22, 34], ground: [-1, 3, -1, 3] },
};

export const VIEWS = {
    FL: { label: 'FRONT-LEFT', yaw: 38, pitch: 32 },
    FR: { label: 'FRONT-RIGHT', yaw: -38, pitch: 32 },
    BR: { label: 'BACK-RIGHT', yaw: 180 + 38, pitch: 32 },
    BL: { label: 'BACK-LEFT', yaw: 180 - 38, pitch: 32 },
    F: { label: 'FRONT', yaw: 0, pitch: 0 },
    S: { label: 'SIDE-EAST', yaw: -90, pitch: 0 },
    T: { label: 'TOP', yaw: 0, pitch: 89.9 },
    EYE: { label: 'PLAYER-EYE', yaw: 22, pitch: 40 },
    NIGHT: { label: 'NIGHT (LIGHT 0)', yaw: 38, pitch: 32, brightness: 0.18 },
};

function frameCorners(frame) {
    const out = [];
    for (const x of [frame.min[0], frame.max[0]]) for (const y of [frame.min[1], frame.max[1]]) for (const z of [frame.min[2], frame.max[2]]) out.push([x, y, z]);
    return out;
}

function renderPanel(target, x0, y0, w, h, quads, frame, view) {
    const cam = cameraFromAngles(view.yaw, view.pitch);
    let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
    for (const p of frameCorners(frame)) {
        const sx = p[0] * cam.right[0] + p[1] * cam.right[1] + p[2] * cam.right[2];
        const sy = -(p[0] * cam.up[0] + p[1] * cam.up[1] + p[2] * cam.up[2]);
        minX = Math.min(minX, sx); maxX = Math.max(maxX, sx); minY = Math.min(minY, sy); maxY = Math.max(maxY, sy);
    }
    const pad = 18;
    const scale = Math.min((w - pad * 2) / (maxX - minX), (h - pad * 2 - 12) / (maxY - minY));
    const panel = { width: w, height: h, data: new Uint8ClampedArray(w * h * 4) };
    fillBackground(panel, [58, 63, 72]);
    const g = frame.ground;
    const all = quads.concat(groundQuads(g[0], g[1], g[2], g[3], GROUND));
    rasterize(all, panel, {
        cam, scale,
        brightness: view.brightness,
        cx: w / 2 - ((minX + maxX) / 2) * scale,
        cy: (h + 12) / 2 - ((minY + maxY) / 2) * scale,
    });
    drawText(panel, 6, 5, view.label, [235, 235, 235]);
    blit(target, panel, x0, y0);
}

function renderIcon(target, x0, y0, w, h, item, label) {
    // 物品栏原生 16px, GUI 缩放 2 时为 32 屏幕像素: 先按 32px 渲染, 再最近邻放大。
    const n = 32;
    const small = { width: n, height: n, data: new Uint8ClampedArray(n * n * 4) };
    fillBackground(small, [139, 139, 139]);
    const quads = guiTransform(item.quads, item.display);
    const cam = cameraFromDir([0, 0, 1]);
    rasterize(quads, small, { cam, scale: n / 16, cx: n / 2, cy: n / 2 });
    const panel = { width: w, height: h, data: new Uint8ClampedArray(w * h * 4) };
    fillBackground(panel, [58, 63, 72]);
    const k = Math.floor(Math.min(w - 20, h - 40) / n);
    const ox = Math.floor((w - n * k) / 2), oy = Math.floor((h - n * k) / 2) + 8;
    for (let y = 0; y < n * k; y++) for (let x = 0; x < n * k; x++) {
        const si = (Math.floor(y / k) * n + Math.floor(x / k)) * 4;
        const di = ((oy + y) * w + ox + x) * 4;
        panel.data[di] = small.data[si]; panel.data[di + 1] = small.data[si + 1]; panel.data[di + 2] = small.data[si + 2]; panel.data[di + 3] = 255;
    }
    // 右下角再放一个 1:1 实际大小。
    for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) {
        const si = (y * n + x) * 4;
        const di = ((h - n - 6 + y) * w + (w - n - 6) + x) * 4;
        panel.data[di] = small.data[si]; panel.data[di + 1] = small.data[si + 1]; panel.data[di + 2] = small.data[si + 2]; panel.data[di + 3] = 255;
    }
    drawText(panel, 6, 5, label, [235, 235, 235]);
    blit(target, panel, x0, y0);
}

function blit(target, src, x0, y0) {
    for (let y = 0; y < src.height; y++) {
        const ty = y0 + y;
        if (ty < 0 || ty >= target.height) continue;
        for (let x = 0; x < src.width; x++) {
            const tx = x0 + x;
            if (tx < 0 || tx >= target.width) continue;
            const si = (y * src.width + x) * 4, di = (ty * target.width + tx) * 4;
            target.data[di] = src.data[si]; target.data[di + 1] = src.data[si + 1]; target.data[di + 2] = src.data[si + 2]; target.data[di + 3] = 255;
        }
    }
}

export function renderSheet(repo, block, state, opts = {}) {
    const root = new AssetRoot([...(opts.overlay ? [opts.overlay] : []), repo]);
    const active = state === 'active';
    const quads = block === 'press' ? pressQuads(root, active) : assemblyQuads(root, active, opts.armWork, opts.armPhase);
    const frame = FRAMES[block];
    const itemId = block === 'press' ? 'gunsmith_press' : 'gunsmith_assembly_bench';
    if (opts.closeup) {
        const w = 1100, h = 860;
        const target = { width: w, height: h, data: new Uint8ClampedArray(w * h * 4) };
        renderPanel(target, 0, 0, w, h, quads, frame, VIEWS[opts.closeup] || VIEWS.FL);
        drawText(target, 6, h - 14, `${block.toUpperCase()} ${state.toUpperCase()} ${opts.tag || ''}`, [255, 220, 120]);
        return target;
    }
    const keys = (opts.views || 'FL,FR,BR,BL,EYE,F,S,T,NIGHT').split(',');
    const cols = 5, pw = 360, ph = 300;
    const rows = Math.ceil((keys.length + 1) / cols);
    const target = { width: cols * pw, height: rows * ph + 18, data: new Uint8ClampedArray(cols * pw * (rows * ph + 18) * 4) };
    fillBackground(target, [30, 32, 36]);
    keys.forEach((k, i) => renderPanel(target, (i % cols) * pw + 1, Math.floor(i / cols) * ph + 1, pw - 2, ph - 2, quads, frame, VIEWS[k]));
    const iconIndex = keys.length;
    renderIcon(target, (iconIndex % cols) * pw + 1, Math.floor(iconIndex / cols) * ph + 1, pw - 2, ph - 2, itemQuads(root, itemId), 'ITEM ICON (GUI x2)');
    drawText(target, 6, rows * ph + 4, `${block.toUpperCase()}  STATE=${state.toUpperCase()}  ${opts.tag || ''}`, [255, 220, 120]);
    return target;
}

if (import.meta.url === `file:///${process.argv[1].replace(/\\/g, '/')}` || process.argv[1].endsWith('render.mjs')) {
    const args = parseArgs(process.argv.slice(2));
    if (!args.repo || !args.block || !args.out) {
        console.error('usage: node render.mjs --repo <root> --block press|assembly [--state idle|active] --out file.png [--overlay <候选方案根>] [--closeup FL|FR|BR|BL|F|S|T|EYE] [--views FL,FR] [--tag text] [--arm 0..1] [--phase 0..1 (Java 时间线)]');
        process.exit(2);
    }
    const img = renderSheet(args.repo, args.block, args.state || 'idle', {
        closeup: args.closeup === true ? 'FL' : args.closeup,
        views: args.views,
        tag: args.tag,
        overlay: args.overlay,
        armWork: args.arm !== undefined ? parseFloat(args.arm) : undefined,
        armPhase: args.phase !== undefined ? parseFloat(args.phase) : undefined,
    });
    writePng(args.out, img);
    console.log('wrote', args.out, img.width + 'x' + img.height);
}
