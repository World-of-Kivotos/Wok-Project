package com.miningdim.job.brewer.station;

import com.miningdim.menu.AbstractMiningMenu;
import com.miningdim.menu.MenuValidity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.items.SlotItemHandler;

/**
 * 酿酒台容器 (酿酒师 阶段 3; 复用共享 {@link AbstractMiningMenu} 脚手架, 与工程师 ProductionTableMenu 同范式:
 * 单 BlockPos 构造, 服务端/客户端均经 level.getBlockEntity(pos) 取 BE, 客户端取不到则用占位数据防越界)。
 *
 * 槽位: {@link BrewingStationBlockEntity#INPUT_SLOTS} 个投料槽 (可放可取) + 1 输出槽 (只取不放, mayPlace=false)
 * + 玩家 36 槽。酿造进度经 {@link ContainerData} 同步 (服务端用 BE 实时 dataAccess; 客户端用 SimpleContainerData
 * 接 setData, 与原版熔炉同范式)。
 *
 * 经 {@link com.miningdim.menu.ModMenus#blockMenuType} 注册 (extraData 首读 BlockPos), 与 {@link BrewingStationBlock}
 * 的 openScreen 写入一一对应。
 */
public final class BrewingStationMenu extends AbstractMiningMenu {

    /** 容器槽数 (投料 + 输出)。 */
    private static final int CONTAINER_SLOTS = BrewingStationBlockEntity.TOTAL_SLOTS;

    // 投料槽布局 (一排, 9px 间隔起点; 输出槽单列偏右)。
    private static final int INPUT_X0 = 26;
    private static final int INPUT_Y = 35;
    private static final int INPUT_DX = 18;
    private static final int OUTPUT_X = 134;
    private static final int OUTPUT_Y = 35;

    private final ContainerData data;

    public BrewingStationMenu(int windowId, Inventory inv, BlockPos pos) {
        super(BrewingStationRegistry.STATION_MENU.get(), windowId, CONTAINER_SLOTS,
                MenuValidity.ofBlock(ContainerLevelAccess.create(inv.player.level(), pos), blockAt(inv, pos)));

        BrewingStationBlockEntity be =
                inv.player.level().getBlockEntity(pos) instanceof BrewingStationBlockEntity station
                        ? station : null;

        if (be != null) {
            for (int i = 0; i < BrewingStationBlockEntity.INPUT_SLOTS; i++) {
                addSlot(new SlotItemHandler(be.inventory(), i, INPUT_X0 + i * INPUT_DX, INPUT_Y));
            }
            addSlot(new OutputSlot(be, BrewingStationBlockEntity.OUTPUT_SLOT, OUTPUT_X, OUTPUT_Y));
            // 服务端: BE 实时 dataAccess (get 读 BE 字段); 客户端: SimpleContainerData 接 setData 同步。
            this.data = inv.player.level().isClientSide
                    ? new SimpleContainerData(BrewingStationBlockEntity.DATA_COUNT)
                    : be.dataAccess();
        } else {
            // 极端时序 (BE 未同步): 空占位槽 + 空数据, 防越界 (与工程师 menu 同兜底)。
            for (int i = 0; i < BrewingStationBlockEntity.INPUT_SLOTS; i++) {
                addSlot(new EmptyPlaceholderSlot(i));
            }
            addSlot(new EmptyPlaceholderSlot(BrewingStationBlockEntity.OUTPUT_SLOT));
            this.data = new SimpleContainerData(BrewingStationBlockEntity.DATA_COUNT);
        }
        addDataSlots(this.data);
        addPlayerInventory(inv, 8, 84);
    }

    /** 取 pos 处方块作 stillValid 校验目标; 非酿酒台时退回酿酒台方块占位 (块不匹配将判 false 关界面)。 */
    private static Block blockAt(Inventory inv, BlockPos pos) {
        Block block = inv.player.level().getBlockState(pos).getBlock();
        if (block instanceof BrewingStationBlock) {
            return block;
        }
        return BrewingStationRegistry.STATION_BLOCK.get();
    }

    /** 客户端读酿造进度 (Screen 进度条用)。 */
    public int progress() {
        return data.get(BrewingStationBlockEntity.DATA_PROGRESS);
    }

    /** 输出槽: 只取不放 (产物由 BE 写入)。 */
    private static final class OutputSlot extends SlotItemHandler {
        OutputSlot(BrewingStationBlockEntity be, int index, int x, int y) {
            super(be.inventory(), index, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }
    }

    /** BE 未同步时的占位槽 (无容器后端, 永不可放/取; 仅占索引防 ContainerData/quickMove 越界)。 */
    private static final class EmptyPlaceholderSlot extends net.minecraft.world.inventory.Slot {
        private static final net.minecraft.world.SimpleContainer DUMMY =
                new net.minecraft.world.SimpleContainer(BrewingStationBlockEntity.TOTAL_SLOTS);

        EmptyPlaceholderSlot(int index) {
            super(DUMMY, index, 0, 0);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }

        @Override
        public boolean mayPickup(net.minecraft.world.entity.player.Player player) {
            return false;
        }
    }
}
