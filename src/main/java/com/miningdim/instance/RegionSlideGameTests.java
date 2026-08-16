package com.miningdim.instance;

import com.miningdim.core.Difficulty;
import com.miningdim.core.MiningConstants;
import com.miningdim.core.RegionBox;
import com.miningdim.core.RegionLayout;
import com.miningdim.persistence.MiningSavedData;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * 滑动 region 重置 (13.4/D3) 的几何单一真源回归。覆盖:
 *  - F088: 矿山维度高度/minY 必须以 ServerLevel 自身几何为准, 而非编译期常量凭空断言;
 *  - F063: RegionGrid 的网格几何 (sizeX/stride/单元原点) 必须来自显式实参 (运行期 config 派生),
 *    退回编译期固定常量 (256/32) 会使本用例断言的具体数值全部对不上;
 *  - F089 + 滑动游标持久化: MiningSavedData.allocateRegionOriginX 单向推进、绝不复用旧坐标,
 *    且该游标必须随存档 save/load 幸存 (重启后不得重发已用坐标); 重置代数计数器同理持久化;
 *  - biome 归属的运行期真相: RegionLayout.Snapshot 的值语义替换必须反映到 difficultyAt 判定,
 *    退回编译期固定单元几何会使 with() 之后的判定与滑动前完全一样, 断言必挂。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class RegionSlideGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "instance";

    // ============================================================
    // F088: 矿山维度几何单一真源 (ServerLevel 自身高度/minY, 不是编译期断言)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void miningDimensionGeometryMatchesRegionConstants(GameTestHelper helper) {
        ServerLevel miningLevel = helper.getLevel().getServer().getLevel(MiningConstants.MINING_LEVEL);
        if (miningLevel == null) {
            helper.fail("矿山维度 miningdim:mining 缺失; ResetSystem.onServerStarted 在缺失时本应直接抛出");
            throw new IllegalStateException("unreachable: helper.fail already threw");
        }

        helper.assertTrue(miningLevel.getHeight() == MiningConstants.REGION_HEIGHT,
                "矿山维度高度必须等于 MiningConstants.REGION_HEIGHT=" + MiningConstants.REGION_HEIGHT
                        + ", 实际 " + miningLevel.getHeight());
        helper.assertTrue(miningLevel.getMinBuildHeight() == MiningConstants.REGION_MIN_Y,
                "矿山维度 minBuildHeight 必须等于 MiningConstants.REGION_MIN_Y=" + MiningConstants.REGION_MIN_Y
                        + ", 实际 " + miningLevel.getMinBuildHeight());
        helper.assertTrue(MiningConstants.REGION_FULL_MAX_WORLD_Y < miningLevel.getMaxBuildHeight(),
                "REGION_FULL_MAX_WORLD_Y=" + MiningConstants.REGION_FULL_MAX_WORLD_Y
                        + " 必须严格小于维度 maxBuildHeight=" + miningLevel.getMaxBuildHeight());

        helper.succeed();
    }

    // ============================================================
    // F063: RegionGrid 网格几何真读 config (显式几何构造器), 不是编译期常量
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void regionGridExplicitGeometryDerivesFromConfigNotCompileConstants(GameTestHelper helper) {
        RegionGrid grid = new RegionGrid(8, 3, 0, 0);

        helper.assertTrue(grid.sizeX() == 128,
                "regionSizeChunks=8 必须派生 sizeX=8*16=128, 实际 " + grid.sizeX());

        RegionBox cell0 = grid.regionForCell(0, 0);
        RegionBox cell2 = grid.regionForCell(2, 0);
        helper.assertTrue(cell0.originX() == 0,
                "单元 (0,0) 的 originX 必须为网格原点 0, 实际 " + cell0.originX());
        helper.assertTrue(cell2.originX() == 352,
                "单元 (2,0) 的 originX 必须为 2*(8+3)*16=352, 实际 " + cell2.originX());

        RegionBox cell1 = grid.regionForCell(1, 0);
        helper.assertTrue(cell1.originX() - cell0.originX() == 176,
                "相邻单元步长必须为 (regionSizeChunks+bufferChunks)*16=176, 实际 "
                        + (cell1.originX() - cell0.originX()));

        helper.assertTrue(cell0.sizeY() == MiningConstants.REGION_HEIGHT,
                "region 的 sizeY 必须取 MiningConstants.REGION_HEIGHT, 实际 " + cell0.sizeY());

        helper.succeed();
    }

    // ============================================================
    // F089 + 滑动游标持久化: 单向推进 / 绝不复用旧坐标 / 随存档 save-load 幸存
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void slidingFrontierPersistsAcrossSaveLoadAndNeverReplaysCoordinates(GameTestHelper helper) {
        MiningSavedData data = new MiningSavedData();
        data.initRegionFrontierIfAbsent(1000);

        int first = data.allocateRegionOriginX(256, 1024);
        int second = data.allocateRegionOriginX(256, 1024);
        int third = data.allocateRegionOriginX(256, 1024);
        helper.assertTrue(first == 1000,
                "首次分配必须直接取初始 frontier=1000, 实际 " + first);
        helper.assertTrue(second == 2280,
                "第二次分配必须先推进 sizeX+separation=1280 再取值, 得 2280, 实际 " + second);
        helper.assertTrue(third == 3560,
                "第三次分配必须再推进 1280, 得 3560, 实际 " + third);
        helper.assertTrue(second > first && third > second,
                "三次分配的世界 X 原点必须严格递增, 实际 " + first + "/" + second + "/" + third);
        helper.assertTrue(second - first >= 256 + 1024 && third - second >= 256 + 1024,
                "相邻两次分配间距必须 >= regionSizeX+separation=1280 (防止新旧代 region 提前互相可见)");

        CompoundTag tag = data.save(new CompoundTag());
        MiningSavedData reloaded = MiningSavedData.load(tag);
        helper.assertTrue(reloaded.regionFrontierX() == 4840,
                "落盘后重载的 frontier 必须等于最后一次推进值 3560+1280=4840, 实际 " + reloaded.regionFrontierX());

        int fourth = reloaded.allocateRegionOriginX(256, 1024);
        helper.assertTrue(fourth == 4840,
                "重启后首次分配必须从落盘 frontier 继续 (4840), 绝不重发已用过的 1000/2280/3560, 实际 " + fourth);

        reloaded.initRegionFrontierIfAbsent(999);
        helper.assertTrue(reloaded.regionFrontierX() == 6120,
                "对已初始化的 frontier 再调用 initRegionFrontierIfAbsent 必须无效 (幂等, 忽略 999), 实际 "
                        + reloaded.regionFrontierX());

        MiningSavedData genData = new MiningSavedData();
        int gen1 = genData.incrementResetGeneration();
        helper.assertTrue(gen1 == 1,
                "重置代数计数器首次自增必须从 1 起, 实际 " + gen1);
        int gen2 = genData.incrementResetGeneration();
        helper.assertTrue(gen2 == 2,
                "重置代数计数器第二次自增必须为 2, 实际 " + gen2);

        CompoundTag genTag = genData.save(new CompoundTag());
        MiningSavedData genReloaded = MiningSavedData.load(genTag);
        int gen3 = genReloaded.incrementResetGeneration();
        helper.assertTrue(gen3 == 3,
                "落盘重载后再自增必须延续到 3 (代数随存档持久化, 不因重启归零), 实际 " + gen3);

        helper.succeed();
    }

    // ============================================================
    // 滑动后的 biome 归属: RegionLayout.Snapshot 纯值语义反映运行期真相 (F003 的隐性前提)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void regionLayoutSnapshotReflectsSlidBoxWithoutMutatingGlobalState(GameTestHelper helper) {
        RegionLayout.Snapshot s = RegionLayout.current();
        RegionBox hardOld = s.boxOf(Difficulty.HARD);

        helper.assertTrue(s.difficultyAt(hardOld.originX() + 8, hardOld.originZ() + 8) == Difficulty.HARD,
                "当前快照下 hard region 内部坐标必须判定为 HARD");
        helper.assertTrue(s.difficultyAt(hardOld.originX() - 1, hardOld.originZ() + 8) == null,
                "hard region 左边界外一格 (缓冲带) 必须判定为 null (归基岩墙), 不得误判成相邻难度");

        RegionBox moved = new RegionBox(5_000_000, MiningConstants.REGION_MIN_Y, 0,
                hardOld.sizeX(), hardOld.sizeY(), hardOld.sizeZ());
        RegionLayout.Snapshot after = s.with(Difficulty.HARD, moved);

        helper.assertTrue(after.difficultyAt(5_000_008, 8) == Difficulty.HARD,
                "滑动后新坐标必须判定为 HARD (Snapshot 是运行期真相, 不是编译期固定几何)");
        helper.assertTrue(after.difficultyAt(hardOld.originX() + 8, hardOld.originZ() + 8) == null,
                "滑动后旧坐标必须不再判定为 HARD (region 已搬走); 退回编译期固定几何此处仍会误判 HARD, 断言必挂");
        helper.assertTrue(after.boxOf(Difficulty.EASY).equals(s.boxOf(Difficulty.EASY)),
                "with() 只应替换 HARD 一块, EASY 必须保持原值不变");
        helper.assertTrue(s.boxOf(Difficulty.HARD).equals(hardOld),
                "s 是不可变快照, with() 生成新对象不得就地修改原快照 (本用例全程未调用 RegionLayout.set, 不污染全局)");

        helper.succeed();
    }
}
