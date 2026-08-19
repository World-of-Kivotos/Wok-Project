package com.miningdim.power.generator;

import com.miningdim.power.PowerRegistry;
import com.miningdim.power.grid.CableThermics;
import com.miningdim.power.grid.VoltageAwareEnergyStorage;
import com.miningdim.power.grid.VoltageClass;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.ItemStackHandler;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 前期预热式发电机（煤炭 / 地热）的控制器。
 *
 * 温度在这里是正向变量：冷机产出为零，烧到工作温度才满载，中间线性。这与燃料芯发电机的温度语义
 * （只在拒收时上升、通向熔毁）互补，两者合起来构成完整的温度曲线，本机不设熔毁。
 */
public final class PreheatGeneratorBlockEntity extends BlockEntity implements MenuProvider {

    public static final int SLOT_FUEL = 0;
    public static final int SLOT_COUNT = 1;

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/power");
    private static final String K_TEMPERATURE = "temperatureC";
    private static final String K_BURN_REMAINING = "burnTicksRemaining";
    private static final String K_BURN_TOTAL = "burnTicksTotal";
    private static final String K_STORED = "storedFe";
    private static final String K_ITEMS = "items";

    private final PreheatGeneratorSpec spec;
    private final ItemStackHandler inventory = new ItemStackHandler(SLOT_COUNT) {
        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            if (slot != SLOT_FUEL) {
                throw new IllegalArgumentException("invalid preheat generator slot: " + slot);
            }
            // 地热机没有燃料槽语义: 槽位仍然存在以便两台共用同一套菜单契约, 但一律拒收。
            return spec.fuelSource() == PreheatGeneratorSpec.FuelSource.BURNABLE_ITEM
                    && ForgeHooks.getBurnTime(stack, RecipeType.SMELTING) > 0;
        }

        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
        }
    };

    private final OutputStorage energy = new OutputStorage();
    private final LazyOptional<ItemStackHandler> itemCap = LazyOptional.of(() -> inventory);
    private final LazyOptional<VoltageAwareEnergyStorage> energyCap = LazyOptional.of(() -> energy);

    private double temperatureC = CableThermics.AMBIENT_C;
    private int burnTicksRemaining;
    private int burnTicksTotal;
    private int storedFe;

    public PreheatGeneratorBlockEntity(BlockPos pos, BlockState state) {
        super(PowerRegistry.PREHEAT_GENERATOR_BE.get(), pos, state);
        this.spec = PreheatGeneratorBlock.specOf(state.getBlock());
    }

    public PreheatGeneratorSpec spec() {
        return spec;
    }

    public PreheatGeneratorSpec.Runtime runtime() {
        return spec.runtime();
    }

    public ItemStackHandler inventory() {
        return inventory;
    }

    public double temperatureC() {
        return temperatureC;
    }

    public int storedFe() {
        return storedFe;
    }

    public int burnTicksRemaining() {
        return burnTicksRemaining;
    }

    public int burnTicksTotal() {
        return burnTicksTotal;
    }

    /** 当前温度对应的输出功率，供菜单与 Jade 直接展示，不重复推导公式。 */
    public int currentOutputFePerTick() {
        return runtime().outputAt(temperatureC);
    }

    public void serverTick() {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        PreheatGeneratorSpec.Runtime runtime = runtime();
        double previousTemperature = temperatureC;
        int previousStored = storedFe;

        // 已满温且缓冲已满时停火保温: 既不空烧玩家的燃料, 也不必在下次取电时重新预热。
        boolean holding = storedFe >= runtime.bufferCapacityFe()
                && temperatureC >= runtime.workingTemperatureC();
        boolean heated = holding || keepHeating(serverLevel, runtime);

        if (heated) {
            // 逐 tick 累加浮点升温值, 走完预热时长后会停在 299.999999 而非 300, 使"是否满温"的判定
            // 永远差一点点(保温逻辑因此第一 tick 必定失效, 白烧一次燃料)。故临门一脚直接吸附。
            double next = temperatureC + runtime.heatupCPerTick();
            temperatureC = next >= runtime.workingTemperatureC() - 1.0E-9D
                    ? runtime.workingTemperatureC() : next;
        } else {
            temperatureC = Math.max(CableThermics.AMBIENT_C, temperatureC - runtime.coolingCPerTick());
        }

        int room = runtime.bufferCapacityFe() - storedFe;
        if (room > 0) {
            storedFe += Math.min(room, runtime.outputAt(temperatureC));
        }

        boolean lit = temperatureC > CableThermics.AMBIENT_C;
        if (getBlockState().getValue(PreheatGeneratorBlock.LIT) != lit) {
            serverLevel.setBlock(worldPosition, getBlockState().setValue(PreheatGeneratorBlock.LIT, lit), 3);
        }
        if (Double.compare(previousTemperature, temperatureC) != 0 || previousStored != storedFe) {
            setChanged();
        }
    }

    /** 维持热源。返回 true 表示本 tick 有热量输入，可以继续升温。 */
    private boolean keepHeating(ServerLevel serverLevel, PreheatGeneratorSpec.Runtime runtime) {
        if (spec.fuelSource() == PreheatGeneratorSpec.FuelSource.LAVA_SOURCE_BELOW) {
            // 地热是可再生的: 只认脚下那一格岩浆源, 不消耗它。流动残留(level 1-7)会变化甚至消失,
            // 不能作为稳定机位, 故必须是源方块。
            return serverLevel.getFluidState(worldPosition.below()).isSourceOfType(Fluids.LAVA);
        }
        if (burnTicksRemaining > 0) {
            burnTicksRemaining--;
            return true;
        }
        ItemStack fuel = inventory.getStackInSlot(SLOT_FUEL);
        int burnTime = ForgeHooks.getBurnTime(fuel, RecipeType.SMELTING);
        if (burnTime <= 0) {
            burnTicksTotal = 0;
            return false;
        }
        // 原版一块煤只烧 1600 tick(80 秒), 对一台要手动喂料的发电机太短, 故按配置倍率放大。
        burnTicksTotal = Math.multiplyExact(burnTime, runtime.fuelBurnMultiplier());
        burnTicksRemaining = burnTicksTotal - 1;
        ItemStack remainder = fuel.getCraftingRemainingItem();
        fuel.shrink(1);
        if (fuel.isEmpty() && !remainder.isEmpty()) {
            inventory.setStackInSlot(SLOT_FUEL, remainder);
        }
        setChanged();
        return true;
    }

    /** 供输出端口抽取；网络侧只经此扣减，内部不另设扣减路径。 */
    private int extractForNetwork(int maxExtract, boolean simulate) {
        int extracted = Math.min(maxExtract, storedFe);
        if (extracted <= 0) {
            return 0;
        }
        if (!simulate) {
            storedFe -= extracted;
            setChanged();
        }
        return extracted;
    }

    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> capability, @Nullable net.minecraft.core.Direction side) {
        if (capability == ForgeCapabilities.ENERGY) {
            return energyCap.cast();
        }
        if (capability == ForgeCapabilities.ITEM_HANDLER) {
            return itemCap.cast();
        }
        return super.getCapability(capability, side);
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        itemCap.invalidate();
        energyCap.invalidate();
    }

    /**
     * 保存路径一律不抛异常。方块实体的保存会被区块自动保存、Forge 的 BlockSnapshot、以及整合包里
     * 任何挂在 setBlock 上的观察者在任意时机触发；本仓已因保存路径抛异常炸过一次服（发电机端口未
     * 连控制器时 saveAdditional 抛 IllegalStateException，把放置补格循环整个炸断）。
     */
    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putDouble(K_TEMPERATURE, temperatureC);
        tag.putInt(K_BURN_REMAINING, burnTicksRemaining);
        tag.putInt(K_BURN_TOTAL, burnTicksTotal);
        tag.putInt(K_STORED, storedFe);
        tag.put(K_ITEMS, inventory.serializeNBT());
    }

    /**
     * 读取路径同样不抛异常，缺键按初始值补齐、越界值钳回合法区间并记一条 warn。
     *
     * 这里刻意偏离本仓机器方块"NBT 不合规即 throw"的既有范式：那套写法适用于玩家中后期才接触、
     * 且异常能被及时发现的设备；而前期发电机是新玩家最早放下的方块之一，一旦 NBT 因任何原因不完整，
     * 抛异常会让整个区块加载失败、存档打不开，代价远超"静默修正一个温度值"。真正的不变量（温度不
     * 低于环境、电量不超容量）在这里被强制恢复，而不是被掩盖。
     */
    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains(K_ITEMS, Tag.TAG_COMPOUND)) {
            inventory.deserializeNBT(tag.getCompound(K_ITEMS));
        }
        temperatureC = tag.contains(K_TEMPERATURE, Tag.TAG_DOUBLE)
                ? tag.getDouble(K_TEMPERATURE) : CableThermics.AMBIENT_C;
        burnTicksRemaining = tag.getInt(K_BURN_REMAINING);
        burnTicksTotal = tag.getInt(K_BURN_TOTAL);
        storedFe = tag.getInt(K_STORED);

        PreheatGeneratorSpec.Runtime runtime = runtime();
        double clampedTemperature = Math.min(runtime.workingTemperatureC(),
                Math.max(CableThermics.AMBIENT_C, temperatureC));
        int clampedStored = Math.min(runtime.bufferCapacityFe(), Math.max(0, storedFe));
        int clampedBurnTotal = Math.max(0, burnTicksTotal);
        int clampedBurnRemaining = Math.min(clampedBurnTotal, Math.max(0, burnTicksRemaining));
        if (Double.compare(clampedTemperature, temperatureC) != 0 || clampedStored != storedFe
                || clampedBurnTotal != burnTicksTotal || clampedBurnRemaining != burnTicksRemaining) {
            LOGGER.warn("preheat generator NBT out of range at {}; clamped temperature {}->{}, stored {}->{}, "
                            + "burn {}/{}->{}/{}", worldPosition, temperatureC, clampedTemperature,
                    storedFe, clampedStored, burnTicksRemaining, burnTicksTotal,
                    clampedBurnRemaining, clampedBurnTotal);
        }
        temperatureC = clampedTemperature;
        storedFe = clampedStored;
        burnTicksTotal = clampedBurnTotal;
        burnTicksRemaining = clampedBurnRemaining;
    }

    @Override
    public Component getDisplayName() {
        return getBlockState().getBlock().getName();
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int windowId, Inventory playerInventory, Player player) {
        return new PreheatGeneratorMenu(windowId, playerInventory, worldPosition);
    }

    /** 发电侧存储：只出不进。外部电源不得往发电机里灌电，否则电网会出现能量环流。 */
    private final class OutputStorage implements VoltageAwareEnergyStorage {

        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            return 0;
        }

        @Override
        public int extractEnergy(int maxExtract, boolean simulate) {
            return extractForNetwork(maxExtract, simulate);
        }

        @Override
        public int getEnergyStored() {
            return storedFe;
        }

        @Override
        public int getMaxEnergyStored() {
            return runtime().bufferCapacityFe();
        }

        @Override
        public boolean canExtract() {
            return true;
        }

        @Override
        public boolean canReceive() {
            return false;
        }

        @Override
        public VoltageClass outputVoltage() {
            return spec.sourceVoltage();
        }

        @Override
        public void reportOvervoltage(VoltageClass networkLimit) {
            // LOW 是最低电压段, 网侧限制不可能低于它, 走到这里说明电网判定有误, 记录而不静默。
            LOGGER.warn("preheat generator at {} received an overvoltage report against network limit {}",
                    worldPosition, networkLimit);
        }
    }
}
