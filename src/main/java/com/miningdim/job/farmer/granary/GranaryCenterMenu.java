package com.miningdim.job.farmer.granary;

import com.miningdim.menu.AbstractMiningMenu;
import com.miningdim.menu.MenuValidity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 账台的多页签界面 (规格「账台的多页签界面」)。
 *
 * **没有容器格**: 这一页显示的全是报告 (结构、我的囤、成员、开放名单、流水), 数据一面在服务端, 界面上放格子
 * 只会让人以为"能从这里把东西塞进粮仓"。玩家背包照常摆在下面 (方便对着清单清点), 但往来只经
 * {@code /granary store} 与囤自己的 27 格界面 —— 与「粮仓不挂物品接口」同一条纪律。
 *
 * 页切换走 {@link GranaryCenterPageC2S}; 内容由服务端现算 ({@link GranaryCenterPageS2C})。
 */
public final class GranaryCenterMenu extends AbstractMiningMenu {

    /** 玩家背包 (27 格) 在界面里的起始行距: 与囤界面同款坐标。 */
    private static final int PLAYER_INV_Y = 84;
    private static final int PLAYER_INV_X = 8;
    private static final int HOTBAR_Y = 142;

    private final BlockPos centerPos;

    /** 服务端构造 (菜单由方块打开时走它; 共享脚手架的 blockMenuType 工厂按这个签名反射调用)。 */
    public GranaryCenterMenu(int windowId, Inventory playerInventory, BlockPos centerPos) {
        // 没有自己的容器格 (0 格), 有效性仍按"玩家站在账台边上"判 (与囤界面同一把尺)。
        super(GranaryMenus.CENTER.get(), windowId, 0, MenuValidity.ofBlock(
                net.minecraft.world.inventory.ContainerLevelAccess.create(playerInventory.player.level(), centerPos),
                playerInventory.player.level().getBlockState(centerPos).getBlock()));
        this.centerPos = centerPos;
        addPlayerInventory(playerInventory);
    }

    /** 账台坐标 (服务端现算页内容时要用它找那座粮仓)。 */
    public BlockPos centerPos() {
        return centerPos;
    }

    private void addPlayerInventory(Inventory playerInventory) {
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9,
                        PLAYER_INV_X + col * 18, PLAYER_INV_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInventory, col, PLAYER_INV_X + col * 18, HOTBAR_Y));
        }
    }

    /**
     * Shift 快移: 界面上没有本模块的格子, 于是它退化成"背包与快捷栏之间搬运" (与玩家在纯报告界面上的预期一致)。
     */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();
        boolean fromInventory = index < 27;
        int targetStart = fromInventory ? 27 : 0;
        int targetEnd = fromInventory ? 36 : 27;
        if (!this.moveItemStackTo(stack, targetStart, targetEnd, false)) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            slot.set(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return copy;
    }

    @Override
    public boolean stillValid(Player player) {
        return player.distanceToSqr(centerPos.getX() + 0.5D, centerPos.getY() + 0.5D,
                centerPos.getZ() + 0.5D) <= 64.0D;
    }

}