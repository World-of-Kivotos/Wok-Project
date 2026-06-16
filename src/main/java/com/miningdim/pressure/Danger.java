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

    /** 难度区基础压力查表 (10.2 zoneTerm), 已归一 [0,1]。 */
    private static final float ZONE_EASY = 0.20f;
    private static final float ZONE_MEDIUM = 0.55f;
    private static final float ZONE_HARD = 1.00f;

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
     * @param data           该玩家压力态 (原地更新 tWin/danger/lastEvalTick)
     * @param difficulty     评估所用难度 (玩家实际所处难度子盒, 见 zoneAt)
     * @param activeInRegion 本评估周期玩家是否在 region 内主动作业 (true 累积 tWin, false 衰减)
     * @param oreRichness01  附近矿物富集度 [0,1]
     * @param currentTick    server game time (用于冻结判定)
     * @param config         配置门面
     * @return 评估后的 danger (= data.danger())
     */
    public float evaluate(PlayerMiningData data, Difficulty difficulty, boolean activeInRegion,
                          float oreRichness01, long currentTick, IMiningConfig config) {
        // tWin 累积/衰减 (10.3)。衰减量按配置 decayPerEval 相对基准缩放, 保证"撤一会儿"显著降压。
        if (activeInRegion) {
            data.setTWin(data.tWin() + TWIN_ACCRUE_PER_EVAL);
        } else {
            data.setTWin(Math.max(0, data.tWin() - decayTicks(config)));
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
