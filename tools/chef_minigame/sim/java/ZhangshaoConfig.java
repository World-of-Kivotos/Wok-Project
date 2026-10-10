package com.miningdim.job.chef.zhangshao;

/**
 * 服务端参数块构造（spec §18.4 / §20）。与 sim.js 的 DEFAULT_CONFIG + buildParams 逐位一致（simVersion 2）。
 * 字段默认值 = ChefConfig [zhangshao] 默认值；接 ForgeConfigSpec 时把这些字段换成配置读取即可（顺序不变）。
 */
public final class ZhangshaoConfig {
    // §20.1–20.2
    public int track = 1000000;
    public int accel = 3960, inBarNum = 6, inBarDen = 10, bounceTopNum = 2, bounceDen = 3, bounceBottomNum = 2;
    public int pinTicks = 10, heightBase = 150000, heightPerLevel = 11000, heightMin = 180000;
    // §20.3
    public int[] starDifficulty = {32, 40, 47, 54, 61, 67, 73};
    public int[] motionOffset = {0, -6, 11, -2, -1};
    public int fishMin = 100000, fishMax = 900000, sinkLine = 300000, floatLine = 700000, fishSubsteps = 3;
    public int newDestDiv = 4000, steadyMul = 20, pctRandMin = 10, pctRandMax = 44;
    public int approachDivMin = 10, approachDivMax = 29, approachRelax = 5, arriveEps = 5280;
    public int hopChanceDiv = 2000, hopMin = 88000, hopMax = 177759;
    public int dartChanceDiv = 3000, dartMin = 88000, dartUnit = 1760, driftStep = 18, driftMax = 2640;
    // §20.4
    public int progressMax = 1000000, progressStart = 250000, gain = 4689, loss = 4689, lossPatience = 3516;
    public int[] stationPacePm = {800, 1000, 1000, 1250};
    public int[] stationCapPm = {1000, 1000, 1000, 1250};
    public int baseCapTicks = 300, flipHoldCap = 990000, perfectGraceTicks = 1;
    // §20.5（offset*Pm：瓶离食材的距离 = 火候条长 H 的千分比）
    public int normalBase = 250, normalPerLevel = 15, goldBase = 12, goldPerLevel = 3, appearMin = 20, appearMax = 60;
    public int offsetMinPm = 550, offsetMaxPm = 900, fill = 34000, drain = 30000, drainSharpEye = 15000, scoreBonus = 40;
    // §20.6
    public int perkSteadyHandsLevel = 3, perkPatienceLevel = 6, perkSharpEyeLevel = 9;
    // §20.7 厨锅
    public int heatStart = 300, heatUp = 30, heatDown = 80, heatMax = 1000, heatMulBase = 700, heatMulSpan = 600;
    public int penDiv = 8, underDiv = 6;
    // §20.8 炸锅
    public int oilStart = 450, oilRiseIn = 4, oilRiseOut = 3, oilRiseStarDiv = 3, oilRiseProgDiv = 300000, oilHotRiseDiv = 2, oilDrop = 280;
    public int[] oilCdByLevel = {34, 33, 32, 31, 30, 29, 28, 27, 26, 25};
    public int oilCold = 300, oilHot = 750, oilBurst = 1000, oilAfterBurst = 550, coldMulPm = 600, hotMulPm = 1000;
    public int burstProgressLoss = 60000, burstKick = 8333, penHotDiv = 3, penColdDiv = 3, penBurst = 80;
    public int negHotDiv = 2, negColdDiv = 2, negBurst = 150, cleanTicks = 20;
    // §20.9 烤炉（flipEarly*Ticks：离翻面点还差几 tick 的熟度，按本局满速涨速换算）
    public int flipsDefault = 1;
    public int[] flipGoodByLevel = {8, 8, 9, 9, 10, 10, 11, 11, 12, 13};
    public int flipLate = 12, flipAuto = 80, flipEarlyGoodTicks = 3, flipEarlyMildTicks = 12, flipEarlyZoneTicks = 20;
    public int burnMulPm = 500, flipKickBase = 4000, flipKickPerStar = 500;
    public int penLate = 25, penEarlyMild = 25, penEarly = 60, penBurntBase = 100, penBurntCap = 200, penAuto = 200;
    public int negEarlyMildUnder = 60, negEarlyUnder = 120, negLateOver = 60, negBurntBase = 150, negBurntPerTick = 3, negBurntCap = 300, negAutoOver = 300;
    // §20.10 备餐台
    public int[] segmentsByStar = {1, 1, 1, 1, 1, 1, 1};
    public int beatsMin = 3, beatsMax = 8, beatsDefault = 5;
    public int[] beatGapByStar = {14, 13, 12, 11, 10, 9, 8};
    public int[] beatJitter = {-2, 0, 2};
    public int beatGapMin = 7, beatLead = 16, beatTail = 8, beatPerfectW = 1;
    public int[] beatGoodWByLevel = {2, 2, 2, 2, 3, 3, 3, 3, 3, 3};
    public int penOk = 8, penMiss = 50, penMash = 20, negMissPm = 500, negMash = 40, drinkBeats = 6, drinkGapDelta = -2;
    // §20.11（品质码：0 低 1 中 2 高 3 超凡 4 闪耀）
    public int tierExtraordinary = 960, tierHigh = 890, tierMedium = 760;
    public int timeoutCap = 1, batchCap = 2, slowCap = 2, mechanicalCap = 1;
    // §20.12
    public int thresholdLow = 40, thresholdMedium = 120, thresholdHigh = 150, maxLow = 2, maxMedium = 1, maxHigh = 1;
    // §20.13（内核/结算用到的部分）
    public int masteryStartBonus = 20000, masteryGainPm = 80;
    public int[] batchPacePm = {1000, 750, 620, 540, 480, 440};
    public int mechanicalMinRuns = 32, mechanicalSamePct = 100, suspectCapRadiant = 40, suspectCapHigh = 80;

    /** 开局选项（服务端在开局那一刻锁定，spec §15.4）。 */
    public static final class Options {
        public int station, star, level;
        public int motion = -1;      // −1 = 按台默认
        public int mastery = 0, batch = 1;
        public int flips = 0;        // 0 = flipsDefault（只对烤炉）
        public int beats = 0;        // 0 = beatsDefault（只对备餐台）
        public boolean drink = false;
        public int goldPm = -1, normalPm = -1; // −1 = 按等级公式（测试/调试可覆盖）
        public boolean goldBlocked = false;    // 灵感冷却 / 日上限 / 可疑分 ≥ 40
    }

    /** 没指定性子时：炸锅浮、冷饮浮，其余混（§13.6 的「其余」）。 */
    public static int defaultMotion(int station, boolean drink) {
        if (station == ZhangshaoKernel.FRYER) return ZhangshaoKernel.FLOAT;
        if (station == ZhangshaoKernel.PREP && drink) return ZhangshaoKernel.FLOAT;
        return ZhangshaoKernel.MIX;
    }

    private static int check(String name, int v, int lo, int hi) {
        if (v < lo || v > hi) throw new IllegalArgumentException(name + " = " + v + " 不在 [" + lo + ", " + hi + "]");
        return v;
    }

    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : v > hi ? hi : v; }

    public int[] buildParams(Options o) {
        final int station = check("station", o.station, 0, 3);
        final int star = check("star", o.star, 1, 7);
        final int L = check("level", o.level, 1, 10);
        final int mastery = check("mastery", o.mastery, 0, 5);
        final int N = check("batch", o.batch, 1, batchPacePm.length);
        final boolean drink = station == ZhangshaoKernel.PREP && o.drink;
        final int motion = check("motion", o.motion >= 0 ? o.motion : defaultMotion(station, drink), 0, 4);
        final boolean isFry = station == ZhangshaoKernel.FRYER, isOven = station == ZhangshaoKernel.OVEN;
        final boolean isPrep = station == ZhangshaoKernel.PREP;
        final int PACE = stationPacePm[station], BPACE = batchPacePm[N - 1];
        final int MAST = 1000 + masteryGainPm * mastery;
        final int H = Math.max(heightMin, heightBase + heightPerLevel * (L - 1));
        final int normal = o.normalPm >= 0 ? o.normalPm : normalBase + normalPerLevel * (L - 1);
        final int gold = (N > 1 || o.goldBlocked) ? 0 : (o.goldPm >= 0 ? o.goldPm : goldBase + goldPerLevel * (L - 1));
        final int bottlePm = Math.min(1000, normal + gold);

        int[] b = new int[ZhangshaoKernel.PARAM_COUNT];
        int i = 0;
        // ---- A 段 ----
        b[i++] = ZhangshaoKernel.SIM_VERSION;
        b[i++] = station;
        b[i++] = star;
        b[i++] = L;
        b[i++] = motion;
        b[i++] = clamp(starDifficulty[star - 1] + motionOffset[motion], 0, 100);  // D
        b[i++] = H;
        b[i++] = progressStart + masteryStartBonus * mastery;                       // P0
        b[i++] = gain;                                                              // GAIN
        b[i++] = L >= perkPatienceLevel ? lossPatience : loss;                      // LOSS
        b[i++] = PACE;
        b[i++] = MAST;
        b[i++] = N;
        b[i++] = BPACE;
        b[i++] = baseCapTicks * 1000000 / (stationCapPm[station] * BPACE);          // CAP
        b[i++] = bottlePm;                                                          // 有瓶‰（内核用）
        b[i++] = Math.min(gold, bottlePm);                                          // 金瓶‰（服务端私下抽签用，内核不读）
        b[i++] = L >= perkSharpEyeLevel ? drainSharpEye : drain;                    // drainPm
        b[i++] = L >= perkSteadyHandsLevel ? 1 : bounceBottomNum;                   // BB
        b[i++] = isOven ? check("flips", o.flips > 0 ? o.flips : flipsDefault, 1, 8) : 0;   // F
        b[i++] = isOven ? flipGoodByLevel[L - 1] : 0;
        b[i++] = isFry ? oilCdByLevel[L - 1] : 0;
        b[i++] = isPrep ? (drink ? drinkBeats : clamp(o.beats > 0 ? o.beats : beatsDefault, beatsMin, beatsMax)) : 0;
        b[i++] = isPrep ? segmentsByStar[star - 1] : 0;
        b[i++] = isPrep ? beatGapByStar[star - 1] + (drink ? drinkGapDelta : 0) : 0;
        b[i++] = isPrep ? (drink ? 0 : 1) : 0;                                      // jitterOn
        b[i++] = isPrep ? beatGoodWByLevel[L - 1] : 0;                              // goodW
        b[i++] = fill * MAST / 1000;                                                // bFill
        b[i++] = appearMin * 1000 / MAST;                                           // bAppearMin
        b[i++] = appearMax * 1000 / MAST;                                           // bAppearMax
        b[i++] = H * offsetMinPm / 1000;                                            // bOffMin
        b[i++] = H * offsetMaxPm / 1000;                                            // bOffMax
        // ---- B 段（§20 表序） ----
        int[] B = {
            track, accel, inBarNum, inBarDen, bounceTopNum, bounceDen, pinTicks,
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
            beatJitter[0], beatJitter[1], beatJitter[2], beatGapMin, beatLead, beatTail, beatPerfectW,
            penOk, penMiss, penMash, negMissPm, negMash,
            tierExtraordinary, tierHigh, tierMedium, timeoutCap, batchCap, slowCap, mechanicalCap,
            thresholdLow, thresholdMedium, thresholdHigh, maxLow, maxMedium, maxHigh,
            mechanicalMinRuns, mechanicalSamePct, suspectCapRadiant, suspectCapHigh,
        };
        System.arraycopy(B, 0, b, i, B.length);
        i += B.length;
        if (i != ZhangshaoKernel.PARAM_COUNT) throw new AssertionError("参数块长度 " + i);
        return b;
    }
}
