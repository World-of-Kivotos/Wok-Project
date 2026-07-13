package com.miningdim.reset;

import com.miningdim.chunk.ChunkServices;
import com.miningdim.core.MiningConstants;
import com.miningdim.core.RegionBox;
import com.miningdim.trap.TrapRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.ChunkEntities;
import net.minecraft.world.level.entity.EntityPersistentStorage;
import net.minecraftforge.common.world.ForgeChunkManager;

import java.util.List;

/**
 * {@link ResetChunkOps} 的生产实现: 直达原版 ChunkMap / RegionFileStorage / PersistentEntitySectionManager
 * 做 region 区块的文件级删除 (设计文档第十三章物理重生成)。所有 API 均从 1.20.1 反编译 sources 核实:
 *   - 卸载判据: {@code ChunkMap.getUpdatingChunkIfPresent(long)} (AT 放开; updatingChunkMap 是当前全部 holder
 *     的权威超集), 返回 null 即该 chunk 无 holder。
 *   - 删地形: {@code ChunkStorage.write(pos, null)} -> {@code RegionFileStorage.write(null)} -> {@code RegionFile.clear(pos)}
 *     释放扇区; {@code ChunkStorage.flushWorker()} = {@code IOWorker.synchronize(true).join()} 排干 + 落盘。
 *   - 删实体: {@code EntityPersistentStorage.storeEntities(空 ChunkEntities)} -> {@code EntityStorage} 空分支
 *     {@code worker.store(pos, null)} 清 entities region; {@code flush(true)} 同步落盘。
 *
 * 这些动作只能在真服验证 (GameTest 无法可靠复现区块卸载/存档删除时序), 故隔在本类; 状态机由 ResetJob 承担并
 * 用记录型替身在 GameTest 锁死。全部主线程调用 (D8)。
 */
final class LiveResetChunkOps implements ResetChunkOps {

    private final ServerLevel miningLevel;
    private final long instanceId;
    private final RegionBox region;

    /** region 覆盖的 chunk 边界 (含), region 与 chunk 对齐, 构造期算一次。 */
    private final int minChunkX;
    private final int maxChunkX;
    private final int minChunkZ;
    private final int maxChunkZ;

    LiveResetChunkOps(ServerLevel miningLevel, long instanceId, RegionBox region) {
        this.miningLevel = miningLevel;
        this.instanceId = instanceId;
        this.region = region;
        this.minChunkX = region.originX() >> 4;
        this.maxChunkX = (region.originX() + region.sizeX() - 1) >> 4;
        this.minChunkZ = region.originZ() >> 4;
        this.maxChunkZ = (region.originZ() + region.sizeZ() - 1) >> 4;
    }

    @Override
    public void releaseTickets() {
        // entry 滑动窗口 ticket (仅维度就绪时存在)。
        if (ChunkServices.isReady()) {
            ChunkServices.ticketService().releaseAll(instanceId);
        }
        // 生成期 ForgeChunkManager region 强制块 (owner = region 原点); add=false 幂等取消, 未强制则无副作用。
        BlockPos owner = new BlockPos(region.originX(), region.originY(), region.originZ());
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                ForgeChunkManager.forceChunk(miningLevel, MiningConstants.MODID, owner, cx, cz, false, false);
            }
        }
    }

    @Override
    public int clearTrapRegistry() {
        TrapRegistry registry = TrapRegistry.get(miningLevel);
        int cleared = 0;
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                cleared += registry.clearChunk(new ChunkPos(cx, cz));
            }
        }
        return cleared;
    }

    @Override
    public boolean allChunksUnloaded() {
        ChunkMap chunkMap = miningLevel.getChunkSource().chunkMap;
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                // 任一 chunk 仍在 updatingChunkMap 命中 (holder 存在) 即未卸载完。
                if (chunkMap.getUpdatingChunkIfPresent(ChunkPos.asLong(cx, cz)) != null) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public void flushPendingWrites() {
        // 地形: ChunkMap 继承 ChunkStorage.flushWorker -> IOWorker.synchronize(true).join()。
        miningLevel.getChunkSource().chunkMap.flushWorker();
        // 实体: entities IOWorker 同步落盘。
        entityStorage().flush(true);
    }

    @Override
    public void deleteChunk(int chunkX, int chunkZ) {
        ChunkPos pos = new ChunkPos(chunkX, chunkZ);
        // 地形: 写 null -> RegionFile.clear(pos) 释放扇区, 下次加载无存档 -> minecraft:noise 重生成。
        miningLevel.getChunkSource().chunkMap.write(pos, null);
        // 实体: 写空 ChunkEntities -> EntityStorage 空分支 store(null) -> entities region 清空 (否则旧 mob 憋在新地形)。
        entityStorage().storeEntities(new ChunkEntities<Entity>(pos, List.of()));
    }

    /** ServerLevel.entityManager.permanentStorage (两级 AT 放开的私有引用)。 */
    private EntityPersistentStorage<Entity> entityStorage() {
        return miningLevel.entityManager.permanentStorage;
    }
}
