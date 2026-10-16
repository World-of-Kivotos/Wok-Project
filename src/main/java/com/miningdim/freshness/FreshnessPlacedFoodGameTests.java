package com.miningdim.freshness;

import com.miningdim.core.MiningConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * 放置食物记录的 GameTest (docs/Spoilage_Granary_DesignSpec.md「物品数据与存档」区块附加数据、
 * 「观察点」第一行「放置食物按记录继承」)。
 *
 * 锁五件事: 摆下一个易腐品方块就记一笔 (没盖章的当场盖章)、不是易腐品不记、**只有掉落地点仍是原来
 * 那个方块时才继承** (换过方块的位置上留的旧账不许盖给后来者)、继承完就忘掉、以及记录跟着区块
 * 存读 (内存里丢掉再读回来还在)。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class FreshnessPlacedFoodGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "freshness_placed";

    /** 每条用例占自己的一条槽位 (同一批次的用例并行跑且共用区域)。 */
    private static final int SLOT = 16;

    private static final BlockPos CAKE_REL = new BlockPos(1, 1, 1);
    private static final BlockPos STONE_REL = new BlockPos(3, 1, 1);
    private static final BlockPos BUSH_REL = new BlockPos(5, 1, 1);

    /** 记录是进程级内存表: 本批先清空, 免得上一轮残留把断言带红。 */
    @BeforeBatch(batch = BATCH)
    public static void resetRecords(ServerLevel level) {
        FreshnessPlacedFood.resetForTest();
    }

    // ============================================================
    // 记账
    // ============================================================

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

        FreshnessPlacedFood.Placed placed = FreshnessPlacedFood.at(pos).orElse(null);
        helper.assertTrue(placed != null, "摆下蛋糕要记一笔 (size=" + FreshnessPlacedFood.size() + ")");
        helper.assertTrue(placed.block().equals(net.minecraftforge.registries.ForgeRegistries.BLOCKS
                        .getKey(Blocks.CAKE)), "记的是这个方块的 id, 实际 " + placed.block());
        helper.assertTrue(placed.batchMinute() == nowMinute - 3L * 24L * 60L,
                "批次时刻按物品上的章记, 实际 " + placed.batchMinute());
        helper.assertTrue(placed.stoppedHours() == 7, "已停表小时一起记, 实际 " + placed.stoppedHours());

        // 没盖章的: 摆下去那一刻当场盖章, 记的是现在。
        BlockPos fresh = helper.absolutePos(cake(slot, CAKE_REL.offset(0, 0, 2)));   // 别撞上 STONE_REL
        place(helper, fresh, Blocks.CAKE, new ItemStack(Items.CAKE, 1));
        FreshnessPlacedFood.Placed stamped = FreshnessPlacedFood.at(fresh).orElse(null);
        helper.assertTrue(stamped != null, "没盖章的摆下去也要记");
        helper.assertTrue(stamped.batchMinute() == FreshnessData.wholeHour(nowMinute),
                "记的是当场盖的整点章, 实际 " + stamped.batchMinute());
        helper.assertTrue(stamped.stoppedHours() == 0, "新货没停过表");

        // 不是易腐品: 不记。
        BlockPos stone = helper.absolutePos(cake(slot, STONE_REL));
        place(helper, stone, Blocks.STONE, new ItemStack(Items.STONE, 1));
        helper.assertTrue(FreshnessPlacedFood.at(stone).isEmpty(), "石头不记");
        helper.succeed();
    }

    // ============================================================
    // 掉落继承
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void lootInheritsThePlacedRecord(GameTestHelper helper) {
        int slot = 1;
        ServerLevel level = helper.getLevel();
        long nowMinute = FreshnessClockData.nowMinute(level);
        BlockPos pos = helper.absolutePos(cake(slot, BUSH_REL));

        // 甜浆果丛会掉甜浆果 (易腐品): 先按「摆了一天的浆果丛」记一笔。
        helper.setBlock(cake(slot, BUSH_REL), matureBush());
        FreshnessPlacedFood.record(level, pos, new ItemStack(Items.SWEET_BERRIES, 1), matureBush());
        FreshnessPlacedFood.Placed recorded = FreshnessPlacedFood.at(pos).orElseThrow();
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
        helper.assertTrue(FreshnessPlacedFood.at(pos).isEmpty(), "继承完就忘掉 (不重复继承)");
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
                FreshnessPlacedFood.at(pos).filter(placed -> placed.block().equals(
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
        helper.assertTrue(FreshnessPlacedFood.at(pos).isPresent(), "对不上的旧账没被消费掉 (留着, 但也不会被用)");
        FreshnessPlacedFood.resetForTest();
        helper.succeed();
    }

    // ============================================================
    // 区块存读
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void recordsFollowTheChunkData(GameTestHelper helper) {
        int slot = 3;
        ServerLevel level = helper.getLevel();
        long nowMinute = FreshnessClockData.nowMinute(level);
        BlockPos pos = helper.absolutePos(cake(slot, CAKE_REL));
        ChunkPos chunkPos = new ChunkPos(pos);

        ItemStack cake = new ItemStack(Items.CAKE, 1);
        FreshnessData.stampAt(cake, nowMinute - 600L, 3);
        FreshnessPlacedFood.record(level, pos, cake, Blocks.CAKE.defaultBlockState());
        helper.assertTrue(FreshnessPlacedFood.at(pos).isPresent(), "先记上");

        // 写进区块附加数据 → 清空内存 → 读回来 (区块卸载再开就是这个过程)。
        CompoundTag data = new CompoundTag();
        FreshnessPlacedFood.saveTo(chunkPos, data);
        helper.assertTrue(data.contains(FreshnessPlacedFood.KEY_PLACED), "写进了区块附加数据");
        FreshnessPlacedFood.resetForTest();
        helper.assertTrue(FreshnessPlacedFood.at(pos).isEmpty(), "内存清空了");
        FreshnessPlacedFood.loadFrom(chunkPos, data);
        FreshnessPlacedFood.Placed back = FreshnessPlacedFood.at(pos).orElse(null);
        helper.assertTrue(back != null, "读回来了");
        helper.assertTrue(back.batchMinute() == nowMinute - 600L && back.stoppedHours() == 3,
                "批次时刻与停表小时原样: " + back);

        // 区块卸载: 内存那份丢掉 (存盘已经做过)。
        FreshnessPlacedFood.unload(chunkPos);
        helper.assertTrue(FreshnessPlacedFood.at(pos).isEmpty(), "卸载之后内存里不再留着");

        // 空记录不该写键。
        CompoundTag empty = new CompoundTag();
        FreshnessPlacedFood.saveTo(chunkPos, empty);
        helper.assertTrue(!empty.contains(FreshnessPlacedFood.KEY_PLACED), "没有记录就不写这个键");
        helper.succeed();
    }

    // ============================================================
    // 夹具
    // ============================================================

    /** 熟透的甜浆果丛 (age 0 的浆果丛不掉浆果)。 */
    private static BlockState matureBush() {
        return Blocks.SWEET_BERRY_BUSH.defaultBlockState()
                .setValue(net.minecraft.world.level.block.SweetBerryBushBlock.AGE, 3);
    }

    private static BlockPos cake(int slot, BlockPos relative) {
        return new BlockPos(slot * SLOT + relative.getX(), relative.getY(), relative.getZ());
    }

    /** 走真实的放置事件路径: 手上拿着那个物品, 把方块放下去。 */
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
