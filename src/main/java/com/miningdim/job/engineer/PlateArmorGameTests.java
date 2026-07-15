package com.miningdim.job.engineer;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.engineer.armor.PlateArmorConstructionMaterial;
import com.miningdim.job.engineer.armor.PlateArmorDamageClassifier;
import com.miningdim.job.engineer.armor.PlateArmorDamageHandler;
import com.miningdim.job.engineer.armor.PlateArmorEquipmentHandler;
import com.miningdim.job.engineer.armor.PlateArmorMath;
import com.miningdim.job.engineer.armor.PlateArmorStats;
import com.miningdim.job.engineer.armor.PlateArmorTier;
import com.miningdim.job.engineer.armor.PlateArmorVariant;
import com.miningdim.job.engineer.armor.PlateArmorWeight;
import com.miningdim.job.engineer.armor.integration.PlateArmorTaczWearLedger;
import com.miningdim.job.engineer.armor.item.PlateArmorItem;
import com.miningdim.job.engineer.effect.NanoShieldHandler;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 插板护甲首版的公式、54 件静态映射、装备属性、耐久与旧纳米系统隔离回归。 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class PlateArmorGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "plate_armor";
    private static final double EPS = 1.0E-6D;

    @BeforeBatch(batch = BATCH)
    public static void beforePlateArmorBatch(ServerLevel level) {
        EngineerConfig.ensureLoadedForTest();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void defaultMatricesMatchApprovedPrototype(GameTestHelper helper) {
        double[] expectedR = {
                .45, .50, .55, .60, .65, .70, .75, .80, .85,
                .85, .88, .90, .90, .92, .94, .94, .96, .98};
        double[] expectedQ = {
                .00, .00, .00, .02, .05, .08, .08, .10, .15,
                .15, .20, .25, .25, .35, .45, .45, .50, .55};
        double[] expectedG = {
                .35, .40, .45, .45, .50, .55, .60, .68, .70,
                .70, .76, .78, .78, .84, .86, .86, .88, .90};
        double[] expectedT = {
                16, 20, 24, 24, 32, 38, 38, 48, 58,
                58, 72, 84, 84, 96, 112, 112, 128, 154};

        for (PlateArmorTier tier : PlateArmorTier.values()) {
            for (PlateArmorWeight weight : PlateArmorWeight.values()) {
                int index = tier.configIndex(weight);
                helper.assertTrue(close(EngineerConfig.PLATE_ARMOR.ballisticProtection(tier, weight), expectedR[index]),
                        "R mismatch at " + tier + "/" + weight);
                helper.assertTrue(close(EngineerConfig.PLATE_ARMOR.armorPiercingBuffer(tier, weight), expectedQ[index]),
                        "Q mismatch at " + tier + "/" + weight);
                helper.assertTrue(close(EngineerConfig.PLATE_ARMOR.generalProtection(tier, weight), expectedG[index]),
                        "G mismatch at " + tier + "/" + weight);
                helper.assertTrue(close(EngineerConfig.PLATE_ARMOR.pressureCapacity(tier, weight), expectedT[index]),
                        "T mismatch at " + tier + "/" + weight);
                helper.assertTrue(expectedQ[index] < expectedR[index], "Q must remain below R at " + tier + "/" + weight);
            }
        }
        helper.assertTrue(close(EngineerConfig.PLATE_ARMOR.movementModifier(PlateArmorWeight.LIGHT), .03),
                "light movement +3%");
        helper.assertTrue(close(EngineerConfig.PLATE_ARMOR.movementModifier(PlateArmorWeight.MEDIUM), 0),
                "medium movement 0%");
        helper.assertTrue(close(EngineerConfig.PLATE_ARMOR.movementModifier(PlateArmorWeight.HEAVY), -.04),
                "heavy movement -4%");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void mediumBallisticTwentyDamageSamples(GameTestHelper helper) {
        double[] expected = {12.0, 9.4, 6.8, 5.12, 3.88, 2.64};
        for (PlateArmorTier tier : PlateArmorTier.values()) {
            double r = EngineerConfig.PLATE_ARMOR.ballisticProtection(tier, PlateArmorWeight.MEDIUM);
            double q = EngineerConfig.PLATE_ARMOR.armorPiercingBuffer(tier, PlateArmorWeight.MEDIUM);
            double actual = PlateArmorMath.reduceSegment(16.0D, r)
                    + PlateArmorMath.reduceSegment(4.0D, q);
            helper.assertTrue(close(actual, expected[tier.ordinal()]),
                    "20 damage / 20% AP mismatch at medium " + tier + ": " + actual);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void mediumGeneralDamageSamplesAndCapacityBoundary(GameTestHelper helper) {
        double[] expected20 = {12.0, 10.0, 6.4, 4.8, 3.2, 2.4};
        double[] expected72 = {64.0, 56.0, 39.36, 17.28, 11.52, 8.64};
        for (PlateArmorTier tier : PlateArmorTier.values()) {
            double g = EngineerConfig.PLATE_ARMOR.generalProtection(tier, PlateArmorWeight.MEDIUM);
            double t = EngineerConfig.PLATE_ARMOR.pressureCapacity(tier, PlateArmorWeight.MEDIUM);
            helper.assertTrue(close(PlateArmorMath.reduceWithPressureCapacity(20, g, t), expected20[tier.ordinal()]),
                    "G/T 20 mismatch at medium " + tier);
            helper.assertTrue(close(PlateArmorMath.reduceWithPressureCapacity(72, g, t), expected72[tier.ordinal()]),
                    "G/T 72 mismatch at medium " + tier);
        }

        double g = .76D;
        double t = 72.0D;
        helper.assertTrue(close(PlateArmorMath.reduceWithPressureCapacity(t - .01, g, t), (t - .01) * (1 - g)),
                "T-0.01 fully protected");
        helper.assertTrue(close(PlateArmorMath.reduceWithPressureCapacity(t, g, t), t * (1 - g)),
                "T exactly fully protected");
        helper.assertTrue(close(PlateArmorMath.reduceWithPressureCapacity(t + .01, g, t), t + .01 - t * g),
                "T+0.01 only overload passes");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void taczTypesAreExactAndUnknownFutureTypesExcluded(GameTestHelper helper) {
        helper.assertTrue(PlateArmorDamageClassifier.classifyTacz("tacz", "bullet")
                        == PlateArmorDamageClassifier.Kind.BALLISTIC_NORMAL,
                "tacz:bullet uses R");
        helper.assertTrue(PlateArmorDamageClassifier.classifyTacz("tacz", "bullet_void")
                        == PlateArmorDamageClassifier.Kind.BALLISTIC_NORMAL,
                "tacz:bullet_void uses R");
        helper.assertTrue(PlateArmorDamageClassifier.classifyTacz("tacz", "bullet_ignore_armor")
                        == PlateArmorDamageClassifier.Kind.BALLISTIC_ARMOR_PIERCING,
                "tacz:bullet_ignore_armor uses Q before bypass exclusion");
        helper.assertTrue(PlateArmorDamageClassifier.classifyTacz("tacz", "bullet_void_ignore_armor")
                        == PlateArmorDamageClassifier.Kind.BALLISTIC_ARMOR_PIERCING,
                "tacz:bullet_void_ignore_armor uses Q");
        helper.assertTrue(PlateArmorDamageClassifier.classifyTacz("tacz", "bullet_future")
                        == PlateArmorDamageClassifier.Kind.EXCLUDED,
                "unknown future tacz bullet type is not guessed");
        helper.assertTrue(PlateArmorDamageClassifier.classifyTacz("minecraft", "bullet")
                        == PlateArmorDamageClassifier.Kind.EXCLUDED,
                "foreign namespace excluded");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void fiftyFourVariantsHaveStableUniqueMappings(GameTestHelper helper) {
        helper.assertTrue(PlateArmorVariant.values().length == 54, "exactly 54 armor variants");
        Set<String> ids = new HashSet<>();
        Map<PlateArmorTier, int[]> counts = new EnumMap<>(PlateArmorTier.class);
        for (PlateArmorTier tier : PlateArmorTier.values()) {
            counts.put(tier, new int[PlateArmorWeight.values().length]);
        }
        for (PlateArmorVariant variant : PlateArmorVariant.values()) {
            helper.assertTrue(ids.add(variant.itemId()), "duplicate armor item id: " + variant.itemId());
            counts.get(variant.tier())[variant.weight().ordinal()]++;
        }

        int[][] expected = {{2, 0, 0}, {1, 0, 0}, {2, 6, 0}, {6, 9, 3}, {5, 6, 9}, {1, 2, 2}};
        for (PlateArmorTier tier : PlateArmorTier.values()) {
            for (PlateArmorWeight weight : PlateArmorWeight.values()) {
                helper.assertTrue(counts.get(tier)[weight.ordinal()] == expected[tier.ordinal()][weight.ordinal()],
                        "variant count mismatch at " + tier + "/" + weight);
            }
        }
        helper.assertTrue(PlateArmorVariant.HEXGRID.material() == PlateArmorConstructionMaterial.UHMWPE,
                "Hexgrid main plate material is UHMWPE");
        helper.assertTrue(PlateArmorVariant.B6B5_16.material() == PlateArmorConstructionMaterial.COMBINED,
                "6B5-16 merged into combined material");
        helper.assertTrue(PlateArmorVariant.B6B5_15_FLORA.material() == PlateArmorConstructionMaterial.COMBINED,
                "6B5-15 merged into combined material");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void materialProfilesAndMappingsMatchApprovedDesign(GameTestHelper helper) {
        helper.assertTrue(PlateArmorConstructionMaterial.values().length == 7, "exactly seven merged materials");

        Map<PlateArmorConstructionMaterial, Integer> expectedDurability = new EnumMap<>(
                PlateArmorConstructionMaterial.class);
        expectedDurability.put(PlateArmorConstructionMaterial.UHMWPE, 850);
        expectedDurability.put(PlateArmorConstructionMaterial.ARAMID, 900);
        expectedDurability.put(PlateArmorConstructionMaterial.ARMOR_STEEL, 760);
        expectedDurability.put(PlateArmorConstructionMaterial.COMBINED, 610);
        expectedDurability.put(PlateArmorConstructionMaterial.ALUMINUM, 580);
        expectedDurability.put(PlateArmorConstructionMaterial.TITANIUM, 700);
        expectedDurability.put(PlateArmorConstructionMaterial.CERAMIC, 420);

        Map<PlateArmorConstructionMaterial, double[]> expectedModifiers = new EnumMap<>(
                PlateArmorConstructionMaterial.class);
        expectedModifiers.put(PlateArmorConstructionMaterial.UHMWPE,
                new double[]{.88D, 1.08D, .94D, 1.08D, 0.0D});
        expectedModifiers.put(PlateArmorConstructionMaterial.ARAMID,
                new double[]{.94D, 1.18D, 1.00D, .90D, 0.0D});
        expectedModifiers.put(PlateArmorConstructionMaterial.ARMOR_STEEL,
                new double[]{.88D, .88D, .88D, 1.15D, .03D});
        expectedModifiers.put(PlateArmorConstructionMaterial.COMBINED,
                new double[]{.94D, .94D, .94D, 1.08D, .01D});
        expectedModifiers.put(PlateArmorConstructionMaterial.ALUMINUM,
                new double[]{.98D, 1.00D, .98D, 1.00D, .015D});
        expectedModifiers.put(PlateArmorConstructionMaterial.TITANIUM,
                new double[]{.90D, .98D, .94D, 1.08D, .005D});
        expectedModifiers.put(PlateArmorConstructionMaterial.CERAMIC,
                new double[]{.94D, .88D, 1.00D, 1.15D, .005D});

        for (PlateArmorConstructionMaterial material : PlateArmorConstructionMaterial.values()) {
            helper.assertTrue(EngineerConfig.PLATE_ARMOR.maxDurability(material) == expectedDurability.get(material),
                    "durability mismatch for " + material);
            double[] expected = expectedModifiers.get(material);
            helper.assertTrue(close(EngineerConfig.PLATE_ARMOR.ballisticLeakMultiplier(material), expected[0]),
                    "ballistic leak mismatch for " + material);
            helper.assertTrue(close(EngineerConfig.PLATE_ARMOR.armorPiercingLeakMultiplier(material), expected[1]),
                    "armor-piercing leak mismatch for " + material);
            helper.assertTrue(close(EngineerConfig.PLATE_ARMOR.generalLeakMultiplier(material), expected[2]),
                    "general leak mismatch for " + material);
            helper.assertTrue(close(EngineerConfig.PLATE_ARMOR.pressureCapacityMultiplier(material), expected[3]),
                    "pressure multiplier mismatch for " + material);
            helper.assertTrue(close(EngineerConfig.PLATE_ARMOR.movementPenalty(material), expected[4]),
                    "movement penalty mismatch for " + material);
        }

        Map<PlateArmorConstructionMaterial, Set<String>> expectedIds = new EnumMap<>(PlateArmorConstructionMaterial.class);
        expectedIds.put(PlateArmorConstructionMaterial.UHMWPE, Set.of(
                "jaypc_olive", "jaypc_black", "mbss", "tv115", "mmac_ranger_green",
                "trooper_tfo_multicam", "banshee_atacs_au", "tactec_ranger_green", "cpc_mod1_atacs_fg",
                "fcpc_v5", "hexatac_hpc_black_multicam", "hexgrid", "stich_defense_mod2"));
        expectedIds.put(PlateArmorConstructionMaterial.ARAMID, Set.of("paca"));
        expectedIds.put(PlateArmorConstructionMaterial.ARMOR_STEEL, Set.of(
                "6b23_1_digital_flora", "kora_kulon", "kora_kulon_digital", "6b13_flora", "ana_m1_olive",
                "stich_profi_v2_black", "tv110_coyote", "6b23_2_mountain_flora", "korund_vm_black", "slick"));
        expectedIds.put(PlateArmorConstructionMaterial.COMBINED, Set.of(
                "6b5_16", "kirasa_n_green", "a18_skanda_multicam", "avs_ranger_green", "avs_multicam",
                "thor_concealable", "6b5_15_flora", "tt_mkiii_coyote", "osprey_mk4a_protection",
                "thor_integrated"));
        expectedIds.put(PlateArmorConstructionMaterial.ALUMINUM, Set.of(
                "mf_untar", "strandhogg_ranger_green", "strandhogg_black_multicam", "osprey_mk4a_assault"));
        expectedIds.put(PlateArmorConstructionMaterial.TITANIUM, Set.of(
                "rbav_af_ranger_green", "6b3tm_01m_khaki", "iotv_gen4_high_mobility",
                "iotv_gen4_full_protection", "iotv_gen4_assault"));
        expectedIds.put(PlateArmorConstructionMaterial.CERAMIC, Set.of(
                "gladiator_s_light_multicam", "6b45_general", "6b45_medic", "gzhel_k", "gladiator_s_gray",
                "gladiator_s_viking", "defender_2_spot_camo", "defender_2", "gladiator_s_deathless",
                "redut_m", "6b43_zabralo_sh"));

        Map<PlateArmorConstructionMaterial, Set<String>> actualIds = new EnumMap<>(PlateArmorConstructionMaterial.class);
        for (PlateArmorConstructionMaterial material : PlateArmorConstructionMaterial.values()) {
            actualIds.put(material, new HashSet<>());
        }
        for (PlateArmorVariant variant : PlateArmorVariant.values()) {
            actualIds.get(variant.material()).add(variant.id());
        }
        for (PlateArmorConstructionMaterial material : PlateArmorConstructionMaterial.values()) {
            helper.assertTrue(actualIds.get(material).equals(expectedIds.get(material)),
                    "Tarkov material mapping mismatch for " + material);
        }

        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void materialProfilesResolveIntoFinalPlayerStats(GameTestHelper helper) {
        PlateArmorStats uhmwpe = PlateArmorStats.resolve(PlateArmorVariant.TACTEC_RANGER_GREEN);
        helper.assertTrue(close(uhmwpe.ballisticProtection(), .912D), "V light UHMWPE R is 91.2%");
        helper.assertTrue(close(uhmwpe.armorPiercingBuffer(), .19D), "V light UHMWPE Q is 19%");
        helper.assertTrue(close(uhmwpe.generalProtection(), .7932D), "V light UHMWPE G is 79.32%");
        helper.assertTrue(close(uhmwpe.pressureCapacity(), 90.72D), "V light UHMWPE T is 90.72");
        helper.assertTrue(close(uhmwpe.movementModifier(), .03D), "V light UHMWPE movement is +3%");

        PlateArmorStats ceramic = PlateArmorStats.resolve(PlateArmorVariant.GLADIATOR_S_LIGHT_MULTICAM);
        helper.assertTrue(close(ceramic.ballisticProtection(), .906D), "V light ceramic R is 90.6%");
        helper.assertTrue(close(ceramic.armorPiercingBuffer(), .34D), "V light ceramic Q is 34%");
        helper.assertTrue(close(ceramic.generalProtection(), .78D), "V light ceramic G is 78%");
        helper.assertTrue(close(ceramic.pressureCapacity(), 96.6D), "V light ceramic T is 96.6");
        helper.assertTrue(close(ceramic.movementModifier(), .025D), "V light ceramic movement is +2.5%");

        PlateArmorStats aramid = PlateArmorStats.resolve(PlateArmorVariant.PACA);
        helper.assertTrue(close(aramid.armorPiercingBuffer(), 0.0D),
                "a zero base Q remains zero even when aramid leak exceeds one");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void defaultMaterialBalanceNeverReversesOverallTierOrder(GameTestHelper helper) {
        double[] expectedMin = {.37662282D, .42866400D, .52319380D, .58632966D, .70630993D, .78109033D};
        double[] expectedMax = {.37662282D, .42866400D, .57443821D, .68064056D, .75710956D, .81098156D};
        double previousMaximum = Double.NEGATIVE_INFINITY;

        for (PlateArmorTier tier : PlateArmorTier.values()) {
            double minimum = Double.POSITIVE_INFINITY;
            double maximum = Double.NEGATIVE_INFINITY;
            int count = 0;
            for (PlateArmorVariant variant : PlateArmorVariant.values()) {
                if (variant.tier() != tier) {
                    continue;
                }
                PlateArmorStats stats = PlateArmorStats.resolve(variant);
                helper.assertTrue(stats.ballisticProtection() >= 0.0D && stats.ballisticProtection() < 1.0D,
                        variant + " final R stays a valid rate");
                helper.assertTrue(stats.armorPiercingBuffer() >= 0.0D && stats.armorPiercingBuffer() < 1.0D,
                        variant + " final Q stays a valid rate");
                helper.assertTrue(stats.generalProtection() >= 0.0D && stats.generalProtection() < 1.0D,
                        variant + " final G stays a valid rate");
                helper.assertTrue(stats.pressureCapacity() >= 0.0D, variant + " final T is non-negative");
                double score = overallBalanceScore(variant, stats);
                minimum = Math.min(minimum, score);
                maximum = Math.max(maximum, score);
                count++;
            }
            helper.assertTrue(count > 0, "each tier has at least one implemented armor");
            helper.assertTrue(close(minimum, expectedMin[tier.ordinal()]), "minimum score mismatch at " + tier);
            helper.assertTrue(close(maximum, expectedMax[tier.ordinal()]), "maximum score mismatch at " + tier);
            helper.assertTrue(minimum > previousMaximum, tier + " weakest armor stays above the prior tier maximum");
            previousMaximum = maximum;
        }

        for (PlateArmorVariant lowerVariant : PlateArmorVariant.values()) {
            PlateArmorStats lowerStats = PlateArmorStats.resolve(lowerVariant);
            int lowerDurability = EngineerConfig.PLATE_ARMOR.maxDurability(lowerVariant.material());
            for (PlateArmorVariant higherVariant : PlateArmorVariant.values()) {
                if (lowerVariant.tier().ordinal() >= higherVariant.tier().ordinal()) {
                    continue;
                }
                PlateArmorStats higherStats = PlateArmorStats.resolve(higherVariant);
                int higherDurability = EngineerConfig.PLATE_ARMOR.maxDurability(higherVariant.material());
                boolean lowerDominates = lowerStats.ballisticProtection() >= higherStats.ballisticProtection()
                        && lowerStats.armorPiercingBuffer() >= higherStats.armorPiercingBuffer()
                        && lowerStats.generalProtection() >= higherStats.generalProtection()
                        && lowerStats.pressureCapacity() >= higherStats.pressureCapacity()
                        && lowerDurability >= higherDurability
                        && lowerStats.movementModifier() >= higherStats.movementModifier();
                helper.assertTrue(!lowerDominates,
                        lowerVariant + " must not dominate higher-tier " + higherVariant + " in all six dimensions");
            }
        }

        PlateArmorVariant lowerVariant = PlateArmorVariant.B6B23_2_MOUNTAIN_FLORA;
        PlateArmorVariant higherVariant = PlateArmorVariant.TACTEC_RANGER_GREEN;
        PlateArmorStats lower = PlateArmorStats.resolve(lowerVariant);
        PlateArmorStats higher = PlateArmorStats.resolve(higherVariant);
        helper.assertTrue(lower.armorPiercingBuffer() > higher.armorPiercingBuffer(),
                "an individual lower-tier attribute may cross the next tier");
        helper.assertTrue(overallBalanceScore(lowerVariant, lower) < overallBalanceScore(higherVariant, higher),
                "the same lower-tier armor remains weaker overall");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void registeredItemsKeepVariantAndDynamicDurability(GameTestHelper helper) {
        for (PlateArmorVariant variant : PlateArmorVariant.values()) {
            helper.assertTrue(ModEngineerItems.plateArmor(variant).get() instanceof PlateArmorItem,
                    variant.itemId() + " registered as PlateArmorItem");
            PlateArmorItem item = (PlateArmorItem) ModEngineerItems.plateArmor(variant).get();
            helper.assertTrue(item.variant() == variant, variant.itemId() + " keeps static variant identity");
            ItemStack stack = new ItemStack(item);
            helper.assertTrue(stack.isDamageableItem(), variant.itemId() + " is damageable");
            helper.assertTrue(stack.getMaxDamage() == EngineerConfig.PLATE_ARMOR.maxDurability(variant.material()),
                    variant.itemId() + " max durability comes only from material");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void exhaustedArmorCannotProtectAfterDurabilityLimitDrops(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ItemStack armor = new ItemStack(ModEngineerItems.plateArmor(PlateArmorVariant.HEXGRID).get());
        armor.setDamageValue(armor.getMaxDamage());
        player.setItemSlot(EquipmentSlot.CHEST, armor);

        LivingHurtEvent event = new LivingHurtEvent(player,
                helper.getLevel().damageSources().playerAttack(player), 20.0F);
        new PlateArmorDamageHandler().onLivingHurt(event);
        helper.assertTrue(close(event.getAmount(), 20.0D),
                "armor at or beyond the current durability limit provides no protection");

        PlateArmorEquipmentHandler.synchronize(player);
        helper.assertTrue(player.getItemBySlot(EquipmentSlot.CHEST).isEmpty(),
                "equipment synchronization removes armor exhausted by a lower reloaded limit");
        helper.assertTrue(player.getAttribute(Attributes.ARMOR)
                        .getModifier(PlateArmorEquipmentHandler.ARMOR_REPLACEMENT_ID) == null,
                "exhausted armor leaves no vanilla replacement modifier");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void equipmentReplacesVanillaArmorAndDoesNotLeaveModifiers(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
        player.setItemSlot(EquipmentSlot.CHEST,
                new ItemStack(ModEngineerItems.plateArmor(PlateArmorVariant.JAYPC_OLIVE).get()));
        PlateArmorEquipmentHandler.synchronize(player);

        helper.assertTrue(close(player.getAttributeValue(Attributes.ARMOR), 0.0D),
                "active plate replaces diamond helmet armor instead of stacking");
        AttributeModifier movement = player.getAttribute(Attributes.MOVEMENT_SPEED)
                .getModifier(PlateArmorEquipmentHandler.MOVEMENT_ID);
        helper.assertTrue(movement != null && close(movement.getAmount(), .03D)
                        && movement.getOperation() == AttributeModifier.Operation.MULTIPLY_TOTAL,
                "light plate has one +3% MULTIPLY_TOTAL movement modifier");

        player.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
        PlateArmorEquipmentHandler.synchronize(player);
        helper.assertTrue(player.getAttribute(Attributes.ARMOR)
                        .getModifier(PlateArmorEquipmentHandler.ARMOR_REPLACEMENT_ID) == null,
                "removing plate clears vanilla armor replacement modifier");
        helper.assertTrue(player.getAttribute(Attributes.ARMOR_TOUGHNESS)
                        .getModifier(PlateArmorEquipmentHandler.TOUGHNESS_REPLACEMENT_ID) == null,
                "removing plate clears toughness replacement modifier");
        helper.assertTrue(player.getAttribute(Attributes.MOVEMENT_SPEED)
                        .getModifier(PlateArmorEquipmentHandler.MOVEMENT_ID) == null,
                "removing plate clears movement modifier");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void liveGeneralHitUsesGTAndWearsOnce(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ItemStack armor = new ItemStack(ModEngineerItems.plateArmor(PlateArmorVariant.B6B23_1_DIGITAL_FLORA).get());
        player.setItemSlot(EquipmentSlot.CHEST, armor); // III medium steel: G=.7184, T=55.2

        LivingHurtEvent event = new LivingHurtEvent(player,
                helper.getLevel().damageSources().playerAttack(player), 20.0F);
        new PlateArmorDamageHandler().onLivingHurt(event);
        helper.assertTrue(close(event.getAmount(), 5.632D), "III medium steel G/T turns 20 into 5.632");
        helper.assertTrue(armor.getDamageValue() == 5, "20 incoming damage wears floor(20/4)=5 exactly once");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void taczWearLedgerSurvivesFatalEquipmentDropAndSettlesOnce(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ItemStack armor = new ItemStack(ModEngineerItems.plateArmor(PlateArmorVariant.HEXGRID).get());
        player.setItemSlot(EquipmentSlot.CHEST, armor);
        PlateArmorTaczWearLedger ledger = new PlateArmorTaczWearLedger();
        UUID bulletId = UUID.randomUUID();

        ledger.capture(bulletId, player);
        player.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY); // 模拟致死流程已先把同一栈移入掉落物。
        helper.assertTrue(ledger.settle(bulletId, player, 20.0D),
                "captured armor reference survives the fatal equipment drop order");
        helper.assertTrue(armor.getDamageValue() == 5,
                "TaCZ base amount 20 wears floor(20/4)=5 without an extra headshot multiplier");
        helper.assertTrue(!ledger.settle(bulletId, player, 20.0D) && ledger.pendingCount() == 0,
                "Post/Kill can settle one bullet only once");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void oldNanoShieldCannotStackFromAnotherSlotWhilePlateIsActive(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ItemStack helmet = new ItemStack(Items.DIAMOND_HELMET);
        NanoNbt.writeEffects(helmet, java.util.EnumSet.of(NanoEffect.SHIELD));
        NanoNbt.setShieldWindowTick(helmet, 20);
        player.setItemSlot(EquipmentSlot.HEAD, helmet);
        player.setItemSlot(EquipmentSlot.CHEST,
                new ItemStack(ModEngineerItems.plateArmor(PlateArmorVariant.TACTEC_RANGER_GREEN).get()));

        NanoShieldHandler handler = new NanoShieldHandler();
        LivingHurtEvent withPlate = new LivingHurtEvent(player,
                helper.getLevel().damageSources().playerAttack(player), 20.0F);
        handler.onLivingHurt(withPlate);
        helper.assertTrue(close(withPlate.getAmount(), 20.0D),
                "legacy nano immunity from another slot is disabled while plate armor is active");

        player.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
        LivingHurtEvent withoutPlate = new LivingHurtEvent(player,
                helper.getLevel().damageSources().playerAttack(player), 20.0F);
        handler.onLivingHurt(withoutPlate);
        helper.assertTrue(close(withoutPlate.getAmount(), 0.0D),
                "legacy nano shield behavior remains available after removing plate armor");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void nanoRepairCanRepairPlateButNeverAddsOldNanoEffects(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ItemStack armor = new ItemStack(ModEngineerItems.plateArmor(PlateArmorVariant.HEXGRID).get());
        armor.setDamageValue(100);
        ItemStack repairPlate = new ItemStack(ModEngineerItems.plate(NanoTier.HIGH).get());

        NanoRepair.Result result = NanoRepair.repair(armor, repairPlate, player,
                new com.miningdim.job.engineer.testutil.FixedDoubleRandom(0.0D));
        helper.assertTrue(result.success() && armor.getDamageValue() == 0,
                "existing nano repair plate repairs new plate armor");
        helper.assertTrue(NanoNbt.effects(armor).isEmpty(),
                "new plate armor never receives old shield/totem/regen effects");
        helper.succeed();
    }

    private static boolean close(double actual, double expected) {
        return Math.abs(actual - expected) < EPS;
    }

    /** 只用于默认值跨级回归；不是游戏内隐藏属性，也不参与实际伤害结算。 */
    private static double overallBalanceScore(PlateArmorVariant variant, PlateArmorStats stats) {
        double highPressureCoverage = stats.generalProtection()
                * Math.min(stats.pressureCapacity() / 100.0D, 1.0D);
        double protection = .35D * stats.ballisticProtection()
                + .25D * stats.armorPiercingBuffer()
                + .20D * stats.generalProtection()
                + .20D * highPressureCoverage;
        double durability = Math.min(
                EngineerConfig.PLATE_ARMOR.maxDurability(variant.material()) / 900.0D, 1.0D);
        double mobility = (stats.movementModifier() + .07D) / .10D;
        return .85D * protection + .08D * durability + .07D * mobility;
    }
}
