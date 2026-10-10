// 菜表 → 掌勺用的「台 / 性子 / 翻面 / 拍数 / 冷饮」五列（spec §13.3–§13.6 的规则逐道套一遍）。
//   node dish-play.mjs        → 写 dish-play.tsv（给人看）和 dish-play.json（给 bots.mjs 按真实菜品加权）
// 输入：../../dish-stars.tsv（204 道菜的星级表，只读）和 ../../stations.md 的分台口径（写在下面的规则里）。
// 这是给菜表补列的提案：正式菜表加上 motion / flips / beats 列以后，以菜表为准，本文件只做核对。
import fs from 'node:fs';

const MOTION_NAMES = ['混', '窜', '稳', '沉', '浮'];
const ST_NAMES = ['厨锅', '炸锅', '烤炉', '备餐台'];
const MIX = 0, DART = 1, STEADY = 2, SINK = 3, FLOAT = 4;

// —— 分台（stations.md）——
const FRYER_IDS = new Set(['casualness_delight:fried_dumpling', 'casualness_delight:spring_roll',
  'casualness_delight:plate_of_fried_dumpling', 'casualness_delight:spring_roll_medley']);
const DRINK_IDS = new Set(['delightful:berry_matcha_latte', 'delightful:salmonberry_ice_cream', 'delightful:matcha_ice_cream',
  'delightful:salmonberry_milkshake', 'delightful:matcha_milkshake', 'casualness_delight:green_tongue',
  'delightful:cantaloupe_popsicle', 'moredelight:chocolate_popsicle', 'farmersdelight:melon_popsicle',
  'crabbersdelight:sea_pickle_juice', 'crabbersdelight:kelp_shake', 'farmersdelight:melon_juice']);
const OVEN_WORDS = ['派', '蛋糕', '乳蛋饼', '巴克拉瓦', '披萨', '烤鸡', '蜜汁火腿', '烤羊排', '牛排配土豆', '香烤鲑鱼',
  '曲奇', '吐司', '土司', '烤面包', '棉花糖饼干', '甜瓜面包', '驴肉火烧', '串', '烤面筋', 'Stuffed Cod'];

// —— 性子（§13.6；烤串按审查处理改成「混」，不再「窜 + 翻 3」叠加）——
const MOTION_RULES = [
  [[STEADY, ['汤', '羹', '炖', '煲', '粥', '火锅']], [SINK, ['水饺', '馄饨', '饺子', '汤圆']], [DART, ['虾', '蟹', '鱿鱼', '鱼', '贝']], [FLOAT, ['甜品', '糖水', '布丁', '果冻', '酱']]],
  [[DART, ['虾', '鱿鱼', '蟹']]],
  [[STEADY, ['派', '蛋糕', '面包', '挞', '披萨']], [SINK, ['烤肉', '整只', '排', '火腿']]],
  [[STEADY, ['沙拉', '冷盘', '色拉']], [DART, ['寿司', '饭团', '海鲜']]],
];
const MOTION_OTHER = [MIX, FLOAT, MIX, MIX];
const MOTION_OVERRIDE = { 'farmersdelight:roast_chicken_block': [SINK, '整只烤肉'] };

export function classify(d) {
  let station;
  if (FRYER_IDS.has(d.id)) station = 1;
  else if (/厨锅/.test(d.method) && !/^工作台/.test(d.method)) station = 0;
  else if (DRINK_IDS.has(d.id)) station = 3;
  else if (OVEN_WORDS.some((w) => d.name.includes(w))) station = 2;
  else station = 3;
  const drink = DRINK_IDS.has(d.id);
  let motion, why;
  if (MOTION_OVERRIDE[d.id]) [motion, why] = MOTION_OVERRIDE[d.id];
  else if (drink) { motion = FLOAT; why = '冷饮'; }
  else {
    for (const [m, words] of MOTION_RULES[station]) {
      const w = words.find((x) => d.name.includes(x));
      if (w) { motion = m; why = `菜名含「${w}」`; break; }
    }
    if (motion === undefined) { motion = MOTION_OTHER[station]; why = station === 1 ? '炸锅默认' : '其余默认'; }
  }
  let flips = 0, beats = 0;
  if (station === 2) flips = d.name.includes('串') ? 3 : (/盛宴/.test(d.method) || d.id === 'farmersdelight:roast_chicken_block') ? 2 : 1;
  if (station === 3) {
    if (drink) beats = 6;
    else {
      const ing = new Set(d.parts.split('+').map((x) => x.replace(/\d+$/, '').trim()).filter((x) => x && x !== '盛宴'));
      beats = Math.max(3, Math.min(8, ing.size + 1));
    }
  }
  return { station, motion, why, flips, beats, drink };
}

export function loadDishes(tsvPath) {
  const lines = fs.readFileSync(tsvPath, 'utf8').replace(/^﻿/, '').split(/\r?\n/).filter(Boolean);
  return lines.slice(1).map((l) => {
    const c = l.split('\t');
    const d = { star: +c[0].replace('★', ''), name: c[2], id: c[3], method: c[5], parts: c[6] || '' };
    return Object.assign(d, classify(d));
  });
}

const isMain = process.argv[1] && import.meta.url.endsWith(process.argv[1].replace(/\\/g, '/').split('/').pop());
if (isMain) {
  const dishes = loadDishes(new URL('../../dish-stars.tsv', import.meta.url));
  const out = ['物品ID\t菜名\t★\t台\t性子\t翻面\t拍数\t冷饮\t性子依据'];
  for (const d of dishes) out.push([d.id, d.name, d.star, ST_NAMES[d.station], MOTION_NAMES[d.motion], d.flips || '', d.beats || '', d.drink ? '是' : '', d.why].join('\t'));
  fs.writeFileSync(new URL('./dish-play.tsv', import.meta.url), out.join('\n') + '\n');
  fs.writeFileSync(new URL('./dish-play.json', import.meta.url), JSON.stringify(dishes.map((d) => ({ id: d.id, name: d.name, star: d.star, station: d.station, motion: d.motion, flips: d.flips, beats: d.beats, drink: d.drink ? 1 : 0 }))));
  const cnt = [0, 0, 0, 0];
  for (const d of dishes) cnt[d.station]++;
  console.log(`共 ${dishes.length} 道：` + ST_NAMES.map((n, i) => `${n} ${cnt[i]}`).join(' / ') + `（其中冷饮 ${dishes.filter((d) => d.drink).length}）`);
  for (let st = 0; st < 4; st++) {
    const parts = [];
    for (let star = 1; star <= 7; star++) {
      const ds = dishes.filter((d) => d.station === st && d.star === star);
      const m = {};
      for (const d of ds) { const k = MOTION_NAMES[d.motion] + (d.flips > 1 ? `翻${d.flips}` : '') + (d.drink ? '饮' : ''); m[k] = (m[k] || 0) + 1; }
      parts.push(`★${star}: ` + (Object.entries(m).map(([k, v]) => `${k}${v}`).join(' ') || '—'));
    }
    console.log(`${ST_NAMES[st]}  ${parts.join(' | ')}`);
  }
}
