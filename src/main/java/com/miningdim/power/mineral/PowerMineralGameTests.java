package com.miningdim.power.mineral;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import com.miningdim.core.Difficulty;
import com.miningdim.core.MiningConstants;
import com.miningdim.job.miner.OreScanService;
import com.miningdim.ore.OreType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 七矿注册、探矿识别与当前 datapack worldgen 接线回归。 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class PowerMineralGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "power_minerals";

    private PowerMineralGameTests() {
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void mineralRegistriesAreLoaded(GameTestHelper helper) {
        for (PowerMineral mineral : PowerMineral.values()) {
            assertRegisteredBlock(helper, mineral.oreKey());
            assertRegisteredBlock(helper, mineral.deepslateOreKey());
            assertBlockItem(helper, mineral.oreKey());
            assertBlockItem(helper, mineral.deepslateOreKey());
            assertRegisteredItem(helper, id(mineral.rawMaterialId()));
            if (mineral.hasIngot()) {
                assertRegisteredItem(helper, id(mineral.ingotId()));
            }
        }
        helper.assertFalse(BuiltInRegistries.BLOCK.containsKey(id("lead_ore")),
                "能源七矿不得额外注册铅矿");
        helper.assertFalse(BuiltInRegistries.ITEM.containsKey(id("raw_lead")),
                "能源七矿不得额外注册铅原矿");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void oreTypeResolvesBothRegisteredVariants(GameTestHelper helper) {
        for (PowerMineral mineral : PowerMineral.values()) {
            OreType oreType = OreType.valueOf(mineral.name());
            Block stone = BuiltInRegistries.BLOCK.get(mineral.oreKey());
            Block deepslate = BuiltInRegistries.BLOCK.get(mineral.deepslateOreKey());
            helper.assertTrue(OreType.fromBlock(stone) == oreType,
                    mineral.name() + " 石质矿必须反查到对应 OreType");
            helper.assertTrue(OreType.fromBlock(deepslate) == oreType,
                    mineral.name() + " 深层矿必须反查到对应 OreType");
            helper.assertTrue(oreType.blockStateAt(1).is(stone),
                    mineral.name() + " 在 Y>=0 必须选择石质矿");
            helper.assertTrue(oreType.blockStateAt(-1).is(deepslate),
                    mineral.name() + " 在 Y<0 必须选择深层矿");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void difficultyBiomesWireExpectedMineralFeatures(GameTestHelper helper) {
        assertMineralFeatures(helper, Difficulty.EASY, List.of("ore_bauxite_easy"));
        assertMineralFeatures(helper, Difficulty.MEDIUM, List.of(
                "ore_bauxite_medium", "ore_borax_medium", "ore_tin_medium", "ore_silver_medium"));
        assertMineralFeatures(helper, Difficulty.HARD, List.of(
                "ore_bauxite_hard", "ore_borax_hard", "ore_tin_hard", "ore_silver_hard",
                "ore_nickel_hard", "ore_chromium_hard", "ore_tungsten_hard"));
        assertFeatureDefaults(helper, "ore_bauxite_easy", 9, 10);
        assertFeatureDefaults(helper, "ore_bauxite_medium", 9, 6);
        assertFeatureDefaults(helper, "ore_bauxite_hard", 9, 3);
        assertFeatureDefaults(helper, "ore_borax_medium", 5, 4);
        assertFeatureDefaults(helper, "ore_borax_hard", 5, 3);
        assertFeatureDefaults(helper, "ore_tin_medium", 8, 5);
        assertFeatureDefaults(helper, "ore_tin_hard", 8, 3);
        assertFeatureDefaults(helper, "ore_silver_medium", 5, 3);
        assertFeatureDefaults(helper, "ore_silver_hard", 5, 5);
        assertFeatureDefaults(helper, "ore_nickel_hard", 6, 4);
        assertFeatureDefaults(helper, "ore_chromium_hard", 4, 3);
        assertFeatureDefaults(helper, "ore_tungsten_hard", 3, 2);
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void oreScanRecognizesEveryNewMineral(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos center = helper.absolutePos(new BlockPos(0, 2, 0));
        BlockPos stonePos = center.offset(1, 0, 0);
        BlockPos deepslatePos = center.offset(-1, 0, 0);
        for (PowerMineral mineral : PowerMineral.values()) {
            OreType oreType = OreType.valueOf(mineral.name());
            level.setBlock(stonePos, BuiltInRegistries.BLOCK.get(mineral.oreKey()).defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(deepslatePos,
                    BuiltInRegistries.BLOCK.get(mineral.deepslateOreKey()).defaultBlockState(), Block.UPDATE_ALL);
            OreScanService.ScanHit hit = OreScanService.scanWorldDetailed(
                    level, center, 2, EnumSet.of(oreType));
            helper.assertTrue(hit.ore() == oreType,
                    "探矿必须识别 " + mineral.name() + "，实得 " + hit.ore());
            helper.assertTrue(hit.positions().size() == 2
                            && hit.positions().contains(stonePos)
                            && hit.positions().contains(deepslatePos),
                    mineral.name() + " 的石质与深层矿都必须进入探矿结果，实得 " + hit.positions());
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void oreScanLocksUnlockSetsAndPriority(GameTestHelper helper) {
        List<OreType> priority = List.of(
                OreType.IRON, OreType.COAL, OreType.BAUXITE,
                OreType.DIAMOND, OreType.BORAX, OreType.TIN, OreType.SILVER,
                OreType.GOLD, OreType.ANCIENT_DEBRIS, OreType.NICKEL, OreType.CHROMIUM, OreType.TUNGSTEN);
        helper.assertTrue(OreScanService.allowedOres(3).equals(EnumSet.copyOf(priority.subList(0, 3))),
                "L3 可探集合必须精确为铁、煤和铝土");
        helper.assertTrue(OreScanService.allowedOres(6).equals(EnumSet.copyOf(priority.subList(0, 7))),
                "L6 可探集合必须精确追加钻石、硼砂、锡和银");
        helper.assertTrue(OreScanService.allowedOres(8).equals(EnumSet.copyOf(priority)),
                "L8 可探集合必须精确追加金、远古残骸、镍、铬和钨");

        ServerLevel level = helper.getLevel();
        BlockPos center = helper.absolutePos(new BlockPos(0, 2, 0));
        List<BlockPos> positions = List.of(
                center.offset(1, 0, 0), center.offset(-1, 0, 0), center.offset(0, 1, 0),
                center.offset(0, -1, 0), center.offset(0, 0, 1), center.offset(0, 0, -1),
                center.offset(1, 1, 0), center.offset(-1, 1, 0), center.offset(1, -1, 0),
                center.offset(-1, -1, 0), center.offset(0, 1, 1), center.offset(0, -1, -1));
        for (int i = 0; i < priority.size(); i++) {
            OreType ore = priority.get(i);
            level.setBlock(positions.get(i), ore.blockStateAt(center.getY()), Block.UPDATE_ALL);
        }
        for (int i = 0; i < priority.size(); i++) {
            Set<OreType> suffix = EnumSet.copyOf(priority.subList(i, priority.size()));
            OreScanService.ScanHit hit = OreScanService.scanWorldDetailed(level, center, 2, suffix);
            helper.assertTrue(hit.ore() == priority.get(i),
                    "探矿优先序第 " + i + " 位必须为 " + priority.get(i) + "，实得 " + hit.ore());
        }
        helper.succeed();
    }

    private static void assertMineralFeatures(GameTestHelper helper, Difficulty difficulty, List<String> expectedPaths) {
        List<ResourceLocation> oreStep = featureIdsAt(helper.getLevel(), difficulty);
        Set<ResourceLocation> expected = new LinkedHashSet<>();
        for (String path : expectedPaths) {
            expected.add(id(path));
        }
        Set<ResourceLocation> actual = new LinkedHashSet<>();
        for (ResourceLocation feature : oreStep) {
            if (MiningConstants.MODID.equals(feature.getNamespace())
                    && feature.getPath().matches("ore_(bauxite|borax|tin|silver|nickel|chromium|tungsten)_(easy|medium|hard)")) {
                actual.add(feature);
            }
        }
        helper.assertTrue(actual.equals(expected),
                difficulty.configName() + " 能源矿接线必须为 " + expected + "，实得 " + actual);

        int lastMisc = lastIndexWithPrefix(oreStep, "misc_");
        int firstTrap = firstIndexWithPrefix(oreStep, "trap_");
        for (ResourceLocation feature : expected) {
            int index = oreStep.indexOf(feature);
            helper.assertTrue(index > lastMisc && index < firstTrap,
                    feature + " 必须位于共有杂项之后、陷阱之前，实得索引 " + index);
        }
    }

    private static List<ResourceLocation> featureIdsAt(ServerLevel level, Difficulty difficulty) {
        Biome biome = level.registryAccess().registryOrThrow(Registries.BIOME)
                .getOrThrow(difficulty.biomeKey());
        List<HolderSet<PlacedFeature>> steps = biome.getGenerationSettings().features();
        if (steps.size() <= 6) {
            throw new IllegalStateException("biome " + difficulty.configName()
                    + " has only " + steps.size() + " generation steps");
        }
        Registry<PlacedFeature> placed = level.registryAccess().registryOrThrow(Registries.PLACED_FEATURE);
        List<ResourceLocation> ids = new ArrayList<>();
        for (Holder<PlacedFeature> holder : steps.get(6)) {
            ResourceLocation key = holder.unwrapKey()
                    .orElseThrow(() -> new IllegalStateException("unkeyed placed feature in " + difficulty.configName()))
                    .location();
            if (!placed.containsKey(key)) {
                throw new IllegalStateException("missing placed feature registration: " + key);
            }
            ids.add(key);
        }
        return ids;
    }

    private static void assertFeatureDefaults(GameTestHelper helper, String placedPath,
                                              int expectedVeinSize, int expectedAttempts) {
        Registry<PlacedFeature> placedRegistry = helper.getLevel().registryAccess()
                .registryOrThrow(Registries.PLACED_FEATURE);
        PlacedFeature placed = placedRegistry.get(id(placedPath));
        if (placed == null) {
            throw new IllegalStateException("缺少放置特征 " + placedPath);
        }
        ConfiguredFeature<?, ?> configured = placed.feature().value();
        helper.assertTrue(configured.config() instanceof OreConfiguration,
                placedPath + " 必须引用原版矿石配置");
        OreConfiguration ore = (OreConfiguration) configured.config();
        helper.assertTrue(ore.size == expectedVeinSize,
                placedPath + " 矿脉尺寸必须为 " + expectedVeinSize + "，实得 " + ore.size);
        helper.assertFalse(placed.placement().isEmpty(), placedPath + " 缺少放置修饰器");
        PlacementModifier count = placed.placement().get(0);
        JsonElement encoded = PlacementModifier.CODEC.encodeStart(JsonOps.INSTANCE, count)
                .result().orElseThrow(() -> new IllegalStateException("无法编码放置修饰器 " + placedPath));
        JsonObject countJson = encoded.getAsJsonObject();
        helper.assertTrue("minecraft:count".equals(countJson.get("type").getAsString()),
                placedPath + " 首个放置修饰器必须为 minecraft:count");
        helper.assertTrue(countJson.get("count").getAsInt() == expectedAttempts,
                placedPath + " 每区块尝试次数必须为 " + expectedAttempts
                        + "，实得 " + countJson.get("count").getAsInt());
    }

    private static int lastIndexWithPrefix(List<ResourceLocation> ids, String prefix) {
        int result = -1;
        for (int i = 0; i < ids.size(); i++) {
            ResourceLocation id = ids.get(i);
            if (MiningConstants.MODID.equals(id.getNamespace()) && id.getPath().startsWith(prefix)) {
                result = i;
            }
        }
        return result;
    }

    private static int firstIndexWithPrefix(List<ResourceLocation> ids, String prefix) {
        for (int i = 0; i < ids.size(); i++) {
            ResourceLocation id = ids.get(i);
            if (MiningConstants.MODID.equals(id.getNamespace()) && id.getPath().startsWith(prefix)) {
                return i;
            }
        }
        throw new IllegalStateException("missing feature prefix " + prefix);
    }

    private static void assertRegisteredBlock(GameTestHelper helper, ResourceLocation id) {
        helper.assertTrue(BuiltInRegistries.BLOCK.containsKey(id), "缺少矿石方块注册 " + id);
    }

    private static void assertRegisteredItem(GameTestHelper helper, ResourceLocation id) {
        helper.assertTrue(BuiltInRegistries.ITEM.containsKey(id), "缺少矿物物品注册 " + id);
    }

    private static void assertBlockItem(GameTestHelper helper, ResourceLocation id) {
        helper.assertTrue(BuiltInRegistries.ITEM.get(id) instanceof BlockItem blockItem
                        && blockItem.getBlock() == BuiltInRegistries.BLOCK.get(id),
                "矿石物品必须绑定同 ID 方块 " + id);
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation(MiningConstants.MODID, path);
    }
}
