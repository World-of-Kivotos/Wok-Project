package com.miningdim.job.munitions.block;

import com.miningdim.job.munitions.ModMunitionsBlockEntities;
import com.miningdim.job.munitions.ModMunitionsSounds;
import com.miningdim.job.munitions.MunitionsConfig;
import com.miningdim.job.munitions.gunsmith.GunsmithAssemblyRecipe;
import com.miningdim.job.munitions.gunsmith.GunsmithBlueprint;
import com.miningdim.job.munitions.gunsmith.GunsmithGunFactory;
import com.miningdim.job.munitions.gunsmith.GunsmithPlatform;
import com.miningdim.job.munitions.gunsmith.GunsmithPressPart;
import com.miningdim.job.munitions.menu.GunsmithAssemblyMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.items.ItemStackHandler;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

public final class GunsmithAssemblyBenchBlockEntity extends BlockEntity implements MenuProvider {

    public static final int SLOT_BLUEPRINT = 0;
    public static final int SLOT_PART_BASE = 1;
    public static final int SLOT_OUTPUT = SLOT_PART_BASE + GunsmithPressPart.values().length;
    public static final int SLOT_COUNT = SLOT_OUTPUT + 1;
    public static final int ASSEMBLY_DURATION_TICKS = 160;

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/gunsmith-assembly");
    private static final int LEGACY_RIFLE_PART_COUNT = 6;
    private static final int LEGACY_RIFLE_SLOT_OUTPUT = SLOT_PART_BASE + LEGACY_RIFLE_PART_COUNT;
    private static final int LEGACY_RIFLE_SLOT_COUNT = LEGACY_RIFLE_SLOT_OUTPUT + 1;
    private static final int LEGACY_PRE_RECEIVER_SLOT_OUTPUT = 10;
    private static final int LEGACY_PRE_RECEIVER_SLOT_COUNT = LEGACY_PRE_RECEIVER_SLOT_OUTPUT + 1;
    private static final int LEGACY_PRE_BIPOD_SLOT_OUTPUT = 11;
    private static final int LEGACY_PRE_BIPOD_SLOT_COUNT = LEGACY_PRE_BIPOD_SLOT_OUTPUT + 1;
    private static final int WELD_SOUND_INTERVAL_TICKS = 24;
    private static final String K_INVENTORY = "Inventory";
    private static final String K_HANDLER_SIZE = "Size";
    private static final String K_PENDING_RESULT = "PendingResult";
    private static final String K_ANIMATION_END = "AnimationEndTick";

    private long animationEndTick;
    private long nextWeldSoundTick;
    private ItemStack pendingResult = ItemStack.EMPTY;
    private boolean pendingBlockedReported;

    private final ItemStackHandler inventory = new ItemStackHandler(SLOT_COUNT) {
        @Override
        protected void onContentsChanged(int slot) {
            if (slot == SLOT_OUTPUT) {
                pendingBlockedReported = false;
            }
            setChanged();
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            if (slot == SLOT_BLUEPRINT) {
                return GunsmithAssemblyRecipe.isBlueprint(stack);
            }
            if (slot >= SLOT_PART_BASE && slot < SLOT_OUTPUT) {
                ItemStack blueprintStack = getStackInSlot(SLOT_BLUEPRINT);
                if (!GunsmithAssemblyRecipe.isBlueprint(blueprintStack)) {
                    return false;
                }
                GunsmithBlueprint blueprint = GunsmithAssemblyRecipe.blueprint(blueprintStack);
                GunsmithPressPart part = partForSlot(slot);
                return blueprint.requiredParts().contains(part)
                        && GunsmithAssemblyRecipe.matchesPart(stack, part, blueprint.platform());
            }
            return false;
        }

        @Override
        public int getSlotLimit(int slot) {
            return 1;
        }
    };

    public GunsmithAssemblyBenchBlockEntity(BlockPos pos, BlockState state) {
        super(ModMunitionsBlockEntities.GUNSMITH_ASSEMBLY_BENCH.get(), pos, state);
    }

    public static int slotForPart(GunsmithPressPart part) {
        return SLOT_PART_BASE + part.index();
    }

    private static GunsmithPressPart partForSlot(int slot) {
        int index = slot - SLOT_PART_BASE;
        GunsmithPressPart[] parts = GunsmithPressPart.values();
        if (index < 0 || index >= parts.length) {
            throw new IllegalArgumentException("slot is not a gunsmith part slot: " + slot);
        }
        return parts[index];
    }

    public ItemStackHandler inventory() {
        return inventory;
    }

    public boolean isPartSlotVisible(GunsmithPressPart part) {
        Objects.requireNonNull(part, "part");
        ItemStack blueprintStack = inventory.getStackInSlot(SLOT_BLUEPRINT);
        return GunsmithAssemblyRecipe.isBlueprint(blueprintStack)
                && GunsmithAssemblyRecipe.blueprint(blueprintStack).requiredParts().contains(part);
    }

    public boolean tryStartAssembly(ServerPlayer player) {
        return tryStartAssembly(player, GunsmithGunFactory::materialize, ASSEMBLY_DURATION_TICKS);
    }

    boolean tryStartAssembly(ServerPlayer player, ItemStack baseGun, int durationTicks) {
        Objects.requireNonNull(baseGun, "baseGun");
        return tryStartAssembly(player, blueprintStack -> baseGun, durationTicks);
    }

    private boolean tryStartAssembly(ServerPlayer player,
                                     Function<ItemStack, ItemStack> gunFactory,
                                     int durationTicks) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(gunFactory, "gunFactory");
        if (durationTicks <= 0) {
            throw new IllegalArgumentException("durationTicks must be positive");
        }
        if (!MunitionsConfig.GUNSMITH_ENABLED.get()) {
            player.displayClientMessage(Component.translatable("message.miningdim.gunsmith.disabled"), true);
            return false;
        }
        if (isAnimating() || !pendingResult.isEmpty()) {
            player.displayClientMessage(
                    Component.translatable("message.miningdim.gunsmith_assembly_bench.busy"), true);
            return false;
        }
        if (!inventory.getStackInSlot(SLOT_OUTPUT).isEmpty()) {
            player.displayClientMessage(
                    Component.translatable("message.miningdim.gunsmith_assembly_bench.output_blocked"), true);
            return false;
        }
        ItemStack blueprintStack = inventory.getStackInSlot(SLOT_BLUEPRINT);
        if (!GunsmithAssemblyRecipe.isBlueprint(blueprintStack)) {
            player.displayClientMessage(
                    Component.translatable("message.miningdim.gunsmith_assembly_bench.missing_blueprint"), true);
            return false;
        }
        GunsmithBlueprint blueprint = GunsmithAssemblyRecipe.blueprint(blueprintStack);
        GunsmithPlatform platform = blueprint.platform();

        EnumMap<GunsmithPressPart, ItemStack> parts = snapshotParts();
        for (GunsmithPressPart part : blueprint.requiredParts()) {
            if (!GunsmithAssemblyRecipe.matchesPart(parts.get(part), part, platform)) {
                player.displayClientMessage(Component.translatable(
                        "message.miningdim.gunsmith_assembly_bench.missing_part",
                        Component.translatable(part.labelKey())), true);
                return false;
            }
        }
        ItemStack baseGun = Objects.requireNonNull(gunFactory.apply(blueprintStack),
                "gunFactory returned null for " + blueprint.gunId());
        if (baseGun.isEmpty()) {
            player.displayClientMessage(
                    Component.translatable("message.miningdim.gunsmith_blueprint.tacz_missing"), true);
            return false;
        }

        ItemStack result = GunsmithAssemblyRecipe.assemble(baseGun, blueprintStack, parts);
        for (GunsmithPressPart part : blueprint.requiredParts()) {
            inventory.extractItem(slotForPart(part), 1, false);
        }
        pendingResult = result;
        beginAnimation(durationTicks);
        player.closeContainer();
        player.displayClientMessage(
                Component.translatable("message.miningdim.gunsmith_assembly_bench.started"), true);
        return true;
    }

    private EnumMap<GunsmithPressPart, ItemStack> snapshotParts() {
        EnumMap<GunsmithPressPart, ItemStack> parts = new EnumMap<>(GunsmithPressPart.class);
        for (GunsmithPressPart part : GunsmithPressPart.values()) {
            parts.put(part, inventory.getStackInSlot(slotForPart(part)).copyWithCount(1));
        }
        return parts;
    }

    private void beginAnimation(int durationTicks) {
        if (level == null || level.isClientSide) {
            throw new IllegalStateException("assembly can only start on the logical server");
        }
        long now = level.getGameTime();
        animationEndTick = now + durationTicks;
        nextWeldSoundTick = now + WELD_SOUND_INTERVAL_TICKS;
        setActiveState(true);
        playWeldSound();
        setChanged();
    }

    public void serverTick() {
        if (level == null || level.isClientSide) {
            return;
        }
        if (animationEndTick == 0L) {
            if (getBlockState().getValue(GunsmithAssemblyBenchBlock.ACTIVE)) {
                setActiveState(false);
            }
            finishPendingResult();
            return;
        }

        long now = level.getGameTime();
        if (now >= animationEndTick) {
            animationEndTick = 0L;
            nextWeldSoundTick = 0L;
            setActiveState(false);
            finishPendingResult();
            setChanged();
            return;
        }
        setActiveState(true);
        if (now >= nextWeldSoundTick) {
            playWeldSound();
            nextWeldSoundTick = now + WELD_SOUND_INTERVAL_TICKS;
        }
    }

    private void finishPendingResult() {
        if (pendingResult.isEmpty()) {
            return;
        }
        if (!inventory.getStackInSlot(SLOT_OUTPUT).isEmpty()) {
            if (!pendingBlockedReported) {
                LOGGER.error("Assembly output blocked at {} while a pending result exists", worldPosition);
                pendingBlockedReported = true;
            }
            return;
        }
        inventory.setStackInSlot(SLOT_OUTPUT, pendingResult);
        pendingResult = ItemStack.EMPTY;
        pendingBlockedReported = false;
        setChanged();
    }

    public boolean isAnimating() {
        if (level == null) {
            return animationEndTick > 0L;
        }
        if (level.isClientSide) {
            return getBlockState().getValue(GunsmithAssemblyBenchBlock.ACTIVE);
        }
        return animationEndTick > level.getGameTime();
    }

    private void setActiveState(boolean active) {
        if (level == null) {
            return;
        }
        BlockState state = getBlockState();
        if (state.getBlock() instanceof GunsmithAssemblyBenchBlock
                && GunsmithAssemblyBenchBlock.isMain(state)) {
            GunsmithAssemblyBenchBlock.setStructureActive(level, worldPosition, state, active);
        }
    }

    private void playWeldSound() {
        if (level == null) {
            return;
        }
        float pitch = 0.94F + level.random.nextFloat() * 0.14F;
        level.playSound(null, worldPosition, ModMunitionsSounds.MUNITIONS_BENCH_WELD.get(),
                SoundSource.BLOCKS, 0.34F, pitch);
    }

    public List<ItemStack> dropContents() {
        List<ItemStack> drops = new ArrayList<>();
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack extracted = inventory.extractItem(slot, Integer.MAX_VALUE, false);
            if (!extracted.isEmpty()) {
                drops.add(extracted);
            }
        }
        if (!pendingResult.isEmpty()) {
            drops.add(pendingResult);
            pendingResult = ItemStack.EMPTY;
        }
        setChanged();
        return drops;
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.miningdim.gunsmith_assembly_bench");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int windowId, Inventory playerInventory, Player player) {
        return new GunsmithAssemblyMenu(windowId, playerInventory, worldPosition);
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.put(K_INVENTORY, inventory.serializeNBT());
        if (!pendingResult.isEmpty()) {
            tag.put(K_PENDING_RESULT, pendingResult.save(new CompoundTag()));
        }
        tag.putLong(K_ANIMATION_END, animationEndTick);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        // Missing inventory/pending keys are the initial state for benches placed before the assembly UI existed.
        if (tag.contains(K_INVENTORY, Tag.TAG_COMPOUND)) {
            loadInventory(tag.getCompound(K_INVENTORY));
        }
        pendingResult = tag.contains(K_PENDING_RESULT, Tag.TAG_COMPOUND)
                ? ItemStack.of(tag.getCompound(K_PENDING_RESULT))
                : ItemStack.EMPTY;
        animationEndTick = tag.getLong(K_ANIMATION_END);
        nextWeldSoundTick = 0L;
        pendingBlockedReported = false;
    }

    private void loadInventory(CompoundTag serializedInventory) {
        if (!serializedInventory.contains(K_HANDLER_SIZE, Tag.TAG_INT)) {
            throw new IllegalStateException("Gunsmith assembly inventory is missing its serialized size at "
                    + worldPosition);
        }
        int serializedSize = serializedInventory.getInt(K_HANDLER_SIZE);
        if (serializedSize == SLOT_COUNT) {
            inventory.deserializeNBT(serializedInventory);
            return;
        }
        if (serializedSize == LEGACY_PRE_BIPOD_SLOT_COUNT) {
            migratePreBipodInventory(serializedInventory);
            LOGGER.info("Migrated pre-bipod gunsmith assembly inventory at {} from {} to {} slots",
                    worldPosition, LEGACY_PRE_BIPOD_SLOT_COUNT, SLOT_COUNT);
            return;
        }
        if (serializedSize == LEGACY_PRE_RECEIVER_SLOT_COUNT) {
            migratePreReceiverInventory(serializedInventory);
            LOGGER.info("Migrated pre-receiver gunsmith assembly inventory at {} from {} to {} slots",
                    worldPosition, LEGACY_PRE_RECEIVER_SLOT_COUNT, SLOT_COUNT);
            return;
        }
        // Saves from the rifle-only assembly bench used six part slots and stored output in slot 7.
        if (serializedSize == LEGACY_RIFLE_SLOT_COUNT) {
            migrateLegacyRifleInventory(serializedInventory);
            LOGGER.info("Migrated rifle-only gunsmith assembly inventory at {} from {} to {} slots",
                    worldPosition, LEGACY_RIFLE_SLOT_COUNT, SLOT_COUNT);
            return;
        }
        throw new IllegalStateException("Unsupported gunsmith assembly inventory size " + serializedSize
                + " at " + worldPosition + "; expected " + SLOT_COUNT + ", legacy "
                + LEGACY_PRE_BIPOD_SLOT_COUNT + ", legacy " + LEGACY_PRE_RECEIVER_SLOT_COUNT
                + " or legacy " + LEGACY_RIFLE_SLOT_COUNT);
    }

    private void migratePreBipodInventory(CompoundTag serializedInventory) {
        ItemStackHandler legacyInventory = new ItemStackHandler(LEGACY_PRE_BIPOD_SLOT_COUNT);
        legacyInventory.deserializeNBT(serializedInventory);

        ItemStackHandler migratedInventory = new ItemStackHandler(SLOT_COUNT);
        for (int slot = SLOT_BLUEPRINT; slot < LEGACY_PRE_BIPOD_SLOT_OUTPUT; slot++) {
            migratedInventory.setStackInSlot(slot, legacyInventory.getStackInSlot(slot).copy());
        }
        migratedInventory.setStackInSlot(SLOT_OUTPUT,
                legacyInventory.getStackInSlot(LEGACY_PRE_BIPOD_SLOT_OUTPUT).copy());
        inventory.deserializeNBT(migratedInventory.serializeNBT());
    }

    private void migratePreReceiverInventory(CompoundTag serializedInventory) {
        ItemStackHandler legacyInventory = new ItemStackHandler(LEGACY_PRE_RECEIVER_SLOT_COUNT);
        legacyInventory.deserializeNBT(serializedInventory);

        ItemStackHandler migratedInventory = new ItemStackHandler(SLOT_COUNT);
        for (int slot = SLOT_BLUEPRINT; slot < LEGACY_PRE_RECEIVER_SLOT_OUTPUT; slot++) {
            migratedInventory.setStackInSlot(slot, legacyInventory.getStackInSlot(slot).copy());
        }
        migratedInventory.setStackInSlot(SLOT_OUTPUT,
                legacyInventory.getStackInSlot(LEGACY_PRE_RECEIVER_SLOT_OUTPUT).copy());
        inventory.deserializeNBT(migratedInventory.serializeNBT());
    }

    private void migrateLegacyRifleInventory(CompoundTag serializedInventory) {
        ItemStackHandler legacyInventory = new ItemStackHandler(LEGACY_RIFLE_SLOT_COUNT);
        legacyInventory.deserializeNBT(serializedInventory);

        ItemStackHandler migratedInventory = new ItemStackHandler(SLOT_COUNT);
        for (int slot = SLOT_BLUEPRINT; slot < LEGACY_RIFLE_SLOT_OUTPUT; slot++) {
            migratedInventory.setStackInSlot(slot, legacyInventory.getStackInSlot(slot).copy());
        }
        migratedInventory.setStackInSlot(SLOT_OUTPUT,
                legacyInventory.getStackInSlot(LEGACY_RIFLE_SLOT_OUTPUT).copy());
        inventory.deserializeNBT(migratedInventory.serializeNBT());
    }

    @Override
    public AABB getRenderBoundingBox() {
        BlockState state = getBlockState();
        if (!(state.getBlock() instanceof GunsmithAssemblyBenchBlock)) {
            return super.getRenderBoundingBox();
        }
        DirectionBounds bounds = DirectionBounds.from(worldPosition, state.getValue(GunsmithAssemblyBenchBlock.FACING));
        return new AABB(bounds.minX, worldPosition.getY(), bounds.minZ,
                bounds.maxX + 1.0D, worldPosition.getY() + 2.25D, bounds.maxZ + 1.0D);
    }

    private record DirectionBounds(int minX, int maxX, int minZ, int maxZ) {
        private static DirectionBounds from(BlockPos mainPos, net.minecraft.core.Direction facing) {
            int minX = mainPos.getX();
            int maxX = mainPos.getX();
            int minZ = mainPos.getZ();
            int maxZ = mainPos.getZ();
            for (GunsmithAssemblyBenchBlock.Part part : GunsmithAssemblyBenchBlock.Part.values()) {
                BlockPos partPos = GunsmithAssemblyBenchBlock.partPos(mainPos, facing, part);
                minX = Math.min(minX, partPos.getX());
                maxX = Math.max(maxX, partPos.getX());
                minZ = Math.min(minZ, partPos.getZ());
                maxZ = Math.max(maxZ, partPos.getZ());
            }
            return new DirectionBounds(minX, maxX, minZ, maxZ);
        }
    }
}
