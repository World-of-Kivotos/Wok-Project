package com.miningdim.job.brewer.cellar;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.brewer.BrewerConfig;
import com.miningdim.job.brewer.BrewerConstants;
import com.miningdim.job.brewer.BrewerItems;
import com.miningdim.job.brewer.WineNbt;
import com.miningdim.job.brewer.WineQuality;
import com.miningdim.job.brewer.WineType;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

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
        BrewerConfig.ensureLoadedForTest();
        // 单瓶 vintage 0, 3 天, 足量燃料, 非满月: vintage -> 3.0。
        // 燃料 (每年耗 = 16 + 5×V², 整天债恰为整数): 第1天 v0->16; 第2天 v1->16+5=21; 第3天 v2->16+20=36 => 共 73。
        CellarSettle.Result r = CellarSettle.settle(
                List.of(new CellarSettle.BottleState(0.0D, false)), 3L * DAY, AMPLE_FUEL, NO_MOON, 0.0D);
        helper.assertTrue(r.bottles().size() == 1, "one bottle returned");
        CellarSettle.BottleState b = r.bottles().get(0);
        helper.assertTrue(Math.abs(b.vintage() - 3.0D) < EPS, "vintage = 3.0 after 3 days, got " + b.vintage());
        helper.assertTrue(!b.spoiled(), "not spoiled with ample fuel");
        helper.assertTrue(r.fuelConsumed() == 73, "fuel consumed = 16+21+36 = 73, got " + r.fuelConsumed());
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void olderWineConsumesMoreFuelPerDay(GameTestHelper helper) {
        BrewerConfig.ensureLoadedForTest();
        // 整天足量燃料: 新酒 (vintage 0) 耗 16; 老酒 (vintage 10) 耗 16+5×10² = 516 (二次递增, 老酒烧钱凶)。
        int freshCost = CellarSettle.settle(
                List.of(new CellarSettle.BottleState(0.0D, false)), DAY, AMPLE_FUEL, NO_MOON, 0.0D).fuelConsumed();
        int oldCost = CellarSettle.settle(
                List.of(new CellarSettle.BottleState(10.0D, false)), DAY, AMPLE_FUEL, NO_MOON, 0.0D).fuelConsumed();
        helper.assertTrue(freshCost == 16, "fresh wine 1 day fuel = 16, got " + freshCost);
        helper.assertTrue(oldCost == 516, "vintage-10 wine 1 day fuel = 16+5*100 = 516, got " + oldCost);
        helper.assertTrue(oldCost > freshCost, "older wine consumes more fuel");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void fullMoonStepAgesFaster(GameTestHelper helper) {
        BrewerConfig.ensureLoadedForTest();
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
        BrewerConfig.ensureLoadedForTest();
        // 断粮衰退极快 (200/天)。短时 0.01 天 (DAY/100): vintage 5 -> 5 - 200×0.01 = 3.0, 未变质, 零耗燃料。
        CellarSettle.Result brief = CellarSettle.settle(
                List.of(new CellarSettle.BottleState(5.0D, false)), DAY / 100L, 0, NO_MOON, 0.0D);
        CellarSettle.BottleState afterBrief = brief.bottles().get(0);
        helper.assertTrue(Math.abs(afterBrief.vintage() - 3.0D) < EPS, "starved 0.01 day: vintage 5 -> 3.0, got " + afterBrief.vintage());
        helper.assertTrue(!afterBrief.spoiled(), "not yet spoiled after brief starve");
        helper.assertTrue(brief.fuelConsumed() == 0, "no fuel consumed when starving");

        // 断粮 1 整天: 5 - 200 = 负 <= 0 -> 秒变质, vintage 0 (v25 满酒断粮约 3 小时即归零)。
        CellarSettle.BottleState afterDay = CellarSettle.settle(
                List.of(new CellarSettle.BottleState(5.0D, false)), DAY, 0, NO_MOON, 0.0D).bottles().get(0);
        helper.assertTrue(afterDay.spoiled(), "starved a full day -> spoiled (fast decay)");
        helper.assertTrue(Math.abs(afterDay.vintage()) < EPS, "spoiled wine vintage clamped to 0, got " + afterDay.vintage());
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void partialDayScalesAgingAndDecay(GameTestHelper helper) {
        BrewerConfig.ensureLoadedForTest();
        // 半天足量燃料: vintage 0 -> 0.5。
        CellarSettle.BottleState aged = CellarSettle.settle(
                List.of(new CellarSettle.BottleState(0.0D, false)), DAY / 2L, AMPLE_FUEL, NO_MOON, 0.0D).bottles().get(0);
        helper.assertTrue(Math.abs(aged.vintage() - 0.5D) < EPS, "half day ages 0.5, got " + aged.vintage());
        // 短断粮 (DAY/200 = 0.005 天): vintage 5 -> 5 - 200×0.005 = 4.0 (衰退快, 短时仍有量, 按比例)。
        CellarSettle.BottleState decayed = CellarSettle.settle(
                List.of(new CellarSettle.BottleState(5.0D, false)), DAY / 200L, 0, NO_MOON, 0.0D).bottles().get(0);
        helper.assertTrue(Math.abs(decayed.vintage() - 4.0D) < EPS, "brief starve: 5 -> 4.0, got " + decayed.vintage());
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void spoiledBottleNeverChanges(GameTestHelper helper) {
        BrewerConfig.ensureLoadedForTest();
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
    public static void fuelShortfallTruncatesAgingThenDecays(GameTestHelper helper) {
        BrewerConfig.ensureLoadedForTest();
        // F027 修复后的窖级保鲜 (非逐瓶配给): 2 瓶 vintage 0, 1 天, 燃料 20 (本步应耗 32 > 20)。预算只够
        // agedFraction = 20/32 = 0.625: 两瓶先增龄到 0.625, 剩余 0.375 天的时间比例按 200/天 衰退
        // (=75 年), 0.625-75 << 0 直接归零变质。燃料债不再无界累加, 扣满 20 后归零 (不欠账)。
        CellarSettle.Result r = CellarSettle.settle(
                List.of(new CellarSettle.BottleState(0.0D, false), new CellarSettle.BottleState(0.0D, false)),
                DAY, 20, NO_MOON, 0.0D);
        helper.assertTrue(r.bottles().size() == 2, "two bottles returned");
        helper.assertTrue(r.bottles().get(0).spoiled() && Math.abs(r.bottles().get(0).vintage()) < EPS,
                "bottle 0 spoiled at vintage 0 after shortfall decay, got " + r.bottles().get(0).vintage());
        helper.assertTrue(r.bottles().get(1).spoiled() && Math.abs(r.bottles().get(1).vintage()) < EPS,
                "bottle 1 spoiled at vintage 0 after shortfall decay, got " + r.bottles().get(1).vintage());
        helper.assertTrue(r.fuelConsumed() == 20, "burns all 20 available, got " + r.fuelConsumed());
        helper.assertTrue(Math.abs(r.fuelDebt() - 0.0D) < EPS, "no unbounded debt carried (F027), got " + r.fuelDebt());
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void fractionalFuelDebtRegression(GameTestHelper helper) {
        BrewerConfig.ensureLoadedForTest();
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
        BrewerConfig.ensureLoadedForTest();
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

    // ============================================================
    // F027: 燃料债截断补测 (债有界 + 离线旁路已堵 + 旧档债迁移)
    // ============================================================
    // 下面两测的 fuelAvailable=64/1 是显式传给 CellarSettle.settle 的参数, 验证的是"预算不足时截断增龄比例
    // 而不是无界欠账"这条算法性质本身, 与酒窖箱燃料槽的实际物理容量 (二段修复后为 BrewerConstants
    // .FUEL_SLOT_CAPACITY=6192, 见 fuelSlotCapacityCoversFullDayAtMainlineThreshold) 是两回事 —— 早期
    // 只囤了几十颗干小麦、或槽内燃料被连续多日结算耗尽剩个位数, 仍是这条算法路径会被走到的真实场景。

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void fuelDebtNeverCarriesAnUnpayableBalance(GameTestHelper helper) {
        BrewerConfig.ensureLoadedForTest();
        // 12 瓶 vintage 10, 1 天, 燃料仅 64 (每瓶每年耗 16+5×10²=516, 合计 6192 远超 64 的支付能力)。
        // 预算全部投入本步 (budget=64<fullDemand), agedFraction=64/6192, debt 精确加满到预算上限即被扣光,
        // 不再像旧实现那样把 6192-64=6128 的差额结转成永久欠款。
        List<CellarSettle.BottleState> tenYearBottles = java.util.Collections.nCopies(12,
                new CellarSettle.BottleState(10.0D, false));
        CellarSettle.Result r = CellarSettle.settle(tenYearBottles, DAY, 64, NO_MOON, 0.0D);
        helper.assertTrue(r.fuelConsumed() == 64, "burns all 64 available, got " + r.fuelConsumed());
        helper.assertTrue(r.fuelDebt() < 1.0D, "debt stays bounded below 1.0 (F027 invariant), got " + r.fuelDebt());
        helper.assertTrue(Math.abs(r.fuelDebt() - 0.0D) < EPS, "debt fully cleared this step, got " + r.fuelDebt());

        // 把结余债喂进第二次结算: 旧债不再把新补的燃料一口吃光, 第二次仍能烧满 64 (债不再累积成不可偿还的欠款)。
        CellarSettle.Result r2 = CellarSettle.settle(tenYearBottles, DAY, 64, NO_MOON, r.fuelDebt());
        helper.assertTrue(r2.fuelConsumed() == 64,
                "second settle still burns the full 64, prior debt did not eat the top-up, got " + r2.fuelConsumed());
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void dailyOneWheatTopUpNoLongerBuysAFullDayOfAging(GameTestHelper helper) {
        BrewerConfig.ensureLoadedForTest();
        // 回归 "每天上线插 1 颗干小麦再离开" 的旁路: 12 瓶 vintage 0, 1 天, 燃料仅 1 (应耗 12×16=192)。
        // 修复前 fuelLeft>0 就整步满额增龄 (骗过门控买满整天陈酿); 修复后按预算 1/192 截断, 未覆盖部分照衰退处理。
        List<CellarSettle.BottleState> bottles = java.util.Collections.nCopies(12,
                new CellarSettle.BottleState(0.0D, false));
        CellarSettle.Result r = CellarSettle.settle(bottles, DAY, 1, NO_MOON, 0.0D);
        helper.assertTrue(r.fuelConsumed() == 1, "only the affordable 1 fuel is burned, got " + r.fuelConsumed());
        helper.assertTrue(Math.abs(r.fuelDebt() - 0.0D) < EPS, "no residual debt after the single-wheat step, got " + r.fuelDebt());
        for (int i = 0; i < 12; i++) {
            CellarSettle.BottleState b = r.bottles().get(i);
            helper.assertTrue(b.spoiled() && Math.abs(b.vintage()) < EPS,
                    "bottle " + i + " spoiled at vintage 0 (daily-topup bypass closed), got vintage="
                            + b.vintage() + " spoiled=" + b.spoiled());
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void fuelSlotCapacityCoversFullDayAtMainlineThreshold(GameTestHelper helper) {
        BrewerConfig.ensureLoadedForTest();
        // F027 二段修复的存在性证明: 满槽 (FUEL_SLOT_CAPACITY) 对 12 瓶 vintage 10 (闪耀主线可达的最低门槛,
        // VINTAGE_LAYER_T1) 一个结算步 (1 天) 的满额应耗 12×(16+5×10²)=6192 应恰好覆盖: agedFraction=1,
        // 零衰退、燃料烧满、债清零。若 BrewerConfig 的费率/BrewerConstants.VINTAGE_LAYER_T1 改了默认值导致
        // 本测挂掉, 说明 FUEL_SLOT_CAPACITY 需要跟着重新推导 (见该常量 javadoc)。
        List<CellarSettle.BottleState> tenYearBottles = java.util.Collections.nCopies(12,
                new CellarSettle.BottleState(BrewerConstants.VINTAGE_LAYER_T1, false));
        CellarSettle.Result r = CellarSettle.settle(tenYearBottles, DAY, BrewerConstants.FUEL_SLOT_CAPACITY, NO_MOON, 0.0D);
        helper.assertTrue(r.fuelConsumed() == BrewerConstants.FUEL_SLOT_CAPACITY,
                "a full fuel slot pays the entire day's demand at the mainline threshold, got " + r.fuelConsumed());
        helper.assertTrue(Math.abs(r.fuelDebt() - 0.0D) < EPS, "debt fully settled, no leftover, got " + r.fuelDebt());
        for (int i = 0; i < r.bottles().size(); i++) {
            CellarSettle.BottleState b = r.bottles().get(i);
            helper.assertTrue(!b.spoiled(), "bottle " + i + " must not spoil when the fuel slot covers the full day's demand");
            helper.assertTrue(Math.abs(b.vintage() - (BrewerConstants.VINTAGE_LAYER_T1 + 1.0D)) < EPS,
                    "bottle " + i + " ages the full day with zero decay, got " + b.vintage());
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void fuelSlotAcceptsMainlineCapacityInASingleStack(GameTestHelper helper) {
        BrewerConfig.ensureLoadedForTest();
        BlockPos rel = new BlockPos(1, 1, 1);
        helper.setBlock(rel, WineCellarRegistry.WINE_CELLAR.get().defaultBlockState());
        WineCellarBlockEntity be = requireCellar(helper, rel);

        // F027 二段修复: 燃料槽真实能一次性吃下 FUEL_SLOT_CAPACITY 颗干小麦, 而不是被 ItemStackHandler
        // 默认 64 上限或 DRIED_WHEAT 物品自身默认 64 stacksTo 卡住 (两者缺一都会导致 insertItem 在 64 截断)。
        ItemStack overfill = new ItemStack(BrewerItems.DRIED_WHEAT.get(), BrewerConstants.FUEL_SLOT_CAPACITY + 500);
        ItemStack remainder = be.inventory().insertItem(WineCellarBlockEntity.FUEL_SLOT, overfill, false);
        helper.assertTrue(remainder.getCount() == 500,
                "insert spills back only the 500 over capacity, got " + remainder.getCount());
        int held = be.inventory().getStackInSlot(WineCellarBlockEntity.FUEL_SLOT).getCount();
        helper.assertTrue(held == BrewerConstants.FUEL_SLOT_CAPACITY,
                "fuel slot holds exactly FUEL_SLOT_CAPACITY after an overfill insert, got " + held);
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void legacyOutOfInvariantFuelDebtIsDiscardedOnLoad(GameTestHelper helper) {
        BrewerConfig.ensureLoadedForTest();
        BlockPos rel = new BlockPos(1, 1, 1);
        helper.setBlock(rel, WineCellarRegistry.WINE_CELLAR.get().defaultBlockState());
        WineCellarBlockEntity be = requireCellar(helper, rel);

        // 旧档场景: 燃料槽 64 颗干小麦 + 1 瓶 vintage 0 的酒。
        be.inventory().setStackInSlot(WineCellarBlockEntity.FUEL_SLOT,
                new ItemStack(BrewerItems.DRIED_WHEAT.get(), 64));
        ItemStack bottle = new ItemStack(BrewerItems.itemFor(WineType.VODKA));
        WineNbt.stamp(bottle, WineQuality.LOW, UUID.randomUUID());
        be.inventory().setStackInSlot(0, bottle);

        // 真实 saveAdditional 拿到合法 Inventory NBT, 再覆写 FuelDebt 模拟 F027 修复前无界累加出的旧档欠账
        // (>=1, 违反 [0,1) 不变式)。
        CompoundTag tag = new CompoundTag();
        be.saveAdditional(tag);
        tag.putLong("LastSettleEpochMillis", System.currentTimeMillis() - DAY);
        tag.putDouble("FuelDebt", 2000.0D);
        be.load(tag);

        // 迁移: load() 侧发现旧债越界即丢弃归零 (不强行让玩家偿还本不该存在的欠款), 本次结算只按真实应耗
        // (单瓶 vintage 0 每年耗 16) 扣款, 不被那笔 2000 的旧债一口吃光。
        be.settleElapsed(DAY, NO_MOON);

        int fuelLeft = be.inventory().getStackInSlot(WineCellarBlockEntity.FUEL_SLOT).getCount();
        helper.assertTrue(fuelLeft == 48,
                "legacy debt discarded: fuel slot only loses the real 16 owed this step (64-16=48), got " + fuelLeft);
        ItemStack settled = be.inventory().getStackInSlot(0);
        helper.assertTrue(Math.abs(WineNbt.readVintage(settled) - 1.0D) < EPS,
                "wine ages the full 1.0 year once the phantom legacy debt is wiped, got " + WineNbt.readVintage(settled));
        helper.assertTrue(!WineNbt.isSpoiled(settled),
                "wine must not spoil: the 2000 legacy debt would otherwise have eaten all 64 fuel");
        helper.succeed();
    }

    private static WineCellarBlockEntity requireCellar(GameTestHelper helper, BlockPos rel) {
        if (helper.getLevel().getBlockEntity(helper.absolutePos(rel)) instanceof WineCellarBlockEntity be) {
            return be;
        }
        throw new IllegalStateException("wine cellar block entity missing");
    }
}
