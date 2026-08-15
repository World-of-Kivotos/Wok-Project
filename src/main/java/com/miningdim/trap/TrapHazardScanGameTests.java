package com.miningdim.trap;

import com.miningdim.core.Difficulty;
import com.miningdim.core.MiningConstants;
import com.miningdim.job.miner.MinerConstants;
import com.miningdim.job.miner.TrapScanService;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * 陷阱危害真实世界扫描 + 坍塌白名单回归 (F033 / F036 / F035)。
 *
 * F033: {@link WorldHazards#hazardAt} 与 {@link TrapScanService#scanWorld} 由离线恒空静态布点表改读真实
 * ServerLevel 方块态; 本类锁死危害判据三条 (岩浆致死 / 崩塌非致死 / 矿石不算陷阱)、真实世界扫描的半径几何与
 * 致死等级门、以及 TRAP_SCAN_MAX_RESULTS 硬顶。删"改扫真实世界"的修复 (回到恒空静态表) 本类必挂。
 *
 * F036: {@link DynamicTrapEngine#isCollapsible} 白名单 + {@link DynamicTrapEngine#dropColumn} 保留原方块态。
 * 删白名单二次校验或把坍塌硬编码回砂砾, 本类的用例 D/E 必挂。
 *
 * F035: {@link DynamicTrapEngine#lethalDynamicAllowed} 的 Easy 区致死类动态陷阱门控, 纯函数回归。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class TrapHazardScanGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "trap_hazard";

    // ============================================================
    // 用例 A (F033 危害判据): 岩浆/岩浆块致死, 砂砾/沙非致死, 矿石/石头非陷阱。
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void hazardAtMapsBlockStatesToTrapType(GameTestHelper helper) {
        helper.assertTrue(WorldHazards.hazardAt(Blocks.LAVA.defaultBlockState()) == TrapType.LAVA_POCKET,
                "LAVA must map to LAVA_POCKET");
        helper.assertTrue(WorldHazards.hazardAt(Blocks.LAVA.defaultBlockState()).lethal(),
                "LAVA_POCKET must be lethal");
        helper.assertTrue(WorldHazards.hazardAt(Blocks.MAGMA_BLOCK.defaultBlockState()) == TrapType.LAVA_POCKET,
                "MAGMA_BLOCK must map to LAVA_POCKET");

        helper.assertTrue(WorldHazards.hazardAt(Blocks.GRAVEL.defaultBlockState()) == TrapType.COLLAPSING_TUNNEL,
                "GRAVEL must map to COLLAPSING_TUNNEL");
        helper.assertFalse(WorldHazards.hazardAt(Blocks.GRAVEL.defaultBlockState()).lethal(),
                "COLLAPSING_TUNNEL must NOT be lethal");
        helper.assertTrue(WorldHazards.hazardAt(Blocks.SAND.defaultBlockState()) == TrapType.COLLAPSING_TUNNEL,
                "SAND must map to COLLAPSING_TUNNEL");
        helper.assertFalse(WorldHazards.hazardAt(Blocks.SAND.defaultBlockState()).lethal(),
                "SAND's COLLAPSING_TUNNEL must NOT be lethal");

        helper.assertTrue(WorldHazards.hazardAt(Blocks.STONE.defaultBlockState()) == null,
                "STONE is not a hazard, must return null");
        helper.assertTrue(WorldHazards.hazardAt(Blocks.DIAMOND_ORE.defaultBlockState()) == null,
                "DIAMOND_ORE is ore, must NOT be reported as a trap");
        helper.assertTrue(WorldHazards.hazardAt(Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState()) == null,
                "DEEPSLATE_DIAMOND_ORE is ore, must NOT be reported as a trap");

        helper.succeed();
    }

    // ============================================================
    // 用例 B (F033 真实世界扫描 + 致死等级门 + 半径几何): 球内非致死 2 + 致死 1, 球外非致死 1。
    // 删"改扫真实世界"的修复 (TrapScanService.scanWorld 回到恒空静态表) -> 两次调用都返回空表 -> 本用例必挂。
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void scanWorldRespectsRadiusAndLethalGate(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos center = helper.absolutePos(new BlockPos(0, 1, 0));
        int radius = 4;

        // 球内: 2 块非致死 GRAVEL + 1 块致死 LAVA, 均满足 dx^2+dy^2+dz^2 <= 16。
        BlockPos a = center.offset(1, 0, 0);   // dist^2 = 1
        BlockPos b = center.offset(0, 1, -1);  // dist^2 = 2
        BlockPos c = center.offset(-2, 0, 2);  // dist^2 = 8, 致死
        level.setBlock(a, Blocks.GRAVEL.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(b, Blocks.GRAVEL.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(c, Blocks.LAVA.defaultBlockState(), Block.UPDATE_ALL);
        // 球外: 1 块非致死 GRAVEL, dist^2 = 25 > 16, 不应出现在任何结果里。
        BlockPos outGravel = center.offset(5, 0, 0);
        level.setBlock(outGravel, Blocks.GRAVEL.defaultBlockState(), Block.UPDATE_ALL);

        List<BlockPos> nonLethal = TrapScanService.scanWorld(level, center, radius, false);
        helper.assertTrue(nonLethal.size() == 2,
                "L5-7 (lethalAllowed=false) must return exactly the 2 non-lethal in-sphere hazards, got " + nonLethal.size());
        helper.assertTrue(nonLethal.contains(a), "non-lethal scan must contain gravel at " + a);
        helper.assertTrue(nonLethal.contains(b), "non-lethal scan must contain gravel at " + b);
        helper.assertFalse(nonLethal.contains(c), "non-lethal scan must NOT contain the lethal lava at " + c);
        helper.assertFalse(nonLethal.contains(outGravel), "non-lethal scan must NOT contain out-of-sphere gravel at " + outGravel);

        List<BlockPos> withLethal = TrapScanService.scanWorld(level, center, radius, true);
        helper.assertTrue(withLethal.size() == 3,
                "L8+ (lethalAllowed=true) must add the lethal hazard, got " + withLethal.size());
        helper.assertTrue(withLethal.contains(c), "lethal-allowed scan must contain lava at " + c);
        helper.assertFalse(withLethal.contains(outGravel), "lethal-allowed scan must still respect the radius gate at " + outGravel);

        helper.succeed();
    }

    // ============================================================
    // 用例 C (F033 硬顶): 半径 3 的球内填满 GRAVEL (约 123 格, 大于硬顶), 结果必须被截到 TRAP_SCAN_MAX_RESULTS。
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void scanWorldCapsAtMaxResults(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos center = helper.absolutePos(new BlockPos(0, 1, 0));
        int radius = 3;
        int r2 = radius * radius;

        int placed = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dy * dy + dz * dz > r2) {
                        continue;
                    }
                    level.setBlock(center.offset(dx, dy, dz), Blocks.GRAVEL.defaultBlockState(), Block.UPDATE_ALL);
                    placed++;
                }
            }
        }
        helper.assertTrue(placed > MinerConstants.TRAP_SCAN_MAX_RESULTS,
                "test setup must place more gravel than the hard cap to actually exercise it, placed=" + placed);

        List<BlockPos> hits = TrapScanService.scanWorld(level, center, radius, false);
        helper.assertTrue(hits.size() == MinerConstants.TRAP_SCAN_MAX_RESULTS,
                "scanWorld must cap results at TRAP_SCAN_MAX_RESULTS=" + MinerConstants.TRAP_SCAN_MAX_RESULTS
                        + ", got " + hits.size());

        helper.succeed();
    }

    // ============================================================
    // 用例 D (F036 承重白名单): 基岩类石材与下落方块为 true, 矿石/远古残骸/箱子为 false。
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void isCollapsibleWhitelistsOnlyBaseStoneAndFallingBlocks(GameTestHelper helper) {
        helper.assertTrue(DynamicTrapEngine.isCollapsible(Blocks.STONE.defaultBlockState()), "STONE must be collapsible");
        helper.assertTrue(DynamicTrapEngine.isCollapsible(Blocks.DEEPSLATE.defaultBlockState()), "DEEPSLATE must be collapsible");
        helper.assertTrue(DynamicTrapEngine.isCollapsible(Blocks.ANDESITE.defaultBlockState()), "ANDESITE must be collapsible");
        helper.assertTrue(DynamicTrapEngine.isCollapsible(Blocks.GRAVEL.defaultBlockState()), "GRAVEL (FallingBlock) must be collapsible");

        helper.assertFalse(DynamicTrapEngine.isCollapsible(Blocks.DIAMOND_ORE.defaultBlockState()),
                "DIAMOND_ORE must NOT be collapsible (ore, not load-bearing stone)");
        helper.assertFalse(DynamicTrapEngine.isCollapsible(Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState()),
                "DEEPSLATE_DIAMOND_ORE must NOT be collapsible");
        helper.assertFalse(DynamicTrapEngine.isCollapsible(Blocks.ANCIENT_DEBRIS.defaultBlockState()),
                "ANCIENT_DEBRIS must NOT be collapsible");
        helper.assertFalse(DynamicTrapEngine.isCollapsible(Blocks.CHEST.defaultBlockState()),
                "CHEST must NOT be collapsible");

        helper.succeed();
    }

    // ============================================================
    // 用例 E (F036 坍塌保留原方块态, 本条 finding 的杀手断言): dropColumn 落下的实体必须携带源方块的真实方块态,
    // 非白名单方块 (矿石) 必须被二次校验拦下 (原样保留, 不生成实体)。
    //
    // 把硬编码 GRAVEL 改回去 -> 第二段断言 (spawned.getBlockState().is(DEEPSLATE)) 必挂;
    // 去掉 isCollapsible 二次校验 -> 第三段断言 (DIAMOND_ORE 被吞/意外生成实体) 必挂。
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void dropColumnPreservesStateAndRejectsNonWhitelist(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        DynamicTrapEngine engine = new DynamicTrapEngine();

        BlockPos p = helper.absolutePos(new BlockPos(2, 1, 2));
        level.setBlock(p, Blocks.DEEPSLATE.defaultBlockState(), Block.UPDATE_ALL);
        engine.dropColumn(level, p);

        helper.assertTrue(level.getBlockState(p).isAir(),
                "source cell must be cleared to air after dropColumn, actual=" + level.getBlockState(p));

        List<FallingBlockEntity> fallingNearP = level.getEntitiesOfClass(FallingBlockEntity.class, new AABB(p).inflate(2.0D));
        helper.assertTrue(fallingNearP.size() == 1,
                "exactly 1 FallingBlockEntity must spawn from dropColumn on a whitelisted block, got " + fallingNearP.size());
        FallingBlockEntity spawned = fallingNearP.get(0);
        helper.assertTrue(spawned.getBlockState().is(Blocks.DEEPSLATE),
                "F036: spawned FallingBlockEntity must carry the original DEEPSLATE state, actual=" + spawned.getBlockState());
        helper.assertFalse(spawned.getBlockState().is(Blocks.GRAVEL),
                "F036 regression guard: spawned FallingBlockEntity must NOT be hardcoded GRAVEL");
        spawned.discard(); // 避免污染同批次其它用例

        BlockPos q = helper.absolutePos(new BlockPos(-2, 1, -2));
        level.setBlock(q, Blocks.DIAMOND_ORE.defaultBlockState(), Block.UPDATE_ALL);
        engine.dropColumn(level, q);

        helper.assertTrue(level.getBlockState(q).is(Blocks.DIAMOND_ORE),
                "F036: non-whitelisted DIAMOND_ORE must remain untouched by dropColumn, actual=" + level.getBlockState(q));
        List<FallingBlockEntity> fallingNearQ = level.getEntitiesOfClass(FallingBlockEntity.class, new AABB(q).inflate(2.0D));
        helper.assertTrue(fallingNearQ.isEmpty(),
                "F036: no FallingBlockEntity may spawn for a non-whitelisted ore block, got " + fallingNearQ.size());

        helper.succeed();
    }

    // ============================================================
    // 用例 G (F033 复核修正: 动态危害登记表叠加扫描)。
    // 预警窗口内的动态陷阱在真正落地成方块态之前就已被 WorldHazards.markActive 登记; scanWorld 必须能
    // 探测到这类 "方块态本身不是危害, 但引擎已登记为活跃危害" 的坐标, 且致死等级门依然按登记的 TrapType
    // 生效。删掉 TrapScanService.scanWorld 里叠加 WorldHazards.activeAt 的分支 -> 本用例必挂 (退化为只查
    // 方块态, 而该坐标此刻仍是普通 STONE/空气)。clearActive 后再扫必须查无 -> 验证注销生效, 不残留幽灵危害。
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void scanWorldDetectsRegisteredActiveHazardsBeforeBlockStateChanges(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos center = helper.absolutePos(new BlockPos(4, 1, 4));
        BlockPos pendingCollapse = center.offset(1, 0, 0);   // 仍是完好 STONE, 尚未落地
        BlockPos pendingLavaBurst = center.offset(-1, 0, 0); // 仍是空气, 尚未 fillLava

        level.setBlock(pendingCollapse, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        // 未登记前: 方块态本身不是危害, 两条坐标都不该出现在扫描结果里。
        List<BlockPos> beforeRegister = TrapScanService.scanWorld(level, center, 3, true);
        helper.assertFalse(beforeRegister.contains(pendingCollapse),
                "before markActive, plain STONE at " + pendingCollapse + " must not be reported as a hazard");
        helper.assertFalse(beforeRegister.contains(pendingLavaBurst),
                "before markActive, empty air at " + pendingLavaBurst + " must not be reported as a hazard");

        WorldHazards.markActive(pendingCollapse, TrapType.LOCAL_COLLAPSE);
        WorldHazards.markActive(pendingLavaBurst, TrapType.LAVA_BURST);
        try {
            List<BlockPos> nonLethal = TrapScanService.scanWorld(level, center, 3, false);
            helper.assertTrue(nonLethal.contains(pendingCollapse),
                    "L5-7 scan must detect the registered non-lethal LOCAL_COLLAPSE at " + pendingCollapse);
            helper.assertFalse(nonLethal.contains(pendingLavaBurst),
                    "L5-7 scan must NOT report the registered lethal LAVA_BURST at " + pendingLavaBurst);

            List<BlockPos> withLethal = TrapScanService.scanWorld(level, center, 3, true);
            helper.assertTrue(withLethal.contains(pendingCollapse),
                    "L8+ scan must still detect the registered LOCAL_COLLAPSE at " + pendingCollapse);
            helper.assertTrue(withLethal.contains(pendingLavaBurst),
                    "L8+ scan must detect the registered lethal LAVA_BURST at " + pendingLavaBurst);
        } finally {
            WorldHazards.clearActive(pendingCollapse);
            WorldHazards.clearActive(pendingLavaBurst);
        }

        List<BlockPos> afterClear = TrapScanService.scanWorld(level, center, 3, true);
        helper.assertFalse(afterClear.contains(pendingCollapse),
                "clearActive must unregister the collapse hazard at " + pendingCollapse);
        helper.assertFalse(afterClear.contains(pendingLavaBurst),
                "clearActive must unregister the lava-burst hazard at " + pendingLavaBurst);

        helper.succeed();
    }

    // ============================================================
    // 用例 F (F035 Easy 门控, 纯函数): 致死类动态陷阱只在 Easy 区被门死, Medium/Hard 放行。
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void lethalDynamicAllowedGatesEasyDifficultyOnly(GameTestHelper helper) {
        helper.assertFalse(DynamicTrapEngine.lethalDynamicAllowed(Difficulty.EASY),
                "EASY must NOT allow lethal dynamic traps");
        helper.assertTrue(DynamicTrapEngine.lethalDynamicAllowed(Difficulty.MEDIUM),
                "MEDIUM must allow lethal dynamic traps");
        helper.assertTrue(DynamicTrapEngine.lethalDynamicAllowed(Difficulty.HARD),
                "HARD must allow lethal dynamic traps");

        helper.succeed();
    }
}
