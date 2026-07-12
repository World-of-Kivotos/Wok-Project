package com.miningdim.worldgen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.miningdim.core.Difficulty;
import com.miningdim.core.MiningConstants;
import com.mojang.serialization.JsonOps;
import net.minecraft.Util;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * worldgen 翻修 1.0.2 契约 GameTest。断言具体业务结果 (删被测逻辑必挂, 禁弱校验):
 * MiningBiomeSource 变体划片确定性 (同输入同输出 / 已预计算的具体片落点 / 片内一致) + 池完整性
 * (每难度盒采样命中该池全部成员, 不越池) + region 外恒 wall + possibleBiomes 恰含 9 群系;
 * 矿表锚相对性守卫: 全部 miningdim placed_feature 的 height_range 只用 above_bottom/below_top
 * (absolute 在 192 盒里是倒挂配置, 防未来回归)。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class WorldgenGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "worldgen";

    private static ResourceKey<Biome> biomeKey(String path) {
        return ResourceKey.create(Registries.BIOME, new ResourceLocation(MiningConstants.MODID, path));
    }

    private static MiningBiomeSource newSource(GameTestHelper helper) {
        Registry<Biome> biomes = helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME);
        return new MiningBiomeSource(biomes.asLookup());
    }

    /** 按方块坐标取群系 (封装 quart 换算; y 不参与, 恒传 0)。 */
    private static Holder<Biome> biomeAt(MiningBiomeSource source, int blockX, int blockZ) {
        return source.getNoiseBiome(QuartPos.fromBlock(blockX), 0, QuartPos.fromBlock(blockZ), null);
    }

    // ============================================================
    // 变体划片: 确定性 + 具体片落点 (离线穷举预计算) + 片内一致 + region 外恒 wall
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void biomeSourceVariantSelectionIsDeterministic(GameTestHelper helper) {
        MiningBiomeSource source = newSource(helper);

        // 具体片落点: 由离线穷举 (mix 哈希对全部片坐标求值, patch=64) 预计算, 锁死哈希实现 ——
        // 任何改动哈希常量/片粒度/权重都会改变落点, 本断言即挂 (确定性回归守卫, 挂了须重新穷举核准)。
        helper.assertTrue(biomeAt(source, 0, 0).is(biomeKey("mining_easy_lush")),
                "easy patch(0,0) (block 0..63) precomputed as mining_easy_lush");
        helper.assertTrue(biomeAt(source, 64, 0).is(biomeKey("mining_easy")),
                "easy patch(1,0) (block 64..127) precomputed as mining_easy");
        helper.assertTrue(biomeAt(source, 288, 64).is(biomeKey("mining_medium_lush")),
                "medium patch(4,1) precomputed as mining_medium_lush");
        helper.assertTrue(biomeAt(source, 768, 0).is(biomeKey("mining_hard_deepdark")),
                "hard patch(12,0) (block 768..831) precomputed as mining_hard_deepdark");

        // 片内一致: 同一 64 格片内任意两点同群系 (块 (0,0) 与 (63,63) 同片)。
        helper.assertTrue(biomeAt(source, 0, 0) == biomeAt(source, 63, 63),
                "same 64-block patch -> same biome holder");
        // 同输入同输出: 两次调用返回同一 holder 实例。
        helper.assertTrue(biomeAt(source, 300, 100) == biomeAt(source, 300, 100),
                "repeated call returns identical holder");
        // 两个独立构造的 source 对同输入同输出 (无实例内部随机态)。
        MiningBiomeSource second = newSource(helper);
        helper.assertTrue(biomeAt(source, 700, 200).is(
                        biomeAt(second, 700, 200).unwrapKey().orElseThrow()),
                "two independently constructed sources agree");

        // region 外恒 wall: X 缓冲带 (easy 与 medium 之间 256..287) / Z 越界 / 网格外负象限。
        helper.assertTrue(biomeAt(source, 260, 10).is(MiningConstants.MINING_WALL_BIOME),
                "buffer gap column (x=260) -> mining_wall");
        helper.assertTrue(biomeAt(source, 0, 300).is(MiningConstants.MINING_WALL_BIOME),
                "z beyond region (z=300) -> mining_wall");
        helper.assertTrue(biomeAt(source, -100, -100).is(MiningConstants.MINING_WALL_BIOME),
                "outside grid (negative quadrant) -> mining_wall");
        helper.succeed();
    }

    // ============================================================
    // 池完整性: 每难度盒 16 格步长全采样, 命中该池全部成员且不越池; possibleBiomes 恰含 9 群系
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void biomeSourcePoolsCompleteAndPossibleBiomesExact(GameTestHelper helper) {
        MiningBiomeSource source = newSource(helper);

        Map<Difficulty, Set<ResourceLocation>> expectedPools = Map.of(
                Difficulty.EASY, Set.of(
                        new ResourceLocation(MiningConstants.MODID, "mining_easy"),
                        new ResourceLocation(MiningConstants.MODID, "mining_easy_lush")),
                Difficulty.MEDIUM, Set.of(
                        new ResourceLocation(MiningConstants.MODID, "mining_medium"),
                        new ResourceLocation(MiningConstants.MODID, "mining_medium_lush"),
                        new ResourceLocation(MiningConstants.MODID, "mining_medium_dripstone")),
                Difficulty.HARD, Set.of(
                        new ResourceLocation(MiningConstants.MODID, "mining_hard"),
                        new ResourceLocation(MiningConstants.MODID, "mining_hard_dripstone"),
                        new ResourceLocation(MiningConstants.MODID, "mining_hard_deepdark")));

        int originZ = MiningConstants.REGION_ORIGIN_Z
                + MiningConstants.FIXED_REGION_CELL_Z * MiningConstants.REGION_STRIDE_Z;
        for (Difficulty d : Difficulty.values()) {
            int originX = MiningConstants.REGION_ORIGIN_X + d.regionCellX() * MiningConstants.REGION_STRIDE_X;
            Set<ResourceLocation> expected = expectedPools.get(d);
            Set<ResourceLocation> seen = new HashSet<>();
            for (int bx = originX; bx < originX + MiningConstants.REGION_SIZE_X; bx += 16) {
                for (int bz = originZ; bz < originZ + MiningConstants.REGION_SIZE_Z; bz += 16) {
                    Holder<Biome> biome = biomeAt(source, bx, bz);
                    ResourceLocation key = biome.unwrapKey().orElseThrow().location();
                    helper.assertTrue(expected.contains(key),
                            d.configName() + " region sample (" + bx + "," + bz + ") stays in pool, got " + key);
                    seen.add(key);
                }
            }
            // 16 格步长盖满全部 64 格片 -> 池内每个成员都必须实际出现 (死变体即挂; patch=64 经离线穷举核准)。
            helper.assertTrue(seen.equals(expected),
                    d.configName() + " full sweep must hit every pool member: expected " + expected + ", saw " + seen);
        }

        // possibleBiomes 恰为 9: 三基础 + 五变体 + wall (缺注册崩 holder 校验, 多注册即池外泄漏)。
        Set<Holder<Biome>> possible = source.possibleBiomes();
        helper.assertTrue(possible.size() == 9, "possibleBiomes exactly 9, got " + possible.size());
        for (String path : new String[]{"mining_easy", "mining_easy_lush", "mining_medium", "mining_medium_lush",
                "mining_medium_dripstone", "mining_hard", "mining_hard_dripstone", "mining_hard_deepdark", "mining_wall"}) {
            ResourceKey<Biome> key = biomeKey(path);
            helper.assertTrue(possible.stream().anyMatch(h -> h.is(key)), "possibleBiomes contains " + path);
        }
        helper.succeed();
    }

    // ============================================================
    // 矿表锚相对性守卫: miningdim placed_feature 的 height_range 禁 absolute 锚
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void placedFeatureHeightAnchorsAreRelativeOnly(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Registry<PlacedFeature> registry = level.registryAccess().registryOrThrow(Registries.PLACED_FEATURE);
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, level.registryAccess());

        int miningdimFeatures = 0;
        int heightRanges = 0;
        for (Map.Entry<ResourceKey<PlacedFeature>, PlacedFeature> entry : registry.entrySet()) {
            ResourceLocation id = entry.getKey().location();
            if (!MiningConstants.MODID.equals(id.getNamespace())) {
                continue;
            }
            miningdimFeatures++;
            JsonElement json = Util.getOrThrow(
                    PlacedFeature.DIRECT_CODEC.encodeStart(ops, entry.getValue()), IllegalStateException::new);
            JsonArray placement = json.getAsJsonObject().getAsJsonArray("placement");
            for (JsonElement modifier : placement) {
                JsonObject obj = modifier.getAsJsonObject();
                if (!"minecraft:height_range".equals(obj.get("type").getAsString())) {
                    continue;
                }
                heightRanges++;
                String height = obj.get("height").toString();
                // absolute 锚在 192 高盒子里对应主世界坐标语义, 必然错位/倒挂 —— 一律相对锚 (above_bottom/below_top)。
                helper.assertFalse(height.contains("\"absolute\""),
                        "placed feature " + id + " uses absolute vertical anchor: " + height);
            }
        }
        // 当前基线: 41 个 miningdim placed_feature 全带 height_range。>= 防回归删减, 也防守卫因过滤错误静默空转。
        helper.assertTrue(miningdimFeatures >= 41,
                "expected at least 41 miningdim placed features, got " + miningdimFeatures);
        helper.assertTrue(heightRanges >= 41,
                "expected at least 41 height_range modifiers scanned, got " + heightRanges);
        helper.succeed();
    }
}
