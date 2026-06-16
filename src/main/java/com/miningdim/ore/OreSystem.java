package com.miningdim.ore;

import com.miningdim.core.InstanceState;
import com.miningdim.core.Subsystem;
import com.miningdim.core.VoxelOccupancy;
import net.minecraftforge.eventbus.api.IEventBus;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 矿物子系统入口 (模块化铁律 3)。本子系统是离线纯计算 + 查表服务, 不注册方块/物品/网络包,
 * 不订阅运行期事件 (铺矿在 OfflineGenerator 产出体素后由 GenerationScheduler 调用, 落方块由 MiningChunkGenerator
 * 读表)。故 register 仅做自注册并暴露静态查表入口, 不接 modBus/forgeBus 事件。
 *
 * 对外入口 (阶段2 接线点): MiningChunkGenerator 在区块填充阶段经 {@link #get()} 拿到本系统单例,
 * 调 {@link #placementFor(InstanceState, VoxelOccupancy)} 取实例铺矿表, 再 blockStateAt(x,y,z) 查矿替换方块。
 * 铺矿表按 (instanceId, seed) 缓存, 同实例多区块共享一次计算 (8.5 末: 缓存避免重复)。
 *
 * 跨子系统: 本系统只依赖 core (SeedUtil/RegionBox/VoxelOccupancy/MiningServices.config), 不 import 其他子系统实现。
 * 陷阱子系统若需"富矿"判据, 经本系统暴露的 OrePlacement.hasOreAt(...) 查询, 不反向依赖。
 */
public final class OreSystem implements Subsystem {

    /** 单例引用, 供 register 后的查表入口取用 (本系统不进 MiningServices, 因 core 无 IOre 门面)。 */
    private static volatile OreSystem instance;

    /**
     * 实例铺矿表缓存: instanceId -> OrePlacement。键含 seed 校验, 实例重置 (seed 变) 时失效重算。
     * 工作线程算表、主线程读表, 故用并发 map; 单条目 OrePlacement 自身不可变。
     */
    private final ConcurrentMap<Long, CachedPlacement> cache = new ConcurrentHashMap<>();

    @Override
    public void register(IEventBus modBus, IEventBus forgeBus) {
        // 矿物子系统无 DeferredRegister / 事件订阅: 仅自注册供查表。bus 参数未用是契约形态, 不留空壳 —— 见类注释说明。
        instance = this;
    }

    @Override
    public String name() {
        return "OreSystem";
    }

    /** 取本系统单例 (阶段2 MiningChunkGenerator 接线入口); 未注册抛 IllegalStateException, 不返 null (C9)。 */
    public static OreSystem get() {
        OreSystem ref = instance;
        if (ref == null) {
            throw new IllegalStateException("OreSystem not registered yet (check subsystem register order)");
        }
        return ref;
    }

    /**
     * 取实例铺矿表 (8.8): 缓存命中 (同 instanceId 同 seed) 直接复用; 否则在调用线程计算并缓存。
     * 设计上由 GenerationScheduler 在工作线程首次调用预热缓存, MiningChunkGenerator 主线程读区块时命中缓存零计算。
     * 若缓存未预热而主线程先到, 也能正确计算 (纯函数, 仅一次性开销), 不会出错。
     *
     * @param instance 实例 (提供 seed/difficulty/regionBox)
     * @param voxels   该实例已生成的体素视图
     */
    public OrePlacement placementFor(InstanceState instance, VoxelOccupancy voxels) {
        CachedPlacement cached = cache.get(instance.instanceId());
        if (cached != null && cached.seed == instance.seed()) {
            return cached.placement;
        }
        OrePlacement placement = OreGenerator.generate(
                instance.seed(), instance.difficulty(), instance.regionBox(), voxels);
        cache.put(instance.instanceId(), new CachedPlacement(instance.seed(), placement));
        return placement;
    }

    /** 已缓存的实例铺矿表 (无需 voxels 重算时的快查); 未缓存返回 null。供 MiningChunkGenerator 热路径优先调用。 */
    public OrePlacement cachedPlacement(long instanceId) {
        CachedPlacement cached = cache.get(instanceId);
        return cached == null ? null : cached.placement;
    }

    /** 实例释放/重置时清缓存 (供 InstanceManager/ResetService 经 core 事件回调或阶段2 接线调用)。 */
    public void invalidate(long instanceId) {
        cache.remove(instanceId);
    }

    private record CachedPlacement(long seed, OrePlacement placement) {
    }
}
