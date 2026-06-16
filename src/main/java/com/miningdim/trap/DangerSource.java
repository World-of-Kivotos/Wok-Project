package com.miningdim.trap;

import net.minecraft.server.level.ServerPlayer;

/**
 * 危险值供给契约 (设计文档第十章 danger 由压力子系统持有)。
 *
 * 模块化约束 (铁律 2): 动态陷阱由 danger 阈值门控 (9.6), 但 danger 是每玩家挂 Capability 的状态 (DG-3),
 * 由压力子系统 (mobpressure) 拥有; core 没有"读 danger"的门面。为避免陷阱子系统硬 import 压力子系统实现,
 * 本接口定义为陷阱包内的"被注入"契约: 压力子系统在自己的 register 内经 {@link TrapSystem#setDangerSource}
 * 把读 danger 的能力注入进来 (推依赖而非拉依赖)。
 *
 * 未注入时 (压力子系统尚未上线/未注册): 陷阱运行期把 danger 视为 0, 需 danger 门控的动态陷阱不触发,
 * 系统优雅降级而非崩溃。这保持了"陷阱包不知道压力包存在"的零耦合, 同时不掩盖缺失 (降级行为有日志)。
 */
@FunctionalInterface
public interface DangerSource {

    /**
     * 读取玩家当前归一化 danger 值 [0, DANGER_MAX]。
     *
     * @param player     目标玩家
     * @param instanceId 玩家所在实例 id
     * @return 当前 danger (归一化)
     */
    float dangerOf(ServerPlayer player, long instanceId);
}
