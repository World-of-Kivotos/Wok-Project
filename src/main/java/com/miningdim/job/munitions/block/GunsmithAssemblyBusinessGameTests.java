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
import com.miningdim.job.munitions.gunsmith.GunsmithGunFactory;
import com.miningdim.job.munitions.gunsmith.GunsmithGunStats;
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
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.EnumMap;

@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class GunsmithAssemblyBusinessGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "gunsmith_assembly";
    private static final BlockPos MAIN_REL = new BlockPos(1, 1, 1);
    private static final GunsmithBaseStats M4_BASE_STATS =
            new GunsmithBaseStats(6.5D, 1.5D, 810, 0.16D);
    private static final GunsmithBaseStats AK47_BASE_STATS =
            new GunsmithBaseStats(9.0D, 1.5D, 600, 0.20D);

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
                assertClose(helper, stats.recoil(), 1.06D, "recoil coefficient");
                assertClose(helper, stats.spread(), 1.30D, "spread coefficient");
                assertClose(helper, stats.handling(), 1.40D, "handling coefficient");
                assertClose(helper, stats.average(), 7.12D / 6.0D, "average coefficient");
                helper.assertTrue(stats.gunId().equals(GunsmithBlueprint.M4A1.gunId()),
                        "M4 blueprint must stamp the original TaCZ M4A1 id");
                assertClose(helper, stats.effectiveDamage(M4_BASE_STATS), 7.80D, "effective damage");
                assertClose(helper, stats.effectiveHeadshot(M4_BASE_STATS), 1.65D, "effective headshot");
                helper.assertTrue(stats.effectiveRpm(M4_BASE_STATS) == 859,
                        "effective RPM must round to 859");
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
            for (GunsmithPressPart part : GunsmithPressPart.values()) {
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
    public static void partSlotsRejectWrongPartAndAcceptEitherPlatform(GameTestHelper helper) {
        placeStructure(helper, Direction.NORTH);
        GunsmithAssemblyBenchBlockEntity be = requireBench(helper);
        int coreSlot = GunsmithAssemblyBenchBlockEntity.slotForPart(GunsmithPressPart.CORE);
        ItemStack wrongPart = part(GunsmithPlatform.AR, GunsmithPressPart.BARREL,
                GunsmithPartQuality.COMMON, 1.00D);
        ItemStack wrongPlatform = part(GunsmithPlatform.AK, GunsmithPressPart.CORE,
                GunsmithPartQuality.COMMON, 1.00D);
        ItemStack correct = part(GunsmithPlatform.AR, GunsmithPressPart.CORE,
                GunsmithPartQuality.COMMON, 1.00D);

        helper.assertTrue(be.inventory().insertItem(coreSlot, wrongPart, false).getCount() == 1,
                "core slot must reject an AR barrel");
        helper.assertTrue(be.inventory().insertItem(coreSlot, wrongPlatform, false).isEmpty(),
                "empty core slot must accept an AK core before a blueprint is selected");
        helper.assertTrue(be.inventory().extractItem(coreSlot, 1, false).getCount() == 1,
                "accepted AK core must be removable");
        helper.assertTrue(be.inventory().insertItem(coreSlot, correct, false).isEmpty(),
                "core slot must accept the matching AR core");
        helper.assertTrue(be.inventory().getStackInSlot(coreSlot).getCount() == 1,
                "accepted core must occupy exactly one slot item");
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
            for (GunsmithPressPart part : GunsmithPressPart.values()) {
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

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void blueprintCatalogContainsFiveArAndThreeAkVariants(GameTestHelper helper) {
        int arCount = 0;
        int akCount = 0;
        for (GunsmithBlueprint blueprint : GunsmithBlueprint.values()) {
            if (blueprint.platform() == GunsmithPlatform.AR) {
                arCount++;
            } else if (blueprint.platform() == GunsmithPlatform.AK) {
                akCount++;
            }
            ItemStack stack = GunsmithBlueprintItem.createStack(
                    ModMunitionsItems.GUNSMITH_BLUEPRINT.get(), blueprint);
            helper.assertTrue(GunsmithBlueprintItem.requireBlueprint(stack) == blueprint,
                    blueprint + " blueprint NBT must decode to the same catalog entry");
        }
        helper.assertTrue(GunsmithBlueprint.values().length == 8,
                "AK/M4 blueprint catalog must contain exactly eight guns");
        helper.assertTrue(arCount == 5, "catalog must contain five M4-family blueprints");
        helper.assertTrue(akCount == 3, "catalog must contain three AK-family blueprints");
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
            for (GunsmithPressPart part : GunsmithPressPart.values()) {
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
        GunsmithAssemblyRecipe.Preview m4 =
                GunsmithAssemblyRecipe.preview(GunsmithBlueprint.M4A1, arParts, M4_BASE_STATS);
        GunsmithAssemblyRecipe.Preview ak47 =
                GunsmithAssemblyRecipe.preview(GunsmithBlueprint.AK47, akParts, AK47_BASE_STATS);

        assertClose(helper, m4.damage(), 7.80D, "M4 preview damage");
        helper.assertTrue(m4.rpm() == 859, "M4 preview RPM must use the M4 base RPM");
        assertClose(helper, m4.adsTime(), 0.16D / 1.40D, "M4 preview ADS");
        assertClose(helper, ak47.damage(), 10.80D, "AK47 preview damage");
        helper.assertTrue(ak47.rpm() == 636, "AK47 preview RPM must use the AK47 base RPM");
        assertClose(helper, ak47.adsTime(), 0.20D / 1.40D, "AK47 preview ADS");
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
        helper.succeed();
    }

    private static void fillCompleteRecipe(GunsmithAssemblyBenchBlockEntity be, GunsmithBlueprint blueprint) {
        be.inventory().setStackInSlot(GunsmithAssemblyBenchBlockEntity.SLOT_BLUEPRINT,
                GunsmithBlueprintItem.createStack(ModMunitionsItems.GUNSMITH_BLUEPRINT.get(), blueprint));
        fillParts(be, blueprint.platform());
    }

    private static void fillParts(GunsmithAssemblyBenchBlockEntity be, GunsmithPlatform platform) {
        setPart(be, platform, GunsmithPressPart.CORE, GunsmithPartQuality.COMMON, 1.04D);
        setPart(be, platform, GunsmithPressPart.BARREL, GunsmithPartQuality.IMPROVED, 1.10D);
        setPart(be, platform, GunsmithPressPart.BOLT, GunsmithPartQuality.MILSPEC, 1.20D);
        setPart(be, platform, GunsmithPressPart.HANDGUARD, GunsmithPartQuality.PRECISION, 1.30D);
        setPart(be, platform, GunsmithPressPart.GRIP, GunsmithPartQuality.LEGENDARY, 1.40D);
        setPart(be, platform, GunsmithPressPart.STOCK, GunsmithPartQuality.IMPROVED, 1.08D);
    }

    private static EnumMap<GunsmithPressPart, ItemStack> previewParts(GunsmithPlatform platform) {
        EnumMap<GunsmithPressPart, ItemStack> parts = new EnumMap<>(GunsmithPressPart.class);
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
}
