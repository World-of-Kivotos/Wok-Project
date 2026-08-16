package com.miningdim.quest;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * 任务系统服务端配置 (miningdim-quest.toml)。
 *
 * <b>全部经济数值都在这里, 一条都不写死在内容池里。</b> 原因见 docs/Economy_Completeness_Audit.md: 信用点的
 * 三大 sink 当前全线失效, 全服净流入尚未做过核对, 任务奖励是一个新增 faucet, 数值必然要随经济总表反复调。
 * 把它们集中在一个 TOML 里, 调平衡不需要改代码更不需要重新编译。
 *
 * 默认值的标定锚点 (取自既有经济常量): 衰减档 60,000 CP/档 · 钻石约 500 CP · 开箱 50,000 CP。日常基数 2,000
 * 是从"每日保底一万"倒推的 (用户决策): 3 条简单档 x 2,000 x 难度 1 + 1 条难档 x 2,000 x 难度 2 = 10,000 CP,
 * 折合约 20 颗钻石。这是<b>全额到手</b>而非过闸前的毛额 —— 理由见下。
 *
 * <b>任务奖励不过全服衰减主闸 (用户决策)。</b> 发奖走
 * {@link com.miningdim.economy.EconomyConstants#QUEST_DAILY_CREDIT_FAUCET_KEY} 这个独立键 + 下方
 * {@link #FAUCET_TIER} 这个正常游玩够不到的档位, 于是实发恒等于名义值。判据写在那个常量的注释里, 一句话:
 * 任务的供给由槽位数硬封, 不是靠衰减封 —— 玩家再肝也变不出第五条日常, 而任务对新人的意义正是<b>可预期</b>的
 * 保底收入, 再叠一层衰减只会把它变成一个玩家算不明白的随机数。
 *
 * 独立键<b>不等于</b>不计数: 任务注入的信用点照样按日累计, 运营查得到; 真被玩出花来时把 {@link #FAUCET_TIER}
 * 调小即可当场恢复衰减, 不用改代码。任何产能不受槽位约束的新 faucet 一律并回全服主闸, 不得援引本例。
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

    public static final ForgeConfigSpec.LongValue FAUCET_TIER;
    public static final ForgeConfigSpec.LongValue DAILY_REWARD_BASE;
    public static final ForgeConfigSpec.LongValue WEEKLY_REWARD_BASE;
    public static final ForgeConfigSpec.LongValue SPECIAL_REWARD_BASE;
    public static final ForgeConfigSpec.LongValue HIDDEN_REWARD_BASE;

    public static final ForgeConfigSpec.DoubleValue DAILY_BOOK_CHANCE;
    public static final ForgeConfigSpec.DoubleValue WEEKLY_BOOK_CHANCE;

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
        FAUCET_TIER = builder.comment(
                        "Decay tier size for the quest-only CREDIT faucet counter (quest_faucet). Quest payouts "
                                + "deliberately do NOT join the global credit_faucet soft cap: quest supply is hard "
                                + "capped by the number of slots dealt, not by grinding, so the cap is 'how many "
                                + "quests', not 'how much each one is worth'. The default is far above what a day of "
                                + "quests can ever total, so payouts are never decayed. Lower it to re-gate quest "
                                + "income without touching code")
                .defineInRange("faucetTier", 1_000_000L, 1L, Long.MAX_VALUE);
        DAILY_REWARD_BASE = builder.comment(
                        "Base CREDIT for a daily quest; actual payout is base * difficulty (difficulty is 1..3). "
                                + "The default is back-solved from a 10,000 CREDIT daily floor: three easy slots at "
                                + "difficulty 1 plus one hard slot at difficulty 2 = 10,000")
                .defineInRange("dailyBase", 2_000L, 0L, Long.MAX_VALUE);
        WEEKLY_REWARD_BASE = builder.comment("Base CREDIT for a weekly quest; payout is base * difficulty")
                .defineInRange("weeklyBase", 6_000L, 0L, Long.MAX_VALUE);
        SPECIAL_REWARD_BASE = builder.comment("Base CREDIT for a special quest; payout is base * difficulty")
                .defineInRange("specialBase", 800L, 0L, Long.MAX_VALUE);
        HIDDEN_REWARD_BASE = builder.comment("Base CREDIT for one hidden chain stage; payout is base * difficulty")
                .defineInRange("hiddenBase", 4_000L, 0L, Long.MAX_VALUE);
        DAILY_BOOK_CHANCE = builder.comment(
                        "Chance that claiming a daily (or special) quest also yields an enchanted book. Rolled "
                                + "independently of the guaranteed material drop, so a book never costs the player "
                                + "their materials")
                .defineInRange("dailyBookChance", 0.04D, 0.0D, 1.0D);
        WEEKLY_BOOK_CHANCE = builder.comment(
                        "Chance that claiming a weekly (or hidden chain) quest also yields an enchanted book")
                .defineInRange("weeklyBookChance", 0.30D, 0.0D, 1.0D);
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
