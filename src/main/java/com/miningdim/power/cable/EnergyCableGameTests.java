package com.miningdim.power.cable;

import com.miningdim.core.MiningConstants;
import com.miningdim.power.PowerRegistry;
import com.miningdim.power.grid.CableThermics;
import com.miningdim.power.grid.EnergyNetworkFault;
import com.miningdim.power.grid.EnergyNetworkManager;
import com.miningdim.power.grid.EnergyNetworkSnapshot;
import com.miningdim.power.grid.VoltageAwareEnergyStorage;
import com.miningdim.power.grid.VoltageClass;
import com.miningdim.power.rubber.PowerRubberRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
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
    public static void p1AndP2RegisteredMaterialsPreserveExistingIds(GameTestHelper helper) {
        ConductorMaterial[] p1 = {
                ConductorMaterial.IRON,
                ConductorMaterial.ALUMINUM,
                ConductorMaterial.COPPER
        };
        helper.assertTrue(PowerRegistry.P1_MATERIALS.size() == 3
                        && PowerRegistry.REGISTERED_MATERIALS.size() == 9
                        && PowerRegistry.CABLES.size() == 9
                        && PowerRegistry.CABLE_ITEMS.size() == 9
                        && PowerRegistry.WIRE_ITEMS.size() == 9
                        && PowerRegistry.REGISTERED_MATERIALS.containsAll(java.util.List.of(
                                ConductorMaterial.IRON, ConductorMaterial.ALUMINUM, ConductorMaterial.COPPER,
                                ConductorMaterial.TINNED_COPPER, ConductorMaterial.OFC_COPPER,
                                ConductorMaterial.OFE_COPPER, ConductorMaterial.SILVER_PLATED_COPPER,
                                ConductorMaterial.GOLD, ConductorMaterial.SILVER))
                        && !PowerRegistry.CABLES.containsKey(ConductorMaterial.GRAPHENE)
                        && !PowerRegistry.CABLES.containsKey(ConductorMaterial.NBTI_SUPERCONDUCTOR)
                        && !PowerRegistry.CABLES.containsKey(ConductorMaterial.YBCO_SUPERCONDUCTOR),
                "注册集合必须恰为 T1-T9，且 T10-T12 不得提前开放，实得 "
                        + PowerRegistry.P1_MATERIALS.size() + "/"
                        + PowerRegistry.REGISTERED_MATERIALS.size() + "/"
                        + PowerRegistry.CABLES.size() + "/"
                        + PowerRegistry.CABLE_ITEMS.size() + "/"
                        + PowerRegistry.WIRE_ITEMS.size());
        for (ConductorMaterial material : p1) {
            helper.assertTrue(PowerRegistry.CABLES.get(material).getId().getPath().equals(material.blockId())
                            && PowerRegistry.CABLE_ITEMS.get(material).getId().getPath().equals(material.blockId())
                            && PowerRegistry.WIRE_ITEMS.get(material).getId().getPath().equals(material.id() + "_wire")
                            && PowerRegistry.CABLES.get(material).get().material() == material,
                    "P1 " + material.id() + " 必须保留线缆 ID、注册方块物品并新增匹配导线");
        }
        helper.assertTrue(ConductorMaterial.IRON.ratedCapacityFe() == 256
                        && ConductorMaterial.ALUMINUM.ratedCapacityFe() == 768
                        && ConductorMaterial.COPPER.ratedCapacityFe() == 1_280,
                "P1 铁、铝、铜容量必须固定为 256/768/1280 FE/t");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void p2ConductorsKeepExactCapacityVoltageAndInsulationContracts(GameTestHelper helper) {
        ConductorMaterial[] p2 = {
                ConductorMaterial.TINNED_COPPER,
                ConductorMaterial.OFC_COPPER,
                ConductorMaterial.OFE_COPPER,
                ConductorMaterial.SILVER_PLATED_COPPER,
                ConductorMaterial.GOLD,
                ConductorMaterial.SILVER
        };
        for (ConductorMaterial material : p2) {
            helper.assertTrue(PowerRegistry.CABLES.get(material).getId().getPath().equals(material.blockId())
                            && PowerRegistry.CABLE_ITEMS.get(material).getId().getPath().equals(material.blockId())
                            && PowerRegistry.WIRE_ITEMS.get(material).getId().getPath().equals(material.id() + "_wire")
                            && PowerRegistry.CABLES.get(material).get().material() == material,
                    "P2 " + material.id() + " 必须注册匹配的线缆方块、物品和导线");
        }
        helper.assertTrue(ConductorMaterial.TINNED_COPPER.ratedCapacityFe() == 1_536
                        && ConductorMaterial.OFC_COPPER.ratedCapacityFe() == 2_048
                        && ConductorMaterial.OFE_COPPER.ratedCapacityFe() == 3_072
                        && ConductorMaterial.SILVER_PLATED_COPPER.ratedCapacityFe() == 4_096
                        && ConductorMaterial.GOLD.ratedCapacityFe() == 2_560
                        && ConductorMaterial.SILVER.ratedCapacityFe() == 5_120,
                "P2 六档导体容量必须固定为 1536/2048/3072/4096/2560/5120 FE/t");
        helper.assertTrue(ConductorMaterial.TINNED_COPPER.voltageClass() == VoltageClass.MEDIUM
                        && ConductorMaterial.OFC_COPPER.voltageClass() == VoltageClass.MEDIUM
                        && ConductorMaterial.OFE_COPPER.voltageClass() == VoltageClass.MEDIUM
                        && ConductorMaterial.SILVER_PLATED_COPPER.voltageClass() == VoltageClass.HIGH
                        && ConductorMaterial.GOLD.voltageClass() == VoltageClass.HIGH
                        && ConductorMaterial.SILVER.voltageClass() == VoltageClass.HIGH,
                "P2 耐压必须按 T4-T6 MEDIUM、T7-T9 HIGH 固定");
        helper.assertTrue(ConductorMaterial.TINNED_COPPER.insulation() == InsulationGrade.PE
                        && ConductorMaterial.OFC_COPPER.insulation() == InsulationGrade.EPR
                        && ConductorMaterial.OFE_COPPER.insulation() == InsulationGrade.XLPE
                        && ConductorMaterial.SILVER_PLATED_COPPER.insulation() == InsulationGrade.XLPE
                        && ConductorMaterial.GOLD.insulation() == InsulationGrade.XLPE
                        && ConductorMaterial.SILVER.insulation() == InsulationGrade.SILICONE
                        && PowerRubberRegistry.INSULATION_EPR.getId().getPath().equals("insulation_epr")
                        && PowerRubberRegistry.INSULATION_XLPE.getId().getPath().equals("insulation_xlpe")
                        && PowerRubberRegistry.INSULATION_SILICONE.getId().getPath().equals("insulation_silicone"),
                "P2 绝缘品及 T4-T9 绝缘关联必须固定");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void cableSixWayConnectionsAndShapesTrackPlacedNeighbours(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockState iron = PowerRegistry.CABLES.get(ConductorMaterial.IRON).get().defaultBlockState();
        BlockPos center = new BlockPos(4, 2, 4);
        BlockPos centerAbs = helper.absolutePos(center);
        helper.setBlock(center, iron);
        for (Direction direction : Direction.values()) {
            helper.setBlock(center.relative(direction), iron);
            helper.assertTrue(level.getBlockState(centerAbs).getValue(connectionProperty(direction)),
                    "放置 " + direction + " 邻缆后中心线缆必须接通该方向");
        }
        for (Direction direction : Direction.values()) {
            helper.setBlock(center.relative(direction), Blocks.AIR);
            helper.assertTrue(!level.getBlockState(centerAbs).getValue(connectionProperty(direction)),
                    "移除 " + direction + " 邻缆后中心线缆必须断开该方向");
        }

        EnergyCableBlock cable = (EnergyCableBlock) level.getBlockState(centerAbs).getBlock();
        BlockState disconnected = level.getBlockState(centerAbs);
        VoxelShape disconnectedShape = cable.getShape(disconnected, level, centerAbs, CollisionContext.empty());
        VoxelShape disconnectedCollision = cable.getCollisionShape(disconnected, level, centerAbs,
                CollisionContext.empty());
        assertShapeBounds(helper, disconnectedShape, 0.375D, 0.625D, 0.375D, 0.625D, 0.375D, 0.625D,
                "无臂线缆外形");
        assertShapeBounds(helper, disconnectedCollision, 0.375D, 0.625D, 0.375D, 0.625D, 0.375D, 0.625D,
                "无臂线缆碰撞");
        helper.assertTrue(!cable.isCollisionShapeFullBlock(disconnected, level, centerAbs),
                "无臂线缆碰撞不得是满方块");

        BlockState eastWest = disconnected.setValue(EnergyCableBlock.EAST, true)
                .setValue(EnergyCableBlock.WEST, true);
        VoxelShape eastWestShape = cable.getShape(eastWest, level, centerAbs, CollisionContext.empty());
        VoxelShape eastWestCollision = cable.getCollisionShape(eastWest, level, centerAbs, CollisionContext.empty());
        assertShapeBounds(helper, eastWestShape, 0.0D, 1.0D, 0.375D, 0.625D, 0.375D, 0.625D,
                "东西轴向线缆外形");
        assertShapeBounds(helper, eastWestCollision, 0.0D, 1.0D, 0.375D, 0.625D, 0.375D, 0.625D,
                "东西轴向线缆碰撞");
        helper.assertTrue(!cable.isCollisionShapeFullBlock(eastWest, level, centerAbs),
                "东西轴向线缆碰撞不得是满方块");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void cableOnLoadRepairsStaleConnectionStateWithoutTicker(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockState iron = PowerRegistry.CABLES.get(ConductorMaterial.IRON).get().defaultBlockState();
        BlockPos center = new BlockPos(4, 2, 4);
        BlockPos east = center.relative(Direction.EAST);
        BlockPos centerAbs = helper.absolutePos(center);
        helper.setBlock(east, iron);
        helper.setBlock(center, iron);
        BlockState stale = level.getBlockState(centerAbs).setValue(EnergyCableBlock.EAST, false);
        level.setBlock(centerAbs, stale, Block.UPDATE_CLIENTS);
        helper.assertTrue(!level.getBlockState(centerAbs).getValue(EnergyCableBlock.EAST),
                "测试前必须构造出已加载邻缆旁的陈旧断开状态");

        BlockEntity blockEntity = level.getBlockEntity(centerAbs);
        helper.assertTrue(blockEntity instanceof EnergyCableBlockEntity,
                "线缆必须具有 EnergyCableBlockEntity 以执行 chunk load 修复");
        blockEntity.onLoad();
        BlockState repaired = level.getBlockState(centerAbs);
        helper.assertTrue(repaired.getValue(EnergyCableBlock.EAST),
                "真实 EnergyCableBlockEntity.onLoad 必须修复陈旧 EAST 连接");
        EnergyCableBlock cable = (EnergyCableBlock) repaired.getBlock();
        helper.assertTrue(cable.getTicker(level, repaired, PowerRegistry.ENERGY_CABLE_BE.get()) == null,
                "线缆修复不得引入逐方块 ticker");
        helper.succeed();
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

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY,
            batch = "energy_cable_multi_face", timeoutTicks = 40)
    public static void multiFaceEndpointsAndRoundRobinStayFair(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        EnergyNetworkManager manager = EnergyNetworkManager.get(level);
        BlockState iron = PowerRegistry.CABLES.get(ConductorMaterial.IRON).get().defaultBlockState();

        BlockPos corner = new BlockPos(2, 1, 2);
        BlockPos northCable = new BlockPos(3, 1, 2);
        BlockPos westCable = new BlockPos(2, 1, 3);
        helper.setBlock(corner, iron);
        helper.setBlock(northCable, iron);
        helper.setBlock(westCable, iron);

        CountingSource sourceWest = new CountingSource(4_096);
        CountingSource sourceNorth = new CountingSource(4_096);
        CountingSink sinkNorthFace = new CountingSink();
        CountingSink sinkWestFace = new CountingSink();
        BlockPos sharedEndpoint = helper.absolutePos(new BlockPos(3, 1, 3));
        manager.debugPutSyntheticEndpoint(helper.absolutePos(new BlockPos(1, 1, 2)), Direction.EAST, sourceWest);
        manager.debugPutSyntheticEndpoint(helper.absolutePos(new BlockPos(2, 1, 1)), Direction.SOUTH, sourceNorth);
        manager.debugPutSyntheticEndpoint(sharedEndpoint, Direction.NORTH, sinkNorthFace);
        manager.debugPutSyntheticEndpoint(sharedEndpoint, Direction.WEST, sinkWestFace);

        BlockPos networkPos = helper.absolutePos(corner);
        helper.startSequence()
                .thenIdle(4)
                .thenExecute(() -> {
                    helper.assertTrue(manager.debugEndpointCountAt(networkPos) == 4,
                            "同一方块的两个查询面必须与两个源一起保留为 4 个端点，实得 "
                                    + manager.debugEndpointCountAt(networkPos));
                    helper.assertTrue(sourceWest.extracted() > 0 && sourceNorth.extracted() > 0,
                            "双生产端必须都获得轮转额度，实得 "
                                    + sourceWest.extracted() + "/" + sourceNorth.extracted());
                    helper.assertTrue(Math.abs(sourceWest.extracted() - sourceNorth.extracted()) <= 256,
                            "双生产端四 tick 累计差不得超过一轮 256 FE，实得 "
                                    + sourceWest.extracted() + "/" + sourceNorth.extracted());
                    helper.assertTrue(sinkNorthFace.received() > 0 && sinkWestFace.received() > 0,
                            "同坐标双面消费端必须都获得轮转额度，实得 "
                                    + sinkNorthFace.received() + "/" + sinkWestFace.received());
                    helper.assertTrue(Math.abs(sinkNorthFace.received() - sinkWestFace.received()) <= 256,
                            "双面消费端四 tick 累计差不得超过一轮 256 FE，实得 "
                                    + sinkNorthFace.received() + "/" + sinkWestFace.received());
                })
                .thenExecute(manager::debugClearSyntheticEndpoints)
                .thenSucceed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void splitDistributesAndMergeAccountsForOverflow(GameTestHelper helper) {
        EnergyNetworkManager manager = EnergyNetworkManager.get(helper.getLevel());
        BlockState copper = PowerRegistry.CABLES.get(ConductorMaterial.COPPER).get().defaultBlockState();
        BlockState iron = PowerRegistry.CABLES.get(ConductorMaterial.IRON).get().defaultBlockState();
        BlockPos a = new BlockPos(2, 1, 1);
        BlockPos bridge = new BlockPos(2, 1, 2);
        BlockPos c = new BlockPos(2, 1, 3);
        BlockPos aAbs = helper.absolutePos(a);
        BlockPos cAbs = helper.absolutePos(c);
        helper.setBlock(a, copper);
        helper.setBlock(bridge, iron);
        helper.setBlock(c, iron);
        helper.assertTrue(manager.receiveIntoNetwork(aAbs, 200, false) == 200,
                "混级网必须先接收 200 FE 作为拆网守恒输入");

        helper.setBlock(bridge, Blocks.AIR);
        helper.assertTrue(manager.storedAt(aAbs) == 167 && manager.storedAt(cAbs) == 33,
                "拆网必须按 1280:256 容量比例精确分配为 167/33，实得 "
                        + manager.storedAt(aAbs) + "/" + manager.storedAt(cAbs));
        helper.assertTrue(manager.receiveIntoNetwork(aAbs, 1_113, false) == 1_113,
                "铜分量必须可补满至 1280 FE");
        helper.assertTrue(manager.receiveIntoNetwork(cAbs, 223, false) == 223,
                "铁分量必须可补满至 256 FE");

        helper.setBlock(bridge, iron);
        EnergyNetworkSnapshot merged = manager.snapshotAt(aAbs).orElseThrow();
        helper.assertTrue(merged.storedFe() == 256,
                "合网后缓冲必须显式裁到新容量 256 FE，实得 " + merged.storedFe());
        helper.assertTrue(merged.lastLossFe() == 1_280 && merged.totalLossFe() == 1_280,
                "合网前 1536 FE 必须记账损失 1280 FE，实得 "
                        + merged.lastLossFe() + "/" + merged.totalLossFe());
        helper.assertTrue(merged.storedFe() + merged.lastLossFe() == 1_536,
                "合网存量与损失之和必须守恒为 1536 FE");
        helper.assertTrue(merged.faults().contains(EnergyNetworkFault.BUFFER_OVERFLOW),
                "合网裁剪必须在只读快照留下 BUFFER_OVERFLOW 故障");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY,
            batch = "energy_cable_voltage", timeoutTicks = 40)
    public static void overvoltageTripsInternalSourceButThirdPartyLowWorks(GameTestHelper helper) {
        helper.assertTrue(ConductorMaterial.IRON.voltageClass() == VoltageClass.LOW
                        && ConductorMaterial.ALUMINUM.voltageClass() == VoltageClass.LOW
                        && ConductorMaterial.COPPER.voltageClass() == VoltageClass.LOW
                        && ConductorMaterial.TINNED_COPPER.voltageClass() == VoltageClass.MEDIUM
                        && ConductorMaterial.OFC_COPPER.voltageClass() == VoltageClass.MEDIUM
                        && ConductorMaterial.OFE_COPPER.voltageClass() == VoltageClass.MEDIUM
                        && ConductorMaterial.SILVER_PLATED_COPPER.voltageClass() == VoltageClass.HIGH
                        && ConductorMaterial.GOLD.voltageClass() == VoltageClass.HIGH
                        && ConductorMaterial.SILVER.voltageClass() == VoltageClass.HIGH
                        && ConductorMaterial.GRAPHENE.voltageClass() == VoltageClass.EXTREME
                        && ConductorMaterial.NBTI_SUPERCONDUCTOR.voltageClass() == VoltageClass.EXTREME
                        && ConductorMaterial.YBCO_SUPERCONDUCTOR.voltageClass() == VoltageClass.EXTREME,
                "十二级导体的四档耐压边界必须保持 T1-3/T4-6/T7-9/T10-12");

        EnergyNetworkManager manager = EnergyNetworkManager.get(helper.getLevel());
        BlockState iron = PowerRegistry.CABLES.get(ConductorMaterial.IRON).get().defaultBlockState();
        BlockPos protectedCable = new BlockPos(2, 1, 2);
        BlockPos fullBufferCable = new BlockPos(4, 1, 2);
        BlockPos thirdPartyCable = new BlockPos(6, 1, 2);
        helper.setBlock(protectedCable, iron);
        helper.setBlock(fullBufferCable, iron);
        helper.setBlock(thirdPartyCable, iron);

        VoltageCountingSource highSource = new VoltageCountingSource(VoltageClass.HIGH, 1_024);
        VoltageCountingSource fullBufferHighSource = new VoltageCountingSource(VoltageClass.HIGH, 1_024);
        CountingSink protectedSink = new CountingSink();
        CountingSource thirdPartySource = new CountingSource(1_024);
        CountingSink thirdPartySink = new CountingSink();
        manager.debugPutSyntheticEndpoint(helper.absolutePos(new BlockPos(2, 1, 1)), Direction.SOUTH, highSource);
        manager.debugPutSyntheticEndpoint(helper.absolutePos(new BlockPos(2, 1, 3)), Direction.NORTH, protectedSink);
        manager.debugPutSyntheticEndpoint(helper.absolutePos(new BlockPos(4, 1, 1)), Direction.SOUTH,
                fullBufferHighSource);
        manager.debugPutSyntheticEndpoint(helper.absolutePos(new BlockPos(6, 1, 1)), Direction.SOUTH, thirdPartySource);
        manager.debugPutSyntheticEndpoint(helper.absolutePos(new BlockPos(6, 1, 3)), Direction.NORTH, thirdPartySink);

        BlockPos protectedAbs = helper.absolutePos(protectedCable);
        BlockPos fullBufferAbs = helper.absolutePos(fullBufferCable);
        helper.assertTrue(manager.receiveIntoNetwork(fullBufferAbs, 256, false) == 256,
                "满缓冲过压场景必须预置 256 FE");
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> {
                    helper.assertTrue(highSource.extracted() == 0,
                            "HIGH 自研源接 LOW 网络不得被抽取，实抽 " + highSource.extracted());
                    helper.assertTrue(highSource.overvoltageReports() > 0
                                    && highSource.lastNetworkLimit() == VoltageClass.LOW,
                            "过压必须回报 LOW 网耐压，回报次数/档位为 "
                                    + highSource.overvoltageReports() + "/" + highSource.lastNetworkLimit());
                    helper.assertTrue(protectedSink.received() == 0,
                            "过压网络不得向汇送电，实送 " + protectedSink.received());
                    EnergyNetworkSnapshot snapshot = manager.snapshotAt(protectedAbs).orElseThrow();
                    helper.assertTrue(snapshot.voltageLimit() == VoltageClass.LOW
                                    && snapshot.faults().contains(EnergyNetworkFault.OVER_VOLTAGE),
                            "LOW 网络快照必须报告 OVER_VOLTAGE");
                    EnergyNetworkSnapshot fullBufferSnapshot = manager.snapshotAt(fullBufferAbs).orElseThrow();
                    helper.assertTrue(fullBufferHighSource.extracted() == 0
                                    && fullBufferHighSource.overvoltageReports() > 0,
                            "满缓冲时 HIGH 自研源仍须被拒绝并收到过压回报，实得抽取/回报 "
                                    + fullBufferHighSource.extracted() + "/"
                                    + fullBufferHighSource.overvoltageReports());
                    helper.assertTrue(fullBufferSnapshot.storedFe() == 256
                                    && fullBufferSnapshot.faults().contains(EnergyNetworkFault.OVER_VOLTAGE),
                            "满缓冲 LOW 网络必须保留 256 FE 并在快照报告过压");
                    helper.assertTrue(thirdPartySource.extracted() > 0 && thirdPartySink.received() > 0,
                            "普通 IEnergyStorage 必须按 LOW 兼容并完成传输，实得 "
                                    + thirdPartySource.extracted() + "/" + thirdPartySink.received());
                })
                .thenExecute(manager::debugClearSyntheticEndpoints)
                .thenSucceed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY,
            batch = "energy_cable_idle_scheduler", timeoutTicks = 40)
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
        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> helper.assertTrue(!manager.debugNetworkActiveAt(firstAbs),
                        "无端点且环境温度的稳态网络必须退出活跃调度集合"))
                .thenSucceed();
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

    private static BooleanProperty connectionProperty(Direction direction) {
        return switch (direction) {
            case DOWN -> EnergyCableBlock.DOWN;
            case UP -> EnergyCableBlock.UP;
            case NORTH -> EnergyCableBlock.NORTH;
            case SOUTH -> EnergyCableBlock.SOUTH;
            case WEST -> EnergyCableBlock.WEST;
            case EAST -> EnergyCableBlock.EAST;
        };
    }

    private static void assertShapeBounds(GameTestHelper helper, VoxelShape shape,
                                          double minX, double maxX, double minY, double maxY,
                                          double minZ, double maxZ, String label) {
        helper.assertTrue(!shape.isEmpty()
                        && shape.min(Direction.Axis.X) == minX && shape.max(Direction.Axis.X) == maxX
                        && shape.min(Direction.Axis.Y) == minY && shape.max(Direction.Axis.Y) == maxY
                        && shape.min(Direction.Axis.Z) == minZ && shape.max(Direction.Axis.Z) == maxZ,
                label + " 边界必须为 x=" + minX + ".." + maxX
                        + " y=" + minY + ".." + maxY + " z=" + minZ + ".." + maxZ
                        + "，实为 x=" + shape.min(Direction.Axis.X) + ".." + shape.max(Direction.Axis.X)
                        + " y=" + shape.min(Direction.Axis.Y) + ".." + shape.max(Direction.Axis.Y)
                        + " z=" + shape.min(Direction.Axis.Z) + ".." + shape.max(Direction.Axis.Z));
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

    private static class CountingSource implements IEnergyStorage {
        private int energy;
        private int extracted;

        private CountingSource(int energy) {
            this.energy = energy;
        }

        int extracted() {
            return extracted;
        }

        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            return 0;
        }

        @Override
        public int extractEnergy(int maxExtract, boolean simulate) {
            int extractedNow = Math.min(maxExtract, energy);
            if (!simulate) {
                energy -= extractedNow;
                extracted += extractedNow;
            }
            return extractedNow;
        }

        @Override
        public int getEnergyStored() {
            return energy;
        }

        @Override
        public int getMaxEnergyStored() {
            return energy + extracted;
        }

        @Override
        public boolean canExtract() {
            return true;
        }

        @Override
        public boolean canReceive() {
            return false;
        }
    }

    private static final class VoltageCountingSource extends CountingSource implements VoltageAwareEnergyStorage {
        private final VoltageClass voltage;
        private int overvoltageReports;
        private VoltageClass lastNetworkLimit;

        private VoltageCountingSource(VoltageClass voltage, int energy) {
            super(energy);
            this.voltage = voltage;
        }

        int overvoltageReports() {
            return overvoltageReports;
        }

        VoltageClass lastNetworkLimit() {
            return lastNetworkLimit;
        }

        @Override
        public VoltageClass outputVoltage() {
            return voltage;
        }

        @Override
        public void reportOvervoltage(VoltageClass networkLimit) {
            overvoltageReports++;
            lastNetworkLimit = networkLimit;
        }
    }

    private static final class CountingSink implements IEnergyStorage {
        private int received;

        int received() {
            return received;
        }

        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
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
            return received;
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
    }
}
