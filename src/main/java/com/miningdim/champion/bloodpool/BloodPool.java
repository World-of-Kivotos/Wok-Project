package com.miningdim.champion.bloodpool;

/**
 * 6★+ 自定义血池纯逻辑 (ChampionStarAffix spec 第六章 6.2 / 9A.3 #9 / 第十四章实现拆分 2)。
 *
 * 原版 generic.max_health 钳制在 1024 (RangedAttribute maxValue, javap 核实), 而 6★ 起有效血量已破 1024。
 * 故 6★+ 一律以自定义 double currentHp / maxHp 为权威: 所有扣血、回血、百分比血修正、低血阈值 (嗜血/命定)、
 * 拦死统一只读写本池。vanilla getHealth/max_health 仅作渲染镜像, 严禁业务逻辑读它做判定 (spec 6.2 #2)。
 *
 * 本类是纯数学模型: 不订阅事件、不碰实体、不依赖 Champions —— 受击事件接线 (LivingHurtEvent 单点 + onDeath
 * 拦死 + 渲染镜像 tick 同步) 在 b 阶段, GameTest 直接断言本池数学。可变状态仅 currentHp (受击/回血推进),
 * maxHp 不可变 (spawn 期定)。非线程安全 (单怪服务端 tick 串行)。
 *
 * 拦死单一判据 (spec 6.2 #3): currentHp - 本次伤害 ≤ 0 → 应主动 entity.kill()。本类提供
 * {@link #wouldDieFrom(double)} 判据 + {@link #applyDamage(double)} 扣池, 拦死动作 (entity.kill) 由 b 阶段
 * 受击 handler 据判据执行。回血池内 clamp (spec 6.2 #4): currentHp = min(currentHp + heal, maxHp)。
 */
public final class BloodPool {

    /** 原版 generic.max_health 硬上限 (RangedAttribute maxValue = 1024.0, javap 核实)。渲染镜像换算用。 */
    public static final double VANILLA_MAX_HEALTH_CLAMP = 1024.0D;

    private final double maxHp;
    private double currentHp;

    /**
     * 以满血构造血池 (spawn 期: currentHp = maxHp)。
     *
     * @param maxHp 最大血量 (必须 &gt;0; 6★+ 通常 &gt;1024。非正抛 IllegalArgumentException 不掩盖)
     */
    public BloodPool(double maxHp) {
        this(maxHp, maxHp);
    }

    /**
     * 以指定当前血构造血池 (从 NBT 还原: currentHp 可 &lt; maxHp)。
     *
     * @param maxHp     最大血量 (必须 &gt;0)
     * @param currentHp 当前血量 (必须 &gt;=0 且 &lt;=maxHp; 越界抛 IllegalArgumentException)
     */
    public BloodPool(double maxHp, double currentHp) {
        if (!(maxHp > 0.0D) || Double.isNaN(maxHp) || Double.isInfinite(maxHp)) {
            throw new IllegalArgumentException("maxHp must be a finite positive value, got " + maxHp);
        }
        if (currentHp < 0.0D || currentHp > maxHp || Double.isNaN(currentHp)) {
            throw new IllegalArgumentException(
                    "currentHp must be in [0, maxHp], got " + currentHp + " (maxHp=" + maxHp + ")");
        }
        this.maxHp = maxHp;
        this.currentHp = currentHp;
    }

    /** 最大血量 (不可变)。 */
    public double maxHp() {
        return maxHp;
    }

    /** 当前血量 (权威值; 业务判定一律读此, 不读 vanilla getHealth)。 */
    public double currentHp() {
        return currentHp;
    }

    /** 当前血量占比 [0,1] (低血阈值嗜血/命定按此判定, spec 6.2 #1; 非读 vanilla)。 */
    public double fraction() {
        return currentHp / maxHp;
    }

    /** 是否已死 (currentHp ≤ 0)。 */
    public boolean isDead() {
        return currentHp <= 0.0D;
    }

    /**
     * 拦死判据 (spec 6.2 #3): currentHp - 本次伤害 ≤ 0 → true (应主动 entity.kill())。本判据是单一权威,
     * 不读 vanilla 血。删本判据 → test_kill_precise_at_zero 必挂。
     *
     * @param damage 本次净伤害 (经净减伤钳制后的最终扣血量; 必须 &gt;=0, 负数抛 IllegalArgumentException)
     * @return 本次伤害是否致死
     */
    public boolean wouldDieFrom(double damage) {
        if (damage < 0.0D || Double.isNaN(damage)) {
            throw new IllegalArgumentException("damage must be >= 0, got " + damage);
        }
        return currentHp - damage <= 0.0D;
    }

    /**
     * 扣血 (受击结算单点调用, damage 已经净减伤钳制): currentHp -= damage, 下钳到 0 (不为负, 保渲染镜像非负)。
     * 返回扣血后是否致死 (= {@link #isDead()}), 供 handler 据此 entity.kill()。
     *
     * @param damage 本次净伤害 (必须 &gt;=0)
     * @return 扣血后是否已死
     */
    public boolean applyDamage(double damage) {
        if (damage < 0.0D || Double.isNaN(damage)) {
            throw new IllegalArgumentException("damage must be >= 0, got " + damage);
        }
        currentHp -= damage;
        if (currentHp < 0.0D) {
            currentHp = 0.0D;
        }
        return isDead();
    }

    /**
     * 回血池内 clamp (spec 6.2 #4): currentHp = min(currentHp + heal, maxHp), 不溢出 maxHp。死后不回血
     * (currentHp ≤ 0 时回血无效, 避免"诈尸"回血; 复活是 spawn 期重建血池的职责, 非回血)。
     *
     * @param heal 本次回血量 (heal×dt 已折算; 必须 &gt;=0)
     * @return 回血后的 currentHp
     */
    public double heal(double heal) {
        if (heal < 0.0D || Double.isNaN(heal)) {
            throw new IllegalArgumentException("heal must be >= 0, got " + heal);
        }
        if (isDead()) {
            return currentHp;
        }
        currentHp = Math.min(currentHp + heal, maxHp);
        return currentHp;
    }

    /**
     * vanilla 渲染镜像血量 (spec 6.2 #2): displayHealth = clamp(currentHp / maxHp × 1024, 0, 1024)。
     * 每 tick 由 b 阶段同步给 vanilla getHealth/max_health 供原版血条/客户端渲染, 仅渲染不参与判定。
     *
     * @return 渲染镜像血量 (float, ∈ [0, 1024])
     */
    public float displayHealth() {
        double mirrored = fraction() * VANILLA_MAX_HEALTH_CLAMP;
        if (mirrored < 0.0D) {
            mirrored = 0.0D;
        }
        if (mirrored > VANILLA_MAX_HEALTH_CLAMP) {
            mirrored = VANILLA_MAX_HEALTH_CLAMP;
        }
        return (float) mirrored;
    }
}
