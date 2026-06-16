package com.miningdim.worldgen.skeleton;

import com.miningdim.worldgen.GenContext;
import com.miningdim.worldgen.VoxelGrid;

/**
 * Stage 1 骨架算法统一接口 (设计文档 7.4.1 SkeletonAlgo)。三种实现按难度选用:
 * RandomWalkSkeleton (Easy)、RoomCorridorSkeleton (Hard)、HybridSkeleton (Medium)。
 *
 * 契约:
 *  - 输入: 全实心的 VoxelGrid (in/out, 直接在其上挖空) + GenContext (派生 skeletonSeed/难度子盒)。
 *  - 输出: 一个自身已连通的空气骨架 (节点图为连通图, 7.4.2), 并返回一个 SpawnAnchor 候选下标,
 *    供 ConnectivityFix 作主连通分量 BFS 起点。
 *  - 仅在难度子盒内收 Y 区间挖空 (6.5), 绝不写隔层/顶板/底板。
 *  - 确定性: 同一 skeletonSeed 逐方块一致, 随机只走 GenContext 派生的单一串行 RandomSource (7.6.2)。
 */
public interface SkeletonGenerator {

    /**
     * 在 grid 上挖出连通骨架。
     *
     * @param grid 全实心输入网格, 方法直接挖空 (写空气)
     * @param ctx  生成上下文 (seed/难度/box)
     * @return 骨架结果, 含连通节点图与出生锚点候选 (本地扁平下标)
     */
    SkeletonResult generate(VoxelGrid grid, GenContext ctx);
}
