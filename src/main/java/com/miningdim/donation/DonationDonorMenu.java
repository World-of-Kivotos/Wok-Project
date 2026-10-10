package com.miningdim.donation;

import com.miningdim.menu.AbstractMiningMenu;
import com.miningdim.menu.MenuValidity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 捐赠者界面: 3x3 投入格 + 玩家背包 (布局与原版发射器一致, 客户端直接复用发射器贴图)。
 *
 * 投入格是每次会话独享的临时容器, 不属于方块实体。服务端每次 {@link #broadcastChanges} (每 tick 与每次点击
 * 之后各一次) 把投入格里的东西尽量转进内部仓库; 仓库放不下的余量留在投入格, 捐赠者可以自己拿回;
 * 关闭界面时先再转一次, 剩下的全部退还 (背包满则掉在脚下, 原版 clearContainer 语义)。
 *
 * 捐赠者看不到仓库: 本菜单根本没有绑定仓库的槽位, 客户端也就收不到仓库内容。
 */
public final class DonationDonorMenu extends AbstractMiningMenu {

    public static final int INPUT_SLOTS = 9;

    /** 发射器贴图里 3x3 格的左上角与玩家背包起点 (与原版 DispenserMenu 一致)。 */
    private static final int GRID_X = 62;
    private static final int GRID_Y = 17;
    private static final int SLOT_PX = 18;
    private static final int PLAYER_INV_X = 8;
    private static final int PLAYER_INV_Y = 84;

    /** 服务端为真实方块实体; 客户端为 null (客户端不需要它, 投入格内容由原版容器同步)。 */
    @Nullable
    private final DonationBoxBlockEntity blockEntity;
    private final SimpleContainer input = new SimpleContainer(INPUT_SLOTS);
    private final DonationSession session = new DonationSession();

    /** 服务端构造。 */
    public DonationDonorMenu(int windowId, Inventory playerInventory, DonationBoxBlockEntity blockEntity) {
        super(DonationRegistry.DONOR_MENU.get(), windowId, INPUT_SLOTS,
                MenuValidity.ofBlock(ContainerLevelAccess.create(blockEntity.getLevel(), blockEntity.getBlockPos()),
                        blockEntity.getBlockState().getBlock()));
        this.blockEntity = blockEntity;
        layout(playerInventory);
    }

    /** 客户端构造 (MenuType 工厂读 extraData 里的 BlockPos 后调用)。 */
    public DonationDonorMenu(int windowId, Inventory playerInventory, BlockPos pos) {
        super(DonationRegistry.DONOR_MENU.get(), windowId, INPUT_SLOTS,
                MenuValidity.ofBlock(ContainerLevelAccess.create(playerInventory.player.level(), pos),
                        playerInventory.player.level().getBlockState(pos).getBlock()));
        this.blockEntity = null;
        layout(playerInventory);
    }

    private void layout(Inventory playerInventory) {
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                addSlot(new InputSlot(input, col + row * 3, GRID_X + col * SLOT_PX, GRID_Y + row * SLOT_PX));
            }
        }
        addPlayerInventory(playerInventory, PLAYER_INV_X, PLAYER_INV_Y);
    }

    /** 投入格: 只接受白名单物品 (Shift 点击走 moveItemStackTo, 同样经过这里)。 */
    private static final class InputSlot extends Slot {
        InputSlot(SimpleContainer container, int index, int x, int y) {
            super(container, index, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return DonationWhitelist.accepts(stack);
        }
    }

    private boolean boxAlive() {
        return blockEntity != null && !blockEntity.isRemoved() && blockEntity.getLevel() != null
                && blockEntity.getLevel().getBlockEntity(blockEntity.getBlockPos()) == blockEntity;
    }

    /**
     * 把投入格转进仓库, 返回本次转入的件数。方块已被移除时不转: 那时仓库已经散落一空,
     * 再往里塞就等于把捐赠者的物品凭空销毁。
     */
    int flushInput() {
        if (!boxAlive()) {
            return 0;
        }
        int moved = 0;
        for (int i = 0; i < INPUT_SLOTS; i++) {
            ItemStack stack = input.getItem(i);
            if (stack.isEmpty() || !DonationWhitelist.accepts(stack)) {
                continue;
            }
            ItemStack remainder = blockEntity.acceptDonation(stack.copy());
            int accepted = stack.getCount() - remainder.getCount();
            if (accepted > 0) {
                session.deposit(stack.getItem(), accepted);
                input.setItem(i, remainder);
                moved += accepted;
            }
        }
        return moved;
    }

    @Override
    public void broadcastChanges() {
        if (blockEntity != null) {
            flushInput();
        }
        super.broadcastChanges();
    }

    @Override
    public boolean stillValid(Player player) {
        return super.stillValid(player) && (blockEntity == null || !blockEntity.isRemoved());
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (blockEntity == null) {
            return;
        }
        flushInput();
        clearContainer(player, input);
        blockEntity.commitSession(player, session);
    }

    /** 测试与界面读取投入格用。 */
    public SimpleContainer input() {
        return input;
    }
}
