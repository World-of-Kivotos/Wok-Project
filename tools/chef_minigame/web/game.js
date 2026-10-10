// 掌勺小游戏 · 网页原型的界面层。规则全部来自 window.ZhangshaoSim（sim/sim.js 原样内联），这里只做：
// 采样输入 → 每 50 ms 调一次内核 stepInPlace → 按 requestAnimationFrame 画面板；结束时用内核 replayTrace 重算校验。
// 画面用「预测渲染」：每帧拿当前状态 + 此刻的按键状态预跑下一 tick（只用于画面），在 当前 → 下一 tick 之间插值，
// 出条变红、箭头都按同一帧画出来的位置判断，所以画面不再比判定晚一 tick（spec §17.4）。
(() => {
'use strict';
const SIM = window.ZhangshaoSim;
const DATA = JSON.parse(document.getElementById('zs-data').textContent);
const { STATION, MOTION, END, QUALITY, BOTTLE, BOTTLE_KIND, NEG, NONE } = SIM;
const $ = (id) => document.getElementById(id);

const ST_KEYS = ['pot', 'fryer', 'oven', 'prep'];
const ST_NAMES = ['厨锅', '炸锅', '烤炉', '备餐台'];
const ST_ACT = ['', '压火', '翻面', '摆料'];
const ST_RULE = [
  '单键。稳住食材火候就旺，越做越快；一断火，火候掉得很快。',
  '油温一直上升，按动作键压火。守住金黄区；过热后升温变慢，到 1000 会爆油。',
  '熟度到三角处弹出「翻面！」，按动作键翻面；三角快到时会先闪，提前一眨眼按也算正好。',
  '熟度到三角处进入摆料段，一切冻结，跟着节拍按动作键；摆完有「接住！」缓一下。',
];
const MOTION_KEYS = ['mix', 'dart', 'steady', 'sink', 'float'];
const MOTION_NAMES = ['混', '窜', '稳', '沉', '浮'];
const MOTION_DESC = ['随机大移动', '时不时急窜', '走走停停', '总往下沉', '总往上浮'];
const MASTERY_NAMES = ['生疏', '入门', '熟练', '精通', '拿手', '招牌'];
const Q_NAME = { '-1': '家常菜', 0: '低', 1: '中', 2: '高', 3: '超凡', 4: '闪耀' };
const Q_CLASS = { '-1': 'burnt', 0: 'low', 1: 'mid', 2: 'high', 3: 'ex', 4: 'rad' };
const Q_MC = { '-1': '#aaaaaa', 0: '#aaaaaa', 1: '#55ff55', 2: '#55ffff', 3: '#ff55ff', 4: '#ffaa00' };
const Q_CHIP = { '-1': 'gray', 0: 'gray', 1: 'green', 2: 'aqua', 3: 'purple', 4: 'gold' };
const EFFECT_TIME = { 0: '3 分钟', 1: '8 分钟', 2: '15 分钟', 3: '30 分钟', 4: '单命永久' };
const END_NAME = { 0: '进行中', 1: '出锅', 2: '糊锅', 3: '到点', 4: '撂勺' };
const REC_LEVEL = [1, 2, 3, 5, 6, 8, 9];          // spec §15.3 推荐等级
const COMBAT_LV = 5, COMBAT_HI_LV = 7;            // spec §15.3 战斗菜门槛（★6 以上 7 级）
const TICK_MS = SIM.TICK_MS, COUNTDOWN = 20;      // spec §17.3：20 tick 倒计时
const STALL_MS = 5000;                            // spec §18.6-4：5 秒收不到输入 = 撂勺（页面切到后台太久也一样）
const FONT = '"Noto Sans SC", "Microsoft YaHei", "PingFang SC", "Hiragino Sans GB", sans-serif';
const sec = (ticks) => (ticks / 20).toFixed(1);   // tick → 秒（一位小数）
const pc = (pm) => `${(pm / 10).toFixed(1)}%`;     // 千分比 → 百分比（一位小数）

// spec §13.6 性子分配（按台子和菜名关键字；烤串按审查处理不再用「窜」）
const MOTION_RULES = [
  [[MOTION.STEADY, ['汤', '羹', '炖', '煲', '粥', '火锅']], [MOTION.SINK, ['水饺', '馄饨', '饺子', '汤圆']], [MOTION.DART, ['虾', '蟹', '鱿鱼', '鱼', '贝']], [MOTION.FLOAT, ['甜品', '糖水', '布丁', '果冻', '酱']]],
  [[MOTION.DART, ['虾', '鱿鱼', '蟹']]],
  [[MOTION.STEADY, ['派', '蛋糕', '面包', '挞', '披萨']], [MOTION.SINK, ['烤肉', '整只', '排', '火腿']]],
  [[MOTION.STEADY, ['沙拉', '冷盘', '色拉']], [MOTION.DART, ['寿司', '饭团', '海鲜']]],
];
const MOTION_OTHER = [MOTION.MIX, MOTION.FLOAT, MOTION.MIX, MOTION.MIX];
const MOTION_OVERRIDE = { 'farmersdelight:roast_chicken_block': [MOTION.SINK, '整只烤肉'] };
function motionByName(st, d) {
  if (MOTION_OVERRIDE[d.id]) return { m: MOTION_OVERRIDE[d.id][0], why: MOTION_OVERRIDE[d.id][1] };
  if (st === STATION.PREP && d.skin === 'drink') return { m: MOTION.FLOAT, why: '冷饮' };
  for (const [m, words] of MOTION_RULES[st]) {
    const w = words.find((x) => d.name.includes(x));
    if (w) return { m, why: `菜名含「${w}」` };
  }
  return { m: MOTION_OTHER[st], why: st === STATION.FRYER ? '炸锅默认' : '其余菜默认' };
}
function combatNeed(d) { return d.cat === '战斗' ? (d.star >= 6 ? COMBAT_HI_LV : COMBAT_LV) : 0; }

// ---------------------------------------------------------------- 设置与对局状态
const ui = { station: 0, slot: 1, level: 5, motion: 'auto', mastery: 0, seedFixed: false, ghost: false };
let game = null;           // 当前一局
let acc = 0, lastNow = 0, frozen = false, S = 3;
const input = { keys: new Set(), pointers: new Set(), mainLatch: false, actLatch: false };
const reduceMQ = window.matchMedia ? window.matchMedia('(prefers-reduced-motion: reduce)') : { matches: false };
const reduced = () => reduceMQ.matches;
const coarseMQ = window.matchMedia ? window.matchMedia('(pointer: coarse)') : { matches: false };

const cv = $('zs-canvas'), ctx = cv.getContext('2d');
const stageEl = cv.parentElement;

const curStation = () => DATA.stations[ui.station];
const curDish = () => curStation().dishes[ui.slot];
function resolvedMotion() {
  if (ui.motion !== 'auto') return +ui.motion;
  return motionByName(ui.station, curDish()).m;
}
function randomU32() {
  try { const a = new Uint32Array(1); crypto.getRandomValues(a); return a[0]; } catch (e) { return (Math.random() * 4294967296) >>> 0; }
}
function randomSeed() { return randomU32() >>> 1; }
function parseSeed(v) {
  const t = String(v).trim();
  if (/^-?\d+$/.test(t)) return Number(t) | 0;
  let h = 0x811C9DC5 | 0;
  for (let i = 0; i < t.length; i++) { h ^= t.charCodeAt(i); h = Math.imul(h, 0x01000193); }
  return h >>> 1;
}

function newRound(seedArg, autostart, extra) {
  const d = curDish(), st = ui.station;
  const opts = { station: st, star: d.slotStar, chefLevel: ui.level, motion: resolvedMotion(), mastery: ui.mastery, drink: d.skin === 'drink' };
  if (st === STATION.OVEN) opts.flips = d.flips || 1;
  if (st === STATION.PREP && d.skin !== 'drink' && d.beats) opts.beats = d.beats;
  if (extra && extra.goldPm !== undefined) opts.goldPm = extra.goldPm;
  const block = SIM.buildParams(opts);
  let seed = seedArg;
  if (seed === undefined) seed = ui.seedFixed ? parseSeed($('zs-seed').value) : randomSeed();
  seed |= 0;
  $('zs-seed').value = String(seed);
  const s = SIM.createGame({ block, seed });
  // 「服务端」私下抽这只瓶是不是金瓶（spec §10、§18.9）：不从种子推出，瓶出现时才揭晓
  const p = s.p;
  let gold = 0;
  if (s.bKind !== BOTTLE_KIND.NONE && p.goldPm > 0) gold = (randomU32() % p.bottlePm) < p.goldPm ? 1 : 0;
  if (extra && extra.gold !== undefined) gold = extra.gold ? 1 : 0;
  game = {
    dish: d, opts, block, seed, s, gold, pred: null, predKey: '', inputs: [], phase: 'ready', cd: 0, endAt: 0, feed: null,
    live: SIM.result(s), res: null, replay: null, flipLog: [], beatLog: [[], []],
    perfectBrokenAt: 0, perfectWhy: '', beatIcons: beatIconsFor(d, s),
    fx: { missAt: -1e9, splash: null, pop: null, shakeUntil: 0, verdict: null, ring: null, placed: null, escAt: -1e9, goAt: -1e9,
      pressAt: -1e9, visIn: true, visMissAt: -1e9, ghostTgt: NONE, ghostAt: 0, stallMsg: '' },
  };
  acc = 0; input.mainLatch = input.actLatch = false;
  layoutCanvas(); updatePad(); renderLevelHint(); renderCard(); markDiff();
  if (autostart) startCountdown();
}
function beatIconsFor(d, s) {
  const p = s.p;
  if (p.station !== STATION.PREP) return [];
  const others = DATA.stations[3].dishes.filter((x) => x.id !== d.id);
  const out = [];
  for (let k = 0; k < p.beats; k++) {
    if (d.skin === 'drink') out.push({ spr: 'prep/ice_cube' });
    else if (k === p.beats - 1) out.push({ raw: d.raw });
    else out.push({ raw: others[k % others.length].raw });
  }
  return out;
}
function startCountdown() {
  const g = game;
  if (!g || g.phase !== 'ready') return;
  g.phase = 'countdown'; g.cd = 0; acc = 0;
  setPhaseText(); updatePad();
}

// ---------------------------------------------------------------- 输入（spec §3.2：bit0 hold，bit1 act）
const isHeld = () => input.keys.size > 0 || input.pointers.size > 0;
const byteNow = () => ((isHeld() || input.mainLatch) ? 1 : 0) | (input.actLatch ? 2 : 0);
function mainDown(src) {
  const was = isHeld();
  src();
  if (!was) { input.mainLatch = true; if (game) game.fx.pressAt = performance.now(); }
  const g = game;
  if (g && g.phase === 'ready') startCountdown();
  $('zs-hold').classList.add('on');
}
function mainUp(src) { src(); if (!isHeld()) $('zs-hold').classList.remove('on'); }
function actPress() {
  input.actLatch = true;
  const g = game;
  if (g && g.phase === 'ready') startCountdown();
}
function isFormField(t) {
  if (!t || !t.tagName) return false;
  const tag = t.tagName;
  if (tag === 'SELECT' || tag === 'TEXTAREA' || t.isContentEditable) return true;
  if (tag === 'INPUT') return !['range', 'button'].includes(t.type);
  return false;
}
// 空格落在这些控件上时交还给浏览器（按钮按下、规则展开、勾选），只在没开局时这样做
function spaceBelongsToControl(t) {
  if (!t || !t.tagName) return false;
  if (t.id === 'zs-hold' || t.id === 'zs-act') return false;
  return t.tagName === 'BUTTON' || t.tagName === 'SUMMARY' || (t.tagName === 'INPUT' && (t.type === 'checkbox' || t.type === 'radio'));
}
document.addEventListener('keydown', (e) => {
  if (e.ctrlKey || e.metaKey || e.altKey) return;
  const field = isFormField(e.target);
  const g = game;
  const playing = g && (g.phase === 'running' || g.phase === 'countdown');
  if (e.code === 'Space') {
    if (field) return;
    if (!playing && spaceBelongsToControl(e.target)) return;
    e.preventDefault();
    if (!e.repeat) mainDown(() => input.keys.add('Space'));
  } else if (e.code === 'KeyF') {
    if (field) return;
    if (!e.repeat) actPress();
  } else if (e.key === 'Enter') {
    if (field || (e.target && e.target.tagName === 'BUTTON') || (e.target && e.target.tagName === 'SUMMARY')) return;
    if (g.phase === 'ready') { e.preventDefault(); startCountdown(); }
    else if (g.phase === 'ended') { e.preventDefault(); again(); }
  } else if (e.code === 'KeyR') {
    if (field) return;
    newRound();
    cv.focus({ preventScroll: true });
  } else if (e.key === 'Escape') {
    const now = performance.now();
    if (!playing) return;
    if (now - g.fx.escAt < 1500) abandon(now); else g.fx.escAt = now;
  }
});
document.addEventListener('keyup', (e) => {
  if (e.code === 'Space') {
    if (input.keys.has('Space')) e.preventDefault();
    mainUp(() => input.keys.delete('Space'));
  }
});
window.addEventListener('blur', () => { input.keys.clear(); input.pointers.clear(); $('zs-hold').classList.remove('on'); });

function bindHoldSurface(el) {
  el.addEventListener('pointerdown', (e) => {
    if (e.button === 2) { e.preventDefault(); actPress(); return; }
    if (e.button !== 0) return;
    e.preventDefault();
    if (el === cv) cv.focus({ preventScroll: true });
    // 一局打完：点「再来一份」（就是这个按钮）或画布直接开下一局；结束后 0.4 秒内不响应，防止连按误触
    if (game && game.phase === 'ended') { if (performance.now() - game.endAt >= 400) again(); return; }
    try { el.setPointerCapture(e.pointerId); } catch (_) { /* 不支持就算了 */ }
    mainDown(() => input.pointers.add(e.pointerId));
  });
  const up = (e) => mainUp(() => input.pointers.delete(e.pointerId));
  el.addEventListener('pointerup', up);
  el.addEventListener('pointercancel', up);
  el.addEventListener('lostpointercapture', up);
  el.addEventListener('contextmenu', (e) => e.preventDefault());
}
bindHoldSurface(cv);
bindHoldSurface($('zs-hold'));
$('zs-hold').addEventListener('click', (e) => { if (e.detail === 0 && game && game.phase === 'ended') again(); });   // 键盘回车
$('zs-act').addEventListener('pointerdown', (e) => { if (e.button === 0 || e.button === 2) { e.preventDefault(); actPress(); } });
$('zs-act').addEventListener('click', (e) => { if (e.detail === 0) actPress(); });   // 键盘回车
$('zs-act').addEventListener('contextmenu', (e) => e.preventDefault());

// ---------------------------------------------------------------- 每 tick
function counters(s) {
  return {
    inPrev: s.inPrev, bursts: s.bursts, bState: s.bState, perfect: livePerfect(s), flipPen: s.flipPen,
    nGood: s.nGood, nLate: s.nLate, nEarly: s.nEarly, nBurnt: s.nBurnt, nAuto: s.nAuto,
    perfB: s.perfB, okB: s.okB, miss: s.miss, mash: s.mash, bi: s.bi, segIdx: s.segIdx, inSeg: s.inSeg,
  };
}
function liveClean(s) {
  const p = s.p;
  if (p.station === STATION.FRYER) return s.bursts === 0 && s.hot + s.cold <= p.cleanTicks;
  if (p.station === STATION.OVEN) return s.flipAllGood === 1;
  if (p.station === STATION.PREP) return s.miss === 0 && s.mash === 0;
  return true;
}
const livePerfect = (s) => s.perfect === 1 && liveClean(s);

function doTick(now) {
  const g = game;
  if (g.phase === 'countdown') {
    input.mainLatch = input.actLatch = false;       // 倒计时不记输入
    if (++g.cd >= COUNTDOWN) { g.phase = 'running'; g.fx.goAt = now; setPhaseText(); announce('下锅！开始'); }
    return;
  }
  if (g.phase !== 'running') return;
  let byte = byteNow();
  if (g.feed) byte = g.feed[g.inputs.length] ?? 0;  // 截图脚本的固定输入（与真人走同一条采样之后的路径）
  input.mainLatch = input.actLatch = false;
  const s = g.s, before = counters(s);
  g.inputs.push(byte);
  SIM.stepInPlace(s, byte);
  g.pred = null;
  g.live = SIM.result(s);
  noteEvents(g, before, now);
  if (s.end !== END.RUNNING) finish(now);
  else updateLive();
}
function noteEvents(g, b, now) {
  const s = g.s, f = g.fx;
  if (b.inPrev === 1 && s.inPrev === 0 && !s.inSeg) f.missAt = now;
  if (s.bursts > b.bursts) { f.splash = { t: now }; f.shakeUntil = now + 240; }
  if (b.bState === BOTTLE.PRESENT && s.bState === BOTTLE.CAUGHT) f.pop = { t: now, u: s.bPos };
  if (b.perfect && !livePerfect(s)) { g.perfectBrokenAt = now; g.perfectWhy = perfectWhy(s); }
  for (const k of ['nGood', 'nLate', 'nEarly', 'nBurnt', 'nAuto']) if (s[k] > b[k]) { g.flipLog.push({ k, pen: s.flipPen - b.flipPen }); f.verdict = { k, t: now, pen: s.flipPen - b.flipPen }; }
  if (b.inSeg) {
    const log = g.beatLog[b.segIdx];
    let i = b.bi;
    if (s.miss > b.miss) { log[i++] = 'miss'; f.ring = { k: 'miss', t: now }; }
    if (s.perfB > b.perfB) { log[i] = 'perfect'; f.ring = { k: 'perfect', t: now }; f.placed = { k: b.segIdx, i }; }
    else if (s.okB > b.okB) { log[i] = 'ok'; f.ring = { k: 'ok', t: now }; f.placed = { k: b.segIdx, i }; }
    if (s.mash > b.mash) f.ring = { k: 'mash', t: now };
  }
}
function perfectWhy(s) {
  const p = s.p;
  if (s.perfect === 0) return `第 ${sec(s.pt)} 秒食材离条超过一眨眼`;
  if (p.station === STATION.FRYER) return s.bursts > 0 ? '爆油了' : '过热 + 冷油超过 1 秒';
  if (p.station === STATION.OVEN) return '有一次翻面不是「正好」';
  if (p.station === STATION.PREP) return s.miss > 0 ? '漏了拍' : '乱按了';
  return '';
}
function finish(now) {
  const g = game;
  g.phase = 'ended'; g.endAt = now; input.mainLatch = input.actLatch = false;
  if (g.s.end === END.ABANDON) {
    g.res = SIM.result(g.s, { gold: g.gold }); g.replay = null;
  } else {
    // 「服务端」口径：拿同一参数块、种子、输入和私下抽的瓶种类从头回放，结果以回放为准（含机械节奏检测）
    const tr = SIM.replayTrace({ block: g.block, seed: g.seed }, g.inputs, { gold: g.gold });
    const liveHash = SIM.hex32(SIM.hashState(g.s));
    g.res = tr.result;
    g.replay = { ok: tr.finalHash === liveHash && tr.consumed === g.inputs.length && tr.padded === 0, hash: tr.finalHash, liveHash };
    if (!g.replay.ok) console.warn('掌勺：回放与实时结果不一致', tr.finalHash, liveHash);
  }
  g.live = g.res;
  setPhaseText(); renderCard(); updatePad();
  announce(`${END_NAME[g.res.end]}，${Q_NAME[g.res.quality]}${g.res.quality >= 0 ? `品质，${g.res.score} 分` : ''}`);
}
function abandon(now, why) {
  const g = game;
  if (g.phase !== 'running' && g.phase !== 'countdown') return;
  g.s = SIM.abandon(g.s);
  g.pred = null;
  g.fx.stallMsg = why || '';
  finish(now);
}
function again() { newRound(undefined, true); cv.focus({ preventScroll: true }); }

// ---------------------------------------------------------------- 主循环（真实时钟 50 ms 一 tick；掉帧时一次补跑完，不封顶）
function frame(now) {
  const dt = Math.max(0, now - lastNow);
  lastNow = now;
  const g = game;
  if (g && !frozen && (g.phase === 'countdown' || g.phase === 'running')) {
    if (dt > STALL_MS) abandon(now, '页面停了超过 5 秒（切到后台或卡住），按「5 秒收不到输入」撂勺处理');
    else {
      acc += dt;
      while (acc >= TICK_MS) {
        acc -= TICK_MS;
        doTick(now);
        if (game !== g || g.phase === 'ended') { acc = 0; break; }
      }
    }
  }
  try { draw(now); } catch (err) { console.error(err); }
  requestAnimationFrame(frame);
}

// ---------------------------------------------------------------- 画布与精灵
const IMG = {};
function loadImages() {
  return Promise.all(Object.entries(DATA.atlases).map(([k, uri]) => new Promise((res, rej) => {
    const im = new Image();
    im.onload = () => res();
    im.onerror = () => rej(new Error('图集加载失败：' + k));
    im.src = uri;
    IMG[k] = im;
  })));
}
function panelH(st) { return st === STATION.PREP ? 208 : 184; }
// 画布按设备像素取整数倍：宽度放得下、且画布 + 按钮区在首屏内（最少 2 倍，宽度不够时再小）
function layoutCanvas() {
  const st = game ? game.s.p.station : ui.station;
  const PH = panelH(st);
  const dpr = window.devicePixelRatio || 1;
  const avail = Math.max(176, stageEl.clientWidth || 176);
  const wFit = Math.max(1, Math.floor(avail * dpr / 176));
  const top = stageEl.getBoundingClientRect().top + (window.scrollY || 0);
  const padH = ($('zs-pad').offsetHeight || 54) + 12 + 12;
  const hFit = Math.floor(Math.max(0, (window.innerHeight || 800) - top - padH) * dpr / PH);
  let s = Math.min(wFit, Math.max(hFit, Math.floor(2 * dpr)), Math.max(1, Math.floor(3 * dpr)));
  s = Math.max(1, s);
  if (cv.width !== 176 * s) cv.width = 176 * s;
  if (cv.height !== PH * s) cv.height = PH * s;
  cv.style.width = (176 * s / dpr) + 'px';
  cv.style.height = (PH * s / dpr) + 'px';
  $('zs-pad').style.maxWidth = Math.max(300, 176 * s / dpr) + 'px';
  S = s;
}
const snap = (v) => Math.round(v * S) / S;
const lerp = (a, b, t) => a + (b - a) * t;
const clamp = (v, lo, hi) => (v < lo ? lo : v > hi ? hi : v);
const anim = (now, ms, n) => Math.floor(now / ms) % n;
const deco = (now, ms, n) => (reduced() ? 0 : anim(now, ms, n));
const blink = (now, ms) => reduced() || anim(now, ms, 2) === 0;
function spr(key) { const v = DATA.spr[key]; if (!v) throw new Error('缺精灵：' + key); return v; }
function frameRect(v, f) {
  const r = v.r;
  if (!v.f) return r;
  const i = ((f % v.f[0]) + v.f[0]) % v.f[0];
  return [r[0], r[1] + i * v.f[2], v.f[1], v.f[2]];
}
function put(key, x, y, f = 0, alpha = 1, k = 1) {
  const v = spr(key), [sx, sy, sw, sh] = frameRect(v, f);
  if (alpha !== 1) ctx.globalAlpha = alpha;
  ctx.drawImage(IMG[v.a], sx, sy, sw, sh, snap(x), snap(y), sw * k, sh * k);
  if (alpha !== 1) ctx.globalAlpha = 1;
}
function putC(key, cx, cy, f = 0, alpha = 1, k = 1) {
  const v = spr(key), r = frameRect(v, f);
  put(key, cx - r[2] * k / 2, cy - r[3] * k / 2, f, alpha, k);
}
function segs(a, b, ss, ds, rep) {
  if (ds < a + b) { const a2 = Math.min(a, Math.floor(ds / 2)), b2 = ds - a2; return [[0, a2, 0, a2], [ss - b2, b2, a2, b2]].filter((x) => x[1] > 0); }
  const out = [];
  if (a) out.push([0, a, 0, a]);
  const sm = ss - a - b, dm = ds - a - b;
  if (dm > 0 && sm > 0) {
    if (!rep) out.push([a, sm, a, dm]);
    else for (let d = 0; d < dm; d += sm) { const w = Math.min(sm, dm - d); out.push([a, w, a + d, w]); }
  }
  if (b) out.push([ss - b, b, ds - b, b]);
  return out;
}
function sliced(key, x, y, w, h, f = 0, alpha = 1) {
  w = Math.round(w); h = Math.round(h);
  if (w <= 0 || h <= 0) return;
  const v = spr(key), [sx, sy, sw, sh] = frameRect(v, f), sl = v.s || [0, 0, 0, 0, 'stretch'];
  const rep = sl[4] === 'repeat', img = IMG[v.a];
  const cols = segs(sl[0], sl[2], sw, w, rep), rows = segs(sl[1], sl[3], sh, h, rep);
  x = snap(x); y = snap(y);
  if (alpha !== 1) ctx.globalAlpha = alpha;
  for (const [ry, rh, dy, dh] of rows) for (const [rx, rw, dx, dw] of cols) ctx.drawImage(img, sx + rx, sy + ry, rw, rh, x + dx, y + dy, dw, dh);
  if (alpha !== 1) ctx.globalAlpha = 1;
}
function rawIcon(xy, x, y, alpha = 1) {
  if (alpha !== 1) ctx.globalAlpha = alpha;
  ctx.drawImage(IMG.dishes, xy[0], xy[1], 16, 16, snap(x), snap(y), 16, 16);
  if (alpha !== 1) ctx.globalAlpha = 1;
}
function trackIcon(xy, cx, cy) { ctx.drawImage(IMG.dishes_track, xy[0], xy[1], 18, 18, snap(cx - 9), snap(cy - 9), 18, 18); }
function mcShadow(hex) {
  const n = parseInt(hex.slice(1), 16);
  return `rgb(${(n >> 16 & 255) >> 2},${(n >> 8 & 255) >> 2},${(n & 255) >> 2})`;
}
function text(str, x, y, o = {}) {
  const size = o.size || 8, color = o.color || '#ffffff';
  ctx.font = `${o.weight || 700} ${size}px ${FONT}`;
  ctx.textAlign = o.align || 'left';
  ctx.textBaseline = 'top';
  if (o.shadow !== false) { const off = Math.max(0.5, size / 8); ctx.fillStyle = mcShadow(color); ctx.fillText(str, x + off, y + off); }
  ctx.fillStyle = color;
  ctx.fillText(str, x, y);
}
function textW(str, size) { ctx.font = `700 ${size}px ${FONT}`; return ctx.measureText(str).width; }
// 亮色字（黄、橙、红）垫一块深色底再写：浅灰面板上直接写对比度不够（原版黄字也都写在深色 tooltip 上）
function chipText(str, x, y, color, o = {}) {
  const size = o.size || 8, w = Math.ceil(textW(str, size)) + 6, h = size + 5;
  const x0 = o.align === 'center' ? x - w / 2 : x;
  sliced('common/tooltip', x0, y - 2, w, h);
  text(str, x0 + 3, y, { size, color });
  return w;
}
function rect(x, y, w, h, color, alpha = 1) {
  if (alpha !== 1) ctx.globalAlpha = alpha;
  ctx.fillStyle = color;
  ctx.fillRect(snap(x), snap(y), Math.round(w * S) / S, Math.round(h * S) / S);
  if (alpha !== 1) ctx.globalAlpha = 1;
}
const DARK = '#404040';    // 原版容器标题色

// 面板坐标（1x GUI 像素）：参考 art/manifest.json 的 referenceLayout
const L = { tx: 62, ty: 34, tw: 26, th: 146, cx: 65, cy: 37, cw: 20, ch: 140, cb: 177, gx: 92, gy: 36, gw: 8, gh: 132 };
const uy = (u) => L.cb - u * L.ch / 1e6;

/** 本帧画面用的状态：当前 tick 与「按此刻按键预跑的下一 tick」之间插值（只用于画面，不进判定）。 */
function viewState(g, now) {
  const s = g.s;
  const running = g.phase === 'running' && s.end === END.RUNNING;
  if (!running) return { s, nx: s, a: 0 };
  const byte = g.feed ? (g.feed[g.inputs.length] ?? 0) : byteNow();
  const key = `${s.tick}|${byte}`;
  if (!g.pred || g.predKey !== key) { g.pred = SIM.step(s, byte); g.predKey = key; }
  return { s, nx: g.pred, a: clamp(acc / TICK_MS, 0, 1) };
}

function draw(now) {
  const g = game;
  if (!g || !IMG.common || !IMG.common.complete) return;
  const s = g.s, p = s.p, st = p.station, key = ST_KEYS[st], PH = panelH(st);
  const vs = viewState(g, now), nx = vs.nx, a = vs.a;
  const V = (k) => lerp(s[k], nx[k], a);
  const live = g.phase === 'running';
  ctx.setTransform(1, 0, 0, 1, 0, 0);
  ctx.clearRect(0, 0, cv.width, cv.height);
  let shx = 0, shy = 0;
  if (g.fx.shakeUntil > now && !reduced()) { shx = Math.round(Math.sin(now / 17) * 1.5); shy = Math.round(Math.cos(now / 23)); }
  ctx.setTransform(S, 0, 0, S, shx * S, shy * S);
  ctx.imageSmoothingEnabled = false;

  // 底板与标题行
  sliced('common/panel', 0, 0, 176, PH);
  put('common/slot', 8, 8);
  rawIcon(g.dish.raw, 9, 9);
  for (let i = 0; i < 7; i++) put(i < p.star ? 'common/star_on' : 'common/star_off', 30 + i * 10, 9);
  drawBadge(g, now, PH);
  drawLiveChip(g);
  text(g.dish.name, 68, 24, { size: 8, color: DARK, shadow: false });
  put(`${key}/icon`, 10, 30);
  put(`common/motion_${MOTION_KEYS[p.motion]}`, 12, 48);
  text(MOTION_NAMES[p.motion], 25, 49, { size: 8, color: DARK, shadow: false });

  // 轨道
  sliced(`${key}/track`, L.tx, L.ty, L.tw, L.th);
  if (st === STATION.OVEN) { put('oven/glow', L.cx, L.cb - 24); put('oven/flames', L.cx, L.cb - 8, deco(now, 120, 4)); }
  if (st === STATION.POT && s.h >= p.heatMax) put('pot/bubbles', L.cx, L.cy, deco(now, 120, 4));

  const yb = V('yb'), yt = V('yt');
  const barH = Math.round(p.H * L.ch / 1e6);
  const dead = s.pin >= p.pinTicks;
  // 出条判定按本帧画出来的位置（预测渲染）：画面上食材出条的那一帧，条就变红、箭头就出来
  const inBarV = !dead && yb <= yt && yt <= yb + p.H;
  if (live && !s.inSeg) {
    if (g.fx.visIn && !inBarV) g.fx.visMissAt = now;
    g.fx.visIn = inBarV;
  } else g.fx.visIn = true;
  let barState = 'normal';
  if (live && !s.inSeg) {
    if (dead) barState = 'dead';
    else if (!inBarV) {
      const since = now - g.fx.visMissAt;
      barState = (!reduced() && since < 300 && Math.floor(since / 100) % 2 === 1) ? 'normal' : 'miss';
    }
  }
  const barTop = uy(yb) - barH;
  sliced(`${key}/bar_${barState}`, L.cx, barTop, L.cw, barH);
  // 按下的那一刻：条上沿亮一下（按键到画面有反馈，不用等下一 tick）
  if (live && now - g.fx.pressAt < 90 && !s.inSeg) rect(L.cx + 1, barTop, L.cw - 2, 2, '#ffffff', 0.7);
  // 贴边 0.25 秒起条边闪烁，提醒快要熄火 / 顶死（spec §6）
  const nearPin = live && !s.inSeg && !dead && s.pin >= 5;
  if (nearPin && blink(now, 120)) {
    rect(L.cx, barTop, L.cw, 1, '#ff5555'); rect(L.cx, barTop + barH - 1, L.cw, 1, '#ff5555');
    rect(L.cx, barTop, 1, barH, '#ff5555'); rect(L.cx + L.cw - 1, barTop, 1, barH, '#ff5555');
  }
  const fy = uy(yt);
  if (live && inBarV && !s.inSeg) putC('common/target_halo', L.cx + 10, fy);
  trackIcon(g.dish.track, L.cx + 10, fy);
  drawGhost(g, now, live);

  // 调料瓶（种类由「服务端」私下抽，出现时揭晓）
  if (s.bState === BOTTLE.PRESENT) {
    const by = uy(s.bPos), gold = g.gold && livePerfect(s);
    if (gold) putC('common/bottle_gold_glint', L.cx + 10, by, deco(now, 120, 4));
    else putC('common/bottle', L.cx + 10, by);
    sliced('common/bottle_meter_frame', L.cx + 1, by + 9, 18, 5);
    const w = Math.round(16 * Math.min(1, V('K') / 1e6));
    if (w > 0) sliced(gold ? 'common/bottle_meter_fill_gold' : 'common/bottle_meter_fill', L.cx + 2, by + 10, w, 3);
  }
  if (g.fx.pop && now - g.fx.pop.t < 300) putC('common/bottle_pop', L.cx + 10, uy(g.fx.pop.u), reduced() ? 2 : Math.floor((now - g.fx.pop.t) / 60));
  if (g.fx.splash && now - g.fx.splash.t < 300) put('fryer/oil_splash', L.cx - 4, fy - 14, reduced() ? 2 : Math.floor((now - g.fx.splash.t) / 60));
  if (st === STATION.OVEN && s.prompt >= 0 && s.pt - s.prompt > p.flipGood + p.flipLate) put('oven/burn_smoke', L.cx + 2, fy - 24, deco(now, 140, 4));
  // 摆料段：节拍判完以后（「接住！」那 0.6 秒）不再压暗，让人看清火候条和食材
  const segBeatsLeft = s.inSeg && s.bi < s.segBeats[s.segIdx].length;
  if (segBeatsLeft) sliced('prep/track_dim', L.cx, L.cy, L.cw, L.ch);

  // 出条箭头（spec §8：食材在条上方 = 火小了，在条下方 = 火大了），按本帧画出来的位置判断
  if (live && !s.inSeg && !inBarV) {
    let up, word;
    if (yt > yb + p.H) { up = true; word = '火小了'; }
    else if (yt < yb) { up = false; word = st === STATION.PREP ? '料多了' : '火大了'; }
    else { up = s.yb === 0; word = s.yb === 0 ? '熄火' : '顶死'; }
    const ay = clamp(fy, 40, 150);
    put(up ? 'common/arrow_up' : 'common/arrow_down', 104, ay - 5);
    chipText(word, 116, ay - 4, up ? '#ffff55' : '#ff5555', { size: 7 });
  } else if (nearPin) {
    chipText(s.yb === 0 ? '贴底久了会熄火' : '顶太久会顶死', 104, 100, '#ff5555', { size: 7 });
  }

  // 熟度条 + 翻面点 / 摆料点
  sliced('common/gauge_frame', L.gx, L.gy, L.gw, L.gh);
  const P = V('P'), fh = Math.round((L.gh - 2) * clamp(P, 0, 1e6) / 1e6);
  if (fh > 0) sliced(`${key}/progress_fill${P >= 750000 ? '_hot' : ''}`, L.gx + 1, L.gy + L.gh - 1 - fh, 6, fh);
  if (fh >= 2) put(`${key}/progress_top`, L.gx + 1, L.gy + L.gh - 1 - fh);
  put(`${key}/progress_cap`, 90, 170);
  const markY = (m) => L.gy + L.gh - 1 - Math.round((L.gh - 2) * m / 1e6);
  if (st === STATION.OVEN) {
    const VK = { nGood: 'good', nLate: 'late', nEarly: 'early', nBurnt: 'burnt', nAuto: 'auto' };
    const g0 = SIM.fullGain(p);
    for (let k = 1; k <= p.F; k++) {
      let m = 'pending';
      const mk = SIM.markAt(p, k, p.F);
      if (k <= g.flipLog.length) m = VK[g.flipLog[k - 1].k];
      else if (k === s.flipIdx + 1 && s.prompt >= 0) m = blink(now, 200) ? 'now' : 'pending';
      // 三角快到了（按满速约 0.3 秒内）：先闪起来，告诉人「快到了」
      else if (k === s.flipIdx + 1 && live && (mk - s.P) <= 6 * g0) m = blink(now, 100) ? 'now' : 'pending';
      put(`oven/flip_mark_${m}`, L.gx + L.gw, markY(mk) - 4);
    }
  } else if (st === STATION.PREP) {
    for (let k = 1; k <= p.segs; k++) put(k <= s.segIdx ? 'prep/seg_mark_done' : 'prep/seg_mark_pending', L.gx + L.gw, markY(SIM.markAt(p, k, p.segs)) - 4);
  }

  // 生 / 焦（备餐台叫「盐」）：红线 = 中品质的负面门槛；没开局时画空
  const started = g.phase === 'running' || g.phase === 'ended';
  const meters = [[146, started ? g.live.underPm : 0, 'raw', '生'], [154, started ? g.live.overPm : 0, st === STATION.PREP ? 'salt' : 'burnt', st === STATION.PREP ? '盐' : '焦']];
  for (const [mx, v, kind, word] of meters) {
    sliced('common/meter_frame', mx, 120, 5, 44);
    const h = Math.round(42 * Math.min(1, v / 200));
    if (h > 0) sliced(`common/meter_fill_${v >= p.thresholdMedium ? 'alert' : kind}`, mx + 1, 163 - h, 3, h);
    put('common/meter_threshold', mx + 1, 163 - Math.round(42 * p.thresholdMedium / 200));
    text(word, mx + 2.5, 166, { size: 7, color: DARK, shadow: false, align: 'center' });
  }

  // 本台仪表
  if (st === STATION.POT) drawPot(g, now, V, dead);
  else if (st === STATION.FRYER) drawFryer(g, now, V);
  else if (st === STATION.OVEN) drawOven(g, now, V);
  else drawPrep(g, now, V);

  // 时间：已用 / 时限 + 倒数条；最后 3 秒闪「快到点」（spec §12，时限只算正式 tick）
  if (started) drawTimer(g, now, V);

  // 覆盖层：待开始 / 倒计时 / 下锅！ / 撂勺提示 / 结算
  if (g.phase === 'ready') {
    sliced('common/tooltip', 14, 74, 148, 52);
    text('按一下开始', 88, 81, { size: 11, align: 'center' });
    text('空格 / 左键 / 手指：按住加火', 88, 98, { size: 7, color: '#aaaaaa', align: 'center' });
    text(st === STATION.POT ? '厨锅只用这一个键' : `F / 右键：${ST_ACT[st]}`, 88, 110, { size: 7, color: '#aaaaaa', align: 'center' });
  } else if (g.phase === 'countdown') {
    const n = g.cd < 7 ? 3 : g.cd < 14 ? 2 : 1;
    sliced('common/tooltip', 58, 74, 60, 60);
    putC(`common/countdown_${n}`, 88, 104, 0, 1, 2);
  } else if (live && now - g.fx.goAt < 650) {
    chipText('下锅！', 88, 100, '#ffff55', { size: 12, align: 'center' });
  }
  if ((live || g.phase === 'countdown') && now - g.fx.escAt < 1500) {
    sliced('common/tooltip', 22, 138, 132, 18);
    text('再按一次 Esc 撂勺', 88, 142, { size: 8, align: 'center', color: '#ff5555' });
  }
  if (g.phase === 'ended') drawEnd(g, now);
}

// 新手落点虚影：只画离食材超过半根火候条的目的地，换目的地后 0.2 秒淡入（近处的目的地画出来只会添乱）
function drawGhost(g, now, live) {
  const s = g.s, p = s.p;
  if (!ui.ghost || !live || s.inSeg || s.tgt === NONE) { g.fx.ghostTgt = NONE; return; }
  if (s.tgt !== g.fx.ghostTgt) { g.fx.ghostTgt = s.tgt; g.fx.ghostAt = now; }
  if (Math.abs(s.tgt - s.yt) <= p.H / 2) return;
  const fade = reduced() ? 1 : clamp((now - g.fx.ghostAt) / 200, 0, 1);
  putC('common/target_ghost', L.cx + 10, uy(s.tgt), 0, 0.85 * fade);
}
function drawTimer(g, now, V) {
  const s = g.s, p = s.p, live = g.phase === 'running';
  const ptV = live && !s.inSeg ? V('pt') : s.pt;
  const left = Math.max(0, p.CAP - ptV);
  // 备餐台右下角有节拍灯，时间条挪到上面一点
  const x = 104, y = p.station === STATION.PREP ? 150 : 164, w = 38;
  if (live && s.inSeg) text('摆料中', x, y, { size: 7, color: DARK, shadow: false });
  else text(`${sec(Math.min(ptV, p.CAP))}/${Math.round(p.CAP / 20)}秒`, x, y, { size: 7, color: DARK, shadow: false });
  rect(x, y + 10, w, 4, '#373737');
  const f = Math.round((w - 2) * left / p.CAP);
  const col = left <= 60 ? '#ff5555' : left <= 120 ? '#ffff55' : '#55ff55';
  if (f > 0) rect(x + 1, y + 11, f, 2, col);
  if (live && !s.inSeg && left <= 60 && blink(now, 250)) chipText('快到点', x, y - 14, '#ff5555', { size: 8 });
}

function drawBadge(g, now, PH) {
  if (!g.perfectBrokenAt) { put('common/perfect_badge', 110, 10); return; }
  const dt = now - g.perfectBrokenAt;
  if (!reduced() && dt < 350) {
    // 碎片裁在面板内框里，不压到边框外
    ctx.save(); ctx.beginPath(); ctx.rect(3, 3, 170, PH - 6); ctx.clip();
    put('common/perfect_shatter', 102, 8, Math.floor(dt / 70));
    ctx.restore();
  } else put('common/perfect_badge_broken', 110, 10);
}
// 实时品质徽章（spec §17.4：按当前分数显示，越线变色）；分数线取参数块，不另写
function liveQuality(r, p) {
  if (r.ended) return r.quality;
  return r.score >= p.tierExtraordinary ? 3 : r.score >= p.tierHigh ? 2 : r.score >= p.tierMedium ? 1 : 0;
}
function drawLiveChip(g) {
  const x = 30, y = 22, w = 34, h = 12;
  if (g.phase === 'ready' || g.phase === 'countdown') {
    sliced('common/chip_white', x, y, w, h);
    text('准备', x + w / 2, y + 2, { size: 7, align: 'center' });
    return;
  }
  const q = liveQuality(g.live, g.s.p);
  sliced(`common/chip_${Q_CHIP[q]}`, x, y, w, h);
  text(Q_NAME[q], x + w / 2, y + 2, { size: 7, align: 'center', color: Q_MC[q] });
}
function keycap(x, y, ch) {
  sliced('common/keycap', x, y, 11, 12);
  text(ch, x + 5.5, y + 2, { size: 7, align: 'center', color: '#373737', shadow: false });
}
function drawPot(g, now, V, dead) {
  const s = g.s, p = s.p;
  const hx = 44, hy = 52, hh = 116;
  sliced('common/gauge_frame', hx, hy, 8, hh);
  const h = V('h'), live = g.phase === 'running';
  const tier = live && dead ? 'dead' : h < 400 ? 'low' : h < 800 ? 'mid' : 'high';
  const hf = Math.round((hh - 2) * h / 1000);
  if (hf > 0) sliced(`pot/heat_fill_${tier}`, hx + 1, hy + hh - 1 - hf, 6, hf);
  if (tier === 'dead') put('pot/heat_tip_dead', hx, hy + hh - 1 - hf - 10);
  else put(`pot/heat_tip_${tier}`, hx, hy + hh - 1 - hf - 10, deco(now, 110, 4));
  put('pot/flame_small', 28, 140, 0, tier === 'low' ? 1 : 0.35);
  put('pot/flame_mid', 28, 112, 0, tier === 'mid' ? 1 : 0.35);
  put('pot/flame_big', 28, 84, 0, tier === 'high' ? 1 : 0.35);
  const mul = p.heatMulBase + Math.floor(s.h * p.heatMulSpan / 1000);
  text('火候', 106, 38, { size: 8, color: DARK, shadow: false });
  text(String(s.h), 130, 38, { size: 8, color: DARK, shadow: false });
  text(`${tier === 'dead' ? '熄火' : tier === 'low' ? '小火' : tier === 'mid' ? '中火' : '旺火'} ×${(mul / 1000).toFixed(2)}`, 106, 50, { size: 7, color: DARK, shadow: false });
}
function drawFryer(g, now, V) {
  const s = g.s, p = s.p, live = g.phase === 'running';
  // 油温到 880 起温度计抖动（爆油预警）；到 1000 爆油
  const warn = live && s.T >= 880;
  const jx = warn && !reduced() ? (anim(now, 40, 2) ? 1 : -1) : 0;
  const fx0 = 44 + jx, fy0 = 34, fhh = 146, ix = fx0 + 4, it = fy0 + 3, ih = fhh - 3 - 12, ib = it + ih;
  const ty = (T) => ib - Math.round(clamp(T, 0, 1000) * ih / 1000);
  sliced('fryer/thermo_frame', fx0, fy0, 12, fhh);
  sliced('fryer/thermo_band_cold', ix, ty(300), 4, ib - ty(300));
  sliced('fryer/thermo_band_gold', ix, ty(750), 4, ty(300) - ty(750));
  sliced('fryer/thermo_band_hot', ix, it, 4, ty(750) - it);
  const T = V('T');
  sliced('fryer/thermo_column', ix + 1, ty(T), 2, ib - ty(T) + 2);
  put('fryer/thermo_column_top', ix + 1, ty(T));
  put('fryer/thermo_pointer', fx0 + 12, ty(T) - 4);
  put('fryer/thermo_tick', fx0 - 3, ty(300));
  put('fryer/thermo_tick', fx0 - 3, ty(750));
  if (s.cd > 0) put('fryer/cooldown_ring', 106, 36, Math.round(8 * (1 - s.cd / p.oilCd)));
  else put('fryer/cooldown_ready', 106, 36);
  keycap(109, 38, 'F');
  text('压火', 126, 40, { size: 8, color: DARK, shadow: false });
  text(`油温 ${s.T}`, 106, 58, { size: 8, color: DARK, shadow: false });
  let word, col;
  if (g.fx.splash && now - g.fx.splash.t < 900) { word = '爆油！'; col = '#ff5555'; }
  else if (warn) { word = blink(now, 150) ? '要爆了！' : '快压火！'; col = '#ff5555'; }
  else if (s.T >= p.oilHot) { word = '过热'; col = '#ff5555'; }
  else if (s.T < p.oilCold) { word = '冷油'; col = '#7f9fff'; }
  else { word = '金黄'; col = '#ffaa00'; }
  chipText(word, 106, 72, col, { size: 8 });
}
function drawOven(g, now, V) {
  const s = g.s, p = s.p, x0 = 108;
  const VERDICT = { nGood: ['正好', 'green'], nLate: ['稍晚', 'yellow'], nEarly: ['翻早', 'blue'], nBurnt: ['焦面', 'brown'], nAuto: ['自动翻', 'red'] };
  const VCOL = { green: '#55ff55', yellow: '#ffff55', blue: '#7f8fff', brown: '#c8823c', red: '#ff5555' };
  if (s.prompt >= 0 && g.phase === 'running') {
    sliced('oven/flip_prompt', x0, 36, 60, 15);
    text('翻面！', x0 + 30, 38, { size: 9, align: 'center' });
    const dt = s.pt - s.prompt + (g.phase === 'running' ? clamp(acc / TICK_MS, 0, 1) : 0);
    let ring, f;
    // 内核：按下时 dt = pt − prompt；dt ≤ flipGood 正好，再 12 tick 内稍晚，更晚焦面（spec §13.3）
    if (dt < p.flipGood + 1) { ring = 'good'; f = Math.floor(16 * dt / (p.flipGood + 1)); }
    else if (dt < p.flipGood + p.flipLate + 1) { ring = 'late'; f = Math.floor(16 * (dt - p.flipGood - 1) / p.flipLate); }
    else { ring = 'burn'; f = Math.floor(16 * (dt - p.flipGood - p.flipLate - 1) / Math.max(1, p.flipAuto - p.flipGood - p.flipLate - 1)); }
    put(`oven/flip_ring_${ring}`, x0 + 22, 56, clamp(f, 0, 15));
  } else if (g.fx.verdict && now - g.fx.verdict.t < 900) {
    const [w0, c] = VERDICT[g.fx.verdict.k];
    const w = g.fx.verdict.k === 'nEarly' ? (g.fx.verdict.pen > p.penEarlyMild ? '翻早·重' : '翻早·轻') : w0;
    sliced(`common/chip_${c}`, x0, 37, 60, 13);
    text(w, x0 + 30, 39, { size: 8, align: 'center', color: VCOL[c] });
  }
  put('oven/flip_icon', x0, 56);
  keycap(x0 + 42, 58, 'F');
  text(`翻面 ${s.flipIdx}/${p.F}`, x0, 76, { size: 8, color: DARK, shadow: false });
}
function drawPrep(g, now, V) {
  const s = g.s, p = s.p, drink = g.dish.skin === 'drink';
  const lx = 8, ly = 186, lw = 160, pcx = lx + lw - 13, pcy = ly + 10;
  sliced('prep/beat_lane', lx, ly, lw, 20);
  put(drink ? 'prep/beat_shaker' : 'prep/beat_plate', lx + lw - 24, ly - 1);
  const sizes = p.segs === 2 ? [Math.floor((p.beats + 1) / 2), p.beats - Math.floor((p.beats + 1) / 2)] : [p.beats];
  const segNow = s.inSeg ? s.segIdx : Math.min(s.segIdx, p.segs - 1);
  const Tm = s.segBeats[segNow] || [], log = g.beatLog[segNow] || [];
  const base = segNow === 1 ? sizes[0] : 0;
  // 节拍灯
  for (let i = 0; i < Tm.length; i++) {
    const on = log[i] === 'perfect' || log[i] === 'ok' || (s.inSeg && s.segT === Tm[i] && !log[i]);
    put(on ? 'prep/beat_light_on' : 'prep/beat_light_off', 104 + i * 8, 177);
  }
  const upcoming = !s.inSeg && s.segIdx < p.segs;
  if (s.inSeg || upcoming) {
    // 摆料段里图标滑向盘子；还没进段时，下一段的料按开段那一刻的位置提前排好（预告，半透明）
    const t = s.inSeg ? V('segT') : 0;
    ctx.save();
    ctx.beginPath(); ctx.rect(lx + 2, ly + 1, lw - 4, 18); ctx.clip();
    for (let i = s.inSeg ? s.bi : 0; i < Tm.length; i++) {
      const x = pcx - (Tm[i] - t) * 4;
      if (x < lx - 8 || x > pcx + 6) continue;
      const ic = g.beatIcons[base + i];
      const al = upcoming ? 0.6 : 1;
      if (ic.spr) putC(ic.spr, x, pcy, 0, al); else rawIcon(ic.raw, x - 8, pcy - 8, al);
    }
    ctx.restore();
  }
  if (s.inSeg) {
    if (s.bi < Tm.length) { chipText('摆料！', 106, 38, '#ffff55', { size: 10 }); keycap(150, 38, 'F'); }
    else {
      // 「接住！」：摆完了，0.6 秒后火候条和食材接着动；显示现在的按键状态（恢复时用的就是它）
      chipText('接住！', 106, 38, '#55ff55', { size: 10 });
      const held = isHeld();
      sliced(held ? 'common/chip_yellow' : 'common/chip_gray', 106, 54, 40, 12);
      text(held ? '按住中' : '松开', 126, 56, { size: 7, align: 'center', color: held ? '#ffff55' : '#ffffff' });
    }
  } else if (upcoming) {
    text('熟度到 ◀ 处', 106, 38, { size: 7, color: DARK, shadow: false });
    text('开始摆料', 106, 48, { size: 7, color: DARK, shadow: false });
  } else {
    text('摆料完成', 106, 38, { size: 7, color: DARK, shadow: false });
  }
  if (g.fx.placed && !drink) {
    const ic = g.beatIcons[(g.fx.placed.k === 1 ? sizes[0] : 0) + g.fx.placed.i];
    if (ic && ic.raw && now - g.fx.ring.t < 600) rawIcon(ic.raw, pcx - 8, pcy - 9);
  }
  if (g.fx.ring && now - g.fx.ring.t < 280) putC(`prep/beat_ring_${g.fx.ring.k === 'perfect' ? 'perfect' : g.fx.ring.k === 'ok' ? 'ok' : 'miss'}`, pcx, pcy);
  if (g.fx.ring && now - g.fx.ring.t < 700 && s.inSeg && s.bi < Tm.length) {
    const W = { perfect: ['正好', '#ffaa00'], ok: ['可以', '#55ffff'], miss: ['漏拍', '#ff5555'], mash: ['乱按', '#c8823c'] }[g.fx.ring.k];
    chipText(W[0], 106, 56, W[1], { size: 8 });
  }
  const judged = s.perfB + s.okB + s.miss;
  text(`节拍 ${judged}/${p.beats}`, 106, 76, { size: 8, color: DARK, shadow: false });
}
function drawEnd(g, now) {
  const r = g.res, q = r.quality;
  ctx.fillStyle = 'rgba(0,0,0,0.32)';
  ctx.fillRect(3, 3, 170, panelH(g.s.p.station) - 6);
  sliced('common/tooltip', 14, 70, 148, 58);
  const name = q >= 0 ? `${Q_NAME[q]}品质` : '家常菜';
  if (q === QUALITY.RADIANT && !reduced()) { ctx.globalAlpha = 0.25 + 0.15 * Math.sin(now / 160); ctx.fillStyle = '#ffaa00'; ctx.fillRect(14, 70, 148, 58); ctx.globalAlpha = 1; }
  text(name, 88, 76, { size: 13, align: 'center', color: Q_MC[q] });
  const line = q >= 0 ? `${END_NAME[r.end]} · ${r.score} 分 · ${sec(r.ticks)} 秒` : `${END_NAME[r.end]} · ${sec(r.ticks)} 秒 · 做成家常菜`;
  text(line, 88, 96, { size: 7, align: 'center', color: '#ffffff' });
  text(coarseMQ.matches ? '点画布或下方按钮再来一份' : 'Enter / 「再来一份」开下一局', 88, 110, { size: 7, align: 'center', color: '#aaaaaa' });
  if (r.perfect) put('common/perfect_stamp', 43, 132);
}

// ---------------------------------------------------------------- 右侧卡片：本局实时 / 结算
function phaseLabel(g) {
  if (g.phase === 'ready') return '待开始';
  if (g.phase === 'countdown') return '倒计时';
  if (g.phase === 'running') return g.s.inSeg ? '摆料段' : '进行中';
  return END_NAME[g.res.end];
}
function setPhaseText() {
  const el = $('zs-phase');
  if (el) { el.textContent = phaseLabel(game); el.classList.toggle('run', game.phase === 'running' || game.phase === 'countdown'); }
}
function dishLine(g) {
  const p = g.s.p, d = g.dish;
  return `★${p.star} ${d.name} · ${ST_NAMES[p.station]} · 性子「${MOTION_NAMES[p.motion]}」 · 厨师 ${p.level} 级${ui.mastery ? ` · 手熟 ${MASTERY_NAMES[ui.mastery]}` : ''} · 种子 <span class="mono">${g.seed}</span>`;
}
function effectHTML(d, q) {
  return `<b>${d.eff.replace(/（.*$/, '')}</b>${/（/.test(d.eff) ? `（${d.eff.replace(/^.*?（|）$/g, '')}）` : ''} · ${d.val} · <b>${EFFECT_TIME[q]}</b>`;
}
// 分数尺：600–1040，品质线取参数块（tierMedium / tierHigh / tierExtraordinary）
const SC_LO = 600, SC_HI = 1040;
const scalePos = (v) => ((clamp(v, SC_LO, SC_HI) - SC_LO) / (SC_HI - SC_LO) * 100).toFixed(2) + '%';
function scaleHTML(score, idle, p) {
  const m = p.tierMedium, h = p.tierHigh, x = p.tierExtraordinary;
  const w = (a, b) => ((b - a) / (SC_HI - SC_LO) * 100).toFixed(2) + '%';
  return `<div class="scale${idle ? ' idle' : ''}" aria-hidden="true"><div class="bands"><i class="b-low" style="width:${w(SC_LO, m)}"></i><i class="b-mid" style="width:${w(m, h)}"></i><i class="b-high" style="width:${w(h, x)}"></i><i class="b-ex" style="width:${w(x, SC_HI)}"></i></div>`
    + `<span class="tick" style="left:${scalePos(m)}">${m} 中</span><span class="tick" style="left:${scalePos(h)}">${h} 高</span><span class="tick" style="left:${scalePos(x)}">${x} 超凡</span>`
    + `<span class="mark" id="zs-mark" style="left:${scalePos(score)}"></span></div>`;
}
function bottleText(g, ended) {
  const s = g.s;
  if (s.bKind === BOTTLE_KIND.NONE) return (ended || s.pt > s.p.bAppearMax) ? '本局没有调料瓶' : '还没出现';
  if (s.bState === BOTTLE.WAITING) return ended ? '瓶没等到出现' : '还没出现';
  const kind = g.gold ? '金瓶' : '普通瓶';
  if (s.bState === BOTTLE.PRESENT) return ended ? `${kind} · 错过` : `${kind} · 抓取 ${Math.floor(s.K / 10000)}%`;
  return `${kind} · 抓到 +${s.p.scoreBonus}`;
}
function stationLine(s) {
  const p = s.p;
  if (p.station === STATION.POT) return `火候 ${s.h} · 平均 ${s.S ? Math.floor(s.hSum / s.S) : s.h}（满火 1000）`;
  if (p.station === STATION.FRYER) return `油温 ${s.T} · 过热 ${sec(s.hot)} 秒 · 冷油 ${sec(s.cold)} 秒 · 爆油 ${s.bursts} 次`;
  if (p.station === STATION.OVEN) return `翻面 ${s.flipIdx}/${p.F} · 正好 ${s.nGood} · 稍晚 ${s.nLate} · 翻早 ${s.nEarly} · 焦面 ${s.nBurnt} · 自动 ${s.nAuto}`;
  return `摆料段 ${s.segIdx}/${p.segs} · 正好 ${s.perfB} · 可以 ${s.okB} · 漏 ${s.miss} · 乱 ${s.mash}`;
}
function renderCard() {
  const g = game, card = $('zs-card');
  if (g.phase === 'ended') { card.innerHTML = resultHTML(g); wireResult(); return; }
  card.innerHTML = `<div class="card-head"><h2>本局</h2><span class="phase" id="zs-phase"></span></div>
    <p class="dishline">${dishLine(g)}</p>
    <div class="bigq"><span class="qn" id="zs-lq">—</span><span class="qs"><b id="zs-lscore">0</b> 分（按当前准度估）</span></div>
    ${scaleHTML(0, true, g.s.p)}
    <dl class="kv">
      <dt>熟度</dt><dd id="zs-l-p"></dd>
      <dt>准度</dt><dd id="zs-l-c"></dd>
      <dt>本台扣分</dt><dd id="zs-l-pen"></dd>
      <dt>调料瓶</dt><dd id="zs-l-b"></dd>
      <dt>Perfect</dt><dd id="zs-l-perf"></dd>
      <dt>本台</dt><dd id="zs-l-st"></dd>
      <dt>用时</dt><dd id="zs-l-t"></dd>
    </dl>
    <p class="note">结束后这里显示结算：品质、得分明细、翻车原因，以及这道菜的厨师效果（${g.dish.eff.replace(/（.*$/, '')}）。</p>`;
  setPhaseText(); updateLive();
}
function updateLive() {
  const g = game;
  if (!g || g.phase === 'ended' || !$('zs-l-p')) return;
  const s = g.s, r = g.live, started = g.phase === 'running';
  const q = started ? liveQuality(r, s.p) : null;
  const lq = $('zs-lq');
  lq.textContent = started ? Q_NAME[q] : '—';
  lq.className = 'qn' + (started ? ` q-${Q_CLASS[q]}` : '');
  $('zs-lscore').textContent = started ? r.score : 0;
  const sc = document.querySelector('#zs-card .scale');
  if (sc) { sc.classList.toggle('idle', !started); $('zs-mark').style.left = scalePos(r.score); }
  $('zs-l-p').textContent = `${(s.P / 10000).toFixed(1)}%`;
  $('zs-l-c').textContent = started ? `${pc(r.accuracy)}（框住 ${sec(s.I)} / ${sec(s.S)} 秒）` : '—';
  $('zs-l-pen').textContent = started ? (r.pen ? `−${r.pen}` : '0') : '—';
  $('zs-l-b').textContent = bottleText(g, false);
  $('zs-l-perf').textContent = livePerfect(s) ? '保持中' : `已断：${g.perfectWhy}`;
  $('zs-l-st').textContent = stationLine(s);
  $('zs-l-t').textContent = `${sec(s.tick)} 秒（最多 ${sec(s.p.CAP)} 秒${s.p.station === STATION.PREP ? '，摆料段不算' : ''}）`;
}
// 扣分 / 翻车值的拆解说明（说人话）。合计以内核 result() 为准，这里只把各项摊开给人看，常数全取参数块。
function penRows(g) {
  const r = g.res, s = g.s, p = s.p, st = p.station;
  if (st === STATION.POT) return `平均火候 ${r.avgHeat}（满火 1000），每差 ${p.penDiv} 扣 1 分`;
  if (st === STATION.FRYER) return `过热 ${pc(r.hotPm)}、冷油 ${pc(r.coldPm)} 的时间（占比每 1% 扣 3.3 分）${r.bursts ? ` · 爆油 ${r.bursts} 次（每次扣 ${p.penBurst}）` : ''}`;
  if (st === STATION.OVEN) {
    const VN = { nGood: '正好', nLate: '稍晚', nEarly: '翻早', nBurnt: '焦面', nAuto: '自动翻' };
    const parts = g.flipLog.map((x, i) => `第 ${i + 1} 面${VN[x.k]}${x.pen ? `（扣 ${x.pen}）` : ''}`);
    return parts.length ? parts.join(' · ') : '还没翻面';
  }
  const parts = [`正好 ${r.beatPerfect}`];
  if (r.beatOk) parts.push(`可以 ${r.beatOk}（每拍扣 ${p.penOk}）`);
  if (r.beatMiss) parts.push(`漏拍 ${r.beatMiss}（每拍扣 ${p.penMiss}）`);
  if (r.beatMash) parts.push(`乱按 ${r.beatMash}（每次扣 ${p.penMash}）`);
  return parts.join(' · ');
}
function negDetail(g) {
  const r = g.res, s = g.s, p = s.p, st = p.station, S0 = Math.max(1, s.S);
  const baseU = Math.floor(s.U * 1000 / S0), baseO = Math.floor(s.O * 1000 / S0);
  const u = [`${pc(baseU)} 的时间食材在火候条上方（火给小了）`], o = [`${pc(baseO)} 的时间食材在火候条下方（${st === STATION.PREP ? '料放多了' : '火给大了'}）`], m = [];
  if (st === STATION.POT) u.push(`火候不够 +${pc(Math.floor((1000 - r.avgHeat) / p.underDiv))}`);
  if (st === STATION.FRYER) {
    if (r.hotPm) o.push(`过热 +${pc(Math.floor(r.hotPm / p.negHotDiv))}`);
    if (r.bursts) o.push(`爆油 ${r.bursts} 次 +${pc(p.negBurst * r.bursts)}`);
    m.push(`冷油下锅 +${pc(Math.floor(r.coldPm / p.negColdDiv))}`);
  }
  if (st === STATION.OVEN) {
    if (s.nEarly) u.push(`翻早 ${s.nEarly} 次 +${pc(s.flipUnder)}`);
    const late = p.negLateOver * s.nLate, auto = p.negAutoOver * s.nAuto, burnt = s.flipOver - late - auto;
    if (s.nLate) o.push(`稍晚 +${pc(late)}`);
    if (s.nBurnt) o.push(`焦面 +${pc(burnt)}`);
    if (s.nAuto) o.push(`自动翻 +${pc(auto)}`);
  }
  if (st === STATION.PREP) {
    if (r.beatMiss) m.push(`漏拍 ${r.beatMiss} 次 +${pc(Math.floor(r.beatMiss * p.negMissPm / p.beats))}`);
    if (r.beatMash) m.push(`乱按 ${r.beatMash} 次 +${pc(p.negMash * r.beatMash)}`);
  }
  if (!m.length) m.push(st === STATION.FRYER || st === STATION.PREP ? '没有' : '这个台子不会有');
  return { u, o, m };
}
function negEffect(code, q, st) {
  if (code === NEG.UNDERDONE) return `缓慢 / 挖掘疲劳 / 虚弱三选一（按菜固定），${[12, 8, 6][q]} 秒，必定生效`;
  if (code === NEG.SCORCHED) return st === STATION.PREP ? '这一口饱和度减半' : `吃下扣最大生命 ${[8, 5, 3][q]}%（至少留 1 血）`;
  return `中毒 ${['II 8 秒', 'I 6 秒', 'I 4 秒'][q]}`;
}
function negHTML(g) {
  const r = g.res, p = g.s.p, st = p.station, q = r.quality;
  if (q < 0) return `<section class="neg"><h3>翻车</h3><p class="note">${g.fx.stallMsg ? g.fx.stallMsg + '。' : ''}糊锅、撂勺不进结算，直接出家常菜：没有品质、没有厨师效果、0 经验，料不退。</p></section>`;
  const th = q === 0 ? p.thresholdLow : q === 1 ? p.thresholdMedium : p.thresholdHigh;
  const maxN = q === 0 ? p.maxLow : q === 1 ? p.maxMedium : p.maxHigh;
  const d = negDetail(g);
  const names = ['夹生', st === STATION.PREP ? '多盐' : '烧焦', '倒胃'];
  const vals = [r.underPm, r.overPm, r.messPm], whys = [d.u, d.o, d.m];
  const max = Math.max(200, ...vals, th * 2);
  const rows = vals.map((v, i) => {
    const hit = r.negatives.includes(i);
    return `<div class="negrow${hit ? ' hit' : ''}"><span class="nn">${names[i]}</span><span class="nb"><i style="width:${(v / max * 100).toFixed(1)}%"></i>${q <= 2 ? `<s style="left:${(th / max * 100).toFixed(1)}%"></s>` : ''}</span><span class="nv">${pc(v)}</span><span class="nwhy">${whys[i].join(' · ')}</span></div>`;
  }).join('');
  let head;
  if (q >= 3) head = '超凡和闪耀永远不带负面。下面是这局的三个翻车值，仅供参考。';
  else if (!r.negatives.length) head = `${Q_NAME[q]}品质的门槛是 ${pc(th)}，这局三个翻车值都没过线，不带负面。`;
  else head = `${Q_NAME[q]}品质的门槛是 ${pc(th)}（最多 ${maxN} 条）。这局带：` + r.negatives.map((c) => `<b>${names[c]}</b>（${negEffect(c, q, st)}）`).join('；') + '。';
  if (r.end === END.TIMEOUT && q <= 2) head += ' 到点必带夹生。';
  return `<section class="neg"><h3>翻车</h3><p class="note">${head}</p><div class="negrows">${rows}</div></section>`;
}
function resultHTML(g) {
  const r = g.res, s = g.s, p = s.p, q = r.quality, d = g.dish;
  const caps = [];
  if (q >= 0 && q < r.qualityRaw) {
    if (r.end === END.TIMEOUT && r.qualityRaw > p.timeoutCap) caps.push('到点：最高「中」');
    if (r.mechanical && r.qualityRaw > p.mechanicalCap) caps.push('按键节奏一模一样（像连点宏）：最高「中」');
  }
  const tags = [];
  tags.push(r.perfect ? '<li class="tag ok">Perfect ✓</li>' : `<li class="tag">Perfect ✗${g.perfectWhy ? ` · ${g.perfectWhy}` : r.end !== END.DONE ? ' · 没有正常出锅' : ''}</li>`);
  const bt = bottleText(g, true);
  tags.push(`<li class="tag${s.bState === BOTTLE.CAUGHT ? ' ok' : ''}">${bt}</li>`);
  for (const c of caps) tags.push(`<li class="tag warn">${c}</li>`);
  if (r.mechanical && !caps.length) tags.push('<li class="tag warn">按键节奏一模一样（像连点宏）</li>');
  const need = combatNeed(d);
  if (q >= 0 && need && ui.level < need) tags.push(`<li class="tag warn">战斗菜：正式服厨师 ${need} 级才能掌勺，这一级只能做家常</li>`);
  const effect = q < 0
    ? '家常菜：没有厨师效果，0 经验。'
    : `这份菜的厨师效果：${effectHTML(d, q)}${q === 4 ? '（死亡前一直有效）' : ''}<span class="why">${d.cat}类 · 效果由菜决定，数值由星级决定，品质只决定时长</span>`;
  const why = q === 4 ? 'Perfect + 金瓶 = 闪耀' : (q === 3 && r.perfect && r.score < p.tierExtraordinary ? 'Perfect 必出超凡' : '');
  const seg = s.tick - s.pt;
  const time = `${sec(r.ticks)} 秒${seg > 0 ? `（正式段 ${sec(s.pt)} 秒 + 摆料段 ${sec(seg)} 秒）` : ''}`;
  const rp = g.replay
    ? (g.replay.ok
      ? `<span title="状态哈希 ${g.replay.hash}">防作弊复算：服务器用同样的种子和你的 ${g.inputs.length} 次按键重算了一遍，结果一致 ✓</span>`
      : `防作弊复算不一致：服务器算出 <code>${g.replay.hash}</code>，本地 <code>${g.replay.liveHash}</code>，以服务器为准。`)
    : '撂勺由服务器在两次判定之间处理，不做复算。';
  return `<div class="card-head"><h2>结算</h2><span class="phase" id="zs-phase">${END_NAME[r.end]}</span></div>
    <p class="dishline">${dishLine(g)}</p>
    <div class="bigq"><span class="qn q-${Q_CLASS[q]}">${q >= 0 ? Q_NAME[q] : '家常菜'}</span><span class="qs">${q >= 0 ? `${r.score} 分 · ` : ''}${END_NAME[r.end]} · 用时 ${time}${why ? ` · ${why}` : ''}</span></div>
    <p class="effect">${effect}</p>
    <ul class="tags">${tags.join('')}</ul>
    ${q >= 0 ? scaleHTML(r.score, false, p) : ''}
    <table class="score">
      <tr><th>准度<span class="why">${pc(r.accuracy)} 的时间框住了食材（${sec(r.I)} / ${sec(r.S)} 秒）</span></th><td class="n">${r.accuracy}</td></tr>
      <tr><th>本台扣分<span class="why">${penRows(g)}</span></th><td class="n">${r.pen ? `−${r.pen}` : '0'}</td></tr>
      <tr><th>调料瓶<span class="why">${bt}</span></th><td class="n">+${r.bottleBonus}</td></tr>
      <tr class="total"><th>总分</th><td class="n">${r.score}</td></tr>
    </table>
    ${negHTML(g)}
    <p class="replay">${rp}</p>
    <div class="actions">
      <button type="button" class="btn primary" id="zs-again">再来一份 <kbd>Enter</kbd></button>
      <button type="button" class="btn" id="zs-same">同种子重打</button>
      <button type="button" class="btn" id="zs-copy">复制回放数据</button>
    </div>
    <textarea id="zs-copybox" readonly hidden aria-label="回放数据 JSON"></textarea>`;
}
function replayJSON(g) {
  const r = g.res;
  return JSON.stringify({
    simVersion: SIM.SIM_VERSION, dish: g.dish.id, seed: g.seed, opts: g.opts, paramsHash: SIM.hex32(SIM.paramsHash(g.block)),
    ext: { gold: g.gold }, inputsRLE: SIM.rleEncode(g.inputs),
    result: { end: r.end, quality: r.quality, score: r.score, accuracy: r.accuracy, pen: r.pen, perfect: r.perfect, bottle: r.bottle, gold: r.gold, negatives: r.negatives, ticks: r.ticks, hash: r.hash },
  });
}
function wireResult() {
  $('zs-again').addEventListener('click', again);
  $('zs-same').addEventListener('click', () => { newRound(game.seed, true); cv.focus({ preventScroll: true }); });
  $('zs-copy').addEventListener('click', () => {
    const txt = replayJSON(game), box = $('zs-copybox'), btn = $('zs-copy');
    const fallback = () => { box.hidden = false; box.value = txt; box.focus(); box.select(); btn.textContent = '已选中，按 Ctrl+C 复制'; };
    try {
      navigator.clipboard.writeText(txt).then(() => { btn.textContent = '已复制'; }, fallback);
    } catch (e) { fallback(); }
  });
}
function announce(msg) { $('zs-announce').textContent = msg; }

// ---------------------------------------------------------------- 设置栏
function starsHTML(n) { return `<span class="stars" aria-label="${n} 星">${'★'.repeat(n)}<span class="off">${'★'.repeat(7 - n)}</span></span>`; }
function renderStations() {
  const box = $('zs-stations');
  const btns = box.querySelectorAll('button[data-st]');
  // 已经建好就只改按下状态（不重建按钮，键盘焦点留在原处）
  if (btns.length === DATA.stations.length) btns.forEach((b) => b.setAttribute('aria-pressed', String(+b.dataset.st === ui.station)));
  else box.innerHTML = DATA.stations.map((st, i) => `<button type="button" data-st="${i}" aria-pressed="${i === ui.station}"><img class="pix" src="${st.icon}" alt="" width="32" height="32"><span>${st.name}</span></button>`).join('');
  $('zs-rule1').textContent = ST_RULE[ui.station];
}
function renderDishes() {
  const st = curStation();
  $('zs-dishes').innerHTML = st.dishes.map((d, i) => `<button type="button" data-i="${i}" aria-pressed="${i === ui.slot}"><img class="pix" src="${d.icon}" alt="" width="32" height="32"><span class="dn">${d.name}</span>${starsHTML(d.slotStar)}</button>`).join('');
  renderDishNote();
}
function renderDishNote() {
  const d = curDish(), el = $('zs-dishnote');
  const bits = [], warn = [];
  bits.push(`厨师效果：${d.eff}，${d.val}（${d.cat}类）。`);
  if (d.star !== d.slotStar) bits.push(`示例菜实为 ★${d.star}，在这里代 ★${d.slotStar}（本台缺这一档的菜）。`);
  if (ui.station === STATION.OVEN) bits.push(`要翻 ${d.flips || 1} 面。`);
  if (d.skin === 'drink') bits.push('冷饮：摇杯皮，固定 6 拍。');
  if (ui.station === STATION.PREP && d.skin !== 'drink') bits.push(`这道菜 ${d.beats || 5} 拍（按不同食材数 + 1）。`);
  const need = combatNeed(d);
  if (need && ui.level < need) warn.push(`战斗菜：厨师 ${need} 级起才能掌勺（正式服这一级只能做家常；这里照样能练）。`);
  const rec = REC_LEVEL[d.slotStar - 1];
  if (ui.level < rec) warn.push(`推荐厨师 ${rec} 级。这道菜对你来说很难，糊锅会做成家常菜。`);
  else bits.push(`推荐厨师 ${rec} 级。`);
  el.className = warn.length ? 'dishnote warn' : 'dishnote';
  el.textContent = warn.concat(bits).join(' ');
}
function renderMotionSelect() {
  const auto = motionByName(ui.station, curDish());
  const sel = $('zs-motion');
  sel.innerHTML = `<option value="auto">按菜名：${MOTION_NAMES[auto.m]}</option>` + MOTION_NAMES.map((n, i) => `<option value="${i}">${n}（${MOTION_DESC[i]}）</option>`).join('');
  sel.value = ui.motion;
  sel.title = `按菜名：${auto.why}`;
  renderMotionWhy();
}
function renderMotionWhy() {
  const auto = motionByName(ui.station, curDish());
  $('zs-motion-why').textContent = ui.motion === 'auto'
    ? `性子按菜名定：${auto.why} → ${MOTION_NAMES[auto.m]}（${MOTION_DESC[auto.m]}）。`
    : `性子手动改成了「${MOTION_NAMES[+ui.motion]}」（${MOTION_DESC[+ui.motion]}）；按菜名本该是「${MOTION_NAMES[auto.m]}」。`;
}
function renderMastery() {
  $('zs-mastery').innerHTML = MASTERY_NAMES.map((n, i) => `<option value="${i}">${i} ${n}${i ? `（起点 ${25 + 2 * i}%）` : ''}</option>`).join('');
  $('zs-mastery').value = String(ui.mastery);
}
function renderLevelHint() {
  const p = game ? game.s.p : null;
  $('zs-level-out').textContent = String(ui.level);
  if (!p) return;
  const perks = [];
  if (ui.level >= 3) perks.push('稳手');
  if (ui.level >= 6) perks.push('耐火');
  if (ui.level >= 9) perks.push('眼尖');
  const extra = p.station === STATION.FRYER ? ` · 压火后等 ${sec(p.oilCd)} 秒` : p.station === STATION.OVEN ? ` · 「正好」窗口 ${sec(p.flipGood + 1)} 秒` : p.station === STATION.PREP ? ` · 「可以」差 ±${sec(p.goodW)} 秒` : '';
  $('zs-level-hint').textContent = `火候条 ${(p.H / 10000).toFixed(1)}%${ui.level <= 3 ? '（1–3 级保底 18%）' : ''} · 有瓶 ${pc(p.bottlePm)}（其中金瓶 ${pc(p.goldPm)}）${perks.length ? ' · ' + perks.join(' / ') : ''}${extra}`;
}
function updatePad() {
  const g = game, st = g.s.p.station, btn = $('zs-act');
  btn.hidden = st === STATION.POT;
  $('zs-act-name').textContent = ST_ACT[st] || '动作';
  // 一局打完：「按住加火」变成「再来一份」（手机上不用往下翻）
  const ended = g.phase === 'ended';
  $('zs-hold').classList.toggle('again', ended);
  $('zs-hold-name').textContent = ended ? '再来一份' : '按住加火';
  $('zs-hold-sub').textContent = ended ? 'Enter / 点这里' : '空格 / 左键';
}
function settingsChanged() { renderLevelHint(); newRound(); }

$('zs-stations').addEventListener('click', (e) => {
  const b = e.target.closest('button[data-st]');
  if (!b) return;
  ui.station = +b.dataset.st;
  if (ui.slot > 6) ui.slot = 0;
  ui.motion = 'auto';
  renderStations(); renderDishes(); renderMotionSelect(); newRound();
});
$('zs-dishes').addEventListener('click', (e) => {
  const b = e.target.closest('button[data-i]');
  if (!b) return;
  ui.slot = +b.dataset.i; ui.motion = 'auto';
  for (const x of $('zs-dishes').querySelectorAll('button')) x.setAttribute('aria-pressed', String(x === b));
  renderDishNote(); renderMotionSelect(); newRound();
});
$('zs-level').addEventListener('input', () => {
  ui.level = +$('zs-level').value;
  ui.ghost = ui.level <= 3; $('zs-ghost').checked = ui.ghost;
  renderDishNote(); settingsChanged();
});
$('zs-motion').addEventListener('change', () => { ui.motion = $('zs-motion').value; renderMotionWhy(); newRound(); });
$('zs-mastery').addEventListener('change', () => { ui.mastery = +$('zs-mastery').value; newRound(); });
$('zs-seedfix').addEventListener('change', () => { ui.seedFixed = $('zs-seedfix').checked; });
$('zs-seed').addEventListener('change', () => { ui.seedFixed = true; $('zs-seedfix').checked = true; newRound(); });
$('zs-ghost').addEventListener('change', () => { ui.ghost = $('zs-ghost').checked; });
$('zs-restart').addEventListener('click', () => { newRound(); cv.focus({ preventScroll: true }); });

// ---------------------------------------------------------------- 难度一览（sim/balance.md · 熟练玩家 · 四台合并）
const LEVELS = [1, 5, 10];
function renderDiff() {
  const B = DATA.balance;
  $('zs-diff-note').textContent = `熟练玩家（反应约 0.2 秒的机器人）在各星级的品质分布：四台合并，每格 ${B.perCell.toLocaleString('en-US')} 局，手熟 0、单份、各台默认性子；格内第一行是超凡及以上的占比，第二行是其中闪耀的占比（没算灵感冷却和每日上限）。机器人不是真人，只用来看梯度。来源 sim/balance.md（${B.when}）。`;
  const order = [['burnt', '糊锅'], ['low', '低'], ['mid', '中'], ['high', '高'], ['ex', '超凡'], ['rad', '闪耀']];
  let html = '<thead><tr><th scope="col">★</th>' + LEVELS.map((l) => `<th scope="col" data-l="${l}">厨师 ${l} 级</th>`).join('') + '</tr></thead><tbody>';
  for (let star = 1; star <= 7; star++) {
    html += `<tr data-star="${star}"><th scope="row">★${star}</th>`;
    for (const l of LEVELS) {
      const c = B.rows[star][l];
      const segsH = order.filter(([k]) => c[k].v > 0).map(([k]) => `<i class="c-${k}" style="width:${c[k].v.toFixed(2)}%"></i>`).join('');
      const full = order.map(([k, n]) => `${n} ${c[k].s}%`).join(' · ') + ` · 均时 ${c.time} · Perfect ${c.perfect}%`;
      html += `<td data-l="${l}" title="★${star} · 厨师 ${l} 级：${full}"><div class="dbar">${segsH}</div>超凡+ ${c.exPlus}%<span class="rad">闪耀 ${c.rad.s}%</span><span class="sr">：${full}</span></td>`;
    }
    html += '</tr>';
  }
  $('zs-diff').innerHTML = html + '</tbody>';
}
function markDiff() {
  const star = curDish().slotStar;
  const near = ui.level <= 2 ? 1 : ui.level <= 7 ? 5 : 10;
  for (const el of $('zs-diff').querySelectorAll('.cur')) el.classList.remove('cur');
  const row = $('zs-diff').querySelector(`tr[data-star="${star}"]`);
  if (row) { row.classList.add('cur'); const td = row.querySelector(`td[data-l="${near}"]`); if (td) td.classList.add('cur'); }
  const th = $('zs-diff').querySelector(`thead th[data-l="${near}"]`);
  if (th) th.classList.add('cur');
}

// ---------------------------------------------------------------- 给截图脚本用的钩子（只读状态 / 暂停时钟 / 固定输入）
window.__zs = {
  state() {
    const g = game, s = g.s;
    return {
      phase: g.phase, tick: s.tick, pt: s.pt, yb: s.yb, vb: s.vb, yt: s.yt, vt: s.vt, H: s.p.H, P: s.P, inSeg: s.inSeg, segIdx: s.segIdx,
      segT: s.segT, bi: s.bi, beats: s.inSeg ? s.segBeats[s.segIdx].slice() : null, prompt: s.prompt, flipGood: s.p.flipGood, T: s.T, cd: s.cd,
      station: s.p.station, end: s.end, bState: s.bState, CAP: s.p.CAP, pin: s.pin, gold: g.gold,
      nextMark: s.p.station === STATION.OVEN && s.flipIdx < s.p.F ? SIM.markAt(s.p, s.flipIdx + 1, s.p.F) : -1, g0: SIM.fullGain(s.p),
    };
  },
  select(o) {
    if (o.station !== undefined) ui.station = o.station;
    if (o.slotStar !== undefined) ui.slot = o.slotStar - 1;
    if (o.level !== undefined) { ui.level = o.level; $('zs-level').value = String(o.level); ui.ghost = o.level <= 3; $('zs-ghost').checked = ui.ghost; }
    if (o.mastery !== undefined) { ui.mastery = o.mastery; $('zs-mastery').value = String(o.mastery); }
    ui.motion = o.motion !== undefined ? String(o.motion) : 'auto';
    renderStations(); renderDishes(); renderMotionSelect();
    newRound(o.seed, false, { goldPm: o.goldPm, gold: o.gold });
    return true;
  },
  // 固定输入：按 tick 顺序喂给内核（走与真人相同的 tick 推进与结算路径），用来截确定的画面（如闪耀）
  feed(inputs) { game.feed = inputs.slice(); startCountdown(); return game.feed.length; },
  freeze(on) { frozen = !!on; return frozen; },
  result() { return game.res; },
};

// ---------------------------------------------------------------- 启动
function watchDpr() {
  // 窗口拖到另一块像素密度不同的屏幕时，宽度没变也要重算整数倍
  if (!window.matchMedia) return;
  const mq = window.matchMedia(`(resolution: ${window.devicePixelRatio || 1}dppx)`);
  const on = () => { layoutCanvas(); watchDpr(); };
  if (mq.addEventListener) mq.addEventListener('change', on, { once: true });
  else if (mq.addListener) mq.addListener(on);
}
function boot() {
  $('zs-level').value = String(ui.level);
  ui.ghost = ui.level <= 3; $('zs-ghost').checked = ui.ghost;
  renderStations(); renderDishes(); renderMotionSelect(); renderMastery(); renderDiff();
  $('zs-foot').innerHTML = `规则内核 <code>sim/sim.js</code>（simVersion ${SIM.SIM_VERSION}，原样内联，20 tick/秒）· 素材 <code>art/manifest.json</code>（${DATA.meta.artHash}）· 平衡数据 <code>sim/balance.md</code> · 厨师效果 <code>dish-effects.tsv</code> · 本页是原型，相当于练习模式：不出菜、不给经验；金瓶由本页代替服务器私下抽。`;
  newRound();
  if (window.ResizeObserver) new ResizeObserver(() => layoutCanvas()).observe(stageEl);
  window.addEventListener('resize', layoutCanvas);
  watchDpr();
  const fontsReady = document.fonts && document.fonts.ready ? Promise.race([document.fonts.ready, new Promise((r) => setTimeout(r, 2500))]) : Promise.resolve();
  Promise.all([loadImages(), fontsReady]).then(() => {
    layoutCanvas();
    lastNow = performance.now();
    draw(lastNow);
    requestAnimationFrame(frame);
    window.__ready = true;
  }, (err) => { console.error(err); window.__ready = true; });
}
window.__ready = false;
boot();
})();
