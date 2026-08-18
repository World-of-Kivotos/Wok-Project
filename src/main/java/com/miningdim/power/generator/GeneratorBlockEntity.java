package com.miningdim.power.generator;

import com.miningdim.power.GeneratorMultiblockBlock;
import com.miningdim.power.PowerRegistry;
import com.miningdim.power.grid.CableThermics;
import com.miningdim.power.grid.VoltageClass;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;
import org.jetbrains.annotations.Nullable;

/** 3x2x2 发电机唯一的状态、库存、热学和内部 FE 权威。 */
public final class GeneratorBlockEntity extends BlockEntity implements MenuProvider {

    public static final int SLOT_FUEL_CORE = 0;
    public static final int SLOT_NICHROME_FUSE = 1;
    public static final int SLOT_COUNT = 2;
    public static final int NBT_SCHEMA_VERSION = 1;

    private static final String K_SCHEMA_VERSION = "schemaVersion";
    private static final String K_SPEC_ID = "specId";
    private static final String K_STATE = "state";
    private static final String K_STORED_FE = "storedFe";
    private static final String K_TEMPERATURE = "temperature";
    private static final String K_REACTION_REMAINDER = "reactionTickRemainder";
    private static final String K_FUEL_CORE = "fuelCore";
    private static final String K_FUSE_STATE = "fuseState";
    private static final String K_NICHROME_FUSE = "nichromeFuse";
    private static final String K_NETWORK_FAULT = "networkFault";
    private static final String K_FAULT_SOURCE_VOLTAGE = "faultSourceVoltage";
    private static final String K_FAULT_NETWORK_LIMIT = "faultNetworkLimit";
    private static final String K_BUFFER_REJECTION = "bufferRejectionFe";
    private static final String K_PORT_REBUILD_REQUIRED = "portRebuildRequired";
    private static final String K_PORT_LINK_VERSION = "portLinkVersion";

    private GeneratorState state = GeneratorState.IDLE;
    private GeneratorFuseState fuseState = GeneratorFuseState.ABSENT;
    private GeneratorNetworkFault networkFault = GeneratorNetworkFault.NONE;
    private VoltageClass faultSourceVoltage = VoltageClass.LOW;
    private VoltageClass faultNetworkLimit = VoltageClass.EXTREME;
    private int storedFe;
    private double temperatureC = CableThermics.AMBIENT_C;
    private int reactionTickRemainder;
    private int bufferRejectionFe;
    private boolean portRebuildRequired = true;
    private long portLinkVersion = 1L;
    private long extractionTick = Long.MIN_VALUE;
    private int extractedThisTick;
    private boolean loadingInventory;

    private final ItemStackHandler inventory = new ItemStackHandler(SLOT_COUNT) {
        @Override
        public int getSlotLimit(int slot) {
            validateGeneratorSlot(slot);
            return 1;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return switch (slot) {
                case SLOT_FUEL_CORE -> isFuelCoreForThisGenerator(stack);
                case SLOT_NICHROME_FUSE -> stack.is(PowerRegistry.NICHROME_FUSE.get());
                default -> throw new IllegalArgumentException("invalid generator inventory slot: " + slot);
            };
        }

        @Override
        protected void onContentsChanged(int slot) {
            if (loadingInventory) {
                return;
            }
            setChanged();
            reconcileInventoryState();
        }
    };

    private final IItemHandler automationInventory = new IItemHandler() {
        @Override
        public int getSlots() {
            return SLOT_COUNT;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            validateGeneratorSlot(slot);
            return inventory.getStackInSlot(slot);
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            validateGeneratorSlot(slot);
            return inventory.insertItem(slot, stack, simulate);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            validateGeneratorSlot(slot);
            return ItemStack.EMPTY;
        }

        @Override
        public int getSlotLimit(int slot) {
            validateGeneratorSlot(slot);
            return 1;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            validateGeneratorSlot(slot);
            return inventory.isItemValid(slot, stack);
        }
    };

    private final LazyOptional<IItemHandler> itemHandler = LazyOptional.of(() -> automationInventory);

    public GeneratorBlockEntity(BlockPos pos, BlockState state) {
        super(PowerRegistry.GENERATOR_CONTROLLER_BE.get(), pos, state);
        if (!GeneratorMultiblockBlock.isAnchor(state)) {
            throw new IllegalArgumentException("generator controller requires anchor state at " + pos);
        }
    }

    public GeneratorSpec spec() {
        return GeneratorSpec.forBlock(getBlockState().getBlock());
    }

    public GeneratorSpec.Runtime runtime() {
        return spec().runtime();
    }

    public GeneratorState state() {
        return state;
    }

    public GeneratorFuseState fuseState() {
        return fuseState;
    }

    public GeneratorNetworkFault networkFault() {
        return networkFault;
    }

    public VoltageClass faultNetworkLimit() {
        return faultNetworkLimit;
    }

    public int storedFe() {
        return storedFe;
    }

    public int bufferCapacityFe() {
        return runtime().bufferCapacityFe();
    }

    public double temperatureC() {
        return temperatureC;
    }

    public double ambientTemperatureC() {
        return CableThermics.AMBIENT_C;
    }

    public double meltdownTemperatureC() {
        return runtime().meltdownTemperatureC();
    }

    public int bufferRejectionFe() {
        return bufferRejectionFe;
    }

    public int reactionTickRemainder() {
        return reactionTickRemainder;
    }

    public ItemStackHandler inventory() {
        return inventory;
    }

    public ItemStack fuelCore() {
        return inventory.getStackInSlot(SLOT_FUEL_CORE);
    }

    public ItemStack nichromeFuse() {
        return inventory.getStackInSlot(SLOT_NICHROME_FUSE);
    }

    public boolean portRebuildRequired() {
        return portRebuildRequired;
    }

    public long portLinkVersion() {
        return portLinkVersion;
    }

    public BlockPos portPos() {
        BlockState state = getBlockState();
        return GeneratorMultiblockBlock.partPos(worldPosition,
                state.getValue(GeneratorMultiblockBlock.FACING), GeneratorMultiblockBlock.PORT_PART);
    }

    public Direction outputDirection() {
        return getBlockState().getValue(GeneratorMultiblockBlock.FACING).getOpposite();
    }

    public boolean isMeltdown() {
        return state == GeneratorState.MELTDOWN;
    }

    public int extractForNetwork(int maxExtract, boolean simulate) {
        if (maxExtract <= 0 || state == GeneratorState.MELTDOWN) {
            return 0;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return 0;
        }
        long now = serverLevel.getGameTime();
        int alreadyExtracted = extractionTick == now ? extractedThisTick : 0;
        int remainingThisTick = runtime().peakFePerTick() - alreadyExtracted;
        if (remainingThisTick < 0) {
            throw new IllegalStateException("generator extraction invariant broken at " + worldPosition);
        }
        int extracted = Math.min(maxExtract, Math.min(storedFe, remainingThisTick));
        if (!simulate && extracted > 0) {
            storedFe -= extracted;
            extractionTick = now;
            extractedThisTick = alreadyExtracted + extracted;
            setChanged();
        }
        return extracted;
    }

    public void reportOvervoltage(VoltageClass networkLimit) {
        if (networkFault == GeneratorNetworkFault.OVER_VOLTAGE
                && faultSourceVoltage == spec().sourceVoltage()
                && faultNetworkLimit == networkLimit) {
            return;
        }
        networkFault = GeneratorNetworkFault.OVER_VOLTAGE;
        faultSourceVoltage = spec().sourceVoltage();
        faultNetworkLimit = networkLimit;
        setChanged();
    }

    public void serverTick() {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (state == GeneratorState.MELTDOWN) {
            return;
        }
        if (state == GeneratorState.RUNNING) {
            tickRunning(serverLevel);
            return;
        }
        int previousRejection = bufferRejectionFe;
        double previousTemperature = temperatureC;
        GeneratorState previousState = state;
        bufferRejectionFe = 0;
        coolWithoutReaction();
        if (state == GeneratorState.SCRAM) {
            tryResumeFromScram();
        }
        if (previousRejection != bufferRejectionFe
                || Double.compare(previousTemperature, temperatureC) != 0
                || previousState != state) {
            setChanged();
        }
    }

    private void tickRunning(ServerLevel serverLevel) {
        ItemStack fuel = inventory.getStackInSlot(SLOT_FUEL_CORE);
        if (!isFuelCoreForThisGenerator(fuel)) {
            state = GeneratorState.IDLE;
            reactionTickRemainder = 0;
            bufferRejectionFe = 0;
            coolWithoutReaction();
            setChanged();
            return;
        }

        GeneratorSpec.Runtime runtime = runtime();
        int room = runtime.bufferCapacityFe() - storedFe;
        if (room < 0) {
            throw new IllegalStateException("generator buffer exceeds capacity at " + worldPosition);
        }
        int accepted = Math.min(room, runtime.peakFePerTick());
        storedFe += accepted;
        bufferRejectionFe = runtime.peakFePerTick() - accepted;

        reactionTickRemainder++;
        if (reactionTickRemainder == 20) {
            reactionTickRemainder = 0;
            if (fuel.hurt(1, serverLevel.random, null)) {
                inventory.setStackInSlot(SLOT_FUEL_CORE, ItemStack.EMPTY);
                state = GeneratorState.IDLE;
            } else {
                setChanged();
            }
        }

        settleTemperature(runtime);
        if (temperatureC >= runtime.meltdownTemperatureC()) {
            state = GeneratorState.MELTDOWN;
            setChanged();
            GeneratorMeltdown.execute(this);
            return;
        }
        if (state == GeneratorState.RUNNING && fuseState == GeneratorFuseState.INSTALLED
                && thermalDelta() >= meltdownDelta(runtime) * 0.85D) {
            inventory.setStackInSlot(SLOT_NICHROME_FUSE, ItemStack.EMPTY);
            fuseState = GeneratorFuseState.TRIPPED;
            state = GeneratorState.SCRAM;
        }
        setChanged();
    }

    private void settleTemperature(GeneratorSpec.Runtime runtime) {
        if (bufferRejectionFe > 0) {
            temperatureC += runtime.maxRejectedTemperatureRiseCPerTick()
                    * ((double) bufferRejectionFe / runtime.peakFePerTick());
            return;
        }
        coolWithoutReaction();
    }

    private void coolWithoutReaction() {
        double cooled = temperatureC - runtime().lowLoadCoolingCPerTick();
        temperatureC = cooled < CableThermics.AMBIENT_C ? CableThermics.AMBIENT_C : cooled;
    }

    private void tryResumeFromScram() {
        if (fuseState != GeneratorFuseState.INSTALLED || !isFuelCoreForThisGenerator(fuelCore())) {
            return;
        }
        if (thermalDelta() <= meltdownDelta(runtime()) * 0.50D) {
            state = GeneratorState.RUNNING;
        }
    }

    private void reconcileInventoryState() {
        ItemStack fuse = inventory.getStackInSlot(SLOT_NICHROME_FUSE);
        fuseState = fuse.isEmpty() ? (fuseState == GeneratorFuseState.TRIPPED
                ? GeneratorFuseState.TRIPPED : GeneratorFuseState.ABSENT) : GeneratorFuseState.INSTALLED;
        if (state == GeneratorState.MELTDOWN) {
            return;
        }
        if (!isFuelCoreForThisGenerator(fuelCore())) {
            if (state != GeneratorState.SCRAM) {
                state = GeneratorState.IDLE;
            }
            reactionTickRemainder = 0;
            return;
        }
        if (fuseState == GeneratorFuseState.TRIPPED) {
            state = GeneratorState.SCRAM;
            return;
        }
        if (state == GeneratorState.IDLE) {
            state = GeneratorState.RUNNING;
            return;
        }
        if (state == GeneratorState.SCRAM) {
            tryResumeFromScram();
        }
    }

    private boolean isFuelCoreForThisGenerator(ItemStack stack) {
        return stack.getItem() instanceof GeneratorFuelCoreItem fuelCore
                && fuelCore.spec() == spec() && stack.getDamageValue() < stack.getMaxDamage();
    }

    private double thermalDelta() {
        return temperatureC - CableThermics.AMBIENT_C;
    }

    private static double meltdownDelta(GeneratorSpec.Runtime runtime) {
        return runtime.meltdownTemperatureC() - CableThermics.AMBIENT_C;
    }

    public void ensurePortProxy() {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        BlockPos portPos = portPos();
        if (!serverLevel.hasChunkAt(portPos)) {
            portRebuildRequired = true;
            return;
        }
        BlockState portState = serverLevel.getBlockState(portPos);
        if (!isExpectedPortState(portState, getBlockState())) {
            portRebuildRequired = true;
            return;
        }
        BlockEntity blockEntity = serverLevel.getBlockEntity(portPos);
        if (blockEntity == null) {
            portLinkVersion++;
            serverLevel.setBlockEntity(new GeneratorPortBlockEntity(portPos, portState));
            blockEntity = serverLevel.getBlockEntity(portPos);
        }
        if (!(blockEntity instanceof GeneratorPortBlockEntity port)) {
            portRebuildRequired = true;
            return;
        }
        port.linkTo(worldPosition, portLinkVersion);
        portRebuildRequired = false;
        setChanged();
    }

    public static @Nullable GeneratorBlockEntity ensureLegacyEntities(ServerLevel level, BlockPos anyPartPos) {
        if (!level.hasChunkAt(anyPartPos)) {
            return null;
        }
        BlockState anyPartState = level.getBlockState(anyPartPos);
        if (!(anyPartState.getBlock() instanceof GeneratorMultiblockBlock)) {
            return null;
        }
        BlockPos anchorPos = GeneratorMultiblockBlock.anchorPos(anyPartPos, anyPartState);
        if (!level.hasChunkAt(anchorPos)) {
            return null;
        }
        BlockState anchorState = level.getBlockState(anchorPos);
        if (!GeneratorMultiblockBlock.isAnchor(anchorState)
                || anchorState.getBlock() != anyPartState.getBlock()
                || anchorState.getValue(GeneratorMultiblockBlock.FACING)
                != anyPartState.getValue(GeneratorMultiblockBlock.FACING)) {
            return null;
        }
        Direction facing = anchorState.getValue(GeneratorMultiblockBlock.FACING);
        for (GeneratorMultiblockBlock.Part part : GeneratorMultiblockBlock.Part.values()) {
            BlockPos partPos = GeneratorMultiblockBlock.partPos(anchorPos, facing, part);
            if (!level.hasChunkAt(partPos)) {
                return null;
            }
            BlockState partState = level.getBlockState(partPos);
            if (partState.getBlock() != anchorState.getBlock()
                    || partState.getValue(GeneratorMultiblockBlock.FACING) != facing
                    || partState.getValue(GeneratorMultiblockBlock.PART) != part) {
                return null;
            }
        }
        BlockPos portPos = GeneratorMultiblockBlock.partPos(anchorPos,
                facing, GeneratorMultiblockBlock.PORT_PART);
        BlockState portState = level.getBlockState(portPos);
        BlockEntity anchorEntity = level.getBlockEntity(anchorPos);
        if (anchorEntity == null) {
            level.setBlockEntity(new GeneratorBlockEntity(anchorPos, anchorState));
            anchorEntity = level.getBlockEntity(anchorPos);
        }
        if (!(anchorEntity instanceof GeneratorBlockEntity controller)) {
            throw new IllegalStateException("generator anchor has incompatible block entity at " + anchorPos);
        }
        controller.ensurePortProxy();
        return controller;
    }

    private static boolean isExpectedPortState(BlockState candidate, BlockState anchorState) {
        return candidate.getBlock() == anchorState.getBlock()
                && candidate.getValue(GeneratorMultiblockBlock.FACING)
                == anchorState.getValue(GeneratorMultiblockBlock.FACING)
                && candidate.getValue(GeneratorMultiblockBlock.PART) == GeneratorMultiblockBlock.PORT_PART;
    }

    public void dropInternalContents() {
        if (level == null || state == GeneratorState.MELTDOWN) {
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
    public void onLoad() {
        super.onLoad();
        ensurePortProxy();
    }

    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> capability, @Nullable Direction side) {
        if (capability == ForgeCapabilities.ITEM_HANDLER) {
            return itemHandler.cast();
        }
        return super.getCapability(capability, side);
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        itemHandler.invalidate();
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable(getBlockState().getBlock().getDescriptionId());
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int windowId, Inventory inventory, Player player) {
        return new GeneratorMenu(windowId, inventory, worldPosition);
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt(K_SCHEMA_VERSION, NBT_SCHEMA_VERSION);
        tag.putString(K_SPEC_ID, spec().id());
        tag.putString(K_STATE, state.name());
        tag.putInt(K_STORED_FE, storedFe);
        tag.putDouble(K_TEMPERATURE, temperatureC);
        tag.putInt(K_REACTION_REMAINDER, reactionTickRemainder);
        if (!fuelCore().isEmpty()) {
            tag.put(K_FUEL_CORE, fuelCore().save(new CompoundTag()));
        }
        tag.putString(K_FUSE_STATE, fuseState.name());
        if (!nichromeFuse().isEmpty()) {
            tag.put(K_NICHROME_FUSE, nichromeFuse().save(new CompoundTag()));
        }
        tag.putString(K_NETWORK_FAULT, networkFault.name());
        tag.putString(K_FAULT_SOURCE_VOLTAGE, faultSourceVoltage.name());
        tag.putString(K_FAULT_NETWORK_LIMIT, faultNetworkLimit.name());
        tag.putInt(K_BUFFER_REJECTION, bufferRejectionFe);
        tag.putBoolean(K_PORT_REBUILD_REQUIRED, portRebuildRequired);
        tag.putLong(K_PORT_LINK_VERSION, portLinkVersion);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (!tag.contains(K_SCHEMA_VERSION, Tag.TAG_INT)) {
            if (containsModernGeneratorField(tag)) {
                throw new IllegalStateException("partial generator NBT without schema at " + worldPosition);
            }
            resetLegacyShellState();
            return;
        }
        if (tag.getInt(K_SCHEMA_VERSION) != NBT_SCHEMA_VERSION) {
            throw new IllegalStateException("unsupported generator NBT schema at " + worldPosition);
        }
        require(tag, K_SPEC_ID, Tag.TAG_STRING);
        if (GeneratorSpec.byId(tag.getString(K_SPEC_ID)) != spec()) {
            throw new IllegalStateException("generator NBT spec does not match block at " + worldPosition);
        }
        require(tag, K_STATE, Tag.TAG_STRING);
        require(tag, K_STORED_FE, Tag.TAG_INT);
        require(tag, K_TEMPERATURE, Tag.TAG_DOUBLE);
        require(tag, K_REACTION_REMAINDER, Tag.TAG_INT);
        require(tag, K_FUSE_STATE, Tag.TAG_STRING);
        require(tag, K_NETWORK_FAULT, Tag.TAG_STRING);
        require(tag, K_FAULT_SOURCE_VOLTAGE, Tag.TAG_STRING);
        require(tag, K_FAULT_NETWORK_LIMIT, Tag.TAG_STRING);
        require(tag, K_BUFFER_REJECTION, Tag.TAG_INT);
        require(tag, K_PORT_REBUILD_REQUIRED, Tag.TAG_BYTE);
        require(tag, K_PORT_LINK_VERSION, Tag.TAG_LONG);
        state = enumValue(GeneratorState.class, tag.getString(K_STATE), K_STATE);
        storedFe = tag.getInt(K_STORED_FE);
        temperatureC = tag.getDouble(K_TEMPERATURE);
        reactionTickRemainder = tag.getInt(K_REACTION_REMAINDER);
        fuseState = enumValue(GeneratorFuseState.class, tag.getString(K_FUSE_STATE), K_FUSE_STATE);
        networkFault = enumValue(GeneratorNetworkFault.class, tag.getString(K_NETWORK_FAULT), K_NETWORK_FAULT);
        faultSourceVoltage = enumValue(VoltageClass.class, tag.getString(K_FAULT_SOURCE_VOLTAGE), K_FAULT_SOURCE_VOLTAGE);
        faultNetworkLimit = enumValue(VoltageClass.class, tag.getString(K_FAULT_NETWORK_LIMIT), K_FAULT_NETWORK_LIMIT);
        bufferRejectionFe = tag.getInt(K_BUFFER_REJECTION);
        portRebuildRequired = tag.getBoolean(K_PORT_REBUILD_REQUIRED);
        portLinkVersion = tag.getLong(K_PORT_LINK_VERSION);
        if (storedFe < 0 || storedFe > bufferCapacityFe() || !Double.isFinite(temperatureC)
                || temperatureC < CableThermics.AMBIENT_C
                || reactionTickRemainder < 0 || reactionTickRemainder >= 20 || bufferRejectionFe < 0
                || bufferRejectionFe > runtime().peakFePerTick()
                || portLinkVersion <= 0L) {
            throw new IllegalStateException("generator NBT values violate runtime contract at " + worldPosition);
        }
        ItemStack fuel = readOptionalStack(tag, K_FUEL_CORE);
        ItemStack fuse = readOptionalStack(tag, K_NICHROME_FUSE);
        if (!fuel.isEmpty() && !isFuelCoreForThisGenerator(fuel)) {
            throw new IllegalStateException("generator NBT has wrong fuel core at " + worldPosition);
        }
        if (!fuse.isEmpty() && !fuse.is(PowerRegistry.NICHROME_FUSE.get())) {
            throw new IllegalStateException("generator NBT has wrong fuse item at " + worldPosition);
        }
        if (fuseState == GeneratorFuseState.INSTALLED && fuse.isEmpty()) {
            throw new IllegalStateException("installed generator fuse is absent at " + worldPosition);
        }
        if (fuseState != GeneratorFuseState.INSTALLED && !fuse.isEmpty()) {
            throw new IllegalStateException("generator fuse item conflicts with fuse state at " + worldPosition);
        }
        if (state == GeneratorState.RUNNING && fuel.isEmpty()) {
            throw new IllegalStateException("running generator has no fuel core at " + worldPosition);
        }
        if (state == GeneratorState.IDLE && !fuel.isEmpty()) {
            throw new IllegalStateException("idle generator has an active fuel core at " + worldPosition);
        }
        loadingInventory = true;
        try {
            inventory.setStackInSlot(SLOT_FUEL_CORE, fuel);
            inventory.setStackInSlot(SLOT_NICHROME_FUSE, fuse);
        } finally {
            loadingInventory = false;
        }
    }

    private void resetLegacyShellState() {
        state = GeneratorState.IDLE;
        fuseState = GeneratorFuseState.ABSENT;
        networkFault = GeneratorNetworkFault.NONE;
        faultSourceVoltage = spec().sourceVoltage();
        faultNetworkLimit = VoltageClass.EXTREME;
        storedFe = 0;
        temperatureC = CableThermics.AMBIENT_C;
        reactionTickRemainder = 0;
        bufferRejectionFe = 0;
        portRebuildRequired = true;
        portLinkVersion = 1L;
        loadingInventory = true;
        try {
            inventory.setStackInSlot(SLOT_FUEL_CORE, ItemStack.EMPTY);
            inventory.setStackInSlot(SLOT_NICHROME_FUSE, ItemStack.EMPTY);
        } finally {
            loadingInventory = false;
        }
    }

    private static void require(CompoundTag tag, String key, int type) {
        if (!tag.contains(key, type)) {
            throw new IllegalStateException("generator NBT missing " + key);
        }
    }

    private static boolean containsModernGeneratorField(CompoundTag tag) {
        return tag.contains(K_SPEC_ID) || tag.contains(K_STATE) || tag.contains(K_STORED_FE)
                || tag.contains(K_TEMPERATURE) || tag.contains(K_REACTION_REMAINDER)
                || tag.contains(K_FUEL_CORE) || tag.contains(K_FUSE_STATE) || tag.contains(K_NICHROME_FUSE)
                || tag.contains(K_NETWORK_FAULT) || tag.contains(K_FAULT_SOURCE_VOLTAGE)
                || tag.contains(K_FAULT_NETWORK_LIMIT) || tag.contains(K_BUFFER_REJECTION)
                || tag.contains(K_PORT_REBUILD_REQUIRED) || tag.contains(K_PORT_LINK_VERSION);
    }

    private static ItemStack readOptionalStack(CompoundTag tag, String key) {
        return tag.contains(key, Tag.TAG_COMPOUND) ? ItemStack.of(tag.getCompound(key)) : ItemStack.EMPTY;
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String field) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("generator NBT has invalid " + field + ": " + value, exception);
        }
    }

    private static void validateGeneratorSlot(int slot) {
        if (slot < 0 || slot >= SLOT_COUNT) {
            throw new IllegalArgumentException("invalid generator inventory slot: " + slot);
        }
    }
}
