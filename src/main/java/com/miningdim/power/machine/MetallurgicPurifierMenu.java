package com.miningdim.power.machine;

import com.miningdim.menu.AbstractMiningMenu;
import com.miningdim.menu.MenuValidity;
import com.miningdim.power.PowerMachineRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.SlotItemHandler;
import org.jetbrains.annotations.Nullable;

/** 提纯机三槽菜单；客户端只读取同步数据，不能选择或结算配方。 */
public final class MetallurgicPurifierMenu extends AbstractMiningMenu {

    private final @Nullable MetallurgicPurifierBlockEntity blockEntity;
    private final ContainerData data;

    public MetallurgicPurifierMenu(int windowId, Inventory inventory, BlockPos pos) {
        super(PowerMachineRegistry.PURIFIER_MENU.get(), windowId, MetallurgicPurifierBlockEntity.SLOT_COUNT,
                MenuValidity.ofBlock(ContainerLevelAccess.create(inventory.player.level(), pos),
                        inventory.player.level().getBlockState(pos).getBlock()));
        blockEntity = inventory.player.level().getBlockEntity(pos) instanceof MetallurgicPurifierBlockEntity purifier
                ? purifier : null;
        ItemStackHandler slots = blockEntity == null
                ? new ItemStackHandler(MetallurgicPurifierBlockEntity.SLOT_COUNT) : blockEntity.inventory();
        addSlot(new SlotItemHandler(slots, MetallurgicPurifierBlockEntity.SLOT_BASE, 51, 36));
        addSlot(new SlotItemHandler(slots, MetallurgicPurifierBlockEntity.SLOT_INFUSION, 101, 36));
        addSlot(new SlotItemHandler(slots, MetallurgicPurifierBlockEntity.SLOT_OUTPUT, 151, 36) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return false;
            }
        });
        data = blockEntity != null && !inventory.player.level().isClientSide
                ? blockEntity.data() : new SimpleContainerData(MetallurgicPurifierBlockEntity.DATA_COUNT);
        addDataSlots(data);
        addPlayerInventory(inventory, 28, 142);
    }

    public @Nullable MetallurgicPurifierBlockEntity blockEntity() {
        return blockEntity;
    }

    public int dataValue(int index) {
        return data.get(index);
    }

    public int progress() {
        return joinInt32(dataValue(MetallurgicPurifierBlockEntity.DATA_PROGRESS_LOW),
                dataValue(MetallurgicPurifierBlockEntity.DATA_PROGRESS_HIGH));
    }

    public int processingTime() {
        return joinInt32(dataValue(MetallurgicPurifierBlockEntity.DATA_DURATION_LOW),
                dataValue(MetallurgicPurifierBlockEntity.DATA_DURATION_HIGH));
    }

    public int storedFe() {
        return joinInt32(dataValue(MetallurgicPurifierBlockEntity.DATA_STORED_FE_LOW),
                dataValue(MetallurgicPurifierBlockEntity.DATA_STORED_FE_HIGH));
    }

    public int energyCapacity() {
        return joinInt32(dataValue(MetallurgicPurifierBlockEntity.DATA_ENERGY_CAPACITY_LOW),
                dataValue(MetallurgicPurifierBlockEntity.DATA_ENERGY_CAPACITY_HIGH));
    }

    public int infusionUnits() {
        return joinInt32(dataValue(MetallurgicPurifierBlockEntity.DATA_INFUSION_UNITS_LOW),
                dataValue(MetallurgicPurifierBlockEntity.DATA_INFUSION_UNITS_HIGH));
    }

    public int infusionCapacity() {
        return joinInt32(dataValue(MetallurgicPurifierBlockEntity.DATA_INFUSION_CAPACITY_LOW),
                dataValue(MetallurgicPurifierBlockEntity.DATA_INFUSION_CAPACITY_HIGH));
    }

    static int joinInt32(int low, int high) {
        return (low & 0xFFFF) | ((high & 0xFFFF) << 16);
    }
}
