package com.miningdim.job.chef.fryer;

import com.miningdim.core.MiningConstants;
import com.miningdim.economy.Currency;
import com.miningdim.economy.EconomyServices;
import com.miningdim.entry.MiningCapabilities;
import com.miningdim.job.JobId;
import com.miningdim.job.JobServices;
import com.miningdim.job.chef.ChefBlocks;
import com.miningdim.job.chef.ChefConfig;
import com.miningdim.job.chef.ChefQualityNbt;
import com.miningdim.job.chef.zhangshao.ZhangshaoKernel;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class FryerGameTests {
    private FryerGameTests() {}
    private record Fixture(FryerBlockEntity fryer, ServerPlayer player, BlockPos pos) {}

    private static Fixture fixture(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        helper.getLevel().setBlockAndUpdate(pos, ChefBlocks.DEEP_FRYER_STEEL.get().defaultBlockState());
        FryerBlockEntity fryer = (FryerBlockEntity) helper.getLevel().getBlockEntity(pos);
        ServerPlayer player = MockGameTestPlayers.makeMockSurvivalServerPlayerWithChannel(helper);
        player.setPos(pos.getX() + .5, pos.getY() + .5, pos.getZ() + 1.5);
        player.containerMenu = new FryerMenu(31, player.getInventory(), fryer);
        fryer.inventory().setStackInSlot(0, new ItemStack(Items.COD, 2));
        return new Fixture(fryer, player, pos);
    }

    private static long balance(ServerPlayer player) { return EconomyServices.economyService().creditBalance(player); }
    private static long xp(ServerPlayer player) { return JobServices.jobService().totalXp(player, JobId.CHEF); }
    private static void fund(ServerPlayer player) { EconomyServices.economyService().grant(player, Currency.CREDIT, 1000); }

    @GameTest(templateNamespace = MiningConstants.MODID, template = "empty", batch = "chef_fryer")
    public static void inventoryCoalescesAndNewerChunkDoesNotReplayOldJournal(GameTestHelper helper) throws IOException {
        Fixture f = fixture(helper);
        try {
            FryerPlayerData data = FryerPlayerData.get(helper.getLevel()); data.sync();
            Path journal = helper.getLevel().getServer().getWorldPath(LevelResource.ROOT)
                    .resolve("data/miningdim_fryer_players.dat");
            byte[] before = Files.readAllBytes(journal);
            for (int i = 0; i < 100; i++) f.fryer.inventory().setStackInSlot(0, new ItemStack(Items.COD, 1 + i % 64));
            f.fryer.inventory().setStackInSlot(0, new ItemStack(Items.COD, 4));
            helper.assertTrue(Arrays.equals(before, Files.readAllBytes(journal)),
                    "One hundred ordinary slot changes do not synchronously rewrite the journal");
            data.flushAsync();
            f.fryer.inventory().setStackInSlot(0, new ItemStack(Items.COD, 6));
            data.sync();
            FryerPlayerData disk = FryerPlayerData.load(net.minecraft.nbt.NbtIo.readCompressed(journal.toFile()).getCompound("data"));
            CompoundTag snapshot = disk.checkpoint(helper.getLevel(), f.pos);
            helper.assertTrue(snapshot.getCompound("Inventory").getList("Items", 10).getCompound(0).getByte("Count") == 6,
                    "The synchronous boundary waits for the immutable background snapshot and commits the latest inventory");
            CompoundTag newer = f.fryer.saveWithoutMetadata();
            long oldRevision = snapshot.getLong("FryerRevision");
            // 磁盘边界：区块已含下一版本，旧 journal 的清理仍可能尚未提交，或区块仍在待写缓存中。
            newer.putLong("FryerRevision", oldRevision + 1);
            f.fryer.setRemoved();
            FryerBlockEntity restored = new FryerBlockEntity(f.pos, f.fryer.getBlockState());
            restored.load(newer); helper.getLevel().setBlockEntity(restored);
            helper.assertTrue(restored.inventory().getStackInSlot(0).getCount() == 6
                    && restored.saveWithoutMetadata().getLong("FryerRevision") == oldRevision + 1
                    && data.checkpoint(helper.getLevel(), f.pos).getLong("FryerRevision") == oldRevision,
                    "A newer chunk skips the stale journal without deleting its recovery evidence before a write receipt");
            restored.inventory().setStackInSlot(0, new ItemStack(Items.COD, 7));
            helper.assertTrue(restored.saveWithoutMetadata().getLong("FryerRevision") > oldRevision + 1,
                    "The next checkpoint advances beyond the loaded chunk revision");
            helper.succeed();
        } finally { MockGameTestPlayers.logout(f.player); }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = "empty", batch = "chef_fryer")
    public static void menusShowEachViewerCurrentDishRecords(GameTestHelper helper) {
        Fixture f = fixture(helper);
        ServerPlayer other = MockGameTestPlayers.makeMockSurvivalServerPlayerWithChannel(helper);
        try {
            other.setPos(f.pos.getX() + .5, f.pos.getY() + .5, f.pos.getZ() + 1.5);
            FryerMenu first = (FryerMenu) f.player.containerMenu;
            FryerMenu second = new FryerMenu(32, other.getInventory(), f.fryer);
            other.containerMenu = second;
            MiningCapabilities.get(f.player).orElseThrow().jobProgress(JobId.CHEF).setLevel(5);
            MiningCapabilities.get(other).orElseThrow().jobProgress(JobId.CHEF).setLevel(9);
            FryerPlayerData records = FryerPlayerData.get(helper.getLevel());
            ResourceLocation cod = new ResourceLocation("minecraft", "cooked_cod");
            ResourceLocation salmon = new ResourceLocation("minecraft", "cooked_salmon");
            long now = System.currentTimeMillis();
            for (int i = 0; i < 3; i++) records.record(f.player.getUUID(), cod, 1, 350, now);
            records.record(other.getUUID(), cod, 1, 700, now);
            records.record(other.getUUID(), salmon, 1, 450, now);
            first.broadcastChanges(); second.broadcastChanges();
            helper.assertTrue(first.value(14) == 5 && first.value(5) == 1 && first.value(12) == 3 && first.value(6) == 350,
                    "First viewer sees their own chef level and cod record");
            helper.assertTrue(second.value(14) == 9 && second.value(5) == 0 && second.value(12) == 1 && second.value(6) == 700,
                    "Second viewer sees their own chef level and cod record");
            f.fryer.inventory().setStackInSlot(0, new ItemStack(Items.SALMON));
            first.broadcastChanges(); second.broadcastChanges();
            helper.assertTrue(first.value(12) == 0 && first.value(6) == 0 && second.value(12) == 1 && second.value(6) == 450,
                    "Changing the ingredients switches both viewers to their own salmon record");
            f.fryer.inventory().setStackInSlot(0, ItemStack.EMPTY);
            second.broadcastChanges();
            helper.assertTrue(second.value(5) == 0 && second.value(12) == 0 && second.value(6) == 0 && second.value(14) == 9,
                    "No matched recipe clears dish records without clearing viewer level");
            f.fryer.inventory().setStackInSlot(0, new ItemStack(Items.COD));
            fund(f.player);
            helper.assertTrue(f.fryer.start(f.player, true), "Operator starts with the last cod reserved");
            int hash = ZhangshaoKernel.hash(f.fryer.sessionState());
            first.broadcastChanges(); second.broadcastChanges();
            helper.assertTrue(second.value(12) == 1 && second.value(6) == 700 && first.value(12) == 3,
                    "Active dish remains visible after its last ingredient is reserved");
            helper.assertTrue(f.fryer.isOperator(f.player) && !f.fryer.isOperator(other)
                    && ZhangshaoKernel.hash(f.fryer.sessionState()) == hash,
                    "Viewing does not alter the operator or session state");
            f.fryer.abort(f.player, f.fryer.roundId(), "Test finished");
            helper.assertTrue(second.value(12) == 1 && second.value(6) == 700 && first.value(12) == 3,
                    "The finished dish keeps each viewer's records visible until the output is taken");
            helper.succeed();
        } finally { MockGameTestPlayers.logout(other); MockGameTestPlayers.logout(f.player); }
    }
    @GameTest(templateNamespace = MiningConstants.MODID, template = "empty", batch = "chef_fryer")
    public static void removedFryerMenuSynchronizesAndClosesWithoutCrash(GameTestHelper helper) {
        Fixture f = fixture(helper);
        try {
            FryerMenu menu = (FryerMenu) f.player.containerMenu;
            menu.broadcastChanges();
            helper.assertTrue(menu.value(13) == 1 && menu.stillValid(f.player), "Placed fryer is usable before removal");
            helper.assertTrue(helper.getLevel().destroyBlock(f.pos, false), "Destroy the fryer through the real block removal lifecycle");
            helper.assertTrue(f.fryer.isRemoved() && !menu.stillValid(f.player), "Old menu outlives its removed block entity");
            // 原版玩家 tick 先同步再检查 stillValid，拆台后的旧菜单必须仍可同步。
            menu.broadcastChanges();
            helper.assertTrue(menu.value(13) == 0, "Removed fryer no longer advertises a usable heat source");
            f.player.tick();
            helper.assertTrue(f.player.containerMenu == f.player.inventoryMenu, "Player tick closes the removed fryer menu without crashing");
            helper.succeed();
        } finally { MockGameTestPlayers.logout(f.player); }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = "empty", batch = "chef_fryer")
    public static void homeCookingConsumesOneRecipeWithoutMoneyOrXp(GameTestHelper helper) {
        Fixture f = fixture(helper);
        try {
            long money = balance(f.player), experience = xp(f.player);
            f.fryer.inventory().setStackInSlot(0, ItemStack.EMPTY);
            f.player.getInventory().setItem(0, new ItemStack(Items.COD, 2));
            ItemStack inserted = f.player.containerMenu.quickMoveStack(f.player, FryerMenu.SLOTS + 27);
            helper.assertTrue(inserted.getCount() == 2 && f.player.getInventory().getItem(0).isEmpty()
                    && f.fryer.inventory().getStackInSlot(0).getCount() == 2, "Shift insertion transfers the exact real ingredients");
            helper.assertTrue(f.fryer.start(f.player, false), "Real starter fish recipe must start");
            helper.assertTrue(f.fryer.inventory().getStackInSlot(0).getCount() == 1, "One fish moves into the reservation");
            for (int i = 0; i < 200; i++) f.fryer.serverTick();
            ItemStack output = f.fryer.inventory().getStackInSlot(8);
            helper.assertTrue(output.is(Items.COOKED_COD) && output.getCount() == 1 && !ChefQualityNbt.hasQuality(output),
                    "Home cooking produces one ordinary cooked fish");
            helper.assertTrue(balance(f.player) == money && xp(f.player) == experience, "Home cooking is free and grants no XP");

            FryerRecipe overlapping = new FryerRecipe(new ResourceLocation("miningdim", "test_overlap"), List.of(
                    new FryerRecipe.Input(Ingredient.of(Items.COD, Items.SALMON), 1),
                    new FryerRecipe.Input(Ingredient.of(Items.COD), 2)), Ingredient.of(Items.BOWL), 1,
                    new ItemStack(Items.COOKED_COD), 1, 4, 200);
            SimpleContainer ingredients = new SimpleContainer(9);
            ingredients.setItem(0, new ItemStack(Items.COD, 2)); ingredients.setItem(1, new ItemStack(Items.SALMON));
            helper.assertTrue(overlapping.allocation(ingredients) == null, "Missing container refuses the actual recipe");
            ingredients.setItem(6, new ItemStack(Items.BOWL));
            int[] taken = overlapping.allocation(ingredients);
            helper.assertTrue(taken != null && taken[0] == 2 && taken[1] == 1 && taken[6] == 1,
                    "Overlapping ingredients consume exactly the required fish and separate bowl");
            helper.succeed();
        } finally { MockGameTestPlayers.logout(f.player); }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = "empty", batch = "chef_fryer")
    public static void refusalsNeverPartiallyChargeOrConsume(GameTestHelper helper) throws IOException {
        Fixture f = fixture(helper);
        try {
            fund(f.player);
            long money = balance(f.player);
            f.fryer.inventory().setStackInSlot(8, new ItemStack(Items.COOKED_SALMON, 64));
            helper.assertTrue(!f.fryer.start(f.player, true) && balance(f.player) == money
                    && f.fryer.inventory().getStackInSlot(0).getCount() == 2, "Full output keeps ingredients and money");
            f.fryer.inventory().extractItem(8, 64, false);
            f.fryer.inventory().setStackInSlot(0, new ItemStack(Items.STONE));
            helper.assertTrue(!f.fryer.start(f.player, true) && balance(f.player) == money, "No recipe cannot charge");
            f.fryer.inventory().setStackInSlot(0, new ItemStack(Items.COD, 2));
            if (money > 0) EconomyServices.economyService().tryCharge(f.player, Currency.CREDIT, money);
            helper.assertTrue(ChefConfig.seasoningCreditCost() > 0 && !f.fryer.start(f.player, true)
                    && balance(f.player) == 0 && f.fryer.inventory().getStackInSlot(0).getCount() == 2,
                    "Insufficient balance keeps all ingredients and leaves no active transaction");
            helper.assertTrue(!f.fryer.busy(), "Rejected start does not lock the fryer");
            CompoundTag beforeIo = f.fryer.saveWithoutMetadata();
            fund(f.player); money = balance(f.player);
            FryerPlayerData.get(helper.getLevel()).sync();
            Path blockedJournal = helper.getLevel().getServer().getWorldPath(LevelResource.ROOT)
                    .resolve("data/miningdim_fryer_players.dat.tmp");
            // 暂存路径不可写会真实触发 journal IOException，不替换生产持久化实现。
            Files.createDirectory(blockedJournal);
            boolean refusedWrite = false;
            try { f.fryer.start(f.player, true); }
            catch (UncheckedIOException expected) { refusedWrite = true; }
            finally { Files.delete(blockedJournal); }
            helper.assertTrue(refusedWrite && balance(f.player) == money && f.fryer.busy(),
                    "Failed transaction persistence prevents charging and keeps the reservation locked for recovery");
            FryerPlayerData.get(helper.getLevel()).sync();
            f.fryer.setRemoved();
            FryerBlockEntity restored = new FryerBlockEntity(f.pos, f.fryer.getBlockState());
            restored.load(beforeIo); helper.getLevel().setBlockEntity(restored); restored.serverTick();
            helper.assertTrue(restored.inventory().getStackInSlot(0).getCount() == 2,
                    "Reloading after the failed start restores the exact raw ingredients without charging");
            helper.succeed();
        } finally { MockGameTestPlayers.logout(f.player); }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = "empty", batch = "chef_fryer", timeoutTicks = 700)
    public static void realTimeInputsSettleServerQualityAndChargeOnce(GameTestHelper helper) {
        Fixture f = fixture(helper);
        fund(f.player);
        MiningCapabilities.get(f.player).orElseThrow().jobProgress(JobId.CHEF).setLevel(9);
        long money = balance(f.player), experience = xp(f.player);
        long azure = EconomyServices.economyService().heartstoneBalance(f.player);
        helper.assertTrue(f.fryer.start(f.player, true), "Manual production starts with funded real ingredients");
        long round = f.fryer.roundId(), started = System.nanoTime();
        ZhangshaoKernel.State local = f.fryer.sessionState();
        byte[] pending = new byte[20]; int[] count = {0}, seq = {0}; boolean[] finished = {false};
        helper.assertTrue(!f.fryer.start(f.player, true) && balance(f.player) == money - ChefConfig.seasoningCreditCost(),
                "A repeated start cannot reserve or charge another fish");
        helper.onEachTick(() -> {
            if (finished[0]) return;
            int due = (int) (((System.nanoTime() - started) / 1_000_000L - 1000L) / 50L);
            while (local.tick < due && local.end == ZhangshaoKernel.RUNNING) {
                int input = control(local);
                pending[count[0]++] = (byte) input; ZhangshaoKernel.step(local, input);
                boolean terminal = local.end != ZhangshaoKernel.RUNNING;
                if (count[0] == 20 || terminal) {
                    f.fryer.acceptInput(f.player, round, seq[0]++, local.tick - count[0],
                            Arrays.copyOf(pending, count[0]), seq[0] == 1 ? 0 : ZhangshaoKernel.hash(local), terminal);
                    count[0] = 0;
                }
            }
            if (f.fryer.data().get(0) == 3) {
                finished[0] = true;
                try {
                    ItemStack output = f.fryer.inventory().getStackInSlot(8);
                    helper.assertTrue(output.is(Items.COOKED_COD) && output.getCount() == 1 && ChefQualityNbt.hasQuality(output),
                            "Accepted real-time inputs produce exactly one quality dish");
                    ZhangshaoKernel.Result expected = ZhangshaoKernel.result(local, null);
                    helper.assertTrue(f.fryer.data().get(8) == expected.accuracy && f.fryer.data().get(9) == expected.pen,
                            "Wrong client hash cannot replace the server replay result");
                    helper.assertTrue(balance(f.player) == money - ChefConfig.seasoningCreditCost() && xp(f.player) > experience
                            && EconomyServices.economyService().heartstoneBalance(f.player) == azure,
                            "Manual quality dish charges only one CREDIT fee, preserves AZURE and grants actual XP");
                    f.fryer.acceptInput(f.player, round, 0, 0, new byte[]{0}, 0, true);
                    helper.assertTrue(output.getCount() == 1 && f.fryer.inventory().getStackInSlot(0).getCount() == 1,
                            "A stale packet cannot duplicate production");
                    helper.succeed();
                } finally { MockGameTestPlayers.logout(f.player); }
            }
        });
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = "empty", batch = "chef_fryer")
    public static void illegalInputAndRepeatedAbortProduceOnlyOneHomeDish(GameTestHelper helper) {
        Fixture f = fixture(helper);
        try {
            fund(f.player); long money = balance(f.player), experience = xp(f.player);
            CompoundTag oldChunk = f.fryer.saveWithoutMetadata();
            helper.assertTrue(f.fryer.start(f.player, true), "Manual start succeeds before invalid input");
            long round = f.fryer.roundId();
            // 输入值来自C2S字节；保留位4是服务器必须拒绝的实际网络边界。
            f.fryer.acceptInput(f.player, round, 0, 0, new byte[]{4}, 0, false);
            f.fryer.abort(f.player, round, "重复关闭");
            f.fryer.acceptInput(f.player, round, 0, 0, new byte[]{4}, 0, false);
            ItemStack result = f.fryer.inventory().getStackInSlot(8);
            helper.assertTrue(result.is(Items.COOKED_COD) && result.getCount() == 1 && !ChefQualityNbt.hasQuality(result),
                    "Invalid input settles one home dish without a quality stamp");
            helper.assertTrue(balance(f.player) == money - ChefConfig.seasoningCreditCost() && xp(f.player) == experience,
                    "Abandonment does not refund or award XP");
            f.fryer.setRemoved();
            FryerBlockEntity restored = new FryerBlockEntity(f.pos, f.fryer.getBlockState());
            restored.load(oldChunk); helper.getLevel().setBlockEntity(restored); restored.serverTick();
            helper.assertTrue(restored.inventory().getStackInSlot(8).is(Items.COOKED_COD)
                    && restored.inventory().getStackInSlot(0).getCount() == 1,
                    "Completed journal restores the dish even when chunk still contains pre-charge ingredients");
            f.player.containerMenu = new FryerMenu(32, f.player.getInventory(), restored);
            ItemStack collected = f.player.containerMenu.quickMoveStack(f.player, FryerMenu.OUTPUT);
            helper.assertTrue(f.player.getInventory().countItem(Items.COOKED_COD) == 1,
                    "Shift collection delivers exactly one dish to the player after the fryer checkpoint");
            restored.load(oldChunk); restored.serverTick();
            helper.assertTrue(collected.getCount() == 1 && restored.inventory().getStackInSlot(8).isEmpty()
                    && balance(f.player) == money - ChefConfig.seasoningCreditCost(),
                    "Collected output cannot reappear or refund when the old chunk is loaded again");
            List<ItemStack> drops = restored.breakAndDrop();
            helper.assertTrue(drops.stream().noneMatch(s -> s.is(Items.COOKED_COD))
                    && drops.stream().filter(s -> s.is(Items.COD)).mapToInt(ItemStack::getCount).sum() == 1,
                    "Breaking the settled fryer drops only the untouched remainder");
            helper.assertTrue(restored.breakAndDrop().stream().allMatch(ItemStack::isEmpty), "Second teardown cannot duplicate drops");
            helper.succeed();
        } finally { MockGameTestPlayers.logout(f.player); }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = "empty", batch = "chef_fryer")
    public static void staleChunkRecoveryReturnsReservedIngredientsAndFeeOnce(GameTestHelper helper) {
        Fixture f = fixture(helper);
        try {
            fund(f.player); long money = balance(f.player);
            CompoundTag oldChunk = f.fryer.saveWithoutMetadata();
            helper.assertTrue(f.fryer.start(f.player, true), "Start records a durable operation before chunk save");
            helper.assertTrue(balance(f.player) == money - ChefConfig.seasoningCreditCost(), "Fee is actually charged");
            f.fryer.setRemoved();
            FryerBlockEntity restored = new FryerBlockEntity(f.pos, f.fryer.getBlockState());
            restored.load(oldChunk); helper.getLevel().setBlockEntity(restored);
            restored.serverTick(); restored.serverTick();
            // 区块恢复后旧菜单仍会走关闭生命周期，不能再用已卸载实体的 held 结算另一份菜。
            f.player.containerMenu.removed(f.player);
            helper.assertTrue(restored.inventory().getStackInSlot(0).is(Items.COD)
                    && restored.inventory().getStackInSlot(0).getCount() == 2 && restored.inventory().getStackInSlot(8).isEmpty(),
                    "Journal overrides stale chunk and restores the exact raw ingredients without a dish");
            helper.assertTrue(balance(f.player) == money, "Recovery refunds the reserved fee exactly once");
            CompoundTag saved = restored.saveWithoutMetadata(); restored.load(saved); restored.serverTick();
            FryerPlayerData.get(helper.getLevel()).retryRefunds();
            helper.assertTrue(balance(f.player) == money && restored.inventory().getStackInSlot(0).getCount() == 2,
                    "Loading the recovered state again cannot refund or restore twice");
            // 区块 NBT 是磁盘输入，不支持的版本不能绕过余容器队列校验。
            CompoundTag unsupported = saved.copy(); unsupported.putInt("FryerVersion", 1);
            FryerBlockEntity incompatible = new FryerBlockEntity(f.pos, f.fryer.getBlockState());
            boolean rejected = false;
            try { incompatible.load(unsupported); }
            catch (IllegalArgumentException expected) { rejected = true; }
            helper.assertTrue(rejected, "Unsupported block save version must be rejected");
            helper.succeed();
        } finally { MockGameTestPlayers.logout(f.player); }
    }

    private record Choice(ZhangshaoKernel.State state, int first, long score) {}
    private static int control(ZhangshaoKernel.State state) {
        List<Choice> beam = List.of(new Choice(state, 0, 0));
        for (int depth = 0; depth < 8; depth++) {
            List<Choice> next = new ArrayList<>();
            for (Choice node : beam) for (int hold = 0; hold <= 1; hold++) {
                ZhangshaoKernel.State predicted = node.state.copy();
                int input = hold | (predicted.T >= 640 && predicted.cd == 0 ? 2 : 0);
                ZhangshaoKernel.step(predicted, input);
                long score = node.score + (predicted.inPrev != 0 ? 1_000_000L : -1_000_000L)
                        - Math.abs(predicted.yb + predicted.p.H / 2 - predicted.yt);
                next.add(new Choice(predicted, depth == 0 ? input : node.first, score));
            }
            next.sort(Comparator.comparingLong(Choice::score).reversed());
            beam = next.subList(0, Math.min(12, next.size()));
        }
        return beam.get(0).first;
    }
}
