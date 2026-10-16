package com.miningdim.job.farmer.granary;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.farmer.granary.block.GranaryBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * 粮仓房间判定的 GameTest (docs/Spoilage_Granary_DesignSpec.md「房间判定」「容量怎么算」)。
 *
 * 屋子在代码里用 setBlock 搭: 内部 3×3×3 (27 格) 的封闭石头盒子, 账台放在室内正中。
 * 每条用例只改一件事 (换一片树叶、灌一格水、拆一面墙、把囤嵌进墙), 于是失败原因唯一可归因。
 *
 * <b>每条用例占自己的一条槽位</b> (相隔 {@value #SLOT} 格)。同一批次里的用例是并行跑的, 共用一片区域,
 * 都用同一组相对坐标就会互相覆盖方块 (实测表现是「封闭小屋」被判出「一间屋两座账台」) ——
 * 分槽位比给每条用例单开一个批次更省事, 也更不容易再踩。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class GranaryStructureGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "granary_structure";

    /** 每条用例的槽位间距 (够放 5×5×5 的小屋与它外面那一格)。 */
    private static final int SLOT = 16;

    private static final int LOW = 1;
    private static final int HIGH = 3;   // 内部 3×3×3 = 27 格

    // ============================================================
    // 成立与计数
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void sealedRoomIsRecognisedAndCountsItsParts(GameTestHelper helper) {
        int slot = 0;
        buildRoom(helper, slot);
        helper.setBlock(center(slot), GranaryBlocks.CENTER.get());
        helper.setBlock(bin(slot, LOW, LOW), GranaryBlocks.BIN.get());
        helper.setBlock(bin(slot, HIGH, HIGH), GranaryBlocks.BIN.get());
        helper.setBlock(vent(slot), GranaryBlocks.VENT.get());

        GranaryStructureScanner.ScanResult result = scan(helper, slot);

        helper.assertTrue(result.ok(), "封闭小屋必须判成立, 实际 " + result.failure());
        helper.assertTrue(result.interiorCells() == 27,
                "室内 27 格 (账台、囤、冷源占的格子也算室内), 实际 " + result.interiorCells());
        helper.assertTrue(result.bins() == 2, "屋里两个储粮囤, 实际 " + result.bins());
        helper.assertTrue(result.binsNotEnclosed() == 0, "两个囤都整个在屋里");
        helper.assertTrue(result.centers() == 1, "一间屋一座账台");
        helper.assertTrue(result.vents() == 1, "贴着室内的那扇气窗要数上, 实际 " + result.vents());
        // 可支撑囤数 = min(室内 27 ÷ 9 = 3, 气窗 1 × 4 = 4) = 3。
        helper.assertTrue(result.supportedBins(false) == 3,
                "粮棚可支撑 3 个囤, 实际 " + result.supportedBins(false));
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void coldSourcesOnlyRaiseTheColdTierSupport(GameTestHelper helper) {
        int slot = 1;
        buildRoom(helper, slot);
        helper.setBlock(center(slot), GranaryBlocks.CENTER.get());
        helper.setBlock(vent(slot), GranaryBlocks.VENT.get());
        // 地板下面垫一块蓝冰: 贴着屋里就算冷源 (规格: 贴在屋里就行, 可以是墙/地板/天花板)。
        helper.setBlock(new BlockPos(slot * SLOT + 2, LOW - 1, 2), Blocks.BLUE_ICE);

        GranaryStructureScanner.ScanResult result = scan(helper, slot);

        helper.assertTrue(result.ok(), "有冷源的小屋照样成立, 实际 " + result.failure());
        helper.assertTrue(result.coldSources() == 1, "数到一个冷源, 实际 " + result.coldSources());
        helper.assertTrue(result.supportedBins(false) == 3, "粮棚不看冷源");
        helper.assertTrue(result.supportedBins(true) == 2,
                "冷库取 min(3, 4, 1×2) = 2, 实际 " + result.supportedBins(true));
        helper.succeed();
    }

    // ============================================================
    // 四类失败原因
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void leavesAreALeak(GameTestHelper helper) {
        int slot = 2;
        buildRoom(helper, slot);
        helper.setBlock(center(slot), GranaryBlocks.CENTER.get());
        helper.setBlock(vent(slot), Blocks.OAK_LEAVES);

        helper.assertTrue(scan(helper, slot).failure() == GranaryStructureScanner.Failure.LEAK,
                "树叶算漏洞, 实际 " + scan(helper, slot).failure());
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void cobwebAndScaffoldingAndPowderSnowAreLeaksToo(GameTestHelper helper) {
        int slot = 3;
        verifyLeak(helper, slot, Blocks.COBWEB);
        verifyLeak(helper, slot + 1, Blocks.SCAFFOLDING);
        verifyLeak(helper, slot + 2, Blocks.POWDER_SNOW);
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void fluidInsideFails(GameTestHelper helper) {
        int slot = 6;
        buildRoom(helper, slot);
        helper.setBlock(center(slot), GranaryBlocks.CENTER.get());
        helper.setBlock(new BlockPos(slot * SLOT + 3, LOW, LOW), Blocks.WATER);

        helper.assertTrue(scan(helper, slot).failure() == GranaryStructureScanner.Failure.FLUID,
                "屋里进水不成立, 实际 " + scan(helper, slot).failure());
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void holeInTheWallLetsTheFloodOut(GameTestHelper helper) {
        int slot = 7;
        buildRoom(helper, slot);
        helper.setBlock(center(slot), GranaryBlocks.CENTER.get());
        // 把缺口与它外面那一格一起挖空 (只挖这一格, 不挖长走廊)。
        helper.setBlock(new BlockPos(slot * SLOT + LOW - 2, 2, 2), Blocks.AIR);
        helper.setBlock(vent(slot), Blocks.AIR);

        GranaryStructureScanner.ScanResult result = scan(helper, slot);

        // 拆一面墙之后洪泛必然冲出小屋: 室内格数大于 3×3×3=27 就是它跑出去了的证据。
        // (真世界里屋外是无边界的空气, 同一条路会先撞上「超出 ±24 范围」或「超过 4096 格」两条上限;
        //   测试世界里屋外是实心方块, 所以这里断言的是更本质的那条判据 —— 洪泛没有被小屋关住。)
        helper.assertTrue(result.interiorCells() > 27,
                "拆了墙就不该还停在 27 格, 实际 " + result.interiorCells());
        helper.succeed();
    }

    // ============================================================
    // 囤必须整个在屋里 + 一间屋只能一座账台
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void binEmbeddedInTheWallIsNotCounted(GameTestHelper helper) {
        int slot = 8;
        buildRoom(helper, slot);
        helper.setBlock(center(slot), GranaryBlocks.CENTER.get());
        // 把囤塞进墙里, 并把它朝外那一面挖空: 于是它有一面朝外, 不算数
        // (规格: 嵌在墙里、或有一面朝外的不算)。
        helper.setBlock(new BlockPos(slot * SLOT + LOW - 2, 2, LOW), Blocks.AIR);
        helper.setBlock(new BlockPos(slot * SLOT + LOW - 1, 2, LOW), GranaryBlocks.BIN.get());

        GranaryStructureScanner.ScanResult result = scan(helper, slot);
        helper.assertTrue(result.ok(), "屋子本身还是成立的, 实际 " + result.failure());
        helper.assertTrue(result.bins() == 0, "嵌墙的囤不算数, 实际 " + result.bins());
        helper.assertTrue(result.binsNotEnclosed() == 1, "要如实报出「有一个囤不算数」");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void secondCenterInTheSameRoomIsAConflict(GameTestHelper helper) {
        int slot = 9;
        buildRoom(helper, slot);
        helper.setBlock(center(slot), GranaryBlocks.CENTER.get());
        helper.setBlock(new BlockPos(slot * SLOT + LOW, 2, LOW), GranaryBlocks.CENTER.get());

        helper.assertTrue(scan(helper, slot).failure() == GranaryStructureScanner.Failure.DUPLICATE_CENTER,
                "一间屋只能有一座账台, 实际 " + scan(helper, slot).failure());
        helper.succeed();
    }

    // ============================================================
    // 夹具
    // ============================================================

    private static void verifyLeak(GameTestHelper helper, int slot, Block leak) {
        buildRoom(helper, slot);
        helper.setBlock(center(slot), GranaryBlocks.CENTER.get());
        helper.setBlock(vent(slot), leak);
        helper.assertTrue(scan(helper, slot).failure() == GranaryStructureScanner.Failure.LEAK,
                leak.getName().getString() + " 算漏洞, 实际 " + scan(helper, slot).failure());
    }

    private static GranaryStructureScanner.ScanResult scan(GameTestHelper helper, int slot) {
        return GranaryStructureScanner.scan(helper.getLevel(), helper.absolutePos(center(slot)));
    }

    /** 账台: 室内正中 (它的 6 个邻格都是室内空气)。 */
    private static BlockPos center(int slot) {
        return new BlockPos(slot * SLOT + 2, 2, 2);
    }

    /** 气窗: 嵌在西墙上, 贴着室内。 */
    private static BlockPos vent(int slot) {
        return new BlockPos(slot * SLOT + LOW - 1, 2, 2);
    }

    private static BlockPos bin(int slot, int x, int z) {
        return new BlockPos(slot * SLOT + x, LOW, z);
    }

    /** 内部 3×3×3 的封闭石头盒子 (含地板与天花板); 只动自己这条槽位。 */
    private static void buildRoom(GameTestHelper helper, int slot) {
        int base = slot * SLOT;
        for (int x = LOW - 1; x <= HIGH + 1; x++) {
            for (int y = LOW - 1; y <= HIGH + 1; y++) {
                for (int z = LOW - 1; z <= HIGH + 1; z++) {
                    boolean shell = x == LOW - 1 || x == HIGH + 1
                            || y == LOW - 1 || y == HIGH + 1
                            || z == LOW - 1 || z == HIGH + 1;
                    BlockState state = shell ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
                    helper.setBlock(new BlockPos(base + x, y, z), state);
                }
            }
        }
    }
}
