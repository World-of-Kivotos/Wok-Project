package com.miningdim.worldgen.skeleton;

import java.util.List;

/**
 * Stage 1 输出 (设计文档 7.4.1 SkeletonResult)。空气掩码已直接写回入参 VoxelGrid,
 * 故此处只携带"节点图 + 出生锚点", 不再重复持有 grid 引用 (避免别名歧义)。
 *
 * @param nodes       连通骨架的节点集合 (本地坐标), 供出生候选取点与调试可视化 (7.4.2)
 * @param spawnAnchor 出生点连通锚点的本地扁平下标 (= VoxelGrid.index)。该点必为空气且在主干上,
 *                    作为 ConnectivityFix 主连通分量 BFS 的起点 (7.5.1)
 */
public record SkeletonResult(List<SkeletonNode> nodes, int spawnAnchor) {
}
