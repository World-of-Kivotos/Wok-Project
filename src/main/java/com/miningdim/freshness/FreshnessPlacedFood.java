package com.miningdim.freshness;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 放置食物记录 (Spoilage_Granary_DesignSpec「物品数据与存档」: 区块附加数据; 「观察点」第一行:
 * 「任何战利品生成 —— 盖现在; **放置食物按记录继承**」)。
 *
 * 玩家把食物**摆成方块** (蛋糕、派这类) 时, 这个食物的保鲜章要跟着方块一起活下去: 记在**区块**上
 * (跟着区块存盘、跟着区块消失), 等这个方块再掉出东西时按记录继承 —— 否则「摆了一天的蛋糕再拆下来」
 * 会变成刚做出来的新货。
 *
 * 三条口径:
 * <ul>
 *   <li>记录里同时存**方块 id**: 只有「掉落的还是原来那个方块」时才继承。没了这一条, 一块被
 *       {@code /setblock} 换成别的东西之后留下的旧记录, 会把后来者的掉落物盖上过期的章;</li>
 *   <li>只在**易腐品**上记账 (石头、工具不记);</li>
 *   <li>继承完就忘掉 (不重复继承, 也不留旧账)。</li>
 * </ul>
 *
 * 说明: 记录是按**位置**记的, 所以「取份」(蛋糕切一块) 这种不产出物品的路径不需要它; 蛋糕剩下的部分
 * 仍按原记录算。第三方盛宴的取份继承随兼容分片接。
 */
public final class FreshnessPlacedFood {

    /** 区块附加数据的键 (跟着区块走)。 */
    public static final String KEY_PLACED = "miningdim_fresh_placed";

    private static final String KEY_POS = "P";
    private static final String KEY_BLOCK = "B";
    private static final String KEY_H = "H";
    private static final String KEY_F = "F";

    /** 一条记录: 这个位置摆着哪个方块、它的批次时刻与已停表小时。 */
    public record Placed(ResourceLocation block, int batchMinute, int stoppedHours) {
    }

    /** 每个已加载区块一份 (区块卸载时丢掉, 存盘由 ChunkDataEvent.Save 负责)。 */
    private record ChunkKey(ResourceKey<Level> dimension, long pos) {}

    private static final Map<ChunkKey, Map<BlockPos, Placed>> BY_CHUNK = new HashMap<>();

    private FreshnessPlacedFood() {
    }

    // ============================================================
    // 读
    // ============================================================

    /** 这个位置放着的食物记录 (没有就是空)。 */
    public static Optional<Placed> at(ResourceKey<Level> dimension, BlockPos pos) {
        Map<BlockPos, Placed> chunk = BY_CHUNK.get(key(dimension, pos));
        return chunk == null ? Optional.empty() : Optional.ofNullable(chunk.get(pos));
    }

    private static ChunkKey key(ResourceKey<Level> dimension, BlockPos pos) {
        return new ChunkKey(dimension, ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
    }

    /** 取份与食用共享这份来源；旧位置已换方块时清理旧账。 */
    public static ItemStack sourceAt(ServerLevel level, BlockPos pos) {
        Optional<Placed> found = at(level.dimension(), pos);
        if (found.isEmpty()) {
            return ItemStack.EMPTY;
        }
        BlockState state = level.getBlockState(pos);
        if (!found.get().block().equals(ForgeRegistries.BLOCKS.getKey(state.getBlock()))) {
            forget(level.dimension(), pos);
            level.getChunkAt(pos).setUnsaved(true);
            return ItemStack.EMPTY;
        }
        ItemStack source = new ItemStack(state.getBlock().asItem());
        FreshnessData.stampAt(source, found.get().batchMinute(), found.get().stoppedHours());
        return source;
    }

    /** 现在记着多少条 (诊断与用例用)。 */
    public static int size() {
        int total = 0;
        for (Map<BlockPos, Placed> chunk : BY_CHUNK.values()) {
            total += chunk.size();
        }
        return total;
    }

    // ============================================================
    // 写
    // ============================================================

    /**
     * 记下「这个位置摆着这个食物」。
     *
     * @param stack 摆下去的那件物品 (没盖章的当场盖章, 记的是盖章后的章)
     */
    public static void record(ServerLevel level, BlockPos pos, ItemStack stack, BlockState placedState) {
        if (!FreshnessConfig.stampingEnabled() || stack.isEmpty() || !FreshnessCategories.spoils(stack)) {
            return;
        }
        long nowMinute = FreshnessClockData.nowMinute(level);
        FreshnessData.stamp(stack, nowMinute);
        ResourceLocation block = ForgeRegistries.BLOCKS.getKey(placedState.getBlock());
        if (block == null) {
            return;
        }
        BY_CHUNK.computeIfAbsent(key(level.dimension(), pos), key -> new HashMap<>())
                .put(pos.immutable(), new Placed(block, (int) FreshnessData.batchMinute(stack),
                        FreshnessData.stoppedHours(stack)));
        level.getChunkAt(pos).setUnsaved(true);
    }

    /** 忘掉这个位置的记录 (继承过了, 或者方块已经不是它了)。 */
    public static void forget(ResourceKey<Level> dimension, BlockPos pos) {
        Map<BlockPos, Placed> chunk = BY_CHUNK.get(key(dimension, pos));
        if (chunk != null && chunk.remove(pos) != null && chunk.isEmpty()) {
            BY_CHUNK.remove(key(dimension, pos));
        }
    }

    /**
     * 战利品生成时, 这个位置能不能继承 (能就返回记录, 并把记录清掉)。
     *
     * 只在「掉落的地点仍是原来那个方块」时继承 —— 换过方块的位置上留着的是旧账, 不该盖给后来者。
     */
    public static Optional<Placed> takeForLoot(LootContext context) {
        Vec3 origin = context.getParamOrNull(LootContextParams.ORIGIN);
        if (origin == null) {
            return Optional.empty();
        }
        BlockPos pos = BlockPos.containing(origin);
        Optional<Placed> found = at(context.getLevel().dimension(), pos);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        BlockState state = context.getParamOrNull(LootContextParams.BLOCK_STATE);
        ResourceLocation current = state == null ? null : ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (current == null || !current.equals(found.get().block())) {
            forget(context.getLevel().dimension(), pos);
            return Optional.empty();
        }
        forget(context.getLevel().dimension(), pos);
        return found;
    }

    /** 区块卸载: 丢掉这一片的记录 (存盘在 ChunkDataEvent.Save 里做过)。 */
    public static void unload(ResourceKey<Level> dimension, ChunkPos chunkPos) {
        BY_CHUNK.remove(new ChunkKey(dimension, chunkPos.toLong()));
    }

    /** 用例与诊断用: 清空全部内存记录。 */
    public static void resetForTest() {
        BY_CHUNK.clear();
    }

    // ============================================================
    // 区块存档 (ChunkDataEvent.Load / Save)
    // ============================================================

    /** 把一个区块的附加数据读进内存 (没有就是空)。 */
    public static void loadFrom(ResourceKey<Level> dimension, ChunkPos chunkPos, CompoundTag data) {
        if (!data.contains(KEY_PLACED, Tag.TAG_LIST)) {
            return;
        }
        ListTag list = data.getList(KEY_PLACED, Tag.TAG_COMPOUND);
        if (list.isEmpty()) {
            return;
        }
        Map<BlockPos, Placed> chunk = new HashMap<>();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            if (!entry.contains(KEY_POS, Tag.TAG_LONG) || !entry.contains(KEY_BLOCK, Tag.TAG_STRING)) {
                continue;
            }
            chunk.put(BlockPos.of(entry.getLong(KEY_POS)),
                    new Placed(new ResourceLocation(entry.getString(KEY_BLOCK)),
                            entry.getInt(KEY_H), entry.getInt(KEY_F)));
        }
        if (!chunk.isEmpty()) {
            BY_CHUNK.put(new ChunkKey(dimension, chunkPos.toLong()), chunk);
        }
    }

    /** 把一个区块的记录写进附加数据 (没有记录就不写键)。 */
    public static void saveTo(ResourceKey<Level> dimension, ChunkPos chunkPos, CompoundTag data) {
        Map<BlockPos, Placed> chunk = BY_CHUNK.get(new ChunkKey(dimension, chunkPos.toLong()));
        if (chunk == null || chunk.isEmpty()) {
            data.remove(KEY_PLACED);
            return;
        }
        ListTag list = new ListTag();
        chunk.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    CompoundTag tag = new CompoundTag();
                    tag.putLong(KEY_POS, entry.getKey().asLong());
                    tag.putString(KEY_BLOCK, entry.getValue().block().toString());
                    tag.putInt(KEY_H, entry.getValue().batchMinute());
                    tag.putInt(KEY_F, entry.getValue().stoppedHours());
                    list.add(tag);
                });
        data.put(KEY_PLACED, list);
    }
}
