package com.miningdim.champion;

import java.util.ArrayList;
import java.util.List;

/**
 * 精英怪【技能词条·利刃华尔兹 BLADE_WALTZ】(批4 波2; ChampionStarAffix spec 7.4 连段突袭技能) 的数值折算 +
 * 落点环几何 + 门控/中止真值 纯逻辑。利刃华尔兹 = 起手预兆后, 冠军对锁定目标做 N 段瞬移突袭, 每段瞬到目标旁一击,
 * 整套总伤恰压在红线 3 连段帽 {@value ChampionRedlines#COMBO_TOTAL_CAP_PCT} × maxHP (每击 = 该帽 / 突袭次数 均分)。
 *
 * 本类把 {@link AffixDef#BLADE_WALTZ} 品质档 (突袭次数 3/4/5/6/7) 折算成: 每档突袭次数 + 每击名义 %maxHP (连段帽
 * 均分) + 每击名义伤害值, 并承载连段的时序常量 (段间隔 10t / CD 600t / 预兆 30t) + 两条硬门控真值 (CD 就绪 + 缰绳
 * 24 格) + 一条中止真值 (冠军距目标 &gt;12 格中止剩余突袭), 以及每段落点候选的环几何 (目标旁 1.5-2 格双环 × 8 向)。
 *
 * 纯函数集合, 不碰世界/实体/Champions/net.minecraft (落点以裸 double 坐标表达, {@link Landing} 由 handler 转
 * BlockPos 再喂守卫), GameTest 直接断言 (删被测折算/几何/门控/中止必挂)。分工: 本类只算 "突袭次数 + 每击伤 +
 * 时序常量 + 门控/中止 boolean + 候选落点坐标"; 实际瞬移 (mob.teleportTo)、落点安全裁决
 * ({@code KnockbackSafetyGuard})、每击 SKILL_AOE 伤下发、预兆/挥砍表现由 integration 层
 * {@code ChampionBladeWaltzHandler} 施加 (真服验)。
 *
 * 连段帽守恒 (红线 3): 每击 = {@code COMBO_TOTAL_CAP_PCT / N}, 整套 N 击合计恒 = 连段帽 (浮点误差 &lt;1e-9);
 * 任一段落点全拒被跳过则整套实付 &lt; 帽 (跳过不结算), 故实付恒 &le; 帽, 绝不超 60% maxHP 一套删人。
 */
public final class ChampionBladeWaltzPlan {

    private ChampionBladeWaltzPlan() {
    }

    /** tick/秒 (秒表 -> tick 折算基)。 */
    public static final long TICKS_PER_SECOND = 20L;

    /** 段间隔 (tick): 相邻两段突袭间隔 0.5s = 10 tick (用户裁定, 全档一致)。 */
    public static final long STRIKE_INTERVAL_TICKS = 10L;

    /** 施放 CD (tick): 一套连段结束后 30s = 600 tick 冷却 (用户裁定 CD 全档一致, 无品质差)。 */
    public static final long COOLDOWN_TICKS = 600L;

    /** 起手预兆时长 (tick): 突袭序列前 1.5s = 30 tick 预兆窗 (自身高亮 + 目标警告 + 磨刀音, 给玩家反制窗)。 */
    public static final long TELEGRAPH_TICKS = 30L;

    /**
     * 扫描/CD 就绪判定周期 (tick): handler 每 1s 扫一次近玩家冠军, CD 就绪 + 缰绳内则起手 (与
     * {@code ChampionBladeWaltzHandler} 的 ServerTick 节流对齐; 预兆推进/突袭执行走 tick 级精度不受此粒度约束)。
     */
    public static final long SCAN_INTERVAL_TICKS = 20L;

    /** 缰绳半径 (格): CD 就绪后, 目标距冠军 &lt;= 此值才起手连段 (主线拍板 24 格门控)。 */
    public static final double TETHER_RANGE = 24.0D;

    /** 缰绳半径平方 (格²): 与 {@code entity.distanceToSqr} 同量纲, 免开方。 */
    public static final double TETHER_RANGE_SQ = TETHER_RANGE * TETHER_RANGE;

    /** 中止半径 (格): 突袭序列进行中, 冠军距目标 &gt; 此值即中止剩余突袭 (主线拍板 12 格; 目标甩脱是合法反制)。 */
    public static final double ABORT_RANGE = 12.0D;

    /** 中止半径平方 (格²): 与 {@code entity.distanceToSqr} 同量纲, 免开方。 */
    public static final double ABORT_RANGE_SQ = ABORT_RANGE * ABORT_RANGE;

    /**
     * 落点环半径档 (格; spec"目标旁 1.5-2 格环"): 每段在目标周围此两档半径 × {@value #RING_ANGLE_COUNT} 向取候选。
     * 偏好序: 先取外档 2.0 (与目标留出身位, 少插进玩家碰撞箱), 外档全被 hazard/墙挡再退内档 1.5。
     */
    private static final double[] RING_RADII = {2.0D, 1.5D};

    /** 落点环角度档数: 8 向 (每 45°) 均布, 与两档半径组合成 {@value #RING_ANGLE_COUNT}×2 = 16 候选。 */
    private static final int RING_ANGLE_COUNT = 8;

    /**
     * 单个突袭落点候选 (裸世界坐标; handler 转 BlockPos 再喂 {@code KnockbackSafetyGuard.evaluateLanding})。
     * y 恒取目标当前脚下 Y —— 冠军瞬到目标同层的相邻格, 落脚高度由守卫沿该柱向下扫定。
     */
    public record Landing(double x, double y, double z) {
    }

    /**
     * 该品质的突袭次数 N (spec 7.4: 3/4/5/6/7 = 普通/中级/高级/超凡/闪耀)。直接读 {@link AffixDef#BLADE_WALTZ} 数值表
     * 取整 (单一权威, 不复制一份魔数副本; 与 {@code ChampionSummonPlan#summonCount} 同法)。
     *
     * @param quality 利刃华尔兹品质
     * @return 突袭次数 (3..7)
     */
    public static int strikeCount(AffixQuality quality) {
        requireQuality(quality);
        return (int) Math.round(AffixDef.BLADE_WALTZ.valueFor(quality));
    }

    /**
     * 每击名义 %maxHP = 连段帽 {@value ChampionRedlines#COMBO_TOTAL_CAP_PCT} / 突袭次数 N (整套均分)。品质越高 N 越大,
     * 单击越轻但击数越多, 整套合计恒 = 连段帽 (见类注连段帽守恒)。引用红线常量 = 60% 单一权威, 不落魔数副本。
     *
     * @param quality 利刃华尔兹品质
     * @return 每击名义 %maxHP (∈ (0, 0.20])
     */
    public static double perStrikePct(AffixQuality quality) {
        requireQuality(quality);
        return ChampionRedlines.COMBO_TOTAL_CAP_PCT / strikeCount(quality);
    }

    /**
     * 每击名义伤害 = {@link #perStrikePct} × 目标 maxHP (handler 以此值走 CHAMPION_SKILL_AOE 源下发, 吃玩家护甲)。
     *
     * @param quality          利刃华尔兹品质
     * @param targetMaxHealth  目标玩家最大生命 (须 &gt;0)
     * @return 每击名义伤害值
     */
    public static double perStrikeDamage(AffixQuality quality, double targetMaxHealth) {
        requireQuality(quality);
        if (!(targetMaxHealth > 0.0D) || Double.isNaN(targetMaxHealth)) {
            throw new IllegalArgumentException("targetMaxHealth must be > 0, got " + targetMaxHealth);
        }
        return perStrikePct(quality) * targetMaxHealth;
    }

    /**
     * CD 是否就绪 (距上次连段结束已过 {@value #COOLDOWN_TICKS}tick)。{@code Long.MIN_VALUE} = 从未施放, 立即就绪
     * (显式判防 nowTick - MIN_VALUE 溢出)。
     *
     * @param nowTick          当前 gameTime tick
     * @param lastComboEndTick 上次连段结束 tick (MIN_VALUE = 从未施放)
     * @return CD 是否就绪
     */
    public static boolean cooldownReady(long nowTick, long lastComboEndTick) {
        if (lastComboEndTick == Long.MIN_VALUE) {
            return true;
        }
        return nowTick - lastComboEndTick >= COOLDOWN_TICKS;
    }

    /**
     * 目标是否在缰绳内 (起手前置门控): 冠军到目标距离平方 &lt;= {@value #TETHER_RANGE} 格² 才起手。超出则本次不起手
     * (CD 保持就绪, 待目标进缰绳)。
     *
     * @param distanceSq 冠军到目标玩家的距离平方 (须 &gt;=0)
     * @return 是否在缰绳内
     */
    public static boolean withinTether(double distanceSq) {
        requireNonNegativeDistance(distanceSq);
        return distanceSq <= TETHER_RANGE_SQ;
    }

    /**
     * 突袭序列是否应中止 (冠军距目标 &gt; {@value #ABORT_RANGE} 格): 每段执行前 handler 以此判目标是否已甩脱。严格
     * 大于 (距² &gt; {@value #ABORT_RANGE_SQ}) 才中止, 恰 12 格 (距²=144) 仍在追击范围内不中止。
     *
     * @param distanceSq 冠军到目标玩家的距离平方 (须 &gt;=0)
     * @return 是否应中止剩余突袭
     */
    public static boolean shouldAbort(double distanceSq) {
        requireNonNegativeDistance(distanceSq);
        return distanceSq > ABORT_RANGE_SQ;
    }

    /**
     * 单段落点候选序列 (目标旁 1.5-2 格双环 × 8 向 = 16 候选)。每段 handler 以【当前目标位置】为环心调本法 (段间目标
     * 移动 = 环心随之移动, 逐段重选), 逐候选过 {@code KnockbackSafetyGuard.evaluateLanding} 求首个安全落点; 全拒则该
     * 段跳过 (不结算, 序列继续)。偏好序: 先外档 2.0 全 8 向, 再内档 1.5 全 8 向; 每档角度自 +X (0°) 起逆时针 45° 步进。
     *
     * <p>几何保证: 每候选到目标的水平位移恰 = 其环半径 (∈{1.5, 2.0}), y 恒取目标脚下 Y。
     *
     * @param targetX 目标 X (环心)
     * @param targetY 目标 Y (候选 Y 取此值)
     * @param targetZ 目标 Z (环心)
     * @return 16 个候选落点 (偏好序; 不可变长度)
     */
    public static List<Landing> strikeCandidates(double targetX, double targetY, double targetZ) {
        List<Landing> result = new ArrayList<>(RING_RADII.length * RING_ANGLE_COUNT);
        for (double radius : RING_RADII) {
            for (int i = 0; i < RING_ANGLE_COUNT; i++) {
                double angle = 2.0D * Math.PI * i / RING_ANGLE_COUNT;
                double x = targetX + radius * Math.cos(angle);
                double z = targetZ + radius * Math.sin(angle);
                result.add(new Landing(x, targetY, z));
            }
        }
        return result;
    }

    private static void requireQuality(AffixQuality quality) {
        if (quality == null) {
            throw new IllegalArgumentException("quality must not be null");
        }
    }

    private static void requireNonNegativeDistance(double distanceSq) {
        if (distanceSq < 0.0D || Double.isNaN(distanceSq)) {
            throw new IllegalArgumentException("distanceSq must be >= 0, got " + distanceSq);
        }
    }
}
