package com.miningdim.config;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

/**
 * SERVER 级配置 spec 持有者 (设计文档第十六章 16.2/16.3)。所有平衡数值的唯一来源 (C6),
 * 业务代码经 ModConfig (IMiningConfig 实现) 实时 *.get() 读取, 严禁缓存或硬编码同义裸常量。
 *
 * 字段与 16.2 各子表 1:1 对应; 默认值/范围照抄 16.2 表 (PENDING 项按表中建议初值)。
 * worldRestart 项 (16.4): regionSizeChunks / bufferChunks / layer.* —— 改值需重启, 运行时读到恒为启动值。
 *
 * 本期 (配置子系统) 仅暴露 IMiningConfig 接口声明的项 (16.2 全表 + maxGenWorkers 等)。第十八章 abuse 闸门
 * 配置不在 IMiningConfig 接口面内, 由反滥用子系统在其引入对应门面时自带 spec 段, 此处不预置无读取方的死键
 * (避免生成代码永不读取的配置项)。
 */
public final class MiningServerConfig {

    public static final ForgeConfigSpec SPEC;

    // ---- 16.2.1 实例治理 (instance) ----
    public static final ForgeConfigSpec.IntValue GLOBAL_CAP;
    public static final ForgeConfigSpec.EnumValue<OverflowPolicy> OVERFLOW_POLICY;
    public static final ForgeConfigSpec.BooleanValue SHARED_BY_DEFAULT;
    public static final ForgeConfigSpec.IntValue MAX_PARTY_SIZE;
    public static final ForgeConfigSpec.IntValue SHARE_CAP;
    public static final ForgeConfigSpec.IntValue REGION_SIZE_CHUNKS;
    public static final ForgeConfigSpec.IntValue BUFFER_CHUNKS;

    // ---- R2: 分层 Y 边界 (layer.*) 已删除 —— 难度由所在 region 决定, 不再按 worldY 分带 ----

    // ---- 16.2.3 矿物总控 (ore) ----
    public static final ForgeConfigSpec.IntValue ORE_BASE_WEIGHT;
    public static final ForgeConfigSpec.DoubleValue ORE_GLOBAL_DENSITY;
    public static final ForgeConfigSpec.BooleanValue ORE_USE_DATAPACK;

    // ---- 16.2.4 难度系数 (difficulty) ----
    public static final ForgeConfigSpec.DoubleValue EASY_MULTIPLIER;
    public static final ForgeConfigSpec.DoubleValue MEDIUM_MULTIPLIER;
    public static final ForgeConfigSpec.DoubleValue HARD_MULTIPLIER;

    // ---- 16.2.5 陷阱 (trap) ----
    public static final ForgeConfigSpec.DoubleValue TRAP_BASE_CHANCE;
    public static final ForgeConfigSpec.DoubleValue TRAP_LOCAL_RISK_MAX;
    public static final ForgeConfigSpec.BooleanValue TRAP_DYNAMIC_ENABLED;
    public static final ForgeConfigSpec.IntValue TRAP_MIN_SPACING;

    // ---- 16.2.6 压力 danger (danger) ----
    public static final ForgeConfigSpec.DoubleValue DANGER_MAX;
    public static final ForgeConfigSpec.DoubleValue DANGER_WEIGHT_ZONE;
    public static final ForgeConfigSpec.DoubleValue DANGER_WEIGHT_TIME;
    public static final ForgeConfigSpec.DoubleValue DANGER_WEIGHT_ORE;
    public static final ForgeConfigSpec.DoubleValue DANGER_TIME_SOFT_CAP;
    public static final ForgeConfigSpec.DoubleValue DANGER_DECAY_PER_EVAL;
    public static final ForgeConfigSpec.IntValue DANGER_EVAL_INTERVAL;

    // ---- 16.2.7 刷怪 (mob) ----
    public static final ForgeConfigSpec.IntValue MOB_SPAWN_INTERVAL;
    public static final ForgeConfigSpec.IntValue MOB_MAX_PER_PLAYER;
    public static final ForgeConfigSpec.IntValue MOB_MAX_PER_INSTANCE;
    public static final ForgeConfigSpec.DoubleValue MOB_BEHIND_CHANCE;
    public static final ForgeConfigSpec.IntValue MOB_SPAWN_RADIUS;

    // ---- 16.2.8 出生扫描 (spawn) ----
    public static final ForgeConfigSpec.IntValue SPAWN_HEADROOM;
    public static final ForgeConfigSpec.BooleanValue SPAWN_REQUIRE_SOLID_FLOOR;
    public static final ForgeConfigSpec.IntValue SPAWN_LAVA_AVOID_RADIUS;
    public static final ForgeConfigSpec.BooleanValue SPAWN_AVOID_TRAP_ZONES;
    public static final ForgeConfigSpec.IntValue SPAWN_POOL_SIZE;
    public static final ForgeConfigSpec.BooleanValue SPAWN_MUST_BE_MAIN;

    // ---- 16.2.9 重置 (reset) ----
    public static final ForgeConfigSpec.IntValue RESET_COOLDOWN_SECONDS;
    public static final ForgeConfigSpec.BooleanValue RESET_REQUIRE_EMPTY;
    public static final ForgeConfigSpec.BooleanValue RESET_KICK_ON_FORCE;
    public static final ForgeConfigSpec.IntValue RESET_CONFIRM_WINDOW_SECONDS;
    // R6: 每难度独立定时刷新 (0=关) + 撤离前倒计时广播秒数。
    public static final ForgeConfigSpec.IntValue AUTO_RESET_HOURS_EASY;
    public static final ForgeConfigSpec.IntValue AUTO_RESET_HOURS_MEDIUM;
    public static final ForgeConfigSpec.IntValue AUTO_RESET_HOURS_HARD;
    public static final ForgeConfigSpec.IntValue AUTO_RESET_WARN_SECONDS;

    // ---- R7: 维度内放置白名单 (rules) ----
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> PLACE_WHITELIST;

    // ---- R4: 入口浮空字文案 (entry; 每难度一条) ----
    public static final ForgeConfigSpec.ConfigValue<String> ENTRY_LABEL_EASY;
    public static final ForgeConfigSpec.ConfigValue<String> ENTRY_LABEL_MEDIUM;
    public static final ForgeConfigSpec.ConfigValue<String> ENTRY_LABEL_HARD;

    // ---- 16.2.10 性能与生命周期 (perf) ----
    public static final ForgeConfigSpec.IntValue LOAD_RADIUS_CHUNKS;
    public static final ForgeConfigSpec.IntValue EMPTY_TTL_SECONDS;
    public static final ForgeConfigSpec.IntValue GC_GRACE_SECONDS;
    public static final ForgeConfigSpec.IntValue GC_SCAN_INTERVAL;
    public static final ForgeConfigSpec.IntValue MAX_GEN_WORKERS;

    /** 16.2.1 instance.overflowPolicy 枚举值 (REJECT 拒绝 / QUEUE 排队)。 */
    public enum OverflowPolicy {
        REJECT,
        QUEUE
    }

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.push("instance");
        GLOBAL_CAP = b.comment("Global concurrent instance cap (D6); overflow handled by overflowPolicy")
                .defineInRange("globalCap", 32, 1, 256);
        OVERFLOW_POLICY = b.comment("Behavior when globalCap reached: REJECT entry or QUEUE the request")
                .defineEnum("overflowPolicy", OverflowPolicy.REJECT);
        SHARED_BY_DEFAULT = b.comment("Default to shared (same-difficulty) instances instead of private")
                .define("sharedByDefault", false);
        MAX_PARTY_SIZE = b.comment("Max party members per private instance")
                .defineInRange("maxPartySize", 4, 1, 16);
        SHARE_CAP = b.comment("Max concurrent players in a single shared instance")
                .defineInRange("shareCap", 8, 1, 64);
        REGION_SIZE_CHUNKS = b.comment("Region edge length in chunks; changing invalidates the existing grid")
                .worldRestart()
                .defineInRange("regionSizeChunks", 16, 4, 64);
        BUFFER_CHUNKS = b.comment("Solid buffer band width between regions, in chunks (>=1)")
                .worldRestart()
                .defineInRange("bufferChunks", 1, 1, 8);
        b.pop();

        // R2: layer.* 子盒 Y 配置已删除 —— 难度由所在 region 决定, 不再按 worldY 分带。

        b.push("ore");
        ORE_BASE_WEIGHT = b.comment("Base ore weight datum; weight = baseWeight * difficultyMultiplier")
                .defineInRange("baseWeight", 100, 1, 10000);
        ORE_GLOBAL_DENSITY = b.comment("Global ore density scale (debug/event use)")
                .defineInRange("globalDensity", 1.0, 0.0, 4.0);
        ORE_USE_DATAPACK = b.comment("Read datapack ore distribution JSON; false falls back to built-in table")
                .define("useDatapackDistribution", true);
        b.pop();

        b.push("difficulty");
        EASY_MULTIPLIER = b.comment("Easy unified multiplier baseline")
                .defineInRange("easyMultiplier", 1.0, 0.1, 5.0);
        MEDIUM_MULTIPLIER = b.defineInRange("mediumMultiplier", 1.5, 0.1, 5.0);
        HARD_MULTIPLIER = b.defineInRange("hardMultiplier", 2.5, 0.1, 5.0);
        b.pop();

        b.push("trap");
        TRAP_BASE_CHANCE = b.comment("trapChance = baseChance * difficulty * localRisk")
                .defineInRange("baseChance", 0.04, 0.0, 1.0);
        TRAP_LOCAL_RISK_MAX = b.comment("Local risk multiplier cap")
                .defineInRange("localRiskMax", 2.0, 1.0, 5.0);
        TRAP_DYNAMIC_ENABLED = b.comment("Enable dynamic traps (behind-player creeper / local collapse / lava burst)")
                .define("dynamicEnabled", true);
        TRAP_MIN_SPACING = b.comment("Minimum spacing between traps of the same kind, in blocks")
                .defineInRange("minSpacingBlocks", 6, 1, 32);
        b.pop();

        b.push("danger");
        DANGER_MAX = b.comment("DANGER_MAX cap (normalized [0,1] domain)")
                .defineInRange("max", 1.0, 0.1, 10.0);
        DANGER_WEIGHT_ZONE = b.comment("zoneDifficulty weight in danger = wZone*zone + wTime*time + wOre*ore")
                .defineInRange("weightZoneDifficulty", 1.0, 0.0, 10.0);
        DANGER_WEIGHT_TIME = b.defineInRange("weightTimeSpent", 0.5, 0.0, 10.0);
        DANGER_WEIGHT_ORE = b.defineInRange("weightOreRichness", 0.3, 0.0, 10.0);
        DANGER_TIME_SOFT_CAP = b.comment("timeSpent soft-cap convergence point in seconds: t' = cap*(1-e^(-t/cap))")
                .defineInRange("timeSoftCap", 60.0, 1.0, 600.0);
        DANGER_DECAY_PER_EVAL = b.comment("Per-eval decay applied when away / downsampled")
                .defineInRange("decayPerTickAway", 0.2, 0.0, 10.0);
        DANGER_EVAL_INTERVAL = b.comment("danger eval period in ticks (D7 downsample); matches DangerSyncS2C cadence")
                .defineInRange("evalIntervalTicks", 20, 1, 200);
        b.pop();

        b.push("mob");
        MOB_SPAWN_INTERVAL = b.comment("Base spawn eval interval; actual = base / (1 + danger/danger.max)")
                .defineInRange("spawnIntervalTicks", 100, 20, 1200);
        MOB_MAX_PER_PLAYER = b.comment("Active spawned-mob cap around each player")
                .defineInRange("maxPerPlayer", 8, 0, 64);
        MOB_MAX_PER_INSTANCE = b.comment("Active spawned-mob hard cap per instance (anti-lag)")
                .defineInRange("maxPerInstance", 30, 0, 256);
        MOB_BEHIND_CHANCE = b.comment("Behind-player spawn chance under high danger")
                .defineInRange("behindPlayerChance", 0.5, 0.0, 1.0);
        MOB_SPAWN_RADIUS = b.comment("Spawn radius in blocks")
                .defineInRange("spawnRadius", 24, 4, 64);
        b.pop();

        b.push("spawn");
        SPAWN_HEADROOM = b.comment("Air blocks required above spawn point")
                .defineInRange("headroomBlocks", 2, 1, 4);
        SPAWN_REQUIRE_SOLID_FLOOR = b.comment("Require a solid block under the spawn point")
                .define("requireSolidFloor", true);
        SPAWN_LAVA_AVOID_RADIUS = b.comment("Avoid lava within this radius of spawn")
                .defineInRange("lavaAvoidRadius", 3, 0, 8);
        SPAWN_AVOID_TRAP_ZONES = b.comment("Spawn point must not be inside a trap zone")
                .define("avoidTrapZones", true);
        SPAWN_POOL_SIZE = b.comment("Number of pre-generated spawn candidates")
                .defineInRange("poolSize", 8, 1, 64);
        SPAWN_MUST_BE_MAIN = b.comment("Spawn point must belong to the main connected component (D4; false only for debug)")
                .define("mustBeMainComponent", true);
        b.pop();

        b.push("reset");
        RESET_COOLDOWN_SECONDS = b.comment("Minimum cooldown between two resets of the same instance, in seconds")
                .defineInRange("cooldownSeconds", 300, 0, 86400);
        RESET_REQUIRE_EMPTY = b.comment("Require instance to be empty before reset")
                .define("requireEmpty", true);
        RESET_KICK_ON_FORCE = b.comment("On forced reset, evacuate present players first")
                .define("kickOnForceReset", true);
        RESET_CONFIRM_WINDOW_SECONDS = b.comment("Second-confirmation window for destructive reset, in seconds")
                .defineInRange("confirmationWindowSeconds", 15, 5, 120);
        AUTO_RESET_HOURS_EASY = b.comment("R6: auto-reset period for the Easy region, in hours (0 = disabled)")
                .defineInRange("autoResetHoursEasy", 6, 0, 168);
        AUTO_RESET_HOURS_MEDIUM = b.comment("R6: auto-reset period for the Medium region, in hours (0 = disabled)")
                .defineInRange("autoResetHoursMedium", 4, 0, 168);
        AUTO_RESET_HOURS_HARD = b.comment("R6: auto-reset period for the Hard region, in hours (0 = disabled)")
                .defineInRange("autoResetHoursHard", 2, 0, 168);
        AUTO_RESET_WARN_SECONDS = b.comment("R6: countdown broadcast / warning before auto-reset evacuation, in seconds")
                .defineInRange("autoResetWarnSeconds", 60, 0, 600);
        b.pop();

        b.push("rules");
        PLACE_WHITELIST = b.comment("R7: block ids that may be placed inside the mining dimension; placing anything else is cancelled")
                .defineList("placeWhitelist",
                        java.util.List.of("minecraft:scaffolding"),
                        o -> o instanceof String);
        b.pop();

        b.push("entry");
        b.comment("R4: floating label text shown above each entry block (translatable not used; raw display text).");
        ENTRY_LABEL_EASY = b.define("labelEasy", "Easy 矿洞 / 右键进入");
        ENTRY_LABEL_MEDIUM = b.define("labelMedium", "Medium 矿洞 / 右键进入");
        ENTRY_LABEL_HARD = b.define("labelHard", "Hard 矿洞 / 右键进入");
        b.pop();

        b.push("perf");
        LOAD_RADIUS_CHUNKS = b.comment("Force-load radius in chunks when an instance activates")
                .defineInRange("loadRadiusChunks", 4, 2, 16);
        EMPTY_TTL_SECONDS = b.comment("TTL for empty instances before GC candidacy, in seconds")
                .defineInRange("emptyInstanceTtlSeconds", 300, 0, 86400);
        GC_GRACE_SECONDS = b.comment("GC grace period after lastEmptyTick, in seconds (D6)")
                .defineInRange("gcGraceSeconds", 120, 0, 3600);
        GC_SCAN_INTERVAL = b.comment("Orphan/empty instance scan period, in ticks")
                .defineInRange("gcScanIntervalTicks", 200, 20, 6000);
        MAX_GEN_WORKERS = b.comment("Offline voxel-generation worker thread cap (D2/D8)")
                .defineInRange("maxGenWorkers", 2, 1, 8);
        b.pop();

        SPEC = b.build();
    }

    private MiningServerConfig() {
    }
}
