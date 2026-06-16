package com.miningdim.core;

import java.util.concurrent.CompletableFuture;

/**
 * 离线体素生成器门面 (设计文档 3.3 OfflineCaveGenerator / 第七章 / D2-D4)。
 * 纯计算、无世界写、不触碰 ServerLevel, 可在工作线程并发 (C7)。
 *
 * 确定性契约 (D3/C3): 同一 (seed, difficulty, regionBox) 必返回逐位相等的 VoxelOccupancy。
 * 连通性契约 (D4/C4): 返回的占用网格中, 出生点锚点 BFS 不可达的可行走空气体素数为 0
 * (小岛已被填实, 主分量已用 A* 打通)。
 *
 * 三阶段 Skeleton -> NoiseCarving -> ConnectivityFix 在实现内部串行, 调用方只看到最终 frozen 视图。
 * 返回 CompletableFuture: 实现内部把任务提交到自管线程池 (perf.maxGenWorkers), 不阻塞调用线程。
 */
public interface IOfflineGenerator {

    /**
     * 异步生成整 region 的体素占用网格。
     *
     * @param seed       实例确定性种子 (含重置代数派生)
     * @param difficulty 难度 (决定骨架算法选型与雕刻参数, 7.4)
     * @param regionBox  目标 region 几何 (本地坐标系即 [0,size))
     * @return 生成完成后兑现的只读体素视图; 生成异常时 future 异常完成 (C9 自然冒泡到调用方回调)
     */
    CompletableFuture<VoxelOccupancy> generate(long seed, Difficulty difficulty, RegionBox regionBox);
}
