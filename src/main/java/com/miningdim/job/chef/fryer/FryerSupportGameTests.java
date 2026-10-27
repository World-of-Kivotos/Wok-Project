package com.miningdim.job.chef.fryer;

import com.miningdim.core.MiningConstants;
import com.miningdim.economy.Currency;
import com.miningdim.economy.EconomyOperationDomain;
import com.miningdim.economy.EconomyOperationStatus;
import com.miningdim.economy.EconomyServices;
import com.miningdim.economy.SqliteEconomyLedger;
import com.miningdim.testutil.MockGameTestPlayers;
import io.netty.buffer.Unpooled;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class FryerSupportGameTests {
    private FryerSupportGameTests() {}

    @GameTest(templateNamespace = MiningConstants.MODID, template = "empty", batch = "chef_fryer_support")
    public static void recipeOverlappingIngredientsAndNetworkRoundTrip(GameTestHelper helper) {
        ResourceLocation id = new ResourceLocation(MiningConstants.MODID, "test_overlap");
        FryerRecipe recipe = new FryerRecipe(id, List.of(
                new FryerRecipe.Input(Ingredient.of(Items.COD, Items.SALMON), 1),
                new FryerRecipe.Input(Ingredient.of(Items.COD), 2)), Ingredient.of(Items.BOWL), 1,
                new ItemStack(Items.COOKED_COD), 1, 4, 200);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            FryerRecipeSerializer serializer = new FryerRecipeSerializer();
            serializer.toNetwork(buf, recipe);
            FryerRecipe decoded = serializer.fromNetwork(id, buf);
            SimpleContainer inv = new SimpleContainer(9);
            inv.setItem(0, new ItemStack(Items.COD, 2)); inv.setItem(1, new ItemStack(Items.SALMON));
            helper.assertTrue(decoded.allocation(inv) == null, "Missing bowl prevents the recipe");
            inv.setItem(6, new ItemStack(Items.BOWL));
            int[] taken = decoded.allocation(inv);
            helper.assertTrue(taken != null && taken[0] == 2 && taken[1] == 1 && taken[6] == 1
                    && decoded.result().is(Items.COOKED_COD) && decoded.cookTime() == 200,
                    "Round trip preserves recipe and overlapping ingredients allocate correctly");
            helper.succeed();
        } finally { buf.release(); }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = "empty", batch = "chef_fryer_support")
    public static void creditOnlyChargeAndRefundAreIdempotent(GameTestHelper helper) throws Exception {
        SqliteEconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        try {
            UUID player = UUID.randomUUID(), operation = UUID.randomUUID();
            ledger.credit(player, Currency.CREDIT, 100);
            ledger.credit(player, Currency.AZURE, 10);
            helper.assertTrue(ledger.tryChargeBundle(EconomyOperationDomain.CHEF_FRYER, player, operation, 30, 0) == EconomyOperationStatus.CHARGED
                    && ledger.tryChargeBundle(EconomyOperationDomain.CHEF_FRYER, player, operation, 30, 0) == EconomyOperationStatus.CHARGED
                    && ledger.balance(player, Currency.CREDIT) == 70 && ledger.balance(player, Currency.AZURE) == 10,
                    "Replayed fryer charge debits CREDIT once and preserves AZURE");
            helper.assertTrue(ledger.refundBundle(EconomyOperationDomain.CHEF_FRYER, player, operation) == EconomyOperationStatus.REFUNDED
                    && ledger.refundBundle(EconomyOperationDomain.CHEF_FRYER, player, operation) == EconomyOperationStatus.REFUNDED
                    && ledger.balance(player, Currency.CREDIT) == 100 && ledger.balance(player, Currency.AZURE) == 10,
                    "Replayed refund returns the original CREDIT once");
            helper.succeed();
        } finally { ledger.connection().close(); }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = "empty", batch = "chef_fryer_support")
    public static void activeTransactionDefersRefundAndProgressSurvivesReload(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockSurvivalServerPlayerWithChannel(helper);
        Object station = new Object(); UUID operation = UUID.randomUUID();
        FryerPlayerData data = FryerPlayerData.get(helper.getLevel());
        long now = System.currentTimeMillis();
        try {
            EconomyServices.economyService().grant(player, Currency.CREDIT, 100);
            long before = EconomyServices.economyService().creditBalance(player);
            helper.assertTrue(EconomyServices.economyService().tryChargeBundle(EconomyOperationDomain.CHEF_FRYER,
                    player, operation, 30, 0) == EconomyOperationStatus.CHARGED, "Charge starts");
            data.begin(player.getUUID(), station, operation, now);
            data.enqueueRefund(operation, player.getUUID()); data.sync();
            data.release(player.getUUID(), new Object(), now);
            data.retryRefunds();
            helper.assertTrue(EconomyServices.economyService().creditBalance(player) == before - 30,
                    "Another station cannot release the active operation and trigger its refund");
            data.release(player.getUUID(), station, now);
            data.retryRefunds();
            helper.assertTrue(EconomyServices.economyService().creditBalance(player) == before,
                    "Released transaction refunds exactly once");
            ResourceLocation dish = new ResourceLocation(MiningConstants.MODID, "test_dish");
            data.record(player.getUUID(), dish, 1, 350, now);
            FryerPlayerData loaded = FryerPlayerData.load(data.save(new CompoundTag()));
            helper.assertTrue(loaded.progress(player.getUUID(), dish) == 1 && loaded.best(player.getUUID(), dish) == 350,
                    "Saved progress and best score survive reload");
            helper.succeed();
        } finally { data.release(player.getUUID(), station, System.currentTimeMillis()); MockGameTestPlayers.logout(player); }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = "empty", batch = "chef_fryer_support")
    public static void rejectsUnsupportedOrIncompletePlayerSave(GameTestHelper helper) {
        // NBT 来自磁盘；损坏版本或缺失事务队列不能被解释成旧档初始态。
        CompoundTag saved = new FryerPlayerData().save(new CompoundTag());
        CompoundTag unsupported = saved.copy();
        unsupported.putInt("Version", 1);
        boolean unsupportedRejected = false;
        try { FryerPlayerData.load(unsupported); }
        catch (IllegalArgumentException expected) {
            unsupportedRejected = "Invalid fryer player save version".equals(expected.getMessage());
        }
        helper.assertTrue(unsupportedRejected, "Unsupported version 1 must be rejected");
        CompoundTag incomplete = saved.copy();
        incomplete.remove("Completions");
        boolean incompleteRejected = false;
        try { FryerPlayerData.load(incomplete); }
        catch (IllegalArgumentException expected) {
            incompleteRejected = "Invalid fryer list Completions".equals(expected.getMessage());
        }
        helper.assertTrue(incompleteRejected, "Version 2 requires the completions list");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = "empty", batch = "chef_fryer_support")
    public static void storageRejectsDamagedJournalWithoutOverwritingIt(GameTestHelper helper) throws Exception {
        Path directory = Files.createTempDirectory("fryer-storage-test-");
        Path file = directory.resolve("miningdim_fryer_players.dat");
        // 损坏文件真实经过原版加载器；直接调 load 测不到原版吞错后创建空档的路径。
        CompoundTag damaged = new FryerPlayerData().save(new CompoundTag()); damaged.remove("Completions");
        FryerJournal.write(file.toFile(), damaged);
        byte[] before = Files.readAllBytes(file);
        DimensionDataStorage storage = new DimensionDataStorage(directory.toFile(), helper.getLevel().getServer().getFixerUpper());
        boolean rejected = false;
        try { FryerPlayerData.loadFromStorage(storage, file); }
        catch (IllegalStateException expected) { rejected = true; }
        helper.assertTrue(rejected && Arrays.equals(before, Files.readAllBytes(file)),
                "Failed vanilla storage load must throw and preserve the exact journal bytes");
        Files.delete(file); Files.delete(directory);
        helper.succeed();
    }
}
