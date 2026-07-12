package com.miningdim.champion;

import java.util.ArrayList;
import java.util.List;

/**
 * 精英怪【机动词条·战术传送 TACTICAL_BLINK】(批4 波1; ChampionStarAffix spec 7.2 脱离型) 的数值折算 + 落点几何
 * 纯逻辑。战术传送与闪光 (BLINK) 相反: 闪光抵近玩家, 战术传送【离开】玩家方向短瞬移 4-8 格拉开身位 (脱离型)。
 *
 * 本类把 {@link AffixDef#TACTICAL_BLINK} 品质档折算成: 施放周期 (tick) + "扫描步进累加 -> 到点判定"的周期状态推进,
 * 并给出脱离落点的几何: 沿【玩家 -> 冠军 延长线】(远离玩家) 4-8 格逐档取候选 + 左右 30 度扇形摆动, 供 handler
 * 逐个过 KnockbackSafetyGuard。此外承载两条硬判定: 受击应激资格 (内 CD 冷却过半才允许受击触发, 防每击刷成永动)
 * 与脱离约束 (候选距玩家不得比冠军当前更近, 守住"脱离"语义)。
 *
 * 纯函数集合, 不碰世界/实体/Champions/net.minecraft (落点以裸 double 坐标表达, {@link Landing} 由 handler 转
 * BlockPos 再喂守卫), GameTest 直接断言 (删被测折算/几何/边界必挂)。分工: 本类只算 "周期 tick + 是否到点 + 受击
 * 资格 + 候选落点坐标 + 脱离约束 + 缰绳边界"; 实际瞬移 (entity.teleportTo)、落点安全裁决 (KnockbackSafetyGuard)、
 * 两端粒子/传送音由 integration 层 {@code ChampionTacticalBlinkHandler} 施加 (真服验)。
 */
public final class ChampionTacticalBlinkPlan {

    private ChampionTacticalBlinkPlan() {
    }

    /** tick/秒 (周期秒表 → tick 折算基)。 */
    public static final long TICKS_PER_SECOND = 20L;

    /**
     * 扫描/周期步进粒度 (tick): handler 每 1s 扫一次近玩家冠军, 有存活目标【且在缰绳内】的扫描把循环推进一个此
     * 粒度 (与 {@code ChampionTacticalBlinkHandler} 的 ServerTick 节流对齐; 与视觉干扰/反击单元同 1s 节奏)。
     */
    public static final long SCAN_INTERVAL_TICKS = 20L;

    /**
     * 施放周期表 tick (品质 普通/中级/高级/超凡/闪耀 = 8/7/6/5/4 s = 160/140/120/100/80 tick)。与
     * {@link AffixDef#TACTICAL_BLINK} 的数值数组 (周期秒) 一致, 索引 = {@link AffixQuality#valueIndex()}。高品质
     * 更频繁脱离。
     */
    private static final long[] CYCLE_PERIOD_TICKS = {160L, 140L, 120L, 100L, 80L};

    /** 缰绳半径 (格): 冠军当前攻击目标距离 &lt;= 此值才推进周期计时, 超出冻结不耗周期 (波1 用户裁定 24 格)。 */
    public static final double TETHER_RANGE = 24.0D;

    /** 缰绳半径平方 (格²): 与 {@code entity.distanceToSqr} 同量纲, 免开方。 */
    public static final double TETHER_RANGE_SQ = TETHER_RANGE * TETHER_RANGE;

    /** 脱离瞬移最小档距离 (格)。 */
    public static final int MIN_BLINK_DISTANCE = 4;

    /** 脱离瞬移最大档距离 (格)。 */
    public static final int MAX_BLINK_DISTANCE = 8;

    /**
     * 距离候选档 (格): 远离方向逐档取, 由远到近 —— 脱离型优先取最大身位差 (拉得越开越好), 远档被墙/危险挡住再
     * 回退到近档。8/7/6/5/4 五档。
     */
    private static final int[] DISTANCE_STEPS = {8, 7, 6, 5, 4};

    /**
     * 扇形摆动角 (度): 沿远离方向的正前方优先 (0), 被挡再左右各 30 度找开口 (波1 用户裁定 30 度扇形)。绕竖直 Y 轴
     * 水平旋转 (脱离是水平拉开身位, 竖直分量由守卫向下找落脚面兜底)。
     */
    private static final double[] SWING_DEGREES = {0.0D, 30.0D, -30.0D};

    /** 水平方向退化阈值: 冠军与玩家水平投影几乎重合 (正上/正下方) 时无远离方向可算, 回退 +X。 */
    private static final double HORIZONTAL_EPSILON = 1.0e-6D;

    /**
     * 单个脱离落点候选 (裸世界坐标; handler 转 BlockPos 再喂 KnockbackSafetyGuard)。y 恒取冠军当前脚下 Y ——
     * 脱离是水平拉开, 落脚高度由守卫沿该柱向下扫定。
     */
    public record Landing(double x, double y, double z) {
    }

    /**
     * 该品质施放周期 (tick; 见 {@link #CYCLE_PERIOD_TICKS} 表: 160/140/120/100/80)。
     *
     * @param quality 战术传送品质
     * @return 周期 tick (&gt;0)
     */
    public static long cycleTicks(AffixQuality quality) {
        requireQuality(quality);
        return CYCLE_PERIOD_TICKS[quality.valueIndex()];
    }

    /**
     * 推进循环一个扫描粒度 (handler 仅在【有存活目标且在缰绳内】的扫描 tick 调用; 无目标/超缰绳不调 = 不推进 =
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
     * 周期是否到点 (已累加 ≥ 该品质周期): 到点则 handler 尝试一次脱离并把累加清零重计 (无论是否找到安全落点,
     * 周期照走不补偿, 单一权威在 handler)。
     *
     * @param elapsedTicks 已累加循环 tick (须 &gt;=0)
     * @param quality      战术传送品质
     * @return 是否到点
     */
    public static boolean cycleReady(long elapsedTicks, AffixQuality quality) {
        requireNonNegative(elapsedTicks);
        requireQuality(quality);
        return elapsedTicks >= cycleTicks(quality);
    }

    /**
     * 受击应激资格 (内 CD 冷却过半判定): 冠军受玩家直接伤害时, 仅当【当前累加周期已过半】才允许受击触发脱离 ——
     * 与周期到点共用同一内 CD ({@code elapsedTicks}), 触发后由 handler 清零重计, 故连续挨打不会把脱离刷成每击
     * 一次的永动 (spec 7.2 "受击或周期到点"; 波1 "两路共用内 CD 不因受击刷成永动")。半程判定用 ×2 比较避分数取整。
     *
     * @param elapsedTicks 已累加循环 tick (须 &gt;=0; = 距上次脱离的冷却进度)
     * @param quality      战术传送品质
     * @return 冷却是否已过半 (允许受击触发)
     */
    public static boolean hitStressEligible(long elapsedTicks, AffixQuality quality) {
        requireNonNegative(elapsedTicks);
        requireQuality(quality);
        return elapsedTicks * 2L >= cycleTicks(quality);
    }

    /**
     * 冠军当前攻击目标是否在缰绳内 (推进周期的前置门控): 距离平方 &lt;= {@value #TETHER_RANGE} 格² 才算在内。超出
     * 则 handler 冻结周期不推进 (spec 波1 缰绳: 超出冻结不耗周期)。
     *
     * @param distanceSq 冠军到目标玩家的距离平方 (须 &gt;=0)
     * @return 是否在缰绳内
     */
    public static boolean withinTether(double distanceSq) {
        if (distanceSq < 0.0D || Double.isNaN(distanceSq)) {
            throw new IllegalArgumentException("distanceSq must be >= 0, got " + distanceSq);
        }
        return distanceSq <= TETHER_RANGE_SQ;
    }

    /**
     * 脱离落点候选序列 (远离玩家方向 4-8 格 + 左右 30 度扇形摆动)。构造: 以【玩家 -> 冠军】水平方向为远离基准
     * (延长线, 越走越远离玩家), 绕竖直 Y 轴取 0/+30/-30 度三个扇向, 每个扇向按 8/7/6/5/4 格逐档取候选, 共 15 个,
     * y 恒取冠军当前脚下 Y。偏好序: 正前方 (0 度) 优先 (最纯粹的脱离方向), 每扇向内由远到近 (身位差越大越好)。
     *
     * <p>几何保证: 每个候选到冠军的水平位移恰 = 档距 (扇向单位向量 × 档距), 且因摆动角 &lt;90 度、远离基准点积
     * 非负, 候选恒比冠军当前更远离玩家 (脱离约束天然成立)。handler 仍对守卫返回的落点复核 {@link #isDisengaging}
     * 作硬闸 (守卫语义若演进/落点被夹回近处则挡下)。
     *
     * @param playerX 玩家 X (远离基准起点)
     * @param playerY 玩家 Y
     * @param playerZ 玩家 Z
     * @param champX  冠军当前 X (瞬移起点)
     * @param champY  冠军当前 Y (落点 Y 取此值)
     * @param champZ  冠军当前 Z
     * @return 15 个候选落点 (偏好序; 不可变)
     */
    public static List<Landing> candidates(double playerX, double playerY, double playerZ,
                                           double champX, double champY, double champZ) {
        // 远离方向 = 玩家 -> 冠军 的水平投影 (延长线方向)。竖直分量不参与 (脱离是水平拉开, 落脚 Y 由守卫定)。
        double ax = champX - playerX;
        double az = champZ - playerZ;
        double horizLen = Math.sqrt(ax * ax + az * az);
        double ux;
        double uz;
        if (horizLen < HORIZONTAL_EPSILON) {
            // 冠军几乎在玩家正上/正下方: 无远离水平方向可算, 回退 +X (仍拉开身位, 恒比重合态更远)。
            ux = 1.0D;
            uz = 0.0D;
        } else {
            ux = ax / horizLen;
            uz = az / horizLen;
        }

        List<Landing> result = new ArrayList<>(SWING_DEGREES.length * DISTANCE_STEPS.length);
        for (double swingDeg : SWING_DEGREES) {
            double rad = Math.toRadians(swingDeg);
            double cos = Math.cos(rad);
            double sin = Math.sin(rad);
            // 绕竖直 Y 轴旋转水平单位向量 (ux,uz): 标准 2D 旋转。
            double rx = ux * cos - uz * sin;
            double rz = ux * sin + uz * cos;
            for (int d : DISTANCE_STEPS) {
                result.add(new Landing(champX + rx * d, champY, champZ + rz * d));
            }
        }
        return result;
    }

    /**
     * 脱离约束 (候选距玩家 &gt; 冠军当前距玩家): 硬守"脱离"语义 —— 落点必须比冠军现在更远离玩家, 否则不是"离开"。
     * handler 对守卫裁定的最终落点复核本判定, 更近的落点一律拒 (纵使 {@link #candidates} 几何已保证, 仍作防御闸)。
     * 距离比较用平方避开方。
     *
     * @param playerX 玩家 X
     * @param playerY 玩家 Y
     * @param playerZ 玩家 Z
     * @param champX  冠军当前 X
     * @param champY  冠军当前 Y
     * @param champZ  冠军当前 Z
     * @param landingX 候选落点 X
     * @param landingY 候选落点 Y
     * @param landingZ 候选落点 Z
     * @return 落点是否严格比冠军当前更远离玩家
     */
    public static boolean isDisengaging(double playerX, double playerY, double playerZ,
                                        double champX, double champY, double champZ,
                                        double landingX, double landingY, double landingZ) {
        double currentSq = distanceSq(playerX, playerY, playerZ, champX, champY, champZ);
        double landingSq = distanceSq(playerX, playerY, playerZ, landingX, landingY, landingZ);
        return landingSq > currentSq;
    }

    private static double distanceSq(double ax, double ay, double az, double bx, double by, double bz) {
        double dx = bx - ax;
        double dy = by - ay;
        double dz = bz - az;
        return dx * dx + dy * dy + dz * dz;
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
