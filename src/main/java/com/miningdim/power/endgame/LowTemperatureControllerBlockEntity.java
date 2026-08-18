package com.miningdim.power.endgame;

import com.miningdim.power.PowerMachineRegistry;
import com.miningdim.power.PowerRegistry;
import com.miningdim.power.cable.EnergyCableBlock;
import com.miningdim.power.grid.CoolingControllerAttachment;
import com.miningdim.power.grid.EnergyNetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;
import org.jetbrains.annotations.Nullable;

/** 液氮罐在启动时立即消耗，倒计时是控制器唯一可持久化的工作状态。 */
public final class LowTemperatureControllerBlockEntity extends BlockEntity
        implements MenuProvider, CoolingControllerAttachment {

    public static final int SLOT_LIQUID_NITROGEN = 0;
    public static final int SLOT_COUNT = 1;
    public static final int COVERAGE_SEGMENTS = 64;
    public static final int COOLING_TICKS_PER_CANISTER = 24_000;
    public static final int NBT_SCHEMA_VERSION = 1;
    public static final int DATA_REMAINING_LOW = 0;
    public static final int DATA_REMAINING_HIGH = 1;
    public static final int DATA_ACTIVE = 2;
    public static final int DATA_COUNT = 3;

    private static final String K_SCHEMA_VERSION = "schemaVersion";
    private static final String K_ITEMS = "items";
    private static final String K_REMAINING = "remaining";

    private final ItemStackHandler inventory = new ItemStackHandler(SLOT_COUNT) {
        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            if (slot != SLOT_LIQUID_NITROGEN) {
                throw new IllegalArgumentException("invalid low temperature controller slot: " + slot);
            }
            return stack.is(PowerMachineRegistry.LIQUID_NITROGEN_CANISTER.get());
        }

        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
        }
    };
    private final LazyOptional<IItemHandler> itemCap = LazyOptional.of(() -> inventory);
    private final ContainerData data = new ContainerData() {
        @Override
        public int get(int index) {
            return switch (index) {
                case DATA_REMAINING_LOW -> lowWord(remainingTicks);
                case DATA_REMAINING_HIGH -> highWord(remainingTicks);
                case DATA_ACTIVE -> isCoolingActive() ? 1 : 0;
                default -> throw new IndexOutOfBoundsException("low temperature controller data index: " + index);
            };
        }

        @Override
        public void set(int index, int value) {
            if (index < 0 || index >= DATA_COUNT) {
                throw new IndexOutOfBoundsException("low temperature controller data index: " + index);
            }
        }

        @Override
        public int getCount() {
            return DATA_COUNT;
        }
    };

    private int remainingTicks;

    public LowTemperatureControllerBlockEntity(BlockPos pos, BlockState state) {
        super(PowerRegistry.LOW_TEMPERATURE_CONTROLLER_BE.get(), pos, state);
    }

    public ItemStackHandler inventory() {
        return inventory;
    }

    public ContainerData data() {
        return data;
    }

    public int remainingTicks() {
        return remainingTicks;
    }

    public boolean isCoolingActive() {
        return remainingTicks > 0;
    }

    @Override
    public BlockPos controlledCablePos() {
        Direction facing = getBlockState().getValue(LowTemperatureControllerBlock.FACING);
        return worldPosition.relative(facing.getOpposite());
    }

    @Override
    public int activeCoverageSegments() {
        return isCoolingActive() ? COVERAGE_SEGMENTS : 0;
    }

    public void serverTick() {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        boolean wasActive = isCoolingActive();
        if (wasActive) {
            remainingTicks--;
            if (remainingTicks == 0) {
                beginCanisterIfAvailable();
            }
        } else {
            beginCanisterIfAvailable();
        }
        boolean active = isCoolingActive();
        if (wasActive != active) {
            publishCoolingCoverage(serverLevel);
            refreshControlledCable(serverLevel);
        }
        updateLit(active);
        setChanged();
    }

    private void beginCanisterIfAvailable() {
        ItemStack canister = inventory.getStackInSlot(SLOT_LIQUID_NITROGEN);
        if (canister.isEmpty()) {
            return;
        }
        if (!canister.is(PowerMachineRegistry.LIQUID_NITROGEN_CANISTER.get())) {
            throw new IllegalStateException("low temperature controller contains invalid item at " + worldPosition);
        }
        canister.shrink(1);
        inventory.setStackInSlot(SLOT_LIQUID_NITROGEN, canister);
        remainingTicks = COOLING_TICKS_PER_CANISTER;
    }

    private void publishCoolingCoverage(ServerLevel serverLevel) {
        EnergyNetworkManager.get(serverLevel).updateCoolingController(
                worldPosition, controlledCablePos(), activeCoverageSegments());
    }

    private void refreshControlledCable(ServerLevel serverLevel) {
        BlockPos cablePos = controlledCablePos();
        if (serverLevel.hasChunkAt(cablePos)
                && serverLevel.getBlockState(cablePos).getBlock() instanceof EnergyCableBlock) {
            EnergyCableBlock.refreshConnectionState(serverLevel, cablePos);
        }
    }

    private void updateLit(boolean lit) {
        if (level != null && getBlockState().getValue(LowTemperatureControllerBlock.LIT) != lit) {
            level.setBlock(worldPosition, getBlockState().setValue(LowTemperatureControllerBlock.LIT, lit), 3);
        }
    }

    public void dropUnconsumedCanisters() {
        if (level == null) {
            return;
        }
        ItemStack canister = inventory.getStackInSlot(SLOT_LIQUID_NITROGEN);
        if (!canister.isEmpty()) {
            Containers.dropItemStack(level, worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(), canister);
            inventory.setStackInSlot(SLOT_LIQUID_NITROGEN, ItemStack.EMPTY);
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel serverLevel) {
            publishCoolingCoverage(serverLevel);
            refreshControlledCable(serverLevel);
        }
    }

    @Override
    public void setRemoved() {
        if (level instanceof ServerLevel serverLevel) {
            EnergyNetworkManager.get(serverLevel).removeCoolingController(worldPosition);
            refreshControlledCable(serverLevel);
        }
        itemCap.invalidate();
        super.setRemoved();
    }

    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> capability, @Nullable Direction side) {
        if (capability == ForgeCapabilities.ITEM_HANDLER) {
            return itemCap.cast();
        }
        return super.getCapability(capability, side);
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        itemCap.invalidate();
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable(getBlockState().getBlock().getDescriptionId());
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int windowId, Inventory inventory, Player player) {
        return new LowTemperatureControllerMenu(windowId, inventory, worldPosition);
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt(K_SCHEMA_VERSION, NBT_SCHEMA_VERSION);
        tag.put(K_ITEMS, inventory.serializeNBT());
        tag.putInt(K_REMAINING, remainingTicks);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (!tag.contains(K_SCHEMA_VERSION, Tag.TAG_INT) || tag.getInt(K_SCHEMA_VERSION) != NBT_SCHEMA_VERSION
                || !tag.contains(K_ITEMS, Tag.TAG_COMPOUND) || !tag.contains(K_REMAINING, Tag.TAG_INT)) {
            throw new IllegalStateException("invalid low temperature controller NBT at " + worldPosition);
        }
        int restoredRemaining = tag.getInt(K_REMAINING);
        if (restoredRemaining < 0 || restoredRemaining > COOLING_TICKS_PER_CANISTER) {
            throw new IllegalStateException("invalid low temperature controller remaining ticks at " + worldPosition);
        }
        CompoundTag itemsTag = tag.getCompound(K_ITEMS);
        validateSerializedInventory(itemsTag);
        inventory.deserializeNBT(itemsTag);
        ItemStack restoredCanister = inventory.getStackInSlot(SLOT_LIQUID_NITROGEN);
        if (!restoredCanister.isEmpty()
                && !restoredCanister.is(PowerMachineRegistry.LIQUID_NITROGEN_CANISTER.get())) {
            throw new IllegalStateException("invalid low temperature controller inventory at " + worldPosition);
        }
        remainingTicks = restoredRemaining;
    }

    private void validateSerializedInventory(CompoundTag itemsTag) {
        if (!itemsTag.contains("Size", Tag.TAG_INT) || itemsTag.getInt("Size") != SLOT_COUNT
                || !itemsTag.contains("Items", Tag.TAG_LIST)) {
            throw new IllegalStateException("invalid low temperature controller inventory shape at " + worldPosition);
        }
        ListTag entries = itemsTag.getList("Items", Tag.TAG_COMPOUND);
        if (entries.size() > SLOT_COUNT) {
            throw new IllegalStateException("invalid low temperature controller inventory size at " + worldPosition);
        }
        boolean[] seenSlots = new boolean[SLOT_COUNT];
        for (int index = 0; index < entries.size(); index++) {
            CompoundTag entry = entries.getCompound(index);
            if (!entry.contains("Slot", Tag.TAG_INT)) {
                throw new IllegalStateException("missing low temperature controller inventory slot at "
                        + worldPosition);
            }
            int slot = entry.getInt("Slot");
            if (slot < 0 || slot >= SLOT_COUNT || seenSlots[slot]) {
                throw new IllegalStateException("invalid low temperature controller inventory slot " + slot
                        + " at " + worldPosition);
            }
            seenSlots[slot] = true;
        }
    }

    private static int lowWord(int value) {
        return value & 0xFFFF;
    }

    private static int highWord(int value) {
        return (value >>> 16) & 0xFFFF;
    }
}
