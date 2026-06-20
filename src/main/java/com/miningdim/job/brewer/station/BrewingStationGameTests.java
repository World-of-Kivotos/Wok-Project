package com.miningdim.job.brewer.station;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.brewer.WineType;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.items.ItemStackHandler;

/**
 * 酿酒台纯逻辑 GameTest: 品质骰子权重不变式 (各级之和=1、低级闪耀≈0、满级闪耀显著>0、随等级单调) +
 * 配方精确匹配/消费 (足料返回正确 WineType、缺料/多料返回 null、consume 精确扣料)。全为具体数值断言, 纯函数
 * 直接驱动, 无需起世界 (与 BrewEffectGameTests / BrewerFoundationGameTests 同 batch="brewer" 范式)。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class BrewingStationGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "brewer";
    private static final double EPS = 1e-9D;

    // ---- 品质骰子权重 ----

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void weightsSumToOneEveryLevel(GameTestHelper helper) {
        for (int lv = BrewQualityRoller.MIN_LEVEL; lv <= BrewQualityRoller.MAX_LEVEL; lv++) {
            double[] w = BrewQualityRoller.weights(lv);
            helper.assertTrue(w.length == BrewQualityRoller.TIERS, "weights length = 5 at level " + lv);
            double sum = 0.0D;
            for (double x : w) {
                helper.assertTrue(x >= 0.0D, "weight non-negative at level " + lv);
                sum += x;
            }
            helper.assertTrue(Math.abs(sum - 1.0D) < EPS, "weights sum to 1 at level " + lv + " got " + sum);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void lowLevelBrilliantNearZeroFullLevelSignificant(GameTestHelper helper) {
        // 闪耀档 = 索引 4 (BRILLIANT)。
        int brilliant = 4;
        double lvl1Brilliant = BrewQualityRoller.weights(1)[brilliant];
        double lvl10Brilliant = BrewQualityRoller.weights(10)[brilliant];
        // 1 级闪耀概率恒 0 (p=0 -> 0.7*0^3 = 0)。
        helper.assertTrue(Math.abs(lvl1Brilliant) < EPS, "level 1 brilliant prob = 0, got " + lvl1Brilliant);
        // 10 级闪耀概率显著 (> 0.10; p=1 raw=0.7, 归一后约 0.18)。
        helper.assertTrue(lvl10Brilliant > 0.10D, "level 10 brilliant prob > 0.10, got " + lvl10Brilliant);
        // 低段 (<=3 级) 闪耀仍 < 0.02 (新手几乎不出闪耀)。
        helper.assertTrue(BrewQualityRoller.weights(3)[brilliant] < 0.02D, "level 3 brilliant < 0.02");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void highTiersRiseAndLowTiersFallWithLevel(GameTestHelper helper) {
        // LOW (索引0) 随等级单调下降; 闪耀 (索引4) 随等级单调不降。
        double[] prevHigh = null;
        double prevLow = Double.MAX_VALUE;
        double prevBrilliant = -1.0D;
        for (int lv = BrewQualityRoller.MIN_LEVEL; lv <= BrewQualityRoller.MAX_LEVEL; lv++) {
            double[] w = BrewQualityRoller.weights(lv);
            helper.assertTrue(w[0] <= prevLow + EPS, "LOW non-increasing at level " + lv);
            helper.assertTrue(w[4] >= prevBrilliant - EPS, "BRILLIANT non-decreasing at level " + lv);
            prevLow = w[0];
            prevBrilliant = w[4];
            prevHigh = w;
        }
        // 满级低档概率显著小于 1 级 (高端品质把概率从低档抽走)。
        helper.assertTrue(prevHigh[0] < BrewQualityRoller.weights(1)[0] - 0.2D,
                "level 10 LOW notably below level 1 LOW");
        helper.succeed();
    }

    // ---- 配方匹配 / 消费 ----

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void exactRecipeMatchesItsType(GameTestHelper helper) {
        // 白兰地 = 小麦16 + 苹果4。
        ItemStackHandler brandy = handler();
        brandy.setStackInSlot(0, new ItemStack(Items.WHEAT, 16));
        brandy.setStackInSlot(1, new ItemStack(Items.APPLE, 4));
        helper.assertTrue(BrewRecipes.match(brandy) == WineType.BRANDY, "wheat16+apple4 -> BRANDY");

        // 伏特加 = 纯小麦32。
        ItemStackHandler vodka = handler();
        vodka.setStackInSlot(0, new ItemStack(Items.WHEAT, 32));
        helper.assertTrue(BrewRecipes.match(vodka) == WineType.VODKA, "wheat32 -> VODKA");

        // 威士忌 = 纯小麦24 (与月光的小麦24 区分: 月光多糖8)。
        ItemStackHandler whiskey = handler();
        whiskey.setStackInSlot(0, new ItemStack(Items.WHEAT, 24));
        helper.assertTrue(BrewRecipes.match(whiskey) == WineType.WHISKEY, "wheat24 -> WHISKEY");

        // 月光 = 小麦24 + 糖8 (精确匹配下不与威士忌混淆)。
        ItemStackHandler moonshine = handler();
        moonshine.setStackInSlot(0, new ItemStack(Items.WHEAT, 24));
        moonshine.setStackInSlot(1, new ItemStack(Items.SUGAR, 8));
        helper.assertTrue(BrewRecipes.match(moonshine) == WineType.MOONSHINE, "wheat24+sugar8 -> MOONSHINE");

        // 茅台 = 小麦16 + 小麦种子8。
        ItemStackHandler maotai = handler();
        maotai.setStackInSlot(0, new ItemStack(Items.WHEAT, 16));
        maotai.setStackInSlot(1, new ItemStack(Items.WHEAT_SEEDS, 8));
        helper.assertTrue(BrewRecipes.match(maotai) == WineType.MAOTAI, "wheat16+seeds8 -> MAOTAI");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void allNineRecipesDefinedAndMatch(GameTestHelper helper) {
        // 九种酒配方均有定义且按各自精确料集 match 回自身 (用 recipeFor 反向铺料保证与表一致)。
        for (WineType type : WineType.values()) {
            var recipe = BrewRecipes.recipeFor(type);
            helper.assertTrue(!recipe.isEmpty(), "recipe defined for " + type);
            ItemStackHandler h = handler();
            int slot = 0;
            for (BrewRecipes.Ingredient ing : recipe) {
                helper.assertTrue(slot < BrewingStationBlockEntity.INPUT_SLOTS,
                        "recipe " + type + " fits in input slots");
                h.setStackInSlot(slot++, new ItemStack(ing.item(), ing.count()));
            }
            helper.assertTrue(BrewRecipes.match(h) == type, "exact recipe of " + type + " matches " + type);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void insufficientOrExtraIngredientsReturnNull(GameTestHelper helper) {
        // 缺料: 白兰地差 1 小麦 (15 < 16) -> 无匹配。
        ItemStackHandler shortWheat = handler();
        shortWheat.setStackInSlot(0, new ItemStack(Items.WHEAT, 15));
        shortWheat.setStackInSlot(1, new ItemStack(Items.APPLE, 4));
        helper.assertTrue(BrewRecipes.match(shortWheat) == null, "wheat15+apple4 -> no match (short wheat)");

        // 多料: 白兰地料齐但多投 1 胡萝卜 (精确匹配拒绝多余物品) -> 无匹配。
        ItemStackHandler extra = handler();
        extra.setStackInSlot(0, new ItemStack(Items.WHEAT, 16));
        extra.setStackInSlot(1, new ItemStack(Items.APPLE, 4));
        extra.setStackInSlot(2, new ItemStack(Items.CARROT, 1));
        helper.assertTrue(BrewRecipes.match(extra) == null, "extra carrot -> no match (exact)");

        // 空输入 -> null。
        helper.assertTrue(BrewRecipes.match(handler()) == null, "empty input -> null");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void consumeRemovesExactlyRecipeAmounts(GameTestHelper helper) {
        // 朗姆 = 甘蔗8 + 小麦16; 投料含多余余量, consume 只扣配方量。
        ItemStackHandler h = handler();
        h.setStackInSlot(0, new ItemStack(Items.SUGAR_CANE, 8));
        h.setStackInSlot(1, new ItemStack(Items.WHEAT, 16));
        helper.assertTrue(BrewRecipes.match(h) == WineType.RUM, "sugarcane8+wheat16 -> RUM");

        BrewRecipes.consume(h, WineType.RUM);
        // 精确配方全扣光: 两槽清空。
        helper.assertTrue(h.getStackInSlot(0).isEmpty(), "sugarcane consumed to empty");
        helper.assertTrue(h.getStackInSlot(1).isEmpty(), "wheat consumed to empty");

        // 跨槽分堆消费: 小麦24 拆两槽 (16+8), consume 威士忌(24) 应跨槽扣净。
        ItemStackHandler split = handler();
        split.setStackInSlot(0, new ItemStack(Items.WHEAT, 16));
        split.setStackInSlot(1, new ItemStack(Items.WHEAT, 8));
        helper.assertTrue(BrewRecipes.match(split) == WineType.WHISKEY, "split wheat 16+8=24 -> WHISKEY");
        BrewRecipes.consume(split, WineType.WHISKEY);
        helper.assertTrue(split.getStackInSlot(0).isEmpty() && split.getStackInSlot(1).isEmpty(),
                "split wheat fully consumed across slots");
        helper.succeed();
    }

    private static ItemStackHandler handler() {
        return new ItemStackHandler(BrewingStationBlockEntity.TOTAL_SLOTS);
    }
}
