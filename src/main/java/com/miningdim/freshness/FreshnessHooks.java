package com.miningdim.freshness;

import net.minecraft.core.BlockPos;
import java.util.Optional;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * mixin 的适配层: 注入点只负责把「看到了什么」转给本类, 判据全部落在 {@link FreshnessSettle} 与
 * {@link FreshnessProcessing} 这两个可测的纯实现上。
 *
 * 这样做还有一个安全上的好处: 注入点越薄, 换 MC 版本时对不上的面越小 (规格「主要风险」第 1 条:
 * 必需 mixin 配置注入失败会崩服, 所以注入点要少而稳, 判据要有 GameTest)。
 */
public final class FreshnessHooks {

    /**
     * 熔炉的原料快照。
     *
     * {@code burn} 成功后会先把原料扣掉, 等我们注入的 RETURN 跑到时, 单件原料已经变成空栈, 继承就无从谈起;
     * 因此在 HEAD 存一份副本, RETURN 取用。只在服务端主线程使用, 用完即清 (不会跨 tick 残留)。
     */
    private static final ThreadLocal<ItemStack> FURNACE_INPUT = new ThreadLocal<>();

    /**
     * 本次 burn **之前**输出格里已经有的东西 (规格「机器输出合并取最老」)。
     *
     * 原版 burn 会把新产物直接 grow 进输出格, 于是"这一堆里哪些是刚出炉的、哪些是原来就放着的"在 RETURN 时
     * 已经分不出来了 —— 必须先在 HEAD 拍一张快照。
     */
    private static final ThreadLocal<ItemStack> FURNACE_OUTPUT_BEFORE = new ThreadLocal<>();

    private FreshnessHooks() {
    }

    // ============================================================
    // 营火: 掉落之前改产物 (规格 mixin 清单 #5)
    // ============================================================

    /**
     * 营火烤好一件、**掉落之前**把产物改好 (规格 mixin 清单 #5): 从还在火上的那件原料继承 (+25% 加热加成),
     * 原料在烤的过程中烂了就不出品、改掉一个腐烂物 (与熔炉同一条规矩)。
     *
     * 为什么必须在掉落这一刻改: 营火存的是**原料**, 产物是掉落时按配方现算的 —— 在放置时给原料盖章不会跟着
     * 进产物 (配方产物是新的 ItemStack)。所以只能在这一个瞬间把产物接上原料的年龄。
     *
     * 同一座营火上同时烤两件同类原料时取**最老**的那一件 (模块的通用保守方向: 宁可接老, 不可凭空变新)。
     *
     * @return 该掉落的产物 (改好的); 没有可继承的原料时原样返回 (随后由掉落观察点按现在盖章)
     */
    public static ItemStack stampCampfireProduct(Level level, CampfireBlockEntity campfire, ItemStack product) {
        ServerLevel serverLevel = serverLevel(level);
        if (serverLevel == null || product.isEmpty() || campfire == null
                || !FreshnessConfig.stampingEnabled()) {
            return product;
        }
        long nowMinute = FreshnessClockData.nowMinute(serverLevel);
        ItemStack input = oldestCampfireInput(campfire, product, nowMinute);
        if (input == null) {
            return product;
        }
        if (FreshnessCheck.isRottenNow(input, nowMinute)) {
            // 原料在火上放烂了: 不出品, 掉一个腐烂物 (与熔炉"原料过期就停工并换腐烂物"同一口径)。
            return FreshnessSettle.rotStack(1);
        }
        FreshnessProcessing.inherit(product, List.of(input), FreshnessProcessing.HEAT_BONUS, nowMinute);
        return product;
    }

    /** 这座营火上"烤出来正好是这件产物"的原料里最老的那一件; 没有就返回 null。 */
    private static ItemStack oldestCampfireInput(CampfireBlockEntity campfire, ItemStack product, long nowMinute) {
        ItemStack oldest = null;
        long oldestRemaining = Long.MAX_VALUE;
        for (ItemStack candidate : campfire.getItems()) {
            if (candidate.isEmpty() || !FreshnessCategories.spoils(candidate)) {
                continue;
            }
            Optional<net.minecraft.world.item.crafting.CampfireCookingRecipe> recipe =
                    campfire.getCookableRecipe(candidate);
            if (recipe.isEmpty() || !recipe.get().getResultItem(RegistryAccess.EMPTY).is(product.getItem())) {
                continue;
            }
            long remaining = FreshnessCheck.remainingMinutes(candidate, nowMinute);
            if (remaining < oldestRemaining) {
                oldestRemaining = remaining;
                oldest = candidate;
            }
        }
        return oldest;
    }
    // ============================================================
    // 漏斗: 每次搬运前给自己那几格盖章 / 换腐烂物, 不做任何合并 (反漏洞 #10)
    // ============================================================

    public static void settleHopper(Container hopper, Level level, BlockPos pos) {
        ServerLevel serverLevel = serverLevel(level);
        if (serverLevel == null || !FreshnessConfig.stampingEnabled()) {
            return;
        }
        long nowMinute = FreshnessClockData.nowMinute(serverLevel);
        List<ItemStack> returns = new ArrayList<>();
        FreshnessSettle.settleContainer(hopper, nowMinute, FreshnessConfig.replacementEnabled(), returns);
        for (ItemStack returned : returns) {
            Block.popResource(serverLevel, pos, returned);
        }
    }

    // ============================================================
    // 熔炉三件套 (+烟熏炉、高炉): 原料过期就停工并换成腐烂物, 出品按加热加成继承
    // ============================================================

    /** @return true 表示本次不烧 (原料已过期) */
    public static boolean refuseRottenFurnaceInput(AbstractFurnaceBlockEntity furnace,
                                                   NonNullList<ItemStack> items) {
        ServerLevel level = serverLevel(furnace.getLevel());
        if (level == null || !FreshnessConfig.stampingEnabled()) {
            return false;
        }
        // 先拍输出格的快照 (合并取最老要用它), 再做原料判定 —— 判定不通过就直接 return, 快照也就没用上。
        FURNACE_OUTPUT_BEFORE.set(items.get(2).copy());
        ItemStack input = items.get(0);
        if (input.isEmpty() || !FreshnessCategories.spoils(input)) {
            return false;
        }
        long nowMinute = FreshnessClockData.nowMinute(level);
        FreshnessData.clamp(input, nowMinute);
        if (!FreshnessData.isStamped(input)) {
            // 还没盖章的原料先盖章, 免得「机器出品」成为绕过计时的入口。
            FURNACE_INPUT.set(input.copy());
            return false;
        }
        int shelfLifeHours = FreshnessCategories.shelfLifeHours(input);
        if (!FreshnessConfig.replacementEnabled() || !FreshnessData.isRotten(input, shelfLifeHours, nowMinute)) {
            FURNACE_INPUT.set(input.copy());
            return false;
        }
        // 过期原料换成腐烂物 (规格: 机器会把过期原料换成腐烂物), 本次不出品。
        FURNACE_INPUT.remove();
        items.set(0, FreshnessSettle.rotStack(input.getCount()));
        return true;
    }

    public static void inheritFurnaceProduct(AbstractFurnaceBlockEntity furnace, NonNullList<ItemStack> items) {
        ItemStack input = FURNACE_INPUT.get();
        FURNACE_INPUT.remove();
        ServerLevel level = serverLevel(furnace.getLevel());
        if (level == null || input == null || input.isEmpty() || !FreshnessConfig.stampingEnabled()) {
            return;
        }
        ItemStack product = items.get(2);
        if (product.isEmpty()) {
            return;
        }
        ItemStack before = FURNACE_OUTPUT_BEFORE.get();
        FURNACE_OUTPUT_BEFORE.remove();
        ItemStack fresh = product.copy();
        FreshnessProcessing.inherit(fresh, List.of(input), FreshnessProcessing.HEAT_BONUS,
                FreshnessClockData.nowMinute(level));
        /*
         * 输出合并取最老 (规格「机器输出合并取最老」/ 测试清单: "f=0 的牛排在输出格、新产物 f=504, 合并后必须是 504"):
         * 输出格里本来就有同种东西时, 那一堆的年龄不能因为"又烤出一件"而被拉年轻 —— 取**较老**的那一批
         * (剩余取少、停表取大)。不然反复烤就能把格子里积压的老货一次次刷新。
         */
        if (before != null && !before.isEmpty()) {
            FreshMerge.preferOlderBatch(fresh, before);
        }
        items.set(2, fresh);
    }

    // ============================================================
    // 合成格 (工作台 3×3 与背包 2×2 走的是同一个静态方法): 有过期原料就不出结果
    // ============================================================

    /** @return true 表示已清空结果格并取消本次结算 */
    public static boolean clearCraftingResultIfRotten(CraftingContainer craftSlots, ResultContainer resultSlots,
                                                      Level level) {
        ServerLevel serverLevel = serverLevel(level);
        if (serverLevel == null || !FreshnessConfig.stampingEnabled()) {
            return false;
        }
        long nowMinute = FreshnessClockData.nowMinute(serverLevel);
        List<ItemStack> ingredients = craftSlots.getItems();
        for (int slot = 0; slot < ingredients.size(); slot++) {
            ItemStack ingredient = ingredients.get(slot);
            if (ingredient.isEmpty() || !FreshnessCategories.spoils(ingredient)) {
                continue;
            }
            FreshnessData.clamp(ingredient, nowMinute);
            if (!FreshnessData.isStamped(ingredient)) {
                continue; // 没盖章的当场按「到手开始计时」算, 不算过期
            }
            int shelfLifeHours = FreshnessCategories.shelfLifeHours(ingredient);
            if (!FreshnessConfig.replacementEnabled() || !FreshnessData.isRotten(ingredient, shelfLifeHours, nowMinute)) {
                continue;
            }
            craftSlots.setItem(slot, FreshnessSettle.rotStack(ingredient.getCount()));
            resultSlots.setItem(0, ItemStack.EMPTY);
            return true;
        }
        return false;
    }

    public static void inheritCraftedProduct(CraftingContainer craftSlots, ResultContainer resultSlots, Level level) {
        ServerLevel serverLevel = serverLevel(level);
        if (serverLevel == null || !FreshnessConfig.stampingEnabled()) {
            return;
        }
        ItemStack product = resultSlots.getItem(0);
        if (product.isEmpty()) {
            return;
        }
        FreshnessProcessing.inherit(product, craftSlots.getItems(), FreshnessProcessing.NO_BONUS,
                FreshnessClockData.nowMinute(serverLevel));
        resultSlots.setItem(0, product);
    }

    private static ServerLevel serverLevel(Level level) {
        return level instanceof ServerLevel serverLevel ? serverLevel : null;
    }
}
