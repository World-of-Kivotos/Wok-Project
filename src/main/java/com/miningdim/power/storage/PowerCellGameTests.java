package com.miningdim.power.storage;

import com.miningdim.core.MiningConstants;
import com.miningdim.power.PowerRegistry;
import com.miningdim.power.cable.ConductorMaterial;
import com.miningdim.power.grid.EnergyNetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * 三级储电的运行契约与挂网行为。
 *
 * 最关键的一条是 storageChargesFromNetworkAfterConsumers: 电网原本把"既能收又能发"的端点一律当
 * 生产端处理（推阶段筛掉了所有 canExtract 的端点），储电挂上去只会被放电、永远充不进电。这条用例
 * 钉死修复后的分轮语义——先满足纯消费端，余量才进储电。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class PowerCellGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "power_cell";
    private static final BlockPos CELL_REL = new BlockPos(3, 2, 3);

    private PowerCellGameTests() {
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void threeTiersKeepExactCapacityAndTransferContract(GameTestHelper helper) {
        assertRuntime(helper, PowerCellSpec.INDUSTRIAL, 13_824_000, 768);
        assertRuntime(helper, PowerCellSpec.MODERN, 165_888_000, 4_608);
        assertRuntime(helper, PowerCellSpec.FUTURE, 884_736_000, 12_288);

        // 容量锚点: 每级储电恰好等于对应档发电机一个燃料芯的总产量。
        helper.assertTrue(PowerCellSpec.INDUSTRIAL.runtime().capacityFe() == 192 * 72_000,
                "工业储电必须等于工业发电机一芯产量");
        helper.assertTrue(PowerCellSpec.MODERN.runtime().capacityFe() == 1_152 * 144_000,
                "现代储电必须等于现代发电机一芯产量");
        helper.assertTrue(PowerCellSpec.FUTURE.runtime().capacityFe() == 3_072 * 288_000,
                "未来储电必须等于未来发电机一芯产量");

        // 容量硬顶必须留在 int 安全区内, 否则 capability 暴露时会出现有损截断。
        helper.assertTrue(PowerCellSpec.MAX_CONFIGURABLE_CAPACITY < Integer.MAX_VALUE,
                "可配置容量上限必须小于 Integer.MAX_VALUE");
        helper.assertTrue(PowerCellSpec.FUTURE.runtime().capacityFe()
                        <= PowerCellSpec.MAX_CONFIGURABLE_CAPACITY,
                "最高档容量不得超过可配置硬顶");
        helper.assertTrue(PowerCellSpec.byId("future") == PowerCellSpec.FUTURE, "规格 id 必须可反查");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void transferRateClampsBothDirectionsAndCapacityHolds(GameTestHelper helper) {
        PowerCellBlockEntity cell = place(helper, CELL_REL, PowerRegistry.INDUSTRIAL_POWER_CELL.get());
        IEnergyStorage storage = energyOf(cell);
        int rate = PowerCellSpec.INDUSTRIAL.runtime().transferFePerTick();

        helper.assertTrue(storage.canReceive() && storage.canExtract(),
                "储电必须是双向端点, 这正是它与发电机(只出)和机器(只进)的区别");
        helper.assertTrue(storage.receiveEnergy(Integer.MAX_VALUE, false) == rate,
                "单次注入必须被传输速率钳到 " + rate);
        helper.assertTrue(cell.storedFe() == rate, "注入后余额必须精确等于速率, 得到 " + cell.storedFe());
        helper.assertTrue(storage.extractEnergy(Integer.MAX_VALUE, false) == rate,
                "单次抽取同样被速率钳制");
        helper.assertTrue(cell.storedFe() == 0, "抽干后余额必须归零, 得到 " + cell.storedFe());

        helper.assertTrue(storage.receiveEnergy(100, true) == 100 && cell.storedFe() == 0,
                "simulate 不得真正改变余额");

        // 灌满: 容量除以速率次注入应恰好填满且不溢出。
        int capacity = PowerCellSpec.INDUSTRIAL.runtime().capacityFe();
        int guard = 0;
        while (cell.storedFe() < capacity && guard++ < 100_000) {
            storage.receiveEnergy(Integer.MAX_VALUE, false);
        }
        helper.assertTrue(cell.storedFe() == capacity,
                "反复注入必须精确停在容量上限, 得到 " + cell.storedFe());
        helper.assertTrue(storage.receiveEnergy(rate, false) == 0, "满仓后不得再收电");
        helper.assertTrue(cell.storedFeLong() == capacity,
                "long 账本必须与 int 读数一致, 得到 " + cell.storedFeLong());
        helper.succeed();
    }

    /**
     * 挂网回归。修复前电网推阶段的筛选是 "!canReceive() || canExtract() 即跳过"，储电两者皆真，
     * 于是永远进不了推阶段、只会被当生产端抽干——挂上去等于一个永远充不满的洞。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH, timeoutTicks = 200)
    public static void storageChargesFromNetworkAfterConsumers(GameTestHelper helper) {
        EnergyNetworkManager manager = EnergyNetworkManager.get(helper.getLevel());
        BlockPos cableA = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos cableB = cableA.east();
        manager.addCable(cableA, ConductorMaterial.COPPER);
        manager.addCable(cableB, ConductorMaterial.COPPER);

        FakeCell cell = new FakeCell(1_000_000);
        FakeSink sink = new FakeSink(64);
        manager.debugPutSyntheticEndpoint(cableA.west(), infiniteSource());
        manager.debugPutSyntheticEndpoint(cableA.north(), sink);
        manager.debugPutSyntheticEndpoint(cableB.south(), cell);

        helper.startSequence()
                .thenIdle(5)
                .thenExecute(() -> {
                    helper.assertTrue(sink.stored == 64,
                            "纯消费端必须被优先喂满, 得到 " + sink.stored);
                    helper.assertTrue(cell.stored > 0,
                            "储电必须能从电网充上电(修复前恒为 0), 得到 " + cell.stored);
                })
                .thenExecute(manager::debugClearSyntheticEndpoints)
                .thenSucceed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void nbtRoundTripAndOutOfRangeClamp(GameTestHelper helper) {
        PowerCellBlockEntity cell = place(helper, CELL_REL, PowerRegistry.MODERN_POWER_CELL.get());
        energyOf(cell).receiveEnergy(4_000, false);
        CompoundTag saved = cell.saveWithFullMetadata();
        int savedStored = cell.storedFe();

        PowerCellBlockEntity reloaded = new PowerCellBlockEntity(
                helper.absolutePos(CELL_REL), cellState(PowerRegistry.MODERN_POWER_CELL.get()));
        reloaded.load(saved);
        helper.assertTrue(reloaded.storedFe() == savedStored,
                "余额必须原样往返, 得到 " + reloaded.storedFe());

        CompoundTag corrupted = cell.saveWithFullMetadata();
        corrupted.putLong("storedFe", Long.MAX_VALUE);
        PowerCellBlockEntity clamped = new PowerCellBlockEntity(
                helper.absolutePos(CELL_REL), cellState(PowerRegistry.MODERN_POWER_CELL.get()));
        clamped.load(corrupted);
        helper.assertTrue(clamped.storedFe() == PowerCellSpec.MODERN.runtime().capacityFe(),
                "越界余额必须钳到容量而不是抛异常, 得到 " + clamped.storedFe());

        CompoundTag negative = cell.saveWithFullMetadata();
        negative.putLong("storedFe", -1L);
        PowerCellBlockEntity floored = new PowerCellBlockEntity(
                helper.absolutePos(CELL_REL), cellState(PowerRegistry.MODERN_POWER_CELL.get()));
        floored.load(negative);
        helper.assertTrue(floored.storedFe() == 0, "负余额必须钳到零, 得到 " + floored.storedFe());
        helper.succeed();
    }

    private static void assertRuntime(GameTestHelper helper, PowerCellSpec spec, int capacity, int transfer) {
        PowerCellSpec.Runtime runtime = spec.runtime();
        helper.assertTrue(runtime.capacityFe() == capacity && runtime.transferFePerTick() == transfer,
                spec + " 运行档位必须与出厂默认一致, 得到 " + runtime);
        helper.assertTrue(runtime.equals(spec.defaults()),
                spec + " 未改配置时运行值必须与出厂默认逐字段相等");
    }

    private static BlockState cellState(Block block) {
        return block.defaultBlockState().setValue(PowerCellBlock.FACING, Direction.NORTH);
    }

    private static PowerCellBlockEntity place(GameTestHelper helper, BlockPos relative, Block block) {
        helper.setBlock(relative, cellState(block));
        if (helper.getBlockEntity(relative) instanceof PowerCellBlockEntity cell) {
            return cell;
        }
        throw new IllegalStateException("power cell block entity missing at " + relative);
    }

    private static IEnergyStorage energyOf(PowerCellBlockEntity cell) {
        return cell.getCapability(ForgeCapabilities.ENERGY)
                .orElseThrow(() -> new IllegalStateException("power cell exposes no energy capability"));
    }

    private static IEnergyStorage infiniteSource() {
        return new IEnergyStorage() {
            @Override
            public int receiveEnergy(int maxReceive, boolean simulate) {
                return 0;
            }

            @Override
            public int extractEnergy(int maxExtract, boolean simulate) {
                return maxExtract;
            }

            @Override
            public int getEnergyStored() {
                return Integer.MAX_VALUE;
            }

            @Override
            public int getMaxEnergyStored() {
                return Integer.MAX_VALUE;
            }

            @Override
            public boolean canExtract() {
                return true;
            }

            @Override
            public boolean canReceive() {
                return false;
            }
        };
    }

    /** 纯消费端: 只收不发, 容量小, 用于验证它排在储电之前被喂饱。 */
    private static final class FakeSink implements IEnergyStorage {
        private final int capacity;
        private int stored;

        private FakeSink(int capacity) {
            this.capacity = capacity;
        }

        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            int accepted = Math.min(maxReceive, capacity - stored);
            if (!simulate) {
                stored += accepted;
            }
            return accepted;
        }

        @Override
        public int extractEnergy(int maxExtract, boolean simulate) {
            return 0;
        }

        @Override
        public int getEnergyStored() {
            return stored;
        }

        @Override
        public int getMaxEnergyStored() {
            return capacity;
        }

        @Override
        public boolean canExtract() {
            return false;
        }

        @Override
        public boolean canReceive() {
            return true;
        }
    }

    /** 双向端点, 形状与真实储电一致, 用于验证电网分轮后能给它充电。 */
    private static final class FakeCell implements IEnergyStorage {
        private final int capacity;
        private int stored;

        private FakeCell(int capacity) {
            this.capacity = capacity;
        }

        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            int accepted = Math.min(maxReceive, capacity - stored);
            if (!simulate) {
                stored += accepted;
            }
            return accepted;
        }

        @Override
        public int extractEnergy(int maxExtract, boolean simulate) {
            int extracted = Math.min(maxExtract, stored);
            if (!simulate) {
                stored -= extracted;
            }
            return extracted;
        }

        @Override
        public int getEnergyStored() {
            return stored;
        }

        @Override
        public int getMaxEnergyStored() {
            return capacity;
        }

        @Override
        public boolean canExtract() {
            return true;
        }

        @Override
        public boolean canReceive() {
            return true;
        }
    }
}
