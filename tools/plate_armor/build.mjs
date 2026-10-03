// Build the single-file preview page.
//   node build.mjs                       -> armor-preview.html with every armor
//   node build.mjs --only paca --out preview-paca.html   -> page with just that armor (for isolated review)
import fs from 'node:fs';
const here = new URL('./', import.meta.url);
const r = f => fs.readFileSync(new URL(f, here), 'utf8').replace(/^﻿/, '');
const arg = k => { const i = process.argv.indexOf(k); return i > 0 ? process.argv[i + 1] : null; };
const only = arg('--only'), out = arg('--out') || 'armor-preview.html';

// page order = the item table (tier I -> VI), not the per-meta 'order' field
const ITEMS = fs.existsSync(new URL('./items.json', here)) ? JSON.parse(r('./items.json')).items : [];
const ORDER = Object.fromEntries(ITEMS.map((it, i) => [it.key, i]));
const esc = s => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/"/g, '&quot;');
const metas = fs.readdirSync(new URL('./armors/', here)).filter(f => f.endsWith('.meta.json'))
  .map(f => JSON.parse(r('./armors/' + f)))
  .filter(m => !only || m.key === only)
  .sort((a, b) => (ORDER[a.key] ?? 999) - (ORDER[b.key] ?? 999) || a.key.localeCompare(b.key));
if (!metas.length) throw new Error('no armor metas' + (only ? ' for ' + only : ''));

// armor source files (jaypc / iotv live in designs.js; the rest in armors/<key>.js)
const armorSrc = metas.filter(m => fs.existsSync(new URL(`./armors/${m.key}.js`, here))).map(m => r(`./armors/${m.key}.js`)).join('\n');

const TIERS = ['I', 'II', 'III', 'IV', 'V', 'VI'];
const tierOf = m => String(m.tier).trim().split(/[\s·]/)[0];
const btn = m => `      <button type="button" id="b-${m.key}" data-armor="${m.key}" title="${esc(m.name)} · ${esc(m.tier)}">${esc(m.name)}<span class="tier">${esc(m.tier)}</span></button>`;
const buttons = TIERS.map(T => {
  const ms = metas.filter(m => tierOf(m) === T);
  return ms.length ? `    <div class="tier-row"><span class="tier-tag">${T}</span>\n${ms.map(btn).join('\n')}\n    </div>` : '';
}).filter(Boolean).join('\n') + metas.filter(m => !TIERS.includes(tierOf(m))).map(btn).join('\n');
const refs = metas.map((m, i) => `    <div class="note" data-for="${m.key}"${i ? ' hidden' : ''}>
      <h3>${esc(m.title)}</h3>
      <p class="hint">原图：${m.refs.map(x => `<a href="${esc(x.url)}" target="_blank" rel="noopener">${esc(x.label)}</a>`).join(' · ')}（${esc(m.refSource || 'tarkov.dev 的游戏内渲染图')}）</p>
      <ul>
${m.notes.map(n => `        <li>${n}</li>`).join('\n')}
      </ul>
    </div>`).join('\n');

const viewer = r('./viewer.js');
const nl = viewer.indexOf('\n');
const rest = viewer.slice(nl + 1);
// local builds load three.js from ./vendor (no network needed); --cdn (for publishing) keeps the CDN import
const imp = process.argv.includes('--cdn') ? viewer.slice(0, nl) : viewer.slice(0, nl).replace(/'https:[^']+'/, "'./vendor/three.module.min.js'");
const keys = JSON.stringify(metas.map(m => m.key));
// current_embed.js (the old hand-written Java models, for the 'now' card) is a generated scratch file and is not in the
// repo; without it CURRENT is empty and the viewer hides the 'now' card
const current = fs.existsSync(new URL('./current_embed.js', here)) ? r('./current_embed.js').replace(/^export /gm, '') : 'const CURRENT = {};';
const script = [
  imp,
  r('./designs.js').replace(/^export /gm, ''),
  armorSrc,
  // keep only the armors on this page, in page order
  `for (const k of Object.keys(ARMORS)) if (!${keys}.includes(k)) delete ARMORS[k];`,
  current,
  rest,
].join('\n');
let html = r('./template.html').replace('<!--ARMOR_BUTTONS-->', () => buttons).replace('<!--ARMOR_REFS-->', () => refs).replace('/*SCRIPT*/', () => script);
// local builds: no Google Fonts either (a hanging stylesheet blocks the module script when the network is flaky)
if (!process.argv.includes('--cdn')) html = html.replace(/<link rel="(preconnect|stylesheet)" href="https:\/\/fonts\.[^"]+"[^>]*>\n?/g, '');
if (only) html = html.replace(/state = \{ armor: [^,]+,/, `state = { armor: '${only}',`);
fs.writeFileSync(new URL('./' + out, here), html);
console.log('ok', out, metas.map(m => m.key).join(','), script.length);
