package com.miningdim.district.guard;

import com.miningdim.core.MiningConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * 地块边界守卫 (设计文档 22.9、22.14), 原版的 5 个 mixin 在开发运行时里真的应用, 行为真跑: 活塞、流体、发射器与投掷器、
 * 下落的方块、海绵、岩浆点火。区域由 {@link GuardTestZones} 按每条用例自己的结构 (16 × 5 × 16 的空结构 district_guard)
 * 装上; 用例之间互不干扰, batch 结束卸下。坐标都是结构里的相对坐标, y = 1 铺一层石头, 机关在 y = 2。
 *
 * <p>另一个 batch ({@code district_world_guards_off}) 用 crossPlot = false 的设置核对急停开关。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class WorldGuardGameTests {

    public static final String TEMPLATE = "district_guard";
    private static final String BATCH = "district_world_guards";
    private static final String BATCH_OFF = "district_world_guards_off";
    private static final int Y = 2;

    private WorldGuardGameTests() {
    }

    @BeforeBatch(batch = BATCH)
    public static void beforeBatch(ServerLevel level) {
        GuardTestZones.begin(GuardSettings.defaults());
    }

    @AfterBatch(batch = BATCH)
    public static void afterBatch(ServerLevel level) {
        GuardTestZones.end();
    }

    @BeforeBatch(batch = BATCH_OFF)
    public static void beforeOffBatch(ServerLevel level) {
        GuardTestZones.begin(GuardSettings.defaults().withCrossPlot(false));
    }

    @AfterBatch(batch = BATCH_OFF)
    public static void afterOffBatch(ServerLevel level) {
        GuardTestZones.end();
    }

    // ================================================================
    // 活塞
    // ================================================================

    /** 活塞在地块 A, 要推的方块在 A 的边上、终点在公共区域: 通电后方块不动, 活塞不伸出。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = TEMPLATE, batch = BATCH)
    public static void pistonCannotPushAcrossPlotBoundary(GameTestHelper helper) {
        floor(helper);
        GuardTestZones.put(helper, "pistonAcross", 0, 0, 15, 15, new int[]{0, 0, 7, 15});
        helper.setBlock(new BlockPos(6, Y, 8), piston(Blocks.PISTON, Direction.EAST));
        helper.setBlock(new BlockPos(7, Y, 8), Blocks.STONE);
        helper.setBlock(new BlockPos(5, Y, 8), Blocks.REDSTONE_BLOCK);
        helper.startSequence().thenExecuteAfter(6, () -> {
            helper.assertBlockPresent(Blocks.STONE, 7, Y, 8);
            helper.assertBlockPresent(Blocks.AIR, 8, Y, 8);
            assertRetracted(helper, new BlockPos(6, Y, 8), "推不过地块边界的活塞不伸出");
        }).thenSucceed();
    }

    /** 区外的活塞往区里推: 不动。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = TEMPLATE, batch = BATCH)
    public static void pistonCannotPushIntoTheDistrictFromOutside(GameTestHelper helper) {
        floor(helper);
        GuardTestZones.put(helper, "pistonInto", 8, 0, 15, 15);
        helper.setBlock(new BlockPos(6, Y, 8), piston(Blocks.PISTON, Direction.EAST));
        helper.setBlock(new BlockPos(7, Y, 8), Blocks.STONE);
        helper.setBlock(new BlockPos(5, Y, 8), Blocks.REDSTONE_BLOCK);
        helper.startSequence().thenExecuteAfter(6, () -> {
            helper.assertBlockPresent(Blocks.STONE, 7, Y, 8);
            helper.assertBlockPresent(Blocks.AIR, 8, Y, 8);
            assertRetracted(helper, new BlockPos(6, Y, 8), "野外的活塞不能把方块推进区里");
        }).thenSucceed();
    }

    /**
     * 在同一区域里伸出, 再把被粘住的那一格划进另一块地、断电: 活塞缩回, 方块留在原地 (服务端状态)。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = TEMPLATE, batch = BATCH)
    public static void stickyPistonDoesNotPullAcrossBoundary(GameTestHelper helper) {
        floor(helper);
        String key = "stickyPull";
        GuardTestZones.put(helper, key, 0, 0, 15, 15, new int[]{0, 0, 15, 15});
        helper.setBlock(new BlockPos(5, Y, 8), piston(Blocks.STICKY_PISTON, Direction.EAST));
        helper.setBlock(new BlockPos(6, Y, 8), Blocks.STONE);
        helper.setBlock(new BlockPos(4, Y, 8), Blocks.REDSTONE_BLOCK);
        helper.startSequence().thenExecuteAfter(6, () -> {
            helper.assertBlockPresent(Blocks.STONE, 7, Y, 8);
            assertExtended(helper, new BlockPos(5, Y, 8), "前提: 同一区域里照常伸出");
            GuardTestZones.put(helper, key, 0, 0, 15, 15, new int[]{0, 0, 6, 15}, new int[]{7, 0, 15, 15});
            helper.setBlock(new BlockPos(4, Y, 8), Blocks.AIR);
        }).thenExecuteAfter(6, () -> {
            helper.assertBlockPresent(Blocks.STONE, 7, Y, 8);
            helper.assertBlockPresent(Blocks.AIR, 6, Y, 8);
            assertRetracted(helper, new BlockPos(5, Y, 8), "活塞照常缩回, 只是不拉");
        }).thenSucceed();
    }

    /** 活塞面对边界, 前方是空气: 活塞头不能进别的区域, 不伸出。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = TEMPLATE, batch = BATCH)
    public static void pistonHeadMayNotEnterAnotherZone(GameTestHelper helper) {
        floor(helper);
        GuardTestZones.put(helper, "pistonHead", 0, 0, 15, 15, new int[]{0, 0, 6, 15});
        helper.setBlock(new BlockPos(6, Y, 8), piston(Blocks.PISTON, Direction.EAST));
        helper.setBlock(new BlockPos(5, Y, 8), Blocks.REDSTONE_BLOCK);
        helper.startSequence().thenExecuteAfter(6, () -> {
            helper.assertBlockPresent(Blocks.AIR, 7, Y, 8);
            assertRetracted(helper, new BlockPos(6, Y, 8), "活塞头不能伸进公共区域");
        }).thenSucceed();
    }

    /** 推动路径的尽头在另一区域有火把 (推动反应"破坏"): 火把还在, 活塞不伸出。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = TEMPLATE, batch = BATCH)
    public static void pistonDoesNotCrushAcrossBoundary(GameTestHelper helper) {
        floor(helper);
        GuardTestZones.put(helper, "pistonCrush", 0, 0, 15, 15, new int[]{0, 0, 5, 15}, new int[]{6, 0, 15, 15});
        helper.setBlock(new BlockPos(4, Y, 8), piston(Blocks.PISTON, Direction.EAST));
        helper.setBlock(new BlockPos(5, Y, 8), Blocks.STONE);
        helper.setBlock(new BlockPos(6, Y, 8), Blocks.TORCH);
        helper.setBlock(new BlockPos(3, Y, 8), Blocks.REDSTONE_BLOCK);
        helper.startSequence().thenExecuteAfter(6, () -> {
            helper.assertBlockPresent(Blocks.TORCH, 6, Y, 8);
            helper.assertBlockPresent(Blocks.STONE, 5, Y, 8);
            assertRetracted(helper, new BlockPos(4, Y, 8), "挤碎别的区域的方块也不行");
        }).thenSucceed();
    }

    /** 同一块地里照常推、拉。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = TEMPLATE, batch = BATCH)
    public static void pistonWithinOneZoneStillWorks(GameTestHelper helper) {
        floor(helper);
        GuardTestZones.put(helper, "pistonInside", 0, 0, 15, 15, new int[]{0, 0, 15, 15});
        helper.setBlock(new BlockPos(4, Y, 8), piston(Blocks.STICKY_PISTON, Direction.EAST));
        helper.setBlock(new BlockPos(5, Y, 8), Blocks.STONE);
        helper.setBlock(new BlockPos(3, Y, 8), Blocks.REDSTONE_BLOCK);
        helper.startSequence().thenExecuteAfter(6, () -> {
            helper.assertBlockPresent(Blocks.STONE, 6, Y, 8);
            assertExtended(helper, new BlockPos(4, Y, 8), "同一块地里照常推");
            helper.setBlock(new BlockPos(3, Y, 8), Blocks.AIR);
        }).thenExecuteAfter(6, () -> {
            helper.assertBlockPresent(Blocks.STONE, 5, Y, 8);
            assertRetracted(helper, new BlockPos(4, Y, 8), "同一块地里照常拉回");
        }).thenSucceed();
    }

    // ================================================================
    // 流体
    // ================================================================

    /**
     * 水源在地块 A 的石柱上、离 A 的边 2 格: 40 tick 后 A 里往下流、铺开到 A 的边, 边界另一侧 (公共区域) 没有水。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = TEMPLATE, batch = BATCH, timeoutTicks = 100)
    public static void waterStopsAtPlotBoundaryButSpreadsAndFallsInside(GameTestHelper helper) {
        floor(helper);
        enclose(helper);
        GuardTestZones.put(helper, "waterPlot", 0, 0, 15, 15, new int[]{0, 0, 10, 15});
        helper.setBlock(new BlockPos(8, Y, 8), Blocks.STONE);
        helper.setBlock(new BlockPos(8, Y + 1, 8), Blocks.WATER);
        helper.startSequence().thenWaitUntil(() -> {
            assertFluid(helper, new BlockPos(9, Y, 8), true, FluidKind.WATER, "水往下流 (竖直方向不拦)");
            assertFluid(helper, new BlockPos(10, Y, 8), true, FluidKind.WATER,
                    "A 里铺开到边 " + describe(helper, new BlockPos(9, Y, 8), new BlockPos(10, Y, 8)));
        }).thenExecuteAfter(30, () -> {
            String cells = describe(helper, new BlockPos(10, Y, 8), new BlockPos(11, Y, 8));
            for (BlockPos pos : List.of(new BlockPos(11, Y, 8), new BlockPos(11, Y + 1, 8), new BlockPos(11, Y, 5),
                    new BlockPos(11, Y, 11), new BlockPos(12, Y, 8))) {
                assertFluid(helper, pos, false, FluidKind.WATER, "水不流过地块边界 " + cells);
            }
        }).thenSucceed();
    }

    /** 排障用: 几格的区域号与流体状态。 */
    private static String describe(GameTestHelper helper, BlockPos... positions) {
        StringBuilder text = new StringBuilder("[");
        for (BlockPos pos : positions) {
            BlockPos at = helper.absolutePos(pos);
            text.append(pos.toShortString()).append(" zone=")
                    .append(Integer.toHexString(DistrictWorldGuards.view().zones().zoneAt(helper.getLevel(), at.getX(),
                            at.getZ())))
                    .append(" block=").append(helper.getLevel().getBlockState(at))
                    .append(" fluid=").append(helper.getLevel().getFluidState(at)).append("; ");
        }
        return text.append("]").toString();
    }

    /** 同上换成岩浆 (主世界 30 tick 一格)。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = TEMPLATE, batch = BATCH, timeoutTicks = 200)
    public static void lavaStopsAtPlotBoundary(GameTestHelper helper) {
        floor(helper);
        enclose(helper);
        GuardTestZones.put(helper, "lavaPlot", 0, 0, 15, 15, new int[]{0, 0, 9, 15});
        helper.setBlock(new BlockPos(8, Y, 8), Blocks.LAVA);
        helper.startSequence().thenWaitUntil(() -> {
            assertFluid(helper, new BlockPos(9, Y, 8), true, FluidKind.LAVA, "岩浆在 A 里照常流");
            assertFluid(helper, new BlockPos(7, Y, 8), true, FluidKind.LAVA, "岩浆在 A 里照常流");
        }).thenExecuteAfter(70, () -> {
            String cells = describe(helper, new BlockPos(8, Y, 8), new BlockPos(9, Y, 8), new BlockPos(10, Y, 8),
                    new BlockPos(10, Y, 7), new BlockPos(10, Y, 9));
            for (BlockPos pos : List.of(new BlockPos(10, Y, 8), new BlockPos(10, Y, 7), new BlockPos(10, Y, 9),
                    new BlockPos(11, Y, 8))) {
                assertFluid(helper, pos, false, FluidKind.LAVA, "岩浆不流过地块边界 " + cells);
            }
        }).thenSucceed();
    }

    /** 水源在公共区域、贴着区边: 流到区外 (外沿不归本章, 交给 Flan 的"水和岩浆越界"开关)。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = TEMPLATE, batch = BATCH, timeoutTicks = 100)
    public static void waterCrossesTheDistrictOuterEdge(GameTestHelper helper) {
        floor(helper);
        enclose(helper);
        GuardTestZones.put(helper, "waterEdge", 0, 0, 9, 15);
        helper.setBlock(new BlockPos(8, Y, 8), Blocks.STONE);
        helper.setBlock(new BlockPos(8, Y + 1, 8), Blocks.WATER);
        helper.startSequence().thenWaitUntil(() ->
                assertFluid(helper, new BlockPos(11, Y, 8), true, FluidKind.WATER, "区的外沿不按地块边界拦"))
                .thenSucceed();
    }

    // ================================================================
    // 发射器与投掷器
    // ================================================================

    /** 发射器在 A、朝向公共区域、装着水桶, 通电: 前方仍是空气, 水桶还在发射器里。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = TEMPLATE, batch = BATCH)
    public static void dispenserWillNotEmptyABucketIntoAnotherZone(GameTestHelper helper) {
        floor(helper);
        GuardTestZones.put(helper, "dispenserBucket", 0, 0, 15, 15, new int[]{0, 0, 7, 15});
        BlockPos dispenser = new BlockPos(7, Y, 8);
        helper.setBlock(dispenser, Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING,
                Direction.EAST));
        ((DispenserBlockEntity) helper.getBlockEntity(dispenser)).setItem(0, new ItemStack(Items.WATER_BUCKET));
        helper.setBlock(new BlockPos(6, Y, 8), Blocks.REDSTONE_BLOCK);
        helper.startSequence().thenExecuteAfter(10, () -> {
            assertFluid(helper, new BlockPos(8, Y, 8), false, FluidKind.WATER, "水桶倒不进别的区域");
            ItemStack left = ((DispenserBlockEntity) helper.getBlockEntity(dispenser)).getItem(0);
            helper.assertTrue(left.is(Items.WATER_BUCKET), "水桶还在发射器里, 实为 " + left);
        }).thenSucceed();
    }

    /** 投掷器在 A, 前方是另一块地里的箱子: 箱子是空的, 物品还在投掷器里。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = TEMPLATE, batch = BATCH)
    public static void dropperWillNotInsertIntoAnotherZone(GameTestHelper helper) {
        floor(helper);
        GuardTestZones.put(helper, "dropperChest", 0, 0, 15, 15, new int[]{0, 0, 7, 15}, new int[]{8, 0, 15, 15});
        BlockPos dropper = new BlockPos(7, Y, 8);
        BlockPos chest = new BlockPos(8, Y, 8);
        helper.setBlock(chest, Blocks.CHEST);
        helper.setBlock(dropper, Blocks.DROPPER.defaultBlockState().setValue(DispenserBlock.FACING, Direction.EAST));
        ((DispenserBlockEntity) helper.getBlockEntity(dropper)).setItem(0, new ItemStack(Items.COBBLESTONE));
        helper.setBlock(new BlockPos(6, Y, 8), Blocks.REDSTONE_BLOCK);
        helper.startSequence().thenExecuteAfter(10, () -> {
            helper.assertTrue(((ChestBlockEntity) helper.getBlockEntity(chest)).isEmpty(),
                    "投掷器塞不进别的地块的箱子");
            ItemStack left = ((DispenserBlockEntity) helper.getBlockEntity(dropper)).getItem(0);
            helper.assertTrue(left.is(Items.COBBLESTONE) && left.getCount() == 1, "物品还在投掷器里, 实为 " + left);
        }).thenSucceed();
    }

    /** 单向口径: 从区里朝区外 (野外) 发射照常。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = TEMPLATE, batch = BATCH)
    public static void dispenserFromDistrictIntoWildStillWorks(GameTestHelper helper) {
        floor(helper);
        enclose(helper);
        GuardTestZones.put(helper, "dispenserWild", 0, 0, 7, 15);
        BlockPos dispenser = new BlockPos(7, Y, 8);
        helper.setBlock(dispenser, Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING,
                Direction.EAST));
        ((DispenserBlockEntity) helper.getBlockEntity(dispenser)).setItem(0, new ItemStack(Items.WATER_BUCKET));
        helper.setBlock(new BlockPos(6, Y, 8), Blocks.REDSTONE_BLOCK);
        helper.startSequence().thenWaitUntil(() -> {
            assertFluid(helper, new BlockPos(8, Y, 8), true, FluidKind.WATER, "从区里朝野外倒水照常");
            ItemStack left = ((DispenserBlockEntity) helper.getBlockEntity(dispenser)).getItem(0);
            helper.assertTrue(left.is(Items.BUCKET), "倒完剩空桶, 实为 " + left);
        }).thenSucceed();
    }

    // ================================================================
    // 下落的方块、海绵、岩浆点火
    // ================================================================

    /** 在 A 上空生成一个带横向速度的沙子实体: 进了 B 的那一列之后消失, B 里没有沙子方块, 掉出一个沙子物品。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = TEMPLATE, batch = BATCH)
    public static void fallingBlockCrossingIntoAnotherZoneDrops(GameTestHelper helper) {
        floor(helper);
        GuardTestZones.put(helper, "fallingSand", 0, 0, 15, 15, new int[]{0, 0, 7, 15}, new int[]{8, 0, 15, 15});
        FallingBlockEntity sand = FallingBlockEntity.fall(helper.getLevel(), helper.absolutePos(new BlockPos(4, 5, 8)),
                Blocks.SAND.defaultBlockState());
        sand.setDeltaMovement(0.5, 0.2, 0.0);
        AABB area = new AABB(helper.absolutePos(new BlockPos(-2, 0, -2)), helper.absolutePos(new BlockPos(18, 8, 18)));
        discardStaleSandDrops(helper, area);
        helper.succeedWhen(() -> {
            helper.assertTrue(helper.getLevel().getEntitiesOfClass(FallingBlockEntity.class, area).isEmpty(),
                    "下落的方块已经不在了");
            for (int x = 8; x <= 15; x++) {
                for (int y = Y; y <= 6; y++) {
                    helper.assertTrue(!helper.getBlockState(new BlockPos(x, y, 8)).is(Blocks.SAND),
                            "B 里没有沙子方块");
                }
            }
            List<ItemEntity> drops = helper.getLevel().getEntitiesOfClass(ItemEntity.class, area,
                    item -> item.getItem().is(Items.SAND));
            helper.assertTrue(drops.size() == 1, "掉出一个沙子物品, 实为 " + drops.size());
        });
    }

    /**
     * disableDrop() 过的下落方块 (原版的可疑沙子就是这样下落的) 横移进 B: 照样消失、B 里没有方块, 但不掉物品 ——
     * 原版落地时对它只碎不掉, 守卫不能凭空多给一个。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = TEMPLATE, batch = BATCH)
    public static void fallingBlockWithDropDisabledCrossingIntoAnotherZoneLeavesNoItem(GameTestHelper helper) {
        floor(helper);
        GuardTestZones.put(helper, "fallingSandNoDrop", 0, 0, 15, 15, new int[]{0, 0, 7, 15},
                new int[]{8, 0, 15, 15});
        FallingBlockEntity sand = FallingBlockEntity.fall(helper.getLevel(), helper.absolutePos(new BlockPos(4, 5, 8)),
                Blocks.SAND.defaultBlockState());
        sand.setDeltaMovement(0.5, 0.2, 0.0);
        sand.disableDrop();
        AABB area = new AABB(helper.absolutePos(new BlockPos(-2, 0, -2)), helper.absolutePos(new BlockPos(18, 8, 18)));
        discardStaleSandDrops(helper, area);
        helper.succeedWhen(() -> {
            helper.assertTrue(helper.getLevel().getEntitiesOfClass(FallingBlockEntity.class, area).isEmpty(),
                    "下落的方块已经不在了");
            for (int x = 8; x <= 15; x++) {
                for (int y = Y; y <= 6; y++) {
                    helper.assertTrue(!helper.getBlockState(new BlockPos(x, y, 8)).is(Blocks.SAND),
                            "B 里没有沙子方块");
                }
            }
            List<ItemEntity> drops = helper.getLevel().getEntitiesOfClass(ItemEntity.class, area,
                    item -> item.getItem().is(Items.SAND));
            helper.assertTrue(drops.isEmpty(), "不掉物品, 实为 " + drops.size());
        });
    }

    /**
     * 开跑前清掉取样盒里的沙子掉落物。run/world 跨轮复用, 同一条用例每轮落在同一组坐标上: 上一轮掉的沙子还躺在原地,
     * "恰好掉出一个"会被它顶成两个。取样盒比结构每边只多 2 格, 结构之间隔 5 格以上, 探不到相邻用例。
     */
    private static void discardStaleSandDrops(GameTestHelper helper, AABB area) {
        helper.getLevel().getEntitiesOfClass(ItemEntity.class, area, item -> item.getItem().is(Items.SAND))
                .forEach(ItemEntity::discard);
    }

    /**
     * 地面上的沙子带着很大的横向速度 (相当于活塞把它推过边界、TNT 大炮打到边界那一格): 同一个 tick 里先越过 A/B 边界、
     * 再落地。判定在 move 之后, 所以照样掉成物品, B 里没有沙子方块 (22.19; 原来在 tick 的 HEAD 判定时会落成方块)。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = TEMPLATE, batch = BATCH)
    public static void fallingBlockLandingAcrossTheBoundaryInOneTickDrops(GameTestHelper helper) {
        floor(helper);
        GuardTestZones.put(helper, "fallingSandOneTick", 0, 0, 15, 15, new int[]{0, 0, 7, 15},
                new int[]{8, 0, 15, 15});
        FallingBlockEntity sand = FallingBlockEntity.fall(helper.getLevel(), helper.absolutePos(new BlockPos(6, Y, 8)),
                Blocks.SAND.defaultBlockState());
        sand.setDeltaMovement(2.0, 0.0, 0.0);
        AABB area = new AABB(helper.absolutePos(new BlockPos(-2, 0, -2)), helper.absolutePos(new BlockPos(18, 8, 18)));
        discardStaleSandDrops(helper, area);
        helper.succeedWhen(() -> {
            helper.assertTrue(helper.getLevel().getEntitiesOfClass(FallingBlockEntity.class, area).isEmpty(),
                    "下落的方块已经不在了");
            for (int x = 6; x <= 12; x++) {
                helper.assertTrue(!helper.getBlockState(new BlockPos(x, Y, 8)).is(Blocks.SAND),
                        "地面上哪里都没有沙子方块 (x = " + x + ")");
            }
            List<ItemEntity> drops = helper.getLevel().getEntitiesOfClass(ItemEntity.class, area,
                    item -> item.getItem().is(Items.SAND));
            helper.assertTrue(drops.size() == 1, "掉出一个沙子物品, 实为 " + drops.size());
        });
    }

    /** 下落的方块的起点随实体存盘: 存一次再读回来, 起点还在 (区块卸下再加载不会把起点换成当时的位置, 22.19)。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = TEMPLATE, batch = BATCH)
    public static void fallingBlockStartSurvivesSaveAndLoad(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos start = helper.absolutePos(new BlockPos(4, 6, 8));
        FallingBlockEntity sand = FallingBlockEntity.fall(level, start, Blocks.SAND.defaultBlockState());
        sand.setNoGravity(true);
        helper.runAfterDelay(2, () -> {
            CompoundTag saved = sand.saveWithoutId(new CompoundTag());
            helper.assertTrue(saved.contains(DistrictWorldGuards.FALLING_START_KEY, Tag.TAG_INT_ARRAY),
                    "存盘带着起点, 实为 " + saved.getAllKeys());
            int[] recorded = saved.getIntArray(DistrictWorldGuards.FALLING_START_KEY);
            helper.assertTrue(recorded.length == 2 && recorded[0] == start.getX() && recorded[1] == start.getZ(),
                    "起点是第一次被看到时的列");
            FallingBlockEntity loaded = new FallingBlockEntity(EntityType.FALLING_BLOCK,
                    level);
            CompoundTag moved = saved.copy();
            loaded.load(moved);
            CompoundTag again = loaded.saveWithoutId(new CompoundTag());
            int[] reloaded = again.getIntArray(DistrictWorldGuards.FALLING_START_KEY);
            helper.assertTrue(reloaded.length == 2 && reloaded[0] == start.getX() && reloaded[1] == start.getZ(),
                    "读回来的实体仍记着原来的起点");
            sand.discard();
            loaded.discard();
            helper.succeed();
        });
    }

    /** 边界两侧都有水, 海绵放在 A: A 的水没了, B 的水还在, 之后也不流回 A。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = TEMPLATE, batch = BATCH)
    public static void spongeDoesNotDrainANeighboursWater(GameTestHelper helper) {
        floor(helper);
        GuardTestZones.put(helper, "sponge", 0, 0, 15, 15, new int[]{0, 0, 7, 15}, new int[]{8, 0, 15, 15});
        for (int x = 3; x <= 12; x++) {
            helper.setBlock(new BlockPos(x, Y, 7), Blocks.STONE);
            helper.setBlock(new BlockPos(x, Y, 9), Blocks.STONE);
        }
        helper.setBlock(new BlockPos(3, Y, 8), Blocks.STONE);
        helper.setBlock(new BlockPos(12, Y, 8), Blocks.STONE);
        for (int x = 4; x <= 11; x++) {
            helper.setBlock(new BlockPos(x, Y, 8), Blocks.WATER);
        }
        helper.setBlock(new BlockPos(3, Y, 8), Blocks.SPONGE);
        helper.startSequence().thenExecuteAfter(1, () -> {
            helper.assertBlockPresent(Blocks.WET_SPONGE, 3, Y, 8);
            for (int x = 4; x <= 7; x++) {
                assertFluid(helper, new BlockPos(x, Y, 8), false, FluidKind.WATER, "A 的水被吸干");
            }
            for (int x = 8; x <= 11; x++) {
                assertFluid(helper, new BlockPos(x, Y, 8), true, FluidKind.WATER, "B 的水还在");
            }
        }).thenExecuteAfter(15, () ->
                assertFluid(helper, new BlockPos(7, Y, 8), false, FluidKind.WATER, "B 的水也不流回 A"))
                .thenSucceed();
    }

    /**
     * 岩浆点火 (FluidPlaceBlockEvent 不看取消): 直接调 ForgeEventFactory.fireFluidPlaceBlockEvent, 落点在另一区域时返回原
     * 状态, 同一区域时返回火 (随机刻没法测, 所以直接发事件)。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = TEMPLATE, batch = BATCH)
    public static void lavaFireEventRevertsAcrossBoundary(GameTestHelper helper) {
        floor(helper);
        GuardTestZones.put(helper, "lavaFire", 0, 0, 15, 15, new int[]{0, 0, 7, 15}, new int[]{8, 0, 15, 15});
        ServerLevel level = helper.getLevel();
        BlockPos liquid = helper.absolutePos(new BlockPos(6, Y, 8));
        BlockPos across = helper.absolutePos(new BlockPos(9, Y, 8));
        BlockPos same = helper.absolutePos(new BlockPos(6, Y, 10));
        BlockState fire = Blocks.FIRE.defaultBlockState();
        helper.assertTrue(ForgeEventFactory.fireFluidPlaceBlockEvent(level, across, liquid, fire)
                .equals(level.getBlockState(across)), "越过地块边界点火: 改回原状态");
        helper.assertTrue(ForgeEventFactory.fireFluidPlaceBlockEvent(level, same, liquid, fire).is(Blocks.FIRE),
                "同一块地里点火照常");
        helper.assertTrue(ForgeEventFactory.fireFluidPlaceBlockEvent(level, liquid, liquid,
                Blocks.STONE.defaultBlockState()).is(Blocks.STONE), "岩浆自己那一格变石头不受影响");
        helper.succeed();
    }

    // ================================================================
    // 没有区域、急停开关
    // ================================================================

    /** 同样的活塞、水、投掷器摆在没有区域的地方: 全部照常。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = TEMPLATE, batch = BATCH, timeoutTicks = 100)
    public static void guardsIdleWithoutZones(GameTestHelper helper) {
        floor(helper);
        enclose(helper);
        crossBoundaryContraptions(helper);
        helper.startSequence().thenWaitUntil(() -> assertCrossBoundaryContraptionsWorked(helper,
                "没有区域时")).thenSucceed();
    }

    /** crossPlot = false 的测试设置: 越界全部放行。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = TEMPLATE, batch = BATCH_OFF, timeoutTicks = 100)
    public static void crossPlotSwitchOffDisablesAll(GameTestHelper helper) {
        floor(helper);
        enclose(helper);
        GuardTestZones.put(helper, "switchOff", 0, 0, 15, 15, new int[]{0, 0, 7, 15}, new int[]{8, 0, 15, 15});
        helper.assertTrue(!GuardTestZones.settings().crossPlot(), "前提: 这个 batch 的地块边界开关是关的");
        crossBoundaryContraptions(helper);
        helper.startSequence().thenWaitUntil(() -> assertCrossBoundaryContraptionsWorked(helper,
                "开关关着时")).thenSucceed();
    }

    /** 跨 x = 7 | 8 的一组机关: 活塞推、投掷器塞箱子、水流。 */
    private static void crossBoundaryContraptions(GameTestHelper helper) {
        helper.setBlock(new BlockPos(6, Y, 3), piston(Blocks.PISTON, Direction.EAST));
        helper.setBlock(new BlockPos(7, Y, 3), Blocks.STONE);
        helper.setBlock(new BlockPos(5, Y, 3), Blocks.REDSTONE_BLOCK);
        BlockPos dropper = new BlockPos(7, Y, 12);
        helper.setBlock(new BlockPos(8, Y, 12), Blocks.CHEST);
        helper.setBlock(dropper, Blocks.DROPPER.defaultBlockState().setValue(DispenserBlock.FACING, Direction.EAST));
        ((DispenserBlockEntity) helper.getBlockEntity(dropper)).setItem(0, new ItemStack(Items.COBBLESTONE));
        helper.setBlock(new BlockPos(6, Y, 12), Blocks.REDSTONE_BLOCK);
        helper.setBlock(new BlockPos(6, Y, 7), Blocks.WATER);
    }

    private static void assertCrossBoundaryContraptionsWorked(GameTestHelper helper, String when) {
        helper.assertBlockPresent(Blocks.STONE, 8, Y, 3);
        helper.assertTrue(!((ChestBlockEntity) helper.getBlockEntity(new BlockPos(8, Y, 12))).isEmpty(),
                when + "投掷器照常塞进箱子");
        assertFluid(helper, new BlockPos(9, Y, 7), true, FluidKind.WATER, when + "水照常流过 x = 7 | 8");
    }

    // ================================================================
    // 工具
    // ================================================================

    private enum FluidKind {
        WATER,
        LAVA
    }

    /** y = 1 整层铺石头。 */
    static void floor(GameTestHelper helper) {
        for (int x = 0; x <= 15; x++) {
            for (int z = 0; z <= 15; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
    }

    /** y = 2..3 沿结构四边围一圈石墙, 流体不出结构。 */
    private static void enclose(GameTestHelper helper) {
        for (int i = 0; i <= 15; i++) {
            for (int y = Y; y <= Y + 1; y++) {
                helper.setBlock(new BlockPos(i, y, 0), Blocks.STONE);
                helper.setBlock(new BlockPos(i, y, 15), Blocks.STONE);
                helper.setBlock(new BlockPos(0, y, i), Blocks.STONE);
                helper.setBlock(new BlockPos(15, y, i), Blocks.STONE);
            }
        }
    }

    private static BlockState piston(Block block, Direction facing) {
        return block.defaultBlockState().setValue(PistonBaseBlock.FACING, facing);
    }

    private static void assertExtended(GameTestHelper helper, BlockPos pos, String message) {
        helper.assertBlockState(pos, state -> state.hasProperty(PistonBaseBlock.EXTENDED)
                && state.getValue(PistonBaseBlock.EXTENDED), () -> message + ": 活塞应伸出");
    }

    private static void assertRetracted(GameTestHelper helper, BlockPos pos, String message) {
        helper.assertBlockState(pos, state -> state.hasProperty(PistonBaseBlock.EXTENDED)
                && !state.getValue(PistonBaseBlock.EXTENDED), () -> message + ": 活塞应缩着");
    }

    private static void assertFluid(GameTestHelper helper, BlockPos pos, boolean present, FluidKind kind,
                                    String message) {
        boolean found = helper.getLevel().getFluidState(helper.absolutePos(pos))
                .is(kind == FluidKind.WATER ? FluidTags.WATER : FluidTags.LAVA);
        helper.assertTrue(found == present, message + " (" + pos.toShortString() + " 应" + (present ? "有" : "没有")
                + kind + ")");
    }
}
