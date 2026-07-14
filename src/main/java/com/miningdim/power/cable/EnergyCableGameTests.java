package com.miningdim.power.cable;

import com.miningdim.core.MiningConstants;
import com.miningdim.power.PowerRegistry;
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
 * 线缆网络的 GameTest。不依赖发电机 (发电机 FE 内脏是提交 B): 拓扑用直接放置/破坏线缆断言并网与拆网,
 * 缓冲用线缆自身的 receive-only FE 能力断言瞬态缓冲与全网共享。发电机->线缆->用电端的端到端流动测放到提交 B。
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
        BlockState basic = PowerRegistry.CABLES.get(CableTier.BASIC).get().defaultBlockState();

        BlockPos a = new BlockPos(2, 1, 1);
        BlockPos bridge = new BlockPos(2, 1, 2);
        BlockPos c = new BlockPos(2, 1, 3);
        BlockPos aAbs = helper.absolutePos(a);
        BlockPos bridgeAbs = helper.absolutePos(bridge);
        BlockPos cAbs = helper.absolutePos(c);

        // A 与 C 之间留空 -> 两张独立网, 各 1 根。
        helper.setBlock(a, basic);
        helper.setBlock(c, basic);
        helper.assertTrue(!manager.debugSameNetwork(aAbs, cAbs),
                "留空的 A 与 C 必须是两张独立网");
        helper.assertTrue(manager.debugNetworkSize(aAbs) == 1,
                "A 必须是单根成网, 实为 " + manager.debugNetworkSize(aAbs));

        // 桥接 B -> 合并成一张 3 根的网。
        helper.setBlock(bridge, basic);
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
        BlockState basic = PowerRegistry.CABLES.get(CableTier.BASIC).get().defaultBlockState();

        BlockPos first = new BlockPos(2, 1, 1);
        BlockPos second = new BlockPos(2, 1, 2);
        helper.setBlock(first, basic);
        helper.setBlock(second, basic);
        BlockPos firstAbs = helper.absolutePos(first);
        BlockPos secondAbs = helper.absolutePos(second);

        IEnergyStorage cap = level.getBlockEntity(firstAbs)
                .getCapability(ForgeCapabilities.ENERGY).resolve().orElse(null);
        helper.assertTrue(cap != null, "线缆必须暴露 ForgeCapabilities.ENERGY");
        helper.assertTrue(!cap.canExtract(), "线缆能力必须只收不放 (canExtract=false)");
        helper.assertTrue(cap.canReceive(), "线缆能力必须可收 (canReceive=true)");

        // 基础级缓冲 = transferCap = 256; 收 100 应全收。
        int accepted = cap.receiveEnergy(100, false);
        helper.assertTrue(accepted == 100, "基础线缆缓冲应全收 100 FE, 实收 " + accepted);

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
}
