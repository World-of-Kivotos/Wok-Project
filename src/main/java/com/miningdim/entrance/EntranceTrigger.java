package com.miningdim.entrance;

import com.miningdim.core.Difficulty;
import net.minecraft.server.level.ServerPlayer;

/**
 * 入口方块 -> 入场流程的最小 seam (模块化铁律 2)。entrance 子系统只依赖本接口, 绝不 import entry 包
 * 的实现类 ({@code EntryGateway}/{@code EntrySystem}); 由 entry 子系统在服务端启动时把真正的实现
 * (转调 {@code EntryGateway.requestEnter}) 注入 {@link EntranceHooks}。
 *
 * 这样 entrance 包对 entry 包零编译依赖, 仅 entry 在启动期反向接线 entrance 的 seam, 维持单向依赖。
 */
@FunctionalInterface
public interface EntranceTrigger {

    /**
     * 玩家通过入口方块 (右键/踩踏) 请求进入指定难度区域。仅服务端调用。
     * 实现转调入场编排 (门控 -> 分配固定区域实例 -> 防虚空传送), 失败由入场层自行提示玩家 (C9)。
     */
    void requestEnter(ServerPlayer player, Difficulty difficulty);
}
