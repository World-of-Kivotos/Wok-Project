package com.miningdim.power.grid;

import net.minecraft.core.BlockPos;

/**
 * 低温控制器与一根受控线缆的显式绑定。控制器实现只需在液氮状态变化时向网络管理器更新该快照。
 */
public interface CoolingControllerAttachment {

    BlockPos controlledCablePos();

    int activeCoverageSegments();
}
