package com.miningdim.economy;

import com.miningdim.economy.EconomyConstants.HighValueOre;
import net.minecraft.nbt.CompoundTag;

import java.util.EnumMap;
import java.util.Map;

/**
 * 单个玩家的反滥用运行态 (设计文档第十八章 18.3/18.4/18.5/18.6 引用的玩家级字段集)。
 *
 * 归属说明 (架构铁律): 设计文档 18.3 把 {@code dailyOreCount} 标注为"存玩家 Capability/PlayerMiningData",
 * 18.4-18.6 的 AFK/重入/死亡字段亦属玩家级数据 (D5)。但 core 当前未定义玩家数据门面接口
 * ({@code IMiningPlayerData} 仅在设计文档 3.3 出现, 未落入 com.miningdim.core), 且 Capability 子系统
 * 尚未交付。本子系统不得 import 尚不存在的 Capability 实现类, 也不得擅自向 core 加接口 (任务约束)。
 * 因此本类作为经济子系统自有的玩家级状态载体, 由 {@link EconomySystem} 以 UUID 为键在内存维护,
 * 登入时重建。它实现 NBT 读写以便 Capability 子系统就绪后把这些字段迁入持久层 (届时本类可被
 * 该子系统的 Capability 数据复用或替换); 当前阶段它是闸门生效所必需的真实状态, 非占位。
 *
 * 线程: 仅服务端主线程读写 (所有事件回调在主线程; tick 评估在 ServerTickEvent)。不做并发防御 (无跨线程访问)。
 */
public final class PlayerAbuseState {

    // ---- 18.3 每玩家每日高价矿物产出计数 (翻日清零) ----
    private final Map<HighValueOre, Integer> dailyOreCount = new EnumMap<>(HighValueOre.class);

    /** 当前计数所属的"日戳": REAL 模式为 UTC 日序 (epochDay), GAME 模式为 dayTime/24000。翻日触发清零。 */
    private long dayStamp = Long.MIN_VALUE;

    // ---- 18.4 AFK / 挂机检测 ----
    /** 最近一次在矿山维度内的有效挖掘 tick (BlockEvent.BreakEvent); 用于 noBreakTicks 判定。 */
    private long lastBreakTick = Long.MIN_VALUE;

    /** AFK 评估窗口起点位置 (用于 noMoveBlocks 位移判定)。null 表示尚未取样。 */
    private double anchorX = Double.NaN;
    private double anchorZ = Double.NaN;

    /** 上次位移取样 tick (滑动窗口锚点刷新用)。 */
    private long lastMoveSampleTick = Long.MIN_VALUE;

    /** 经济冻结态: AFK 期间为 true, 暂停 danger 累积 / 后方刷怪锚定 / 掉落计入 (18.4)。 */
    private boolean afkFrozen = false;

    // ---- 18.5 danger 重入冷却 ----
    /** 上次离开的实例 id; -1 = 无。 */
    private long lastInstanceId = -1L;

    /** 上次离开该实例的 tick。 */
    private long lastLeaveTick = Long.MIN_VALUE;

    /** 上次离开时的 danger 值 (用于冷却内重入的 max(衰减值, 离开值*retainRatio))。 */
    private float lastLeaveDanger = 0.0f;

    // ---- 18.6 死亡惩罚 ----
    /** 死亡后再进入任意实例解禁的 tick (death.reentryCooldownTicks); 0 = 无冷却。 */
    private long deathReentryUntilTick = 0L;

    /** death.lockInstanceTicks>0 时, 该玩家对"死亡所在实例"的再入解禁 tick; 键为 instanceId。 */
    private long lockedInstanceId = -1L;
    private long lockedInstanceUntilTick = 0L;

    public PlayerAbuseState() {
        for (HighValueOre ore : HighValueOre.values()) {
            dailyOreCount.put(ore, 0);
        }
    }

    // ---- 18.3 每日矿物计数 ----

    /**
     * 在 newStamp 与当前 dayStamp 不同时清零全部计数 (翻日)。由 InstanceManager 周期 tick 批量调用,
     * 不在玩家请求路径做时钟比较以外的写 (18.2 口径)。返回是否发生了翻日清零。
     */
    public boolean rolloverIfNewDay(long newStamp) {
        if (newStamp != dayStamp) {
            dayStamp = newStamp;
            for (HighValueOre ore : HighValueOre.values()) {
                dailyOreCount.put(ore, 0);
            }
            return true;
        }
        return false;
    }

    /** 当前某类高价矿当日已产出计数。 */
    public int dailyOreCount(HighValueOre ore) {
        return dailyOreCount.get(ore);
    }

    /** 给某类高价矿当日计数加 delta, 返回加后的新值。 */
    public int addDailyOreCount(HighValueOre ore, int delta) {
        int now = dailyOreCount.get(ore) + delta;
        dailyOreCount.put(ore, now);
        return now;
    }

    public long dayStamp() {
        return dayStamp;
    }

    // ---- 18.4 AFK ----

    public long lastBreakTick() {
        return lastBreakTick;
    }

    public void setLastBreakTick(long tick) {
        this.lastBreakTick = tick;
    }

    public boolean afkFrozen() {
        return afkFrozen;
    }

    public void setAfkFrozen(boolean frozen) {
        this.afkFrozen = frozen;
    }

    public boolean hasMoveAnchor() {
        return !Double.isNaN(anchorX);
    }

    public double anchorX() {
        return anchorX;
    }

    public double anchorZ() {
        return anchorZ;
    }

    public long lastMoveSampleTick() {
        return lastMoveSampleTick;
    }

    /** 刷新位移评估锚点为当前坐标与 tick (位移超阈或周期重锚时调用)。 */
    public void resetMoveAnchor(double x, double z, long tick) {
        this.anchorX = x;
        this.anchorZ = z;
        this.lastMoveSampleTick = tick;
    }

    // ---- 18.5 重入冷却 ----

    public long lastInstanceId() {
        return lastInstanceId;
    }

    public long lastLeaveTick() {
        return lastLeaveTick;
    }

    public float lastLeaveDanger() {
        return lastLeaveDanger;
    }

    /** 记录一次离开 (18.5 L2 字段): 离开实例 id、离开 tick、离开时 danger。 */
    public void recordLeave(long instanceId, long tick, float danger) {
        this.lastInstanceId = instanceId;
        this.lastLeaveTick = tick;
        this.lastLeaveDanger = danger;
    }

    // ---- 18.6 死亡惩罚 ----

    public long deathReentryUntilTick() {
        return deathReentryUntilTick;
    }

    public void setDeathReentryUntilTick(long tick) {
        this.deathReentryUntilTick = tick;
    }

    public long lockedInstanceId() {
        return lockedInstanceId;
    }

    public long lockedInstanceUntilTick() {
        return lockedInstanceUntilTick;
    }

    /** 记录 death.lockInstanceTicks 锁定: 该玩家对 instanceId 在 untilTick 前不得再入。 */
    public void lockInstance(long instanceId, long untilTick) {
        this.lockedInstanceId = instanceId;
        this.lockedInstanceUntilTick = untilTick;
    }

    // ---- NBT 读写 (供 Capability 子系统就绪后迁入持久层; 18.x 字段全持久化) ----

    private static final String K_DAY_STAMP = "dayStamp";
    private static final String K_DIAMOND = "dailyDiamond";
    private static final String K_GOLD = "dailyGold";
    private static final String K_NETHERITE = "dailyNetheriteScrap";
    private static final String K_LAST_BREAK = "lastBreakTick";
    private static final String K_AFK = "afkFrozen";
    private static final String K_ANCHOR_X = "moveAnchorX";
    private static final String K_ANCHOR_Z = "moveAnchorZ";
    private static final String K_HAS_ANCHOR = "hasMoveAnchor";
    private static final String K_MOVE_SAMPLE = "lastMoveSampleTick";
    private static final String K_LAST_INSTANCE = "lastInstanceId";
    private static final String K_LAST_LEAVE = "lastLeaveTick";
    private static final String K_LAST_LEAVE_DANGER = "lastLeaveDanger";
    private static final String K_DEATH_REENTRY = "deathReentryUntilTick";
    private static final String K_LOCK_INSTANCE = "lockedInstanceId";
    private static final String K_LOCK_UNTIL = "lockedInstanceUntilTick";

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong(K_DAY_STAMP, dayStamp);
        tag.putInt(K_DIAMOND, dailyOreCount.get(HighValueOre.DIAMOND));
        tag.putInt(K_GOLD, dailyOreCount.get(HighValueOre.GOLD));
        tag.putInt(K_NETHERITE, dailyOreCount.get(HighValueOre.NETHERITE_SCRAP));
        tag.putLong(K_LAST_BREAK, lastBreakTick);
        tag.putBoolean(K_AFK, afkFrozen);
        tag.putBoolean(K_HAS_ANCHOR, hasMoveAnchor());
        if (hasMoveAnchor()) {
            tag.putDouble(K_ANCHOR_X, anchorX);
            tag.putDouble(K_ANCHOR_Z, anchorZ);
        }
        tag.putLong(K_MOVE_SAMPLE, lastMoveSampleTick);
        tag.putLong(K_LAST_INSTANCE, lastInstanceId);
        tag.putLong(K_LAST_LEAVE, lastLeaveTick);
        tag.putFloat(K_LAST_LEAVE_DANGER, lastLeaveDanger);
        tag.putLong(K_DEATH_REENTRY, deathReentryUntilTick);
        tag.putLong(K_LOCK_INSTANCE, lockedInstanceId);
        tag.putLong(K_LOCK_UNTIL, lockedInstanceUntilTick);
        return tag;
    }

    public static PlayerAbuseState load(CompoundTag tag) {
        PlayerAbuseState s = new PlayerAbuseState();
        s.dayStamp = tag.getLong(K_DAY_STAMP);
        s.dailyOreCount.put(HighValueOre.DIAMOND, tag.getInt(K_DIAMOND));
        s.dailyOreCount.put(HighValueOre.GOLD, tag.getInt(K_GOLD));
        s.dailyOreCount.put(HighValueOre.NETHERITE_SCRAP, tag.getInt(K_NETHERITE));
        s.lastBreakTick = tag.getLong(K_LAST_BREAK);
        s.afkFrozen = tag.getBoolean(K_AFK);
        if (tag.getBoolean(K_HAS_ANCHOR)) {
            s.anchorX = tag.getDouble(K_ANCHOR_X);
            s.anchorZ = tag.getDouble(K_ANCHOR_Z);
        }
        s.lastMoveSampleTick = tag.getLong(K_MOVE_SAMPLE);
        s.lastInstanceId = tag.getLong(K_LAST_INSTANCE);
        s.lastLeaveTick = tag.getLong(K_LAST_LEAVE);
        s.lastLeaveDanger = tag.getFloat(K_LAST_LEAVE_DANGER);
        s.deathReentryUntilTick = tag.getLong(K_DEATH_REENTRY);
        s.lockedInstanceId = tag.getLong(K_LOCK_INSTANCE);
        s.lockedInstanceUntilTick = tag.getLong(K_LOCK_UNTIL);
        return s;
    }
}
