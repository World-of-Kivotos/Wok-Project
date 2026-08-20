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
    private static final String P3_PROFILE_BATCH = "power_endgame_profile";
    private static final String P3_NBTI_BATCH = "power_endgame_nbti";
    private static final String P3_DISTANCE_BATCH = "power_endgame_distance";

    private EnergyCableGameTests() {
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void registeredMaterialsPreserveAllTwelveTiersAndExistingIds(GameTestHelper helper) {
        ConductorMaterial[] p1 = {
                ConductorMaterial.IRON,
                ConductorMaterial.ALUMINUM,
                ConductorMaterial.COPPER
        };
        helper.assertTrue(PowerRegistry.P1_MATERIALS.size() == 3
                        && PowerRegistry.REGISTERED_MATERIALS.size() == 12
                        && PowerRegistry.CABLES.size() == 12
                        && PowerRegistry.CABLE_ITEMS.size() == 12
                        && PowerRegistry.WIRE_ITEMS.size() == 12
                        && PowerRegistry.REGISTERED_MATERIALS.containsAll(java.util.List.of(
                                ConductorMaterial.IRON, ConductorMaterial.ALUMINUM, ConductorMaterial.COPPER,
                                ConductorMaterial.TINNED_COPPER, ConductorMaterial.OFC_COPPER,
                                ConductorMaterial.OFE_COPPER, ConductorMaterial.SILVER_PLATED_COPPER,
                                ConductorMaterial.GOLD, ConductorMaterial.SILVER))
                        && PowerRegistry.REGISTERED_MATERIALS.containsAll(java.util.List.of(
                                ConductorMaterial.GRAPHENE, ConductorMaterial.NBTI_SUPERCONDUCTOR,
                                ConductorMaterial.YBCO_SUPERCONDUCTOR))
                        && PowerRegistry.CABLES.containsKey(ConductorMaterial.GRAPHENE)
                        && PowerRegistry.CABLES.containsKey(ConductorMaterial.NBTI_SUPERCONDUCTOR)
                        && PowerRegistry.CABLES.containsKey(ConductorMaterial.YBCO_SUPERCONDUCTOR),
                "注册集合必须恰为 T1-T12 且保留 P1 兼容集合，实得 "
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

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = P3_PROFILE_BATCH)
    public static void p3ProfilesAndTungstenSpecialCableKeepExactContracts(GameTestHelper helper) {
        helper.assertTrue(ConductorMaterial.values().length == 12,
                "ConductorMaterial 必须恰好保留十二级，实得 " + ConductorMaterial.values().length);
        helper.assertTrue(ConductorMaterial.GRAPHENE.ratedCapacityFe() == 8_192
                        && ConductorMaterial.NBTI_SUPERCONDUCTOR.ratedCapacityFe() == 16_384
                        && ConductorMaterial.YBCO_SUPERCONDUCTOR.ratedCapacityFe() == 32_768,
                "P3 三档额定容量必须为 8192/16384/32768 FE/t");
        helper.assertTrue(ConductorMaterial.GRAPHENE.voltageClass() == VoltageClass.EXTREME
                        && ConductorMaterial.NBTI_SUPERCONDUCTOR.voltageClass() == VoltageClass.EXTREME
                        && ConductorMaterial.YBCO_SUPERCONDUCTOR.voltageClass() == VoltageClass.EXTREME
                        && ConductorMaterial.GRAPHENE.thermalMode() == CableProfile.ThermalMode.GRAPHENE
                        && ConductorMaterial.NBTI_SUPERCONDUCTOR.thermalMode() == CableProfile.ThermalMode.NBTI
                        && ConductorMaterial.YBCO_SUPERCONDUCTOR.thermalMode() == CableProfile.ThermalMode.YBCO,
                "P3 三档必须使用 EXTREME 耐压和对应热学模式");
        helper.assertTrue(!PowerRegistry.REGISTERED_MATERIALS.contains(SpecialCableMaterial.TUNGSTEN)
                        && SpecialCableMaterial.TUNGSTEN.ratedCapacityFe() == 1_536
                        && SpecialCableMaterial.TUNGSTEN.voltageClass() == VoltageClass.EXTREME
                        && SpecialCableMaterial.TUNGSTEN.maxContinuousTemperatureC() == 300
                        && Double.compare(SpecialCableMaterial.TUNGSTEN.degradeFloor(), 0.85D) == 0
                        && PowerRegistry.TUNGSTEN_HEAT_RESISTANT_CABLE.get().material()
                        == SpecialCableMaterial.TUNGSTEN,
                "钨耐热线必须独立于十二级，容量/耐压/耐温/floor 和统一网络剖面必须精确");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = P3_NBTI_BATCH)
    public static void nbtiCoolingHasSixtyFourSegmentBoundaryAndSecondControllerRestoresIt(
            GameTestHelper helper) {
        EnergyNetworkManager manager = EnergyNetworkManager.get(helper.getLevel());
        BlockPos first = helper.absolutePos(new BlockPos(2, 2_001, 2));
        BlockPos controllerA = helper.absolutePos(new BlockPos(2, 2_003, 2));
        BlockPos controllerB = helper.absolutePos(new BlockPos(2, 2_003, 4));
        for (int i = 0; i < 64; i++) {
            manager.addCable(first.east(i), ConductorMaterial.NBTI_SUPERCONDUCTOR);
        }
        manager.updateCoolingController(controllerA, first, 64);
        EnergyNetworkSnapshot active = manager.snapshotAt(first).orElseThrow();
        helper.assertTrue(active.coolingState() == EnergyNetworkSnapshot.CoolingState.ACTIVE
                        && active.effectiveCapacityFe() == 16_384
                        && !active.faults().contains(EnergyNetworkFault.SUPERCONDUCTOR_QUENCH),
                "64 段 NbTi 恰好由一台控制器覆盖时必须保持 ACTIVE 和 16384 FE/t");

        manager.addCable(first.east(64), ConductorMaterial.NBTI_SUPERCONDUCTOR);
        EnergyNetworkSnapshot insufficient = manager.snapshotAt(first).orElseThrow();
        helper.assertTrue(insufficient.coolingState() == EnergyNetworkSnapshot.CoolingState.INSUFFICIENT
                        && insufficient.effectiveCapacityFe() == 1_638
                        && insufficient.faults().contains(EnergyNetworkFault.SUPERCONDUCTOR_QUENCH),
                "新增第65段后必须失超，容量降为额定10%%并报告 SUPERCONDUCTOR_QUENCH");

        manager.updateCoolingController(controllerB, first.east(64), 64);
        EnergyNetworkSnapshot restored = manager.snapshotAt(first).orElseThrow();
        helper.assertTrue(restored.coolingState() == EnergyNetworkSnapshot.CoolingState.ACTIVE
                        && restored.effectiveCapacityFe() == 16_384
                        && manager.debugNetworkSize(first) == 65,
                "第二台控制器补足覆盖后必须恢复 ACTIVE，且65段线缆不得被销毁");

        BlockPos splitCable = first.east(32);
        manager.removeCable(splitCable);
        EnergyNetworkSnapshot leftSplit = manager.snapshotAt(first).orElseThrow();
        EnergyNetworkSnapshot rightSplit = manager.snapshotAt(first.east(64)).orElseThrow();
        helper.assertTrue(leftSplit.coolingState() == EnergyNetworkSnapshot.CoolingState.ACTIVE
                        && rightSplit.coolingState() == EnergyNetworkSnapshot.CoolingState.ACTIVE
                        && manager.debugNetworkSize(first) == 32
                        && manager.debugNetworkSize(first.east(64)) == 32,
                "拆网后两台控制器必须分别覆盖32段分量且都保持 ACTIVE");
        manager.addCable(splitCable, ConductorMaterial.NBTI_SUPERCONDUCTOR);
        EnergyNetworkSnapshot remerged = manager.snapshotAt(first).orElseThrow();
        helper.assertTrue(remerged.coolingState() == EnergyNetworkSnapshot.CoolingState.ACTIVE
                        && remerged.effectiveCapacityFe() == 16_384
                        && manager.debugNetworkSize(first) == 65,
                "重新并网后两台控制器覆盖必须重新汇总并保持65段 ACTIVE");
        manager.removeCoolingController(controllerA);
        manager.removeCoolingController(controllerB);
        for (int i = 0; i < 65; i++) {
            manager.removeCable(first.east(i));
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = P3_DISTANCE_BATCH)
    public static void p3DistanceResistanceAndTemperatureContractsAreExact(GameTestHelper helper) {
        helper.assertTrue(CableThermics.grapheneResistanceMultiplier(20.0D) == 1.0D
                        && CableThermics.grapheneResistanceMultiplier(180.0D) == 0.5D,
                "石墨烯线路电阻倍率必须从20C的1.0线性降至180C的0.5");
        int warmGross = 100_000;
        int warmNet = CableThermics.netAfterDistanceLoss(warmGross, 80);
        int hotNet = CableThermics.netAfterDistanceLoss(warmGross, 40);
        helper.assertTrue(warmNet == 99_992 && hotNet == 99_996 && hotNet > warmNet,
                "石墨烯升温后线路电阻下降必须降低精确距离损耗，实得 " + warmNet + "/" + hotNet);
        int target = 10_000;
        int gross = CableThermics.grossForDelivered(target, 80);
        helper.assertTrue(gross == 10_001
                        && CableThermics.netAfterDistanceLoss(gross, 80) == target
                        && CableThermics.netAfterDistanceLoss(10_000, 1) == 9_999,
                "距离损耗反解必须满足送达守恒，YBCO单单位线路损耗应近零");

        EnergyNetworkManager manager = EnergyNetworkManager.get(helper.getLevel());
        BlockPos grapheneFirst = helper.absolutePos(new BlockPos(2, 2_001, 2));
        BlockPos grapheneSource = grapheneFirst.west();
        BlockPos grapheneSink = grapheneFirst.east(20);
        for (int index = 0; index < 20; index++) {
            manager.addCable(grapheneFirst.east(index), ConductorMaterial.GRAPHENE);
        }
        manager.debugPutSyntheticEndpoint(grapheneSource, infiniteSource());
        manager.debugPutSyntheticEndpoint(grapheneSink, infiniteSink());

        helper.startSequence()
                .thenIdle(1)
                .thenExecute(() -> {
                    EnergyNetworkSnapshot ambient = manager.snapshotAt(grapheneFirst).orElseThrow();
                    helper.assertTrue(ambient.temperatureC() > CableThermics.AMBIENT_C
                                    && ambient.lastDistanceLossFe() == 15
                                    && ambient.totalDistanceLossFe() == 15
                                    && ambient.effectiveCapacityFe() == 8_192,
                            "20段石墨烯网络环境首结算必须实际记账15 FE损耗且容量仍为8192，实得温度/损耗/容量 "
                                    + ambient.temperatureC() + "/" + ambient.lastDistanceLossFe() + "/"
                                    + ambient.effectiveCapacityFe());
                })
                .thenIdle(40)
                .thenExecute(() -> {
                    EnergyNetworkSnapshot hot = manager.snapshotAt(grapheneFirst).orElseThrow();
                    helper.assertTrue(hot.temperatureC() >= 180.0D
                                    && hot.lastDistanceLossFe() == 8
                                    && hot.lastDistanceLossFe() < 15
                                    && hot.effectiveCapacityFe() == 8_192,
                            "石墨烯网温达到180C后实际距离损耗必须降至8 FE且容量不升额定，实得温度/损耗/容量 "
                                    + hot.temperatureC() + "/" + hot.lastDistanceLossFe() + "/"
                                    + hot.effectiveCapacityFe());
                    manager.debugClearSyntheticEndpoints();
                    BlockPos ybcoFirst = helper.absolutePos(new BlockPos(2, 2_001, 10));
                    for (int index = 0; index < 20; index++) {
                        manager.addCable(ybcoFirst.east(index), ConductorMaterial.YBCO_SUPERCONDUCTOR);
                    }
                    manager.debugPutSyntheticEndpoint(ybcoFirst.west(), infiniteSource());
                    manager.debugPutSyntheticEndpoint(ybcoFirst.east(20), infiniteSink());
                })
                .thenIdle(1)
                .thenExecute(() -> {
                    BlockPos ybcoFirst = helper.absolutePos(new BlockPos(2, 2_001, 10));
                    EnergyNetworkSnapshot ybcoSnapshot = manager.snapshotAt(ybcoFirst).orElseThrow();
                    helper.assertTrue(ybcoSnapshot.coolingState() == EnergyNetworkSnapshot.CoolingState.NOT_REQUIRED
                                    && ybcoSnapshot.effectiveCapacityFe() == 32_768
                                    && ybcoSnapshot.lastDistanceLossFe() == 2
                                    && ybcoSnapshot.totalDistanceLossFe() == 2,
                            "20段YBCO无需低温控制且实际距离损耗必须近零为2 FE，实得状态/容量/损耗 "
                                    + ybcoSnapshot.coolingState() + "/" + ybcoSnapshot.effectiveCapacityFe() + "/"
                                    + ybcoSnapshot.lastDistanceLossFe() + "/" + ybcoSnapshot.totalDistanceLossFe());
                    manager.debugClearSyntheticEndpoints();
                    for (int index = 0; index < 20; index++) {
                        manager.removeCable(grapheneFirst.east(index));
                        manager.removeCable(ybcoFirst.east(index));
                    }
                })
                .thenSucceed();
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
                        && ConductorMaterial.GOLD.ratedCapacityFe() == 3_200
                        && ConductorMaterial.SILVER.ratedCapacityFe() == 5_120,
                "P2 六档导体容量必须固定为 1536/2048/3072/4096/3200/5120 FE/t");
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
    public static void splitDistributesAndMergeConservesEnergy(GameTestHelper helper) {
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
        helper.assertTrue(merged.storedFe() == 1_536 && merged.bufferCapacityFe() == 256,
                "合网必须把 1280+256 原样保留为 1536 FE 并把木桶容量取到 256，实得 "
                        + merged.storedFe() + "/" + merged.bufferCapacityFe());
        helper.assertTrue(merged.bufferOverflowFe() == 1_280 && merged.totalBufferOverflowFe() == 1_280,
                "超出木桶容量的 1280 FE 必须记为待消化超额而不是损耗，实得 "
                        + merged.bufferOverflowFe() + "/" + merged.totalBufferOverflowFe());
        helper.assertTrue(merged.faults().contains(EnergyNetworkFault.BUFFER_OVERFLOW),
                "合网超额必须在只读快照留下 BUFFER_OVERFLOW 故障");
        helper.assertTrue(manager.receiveIntoNetwork(aAbs, 512, false) == 0
                        && manager.receiveIntoNetwork(aAbs, 512, true) == 0
                        && manager.storedAt(aAbs) == 1_536,
                "超额期间推式注入必须安全返回 0 且不动存量，实得 " + manager.storedAt(aAbs));

        // 展示夹紧与内部真账是两条独立契约, 必须同时钉死: 第三方 mod 按 Forge 的事实约定用
        // getMaxEnergyStored() - getEnergyStored() 算可注入余量, 不夹紧就会拿到 256-1536 = -1280。
        IEnergyStorage overflowedView = helper.getLevel().getBlockEntity(aAbs)
                .getCapability(ForgeCapabilities.ENERGY).resolve().orElseThrow();
        helper.assertTrue(overflowedView.getEnergyStored() == 256 && overflowedView.getMaxEnergyStored() == 256,
                "超额期间线缆对外读数必须夹在木桶容量 256 内(否则第三方算余量得 -1280)，实得 "
                        + overflowedView.getEnergyStored() + "/" + overflowedView.getMaxEnergyStored());
        helper.assertTrue(manager.storedAt(aAbs) == 1_536,
                "展示层夹紧绝不得回写内部真账, 内部存量必须仍是 1536，实得 " + manager.storedAt(aAbs));

        // 超额尚未消化时拆掉铜段: 拆网既不得因"存量高于剩余容量"抛异常, 也不得顺手把这 1536 FE 抹掉。
        helper.setBlock(a, Blocks.AIR);
        helper.assertTrue(manager.storedAt(cAbs) == 1_536 && manager.debugNetworkSize(cAbs) == 2,
                "超额网拆掉铜段后 1536 FE 必须全额留在剩余 2 根铁缆上，实得 "
                        + manager.storedAt(cAbs) + "/" + manager.debugNetworkSize(cAbs));
        helper.succeed();
    }

    /**
     * 真机故障复现: 两张各自打满的同级网被一根线缆桥接时, 存量必须逐 FE 守恒。
     * 删掉"合网不再裁剪"这条修复, 存量会被摁回 256 并把 256 FE 记成损耗, 本用例必挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void mergingTwoFullNetworksKeepsEveryStoredFe(GameTestHelper helper) {
        EnergyNetworkManager manager = EnergyNetworkManager.get(helper.getLevel());
        BlockState iron = PowerRegistry.CABLES.get(ConductorMaterial.IRON).get().defaultBlockState();
        BlockPos left = new BlockPos(2, 1, 1);
        BlockPos bridge = new BlockPos(2, 1, 2);
        BlockPos right = new BlockPos(2, 1, 3);
        BlockPos leftAbs = helper.absolutePos(left);
        BlockPos rightAbs = helper.absolutePos(right);
        helper.setBlock(left, iron);
        helper.setBlock(right, iron);
        helper.assertTrue(manager.receiveIntoNetwork(leftAbs, 256, false) == 256
                        && manager.receiveIntoNetwork(rightAbs, 256, false) == 256,
                "两张铁网必须各自先打满 256 FE");

        helper.setBlock(bridge, iron);
        EnergyNetworkSnapshot merged = manager.snapshotAt(leftAbs).orElseThrow();
        helper.assertTrue(merged.storedFe() == 512,
                "两张满缓冲网合并后存量必须精确守恒为 512 FE，实得 " + merged.storedFe());
        helper.assertTrue(merged.bufferCapacityFe() == 256 && merged.bufferOverflowFe() == 256,
                "木桶容量仍为 256 FE 且超额量必须精确为 256 FE，实得 "
                        + merged.bufferCapacityFe() + "/" + merged.bufferOverflowFe());
        helper.assertTrue(merged.totalBufferOverflowFe() == 256
                        && merged.lastDistanceLossFe() == 0
                        && merged.totalDistanceLossFe() == 0,
                "合网只记一次 256 FE 超额事件，且绝不得把它算进任何损耗账，实得超额/距离损耗 "
                        + merged.totalBufferOverflowFe() + "/" + merged.totalDistanceLossFe());
        helper.assertTrue(manager.storedAt(rightAbs) == 512 && manager.debugNetworkSize(leftAbs) == 3,
                "三根铁缆必须同属一网并共享这 512 FE，实得 "
                        + manager.storedAt(rightAbs) + "/" + manager.debugNetworkSize(leftAbs));
        helper.succeed();
    }

    /**
     * 最后一根线缆被拆掉时瞬态缓冲无处承接, 随导体一起消失是设计内行为, 但必须记账 + 告警, 绝不静默 return。
     * 删掉 discardLastCableBuffer 这 1536 FE 会凭空蒸发而累计弃电量恒为 0, 本用例必挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = "energy_cable_discard")
    public static void removingLastCableAccountsForDiscardedBuffer(GameTestHelper helper) {
        clearCableWorkspace(helper);
        EnergyNetworkManager manager = EnergyNetworkManager.get(helper.getLevel());
        BlockState copper = PowerRegistry.CABLES.get(ConductorMaterial.COPPER).get().defaultBlockState();
        BlockState iron = PowerRegistry.CABLES.get(ConductorMaterial.IRON).get().defaultBlockState();
        BlockPos a = new BlockPos(2, 1, 1);
        BlockPos bridge = new BlockPos(2, 1, 2);
        BlockPos c = new BlockPos(2, 1, 3);
        BlockPos aAbs = helper.absolutePos(a);
        BlockPos cAbs = helper.absolutePos(c);
        // GameTest 复用存档, 累计弃电量跨轮不清零, 只能断言本轮增量 (清场本身也可能产生弃电, 故基线在清场之后取)。
        long baseline = manager.discardedBufferFe();

        helper.setBlock(a, copper);
        helper.setBlock(c, iron);
        helper.assertTrue(manager.receiveIntoNetwork(aAbs, 1_280, false) == 1_280
                        && manager.receiveIntoNetwork(cAbs, 256, false) == 256,
                "铜网与铁网必须各自先打满 1280 / 256 FE");
        helper.setBlock(bridge, iron);
        helper.assertTrue(manager.storedAt(aAbs) == 1_536,
                "合网瞬间必须完整保留 1536 FE，实得 " + manager.storedAt(aAbs));

        // 拆到只剩一根: 中途每一步都由 reindexComponents 按容量比例整体搬走, 一分都不该算作弃电。
        helper.setBlock(a, Blocks.AIR);
        helper.setBlock(bridge, Blocks.AIR);
        helper.assertTrue(manager.storedAt(cAbs) == 1_536,
                "拆到最后一根之前 1536 FE 必须全额跟着幸存分量走，实得 " + manager.storedAt(cAbs));
        helper.assertTrue(manager.discardedBufferFe() - baseline == 0L,
                "还有幸存线缆时绝不得记任何弃电，实得 " + (manager.discardedBufferFe() - baseline));

        helper.setBlock(c, Blocks.AIR);
        helper.assertTrue(manager.debugNetworkSize(cAbs) == 0,
                "最后一根拆掉后该坐标不得再属于任何网");
        helper.assertTrue(manager.discardedBufferFe() - baseline == 1_536L,
                "随最后一根线缆消失的 1536 FE 必须精确记进弃电账，实得 "
                        + (manager.discardedBufferFe() - baseline));
        helper.succeed();
    }

    /**
     * 累计超额账只记"本次拓扑变更新产生"的那一笔。往一张仍在超编的网上再放线缆(真机上区块重载时每根线缆
     * 各走一次 addCable)必须一分都不再计, 否则同一笔 FE 会被反复累加到 Jade 的"累计超额"上。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = "energy_cable_overflow_once")
    public static void extendingAnOverflowingNetworkCountsOverflowOnlyOnce(GameTestHelper helper) {
        clearCableWorkspace(helper);
        EnergyNetworkManager manager = EnergyNetworkManager.get(helper.getLevel());
        BlockState iron = PowerRegistry.CABLES.get(ConductorMaterial.IRON).get().defaultBlockState();
        BlockPos left = new BlockPos(2, 1, 1);
        BlockPos bridge = new BlockPos(2, 1, 2);
        BlockPos right = new BlockPos(2, 1, 3);
        BlockPos extension = new BlockPos(3, 1, 1);
        BlockPos secondExtension = new BlockPos(4, 1, 1);
        BlockPos leftAbs = helper.absolutePos(left);
        helper.setBlock(left, iron);
        helper.setBlock(right, iron);
        helper.assertTrue(manager.receiveIntoNetwork(leftAbs, 256, false) == 256
                        && manager.receiveIntoNetwork(helper.absolutePos(right), 256, false) == 256,
                "两张铁网必须各自先打满 256 FE");

        helper.setBlock(bridge, iron);
        EnergyNetworkSnapshot merged = manager.snapshotAt(leftAbs).orElseThrow();
        helper.assertTrue(merged.storedFe() == 512 && merged.bufferOverflowFe() == 256
                        && merged.totalBufferOverflowFe() == 256,
                "合网必须守恒 512 FE 并记一次 256 FE 超额，实得 " + merged.storedFe() + "/"
                        + merged.bufferOverflowFe() + "/" + merged.totalBufferOverflowFe());

        helper.setBlock(extension, iron);
        EnergyNetworkSnapshot afterFirst = manager.snapshotAt(leftAbs).orElseThrow();
        helper.assertTrue(afterFirst.storedFe() == 512 && afterFirst.bufferOverflowFe() == 256,
                "扩容不得改变存量与当期超额，实得 " + afterFirst.storedFe() + "/" + afterFirst.bufferOverflowFe());
        helper.assertTrue(afterFirst.totalBufferOverflowFe() == 256,
                "往超编网上再放一根线缆必须一分都不再计入累计超额, 实得 "
                        + afterFirst.totalBufferOverflowFe() + " (重复计会得到 512)");

        helper.setBlock(secondExtension, iron);
        EnergyNetworkSnapshot afterSecond = manager.snapshotAt(leftAbs).orElseThrow();
        helper.assertTrue(afterSecond.totalBufferOverflowFe() == 256,
                "第二次扩容同样不得再计, 实得 " + afterSecond.totalBufferOverflowFe() + " (重复计会得到 768)");
        helper.assertTrue(afterSecond.faults().contains(EnergyNetworkFault.BUFFER_OVERFLOW),
                "仍在超编期间 BUFFER_OVERFLOW 故障必须继续挂着, 与是不是新事件无关");
        helper.assertTrue(afterSecond.storedFe() == 512 && manager.debugNetworkSize(leftAbs) == 5,
                "五根铁缆必须同属一网并共享这 512 FE，实得 " + afterSecond.storedFe() + "/"
                        + manager.debugNetworkSize(leftAbs));
        helper.succeed();
    }

    /**
     * GameTest 的 empty 模板只有 1x1x1, 结构外的方块跨轮留在原地。同名用例第二轮跑时, 对着一根已经存在
     * 且状态相同的线缆调 setBlock 是空操作 (原版 LevelChunk 直接短路), 于是上一轮的并网关系会原样带进来,
     * 把"两张独立网各自打满"这类前置断言整体带偏。故新用例先把工作区清干净。
     */
    private static void clearCableWorkspace(GameTestHelper helper) {
        // 从 x=1/z=1 起清: 相对坐标 (0,0,0) 是结构方块本体, 清掉它 GameTest 收尾取不到结构边界会直接炸。
        for (int x = 1; x <= 5; x++) {
            for (int y = 0; y <= 2; y++) {
                for (int z = 1; z <= 4; z++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
    }

    /**
     * 合网超额必须由推阶段在随后若干 settlement 内消化干净, 且全程守恒: 送达量 + 距离损耗 + 余量 = 合网前总量。
     * 该用例同时守住"消化完故障标记自行摘除", 防止玩家看到一条永远擦不掉的红字。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY,
            batch = "energy_cable_overflow_drain", timeoutTicks = 80)
    public static void mergeOverflowDrainsThroughConsumerWithoutLosingFe(GameTestHelper helper) {
        EnergyNetworkManager manager = EnergyNetworkManager.get(helper.getLevel());
        BlockState copper = PowerRegistry.CABLES.get(ConductorMaterial.COPPER).get().defaultBlockState();
        BlockState iron = PowerRegistry.CABLES.get(ConductorMaterial.IRON).get().defaultBlockState();
        BlockPos a = new BlockPos(2, 1, 1);
        BlockPos bridge = new BlockPos(2, 1, 2);
        BlockPos c = new BlockPos(2, 1, 3);
        BlockPos aAbs = helper.absolutePos(a);
        helper.setBlock(a, copper);
        helper.setBlock(c, iron);
        helper.assertTrue(manager.receiveIntoNetwork(aAbs, 1_280, false) == 1_280
                        && manager.receiveIntoNetwork(helper.absolutePos(c), 256, false) == 256,
                "铜网与铁网必须各自先打满 1280 / 256 FE");

        helper.setBlock(bridge, iron);
        CountingSink sink = new CountingSink();
        manager.debugPutSyntheticEndpoint(helper.absolutePos(new BlockPos(2, 1, 4)), Direction.NORTH, sink);
        helper.assertTrue(manager.storedAt(aAbs) == 1_536,
                "合网瞬间必须完整保留 1536 FE，实得 " + manager.storedAt(aAbs));

        helper.startSequence()
                .thenIdle(1)
                .thenExecute(() -> {
                    int stored = manager.storedAt(aAbs);
                    helper.assertTrue(stored > 256 && stored < 1_536,
                            "超额必须逐 settlement 缓慢消化(仍高于 256 且已低于 1536)，实得 " + stored);
                })
                .thenIdle(20)
                .thenExecute(() -> {
                    EnergyNetworkSnapshot drained = manager.snapshotAt(aAbs).orElseThrow();
                    helper.assertTrue(drained.storedFe() <= drained.bufferCapacityFe(),
                            "超额必须在 21 tick 内回落到 256 FE 缓冲以内，实得存量/容量 "
                                    + drained.storedFe() + "/" + drained.bufferCapacityFe());
                    helper.assertTrue(sink.received() + drained.totalDistanceLossFe() + drained.storedFe() == 1_536,
                            "送达量+距离损耗+余量必须精确守恒为 1536 FE，实得 "
                                    + sink.received() + "+" + drained.totalDistanceLossFe() + "+"
                                    + drained.storedFe());
                    helper.assertTrue(sink.received() >= 1_280,
                            "至少超额的那 1280 FE 必须真的送到消费端，实送 " + sink.received());
                    helper.assertTrue(drained.totalBufferOverflowFe() == 1_280
                                    && !drained.faults().contains(EnergyNetworkFault.BUFFER_OVERFLOW),
                            "消化完累计超额必须停在 1280 FE 且故障标记自行摘除，实得 "
                                    + drained.totalBufferOverflowFe() + "/" + drained.faults());
                })
                .thenExecute(manager::debugClearSyntheticEndpoints)
                .thenSucceed();
    }

    /**
     * 超额期间拉阶段必须整体跳过: 网上的电还没送完就一分都不再从发电端拉。对照组是同期未超额的等价网络,
     * 它必须照常拉满 —— 没有对照组的话, 本用例会退化成"结算根本没跑"也能通过的空断言。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY,
            batch = "energy_cable_overflow_pull", timeoutTicks = 60)
    public static void overflowingNetworkStopsPullingFromProducers(GameTestHelper helper) {
        EnergyNetworkManager manager = EnergyNetworkManager.get(helper.getLevel());
        BlockState iron = PowerRegistry.CABLES.get(ConductorMaterial.IRON).get().defaultBlockState();
        BlockPos left = new BlockPos(2, 1, 1);
        BlockPos bridge = new BlockPos(2, 1, 2);
        BlockPos right = new BlockPos(2, 1, 3);
        BlockPos control = new BlockPos(6, 1, 1);
        BlockPos leftAbs = helper.absolutePos(left);
        helper.setBlock(left, iron);
        helper.setBlock(right, iron);
        helper.setBlock(control, iron);
        helper.assertTrue(manager.receiveIntoNetwork(leftAbs, 256, false) == 256
                        && manager.receiveIntoNetwork(helper.absolutePos(right), 256, false) == 256,
                "两张铁网必须各自先打满 256 FE");
        helper.setBlock(bridge, iron);

        CountingSource overflowSource = new CountingSource(256);
        CountingSource controlSource = new CountingSource(256);
        manager.debugPutSyntheticEndpoint(helper.absolutePos(new BlockPos(2, 1, 0)), Direction.SOUTH,
                overflowSource);
        manager.debugPutSyntheticEndpoint(helper.absolutePos(new BlockPos(6, 1, 0)), Direction.SOUTH,
                controlSource);

        helper.startSequence()
                .thenIdle(3)
                .thenExecute(() -> {
                    helper.assertTrue(manager.debugEndpointCountAt(leftAbs) == 1,
                            "超额网必须真的被结算过并缓存到 1 个端点，实得 "
                                    + manager.debugEndpointCountAt(leftAbs));
                    helper.assertTrue(overflowSource.extractCalls() == 0 && overflowSource.extracted() == 0,
                            "超额期间生产端不得被调用或被取走一分 FE，实得调用次数/取电量 "
                                    + overflowSource.extractCalls() + "/" + overflowSource.extracted());
                    helper.assertTrue(manager.storedAt(leftAbs) == 512,
                            "无消费端时超额存量必须原样保留 512 FE，实得 " + manager.storedAt(leftAbs));
                    helper.assertTrue(controlSource.extracted() == 256,
                            "同期未超额的对照网必须把整段 256 FE 拉入缓冲，实得 " + controlSource.extracted());
                })
                .thenExecute(manager::debugClearSyntheticEndpoints)
                .thenSucceed();
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
        private int extractCalls;

        private CountingSource(int energy) {
            this.energy = energy;
        }

        int extracted() {
            return extracted;
        }

        /** 含 simulate 在内的抽取调用次数; 用于断言超额期间生产端被完全跳过而不只是抽到 0。 */
        int extractCalls() {
            return extractCalls;
        }

        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            return 0;
        }

        @Override
        public int extractEnergy(int maxExtract, boolean simulate) {
            extractCalls++;
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
