package com.miningdim.power.grid;

import com.miningdim.core.MiningConstants;
import com.miningdim.power.cable.ConductorMaterial;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * 与 Flux Networks (通量网络) 的 Forge Energy 协议级互操作。
 *
 * 本仓库对 Flux 只有一份配方覆盖, 零代码集成, 两边全靠 Forge Energy capability 通用互操作, 因此这里
 * 用与 Flux 设备逐位一致的 capability 形状做替身, 把互操作的每条方向钉死。形状取自 FluxNetworks
 * 1.20.1-7.2.1.15 的 javap 实测 (见各替身的注释), 而不是照抄我方实现 —— 期望值必须独立于被测代码。
 *
 * Flux 侧的判据 (sonar.fluxnetworks.common.integration.energy.ForgeEnergyConnector):
 *  - canSendTo(邻居)     = 邻居.canReceive()   —— Flux Point 往外送电的门
 *  - canReceiveFrom(邻居) = 邻居.canExtract()  —— Flux Plug 往里吸电的门
 *  - sendTo/receiveFrom 分别调邻居的 receiveEnergy/extractEnergy
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class FluxInteropGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "flux_interop";

    private FluxInteropGameTests() {
    }

    /**
     * Flux Plug 是纯消费端形状, 必须能被我方电网主动 push 喂到电。
     *
     * Plug 自己每 cycle 会尝试 receiveFrom(邻居) 即反向抽取, 而我方线缆是 receive-only, 那条路注定为空;
     * 因此"Plug 能不能拿到电"完全取决于我方 manager 认不认它是消费端。认了才通。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH, timeoutTicks = 200)
    public static void fluxPlugShapeIsFedByOurNetwork(GameTestHelper helper) {
        EnergyNetworkManager manager = EnergyNetworkManager.get(helper.getLevel());
        BlockPos cableA = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos cableB = cableA.east();
        manager.addCable(cableA, ConductorMaterial.COPPER);
        manager.addCable(cableB, ConductorMaterial.COPPER);

        FluxPlugShape plug = new FluxPlugShape(true);
        manager.debugPutSyntheticEndpoint(cableA.west(), infiniteSource());
        manager.debugPutSyntheticEndpoint(cableB.south(), plug);

        helper.startSequence()
                .thenIdle(10)
                .thenExecute(() -> helper.assertTrue(plug.received > 0,
                        "Flux Plug 形状(只收不发)必须被我方电网当消费端喂电, 实收 " + plug.received + " FE"))
                .thenExecute(manager::debugClearSyntheticEndpoints)
                .thenSucceed();
    }

    /**
     * Flux 网络未建立时 Plug 的 canReceive 返回 false (实测: canReceive 体是 getNetwork().isValid())。
     * 此时我方必须干净跳过它, 而不是把它当成一个吃不下电的黑洞或者抛异常。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH, timeoutTicks = 200)
    public static void unlinkedFluxPlugIsSkippedNotStuck(GameTestHelper helper) {
        EnergyNetworkManager manager = EnergyNetworkManager.get(helper.getLevel());
        BlockPos cableA = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos cableB = cableA.east();
        manager.addCable(cableA, ConductorMaterial.COPPER);
        manager.addCable(cableB, ConductorMaterial.COPPER);

        FluxPlugShape unlinked = new FluxPlugShape(false);
        FakeSink realLoad = new FakeSink(500);
        manager.debugPutSyntheticEndpoint(cableA.west(), infiniteSource());
        manager.debugPutSyntheticEndpoint(cableB.south(), unlinked);
        manager.debugPutSyntheticEndpoint(cableB.north(), realLoad);

        helper.startSequence()
                .thenIdle(10)
                .thenExecute(() -> {
                    helper.assertTrue(unlinked.received == 0,
                            "未接入 Flux 网络的 Plug 必须收不到电, 实收 " + unlinked.received + " FE");
                    helper.assertTrue(realLoad.stored == 500,
                            "未接网的 Plug 不得挡住同网真实负载, 负载实收 " + realLoad.stored + " FE");
                })
                .thenExecute(manager::debugClearSyntheticEndpoints)
                .thenSucceed();
    }

    /**
     * Flux Point 是"两个 can 都为 false + 主动 sendTo 邻居"的形状 (实测 canReceive/canExtract 体均为
     * iconst_0)。它对我方是死端点, 靠自己调线缆的 receiveEnergy 送电进来。
     *
     * 这条钉两件事: 一是死端点混在端点表里不得让结算出错; 二是它推进来的电必须真进本网缓冲并能被消费端用掉。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH, timeoutTicks = 200)
    public static void fluxPointShapePushesIntoOurCable(GameTestHelper helper) {
        EnergyNetworkManager manager = EnergyNetworkManager.get(helper.getLevel());
        BlockPos cableA = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos cableB = cableA.east();
        manager.addCable(cableA, ConductorMaterial.COPPER);
        manager.addCable(cableB, ConductorMaterial.COPPER);

        FluxPointShape point = new FluxPointShape();
        FakeSink sink = new FakeSink(400);
        manager.debugPutSyntheticEndpoint(cableB.south(), point);
        manager.debugPutSyntheticEndpoint(cableA.north(), sink);

        helper.startSequence()
                // Flux Point 在自己的 cycle 里主动调邻居 receiveEnergy, 这里逐 tick 复现该动作。
                .thenExecuteFor(20, () -> manager.receiveIntoNetwork(cableB, 256, false))
                .thenIdle(5)
                .thenExecute(() -> {
                    helper.assertTrue(sink.stored > 0,
                            "Flux Point 推进线缆的电必须能被同网消费端用掉, 消费端实收 " + sink.stored + " FE");
                    helper.assertTrue(point.extractCalls == 0,
                            "Flux Point 形状不应被我方当生产端反向抽取, 实际被抽 " + point.extractCalls + " 次");
                })
                .thenExecute(manager::debugClearSyntheticEndpoints)
                .thenSucceed();
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

    /**
     * TileFluxPlug$EnergyStorage 的形状: canExtract 恒 false, canReceive = 所属 Flux 网络是否有效。
     */
    private static final class FluxPlugShape implements IEnergyStorage {
        private final boolean networkValid;
        private int received;

        private FluxPlugShape(boolean networkValid) {
            this.networkValid = networkValid;
        }

        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            if (!networkValid) {
                return 0;
            }
            if (!simulate) {
                received += maxReceive;
            }
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
            return networkValid;
        }
    }

    /** TileFluxPoint$EnergyStorage 的形状: canReceive 与 canExtract 均恒 false。 */
    private static final class FluxPointShape implements IEnergyStorage {
        private int extractCalls;

        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            return 0;
        }

        @Override
        public int extractEnergy(int maxExtract, boolean simulate) {
            if (!simulate) {
                extractCalls++;
            }
            return 0;
        }

        @Override
        public int getEnergyStored() {
            return 0;
        }

        @Override
        public int getMaxEnergyStored() {
            return 0;
        }

        @Override
        public boolean canExtract() {
            return false;
        }

        @Override
        public boolean canReceive() {
            return false;
        }
    }

    /** 纯消费端, 用于验证 Flux 设备不会挡住同网的真实负载。 */
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
}
