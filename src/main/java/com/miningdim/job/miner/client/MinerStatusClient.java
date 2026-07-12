package com.miningdim.job.miner.client;

import com.miningdim.job.miner.network.MinerStatusS2C;

/**
 * 矿工状态 HUD 的客户端快照持有者 (仅客户端逻辑端加载, 经 {@link MinerStatusS2C#handle} 的 DistExecutor 隔离
 * 调 {@link #accept})。整体替换式缓存最近一次服务端下发的瞬态态; MinerHudOverlay 每帧读取渲染。
 *
 * 不持有任何客户端推算的 CD/充能 (服务端权威, 客户端只展示本快照)。volatile 保证网络主线程写、渲染线程读可见。
 */
public final class MinerStatusClient {

    private MinerStatusClient() {
    }

    private static volatile MinerStatusS2C current;

    /** 由 S2C 客户端 handler 在客户端主线程调用: 整体替换快照。 */
    public static void accept(MinerStatusS2C msg) {
        current = msg;
    }

    /** 最近一次快照 (从未收到过返回 null; overlay 据此不渲染)。 */
    public static MinerStatusS2C current() {
        return current;
    }

    /** 丢弃快照 (overlay 判定玩家已离开矿洞维度时调用): 防再入矿洞时新包到达前闪现上次的旧充能/CD。 */
    public static void clear() {
        current = null;
    }
}
