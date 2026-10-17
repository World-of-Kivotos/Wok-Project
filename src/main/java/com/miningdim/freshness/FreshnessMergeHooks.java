package com.miningdim.freshness;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.ItemStackedOnOtherEvent;

/**
 * 同种食物合并的适配层: 拾取入包、点击叠放、配方书追加、粮仓一键存入都调这里, 判据全在 {@link FreshMerge}。
 *
 * 明确**不**合并的地方 (规格「不合并的地方」, 与 {@link FreshnessMergeHooks} 的调用点一一对应):
 * 漏斗、拖拽分发、双击收集、掉落物互相合并、机器与第三方容器接口、创造模式 —— 这些路径不会调到这里。
 */
public final class FreshnessMergeHooks {

    private FreshnessMergeHooks() {
    }

    /**
     * 在容器里找一个「只差新鲜度且满足合并条件」的格子 (拾取与入包路径)。
     * 找不到返回 −1, 让原版继续走它自己的逻辑 (空格子、同章格子)。
     */
    public static int findMergeableSlot(Container container, ItemStack incoming) {
        if (incoming.isEmpty()) {
            return -1;
        }
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (FreshMerge.canMerge(container.getItem(slot), incoming)) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * 原版即将把 incoming 追加进 index 格: 先把两堆的章改成合并后应有的样子, 原版随后 grow 时账就是对的。
     * 件数不动 (原版自己搬), 所以这里只写物品数据。
     */
    public static void prepareAppend(Container container, int index, ItemStack incoming) {
        if (index < 0 || index >= container.getContainerSize() || incoming.isEmpty()) {
            return;
        }
        ItemStack target = container.getItem(index);
        if (target.isEmpty()) {
            return;
        }
        if (FreshMerge.prepareAppend(target, incoming) > 0) {
            container.setItem(index, target);
        }
    }

    /** 配方书往格子里追加: 格子取最老的那批 (剩余取较少、停表取较大)。 */
    public static void prepareRecipeAppend(ItemStack target, ItemStack incoming) {
        FreshMerge.preferOlderBatch(target, incoming);
    }

    /**
     * 点击叠放 (光标上的一堆点到格子里的一堆): 两堆只差新鲜度时自己写回两边并取消原版处理。
     * 原版只认「完全相同才叠」, 不拦的话玩家点一下会被拒 (或者叠不上)。
     */
    /**
     * Shift 快移的合并预跑 (规格 mixin 清单 #9 / {@code AbstractContainerMenu.moveItemStackTo}):
     * 在 vanilla 搬东西**之前**, 先试着把整堆并进"只差新鲜度"的目标槽。
     *
     * 语义刻意与 vanilla 的合并阶段对齐:
     * <ul>
     *   <li>**整堆装得下才并** (与 {@link FreshMerge#prepareAppend} 同一条规矩): 装不下时 vanilla 只会搬一部分,
     *       那部分跟着目标槽的老章走是保守方向, 不该被平均拉年轻 —— 于是这里也不插手, 交给 vanilla 的
     *       "完全相同才叠" 与找空槽阶段;</li>
     *   <li>只认白名单目标 ({@link FreshnessContainers#isMergeTarget}): 机器格子有自己的批次与事务规矩;</li>
     *   <li>并成功了就返回 {@code true} (等价于"整堆搬完"), 由注入点直接结束 vanilla 那一段 —— 玩家按一次
     *       Shift, 该见到的是东西已经进包了。</li>
     * </ul>
     *
     * @return 真并了返回 {@link Boolean#TRUE}; 没插手返回 null (调用方继续走原版)
     */
    public static Boolean mergeShiftMove(AbstractContainerMenu menu, ItemStack stack,
                                         int startIndex, int endIndex, boolean reverseDirection) {
        if (menu == null || stack.isEmpty() || endIndex <= startIndex) {
            return null;
        }
        int index = reverseDirection ? endIndex - 1 : startIndex;
        while (reverseDirection ? index >= startIndex : index < endIndex) {
            Slot slot = menu.slots.get(index);
            if (FreshnessContainers.isMergeTarget(menu, slot) && slot.mayPlace(stack)) {
                ItemStack target = slot.getItem();
                if (FreshMerge.mergeInto(target, stack) > 0) {
                    slot.set(target);
                    slot.setChanged();
                    menu.broadcastChanges();
                    return Boolean.TRUE;
                }
            }
            index += reverseDirection ? -1 : 1;
        }
        return null;
    }
    public static void onStackedOnOther(ItemStackedOnOtherEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) {
            return; // 客户端不算账, 一律以服务端结果为准
        }
        ItemStack target = event.getStackedOnItem();
        ItemStack carried = event.getCarriedItem();
        if (FreshMerge.mergeInto(target, carried) <= 0) {
            return;
        }
        event.getSlot().set(target);
        event.getSlot().setChanged();
        event.getCarriedSlotAccess().set(carried);
        event.setCanceled(true);
        player.containerMenu.broadcastChanges();
    }
}
