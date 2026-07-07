package com.miningdim.champion;

/**
 * 精英怪【技能词条·视觉干扰 VISUAL_DISRUPTION】(Stage2; ChampionStarAffix spec 7.4 ★4 c12) 的数值折算 + 周期推进
 * 纯逻辑。把 {@link AffixDef#VISUAL_DISRUPTION} 品质档折算成: 单次失明【名义】时长 (tick) + 施放周期 (tick), 并给出
 * "扫描步进累加 -> 到点判定"的周期状态推进 (供 handler 逐扫描驱动)。
 *
 * 纯函数集合, 不碰世界/实体/Champions/net.minecraft, GameTest 直接断言 (删被测折算/周期判定必挂)。分工: 本类只算
 * "失明时长 + 周期 tick + 是否到点"; 实际失明 (原版 Blindness MobEffect)、控制裁剪 (经
 * {@link com.miningdim.champion.aggregate.PlayerControlAggregator} 红线 5 的 7s 窗 50% + ≥2s 自由窗夹断)、施放特效
 * (墨鱼墨粒子 + 远古守卫诅咒音) 由 integration 层 {@code ChampionVisualDisruptionHandler} 施加 (真服验)。
 *
 * 周期语义 (spec 7.4: 周期失明 1s每12s (普通) ... 2.5s每7s (闪耀, 仅★9+)): 循环计时只在【冠军有存活攻击目标】的
 * 扫描 tick 推进 (无目标不耗周期), 故本类用"已累加循环 tick"模型 —— handler 每个有目标的扫描调 {@link #advanceCycle}
 * 步进一个扫描粒度, {@link #cycleReady} 判到点; 到点由 handler 清零重计 (被控制裁到 0 亦清零, 周期照走不补偿,
 * 单一权威在 handler 不复制)。
 */
public final class ChampionVisualDisruptionValues {

    private ChampionVisualDisruptionValues() {
    }

    /** tick/秒 (时长/周期秒表 → tick 折算基)。 */
    public static final long TICKS_PER_SECOND = 20L;

    /**
     * 扫描/周期步进粒度 (tick): handler 每 1s 扫一次近玩家冠军, 有存活目标的扫描把循环推进一个此粒度 (与
     * {@code ChampionVisualDisruptionHandler} 的 ServerTick 节流对齐)。
     */
    public static final long SCAN_INTERVAL_TICKS = 20L;

    /**
     * 施放周期表 tick (品质 普通/中级/高级/超凡/闪耀 = 12/10.5/9/8/7 s = 240/210/180/160/140 tick)。
     * spec 7.4 只给端点 (普通 每 12s / 闪耀 每 7s); 中间三档 (中级/高级/超凡 = 10.5/9/8 s) 为用户拍板插值
     * (非线性, 前段每档 -1.5s、后段每档 -1s, 高品质更频繁施放)。索引 = {@link AffixQuality#valueIndex()}。
     */
    private static final long[] CYCLE_PERIOD_TICKS = {240L, 210L, 180L, 160L, 140L};

    /**
     * 单次失明【名义】时长 (tick) = {@link AffixDef#VISUAL_DISRUPTION} 品质档秒 × 20。2026-07-07 真服验收用户二调:
     * 全档统一 3s = 60 tick (原 1~2.5s 体感太短; 品质差异保留在施放周期上)。名义时长须再经控制聚合器
     * {@link com.miningdim.champion.aggregate.PlayerControlAggregator#admit} 的 7s 窗 50% 上限 + 自由窗复核夹断
     * 才是实际失明 tick (本类不夹, 单一权威在聚合器)。
     *
     * @param quality 视觉干扰品质
     * @return 名义失明 tick (&gt;0)
     */
    public static long blindnessDurationTicks(AffixQuality quality) {
        requireQuality(quality);
        // Math.round 防浮点表示误差 (2.25/2.5 等虽在 double 精确, 折算仍显式取整保长整值稳定)。
        return Math.round(AffixDef.VISUAL_DISRUPTION.valueFor(quality) * TICKS_PER_SECOND);
    }

    /**
     * 该品质施放周期 (tick; 见 {@link #CYCLE_PERIOD_TICKS} 表: 240/210/180/160/140)。
     *
     * @param quality 视觉干扰品质
     * @return 周期 tick (&gt;0)
     */
    public static long cyclePeriodTicks(AffixQuality quality) {
        requireQuality(quality);
        return CYCLE_PERIOD_TICKS[quality.valueIndex()];
    }

    /**
     * 推进循环一个扫描粒度 (handler 仅在【有存活目标】的扫描 tick 调用; 无目标不调 = 不推进 = 无目标不耗周期)。
     *
     * @param elapsedTicks 已累加循环 tick (须 &gt;=0)
     * @return 推进后的累加 tick
     */
    public static long advanceCycle(long elapsedTicks) {
        requireNonNegative(elapsedTicks);
        return elapsedTicks + SCAN_INTERVAL_TICKS;
    }

    /**
     * 循环是否到点 (已累加 ≥ 该品质周期): 到点则 handler 施放一次并把累加清零重计。
     *
     * @param elapsedTicks 已累加循环 tick (须 &gt;=0)
     * @param quality      视觉干扰品质
     * @return 是否到点施放
     */
    public static boolean cycleReady(long elapsedTicks, AffixQuality quality) {
        requireNonNegative(elapsedTicks);
        requireQuality(quality);
        return elapsedTicks >= cyclePeriodTicks(quality);
    }

    private static void requireQuality(AffixQuality quality) {
        if (quality == null) {
            throw new IllegalArgumentException("quality must not be null");
        }
    }

    private static void requireNonNegative(long elapsedTicks) {
        if (elapsedTicks < 0L) {
            throw new IllegalArgumentException("elapsedTicks must be >= 0, got " + elapsedTicks);
        }
    }
}
