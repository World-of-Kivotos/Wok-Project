package com.miningdim.worldgen.skeleton;

/**
 * 骨架节点图的一个节点 (设计文档 7.4.1 NodeGraph): region 本地坐标系下一个关键点
 * (房间中心 / 随机游走起点 / 主干节点)。出生候选点优先取自这些节点 (7.4.2),
 * 因为节点必在已连通骨架上, 降低 Stage 3 把出生点判为孤岛的概率。
 *
 * 不可变值对象; equals/hashCode 由坐标决定 (record 自动提供), 适合做去重与图算法的键。
 */
public record SkeletonNode(int x, int y, int z) {
}
