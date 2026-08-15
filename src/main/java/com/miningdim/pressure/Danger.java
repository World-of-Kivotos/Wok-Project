package com.miningdim.pressure;

import com.miningdim.core.Difficulty;
import com.miningdim.core.IMiningConfig;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * danger 计算核心 + 每玩家压力态注册表 (设计文档 10.2 / 10.3 / DG-1..DG-4)。
 *
 * danger = clamp(W_ZONE*zoneTerm + W_TIME*timeTerm + W_ORE*oreTerm, 0, DANGER_MAX), 三项各归一 [0,1]。
 *   zoneTerm: 难度区基础压力查表常量 (Easy 0.2 / Medium 0.55 / Hard 1.0, 10.2)。
 *   timeTerm: 1 - exp(-K_TIME * tWin / TIME_SCALE) 软封顶, 渐近 1 不超 (10.3, 修复"超时必死")。
 *   oreTerm:  附近矿物富集度 [0,1], 由调用方 (压力 tick) 传入; 矿物子系统尚无 core 门面暴露富集度时按 0。
 *
 * 软封顶保证: 单靠时间最多贡献 W_TIME * 1.0 的 danger, 时间越长 danger 不会无界增长 (DG-2)。
 *
 * 线程纪律 (D8): danger 评估为轻量纯计算, 在服务端主线程 tick 内完成。per-player 表的读写也只在主线程,
 * 用 ConcurrentHashMap 仅为防御调试/日志侧的跨线程只读快照, 不替代主线程串行写。
 */
public final class Danger {

    /**
     * 难度区基础压力查表 (10.2 zoneTerm), 已归一 [0,1] (F035 重配, ZONE_HARD 经复核修正)。
     *
     * 生效预算: MobPressureSystem.java:174 把 oreRichness01 硬编码 0.0f, wOre*ore 那 0.3 恒拿不到;
     * 实际可达 danger = wZone*zone + wTime*timeTerm, spec 默认 wZone=1.0 / wTime=0.5 / danger.max=1.0
     * (MiningServerConfig:176-186)。即时间项最多贡献 0.50, zone 必须给它留满这 0.50 的余量。
     *
     * 三档天花板 (zone + 0.50) 按难度拉开且各自卡在一个有语义的门下: Easy 0.59 严格低于 SpawnTier.HIGH
     * 的 0.60 (SpawnTier.java:34) 也低于 DANGER_THRESH_LAVA 0.70, 即 Easy 全程不出苦力怕、不喷岩浆,
     * 与 TrapParams.FACTOR_EASY=0 "新手区无致死陷阱" 同口径; Medium 0.79 严格低于 SpawnTier.EXTREME
     * 的 0.80, 即满压档是 Hard 专属; Hard 0.90 (原 1.00, 见下方复核修正) 仍能爬过 EXTREME 的 0.80 门。
     *
     * 三档出场值同样拉开且都不在顶档: Easy 0.09 = SAFE, Medium 0.29 = LIGHT, Hard 0.40 (原 0.50, 见下方
     * 复核修正) = MEDIUM 档, 三档都留出了"越待越危险"的成长区间。
     *
     * Easy 取 0.09 而不是整数 0.10, Medium 取 0.29 而不是 0.30: 就是为了让天花板严格小于上述两个档位
     * 阈值 (0.10+0.5=0.60 会因浮点相等而恰好踩进 HIGH; 0.30+0.5=0.80 会恰好踩进 EXTREME)。
     *
     * 复核修正 (三次独立复核坐实): 上一版 ZONE_HARD 取 0.50, 与 TrapParams.DANGER_THRESH_CREEPER 的
     * 0.50 完全相等 —— compose(HARD,0,...)=wZone*0.50=0.50, danger>=0.50 即成立, 出生冻结一结束 (freeze
     * 期间 tWin 仍在累积, 只是 danger 显示被钳低) 身后刷苦力怕立刻可触发, 与 Easy/Medium 特意避开浮点相等
     * 的做法自相矛盾, 且零成长区间 (原论证只核对了 SpawnTier.HIGH/EXTREME 与 DANGER_THRESH_LAVA 三个门,
     * 漏了 TrapParams 里另外两个更低的门 DANGER_THRESH_CREEPER=0.50 / DANGER_THRESH_COLLAPSE=0.55)。
     * 改取 0.40: (a) 与 CREEPER 阈值留出 0.10 的原始余量, 而不是恰好相等; (b) 恰好落在 SpawnTier.MEDIUM
     * 的下界, 与 Medium 难度出场的 LIGHT 档区分开, 不破坏"三档出场值分属三个不同 SpawnTier"的既有设计
     * 不变量 (DangerCurveGameTests.spawnValuesDistinguishableAcrossDifficulties); (c) 天花板降到 0.90,
     * 仍严格高于 EXTREME 的 0.80, 久留满压这条能力不受影响。
     *
     * 已知残留 (据实报告, 本次未修, 超出本 finding 范围): 出生冻结期内 tWin 并未被冻结 (MobPressureSystem
     * 只钳低 danger 显示值, 不冻结 tWin 累积), SPAWN_FREEZE_TICKS=200 结束时 tWin 已累积约 200,
     * timeTerm(200,1200)≈0.154, 令冻结刚结束时的原始 danger ≈ 0.40+0.5*0.154=0.477, 距 CREEPER(0.50) 仅
     * 剩约 0.023 的余量 —— 严格意义上不再"零成长区间", 但余量依然很薄。彻底解决需要冻结窗口内也冻结 tWin
     * 累积 (改 MobPressureSystem/PlayerMiningData), 不在 Danger.java 范围内, 留待后续 finding 处理。
     */
    private static final float ZONE_EASY = 0.09f;
    private static final float ZONE_MEDIUM = 0.29f;
    private static final float ZONE_HARD = 0.40f;

    /** 时间项收敛速率 K_TIME (10.3), 曲线在 tWin == TIME_SCALE 时达约 0.63。 */
    private static final double K_TIME = 1.0;

    /** 出生冻结期内 danger 钳顶值 (11.7: danger = min(danger, 0.15))。 */
    private static final float SPAWN_FREEZE_DANGER_CLAMP = 0.15f;

    /** 在 region 内主动作业时 tWin 每评估周期累积量 (10.3: tWin += 20/评估)。 */
    private static final int TWIN_ACCRUE_PER_EVAL = 20;

    /** 离区/降频时 tWin 衰减基准 (10.3 DECAY_PER_EVAL 的 tick 化, 对应配置默认 decayPerEval=0.2)。 */
    private static final int TWIN_DECAY_BASE = 8;

    /** UUID -> 玩家压力态。仅服务端在场玩家有条目; 离开/登出由 MobPressureSystem 移除。 */
    private final Map<UUID, PlayerMiningData> byPlayer = new ConcurrentHashMap<>();

    /** 取已有压力态; 不存在返回 null (调用方决定是否 onEnter)。 */
    public PlayerMiningData get(UUID playerId) {
        return byPlayer.get(playerId);
    }

    /**
     * 玩家进入实例时初始化压力态 (14.2 步骤 11 initDanger): danger=0, tWin=0,
     * spawnFreezeUntil 由调用方随后设置。已存在则重置为新实例的初值。
     */
    public PlayerMiningData onEnter(UUID playerId, long instanceId, long currentTick) {
        PlayerMiningData data = new PlayerMiningData(instanceId, currentTick);
        byPlayer.put(playerId, data);
        return data;
    }

    /** 玩家离开矿山/登出时移除压力态 (12.6 离开汇聚 + 10.7 离区处理)。 */
    public void onLeave(UUID playerId) {
        byPlayer.remove(playerId);
    }

    /** 当前持有压力态的玩家数 (诊断用)。 */
    public int trackedPlayers() {
        return byPlayer.size();
    }

    // ---- 纯计算 (无副作用, 便于 TDD 断言) ----

    /** 难度区基础压力 (10.2 zoneTerm 查表)。 */
    public static float zoneTerm(Difficulty difficulty) {
        return switch (difficulty) {
            case EASY -> ZONE_EASY;
            case MEDIUM -> ZONE_MEDIUM;
            case HARD -> ZONE_HARD;
        };
    }

    /**
     * 时间项软封顶 (10.3): timeTerm = 1 - exp(-K_TIME * tWin / TIME_SCALE)。
     * TIME_SCALE 由配置 dangerTimeSoftCap() (秒) 换算为 tick (*20); 永不超过 1, 渐近收敛。
     *
     * @param tWin             活跃作业 tick 累计 (>=0)
     * @param timeSoftCapTicks 软封顶时间尺度 (tick, > 0)
     */
    public static float timeTerm(int tWin, double timeSoftCapTicks) {
        if (tWin <= 0 || timeSoftCapTicks <= 0) {
            return 0.0f;
        }
        double t = Math.exp(-K_TIME * (double) tWin / timeSoftCapTicks);
        return (float) (1.0 - t);
    }

    /** 矿物富集度归一项 (10.2 oreTerm), 入参已在 [0,1], 此处仅做防御性钳制。 */
    public static float oreTerm(float oreRichness01) {
        return clamp01(oreRichness01);
    }

    /**
     * 组合 danger (10.2): clamp(W_ZONE*zone + W_TIME*time + W_ORE*ore, 0, DANGER_MAX)。
     * 权重与 DANGER_MAX 实时取自配置 (C6, 不缓存裸常量)。
     */
    public static float compose(Difficulty difficulty, int tWin, float oreRichness01, IMiningConfig config) {
        double wZone = config.dangerWeightZone();
        double wTime = config.dangerWeightTime();
        double wOre = config.dangerWeightOre();
        double timeSoftCapTicks = config.dangerTimeSoftCap() * 20.0; // 秒 -> tick
        double dangerMax = config.dangerMax();

        double raw = wZone * zoneTerm(difficulty)
                + wTime * timeTerm(tWin, timeSoftCapTicks)
                + wOre * oreTerm(oreRichness01);
        return (float) clamp(raw, 0.0, dangerMax);
    }

    /**
     * 对一个玩家推进一次评估 (10.3 累积/衰减 + 10.2 合成 + 11.7 冻结钳制)。
     * 调用方负责按评估周期 (dangerEvalIntervalTicks) 节流, 并已确认玩家在该实例 region 内。
     *
     * @param data             该玩家压力态 (原地更新 tWin/danger/lastEvalTick)
     * @param difficulty       评估所用难度 (玩家实际所处难度子盒, 见 zoneAt)
     * @param activeInRegion   本评估周期玩家是否在 region 内主动作业 (true 累积 tWin, false 衰减)
     * @param oreRichness01    附近矿物富集度 [0,1]
     * @param timeAccrueFactor 职业 danger 时间项缩放系数 (Major 缺陷四): 缩放 tWin 累积/衰减量, 取值越小累积越慢
     *                         (矿工耐压: 1.0 未解锁 -> 0.60 满级封底)。须 > 0 (不钳 0, 不动 zoneTerm); 无职业系数传 1.0。
     * @param currentTick      server game time (用于冻结判定)
     * @param config           配置门面
     * @return 评估后的 danger (= data.danger())
     */
    public float evaluate(PlayerMiningData data, Difficulty difficulty, boolean activeInRegion,
                          float oreRichness01, float timeAccrueFactor, long currentTick, IMiningConfig config) {
        // tWin 累积/衰减 (10.3)。累积/衰减量同乘职业系数 (Major 缺陷四): 耐压玩家既慢累积也慢衰减, 净效果是
        // 时间项更平缓; 衰减量另按配置 decayPerEval 相对基准缩放, 保证"撤一会儿"仍显著降压。系数只缩放时间项 tWin,
        // 不动 zoneTerm (难度基础压力), 故耐压玩家在高难区仍承受 zone 压力, 不会实质免疫压力系统。
        if (activeInRegion) {
            int accrue = Math.round(TWIN_ACCRUE_PER_EVAL * timeAccrueFactor);
            data.setTWin(data.tWin() + accrue);
        } else {
            int decay = Math.round(decayTicks(config) * timeAccrueFactor);
            data.setTWin(Math.max(0, data.tWin() - decay));
        }

        float danger = compose(difficulty, data.tWin(), oreRichness01, config);

        // 出生冻结期钳低 (11.7), 与刷怪门控同源单点控制。
        if (data.inSpawnFreeze(currentTick)) {
            danger = Math.min(danger, SPAWN_FREEZE_DANGER_CLAMP);
        }

        data.setDanger(danger);
        data.setLastEvalTick(currentTick);
        return danger;
    }

    /**
     * 衰减 tick 量: 以基准 TWIN_DECAY_BASE 按配置 decayPerEval 相对默认 0.2 缩放。
     * decayPerEval 是归一域上的"每评估衰减量", 这里映射回 tWin 整数衰减 (基准 0.2 对应 8 tick)。
     */
    private static int decayTicks(IMiningConfig config) {
        double ratio = config.dangerDecayPerEval() / 0.2; // 默认 0.2 -> ratio 1.0
        return Math.max(0, (int) Math.round(TWIN_DECAY_BASE * ratio));
    }

    /**
     * 玩家当前所处难度区 (R2: 整块 region 单难度, zone 恒等于实例难度)。
     * 旧模型按 worldY 子盒分带已废弃, 三难度各占独立 region 全高, 故 zone 直接取实例难度。
     */
    public static Difficulty zoneAt(ServerPlayer player, Difficulty instanceDifficulty) {
        return instanceDifficulty;
    }

    private static float clamp01(float v) {
        return (float) clamp(v, 0.0, 1.0);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
