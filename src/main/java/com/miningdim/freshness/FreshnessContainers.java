package com.miningdim.freshness;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;

/**
 * 「哪些容器参与合并」的唯一判据 (规格「同种食物合并」: 只对**玩家背包**和白名单容器生效)。
 *
 * 白名单只在两处: 观察点结算 (开/关容器时给整箱盖章) 与 Shift 快移合并。两处必须**同一份名单** ——
 * 名单宽一处窄一处, 就会出现"开了箱结算、Shift 却并进机器里"这种半通不通的行为。
 *
 * 白名单目前是四种原版容器界面: 箱子 / 潜影盒 / 漏斗 / 发射器。机器 (熔炉、酿酒台、粮仓囤、各职业台)
 * 一律**不是**合并目标: 那些格子有自己的批次与事务规矩, 让 Shift 快移并进去会绕开它们。
 */
public final class FreshnessContainers {

    private FreshnessContainers() {
    }

    /** 这个界面是不是白名单容器 (箱子 / 潜影盒 / 漏斗 / 发射器)。 */
    public static boolean isWhitelistedMenu(AbstractContainerMenu menu) {
        return menu instanceof ChestMenu
                || menu instanceof ShulkerBoxMenu
                || menu instanceof HopperMenu
                || menu instanceof DispenserMenu;
    }

    /**
     * 这个槽能不能当合并目标: 玩家背包的槽 (含快捷栏、副手、盔甲 —— 它们都在同一个 {@link Inventory} 里),
     * 或者白名单容器界面里的槽。
     */
    public static boolean isMergeTarget(AbstractContainerMenu menu, Slot slot) {
        if (menu == null || slot == null) {
            return false;
        }
        return slot.container instanceof Inventory || isWhitelistedMenu(menu);
    }
}
