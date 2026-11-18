package com.miningdim.freshness;

import com.miningdim.core.MiningConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** 放置食物记录的区块存读测试；事件与掉落测试随运行入口接入。 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class FreshnessPlacedFoodGameTests {
    private static final String EMPTY = "empty";
    private static final String BATCH = "freshness_placed";
    private static final int SLOT = 16;
    private static final BlockPos CAKE_REL = new BlockPos(1, 1, 1);

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void recordsFollowTheChunkData(GameTestHelper helper) {
        FreshnessConfig.Mode previousMode = FreshnessConfig.MODE.get();
        FreshnessConfig.MODE.set(FreshnessConfig.Mode.DRY_RUN);
        FreshnessPlacedFood.resetForTest();
        try {
            int slot = 3;
            ServerLevel level = helper.getLevel();
            long nowMinute = FreshnessClockData.nowMinute(level);
            BlockPos pos = helper.absolutePos(cake(slot, CAKE_REL));
            ChunkPos chunkPos = new ChunkPos(pos);

            ItemStack cake = new ItemStack(Items.CAKE, 1);
            FreshnessData.stampAt(cake, nowMinute - 600L, 3);
            FreshnessData.markMigrated(cake);
            FreshnessData.markVillagerGoodsFlag(cake);
            FreshnessPlacedFood.record(level, pos, cake, Blocks.CAKE.defaultBlockState());
            helper.assertTrue(FreshnessPlacedFood.at(level.dimension(), pos).isPresent(), "先记上");

            // Forge 真实顺序是 Unload → Save；直到 tick END 才可释放内存记录。
            CompoundTag data = new CompoundTag();
            FreshnessPlacedFood.unload(level.dimension(), chunkPos);
            FreshnessPlacedFood.saveTo(level.dimension(), chunkPos, data);
            helper.assertTrue(data.contains(FreshnessPlacedFood.KEY_PLACED), "写进了区块附加数据");
            FreshnessPlacedFood.finishChunkUnloads();
            helper.assertTrue(FreshnessPlacedFood.at(level.dimension(), pos).isEmpty(), "内存清空了");
            FreshnessPlacedFood.loadFrom(level.dimension(), chunkPos, data);
            FreshnessPlacedFood.Placed back = FreshnessPlacedFood.at(level.dimension(), pos).orElse(null);
            helper.assertTrue(back != null, "读回来了");
            helper.assertTrue(back.batchMinute() == nowMinute - 600L && back.stoppedHours() == 3,
                    "批次时刻与停表小时原样: " + back);
            ItemStack inherited = new ItemStack(Items.CAKE);
            back.stampOnto(inherited);
            helper.assertTrue(FreshnessData.isMigrated(inherited) && FreshnessData.isVillagerGoods(inherited)
                            && FreshnessData.batchMinute(inherited) == nowMinute - 600L
                            && FreshnessData.stoppedHours(inherited) == 3,
                    "取份和掉落继承完整的批次、停表与来源标记");

            CompoundTag legacy = data.copy();
            CompoundTag legacyEntry = legacy.getList(FreshnessPlacedFood.KEY_PLACED, Tag.TAG_COMPOUND).getCompound(0);
            legacyEntry.remove("M");
            legacyEntry.remove("V");
            FreshnessPlacedFood.loadFrom(level.dimension(), chunkPos, legacy);
            FreshnessPlacedFood.Placed old = FreshnessPlacedFood.at(level.dimension(), pos).orElseThrow();
            helper.assertTrue(!old.migrated() && !old.villagerGoods() && old.batchMinute() == nowMinute - 600L,
                    "旧记录仅有 P/B/H/F；不能凭空恢复从未保存的来源标记");

            FreshnessPlacedFood.unload(level.dimension(), chunkPos);
            FreshnessPlacedFood.finishChunkUnloads();
            helper.assertTrue(FreshnessPlacedFood.at(level.dimension(), pos).isEmpty(), "卸载之后内存里不再留着");

            // 空记录不该写键。
            CompoundTag empty = new CompoundTag();
            FreshnessPlacedFood.saveTo(level.dimension(), chunkPos, empty);
            helper.assertTrue(!empty.contains(FreshnessPlacedFood.KEY_PLACED), "没有记录就不写这个键");
            helper.succeed();
        } finally {
            FreshnessPlacedFood.resetForTest();
            FreshnessConfig.MODE.set(previousMode);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void damagedPlacedRecordsCannotInventFreshness(GameTestHelper helper) {
        // ChunkDataEvent.Load 读取保存的记录；缺少 H 或错误类型 F 不能静默变为 0。
        BlockPos pos = helper.absolutePos(CAKE_REL);
        ChunkPos chunk = new ChunkPos(pos);
        CompoundTag valid = placedData(pos, 12360);
        CompoundTag missingBatch = valid.copy();
        missingBatch.getList(FreshnessPlacedFood.KEY_PLACED, Tag.TAG_COMPOUND).getCompound(0).remove("H");
        CompoundTag wrongStoppedType = valid.copy();
        wrongStoppedType.getList(FreshnessPlacedFood.KEY_PLACED, Tag.TAG_COMPOUND).getCompound(0).putString("F", "broken");
        try {
            for (CompoundTag damaged : new CompoundTag[]{missingBatch, wrongStoppedType}) {
                boolean rejected = false;
                try {
                    FreshnessPlacedFood.loadFrom(helper.getLevel().dimension(), chunk, damaged);
                } catch (IllegalArgumentException expected) {
                    rejected = true;
                }
                helper.assertTrue(rejected, "损坏的放置记录必须显式拒绝");
            }
            helper.succeed();
        } finally {
            FreshnessPlacedFood.resetForTest();
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void asynchronousReloadCancelsPendingRelease(GameTestHelper helper) {
        // IO线程可在卸载与服务端 tick END 之间重新读入同一区块。
        BlockPos pos = helper.absolutePos(CAKE_REL);
        ChunkPos chunk = new ChunkPos(pos);
        var dimension = helper.getLevel().dimension();
        try {
            FreshnessPlacedFood.loadFrom(dimension, chunk, placedData(pos, 12360));
            FreshnessPlacedFood.unload(dimension, chunk);
            java.util.concurrent.CompletableFuture.runAsync(
                    () -> FreshnessPlacedFood.loadFrom(dimension, chunk, placedData(pos, 12420))).join();
            FreshnessPlacedFood.finishChunkUnloads();
            helper.assertTrue(FreshnessPlacedFood.at(dimension, pos).orElseThrow().batchMinute() == 12420,
                    "旧卸载清理不得删除异步重新载入的新记录");
            helper.succeed();
        } finally {
            FreshnessPlacedFood.resetForTest();
        }
    }

    private static CompoundTag placedData(BlockPos pos, int batch) {
        CompoundTag entry = new CompoundTag();
        entry.putLong("P", pos.asLong());
        entry.putString("B", "minecraft:cake");
        entry.putInt("H", batch);
        entry.putInt("F", 0);
        ListTag entries = new ListTag();
        entries.add(entry);
        CompoundTag data = new CompoundTag();
        data.put(FreshnessPlacedFood.KEY_PLACED, entries);
        return data;
    }

    private static BlockPos cake(int slot, BlockPos relative) {
        return new BlockPos(slot * SLOT + relative.getX(), relative.getY(), relative.getZ());
    }
}
