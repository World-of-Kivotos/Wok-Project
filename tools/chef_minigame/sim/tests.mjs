// 掌勺内核测试（simVersion 2）。用法：node tests.mjs（只用内置 node:test / node:assert / node:vm / node:fs）
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import * as Z from './sim.js';
import { playGame, perfectBot, humanBot, macroBot, randomBot, tapBot, gameSeed, privateGold, openLoopStreams } from './bots.mjs';

const { STATION: ST, MOTION: MO, END, QUALITY: Q, BOTTLE, BOTTLE_KIND, NEG } = Z;
const STATIONS = [ST.POT, ST.FRYER, ST.OVEN, ST.PREP];

function run(cfg, inputsOrFn, maxTicks = 20000) {
  const s = Z.createGame(cfg);
  const inputs = [];
  for (let t = 0; s.end === END.RUNNING; t++) {
    if (t > maxTicks) throw new Error('did not end');
    const v = typeof inputsOrFn === 'function' ? inputsOrFn(s, t) : (inputsOrFn[t] ?? 0);
    inputs.push(v); Z.stepInPlace(s, v);
  }
  return { s, inputs };
}
function isInt32(v) { return Number.isInteger(v) && (v | 0) === v && !Object.is(v, -0); }
function checkInvariants(s) {
  const p = s.p;
  for (const f of Z.HASH_FIELDS) assert.ok(isInt32(s[f]), `${f} 不是 int32: ${s[f]}`);
  assert.equal(s.I + s.U + s.O, s.S);
  assert.ok(s.P >= 0 && s.P <= p.progressMax);
  assert.ok(s.yb >= 0 && s.yb <= p.track - p.H);
  assert.ok(s.yt >= p.fishMin && s.yt <= p.fishMax);
  assert.ok(s.tgt === Z.NONE || (s.tgt >= p.fishMin && s.tgt <= p.fishMax));
  assert.ok(s.K >= 0 && s.K <= 1000000);
  assert.ok(s.h >= 0 && s.h <= p.heatMax);
  assert.ok(s.T >= 0 && s.T < p.oilBurst);
  assert.ok(s.pt <= p.CAP && s.tick >= s.pt);
  assert.ok(s.perfect === 0 || s.perfect === 1);
  assert.ok(s.bKind === BOTTLE_KIND.NONE || s.bKind === BOTTLE_KIND.NORMAL, '内核只知道有没有瓶');
}

// ---------------------------------------------------------------------------
// 随机数与哈希
// ---------------------------------------------------------------------------
test('Mulberry32 与公开参考实现逐位一致', () => {
  // 公开的 mulberry32（t ^= t + imul(...) 等价于规格里的 (t + imul(...)) ^ t）
  function ref(a) {
    return function () {
      let t = (a += 0x6D2B79F5);
      t = Math.imul(t ^ (t >>> 15), t | 1);
      t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
      return (t ^ (t >>> 14)) | 0;
    };
  }
  for (const seed of [0, 1, -1, 12345, 0x7FFFFFFF, -2147483648, 987654321]) {
    const a = new Z.Mulberry32(seed), b = ref(seed);
    for (let i = 0; i < 2000; i++) assert.equal(a.next(), b());
  }
});

test('rnd / range 的范围与含两端', () => {
  const r = new Z.Mulberry32(7);
  const seen = new Set();
  for (let i = 0; i < 20000; i++) {
    const v = r.range(-2, 2); assert.ok(v >= -2 && v <= 2); seen.add(v);
    const w = r.rnd(3); assert.ok(w >= 0 && w < 3);
  }
  assert.equal(seen.size, 5);
});

test('fmix32 / FNV-1a 基本性质', () => {
  assert.equal(Z.fmix32(0), 0);
  assert.notEqual(Z.fmix32(1), Z.fmix32(2));
  assert.equal(Z.fnv1aInts([]), 0x811C9DC5 | 0);
  assert.equal(Z.hex32(Z.fnv1aInts([])), '811c9dc5');
});

// ---------------------------------------------------------------------------
// 参数块
// ---------------------------------------------------------------------------
test('参数块：长度、派生量、时限、往返', () => {
  assert.equal(Z.A_FIELDS.length, 32);
  // 时限：厨锅不再跟 PACE 拉长（与炸锅、烤炉一样 15 秒）；备餐台 12 秒（不含摆料段）
  for (const [st, cap] of [[ST.POT, 300], [ST.FRYER, 300], [ST.OVEN, 300], [ST.PREP, 240]]) {
    const b = Z.buildParams({ station: st, star: 3, chefLevel: 5 });
    assert.equal(b.length, Z.PARAM_COUNT);
    const p = Z.decodeParams(b);
    assert.equal(p.CAP, cap);
    assert.equal(p.H, 150000 + 11000 * 4);
    assert.ok(b.every(isInt32));
  }
  // L1–3 火候条至少 18%，L4 起按公式
  for (const [L, H] of [[1, 180000], [2, 180000], [3, 180000], [4, 183000], [10, 249000]])
    assert.equal(Z.decodeParams(Z.buildParams({ station: 0, star: 1, chefLevel: L })).H, H);
  const p = Z.decodeParams(Z.buildParams({ station: ST.POT, star: 7, chefLevel: 10, mastery: 5, batch: 6 }));
  assert.equal(p.D, 73); assert.equal(p.P0, 350000); assert.equal(p.MAST, 1400); assert.equal(p.BPACE, 440);
  assert.equal(p.goldPm, 0, '批量局金瓶率必须为 0');
  assert.equal(p.LOSS, 3516); assert.equal(p.BB, 1); assert.equal(p.drainPm, 15000);
  assert.equal(p.CAP, (300000000 / (1000 * 440)) | 0);
  // 瓶：抓取速度和出现时刻随手熟缩放，离食材的距离按火候条长取
  assert.equal(p.bFill, (34000 * 1400 / 1000) | 0); assert.equal(p.bAppearMin, (20000 / 1400) | 0); assert.equal(p.bAppearMax, (60000 / 1400) | 0);
  assert.equal(p.bOffMin, (249000 * 550 / 1000) | 0); assert.equal(p.bOffMax, (249000 * 900 / 1000) | 0);
  const g = Z.decodeParams(Z.buildParams({ station: ST.POT, star: 2, chefLevel: 10 }));
  assert.equal(g.bottlePm, 250 + 15 * 9 + 12 + 3 * 9); assert.equal(g.goldPm, 12 + 3 * 9);
  assert.equal(Z.decodeParams(Z.buildParams({ station: 0, star: 1, chefLevel: 1, goldPm: 1000 })).bottlePm, 1000, '瓶率封顶 1000');
  const q = Z.decodeParams(Z.buildParams({ station: ST.PREP, star: 6, chefLevel: 1, drink: true }));
  assert.equal(q.motion, MO.FLOAT); assert.equal(q.beats, 6); assert.equal(q.beatGap, 7); assert.equal(q.jitterOn, 0); assert.equal(q.segs, 1);
  assert.equal(Z.decodeParams(Z.buildParams({ station: ST.POT, star: 1, chefLevel: 1, motion: MO.DART })).D, 26);
  assert.equal(Z.decodeParams(Z.buildParams({ station: ST.OVEN, star: 1, chefLevel: 1 })).motion, MO.MIX, '烤炉默认性子按 §13.6 是「混」');
  assert.throws(() => Z.buildParams({ station: 4, star: 1, chefLevel: 1 }));
  assert.throws(() => Z.buildParams({ station: 0, star: 8, chefLevel: 1 }));
  assert.throws(() => Z.decodeParams([2]));
  assert.ok(Z.validateConfig());
  assert.throws(() => Z.validateConfig({ tierHigh: 999 }));
  assert.throws(() => Z.validateConfig({ thresholdHigh: 100 }), '负面门槛要 低 ≤ 中 ≤ 高');
  assert.throws(() => Z.validateConfig({ flipEarlyMildTicks: 2 }));
});

test('配置改动只经参数块生效（内核不读配置）', () => {
  const cfg = { station: ST.POT, star: 2, chefLevel: 4, seed: 99 };
  const a = Z.buildParams(cfg), b = Z.buildParams(cfg, { accel: 3000 });
  assert.notEqual(Z.paramsHash(a), Z.paramsHash(b));
  const ins = Array.from({ length: 200 }, (_, i) => (i >> 3) & 1);
  const ra = Z.replayTrace({ block: a, seed: 99 }, ins), rb = Z.replayTrace({ block: b, seed: 99 }, ins);
  assert.notEqual(ra.finalHash, rb.finalHash);
});

// ---------------------------------------------------------------------------
// 确定性
// ---------------------------------------------------------------------------
test('确定性：同种子同输入，每 tick 状态哈希逐位相同', () => {
  for (const st of STATIONS) for (const star of [1, 4, 7]) {
    const seed = 1000 + st * 10 + star;
    const ins = []; const r = new Z.Mulberry32(seed * 3);
    for (let i = 0; i < 1500; i++) ins.push((r.rnd(100) < 55 ? 1 : 0) | (r.rnd(100) < 8 ? 2 : 0));
    const cfg = { station: st, star, chefLevel: 5, seed };
    const a = Z.createGame(cfg), b = Z.createGame(cfg);
    for (let i = 0; i < ins.length && a.end === END.RUNNING; i++) {
      Z.stepInPlace(a, ins[i]); Z.stepInPlace(b, ins[i]);
      assert.equal(Z.hashState(a), Z.hashState(b));
    }
    assert.deepEqual(Z.result(a), Z.result(b));
  }
});

test('纯函数 step 不改入参，且与 stepInPlace 一致', () => {
  for (const st of STATIONS) {
    let s = Z.createGame({ station: st, star: 5, chefLevel: 3, seed: 4242 + st });
    const t = Z.createGame({ station: st, star: 5, chefLevel: 3, seed: 4242 + st });
    const bot = humanBot('average', 77);
    while (s.end === END.RUNNING) {
      const v = bot.decide(s);
      const before = Z.hashState(s);
      const n = Z.step(s, v);
      assert.equal(Z.hashState(s), before, 'step 改了入参');
      Z.stepInPlace(t, v);
      assert.equal(Z.hashState(n), Z.hashState(t));
      s = n;
    }
    assert.deepEqual(Z.result(s), Z.result(t));
  }
});

test('不同种子给出不同的局', () => {
  const ins = new Array(300).fill(0).map((_, i) => (i % 9 < 5 ? 1 : 0));
  const hs = new Set();
  for (let seed = 0; seed < 50; seed++) hs.add(Z.replayTrace({ station: ST.POT, star: 3, chefLevel: 3, seed }, ins).finalHash);
  assert.ok(hs.size >= 49);
});

test('抽签纪律：rT 每个正式 tick 固定 24 次、rB/rS 开局后不再抽，与输入无关', () => {
  for (const st of STATIONS) {
    const cfg = { station: st, star: 6, chefLevel: 2, seed: 31337 };
    const ref = new Z.Mulberry32(Z.fmix32(31337 ^ Z.STREAM_SALT.T));
    ref.next(); // 开局第一跳
    const s0 = Z.createGame(cfg);
    assert.equal(s0.rTa, ref.a);
    const runs = [0, 1, 2, 3].map((v) => { const s = Z.createGame(cfg); Z.stepInPlace(s, v); return s; });
    for (let k = 0; k < 24; k++) ref.next();
    for (const s of runs) {
      assert.equal(s.rTa, ref.a);
      assert.equal(s.rBa, s0.rBa); assert.equal(s.rSa, s0.rSa);
    }
    // 整局：rT 抽签次数 = 1 + 24 × pt
    const { s } = run(cfg, (x, t) => ((t * 7) % 11 < 6 ? 1 : 0) | (t % 13 === 0 ? 2 : 0));
    const r2 = new Z.Mulberry32(Z.fmix32(31337 ^ Z.STREAM_SALT.T));
    for (let k = 0; k < 1 + 24 * s.pt; k++) r2.next();
    assert.equal(s.rTa, r2.a);
    assert.equal(s.rBa, s0.rBa); assert.equal(s.rSa, s0.rSa);
  }
});

// ---------------------------------------------------------------------------
// 回放 = 实时
// ---------------------------------------------------------------------------
test('回放 = 实时（各台 × 多星级 × 多种机器人）', () => {
  let n = 0;
  for (const st of STATIONS) for (const star of [1, 3, 5, 7]) for (const L of [1, 6, 10]) for (const kind of ['novice', 'average', 'good', 'random', 'perfect']) {
    if (kind === 'perfect' && (star % 2 === 0 || L === 6)) continue;
    const seed = gameSeed(st, star * 100 + L * 7);
    const cfg = { station: st, star, chefLevel: L, seed };
    const { state, inputs } = playGame(cfg, kind, seed ^ 5);
    const gold = state.bKind ? privateGold(seed, state.p) : 0;
    const live = Z.result(state, { gold, mechanical: Z.detectMechanical(inputs, state.p) });
    const tr = Z.replayTrace(cfg, inputs, { gold });
    assert.equal(tr.consumed, inputs.length); assert.equal(tr.padded, 0);
    assert.deepEqual(tr.result, live, `${st}/${star}/${L}/${kind}`);
    assert.equal(tr.finalHash, Z.hex32(Z.hashState(state)));
    // 从参数块回放也一样
    assert.deepEqual(Z.replay({ block: Z.buildParams(cfg), seed }, inputs, { gold }), live);
    n++;
  }
  assert.ok(n > 150);
});

test('回放：多发的输入丢弃、少发补 0、非法字节 = 撂勺', () => {
  const cfg = { station: ST.FRYER, star: 2, chefLevel: 4, seed: 555 };
  const { state, inputs } = playGame(cfg, 'good', 9);
  const extra = inputs.concat(new Array(50).fill(1));
  const tr = Z.replayTrace(cfg, extra);
  assert.equal(tr.consumed, inputs.length);
  assert.equal(tr.finalHash, Z.hex32(Z.hashState(state)));
  const short = Z.replayTrace(cfg, inputs.slice(0, 30));
  assert.ok(short.padded > 0 && short.result.ended === 1);
  assert.equal(short.result.end, END.BURNT, '后面全松应糊锅');
  const bad = inputs.slice(); bad[40] = 4;
  const tb = Z.replayTrace(cfg, bad);
  assert.equal(tb.invalid, 1); assert.equal(tb.result.end, END.ABANDON); assert.equal(tb.result.quality, Q.HOMESTYLE);
  assert.equal(tb.consumed, 40);
  const ab = Z.abandon(Z.createGame(cfg));
  assert.equal(ab.end, END.ABANDON);
  assert.equal(Z.result(ab).quality, Q.HOMESTYLE);
});

// ---------------------------------------------------------------------------
// 边界：全程不按 / 全程按住 / 随机乱按
// ---------------------------------------------------------------------------
test('全程不按、全程按住（含按住 + 狂按动作键）必定糊锅', () => {
  // 时长上界：手熟 0 约 4–6 秒（spec §16.3）；手熟 5 起点高 10%，稍长
  for (const [mastery, maxSec] of [[0, 6.5], [5, 8.5]])
    for (const st of STATIONS) for (const star of [1, 4, 7]) for (const L of [1, 3, 6, 9, 10]) for (const v of [0, 1, 2, 3]) for (const seed of [1, 2, 3]) {
      const { s } = run({ station: st, star, chefLevel: L, seed, mastery }, () => v);
      assert.equal(s.end, END.BURNT, `st${st} ★${star} L${L} in=${v}`);
      assert.equal(Z.result(s).quality, Q.HOMESTYLE);
      assert.ok(s.tick / 20 <= maxSec, `挂机 ${s.tick / 20}s 才糊锅（手熟 ${mastery}）`);
    }
});

test('随机乱按：每 tick 不变式成立、不崩、所有状态都是 int32', () => {
  for (const st of STATIONS) for (const star of [1, 7]) for (const L of [1, 10]) for (let g = 0; g < 15; g++) {
    const seed = Z.fmix32(st * 1000 + star * 100 + L * 10 + g);
    const bot = randomBot(seed);
    const s = Z.createGame({ station: st, star, chefLevel: L, seed, flips: 3, beats: 8, batch: L === 10 ? 3 : 1 });
    while (s.end === END.RUNNING) { Z.stepInPlace(s, bot.decide(s)); checkInvariants(s); }
    const r = Z.result(s, { gold: 1 });
    for (const [k, v] of Object.entries(r)) if (typeof v === 'number') assert.ok(isInt32(v), `result.${k}`);
    assert.ok(r.quality <= Q.MEDIUM, '乱按不该出高品质');
  }
});

test('人类机器人：全程不变式成立', () => {
  for (const st of STATIONS) for (const kind of ['novice', 'good']) for (let g = 0; g < 6; g++) {
    const seed = Z.fmix32(77 + st * 31 + g);
    const s = Z.createGame({ station: st, star: 1 + g, chefLevel: 1 + g, seed, flips: 2, beats: 6 });
    const bot = humanBot(kind, seed, g % 2 ? { attention: 3, glance: 2, flip: 'anticipate', flipSd: 2 } : {});
    while (s.end === END.RUNNING) { Z.stepInPlace(s, bot.decide(s)); checkInvariants(s); }
  }
});

// ---------------------------------------------------------------------------
// 可达性
// ---------------------------------------------------------------------------
test('完美机器人：每台 × 每星级都能拿 Perfect（L1 与 L10）', () => {
  for (const st of STATIONS) for (let star = 1; star <= 7; star++) for (const L of [1, 10]) {
    let got = false;
    for (let g = 0; g < 10 && !got; g++) {
      const seed = gameSeed(st, 500 + g);
      const { state } = playGame({ station: st, star, chefLevel: L, seed }, 'perfect');
      const r = Z.result(state);
      if (r.perfect) { got = true; assert.ok(r.quality >= Q.EXTRAORDINARY); }
    }
    assert.ok(got, `st${st} ★${star} L${L} 没有一局 Perfect`);
  }
});

test('完美机器人 + 必出瓶 + 服务端判金瓶：每台都能做出闪耀；不判金瓶就只是超凡', () => {
  for (const st of STATIONS) {
    let got = false;
    for (let g = 0; g < 60 && !got; g++) {
      const { state } = playGame({ station: st, star: 3, chefLevel: 10, seed: gameSeed(st, 900 + g), goldPm: 1000 }, 'perfect');
      got = Z.result(state, { gold: 1 }).quality === Q.RADIANT;
      if (got) {
        assert.equal(Z.result(state).quality, Q.EXTRAORDINARY, '没有 ext.gold 时内核不知道是金瓶');
        assert.equal(Z.result(state, { gold: 1 }).bottleKind, BOTTLE_KIND.GOLD);
      }
    }
    assert.ok(got, `st${st} 闪耀不可达`);
  }
});

test('金瓶不从种子推出：内核状态里只有「有没有瓶」，ext.gold 只在有瓶时生效', () => {
  let withBottle = 0;
  for (let seed = 0; seed < 400; seed++) {
    const s = Z.createGame({ station: 0, star: 2, chefLevel: 10, seed });
    assert.ok(s.bKind === BOTTLE_KIND.NONE || s.bKind === BOTTLE_KIND.NORMAL);
    if (s.bKind === BOTTLE_KIND.NONE) assert.equal(Z.result(s, { gold: 1 }).gold, 0);
    else withBottle++;
  }
  // 有瓶率 ≈ bottlePm = 250 + 135 + 39 = 424‰
  assert.ok(withBottle > 130 && withBottle < 210, `有瓶 ${withBottle}/400`);
});

test('读种子的完美机器人：出金瓶的局里多数能抓到（瓶离食材 0.55–0.9 个火候条长）', () => {
  for (const [st, star, L] of [[0, 1, 1], [1, 1, 10], [0, 7, 10]]) {
    let caught = 0; const n = 40;
    for (let g = 0; g < n; g++) {
      const { state } = playGame({ station: st, star, chefLevel: L, seed: gameSeed(st, 7000 + g), goldPm: 1000 }, 'perfect');
      if (state.bState === BOTTLE.CAUGHT) caught++;
    }
    assert.ok(caught >= n * 0.5, `st${st} ★${star} L${L} 只抓到 ${caught}/${n}`);
  }
});

test('失败可达：糊锅、到点（品质 ≤ 中且必带夹生；时限 15 秒就结束）', () => {
  const { s } = run({ station: ST.POT, star: 1, chefLevel: 10, seed: 5 }, () => 0);
  assert.equal(s.end, END.BURNT);
  let timeouts = 0;
  for (let g = 0; g < 80; g++) {
    const { state } = playGame({ station: g % 4, star: 1, chefLevel: 1, seed: gameSeed(g % 4, g) }, 'novice');
    if (state.end === END.TIMEOUT) {
      timeouts++;
      const r = Z.result(state);
      assert.ok(r.quality <= Q.MEDIUM && r.quality >= Q.LOW);
      assert.ok(r.negatives.includes(NEG.UNDERDONE));
      assert.ok(r.negatives.length <= 2);
      assert.equal(state.pt, state.p.CAP);
      assert.ok(state.pt <= 300);
    }
  }
  assert.ok(timeouts > 10);
});

// ---------------------------------------------------------------------------
// 内核细节
// ---------------------------------------------------------------------------
test('火候条：贴顶 10 tick 后顶死、不算框住，记过火', () => {
  const s = Z.createGame({ station: ST.POT, star: 1, chefLevel: 1, seed: 1 });
  let firstPinnedTick = -1;
  for (let t = 0; t < 60 && s.end === END.RUNNING; t++) {
    const O0 = s.O;
    Z.stepInPlace(s, 1);
    if (s.pin >= 10 && firstPinnedTick < 0) { firstPinnedTick = t; assert.equal(s.O, O0 + 1); assert.equal(s.inPrev, 0); }
  }
  assert.ok(firstPinnedTick > 0);
  // 全松贴底：记欠火
  const b = Z.createGame({ station: ST.POT, star: 1, chefLevel: 1, seed: 1 });
  while (b.end === END.RUNNING) Z.stepInPlace(b, 0);
  assert.ok(b.U > b.O);
});

test('Perfect：单 tick 擦边原谅，连续 2 tick 离条即断', () => {
  // 构造：先跑到瓶出现前，然后手动把食材挪出条
  const s = Z.createGame({ station: ST.POT, star: 1, chefLevel: 10, seed: 3, normalPm: 0, goldPm: 0 });
  const bot = perfectBot(s);
  for (let i = 0; i < 30; i++) Z.stepInPlace(s, bot.decide(s));
  assert.equal(s.perfect, 1);
  const a = Z.cloneState(s);
  a.outRun = 1; // 假设上一 tick 擦边
  a.yt = 900000; a.yb = 0; a.vb = 0; a.vt = 0; a.tgt = 900000;
  Z.stepInPlace(a, 0);
  assert.equal(a.perfect, 0);
  const c = Z.cloneState(s);
  c.outRun = 0; c.yt = 900000; c.yb = 0; c.vb = 0; c.vt = 0; c.tgt = 900000;
  Z.stepInPlace(c, 0);
  assert.equal(c.outRun, 1); assert.equal(c.perfect, 1);
});

test('调料瓶：出现时刻/位置、连续框住 30 tick 抓到、瓶护、手熟缩放', () => {
  // 找一局有瓶的
  let s;
  for (let seed = 1; ; seed++) { s = Z.createGame({ station: ST.POT, star: 1, chefLevel: 1, seed }); if (s.bKind !== BOTTLE_KIND.NONE) break; }
  const p = s.p;
  assert.equal(s.bState, BOTTLE.WAITING);
  assert.ok(s.bAt >= p.bAppearMin && s.bAt <= p.bAppearMax && s.bOff >= p.bOffMin && s.bOff <= p.bOffMax);
  assert.ok(s.bOff >= 0.55 * p.H - 1 && s.bOff <= 0.9 * p.H, '瓶离食材 0.55–0.9 个火候条长');
  while (s.pt < s.bAt) Z.stepInPlace(s, s.yb + s.p.H / 2 < s.yt ? 1 : 0);
  assert.equal(s.bState, BOTTLE.WAITING);
  Z.stepInPlace(s, 0);
  assert.equal(s.bState, BOTTLE.PRESENT);
  assert.equal(s.pt, s.bAt + 1);
  assert.ok(Math.abs(s.bPos - s.yt) === s.bOff || s.bPos === 100000 || s.bPos === 900000, '瓶位置 = 食材 ± bOff（越界换边再夹取）');
  // 每 tick 把条（静止）摆到瓶正中：连续框住 30 tick 抓到；在场且在条内时熟度不降（瓶护）
  const a = Z.cloneState(s);
  a.K = 0;
  const center = a.bPos - (p.H >> 1);
  let caughtAt = -1;
  for (let i = 0; i < 40 && a.end === END.RUNNING; i++) {
    a.yb = center; a.vb = 0;
    const P0 = a.P, wasPresent = a.bState === BOTTLE.PRESENT;
    Z.stepInPlace(a, 0);
    if (a.bState === BOTTLE.CAUGHT && caughtAt < 0) caughtAt = i;
    if (wasPresent && a.bState === BOTTLE.PRESENT) assert.ok(a.P >= P0, '瓶护失效');
  }
  assert.equal(caughtAt, 29, `抓瓶用了 ${caughtAt + 1} tick`);
  // 手熟 5：抓取快 1.4 倍（22 tick），出现时刻按 1/1.4 提前
  const m = Z.decodeParams(Z.buildParams({ station: 0, star: 1, chefLevel: 1, mastery: 5 }));
  assert.equal(Math.ceil(1000000 / m.bFill), 22);
  assert.ok(m.bAppearMax < 60 && m.bAppearMin < 20);
});

test('厨锅：火候 h 涨/掉与平均火候扣分', () => {
  const s = Z.createGame({ station: ST.POT, star: 1, chefLevel: 10, seed: 8 });
  const bot = perfectBot(s);
  while (s.end === END.RUNNING) Z.stepInPlace(s, bot.decide(s));
  const r = Z.result(s);
  assert.ok(r.avgHeat > 900);
  assert.equal(r.pen, ((1000 - r.avgHeat) / 8) | 0);
  const t = Z.createGame({ station: ST.POT, star: 1, chefLevel: 1, seed: 8 });
  const h0 = t.h; Z.stepInPlace(t, 0);
  assert.ok(t.h === h0 + 30 || t.h === h0 - 80);
});

test('炸锅：压火、冷却期内无效、过热区升温减半、不压火会爆油', () => {
  const s = Z.createGame({ station: ST.FRYER, star: 1, chefLevel: 1, seed: 11 });
  const T0 = s.T;
  Z.stepInPlace(s, 2);
  assert.equal(s.cd, s.p.oilCd);
  assert.ok(s.T < T0 - 250);
  const T1 = s.T;
  Z.stepInPlace(s, 2); // 冷却中：无效
  assert.ok(s.T > T1 - 100);
  assert.equal(s.cd, s.p.oilCd - 1);
  // 过热区（T ≥ 750）升温减半：★1、P < 300000 时框住 +4 → +2，没框住 +3 → +1
  const h = Z.cloneState(Z.createGame({ station: ST.FRYER, star: 1, chefLevel: 1, seed: 11 }));
  h.T = 760;
  const inb = Z.cloneState(h); Z.stepInPlace(inb, 0);
  assert.ok(inb.T === 762 || inb.T === 761, `过热区升温 ${inb.T - 760}`);
  assert.equal(inb.hot, 1);
  const cool = Z.cloneState(h); cool.T = 700; Z.stepInPlace(cool, 0);
  assert.ok(cool.T === 704 || cool.T === 703);
  // 不压火：爆油
  const b = Z.createGame({ station: ST.FRYER, star: 7, chefLevel: 1, seed: 12 });
  const bot = humanBot('good', 3);
  while (b.end === END.RUNNING && b.bursts === 0) Z.stepInPlace(b, bot.decide(b) & 1);
  assert.ok(b.bursts >= 1);
  assert.equal(b.T, b.p.oilAfterBurst);
  assert.equal(b.kickN, 1);
  assert.ok(b.hot > 30, '爆油前要先在过热区待一阵（斜坡，不是悬崖）');
});

test('烤炉：提示、正好/稍晚/焦面/自动翻、未翻完熟度封顶', () => {
  const cfg = { station: ST.OVEN, star: 3, chefLevel: 1, seed: 21, flips: 1 };
  function upToPrompt() {
    const s = Z.createGame(cfg); const bot = perfectBot(s);
    while (s.prompt < 0 && s.end === END.RUNNING) Z.stepInPlace(s, bot.decide(s) & 1);
    return { s, bot };
  }
  // 正好：第一 tick 就翻
  { const { s, bot } = upToPrompt(); Z.stepInPlace(s, (bot.decide(s) & 1) | 2); assert.equal(s.nGood, 1); assert.equal(s.flipIdx, 1); assert.equal(s.kickN, 1); assert.equal(s.flipAllGood, 1); }
  // 稍晚：flipGood+1 tick 后
  { const { s, bot } = upToPrompt(); const g = s.p.flipGood; for (let i = 0; i <= g; i++) Z.stepInPlace(s, bot.decide(s) & 1); Z.stepInPlace(s, (bot.decide(s) & 1) | 2);
    assert.equal(s.nLate, 1); assert.equal(s.flipPen, 25); assert.equal(s.flipOver, 60); assert.equal(s.flipAllGood, 0); }
  // 焦面：+30 tick
  { const { s, bot } = upToPrompt(); for (let i = 0; i < 30; i++) Z.stepInPlace(s, bot.decide(s) & 1); const burn = s.burn; Z.stepInPlace(s, (bot.decide(s) & 1) | 2);
    assert.equal(s.nBurnt, 1); assert.ok(burn > 0); assert.equal(s.flipPen, Math.min(200, 100 + burn)); }
  // 自动翻：从不按
  { const { s, bot } = upToPrompt(); while (s.flipIdx === 0 && s.end === END.RUNNING) { assert.ok(s.P <= 990000); Z.stepInPlace(s, bot.decide(s) & 1); }
    assert.equal(s.nAuto, 1); assert.equal(s.flipPen, 200); }
  // 离翻面点更远时按：无效
  { const s = Z.createGame(cfg); Z.stepInPlace(s, 3); assert.equal(s.flipIdx, 0); assert.equal(s.kickN, 0); }
});

test('烤炉：提示出现前按，按离翻面点还差几 tick 分档（≤3 正好 / ≤12 轻罚 / ≤20 重罚 / 更早无效）', () => {
  const cfg = { station: ST.OVEN, star: 3, chefLevel: 1, seed: 21, flips: 1 };
  // 用完美机器人跟条（每 tick 都框住，熟度每 tick 涨满速 g0），在提示前 k tick 按
  function pressAhead(k) {
    const s = Z.createGame(cfg); const bot = perfectBot(s);
    const mark = Z.markAt(s.p, 1, 1), g0 = Z.fullGain(s.p);
    assert.equal(g0, 4689);
    while (s.end === END.RUNNING && s.prompt < 0 && mark - s.P > k * g0) Z.stepInPlace(s, bot.decide(s) & 1);
    const d = mark - s.P;
    Z.stepInPlace(s, (bot.decide(s) & 1) | 2);
    return { s, d, g0 };
  }
  { const { s, d, g0 } = pressAhead(3); assert.ok(d <= 3 * g0 && d > 0);
    assert.equal(s.nGood, 1); assert.equal(s.flipIdx, 1); assert.equal(s.flipPen, 0); assert.equal(s.flipAllGood, 1); assert.equal(s.kickN, 1);
    const bot = perfectBot(s); while (s.end === END.RUNNING) { Z.stepInPlace(s, bot.decide(s) & 1); assert.equal(s.prompt, -1, '已经翻过，这个翻面点不再出提示'); }
    assert.equal(Z.result(s).perfect, 1); }
  { const { s, d, g0 } = pressAhead(10); assert.ok(d > 3 * g0 && d <= 12 * g0);
    assert.equal(s.nEarly, 1); assert.equal(s.flipPen, 25); assert.equal(s.flipUnder, 60); assert.equal(s.flipAllGood, 0); }
  { const { s, d, g0 } = pressAhead(18); assert.ok(d > 12 * g0 && d <= 20 * g0);
    assert.equal(s.nEarly, 1); assert.equal(s.flipPen, 60); assert.equal(s.flipUnder, 120); }
  { const { s, d, g0 } = pressAhead(24); assert.ok(d > 20 * g0);
    assert.equal(s.flipIdx, 0); assert.equal(s.flipPen, 0); assert.equal(s.kickN, 0); }
  // 一直狂按动作键：第一下落在重罚档，不会白拿「正好」
  { const s = Z.createGame(cfg); const bot = perfectBot(s);
    while (s.end === END.RUNNING) Z.stepInPlace(s, (bot.decide(s) & 1) | 2);
    assert.equal(s.nEarly, 1); assert.equal(s.nGood, 0); assert.equal(s.flipUnder, 120); }
});

test('备餐台：进入摆料段、冻结、漏拍/乱按/可以/正好；两段的旧口径仍可配置', () => {
  // 默认 ★6 只有一段，首拍提前 16 tick
  const one = Z.createGame({ station: ST.PREP, star: 6, chefLevel: 1, seed: 33, beats: 5 });
  assert.equal(one.segBeats.length, 1); assert.equal(one.segBeats[0][0], 16);
  // 两段（segmentsByStar 可配，内核两条路径都要测）
  const cfg = { station: ST.PREP, star: 6, chefLevel: 1, seed: 33, beats: 5, config: { segmentsByStar: [1, 1, 1, 1, 1, 2, 2] } };
  const s = Z.createGame(cfg);
  assert.equal(s.segBeats.length, 2);
  assert.deepEqual(s.segBeats.map((x) => x.length), [3, 2]);
  for (const seg of s.segBeats) { assert.equal(seg[0], 16); for (let i = 1; i < seg.length; i++) assert.ok(seg[i] - seg[i - 1] >= 7); }
  const bot = perfectBot(s);
  while (!s.inSeg && s.end === END.RUNNING) Z.stepInPlace(s, bot.decide(s));
  assert.equal(s.inSeg, 1);
  const frozen = { yt: s.yt, yb: s.yb, P: s.P, pt: s.pt, rTa: s.rTa, S: s.S };
  const Tm = s.segBeats[0];
  // 第 0 拍：偏 2 tick（L1 goodW=2 → 可以）；第 1 拍：漏（Tm[1]+3 记漏拍）；
  // Tm[2]−3（≥ Tm[1]+3 且离第 2 拍 3 > goodW）乱按一次；第 2 拍正好
  assert.ok(Tm[2] - 3 >= Tm[1] + 3);
  const plan = new Map([[Tm[0] + 2, 2], [Tm[2] - 3, 2], [Tm[2], 2]]);
  while (s.inSeg) Z.stepInPlace(s, (plan.get(s.segT) || 0) | 1);
  for (const [k, v] of Object.entries(frozen)) assert.equal(s[k], v, `摆料段内 ${k} 应冻结`);
  assert.equal(s.okB, 1); assert.equal(s.miss, 1); assert.equal(s.mash, 1); assert.equal(s.perfB, 1);
  assert.equal(s.segIdx, 1);
  while (s.end === END.RUNNING) Z.stepInPlace(s, bot.decide(s));
  const r = Z.result(s);
  assert.equal(r.perfect, 0);
  assert.equal(r.pen, 8 * 1 + 50 * 1 + 20 * 1);
  assert.equal(r.messPm, ((1 * 500 / 5) | 0) + 40);
  // 冷饮：等距
  const d = Z.createGame({ station: ST.PREP, star: 2, chefLevel: 1, seed: 1, drink: true });
  assert.deepEqual(d.segBeats, [[16, 27, 38, 49, 60, 71]]);
});

test('结算：分数线、上限、负面挑选（门槛 低 40 / 中 120 / 高 150）', () => {
  const base = Z.createGame({ station: ST.POT, star: 1, chefLevel: 1, seed: 1 });
  const mk = (o) => Object.assign(Z.cloneState(base), { hSum: 0 }, o);
  // 厨锅 avgH=1000 无扣分：C=970 → 超凡（出锅）
  let r = Z.result(mk({ end: END.DONE, S: 100, I: 97, U: 2, O: 1, hSum: 100000, perfect: 0 }));
  assert.equal(r.score, 970); assert.equal(r.quality, Q.EXTRAORDINARY); assert.deepEqual(r.negatives, []);
  // 到点：不是出锅，所以 970 分也只到「高」，再封顶「中」，必带夹生
  r = Z.result(mk({ end: END.TIMEOUT, S: 100, I: 97, U: 0, O: 3, hSum: 100000, perfect: 0 }));
  assert.equal(r.qualityRaw, Q.HIGH); assert.equal(r.quality, Q.MEDIUM); assert.deepEqual(r.negatives, [NEG.UNDERDONE]);
  // 低品质：门槛 40，最多 2 条，取最大的两个
  r = Z.result(mk({ end: END.DONE, S: 1000, I: 700, U: 100, O: 200, hSum: 1000000, perfect: 0 }));
  assert.equal(r.quality, Q.LOW); assert.deepEqual(r.negatives, [NEG.UNDERDONE, NEG.SCORCHED]);
  // 低品质 + 到点：已有两条（过火最大、欠火第二）→ 不变；若欠火不够门槛 → 补夹生、去掉最小
  r = Z.result(mk({ end: END.TIMEOUT, S: 1000, I: 650, U: 10, O: 340, hSum: 1000000, perfect: 0 }));
  assert.deepEqual(r.negatives, [NEG.UNDERDONE, NEG.SCORCHED]);
  // 高品质：门槛 150。厨锅追条丢 10% 也不带负面
  r = Z.result(mk({ end: END.DONE, S: 1000, I: 900, U: 0, O: 100, hSum: 1000000, perfect: 0 }));
  assert.equal(r.quality, Q.HIGH); assert.deepEqual(r.negatives, []);
  // 高品质只在明确事故时带负面：炸锅爆油 1 次（过火 +150）
  const fry = Z.createGame({ station: ST.FRYER, star: 1, chefLevel: 1, seed: 1 });
  r = Z.result(Object.assign(Z.cloneState(fry), { end: END.DONE, S: 1000, I: 990, U: 0, O: 10, bursts: 1, perfect: 0 }));
  assert.equal(r.score, 910); assert.equal(r.quality, Q.HIGH); assert.deepEqual(r.negatives, [NEG.SCORCHED]);
  // 中品质：门槛 120；欠火与过火相等时夹生优先
  r = Z.result(mk({ end: END.DONE, S: 1000, I: 800, U: 100, O: 100, hSum: 1000000, perfect: 0 }));
  assert.equal(r.quality, Q.MEDIUM); assert.deepEqual(r.negatives, []);
  r = Z.result(mk({ end: END.DONE, S: 1000, I: 760, U: 120, O: 120, hSum: 1000000, perfect: 0 }));
  assert.equal(r.quality, Q.MEDIUM); assert.deepEqual(r.negatives, [NEG.UNDERDONE]);
  // Perfect 必超凡；Perfect + 有瓶抓到 + 服务端判金瓶 = 闪耀；外部上限
  r = Z.result(mk({ end: END.DONE, S: 100, I: 90, U: 10, O: 0, hSum: 50000, perfect: 1 }));
  assert.equal(r.quality, Q.EXTRAORDINARY);
  const caught = { end: END.DONE, S: 100, I: 99, U: 1, O: 0, hSum: 100000, perfect: 1, bKind: BOTTLE_KIND.NORMAL, bState: BOTTLE.CAUGHT };
  r = Z.result(mk(caught), { gold: 1 });
  assert.equal(r.quality, Q.RADIANT); assert.equal(r.score, 990 + 40); assert.equal(r.gold, 1); assert.equal(r.bottleKind, BOTTLE_KIND.GOLD);
  r = Z.result(mk(caught));
  assert.equal(r.quality, Q.EXTRAORDINARY); assert.equal(r.gold, 0); assert.equal(r.bottleKind, BOTTLE_KIND.NORMAL);
  assert.equal(Z.result(mk(caught), { gold: 1, suspect: 40 }).quality, Q.EXTRAORDINARY);
  assert.equal(Z.result(mk({ end: END.DONE, S: 100, I: 99, U: 1, O: 0, hSum: 100000, perfect: 1 }), { suspect: 80 }).quality, Q.HIGH);
  assert.equal(Z.result(mk({ end: END.DONE, S: 100, I: 99, U: 1, O: 0, hSum: 100000, perfect: 1 }), { slow: 1 }).quality, Q.HIGH);
  assert.equal(Z.result(mk({ end: END.DONE, S: 100, I: 99, U: 1, O: 0, hSum: 100000, perfect: 1 }), { mechanical: 1 }).quality, Q.MEDIUM);
  // 糊锅：家常、无负面
  r = Z.result(mk({ end: END.BURNT, S: 100, I: 10, U: 90, O: 0 }));
  assert.equal(r.quality, Q.HOMESTYLE); assert.deepEqual(r.negatives, []);
});

test('机械节奏检测：交替宏命中；人类机器人与 10 Hz 轻点悬停不命中', () => {
  const p = Z.decodeParams(Z.buildParams({ station: 0, star: 1, chefLevel: 1 }));
  assert.equal(Z.detectMechanical(Array.from({ length: 200 }, (_, i) => i & 1), p), 1);
  assert.equal(Z.detectMechanical(Array.from({ length: 200 }, (_, i) => ((i / 3) | 0) & 1), p), 1);
  assert.equal(Z.detectMechanical(new Array(200).fill(1), p), 0);
  // 一个段长不一样就不算（100%）；段数不够 32 也不算
  const glitch = Array.from({ length: 200 }, (_, i) => i & 1); glitch[100] = glitch[99];
  assert.equal(Z.detectMechanical(glitch, p), 0);
  assert.equal(Z.detectMechanical(Array.from({ length: 60 }, (_, i) => i & 1), p), 0);
  let hits = 0, n = 0;
  for (const st of STATIONS) for (let g = 0; g < 25; g++) {
    const { state, inputs } = playGame({ station: st, star: 3, chefLevel: 5, seed: gameSeed(st, g) }, g % 2 ? 'good' : 'average');
    hits += Z.detectMechanical(inputs, state.p); n++;
  }
  assert.ok(hits <= 1, `人类机器人被误判 ${hits}/${n}`);
  let tapHits = 0;
  for (let g = 0; g < 200; g++) {
    for (const o of [{ period: 100, periodSd: 10, durSd: 10 }, { mode: 'rate', period: 100, periodSd: 10, dur: 40, durSd: 10 }]) {
      const { state, inputs } = playGame({ station: 0, star: 1, chefLevel: 10, motion: MO.SINK, seed: gameSeed(0, g) }, 'tap', g, o);
      tapHits += Z.detectMechanical(inputs, state.p);
    }
  }
  assert.ok(tapHits <= 2, `轻点悬停被误判 ${tapHits}/400`);
  const so = openLoopStreams();
  assert.ok(so.real <= 0.5, `开环 10 Hz 按键流误判 ${so.real}%`);
});

// ---------------------------------------------------------------------------
// 内联版与 golden
// ---------------------------------------------------------------------------
test('sim.inline.js 与 ES module 结果逐位一致', () => {
  const code = readFileSync(new URL('./sim.inline.js', import.meta.url), 'utf8');
  const src = readFileSync(new URL('./sim.js', import.meta.url), 'utf8');
  assert.ok(code.includes(src.slice(src.indexOf('const K_MAX'), src.indexOf('const K_MAX') + 200)), 'sim.inline.js 过期，请先 node build-inline.mjs');
  assert.ok(code.includes('export const SIM_VERSION') === false && code.includes(`const SIM_VERSION = ${Z.SIM_VERSION};`), 'sim.inline.js 的 simVersion 过期');
  const ctx = {}; vm.createContext(ctx); vm.runInContext(code, ctx);
  const IZ = ctx.ZhangshaoSim;
  assert.ok(IZ && typeof IZ.replay === 'function');
  for (const st of STATIONS) for (const star of [2, 6]) {
    const cfg = { station: st, star, chefLevel: 4, seed: 777 + st };
    const { inputs } = playGame(cfg, 'average', 3);
    assert.equal(JSON.stringify(IZ.replay(cfg, inputs, { gold: 1 })), JSON.stringify(Z.replay(cfg, inputs, { gold: 1 })));
  }
});

test('golden.json 回归（与 Java 对拍用的同一份数据）', () => {
  const g = JSON.parse(readFileSync(new URL('./golden.json', import.meta.url), 'utf8'));
  assert.equal(g.simVersion, Z.SIM_VERSION);
  assert.deepEqual(g.paramFields, Z.PARAM_FIELDS);
  assert.deepEqual(g.hashFields, Z.HASH_FIELDS);
  for (const v of g.vectors.mulberry32) { const r = new Z.Mulberry32(v.state); assert.deepEqual(v.next.map(() => r.next()), v.next); assert.equal(r.a, v.stateAfter); }
  for (const [x, y] of g.vectors.fmix32) assert.equal(Z.fmix32(x), y);
  assert.ok(g.cases.length >= 14);
  for (const c of g.cases) {
    const params = Z.buildParams(Object.assign({}, c.config, { seed: c.seed }), c.configOverride);
    assert.deepEqual(params, c.params, c.name + ' 参数块');
    assert.equal(Z.hex32(Z.paramsHash(params)), c.paramsHash);
    assert.equal(Z.hex32(Z.hashState(Z.initState(c.params, c.seed))), c.initHash, c.name + ' 开局哈希');
    const ins = Z.rleDecode(c.inputsRle);
    assert.equal(ins.length, c.inputCount);
    const tr = Z.replayTrace({ block: c.params, seed: c.seed }, ins, c.ext);
    assert.deepEqual(tr.hashes, c.hashes, c.name + ' 逐 20 tick 哈希');
    assert.equal(tr.finalHash, c.finalHash);
    assert.deepEqual(tr.result, c.result, c.name + ' 结算');
    assert.equal(tr.consumed, c.inputCount);
  }
});
