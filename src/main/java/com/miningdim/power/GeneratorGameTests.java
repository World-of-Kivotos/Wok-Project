package com.miningdim.power;

import com.miningdim.core.MiningConstants;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class GeneratorGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "generator_multiblock";
    private static final BlockPos ANCHOR_REL = new BlockPos(3, 1, 3);

    private GeneratorGameTests() {
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void coordinatesRoundTripForAllFacings(GameTestHelper helper) {
        GeneratorMultiblockBlock block = PowerRegistry.INDUSTRIAL_GENERATOR.get();
        BlockPos anchor = new BlockPos(40, 8, 40);

        for (Direction facing : Direction.Plane.HORIZONTAL) {
            Set<BlockPos> positions = new HashSet<>();
            for (GeneratorMultiblockBlock.Part part : GeneratorMultiblockBlock.Part.values()) {
                BlockPos partPos = GeneratorMultiblockBlock.partPos(anchor, facing, part);
                BlockState state = block.defaultBlockState()
                        .setValue(GeneratorMultiblockBlock.FACING, facing)
                        .setValue(GeneratorMultiblockBlock.PART, part);
                helper.assertTrue(GeneratorMultiblockBlock.anchorPos(partPos, state).equals(anchor),
                        facing + " " + part + " must resolve back to the front-center anchor");
                helper.assertTrue(part.getSerializedName().equals(
                                "x" + part.x() + "_z" + part.z() + "_y" + part.y()),
                        part + " must keep the xN_zN_yN serialized state name");
                positions.add(partPos);
            }
            helper.assertTrue(positions.size() == 12,
                    facing + " must produce twelve unique cells for a 3x2x2 structure");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void rotationAndMirrorPreserveCoordinateContract(GameTestHelper helper) {
        GeneratorMultiblockBlock block = PowerRegistry.INDUSTRIAL_GENERATOR.get();
        BlockPos anchor = BlockPos.ZERO;

        for (Direction facing : Direction.Plane.HORIZONTAL) {
            for (GeneratorMultiblockBlock.Part part : GeneratorMultiblockBlock.Part.values()) {
                BlockPos partPos = GeneratorMultiblockBlock.partPos(anchor, facing, part);
                BlockState state = block.defaultBlockState()
                        .setValue(GeneratorMultiblockBlock.FACING, facing)
                        .setValue(GeneratorMultiblockBlock.PART, part);

                for (Rotation rotation : Rotation.values()) {
                    BlockPos transformedAnchor = StructureTemplate.transform(
                            anchor, Mirror.NONE, rotation, BlockPos.ZERO);
                    BlockPos transformedPart = StructureTemplate.transform(
                            partPos, Mirror.NONE, rotation, BlockPos.ZERO);
                    BlockState transformedState = block.rotate(state, rotation);
                    BlockPos expected = GeneratorMultiblockBlock.partPos(
                            transformedAnchor,
                            transformedState.getValue(GeneratorMultiblockBlock.FACING),
                            transformedState.getValue(GeneratorMultiblockBlock.PART));
                    helper.assertTrue(expected.equals(transformedPart),
                            rotation + " must preserve " + facing + " " + part + " coordinates");
                }

                for (Mirror mirror : Mirror.values()) {
                    BlockPos transformedAnchor = StructureTemplate.transform(
                            anchor, mirror, Rotation.NONE, BlockPos.ZERO);
                    BlockPos transformedPart = StructureTemplate.transform(
                            partPos, mirror, Rotation.NONE, BlockPos.ZERO);
                    BlockState transformedState = block.mirror(state, mirror);
                    BlockPos expected = GeneratorMultiblockBlock.partPos(
                            transformedAnchor,
                            transformedState.getValue(GeneratorMultiblockBlock.FACING),
                            transformedState.getValue(GeneratorMultiblockBlock.PART));
                    helper.assertTrue(expected.equals(transformedPart),
                            mirror + " must preserve " + facing + " " + part + " coordinates");
                }
            }
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void everyGeneratorPartBlocksPistonMovement(GameTestHelper helper) {
        assertPistonBlocked(helper, PowerRegistry.INDUSTRIAL_GENERATOR.get());
        assertPistonBlocked(helper, PowerRegistry.MODERN_GENERATOR.get());
        assertPistonBlocked(helper, PowerRegistry.FUTURE_ENERGY_GENERATOR.get());
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void allThreeBlockItemsPlaceTwelveParts(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        player.setYRot(0.0F);
        movePlayerClearOfFootprint(helper, player);
        clearFootprint(helper, Direction.NORTH);

        placeAndAssert(helper, player, PowerRegistry.INDUSTRIAL_GENERATOR.get(),
                PowerRegistry.INDUSTRIAL_GENERATOR_ITEM.get());
        removeStructureByReplacement(helper, PowerRegistry.INDUSTRIAL_GENERATOR.get());
        placeAndAssert(helper, player, PowerRegistry.MODERN_GENERATOR.get(),
                PowerRegistry.MODERN_GENERATOR_ITEM.get());
        removeStructureByReplacement(helper, PowerRegistry.MODERN_GENERATOR.get());
        placeAndAssert(helper, player, PowerRegistry.FUTURE_ENERGY_GENERATOR.get(),
                PowerRegistry.FUTURE_ENERGY_GENERATOR_ITEM.get());
        removeStructureByReplacement(helper, PowerRegistry.FUTURE_ENERGY_GENERATOR.get());
        helper.setBlock(ANCHOR_REL.below(), Blocks.AIR);

        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void blockedOrOutOfHeightFootprintIsRejectedAtomically(GameTestHelper helper) {
        GeneratorMultiblockBlock block = PowerRegistry.INDUSTRIAL_GENERATOR.get();
        Item item = PowerRegistry.INDUSTRIAL_GENERATOR_ITEM.get();
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        player.setYRot(0.0F);
        movePlayerClearOfFootprint(helper, player);
        clearFootprint(helper, Direction.NORTH);
        BlockPlaceContext context = placementContext(helper, player, item, ANCHOR_REL);
        assertClearFootprintPlaceable(helper, block, context);
        BlockState clearState = block.getStateForPlacement(context);
        helper.assertTrue(clearState != null, "clear 3x2x2 footprint must be placeable");

        Direction facing = clearState.getValue(GeneratorMultiblockBlock.FACING);
        BlockPos blockedPos = GeneratorMultiblockBlock.partPos(
                context.getClickedPos(), facing, GeneratorMultiblockBlock.Part.X2_Z1_Y1);
        helper.getLevel().setBlock(blockedPos, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        helper.assertTrue(block.getStateForPlacement(context) == null,
                "one occupied subordinate cell must reject the entire structure");
        InteractionResult blockedResult = ((BlockItem) item).place(context);
        helper.assertTrue(blockedResult == InteractionResult.FAIL,
                "blocked BlockItem placement must report failure");
        helper.assertTrue(helper.getLevel().getBlockState(context.getClickedPos()).isAir(),
                "failed placement must not leave the anchor behind");
        for (GeneratorMultiblockBlock.Part part : GeneratorMultiblockBlock.Part.values()) {
            BlockPos target = GeneratorMultiblockBlock.partPos(context.getClickedPos(), facing, part);
            if (!target.equals(blockedPos)) {
                helper.assertTrue(helper.getLevel().getBlockState(target).getBlock() != block,
                        "failed placement must not leave part " + part);
            }
        }
        helper.getLevel().setBlock(blockedPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

        BlockPos topAnchor = new BlockPos(
                context.getClickedPos().getX(), helper.getLevel().getMaxBuildHeight() - 1,
                context.getClickedPos().getZ());
        BlockPos topSupport = topAnchor.below();
        helper.getLevel().setBlock(topSupport, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        BlockPlaceContext topContext = placementContextAtSupport(helper, player, item, topSupport);
        helper.assertTrue(block.getStateForPlacement(topContext) == null,
                "upper layer outside build height must reject the entire structure");
        helper.getLevel().setBlock(topSupport, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        helper.setBlock(ANCHOR_REL.below(), Blocks.AIR);
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void playerBreakUsesOneNormalDropAndCreativeUsesNone(GameTestHelper helper) {
        GeneratorMultiblockBlock block = PowerRegistry.INDUSTRIAL_GENERATOR.get();
        Direction facing = Direction.NORTH;

        ServerPlayer survival = MockGameTestPlayers.makeMockSurvivalServerPlayerWithChannel(helper);
        survival.setGameMode(GameType.SURVIVAL);
        survival.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        placeStructure(helper, block, facing);
        BlockPos wrongToolTarget = helper.absolutePos(GeneratorMultiblockBlock.partPos(
                ANCHOR_REL, facing, GeneratorMultiblockBlock.Part.X2_Z1_Y1));
        helper.assertTrue(survival.gameMode.destroyBlock(wrongToolTarget),
                "survival player must be able to break a subordinate part");
        assertStructureRemoved(helper, block, facing);
        helper.assertTrue(countDrops(helper, PowerRegistry.INDUSTRIAL_GENERATOR_ITEM.get()) == 0,
                "wrong tool must preserve the iron-block harvest gate");

        survival.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_PICKAXE));
        for (GeneratorMultiblockBlock.Part brokenPart : GeneratorMultiblockBlock.Part.values()) {
            placeStructure(helper, block, facing);
            BlockPos brokenAbsolute = helper.absolutePos(
                    GeneratorMultiblockBlock.partPos(ANCHOR_REL, facing, brokenPart));
            helper.assertTrue(survival.gameMode.destroyBlock(brokenAbsolute),
                    "correct pickaxe must break " + brokenPart);
            assertStructureRemoved(helper, block, facing);
            helper.assertTrue(countDrops(helper, PowerRegistry.INDUSTRIAL_GENERATOR_ITEM.get()) == 1,
                    brokenPart + " must be the only normal loot source for its teardown");
            discardDrops(helper, PowerRegistry.INDUSTRIAL_GENERATOR_ITEM.get());
        }

        ServerPlayer creative = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        creative.setGameMode(GameType.CREATIVE);
        placeStructure(helper, block, facing);
        BlockPos creativeTarget = helper.absolutePos(GeneratorMultiblockBlock.partPos(
                ANCHOR_REL, facing, GeneratorMultiblockBlock.ANCHOR_PART));
        helper.assertTrue(creative.gameMode.destroyBlock(creativeTarget),
                "creative player must be able to break the anchor part");
        assertStructureRemoved(helper, block, facing);
        helper.assertTrue(countDrops(helper, PowerRegistry.INDUSTRIAL_GENERATOR_ITEM.get()) == 0,
                "creative teardown must create no item drop");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void explodedPartClearsTheRemainingStructure(GameTestHelper helper) {
        GeneratorMultiblockBlock block = PowerRegistry.MODERN_GENERATOR.get();
        Direction facing = Direction.WEST;
        GeneratorMultiblockBlock.Part explodedPart = GeneratorMultiblockBlock.Part.X0_Z1_Y0;
        placeStructure(helper, block, facing);
        BlockPos explodedAbsolute = helper.absolutePos(
                GeneratorMultiblockBlock.partPos(ANCHOR_REL, facing, explodedPart));
        BlockState explodedState = helper.getLevel().getBlockState(explodedAbsolute);
        Explosion explosion = new Explosion(
                helper.getLevel(), null,
                explodedAbsolute.getX() + 0.5D,
                explodedAbsolute.getY() + 0.5D,
                explodedAbsolute.getZ() + 0.5D,
                4.0F, List.of(explodedAbsolute));

        block.onBlockExploded(explodedState, helper.getLevel(), explodedAbsolute, explosion);
        assertStructureRemoved(helper, block, facing);
        helper.succeed();
    }

    /**
     * 玩家放置的真实链路是 ServerPlayerGameMode -> ForgeHooks.onPlaceItemIntoWorld: 那条路径会打开
     * Level.captureBlockSnapshots, setPlacedBy 补的 11 格全部落进快照, 直到 hook 收尾才统一
     * markAndNotifyBlock 广播给客户端。直接调 BlockItem.place 会整段绕开这套机制, 所以真实路径必须
     * 单独守一条: 它一旦漏格, 现场就是"服务端有方块、客户端看不见"的幽灵。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void forgePlacementPathPlacesTwelveParts(GameTestHelper helper) {
        GeneratorMultiblockBlock block = PowerRegistry.MODERN_GENERATOR.get();
        Item item = PowerRegistry.MODERN_GENERATOR_ITEM.get();
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        player.setYRot(0.0F);
        movePlayerClearOfFootprint(helper, player);
        clearFootprint(helper, Direction.NORTH);

        InteractionResult result = ForgeHooks.onPlaceItemIntoWorld(
                useOnContext(helper, player, item, ANCHOR_REL));
        helper.assertTrue(result.consumesAction(),
                "forge placement path must consume the action, got " + result);
        assertTwelveParts(helper, block, Direction.NORTH);

        removeStructureByReplacement(helper, block);
        helper.setBlock(ANCHOR_REL.below(), Blocks.AIR);
        helper.succeed();
    }

    /**
     * 放置事件被取消时 (矿山维度白名单闸就会这么干), Forge 只回滚它捕获到的快照。若 12 格没有被当作
     * 同一次 multi-place 捕获, 回滚就只会撤掉 anchor, 另外 11 格原地留成幽灵。这里同时钉死两件事:
     * 事件确实带着 12 份快照发出, 且取消后一格不剩。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void canceledPlacementEventLeavesNoPartBehind(GameTestHelper helper) {
        GeneratorMultiblockBlock block = PowerRegistry.INDUSTRIAL_GENERATOR.get();
        Item item = PowerRegistry.INDUSTRIAL_GENERATOR_ITEM.get();
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        player.setYRot(0.0F);
        movePlayerClearOfFootprint(helper, player);
        clearFootprint(helper, Direction.NORTH);

        int[] capturedSnapshots = {0};
        Object placementCanceler = new Object() {
            @SubscribeEvent
            public void onMultiPlace(BlockEvent.EntityMultiPlaceEvent event) {
                capturedSnapshots[0] = event.getReplacedBlockSnapshots().size();
                event.setCanceled(true);
            }
        };
        MinecraftForge.EVENT_BUS.register(placementCanceler);
        InteractionResult result;
        try {
            result = ForgeHooks.onPlaceItemIntoWorld(useOnContext(helper, player, item, ANCHOR_REL));
        } finally {
            MinecraftForge.EVENT_BUS.unregister(placementCanceler);
        }

        helper.assertTrue(result == InteractionResult.FAIL,
                "canceled placement must report FAIL, got " + result);
        helper.assertTrue(capturedSnapshots[0] == 12,
                "the whole 3x2x2 must reach the event as one multi-place, got " + capturedSnapshots[0]);
        assertStructureRemoved(helper, block, Direction.NORTH);
        helper.setBlock(ANCHOR_REL.below(), Blocks.AIR);
        helper.succeed();
    }

    /**
     * 孤立的从属格 (指令直接放的、旧存档遗留的、拆除时因状态错位被 clearStructure 漏掉的) 没有方块实体,
     * 只能靠邻居更新触发自检。它看起来只是整机的一块碎片, 却照样占位挡住重新放置。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void orphanPartSelfHealsAfterNeighborUpdate(GameTestHelper helper) {
        GeneratorMultiblockBlock block = PowerRegistry.FUTURE_ENERGY_GENERATOR.get();
        Direction facing = Direction.NORTH;
        helper.setBlock(ANCHOR_REL, block.defaultBlockState()
                .setValue(GeneratorMultiblockBlock.FACING, facing)
                .setValue(GeneratorMultiblockBlock.PART, GeneratorMultiblockBlock.Part.X2_Z1_Y1));
        helper.assertBlockPresent(block, ANCHOR_REL);

        helper.setBlock(ANCHOR_REL.above(), Blocks.STONE);
        helper.runAfterDelay(3L, () -> {
            helper.assertBlock(ANCHOR_REL, candidate -> candidate != block,
                    "orphan subordinate part must clear itself after a neighbour update");
            helper.setBlock(ANCHOR_REL.above(), Blocks.AIR);
            helper.succeed();
        });
    }

    /** 孤立 anchor 有自己的 ticker, 不依赖任何邻居动静也该在一个自检周期内清掉。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH, timeoutTicks = 200)
    public static void orphanAnchorSelfHealsOnItsOwnTicker(GameTestHelper helper) {
        GeneratorMultiblockBlock block = PowerRegistry.INDUSTRIAL_GENERATOR.get();
        helper.setBlock(ANCHOR_REL, block.defaultBlockState()
                .setValue(GeneratorMultiblockBlock.FACING, Direction.NORTH)
                .setValue(GeneratorMultiblockBlock.PART, GeneratorMultiblockBlock.ANCHOR_PART));
        helper.assertBlockPresent(block, ANCHOR_REL);

        helper.runAfterDelay(25L, () -> {
            helper.assertBlock(ANCHOR_REL, candidate -> candidate != block,
                    "orphan anchor must clear itself within one audit interval");
            helper.succeed();
        });
    }

    /**
     * 把一格换成同方块不同 facing 是现场里最阴的一种破损: onRemove 的入口条件是
     * {@code !state.is(newState.getBlock())}, 同方块替换整段跳过, clearStructure 根本不会跑,
     * 于是剩下 11 格全部原地留存。自检必须把这类残缺连同错位格一起清干净。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void rotatedPartBreaksStructureAndIsCleared(GameTestHelper helper) {
        GeneratorMultiblockBlock block = PowerRegistry.MODERN_GENERATOR.get();
        Direction facing = Direction.NORTH;
        placeStructure(helper, block, facing);
        BlockPos rotatedRel = GeneratorMultiblockBlock.partPos(
                ANCHOR_REL, facing, GeneratorMultiblockBlock.Part.X0_Z0_Y1);
        helper.setBlock(rotatedRel, block.defaultBlockState()
                .setValue(GeneratorMultiblockBlock.FACING, facing.getClockWise())
                .setValue(GeneratorMultiblockBlock.PART, GeneratorMultiblockBlock.Part.X0_Z0_Y1));
        helper.assertBlockPresent(block, rotatedRel);

        helper.runAfterDelay(6L, () -> {
            for (GeneratorMultiblockBlock.Part part : GeneratorMultiblockBlock.Part.values()) {
                BlockPos target = GeneratorMultiblockBlock.partPos(ANCHOR_REL, facing, part);
                helper.assertBlock(target,
                        candidate -> !(candidate instanceof GeneratorMultiblockBlock),
                        "broken structure must leave no generator block at " + part);
            }
            helper.succeed();
        });
    }

    /** 放置被拒时必须报出到底是哪一格、被什么挡住, 否则现场只能看到"右键没反应"。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void placementObstructionNamesTheBlockedCell(GameTestHelper helper) {
        GeneratorMultiblockBlock block = PowerRegistry.INDUSTRIAL_GENERATOR.get();
        Item item = PowerRegistry.INDUSTRIAL_GENERATOR_ITEM.get();
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        player.setYRot(0.0F);
        movePlayerClearOfFootprint(helper, player);
        clearFootprint(helper, Direction.NORTH);
        BlockPlaceContext context = placementContext(helper, player, item, ANCHOR_REL);
        Direction facing = Direction.NORTH;
        helper.assertTrue(block.findObstruction(context, facing) == null,
                "a clear footprint must report no obstruction");

        BlockPos blockedPos = GeneratorMultiblockBlock.partPos(
                context.getClickedPos(), facing, GeneratorMultiblockBlock.Part.X2_Z1_Y1);
        helper.getLevel().setBlock(blockedPos, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        assertObstruction(helper, block.findObstruction(context, facing),
                "message.miningdim.power.generator.blocked_by_block", blockedPos);
        helper.getLevel().setBlock(blockedPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

        BlockPos occupiedPos = GeneratorMultiblockBlock.partPos(
                context.getClickedPos(), facing, GeneratorMultiblockBlock.Part.X0_Z0_Y0);
        player.teleportTo(occupiedPos.getX() + 0.5D, occupiedPos.getY(), occupiedPos.getZ() + 0.5D);
        assertObstruction(helper, block.findObstruction(context, facing),
                "message.miningdim.power.generator.blocked_by_entity", occupiedPos);
        movePlayerClearOfFootprint(helper, player);

        BlockPos topSupport = new BlockPos(context.getClickedPos().getX(),
                helper.getLevel().getMaxBuildHeight() - 1, context.getClickedPos().getZ()).below();
        helper.getLevel().setBlock(topSupport, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        BlockPlaceContext topContext = placementContextAtSupport(helper, player, item, topSupport);
        Component outOfBounds = block.findObstruction(topContext, facing);
        helper.assertTrue(outOfBounds != null && translationKeyOf(outOfBounds)
                        .equals("message.miningdim.power.generator.out_of_bounds"),
                "an upper layer above build height must be reported as out of bounds, got " + outOfBounds);
        helper.getLevel().setBlock(topSupport, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

        helper.setBlock(ANCHOR_REL.below(), Blocks.AIR);
        helper.succeed();
    }

    private static void assertObstruction(GameTestHelper helper, Component obstruction,
                                          String expectedKey, BlockPos expectedPos) {
        helper.assertTrue(obstruction != null, "blocked cell " + expectedPos + " must produce a report");
        helper.assertTrue(translationKeyOf(obstruction).equals(expectedKey),
                "expected " + expectedKey + ", got " + translationKeyOf(obstruction));
        Object[] args = ((TranslatableContents) obstruction.getContents()).getArgs();
        helper.assertTrue(args.length >= 3, "the report must carry the blocked coordinates");
        helper.assertTrue(args[0].equals(expectedPos.getX())
                        && args[1].equals(expectedPos.getY())
                        && args[2].equals(expectedPos.getZ()),
                "the report must name " + expectedPos + ", got ("
                        + args[0] + ", " + args[1] + ", " + args[2] + ")");
    }

    private static String translationKeyOf(Component component) {
        return ((TranslatableContents) component.getContents()).getKey();
    }

    private static UseOnContext useOnContext(GameTestHelper helper, ServerPlayer player,
                                             Item item, BlockPos anchorRelative) {
        BlockPos supportRelative = anchorRelative.below();
        helper.setBlock(supportRelative, Blocks.STONE);
        BlockPos supportAbsolute = helper.absolutePos(supportRelative);
        ItemStack stack = new ItemStack(item);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        BlockHitResult hit = new BlockHitResult(
                Vec3.atCenterOf(supportAbsolute).add(0.0D, 0.5D, 0.0D),
                Direction.UP, supportAbsolute, false);
        return new UseOnContext(helper.getLevel(), player, InteractionHand.MAIN_HAND, stack, hit);
    }

    private static void assertTwelveParts(GameTestHelper helper, GeneratorMultiblockBlock block,
                                          Direction facing) {
        for (GeneratorMultiblockBlock.Part part : GeneratorMultiblockBlock.Part.values()) {
            BlockPos target = helper.absolutePos(
                    GeneratorMultiblockBlock.partPos(ANCHOR_REL, facing, part));
            BlockState state = helper.getLevel().getBlockState(target);
            helper.assertTrue(state.getBlock() == block, "real placement path must place " + part);
            helper.assertTrue(state.getValue(GeneratorMultiblockBlock.FACING) == facing,
                    part + " must share the anchor facing");
            helper.assertTrue(state.getValue(GeneratorMultiblockBlock.PART) == part,
                    part + " must keep its exact part state");
        }
    }

    private static void placeAndAssert(GameTestHelper helper, ServerPlayer player,
                                       GeneratorMultiblockBlock block, Item item) {
        BlockPlaceContext context = placementContext(helper, player, item, ANCHOR_REL);
        BlockState placementState = block.getStateForPlacement(context);
        helper.assertTrue(placementState != null, "clear footprint must produce a placement state for " + item);
        Direction facing = placementState.getValue(GeneratorMultiblockBlock.FACING);
        InteractionResult result = ((BlockItem) item).place(context);
        helper.assertTrue(result.consumesAction(), "BlockItem placement must succeed for " + item);

        for (GeneratorMultiblockBlock.Part part : GeneratorMultiblockBlock.Part.values()) {
            BlockPos target = GeneratorMultiblockBlock.partPos(context.getClickedPos(), facing, part);
            BlockState state = helper.getLevel().getBlockState(target);
            helper.assertTrue(state.getBlock() == block, item + " must place " + part);
            helper.assertTrue(state.getValue(GeneratorMultiblockBlock.FACING) == facing,
                    part + " must share the anchor facing");
            helper.assertTrue(state.getValue(GeneratorMultiblockBlock.PART) == part,
                    part + " must keep its exact part state");
        }
    }

    private static void assertPistonBlocked(GameTestHelper helper, GeneratorMultiblockBlock block) {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            for (GeneratorMultiblockBlock.Part part : GeneratorMultiblockBlock.Part.values()) {
                BlockState state = block.defaultBlockState()
                        .setValue(GeneratorMultiblockBlock.FACING, facing)
                        .setValue(GeneratorMultiblockBlock.PART, part);
                helper.assertTrue(state.getPistonPushReaction() == PushReaction.BLOCK,
                        block + " " + facing + " " + part + " must reject piston movement");
            }
        }
    }

    private static BlockPlaceContext placementContext(GameTestHelper helper, ServerPlayer player,
                                                       Item item, BlockPos anchorRelative) {
        BlockPos supportRelative = anchorRelative.below();
        helper.setBlock(supportRelative, Blocks.STONE);
        return placementContextAtSupport(helper, player, item, helper.absolutePos(supportRelative));
    }

    private static void movePlayerClearOfFootprint(GameTestHelper helper, ServerPlayer player) {
        BlockPos clearPosition = helper.absolutePos(ANCHOR_REL.above(4));
        player.teleportTo(
                clearPosition.getX() + 0.5D,
                clearPosition.getY(),
                clearPosition.getZ() + 0.5D);
    }

    private static void clearFootprint(GameTestHelper helper, Direction facing) {
        for (GeneratorMultiblockBlock.Part part : GeneratorMultiblockBlock.Part.values()) {
            helper.setBlock(GeneratorMultiblockBlock.partPos(ANCHOR_REL, facing, part), Blocks.AIR);
        }
    }

    private static void assertClearFootprintPlaceable(GameTestHelper helper,
                                                      GeneratorMultiblockBlock block,
                                                      BlockPlaceContext context) {
        Direction facing = context.getHorizontalDirection().getOpposite();
        CollisionContext collisionContext = CollisionContext.of(context.getPlayer());
        for (GeneratorMultiblockBlock.Part part : GeneratorMultiblockBlock.Part.values()) {
            BlockPos target = GeneratorMultiblockBlock.partPos(context.getClickedPos(), facing, part);
            BlockState targetState = block.defaultBlockState()
                    .setValue(GeneratorMultiblockBlock.FACING, facing)
                    .setValue(GeneratorMultiblockBlock.PART, part);
            helper.assertTrue(context.getLevel().isInWorldBounds(target), part + " must be in world bounds");
            helper.assertTrue(!context.getLevel().isOutsideBuildHeight(target),
                    part + " must be inside build height");
            helper.assertTrue(context.getLevel().getWorldBorder().isWithinBounds(target),
                    part + " must be inside the world border");
            helper.assertTrue(context.getLevel().getBlockState(target).canBeReplaced(context),
                    part + " must target a replaceable cell, found " + context.getLevel().getBlockState(target));
            helper.assertTrue(context.getLevel().isUnobstructed(targetState, target, collisionContext),
                    part + " must not intersect an entity");
        }
    }

    private static BlockPlaceContext placementContextAtSupport(GameTestHelper helper, ServerPlayer player,
                                                                Item item, BlockPos supportAbsolute) {
        ItemStack stack = new ItemStack(item);
        BlockHitResult hit = new BlockHitResult(
                Vec3.atCenterOf(supportAbsolute).add(0.0D, 0.5D, 0.0D),
                Direction.UP, supportAbsolute, false);
        return new BlockPlaceContext(helper.getLevel(), player, InteractionHand.MAIN_HAND, stack, hit);
    }

    private static void removeStructureByReplacement(GameTestHelper helper, GeneratorMultiblockBlock block) {
        BlockPos anchorAbsolute = helper.absolutePos(ANCHOR_REL);
        BlockState anchorState = helper.getLevel().getBlockState(anchorAbsolute);
        Direction facing = anchorState.getValue(GeneratorMultiblockBlock.FACING);
        helper.getLevel().setBlock(anchorAbsolute, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        assertStructureRemoved(helper, block, facing);
    }

    private static void placeStructure(GameTestHelper helper, GeneratorMultiblockBlock block, Direction facing) {
        for (GeneratorMultiblockBlock.Part part : GeneratorMultiblockBlock.Part.values()) {
            BlockState state = block.defaultBlockState()
                    .setValue(GeneratorMultiblockBlock.FACING, facing)
                    .setValue(GeneratorMultiblockBlock.PART, part);
            helper.setBlock(GeneratorMultiblockBlock.partPos(ANCHOR_REL, facing, part), state);
        }
    }

    private static void assertStructureRemoved(GameTestHelper helper, GeneratorMultiblockBlock block,
                                               Direction facing) {
        for (GeneratorMultiblockBlock.Part part : GeneratorMultiblockBlock.Part.values()) {
            BlockPos target = helper.absolutePos(
                    GeneratorMultiblockBlock.partPos(ANCHOR_REL, facing, part));
            helper.assertTrue(helper.getLevel().getBlockState(target).getBlock() != block,
                    "teardown must remove " + part);
        }
    }

    private static int countDrops(GameTestHelper helper, Item item) {
        AABB area = new AABB(helper.absolutePos(ANCHOR_REL)).inflate(6.0D);
        return helper.getLevel().getEntitiesOfClass(ItemEntity.class, area).stream()
                .map(ItemEntity::getItem)
                .filter(stack -> stack.is(item))
                .mapToInt(ItemStack::getCount)
                .sum();
    }

    private static void discardDrops(GameTestHelper helper, Item item) {
        AABB area = new AABB(helper.absolutePos(ANCHOR_REL)).inflate(6.0D);
        helper.getLevel().getEntitiesOfClass(ItemEntity.class, area).stream()
                .filter(entity -> entity.getItem().is(item))
                .forEach(ItemEntity::discard);
    }
}
