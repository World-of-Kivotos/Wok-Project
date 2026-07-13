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
import com.miningdim.job.munitions.gunsmith.GunsmithGunStats;
import com.miningdim.job.munitions.gunsmith.GunsmithGunTooltip;
import com.miningdim.job.munitions.gunsmith.GunsmithPartItem;
import com.miningdim.job.munitions.gunsmith.GunsmithPartQuality;
import com.miningdim.job.munitions.gunsmith.GunsmithPlatform;
import com.miningdim.job.munitions.gunsmith.GunsmithPressPart;
import com.miningdim.job.munitions.gunsmith.GunsmithTaczBridge;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

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
    public static void blueprintCatalogKeepsRifleSixPartSetsAndAddsM1911FivePartSet(GameTestHelper helper) {
        int arCount = 0;
        int akCount = 0;
        int pistolCount = 0;
        for (GunsmithBlueprint blueprint : GunsmithBlueprint.values()) {
            if (blueprint.platform() == GunsmithPlatform.AR) {
                arCount++;
            } else if (blueprint.platform() == GunsmithPlatform.AK) {
                akCount++;
            } else if (blueprint.platform() == GunsmithPlatform.PISTOL) {
                pistolCount++;
            }
            ItemStack stack = GunsmithBlueprintItem.createStack(
                    ModMunitionsItems.GUNSMITH_BLUEPRINT.get(), blueprint);
            helper.assertTrue(GunsmithBlueprintItem.requireBlueprint(stack) == blueprint,
                    blueprint + " blueprint NBT must decode to the same catalog entry");
            int expectedPartCount = blueprint.platform() == GunsmithPlatform.PISTOL ? 5 : 6;
            helper.assertTrue(blueprint.requiredParts().size() == expectedPartCount,
                    blueprint + " must require exactly " + expectedPartCount + " platform parts");
            helper.assertTrue(blueprint.requiredParts().equals(blueprint.platform().supportedParts()),
                    blueprint + " must use the platform's explicit supported part set");
            helper.assertTrue(GunsmithAssemblyRecipe.assembledGunId(stack).equals(blueprint.gunId()),
                    blueprint + " must keep assembling the blueprint's original gun id");
        }
        helper.assertTrue(GunsmithBlueprint.values().length == 9,
                "gunsmith blueprint catalog must contain eight rifles and M1911");
        helper.assertTrue(arCount == 5, "catalog must contain five M4-family blueprints");
        helper.assertTrue(akCount == 3, "catalog must contain three AK-family blueprints");
        helper.assertTrue(pistolCount == 1, "catalog must contain the M1911 pistol blueprint");
        helper.assertTrue(GunsmithBlueprint.M1911.requiredParts().containsAll(List.of(
                        GunsmithPressPart.BARREL, GunsmithPressPart.SLIDE, GunsmithPressPart.GRIP,
                        GunsmithPressPart.TRIGGER, GunsmithPressPart.HAMMER)),
                "M1911 must require barrel, slide, grip, trigger, and hammer");
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
    public static void v2GunRecordsOrderedImmutablePartSummaries(GameTestHelper helper) {
        ItemStack output = GunsmithAssemblyRecipe.assemble(
                new ItemStack(Items.IRON_HOE),
                GunsmithBlueprintItem.createStack(ModMunitionsItems.GUNSMITH_BLUEPRINT.get(), GunsmithBlueprint.M4A1),
                previewParts(GunsmithPlatform.AR));
        CompoundTag root = output.getOrCreateTag().getCompound(GunsmithGunStats.ROOT_KEY);
        helper.assertTrue(root.contains(GunsmithGunStats.VERSION_KEY, Tag.TAG_INT),
                "new guns must write an integer format version");
        helper.assertTrue(root.getInt(GunsmithGunStats.VERSION_KEY) == GunsmithGunStats.CURRENT_VERSION,
                "new guns must write format version 2");
        helper.assertTrue(root.getCompound(GunsmithGunStats.STATS_KEY).contains("range", Tag.TAG_DOUBLE),
                "new guns must write range in Stats");

        GunsmithGunStats stats = GunsmithGunStats.from(output);
        helper.assertTrue(stats != null, "new guns must expose part summaries");
        List<GunsmithGunStats.PartSummary> parts = stats.parts();
        helper.assertTrue(parts.size() == GunsmithBlueprint.M4A1.requiredParts().size(),
                "the complete rifle recipe must record its six installed parts");
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
                "complete six-part gun tooltip must use exactly eight rows instead of a long single column");
        for (int row = 5; row < 8; row++) {
            helper.assertTrue(tooltip.get(row).getSiblings().size() == 3,
                    "each installed-parts row must contain two parts separated into columns");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void v2GunRejectsPartsThatDoNotMatchItsBlueprint(GameTestHelper helper) {
        ItemStack missingPart = GunsmithAssemblyRecipe.assemble(
                new ItemStack(Items.IRON_HOE),
                GunsmithBlueprintItem.createStack(ModMunitionsItems.GUNSMITH_BLUEPRINT.get(), GunsmithBlueprint.M4A1),
                previewParts(GunsmithPlatform.AR));
        CompoundTag missingPartRoot = missingPart.getOrCreateTag().getCompound(GunsmithGunStats.ROOT_KEY);
        missingPartRoot.getCompound(GunsmithGunStats.PARTS_KEY).remove(GunsmithPressPart.STOCK.id());
        assertStatsRejected(helper, missingPart, "a v2 gun missing its stock must be rejected");

        ItemStack unknownPart = GunsmithAssemblyRecipe.assemble(
                new ItemStack(Items.IRON_HOE),
                GunsmithBlueprintItem.createStack(ModMunitionsItems.GUNSMITH_BLUEPRINT.get(), GunsmithBlueprint.M4A1),
                previewParts(GunsmithPlatform.AR));
        CompoundTag unknownPartRoot = unknownPart.getOrCreateTag().getCompound(GunsmithGunStats.ROOT_KEY);
        unknownPartRoot.getCompound(GunsmithGunStats.PARTS_KEY).put("unknown", new CompoundTag());
        assertStatsRejected(helper, unknownPart, "a v2 gun with an unknown part must be rejected");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void v2GunRejectsGunIdAndStatsThatDoNotMatchBlueprintParts(GameTestHelper helper) {
        ItemStack mismatchedGunId = assembledM4Gun();
        CompoundTag mismatchedGunRoot = mismatchedGunId.getOrCreateTag()
                .getCompound(GunsmithGunStats.ROOT_KEY);
        mismatchedGunRoot.putString("gunId", GunsmithBlueprint.AK47.gunId().toString());
        assertStatsRejected(helper, mismatchedGunId,
                "a v2 M4 gun carrying the AK47 gun id must be rejected");

        String[] statKeys = {"damage", "headshot", "range", "recoil", "spread", "handling", "average"};
        for (String statKey : statKeys) {
            ItemStack mismatchedStats = assembledM4Gun();
            CompoundTag stats = mismatchedStats.getOrCreateTag()
                    .getCompound(GunsmithGunStats.ROOT_KEY)
                    .getCompound(GunsmithGunStats.STATS_KEY);
            stats.putDouble(statKey, stats.getDouble(statKey) + 0.01D);
            assertStatsRejected(helper, mismatchedStats,
                    "a v2 gun with a " + statKey + " value inconsistent with its parts must be rejected");
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
        boolean partValidationThrew = false;
        try {
            be.inventory().isItemValid(GunsmithAssemblyBenchBlockEntity.slotForPart(GunsmithPressPart.CORE),
                    part(GunsmithPlatform.AR, GunsmithPressPart.CORE, GunsmithPartQuality.COMMON, 1.00D));
        } catch (IllegalArgumentException expected) {
            partValidationThrew = true;
        }
        helper.assertTrue(partValidationThrew,
                "a corrupt blueprint must fail part-slot validation instead of behaving like no blueprint");
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
}
