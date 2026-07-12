package com.miningdim.champion;

/**
 * 精英怪【自身被动词条】(Stage2 批1) 的数值折算 + 触发门槛纯逻辑 (ChampionStarAffix spec 7.1 再生组织/易燃再生/
 * 反震 + 7.3 高速移动)。把 {@link AffixDef} 档位数值折算成: 自身每秒回血量 (脱战/战斗)、反震反伤名义量、移速加成,
 * 并给出回血/反伤的时间门槛判定 (脱战窗/受伤停回窗/反伤内 CD)。
 *
 * 纯函数集合, 不碰世界/实体/Champions, GameTest 直接断言 (删被测折算/门槛必挂)。分工: 本类只算"数值 + 是否到
 * 触发门槛"; 实际回血 (血池 {@link com.miningdim.champion.bloodpool.BloodPool}#heal / vanilla heal)、反伤 (经
 * {@link com.miningdim.champion.aggregate.RetaliationAggregator} 秒窗/窗口封顶后打回攻击者)、移速修饰 (MOVEMENT_SPEED
 * 瞬态 modifier) 由 integration 层 {@code ChampionSelfEffectHandler} 施加 (真服验)。
 *
 * 反震反伤红线 (spec 红线 2): 本类只算"名义反伤量 = 反伤% × 攻击者 maxHP"(锁定攻击者 maxHP 为基数, 绝不按收到
 * 伤害的 % 反弹); 30%/s + 40%/窗 的多源累加封顶由 {@link com.miningdim.champion.aggregate.RetaliationAggregator}
 * 单一权威夹断, 本类不复制。
 */
public final class ChampionSelfBuffValues {

    private ChampionSelfBuffValues() {
    }

    /** 再生组织脱战判定窗 (spec 7.1: 任意受伤重置 5s -> 100 tick 无伤才回, 实战不触发只惩罚脱离/翻盘)。 */
    public static final long REGEN_TISSUE_OUT_OF_COMBAT_TICKS = 100L;

    /** 易燃再生受伤停回窗 (spec 7.1 [红队] off-switch: 受任意伤害停回 1.5s -> 30 tick)。 */
    public static final long FLAMMABLE_REGEN_PAUSE_TICKS = 30L;

    /** 反震反伤内 CD (spec 7.1 [红队]: 内 CD 1.5s->≥3s -> 60 tick; 逐击反伤被此闸挡)。 */
    public static final long THORNS_INTERNAL_CD_TICKS = 60L;

    /** 自身回血结算周期 (tick): 回血按 1s = 20tick 结算一次, 每次施加一整秒名义回血 (与 handler 扫描节流对齐)。 */
    public static final long HEAL_TICK_INTERVAL = 20L;

    /**
     * 再生组织每秒回血 (spec 7.1: 脱战回 3/4/5/6/8% maxHP/s)。按有效最大血量折 HP (血池 maxHp / 1-5★ vanilla
     * maxHealth 由 handler 取)。仅脱战 ({@link #isOutOfCombat}) 时由 handler 施加。
     *
     * @param quality        再生组织品质
     * @param effectiveMaxHp 冠军有效最大血量 (&gt;0; 6★+ 血池 maxHp / 其余 vanilla maxHealth)
     * @return 每秒回血 HP (&gt;=0)
     */
    public static double regenTissueHealPerSecond(AffixQuality quality, double effectiveMaxHp) {
        requireQuality(quality);
        requirePositive(effectiveMaxHp, "effectiveMaxHp");
        return AffixDef.REGEN_TISSUE.valueFor(quality) * effectiveMaxHp;
    }

    /**
     * 易燃再生每秒回血 (spec 7.1: 战斗回 FLAT 8/15/30/60/90 HP/s)。FLAT HP (不随血量缩放)。仅距上次受伤 ≥1.5s
     * ({@link #flammableRegenReady}) 时由 handler 施加 (受伤停回 off-switch)。
     *
     * @param quality 易燃再生品质
     * @return 每秒回血 HP (FLAT; &gt;=0)
     */
    public static double flammableRegenHealPerSecond(AffixQuality quality) {
        requireQuality(quality);
        return AffixDef.FLAMMABLE_REGEN.valueFor(quality);
    }

    /**
     * 高速移动移速加成系数 (spec 7.3: +移速 10/15/22/30/40%)。作为 MOVEMENT_SPEED 的 MULTIPLY_TOTAL modifier 系数
     * 由 handler 挂上 (瞬态)。spec 红线"硬钳结果移速 ≤玩家疾跑速度"属真服手感标定, 批1先按名义系数施加待真服调。
     *
     * @param quality 高速移动品质
     * @return 移速加成系数 (∈ [0.10, 0.40])
     */
    public static double sprintSpeedBonus(AffixQuality quality) {
        requireQuality(quality);
        return AffixDef.SPRINT.valueFor(quality);
    }

    /**
     * 反震反伤名义量 (spec 7.1 反震 + 红线 2): 反伤% × 攻击者 maxHP (锁定攻击者 maxHP 为基数)。返回的是【名义】
     * 反伤, 须再经 {@link com.miningdim.champion.aggregate.RetaliationAggregator#admit} 的 30%/s + 40%/窗 封顶夹断
     * 才是实际反弹量 (本类不夹, 单一权威在聚合器)。
     *
     * @param quality       反震品质
     * @param attackerMaxHp 攻击者 (玩家) 有效最大血量 (&gt;0; 反伤%的基数)
     * @return 名义反伤 HP (&gt;=0; 未经秒窗/窗口封顶)
     */
    public static double thornsReflectRaw(AffixQuality quality, double attackerMaxHp) {
        requireQuality(quality);
        requirePositive(attackerMaxHp, "attackerMaxHp");
        return AffixDef.THORNS.valueFor(quality) * attackerMaxHp;
    }

    /**
     * 是否脱战 (距上次受伤 ≥5s): 再生组织触发门槛。lastHurtTick = {@link Long#MIN_VALUE} (从未受伤) 视为脱战。
     *
     * @param nowTick      当前 gameTime tick
     * @param lastHurtTick 上次受伤 tick (Long.MIN_VALUE = 从未受伤)
     * @return 是否已脱战
     */
    public static boolean isOutOfCombat(long nowTick, long lastHurtTick) {
        return elapsedAtLeast(nowTick, lastHurtTick, REGEN_TISSUE_OUT_OF_COMBAT_TICKS);
    }

    /**
     * 易燃再生是否可回 (距上次受伤 ≥1.5s): off-switch 门槛。lastHurtTick = Long.MIN_VALUE 视为可回。
     *
     * @param nowTick      当前 gameTime tick
     * @param lastHurtTick 上次受伤 tick
     * @return 是否可回
     */
    public static boolean flammableRegenReady(long nowTick, long lastHurtTick) {
        return elapsedAtLeast(nowTick, lastHurtTick, FLAMMABLE_REGEN_PAUSE_TICKS);
    }

    /**
     * 反震是否过内 CD (距上次反伤 ≥3s): 逐击反伤限频门槛。lastThornsTick = Long.MIN_VALUE (从未反伤) 视为就绪。
     *
     * @param nowTick        当前 gameTime tick
     * @param lastThornsTick 上次反伤 tick
     * @return 是否过内 CD
     */
    public static boolean thornsReady(long nowTick, long lastThornsTick) {
        return elapsedAtLeast(nowTick, lastThornsTick, THORNS_INTERNAL_CD_TICKS);
    }

    /** lastTick=Long.MIN_VALUE (从未) 恒 true; 否则 nowTick-lastTick ≥ window。显式判 MIN_VALUE 防减法溢出。 */
    private static boolean elapsedAtLeast(long nowTick, long lastTick, long window) {
        if (lastTick == Long.MIN_VALUE) {
            return true;
        }
        return nowTick - lastTick >= window;
    }

    private static void requireQuality(AffixQuality quality) {
        if (quality == null) {
            throw new IllegalArgumentException("quality must not be null");
        }
    }

    private static void requirePositive(double v, String name) {
        if (!(v > 0.0D) || Double.isNaN(v)) {
            throw new IllegalArgumentException(name + " must be > 0, got " + v);
        }
    }
}
