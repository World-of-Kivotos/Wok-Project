package com.miningdim.achievement;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * 成就系统服务端配置 (miningdim-achievement.toml): 有效撤离、计数挖掘与困难作业时长的阈值
 * (Achievement_System_DesignSpec 6.1 / 6.3, 2026-09-26 服主确认), 以及任务领取计数的每日上限 (6.1)。
 *
 * 这些阈值是本模块自己的口径, 不读任务模块的 {@code QuestConfig.extractionMinDwellTicks}, 即使默认值相同: 两边的
 * 用途不同, 任何一边调整都不该牵动另一边。由 {@link AchievementSystem} 走标准的 registerConfig 注册, 支持运行期热改
 * (读取方每次实时取值, 不在启动时快照), GameTest 下由 GameTestConfigWatchGuard 统一摘掉监视器。
 */
public final class AchievementConfig {

    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.IntValue EXTRACTION_MIN_DWELL_TICKS;
    public static final ForgeConfigSpec.IntValue MIN_TRIP_BLOCKS;
    public static final ForgeConfigSpec.IntValue DAILY_EXTRACTION_CAP;
    public static final ForgeConfigSpec.IntValue DAILY_HARD_EXTRACTION_CAP;
    public static final ForgeConfigSpec.IntValue HARD_ACTIVE_GAP_CAP_TICKS;
    public static final ForgeConfigSpec.IntValue HARD_ACTIVE_PER_BLOCK_CAP_TICKS;
    public static final ForgeConfigSpec.IntValue DAILY_QUEST_CAP;

    private AchievementConfig() {
    }

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.push("extraction");
        EXTRACTION_MIN_DWELL_TICKS = builder.comment(
                        "Minimum ticks a trip must last (measured on the overworld game time) for leaving the mining "
                                + "dimension to count as a valid extraction (6000 = 5 minutes). Entering is free and "
                                + "leaving is instant, so without this gate extractions could be farmed in minutes")
                .defineInRange("extractionMinDwellTicks", 6_000, 0, 1_728_000);
        MIN_TRIP_BLOCKS = builder.comment(
                        "Minimum number of counted block breaks during the trip for an extraction to be valid")
                .defineInRange("minTripBlocks", 32, 0, 100_000);
        DAILY_EXTRACTION_CAP = builder.comment(
                        "How many valid extractions per player and UTC day count towards the mining_extractions stat")
                .defineInRange("dailyExtractionCap", 5, 0, 1_000);
        DAILY_HARD_EXTRACTION_CAP = builder.comment(
                        "How many valid hard-difficulty extractions per player and UTC day count towards the "
                                + "mining_extractions_hard stat (counted separately from dailyExtractionCap)")
                .defineInRange("dailyHardExtractionCap", 2, 0, 1_000);
        builder.pop();

        builder.push("hardActive");
        HARD_ACTIVE_GAP_CAP_TICKS = builder.comment(
                        "Upper bound (ticks) credited for the gap between two consecutive counted block breaks when a "
                                + "valid hard extraction adds to the mining_hard_active_ticks stat (3600 = 3 minutes)")
                .defineInRange("hardActiveGapCapTicks", 3_600, 1, 1_728_000);
        HARD_ACTIVE_PER_BLOCK_CAP_TICKS = builder.comment(
                        "The active time credited for one trip never exceeds its counted block breaks times this "
                                + "many ticks (600 = 30 seconds per block)")
                .defineInRange("hardActivePerBlockCapTicks", 600, 1, 1_728_000);
        builder.pop();

        builder.push("quest");
        DAILY_QUEST_CAP = builder.comment(
                        "How many claimed quest rewards per player and UTC day count towards the quests_completed stat")
                .defineInRange("dailyQuestCap", 6, 0, 1_000);
        builder.pop();

        SPEC = builder.build();
    }
}
