package com.miningdim.job.agent;

import net.minecraft.nbt.CompoundTag;

/**
 * 悬赏板上一张悬赏的状态 (SpecialAgent_Job_DesignSpec 10.5 悬赏完成判定 + UTC 翻日 / ISO 周重置)。
 *
 * 生命周期: 翻日/翻周时由 {@link BountyGenerator} 掷出, 先以<b>未接取</b>的"可接悬赏"挂在板上 ->
 * 玩家接取 ({@link #accept}, 占一个当期槽位) -> 合格击杀逐次累计 ({@link #recordKill}) -> 达标后发奖一次
 * ({@link #tryClaim})。未接取的悬赏不计进度: 接取是玩家主动选择做特勤活计的动作, 也是入职标志的置位点 (7.0,
 * 2026-09-30 拍板), 不接也能白拿进度等于让全服每个打精英的人都在做悬赏。
 *
 * 接取后不能放弃、不能重摇: 悬赏是"周期性但不可重摇"的来源 (见 {@code quest.QuestSource} 的同名论证), 放弃再接
 * 等于把槽位变成可反复刷好单的抽奖机。
 *
 * 纯逻辑, 无世界引用; 周期戳由调用方经 {@link AgentClock} 注入。
 */
public final class BountyProgress {

    private static final String K_DEF = "def";
    private static final String K_KILLS = "kills";
    private static final String K_ACCEPTED = "accepted";
    private static final String K_CLAIMED = "claimed";
    private static final String K_STAMP = "stamp";

    private final BountyDefinition definition;
    private int killCount;
    private boolean accepted;
    private boolean claimed;
    /** 掷出/上次重置时的周期戳 (DAILY = epochDay; WEEKLY = ISO 周戳); 跨戳触发重置。 */
    private long periodStamp;

    /**
     * 一张刚掷出、尚未接取的悬赏。
     *
     * @param definition  本悬赏定义
     * @param periodStamp 掷出时的周期戳 (DAILY 传 epochDay; WEEKLY 传 ISO 周戳)
     */
    public BountyProgress(BountyDefinition definition, long periodStamp) {
        this(definition, periodStamp, 0, false, false);
    }

    private BountyProgress(BountyDefinition definition, long periodStamp, int killCount, boolean accepted,
                           boolean claimed) {
        if (definition == null) {
            throw new IllegalArgumentException("definition must not be null");
        }
        this.definition = definition;
        this.periodStamp = periodStamp;
        this.killCount = killCount;
        this.accepted = accepted;
        this.claimed = claimed;
    }

    public BountyDefinition definition() {
        return definition;
    }

    public int killCount() {
        return killCount;
    }

    public boolean accepted() {
        return accepted;
    }

    public boolean claimed() {
        return claimed;
    }

    public long periodStamp() {
        return periodStamp;
    }

    /**
     * 接取。只翻状态位, 槽位上限由悬赏板 ({@link BountyBoard#accept}) 裁决。
     *
     * @return 是否首次接取 (false = 早已接过)
     */
    public boolean accept() {
        if (accepted) {
            return false;
        }
        accepted = true;
        return true;
    }

    /**
     * 跨周期戳时重置进度 (10.5: 日常翻日 / 周常翻周清零)。同戳不动。悬赏板翻期时整张重掷, 本方法只供单张悬赏的
     * 边界测试与未来按单张续期的用法。
     *
     * @return 是否发生了重置
     */
    public boolean rolloverIfStale(long currentPeriodStamp) {
        if (currentPeriodStamp == periodStamp) {
            return false;
        }
        this.periodStamp = currentPeriodStamp;
        this.killCount = 0;
        this.accepted = false;
        this.claimed = false;
        return true;
    }

    /**
     * 记录一次击杀: 已接取、未完成、且 {@link BountyDefinition#countsToward} 命中时计数 +1。达标后不再增计。
     *
     * @return 本次是否计入
     */
    public boolean recordKill(BountyKill kill) {
        if (!accepted || isComplete()) {
            return false;
        }
        if (!definition.countsToward(kill)) {
            return false;
        }
        killCount++;
        return true;
    }

    /** 是否已达成完成条件 (合格击杀计数 &gt;= requiredCount)。 */
    public boolean isComplete() {
        return killCount >= definition.requiredCount();
    }

    /**
     * 领取完成奖励: 已完成且未领过则标记 claimed 返回 true (调用方据此发奖); 否则返回 false (不发)。
     */
    public boolean tryClaim() {
        if (!isComplete() || claimed) {
            return false;
        }
        claimed = true;
        return true;
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.put(K_DEF, definition.toTag());
        tag.putInt(K_KILLS, killCount);
        tag.putBoolean(K_ACCEPTED, accepted);
        tag.putBoolean(K_CLAIMED, claimed);
        tag.putLong(K_STAMP, periodStamp);
        return tag;
    }

    /** 读盘; 定义非法时由 {@link BountyDefinition#fromTag} 抛 IllegalArgumentException。 */
    public static BountyProgress fromTag(CompoundTag tag) {
        BountyDefinition definition = BountyDefinition.fromTag(tag.getCompound(K_DEF));
        int kills = Math.max(0, Math.min(tag.getInt(K_KILLS), definition.requiredCount()));
        return new BountyProgress(definition, tag.getLong(K_STAMP), kills, tag.getBoolean(K_ACCEPTED),
                tag.getBoolean(K_CLAIMED));
    }
}
