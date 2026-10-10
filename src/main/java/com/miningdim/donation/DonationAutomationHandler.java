package com.miningdim.donation;

import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;
import org.jetbrains.annotations.NotNull;

/**
 * 对外暴露给漏斗、管道等自动化的只进不出视图。
 *
 *  - insertItem: 先过 {@link DonationWhitelist} 再转交内部仓库; 真实插入 (非模拟) 的件数记进流水的自动化聚合窗口。
 *  - extractItem: 恒返回空栈。任何面都抽不出东西 —— 这是"只进不出"的全部保证, 不依赖调用方自觉。
 *  - getStackInSlot: 返回副本。IItemHandler 契约本就禁止改返回值, 但有些模组的抽取实现会直接 shrink 它;
 *    返回副本让这种写法最多改到一个临时对象。
 *
 * 底面 (Direction.DOWN) 与空面 (side == null, Jade 等信息显示模组的读法) 连这个视图都不给,
 * 见 {@link DonationBoxBlockEntity#getCapability}。
 */
final class DonationAutomationHandler implements IItemHandler {

    private final DonationBoxBlockEntity owner;

    DonationAutomationHandler(DonationBoxBlockEntity owner) {
        this.owner = owner;
    }

    @Override
    public int getSlots() {
        return owner.storage().getSlots();
    }

    @Override
    public @NotNull ItemStack getStackInSlot(int slot) {
        return owner.storage().getStackInSlot(slot).copy();
    }

    @Override
    public @NotNull ItemStack insertItem(int slot, @NotNull ItemStack stack, boolean simulate) {
        if (stack.isEmpty() || !DonationWhitelist.accepts(stack) || owner.isRemoved()) {
            return stack;
        }
        ItemStack remainder = owner.storage().insertItem(slot, stack, simulate);
        if (!simulate) {
            int inserted = stack.getCount() - remainder.getCount();
            if (inserted > 0) {
                owner.onAutomationInserted(stack.getItem(), inserted);
            }
        }
        return remainder;
    }

    @Override
    public @NotNull ItemStack extractItem(int slot, int amount, boolean simulate) {
        return ItemStack.EMPTY;
    }

    @Override
    public int getSlotLimit(int slot) {
        return owner.storage().getSlotLimit(slot);
    }

    @Override
    public boolean isItemValid(int slot, @NotNull ItemStack stack) {
        return DonationWhitelist.accepts(stack);
    }
}
