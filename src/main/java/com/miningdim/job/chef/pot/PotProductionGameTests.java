package com.miningdim.job.chef.pot;

import com.miningdim.core.MiningConstants;
import com.miningdim.economy.Currency;
import com.miningdim.economy.EconomyServices;
import com.miningdim.job.JobId;
import com.miningdim.job.JobServices;
import com.miningdim.entry.MiningCapabilities;
import com.miningdim.job.chef.ChefConfig;
import com.miningdim.job.chef.ChefQualityNbt;
import com.miningdim.job.chef.ChefQuality;
import com.miningdim.job.chef.fryer.FryerPlayerData;
import com.miningdim.job.chef.zhangshao.ZhangshaoKernel;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.registries.ForgeRegistries;
import vectorwing.farmersdelight.common.block.entity.CookingPotBlockEntity;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class PotProductionGameTests {
    private PotProductionGameTests() {}

    @GameTest(templateNamespace = MiningConstants.MODID, template = "empty", batch = "chef_pot", timeoutTicks = 700)
    public static void manualReplayProducesQualityDish(GameTestHelper helper) {
        if (!ModList.get().isLoaded("farmersdelight")) { helper.succeed(); return; }
        WithFarmersDelight.manualReplayProducesQualityDish(helper);
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = "empty", batch = "chef_pot")
    public static void busyPotProtectsReservation(GameTestHelper helper) {
        if (!ModList.get().isLoaded("farmersdelight")) { helper.succeed(); return; }
        WithFarmersDelight.busyPotProtectsReservation(helper);
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = "empty", batch = "chef_pot")
    public static void closingMenuSettlesHomeDish(GameTestHelper helper) {
        if (!ModList.get().isLoaded("farmersdelight")) { helper.succeed(); return; }
        WithFarmersDelight.closingMenuSettlesHomeDish(helper);
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = "empty", batch = "chef_pot")
    public static void reloadReturnsInputsAndRefundsOnce(GameTestHelper helper) {
        if (!ModList.get().isLoaded("farmersdelight")) { helper.succeed(); return; }
        WithFarmersDelight.reloadReturnsInputsAndRefundsOnce(helper);
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = "empty", batch = "chef_pot")
    public static void remainderAndFeastYieldArePreserved(GameTestHelper helper) {
        if (!ModList.get().isLoaded("farmersdelight")) { helper.succeed(); return; }
        WithFarmersDelight.remainderAndFeastYieldArePreserved(helper);
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = "empty", batch = "chef_pot")
    public static void removedMenuCannotRestoreDroppedInventory(GameTestHelper helper) {
        if (!ModList.get().isLoaded("farmersdelight")) { helper.succeed(); return; }
        WithFarmersDelight.removedMenuCannotRestoreDroppedInventory(helper);
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = "empty", batch = "chef_pot")
    public static void pickingPotDoesNotSettleOrSuppressMealDrop(GameTestHelper helper) {
        if (!ModList.get().isLoaded("farmersdelight")) { helper.succeed(); return; }
        WithFarmersDelight.pickingPotDoesNotSettleOrSuppressMealDrop(helper);
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = "empty", batch = "chef_pot")
    public static void playerBreakDoesNotDuplicatePotWithoutBowls(GameTestHelper helper) {
        if (!ModList.get().isLoaded("farmersdelight")) { helper.succeed(); return; }
        WithFarmersDelight.playerBreakDoesNotDuplicatePotWithoutBowls(helper);
    }

    private static final class WithFarmersDelight {
        private record Fixture(CookingPotBlockEntity pot, ServerPlayer player, BlockPos pos) {}

        private static Fixture fixture(GameTestHelper helper, int x) {
            BlockPos pos = helper.absolutePos(new BlockPos(x, 2, 1));
            helper.getLevel().setBlockAndUpdate(pos.below(), Blocks.CAMPFIRE.defaultBlockState());
            var block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("farmersdelight", "cooking_pot"));
            if (block == null || block == Blocks.AIR) throw new IllegalStateException("Farmer's Delight cooking pot is not registered");
            helper.getLevel().setBlockAndUpdate(pos, block.defaultBlockState());
            CookingPotBlockEntity pot = (CookingPotBlockEntity) helper.getLevel().getBlockEntity(pos);
            ServerPlayer player = MockGameTestPlayers.makeMockSurvivalServerPlayerWithChannel(helper);
            player.setPos(pos.getX() + .5, pos.getY() + .5, pos.getZ() + 1.5);
            player.containerMenu = pot.createMenu(41, player.getInventory(), player);
            EconomyServices.economyService().grant(player, Currency.CREDIT, 1000);
            return new Fixture(pot, player, pos);
        }

        private static void mushroomStew(CookingPotBlockEntity pot, int count) {
            pot.getInventory().setStackInSlot(0, new ItemStack(Items.BROWN_MUSHROOM, count));
            pot.getInventory().setStackInSlot(1, new ItemStack(Items.RED_MUSHROOM, count));
            pot.getInventory().setStackInSlot(CookingPotBlockEntity.CONTAINER_SLOT, new ItemStack(Items.BOWL, count));
        }

        private static void manualReplayProducesQualityDish(GameTestHelper helper) {
            Fixture f = fixture(helper, 1);
            mushroomStew(f.pot, 2);
            MiningCapabilities.get(f.player).orElseThrow().jobProgress(JobId.CHEF).setLevel(9);
            long before = balance(f.player), beforeXp = xp(f.player);
            helper.assertTrue(PotCraftService.start(f.player, f.pot), "Real mushroom stew starts in heated pot");
            long round = PotCraftService.roundId(f.pot), started = System.nanoTime();
            ZhangshaoKernel.State local = PotCraftService.sessionState(f.pot);
            byte[] pending = new byte[20];
            int[] count = {0}, seq = {0}; boolean[] finished = {false};
            helper.onEachTick(() -> {
                if (finished[0]) return;
                int due = (int) (((System.nanoTime() - started) / 1_000_000L - 1000L) / 50L);
                while (local.tick < due && local.end == ZhangshaoKernel.RUNNING) {
                    int input = control(local);
                    pending[count[0]++] = (byte) input;
                    ZhangshaoKernel.step(local, input);
                    boolean terminal = local.end != ZhangshaoKernel.RUNNING;
                    if (count[0] == 20 || terminal) {
                        PotCraftService.acceptInput(f.player, f.pot, round, seq[0]++, local.tick - count[0],
                                Arrays.copyOf(pending, count[0]), ZhangshaoKernel.hash(local), terminal);
                        count[0] = 0;
                    }
                }
                if (local.end != ZhangshaoKernel.RUNNING) {
                    finished[0] = true;
                    try {
                        ItemStack meal = f.pot.getMeal();
                        helper.assertTrue(meal.is(Items.MUSHROOM_STEW) && meal.getCount() == 1 && ChefQualityNbt.hasQuality(meal),
                                "Server replay produces exactly one quality dish");
                        helper.assertTrue(f.pot.getInventory().getStackInSlot(0).getCount() == 1
                                && f.pot.getInventory().getStackInSlot(1).getCount() == 1,
                                "One recipe was consumed from two available recipes");
                        helper.assertTrue(balance(f.player) == before - ChefConfig.seasoningCreditCost() && xp(f.player) > beforeXp,
                                "Manual dish charges one fee and grants chef XP");
                        helper.succeed();
                    } finally { MockGameTestPlayers.logout(f.player); }
                }
            });
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

        private static void busyPotProtectsReservation(GameTestHelper helper) {
            Fixture f = fixture(helper, 1);
            ServerPlayer other = MockGameTestPlayers.makeMockSurvivalServerPlayerWithChannel(helper);
            try {
                mushroomStew(f.pot, 1);
                other.setPos(f.pos.getX() + .5, f.pos.getY() + .5, f.pos.getZ() + 1.5);
                other.containerMenu = f.pot.createMenu(42, other.getInventory(), other);
                long before = balance(f.player), otherBefore = balance(other);
                helper.assertTrue(f.pot.isHeated() && PotCraftService.start(f.player, f.pot), "Heated real pot starts mushroom stew");
                helper.assertTrue(balance(f.player) == before - ChefConfig.seasoningCreditCost(), "Start charges exactly one fee");
                helper.assertTrue(f.pot.getInventory().getStackInSlot(0).isEmpty()
                        && f.pot.getInventory().getStackInSlot(1).isEmpty(), "Ingredients are reserved outside visible slots");
                other.containerMenu.clicked(7, 0, ClickType.PICKUP, other);
                helper.assertTrue(other.containerMenu.getCarried().isEmpty()
                        && f.pot.getInventory().getStackInSlot(CookingPotBlockEntity.CONTAINER_SLOT).getCount() == 1,
                        "A second player cannot remove the bowl during a round");
                var hopper = f.pot.getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.DOWN).resolve().orElseThrow();
                for (int i = 0; i < hopper.getSlots(); i++) {
                    helper.assertTrue(hopper.extractItem(i, 64, false).isEmpty(), "Hopper cannot extract reserved ingredients");
                }
                for (int i = 0; i < 220; i++) CookingPotBlockEntity.cookingTick(helper.getLevel(), f.pos, f.pot.getBlockState(), f.pot);
                helper.assertTrue(f.pot.getMeal().isEmpty() && !PotCraftService.start(other, f.pot)
                        && balance(other) == otherBefore, "Native cooking and another player cannot consume or charge the reservation");
                PotCraftService.abort(f.player, f.pot, PotCraftService.roundId(f.pot), "test abandon");
                helper.succeed();
            } finally { MockGameTestPlayers.logout(other); MockGameTestPlayers.logout(f.player); }
        }

        private static void closingMenuSettlesHomeDish(GameTestHelper helper) {
            Fixture f = fixture(helper, 1), broken = fixture(helper, 3);
            try {
                mushroomStew(f.pot, 2);
                long before = balance(f.player), beforeXp = xp(f.player);
                helper.assertTrue(PotCraftService.start(f.player, f.pot), "Manual mushroom stew starts");
                f.player.closeContainer();
                PotCraftService.tick(f.pot);
                ItemStack meal = f.pot.getMeal();
                helper.assertTrue(meal.is(Items.MUSHROOM_STEW) && meal.getCount() == 1 && !ChefQualityNbt.hasQuality(meal),
                        "Closing the menu settles exactly one ordinary dish");
                helper.assertTrue(f.pot.getInventory().getStackInSlot(0).getCount() == 1
                        && f.pot.getInventory().getStackInSlot(1).getCount() == 1, "Unreserved ingredients remain");
                helper.assertTrue(balance(f.player) == before - ChefConfig.seasoningCreditCost() && xp(f.player) == beforeXp,
                        "Abandonment keeps the fee and grants no chef XP");
                CookingPotBlockEntity.cookingTick(helper.getLevel(), f.pos, f.pot.getBlockState(), f.pot);
                helper.assertTrue(f.pot.getInventory().getStackInSlot(CookingPotBlockEntity.OUTPUT_SLOT).is(Items.MUSHROOM_STEW)
                        && f.pot.getInventory().getStackInSlot(CookingPotBlockEntity.OUTPUT_SLOT).getCount() == 1
                        && f.pot.getInventory().getStackInSlot(CookingPotBlockEntity.CONTAINER_SLOT).getCount() == 1,
                        "Native serving consumes one bowl and produces one serving");
                f.pot.getInventory().extractItem(CookingPotBlockEntity.OUTPUT_SLOT, 1, false);
                for (int i = 0; i < 201; i++) CookingPotBlockEntity.cookingTick(helper.getLevel(), f.pos, f.pot.getBlockState(), f.pot);
                helper.assertTrue(f.pot.getInventory().getStackInSlot(CookingPotBlockEntity.OUTPUT_SLOT).is(Items.MUSHROOM_STEW)
                        && f.pot.getInventory().getStackInSlot(0).isEmpty() && f.pot.getInventory().getStackInSlot(1).isEmpty()
                        && balance(f.player) == before - ChefConfig.seasoningCreditCost() && xp(f.player) == beforeXp,
                        "Native home cooking resumes for the remaining ingredients without another fee or XP");
                mushroomStew(broken.pot, 2);
                helper.assertTrue(PotCraftService.start(broken.player, broken.pot), "Second pot starts before it is broken");
                broken.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_PICKAXE));
                helper.assertTrue(broken.player.gameMode.destroyBlock(broken.pos), "Survival player breaks an active pot");
                List<ItemEntity> drops = helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(broken.pos).inflate(1));
                int stew = 0, brown = 0, red = 0, bowls = 0;
                for (ItemEntity entity : drops) {
                    ItemStack stack = entity.getItem();
                    if (stack.is(Items.MUSHROOM_STEW)) stew += stack.getCount();
                    if (stack.is(Items.BROWN_MUSHROOM)) brown += stack.getCount();
                    if (stack.is(Items.RED_MUSHROOM)) red += stack.getCount();
                    if (stack.is(Items.BOWL)) bowls += stack.getCount();
                }
                helper.assertTrue(stew == 0 && storedStewDrops(helper, broken.pos) == 1 && countPotDrops(helper, broken.pos) == 1
                                && brown == 1 && red == 1 && bowls == 2,
                        "Native loot retains one home dish in one pot and drops the unused ingredients and bowls exactly once");
                helper.succeed();
            } finally { MockGameTestPlayers.logout(f.player); MockGameTestPlayers.logout(broken.player); }
        }

        private static void reloadReturnsInputsAndRefundsOnce(GameTestHelper helper) {
            Fixture f = fixture(helper, 1);
            try {
                mushroomStew(f.pot, 2);
                long before = balance(f.player);
                helper.assertTrue(PotCraftService.start(f.player, f.pot), "Start reserves ingredients before save");
                CompoundTag saved = f.pot.saveWithoutMetadata();
                helper.assertTrue(balance(f.player) == before - ChefConfig.seasoningCreditCost(), "Fee charged before reload");
                f.pot.setRemoved();
                CookingPotBlockEntity restored = new CookingPotBlockEntity(f.pos, f.pot.getBlockState());
                restored.load(saved); helper.getLevel().setBlockEntity(restored); PotCraftService.ensureHydrated(restored);
                helper.assertTrue(restored.getInventory().getStackInSlot(0).getCount() == 2
                        && restored.getInventory().getStackInSlot(1).getCount() == 2 && restored.getMeal().isEmpty(),
                        "Interrupted save returns all ingredients without a dish");
                helper.assertTrue(balance(f.player) == before, "Interrupted save refunds the fee");
                restored.load(saved); PotCraftService.ensureHydrated(restored);
                helper.assertTrue(balance(f.player) == before && restored.getInventory().getStackInSlot(0).getCount() == 2
                        && restored.getInventory().getStackInSlot(1).getCount() == 2 && restored.getMeal().isEmpty(),
                        "Repeated load cannot duplicate ingredients, dish or refund");
                helper.succeed();
            } finally { MockGameTestPlayers.logout(f.player); }
        }

        private static void removedMenuCannotRestoreDroppedInventory(GameTestHelper helper) {
            Fixture f = fixture(helper, 1);
            try {
                mushroomStew(f.pot, 2);
                helper.assertTrue(PotCraftService.start(f.player, f.pot), "Start a pot before breaking it with its menu open");
                var oldMenu = f.player.containerMenu;
                helper.assertTrue(helper.getLevel().destroyBlock(f.pos, false), "Break the pot while its menu remains open");
                CompoundTag tombstone = FryerPlayerData.get(helper.getLevel()).checkpoint(helper.getLevel(), f.pos);
                helper.assertTrue(tombstone != null && tombstone.getBoolean("FryerRemoved")
                                && tombstone.getCompound("Inventory").getList("Items", Tag.TAG_COMPOUND).isEmpty(),
                        "Breaking leaves a durable empty tombstone until the chunk is stored");
                oldMenu.broadcastChanges();
                helper.assertTrue(tombstone.equals(FryerPlayerData.get(helper.getLevel()).checkpoint(helper.getLevel(), f.pos)),
                        "Synchronizing the removed menu must not replace the empty tombstone with dropped inventory");
                helper.getLevel().setBlockAndUpdate(f.pos, f.pot.getBlockState());
                CookingPotBlockEntity replacement = (CookingPotBlockEntity) helper.getLevel().getBlockEntity(f.pos);
                oldMenu.clicked(0, 0, ClickType.PICKUP, f.player);
                oldMenu.broadcastChanges();
                helper.assertTrue(f.player.containerMenu.getCarried().isEmpty(), "An old menu cannot take dropped ingredients");
                for (int i = 0; i < replacement.getInventory().getSlots(); i++) {
                    helper.assertTrue(replacement.getInventory().getStackInSlot(i).isEmpty(),
                            "A replacement pot must not inherit items already dropped by the old pot");
                }
                f.player.tick();
                helper.assertTrue(f.player.containerMenu == f.player.inventoryMenu, "Player tick closes the removed pot menu");
                helper.succeed();
            } finally { MockGameTestPlayers.logout(f.player); }
        }

        private static void pickingPotDoesNotSettleOrSuppressMealDrop(GameTestHelper helper) {
            Fixture f = fixture(helper, 1);
            try {
                ItemStack emptyPreview = f.pot.getBlockState().getBlock().getCloneItemStack(helper.getLevel(), f.pos, f.pot.getBlockState());
                ItemStack nativePot = new ItemStack(ForgeRegistries.ITEMS.getValue(new ResourceLocation("farmersdelight", "cooking_pot")));
                helper.assertTrue(ItemStack.isSameItemSameTags(emptyPreview, nativePot),
                        "An empty pot preview must remain a plain native item without checkpoint NBT");
                mushroomStew(f.pot, 2);
                helper.assertTrue(PotCraftService.start(f.player, f.pot), "Start a pot before requesting its pick-block preview");
                long round = PotCraftService.roundId(f.pot), charged = balance(f.player), beforeXp = xp(f.player);
                ItemStack preview = f.pot.getBlockState().getBlock().getCloneItemStack(helper.getLevel(), f.pos, f.pot.getBlockState());
                helper.assertTrue(PotCraftService.roundId(f.pot) == round && f.pot.getMeal().isEmpty(),
                        "Picking a block must not settle an active manual round");
                CompoundTag previewData = preview.getTagElement("BlockEntityTag");
                helper.assertTrue(previewData == null || !previewData.contains("WokPotRound"),
                        "Pick-block previews must not copy the live transaction or reserved ingredients");
                helper.assertTrue(previewData == null || !previewData.contains("FryerRevision"),
                        "Pick-block previews must not copy a block checkpoint revision");
                helper.assertTrue(balance(f.player) == charged && xp(f.player) == beforeXp,
                        "Previewing cannot complete, refund or reward the active round");
                PotCraftService.abort(f.player, f.pot, round, "test abandon");
                f.pot.getBlockState().getBlock().getCloneItemStack(helper.getLevel(), f.pos, f.pot.getBlockState());
                f.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_PICKAXE));
                helper.assertTrue(f.player.gameMode.destroyBlock(f.pos), "Break a pot after previewing the finished meal");
                helper.assertTrue(countPotDrops(helper, f.pos) == 1 && storedStewDrops(helper, f.pos) == 1,
                        "A previous preview must not suppress the later native pot and meal drop");
                Fixture empty = fixture(helper, 3);
                try {
                    empty.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_PICKAXE));
                    helper.assertTrue(empty.player.gameMode.destroyBlock(empty.pos), "Break an empty pot through the native player path");
                    ItemStack drop = helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(empty.pos).inflate(1)).stream()
                            .map(ItemEntity::getItem).filter(item -> item.is(nativePot.getItem())).findFirst().orElseThrow();
                    helper.assertTrue(ItemStack.isSameItemSameTags(drop, nativePot),
                            "An empty pot loot drop must remain a plain native item without checkpoint NBT");
                } finally { MockGameTestPlayers.logout(empty.player); }
                helper.succeed();
            } finally { MockGameTestPlayers.logout(f.player); }
        }

        private static void playerBreakDoesNotDuplicatePotWithoutBowls(GameTestHelper helper) {
            Fixture f = fixture(helper, 1);
            try {
                mushroomStew(f.pot, 1);
                f.pot.getInventory().setStackInSlot(CookingPotBlockEntity.CONTAINER_SLOT, ItemStack.EMPTY);
                long before = balance(f.player), beforeXp = xp(f.player);
                for (int i = 0; i < 201; i++) CookingPotBlockEntity.cookingTick(helper.getLevel(), f.pos, f.pot.getBlockState(), f.pot);
                helper.assertTrue(f.pot.getMeal().is(Items.MUSHROOM_STEW), "Native home cooking leaves a meal in a pot without bowls");
                int beforePots = countPotDrops(helper, f.pos);
                f.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_PICKAXE));
                helper.assertTrue(f.player.gameMode.destroyBlock(f.pos), "Break the pot through the real survival player path");
                helper.assertTrue(countPotDrops(helper, f.pos) - beforePots == 1,
                        "A survival player breaking a full pot without bowls must receive exactly one pot");
                helper.assertTrue(storedStewDrops(helper, f.pos) == 1, "The one dropped pot retains exactly one home-cooked meal");
                helper.assertTrue(balance(f.player) == before && xp(f.player) == beforeXp, "Native home cooking and breaking remain free of chef rewards");
                ItemEntity droppedPot = helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(f.pos).inflate(1)).stream()
                        .filter(entity -> ForgeRegistries.ITEMS.getKey(entity.getItem().getItem()).equals(new ResourceLocation("farmersdelight", "cooking_pot")))
                        .findFirst().orElseThrow();
                helper.assertTrue(!droppedPot.getItem().getTagElement("BlockEntityTag").contains("FryerRevision"),
                        "A carried meal must not retain the source block's checkpoint revision");
                f.player.setItemInHand(InteractionHand.MAIN_HAND, droppedPot.getItem().copy()); droppedPot.discard();
                var placed = f.player.getMainHandItem().useOn(new UseOnContext(f.player, InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(f.pos.below()).add(0, .5, 0), Direction.UP, f.pos.below(), false)));
                helper.assertTrue(placed.consumesAction() && helper.getLevel().getBlockEntity(f.pos) instanceof CookingPotBlockEntity,
                        "The dropped pot can be placed back through its real item path");
                CookingPotBlockEntity replacement = (CookingPotBlockEntity) helper.getLevel().getBlockEntity(f.pos);
                helper.assertTrue(replacement.getMeal().is(Items.MUSHROOM_STEW) && replacement.getMeal().getCount() == 1,
                        "Placing a carried meal supersedes the old removal tombstone without losing or duplicating food");
                pendingServingsSurviveNativeLoot(helper);
                helper.succeed();
            } finally { MockGameTestPlayers.logout(f.player); }
        }

        private static void pendingServingsSurviveNativeLoot(GameTestHelper helper) {
            Fixture f = fixture(helper, 3);
            try {
                CompoundTag snapshot = f.pot.saveWithoutMetadata();
                var inventory = new net.minecraftforge.items.ItemStackHandler(9);
                ItemStack radiant = new ItemStack(Items.MUSHROOM_STEW), extra = new ItemStack(Items.MUSHROOM_STEW);
                ChefQualityNbt.stamp(radiant, ChefQuality.RADIANT, List.of());
                ChefQualityNbt.stamp(extra, ChefQuality.EXTRAORDINARY, List.of());
                inventory.setStackInSlot(CookingPotBlockEntity.MEAL_DISPLAY_SLOT, radiant);
                snapshot.put("Inventory", inventory.serializeNBT());
                snapshot.put("Container", new ItemStack(Items.BOWL).save(new CompoundTag()));
                ListTag pending = new ListTag(); pending.add(extra.save(new CompoundTag())); snapshot.put("WokPotPending", pending);
                FryerPlayerData data = FryerPlayerData.get(helper.getLevel());
                data.checkpoint(helper.getLevel(), f.pos, snapshot); data.sync();
                f.pot.load(snapshot);
                f.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_PICKAXE));
                helper.assertTrue(f.player.gameMode.destroyBlock(f.pos), "Break a recovered radiant batch with a pending serving");
                helper.assertTrue(countPotDrops(helper, f.pos) == 1 && storedStewDrops(helper, f.pos) == 1,
                        "Native loot keeps the displayed serving in exactly one pot");
                int savedPending = 0;
                for (ItemEntity entity : helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(f.pos).inflate(1))) {
                    CompoundTag meal = entity.getItem().getTagElement("BlockEntityTag");
                    if (meal == null) continue;
                    for (Tag entry : meal.getList("WokPotPending", Tag.TAG_COMPOUND)) {
                        ItemStack serving = ItemStack.of((CompoundTag) entry);
                        helper.assertTrue(serving.is(Items.MUSHROOM_STEW) && ChefQualityNbt.hasQuality(serving),
                                "The pending serving keeps its dish and quality after native loot serializes the removed pot");
                        savedPending += serving.getCount();
                    }
                }
                helper.assertTrue(savedPending == 1, "Native loot retains exactly one pending serving after block-entity removal");
            } finally { MockGameTestPlayers.logout(f.player); }
        }

        private static int countPotDrops(GameTestHelper helper, BlockPos pos) {
            return helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(1)).stream()
                    .filter(entity -> ForgeRegistries.ITEMS.getKey(entity.getItem().getItem()).equals(new ResourceLocation("farmersdelight", "cooking_pot")))
                    .mapToInt(entity -> entity.getItem().getCount()).sum();
        }

        private static int storedStewDrops(GameTestHelper helper, BlockPos pos) {
            int count = 0;
            for (ItemEntity entity : helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(1))) {
                ItemStack item = entity.getItem();
                if (!ForgeRegistries.ITEMS.getKey(item.getItem()).equals(new ResourceLocation("farmersdelight", "cooking_pot"))) continue;
                CompoundTag meal = item.getTagElement("BlockEntityTag");
                if (meal == null) continue;
                var inventory = new net.minecraftforge.items.ItemStackHandler(9);
                inventory.deserializeNBT(meal.getCompound("Inventory"));
                ItemStack stew = inventory.getStackInSlot(CookingPotBlockEntity.MEAL_DISPLAY_SLOT);
                if (stew.is(Items.MUSHROOM_STEW)) count += stew.getCount();
            }
            return count;
        }

        private static void remainderAndFeastYieldArePreserved(GameTestHelper helper) {
            Fixture cocoa = fixture(helper, 1), feast = fixture(helper, 3);
            ServerPlayer dumplingCook = MockGameTestPlayers.makeMockSurvivalServerPlayerWithChannel(helper);
            try {
                var ci = cocoa.pot.getInventory();
                ci.setStackInSlot(0, new ItemStack(Items.MILK_BUCKET));
                ci.setStackInSlot(1, new ItemStack(Items.SUGAR));
                ci.setStackInSlot(2, new ItemStack(Items.COCOA_BEANS));
                ci.setStackInSlot(3, new ItemStack(Items.COCOA_BEANS));
                helper.assertTrue(PotCraftService.start(cocoa.player, cocoa.pot), "Real hot cocoa recipe starts");
                PotCraftService.abort(cocoa.player, cocoa.pot, PotCraftService.roundId(cocoa.pot), "test abandon");
                int buckets = 0;
                for (int i = 0; i < 6; i++) if (ci.getStackInSlot(i).is(Items.BUCKET)) buckets += ci.getStackInSlot(i).getCount();
                helper.assertTrue(buckets == 1 && cocoa.pot.getMeal().getCount() == 1
                        && ForgeRegistries.ITEMS.getKey(cocoa.pot.getMeal().getItem()).equals(new ResourceLocation("farmersdelight", "hot_cocoa")),
                        "Milk yields one empty bucket and one cocoa without duplication");
                var fi = feast.pot.getInventory();
                fi.setStackInSlot(0, new ItemStack(ForgeRegistries.ITEMS.getValue(new ResourceLocation("farmersdelight", "rice"))));
                fi.setStackInSlot(1, new ItemStack(ForgeRegistries.ITEMS.getValue(new ResourceLocation("farmersdelight", "onion"))));
                fi.setStackInSlot(2, new ItemStack(Items.BROWN_MUSHROOM));
                fi.setStackInSlot(3, new ItemStack(Items.POTATO));
                fi.setStackInSlot(4, new ItemStack(Items.SWEET_BERRIES));
                fi.setStackInSlot(5, new ItemStack(Items.CARROT));
                fi.setStackInSlot(CookingPotBlockEntity.CONTAINER_SLOT, new ItemStack(Items.PUMPKIN));
                helper.assertTrue(PotCraftService.start(feast.player, feast.pot), "Real stuffed pumpkin feast recipe starts");
                PotCraftService.abort(feast.player, feast.pot, PotCraftService.roundId(feast.pot), "test abandon");
                ItemStack product = feast.pot.getMeal();
                helper.assertTrue(product.getCount() == 1 && ForgeRegistries.ITEMS.getKey(product.getItem())
                        .equals(new ResourceLocation("farmersdelight", "stuffed_pumpkin_block")), "One whole feast is produced");
                ci.extractItem(0, 64, false);
                ci.extractItem(CookingPotBlockEntity.MEAL_DISPLAY_SLOT, 64, false);
                ci.setStackInSlot(0, new ItemStack(ForgeRegistries.ITEMS.getValue(new ResourceLocation("farmersdelight", "wheat_dough"))));
                ci.setStackInSlot(1, new ItemStack(ForgeRegistries.ITEMS.getValue(new ResourceLocation("farmersdelight", "cabbage"))));
                ci.setStackInSlot(2, new ItemStack(ForgeRegistries.ITEMS.getValue(new ResourceLocation("farmersdelight", "onion"))));
                ci.setStackInSlot(3, new ItemStack(Items.BROWN_MUSHROOM));
                dumplingCook.setPos(cocoa.pos.getX() + .5, cocoa.pos.getY() + .5, cocoa.pos.getZ() + 1.5);
                dumplingCook.containerMenu = cocoa.pot.createMenu(43, dumplingCook.getInventory(), dumplingCook);
                EconomyServices.economyService().grant(dumplingCook, Currency.CREDIT, 1000);
                helper.assertTrue(PotCraftService.start(dumplingCook, cocoa.pot), "Real two-output dumpling recipe starts");
                PotCraftService.abort(dumplingCook, cocoa.pot, PotCraftService.roundId(cocoa.pot), "test abandon");
                ItemStack dumplings = cocoa.pot.getMeal();
                helper.assertTrue(dumplings.getCount() == 2 && ForgeRegistries.ITEMS.getKey(dumplings.getItem())
                        .equals(new ResourceLocation("farmersdelight", "dumplings")), "One recipe yields exactly two dumplings");
                helper.succeed();
            } finally { MockGameTestPlayers.logout(cocoa.player); MockGameTestPlayers.logout(feast.player); MockGameTestPlayers.logout(dumplingCook); }
        }

        private static long balance(ServerPlayer player) {
            return EconomyServices.economyService().creditBalance(player);
        }

        private static long xp(ServerPlayer player) {
            return JobServices.jobService().totalXp(player, JobId.CHEF);
        }
    }
}
