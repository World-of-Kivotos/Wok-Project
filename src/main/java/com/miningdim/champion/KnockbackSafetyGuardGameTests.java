package com.miningdim.champion;

import com.miningdim.champion.integration.KnockbackSafetyGuard;
import com.miningdim.core.MiningConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * {@link KnockbackSafetyGuard#probeOf} 世界适配层 GameTest (ChampionStarAffix spec 9.3 探针语义)。
 *
 * <p>纯落点数学 (扫描深度 / 边缘 margin / clamp 步进) 已由 {@link SafeLandingRules} 的纯逻辑测试钉死, 本类【不】
 * 重复。此处只钉死【本部件独有的世界读适配】—— 即 probeOf 对真实方块的 isHazard/isFooting/minY 判定: 在 1x1x1
 * 的 empty 模板单格上逐类摆放岩浆/岩浆块/火/石/空气 (灵魂火因 canSurvive 放置约束走状态级断言), 断言探针精确布尔值 (删任一 hazard 分支或 footing 的
 * "且非 hazard"约束必挂)。evaluateLanding/clampDisplacement 的结果映射是 probeOf + SafeLandingRules 的直接组合,
 * 落点几何需多格纵向立柱 (超出 1x1x1 模板可控范围), 交由真服验收。
 *
 * <p>template = "empty" (1x1x1), batch = "knockback_safety_guard"。单格坐标沿用包内既有约定 relative (0,1,0)
 * (见 EngineerGameTests: empty 模板上 setBlock 该格并经 absolutePos 读取)。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class KnockbackSafetyGuardGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "knockback_safety_guard";

    /** 探针测试用的单格相对坐标 (与 EngineerGameTests 一致, empty 模板上可写可读)。 */
    private static final BlockPos CELL_REL = new BlockPos(0, 1, 0);

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void lavaSourceIsHazardAndNotFooting(GameTestHelper helper) {
        // 岩浆源: FluidState 属 FluidTags.LAVA -> hazard; 流体面不 sturdy 且 hazard 优先 -> 非落脚。
        ServerLevel level = helper.getLevel();
        helper.setBlock(CELL_REL, Blocks.LAVA);
        BlockPos abs = helper.absolutePos(CELL_REL);
        SafeLandingRules.ColumnProbe probe = KnockbackSafetyGuard.probeOf(level);
        helper.assertTrue(probe.isHazard(abs.getX(), abs.getY(), abs.getZ()), "岩浆源 isHazard = true");
        helper.assertTrue(!probe.isFooting(abs.getX(), abs.getY(), abs.getZ()), "岩浆源 isFooting = false");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void magmaBlockIsHazardOverridingSturdyFooting(GameTestHelper helper) {
        // 岩浆块是实心可站立方块 (isFaceSturdy UP = true), 若无 hazard 优先约束 isFooting 会误判 true;
        // 断言 isFooting = false 专钉 probeOf 里"且非 hazard"这条 (删该判断此断言必挂)。
        ServerLevel level = helper.getLevel();
        helper.setBlock(CELL_REL, Blocks.MAGMA_BLOCK);
        BlockPos abs = helper.absolutePos(CELL_REL);
        SafeLandingRules.ColumnProbe probe = KnockbackSafetyGuard.probeOf(level);
        helper.assertTrue(probe.isHazard(abs.getX(), abs.getY(), abs.getZ()), "岩浆块 isHazard = true");
        helper.assertTrue(!probe.isFooting(abs.getX(), abs.getY(), abs.getZ()),
                "岩浆块虽 sturdy 也非落脚 (hazard 优先)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void fireIsHazardAndNotFooting(GameTestHelper helper) {
        // 火: is(Blocks.FIRE) 与 is(BlockTags.FIRE) 双命中 hazard; 火非固体 -> 非落脚。
        ServerLevel level = helper.getLevel();
        helper.setBlock(CELL_REL, Blocks.FIRE);
        BlockPos abs = helper.absolutePos(CELL_REL);
        SafeLandingRules.ColumnProbe probe = KnockbackSafetyGuard.probeOf(level);
        helper.assertTrue(probe.isHazard(abs.getX(), abs.getY(), abs.getZ()), "火 isHazard = true");
        helper.assertTrue(!probe.isFooting(abs.getX(), abs.getY(), abs.getZ()), "火 isFooting = false");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void soulFireIsHazard(GameTestHelper helper) {
        // 灵魂火: is(Blocks.SOUL_FIRE) 命中 hazard (专钉 SOUL_FIRE 分支)。不走世界放置 —— 灵魂火 canSurvive
        // 硬性要求下方灵魂沙/灵魂土, 1x1 模板 setBlock 放置即弹掉成空气 (真服首验踩坑), 故直接对方块态断言。
        helper.assertTrue(KnockbackSafetyGuard.isHazardState(Blocks.SOUL_FIRE.defaultBlockState()),
                "灵魂火 isHazardState = true");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void solidStoneIsFootingNotHazard(GameTestHelper helper) {
        // 普通实心石: 非 hazard 且顶面 sturdy -> 唯一应判 isFooting = true 的样本 (正向锚点)。
        ServerLevel level = helper.getLevel();
        helper.setBlock(CELL_REL, Blocks.STONE);
        BlockPos abs = helper.absolutePos(CELL_REL);
        SafeLandingRules.ColumnProbe probe = KnockbackSafetyGuard.probeOf(level);
        helper.assertTrue(!probe.isHazard(abs.getX(), abs.getY(), abs.getZ()), "石 isHazard = false");
        helper.assertTrue(probe.isFooting(abs.getX(), abs.getY(), abs.getZ()), "石 isFooting = true");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void airIsNeitherHazardNorFooting(GameTestHelper helper) {
        // 空气: 既非 hazard 也无承载面 -> 两者皆 false (负向锚点, 防 probeOf 把空气误判为任一态)。
        ServerLevel level = helper.getLevel();
        helper.setBlock(CELL_REL, Blocks.AIR);
        BlockPos abs = helper.absolutePos(CELL_REL);
        SafeLandingRules.ColumnProbe probe = KnockbackSafetyGuard.probeOf(level);
        helper.assertTrue(!probe.isHazard(abs.getX(), abs.getY(), abs.getZ()), "空气 isHazard = false");
        helper.assertTrue(!probe.isFooting(abs.getX(), abs.getY(), abs.getZ()), "空气 isFooting = false");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void minYDelegatesToLevelBuildHeight(GameTestHelper helper) {
        // minY 必须实读维度下界 (虚空扫描下界), 删委托改成硬编码常量则与 level.getMinBuildHeight() 不符必挂。
        ServerLevel level = helper.getLevel();
        SafeLandingRules.ColumnProbe probe = KnockbackSafetyGuard.probeOf(level);
        helper.assertTrue(probe.minY() == level.getMinBuildHeight(),
                "probe.minY 委托 level.getMinBuildHeight = " + level.getMinBuildHeight());
        helper.succeed();
    }
}
