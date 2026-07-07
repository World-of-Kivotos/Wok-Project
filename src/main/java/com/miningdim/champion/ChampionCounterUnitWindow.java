package com.miningdim.champion;

/**
 * 反击单元 COUNTER_UNIT 反击窗纯逻辑 (ChampionStarAffix spec 7.4 反击单元 + 红线 2 三层封顶第一层)。
 *
 * 反击单元机制: 冠军每 15s (锁定周期) 锁定当前仇恨玩家开 5s 反击窗, 窗内该玩家对本怪的每笔伤害按【反伤比 ×
 * 该笔名义伤害】折成名义反伤打回, 经三层封顶后落地 (真伤口径 champion_thorns, 与反震一致):
 *   第一层 = 本源私有秒窗 ≤20% attacker maxHP/s (本类【实例】自算, 滚动 20tick 窗累计超额剪);
 *   第二/三层 = {@link com.miningdim.champion.aggregate.RetaliationAggregator} 的 30%/s 全局多源 + 40%/窗
 *   (由 integration 层调用聚合器夹, 本类不复制那两层数学)。
 *
 * 本类承载两类纯逻辑, 无世界/实体引用 (只用 {@link AffixDef}/{@link AffixQuality} 纯数据枚举), GameTest 直接
 * 断言 (删被测数值/相位/封顶必挂):
 *   - 静态: 反伤比与名义反伤折算 ({@link #reflectRatio}/{@link #nominalRetaliation})、反击窗相位与锁定周期门槛
 *     ({@link #isWithinWindow}/{@link #lockCycleReady}) + 各时间常量;
 *   - 实例: 一次开窗对应一个被锁 attacker 的【本源私有 20%/s 滚动秒窗累加器】({@link #admit} 夹断第一层)。
 *
 * 分工 (与聚合器单一权威): 本类第一层只夹"本反击源自己的 20%/s", 跨源共享的 30%/s + 40%/窗 一律由
 * {@link com.miningdim.champion.aggregate.RetaliationAggregator} 夹, 本类绝不复制。20% 是反击单元专属次级上限
 * (spec 红线 2 [红队]), 非全局红线, 故常量落本类而非 {@link ChampionRedlines}。
 */
public final class ChampionCounterUnitWindow {

    /** 锁定周期 (tick): 15s; 冠军每周期尝试锁定一次当前仇恨玩家 (有存活玩家目标才推进/尝试)。 */
    public static final long LOCK_CYCLE_TICKS = 300L;

    /** 反击窗时长 (tick): 5s (spec 7.4 窗口 ≤5s); 相位 [0,100) 为窗内。 */
    public static final long WINDOW_TICKS = 100L;

    /** 本源私有秒窗上限占比 (spec 红线 2 [红队]: 反击单元次级每秒 ≤20% attacker maxHP, 即 80 血玩家 16HP/s)。 */
    public static final double SOURCE_PER_SECOND_CAP_PCT = 0.20D;

    /** 秒窗滚动粒度 (tick): 20tick = 1s (与 RetaliationAggregator 同粒度)。 */
    private static final long TICKS_PER_SECOND = 20L;

    private final double perSecondCap;
    private long currentSecondStartTick = Long.MIN_VALUE;
    private double secondAccumulated = 0.0D;

    /**
     * @param attackerMaxHp 被锁玩家开窗时的最大有效血量 (须 &gt;0; 本源私有秒窗上限 = 20% × 此值)
     */
    public ChampionCounterUnitWindow(double attackerMaxHp) {
        if (!(attackerMaxHp > 0.0D) || Double.isNaN(attackerMaxHp)) {
            throw new IllegalArgumentException("attackerMaxHp must be > 0, got " + attackerMaxHp);
        }
        this.perSecondCap = attackerMaxHp * SOURCE_PER_SECOND_CAP_PCT;
    }

    /**
     * 反伤比 (spec 7.4: 40/55/70/85/100% 按品质)。
     *
     * @param quality 反击单元品质
     * @return 反伤比 (∈ [0.40, 1.00])
     */
    public static double reflectRatio(AffixQuality quality) {
        requireQuality(quality);
        return AffixDef.COUNTER_UNIT.valueFor(quality);
    }

    /**
     * 名义反伤 = 反伤比 × 该笔名义入伤 (spec 7.4)。折算的是【名义】值, 须再经第一层私有秒窗 {@link #admit} +
     * 聚合器 30%/s+40%/窗 夹断才是实际反弹量 (本类不夹后两层)。
     *
     * @param quality        反击单元品质
     * @param incomingDamage 该笔名义入伤 (HP; 受击点 event.getAmount(), 须 &gt;=0)
     * @return 名义反伤 HP (&gt;=0; 未经任何封顶)
     */
    public static double nominalRetaliation(AffixQuality quality, double incomingDamage) {
        requireQuality(quality);
        if (incomingDamage < 0.0D || Double.isNaN(incomingDamage)) {
            throw new IllegalArgumentException("incomingDamage must be >= 0, got " + incomingDamage);
        }
        return AffixDef.COUNTER_UNIT.valueFor(quality) * incomingDamage;
    }

    /**
     * 距锁定 tick 数是否在反击窗内 (相位 [0, {@link #WINDOW_TICKS}), 100tick 恰界出窗)。
     *
     * @param ticksSinceLock 距锁定 tick 数 (须 &gt;=0; 锁定在未来属调用方 bug, 抛不掩盖)
     * @return 是否窗内
     */
    public static boolean isWithinWindow(long ticksSinceLock) {
        if (ticksSinceLock < 0L) {
            throw new IllegalArgumentException("ticksSinceLock must be >= 0, got " + ticksSinceLock);
        }
        return ticksSinceLock < WINDOW_TICKS;
    }

    /**
     * 锁定周期是否就绪 (距上次成功锁定 ≥ {@link #LOCK_CYCLE_TICKS}): 就绪才尝试开新窗。lastLockTick =
     * {@link Long#MIN_VALUE} (从未锁定) 视为就绪 (首锁即时)。显式判 MIN_VALUE 防减法溢出。
     *
     * @param nowTick      当前 gameTime tick
     * @param lastLockTick 上次成功锁定 tick (Long.MIN_VALUE = 从未锁定)
     * @return 是否就绪
     */
    public static boolean lockCycleReady(long nowTick, long lastLockTick) {
        if (lastLockTick == Long.MIN_VALUE) {
            return true;
        }
        return nowTick - lastLockTick >= LOCK_CYCLE_TICKS;
    }

    /**
     * 申请反弹一笔名义反伤, 返回经【本源私有 20%/s 滚动秒窗】夹断后的量 (≥0; 0 = 本源本秒额度耗尽)。此为三层封顶
     * 第一层, 夹后仍须过聚合器 30%/s+40%/窗 (integration 层调用)。跨入新 20tick 秒窗则重置秒累计。
     *
     * 累计口径 (与 RetaliationAggregator 一致): 秒累计的是本层【放行】量 (即传给下游聚合器的量), 非最终落地量 ——
     * 下游全局裁剪是共享 30%/s 的职责, 本层只负责"本反击源自己每秒不超 20%"。
     *
     * @param nominal 名义反伤量 (HP; {@link #nominalRetaliation} 折算值, 须 &gt;=0)
     * @param nowTick 当前 gameTime tick
     * @return 经私有秒窗夹断后的量
     */
    public double admit(double nominal, long nowTick) {
        if (nominal < 0.0D || Double.isNaN(nominal)) {
            throw new IllegalArgumentException("nominal retaliation must be >= 0, got " + nominal);
        }
        // 秒窗滚动: 首笔或跨入新 20tick 秒窗则重置秒累计。
        if (currentSecondStartTick == Long.MIN_VALUE || nowTick - currentSecondStartTick >= TICKS_PER_SECOND) {
            currentSecondStartTick = nowTick;
            secondAccumulated = 0.0D;
        }
        double room = perSecondCap - secondAccumulated;
        double allowed = Math.max(0.0D, Math.min(nominal, room));
        secondAccumulated += allowed;
        return allowed;
    }

    /** 本源私有秒窗上限 (= 20% attacker maxHP; 诊断/测试)。 */
    public double perSecondCap() {
        return perSecondCap;
    }

    /** 当前秒窗已放行量 (诊断/测试)。 */
    public double secondAccumulated() {
        return secondAccumulated;
    }

    private static void requireQuality(AffixQuality quality) {
        if (quality == null) {
            throw new IllegalArgumentException("quality must not be null");
        }
    }
}
