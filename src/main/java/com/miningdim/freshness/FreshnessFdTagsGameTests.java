package com.miningdim.freshness;

import com.miningdim.core.MiningConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import vectorwing.farmersdelight.common.registry.ModBlocks;
import vectorwing.farmersdelight.common.registry.ModItems;

/**
 * 类别标签的第三方条目 (docs/Spoilage_Granary_DesignSpec.md「保质期与豁免」判定顺序第 3 步)。
 *
 * <p>判定顺序里"能吃但没标类别 → 熟食"这条兜底只救得了**能吃**的东西: 稻米、面团、生面团、派皮、
 * 以及盛宴/派/蛋糕那些**方块物品**都不能直接吃, 漏标就是"永远不坏"。所以按模组拆一张子标签
 * ({@code miningdim:freshness/fd/*}), 类别的总标签再引用它 —— 没装 FD 时那张子标签是空的,
 * 引用照旧成立。</p>
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class FreshnessFdTagsGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "freshness_fd_tags";

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void fdFoodsLandInTheirCategories(GameTestHelper helper) {
        assertCategory(helper, new ItemStack(ModItems.MINCED_BEEF.get()), FreshnessCategory.FRESH);
        assertCategory(helper, new ItemStack(ModItems.BACON.get()), FreshnessCategory.FRESH);
        assertCategory(helper, new ItemStack(ModItems.CABBAGE.get()), FreshnessCategory.PRODUCE);
        assertCategory(helper, new ItemStack(ModItems.WHEAT_DOUGH.get()), FreshnessCategory.PRODUCE);
        assertCategory(helper, new ItemStack(ModItems.RAW_PASTA.get()), FreshnessCategory.PRODUCE);
        assertCategory(helper, new ItemStack(ModItems.RICE.get()), FreshnessCategory.GRAIN);
        assertCategory(helper, new ItemStack(ModItems.SMOKED_HAM.get()), FreshnessCategory.PRESERVED);
        helper.succeed();
    }

    /**
     * 不能直接吃的方块物品也必须标上 (兜底救不了它们): 没标类别的东西在判定顺序里落到最后一步 ——
     * 「其余不腐烂」, 于是一整只烤鸡永远不坏。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void foodBlocksAndIngredientsAreTagged(GameTestHelper helper) {
        assertCategory(helper, new ItemStack(ModBlocks.ROAST_CHICKEN_BLOCK.get().asItem()),
                FreshnessCategory.COOKED);
        assertCategory(helper, new ItemStack(ModBlocks.APPLE_PIE.get().asItem()), FreshnessCategory.COOKED);
        assertCategory(helper, new ItemStack(ModItems.PIE_CRUST.get()), FreshnessCategory.PRODUCE);

        // 子标签本身也要真的在: 类别的总标签引用它, 它空了就等于这一批条目全丢。
        TagKey<Item> fdGrain = TagKey.create(Registries.ITEM,
                new ResourceLocation(MiningConstants.MODID, "freshness/fd/grain"));
        helper.assertTrue(new ItemStack(ModItems.RICE.get()).is(fdGrain),
                "稻米要落在 FD 的谷物子标签里");

        // 不是食物的东西不能被顺手塞进来 (标签写错最容易在这里露馅)。
        helper.assertTrue(FreshnessCategories.categoryOf(new ItemStack(Items.STONE)).isEmpty(),
                "石头不该有保质期类别");
        helper.succeed();
    }

    private static void assertCategory(GameTestHelper helper, ItemStack stack, FreshnessCategory expected) {
        FreshnessCategory actual = FreshnessCategories.categoryOf(stack).orElse(null);
        helper.assertTrue(actual == expected,
                stack.getDescriptionId() + " 应当归 " + expected + ", 实得 " + actual);
    }
}
