package com.miningdim.power.grid;

import com.miningdim.power.cable.CableProfile;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一张连通的线缆网 (一个连通分量) 的运行时状态。纯数据 + 缓存, 全部驻内存, 不进任何 NBT
 * (Powah #169 的死因就是把全网成员坐标写进单块 NBT 导致指数膨胀崩服; 本网成员由 EnergyNetworkManager
 * 在 onLoad 时 flood-fill 重建)。
 *
 * 拓扑与端点由 {@link EnergyNetworkManager} 维护, 本类只持状态; 故字段包级可见、由 manager 直写。
 * stored 是瞬态导体缓冲 (非电池): 每 settlement 由 manager 抽空再分配, 上限 {@link #bufferCap}。
 *
 * 热学 (设计文档第三章): 网温 temperatureC 是每张网一个值; 材料剖面 (额定 / 降效 floor / 绝缘耐温) 均取
 * 网内最弱一段 (木桶效应), 仅成员增删时由 {@link #recomputeProfile} 重算并缓存, 绝不每 tick 算。
 */
final class EnergyNetwork {

    /** 成员线缆坐标 -> 其物理剖面 (决定木桶额定/floor/绝缘)。 */
    final Map<BlockPos, CableProfile> cables = new HashMap<>();

    /** 相邻的非线缆能量端点，按 {@link EnergyEndpointKey} 的稳定顺序刷新。 */
    final List<EnergyEndpointKey> endpoints = new ArrayList<>();

    /** 生产和消费端的稳定轮转游标，避免固定排序下首端长期独占吞吐。 */
    int producerCursor;
    int consumerCursor;

    /** 网内最低额定吞吐帽 R (木桶效应); 仅成员增删时由 manager 重算, 不每 tick 算。 */
    int ratedCap;

    /** 网内最弱导体的降效 floor (最先崩的那段主导整条网的最低效率)。仅成员增删时重算。 */
    double degradeFloor = 1.0;

    /** 网内最弱绝缘的耐温档 (°C); 降效起始点由它定 (最弱绝缘主导)。仅成员增删时重算。 */
    int insulationMaxTempC = Integer.MAX_VALUE;

    /** 网内最弱线缆耐压等级。 */
    VoltageClass voltageLimit = VoltageClass.EXTREME;

    /** 网温 (°C), 每张网一个值; 每 settlement 由 manager 依实际负载推进一 tick。 */
    double temperatureC = CableThermics.AMBIENT_C;

    /** 上一 settlement 实际送达用电端的 FE (供热学推进与 Jade 负载率显示)。 */
    int lastLoad;

    /** 上次结算和全生命周期的距离/保护损耗。 */
    int lastLossFe;
    long totalLossFe;

    /** 网侧故障与 P3 冷却状态。 */
    final Set<EnergyNetworkFault> faults = EnumSet.of(EnergyNetworkFault.NONE);
    EnergyNetworkSnapshot.CoolingState coolingState = EnergyNetworkSnapshot.CoolingState.NOT_REQUIRED;

    /** 瞬态缓冲当前存量 (FE)。 */
    int stored;

    /** 端点脏标记: 成员增删或邻居变化时置真, 下次 settlement 前惰性重算一次, 绝不每 tick 扫描。 */
    boolean endpointsDirty = true;

    /** 瞬态缓冲容量 = 一次额定吞吐 (线缆不是电池)。 */
    int bufferCap() {
        return ratedCap;
    }

    /** 当前网温下可用吞吐帽，供 manager 结算和只读快照复用。 */
    int effectiveCap() {
        double efficiency = CableThermics.efficiency(temperatureC, insulationMaxTempC, degradeFloor);
        return (int) Math.floor(ratedCap * efficiency);
    }

    /** 当前状态的不可变读模型。 */
    EnergyNetworkSnapshot snapshot() {
        double loadRatio = ratedCap == 0 ? 0.0 : (double) lastLoad / ratedCap;
        return new EnergyNetworkSnapshot(
                ratedCap,
                effectiveCap(),
                bufferCap(),
                stored,
                temperatureC,
                lastLoad,
                loadRatio,
                lastLossFe,
                totalLossFe,
                voltageLimit,
                faults,
                coolingState);
    }

    /** 依据当前成员材料重算木桶剖面 (额定 / floor / 绝缘)。成员增删后由 manager 调用一次。 */
    void recomputeProfile() {
        int cap = Integer.MAX_VALUE;
        double floor = 1.0;
        int insulation = Integer.MAX_VALUE;
        VoltageClass voltage = VoltageClass.EXTREME;
        for (CableProfile material : cables.values()) {
            cap = Math.min(cap, material.ratedCapacityFe());
            floor = Math.min(floor, material.degradeFloor());
            insulation = Math.min(insulation, material.insulation().maxContinuousTempC());
            if (voltage.isHigherThan(material.voltageClass())) {
                voltage = material.voltageClass();
            }
        }
        ratedCap = (cap == Integer.MAX_VALUE) ? 0 : cap;
        degradeFloor = floor;
        insulationMaxTempC = insulation;
        voltageLimit = voltage;
    }
}
