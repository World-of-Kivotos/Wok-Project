package com.miningdim.freshness;

import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.event.level.BlockEvent;
import java.util.List;
import com.miningdim.core.MiningConstants;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
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
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** 放置食物记录的区块存读与战利品继承回归。 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class FreshnessPlacedFoodGameTests {
    private static final BlockPos STONE_REL = new BlockPos(3, 1, 1);
    private static final BlockPos BUSH_REL = new BlockPos(5, 1, 1);
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

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void onlyBlockLootConsumesThePlacedBatch(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(CAKE_REL);
        FreshnessConfig.Mode previous = FreshnessConfig.MODE.get();
        FreshnessConfig.MODE.set(FreshnessConfig.Mode.DRY_RUN);
        try {
            ItemStack cake = new ItemStack(Items.CAKE);
            long batch = FreshnessClockData.nowMinute(level) - 600;
            FreshnessData.stampAt(cake, batch, 3);
            FreshnessData.markMigrated(cake);
            FreshnessData.markVillagerGoodsFlag(cake);
            FreshnessPlacedFood.record(level, pos, cake, Blocks.CAKE.defaultBlockState());

            var modifier = new FreshnessLootModifier(new LootItemCondition[0]);
            var params = new LootParams.Builder(level).withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(pos));
            var chest = new LootContext.Builder(params.create(LootContextParamSets.CHEST)).create(null);
            var unrelated = new ItemStack(Items.CAKE);
            modifier.doApply(ObjectArrayList.of(unrelated), chest);
            helper.assertTrue(!FreshnessData.isMigrated(unrelated) && FreshnessPlacedFood.at(level.dimension(), pos).isPresent(),
                    "同位置的非方块战利品不可取走放置食物记录");

            params.withParameter(LootContextParams.BLOCK_STATE, Blocks.CAKE.defaultBlockState())
                    .withParameter(LootContextParams.TOOL, ItemStack.EMPTY);
            var block = new LootContext.Builder(params.create(LootContextParamSets.BLOCK)).create(null);
            var dropped = new ItemStack(Items.CAKE);
            modifier.doApply(ObjectArrayList.of(dropped), block);
            helper.assertTrue(FreshnessData.batchMinute(dropped) == batch && FreshnessData.stoppedHours(dropped) == 3
                            && FreshnessData.isMigrated(dropped) && FreshnessData.isVillagerGoods(dropped)
                            && FreshnessPlacedFood.at(level.dimension(), pos).isEmpty(),
                    "拆除掉落必须一次继承完整批次、停表与来源标记");
            helper.succeed();
        } finally {
            FreshnessPlacedFood.forget(level.dimension(), pos);
            FreshnessConfig.MODE.set(previous);
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

    @BeforeBatch(batch = BATCH)
    public static void resetRecords(ServerLevel level) {
        FreshnessPlacedFood.resetForTest();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void placingFoodRecordsItsStamp(GameTestHelper helper) {
        int slot = 0;
        ServerLevel level = helper.getLevel();
        long nowMinute = FreshnessClockData.nowMinute(level);
        BlockPos pos = helper.absolutePos(cake(slot, CAKE_REL));

        // 手上拿一个盖过章的蛋糕 (三天前的一批, 停过 7 小时): 摆下去应当原样记下来。
        ItemStack cake = new ItemStack(Items.CAKE, 1);
        FreshnessData.stampAt(cake, nowMinute - 3L * 24L * 60L, 7);
        place(helper, pos, Blocks.CAKE, cake);

        FreshnessPlacedFood.Placed placed = FreshnessPlacedFood.at(level.dimension(), pos).orElse(null);
        helper.assertTrue(placed != null, "摆下蛋糕要记一笔 (size=" + FreshnessPlacedFood.size() + ")");
        helper.assertTrue(placed.block().equals(net.minecraftforge.registries.ForgeRegistries.BLOCKS
                        .getKey(Blocks.CAKE)), "记的是这个方块的 id, 实际 " + placed.block());
        helper.assertTrue(placed.batchMinute() == nowMinute - 3L * 24L * 60L,
                "批次时刻按物品上的章记, 实际 " + placed.batchMinute());
        helper.assertTrue(placed.stoppedHours() == 7, "已停表小时一起记, 实际 " + placed.stoppedHours());

        // 没盖章的: 摆下去那一刻当场盖章, 记的是现在。
        BlockPos fresh = helper.absolutePos(cake(slot, CAKE_REL.offset(0, 0, 2)));   // 别撞上 STONE_REL
        place(helper, fresh, Blocks.CAKE, new ItemStack(Items.CAKE, 1));
        FreshnessPlacedFood.Placed stamped = FreshnessPlacedFood.at(level.dimension(), fresh).orElse(null);
        helper.assertTrue(stamped != null, "没盖章的摆下去也要记");
        helper.assertTrue(stamped.batchMinute() == FreshnessData.wholeHour(nowMinute),
                "记的是当场盖的整点章, 实际 " + stamped.batchMinute());
        helper.assertTrue(stamped.stoppedHours() == 0, "新货没停过表");

        // 不是易腐品: 不记。
        BlockPos stone = helper.absolutePos(cake(slot, STONE_REL));
        place(helper, stone, Blocks.STONE, new ItemStack(Items.STONE, 1));
        helper.assertTrue(FreshnessPlacedFood.at(level.dimension(), stone).isEmpty(), "石头不记");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void lootInheritsThePlacedRecord(GameTestHelper helper) {
        int slot = 1;
        ServerLevel level = helper.getLevel();
        long nowMinute = FreshnessClockData.nowMinute(level);
        BlockPos pos = helper.absolutePos(cake(slot, BUSH_REL));

        // 甜浆果丛会掉甜浆果 (易腐品): 先按「摆了一天的浆果丛」记一笔。
        helper.setBlock(cake(slot, BUSH_REL), matureBush());
        FreshnessPlacedFood.record(level, pos, new ItemStack(Items.SWEET_BERRIES, 1), matureBush());
        FreshnessPlacedFood.Placed recorded = FreshnessPlacedFood.at(level.dimension(), pos).orElseThrow();
        helper.assertTrue(recorded.batchMinute() == FreshnessData.wholeHour(nowMinute),
                "夹具: 记录的是当场盖的章");

        // 拆掉它: 掉出来的浆果要按记录继承, 而不是盖「现在」。
        level.destroyBlock(pos, true);
        ItemEntity drop = level.getEntitiesOfClass(ItemEntity.class,
                        new net.minecraft.world.phys.AABB(pos).inflate(2.0D)).stream()
                .filter(entity -> entity.getItem().is(Items.SWEET_BERRIES))
                .findFirst().orElse(null);
        helper.assertTrue(drop != null, "浆果丛要掉出浆果 (方块=" + level.getBlockState(pos).getBlock() + ")");
        helper.assertTrue(FreshnessData.batchMinute(drop.getItem()) == recorded.batchMinute(),
                "掉落物按记录继承批次时刻: 记录 " + recorded.batchMinute() + " / 实际 "
                        + FreshnessData.batchMinute(drop.getItem()));
        helper.assertTrue(FreshnessPlacedFood.at(level.dimension(), pos).isEmpty(), "继承完就忘掉 (不重复继承)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void staleRecordIsNotInheritedByAnotherBlock(GameTestHelper helper) {
        int slot = 2;
        ServerLevel level = helper.getLevel();
        long nowMinute = FreshnessClockData.nowMinute(level);
        BlockPos pos = helper.absolutePos(cake(slot, BUSH_REL));

        // 记一笔「蛋糕」的旧账 (三天前), 然后这个位置其实放的是浆果丛 —— 旧账不该盖给浆果。
        helper.setBlock(cake(slot, BUSH_REL), matureBush());
        FreshnessPlacedFood.record(level, pos,
                new ItemStack(Items.CAKE, 1), Blocks.CAKE.defaultBlockState());
        BlockState bush = matureBush();
        java.util.Optional<FreshnessPlacedFood.Placed> forBush =
                FreshnessPlacedFood.at(level.dimension(), pos).filter(placed -> placed.block().equals(
                        net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(bush.getBlock())));
        helper.assertTrue(forBush.isEmpty(), "方块对不上的记录不算 (判定按方块 id)");

        level.destroyBlock(pos, true);
        ItemEntity drop = level.getEntitiesOfClass(ItemEntity.class,
                        new net.minecraft.world.phys.AABB(pos).inflate(2.0D)).stream()
                .filter(entity -> entity.getItem().is(Items.SWEET_BERRIES))
                .findFirst().orElse(null);
        helper.assertTrue(drop != null, "浆果丛要掉出浆果");
        helper.assertTrue(FreshnessData.batchMinute(drop.getItem()) == FreshnessData.wholeHour(nowMinute),
                "拿到的应当是「现在」这一章而不是旧账: 实际 " + FreshnessData.batchMinute(drop.getItem()));
        helper.assertTrue(FreshnessPlacedFood.at(level.dimension(), pos).isEmpty(), "对不上的旧账应被清理");
        FreshnessPlacedFood.resetForTest();
        helper.succeed();
    }

    private static BlockState matureBush() {
        return Blocks.SWEET_BERRY_BUSH.defaultBlockState()
                .setValue(net.minecraft.world.level.block.SweetBerryBushBlock.AGE, 3);
    }

    private static void place(GameTestHelper helper, BlockPos absolute, net.minecraft.world.level.block.Block block,
                              ItemStack held) {
        Player player = com.miningdim.testutil.MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, held);
        helper.getLevel().setBlock(absolute, block.defaultBlockState(), 3);
        BlockEvent.EntityPlaceEvent event = new BlockEvent.EntityPlaceEvent(
                BlockSnapshot.create(helper.getLevel().dimension(), helper.getLevel(), absolute),
                Blocks.AIR.defaultBlockState(), player);
        FreshnessPlacedFoodHooks.onBlockPlaced(event);
        helper.assertTrue(!event.isCanceled(), "夹具: 放置事件没被取消");
    }
}
