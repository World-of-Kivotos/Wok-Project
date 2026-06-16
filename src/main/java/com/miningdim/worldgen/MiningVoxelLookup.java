package com.miningdim.worldgen;

import com.miningdim.core.VoxelOccupancy;

import java.util.function.LongFunction;

/**
 * worldgen 子系统对外暴露的"instanceId -> 冻结体素视图"查表入口 (阶段2 接线点)。
 *
 * 背景 (跨子系统边界): MiningChunkGenerator 在区块回调里需按 region 查体素落方块, 体素由 instance
 * 子系统的离线调度器算好并缓存 (instance.GenerationScheduler.voxelsOf)。但模块化铁律 2 禁止 worldgen
 * import instance 实现类, 且 core.IInstanceManager 当前契约只给 regionAt(返回 InstanceState, 不含体素),
 * 未发布"instanceId -> VoxelOccupancy"的 core 级 seam。
 *
 * 为不修改 core / 不反向依赖 instance, worldgen 在自己包内提供这个静态函数式 seam:
 *   - MiningChunkGenerator 只读本类 (resolve) 取体素, 不知道提供方是谁;
 *   - 阶段2 集成层 (主类装配 或 instance 子系统的 register) 调 setProvider(id -> instanceManager.scheduler().voxelsOf(id))
 *     把 instance 的 voxelsOf 接进来。提供方依赖 worldgen 这个 seam (单向, 无环), 满足铁律 2:
 *     子系统协作经接口而非互相 import 实现类。
 *
 * 未接线时 resolve 返回 null, MiningChunkGenerator 据此填实心 (与 region 外/未就绪同处置, 7.7.1),
 * 不抛异常 —— 区块回调在世界刚加载、provider 尚未 set 的窗口期必须安全降级为实心墙, 而非崩服。
 *
 * 线程: provider 引用用 volatile, 读侧在区块 worker 线程, 写侧 (set) 在服务端启动期主线程, 单次设置。
 */
public final class MiningVoxelLookup {

    private MiningVoxelLookup() {
    }

    private static volatile LongFunction<VoxelOccupancy> provider;

    /** 阶段2 集成层注入体素提供方 (传 null 抛 IAE, 杜绝静默清空)。 */
    public static void setProvider(LongFunction<VoxelOccupancy> p) {
        if (p == null) {
            throw new IllegalArgumentException("voxel provider must not be null");
        }
        provider = p;
    }

    /** 按 instanceId 取冻结体素; 未接线或该实例无缓存返回 null (调用方填实心)。 */
    public static VoxelOccupancy resolve(long instanceId) {
        LongFunction<VoxelOccupancy> p = provider;
        if (p == null) {
            return null;
        }
        return p.apply(instanceId);
    }
}
