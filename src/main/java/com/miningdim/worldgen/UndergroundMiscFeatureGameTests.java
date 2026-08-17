package com.miningdim.worldgen;

import com.miningdim.core.Difficulty;
import com.miningdim.core.MiningConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 地下杂项特征的接线回归。
 *
 * 背景: 三个矿洞群系此前只挂了 ore_* 与 monster_room + 陷阱, 十一个生成阶段里九个是空数组; 加上
 * noise_settings 的 surface_rule 只产 bedrock 与 deepslate、default_fluid=air、aquifers/ore_veins 全关,
 * 世界里除了石头/深板岩/矿/刷怪笼/陷阱<b>什么都没有</b>。本组用例锁住补齐后的接线。
 *
 * 这组测试锁的是<b>接线</b>: 哪个难度拿到哪些杂项、以及杂项与矿脉的先后顺序。
 *
 * 刻意不把"原版 configured_feature 的键名有没有写错"当成本组的职责 —— 实测 (故意把 ore_gravel 写成
 * ore_gravel_typo) 原版加载器会直接抛
 * {@code IllegalStateException: Unbound values in registry ...[minecraft:ore_gravel_typo]} 让专用服务端起不来,
 * 那道门比任何断言都硬。本文件第一条用例断言注册表含这八个 placed_feature, 作用是"JSON 确实被当作数据包读到了"
 * (文件放错目录/拼错文件名不会让服务端崩, 只会让特征根本不存在), 而不是校验内层引用。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class UndergroundMiscFeatureGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "worldgen_misc";

    /** 三档共有的杂石/泥土/沙砾 (纯观感 + 沙砾给陷阱探测提供可扫的危害方块)。 */
    private static final List<String> SHARED_MISC = List.of(
            "misc_andesite", "misc_granite", "misc_diorite", "misc_tuff", "misc_dirt", "misc_gravel");

    // ============================================================
    // 1. 八个 placed_feature 全部被数据包加载 (JSON 解析成功 + 引用的原版 configured_feature 键存在)
    // ============================================================

    /**
     * 注册表里必须真有这八个键。写错原版 configured_feature 的键名会让这条挂 —— 那正是本组最需要防的错,
     * 因为它在运行期是静默的 (特征不生成, 服务端不报错)。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void allMiscPlacedFeaturesAreLoaded(GameTestHelper helper) {
        Registry<PlacedFeature> placed = helper.getLevel().registryAccess()
                .registryOrThrow(Registries.PLACED_FEATURE);
        for (String name : SHARED_MISC) {
            ResourceLocation id = new ResourceLocation(MiningConstants.MODID, name);
            helper.assertTrue(placed.containsKey(id),
                    "placed_feature " + id + " 必须已加载 (JSON 解析成功且引用的原版特征键存在)");
        }
        for (String name : List.of("misc_amethyst_geode", "misc_spring_lava")) {
            ResourceLocation id = new ResourceLocation(MiningConstants.MODID, name);
            helper.assertTrue(placed.containsKey(id), "placed_feature " + id + " 必须已加载");
        }
        helper.succeed();
    }

    // ============================================================
    // 2. 三档都拿到共有杂项, 且矿脉排在杂项之后
    // ============================================================

    /**
     * 顺序有两重约束, 都不是洁癖。
     *
     * 一、语义: 杂石属于 stone_ore_replaceables, 排在矿脉<b>之前</b>矿脉才能穿过它们照常生成; 反过来先铺矿
     * 再铺杂石, 杂石会把已生成的矿覆盖掉, 表现是矿产密度莫名变低。
     *
     * 二、原版硬约束: 同一维度内<b>所有群系必须对特征的相对顺序取得一致</b> —— 原版对全部群系的特征表做一次
     * 全局拓扑排序, 只要 easy 说"杂项在矿前"而 hard 说"矿在杂项前", 就构成环, 生成区块时直接抛
     * {@code Feature order cycle found, involved sources: [mining_easy, mining_hard]}。这条实测过 (把 easy
     * 的杂项挪到矿后, 除本用例外还连带炸了三条死亡规则用例, 因为它们要生成矿洞区块)。所以三档必须齐步走,
     * 改一档就得改三档。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void everyDifficultyGetsSharedMiscBeforeOres(GameTestHelper helper) {
        for (Difficulty difficulty : Difficulty.values()) {
            List<ResourceLocation> oreStep = featureIdsAt(helper.getLevel(), difficulty, 6);
            for (String name : SHARED_MISC) {
                ResourceLocation id = new ResourceLocation(MiningConstants.MODID, name);
                helper.assertTrue(oreStep.contains(id),
                        difficulty.configName() + " 的地下矿阶段必须含 " + id + ", 实得 " + oreStep);
            }
            int lastMisc = -1;
            int firstOre = Integer.MAX_VALUE;
            for (int i = 0; i < oreStep.size(); i++) {
                ResourceLocation id = oreStep.get(i);
                if (MiningConstants.MODID.equals(id.getNamespace()) && id.getPath().startsWith("misc_")) {
                    lastMisc = Math.max(lastMisc, i);
                } else if (id.getPath().startsWith("ore_")) {
                    firstOre = Math.min(firstOre, i);
                }
            }
            helper.assertTrue(lastMisc < firstOre,
                    difficulty.configName() + " 的杂项必须全部排在矿脉之前 (否则杂石会覆盖已生成的矿), "
                            + "实得最后一个杂项在 " + lastMisc + ", 第一个矿在 " + firstOre);
        }
        helper.succeed();
    }

    // ============================================================
    // 3. 紫晶洞与岩浆泉的难度门
    // ============================================================

    /**
     * 岩浆泉只给 hard: easy 是新手区, TrapParams.difficultyFactor(EASY)=0 已明确"新手区无致死陷阱",
     * 天然岩浆是同一类致死危害, 提前给到 easy/medium 与那条决策相矛盾。紫晶洞给 medium 起, 作为"往深处走"
     * 的观感与产出理由。删掉难度门 (三档一律给) 会让本条挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void geodeAndLavaSpringAreDifficultyGated(GameTestHelper helper) {
        ResourceLocation geode = new ResourceLocation(MiningConstants.MODID, "misc_amethyst_geode");
        ResourceLocation spring = new ResourceLocation(MiningConstants.MODID, "misc_spring_lava");

        helper.assertFalse(featureIdsAt(helper.getLevel(), Difficulty.EASY, 2).contains(geode),
                "easy 不给紫晶洞");
        helper.assertTrue(featureIdsAt(helper.getLevel(), Difficulty.MEDIUM, 2).contains(geode),
                "medium 必须有紫晶洞");
        helper.assertTrue(featureIdsAt(helper.getLevel(), Difficulty.HARD, 2).contains(geode),
                "hard 必须有紫晶洞");

        helper.assertFalse(featureIdsAt(helper.getLevel(), Difficulty.EASY, 8).contains(spring),
                "easy 不得有天然岩浆泉 (新手区无致死危害)");
        helper.assertFalse(featureIdsAt(helper.getLevel(), Difficulty.MEDIUM, 8).contains(spring),
                "medium 不得有天然岩浆泉");
        helper.assertTrue(featureIdsAt(helper.getLevel(), Difficulty.HARD, 8).contains(spring),
                "hard 必须有天然岩浆泉");
        helper.succeed();
    }

    // ============================================================
    // 工具
    // ============================================================

    /**
     * 取某难度群系某个生成阶段的特征 id 列表 (保序 —— 顺序本身是被断言的对象之一)。
     *
     * @param step GenerationStep.Decoration 序号: 2=LOCAL_MODIFICATIONS, 6=UNDERGROUND_ORES, 8=FLUID_SPRINGS
     */
    private static List<ResourceLocation> featureIdsAt(ServerLevel level, Difficulty difficulty, int step) {
        Biome biome = level.registryAccess().registryOrThrow(Registries.BIOME)
                .getOrThrow(difficulty.biomeKey());
        List<HolderSet<PlacedFeature>> steps = biome.getGenerationSettings().features();
        if (step >= steps.size()) {
            throw new IllegalStateException("biome " + difficulty.configName()
                    + " has only " + steps.size() + " generation steps, asked for " + step);
        }
        List<ResourceLocation> ids = new java.util.ArrayList<>();
        Set<ResourceLocation> seen = new HashSet<>();
        for (Holder<PlacedFeature> holder : steps.get(step)) {
            holder.unwrapKey().ifPresent(key -> {
                if (seen.add(key.location())) {
                    ids.add(key.location());
                }
            });
        }
        return ids;
    }
}
