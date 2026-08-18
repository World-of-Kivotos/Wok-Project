package com.miningdim.power.machine;

import com.miningdim.power.PowerMachineConfig;
import com.miningdim.power.PowerMachineRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
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
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/** 空分装置只处理选定模式的一条数据包配方；缺配方或重复配方会明确报错并停机。 */
public final class AirSeparationUnitBlockEntity extends BlockEntity implements MenuProvider {

    public static final int SLOT_OUTPUT = 0;
    public static final int SLOT_COUNT = 1;
    public static final int DATA_PROGRESS_LOW = 0;
    public static final int DATA_PROGRESS_HIGH = 1;
    public static final int DATA_DURATION_LOW = 2;
    public static final int DATA_DURATION_HIGH = 3;
    public static final int DATA_STORED_FE_LOW = 4;
    public static final int DATA_STORED_FE_HIGH = 5;
    public static final int DATA_ENERGY_CAPACITY_LOW = 6;
    public static final int DATA_ENERGY_CAPACITY_HIGH = 7;
    public static final int DATA_MODE = 8;
    public static final int DATA_COUNT = 9;
    public static final int NBT_SCHEMA_VERSION = 1;

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/power");
    private static final String K_SCHEMA_VERSION = "schemaVersion";
    private static final String K_ITEMS = "items";
    private static final String K_ENERGY = "energy";
    private static final String K_MODE = "mode";
    private static final String K_PROGRESS = "progress";

    private final ItemStackHandler inventory = new ItemStackHandler(SLOT_COUNT) {
        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            if (slot != SLOT_OUTPUT) {
                throw new IllegalArgumentException("invalid air separation slot: " + slot);
            }
            return false;
        }

        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
        }
    };
    private final MachineEnergyStorage energy = new MachineEnergyStorage(
            PowerMachineConfig::airSeparatorEnergyCapacity, this::setChanged);
    private final LazyOptional<IItemHandler> itemCap = LazyOptional.of(() -> inventory);
    private final LazyOptional<IEnergyStorage> energyCap = LazyOptional.of(() -> energy);
    private final ContainerData data = new ContainerData() {
        @Override
        public int get(int index) {
            return switch (index) {
                case DATA_PROGRESS_LOW -> lowWord(progress);
                case DATA_PROGRESS_HIGH -> highWord(progress);
                case DATA_DURATION_LOW -> lowWord(processingTime());
                case DATA_DURATION_HIGH -> highWord(processingTime());
                case DATA_STORED_FE_LOW -> lowWord(energy.getEnergyStored());
                case DATA_STORED_FE_HIGH -> highWord(energy.getEnergyStored());
                case DATA_ENERGY_CAPACITY_LOW -> lowWord(energy.getMaxEnergyStored());
                case DATA_ENERGY_CAPACITY_HIGH -> highWord(energy.getMaxEnergyStored());
                case DATA_MODE -> mode.ordinal();
                default -> throw new IndexOutOfBoundsException("air separation data index: " + index);
            };
        }

        @Override
        public void set(int index, int value) {
            if (index < 0 || index >= DATA_COUNT) {
                throw new IndexOutOfBoundsException("air separation data index: " + index);
            }
        }

        @Override
        public int getCount() {
            return DATA_COUNT;
        }
    };

    private AirSeparationMode mode = AirSeparationMode.ARGON;
    private int progress;
    private @Nullable String lastRecipeFault;

    public AirSeparationUnitBlockEntity(BlockPos pos, BlockState state) {
        super(PowerMachineRegistry.AIR_SEPARATION_UNIT_BE.get(), pos, state);
    }

    public ItemStackHandler inventory() {
        return inventory;
    }

    public ContainerData data() {
        return data;
    }

    public int progress() {
        return progress;
    }

    public int processingTime() {
        return PowerMachineConfig.airSeparating(mode).durationTicks();
    }

    public int storedFe() {
        return energy.getEnergyStored();
    }

    public int energyCapacity() {
        return energy.getMaxEnergyStored();
    }

    public AirSeparationMode mode() {
        return mode;
    }

    private static int lowWord(int value) {
        return value & 0xFFFF;
    }

    private static int highWord(int value) {
        return (value >>> 16) & 0xFFFF;
    }

    public boolean setMode(AirSeparationMode requested) {
        if (progress != 0 || requested == mode) {
            return false;
        }
        mode = requested;
        lastRecipeFault = null;
        setChanged();
        updateLit(false);
        return true;
    }

    public void serverTick() {
        if (!(level instanceof ServerLevel)) {
            return;
        }
        AirSeparatingRecipe recipe = recipeForMode();
        if (recipe == null || !canOutput(recipe.result())) {
            updateLit(false);
            return;
        }
        AirSeparatingRuntime runtime = PowerMachineConfig.airSeparating(mode);
        if (progress >= runtime.durationTicks()) {
            insertOutput(recipe.result());
            progress = 0;
            updateLit(false);
            setChanged();
            return;
        }
        if (!energy.hasAtLeast(runtime.fePerTick())) {
            updateLit(false);
            return;
        }
        energy.consume(runtime.fePerTick());
        progress++;
        boolean completed = progress >= runtime.durationTicks();
        if (completed) {
            if (!canOutput(recipe.result())) {
                throw new IllegalStateException("air separator completion invariant broken at " + worldPosition);
            }
            insertOutput(recipe.result());
            progress = 0;
        }
        updateLit(!completed);
        setChanged();
    }

    private @Nullable AirSeparatingRecipe recipeForMode() {
        if (!(level instanceof ServerLevel serverLevel)) {
            return null;
        }
        List<AirSeparatingRecipe> recipes = serverLevel.getRecipeManager()
                .getAllRecipesFor(PowerMachineRegistry.AIR_SEPARATING_TYPE.get());
        AirSeparatingRecipe selected = null;
        for (AirSeparatingRecipe candidate : recipes) {
            if (candidate.mode() != mode) {
                continue;
            }
            if (selected != null) {
                reportRecipeFault("multiple recipes", selected.getId(), candidate.getId());
                return null;
            }
            selected = candidate;
        }
        if (selected == null) {
            reportRecipeFault("no recipe", null, null);
        } else {
            lastRecipeFault = null;
        }
        return selected;
    }

    private void reportRecipeFault(String reason, @Nullable net.minecraft.resources.ResourceLocation first,
                                   @Nullable net.minecraft.resources.ResourceLocation second) {
        String fault = reason + ':' + mode.id() + ':' + first + ':' + second;
        if (!fault.equals(lastRecipeFault)) {
            LOGGER.error("[miningdim] air separation stopped at {}: {} for mode {} (first={}, second={})",
                    worldPosition, reason, mode.id(), first, second);
            lastRecipeFault = fault;
        }
    }

    private boolean canOutput(ItemStack result) {
        ItemStack output = inventory.getStackInSlot(SLOT_OUTPUT);
        return output.isEmpty() || (ItemStack.isSameItemSameTags(output, result)
                && output.getCount() + result.getCount() <= output.getMaxStackSize());
    }

    private void insertOutput(ItemStack result) {
        ItemStack output = inventory.getStackInSlot(SLOT_OUTPUT);
        if (output.isEmpty()) {
            inventory.setStackInSlot(SLOT_OUTPUT, result.copy());
        } else {
            output.grow(result.getCount());
            inventory.setStackInSlot(SLOT_OUTPUT, output);
        }
    }

    private void updateLit(boolean lit) {
        if (level != null && getBlockState().getValue(PowerMachineBlock.LIT) != lit) {
            level.setBlock(worldPosition, getBlockState().setValue(PowerMachineBlock.LIT, lit), 3);
        }
    }

    public void dropContents() {
        if (level == null) {
            return;
        }
        ItemStack stack = inventory.getStackInSlot(SLOT_OUTPUT);
        if (!stack.isEmpty()) {
            Containers.dropItemStack(level, worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(), stack);
            inventory.setStackInSlot(SLOT_OUTPUT, ItemStack.EMPTY);
        }
    }

    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> capability, @Nullable net.minecraft.core.Direction side) {
        if (capability == ForgeCapabilities.ENERGY) {
            return energyCap.cast();
        }
        if (capability == ForgeCapabilities.ITEM_HANDLER) {
            return itemCap.cast();
        }
        return super.getCapability(capability, side);
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        itemCap.invalidate();
        energyCap.invalidate();
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable(getBlockState().getBlock().getDescriptionId());
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int windowId, Inventory inventory, Player player) {
        return new AirSeparationMenu(windowId, inventory, worldPosition);
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt(K_SCHEMA_VERSION, NBT_SCHEMA_VERSION);
        tag.put(K_ITEMS, inventory.serializeNBT());
        CompoundTag energyTag = new CompoundTag();
        energy.save(energyTag);
        tag.put(K_ENERGY, energyTag);
        tag.putString(K_MODE, mode.id());
        tag.putInt(K_PROGRESS, progress);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (!tag.contains(K_SCHEMA_VERSION, Tag.TAG_INT) || tag.getInt(K_SCHEMA_VERSION) != NBT_SCHEMA_VERSION
                || !tag.contains(K_ITEMS, Tag.TAG_COMPOUND) || !tag.contains(K_ENERGY, Tag.TAG_COMPOUND)
                || !tag.contains(K_MODE, Tag.TAG_STRING) || !tag.contains(K_PROGRESS, Tag.TAG_INT)) {
            throw new IllegalStateException("invalid air separator NBT at " + worldPosition);
        }
        inventory.deserializeNBT(tag.getCompound(K_ITEMS));
        energy.load(tag.getCompound(K_ENERGY));
        mode = AirSeparationMode.byId(tag.getString(K_MODE));
        progress = tag.getInt(K_PROGRESS);
        if (progress < 0) {
            throw new IllegalStateException("invalid air separator progress at " + worldPosition);
        }
    }
}
