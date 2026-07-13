package com.miningdim.instance;

import com.miningdim.core.GenState;
import com.miningdim.core.InstanceState;
import com.miningdim.core.MiningConstants;
import com.miningdim.core.RegionBox;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.common.world.ForgeChunkManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 实例生成状态调度器 (设计文档 7.9)。原设计的自定义离线体素管线 (IOfflineGenerator -> voxelsOf ->
 * MiningChunkGenerator 落方块) 已判废: 维度改用 minecraft:noise 懒生成 + 原版 ore feature, MiningChunkGenerator
 * 从不被实例化, 故 submit 不再跑体素计算 (原"首次 enter 慢"根源)。整 region 开机预热 (分帧 force-load 全 region
 * 触发噪声落地形) 亦已按方案 B 摘除: 地形、陷阱伪装 (ChunkEvent.Load 驱动) 与 reset 后重生成全部走懒生成,
 * 玩家进场只依赖 EntryGateway 既有的 spawn 周边 force-load (ChunkTicketManager)。当前职责收敛为:
 *   1. submit(): 直接把实例置 READY 并通知终态回调 (兑现挂起的 allocate future), 无任何区块预加载。
 *   2. release(): 离场空置/销毁时释放该 region owner 的全部强加载票 (含 EntryGateway 用同 owner 铺的 spawn
 *      周边票), 允许区块自然卸载。
 *
 * 线程纪律 (D8): 全部方法均主线程调用 (由 InstanceManager 串行)。
 */
public final class GenerationScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/GenerationScheduler");

    /** force-load owner key (ForgeChunkManager 按 modId + ownerBlockPos 归属强加载票)。 */
    private final MinecraftServer server;

    /**
     * 生成终态回调 (主线程): 实例进入 READY/READY_FALLBACK/FAILED 后通知 InstanceManager,
     * 以兑现/异常完成挂起的 allocate future。由 InstanceManager 注入, 不暴露给其他子系统。
     */
    private final java.util.function.Consumer<InstanceState> onTerminalState;

    public GenerationScheduler(MinecraftServer server,
                               java.util.function.Consumer<InstanceState> onTerminalState) {
        this.server = server;
        this.onTerminalState = onTerminalState;
    }

    /**
     * 为实例提交生成 (7.9.1)。前置: 实例已登记、genState == PENDING。同步在主线程完成: 直接置 READY、通知终态
     * 回调兑现挂起的 allocate future。无任何区块预加载 —— 地形走 minecraft:noise 懒生成, 玩家进场时由
     * EntryGateway 的 spawn 周边 force-load 触发落地形。不再有工作线程异步窗口 (体素管线已判废), 也不再有整
     * region 开机预热队列 (方案 B 已摘除)。仅主线程调用 (由 InstanceManager 串行)。
     */
    public void submit(InstanceState instance) {
        instance.setGenState(GenState.READY);
        LOGGER.info("[miningdim] instance {} READY (noise lazy-gen; no region preheat)",
                instance.instanceId());
        onTerminalState.accept(instance);
    }

    /** 回收/离场空置时释放该 region 强加载票, 允许区块自然卸载 (主线程)。 */
    public void release(InstanceState instance) {
        ServerLevel level = server.getLevel(MiningConstants.MINING_LEVEL);
        if (level == null) {
            return;
        }
        RegionBox box = instance.regionBox();
        int minChunkX = box.originX() >> 4;
        int minChunkZ = box.originZ() >> 4;
        int maxChunkX = (box.originX() + box.sizeX() - 1) >> 4;
        int maxChunkZ = (box.originZ() + box.sizeZ() - 1) >> 4;
        net.minecraft.core.BlockPos owner =
                new net.minecraft.core.BlockPos(box.originX(), box.originY(), box.originZ());
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                ForgeChunkManager.forceChunk(level, MiningConstants.MODID, owner, cx, cz, false, false);
            }
        }
    }
}
