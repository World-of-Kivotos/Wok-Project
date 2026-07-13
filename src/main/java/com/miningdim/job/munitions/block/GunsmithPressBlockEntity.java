package com.miningdim.job.munitions.block;

import com.miningdim.job.munitions.ModMunitionsBlockEntities;
import com.miningdim.job.munitions.ModMunitionsItems;
import com.miningdim.job.munitions.ModMunitionsSounds;
import com.miningdim.job.munitions.gunsmith.GunsmithPartItem;
import com.miningdim.job.munitions.gunsmith.GunsmithPartQuality;
import com.miningdim.job.munitions.gunsmith.GunsmithPlatform;
import com.miningdim.job.munitions.gunsmith.GunsmithPressPart;
import com.miningdim.job.munitions.menu.GunsmithPressMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.items.ItemStackHandler;
import org.jetbrains.annotations.Nullable;

public final class GunsmithPressBlockEntity extends BlockEntity implements MenuProvider {

    public static final int SLOT_GUN_PARTS = 0;
    public static final int SLOT_ALLOY = 1;
    public static final int SLOT_POLYMER = 2;
    public static final int SLOT_OUTPUT = 3;
    public static final int SLOT_COUNT = 4;

    public static final int DATA_SELECTED_PLATFORM = 0;
    public static final int DATA_SELECTED_PART = 1;
    public static final int DATA_SELECTED_QUALITY = 2;
    public static final int DATA_PROGRESS_TICKS = 3;
    public static final int DATA_REQUIRED_TICKS = 4;
    public static final int DATA_ACTIVE = 5;
    public static final int DATA_COUNT = 6;
    private static final int HYDRAULIC_SOUND_INTERVAL = 34;

    private GunsmithPlatform selectedPlatform = GunsmithPlatform.AR;
    private GunsmithPressPart selectedPart = GunsmithPressPart.CORE;
    private GunsmithPartQuality selectedQuality = GunsmithPartQuality.COMMON;
    private long activeStartTick;
    private long activeUntilTick;
    private long nextHydraulicSoundTick;

    private final ItemStackHandler inventory = new ItemStackHandler(SLOT_COUNT) {
        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return slot != SLOT_OUTPUT;
        }
    };

    private final ContainerData dataAccess = new ContainerData() {
        @Override
        public int get(int index) {
            return switch (index) {
                case DATA_SELECTED_PLATFORM -> selectedPlatform.index();
                case DATA_SELECTED_PART -> selectedPart.index();
                case DATA_SELECTED_QUALITY -> selectedQuality.index();
                case DATA_PROGRESS_TICKS -> productionProgressTicks();
                case DATA_REQUIRED_TICKS -> productionRequiredTicks();
                case DATA_ACTIVE -> isPressing() ? 1 : 0;
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {
            // 服务端权威: 客户端只通过按钮请求修改。
        }

        @Override
        public int getCount() {
            return DATA_COUNT;
        }
    };

    public GunsmithPressBlockEntity(BlockPos pos, BlockState state) {
        super(ModMunitionsBlockEntities.GUNSMITH_PRESS.get(), pos, state);
    }

    public ItemStackHandler inventory() {
        return inventory;
    }

    public ContainerData dataAccess() {
        return dataAccess;
    }

    public GunsmithPlatform selectedPlatform() {
        return selectedPlatform;
    }

    public GunsmithPressPart selectedPart() {
        return selectedPart;
    }

    public GunsmithPartQuality selectedQuality() {
        return selectedQuality;
    }

    public boolean trySelectPlatform(int index) {
        if (isPressing()) {
            return false;
        }
        this.selectedPlatform = GunsmithPlatform.byIndex(index);
        normalizeSelectedPart();
        setChanged();
        return true;
    }

    public boolean trySelectPart(int compactIndex) {
        if (isPressing()) {
            return false;
        }
        int row = 0;
        for (GunsmithPressPart part : selectedPlatform.supportedParts()) {
            if (row++ == compactIndex) {
                if (!selectedPlatform.supports(part)) {
                    return false;
                }
                this.selectedPart = part;
                setChanged();
                return true;
            }
        }
        return false;
    }

    public boolean trySelectQuality(int index) {
        if (isPressing()) {
            return false;
        }
        this.selectedQuality = GunsmithPartQuality.byIndex(index);
        setChanged();
        return true;
    }

    public boolean tryStartPreview(ServerPlayer player) {
        if (isPressing()) {
            player.displayClientMessage(Component.translatable("message.miningdim.gunsmith_press.busy"), true);
            return false;
        }
        if (!selectedPlatform.supports(selectedPart)) {
            player.displayClientMessage(
                    Component.translatable("message.miningdim.gunsmith_press.unsupported_part"), true);
            return false;
        }
        if (!inventory.getStackInSlot(SLOT_OUTPUT).isEmpty()) {
            player.displayClientMessage(Component.translatable("message.miningdim.gunsmith_press.output_blocked"), true);
            return false;
        }
        if (!hasRequiredMaterials()) {
            player.displayClientMessage(Component.translatable("message.miningdim.gunsmith_press.missing_materials"), true);
            return false;
        }
        consumeRequiredMaterials();
        startPressRun();
        player.displayClientMessage(Component.translatable("message.miningdim.gunsmith_press.started"), true);
        return true;
    }

    public void serverTick() {
        if (level == null || level.isClientSide) {
            return;
        }
        long now = level.getGameTime();
        if (activeUntilTick <= 0L) {
            if (getBlockState().hasProperty(GunsmithPressBlock.ACTIVE)
                    && getBlockState().getValue(GunsmithPressBlock.ACTIVE)) {
                setActiveState(false);
            }
            return;
        }
        if (now >= activeUntilTick) {
            finishPressRun();
            return;
        }
        if (now >= nextHydraulicSoundTick) {
            playHydraulicSound();
            nextHydraulicSoundTick = now + HYDRAULIC_SOUND_INTERVAL;
        }
    }

    private void startPressRun() {
        if (level == null || level.isClientSide) {
            return;
        }
        long now = level.getGameTime();
        activeStartTick = now;
        activeUntilTick = now + productionRequiredTicks();
        nextHydraulicSoundTick = now + HYDRAULIC_SOUND_INTERVAL;
        setActiveState(true);
        playHydraulicSound();
        setChanged();
    }

    private void finishPressRun() {
        if (level == null || level.isClientSide) {
            return;
        }
        if (inventory.getStackInSlot(SLOT_OUTPUT).isEmpty()) {
            inventory.setStackInSlot(SLOT_OUTPUT, GunsmithPartItem.createRolledStack(
                    ModMunitionsItems.GUNSMITH_PART.get(), selectedPlatform, selectedPart, selectedQuality,
                    level.random));
        }
        activeStartTick = 0L;
        activeUntilTick = 0L;
        nextHydraulicSoundTick = 0L;
        setActiveState(false);
        setChanged();
    }

    public int productionRequiredTicks() {
        return selectedQuality.requiredTicks();
    }

    public int productionProgressTicks() {
        if (level == null || !isPressing() || activeStartTick <= 0L) {
            return 0;
        }
        long elapsed = Math.max(0L, level.getGameTime() - activeStartTick);
        return (int) Math.min(productionRequiredTicks(), elapsed);
    }

    public boolean isPressing() {
        if (level == null) {
            return activeUntilTick > 0L;
        }
        return activeUntilTick > level.getGameTime();
    }

    private boolean hasRequiredMaterials() {
        return inventory.getStackInSlot(SLOT_GUN_PARTS).getCount() >= requiredGunParts()
                && inventory.getStackInSlot(SLOT_ALLOY).getCount() >= requiredAlloy()
                && inventory.getStackInSlot(SLOT_POLYMER).getCount() >= requiredPolymer();
    }

    private void consumeRequiredMaterials() {
        consume(SLOT_GUN_PARTS, requiredGunParts());
        consume(SLOT_ALLOY, requiredAlloy());
        consume(SLOT_POLYMER, requiredPolymer());
    }

    private int requiredGunParts() {
        return selectedPart.partsCost() * selectedQuality.materialMultiplier();
    }

    private int requiredAlloy() {
        return selectedPart.alloyCost() * selectedQuality.materialMultiplier();
    }

    private int requiredPolymer() {
        return selectedPart.polymerCost() * selectedQuality.materialMultiplier();
    }

    private void consume(int slot, int amount) {
        if (amount <= 0) {
            return;
        }
        ItemStack stack = inventory.getStackInSlot(slot);
        stack.shrink(amount);
        inventory.setStackInSlot(slot, stack.isEmpty() ? ItemStack.EMPTY : stack);
    }

    private void setActiveState(boolean active) {
        if (level == null) {
            return;
        }
        BlockState state = getBlockState();
        if (state.hasProperty(GunsmithPressBlock.ACTIVE)
                && state.getValue(GunsmithPressBlock.ACTIVE) != active) {
            level.setBlock(worldPosition, state.setValue(GunsmithPressBlock.ACTIVE, active), Block.UPDATE_ALL);
        }
    }

    private void playHydraulicSound() {
        if (level == null) {
            return;
        }
        float pitch = 0.92F + level.random.nextFloat() * 0.12F;
        level.playSound(null, worldPosition, ModMunitionsSounds.GUNSMITH_PRESS_HYDRAULIC.get(),
                SoundSource.BLOCKS, 0.56F, pitch);
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.miningdim.gunsmith_press");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int windowId, Inventory inv, Player player) {
        return new GunsmithPressMenu(windowId, inv, worldPosition);
    }

    private static final String K_INV = "Inv";
    private static final String K_PLATFORM = "SelectedPlatform";
    private static final String K_PART = "SelectedPart";
    private static final String K_QUALITY = "SelectedQuality";
    private static final String K_ACTIVE_START = "ActiveStartTick";
    private static final String K_ACTIVE_UNTIL = "ActiveUntilTick";

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.put(K_INV, inventory.serializeNBT());
        tag.putString(K_PLATFORM, selectedPlatform.id());
        tag.putString(K_PART, selectedPart.id());
        tag.putString(K_QUALITY, selectedQuality.id());
        tag.putLong(K_ACTIVE_START, activeStartTick);
        tag.putLong(K_ACTIVE_UNTIL, activeUntilTick);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains(K_INV)) {
            inventory.deserializeNBT(tag.getCompound(K_INV));
        }
        selectedPlatform = tag.contains(K_PLATFORM)
                ? GunsmithPlatform.byId(tag.getString(K_PLATFORM)) : GunsmithPlatform.AR;
        selectedPart = tag.contains(K_PART)
                ? GunsmithPressPart.byId(tag.getString(K_PART)) : GunsmithPressPart.CORE;
        selectedQuality = tag.contains(K_QUALITY)
                ? GunsmithPartQuality.byId(tag.getString(K_QUALITY)) : GunsmithPartQuality.COMMON;
        normalizeSelectedPart();
        activeStartTick = tag.getLong(K_ACTIVE_START);
        activeUntilTick = tag.getLong(K_ACTIVE_UNTIL);
    }

    private void normalizeSelectedPart() {
        if (selectedPlatform.supports(selectedPart)) {
            return;
        }
        for (GunsmithPressPart part : selectedPlatform.supportedParts()) {
            selectedPart = part;
            return;
        }
        throw new IllegalStateException("Gunsmith platform has no supported parts: " + selectedPlatform.id());
    }
}
