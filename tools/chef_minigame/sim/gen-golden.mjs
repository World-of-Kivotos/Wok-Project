// 生成 golden.json：一组「参数块 + 种子 + 输入（+ 服务端外部判定 ext）→ 每 20 tick 哈希 + 终局哈希 + 结算结果」，
// 外加 PRNG / fmix32 / 子流种子的已知答案向量。Java 端（JUnit / GameTest）逐条对拍。
// 用法：node gen-golden.mjs
import { writeFileSync } from 'node:fs';
import * as Z from './sim.js';
import { humanBot, perfectBot, macroBot } from './bots.mjs';

const { STATION: ST, MOTION: MO, END, QUALITY: Q } = Z;

// —— 机器人变体 ——
const bots = {
  average: (s0, seed) => humanBot('average', seed),
  good: (s0, seed) => humanBot('good', seed),
  novice: (s0, seed) => humanBot('novice', seed),
  perfect: (s0) => perfectBot(s0),
  toggle1: () => macroBot('toggle1'),
  // 普通玩家只管火候条、从不压火（炸锅爆油用）
  averageNoAct: (s0, seed) => { const b = humanBot('average', seed); return { decide: (s) => b.decide(s) & 1 }; },
  // 普通玩家火候条 + 动作键每 tick 都按（烤炉翻早用）
  averageSpamAct: (s0, seed) => { const b = humanBot('average', seed); return { decide: (s) => (b.decide(s) & 1) | 2 }; },
  // 熟练玩家预判翻面：瞄准提示出现前 2 tick（落在「正好」档）/ 前 7 tick（落在「翻早·轻罚」档）
  anticipate2: (s0, seed) => humanBot('good', seed, { flip: 'anticipate', flipSd: 0, flipBias: -2 }),
  anticipate7: (s0, seed) => humanBot('good', seed, { flip: 'anticipate', flipSd: 0, flipBias: -7 }),
  // 熟练玩家每隔约 3 秒才瞟一眼油温（过热区升温减半的斜坡）
  glance3: (s0, seed) => humanBot('good', seed, { glance: 3 }),
};

function play(cfg, botName) {
  const s = Z.createGame(cfg);
  const bot = bots[botName](s, Z.fmix32(cfg.seed ^ 0x6A09E667));
  const inputs = [];
  while (s.end === END.RUNNING) { const v = bot.decide(s) & 3; inputs.push(v); Z.stepInPlace(s, v); }
  return { s, inputs };
}

function findCase(spec) {
  for (let k = 0; k < 4000; k++) {
    const seed = Z.fmix32((spec.seedBase + k) | 0);
    const cfg = Object.assign({}, spec.config, { seed });
    if (spec.configOverride) cfg.config = spec.configOverride;
    const { s, inputs } = play(cfg, spec.bot);
    const ext = spec.ext || { gold: 0 };
    const r = Z.result(s, Object.assign({}, ext, { mechanical: Z.detectMechanical(inputs, s.p) }));
    if (spec.want(s, r)) return { cfg, inputs };
  }
  throw new Error('找不到满足条件的种子：' + spec.name);
}

const SPECS = [
  { name: 'pot-star1-L1-mix-average', desc: '厨锅 ★1 L1 混，普通玩家：正常出锅、抓到瓶（服务端判普通瓶）、中品质带一条负面', bot: 'average', seedBase: 1000,
    config: { station: ST.POT, star: 1, chefLevel: 1, motion: MO.MIX },
    want: (s, r) => s.end === END.DONE && r.quality === Q.MEDIUM && r.negatives.length === 1 && s.bState === Z.BOTTLE.CAUGHT },
  { name: 'pot-star1-L1-novice-timeout', desc: '厨锅 ★1 L1，新手：熬到 300 tick（15 秒）到点，封顶「中」并补夹生', bot: 'novice', seedBase: 1500,
    config: { station: ST.POT, star: 1, chefLevel: 1, motion: MO.STEADY },
    want: (s, r) => s.end === END.TIMEOUT && s.pt === 300 && r.negatives.includes(Z.NEG.UNDERDONE) },
  { name: 'pot-star3-L10-batch6', desc: '厨锅 ★3 L10 批量 6 份（手熟 2），封顶「高」、金瓶率 0', bot: 'average', seedBase: 2000,
    config: { station: ST.POT, star: 3, chefLevel: 10, motion: MO.MIX, batch: 6, mastery: 2 },
    want: (s, r) => s.end === END.DONE && r.qualityRaw === Q.EXTRAORDINARY && r.quality === Q.HIGH },
  { name: 'pot-star1-L10-sink-toggle1-macro', desc: '厨锅 ★1 L10 沉，每 tick 交替宏：机械节奏命中、封顶「中」', bot: 'toggle1', seedBase: 3000,
    config: { station: ST.POT, star: 1, chefLevel: 10, motion: MO.SINK },
    want: (s, r) => s.end === END.DONE && r.mechanical === 1 && r.qualityRaw >= Q.EXTRAORDINARY && r.quality === Q.MEDIUM },
  { name: 'fryer-star6-L1-float-bursts', desc: '炸锅 ★6 L1 浮，从不压火：先在过热区慢慢升温，再多次爆油', bot: 'averageNoAct', seedBase: 4000,
    config: { station: ST.FRYER, star: 6, chefLevel: 1 },
    want: (s, r) => s.bursts >= 2 && s.hot > 60 },
  { name: 'fryer-star4-L5-glance3-hot', desc: '炸锅 ★4 L5 浮，熟练玩家每 3 秒才瞟一眼油温：过热一阵但不爆油', bot: 'glance3', seedBase: 4500,
    config: { station: ST.FRYER, star: 4, chefLevel: 5 },
    want: (s, r) => s.end === END.DONE && s.bursts === 0 && s.hot > 40 && r.negatives.length >= 0 },
  { name: 'fryer-star2-L10-dart-perfect-radiant', desc: '炸锅 ★2 L10 窜，完美机器人 + 必有瓶 + 服务端判金瓶：闪耀', bot: 'perfect', seedBase: 5000,
    config: { station: ST.FRYER, star: 2, chefLevel: 10, motion: MO.DART, goldPm: 1000 }, ext: { gold: 1 },
    want: (s, r) => r.quality === Q.RADIANT && r.perfect === 1 },
  { name: 'oven-star3-L3-steady-flips3-mastery5', desc: '烤炉 ★3 L3 稳，翻 3 面、手熟 5，新手：有稍晚/焦面', bot: 'novice', seedBase: 6000,
    config: { station: ST.OVEN, star: 3, chefLevel: 3, motion: MO.STEADY, flips: 3, mastery: 5 },
    want: (s, r) => s.end === END.DONE && r.flipLate >= 1 && r.flipBurnt + r.flipGood >= 1 && r.flips === 3 },
  { name: 'oven-star5-L7-sink-flips2-early', desc: '烤炉 ★5 L7 沉，翻 2 面，动作键乱按：两次都落在「翻早·重罚」档', bot: 'averageSpamAct', seedBase: 7000,
    config: { station: ST.OVEN, star: 5, chefLevel: 7, motion: MO.SINK, flips: 2 },
    want: (s, r) => s.end === END.DONE && r.flipEarly === 2 && s.flipUnder === 240 },
  { name: 'oven-star2-L5-anticipate-good', desc: '烤炉 ★2 L5 混，翻 2 面，熟练玩家在提示前 2 tick 预判按下：算「正好」，Perfect 不断', bot: 'anticipate2', seedBase: 7500,
    config: { station: ST.OVEN, star: 2, chefLevel: 5, motion: MO.MIX, flips: 2 },
    want: (s, r) => s.end === END.DONE && r.flipGood === 2 && r.perfect === 1 },
  { name: 'oven-star3-L5-anticipate-mild', desc: '烤炉 ★3 L5 混，熟练玩家提前约 7 tick 按：「翻早·轻罚」（扣 25、欠火 +60）', bot: 'anticipate7', seedBase: 7700,
    config: { station: ST.OVEN, star: 3, chefLevel: 5, motion: MO.MIX, flips: 1 },
    want: (s, r) => s.end === END.DONE && r.flipEarly === 1 && s.flipPen === 25 && s.flipUnder === 60 },
  { name: 'oven-star4-L5-mix-autoflip', desc: '烤炉 ★4 L5 混，从不翻面：焦面计数 + 自动翻', bot: 'averageNoAct', seedBase: 8000,
    config: { station: ST.OVEN, star: 4, chefLevel: 5, motion: MO.MIX, flips: 1 },
    want: (s, r) => s.end !== END.BURNT && r.flipAuto === 1 },
  { name: 'prep-star6-L5-mix-beats7-novice', desc: '备餐台 ★6 L5 混，7 拍一段，新手：漏拍/乱按', bot: 'novice', seedBase: 9000,
    config: { station: ST.PREP, star: 6, chefLevel: 5, motion: MO.MIX, beats: 7 },
    want: (s, r) => s.segIdx === 1 && (r.beatMiss + r.beatMash) >= 1 && r.ended === 1 },
  { name: 'prep-star7-L5-two-segments-timeout', desc: '备餐台 ★7 L5，配置成两段（segmentsByStar 可配）：新手到点、两段都打完', bot: 'novice', seedBase: 9500,
    config: { station: ST.PREP, star: 7, chefLevel: 5, motion: MO.MIX, beats: 7 }, configOverride: { segmentsByStar: [1, 1, 1, 1, 1, 2, 2] },
    want: (s, r) => s.end === END.TIMEOUT && s.segIdx === 2 && r.negatives.includes(Z.NEG.UNDERDONE) },
  { name: 'prep-drink-star2-L9-good', desc: '冷饮（备餐台）★2 L9 浮，6 拍等距，熟练玩家', bot: 'good', seedBase: 10000,
    config: { station: ST.PREP, star: 2, chefLevel: 9, drink: true },
    want: (s, r) => s.end === END.DONE && r.beatPerfect >= 3 && r.beatOk >= 1 },
];

const cases = [];
for (const spec of SPECS) {
  const { cfg, inputs } = findCase(spec);
  const params = Z.buildParams(cfg, spec.configOverride);
  const ext = spec.ext || { gold: 0 };
  const tr = Z.replayTrace({ block: params, seed: cfg.seed }, inputs, ext);
  // 回放必须与实时一致
  const live = Z.createGame(cfg); for (const v of inputs) Z.stepInPlace(live, v);
  if (Z.hex32(Z.hashState(live)) !== tr.finalHash) throw new Error('回放 ≠ 实时：' + spec.name);
  if (tr.consumed !== inputs.length || tr.padded) throw new Error('输入长度不符：' + spec.name);
  const init = Z.initState(params, cfg.seed);
  const config = Object.assign({}, cfg); delete config.seed; delete config.config;
  const c = {
    name: spec.name, description: spec.desc, config, seed: cfg.seed,
    params, paramsHash: Z.hex32(Z.paramsHash(params)),
    initHash: Z.hex32(Z.hashState(init)),
    ext,
    inputCount: inputs.length, inputsRle: Z.rleEncode(inputs),
    hashes: tr.hashes, finalHash: tr.finalHash, result: tr.result,
  };
  if (spec.configOverride) c.configOverride = spec.configOverride;
  cases.push(c);
  console.log(`${spec.name}: seed ${cfg.seed} ticks ${inputs.length} end ${tr.result.end} q ${tr.result.quality} score ${tr.result.score} hash ${tr.finalHash}`);
}

// —— 已知答案向量 ——
const mul = [0, 1, -1, 0x12345678, 0x7FFFFFFF, -2147483648].map((a) => {
  const r = new Z.Mulberry32(a);
  return { state: a, next: Array.from({ length: 6 }, () => r.next()), stateAfter: r.a };
});
const rr = new Z.Mulberry32(42);
const rndVec = { state: 42, ops: [] };
for (const [kind, x, y] of [['rnd', 4000], ['range', 10, 44], ['range', 100000, 899999], ['rnd', 2], ['rnd', 3], ['range', 88000, 177759], ['rnd', 1000], ['next']]) {
  const v = kind === 'rnd' ? rr.rnd(x) : kind === 'range' ? rr.range(x, y) : rr.next();
  rndVec.ops.push(kind === 'next' ? ['next', v] : kind === 'rnd' ? ['rnd', x, v] : ['range', x, y, v]);
}
const fmix = [0, 1, -1, 123456789, -987654321, 0x0F00D5, 0x0B0771E, 0x057A7].map((x) => [x, Z.fmix32(x)]);
const streams = [0, 1, -1, 20261010, -123456].map((seed) => ({
  seed, rT: Z.fmix32(seed ^ Z.STREAM_SALT.T), rB: Z.fmix32(seed ^ Z.STREAM_SALT.B), rS: Z.fmix32(seed ^ Z.STREAM_SALT.S),
}));
const fnv = { ints: [0, 1, -1, 2147483647, -2147483648, 1000000], hash: Z.hex32(Z.fnv1aInts([0, 1, -1, 2147483647, -2147483648, 1000000])) };

const golden = {
  simVersion: Z.SIM_VERSION,
  generatedBy: 'gen-golden.mjs',
  format: {
    params: 'int[]，下标含义见 paramFields（A 段 0–31 + B 段）',
    configOverride: '可选：buildParams 的第二个参数（覆盖 DEFAULT_CONFIG 的个别键），Java 端同样覆盖 ZhangshaoConfig 的同名字段',
    ext: '服务端外部判定：gold = 服务端私下抽到的瓶种类（1 金 / 0 普通），回放与结算时传入 result',
    inputsRle: '[[长度, 输入字节], …]，展开即每 tick 一个输入字节（倒计时不含）',
    hashes: '每推进 20 tick（tick = 20, 40, …）记一次 hashState，8 位小写十六进制（无符号）',
    result: 'result(state, {gold, mechanical: detectMechanical(已消耗输入)}) 的全部字段，negatives 为 NEG 码按 0,1,2 升序',
    replay: '按输入逐 tick 推进直到结束；golden 的输入恰好在结束那一 tick 用完',
  },
  paramFields: Z.PARAM_FIELDS,
  hashFields: Z.HASH_FIELDS,
  vectors: { mulberry32: mul, rndRange: rndVec, fmix32: fmix, streams, fnv1a: fnv },
  cases,
};
writeFileSync(new URL('./golden.json', import.meta.url), JSON.stringify(golden, null, 1) + '\n');
console.log(`golden.json: ${cases.length} cases`);
