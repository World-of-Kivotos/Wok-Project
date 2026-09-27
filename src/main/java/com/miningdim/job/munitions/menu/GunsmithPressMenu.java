package com.miningdim.job.munitions.menu;

import com.miningdim.job.munitions.ModMunitionsBlocks;
import com.miningdim.job.munitions.ModMunitionsMenus;
import com.miningdim.job.munitions.MunitionsLevels;
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
import net.minecraft.world.inventory.DataSlot;
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

    /*
     * 槽位坐标 (GUI 像素, 以界面左上角为原点, 360x240)。三种界面风格共用同一套位置 (风格只换画法, 不动格子),
     * GunsmithPressScreen 按这些常量画槽框并做"可点控件不压背包/容器槽判定盒"的类加载自检, 所以改这里要同步看那条自检。
     * 右侧"原料"面板里三格竖排, 成品槽在中间液压机工位的正下方。
     */
    public static final int SLOT_GUN_PARTS_X = 276;
    public static final int SLOT_GUN_PARTS_Y = 44;
    public static final int SLOT_ALLOY_X = 276;
    public static final int SLOT_ALLOY_Y = 68;
    public static final int SLOT_POLYMER_X = 276;
    public static final int SLOT_POLYMER_Y = 92;
    public static final int SLOT_OUTPUT_X = 136;
    public static final int SLOT_OUTPUT_Y = 95;
    /** 玩家背包左上角 (3x9 主背包 + 快捷栏, 布局见 AbstractMiningMenu.addPlayerInventory)。 */
    public static final int PLAYER_INV_X = 100;
    public static final int PLAYER_INV_Y = 148;

    private final GunsmithPressBlockEntity blockEntity;
    private final ContainerData data;
    private final DataSlot ownerLevelSlot;

    public GunsmithPressMenu(int windowId, Inventory inv, BlockPos pos) {
        super(ModMunitionsMenus.GUNSMITH_PRESS.get(), windowId, CONTAINER_SLOTS,
                MenuValidity.ofBlock(ContainerLevelAccess.create(inv.player.level(), pos), blockAt(inv, pos)));
        this.blockEntity = inv.player.level().getBlockEntity(pos) instanceof GunsmithPressBlockEntity be
                ? be : null;

        if (blockEntity != null) {
            addSlot(new SlotItemHandler(blockEntity.inventory(),
                    GunsmithPressBlockEntity.SLOT_GUN_PARTS, SLOT_GUN_PARTS_X, SLOT_GUN_PARTS_Y));
            addSlot(new SlotItemHandler(blockEntity.inventory(),
                    GunsmithPressBlockEntity.SLOT_ALLOY, SLOT_ALLOY_X, SLOT_ALLOY_Y));
            addSlot(new SlotItemHandler(blockEntity.inventory(),
                    GunsmithPressBlockEntity.SLOT_POLYMER, SLOT_POLYMER_X, SLOT_POLYMER_Y));
            addSlot(new OutputSlot(blockEntity, GunsmithPressBlockEntity.SLOT_OUTPUT, SLOT_OUTPUT_X, SLOT_OUTPUT_Y));
            this.data = inv.player.level().isClientSide
                    ? new SimpleContainerData(GunsmithPressBlockEntity.DATA_COUNT)
                    : blockEntity.dataAccess();
        } else {
            // 占位槽放在真槽的位置上 (原先堆在 (0,0), 新界面那里是标题栏, 悬停高亮会压在标题上)。
            for (int i = 0; i < CONTAINER_SLOTS; i++) {
                addSlot(new EmptyPlaceholderSlot(i, containerSlotX(i), containerSlotY(i)));
            }
            this.data = new SimpleContainerData(GunsmithPressBlockEntity.DATA_COUNT);
        }
        addDataSlots(this.data);
        // 本菜单玩家自己的军火商等级 (每位观看者各一份, 不是方块实体的共享数据)。品质 / 稀有度门和开工门由服务端按
        // 点击者的实时等级判定, 而客户端 ClientJobState 只在登录和 /job set 时同步, 游戏中升级后一直是旧值;
        // 界面改读这一格, broadcastChanges 每 tick 推送。两个分支之后统一登记, 客户端与服务端槽位顺序一致。
        this.ownerLevelSlot = inv.player.level().isClientSide
                ? DataSlot.standalone()
                : new DataSlot() {
                    @Override
                    public int get() {
                        return MunitionsLevels.munitionsLevel(inv.player);
                    }

                    @Override
                    public void set(int value) {
                        // 服务端权威, 不接受写入。
                    }
                };
        addDataSlot(this.ownerLevelSlot);
        addPlayerInventory(inv, PLAYER_INV_X, PLAYER_INV_Y);
    }

    /**
     * 本菜单玩家的军火商等级 (服务端实时值, 经菜单数据槽同步)。客户端在首次整包同步之前读到 0,
     * 调用方应退回本地镜像。
     */
    public int ownerMunitionsLevel() {
        return ownerLevelSlot.get();
    }

    /** 容器槽 (按方块实体槽位下标) 的界面 x。 */
    public static int containerSlotX(int slot) {
        return switch (slot) {
            case GunsmithPressBlockEntity.SLOT_GUN_PARTS -> SLOT_GUN_PARTS_X;
            case GunsmithPressBlockEntity.SLOT_ALLOY -> SLOT_ALLOY_X;
            case GunsmithPressBlockEntity.SLOT_POLYMER -> SLOT_POLYMER_X;
            case GunsmithPressBlockEntity.SLOT_OUTPUT -> SLOT_OUTPUT_X;
            default -> throw new IllegalArgumentException("slot is not a gunsmith press container slot: " + slot);
        };
    }

    /** 容器槽 (按方块实体槽位下标) 的界面 y。 */
    public static int containerSlotY(int slot) {
        return switch (slot) {
            case GunsmithPressBlockEntity.SLOT_GUN_PARTS -> SLOT_GUN_PARTS_Y;
            case GunsmithPressBlockEntity.SLOT_ALLOY -> SLOT_ALLOY_Y;
            case GunsmithPressBlockEntity.SLOT_POLYMER -> SLOT_POLYMER_Y;
            case GunsmithPressBlockEntity.SLOT_OUTPUT -> SLOT_OUTPUT_Y;
            default -> throw new IllegalArgumentException("slot is not a gunsmith press container slot: " + slot);
        };
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
        if (id == BUTTON_START_PREVIEW) {
            return blockEntity.tryStartPreview(serverPlayer);
        }
        int variantIndex = id - BUTTON_VARIANT_BASE;
        if (variantIndex >= 0 && variantIndex < GunsmithPartVariant.values().length) {
            return blockEntity.trySelectVariant(variantIndex);
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

        EmptyPlaceholderSlot(int index, int x, int y) {
            super(DUMMY, index, x, y);
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
