package com.miningdim.job.munitions.block;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.munitions.ModMunitionsBlocks;
import com.miningdim.job.munitions.ModMunitionsItems;
import com.miningdim.job.munitions.gunsmith.GunsmithPartItem;
import com.miningdim.job.munitions.gunsmith.GunsmithPartQuality;
import com.miningdim.job.munitions.gunsmith.GunsmithPartVariant;
import com.miningdim.job.munitions.gunsmith.GunsmithPlatform;
import com.miningdim.job.munitions.gunsmith.GunsmithPressPart;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class GunsmithPressGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "gunsmith_press";
    private static final BlockPos PRESS_REL = new BlockPos(1, 1, 1);

    private GunsmithPressGameTests() {
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
    public static void marksmanPlatformOffersOrderedFivePartsAndStartsHandguardProduction(GameTestHelper helper) {
        helper.setBlock(PRESS_REL, ModMunitionsBlocks.GUNSMITH_PRESS.get().defaultBlockState());
        GunsmithPressBlockEntity press = requirePress(helper);

        helper.assertTrue(press.trySelectPlatform(GunsmithPlatform.MARKSMAN.index()),
                "press must accept the marksman platform");
        helper.assertTrue(GunsmithPlatform.MARKSMAN.supportedParts().size() == 5,
                "marksman press selection must contain exactly five parts");
        helper.assertTrue(new java.util.ArrayList<>(GunsmithPlatform.MARKSMAN.supportedParts()).equals(java.util.List.of(
                        GunsmithPressPart.HANDGUARD, GunsmithPressPart.CORE, GunsmithPressPart.STOCK,
                        GunsmithPressPart.BOLT, GunsmithPressPart.BARREL)),
                "marksman parts must retain the configured compact-row order");
        helper.assertFalse(GunsmithPlatform.MARKSMAN.supports(GunsmithPressPart.GRIP),
                "marksman must not expose a grip slot");
        assertIllegalCombination(helper, GunsmithPlatform.MARKSMAN, GunsmithPressPart.GRIP);

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
    public static void sniperPlatformOffersOrderedFourPartsAndStartsReceiverProduction(GameTestHelper helper) {
        helper.setBlock(PRESS_REL, ModMunitionsBlocks.GUNSMITH_PRESS.get().defaultBlockState());
        GunsmithPressBlockEntity press = requirePress(helper);

        helper.assertTrue(press.trySelectPlatform(GunsmithPlatform.SNIPER.index()),
                "press must accept the sniper platform");
        helper.assertTrue(GunsmithPlatform.SNIPER.supportedParts().size() == 4,
                "sniper press selection must contain exactly four parts");
        helper.assertTrue(new java.util.ArrayList<>(GunsmithPlatform.SNIPER.supportedParts()).equals(java.util.List.of(
                        GunsmithPressPart.RECEIVER, GunsmithPressPart.STOCK, GunsmithPressPart.BARREL,
                        GunsmithPressPart.HANDGUARD)),
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
        helper.assertTrue(press.trySelectPart(0), "press must select the first sniper compact row");
        helper.assertTrue(press.selectedPart() == GunsmithPressPart.RECEIVER,
                "first sniper compact row must select receiver");
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
        helper.assertTrue(press.isPressing(), "sniper receiver production must put the press into its active state");
        helper.assertTrue(press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_GUN_PARTS).getCount() == 58,
                "common receiver production must consume six generic gun parts");
        helper.assertTrue(press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_ALLOY).getCount() == 58,
                "common receiver production must consume six alloy units");
        helper.assertTrue(press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_POLYMER).getCount() == 59,
                "common receiver production must consume five polymer units");
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
    public static void arCoreOffersGehennaVariantAndRejectsOtherSlots(GameTestHelper helper) {
        List<GunsmithPartVariant> variants =
                GunsmithPartVariant.availableFor(GunsmithPlatform.AR, GunsmithPressPart.CORE);
        helper.assertTrue(variants.equals(List.of(
                        GunsmithPartVariant.BASIC, GunsmithPartVariant.GEHENNA_HIGH_SPEED_GAS)),
                "AR core must expose basic gas first and Gehenna high-speed gas second");
        helper.assertTrue(GunsmithPartVariant.GEHENNA_HIGH_SPEED_GAS.supports(
                        GunsmithPlatform.AR, GunsmithPressPart.CORE),
                "Gehenna high-speed gas must support the AR core slot");
        helper.assertFalse(GunsmithPartVariant.GEHENNA_HIGH_SPEED_GAS.supports(
                        GunsmithPlatform.AK, GunsmithPressPart.CORE),
                "Gehenna high-speed gas must reject the AK core slot");
        helper.assertFalse(GunsmithPartVariant.GEHENNA_HIGH_SPEED_GAS.supports(
                        GunsmithPlatform.AR, GunsmithPressPart.BARREL),
                "Gehenna high-speed gas must reject non-core AR slots");

        ItemStack valid = GunsmithPartItem.createStack(
                ModMunitionsItems.GUNSMITH_PART.get(), GunsmithPlatform.AR, GunsmithPressPart.CORE,
                GunsmithPartVariant.GEHENNA_HIGH_SPEED_GAS, GunsmithPartQuality.COMMON, 0.96D);
        helper.assertTrue(GunsmithPartItem.matches(valid, GunsmithPlatform.AR, GunsmithPressPart.CORE,
                        GunsmithPartVariant.GEHENNA_HIGH_SPEED_GAS),
                "a valid Gehenna AR core must decode with its special variant");
        assertIllegalVariantCombination(helper, GunsmithPlatform.AK, GunsmithPressPart.CORE,
                GunsmithPartVariant.GEHENNA_HIGH_SPEED_GAS);
        assertIllegalVariantCombination(helper, GunsmithPlatform.AR, GunsmithPressPart.BARREL,
                GunsmithPartVariant.GEHENNA_HIGH_SPEED_GAS);
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void partDataReadsLegacyAsBasicAndWritesExplicitVersionedVariant(GameTestHelper helper) {
        ItemStack legacy = GunsmithPartItem.createStack(
                ModMunitionsItems.GUNSMITH_PART.get(), GunsmithPlatform.AR, GunsmithPressPart.CORE,
                GunsmithPartVariant.BASIC, GunsmithPartQuality.COMMON, 1.00D);
        CompoundTag legacyTag = legacy.getOrCreateTag();
        legacyTag.remove("GunsmithPartDataVersion");
        legacyTag.remove("GunsmithVariant");

        GunsmithPartItem.PartData legacyData = GunsmithPartItem.requirePartData(legacy);
        helper.assertTrue(legacyData.variant() == GunsmithPartVariant.BASIC,
                "an unversioned part without a variant must migrate logically to basic");
        helper.assertFalse(legacyTag.contains("GunsmithPartDataVersion"),
                "reading a legacy part must not mutate it with a data version");
        helper.assertFalse(legacyTag.contains("GunsmithVariant"),
                "reading a legacy part must not mutate it with a variant");

        ItemStack current = GunsmithPartItem.createStack(
                ModMunitionsItems.GUNSMITH_PART.get(), GunsmithPlatform.AR, GunsmithPressPart.CORE,
                GunsmithPartVariant.GEHENNA_HIGH_SPEED_GAS, GunsmithPartQuality.LEGENDARY, 1.50D);
        CompoundTag currentTag = current.getOrCreateTag();
        helper.assertTrue(currentTag.contains("GunsmithPartDataVersion", Tag.TAG_INT),
                "new parts must write an integer data version");
        helper.assertTrue(currentTag.getInt("GunsmithPartDataVersion") == 2,
                "new parts must write the current data version 2");
        helper.assertTrue(currentTag.contains("GunsmithVariant", Tag.TAG_STRING),
                "new parts must write an explicit string variant");
        helper.assertTrue(currentTag.getString("GunsmithVariant").equals(
                        GunsmithPartVariant.GEHENNA_HIGH_SPEED_GAS.id()),
                "new special parts must persist the Gehenna variant id");
        helper.assertTrue(GunsmithPartItem.requirePartData(current).variant()
                        == GunsmithPartVariant.GEHENNA_HIGH_SPEED_GAS,
                "new special parts must decode their explicit variant");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH, timeoutTicks = 20)
    public static void pressPersistsAndProducesSelectedGehennaVariant(GameTestHelper helper) {
        helper.setBlock(PRESS_REL, ModMunitionsBlocks.GUNSMITH_PRESS.get().defaultBlockState());
        GunsmithPressBlockEntity press = requirePress(helper);

        helper.assertTrue(press.trySelectVariant(GunsmithPartVariant.GEHENNA_HIGH_SPEED_GAS.index()),
                "AR core press selection must accept Gehenna high-speed gas");
        helper.assertTrue(press.selectedVariant() == GunsmithPartVariant.GEHENNA_HIGH_SPEED_GAS,
                "press must retain the selected special variant");
        CompoundTag saved = new CompoundTag();
        press.saveAdditional(saved);
        helper.assertTrue(saved.getString("SelectedVariant").equals(
                        GunsmithPartVariant.GEHENNA_HIGH_SPEED_GAS.id()),
                "press save data must persist the selected special variant");

        helper.assertTrue(press.trySelectPlatform(GunsmithPlatform.AK.index()),
                "press must allow switching away from AR before the load round trip");
        helper.assertTrue(press.selectedVariant() == GunsmithPartVariant.BASIC,
                "switching to an incompatible platform must normalize the variant to basic");
        press.load(saved);
        helper.assertTrue(press.selectedPlatform() == GunsmithPlatform.AR,
                "press load must restore the selected AR platform");
        helper.assertTrue(press.selectedPart() == GunsmithPressPart.CORE,
                "press load must restore the selected core slot");
        helper.assertTrue(press.selectedVariant() == GunsmithPartVariant.GEHENNA_HIGH_SPEED_GAS,
                "press load must restore the selected Gehenna variant");

        press.inventory().setStackInSlot(GunsmithPressBlockEntity.SLOT_GUN_PARTS,
                new ItemStack(Items.IRON_INGOT, 64));
        press.inventory().setStackInSlot(GunsmithPressBlockEntity.SLOT_ALLOY,
                new ItemStack(Items.COPPER_INGOT, 64));
        press.inventory().setStackInSlot(GunsmithPressBlockEntity.SLOT_POLYMER,
                new ItemStack(Items.SLIME_BLOCK, 64));
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        helper.assertTrue(press.tryStartPreview(player),
                "complete materials must start Gehenna component production");

        CompoundTag inProgress = new CompoundTag();
        press.saveAdditional(inProgress);
        inProgress.putLong("ActiveUntilTick", Math.max(1L, helper.getLevel().getGameTime()));
        press.load(inProgress);
        helper.runAfterDelay(2, () -> {
            ItemStack output = press.inventory().getStackInSlot(GunsmithPressBlockEntity.SLOT_OUTPUT);
            helper.assertFalse(output.isEmpty(), "completed press run must create an output component");
            GunsmithPartItem.PartData outputData = GunsmithPartItem.requirePartData(output);
            helper.assertTrue(outputData.platform() == GunsmithPlatform.AR,
                    "pressed Gehenna component must retain the AR platform");
            helper.assertTrue(outputData.part() == GunsmithPressPart.CORE,
                    "pressed Gehenna component must retain the core slot");
            helper.assertTrue(outputData.variant() == GunsmithPartVariant.GEHENNA_HIGH_SPEED_GAS,
                    "pressed output must retain the selected Gehenna variant");
            helper.succeed();
        });
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
        assertModelData(helper, GunsmithPlatform.AR, GunsmithPressPart.CORE, GunsmithPartQuality.COMMON, 1);
        assertModelData(helper, GunsmithPlatform.AR, GunsmithPressPart.STOCK, GunsmithPartQuality.LEGENDARY, 55);
        assertModelData(helper, GunsmithPlatform.AR, GunsmithPressPart.CORE,
                GunsmithPartVariant.GEHENNA_HIGH_SPEED_GAS, GunsmithPartQuality.COMMON, 1_000_001);
        assertModelData(helper, GunsmithPlatform.AR, GunsmithPressPart.CORE,
                GunsmithPartVariant.GEHENNA_HIGH_SPEED_GAS, GunsmithPartQuality.LEGENDARY, 1_000_005);
        assertModelData(helper, GunsmithPlatform.AK, GunsmithPressPart.CORE, GunsmithPartQuality.COMMON, 101);
        assertModelData(helper, GunsmithPlatform.AK, GunsmithPressPart.STOCK, GunsmithPartQuality.LEGENDARY, 155);
        assertIllegalCombination(helper, GunsmithPlatform.PISTOL, GunsmithPressPart.CORE);
        assertIllegalCombination(helper, GunsmithPlatform.AR, GunsmithPressPart.SLIDE);
        assertIllegalCombination(helper, GunsmithPlatform.MACHINE_GUN, GunsmithPressPart.SLIDE);
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
        assertModelData(helper, platform, part, GunsmithPartVariant.BASIC, quality, expected);
    }

    private static void assertModelData(GameTestHelper helper, GunsmithPlatform platform,
                                        GunsmithPressPart part, GunsmithPartVariant variant,
                                        GunsmithPartQuality quality, int expected) {
        ItemStack stack = GunsmithPartItem.createStack(
                ModMunitionsItems.GUNSMITH_PART.get(), platform, part, variant, quality);
        helper.assertTrue(stack.getOrCreateTag().getInt("CustomModelData") == expected,
                platform + " " + part + " " + variant + " " + quality
                        + " model code must remain " + expected);
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

    private static void assertIllegalVariantCombination(GameTestHelper helper, GunsmithPlatform platform,
                                                        GunsmithPressPart part,
                                                        GunsmithPartVariant variant) {
        boolean threw = false;
        try {
            GunsmithPartItem.createStack(ModMunitionsItems.GUNSMITH_PART.get(), platform, part,
                    variant, GunsmithPartQuality.COMMON);
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        helper.assertTrue(threw, variant + " must reject " + platform + "/" + part);
    }
}
