// 掌勺小游戏 · 网页原型打包
//   node build.mjs
// 输出（都在本文件夹）：
//   minigame.html          本地版：带完整 HTML 骨架，双击就能在浏览器里打开（file:// 也行）
//   publish-minigame.html  发布版：只有页面内容（开头 <title> 再 <style>，不写 doctype/html/head/body），给 Artifact 发布用
// 输入：web/template.html + web/game.js（界面层）、sim/sim.inline.js（规则内核，原样内联）、
//       art/manifest.json + art/atlas/*.png + art/dishes.json（素材，内嵌为 data URI）、sim/balance.md（难度一览）、
//       ../dish-effects.tsv（每道菜的厨师效果、类别，结果卡和战斗菜门槛用）、sim/dish-play.json（备餐台拍数）。
// 只用 Node 自带模块。
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const abs = (p) => path.join(here, p);
const rd = (p) => fs.readFileSync(abs(p), 'utf8');
const norm = (t) => t.replace(/\r\n/g, '\n');
const dataURI = (p) => 'data:image/png;base64,' + fs.readFileSync(abs(p)).toString('base64');

// ---------------------------------------------------------------- 1. 内核：确认 sim.inline.js 与 sim.js 同步，再原样内联
// 与 sim/build-inline.mjs 完全相同的变换；不一致就说明有人改了 sim.js 没重新生成内联版。
function inlineFrom(src) {
  const names = [];
  const body = src.replace(/^export\s+(const|let|function|class)\s+([A-Za-z_$][\w$]*)/gm, (m, kind, name) => { names.push(name); return `${kind} ${name}`; });
  return `// 由 build-inline.mjs 从 sim.js 生成，请勿手改。用法：<script src="sim.inline.js"></script> 后访问 window.ZhangshaoSim
(function (root) {
'use strict';
${body}
root.ZhangshaoSim = Object.freeze({ ${names.join(', ')} });
})(typeof globalThis !== 'undefined' ? globalThis : this);
`;
}
const simInline = rd('sim/sim.inline.js');
if (norm(simInline) !== norm(inlineFrom(rd('sim/sim.js')))) {
  throw new Error('sim/sim.inline.js 和 sim/sim.js 不同步：先在 sim/ 里运行 node build-inline.mjs');
}
// 内联版第 1 行注释里写着用法「<script src="sim.inline.js"></script>」，放进 <script> 会提前闭合标签。
// 只把注释行里的 </script 写成 <\/script（JS 注释，不影响任何代码），代码行一个字节都不动。
const simLines = norm(simInline).split('\n');
for (let i = 0; i < simLines.length; i++) {
  if (!/<\/script|<!--/i.test(simLines[i])) continue;
  if (!/^\s*\/\//.test(simLines[i])) throw new Error(`sim.inline.js 第 ${i + 1} 行（代码行）有 </script 或 <!--，不能直接内联`);
  simLines[i] = simLines[i].replace(/<\/script/gi, '<\\/script').replace(/<!--/g, '<\\!--');
}
const simEmbed = simLines.join('\n');

// ---------------------------------------------------------------- 2. 素材
const manifest = JSON.parse(rd('art/manifest.json'));
const atlases = {};
for (const [k, a] of Object.entries(manifest.atlases)) atlases[k] = dataURI('art/' + a.file);
const spr = {};
for (const [key, v] of Object.entries(manifest.sprites)) {
  const a = path.basename(v.atlas, '.png');
  if (!atlases[a]) throw new Error(`${key}: 图集 ${v.atlas} 不在 manifest.atlases 里`);
  const o = { a, r: v.rect };
  if (v.slice) o.s = [v.slice.left, v.slice.top, v.slice.right, v.slice.bottom, v.slice.mode];
  if (v.frames) {
    if (v.frames.layout !== 'vertical') throw new Error(`${key}: 只支持竖排帧`);
    if (v.frames.h * v.frames.count !== v.rect[3]) throw new Error(`${key}: 帧数和图高对不上`);
    o.f = [v.frames.count, v.frames.w, v.frames.h];
  }
  spr[key] = o;
}
// 页面用到的精灵必须都在 manifest 里（防素材改名后页面静默缺图）
const game = rd('web/game.js');
const used = new Set();
for (const m of game.matchAll(/'((?:common|pot|fryer|oven|prep)\/[a-z0-9_]+)'/g)) used.add(m[1]);
const missing = [...used].filter((k) => !spr[k]);
if (missing.length) throw new Error('页面用到但 manifest 没有的精灵：' + missing.join(', '));

const dishes = JSON.parse(rd('art/dishes.json'));
// 厨师效果（../dish-effects.tsv，已拍板）：按物品 id 并入 类别 / 效果名 / 效果数值
const effects = new Map();
{
  const lines = norm(fs.readFileSync(abs('../dish-effects.tsv'), 'utf8')).replace(/^﻿/, '').split('\n').filter(Boolean);
  const head = lines[0].split('\t');
  const col = (name) => { const i = head.indexOf(name); if (i < 0) throw new Error('dish-effects.tsv 缺列：' + name); return i; };
  const ci = { id: col('物品ID'), cat: col('类别'), eff: col('效果名'), val: col('效果数值') };
  for (const l of lines.slice(1)) { const c = l.split('\t'); effects.set(c[ci.id], { cat: c[ci.cat], eff: c[ci.eff], val: c[ci.val] }); }
}
// 拍数（sim/dish-play.json：菜表补列的提案，按「不同食材数 + 1」算，§13.4）
const play = new Map(JSON.parse(rd('sim/dish-play.json')).map((d) => [d.id, d]));
const ST_ORDER = ['pot', 'fryer', 'oven', 'prep'];
const stations = ST_ORDER.map((k) => {
  const st = dishes.stations[k];
  if (!st || st.dishes.length !== 7) throw new Error(`dishes.json：${k} 应有 7 道示例菜`);
  return {
    key: k, name: st.name, icon: dataURI(`art/sprites/${k}/icon.png`),
    dishes: st.dishes.map((d) => {
      const e = effects.get(d.id);
      if (!e) throw new Error(`dish-effects.tsv 里没有 ${d.id}`);
      const pl = play.get(d.id);
      return {
        slotStar: d.slotStar, star: d.star, id: d.id, name: d.name, method: d.method, note: d.note || '',
        flips: d.flips || 0, skin: d.skin || '', icon: dataURI('art/' + d.icon),
        raw: d.atlas.raw.rect.slice(0, 2), track: d.atlas.track.rect.slice(0, 2),
        cat: e.cat, eff: e.eff, val: e.val,
        beats: k === 'prep' && d.skin !== 'drink' && pl ? (pl.beats || 0) : 0,
      };
    }),
  };
});
for (const [k, file] of [['dishes', 'atlas/dishes.png'], ['dishes_track', 'atlas/dishes_track.png']]) {
  if (!atlases[k]) atlases[k] = dataURI('art/' + file);
}

// ---------------------------------------------------------------- 3. 难度一览：sim/balance.md「四台合并 → 熟练」
function parseBalance(md) {
  md = norm(md);
  const when = (md.match(/node bots\.mjs balance (\d+)`（([^，）]+)/) || [])[2] || '';
  const perStation = +((md.match(/每格 \*\*(\d+) 局\*\*/) || [])[1] || 0);
  const sec = md.split('\n## 四台合并')[1];
  if (!sec) throw new Error('balance.md 里找不到「## 四台合并」');
  const part = sec.split('\n### 熟练')[1];
  if (!part) throw new Error('balance.md 里找不到「### 熟练」');
  const lines = part.split('\n').filter((l) => /^\|\s*\d\s*\|/.test(l)).slice(0, 7);
  if (lines.length !== 7) throw new Error('「熟练」表应有 7 行');
  const num = (t) => (t.startsWith('<') ? { v: 0.05, s: t } : { v: +t, s: t });
  const keys = ['low', 'mid', 'high', 'ex', 'rad', 'burnt'];
  const rows = {};
  for (const line of lines) {
    const cells = line.split('|').slice(1, -1).map((c) => c.trim());
    const star = +cells[0];
    rows[star] = {};
    [1, 5, 10].forEach((L, i) => {
      const m = cells[i + 1].match(/^([^ ]+) · ([\d.]+)s · P([\d.<]+)$/);
      if (!m) throw new Error(`balance.md 熟练 ★${star} 第 ${i + 1} 格读不懂：${cells[i + 1]}`);
      const parts = m[1].split('/');
      if (parts.length !== 6) throw new Error('分布应为 6 项：' + m[1]);
      const c = {};
      keys.forEach((k, j) => { c[k] = num(parts[j]); });
      const ex = c.ex.v + c.rad.v;
      c.exPlus = ex >= 10 ? Math.round(ex).toString() : ex.toFixed(1).replace(/\.0$/, '');
      c.time = m[2] + ' 秒';
      c.perfect = m[3];
      rows[star][L] = c;
    });
  }
  return { when, perCell: perStation * 4, rows };
}
const balance = parseBalance(rd('sim/balance.md'));

// ---------------------------------------------------------------- 4. 拼页面
const DATA = { atlases, spr, stations, balance, meta: { artHash: manifest.contentHash } };
const json = JSON.stringify(DATA).replace(/</g, '\\u003c');
if (/<\/script/i.test(game)) throw new Error('game.js 里不能出现 </script');
const tpl = norm(rd('web/template.html'));
const fill = (t, mark, content) => {
  const parts = t.split(mark);
  if (parts.length !== 2) throw new Error('模板占位符不唯一：' + mark);
  return parts[0] + content + parts[1];
};
let content = tpl;
content = fill(content, '<!--@SIM@-->', `<script>\n${simEmbed.trimEnd()}\n</script>`);
content = fill(content, '<!--@DATA@-->', `<script type="application/json" id="zs-data">${json}</script>`);
content = fill(content, '<!--@GAME@-->', `<script>\n${norm(game).trimEnd()}\n</script>`);
if (!content.startsWith('<title>掌勺小游戏</title>\n<style>')) throw new Error('发布版必须以 <title> 再 <style> 开头');
if (/<(!doctype|html|head|body)[\s>]/i.test(content.replace(/<script[\s\S]*?<\/script>/g, ''))) throw new Error('发布版不能写 doctype/html/head/body');

const publish = content;
// 本地版：照发布时的外壳补上骨架（charset、viewport-fit=cover、小 reset），其余完全相同
const local = `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<style>:root{color-scheme:light;padding-top:env(safe-area-inset-top,0px);padding-bottom:env(safe-area-inset-bottom,0px)}body{margin:0;font:14px system-ui,-apple-system,"Segoe UI",sans-serif}img{max-width:100%}[hidden]{display:none!important}</style>
</head>
<body>
${content}
</body>
</html>
`;
fs.writeFileSync(abs('publish-minigame.html'), publish);
fs.writeFileSync(abs('minigame.html'), local);
const kb = (n) => (n / 1024).toFixed(1) + ' KB';
console.log(`minigame.html          ${kb(Buffer.byteLength(local))}`);
console.log(`publish-minigame.html  ${kb(Buffer.byteLength(publish))}（上限 16 MB）`);
console.log(`精灵 ${Object.keys(spr).length}（页面用到 ${used.size}）· 图集 ${Object.keys(atlases).length} · 示例菜 ${stations.reduce((n, s) => n + s.dishes.length, 0)} · 难度表 ${Object.keys(balance.rows).length} 行（${balance.when}）`);
