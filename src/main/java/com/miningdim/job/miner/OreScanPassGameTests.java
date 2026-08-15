package com.miningdim.job.miner;

import com.miningdim.core.MiningConstants;
import com.miningdim.ore.OreType;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.Set;

/**
 * 探矿单趟分桶扫描回归 (F085): {@link OreScanService#scanWorldDetailed} 把旧实现"每个矿种各扫一遍整球"
 * 改成"单趟遍历同时分桶", 本类锁死改写前后语义完全等价 —— 单矿种优先序、次优先落桶、硬顶、等级门四条。
 *
 * 删 preferenceOrder 选桶逻辑或把桶顺序写错, 用例 A/B 必挂; 删硬顶判据, 用例 C 必挂; 删等级门, 用例 D 必挂。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class OreScanPassGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "ore_scan_pass";

    /**
     * GameTestServer 的世界不是空 void: "empty" 模板只保证结构自身包围盒是空气, 球心周边仍是真实生成的地形,
     * 可能天然含铁矿等常见矿种 (已实测: 相邻用例在同一坐标附近命中过天然铁矿, 见 F085 回归排查记录)。
     * 在放置本用例的专属矿种前先把整个扫描球 (半径 +1 留冗余) 清成空气, 消除天然地形矿种的干扰,
     * 保证 "球内只有我放的矿" 这条前提真实成立。
     */
    private static void clearScanSphere(ServerLevel level, BlockPos center, int radius) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int clearRadius = radius + 1;
        for (int dx = -clearRadius; dx <= clearRadius; dx++) {
            for (int dy = -clearRadius; dy <= clearRadius; dy++) {
                for (int dz = -clearRadius; dz <= clearRadius; dz++) {
                    cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    level.setBlock(cursor, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
    }

    // ============================================================
    // 用例 A (F085 等价性 - 优先序): 铁 3 + 钻 2 同球, 高优先矿种 (铁) 必须赢, 结果不含钻石坐标。
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void scanWorldDetailedPicksHighestPriorityOre(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos center = helper.absolutePos(new BlockPos(0, 1, 0));
        int radius = 4;
        clearScanSphere(level, center, radius);

        BlockPos iron1 = center.offset(1, 0, 0);
        BlockPos iron2 = center.offset(0, 1, -1);
        BlockPos iron3 = center.offset(-1, 0, 1);
        level.setBlock(iron1, Blocks.IRON_ORE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(iron2, Blocks.IRON_ORE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(iron3, Blocks.IRON_ORE.defaultBlockState(), Block.UPDATE_ALL);

        BlockPos diamond1 = center.offset(2, 0, 0);
        BlockPos diamond2 = center.offset(0, -1, 2);
        level.setBlock(diamond1, Blocks.DIAMOND_ORE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(diamond2, Blocks.DIAMOND_ORE.defaultBlockState(), Block.UPDATE_ALL);

        Set<OreType> allowed = OreScanService.allowedOres(6); // 含 IRON/COAL/DIAMOND
        OreScanService.ScanHit hit = OreScanService.scanWorldDetailed(level, center, radius, allowed);

        helper.assertTrue(hit.ore() == OreType.IRON,
                "highest-priority ore present in-sphere (IRON) must win, actual=" + hit.ore());
        helper.assertTrue(hit.positions().size() == 3,
                "must return exactly the 3 in-sphere iron positions, got " + hit.positions().size());
        helper.assertTrue(hit.positions().contains(iron1) && hit.positions().contains(iron2) && hit.positions().contains(iron3),
                "result must contain all 3 iron positions");
        helper.assertFalse(hit.positions().contains(diamond1),
                "result must NOT contain diamond position " + diamond1 + " when iron (higher priority) is present");
        helper.assertFalse(hit.positions().contains(diamond2),
                "result must NOT contain diamond position " + diamond2 + " when iron (higher priority) is present");

        helper.succeed();
    }

    // ============================================================
    // 用例 B (F085 等价性 - 落到次优先): 球内只有钻石 (无铁/煤), 高优先桶为空时必须正确落到下一个非空桶。
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void scanWorldDetailedFallsThroughToNextNonEmptyBucket(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos center = helper.absolutePos(new BlockPos(0, 1, 0));
        int radius = 4;
        clearScanSphere(level, center, radius);

        BlockPos deepDiamond1 = center.offset(1, 0, 0);
        BlockPos deepDiamond2 = center.offset(-1, 1, 0);
        level.setBlock(deepDiamond1, Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(deepDiamond2, Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState(), Block.UPDATE_ALL);

        Set<OreType> allowed = OreScanService.allowedOres(6); // 含 IRON/COAL/DIAMOND, 但球内 IRON/COAL 桶为空
        OreScanService.ScanHit hit = OreScanService.scanWorldDetailed(level, center, radius, allowed);

        helper.assertTrue(hit.ore() == OreType.DIAMOND,
                "with empty IRON/COAL buckets, scan must fall through to DIAMOND, actual=" + hit.ore());
        helper.assertTrue(hit.positions().size() == 2,
                "must return exactly the 2 deepslate-diamond positions, got " + hit.positions().size());
        helper.assertTrue(hit.positions().contains(deepDiamond1) && hit.positions().contains(deepDiamond2),
                "result must contain both deepslate-diamond positions");

        helper.succeed();
    }

    // ============================================================
    // 用例 C (F085 硬顶): 球内放超过 ORE_SCAN_MAX_RESULTS 块 IRON_ORE, 结果必须被截到硬顶, 不是全量。
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void scanWorldDetailedCapsAtMaxResults(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos center = helper.absolutePos(new BlockPos(0, 1, 0));
        int radius = 4;
        int r2 = radius * radius;

        int placed = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dy * dy + dz * dz > r2) {
                        continue;
                    }
                    level.setBlock(center.offset(dx, dy, dz), Blocks.IRON_ORE.defaultBlockState(), Block.UPDATE_ALL);
                    placed++;
                }
            }
        }
        helper.assertTrue(placed > MinerConstants.ORE_SCAN_MAX_RESULTS,
                "test setup must place more iron ore than the hard cap to actually exercise it, placed=" + placed);

        Set<OreType> allowed = OreScanService.allowedOres(6);
        OreScanService.ScanHit hit = OreScanService.scanWorldDetailed(level, center, radius, allowed);

        helper.assertTrue(hit.ore() == OreType.IRON, "capped scan must still report IRON as the hit ore, actual=" + hit.ore());
        helper.assertTrue(hit.positions().size() == MinerConstants.ORE_SCAN_MAX_RESULTS,
                "scanWorldDetailed must cap results at ORE_SCAN_MAX_RESULTS=" + MinerConstants.ORE_SCAN_MAX_RESULTS
                        + ", got " + hit.positions().size());

        helper.succeed();
    }

    // ============================================================
    // 用例 D (等级门未被单趟重构破坏): L2 (< ORE_SCAN_UNLOCK_LEVEL=3) 的可探集合为空, 即便球内有矿也不下发。
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void scanWorldDetailedLevelGateStillBlocksBelowUnlock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos center = helper.absolutePos(new BlockPos(0, 1, 0));
        int radius = 4;

        level.setBlock(center.offset(1, 0, 0), Blocks.IRON_ORE.defaultBlockState(), Block.UPDATE_ALL);

        Set<OreType> lockedL2 = OreScanService.allowedOres(2);
        helper.assertTrue(lockedL2.isEmpty(), "L2 allowedOres must be empty (scan locked below L3)");

        OreScanService.ScanHit hit = OreScanService.scanWorldDetailed(level, center, radius, lockedL2);
        helper.assertTrue(hit.ore() == null, "L2 (empty allowed set) must report no ore even with iron present, actual=" + hit.ore());
        helper.assertTrue(hit.positions().isEmpty(),
                "L2 (empty allowed set) must return no positions, got " + hit.positions().size());

        helper.succeed();
    }
}
