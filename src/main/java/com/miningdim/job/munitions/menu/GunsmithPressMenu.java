package com.miningdim.job.munitions.menu;

import com.miningdim.job.munitions.ModMunitionsBlocks;
import com.miningdim.job.munitions.ModMunitionsMenus;
import com.miningdim.job.munitions.block.GunsmithPressBlock;
import com.miningdim.job.munitions.block.GunsmithPressBlockEntity;
import com.miningdim.job.munitions.gunsmith.GunsmithPartQuality;
import com.miningdim.job.munitions.gunsmith.GunsmithPartVariant;
import com.miningdim.job.munitions.gunsmith.GunsmithPlatform;
import com.miningdim.job.munitions.gunsmith.GunsmithPressPart;
import com.miningdim.menu.AbstractMiningMenu;
import com.miningdim.menu.MenuValidity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.items.SlotItemHandler;

public final class GunsmithPressMenu extends AbstractMiningMenu {

    private static final int CONTAINER_SLOTS = GunsmithPressBlockEntity.SLOT_COUNT;

    public static final int BUTTON_PART_BASE = 0;
    public static final int BUTTON_QUALITY_BASE = 100;
    public static final int BUTTON_START_PREVIEW = 200;
    public static final int BUTTON_PLATFORM_BASE = 300;
    public static final int BUTTON_VARIANT_BASE = 400;

    private static final int SLOT_GUN_PARTS_X = 294;
    private static final int SLOT_ALLOY_X = 320;
    private static final int SLOT_POLYMER_X = 294;
    private static final int SLOT_OUTPUT_X = 320;
    private static final int SLOT_TOP_Y = 84;
    private static final int SLOT_BOTTOM_Y = 110;
    private static final int SLOT_OUTPUT_Y = 110;

    private final GunsmithPressBlockEntity blockEntity;
    private final ContainerData data;

    public GunsmithPressMenu(int windowId, Inventory inv, BlockPos pos) {
        super(ModMunitionsMenus.GUNSMITH_PRESS.get(), windowId, CONTAINER_SLOTS,
                MenuValidity.ofBlock(ContainerLevelAccess.create(inv.player.level(), pos), blockAt(inv, pos)));
        this.blockEntity = inv.player.level().getBlockEntity(pos) instanceof GunsmithPressBlockEntity be
                ? be : null;

        if (blockEntity != null) {
            addSlot(new SlotItemHandler(blockEntity.inventory(),
                    GunsmithPressBlockEntity.SLOT_GUN_PARTS, SLOT_GUN_PARTS_X, SLOT_TOP_Y));
            addSlot(new SlotItemHandler(blockEntity.inventory(),
                    GunsmithPressBlockEntity.SLOT_ALLOY, SLOT_ALLOY_X, SLOT_TOP_Y));
            addSlot(new SlotItemHandler(blockEntity.inventory(),
                    GunsmithPressBlockEntity.SLOT_POLYMER, SLOT_POLYMER_X, SLOT_BOTTOM_Y));
            addSlot(new OutputSlot(blockEntity, GunsmithPressBlockEntity.SLOT_OUTPUT, SLOT_OUTPUT_X, SLOT_OUTPUT_Y));
            this.data = inv.player.level().isClientSide
                    ? new SimpleContainerData(GunsmithPressBlockEntity.DATA_COUNT)
                    : blockEntity.dataAccess();
        } else {
            for (int i = 0; i < CONTAINER_SLOTS; i++) {
                addSlot(new EmptyPlaceholderSlot(i));
            }
            this.data = new SimpleContainerData(GunsmithPressBlockEntity.DATA_COUNT);
        }
        addDataSlots(this.data);
        addPlayerInventory(inv, 100, 148);
    }

    private static Block blockAt(Inventory inv, BlockPos pos) {
        Block block = inv.player.level().getBlockState(pos).getBlock();
        return block instanceof GunsmithPressBlock ? block : ModMunitionsBlocks.GUNSMITH_PRESS.get();
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (!(player instanceof ServerPlayer serverPlayer) || blockEntity == null) {
            return false;
        }
        int platformIndex = id - BUTTON_PLATFORM_BASE;
        if (platformIndex >= 0 && platformIndex < GunsmithPlatform.values().length) {
            return blockEntity.trySelectPlatform(platformIndex);
        }
        if (id >= BUTTON_PART_BASE && id < BUTTON_QUALITY_BASE) {
            return blockEntity.trySelectPart(id - BUTTON_PART_BASE);
        }
        int qualityIndex = id - BUTTON_QUALITY_BASE;
        if (qualityIndex >= 0 && qualityIndex < GunsmithPartQuality.values().length) {
            return blockEntity.trySelectQuality(qualityIndex, serverPlayer);
        }
        int variantIndex = id - BUTTON_VARIANT_BASE;
        if (variantIndex >= 0 && variantIndex < GunsmithPartVariant.values().length) {
            return blockEntity.trySelectVariant(variantIndex);
        }
        if (id == BUTTON_START_PREVIEW) {
            return blockEntity.tryStartPreview(serverPlayer);
        }
        return false;
    }

    public int selectedPartIndex() {
        return data.get(GunsmithPressBlockEntity.DATA_SELECTED_PART);
    }

    public int selectedPlatformIndex() {
        return data.get(GunsmithPressBlockEntity.DATA_SELECTED_PLATFORM);
    }

    public int selectedQualityIndex() {
        return data.get(GunsmithPressBlockEntity.DATA_SELECTED_QUALITY);
    }

    public int selectedVariantIndex() {
        return data.get(GunsmithPressBlockEntity.DATA_SELECTED_VARIANT);
    }

    public GunsmithPlatform selectedPlatform() {
        return GunsmithPlatform.byIndex(selectedPlatformIndex());
    }

    public GunsmithPressPart selectedPart() {
        return GunsmithPressPart.byIndex(selectedPartIndex());
    }

    public GunsmithPartQuality selectedQuality() {
        return GunsmithPartQuality.byIndex(selectedQualityIndex());
    }

    public GunsmithPartVariant selectedVariant() {
        return GunsmithPartVariant.byIndex(selectedVariantIndex());
    }

    public int requiredGunParts() {
        return selectedPart().partsCost() * selectedQuality().materialMultiplier();
    }

    public int requiredAlloy() {
        return selectedPart().alloyCost() * selectedQuality().materialMultiplier();
    }

    public int requiredPolymer() {
        return selectedPart().polymerCost() * selectedQuality().materialMultiplier();
    }

    public int inputCount(int slot) {
        if (slot < GunsmithPressBlockEntity.SLOT_GUN_PARTS
                || slot > GunsmithPressBlockEntity.SLOT_POLYMER) {
            throw new IllegalArgumentException("slot is not a gunsmith press input slot: " + slot);
        }
        return getSlot(slot).getItem().getCount();
    }

    public boolean canStart() {
        return !isPressing()
                && !getSlot(GunsmithPressBlockEntity.SLOT_OUTPUT).hasItem()
                && inputCount(GunsmithPressBlockEntity.SLOT_GUN_PARTS) >= requiredGunParts()
                && inputCount(GunsmithPressBlockEntity.SLOT_ALLOY) >= requiredAlloy()
                && inputCount(GunsmithPressBlockEntity.SLOT_POLYMER) >= requiredPolymer();
    }

    public int productionProgressTicks() {
        return data.get(GunsmithPressBlockEntity.DATA_PROGRESS_TICKS);
    }

    public int productionRequiredTicks() {
        return data.get(GunsmithPressBlockEntity.DATA_REQUIRED_TICKS);
    }

    public boolean isPressing() {
        return data.get(GunsmithPressBlockEntity.DATA_ACTIVE) != 0;
    }

    private static final class OutputSlot extends SlotItemHandler {
        OutputSlot(GunsmithPressBlockEntity be, int index, int x, int y) {
            super(be.inventory(), index, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }
    }

    private static final class EmptyPlaceholderSlot extends net.minecraft.world.inventory.Slot {
        private static final SimpleContainer DUMMY = new SimpleContainer(GunsmithPressBlockEntity.SLOT_COUNT);

        EmptyPlaceholderSlot(int index) {
            super(DUMMY, index, 0, 0);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }

        @Override
        public boolean mayPickup(Player player) {
            return false;
        }
    }
}
