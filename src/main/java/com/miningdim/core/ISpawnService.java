package com.miningdim.core;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * 安全出生服务门面 (设计文档 3.3 SpawnSystem / 第十一章, G7/D4)。
 * 候选点来自真实世界预扫描的出生池, 取点带占用 TTL 避免多人叠格; 池内取不到安全点时
 * 由实现方建兜底安全平台并返回其站立点, 故本方法不返回 null、也不抛 —— 原版噪声地形
 * 本来就不保证中心有现成空腔, 那是预期分支不是缺陷。
 */
public interface ISpawnService {

    /**
     * 解析一个安全出生点 (世界坐标)。候选来自真实世界预扫描的出生池, 经安全谓词过滤,
     * 取点带占用 TTL 避免多人叠格。
     *
     * @param level    矿山维度 ServerLevel
     * @param instance 实例 (提供 regionBox/difficulty/seed)
     * @return 世界坐标安全出生点
     */
    BlockPos findSpawn(ServerLevel level, InstanceState instance);

    /**
     * 在已落方块的世界中复核某点是否安全 (传送前二次确认 / 重生点校验)。
     * 与 findSpawn 预扫描出生池所用的安全谓词一致。
     *
     * @param level    矿山维度 ServerLevel
     * @param pos      候选点 (玩家脚部)
     * @param instance 所属实例
     * @return 安全则 true
     */
    boolean isSafe(ServerLevel level, BlockPos pos, InstanceState instance);
}
