package com.miningdim.job.chef.fryer;

import com.miningdim.core.MiningConstants;
import com.miningdim.economy.Currency;
import com.miningdim.economy.EconomyServices;
import com.miningdim.job.chef.ChefBlocks;
import com.miningdim.job.chef.ChefConfig;
import com.miningdim.job.chef.ChefQualityNbt;
import com.miningdim.job.chef.station.CookingStationBlock;
import com.miningdim.job.chef.zhangshao.ZhangshaoKernel;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.items.ItemStackHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class CookingProductionGameTests {
    private CookingProductionGameTests() {}
    private record Fixture(FryerBlockEntity station, ServerPlayer player, BlockPos pos) {}

    private static Fixture fixture(GameTestHelper helper, Block block, int x) {
        BlockPos pos = helper.absolutePos(new BlockPos(x, 1, 1));
        helper.getLevel().setBlockAndUpdate(pos, block.defaultBlockState());
        FryerBlockEntity station = (FryerBlockEntity) helper.getLevel().getBlockEntity(pos);
        ServerPlayer player = MockGameTestPlayers.makeMockSurvivalServerPlayerWithChannel(helper);
        player.setPos(pos.getX() + .5, pos.getY() + .5, pos.getZ() + 1.5);
        player.containerMenu = new FryerMenu(41, player.getInventory(), station);
        return new Fixture(station, player, pos);
    }

    private static void cake(FryerBlockEntity station) {
        for (int i = 0; i < 3; i++) station.inventory().setStackInSlot(i, new ItemStack(Items.MILK_BUCKET));
        station.inventory().setStackInSlot(3, new ItemStack(Items.SUGAR, 2));
        station.inventory().setStackInSlot(4, new ItemStack(Items.EGG));
        station.inventory().setStackInSlot(5, new ItemStack(Items.WHEAT, 3));
    }

    private static long money(ServerPlayer player) { return EconomyServices.economyService().creditBalance(player); }

    @GameTest(templateNamespace = MiningConstants.MODID, template = "empty", batch = "chef_production")
    public static void ovenRealCakeAndCookiesPreserveRemaindersAndYield(GameTestHelper helper) {
        Fixture cake = fixture(helper, ChefBlocks.BAKING_OVEN_STEEL.get(), 1);
        Fixture cookie = fixture(helper, ChefBlocks.BAKING_OVEN_CHINESE.get(), 3);
        try {
            cake(cake.station); long beforeCake = money(cake.player), beforeCookie = money(cookie.player);
            cake.station.inventory().setStackInSlot(3, ItemStack.EMPTY);
            cake.player.getInventory().setItem(0, new ItemStack(Items.SUGAR, 2));
            cake.player.containerMenu.quickMoveStack(cake.player, FryerMenu.SLOTS + 27);
            helper.assertTrue(cake.station.inventory().getStackInSlot(3).is(Items.SUGAR)
                    && cake.station.inventory().getStackInSlot(FryerRecipe.SEASONING_SLOT).isEmpty()
                    && cake.player.getInventory().getItem(0).isEmpty(), "Shift sugar fills the cake ingredient slot rather than optional seasoning");
            helper.assertTrue(cake.station.previewRecipe() != null && cake.station.previewRecipe().result().is(Items.CAKE), "Loaded cake recipe is assigned to oven");
            helper.assertTrue(cake.station.start(cake.player, false), "Cake starts as home cooking");
            for (int i = 0; i < 200; i++) cake.station.serverTick();
            ItemStack result = cake.station.inventory().getStackInSlot(FryerRecipe.OUTPUT_SLOT);
            helper.assertTrue(result.is(Items.CAKE) && result.getCount() == 1 && !ChefQualityNbt.hasQuality(result), "One ordinary cake is produced");
            int buckets = 0;
            for (int i = 0; i <= FryerRecipe.CONTAINER_SLOT; i++) {
                ItemStack stack = cake.station.inventory().getStackInSlot(i);
                if (stack.is(Items.BUCKET)) buckets += stack.getCount();
                else helper.assertTrue(stack.isEmpty(), "Cake consumes all other inputs");
            }
            helper.assertTrue(buckets == 3 && money(cake.player) == beforeCake, "Three milk buckets return three empty buckets without a fee");
            cookie.station.inventory().setStackInSlot(0, new ItemStack(Items.WHEAT, 2));
            cookie.station.inventory().setStackInSlot(1, new ItemStack(Items.COCOA_BEANS));
            helper.assertTrue(cookie.station.start(cookie.player, false), "Loaded cookie recipe starts in oven");
            for (int i = 0; i < 200; i++) cookie.station.serverTick();
            ItemStack cookies = cookie.station.inventory().getStackInSlot(FryerRecipe.OUTPUT_SLOT);
            helper.assertTrue(cookies.is(Items.COOKIE) && cookies.getCount() == 8
                    && cookie.station.inventory().getStackInSlot(0).isEmpty() && cookie.station.inventory().getStackInSlot(1).isEmpty()
                    && money(cookie.player) == beforeCookie, "Cookie recipe consumes its inputs and yields eight free cookies");
            helper.succeed();
        } finally { MockGameTestPlayers.logout(cake.player); MockGameTestPlayers.logout(cookie.player); }
    }

    private static FryerRecipe prepFixture() {
        List<FryerRecipe.Input> inputs = new ArrayList<>();
        for (Item item : new Item[]{Items.CARROT, Items.POTATO, Items.APPLE, Items.BREAD, Items.BEETROOT,
                Items.EGG, Items.WHEAT, Items.MELON_SLICE, Items.SUGAR}) inputs.add(new FryerRecipe.Input(Ingredient.of(item), 1));
        return new FryerRecipe(new ResourceLocation(MiningConstants.MODID, "test_production_nine"), inputs,
                Ingredient.of(Items.BOWL), 1, new ItemStack(Items.MUSHROOM_STEW), 2, 4, 1,
                CookingStationBlock.Kind.PREP_COUNTER, 0, 6, true);
    }

    private static void insertNine(FryerBlockEntity station) {
        Item[] items = {Items.CARROT, Items.POTATO, Items.APPLE, Items.BREAD, Items.BEETROOT,
                Items.EGG, Items.WHEAT, Items.MELON_SLICE, Items.SUGAR};
        for (int i = 0; i < items.length; i++) station.inventory().setStackInSlot(i, new ItemStack(items[i]));
        station.inventory().setStackInSlot(FryerRecipe.CONTAINER_SLOT, new ItemStack(Items.BOWL));
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = "empty", batch = "chef_production")
    public static void nineInputsAndContainerAreIsolatedToPrep(GameTestHelper helper) {
        RecipeManager manager = helper.getLevel().getRecipeManager();
        List<Recipe<?>> original = List.copyOf(manager.getRecipes());
        Fixture prep = fixture(helper, ChefBlocks.PREP_COUNTER_CHINESE.get(), 1);
        Fixture oven = fixture(helper, ChefBlocks.BAKING_OVEN_STEEL.get(), 3);
        try {
            List<Recipe<?>> recipes = new ArrayList<>(original); recipes.add(prepFixture()); manager.replaceRecipes(recipes);
            insertNine(prep.station); insertNine(oven.station);
            helper.assertTrue(oven.station.previewRecipe() == null, "Prep recipe cannot match the oven");
            helper.assertTrue(prep.station.start(prep.player, false), "Nine distinct inputs and the bowl start on prep counter");
            prep.station.serverTick();
            ItemStack product = prep.station.inventory().getStackInSlot(FryerRecipe.OUTPUT_SLOT);
            helper.assertTrue(product.is(Items.MUSHROOM_STEW) && product.getCount() == 1, "Exact prep product appears");
            for (int i = 0; i <= FryerRecipe.CONTAINER_SLOT; i++) helper.assertTrue(prep.station.inventory().getStackInSlot(i).isEmpty(), "Input and bowl consumed once: slot " + i);
            helper.succeed();
        } finally { manager.replaceRecipes(original); MockGameTestPlayers.logout(prep.player); MockGameTestPlayers.logout(oven.player); }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = "empty", batch = "chef_production")
    public static void manualOvenAndDrinkParametersAndAbortAreServerOwned(GameTestHelper helper) {
        RecipeManager manager = helper.getLevel().getRecipeManager();
        List<Recipe<?>> original = List.copyOf(manager.getRecipes());
        Fixture oven = fixture(helper, ChefBlocks.BAKING_OVEN_STEEL.get(), 1);
        Fixture prep = fixture(helper, ChefBlocks.PREP_COUNTER_CHINESE.get(), 3);
        try {
            List<Recipe<?>> recipes = new ArrayList<>(original); recipes.add(prepFixture()); manager.replaceRecipes(recipes);
            EconomyServices.economyService().grant(oven.player, Currency.CREDIT, 1000);
            EconomyServices.economyService().grant(prep.player, Currency.CREDIT, 1000);
            long ovenBefore = money(oven.player), prepBefore = money(prep.player);
            cake(oven.station); insertNine(prep.station);
            helper.assertTrue(oven.station.start(oven.player, true) && prep.station.start(prep.player, true), "Both manual stations start");
            ZhangshaoKernel.State a = oven.station.sessionState(), b = prep.station.sessionState();
            helper.assertTrue(a.p.station == ZhangshaoKernel.OVEN && a.p.F == 2 && b.p.station == ZhangshaoKernel.PREP
                    && b.p.beats == 6 && b.p.jitterOn == 0, "Oven flips and drink rhythm reach the server kernel");
            long ovenRound = oven.station.roundId(), prepRound = prep.station.roundId();
            oven.station.abort(oven.player, ovenRound, "test"); prep.station.abort(prep.player, prepRound, "test");
            oven.station.abort(oven.player, ovenRound, "repeat"); prep.station.abort(prep.player, prepRound, "repeat");
            helper.assertTrue(oven.station.inventory().getStackInSlot(FryerRecipe.OUTPUT_SLOT).is(Items.CAKE)
                    && prep.station.inventory().getStackInSlot(FryerRecipe.OUTPUT_SLOT).is(Items.MUSHROOM_STEW)
                    && oven.station.inventory().getStackInSlot(FryerRecipe.OUTPUT_SLOT).getCount() == 1
                    && prep.station.inventory().getStackInSlot(FryerRecipe.OUTPUT_SLOT).getCount() == 1,
                    "Repeated abort produces one home dish per station");
            helper.assertTrue(money(oven.player) == ovenBefore - ChefConfig.seasoningCreditCost()
                    && money(prep.player) == prepBefore - ChefConfig.seasoningCreditCost(), "Each manual round charges once");
            helper.succeed();
        } finally { manager.replaceRecipes(original); MockGameTestPlayers.logout(oven.player); MockGameTestPlayers.logout(prep.player); }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = "empty", batch = "chef_production")
    public static void versionTwoCheckpointRestoresInventoryAndReservationsOnce(GameTestHelper helper) {
        Fixture f = fixture(helper, ChefBlocks.DEEP_FRYER_STEEL.get(), 1);
        try {
            CompoundTag old = f.station.saveWithoutMetadata(); old.putInt("FryerVersion", 2);
            ItemStackHandler inv = new ItemStackHandler(9);
            inv.setStackInSlot(0, new ItemStack(Items.COD)); inv.setStackInSlot(6, new ItemStack(Items.BOWL));
            inv.setStackInSlot(7, new ItemStack(Items.SUGAR)); inv.setStackInSlot(8, new ItemStack(Items.COOKIE));
            old.put("Inventory", inv.serializeNBT()); old.putBoolean("InProgress", true); old.putBoolean("Charged", false);
            old.putUUID("Owner", f.player.getUUID()); old.putUUID("Operation", UUID.randomUUID());
            ListTag reserved = new ListTag();
            for (int i = 0; i < 7; i++) {
                CompoundTag entry = new CompoundTag(); entry.putInt("Slot", i);
                ItemStack stack = i == 0 ? new ItemStack(Items.COD, 2) : i == 6 ? new ItemStack(Items.BOWL) : ItemStack.EMPTY;
                entry.put("Stack", stack.isEmpty() ? new CompoundTag() : stack.save(new CompoundTag())); reserved.add(entry);
            }
            old.put("Reserved", reserved);
            FryerPlayerData.get(helper.getLevel()).checkpoint(helper.getLevel(), f.pos, old);
            f.station.load(old); f.station.serverTick();
            helper.assertTrue(f.station.inventory().getStackInSlot(0).getCount() == 3
                    && f.station.inventory().getStackInSlot(FryerRecipe.CONTAINER_SLOT).getCount() == 2
                    && f.station.inventory().getStackInSlot(FryerRecipe.SEASONING_SLOT).is(Items.SUGAR)
                    && f.station.inventory().getStackInSlot(FryerRecipe.OUTPUT_SLOT).is(Items.COOKIE), "v2 inventory and reserved items map to v3 slots");
            f.station.load(old); f.station.serverTick();
            helper.assertTrue(f.station.inventory().getStackInSlot(0).getCount() == 3
                    && f.station.inventory().getStackInSlot(FryerRecipe.CONTAINER_SLOT).getCount() == 2
                    && f.station.inventory().getStackInSlot(FryerRecipe.OUTPUT_SLOT).getCount() == 1,
                    "Reloading stale v2 chunk after checkpoint cannot copy reservations or output");
            helper.succeed();
        } finally { MockGameTestPlayers.logout(f.player); }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = "empty", batch = "chef_production")
    public static void damagedReservationCannotDuplicateAnActiveOrder(GameTestHelper helper) {
        Fixture f = fixture(helper, ChefBlocks.DEEP_FRYER_STEEL.get(), 1);
        try {
            f.station.inventory().setStackInSlot(0, new ItemStack(Items.COD, 2));
            helper.assertTrue(f.station.start(f.player, false), "Real fryer order reserves one fish");
            CompoundTag corrupted = f.station.saveWithoutMetadata();
            ListTag reserved = corrupted.getList("Reserved", Tag.TAG_COMPOUND);
            reserved.getCompound(1).putInt("Slot", 0);
            FryerBlockEntity copy = new FryerBlockEntity(f.pos, f.station.getBlockState());
            boolean rejected = false;
            try { copy.load(corrupted); }
            catch (IllegalArgumentException expected) { rejected = true; }
            helper.assertTrue(rejected, "Duplicate reserved slots from damaged NBT are rejected");
            for (int i = 0; i < 200; i++) f.station.serverTick();
            helper.assertTrue(f.station.inventory().getStackInSlot(0).getCount() == 1
                    && f.station.inventory().getStackInSlot(FryerRecipe.OUTPUT_SLOT).is(Items.COOKED_COD)
                    && f.station.inventory().getStackInSlot(FryerRecipe.OUTPUT_SLOT).getCount() == 1,
                    "Original order completes exactly once after rejected damaged copy");
            helper.succeed();
        } finally { MockGameTestPlayers.logout(f.player); }
    }
}
