package com.miningdim.pressure;

/**
 * 单个玩家的压力运行态 (设计文档 10.7 / DG-3)。danger 每玩家独立, 故每个在场玩家持有一份。
 *
 * 归属决策 (架构铁律 2): 设计文档 12.5 (D5) 把 danger 列入跨维度玩家 Capability, 但那份 Capability
 * (含 prevDimension/prevPos/prevGameMode 等回退态) 归入口/持久化子系统, 属其他 package 的实现, 按
 * "子系统间严禁互相 import 实现类"的铁律不在此绑定。压力子系统自持玩家级运行态, 以 UUID 为键在内存维护,
 * 进入时重建; 这与同仓其他玩家级运行态子系统的做法一致 (自持纯数据, 经 core 门面对外, 不跨 import)。
 *
 * 对外暴露: 压力把当前 danger 经 core IMiningNetwork.sendDanger 推给客户端 HUD (15.4.2)。持久化/跨死亡
 * 复制的 danger 由 Capability 子系统镜像 (其负责死亡策略 14.6), 本运行态不跨重启持久化 —— 重新进入即
 * danger=0 (与 14.2 步骤 11 initDanger 语义一致)。
 *
 * 线程纪律 (D8): 所有读写只在服务端主线程发生 (ServerTickEvent + 事件回调), 故无需并发保护。
 */
public final class PlayerMiningData {

    /** 当前所在实例 id; 不在矿山为 -1 (12.5 currentInstanceId 语义)。 */
    private long instanceId;

    /** 当前危险值, 归一化 [0, DANGER_MAX] (10.2)。 */
    private float danger;

    /**
     * 时间项滑动窗口的"活跃作业 tick"累计值 (10.3 tWin)。
     * 经 timeTerm = 1 - exp(-K * tWin / TIME_SCALE) 软封顶, 离区/降频时衰减。
     */
    private int tWin;

    /** 最近一次 danger 评估的 server game time, 用于按评估周期推进 (10.7 lastEvalTick)。 */
    private long lastEvalTick;

    /**
     * 出生冻结截止 tick (11.7): currentTick < spawnFreezeUntil 时 danger 钳到低值且不主动刷怪。
     * 本系统在首次观测某玩家进入实例时设为 tick + SPAWN_FREEZE_TICKS。
     */
    private long spawnFreezeUntil;

    public PlayerMiningData(long instanceId, long currentTick) {
        this.instanceId = instanceId;
        this.danger = 0.0f;
        this.tWin = 0;
        this.lastEvalTick = currentTick;
        this.spawnFreezeUntil = 0L;
    }

    public long instanceId() {
        return instanceId;
    }

    public void setInstanceId(long instanceId) {
        this.instanceId = instanceId;
    }

    public float danger() {
        return danger;
    }

    public void setDanger(float danger) {
        this.danger = danger;
    }

    public int tWin() {
        return tWin;
    }

    public void setTWin(int tWin) {
        this.tWin = tWin;
    }

    public long lastEvalTick() {
        return lastEvalTick;
    }

    public void setLastEvalTick(long lastEvalTick) {
        this.lastEvalTick = lastEvalTick;
    }

    public long spawnFreezeUntil() {
        return spawnFreezeUntil;
    }

    public void setSpawnFreezeUntil(long spawnFreezeUntil) {
        this.spawnFreezeUntil = spawnFreezeUntil;
    }

    /** 当前是否处于出生冻结期 (11.7): 期间评估 danger 钳低且不刷怪。 */
    public boolean inSpawnFreeze(long currentTick) {
        return currentTick < spawnFreezeUntil;
    }
}
