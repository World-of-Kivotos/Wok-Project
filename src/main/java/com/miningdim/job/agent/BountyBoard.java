package com.miningdim.job.agent;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.RandomSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 单个玩家的悬赏板 (SpecialAgent_Job_DesignSpec 7.2 槽位 + 10.5 个人任务清单)。
 *
 * 一块板 = 本日可接悬赏 + 本周可接悬赏 + 待补发的青辉石。可接张数 = 当前槽位 + 少量余量
 * ({@link BountyGenerator#DAILY_EXTRA_OFFERS} / {@link BountyGenerator#WEEKLY_EXTRA_OFFERS}), 玩家从中挑, 接满
 * 槽位即止。槽位数按<b>接取那一刻</b>的等级查表: 一天之内升级, 当天就能多接一张。
 *
 * 翻期: 日戳 (UTC epochDay) 变了清空本日悬赏重掷, ISO 周戳变了清空本周悬赏重掷 —— 未完成的作废, 已完成的奖励早在
 * 完成那一刻发掉了, 没有"忘了领"这回事。周期内升级导致槽位变多时<b>只补掷, 不重掷</b>: 已接的、没接的都保留原样,
 * 否则升一级就能把整块板换一遍, 等于免费重摇。
 *
 * 待补发青辉石 ({@link #pendingAzure}): 悬赏青辉石先过周软上限记账, 再经与精英掉落共享的每人每日硬上限入账; 同一天
 * 完成两三张周常时日上限会截断, 截掉的部分记在这里, 之后登录或再完成悬赏时补发, 不吞玩家已经挣到的东西。
 *
 * 纯逻辑 + NBT, 无世界引用; 等级、日戳、周戳、随机源都由调用方注入。
 */
public final class BountyBoard {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/agent/bounty");

    private static final String K_DAY = "day";
    private static final String K_WEEK = "week";
    private static final String K_DAILY = "daily";
    private static final String K_WEEKLY = "weekly";
    private static final String K_PENDING_AZURE = "pendingAzure";
    private static final String K_WORLD_BOSS_ORDERS = "worldBossOrders";

    /** 接取的结果码 (面板按它出文案, 顺序即声明序)。 */
    public enum AcceptOutcome {
        OK,
        /** 悬赏系统被运营关闭 (miningdim-agent.toml)。 */
        DISABLED,
        /** 本期板上没有这张悬赏 (多半是跨了日/周, 面板还停在旧板上)。 */
        NOT_FOUND,
        ALREADY_ACCEPTED,
        /** 该周期在当前等级未解锁 (周常 L4 起)。 */
        LOCKED,
        /** 本期槽位已接满。 */
        NO_SLOT,
        /** 目标星级高于当前可接上限 (等级被管理员调低过)。 */
        STAR_TOO_HIGH
    }

    private long dayStamp = Long.MIN_VALUE;
    private long weekStamp = Long.MIN_VALUE;
    private final List<BountyProgress> daily = new ArrayList<>();
    private final List<BountyProgress> weekly = new ArrayList<>();
    private long pendingAzure;
    /** 本周已结算的世界 BOSS 讨伐令次数 (面板展示用, 随周戳清零)。 */
    private int worldBossOrders;

    public List<BountyProgress> daily() {
        return Collections.unmodifiableList(daily);
    }

    public List<BountyProgress> weekly() {
        return Collections.unmodifiableList(weekly);
    }

    public long dayStamp() {
        return dayStamp;
    }

    public long weekStamp() {
        return weekStamp;
    }

    public long pendingAzure() {
        return pendingAzure;
    }

    public int worldBossOrders() {
        return worldBossOrders;
    }

    /**
     * 翻期清空 + 按当前等级补掷到应有张数。
     *
     * @return 板是否有变化 (调用方据此 setDirty)
     */
    public boolean refresh(long currentDay, long currentWeek, int agentLevel, BountyRewardTable table,
                           RandomSource rng) {
        boolean changed = false;
        if (currentDay != dayStamp) {
            daily.clear();
            dayStamp = currentDay;
            changed = true;
        }
        if (currentWeek != weekStamp) {
            weekly.clear();
            weekStamp = currentWeek;
            worldBossOrders = 0;
            changed = true;
        }
        changed |= topUp(daily, BountyDefinition.Period.DAILY, "d" + dayStamp,
                AgentSkillTable.dailyBountySlots(agentLevel) + BountyGenerator.DAILY_EXTRA_OFFERS,
                agentLevel, dayStamp, table, rng);
        int weeklySlots = AgentSkillTable.weeklyBountySlots(agentLevel);
        if (weeklySlots > 0) {
            changed |= topUp(weekly, BountyDefinition.Period.WEEKLY, "w" + weekStamp,
                    weeklySlots + BountyGenerator.WEEKLY_EXTRA_OFFERS, agentLevel, weekStamp, table, rng);
        }
        return changed;
    }

    private static boolean topUp(List<BountyProgress> list, BountyDefinition.Period period, String idPrefix,
                                 int target, int agentLevel, long stamp, BountyRewardTable table, RandomSource rng) {
        int missing = target - list.size();
        if (missing <= 0) {
            return false;
        }
        List<BountyDefinition> existing = new ArrayList<>(list.size());
        for (BountyProgress progress : list) {
            existing.add(progress.definition());
        }
        // 序号取当前张数: 本期只增不删, 序号天然不撞。
        for (BountyDefinition def : BountyGenerator.draw(period, agentLevel, missing, idPrefix, list.size(),
                existing, table, rng)) {
            list.add(new BountyProgress(def, stamp));
        }
        return true;
    }

    /**
     * 接取一张悬赏。槽位与星级门按当前等级裁决; 成功后该悬赏开始累计击杀。
     */
    public AcceptOutcome accept(BountyDefinition.Period period, String bountyId, int agentLevel) {
        List<BountyProgress> list;
        int slots;
        if (period == BountyDefinition.Period.DAILY) {
            list = daily;
            slots = AgentSkillTable.dailyBountySlots(agentLevel);
        } else if (period == BountyDefinition.Period.WEEKLY) {
            list = weekly;
            slots = AgentSkillTable.weeklyBountySlots(agentLevel);
        } else {
            return AcceptOutcome.NOT_FOUND;
        }
        BountyProgress target = null;
        for (BountyProgress progress : list) {
            if (progress.definition().id().equals(bountyId)) {
                target = progress;
                break;
            }
        }
        if (target == null) {
            return AcceptOutcome.NOT_FOUND;
        }
        if (target.accepted()) {
            return AcceptOutcome.ALREADY_ACCEPTED;
        }
        if (slots <= 0) {
            return AcceptOutcome.LOCKED;
        }
        if (acceptedCount(list) >= slots) {
            return AcceptOutcome.NO_SLOT;
        }
        if (target.definition().minStar() > AgentSkillTable.maxBountyStar(agentLevel)) {
            return AcceptOutcome.STAR_TOO_HIGH;
        }
        target.accept();
        return AcceptOutcome.OK;
    }

    /** 本期已接张数 (含已完成的: 槽位是"今天能做几张", 做完不退)。 */
    public static int acceptedCount(List<BountyProgress> list) {
        int n = 0;
        for (BountyProgress progress : list) {
            if (progress.accepted()) {
                n++;
            }
        }
        return n;
    }

    /**
     * 记一次合格击杀, 返回因此<b>刚好完成</b>的悬赏 (已标记领取, 调用方据此发奖)。一次击杀可以同时推进多张悬赏,
     * 日常与周常互不排斥。
     */
    public List<BountyDefinition> recordKill(BountyKill kill) {
        List<BountyDefinition> completed = new ArrayList<>();
        recordKill(daily, kill, completed);
        recordKill(weekly, kill, completed);
        return completed;
    }

    private static void recordKill(List<BountyProgress> list, BountyKill kill, List<BountyDefinition> completed) {
        for (BountyProgress progress : list) {
            if (progress.recordKill(kill) && progress.tryClaim()) {
                completed.add(progress.definition());
            }
        }
    }

    /** 本次击杀是否会推进任何一张已接未完成的悬赏 (悬赏雷达用, 不改状态)。 */
    public boolean wouldAdvance(BountyKill kill) {
        return wouldAdvance(daily, kill) || wouldAdvance(weekly, kill);
    }

    private static boolean wouldAdvance(List<BountyProgress> list, BountyKill kill) {
        for (BountyProgress progress : list) {
            if (progress.accepted() && !progress.isComplete() && progress.definition().countsToward(kill)) {
                return true;
            }
        }
        return false;
    }

    public void addPendingAzure(long amount) {
        if (amount < 0L) {
            throw new IllegalArgumentException("pending azure must be >= 0, got " + amount);
        }
        pendingAzure += amount;
    }

    /** 补发成功后扣减待发量 (不会扣成负数)。 */
    public void settlePendingAzure(long paid) {
        pendingAzure = Math.max(0L, pendingAzure - paid);
    }

    public void recordWorldBossOrder() {
        worldBossOrders++;
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putLong(K_DAY, dayStamp);
        tag.putLong(K_WEEK, weekStamp);
        tag.put(K_DAILY, listTag(daily));
        tag.put(K_WEEKLY, listTag(weekly));
        tag.putLong(K_PENDING_AZURE, pendingAzure);
        tag.putInt(K_WORLD_BOSS_ORDERS, worldBossOrders);
        return tag;
    }

    public static BountyBoard fromTag(CompoundTag tag) {
        BountyBoard board = new BountyBoard();
        board.dayStamp = tag.contains(K_DAY) ? tag.getLong(K_DAY) : Long.MIN_VALUE;
        board.weekStamp = tag.contains(K_WEEK) ? tag.getLong(K_WEEK) : Long.MIN_VALUE;
        readList(tag.getList(K_DAILY, Tag.TAG_COMPOUND), board.daily);
        readList(tag.getList(K_WEEKLY, Tag.TAG_COMPOUND), board.weekly);
        board.pendingAzure = Math.max(0L, tag.getLong(K_PENDING_AZURE));
        board.worldBossOrders = Math.max(0, tag.getInt(K_WORLD_BOSS_ORDERS));
        return board;
    }

    private static ListTag listTag(List<BountyProgress> list) {
        ListTag out = new ListTag();
        for (BountyProgress progress : list) {
            out.add(progress.toTag());
        }
        return out;
    }

    private static void readList(ListTag tags, List<BountyProgress> into) {
        for (int i = 0; i < tags.size(); i++) {
            try {
                into.add(BountyProgress.fromTag(tags.getCompound(i)));
            } catch (IllegalArgumentException e) {
                // 某张悬赏的结构读不回来 (枚举改名等): 丢掉这一张, 不让整块板乃至整份存档读不出来。下次翻期自然补齐。
                LOGGER.warn("dropping unreadable bounty entry: {}", e.getMessage());
            }
        }
    }
}
