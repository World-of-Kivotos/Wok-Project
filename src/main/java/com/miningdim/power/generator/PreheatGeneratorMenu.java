package com.miningdim.power.generator;

import com.miningdim.menu.AbstractMiningMenu;
import com.miningdim.menu.MenuValidity;
import com.miningdim.power.PowerRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.SlotItemHandler;
import org.jetbrains.annotations.Nullable;

/**
 * 前期发电机菜单。煤炭机有一个燃料槽；地热机没有燃料语义，槽位仍然存在但一律拒收，
 * 以便两台共用同一套槽位契约与同一个界面类。
 */
public final class PreheatGeneratorMenu extends AbstractMiningMenu {

    public static final int CONTAINER_SLOTS = PreheatGeneratorBlockEntity.SLOT_COUNT;
    public static final int DATA_TEMPERATURE_CENTI_LOW = 0;
    public static final int DATA_TEMPERATURE_CENTI_HIGH = 1;
    public static final int DATA_WORKING_TEMPERATURE_CENTI_LOW = 2;
    public static final int DATA_WORKING_TEMPERATURE_CENTI_HIGH = 3;
    public static final int DATA_STORED_FE_LOW = 4;
    public static final int DATA_STORED_FE_HIGH = 5;
    public static final int DATA_BUFFER_CAPACITY_LOW = 6;
    public static final int DATA_BUFFER_CAPACITY_HIGH = 7;
    public static final int DATA_OUTPUT_FE_LOW = 8;
    public static final int DATA_OUTPUT_FE_HIGH = 9;
    public static final int DATA_BURN_REMAINING_LOW = 10;
    public static final int DATA_BURN_REMAINING_HIGH = 11;
    public static final int DATA_BURN_TOTAL_LOW = 12;
    public static final int DATA_BURN_TOTAL_HIGH = 13;
    public static final int DATA_COUNT = 14;

    private static final int FUEL_SLOT_X = 101;
    private static final int FUEL_SLOT_Y = 36;

    private final @Nullable PreheatGeneratorBlockEntity blockEntity;
    private final ItemStackHandler fallbackInventory = new ItemStackHandler(CONTAINER_SLOTS);
    private final ContainerData data;

    public PreheatGeneratorMenu(int windowId, Inventory playerInv, BlockPos pos) {
        super(PowerRegistry.PREHEAT_GENERATOR_MENU.get(), windowId, CONTAINER_SLOTS,
                MenuValidity.ofBlock(ContainerLevelAccess.create(playerInv.player.level(), pos),
                        preheatGeneratorBlockAt(playerInv, pos)));
        this.blockEntity = findGenerator(playerInv, pos);
        addFuelSlot();
        addPlayerInventory(playerInv, 28, 142);
        this.data = dataFor(blockEntity, playerInv.player.level().isClientSide);
        addDataSlots(data);
    }

    private static Block preheatGeneratorBlockAt(Inventory playerInv, BlockPos pos) {
        Block block = playerInv.player.level().getBlockState(pos).getBlock();
        if (block instanceof PreheatGeneratorBlock) {
            return block;
        }
        throw new IllegalArgumentException("preheat generator menu received a non-generator position: " + pos);
    }

    @Nullable
    private static PreheatGeneratorBlockEntity findGenerator(Inventory playerInv, BlockPos pos) {
        if (playerInv.player.level().getBlockEntity(pos) instanceof PreheatGeneratorBlockEntity generator) {
            return generator;
        }
        return null;
    }

    private void addFuelSlot() {
        ItemStackHandler handler = blockEntity == null ? fallbackInventory : blockEntity.inventory();
        this.addSlot(new SlotItemHandler(handler, PreheatGeneratorBlockEntity.SLOT_FUEL,
                FUEL_SLOT_X, FUEL_SLOT_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return handler.isItemValid(PreheatGeneratorBlockEntity.SLOT_FUEL, stack);
            }
        });
    }

    private static ContainerData dataFor(@Nullable PreheatGeneratorBlockEntity generator, boolean clientSide) {
        if (generator == null || clientSide) {
            return new SimpleContainerData(DATA_COUNT);
        }
        return new ContainerData() {
            @Override
            public int get(int index) {
                PreheatGeneratorSpec.Runtime runtime = generator.runtime();
                return switch (index) {
                    case DATA_TEMPERATURE_CENTI_LOW -> lowWord(centi(generator.temperatureC()));
                    case DATA_TEMPERATURE_CENTI_HIGH -> highWord(centi(generator.temperatureC()));
                    case DATA_WORKING_TEMPERATURE_CENTI_LOW -> lowWord(centi(runtime.workingTemperatureC()));
                    case DATA_WORKING_TEMPERATURE_CENTI_HIGH -> highWord(centi(runtime.workingTemperatureC()));
                    case DATA_STORED_FE_LOW -> lowWord(generator.storedFe());
                    case DATA_STORED_FE_HIGH -> highWord(generator.storedFe());
                    case DATA_BUFFER_CAPACITY_LOW -> lowWord(runtime.bufferCapacityFe());
                    case DATA_BUFFER_CAPACITY_HIGH -> highWord(runtime.bufferCapacityFe());
                    case DATA_OUTPUT_FE_LOW -> lowWord(generator.currentOutputFePerTick());
                    case DATA_OUTPUT_FE_HIGH -> highWord(generator.currentOutputFePerTick());
                    case DATA_BURN_REMAINING_LOW -> lowWord(generator.burnTicksRemaining());
                    case DATA_BURN_REMAINING_HIGH -> highWord(generator.burnTicksRemaining());
                    case DATA_BURN_TOTAL_LOW -> lowWord(generator.burnTicksTotal());
                    case DATA_BURN_TOTAL_HIGH -> highWord(generator.burnTicksTotal());
                    default -> throw new IllegalArgumentException("invalid preheat generator data index: " + index);
                };
            }

            @Override
            public void set(int index, int value) {
                if (index < 0 || index >= DATA_COUNT) {
                    throw new IllegalArgumentException("invalid preheat generator data index: " + index);
                }
                // 这些数据是服务端到客户端单向同步, 客户端写入一律忽略。
            }

            @Override
            public int getCount() {
                return DATA_COUNT;
            }
        };
    }

    /** 温度带两位小数, 以厘摄氏度整数过 ContainerData, 避免浮点同步。 */
    public static int centi(double temperatureC) {
        return (int) Math.round(temperatureC * 100.0D);
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

    public double temperatureC() {
        return merge(data.get(DATA_TEMPERATURE_CENTI_LOW), data.get(DATA_TEMPERATURE_CENTI_HIGH)) / 100.0D;
    }

    public double workingTemperatureC() {
        return merge(data.get(DATA_WORKING_TEMPERATURE_CENTI_LOW),
                data.get(DATA_WORKING_TEMPERATURE_CENTI_HIGH)) / 100.0D;
    }

    public int storedFe() {
        return merge(data.get(DATA_STORED_FE_LOW), data.get(DATA_STORED_FE_HIGH));
    }

    public int bufferCapacityFe() {
        return merge(data.get(DATA_BUFFER_CAPACITY_LOW), data.get(DATA_BUFFER_CAPACITY_HIGH));
    }

    public int outputFePerTick() {
        return merge(data.get(DATA_OUTPUT_FE_LOW), data.get(DATA_OUTPUT_FE_HIGH));
    }

    public int burnTicksRemaining() {
        return merge(data.get(DATA_BURN_REMAINING_LOW), data.get(DATA_BURN_REMAINING_HIGH));
    }

    public int burnTicksTotal() {
        return merge(data.get(DATA_BURN_TOTAL_LOW), data.get(DATA_BURN_TOTAL_HIGH));
    }
}
