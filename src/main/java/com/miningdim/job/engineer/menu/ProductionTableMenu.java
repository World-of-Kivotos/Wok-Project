package com.miningdim.job.engineer.menu;

import com.miningdim.job.engineer.ModEngineerBlocks;
import com.miningdim.job.engineer.ModEngineerMenus;
import com.miningdim.job.engineer.NanoTier;
import com.miningdim.job.engineer.block.ProductionTableBlock;
import com.miningdim.job.engineer.block.ProductionTableBlockEntity;
import com.miningdim.menu.AbstractMiningMenu;
import com.miningdim.menu.MenuValidity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.SlotItemHandler;

/**
 * 生产台容器 (MillenniumEngineer_Mod_DesignSpec 四 / 10.5)。继承共享 {@link AbstractMiningMenu} (正确
 * quickMoveStack + stillValid 已由基类经 {@link MenuValidity#ofBlock} 实现)。
 *
 * 槽位: 输入矿石槽 (可放可取) + 输出板槽 (只取不放, 取出经 {@link ProductionTableBlockEntity#onOutputTaken}
 * 结算生产经验) + 玩家 36 槽。进度/品质/游标/绿区/锁/机器档/选中档 经 {@link ContainerData} 同步给客户端
 * (服务端用 BE 的实时 dataAccess; 客户端用 SimpleContainerData 接收 setData 同步, 与原版熔炉同范式)。
 *
 * 按钮路由 (clickMenuButton; 不新开网络包, 走原版 AbstractContainerMenu.clickMenuButton):
 *  - [0, tier count): 选档 tierIndex (服务端权威重校三道门);
 *  - 100:   一次校准点击 (服务端判窗口内才算命中);
 *  - 200:   切锁 (仅主人)。
 */
public final class ProductionTableMenu extends AbstractMiningMenu {

    private static final int CONTAINER_SLOTS = 2;

    public static final int BUTTON_CALIBRATE = 100;
    public static final int BUTTON_TOGGLE_LOCK = 200;

    private final ProductionTableBlockEntity blockEntity;
    private final ContainerData data;

    public ProductionTableMenu(int windowId, Inventory inv, BlockPos pos) {
        super(ModEngineerMenus.PRODUCTION_TABLE.get(), windowId, CONTAINER_SLOTS,
                MenuValidity.ofBlock(ContainerLevelAccess.create(inv.player.level(), pos), blockAt(inv, pos)));
        this.blockEntity = inv.player.level().getBlockEntity(pos) instanceof ProductionTableBlockEntity be
                ? be : null;

        if (blockEntity != null) {
            addSlot(new SlotItemHandler(blockEntity.inventory(),
                    ProductionTableBlockEntity.SLOT_INPUT, 44, 35));
            addSlot(new OutputSlot(blockEntity, ProductionTableBlockEntity.SLOT_OUTPUT, 116, 35));
            // 服务端: BE 实时 dataAccess (set 为 no-op, get 读 BE 字段); 客户端: SimpleContainerData 接 setData。
            this.data = inv.player.level().isClientSide
                    ? new SimpleContainerData(ProductionTableBlockEntity.DATA_COUNT())
                    : blockEntity.dataAccess();
        } else {
            // 极端时序 (BE 未同步): 空数据占位, 防 ContainerData 越界。
            this.data = new SimpleContainerData(ProductionTableBlockEntity.DATA_COUNT());
        }
        addDataSlots(this.data);
        addPlayerInventory(inv, 8, 84);
    }

    /** 取 pos 处方块作 stillValid 校验目标; 非生产台时退回低档块占位 (块不匹配将判 false 关闭界面)。 */
    private static net.minecraft.world.level.block.Block blockAt(Inventory inv, BlockPos pos) {
        net.minecraft.world.level.block.Block block = inv.player.level().getBlockState(pos).getBlock();
        if (block instanceof ProductionTableBlock) {
            return block;
        }
        return ModEngineerBlocks.table(NanoTier.LOW).get();
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (!(player instanceof ServerPlayer serverPlayer) || blockEntity == null) {
            return false;
        }
        if (id >= 0 && id < NanoTier.values().length) {
            return blockEntity.trySelectTier(NanoTier.byIndex(id), serverPlayer);
        }
        if (id == BUTTON_CALIBRATE) {
            blockEntity.onCalibrationClick(serverPlayer);
            return true;
        }
        if (id == BUTTON_TOGGLE_LOCK) {
            if (blockEntity.isOwner(player)) {
                blockEntity.toggleLocked();
                return true;
            }
            return false;
        }
        return false;
    }

    /** 输出板槽: 只取不放; 取出时结算生产经验 (7.4)。 */
    private static final class OutputSlot extends SlotItemHandler {
        private final ProductionTableBlockEntity be;

        /**
         * 取出前的板栈快照。基类 {@link AbstractMiningMenu#quickMoveStack} 与 vanilla {@code Slot.safeTake} 都在
         * 移除板栈前先读 {@link #getItem()}, 故此处随每次读取刷新快照; 移除后 {@link #onTake} 据
         * (快照数量 - 残留数量) 算本次实际取走量。修复 Shift 整栈取板时基类传入 onTake 的是残留 EMPTY 栈、
         * 据其结算导致经验静默丢失 + pending 永不清的缺陷 (engineer-01)。
         */
        private ItemStack takeSnapshot = ItemStack.EMPTY;

        OutputSlot(ProductionTableBlockEntity be, int index, int x, int y) {
            super(be.inventory(), index, x, y);
            this.be = be;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }

        @Override
        public ItemStack getItem() {
            ItemStack current = super.getItem();
            // 仅在板栈"非缩减"读取时刷新快照 (首次/换物/新产出填充使数量增大); 不在移除后用残留小栈覆盖快照。
            // 否则 vanilla Slot.tryRemove 在 remove() 之后还会再读一次 getItem() (判残留是否清空), 那次读取的
            // 残留小栈会把取出前的满栈快照覆盖掉, 令 onTake 的 (快照数量 - 残留数量) 误算为 0, 漏结算。
            if (!current.isEmpty()
                    && (takeSnapshot.isEmpty()
                        || !ItemStack.isSameItemSameTags(current, takeSnapshot)
                        || current.getCount() > takeSnapshot.getCount())) {
                this.takeSnapshot = current.copy();
            }
            return current;
        }

        @Override
        public void onTake(Player player, ItemStack stack) {
            if (player instanceof ServerPlayer serverPlayer) {
                // 实际取走量 = 取出前快照数量 - 移除后槽内残留数量。鼠标/Shift、整取/部分取四条路径统一口径:
                // Shift 整栈取走时基类传入的 stack 是残留 EMPTY, 据其结算会漏算 (engineer-01), 故改用快照差值。
                int takenCount = this.takeSnapshot.getCount() - super.getItem().getCount();
                be.onOutputTaken(serverPlayer, this.takeSnapshot, takenCount);
            }
            super.onTake(player, stack);
            // 取后把快照重置为槽内残留, 作为下一次取出的基线 (空槽则 EMPTY)。
            this.takeSnapshot = super.getItem().copy();
        }
    }

    // ---- 客户端读同步值 (Screen 渲染用) ----

    public int machineTierIndex() {
        return data.get(ProductionTableBlockEntity.DATA_MACHINE_TIER);
    }

    public int progress() {
        return data.get(ProductionTableBlockEntity.DATA_PROGRESS);
    }

    public int cursor() {
        return data.get(ProductionTableBlockEntity.DATA_CURSOR);
    }

    public int greenStart() {
        return data.get(ProductionTableBlockEntity.DATA_GREEN);
    }

    public boolean isLocked() {
        return data.get(ProductionTableBlockEntity.DATA_LOCKED) != 0;
    }

    public int selectedTierIndex() {
        return data.get(ProductionTableBlockEntity.DATA_SELECTED_TIER);
    }
}
