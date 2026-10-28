package com.miningdim.job.chef.fryer;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;

/** 只有实际区块写入和刷新均成功，才允许回收已被区块覆盖的检查点。 */
public final class FryerChunkPersistence {
    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/fryer");
    private static final Map<IOWorker, Map<Long, Object>> WRITES = new WeakHashMap<>();

    private FryerChunkPersistence() {}

    public static CompletableFuture<Void> track(ServerLevel level, IOWorker worker, ChunkPos pos, CompoundTag chunk,
                             CompletableFuture<Void> written) {
        FryerPlayerData data = FryerPlayerData.get(level);
        Map<BlockPos, CompoundTag> candidates = data.checkpointsInChunk(level, pos);
        Object token = new Object();
        synchronized (WRITES) {
            Map<Long, Object> writes = WRITES.computeIfAbsent(worker, ignored -> new HashMap<>());
            writes.put(pos.toLong(), token);
        }
        if (candidates.isEmpty()) { finishWrite(worker, pos, token); return CompletableFuture.completedFuture(null); }
        Map<BlockPos, CompoundTag> entities = new HashMap<>();
        for (Tag entry : chunk.getList("block_entities", Tag.TAG_COMPOUND)) {
            CompoundTag entity = (CompoundTag) entry;
            entities.put(new BlockPos(entity.getInt("x"), entity.getInt("y"), entity.getInt("z")), entity);
        }
        Map<BlockPos, CompoundTag> receipts = new HashMap<>();
        candidates.forEach((position, snapshot) -> {
            if (covered(snapshot, entities.get(position))) receipts.put(position, snapshot);
        });
        if (receipts.isEmpty()) { finishWrite(worker, pos, token); return CompletableFuture.completedFuture(null); }
        // Forge Save 事件发生在 store 之前；必须保留本次真正的写入 future，失败不能被后续 flush 掩盖。
        return written.thenCompose(ignored -> worker.synchronize(true))
                .thenCompose(ignored -> level.getServer().submit(() -> {
                    // 同区块 store 会合并并复用 future；被后来一次写入替代的收据不能证明其内容落盘。
                    if (finishWrite(worker, pos, token)) data.acknowledgeChunk(level, receipts);
                })).whenComplete((ignored, failure) -> {
            if (failure != null) {
                finishWrite(worker, pos, token);
                LOGGER.error("Cannot acknowledge fryer chunk dimension={} chunk={}; checkpoints retained",
                        level.dimension().location(), pos, failure);
            }
        });
    }

    private static boolean finishWrite(IOWorker worker, ChunkPos pos, Object token) {
        synchronized (WRITES) {
            Map<Long, Object> writes = WRITES.get(worker);
            return writes != null && writes.remove(pos.toLong(), token);
        }
    }

    static boolean covered(CompoundTag snapshot, CompoundTag entity) {
        if (entity == null) {
            if (snapshot.getBoolean("FryerRemoved")) return true;
            // 已发布 v2 journal 的墓碑没有 Removed 标记；只回收空闲且无任何物品的旧快照。
            return (!snapshot.contains("FryerRevision") || snapshot.getLong("FryerRevision") == 0L)
                    && snapshot.contains("InProgress", Tag.TAG_BYTE)
                    && !snapshot.getBoolean("InProgress") && snapshot.contains("Inventory", Tag.TAG_COMPOUND)
                    && snapshot.getCompound("Inventory").contains("Items", Tag.TAG_LIST)
                    && snapshot.getCompound("Inventory").getList("Items", Tag.TAG_COMPOUND).isEmpty()
                    && snapshot.contains("BufferedOutputs", Tag.TAG_LIST)
                    && snapshot.getList("BufferedOutputs", Tag.TAG_COMPOUND).isEmpty()
                    && snapshot.contains("ReturnedItems", Tag.TAG_LIST)
                    && snapshot.getList("ReturnedItems", Tag.TAG_COMPOUND).isEmpty();
        }
        long revision = snapshot.contains("FryerRevision", Tag.TAG_LONG) ? snapshot.getLong("FryerRevision") : 0L;
        long savedRevision = entity.contains("FryerRevision", Tag.TAG_LONG) ? entity.getLong("FryerRevision") : 0L;
        if (savedRevision > revision) return true;
        if (savedRevision < revision) return false;
        return snapshot.getAllKeys().stream().allMatch(key -> snapshot.get(key).equals(entity.get(key)));
    }
}
