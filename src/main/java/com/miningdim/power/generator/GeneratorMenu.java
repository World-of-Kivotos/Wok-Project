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

/** 三档发电机菜单：燃料芯与镍铬保险丝均可由玩家取放，自动化抽取由 BE 能力另行限制。 */
public final class GeneratorMenu extends AbstractMiningMenu {

    public static final int CONTAINER_SLOTS = GeneratorBlockEntity.SLOT_COUNT;
    public static final int DATA_COUNT = 10;

    private static final int FUEL_SLOT_X = 62;
    private static final int FUEL_SLOT_Y = 35;
    private static final int FUSE_SLOT_X = 98;
    private static final int FUSE_SLOT_Y = 35;

    private final GeneratorBlockEntity blockEntity;
    private final ItemStackHandler fallbackInventory = new ItemStackHandler(CONTAINER_SLOTS);
    private final ContainerData data;

    public GeneratorMenu(int windowId, Inventory playerInv, BlockPos pos) {
        super(PowerRegistry.GENERATOR_MENU.get(), windowId, CONTAINER_SLOTS,
                MenuValidity.ofBlock(ContainerLevelAccess.create(playerInv.player.level(), pos),
                        generatorBlockAt(playerInv, pos)));
        this.blockEntity = findGenerator(playerInv, pos);
        addGeneratorSlots(playerInv, pos);
        addPlayerInventory(playerInv, 8, 140);
        this.data = blockEntity == null ? new SimpleContainerData(DATA_COUNT) : dataFor(blockEntity);
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

    private static ContainerData dataFor(GeneratorBlockEntity generator) {
        return new ContainerData() {
            @Override
            public int get(int index) {
                return switch (index) {
                    case 0 -> generator.state().ordinal();
                    case 1 -> generator.storedFe();
                    case 2 -> generator.bufferCapacityFe();
                    case 3 -> toCentidegrees(generator.temperatureC());
                    case 4 -> toCentidegrees(generator.meltdownTemperatureC());
                    case 5 -> generator.bufferRejectionFe();
                    case 6 -> generator.fuseState().ordinal();
                    case 7 -> generator.fuelCore().isEmpty() ? 0 : generator.fuelCore().getDamageValue();
                    case 8 -> generator.fuelCore().isEmpty() ? 0 : generator.fuelCore().getMaxDamage();
                    case 9 -> generator.networkFault().ordinal();
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

    private static int toCentidegrees(double temperature) {
        return Math.toIntExact(Math.round(temperature * 100.0D));
    }

    public GeneratorBlockEntity blockEntity() {
        return blockEntity;
    }

    public int dataValue(int index) {
        return data.get(index);
    }

    public int stateOrdinal() {
        return dataValue(0);
    }

    public int storedFe() {
        return dataValue(1);
    }

    public int bufferCapacityFe() {
        return dataValue(2);
    }

    public double temperatureC() {
        return dataValue(3) / 100.0D;
    }

    public double meltdownTemperatureC() {
        return dataValue(4) / 100.0D;
    }

    public int bufferRejectionFe() {
        return dataValue(5);
    }

    public int fuseStateOrdinal() {
        return dataValue(6);
    }

    public int fuelDamage() {
        return dataValue(7);
    }

    public int fuelMaxDamage() {
        return dataValue(8);
    }

    public int networkFaultOrdinal() {
        return dataValue(9);
    }
}
