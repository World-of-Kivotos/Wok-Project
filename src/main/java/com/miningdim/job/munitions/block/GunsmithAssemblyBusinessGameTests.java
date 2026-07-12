package com.miningdim.job.munitions.block;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.munitions.ModMunitionsBlocks;
import com.miningdim.job.munitions.ModMunitionsItems;
import com.miningdim.job.munitions.MunitionsConfig;
import com.miningdim.job.munitions.gunsmith.GunsmithGunStats;
import com.miningdim.job.munitions.gunsmith.GunsmithPartItem;
import com.miningdim.job.munitions.gunsmith.GunsmithPartQuality;
import com.miningdim.job.munitions.gunsmith.GunsmithPlatform;
import com.miningdim.job.munitions.gunsmith.GunsmithPressPart;
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

@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class GunsmithAssemblyBusinessGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "gunsmith_assembly";
    private static final BlockPos MAIN_REL = new BlockPos(1, 1, 1);

    private GunsmithAssemblyBusinessGameTests() {
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH, timeoutTicks = 20)
    public static void completeAssemblyConsumesPartsAndOutputsStampedGunAfterAnimation(GameTestHelper helper) {
        Direction facing = Direction.NORTH;
        placeStructure(helper, facing);
        GunsmithAssemblyBenchBlockEntity be = requireBench(helper);
        fillCompleteRecipe(be);
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        boolean previousEnabled = MunitionsConfig.GUNSMITH_ENABLED.get();

        try {
            MunitionsConfig.GUNSMITH_ENABLED.set(true);
            helper.assertTrue(be.tryStartAssembly(player, new ItemStack(Items.IRON_HOE), 6),
                    "complete recipe must start assembly");
            helper.assertTrue(be.inventory().getStackInSlot(GunsmithAssemblyBenchBlockEntity.SLOT_BLUEPRINT)
                            .is(ModMunitionsItems.M4_ASSEMBLY_TEMPLATE.get()),
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
                assertClose(helper, stats.effectiveDamage(), 7.80D, "effective damage");
                assertClose(helper, stats.effectiveHeadshot(), 1.65D, "effective headshot");
                helper.assertTrue(stats.effectiveRpm() == 859, "effective RPM must round to 859");
                assertClose(helper, stats.effectiveAdsTime(), 0.16D / 1.40D, "effective ADS time");
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
        fillCompleteRecipe(be);
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
    public static void partSlotsRejectWrongPartAndPlatform(GameTestHelper helper) {
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
        helper.assertTrue(be.inventory().insertItem(coreSlot, wrongPlatform, false).getCount() == 1,
                "core slot must reject an AK core");
        helper.assertTrue(be.inventory().insertItem(coreSlot, correct, false).isEmpty(),
                "core slot must accept the matching AR core");
        helper.assertTrue(be.inventory().getStackInSlot(coreSlot).getCount() == 1,
                "accepted core must occupy exactly one slot item");
        helper.succeed();
    }

    private static void fillCompleteRecipe(GunsmithAssemblyBenchBlockEntity be) {
        be.inventory().setStackInSlot(GunsmithAssemblyBenchBlockEntity.SLOT_BLUEPRINT,
                new ItemStack(ModMunitionsItems.M4_ASSEMBLY_TEMPLATE.get()));
        setPart(be, GunsmithPressPart.CORE, GunsmithPartQuality.COMMON, 1.04D);
        setPart(be, GunsmithPressPart.BARREL, GunsmithPartQuality.IMPROVED, 1.10D);
        setPart(be, GunsmithPressPart.BOLT, GunsmithPartQuality.MILSPEC, 1.20D);
        setPart(be, GunsmithPressPart.HANDGUARD, GunsmithPartQuality.PRECISION, 1.30D);
        setPart(be, GunsmithPressPart.GRIP, GunsmithPartQuality.LEGENDARY, 1.40D);
        setPart(be, GunsmithPressPart.STOCK, GunsmithPartQuality.IMPROVED, 1.08D);
    }

    private static void setPart(GunsmithAssemblyBenchBlockEntity be, GunsmithPressPart part,
                                GunsmithPartQuality quality, double coefficient) {
        be.inventory().setStackInSlot(GunsmithAssemblyBenchBlockEntity.slotForPart(part),
                part(GunsmithPlatform.AR, part, quality, coefficient));
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
