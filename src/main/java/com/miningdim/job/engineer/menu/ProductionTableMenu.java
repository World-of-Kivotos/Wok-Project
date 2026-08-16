package com.miningdim.job.engineer.menu;

import com.miningdim.job.engineer.ModEngineerBlocks;
import com.miningdim.job.engineer.ModEngineerMenus;
import com.miningdim.job.engineer.NanoNbt;
import com.miningdim.job.engineer.NanoTier;
import com.miningdim.job.engineer.block.ProductionTableBlock;
import com.miningdim.job.engineer.block.ProductionTableBlockEntity;
import com.miningdim.menu.AbstractMiningMenu;
import com.miningdim.menu.MenuValidity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
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
    private final OutputSlot outputSlot;

    public ProductionTableMenu(int windowId, Inventory inv, BlockPos pos) {
        super(ModEngineerMenus.PRODUCTION_TABLE.get(), windowId, CONTAINER_SLOTS,
                MenuValidity.ofBlock(ContainerLevelAccess.create(inv.player.level(), pos), blockAt(inv, pos)));
        this.blockEntity = inv.player.level().getBlockEntity(pos) instanceof ProductionTableBlockEntity be
                ? be : null;

        if (blockEntity != null) {
            addSlot(new SlotItemHandler(blockEntity.inventory(),
                    ProductionTableBlockEntity.SLOT_INPUT, 60, 79));
            OutputSlot slot = new OutputSlot(blockEntity, ProductionTableBlockEntity.SLOT_OUTPUT, 180, 79);
            addSlot(slot);
            this.outputSlot = slot;
            // 服务端: BE 实时 dataAccess (set 为 no-op, get 读 BE 字段); 客户端: SimpleContainerData 接 setData。
            this.data = inv.player.level().isClientSide
                    ? new SimpleContainerData(ProductionTableBlockEntity.DATA_COUNT())
                    : blockEntity.dataAccess();
        } else {
            // 极端时序 (BE 未同步): 空数据占位, 防 ContainerData 越界。
            this.data = new SimpleContainerData(ProductionTableBlockEntity.DATA_COUNT());
            this.outputSlot = null;
        }
        addDataSlots(this.data);
        addPlayerInventory(inv, 48, 164);
    }

    /**
     * 拦截输出槽的 Shift 取板: 在基类 {@link AbstractMiningMenu#quickMoveStack} 分裂 live 栈之前, 先把
     * pending 标记抹到 live 栈上并留存带 pending 的快照 (beginQuickMove); 分裂出去塞进玩家背包的副本据此
     * 不再带 pending。super 调用可能因移不动 / 一个都没移走而提前 return EMPTY (此时不会调 onTake), 此时
     * try/finally 保证 endQuickMove 把槽内残留 (未真正离槽的部分) 的 pending 用快照原样还原, 防止把尚未
     * 结算的板错误清空 pending 导致玩家后续取残留时经验丢失。
     */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (outputSlot == null || index < 0 || index >= this.slots.size() || this.slots.get(index) != outputSlot) {
            return super.quickMoveStack(player, index);
        }
        outputSlot.beginQuickMove();
        try {
            return super.quickMoveStack(player, index);
        } finally {
            outputSlot.endQuickMove();
        }
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

    @Override
    public boolean stillValid(Player player) {
        if (!super.stillValid(player)) {
            return false;
        }
        return !(player instanceof ServerPlayer serverPlayer)
                || blockEntity == null
                || blockEntity.canAccess(serverPlayer);
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

        /**
         * Shift 取板期间 (beginQuickMove ~ endQuickMove) 为 true。基类 quickMoveStack 用
         * {@code slot.getItem()} 取到的 live 栈引用会被 {@code stack.split(n)} 直接分裂, 分裂出去塞进玩家
         * 背包的副本继承分裂前 live 栈的 NBT —— 必须在分裂前就把 live 栈的 pending 抹掉, 这段窗口期内
         * getItem/onTake 的既有快照刷新与收尾逻辑要让位给 beginQuickMove/endQuickMove 统一处理。
         */
        private boolean quickMoveActive;

        OutputSlot(ProductionTableBlockEntity be, int index, int x, int y) {
            super(be.inventory(), index, x, y);
            this.be = be;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }

        @Override
        public boolean mayPickup(Player player) {
            return !(player instanceof ServerPlayer serverPlayer) || be.canTakeOutput(serverPlayer);
        }

        @Override
        public ItemStack getItem() {
            ItemStack current = super.getItem();
            if (quickMoveActive) {
                // live 栈的 pending 已被 beginQuickMove 抹掉; 若在此仍按常规刷新快照, 会用 pending=false 的
                // live 栈覆盖 beginQuickMove 留存的带 pending 快照, 导致 onTake 结算时误判"已结算过"而漏发经验。
                return current;
            }
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
        public ItemStack remove(int amount) {
            // SlotItemHandler.remove 经 ItemStackHandler.extractItem 返回的是玩家鼠标真正拿到的那一份
            // (handler 内栈的新副本, 与槽内残留是两个独立对象), pending 必须清在这份实栈身上——槽内残留
            // (部分取时) 是尚未结算的板, 不受影响, 继续带 pending 等待下一次取出结算。
            ItemStack removed = super.remove(amount);
            NanoNbt.clearProductionXpPending(removed);
            return removed;
        }

        @Override
        public void onTake(Player player, ItemStack stack) {
            if (player instanceof ServerPlayer serverPlayer) {
                // 实际取走量 = 取出前快照数量 - 移除后槽内残留数量。鼠标/Shift、整取/部分取四条路径统一口径:
                // Shift 整栈取走时基类传入的 stack 是残留 EMPTY, 据其结算会漏算 (engineer-01), 故改用快照差值。
                // 传副本给 onOutputTaken: 该方法会就地清掉传入栈的 pending (结算即清契约), 但 takeSnapshot
                // 字段本身必须保留原始 pending=true —— quickMoveActive 分支下 endQuickMove 还要用它把槽内
                // 残留的 pending 还原回去, 传实体字段会导致这份快照被提前清空, 残留经验永久丢失。
                int takenCount = this.takeSnapshot.getCount() - super.getItem().getCount();
                be.onOutputTaken(serverPlayer, this.takeSnapshot.copy(), takenCount);
            }
            super.onTake(player, stack);
            if (quickMoveActive) {
                // endQuickMove 需要这份带 pending 的快照去还原槽内残留, 此处不能提前覆盖。
                return;
            }
            // SWAP (数字键 1-9 / 副手键 F) 既不经 remove(int) 也不经 quickMoveStack: vanilla doClick 直接把
            // slot.getItem() 拿到的 live 栈本体塞进玩家背包再调 onTake, 此处的 stack 形参就是玩家手上那个
            // 实际对象, 必须在这里兜底清 pending; 鼠标路径的 stack 已在 remove(int) 覆写里清过, 此处幂等。
            NanoNbt.clearProductionXpPending(stack);
            // 取后把快照重置为槽内残留, 作为下一次取出的基线 (空槽则 EMPTY)。
            this.takeSnapshot = super.getItem().copy();
        }

        /**
         * Shift 取板开始: 在基类 moveItemStackTo 分裂 live 栈之前调用。先把带 pending 的 live 栈复制为结算
         * 依据 (takeSnapshot), 再抹掉 live 栈本身的 pending —— 之后 moveItemStackTo 用 stack.split(n) 分裂出
         * 去塞进玩家背包的副本据此不再带 pending, 无需在副本离开菜单后再去追。
         */
        void beginQuickMove() {
            ItemStack current = super.getItem();
            if (current.isEmpty()) {
                return;
            }
            this.takeSnapshot = current.copy();
            this.quickMoveActive = true;
            NanoNbt.clearProductionXpPending(current);
        }

        /**
         * Shift 取板结束: 若还有残留 (部分取), 残留是尚未结算的板, 必须把 beginQuickMove 抹掉的 pending
         * 用留存的快照 NBT 还原回去, 否则玩家下次取残留时会读到 pending=false 漏发经验。就地改 NBT 不经
         * ItemStackHandler 的 setStackInSlot, 不会触发 onContentsChanged 标脏, 故经 {@link #set} 显式标脏。
         */
        void endQuickMove() {
            if (!quickMoveActive) {
                return;
            }
            this.quickMoveActive = false;
            ItemStack residual = super.getItem();
            if (!residual.isEmpty()) {
                CompoundTag snapshotTag = this.takeSnapshot.getTag();
                residual.setTag(snapshotTag == null ? null : snapshotTag.copy());
                this.set(residual);
            }
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

    public int quality() {
        return data.get(ProductionTableBlockEntity.DATA_QUALITY);
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

    public int elapsedTicks() {
        return data.get(ProductionTableBlockEntity.DATA_ELAPSED_TICKS);
    }

    public int requiredTicks() {
        return data.get(ProductionTableBlockEntity.DATA_REQUIRED_TICKS);
    }
}
