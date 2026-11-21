package com.miningdim.freshness;

import com.miningdim.core.MiningConstants;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 压缩方块与可逆转换的配方过滤 (规格「压缩方块与可逆转换」规则 1)。
 *
 * **规则**: 「合成配方、只有一种原料、产出 ≥ 4 个、产物会腐烂、原料不会腐烂」的一律删掉。
 *
 * 为什么必须删而不是靠继承: 加工继承只保证"产物不更新的" —— 但**拆回是 1 变 N**: 一块放了 47 小时的干草块
 * 拆成 9 个小麦, 每个小麦都继承那 47 小时, 于是"总剩余寿命"从 1 份变成 9 份。这不是计时被刷新, 而是
 * **凭空放大**, 继承挡不住。所以这一条只能从配方源头掐掉: 压缩可以, 拆回不行。
 *
 * 判据照着规格逐字写, 不做"聪明"的推广:
 * <ul>
 *   <li>**合成配方**: 只认 {@link RecipeType#CRAFTING}; 砧板一类切分配方是另一条规则 (规则 2);</li>
 *   <li>**只有一种原料**: 所有非空原料里出现的**不同物品**恰好一种 (9 格同一种东西算一种);</li>
 *   <li>**产出 ≥ 4 个**: 1 变 2、1 变 3 不删 —— 那不是压缩方块, 删了会误伤正经配方 (例如 1 个面团 → 4 个饼干
 *       这种"一料多产"的烹饪配方会被误伤, 因此阈值取规格给的 4 并要求产物是易腐品才有意义);</li>
 *   <li>**产物会腐烂、原料不会腐烂**: 两头的类别判定都走 {@link FreshnessCategories}, 与全模块同一份口径。</li>
 * </ul>
 *
 * 白名单按配方 id 配置 (整合包里有正当的 1 料 4 产配方时用得上); 删了哪些**逐条写进日志**, 免得"某个配方
 * 莫名其妙没了"变成悬案。
 */
public final class FreshnessRecipeFilter {

    private FreshnessRecipeFilter() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/freshness");

    /** 产出数量的门槛: 规格给的 4 (低于它的不是压缩方块)。 */
    public static final int MIN_OUTPUT_COUNT = 4;

    /**
     * 这条配方该不该删。
     *
     * @param recipe     候选配方
     * @param registries 取产物 ItemStack 用的注册表访问 (配方自带的结果原型需要它才能解出物品)
     */
    public static boolean shouldRemove(Recipe<?> recipe, RegistryAccess registries) {
        if (recipe == null || recipe.getType() != RecipeType.CRAFTING || recipe.isSpecial()) {
            return false;
        }
        ItemStack result = resultOf(recipe, registries);
        if (result.isEmpty() || result.getCount() < MIN_OUTPUT_COUNT) {
            return false;
        }
        if (!FreshnessCategories.spoils(result)) {
            return false; // 产物不腐烂: 拿它当"新货"没有意义
        }
        NonNullList<Ingredient> ingredients = recipe.getIngredients();
        Set<Item> distinct = new LinkedHashSet<>();
        for (Ingredient ingredient : ingredients) {
            if (ingredient.isEmpty()) {
                continue;
            }
            for (ItemStack candidate : ingredient.getItems()) {
                if (!candidate.isEmpty()) {
                    distinct.add(candidate.getItem());
                }
            }
        }
        if (distinct.size() != 1) {
            return false; // 多料配方 (包括"同一条配方里混了几种木板"这类) 一律不动
        }
        ItemStack input = new ItemStack(distinct.iterator().next());
        // 原料会腐烂 -> 那是"切开再拼回"或烹饪, 归规则 2 与加工继承管; 这里只掐"不腐烂的压缩块拆回易腐品"。
        return !FreshnessCategories.spoils(input);
    }

    /**
     * 从一张合成配方表里挑出该删的那些 (纯函数, 不改传入的表)。
     *
     * @param crafting  合成配方表 (可能为 null / 不可变 —— 本方法只读)
     * @param whitelist 白名单 (配方 id); 命中即跳过, 传 null 等于没有白名单
     */
    public static List<ResourceLocation> collectRemovals(Map<ResourceLocation, Recipe<?>> crafting,
                                                        Set<ResourceLocation> whitelist) {
        if (crafting == null || crafting.isEmpty()) {
            return List.of();
        }
        List<ResourceLocation> removed = new ArrayList<>();
        for (Map.Entry<ResourceLocation, Recipe<?>> entry : crafting.entrySet()) {
            ResourceLocation id = entry.getKey();
            if (whitelist != null && whitelist.contains(id)) {
                continue;
            }
            if (shouldRemove(entry.getValue(), RegistryAccess.EMPTY)) {
                removed.add(id);
            }
        }
        return removed;
    }

    /**
     * 复制一张表并去掉这些 id, 返回**可变**的副本。
     *
     * 为什么要复制而不是就地删: {@code RecipeManager} 内部的表是 {@code ImmutableMap} (1.20.1 用
     * {@code ImmutableMap.Builder} 建), 就地 {@code remove} 会直接抛 {@code UnsupportedOperationException};
     * 而外层那张 {@code Map<RecipeType<?>, Map<...>>} 是可变的 {@code HashMap}, 所以做法是"造一份新的子表
     * 再把它换进去"。
     */
    public static Map<ResourceLocation, Recipe<?>> rebuildWithout(Map<ResourceLocation, Recipe<?>> source,
                                                                 List<ResourceLocation> removed) {
        Map<ResourceLocation, Recipe<?>> rebuilt = new LinkedHashMap<>(source);
        for (ResourceLocation id : removed) {
            rebuilt.remove(id);
        }
        return rebuilt;
    }

    /** 配置里的白名单字符串 -> id 集合 (解析不出来的忽略)。 */
    public static Set<ResourceLocation> parseWhitelist(List<? extends String> raws) {
        Set<ResourceLocation> whitelist = new LinkedHashSet<>();
        if (raws != null) {
            for (String raw : raws) {
                ResourceLocation id = ResourceLocation.tryParse(raw);
                if (id != null) {
                    whitelist.add(id);
                }
            }
        }
        return whitelist;
    }

    private static ItemStack resultOf(Recipe<?> recipe, RegistryAccess registries) {
        try {
            // 1.20.1 的配方结果原型要用注册表访问解; 缺注册表 (理论上不会) 时退化成不解。
            return registries == null ? ItemStack.EMPTY : recipe.getResultItem(registries);
        } catch (RuntimeException malformed) {
            return ItemStack.EMPTY;
        }
    }

    /** 把删掉的配方逐条写日志 (一次汇总 + 逐条 id), 给"某个配方怎么没了"留下可查的凭据。 */
    public static void logRemoved(List<ResourceLocation> removed) {
        if (removed.isEmpty()) {
            return;
        }
        LOGGER.info("[miningdim] freshness recipe filter: 删掉 {} 条「压缩块拆回」配方 (规格「压缩方块与可逆转换」规则 1)",
                removed.size());
        for (ResourceLocation id : removed) {
            LOGGER.info("[miningdim] freshness recipe filter: removed {}", id);
        }
    }

    /** 语言键前缀: 压成块的作物块 tooltip 上的「不可拆回」提醒 (客户端显示那一片接)。 */
    public static final String KEY_NOT_UNSQUASHABLE = "tooltip." + MiningConstants.MODID + ".freshness.not_unsquashable";
}
