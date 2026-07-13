package com.miningdim.job.munitions;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.munitions.block.GunsmithAssemblyBenchBlock;
import com.miningdim.job.munitions.block.GunsmithAssemblyBenchBlockEntity;
import com.miningdim.job.munitions.gunsmith.GunsmithBlueprint;
import com.miningdim.job.munitions.gunsmith.GunsmithBlueprintItem;
import com.miningdim.job.munitions.gunsmith.GunsmithPartItem;
import com.miningdim.job.munitions.gunsmith.GunsmithPartQuality;
import com.miningdim.job.munitions.gunsmith.GunsmithPlatform;
import com.miningdim.job.munitions.gunsmith.GunsmithPressPart;
import com.miningdim.job.munitions.menu.GunsmithAssemblyMenu;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.HashSet;
import java.util.Set;

@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class GunsmithAssemblyGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "gunsmith_assembly";
    private static final BlockPos MAIN_REL = new BlockPos(1, 1, 1);

    private GunsmithAssemblyGameTests() {
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void assemblyBenchCoordinatesRoundTripForEveryFacing(GameTestHelper helper) {
        GunsmithAssemblyBenchBlock block = assemblyBlock();
        BlockPos mainPos = new BlockPos(40, 8, 40);
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            Set<BlockPos> positions = new HashSet<>();
            int minX = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxZ = Integer.MIN_VALUE;
            for (GunsmithAssemblyBenchBlock.Part part : GunsmithAssemblyBenchBlock.Part.values()) {
                BlockPos partPos = GunsmithAssemblyBenchBlock.partPos(mainPos, facing, part);
                BlockState state = block.defaultBlockState()
                        .setValue(GunsmithAssemblyBenchBlock.FACING, facing)
                        .setValue(GunsmithAssemblyBenchBlock.PART, part);
                helper.assertTrue(GunsmithAssemblyBenchBlock.mainPos(partPos, state).equals(mainPos),
                        facing + " " + part + " must map back to the main position");
                positions.add(partPos);
                minX = Math.min(minX, partPos.getX());
                maxX = Math.max(maxX, partPos.getX());
                minZ = Math.min(minZ, partPos.getZ());
                maxZ = Math.max(maxZ, partPos.getZ());
            }
            helper.assertTrue(positions.size() == 4, facing + " must produce four unique structure positions");
            helper.assertTrue(maxX - minX == 1 && maxZ - minZ == 1,
                    facing + " footprint must be exactly 2x2 blocks");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void assemblyBenchBlockItemPlacesFourPartsAndDropsExactlyOnce(GameTestHelper helper) {
        helper.setBlock(MAIN_REL.below(), Blocks.STONE);
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        player.setYRot(0.0F);
        ItemStack stack = new ItemStack(ModMunitionsItems.GUNSMITH_ASSEMBLY_BENCH_ITEM.get());
        BlockPlaceContext context = placementContext(helper, player, stack);
        BlockState placementState = assemblyBlock().getStateForPlacement(context);
        helper.assertTrue(placementState != null, "clear 2x2 footprint must produce a placement state");

        Direction facing = placementState.getValue(GunsmithAssemblyBenchBlock.FACING);
        InteractionResult result = ((BlockItem) ModMunitionsItems.GUNSMITH_ASSEMBLY_BENCH_ITEM.get()).place(context);
        helper.assertTrue(result.consumesAction(), "BlockItem placement must succeed for a clear footprint");

        BlockPos mainAbsolute = context.getClickedPos();
        int totalDrops = 0;
        for (GunsmithAssemblyBenchBlock.Part part : GunsmithAssemblyBenchBlock.Part.values()) {
            BlockPos partAbsolute = GunsmithAssemblyBenchBlock.partPos(mainAbsolute, facing, part);
            BlockState state = helper.getLevel().getBlockState(partAbsolute);
            helper.assertTrue(state.getBlock() == assemblyBlock(), part + " must be placed by BlockItem");
            helper.assertTrue(state.getValue(GunsmithAssemblyBenchBlock.PART) == part,
                    part + " must keep its exact part state");
            BlockEntity found = helper.getLevel().getBlockEntity(partAbsolute);
            helper.assertTrue((part == GunsmithAssemblyBenchBlock.Part.MAIN)
                            == (found instanceof GunsmithAssemblyBenchBlockEntity),
                    "only main may own the block entity, checked " + part);
            if (found instanceof GunsmithAssemblyBenchBlockEntity assemblyBe) {
                helper.assertTrue(assemblyBe.getType() == ModMunitionsBlockEntities.GUNSMITH_ASSEMBLY_BENCH.get(),
                        "main must use the registered assembly bench block entity type");
            }

            int partDrops = Block.getDrops(state, helper.getLevel(), partAbsolute, found).stream()
                    .filter(drop -> drop.is(ModMunitionsItems.GUNSMITH_ASSEMBLY_BENCH_ITEM.get()))
                    .mapToInt(ItemStack::getCount)
                    .sum();
            helper.assertTrue(partDrops == (part == GunsmithAssemblyBenchBlock.Part.MAIN ? 1 : 0),
                    part + " loot count must match the single-main-drop contract");
            totalDrops += partDrops;
        }
        helper.assertTrue(totalDrops == 1, "the complete 2x2 structure must yield exactly one assembly bench");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void assemblyBenchBlockItemRejectsBlockedFootprintAtomically(GameTestHelper helper) {
        helper.setBlock(MAIN_REL.below(), Blocks.STONE);
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        player.setYRot(0.0F);
        ItemStack stack = new ItemStack(ModMunitionsItems.GUNSMITH_ASSEMBLY_BENCH_ITEM.get());
        BlockPlaceContext context = placementContext(helper, player, stack);
        BlockState clearState = assemblyBlock().getStateForPlacement(context);
        helper.assertTrue(clearState != null, "control footprint must begin clear");

        Direction facing = clearState.getValue(GunsmithAssemblyBenchBlock.FACING);
        BlockPos sideAbsolute = GunsmithAssemblyBenchBlock.partPos(
                context.getClickedPos(), facing, GunsmithAssemblyBenchBlock.Part.SIDE);
        helper.getLevel().setBlock(sideAbsolute, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        helper.assertTrue(assemblyBlock().getStateForPlacement(context) == null,
                "occupied side cell must reject the complete placement state");

        InteractionResult result = ((BlockItem) ModMunitionsItems.GUNSMITH_ASSEMBLY_BENCH_ITEM.get()).place(context);
        helper.assertTrue(result == InteractionResult.FAIL, "blocked 2x2 footprint must fail BlockItem placement");
        helper.assertTrue(helper.getLevel().getBlockState(context.getClickedPos()).isAir(),
                "failed placement must not leave the main part behind");
        helper.assertTrue(helper.getLevel().getBlockState(sideAbsolute).is(Blocks.STONE),
                "failed placement must not overwrite the blocking cell");
        for (GunsmithAssemblyBenchBlock.Part part : GunsmithAssemblyBenchBlock.Part.values()) {
            if (part == GunsmithAssemblyBenchBlock.Part.MAIN || part == GunsmithAssemblyBenchBlock.Part.SIDE) {
                continue;
            }
            BlockPos partAbsolute = GunsmithAssemblyBenchBlock.partPos(context.getClickedPos(), facing, part);
            helper.assertTrue(helper.getLevel().getBlockState(partAbsolute).isAir(),
                    "failed placement must not leave " + part);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH, timeoutTicks = 20)
    public static void assemblyBenchUseHonorsGateAndOpensMainMenuFromEveryPart(GameTestHelper helper) {
        Direction facing = Direction.EAST;
        placeStructure(helper, facing);
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        BlockPos clickedRelative = GunsmithAssemblyBenchBlock.partPos(
                MAIN_REL, facing, GunsmithAssemblyBenchBlock.Part.BACK_SIDE);
        BlockPos clickedAbsolute = helper.absolutePos(clickedRelative);
        BlockState clickedState = helper.getLevel().getBlockState(clickedAbsolute);
        BlockHitResult hit = new BlockHitResult(
                Vec3.atCenterOf(clickedAbsolute), Direction.UP, clickedAbsolute, false);
        GunsmithAssemblyBenchBlockEntity be = requireMainBlockEntity(helper, helper.absolutePos(MAIN_REL));
        boolean previousEnabled = MunitionsConfig.GUNSMITH_ENABLED.get();

        try {
            MunitionsConfig.GUNSMITH_ENABLED.set(false);
            InteractionResult disabledResult = assemblyBlock().use(
                    clickedState, helper.getLevel(), clickedAbsolute, player, InteractionHand.MAIN_HAND, hit);
            helper.assertTrue(disabledResult == InteractionResult.CONSUME,
                    "disabled assembly bench use must be handled on the server");
            helper.assertFalse(be.isAnimating(), "disabled side-part use must not start assembly");
            helper.assertFalse(player.containerMenu instanceof GunsmithAssemblyMenu,
                    "disabled side-part use must not open the assembly menu");
            assertStructureActive(helper, facing, false);

            MunitionsConfig.GUNSMITH_ENABLED.set(true);
            InteractionResult enabledResult = assemblyBlock().use(
                    clickedState, helper.getLevel(), clickedAbsolute, player, InteractionHand.MAIN_HAND, hit);
            helper.assertTrue(enabledResult == InteractionResult.CONSUME,
                    "enabled side-part use must be handled on the server");
            helper.assertTrue(player.containerMenu instanceof GunsmithAssemblyMenu,
                    "back-side use must resolve to and open the main block entity menu");
            helper.assertFalse(be.isAnimating(), "opening the menu must not start assembly before button confirmation");
            GunsmithAssemblyMenu menu = (GunsmithAssemblyMenu) player.containerMenu;
            int firstPlayerMenuSlot = GunsmithAssemblyBenchBlockEntity.SLOT_COUNT;
            player.getInventory().setItem(9, GunsmithPartItem.createStack(
                    ModMunitionsItems.GUNSMITH_PART.get(), GunsmithPlatform.AR,
                    GunsmithPressPart.CORE, GunsmithPartQuality.COMMON, 1.0D));
            for (GunsmithPressPart part : GunsmithPressPart.values()) {
                helper.assertFalse(menu.getSlot(GunsmithAssemblyBenchBlockEntity.slotForPart(part)).isActive(),
                        "empty part slot must stay inactive before a blueprint is inserted: " + part);
            }
            helper.assertTrue(menu.quickMoveStack(player, firstPlayerMenuSlot).isEmpty(),
                    "shift-click must not move a part before a blueprint is inserted");
            helper.assertTrue(player.getInventory().getItem(9).getCount() == 1,
                    "rejected shift-click must preserve the player's part");
            be.inventory().setStackInSlot(GunsmithAssemblyBenchBlockEntity.SLOT_BLUEPRINT,
                    GunsmithBlueprintItem.createStack(
                            ModMunitionsItems.GUNSMITH_BLUEPRINT.get(), GunsmithBlueprint.M4A1));
            for (GunsmithPressPart part : GunsmithPressPart.values()) {
                boolean required = GunsmithBlueprint.M4A1.requiredParts().contains(part);
                helper.assertTrue(
                        menu.getSlot(GunsmithAssemblyBenchBlockEntity.slotForPart(part)).isActive() == required,
                        "M4 blueprint must expose exactly its six required part slots: " + part);
            }
            helper.assertTrue(menu.quickMoveStack(player, firstPlayerMenuSlot)
                            .is(ModMunitionsItems.GUNSMITH_PART.get()),
                    "shift-click must move a matching part after a blueprint is inserted");
            helper.assertTrue(player.getInventory().getItem(9).isEmpty(),
                    "accepted shift-click must empty the player's source slot");
            helper.assertTrue(!be.inventory().getStackInSlot(
                            GunsmithAssemblyBenchBlockEntity.slotForPart(GunsmithPressPart.CORE)).isEmpty(),
                    "accepted shift-click must place the core in its blueprint slot");
            assertStructureActive(helper, facing, false);
            player.closeContainer();
        } finally {
            MunitionsConfig.GUNSMITH_ENABLED.set(previousEnabled);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void assemblyBenchEveryPartCascadesForEveryFacing(GameTestHelper helper) {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            for (GunsmithAssemblyBenchBlock.Part brokenPart : GunsmithAssemblyBenchBlock.Part.values()) {
                placeStructure(helper, facing);
                BlockPos brokenAbsolute = helper.absolutePos(
                        GunsmithAssemblyBenchBlock.partPos(MAIN_REL, facing, brokenPart));
                helper.getLevel().destroyBlock(brokenAbsolute, false);
                for (GunsmithAssemblyBenchBlock.Part expectedRemoved : GunsmithAssemblyBenchBlock.Part.values()) {
                    BlockPos absolute = helper.absolutePos(
                            GunsmithAssemblyBenchBlock.partPos(MAIN_REL, facing, expectedRemoved));
                    helper.assertTrue(helper.getLevel().getBlockState(absolute).isAir(),
                            "breaking " + facing + " " + brokenPart + " must remove " + expectedRemoved);
                }
            }
        }
        helper.succeed();
    }

    private static GunsmithAssemblyBenchBlock assemblyBlock() {
        return (GunsmithAssemblyBenchBlock) ModMunitionsBlocks.GUNSMITH_ASSEMBLY_BENCH.get();
    }

    private static BlockPlaceContext placementContext(GameTestHelper helper, ServerPlayer player, ItemStack stack) {
        BlockPos supportAbsolute = helper.absolutePos(MAIN_REL.below());
        BlockHitResult hit = new BlockHitResult(
                Vec3.atCenterOf(supportAbsolute).add(0.0D, 0.5D, 0.0D),
                Direction.UP, supportAbsolute, false);
        return new BlockPlaceContext(helper.getLevel(), player, InteractionHand.MAIN_HAND, stack, hit);
    }

    private static void placeStructure(GameTestHelper helper, Direction facing) {
        GunsmithAssemblyBenchBlock block = assemblyBlock();
        for (GunsmithAssemblyBenchBlock.Part part : GunsmithAssemblyBenchBlock.Part.values()) {
            BlockState state = block.defaultBlockState()
                    .setValue(GunsmithAssemblyBenchBlock.FACING, facing)
                    .setValue(GunsmithAssemblyBenchBlock.PART, part)
                    .setValue(GunsmithAssemblyBenchBlock.ACTIVE, false);
            helper.setBlock(GunsmithAssemblyBenchBlock.partPos(MAIN_REL, facing, part), state);
        }
    }

    private static GunsmithAssemblyBenchBlockEntity requireMainBlockEntity(GameTestHelper helper, BlockPos absolute) {
        BlockEntity found = helper.getLevel().getBlockEntity(absolute);
        if (!(found instanceof GunsmithAssemblyBenchBlockEntity be)) {
            throw new IllegalStateException("assembly bench block entity missing at " + absolute);
        }
        return be;
    }

    private static void assertStructureActive(GameTestHelper helper, Direction facing, boolean expected) {
        for (GunsmithAssemblyBenchBlock.Part part : GunsmithAssemblyBenchBlock.Part.values()) {
            BlockPos absolute = helper.absolutePos(GunsmithAssemblyBenchBlock.partPos(MAIN_REL, facing, part));
            BlockState state = helper.getLevel().getBlockState(absolute);
            helper.assertTrue(state.getBlock() == assemblyBlock(), part + " must remain part of the structure");
            helper.assertTrue(state.getValue(GunsmithAssemblyBenchBlock.ACTIVE) == expected,
                    part + " active state must be " + expected);
        }
    }
}
