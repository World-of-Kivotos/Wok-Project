package com.miningdim.champion;

/**
 * 精英怪【自我修复单元】(SELF_REPAIR, spec 7.4 ★4 c14) 读条自愈状态机纯逻辑 (Stage2 技能批)。
 *
 * 机制 (spec 7.4 + 用户 2026-07-07 拍板): 有效血占比 ≤50% 且 CD 就绪时进入读条修复, 读条 6s=120tick 内每秒
 * (20tick) 回一跳血, 期间由 handler 定身 + 抑制攻击 (给玩家的输出窗口); 读满进 CD, 近战命中打断进 CD;
 * 受任意伤害后 1.5s=30tick 内的回血跳作废 (读条不中断, 只停跳血)。CD=25s=500tick, 从读条结束/打断起算。
 *
 * 本类是【确定性状态机纯函数集】: 不 import 任何 net.minecraft 世界/实体类型, 只承载 tick 计时与三态迁移
 * (IDLE / CHANNELING / COOLDOWN), 供 GameTest 逐 tick 精确断言 (删触发阈值/读条时长/跳血对齐/停回窗/打断/CD 必挂)。
 * 实际定身 (getNavigation().stop + setDeltaMovement)、攻击抑制、回血施加 (血池 heal / vanilla heal)、发光/粒子/音效、
 * 近战判别 (DamageTypes.MOB_ATTACK 系) 由 integration 层 {@code ChampionSelfRepairHandler} 负责 (真服验)。
 *
 * 回血分工 (与 {@link ChampionSelfBuffValues} 一致): 本类只算"本跳该回多少 / 是否被停回窗作废 / 是否读满",
 * 每跳回血量的数值 = handler 传入的 {@code healPerSecond}(= {@link AffixDef#SELF_REPAIR} 按品质档 40/0/80/150/300),
 * 一跳 20tick = 1s 故一跳即回一整秒名义量。中级档 0 的防御性早退在 {@link #tryStart} (healPerSecond ≤0 不启动)。
 *
 * 可变状态: 三态 + 读条起点 tick + 读条结束 tick (CD 起算) + 上次受伤 tick (停回窗)。非线程安全 (单怪服务端 tick 串行)。
 */
public final class ChampionSelfRepairCycle {

    /** 触发阈值: 有效血占比 ≤此值才可进读条 (spec 7.4 用户拍板 0.50)。恰界 0.50 触发 (≤, 非 &lt;)。 */
    public static final double TRIGGER_FRACTION = 0.50D;

    /** 读条时长 (tick): 6s。读满即 DONE 进 CD。 */
    public static final long CHANNEL_TICKS = 120L;

    /** 修复 CD (tick): 25s, 从读条结束/打断起算 (恰界 500 可再触发)。 */
    public static final long COOLDOWN_TICKS = 500L;

    /** 回血跳周期 (tick): 读条内每 20tick=1s 一跳。 */
    public static final long HEAL_JUMP_INTERVAL = 20L;

    /** 受伤停回窗 (tick): 距上次受伤 &lt;此值的回血跳作废 (spec 7.4 受任意伤害暂停 1.5s; 读条不中断只停跳血)。 */
    public static final long HURT_PAUSE_TICKS = 30L;

    /** 三态: 空闲 (可触发) / 读条中 (定身回血) / 冷却中 (读满或打断后, 到 CD 才回 IDLE)。 */
    public enum State {
        IDLE,
        CHANNELING,
        COOLDOWN
    }

    /**
     * 一 tick 读条推进结果: 本跳回血量 (0 = 非跳 tick 或被停回窗作废) + 是否被停回窗作废 (跳 tick 但距受伤 &lt;30tick)
     * + 本 tick 是否读满 (读满同 tick 已迁 COOLDOWN)。读满 tick 与第 6 跳同 tick, 故 completed=true 时 heal 可 &gt;0。
     *
     * @param heal      本 tick 应施加回血量 (&gt;=0)
     * @param paused    本 tick 是跳 tick 但因近期受伤被作废 (供诊断"暂停"日志; 与 heal&gt;0 互斥)
     * @param completed 本 tick 读满 (已迁 COOLDOWN, 供 handler 放读满特效)
     */
    public record ChannelTick(double heal, boolean paused, boolean completed) {
    }

    private State state = State.IDLE;
    private long channelStartTick = Long.MIN_VALUE;
    private long channelEndTick = Long.MIN_VALUE;
    private long lastHurtTick = Long.MIN_VALUE;

    /** 当前状态 (诊断/测试)。 */
    public State state() {
        return state;
    }

    /** 是否读条中 (handler 据此每 tick 定身 + 推进)。 */
    public boolean isChanneling() {
        return state == State.CHANNELING;
    }

    /**
     * 尝试起读条 (handler 对非读条冠军每 tick 调, 传入当前有效血占比与该品质每秒回血量)。起读条条件全满足则迁
     * CHANNELING 并返 true:
     *  1. 状态就绪 (IDLE; 或 COOLDOWN 且距读条结束 ≥{@link #COOLDOWN_TICKS} —— 此调惰性把 CD 到点的 COOLDOWN 迁回 IDLE);
     *  2. healPerSecond &gt;0 (中级档 0 前导占位防御性不启动);
     *  3. 有效血占比 ≤{@link #TRIGGER_FRACTION} (恰界 0.50 触发)。
     * 读条中调恒返 false (不重复起读条)。
     *
     * @param nowTick        当前 gameTime tick
     * @param healthFraction 有效血占比 (血池 fraction / 无池 getHealth/getMaxHealth; 须 &gt;=0 非 NaN)
     * @param healPerSecond  该品质每秒回血量 (须 &gt;=0 非 NaN; 0 = 中级档占位, 不启动)
     * @return 是否本 tick 起了读条
     */
    public boolean tryStart(long nowTick, double healthFraction, double healPerSecond) {
        requireFraction(healthFraction);
        requireHealPerSecond(healPerSecond);
        // CD 到点惰性回 IDLE (从读条结束/打断起算 ≥500tick)。恰界 500 就绪。
        if (state == State.COOLDOWN && nowTick - channelEndTick >= COOLDOWN_TICKS) {
            state = State.IDLE;
        }
        if (state != State.IDLE) {
            return false; // 读条中 / CD 未到: 不起读条。
        }
        if (healPerSecond <= 0.0D) {
            return false; // 中级档 0 (前导占位) / 未装配: 防御性不启动。
        }
        if (healthFraction > TRIGGER_FRACTION) {
            return false; // 血量未达触发阈值 (>50%)。
        }
        state = State.CHANNELING;
        channelStartTick = nowTick;
        return true;
    }

    /**
     * 推进读条一 tick (handler 仅对 {@link #isChanneling()} 的冠军每 tick 调一次)。据距读条起点 tick 数确定性推导:
     *  - 跳 tick (elapsed 为 20 的正整数倍且 ≤120): 距上次受伤 &lt;30tick 则作废 (paused), 否则回 healPerSecond;
     *  - elapsed ≥120: 读满, 迁 COOLDOWN 并以本 tick 为 CD 起点。elapsed==120 同 tick 兼是第 6 跳 (heal) 与读满 (completed)。
     *
     * @param nowTick       当前 gameTime tick (须 ≥读条起点; 早于起点属 handler bug, 抛不掩盖)
     * @param healPerSecond 该品质每秒回血量 (须 &gt;=0 非 NaN)
     * @return 本 tick 推进结果
     */
    public ChannelTick advance(long nowTick, double healPerSecond) {
        if (state != State.CHANNELING) {
            throw new IllegalStateException("advance called while not channeling: " + state);
        }
        requireHealPerSecond(healPerSecond);
        long elapsed = nowTick - channelStartTick;
        if (elapsed < 0L) {
            throw new IllegalArgumentException("nowTick before channel start: " + nowTick + " < " + channelStartTick);
        }

        double heal = 0.0D;
        boolean paused = false;
        if (elapsed > 0L && elapsed % HEAL_JUMP_INTERVAL == 0L && elapsed <= CHANNEL_TICKS) {
            // 跳 tick: 近期受伤 (距上次受伤 <30tick) 作废本跳 (读条不中断, 只停跳血)。
            if (lastHurtTick != Long.MIN_VALUE && nowTick - lastHurtTick < HURT_PAUSE_TICKS) {
                paused = true;
            } else {
                heal = healPerSecond;
            }
        }

        boolean completed = false;
        if (elapsed >= CHANNEL_TICKS) {
            state = State.COOLDOWN;
            channelEndTick = nowTick;
            completed = true;
        }
        return new ChannelTick(heal, paused, completed);
    }

    /**
     * 记录受伤 tick (handler 对读条冠军受任意伤害时调): 供 {@link #advance} 停回窗作废本跳。非读条期调无副作用
     * (仅刷时戳; 停回窗只在读条内的跳 tick 生效)。
     */
    public void recordHurt(long nowTick) {
        this.lastHurtTick = nowTick;
    }

    /**
     * 近战命中打断读条 (handler 判近战伤害类型后调): 读条中则迁 COOLDOWN 并以本 tick 为 CD 起点, 返 true;
     * 非读条期返 false (无事发生, 近战不触发)。
     *
     * @param nowTick 当前 gameTime tick
     * @return 是否本 tick 打断了读条
     */
    public boolean interruptByMelee(long nowTick) {
        if (state != State.CHANNELING) {
            return false;
        }
        state = State.COOLDOWN;
        channelEndTick = nowTick;
        return true;
    }

    private static void requireFraction(double v) {
        if (v < 0.0D || Double.isNaN(v)) {
            throw new IllegalArgumentException("healthFraction must be >= 0, got " + v);
        }
    }

    private static void requireHealPerSecond(double v) {
        if (v < 0.0D || Double.isNaN(v)) {
            throw new IllegalArgumentException("healPerSecond must be >= 0, got " + v);
        }
    }
}
