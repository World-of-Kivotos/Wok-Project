// 截图用的「玩家」：只通过真实的键盘事件（空格按住/松开、F 轻点）操作页面，和真人走同一条输入路径。
// 读状态用页面的 window.__zs.state()（只读），截中途图时用 __zs.freeze() 暂停时钟。
// 用法（shot.mjs 的 plan 里）：{ "jsFile": "bot.js", "js": "__bot.run({station:0, slotStar:3, level:5, seed:1, pause:'tick:80'})", "value": true }
//   pause：'tick:N'（第 N tick 暂停）、'prompt'（烤炉「翻面！」出现后暂停）、'premark'（烤炉离三角不到 4 tick、提示还没出）、
//          'seg'（备餐台摆料段第 3 拍前暂停）、'tail'（摆料段最后一拍判完、「接住！」那 0.6 秒）、'T:N'（炸锅油温 ≥ N）、'left:N'（离时限 ≤ N tick）；不给就打到结束。
//   noAct:true = 从不按动作键（截炸锅爆油预警用）。
//   之后 { "js": "__bot.resume()", "value": true } 继续打到结束，返回结算摘要。
//   sloppy:true（可配 lag:N）= 反应慢 N tick 的手：用来截带负面的结算卡。
(() => {
  if (window.__bot) return 'bot ready';
  const fire = (type, code) => document.dispatchEvent(new KeyboardEvent(type, { code, key: code === 'Space' ? ' ' : 'f', bubbles: true, cancelable: true }));
  let holding = false, ctl = null;
  const setHold = (on) => { if (on !== holding) { holding = on; fire(on ? 'keydown' : 'keyup', 'Space'); } };
  const tapF = () => { fire('keydown', 'KeyF'); fire('keyup', 'KeyF'); };
  const summary = () => {
    const r = window.__zs.result();
    return r && { end: r.end, quality: r.quality, score: r.score, accuracy: r.accuracy, pen: r.pen, perfect: r.perfect, bottle: r.bottle, negatives: r.negatives, ticks: r.ticks, mechanical: r.mechanical };
  };
  function pauseHit(o, st) {
    if (!o.pause || o.paused) return false;
    if (o.pause.startsWith('tick:')) return st.tick >= +o.pause.slice(5);
    if (o.pause === 'prompt') return st.prompt >= 0 && st.pt - st.prompt >= 1;
    if (o.pause === 'seg') return !!st.inSeg && st.bi >= 2;
    if (o.pause === 'tail') return !!st.inSeg && st.beats && st.bi >= st.beats.length;
    if (o.pause === 'premark') return st.prompt < 0 && st.nextMark >= 0 && st.nextMark - st.P <= 4 * st.g0 && st.nextMark - st.P > 0;
    if (o.pause.startsWith('T:')) return st.T >= +o.pause.slice(2);
    if (o.pause.startsWith('left:')) return !st.inSeg && st.CAP - st.pt <= +o.pause.slice(5);
    return false;
  }
  function loop() {
    const c = ctl;
    if (!c) return;
    const st = window.__zs.state();
    if (c.waitingResume) { requestAnimationFrame(loop); return; }
    if (st.phase === 'ready') {
      if (!c.started) { c.started = true; setHold(true); setTimeout(() => setHold(false), 80); }
    } else if (st.phase === 'running') {
      if (pauseHit(c.o, st)) {
        c.o.paused = true; c.waitingResume = true;
        window.__zs.freeze(true);
        const res = c.onPause; c.onPause = null;
        if (res) res({ paused: true, tick: st.tick, P: st.P, inSeg: st.inSeg });
        requestAnimationFrame(loop);
        return;
      }
      // sloppy：看到的是 lag tick 之前的食材（反应慢），翻面偏晚、节拍偏一点还会漏、油温压得晚
      const sl = !!c.o.sloppy, lag = sl ? (c.o.lag || 6) : 0;
      if (!c.hist.length || c.hist[c.hist.length - 1].tick !== st.tick) c.hist.push({ tick: st.tick, yt: st.yt, vt: st.vt });
      while (c.hist.length > 1 && c.hist[1].tick <= st.tick - lag) c.hist.shift();
      const seen = c.hist[0];
      if (st.inSeg) {
        const Tm = st.beats, off = sl ? 2 : 0;
        if (Tm && st.bi < Tm.length && st.segT >= Tm[st.bi] + off && st.tick !== c.lastAct) {
          c.lastAct = st.tick; c.beatN = (c.beatN || 0) + 1;
          if (!(sl && c.beatN % 3 === 0)) tapF();
        }
      } else {
        // 带提前量的开关控制：预测 4 tick 后食材与火候条中心的相对位置
        const center = st.yb + st.H / 2;
        const err = (seen.yt + seen.vt * 3 * 4) - (center + st.vb * 4);
        setHold(err > 0);
        if (st.station === 1 && !c.o.noAct && st.T > (sl ? 820 : 700) && st.cd === 0 && st.tick !== c.lastAct) { c.lastAct = st.tick; tapF(); }
        if (st.station === 2 && st.prompt >= 0 && st.pt - st.prompt >= (sl ? st.flipGood + 4 : 2) && st.tick !== c.lastAct) { c.lastAct = st.tick; tapF(); }
      }
    } else if (st.phase === 'ended') {
      setHold(false);
      ctl = null;
      const done = c.onPause || c.onEnd;
      if (done) done(summary());
      return;
    }
    requestAnimationFrame(loop);
  }
  window.__bot = {
    run(o) {
      setHold(false);
      window.__zs.freeze(false);
      window.__zs.select(o);
      return new Promise((resolve) => {
        ctl = { o: Object.assign({}, o), started: false, lastAct: -1, onPause: resolve, onEnd: null, waitingResume: false, hist: [] };
        requestAnimationFrame(loop);
      });
    },
    resume() {
      return new Promise((resolve) => {
        if (!ctl) { resolve(summary()); return; }
        ctl.onEnd = resolve; ctl.waitingResume = false;
        window.__zs.freeze(false);
      });
    },
  };
  return 'bot ready';
})()
