package com.miningdim.power.cable;

import com.miningdim.core.MiningConstants;
import com.miningdim.power.PowerRegistry;
import com.miningdim.power.grid.CableThermics;
import com.miningdim.power.grid.EnergyNetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * 线缆网络的 GameTest。拓扑/缓冲用直接放置/破坏线缆断言并网与拆网及全网共享缓冲; 热学用合成端点 (无需注册测试方块)
 * 驱动真实 settle 循环, 断言"持续满载->网温升->有效吞吐降->撤载冷却回升"这条过载降效闭环端到端成立。
 * 发电机->线缆->用电端的真实发电机联调放到发电机子系统 (另立文档)。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class EnergyCableGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "energy_cable";

    private EnergyCableGameTests() {
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void cablesMergeOnBridgeAndSplitOnRemoval(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        EnergyNetworkManager manager = EnergyNetworkManager.get(level);
        BlockState iron = PowerRegistry.CABLES.get(ConductorMaterial.IRON).get().defaultBlockState();

        BlockPos a = new BlockPos(2, 1, 1);
        BlockPos bridge = new BlockPos(2, 1, 2);
        BlockPos c = new BlockPos(2, 1, 3);
        BlockPos aAbs = helper.absolutePos(a);
        BlockPos bridgeAbs = helper.absolutePos(bridge);
        BlockPos cAbs = helper.absolutePos(c);

        // A 与 C 之间留空 -> 两张独立网, 各 1 根。
        helper.setBlock(a, iron);
        helper.setBlock(c, iron);
        helper.assertTrue(!manager.debugSameNetwork(aAbs, cAbs),
                "留空的 A 与 C 必须是两张独立网");
        helper.assertTrue(manager.debugNetworkSize(aAbs) == 1,
                "A 必须是单根成网, 实为 " + manager.debugNetworkSize(aAbs));

        // 桥接 B -> 合并成一张 3 根的网。
        helper.setBlock(bridge, iron);
        helper.assertTrue(manager.debugSameNetwork(aAbs, cAbs),
                "桥接中段后 A..C 必须并入同一张网");
        helper.assertTrue(manager.debugNetworkSize(aAbs) == 3,
                "合并后应含 3 根线缆, 实为 " + manager.debugNetworkSize(aAbs));

        // 拆掉桥 B -> 重新分裂为两张单根网。
        helper.setBlock(bridge, Blocks.AIR);
        helper.assertTrue(!manager.debugSameNetwork(aAbs, cAbs),
                "移除桥段后必须拆成两张网");
        helper.assertTrue(manager.debugNetworkSize(aAbs) == 1 && manager.debugNetworkSize(cAbs) == 1,
                "拆分两半必须各为单根网");
        helper.assertTrue(manager.debugNetworkSize(bridgeAbs) == 0,
                "已移除的桥段不应再属于任何网");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void cableCapabilityIsReceiveOnlyAndSharesBufferAcrossNetwork(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        EnergyNetworkManager manager = EnergyNetworkManager.get(level);
        BlockState iron = PowerRegistry.CABLES.get(ConductorMaterial.IRON).get().defaultBlockState();

        BlockPos first = new BlockPos(2, 1, 1);
        BlockPos second = new BlockPos(2, 1, 2);
        helper.setBlock(first, iron);
        helper.setBlock(second, iron);
        BlockPos firstAbs = helper.absolutePos(first);
        BlockPos secondAbs = helper.absolutePos(second);

        IEnergyStorage cap = level.getBlockEntity(firstAbs)
                .getCapability(ForgeCapabilities.ENERGY).resolve().orElse(null);
        helper.assertTrue(cap != null, "线缆必须暴露 ForgeCapabilities.ENERGY");
        helper.assertTrue(!cap.canExtract(), "线缆能力必须只收不放 (canExtract=false)");
        helper.assertTrue(cap.canReceive(), "线缆能力必须可收 (canReceive=true)");

        // 铁级瞬态缓冲 = 额定 = 256; 收 100 应全收。
        int accepted = cap.receiveEnergy(100, false);
        helper.assertTrue(accepted == 100, "铁线缆缓冲应全收 100 FE, 实收 " + accepted);

        // 相邻线缆共享同一张网的缓冲。
        helper.assertTrue(manager.storedAt(secondAbs) == 100,
                "相邻线缆必须共享全网缓冲, 实读 " + manager.storedAt(secondAbs));

        // 再收 10000: 单次被封顶且缓冲(256)只剩 156 空间 -> 实收 156, 缓冲填满 256。
        int accepted2 = cap.receiveEnergy(10_000, false);
        helper.assertTrue(accepted2 == 156, "越量收电应受缓冲余量(156)限制, 实收 " + accepted2);
        helper.assertTrue(manager.storedAt(firstAbs) == 256,
                "缓冲应填满至 256, 实读 " + manager.storedAt(firstAbs));
        helper.succeed();
    }

    /**
     * 热学过载降效端到端: 一条铜缆 (额定 1280) 接无限源与无限汇, 持续满载 -> 网温超安全线升温 -> 有效吞吐被
     * eff(网温) 压到低于额定; 撤源后负载归零 -> 网温冷却回落近环境。删掉热学逻辑此测必挂 (网温恒为环境、吞吐恒额定)。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH, timeoutTicks = 500)
    public static void networkOverheatsUnderSustainedLoadThenRecovers(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        EnergyNetworkManager manager = EnergyNetworkManager.get(level);
        BlockState copper = PowerRegistry.CABLES.get(ConductorMaterial.COPPER).get().defaultBlockState();

        BlockPos a = new BlockPos(2, 1, 1);
        BlockPos mid = new BlockPos(2, 1, 2);
        BlockPos b = new BlockPos(2, 1, 3);
        helper.setBlock(a, copper);
        helper.setBlock(mid, copper);
        helper.setBlock(b, copper);
        BlockPos aAbs = helper.absolutePos(a);

        // 无限源接 a 端外侧, 无限汇接 b 端外侧 (合成端点仅作 map 键, 无需真方块/在结构界内)。
        BlockPos srcPos = helper.absolutePos(new BlockPos(2, 1, 0));
        BlockPos sinkPos = helper.absolutePos(new BlockPos(2, 1, 4));
        manager.debugPutSyntheticEndpoint(srcPos, infiniteSource());
        manager.debugPutSyntheticEndpoint(sinkPos, infiniteSink());

        int rated = manager.debugRatedCapAt(aAbs);
        helper.assertTrue(rated == ConductorMaterial.COPPER.ratedCapacityFe(),
                "铜网额定应为 " + ConductorMaterial.COPPER.ratedCapacityFe() + ", 实为 " + rated);

        helper.startSequence()
                // 持续满载升温至降效平衡 (满载 loadRatio=1.0 > 0.75 安全线)。
                .thenIdle(200)
                .thenExecute(() -> {
                    double temp = manager.networkTemperatureAt(aAbs);
                    helper.assertTrue(temp > CableThermics.AMBIENT_C + 1.0,
                            "持续满载后网温必须显著高于环境 " + CableThermics.AMBIENT_C + ", 实为 " + temp);
                    int load = manager.debugLastLoadAt(aAbs);
                    helper.assertTrue(load < rated,
                            "过热后有效吞吐必须被压到低于额定 " + rated + ", 实为 " + load);
                    helper.assertTrue(load > 0, "平衡态仍应有部分吞吐 (非全断), 实为 " + load);
                })
                // 撤掉源与汇 -> 负载归零 -> 冷却回升。
                .thenExecute(manager::debugClearSyntheticEndpoints)
                .thenIdle(220)
                .thenExecute(() -> {
                    double temp = manager.networkTemperatureAt(aAbs);
                    helper.assertTrue(temp < CableThermics.AMBIENT_C + 5.0,
                            "撤载冷却后网温必须回落近环境, 实为 " + temp);
                })
                .thenSucceed();
    }

    /** 合成无限源: 只出不进 (canExtract), 供热学测试制造持续满载。 */
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

    /** 合成无限汇: 只进不出 (canReceive 且非 canExtract), 令送达量不受消费端限制。 */
    private static IEnergyStorage infiniteSink() {
        return new IEnergyStorage() {
            @Override
            public int receiveEnergy(int maxReceive, boolean simulate) {
                return maxReceive;
            }

            @Override
            public int extractEnergy(int maxExtract, boolean simulate) {
                return 0;
            }

            @Override
            public int getEnergyStored() {
                return 0;
            }

            @Override
            public int getMaxEnergyStored() {
                return Integer.MAX_VALUE;
            }

            @Override
            public boolean canExtract() {
                return false;
            }

            @Override
            public boolean canReceive() {
                return true;
            }
        };
    }
}
