package com.miningdim.job.agent;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * 特勤悬赏服务端配置 (miningdim-agent.toml, 由 {@link AgentSystem} 注册)。
 *
 * 只放悬赏的绝对奖励量 (SpecialAgent_Job_DesignSpec 12.1 第 2 条: 绝对量走 config, 结构与曲线在代码)。默认值即
 * 2026-09-30 拍板的"保守"档, 与 {@link BountyRewardTable#DEFAULTS} 逐项一致。信用点三大 sink 修好之前, 这里是
 * 悬赏这个新增 faucet 唯一的调节阀: 真被玩出花来时改小数值或关掉 {@link #ENABLED}, 不用出新包。
 *
 * 槽位、星级门、讨伐只数属于第四章总表的结构, 不开配置 —— 改它们等于改职业设计, 应当回文档拍板。
 */
public final class AgentBountyConfig {

    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.BooleanValue ENABLED;
    public static final ForgeConfigSpec.LongValue DAILY_CREDIT_MIN;
    public static final ForgeConfigSpec.LongValue DAILY_CREDIT_MAX;
    public static final ForgeConfigSpec.LongValue DAILY_XP_MIN;
    public static final ForgeConfigSpec.LongValue DAILY_XP_MAX;
    public static final ForgeConfigSpec.LongValue WEEKLY_CREDIT_MIN;
    public static final ForgeConfigSpec.LongValue WEEKLY_CREDIT_MAX;
    public static final ForgeConfigSpec.LongValue WEEKLY_XP_MIN;
    public static final ForgeConfigSpec.LongValue WEEKLY_XP_MAX;
    public static final ForgeConfigSpec.LongValue WEEKLY_AZURE_MIN;
    public static final ForgeConfigSpec.LongValue WEEKLY_AZURE_MAX;
    public static final ForgeConfigSpec.LongValue WORLD_BOSS_CREDIT;
    public static final ForgeConfigSpec.LongValue WORLD_BOSS_XP;
    public static final ForgeConfigSpec.LongValue WORLD_BOSS_AZURE;

    private AgentBountyConfig() {
    }

    static {
        BountyRewardTable d = BountyRewardTable.DEFAULTS;
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.push("bounty");
        ENABLED = builder.comment("Master switch for agent bounties. When false the board is hidden, nothing can be "
                        + "accepted and kills do not advance bounties (already earned rewards are unaffected).")
                .define("enabled", true);
        builder.comment("Rewards scale linearly with the bounty's target star: star 1 pays the min, star 9 "
                + "(highest natural spawn) pays the max. Credits go through the global daily credit faucet decay.");
        DAILY_CREDIT_MIN = builder.defineInRange("dailyCreditMin", d.dailyCreditMin(), 0L, Long.MAX_VALUE);
        DAILY_CREDIT_MAX = builder.defineInRange("dailyCreditMax", d.dailyCreditMax(), 0L, Long.MAX_VALUE);
        DAILY_XP_MIN = builder.defineInRange("dailyXpMin", d.dailyXpMin(), 0L, Long.MAX_VALUE);
        DAILY_XP_MAX = builder.defineInRange("dailyXpMax", d.dailyXpMax(), 0L, Long.MAX_VALUE);
        WEEKLY_CREDIT_MIN = builder.defineInRange("weeklyCreditMin", d.weeklyCreditMin(), 0L, Long.MAX_VALUE);
        WEEKLY_CREDIT_MAX = builder.defineInRange("weeklyCreditMax", d.weeklyCreditMax(), 0L, Long.MAX_VALUE);
        WEEKLY_XP_MIN = builder.defineInRange("weeklyXpMin", d.weeklyXpMin(), 0L, Long.MAX_VALUE);
        WEEKLY_XP_MAX = builder.defineInRange("weeklyXpMax", d.weeklyXpMax(), 0L, Long.MAX_VALUE);
        WEEKLY_AZURE_MIN = builder.comment("Azure per weekly bounty; still limited by the weekly bounty azure cap "
                        + "(50) and the shared per-player daily azure cap (30).")
                .defineInRange("weeklyAzureMin", d.weeklyAzureMin(), 0L, Long.MAX_VALUE);
        WEEKLY_AZURE_MAX = builder.defineInRange("weeklyAzureMax", d.weeklyAzureMax(), 0L, Long.MAX_VALUE);
        WORLD_BOSS_CREDIT = builder.comment("World boss order: paid to every qualified level 8+ agent each time a "
                        + "world boss is killed; does not use a bounty slot.")
                .defineInRange("worldBossCredit", d.worldBossCredit(), 0L, Long.MAX_VALUE);
        WORLD_BOSS_XP = builder.defineInRange("worldBossXp", d.worldBossXp(), 0L, Long.MAX_VALUE);
        WORLD_BOSS_AZURE = builder.defineInRange("worldBossAzure", d.worldBossAzure(), 0L, Long.MAX_VALUE);
        builder.pop();

        SPEC = builder.build();
    }

    /** 悬赏是否开放 (配置尚未加载时按默认开放)。 */
    public static boolean enabled() {
        return !SPEC.isLoaded() || ENABLED.get();
    }

    /**
     * 当前奖励表。配置尚未加载 (启动早期) 时用拍板默认值; 运营把某项的 max 填得比 min 小时按 min 取, 不让一行
     * 手误把整个悬赏系统变成构造异常。
     */
    public static BountyRewardTable table() {
        if (!SPEC.isLoaded()) {
            return BountyRewardTable.DEFAULTS;
        }
        return new BountyRewardTable(
                DAILY_CREDIT_MIN.get(), Math.max(DAILY_CREDIT_MIN.get(), DAILY_CREDIT_MAX.get()),
                DAILY_XP_MIN.get(), Math.max(DAILY_XP_MIN.get(), DAILY_XP_MAX.get()),
                WEEKLY_CREDIT_MIN.get(), Math.max(WEEKLY_CREDIT_MIN.get(), WEEKLY_CREDIT_MAX.get()),
                WEEKLY_XP_MIN.get(), Math.max(WEEKLY_XP_MIN.get(), WEEKLY_XP_MAX.get()),
                WEEKLY_AZURE_MIN.get(), Math.max(WEEKLY_AZURE_MIN.get(), WEEKLY_AZURE_MAX.get()),
                WORLD_BOSS_CREDIT.get(), WORLD_BOSS_XP.get(), WORLD_BOSS_AZURE.get());
    }
}
