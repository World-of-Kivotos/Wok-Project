package com.miningdim.district.guard.create;

import com.miningdim.district.DistrictLimits;
import com.miningdim.district.core.DistrictBounds;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * /district machines 的扫描 (设计文档 22.8): 该区及外围 8 格内<b>已加载</b>区块的方块实体里, 按 {@link CreateBlockPolicy}
 * 算作"机器"的, 按方块 id 计数, 每种给前 10 个坐标, 并报告有多少区块没加载、没扫到。没有方块实体的拒绝名单方块 (犁、
 * 活塞杆、控制铁轨、红石触点) 不扫: 逐格扫方块状态太贵。只读, 不加载任何区块。
 */
public final class CreateMachineScan {

    /** 每种方块列几个坐标。 */
    public static final int COORDS_PER_BLOCK = 10;

    /** 一种方块: id、个数、前几个坐标。 */
    public record Entry(ResourceLocation block, int count, List<BlockPos> first) {

        public Entry {
            first = List.copyOf(first);
        }
    }

    /** 一次扫描: 按方块 id 排好的条目、总数、没加载的区块数。 */
    public record Result(List<Entry> entries, int total, int unloadedChunks) {

        public Result {
            entries = List.copyOf(entries);
        }
    }

    private CreateMachineScan() {
    }

    public static Result scan(ServerLevel level, DistrictBounds bounds, CreateBlockPolicy policy) {
        int buffer = DistrictLimits.BUFFER_BLOCKS;
        int minX = Math.min(bounds.minX(), bounds.maxX()) - buffer;
        int maxX = Math.max(bounds.minX(), bounds.maxX()) + buffer;
        int minZ = Math.min(bounds.minZ(), bounds.maxZ()) - buffer;
        int maxZ = Math.max(bounds.minZ(), bounds.maxZ()) + buffer;
        Map<ResourceLocation, List<BlockPos>> found = new TreeMap<>();
        Map<ResourceLocation, Integer> counts = new TreeMap<>();
        int unloaded = 0;
        int total = 0;
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) {
                    unloaded++;
                    continue;
                }
                for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
                    BlockPos pos = entry.getKey();
                    if (pos.getX() < minX || pos.getX() > maxX || pos.getZ() < minZ || pos.getZ() > maxZ) {
                        continue;
                    }
                    Block block = entry.getValue().getBlockState().getBlock();
                    if (!policy.isMachine(block)) {
                        continue;
                    }
                    ResourceLocation id = ForgeRegistries.BLOCKS.getKey(block);
                    if (id == null) {
                        continue;
                    }
                    total++;
                    counts.merge(id, 1, Integer::sum);
                    List<BlockPos> coords = found.computeIfAbsent(id, ignored -> new ArrayList<>());
                    if (coords.size() < COORDS_PER_BLOCK) {
                        coords.add(pos.immutable());
                    }
                }
            }
        }
        List<Entry> entries = new ArrayList<>();
        for (Map.Entry<ResourceLocation, Integer> count : counts.entrySet()) {
            List<BlockPos> coords = new ArrayList<>(found.get(count.getKey()));
            coords.sort(BlockPos::compareTo);
            entries.add(new Entry(count.getKey(), count.getValue(), coords));
        }
        return new Result(entries, total, unloaded);
    }
}
