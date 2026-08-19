package com.miningdim.config;

import com.miningdim.core.Difficulty;
import com.miningdim.core.IMiningConfig;

/**
 * 配置门面实现 (设计文档第十六章, IMiningConfig)。每个 getter 实时读 MiningServerConfig 对应 spec 值的 .get(),
 * 不缓存 —— 保证 /reload 后非 worldRestart 项即时生效 (16.8)。
 *
 * 读取时机安全性 (16.3): 业务系统在世界加载后才运行, 天然晚于 ModConfigEvent.Loading, spec 此时已就绪。
 * worldRestart 项 (regionSizeChunks/bufferChunks/layer.*) 即便此处实时读, ForgeConfigSpec 在运行时返回的
 * 也恒为启动时值 (Forge 对 worldRestart 项不热更), 与接口注释一致。
 *
 * 类名为 ModConfig (任务指定); 与 Forge 的 net.minecraftforge.fml.config.ModConfig 同简单名, 本类不 import 它,
 * 注册时用全限定名引用 Forge 类型, 不产生歧义。
 */
public final class ModConfig implements IMiningConfig {

    // ---- 16.2.1 实例治理 ----

    @Override
    public int globalCap() {
        return MiningServerConfig.GLOBAL_CAP.get();
    }

    @Override
    public boolean queueOnOverflow() {
        return MiningServerConfig.OVERFLOW_POLICY.get() == MiningServerConfig.OverflowPolicy.QUEUE;
    }

    @Override
    public boolean sharedByDefault() {
        return MiningServerConfig.SHARED_BY_DEFAULT.get();
    }

    @Override
    public int maxPartySize() {
        return MiningServerConfig.MAX_PARTY_SIZE.get();
    }

    @Override
    public int shareCap() {
        return MiningServerConfig.SHARE_CAP.get();
    }

    @Override
    public int regionSizeChunks() {
        return MiningServerConfig.REGION_SIZE_CHUNKS.get();
    }

    @Override
    public int bufferChunks() {
        return MiningServerConfig.BUFFER_CHUNKS.get();
    }

    // ---- R2: 分层 Y 边界实现已删除 (难度由所在 region 决定) ----

    // ---- 16.2.3 矿物总控 ----

    @Override
    public int oreBaseWeight() {
        return MiningServerConfig.ORE_BASE_WEIGHT.get();
    }

    @Override
    public double oreGlobalDensity() {
        return MiningServerConfig.ORE_GLOBAL_DENSITY.get();
    }

    @Override
    public boolean useDatapackOreDistribution() {
        return MiningServerConfig.ORE_USE_DATAPACK.get();
    }

    // ---- 16.2.4 难度系数 ----

    @Override
    public double difficultyMultiplier(Difficulty difficulty) {
        return switch (difficulty) {
            case EASY -> MiningServerConfig.EASY_MULTIPLIER.get();
            case MEDIUM -> MiningServerConfig.MEDIUM_MULTIPLIER.get();
            case HARD -> MiningServerConfig.HARD_MULTIPLIER.get();
        };
    }

    // ---- 16.2.5 陷阱 ----

    @Override
    public double trapBaseChance() {
        return MiningServerConfig.TRAP_BASE_CHANCE.get();
    }

    @Override
    public double trapLocalRiskMax() {
        return MiningServerConfig.TRAP_LOCAL_RISK_MAX.get();
    }

    @Override
    public boolean trapDynamicEnabled() {
        return MiningServerConfig.TRAP_DYNAMIC_ENABLED.get();
    }

    @Override
    public int trapMinSpacingBlocks() {
        return MiningServerConfig.TRAP_MIN_SPACING.get();
    }

    // ---- 16.2.6 压力 danger ----

    @Override
    public double dangerMax() {
        return MiningServerConfig.DANGER_MAX.get();
    }

    @Override
    public double dangerWeightZone() {
        return MiningServerConfig.DANGER_WEIGHT_ZONE.get();
    }

    @Override
    public double dangerWeightTime() {
        return MiningServerConfig.DANGER_WEIGHT_TIME.get();
    }

    @Override
    public double dangerWeightOre() {
        return MiningServerConfig.DANGER_WEIGHT_ORE.get();
    }

    @Override
    public double dangerTimeSoftCap() {
        return MiningServerConfig.DANGER_TIME_SOFT_CAP.get();
    }

    @Override
    public double dangerDecayPerEval() {
        return MiningServerConfig.DANGER_DECAY_PER_EVAL.get();
    }

    @Override
    public int dangerEvalIntervalTicks() {
        return MiningServerConfig.DANGER_EVAL_INTERVAL.get();
    }

    // ---- 16.2.7 刷怪 ----

    @Override
    public int mobSpawnIntervalTicks() {
        return MiningServerConfig.MOB_SPAWN_INTERVAL.get();
    }

    @Override
    public int mobMaxPerPlayer() {
        return MiningServerConfig.MOB_MAX_PER_PLAYER.get();
    }

    @Override
    public int mobMaxPerInstance() {
        return MiningServerConfig.MOB_MAX_PER_INSTANCE.get();
    }

    @Override
    public double mobBehindPlayerChance() {
        return MiningServerConfig.MOB_BEHIND_CHANCE.get();
    }

    @Override
    public int mobSpawnRadius() {
        return MiningServerConfig.MOB_SPAWN_RADIUS.get();
    }

    // ---- 16.2.8 出生扫描 ----

    @Override
    public int spawnHeadroomBlocks() {
        return MiningServerConfig.SPAWN_HEADROOM.get();
    }

    @Override
    public boolean spawnRequireSolidFloor() {
        return MiningServerConfig.SPAWN_REQUIRE_SOLID_FLOOR.get();
    }

    @Override
    public int spawnLavaAvoidRadius() {
        return MiningServerConfig.SPAWN_LAVA_AVOID_RADIUS.get();
    }

    @Override
    public boolean spawnAvoidTrapZones() {
        return MiningServerConfig.SPAWN_AVOID_TRAP_ZONES.get();
    }

    @Override
    public int spawnPoolSize() {
        return MiningServerConfig.SPAWN_POOL_SIZE.get();
    }

    @Override
    public boolean spawnMustBeMainComponent() {
        return MiningServerConfig.SPAWN_MUST_BE_MAIN.get();
    }

    // ---- 16.2.9 重置 ----

    @Override
    public int resetCooldownSeconds() {
        return MiningServerConfig.RESET_COOLDOWN_SECONDS.get();
    }

    @Override
    public boolean resetRequireEmpty() {
        return MiningServerConfig.RESET_REQUIRE_EMPTY.get();
    }

    @Override
    public boolean resetKickOnForce() {
        return MiningServerConfig.RESET_KICK_ON_FORCE.get();
    }

    @Override
    public int resetConfirmationWindowSeconds() {
        return MiningServerConfig.RESET_CONFIRM_WINDOW_SECONDS.get();
    }

    @Override
    public int autoResetHours(Difficulty difficulty) {
        return switch (difficulty) {
            case EASY -> MiningServerConfig.AUTO_RESET_HOURS_EASY.get();
            case MEDIUM -> MiningServerConfig.AUTO_RESET_HOURS_MEDIUM.get();
            case HARD -> MiningServerConfig.AUTO_RESET_HOURS_HARD.get();
        };
    }

    @Override
    public int autoResetWarnSeconds() {
        return MiningServerConfig.AUTO_RESET_WARN_SECONDS.get();
    }

    // ---- R7: 放置白名单 ----

    @Override
    public java.util.List<String> placeWhitelist() {
        // ConfigValue<List<? extends String>> -> 拷贝为不可变 List<String>, 防调用方改动 spec 内部列表。
        return java.util.List.copyOf(MiningServerConfig.PLACE_WHITELIST.get());
    }

    // ---- R4: 入口浮空字文案 ----

    @Override
    public String entryLabel(Difficulty difficulty) {
        return switch (difficulty) {
            case EASY -> MiningServerConfig.ENTRY_LABEL_EASY.get();
            case MEDIUM -> MiningServerConfig.ENTRY_LABEL_MEDIUM.get();
            case HARD -> MiningServerConfig.ENTRY_LABEL_HARD.get();
        };
    }

    @Override
    public long entryFee(Difficulty difficulty) {
        return switch (difficulty) {
            case EASY -> MiningServerConfig.ENTRY_FEE_EASY.get();
            case MEDIUM -> MiningServerConfig.ENTRY_FEE_MEDIUM.get();
            case HARD -> MiningServerConfig.ENTRY_FEE_HARD.get();
        };
    }

    // ---- 16.2.10 性能与生命周期 ----

    @Override
    public int loadRadiusChunks() {
        return MiningServerConfig.LOAD_RADIUS_CHUNKS.get();
    }

    @Override
    public int emptyInstanceTtlSeconds() {
        return MiningServerConfig.EMPTY_TTL_SECONDS.get();
    }

    @Override
    public int gcGraceSeconds() {
        return MiningServerConfig.GC_GRACE_SECONDS.get();
    }

    @Override
    public int gcScanIntervalTicks() {
        return MiningServerConfig.GC_SCAN_INTERVAL.get();
    }

    @Override
    public int maxGenWorkers() {
        return MiningServerConfig.MAX_GEN_WORKERS.get();
    }

    @Override
    public int creativeFlightMaxBlocksPerTick() {
        return MiningServerConfig.CREATIVE_FLIGHT_MAX_BLOCKS_PER_TICK.get();
    }
}
