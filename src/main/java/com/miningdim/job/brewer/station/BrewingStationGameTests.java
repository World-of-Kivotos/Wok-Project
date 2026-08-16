package com.miningdim.job.brewer.station;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.brewer.BrewerItems;
import com.miningdim.job.brewer.WineType;
import com.miningdim.job.farmer.item.FarmerItems;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.items.ItemStackHandler;

import java.util.Optional;

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
    public static void brilliantGatedToL10AndDoubledOnFullMoon(GameTestHelper helper) {
        // 闪耀档 = 索引 4 (BRILLIANT)。闪耀难做: 仅 L10 可出, L10 非满月 ≈ 5%, 满月近翻倍。
        int brilliant = 4;
        // 仅 L10: 1-9 级闪耀恒 0。
        helper.assertTrue(Math.abs(BrewQualityRoller.weights(1)[brilliant]) < EPS, "level 1 brilliant = 0");
        helper.assertTrue(Math.abs(BrewQualityRoller.weights(9)[brilliant]) < EPS, "level 9 brilliant = 0 (only L10)");
        helper.assertTrue(BrewQualityRoller.weights(3)[brilliant] < 0.02D, "level 3 brilliant < 0.02");
        // L10 非满月闪耀 ≈ 5% (难做)。
        double lvl10 = BrewQualityRoller.weights(10)[brilliant];
        helper.assertTrue(Math.abs(lvl10 - 0.05D) < 0.01D, "level 10 brilliant ~= 5%, got " + lvl10);
        // L10 满月闪耀近翻倍 (≈ 9.5%, 显著高于非满月但仍受归一约束)。
        double lvl10Moon = BrewQualityRoller.weights(10, true)[brilliant];
        helper.assertTrue(lvl10Moon > lvl10 * 1.5D, "full-moon brilliant ~doubled, got " + lvl10Moon + " vs " + lvl10);
        helper.assertTrue(lvl10Moon < 0.12D, "full-moon brilliant still bounded (~9.5%), got " + lvl10Moon);
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
        brandy.setStackInSlot(0, new ItemStack(FarmerItems.FARMER_WHEAT.get(), 16));
        brandy.setStackInSlot(1, new ItemStack(Items.APPLE, 4));
        helper.assertTrue(BrewRecipes.match(brandy) == WineType.BRANDY, "wheat16+apple4 -> BRANDY");

        // 伏特加 = 纯小麦32。
        ItemStackHandler vodka = handler();
        vodka.setStackInSlot(0, new ItemStack(FarmerItems.FARMER_WHEAT.get(), 32));
        helper.assertTrue(BrewRecipes.match(vodka) == WineType.VODKA, "wheat32 -> VODKA");

        // 威士忌 = 纯小麦24 (与月光的小麦24 区分: 月光多糖8)。
        ItemStackHandler whiskey = handler();
        whiskey.setStackInSlot(0, new ItemStack(FarmerItems.FARMER_WHEAT.get(), 24));
        helper.assertTrue(BrewRecipes.match(whiskey) == WineType.WHISKEY, "wheat24 -> WHISKEY");

        // 月光 = 小麦24 + 糖8 (精确匹配下不与威士忌混淆)。
        ItemStackHandler moonshine = handler();
        moonshine.setStackInSlot(0, new ItemStack(FarmerItems.FARMER_WHEAT.get(), 24));
        moonshine.setStackInSlot(1, new ItemStack(Items.SUGAR, 8));
        helper.assertTrue(BrewRecipes.match(moonshine) == WineType.MOONSHINE, "wheat24+sugar8 -> MOONSHINE");

        // 茅台 = 小麦16 + 小麦种子8。
        ItemStackHandler maotai = handler();
        maotai.setStackInSlot(0, new ItemStack(FarmerItems.FARMER_WHEAT.get(), 16));
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
        shortWheat.setStackInSlot(0, new ItemStack(FarmerItems.FARMER_WHEAT.get(), 15));
        shortWheat.setStackInSlot(1, new ItemStack(Items.APPLE, 4));
        helper.assertTrue(BrewRecipes.match(shortWheat) == null, "wheat15+apple4 -> no match (short wheat)");

        // 多料: 白兰地料齐但多投 1 胡萝卜 (精确匹配拒绝多余物品) -> 无匹配。
        ItemStackHandler extra = handler();
        extra.setStackInSlot(0, new ItemStack(FarmerItems.FARMER_WHEAT.get(), 16));
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
        h.setStackInSlot(1, new ItemStack(FarmerItems.FARMER_WHEAT.get(), 16));
        helper.assertTrue(BrewRecipes.match(h) == WineType.RUM, "sugarcane8+wheat16 -> RUM");

        BrewRecipes.consume(h, WineType.RUM);
        // 精确配方全扣光: 两槽清空。
        helper.assertTrue(h.getStackInSlot(0).isEmpty(), "sugarcane consumed to empty");
        helper.assertTrue(h.getStackInSlot(1).isEmpty(), "wheat consumed to empty");

        // 跨槽分堆消费: 小麦24 拆两槽 (16+8), consume 威士忌(24) 应跨槽扣净。
        ItemStackHandler split = handler();
        split.setStackInSlot(0, new ItemStack(FarmerItems.FARMER_WHEAT.get(), 16));
        split.setStackInSlot(1, new ItemStack(FarmerItems.FARMER_WHEAT.get(), 8));
        helper.assertTrue(BrewRecipes.match(split) == WineType.WHISKEY, "split wheat 16+8=24 -> WHISKEY");
        BrewRecipes.consume(split, WineType.WHISKEY);
        helper.assertTrue(split.getStackInSlot(0).isEmpty() && split.getStackInSlot(1).isEmpty(),
                "split wheat fully consumed across slots");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void vanillaWheatNoLongerMatchesAnyRecipeOnlyFarmerWheatDoes(GameTestHelper helper) {
        // 回归 (F029): 原版小麦不得再命中伏特加配方, 否则农夫供给约束被绕开。
        ItemStackHandler vanilla = handler();
        vanilla.setStackInSlot(0, new ItemStack(Items.WHEAT, 32));
        helper.assertTrue(BrewRecipes.match(vanilla) == null,
                "原版小麦不得再命中伏特加配方, 否则农夫供给约束被绕开");

        ItemStackHandler farmer = handler();
        farmer.setStackInSlot(0, new ItemStack(FarmerItems.FARMER_WHEAT.get(), 32));
        helper.assertTrue(BrewRecipes.match(farmer) == WineType.VODKA,
                "farmer_wheat32 -> VODKA (农夫小麦是唯一能满足酿造 sink 的小麦来源)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void allNineRecipesUseFarmerWheatWithDesignatedCountsNoVanillaWheat(GameTestHelper helper) {
        // F029: 九种配方的小麦项必须是 farmer_wheat 且计数锁定既定值; 整张配方表禁止出现原版 Items.WHEAT
        // (逐条 Ingredient 扫)。改回原版小麦或改错计数必挂。
        for (WineType type : WineType.values()) {
            var recipe = BrewRecipes.recipeFor(type);
            int expectedCount = expectedFarmerWheatCount(type);
            int farmerWheatIngredients = 0;
            for (BrewRecipes.Ingredient ing : recipe) {
                helper.assertTrue(ing.item() != Items.WHEAT,
                        "配方 " + type + " 不得含原版 Items.WHEAT");
                if (ing.item() == FarmerItems.FARMER_WHEAT.get()) {
                    farmerWheatIngredients++;
                    helper.assertTrue(ing.count() == expectedCount,
                            "配方 " + type + " farmer_wheat 计数应为 " + expectedCount + ", 实际 " + ing.count());
                }
            }
            helper.assertTrue(farmerWheatIngredients == 1,
                    "配方 " + type + " 应恰有一条 farmer_wheat 原料, 实际 " + farmerWheatIngredients + " 条");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void vanillaWheatRejectedFarmerWheatMatchesVodkaAndConsumesToEmpty(GameTestHelper helper) {
        // F029: 原版小麦不再命中任何配方; 农夫小麦32 精确命中 VODKA, consume 后该槽扣空 (无残留)。
        ItemStackHandler vanilla = handler();
        vanilla.setStackInSlot(0, new ItemStack(Items.WHEAT, 32));
        helper.assertTrue(BrewRecipes.match(vanilla) == null, "原版小麦32 不命中任何配方");

        ItemStackHandler farmer = handler();
        farmer.setStackInSlot(0, new ItemStack(FarmerItems.FARMER_WHEAT.get(), 32));
        helper.assertTrue(BrewRecipes.match(farmer) == WineType.VODKA, "农夫小麦32 命中 VODKA");

        BrewRecipes.consume(farmer, WineType.VODKA);
        helper.assertTrue(farmer.getStackInSlot(0).isEmpty(), "consume 后该槽应扣空");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void driedWheatSmeltingRecipeAcceptsOnlyFarmerWheat(GameTestHelper helper) {
        // F029: 数据包熔炼配方 miningdim:brewer/dried_wheat 的输入项须换成 farmer_wheat, 原版小麦不再命中,
        // 产物仍是 miningdim:dried_wheat。这条直接锁住 json 被改回去。
        Optional<? extends Recipe<?>> recipeOpt = helper.getLevel().getRecipeManager()
                .byKey(new ResourceLocation(MiningConstants.MODID, "brewer/dried_wheat"));
        helper.assertTrue(recipeOpt.isPresent(), "miningdim:brewer/dried_wheat 熔炼配方必须存在");
        Recipe<?> recipe = recipeOpt.get();

        Ingredient ingredient = recipe.getIngredients().get(0);
        helper.assertTrue(ingredient.test(new ItemStack(FarmerItems.FARMER_WHEAT.get())),
                "干小麦配方须接受农夫小麦");
        helper.assertTrue(!ingredient.test(new ItemStack(Items.WHEAT)),
                "干小麦配方不得再接受原版小麦");

        ItemStack result = recipe.getResultItem(helper.getLevel().registryAccess());
        helper.assertTrue(result.is(BrewerItems.DRIED_WHEAT.get()),
                "干小麦配方产物须仍是 miningdim:dried_wheat");
        helper.succeed();
    }

    /** 九种酒配方小麦项的既定 farmer_wheat 计数 (F029 回归锁定, 与 BrewRecipes 配方表逐一对应)。 */
    private static int expectedFarmerWheatCount(WineType type) {
        return switch (type) {
            case WHISKEY, MOONSHINE -> 24;
            case VODKA -> 32;
            default -> 16;
        };
    }

    private static ItemStackHandler handler() {
        return new ItemStackHandler(BrewingStationBlockEntity.TOTAL_SLOTS);
    }
}
