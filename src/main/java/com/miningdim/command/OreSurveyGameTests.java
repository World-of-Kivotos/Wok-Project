package com.miningdim.command;

import com.miningdim.core.MiningConstants;
import com.miningdim.trap.StaticTrapKind;
import com.miningdim.trap.TrapRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * /mining oresurvey 统计核心契约 (worldgen 翻修 1.0.2)。断言具体业务结果 (删被测逻辑必挂, 禁弱校验):
 * classify 的矿种归并 (石头/深板岩变体合一, 非矿 null) / layerBucket 的分层公式 (含负 y 边界) /
 * countTraps 的立方体精确过滤 (Chebyshev 边界含入含出) / survey 的世界扫描精确计数 + 分层归属 + 立方体排除。
 *
 * 世界扫描测试把方块摆在结构原点上方 +40 高空: 全库其它 GameTest 的世界写都贴地 (y offset <= 5),
 * 高空立方体内除本测试摆的方块外只有空气, 计数断言可用精确等值而不受邻近测试结构污染。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class OreSurveyGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "worldgen";

    // ============================================================
    // classify: 石头/深板岩变体归并同类; 非矿 null
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void oreSurveyClassifyMergesVariantsAndRejectsNonOres(GameTestHelper helper) {
        helper.assertTrue(OreSurvey.classify(Blocks.COAL_ORE.defaultBlockState()) == OreSurvey.OreCategory.COAL,
                "coal_ore -> COAL");
        helper.assertTrue(OreSurvey.classify(Blocks.DEEPSLATE_COAL_ORE.defaultBlockState()) == OreSurvey.OreCategory.COAL,
                "deepslate_coal_ore merges into COAL");
        helper.assertTrue(OreSurvey.classify(Blocks.DEEPSLATE_GOLD_ORE.defaultBlockState()) == OreSurvey.OreCategory.GOLD,
                "deepslate_gold_ore -> GOLD");
        helper.assertTrue(OreSurvey.classify(Blocks.ANCIENT_DEBRIS.defaultBlockState()) == OreSurvey.OreCategory.ANCIENT_DEBRIS,
                "ancient_debris -> ANCIENT_DEBRIS");
        helper.assertTrue(OreSurvey.classify(Blocks.STONE.defaultBlockState()) == null, "stone -> null");
        helper.assertTrue(OreSurvey.classify(Blocks.DEEPSLATE.defaultBlockState()) == null, "deepslate -> null");
        helper.assertTrue(OreSurvey.classify(Blocks.RAW_IRON_BLOCK.defaultBlockState()) == null,
                "raw_iron_block is not an ore -> null");
        helper.succeed();
    }

    // ============================================================
    // layerBucket: 32 格分层公式 (含负 y / 边界值)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void oreSurveyLayerBucketHandlesBoundariesAndNegativeY(GameTestHelper helper) {
        helper.assertTrue(OreSurvey.layerBucket(0) == 0, "y=0 -> bucket 0");
        helper.assertTrue(OreSurvey.layerBucket(31) == 0, "y=31 -> bucket 0 (upper edge)");
        helper.assertTrue(OreSurvey.layerBucket(32) == 32, "y=32 -> bucket 32 (lower edge)");
        helper.assertTrue(OreSurvey.layerBucket(63) == 32, "y=63 -> bucket 32");
        helper.assertTrue(OreSurvey.layerBucket(-1) == -32, "y=-1 -> bucket -32 (floorDiv, not truncation)");
        helper.assertTrue(OreSurvey.layerBucket(-32) == -32, "y=-32 -> bucket -32 (lower edge)");
        helper.assertTrue(OreSurvey.layerBucket(-40) == -64, "y=-40 -> bucket -64");
        helper.assertTrue(OreSurvey.layerBucket(-64) == -64, "y=-64 -> bucket -64 (world bottom)");
        helper.succeed();
    }

    // ============================================================
    // countTraps: 立方体 Chebyshev 过滤 (边界含入 radius, 排除 radius+1) + 分 kind 归并 + 未命中 kind 计 0
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void oreSurveyCountTrapsFiltersCubeBoundaryExactly(GameTestHelper helper) {
        BlockPos center = new BlockPos(100, 50, 100);
        int radius = 8;
        List<TrapRegistry.Entry> entries = List.of(
                // 含入: 中心本身 + 三轴各贴 radius 边界。
                new TrapRegistry.Entry(center, StaticTrapKind.TNT_VEIN),
                new TrapRegistry.Entry(center.offset(radius, 0, 0), StaticTrapKind.TNT_VEIN),
                new TrapRegistry.Entry(center.offset(0, -radius, 0), StaticTrapKind.LAVA_POCKET),
                new TrapRegistry.Entry(center.offset(-radius, radius, radius), StaticTrapKind.FAKE_ORE),
                // 排除: 任一轴超出 radius 即出立方体。
                new TrapRegistry.Entry(center.offset(radius + 1, 0, 0), StaticTrapKind.FAKE_ORE),
                new TrapRegistry.Entry(center.offset(0, radius + 1, 0), StaticTrapKind.COLLAPSING_TUNNEL),
                new TrapRegistry.Entry(center.offset(radius, radius, radius + 1), StaticTrapKind.TNT_VEIN));

        Map<StaticTrapKind, Integer> counts = OreSurvey.countTraps(entries, center, radius);
        helper.assertTrue(counts.get(StaticTrapKind.TNT_VEIN) == 2, "tnt_vein: center + x-boundary = 2");
        helper.assertTrue(counts.get(StaticTrapKind.LAVA_POCKET) == 1, "lava_pocket: y-boundary included = 1");
        helper.assertTrue(counts.get(StaticTrapKind.FAKE_ORE) == 1, "fake_ore: corner included, x+1 excluded = 1");
        helper.assertTrue(counts.get(StaticTrapKind.COLLAPSING_TUNNEL) == 0,
                "collapsing_tunnel: only entry is outside -> present with count 0");
        helper.succeed();
    }

    // ============================================================
    // survey: 世界扫描精确计数 + 分层归属 + 立方体排除 (高空隔离摆块)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void oreSurveyWorldScanCountsAndLayersExactly(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int radius = 3;
        BlockPos centerRel = new BlockPos(2, 42, 2);
        BlockPos center = helper.absolutePos(centerRel);

        // 环境净化: gametest 地块坐落在真实地形里 (实测本套件结构origin在 y=-60 附近, 立方体正落天然深板岩
        // 金矿带), 先把整个扫描立方体清成空气, 保证后续精确等值断言只对本测试摆的方块成立 (与环境/批次布局无关)。
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    helper.setBlock(centerRel.offset(dx, dy, dz), Blocks.AIR);
                }
            }
        }
        // 立方体外的两个排除位也先清底再摆 (它们外侧的天然方块不影响计数, 无需清)。
        helper.setBlock(centerRel.offset(0, radius + 1, 0), Blocks.AIR);
        helper.setBlock(centerRel.offset(radius + 1, 0, 0), Blocks.AIR);

        // 立方体内 6 块 (含 y 下边界 dy=-3): 绿宝石 2 + 残骸 3 + 红石 1。
        BlockPos emeraldA = centerRel.offset(0, -1, 0);
        BlockPos emeraldB = centerRel.offset(1, 1, 0);
        BlockPos debrisA = centerRel.offset(-1, -1, -1);
        BlockPos debrisB = centerRel.offset(0, 2, 1);
        BlockPos debrisC = centerRel.offset(2, 0, 0);
        BlockPos redstone = centerRel.offset(0, -radius, 0);
        helper.setBlock(emeraldA, Blocks.DEEPSLATE_EMERALD_ORE);
        helper.setBlock(emeraldB, Blocks.DEEPSLATE_EMERALD_ORE);
        helper.setBlock(debrisA, Blocks.ANCIENT_DEBRIS);
        helper.setBlock(debrisB, Blocks.ANCIENT_DEBRIS);
        helper.setBlock(debrisC, Blocks.ANCIENT_DEBRIS);
        helper.setBlock(redstone, Blocks.DEEPSLATE_REDSTONE_ORE);
        // 立方体外 2 块 (dy=+4 / dx=+4): 必须被排除。
        helper.setBlock(centerRel.offset(0, radius + 1, 0), Blocks.DEEPSLATE_EMERALD_ORE);
        helper.setBlock(centerRel.offset(radius + 1, 0, 0), Blocks.ANCIENT_DEBRIS);

        OreSurvey.Result result = OreSurvey.survey(level, center, radius);

        helper.assertTrue(result.ores().getOrDefault(OreSurvey.OreCategory.EMERALD, 0) == 2,
                "emerald exactly 2 (outside-cube emerald excluded), got " + result.ores());
        helper.assertTrue(result.ores().getOrDefault(OreSurvey.OreCategory.ANCIENT_DEBRIS, 0) == 3,
                "ancient_debris exactly 3 (outside-cube debris excluded), got " + result.ores());
        helper.assertTrue(result.ores().getOrDefault(OreSurvey.OreCategory.REDSTONE, 0) == 1,
                "redstone exactly 1 (y lower boundary dy=-radius included), got " + result.ores());
        helper.assertTrue(result.total() == 6, "total exactly 6, got " + result.total()
                + " ores=" + result.ores() + " layers=" + result.layers() + " center=" + center);

        // 分层归属: 期望层表由每块的绝对 y 经 layerBucket 归层累计 (layerBucket 公式另有独立硬编码测试, 非循环论证)。
        Map<Integer, Integer> expectedLayers = new HashMap<>();
        for (BlockPos rel : List.of(emeraldA, emeraldB, debrisA, debrisB, debrisC, redstone)) {
            int absY = helper.absolutePos(rel).getY();
            expectedLayers.merge(OreSurvey.layerBucket(absY), 1, Integer::sum);
        }
        helper.assertTrue(expectedLayers.equals(result.layers()),
                "layer map exact: expected " + expectedLayers + ", got " + result.layers());

        helper.assertTrue(result.loadedChunks() >= 1, "at least center chunk loaded");
        helper.assertTrue(result.scannedSections() >= 1, "palette precheck kept at least one ore-bearing section");
        helper.succeed();
    }
}
