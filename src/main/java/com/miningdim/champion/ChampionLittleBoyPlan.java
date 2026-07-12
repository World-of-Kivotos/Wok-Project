package com.miningdim.champion;

/**
 * 精英怪【技能词条·小男孩 LITTLE_BOY】(Stage2 批4 波2; ChampionStarAffix spec 7.4 一次性核弹) 的触发血线 /
 * 蓄力时长 / 打断门槛 / 边缘衰减 / 档表数值 纯逻辑。把用户与主线的裁定 (背水血线 0.60 / 蓄力 5s / 半径 8 格 /
 * 边缘 50% / 门槛 = 到场玩家数 x 120 且最少计 1 人 / aoePct 档表) 折算成可精确断言的纯函数。
 *
 * 纯函数集合, 不碰世界/实体/Champions/net.minecraft, GameTest 直接断言 (删被测折算/门槛/衰减必挂)。分工: 本类只算
 * "触发血线 boolean + 打断门槛 double + 边缘衰减系数 + 单人 AOE 名义 %maxHP + 半径内判定"; 实际血量占比读取 (血池
 * 优先口径)、蓄力光柱/警告/引爆表现、逐玩家 CHAMPION_SKILL_AOE 结算 + 免疫缓冲授予、起手摘词条 均由 integration 层
 * {@code ChampionLittleBoyHandler} 施加 (真服验)。
 *
 * 一次性核弹语义 (spec 7.4 + 主线裁定): 触发时机 = 自身有效血量占比【首次】&lt;= {@link #TRIGGER_HP_FRACTION}
 * (背水核弹, 玩家可预期); 起手即摘词条 (打断与否都消耗, 防重触发); 蓄力 {@link #CHARGE_TICKS}tick 内玩家对本怪累计
 * 打出 {@link #interruptThreshold} 伤害则打断 (无 AOE), 否则半径 {@link #BLAST_RADIUS} 内玩家逐个吃
 * 基础% x maxHP x {@link #edgeFalloff} 衰减 (中心满、边缘半), 命中后授 2s 免疫缓冲 (红线 3)。
 *
 * 红线自查 (spec 红线 3 / {@link ChampionRedlines#TELEGRAPHED_HIT_CAP_PCT}): 带预兆可躲技能单次名义 %maxHP 硬帽 0.90;
 * 本词条中心满衰减档 = aoePct 最高档 0.85 &lt; 0.90 名义帽 (边缘更低), 结构上不越帽 —— {@code ChampionLittleBoyGameTests}
 * 以红线常量断言此不变量, aoePct 表被上调越帽即挂。
 */
public final class ChampionLittleBoyPlan {

    private ChampionLittleBoyPlan() {
    }

    /** tick/秒 (蓄力秒 -> tick 折算基, 与 handler ServerTick 节流同源)。 */
    public static final long TICKS_PER_SECOND = 20L;

    /**
     * 触发血线 (主线裁定): 自身有效血量占比首次 &lt;= 此值即起手蓄力 (背水核弹, 玩家可预期)。血量占比读法照
     * {@code ChampionAttackHandler.championHpFraction} 的血池优先口径 (6★+ 读影子血池 fraction, 缺池回退 vanilla)。
     */
    public static final double TRIGGER_HP_FRACTION = 0.60D;

    /** 蓄力时长 (tick; 用户裁定 5s = 100tick): 起手到引爆的可打断窗口。 */
    public static final long CHARGE_TICKS = 100L;

    /** AOE 半径 (格; 用户裁定): 引爆时此半径内玩家逐个结算, 半径外不吃伤。 */
    public static final double BLAST_RADIUS = 8.0D;

    /**
     * 边缘衰减深度 (用户裁定"边缘 50%"): 衰减系数 = 1 - 深度 x (距离/半径), 中心 (距离 0) = 1.0 满伤,
     * 边缘 (距离 = 半径) = 1 - 深度 = 0.5 半伤。深度 0.5 即"边缘降到 50%"。
     */
    public static final double EDGE_FALLOFF_DEPTH = 0.5D;

    /** 打断门槛的每玩家系数 (spec 定死): 门槛 = 到场玩家数 x 此值 (蓄力期玩家对本怪累计伤害达此即打断)。 */
    public static final double INTERRUPT_DAMAGE_PER_PLAYER = 120.0D;

    /** 到场玩家数下限 (主线裁定"最少计 1 人"): 门槛按此下限的玩家数计, 无人在场时仍需 1 人份伤害才打断。 */
    public static final int MIN_PLAYER_COUNT = 1;

    /**
     * 是否到达背水触发血线: 有效血量占比 &lt;= {@link #TRIGGER_HP_FRACTION} (含边界)。handler 每秒扫描读血池占比后调此判定,
     * 首次为真即起手 (起手摘词条后不再复判, "首次"语义由 handler 的摘词条落实)。
     *
     * @param hpFraction 有效血量占比 [0,1] (血池优先口径; NaN 抛不掩盖脏值)
     * @return 是否应起手蓄力
     */
    public static boolean shouldTrigger(double hpFraction) {
        if (Double.isNaN(hpFraction)) {
            throw new IllegalArgumentException("hpFraction must not be NaN");
        }
        return hpFraction <= TRIGGER_HP_FRACTION;
    }

    /**
     * 打断门槛 = max({@link #MIN_PLAYER_COUNT}, 到场玩家数) x {@link #INTERRUPT_DAMAGE_PER_PLAYER}
     * (spec 定死 + 主线裁定最少计 1 人)。1/2/3 人 = 120/240/360; 无人 (0 或负, 理论不出现) 按 1 人计 = 120。
     *
     * @param nearbyPlayers 起手瞬间半径内存活玩家数 (handler 侧世界查询; 负值/0 由下限钳)
     * @return 蓄力期玩家须对本怪累计打出的打断伤害门槛
     */
    public static double interruptThreshold(int nearbyPlayers) {
        int counted = Math.max(MIN_PLAYER_COUNT, nearbyPlayers);
        return counted * INTERRUPT_DAMAGE_PER_PLAYER;
    }

    /**
     * 蓄力期是否已被打断: 玩家对本怪累计伤害 &gt;= 门槛 (含边界即打断)。删本判据打断永不触发, 核弹变不可躲。
     *
     * @param accumulatedPlayerDamage 蓄力期玩家来源累计伤害 (名义入伤口径, handler 侧累加)
     * @param threshold               {@link #interruptThreshold} 门槛
     * @return 是否达打断门槛
     */
    public static boolean isInterrupted(double accumulatedPlayerDamage, double threshold) {
        return accumulatedPlayerDamage >= threshold;
    }

    /** 是否在爆炸半径内 (距离 &lt;= {@link #BLAST_RADIUS}, 含边界): 半径外玩家不吃 AOE。 */
    public static boolean withinBlast(double distance) {
        return distance <= BLAST_RADIUS;
    }

    /**
     * 边缘衰减系数 (用户裁定精确表): 中心 (0 格) = 1.0, 半径中点 (4 格) = 0.75, 边缘 (8 格) = 0.5。
     * 公式 = 1 - {@link #EDGE_FALLOFF_DEPTH} x (clamp(距离,0,半径)/半径); 距离超半径按边缘 0.5 钳
     * (防御, handler 只对半径内玩家调此), 负距离不合法抛。
     *
     * @param distance 玩家到冠军的距离 (格; &gt;=0)
     * @return 衰减系数 [0.5, 1.0]
     */
    public static double edgeFalloff(double distance) {
        if (distance < 0.0D || Double.isNaN(distance)) {
            throw new IllegalArgumentException("distance must be >= 0, got " + distance);
        }
        double clamped = Math.min(distance, BLAST_RADIUS);
        return 1.0D - EDGE_FALLOFF_DEPTH * (clamped / BLAST_RADIUS);
    }

    /**
     * 该品质档的 AOE 基础名义 %maxHP (= {@link AffixDef#LITTLE_BOY} 主数值表 {0,0,0,0.70,0.85}): 仅超凡/闪耀有意义
     * (0.70/0.85), 前三档 0 占位 (词条最低超凡, 不该在低档装配)。
     *
     * @param quality 小男孩品质
     * @return 基础名义 %maxHP (中心满衰减前)
     */
    public static double aoePct(AffixQuality quality) {
        if (quality == null) {
            throw new IllegalArgumentException("quality must not be null");
        }
        return AffixDef.LITTLE_BOY.valueFor(quality);
    }

    /**
     * 单个玩家的 AOE 名义扣血 = {@link #aoePct} x 玩家 maxHP x {@link #edgeFalloff}(距离)。CHAMPION_SKILL_AOE 名义值,
     * 吃玩家护甲 (不入 bypasses_armor) 后实付, 由 handler 下发。
     *
     * @param quality     小男孩品质
     * @param playerMaxHp 该玩家最大血量 (&gt;0)
     * @param distance    该玩家到冠军距离 (格; &gt;=0)
     * @return AOE 名义扣血
     */
    public static double blastDamage(AffixQuality quality, double playerMaxHp, double distance) {
        if (!(playerMaxHp > 0.0D) || Double.isNaN(playerMaxHp)) {
            throw new IllegalArgumentException("playerMaxHp must be > 0, got " + playerMaxHp);
        }
        return aoePct(quality) * playerMaxHp * edgeFalloff(distance);
    }
}
