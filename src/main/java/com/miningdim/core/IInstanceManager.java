package com.miningdim.core;

import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 实例生命周期权威门面 (设计文档 3.3 InstanceManager / 第十二章)。实现为服务端单例,
 * 由 InstanceSystem 子系统在 ServerStartedEvent 从 SavedData 重建后注入 MiningServices。
 *
 * 线程契约 (D8/12.4): 所有分配/回收/计数操作只在主线程执行; 网络/工作线程调用方须经 server.execute 串行回主线程。
 * 异常契约 (C9): 超上限抛 InstanceLimitException (本接口同包定义), 由入口层 (命令/网络 handler) 捕获转玩家提示。
 */
public interface IInstanceManager {

    /**
     * 为请求者分配一个匹配难度的实例 (12.2 私有/共享分配语义)。
     * 实例可能尚在 PENDING/GENERATING; 返回的 future 在生成完成 (genState 进入 READY/READY_FALLBACK) 后兑现。
     * 超全局上限按配置 overflowPolicy 处理: REJECT 时立即以 InstanceLimitException 完成异常; QUEUE 时入队, 兑现或超时失败。
     *
     * @param requester  发起进入的玩家 (用于解析 ownerKey 与组队)
     * @param difficulty 目标难度
     * @return 生成就绪后兑现的实例状态
     */
    CompletableFuture<InstanceState> allocate(ServerPlayer requester, Difficulty difficulty);

    /** 按 id 查实例; 不存在返回 empty。 */
    Optional<InstanceState> byId(long instanceId);

    /**
     * 按世界坐标定位所属实例 (供 ChunkGenerator/BiomeSource 查 region; 4.2)。
     * 落在缓冲带/region 外返回 null (调用方据此填实心墙)。热路径方法, 返回 null 而非 Optional 以省装箱。
     */
    InstanceState regionAt(int worldX, int worldZ);

    /** 玩家进入实例: playerSet add、active=true、lastEmptyTick=-1 (12.6)。主线程。 */
    void onPlayerEnter(long instanceId, ServerPlayer player);

    /**
     * 玩家离开实例的统一汇聚点 (12.6 onPlayerLeaveInstance): playerSet remove;
     * 归零则 active=false、记 lastEmptyTick、释放强加载、唤醒排队。所有离开路径必须汇聚到此。主线程。
     */
    void onPlayerLeave(long instanceId, ServerPlayer player);

    /** 释放/销毁指定实例的入口 (供 ResetService 回收或运维清理); 实现负责 region free 与 SavedData setDirty。主线程。 */
    void release(long instanceId);

    /**
     * 从离线生成调度器的分帧强加载队列移除指定实例尚未消费的残留区块任务, 返回清除数 (供 ResetService 在
     * UNLOAD 断源, 避免卸载等待与旧排队任务竞争)。模块化铁律 2: reset 子系统经此门面调用, 不 import
     * instance 实现类与 GenerationScheduler。主线程。
     */
    int cancelQueuedChunkLoads(long instanceId);

    /** 当前存活实例数 (含 GENERATING), 用于 globalCap 校验。 */
    int activeInstanceCount();

    /** 只读快照集合, 供命令/调试遍历 (不可用于修改)。 */
    Collection<InstanceState> snapshot();

    /** 对每个实例执行只读操作 (避免暴露内部集合的并发修改风险)。 */
    void forEach(Consumer<InstanceState> action);

    /**
     * 为指定实例 id 派生确定性种子 (= SeedUtil.deriveSeed(globalSeed, id, resetGeneration=0))。
     * ResetService 做 NEW_SEED 重置时按 resetGeneration 自行调用 SeedUtil 派生新种子; 本方法给首次种子。
     */
    long nextSeedFor(long instanceId);
}
