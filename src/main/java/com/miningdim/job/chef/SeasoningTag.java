package com.miningdim.job.chef;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Map;
import java.util.Set;

/**
 * 调料判定与偏置映射 (Chef_Job_DesignSpec 第五章; 软依赖, tag + 内置映射)。
 *
 * 是否调料 ({@link #isSeasoning}): 物品在 {@value #SEASONINGS_TAG_PATH} item tag 内即算调料 (datapack
 * {@code data/miningdim/tags/items/seasonings.json} 默认填 FID 真实 ID + 原版糖/蜂蜜 + FD tomato_sauce,
 * ModList 守卫缺失 mod 不要求 —— tag 缺失条目自动忽略, 不报错)。
 *
 * 偏置方向 ({@link #biasOf}): 据调料 id 归类到第五章七方向 (盐->SAVORY, 糖->SWEET, 油->OILY, 醋->SOUR,
 * 辣椒/花椒->SPICY, 姜/蒜/孜然->AROMATIC, 咖喱/火锅底料->COMPLEX)。id 不在内置映射 (第三方调料) -> NONE
 * (算调料但不偏置, 仍消耗)。FID id 经 jar 实核 (第五章列表)。
 */
public final class SeasoningTag {

    private SeasoningTag() {
    }

    private static final String FID = "flavor_immersed_daily";
    static final String SEASONINGS_TAG_PATH = "seasonings";

    /** 调料 item tag (datapack 提供默认内容; 软依赖缺失 mod 条目自动忽略)。 */
    public static final TagKey<Item> SEASONINGS = TagKey.create(
            ForgeRegistries.ITEMS.getRegistryKey(),
            new ResourceLocation(com.miningdim.core.MiningConstants.MODID, SEASONINGS_TAG_PATH));

    /** 是否调料 (在 seasonings tag 内)。 */
    public static boolean isSeasoning(ItemStack stack) {
        return !stack.isEmpty() && stack.is(SEASONINGS);
    }

    /**
     * FID 调料 id -> 偏置方向 (第五章表实核 id)。原版糖/蜂蜜与 FD tomato_sauce 单列在 {@link #biasOf}。
     * 此映射只放 FID 命名空间下的 path, biasOf 内按命名空间分派。
     */
    private static final Map<String, SeasoningBias> FID_BIAS = Map.ofEntries(
            // 咸鲜 -> SAVORY
            Map.entry("salt", SeasoningBias.SAVORY),
            Map.entry("soy", SeasoningBias.SAVORY),
            Map.entry("pepperedsalt", SeasoningBias.SAVORY),
            Map.entry("thickbroadbeansauce", SeasoningBias.SAVORY),
            Map.entry("sweetflourasuve", SeasoningBias.SAVORY),
            // 油脂 -> OILY
            Map.entry("cookingoil", SeasoningBias.OILY),
            Map.entry("sesameoil", SeasoningBias.OILY),
            Map.entry("butter", SeasoningBias.OILY),
            Map.entry("cream", SeasoningBias.OILY),
            // 糖 -> SWEET
            Map.entry("brownsugar", SeasoningBias.SWEET),
            Map.entry("crystalsugar", SeasoningBias.SWEET),
            Map.entry("whitesugarsyrup", SeasoningBias.SWEET),
            Map.entry("concentratedsyrup", SeasoningBias.SWEET),
            // 酸 -> SOUR
            Map.entry("vinegar", SeasoningBias.SOUR),
            // 辛香 -> SPICY (辣椒/花椒)
            Map.entry("chillipowder", SeasoningBias.SPICY),
            Map.entry("drysichuanpepper", SeasoningBias.SPICY),
            Map.entry("chinesepicklyashpowder", SeasoningBias.SPICY),
            // 辛香 -> AROMATIC (姜/蒜/孜然/葱/芝麻)
            Map.entry("ginger", SeasoningBias.AROMATIC),
            Map.entry("garlic", SeasoningBias.AROMATIC),
            Map.entry("garlicpowder", SeasoningBias.AROMATIC),
            Map.entry("cumin", SeasoningBias.AROMATIC),
            Map.entry("cuminpowder", SeasoningBias.AROMATIC),
            Map.entry("onionpowder", SeasoningBias.AROMATIC),
            Map.entry("sesamepowder", SeasoningBias.AROMATIC),
            // 复合底料 -> COMPLEX
            Map.entry("curry", SeasoningBias.COMPLEX),
            Map.entry("spicy_hot_pot_base", SeasoningBias.COMPLEX),
            Map.entry("pepper_hot_pot_base", SeasoningBias.COMPLEX)
    );

    /** 原版调料 id -> 偏置 (糖/蜂蜜瓶 -> SWEET)。 */
    private static final Set<Item> VANILLA_SWEET = Set.of(Items.SUGAR, Items.HONEY_BOTTLE);

    /**
     * 调料偏置方向 (非调料 / 未识别 -> NONE)。FD tomato_sauce -> SAVORY (酸甜咸鲜, 归续航鲜味)。
     */
    public static SeasoningBias biasOf(ItemStack stack) {
        if (!isSeasoning(stack)) {
            return SeasoningBias.NONE;
        }
        Item item = stack.getItem();
        if (VANILLA_SWEET.contains(item)) {
            return SeasoningBias.SWEET;
        }
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
        if (id == null) {
            return SeasoningBias.NONE;
        }
        if (FID.equals(id.getNamespace())) {
            return FID_BIAS.getOrDefault(id.getPath(), SeasoningBias.NONE);
        }
        if ("farmersdelight".equals(id.getNamespace()) && "tomato_sauce".equals(id.getPath())) {
            return SeasoningBias.SAVORY;
        }
        return SeasoningBias.NONE;
    }
}
