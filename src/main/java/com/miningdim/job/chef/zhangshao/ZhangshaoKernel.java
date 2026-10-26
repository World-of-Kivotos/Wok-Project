package com.miningdim.job.chef.zhangshao;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 掌勺小游戏确定性内核（simVersion 2）。与 minigame/sim/sim.js 逐位一致，由 golden.json 对拍。
 * <p>
 * 约定：全部 int（32 位、溢出回绕），没有 long / float / double；Java 的 {@code /} 本来就向零截断，等于 JS 的 tdiv；
 * {@code >>>} 只出现在随机数、哈希、a8 位操作里；取模只对非负数做。执行顺序严格按 spec §5。
 * 改任何规则都要升 {@link #SIM_VERSION} 并重新生成 golden.json。
 */
public final class ZhangshaoKernel {
    private ZhangshaoKernel() {}

    public static final int SIM_VERSION = 2;
    public static final int NONE = -1;
    // 台 / 性子 / 结局 / 品质 / 瓶 / 负面
    public static final int POT = 0, FRYER = 1, OVEN = 2, PREP = 3;
    public static final int MIX = 0, DART = 1, STEADY = 2, SINK = 3, FLOAT = 4;
    public static final int RUNNING = 0, DONE = 1, BURNT = 2, TIMEOUT = 3, ABANDON = 4;
    public static final int Q_HOMESTYLE = -1, Q_LOW = 0, Q_MEDIUM = 1, Q_HIGH = 2, Q_EXTRAORDINARY = 3, Q_RADIANT = 4;
    public static final int B_WAITING = 0, B_PRESENT = 1, B_CAUGHT = 2, B_NONE = 3;
    /** 内核状态 bKind 只用 KIND_NONE / KIND_NORMAL（=有瓶，种类内核不知道）；结算结果 bottleKind 才会是 KIND_GOLD（由 Ext.gold 给出）。 */
    public static final int KIND_NONE = 0, KIND_NORMAL = 1, KIND_GOLD = 2;
    public static final int NEG_UNDERDONE = 0, NEG_SCORCHED = 1, NEG_NAUSEA = 2; // SCORCHED 在备餐台显示为「多盐」
    private static final int SIDE_IN = 0, SIDE_UNDER = 1, SIDE_OVER = 2;
    private static final int K_MAX = 1_000_000;
    private static final int JIT_DRAWS = 8;
    public static final int SALT_T = 0x0F00D5, SALT_B = 0x0B0771E, SALT_S = 0x057A7;

    // =====================================================================
    // 随机数与哈希
    // =====================================================================
    /** MurmurHash3 fmix32。int 乘法自动回绕，等于 JS 的 Math.imul。 */
    public static int fmix32(int h) {
        h ^= h >>> 16;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        h *= 0xC2B2AE35;
        h ^= h >>> 16;
        return h;
    }

    /** Mulberry32：状态是一个 int。 */
    public static final class Mulberry32 {
        public int a;
        public Mulberry32(int state) { a = state; }
        public Mulberry32(Mulberry32 o) { a = o.a; }
        public int next() {
            a += 0x6D2B79F5;
            int t = (a ^ (a >>> 15)) * (1 | a);
            t = (t + (t ^ (t >>> 7)) * (61 | t)) ^ t;
            return t ^ (t >>> 14);
        }
        /** n > 0；next()>>>1 非负，所以 % 安全。 */
        public int rnd(int n) { return (next() >>> 1) % n; }
        /** 含两端。 */
        public int range(int lo, int hi) { return lo + rnd(hi - lo + 1); }
    }

    /** 逐字 FNV-1a 32。 */
    public static int fnv1a(int[] v) {
        int h = 0x811C9DC5;
        for (int x : v) { h ^= x; h *= 0x01000193; }
        return h;
    }

    public static String hex32(int h) { return String.format("%08x", h); }

    // =====================================================================
    // 参数块（spec §18.4）：A 段 #0–31 + B 段 #32–143
    // =====================================================================
    public static final int PARAM_COUNT = 144;

    public static final class Params {
        // A 段
        public final int simVersion, station, star, level, motion, D, H, P0, GAIN, LOSS, PACE, MAST, N, BPACE, CAP,
            bottlePm, goldPm, drainPm, BB, F, flipGood, oilCd, beats, segs, beatGap, jitterOn, goodW,
            bFill, bAppearMin, bAppearMax, bOffMin, bOffMax;
        // B 段
        public final int track, accel, inBarNum, inBarDen, bounceTopNum, bounceDen, pinTicks,
            fishMin, fishMax, sinkLine, floatLine, fishSubsteps, newDestDiv, steadyMul, pctRandMin, pctRandMax,
            approachDivMin, approachDivMax, approachRelax, arriveEps, hopChanceDiv, hopMin, hopMax,
            dartChanceDiv, dartMin, dartUnit, driftStep, driftMax,
            progressMax, flipHoldCap, perfectGraceTicks,
            scoreBonus,
            heatStart, heatUp, heatDown, heatMax, heatMulBase, heatMulSpan, penDiv, underDiv,
            oilStart, oilRiseIn, oilRiseOut, oilRiseStarDiv, oilRiseProgDiv, oilHotRiseDiv, oilDrop, oilCold, oilHot, oilBurst,
            oilAfterBurst, coldMulPm, hotMulPm, burstProgressLoss, burstKick, penHotDiv, penColdDiv, penBurst,
            negHotDiv, negColdDiv, negBurst, cleanTicks,
            flipLate, flipAuto, flipEarlyGoodTicks, flipEarlyMildTicks, flipEarlyZoneTicks, burnMulPm, flipKickBase, flipKickPerStar,
            penLate, penEarlyMild, penEarly, penBurntBase, penBurntCap, penAuto,
            negEarlyMildUnder, negEarlyUnder, negLateOver, negBurntBase, negBurntPerTick, negBurntCap, negAutoOver,
            beatJitter0, beatJitter1, beatJitter2, beatGapMin, beatLead, beatTail, beatPerfectW,
            penOk, penMiss, penMash, negMissPm, negMash,
            tierExtraordinary, tierHigh, tierMedium, timeoutCap, batchCap, slowCap, mechanicalCap,
            thresholdLow, thresholdMedium, thresholdHigh, maxLow, maxMedium, maxHigh,
            mechanicalMinRuns, mechanicalSamePct, suspectCapRadiant, suspectCapHigh;
        public final int[] beatJitter;
        private final int[] block;

        public Params(int[] b) {
            if (b.length != PARAM_COUNT) throw new IllegalArgumentException("参数块长度应为 " + PARAM_COUNT + "，实际 " + b.length);
            if (b[0] != SIM_VERSION) throw new IllegalArgumentException("不认识的 simVersion " + b[0]);
            block = b.clone();
            int i = 0;
            simVersion = b[i++]; station = b[i++]; star = b[i++]; level = b[i++]; motion = b[i++]; D = b[i++]; H = b[i++];
            P0 = b[i++]; GAIN = b[i++]; LOSS = b[i++]; PACE = b[i++]; MAST = b[i++]; N = b[i++]; BPACE = b[i++]; CAP = b[i++];
            bottlePm = b[i++]; goldPm = b[i++]; drainPm = b[i++]; BB = b[i++]; F = b[i++]; flipGood = b[i++]; oilCd = b[i++];
            beats = b[i++]; segs = b[i++]; beatGap = b[i++]; jitterOn = b[i++]; goodW = b[i++];
            bFill = b[i++]; bAppearMin = b[i++]; bAppearMax = b[i++]; bOffMin = b[i++]; bOffMax = b[i++];
            track = b[i++]; accel = b[i++]; inBarNum = b[i++]; inBarDen = b[i++]; bounceTopNum = b[i++]; bounceDen = b[i++]; pinTicks = b[i++];
            fishMin = b[i++]; fishMax = b[i++]; sinkLine = b[i++]; floatLine = b[i++]; fishSubsteps = b[i++]; newDestDiv = b[i++];
            steadyMul = b[i++]; pctRandMin = b[i++]; pctRandMax = b[i++]; approachDivMin = b[i++]; approachDivMax = b[i++];
            approachRelax = b[i++]; arriveEps = b[i++]; hopChanceDiv = b[i++]; hopMin = b[i++]; hopMax = b[i++];
            dartChanceDiv = b[i++]; dartMin = b[i++]; dartUnit = b[i++]; driftStep = b[i++]; driftMax = b[i++];
            progressMax = b[i++]; flipHoldCap = b[i++]; perfectGraceTicks = b[i++];
            scoreBonus = b[i++];
            heatStart = b[i++]; heatUp = b[i++]; heatDown = b[i++]; heatMax = b[i++]; heatMulBase = b[i++]; heatMulSpan = b[i++];
            penDiv = b[i++]; underDiv = b[i++];
            oilStart = b[i++]; oilRiseIn = b[i++]; oilRiseOut = b[i++]; oilRiseStarDiv = b[i++]; oilRiseProgDiv = b[i++];
            oilHotRiseDiv = b[i++]; oilDrop = b[i++]; oilCold = b[i++]; oilHot = b[i++]; oilBurst = b[i++]; oilAfterBurst = b[i++]; coldMulPm = b[i++];
            hotMulPm = b[i++]; burstProgressLoss = b[i++]; burstKick = b[i++]; penHotDiv = b[i++]; penColdDiv = b[i++];
            penBurst = b[i++]; negHotDiv = b[i++]; negColdDiv = b[i++]; negBurst = b[i++]; cleanTicks = b[i++];
            flipLate = b[i++]; flipAuto = b[i++]; flipEarlyGoodTicks = b[i++]; flipEarlyMildTicks = b[i++]; flipEarlyZoneTicks = b[i++];
            burnMulPm = b[i++]; flipKickBase = b[i++];
            flipKickPerStar = b[i++]; penLate = b[i++]; penEarlyMild = b[i++]; penEarly = b[i++]; penBurntBase = b[i++]; penBurntCap = b[i++];
            penAuto = b[i++]; negEarlyMildUnder = b[i++]; negEarlyUnder = b[i++]; negLateOver = b[i++]; negBurntBase = b[i++]; negBurntPerTick = b[i++];
            negBurntCap = b[i++]; negAutoOver = b[i++];
            beatJitter0 = b[i++]; beatJitter1 = b[i++]; beatJitter2 = b[i++]; beatGapMin = b[i++]; beatLead = b[i++];
            beatTail = b[i++]; beatPerfectW = b[i++]; penOk = b[i++]; penMiss = b[i++]; penMash = b[i++]; negMissPm = b[i++];
            negMash = b[i++];
            tierExtraordinary = b[i++]; tierHigh = b[i++]; tierMedium = b[i++]; timeoutCap = b[i++]; batchCap = b[i++];
            slowCap = b[i++]; mechanicalCap = b[i++];
            thresholdLow = b[i++]; thresholdMedium = b[i++]; thresholdHigh = b[i++]; maxLow = b[i++]; maxMedium = b[i++];
            maxHigh = b[i++];
            mechanicalMinRuns = b[i++]; mechanicalSamePct = b[i++]; suspectCapRadiant = b[i++]; suspectCapHigh = b[i++];
            if (i != PARAM_COUNT) throw new AssertionError("字段数不符 " + i);
            beatJitter = new int[] {beatJitter0, beatJitter1, beatJitter2};
        }

        public int[] block() { return block.clone(); }
        public int hash() { return fnv1a(block); }
    }

    // =====================================================================
    // 状态
    // =====================================================================
    public static final class State {
        public final Params p;
        public final int seed;
        // 开局后只读
        public int bAt, bOff, bDir, kickBits;
        public int[] jit;
        public int[][] segBeats;
        // 状态（哈希顺序见 hash()）
        public int tick, pt, end, inSeg, segIdx, segT, bi, yb, vb, inPrev, pin, yt, vt, tgt, ad;
        public int P, S, I, U, O, outRun, perfect, bKind, bState, bPos, K, h, hSum, T, cd, hot;
        public int cold, bursts, flipIdx, prompt, burn, flipPen, flipUnder, flipOver, flipAllGood;
        public int perfB, okB, miss, mash, kickN;
        public final Mulberry32 rT, rB, rS;
        // 不进哈希的展示计数（结算屏）
        public int nGood, nLate, nEarly, nBurnt, nAuto;

        State(Params p, int seed) {
            this.p = p;
            this.seed = seed;
            rT = new Mulberry32(fmix32(seed ^ SALT_T));
            rB = new Mulberry32(fmix32(seed ^ SALT_B));
            rS = new Mulberry32(fmix32(seed ^ SALT_S));
        }

        /** 深拷贝（jit / segBeats 只读，共享）。 */
        public State copy() {
            State c = new State(p, seed, rT, rB, rS);
            c.bAt = bAt; c.bOff = bOff; c.bDir = bDir; c.kickBits = kickBits; c.jit = jit; c.segBeats = segBeats;
            c.tick = tick; c.pt = pt; c.end = end; c.inSeg = inSeg; c.segIdx = segIdx; c.segT = segT; c.bi = bi;
            c.yb = yb; c.vb = vb; c.inPrev = inPrev; c.pin = pin; c.yt = yt; c.vt = vt; c.tgt = tgt; c.ad = ad;
            c.P = P; c.S = S; c.I = I; c.U = U; c.O = O; c.outRun = outRun; c.perfect = perfect; c.bKind = bKind;
            c.bState = bState; c.bPos = bPos; c.K = K; c.h = h; c.hSum = hSum; c.T = T; c.cd = cd; c.hot = hot;
            c.cold = cold; c.bursts = bursts; c.flipIdx = flipIdx; c.prompt = prompt; c.burn = burn; c.flipPen = flipPen;
            c.flipUnder = flipUnder; c.flipOver = flipOver; c.flipAllGood = flipAllGood;
            c.perfB = perfB; c.okB = okB; c.miss = miss; c.mash = mash; c.kickN = kickN;
            c.nGood = nGood; c.nLate = nLate; c.nEarly = nEarly; c.nBurnt = nBurnt; c.nAuto = nAuto;
            return c;
        }

        private State(Params p, int seed, Mulberry32 t, Mulberry32 b, Mulberry32 s) {
            this.p = p; this.seed = seed;
            rT = new Mulberry32(t); rB = new Mulberry32(b); rS = new Mulberry32(s);
        }
    }

    // =====================================================================
    // 开局（spec §4）
    // =====================================================================
    public static State init(Params p, int seed) {
        State s = new State(p, seed);
        s.tick = 0; s.pt = 0; s.end = RUNNING; s.inSeg = 0; s.segIdx = 0; s.segT = 0; s.bi = 0;
        s.yb = (p.track - p.H) / 2; s.vb = 0; s.inPrev = 1; s.pin = 0;
        s.yt = p.track / 2; s.vt = 0; s.tgt = NONE; s.ad = 0;
        s.P = p.P0; s.S = 0; s.I = 0; s.U = 0; s.O = 0; s.outRun = 0; s.perfect = 1;
        s.bKind = KIND_NONE; s.bState = B_NONE; s.bPos = 0; s.K = 0;
        s.h = p.heatStart; s.hSum = 0;
        s.T = p.oilStart; s.cd = 0; s.hot = 0; s.cold = 0; s.bursts = 0;
        s.flipIdx = 0; s.prompt = -1; s.burn = 0; s.flipPen = 0; s.flipUnder = 0; s.flipOver = 0; s.flipAllGood = 1;
        s.perfB = 0; s.okB = 0; s.miss = 0; s.mash = 0; s.kickN = 0;
        // rT：开局第一跳
        s.tgt = s.rT.range(p.fishMin, p.fishMax - 1);
        // rB：调料瓶，固定 4 个（有没有瓶、出现时刻、离食材多远、方向；种类不在这里抽，由服务端私下抽）
        int bottleRoll = s.rB.rnd(1000);
        s.bAt = s.rB.range(p.bAppearMin, p.bAppearMax);
        s.bOff = s.rB.range(p.bOffMin, p.bOffMax);
        s.bDir = s.rB.rnd(2);
        if (bottleRoll < p.bottlePm) s.bKind = KIND_NORMAL;
        s.bState = s.bKind == KIND_NONE ? B_NONE : B_WAITING;
        // rS：8 个节拍抖动 + 溅射方向掩码（任何台子都抽满）
        s.jit = new int[JIT_DRAWS];
        for (int k = 0; k < JIT_DRAWS; k++) s.jit[k] = p.beatJitter[s.rS.rnd(3)];
        s.kickBits = s.rS.next();
        // 备餐台节拍表
        if (p.station == PREP) {
            int n = p.beats;
            int first = (n + 1) / 2;
            int[] sizes = p.segs == 2 ? new int[] {first, n - first} : new int[] {n};
            s.segBeats = new int[sizes.length][];
            int k = 0;
            for (int si = 0; si < sizes.length; si++) {
                int[] times = new int[sizes[si]];
                int at = p.beatLead;
                for (int i = 0; i < sizes[si]; i++) {
                    if (i > 0) at += Math.max(p.beatGapMin, p.beatGap + (p.jitterOn != 0 ? s.jit[k] : 0));
                    times[i] = at;
                    k++;
                }
                s.segBeats[si] = times;
            }
        } else {
            s.segBeats = new int[0][];
        }
        return s;
    }

    // =====================================================================
    // 每 tick（spec §5）
    // =====================================================================
    /** 翻面点 / 摆料点。 */
    public static int markAt(Params p, int k, int n) {
        return p.P0 + (p.progressMax - p.P0) * k / (n + 1);
    }

    /** 满速涨速（框住、本台倍率 1000 时每 tick 涨多少熟度），与 step ⑦ 同一条截断链。 */
    public static int fullGain(Params p) {
        int g = p.GAIN;
        g = g * p.PACE / 1000;
        g = g * p.MAST / 1000;
        g = g * p.BPACE / 1000;
        return g;
    }

    private static void kick(State s, int amt) {
        int up = (s.kickBits >>> (s.kickN & 31)) & 1;
        s.kickN++;
        s.vt = up != 0 ? s.vt + amt : s.vt - amt;
    }

    private static void fishSub(State s, Params p) {
        Mulberry32 r = s.rT;
        // 固定抽 8 个数，不管后面用不用
        int a1 = r.rnd(p.newDestDiv);
        int a2 = r.range(p.pctRandMin, p.pctRandMax);
        int a3 = r.range(p.fishMin, p.fishMax - 1);
        int a4 = r.range(p.approachDivMin, p.approachDivMax);
        int a5 = r.rnd(p.hopChanceDiv);
        int a6 = r.range(p.hopMin, p.hopMax);
        int a7 = r.rnd(p.dartChanceDiv);
        int a8 = r.next();

        final int D = p.D, M = p.motion;
        int yt = s.yt, vt = s.vt, tgt = s.tgt, ad = s.ad;
        // 1 换目的地
        int mul = M == STEADY ? p.steadyMul : 1;
        if ((M != STEADY || tgt == NONE) && a1 < D * mul) {
            int pct = Math.min(99, D + a2);
            tgt = yt + (a3 - yt) * pct / 100;
        }
        // 2 沉 / 浮漂移
        if (M == SINK) ad = yt > p.sinkLine ? Math.max(ad - p.driftStep, -p.driftMax) : 0;
        else if (M == FLOAT) ad = yt < p.floatLine ? Math.min(ad + p.driftStep, p.driftMax) : 0;
        // 3 追目的地 / 小跳 / 滑行
        if (tgt != NONE && Math.abs(yt - tgt) > p.arriveEps) {
            int div = a4 + 100 - Math.min(100, D);
            int acc = (tgt - yt) / div;
            vt = vt + (acc - vt) / p.approachRelax;
        } else if (M != STEADY && a5 < D) {
            tgt = (a8 & 1) != 0 ? yt + a6 : yt - a6;
        } else {
            tgt = NONE;
        }
        // 4 窜：急窜
        if (M == DART && a7 < D) {
            int dmax = (101 + 2 * D) * p.dartUnit;
            int size = p.dartMin + ((a8 >>> 2) % (dmax - p.dartMin));
            tgt = ((a8 >>> 1) & 1) != 0 ? yt + size : yt - size;
        }
        // 5 夹取与移动（tgt 恰好算出 −1 时与 NONE 同值：按规格原样处理，不要改成 boolean）
        if (tgt != NONE) tgt = Math.max(p.fishMin, Math.min(p.fishMax, tgt));
        yt = yt + vt + ad;
        if (yt < p.fishMin) { yt = p.fishMin; vt = 0; }
        else if (yt > p.fishMax) { yt = p.fishMax; vt = 0; }
        s.yt = yt; s.vt = vt; s.tgt = tgt; s.ad = ad;
    }

    private static void prepSegmentTick(State s, Params p, int act) {
        int[] tm = s.segBeats[s.segIdx];
        int c = tm.length, gw = p.goodW, t = s.segT;
        if (s.bi < c && t > tm[s.bi] + gw) { s.miss++; s.bi++; }            // 漏拍
        if (act != 0 && s.bi < c) {
            int d = Math.abs(t - tm[s.bi]);
            if (d <= p.beatPerfectW) { s.perfB++; s.bi++; }                 // 正好
            else if (d <= gw) { s.okB++; s.bi++; }                           // 可以
            else { s.mash++; }                                               // 乱按
        }
        s.segT++;
        if (s.segT >= tm[c - 1] + gw + p.beatTail + 1) { s.inSeg = 0; s.segIdx++; }
    }

    private static void ovenFlipInput(State s, Params p) {
        int kickAmt = p.flipKickBase + p.flipKickPerStar * p.star;
        if (s.prompt >= 0) {
            int dt = s.pt - s.prompt;
            if (dt <= p.flipGood) {
                s.nGood++;
            } else if (dt <= p.flipGood + p.flipLate) {
                s.nLate++; s.flipPen += p.penLate; s.flipOver += p.negLateOver; s.flipAllGood = 0;
            } else {
                s.nBurnt++;
                s.flipPen += Math.min(p.penBurntCap, p.penBurntBase + s.burn);
                s.flipOver += Math.min(p.negBurntCap, p.negBurntBase + p.negBurntPerTick * s.burn);
                s.flipAllGood = 0;
            }
            s.flipIdx++; s.prompt = -1; s.burn = 0; kick(s, kickAmt);
        } else if (s.flipIdx < p.F) {
            // 提示还没出：按「离翻面点还差几 tick」分档（熟度差 ÷ 满速涨速）
            int d = markAt(p, s.flipIdx + 1, p.F) - s.P;
            int g0 = fullGain(p);
            if (d <= g0 * p.flipEarlyGoodTicks) {                    // 差 ≤ 3 tick：算正好（这个翻面点不再出提示）
                s.nGood++; s.flipIdx++; kick(s, kickAmt);
            } else if (d <= g0 * p.flipEarlyMildTicks) {             // 差 4–12 tick：翻早（轻）
                s.nEarly++; s.flipPen += p.penEarlyMild; s.flipUnder += p.negEarlyMildUnder; s.flipAllGood = 0;
                s.flipIdx++; kick(s, kickAmt);
            } else if (d <= g0 * p.flipEarlyZoneTicks) {             // 差 13–20 tick：翻早（重）
                s.nEarly++; s.flipPen += p.penEarly; s.flipUnder += p.negEarlyUnder; s.flipAllGood = 0;
                s.flipIdx++; kick(s, kickAmt);
            }
            // 更早按：无效、不罚（防误触）
        }
    }

    /** 推进 1 tick（就地修改）。input：bit0 hold，bit1 act；保留位的合法性由 replay 校验。 */
    public static void step(State s, int input) {
        if (s.end != RUNNING) return;
        final Params p = s.p;
        final int hold = input & 1, act = (input >> 1) & 1;

        // ⓪ 备餐台摆料段
        if (s.inSeg != 0) { prepSegmentTick(s, p, act); s.tick++; return; }

        final int st = p.station;
        // ① stationPre
        if (st == FRYER) {
            if (s.cd > 0) s.cd--;
            if (act != 0 && s.cd == 0) { s.T = Math.max(0, s.T - p.oilDrop); s.cd = p.oilCd; }
        } else if (st == OVEN) {
            if (act != 0) ovenFlipInput(s, p);
        }

        // ② 火候条
        final int H = p.H, top = p.track - H;
        if (hold != 0 && (s.yb == 0 || s.yb == top)) s.vb = 0;
        int a = hold != 0 ? p.accel : -p.accel;
        if (s.inPrev != 0) a = a * p.inBarNum / p.inBarDen;
        s.vb += a;
        s.yb += s.vb;
        if (s.yb > top) { s.yb = top; s.vb = -(s.vb * p.bounceTopNum / p.bounceDen); }
        else if (s.yb < 0) { s.yb = 0; s.vb = -(s.vb * p.BB / p.bounceDen); }
        s.pin = (s.yb == 0 || s.yb == top) ? s.pin + 1 : 0;
        final boolean pinned = s.pin >= p.pinTicks;

        // ③ 食材
        for (int k = 0; k < p.fishSubsteps; k++) fishSub(s, p);

        // ④ 判定
        final boolean geo = s.yb <= s.yt && s.yt <= s.yb + H;
        final boolean inBar = geo && !pinned;
        final int side;
        if (inBar) side = SIDE_IN;
        else if (s.yt > s.yb + H) side = SIDE_UNDER;
        else if (s.yt < s.yb) side = SIDE_OVER;
        else side = s.yb == 0 ? SIDE_UNDER : SIDE_OVER;

        // ⑤ 调料瓶
        boolean bIn = false;
        if (s.bState == B_WAITING && s.pt == s.bAt) {
            int off = s.bDir != 0 ? s.bOff : -s.bOff;
            int pos = s.yt + off;
            if (pos < p.fishMin || pos > p.fishMax) pos = s.yt - off;
            s.bPos = Math.max(p.fishMin, Math.min(p.fishMax, pos));
            s.bState = B_PRESENT;
        }
        if (s.bState == B_PRESENT) {
            bIn = !pinned && s.yb <= s.bPos && s.bPos <= s.yb + H;
            if (bIn) {
                s.K += p.bFill;
                if (s.K >= K_MAX) { s.K = K_MAX; s.bState = B_CAUGHT; }
            } else {
                s.K = Math.max(0, s.K - p.drainPm);
            }
        }

        // ⑥ stationMid
        int mul = 1000;
        boolean burst = false;
        if (st == POT) {
            s.h = inBar ? Math.min(p.heatMax, s.h + p.heatUp) : Math.max(0, s.h - p.heatDown);
            mul = p.heatMulBase + s.h * p.heatMulSpan / 1000;
        } else if (st == FRYER) {
            int rise = (inBar ? p.oilRiseIn : p.oilRiseOut) + (p.star - 1) / p.oilRiseStarDiv + s.P / p.oilRiseProgDiv;
            if (s.T >= p.oilHot) rise = rise / p.oilHotRiseDiv;          // 过热区升温放缓
            s.T += rise;
            if (s.T >= p.oilBurst) {
                s.T = p.oilAfterBurst; s.bursts++; burst = true; kick(s, p.burstKick);
            } else if (s.T >= p.oilHot) {
                s.hot++; mul = p.hotMulPm;
            } else if (s.T < p.oilCold) {
                s.cold++; mul = p.coldMulPm;
            }
        } else if (st == OVEN) {
            if (s.prompt >= 0 && s.pt - s.prompt > p.flipGood + p.flipLate) { s.burn++; mul = p.burnMulPm; }
        }

        // ⑦ 熟度（每乘一步就 /1000 截断）
        int g = p.GAIN;
        g = g * mul / 1000;
        g = g * p.PACE / 1000;
        g = g * p.MAST / 1000;
        g = g * p.BPACE / 1000;
        int l = p.LOSS;
        l = l * p.PACE / 1000;
        l = l * p.BPACE / 1000;
        if (inBar) s.P += g;
        else if (s.bState == B_PRESENT && bIn) { /* 瓶护：不变 */ }
        else s.P -= l;
        if (burst) s.P -= p.burstProgressLoss;
        if (st == OVEN && s.flipIdx < p.F) s.P = Math.min(s.P, p.flipHoldCap);

        // ⑧ 统计
        s.S++;
        if (inBar) { s.I++; s.outRun = 0; }
        else {
            if (side == SIDE_UNDER) s.U++; else s.O++;
            s.outRun++;
            if (s.outRun > p.perfectGraceTicks) s.perfect = 0;
        }
        if (st == POT) s.hSum += s.h;

        // ⑨ stationPost
        if (st == OVEN) {
            if (s.prompt >= 0 && s.pt - s.prompt >= p.flipAuto) {
                s.nAuto++; s.flipPen += p.penAuto; s.flipOver += p.negAutoOver; s.flipAllGood = 0;
                s.flipIdx++; s.prompt = -1; s.burn = 0;
                kick(s, p.flipKickBase + p.flipKickPerStar * p.star);
            }
            if (s.prompt < 0 && s.flipIdx < p.F && s.P >= markAt(p, s.flipIdx + 1, p.F)) s.prompt = s.pt + 1;
        } else if (st == PREP) {
            if (s.segIdx < p.segs && s.P >= markAt(p, s.segIdx + 1, p.segs)) { s.inSeg = 1; s.segT = 0; s.bi = 0; }
        }

        // ⑩ 收尾（出锅 → 糊锅 → 到点）
        s.inPrev = inBar ? 1 : 0;
        s.pt++; s.tick++;
        if (s.P >= p.progressMax) { s.P = p.progressMax; s.end = DONE; }
        else if (s.P <= 0) { s.P = 0; s.end = BURNT; }
        else if (s.pt >= p.CAP) { s.end = TIMEOUT; }
    }

    /** 撂勺（服务端在 tick 之间判定）。 */
    public static void abandon(State s) { if (s.end == RUNNING) s.end = ABANDON; }

    // =====================================================================
    // 状态哈希（spec §19.3）
    // =====================================================================
    public static int hash(State s) {
        int[] v = {
            s.tick, s.pt, s.end, s.inSeg, s.segIdx, s.segT, s.bi, s.yb, s.vb, s.inPrev, s.pin, s.yt, s.vt, s.tgt, s.ad,
            s.P, s.S, s.I, s.U, s.O, s.outRun, s.perfect, s.bKind, s.bState, s.bPos, s.K, s.h, s.hSum, s.T, s.cd, s.hot,
            s.cold, s.bursts, s.flipIdx, s.prompt, s.burn, s.flipPen, s.flipUnder, s.flipOver, s.flipAllGood,
            s.perfB, s.okB, s.miss, s.mash, s.kickN, s.rT.a, s.rB.a, s.rS.a,
        };
        return fnv1a(v);
    }

    // =====================================================================
    // 机械节奏检测（spec §18.7 本局规则）
    // =====================================================================
    public static int detectMechanical(int[] inputs, int count, Params p) {
        if (count <= 0) return 0;
        int[] runBit = new int[count], runLen = new int[count];
        int runs = 0, cur = inputs[0] & 1, len = 0;
        for (int i = 0; i < count; i++) {
            int b = inputs[i] & 1;
            if (b == cur) len++;
            else { runBit[runs] = cur; runLen[runs] = len; runs++; cur = b; len = 1; }
        }
        runBit[runs] = cur; runLen[runs] = len; runs++;
        if (runs <= 2) return 0;
        // 去掉首尾段，按住段 / 松开段分别统计「最常见长度」的占比
        int[] holdCnt = new int[count + 1], relCnt = new int[count + 1];
        int holdN = 0, relN = 0, holdBest = 0, relBest = 0;
        for (int i = 1; i < runs - 1; i++) {
            if (runBit[i] != 0) { holdN++; holdBest = Math.max(holdBest, ++holdCnt[runLen[i]]); }
            else { relN++; relBest = Math.max(relBest, ++relCnt[runLen[i]]); }
        }
        boolean holdSame = holdN >= p.mechanicalMinRuns && holdBest * 100 >= p.mechanicalSamePct * holdN;
        boolean relSame = relN >= p.mechanicalMinRuns && relBest * 100 >= p.mechanicalSamePct * relN;
        return holdSame && relSame ? 1 : 0;
    }

    // =====================================================================
    // 结算（spec §14）
    // =====================================================================
    /** 服务端外部判定（可全 0）。gold = 服务端私下抽到的瓶种类（1 金 / 0 普通），不从种子推出。 */
    public static final class Ext {
        public int gold, slow, mechanical, suspect;
        public Ext() {}
        public Ext(int gold, int slow, int mechanical, int suspect) { this.gold = gold; this.slow = slow; this.mechanical = mechanical; this.suspect = suspect; }
    }

    public static final class Result {
        public int simVersion, station, star, level, end, ended, quality, qualityRaw, perfect;
        public int bottle, bottleKind, bottleState, gold, score, accuracy, pen, bottleBonus, underPm, overPm, messPm;
        public int[] negatives;
        public int ticks, formalTicks, S, I, U, O, progress, avgHeat, hotPm, coldPm, bursts;
        public int flips, flipGood, flipLate, flipEarly, flipBurnt, flipAuto, beatPerfect, beatOk, beatMiss, beatMash;
        public int mechanical, slow, suspect;
        public String hash;

        /** 与 sim.js result() 同名同序，便于和 golden.json 逐键比较。 */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("simVersion", simVersion); m.put("station", station); m.put("star", star); m.put("level", level);
            m.put("end", end); m.put("ended", ended); m.put("quality", quality); m.put("qualityRaw", qualityRaw);
            m.put("perfect", perfect); m.put("bottle", bottle); m.put("bottleKind", bottleKind);
            m.put("bottleState", bottleState); m.put("gold", gold); m.put("score", score); m.put("accuracy", accuracy);
            m.put("pen", pen); m.put("bottleBonus", bottleBonus); m.put("underPm", underPm); m.put("overPm", overPm);
            m.put("messPm", messPm); m.put("negatives", negatives); m.put("ticks", ticks); m.put("formalTicks", formalTicks);
            m.put("S", S); m.put("I", I); m.put("U", U); m.put("O", O); m.put("progress", progress);
            m.put("avgHeat", avgHeat); m.put("hotPm", hotPm); m.put("coldPm", coldPm); m.put("bursts", bursts);
            m.put("flips", flips); m.put("flipGood", flipGood); m.put("flipLate", flipLate); m.put("flipEarly", flipEarly);
            m.put("flipBurnt", flipBurnt); m.put("flipAuto", flipAuto); m.put("beatPerfect", beatPerfect);
            m.put("beatOk", beatOk); m.put("beatMiss", beatMiss); m.put("beatMash", beatMash);
            m.put("mechanical", mechanical); m.put("slow", slow); m.put("suspect", suspect); m.put("hash", hash);
            return m;
        }
    }

    public static Result result(State s, Ext ext) {
        final Params p = s.p;
        final Ext e = ext != null ? ext : new Ext();
        final int S = Math.max(1, s.S);
        final int C = s.I * 1000 / S;
        int under = s.U * 1000 / S, over = s.O * 1000 / S, mess = 0, pen = 0, clean = 1;
        int avgHeat = 0, hotPm = 0, coldPm = 0;
        switch (p.station) {
            case POT:
                avgHeat = s.hSum / S;
                pen = (1000 - avgHeat) / p.penDiv;
                under += (1000 - avgHeat) / p.underDiv;
                break;
            case FRYER:
                hotPm = s.hot * 1000 / S;
                coldPm = s.cold * 1000 / S;
                pen = hotPm / p.penHotDiv + coldPm / p.penColdDiv + p.penBurst * s.bursts;
                over += hotPm / p.negHotDiv + p.negBurst * s.bursts;
                mess += coldPm / p.negColdDiv;
                clean = (s.bursts == 0 && s.hot + s.cold <= p.cleanTicks) ? 1 : 0;
                break;
            case OVEN:
                pen = s.flipPen; under += s.flipUnder; over += s.flipOver; clean = s.flipAllGood;
                break;
            case PREP:
                pen = p.penOk * s.okB + p.penMiss * s.miss + p.penMash * s.mash;
                mess += s.miss * p.negMissPm / p.beats + p.negMash * s.mash;
                clean = (s.miss == 0 && s.mash == 0) ? 1 : 0;
                break;
            default:
                throw new IllegalStateException("station " + p.station);
        }
        final int bottle = s.bState == B_CAUGHT ? 1 : 0;
        final int gold = (s.bKind != KIND_NONE && e.gold != 0) ? 1 : 0;
        final int bottleKind = s.bKind == KIND_NONE ? KIND_NONE : gold == 1 ? KIND_GOLD : KIND_NORMAL;
        final int bottleBonus = p.scoreBonus * bottle;
        final int score = Math.max(0, C - pen + bottleBonus);
        final int done = s.end == DONE ? 1 : 0;
        final int perfect = (done == 1 && s.perfect == 1 && clean == 1) ? 1 : 0;
        final int mechanical = e.mechanical != 0 ? 1 : 0, slow = e.slow != 0 ? 1 : 0, suspect = e.suspect;

        int qualityRaw, quality;
        if (s.end == BURNT || s.end == ABANDON) {
            qualityRaw = quality = Q_HOMESTYLE;
        } else {
            if (perfect == 1 && bottle == 1 && gold == 1 && p.N == 1) qualityRaw = Q_RADIANT;
            else if (done == 1 && (perfect == 1 || score >= p.tierExtraordinary)) qualityRaw = Q_EXTRAORDINARY;
            else if (score >= p.tierHigh) qualityRaw = Q_HIGH;
            else if (score >= p.tierMedium) qualityRaw = Q_MEDIUM;
            else qualityRaw = Q_LOW;
            int cap = Q_RADIANT;
            if (s.end == TIMEOUT) cap = Math.min(cap, p.timeoutCap);
            if (p.N > 1) cap = Math.min(cap, p.batchCap);
            if (slow == 1) cap = Math.min(cap, p.slowCap);
            if (mechanical == 1) cap = Math.min(cap, p.mechanicalCap);
            if (suspect >= p.suspectCapHigh) cap = Math.min(cap, Q_HIGH);
            else if (suspect >= p.suspectCapRadiant) cap = Math.min(cap, Q_EXTRAORDINARY);
            quality = Math.min(qualityRaw, cap);
        }

        // 负面（spec §14.5）
        List<Integer> negs = new ArrayList<>();
        if (quality >= Q_LOW && quality <= Q_HIGH) {
            int th = quality == Q_LOW ? p.thresholdLow : quality == Q_MEDIUM ? p.thresholdMedium : p.thresholdHigh;
            int maxN = quality == Q_LOW ? p.maxLow : quality == Q_MEDIUM ? p.maxMedium : p.maxHigh;
            int[] vals = {under, over, mess};
            int[] order = {0, 1, 2};
            // 稳定插入排序：值降序；值相等保持 夹生 > 烧焦 > 倒胃
            for (int i = 1; i < 3; i++) {
                for (int j = i; j > 0 && vals[order[j]] > vals[order[j - 1]]; j--) {
                    int t = order[j]; order[j] = order[j - 1]; order[j - 1] = t;
                }
            }
            List<Integer> picked = new ArrayList<>();
            for (int i = 0; i < 3 && picked.size() < maxN; i++) if (vals[order[i]] >= th) picked.add(order[i]);
            if (s.end == TIMEOUT && !picked.contains(NEG_UNDERDONE)) {
                picked.add(NEG_UNDERDONE);
                if (picked.size() > 2) picked.remove(picked.size() - 2); // 去掉原来挑中的最小那条
            }
            for (int k = 0; k < 3; k++) if (picked.contains(k)) negs.add(k); // 规范顺序
        }

        Result r = new Result();
        r.simVersion = SIM_VERSION; r.station = p.station; r.star = p.star; r.level = p.level;
        r.end = s.end; r.ended = s.end != RUNNING ? 1 : 0; r.quality = quality; r.qualityRaw = qualityRaw; r.perfect = perfect;
        r.bottle = bottle; r.bottleKind = bottleKind; r.bottleState = s.bState; r.gold = gold;
        r.score = score; r.accuracy = C; r.pen = pen; r.bottleBonus = bottleBonus;
        r.underPm = under; r.overPm = over; r.messPm = mess;
        r.negatives = negs.stream().mapToInt(Integer::intValue).toArray();
        r.ticks = s.tick; r.formalTicks = s.pt; r.S = s.S; r.I = s.I; r.U = s.U; r.O = s.O; r.progress = s.P;
        r.avgHeat = avgHeat; r.hotPm = hotPm; r.coldPm = coldPm; r.bursts = s.bursts;
        r.flips = s.flipIdx; r.flipGood = s.nGood; r.flipLate = s.nLate; r.flipEarly = s.nEarly; r.flipBurnt = s.nBurnt; r.flipAuto = s.nAuto;
        r.beatPerfect = s.perfB; r.beatOk = s.okB; r.beatMiss = s.miss; r.beatMash = s.mash;
        r.mechanical = mechanical; r.slow = slow; r.suspect = suspect;
        r.hash = hex32(hash(s));
        return r;
    }

    // =====================================================================
    // 回放（spec §18.6）
    // =====================================================================
    public static final class Trace {
        public State state;
        public Result result;
        public final List<String> hashes = new ArrayList<>();
        public String finalHash;
        public int consumed, padded, invalid;
    }

    /** 输入用完还没结束 → 余下按 0 补；保留位非 0 → 撂勺。每推进 20 tick 记一次状态哈希。 */
    public static Trace replay(Params p, int seed, int[] inputs, Ext ext) {
        Trace tr = new Trace();
        State s = init(p, seed);
        int i = 0;
        final int guard = p.CAP * 4 + 4096;
        while (s.end == RUNNING) {
            int in = 0;
            if (i < inputs.length) in = inputs[i]; else tr.padded++;
            if ((in & ~3) != 0) { s.end = ABANDON; tr.invalid = 1; break; }
            step(s, in);
            i++;
            if (s.tick % 20 == 0) tr.hashes.add(hex32(hash(s)));
            if (i > guard) throw new IllegalStateException("replay 超出上界");
        }
        tr.consumed = i;
        int[] used = inputs.length >= i ? inputs : java.util.Arrays.copyOf(inputs, i);
        Ext e = ext != null ? ext : new Ext();
        int mech = e.mechanical != 0 ? 1 : detectMechanical(used, i, p);
        tr.state = s;
        tr.result = result(s, new Ext(e.gold, e.slow, mech, e.suspect));
        tr.finalHash = hex32(hash(s));
        return tr;
    }
}
