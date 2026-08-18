package com.miningdim.power.grid;

import net.minecraftforge.energy.IEnergyStorage;

/**
 * 自研端点可声明输出电压并接收网侧过压反馈；普通 Forge Energy 端点按 LOW 兼容。
 */
public interface VoltageAwareEnergyStorage extends IEnergyStorage {

    VoltageClass outputVoltage();

    void reportOvervoltage(VoltageClass networkLimit);
}
