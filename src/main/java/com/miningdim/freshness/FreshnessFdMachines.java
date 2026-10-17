package com.miningdim.freshness;

import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;

import java.util.List;

/**
 * FD 机器里「放着不加热也照常烂」(Spoilage_Granary_DesignSpec「可选配置」#12、#14、#15)。
 *
 * <p>这三台机器（煎锅、炉灶、厨锅）各自有一个内部物品栏: 东西放进去之后**不在任何观察点上** ——
 * 玩家背包、箱子那类白名单容器、掉落物都扫不到它。不补这一处, 机器就是免费的冰箱 (规格点名的漏洞)。
 * 补法是在它们的 {@code cookingTick} 上每次结算一遍内部物品栏 (煎锅/炉灶/厨锅都是每 tick 跑一次的
 * 静态方法), 于是"放进去就停表"这件事不成立, 加热与否都一样会烂。</p>
 *
 * <p>判定与替换全部复用 {@link FreshnessSettle#settleSlot}: 这里不另写一套「什么算烂」。</p>
 */
public final class FreshnessFdMachines {

    private FreshnessFdMachines() {
    }

    /**
     * 结算一个 Forge 物品栏 (FD 三台机器的内部物品栏都是这一种)。
     *
     * <p>换掉的格数返回给调用方 (用例与统计用)。注意写回: 有些 handler 的 {@code getStackInSlot}
     * 返回的是副本, 光在副本上盖章不会落盘 —— 所以"盖章"与"替换"两条路都写回一次。</p>
     */
    public static int settle(IItemHandler handler, long nowMinute, List<ItemStack> returns) {
        if (handler == null) {
            return 0;
        }
        // 只有"可写"的物品栏才能落盘: 取不到就退化成"只在拿到的栈上盖章"(仍在的调用方会自己写回)。
        IItemHandlerModifiable writable = handler instanceof IItemHandlerModifiable modifiable ? modifiable : null;
        boolean replaceRotten = FreshnessConfig.replacementEnabled();
        int rotted = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (stack.isEmpty()) {
                continue;
            }
            ItemStack replacement = FreshnessSettle.settleSlot(stack, nowMinute, replaceRotten, returns);
            if (!replacement.isEmpty()) {
                if (writable != null) {
                    writable.setStackInSlot(slot, replacement);
                }
                rotted++;
            } else if (writable != null && FreshnessData.isStamped(stack)) {
                writable.setStackInSlot(slot, stack);
            }
        }
        return rotted;
    }
}
