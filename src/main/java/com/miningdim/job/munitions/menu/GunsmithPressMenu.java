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

    /*
     * 按钮 ID 必须落在 0..127 (V05): 原版 ServerboundContainerButtonClickPacket 按有符号 byte 编解码 buttonId,
     * 专用服上 >=128 的值会被截成负数或回绕进别的段 (旧的开工 200 -> -56, 平台 300+i -> 44+i 误入部件段);
     * 单机/局域网房主走内存连接不编解码, 测不出来。各段是互不重叠的精确区间 [BASE, BASE + CAPACITY),
     * 容量按现有条目数留足余量 (型号预留到 32 个), 96..127 空着留给以后的新按钮; 类加载期断言兜底。
     * 真要超出 128 个 ID 就改走自定义 C2S 包, 别再往这里塞。
     */
    public static final int BUTTON_ID_MAX = 127;
    public static final int BUTTON_PART_BASE = 0;
    public static final int BUTTON_PART_CAPACITY = 16;
    public static final int BUTTON_QUALITY_BASE = 16;
    public static final int BUTTON_QUALITY_CAPACITY = 8;
    public static final int BUTTON_START_PREVIEW = 24;
    public static final int BUTTON_PLATFORM_BASE = 32;
    public static final int BUTTON_PLATFORM_CAPACITY = 32;
    public static final int BUTTON_VARIANT_BASE = 64;
    public static final int BUTTON_VARIANT_CAPACITY = 32;

    static {
        assertButtonLayout();
    }

    private static final int SLOT_GUN_PARTS_X = 294;
    private static final int SLOT_ALLOY_X = 320;
    private static final int SLOT_POLYMER_X = 294;
    private static final int SLOT_OUTPUT_X = 178;
    private static final int SLOT_TOP_Y = 84;
    private static final int SLOT_BOTTOM_Y = 110;
    private static final int SLOT_OUTPUT_Y = 92;

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
        if (inRange(id, BUTTON_PART_BASE, BUTTON_PART_CAPACITY)) {
            return blockEntity.trySelectPart(id - BUTTON_PART_BASE);
        }
        if (inRange(id, BUTTON_QUALITY_BASE, BUTTON_QUALITY_CAPACITY)) {
            int qualityIndex = id - BUTTON_QUALITY_BASE;
            return qualityIndex < GunsmithPartQuality.values().length
                    && blockEntity.trySelectQuality(qualityIndex, serverPlayer);
        }
        if (id == BUTTON_START_PREVIEW) {
            return blockEntity.tryStartPress(serverPlayer);
        }
        if (inRange(id, BUTTON_PLATFORM_BASE, BUTTON_PLATFORM_CAPACITY)) {
            int platformIndex = id - BUTTON_PLATFORM_BASE;
            return platformIndex < GunsmithPlatform.values().length
                    && blockEntity.trySelectPlatform(platformIndex, serverPlayer);
        }
        if (inRange(id, BUTTON_VARIANT_BASE, BUTTON_VARIANT_CAPACITY)) {
            int variantIndex = id - BUTTON_VARIANT_BASE;
            return variantIndex < GunsmithPartVariant.values().length
                    && blockEntity.trySelectVariant(variantIndex);
        }
        return false;
    }

    private static boolean inRange(int id, int base, int capacity) {
        return id >= base && id < base + capacity;
    }

    /**
     * 按钮段布局自检 (V05): 每段都在 0..BUTTON_ID_MAX 内、两两不重叠、容量装得下现有条目; 任何一条不成立
     * 就在类加载时直接抛, 而不是等部署到专用服后按钮悄悄失灵。部件段按部件总数核, 它是单平台紧凑行数的上界。
     */
    public static void assertButtonLayout() {
        int[][] ranges = {
                {BUTTON_PART_BASE, BUTTON_PART_CAPACITY, GunsmithPressPart.values().length},
                {BUTTON_QUALITY_BASE, BUTTON_QUALITY_CAPACITY, GunsmithPartQuality.values().length},
                {BUTTON_START_PREVIEW, 1, 1},
                {BUTTON_PLATFORM_BASE, BUTTON_PLATFORM_CAPACITY, GunsmithPlatform.values().length},
                {BUTTON_VARIANT_BASE, BUTTON_VARIANT_CAPACITY, GunsmithPartVariant.values().length}};
        for (int i = 0; i < ranges.length; i++) {
            int base = ranges[i][0];
            int capacity = ranges[i][1];
            int used = ranges[i][2];
            if (base < 0 || capacity <= 0 || base + capacity - 1 > BUTTON_ID_MAX) {
                throw new IllegalStateException("gunsmith press button range [" + base + ", " + (base + capacity)
                        + ") leaves 0.." + BUTTON_ID_MAX);
            }
            if (used > capacity) {
                throw new IllegalStateException("gunsmith press button range at " + base + " holds " + capacity
                        + " ids but needs " + used);
            }
            for (int j = 0; j < i; j++) {
                int otherBase = ranges[j][0];
                int otherEnd = otherBase + ranges[j][1];
                if (base < otherEnd && otherBase < base + capacity) {
                    throw new IllegalStateException("gunsmith press button ranges at " + otherBase + " and "
                            + base + " overlap");
                }
            }
        }
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

    public GunsmithPlatform selectedPlatform() {
        return GunsmithPlatform.byIndex(selectedPlatformIndex());
    }

    public GunsmithPressPart selectedPart() {
        return GunsmithPressPart.byIndex(selectedPartIndex());
    }

    public GunsmithPartQuality selectedQuality() {
        return GunsmithPartQuality.byIndex(selectedQualityIndex());
    }

    public int selectedVariantIndex() {
        return data.get(GunsmithPressBlockEntity.DATA_SELECTED_VARIANT);
    }

    public GunsmithPartVariant selectedVariant() {
        return GunsmithPartVariant.byIndex(selectedVariantIndex());
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
