package com.miningdim.power.endgame;

import com.miningdim.menu.AbstractMiningMenu;
import com.miningdim.menu.MenuValidity;
import com.miningdim.power.PowerMachineRegistry;
import com.miningdim.power.PowerRegistry;
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

/** 单槽控制器菜单；服务端库存规则仍由方块实体权威校验。 */
public final class LowTemperatureControllerMenu extends AbstractMiningMenu {

    private final @Nullable LowTemperatureControllerBlockEntity blockEntity;
    private final ContainerData data;

    public LowTemperatureControllerMenu(int windowId, Inventory inventory, BlockPos pos) {
        super(PowerRegistry.LOW_TEMPERATURE_CONTROLLER_MENU.get(), windowId,
                LowTemperatureControllerBlockEntity.SLOT_COUNT,
                MenuValidity.ofBlock(ContainerLevelAccess.create(inventory.player.level(), pos),
                        inventory.player.level().getBlockState(pos).getBlock()));
        blockEntity = inventory.player.level().getBlockEntity(pos) instanceof LowTemperatureControllerBlockEntity controller
                ? controller : null;
        ItemStackHandler slots = blockEntity == null
                ? new ItemStackHandler(LowTemperatureControllerBlockEntity.SLOT_COUNT) : blockEntity.inventory();
        addSlot(new SlotItemHandler(slots, LowTemperatureControllerBlockEntity.SLOT_LIQUID_NITROGEN, 101, 35) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return stack.is(PowerMachineRegistry.LIQUID_NITROGEN_CANISTER.get());
            }
        });
        data = blockEntity != null && !inventory.player.level().isClientSide
                ? blockEntity.data() : new SimpleContainerData(LowTemperatureControllerBlockEntity.DATA_COUNT);
        addDataSlots(data);
        addPlayerInventory(inventory, 28, 94);
    }

    public @Nullable LowTemperatureControllerBlockEntity blockEntity() {
        return blockEntity;
    }

    public int remainingTicks() {
        return joinInt32(data.get(LowTemperatureControllerBlockEntity.DATA_REMAINING_LOW),
                data.get(LowTemperatureControllerBlockEntity.DATA_REMAINING_HIGH));
    }

    public boolean isCoolingActive() {
        return data.get(LowTemperatureControllerBlockEntity.DATA_ACTIVE) != 0;
    }

    private static int joinInt32(int low, int high) {
        return (low & 0xFFFF) | ((high & 0xFFFF) << 16);
    }
}
