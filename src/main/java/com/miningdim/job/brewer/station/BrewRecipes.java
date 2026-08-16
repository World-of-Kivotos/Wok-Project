package com.miningdim.job.brewer.station;

import com.miningdim.job.brewer.WineType;
import com.miningdim.job.farmer.item.FarmerItems;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 酿酒台投料配方 (酿酒师 阶段 3 纯逻辑)。九种酒各一配方, 小麦项吃 {@code miningdim:farmer_wheat}
 * (农夫 mod 耕地产出, 受耕地放置硬封顶与档位门约束), 其余辅料 (苹果/糖/甘蔗/胡萝卜/小麦种子) 仍用原版物品;
 * 这才是设计里 "农夫供给酿酒师" 那条链的真正落点 —— 原版小麦不再能满足任何一条酿造配方
 * (见 MEMORY economy-laundering, F029)。
 *
 * 设计为纯函数 (无世界依赖, 只读 {@link IItemHandler} 的 N 个输入槽): {@link #match} 扫输入槽判出唯一满足的
 * 配方类型, {@link #consume} 把该配方各料从槽内扣掉。BE 调这两个入口, GameTest 直接驱动断言, 故配方逻辑可测。
 *
 * 匹配语义 = 精确匹配 (exact): 输入槽聚合后的物品集合必须与某配方的物品集合 "种类完全一致且每种计数恰好相等",
 * 才算 match。不用 "齐备且足量 (>=)" 是因为某些配方的料集是另一些的子集 (例: 威士忌纯小麦24 是月光小麦24+糖8
 * 的子集), >= 语义会让超集输入同时满足子集配方造成歧义; 精确匹配从根上消除歧义, 也符合 "按既定配方下料" 的直觉
 * (多投/错投不出酒, 逼玩家按方下料)。
 */
public final class BrewRecipes {

    private BrewRecipes() {
    }

    /** 一条配方原料项: 某 item 需要 count 个 (精确)。 */
    public record Ingredient(Item item, int count) {
        public Ingredient {
            if (item == null) {
                throw new IllegalArgumentException("ingredient item must not be null");
            }
            if (count <= 0) {
                throw new IllegalArgumentException("ingredient count must be > 0, got " + count);
            }
        }
    }

    /**
     * initialization-on-demand holder: 配方表引用 {@code FarmerItems.FARMER_WHEAT.get()}, 而
     * {@code RegistryObject.get()} 在物品注册完成前调用会抛; 工程范式禁止在类静态初始化期 (即本类被首次加载时)
     * 求值任何 RegistryObject。把表挪进内部类可以让 JVM 把求值推迟到 Holder 类首次被引用的那一刻 (也就是
     * {@link #recipeFor} / {@link #match} 首次被调用时), 而这必然发生在 mod 物品注册完成之后; JVM 规范保证
     * 类初始化本身线程安全, 无需额外加锁。
     */
    private static final class Holder {
        /**
         * 九种酒配方表 (精确匹配)。原料量体现小麦大宗消耗 (每配方含 16-32 小麦, 均为 farmer_wheat):
         *  - BRANDY 白兰地  = 小麦16 + 苹果4
         *  - VODKA  伏特加  = 小麦32 (纯小麦超大宗)
         *  - GIN    金酒    = 小麦16 + 糖4
         *  - RUM    朗姆酒  = 甘蔗8 + 小麦16
         *  - TEQUILA 龙舌兰 = 胡萝卜8 + 小麦16
         *  - MAOTAI 茅台    = 小麦16 + 小麦种子8 (稻米暂用小麦种子代, 见 openIssues)
         *  - WHISKEY 威士忌 = 小麦24
         *  - CHAMPAGNE 香槟 = 小麦16 + 糖4 + 苹果2
         *  - MOONSHINE 月光 = 小麦24 + 糖8
         *
         * 精确匹配下无歧义约束 (子集不再误命中)。香槟与金酒物品集合不同 (香槟多苹果2), 月光与威士忌不同
         * (月光多糖8), 故各配方的 "物品集合 + 计数" 互不相等, 一组输入至多命中一条。
         */
        static final Map<WineType, List<Ingredient>> RECIPES = buildRecipes();
    }

    private static Map<WineType, List<Ingredient>> buildRecipes() {
        Map<WineType, List<Ingredient>> map = new EnumMap<>(WineType.class);
        Item farmerWheat = FarmerItems.FARMER_WHEAT.get();
        map.put(WineType.BRANDY, List.of(
                new Ingredient(farmerWheat, 16), new Ingredient(Items.APPLE, 4)));
        map.put(WineType.VODKA, List.of(
                new Ingredient(farmerWheat, 32)));
        map.put(WineType.GIN, List.of(
                new Ingredient(farmerWheat, 16), new Ingredient(Items.SUGAR, 4)));
        map.put(WineType.RUM, List.of(
                new Ingredient(Items.SUGAR_CANE, 8), new Ingredient(farmerWheat, 16)));
        map.put(WineType.TEQUILA, List.of(
                new Ingredient(Items.CARROT, 8), new Ingredient(farmerWheat, 16)));
        map.put(WineType.MAOTAI, List.of(
                new Ingredient(farmerWheat, 16), new Ingredient(Items.WHEAT_SEEDS, 8)));
        map.put(WineType.WHISKEY, List.of(
                new Ingredient(farmerWheat, 24)));
        map.put(WineType.CHAMPAGNE, List.of(
                new Ingredient(farmerWheat, 16), new Ingredient(Items.SUGAR, 4),
                new Ingredient(Items.APPLE, 2)));
        map.put(WineType.MOONSHINE, List.of(
                new Ingredient(farmerWheat, 24), new Ingredient(Items.SUGAR, 8)));
        // 不可变化 (配方表全局只读, 防运行期被改)。
        Map<WineType, List<Ingredient>> immutable = new EnumMap<>(WineType.class);
        for (Map.Entry<WineType, List<Ingredient>> e : map.entrySet()) {
            immutable.put(e.getKey(), Collections.unmodifiableList(new ArrayList<>(e.getValue())));
        }
        return Collections.unmodifiableMap(immutable);
    }

    /** 取某酒类型的配方 (只读); 未定义返回空列表 (九种均有定义, 防御性兜底)。 */
    public static List<Ingredient> recipeFor(WineType type) {
        return Holder.RECIPES.getOrDefault(type, Collections.emptyList());
    }

    /**
     * 扫输入槽判匹配的配方类型 (纯函数, 只读 handler)。把输入槽内每种 item 的计数聚合, 找其 "物品集合 + 计数"
     * 与某配方精确相等的类型并返回; 无任何配方精确匹配返回 null (BE 据此短路不酿造)。空输入返回 null。
     */
    public static WineType match(IItemHandler input) {
        if (input == null) {
            return null;
        }
        Map<Item, Integer> available = tally(input);
        if (available.isEmpty()) {
            return null;
        }
        for (WineType type : WineType.values()) {
            if (matchesExactly(available, recipeFor(type))) {
                return type;
            }
        }
        return null;
    }

    /**
     * 从输入槽精确扣掉某配方各料 (原地修改 handler)。调用前应已 {@link #match} 确认该 type 精确满足; 本方法仍
     * 逐项校验足量, 不足则抛 (投料中途变动属时序错误, 自然冒泡不静默掩盖)。
     *
     * @param input 输入槽 handler (须为可写 {@link IItemHandlerModifiable})
     * @param type  要消耗的配方类型
     */
    public static void consume(IItemHandler input, WineType type) {
        if (input == null || type == null) {
            throw new IllegalArgumentException("consume requires non-null input handler and type");
        }
        for (Ingredient ing : recipeFor(type)) {
            int remaining = ing.count();
            for (int slot = 0; slot < input.getSlots() && remaining > 0; slot++) {
                ItemStack stack = input.getStackInSlot(slot);
                if (stack.isEmpty() || !stack.is(ing.item())) {
                    continue;
                }
                int take = Math.min(remaining, stack.getCount());
                stack.shrink(take);
                // getStackInSlot 返回内部引用, shrink 已原地生效; 仍显式回写以兼容不暴露内部引用的包装实现。
                if (input instanceof IItemHandlerModifiable modifiable) {
                    modifiable.setStackInSlot(slot, stack.isEmpty() ? ItemStack.EMPTY : stack);
                }
                remaining -= take;
            }
            if (remaining > 0) {
                throw new IllegalStateException(
                        "consume found insufficient " + ing.item() + " for " + type + " (short " + remaining + ")");
            }
        }
    }

    /** 聚合输入槽内每种 item 的总计数 (空槽跳过)。 */
    private static Map<Item, Integer> tally(IItemHandler input) {
        Map<Item, Integer> counts = new HashMap<>();
        for (int slot = 0; slot < input.getSlots(); slot++) {
            ItemStack stack = input.getStackInSlot(slot);
            if (stack.isEmpty()) {
                continue;
            }
            counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
        return counts;
    }

    /**
     * 聚合计数是否与某配方 "精确相等": 物品种类数一致, 且每项 ingredient 计数恰好等于聚合计数 (无多余物品)。
     * 空配方视为不匹配 (防未定义类型误命中)。
     */
    private static boolean matchesExactly(Map<Item, Integer> available, List<Ingredient> recipe) {
        if (recipe.isEmpty()) {
            return false;
        }
        if (available.size() != recipe.size()) {
            return false; // 种类数不等 -> 必有多余/缺失物品, 非精确。
        }
        for (Ingredient ing : recipe) {
            if (available.getOrDefault(ing.item(), 0) != ing.count()) {
                return false;
            }
        }
        return true;
    }
}
