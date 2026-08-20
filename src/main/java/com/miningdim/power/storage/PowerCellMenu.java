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

/**
 * 储电菜单：没有槽位，只同步整组的余额、容量与上一 tick 的进出功率。点开组内任意一块看到的都是整组读数。
 *
 * 每个读数占 4 个数据槽。原版 ContainerData 的同步包按 16 位写值，单槽装不下大数；而聚合读数是 long
 * （64 块未来储电就是 566 亿 FE，连 int 都装不下），所以按 16 位切四段传输，客户端再拼回 long。
 */
public final class PowerCellMenu extends AbstractMiningMenu {

    public static final int CONTAINER_SLOTS = 0;
    /** 一个 long 读数占几个 16 位数据槽。 */
    public static final int WORDS_PER_VALUE = 4;
    public static final int VALUE_STORED = 0;
    public static final int VALUE_CAPACITY = 1;
    public static final int VALUE_RECEIVED = 2;
    public static final int VALUE_EXTRACTED = 3;
    /** 整组传输速率上限, 供界面把进出两条表按同一个满格基准画, 否则读数之间没有可比性。 */
    public static final int VALUE_TRANSFER = 4;
    public static final int VALUE_COUNT = 5;
    public static final int DATA_COUNT = VALUE_COUNT * WORDS_PER_VALUE;

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

    /** 包级可见供 GameTest 直接取服务端数据槽与客户端镜像, 与 GeneratorMenu.dataFor 同范式。 */
    static ContainerData dataFor(@Nullable PowerCellBlockEntity cell, boolean clientSide) {
        if (cell == null || clientSide) {
            return new SimpleContainerData(DATA_COUNT);
        }
        return new ContainerData() {
            @Override
            public int get(int index) {
                if (index < 0 || index >= DATA_COUNT) {
                    throw new IllegalArgumentException("invalid power cell data index: " + index);
                }
                // 玩家可以在界面开着时把这块挖掉: stillValid 要到下一 tick 才关窗, 这一 tick 的同步先报 0,
                // 否则会去查一个已经退组的坐标。
                if (cell.isRemoved()) {
                    return 0;
                }
                long value = switch (index / WORDS_PER_VALUE) {
                    case VALUE_STORED -> cell.groupStoredFe();
                    case VALUE_CAPACITY -> cell.groupCapacityFe();
                    case VALUE_RECEIVED -> cell.groupLastReceivedFe();
                    case VALUE_EXTRACTED -> cell.groupLastExtractedFe();
                    case VALUE_TRANSFER -> cell.groupTransferFePerTick();
                    default -> throw new IllegalArgumentException("invalid power cell value index: " + index);
                };
                return word(value, index % WORDS_PER_VALUE);
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

    /** 取 long 的第 wordIndex 个 16 位段 (0 为最低段)。 */
    public static int word(long value, int wordIndex) {
        return (int) ((value >>> (16 * wordIndex)) & 0xFFFFL);
    }

    /** 把四个 16 位段拼回 long；每段都要先掩掉符号扩展，网络层是按 short 传的。 */
    public static long mergeWords(int word0, int word1, int word2, int word3) {
        return (word0 & 0xFFFFL)
                | ((word1 & 0xFFFFL) << 16)
                | ((word2 & 0xFFFFL) << 32)
                | ((word3 & 0xFFFFL) << 48);
    }

    private long value(int valueIndex) {
        int base = valueIndex * WORDS_PER_VALUE;
        return mergeWords(data.get(base), data.get(base + 1), data.get(base + 2), data.get(base + 3));
    }

    public long storedFe() {
        return value(VALUE_STORED);
    }

    public long capacityFe() {
        return value(VALUE_CAPACITY);
    }

    public long lastReceivedFe() {
        return value(VALUE_RECEIVED);
    }

    public long lastExtractedFe() {
        return value(VALUE_EXTRACTED);
    }

    public long transferFePerTick() {
        return value(VALUE_TRANSFER);
    }
}
