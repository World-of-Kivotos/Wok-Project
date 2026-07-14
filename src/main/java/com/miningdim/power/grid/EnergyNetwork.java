package com.miningdim.power.grid;

import com.miningdim.power.cable.CableTier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.HashMap;
import java.util.Map;

/**
 * 一张连通的线缆网 (一个连通分量) 的运行时状态。纯数据 + 缓存, 全部驻内存, 不进任何 NBT
 * (Powah #169 的死因就是把全网成员坐标写进单块 NBT 导致指数膨胀崩服; 本网成员由 EnergyNetworkManager
 * 在 onLoad 时 flood-fill 重建)。
 *
 * 拓扑与端点由 {@link EnergyNetworkManager} 维护, 本类只持状态; 故字段包级可见、由 manager 直写。
 * stored 是瞬态导体缓冲 (非电池): 每 settlement 由 manager 抽空再分配, 上限 {@link #bufferCap}。
 */
final class EnergyNetwork {

    /** 成员线缆坐标 -> 其级别 (决定木桶吞吐)。 */
    final Map<BlockPos, CableTier> cables = new HashMap<>();

    /** 相邻的非线缆能量端点: 端点方块坐标 -> 该端点朝向线缆的面 (查 capability 用)。仅端点重算时刷新。 */
    final Map<BlockPos, Direction> endpoints = new HashMap<>();

    /** 网内最低级线缆的每 settlement 吞吐帽 (木桶效应); 仅成员增删时由 manager 重算, 不每 tick 算。 */
    int transferCap;

    /** 瞬态缓冲当前存量 (FE)。 */
    int stored;

    /** 端点脏标记: 成员增删或邻居变化时置真, 下次 settlement 前惰性重算一次, 绝不每 tick 扫描。 */
    boolean endpointsDirty = true;

    /** 瞬态缓冲容量 = 一次 settlement 吞吐 (线缆不是电池)。 */
    int bufferCap() {
        return transferCap;
    }

    /** 依据当前成员级别重算木桶吞吐帽。成员增删后由 manager 调用一次。 */
    void recomputeTransferCap() {
        int cap = Integer.MAX_VALUE;
        for (CableTier tier : cables.values()) {
            cap = Math.min(cap, tier.transferCapFePerTick());
        }
        transferCap = (cap == Integer.MAX_VALUE) ? 0 : cap;
        if (stored > bufferCap()) {
            stored = bufferCap();
        }
    }
}
