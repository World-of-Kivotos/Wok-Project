package com.miningdim.freshness;

import com.miningdim.core.MiningConstants;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class FreshnessBoundaryGameTests {

    private static final String BATCH = "freshness_boundary";

    @GameTest(templateNamespace = MiningConstants.MODID, template = "empty", batch = BATCH)
    public static void expiredPlacedCakeCannotBeEaten(GameTestHelper helper) {
        FreshnessConfig.Mode previous = FreshnessConfig.MODE.get();
        FreshnessConfig.MODE.set(FreshnessConfig.Mode.ON);
        BlockPos pos = helper.absolutePos(new BlockPos(1, 2, 1));
        try {
            helper.getLevel().setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 3);
            helper.getLevel().setBlock(pos, Blocks.CAKE.defaultBlockState(), 3);
            long now = FreshnessClockData.nowMinute(helper.getLevel());
            ItemStack cake = new ItemStack(Items.CAKE);
            FreshnessData.stampAt(cake, now - FreshnessCategories.shelfLifeHours(cake) * 60L, 0);
            FreshnessPlacedFood.record(helper.getLevel(), pos, cake, Blocks.CAKE.defaultBlockState());
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            player.setPos(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
            player.getFoodData().setFoodLevel(0);
            player.gameMode.useItemOn(player, helper.getLevel(), ItemStack.EMPTY, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
            helper.assertTrue(player.getFoodData().getFoodLevel() == 0, "expired cake must not feed the player");
            helper.assertTrue(helper.getLevel().getBlockState(pos).isAir(), "expired placed cake is removed");
            helper.assertTrue(FreshnessPlacedFood.at(helper.getLevel().dimension(), pos).isEmpty(),
                    "expired placed record is removed");
        } finally {
            FreshnessPlacedFood.forget(helper.getLevel().dimension(), pos);
            FreshnessConfig.MODE.set(previous);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = "empty", batch = BATCH)
    public static void placedRecordsAreIsolatedByDimension(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(new BlockPos(2, 2, 2));
        ChunkPos chunk = new ChunkPos(pos);
        ItemStack cake = new ItemStack(Items.CAKE);
        long now = FreshnessClockData.nowMinute(helper.getLevel());
        FreshnessData.stampAt(cake, now - 120L, 3);
        helper.getLevel().setBlock(pos, Blocks.CAKE.defaultBlockState(), 3);
        FreshnessPlacedFood.record(helper.getLevel(), pos, cake, Blocks.CAKE.defaultBlockState());
        CompoundTag saved = new CompoundTag();
        FreshnessPlacedFood.saveTo(helper.getLevel().dimension(), chunk, saved);
        saved.getList(FreshnessPlacedFood.KEY_PLACED, 10).getCompound(0).putInt("H", (int) now - 600);
        FreshnessPlacedFood.loadFrom(Level.NETHER, chunk, saved);
        helper.assertTrue(FreshnessPlacedFood.at(helper.getLevel().dimension(), pos).orElseThrow().batchMinute() == now - 120L,
                "nether load must not overwrite overworld food");
        FreshnessPlacedFood.unload(Level.NETHER, chunk);
        helper.assertTrue(FreshnessPlacedFood.at(helper.getLevel().dimension(), pos).isPresent(),
                "nether unload must not erase overworld records");
        FreshnessPlacedFood.forget(helper.getLevel().dimension(), pos);
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = "empty", batch = BATCH)
    public static void takingCachedCraftingResultRechecksExpiredIngredients(GameTestHelper helper) {
        checkCachedCraft(helper, FreshnessConfig.Mode.ON, false);
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = "empty", batch = BATCH)
    public static void dryRunDoesNotBlockCachedCraftingResult(GameTestHelper helper) {
        checkCachedCraft(helper, FreshnessConfig.Mode.DRY_RUN, true);
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = "empty", batch = BATCH)
    public static void expiringMultipleCraftingInputsReturnsEveryBowl(GameTestHelper helper) {
        FreshnessConfig.Mode previous = FreshnessConfig.MODE.get();
        FreshnessConfig.MODE.set(FreshnessConfig.Mode.ON);
        try {
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
            helper.getLevel().setBlock(pos, Blocks.CRAFTING_TABLE.defaultBlockState(), 3);
            CraftingMenu menu = new CraftingMenu(8, player.getInventory(),
                    ContainerLevelAccess.create(helper.getLevel(), pos));
            player.containerMenu = menu;
            long now = FreshnessClockData.nowMinute(helper.getLevel());
            for (int index = 1; index <= 2; index++) {
                ItemStack stew = new ItemStack(Items.MUSHROOM_STEW);
                FreshnessData.stamp(stew, now);
                menu.getSlot(index).set(stew);
            }
            // 原料同时过期；第一格替换触发同步重算时，第二格仍须退回容器。
            for (int index = 1; index <= 2; index++) {
                ItemStack stew = menu.getSlot(index).getItem();
                FreshnessData.stampAt(stew, now - FreshnessCategories.shelfLifeHours(stew) * 60L, 0);
            }
            menu.clicked(0, 0, ClickType.PICKUP, player);
            helper.assertTrue(player.getInventory().countItem(Items.BOWL) == 2,
                    "each expired crafting input returns its own bowl even during recursive recalculation");
            helper.assertTrue(menu.getSlot(1).getItem().is(com.miningdim.freshness.item.FreshnessItems.ROT.get())
                            && menu.getSlot(2).getItem().is(com.miningdim.freshness.item.FreshnessItems.ROT.get()),
                    "both expired inputs are replaced");
        } finally {
            FreshnessConfig.MODE.set(previous);
        }
        helper.succeed();
    }

    private static void checkCachedCraft(GameTestHelper helper, FreshnessConfig.Mode mode, boolean allowed) {
        FreshnessConfig.Mode previous = FreshnessConfig.MODE.get();
        FreshnessConfig.MODE.set(mode);
        try {
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
            helper.getLevel().setBlock(pos, Blocks.CRAFTING_TABLE.defaultBlockState(), 3);
            CraftingMenu menu = new CraftingMenu(7, player.getInventory(),
                    ContainerLevelAccess.create(helper.getLevel(), pos));
            player.containerMenu = menu;
            long now = FreshnessClockData.nowMinute(helper.getLevel());
            for (int index = 1; index <= 3; index++) {
                ItemStack wheat = new ItemStack(Items.WHEAT);
                FreshnessData.stamp(wheat, now);
                menu.getSlot(index).set(wheat);
            }
            helper.assertTrue(menu.getSlot(0).getItem().is(Items.BREAD), "fixture has a cached bread result");
            // 原料过期时不会触发 slotsChanged；保持已经算出的结果格来复现该入口。
            FreshnessData.stampAt(menu.getSlot(1).getItem(),
                    now - FreshnessCategories.shelfLifeHours(menu.getSlot(1).getItem()) * 60L, 0);
            menu.clicked(0, 0, ClickType.PICKUP, player);
            helper.assertTrue(allowed ? menu.getCarried().is(Items.BREAD) : menu.getCarried().isEmpty(),
                    "cached result enforcement must follow mode " + mode);
            if (!allowed) {
                helper.assertTrue(menu.getSlot(0).getItem().isEmpty(), "expired recipe result is cleared before pickup");
            }
        } finally {
            FreshnessConfig.MODE.set(previous);
        }
        helper.succeed();
    }
}
