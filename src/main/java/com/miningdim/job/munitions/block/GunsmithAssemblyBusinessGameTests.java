package com.miningdim.job.munitions.block;

import com.miningdim.core.MiningConstants;
import com.miningdim.economy.AbuseGuard;
import com.miningdim.economy.Currency;
import com.miningdim.economy.EconomyLedger;
import com.miningdim.economy.EconomyService;
import com.miningdim.economy.EconomyServices;
import com.miningdim.economy.IEconomyService;
import com.miningdim.economy.PlayerAbuseState;
import com.miningdim.economy.SqliteEconomyLedger;
import com.miningdim.job.IJobService;
import com.miningdim.job.JobId;
import com.miningdim.job.JobProgress;
import com.miningdim.job.JobServices;
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
import com.miningdim.testutil.ConfigBaseline;
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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.items.ItemStackHandler;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

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

    /**
     * 跨轮基线归位: 本批次会临时改下列配置项做探针, 先抹掉上一轮可能残留的值 (见 {@link ConfigBaseline})。
     *
     * 绝不能在这里把装配等级门与工费置 0 当"隔离基线": ForgeConfigSpec 的写入是落盘的, runGameTestServer 复用
     * run/world 存档, assemblyWorkFeeCredits 的下界就是 0, SPEC.correct() 不会纠回, 探针值会留在
     * run/world/serverconfig/miningdim-munitions.toml 里跨轮存活, 使装配工费 sink 在后续每一轮 (以及任何复用
     * 该存档的手动开服) 全局失效且毫无告警。要真开工的用例一律自带够级别、够余额的玩家上下文
     * ({@link #withGunsmithContext}), 而不是把生产配置改没。
     */
    @BeforeBatch(batch = BATCH)
    public static void resetConfigBaseline(ServerLevel level) {
        ConfigBaseline.resetToDefaults(
                MunitionsConfig.GUNSMITH_ENABLED,
                MunitionsConfig.ASSEMBLY_UNLOCK_LEVEL,
                MunitionsConfig.ASSEMBLY_WORK_FEE_CREDITS);
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH, timeoutTicks = 20)
    public static void completeAssemblyConsumesPartsAndOutputsStampedGunAfterAnimation(GameTestHelper helper) {
        Direction facing = Direction.NORTH;
        placeStructure(helper, facing);
        GunsmithAssemblyBenchBlockEntity be = requireBench(helper);
        fillCompleteRecipe(be, GunsmithBlueprint.M4A1);
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        GunsmithContext context = installGunsmithContext(player, 10, 100000L);

        try {
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
            restoreGunsmithContext(context);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH, timeoutTicks = 20)
    public static void inProgressAssemblySurvivesSaveLoadRoundTripAndStillDelivers(GameTestHelper helper) {
        placeStructure(helper, Direction.NORTH);
        GunsmithAssemblyBenchBlockEntity be = requireBench(helper);
        fillCompleteRecipe(be, GunsmithBlueprint.M4A1);
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        GunsmithContext context = installGunsmithContext(player, 10, 100000L);

        try {
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
            restoreGunsmithContext(context);
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
        GunsmithContext context = installGunsmithContext(player, 10, 100000L);

        try {
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
            restoreGunsmithContext(context);
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
        GunsmithContext context = installGunsmithContext(player, 10, 100000L);

        try {
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
            restoreGunsmithContext(context);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH, timeoutTicks = 20)
    public static void m1911AssemblyConsumesFivePartsAndMapsEveryPistolStat(GameTestHelper helper) {
        placeStructure(helper, Direction.NORTH);
        GunsmithAssemblyBenchBlockEntity be = requireBench(helper);
        fillCompleteRecipe(be, GunsmithBlueprint.M1911);
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        GunsmithContext context = installGunsmithContext(player, 10, 100000L);

        try {
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
            restoreGunsmithContext(context);
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
        GunsmithContext context = installGunsmithContext(player, 10, 100000L);

        try {
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
            restoreGunsmithContext(context);
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
        assertDamageCapAnchor(helper);
        EnumMap<GunsmithPressPart, ItemStack> parts = previewParts(GunsmithPlatform.AK);
        parts.put(GunsmithPressPart.CORE, GunsmithPartItem.createStack(
                ModMunitionsItems.GUNSMITH_PART.get(), GunsmithPlatform.AK, GunsmithPressPart.CORE,
                GunsmithPartQuality.LEGENDARY, GunsmithPartVariant.RED_EAST_HIGH_PRESSURE_GAS));
        GunsmithAssemblyRecipe.Preview preview =
                GunsmithAssemblyRecipe.preview(GunsmithBlueprint.AK47, parts, AK47_BASE_STATS);
        // 1.20 (MILSPEC 枪机品质系数) x 2.00 (传奇红东导气伤害乘数) = 2.40 已越过 2.25 总帽, 故预览伤害
        // 手算为 9.0 (AK 基础伤害) x 2.25 = 20.25。预览与成品必须同帽, 否则装配台显示一套、到手另一套 (审查 27)。
        assertClose(helper, preview.damage(), 20.25D,
                "legendary red east gas preview damage must be clamped by the total damage cap");
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
        assertClose(helper, stats.damage(), 2.25D,
                "assembled red east damage multiplier must be clamped to the 2.25 total cap");
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

    /**
     * 总帽必须真的咬得住最坏组合 (审查 27)。
     *
     * AK 平台上三件加伤互不排斥: 传奇枪机的品质系数上限 1.50 x 传奇红东高压导气核心的伤害 2.00 x
     * 赤雪-A 枪机型号的伤害 1.25 = 3.75 倍, 对 AK47 基础伤害 9.0 折算单发躯干 33.75 点, 80 血公服三发致死。
     * 期望值全部手算, 不回调 capDamageMultiplier / gunsmithDamageMultiplierCap 当预言机。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void damageMultiplierCapClampsTheAkDoubleSpecialCompound(GameTestHelper helper) {
        assertDamageCapAnchor(helper);
        EnumMap<GunsmithPressPart, ItemStack> parts = previewParts(GunsmithPlatform.AK);
        parts.put(GunsmithPressPart.CORE, GunsmithPartItem.createStack(
                ModMunitionsItems.GUNSMITH_PART.get(), GunsmithPlatform.AK, GunsmithPressPart.CORE,
                GunsmithPartQuality.LEGENDARY, GunsmithPartVariant.RED_EAST_HIGH_PRESSURE_GAS));
        parts.put(GunsmithPressPart.BOLT, GunsmithPartItem.createStack(
                ModMunitionsItems.GUNSMITH_PART.get(), GunsmithPlatform.AK, GunsmithPressPart.BOLT,
                GunsmithPartQuality.LEGENDARY, GunsmithPartVariant.RED_WINTER_CHIXUE_A_BOLT, 1.50D));

        GunsmithAssemblyRecipe.Preview preview =
                GunsmithAssemblyRecipe.preview(GunsmithBlueprint.AK47, parts, AK47_BASE_STATS);
        assertClose(helper, preview.damage(), 20.25D,
                "装配预览的伤害必须与成品同帽 (9.0 x 2.25), 不得显示未封顶的 9.0 x 3.75");

        ItemStack output = GunsmithAssemblyRecipe.assemble(new ItemStack(Items.IRON_HOE),
                GunsmithBlueprintItem.createStack(ModMunitionsItems.GUNSMITH_BLUEPRINT.get(),
                        GunsmithBlueprint.AK47), parts);
        GunsmithGunStats stats = GunsmithGunStats.from(output);
        helper.assertTrue(stats != null, "双特殊组件的 AK 仍必须是合法数据");
        assertClose(helper, stats.damage(), 2.25D,
                "1.50 x 2.00 x 1.25 = 3.75 必须被钳到 2.25");
        // 帽是读取期施加的: NBT 里缓存的仍是未封顶的品质系数, 封顶绝不能反写玩家的零件数值。
        assertClose(helper, output.getOrCreateTag().getCompound(GunsmithGunStats.ROOT_KEY)
                        .getCompound(GunsmithGunStats.STATS_KEY).getDouble("damage"), 1.50D,
                "总帽不得改写 NBT 里缓存的枪机品质系数");
        assertClose(helper, GunsmithStatMultipliers.of(stats, 10.0D).damage(), 2.25D,
                "下发给 TaCZ 的伤害乘子必须取封顶后的值");
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
        helper.succeed();
    }

    /**
     * 耐久段的版本号是硬校验, 不是迁移入口 (审查 70)。
     *
     * {@code write()} 恒写当前版本, 而耐久段与本子系统同批落地, 任何存档里都不会留下别的版本号 —— 出现别的
     * 版本号只可能是外部改档。原先那段用例手写 {@code version=1} 再断言"迁移成功", 是拿伪造数据喂出来的绿灯:
     * 被迁移的形态从未在世界里存在过。现在的契约是权威路径硬抛、只读路径降级成"非托管枪", 一律不替它编迁移。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void forgedDurabilityVersionIsRejectedInsteadOfSilentlyMigrated(GameTestHelper helper) {
        helper.assertTrue(GunsmithGunDurability.isManagedGun(assembledM4Gun()),
                "前提校验: 刚装配出来的枪本就是托管枪");

        ItemStack forged = assembledM4Gun();
        forged.getOrCreateTag().getCompound(GunsmithGunStats.ROOT_KEY)
                .getCompound(GunsmithGunDurability.DURABILITY_KEY)
                .putInt("version", 1);

        boolean threw = false;
        try {
            GunsmithGunDurability.view(forged);
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        helper.assertTrue(threw,
                "耐久段版本号对不上时权威读取路径必须抛, 而不是当成满耐久放行");
        helper.assertTrue(GunsmithGunDurability.tryManaged(forged) == null,
                "只读路径必须降级成非托管枪 (返回 null), 不得把异常放穿到包处理器");
        helper.assertFalse(GunsmithGunDurability.isManagedGun(forged),
                "版本号被改坏的枪不得再被判为可维修的托管枪");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void versionThreeSixPartArGunRemainsReadable(GameTestHelper helper) {
        // 主线 v3 六槽 M4 的真实形态: 型号 id 全是已发布的 "basic", Stats 里比本版多三个键。
        ItemStack legacy = legacyGunStack(3, "m4a1", "ar", "tacz:m4a1",
                legacyRiflePartTags("basic", "common", 1.04D),
                legacyStatsTag(1.20D, 1.10D, 1.04D, 1.08D, 1.30D, 1.40D, 7.12D / 6.0D, 1.0D));

        GunsmithGunStats stats = GunsmithGunStats.from(legacy);
        helper.assertTrue(stats != null, "v3 six-part AR gun must remain readable");
        helper.assertTrue(stats.parts().size() == 6, "v3 AR gun must retain its six legacy parts");
        helper.assertTrue(stats.parts().stream().allMatch(part -> part.variant() == GunsmithPartVariant.BASE),
                "every published \"basic\" component id must resolve to the base component");
        assertClose(helper, stats.damage(), 1.20D, "v3 AR gun damage must come from the bolt coefficient");
        assertClose(helper, stats.headshot(), 1.10D, "v3 AR gun headshot must come from the barrel coefficient");
        assertClose(helper, stats.range(), 1.04D, "v3 AR gun range must come from the core coefficient");
        assertClose(helper, stats.recoil(), 1.08D, "v3 AR gun recoil must come from the stock coefficient");
        assertClose(helper, stats.spread(), 1.30D, "v3 AR gun spread must come from the handguard coefficient");
        assertClose(helper, stats.handling(), 1.40D, "v3 AR gun handling must come from the grip coefficient");
        assertClose(helper, stats.average(), 7.12D / 6.0D, "v3 AR gun average must use all six coefficients");
        assertClose(helper, stats.fireRate(), 1.0D, "v3 AR gun must not gain an implicit special-part effect");
        assertClose(helper, stats.specialAdsSpeed(), 1.0D,
                "v3 AR gun must not gain an implicit ADS penalty");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void versionFiveAllBaseArGunReadsBackEveryStatFromHandWrittenNbt(GameTestHelper helper) {
        ItemStack legacy = legacyGunStack(5, "m4a1", "ar", "tacz:m4a1",
                legacyRiflePartTags("basic", "common", 1.04D),
                legacyStatsTag(1.20D, 1.10D, 1.04D, 1.08D, 1.30D, 1.40D, 7.12D / 6.0D, 1.0D));

        GunsmithGunStats stats = GunsmithGunStats.from(legacy);
        helper.assertTrue(stats != null && stats.parts().size() == 6,
                "a real v5 six-part AR gun must stay readable");
        assertClose(helper, stats.damage(), 1.20D, "v5 AR gun damage must come from the bolt coefficient");
        assertClose(helper, stats.range(), 1.04D, "v5 AR gun range must come from the core coefficient");
        assertClose(helper, stats.effectiveDamage(M4_BASE_STATS), 6.5D * 1.20D,
                "v5 AR effective damage must apply the bolt coefficient to the base profile");
        assertClose(helper, stats.effectiveRange(M4_BASE_STATS), 48.0D * 1.04D,
                "v5 AR effective range must apply the core coefficient to the base profile");
        assertClose(helper, stats.fireRate(), 1.0D, "an all-base v5 gun must have no fire-rate modifier");

        // v3 起 variant 是必填字段: 缺失说明数据畸形, 必须硬抛而不是默默降级成普通组件把玩家的势力组件抹掉。
        CompoundTag missingVariantParts = legacyRiflePartTags("basic", "common", 1.04D);
        missingVariantParts.getCompound("bolt").remove("variant");
        ItemStack malformed = legacyGunStack(5, "m4a1", "ar", "tacz:m4a1",
                missingVariantParts,
                legacyStatsTag(1.20D, 1.10D, 1.04D, 1.08D, 1.30D, 1.40D, 7.12D / 6.0D, 1.0D));
        assertStatsRejected(helper, malformed,
                "a v5 component without a stored variant id must be rejected as malformed data");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void versionFiveGehennaCoreArGunRecomputesRangeInsteadOfTrustingStaleCache(GameTestHelper helper) {
        // 主线把格赫娜导气核心的 range 强制写成 1.0, 而部件表里核心的实际系数是 1.43。这份"缓存与部件对不上"
        // 的数据正是世界里每一把 v3+ 格赫娜枪的真实形态: 拿它逐项精确比对就会把整批合法存量枪判成畸形。
        ItemStack legacy = legacyGunStack(5, "m4a1", "ar", "tacz:m4a1",
                legacyRiflePartTags("gehenna_high_speed_gas", "legendary", 1.43D),
                legacyStatsTag(1.20D, 1.10D, 1.00D, 1.08D, 1.30D, 1.40D, 7.51D / 6.0D, 1.25D));

        GunsmithGunStats stats = GunsmithGunStats.from(legacy);
        helper.assertTrue(stats != null,
                "a real v5 Gehenna gun must stay readable instead of throwing on its stale range cache");
        GunsmithGunStats.PartSummary core = stats.parts().stream()
                .filter(part -> part.part() == GunsmithPressPart.CORE)
                .findFirst()
                .orElseThrow();
        helper.assertTrue(core.variant() == GunsmithPartVariant.GEHENNA_GAS,
                "the published \"gehenna_high_speed_gas\" id must resolve to the Gehenna gas component");
        helper.assertTrue(core.quality() == GunsmithPartQuality.LEGENDARY,
                "a legacy Gehenna core must keep its stored legendary quality");
        assertClose(helper, stats.range(), 1.43D,
                "range must be recomputed from the installed core (1.43), not read back from the stale 1.0 cache");
        assertClose(helper, stats.effectiveRange(M4_BASE_STATS), 48.0D * 1.43D,
                "the recomputed range must reach the base profile unchanged");
        assertClose(helper, stats.damage(), 1.20D,
                "Gehenna gas must not touch damage; that still comes from the bolt");
        assertClose(helper, stats.fireRate(), 1.25D,
                "legendary Gehenna gas must keep its +25% fire rate");
        assertClose(helper, stats.specialSpread(), 1.15D,
                "legendary Gehenna gas must keep its +15% spread penalty");
        assertClose(helper, stats.specialRecoil(), 2.00D,
                "legendary Gehenna gas must keep its doubled recoil penalty");
        assertClose(helper, stats.average(), 7.51D / 6.0D,
                "the overall coefficient must average the six stored coefficients");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void versionThreeAllBaseAkGunReadsBackEveryStatFromHandWrittenNbt(GameTestHelper helper) {
        ItemStack legacy = legacyGunStack(3, "ak47", "ak", "tacz:ak47",
                legacyRiflePartTags("basic", "common", 1.04D),
                legacyStatsTag(1.20D, 1.10D, 1.04D, 1.08D, 1.30D, 1.40D, 7.12D / 6.0D, 1.0D));

        GunsmithGunStats stats = GunsmithGunStats.from(legacy);
        helper.assertTrue(stats != null, "a real v3 AK gun must stay readable");
        helper.assertTrue(stats.platform().equals(GunsmithPlatform.AK.id()),
                "a v3 AK gun must keep reporting the AK platform");
        helper.assertTrue(stats.parts().size() == 6 && stats.parts().stream()
                        .allMatch(part -> part.variant() == GunsmithPartVariant.BASE),
                "every published \"basic\" component id must resolve to the base component on AK too");
        assertClose(helper, stats.damage(), 1.20D, "v3 AK gun damage must come from the bolt coefficient");
        assertClose(helper, stats.range(), 1.04D, "v3 AK gun range must come from the core coefficient");
        assertClose(helper, stats.effectiveDamage(AK47_BASE_STATS), 9.0D * 1.20D,
                "v3 AK effective damage must apply the bolt coefficient to the AK base profile");
        assertClose(helper, stats.effectiveRange(AK47_BASE_STATS), 52.0D * 1.04D,
                "v3 AK effective range must apply the core coefficient to the AK base profile");
        assertClose(helper, stats.fireRate(), 1.0D, "an all-base v3 AK gun must have no fire-rate modifier");
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
        // 五槽 marksman 的真实形态: 没有握把槽, 其余五件的型号 id 都是已发布的 "basic"。
        CompoundTag parts = legacyRiflePartTags("basic", "common", 1.04D);
        parts.remove("grip");
        ItemStack legacy = legacyGunStack(5, "spr15hb", "marksman", "tacz:spr15hb", parts,
                legacyStatsTag(1.20D, 1.10D, 1.04D, 1.08D, 1.30D, 1.00D, 5.72D / 5.0D, 1.0D));

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
        // 四槽栓动步枪的真实形态: 没有撞针槽, 其余四件的型号 id 都是已发布的 "basic"。
        CompoundTag parts = new CompoundTag();
        parts.put("receiver", legacyPartTag("basic", "common", 1.00D));
        parts.put("stock", legacyPartTag("basic", "common", 1.00D));
        parts.put("barrel", legacyPartTag("basic", "common", 1.00D));
        parts.put("handguard", legacyPartTag("basic", "common", 1.00D));
        ItemStack legacy = legacyGunStack(6, "kar98", "sniper", "tacz:kar98", parts,
                legacyStatsTag(1.00D, 1.00D, 1.00D, 1.00D, 1.00D, 1.00D, 1.00D, 1.00D));

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
        // 中间版本误建的第七个机匣槽: 六件 "basic" 之外还挂着一件 "mk_ax_a_receiver"。
        CompoundTag parts = legacyRiflePartTags("basic", "common", 1.04D);
        parts.put("receiver", legacyPartTag("mk_ax_a_receiver", "legendary", 1.43D));
        ItemStack legacy = legacyGunStack(4, "m4a1", "ar", "tacz:m4a1", parts,
                legacyStatsTag(1.20D, 1.10D, 1.04D, 1.08D, 1.30D, 1.40D, 7.12D / 6.0D, 1.0D));

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
        // 1.43 (机匣带过来的系数) x 1.25 (MK-AX-A 传奇档伤害乘数) = 1.7875, 仍在 2.25 总帽之下。
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

        assertDurabilityConfigAnchors(helper);
        GunsmithGunDurability.State arInitial = GunsmithGunDurability.view(ar);
        GunsmithGunDurability.State akInitial = GunsmithGunDurability.view(ak);
        helper.assertTrue(arInitial.maximum() == 2000 && arInitial.current() == 2000
                        && arInitial.originalMaximum() == 2000 && arInitial.repairs() == 0,
                "a new AR must start at 2000/2000 with zero repairs, got " + arInitial);
        helper.assertTrue(akInitial.maximum() == 2400 && akInitial.current() == 2400,
                "a new AK must start at 2400/2400, got " + akInitial);

        // 期望值全部手算, 不回调 minimumMaximum / repairLoss: 永久磨损 = ceil(2000 x 0.10) = 200, 故第一次
        // 维修后的上限恒为 1800 (仍高于 30% 地板 600)。
        int expectedNextMaximum = 1800;
        while (GunsmithGunDurability.view(ar).current() >= expectedNextMaximum) {
            helper.assertTrue(GunsmithGunDurability.consumeShot(ar).canFire(),
                    "wearing an intact AR must allow each shot");
        }
        GunsmithGunDurability.RepairPreview preview = GunsmithGunDurability.repairPreview(ar);
        helper.assertTrue(preview.available(), "sufficiently worn AR must become repairable");
        helper.assertTrue(preview.nextMaximum() == expectedNextMaximum,
                "repair preview must drop the AR maximum by exactly 200 to 1800, got " + preview.nextMaximum());
        helper.assertTrue(preview.requiredPart() == GunsmithPressPart.BOLT,
                "the AR service part must remain the bolt");

        GunsmithGunDurability.RepairResult repaired = GunsmithGunDurability.repair(ar);
        helper.assertTrue(repaired.repaired(), "validated durability repair must succeed");
        helper.assertTrue(repaired.after().current() == expectedNextMaximum
                        && repaired.after().maximum() == expectedNextMaximum,
                "repair must refill to the newly reduced maximum");
        helper.assertTrue(repaired.after().originalMaximum() == 2000,
                "a repair must never move the original maximum");
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
        assertDurabilityConfigAnchors(helper);
        helper.assertTrue(MunitionsConfig.REPAIR_WORK_FEE_CREDITS.get() == 1500,
                "本用例按单次维修工费 1500 CP 手算余额期望值");
        ItemStack gun = wornArGun(helper, 1800);
        be.inventory().setStackInSlot(GunsmithAssemblyBenchBlockEntity.SLOT_BLUEPRINT, gun);
        // AR 的维修件是枪机; 替换件品质不得低于枪上装着的那件 (assembledM4Gun 的枪机是 MILSPEC 1.20)。
        be.inventory().setStackInSlot(
                GunsmithAssemblyBenchBlockEntity.slotForPart(GunsmithPressPart.BOLT),
                part(GunsmithPlatform.AR, GunsmithPressPart.BOLT, GunsmithPartQuality.MILSPEC, 1.20D));
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        GunsmithContext context = installGunsmithContext(player, 10, 100000L);
        try {
            helper.assertTrue(be.tryStartRepair(player, 6),
                    "worn gun plus its platform service part must start bench repair");
            helper.assertTrue(context.ledger().balance(player.getUUID(), Currency.CREDIT) == 98500L,
                    "bench repair must destroy exactly the 1500 CP work fee, balance left "
                            + context.ledger().balance(player.getUUID(), Currency.CREDIT));
            helper.assertTrue(be.inventory().getStackInSlot(
                            GunsmithAssemblyBenchBlockEntity.SLOT_BLUEPRINT).isEmpty(),
                    "repair must consume the input gun into the pending result");
            helper.assertTrue(be.inventory().getStackInSlot(
                            GunsmithAssemblyBenchBlockEntity.slotForPart(GunsmithPressPart.BOLT)).isEmpty(),
                    "repair must consume exactly one matching platform service part");
            helper.runAfterDelay(8, () -> {
                ItemStack output = be.inventory().getStackInSlot(
                        GunsmithAssemblyBenchBlockEntity.SLOT_OUTPUT);
                helper.assertFalse(output.isEmpty(),
                        "completed repair must deliver the gun to output");
                GunsmithGunDurability.State repaired = GunsmithGunDurability.view(output);
                helper.assertTrue(repaired.repairs() == 1,
                        "bench repair output must record one repair");
                helper.assertTrue(repaired.maximum() == 1800 && repaired.current() == 1800
                                && repaired.originalMaximum() == 2000,
                        "bench repair must refill to the permanently reduced 1800 maximum, got " + repaired);
                helper.succeed();
            });
        } finally {
            restoreGunsmithContext(context);
        }
    }

    // ============================================================
    // 耐久三条核心业务规则 (打空停火 / 满耐久与磨损不足不可修 / 修到 30% 地板永不可修)。
    // 期望值全部按配置默认值手算: AR 2000 发, 单次维修永久 -200 (10%), 地板 ceil(2000 x 0.30) = 600,
    // 故第 7 次维修恰好落到 600, 第 8 次必须 EXHAUSTED。一律不回调 minimumMaximum / repairLoss 当预言机。
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH, timeoutTicks = 40)
    public static void emptyDurabilityStopsFiringAndNeverGoesNegative(GameTestHelper helper) {
        assertDurabilityConfigAnchors(helper);
        ItemStack ar = assembledM4Gun();
        for (int shot = 1; shot <= 2000; shot++) {
            GunsmithGunDurability.ShotWear wear = GunsmithGunDurability.consumeShot(ar);
            helper.assertTrue(wear.canFire(), "shot " + shot + " of 2000 must be allowed");
            helper.assertTrue(wear.state().current() == 2000 - shot,
                    "shot " + shot + " must consume exactly one durability point");
            helper.assertTrue(wear.becameBroken() == (shot == 2000),
                    "the breaking edge must be reported on shot 2000 only, got shot " + shot);
        }

        GunsmithGunDurability.ShotWear dryFire = GunsmithGunDurability.consumeShot(ar);
        helper.assertFalse(dryFire.canFire(),
                "a gun at zero durability must refuse the next shot so the caller cancels the fire event");
        helper.assertTrue(dryFire.state().current() == 0,
                "a refused shot must not push durability below zero");
        helper.assertFalse(dryFire.becameBroken(),
                "the breaking edge must be reported exactly once, not on every later dry fire");
        helper.assertTrue(GunsmithGunDurability.view(ar).current() == 0,
                "a refused shot must not write a new durability value");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void repairIsRefusedWhileFullOrInsufficientlyWornWithoutTouchingState(GameTestHelper helper) {
        assertDurabilityConfigAnchors(helper);

        ItemStack pristine = assembledM4Gun();
        GunsmithGunDurability.RepairPreview full = GunsmithGunDurability.repairPreview(pristine);
        helper.assertTrue(full.status() == GunsmithGunDurability.RepairStatus.FULL,
                "an unfired gun must preview as FULL, got " + full.status());
        helper.assertFalse(full.available(), "a FULL preview must not be available");
        helper.assertTrue(full.restored() == 0, "a FULL preview must promise no restored durability");
        GunsmithGunDurability.RepairResult refusedFull = GunsmithGunDurability.repair(pristine);
        helper.assertFalse(refusedFull.repaired(), "repairing a full gun must fail");
        helper.assertTrue(refusedFull.status() == GunsmithGunDurability.RepairStatus.FULL,
                "a refused repair must report why it was refused");
        GunsmithGunDurability.State afterFull = GunsmithGunDurability.view(pristine);
        helper.assertTrue(afterFull.originalMaximum() == 2000 && afterFull.maximum() == 2000
                        && afterFull.current() == 2000 && afterFull.repairs() == 0,
                "a refused repair must leave every durability field untouched, got " + afterFull);

        // 只打 100 发: 下一次维修会把上限压到 1800, 而 current 还有 1900, 修了反而更差 -> 必须拒绝。
        ItemStack lightlyWorn = assembledM4Gun();
        for (int shot = 0; shot < 100; shot++) {
            helper.assertTrue(GunsmithGunDurability.consumeShot(lightlyWorn).canFire(),
                    "wearing an intact AR must allow each shot");
        }
        GunsmithGunDurability.RepairPreview insufficient = GunsmithGunDurability.repairPreview(lightlyWorn);
        helper.assertTrue(insufficient.status() == GunsmithGunDurability.RepairStatus.INSUFFICIENT_WEAR,
                "100 shots of wear must preview as INSUFFICIENT_WEAR, got " + insufficient.status());
        helper.assertTrue(insufficient.nextMaximum() == 1800,
                "the refused preview must still report the 1800 maximum a repair would impose");
        helper.assertTrue(insufficient.restored() == 0,
                "an INSUFFICIENT_WEAR preview must promise no restored durability");
        GunsmithGunDurability.RepairResult refusedWear = GunsmithGunDurability.repair(lightlyWorn);
        helper.assertFalse(refusedWear.repaired(), "repairing an insufficiently worn gun must fail");
        GunsmithGunDurability.State afterWear = GunsmithGunDurability.view(lightlyWorn);
        helper.assertTrue(afterWear.originalMaximum() == 2000 && afterWear.maximum() == 2000
                        && afterWear.current() == 1900 && afterWear.repairs() == 0,
                "a refused repair must leave every durability field untouched, got " + afterWear);
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH, timeoutTicks = 40)
    public static void sevenRepairsReachTheFloorAndTheEighthIsPermanentlyExhausted(GameTestHelper helper) {
        assertDurabilityConfigAnchors(helper);
        ItemStack ar = assembledM4Gun();

        int expectedMaximum = 2000;
        for (int repairs = 1; repairs <= 7; repairs++) {
            int nextMaximum = expectedMaximum - 200;
            while (GunsmithGunDurability.view(ar).current() >= nextMaximum) {
                helper.assertTrue(GunsmithGunDurability.consumeShot(ar).canFire(),
                        "wearing a repairable AR must allow each shot");
            }
            GunsmithGunDurability.RepairPreview preview = GunsmithGunDurability.repairPreview(ar);
            helper.assertTrue(preview.status() == GunsmithGunDurability.RepairStatus.AVAILABLE,
                    "repair " + repairs + " must still be available, got " + preview.status());
            helper.assertTrue(preview.nextMaximum() == nextMaximum,
                    "repair " + repairs + " must drop the maximum to " + nextMaximum
                            + ", got " + preview.nextMaximum());
            GunsmithGunDurability.RepairResult result = GunsmithGunDurability.repair(ar);
            helper.assertTrue(result.repaired(), "repair " + repairs + " must succeed");
            helper.assertTrue(result.after().maximum() == nextMaximum
                            && result.after().current() == nextMaximum
                            && result.after().originalMaximum() == 2000
                            && result.after().repairs() == repairs,
                    "repair " + repairs + " must refill to " + nextMaximum + ", got " + result.after());
            expectedMaximum = nextMaximum;
        }
        helper.assertTrue(expectedMaximum == 600,
                "seven AR repairs must land exactly on the 30% floor of 600, got " + expectedMaximum);

        GunsmithGunDurability.RepairPreview exhausted = GunsmithGunDurability.repairPreview(ar);
        helper.assertTrue(exhausted.status() == GunsmithGunDurability.RepairStatus.EXHAUSTED,
                "a gun already at the floor must preview as EXHAUSTED, got " + exhausted.status());
        helper.assertTrue(exhausted.nextMaximum() == 600 && exhausted.restored() == 0,
                "an EXHAUSTED preview must promise neither a lower maximum nor restored durability");

        // 再磨一发, 证明 EXHAUSTED 不是"因为现在是满的"而是"因为已经修到底了"。
        helper.assertTrue(GunsmithGunDurability.consumeShot(ar).canFire(),
                "a gun at the floor must still be able to fire out its remaining durability");
        GunsmithGunDurability.RepairPreview stillExhausted = GunsmithGunDurability.repairPreview(ar);
        helper.assertTrue(stillExhausted.status() == GunsmithGunDurability.RepairStatus.EXHAUSTED,
                "a worn gun at the floor must stay EXHAUSTED, got " + stillExhausted.status());
        GunsmithGunDurability.RepairResult refused = GunsmithGunDurability.repair(ar);
        helper.assertFalse(refused.repaired(), "a gun at the floor must never be repairable again");
        GunsmithGunDurability.State finalState = GunsmithGunDurability.view(ar);
        helper.assertTrue(finalState.originalMaximum() == 2000 && finalState.maximum() == 600
                        && finalState.current() == 599 && finalState.repairs() == 7,
                "a refused EXHAUSTED repair must leave every durability field untouched, got " + finalState);
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void assemblyBenchRefusesFullDurabilityRepairWithoutConsumingAnything(GameTestHelper helper) {
        placeStructure(helper, Direction.NORTH);
        GunsmithAssemblyBenchBlockEntity be = requireBench(helper);
        assertDurabilityConfigAnchors(helper);
        be.inventory().setStackInSlot(GunsmithAssemblyBenchBlockEntity.SLOT_BLUEPRINT, assembledM4Gun());
        be.inventory().setStackInSlot(
                GunsmithAssemblyBenchBlockEntity.slotForPart(GunsmithPressPart.BOLT),
                part(GunsmithPlatform.AR, GunsmithPressPart.BOLT, GunsmithPartQuality.MILSPEC, 1.20D));
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        GunsmithContext context = installGunsmithContext(player, 10, 100000L);
        try {
            // 零件与等级都合格, 唯一的拒绝理由是这把枪还是满耐久; 拒绝帧必须一件不吞、一分不扣。
            helper.assertFalse(be.tryStartRepair(player, 6),
                    "a full-durability gun must be refused by the assembly bench");
            helper.assertFalse(be.isAnimating(), "a refused repair must not animate");
            helper.assertTrue(context.ledger().balance(player.getUUID(), Currency.CREDIT) == 100000L,
                    "a refused repair must not charge the repair work fee, balance left "
                            + context.ledger().balance(player.getUUID(), Currency.CREDIT));
            ItemStack keptGun = be.inventory().getStackInSlot(
                    GunsmithAssemblyBenchBlockEntity.SLOT_BLUEPRINT);
            helper.assertFalse(keptGun.isEmpty(), "a refused repair must leave the gun in the blueprint slot");
            helper.assertTrue(GunsmithGunDurability.view(keptGun).repairs() == 0,
                    "a refused repair must not stamp a repair onto the gun");
            helper.assertFalse(be.inventory().getStackInSlot(
                            GunsmithAssemblyBenchBlockEntity.slotForPart(GunsmithPressPart.BOLT)).isEmpty(),
                    "a refused repair must leave the service part in its slot");
            helper.assertTrue(be.inventory().getStackInSlot(
                            GunsmithAssemblyBenchBlockEntity.SLOT_OUTPUT).isEmpty(),
                    "a refused repair must not create output");
        } finally {
            restoreGunsmithContext(context);
        }
        helper.succeed();
    }

    /**
     * 维修替换件的降级校验 (审查 30): {@link #assembledM4Gun()} 的枪机是 MILSPEC 1.20, 塞一件普通枪机必须被拒。
     *
     * 放行降级件的后果是最便宜的普通基础件就能给传奇 / 势力组件枪无限续命, 稀缺组件的成本退化成一次性投入。
     * 两道防线都要守: 槽位谓词在放入那一刻拒, 权威侧在开工那一帧再拒一次 (换枪后留在槽里的旧件走的正是后者)。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH, timeoutTicks = 40)
    public static void assemblyBenchRefusesDowngradedServicePartWithoutConsumingAnything(GameTestHelper helper) {
        placeStructure(helper, Direction.NORTH);
        GunsmithAssemblyBenchBlockEntity be = requireBench(helper);
        assertDurabilityConfigAnchors(helper);
        ItemStack gun = wornArGun(helper, 1800);
        ItemStack downgraded = part(GunsmithPlatform.AR, GunsmithPressPart.BOLT,
                GunsmithPartQuality.COMMON, 1.00D);
        be.inventory().setStackInSlot(GunsmithAssemblyBenchBlockEntity.SLOT_BLUEPRINT, gun);

        helper.assertFalse(be.inventory().isItemValid(
                        GunsmithAssemblyBenchBlockEntity.slotForPart(GunsmithPressPart.BOLT), downgraded),
                "低于原件品质的枪机必须连槽位谓词都过不了");
        // 绕过槽位谓词直接落槽: 真实链路里"先放好合格件再把枪换成更高品质的那把"就会产生这个状态。
        be.inventory().setStackInSlot(
                GunsmithAssemblyBenchBlockEntity.slotForPart(GunsmithPressPart.BOLT), downgraded);

        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        GunsmithContext context = installGunsmithContext(player, 10, 100000L);
        try {
            helper.assertFalse(be.tryStartRepair(player, 6),
                    "普通枪机不得给装着 MILSPEC 枪机的枪续命");
            helper.assertFalse(be.isAnimating(), "被拒的维修不得开始动画");
            helper.assertTrue(context.ledger().balance(player.getUUID(), Currency.CREDIT) == 100000L,
                    "被拒的维修不得扣走维修工费, 余额实得 "
                            + context.ledger().balance(player.getUUID(), Currency.CREDIT));
            ItemStack keptGun = be.inventory().getStackInSlot(
                    GunsmithAssemblyBenchBlockEntity.SLOT_BLUEPRINT);
            helper.assertFalse(keptGun.isEmpty(), "被拒的维修必须把枪原样留在槽里");
            GunsmithGunDurability.State kept = GunsmithGunDurability.view(keptGun);
            // wornArGun 打到 current 刚好低于 1800 就停, 故这里恒为 1799: 逐字段都不许被拒绝帧改动。
            helper.assertTrue(kept.repairs() == 0 && kept.maximum() == 2000
                            && kept.originalMaximum() == 2000 && kept.current() == 1799,
                    "被拒的维修不得在枪的耐久上留下任何痕迹, 实得 " + kept);
            ItemStack keptPart = be.inventory().getStackInSlot(
                    GunsmithAssemblyBenchBlockEntity.slotForPart(GunsmithPressPart.BOLT));
            helper.assertTrue(GunsmithPartItem.qualityOf(keptPart) == GunsmithPartQuality.COMMON
                            && keptPart.getCount() == 1,
                    "被拒的维修必须把那件普通枪机原样留在槽里");
            helper.assertTrue(be.inventory().getStackInSlot(
                            GunsmithAssemblyBenchBlockEntity.SLOT_OUTPUT).isEmpty(),
                    "被拒的维修不得产出");

            // 正对照: 同一把枪、同一台机器, 只把替换件换成同型号同品质就必须立刻开工 —— 上面的拒绝
            // 因此确实来自品质判定, 而不是等级门 / 工费 / 磨损度这些别的门。
            be.inventory().setStackInSlot(
                    GunsmithAssemblyBenchBlockEntity.slotForPart(GunsmithPressPart.BOLT),
                    part(GunsmithPlatform.AR, GunsmithPressPart.BOLT, GunsmithPartQuality.MILSPEC, 1.20D));
            helper.assertTrue(be.tryStartRepair(player, 6),
                    "正对照: 换成 MILSPEC 枪机后同一台必须能开工");
        } finally {
            restoreGunsmithContext(context);
        }
        helper.succeed();
    }

    // ============================================================
    // F048 装配等级门 + 工费 sink: L1 拒且图纸/零件原封不动; L10 余额恰好够时精确销毁 5000 CP;
    // L10 但余额差 1 CP (边界值) 全额作废且零件一件没扣 (先查后扣)。期望值按配置默认值手算成常量。
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH, timeoutTicks = 20)
    public static void assemblyEnforcesLevelGateAndWorkFeeSink(GameTestHelper helper) {
        helper.assertTrue(MunitionsConfig.ASSEMBLY_UNLOCK_LEVEL.get() == 5,
                "本用例按装配解锁等级 5 手算 (L1 拒 / L10 过); 配置默认值改了必须同步改本用例");
        helper.assertTrue(MunitionsConfig.ASSEMBLY_WORK_FEE_CREDITS.get() == 5000,
                "本用例按装配工费 5000 CP 手算余额期望值; 配置默认值改了必须同步改本用例");

        placeStructure(helper, Direction.NORTH);
        GunsmithAssemblyBenchBlockEntity be = requireBench(helper);
        fillCompleteRecipe(be, GunsmithBlueprint.M4A1);
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);

        // -- L1: 装配等级门未解锁 -> 拒绝, 图纸与全部零件槽原封不动, 且一分工费都不扣。
        GunsmithContext lowLevel = installGunsmithContext(player, 1, 5000L);
        try {
            helper.assertFalse(be.tryStartAssembly(player, new ItemStack(Items.IRON_HOE), 6),
                    "L1 player must not pass the assembly unlock gate (F048, unlock L5)");
            helper.assertTrue(be.inventory().getStackInSlot(GunsmithAssemblyBenchBlockEntity.SLOT_BLUEPRINT)
                            .is(ModMunitionsItems.GUNSMITH_BLUEPRINT.get()),
                    "a level-gate rejection must preserve the blueprint");
            for (GunsmithPressPart part : GunsmithBlueprint.M4A1.requiredParts()) {
                helper.assertFalse(be.inventory().getStackInSlot(
                                GunsmithAssemblyBenchBlockEntity.slotForPart(part)).isEmpty(),
                        "a level-gate rejection must preserve " + part);
            }
            helper.assertTrue(lowLevel.ledger().balance(player.getUUID(), Currency.CREDIT) == 5000L,
                    "a level-gate rejection must not charge the assembly work fee");
        } finally {
            restoreGunsmithContext(lowLevel);
        }

        // -- L10 但余额比工费差 1 CP (边界值) -> 全额作废, 图纸与零件分文不扣 (先查后扣)。
        GunsmithContext oneShort = installGunsmithContext(player, 10, 4999L);
        try {
            helper.assertFalse(be.tryStartAssembly(player, new ItemStack(Items.IRON_HOE), 6),
                    "a balance one credit short of the assembly work fee must reject the start");
            helper.assertFalse(be.isAnimating(), "a fee-rejected start must not animate");
            for (GunsmithPressPart part : GunsmithBlueprint.M4A1.requiredParts()) {
                helper.assertFalse(be.inventory().getStackInSlot(
                                GunsmithAssemblyBenchBlockEntity.slotForPart(part)).isEmpty(),
                        "a fee-rejected start must not consume " + part);
            }
            helper.assertTrue(oneShort.ledger().balance(player.getUUID(), Currency.CREDIT) == 4999L,
                    "a failed fee charge must leave the balance untouched, got "
                            + oneShort.ledger().balance(player.getUUID(), Currency.CREDIT));
        } finally {
            restoreGunsmithContext(oneShort);
        }

        // -- L10 且余额恰好够付 -> 开工并把 5000 CP 精确销毁到 0。
        GunsmithContext funded = installGunsmithContext(player, 10, 5000L);
        try {
            helper.assertTrue(be.tryStartAssembly(player, new ItemStack(Items.IRON_HOE), 6),
                    "L10 player with sufficient balance must start assembly");
            helper.assertTrue(funded.ledger().balance(player.getUUID(), Currency.CREDIT) == 0L,
                    "the assembly work fee sink must destroy exactly 5000 CP, balance left "
                            + funded.ledger().balance(player.getUUID(), Currency.CREDIT));
            for (GunsmithPressPart part : GunsmithBlueprint.M4A1.requiredParts()) {
                helper.assertTrue(be.inventory().getStackInSlot(
                                GunsmithAssemblyBenchBlockEntity.slotForPart(part)).isEmpty(),
                        "a charged assembly must consume " + part);
            }
        } finally {
            restoreGunsmithContext(funded);
        }
        helper.succeed();
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

    /**
     * 手写一把存量成品枪的 NBT。
     *
     * 迁移用例的 fixture 必须与被测实现异源: 用 {@link GunsmithAssemblyRecipe#assemble} 造出来再把版本号改小,
     * 等于拿本版写入器伪造旧版数据, 主线真实写出的形态 (Stats 里多 fireRate/verticalRecoil/inaccuracy 三个键、
     * 型号 id 是已发布的 "basic"/"gehenna_high_speed_gas"、格赫娜核心的 range 被强制写成 1.0) 一条都进不了
     * fixture, 于是整套迁移测试自证。
     */
    private static ItemStack legacyGunStack(int version, String templateId, String platformId,
                                            String gunId, CompoundTag parts, CompoundTag stats) {
        ItemStack stack = new ItemStack(Items.IRON_HOE);
        CompoundTag root = new CompoundTag();
        root.putInt(GunsmithGunStats.VERSION_KEY, version);
        root.putString("template", templateId);
        root.putString("platform", platformId);
        root.putString("gunId", gunId);
        root.put(GunsmithGunStats.PARTS_KEY, parts);
        root.put(GunsmithGunStats.STATS_KEY, stats);
        stack.getOrCreateTag().put(GunsmithGunStats.ROOT_KEY, root);
        return stack;
    }

    /** 主线写出的单件部件摘要: variant / quality / coefficient 三个字段, 型号写当时已发布的 id。 */
    private static CompoundTag legacyPartTag(String variantId, String qualityId, double coefficient) {
        CompoundTag tag = new CompoundTag();
        tag.putString("variant", variantId);
        tag.putString("quality", qualityId);
        tag.putDouble("coefficient", coefficient);
        return tag;
    }

    /** 主线写出的六槽步枪部件表; 核心的型号 id 与品质由调用方给, 其余五件固定为已发布的 "basic"。 */
    private static CompoundTag legacyRiflePartTags(String coreVariantId, String coreQualityId,
                                                   double coreCoefficient) {
        CompoundTag parts = new CompoundTag();
        parts.put("core", legacyPartTag(coreVariantId, coreQualityId, coreCoefficient));
        parts.put("barrel", legacyPartTag("basic", "improved", 1.10D));
        parts.put("bolt", legacyPartTag("basic", "milspec", 1.20D));
        parts.put("handguard", legacyPartTag("basic", "precision", 1.30D));
        parts.put("grip", legacyPartTag("basic", "legendary", 1.40D));
        parts.put("stock", legacyPartTag("basic", "improved", 1.08D));
        return parts;
    }

    /**
     * 主线写出的 Stats 缓存。除七个基础键外还多写 fireRate / verticalRecoil / inaccuracy 三个键 —— 当前读取器
     * 完全不看它们; fixture 里保留是为了证明这三个多余键不会让存量枪读不出来。
     */
    private static CompoundTag legacyStatsTag(double damage, double headshot, double range, double recoil,
                                              double spread, double handling, double average, double fireRate) {
        CompoundTag stats = new CompoundTag();
        stats.putDouble("damage", damage);
        stats.putDouble("headshot", headshot);
        stats.putDouble("range", range);
        stats.putDouble("recoil", recoil);
        stats.putDouble("spread", spread);
        stats.putDouble("handling", handling);
        stats.putDouble("average", average);
        stats.putDouble("fireRate", fireRate);
        stats.putDouble("verticalRecoil", 1.0D / recoil);
        stats.putDouble("inaccuracy", 1.0D / spread);
        return stats;
    }

    /**
     * 耐久用例的期望值 (2000 / 2400 / 200 / 1800 / 600) 全是按下列配置默认值手算的常量。默认值一改这里先红,
     * 直接指明原因, 而不是让下游一堆数字断言各自莫名其妙地挂掉。
     */
    private static void assertDurabilityConfigAnchors(GameTestHelper helper) {
        helper.assertTrue(MunitionsConfig.GUN_DURABILITY_AR.get() == 2000,
                "本组用例按 AR 最大耐久 2000 手算期望值");
        helper.assertTrue(MunitionsConfig.GUN_DURABILITY_AK.get() == 2400,
                "本组用例按 AK 最大耐久 2400 手算期望值");
        helper.assertTrue(Math.abs(MunitionsConfig.GUN_REPAIR_LOSS_AR.get() - 0.10D) < 0.0000001D,
                "本组用例按 AR 单次维修永久磨损 10% (=200 点) 手算期望值");
        helper.assertTrue(Math.abs(MunitionsConfig.GUN_REPAIR_MINIMUM_RATIO.get() - 0.30D) < 0.0000001D,
                "本组用例按 30% 维修地板 (=600 点, 恰好 7 次维修到底) 手算期望值");
    }

    /**
     * 整枪伤害总帽的手算锚点 (审查 27)。20.25 / 2.25 这些封顶期望值全按它算; 配置默认值一改这里先红,
     * 直接指明原因, 而不是让下游一串伤害断言各自莫名其妙地挂掉。
     */
    private static void assertDamageCapAnchor(GameTestHelper helper) {
        helper.assertTrue(Math.abs(MunitionsConfig.GUNSMITH_DAMAGE_MULTIPLIER_CAP.get() - 2.25D) < 0.0000001D,
                "本组用例按整枪伤害总帽 2.25 手算期望值");
    }

    /** 把一把新 AR 磨到 current 刚好低于 threshold, 使下一次维修落进 AVAILABLE。 */
    private static ItemStack wornArGun(GameTestHelper helper, int threshold) {
        ItemStack gun = assembledM4Gun();
        while (GunsmithGunDurability.view(gun).current() >= threshold) {
            helper.assertTrue(GunsmithGunDurability.consumeShot(gun).canFire(),
                    "wearing an intact AR must allow each shot");
        }
        return gun;
    }

    /**
     * 装/卸「枪匠开关 + 定级职业门面 + 内存账本经济」替身。
     *
     * 装配与维修的服务端权威路径都要过等级门与信用点工费 sink, 而批次基线把配置留在生产默认值 (装配 L5 /
     * 5000 CP, 维修 L4 / 1500 CP), 所以每个真开工的用例都得自带够级别、够余额的玩家上下文。集中在这里装卸,
     * 免得各用例各写一遍 finally 时漏还原, 污染同批次后续用例。
     */
    private static GunsmithContext installGunsmithContext(ServerPlayer player, int munitionsLevel, long credits) {
        boolean previousEnabled = MunitionsConfig.GUNSMITH_ENABLED.get();
        MunitionsConfig.GUNSMITH_ENABLED.set(true);
        IJobService previousJob = swapJob(new FixedLevelJobService(munitionsLevel));
        EconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService previousEconomy = swapEconomy(freshEconomy(ledger));
        if (credits > 0L) {
            ledger.credit(player.getUUID(), Currency.CREDIT, credits);
        }
        return new GunsmithContext(previousJob, previousEconomy, ledger, previousEnabled);
    }

    private static void restoreGunsmithContext(GunsmithContext context) {
        MunitionsConfig.GUNSMITH_ENABLED.set(context.previousEnabled());
        restoreJob(context.previousJob());
        restoreEconomy(context.previousEconomy());
    }

    private static IJobService swapJob(IJobService fake) {
        IJobService previous;
        try {
            previous = JobServices.jobService();
        } catch (IllegalStateException notRegistered) {
            previous = null;
        }
        JobServices.registerJobService(fake);
        return previous;
    }

    private static void restoreJob(IJobService previous) {
        if (previous != null) {
            JobServices.registerJobService(previous);
        } else {
            JobServices.reset();
        }
    }

    private static IEconomyService swapEconomy(IEconomyService fake) {
        IEconomyService previous = EconomyServices.isRegistered() ? EconomyServices.economyService() : null;
        EconomyServices.registerEconomyService(fake);
        return previous;
    }

    private static void restoreEconomy(IEconomyService previous) {
        if (previous != null) {
            EconomyServices.registerEconomyService(previous);
        } else {
            EconomyServices.reset();
        }
    }

    /** 真 EconomyService (内存账本 + AbuseGuard + 惰性玩家态解析器); tryCharge 走真 sink 语义。 */
    private static IEconomyService freshEconomy(EconomyLedger ledger) {
        Map<UUID, PlayerAbuseState> states = new HashMap<>();
        Function<UUID, PlayerAbuseState> resolver = id -> states.computeIfAbsent(id, key -> new PlayerAbuseState());
        return new EconomyService(ledger, new AbuseGuard(), resolver);
    }

    /** 一次 {@link #installGunsmithContext} 装上的全部替身与被顶掉的原值, 供 finally 逐个还原。 */
    private record GunsmithContext(IJobService previousJob, IEconomyService previousEconomy,
                                   EconomyLedger ledger, boolean previousEnabled) {
    }

    /** 定级职业门面替身 (level/grantXp 不计数, 仅供枪匠等级门读取)。 */
    private static final class FixedLevelJobService implements IJobService {
        private final int level;

        FixedLevelJobService(int level) {
            this.level = level;
        }

        @Override
        public int level(Player player, JobId job) {
            return level;
        }

        @Override
        public long totalXp(Player player, JobId job) {
            return 0L;
        }

        @Override
        public long grantXp(Player player, JobId job, long rawXp) {
            return rawXp;
        }

        @Override
        public JobProgress progress(Player player, JobId job) {
            throw new UnsupportedOperationException("not exercised by gunsmith assembly business tests");
        }
    }
}
