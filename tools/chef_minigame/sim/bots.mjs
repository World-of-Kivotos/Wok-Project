// 掌勺小游戏 · 机器人与大样本平衡仿真（simVersion 2）
// ---------------------------------------------------------------------------
// 机器人只通过 decide(state) → 输入字节 与内核交互，从不修改内核状态。
// 机器人自己可以用浮点和自己的随机数（它们模拟的是“人”，不属于内核）。
//
//   perfect  完美：读种子的“神谕”。克隆状态预演未来 24 个正式 tick 的食材轨迹
//            （食材 AI 与火候条无关），再对火候条做深度优先搜索，保证食材不脱条；
//            动作键按最优时机（炸锅贪心压火、烤炉提示第一 tick 翻面、备餐台 0 偏差）。
//            用来证明 Perfect / 闪耀“可达”，也是分布的理论上界。
//   expert   高手：反应 0.15 秒
//   good     熟练：反应 0.2 秒、感知误差、按键最短间隔
//   average  普通：反应 0.25 秒
//   novice   新手：反应 0.3 秒、误差大、按键慢
//   random   乱按：随机开关主键、随机点动作键
//   tap      轻点悬停的真人：毫秒级按键（每次按约 40 ms、周期约 100 ms），用来回归「机械节奏」误判率
//   release / hold / toggle1 / toggle6  挂机与宏
//
// 人类机器人的可选行为（opts，默认都关；审查后补的三种「bot 没覆盖的真人行为」）：
//   attention: k   按一次动作键后 k tick 顾不上火候条（分心）
//   glance: G      炸锅：每隔约 G 秒瞟一眼油温，瞟的 0.3 秒里看不到食材（只在瞟的时候压火）
//   flip: 'anticipate', flipSd, flipBias   烤炉：盯着熟度条三角预判翻面时刻（不等「翻面！」提示）
//
// 金瓶：内核不知道瓶的种类（spec §10）。仿真里用 privateGold(seed, p) 代替服务端的私下抽签。
//
// 用法：
//   node bots.mjs balance [每格局数=300] [--workers=N]   → 写 balance.md / balance.json
//   node bots.mjs cell <台0-3> <★> <等级> <skill> [局数]  → 打印单格统计
// ---------------------------------------------------------------------------
import * as Z from './sim.js';

const { STATION, END, QUALITY, BOTTLE, BOTTLE_KIND, NONE } = Z;

// 人类机器人参数（与 spec §16 校准草稿一致）
// R 反应延迟(tick) sigma 感知误差(u) k 速度前瞻(tick) minT 最短按键间隔 alpha 外推比例
// dead 死区(u) flipSd 翻面抖动 tapSd 节拍抖动 oilSd 油温误判 greed 贪瓶倾向
export const SKILLS = Object.freeze({
  novice: { R: 6, sigma: 70000, k: 3, minT: 2, alpha: 0.3, dead: 25000, flipSd: 4, tapSd: 1.8, oilSd: 60, greed: 0.4 },
  average: { R: 5, sigma: 40000, k: 4, minT: 1, alpha: 0.5, dead: 15000, flipSd: 2.5, tapSd: 1.3, oilSd: 40, greed: 0.7 },
  good: { R: 4, sigma: 22000, k: 5, minT: 1, alpha: 0.7, dead: 10000, flipSd: 1.5, tapSd: 0.9, oilSd: 25, greed: 1 },
  expert: { R: 3, sigma: 12000, k: 6, minT: 1, alpha: 0.85, dead: 6000, flipSd: 0.9, tapSd: 0.65, oilSd: 15, greed: 1 },
});
export const SKILL_NAMES = Object.freeze({
  perfect: '完美', expert: '高手', good: '熟练', average: '普通', novice: '新手', random: '乱按', tap: '轻点悬停',
  release: '全松', hold: '全按', toggle1: '每tick交替', toggle6: '每6tick开关',
});

export function lcg(seed) {
  let s = seed >>> 0 || 1;
  return () => { s = (Math.imul(s, 1664525) + 1013904223) >>> 0; return s / 4294967296; };
}
export function gauss(rng) {
  let u = 0; while (u === 0) u = rng();
  const v = rng();
  return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v);
}

/** 服务端私下抽「这只瓶是不是金瓶」的替身（正式服用 SecureRandom，不从种子推出；仿真要可复现，所以按种子派生）。 */
export function privateGold(seed, p) {
  if (p.bottlePm <= 0 || p.goldPm <= 0) return 0;
  const h = Z.fmix32(seed ^ 0x60D1D);
  return ((h >>> 1) % p.bottlePm) < p.goldPm ? 1 : 0;
}

// ---------------------------------------------------------------------------
// 人类机器人：带反应延迟、感知噪声（AR(1)）、死区与最短按键间隔的 PD 控制器
// ---------------------------------------------------------------------------
export function humanBot(skill, seed, o = {}) {
  const sk = SKILLS[skill];
  if (!sk) throw new Error('unknown skill ' + skill);
  const rnd = lcg(seed ^ 0x5bd1e995);
  const hist = [], Thist = [], Phist = [];
  let hold = 0, since = 99, noise = 0, flipAt = -1, lastPrompt = -2, tapPlan = null, segSeen = -1, freeze = 0;
  const G = o.glance || 0;
  let nextGlance = G ? Math.round(G * 20 * (0.5 + rnd())) : 0, glanceLeft = 0, glanceIdx = 0;
  let antIdx = -1, antPlanned = false;
  return {
    name: skill,
    decide(s) {
      const p = s.p;
      if (s.inSeg) {
        if (segSeen !== s.segIdx) {
          segSeen = s.segIdx;
          tapPlan = s.segBeats[s.segIdx].map((t) => t + Math.round(gauss(rnd) * sk.tapSd));
        }
        return tapPlan.includes(s.segT) ? 2 : 0;
      }
      hist.push(s.yt); Thist.push(s.T); Phist.push(s.P);
      let act = 0;
      let i = Math.max(0, hist.length - 1 - sk.R);
      if (p.station === STATION.FRYER && G > 0) {
        if (glanceLeft === 0 && s.pt >= nextGlance) { glanceLeft = 6; glanceIdx = i; }
        if (glanceLeft > 0) {
          glanceLeft--;
          if (s.cd === 0 && s.T + gauss(rnd) * sk.oilSd >= 600) act = 2;
          if (glanceLeft === 0) nextGlance = s.pt + Math.max(6, Math.round(G * 20 * (0.7 + 0.6 * rnd())));
          i = glanceIdx;            // 瞟油温的 0.3 秒里，食材停在最后看到的位置
        }
      }
      const seen = hist[i], prev = hist[Math.max(0, i - 1)];
      noise = noise * 0.9127 + gauss(rnd) * sk.sigma * 0.408;
      const est = seen + (seen - prev) * sk.R * sk.alpha + noise;
      let desired = est;
      if (s.bState === BOTTLE.PRESENT) {
        if (Math.abs(s.bPos - est) < p.H * 0.7) desired = (s.bPos + est) / 2;
        else if (rnd() < sk.greed * 0.02 && !s.perfect && s.P > 550000) desired = s.bPos;
      }
      const c = s.yb + p.H / 2 + s.vb * sk.k;
      let want = hold;
      if (c < desired - sk.dead) want = 1; else if (c > desired + sk.dead) want = 0;
      since++;
      if (freeze > 0) freeze--;
      else if (want !== hold && since >= sk.minT) { hold = want; since = 0; }
      if (p.station === STATION.FRYER && !G) {
        const Ts = Thist[Math.max(0, Thist.length - 1 - sk.R)] + gauss(rnd) * sk.oilSd;
        if (s.cd === 0 && Ts >= 640) act = 2;
      } else if (p.station === STATION.OVEN) {
        if (o.flip === 'anticipate') {
          // 预判：看熟度条最近 0.4 秒涨得多快，估「离三角还差几 tick」，瞄准提示出现的那一 tick 按，带 σ 的计时误差；
          // 提示先出来了就按反应型兜底
          if (s.flipIdx !== antIdx) { antIdx = s.flipIdx; antPlanned = false; flipAt = -1; }
          if (s.flipIdx < p.F) {
            if (!antPlanned && s.prompt < 0 && Phist.length > 8) {
              const d = Z.markAt(p, s.flipIdx + 1, p.F) - s.P, rate = (s.P - Phist[Phist.length - 9]) / 8;
              const ttc = rate > 0 ? Math.ceil(d / rate) : 99;
              if (ttc <= 10) { antPlanned = true; flipAt = s.pt + ttc + Math.round((o.flipBias || 0) + gauss(rnd) * (o.flipSd ?? 2)); }
            }
            if (s.prompt >= 0 && s.prompt !== lastPrompt) {
              lastPrompt = s.prompt;
              const reactAt = s.prompt + sk.R + Math.round(Math.abs(gauss(rnd)) * sk.flipSd);
              if (flipAt < 0 || flipAt > reactAt) flipAt = reactAt;
              antPlanned = true;
            }
            if (flipAt >= 0 && s.pt >= flipAt) { act = 2; flipAt = -1; }
          }
        } else {
          if (s.prompt >= 0 && s.prompt !== lastPrompt) {
            lastPrompt = s.prompt;
            flipAt = s.prompt + sk.R + Math.round(Math.abs(gauss(rnd)) * sk.flipSd);
          }
          if (flipAt >= 0 && s.pt >= flipAt) { act = 2; flipAt = -1; }
        }
      }
      if (act && o.attention) freeze = o.attention;
      return hold | act;
    },
  };
}

// ---------------------------------------------------------------------------
// 轻点悬停的真人（毫秒级按键 → 每 50 ms 采样，带轻点锁存，与网页/客户端的采样口径一致）
//   周期 period±periodSd ms，每次按下时长 = 占空比 × 周期 ± durSd（占空比由 PD 控制器给出）；
//   想往上就按长一点（> 0.85 直接按住），想往下就按短一点（< 0.15 停手）。
// ---------------------------------------------------------------------------
export function tapBot(seed, o = {}) {
  const sk = SKILLS[o.skill || 'good'];
  const per = o.period ?? 100, perSd = o.periodSd ?? 10, durSd = o.durSd ?? 10, rateMode = o.mode === 'rate', dur0 = o.dur ?? 40;
  const rnd = lcg(seed ^ 0x7A93C1);
  const hist = [];
  let noise = 0, tNext = rnd() * per, pressEnd = -1;
  return {
    name: 'tap',
    decide(s) {
      const p = s.p;
      if (s.inSeg) return 0;
      hist.push(s.yt);
      const i = Math.max(0, hist.length - 1 - sk.R), seen = hist[i], prev = hist[Math.max(0, i - 1)];
      noise = noise * 0.9127 + gauss(rnd) * sk.sigma * 0.408;
      const est = seen + (seen - prev) * sk.R * sk.alpha + noise;
      const c = s.yb + p.H / 2 + s.vb * sk.k;
      const duty = Math.max(0, Math.min(1, 0.5 + (est - c) / (p.H * 0.8)));
      const t0 = s.tick * 50, t1 = t0 + 50;
      let bit = 0;
      while (tNext <= t1) {
        let gap = per;
        if (tNext > t0) {
          if (duty >= 0.15) {
            // 占空比模式：周期固定、按得长短跟着走；频率模式（审查的模型）：每次按约 40 ms、最快 ~10.5 Hz，想往下就点得稀
            const dur = duty > 0.85 ? per : rateMode ? Math.max(15, dur0 + gauss(rnd) * durSd) : Math.max(15, Math.min(per - 15, duty * per + gauss(rnd) * durSd));
            pressEnd = tNext + dur; bit = 1;
            if (rateMode) gap = Math.max(95, per * 0.5 / Math.max(0.15, duty));
          }
        }
        tNext += Math.max(40, gap + gauss(rnd) * perSd);
      }
      if (pressEnd > t1) bit = 1;
      return bit;
    },
  };
}

// ---------------------------------------------------------------------------
// 完美机器人（神谕）
// ---------------------------------------------------------------------------
/** 完美的动作键策略（只看状态）。 */
export function perfectAct(s) {
  const p = s.p;
  if (s.inSeg) {
    const Tm = s.segBeats[s.segIdx];
    return (s.bi < Tm.length && s.segT === Tm[s.bi]) ? 2 : 0;
  }
  if (p.station === STATION.FRYER) {
    // 本 tick 的 stationPre 先 cd−1 再判；按下后 T−drop+本 tick 升温 仍 ≥ 冷油线就按（贪心压火）
    if (s.cd <= 1) {
      const rise = p.oilRiseIn + (((p.star - 1) / p.oilRiseStarDiv) | 0) + ((s.P / p.oilRiseProgDiv) | 0);
      if (s.T - p.oilDrop + rise >= p.oilCold) return 2;
    }
    return 0;
  }
  if (p.station === STATION.OVEN) return (s.prompt >= 0 && s.pt >= s.prompt) ? 2 : 0;
  return 0;
}

/** 预演未来 K 个正式 tick 的食材位置（假设之后全程框住）。 */
function predictFish(s, predP, K) {
  const c = Object.assign({}, s);
  c.p = predP;
  const out = [];
  let guard = 600;
  while (out.length < K && c.end === END.RUNNING && guard-- > 0) {
    const wasSeg = c.inSeg;
    Z.stepInPlace(c, perfectAct(c));
    if (!wasSeg) out.push(c.yt);
  }
  return out;
}

/** 对火候条做 DFS：先找全程框住的路径，找不到再允许单 tick 擦边。返回本 tick 的 hold。 */
function planHold(s, fish, bottlePos, budget0) {
  const p = s.p, H = p.H, top = p.track - H, PIN = p.pinTicks;
  const K = fish.length;
  if (K === 0) return 0;
  const half = (H / 2) | 0, margin = (H / 8) | 0;
  let budget = 0, found = -1, bestDepth = -1, bestFirst = 0, bestStrict = false;

  function dfs(d, yb, vb, inPrev, pin, orun, first, maxOut) {
    if (d === K) { found = first; return true; }
    if (--budget < 0) return false;
    const look = fish[d + 3 < K ? d + 3 : K - 1];
    let tgt = look;
    if (bottlePos >= 0 && Math.abs(bottlePos - look) < H - margin) tgt = (bottlePos + look) / 2;
    const c = yb + half + vb * 4;
    const pref = c < tgt ? 1 : 0;
    for (let i = 0; i < 2; i++) {
      const h = i === 0 ? pref : 1 - pref;
      let v = vb, y = yb;
      if (h && (y === 0 || y === top)) v = 0;
      let a = h ? p.accel : -p.accel;
      if (inPrev) a = Z.tdiv(a * p.inBarNum, p.inBarDen);
      v += a; y += v;
      if (y > top) { y = top; v = -Z.tdiv(v * p.bounceTopNum, p.bounceDen); }
      else if (y < 0) { y = 0; v = -Z.tdiv(v * p.BB, p.bounceDen); }
      const pn = (y === 0 || y === top) ? pin + 1 : 0;
      const f = fish[d];
      const inb = (y <= f && f <= y + H && pn < PIN) ? 1 : 0;
      const o2 = inb ? 0 : orun + 1;
      if (o2 > maxOut) continue;
      const fst = d === 0 ? h : first;
      if (d + 1 > bestDepth || (d + 1 === bestDepth && maxOut === 0 && !bestStrict)) {
        bestDepth = d + 1; bestFirst = fst; bestStrict = maxOut === 0;
      }
      if (dfs(d + 1, y, v, inb, pn, o2, fst, maxOut)) return true;
      if (budget < 0) return false;
    }
    return false;
  }
  for (const maxOut of [0, 1]) {
    budget = budget0; found = -1;
    if (dfs(0, s.yb, s.vb, s.inPrev, s.pin, s.outRun, 0, maxOut)) return found;
  }
  return bestFirst;
}

export function perfectBot(s0, opts = {}) {
  const p = s0.p;
  const predP = Object.freeze(Object.assign({}, p, { H: p.track, pinTicks: 1 << 30 }));
  const K = opts.horizon ?? 24, budget = opts.budget ?? 3000, chase = opts.chaseBottle ?? true;
  return {
    name: 'perfect',
    decide(s) {
      const act = perfectAct(s);
      if (s.inSeg) return act;
      const fish = predictFish(s, predP, K);
      const bp = (chase && s.bState === BOTTLE.PRESENT) ? s.bPos : -1;
      return planHold(s, fish, bp, budget) | act;
    },
  };
}

// ---------------------------------------------------------------------------
// 乱按与宏
// ---------------------------------------------------------------------------
export function randomBot(seed) {
  const rnd = lcg(seed ^ 0x2545F491);
  let hold = 0;
  return { name: 'random', decide() { if (rnd() < 0.35) hold ^= 1; return hold | (rnd() < 0.1 ? 2 : 0); } };
}
export function macroBot(kind) {
  const f = {
    release: () => 0,
    hold: () => 1,
    toggle1: (t) => t & 1,
    toggle6: (t) => ((t / 6) | 0) & 1,
  }[kind];
  if (!f) throw new Error('unknown macro ' + kind);
  return { name: kind, decide(s) { return f(s.tick); } };
}

export function makeBot(kind, s0, seed, opts) {
  if (kind === 'perfect') return perfectBot(s0, opts || {});
  if (SKILLS[kind]) return humanBot(kind, seed, opts || {});
  if (kind === 'tap') return tapBot(seed, opts || {});
  if (kind === 'random') return randomBot(seed);
  return macroBot(kind);
}

/** 实时跑一局：每 tick 让机器人看状态出输入，记录输入流。 */
export function playGame(config, kind, botSeed, botOpts) {
  const s = Z.createGame(config);
  const bot = makeBot(kind, s, botSeed ?? (config.seed ^ 0x51ED270B), botOpts);
  const inputs = [];
  while (s.end === END.RUNNING) {
    const inp = bot.decide(s) & 3;
    inputs.push(inp);
    Z.stepInPlace(s, inp);
    if (inputs.length > 20000) throw new Error('game did not end');
  }
  return { state: s, inputs };
}

// ---------------------------------------------------------------------------
// 统计
// ---------------------------------------------------------------------------
export function gameSeed(station, i) {
  // 与 ★、等级、技能无关：同一格子行/列之间用同一批种子（公共随机数，比较更稳）
  return Z.fmix32((Math.imul(i + 1, 0x9E3779B1) ^ Math.imul(station + 1, 0x85EBCA6B)) | 0);
}

export function runCell({ station, star, level, skill, n, motion, extra, tag, bot, seedBase, dishes }) {
  const st = { station, star, level, skill, n, motion: motion ?? null, extra: extra ?? null, tag: tag ?? null, bot: bot ?? null, dishes: dishes ?? null,
    q: [0, 0, 0, 0, 0, 0], // 翻车(糊锅) 低 中 高 超凡 闪耀
    qCapped: [0, 0, 0, 0, 0, 0], // 套上机械节奏封顶后
    timeout: 0, perfect: 0, ticks: 0, durs: [], scoreSum: 0, mech: 0,
    goldSeen: 0, goldCaught: 0, bottleSeen: 0, bottleCaught: 0, negAny: 0, negMid: 0, midN: 0, negHigh: 0, highN: 0, negLow: 0, lowN: 0,
    flipGood: 0, flipEarly: 0, flipLate: 0, flipBurnt: 0, flipAuto: 0, flipN: 0, burstGames: 0, hot: 0, cold: 0, S: 0 };
  for (let i = 0; i < n; i++) {
    const seed = gameSeed(station, i + (seedBase || 0));
    const cfg = Object.assign({ station, star, chefLevel: level, seed }, extra || {});
    if (motion !== undefined && motion !== null) cfg.motion = motion;
    const { state, inputs } = playGame(cfg, skill, Z.fmix32(seed ^ 0x1234567), bot || undefined);
    const gold = state.bKind !== BOTTLE_KIND.NONE ? privateGold(seed, state.p) : 0;
    const r = Z.result(state, { gold });
    const mechanical = Z.detectMechanical(inputs, state.p);
    st.qCapped[Z.result(state, { gold, mechanical }).quality + 1]++;
    st.q[r.quality + 1]++;
    if (r.end === END.TIMEOUT) st.timeout++;
    if (r.perfect) st.perfect++;
    st.ticks += state.tick; st.durs.push(state.tick);
    st.scoreSum += r.quality === QUALITY.HOMESTYLE ? 0 : r.score;
    if (mechanical) st.mech++;
    if (state.bKind !== BOTTLE_KIND.NONE) st.bottleSeen++;
    if (gold) { st.goldSeen++; if (r.bottle) st.goldCaught++; }
    if (r.bottle) st.bottleCaught++;
    if (r.negatives.length) st.negAny++;
    if (r.quality === QUALITY.LOW) { st.lowN++; if (r.negatives.length) st.negLow++; }
    if (r.quality === QUALITY.MEDIUM) { st.midN++; if (r.negatives.length) st.negMid++; }
    if (r.quality === QUALITY.HIGH) { st.highN++; if (r.negatives.length) st.negHigh++; }
    st.flipGood += r.flipGood; st.flipEarly += r.flipEarly; st.flipLate += r.flipLate; st.flipBurnt += r.flipBurnt; st.flipAuto += r.flipAuto;
    st.flipN += r.flipGood + r.flipEarly + r.flipLate + r.flipBurnt + r.flipAuto;
    if (r.bursts) st.burstGames++;
    st.hot += state.hot; st.cold += state.cold; st.S += state.S;
  }
  st.durs.sort((a, b) => a - b);
  st.d50 = st.durs[n >> 1]; st.d90 = st.durs[Math.floor(n * 0.9)];
  delete st.durs;
  return st;
}

// 质量点：翻车 0、低 1、中 2、高 3、超凡/闪耀 4（单调性检查用）
export function qualityPoints(c) {
  return (c.q[1] * 1 + c.q[2] * 2 + c.q[3] * 3 + (c.q[4] + c.q[5]) * 4) / c.n;
}
const exPlus = (c) => 100 * (c.q[4] + c.q[5]) / c.n;

// ---------------------------------------------------------------------------
// 平衡报告
// ---------------------------------------------------------------------------
const ST_NAMES = ['厨锅', '炸锅', '烤炉', '备餐台'];
const ST_MOTION = ['混', '浮', '混', '混'];
const LEVELS = [1, 5, 10];
const MAIN_SKILLS = ['perfect', 'expert', 'good', 'average', 'novice', 'random'];
const MOTION_NAMES = ['混', '窜', '稳', '沉', '浮'];
const REC_LEVEL = [1, 2, 3, 5, 6, 8, 9];

function pct(x, n, d = 1) { return (100 * x / Math.max(1, n)).toFixed(d); }
function fmtDist(c) {
  const n = c.n, q = c.q;
  const r = (k) => { const v = 100 * q[k] / n; return v === 0 ? '0' : v < 0.1 ? '<0.1' : v < 10 ? v.toFixed(1) : v.toFixed(0); };
  return `${r(1)}/${r(2)}/${r(3)}/${r(4)}/${r(5)}/${r(0)}`;
}
const SUM_FIELDS = ['timeout', 'perfect', 'ticks', 'mech', 'goldSeen', 'goldCaught', 'bottleSeen', 'bottleCaught', 'negAny', 'negMid', 'midN', 'negHigh', 'highN', 'negLow', 'lowN', 'scoreSum',
  'flipGood', 'flipEarly', 'flipLate', 'flipBurnt', 'flipAuto', 'flipN', 'burstGames', 'hot', 'cold', 'S'];
function merge(cells) {
  const m = { n: 0, q: [0, 0, 0, 0, 0, 0] };
  for (const f of SUM_FIELDS) m[f] = 0;
  for (const c of cells) {
    m.n += c.n; for (let k = 0; k < 6; k++) m.q[k] += c.q[k];
    for (const f of SUM_FIELDS) m[f] += c[f] || 0;
  }
  return m;
}
function comboName(c) {
  const e = c.extra || {};
  return MOTION_NAMES[c.motion] + (e.flips > 1 ? `·翻${e.flips}` : '') + (e.drink ? '·冷饮' : '');
}

/** 开环按键流的机械节奏误判率（%）：毫秒级按键 → 每 50 ms 采样（按住或自上次采样以来按下过 → 1）。 */
export function openLoopStreams() {
  const p = Z.decodeParams(Z.buildParams({ station: 0, star: 1, chefLevel: 1 }));
  function stream(rng, ticks, periodMs, periodSd, durMs, durSd) {
    const presses = [];
    let t = rng() * 100;
    while (t < ticks * 50 + 200) {
      const d = Math.max(15, Math.min(periodMs - 10, durMs + gauss(rng) * durSd));
      presses.push([t, t + d]);
      t += Math.max(40, periodMs + gauss(rng) * periodSd);
    }
    const out = [];
    let pi = 0;
    for (let k = 1; k <= ticks; k++) {
      const now = k * 50, prev = (k - 1) * 50;
      let b = 0;
      for (let j = Math.max(0, pi - 2); j < presses.length && presses[j][0] <= now; j++) {
        const [s, e] = presses[j];
        if ((s > prev && s <= now) || (s <= now && e > now)) b = 1;
      }
      while (pi < presses.length && presses[pi][1] < prev) pi++;
      out.push(b);
    }
    return out;
  }
  let real = 0, extreme = 0;
  for (const ticks of [160, 240]) for (const psd of [5, 10, 20]) for (const [dur, dsd] of [[35, 8], [50, 12], [70, 15]]) {
    const rng = lcg(ticks * 7919 + psd * 131 + dur);
    let hit = 0; const M = 2000;
    for (let i = 0; i < M; i++) hit += Z.detectMechanical(stream(rng, ticks, 100, psd, dur, dsd), p);
    const r = 100 * hit / M;
    if (psd >= 10) real = Math.max(real, r); else extreme = Math.max(extreme, r);
  }
  const macro = Z.detectMechanical(Array.from({ length: 160 }, (_, i) => i & 1), p) && Z.detectMechanical(Array.from({ length: 200 }, (_, i) => ((i / 3) | 0) & 1), p);
  return { real: macro ? real : 100, extreme };
}

export function analyze(cells, N, ex = []) {
  const get = (skill, station, star, level) => cells.find((c) => c.skill === skill && c.station === station && c.star === star && c.level === level);
  const by = (tag) => ex.filter((c) => c.tag === tag);
  const checks = [];
  const add = (ok, name, detail) => checks.push({ ok, name, detail });
  const TOL = 0.06; // 质量点允许的抽样噪声（每格 300 局时质量点标准误约 0.05）
  // 1 难度随星级单调（质量点不升）
  for (const skill of ['expert', 'good', 'average', 'novice']) {
    const viol = [];
    for (let station = 0; station < 4; station++) for (const level of LEVELS) {
      for (let star = 2; star <= 7; star++) {
        const a = qualityPoints(get(skill, station, star - 1, level)), b = qualityPoints(get(skill, station, star, level));
        if (b > a + TOL) viol.push(`${ST_NAMES[station]} L${level} ★${star - 1}→★${star}: ${a.toFixed(2)}→${b.toFixed(2)}`);
      }
    }
    const pv = [];
    for (const level of LEVELS) for (let star = 2; star <= 7; star++) {
      const a = qualityPoints(merge([0, 1, 2, 3].map((st) => get(skill, st, star - 1, level))));
      const b = qualityPoints(merge([0, 1, 2, 3].map((st) => get(skill, st, star, level))));
      if (b > a + 0.02) pv.push(`L${level} ★${star - 1}→★${star}: ${a.toFixed(2)}→${b.toFixed(2)}`);
    }
    add(viol.length === 0 && pv.length === 0, `${SKILL_NAMES[skill]}：难度随星级单调`, viol.length + pv.length === 0 ? '各台各等级、四台合并均单调（质量点容差 ±0.06 / 合并 ±0.02）' : [...pv.map((x) => '合并 ' + x), ...viol].join('；'));
  }
  // 2 难度随厨师等级下降（质量点不降）
  for (const skill of ['expert', 'good', 'average', 'novice']) {
    const viol = [];
    for (let station = 0; station < 4; station++) for (let star = 1; star <= 7; star++) {
      for (let li = 1; li < 3; li++) {
        const a = qualityPoints(get(skill, station, star, LEVELS[li - 1])), b = qualityPoints(get(skill, station, star, LEVELS[li]));
        if (b + TOL < a) viol.push(`${ST_NAMES[station]} ★${star} L${LEVELS[li - 1]}→L${LEVELS[li]}: ${a.toFixed(2)}→${b.toFixed(2)}`);
      }
    }
    add(viol.length === 0, `${SKILL_NAMES[skill]}：难度随厨师等级下降`, viol.length ? viol.join('；') : '全部满足');
  }
  // 3 闪耀：熟练玩家稀有但可达（有大样本时用大样本）
  {
    const big = by('radiant');
    const src = big.length ? big : cells;
    const g10 = merge(src.filter((c) => c.skill === 'good' && c.level === 10));
    const rate10 = 100 * g10.q[5] / g10.n;
    let maxCell = 0, maxName = '';
    for (const c of src.filter((x) => x.skill === 'good')) { const r = 100 * c.q[5] / c.n; if (r > maxCell) { maxCell = r; maxName = `${ST_NAMES[c.station]} ★${c.star} L${c.level}`; } }
    add(g10.q[5] > 0 && rate10 <= 2.5 && maxCell <= 5, '熟练：闪耀稀有但可达（L10 合计 ≤ 2.5%，单格 ≤ 5%）', `L10 全部格合计 ${rate10.toFixed(2)}%（${g10.q[5]}/${g10.n}${big.length ? '，大样本' : ''}）；单格最高 ${maxCell.toFixed(1)}%（${maxName}）`);
    const a10 = merge(src.filter((c) => c.skill === 'average' && c.level === 10)), e10 = merge(src.filter((c) => c.skill === 'expert' && c.level === 10));
    if (a10.n && e10.n) {
      const ra = 100 * a10.q[5] / a10.n, re = 100 * e10.q[5] / e10.n;
      add(ra >= 0.3 && ra <= 1 && re >= 1 && re <= 3, '闪耀率落在 spec §1 目标（普通 L10 0.3–1%，高手 L10 1–3%）', `普通 L10 ${ra.toFixed(2)}%，高手 L10 ${re.toFixed(2)}%`);
    }
    const p10 = merge(cells.filter((c) => c.skill === 'perfect' && c.level === 10));
    add(p10.q[5] > 0, '完美：闪耀可达', `L10 合计 ${pct(p10.q[5], p10.n, 2)}%（理论上界：要出金瓶，还要在不脱手的前提下抓到瓶）`);
  }
  // 3b 手熟不让闪耀变少（大样本）
  {
    const ms = by('masteryRad');
    if (ms.length) {
      const parts = []; let ok = true;
      for (const skill of ['good', 'expert']) {
        const m0 = merge(ms.filter((c) => c.skill === skill && c.extra.mastery === 0)), m5 = merge(ms.filter((c) => c.skill === skill && c.extra.mastery === 5));
        const r0 = m0.q[5] / m0.n, r5 = m5.q[5] / m5.n;
        const h0 = r0 * 3600 / (m0.ticks / m0.n / 20 + 2.5), h5 = r5 * 3600 / (m5.ticks / m5.n / 20 + 2.5);
        if (r5 < 0.8 * r0) ok = false;
        parts.push(`${SKILL_NAMES[skill]} L10 手熟 0→5：单局 ${(100 * r0).toFixed(2)}%→${(100 * r5).toFixed(2)}%，每小时 ${h0.toFixed(1)}→${h5.toFixed(1)} 个（不计冷却，${m0.n} 局/档）`);
      }
      add(ok, '手熟不让闪耀明显变少（手熟 5 单局 ≥ 手熟 0 的 80%）', parts.join('；'));
    }
  }
  // 4 新手 ★1：不至于总翻车；熬不出来的局在时限（正式段 15 秒，备餐台 12 秒 + 摆料段）就结束
  {
    const parts = [];
    let ok = true;
    for (let station = 0; station < 4; station++) {
      const c = get('novice', station, 1, 1);
      const burnt = 100 * c.q[0] / c.n, low = 100 * c.q[1] / c.n, to = 100 * c.timeout / c.n;
      parts.push(`${ST_NAMES[station]} 翻车 ${burnt.toFixed(1)}% / 低 ${low.toFixed(0)}% / 到点 ${to.toFixed(0)}% / 中位 ${(c.d50 / 20).toFixed(1)}s / 90 分位 ${(c.d90 / 20).toFixed(1)}s`);
      if (burnt > 20 || c.d90 / 20 > (station === 3 ? 17 : 15)) ok = false;
    }
    add(ok, '新手 L1★1：翻车 ≤ 20%，90 分位时长 ≤ 15 秒（备餐台 ≤ 17 秒，含摆料段）', parts.join('；'));
  }
  // 5 乱按：出不了中及以上；低等级基本翻车
  {
    const r = merge(cells.filter((c) => c.skill === 'random'));
    const r15 = merge(cells.filter((c) => c.skill === 'random' && c.level <= 5));
    const r10 = merge(cells.filter((c) => c.skill === 'random' && c.level === 10));
    const midPlus = r.q[2] + r.q[3] + r.q[4] + r.q[5];
    add(midPlus / r.n <= 0.01 && r15.q[0] / r15.n >= 0.95, '乱按：出不了中及以上（≤ 1%），L1/L5 翻车 ≥ 95%',
      `中及以上 ${pct(midPlus, r.n, 2)}%；L1/L5 翻车 ${pct(r15.q[0], r15.n)}%；L10 翻车 ${pct(r10.q[0], r10.n)}%、出低 ${pct(r10.q[1], r10.n)}%`);
  }
  // 6 完美机器人每台每星都拿到过 Perfect
  {
    const miss = [];
    for (let station = 0; station < 4; station++) for (let star = 1; star <= 7; star++) {
      const tot = LEVELS.reduce((a, L) => a + get('perfect', station, star, L).perfect, 0);
      if (tot === 0) miss.push(`${ST_NAMES[station]} ★${star}`);
    }
    add(miss.length === 0, '完美机器人：每台每星都能拿 Perfect', miss.length ? '没拿到：' + miss.join('、') : '全部可达');
  }
  // 7 四台差距：同档、同星、同级的超凡+ 极差 ≤ 10 个点（不分心 / 按动作键后分心 3 tick 各跑一遍）
  {
    const four = by('four');
    if (four.length) {
      const parts = []; let ok = true;
      for (const att of [0, 3]) for (const [skill, L, star] of [['average', 5, 3], ['good', 10, 5], ['good', 5, 4]]) {
        const ex4 = [0, 1, 2, 3].map((st) => exPlus(four.find((c) => c.skill === skill && c.level === L && c.star === star && c.station === st && (c.bot ? c.bot.attention || 0 : 0) === att)));
        const rg = Math.max(...ex4) - Math.min(...ex4);
        if (rg > 10) ok = false;
        parts.push(`${att ? '分心3 ' : ''}${SKILL_NAMES[skill]} L${L}★${star}：${ex4.map((x, i) => ST_NAMES[i] + ' ' + x.toFixed(0)).join(' / ')}（极差 ${rg.toFixed(0)}）`);
      }
      add(ok, `四台差异：超凡+ 极差 ≤ 10 个点（每格 ${four[0].n} 局）`, parts.join('；'));
    }
  }
  // 8 真实菜的性子矩阵：同台同星、真实菜用到的各种（性子 × 翻面 × 冷饮），普通 L5 与熟练 L10 的质量点差 ≤ 0.25
  {
    const dm = by('dishMotion');
    if (dm.length) {
      const bad = [], worst = [];
      for (const [skill, L] of [['average', 5], ['good', 10]]) for (let station = 0; station < 4; station++) for (let star = 1; star <= 7; star++) {
        const cs = dm.filter((c) => c.skill === skill && c.level === L && c.station === station && c.star === star);
        if (cs.length < 2) continue;
        const v = cs.map((c) => [comboName(c), qualityPoints(c)]).sort((a, b) => a[1] - b[1]);
        const rg = v[v.length - 1][1] - v[0][1];
        worst.push([rg, `${SKILL_NAMES[skill]} L${L} ${ST_NAMES[station]}★${star}：${v[0][0]} ${v[0][1].toFixed(2)} ~ ${v[v.length - 1][0]} ${v[v.length - 1][1].toFixed(2)}`]);
        if (rg > 0.25) bad.push(`${SKILL_NAMES[skill]} L${L} ${ST_NAMES[station]}★${star} 差 ${rg.toFixed(2)}（${v.map((x) => x[0] + ' ' + x[1].toFixed(2)).join('，')}）`);
      }
      worst.sort((a, b) => b[0] - a[0]);
      add(bad.length === 0, `真实菜性子矩阵：同台同星各种性子的质量点差 ≤ 0.25（每格 ${dm[0].n} 局）`, bad.length ? bad.join('；') : '全部满足；差最大的三格：' + worst.slice(0, 3).map((w) => `${w[1]}（差 ${w[0].toFixed(2)}）`).join('；'));
    }
  }
  // 9 负面不成常态：普通玩家在推荐等级的出品带负面率（四台合并）
  {
    const rc = by('recLevel');
    if (rc.length) {
      const parts = []; let ok = true;
      for (let star = 1; star <= 7; star++) {
        const m = merge(rc.filter((c) => c.skill === 'average' && c.star === star));
        const r = 100 * m.negAny / m.n;
        if (star <= 5 && r > 30) ok = false;
        parts.push(`★${star}@L${REC_LEVEL[star - 1]} ${r.toFixed(0)}%`);
      }
      const g = merge(rc.filter((c) => c.skill === 'good'));
      add(ok, '普通玩家在推荐等级的出品带负面 ≤ 30%（★1–★5）', parts.join(' / ') + `；熟练玩家各星合计 ${pct(g.negAny, g.n, 0)}%`);
    }
  }
  // 10 烤炉：预判翻面不吃亏
  {
    const an = by('antic');
    if (an.length) {
      const parts = []; let ok = true;
      for (const c of an) {
        const e = 100 * c.flipEarly / Math.max(1, c.flipN), g = 100 * c.flipGood / Math.max(1, c.flipN), b = c.bot || {};
        const sd = b.flip === 'anticipate' ? `预判 σ=${b.flipSd * 50}ms` : '反应型';
        if (c.skill !== 'good') continue;
        if (b.flip === 'anticipate' && b.flipSd <= 2 && e > 10) ok = false;
        parts.push(`${sd}：正好 ${g.toFixed(0)}% / 翻早 ${e.toFixed(0)}% / 超凡+ ${exPlus(c).toFixed(0)}%`);
      }
      add(ok, '烤炉预判翻面（熟练）：计时误差 σ ≤ 100 ms 时判翻早 ≤ 10%（v1 规则下是 28%/42%）', parts.join('；'));
    }
  }
  // 11 机械节奏：轻点悬停的真人与人类机器人不被误判
  {
    const tp = by('tap');
    if (tp.length) {
      const real = tp.filter((c) => (c.bot.periodSd ?? 10) >= 10), extreme = tp.filter((c) => (c.bot.periodSd ?? 10) < 10);
      const mr = merge(real), me = merge(extreme);
      const hb = merge(cells.filter((c) => ['expert', 'good', 'average', 'novice'].includes(c.skill)));
      const fp = 100 * mr.mech / mr.n;
      // 开环按键流（审查 expH 的模型：不看食材、一直按固定节奏点）
      const so = openLoopStreams(cells.length ? cells[0] : null);
      const ok = fp <= 0.5 && hb.mech / hb.n <= 0.005 && so.real <= 0.5;
      add(ok, '机械节奏误判：10 Hz 轻点悬停（周期抖动 ≥ 10 ms）≤ 0.5%，人类机器人 ≤ 0.5%',
        `游戏里轻点悬停 ${fp.toFixed(2)}%（${mr.mech}/${mr.n}），周期抖动 5 ms 的极端稳手 ${pct(me.mech, me.n, 2)}%；开环 10 Hz 按键流（周期抖动 ≥ 10 ms）最高 ${so.real.toFixed(2)}%，周期抖动 5 ms 最高 ${so.extreme.toFixed(2)}%（参考）；人类机器人 ${pct(hb.mech, hb.n, 2)}%；每 tick 交替宏仍 100% 命中`);
    }
  }
  return checks;
}

export function renderMarkdown(cells, N, meta, ex = []) {
  const get = (skill, station, star, level) => cells.find((c) => c.skill === skill && c.station === station && c.star === star && c.level === level);
  const checks = analyze(cells, N, ex);
  const L = [];
  L.push('# 掌勺小游戏 · 平衡仿真报告');
  L.push('');
  L.push(`> 生成：\`node bots.mjs balance ${N}\`（${meta.when}，用时 ${meta.seconds}s）。内核 simVersion ${Z.SIM_VERSION}，参数 = sim.js DEFAULT_CONFIG（paramsHash 见 balance.json）。`);
  L.push(`> 口径：4 台 × ★1–★7 × 厨师 L1/L5/L10，每格 **${N} 局**；手熟 0、单份；各台默认性子（厨锅混、炸锅浮、烤炉混 / 翻 1 面、备餐台混 / 5 拍）。同一台的各格共用同一批种子（公共随机数）。`);
  L.push('> 品质分布列依次为 **低 / 中 / 高 / 超凡 / 闪耀 / 翻车（%）**；“翻车”= 糊锅（出家常菜）。闪耀率**未计**灵感冷却和日上限；金瓶由 `privateGold` 代替服务端私下抽签。均时 = 正式段平均秒数（含备餐台摆料段，不含 1 秒倒计时）。');
  L.push('> 机器人：完美 = 读种子的神谕（理论上界）；高手/熟练/普通/新手 = 反应 0.15/0.2/0.25/0.3 秒的 PD 控制器（带感知误差与最短按键间隔）；乱按 = 随机开关。主表的机器人不分心、烤炉不预判；分心、瞟油温、预判翻面、轻点悬停另见「补充实验」。机器人不是真人，数字用来定方向（spec §22-1）。');
  L.push('');
  L.push('## 检查结论');
  L.push('');
  L.push('| 结论 | 检查 | 细节 |');
  L.push('|---|---|---|');
  for (const c of checks) L.push(`| ${c.ok ? '通过' : '**不通过**'} | ${c.name} | ${c.detail.replace(/\|/g, '/')} |`);
  L.push('');

  // 四台合并（对照 spec §16）
  L.push('## 四台合并（对照 spec §16）');
  L.push('');
  L.push('每格 = 4 台合并（' + (4 * N) + ' 局）。格式：低/中/高/超凡/闪耀/翻车 · 均时 · Perfect%');
  for (const skill of MAIN_SKILLS) {
    L.push('');
    L.push(`### ${SKILL_NAMES[skill]}`);
    L.push('');
    L.push('| ★ | 厨师 1 级 | 厨师 5 级 | 厨师 10 级 |');
    L.push('|---|---|---|---|');
    for (let star = 1; star <= 7; star++) {
      const row = [String(star)];
      for (const level of LEVELS) {
        const m = merge([0, 1, 2, 3].map((st) => get(skill, st, star, level)));
        row.push(`${fmtDist(m)} · ${(m.ticks / m.n / 20).toFixed(1)}s · P${pct(m.perfect, m.n, m.perfect * 100 / m.n < 1 ? 1 : 0)}`);
      }
      L.push('| ' + row.join(' | ') + ' |');
    }
  }
  L.push('');

  // 分台明细
  L.push('## 分台明细');
  L.push('');
  L.push('格式：低/中/高/超凡/闪耀/翻车 · 均时 · Perfect% · 到点%');
  for (const skill of MAIN_SKILLS) {
    L.push('');
    L.push(`### ${SKILL_NAMES[skill]}`);
    for (let station = 0; station < 4; station++) {
      L.push('');
      L.push(`**${ST_NAMES[station]}**（性子：${ST_MOTION[station]}）`);
      L.push('');
      L.push('| ★ | 厨师 1 级 | 厨师 5 级 | 厨师 10 级 |');
      L.push('|---|---|---|---|');
      for (let star = 1; star <= 7; star++) {
        const row = [String(star)];
        for (const level of LEVELS) {
          const c = get(skill, station, star, level);
          row.push(`${fmtDist(c)} · ${(c.ticks / c.n / 20).toFixed(1)}s · P${pct(c.perfect, c.n, 0)} · 到点${pct(c.timeout, c.n, 0)}`);
        }
        L.push('| ' + row.join(' | ') + ' |');
      }
    }
  }
  L.push('');

  // 其它指标
  L.push('## 其它指标');
  L.push('');
  L.push('| 技能 | 局数 | 中位时长 | 抓瓶率（有瓶局） | 金瓶出现率 | 金瓶抓到率 | 低品质带负面 | 中品质带负面 | 高品质带负面 | 机械节奏误判率 |');
  L.push('|---|---|---|---|---|---|---|---|---|---|');
  for (const skill of MAIN_SKILLS) {
    const cs = cells.filter((c) => c.skill === skill);
    const m = merge(cs);
    const d50 = cs.map((c) => c.d50).sort((a, b) => a - b)[cs.length >> 1];
    L.push(`| ${SKILL_NAMES[skill]} | ${m.n} | ${(d50 / 20).toFixed(1)}s（各格中位数的中位） | ${pct(m.bottleCaught, m.bottleSeen)}% | ${pct(m.goldSeen, m.n)}% | ${m.goldSeen ? pct(m.goldCaught, m.goldSeen, 0) + '%' : '-'} | ${m.lowN ? pct(m.negLow, m.lowN, 0) : '-'}% | ${m.midN ? pct(m.negMid, m.midN, 0) : '-'}% | ${m.highN ? pct(m.negHigh, m.highN, 0) : '-'}% | ${pct(m.mech, m.n, 2)}% |`);
  }
  L.push('');
  if (meta.extra) { L.push(meta.extra); L.push(''); }
  return L.join('\n');
}

// ---------------------------------------------------------------------------
// 补充实验：挂机与宏、性子、手熟、批量、翻面次数与拍数、分心、瞟油温、预判翻面、轻点悬停、真实菜性子、推荐等级
// ---------------------------------------------------------------------------
export function extraTasks(N, dishes) {
  const T = [];
  for (const skill of ['release', 'hold', 'toggle6', 'toggle1', 'random'])
    for (let station = 0; station < 4; station++)
      for (const [star, level] of [[1, 1], [1, 10], [4, 5], [7, 10]]) T.push({ tag: 'afk', station, star, level, skill, n: N });
  for (let motion = 0; motion < 5; motion++) T.push({ tag: 'macroMotion', station: 0, star: 1, level: 10, skill: 'toggle1', motion, n: N });
  for (const skill of ['average', 'good']) for (let motion = 0; motion < 5; motion++)
    T.push({ tag: 'motion', station: 0, star: 4, level: 5, skill, motion, n: N });
  for (const skill of ['average', 'good']) for (const mastery of [0, 1, 3, 5])
    T.push({ tag: 'mastery', station: 0, star: 3, level: 5, skill, extra: { mastery }, n: N });
  for (const skill of ['average', 'good']) for (const batch of [1, 2, 4, 6])
    T.push({ tag: 'batch', station: 0, star: 3, level: 10, skill, extra: { batch, mastery: 2 }, n: N });
  for (const skill of ['average', 'good']) {
    for (const flips of [1, 2, 3]) T.push({ tag: 'flips', station: 2, star: 3, level: 5, skill, extra: { flips }, n: N });
    for (const beats of [3, 5, 8]) T.push({ tag: 'beats', station: 3, star: 3, level: 5, skill, extra: { beats }, n: N });
    T.push({ tag: 'beats', station: 3, star: 3, level: 5, skill, extra: { drink: true }, n: N });
  }
  // 四台差异（不分心 / 分心 3 tick）
  const N4 = Math.max(N, 1500);
  for (const att of [0, 3]) for (const [skill, level, star] of [['average', 5, 3], ['good', 10, 5], ['good', 5, 4]]) for (let station = 0; station < 4; station++)
    T.push({ tag: 'four', station, star, level, skill, n: N4, bot: att ? { attention: att } : null });
  // 炸锅：瞟油温（每隔 G 秒看一眼，看的 0.3 秒里看不到食材）
  for (const skill of ['good', 'average']) for (const glance of [0, 1.5, 2, 3])
    T.push({ tag: 'glance', station: 1, star: 4, level: 5, skill, n: Math.max(N, 600), bot: glance ? { glance } : null });
  // 烤炉：预判翻面（σ = 50/100/150 ms）对照反应型
  for (const skill of ['good', 'average']) {
    T.push({ tag: 'antic', station: 2, star: 4, level: 5, skill, n: Math.max(N, 600), bot: null });
    for (const flipSd of [1, 2, 3]) T.push({ tag: 'antic', station: 2, star: 4, level: 5, skill, n: Math.max(N, 600), bot: { flip: 'anticipate', flipSd } });
  }
  // 机械节奏回归：10 Hz 轻点悬停（安静的 ★1 沉 / 混；L1 / L5 / L10）
  for (const [motion, star] of [[3, 1], [0, 1], [2, 1], [3, 2]]) for (const level of [1, 5, 10]) {
    T.push({ tag: 'tap', station: 0, star, level, skill: 'tap', motion, n: Math.max(N, 600), bot: { period: 100, periodSd: 10, durSd: 10 } });
    T.push({ tag: 'tap', station: 0, star, level, skill: 'tap', motion, n: Math.max(N, 600), bot: { mode: 'rate', period: 100, periodSd: 10, dur: 40, durSd: 10 } });
    T.push({ tag: 'tap', station: 0, star, level, skill: 'tap', motion, n: Math.max(N, 600), bot: { mode: 'rate', period: 100, periodSd: 5, dur: 40, durSd: 8 } });
  }
  // 真实菜的性子矩阵（dish-play.json：每台每星真实用到的 性子 × 翻面 × 冷饮）
  if (dishes) {
    const combos = new Map();
    for (const d of dishes) {
      const key = `${d.station}|${d.star}|${d.motion}|${d.flips || 0}|${d.drink ? 1 : 0}`;
      if (!combos.has(key)) combos.set(key, { station: d.station, star: d.star, motion: d.motion, extra: Object.assign({}, d.flips ? { flips: d.flips } : {}, d.drink ? { drink: true } : {}), dishes: 0 });
      combos.get(key).dishes++;
    }
    for (const cb of combos.values()) for (const [skill, level] of [['average', 5], ['good', 10]])
      T.push({ tag: 'dishMotion', station: cb.station, star: cb.star, level, skill, motion: cb.motion, extra: cb.extra, n: Math.max(N, 1200), dishes: cb.dishes });
  }
  // 推荐等级（★1→1 … ★7→9）
  for (const skill of ['average', 'good']) for (let star = 1; star <= 7; star++) for (let station = 0; station < 4; station++)
    T.push({ tag: 'recLevel', station, star, level: REC_LEVEL[star - 1], skill, n: N });
  return T;
}

export function renderRadiant(extras) {
  const rc = extras.filter((c) => c.tag === 'radiant');
  if (!rc.length) return '';
  const R = [];
  R.push(`### 闪耀率大样本（每格 ${rc[0].n} 局，四台合并后每行 ${4 * rc[0].n} 局/星）`);
  R.push('');
  R.push('spec §1 目标：普通玩家满级约 0.3–1%/局，高手满级约 1–3%/局（未计灵感冷却与日上限）。');
  R.push('');
  R.push('| 技能 | 等级 | 合计 | ★1 | ★2 | ★3 | ★4 | ★5 | ★6 | ★7 | 超凡+（合计） | 金瓶局抓到金瓶 |');
  R.push('|---|---|---|---|---|---|---|---|---|---|---|---|');
  for (const skill of ['expert', 'good', 'average']) for (const level of LEVELS) {
    const cs = rc.filter((c) => c.skill === skill && c.level === level);
    const m = merge(cs);
    const byStar = [1, 2, 3, 4, 5, 6, 7].map((star) => { const mm = merge(cs.filter((c) => c.star === star)); return (100 * mm.q[5] / mm.n).toFixed(2); });
    R.push(`| ${SKILL_NAMES[skill]} | L${level} | **${(100 * m.q[5] / m.n).toFixed(2)}%** | ${byStar.join(' | ')} | ${(100 * (m.q[4] + m.q[5]) / m.n).toFixed(1)}% | ${pct(m.goldCaught, m.goldSeen, 0)}% |`);
  }
  R.push('');
  return R.join('\n');
}

export function renderExtras(cells) {
  const L = [];
  const by = (tag) => cells.filter((c) => c.tag === tag);
  if (!by('afk').length) return '';
  const line = (c) => `${fmtDist(c)} · ${(c.ticks / c.n / 20).toFixed(1)}s · P${pct(c.perfect, c.n, 0)} · 到点${pct(c.timeout, c.n, 0)}`;
  L.push('## 补充实验');
  L.push('');
  L.push('### 挂机、宏与乱按（每格 ' + by('afk')[0].n + ' 局）');
  L.push('');
  L.push('格式：低/中/高/超凡/闪耀/翻车 · 均时 · 机械节奏命中%。“全松/全按”必须 100% 翻车（spec §1-5）。');
  L.push('');
  L.push('| 输入 | 台 | L1★1 | L10★1 | L5★4 | L10★7 |');
  L.push('|---|---|---|---|---|---|');
  for (const skill of ['release', 'hold', 'toggle6', 'toggle1', 'random']) for (let station = 0; station < 4; station++) {
    const row = [SKILL_NAMES[skill], ST_NAMES[station]];
    for (const [star, level] of [[1, 1], [1, 10], [4, 5], [7, 10]]) {
      const c = by('afk').find((x) => x.skill === skill && x.station === station && x.star === star && x.level === level);
      row.push(`${fmtDist(c)} · ${(c.ticks / c.n / 20).toFixed(1)}s · 机械${pct(c.mech, c.n, 0)}`);
    }
    L.push('| ' + row.join(' | ') + ' |');
  }
  L.push('');
  L.push('**每 tick 交替宏 × 性子**（厨锅 L10★1；spec §22-5 的已知风险）。“封顶后”= 套上 §18.7 机械节奏封顶（中）之后的分布。');
  L.push('');
  L.push('| 性子 | 原始 低/中/高/超凡/闪耀/翻车 | 机械节奏命中 | 封顶后 |');
  L.push('|---|---|---|---|');
  for (const c of by('macroMotion')) {
    const capped = { n: c.n, q: c.qCapped };
    L.push(`| ${MOTION_NAMES[c.motion]} | ${fmtDist(c)} | ${pct(c.mech, c.n, 0)}% | ${fmtDist(capped)} |`);
  }
  L.push('');
  L.push('### 性子差异（厨锅 L5★4）');
  L.push('');
  L.push('| 技能 | ' + MOTION_NAMES.join(' | ') + ' |');
  L.push('|---|---|---|---|---|---|');
  for (const skill of ['average', 'good']) {
    const cs = by('motion').filter((c) => c.skill === skill).sort((a, b) => a.motion - b.motion);
    L.push(`| ${SKILL_NAMES[skill]} | ` + cs.map((c) => `超凡+ ${pct(c.q[4] + c.q[5], c.n, 0)}%（${line(c)}）`).join(' | ') + ' |');
  }
  L.push('');
  const dm = by('dishMotion');
  if (dm.length) {
    L.push(`### 真实菜的性子矩阵（每格 ${dm[0].n} 局；dish-play.tsv 里每台每星真实用到的组合，括号里是用这个组合的菜数）`);
    L.push('');
    L.push('格式：质量点（翻车 0 / 低 1 / 中 2 / 高 3 / 超凡+ 4）· 超凡+%。同台同星最大差 ≤ 0.25 质量点算通过（约一颗星的落差）。');
    L.push('');
    L.push('| 台 | ★ | 组合（菜数） | 普通 L5 | 熟练 L10 |');
    L.push('|---|---|---|---|---|');
    const keys = [...new Set(dm.map((c) => `${c.station}|${c.star}|${comboName(c)}`))];
    keys.sort((a, b) => { const [sa, xa] = a.split('|').map(Number), [sb, xb] = b.split('|').map(Number); return sa - sb || xa - xb; });
    for (const k of keys) {
      const [st, star, name] = k.split('|');
      const f = (skill, level) => { const c = dm.find((x) => x.skill === skill && x.level === level && `${x.station}|${x.star}|${comboName(x)}` === k); return c ? `${qualityPoints(c).toFixed(2)} · ${exPlus(c).toFixed(0)}%` : '-'; };
      const nd = dm.find((x) => `${x.station}|${x.star}|${comboName(x)}` === k).dishes;
      L.push(`| ${ST_NAMES[+st]} | ★${star} | ${name}（${nd}） | ${f('average', 5)} | ${f('good', 10)} |`);
    }
    L.push('');
  }
  const four = by('four');
  if (four.length) {
    L.push(`### 四台差异：不分心 vs 按动作键后分心 3 tick（每格 ${four[0].n} 局，超凡+%）`);
    L.push('');
    L.push('| 设置 | ' + ST_NAMES.join(' | ') + ' | 极差 |');
    L.push('|---|---|---|---|---|---|');
    for (const att of [0, 3]) for (const [skill, level, star] of [['average', 5, 3], ['good', 10, 5], ['good', 5, 4]]) {
      const ex4 = [0, 1, 2, 3].map((st) => exPlus(four.find((c) => c.skill === skill && c.level === level && c.star === star && c.station === st && (c.bot ? c.bot.attention || 0 : 0) === att)));
      L.push(`| ${att ? '分心 3 tick · ' : ''}${SKILL_NAMES[skill]} L${level}★${star} | ${ex4.map((x) => x.toFixed(0)).join(' | ')} | ${(Math.max(...ex4) - Math.min(...ex4)).toFixed(0)} |`);
    }
    L.push('');
  }
  const gl = by('glance');
  if (gl.length) {
    L.push('### 炸锅：瞟油温（★4 L5；每隔约 G 秒看一眼油温，看的 0.3 秒里看不到食材、只在看的时候压火）');
    L.push('');
    L.push('| 技能 | 瞟的间隔 | 爆油局% | 过热时间% | 冷油时间% | Perfect% | 超凡+% | 低/中/高/超凡/闪耀/翻车 |');
    L.push('|---|---|---|---|---|---|---|---|');
    for (const c of gl) L.push(`| ${SKILL_NAMES[c.skill]} | ${c.bot ? c.bot.glance + ' 秒' : '随时看'} | ${pct(c.burstGames, c.n)} | ${pct(c.hot, c.S)} | ${pct(c.cold, c.S)} | ${pct(c.perfect, c.n)} | ${exPlus(c).toFixed(0)} | ${fmtDist(c)} |`);
    L.push('');
  }
  const an = by('antic');
  if (an.length) {
    L.push('### 烤炉：预判翻面（★4 L5；盯熟度条三角，瞄准提示出现那一 tick 按，带 σ 的计时误差）');
    L.push('');
    L.push('| 技能 | 翻面方式 | 正好% | 翻早% | 稍晚% | 焦面% | 自动翻% | Perfect% | 超凡+% |');
    L.push('|---|---|---|---|---|---|---|---|---|');
    for (const c of an) {
      const f = (x) => pct(x, c.flipN, 0);
      L.push(`| ${SKILL_NAMES[c.skill]} | ${c.bot ? `预判 σ=${c.bot.flipSd * 50} ms` : '看提示反应'} | ${f(c.flipGood)} | ${f(c.flipEarly)} | ${f(c.flipLate)} | ${f(c.flipBurnt)} | ${f(c.flipAuto)} | ${pct(c.perfect, c.n, 0)} | ${exPlus(c).toFixed(0)} |`);
    }
    L.push('');
  }
  const tp = by('tap');
  if (tp.length) {
    L.push('### 机械节奏回归：10 Hz 轻点悬停的真人（厨锅；周期约 100 ms）');
    L.push('');
    L.push('「占空比」= 周期固定、按得长短跟着食材走；「频率」= 审查用的模型：每次按约 40 ms、最快约 10.5 Hz、想往下就点得稀。');
    L.push('');
    L.push('| 性子 · ★ | 等级 | 占空比 ±10 ms：误判% / 超凡+% | 频率 ±10 ms：误判% / 超凡+% | 频率 ±5 ms（极端稳，参考）：误判% |');
    L.push('|---|---|---|---|---|');
    for (const [motion, star] of [[3, 1], [0, 1], [2, 1], [3, 2]]) for (const level of [1, 5, 10]) {
      const f = (mode, sd) => tp.find((c) => c.motion === motion && c.star === star && c.level === level && (c.bot.mode || 'duty') === mode && c.bot.periodSd === sd);
      const a = f('duty', 10), b = f('rate', 10), d = f('rate', 5);
      L.push(`| ${MOTION_NAMES[motion]} ★${star} | L${level} | ${pct(a.mech, a.n, 2)} / ${exPlus(a).toFixed(0)} | ${pct(b.mech, b.n, 2)} / ${exPlus(b).toFixed(0)} | ${pct(d.mech, d.n, 2)} |`);
    }
    L.push('');
  }
  const rc = by('recLevel');
  if (rc.length) {
    L.push('### 推荐等级（★1→1、★2→2、★3→3、★4→5、★5→6、★6→8、★7→9；四台合并）');
    L.push('');
    L.push('| 技能 | ★ | 低/中/高/超凡/闪耀/翻车 | 带负面% | 中品质带负面% | 高品质带负面% | 到点% |');
    L.push('|---|---|---|---|---|---|---|');
    for (const skill of ['average', 'good']) for (let star = 1; star <= 7; star++) {
      const m = merge(rc.filter((c) => c.skill === skill && c.star === star));
      L.push(`| ${SKILL_NAMES[skill]} | ★${star}@L${REC_LEVEL[star - 1]} | ${fmtDist(m)} | ${pct(m.negAny, m.n, 0)} | ${m.midN ? pct(m.negMid, m.midN, 0) : '-'} | ${m.highN ? pct(m.negHigh, m.highN, 0) : '-'} | ${pct(m.timeout, m.n, 0)} |`);
    }
    L.push('');
  }
  L.push('### 手熟（厨锅 L5★3）');
  L.push('');
  L.push('| 技能 | 手熟 0 | 手熟 1 | 手熟 3 | 手熟 5 |');
  L.push('|---|---|---|---|---|');
  for (const skill of ['average', 'good']) {
    const cs = by('mastery').filter((c) => c.skill === skill);
    L.push(`| ${SKILL_NAMES[skill]} | ` + cs.map((c) => `中位 ${(c.d50 / 20).toFixed(1)}s · 超凡+ ${pct(c.q[4] + c.q[5], c.n, 0)}%`).join(' | ') + ' |');
  }
  L.push('');
  const mr = by('masteryRad');
  if (mr.length) {
    L.push(`### 手熟与闪耀（L10，四台 × ★1–★7，每格 ${mr[0].n} 局）`);
    L.push('');
    L.push('每小时按「一局用时 + 2.5 秒（倒计时与连做间隔）」折算，不计灵感冷却与日上限。');
    L.push('');
    L.push('| 技能 | 手熟 | 单局闪耀 | 均时 | 每小时闪耀 | 金瓶局抓到金瓶 |');
    L.push('|---|---|---|---|---|---|');
    for (const skill of ['good', 'expert']) for (const mastery of [0, 5]) {
      const m = merge(mr.filter((c) => c.skill === skill && c.extra.mastery === mastery));
      const r = m.q[5] / m.n, sec = m.ticks / m.n / 20;
      L.push(`| ${SKILL_NAMES[skill]} | ${mastery} | ${(100 * r).toFixed(2)}% | ${sec.toFixed(1)}s | ${(r * 3600 / (sec + 2.5)).toFixed(1)} | ${pct(m.goldCaught, m.goldSeen, 0)}% |`);
    }
    L.push('');
  }
  L.push('### 批量（厨锅 L10★3，手熟 2；上限「高」，金瓶率 0）');
  L.push('');
  L.push('| 技能 | 1 份 | 2 份 | 4 份 | 6 份 |');
  L.push('|---|---|---|---|---|');
  for (const skill of ['average', 'good']) {
    const cs = by('batch').filter((c) => c.skill === skill);
    L.push(`| ${SKILL_NAMES[skill]} | ` + cs.map((c) => `${line(c)}`).join(' | ') + ' |');
  }
  L.push('');
  L.push('### 烤炉翻面次数 / 备餐台拍数（L5★3）');
  L.push('');
  L.push('| 技能 | 烤炉翻 1 | 翻 2 | 翻 3 | 备餐台 3 拍 | 5 拍 | 8 拍 | 冷饮（6 拍等距） |');
  L.push('|---|---|---|---|---|---|---|---|');
  for (const skill of ['average', 'good']) {
    const f = by('flips').filter((c) => c.skill === skill), b = by('beats').filter((c) => c.skill === skill);
    L.push(`| ${SKILL_NAMES[skill]} | ` + [...f, ...b].map((c) => `超凡+ ${pct(c.q[4] + c.q[5], c.n, 0)}% · P${pct(c.perfect, c.n, 0)} · ${(c.ticks / c.n / 20).toFixed(1)}s`).join(' | ') + ' |');
  }
  L.push('');
  return L.join('\n');
}

// ---------------------------------------------------------------------------
// 并行跑格子（worker_threads，内置模块）
// ---------------------------------------------------------------------------
export async function runParallel(tasks, workers) {
  const { Worker } = await import('node:worker_threads');
  const results = new Array(tasks.length);
  let nextIdx = 0, done = 0;
  // 重任务（完美机器人、大样本）先发
  const order = tasks.map((t, i) => i).sort((a, b) => ((tasks[b].skill === 'perfect') - (tasks[a].skill === 'perfect')) || (tasks[b].n - tasks[a].n));
  const pool = [];
  if (!tasks.length) return results;
  await new Promise((resolve, reject) => {
    const feed = (w) => {
      if (nextIdx >= order.length) return;
      const i = order[nextIdx++];
      w.postMessage({ i, task: tasks[i] });
    };
    for (let k = 0; k < Math.min(workers, tasks.length); k++) {
      const w = new Worker(new URL(import.meta.url), { workerData: { role: 'worker' } });
      w.on('message', ({ i, res }) => {
        results[i] = res; done++;
        if (done % 100 === 0 || done === tasks.length) process.stderr.write(`  ${done}/${tasks.length}\n`);
        if (done === tasks.length) resolve();
        else feed(w);
      });
      w.on('error', reject);
      pool.push(w);
      feed(w);
    }
  });
  await Promise.all(pool.map((w) => w.terminate()));
  return results;
}

async function main() {
  const args = process.argv.slice(2);
  const mode = args[0] || 'balance';
  const opt = Object.fromEntries(args.filter((a) => a.startsWith('--')).map((a) => { const [k, v] = a.slice(2).split('='); return [k, v ?? '1']; }));
  const pos = args.filter((a) => !a.startsWith('--'));
  if (mode === 'cell') {
    const [, st, star, level, skill, n] = pos;
    const c = runCell({ station: +st, star: +star, level: +level, skill, n: +(n || 300), motion: opt.motion !== undefined ? +opt.motion : undefined });
    console.log(`${SKILL_NAMES[skill]} ${ST_NAMES[c.station]} ★${c.star} L${c.level}: ${fmtDist(c)} 均时 ${(c.ticks / c.n / 20).toFixed(1)}s Perfect ${pct(c.perfect, c.n)}% 到点 ${pct(c.timeout, c.n)}% 抓瓶 ${pct(c.bottleCaught, Math.max(1, c.bottleSeen))}% 机械 ${pct(c.mech, c.n)}% 质量点 ${qualityPoints(c).toFixed(2)}`);
    return;
  }
  if (mode === 'render') {
    const fs = await import('node:fs');
    const j = JSON.parse(fs.readFileSync(pos[1], 'utf8'));
    const extras = j.extras || [];
    const meta = { when: j.when, seconds: j.seconds };
    meta.extra = renderExtras(extras) + '\n' + renderRadiant(extras);
    if (opt.out) fs.writeFileSync(opt.out, renderMarkdown(j.cells, j.N, meta, extras));
    for (const c of analyze(j.cells, j.N, extras)) console.log(`${c.ok ? 'OK  ' : 'FAIL'} ${c.name}: ${c.detail}`);
    return;
  }
  if (mode === 'balance') {
    const N = +(pos[1] || 300);
    const os = await import('node:os');
    const fs = await import('node:fs');
    const workers = +(opt.workers || Math.max(1, Math.min(18, os.cpus().length - 2)));
    const skills = (opt.skills || MAIN_SKILLS.join(',')).split(',');
    const tasks = [];
    for (const skill of skills) for (let station = 0; station < 4; station++) for (let star = 1; star <= 7; star++) for (const level of LEVELS)
      tasks.push({ station, star, level, skill, n: N });
    const nMain = tasks.length;
    let dishes = null;
    try { dishes = JSON.parse(fs.readFileSync(new URL('./dish-play.json', import.meta.url), 'utf8')); } catch (e) { process.stderr.write('没有 dish-play.json，跳过真实菜性子矩阵（先 node dish-play.mjs）\n'); }
    if (!opt.radiant) tasks.push(...extraTasks(N, dishes));
    // 大样本闪耀率（闪耀是 1% 量级的稀有事件，每格 300 局的抽样误差太大）
    const RN = opt.radiant ? 0 : +(opt.radiantN ?? 3000);
    if (RN > 0) {
      for (const skill of ['expert', 'good', 'average']) for (let station = 0; station < 4; station++) for (let star = 1; star <= 7; star++) for (const level of LEVELS)
        tasks.push({ tag: 'radiant', station, star, level, skill, n: RN });
      const MN = +(opt.masteryN ?? 2000);
      for (const skill of ['good', 'expert']) for (const mastery of [0, 5]) for (let station = 0; station < 4; station++) for (let star = 1; star <= 7; star++)
        tasks.push({ tag: 'masteryRad', station, star, level: 10, skill, n: MN, extra: { mastery }, seedBase: 500000 });
    }
    const t0 = Date.now();
    process.stderr.write(`balance: ${tasks.length} 格，${workers} 线程\n`);
    const all = await runParallel(tasks, workers);
    const cells = all.slice(0, nMain), extras = all.slice(nMain);
    const seconds = ((Date.now() - t0) / 1000).toFixed(0);
    const outDir = new URL('.', import.meta.url);
    const meta = { when: new Date().toISOString().slice(0, 16).replace('T', ' ') + ' UTC', seconds };
    meta.extra = renderExtras(extras) + '\n' + renderRadiant(extras);
    const ph = Z.hex32(Z.paramsHash(Z.buildParams({ station: 0, star: 1, chefLevel: 1 })));
    const outPath = (v, def) => (v && /^[A-Za-z]:[\\/]/.test(v)) ? v : new URL(v || def, outDir);
    if (opt.json !== 'none') fs.writeFileSync(outPath(opt.json, 'balance.json'), JSON.stringify({ N, when: meta.when, seconds: meta.seconds, simVersion: Z.SIM_VERSION, paramsHashPotStar1L1: ph, cells, extras }, null, 0));
    if (opt.out !== 'none') fs.writeFileSync(outPath(opt.out, 'balance.md'), renderMarkdown(cells, N, meta, extras));
    for (const c of analyze(cells, N, extras)) console.log(`${c.ok ? 'OK  ' : 'FAIL'} ${c.name}: ${c.detail}`);
    console.log(`done in ${meta.seconds}s`);
  }
}

// worker 入口 / CLI 入口
const wt = await import('node:worker_threads');
if (!wt.isMainThread && wt.workerData && wt.workerData.role === 'worker') {
  wt.parentPort.on('message', ({ i, task }) => { wt.parentPort.postMessage({ i, res: runCell(task) }); });
} else if (typeof process !== 'undefined' && process.argv[1] && import.meta.url.endsWith(process.argv[1].replace(/\\/g, '/').split('/').pop())) {
  await main();
}
