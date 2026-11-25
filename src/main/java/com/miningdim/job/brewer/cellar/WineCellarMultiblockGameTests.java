package com.miningdim.job.brewer.cellar;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.brewer.BrewerItems;
import com.miningdim.job.brewer.WineNbt;
import com.miningdim.job.brewer.WineQuality;
import com.miningdim.job.brewer.WineType;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Multi-block placement, redirection, legacy, locked-save, and side-break regressions. */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class WineCellarMultiblockGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "brewer";
    private static final BlockPos REL = new BlockPos(2, 2, 2);

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void blockItemPlacesMainAndSideWithOneInventory(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        try {
            player.setYRot(0.0F);
            helper.setBlock(REL.below(), Blocks.STONE);
            ItemStack stack = new ItemStack(WineCellarRegistry.WINE_CELLAR_ITEM.get());
            BlockPlaceContext context = placementContext(helper, player, stack, REL.below());
            InteractionResult result = ((BlockItem) WineCellarRegistry.WINE_CELLAR_ITEM.get()).place(context);
            BlockPos mainPos = context.getClickedPos();
            BlockState main = helper.getLevel().getBlockState(mainPos);
            BlockPos sidePos = WineCellarBlock.partPos(mainPos, main.getValue(WineCellarBlock.FACING), WineCellarBlock.Part.SIDE);
            BlockState side = helper.getLevel().getBlockState(sidePos);
            helper.assertTrue(result.consumesAction()
                            && main.getValue(WineCellarBlock.PART) == WineCellarBlock.Part.MAIN
                            && side.getValue(WineCellarBlock.PART) == WineCellarBlock.Part.SIDE,
                    "BlockItem placement must create the complete MAIN/SIDE pair");
            helper.assertTrue(helper.getLevel().getBlockEntity(mainPos) instanceof WineCellarBlockEntity
                            && helper.getLevel().getBlockEntity(sidePos) == null,
                    "only MAIN may own the wine-cellar inventory");
            WineCellarBlockEntity cellar = (WineCellarBlockEntity) helper.getLevel().getBlockEntity(mainPos);
            ItemStack wine = new ItemStack(BrewerItems.itemFor(WineType.VODKA));
            WineNbt.stamp(wine, WineQuality.LOW, player.getUUID());
            cellar.inventory().setStackInSlot(0, wine.copy());
            cellar.inventory().setStackInSlot(WineCellarBlockEntity.FUEL_SLOT,
                    new ItemStack(BrewerItems.DRIED_WHEAT.get(), 11));
            cellar.handleUpdateTag(cellar.getUpdateTag());
            helper.assertTrue(cellar.displayedBottle(0).is(BrewerItems.itemFor(WineType.VODKA)),
                    "display update tag must expose a placed wine bottle");
            cellar.inventory().setStackInSlot(0, ItemStack.EMPTY);
            cellar.handleUpdateTag(cellar.getUpdateTag());
            helper.assertTrue(cellar.displayedBottle(0).isEmpty(),
                    "display update tag must clear the bottle after the wine slot is emptied");
            cellar.inventory().setStackInSlot(0, wine.copy());
            cellar.handleUpdateTag(new CompoundTag());
            helper.assertTrue(ItemStack.isSameItemSameTags(cellar.inventory().getStackInSlot(0), wine)
                            && cellar.inventory().getStackInSlot(WineCellarBlockEntity.FUEL_SLOT).getCount() == 11,
                    "a display packet must not overwrite authoritative wine inventory or fuel");
        } finally {
            MockGameTestPlayers.logout(player);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void sideInteractionNormalizesToMainInventory(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        try {
            PlacedCellar placed = placeCellar(helper, player);
            InteractionResult result = placed.block().use(placed.sideState(), helper.getLevel(), placed.sidePos(), player,
                    InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(placed.sidePos()), Direction.UP, placed.sidePos(), false));
            helper.assertTrue(WineCellarBlock.mainPos(placed.sidePos(), placed.sideState()).equals(placed.mainPos())
                            && result.consumesAction()
                            && player.containerMenu instanceof WineCellarMenu menu
                            && menu.blockEntity() == placed.blockEntity(),
                    "SIDE interaction must open the MAIN inventory rather than a second container");
        } finally {
            MockGameTestPlayers.logout(player);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void propertyFreeLegacyCellarRemainsSingleBlock(GameTestHelper helper) {
        CompoundTag oldTag = new CompoundTag();
        oldTag.putString("Name", "miningdim:wine_cellar");
        BlockState legacy = BlockState.CODEC.parse(NbtOps.INSTANCE, oldTag).result().orElseThrow();
        BlockPos pos = helper.absolutePos(REL);
        helper.getLevel().setBlock(pos, legacy, Block.UPDATE_ALL);
        BlockEntity original = helper.getLevel().getBlockEntity(pos);
        helper.getLevel().setBlock(pos.east(), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        helper.assertTrue(legacy.getValue(WineCellarBlock.PART) == WineCellarBlock.Part.LEGACY
                        && helper.getLevel().getBlockState(pos) == legacy
                        && helper.getLevel().getBlockEntity(pos) == original,
                "property-free pre-multiblock state must keep its single block and inventory");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void lockedMainCannotBeBrokenThroughSideAndRetainsOriginalNbt(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockSurvivalServerPlayerWithChannel(helper);
        try {
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_AXE));
            PlacedCellar placed = placeCellar(helper, player);
            CompoundTag original = placed.blockEntity().saveWithFullMetadata();
            original.putString("SideLockRegression", UUID.randomUUID().toString());
            original.putInt("FuelReserve", -1);
            placed.blockEntity().load(original);
            helper.assertTrue(placed.blockEntity().isLocked(), "invalid main NBT must enter the locked recovery state");
            player.gameMode.destroyBlock(placed.sidePos());
            helper.assertTrue(helper.getLevel().getBlockState(placed.mainPos()).is(placed.block())
                            && helper.getLevel().getBlockState(placed.sidePos()).is(placed.block())
                            && placed.blockEntity().saveWithFullMetadata().equals(original),
                    "breaking SIDE must not bypass a locked MAIN or mutate its original NBT");
        } finally {
            MockGameTestPlayers.logout(player);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void normalSideBreakReturnsInventoryAndReserveExactlyOnce(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockSurvivalServerPlayerWithChannel(helper);
        try {
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_AXE));
            PlacedCellar placed = placeCellar(helper, player);
            ItemStack wine = new ItemStack(BrewerItems.itemFor(WineType.VODKA), 2);
            WineNbt.stamp(wine, WineQuality.LOW, player.getUUID());
            placed.blockEntity().inventory().setStackInSlot(0, wine.copy());
            for (int i = 0; i < 3; i++) {
                placed.blockEntity().inventory().setStackInSlot(WineCellarBlockEntity.FUEL_SLOT,
                        new ItemStack(BrewerItems.DRIED_WHEAT.get(), 64));
                placed.blockEntity().depositFuel();
            }
            placed.blockEntity().inventory().setStackInSlot(WineCellarBlockEntity.FUEL_SLOT,
                    new ItemStack(BrewerItems.DRIED_WHEAT.get(), 7));
            AABB area = new AABB(placed.mainPos()).minmax(new AABB(placed.sidePos())).inflate(2.0D);
            Set<UUID> prior = helper.getLevel().getEntitiesOfClass(ItemEntity.class, area).stream()
                    .map(ItemEntity::getUUID).collect(Collectors.toSet());

            player.gameMode.destroyBlock(placed.sidePos());
            helper.assertTrue(helper.getLevel().getBlockState(placed.mainPos()).isAir()
                            && helper.getLevel().getBlockState(placed.sidePos()).isAir(),
                    "breaking an unlocked side must remove both blocks");

            List<ItemEntity> drops = helper.getLevel().getEntitiesOfClass(ItemEntity.class, area).stream()
                    .filter(drop -> !prior.contains(drop.getUUID())).toList();
            helper.assertTrue(count(drops, wine) == 2
                            && count(drops, new ItemStack(BrewerItems.DRIED_WHEAT.get())) == 199
                            && count(drops, new ItemStack(WineCellarRegistry.WINE_CELLAR_ITEM.get())) == 1,
                    "SIDE break must return wine, all 192 reserve plus 7 slot fuel, and one cellar exactly once");
        } finally {
            MockGameTestPlayers.logout(player);
        }
        helper.succeed();
    }

    private static PlacedCellar placeCellar(GameTestHelper helper, ServerPlayer player) {
        helper.setBlock(REL.below(), Blocks.STONE);
        player.setYRot(0.0F);
        ItemStack stack = new ItemStack(WineCellarRegistry.WINE_CELLAR_ITEM.get());
        BlockPlaceContext context = placementContext(helper, player, stack, REL.below());
        InteractionResult result = ((BlockItem) WineCellarRegistry.WINE_CELLAR_ITEM.get()).place(context);
        BlockPos mainPos = context.getClickedPos();
        BlockState mainState = helper.getLevel().getBlockState(mainPos);
        WineCellarBlock block = (WineCellarBlock) WineCellarRegistry.WINE_CELLAR.get();
        BlockPos sidePos = WineCellarBlock.partPos(mainPos, mainState.getValue(WineCellarBlock.FACING), WineCellarBlock.Part.SIDE);
        if (!result.consumesAction() || !(helper.getLevel().getBlockEntity(mainPos) instanceof WineCellarBlockEntity blockEntity)) {
            throw new IllegalStateException("wine cellar BlockItem placement failed");
        }
        return new PlacedCellar(block, mainPos, sidePos, helper.getLevel().getBlockState(sidePos), blockEntity);
    }

    private static BlockPlaceContext placementContext(GameTestHelper helper, ServerPlayer player, ItemStack stack, BlockPos supportRel) {
        BlockPos support = helper.absolutePos(supportRel);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(support).add(0.0D, 0.5D, 0.0D), Direction.UP, support, false);
        return new BlockPlaceContext(helper.getLevel(), player, InteractionHand.MAIN_HAND, stack, hit);
    }

    private static int count(List<ItemEntity> drops, ItemStack expected) {
        return drops.stream().map(ItemEntity::getItem).filter(stack -> ItemStack.isSameItemSameTags(stack, expected))
                .mapToInt(ItemStack::getCount).sum();
    }

    private record PlacedCellar(WineCellarBlock block, BlockPos mainPos, BlockPos sidePos, BlockState sideState,
                                WineCellarBlockEntity blockEntity) {
    }
}
