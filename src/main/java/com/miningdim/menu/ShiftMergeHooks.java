package com.miningdim.menu;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/**
 * Shift 快移合并的接缝 (Spoilage_Granary_DesignSpec mixin 清单 #9)。
 *
 * 为什么要有这一层而不是让菜单基类直接调腐烂底座: {@code AbstractMiningMenu} 整段重写了 vanilla 的
 * {@code moveItemStackTo}, 而它属于 **wok-core** —— 依赖方向是"业务模块依赖核心", 让核心反过来依赖
 * 腐烂底座会把方向掰反 (模块边界校验器也会当场拒绝)。所以核心只留一个可注册的空接缝:
 * 默认什么都不做 (行为与 vanilla 一致), 由腐烂底座在装配时把自己的合并函数挂上来。
 *
 * 同一份接缝给婚姻背包那处覆写共用, 于是主 MOD 的三处入口 (vanilla 那一支走 mixin、这两处覆写走这里)
 * 用的是**同一个**判据。
 */
public final class ShiftMergeHooks {

    private ShiftMergeHooks() {
    }

    /** 合并函数: 真并了返回 {@code Boolean.TRUE}; 没插手返回 null (调用方继续走原版)。 */
    @FunctionalInterface
    public interface ShiftMerge {

        Boolean merge(AbstractContainerMenu menu, ItemStack stack, int startIndex, int endIndex,
                      boolean reverseDirection);
    }

    private static volatile ShiftMerge hook;

    /** 装配期注册 (腐烂底座在 register 里挂一次); 传 null 可卸下 (用例复位用)。 */
    public static void register(ShiftMerge merge) {
        hook = merge;
    }

    /** 问一遍接缝; 没人注册就返回 null (等于原版行为)。 */
    public static Boolean tryMerge(AbstractContainerMenu menu, ItemStack stack, int startIndex, int endIndex,
                                   boolean reverseDirection) {
        ShiftMerge current = hook;
        if (current == null) {
            return null;
        }
        return current.merge(menu, stack, startIndex, endIndex, reverseDirection);
    }
}
