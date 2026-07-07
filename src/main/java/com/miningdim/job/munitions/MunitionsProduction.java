package com.miningdim.job.munitions;

/**
 * 军火台被动产线纯逻辑层 (Munitions_Job_DesignSpec 五/六/九章)。无任何 TACZ 依赖 (compileOnly 铁律): 算
 * "离线流逝 tick -> 应产多少发某口径 / 消耗多少料 / 工费多少 / 产弹经验多少", 把结果交给 BE 落地。GameTest
 * 只测本类 (删核心乘法/夹缓冲/扣料即必挂, 非 is-not-null 弱校验)。物化成 TACZ 弹 ItemStack 在
 * {@link MunitionsAmmoFactory}, 本类绝不触及。
 *
 * 产能模型 (五章, 类农夫被动追算): 总产能 = 制造台数 × 每台速率; 缓冲满则停产 (缓冲即天然离线产量上限)。
 * 离线追算: 记上次结算 tick, 玩家回来/区块加载时按流逝 tick 一次性补产, 填到缓冲上限即停。
 *
 * 双推进剂 (四章): 直造 (L1-5) 7 铜 + 16 火药 -> 40 发; 提炼 (L6+) 同料 -> 70 发 (翻倍, 利润质变线)。每批料消耗
 * 固定, 产出按 "基准发数 × 口径缩产系数" 缩放 (高阶弹单发料重出弹少)。本类按 "缓冲剩余空间 + 可用料 + 流逝时间"
 * 三者取最小, 决定本次实产发数, 再倒推消耗几批料。
 *
 * 工费 (九章 sink): 1.5 CP/发, ×10 锚价整数化为 15/10 发; 按本次产弹总量聚合后整数扣 (永不对单发传小数)。
 */
public final class MunitionsProduction {

    private MunitionsProduction() {
    }

    /**
     * 一次离线追算的产线结算结果 (BE 据此扣料、入缓冲、扣工费、给经验; 不可变)。
     *
     * @param roundsProduced 本次产出的弹药发数 (已按口径缩产 + 缓冲上限 + 可用料夹取; >=0)
     * @param batchesConsumed 本次消耗的料批数
     * @param primerConsumed 本次消耗底火总数
     * @param casingConsumed 本次消耗弹壳总数
     * @param bulletHeadConsumed 本次消耗弹头总数
     * @param propellantConsumed 本次消耗发射药总数
     * @param workFeeCredits  本次产弹应扣的信用点工费 (聚合整数; 销毁 = sink)
     * @param rawXp           本次产弹应给的原始经验 (谁产谁得, 框架再过衰减/软上限)
     */
    public record Result(int roundsProduced, int batchesConsumed, int primerConsumed,
                         int casingConsumed, int bulletHeadConsumed, int propellantConsumed,
                         long workFeeCredits, long rawXp) {

        /** 空结算 (无产出): 流逝不足 / 缓冲已满 / 无料。 */
        public static final Result NONE = new Result(0, 0, 0, 0, 0, 0, 0L, 0L);

        public boolean produced() {
            return roundsProduced > 0;
        }
    }

    /**
     * 把某等级每台速率 (发/时) 换算为 "每发耗时 tick" (11.3 PENDING 速率->tick)。
     * 每发 tick = ticksPerRateHour / ratePerTable (向上取整, 至少 1, 防 0 除 / 瞬产)。
     *
     * @param level 军火商等级 (查 ratePerTable)
     * @return 单台产出 1 发步枪当量所需 tick (>=1)
     */
    public static int ticksPerRound(int level) {
        int rate = MunitionsLevels.ratePerTable(level);
        int hourTicks = MunitionsConfig.TICKS_PER_RATE_HOUR.get();
        // 向上取整: 速率高 -> 每发 tick 少; rate 必 >=1 (config 下界), hourTicks 必 >=20。
        int perRound = (hourTicks + rate - 1) / rate;
        return Math.max(1, perRound);
    }

    /**
     * 由流逝 tick + 台数 + 速率算 "理论可产步枪当量发数" (五章 总产能 = 台数 × 速率; 未夹缓冲/料)。
     * 理论发数 = floor(流逝 tick / 每发 tick) × 台数。
     *
     * @param elapsedTicks 自上次结算流逝的 tick (>=0)
     * @param tableCount   制造台数 (>=0; 单 BE 通常视作 1 台, 全局产能 = 多台累加)
     * @param level        军火商等级 (查每发 tick)
     * @return 理论可产步枪当量发数 (>=0)
     */
    public static long theoreticalRounds(long elapsedTicks, int tableCount, int level) {
        if (elapsedTicks <= 0 || tableCount <= 0) {
            return 0L;
        }
        int perRound = ticksPerRound(level);
        long perTableRounds = elapsedTicks / perRound;
        return perTableRounds * tableCount;
    }

    /**
     * 该等级 + 提炼解锁态下, 单批料产出的口径实发数 (四章配方 + 缩产系数)。
     * 步枪基准 = 直造 40 / 提炼 70 发; 再 × 口径缩产系数后向下取整, 至少 1 发 (有料即产, 不静默吞)。
     *
     * @param caliber 目标口径 (缩产系数来源)
     * @param level   军火商等级 (决定提炼是否解锁)
     * @return 单批料该口径实产发数 (>=1)
     */
    public static int roundsPerBatch(MunitionsCaliber caliber, int level) {
        int baseRounds = MunitionsLevels.isRefineUnlocked(level)
                ? MunitionsConfig.REFINED_ROUNDS_PER_BATCH.get()
                : MunitionsConfig.DIRECT_ROUNDS_PER_BATCH.get();
        int scaled = (int) Math.floor(baseRounds * caliber.yieldFactor());
        return Math.max(1, scaled);
    }

    /**
     * 离线追算一次产线结算 (五章被动产线核心)。本次实产发数取三者最小:
     *  1. 流逝时间允许的理论步枪当量发数 × 口径缩产 (theoreticalRounds × yieldFactor);
     *  2. 缓冲剩余空间 (bufferRemaining; 缓冲满停产);
     *  3. 可用料能撑的批数 × 单批口径实发数 (铜/火药任一不足按短板算批)。
     * 实产发数按 "单批口径实发数" 向下取整到整批 (产线按批走料, 不产半批), 再倒推消耗料批数与工费/经验。
     *
     * 工费/经验按实产发数聚合: 工费 = floor(发数 × 1.5) 经 ×10 锚价整数化为 floor(发数 × 15 / 10); 经验 =
     * floor(发数 × perRoundMilli / 1000) (谁产谁得原始经验, 框架再衰减)。
     *
     * @param caliber          目标口径 (null = 未选口径, 不产 -> NONE)
     * @param level            军火商等级 (速率/缓冲/提炼解锁/经验)
     * @param tableCount       本次参与产能的制造台数 (>=1)
     * @param elapsedTicks     自上次结算流逝 tick (>=0)
     * @param bufferRemaining  缓冲剩余可入发数 (= 缓冲上限 - 已存; >=0)
     * @param availablePrimer 料槽可用底火数
     * @param availableCasing 料槽可用弹壳数
     * @param availableBulletHead 料槽可用弹头数
     * @param availablePropellant 料槽可用发射药数
     * @return 结算结果 (实产发数 / 料批 / 料耗 / 工费 / 经验); 任一前置不足返 {@link Result#NONE}
     */
    public static Result settle(MunitionsCaliber caliber, int level, int tableCount, long elapsedTicks,
                                int bufferRemaining, int availablePrimer, int availableCasing,
                                int availableBulletHead, int availablePropellant) {
        if (caliber == null || tableCount <= 0 || elapsedTicks <= 0 || bufferRemaining <= 0) {
            return Result.NONE;
        }

        int perBatchRounds = roundsPerBatch(caliber, level);
        int primerPerBatch = MunitionsConfig.RECIPE_PRIMER_COST.get();
        int casingPerBatch = MunitionsConfig.RECIPE_CASING_COST.get();
        int bulletHeadPerBatch = MunitionsConfig.RECIPE_BULLET_HEAD_COST.get();
        int propellantPerBatch = MunitionsConfig.RECIPE_PROPELLANT_COST.get();

        // 门 1: 流逝时间允许的步枪当量发数, 再过口径缩产 (与单批口径实发数同口径)。
        long theoryRifleRounds = theoreticalRounds(elapsedTicks, tableCount, level);
        long theoryCaliberRounds = (long) Math.floor(theoryRifleRounds * caliber.yieldFactor());

        // 门 2: 缓冲剩余空间。门 3: 料能撑的最大批数 (铜/火药短板)。
        int maxBatchesByPrimer = availablePrimer / primerPerBatch;
        int maxBatchesByCasing = availableCasing / casingPerBatch;
        int maxBatchesByBulletHead = availableBulletHead / bulletHeadPerBatch;
        int maxBatchesByPropellant = availablePropellant / propellantPerBatch;
        int maxBatchesByMaterial = Math.min(
                Math.min(maxBatchesByPrimer, maxBatchesByCasing),
                Math.min(maxBatchesByBulletHead, maxBatchesByPropellant));
        if (maxBatchesByMaterial <= 0) {
            return Result.NONE; // 料不足一批: 不产 (先查后扣, 杜绝白产)。
        }

        // 三门取最小, 折算到 "整批"。先把时间门/缓冲门换成 "允许的最大批数" (向下取整)。
        long maxBatchesByTime = theoryCaliberRounds / perBatchRounds;
        long maxBatchesByBuffer = (long) bufferRemaining / perBatchRounds;
        long batches = Math.min(maxBatchesByMaterial, Math.min(maxBatchesByTime, maxBatchesByBuffer));
        if (batches <= 0) {
            return Result.NONE; // 时间/缓冲/料任一不足一整批: 不产。
        }

        int batchesInt = (int) Math.min(batches, Integer.MAX_VALUE);
        int rounds = batchesInt * perBatchRounds;
        int primerConsumed = batchesInt * primerPerBatch;
        int casingConsumed = batchesInt * casingPerBatch;
        int bulletHeadConsumed = batchesInt * bulletHeadPerBatch;
        int propellantConsumed = batchesInt * propellantPerBatch;
        long workFee = workFee(rounds);
        long rawXp = produceXp(rounds);

        return new Result(rounds, batchesInt, primerConsumed, casingConsumed,
                bulletHeadConsumed, propellantConsumed, workFee, rawXp);
    }

    /**
     * 产 N 发的聚合工费 (九章 sink): 1.5 CP/发 经 ×10 锚价整数化为 15/10 发 -> floor(N × 15 / 10)。
     * 在批结算点整数聚合扣 (永不对单发传 1.5; tryCharge 收 long)。
     *
     * @param rounds 本次产弹发数 (>=0)
     * @return 应扣信用点工费 (>=0)
     */
    public static long workFee(int rounds) {
        if (rounds <= 0) {
            return 0L;
        }
        long perTen = MunitionsConfig.WORK_FEE_PER_TEN_ROUNDS.get();
        return (long) rounds * perTen / 10L;
    }

    /**
     * 产 N 发的原始经验 (七章 谁产谁得): floor(N × perRoundMilli / 1000)。框架再过每日衰减/软上限。
     *
     * @param rounds 本次产弹发数 (>=0)
     * @return 原始经验 (>=0)
     */
    public static long produceXp(int rounds) {
        if (rounds <= 0) {
            return 0L;
        }
        long perRoundMilli = MunitionsConfig.PRODUCE_XP_PER_ROUND_MILLI.get();
        return (long) rounds * perRoundMilli / 1000L;
    }
}
