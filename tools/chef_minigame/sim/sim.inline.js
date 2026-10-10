// 由 build-inline.mjs 从 sim.js 生成，请勿手改。用法：<script src="sim.inline.js"></script> 后访问 window.ZhangshaoSim
(function (root) {
'use strict';
// 掌勺小游戏 · 确定性内核（simVersion 2）
// ---------------------------------------------------------------------------
// 规则来源：../spec.md（§3–§14、§18.2、§18.4、§18.7、§19）。Java 端逐位复刻见 JAVA_PORTING.md。
//
// 约定（违反任何一条，JS 与 Java 就会算岔）：
//   * 所有状态与参数都是 32 位有符号整数；布尔存 0/1。状态推进里没有浮点。
//   * 被除数、除数都非负时用 (a / b) | 0；带符号的除法一律用 tdiv（向零截断）。
//   * 不用 >> 做除法；>>> 只出现在随机数、哈希、a8 位操作里；取模只对非负数做。
//   * 回绕乘法（随机数、哈希）只用 Math.imul；其余乘积都 < 2^31（§19.1）。
//   * 每 tick 的执行顺序严格按 §5，随机数抽签次数与输入无关。
//
// simVersion 2（2026-10-10 审查处理，spec「审查处理记录」）相对 1 的规则改动：
//   * 调料瓶：内核只知道「有没有瓶」，金瓶 / 普通瓶由服务端私下抽、不从种子推出（结算时经 ext.gold 传入）；
//     瓶离食材的距离按火候条长度取（bOffMin/bOffMax），抓取速度与出现时刻随手熟缩放（bFill/bAppear*），都在参数块 A 段。
//   * 烤炉：提示出现前按翻面按「离翻面点还差几 tick（按满速换算）」分档：≤ 3 tick 算正好，≤ 12 tick 轻罚，≤ 20 tick 重罚，更早无效。
//   * 炸锅：油温进入过热区（≥ oilHot）后升温减半（oilHotRiseDiv），漏压一次不再直接爆油。
//   * 参数：时限 300（厨锅不再跟 PACE 拉长）、L1–3 火候条至少 18%、负面门槛 40/120/150、窜的急窜概率减半、
//     过热不再加速、机械节奏 98%/32 段、备餐台首拍提前 16 tick 且 ★6–★7 并成一段、金瓶率下调（见 DEFAULT_CONFIG）。
//
// 本文件不 import 任何东西、不碰 DOM / 时间 / Math.random，可在浏览器和 Node 中运行。
// sim.inline.js 由 build-inline.mjs 从本文件生成（去掉 export，挂到 globalThis.ZhangshaoSim）。
// ---------------------------------------------------------------------------

const SIM_VERSION = 2;
const TICKS_PER_SECOND = 20;
const TICK_MS = 50;
const NONE = -1;

const STATION = Object.freeze({ POT: 0, FRYER: 1, OVEN: 2, PREP: 3 });
const MOTION = Object.freeze({ MIX: 0, DART: 1, STEADY: 2, SINK: 3, FLOAT: 4 });
const END = Object.freeze({ RUNNING: 0, DONE: 1, BURNT: 2, TIMEOUT: 3, ABANDON: 4 });
const QUALITY = Object.freeze({ HOMESTYLE: -1, LOW: 0, MEDIUM: 1, HIGH: 2, EXTRAORDINARY: 3, RADIANT: 4 });
const BOTTLE = Object.freeze({ WAITING: 0, PRESENT: 1, CAUGHT: 2, NONE: 3 });
// 内核状态 bKind 只用 0（本局无瓶）/ 1（有瓶，种类内核不知道）；结算结果 bottleKind 用 0 无 / 1 普通 / 2 金（金由 ext.gold 给出）
const BOTTLE_KIND = Object.freeze({ NONE: 0, NORMAL: 1, GOLD: 2 });
// 负面：SCORCHED 在备餐台显示为「多盐」（§13.4）
const NEG = Object.freeze({ UNDERDONE: 0, SCORCHED: 1, NAUSEA: 2 });
const SIDE = Object.freeze({ IN: 0, UNDER: 1, OVER: 2 });

const K_MAX = 1000000;      // 抓瓶进度满值（§10）
const JIT_DRAWS = 8;        // rS 开局固定抽 8 个节拍抖动（§4.2）

// ---------------------------------------------------------------------------
// 默认配置（ChefConfig [zhangshao]，§20）。数组按 ★1–★7 或 L1–L10 排列。
// ---------------------------------------------------------------------------
const DEFAULT_CONFIG = Object.freeze({
  // §20.1
  track: 1000000,
  // §20.2
  accel: 3960, inBarNum: 6, inBarDen: 10, bounceTopNum: 2, bounceDen: 3, bounceBottomNum: 2,
  pinTicks: 10, heightBase: 150000, heightPerLevel: 11000, heightMin: 180000,
  // §20.3
  starDifficulty: [32, 40, 47, 54, 61, 67, 73], motionOffset: [0, -6, 11, -2, -1],
  fishMin: 100000, fishMax: 900000, sinkLine: 300000, floatLine: 700000, fishSubsteps: 3,
  newDestDiv: 4000, steadyMul: 20, pctRandMin: 10, pctRandMax: 44,
  approachDivMin: 10, approachDivMax: 29, approachRelax: 5, arriveEps: 5280,
  hopChanceDiv: 2000, hopMin: 88000, hopMax: 177759,
  dartChanceDiv: 3000, dartMin: 88000, dartUnit: 1760, driftStep: 18, driftMax: 2640,
  // §20.4
  progressMax: 1000000, progressStart: 250000, gain: 4689, loss: 4689, lossPatience: 3516,
  stationPacePm: [800, 1000, 1000, 1250], stationCapPm: [1000, 1000, 1000, 1250], baseCapTicks: 300,
  flipHoldCap: 990000, perfectGraceTicks: 1,
  // §20.5（offset*Pm：瓶离食材的距离 = 火候条长 H 的千分比）
  normalBase: 250, normalPerLevel: 15, goldBase: 12, goldPerLevel: 3, appearMin: 20, appearMax: 60,
  offsetMinPm: 550, offsetMaxPm: 900, fill: 34000, drain: 30000, drainSharpEye: 15000, scoreBonus: 40,
  // §20.6
  perkSteadyHandsLevel: 3, perkPatienceLevel: 6, perkSharpEyeLevel: 9,
  // §20.7 厨锅
  heatStart: 300, heatUp: 30, heatDown: 80, heatMax: 1000, heatMulBase: 700, heatMulSpan: 600,
  penDiv: 8, underDiv: 6,
  // §20.8 炸锅
  oilStart: 450, oilRiseIn: 4, oilRiseOut: 3, oilRiseStarDiv: 3, oilRiseProgDiv: 300000, oilHotRiseDiv: 2, oilDrop: 280,
  oilCdByLevel: [34, 33, 32, 31, 30, 29, 28, 27, 26, 25],
  oilCold: 300, oilHot: 750, oilBurst: 1000, oilAfterBurst: 550, coldMulPm: 600, hotMulPm: 1000,
  burstProgressLoss: 60000, burstKick: 8333, penHotDiv: 3, penColdDiv: 3, penBurst: 80,
  negHotDiv: 2, negColdDiv: 2, negBurst: 150, cleanTicks: 20,
  // §20.9 烤炉（flipEarly*Ticks：离翻面点还差几 tick 的熟度，按本局满速涨速换算）
  flipsDefault: 1, flipGoodByLevel: [8, 8, 9, 9, 10, 10, 11, 11, 12, 13],
  flipLate: 12, flipAuto: 80, flipEarlyGoodTicks: 3, flipEarlyMildTicks: 12, flipEarlyZoneTicks: 20,
  burnMulPm: 500, flipKickBase: 4000, flipKickPerStar: 500,
  penLate: 25, penEarlyMild: 25, penEarly: 60, penBurntBase: 100, penBurntCap: 200, penAuto: 200,
  negEarlyMildUnder: 60, negEarlyUnder: 120, negLateOver: 60, negBurntBase: 150, negBurntPerTick: 3, negBurntCap: 300, negAutoOver: 300,
  // §20.10 备餐台
  segmentsByStar: [1, 1, 1, 1, 1, 1, 1], beatsMin: 3, beatsMax: 8, beatsDefault: 5,
  beatGapByStar: [14, 13, 12, 11, 10, 9, 8], beatJitter: [-2, 0, 2], beatGapMin: 7,
  beatLead: 16, beatTail: 8, beatPerfectW: 1, beatGoodWByLevel: [2, 2, 2, 2, 3, 3, 3, 3, 3, 3],
  penOk: 8, penMiss: 50, penMash: 20, negMissPm: 500, negMash: 40, drinkBeats: 6, drinkGapDelta: -2,
  // §20.11 评分（品质用 QUALITY 的整数码）
  tierExtraordinary: 960, tierHigh: 890, tierMedium: 760,
  timeoutCap: 1, batchCap: 2, slowCap: 2, mechanicalCap: 1,
  // §20.12 负面
  thresholdLow: 40, thresholdMedium: 120, thresholdHigh: 150, maxLow: 2, maxMedium: 1, maxHigh: 1,
  // §20.13（只取内核/结算用到的）
  masteryStartBonus: 20000, masteryGainPm: 80, batchPacePm: [1000, 750, 620, 540, 480, 440],
  mechanicalMinRuns: 32, mechanicalSamePct: 100, suspectCapRadiant: 40, suspectCapHigh: 80,
});

// ---------------------------------------------------------------------------
// 参数块（§18.4）。A 段 32 个本局派生量；B 段是内核/结算读取的规则常量，
// 按 §20 各表自上而下的顺序从 #32 起追加（已在 A 段解析掉的按级/按星数组和瓶参数不重复放）。
// ---------------------------------------------------------------------------
const A_FIELDS = Object.freeze([
  'simVersion', 'station', 'star', 'level', 'motion', 'D', 'H', 'P0', 'GAIN', 'LOSS',
  'PACE', 'MAST', 'N', 'BPACE', 'CAP', 'bottlePm', 'goldPm', 'drainPm', 'BB', 'F',
  'flipGood', 'oilCd', 'beats', 'segs', 'beatGap', 'jitterOn', 'goodW',
  'bFill', 'bAppearMin', 'bAppearMax', 'bOffMin', 'bOffMax',
]);
const B_FIELDS = Object.freeze([
  // §20.1–20.2
  'track', 'accel', 'inBarNum', 'inBarDen', 'bounceTopNum', 'bounceDen', 'pinTicks',
  // §20.3
  'fishMin', 'fishMax', 'sinkLine', 'floatLine', 'fishSubsteps', 'newDestDiv', 'steadyMul',
  'pctRandMin', 'pctRandMax', 'approachDivMin', 'approachDivMax', 'approachRelax', 'arriveEps',
  'hopChanceDiv', 'hopMin', 'hopMax', 'dartChanceDiv', 'dartMin', 'dartUnit', 'driftStep', 'driftMax',
  // §20.4
  'progressMax', 'flipHoldCap', 'perfectGraceTicks',
  // §20.5
  'scoreBonus',
  // §20.7
  'heatStart', 'heatUp', 'heatDown', 'heatMax', 'heatMulBase', 'heatMulSpan', 'penDiv', 'underDiv',
  // §20.8
  'oilStart', 'oilRiseIn', 'oilRiseOut', 'oilRiseStarDiv', 'oilRiseProgDiv', 'oilHotRiseDiv', 'oilDrop',
  'oilCold', 'oilHot', 'oilBurst', 'oilAfterBurst', 'coldMulPm', 'hotMulPm', 'burstProgressLoss', 'burstKick',
  'penHotDiv', 'penColdDiv', 'penBurst', 'negHotDiv', 'negColdDiv', 'negBurst', 'cleanTicks',
  // §20.9
  'flipLate', 'flipAuto', 'flipEarlyGoodTicks', 'flipEarlyMildTicks', 'flipEarlyZoneTicks',
  'burnMulPm', 'flipKickBase', 'flipKickPerStar',
  'penLate', 'penEarlyMild', 'penEarly', 'penBurntBase', 'penBurntCap', 'penAuto',
  'negEarlyMildUnder', 'negEarlyUnder', 'negLateOver', 'negBurntBase', 'negBurntPerTick', 'negBurntCap', 'negAutoOver',
  // §20.10
  'beatJitter0', 'beatJitter1', 'beatJitter2', 'beatGapMin', 'beatLead', 'beatTail', 'beatPerfectW',
  'penOk', 'penMiss', 'penMash', 'negMissPm', 'negMash',
  // §20.11
  'tierExtraordinary', 'tierHigh', 'tierMedium', 'timeoutCap', 'batchCap', 'slowCap', 'mechanicalCap',
  // §20.12
  'thresholdLow', 'thresholdMedium', 'thresholdHigh', 'maxLow', 'maxMedium', 'maxHigh',
  // §20.13
  'mechanicalMinRuns', 'mechanicalSamePct', 'suspectCapRadiant', 'suspectCapHigh',
]);
const PARAM_FIELDS = Object.freeze(A_FIELDS.concat(B_FIELDS));
const PARAM_COUNT = PARAM_FIELDS.length;

// ---------------------------------------------------------------------------
// 整数工具
// ---------------------------------------------------------------------------
/** 带符号除法，向零截断（§3.3）。要求 b > 0。 */
function tdiv(a, b) {
  return a >= 0 ? (a / b) | 0 : (-((-a / b) | 0)) | 0;
}
function imin(a, b) { return a < b ? a : b; }
function imax(a, b) { return a > b ? a : b; }
function iabs(a) { return a < 0 ? (-a) | 0 : a; }
function clamp(v, lo, hi) { return v < lo ? lo : v > hi ? hi : v; }

/** fmix32（MurmurHash3 终结器，§18.2）。 */
function fmix32(h) {
  h |= 0;
  h ^= h >>> 16; h = Math.imul(h, 0x85EBCA6B);
  h ^= h >>> 13; h = Math.imul(h, 0xC2B2AE35);
  h ^= h >>> 16;
  return h | 0;
}

/** 子流种子派生（§18.2）。 */
const STREAM_SALT = Object.freeze({ T: 0x0F00D5, B: 0x0B0771E, S: 0x057A7 });

// Mulberry32：状态是一个 int32。内核内部用模块级寄存器 _a 以免分配对象。
let _a = 0;
function next() {
  _a = (_a + 0x6D2B79F5) | 0;
  let t = Math.imul(_a ^ (_a >>> 15), 1 | _a);
  t = ((t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t) | 0;
  return (t ^ (t >>> 14)) | 0;
}
function rnd(n) { return ((next() >>> 1) % n) | 0; }
function range(lo, hi) { return (lo + rnd(hi - lo + 1)) | 0; }

/** 独立的 Mulberry32 对象（测试与文档用；内核不用它）。 */
class Mulberry32 {
  constructor(state) { this.a = state | 0; }
  next() {
    this.a = (this.a + 0x6D2B79F5) | 0;
    let t = Math.imul(this.a ^ (this.a >>> 15), 1 | this.a);
    t = ((t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t) | 0;
    return (t ^ (t >>> 14)) | 0;
  }
  rnd(n) { return ((this.next() >>> 1) % n) | 0; }
  range(lo, hi) { return (lo + this.rnd(hi - lo + 1)) | 0; }
}

/** 逐字 FNV-1a 32（§19.3）：h ^= v; h = imul(h, 0x01000193)。返回 int32。 */
function fnv1aInts(ints) {
  let h = 0x811C9DC5 | 0;
  for (let i = 0; i < ints.length; i++) { h ^= ints[i] | 0; h = Math.imul(h, 0x01000193); }
  return h | 0;
}
/** int32 → 8 位小写十六进制（无符号），即 Java String.format("%08x", h)。 */
function hex32(h) { return (h >>> 0).toString(16).padStart(8, '0'); }

// ---------------------------------------------------------------------------
// 参数块
// ---------------------------------------------------------------------------
/** 没指定性子时的默认值：炸锅浮、冷饮浮，其余混（§13.6 的「其余」）。 */
function defaultMotion(station, drink) {
  if (station === STATION.FRYER) return MOTION.FLOAT;
  if (station === STATION.PREP && drink) return MOTION.FLOAT;
  return MOTION.MIX;
}

function mergeConfig(cfg) {
  if (!cfg || cfg === DEFAULT_CONFIG) return DEFAULT_CONFIG;
  return Object.assign({}, DEFAULT_CONFIG, cfg);
}

function checkInt(name, v, lo, hi) {
  if (!Number.isInteger(v) || v < lo || v > hi) throw new RangeError(`${name} = ${v} 不在 [${lo}, ${hi}]`);
  return v;
}

/**
 * 由开局选项算出参数块 int[]（服务端在开局时做的事，§18.4）。
 * opts: { station, star, chefLevel, motion?, mastery?, batch?, flips?, beats?, drink?,
 *         goldPm?, normalPm?, goldBlocked? }
 * 瓶：bottlePm = 普通瓶‰ + 金瓶‰（封顶 1000），内核只用它决定「有没有瓶」；
 *     goldPm 是金瓶占全部局的‰，服务端按 goldPm / bottlePm 私下抽这只瓶是不是金瓶（§10），内核不读。
 */
function buildParams(opts, config) {
  const c = mergeConfig(config);
  const station = checkInt('station', opts.station, 0, 3);
  const star = checkInt('star', opts.star, 1, 7);
  const L = checkInt('chefLevel', opts.chefLevel ?? opts.level, 1, 10);
  const mastery = checkInt('mastery', opts.mastery ?? 0, 0, 5);
  const N = checkInt('batch', opts.batch ?? 1, 1, c.batchPacePm.length);
  const drink = station === STATION.PREP && !!opts.drink;
  const motion = checkInt('motion', opts.motion ?? defaultMotion(station, drink), 0, 4);
  const isFry = station === STATION.FRYER, isOven = station === STATION.OVEN, isPrep = station === STATION.PREP;

  const PACE = c.stationPacePm[station];
  const BPACE = c.batchPacePm[N - 1];
  const MAST = 1000 + c.masteryGainPm * mastery;
  const H = imax(c.heightMin, c.heightBase + c.heightPerLevel * (L - 1));
  const normal = opts.normalPm ?? (c.normalBase + c.normalPerLevel * (L - 1));
  const gold = (N > 1 || opts.goldBlocked) ? 0 : (opts.goldPm ?? (c.goldBase + c.goldPerLevel * (L - 1)));
  const bottlePm = imin(1000, normal + gold);
  const A = {
    simVersion: SIM_VERSION,
    station, star, level: L, motion,
    D: clamp(c.starDifficulty[star - 1] + c.motionOffset[motion], 0, 100),
    H,
    P0: c.progressStart + c.masteryStartBonus * mastery,
    GAIN: c.gain,
    LOSS: L >= c.perkPatienceLevel ? c.lossPatience : c.loss,
    PACE, MAST, N, BPACE,
    CAP: ((c.baseCapTicks * 1000000) / (c.stationCapPm[station] * BPACE)) | 0,
    bottlePm,
    goldPm: imin(gold, bottlePm),
    drainPm: L >= c.perkSharpEyeLevel ? c.drainSharpEye : c.drain,
    BB: L >= c.perkSteadyHandsLevel ? 1 : c.bounceBottomNum,
    F: isOven ? checkInt('flips', opts.flips ?? c.flipsDefault, 1, 8) : 0,
    flipGood: isOven ? c.flipGoodByLevel[L - 1] : 0,
    oilCd: isFry ? c.oilCdByLevel[L - 1] : 0,
    beats: isPrep ? (drink ? c.drinkBeats : clamp(opts.beats ?? c.beatsDefault, c.beatsMin, c.beatsMax)) : 0,
    segs: isPrep ? c.segmentsByStar[star - 1] : 0,
    beatGap: isPrep ? c.beatGapByStar[star - 1] + (drink ? c.drinkGapDelta : 0) : 0,
    jitterOn: isPrep ? (drink ? 0 : 1) : 0,
    goodW: isPrep ? c.beatGoodWByLevel[L - 1] : 0,
    bFill: ((c.fill * MAST) / 1000) | 0,
    bAppearMin: ((c.appearMin * 1000) / MAST) | 0,
    bAppearMax: ((c.appearMax * 1000) / MAST) | 0,
    bOffMin: ((H * c.offsetMinPm) / 1000) | 0,
    bOffMax: ((H * c.offsetMaxPm) / 1000) | 0,
  };
  const B = Object.assign({}, c, {
    beatJitter0: c.beatJitter[0], beatJitter1: c.beatJitter[1], beatJitter2: c.beatJitter[2],
  });
  const block = new Array(PARAM_COUNT);
  for (let i = 0; i < A_FIELDS.length; i++) block[i] = A[A_FIELDS[i]] | 0;
  for (let i = 0; i < B_FIELDS.length; i++) {
    const v = B[B_FIELDS[i]];
    if (!Number.isInteger(v)) throw new TypeError(`配置键 ${B_FIELDS[i]} 不是整数: ${v}`);
    block[A_FIELDS.length + i] = v | 0;
  }
  return block;
}

/** 参数块 → 只读命名对象（内核只读它，不读配置）。 */
function decodeParams(block) {
  if (!block || block.length !== PARAM_COUNT) throw new RangeError(`参数块长度应为 ${PARAM_COUNT}`);
  if ((block[0] | 0) !== SIM_VERSION) throw new RangeError(`不认识的 simVersion ${block[0]}`);
  const p = {};
  for (let i = 0; i < PARAM_COUNT; i++) p[PARAM_FIELDS[i]] = block[i] | 0;
  p.beatJitter = Object.freeze([p.beatJitter0, p.beatJitter1, p.beatJitter2]);
  return Object.freeze(p);
}

/** 参数哈希：对整个 int[] 做逐字 FNV-1a（§18.4）。 */
function paramsHash(block) { return fnv1aInts(block); }

/** 加载时跨键校验（§20.14 的子集），不满足抛错。 */
function validateConfig(config) {
  const c = mergeConfig(config);
  const errs = [];
  const need = (ok, msg) => { if (!ok) errs.push(msg); };
  need(c.tierMedium < c.tierHigh && c.tierHigh < c.tierExtraordinary && c.tierExtraordinary <= 1000, '分数线顺序');
  need(c.thresholdLow <= c.thresholdMedium && c.thresholdMedium <= c.thresholdHigh, '负面门槛顺序');
  need(c.fishMin < c.sinkLine && c.sinkLine < c.floatLine && c.floatLine < c.fishMax, '食材范围与沉浮线');
  need(c.heightMin <= c.heightBase + c.heightPerLevel * 9, 'heightMin 不能超过 10 级火候条');
  need(imax(c.heightMin, c.heightBase + c.heightPerLevel * 9) < c.fishMax - c.fishMin, '10 级火候条过长');
  need(c.hopMin < c.hopMax, 'hopMin < hopMax');
  need(c.dartMin < (101 + 2 * 0) * c.dartUnit, 'dartMin 过大');
  need(c.appearMax + 30 <= 128, 'appearMax 过晚');
  need(c.offsetMinPm > 0 && c.offsetMinPm <= c.offsetMaxPm && c.offsetMaxPm <= 1000, '瓶偏移（火候条长的千分比）');
  need(c.oilCold < c.oilHot && c.oilHot < c.oilBurst && c.oilAfterBurst >= c.oilCold && c.oilAfterBurst < c.oilBurst, '油温区间');
  need(c.oilHotRiseDiv >= 1, 'oilHotRiseDiv ≥ 1');
  need(c.flipLate >= 0 && c.flipAuto > c.flipGoodByLevel[9] + c.flipLate, '翻面窗口');
  need(c.flipEarlyGoodTicks >= 0 && c.flipEarlyGoodTicks <= c.flipEarlyMildTicks && c.flipEarlyMildTicks <= c.flipEarlyZoneTicks, '翻早分档');
  need(c.beatGapMin >= 2 * c.beatGoodWByLevel[9] + 1, '节拍窗重叠');
  need(c.beatLead > c.beatGoodWByLevel[9], '首拍提前量要大于判定窗');
  need(c.gain > 0 && c.loss > 0 && c.lossPatience > 0, 'gain/loss > 0');
  need(c.beatJitter.length === 3, 'beatJitter 必须 3 个');
  need(c.beatsMax <= JIT_DRAWS, 'beatsMax ≤ 8');
  need(c.segmentsByStar.every((v) => v === 1 || v === 2), '段数只能 1 或 2');
  need(c.fishSubsteps >= 1, 'fishSubsteps ≥ 1');
  need(c.stationCapPm.every((v) => v >= 1 && v <= 5000) && c.stationPacePm.every((v) => v >= 1 && v <= 5000), '*Pm 范围');
  // 中间积 < 2^31（§19.1）
  need((c.fishMax - c.fishMin) * 99 < 2147483647, '(a3−yt)×pct 溢出');
  need(c.gain * (c.heatMulBase + c.heatMulSpan) < 2147483647 && c.gain * c.hotMulPm < 2147483647, 'GAIN×mul 溢出');
  need(c.baseCapTicks * 1000000 < 2147483647, 'CAP 分子溢出');
  need(c.fishMax * c.offsetMaxPm < 2147483647, 'H×offsetPm 溢出');
  if (errs.length) throw new RangeError('配置校验失败: ' + errs.join('; '));
  return true;
}

// ---------------------------------------------------------------------------
// 开局（§4）
// ---------------------------------------------------------------------------
/** 由参数块 + 种子建立初始状态。所有开局抽签都在这里，固定次数、固定顺序。 */
function initState(block, seed) {
  const p = decodeParams(block);
  seed |= 0;
  const s = {
    // 只读部分（克隆时共享）
    p, block, seed,
    bAt: 0, bOff: 0, bDir: 0, kickBits: 0, jit: null, segBeats: null,
    // —— 以下为状态（hashState 的字段顺序见 HASH_FIELDS）——
    tick: 0, pt: 0, end: END.RUNNING, inSeg: 0, segIdx: 0, segT: 0, bi: 0,
    yb: ((p.track - p.H) / 2) | 0, vb: 0, inPrev: 1, pin: 0,
    yt: (p.track / 2) | 0, vt: 0, tgt: NONE, ad: 0,
    P: p.P0, S: 0, I: 0, U: 0, O: 0, outRun: 0, perfect: 1,
    bKind: BOTTLE_KIND.NONE, bState: BOTTLE.NONE, bPos: 0, K: 0,
    h: p.heatStart, hSum: 0,
    T: p.oilStart, cd: 0, hot: 0, cold: 0, bursts: 0,
    flipIdx: 0, prompt: -1, burn: 0, flipPen: 0, flipUnder: 0, flipOver: 0, flipAllGood: 1,
    perfB: 0, okB: 0, miss: 0, mash: 0, kickN: 0,
    rTa: 0, rBa: 0, rSa: 0,
    // 不进哈希的展示计数（结算屏用，结果里会输出）
    nGood: 0, nLate: 0, nEarly: 0, nBurnt: 0, nAuto: 0,
  };
  // rT：开局第一跳
  _a = fmix32(seed ^ STREAM_SALT.T);
  s.tgt = range(p.fishMin, p.fishMax - 1);
  s.rTa = _a;
  // rB：调料瓶，固定 4 个（有没有瓶、出现时刻、离食材多远、方向；种类不在这里抽）
  _a = fmix32(seed ^ STREAM_SALT.B);
  const bottleRoll = rnd(1000);
  s.bAt = range(p.bAppearMin, p.bAppearMax);
  s.bOff = range(p.bOffMin, p.bOffMax);
  s.bDir = rnd(2);
  s.rBa = _a;
  if (bottleRoll < p.bottlePm) s.bKind = BOTTLE_KIND.NORMAL; // 1 = 有瓶（种类未知）
  s.bState = s.bKind === BOTTLE_KIND.NONE ? BOTTLE.NONE : BOTTLE.WAITING;
  // rS：8 个节拍抖动 + 溅射方向掩码，任何台子都抽满
  _a = fmix32(seed ^ STREAM_SALT.S);
  const jit = new Array(JIT_DRAWS);
  for (let k = 0; k < JIT_DRAWS; k++) jit[k] = p.beatJitter[rnd(3)];
  s.kickBits = next();
  s.rSa = _a;
  s.jit = Object.freeze(jit);
  // 备餐台节拍表（段内相对时刻，§13.4）
  const segBeats = [];
  if (p.station === STATION.PREP) {
    const n = p.beats;
    const sizes = p.segs === 2 ? [((n + 1) / 2) | 0, n - (((n + 1) / 2) | 0)] : [n];
    let k = 0;
    for (let si = 0; si < sizes.length; si++) {
      const times = [];
      let at = p.beatLead;
      for (let i = 0; i < sizes[si]; i++) {
        if (i > 0) at += imax(p.beatGapMin, p.beatGap + (p.jitterOn ? jit[k] : 0));
        times.push(at);
        k++;
      }
      segBeats.push(Object.freeze(times));
    }
  }
  s.segBeats = Object.freeze(segBeats);
  return s;
}

/** createGame({station, star, chefLevel, seed, ...}) → 初始状态。 */
function createGame(opts) {
  const block = opts.block ? opts.block.slice() : buildParams(opts, opts.config);
  return initState(block, opts.seed | 0);
}

/** 浅克隆（只读部分共享）。 */
function cloneState(s) { return Object.assign({}, s); }

// ---------------------------------------------------------------------------
// 每 tick（§5）
// ---------------------------------------------------------------------------
/** 纯函数版：返回新状态，不改 s。 */
function step(s, input) { return stepInPlace(cloneState(s), input); }

function kick(s, amt) {
  const up = (s.kickBits >>> (s.kickN & 31)) & 1;
  s.kickN = (s.kickN + 1) | 0;
  s.vt = (up ? s.vt + amt : s.vt - amt) | 0;
}

/** 翻面点 / 摆料点：P0 + (progressMax − P0) × k / (n + 1)。 */
function markAt(p, k, n) {
  return (p.P0 + (((p.progressMax - p.P0) * k) / (n + 1)) | 0) | 0;
}

/** 满速涨速（框住、本台倍率 1000 时每 tick 涨多少熟度），与 §5 ⑦ 同一条截断链。烤炉翻早分档用它把熟度差换算成 tick。 */
function fullGain(p) {
  let g = p.GAIN;
  g = (g * p.PACE / 1000) | 0;
  g = (g * p.MAST / 1000) | 0;
  g = (g * p.BPACE / 1000) | 0;
  return g;
}

function fishSub(s, p) {
  _a = s.rTa;
  const a1 = rnd(p.newDestDiv);
  const a2 = range(p.pctRandMin, p.pctRandMax);
  const a3 = range(p.fishMin, p.fishMax - 1);
  const a4 = range(p.approachDivMin, p.approachDivMax);
  const a5 = rnd(p.hopChanceDiv);
  const a6 = range(p.hopMin, p.hopMax);
  const a7 = rnd(p.dartChanceDiv);
  const a8 = next();
  s.rTa = _a;

  const D = p.D, M = p.motion;
  let yt = s.yt, vt = s.vt, tgt = s.tgt, ad = s.ad;
  // 1 换目的地
  const mul = M === MOTION.STEADY ? p.steadyMul : 1;
  if ((M !== MOTION.STEADY || tgt === NONE) && a1 < D * mul) {
    const pct = imin(99, D + a2);
    tgt = (yt + tdiv((a3 - yt) * pct, 100)) | 0;
  }
  // 2 沉 / 浮漂移
  if (M === MOTION.SINK) ad = yt > p.sinkLine ? imax(ad - p.driftStep, -p.driftMax) : 0;
  else if (M === MOTION.FLOAT) ad = yt < p.floatLine ? imin(ad + p.driftStep, p.driftMax) : 0;
  // 3 追目的地 / 小跳 / 滑行
  if (tgt !== NONE && iabs(yt - tgt) > p.arriveEps) {
    const div = a4 + 100 - imin(100, D);
    const acc = tdiv(tgt - yt, div);
    vt = (vt + tdiv(acc - vt, p.approachRelax)) | 0;
  } else if (M !== MOTION.STEADY && a5 < D) {
    tgt = (a8 & 1) ? (yt + a6) | 0 : (yt - a6) | 0;
  } else {
    tgt = NONE;
  }
  // 4 窜：急窜
  if (M === MOTION.DART && a7 < D) {
    const dmax = (101 + 2 * D) * p.dartUnit;
    const size = p.dartMin + ((a8 >>> 2) % (dmax - p.dartMin));
    tgt = ((a8 >>> 1) & 1) ? (yt + size) | 0 : (yt - size) | 0;
  }
  // 5 夹取与移动（注意：tgt 恰好算出 −1 时与 NONE 同值，按规格原样处理，见 JAVA_PORTING.md）
  if (tgt !== NONE) tgt = clamp(tgt, p.fishMin, p.fishMax);
  yt = (yt + vt + ad) | 0;
  if (yt < p.fishMin) { yt = p.fishMin; vt = 0; }
  else if (yt > p.fishMax) { yt = p.fishMax; vt = 0; }
  s.yt = yt; s.vt = vt; s.tgt = tgt; s.ad = ad;
}

function prepSegmentTick(s, p, act) {
  const Tm = s.segBeats[s.segIdx], c = Tm.length, gw = p.goodW, t = s.segT;
  if (s.bi < c && t > Tm[s.bi] + gw) { s.miss++; s.bi++; }              // 漏拍
  if (act && s.bi < c) {
    const d = iabs(t - Tm[s.bi]);
    if (d <= p.beatPerfectW) { s.perfB++; s.bi++; }                     // 正好
    else if (d <= gw) { s.okB++; s.bi++; }                               // 可以
    else { s.mash++; }                                                   // 乱按
  }
  s.segT++;
  if (s.segT >= Tm[c - 1] + gw + p.beatTail + 1) { s.inSeg = 0; s.segIdx++; }
}

function ovenFlipInput(s, p) {
  const kickAmt = p.flipKickBase + p.flipKickPerStar * p.star;
  if (s.prompt >= 0) {
    const dt = s.pt - s.prompt;
    if (dt <= p.flipGood) { s.nGood++; }
    else if (dt <= p.flipGood + p.flipLate) {
      s.nLate++; s.flipPen += p.penLate; s.flipOver += p.negLateOver; s.flipAllGood = 0;
    } else {
      s.nBurnt++;
      s.flipPen += imin(p.penBurntCap, p.penBurntBase + s.burn);
      s.flipOver += imin(p.negBurntCap, p.negBurntBase + p.negBurntPerTick * s.burn);
      s.flipAllGood = 0;
    }
    s.flipIdx++; s.prompt = -1; s.burn = 0; kick(s, kickAmt);
  } else if (s.flipIdx < p.F) {
    // 提示还没出：按「离翻面点还差几 tick」分档（熟度差 ÷ 满速涨速）
    const d = markAt(p, s.flipIdx + 1, p.F) - s.P;
    const g0 = fullGain(p);
    if (d <= g0 * p.flipEarlyGoodTicks) {                                // 差 ≤ 3 tick：算正好（这个翻面点不再出提示）
      s.nGood++; s.flipIdx++; kick(s, kickAmt);
    } else if (d <= g0 * p.flipEarlyMildTicks) {                         // 差 4–12 tick：翻早（轻）
      s.nEarly++; s.flipPen += p.penEarlyMild; s.flipUnder += p.negEarlyMildUnder; s.flipAllGood = 0;
      s.flipIdx++; kick(s, kickAmt);
    } else if (d <= g0 * p.flipEarlyZoneTicks) {                         // 差 13–20 tick：翻早（重）
      s.nEarly++; s.flipPen += p.penEarly; s.flipUnder += p.negEarlyUnder; s.flipAllGood = 0;
      s.flipIdx++; kick(s, kickAmt);
    }
    // 更早按：无效、不罚（防误触）
  }
}

/** 推进 1 tick（就地修改并返回 s）。input：bit0 hold，bit1 act，其余位忽略（合法性由 replay 校验）。 */
function stepInPlace(s, input) {
  if (s.end !== END.RUNNING) return s;
  const p = s.p;
  const hold = input & 1, act = (input >> 1) & 1;

  // ⓪ 备餐台摆料段
  if (s.inSeg) { prepSegmentTick(s, p, act); s.tick++; return s; }

  const st = p.station;
  // ① stationPre
  if (st === STATION.FRYER) {
    if (s.cd > 0) s.cd--;
    if (act && s.cd === 0) { s.T = imax(0, s.T - p.oilDrop); s.cd = p.oilCd; }
  } else if (st === STATION.OVEN) {
    if (act) ovenFlipInput(s, p);
  }

  // ② 火候条
  const H = p.H, top = p.track - H;
  if (hold && (s.yb === 0 || s.yb === top)) s.vb = 0;
  let a = hold ? p.accel : -p.accel;
  if (s.inPrev) a = tdiv(a * p.inBarNum, p.inBarDen);
  s.vb = (s.vb + a) | 0;
  s.yb = (s.yb + s.vb) | 0;
  if (s.yb > top) { s.yb = top; s.vb = (-tdiv(s.vb * p.bounceTopNum, p.bounceDen)) | 0; }
  else if (s.yb < 0) { s.yb = 0; s.vb = (-tdiv(s.vb * p.BB, p.bounceDen)) | 0; }
  s.pin = (s.yb === 0 || s.yb === top) ? s.pin + 1 : 0;
  const pinned = s.pin >= p.pinTicks;

  // ③ 食材
  for (let k = 0; k < p.fishSubsteps; k++) fishSub(s, p);

  // ④ 判定
  const geo = s.yb <= s.yt && s.yt <= s.yb + H;
  const inBar = geo && !pinned;
  let side;
  if (inBar) side = SIDE.IN;
  else if (s.yt > s.yb + H) side = SIDE.UNDER;
  else if (s.yt < s.yb) side = SIDE.OVER;
  else side = s.yb === 0 ? SIDE.UNDER : SIDE.OVER;

  // ⑤ 调料瓶
  let bIn = 0;
  if (s.bState === BOTTLE.WAITING && s.pt === s.bAt) {
    const off = s.bDir ? s.bOff : -s.bOff;
    let pos = s.yt + off;
    if (pos < p.fishMin || pos > p.fishMax) pos = s.yt - off;
    s.bPos = clamp(pos, p.fishMin, p.fishMax);
    s.bState = BOTTLE.PRESENT;
  }
  if (s.bState === BOTTLE.PRESENT) {
    bIn = (!pinned && s.yb <= s.bPos && s.bPos <= s.yb + H) ? 1 : 0;
    if (bIn) {
      s.K += p.bFill;
      if (s.K >= K_MAX) { s.K = K_MAX; s.bState = BOTTLE.CAUGHT; }
    } else {
      s.K = imax(0, s.K - p.drainPm);
    }
  }

  // ⑥ stationMid
  let mul = 1000, burst = 0;
  if (st === STATION.POT) {
    s.h = inBar ? imin(p.heatMax, s.h + p.heatUp) : imax(0, s.h - p.heatDown);
    mul = p.heatMulBase + ((s.h * p.heatMulSpan / 1000) | 0);
  } else if (st === STATION.FRYER) {
    let rise = (inBar ? p.oilRiseIn : p.oilRiseOut) + (((p.star - 1) / p.oilRiseStarDiv) | 0) + ((s.P / p.oilRiseProgDiv) | 0);
    if (s.T >= p.oilHot) rise = (rise / p.oilHotRiseDiv) | 0;           // 过热区升温放缓：漏压一次是斜坡、不是悬崖
    s.T += rise;
    if (s.T >= p.oilBurst) {
      s.T = p.oilAfterBurst; s.bursts++; burst = 1; kick(s, p.burstKick);
    } else if (s.T >= p.oilHot) {
      s.hot++; mul = p.hotMulPm;
    } else if (s.T < p.oilCold) {
      s.cold++; mul = p.coldMulPm;
    }
  } else if (st === STATION.OVEN) {
    if (s.prompt >= 0 && s.pt - s.prompt > p.flipGood + p.flipLate) { s.burn++; mul = p.burnMulPm; }
  }

  // ⑦ 熟度
  let g = p.GAIN;
  g = (g * mul / 1000) | 0;
  g = (g * p.PACE / 1000) | 0;
  g = (g * p.MAST / 1000) | 0;
  g = (g * p.BPACE / 1000) | 0;
  let l = p.LOSS;
  l = (l * p.PACE / 1000) | 0;
  l = (l * p.BPACE / 1000) | 0;
  if (inBar) s.P += g;
  else if (s.bState === BOTTLE.PRESENT && bIn) { /* 瓶护：不变 */ }
  else s.P -= l;
  if (burst) s.P -= p.burstProgressLoss;
  if (st === STATION.OVEN && s.flipIdx < p.F) s.P = imin(s.P, p.flipHoldCap);

  // ⑧ 统计
  s.S++;
  if (inBar) { s.I++; s.outRun = 0; }
  else {
    if (side === SIDE.UNDER) s.U++; else s.O++;
    s.outRun++;
    if (s.outRun > p.perfectGraceTicks) s.perfect = 0;
  }
  if (st === STATION.POT) s.hSum += s.h;

  // ⑨ stationPost
  if (st === STATION.OVEN) {
    if (s.prompt >= 0 && s.pt - s.prompt >= p.flipAuto) {
      s.nAuto++; s.flipPen += p.penAuto; s.flipOver += p.negAutoOver; s.flipAllGood = 0;
      s.flipIdx++; s.prompt = -1; s.burn = 0;
      kick(s, p.flipKickBase + p.flipKickPerStar * p.star);
    }
    if (s.prompt < 0 && s.flipIdx < p.F && s.P >= markAt(p, s.flipIdx + 1, p.F)) s.prompt = s.pt + 1;
  } else if (st === STATION.PREP) {
    if (s.segIdx < p.segs && s.P >= markAt(p, s.segIdx + 1, p.segs)) { s.inSeg = 1; s.segT = 0; s.bi = 0; }
  }

  // ⑩ 收尾
  s.inPrev = inBar ? 1 : 0;
  s.pt++; s.tick++;
  if (s.P >= p.progressMax) { s.P = p.progressMax; s.end = END.DONE; }
  else if (s.P <= 0) { s.P = 0; s.end = END.BURNT; }
  else if (s.pt >= p.CAP) { s.end = END.TIMEOUT; }
  return s;
}

/** 撂勺（服务端在 tick 之间判定，§12）。纯函数。 */
function abandon(s) {
  const n = cloneState(s);
  if (n.end === END.RUNNING) n.end = END.ABANDON;
  return n;
}

// ---------------------------------------------------------------------------
// 状态哈希（§19.3）
// ---------------------------------------------------------------------------
const HASH_FIELDS = Object.freeze([
  'tick', 'pt', 'end', 'inSeg', 'segIdx', 'segT', 'bi', 'yb', 'vb', 'inPrev', 'pin', 'yt', 'vt', 'tgt', 'ad',
  'P', 'S', 'I', 'U', 'O', 'outRun', 'perfect', 'bKind', 'bState', 'bPos', 'K', 'h', 'hSum', 'T', 'cd', 'hot',
  'cold', 'bursts', 'flipIdx', 'prompt', 'burn', 'flipPen', 'flipUnder', 'flipOver', 'flipAllGood',
  'perfB', 'okB', 'miss', 'mash', 'kickN', 'rTa', 'rBa', 'rSa',
]);
function hashState(s) {
  let h = 0x811C9DC5 | 0;
  for (let i = 0; i < HASH_FIELDS.length; i++) { h ^= s[HASH_FIELDS[i]] | 0; h = Math.imul(h, 0x01000193); }
  return h | 0;
}

// ---------------------------------------------------------------------------
// 机械节奏检测（§18.7 本局规则）：对已消耗输入的 hold 位做游程
// ---------------------------------------------------------------------------
function detectMechanical(inputs, p, count) {
  const n = count === undefined ? inputs.length : count;
  if (n <= 0) return 0;
  // 游程
  const bits = [], lens = [];
  let cur = inputs[0] & 1, len = 0;
  for (let i = 0; i < n; i++) {
    const b = inputs[i] & 1;
    if (b === cur) len++;
    else { bits.push(cur); lens.push(len); cur = b; len = 1; }
  }
  bits.push(cur); lens.push(len);
  if (bits.length <= 2) return 0;
  // 去掉首尾段
  const holdLens = [], relLens = [];
  for (let i = 1; i < bits.length - 1; i++) (bits[i] ? holdLens : relLens).push(lens[i]);
  return (sameEnough(holdLens, p) && sameEnough(relLens, p)) ? 1 : 0;
}
function sameEnough(arr, p) {
  if (arr.length < p.mechanicalMinRuns) return false;
  const cnt = new Map();
  let best = 0;
  for (const v of arr) { const c = (cnt.get(v) || 0) + 1; cnt.set(v, c); if (c > best) best = c; }
  return best * 100 >= p.mechanicalSamePct * arr.length;
}

// ---------------------------------------------------------------------------
// 结算（§14）
// ---------------------------------------------------------------------------
/**
 * result(state, ext?) → 结算对象（全是整数，负面是 NEG 码数组）。
 * ext（服务端外部判定，可省）：{ gold: 0/1（服务端私下抽的瓶种类），slow: 0/1, mechanical: 0/1, suspect: int }
 * 进行中调用时给出临时结果（ended = 0，用于实时品质徽章）。
 */
function result(s, ext) {
  const p = s.p;
  const e = ext || {};
  const S = imax(1, s.S);
  const C = (s.I * 1000 / S) | 0;
  let under = (s.U * 1000 / S) | 0, over = (s.O * 1000 / S) | 0, mess = 0, pen = 0, clean = 1;
  let avgHeat = 0, hotPm = 0, coldPm = 0;
  switch (p.station) {
    case STATION.POT: {
      avgHeat = (s.hSum / S) | 0;
      pen = ((1000 - avgHeat) / p.penDiv) | 0;
      under += ((1000 - avgHeat) / p.underDiv) | 0;
      break;
    }
    case STATION.FRYER: {
      hotPm = (s.hot * 1000 / S) | 0; coldPm = (s.cold * 1000 / S) | 0;
      pen = ((hotPm / p.penHotDiv) | 0) + ((coldPm / p.penColdDiv) | 0) + p.penBurst * s.bursts;
      over += ((hotPm / p.negHotDiv) | 0) + p.negBurst * s.bursts;
      mess += (coldPm / p.negColdDiv) | 0;
      clean = (s.bursts === 0 && s.hot + s.cold <= p.cleanTicks) ? 1 : 0;
      break;
    }
    case STATION.OVEN: {
      pen = s.flipPen; under += s.flipUnder; over += s.flipOver; clean = s.flipAllGood;
      break;
    }
    case STATION.PREP: {
      pen = p.penOk * s.okB + p.penMiss * s.miss + p.penMash * s.mash;
      mess += ((s.miss * p.negMissPm / p.beats) | 0) + p.negMash * s.mash;
      clean = (s.miss === 0 && s.mash === 0) ? 1 : 0;
      break;
    }
  }
  const bottle = s.bState === BOTTLE.CAUGHT ? 1 : 0;
  const gold = (s.bKind !== BOTTLE_KIND.NONE && e.gold) ? 1 : 0;
  const bottleKind = s.bKind === BOTTLE_KIND.NONE ? BOTTLE_KIND.NONE : gold ? BOTTLE_KIND.GOLD : BOTTLE_KIND.NORMAL;
  const bottleBonus = p.scoreBonus * bottle;
  const score = imax(0, C - pen + bottleBonus);
  const done = s.end === END.DONE ? 1 : 0;
  const perfect = (done && s.perfect === 1 && clean === 1) ? 1 : 0;
  const mechanical = e.mechanical ? 1 : 0, slow = e.slow ? 1 : 0, suspect = e.suspect | 0;

  let qualityRaw, quality;
  if (s.end === END.BURNT || s.end === END.ABANDON) {
    qualityRaw = quality = QUALITY.HOMESTYLE;
  } else {
    if (perfect && bottle && gold && p.N === 1) qualityRaw = QUALITY.RADIANT;
    else if (done && (perfect || score >= p.tierExtraordinary)) qualityRaw = QUALITY.EXTRAORDINARY;
    else if (score >= p.tierHigh) qualityRaw = QUALITY.HIGH;
    else if (score >= p.tierMedium) qualityRaw = QUALITY.MEDIUM;
    else qualityRaw = QUALITY.LOW;
    let cap = QUALITY.RADIANT;
    if (s.end === END.TIMEOUT) cap = imin(cap, p.timeoutCap);
    if (p.N > 1) cap = imin(cap, p.batchCap);
    if (slow) cap = imin(cap, p.slowCap);
    if (mechanical) cap = imin(cap, p.mechanicalCap);
    if (suspect >= p.suspectCapHigh) cap = imin(cap, QUALITY.HIGH);
    else if (suspect >= p.suspectCapRadiant) cap = imin(cap, QUALITY.EXTRAORDINARY);
    quality = imin(qualityRaw, cap);
  }

  // 负面（§14.5）：只出在 低/中/高
  const negatives = [];
  if (quality >= QUALITY.LOW && quality <= QUALITY.HIGH) {
    const th = quality === QUALITY.LOW ? p.thresholdLow : quality === QUALITY.MEDIUM ? p.thresholdMedium : p.thresholdHigh;
    const maxN = quality === QUALITY.LOW ? p.maxLow : quality === QUALITY.MEDIUM ? p.maxMedium : p.maxHigh;
    const vals = [under, over, mess];
    // 按值降序、值相等按 夹生 > 烧焦 > 倒胃 挑（稳定选择）
    const order = [0, 1, 2];
    for (let i = 1; i < 3; i++) {
      for (let j = i; j > 0 && vals[order[j]] > vals[order[j - 1]]; j--) {
        const t = order[j]; order[j] = order[j - 1]; order[j - 1] = t;
      }
    }
    const picked = [];
    for (let i = 0; i < 3 && picked.length < maxN; i++) if (vals[order[i]] >= th) picked.push(order[i]);
    if (s.end === END.TIMEOUT && picked.indexOf(NEG.UNDERDONE) < 0) {
      picked.push(NEG.UNDERDONE);
      if (picked.length > 2) picked.splice(picked.length - 2, 1); // 去掉原来挑中的最小那条
    }
    for (let k = 0; k < 3; k++) if (picked.indexOf(k) >= 0) negatives.push(k); // 规范顺序输出
  }

  return {
    simVersion: SIM_VERSION,
    station: p.station, star: p.star, level: p.level,
    end: s.end, ended: s.end !== END.RUNNING ? 1 : 0,
    quality, qualityRaw, perfect,
    bottle, bottleKind, bottleState: s.bState, gold,
    score, accuracy: C, pen, bottleBonus,
    underPm: under, overPm: over, messPm: mess, negatives,
    ticks: s.tick, formalTicks: s.pt, S: s.S, I: s.I, U: s.U, O: s.O, progress: s.P,
    avgHeat, hotPm, coldPm, bursts: s.bursts,
    flips: s.flipIdx, flipGood: s.nGood, flipLate: s.nLate, flipEarly: s.nEarly, flipBurnt: s.nBurnt, flipAuto: s.nAuto,
    beatPerfect: s.perfB, beatOk: s.okB, beatMiss: s.miss, beatMash: s.mash,
    mechanical, slow, suspect,
    hash: hex32(hashState(s)),
  };
}

// ---------------------------------------------------------------------------
// 回放（§18.6）
// ---------------------------------------------------------------------------
/**
 * replayTrace(config, inputs, ext?) → { state, result, hashes, finalHash, consumed, padded, invalid }
 * config：createGame 的选项，或 { block, seed }。ext 原样传给 result（gold 来自服务端私下抽签）。
 * 输入用完还没结束 → 余下按 0 补到结束；遇到保留位非 0 的字节 → 撂勺。
 * hashes：每推进 20 tick 记一次状态哈希（tick = 20, 40, …）。
 */
function replayTrace(config, inputs, ext) {
  const s = createGame(config);
  const hashes = [];
  let i = 0, invalid = 0, padded = 0;
  const guard = s.p.CAP * 4 + 4096; // 理论上界远小于此
  while (s.end === END.RUNNING) {
    let inp = 0;
    if (i < inputs.length) inp = inputs[i] | 0; else padded++;
    if ((inp & ~3) !== 0) { s.end = END.ABANDON; invalid = 1; break; }
    stepInPlace(s, inp);
    i++;
    if (s.tick % 20 === 0) hashes.push(hex32(hashState(s)));
    if (i > guard) throw new Error('replay 超出上界');
  }
  const consumed = i;
  const mechanical = detectMechanical(inputs.length >= consumed ? inputs : padInputs(inputs, consumed), s.p, consumed);
  const res = result(s, Object.assign({}, ext || {}, { mechanical: (ext && ext.mechanical) || mechanical }));
  return { state: s, result: res, hashes, finalHash: hex32(hashState(s)), consumed, padded, invalid };
}
function padInputs(inputs, n) { const a = inputs.slice(); while (a.length < n) a.push(0); return a; }

/** replay(config, inputs[], ext?) → result */
function replay(config, inputs, ext) { return replayTrace(config, inputs, ext).result; }

// ---------------------------------------------------------------------------
// 输入 RLE（§18.3：varint 长度 + 1 字节值；这里给 JSON 友好的 [[len, val], …]）
// ---------------------------------------------------------------------------
function rleEncode(inputs) {
  const out = [];
  for (let i = 0; i < inputs.length;) {
    const v = inputs[i] | 0; let j = i + 1;
    while (j < inputs.length && (inputs[j] | 0) === v) j++;
    out.push([j - i, v]); i = j;
  }
  return out;
}
function rleDecode(runs) {
  const out = [];
  for (const [len, v] of runs) for (let k = 0; k < len; k++) out.push(v | 0);
  return out;
}

// ---------------------------------------------------------------------------
// 显示换算（只在内核外用浮点）
// ---------------------------------------------------------------------------
function view(s, gold) {
  const p = s.p, T = p.track;
  return {
    barLo: s.yb / T, barHi: (s.yb + p.H) / T, fish: s.yt / T,
    target: s.tgt === NONE ? null : s.tgt / T,
    progress: s.P / p.progressMax,
    bottle: s.bState === BOTTLE.PRESENT ? { pos: s.bPos / T, fill: s.K / K_MAX, gold: !!gold } : null,
    seconds: s.tick / TICKS_PER_SECOND,
  };
}

root.ZhangshaoSim = Object.freeze({ SIM_VERSION, TICKS_PER_SECOND, TICK_MS, NONE, STATION, MOTION, END, QUALITY, BOTTLE, BOTTLE_KIND, NEG, SIDE, DEFAULT_CONFIG, A_FIELDS, B_FIELDS, PARAM_FIELDS, PARAM_COUNT, tdiv, fmix32, STREAM_SALT, Mulberry32, fnv1aInts, hex32, defaultMotion, buildParams, decodeParams, paramsHash, validateConfig, initState, createGame, cloneState, step, markAt, fullGain, stepInPlace, abandon, HASH_FIELDS, hashState, detectMechanical, result, replayTrace, replay, rleEncode, rleDecode, view });
})(typeof globalThis !== 'undefined' ? globalThis : this);
