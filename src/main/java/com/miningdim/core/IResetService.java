package com.miningdim.core;

import net.minecraft.server.MinecraftServer;

import java.util.concurrent.CompletableFuture;

/**
 * 重置服务门面 (设计文档 3.3 ResetSystem / 第十三章, G8/D1)。单实例 region 级重置:
 * 仅删除/重生成该 region 区块, 不触碰其他实例, 不增删维度 (C1)。
 *
 * 流程 (3.3/13): 先疏散玩家回进入前坐标 (读 Capability, D5) -> genState=RESETTING ->
 * 文件级删除 region 区块 (绝不逐块 setBlock) -> 按 mode 决定种子 -> 重跑离线生成 -> READY。
 * 区块删除与重生成调度在主线程经 server.execute; 离线生成在工作线程 (D8)。
 */
public interface IResetService {

    /** 重置模式 (3.3)。 */
    enum ResetMode {
        /** 原样重建: 复用原 seed, resetGeneration 不变, 体素逐位相同 (确定性验收用)。 */
        SAME_SEED,
        /** 刷新随机: resetGeneration+1 派生新 seed, 产出新布局。 */
        NEW_SEED
    }

    /**
     * 重置指定实例。
     *
     * @param instanceId 目标实例
     * @param mode       重置模式
     * @return 重置完成 (实例回到 READY) 后兑现; 实例不存在或重置前置条件不满足 (有人在场且 requireEmpty)
     *         时 future 异常完成 (IllegalStateException 自然冒泡, 由入口层捕获)
     */
    CompletableFuture<Void> reset(long instanceId, ResetMode mode);

    /**
     * 疏散实例内全部玩家回各自进入前坐标/维度/gamemode (读 Capability, D5/14.6)。
     * 在主线程执行传送; 重置与运维强制清理前调用。
     */
    void evacuate(InstanceState instance, MinecraftServer server);
}
