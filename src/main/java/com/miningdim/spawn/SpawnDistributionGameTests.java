package com.miningdim.spawn;

import com.miningdim.core.Difficulty;
import com.miningdim.core.GenState;
import com.miningdim.core.InstanceState;
import com.miningdim.core.MiningConstants;
import com.miningdim.core.RegionBox;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * spawn 出生点分布回归 (F034): 池占用 TTL 真实生效 + 候选点强制水平散布, 防止多人叠在同一格被
 * 一颗苦力怕一锅端。本类与 {@link SpawnSystem} / {@link SpawnPool} 同包, 直接调包内可见的
 * {@link SpawnSystem#buildPool}。template = "empty" (1x1x1 空模板), 地形由测试自建, 通过真实
 * ServerLevel 方块写入/读取 (isSafe 在真实世界上复核, 不能纯内存伪造)。
 *
 * config 一律走真实 {@link com.miningdim.core.MiningServices#config()} (GameTest 服务端已完整启动,
 * spawn 子表默认值: headroomBlocks=2 / requireSolidFloor=true / lavaAvoidRadius=3 / poolSize=8),
 * SpawnSystem.isSafe/buildPool 本就直接读该门面, 不接受外部注入, 故不比照 PressureGameTests 伪造配置。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class SpawnDistributionGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "spawn_pool";

    /** 池内任意两候选点须满足的最小水平 Chebyshev 间距 (与 SpawnSystem.MIN_SPAWN_SEPARATION 同值, 该常量 private 不可跨类引用)。 */
    private static final int MIN_SEPARATION = 4;

    private static InstanceState newInstance(long instanceId, long seed, RegionBox box) {
        return new InstanceState(instanceId, seed, Difficulty.EASY, box,
                UUID.randomUUID(), false, 0L, GenState.READY);
    }

    /** 铺一片 size x size 的 STONE 地板 (以 base 为 (0,0) 角, base 所在 Y 层), 其上 3 层清成空气。 */
    private static void buildFloor(ServerLevel level, BlockPos base, int size) {
        for (int dx = 0; dx < size; dx++) {
            for (int dz = 0; dz < size; dz++) {
                level.setBlockAndUpdate(base.offset(dx, 0, dz), Blocks.STONE.defaultBlockState());
                for (int dy = 1; dy <= 3; dy++) {
                    level.setBlockAndUpdate(base.offset(dx, dy, dz), Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    /**
     * 把 size x size 列在 [{@link MiningConstants#REGION_MIN_Y}, {@link MiningConstants#REGION_MAX_Y_EXCLUSIVE})
     * 整段竖直高度清成空气。GameTestServer 的世界不是空 void: "empty" 模板只保证结构自身包围盒是空气,
     * 更大范围仍是真实生成的地形 (已实测: 该高度区间内曾天然命中 minecraft:mud), buildPool 的扫描区
     * 覆盖到 region 全高, 必须真清空整段高度才能让"无候选"这条前提真实成立。
     */
    private static void clearAirColumn(ServerLevel level, BlockPos base, int size) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = 0; dx < size; dx++) {
            for (int dz = 0; dz < size; dz++) {
                for (int wy = MiningConstants.REGION_MIN_Y; wy < MiningConstants.REGION_MAX_Y_EXCLUSIVE; wy++) {
                    cursor.set(base.getX() + dx, wy, base.getZ() + dz);
                    level.setBlock(cursor, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
    }

    private static int chebyshev(BlockPos a, BlockPos b) {
        return Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getZ() - b.getZ()));
    }

    // ============================================================
    // 用例 A: SpawnPool 占用 TTL 真生效 (纯逻辑, 不接触世界)
    // 删掉 claim 的占用记账 (occupiedUntil.put) -> 第一条 (4 次 claim(0,...) 应两两不同) 必挂
    // (退化行为下 4 次都会原地返回同一个 pool.get(0))。
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void poolClaimTtlExpiresAndReclaims(GameTestHelper helper) {
        BlockPos p0 = new BlockPos(0, 64, 0);
        BlockPos p1 = new BlockPos(10, 64, 0);
        BlockPos p2 = new BlockPos(0, 64, 10);
        BlockPos p3 = new BlockPos(10, 64, 10);
        SpawnPool pool = new SpawnPool(List.of(p0, p1, p2, p3));

        Set<BlockPos> claimed = new HashSet<>();
        for (int i = 0; i < 4; i++) {
            BlockPos c = pool.claim(0, 100L, 60);
            helper.assertTrue(c != null, "claim #" + i + " on a 4-point pool with none occupied must succeed");
            claimed.add(c);
        }
        helper.assertTrue(claimed.size() == 4,
                "4 successive claim(0, now=100, ttl=60) calls on a 4-point pool must yield 4 DISTINCT points, got "
                        + claimed.size() + ": " + claimed);

        BlockPos fifth = pool.claim(0, 100L, 60);
        helper.assertTrue(fifth == null,
                "5th claim on a fully-occupied 4-point pool must return null, got " + fifth);

        helper.assertTrue(pool.occupiedCount(100L) == 4,
                "occupiedCount(100) after 4 claims (each until=160) must be 4, got " + pool.occupiedCount(100L));

        // 时间推进到 161 (> 100+60=160): 全部 4 个占用已过 TTL, 应可复用。
        BlockPos afterExpiry = pool.claim(0, 161L, 60);
        helper.assertTrue(afterExpiry != null,
                "claim at tick 161 (past TTL boundary 160) must succeed: TTL expiry must free the point");

        helper.assertTrue(pool.occupiedCount(161L) == 1,
                "occupiedCount(161) right after TTL-expiry reclaim must recount to exactly 1 (the fresh claim; "
                        + "the 4 old entries expired at tick 160), got " + pool.occupiedCount(161L));

        helper.succeed();
    }

    // ============================================================
    // 用例 B: F034 杀手断言 —— 同一 SpawnSystem 实例连续两次取点不得落在同一格
    // 把 findSpawn 退回"扫描到第一个安全点就 return"(不经 pool.claim 占用记账) -> 两次都会返回同一个
    // 确定性起点 (deterministicPick(seed, pick, size) 与 pick 无关地扫到同一格) -> equals 断言必挂。
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void findSpawnDistinctPointsAcrossSuccessivePicks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos base = helper.absolutePos(new BlockPos(0, 0, 0));
        buildFloor(level, base, 12);

        RegionBox box = new RegionBox(base.getX(), MiningConstants.REGION_MIN_Y, base.getZ(),
                12, MiningConstants.REGION_HEIGHT, 12);
        InstanceState instance = newInstance(42001L, 777L, box);

        SpawnSystem sys = new SpawnSystem();
        BlockPos first = sys.findSpawn(level, instance);
        BlockPos second = sys.findSpawn(level, instance);

        helper.assertTrue(first != null, "first findSpawn on a safe flat 12x12 floor must return a real point");
        helper.assertTrue(second != null, "second findSpawn on a safe flat 12x12 floor must return a real point");
        helper.assertTrue(!first.equals(second),
                "F034: two successive findSpawn calls on the same instance must NOT return the same cell "
                        + "(both resolved to " + first + ")");

        int chebyshevDist = chebyshev(first, second);
        helper.assertTrue(chebyshevDist >= MIN_SEPARATION,
                "distinct picks must be horizontally separated by Chebyshev distance >= " + MIN_SEPARATION
                        + ", got " + chebyshevDist + " (first=" + first + ", second=" + second + ")");

        helper.assertTrue(sys.isSafe(level, first, instance), "first spawn point must pass isSafe: " + first);
        helper.assertTrue(sys.isSafe(level, second, instance), "second spawn point must pass isSafe: " + second);

        helper.succeed();
    }

    // ============================================================
    // 用例 C: 池真的有多个候选且都合法, 两两满足最小散布约束
    // 删掉 isSeparated 的间距过滤 (isSeparated 恒 true) -> 候选会挤在同一列附近, 距离断言必挂。
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void buildPoolYieldsMultipleSeparatedSafeCandidates(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos base = helper.absolutePos(new BlockPos(0, 0, 0));
        buildFloor(level, base, 12);

        RegionBox box = new RegionBox(base.getX(), MiningConstants.REGION_MIN_Y, base.getZ(),
                12, MiningConstants.REGION_HEIGHT, 12);
        InstanceState instance = newInstance(42002L, 888L, box);

        SpawnSystem sys = new SpawnSystem();
        SpawnPool pool = sys.buildPool(level, instance);

        helper.assertTrue(pool.size() >= 2,
                "a 12x12 flat safe floor must yield >= 2 pool candidates, got " + pool.size());
        helper.assertTrue(pool.anchor() != null, "pool anchor must be non-null when candidates exist");

        List<BlockPos> points = pool.points();
        for (int i = 0; i < points.size(); i++) {
            BlockPos a = points.get(i);
            helper.assertTrue(sys.isSafe(level, a, instance), "pool candidate must itself pass isSafe: " + a);
            for (int j = i + 1; j < points.size(); j++) {
                BlockPos b = points.get(j);
                int dist = chebyshev(a, b);
                helper.assertTrue(dist >= MIN_SEPARATION,
                        "F034: pool candidates must be pairwise separated by Chebyshev >= " + MIN_SEPARATION
                                + ", got " + dist + " between " + a + " and " + b);
            }
        }

        helper.succeed();
    }

    // ============================================================
    // 用例 D: 取不到点时兜底平台真被建出来, 而不是返回 null / 抛异常
    // 删掉 buildFallbackPlatform 调用 (findSpawn 池空时直接 return null) -> 第一条 null 检查必挂;
    // 若保留调用但漏掉方块写入 -> 后续 STONE / 空气 断言必挂。
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void findSpawnFallsBackToPlatformWhenPoolEmpty(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // 显式清空整段竖直高度 (而非假定原生纯空气, GameTestServer 世界并非 void): buildPool 扫不到任何候选。
        BlockPos base = helper.absolutePos(new BlockPos(0, 0, 0));
        clearAirColumn(level, base, 8);

        RegionBox box = new RegionBox(base.getX(), MiningConstants.REGION_MIN_Y, base.getZ(),
                8, MiningConstants.REGION_HEIGHT, 8);
        InstanceState instance = newInstance(42003L, 999L, box);

        SpawnSystem sys = new SpawnSystem();
        BlockPos spawn = sys.findSpawn(level, instance);

        helper.assertTrue(spawn != null,
                "findSpawn on an all-air region with no floor must still return a point via the fallback platform");
        helper.assertTrue(sys.isSafe(level, spawn, instance),
                "fallback platform spawn point must itself pass isSafe: " + spawn);

        helper.assertTrue(level.getBlockState(spawn.below()).is(Blocks.STONE),
                "fallback platform must have actually placed STONE beneath the spawn point, got "
                        + level.getBlockState(spawn.below()));
        helper.assertTrue(level.getBlockState(spawn).isAir(),
                "fallback platform spawn cell must be air, got " + level.getBlockState(spawn));
        helper.assertTrue(level.getBlockState(spawn.above()).isAir(),
                "fallback platform spawn cell + 1 (headroom) must be air, got " + level.getBlockState(spawn.above()));

        helper.succeed();
    }

    // ============================================================
    // 用例 E (F034 复核修正): 兜底平台连续多次调用不得永远摞在同一格。
    // 删掉 claimFallbackCell 的抖动查找 (findSpawn 退回恒定的 fallbackCenter) -> 3 次调用会得到 3 个
    // 完全相同的 BlockPos -> distinct 断言必挂 (复核者原话: "该实例往后每一个入场玩家都被传送到同一格")。
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void findSpawnFallbackPlatformsAreNotStackedOnTheSameCell(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos base = helper.absolutePos(new BlockPos(0, 0, 0));
        clearAirColumn(level, base, 8);

        RegionBox box = new RegionBox(base.getX(), MiningConstants.REGION_MIN_Y, base.getZ(),
                8, MiningConstants.REGION_HEIGHT, 8);
        InstanceState instance = newInstance(42005L, 222L, box);

        SpawnSystem sys = new SpawnSystem();
        Set<BlockPos> spawns = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            BlockPos spawn = sys.findSpawn(level, instance);
            helper.assertTrue(spawn != null, "fallback findSpawn #" + i + " must return a point");
            spawns.add(spawn);
        }

        helper.assertTrue(spawns.size() >= 2,
                "F034: 3 consecutive findSpawn calls on a permanently-void region (all routed through the fallback "
                        + "platform) must not all stack on the same cell, got " + spawns.size() + " distinct: " + spawns);

        helper.succeed();
    }

    // ============================================================
    // 用例 F (F034 复核修正): 空池不得被永久缓存。第一次 findSpawn 在全空区域走兜底平台后, 若地形随后
    // 变得可用 (玩家把区域挖开/整平), 下一次 findSpawn 必须重新拿到真实池候选, 而不是被永久钉死在
    // 兜底平台分支。
    // 把 "只缓存非空池" 退回无条件 pools.computeIfAbsent 缓存 -> 第二次 findSpawn 仍命中缓存的空池,
    // 结果必然落在 buildPool 扫出的真实候选集合之外 -> 成员断言必挂。
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void findSpawnRetriesAfterEmptyPoolInsteadOfCachingForever(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos base = helper.absolutePos(new BlockPos(0, 0, 0));
        clearAirColumn(level, base, 8);

        RegionBox box = new RegionBox(base.getX(), MiningConstants.REGION_MIN_Y, base.getZ(),
                8, MiningConstants.REGION_HEIGHT, 8);
        InstanceState instance = newInstance(42004L, 111L, box);

        SpawnSystem sys = new SpawnSystem();
        BlockPos firstSpawn = sys.findSpawn(level, instance);
        helper.assertTrue(firstSpawn != null, "first findSpawn on an all-void region must still return a fallback point");

        // 地形变得可用: 铺一片真实安全地板 (模拟玩家把区域挖开/整平)。
        buildFloor(level, base, 8);
        SpawnPool truePool = sys.buildPool(level, instance);
        helper.assertTrue(truePool.size() > 0,
                "after building a real floor, buildPool must now find real candidates, got " + truePool.size());

        BlockPos secondSpawn = sys.findSpawn(level, instance);
        helper.assertTrue(secondSpawn != null, "second findSpawn must return a point");
        helper.assertTrue(truePool.points().contains(secondSpawn),
                "F034: after terrain became minable, findSpawn must pick up the real pool instead of staying stuck "
                        + "on a permanently-cached empty pool (second spawn " + secondSpawn
                        + " is not among the real candidates " + truePool.points() + ")");

        helper.succeed();
    }
}
