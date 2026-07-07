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
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.SlotItemHandler;

/**
 * 军火台容器 (Munitions_Job_DesignSpec 五/十章)。继承共享 {@link AbstractMiningMenu} (正确 quickMoveStack +
 * stillValid 已由基类经 {@link MenuValidity#ofBlock} 实现)。
 *
 * 槽位: 料槽 铜/火药/发射药 (可放可取, isItemValid 限料种) + 输出缓冲槽 (只取不放, 取出经
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

    /** 输出缓冲槽: 只取不放; 取出时回收缓冲计数 (谁产谁得经验已在产出帧入主人)。 */
    private static final class OutputSlot extends SlotItemHandler {
        private final MunitionsBenchBlockEntity be;

        /**
         * 取出前的输出栈快照。基类 {@link AbstractMiningMenu#quickMoveStack} 与 vanilla {@code Slot.safeTake} 都在
         * 移除输出栈前先读 {@link #getItem()}, 故此处随每次读取刷新快照; 移除后 {@link #onTake} 据
         * (快照数量 - 残留数量) 算本次实际取走量。修复 Shift 整栈取弹时基类传入 onTake 的是移除后残留 EMPTY 栈、
         * 据其结算导致 bufferedRounds 缓冲计数永不回收的缺陷 (munitions-output, 同 engineer-01)。
         */
        private ItemStack takeSnapshot = ItemStack.EMPTY;

        OutputSlot(MunitionsBenchBlockEntity be, int index, int x, int y) {
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
            // 仅在输出栈 "非缩减" 读取时刷新快照 (首次/换弹/新产出填充使数量增大); 不在移除后用残留小栈覆盖快照。
            // 否则 vanilla Slot.tryRemove 在 remove() 之后还会再读一次 getItem() (判残留是否清空), 那次读取的
            // 残留小栈会把取出前的满栈快照覆盖掉, 令 onTake 的 (快照数量 - 残留数量) 误算为 0, 漏回收缓冲。
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
                // Shift 整栈取走时基类传入的 stack 是残留 EMPTY, 据其结算会漏回收缓冲 (munitions-output), 故改用快照差值。
                int takenCount = this.takeSnapshot.getCount() - super.getItem().getCount();
                be.onOutputTaken(serverPlayer, takenCount);
            }
            super.onTake(player, stack);
            // 取后把快照重置为槽内残留, 作为下一次取出的基线 (空槽则 EMPTY)。
            this.takeSnapshot = super.getItem().copy();
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
        return data.get(MunitionsBenchBlockEntity.DATA_PRODUCTION_PROGRESS_TICKS);
    }

    public int productionRequiredTicks() {
        return data.get(MunitionsBenchBlockEntity.DATA_PRODUCTION_REQUIRED_TICKS);
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
