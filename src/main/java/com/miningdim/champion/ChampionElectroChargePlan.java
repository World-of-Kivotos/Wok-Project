package com.miningdim.champion;

import java.util.ArrayList;
import java.util.List;

/**
 * 精英怪【技能词条·电磁蓄力 ELECTRO_CHARGE】(批4 波2; ChampionStarAffix spec 7.4 可躲型单点 AOE) 的数值折算 +
 * 落点几何 + 半径/缰绳判定 纯逻辑。电磁蓄力 = 冠军把当前攻击目标的【此刻站位】锁为落点, 蓄力 2s 后在落点半径
 * {@value #AOE_RADIUS} 格内引爆一发大额 AOE, 半径内每名存活玩家按自身 maxHP 百分比受伤。
 *
 * 可躲是红线 3 放宽本词条到大额单发 AOE (18~55% maxHP) 的前提: 蓄力期【落点锁定不追踪玩家】—— 玩家看见落点环
 * 指示后有 2s 窗口走出半径即完全免伤, 故名义值可远高于常规 on-hit 伤害而不违红线 (打不中的伤害不入叠杀预算)。
 *
 * 纯函数集合, 不碰世界/实体/Champions/net.minecraft (落点/环点以裸 double 坐标表达), GameTest 直接断言 (删被测
 * 折算/几何/边界必挂)。分工: 本类只算 "施放周期 tick + 蓄力时长 + AOE 百分比/伤害 + 半径/缰绳边界 + 落点环点坐标";
 * 实际 AABB 扫玩家、CHAMPION_SKILL_AOE 伤害下发、免疫缓冲 grant、蓄力/落点/爆点粒子音由 integration 层
 * {@code ChampionElectroChargeHandler} 施加 (真服验)。
 */
public final class ChampionElectroChargePlan {

    private ChampionElectroChargePlan() {
    }

    /** tick/秒 (周期秒表 → tick 折算基)。 */
    public static final long TICKS_PER_SECOND = 20L;

    /**
     * 扫描/冷却步进粒度 (tick): handler 每 1s 扫一次近玩家冠军, 门控通过 (有存活目标且在缰绳内) 的扫描把冷却循环
     * 推进一个此粒度 (与 {@code ChampionElectroChargeHandler} 的 ServerTick 节流对齐; 与闪光/战术传送同 1s 节奏)。
     */
    public static final long SCAN_INTERVAL_TICKS = 20L;

    /**
     * 蓄力时长 (tick; 用户裁定 2s = 40 tick): 冷却到点起手后落点即锁定, 须蓄满此时长才引爆。此窗即玩家可躲窗 ——
     * 蓄力期内走出 {@value #AOE_RADIUS} 格半径即免伤。蓄力不设打断 (spec 未给且 2s 已短)。
     */
    public static final long CHARGE_TICKS = 40L;

    /** AOE 半径 (格; 用户裁定 3.5)。 */
    public static final double AOE_RADIUS = 3.5D;

    /** AOE 半径平方 (格²; 与 distanceToSqr 同量纲免开方)。 */
    public static final double AOE_RADIUS_SQ = AOE_RADIUS * AOE_RADIUS;

    /** 缰绳半径 (格; 波1/波2 统一 24): 冠军当前攻击目标距离 &lt;= 此值才推进冷却周期, 超出冻结不耗周期。 */
    public static final double TETHER_RANGE = 24.0D;

    /** 缰绳半径平方 (格²)。 */
    public static final double TETHER_RANGE_SQ = TETHER_RANGE * TETHER_RANGE;

    /** 落点环指示描点数 (圆周 24 点; handler 逐点喷粒子描 {@value #AOE_RADIUS} 格边界圈, 让玩家肉眼判定是否已走出)。 */
    public static final int RING_POINT_COUNT = 24;

    /**
     * 施放冷却周期表 tick (主线拍板: 品质 普通/中级/高级/超凡/闪耀 = 14/13/12/11/10 s = 280/260/240/220/200 tick,
     * 品质越高越频繁)。索引 = {@link AffixQuality#valueIndex()}。注: 本表与 {@link AffixDef#ELECTRO_CHARGE} 的数值
     * 数组【无关】—— 后者是单发 AOE 百分比 (0.18~0.55), 施放周期是主线独立裁定的另一维, 故此处硬编码不折算 AffixDef 值。
     */
    private static final long[] CYCLE_PERIOD_TICKS = {280L, 260L, 240L, 220L, 200L};

    /**
     * 该品质施放冷却周期 (tick; 见 {@link #CYCLE_PERIOD_TICKS} 表: 280/260/240/220/200)。
     *
     * @param quality 电磁蓄力品质
     * @return 冷却周期 tick (&gt;0)
     */
    public static long cycleTicks(AffixQuality quality) {
        requireQuality(quality);
        return CYCLE_PERIOD_TICKS[quality.valueIndex()];
    }

    /**
     * 推进冷却一个扫描粒度 (handler 仅在【有存活目标且在缰绳内】的扫描 tick 调用; 无目标/超缰绳不调 = 不推进 =
     * 冻结不耗周期)。
     *
     * @param elapsedTicks 已累加冷却 tick (须 &gt;=0)
     * @return 推进后的累加 tick
     */
    public static long advanceCycle(long elapsedTicks) {
        requireNonNegative(elapsedTicks);
        return elapsedTicks + SCAN_INTERVAL_TICKS;
    }

    /**
     * 冷却是否到点 (已累加 ≥ 该品质周期): 到点则 handler 起手蓄力并把累加于蓄力结束时清零重计。
     *
     * @param elapsedTicks 已累加冷却 tick (须 &gt;=0)
     * @param quality      电磁蓄力品质
     * @return 是否到点
     */
    public static boolean cycleReady(long elapsedTicks, AffixQuality quality) {
        requireNonNegative(elapsedTicks);
        requireQuality(quality);
        return elapsedTicks >= cycleTicks(quality);
    }

    /**
     * 该品质单发 AOE 百分比 (spec 7.4: 0.18/0.26/0.36/0.46/0.55 × 各玩家自身 maxHP), 取自
     * {@link AffixDef#ELECTRO_CHARGE} 主数值 (语义解释在本词条 = %maxHP)。
     *
     * @param quality 电磁蓄力品质
     * @return AOE 百分比 (0,1)
     */
    public static double aoeFraction(AffixQuality quality) {
        requireQuality(quality);
        return AffixDef.ELECTRO_CHARGE.valueFor(quality);
    }

    /**
     * 单发 AOE 伤害名义值 = {@link #aoeFraction} × 该玩家 maxHP (spec 7.4 各玩家按自身血量结算)。名义值经玩家护甲
     * 减免后生效 (CHAMPION_SKILL_AOE 不入 bypasses_armor, 红线 3 原文), 故精装玩家吃更少实伤。
     *
     * @param quality 电磁蓄力品质
     * @param maxHp   受击玩家的 maxHealth (须 &gt;0; 活玩家恒 &gt;0, 0/负/NaN 属调用方 bug 抛不掩盖)
     * @return 该玩家应受的 AOE 名义伤
     */
    public static double aoeDamage(AffixQuality quality, double maxHp) {
        requireQuality(quality);
        if (!(maxHp > 0.0D) || Double.isNaN(maxHp)) {
            throw new IllegalArgumentException("maxHp must be > 0, got " + maxHp);
        }
        return aoeFraction(quality) * maxHp;
    }

    /**
     * 落点半径判定: 玩家到落点距离平方 &lt;= {@value #AOE_RADIUS_SQ} (含边界 {@value #AOE_RADIUS} 格) 即在 AOE 内。
     * 边界含 3.5 格 (距² 12.25) 而拒 3.51 格 (距² 12.3201) —— 平方距离直接比较, 免开方精度损耗。
     *
     * @param distanceSq 玩家到落点的距离平方 (须 &gt;=0)
     * @return 是否在 AOE 半径内
     */
    public static boolean withinAoe(double distanceSq) {
        requireNonNegativeDistance(distanceSq);
        return distanceSq <= AOE_RADIUS_SQ;
    }

    /**
     * 缰绳判定: 冠军到攻击目标距离平方 &lt;= {@value #TETHER_RANGE_SQ} (含边界 {@value #TETHER_RANGE} 格) 即在缰绳内
     * (推进冷却周期的前置门控; 超出则 handler 冻结周期不推进)。
     *
     * @param distanceSq 冠军到目标玩家的距离平方 (须 &gt;=0)
     * @return 是否在缰绳内
     */
    public static boolean withinTether(double distanceSq) {
        requireNonNegativeDistance(distanceSq);
        return distanceSq <= TETHER_RANGE_SQ;
    }

    /**
     * 落点环指示描点 (圆周 {@value #RING_POINT_COUNT} 点, 半径 {@value #AOE_RADIUS} 格): handler 逐点喷粒子把 AOE
     * 边界圈可视化, 让玩家在 2s 蓄力窗内肉眼判定是否已走出半径。角度均分 (第 i 点 = i × 2π/24), 每点恒落半径圆周上
     * (cos/sin 单位圆 × 半径), 首点 (角 0) = (centerX + {@value #AOE_RADIUS}, centerZ)。
     *
     * @param centerX 落点 X (锁定的目标当时站位)
     * @param centerZ 落点 Z
     * @return {@value #RING_POINT_COUNT} 个 (x,z) 环点
     */
    public static List<double[]> ringPoints(double centerX, double centerZ) {
        List<double[]> points = new ArrayList<>(RING_POINT_COUNT);
        for (int i = 0; i < RING_POINT_COUNT; i++) {
            double angle = (2.0D * Math.PI * i) / RING_POINT_COUNT;
            double x = centerX + AOE_RADIUS * Math.cos(angle);
            double z = centerZ + AOE_RADIUS * Math.sin(angle);
            points.add(new double[]{x, z});
        }
        return points;
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

    private static void requireNonNegativeDistance(double distanceSq) {
        if (distanceSq < 0.0D || Double.isNaN(distanceSq)) {
            throw new IllegalArgumentException("distanceSq must be >= 0, got " + distanceSq);
        }
    }
}
