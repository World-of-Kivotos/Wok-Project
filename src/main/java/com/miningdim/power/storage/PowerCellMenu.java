package com.miningdim.power.storage;

import com.miningdim.menu.AbstractMiningMenu;
import com.miningdim.menu.MenuValidity;
import com.miningdim.power.PowerRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

/** 储电菜单：没有槽位，只同步余额与上一 tick 的进出功率。 */
public final class PowerCellMenu extends AbstractMiningMenu {

    public static final int CONTAINER_SLOTS = 0;
    public static final int DATA_STORED_LOW = 0;
    public static final int DATA_STORED_HIGH = 1;
    public static final int DATA_CAPACITY_LOW = 2;
    public static final int DATA_CAPACITY_HIGH = 3;
    public static final int DATA_RECEIVED_LOW = 4;
    public static final int DATA_RECEIVED_HIGH = 5;
    public static final int DATA_EXTRACTED_LOW = 6;
    public static final int DATA_EXTRACTED_HIGH = 7;
    /** 本档传输速率上限, 供界面把进出两条表按同一个满格基准画, 否则读数之间没有可比性。 */
    public static final int DATA_TRANSFER_LOW = 8;
    public static final int DATA_TRANSFER_HIGH = 9;
    public static final int DATA_COUNT = 10;

    private final ContainerData data;

    public PowerCellMenu(int windowId, Inventory playerInv, BlockPos pos) {
        super(PowerRegistry.POWER_CELL_MENU.get(), windowId, CONTAINER_SLOTS,
                MenuValidity.ofBlock(ContainerLevelAccess.create(playerInv.player.level(), pos),
                        powerCellBlockAt(playerInv, pos)));
        addPlayerInventory(playerInv, 28, 142);
        this.data = dataFor(findCell(playerInv, pos), playerInv.player.level().isClientSide);
        addDataSlots(data);
    }

    private static Block powerCellBlockAt(Inventory playerInv, BlockPos pos) {
        Block block = playerInv.player.level().getBlockState(pos).getBlock();
        if (block instanceof PowerCellBlock) {
            return block;
        }
        throw new IllegalArgumentException("power cell menu received a non-cell position: " + pos);
    }

    @Nullable
    private static PowerCellBlockEntity findCell(Inventory playerInv, BlockPos pos) {
        if (playerInv.player.level().getBlockEntity(pos) instanceof PowerCellBlockEntity cell) {
            return cell;
        }
        return null;
    }

    private static ContainerData dataFor(@Nullable PowerCellBlockEntity cell, boolean clientSide) {
        if (cell == null || clientSide) {
            return new SimpleContainerData(DATA_COUNT);
        }
        return new ContainerData() {
            @Override
            public int get(int index) {
                return switch (index) {
                    case DATA_STORED_LOW -> lowWord(cell.storedFe());
                    case DATA_STORED_HIGH -> highWord(cell.storedFe());
                    case DATA_CAPACITY_LOW -> lowWord(cell.capacityFe());
                    case DATA_CAPACITY_HIGH -> highWord(cell.capacityFe());
                    case DATA_RECEIVED_LOW -> lowWord(cell.lastReceivedFe());
                    case DATA_RECEIVED_HIGH -> highWord(cell.lastReceivedFe());
                    case DATA_EXTRACTED_LOW -> lowWord(cell.lastExtractedFe());
                    case DATA_EXTRACTED_HIGH -> highWord(cell.lastExtractedFe());
                    case DATA_TRANSFER_LOW -> lowWord(cell.runtime().transferFePerTick());
                    case DATA_TRANSFER_HIGH -> highWord(cell.runtime().transferFePerTick());
                    default -> throw new IllegalArgumentException("invalid power cell data index: " + index);
                };
            }

            @Override
            public void set(int index, int value) {
                if (index < 0 || index >= DATA_COUNT) {
                    throw new IllegalArgumentException("invalid power cell data index: " + index);
                }
                // 服务端到客户端单向同步, 客户端写入一律忽略。
            }

            @Override
            public int getCount() {
                return DATA_COUNT;
            }
        };
    }

    public static int lowWord(int value) {
        return value & 0xFFFF;
    }

    public static int highWord(int value) {
        return (value >>> 16) & 0xFFFF;
    }

    public static int merge(int low, int high) {
        return (high << 16) | (low & 0xFFFF);
    }

    public int storedFe() {
        return merge(data.get(DATA_STORED_LOW), data.get(DATA_STORED_HIGH));
    }

    public int capacityFe() {
        return merge(data.get(DATA_CAPACITY_LOW), data.get(DATA_CAPACITY_HIGH));
    }

    public int lastReceivedFe() {
        return merge(data.get(DATA_RECEIVED_LOW), data.get(DATA_RECEIVED_HIGH));
    }

    public int lastExtractedFe() {
        return merge(data.get(DATA_EXTRACTED_LOW), data.get(DATA_EXTRACTED_HIGH));
    }

    public int transferFePerTick() {
        return merge(data.get(DATA_TRANSFER_LOW), data.get(DATA_TRANSFER_HIGH));
    }
}
