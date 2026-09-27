package com.miningdim.job.munitions.menu;

import com.miningdim.job.munitions.ModMunitionsBlocks;
import com.miningdim.job.munitions.ModMunitionsMenus;
import com.miningdim.job.munitions.MunitionsLevels;
import com.miningdim.job.munitions.block.GunsmithAssemblyBenchBlockEntity;
import com.miningdim.job.munitions.gunsmith.GunsmithAssemblyRecipe;
import com.miningdim.job.munitions.gunsmith.GunsmithBlueprint;
import com.miningdim.job.munitions.gunsmith.GunsmithGunDurability;
import com.miningdim.job.munitions.gunsmith.GunsmithPressPart;
import com.miningdim.menu.AbstractMiningMenu;
import com.miningdim.menu.MenuValidity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.SlotItemHandler;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.IntSupplier;

public final class GunsmithAssemblyMenu extends AbstractMiningMenu {

    public static final int BUTTON_START_ASSEMBLY = 0;

    /*
     * 槽位坐标 (GUI 像素, 以界面左上角为原点, 360x240), 与军火台 / 冲压机新界面同一套外框: 三种界面风格共用这些位置,
     * GunsmithAssemblyScreen 按它们画槽框、引线, 并在类加载时自检"可点控件不压槽位判定盒"。
     * 12 种部件共用 8 个位置 (上排 4 个、下排 4 个, 围着中间的枪械剪影): 同一张图纸不会同时要求的部件才叠在
     * 一个位置上 (导气/套筒、枪机/机匣、击锤/撞针、脚架/扳机), GunsmithAssemblyGameTests 逐平台核对不冲突。
     */
    public static final int SLOT_BLUEPRINT_X = 15;
    public static final int SLOT_BLUEPRINT_Y = 45;
    public static final int SLOT_OUTPUT_X = 303;
    public static final int SLOT_OUTPUT_Y = 162;
    public static final int PART_ROW_TOP_Y = 35;
    public static final int PART_ROW_BOTTOM_Y = 121;
    private static final int PART_COL_1 = 104;
    private static final int PART_COL_2 = 144;
    private static final int PART_COL_3 = 184;
    private static final int PART_COL_4 = 224;
    /** 玩家背包左上角 (3x9 主背包 + 快捷栏, 布局见 AbstractMiningMenu.addPlayerInventory)。 */
    public static final int PLAYER_INV_X = 100;
    public static final int PLAYER_INV_Y = 148;

    private final GunsmithAssemblyBenchBlockEntity blockEntity;
    private final DataSlot ownerLevelSlot;
    private final DataSlot remainingTicksSlot;

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
        // 两格服务端只读数据, broadcastChanges 每 tick 推送: 本菜单玩家的军火商实时等级 (客户端 ClientJobState
        // 只在登录与 /job set 时同步, 游戏中升级后界面的等级门会一直是旧值) 与本次组装剩余 tick
        // (客户端方块实体只知道"在干活", 不知道何时结束)。客户端与服务端按同一顺序登记。
        boolean client = inv.player.level().isClientSide;
        this.ownerLevelSlot = client ? DataSlot.standalone() : serverSlot(() -> MunitionsLevels.munitionsLevel(inv.player));
        this.remainingTicksSlot = client ? DataSlot.standalone() : serverSlot(blockEntity::animationRemainingTicks);
        addDataSlot(this.ownerLevelSlot);
        addDataSlot(this.remainingTicksSlot);
        addPlayerInventory(inv, PLAYER_INV_X, PLAYER_INV_Y);
    }

    private static DataSlot serverSlot(IntSupplier source) {
        return new DataSlot() {
            @Override
            public int get() {
                return source.getAsInt();
            }

            @Override
            public void set(int value) {
                // 服务端权威, 不接受写入。
            }
        };
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

    public ItemStack input() {
        return blueprint();
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
                || !blockEntity.inventory().getStackInSlot(GunsmithAssemblyBenchBlockEntity.SLOT_OUTPUT).isEmpty()) {
            return false;
        }
        GunsmithGunDurability.Managed repairTarget = repairTarget();
        if (repairTarget != null) {
            GunsmithGunDurability.RepairPreview preview =
                    GunsmithGunDurability.repairPreview(repairTarget);
            if (!preview.available()) {
                return false;
            }
            return GunsmithGunDurability.isRepairReplacement(repairTarget.stats(),
                    blockEntity.inventory().getStackInSlot(
                            GunsmithAssemblyBenchBlockEntity.slotForPart(preview.requiredPart())));
        }
        if (!GunsmithAssemblyRecipe.isBlueprint(blueprint())) {
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

    public boolean isRepairMode() {
        return repairTarget() != null;
    }

    /**
     * 图纸槽里那把待维修枪的单次解析结果, 非维修态返回 null。菜单谓词与客户端渲染每帧都会问几次,
     * 必须走不抛的入口, 且不要为同一把枪反复重解析整套部件 (审查 2/40)。
     */
    @Nullable
    public GunsmithGunDurability.Managed repairTarget() {
        return GunsmithGunDurability.tryManaged(input());
    }

    public boolean isPartSlotVisible(GunsmithPressPart part) {
        return blockEntity.isPartSlotVisible(part);
    }

    public boolean isAnimating() {
        return blockEntity.isAnimating();
    }

    /**
     * 本菜单玩家的军火商等级 (服务端实时值, 经菜单数据槽同步)。客户端在首次整包同步之前读到 0,
     * 调用方应退回本地镜像。
     */
    public int ownerMunitionsLevel() {
        return ownerLevelSlot.get();
    }

    /** 本次组装 / 维修还剩多少 tick (服务端经数据槽同步; 0 = 没在干活或尚未同步)。 */
    public int animationRemainingTicks() {
        return Math.max(0, remainingTicksSlot.get());
    }

    /** 部件槽的界面 x: 上下两排各 4 列, 列 1 枪管/护木, 列 2 导气/套筒/脚架/扳机, 列 3 枪机/机匣/握把, 列 4 击锤/撞针/枪托。 */
    public static int partSlotX(GunsmithPressPart part) {
        return switch (part) {
            case BARREL, HANDGUARD -> PART_COL_1;
            case CORE, SLIDE, BIPOD, TRIGGER -> PART_COL_2;
            case BOLT, RECEIVER, GRIP -> PART_COL_3;
            case HAMMER, FIRING_PIN, STOCK -> PART_COL_4;
        };
    }

    /** 部件槽的界面 y: 枪身上半截的部件在上排, 护木、握把、枪托这类下半截的在下排。 */
    public static int partSlotY(GunsmithPressPart part) {
        return switch (part) {
            case BARREL, CORE, SLIDE, BOLT, RECEIVER, HAMMER, FIRING_PIN -> PART_ROW_TOP_Y;
            case HANDGUARD, BIPOD, TRIGGER, GRIP, STOCK -> PART_ROW_BOTTOM_Y;
        };
    }

    /** 容器槽 (按方块实体槽位下标) 的界面 x。 */
    public static int containerSlotX(int slot) {
        if (slot == GunsmithAssemblyBenchBlockEntity.SLOT_BLUEPRINT) {
            return SLOT_BLUEPRINT_X;
        }
        if (slot == GunsmithAssemblyBenchBlockEntity.SLOT_OUTPUT) {
            return SLOT_OUTPUT_X;
        }
        return partSlotX(partForSlot(slot));
    }

    /** 容器槽 (按方块实体槽位下标) 的界面 y。 */
    public static int containerSlotY(int slot) {
        if (slot == GunsmithAssemblyBenchBlockEntity.SLOT_BLUEPRINT) {
            return SLOT_BLUEPRINT_Y;
        }
        if (slot == GunsmithAssemblyBenchBlockEntity.SLOT_OUTPUT) {
            return SLOT_OUTPUT_Y;
        }
        return partSlotY(partForSlot(slot));
    }

    private static GunsmithPressPart partForSlot(int slot) {
        int index = slot - GunsmithAssemblyBenchBlockEntity.SLOT_PART_BASE;
        GunsmithPressPart[] parts = GunsmithPressPart.values();
        if (index < 0 || index >= parts.length) {
            throw new IllegalArgumentException("slot is not a gunsmith assembly container slot: " + slot);
        }
        return parts[index];
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
