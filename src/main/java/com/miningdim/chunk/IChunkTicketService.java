package com.miningdim.chunk;

import com.miningdim.core.InstanceState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Set;

/**
 * 区块强加载门面 (设计文档 19.1)。core 契约层未定义 chunk-ticket 接口 (core 不可改),
 * 而 entry / reset 两个子系统又确实需要 "传送前确保 spawn 区块就绪" 与 "重置前卸载 region 区块" 能力,
 * 故在 chunk 子系统内提供本门面接口 + {@link ChunkServices} 静态定位器, 让 entry/reset 只依赖本接口、
 * 不 import {@link ChunkTicketManager} 实现类, 维持子系统解耦 (模块化铁律 2 的同构落地)。
 *
 * 全部方法均为世界写或读, 须在服务端主线程调用 (D8)。
 */
public interface IChunkTicketService {

    /**
     * 按一组在场玩家位置刷新某实例的滑动 ticket 窗口 (19.1 激活/滑动更新)。
     * 多玩家共享实例时取窗口并集。落在缓冲带/他 region 的候选区块自动剔除。
     */
    void refreshWindow(InstanceState state, Iterable<ServerPlayer> presentPlayers);

    /**
     * 强制确保给定区块在某实例内被 ticking 强加载 (14.3 入场前 force-load)。
     * 返回后调用方须配合 {@link #areChunksLoaded} 轮询确认 FULL 再传送 (防虚空)。
     */
    void ensureTicking(InstanceState state, Set<Long> chunks);

    /** 给定区块集合是否全部加载且达 FULL 状态 (14.3 awaitChunksLoaded)。 */
    boolean areChunksLoaded(Set<Long> chunks);

    /** 空置 TTL 到期或销毁实例: 释放该实例全部 ticket (19.1 卸载释放 / R5 add=false)。 */
    void releaseAll(long instanceId);

    /** 空置宽限期内把实例 ticket 降为仅加载不 tick (19.1 空置 TTL 行 / 12.7)。 */
    void demoteToLoadOnly(long instanceId);

    /** 该实例当前是否仍持有任何 ticket。 */
    boolean hasTickets(long instanceId);

    /** 矿山维度 ServerLevel (entry/reset 传送与区块删除目标)。 */
    ServerLevel level();

    /** 围绕中心点按区块半径生成区块键集合 (14.3 spawn 周边窗口辅助)。 */
    Set<Long> chunksAround(BlockPos center, int radiusChunks);
}
