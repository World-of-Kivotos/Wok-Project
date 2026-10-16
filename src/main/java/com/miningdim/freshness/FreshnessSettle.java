package com.miningdim.freshness;

import com.miningdim.freshness.item.FreshnessItems;
import net.minecraft.world.Container;
import net.minecraft.world.item.BowlFoodItem;
import net.minecraft.world.item.HoneyBottleItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Optional;

/**
 * 结算: 把「观察点」看到的一格东西推到当前时刻该有的样子 (规格「观察点」「腐烂终态」)。
 *
 * 全模块只有这一处做「盖章 / 换成腐烂物」两个动作, 所有观察点 (掉落、玩家扫描、白名单容器、漏斗、
 * 加工出品、进食、市场、联网的消费点) 都调它, 免得每个入口各写一套判据。
 *
 * 契约:
 * <ul>
 *   <li>会就地把未盖章的易腐品盖上整点章、把未来批次与超额停表钳回合法区间;</li>
 *   <li>发现已腐烂且 {@code replaceRotten} 为真时, 返回该格**应当放入**的腐烂物 (1 换 1),
 *       并把碗、瓶这类容器按件收集进 {@code returns}; 调用方负责把返回值放回格子;</li>
 *   <li>返回空 = 这一格不用动。</li>
 * </ul>
 *
 * {@code replaceRotten} 由调用方从 {@link FreshnessConfig#replacementEnabled()} 取: 试运行档照常盖章但不换,
 * 于是同一套代码在试运行下只统计、不破坏 (安全阀)。
 */
public final class FreshnessSettle {

    private FreshnessSettle() {
    }

    /**
     * 结算一格。
     *
     * @param stack          格子里的东西 (会被就地盖章/钳制)
     * @param nowMinute      现在的分钟序号
     * @param replaceRotten  是否执行终态替换 (试运行档为 false)
     * @param returns        碗、瓶这类容器的收集处; 传 null 表示丢弃容器退回
     * @return 该格应当放入的东西; 空表示保持原样
     */
    public static ItemStack settleSlot(ItemStack stack, long nowMinute, boolean replaceRotten,
                                       List<ItemStack> returns) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        Optional<FreshnessCategory> category = FreshnessCategories.categoryOf(stack);
        if (category.isEmpty()) {
            return ItemStack.EMPTY;
        }
        FreshnessData.clamp(stack, nowMinute);
        if (!FreshnessData.isStamped(stack)) {
            FreshnessData.stamp(stack, nowMinute);
            FreshnessStats.recordStamped();
            return ItemStack.EMPTY;
        }
        if (!replaceRotten) {
            // 试运行: 只统计「本该腐烂多少件」, 不动东西 (安全阀)。
            if (FreshnessData.isRotten(stack, FreshnessConfig.shelfLifeHours(category.get()), nowMinute)) {
                FreshnessStats.recordWouldRot();
            }
            return ItemStack.EMPTY;
        }
        int shelfLifeHours = FreshnessConfig.shelfLifeHours(category.get());
        if (!FreshnessData.isRotten(stack, shelfLifeHours, nowMinute)) {
            return ItemStack.EMPTY;
        }
        FreshnessStats.recordRotted();
        if (returns != null) {
            returns.addAll(containerReturns(stack));
        }
        return rotStack(stack.getCount());
    }

    /**
     * 结算整个容器 (玩家背包、箱子、储粮囤都算 Container)。
     *
     * @return 换成腐烂物的格数
     */
    public static int settleContainer(Container container, long nowMinute, boolean replaceRotten,
                                      List<ItemStack> returns) {
        int rotted = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack replacement = settleSlot(container.getItem(slot), nowMinute, replaceRotten, returns);
            if (!replacement.isEmpty()) {
                container.setItem(slot, replacement);
                rotted++;
            }
        }
        return rotted;
    }

    /** 腐烂物栈 (1 换 1, 至少 1 个)。 */
    public static ItemStack rotStack(int count) {
        return new ItemStack(FreshnessItems.ROT.get(), Math.max(1, count));
    }

    /** 腐烂物物品是否已注册 (配置成「关闭」或在极早期阶段, 物品可能还取不到)。 */
    public static boolean rotItemAvailable() {
        return FreshnessItems.ROT.isPresent();
    }

    /**
     * 碗装、瓶装的食物腐烂时把碗和瓶按件退回 (规格「怎么换」)。
     *
     * 1.20.1 的容器返还分两种机制, 都要认:
     * <ul>
     *   <li><b>合成剩余物</b> ({@code hasCraftingRemainingItem}) —— 例如岩浆桶还桶、蘑菇煲在某些版本里的写法;</li>
     *   <li><b>吃完给回容器</b> —— 原版碗装食物是 {@link BowlFoodItem}、瓶装饮料是 {@link HoneyBottleItem},
     *       它们的容器在 {@code finishUsingItem} 里给回, 物品本身没有合成剩余物。腐烂时同样要退,
     *       否则玩家白丢一个碗 (模组的同类物品一般也继承这两个基类)。</li>
     * </ul>
     * 两种都不认的物品不带容器, 不退回。
     */
    private static List<ItemStack> containerReturns(ItemStack stack) {
        Item container = containerItemFor(stack.getItem());
        if (container == null) {
            return List.of();
        }
        ItemStack returned = new ItemStack(container, stack.getCount());
        return List.of(returned);
    }

    private static Item containerItemFor(Item item) {
        if (item.hasCraftingRemainingItem()) {
            return item.getCraftingRemainingItem();
        }
        if (item instanceof BowlFoodItem) {
            return Items.BOWL;
        }
        if (item instanceof HoneyBottleItem) {
            return Items.GLASS_BOTTLE;
        }
        return null;
    }
}
