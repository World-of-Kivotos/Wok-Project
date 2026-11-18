package com.miningdim.job.farmer.granary;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.farmer.granary.GranaryStructureScanner.Failure;
import com.miningdim.job.farmer.granary.GranaryStructureScanner.ScanResult;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class GranaryDamageGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "granary_damage";
    private static final long DAMAGED_AT = 1_000_000L;
    private static final long DAY = 24L;

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void graceIsTheLaterOfSevenDaysAndThreeDaysAfterLogin(GameTestHelper helper) {
        // 没上线过: 出事起 7 天。
        helper.assertTrue(GranaryGrace.deadline(DAMAGED_AT, 0L) == DAMAGED_AT + 7L * DAY,
                "出事后还没上线, 就是 7 天");
        // 出事当天上线: 7 天更晚。
        helper.assertTrue(GranaryGrace.deadline(DAMAGED_AT, DAMAGED_AT) == DAMAGED_AT + 7L * DAY,
                "当天上线还是 7 天");
        // 第 6 天才上线: 上线起 3 天更晚 (还剩 3 天, 而不是 1 天)。
        helper.assertTrue(GranaryGrace.deadline(DAMAGED_AT, DAMAGED_AT + 6L * DAY) == DAMAGED_AT + 9L * DAY,
                "第 6 天才上线, 从上线那天起再给 3 天");
        // 一直不上线: 28 天封顶。
        helper.assertTrue(GranaryGrace.deadline(DAMAGED_AT, DAMAGED_AT + 100L * DAY) == DAMAGED_AT + 28L * DAY,
                "上限 28 天, 不上线也不能无限拖");

        helper.assertTrue(GranaryGrace.inGrace(DAMAGED_AT + 167L, DAMAGED_AT, 0L), "第 167 小时还在宽限内");
        helper.assertTrue(!GranaryGrace.inGrace(DAMAGED_AT + 168L, DAMAGED_AT, 0L),
                "正好到截止那一刻就不再宽限 (停表从这里停)");
        helper.assertTrue(GranaryGrace.remainingHours(DAMAGED_AT, DAMAGED_AT, 0L) == 7L * DAY,
                "出事当天还剩 7 天");
        helper.assertTrue(GranaryGrace.remainingHours(DAMAGED_AT + 20L * DAY, DAMAGED_AT, 0L) == 0L,
                "过期之后剩余一律算 0");
        helper.assertTrue(GranaryGrace.remainingHours(DAMAGED_AT, DAMAGED_AT, DAMAGED_AT + 100L * DAY)
                == 28L * DAY, "就算一直不上线, 也只剩 28 天封顶那么多");

        boolean threw = false;
        try {
            GranaryGrace.deadline(0L, 0L);
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        helper.assertTrue(threw, "没坏过就没有宽限可算 (出事时刻必须 > 0)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void structuralDamageOnlyLooksAtBaseRequirements(GameTestHelper helper) {
        // 漏风: 整座损坏。
        helper.assertTrue(GranaryDamage.structural(GranaryTier.SHED,
                scan(45, 5, 0, 1, 2, 0, Failure.LEAK)), "漏风判整座损坏");
        // 室内格数掉到本档基础要求以下 (粮棚 18 格): 整座损坏。
        helper.assertTrue(GranaryDamage.structural(GranaryTier.SHED, scan(12, 1, 0, 1, 1, 0)),
                "室内 12 格不够粮棚的 18 格基础要求");
        helper.assertTrue(!GranaryDamage.structural(GranaryTier.SHED, scan(20, 1, 0, 1, 1, 0)),
                "20 格够粮棚的基础要求 (哪怕放不下更多囤)");
        // 3 个囤要 27 格: 20 格只让「排不下的那几个」停用, 不算整座损坏 —— 这条界线最容易写错。
        helper.assertTrue(GranaryEnablement.decide(3, 20 / GranaryStructureScanner.CELLS_PER_BIN).unsupported() == 1,
                "20 格撑得住 2 个囤, 第 3 个排不上 (停用而不是整座损坏)");
        // 同一间屋, 冷库的基础要求是 36 格。
        helper.assertTrue(GranaryDamage.structural(GranaryTier.COLD, scan(27, 1, 0, 1, 1, 2)),
                "27 格对粮棚够、对冷库不够");
        helper.assertTrue(!GranaryDamage.structural(GranaryTier.COLD, scan(36, 1, 0, 1, 1, 2)),
                "36 格够冷库的基础要求");
        helper.assertTrue(GranaryDamage.describe(GranaryTier.SHED, scan(12, 1, 0, 1, 1, 0)).contains("18"),
                "提示要写清差多少格: " + GranaryDamage.describe(GranaryTier.SHED, scan(12, 1, 0, 1, 1, 0)));
        helper.succeed();
    }

    private static ScanResult scan(int interiorCells, int bins, int binsNotEnclosed, int centers,
                                  int vents, int coldSources) {
        return scan(interiorCells, bins, binsNotEnclosed, centers, vents, coldSources, Failure.NONE);
    }

    private static ScanResult scan(int interiorCells, int bins, int binsNotEnclosed, int centers,
                                  int vents, int coldSources, Failure failure) {
        return new ScanResult(interiorCells, bins, binsNotEnclosed, centers, vents, coldSources, failure);
    }
}
