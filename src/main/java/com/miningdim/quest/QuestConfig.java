package com.miningdim.quest;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * 任务系统服务端配置 (miningdim-quest.toml)。
 *
 * <b>全部经济数值都在这里, 一条都不写死在内容池里。</b> 原因见 docs/Economy_Completeness_Audit.md: 信用点的
 * 三大 sink 当前全线失效, 全服净流入尚未做过核对, 任务奖励是一个新增 faucet, 数值必然要随经济总表反复调。
 * 把它们集中在一个 TOML 里, 调平衡不需要改代码更不需要重新编译。
 *
 * 默认值的标定锚点 (取自既有经济常量): 衰减档 60,000 CP/档 · 钻石约 500 CP · 开箱 50,000 CP。按默认值, 4 条
 * 每日任务约 7,200 CP/天, 折合约 14 颗钻石, 明显低于一个衰减档 —— 刻意保守, 宁可上线后往上调。
 *
 * <b>这里没有"任务每日信用点上限"这一项, 是刻意的。</b> 任务奖励与卖矿卖菜共用
 * {@link com.miningdim.economy.EconomyConstants#GLOBAL_DAILY_CREDIT_FAUCET_KEY} 这一个 faucet 键与同一个
 * 60,000 衰减档 (见 {@link QuestRewards})。给任务再配一个私有上限, 正是经济文档 8.5 与既往审计判定过的缺陷
 * ——"各 faucet 各算私有上限"等于在全服统一软上限之外另开一个口子。
 */
public final class QuestConfig {

    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.BooleanValue ENABLED;
    public static final ForgeConfigSpec.IntValue DAILY_SLOTS;
    public static final ForgeConfigSpec.IntValue DAILY_HARD_SLOTS;
    public static final ForgeConfigSpec.IntValue WEEKLY_SLOTS;
    public static final ForgeConfigSpec.IntValue MAX_ACTIVE_SPECIAL;
    public static final ForgeConfigSpec.IntValue EXTRACTION_MIN_DWELL_TICKS;

    public static final ForgeConfigSpec.LongValue DAILY_REFRESH_COST;
    public static final ForgeConfigSpec.LongValue WEEKLY_REFRESH_COST;

    public static final ForgeConfigSpec.LongValue DAILY_REWARD_BASE;
    public static final ForgeConfigSpec.LongValue WEEKLY_REWARD_BASE;
    public static final ForgeConfigSpec.LongValue SPECIAL_REWARD_BASE;
    public static final ForgeConfigSpec.LongValue HIDDEN_REWARD_BASE;

    public static final ForgeConfigSpec.DoubleValue VILLAGE_TRIGGER_CHANCE;
    public static final ForgeConfigSpec.IntValue VILLAGE_TRIGGER_COOLDOWN_TICKS;
    public static final ForgeConfigSpec.IntValue STRUCTURE_SCAN_INTERVAL_TICKS;
    public static final ForgeConfigSpec.IntValue CHAIN_UNLOCK_SCAN_INTERVAL_TICKS;

    private QuestConfig() {
    }

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.push("quest");
        ENABLED = builder.comment("Whether the quest system is enabled")
                .define("enabled", true);
        DAILY_SLOTS = builder.comment("How many daily quests are dealt on each UTC day rollover")
                .defineInRange("dailySlots", 4, 1, 12);
        DAILY_HARD_SLOTS = builder.comment(
                        "How many of the daily slots are drawn from the hard tier (difficulty >= 2); the rest come "
                                + "from the easy tier. Rerolling a slot always redraws within that slot's own tier, "
                                + "otherwise paying to turn a hard slot into an easy one would be a fixed arbitrage")
                .defineInRange("dailyHardSlots", 1, 0, 12);
        WEEKLY_SLOTS = builder.comment("How many weekly quests are dealt on each ISO week rollover")
                .defineInRange("weeklySlots", 1, 1, 6);
        MAX_ACTIVE_SPECIAL = builder.comment(
                        "Maximum simultaneously active special (event-triggered) quests per player")
                .defineInRange("maxActiveSpecial", 2, 1, 10);
        builder.pop();

        builder.push("refresh");
        DAILY_REFRESH_COST = builder.comment(
                        "CREDIT destroyed to reroll one daily quest slot; this is a sink, not a transfer")
                .defineInRange("dailyCost", 500L, 0L, Long.MAX_VALUE);
        WEEKLY_REFRESH_COST = builder.comment("CREDIT destroyed to reroll one weekly quest slot")
                .defineInRange("weeklyCost", 2_500L, 0L, Long.MAX_VALUE);
        builder.pop();

        builder.push("reward");
        DAILY_REWARD_BASE = builder.comment(
                        "Base CREDIT for a daily quest; actual payout is base * difficulty (difficulty is 1..3)")
                .defineInRange("dailyBase", 1_200L, 0L, Long.MAX_VALUE);
        WEEKLY_REWARD_BASE = builder.comment("Base CREDIT for a weekly quest; payout is base * difficulty")
                .defineInRange("weeklyBase", 6_000L, 0L, Long.MAX_VALUE);
        SPECIAL_REWARD_BASE = builder.comment("Base CREDIT for a special quest; payout is base * difficulty")
                .defineInRange("specialBase", 800L, 0L, Long.MAX_VALUE);
        HIDDEN_REWARD_BASE = builder.comment("Base CREDIT for one hidden chain stage; payout is base * difficulty")
                .defineInRange("hiddenBase", 4_000L, 0L, Long.MAX_VALUE);
        builder.pop();

        builder.push("triggers");
        VILLAGE_TRIGGER_CHANCE = builder.comment(
                        "Probability of offering a special quest when a player is first seen inside a village")
                .defineInRange("villageChance", 0.15D, 0.0D, 1.0D);
        VILLAGE_TRIGGER_COOLDOWN_TICKS = builder.comment(
                        "Minimum ticks between two special-quest offers for one player (6000 = 5 minutes)")
                .defineInRange("cooldownTicks", 6_000, 20, 1_728_000);
        STRUCTURE_SCAN_INTERVAL_TICKS = builder.comment(
                        "How often a player's position is tested against structures; structure lookup touches "
                                + "chunk structure starts, so do not lower this without measuring")
                .defineInRange("structureScanIntervalTicks", 40, 5, 1_200);
        EXTRACTION_MIN_DWELL_TICKS = builder.comment(
                        "Minimum ticks a player must stay inside the mining dimension for a voluntary exit to count "
                                + "as an extraction (6000 = 5 minutes). Without this gate, entering and immediately "
                                + "leaving costs nothing and the objective degrades into an unlimited free action")
                .defineInRange("extractionMinDwellTicks", 6_000, 0, 1_728_000);
        CHAIN_UNLOCK_SCAN_INTERVAL_TICKS = builder.comment(
                        "How often a player's inventory is scanned for hidden-chain unlock items (e.g. a sniper "
                                + "rifle for the marksman chain)")
                .defineInRange("chainUnlockScanIntervalTicks", 100, 20, 1_200);
        builder.pop();

        SPEC = builder.build();
    }
}
