package com.miningdim.job.munitions.block;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.munitions.ModMunitionsBlocks;
import com.miningdim.job.munitions.ModMunitionsItems;
import com.miningdim.job.munitions.MunitionsAmmoFactory;
import com.miningdim.job.munitions.MunitionsConfig;
import com.miningdim.job.munitions.gunsmith.GunsmithBaseStats;
import com.miningdim.job.munitions.gunsmith.GunsmithAssemblyRecipe;
import com.miningdim.job.munitions.gunsmith.GunsmithBlueprint;
import com.miningdim.job.munitions.gunsmith.GunsmithBlueprintItem;
import com.miningdim.job.munitions.gunsmith.GunsmithFireModePolicy;
import com.miningdim.job.munitions.gunsmith.GunsmithGunFactory;
import com.miningdim.job.munitions.gunsmith.GunsmithGunDurability;
import com.miningdim.job.munitions.gunsmith.GunsmithGunStats;
import com.miningdim.job.munitions.gunsmith.GunsmithGunTooltip;
import com.miningdim.job.munitions.gunsmith.GunsmithPartItem;
import com.miningdim.job.munitions.gunsmith.GunsmithPartQuality;
import com.miningdim.job.munitions.gunsmith.GunsmithPartVariant;
import com.miningdim.job.munitions.gunsmith.GunsmithPlatform;
import com.miningdim.job.munitions.gunsmith.GunsmithPressPart;
import com.miningdim.job.munitions.gunsmith.GunsmithStatMultipliers;
import com.miningdim.job.munitions.gunsmith.GunsmithTaczBridge;
import com.miningdim.job.munitions.menu.GunsmithAssemblyMenu;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.items.ItemStackHandler;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class GunsmithAssemblyBusinessGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "gunsmith_assembly";
    private static final BlockPos MAIN_REL = new BlockPos(1, 1, 1);
    private static final GunsmithBaseStats M4_BASE_STATS =
            new GunsmithBaseStats(6.5D, 1.5D, 48.0D, 0.16D);
    private static final GunsmithBaseStats AK47_BASE_STATS =
            new GunsmithBaseStats(9.0D, 1.5D, 52.0D, 0.20D);
    private static final GunsmithBaseStats M1911_BASE_STATS =
            new GunsmithBaseStats(11.0D, 1.5D, 19.0D, 0.08D);

    private GunsmithAssemblyBusinessGameTests() {
    }

    @BeforeBatch(batch = BATCH)
    public static void useIsolatedAssemblyEconomyBaseline(ServerLevel level) {
        MunitionsConfig.ASSEMBLY_UNLOCK_LEVEL.set(0);
        MunitionsConfig.ASSEMBLY_WORK_FEE_CREDITS.set(0);
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH, timeoutTicks = 20)
    public static void completeAssemblyConsumesPartsAndOutputsStampedGunAfterAnimation(GameTestHelper helper) {
        Direction facing = Direction.NORTH;
        placeStructure(helper, facing);
        GunsmithAssemblyBenchBlockEntity be = requireBench(helper);
        fillCompleteRecipe(be, GunsmithBlueprint.M4A1);
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        boolean previousEnabled = MunitionsConfig.GUNSMITH_ENABLED.get();

        try {
            MunitionsConfig.GUNSMITH_ENABLED.set(true);
            helper.assertTrue(be.tryStartAssembly(player, new ItemStack(Items.IRON_HOE), 6),
                    "complete recipe must start assembly");
            helper.assertTrue(be.inventory().getStackInSlot(GunsmithAssemblyBenchBlockEntity.SLOT_BLUEPRINT)
                            .is(ModMunitionsItems.GUNSMITH_BLUEPRINT.get()),
                    "assembly blueprint must remain in its slot");
            for (GunsmithPressPart part : GunsmithPressPart.values()) {
                helper.assertTrue(be.inventory().getStackInSlot(
                                GunsmithAssemblyBenchBlockEntity.slotForPart(part)).isEmpty(),
                        part + " must be consumed when assembly starts");
            }
            helper.assertTrue(be.inventory().getStackInSlot(GunsmithAssemblyBenchBlockEntity.SLOT_OUTPUT).isEmpty(),
                    "output must remain empty while the arms are moving");
            assertStructureActive(helper, facing, true);

            helper.runAfterDelay(8, () -> {
                assertStructureActive(helper, facing, false);
                ItemStack output = be.inventory().getStackInSlot(GunsmithAssemblyBenchBlockEntity.SLOT_OUTPUT);
                helper.assertTrue(output.is(Items.IRON_HOE), "animation completion must deliver the stamped base item");
                GunsmithGunStats stats = GunsmithGunStats.from(output);
                helper.assertTrue(stats != null, "finished gun must carry gunsmith NBT");
                assertClose(helper, stats.damage(), 1.20D, "damage coefficient");
                assertClose(helper, stats.headshot(), 1.10D, "headshot coefficient");
                assertClose(helper, stats.range(), 1.04D, "range coefficient");
                assertClose(helper, stats.recoil(), 1.08D, "recoil coefficient");
                assertClose(helper, stats.spread(), 1.30D, "spread coefficient");
                assertClose(helper, stats.handling(), 1.40D, "handling coefficient");
                assertClose(helper, stats.average(), 7.12D / 6.0D, "average coefficient");
                helper.assertTrue(stats.gunId().equals(GunsmithBlueprint.M4A1.gunId()),
                        "M4 blueprint must stamp the original TaCZ M4A1 id");
                assertClose(helper, stats.effectiveDamage(M4_BASE_STATS), 7.80D, "effective damage");
                assertClose(helper, stats.effectiveHeadshot(M4_BASE_STATS), 1.65D, "effective headshot");
                assertClose(helper, stats.effectiveRange(M4_BASE_STATS), 49.92D, "effective range");
                assertClose(helper, stats.effectiveAdsTime(M4_BASE_STATS),
                        0.16D / 1.40D, "effective ADS time");
                helper.succeed();
            });
        } finally {
            MunitionsConfig.GUNSMITH_ENABLED.set(previousEnabled);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH, timeoutTicks = 20)
    public static void inProgressAssemblySurvivesSaveLoadRoundTripAndStillDelivers(GameTestHelper helper) {
        placeStructure(helper, Direction.NORTH);
        GunsmithAssemblyBenchBlockEntity be = requireBench(helper);
        fillCompleteRecipe(be, GunsmithBlueprint.M4A1);
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        boolean previousEnabled = MunitionsConfig.GUNSMITH_ENABLED.get();

        try {
            MunitionsConfig.GUNSMITH_ENABLED.set(true);
            helper.assertTrue(be.tryStartAssembly(player, new ItemStack(Items.IRON_HOE), 6),
                    "complete recipe must start assembly");

            // 动画进行中存盘再读回 (模拟区块卸载/服务器重启): pendingResult + animationEndTick 必须往返无损。(审查 TQ-3)
            CompoundTag saved = be.saveWithoutMetadata();
            helper.assertTrue(saved.contains("PendingResult", Tag.TAG_COMPOUND),
                    "in-progress assembly must persist its pending result");
            be.load(saved);
            helper.assertTrue(be.isAnimating(), "reloaded assembly must still be animating");
            helper.assertTrue(be.inventory().getStackInSlot(GunsmithAssemblyBenchBlockEntity.SLOT_OUTPUT).isEmpty(),
                    "reloaded assembly must not deliver before the animation ends");

            helper.runAfterDelay(8, () -> {
                ItemStack output = be.inventory().getStackInSlot(GunsmithAssemblyBenchBlockEntity.SLOT_OUTPUT);
                helper.assertTrue(output.is(Items.IRON_HOE),
                        "assembly must still deliver the stamped gun after a save/load round trip");
                GunsmithGunStats stats = GunsmithGunStats.from(output);
                helper.assertTrue(stats != null, "delivered gun must carry gunsmith NBT after reload");
                assertClose(helper, stats.damage(), 1.20D, "round-tripped damage coefficient");
                helper.succeed();
            });
        } finally {
            MunitionsConfig.GUNSMITH_ENABLED.set(previousEnabled);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void incompleteAssemblyPreservesEveryInsertedItem(GameTestHelper helper) {
        placeStructure(helper, Direction.NORTH);
        GunsmithAssemblyBenchBlockEntity be = requireBench(helper);
        fillCompleteRecipe(be, GunsmithBlueprint.M4A1);
        be.inventory().setStackInSlot(
                GunsmithAssemblyBenchBlockEntity.slotForPart(GunsmithPressPart.GRIP), ItemStack.EMPTY);
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        boolean previousEnabled = MunitionsConfig.GUNSMITH_ENABLED.get();

        try {
            MunitionsConfig.GUNSMITH_ENABLED.set(true);
            helper.assertFalse(be.tryStartAssembly(player, new ItemStack(Items.IRON_HOE), 6),
                    "missing grip must reject assembly");
            helper.assertFalse(be.isAnimating(), "rejected assembly must not animate");
            helper.assertTrue(be.inventory().getStackInSlot(GunsmithAssemblyBenchBlockEntity.SLOT_OUTPUT).isEmpty(),
                    "rejected assembly must not create output");
            for (GunsmithPressPart part : GunsmithBlueprint.M4A1.requiredParts()) {
                ItemStack stack = be.inventory().getStackInSlot(GunsmithAssemblyBenchBlockEntity.slotForPart(part));
                helper.assertTrue(part == GunsmithPressPart.GRIP ? stack.isEmpty() : !stack.isEmpty(),
                        "rejected assembly must preserve the exact input state for " + part);
            }
        } finally {
            MunitionsConfig.GUNSMITH_ENABLED.set(previousEnabled);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void partSlotsRequireBlueprintAndMatchPartAndPlatform(GameTestHelper helper) {
        placeStructure(helper, Direction.NORTH);
        GunsmithAssemblyBenchBlockEntity be = requireBench(helper);
        int coreSlot = GunsmithAssemblyBenchBlockEntity.slotForPart(GunsmithPressPart.CORE);
        ItemStack wrongPart = part(GunsmithPlatform.AR, GunsmithPressPart.BARREL,
                GunsmithPartQuality.COMMON, 1.00D);
        ItemStack wrongPlatform = part(GunsmithPlatform.AK, GunsmithPressPart.CORE,
                GunsmithPartQuality.COMMON, 1.00D);
        ItemStack correct = part(GunsmithPlatform.AR, GunsmithPressPart.CORE,
                GunsmithPartQuality.COMMON, 1.00D);

        helper.assertFalse(be.isPartSlotVisible(GunsmithPressPart.CORE),
                "an empty part slot must be hidden before a blueprint is selected");
        helper.assertTrue(be.inventory().insertItem(coreSlot, correct, false).getCount() == 1,
                "part slots must reject parts before a blueprint is selected");

        be.inventory().setStackInSlot(GunsmithAssemblyBenchBlockEntity.SLOT_BLUEPRINT,
                GunsmithBlueprintItem.createStack(ModMunitionsItems.GUNSMITH_BLUEPRINT.get(), GunsmithBlueprint.M4A1));
        helper.assertTrue(be.isPartSlotVisible(GunsmithPressPart.CORE),
                "a required part slot must be visible after selecting a blueprint");
        helper.assertTrue(be.inventory().insertItem(coreSlot, wrongPart, false).getCount() == 1,
                "core slot must reject an AR barrel after selecting an M4 blueprint");
        helper.assertTrue(be.inventory().insertItem(coreSlot, wrongPlatform, false).getCount() == 1,
                "M4 blueprint core slot must reject an AK core");
        helper.assertTrue(be.inventory().insertItem(coreSlot, correct, false).isEmpty(),
                "M4 blueprint core slot must accept the matching AR core");
        helper.assertTrue(be.inventory().getStackInSlot(coreSlot).getCount() == 1,
                "accepted core must occupy exactly one slot item");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void occupiedLegacyPartSlotStaysHiddenWithoutBlueprintAndRemovable(GameTestHelper helper) {
        placeStructure(helper, Direction.NORTH);
        GunsmithAssemblyBenchBlockEntity be = requireBench(helper);
        int coreSlot = GunsmithAssemblyBenchBlockEntity.slotForPart(GunsmithPressPart.CORE);
        ItemStack legacyCore = part(GunsmithPlatform.AK, GunsmithPressPart.CORE,
                GunsmithPartQuality.COMMON, 1.00D);

        be.inventory().setStackInSlot(coreSlot, legacyCore);
        helper.assertFalse(be.isPartSlotVisible(GunsmithPressPart.CORE),
                "a part slot must stay hidden until a blueprint is inserted");
        helper.assertTrue(be.inventory().extractItem(coreSlot, 1, false).is(legacyCore.getItem()),
                "a hidden legacy part slot must remain removable through inventory recovery");
        helper.assertFalse(be.isPartSlotVisible(GunsmithPressPart.CORE),
                "an emptied part slot must hide again without a blueprint");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void rifleOnlyInventoryMigratesBeforeBlueprintMenuSync(GameTestHelper helper) {
        placeStructure(helper, Direction.NORTH);
        GunsmithAssemblyBenchBlockEntity be = requireBench(helper);

        int legacyOutputSlot = 7;
        ItemStackHandler legacyInventory = new ItemStackHandler(8);
        legacyInventory.setStackInSlot(GunsmithAssemblyBenchBlockEntity.SLOT_BLUEPRINT,
                GunsmithBlueprintItem.createStack(ModMunitionsItems.GUNSMITH_BLUEPRINT.get(),
                        GunsmithBlueprint.M4A1));
        for (GunsmithPressPart part : GunsmithBlueprint.M4A1.requiredParts()) {
            if (part == GunsmithPressPart.RECEIVER) {
                continue;
            }
            legacyInventory.setStackInSlot(GunsmithAssemblyBenchBlockEntity.slotForPart(part),
                    part(GunsmithPlatform.AR, part, GunsmithPartQuality.COMMON, 1.00D));
        }
        legacyInventory.setStackInSlot(legacyOutputSlot, new ItemStack(Items.DIAMOND));

        CompoundTag savedBench = new CompoundTag();
        savedBench.put("Inventory", legacyInventory.serializeNBT());
        be.load(savedBench);

        helper.assertTrue(be.inventory().getSlots() == GunsmithAssemblyBenchBlockEntity.SLOT_COUNT,
                "rifle-only save must expand to the current fixed inventory size");
        helper.assertTrue(be.inventory().getStackInSlot(GunsmithAssemblyBenchBlockEntity.SLOT_BLUEPRINT)
                        .is(ModMunitionsItems.GUNSMITH_BLUEPRINT.get()),
                "migration must preserve the blueprint");
        for (GunsmithPressPart part : GunsmithBlueprint.M4A1.requiredParts()) {
            if (part == GunsmithPressPart.RECEIVER) {
                continue;
            }
            helper.assertTrue(GunsmithAssemblyRecipe.matchesPart(
                            be.inventory().getStackInSlot(GunsmithAssemblyBenchBlockEntity.slotForPart(part)),
                            part, GunsmithPlatform.AR),
                    "migration must preserve rifle part " + part.id());
        }
        for (GunsmithPressPart newerPart : List.of(
                GunsmithPressPart.SLIDE, GunsmithPressPart.TRIGGER, GunsmithPressPart.HAMMER,
                GunsmithPressPart.RECEIVER)) {
            helper.assertTrue(be.inventory().getStackInSlot(
                            GunsmithAssemblyBenchBlockEntity.slotForPart(newerPart)).isEmpty(),
                    "rifle-only migration must leave newer part slot empty: " + newerPart.id());
        }
        helper.assertTrue(be.inventory().getStackInSlot(GunsmithAssemblyBenchBlockEntity.SLOT_OUTPUT)
                        .is(Items.DIAMOND),
                "migration must move legacy slot 7 into the current output slot");

        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        GunsmithAssemblyMenu menu = new GunsmithAssemblyMenu(1, player.getInventory(), be.getBlockPos());
        menu.broadcastChanges();
        helper.assertTrue(menu.getSlot(GunsmithAssemblyBenchBlockEntity.SLOT_OUTPUT).getItem().is(Items.DIAMOND),
                "the first menu synchronization after loading a blueprint must read every slot safely");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void preReceiverInventoryMigratesAndMenuSynchronizes(GameTestHelper helper) {
        placeStructure(helper, Direction.NORTH);
        GunsmithAssemblyBenchBlockEntity be = requireBench(helper);

        ItemStackHandler legacyInventory = new ItemStackHandler(11);
        legacyInventory.setStackInSlot(GunsmithAssemblyBenchBlockEntity.SLOT_BLUEPRINT,
                GunsmithBlueprintItem.createStack(ModMunitionsItems.GUNSMITH_BLUEPRINT.get(),
                        GunsmithBlueprint.M4A1));
        for (int slot = 1; slot < 10; slot++) {
            legacyInventory.setStackInSlot(slot, new ItemStack(Items.IRON_INGOT, slot));
        }
        legacyInventory.setStackInSlot(10, new ItemStack(Items.DIAMOND));

        CompoundTag savedBench = new CompoundTag();
        savedBench.put("Inventory", legacyInventory.serializeNBT());
        be.load(savedBench);

        helper.assertTrue(be.inventory().getSlots() == GunsmithAssemblyBenchBlockEntity.SLOT_COUNT,
                "pre-receiver save must expand to the current fixed inventory size");
        helper.assertTrue(be.inventory().getStackInSlot(GunsmithAssemblyBenchBlockEntity.SLOT_BLUEPRINT)
                        .is(ModMunitionsItems.GUNSMITH_BLUEPRINT.get()),
                "pre-receiver migration must preserve the blueprint in slot zero");
        for (int slot = 1; slot < 10; slot++) {
            ItemStack migrated = be.inventory().getStackInSlot(slot);
            helper.assertTrue(migrated.is(Items.IRON_INGOT) && migrated.getCount() == slot,
                    "pre-receiver migration must preserve legacy slot " + slot);
        }
        helper.assertTrue(be.inventory().getStackInSlot(
                        GunsmithAssemblyBenchBlockEntity.slotForPart(GunsmithPressPart.RECEIVER)).isEmpty(),
                "pre-receiver migration must leave the new receiver slot empty");
        helper.assertTrue(be.inventory().getStackInSlot(GunsmithAssemblyBenchBlockEntity.SLOT_OUTPUT)
                        .is(Items.DIAMOND),
                "pre-receiver migration must move legacy output slot 10 into the new output slot");

        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        GunsmithAssemblyMenu menu = new GunsmithAssemblyMenu(1, player.getInventory(), be.getBlockPos());
        menu.broadcastChanges();
        helper.assertTrue(menu.getSlot(GunsmithAssemblyBenchBlockEntity.SLOT_OUTPUT).getItem().is(Items.DIAMOND),
                "the first menu synchronization after pre-receiver migration must read every slot safely");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void preBipodInventoryMigrationPreservesSlotsAndMovesOutput(GameTestHelper helper) {
        placeStructure(helper, Direction.NORTH);
        GunsmithAssemblyBenchBlockEntity be = requireBench(helper);

        ItemStackHandler legacyInventory = new ItemStackHandler(12);
        for (int slot = 0; slot <= 10; slot++) {
            legacyInventory.setStackInSlot(slot, new ItemStack(Items.IRON_INGOT, slot + 1));
        }
        legacyInventory.setStackInSlot(11, new ItemStack(Items.DIAMOND));
        CompoundTag savedBench = new CompoundTag();
        savedBench.put("Inventory", legacyInventory.serializeNBT());
        be.load(savedBench);

        helper.assertTrue(be.inventory().getSlots() == GunsmithAssemblyBenchBlockEntity.SLOT_COUNT,
                "pre-bipod save must expand from twelve slots to the current inventory size");
        for (int slot = 0; slot <= 10; slot++) {
            ItemStack migrated = be.inventory().getStackInSlot(slot);
            helper.assertTrue(migrated.is(Items.IRON_INGOT) && migrated.getCount() == slot + 1,
                    "pre-bipod migration must preserve legacy slot " + slot);
        }
        helper.assertTrue(be.inventory().getStackInSlot(
                        GunsmithAssemblyBenchBlockEntity.slotForPart(GunsmithPressPart.BIPOD)).isEmpty(),
                "pre-bipod migration must leave the new bipod slot empty");
        helper.assertTrue(be.inventory().getStackInSlot(GunsmithAssemblyBenchBlockEntity.SLOT_OUTPUT).is(Items.DIAMOND),
                "pre-bipod migration must move legacy output slot eleven into the new output slot");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void preFiringPinInventoryMigrationPreservesSlotsAndMovesOutput(GameTestHelper helper) {
        placeStructure(helper, Direction.NORTH);
        GunsmithAssemblyBenchBlockEntity be = requireBench(helper);

        ItemStackHandler legacyInventory = new ItemStackHandler(13);
        for (int slot = 0; slot <= 11; slot++) {
            legacyInventory.setStackInSlot(slot, new ItemStack(Items.IRON_INGOT, slot + 1));
        }
        legacyInventory.setStackInSlot(12, new ItemStack(Items.DIAMOND));
        CompoundTag savedBench = new CompoundTag();
        savedBench.put("Inventory", legacyInventory.serializeNBT());
        be.load(savedBench);

        helper.assertTrue(be.inventory().getSlots() == GunsmithAssemblyBenchBlockEntity.SLOT_COUNT,
                "pre-firing-pin save must expand from thirteen slots to the current inventory size");
        for (int slot = 0; slot <= 11; slot++) {
            ItemStack migrated = be.inventory().getStackInSlot(slot);
            helper.assertTrue(migrated.is(Items.IRON_INGOT) && migrated.getCount() == slot + 1,
                    "pre-firing-pin migration must preserve legacy slot " + slot);
        }
        helper.assertTrue(be.inventory().getStackInSlot(
                        GunsmithAssemblyBenchBlockEntity.slotForPart(GunsmithPressPart.FIRING_PIN)).isEmpty(),
                "pre-firing-pin migration must leave the new firing-pin slot empty");
        helper.assertTrue(be.inventory().getStackInSlot(GunsmithAssemblyBenchBlockEntity.SLOT_OUTPUT).is(Items.DIAMOND),
                "pre-firing-pin migration must move legacy output slot twelve into the new output slot");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void unknownAssemblyInventorySizeFailsLoudly(GameTestHelper helper) {
        placeStructure(helper, Direction.NORTH);
        GunsmithAssemblyBenchBlockEntity be = requireBench(helper);
        ItemStackHandler unknownInventory = new ItemStackHandler(9);
        CompoundTag savedBench = new CompoundTag();
        savedBench.put("Inventory", unknownInventory.serializeNBT());

        boolean rejectedWithSize = false;
        try {
            be.load(savedBench);
        } catch (IllegalStateException expected) {
            rejectedWithSize = expected.getMessage().contains("inventory size 9");
        }
        helper.assertTrue(rejectedWithSize,
                "an unknown assembly inventory size must fail with the rejected size in its message");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH, timeoutTicks = 20)
    public static void akBlueprintRejectsArPartsThenStampsAkGun(GameTestHelper helper) {
        placeStructure(helper, Direction.NORTH);
        GunsmithAssemblyBenchBlockEntity be = requireBench(helper);
        fillCompleteRecipe(be, GunsmithBlueprint.AK47);
        fillParts(be, GunsmithPlatform.AR);
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        boolean previousEnabled = MunitionsConfig.GUNSMITH_ENABLED.get();

        try {
            MunitionsConfig.GUNSMITH_ENABLED.set(true);
            helper.assertFalse(be.tryStartAssembly(player, new ItemStack(Items.DIAMOND_HOE), 4),
                    "AK blueprint must reject a complete AR part set");
            for (GunsmithPressPart part : GunsmithBlueprint.AK47.requiredParts()) {
                helper.assertTrue(!be.inventory().getStackInSlot(
                                GunsmithAssemblyBenchBlockEntity.slotForPart(part)).isEmpty(),
                        "rejected AK assembly must preserve " + part);
            }

            fillParts(be, GunsmithPlatform.AK);
            helper.assertTrue(be.tryStartAssembly(player, new ItemStack(Items.DIAMOND_HOE), 4),
                    "AK blueprint must accept a complete AK part set");
            helper.runAfterDelay(6, () -> {
                ItemStack output = be.inventory().getStackInSlot(GunsmithAssemblyBenchBlockEntity.SLOT_OUTPUT);
                helper.assertTrue(output.is(Items.DIAMOND_HOE), "AK assembly must deliver the supplied base item");
                GunsmithGunStats stats = GunsmithGunStats.from(output);
                helper.assertTrue(stats != null, "AK output must carry gunsmith NBT");
                helper.assertTrue(stats.gunId().equals(GunsmithBlueprint.AK47.gunId()),
                        "AK blueprint must stamp tacz:ak47");
                helper.assertTrue(stats.platform().equals(GunsmithPlatform.AK.id()),
                        "AK blueprint must stamp the AK platform");
                helper.assertTrue(stats.template().equals(GunsmithBlueprint.AK47.templateId()),
                        "AK blueprint must stamp the AK47 template id");
                helper.succeed();
            });
        } finally {
            MunitionsConfig.GUNSMITH_ENABLED.set(previousEnabled);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH, timeoutTicks = 20)
    public static void m1911AssemblyConsumesFivePartsAndMapsEveryPistolStat(GameTestHelper helper) {
        placeStructure(helper, Direction.NORTH);
        GunsmithAssemblyBenchBlockEntity be = requireBench(helper);
        fillCompleteRecipe(be, GunsmithBlueprint.M1911);
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        boolean previousEnabled = MunitionsConfig.GUNSMITH_ENABLED.get();

        try {
            MunitionsConfig.GUNSMITH_ENABLED.set(true);
            for (GunsmithPressPart part : GunsmithPressPart.values()) {
                helper.assertTrue(be.isPartSlotVisible(part) == GunsmithBlueprint.M1911.requiredParts().contains(part),
                        "M1911 must expose exactly its five required slots: " + part);
            }
            helper.assertTrue(be.tryStartAssembly(player, new ItemStack(Items.GOLDEN_HOE), 4),
                    "complete M1911 recipe must start assembly");
            for (GunsmithPressPart part : GunsmithBlueprint.M1911.requiredParts()) {
                helper.assertTrue(be.inventory().getStackInSlot(
                                GunsmithAssemblyBenchBlockEntity.slotForPart(part)).isEmpty(),
                        part + " must be consumed by M1911 assembly");
            }
            helper.runAfterDelay(6, () -> {
                ItemStack output = be.inventory().getStackInSlot(GunsmithAssemblyBenchBlockEntity.SLOT_OUTPUT);
                helper.assertTrue(output.is(Items.GOLDEN_HOE), "M1911 assembly must deliver the supplied base item");
                GunsmithGunStats stats = GunsmithGunStats.from(output);
                helper.assertTrue(stats != null, "M1911 output must carry gunsmith NBT");
                helper.assertTrue(stats.gunId().equals(GunsmithBlueprint.M1911.gunId()),
                        "M1911 blueprint must stamp tacz:m1911");
                helper.assertTrue(stats.parts().size() == 5, "M1911 must record exactly five installed parts");
                assertClose(helper, stats.damage(), 1.20D, "M1911 hammer damage coefficient");
                assertClose(helper, stats.headshot(), 1.10D, "M1911 barrel headshot coefficient");
                assertClose(helper, stats.range(), 1.0D, "M1911 range must remain unchanged");
                assertClose(helper, stats.recoil(), 1.08D, "M1911 slide recoil coefficient");
                assertClose(helper, stats.spread(), 1.30D, "M1911 trigger spread coefficient");
                assertClose(helper, stats.handling(), 1.40D, "M1911 grip handling coefficient");
                assertClose(helper, stats.average(), 6.08D / 5.0D, "M1911 five-part average");
                assertClose(helper, stats.effectiveDamage(M1911_BASE_STATS), 13.20D,
                        "M1911 effective damage");
                assertClose(helper, stats.effectiveRange(M1911_BASE_STATS), 19.0D,
                        "M1911 effective range");
                assertClose(helper, stats.effectiveAdsTime(M1911_BASE_STATS), 0.08D / 1.40D,
                        "M1911 effective ADS time");
                helper.succeed();
            });
        } finally {
            MunitionsConfig.GUNSMITH_ENABLED.set(previousEnabled);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void blueprintCatalogUsesPlatformSpecificPartSets(GameTestHelper helper) {
        int arCount = 0;
        int akCount = 0;
        int pistolCount = 0;
        int shotgunCount = 0;
        int smgCount = 0;
        int marksmanCount = 0;
        int sniperCount = 0;
        int machineGunCount = 0;
        for (GunsmithBlueprint blueprint : GunsmithBlueprint.values()) {
            if (blueprint.platform() == GunsmithPlatform.AR) {
                arCount++;
            } else if (blueprint.platform() == GunsmithPlatform.AK) {
                akCount++;
            } else if (blueprint.platform() == GunsmithPlatform.PISTOL) {
                pistolCount++;
            } else if (blueprint.platform() == GunsmithPlatform.SHOTGUN) {
                shotgunCount++;
            } else if (blueprint.platform() == GunsmithPlatform.SMG) {
                smgCount++;
            } else if (blueprint.platform() == GunsmithPlatform.MARKSMAN) {
                marksmanCount++;
            } else if (blueprint.platform() == GunsmithPlatform.SNIPER) {
                sniperCount++;
            } else if (blueprint.platform() == GunsmithPlatform.MACHINE_GUN) {
                machineGunCount++;
            }
            ItemStack stack = GunsmithBlueprintItem.createStack(
                    ModMunitionsItems.GUNSMITH_BLUEPRINT.get(), blueprint);
            helper.assertTrue(GunsmithBlueprintItem.requireBlueprint(stack) == blueprint,
                    blueprint + " blueprint NBT must decode to the same catalog entry");
            helper.assertTrue(stack.getTag() != null
                            && stack.getTag().getInt("CustomModelData") == blueprint.iconModelData(),
                    blueprint + " must use its platform-specific blueprint icon model");
            int expectedPartCount = switch (blueprint.platform()) {
                case AR, AK, MARKSMAN -> 6;
                case PISTOL, SMG, SNIPER -> 5;
                case SHOTGUN -> 4;
                default -> throw new IllegalStateException(
                        "Unexpected platform with a gunsmith blueprint: " + blueprint.platform());
            };
            helper.assertTrue(blueprint.requiredParts().size() == expectedPartCount,
                    blueprint + " must require exactly " + expectedPartCount + " platform parts");
            helper.assertTrue(blueprint.requiredParts().equals(blueprint.platform().supportedParts()),
                    blueprint + " must use the platform's explicit supported part set");
            helper.assertTrue(GunsmithAssemblyRecipe.assembledGunId(stack).equals(blueprint.gunId()),
                    blueprint + " must keep assembling the blueprint's original gun id");
        }
        helper.assertTrue(GunsmithBlueprint.values().length == 21,
                "gunsmith blueprint catalog must contain 21 bound weapon blueprints");
        helper.assertTrue(arCount == 4, "catalog must contain four AR-platform blueprints");
        helper.assertTrue(akCount == 3, "catalog must contain three AK-family blueprints");
        helper.assertTrue(pistolCount == 1, "catalog must contain the M1911 pistol blueprint");
        helper.assertTrue(shotgunCount == 4, "catalog must contain four first-wave shotgun blueprints");
        helper.assertTrue(smgCount == 5, "catalog must contain five first-wave SMG blueprints");
        helper.assertTrue(marksmanCount == 1, "catalog must contain the SPR15HB marksman blueprint");
        helper.assertTrue(sniperCount == 3, "catalog must contain three bolt-action blueprints");
        helper.assertTrue(machineGunCount == 0, "machine gun platform must not gain a fabricated blueprint");
        helper.assertTrue(GunsmithBlueprint.M1911.requiredParts().containsAll(List.of(
                        GunsmithPressPart.BARREL, GunsmithPressPart.SLIDE, GunsmithPressPart.GRIP,
                        GunsmithPressPart.TRIGGER, GunsmithPressPart.HAMMER)),
                "M1911 must require barrel, slide, grip, trigger, and hammer");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void firstWaveBlueprintsMatchGunPackIdsAndNameKeys(GameTestHelper helper) {
        assertBlueprintSource(helper, GunsmithBlueprint.M870,
                "tacz:m870", "tacz.gun.m870.name", GunsmithPlatform.SHOTGUN);
        assertBlueprintSource(helper, GunsmithBlueprint.M1887_LONG,
                "ccrp:m1887_long", "ccrp.gun.m1887_long.name", GunsmithPlatform.SHOTGUN);
        assertBlueprintSource(helper, GunsmithBlueprint.KSG,
                "hare:ksg", "hare.gun.ksg.name", GunsmithPlatform.SHOTGUN);
        assertBlueprintSource(helper, GunsmithBlueprint.M1014,
                "tacz:m1014", "tacz.gun.m1014.name", GunsmithPlatform.SHOTGUN);
        assertBlueprintSource(helper, GunsmithBlueprint.UZI,
                "tacz:uzi", "tacz.gun.uzi.name", GunsmithPlatform.SMG);
        assertBlueprintSource(helper, GunsmithBlueprint.UMP45,
                "tacz:ump45", "tacz.gun.ump45.name", GunsmithPlatform.SMG);
        assertBlueprintSource(helper, GunsmithBlueprint.HK_MP5A5,
                "tacz:hk_mp5a5", "tacz.gun.hk_mp5a5.name", GunsmithPlatform.SMG);
        assertBlueprintSource(helper, GunsmithBlueprint.STERLING,
                "wyyc1991:stl", "wyyc.stl.name", GunsmithPlatform.SMG);
        assertBlueprintSource(helper, GunsmithBlueprint.MPX,
                "ccrp:mpx", "ccrp.gun.mpx.name", GunsmithPlatform.SMG);
        assertBlueprintSource(helper, GunsmithBlueprint.SPR15HB,
                "tacz:spr15hb", "tacz.gun.spr15hb.name", GunsmithPlatform.MARKSMAN);
        assertBlueprintSource(helper, GunsmithBlueprint.KAR98K,
                "tacz:kar98", "tacz.gun.kar98.name", GunsmithPlatform.SNIPER);
        assertBlueprintSource(helper, GunsmithBlueprint.SMLE_III,
                "lavender:smle_iii", "lavender.gun.smle_iii.name", GunsmithPlatform.SNIPER);
        assertBlueprintSource(helper, GunsmithBlueprint.M700,
                "tacz:m700", "tacz.gun.m700.name", GunsmithPlatform.SNIPER);
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void fireModePolicyRequiresCompleteOrderedBlueprintModes(GameTestHelper helper) {
        helper.assertTrue(GunsmithFireModePolicy.preserveAndSelectFirst(
                        List.of("auto", "semi"), List.of("auto", "semi")).equals("auto"),
                "auto + semi must preserve auto as the initial fire mode");
        helper.assertTrue(GunsmithFireModePolicy.preserveAndSelectFirst(
                        List.of("burst", "semi"), List.of("burst", "semi")).equals("burst"),
                "burst + semi must preserve burst as the initial fire mode");
        helper.assertTrue(GunsmithFireModePolicy.preserveAndSelectFirst(
                        List.of("semi", "burst"), List.of("semi", "burst")).equals("semi"),
                "semi + burst must preserve semi as the initial fire mode");

        assertFireModePolicyRejects(helper, List.of("auto", "semi"), List.of("auto"),
                "a finished gun missing a blueprint fire mode must be rejected");
        assertFireModePolicyRejects(helper, List.of("auto", "semi"), List.of("auto", "semi", "burst"),
                "a finished gun adding a fire mode absent from the blueprint must be rejected");
        assertFireModePolicyRejects(helper, List.of("semi", "burst"), List.of("burst", "semi"),
                "a finished gun with reordered fire modes must be rejected");
        assertFireModePolicyRejects(helper, List.of(), List.of("auto"),
                "an empty source fire-mode list must be rejected");
        assertFireModePolicyRejects(helper, List.of("auto"), List.of(),
                "an empty finished fire-mode list must be rejected");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void threeRoundBurstFireModePolicyRequiresExactDedicatedProfile(GameTestHelper helper) {
        helper.assertTrue(GunsmithFireModePolicy.forceThreeRoundBurst(
                        List.of("auto", "semi"), List.of("burst"), "burst", 3, false).equals("burst"),
                "three-round-burst assembly must select its sole burst mode");
        assertBurstFireModePolicyRejects(helper, List.of("auto", "semi"),
                List.of("burst", "semi"), 3, false,
                "three-round-burst assembly must not retain a second fire mode");
        assertBurstFireModePolicyRejects(helper, List.of("auto", "semi"),
                List.of("burst"), 2, false,
                "three-round-burst assembly must reject a two-round burst profile");
        assertBurstFireModePolicyRejects(helper, List.of("auto", "semi"),
                List.of("burst"), 3, true,
                "three-round-burst assembly must require a new trigger pull after each burst");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void legacyM4TemplatePreservesLegacyOutputGunId(GameTestHelper helper) {
        ItemStack legacy = new ItemStack(ModMunitionsItems.M4_ASSEMBLY_TEMPLATE.get());
        helper.assertTrue(GunsmithAssemblyRecipe.blueprint(legacy) == GunsmithBlueprint.M4A1,
                "legacy M4 template must remain compatible with the M4A1 blueprint");
        ItemStack output = GunsmithAssemblyRecipe.assemble(
                new ItemStack(Items.IRON_HOE), legacy, previewParts(GunsmithPlatform.AR));
        GunsmithGunStats stats = GunsmithGunStats.from(output);
        helper.assertTrue(stats != null, "legacy M4 output must carry gunsmith NBT");
        helper.assertTrue(stats.gunId().equals(GunsmithGunFactory.M4A1_ID),
                "legacy M4 template must keep producing miningdim:m4a1_gunsmith");
        helper.assertTrue(GunsmithAssemblyRecipe.assembledGunId(legacy).equals(GunsmithGunFactory.M4A1_ID),
                "legacy M4 template must resolve the legacy TaCZ gun id");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void v1GunWithoutVersionMigratesRangeAndRecoilFromParts(GameTestHelper helper) {
        ItemStack legacy = GunsmithAssemblyRecipe.assemble(
                new ItemStack(Items.IRON_HOE),
                GunsmithBlueprintItem.createStack(ModMunitionsItems.GUNSMITH_BLUEPRINT.get(), GunsmithBlueprint.M4A1),
                previewParts(GunsmithPlatform.AR));
        CompoundTag root = legacy.getOrCreateTag().getCompound(GunsmithGunStats.ROOT_KEY);
        root.remove(GunsmithGunStats.VERSION_KEY);
        CompoundTag legacyStats = root.getCompound(GunsmithGunStats.STATS_KEY);
        legacyStats.remove("range");
        legacyStats.putDouble("recoil", 1.06D);

        GunsmithGunStats stats = GunsmithGunStats.from(legacy);
        helper.assertTrue(stats != null, "legacy gunsmith NBT must remain readable");
        assertClose(helper, stats.range(), 1.04D, "v1 range must come from core");
        assertClose(helper, stats.recoil(), 1.08D, "v1 recoil must come from stock instead of the old average");
        assertClose(helper, stats.effectiveRange(M4_BASE_STATS), 49.92D, "v1 effective range");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void currentGunRecordsOrderedImmutablePartSummaries(GameTestHelper helper) {
        ItemStack output = GunsmithAssemblyRecipe.assemble(
                new ItemStack(Items.IRON_HOE),
                GunsmithBlueprintItem.createStack(ModMunitionsItems.GUNSMITH_BLUEPRINT.get(), GunsmithBlueprint.M4A1),
                previewParts(GunsmithPlatform.AR));
        CompoundTag root = output.getOrCreateTag().getCompound(GunsmithGunStats.ROOT_KEY);
        helper.assertTrue(root.contains(GunsmithGunStats.VERSION_KEY, Tag.TAG_INT),
                "new guns must write an integer format version");
        helper.assertTrue(root.getInt(GunsmithGunStats.VERSION_KEY) == GunsmithGunStats.CURRENT_VERSION,
                "new guns must write the current format version");
        helper.assertTrue(root.getCompound(GunsmithGunStats.STATS_KEY).contains("range", Tag.TAG_DOUBLE),
                "new guns must write range in Stats");

        GunsmithGunStats stats = GunsmithGunStats.from(output);
        helper.assertTrue(stats != null, "new guns must expose part summaries");
        List<GunsmithGunStats.PartSummary> parts = stats.parts();
        helper.assertTrue(parts.size() == GunsmithBlueprint.M4A1.requiredParts().size(),
                "the complete AR recipe must record its six installed parts");
        assertPartSummary(helper, parts.get(0), GunsmithPressPart.CORE, GunsmithPartQuality.COMMON, 1.04D);
        assertPartSummary(helper, parts.get(1), GunsmithPressPart.BARREL, GunsmithPartQuality.IMPROVED, 1.10D);
        assertPartSummary(helper, parts.get(2), GunsmithPressPart.BOLT, GunsmithPartQuality.MILSPEC, 1.20D);
        assertPartSummary(helper, parts.get(3), GunsmithPressPart.HANDGUARD, GunsmithPartQuality.PRECISION, 1.30D);
        assertPartSummary(helper, parts.get(4), GunsmithPressPart.GRIP, GunsmithPartQuality.LEGENDARY, 1.40D);
        assertPartSummary(helper, parts.get(5), GunsmithPressPart.STOCK, GunsmithPartQuality.IMPROVED, 1.08D);
        boolean immutable = false;
        try {
            parts.add(parts.get(0));
        } catch (UnsupportedOperationException expected) {
            immutable = true;
        }
        helper.assertTrue(immutable, "part summaries must be immutable");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void finishedGunTooltipCompactsStatsAndPartsIntoEightRows(GameTestHelper helper) {
        GunsmithGunStats stats = GunsmithGunStats.from(assembledM4Gun());
        helper.assertTrue(stats != null, "assembled M4 must carry gunsmith stats");
        List<Component> tooltip = new ArrayList<>();
        GunsmithGunTooltip.append(tooltip, stats, M4_BASE_STATS);

        helper.assertTrue(tooltip.size() == 8,
                "complete six-part gun tooltip must use three compact component rows");
        for (int row = 5; row < 8; row++) {
            helper.assertTrue(tooltip.get(row).getSiblings().size() == 3,
                    "each paired installed-parts row must contain two parts separated into columns");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void currentGunRejectsPartsThatDoNotMatchItsBlueprint(GameTestHelper helper) {
        ItemStack missingPart = GunsmithAssemblyRecipe.assemble(
                new ItemStack(Items.IRON_HOE),
                GunsmithBlueprintItem.createStack(ModMunitionsItems.GUNSMITH_BLUEPRINT.get(), GunsmithBlueprint.M4A1),
                previewParts(GunsmithPlatform.AR));
        CompoundTag missingPartRoot = missingPart.getOrCreateTag().getCompound(GunsmithGunStats.ROOT_KEY);
        missingPartRoot.getCompound(GunsmithGunStats.PARTS_KEY).remove(GunsmithPressPart.STOCK.id());
        assertStatsRejected(helper, missingPart, "a current gun missing its stock must be rejected");

        ItemStack unknownPart = GunsmithAssemblyRecipe.assemble(
                new ItemStack(Items.IRON_HOE),
                GunsmithBlueprintItem.createStack(ModMunitionsItems.GUNSMITH_BLUEPRINT.get(), GunsmithBlueprint.M4A1),
                previewParts(GunsmithPlatform.AR));
        CompoundTag unknownPartRoot = unknownPart.getOrCreateTag().getCompound(GunsmithGunStats.ROOT_KEY);
        unknownPartRoot.getCompound(GunsmithGunStats.PARTS_KEY).put("unknown", new CompoundTag());
        assertStatsRejected(helper, unknownPart, "a current gun with an unknown part must be rejected");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void currentGunRejectsGunIdAndStatsThatDoNotMatchBlueprintParts(GameTestHelper helper) {
        ItemStack mismatchedGunId = assembledM4Gun();
        CompoundTag mismatchedGunRoot = mismatchedGunId.getOrCreateTag()
                .getCompound(GunsmithGunStats.ROOT_KEY);
        mismatchedGunRoot.putString("gunId", GunsmithBlueprint.AK47.gunId().toString());
        assertStatsRejected(helper, mismatchedGunId,
                "a current M4 gun carrying the AK47 gun id must be rejected");

        String[] statKeys = {"damage", "headshot", "range", "recoil", "spread", "handling", "average"};
        for (String statKey : statKeys) {
            ItemStack mismatchedStats = assembledM4Gun();
            CompoundTag stats = mismatchedStats.getOrCreateTag()
                    .getCompound(GunsmithGunStats.ROOT_KEY)
                    .getCompound(GunsmithGunStats.STATS_KEY);
            stats.putDouble(statKey, stats.getDouble(statKey) + 0.01D);
            assertStatsRejected(helper, mismatchedStats,
                    "a current gun with a " + statKey + " value inconsistent with its parts must be rejected");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void missingTaczRejectsAssemblyWithoutConsumingInputs(GameTestHelper helper) {
        placeStructure(helper, Direction.NORTH);
        GunsmithAssemblyBenchBlockEntity be = requireBench(helper);
        fillCompleteRecipe(be, GunsmithBlueprint.M4A1);
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        boolean previousEnabled = MunitionsConfig.GUNSMITH_ENABLED.get();

        try {
            MunitionsConfig.GUNSMITH_ENABLED.set(true);
            helper.assertFalse(MunitionsAmmoFactory.isTaczLoaded(),
                    "GameTest profile must exercise the missing-TaCZ boundary");
            helper.assertTrue(GunsmithTaczBridge.findBaseStats(GunsmithBlueprint.M4A1.gunId()).isEmpty(),
                    "missing TaCZ must expose unavailable base stats");
            helper.assertFalse(be.tryStartAssembly(player),
                    "real gun factory must reject assembly when TaCZ is unavailable");
            helper.assertFalse(be.isAnimating(), "rejected assembly must not animate");
            helper.assertTrue(be.inventory().getStackInSlot(
                            GunsmithAssemblyBenchBlockEntity.SLOT_BLUEPRINT).is(ModMunitionsItems.GUNSMITH_BLUEPRINT.get()),
                    "rejected assembly must preserve the blueprint");
            for (GunsmithPressPart part : GunsmithBlueprint.M4A1.requiredParts()) {
                helper.assertTrue(!be.inventory().getStackInSlot(
                                GunsmithAssemblyBenchBlockEntity.slotForPart(part)).isEmpty(),
                        "rejected assembly must preserve " + part);
            }
            helper.assertTrue(be.inventory().getStackInSlot(
                            GunsmithAssemblyBenchBlockEntity.SLOT_OUTPUT).isEmpty(),
                    "rejected assembly must not create output");
            helper.succeed();
        } finally {
            MunitionsConfig.GUNSMITH_ENABLED.set(previousEnabled);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void previewUsesTheSelectedBlueprintBaseStats(GameTestHelper helper) {
        EnumMap<GunsmithPressPart, ItemStack> arParts = previewParts(GunsmithPlatform.AR);
        EnumMap<GunsmithPressPart, ItemStack> akParts = previewParts(GunsmithPlatform.AK);
        EnumMap<GunsmithPressPart, ItemStack> pistolParts = previewParts(GunsmithPlatform.PISTOL);
        GunsmithAssemblyRecipe.Preview m4 =
                GunsmithAssemblyRecipe.preview(GunsmithBlueprint.M4A1, arParts, M4_BASE_STATS);
        GunsmithAssemblyRecipe.Preview ak47 =
                GunsmithAssemblyRecipe.preview(GunsmithBlueprint.AK47, akParts, AK47_BASE_STATS);
        GunsmithAssemblyRecipe.Preview m1911 =
                GunsmithAssemblyRecipe.preview(GunsmithBlueprint.M1911, pistolParts, M1911_BASE_STATS);

        assertClose(helper, m4.damage(), 7.80D, "M4 preview damage");
        assertClose(helper, m4.range(), 1.04D, "M4 preview range coefficient");
        assertClose(helper, m4.effectiveRange(), 49.92D, "M4 preview effective range");
        assertClose(helper, m4.adsTime(), 0.16D / 1.40D, "M4 preview ADS");
        assertClose(helper, ak47.damage(), 10.80D, "AK47 preview damage");
        assertClose(helper, ak47.range(), 1.04D, "AK47 preview range coefficient");
        assertClose(helper, ak47.effectiveRange(), 54.08D, "AK47 preview effective range");
        assertClose(helper, ak47.adsTime(), 0.20D / 1.40D, "AK47 preview ADS");
        assertClose(helper, m1911.damage(), 13.20D, "M1911 preview damage");
        assertClose(helper, m1911.headshot(), 1.65D, "M1911 preview headshot");
        assertClose(helper, m1911.range(), 1.0D, "M1911 preview range coefficient");
        assertClose(helper, m1911.effectiveRange(), 19.0D, "M1911 preview effective range");
        assertClose(helper, m1911.recoilChange(), (1.0D / 1.08D - 1.0D) * 100.0D,
                "M1911 preview recoil");
        assertClose(helper, m1911.spreadChange(), (1.0D / 1.30D - 1.0D) * 100.0D,
                "M1911 preview spread");
        assertClose(helper, m1911.adsTime(), 0.08D / 1.40D, "M1911 preview ADS");
        assertClose(helper, m1911.average(), 6.08D / 5.0D, "M1911 preview five-part average");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void oneLegendaryPartCannotInflateTheSixPartOverallCoefficient(GameTestHelper helper) {
        EnumMap<GunsmithPressPart, ItemStack> parts = new EnumMap<>(GunsmithPressPart.class);
        for (GunsmithPressPart part : GunsmithBlueprint.M4A1.requiredParts()) {
            parts.put(part, part(GunsmithPlatform.AR, part, GunsmithPartQuality.COMMON, 1.04D));
        }
        parts.put(GunsmithPressPart.GRIP,
                part(GunsmithPlatform.AR, GunsmithPressPart.GRIP,
                        GunsmithPartQuality.LEGENDARY, 1.50D));

        GunsmithAssemblyRecipe.Preview preview =
                GunsmithAssemblyRecipe.preview(GunsmithBlueprint.M4A1, parts, M4_BASE_STATS);
        assertClose(helper, preview.overallCoefficient(), 6.70D / 6.0D,
                "five maximum common parts plus one maximum legendary part must use the six-part average");
        helper.assertTrue(preview.overallCoefficient() < 1.12D,
                "one legendary part must not inflate the overall coefficient to legendary strength");

        ItemStack output = GunsmithAssemblyRecipe.assemble(new ItemStack(Items.IRON_HOE),
                GunsmithBlueprintItem.createStack(ModMunitionsItems.GUNSMITH_BLUEPRINT.get(),
                        GunsmithBlueprint.M4A1), parts);
        GunsmithGunStats stats = GunsmithGunStats.from(output);
        helper.assertTrue(stats != null, "mixed-quality assembly must produce valid gunsmith stats");
        assertClose(helper, stats.average(), 6.70D / 6.0D,
                "assembled gun must persist the same mixed-quality average");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void previewTreatsStalePartsFromAnotherPlatformAsEmpty(GameTestHelper helper) {
        EnumMap<GunsmithPressPart, ItemStack> staleArParts = previewParts(GunsmithPlatform.AR);
        EnumMap<GunsmithPressPart, ItemStack> compatible =
                GunsmithAssemblyRecipe.previewCompatibleParts(GunsmithBlueprint.M870, staleArParts);

        for (GunsmithPressPart part : GunsmithBlueprint.M870.requiredParts()) {
            helper.assertTrue(compatible.get(part).isEmpty(),
                    "shotgun preview must ignore stale AR " + part.id());
        }
        GunsmithAssemblyRecipe.Preview preview =
                GunsmithAssemblyRecipe.preview(GunsmithBlueprint.M870, compatible, M4_BASE_STATS);
        assertClose(helper, preview.damage(), M4_BASE_STATS.damage(),
                "shotgun preview with stale AR parts must retain base damage");
        assertClose(helper, preview.average(), 1.0D,
                "shotgun preview with stale AR parts must use neutral coefficients");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void legendaryRedEastGasSurvivesAssemblyAndAppliesMultiplicativePenalty(GameTestHelper helper) {
        EnumMap<GunsmithPressPart, ItemStack> parts = previewParts(GunsmithPlatform.AK);
        parts.put(GunsmithPressPart.CORE, GunsmithPartItem.createStack(
                ModMunitionsItems.GUNSMITH_PART.get(), GunsmithPlatform.AK, GunsmithPressPart.CORE,
                GunsmithPartQuality.LEGENDARY, GunsmithPartVariant.RED_EAST_HIGH_PRESSURE_GAS));
        GunsmithAssemblyRecipe.Preview preview =
                GunsmithAssemblyRecipe.preview(GunsmithBlueprint.AK47, parts, AK47_BASE_STATS);
        assertClose(helper, preview.damage(), 21.60D,
                "legendary red east gas must double the AK bolt-adjusted damage");
        assertClose(helper, preview.range(), 0.858D,
                "red east gas must reduce the legendary core range coefficient by 40 percent");
        assertClose(helper, preview.effectiveRange(), 44.616D,
                "red east gas must preserve legendary range scaling before applying its penalty");

        ItemStack output = GunsmithAssemblyRecipe.assemble(new ItemStack(Items.IRON_HOE),
                GunsmithBlueprintItem.createStack(ModMunitionsItems.GUNSMITH_BLUEPRINT.get(), GunsmithBlueprint.AK47),
                parts);
        CompoundTag cachedStats = output.getOrCreateTag().getCompound(GunsmithGunStats.ROOT_KEY)
                .getCompound(GunsmithGunStats.STATS_KEY);
        assertClose(helper, cachedStats.getDouble("range"), 1.43D,
                "current gun NBT must cache base range instead of the hot-reload component penalty");
        assertClose(helper, cachedStats.getDouble("damage"), 1.20D,
                "current gun NBT must cache base damage instead of the hot-reload component bonus");
        GunsmithGunStats stats = GunsmithGunStats.from(output);
        helper.assertTrue(stats != null, "red east assembly must produce valid gunsmith stats");
        assertClose(helper, stats.damage(), 2.40D, "assembled red east damage multiplier");
        assertClose(helper, stats.range(), 0.858D, "assembled red east effective-range multiplier");
        assertClose(helper, stats.fireRate(), 0.75D, "assembled red east fire-rate multiplier");
        assertClose(helper, stats.specialSpread(), 1.80D, "assembled red east spread penalty");
        assertClose(helper, stats.specialRecoil(), 2.00D, "assembled red east all-axis recoil penalty");
        assertClose(helper, stats.verticalRecoil(), 3.00D, "assembled red east pitch-recoil multiplier");
        GunsmithStatMultipliers multipliers = GunsmithStatMultipliers.of(stats, 10.0D);
        assertClose(helper, multipliers.inaccuracy(), (1.0D / 1.30D) * 1.80D,
                "red east spread penalty must stack after handguard control");
        assertClose(helper, multipliers.recoil(), (1.0D / 1.08D) * 2.00D,
                "red east all-axis recoil must stack after stock control");
        assertClose(helper, multipliers.verticalRecoil(), (1.0D / 1.08D) * 2.00D * 3.00D,
                "red east extra vertical recoil must stack after all-axis recoil");
        helper.assertTrue(stats.parts().stream().anyMatch(part ->
                        part.part() == GunsmithPressPart.CORE
                                && part.variant() == GunsmithPartVariant.RED_EAST_HIGH_PRESSURE_GAS),
                "assembled gun must retain the red east component variant");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void legendaryChixueABoltSurvivesAkAssemblyAndAppliesFixedTradeoffs(GameTestHelper helper) {
        EnumMap<GunsmithPressPart, ItemStack> parts = previewParts(GunsmithPlatform.AK);
        parts.put(GunsmithPressPart.BOLT, GunsmithPartItem.createStack(
                ModMunitionsItems.GUNSMITH_PART.get(), GunsmithPlatform.AK, GunsmithPressPart.BOLT,
                GunsmithPartQuality.LEGENDARY, GunsmithPartVariant.RED_WINTER_CHIXUE_A_BOLT));

        GunsmithAssemblyRecipe.Preview preview =
                GunsmithAssemblyRecipe.preview(GunsmithBlueprint.AK47, parts, AK47_BASE_STATS);
        assertClose(helper, preview.damage(), 9.0D * 1.43D * 1.25D,
                "Chixue-A preview damage");
        assertClose(helper, preview.recoilChange(), (1.0D / 1.08D * 1.35D - 1.0D) * 100.0D,
                "Chixue-A preview recoil");

        ItemStack output = GunsmithAssemblyRecipe.assemble(new ItemStack(Items.IRON_HOE),
                GunsmithBlueprintItem.createStack(ModMunitionsItems.GUNSMITH_BLUEPRINT.get(),
                        GunsmithBlueprint.AK47), parts);
        GunsmithGunStats stats = GunsmithGunStats.from(output);
        helper.assertTrue(stats != null, "Chixue-A assembly must produce valid gunsmith stats");
        assertClose(helper, stats.damage(), 1.43D * 1.25D,
                "assembled Chixue-A damage multiplier");
        assertClose(helper, stats.specialRecoil(), 1.35D,
                "assembled Chixue-A recoil penalty");
        assertClose(helper, stats.armorIgnore(), 0.75D,
                "assembled Chixue-A armor penetration multiplier");
        GunsmithStatMultipliers multipliers = GunsmithStatMultipliers.of(stats, 10.0D);
        assertClose(helper, multipliers.recoil(), 1.0D / 1.08D * 1.35D,
                "Chixue-A recoil penalty must stack after stock control");
        assertClose(helper, multipliers.armorIgnore(), 0.75D,
                "Chixue-A armor penetration penalty must reach the TaCZ multiplier mapping");
        helper.assertTrue(stats.parts().stream().anyMatch(part ->
                        part.part() == GunsmithPressPart.BOLT
                                && part.variant() == GunsmithPartVariant.RED_WINTER_CHIXUE_A_BOLT),
                "assembled gun must retain Chixue-A in the AK bolt slot");
        helper.assertTrue(stats.parts().stream().noneMatch(part -> part.part() == GunsmithPressPart.RECEIVER),
                "assembled Chixue-A AK must not gain a receiver slot");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void legendaryMkAxABoltAppliesAllAdoptedArTradeoffs(GameTestHelper helper) {
        EnumMap<GunsmithPressPart, ItemStack> parts = previewParts(GunsmithPlatform.AR);
        parts.put(GunsmithPressPart.BOLT, GunsmithPartItem.createStack(
                ModMunitionsItems.GUNSMITH_PART.get(), GunsmithPlatform.AR, GunsmithPressPart.BOLT,
                GunsmithPartQuality.LEGENDARY, GunsmithPartVariant.MK_AX_A_BOLT));

        GunsmithAssemblyRecipe.Preview preview =
                GunsmithAssemblyRecipe.preview(GunsmithBlueprint.M4A1, parts, M4_BASE_STATS);
        assertClose(helper, preview.damage(), 11.61875D, "legendary MK-AX-A preview damage");
        assertClose(helper, preview.range(), 1.30D, "legendary MK-AX-A preview range");
        assertClose(helper, preview.effectiveRange(), 62.40D, "legendary MK-AX-A preview effective range");
        assertClose(helper, preview.recoilChange(), (1.0D / 1.08D * 0.75D - 1.0D) * 100.0D,
                "legendary MK-AX-A preview recoil");
        assertClose(helper, preview.spreadChange(), (1.0D / 1.30D * 0.75D - 1.0D) * 100.0D,
                "legendary MK-AX-A preview spread");
        assertClose(helper, preview.adsTime(), 0.16D / (1.40D * 0.70D),
                "MK-AX-A must reduce ADS speed by a fixed 30 percent");

        ItemStack output = GunsmithAssemblyRecipe.assemble(new ItemStack(Items.IRON_HOE),
                GunsmithBlueprintItem.createStack(ModMunitionsItems.GUNSMITH_BLUEPRINT.get(), GunsmithBlueprint.M4A1),
                parts);
        GunsmithGunStats stats = GunsmithGunStats.from(output);
        helper.assertTrue(stats != null, "MK-AX-A assembly must produce valid gunsmith stats");
        assertClose(helper, stats.damage(), 1.7875D, "assembled MK-AX-A damage multiplier");
        assertClose(helper, stats.range(), 1.30D, "assembled MK-AX-A range multiplier");
        assertClose(helper, stats.fireRate(), 1.05D, "assembled MK-AX-A fire-rate multiplier");
        assertClose(helper, stats.specialSpread(), 0.75D, "assembled MK-AX-A spread multiplier");
        assertClose(helper, stats.specialRecoil(), 0.75D, "assembled MK-AX-A recoil multiplier");
        assertClose(helper, stats.verticalRecoil(), 1.00D,
                "assembled MK-AX-A must not double-apply vertical recoil control");
        assertClose(helper, stats.specialAdsSpeed(), 0.70D, "assembled MK-AX-A ADS speed multiplier");
        assertClose(helper, stats.effectiveAdsTime(M4_BASE_STATS), 0.16D / (1.40D * 0.70D),
                "assembled MK-AX-A effective ADS time");
        helper.assertTrue(stats.parts().stream().anyMatch(part ->
                        part.part() == GunsmithPressPart.BOLT
                                && part.variant() == GunsmithPartVariant.MK_AX_A_BOLT),
                "assembled gun must retain the MK-AX-A bolt variant");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void threeRoundBurstBoltProducesDedicatedSixPartArGun(GameTestHelper helper) {
        EnumMap<GunsmithPressPart, ItemStack> parts = previewParts(GunsmithPlatform.AR);
        parts.put(GunsmithPressPart.BOLT, GunsmithPartItem.createStack(
                ModMunitionsItems.GUNSMITH_PART.get(), GunsmithPlatform.AR, GunsmithPressPart.BOLT,
                GunsmithPartQuality.COMMON, GunsmithPartVariant.AR_THREE_ROUND_BURST_BOLT));

        GunsmithAssemblyRecipe.Preview preview =
                GunsmithAssemblyRecipe.preview(GunsmithBlueprint.M4A1, parts, M4_BASE_STATS);
        assertClose(helper, preview.recoilChange(), (1.0D / 1.08D * 0.65D - 1.0D) * 100.0D,
                "three-round-burst bolt preview recoil");
        assertClose(helper, preview.spreadChange(), (1.0D / 1.30D * 0.75D - 1.0D) * 100.0D,
                "three-round-burst bolt preview spread");

        ItemStack blueprint = GunsmithBlueprintItem.createStack(
                ModMunitionsItems.GUNSMITH_BLUEPRINT.get(), GunsmithBlueprint.M4A1);
        ResourceLocation expectedGunId = GunsmithGunFactory.burstGunId(GunsmithBlueprint.M4A1);
        helper.assertTrue(GunsmithAssemblyRecipe.assembledGunId(blueprint, parts).equals(expectedGunId),
                "three-round-burst bolt must resolve the dedicated M4-family burst gun id");
        ItemStack output = GunsmithAssemblyRecipe.assemble(new ItemStack(Items.IRON_HOE), blueprint, parts);
        GunsmithGunStats stats = GunsmithGunStats.from(output);
        helper.assertTrue(stats != null, "three-round-burst bolt assembly must produce valid gunsmith stats");
        helper.assertTrue(stats.gunId().equals(expectedGunId),
                "assembled NBT must retain the dedicated burst gun id");
        helper.assertTrue(stats.parts().size() == 6,
                "three-round-burst AR gun must retain exactly six component slots");
        helper.assertTrue(stats.forcesBurstFireMode(),
                "assembled stats must retain the forced three-round-burst behavior");
        assertClose(helper, stats.specialRecoil(), 0.65D,
                "assembled three-round-burst bolt recoil multiplier");
        assertClose(helper, stats.specialSpread(), 0.75D,
                "assembled three-round-burst bolt spread multiplier");
        helper.assertTrue(stats.parts().stream().anyMatch(part ->
                        part.part() == GunsmithPressPart.BOLT
                                && part.variant() == GunsmithPartVariant.AR_THREE_ROUND_BURST_BOLT),
                "assembled gun must retain the three-round-burst bolt in the AR bolt slot");
        helper.assertTrue(stats.parts().stream().noneMatch(part -> part.part() == GunsmithPressPart.RECEIVER),
                "assembled three-round-burst AR gun must not gain a receiver slot");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void legendaryTrinityBarrelStacksFixedEffectsAfterBarrelQuality(GameTestHelper helper) {
        EnumMap<GunsmithPressPart, ItemStack> parts = previewParts(GunsmithPlatform.AR);
        parts.put(GunsmithPressPart.BARREL, GunsmithPartItem.createStack(
                ModMunitionsItems.GUNSMITH_PART.get(), GunsmithPlatform.AR, GunsmithPressPart.BARREL,
                GunsmithPartQuality.LEGENDARY,
                GunsmithPartVariant.TRINITY_PRECISION_GRADUATED_BARREL));

        GunsmithAssemblyRecipe.Preview preview =
                GunsmithAssemblyRecipe.preview(GunsmithBlueprint.M4A1, parts, M4_BASE_STATS);
        assertClose(helper, preview.damage(), 7.80D, "Trinity preview body damage");
        assertClose(helper, preview.headshot(), 1.50D * 1.43D * 1.50D,
                "Trinity preview headshot multiplier");
        assertClose(helper, preview.range(), 1.04D * 1.50D, "Trinity preview range");
        assertClose(helper, preview.effectiveRange(), 48.0D * 1.04D * 1.50D,
                "Trinity preview effective range");
        assertClose(helper, preview.recoilChange(), (1.0D / 1.08D - 1.0D) * 100.0D,
                "Trinity preview must preserve base recoil");
        assertClose(helper, preview.spreadChange(), (1.0D / 1.30D * 0.70D - 1.0D) * 100.0D,
                "Trinity preview spread");
        assertClose(helper, preview.adsTime(), 0.16D / (1.40D * 0.50D),
                "Trinity preview ADS time");

        ItemStack output = GunsmithAssemblyRecipe.assemble(new ItemStack(Items.IRON_HOE),
                GunsmithBlueprintItem.createStack(ModMunitionsItems.GUNSMITH_BLUEPRINT.get(), GunsmithBlueprint.M4A1),
                parts);
        GunsmithGunStats stats = GunsmithGunStats.from(output);
        helper.assertTrue(stats != null, "Trinity assembly must produce valid gunsmith stats");
        assertClose(helper, stats.damage(), 1.20D, "assembled Trinity body-damage multiplier");
        assertClose(helper, stats.headshot(), 1.43D * 1.50D,
                "assembled Trinity headshot multiplier");
        assertClose(helper, stats.range(), 1.04D * 1.50D, "assembled Trinity range multiplier");
        assertClose(helper, stats.specialSpread(), 0.70D, "assembled Trinity spread multiplier");
        assertClose(helper, stats.specialAdsSpeed(), 0.50D, "assembled Trinity ADS-speed multiplier");
        GunsmithStatMultipliers multipliers = GunsmithStatMultipliers.of(stats, 1.80D);
        assertClose(helper, multipliers.headshot(), 1.43D * 1.50D,
                "Trinity faction headshot bonus must remain outside the base-quality cap");
        helper.assertTrue(stats.parts().stream().anyMatch(part ->
                        part.part() == GunsmithPressPart.BARREL
                                && part.variant() == GunsmithPartVariant.TRINITY_PRECISION_GRADUATED_BARREL),
                "assembled gun must retain the Trinity barrel variant");
        GunsmithGunDurability.State durability = GunsmithGunDurability.view(output);
        int trinityMaximum = (int) Math.floor(MunitionsConfig.GUN_DURABILITY_AR.get() * 0.70D);
        helper.assertTrue(durability.originalMaximum() == trinityMaximum
                        && durability.maximum() == durability.originalMaximum(),
                "Trinity barrel must reduce a newly assembled AR maximum durability by 30 percent");

        CompoundTag legacyDurability = output.getTag().getCompound(GunsmithGunStats.ROOT_KEY)
                .getCompound(GunsmithGunDurability.DURABILITY_KEY);
        legacyDurability.putInt("version", 1);
        legacyDurability.putInt("originalMaximum", MunitionsConfig.GUN_DURABILITY_AR.get());
        legacyDurability.putInt("maximum", MunitionsConfig.GUN_DURABILITY_AR.get());
        legacyDurability.putInt("current", MunitionsConfig.GUN_DURABILITY_AR.get() - 100);
        GunsmithGunDurability.State migrated = GunsmithGunDurability.ensureInitialized(output);
        helper.assertTrue(migrated.originalMaximum() == trinityMaximum
                        && migrated.maximum() == trinityMaximum
                        && migrated.current() == (int) Math.floor(
                                (MunitionsConfig.GUN_DURABILITY_AR.get() - 100) * 0.70D),
                "legacy Trinity gun durability must migrate the cap while preserving wear ratio");
        helper.assertTrue(output.getTag().getCompound(GunsmithGunStats.ROOT_KEY)
                        .getCompound(GunsmithGunDurability.DURABILITY_KEY).getInt("version") == 2,
                "authoritative Trinity durability migration must persist the current schema version");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void versionThreeSixPartArGunRemainsReadable(GameTestHelper helper) {
        ItemStack legacy = assembledM4Gun();
        CompoundTag root = legacy.getOrCreateTag().getCompound(GunsmithGunStats.ROOT_KEY);
        root.putInt(GunsmithGunStats.VERSION_KEY, 3);
        root.getCompound(GunsmithGunStats.STATS_KEY).putDouble("average",
                (1.04D + 1.10D + 1.20D + 1.30D + 1.40D + 1.08D) / 6.0D);

        GunsmithGunStats stats = GunsmithGunStats.from(legacy);
        helper.assertTrue(stats != null, "v3 six-part AR gun must remain readable");
        helper.assertTrue(stats.parts().size() == 6, "v3 AR gun must retain its six legacy parts");
        assertClose(helper, stats.damage(), 1.20D, "v3 AR gun damage must remain unchanged");
        assertClose(helper, stats.fireRate(), 1.0D, "v3 AR gun must not gain an implicit special-part effect");
        assertClose(helper, stats.specialAdsSpeed(), 1.0D,
                "v3 AR gun must not gain an implicit ADS penalty");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void legacyArSpr15hbMigratesToSixPartMarksmanData(GameTestHelper helper) {
        ItemStack legacy = assembledM4Gun();
        CompoundTag root = legacy.getOrCreateTag().getCompound(GunsmithGunStats.ROOT_KEY);
        root.putString("template", GunsmithBlueprint.SPR15HB.templateId());
        root.putString("gunId", GunsmithBlueprint.SPR15HB.gunId().toString());

        GunsmithGunStats stats = GunsmithGunStats.from(legacy);
        helper.assertTrue(stats != null, "legacy AR SPR15HB gun must remain readable");
        helper.assertTrue(stats.blueprint() == GunsmithBlueprint.SPR15HB,
                "legacy SPR15HB gun must retain its blueprint identity");
        helper.assertTrue(stats.platform().equals(GunsmithPlatform.MARKSMAN.id()),
                "legacy SPR15HB gun must report the marksman platform after migration");
        helper.assertTrue(stats.parts().size() == 6,
                "legacy six-part SPR15HB data must preserve all six marksman parts");
        helper.assertTrue(stats.parts().stream().anyMatch(part -> part.part() == GunsmithPressPart.GRIP),
                "legacy AR grip must remain active on the marksman platform");
        assertClose(helper, stats.handling(), 1.40D,
                "migrated SPR15HB handling must use the preserved grip coefficient");
        assertClose(helper, stats.average(), (1.04D + 1.10D + 1.20D + 1.30D + 1.40D + 1.08D) / 6.0D,
                "migrated SPR15HB average must use all six marksman parts");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void versionFiveMarksmanWithoutGripReceivesCompatibilityGrip(GameTestHelper helper) {
        ItemStack legacy = assembledM4Gun();
        CompoundTag root = legacy.getOrCreateTag().getCompound(GunsmithGunStats.ROOT_KEY);
        root.putInt(GunsmithGunStats.VERSION_KEY, 5);
        root.putString("platform", GunsmithPlatform.MARKSMAN.id());
        root.putString("template", GunsmithBlueprint.SPR15HB.templateId());
        root.putString("gunId", GunsmithBlueprint.SPR15HB.gunId().toString());
        root.getCompound(GunsmithGunStats.PARTS_KEY).remove(GunsmithPressPart.GRIP.id());

        GunsmithGunStats stats = GunsmithGunStats.from(legacy);
        helper.assertTrue(stats != null && stats.parts().size() == 6,
                "v5 five-part marksman gun must remain readable as six-part data");
        GunsmithGunStats.PartSummary grip = stats.parts().stream()
                .filter(part -> part.part() == GunsmithPressPart.GRIP)
                .findFirst()
                .orElseThrow();
        helper.assertTrue(grip.quality() == GunsmithPartQuality.COMMON
                        && grip.variant() == GunsmithPartVariant.BASE,
                "v5 marksman migration must add a neutral common base grip");
        assertClose(helper, grip.coefficient(), 1.0D,
                "v5 marksman compatibility grip must preserve the old fixed handling");
        assertClose(helper, stats.handling(), 1.0D,
                "v5 marksman handling must remain unchanged after migration");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void versionSixSniperWithoutFiringPinReceivesCompatibilityFiringPin(GameTestHelper helper) {
        EnumMap<GunsmithPressPart, ItemStack> parts = new EnumMap<>(GunsmithPressPart.class);
        for (GunsmithPressPart part : GunsmithPlatform.SNIPER.supportedParts()) {
            parts.put(part, part(GunsmithPlatform.SNIPER, part, GunsmithPartQuality.COMMON, 1.0D));
        }
        ItemStack legacy = GunsmithAssemblyRecipe.assemble(new ItemStack(Items.IRON_HOE),
                GunsmithBlueprintItem.createStack(ModMunitionsItems.GUNSMITH_BLUEPRINT.get(),
                        GunsmithBlueprint.KAR98K), parts);
        CompoundTag root = legacy.getOrCreateTag().getCompound(GunsmithGunStats.ROOT_KEY);
        root.putInt(GunsmithGunStats.VERSION_KEY, 6);
        root.getCompound(GunsmithGunStats.PARTS_KEY).remove(GunsmithPressPart.FIRING_PIN.id());

        GunsmithGunStats stats = GunsmithGunStats.from(legacy);
        helper.assertTrue(stats != null && stats.parts().size() == 5,
                "v6 four-part sniper gun must remain readable as five-part data");
        GunsmithGunStats.PartSummary firingPin = stats.parts().stream()
                .filter(part -> part.part() == GunsmithPressPart.FIRING_PIN)
                .findFirst()
                .orElseThrow();
        helper.assertTrue(firingPin.quality() == GunsmithPartQuality.COMMON
                        && firingPin.variant() == GunsmithPartVariant.BASE,
                "v6 sniper migration must add a neutral common base firing pin");
        assertClose(helper, firingPin.coefficient(), 1.0D,
                "v6 sniper compatibility firing pin must preserve old fixed handling");
        assertClose(helper, stats.handling(), 1.0D,
                "v6 sniper handling must remain unchanged after migration");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void versionFourArReceiverMistakeMigratesToSixPartBoltData(GameTestHelper helper) {
        ItemStack legacy = assembledM4Gun();
        CompoundTag root = legacy.getOrCreateTag().getCompound(GunsmithGunStats.ROOT_KEY);
        root.putInt(GunsmithGunStats.VERSION_KEY, 4);
        CompoundTag receiver = new CompoundTag();
        receiver.putString("quality", GunsmithPartQuality.LEGENDARY.id());
        receiver.putString("variant", "mk_ax_a_receiver");
        receiver.putDouble("coefficient", 1.43D);
        root.getCompound(GunsmithGunStats.PARTS_KEY).put(GunsmithPressPart.RECEIVER.id(), receiver);

        GunsmithGunStats stats = GunsmithGunStats.from(legacy);
        helper.assertTrue(stats != null, "v4 accidental AR receiver data must remain readable");
        helper.assertTrue(stats.parts().size() == 6,
                "v4 accidental seven-part AR data must normalize to the original six parts");
        helper.assertTrue(stats.parts().stream().noneMatch(part -> part.part() == GunsmithPressPart.RECEIVER),
                "migrated AR data must not retain a receiver slot");
        helper.assertTrue(stats.parts().stream().anyMatch(part ->
                        part.part() == GunsmithPressPart.BOLT
                                && part.variant() == GunsmithPartVariant.MK_AX_A_BOLT
                                && part.quality() == GunsmithPartQuality.LEGENDARY),
                "legacy MK-AX-A receiver data must become the AR bolt component");
        assertClose(helper, stats.damage(), 1.7875D,
                "migrated MK-AX-A bolt must use its corrected bolt coefficient and variant bonus");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void v2RedEastGunMigratesOldRangeCacheToCurrentPenalty(GameTestHelper helper) {
        EnumMap<GunsmithPressPart, ItemStack> parts = previewParts(GunsmithPlatform.AK);
        parts.put(GunsmithPressPart.CORE, GunsmithPartItem.createStack(
                ModMunitionsItems.GUNSMITH_PART.get(), GunsmithPlatform.AK, GunsmithPressPart.CORE,
                GunsmithPartQuality.LEGENDARY, GunsmithPartVariant.RED_EAST_HIGH_PRESSURE_GAS));
        ItemStack legacy = GunsmithAssemblyRecipe.assemble(new ItemStack(Items.IRON_HOE),
                GunsmithBlueprintItem.createStack(ModMunitionsItems.GUNSMITH_BLUEPRINT.get(), GunsmithBlueprint.AK47),
                parts);
        CompoundTag root = legacy.getOrCreateTag().getCompound(GunsmithGunStats.ROOT_KEY);
        root.putInt(GunsmithGunStats.VERSION_KEY, 2);
        root.getCompound(GunsmithGunStats.STATS_KEY).putDouble("damage", 2.40D);
        root.getCompound(GunsmithGunStats.STATS_KEY).putDouble("range", 1.0D);

        GunsmithGunStats stats = GunsmithGunStats.from(legacy);
        helper.assertTrue(stats != null, "v2 red east gun must remain readable");
        assertClose(helper, stats.range(), 0.858D,
                "v2 red east gun must migrate to the multiplicative range penalty");
        assertClose(helper, stats.effectiveRange(AK47_BASE_STATS), 44.616D,
                "migrated v2 red east gun must preserve quality scaling before applying the penalty");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void blueprintWithoutGunIdFailsStrictly(GameTestHelper helper) {
        ItemStack corrupt = new ItemStack(ModMunitionsItems.GUNSMITH_BLUEPRINT.get());
        boolean threw = false;
        try {
            GunsmithBlueprintItem.requireBlueprint(corrupt);
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        helper.assertTrue(threw, "blueprint NBT without GunId must throw instead of becoming M4");

        placeStructure(helper, Direction.NORTH);
        GunsmithAssemblyBenchBlockEntity be = requireBench(helper);
        be.inventory().setStackInSlot(GunsmithAssemblyBenchBlockEntity.SLOT_BLUEPRINT, corrupt);
        helper.assertFalse(be.inventory().isItemValid(
                        GunsmithAssemblyBenchBlockEntity.slotForPart(GunsmithPressPart.CORE),
                        part(GunsmithPlatform.AR, GunsmithPressPart.CORE,
                                GunsmithPartQuality.COMMON, 1.00D)),
                "a corrupt blueprint must be rejected by the non-throwing slot predicate");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH, timeoutTicks = 20)
    public static void platformDurabilityAndPermanentRepairWearAreDeterministic(GameTestHelper helper) {
        ItemStack ar = assembledM4Gun();
        ItemStack ak = GunsmithAssemblyRecipe.assemble(
                new ItemStack(Items.IRON_HOE),
                GunsmithBlueprintItem.createStack(
                        ModMunitionsItems.GUNSMITH_BLUEPRINT.get(), GunsmithBlueprint.AK47),
                previewParts(GunsmithPlatform.AK));

        GunsmithGunDurability.State arInitial = GunsmithGunDurability.view(ar);
        GunsmithGunDurability.State akInitial = GunsmithGunDurability.view(ak);
        helper.assertTrue(arInitial.maximum() == MunitionsConfig.GUN_DURABILITY_AR.get(),
                "new AR must use the AR platform durability config");
        helper.assertTrue(akInitial.maximum() == MunitionsConfig.GUN_DURABILITY_AK.get(),
                "new AK must use the AK platform durability config");
        helper.assertTrue(arInitial.maximum() != akInitial.maximum(),
                "AR and AK platform durability must remain independently configurable");

        int expectedLoss = Math.max(1, (int) Math.ceil(
                arInitial.originalMaximum() * MunitionsConfig.GUN_REPAIR_LOSS_AR.get()));
        int expectedNextMaximum = Math.max(
                GunsmithGunDurability.minimumMaximum(arInitial.originalMaximum()),
                arInitial.maximum() - expectedLoss);
        while (GunsmithGunDurability.view(ar).current() >= expectedNextMaximum) {
            helper.assertTrue(GunsmithGunDurability.consumeShot(ar).canFire(),
                    "wearing an intact AR must allow each shot");
        }
        GunsmithGunDurability.RepairPreview preview = GunsmithGunDurability.repairPreview(ar);
        helper.assertTrue(preview.available(), "sufficiently worn AR must become repairable");
        helper.assertTrue(preview.nextMaximum() == expectedNextMaximum,
                "repair preview must apply the configured permanent AR maximum loss");

        GunsmithGunDurability.RepairResult repaired = GunsmithGunDurability.repair(ar);
        helper.assertTrue(repaired.repaired(), "validated durability repair must succeed");
        helper.assertTrue(repaired.after().current() == expectedNextMaximum
                        && repaired.after().maximum() == expectedNextMaximum,
                "repair must refill to the newly reduced maximum");
        helper.assertTrue(repaired.after().repairs() == 1,
                "successful repair must persist its repair count");

        ItemStack legacy = assembledM4Gun();
        legacy.getTag().getCompound(GunsmithGunStats.ROOT_KEY)
                .remove(GunsmithGunDurability.DURABILITY_KEY);
        helper.assertFalse(legacy.getTag().getCompound(GunsmithGunStats.ROOT_KEY)
                        .contains(GunsmithGunDurability.DURABILITY_KEY),
                "legacy fixture must begin without durability data");
        GunsmithGunDurability.ensureInitialized(legacy);
        helper.assertTrue(legacy.getTag().getCompound(GunsmithGunStats.ROOT_KEY)
                        .contains(GunsmithGunDurability.DURABILITY_KEY, Tag.TAG_COMPOUND),
                "first authoritative use must migrate a legacy assembled gun to full durability");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH, timeoutTicks = 30)
    public static void assemblyBenchRepairConsumesGunAndPlatformServicePart(GameTestHelper helper) {
        placeStructure(helper, Direction.NORTH);
        GunsmithAssemblyBenchBlockEntity be = requireBench(helper);
        ItemStack gun = assembledM4Gun();
        GunsmithGunDurability.State initial = GunsmithGunDurability.view(gun);
        int repairLoss = Math.max(1, (int) Math.ceil(
                initial.originalMaximum() * MunitionsConfig.GUN_REPAIR_LOSS_AR.get()));
        int nextMaximum = Math.max(GunsmithGunDurability.minimumMaximum(initial.originalMaximum()),
                initial.maximum() - repairLoss);
        while (GunsmithGunDurability.view(gun).current() >= nextMaximum) {
            GunsmithGunDurability.consumeShot(gun);
        }
        be.inventory().setStackInSlot(GunsmithAssemblyBenchBlockEntity.SLOT_BLUEPRINT, gun);
        GunsmithPressPart repairPart = GunsmithGunDurability.repairPart(GunsmithPlatform.AR);
        be.inventory().setStackInSlot(GunsmithAssemblyBenchBlockEntity.slotForPart(repairPart),
                part(GunsmithPlatform.AR, repairPart, GunsmithPartQuality.COMMON, 1.0D));
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        boolean previousEnabled = MunitionsConfig.GUNSMITH_ENABLED.get();
        try {
            MunitionsConfig.GUNSMITH_ENABLED.set(true);
            helper.assertTrue(be.tryStartRepair(player, 6),
                    "worn gun plus its platform service part must start bench repair");
            helper.assertTrue(be.inventory().getStackInSlot(
                            GunsmithAssemblyBenchBlockEntity.SLOT_BLUEPRINT).isEmpty(),
                    "repair must consume the input gun into the pending result");
            helper.assertTrue(be.inventory().getStackInSlot(
                            GunsmithAssemblyBenchBlockEntity.slotForPart(repairPart)).isEmpty(),
                    "repair must consume exactly one matching platform service part");
            helper.runAfterDelay(8, () -> {
                try {
                    ItemStack output = be.inventory().getStackInSlot(
                            GunsmithAssemblyBenchBlockEntity.SLOT_OUTPUT);
                    helper.assertFalse(output.isEmpty(),
                            "completed repair must deliver the gun to output");
                    GunsmithGunDurability.State repaired = GunsmithGunDurability.view(output);
                    helper.assertTrue(repaired.repairs() == 1,
                            "bench repair output must record one repair");
                    helper.assertTrue(repaired.maximum() < initial.maximum()
                                    && repaired.current() == repaired.maximum(),
                            "bench repair must refill the gun while permanently lowering its maximum");
                } finally {
                    MunitionsConfig.GUNSMITH_ENABLED.set(previousEnabled);
                }
                helper.succeed();
            });
        } catch (RuntimeException exception) {
            MunitionsConfig.GUNSMITH_ENABLED.set(previousEnabled);
            throw exception;
        }
    }

    private static void fillCompleteRecipe(GunsmithAssemblyBenchBlockEntity be, GunsmithBlueprint blueprint) {
        be.inventory().setStackInSlot(GunsmithAssemblyBenchBlockEntity.SLOT_BLUEPRINT,
                GunsmithBlueprintItem.createStack(ModMunitionsItems.GUNSMITH_BLUEPRINT.get(), blueprint));
        fillParts(be, blueprint.platform());
    }

    private static void fillParts(GunsmithAssemblyBenchBlockEntity be, GunsmithPlatform platform) {
        if (platform == GunsmithPlatform.PISTOL) {
            setPart(be, platform, GunsmithPressPart.BARREL, GunsmithPartQuality.IMPROVED, 1.10D);
            setPart(be, platform, GunsmithPressPart.GRIP, GunsmithPartQuality.LEGENDARY, 1.40D);
            setPart(be, platform, GunsmithPressPart.SLIDE, GunsmithPartQuality.IMPROVED, 1.08D);
            setPart(be, platform, GunsmithPressPart.TRIGGER, GunsmithPartQuality.PRECISION, 1.30D);
            setPart(be, platform, GunsmithPressPart.HAMMER, GunsmithPartQuality.MILSPEC, 1.20D);
            return;
        }
        setPart(be, platform, GunsmithPressPart.CORE, GunsmithPartQuality.COMMON, 1.04D);
        setPart(be, platform, GunsmithPressPart.BARREL, GunsmithPartQuality.IMPROVED, 1.10D);
        setPart(be, platform, GunsmithPressPart.BOLT, GunsmithPartQuality.MILSPEC, 1.20D);
        setPart(be, platform, GunsmithPressPart.HANDGUARD, GunsmithPartQuality.PRECISION, 1.30D);
        setPart(be, platform, GunsmithPressPart.GRIP, GunsmithPartQuality.LEGENDARY, 1.40D);
        setPart(be, platform, GunsmithPressPart.STOCK, GunsmithPartQuality.IMPROVED, 1.08D);
    }

    private static EnumMap<GunsmithPressPart, ItemStack> previewParts(GunsmithPlatform platform) {
        EnumMap<GunsmithPressPart, ItemStack> parts = new EnumMap<>(GunsmithPressPart.class);
        if (platform == GunsmithPlatform.PISTOL) {
            parts.put(GunsmithPressPart.BARREL,
                    part(platform, GunsmithPressPart.BARREL, GunsmithPartQuality.IMPROVED, 1.10D));
            parts.put(GunsmithPressPart.GRIP,
                    part(platform, GunsmithPressPart.GRIP, GunsmithPartQuality.LEGENDARY, 1.40D));
            parts.put(GunsmithPressPart.SLIDE,
                    part(platform, GunsmithPressPart.SLIDE, GunsmithPartQuality.IMPROVED, 1.08D));
            parts.put(GunsmithPressPart.TRIGGER,
                    part(platform, GunsmithPressPart.TRIGGER, GunsmithPartQuality.PRECISION, 1.30D));
            parts.put(GunsmithPressPart.HAMMER,
                    part(platform, GunsmithPressPart.HAMMER, GunsmithPartQuality.MILSPEC, 1.20D));
            return parts;
        }
        parts.put(GunsmithPressPart.CORE,
                part(platform, GunsmithPressPart.CORE, GunsmithPartQuality.COMMON, 1.04D));
        parts.put(GunsmithPressPart.BARREL,
                part(platform, GunsmithPressPart.BARREL, GunsmithPartQuality.IMPROVED, 1.10D));
        parts.put(GunsmithPressPart.BOLT,
                part(platform, GunsmithPressPart.BOLT, GunsmithPartQuality.MILSPEC, 1.20D));
        parts.put(GunsmithPressPart.HANDGUARD,
                part(platform, GunsmithPressPart.HANDGUARD, GunsmithPartQuality.PRECISION, 1.30D));
        parts.put(GunsmithPressPart.GRIP,
                part(platform, GunsmithPressPart.GRIP, GunsmithPartQuality.LEGENDARY, 1.40D));
        parts.put(GunsmithPressPart.STOCK,
                part(platform, GunsmithPressPart.STOCK, GunsmithPartQuality.IMPROVED, 1.08D));
        return parts;
    }

    private static ItemStack assembledM4Gun() {
        return GunsmithAssemblyRecipe.assemble(
                new ItemStack(Items.IRON_HOE),
                GunsmithBlueprintItem.createStack(
                        ModMunitionsItems.GUNSMITH_BLUEPRINT.get(), GunsmithBlueprint.M4A1),
                previewParts(GunsmithPlatform.AR));
    }

    private static void setPart(GunsmithAssemblyBenchBlockEntity be, GunsmithPlatform platform,
                                GunsmithPressPart part,
                                GunsmithPartQuality quality, double coefficient) {
        be.inventory().setStackInSlot(GunsmithAssemblyBenchBlockEntity.slotForPart(part),
                part(platform, part, quality, coefficient));
    }

    private static ItemStack part(GunsmithPlatform platform, GunsmithPressPart part,
                                  GunsmithPartQuality quality, double coefficient) {
        return GunsmithPartItem.createStack(
                ModMunitionsItems.GUNSMITH_PART.get(), platform, part, quality, coefficient);
    }

    private static void placeStructure(GameTestHelper helper, Direction facing) {
        GunsmithAssemblyBenchBlock block =
                (GunsmithAssemblyBenchBlock) ModMunitionsBlocks.GUNSMITH_ASSEMBLY_BENCH.get();
        for (GunsmithAssemblyBenchBlock.Part part : GunsmithAssemblyBenchBlock.Part.values()) {
            BlockState state = block.defaultBlockState()
                    .setValue(GunsmithAssemblyBenchBlock.FACING, facing)
                    .setValue(GunsmithAssemblyBenchBlock.PART, part)
                    .setValue(GunsmithAssemblyBenchBlock.ACTIVE, false);
            helper.setBlock(GunsmithAssemblyBenchBlock.partPos(MAIN_REL, facing, part), state);
        }
    }

    private static GunsmithAssemblyBenchBlockEntity requireBench(GameTestHelper helper) {
        if (!(helper.getLevel().getBlockEntity(helper.absolutePos(MAIN_REL))
                instanceof GunsmithAssemblyBenchBlockEntity be)) {
            throw new IllegalStateException("assembly bench block entity missing");
        }
        return be;
    }

    private static void assertStructureActive(GameTestHelper helper, Direction facing, boolean expected) {
        for (GunsmithAssemblyBenchBlock.Part part : GunsmithAssemblyBenchBlock.Part.values()) {
            BlockPos absolute = helper.absolutePos(GunsmithAssemblyBenchBlock.partPos(MAIN_REL, facing, part));
            helper.assertTrue(helper.getLevel().getBlockState(absolute)
                            .getValue(GunsmithAssemblyBenchBlock.ACTIVE) == expected,
                    part + " active state must be " + expected);
        }
    }

    private static void assertClose(GameTestHelper helper, double actual, double expected, String label) {
        helper.assertTrue(Math.abs(actual - expected) < 0.0000001D,
                label + " expected " + expected + " but was " + actual);
    }

    private static void assertPartSummary(GameTestHelper helper, GunsmithGunStats.PartSummary actual,
                                          GunsmithPressPart expectedPart, GunsmithPartQuality expectedQuality,
                                          double expectedCoefficient) {
        helper.assertTrue(actual.part() == expectedPart, "unexpected part summary order");
        helper.assertTrue(actual.quality() == expectedQuality,
                expectedPart + " must retain its quality");
        assertClose(helper, actual.coefficient(), expectedCoefficient,
                expectedPart + " must retain its coefficient");
    }

    private static void assertBlueprintSource(GameTestHelper helper, GunsmithBlueprint blueprint,
                                              String expectedGunId, String expectedNameKey,
                                              GunsmithPlatform expectedPlatform) {
        ResourceLocation gunId = ResourceLocation.tryParse(expectedGunId);
        if (gunId == null) {
            throw new IllegalArgumentException("Invalid test gun id: " + expectedGunId);
        }
        helper.assertTrue(blueprint.gunId().equals(gunId),
                blueprint + " must preserve source gun id " + expectedGunId);
        helper.assertTrue(blueprint.nameKey().equals(expectedNameKey),
                blueprint + " must preserve source localization key " + expectedNameKey);
        helper.assertTrue(blueprint.platform() == expectedPlatform,
                blueprint + " must use the " + expectedPlatform.id() + " component platform");
        helper.assertTrue(blueprint.templateId().equals(gunId.getPath()),
                blueprint + " template id must match the source gun path");
        helper.assertTrue(GunsmithBlueprint.require(gunId) == blueprint,
                expectedGunId + " must resolve back to its blueprint catalog entry");
    }

    private static void assertStatsRejected(GameTestHelper helper, ItemStack stack, String message) {
        boolean threw = false;
        try {
            GunsmithGunStats.from(stack);
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        helper.assertTrue(threw, message);
    }

    private static void assertFireModePolicyRejects(GameTestHelper helper, List<String> sourceModes,
                                                     List<String> finishedModes, String message) {
        boolean threw = false;
        try {
            GunsmithFireModePolicy.preserveAndSelectFirst(sourceModes, finishedModes);
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        helper.assertTrue(threw, message);
    }

    private static void assertBurstFireModePolicyRejects(GameTestHelper helper, List<String> sourceModes,
                                                          List<String> finishedModes, int burstCount,
                                                          boolean continuousBurst, String message) {
        boolean threw = false;
        try {
            GunsmithFireModePolicy.forceThreeRoundBurst(
                    sourceModes, finishedModes, "burst", burstCount, continuousBurst);
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        helper.assertTrue(threw, message);
    }
}
