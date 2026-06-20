package com.miningdim.job.brewer;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.JobId;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * 酿酒师地基纯逻辑 GameTest: 品质系数曲线 + 年份时钟换算 + 满月加成 + NBT 盖章往返 + 强度公式 + JobId 接入。
 * 这些断言均为具体业务结果 (删掉被测逻辑即挂), 不依赖世界/网络, 故放地基提交即可全绿。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class BrewerFoundationGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "brewer";
    private static final double EPS = 1e-9D;

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void qualityCoefficientsAreSpecCurve(GameTestHelper helper) {
        helper.assertTrue(Math.abs(WineQuality.LOW.coefficient() - 1.0D) < EPS, "low coeff = 1.0");
        helper.assertTrue(Math.abs(WineQuality.MID.coefficient() - 1.5D) < EPS, "mid coeff = 1.5");
        helper.assertTrue(Math.abs(WineQuality.HIGH.coefficient() - 2.0D) < EPS, "high coeff = 2.0");
        helper.assertTrue(Math.abs(WineQuality.SUPERB.coefficient() - 3.0D) < EPS, "superb coeff = 3.0");
        helper.assertTrue(Math.abs(WineQuality.BRILLIANT.coefficient() - 5.0D) < EPS, "brilliant coeff = 5.0");
        helper.assertTrue(WineQuality.BRILLIANT.isBrilliant(), "BRILLIANT.isBrilliant()");
        helper.assertTrue(!WineQuality.SUPERB.isBrilliant(), "SUPERB not brilliant");
        helper.assertTrue(WineQuality.fromId("superb") == WineQuality.SUPERB, "fromId(superb) resolves SUPERB");
        helper.assertTrue(WineQuality.fromId("nope") == null, "fromId unknown -> null");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void vintageClockConvertsRealMillisToYears(GameTestHelper helper) {
        // 现实挂钟: 默认 86_400_000 毫秒 (现实一天) = 1 年份。
        helper.assertTrue(Math.abs(VintageClock.vintageYearsFromMillis(86_400_000L) - 1.0D) < EPS, "1 real day = 1 year");
        helper.assertTrue(Math.abs(VintageClock.vintageYearsFromMillis(172_800_000L) - 2.0D) < EPS, "2 real days = 2 years");
        helper.assertTrue(Math.abs(VintageClock.vintageYearsFromMillis(43_200_000L) - 0.5D) < EPS, "12 real hours = 0.5 year");
        helper.assertTrue(Math.abs(VintageClock.vintageYearsFromMillis(0L)) < EPS, "0 millis = 0 year");
        helper.assertTrue(Math.abs(VintageClock.vintageYearsFromMillis(-5L)) < EPS, "negative millis = 0 year");
        helper.assertTrue(VintageClock.millisForYears(1.0D) == 86_400_000L, "1 year = 1 real day millis");
        helper.assertTrue(VintageClock.millisForYears(0.0D) == 0L, "0 year = 0 millis");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void fullMoonBonusOnlyOnPhaseZero(GameTestHelper helper) {
        helper.assertTrue(VintageClock.isFullMoon(0), "phase 0 is full moon");
        helper.assertTrue(!VintageClock.isFullMoon(4), "phase 4 is not full moon");
        // 满月: 2.0 * 1.25 = 2.5; 非满月: 原值 2.0。
        helper.assertTrue(Math.abs(VintageClock.applyMoonBonus(2.0D, 0) - 2.5D) < EPS, "full-moon 2.0 -> 2.5");
        helper.assertTrue(Math.abs(VintageClock.applyMoonBonus(2.0D, 3) - 2.0D) < EPS, "non-full-moon 2.0 -> 2.0");
        helper.assertTrue(Math.abs(VintageClock.applyMoonBonus(0.0D, 0)) < EPS, "zero base stays zero even full moon");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void wineNbtRoundTripsQualityVintageAndStrength(GameTestHelper helper) {
        ItemStack stack = new ItemStack(Items.GLASS_BOTTLE);
        helper.assertTrue(!WineNbt.isWine(stack), "plain stack is not wine before stamp");

        UUID brewer = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
        WineNbt.stamp(stack, WineQuality.SUPERB, brewer);
        helper.assertTrue(WineNbt.isWine(stack), "stamped stack is wine");
        helper.assertTrue(WineNbt.readQuality(stack) == WineQuality.SUPERB, "reads back SUPERB");
        helper.assertTrue(Math.abs(WineNbt.readVintage(stack)) < EPS, "fresh wine vintage = 0");
        helper.assertTrue(brewer.equals(WineNbt.readBrewer(stack)), "reads back brewer uuid");
        helper.assertTrue(Math.abs(WineNbt.strength(stack)) < EPS, "strength 0 at vintage 0");

        double v = WineNbt.addVintage(stack, 4.0D);
        helper.assertTrue(Math.abs(v - 4.0D) < EPS, "addVintage returns 4.0");
        helper.assertTrue(Math.abs(WineNbt.readVintage(stack) - 4.0D) < EPS, "vintage persisted 4.0");
        // S = 年份4 × 超凡系数3.0 = 12.0
        helper.assertTrue(Math.abs(WineNbt.strength(stack) - 12.0D) < EPS, "strength = 4 * 3.0 = 12.0");

        // 变质: 强度归 0 (即便年份/品质仍在); 取消变质则恢复。
        WineNbt.setSpoiled(stack, true);
        helper.assertTrue(WineNbt.isSpoiled(stack), "spoiled flag set");
        helper.assertTrue(Math.abs(WineNbt.strength(stack)) < EPS, "spoiled wine strength 0");
        WineNbt.setSpoiled(stack, false);
        helper.assertTrue(Math.abs(WineNbt.strength(stack) - 12.0D) < EPS, "un-spoiled strength restored 12.0");

        // 非酒 stack: strength/readQuality 短路为 0/null, 不静默默认。
        helper.assertTrue(Math.abs(WineNbt.strength(new ItemStack(Items.STONE))) < EPS, "non-wine strength 0");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void brewerJobIdWired(GameTestHelper helper) {
        helper.assertTrue(JobId.byId("brewer") == JobId.BREWER, "byId(brewer) resolves BREWER");
        helper.assertTrue(JobId.BREWER.id().equals("brewer"), "BREWER.id() = brewer");
        helper.succeed();
    }
}
