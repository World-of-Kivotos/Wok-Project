package com.miningdim.job.munitions.block;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.munitions.ModMunitionsBlocks;
import com.miningdim.job.munitions.ModMunitionsItems;
import com.miningdim.job.munitions.MunitionsConfig;
import com.miningdim.job.munitions.gunsmith.GunsmithPartItem;
import com.miningdim.job.munitions.gunsmith.GunsmithPartQuality;
import com.miningdim.job.munitions.gunsmith.GunsmithPartVariant;
import com.miningdim.job.munitions.gunsmith.GunsmithPlatform;
import com.miningdim.job.munitions.gunsmith.GunsmithPressPart;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.EnumMap;
import java.util.Map;

@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class GunsmithPressGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "gunsmith_press";
    private static final BlockPos PRESS_REL = new BlockPos(1, 1, 1);

    private GunsmithPressGameTests() {
    }

    @BeforeBatch(batch = BATCH)
    public static void useIsolatedPressEconomyBaseline(ServerLevel level) {
        MunitionsConfig.QUALITY_UNLOCK_COMMON.set(0);
        MunitionsConfig.PRESS_WORK_FEE_CREDITS.set(0);
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void pistolPlatformOffersFivePartsAndStartsHammerProduction(GameTestHelper helper) {
        helper.setBlock(PRESS_REL, ModMunitionsBlocks.GUNSMITH_PRESS.get().defaultBlockState());
        GunsmithPressBlockEntity press = requirePress(helper);

        helper.assertTrue(press.trySelectPlatform(GunsmithPlatform.PISTOL.index()),
                "press must accept the pistol platform");
        helper.assertTrue(press.selectedPart() == GunsmithPressPart.BARREL,
                "switching to pistol must normalize an AR-only selection to the first pistol part");
        helper.assertTrue(GunsmithPlatform.PISTOL.supportedParts().size() == 5,
                "pistol press selection must contain exactly five parts");
        helper.assertFalse(GunsmithPlatform.PISTOL.supports(GunsmithPressPart.CORE),
                "pistol press selection must exclude the rifle gas system");

        int hammerRow = compactRow(GunsmithPlatform.PISTOL, GunsmithPressPart.HAMMER);
        helper.assertTrue(press.trySelectPart(hammerRow), "press must select the pistol hammer by compact row");
        helper.assertTrue(press.selectedPart() == GunsmithPressPart.HAMMER,
                "compact pistol row must resolve to the hammer");
        helper.assertFalse(press.trySelectPart(99), "press must reject an unknown compact part row");
        helper.assertTrue(press.selectedPart() == GunsmithPressPart.HAMMER,
                "rejected selection must preserve the current part");

        press.inventory().setStackInSlot(GunsmithPressBlockEntity.SLOT_GUN_PARTS,
                new ItemStack(Items.IRON_INGOT, 64));
        press.inventory().setStackInSlot(GunsmithPressBlockEntity.SLOT_ALLOY,
                new ItemStack(Items.COPPER_INGOT, 64));
        press.inventory().setStackInSlot(GunsmithPressBlockEntity.SLOT_POLYMER,
                new ItemStack(Items.SLIME_BLOCK, 64));
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);

        helper.assertTrue(press.tryStartPreview(player), "complete hammer materials must start the press");
        helper.assertTrue(press.isPressing(), "pistol hammer production must put the press into its active state");
        helper.assertTrue(press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_GUN_PARTS).getCount() == 62,
                "common hammer production must consume two generic gun parts");
        helper.assertTrue(press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_ALLOY).getCount() == 60,
                "common hammer production must consume four alloy units");
        helper.assertTrue(press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_POLYMER).getCount() == 64,
                "common hammer production must not consume polymer");
        helper.assertTrue(press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_OUTPUT).isEmpty(),
                "press output must remain empty until production finishes");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void bullpupPlatformOffersReceiverAndStartsCommonReceiverProduction(GameTestHelper helper) {
        helper.setBlock(PRESS_REL, ModMunitionsBlocks.GUNSMITH_PRESS.get().defaultBlockState());
        GunsmithPressBlockEntity press = requirePress(helper);

        helper.assertTrue(press.trySelectPlatform(GunsmithPlatform.BULLPUP.index()),
                "press must accept the bullpup platform");
        helper.assertTrue(GunsmithPlatform.BULLPUP.supportedParts().size() == 5,
                "bullpup press selection must contain exactly five parts");
        helper.assertTrue(GunsmithPlatform.BULLPUP.supportedParts().containsAll(java.util.List.of(
                        GunsmithPressPart.CORE, GunsmithPressPart.BARREL, GunsmithPressPart.HANDGUARD,
                        GunsmithPressPart.GRIP, GunsmithPressPart.RECEIVER)),
                "bullpup must support core, barrel, handguard, grip, and receiver");
        helper.assertFalse(GunsmithPlatform.BULLPUP.supports(GunsmithPressPart.BOLT),
                "bullpup must reject bolt");
        helper.assertFalse(GunsmithPlatform.BULLPUP.supports(GunsmithPressPart.STOCK),
                "bullpup must reject stock");
        assertIllegalCombination(helper, GunsmithPlatform.BULLPUP, GunsmithPressPart.BOLT);
        assertIllegalCombination(helper, GunsmithPlatform.BULLPUP, GunsmithPressPart.STOCK);

        int receiverRow = compactRow(GunsmithPlatform.BULLPUP, GunsmithPressPart.RECEIVER);
        helper.assertTrue(press.trySelectPart(receiverRow), "press must select the bullpup receiver by compact row");
        helper.assertTrue(press.selectedPart() == GunsmithPressPart.RECEIVER,
                "compact bullpup row must resolve to receiver");
        helper.assertTrue(press.selectedQuality() == GunsmithPartQuality.COMMON,
                "receiver production must start at common quality");

        press.inventory().setStackInSlot(GunsmithPressBlockEntity.SLOT_GUN_PARTS,
                new ItemStack(Items.IRON_INGOT, 64));
        press.inventory().setStackInSlot(GunsmithPressBlockEntity.SLOT_ALLOY,
                new ItemStack(Items.COPPER_INGOT, 64));
        press.inventory().setStackInSlot(GunsmithPressBlockEntity.SLOT_POLYMER,
                new ItemStack(Items.SLIME_BLOCK, 64));
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);

        helper.assertTrue(press.tryStartPreview(player), "complete receiver materials must start the press");
        helper.assertTrue(press.isPressing(), "bullpup receiver production must put the press into its active state");
        helper.assertTrue(press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_GUN_PARTS).getCount() == 58,
                "common receiver production must consume six generic gun parts");
        helper.assertTrue(press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_ALLOY).getCount() == 58,
                "common receiver production must consume six alloy units");
        helper.assertTrue(press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_POLYMER).getCount() == 59,
                "common receiver production must consume five polymer units");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void marksmanPlatformOffersOrderedSixPartsAndStartsHandguardProduction(GameTestHelper helper) {
        helper.setBlock(PRESS_REL, ModMunitionsBlocks.GUNSMITH_PRESS.get().defaultBlockState());
        GunsmithPressBlockEntity press = requirePress(helper);

        helper.assertTrue(press.trySelectPlatform(GunsmithPlatform.MARKSMAN.index()),
                "press must accept the marksman platform");
        helper.assertTrue(GunsmithPlatform.MARKSMAN.supportedParts().size() == 6,
                "marksman press selection must contain exactly six parts");
        helper.assertTrue(new java.util.ArrayList<>(GunsmithPlatform.MARKSMAN.supportedParts()).equals(java.util.List.of(
                        GunsmithPressPart.HANDGUARD, GunsmithPressPart.CORE, GunsmithPressPart.STOCK,
                        GunsmithPressPart.BOLT, GunsmithPressPart.BARREL, GunsmithPressPart.GRIP)),
                "marksman parts must retain the configured compact-row order");
        helper.assertTrue(GunsmithPlatform.MARKSMAN.supports(GunsmithPressPart.GRIP),
                "marksman must expose a grip slot");

        helper.assertTrue(press.trySelectPart(0), "first marksman compact row must select handguard");
        helper.assertTrue(press.selectedPart() == GunsmithPressPart.HANDGUARD,
                "first marksman compact row must resolve to handguard");

        press.inventory().setStackInSlot(GunsmithPressBlockEntity.SLOT_GUN_PARTS,
                new ItemStack(Items.IRON_INGOT, 64));
        press.inventory().setStackInSlot(GunsmithPressBlockEntity.SLOT_ALLOY,
                new ItemStack(Items.COPPER_INGOT, 64));
        press.inventory().setStackInSlot(GunsmithPressBlockEntity.SLOT_POLYMER,
                new ItemStack(Items.SLIME_BLOCK, 64));
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);

        helper.assertTrue(press.tryStartPreview(player), "complete handguard materials must start the press");
        helper.assertTrue(press.isPressing(), "marksman handguard production must put the press into its active state");
        helper.assertTrue(press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_GUN_PARTS).getCount() == 61,
                "common handguard production must consume three generic gun parts");
        helper.assertTrue(press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_ALLOY).getCount() == 62,
                "common handguard production must consume two alloy units");
        helper.assertTrue(press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_POLYMER).getCount() == 60,
                "common handguard production must consume four polymer units");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void sniperPlatformOffersOrderedFivePartsAndStartsFiringPinProduction(GameTestHelper helper) {
        helper.setBlock(PRESS_REL, ModMunitionsBlocks.GUNSMITH_PRESS.get().defaultBlockState());
        GunsmithPressBlockEntity press = requirePress(helper);

        helper.assertTrue(press.trySelectPlatform(GunsmithPlatform.SNIPER.index()),
                "press must accept the sniper platform");
        helper.assertTrue(GunsmithPlatform.SNIPER.supportedParts().size() == 5,
                "sniper press selection must contain exactly five parts");
        helper.assertTrue(new java.util.ArrayList<>(GunsmithPlatform.SNIPER.supportedParts()).equals(java.util.List.of(
                        GunsmithPressPart.RECEIVER, GunsmithPressPart.STOCK, GunsmithPressPart.BARREL,
                        GunsmithPressPart.HANDGUARD, GunsmithPressPart.FIRING_PIN)),
                "sniper parts must retain the configured compact-row order");
        helper.assertFalse(GunsmithPlatform.SNIPER.supports(GunsmithPressPart.CORE),
                "sniper must not expose a core slot");
        helper.assertFalse(GunsmithPlatform.SNIPER.supports(GunsmithPressPart.GRIP),
                "sniper must not expose a grip slot");
        helper.assertFalse(GunsmithPlatform.SNIPER.supports(GunsmithPressPart.BOLT),
                "sniper must not expose a bolt slot");
        assertIllegalCombination(helper, GunsmithPlatform.SNIPER, GunsmithPressPart.CORE);
        assertIllegalCombination(helper, GunsmithPlatform.SNIPER, GunsmithPressPart.GRIP);
        assertIllegalCombination(helper, GunsmithPlatform.SNIPER, GunsmithPressPart.BOLT);

        helper.assertTrue(press.selectedPart() == GunsmithPressPart.RECEIVER,
                "first sniper compact row must resolve to receiver");
        helper.assertTrue(press.trySelectPart(compactRow(GunsmithPlatform.SNIPER,
                        GunsmithPressPart.FIRING_PIN)),
                "press must select the sniper firing pin by compact row");
        helper.assertTrue(press.selectedPart() == GunsmithPressPart.FIRING_PIN,
                "sniper firing-pin compact row must select firing pin");
        helper.assertTrue(press.selectedQuality() == GunsmithPartQuality.COMMON,
                "firing-pin production must start at common quality");

        press.inventory().setStackInSlot(GunsmithPressBlockEntity.SLOT_GUN_PARTS,
                new ItemStack(Items.IRON_INGOT, 64));
        press.inventory().setStackInSlot(GunsmithPressBlockEntity.SLOT_ALLOY,
                new ItemStack(Items.COPPER_INGOT, 64));
        press.inventory().setStackInSlot(GunsmithPressBlockEntity.SLOT_POLYMER,
                new ItemStack(Items.SLIME_BLOCK, 64));
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);

        helper.assertTrue(press.tryStartPreview(player), "complete firing-pin materials must start the press");
        helper.assertTrue(press.isPressing(), "sniper firing-pin production must put the press into its active state");
        helper.assertTrue(press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_GUN_PARTS).getCount() == 60,
                "common firing-pin production must consume four generic gun parts");
        helper.assertTrue(press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_ALLOY).getCount() == 59,
                "common firing-pin production must consume five alloy units");
        helper.assertTrue(press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_POLYMER).getCount() == 64,
                "common firing-pin production must not consume polymer");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void machineGunPlatformOffersOrderedFivePartsAndStartsBipodProduction(GameTestHelper helper) {
        helper.setBlock(PRESS_REL, ModMunitionsBlocks.GUNSMITH_PRESS.get().defaultBlockState());
        GunsmithPressBlockEntity press = requirePress(helper);

        helper.assertTrue(press.trySelectPlatform(GunsmithPlatform.MACHINE_GUN.index()),
                "press must accept the machine gun platform");
        helper.assertTrue(press.selectedPart() == GunsmithPressPart.HANDGUARD,
                "switching to machine gun must normalize to the first configured part");
        helper.assertTrue(new java.util.ArrayList<>(GunsmithPlatform.MACHINE_GUN.supportedParts()).equals(java.util.List.of(
                        GunsmithPressPart.HANDGUARD, GunsmithPressPart.BOLT, GunsmithPressPart.BARREL,
                        GunsmithPressPart.STOCK, GunsmithPressPart.BIPOD)),
                "machine gun parts must retain the configured compact-row order");
        helper.assertFalse(GunsmithPlatform.MACHINE_GUN.supports(GunsmithPressPart.CORE),
                "machine gun must not expose a core slot");
        helper.assertFalse(GunsmithPlatform.MACHINE_GUN.supports(GunsmithPressPart.GRIP),
                "machine gun must not expose a grip slot");
        helper.assertFalse(GunsmithPlatform.MACHINE_GUN.supports(GunsmithPressPart.RECEIVER),
                "machine gun must not expose a receiver slot");
        assertIllegalCombination(helper, GunsmithPlatform.MACHINE_GUN, GunsmithPressPart.CORE);
        assertIllegalCombination(helper, GunsmithPlatform.MACHINE_GUN, GunsmithPressPart.GRIP);
        assertIllegalCombination(helper, GunsmithPlatform.MACHINE_GUN, GunsmithPressPart.RECEIVER);

        helper.assertTrue(press.trySelectPart(compactRow(GunsmithPlatform.MACHINE_GUN, GunsmithPressPart.BIPOD)),
                "press must select the machine gun bipod by compact row");
        helper.assertTrue(press.selectedPart() == GunsmithPressPart.BIPOD,
                "machine gun bipod compact row must resolve to bipod");
        press.inventory().setStackInSlot(GunsmithPressBlockEntity.SLOT_GUN_PARTS,
                new ItemStack(Items.IRON_INGOT, 64));
        press.inventory().setStackInSlot(GunsmithPressBlockEntity.SLOT_ALLOY,
                new ItemStack(Items.COPPER_INGOT, 64));
        press.inventory().setStackInSlot(GunsmithPressBlockEntity.SLOT_POLYMER,
                new ItemStack(Items.SLIME_BLOCK, 64));
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);

        helper.assertTrue(press.tryStartPreview(player), "complete bipod materials must start the press");
        helper.assertTrue(press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_GUN_PARTS).getCount() == 61,
                "common bipod production must consume three generic gun parts");
        helper.assertTrue(press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_ALLOY).getCount() == 60,
                "common bipod production must consume four alloy units");
        helper.assertTrue(press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_POLYMER).getCount() == 63,
                "common bipod production must consume one polymer unit");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void shotgunPlatformOffersOrderedFourPartsAndStartsBoltProduction(GameTestHelper helper) {
        helper.setBlock(PRESS_REL, ModMunitionsBlocks.GUNSMITH_PRESS.get().defaultBlockState());
        GunsmithPressBlockEntity press = requirePress(helper);

        helper.assertTrue(press.trySelectPlatform(GunsmithPlatform.SHOTGUN.index()),
                "press must accept the shotgun platform");
        helper.assertTrue(press.selectedPart() == GunsmithPressPart.STOCK,
                "switching to shotgun must normalize to the first configured part");
        helper.assertTrue(new java.util.ArrayList<>(GunsmithPlatform.SHOTGUN.supportedParts()).equals(java.util.List.of(
                        GunsmithPressPart.STOCK, GunsmithPressPart.BARREL,
                        GunsmithPressPart.BOLT, GunsmithPressPart.HANDGUARD)),
                "shotgun parts must retain stock, barrel, bolt, handguard order");
        helper.assertFalse(GunsmithPlatform.SHOTGUN.supports(GunsmithPressPart.CORE),
                "shotgun must not expose a gas-system slot");
        helper.assertFalse(GunsmithPlatform.SHOTGUN.supports(GunsmithPressPart.GRIP),
                "shotgun must not expose a grip slot");
        helper.assertFalse(GunsmithPlatform.SHOTGUN.supports(GunsmithPressPart.RECEIVER),
                "shotgun must not expose a separate receiver slot");
        assertIllegalCombination(helper, GunsmithPlatform.SHOTGUN, GunsmithPressPart.CORE);
        assertIllegalCombination(helper, GunsmithPlatform.SHOTGUN, GunsmithPressPart.GRIP);
        assertIllegalCombination(helper, GunsmithPlatform.SHOTGUN, GunsmithPressPart.RECEIVER);

        helper.assertTrue(press.trySelectPart(compactRow(GunsmithPlatform.SHOTGUN, GunsmithPressPart.BOLT)),
                "press must select the shotgun bolt by compact row");
        press.inventory().setStackInSlot(GunsmithPressBlockEntity.SLOT_GUN_PARTS,
                new ItemStack(Items.IRON_INGOT, 64));
        press.inventory().setStackInSlot(GunsmithPressBlockEntity.SLOT_ALLOY,
                new ItemStack(Items.COPPER_INGOT, 64));
        press.inventory().setStackInSlot(GunsmithPressBlockEntity.SLOT_POLYMER,
                new ItemStack(Items.SLIME_BLOCK, 64));
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);

        helper.assertTrue(press.tryStartPreview(player), "complete bolt materials must start the shotgun press");
        helper.assertTrue(press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_GUN_PARTS).getCount() == 58,
                "common shotgun bolt production must consume six generic gun parts");
        helper.assertTrue(press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_ALLOY).getCount() == 59,
                "common shotgun bolt production must consume five alloy units");
        helper.assertTrue(press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_POLYMER).getCount() == 64,
                "common shotgun bolt production must consume no polymer");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void smgPlatformOffersOrderedFivePartsAndStartsReceiverProduction(GameTestHelper helper) {
        helper.setBlock(PRESS_REL, ModMunitionsBlocks.GUNSMITH_PRESS.get().defaultBlockState());
        GunsmithPressBlockEntity press = requirePress(helper);

        helper.assertTrue(press.trySelectPlatform(GunsmithPlatform.SMG.index()),
                "press must accept the SMG platform");
        helper.assertTrue(press.selectedPart() == GunsmithPressPart.BARREL,
                "switching to SMG must normalize to the first configured part");
        helper.assertTrue(new java.util.ArrayList<>(GunsmithPlatform.SMG.supportedParts()).equals(java.util.List.of(
                        GunsmithPressPart.BARREL, GunsmithPressPart.STOCK, GunsmithPressPart.RECEIVER,
                        GunsmithPressPart.HANDGUARD, GunsmithPressPart.GRIP)),
                "SMG parts must retain barrel, stock, receiver, handguard, grip order");
        helper.assertFalse(GunsmithPlatform.SMG.supports(GunsmithPressPart.CORE),
                "SMG must not expose a gas-system slot");
        helper.assertFalse(GunsmithPlatform.SMG.supports(GunsmithPressPart.BOLT),
                "SMG must not expose a separate bolt slot");
        assertIllegalCombination(helper, GunsmithPlatform.SMG, GunsmithPressPart.CORE);
        assertIllegalCombination(helper, GunsmithPlatform.SMG, GunsmithPressPart.BOLT);

        helper.assertTrue(press.trySelectPart(compactRow(GunsmithPlatform.SMG, GunsmithPressPart.RECEIVER)),
                "press must select the SMG receiver by compact row");
        press.inventory().setStackInSlot(GunsmithPressBlockEntity.SLOT_GUN_PARTS,
                new ItemStack(Items.IRON_INGOT, 64));
        press.inventory().setStackInSlot(GunsmithPressBlockEntity.SLOT_ALLOY,
                new ItemStack(Items.COPPER_INGOT, 64));
        press.inventory().setStackInSlot(GunsmithPressBlockEntity.SLOT_POLYMER,
                new ItemStack(Items.SLIME_BLOCK, 64));
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);

        helper.assertTrue(press.tryStartPreview(player), "complete receiver materials must start the SMG press");
        helper.assertTrue(press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_GUN_PARTS).getCount() == 58,
                "common SMG receiver production must consume six generic gun parts");
        helper.assertTrue(press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_ALLOY).getCount() == 58,
                "common SMG receiver production must consume six alloy units");
        helper.assertTrue(press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_POLYMER).getCount() == 59,
                "common SMG receiver production must consume five polymer units");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void platformPartModelDataCoversFiveQualitiesWithoutChangingRifleCodes(GameTestHelper helper) {
        Map<GunsmithPressPart, Integer> pistolBases = new EnumMap<>(GunsmithPressPart.class);
        pistolBases.put(GunsmithPressPart.BARREL, 211);
        pistolBases.put(GunsmithPressPart.GRIP, 241);
        pistolBases.put(GunsmithPressPart.SLIDE, 261);
        pistolBases.put(GunsmithPressPart.TRIGGER, 271);
        pistolBases.put(GunsmithPressPart.HAMMER, 281);

        for (Map.Entry<GunsmithPressPart, Integer> entry : pistolBases.entrySet()) {
            for (GunsmithPartQuality quality : GunsmithPartQuality.values()) {
                ItemStack stack = GunsmithPartItem.createStack(
                        ModMunitionsItems.GUNSMITH_PART.get(), GunsmithPlatform.PISTOL, entry.getKey(), quality);
                helper.assertTrue(stack.getOrCreateTag().getInt("CustomModelData")
                                == entry.getValue() + quality.index(),
                        entry.getKey() + " " + quality + " must use its reserved pistol model code");
                helper.assertTrue(GunsmithPartItem.matches(stack, GunsmithPlatform.PISTOL, entry.getKey()),
                        "generated pistol part must decode to its platform and part");
            }
        }

        Map<GunsmithPressPart, Integer> bullpupBases = new EnumMap<>(GunsmithPressPart.class);
        bullpupBases.put(GunsmithPressPart.CORE, 301);
        bullpupBases.put(GunsmithPressPart.BARREL, 311);
        bullpupBases.put(GunsmithPressPart.HANDGUARD, 331);
        bullpupBases.put(GunsmithPressPart.GRIP, 341);
        bullpupBases.put(GunsmithPressPart.RECEIVER, 391);
        for (Map.Entry<GunsmithPressPart, Integer> entry : bullpupBases.entrySet()) {
            for (GunsmithPartQuality quality : GunsmithPartQuality.values()) {
                assertModelData(helper, GunsmithPlatform.BULLPUP, entry.getKey(), quality,
                        entry.getValue() + quality.index());
            }
        }

        Map<GunsmithPressPart, Integer> marksmanBases = new EnumMap<>(GunsmithPressPart.class);
        marksmanBases.put(GunsmithPressPart.HANDGUARD, 431);
        marksmanBases.put(GunsmithPressPart.CORE, 401);
        marksmanBases.put(GunsmithPressPart.STOCK, 451);
        marksmanBases.put(GunsmithPressPart.BOLT, 421);
        marksmanBases.put(GunsmithPressPart.BARREL, 411);
        marksmanBases.put(GunsmithPressPart.GRIP, 441);
        for (Map.Entry<GunsmithPressPart, Integer> entry : marksmanBases.entrySet()) {
            for (GunsmithPartQuality quality : GunsmithPartQuality.values()) {
                assertModelData(helper, GunsmithPlatform.MARKSMAN, entry.getKey(), quality,
                        entry.getValue() + quality.index());
            }
        }

        Map<GunsmithPressPart, Integer> sniperBases = new EnumMap<>(GunsmithPressPart.class);
        sniperBases.put(GunsmithPressPart.BARREL, 511);
        sniperBases.put(GunsmithPressPart.HANDGUARD, 531);
        sniperBases.put(GunsmithPressPart.STOCK, 551);
        sniperBases.put(GunsmithPressPart.RECEIVER, 591);
        sniperBases.put(GunsmithPressPart.FIRING_PIN, 10501);
        for (Map.Entry<GunsmithPressPart, Integer> entry : sniperBases.entrySet()) {
            for (GunsmithPartQuality quality : GunsmithPartQuality.values()) {
                assertModelData(helper, GunsmithPlatform.SNIPER, entry.getKey(), quality,
                        entry.getValue() + quality.index());
            }
        }

        Map<GunsmithPressPart, Integer> machineGunBases = new EnumMap<>(GunsmithPressPart.class);
        machineGunBases.put(GunsmithPressPart.BARREL, 611);
        machineGunBases.put(GunsmithPressPart.BOLT, 621);
        machineGunBases.put(GunsmithPressPart.HANDGUARD, 631);
        machineGunBases.put(GunsmithPressPart.STOCK, 651);
        machineGunBases.put(GunsmithPressPart.BIPOD, 701);
        for (Map.Entry<GunsmithPressPart, Integer> entry : machineGunBases.entrySet()) {
            for (GunsmithPartQuality quality : GunsmithPartQuality.values()) {
                assertModelData(helper, GunsmithPlatform.MACHINE_GUN, entry.getKey(), quality,
                        entry.getValue() + quality.index());
            }
        }

        Map<GunsmithPressPart, Integer> shotgunBases = new EnumMap<>(GunsmithPressPart.class);
        shotgunBases.put(GunsmithPressPart.BARREL, 711);
        shotgunBases.put(GunsmithPressPart.BOLT, 721);
        shotgunBases.put(GunsmithPressPart.HANDGUARD, 731);
        shotgunBases.put(GunsmithPressPart.STOCK, 751);
        for (Map.Entry<GunsmithPressPart, Integer> entry : shotgunBases.entrySet()) {
            for (GunsmithPartQuality quality : GunsmithPartQuality.values()) {
                assertModelData(helper, GunsmithPlatform.SHOTGUN, entry.getKey(), quality,
                        entry.getValue() + quality.index());
            }
        }

        Map<GunsmithPressPart, Integer> smgBases = new EnumMap<>(GunsmithPressPart.class);
        smgBases.put(GunsmithPressPart.BARREL, 811);
        smgBases.put(GunsmithPressPart.HANDGUARD, 831);
        smgBases.put(GunsmithPressPart.GRIP, 841);
        smgBases.put(GunsmithPressPart.STOCK, 851);
        smgBases.put(GunsmithPressPart.RECEIVER, 891);
        for (Map.Entry<GunsmithPressPart, Integer> entry : smgBases.entrySet()) {
            for (GunsmithPartQuality quality : GunsmithPartQuality.values()) {
                assertModelData(helper, GunsmithPlatform.SMG, entry.getKey(), quality,
                        entry.getValue() + quality.index());
            }
        }

        helper.assertTrue(GunsmithPlatform.byIndex(0) == GunsmithPlatform.AR,
                "AR platform index must remain zero");
        helper.assertTrue(GunsmithPlatform.byIndex(1) == GunsmithPlatform.AK,
                "AK platform index must remain one");
        helper.assertTrue(GunsmithPlatform.byIndex(2) == GunsmithPlatform.PISTOL,
                "pistol platform index must remain two");
        helper.assertTrue(GunsmithPlatform.byIndex(3) == GunsmithPlatform.BULLPUP,
                "bullpup platform index must remain three");
        helper.assertTrue(GunsmithPlatform.byIndex(4) == GunsmithPlatform.MARKSMAN,
                "marksman platform index must be four");
        helper.assertTrue(GunsmithPlatform.SNIPER.index() == 5,
                "sniper platform index must be five");
        helper.assertTrue(GunsmithPlatform.byIndex(5) == GunsmithPlatform.SNIPER,
                "sniper platform must decode from index five");
        helper.assertTrue(GunsmithPlatform.MACHINE_GUN.index() == 6,
                "machine gun platform index must be six");
        helper.assertTrue(GunsmithPlatform.byIndex(6) == GunsmithPlatform.MACHINE_GUN,
                "machine gun platform must decode from index six");
        helper.assertTrue(GunsmithPlatform.SHOTGUN.index() == 7,
                "shotgun platform index must be seven");
        helper.assertTrue(GunsmithPlatform.byIndex(7) == GunsmithPlatform.SHOTGUN,
                "shotgun platform must decode from index seven");
        helper.assertTrue(GunsmithPlatform.SMG.index() == 8,
                "SMG platform index must be eight");
        helper.assertTrue(GunsmithPlatform.byIndex(8) == GunsmithPlatform.SMG,
                "SMG platform must decode from index eight");
        helper.assertTrue(GunsmithPlatform.AR.supportedParts().size() == 6,
                "AR platform must retain its original six component slots");
        helper.assertFalse(GunsmithPlatform.AR.supports(GunsmithPressPart.RECEIVER),
                "AR platform must not expose a separate receiver slot");
        assertModelData(helper, GunsmithPlatform.AR, GunsmithPressPart.CORE, GunsmithPartQuality.COMMON, 1);
        assertModelData(helper, GunsmithPlatform.AR, GunsmithPressPart.STOCK, GunsmithPartQuality.LEGENDARY, 55);
        assertModelData(helper, GunsmithPlatform.AK, GunsmithPressPart.CORE, GunsmithPartQuality.COMMON, 101);
        assertModelData(helper, GunsmithPlatform.AK, GunsmithPressPart.STOCK, GunsmithPartQuality.LEGENDARY, 155);
        for (GunsmithPartQuality quality : GunsmithPartQuality.values()) {
            ItemStack gehenna = GunsmithPartItem.createStack(ModMunitionsItems.GUNSMITH_PART.get(),
                    GunsmithPlatform.AR, GunsmithPressPart.CORE, quality, GunsmithPartVariant.GEHENNA_GAS);
            helper.assertTrue(gehenna.getOrCreateTag().getInt("CustomModelData") == 10001 + quality.index(),
                    "gehenna gas quality must use its reserved model code");
            ItemStack redEast = GunsmithPartItem.createStack(ModMunitionsItems.GUNSMITH_PART.get(),
                    GunsmithPlatform.AK, GunsmithPressPart.CORE, quality,
                    GunsmithPartVariant.RED_EAST_HIGH_PRESSURE_GAS);
            helper.assertTrue(redEast.getOrCreateTag().getInt("CustomModelData") == 10101 + quality.index(),
                    "red east gas quality must use its reserved model code");
            ItemStack mkAxA = GunsmithPartItem.createStack(ModMunitionsItems.GUNSMITH_PART.get(),
                    GunsmithPlatform.AR, GunsmithPressPart.BOLT, quality,
                    GunsmithPartVariant.MK_AX_A_BOLT);
            helper.assertTrue(mkAxA.getOrCreateTag().getInt("CustomModelData") == 10201 + quality.index(),
                    "MK-AX-A bolt quality must use its reserved model code");
            ItemStack threeRoundBurst = GunsmithPartItem.createStack(ModMunitionsItems.GUNSMITH_PART.get(),
                    GunsmithPlatform.AR, GunsmithPressPart.BOLT, quality,
                    GunsmithPartVariant.AR_THREE_ROUND_BURST_BOLT);
            helper.assertTrue(threeRoundBurst.getOrCreateTag().getInt("CustomModelData")
                            == 10401 + quality.index(),
                    "three-round-burst bolt quality must use its reserved model code");
            ItemStack trinitySniperBarrel = GunsmithPartItem.createStack(
                    ModMunitionsItems.GUNSMITH_PART.get(), GunsmithPlatform.SNIPER,
                    GunsmithPressPart.BARREL, quality,
                    GunsmithPartVariant.TRINITY_PRECISION_GRADUATED_SNIPER_BARREL);
            helper.assertTrue(trinitySniperBarrel.getOrCreateTag().getInt("CustomModelData")
                            == 10601 + quality.index(),
                    "Trinity sniper barrel quality must use its reserved model code");
        }
        ItemStack legacyMkAxA = GunsmithPartItem.createStack(ModMunitionsItems.GUNSMITH_PART.get(),
                GunsmithPlatform.AR, GunsmithPressPart.BOLT, GunsmithPartQuality.COMMON,
                GunsmithPartVariant.MK_AX_A_BOLT);
        legacyMkAxA.getOrCreateTag().putString("GunsmithPart", "receiver");
        legacyMkAxA.getOrCreateTag().putString("GunsmithVariant", "mk_ax_a_receiver");
        GunsmithPartItem.PartData migrated = GunsmithPartItem.requirePartData(legacyMkAxA);
        helper.assertTrue(migrated.part() == GunsmithPressPart.BOLT
                        && migrated.variant() == GunsmithPartVariant.MK_AX_A_BOLT,
                "legacy AR receiver item must migrate into the existing bolt slot");
        helper.assertTrue("bolt".equals(legacyMkAxA.getOrCreateTag().getString("GunsmithPart"))
                        && "mk_ax_a_bolt".equals(legacyMkAxA.getOrCreateTag().getString("GunsmithVariant")),
                "legacy MK-AX-A item NBT must be rewritten to the corrected bolt ids");
        assertIllegalCombination(helper, GunsmithPlatform.PISTOL, GunsmithPressPart.CORE);
        assertIllegalCombination(helper, GunsmithPlatform.AR, GunsmithPressPart.SLIDE);
        assertIllegalCombination(helper, GunsmithPlatform.MACHINE_GUN, GunsmithPressPart.SLIDE);
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void pressOffersPlatformSpecificGasForArAndAkCore(GameTestHelper helper) {
        helper.setBlock(PRESS_REL, ModMunitionsBlocks.GUNSMITH_PRESS.get().defaultBlockState());
        GunsmithPressBlockEntity press = requirePress(helper);
        helper.assertTrue(press.trySelectVariant(GunsmithPartVariant.GEHENNA_GAS.index()),
                "AR/M4 core must accept the Gehenna gas variant");
        helper.assertTrue(press.selectedVariant() == GunsmithPartVariant.GEHENNA_GAS,
                "press must retain selected Gehenna gas");
        helper.assertTrue(press.trySelectPlatform(GunsmithPlatform.AK.index()),
                "press must select AK platform");
        helper.assertTrue(press.selectedVariant() == GunsmithPartVariant.BASE,
                "switching from AR to AK must clear the AR-only Gehenna gas");
        helper.assertTrue(press.trySelectVariant(GunsmithPartVariant.RED_EAST_HIGH_PRESSURE_GAS.index()),
                "AK core must accept red east gas");
        helper.assertTrue(press.selectedVariant() == GunsmithPartVariant.RED_EAST_HIGH_PRESSURE_GAS,
                "press must retain selected red east gas");
        helper.assertTrue(press.trySelectPart(compactRow(GunsmithPlatform.AK, GunsmithPressPart.BARREL)),
                "press must select AK barrel");
        helper.assertTrue(press.selectedVariant() == GunsmithPartVariant.BASE,
                "changing away from AK core must normalize the component variant");
        helper.assertTrue(press.trySelectPlatform(GunsmithPlatform.AR.index()),
                "press must switch back to AR platform");
        helper.assertTrue(press.trySelectPart(compactRow(GunsmithPlatform.AR, GunsmithPressPart.BOLT)),
                "press must select the existing AR bolt slot");
        helper.assertTrue(press.trySelectVariant(GunsmithPartVariant.MK_AX_A_BOLT.index()),
                "AR bolt slot must accept MK-AX-A");
        helper.assertTrue(press.selectedVariant() == GunsmithPartVariant.MK_AX_A_BOLT,
                "press must retain selected MK-AX-A bolt");
        helper.assertTrue(press.trySelectVariant(GunsmithPartVariant.AR_THREE_ROUND_BURST_BOLT.index()),
                "the same AR bolt slot must accept the three-round-burst bolt");
        helper.assertTrue(press.selectedVariant() == GunsmithPartVariant.AR_THREE_ROUND_BURST_BOLT,
                "press must retain the selected three-round-burst bolt");
        helper.assertTrue(press.trySelectPlatform(GunsmithPlatform.SNIPER.index()),
                "press must select the bolt-action rifle platform");
        helper.assertTrue(press.trySelectPart(compactRow(GunsmithPlatform.SNIPER, GunsmithPressPart.BARREL)),
                "press must select the sniper barrel slot");
        helper.assertTrue(press.trySelectVariant(
                        GunsmithPartVariant.TRINITY_PRECISION_GRADUATED_SNIPER_BARREL.index()),
                "sniper barrel slot must accept the Trinity precision-graduated variant");
        helper.assertTrue(press.selectedVariant()
                        == GunsmithPartVariant.TRINITY_PRECISION_GRADUATED_SNIPER_BARREL,
                "press must retain the selected Trinity sniper barrel");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void pressRejectsWrongMaterialsAndGatesEachSlotByType(GameTestHelper helper) {
        helper.setBlock(PRESS_REL, ModMunitionsBlocks.GUNSMITH_PRESS.get().defaultBlockState());
        GunsmithPressBlockEntity press = requirePress(helper);

        // 三槽只认对应材料, 且互不串 (审查 PRESS-MAT-01)。
        helper.assertFalse(press.inventory().isItemValid(
                        GunsmithPressBlockEntity.SLOT_GUN_PARTS, new ItemStack(Items.COBBLESTONE)),
                "gun-parts slot must reject cobblestone");
        helper.assertFalse(press.inventory().isItemValid(
                        GunsmithPressBlockEntity.SLOT_ALLOY, new ItemStack(Items.IRON_INGOT)),
                "alloy slot must reject iron (its material is copper)");
        helper.assertFalse(press.inventory().isItemValid(
                        GunsmithPressBlockEntity.SLOT_POLYMER, new ItemStack(Items.COPPER_INGOT)),
                "polymer slot must reject copper (its material is slime)");
        helper.assertTrue(press.inventory().isItemValid(
                        GunsmithPressBlockEntity.SLOT_ALLOY, new ItemStack(Items.COPPER_INGOT)),
                "alloy slot must accept copper");
        helper.assertTrue(press.inventory().isItemValid(
                        GunsmithPressBlockEntity.SLOT_POLYMER, new ItemStack(Items.SLIME_BLOCK)),
                "polymer slot must accept slime block");

        int receiverRow = compactRow(GunsmithPlatform.BULLPUP, GunsmithPressPart.RECEIVER);
        helper.assertTrue(press.trySelectPlatform(GunsmithPlatform.BULLPUP.index()),
                "press must accept the bullpup platform");
        helper.assertTrue(press.trySelectPart(receiverRow), "press must select the bullpup receiver");

        // 圆石冒充三槽 (RECEIVER 需 6/6/5) 严禁开工, 且不得消耗。
        press.inventory().setStackInSlot(GunsmithPressBlockEntity.SLOT_GUN_PARTS,
                new ItemStack(Items.COBBLESTONE, 64));
        press.inventory().setStackInSlot(GunsmithPressBlockEntity.SLOT_ALLOY,
                new ItemStack(Items.COBBLESTONE, 64));
        press.inventory().setStackInSlot(GunsmithPressBlockEntity.SLOT_POLYMER,
                new ItemStack(Items.COBBLESTONE, 64));
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        helper.assertFalse(press.tryStartPreview(player),
                "cobblestone stuffed into every slot must not start a real gun-part press");
        helper.assertFalse(press.isPressing(), "rejected press must stay idle");
        helper.assertTrue(press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_GUN_PARTS).getCount() == 64,
                "rejected press must not consume the fake gun-parts material");

        // 换成正确材料 -> 正常开工并按 6/6/5 消耗。
        press.inventory().setStackInSlot(GunsmithPressBlockEntity.SLOT_GUN_PARTS,
                new ItemStack(Items.IRON_INGOT, 64));
        press.inventory().setStackInSlot(GunsmithPressBlockEntity.SLOT_ALLOY,
                new ItemStack(Items.COPPER_INGOT, 64));
        press.inventory().setStackInSlot(GunsmithPressBlockEntity.SLOT_POLYMER,
                new ItemStack(Items.SLIME_BLOCK, 64));
        helper.assertTrue(press.tryStartPreview(player),
                "correct iron/copper/slime materials must start the receiver press");
        helper.assertTrue(press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_GUN_PARTS).getCount() == 58,
                "receiver press must consume six iron gun parts");
        helper.assertTrue(press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_ALLOY).getCount() == 58,
                "receiver press must consume six copper alloy");
        helper.assertTrue(press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_POLYMER).getCount() == 59,
                "receiver press must consume five slime polymer");
        helper.succeed();
    }

    private static GunsmithPressBlockEntity requirePress(GameTestHelper helper) {
        if (helper.getLevel().getBlockEntity(helper.absolutePos(PRESS_REL))
                instanceof GunsmithPressBlockEntity press) {
            return press;
        }
        throw new IllegalStateException("gunsmith press block entity missing");
    }

    private static int compactRow(GunsmithPlatform platform, GunsmithPressPart wanted) {
        int row = 0;
        for (GunsmithPressPart part : platform.supportedParts()) {
            if (part == wanted) {
                return row;
            }
            row++;
        }
        throw new IllegalArgumentException("part is not supported by platform: " + wanted);
    }

    private static void assertModelData(GameTestHelper helper, GunsmithPlatform platform,
                                        GunsmithPressPart part, GunsmithPartQuality quality, int expected) {
        ItemStack stack = GunsmithPartItem.createStack(
                ModMunitionsItems.GUNSMITH_PART.get(), platform, part, quality);
        helper.assertTrue(stack.getOrCreateTag().getInt("CustomModelData") == expected,
                platform + " " + part + " " + quality + " model code must remain " + expected);
    }

    private static void assertIllegalCombination(GameTestHelper helper, GunsmithPlatform platform,
                                                 GunsmithPressPart part) {
        boolean threw = false;
        try {
            GunsmithPartItem.createStack(
                    ModMunitionsItems.GUNSMITH_PART.get(), platform, part, GunsmithPartQuality.COMMON);
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        helper.assertTrue(threw, platform + " must reject unsupported part " + part);
    }
}
