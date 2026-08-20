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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * 储电"松散聚合"多方块的运行契约。
 *
 * 最关键的一条是 groupOnSharedNetworkKeepsOneRatePerTick: 组速率按成员线性叠加之后，若每块储电各自
 * 拿着完整的组速率去参与电网结算，一组贴网的块数就直接把速率乘一遍——两块贴网等于凭空多出一份组速率
 * 的进电能力，是一个纯粹的刷电漏洞。该用例用"三块成组、只有两块贴网"的布局把三种实现区分开：
 * 每块各自限速 = 1536，每面各享一份组速率 = 4608，只有正确实现恰好等于一份组速率 2304。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class PowerCellGroupGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "power_cell_group";

    private static final BlockPos CELL_A = new BlockPos(2, 2, 2);
    private static final BlockPos CELL_B = new BlockPos(3, 2, 2);
    private static final BlockPos CELL_C = new BlockPos(4, 2, 2);
    private static final BlockPos CELL_D = new BlockPos(5, 2, 2);
    /** 与 CELL_A..D 那一行都不相邻的坐标, 用于验证"放回去的储电自成一组"。 */
    private static final BlockPos ISOLATED_CELL = new BlockPos(6, 2, 2);
    private static final BlockPos CABLE_A = new BlockPos(2, 1, 2);
    private static final BlockPos CABLE_B = new BlockPos(3, 1, 2);
    /** 电源贴在 CABLE_A 西侧, 用相对坐标给出以免结构旋转时落到另一根线缆上。 */
    private static final BlockPos SOURCE_REL = new BlockPos(1, 1, 2);

    private static final long INDUSTRIAL_CAPACITY = 13_824_000L;
    private static final long INDUSTRIAL_RATE = 768L;
    private static final long MODERN_CAPACITY = 165_888_000L;
    private static final long FUTURE_CAPACITY = 884_736_000L;
    private static final long FUTURE_RATE = 12_288L;

    private PowerCellGroupGameTests() {
    }

    /** 用例1: 同档相邻并成一组、异档不并、破坏中段分裂成两组。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void adjacentSameTierMergesAndBreakingSplits(GameTestHelper helper) {
        clearWorkspace(helper);
        PowerCellGroupManager manager = PowerCellGroupManager.get(helper.getLevel());
        PowerCellBlockEntity cellA = place(helper, CELL_A, PowerRegistry.INDUSTRIAL_POWER_CELL.get());
        place(helper, CELL_B, PowerRegistry.INDUSTRIAL_POWER_CELL.get());
        PowerCellBlockEntity cellC = place(helper, CELL_C, PowerRegistry.INDUSTRIAL_POWER_CELL.get());

        helper.assertTrue(manager.groupSizeAt(helper.absolutePos(CELL_A)) == 3,
                "三块同档相邻必须并成一组, 得到 " + manager.groupSizeAt(helper.absolutePos(CELL_A)));
        helper.assertTrue(manager.sameGroup(helper.absolutePos(CELL_A), helper.absolutePos(CELL_C)),
                "首尾两块必须经中段并入同一组");
        helper.assertTrue(cellA.groupCapacityFe() == 3L * INDUSTRIAL_CAPACITY,
                "三块工业储电组容量必须是 " + (3L * INDUSTRIAL_CAPACITY) + ", 得到 " + cellA.groupCapacityFe());

        // 异档相邻绝不并组: 三档共用同一个 BlockEntityType, 只靠 instanceof 判定会并出"三档混装组"。
        PowerCellBlockEntity cellD = place(helper, CELL_D, PowerRegistry.MODERN_POWER_CELL.get());
        helper.assertTrue(!manager.sameGroup(helper.absolutePos(CELL_C), helper.absolutePos(CELL_D)),
                "现代储电不得并入工业储电组");
        helper.assertTrue(manager.groupSizeAt(helper.absolutePos(CELL_D)) == 1,
                "异档方块必须自成一组, 得到 " + manager.groupSizeAt(helper.absolutePos(CELL_D)));
        helper.assertTrue(cellD.groupCapacityFe() == MODERN_CAPACITY,
                "单块现代储电组容量必须是 " + MODERN_CAPACITY + ", 得到 " + cellD.groupCapacityFe());
        helper.assertTrue(manager.groupSizeAt(helper.absolutePos(CELL_A)) == 3,
                "异档方块入场不得改变原工业组成员数");

        // 挖掉中段 -> 一分为二, 各自重新算容量。
        helper.setBlock(CELL_B, Blocks.AIR);
        helper.assertTrue(!manager.sameGroup(helper.absolutePos(CELL_A), helper.absolutePos(CELL_C)),
                "挖掉中段后首尾必须拆成两组");
        helper.assertTrue(manager.groupSizeAt(helper.absolutePos(CELL_A)) == 1
                        && manager.groupSizeAt(helper.absolutePos(CELL_C)) == 1,
                "拆分后两组必须各剩一块, 得到 " + manager.groupSizeAt(helper.absolutePos(CELL_A))
                        + "/" + manager.groupSizeAt(helper.absolutePos(CELL_C)));
        helper.assertTrue(manager.groupSizeAt(helper.absolutePos(CELL_B)) == 0,
                "已挖掉的中段不得再属于任何组");
        helper.assertTrue(cellA.groupCapacityFe() == INDUSTRIAL_CAPACITY
                        && cellC.groupCapacityFe() == INDUSTRIAL_CAPACITY,
                "拆分后两组容量必须各自回落到单块的 " + INDUSTRIAL_CAPACITY + ", 得到 "
                        + cellA.groupCapacityFe() + "/" + cellC.groupCapacityFe());
        helper.succeed();
    }

    /** 用例2: 容量与速率按成员数线性叠加, 不设边际递减。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void capacityAndTransferStackLinearly(GameTestHelper helper) {
        clearWorkspace(helper);
        PowerCellBlockEntity cellA = place(helper, CELL_A, PowerRegistry.FUTURE_POWER_CELL.get());
        place(helper, CELL_B, PowerRegistry.FUTURE_POWER_CELL.get());

        helper.assertTrue(cellA.groupSize() == 2, "两块必须成一组, 得到 " + cellA.groupSize());
        helper.assertTrue(cellA.groupCapacityFe() == 2L * FUTURE_CAPACITY,
                "两块未来储电组容量必须精确等于 " + (2L * FUTURE_CAPACITY) + ", 得到 " + cellA.groupCapacityFe());
        helper.assertTrue(cellA.groupTransferFePerTick() == 2L * FUTURE_RATE,
                "组速率必须精确等于 " + (2L * FUTURE_RATE) + ", 得到 " + cellA.groupTransferFePerTick());
        // 该总量仍在 int 内, 因此这里必须是精确值而不是饱和值 —— 饱和只在越界时出现, 见用例4。
        helper.assertTrue(energyOf(cellA).getMaxEnergyStored() == (int) (2L * FUTURE_CAPACITY),
                "组容量未越界时对外读数必须是精确值 " + (2L * FUTURE_CAPACITY)
                        + ", 得到 " + energyOf(cellA).getMaxEnergyStored());
        helper.assertTrue(PowerCellSpec.FUTURE.runtime().transferFePerTick() == FUTURE_RATE,
                "成组不得改写单块出厂速率");
        helper.succeed();
    }

    /** 用例3(直接口): 一组对外只有一份速率额度, 第一个被服务的面吃满, 其余面同 tick 内一律 0。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void groupRateIsSharedAcrossContactFaces(GameTestHelper helper) {
        clearWorkspace(helper);
        PowerCellBlockEntity cellA = place(helper, CELL_A, PowerRegistry.INDUSTRIAL_POWER_CELL.get());
        PowerCellBlockEntity cellB = place(helper, CELL_B, PowerRegistry.INDUSTRIAL_POWER_CELL.get());
        long groupRate = 2L * INDUSTRIAL_RATE;

        int firstFace = energyOf(cellA).receiveEnergy(Integer.MAX_VALUE, false);
        int secondFace = energyOf(cellB).receiveEnergy(Integer.MAX_VALUE, false);
        helper.assertTrue(firstFace == groupRate,
                "单面必须能吃下整份组速率 " + groupRate + " FE, 得到 " + firstFace);
        helper.assertTrue(secondFace == 0,
                "同 tick 内第二个面必须一分都拿不到, 得到 " + secondFace);
        helper.assertTrue(cellA.groupStoredFe() == groupRate,
                "组余额必须精确等于一份组速率 " + groupRate + ", 得到 " + cellA.groupStoredFe());
        helper.assertTrue(cellA.storedFeLong() == INDUSTRIAL_RATE && cellB.storedFeLong() == INDUSTRIAL_RATE,
                "一次注入必须按成员均摊为 " + INDUSTRIAL_RATE + "/" + INDUSTRIAL_RATE + ", 得到 "
                        + cellA.storedFeLong() + "/" + cellB.storedFeLong());
        helper.succeed();
    }

    /**
     * 用例3(挂网): 三块成组、其中两块贴同一张线缆网, 单 tick 整组实收必须恰好一份组速率。
     *
     * 布局把三种实现分得干干净净: 每块按单块速率限速得 2 x 768 = 1536; 每个接触面各享一份组速率得
     * 2 x 2304 = 4608; 只有"整组共享一份组速率"得 2304。银缆额定 5120 FE/t, 高于 4608, 因此线缆吞吐
     * 不会替错误实现挡住超额。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH, timeoutTicks = 200)
    public static void groupOnSharedNetworkKeepsOneRatePerTick(GameTestHelper helper) {
        clearWorkspace(helper);
        EnergyNetworkManager network = EnergyNetworkManager.get(helper.getLevel());
        BlockState silver = PowerRegistry.CABLES.get(ConductorMaterial.SILVER).get().defaultBlockState();
        helper.setBlock(CABLE_A, silver);
        helper.setBlock(CABLE_B, silver);

        PowerCellBlockEntity cellA = place(helper, CELL_A, PowerRegistry.INDUSTRIAL_POWER_CELL.get());
        place(helper, CELL_B, PowerRegistry.INDUSTRIAL_POWER_CELL.get());
        place(helper, CELL_C, PowerRegistry.INDUSTRIAL_POWER_CELL.get());
        long groupRate = 3L * INDUSTRIAL_RATE;

        network.debugPutSyntheticEndpoint(helper.absolutePos(SOURCE_REL), infiniteSource());

        helper.startSequence()
                .thenIdle(20)
                .thenExecute(() -> {
                    helper.assertTrue(cellA.groupSize() == 3,
                            "三块必须成一组, 得到 " + cellA.groupSize());
                    helper.assertTrue(network.debugEndpointCountAt(helper.absolutePos(CABLE_A)) == 3,
                            "电网必须登记 1 个电源端点 + 2 个储电端点, 得到 "
                                    + network.debugEndpointCountAt(helper.absolutePos(CABLE_A)));
                    helper.assertTrue(cellA.groupLastReceivedFe() == groupRate,
                            "整组单 tick 实收必须恰好一份组速率 " + groupRate + " FE, 得到 "
                                    + cellA.groupLastReceivedFe() + " (1536=每块各自限速, 4608=每面各享一份)");
                })
                .thenExecute(network::debugClearSyntheticEndpoints)
                .thenSucceed();
    }

    /** 用例4: 组容量越过 int 上限时对外必须饱和到 Integer.MAX_VALUE, 而不是溢出成负数。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void groupCapacitySaturatesInsteadOfOverflowing(GameTestHelper helper) {
        clearWorkspace(helper);
        PowerCellBlockEntity cellA = place(helper, CELL_A, PowerRegistry.FUTURE_POWER_CELL.get());
        place(helper, CELL_B, PowerRegistry.FUTURE_POWER_CELL.get());
        place(helper, CELL_C, PowerRegistry.FUTURE_POWER_CELL.get());

        helper.assertTrue(cellA.groupSize() == 3, "三块必须成一组, 得到 " + cellA.groupSize());
        helper.assertTrue(cellA.groupCapacityFe() == 3L * FUTURE_CAPACITY,
                "long 账本必须保住精确总容量 " + (3L * FUTURE_CAPACITY) + ", 得到 " + cellA.groupCapacityFe());
        helper.assertTrue(cellA.groupCapacityFe() > Integer.MAX_VALUE,
                "本用例的前提就是总容量越过 int 上限, 得到 " + cellA.groupCapacityFe());
        helper.assertTrue(energyOf(cellA).getMaxEnergyStored() == Integer.MAX_VALUE,
                "对外 int 读数必须饱和到 " + Integer.MAX_VALUE + ", 得到 "
                        + energyOf(cellA).getMaxEnergyStored() + " (强转会得到 -1640759296)");
        helper.succeed();
    }

    /** 用例5: 余额归各块自己所有, 拆一块整组余额精确减少那块的份额。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void memberSharesSumToGroupBalanceAndLeaveWithTheBlock(GameTestHelper helper) {
        clearWorkspace(helper);
        PowerCellBlockEntity cellA = place(helper, CELL_A, PowerRegistry.INDUSTRIAL_POWER_CELL.get());
        PowerCellBlockEntity cellB = place(helper, CELL_B, PowerRegistry.INDUSTRIAL_POWER_CELL.get());
        PowerCellBlockEntity cellC = place(helper, CELL_C, PowerRegistry.INDUSTRIAL_POWER_CELL.get());
        long groupRate = 3L * INDUSTRIAL_RATE;

        int accepted = energyOf(cellA).receiveEnergy(Integer.MAX_VALUE, false);
        helper.assertTrue(accepted == groupRate,
                "一次注入必须吃满整份组速率 " + groupRate + ", 得到 " + accepted);
        helper.assertTrue(cellA.storedFeLong() == INDUSTRIAL_RATE
                        && cellB.storedFeLong() == INDUSTRIAL_RATE
                        && cellC.storedFeLong() == INDUSTRIAL_RATE,
                "三块必须各持 " + INDUSTRIAL_RATE + " FE, 得到 " + cellA.storedFeLong() + "/"
                        + cellB.storedFeLong() + "/" + cellC.storedFeLong());
        helper.assertTrue(cellA.groupStoredFe()
                        == cellA.storedFeLong() + cellB.storedFeLong() + cellC.storedFeLong(),
                "组余额必须恒等于成员份额之和, 得到 " + cellA.groupStoredFe());

        // 拆掉末端一块: 它带走自己那 768 FE, 剩余组精确减少这一份, 不做任何再分配。
        // "带走"必须由战利品表兑现: 界面读的是整组聚合值, 掉落若不带 NBT, 这层聚合会把"挖一块蒸发一份余额"
        // 完全藏在"一个大电池"的假象后面 —— 玩家只会看到总额从 2304 跳到 1536 而毫无提示。
        ServerLevel level = helper.getLevel();
        BlockPos cellCAbs = helper.absolutePos(CELL_C);
        List<ItemStack> drops = Block.getDrops(level.getBlockState(cellCAbs), level, cellCAbs, cellC);
        helper.assertTrue(drops.size() == 1
                        && drops.get(0).getItem() == PowerRegistry.INDUSTRIAL_POWER_CELL.get().asItem(),
                "破坏储电必须恰好掉落一件对应储电方块, 得到 " + drops);
        CompoundTag carried = BlockItem.getBlockEntityData(drops.get(0));
        helper.assertTrue(carried != null && carried.getLong("storedFe") == INDUSTRIAL_RATE,
                "掉落物必须带走这块自己的 " + INDUSTRIAL_RATE + " FE, 得到 " + carried);

        helper.setBlock(CELL_C, Blocks.AIR);
        helper.assertTrue(cellA.groupSize() == 2, "拆掉一块后组必须剩 2 个成员, 得到 " + cellA.groupSize());
        helper.assertTrue(cellA.groupStoredFe() == groupRate - INDUSTRIAL_RATE,
                "剩余组余额必须精确等于 " + (groupRate - INDUSTRIAL_RATE) + ", 得到 " + cellA.groupStoredFe());
        helper.assertTrue(cellA.storedFeLong() == INDUSTRIAL_RATE && cellB.storedFeLong() == INDUSTRIAL_RATE,
                "存活成员的份额必须原样不动, 得到 " + cellA.storedFeLong() + "/" + cellB.storedFeLong());
        helper.succeed();
    }

    /**
     * 余额随方块进出物品栏的完整往返: 挖下来带 NBT、放回去原样回来、空储电不带 NBT。
     *
     * 最后一条不是洁癖: copy_nbt 无条件写会让每一块挖下来的空储电都带上 BlockEntityTag 而无法与合成品
     * 堆叠, 所以 saveAdditional 在余额为 0 时刻意不写这个键。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void balanceSurvivesBreakAndReplaceThroughItemNbt(GameTestHelper helper) {
        clearWorkspace(helper);
        ServerLevel level = helper.getLevel();
        PowerCellBlockEntity cellA = place(helper, CELL_A, PowerRegistry.INDUSTRIAL_POWER_CELL.get());
        place(helper, CELL_B, PowerRegistry.INDUSTRIAL_POWER_CELL.get());
        PowerCellBlockEntity cellC = place(helper, CELL_C, PowerRegistry.INDUSTRIAL_POWER_CELL.get());
        long groupRate = 3L * INDUSTRIAL_RATE;
        helper.assertTrue(energyOf(cellA).receiveEnergy(Integer.MAX_VALUE, false) == groupRate,
                "一次注入必须吃满整份组速率 " + groupRate);

        BlockPos cellCAbs = helper.absolutePos(CELL_C);
        ItemStack dropped = Block.getDrops(level.getBlockState(cellCAbs), level, cellCAbs, cellC).get(0);
        helper.assertTrue(BlockItem.getBlockEntityData(dropped).getLong("storedFe") == INDUSTRIAL_RATE,
                "掉落物必须带走 " + INDUSTRIAL_RATE + " FE, 得到 "
                        + BlockItem.getBlockEntityData(dropped));

        // 放回一个与原组不相邻的坐标: 余额必须原样回来, 且它自成一组时读数就是这一份。
        PowerCellBlockEntity replaced = place(helper, ISOLATED_CELL, PowerRegistry.INDUSTRIAL_POWER_CELL.get());
        helper.assertTrue(replaced.storedFeLong() == 0,
                "刚放下的空储电余额必须是 0, 得到 " + replaced.storedFeLong());
        helper.assertTrue(BlockItem.updateCustomBlockEntityTag(level, null,
                        helper.absolutePos(ISOLATED_CELL), dropped),
                "原版 BlockItem 必须认下这份 BlockEntityTag");
        helper.assertTrue(replaced.storedFeLong() == INDUSTRIAL_RATE,
                "放回去的储电必须原样拿回 " + INDUSTRIAL_RATE + " FE, 得到 " + replaced.storedFeLong());
        helper.assertTrue(replaced.groupSize() == 1 && replaced.groupStoredFe() == INDUSTRIAL_RATE,
                "它与原组不相邻, 必须自成一组且组余额就是这一份, 得到 " + replaced.groupSize()
                        + "/" + replaced.groupStoredFe());

        // 抽干后再挖: 空储电的掉落物不得带任何 BlockEntityTag, 否则与合成品不堆叠。
        helper.assertTrue(energyOf(cellA).extractEnergy(Integer.MAX_VALUE, false) == groupRate,
                "抽干整组必须恰好取出 " + groupRate + " FE");
        helper.assertTrue(cellC.storedFeLong() == 0, "抽干后成员份额必须归零, 得到 " + cellC.storedFeLong());
        ItemStack emptyDrop = Block.getDrops(level.getBlockState(cellCAbs), level, cellCAbs, cellC).get(0);
        helper.assertTrue(BlockItem.getBlockEntityData(emptyDrop) == null,
                "空储电的掉落物不得带 BlockEntityTag, 得到 " + emptyDrop.getTag());
        helper.succeed();
    }

    /**
     * 分组索引里查不到本坐标时必须按"尚未接入"读, 不得抛异常。
     *
     * 这条路径挂在电网每 tick 的结算路径上(CellStorage 的四个口全经过 group()), 而 Forge 存在"方块实体
     * 已可解析、onLoad 却被推迟到下一 tick"的真实窗口。抛异常就是拿一次区块加载时序换服务端崩溃。
     * 这里用 removeCell 精确复现那个窗口: BE 与余额都还在, 只是索引里没有它。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void cellMissingFromIndexReadsAsStandaloneInsteadOfCrashing(GameTestHelper helper) {
        clearWorkspace(helper);
        PowerCellGroupManager manager = PowerCellGroupManager.get(helper.getLevel());
        PowerCellBlockEntity cellA = place(helper, CELL_A, PowerRegistry.INDUSTRIAL_POWER_CELL.get());
        PowerCellBlockEntity cellB = place(helper, CELL_B, PowerRegistry.INDUSTRIAL_POWER_CELL.get());
        BlockPos cellAAbs = helper.absolutePos(CELL_A);
        helper.assertTrue(energyOf(cellA).receiveEnergy(Integer.MAX_VALUE, false) == 2L * INDUSTRIAL_RATE,
                "两块成组时一次注入必须吃满 " + (2L * INDUSTRIAL_RATE) + " FE");

        manager.removeCell(cellAAbs);
        helper.assertTrue(manager.groupSizeAt(cellAAbs) == 0, "前提: A 必须已经不在任何组里");

        IEnergyStorage view = energyOf(cellA);
        helper.assertTrue(view.getEnergyStored() == INDUSTRIAL_RATE,
                "未入组时余额读数必须退化成本方块自身的 " + INDUSTRIAL_RATE + ", 得到 " + view.getEnergyStored());
        helper.assertTrue(view.getMaxEnergyStored() == INDUSTRIAL_CAPACITY,
                "未入组时容量读数必须退化成单块容量 " + INDUSTRIAL_CAPACITY + ", 得到 "
                        + view.getMaxEnergyStored());
        helper.assertTrue(view.receiveEnergy(1_000, false) == 0 && view.extractEnergy(1_000, false) == 0,
                "未入组时绝不搬电: 绕开组的每 tick 速率额度等于把刷电漏洞放回来");
        helper.assertTrue(cellA.extractForCharging(1_000, false) == 0, "未入组时手动充电口同样必须返回 0");
        helper.assertTrue(cellA.storedFeLong() == INDUSTRIAL_RATE,
                "全程一分余额都不得被动过, 得到 " + cellA.storedFeLong());
        helper.assertTrue(cellA.groupSize() == 1
                        && cellA.groupStoredFe() == INDUSTRIAL_RATE
                        && cellA.groupCapacityFe() == INDUSTRIAL_CAPACITY
                        && cellA.groupTransferFePerTick() == INDUSTRIAL_RATE,
                "未入组时全部聚合读数必须退化成成员数为 1 的组, 得到 " + cellA.groupSize() + "/"
                        + cellA.groupStoredFe() + "/" + cellA.groupCapacityFe() + "/"
                        + cellA.groupTransferFePerTick());
        helper.assertTrue(cellB.groupStoredFe() == INDUSTRIAL_RATE,
                "留在索引里的 B 必须已经拆成单块组, 得到 " + cellB.groupStoredFe());

        manager.addCell(cellAAbs, PowerCellSpec.INDUSTRIAL);
        helper.assertTrue(manager.sameGroup(cellAAbs, helper.absolutePos(CELL_B))
                        && cellA.groupStoredFe() == 2L * INDUSTRIAL_RATE,
                "重新入组后聚合读数必须立刻恢复成 " + (2L * INDUSTRIAL_RATE) + ", 得到 " + cellA.groupStoredFe());
        helper.succeed();
    }

    /**
     * 索引里挂着一个世界上并不存在的成员时, 一次读写必须按"本次不含这块"处理而不是抛异常, 且可注入
     * 空间只能按真正解析到的成员算 —— 否则会收下一笔谁都装不下的电, 最后在均摊那步炸掉。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void unresolvableMemberIsSkippedInsteadOfCrashing(GameTestHelper helper) {
        clearWorkspace(helper);
        PowerCellGroupManager manager = PowerCellGroupManager.get(helper.getLevel());
        PowerCellBlockEntity cellA = place(helper, CELL_A, PowerRegistry.INDUSTRIAL_POWER_CELL.get());
        PowerCellBlockEntity cellB = place(helper, CELL_B, PowerRegistry.INDUSTRIAL_POWER_CELL.get());
        BlockPos ghost = helper.absolutePos(CELL_A).above();
        helper.assertTrue(helper.getLevel().getBlockEntity(ghost) == null,
                "前提: 幽灵坐标上必须确实没有方块实体");

        manager.addCell(ghost, PowerCellSpec.INDUSTRIAL);
        helper.assertTrue(manager.groupSizeAt(helper.absolutePos(CELL_A)) == 3,
                "索引层必须认下这个幽灵成员, 得到 " + manager.groupSizeAt(helper.absolutePos(CELL_A)));

        long nominalRate = 3L * INDUSTRIAL_RATE;
        int accepted = energyOf(cellA).receiveEnergy(Integer.MAX_VALUE, false);
        helper.assertTrue(accepted == nominalRate,
                "额度按名义组速率 " + nominalRate + " 计, 得到 " + accepted);
        helper.assertTrue(cellA.storedFeLong() == nominalRate / 2 && cellB.storedFeLong() == nominalRate / 2,
                "这笔电必须只均摊给真实存在的两块(各 " + (nominalRate / 2) + "), 得到 "
                        + cellA.storedFeLong() + "/" + cellB.storedFeLong());
        helper.assertTrue(cellA.groupStoredFe() == nominalRate,
                "组余额只汇总解析得到的成员, 得到 " + cellA.groupStoredFe());
        helper.assertTrue(cellA.groupCapacityFe() == 3L * INDUSTRIAL_CAPACITY,
                "名义容量仍按成员数线性叠加, 得到 " + cellA.groupCapacityFe());

        manager.removeCell(ghost);
        helper.assertTrue(manager.groupSizeAt(helper.absolutePos(CELL_A)) == 2,
                "移除幽灵成员后必须回落到两块, 得到 " + manager.groupSizeAt(helper.absolutePos(CELL_A)));
        helper.succeed();
    }

    /**
     * 菜单数据槽契约: 五个 long 读数按 16 位切四段下发, 客户端经 signed short 传输后必须逐位无损重组。
     *
     * 三块工业储电的组容量 41_472_000 最低 16 位段是 53248, 原版同步包按 writeShort 传, 客户端 readShort
     * 读回 -12288; mergeWords 少写一处 & 0xFFFFL, 界面就会显示负数或天文数字。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void menuDataRoundTripsLongReadingsThroughSignedShortSlots(GameTestHelper helper) {
        helper.assertTrue(PowerCellMenu.WORDS_PER_VALUE == 4
                        && PowerCellMenu.VALUE_STORED == 0
                        && PowerCellMenu.VALUE_CAPACITY == 1
                        && PowerCellMenu.VALUE_RECEIVED == 2
                        && PowerCellMenu.VALUE_EXTRACTED == 3
                        && PowerCellMenu.VALUE_TRANSFER == 4
                        && PowerCellMenu.DATA_COUNT == 20,
                "储电菜单必须保持 5 个 long 读数 x 4 段 = 20 个数据槽的固定布局, 得到 "
                        + PowerCellMenu.DATA_COUNT);

        long[] boundaries = {
                0L, 32_767L, 32_768L, 65_535L, 65_536L,
                INDUSTRIAL_CAPACITY, 3L * INDUSTRIAL_CAPACITY,
                3L * FUTURE_CAPACITY, 64L * FUTURE_CAPACITY
        };
        for (long value : boundaries) {
            int word0 = PowerCellMenu.word(value, 0);
            int word1 = PowerCellMenu.word(value, 1);
            int word2 = PowerCellMenu.word(value, 2);
            int word3 = PowerCellMenu.word(value, 3);
            long throughNetwork = PowerCellMenu.mergeWords(
                    (short) word0, (short) word1, (short) word2, (short) word3);
            helper.assertTrue(PowerCellMenu.mergeWords(word0, word1, word2, word3) == value
                            && throughNetwork == value,
                    "long 读数必须跨 signed short 传输后逐位复原: " + value + ", 得到 " + throughNetwork);
        }

        clearWorkspace(helper);
        PowerCellBlockEntity cellA = place(helper, CELL_A, PowerRegistry.INDUSTRIAL_POWER_CELL.get());
        PowerCellBlockEntity cellB = place(helper, CELL_B, PowerRegistry.INDUSTRIAL_POWER_CELL.get());
        PowerCellBlockEntity cellC = place(helper, CELL_C, PowerRegistry.INDUSTRIAL_POWER_CELL.get());
        long groupRate = 3L * INDUSTRIAL_RATE;
        helper.assertTrue(energyOf(cellA).receiveEnergy(Integer.MAX_VALUE, false) == groupRate,
                "一次注入必须吃满整份组速率 " + groupRate);
        cellA.serverTick();
        cellB.serverTick();
        cellC.serverTick();
        // 把余额顶到跨 16 位段的临界: A/B 满仓、C 清零 -> 组余额 27648000(低段 57344, 按 short 读是 -8192)。
        preload(cellA, INDUSTRIAL_CAPACITY);
        preload(cellB, INDUSTRIAL_CAPACITY);
        preload(cellC, 0L);

        ContainerData serverData = PowerCellMenu.dataFor(cellA, false);
        ContainerData clientData = PowerCellMenu.dataFor(cellA, true);
        helper.assertTrue(!(serverData instanceof SimpleContainerData)
                        && clientData instanceof SimpleContainerData
                        && serverData.getCount() == PowerCellMenu.DATA_COUNT
                        && clientData.getCount() == PowerCellMenu.DATA_COUNT,
                "客户端储电菜单必须使用独立 SimpleContainerData 镜像");
        for (int index = 0; index < PowerCellMenu.DATA_COUNT; index++) {
            clientData.set(index, (short) serverData.get(index));
        }

        helper.assertTrue(mirrored(clientData, PowerCellMenu.VALUE_STORED) == 2L * INDUSTRIAL_CAPACITY,
                "客户端必须精确重组组余额 " + (2L * INDUSTRIAL_CAPACITY) + ", 得到 "
                        + mirrored(clientData, PowerCellMenu.VALUE_STORED));
        helper.assertTrue(mirrored(clientData, PowerCellMenu.VALUE_CAPACITY) == 3L * INDUSTRIAL_CAPACITY,
                "客户端必须精确重组组容量 " + (3L * INDUSTRIAL_CAPACITY) + ", 得到 "
                        + mirrored(clientData, PowerCellMenu.VALUE_CAPACITY));
        helper.assertTrue(mirrored(clientData, PowerCellMenu.VALUE_RECEIVED) == groupRate
                        && mirrored(clientData, PowerCellMenu.VALUE_EXTRACTED) == 0L,
                "客户端必须精确重组上一 tick 的进出功率 " + groupRate + "/0, 得到 "
                        + mirrored(clientData, PowerCellMenu.VALUE_RECEIVED) + "/"
                        + mirrored(clientData, PowerCellMenu.VALUE_EXTRACTED));
        helper.assertTrue(mirrored(clientData, PowerCellMenu.VALUE_TRANSFER) == groupRate,
                "客户端必须精确重组组速率 " + groupRate + ", 得到 "
                        + mirrored(clientData, PowerCellMenu.VALUE_TRANSFER));

        // 客户端镜像只认同步包: 服务端余额再变, 没重新同步就不得跟着变。
        preload(cellC, INDUSTRIAL_CAPACITY);
        helper.assertTrue(cellA.groupStoredFe() == 3L * INDUSTRIAL_CAPACITY,
                "前提: 服务端组余额必须已经变成 " + (3L * INDUSTRIAL_CAPACITY));
        helper.assertTrue(mirrored(clientData, PowerCellMenu.VALUE_STORED) == 2L * INDUSTRIAL_CAPACITY,
                "客户端菜单镜像不得绕过同步包直接读服务端方块实体, 得到 "
                        + mirrored(clientData, PowerCellMenu.VALUE_STORED));
        helper.succeed();
    }

    /**
     * 成员上限 64: 超限必须拒绝并入而不是静默合并。
     *
     * 这里直接驱动分组索引而不是摆 65 个方块: GameTest 结构只有 1x1x1, 铺 65 块会越进相邻用例的地盘。
     * 索引层的并组判定本来就与方块实体无关, 用例结束时逐个退组, 不留残留。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void groupRefusesToMergeBeyondMemberLimit(GameTestHelper helper) {
        PowerCellGroupManager manager = PowerCellGroupManager.get(helper.getLevel());
        BlockPos origin = helper.absolutePos(new BlockPos(0, 2, 0)).offset(4_096, 0, 0);
        for (int i = 0; i < PowerCellGroup.MAX_MEMBERS; i++) {
            manager.addCell(origin.offset(i, 0, 0), PowerCellSpec.INDUSTRIAL);
        }
        helper.assertTrue(manager.groupSizeAt(origin) == PowerCellGroup.MAX_MEMBERS,
                "满编组必须恰好 " + PowerCellGroup.MAX_MEMBERS + " 个成员, 得到 " + manager.groupSizeAt(origin));

        BlockPos overflow = origin.offset(PowerCellGroup.MAX_MEMBERS, 0, 0);
        manager.addCell(overflow, PowerCellSpec.INDUSTRIAL);
        helper.assertTrue(manager.groupSizeAt(overflow) == 1,
                "超限的方块必须自成一组, 得到 " + manager.groupSizeAt(overflow));
        helper.assertTrue(!manager.sameGroup(origin, overflow),
                "超限的方块绝不得被静默并入满编组");
        helper.assertTrue(manager.groupSizeAt(origin) == PowerCellGroup.MAX_MEMBERS,
                "满编组不得因为被拒的邻居而变化, 得到 " + manager.groupSizeAt(origin));

        for (int i = 0; i <= PowerCellGroup.MAX_MEMBERS; i++) {
            manager.removeCell(origin.offset(i, 0, 0));
        }
        helper.assertTrue(manager.groupSizeAt(origin) == 0 && manager.groupSizeAt(overflow) == 0,
                "用例收尾必须把索引清空, 得到 " + manager.groupSizeAt(origin) + "/"
                        + manager.groupSizeAt(overflow));
        helper.succeed();
    }

    /**
     * 手动充电必须只扣走目标真正收下的量。
     *
     * 目标的 simulate 与实收不一致是第三方 capability 里真实存在的情况(Forge 不做任何保证)。旧实现
     * 先按 simulate 报的余量从储电扣电, 再把扣出来的量丢给目标并丢弃返回值 —— 目标少收多少就凭空烧掉
     * 多少, 而组化把单次可搬量从单块速率抬到整组速率之后, 单次失配的损失最多放大到 64 倍。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void manualChargingBurnsNothingWhenTargetAcceptsLessThanSimulated(GameTestHelper helper) {
        clearWorkspace(helper);
        PowerCellBlockEntity cellA = place(helper, CELL_A, PowerRegistry.INDUSTRIAL_POWER_CELL.get());
        PowerCellBlockEntity cellB = place(helper, CELL_B, PowerRegistry.INDUSTRIAL_POWER_CELL.get());
        long total = 12_000L;
        preload(cellA, 6_000L);
        preload(cellB, 6_000L);
        helper.assertTrue(cellA.groupStoredFe() == total,
                "两块工业储电必须成组且共 " + total + " FE, 得到 " + cellA.groupStoredFe());

        HalfAcceptingSink sink = new HalfAcceptingSink((int) total);
        PowerCellBlock.chargeInto(cellA, sink);

        long conserved = sink.stored + cellA.groupStoredFe();
        helper.assertTrue(conserved == total,
                "手动充电必须逐 FE 守恒: 目标实收 " + sink.stored + " + 组内余额 " + cellA.groupStoredFe()
                        + " 应等于 " + total + ", 得到 " + conserved);
        // 只断言守恒会被"什么都没发生"蒙混过关(0 + 12000 也守恒), 故同时钉住确实灌进去了。
        helper.assertTrue(sink.stored >= 11_000,
                "目标必须被灌到接近满仓, 实收 " + sink.stored);
        helper.assertTrue(cellA.groupStoredFe() < INDUSTRIAL_RATE,
                "储电必须被抽到只剩不足一份速率的零头, 剩余 " + cellA.groupStoredFe());
        helper.succeed();
    }

    /**
     * 组容量越过 int 上限后, 对外读数必须仍留出可用余量。
     *
     * 余额与容量各自独立饱和截断时两者会同时钉在 Integer.MAX_VALUE, 第三方按 Forge 的事实约定算
     * max - stored 得 0, 于是判定这组储电已满而停止推电 —— 三块未来储电(26.5 亿)就已经触发。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void oversizedGroupKeepsUsableRoomInItsIntWindow(GameTestHelper helper) {
        clearWorkspace(helper);
        PowerCellBlockEntity futureA = place(helper, CELL_A, PowerRegistry.FUTURE_POWER_CELL.get());
        PowerCellBlockEntity futureB = place(helper, CELL_B, PowerRegistry.FUTURE_POWER_CELL.get());
        PowerCellBlockEntity futureC = place(helper, CELL_C, PowerRegistry.FUTURE_POWER_CELL.get());
        preload(futureA, 833_333_334L);
        preload(futureB, 833_333_333L);
        preload(futureC, 833_333_333L);

        long capacity = 3L * FUTURE_CAPACITY;
        long stored = 2_500_000_000L;
        helper.assertTrue(futureA.groupCapacityFe() == capacity && futureA.groupStoredFe() == stored,
                "内部账本必须是真实的 long 值 " + stored + "/" + capacity + ", 得到 "
                        + futureA.groupStoredFe() + "/" + futureA.groupCapacityFe());
        helper.assertTrue(capacity > Integer.MAX_VALUE && stored > Integer.MAX_VALUE,
                "本用例的前提是余额与容量双双越过 int 上限, 否则测不到缩放路径");

        IEnergyStorage energy = energyOf(futureA);
        helper.assertTrue(energy.getMaxEnergyStored() == Integer.MAX_VALUE,
                "越界容量对外必须饱和到 " + Integer.MAX_VALUE + ", 得到 " + energy.getMaxEnergyStored());
        // 2_500_000_000 / 2_654_208_000 x Integer.MAX_VALUE, 按同一公式独立算出的期望值。
        helper.assertTrue(energy.getEnergyStored() == 2_022_716_048,
                "越界余额必须按同比例缩进 int 窗口, 期望 2022716048, 得到 " + energy.getEnergyStored());
        long reportedRoom = (long) energy.getMaxEnergyStored() - energy.getEnergyStored();
        helper.assertTrue(reportedRoom == 124_767_599L,
                "第三方按 max - stored 算出的余量必须仍然可用, 期望 124767599, 得到 " + reportedRoom);
        helper.assertTrue(futureB.groupStoredFe() == stored && futureC.groupStoredFe() == stored,
                "对外缩放只是展示口径, 绝不许回写内部账本");

        // 容量没越界时一律走精确值, 缩放不得渗进正常量级。
        clearWorkspace(helper);
        PowerCellBlockEntity smallA = place(helper, CELL_A, PowerRegistry.INDUSTRIAL_POWER_CELL.get());
        place(helper, CELL_B, PowerRegistry.INDUSTRIAL_POWER_CELL.get());
        preload(smallA, 1_000L);
        IEnergyStorage small = energyOf(smallA);
        helper.assertTrue(small.getEnergyStored() == 1_000
                        && small.getMaxEnergyStored() == (int) (2L * INDUSTRIAL_CAPACITY),
                "容量在 int 安全区内时读数必须逐位精确, 得到 " + small.getEnergyStored()
                        + "/" + small.getMaxEnergyStored());
        helper.succeed();
    }

    /**
     * 菜单的整组读数必须在同一 tick 内取自同一份快照。
     *
     * 每个 long 读数切成 4 个槽, broadcastChanges 每 tick 把 20 个槽逐一取一遍; 若每槽各自重算, 组余额
     * 在两次取槽之间发生变化就会撕裂 —— 四段来自不同的值, 客户端拼回来的既不是变化前也不是变化后, 而是
     * 一个不存在的数。顺带把每 tick 十几倍成员数次的 getBlockEntity 也省掉。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void menuReadingsComeFromOneSnapshotPerTick(GameTestHelper helper) {
        clearWorkspace(helper);
        PowerCellBlockEntity cellA = place(helper, CELL_A, PowerRegistry.INDUSTRIAL_POWER_CELL.get());
        place(helper, CELL_B, PowerRegistry.INDUSTRIAL_POWER_CELL.get());
        // 65536 与 65535 刻意跨 16 位段边界: 前者是 word0=0/word1=1, 后者是 word0=65535/word1=0,
        // 撕裂读会拼出 0 —— 既不是 65536 也不是 65535。
        preload(cellA, 65_536L);
        ContainerData data = PowerCellMenu.dataFor(cellA, false);

        int base = PowerCellMenu.VALUE_STORED * PowerCellMenu.WORDS_PER_VALUE;
        int word0 = data.get(base);
        preload(cellA, 65_535L);
        long reassembled = PowerCellMenu.mergeWords(
                word0, data.get(base + 1), data.get(base + 2), data.get(base + 3));
        helper.assertTrue(reassembled == 65_536L,
                "同一 tick 内取到的四段必须来自同一份快照(65536), 撕裂读会得到 0, 实得 " + reassembled);

        helper.startSequence()
                .thenIdle(2)
                .thenExecute(() -> {
                    long refreshed = mirrored(data, PowerCellMenu.VALUE_STORED);
                    helper.assertTrue(refreshed == 65_535L,
                            "跨 tick 后必须刷新成新值 65535, 实得 " + refreshed);
                })
                .thenSucceed();
    }

    /**
     * GameTest 复用存档, 结构外的方块会跨轮留在原地。留在工作区一格邻域里的旧储电会并进本轮的组, 把
     * 成员数与容量断言全部带偏, 因此每个用例先把工作区连同一格边界清干净。
     */
    private static void clearWorkspace(GameTestHelper helper) {
        // 从 x=1/z=1 起清: 相对坐标 (0,0,0) 就是结构方块本体, 清掉它 GameTest 收尾取不到结构边界会直接炸。
        for (int x = 1; x <= 6; x++) {
            for (int y = 0; y <= 3; y++) {
                for (int z = 1; z <= 3; z++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
    }

    private static PowerCellBlockEntity place(GameTestHelper helper, BlockPos relative, Block block) {
        helper.setBlock(relative, block.defaultBlockState().setValue(PowerCellBlock.FACING, Direction.NORTH));
        if (helper.getBlockEntity(relative) instanceof PowerCellBlockEntity cell) {
            return cell;
        }
        throw new IllegalStateException("power cell block entity missing at " + relative);
    }

    /** 直接把余额顶到指定值: 速率额度按 tick 计, 一个 tick 内注不满 1382 万 FE。 */
    private static void preload(PowerCellBlockEntity cell, long storedFe) {
        CompoundTag tag = cell.saveWithFullMetadata();
        tag.putLong("storedFe", storedFe);
        cell.load(tag);
    }

    /** 按客户端的读法把某个读数的四段重组回 long。 */
    private static long mirrored(ContainerData data, int valueIndex) {
        int base = valueIndex * PowerCellMenu.WORDS_PER_VALUE;
        return PowerCellMenu.mergeWords(data.get(base), data.get(base + 1),
                data.get(base + 2), data.get(base + 3));
    }

    private static IEnergyStorage energyOf(PowerCellBlockEntity cell) {
        return cell.getCapability(ForgeCapabilities.ENERGY)
                .orElseThrow(() -> new IllegalStateException("power cell exposes no energy capability"));
    }

    /**
     * simulate 老实报余量、实收只收一半的目标。用来复现第三方 capability 的 simulate 与实收不一致,
     * 这正是"先扣后送"会凭空烧电的那种目标。
     */
    private static final class HalfAcceptingSink implements IEnergyStorage {
        private final int capacity;
        private int stored;

        private HalfAcceptingSink(int capacity) {
            this.capacity = capacity;
        }

        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            int room = capacity - stored;
            if (simulate) {
                return Math.min(maxReceive, room);
            }
            int accepted = Math.min(maxReceive / 2, room);
            stored += accepted;
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

    /** 只发不收的无限电源, 让电网的拉阶段每 tick 都能把缓冲填满, 排除供给不足对断言的干扰。 */
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
}
