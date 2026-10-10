// 从 sim.js 生成可直接内联进页面 <script> 的版本：sim.inline.js
// 做法：去掉顶层 export 关键字，包进 IIFE，把所有导出挂到 globalThis.ZhangshaoSim。
// 用法：node build-inline.mjs
import { readFileSync, writeFileSync } from 'node:fs';

const src = readFileSync(new URL('./sim.js', import.meta.url), 'utf8');
if (/^\s*import\s/m.test(src)) throw new Error('sim.js 不能有 import（内联版要自包含）');

const names = [];
const body = src.replace(/^export\s+(const|let|function|class)\s+([A-Za-z_$][\w$]*)/gm, (m, kind, name) => {
  names.push(name);
  return `${kind} ${name}`;
});
if (/^export\s/m.test(body)) throw new Error('有没处理掉的 export 形式');

const out = `// 由 build-inline.mjs 从 sim.js 生成，请勿手改。用法：<script src="sim.inline.js"></script> 后访问 window.ZhangshaoSim
(function (root) {
'use strict';
${body}
root.ZhangshaoSim = Object.freeze({ ${names.join(', ')} });
})(typeof globalThis !== 'undefined' ? globalThis : this);
`;
writeFileSync(new URL('./sim.inline.js', import.meta.url), out);
console.log(`sim.inline.js: ${names.length} exports, ${out.length} bytes`);
