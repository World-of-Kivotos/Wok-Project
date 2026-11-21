package com.miningdim.job.brewer.cellar;

import com.miningdim.job.brewer.BrewerItems;
import com.miningdim.job.brewer.BrewerConstants;
import com.miningdim.job.brewer.WineNbt;
import com.miningdim.menu.AbstractMiningMenu;
import com.miningdim.menu.MenuValidity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.SlotItemHandler;

/**
 * 酒窖箱容器菜单 (酿酒师 阶段 4; 复用 {@link AbstractMiningMenu} 脚手架)。容器槽: 12 酒槽 (3x4, 仅接受
 * {@link WineNbt#isWine} 的酒) + 1 干小麦燃料槽 (仅 {@link BrewerItems#DRIED_WHEAT}) + 玩家 36 槽。
 *
 * 双构造 (照调味台范式): 服务端由 {@link WineCellarBlockEntity#createMenu} 调 (持 BE 引用直接绑槽); 客户端经
 * {@link com.miningdim.menu.ModMenus#blockMenuType} extraData 读 BlockPos 后重建 (从世界取 BE, 缺失则建临时
 * 实体避免 NPE, 槽空不影响渲染)。燃料总量通过 ContainerData 同步。
 */
public final class WineCellarMenu extends AbstractMiningMenu {

    /** 容器槽数 (12 酒槽 + 1 燃料槽)。 */
    public static final int CONTAINER_SLOTS = WineCellarBlockEntity.TOTAL_SLOTS;

    // 酒槽 3x4 网格布局 (12 槽, 相对界面左上角)。
    private static final int WINE_GRID_COLS = 4;
    private static final int WINE_GRID_ROWS = 3;
    private static final int WINE_GRID_X = 44;
    private static final int WINE_GRID_Y = 18;
    private static final int SLOT_PX = 18;

    // 燃料槽布局 (酒槽网格右侧)。
    private static final int FUEL_SLOT_X = 134;
    private static final int FUEL_SLOT_Y = 36;

    public static final int DEPOSIT = 0;
    public static final int WITHDRAW = 1;
    private final WineCellarBlockEntity blockEntity;
    private final ContainerData fuelData;

    /** 服务端构造 (由 BlockEntity.createMenu 调)。 */
    public WineCellarMenu(int windowId, Inventory playerInv, WineCellarBlockEntity be) {
        super(WineCellarRegistry.WINE_CELLAR_MENU.get(), windowId, CONTAINER_SLOTS,
                MenuValidity.ofBlock(
                        ContainerLevelAccess.create(be.getLevel(), be.getBlockPos()),
                        be.getBlockState().getBlock()));
        this.blockEntity = be;
        this.fuelData = new ContainerData() {
            public int get(int index) { return index == 0 ? be.fuelTotal() & 0xffff : be.fuelTotal() >>> 16; }
            public void set(int index, int value) { }
            public int getCount() { return 2; }
        };
        addDataSlots(fuelData);
        addContainerSlots(be);
        addPlayerInventory(playerInv, 8, 84);
    }

    /** 客户端构造 (blockMenuType extraData 读 BlockPos 后调; 从世界取 BE, 缺失建临时实体)。 */
    public WineCellarMenu(int windowId, Inventory playerInv, BlockPos pos) {
        super(WineCellarRegistry.WINE_CELLAR_MENU.get(), windowId, CONTAINER_SLOTS,
                MenuValidity.ofBlock(
                        ContainerLevelAccess.create(playerInv.player.level(), pos),
                        playerInv.player.level().getBlockState(pos).getBlock()));
        this.blockEntity = clientBlockEntity(playerInv, pos);
        this.fuelData = new SimpleContainerData(2);
        addDataSlots(fuelData);
        addContainerSlots(blockEntity);
        addPlayerInventory(playerInv, 8, 84);
    }

    @Override
    public void clicked(int slot, int button, ClickType clickType, Player player) {
        if (blockEntity.isLocked()) return;
        blockEntity.settleNow();
        super.clicked(slot, button, clickType, player);
    }

    @Override
    public boolean stillValid(Player player) {
        return !blockEntity.isLocked() && super.stillValid(player);
    }

    public int fuelTotal() {
        return (fuelData.get(0) & 0xffff) | ((fuelData.get(1) & 0xffff) << 16);
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (player.level().isClientSide || !stillValid(player) || player.containerMenu != this
                || (id != DEPOSIT && id != WITHDRAW)) return false;
        blockEntity.settleNow();
        if (id == DEPOSIT) blockEntity.depositFuel();
        else blockEntity.withdrawFuel();
        return true;
    }

    private void addContainerSlots(WineCellarBlockEntity be) {
        // 12 酒槽 3x4 (仅接受酒)。
        for (int row = 0; row < WINE_GRID_ROWS; row++) {
            for (int col = 0; col < WINE_GRID_COLS; col++) {
                int index = row * WINE_GRID_COLS + col;
                int x = WINE_GRID_X + col * SLOT_PX;
                int y = WINE_GRID_Y + row * SLOT_PX;
                this.addSlot(new SlotItemHandler(be.inventory(), index, x, y) {
                    @Override
                    public boolean mayPlace(ItemStack stack) {
                        return WineNbt.isWine(stack);
                    }
                });
            }
        }
        // 燃料槽 (仅干小麦)。
        this.addSlot(new SlotItemHandler(be.inventory(), WineCellarBlockEntity.FUEL_SLOT, FUEL_SLOT_X, FUEL_SLOT_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return stack.is(BrewerItems.DRIED_WHEAT.get()) && getMaxStackSize() > getItem().getCount();
            }

            @Override
            public int getMaxStackSize() {
                int reserve = fuelTotal() - getItem().getCount();
                return Math.min(64, BrewerConstants.FUEL_SLOT_CAPACITY - reserve);
            }

            @Override
            public int getMaxStackSize(ItemStack stack) { return getMaxStackSize(); }
        });
    }

    /** 客户端侧从世界取 BlockEntity (绑槽容器用); 缺失时建临时实体避免 NPE (槽空, 不影响渲染)。 */
    private static WineCellarBlockEntity clientBlockEntity(Inventory inv, BlockPos pos) {
        if (inv.player.level().getBlockEntity(pos) instanceof WineCellarBlockEntity be) {
            return be;
        }
        return new WineCellarBlockEntity(pos, inv.player.level().getBlockState(pos));
    }

    public WineCellarBlockEntity blockEntity() {
        return blockEntity;
    }
}
