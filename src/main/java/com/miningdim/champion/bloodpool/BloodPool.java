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
 * 拦死单一判据 (spec 6.2 #3): currentHp - 本次伤害 ≤ 0 → 应扣池到 0 + 摘池放行, 交外层 vanilla hurt() 尾部的
 * die() 驱动真死流程 (F101: 不再由本层调 entity.kill() 主动触发, 因 setHealth(0) 已使该调用恒为 no-op)。
 * 本类提供 {@link #wouldDieFrom(double)} 判据 + {@link #applyDamage(double)} 扣池, 拦死动作由受击 handler
 * ({@code ChampionBloodPoolHandler}) 据判据执行。回血池内 clamp (spec 6.2 #4): currentHp = min(currentHp + heal, maxHp)。
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
     * 以指定当前血构造血池 (F040: 从 NBT 还原时按存档当前血重建, currentHp 可 &lt; maxHp; 须 &gt;0, 已死态
     * (0 血) 不建池, 由调用方判定不建/走 promote 重新盖章)。
     *
     * @param maxHp     最大血量 (必须 &gt;0)
     * @param currentHp 当前血量 (必须 &gt;0 且 &lt;=maxHp; 越界抛 IllegalArgumentException)
     */
    public BloodPool(double maxHp, double currentHp) {
        if (!(maxHp > 0.0D) || Double.isNaN(maxHp) || Double.isInfinite(maxHp)) {
            throw new IllegalArgumentException("maxHp must be a finite positive value, got " + maxHp);
        }
        if (!(currentHp > 0.0D) || currentHp > maxHp || Double.isNaN(currentHp)) {
            throw new IllegalArgumentException(
                    "currentHp must be in (0, maxHp], got " + currentHp + " (maxHp=" + maxHp + ")");
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
     * 拦死判据 (spec 6.2 #3): currentHp - 本次伤害 ≤ 0 → true (应扣池到 0 + 摘池放行, 见 {@link #applyDamage})。
     * 本判据是单一权威, 不读 vanilla 血。删本判据 → test_kill_precise_at_zero 必挂。
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
     * 返回扣血后是否致死 (= {@link #isDead()}), 供 handler 据此摘池放行, 交外层 vanilla die() 驱动真死。
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
     * vanilla 渲染镜像血量 (spec 6.2 #2), 按 1024 保守钳: displayHealth = clamp(currentHp / maxHp × 1024, 0, 1024)。
     * 供无 AttributeFix 环境 (dev GameTest/纯原版属性上限) 使用; 真服镜像走 {@link #displayHealth(double)} 传实际
     * 属性上限。仅渲染不参与判定。
     *
     * @return 渲染镜像血量 (float, ∈ [0, 1024])
     */
    public float displayHealth() {
        return displayHealth(VANILLA_MAX_HEALTH_CLAMP);
    }

    /**
     * vanilla 渲染镜像血量, 按【实际属性上限】等比例映射: clamp(fraction × vanillaMax, 0, vanillaMax)。
     * 测试服装了 AttributeFix (max_health 上限抬到 1e6), promoter 把血池怪的 vanilla 血量属性设到有效血真值,
     * 此时 vanillaMax = 池 maxHp -> 镜像 = 池 currentHp 原值, Jade 等悬浮血条直显真血 (如 27000/27000);
     * 无 AttributeFix 时属性自钳 1024, 传入的 vanillaMax = 1024 自动退回保守镜像。仅渲染不参与判定。
     *
     * @param vanillaMax 实体当前 vanilla getMaxHealth() (属性钳后的实际上限; 须 &gt;0, 非正抛不掩盖)
     * @return 渲染镜像血量 (float, ∈ [0, vanillaMax])
     */
    public float displayHealth(double vanillaMax) {
        if (!(vanillaMax > 0.0D) || Double.isNaN(vanillaMax)) {
            throw new IllegalArgumentException("vanillaMax must be > 0, got " + vanillaMax);
        }
        double mirrored = fraction() * vanillaMax;
        if (mirrored < 0.0D) {
            mirrored = 0.0D;
        }
        if (mirrored > vanillaMax) {
            mirrored = vanillaMax;
        }
        return (float) mirrored;
    }
}
