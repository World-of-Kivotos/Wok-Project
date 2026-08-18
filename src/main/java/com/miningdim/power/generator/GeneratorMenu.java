package com.miningdim.power.generator;

import com.miningdim.menu.AbstractMiningMenu;
import com.miningdim.menu.MenuValidity;
import com.miningdim.power.GeneratorMultiblockBlock;
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

/** 三档发电机菜单：燃料芯与镍铬保险丝均可由玩家取放，自动化抽取由 BE 能力另行限制。 */
public final class GeneratorMenu extends AbstractMiningMenu {

    public static final int CONTAINER_SLOTS = GeneratorBlockEntity.SLOT_COUNT;
    public static final int DATA_STATE = 0;
    public static final int DATA_STORED_FE_LOW = 1;
    public static final int DATA_STORED_FE_HIGH = 2;
    public static final int DATA_BUFFER_CAPACITY_LOW = 3;
    public static final int DATA_BUFFER_CAPACITY_HIGH = 4;
    public static final int DATA_TEMPERATURE_LOW = 5;
    public static final int DATA_TEMPERATURE_HIGH = 6;
    public static final int DATA_MELTDOWN_TEMPERATURE_LOW = 7;
    public static final int DATA_MELTDOWN_TEMPERATURE_HIGH = 8;
    public static final int DATA_BUFFER_REJECTION_LOW = 9;
    public static final int DATA_BUFFER_REJECTION_HIGH = 10;
    public static final int DATA_FUSE_STATE = 11;
    public static final int DATA_FUEL_REMAINING_LOW = 12;
    public static final int DATA_FUEL_REMAINING_HIGH = 13;
    public static final int DATA_FUEL_MAX_DAMAGE_LOW = 14;
    public static final int DATA_FUEL_MAX_DAMAGE_HIGH = 15;
    public static final int DATA_NETWORK_FAULT = 16;
    public static final int DATA_COUNT = 17;

    private static final int FUEL_SLOT_X = 69;
    private static final int FUEL_SLOT_Y = 37;
    private static final int FUSE_SLOT_X = 133;
    private static final int FUSE_SLOT_Y = 37;

    private final @Nullable GeneratorBlockEntity blockEntity;
    private final ItemStackHandler fallbackInventory = new ItemStackHandler(CONTAINER_SLOTS);
    private final ContainerData data;

    public GeneratorMenu(int windowId, Inventory playerInv, BlockPos pos) {
        super(PowerRegistry.GENERATOR_MENU.get(), windowId, CONTAINER_SLOTS,
                MenuValidity.ofBlock(ContainerLevelAccess.create(playerInv.player.level(), pos),
                        generatorBlockAt(playerInv, pos)));
        this.blockEntity = findGenerator(playerInv, pos);
        addGeneratorSlots(playerInv, pos);
        addPlayerInventory(playerInv, 28, 142);
        this.data = dataFor(blockEntity, playerInv.player.level().isClientSide);
        addDataSlots(data);
    }

    private static Block generatorBlockAt(Inventory playerInv, BlockPos pos) {
        Block block = playerInv.player.level().getBlockState(pos).getBlock();
        if (block instanceof GeneratorMultiblockBlock) {
            return block;
        }
        throw new IllegalArgumentException("generator menu received a non-generator position: " + pos);
    }

    private static GeneratorBlockEntity findGenerator(Inventory playerInv, BlockPos pos) {
        if (playerInv.player.level().getBlockEntity(pos) instanceof GeneratorBlockEntity generator) {
            return generator;
        }
        return null;
    }

    private void addGeneratorSlots(Inventory playerInv, BlockPos pos) {
        ItemStackHandler handler = blockEntity == null ? fallbackInventory : blockEntity.inventory();
        this.addSlot(new SlotItemHandler(handler, GeneratorBlockEntity.SLOT_FUEL_CORE,
                FUEL_SLOT_X, FUEL_SLOT_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                if (blockEntity != null) {
                    return blockEntity.inventory().isItemValid(GeneratorBlockEntity.SLOT_FUEL_CORE, stack);
                }
                return stack.getItem() instanceof GeneratorFuelCoreItem
                        && ((GeneratorFuelCoreItem) stack.getItem()).spec()
                        == GeneratorSpec.forBlock(generatorBlockAt(playerInv, pos));
            }
        });
        this.addSlot(new SlotItemHandler(handler, GeneratorBlockEntity.SLOT_NICHROME_FUSE,
                FUSE_SLOT_X, FUSE_SLOT_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return stack.is(PowerRegistry.NICHROME_FUSE.get());
            }
        });
    }

    static ContainerData dataFor(@Nullable GeneratorBlockEntity generator, boolean clientSide) {
        if (clientSide || generator == null) {
            return new SimpleContainerData(DATA_COUNT);
        }
        return new ContainerData() {
            @Override
            public int get(int index) {
                return switch (index) {
                    case DATA_STATE -> generator.state().ordinal();
                    case DATA_STORED_FE_LOW -> lowWord(generator.storedFe());
                    case DATA_STORED_FE_HIGH -> highWord(generator.storedFe());
                    case DATA_BUFFER_CAPACITY_LOW -> lowWord(generator.bufferCapacityFe());
                    case DATA_BUFFER_CAPACITY_HIGH -> highWord(generator.bufferCapacityFe());
                    case DATA_TEMPERATURE_LOW -> lowWord(toCentidegrees(generator.temperatureC()));
                    case DATA_TEMPERATURE_HIGH -> highWord(toCentidegrees(generator.temperatureC()));
                    case DATA_MELTDOWN_TEMPERATURE_LOW -> lowWord(toCentidegrees(generator.meltdownTemperatureC()));
                    case DATA_MELTDOWN_TEMPERATURE_HIGH -> highWord(toCentidegrees(generator.meltdownTemperatureC()));
                    case DATA_BUFFER_REJECTION_LOW -> lowWord(generator.bufferRejectionFe());
                    case DATA_BUFFER_REJECTION_HIGH -> highWord(generator.bufferRejectionFe());
                    case DATA_FUSE_STATE -> generator.fuseState().ordinal();
                    case DATA_FUEL_REMAINING_LOW -> lowWord(fuelRemainingDurability(generator));
                    case DATA_FUEL_REMAINING_HIGH -> highWord(fuelRemainingDurability(generator));
                    case DATA_FUEL_MAX_DAMAGE_LOW -> lowWord(fuelMaxDamage(generator));
                    case DATA_FUEL_MAX_DAMAGE_HIGH -> highWord(fuelMaxDamage(generator));
                    case DATA_NETWORK_FAULT -> generator.networkFault().ordinal();
                    default -> throw new IndexOutOfBoundsException("generator menu data index: " + index);
                };
            }

            @Override
            public void set(int index, int value) {
                if (index < 0 || index >= DATA_COUNT) {
                    throw new IndexOutOfBoundsException("generator menu data index: " + index);
                }
            }

            @Override
            public int getCount() {
                return DATA_COUNT;
            }
        };
    }

    private static int fuelRemainingDurability(GeneratorBlockEntity generator) {
        ItemStack fuelCore = generator.fuelCore();
        return fuelCore.isEmpty() ? 0 : Math.max(0, fuelCore.getMaxDamage() - fuelCore.getDamageValue());
    }

    private static int fuelMaxDamage(GeneratorBlockEntity generator) {
        ItemStack fuelCore = generator.fuelCore();
        return fuelCore.isEmpty() ? 0 : fuelCore.getMaxDamage();
    }

    private static int toCentidegrees(double temperature) {
        return Math.toIntExact(Math.round(temperature * 100.0D));
    }

    public @Nullable GeneratorBlockEntity blockEntity() {
        return blockEntity;
    }

    public int dataValue(int index) {
        return data.get(index);
    }

    public int stateOrdinal() {
        return dataValue(DATA_STATE);
    }

    public int storedFe() {
        return joinInt32(dataValue(DATA_STORED_FE_LOW), dataValue(DATA_STORED_FE_HIGH));
    }

    public int bufferCapacityFe() {
        return joinInt32(dataValue(DATA_BUFFER_CAPACITY_LOW), dataValue(DATA_BUFFER_CAPACITY_HIGH));
    }

    public double temperatureC() {
        return joinInt32(dataValue(DATA_TEMPERATURE_LOW), dataValue(DATA_TEMPERATURE_HIGH)) / 100.0D;
    }

    public double meltdownTemperatureC() {
        return joinInt32(dataValue(DATA_MELTDOWN_TEMPERATURE_LOW),
                dataValue(DATA_MELTDOWN_TEMPERATURE_HIGH)) / 100.0D;
    }

    public int bufferRejectionFe() {
        return joinInt32(dataValue(DATA_BUFFER_REJECTION_LOW), dataValue(DATA_BUFFER_REJECTION_HIGH));
    }

    public int fuseStateOrdinal() {
        return dataValue(DATA_FUSE_STATE);
    }

    public int fuelRemainingDurability() {
        return joinInt32(dataValue(DATA_FUEL_REMAINING_LOW), dataValue(DATA_FUEL_REMAINING_HIGH));
    }

    public int fuelMaxDamage() {
        return joinInt32(dataValue(DATA_FUEL_MAX_DAMAGE_LOW), dataValue(DATA_FUEL_MAX_DAMAGE_HIGH));
    }

    public int networkFaultOrdinal() {
        return dataValue(DATA_NETWORK_FAULT);
    }

    static int lowWord(int value) {
        return value & 0xFFFF;
    }

    static int highWord(int value) {
        return (value >>> 16) & 0xFFFF;
    }

    static int joinInt32(int low, int high) {
        return (low & 0xFFFF) | ((high & 0xFFFF) << 16);
    }
}
