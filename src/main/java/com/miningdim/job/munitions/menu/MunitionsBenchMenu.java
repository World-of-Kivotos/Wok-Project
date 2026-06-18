package com.miningdim.job.munitions.menu;

import com.miningdim.job.munitions.ModMunitionsBlocks;
import com.miningdim.job.munitions.ModMunitionsMenus;
import com.miningdim.job.munitions.MunitionsCaliber;
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

    private static final int CONTAINER_SLOTS = 4;

    public static final int BUTTON_TOGGLE_LOCK = 200;

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
                    MunitionsBenchBlockEntity.SLOT_COPPER, 26, 24));
            addSlot(new SlotItemHandler(blockEntity.inventory(),
                    MunitionsBenchBlockEntity.SLOT_GUNPOWDER, 26, 47));
            addSlot(new SlotItemHandler(blockEntity.inventory(),
                    MunitionsBenchBlockEntity.SLOT_PROPELLANT, 48, 35));
            addSlot(new OutputSlot(blockEntity, MunitionsBenchBlockEntity.SLOT_OUTPUT, 116, 35));
            this.data = inv.player.level().isClientSide
                    ? new SimpleContainerData(MunitionsBenchBlockEntity.DATA_COUNT())
                    : blockEntity.dataAccess();
        } else {
            this.data = new SimpleContainerData(MunitionsBenchBlockEntity.DATA_COUNT());
        }
        addDataSlots(this.data);
        addPlayerInventory(inv, 8, 84);
    }

    /** 取 pos 处方块作 stillValid 校验目标; 非军火台时退回军火台方块占位 (块不匹配判 false 关闭界面)。 */
    private static net.minecraft.world.level.block.Block blockAt(Inventory inv, BlockPos pos) {
        net.minecraft.world.level.block.Block block = inv.player.level().getBlockState(pos).getBlock();
        return ModMunitionsBlocks.MUNITIONS_BENCH.get() == block
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
        return false;
    }

    /** 输出缓冲槽: 只取不放; 取出时回收缓冲计数 (谁产谁得经验已在产出帧入主人)。 */
    private static final class OutputSlot extends SlotItemHandler {
        private final MunitionsBenchBlockEntity be;

        OutputSlot(MunitionsBenchBlockEntity be, int index, int x, int y) {
            super(be.inventory(), index, x, y);
            this.be = be;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }

        @Override
        public void onTake(Player player, ItemStack stack) {
            if (player instanceof ServerPlayer serverPlayer) {
                be.onOutputTaken(serverPlayer, stack);
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
}
