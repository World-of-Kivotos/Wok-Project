package com.miningdim.core;

/**
 * 配置门面 (设计文档第十六章, SERVER 级 ForgeConfigSpec)。所有平衡数值的唯一来源 (C6),
 * 业务代码严禁出现同义裸常量。实现 (ConfigSystem) 在每个 getter 内实时 *.get(), 不缓存,
 * 以保证 /reload 后非 worldRestart 项即时生效 (16.8)。
 *
 * 读取时机: 生成/刷怪系统在世界加载后才运行, 天然晚于 ModConfigEvent.Loading, 故读取安全 (16.3)。
 * 方法分组对应 16.2 各子表。worldRestart 标记的项 (regionSizeChunks/bufferChunks/layer.*) 改值需重启,
 * 运行时读到的恒为启动时值。
 */
public interface IMiningConfig {

    // ---- 16.2.1 实例治理 ----

    /** 全局并发实例上限 (instance.globalCap, 默认 32)。 */
    int globalCap();

    /** 超上限策略: true=排队(QUEUE), false=拒绝(REJECT) (instance.overflowPolicy)。 */
    boolean queueOnOverflow();

    /** 默认是否共享实例 (instance.sharedByDefault, 默认 false)。 */
    boolean sharedByDefault();

    /** 单实例最大组队人数 (instance.maxPartySize, 默认 4)。 */
    int maxPartySize();

    /** 单共享实例最大在场人数 (instance.shareCap, 默认 8)。 */
    int shareCap();

    /** 单实例 region 边长 (区块数, instance.regionSizeChunks, 默认 16; worldRestart)。 */
    int regionSizeChunks();

    /** region 间实心缓冲带宽度 (区块, instance.bufferChunks, 默认 1, >=1; worldRestart)。 */
    int bufferChunks();

    // ---- R2: 分层 Y 边界 getter 已删除 (难度由所在 region 决定, 不再按 worldY 分带) ----

    // ---- 16.2.3 矿物总控 ----

    /** 矿物基础权重基准 (ore.baseWeight, 默认 100)。 */
    int oreBaseWeight();

    /** 全局矿物密度缩放 (ore.globalDensity, 默认 1.0)。 */
    double oreGlobalDensity();

    /** 是否读 datapack 矿物分布表 (ore.useDatapackDistribution, 默认 true)。 */
    boolean useDatapackOreDistribution();

    // ---- 16.2.4 难度系数 ----

    /** 指定难度的统一乘子基线 (difficulty.<d>Multiplier; Easy 1.0/Medium 1.5/Hard 2.5)。 */
    double difficultyMultiplier(Difficulty difficulty);

    // ---- 16.2.5 陷阱 ----

    /** 陷阱基础概率 (trap.baseChance, 默认 0.04); trapChance = baseChance * difficulty * localRisk。 */
    double trapBaseChance();

    /** 局部风险封顶 (trap.localRiskMax, 默认 2.0)。 */
    double trapLocalRiskMax();

    /** 是否启用动态陷阱 (trap.dynamicEnabled, 默认 true)。 */
    boolean trapDynamicEnabled();

    /** 同类陷阱最小间距 (trap.minSpacingBlocks, 默认 6)。 */
    int trapMinSpacingBlocks();

    // ---- 16.2.6 压力 danger (归一化 [0,1]) ----

    /** DANGER_MAX 封顶 (danger.max, 默认 1.0)。 */
    double dangerMax();

    /** zoneDifficulty 权重 (danger.weightZoneDifficulty, 默认 1.0)。 */
    double dangerWeightZone();

    /** timeSpent 权重 (danger.weightTimeSpent, 默认 0.5)。 */
    double dangerWeightTime();

    /** oreRichness 权重 (danger.weightOreRichness, 默认 0.3)。 */
    double dangerWeightOre();

    /** timeSpent 软封顶收敛点 (秒, danger.timeSoftCap, 默认 60)。 */
    double dangerTimeSoftCap();

    /** 离区/降频时每评估周期衰减量 (danger.decayPerTickAway, 默认 0.2)。 */
    double dangerDecayPerEval();

    /** danger 评估周期 (tick, danger.evalIntervalTicks, 默认 20)。 */
    int dangerEvalIntervalTicks();

    // ---- 16.2.7 刷怪 ----

    /** 基础刷怪评估间隔 (tick, mob.spawnIntervalTicks, 默认 100)。 */
    int mobSpawnIntervalTicks();

    /** 每玩家周边活跃 mod 刷怪上限 (mob.maxPerPlayer, 默认 8)。 */
    int mobMaxPerPlayer();

    /** 单实例活跃 mod 刷怪硬上限 (mob.maxPerInstance, 默认 30, 防卡服)。 */
    int mobMaxPerInstance();

    /** 高 danger 时"后方生成"概率 (mob.behindPlayerChance, 默认 0.5)。 */
    double mobBehindPlayerChance();

    /** 刷怪生成半径 (方块, mob.spawnRadius, 默认 24)。 */
    int mobSpawnRadius();

    // ---- 16.2.8 出生扫描 ----

    /** 头顶需空气格数 (spawn.headroomBlocks, 默认 2)。 */
    int spawnHeadroomBlocks();

    /** 脚下须固体 (spawn.requireSolidFloor, 默认 true)。 */
    boolean spawnRequireSolidFloor();

    /** 周围禁岩浆半径 (spawn.lavaAvoidRadius, 默认 3)。 */
    int spawnLavaAvoidRadius();

    /** 出生点是否避开陷阱区 (spawn.avoidTrapZones, 默认 true)。 */
    boolean spawnAvoidTrapZones();

    /** 预生成 spawn pool 候选点数 (spawn.poolSize, 默认 8)。 */
    int spawnPoolSize();

    /** 出生点是否须属主连通分量 (spawn.mustBeMainComponent, 默认 true, 强制; D4)。 */
    boolean spawnMustBeMainComponent();

    // ---- 16.2.9 重置 ----

    /** 同实例两次重置最小冷却 (秒, reset.cooldownSeconds, 默认 300)。 */
    int resetCooldownSeconds();

    /** 重置前实例须无玩家 (reset.requireEmpty, 默认 true)。 */
    boolean resetRequireEmpty();

    /** OP 强制重置时是否先踢出在场玩家 (reset.kickOnForceReset, 默认 true)。 */
    boolean resetKickOnForce();

    /** 破坏性重置二次确认窗口 (秒, reset.confirmationWindowSeconds, 默认 15)。 */
    int resetConfirmationWindowSeconds();

    /**
     * R6: 指定难度区域的定时自动刷新周期 (小时, reset.autoResetHours<D>, 默认 Easy 6 / Medium 4 / Hard 2)。
     * 0 表示关闭该难度的定时刷新。每难度独立计时, 由 ResetSystem 持久化 lastReset 跟踪。
     */
    int autoResetHours(Difficulty difficulty);

    /** R6: 自动刷新前倒计时广播/撤离预警秒数 (reset.autoResetWarnSeconds, 默认 60, 0=不预警直接刷新)。 */
    int autoResetWarnSeconds();

    // ---- R7: 放置白名单 (rules) ----

    /**
     * R7: 矿山维度内允许放置的方块 id 列表 (rules.placeWhitelist, 默认含 "minecraft:scaffolding")。
     * 放置非白名单方块时取消并提示玩家; 挖矿/破坏不受限。返回不可变快照。
     */
    java.util.List<String> placeWhitelist();

    // ---- R4: 入口浮空字文案 (entry; 每难度一条) ----

    /** R4: 指定难度入口方块上方浮空字默认文案 (entry.label<D>, 默认中文)。 */
    String entryLabel(Difficulty difficulty);

    /** 指定难度的入场信用点费用 (entry.entryFee<D>, 默认 0 即免费)。 */
    long entryFee(Difficulty difficulty);

    // ---- 16.2.10 性能与生命周期 ----

    /** 实例激活时强加载区块半径 (perf.loadRadiusChunks, 默认 4)。 */
    int loadRadiusChunks();

    /** 空实例存活 TTL (秒, perf.emptyInstanceTtlSeconds, 默认 300)。 */
    int emptyInstanceTtlSeconds();

    /** GC 宽限期 (秒, perf.gcGraceSeconds, 默认 120)。 */
    int gcGraceSeconds();

    /** 孤儿/空实例扫描周期 (tick, perf.gcScanIntervalTicks, 默认 200)。 */
    int gcScanIntervalTicks();

    /** 离线生成工作线程数上限 (perf.maxGenWorkers, 默认 2)。 */
    int maxGenWorkers();

    /**
     * 创造飞行状态下允许的每 tick 最大位移 (格, movement.creativeFlightMaxBlocksPerTick, 默认 280)。
     * 仅放宽 Abilities.flying 为真时的原版 "moved too quickly" 校验; 其余状态一律维持原版 10 格/tick。
     */
    int creativeFlightMaxBlocksPerTick();
}
