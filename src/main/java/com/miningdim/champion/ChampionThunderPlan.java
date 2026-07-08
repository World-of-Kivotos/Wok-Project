package com.miningdim.champion;

import java.util.ArrayList;
import java.util.List;

/**
 * 精英怪【技能词条·天雷 THUNDER】(批4 波2; ChampionStarAffix spec 7.4 可躲多点 AOE) 的数值折算 + 落点散布几何
 * 纯逻辑。天雷是电磁蓄力 (单点) 的多点版: 以目标为中心散布 N 个落点, 1.5s 粒子环预兆后逐点落雷, 半径内玩家各吃
 * "每点 %maxHP"的 CHAMPION_SKILL_AOE。核心反制是【多点分散不可同点叠杀】—— 任意两落点水平间距 &gt;= 2×半径,
 * 加上命中即 grant 2s 免疫缓冲, 保证单玩家一次天雷至多被一点结算 (其余点落他身上被缓冲掐 0)。
 *
 * <p>本类把 {@link AffixDef#THUNDER} 品质档折算成: 施放周期 (tick) + "扫描步进累加 -&gt; 到点判定" + 每点伤害
 * (fraction × 玩家 maxHP) + 落点数, 并给出散布落点的几何: 给定 (角度, 距离) 提案序列, 以拒绝采样贪心挑出两两间距
 * 达标的落点集 (随机源在 handler 层, 本类只做确定性挑选, 无随机/无死循环)。
 *
 * <p>纯函数集合, 不碰世界/实体/Champions/net.minecraft (落点以裸 {@link BlastPoint} 水平坐标表达, handler 补 Y +
 * 转实体位置), GameTest 直接断言 (删被测折算/几何/边界必挂)。分工: 本类只算"周期 tick + 落点数 + 每点伤害 + 散布
 * 坐标 + 两两间距校验 + 半径判定"; 实际落雷 (LightningBolt)、粒子环、AOE 结算与免疫缓冲授予由 integration 层
 * {@code ChampionThunderHandler} 施加 (真服验)。
 *
 * <p>数值权威分工: 每点 %maxHP ({@link AffixDef#THUNDER} 主数值) 与落点数 (副数值) 单一权威在 {@link AffixDef},
 * 本类只读转发 (防第二份表漂移); 施放周期 (16/15/14/13/12s) 是主线平衡裁定, 不在 AffixDef, 故作本类专有表。
 */
public final class ChampionThunderPlan {

    private ChampionThunderPlan() {
    }

    /** tick/秒 (周期秒表 → tick 折算基)。 */
    public static final long TICKS_PER_SECOND = 20L;

    /**
     * 扫描/周期步进粒度 (tick): handler 每 1s 扫一次近玩家冠军, 门控通过的扫描把循环推进一个此粒度 (与
     * {@code ChampionThunderHandler} 的 ServerTick 节流对齐; 与电磁/视觉干扰同 1s 节奏)。
     */
    public static final long SCAN_INTERVAL_TICKS = 20L;

    /**
     * 施放周期表 tick (品质 普通/中级/高级/超凡/闪耀 = 16/15/14/13/12 s = 320/300/280/260/240 tick; 主线裁定)。
     * 索引 = {@link AffixQuality#valueIndex()}。高品质更频繁落雷。不在 {@link AffixDef#THUNDER} (其主/副数值另表
     * 每点伤害/落点数), 故为本类专有。
     */
    private static final long[] CYCLE_PERIOD_TICKS = {320L, 300L, 280L, 260L, 240L};

    /**
     * 门控范围 (格): 冠军当前攻击目标距离 &lt;= 此值才推进周期计时, 超出冻结不耗周期 (门控同电磁; 用户裁定 24 格)。
     */
    public static final double TARGET_RANGE = 24.0D;

    /** 门控范围平方 (格²): 与 {@code entity.distanceToSqr} 同量纲, 免开方。 */
    public static final double TARGET_RANGE_SQ = TARGET_RANGE * TARGET_RANGE;

    /** 预兆时长 (tick): 用户裁定 1.5s = 30 tick; 每落点逐 tick 喷粒子环, 给玩家散开的可躲窗。 */
    public static final int WARNING_TICKS = 30;

    /** 每落点 AOE 半径 (格): 用户裁定每点 2.5 格; 半径内玩家吃该点伤害。 */
    public static final double PER_POINT_RADIUS = 2.5D;

    /** 每落点 AOE 半径平方 (格²): 与水平距离平方比较, 免开方。 */
    public static final double PER_POINT_RADIUS_SQ = PER_POINT_RADIUS * PER_POINT_RADIUS;

    /** 落点绕目标散布最小距离 (格): 用户裁定 3 格 (不贴脸目标)。 */
    public static final double MIN_SCATTER_DISTANCE = 3.0D;

    /** 落点绕目标散布最大距离 (格): 用户裁定 8 格。 */
    public static final double MAX_SCATTER_DISTANCE = 8.0D;

    /**
     * 任意两落点最小水平间距 (格): 硬约束 = 2×半径 (spec"多点分散不可同点叠杀")。两点间距 &gt;=5 则半径 2.5 的
     * 两个圆至多相切, 不重叠 —— 单玩家至多落在一个点的核心杀伤圈内, 叠杀由 2s 免疫缓冲兜底。
     */
    public static final double MIN_POINT_SEPARATION = 2.0D * PER_POINT_RADIUS;

    /** 两落点最小间距平方 (格²): 拒绝采样贴脸判定用, 免开方。 */
    public static final double MIN_POINT_SEPARATION_SQ = MIN_POINT_SEPARATION * MIN_POINT_SEPARATION;

    /** 落点数下限 (普通档 2 点; = {@link AffixDef#THUNDER} 副数值最小值)。 */
    public static final int MIN_POINTS = 2;

    /** 落点数上限 (闪耀档 6 点; = {@link AffixDef#THUNDER} 副数值最大值)。 */
    public static final int MAX_POINTS = 6;

    /**
     * 每落点的拒绝采样提案预算 (handler 按 desiredCount × 此值生成 (角度, 距离) 提案喂本类贪心挑选)。够大以在散布
     * 环内大概率凑齐达标落点集, 又有上限 —— 提案耗尽仍不够时 {@link #selectScatterPoints} 放宽到已挑出的点数,
     * 绝不死循环 (spec"上限尝试次数后放宽")。
     */
    public static final int SCATTER_ATTEMPTS_PER_POINT = 8;

    /**
     * 单个落点 (裸世界水平坐标; handler 补 Y = 目标脚下再转实体位置 / LightningBolt 落点)。天雷是从天而降的柱状
     * 打击, 落点由水平 (x,z) 定位, 竖直由 handler 取目标脚下 Y。
     */
    public record BlastPoint(double x, double z) {
    }

    /**
     * 该品质施放周期 (tick; 见 {@link #CYCLE_PERIOD_TICKS} 表: 320/300/280/260/240)。
     *
     * @param quality 天雷品质
     * @return 周期 tick (&gt;0)
     */
    public static long cycleTicks(AffixQuality quality) {
        requireQuality(quality);
        return CYCLE_PERIOD_TICKS[quality.valueIndex()];
    }

    /**
     * 推进循环一个扫描粒度 (handler 仅在【有存活目标且在门控范围内】的扫描 tick 调用; 无目标/超范围不调 = 不推进 =
     * 冻结不耗周期)。
     *
     * @param elapsedTicks 已累加循环 tick (须 &gt;=0)
     * @return 推进后的累加 tick
     */
    public static long advanceCycle(long elapsedTicks) {
        requireNonNegative(elapsedTicks);
        return elapsedTicks + SCAN_INTERVAL_TICKS;
    }

    /**
     * 周期是否到点 (已累加 ≥ 该品质周期): 到点则 handler 起一次预兆并把累加清零重计 (无论落点是否凑齐, 周期照走
     * 不补偿, 单一权威在 handler)。
     *
     * @param elapsedTicks 已累加循环 tick (须 &gt;=0)
     * @param quality      天雷品质
     * @return 是否到点
     */
    public static boolean cycleReady(long elapsedTicks, AffixQuality quality) {
        requireNonNegative(elapsedTicks);
        requireQuality(quality);
        return elapsedTicks >= cycleTicks(quality);
    }

    /**
     * 冠军当前攻击目标是否在门控范围内 (推进周期的前置门控): 距离平方 &lt;= {@value #TARGET_RANGE} 格² 才算在内。
     * 超出则 handler 冻结周期不推进 (门控同电磁)。
     *
     * @param distanceSq 冠军到目标玩家的距离平方 (须 &gt;=0)
     * @return 是否在门控范围内
     */
    public static boolean withinTargetRange(double distanceSq) {
        requireNonNegativeDistance(distanceSq, "distanceSq");
        return distanceSq <= TARGET_RANGE_SQ;
    }

    /**
     * 玩家是否落在某落点的杀伤圈内 (半径判定): 玩家到落点的【水平】距离平方 &lt;= {@value #PER_POINT_RADIUS} 格²
     * 才吃该点伤害。用水平距离 (柱状打击, 落点竖直贯穿, 竖直高差不参与), 与散布/间距同量纲。
     *
     * @param horizontalDistanceSq 玩家到落点的水平距离平方 (dx²+dz², 须 &gt;=0)
     * @return 是否命中该点
     */
    public static boolean withinBlast(double horizontalDistanceSq) {
        requireNonNegativeDistance(horizontalDistanceSq, "horizontalDistanceSq");
        return horizontalDistanceSq <= PER_POINT_RADIUS_SQ;
    }

    /**
     * 该品质落点数 (2/3/4/5/6; 读 {@link AffixDef#THUNDER} 副数值转发, 单一权威防漂移)。副数值以 double 存
     * (2.0..6.0), 取整用 {@link Math#round} 防浮点表示误差。
     *
     * @param quality 天雷品质
     * @return 落点数 (∈ [{@value #MIN_POINTS}, {@value #MAX_POINTS}])
     */
    public static int pointCount(AffixQuality quality) {
        requireQuality(quality);
        return (int) Math.round(AffixDef.THUNDER.secondaryValueFor(quality));
    }

    /**
     * 该品质每落点伤害占比 (0.12/0.17/0.22/0.27/0.32 × maxHP; 读 {@link AffixDef#THUNDER} 主数值转发, 单一权威
     * 防漂移)。
     *
     * @param quality 天雷品质
     * @return 每点伤害占玩家 maxHP 的比例
     */
    public static double perPointDamageFraction(AffixQuality quality) {
        requireQuality(quality);
        return AffixDef.THUNDER.valueFor(quality);
    }

    /**
     * 该品质每落点对某玩家的实际伤害 = {@link #perPointDamageFraction} × 玩家 maxHP (spec 平衡: 一律按 %最大血量
     * 下发, 精装玩家吃得多/脆皮吃得少)。名义值 (CHAMPION_SKILL_AOE 吃玩家护甲, 由结算层减免)。
     *
     * @param playerMaxHealth 被命中玩家最大生命 (须 &gt;=0)
     * @param quality         天雷品质
     * @return 每点名义伤害
     */
    public static double perPointDamage(double playerMaxHealth, AffixQuality quality) {
        requireQuality(quality);
        if (playerMaxHealth < 0.0D || Double.isNaN(playerMaxHealth)) {
            throw new IllegalArgumentException("playerMaxHealth must be >= 0, got " + playerMaxHealth);
        }
        return perPointDamageFraction(quality) * playerMaxHealth;
    }

    /**
     * 拒绝采样提案预算 (handler 生成的 (角度, 距离) 提案数 = desiredCount × {@value #SCATTER_ATTEMPTS_PER_POINT})。
     *
     * @param desiredCount 期望落点数 (须 ∈ [{@value #MIN_POINTS}, {@value #MAX_POINTS}])
     * @return 提案数
     */
    public static int scatterAttemptBudget(int desiredCount) {
        requirePointCount(desiredCount);
        return desiredCount * SCATTER_ATTEMPTS_PER_POINT;
    }

    /**
     * 单个 (角度, 距离) 提案转落点坐标: 以目标为圆心, 极坐标 (distance, angle) 映射到笛卡尔水平坐标。纯几何。
     *
     * @param centerX  目标 X (散布圆心)
     * @param centerZ  目标 Z
     * @param angleRad 提案角度 (弧度; handler 层随机)
     * @param distance 提案距离 (格; 须 ∈ [{@value #MIN_SCATTER_DISTANCE}, {@value #MAX_SCATTER_DISTANCE}])
     * @return 落点水平坐标
     */
    public static BlastPoint pointAt(double centerX, double centerZ, double angleRad, double distance) {
        requireScatterDistance(distance);
        return new BlastPoint(centerX + distance * Math.cos(angleRad), centerZ + distance * Math.sin(angleRad));
    }

    /**
     * 两落点是否达到最小间距 (间距 &gt;= {@value #MIN_POINT_SEPARATION} 格 = 2×半径 = 不重叠): 用水平距离平方与
     * {@link #MIN_POINT_SEPARATION_SQ} 比较, 免开方。恰好相切 (间距 = 2×半径) 判达标。
     *
     * @return 是否达标 (可共存于同一次天雷)
     */
    public static boolean separated(double ax, double az, double bx, double bz) {
        double dx = bx - ax;
        double dz = bz - az;
        return dx * dx + dz * dz >= MIN_POINT_SEPARATION_SQ;
    }

    /**
     * 一组落点是否两两皆达标 (供 handler 落点集自检 / GameTest 断言散布结果)。空集/单点恒达标 (无两两对)。
     *
     * @param points 落点集
     * @return 是否任意两两间距 &gt;= {@value #MIN_POINT_SEPARATION} 格
     */
    public static boolean allPairsSeparated(List<BlastPoint> points) {
        if (points == null) {
            throw new IllegalArgumentException("points must not be null");
        }
        for (int i = 0; i < points.size(); i++) {
            BlastPoint a = points.get(i);
            for (int j = i + 1; j < points.size(); j++) {
                BlastPoint b = points.get(j);
                if (!separated(a.x(), a.z(), b.x(), b.z())) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 拒绝采样贪心挑落点 (spec"多点分散不可同点叠杀"的落点生成): 按提案序 (角度, 距离) 逐个转坐标, 与已挑出的点
     * 逐一过 {@link #separated} 间距校验, 达标则纳入, 满 desiredCount 即止。提案耗尽仍不足 desiredCount 时放宽到
     * 已挑出的点数 (返回可能少于 desiredCount) —— 确定性、无随机、无死循环 (随机在 handler 层预生成提案喂入)。
     *
     * <p>间距约束保证: 结果两两间距 &gt;= 2×半径, 故半径 {@value #PER_POINT_RADIUS} 的杀伤圈两两至多相切不重叠,
     * 单玩家至多落一点核心圈; 边界 (恰在两切点) 由 handler 逐点结算 + 首点 grant 免疫缓冲兜底 (第二点被掐 0)。
     *
     * @param centerX      散布圆心 X (目标 X)
     * @param centerZ      散布圆心 Z (目标 Z)
     * @param anglesRad    提案角度序列 (弧度; 长度须与 distances 一致)
     * @param distances    提案距离序列 (格; 每个须 ∈ [{@value #MIN_SCATTER_DISTANCE}, {@value #MAX_SCATTER_DISTANCE}])
     * @param desiredCount 期望落点数 (须 ∈ [{@value #MIN_POINTS}, {@value #MAX_POINTS}])
     * @return 挑出的落点集 (两两达标; 可能少于 desiredCount = 放宽)
     */
    public static List<BlastPoint> selectScatterPoints(double centerX, double centerZ,
                                                       double[] anglesRad, double[] distances, int desiredCount) {
        if (anglesRad == null || distances == null) {
            throw new IllegalArgumentException("anglesRad and distances must not be null");
        }
        if (anglesRad.length != distances.length) {
            throw new IllegalArgumentException("anglesRad/distances length mismatch: "
                    + anglesRad.length + " vs " + distances.length);
        }
        requirePointCount(desiredCount);
        for (double distance : distances) {
            requireScatterDistance(distance); // 提案距离须在散布环内 (全量前置校验, 异常必须痛)。
        }

        List<BlastPoint> accepted = new ArrayList<>(desiredCount);
        for (int i = 0; i < anglesRad.length && accepted.size() < desiredCount; i++) {
            BlastPoint candidate = pointAt(centerX, centerZ, anglesRad[i], distances[i]);
            boolean clear = true;
            for (BlastPoint existing : accepted) {
                if (!separated(existing.x(), existing.z(), candidate.x(), candidate.z())) {
                    clear = false; // 贴脸 (间距 < 2×半径): 拒绝, 试下一提案。
                    break;
                }
            }
            if (clear) {
                accepted.add(candidate);
            }
        }
        return accepted;
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

    private static void requireNonNegativeDistance(double distanceSq, String name) {
        if (distanceSq < 0.0D || Double.isNaN(distanceSq)) {
            throw new IllegalArgumentException(name + " must be >= 0, got " + distanceSq);
        }
    }

    private static void requirePointCount(int desiredCount) {
        if (desiredCount < MIN_POINTS || desiredCount > MAX_POINTS) {
            throw new IllegalArgumentException(
                    "desiredCount must be in [" + MIN_POINTS + "," + MAX_POINTS + "], got " + desiredCount);
        }
    }

    private static void requireScatterDistance(double distance) {
        if (distance < MIN_SCATTER_DISTANCE || distance > MAX_SCATTER_DISTANCE || Double.isNaN(distance)) {
            throw new IllegalArgumentException("scatter distance must be in ["
                    + MIN_SCATTER_DISTANCE + "," + MAX_SCATTER_DISTANCE + "], got " + distance);
        }
    }
}
