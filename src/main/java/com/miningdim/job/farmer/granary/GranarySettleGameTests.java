package com.miningdim.job.farmer.granary;

import com.miningdim.core.MiningConstants;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class GranarySettleGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "granary_settle";
    private static final long ENTRY = 1_000_000L;   // 随意一个入仓小时序号

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void storedTwentyOneHoursCreditsNothing(GameTestHelper helper) {
        GranarySettle.Result result = GranarySettle.settle(ENTRY, 0, ENTRY + 21L, 0);
        helper.assertTrue(result.grantedHours() == 0, "不满 24 小时取出, 这次停表记 0");
        helper.assertTrue(result.creditedHours() == 0, "已计停表还是 0");
        helper.assertTrue(!result.credited(), "这一趟没记上东西");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void exactlyOneDayCreditsTwentyThreeHours(GameTestHelper helper) {
        // 存满 24 小时才起算, 入仓那一小时不计 —— 所以正好 24 小时记 23。
        helper.assertTrue(GranarySettle.settle(ENTRY, 0, ENTRY + 24L, 0).grantedHours() == 23,
                "正好存满一天记 23 小时");
        helper.assertTrue(GranarySettle.settle(ENTRY, 0, ENTRY + 25L, 0).grantedHours() == 24,
                "存 25 小时记 24");
        helper.assertTrue(GranarySettle.settle(ENTRY, 0, ENTRY + 168L, 0).grantedHours() == 167,
                "存 168 小时记 167");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void twentyOneDaysIsTheHardCap(GameTestHelper helper) {
        GranarySettle.Result result = GranarySettle.settle(ENTRY, 0, ENTRY + 600L, 0);
        helper.assertTrue(result.grantedHours() == 504, "存 600 小时封顶 504 (21 天)");
        helper.assertTrue(result.capReached(), "到顶要报出来");
        helper.assertTrue(GranarySettle.settle(ENTRY + 600L, 0, ENTRY + 900L, 0).grantedHours() == 299,
                "另起一趟 (换一个入仓时刻) 又是新的一段, 但物品自己的总额度仍然是 504");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void midwaySettlementKeepsTheOriginalEntryHour(GameTestHelper helper) {
        // 中途打开账台看一眼: 先结算到现在, E 不动。
        GranarySettle.Result first = GranarySettle.settle(ENTRY, 0, ENTRY + 100L, 0);
        helper.assertTrue(first.creditedHours() == 99, "第一趟记 99");
        GranarySettle.Result second = GranarySettle.settle(ENTRY, first.creditedHours(), ENTRY + 200L, 99);
        helper.assertTrue(second.creditedHours() == 199, "接着记到 199");
        // 与「中间一次都不看」的结果必须一致 —— 这就是「中途打开不重置」的判据。
        GranarySettle.Result straight = GranarySettle.settle(ENTRY, 0, ENTRY + 200L, 0);
        helper.assertTrue(second.creditedHours() == straight.creditedHours(),
                "分两次结算与一次结算结果必须相同, 实际 " + second.creditedHours() + " vs " + straight.creditedHours());
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void itemCapAndEdgeCasesAreRespected(GameTestHelper helper) {
        // 物品已经有 500 小时额度: 这一趟最多再记 4 小时。
        GranarySettle.Result nearlyFull = GranarySettle.settle(ENTRY, 0, ENTRY + 600L, 500);
        helper.assertTrue(nearlyFull.grantedHours() == 4, "只按剩下的 4 小时额度记, 实际 " + nearlyFull.grantedHours());
        helper.assertTrue(nearlyFull.capReached(), "记满要报出来");

        // 额度用光: 什么也不记。
        GranarySettle.Result exhausted = GranarySettle.settle(ENTRY, 0, ENTRY + 600L, 504);
        helper.assertTrue(exhausted.grantedHours() == 0 && exhausted.capReached(),
                "额度用完就不该再记 (照常腐烂)");

        // 截止时刻早于入仓时刻 (时钟回拨/异常数据): 不记, 也不报错。
        helper.assertTrue(GranarySettle.settle(ENTRY, 0, ENTRY - 10L, 0).grantedHours() == 0,
                "截止时刻早于入仓时刻不记");
        helper.succeed();
    }
}
