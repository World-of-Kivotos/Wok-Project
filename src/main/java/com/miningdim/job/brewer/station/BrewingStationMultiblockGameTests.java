package com.miningdim.job.brewer.station;

import com.miningdim.core.MiningConstants;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Real placement and redirected-use regressions for the brewing-station multiblock. */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class BrewingStationMultiblockGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "brewer";
    private static final BlockPos REL = new BlockPos(2, 2, 2);

    private BrewingStationMultiblockGameTests() {
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void blockItemPlacesFourPartsWithOneInventory(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        try {
            PlacedStation placed = placeStation(helper, player);
            for (BrewingStationBlock.Part part : new BrewingStationBlock.Part[]{
                    BrewingStationBlock.Part.LOWER_LEFT, BrewingStationBlock.Part.LOWER_RIGHT,
                    BrewingStationBlock.Part.UPPER_LEFT, BrewingStationBlock.Part.UPPER_RIGHT}) {
                BlockPos partPos = BrewingStationBlock.partPos(placed.mainPos(), placed.mainState().getValue(BrewingStationBlock.FACING), part);
                helper.assertTrue(helper.getLevel().getBlockState(partPos).getValue(BrewingStationBlock.PART) == part,
                        "BlockItem placement must create " + part);
                helper.assertTrue((part == BrewingStationBlock.Part.LOWER_LEFT)
                                == (helper.getLevel().getBlockEntity(partPos) instanceof BrewingStationBlockEntity),
                        "only LOWER_LEFT may own the brewing inventory");
            }
        } finally {
            MockGameTestPlayers.logout(player);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void auxiliaryPartInteractionOpensMainInventory(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        try {
            PlacedStation placed = placeStation(helper, player);
            BlockPos side = BrewingStationBlock.partPos(placed.mainPos(), placed.mainState().getValue(BrewingStationBlock.FACING),
                    BrewingStationBlock.Part.UPPER_RIGHT);
            BlockState sideState = helper.getLevel().getBlockState(side);
            InteractionResult result = placed.block().use(sideState, helper.getLevel(), side, player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(side), Direction.UP, side, false));
            helper.assertTrue(result.consumesAction()
                            && BrewingStationBlock.mainPos(side, sideState).equals(placed.mainPos())
                            && player.containerMenu instanceof BrewingStationMenu
                            && placed.blockEntity().saveWithFullMetadata().getUUID("Operator").equals(player.getUUID()),
                    "auxiliary interaction must open and assign the LOWER_LEFT inventory");
        } finally {
            MockGameTestPlayers.logout(player);
        }
        helper.succeed();
    }

    private static PlacedStation placeStation(GameTestHelper helper, ServerPlayer player) {
        helper.setBlock(REL.below(), Blocks.STONE);
        player.setYRot(0.0F);
        ItemStack stack = new ItemStack(BrewingStationRegistry.STATION_ITEM.get());
        BlockPos support = helper.absolutePos(REL.below());
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(support).add(0.0D, 0.5D, 0.0D),
                Direction.UP, support, false);
        InteractionResult result = ((BlockItem) BrewingStationRegistry.STATION_ITEM.get()).place(
                new BlockPlaceContext(helper.getLevel(), player, InteractionHand.MAIN_HAND, stack, hit));
        BlockPos main = support.above();
        BlockState mainState = helper.getLevel().getBlockState(main);
        if (!result.consumesAction() || !(helper.getLevel().getBlockEntity(main) instanceof BrewingStationBlockEntity be)) {
            throw new IllegalStateException("brewing station BlockItem placement failed");
        }
        return new PlacedStation((BrewingStationBlock) BrewingStationRegistry.STATION_BLOCK.get(), main, mainState, be);
    }

    private record PlacedStation(BrewingStationBlock block, BlockPos mainPos, BlockState mainState,
                                 BrewingStationBlockEntity blockEntity) {
    }
}
