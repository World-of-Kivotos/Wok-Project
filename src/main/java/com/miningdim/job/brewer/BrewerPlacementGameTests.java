package com.miningdim.job.brewer;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.brewer.cellar.WineCellarBlock;
import com.miningdim.job.brewer.cellar.WineCellarRegistry;
import com.miningdim.job.brewer.station.BrewingStationBlock;
import com.miningdim.job.brewer.station.BrewingStationRegistry;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class BrewerPlacementGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "brewer";
    private static final BlockPos MAIN = new BlockPos(2, 2, 2);

    private BrewerPlacementGameTests() {
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void brewingStationBlockItemRejectsEveryOccupiedAuxiliaryPart(GameTestHelper helper) {
        BrewingStationBlock block = (BrewingStationBlock) BrewingStationRegistry.STATION_BLOCK.get();
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        try {
            player.setYRot(0.0F);
            helper.setBlock(MAIN.below(), Blocks.STONE);
            for (BrewingStationBlock.Part part : new BrewingStationBlock.Part[]{
                    BrewingStationBlock.Part.LOWER_RIGHT,
                    BrewingStationBlock.Part.UPPER_LEFT,
                    BrewingStationBlock.Part.UPPER_RIGHT}) {
                ItemStack stack = new ItemStack(BrewingStationRegistry.STATION_ITEM.get());
                BlockPlaceContext context = placementContext(helper, player, stack, MAIN.below());
                BlockState placed = block.getStateForPlacement(context);
                helper.assertTrue(placed != null, "clear workstation footprint must have a placement state");
                BlockPos occupied = BrewingStationBlock.partPos(MAIN, placed.getValue(BrewingStationBlock.FACING), part);
                assertBlockedPlacement(helper, (BlockItem) BrewingStationRegistry.STATION_ITEM.get(), context,
                        occupied, "occupied " + part + " must reject BlockItem placement");
            }
        } finally {
            MockGameTestPlayers.logout(player);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void wineCellarBlockItemRejectsOccupiedSidePart(GameTestHelper helper) {
        WineCellarBlock block = (WineCellarBlock) WineCellarRegistry.WINE_CELLAR.get();
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        try {
            player.setYRot(0.0F);
            helper.setBlock(MAIN.below(), Blocks.STONE);
            ItemStack stack = new ItemStack(WineCellarRegistry.WINE_CELLAR_ITEM.get());
            BlockPlaceContext context = placementContext(helper, player, stack, MAIN.below());
            BlockState placed = block.getStateForPlacement(context);
            helper.assertTrue(placed != null, "clear cellar footprint must have a placement state");
            BlockPos occupied = WineCellarBlock.partPos(MAIN, placed.getValue(WineCellarBlock.FACING), WineCellarBlock.Part.SIDE);
            assertBlockedPlacement(helper, (BlockItem) WineCellarRegistry.WINE_CELLAR_ITEM.get(), context,
                    occupied, "occupied cellar side must reject BlockItem placement");
        } finally {
            MockGameTestPlayers.logout(player);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void survivalPickaxeMiningReturnsOneBrewingStation(GameTestHelper helper) {
        BrewingStationBlock block = (BrewingStationBlock) BrewingStationRegistry.STATION_BLOCK.get();
        ServerPlayer player = MockGameTestPlayers.makeMockSurvivalServerPlayerWithChannel(helper);
        try {
            player.setYRot(0.0F);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_PICKAXE));
            helper.setBlock(MAIN.below(), Blocks.STONE);
            ItemStack stack = new ItemStack(BrewingStationRegistry.STATION_ITEM.get());
            BlockPlaceContext context = placementContext(helper, player, stack, MAIN.below());
            InteractionResult result = ((BlockItem) BrewingStationRegistry.STATION_ITEM.get()).place(context);
            helper.assertTrue(result.consumesAction(), "clear workstation must place through BlockItem");
            BlockPos absoluteMain = helper.absolutePos(MAIN);
            helper.assertTrue(helper.getLevel().getBlockState(absoluteMain).is(BlockTags.MINEABLE_WITH_PICKAXE),
                    "brewing station must be tagged mineable with a pickaxe");
            Set<UUID> priorDrops = helper.getLevel().getEntitiesOfClass(ItemEntity.class, area(absoluteMain)).stream()
                    .map(ItemEntity::getUUID).collect(Collectors.toSet());
            helper.assertTrue(player.gameMode.destroyBlock(absoluteMain), "survival pickaxe must break the workstation");
            int returned = helper.getLevel().getEntitiesOfClass(ItemEntity.class, area(absoluteMain)).stream()
                    .filter(drop -> !priorDrops.contains(drop.getUUID()))
                    .map(ItemEntity::getItem)
                    .filter(drop -> drop.is(BrewingStationRegistry.STATION_ITEM.get()))
                    .mapToInt(ItemStack::getCount)
                    .sum();
            helper.assertTrue(returned == 1, "survival pickaxe mining must return one workstation");
        } finally {
            MockGameTestPlayers.logout(player);
        }
        helper.succeed();
    }

    private static BlockPlaceContext placementContext(GameTestHelper helper, ServerPlayer player,
                                                      ItemStack stack, BlockPos support) {
        BlockPos absoluteSupport = helper.absolutePos(support);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(absoluteSupport).add(0.0D, 0.5D, 0.0D),
                Direction.UP, absoluteSupport, false);
        return new BlockPlaceContext(player, InteractionHand.MAIN_HAND, stack, hit);
    }

    private static void assertBlockedPlacement(GameTestHelper helper, BlockItem item,
                                               BlockPlaceContext context, BlockPos occupied, String message) {
        Mob blocker = helper.spawn(EntityType.COW, occupied);
        int initialCount = context.getItemInHand().getCount();
        try {
            helper.assertTrue(item.place(context) == InteractionResult.FAIL, message);
            helper.assertTrue(context.getItemInHand().getCount() == initialCount,
                    "failed placement must preserve the stack");
            helper.assertTrue(helper.getLevel().getBlockState(helper.absolutePos(MAIN)).isAir()
                            && helper.getLevel().getBlockState(helper.absolutePos(occupied)).isAir(),
                    "failed placement must leave the main and occupied cells empty");
        } finally {
            blocker.discard();
        }
    }

    private static AABB area(BlockPos pos) {
        return new AABB(pos).inflate(2.0D);
    }
}
