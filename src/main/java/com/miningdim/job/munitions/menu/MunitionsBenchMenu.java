package com.miningdim.job.munitions.menu;

import com.miningdim.job.munitions.ModMunitionsBlocks;
import com.miningdim.job.munitions.ModMunitionsMenus;
import com.miningdim.job.munitions.MunitionsCaliber;
import com.miningdim.job.munitions.block.MunitionsBenchBlock;
import com.miningdim.job.munitions.block.MunitionsBenchBlockEntity;
import com.miningdim.menu.AbstractMiningMenu;
import com.miningdim.menu.MenuValidity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.SlotItemHandler;

/**
 * 军火台容器 (Munitions_Job_DesignSpec 五/十章)。继承共享 {@link AbstractMiningMenu} (正确 quickMoveStack +
 * stillValid 已由基类经 {@link MenuValidity#ofBlock} 实现)。
 *
 * 槽位: 料槽 底火/弹壳/弹头/发射药 (可放可取, isItemValid 限料种) + 输出缓冲槽 (只取不放, 取出经
 * {@link MunitionsBenchBlockEntity#onOutputTaken} 回收缓冲计数) + 玩家 36 槽。选中口径/缓冲发数/缓冲上限/锁/提炼
 * 解锁 经 {@link ContainerData} 同步 (服务端用 BE 实时 dataAccess; 客户端用 SimpleContainerData)。
 *
 * 打开即触发一次离线追算结算 (主人在线时一次性补产; 见 {@link MunitionsBenchBlockEntity#onAccess})。
 *
 * 按钮路由 (clickMenuButton; 走原版通道, 不新开网络包):
 *  - [0, caliber count): 选口径 caliberIndex (服务端权威重校等级门);
 *  - 200: 切锁 (仅主人)。
 */
public final class MunitionsBenchMenu extends AbstractMiningMenu {

    private static final int CONTAINER_SLOTS = 5;

    public static final int BUTTON_TOGGLE_LOCK = 200;
    public static final int BUTTON_START_CRAFT = 210;
    public static final int BUTTON_CANCEL_CRAFT = 211;
    public static final int BUTTON_TOGGLE_CONTINUOUS = 212;

    private final MunitionsBenchBlockEntity blockEntity;
    private final ContainerData data;

    public MunitionsBenchMenu(int windowId, Inventory inv, BlockPos pos) {
        super(ModMunitionsMenus.MUNITIONS_BENCH.get(), windowId, CONTAINER_SLOTS,
                MenuValidity.ofBlock(ContainerLevelAccess.create(inv.player.level(), pos), blockAt(inv, pos)));
        this.blockEntity = inv.player.level().getBlockEntity(pos) instanceof MunitionsBenchBlockEntity be
                ? be : null;

        if (blockEntity != null) {
            // 打开即结算 (主人在线一次性补产)。仅服务端 (客户端无权威 BE 逻辑)。
            if (!inv.player.level().isClientSide && inv.player instanceof ServerPlayer serverPlayer) {
                blockEntity.onAccess(serverPlayer);
            }
            addSlot(new SlotItemHandler(blockEntity.inventory(),
                    MunitionsBenchBlockEntity.SLOT_PRIMER, 296, 158));
            addSlot(new SlotItemHandler(blockEntity.inventory(),
                    MunitionsBenchBlockEntity.SLOT_CASING, 322, 158));
            addSlot(new SlotItemHandler(blockEntity.inventory(),
                    MunitionsBenchBlockEntity.SLOT_BULLET_HEAD, 296, 184));
            addSlot(new SlotItemHandler(blockEntity.inventory(),
                    MunitionsBenchBlockEntity.SLOT_PROPELLANT, 322, 184));
            addSlot(new OutputSlot(blockEntity, MunitionsBenchBlockEntity.SLOT_OUTPUT, 316, 84));
            this.data = inv.player.level().isClientSide
                    ? new SimpleContainerData(MunitionsBenchBlockEntity.DATA_COUNT())
                    : blockEntity.dataAccess();
        } else {
            this.data = new SimpleContainerData(MunitionsBenchBlockEntity.DATA_COUNT());
        }
        addDataSlots(this.data);
        addPlayerInventory(inv, 100, 148);
    }

    /** 取 pos 处方块作 stillValid 校验目标; 非军火台时退回军火台方块占位 (块不匹配判 false 关闭界面)。 */
    private static net.minecraft.world.level.block.Block blockAt(Inventory inv, BlockPos pos) {
        net.minecraft.world.level.block.Block block = inv.player.level().getBlockState(pos).getBlock();
        return block instanceof MunitionsBenchBlock
                ? block : ModMunitionsBlocks.MUNITIONS_BENCH.get();
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (!(player instanceof ServerPlayer serverPlayer) || blockEntity == null) {
            return false;
        }
        if (id >= 0 && id < MunitionsCaliber.values().length) {
            return blockEntity.trySelectCaliber(MunitionsCaliber.byIndex(id), serverPlayer);
        }
        if (id == BUTTON_TOGGLE_LOCK) {
            if (blockEntity.isOwner(player)) {
                blockEntity.toggleLocked();
                return true;
            }
            return false;
        }
        if (id == BUTTON_START_CRAFT) {
            return blockEntity.tryStartCraft(serverPlayer);
        }
        if (id == BUTTON_CANCEL_CRAFT) {
            return blockEntity.cancelCraft(serverPlayer);
        }
        if (id == BUTTON_TOGGLE_CONTINUOUS) {
            return blockEntity.toggleContinuousCrafting(serverPlayer);
        }
        return false;
    }

    /**
     * Shift 移物覆写 (审查 M-7/M-8):
     *  1. 玩家区 -> 容器区的目标区间排除输出槽 —— vanilla moveItemStackTo 的合并分支只判同物同 tag 不调
     *     mayPlace, 玩家背包里的同种弹药会被并进输出槽, 随后 refreshOutputStack 按缓冲重物化把并入的弹覆盖销毁;
     *  2. 输出槽 -> 玩家区的取弹量以移动前后槽内差值精确结算 —— moveItemStackTo 直改源栈不经 Slot.remove,
     *     OutputSlot 的 remove 计量对 Shift 路径不可见。结算放在槽状态落定之后, refreshOutputStack 重物化
     *     的剩余弹不会被本方法的清槽逻辑抹掉。
     */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stackInSlot = slot.getItem();
        ItemStack moved = stackInSlot.copy();
        int playerStart = CONTAINER_SLOTS;
        int playerEnd = this.slots.size();
        int takenFromOutput = 0;

        if (index < playerStart) {
            int before = stackInSlot.getCount();
            if (!this.moveItemStackTo(stackInSlot, playerStart, playerEnd, true)) {
                return ItemStack.EMPTY;
            }
            if (index == MunitionsBenchBlockEntity.SLOT_OUTPUT) {
                takenFromOutput = before - stackInSlot.getCount();
            }
        } else {
            if (!this.moveItemStackTo(stackInSlot, 0, MunitionsBenchBlockEntity.SLOT_OUTPUT, false)) {
                return ItemStack.EMPTY;
            }
        }

        if (stackInSlot.isEmpty()) {
            slot.set(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        if (takenFromOutput > 0 && blockEntity != null && player instanceof ServerPlayer serverPlayer) {
            blockEntity.onOutputTaken(serverPlayer, takenFromOutput);
        }
        if (stackInSlot.getCount() == moved.getCount()) {
            return ItemStack.EMPTY;
        }
        slot.onTake(player, stackInSlot);
        return moved;
    }

    /**
     * 输出缓冲槽: 只取不放; 取出时回收缓冲计数 (谁产谁得经验已在产出帧入主人)。
     * 取弹计量走 {@link #remove} 精确累计 (审查 M-8): remove 是鼠标路径 (safeTake/tryRemove) 物品离槽的唯一
     * 入口, 整取/半取天然精确, 且每个 menu 实例独立计量 —— 替换旧的 getItem 快照机制 (vanilla 每 tick
     * broadcastChanges 会虚调 getItem, 把第二名观看者的快照钉在历史最大值, 多人同开交错取弹时按旧快照差值
     * 超额扣缓冲)。Shift 路径不经 remove, 由外层 quickMoveStack 差值结算, 此处消费 0 不双计。
     */
    private static final class OutputSlot extends SlotItemHandler {
        private final MunitionsBenchBlockEntity be;
        private int pendingTaken = 0;

        OutputSlot(MunitionsBenchBlockEntity be, int index, int x, int y) {
            super(be.inventory(), index, x, y);
            this.be = be;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }

        @Override
        public ItemStack remove(int amount) {
            ItemStack removed = super.remove(amount);
            pendingTaken += removed.getCount();
            return removed;
        }

        @Override
        public void onTake(Player player, ItemStack stack) {
            int taken = pendingTaken;
            pendingTaken = 0;
            if (taken > 0 && player instanceof ServerPlayer serverPlayer) {
                be.onOutputTaken(serverPlayer, taken);
            }
            super.onTake(player, stack);
        }
    }

    // ---- 客户端读同步值 (Screen 渲染用) ----

    public int selectedCaliberIndex() {
        return data.get(MunitionsBenchBlockEntity.DATA_SELECTED_CALIBER);
    }

    public int bufferedRounds() {
        return data.get(MunitionsBenchBlockEntity.DATA_BUFFERED_ROUNDS);
    }

    public int bufferCap() {
        return data.get(MunitionsBenchBlockEntity.DATA_BUFFER_CAP);
    }

    public boolean isLocked() {
        return data.get(MunitionsBenchBlockEntity.DATA_LOCKED) != 0;
    }

    public boolean isRefineUnlocked() {
        return data.get(MunitionsBenchBlockEntity.DATA_REFINE_UNLOCKED) != 0;
    }

    public int productionProgressTicks() {
        // 服务端按秒过线 (int16 规避, 见 BE dataAccess), 此处 x20 还原 ticks; 秒粒度对进度条视觉无感。
        return data.get(MunitionsBenchBlockEntity.DATA_PRODUCTION_PROGRESS_TICKS) * 20;
    }

    public int productionRequiredTicks() {
        return data.get(MunitionsBenchBlockEntity.DATA_PRODUCTION_REQUIRED_TICKS) * 20;
    }

    public int effectiveMunitionsLevel() {
        return Math.max(1, Math.min(10, data.get(MunitionsBenchBlockEntity.DATA_EFFECTIVE_LEVEL)));
    }

    public boolean isCraftingActive() {
        return data.get(MunitionsBenchBlockEntity.DATA_CRAFTING_ACTIVE) != 0;
    }

    public boolean isContinuousCrafting() {
        return data.get(MunitionsBenchBlockEntity.DATA_CONTINUOUS_CRAFTING) != 0;
    }
}
