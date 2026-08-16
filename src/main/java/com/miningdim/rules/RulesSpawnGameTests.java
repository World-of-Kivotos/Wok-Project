package com.miningdim.rules;

import com.miningdim.core.MiningConstants;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerSetSpawnEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** 矿山维度重生点规则的事件总线集成测试。 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class RulesSpawnGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "rules";

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void miningDimensionSpawnIsCanceled(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        PlayerSetSpawnEvent event = new PlayerSetSpawnEvent(
                player, MiningConstants.MINING_LEVEL, BlockPos.ZERO, false);

        MinecraftForge.EVENT_BUS.post(event);

        helper.assertTrue(event.isCanceled(), "指向矿山维度的重生点设置必须被取消");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void overworldSpawnIsNotCanceled(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        PlayerSetSpawnEvent event = new PlayerSetSpawnEvent(player, Level.OVERWORLD, BlockPos.ZERO, false);

        MinecraftForge.EVENT_BUS.post(event);

        helper.assertTrue(!event.isCanceled(), "指向主世界的重生点设置不得被取消");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void clearingSpawnIsNotCanceled(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        PlayerSetSpawnEvent event = new PlayerSetSpawnEvent(player, Level.OVERWORLD, null, false);

        MinecraftForge.EVENT_BUS.post(event);

        helper.assertTrue(event.getNewSpawn() == null, "测试前提: 清除重生点事件必须携带 null 坐标");
        helper.assertTrue(!event.isCanceled(), "清除已有重生点不得被取消");
        helper.succeed();
    }
}
