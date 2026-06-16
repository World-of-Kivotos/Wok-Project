package com.miningdim.core;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * 安全出生服务门面 (设计文档 3.3 SpawnSystem / 第十一章, G7/D4)。
 * 从离线生成的体素视图里筛选满足安全谓词 (头顶 spawn.headroomBlocks 格空气 / 脚下固体 /
 * 无岩浆 / 非陷阱区) 且 ∈ 主连通分量的点 (D4 保证存在)。
 */
public interface ISpawnService {

    /**
     * 解析一个安全出生点 (世界坐标)。候选来自体素视图主连通分量, 经安全谓词过滤。
     *
     * @param instance 实例 (提供 regionBox/difficulty/seed)
     * @param voxels   该实例已生成的体素占用视图
     * @return 世界坐标安全出生点
     * @throws IllegalStateException 无合法点时抛 (C4 保证不应发生; 抛出即暴露连通性缺陷, 不掩盖)
     */
    BlockPos findSpawn(InstanceState instance, VoxelOccupancy voxels);

    /**
     * 在已落方块的世界中复核某点是否安全 (传送前二次确认 / 重生点校验)。
     * 与 findSpawn 的体素谓词一致, 但读真实 ServerLevel 方块状态。
     *
     * @param level    矿山维度 ServerLevel
     * @param pos      候选点 (玩家脚部)
     * @param instance 所属实例
     * @return 安全则 true
     */
    boolean isSafe(ServerLevel level, BlockPos pos, InstanceState instance);
}
