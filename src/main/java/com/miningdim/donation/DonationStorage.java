package com.miningdim.donation;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemStackHandler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 捐赠箱内部仓库: 固定 54 格 (6x9, 与原版大箱子同尺寸), 每一次 insertItem 都过 {@link DonationWhitelist}。
 *
 * 这个 handler 本身可取可放, 只交给管理界面的槽位使用; 对外 (漏斗、管道) 暴露的是只进不出的
 * {@link DonationAutomationHandler} 包装, 永远不直接暴露本类。{@code setStackInSlot} 不做白名单校验 ——
 * 它只被存档读回和原版槽位写回调用, 而槽位写回之前已经过 mayPlace。
 */
public final class DonationStorage extends ItemStackHandler {

    public static final int SLOTS = 54;

    private final Runnable onChanged;

    public DonationStorage(Runnable onChanged) {
        super(SLOTS);
        this.onChanged = onChanged;
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return DonationWhitelist.accepts(stack);
    }

    @Override
    protected void onContentsChanged(int slot) {
        onChanged.run();
    }

    /** 已占用的格数 (账本界面显示仓库余量用)。 */
    public int usedSlots() {
        int used = 0;
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty()) {
                used++;
            }
        }
        return used;
    }

    /**
     * 按物品汇总数量。管理界面在每次点击前后各取一次快照求差, 以此把存取精确记到点击者名下;
     * 仓库里的物品都过了"不带 NBT"这条白名单, 所以只按 Item 聚合不会把两种不同的栈混为一谈。
     */
    public Map<Item, Integer> countByItem() {
        Map<Item, Integer> counts = new LinkedHashMap<>();
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty()) {
                counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return counts;
    }

    /** 某物品的总数量 (测试与指令查询用)。 */
    public int count(Item item) {
        int total = 0;
        for (ItemStack stack : stacks) {
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * 取出全部内容并清空仓库 (方块被移除时散落用)。返回的是原栈对象本身, 调用方负责把它们放进世界;
     * 清空在返回前完成, 之后任何仍持有本仓库引用的界面都只能看到空格。
     */
    public List<ItemStack> drainAll() {
        List<ItemStack> drained = new ArrayList<>();
        for (int i = 0; i < stacks.size(); i++) {
            ItemStack stack = stacks.get(i);
            if (!stack.isEmpty()) {
                drained.add(stack);
                stacks.set(i, ItemStack.EMPTY);
            }
        }
        if (!drained.isEmpty()) {
            onChanged.run();
        }
        return drained;
    }
}
