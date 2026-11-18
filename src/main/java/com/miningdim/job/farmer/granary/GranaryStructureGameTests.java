package com.miningdim.job.farmer.granary;

import com.miningdim.core.MiningConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class GranaryStructureGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "granary_structure";
    private static final int SLOT = 16;
    private static final int LOW = 1;
    private static final int HIGH = 3;   // 内部 3×3×3 = 27 格

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void leavesAreALeak(GameTestHelper helper) {
        int slot = 2;
        buildRoom(helper, slot);
        helper.setBlock(center(slot), Blocks.STONE);
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
        helper.setBlock(center(slot), Blocks.STONE);
        helper.setBlock(new BlockPos(slot * SLOT + 3, LOW, LOW), Blocks.WATER);

        helper.assertTrue(scan(helper, slot).failure() == GranaryStructureScanner.Failure.FLUID,
                "屋里进水不成立, 实际 " + scan(helper, slot).failure());
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void holeInTheWallLetsTheFloodOut(GameTestHelper helper) {
        int slot = 7;
        buildRoom(helper, slot);
        helper.setBlock(center(slot), Blocks.STONE);
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

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void centerBetweenTwoSealedRoomsIsRejected(GameTestHelper helper) {
        int slot = 11;
        buildRoom(helper, slot);
        for (int y = LOW; y <= HIGH; y++) {
            for (int z = LOW; z <= HIGH; z++) {
                helper.setBlock(new BlockPos(center(slot).getX(), y, z), Blocks.STONE);
            }
        }
        helper.assertTrue(scan(helper, slot).failure() == GranaryStructureScanner.Failure.CENTER_BETWEEN_ROOMS,
                "账台两侧的空气不能连通时必须拒绝验收");
        helper.succeed();
    }

    private static void verifyLeak(GameTestHelper helper, int slot, Block leak) {
        buildRoom(helper, slot);
        helper.setBlock(center(slot), Blocks.STONE);
        helper.setBlock(vent(slot), leak);
        helper.assertTrue(scan(helper, slot).failure() == GranaryStructureScanner.Failure.LEAK,
                leak.getName().getString() + " 算漏洞, 实际 " + scan(helper, slot).failure());
    }

    private static GranaryStructureScanner.ScanResult scan(GameTestHelper helper, int slot) {
        return GranaryStructureScanner.scan(helper.getLevel(), helper.absolutePos(center(slot)));
    }

    private static BlockPos center(int slot) {
        return new BlockPos(slot * SLOT + 2, 2, 2);
    }

    private static BlockPos vent(int slot) {
        return new BlockPos(slot * SLOT + LOW - 1, 2, 2);
    }

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
