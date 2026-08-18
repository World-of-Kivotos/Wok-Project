package com.miningdim.power.machine;

import com.miningdim.menu.AbstractMiningMenu;
import com.miningdim.menu.MenuValidity;
import com.miningdim.power.PowerMachineRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.SlotItemHandler;
import org.jetbrains.annotations.Nullable;

/** 空分装置菜单；模式切换走原版菜单按钮并且由方块实体拒绝非零进度请求。 */
public final class AirSeparationMenu extends AbstractMiningMenu {

    public static final int BUTTON_ARGON = 0;
    public static final int BUTTON_LIQUID_NITROGEN = 1;

    private final @Nullable AirSeparationUnitBlockEntity blockEntity;
    private final ContainerData data;

    public AirSeparationMenu(int windowId, Inventory inventory, BlockPos pos) {
        super(PowerMachineRegistry.AIR_SEPARATOR_MENU.get(), windowId, AirSeparationUnitBlockEntity.SLOT_COUNT,
                MenuValidity.ofBlock(ContainerLevelAccess.create(inventory.player.level(), pos),
                        inventory.player.level().getBlockState(pos).getBlock()));
        blockEntity = inventory.player.level().getBlockEntity(pos) instanceof AirSeparationUnitBlockEntity separator
                ? separator : null;
        ItemStackHandler slots = blockEntity == null
                ? new ItemStackHandler(AirSeparationUnitBlockEntity.SLOT_COUNT) : blockEntity.inventory();
        addSlot(new SlotItemHandler(slots, AirSeparationUnitBlockEntity.SLOT_OUTPUT, 79, 72) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return false;
            }
        });
        data = blockEntity != null && !inventory.player.level().isClientSide
                ? blockEntity.data() : new SimpleContainerData(AirSeparationUnitBlockEntity.DATA_COUNT);
        addDataSlots(data);
        addPlayerInventory(inventory, 8, 140);
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (!(player instanceof ServerPlayer) || blockEntity == null) {
            return false;
        }
        return switch (id) {
            case BUTTON_ARGON -> blockEntity.setMode(AirSeparationMode.ARGON);
            case BUTTON_LIQUID_NITROGEN -> blockEntity.setMode(AirSeparationMode.LIQUID_NITROGEN);
            default -> false;
        };
    }

    public @Nullable AirSeparationUnitBlockEntity blockEntity() {
        return blockEntity;
    }

    public int dataValue(int index) {
        return data.get(index);
    }

    public int progress() {
        return joinInt32(dataValue(AirSeparationUnitBlockEntity.DATA_PROGRESS_LOW),
                dataValue(AirSeparationUnitBlockEntity.DATA_PROGRESS_HIGH));
    }

    public int processingTime() {
        return joinInt32(dataValue(AirSeparationUnitBlockEntity.DATA_DURATION_LOW),
                dataValue(AirSeparationUnitBlockEntity.DATA_DURATION_HIGH));
    }

    public int storedFe() {
        return joinInt32(dataValue(AirSeparationUnitBlockEntity.DATA_STORED_FE_LOW),
                dataValue(AirSeparationUnitBlockEntity.DATA_STORED_FE_HIGH));
    }

    public int energyCapacity() {
        return joinInt32(dataValue(AirSeparationUnitBlockEntity.DATA_ENERGY_CAPACITY_LOW),
                dataValue(AirSeparationUnitBlockEntity.DATA_ENERGY_CAPACITY_HIGH));
    }

    public int modeOrdinal() {
        return dataValue(AirSeparationUnitBlockEntity.DATA_MODE);
    }

    static int joinInt32(int low, int high) {
        return (low & 0xFFFF) | ((high & 0xFFFF) << 16);
    }
}
