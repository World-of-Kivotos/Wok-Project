package com.miningdim.core;

/**
 * 实例被重置 (滑动到新 region) 后, 一切按 instanceId 缓存了旧几何/旧种子的子系统必须失效重算
 * (陷阱静态表、铺矿表、出生池、刷怪调度态)。这些子系统早就各自写好了清理方法却零调用点,
 * 本接口是它们与 reset 子系统之间唯一的解耦通道。
 */
@FunctionalInterface
public interface IInstanceResetListener {

    void onInstanceReset(long instanceId);
}
