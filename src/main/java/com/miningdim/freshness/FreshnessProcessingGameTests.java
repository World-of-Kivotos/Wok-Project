package com.miningdim.freshness;

import com.miningdim.core.MiningConstants;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.util.List;

/** 加工继承纯策略的可达业务用例；适配器测试随运行入口接入。 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class FreshnessProcessingGameTests {
    private static final String EMPTY = "empty";
    private static final String BATCH = "freshness_processing";
    private static final long FIXED_NOW = 500_000L * 60L;

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void inheritTakesWorstRatioAndIgnoresBetterIngredients(GameTestHelper helper) {
        FreshnessConfig.Mode previousMode = FreshnessConfig.MODE.get();
        FreshnessConfig.MODE.set(FreshnessConfig.Mode.DRY_RUN);
        try {
            int wheatShelf = FreshnessConfig.shelfLifeHours(FreshnessCategory.GRAIN);
            int breadShelf = FreshnessConfig.shelfLifeHours(FreshnessCategory.COOKED);

            ItemStack wheat = ratio(wheatShelf, 0.5D, Items.WHEAT, 3);
            ItemStack apple = ratio(FreshnessConfig.shelfLifeHours(FreshnessCategory.PRODUCE), 1.0D, Items.APPLE, 1);
            ItemStack bread = new ItemStack(Items.BREAD);
            FreshnessProcessing.inherit(bread, List.of(wheat, apple), FreshnessProcessing.NO_BONUS, FIXED_NOW);

            long expected = (long) Math.floor(breadShelf * 60.0D * 0.5D);
            long actual = FreshnessData.remainingMinutes(bread, breadShelf, FIXED_NOW);
            helper.assertTrue(actual == expected,
                    "合成产物按原料里最差的比例继承 (期望 " + expected + " 分钟, 实际 " + actual + ")");

            ItemStack onlyGood = new ItemStack(Items.BREAD);
            FreshnessProcessing.inherit(onlyGood, List.of(apple), FreshnessProcessing.NO_BONUS, FIXED_NOW);
            helper.assertTrue(FreshnessData.remainingMinutes(onlyGood, breadShelf, FIXED_NOW) == breadShelf * 60L,
                    "全新原料做出的产物应当是满保质期 (说明取的不是平均值)");
            helper.succeed();
        } finally {
            FreshnessConfig.MODE.set(previousMode);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void heatBonusTopsUpAndCapsAtFullShelfLife(GameTestHelper helper) {
        FreshnessConfig.Mode previousMode = FreshnessConfig.MODE.get();
        FreshnessConfig.MODE.set(FreshnessConfig.Mode.DRY_RUN);
        try {
            int meatShelf = FreshnessConfig.shelfLifeHours(FreshnessCategory.FRESH);
            int cookedShelf = FreshnessConfig.shelfLifeHours(FreshnessCategory.COOKED);

            ItemStack quarter = ratio(meatShelf, 0.25D, Items.BEEF, 1);
            ItemStack halfSteak = new ItemStack(Items.COOKED_BEEF);
            FreshnessProcessing.inherit(halfSteak, List.of(quarter), FreshnessProcessing.HEAT_BONUS, FIXED_NOW);
            long halfExpected = (long) Math.floor(cookedShelf * 60.0D * 0.5D);
            helper.assertTrue(FreshnessData.remainingMinutes(halfSteak, cookedShelf, FIXED_NOW) == halfExpected,
                    "加热加成把 0.25 顶到 0.5");

            ItemStack threeQuarters = ratio(meatShelf, 0.75D, Items.BEEF, 1);
            ItemStack fullSteak = new ItemStack(Items.COOKED_BEEF);
            FreshnessProcessing.inherit(fullSteak, List.of(threeQuarters), FreshnessProcessing.HEAT_BONUS, FIXED_NOW);
            helper.assertTrue(FreshnessData.remainingMinutes(fullSteak, cookedShelf, FIXED_NOW) == cookedShelf * 60L,
                    "加成后超过 1 一律封顶为满保质期");
            helper.succeed();
        } finally {
            FreshnessConfig.MODE.set(previousMode);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void productRemainingIsFlooredAndNeverBelowOneHour(GameTestHelper helper) {
        FreshnessConfig.Mode previousMode = FreshnessConfig.MODE.get();
        FreshnessConfig.MODE.set(FreshnessConfig.Mode.DRY_RUN);
        try {
            int meatShelf = FreshnessConfig.shelfLifeHours(FreshnessCategory.FRESH);
            int cookedShelf = FreshnessConfig.shelfLifeHours(FreshnessCategory.COOKED);

            ItemStack nearlyGone = ratio(meatShelf, 0.3D, Items.BEEF, 1);
            ItemStack product = new ItemStack(Items.COOKED_BEEF);
            FreshnessProcessing.inherit(product, List.of(nearlyGone), FreshnessProcessing.NO_BONUS, FIXED_NOW);
            long expected = 14L * 60L; // 48 小时 × 30% = 14.4 小时，舍去 24 分钟。
            helper.assertTrue(FreshnessData.remainingMinutes(product, cookedShelf, FIXED_NOW) == expected,
                    "产物剩余按保质期 × 比例向下取整");

            long processedAt = FIXED_NOW + 50L;
            ItemStack dust = ratioAt(meatShelf, 0.0001D, Items.BEEF, 1, processedAt);
            ItemStack floored = new ItemStack(Items.COOKED_BEEF);
            FreshnessProcessing.inherit(floored, List.of(dust), FreshnessProcessing.NO_BONUS, processedAt);
            helper.assertTrue(FreshnessData.remainingMinutes(floored, cookedShelf, processedAt) == 60L
                            && FreshnessData.batchMinute(floored) % 60L == 50L,
                    "在第 50 分钟加工仍保留完整 60 分钟，不向前锚到整点");
            helper.succeed();
        } finally {
            FreshnessConfig.MODE.set(previousMode);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void stoppedHoursFollowTheMostStoppedIngredient(GameTestHelper helper) {
        FreshnessConfig.Mode previousMode = FreshnessConfig.MODE.get();
        FreshnessConfig.MODE.set(FreshnessConfig.Mode.DRY_RUN);
        try {
            int meatShelf = FreshnessConfig.shelfLifeHours(FreshnessCategory.FRESH);
            int cookedShelf = FreshnessConfig.shelfLifeHours(FreshnessCategory.COOKED);

            // 停表额度会加长剩余, 所以「剩 50% 且停过 100 小时」的批次要按 (剩余 − 保质期 − 停表) 反算。
            ItemStack frozen = ratioWithStopped(meatShelf, 0.5D, 100, Items.BEEF, 1);
            ItemStack plain = ratioWithStopped(meatShelf, 1.0D, 5, Items.BEEF, 1);

            ItemStack product = new ItemStack(Items.COOKED_BEEF);
            FreshnessProcessing.inherit(product, List.of(frozen, plain), FreshnessProcessing.NO_BONUS, FIXED_NOW);
            helper.assertTrue(FreshnessData.stoppedHours(product) == 100,
                    "停表额度取原料里最大的 (堵「冻满额度 -> 做熟 -> 再冻满」)");
            long expected = (long) Math.floor(cookedShelf * 60.0D * 0.5D);
            helper.assertTrue(FreshnessData.remainingMinutes(product, cookedShelf, FIXED_NOW) == expected,
                    "剩余比例照样取最差的那件");
            helper.succeed();
        } finally {
            FreshnessConfig.MODE.set(previousMode);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void anyRottenOnlyCountsSpoilingStampedIngredients(GameTestHelper helper) {
        FreshnessConfig.Mode previousMode = FreshnessConfig.MODE.get();
        FreshnessConfig.MODE.set(FreshnessConfig.Mode.DRY_RUN);
        try {
            helper.assertTrue(!FreshnessProcessing.anyRotten(List.of(new ItemStack(Items.BEEF)), FIXED_NOW),
                    "没盖章的原料按「刚到手的」算, 不算过期");
            helper.assertTrue(FreshnessProcessing.anyRotten(List.of(ancient(Items.BEEF, 1)), FIXED_NOW),
                    "过了保质期的原料要判出来");
            helper.assertTrue(!FreshnessProcessing.anyRotten(List.of(ancient(Items.DIAMOND, 1)), FIXED_NOW),
                    "不腐烂的东西不参与判定");
            helper.assertTrue(FreshnessProcessing.anyRotten(
                            List.of(new ItemStack(Items.BEEF), ancient(Items.COOKED_BEEF, 1)), FIXED_NOW),
                    "一堆原料里有任何一件过期就不出产物");
            helper.succeed();
        } finally {
            FreshnessConfig.MODE.set(previousMode);
        }
    }

    /** 一件「在 FIXED_NOW 时剩 ratio 比例」的东西 (策略层用例用)。 */
    private static ItemStack ratio(int shelfLifeHours, double ratio, Item item, int count) {
        return ratioAt(shelfLifeHours, ratio, item, count, FIXED_NOW);
    }

    /** 同 {@link #ratio}, 但时刻由调用方给 (适配层用例一律用服务器时钟)。 */
    private static ItemStack ratioAt(int shelfLifeHours, double ratio, Item item, int count, long nowMinute) {
        ItemStack stack = new ItemStack(item, count);
        long remaining = (long) Math.floor(shelfLifeHours * 60.0D * ratio);
        FreshnessData.stampAt(stack, nowMinute + remaining - (long) shelfLifeHours * 60L, 0);
        return stack;
    }

    /** 一件「剩 ratio 比例、并且已经停过 stoppedHours 小时」的东西。 */
    private static ItemStack ratioWithStopped(int shelfLifeHours, double ratio, int stoppedHours,
                                              Item item, int count) {
        ItemStack stack = new ItemStack(item, count);
        long remaining = (long) Math.floor(shelfLifeHours * 60.0D * ratio);
        FreshnessData.stampAt(stack, FIXED_NOW + remaining
                - (long) shelfLifeHours * 60L - (long) stoppedHours * 60L, stoppedHours);
        return stack;
    }

    private static ItemStack ancient(Item item, int count) {
        return ancientAt(item, count, FIXED_NOW);
    }

    /** 一件远在过去的批次 (一定过期) 的东西。 */
    private static ItemStack ancientAt(Item item, int count, long nowMinute) {
        ItemStack stack = new ItemStack(item, count);
        FreshnessData.stampAt(stack, nowMinute - 2L * 365L * 24L * 60L, 0);
        return stack;
    }

}
