package com.miningdim.power.machine;

import com.miningdim.power.PowerMachineConfig;
import com.miningdim.power.PowerMachineRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
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

import java.util.List;

/** 以带类型的灌注缓冲执行数据包配方；FE、进度和输入输出只由服务端持有。 */
public final class MetallurgicPurifierBlockEntity extends BlockEntity implements MenuProvider {

    public static final int SLOT_BASE = 0;
    public static final int SLOT_INFUSION = 1;
    public static final int SLOT_OUTPUT = 2;
    public static final int SLOT_COUNT = 3;
    public static final int DATA_PROGRESS_LOW = 0;
    public static final int DATA_PROGRESS_HIGH = 1;
    public static final int DATA_DURATION_LOW = 2;
    public static final int DATA_DURATION_HIGH = 3;
    public static final int DATA_STORED_FE_LOW = 4;
    public static final int DATA_STORED_FE_HIGH = 5;
    public static final int DATA_ENERGY_CAPACITY_LOW = 6;
    public static final int DATA_ENERGY_CAPACITY_HIGH = 7;
    public static final int DATA_INFUSION_UNITS_LOW = 8;
    public static final int DATA_INFUSION_UNITS_HIGH = 9;
    public static final int DATA_INFUSION_CAPACITY_LOW = 10;
    public static final int DATA_INFUSION_CAPACITY_HIGH = 11;
    public static final int DATA_COUNT = 12;
    public static final int NBT_SCHEMA_VERSION = 1;

    private static final String K_SCHEMA_VERSION = "schemaVersion";
    private static final String K_ITEMS = "items";
    private static final String K_ENERGY = "energy";
    private static final String K_INFUSION_TYPE = "infusionType";
    private static final String K_INFUSION_UNITS = "infusionUnits";
    private static final String K_ACTIVE_RECIPE = "activeRecipe";
    private static final String K_PROGRESS = "progress";

    private final ItemStackHandler inventory = new ItemStackHandler(SLOT_COUNT) {
        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return switch (slot) {
                case SLOT_BASE -> hasAnyRecipeForBase(stack);
                case SLOT_INFUSION -> acceptsInfusion(stack);
                case SLOT_OUTPUT -> false;
                default -> throw new IllegalArgumentException("invalid purifier slot: " + slot);
            };
        }

        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
        }
    };
    private final MachineEnergyStorage energy = new MachineEnergyStorage(
            PowerMachineConfig::purifierEnergyCapacity, this::setChanged);
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
                case DATA_INFUSION_UNITS_LOW -> lowWord(infusionUnits);
                case DATA_INFUSION_UNITS_HIGH -> highWord(infusionUnits);
                case DATA_INFUSION_CAPACITY_LOW -> lowWord(infusionCapacity());
                case DATA_INFUSION_CAPACITY_HIGH -> highWord(infusionCapacity());
                default -> throw new IndexOutOfBoundsException("purifier data index: " + index);
            };
        }

        @Override
        public void set(int index, int value) {
            if (index < 0 || index >= DATA_COUNT) {
                throw new IndexOutOfBoundsException("purifier data index: " + index);
            }
        }

        @Override
        public int getCount() {
            return DATA_COUNT;
        }
    };

    private @Nullable ResourceLocation infusionType;
    private int infusionUnits;
    private @Nullable ResourceLocation activeRecipeId;
    private int progress;

    public MetallurgicPurifierBlockEntity(BlockPos pos, BlockState state) {
        super(PowerMachineRegistry.METALLURGIC_PURIFIER_BE.get(), pos, state);
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
        MetallurgicPurifyingRecipe recipe = activeRecipe();
        return recipe == null ? 0 : runtime(recipe).durationTicks();
    }

    public int storedFe() {
        return energy.getEnergyStored();
    }

    public int energyCapacity() {
        return energy.getMaxEnergyStored();
    }

    public int infusionUnits() {
        return infusionUnits;
    }

    public int infusionCapacity() {
        return Math.max(PowerMachineConfig.infusionCapacity(), infusionUnits);
    }

    public @Nullable ResourceLocation activeRecipeId() {
        return activeRecipeId;
    }

    private static int lowWord(int value) {
        return value & 0xFFFF;
    }

    private static int highWord(int value) {
        return (value >>> 16) & 0xFFFF;
    }

    public void serverTick() {
        if (!(level instanceof ServerLevel)) {
            return;
        }
        MetallurgicPurifyingRecipe recipe = activeRecipe();
        if (recipe == null) {
            recipe = resolveRecipe(inventory.getStackInSlot(SLOT_BASE), inventory.getStackInSlot(SLOT_INFUSION));
        }
        if (recipe == null || !recipe.matchesBase(inventory.getStackInSlot(SLOT_BASE)) || !canOutput(recipe.result())) {
            updateLit(false);
            return;
        }
        absorbInfusion(recipe);
        PurifyingRuntime runtime = runtime(recipe);
        if (infusionType == null || !infusionType.equals(recipe.infusionType())
                || infusionUnits < runtime.infusionUnits()) {
            updateLit(false);
            return;
        }
        if (progress >= runtime.durationTicks()) {
            finish(recipe, runtime);
            updateLit(false);
            setChanged();
            return;
        }
        if (!energy.hasAtLeast(runtime.fePerTick())) {
            updateLit(false);
            return;
        }
        energy.consume(runtime.fePerTick());
        activeRecipeId = recipe.getId();
        progress++;
        boolean completed = progress >= runtime.durationTicks();
        if (completed) {
            finish(recipe, runtime);
        }
        updateLit(!completed);
        setChanged();
    }

    private void absorbInfusion(MetallurgicPurifyingRecipe recipe) {
        ItemStack stack = inventory.getStackInSlot(SLOT_INFUSION);
        if (stack.isEmpty() || !recipe.matchesInfusion(stack)) {
            return;
        }
        if (infusionType != null && !infusionType.equals(recipe.infusionType())) {
            return;
        }
        int available = infusionCapacity() - infusionUnits;
        if (available <= 0) {
            return;
        }
        PurifyingRuntime runtime = runtime(recipe);
        int itemsFit = available / runtime.infusionUnitsPerItem();
        int consumed = Math.min(itemsFit, stack.getCount());
        if (consumed == 0) {
            return;
        }
        if (infusionType == null) {
            infusionType = recipe.infusionType();
        }
        stack.shrink(consumed);
        inventory.setStackInSlot(SLOT_INFUSION, stack);
        infusionUnits += consumed * runtime.infusionUnitsPerItem();
        setChanged();
    }

    private void finish(MetallurgicPurifyingRecipe recipe, PurifyingRuntime runtime) {
        ItemStack base = inventory.getStackInSlot(SLOT_BASE);
        if (!recipe.matchesBase(base) || !canOutput(recipe.result()) || infusionType == null
                || !infusionType.equals(recipe.infusionType()) || infusionUnits < runtime.infusionUnits()) {
            throw new IllegalStateException("purifier completion invariant broken at " + worldPosition);
        }
        base.shrink(1);
        inventory.setStackInSlot(SLOT_BASE, base);
        insertOutput(recipe.result());
        infusionUnits -= runtime.infusionUnits();
        if (infusionUnits == 0) {
            infusionType = null;
        }
        activeRecipeId = null;
        progress = 0;
    }

    private boolean acceptsInfusion(ItemStack stack) {
        MetallurgicPurifyingRecipe recipe = activeRecipe();
        if (recipe == null) {
            recipe = resolveRecipe(inventory.getStackInSlot(SLOT_BASE), stack);
        }
        return recipe != null && canOutput(recipe.result()) && recipe.matchesInfusion(stack)
                && (infusionType == null || infusionType.equals(recipe.infusionType()));
    }

    private boolean hasAnyRecipeForBase(ItemStack base) {
        if (level == null || base.isEmpty()) {
            return false;
        }
        List<MetallurgicPurifyingRecipe> recipes = level.getRecipeManager()
                .getAllRecipesFor(PowerMachineRegistry.METALLURGIC_PURIFYING_TYPE.get());
        return recipes.stream().anyMatch(recipe -> recipe.matchesBase(base));
    }

    private @Nullable MetallurgicPurifyingRecipe resolveRecipe(ItemStack base, ItemStack candidateInfusion) {
        if (level == null || base.isEmpty()) {
            return null;
        }
        if (infusionType == null && candidateInfusion.isEmpty()) {
            return null;
        }
        List<MetallurgicPurifyingRecipe> recipes = level.getRecipeManager()
                .getAllRecipesFor(PowerMachineRegistry.METALLURGIC_PURIFYING_TYPE.get());
        MetallurgicPurifyingRecipe match = null;
        for (MetallurgicPurifyingRecipe recipe : recipes) {
            if (!recipe.matchesBase(base)
                    || (infusionType != null && !infusionType.equals(recipe.infusionType()))
                    || (infusionType == null && !recipe.matchesInfusion(candidateInfusion))) {
                continue;
            }
            if (match != null) {
                throw new IllegalStateException("ambiguous purifier recipe resolution at " + worldPosition);
            }
            match = recipe;
        }
        return match;
    }

    private @Nullable MetallurgicPurifyingRecipe activeRecipe() {
        if (activeRecipeId == null || !(level instanceof ServerLevel serverLevel)) {
            return null;
        }
        return serverLevel.getRecipeManager().byKey(activeRecipeId)
                .filter(MetallurgicPurifyingRecipe.class::isInstance)
                .map(MetallurgicPurifyingRecipe.class::cast)
                .orElseThrow(() -> new IllegalStateException("purifier active recipe is absent: " + activeRecipeId));
    }

    private static PurifyingRuntime runtime(MetallurgicPurifyingRecipe recipe) {
        return PowerMachineConfig.purifying(recipe.profile());
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
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (!stack.isEmpty()) {
                Containers.dropItemStack(level, worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(), stack);
                inventory.setStackInSlot(slot, ItemStack.EMPTY);
            }
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
        return new MetallurgicPurifierMenu(windowId, inventory, worldPosition);
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt(K_SCHEMA_VERSION, NBT_SCHEMA_VERSION);
        tag.put(K_ITEMS, inventory.serializeNBT());
        CompoundTag energyTag = new CompoundTag();
        energy.save(energyTag);
        tag.put(K_ENERGY, energyTag);
        if (infusionType != null) {
            tag.putString(K_INFUSION_TYPE, infusionType.toString());
        }
        tag.putInt(K_INFUSION_UNITS, infusionUnits);
        if (activeRecipeId != null) {
            tag.putString(K_ACTIVE_RECIPE, activeRecipeId.toString());
        }
        tag.putInt(K_PROGRESS, progress);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (!tag.contains(K_SCHEMA_VERSION, Tag.TAG_INT) || tag.getInt(K_SCHEMA_VERSION) != NBT_SCHEMA_VERSION
                || !tag.contains(K_ITEMS, Tag.TAG_COMPOUND) || !tag.contains(K_ENERGY, Tag.TAG_COMPOUND)
                || !tag.contains(K_INFUSION_UNITS, Tag.TAG_INT) || !tag.contains(K_PROGRESS, Tag.TAG_INT)) {
            throw new IllegalStateException("invalid purifier NBT at " + worldPosition);
        }
        inventory.deserializeNBT(tag.getCompound(K_ITEMS));
        energy.load(tag.getCompound(K_ENERGY));
        infusionUnits = tag.getInt(K_INFUSION_UNITS);
        if (infusionUnits < 0) {
            throw new IllegalStateException("invalid purifier infusion amount at " + worldPosition);
        }
        infusionType = readOptionalId(tag, K_INFUSION_TYPE);
        activeRecipeId = readOptionalId(tag, K_ACTIVE_RECIPE);
        progress = tag.getInt(K_PROGRESS);
        if (progress < 0 || (infusionUnits == 0 && infusionType != null) || (infusionUnits > 0 && infusionType == null)) {
            throw new IllegalStateException("inconsistent purifier NBT at " + worldPosition);
        }
    }

    private static @Nullable ResourceLocation readOptionalId(CompoundTag tag, String key) {
        if (!tag.contains(key, Tag.TAG_STRING)) {
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(tag.getString(key));
        if (id == null) {
            throw new IllegalStateException("invalid resource id in machine NBT: " + key);
        }
        return id;
    }
}
