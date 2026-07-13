package com.miningdim.job.munitions.menu;

import com.miningdim.job.munitions.ModMunitionsBlocks;
import com.miningdim.job.munitions.ModMunitionsMenus;
import com.miningdim.job.munitions.block.GunsmithAssemblyBenchBlockEntity;
import com.miningdim.job.munitions.gunsmith.GunsmithAssemblyRecipe;
import com.miningdim.job.munitions.gunsmith.GunsmithBlueprint;
import com.miningdim.job.munitions.gunsmith.GunsmithPressPart;
import com.miningdim.menu.AbstractMiningMenu;
import com.miningdim.menu.MenuValidity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.SlotItemHandler;

import java.util.EnumMap;
import java.util.Map;

public final class GunsmithAssemblyMenu extends AbstractMiningMenu {

    public static final int BUTTON_START_ASSEMBLY = 0;

    private static final int SLOT_BLUEPRINT_X = 24;
    private static final int SLOT_BLUEPRINT_Y = 94;
    private static final int SLOT_OUTPUT_X = 366;
    private static final int SLOT_OUTPUT_Y = 177;

    private final GunsmithAssemblyBenchBlockEntity blockEntity;

    public GunsmithAssemblyMenu(int windowId, Inventory inv, BlockPos pos) {
        super(ModMunitionsMenus.GUNSMITH_ASSEMBLY_BENCH.get(), windowId,
                GunsmithAssemblyBenchBlockEntity.SLOT_COUNT,
                MenuValidity.ofBlock(ContainerLevelAccess.create(inv.player.level(), pos),
                        ModMunitionsBlocks.GUNSMITH_ASSEMBLY_BENCH.get()));
        if (!(inv.player.level().getBlockEntity(pos) instanceof GunsmithAssemblyBenchBlockEntity found)) {
            throw new IllegalStateException("Missing gunsmith assembly bench block entity at " + pos);
        }
        this.blockEntity = found;

        addSlot(new SlotItemHandler(blockEntity.inventory(), GunsmithAssemblyBenchBlockEntity.SLOT_BLUEPRINT,
                SLOT_BLUEPRINT_X, SLOT_BLUEPRINT_Y));
        for (GunsmithPressPart part : GunsmithPressPart.values()) {
            addSlot(new PartSlot(blockEntity, part, partSlotX(part), partSlotY(part)));
        }
        addSlot(new OutputSlot(blockEntity, GunsmithAssemblyBenchBlockEntity.SLOT_OUTPUT,
                SLOT_OUTPUT_X, SLOT_OUTPUT_Y));
        addPlayerInventory(inv, 126, 160);
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        return id == BUTTON_START_ASSEMBLY
                && player instanceof ServerPlayer serverPlayer
                && blockEntity.tryStartAssembly(serverPlayer);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stackInSlot = slot.getItem();
        ItemStack moved = stackInSlot.copy();
        int playerStart = GunsmithAssemblyBenchBlockEntity.SLOT_COUNT;

        if (index < playerStart) {
            if (!this.moveItemStackTo(stackInSlot, playerStart, this.slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else if (!this.moveItemStackTo(stackInSlot, 0, GunsmithAssemblyBenchBlockEntity.SLOT_OUTPUT, false)) {
            return ItemStack.EMPTY;
        }

        if (stackInSlot.isEmpty()) {
            slot.set(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        if (stackInSlot.getCount() == moved.getCount()) {
            return ItemStack.EMPTY;
        }
        slot.onTake(player, stackInSlot);
        return moved;
    }

    public ItemStack blueprint() {
        return blockEntity.inventory().getStackInSlot(GunsmithAssemblyBenchBlockEntity.SLOT_BLUEPRINT);
    }

    public Map<GunsmithPressPart, ItemStack> partStacks() {
        Map<GunsmithPressPart, ItemStack> parts = new EnumMap<>(GunsmithPressPart.class);
        for (GunsmithPressPart part : GunsmithPressPart.values()) {
            parts.put(part, blockEntity.inventory().getStackInSlot(
                    GunsmithAssemblyBenchBlockEntity.slotForPart(part)));
        }
        return parts;
    }

    public boolean canAssemble() {
        if (blockEntity.isAnimating()
                || !GunsmithAssemblyRecipe.isBlueprint(blueprint())
                || !blockEntity.inventory().getStackInSlot(GunsmithAssemblyBenchBlockEntity.SLOT_OUTPUT).isEmpty()) {
            return false;
        }
        GunsmithBlueprint blueprint = GunsmithAssemblyRecipe.blueprint(blueprint());
        for (GunsmithPressPart part : blueprint.requiredParts()) {
            if (!GunsmithAssemblyRecipe.matchesPart(
                    blockEntity.inventory().getStackInSlot(GunsmithAssemblyBenchBlockEntity.slotForPart(part)), part,
                    blueprint.platform())) {
                return false;
            }
        }
        return true;
    }

    public boolean isPartSlotVisible(GunsmithPressPart part) {
        return blockEntity.isPartSlotVisible(part);
    }

    public boolean isAnimating() {
        return blockEntity.isAnimating();
    }

    public static int partSlotX(GunsmithPressPart part) {
        return switch (part) {
            case CORE -> 130;
            case BARREL -> 72;
            case BOLT -> 240;
            case HANDGUARD -> 52;
            case GRIP -> 278;
            case STOCK -> 278;
            case SLIDE -> 168;
            case TRIGGER -> 166;
            case HAMMER -> 248;
            case RECEIVER -> 242;
        };
    }

    public static int partSlotY(GunsmithPressPart part) {
        return switch (part) {
            case CORE, BOLT, BARREL -> 50;
            case STOCK -> 94;
            case HANDGUARD, GRIP -> 130;
            case SLIDE -> 46;
            case TRIGGER -> 130;
            case HAMMER -> 50;
            case RECEIVER -> 94;
        };
    }

    private static final class PartSlot extends SlotItemHandler {

        private final GunsmithAssemblyBenchBlockEntity blockEntity;
        private final GunsmithPressPart part;

        PartSlot(GunsmithAssemblyBenchBlockEntity blockEntity, GunsmithPressPart part, int x, int y) {
            super(blockEntity.inventory(), GunsmithAssemblyBenchBlockEntity.slotForPart(part), x, y);
            this.blockEntity = blockEntity;
            this.part = part;
        }

        @Override
        public boolean isActive() {
            return blockEntity.isPartSlotVisible(part);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return isActive() && super.mayPlace(stack);
        }
    }

    private static final class OutputSlot extends SlotItemHandler {

        OutputSlot(GunsmithAssemblyBenchBlockEntity blockEntity, int index, int x, int y) {
            super(blockEntity.inventory(), index, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }
    }
}
