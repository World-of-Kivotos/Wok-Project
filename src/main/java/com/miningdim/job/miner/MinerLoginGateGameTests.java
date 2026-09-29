package com.miningdim.job.miner;

import com.miningdim.core.MiningConstants;
import com.miningdim.core.auth.PlayerLoginGate;
import com.miningdim.entry.MiningCapabilities;
import com.miningdim.job.JobId;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * 矿工键位包的登录门: 未通过 AccessHub /login 的连接, 三个矿工 C2S 包的服务端入口 ({@link MinerActions} 的
 * handleToggle / handleChainHold / handleChainPreview) 什么都不做; 登录后同样的调用照常生效。
 *
 * 用满级矿工驱动, 让"被拒"与"等级没解锁"区分得开: 满级时开关翻转与按住激活都一定会发生, 没发生只能是登录门。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class MinerLoginGateGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "miner_login_gate";

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void minerKeyPacketsDoNothingBeforeLogin(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        MiningCapabilities.get(player)
                .orElseThrow(() -> new IllegalStateException("mock 玩家没有挂上矿山玩家数据 capability"))
                .jobProgress(JobId.MINER).setLevel(MinerConstants.MAX_LEVEL);
        MinerChargeState state = MinerSystem.get().stateOf(player);
        long now = player.serverLevel().getGameTime();

        try (PlayerLoginGate.ForcedVerdict ignored =
                     PlayerLoginGate.forceVerdictForTest(player.getUUID(), PlayerLoginGate.Verdict.NOT_LOGGED_IN)) {
            MinerActions.handleToggle(player, MinerSkill.AUTO_COLLECT);
            helper.assertTrue(!state.toggled(MinerSkill.AUTO_COLLECT),
                    "a toggle packet from a player who has not /login-ed must not flip anything");
            MinerActions.handleChainHold(player, true);
            helper.assertTrue(!state.chainHeldActive(now),
                    "a chain-hold packet from a player who has not /login-ed must not arm chain mining");
            // 预览只读, 但同样不许替未登录者跑 plan (它会下发可连锁坐标); 这里只要求它安静返回。
            MinerActions.handleChainPreview(player, player.blockPosition().below());
        }

        MinerActions.handleToggle(player, MinerSkill.AUTO_COLLECT);
        helper.assertTrue(state.toggled(MinerSkill.AUTO_COLLECT),
                "after login the same toggle must flip AUTO_COLLECT for a max-level miner");
        MinerActions.handleChainHold(player, true);
        helper.assertTrue(state.chainHeldActive(now),
                "after login the same chain-hold must arm chain mining");
        MinerActions.handleChainHold(player, false);
        helper.succeed();
    }
}
