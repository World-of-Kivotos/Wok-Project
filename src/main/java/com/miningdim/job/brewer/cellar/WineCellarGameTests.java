package com.miningdim.job.brewer.cellar;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.brewer.BrewerConstants;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * 酒窖箱陈酿结算纯逻辑 GameTest (酿酒师 阶段 4)。断言 {@link CellarSettle#settle} 的具体业务数值: 足量燃料下
 * 年份按现实天增长 + 扣对燃料 (小数债累加, 老酒耗更多); 断粮倒扣 vintage 至变质; 满月步年增量更高; 已变质瓶
 * 不再变化; 窖级共享燃料。并含【小数燃料债回归测试】: 模拟 BE 高频小步唤醒, 一天只该耗 ~16 颗而非上万。
 * 全为确定性数值断言 (删掉被测逻辑即挂), 不依赖世界 / 网络。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class WineCellarGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "brewer";
    private static final double EPS = 1e-9D;

    private static final long DAY = BrewerConstants.MILLIS_PER_VINTAGE_YEAR; // 1 现实天 = 1 年份。
    private static final int NO_MOON = 4;  // 非满月。
    private static final int FULL_MOON = 0;
    private static final int AMPLE_FUEL = 100_000;

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void ampleFuelAgesOneYearPerDayAndConsumesEscalatingFuel(GameTestHelper helper) {
        // 单瓶 vintage 0, 3 天, 足量燃料, 非满月: vintage -> 3.0。
        // 燃料 (小数债累加, 跨整数才扣): 第1天 16.0->扣16(债0); 第2天 +17.6(债17.6)->扣17(债0.6);
        //   第3天 +19.2(债19.8)->扣19(债0.8) => 共 52 (旧 per-step ceil 会是 54)。
        CellarSettle.Result r = CellarSettle.settle(
                List.of(new CellarSettle.BottleState(0.0D, false)), 3L * DAY, AMPLE_FUEL, NO_MOON, 0.0D);
        helper.assertTrue(r.bottles().size() == 1, "one bottle returned");
        CellarSettle.BottleState b = r.bottles().get(0);
        helper.assertTrue(Math.abs(b.vintage() - 3.0D) < EPS, "vintage = 3.0 after 3 days, got " + b.vintage());
        helper.assertTrue(!b.spoiled(), "not spoiled with ample fuel");
        helper.assertTrue(r.fuelConsumed() == 52, "fuel consumed = 16+17+19 = 52, got " + r.fuelConsumed());
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void olderWineConsumesMoreFuelPerDay(GameTestHelper helper) {
        // 整天足量燃料 (债恰为整数): 新酒 (vintage 0) 耗 16; 老酒 (vintage 10) 耗 16×(1+10/10)=32 (恰翻倍)。
        int freshCost = CellarSettle.settle(
                List.of(new CellarSettle.BottleState(0.0D, false)), DAY, AMPLE_FUEL, NO_MOON, 0.0D).fuelConsumed();
        int oldCost = CellarSettle.settle(
                List.of(new CellarSettle.BottleState(10.0D, false)), DAY, AMPLE_FUEL, NO_MOON, 0.0D).fuelConsumed();
        helper.assertTrue(freshCost == 16, "fresh wine 1 day fuel = 16, got " + freshCost);
        helper.assertTrue(oldCost == 32, "vintage-10 wine 1 day fuel = 32, got " + oldCost);
        helper.assertTrue(oldCost > freshCost, "older wine consumes more fuel");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void fullMoonStepAgesFaster(GameTestHelper helper) {
        // 满月 1 天: stepYears = 1.0 × (1+0.25) = 1.25; 非满月 1 天 = 1.0。
        CellarSettle.BottleState moon = CellarSettle.settle(
                List.of(new CellarSettle.BottleState(0.0D, false)), DAY, AMPLE_FUEL, FULL_MOON, 0.0D).bottles().get(0);
        CellarSettle.BottleState plain = CellarSettle.settle(
                List.of(new CellarSettle.BottleState(0.0D, false)), DAY, AMPLE_FUEL, NO_MOON, 0.0D).bottles().get(0);
        helper.assertTrue(Math.abs(moon.vintage() - 1.25D) < EPS, "full-moon day vintage = 1.25, got " + moon.vintage());
        helper.assertTrue(Math.abs(plain.vintage() - 1.0D) < EPS, "plain day vintage = 1.0, got " + plain.vintage());
        helper.assertTrue(moon.vintage() > plain.vintage(), "full moon ages faster");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void starvationDecaysVintageThenSpoils(GameTestHelper helper) {
        // 断粮 (fuel 0) 1 天: vintage 5 -> 5 - 3.0×1 = 2.0, 未变质, 零耗燃料。
        CellarSettle.Result oneDay = CellarSettle.settle(
                List.of(new CellarSettle.BottleState(5.0D, false)), DAY, 0, NO_MOON, 0.0D);
        CellarSettle.BottleState afterOne = oneDay.bottles().get(0);
        helper.assertTrue(Math.abs(afterOne.vintage() - 2.0D) < EPS, "starved 1 day: vintage 5 -> 2.0, got " + afterOne.vintage());
        helper.assertTrue(!afterOne.spoiled(), "not yet spoiled after 1 starved day");
        helper.assertTrue(oneDay.fuelConsumed() == 0, "no fuel consumed when starving");

        // 断粮 2 天: 第1天 5->2; 第2天 2 - 3 = -1 <= 0 -> 变质, vintage 0。
        CellarSettle.BottleState afterTwo = CellarSettle.settle(
                List.of(new CellarSettle.BottleState(5.0D, false)), 2L * DAY, 0, NO_MOON, 0.0D).bottles().get(0);
        helper.assertTrue(afterTwo.spoiled(), "starved 2 days -> spoiled");
        helper.assertTrue(Math.abs(afterTwo.vintage()) < EPS, "spoiled wine vintage clamped to 0, got " + afterTwo.vintage());
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void partialDayScalesAgingAndDecay(GameTestHelper helper) {
        // 半天足量燃料: vintage 0 -> 0.5。
        CellarSettle.BottleState aged = CellarSettle.settle(
                List.of(new CellarSettle.BottleState(0.0D, false)), DAY / 2L, AMPLE_FUEL, NO_MOON, 0.0D).bottles().get(0);
        helper.assertTrue(Math.abs(aged.vintage() - 0.5D) < EPS, "half day ages 0.5, got " + aged.vintage());
        // 半天断粮: vintage 5 -> 5 - 3.0×0.5 = 3.5。
        CellarSettle.BottleState decayed = CellarSettle.settle(
                List.of(new CellarSettle.BottleState(5.0D, false)), DAY / 2L, 0, NO_MOON, 0.0D).bottles().get(0);
        helper.assertTrue(Math.abs(decayed.vintage() - 3.5D) < EPS, "half day starve: 5 -> 3.5, got " + decayed.vintage());
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void spoiledBottleNeverChanges(GameTestHelper helper) {
        // 已变质瓶: 不增龄, 不耗燃料 (即便足量燃料 / 满月 / 长时间)。
        CellarSettle.Result r = CellarSettle.settle(
                List.of(new CellarSettle.BottleState(0.0D, true)), 10L * DAY, AMPLE_FUEL, FULL_MOON, 0.0D);
        CellarSettle.BottleState b = r.bottles().get(0);
        helper.assertTrue(b.spoiled(), "spoiled stays spoiled");
        helper.assertTrue(Math.abs(b.vintage()) < EPS, "spoiled vintage stays 0, got " + b.vintage());
        helper.assertTrue(r.fuelConsumed() == 0, "spoiled bottle burns no fuel, got " + r.fuelConsumed());
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void sharedFuelPoolAgesAllWhileFuelPresent(GameTestHelper helper) {
        // 窖级保鲜 (非逐瓶配给): 2 瓶 vintage 0, 1 天, 燃料 20 (本步应耗 32 > 20)。槽内本步有粮 -> 两瓶都增龄到 1.0;
        // 只扣得起 20 (其余 12 入小数债保留); 下一步槽见底才衰退。
        CellarSettle.Result r = CellarSettle.settle(
                List.of(new CellarSettle.BottleState(0.0D, false), new CellarSettle.BottleState(0.0D, false)),
                DAY, 20, NO_MOON, 0.0D);
        helper.assertTrue(r.bottles().size() == 2, "two bottles returned");
        helper.assertTrue(Math.abs(r.bottles().get(0).vintage() - 1.0D) < EPS && !r.bottles().get(0).spoiled(),
                "bottle 0 ages to 1.0, got " + r.bottles().get(0).vintage());
        helper.assertTrue(Math.abs(r.bottles().get(1).vintage() - 1.0D) < EPS && !r.bottles().get(1).spoiled(),
                "bottle 1 ages to 1.0, got " + r.bottles().get(1).vintage());
        helper.assertTrue(r.fuelConsumed() == 20, "burns all 20 available, got " + r.fuelConsumed());
        helper.assertTrue(Math.abs(r.fuelDebt() - 12.0D) < EPS, "12 fuel debt carried, got " + r.fuelDebt());

        // 续一步且槽已空 (fuel 0): 两瓶衰退 (窖见底即暂停陈酿 -> 倒扣)。
        CellarSettle.Result next = CellarSettle.settle(r.bottles(), DAY, 0, NO_MOON, r.fuelDebt());
        helper.assertTrue(next.bottles().get(0).vintage() < 1.0D, "bottle 0 decays once slot empty");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void fractionalFuelDebtRegression(GameTestHelper helper) {
        // 回归: 模拟酒窖箱真实高频唤醒 (每步远小于一天)。旧 per-step ceil 会几步烧一颗、一天上百上千; 小数债下
        // 一天应只耗 ~16 颗 (与整天单步一致)。这里 100 个 1/100 天的小步 = 1 天。
        long step = DAY / 100L;
        List<CellarSettle.BottleState> bottles = List.of(new CellarSettle.BottleState(0.0D, false));
        double debt = 0.0D;
        int fuelLeft = AMPLE_FUEL;
        int totalBurn = 0;
        for (int i = 0; i < 100; i++) {
            CellarSettle.Result r = CellarSettle.settle(bottles, step, fuelLeft, NO_MOON, debt);
            bottles = r.bottles();
            debt = r.fuelDebt();
            totalBurn += r.fuelConsumed();
            fuelLeft -= r.fuelConsumed();
        }
        // 一天 vintage ~1.0; 燃料 ~16 (绝非旧 bug 的上百)。
        helper.assertTrue(Math.abs(bottles.get(0).vintage() - 1.0D) < 1e-6D,
                "100 small steps over a day -> vintage ~1.0, got " + bottles.get(0).vintage());
        helper.assertTrue(totalBurn >= 15 && totalBurn <= 18,
                "fractional debt: ~16 fuel/day across many small steps, got " + totalBurn);
        helper.assertTrue(totalBurn < 30, "regression: not the old per-step-ceil blowup, got " + totalBurn);
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void zeroElapsedIsNoOp(GameTestHelper helper) {
        // 零 / 负挂钟差: 原样返回, 零耗燃料、债不变 (懒结算无推进时不动酒)。
        CellarSettle.Result zero = CellarSettle.settle(
                List.of(new CellarSettle.BottleState(2.0D, false)), 0L, AMPLE_FUEL, NO_MOON, 3.5D);
        helper.assertTrue(Math.abs(zero.bottles().get(0).vintage() - 2.0D) < EPS, "zero elapsed keeps vintage");
        helper.assertTrue(zero.fuelConsumed() == 0, "zero elapsed burns no fuel");
        helper.assertTrue(Math.abs(zero.fuelDebt() - 3.5D) < EPS, "zero elapsed keeps fuel debt unchanged");
        CellarSettle.Result neg = CellarSettle.settle(
                List.of(new CellarSettle.BottleState(2.0D, false)), -DAY, AMPLE_FUEL, NO_MOON, 0.0D);
        helper.assertTrue(Math.abs(neg.bottles().get(0).vintage() - 2.0D) < EPS, "negative elapsed keeps vintage");
        helper.assertTrue(neg.fuelConsumed() == 0, "negative elapsed burns no fuel");
        helper.succeed();
    }
}
