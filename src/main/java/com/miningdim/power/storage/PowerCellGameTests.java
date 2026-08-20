package com.miningdim.power.storage;

import com.miningdim.core.MiningConstants;
import com.miningdim.power.PowerLitDisplay;
import com.miningdim.power.PowerRegistry;
import com.miningdim.power.cable.ConductorMaterial;
import com.miningdim.power.grid.CableThermics;
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

    /**
     * 空载电网不得动储电一分钱。
     *
     * 修复前拉阶段的储电轮无条件把线缆缓冲填满, 推阶段又原样灌回, 于是没有任何负载时储电每 tick 也在
     * "抽出去又收回来": 余额看着不动 (净额为零), 实际两侧各扣一次距离损耗在慢慢漏电, 且回灌量被计入
     * 负载把网温顶进降效区, 稳态卡在 eff=0.75 —— 线缆常年白丢 25% 有效吞吐。故余额断言不够, 必须钉死
     * 放电流水为零, 并钉死网温不离环境温。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH, timeoutTicks = 200)
    public static void idleNetworkNeverDrainsStorage(GameTestHelper helper) {
        EnergyNetworkManager manager = EnergyNetworkManager.get(helper.getLevel());
        BlockPos cableA = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos cableB = cableA.east();
        manager.addCable(cableA, ConductorMaterial.COPPER);
        manager.addCable(cableB, ConductorMaterial.COPPER);

        FakeCell cell = new FakeCell(1_000_000);
        cell.stored = 500_000;
        manager.debugPutSyntheticEndpoint(cableB.south(), cell);

        helper.startSequence()
                .thenIdle(40)
                .thenExecute(() -> {
                    helper.assertTrue(cell.extractedTotal == 0,
                            "空载电网必须一点都不抽储电, 实抽 " + cell.extractedTotal
                                    + " FE, 放电 " + cell.extractCalls + " 次");
                    helper.assertTrue(cell.stored == 500_000,
                            "储电余额必须纹丝不动, 得到 " + cell.stored);
                    double temperature = manager.networkTemperatureAt(cableA);
                    helper.assertTrue(Math.abs(temperature - CableThermics.AMBIENT_C) < 0.001,
                            "空载不得升温, 得到 " + temperature + "C (修复前空转往返会把网温顶到降效点)");
                })
                .thenExecute(manager::debugClearSyntheticEndpoints)
                .thenSucceed();
    }

    /**
     * 储电放电量只补消费端的真实缺口, 不按线缆额定吞吐放。
     *
     * 铜缆额定 1280 FE/t, 而消费端总共只要 300 FE。修复前储电会被抽满 1280 (填满线缆缓冲), 多出来的
     * 980 当场原路灌回; 修复后放电量必须贴着 300 这个真实缺口。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH, timeoutTicks = 200)
    public static void storageDischargeTracksConsumerDemand(GameTestHelper helper) {
        EnergyNetworkManager manager = EnergyNetworkManager.get(helper.getLevel());
        BlockPos cableA = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos cableB = cableA.east();
        manager.addCable(cableA, ConductorMaterial.COPPER);
        manager.addCable(cableB, ConductorMaterial.COPPER);

        FakeCell cell = new FakeCell(1_000_000);
        cell.stored = 500_000;
        FakeSink sink = new FakeSink(300);
        manager.debugPutSyntheticEndpoint(cableA.north(), sink);
        manager.debugPutSyntheticEndpoint(cableB.south(), cell);

        helper.startSequence()
                .thenIdle(40)
                .thenExecute(() -> {
                    // 尾部 1 FE 送不达是距离损耗的既有整数边界, 与本修复无关: 铜缆 168 units 下
                    // netAfterDistanceLoss(1, 168) 整除后为 0, 于是最后一点余量永远开不出报价。
                    // 这里钉的是"储电确实把消费端喂到了容量附近", 不是钉那 1 FE。
                    helper.assertTrue(sink.stored >= 299,
                            "消费端必须由储电喂到容量附近, 得到 " + sink.stored);
                    // 放行余量留给距离损耗的整数进位; 上限远低于 1280, 修复前的"抽满缓冲"必然越过。
                    helper.assertTrue(cell.extractedTotal <= 400,
                            "储电放电量必须贴着消费端缺口(300 FE)而不是线缆额定(1280 FE/t), 实抽 "
                                    + cell.extractedTotal + " FE");
                    helper.assertTrue(cell.extractedTotal >= 300,
                            "放电量不得少于消费端实收, 实抽 " + cell.extractedTotal + " FE");
                })
                .thenExecute(manager::debugClearSyntheticEndpoints)
                .thenSucceed();
    }

    /**
     * LIT 熄灭必须走宽限, 不能跟着当 tick 的流量翻。
     *
     * 供电落在临界时机器/储电会"攒一 tick 跑一格", 若 LIT 直接等于当 tick 的流量判定, 贴图就每一两
     * tick 翻一次 (真机上看到的抽搐), 且每次翻转都是一次方块更新。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void litHoldsThroughGraceAfterFlowStops(GameTestHelper helper) {
        PowerCellBlockEntity cell = place(helper, CELL_REL, PowerRegistry.INDUSTRIAL_POWER_CELL.get());
        energyOf(cell).receiveEnergy(1_000, false);
        cell.serverTick();
        helper.assertTrue(helper.getBlockState(CELL_REL).getValue(PowerCellBlock.LIT),
                "有进出流量必须点亮");

        for (int tick = 0; tick < PowerLitDisplay.GRACE_TICKS - 1; tick++) {
            cell.serverTick();
        }
        helper.assertTrue(helper.getBlockState(CELL_REL).getValue(PowerCellBlock.LIT),
                "宽限期内断流不得熄灭, 否则供电临界会被渲染成贴图抽搐");

        cell.serverTick();
        helper.assertTrue(!helper.getBlockState(CELL_REL).getValue(PowerCellBlock.LIT),
                "宽限耗尽必须熄灭, 否则灯会骗人");
        helper.succeed();
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
        /** 真实放电总量与调用次数: 空转 churn 只看余额看不出来 (抽出又灌回, 净额为零), 必须记流水。 */
        private int extractedTotal;
        private int extractCalls;

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
                extractedTotal += extracted;
                if (extracted > 0) {
                    extractCalls++;
                }
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
