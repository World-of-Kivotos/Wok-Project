package com.miningdim.freshness;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;

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

    private FreshnessHooks() {
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
        FreshnessProcessing.inherit(product, List.of(input), FreshnessProcessing.HEAT_BONUS,
                FreshnessClockData.nowMinute(level));
        items.set(2, product);
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
