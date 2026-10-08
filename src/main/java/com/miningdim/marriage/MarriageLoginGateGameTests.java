package com.miningdim.marriage;

import com.miningdim.core.MiningConstants;
import com.miningdim.core.auth.PlayerLoginGate;
import com.miningdim.entry.IMiningPlayerData;
import com.miningdim.entry.MiningCapabilities;
import com.miningdim.registry.ModItems;
import com.miningdim.testutil.MockGameTestPlayers;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 结婚戒指右键的登录门: 未通过 /login 的玩家右键戒指, 不触发传送, 也不下发共享背包内容。
 *
 * 两道拦截分工: "还没 /login" 由登录门在 HIGHEST 取消 (core.auth.LoginGateSubsystem), 事件根本到不了戒指监听器;
 * "登录态无从判定" 时原版交互放行, 由戒指监听器自己对本 mod 功能关门。
 *
 * 走真实的 Forge 事件总线 (MinecraftForge.EVENT_BUS.post), 证明拦截发生在已注册的监听器里而不是某个没接线的
 * 辅助方法里; 以动作栏文案区分"被登录门拦下"与"走进了戒指业务逻辑" (后者对未婚玩家回的是婚姻文案)。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class MarriageLoginGateGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "marriage_login_gate";
    private static final String LOGIN_REQUIRED_KEY = "message.miningdim.login_gate.required";
    private static final String LOGIN_UNAVAILABLE_KEY = "message.miningdim.login_gate.unavailable";
    private static final String FILED_NOTIFY_KEY = "message.miningdim.marriage.divorce.filed_notify";
    private static final String CLAIMS_DELIVERED_KEY = "message.miningdim.marriage.divorce.claims_delivered";
    /** divorceNoticeAndClaimsWaitForLogin 的超时上限, 也是它超时出口的触发拍; 两处必须同值, 故收成一个常量。 */
    private static final int NOTICE_TIMEOUT_TICKS = 100;

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void weddingRingDoesNothingBeforeLogin(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.WEDDING_RING.get()));
        EmbeddedChannel channel = (EmbeddedChannel) player.connection.connection.channel();
        drainActionBarKeys(channel);

        try (PlayerLoginGate.ForcedVerdict ignored =
                     PlayerLoginGate.forceVerdictForTest(player.getUUID(), PlayerLoginGate.Verdict.NOT_LOGGED_IN)) {
            PlayerInteractEvent.RightClickItem event =
                    new PlayerInteractEvent.RightClickItem(player, InteractionHand.MAIN_HAND);
            MinecraftForge.EVENT_BUS.post(event);
            helper.assertTrue(event.isCanceled(), "the ring use of a player who has not /login-ed must be consumed");
            List<String> keys = drainActionBarKeys(channel);
            helper.assertTrue(keys.isEmpty(),
                    "cancelled at HIGHEST, the ring listener must not run at all (no marriage message), got " + keys);
        }

        try (PlayerLoginGate.ForcedVerdict ignored =
                     PlayerLoginGate.forceVerdictForTest(player.getUUID(), PlayerLoginGate.Verdict.UNAVAILABLE)) {
            PlayerInteractEvent.RightClickItem event =
                    new PlayerInteractEvent.RightClickItem(player, InteractionHand.MAIN_HAND);
            MinecraftForge.EVENT_BUS.post(event);
            helper.assertTrue(event.isCanceled(), "UNAVAILABLE: the ring listener itself must refuse and consume the use");
            List<String> keys = drainActionBarKeys(channel);
            helper.assertTrue(keys.equals(List.of(LOGIN_UNAVAILABLE_KEY)),
                    "UNAVAILABLE: the only feedback must be the login-check notice (no ring logic may run), got "
                            + keys);
        }

        PlayerInteractEvent.RightClickItem afterLogin =
                new PlayerInteractEvent.RightClickItem(player, InteractionHand.MAIN_HAND);
        MinecraftForge.EVENT_BUS.post(afterLogin);
        List<String> keys = drainActionBarKeys(channel);
        helper.assertTrue(!keys.isEmpty() && !keys.contains(LOGIN_REQUIRED_KEY) && !keys.contains(LOGIN_UNAVAILABLE_KEY),
                "after login the ring logic must run again (an unmarried player gets a marriage message), got " + keys);
        helper.succeed();
    }

    /**
     * 进服时要给本人的公示期知情通知 (含发起方名字) 与离婚清算物, 等登录门确认身份后才送达。
     *
     * 走真实接线: 经事件总线重放一次进服 (此刻被拒) -> 什么都不发, 清算物不进背包; 判定放行后<b>真实 tick</b> 的
     * 登录确认巡检触发 MarriageSystem 注册的监听器 -> 通知与清算物此刻送达。把 deliverClaims 或知情通知挪回
     * onPlayerLoggedIn, 或删掉 MarriageSystem.register 里那行监听器注册, 都会挂。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH,
            timeoutTicks = NOTICE_TIMEOUT_TICKS)
    public static void divorceNoticeAndClaimsWaitForLogin(GameTestHelper helper) {
        ServerPlayer spouse = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ServerLevel overworld = spouse.getServer().overworld();
        UUID initiator = UUID.randomUUID();
        MarriageRegistry registry = MarriageRegistry.get(overworld);
        MarriageState state = registry.createMarriage(initiator, spouse.getUUID(), overworld.getGameTime());
        // 这桩测试婚姻、它的公示期与待领取的钻石都落在 overworld 存档里。回收只挂在成功回调上的话, 用例失败或超时时
        // 它们会留给后面批次的到期扫描, 并随 run/world 进下一轮, 故另备下面两条不依赖成功的出口。
        Runnable discardTestMarriage = () -> {
            registry.dissolve(state.marriageId());
            MarriageHistory.get(overworld).takeSettlementClaims(spouse.getUUID());
        };
        // 超时出口: 框架在 tick 超过 timeoutTicks 时才判超时, 同一 tick 里定时任务又先于判定执行, 所以排在
        // timeoutTicks 这一拍必定赶在超时之前; 用例已经成功则框架不再 tick 它, 这条不会再跑。
        helper.runAtTickTime(NOTICE_TIMEOUT_TICKS, discardTestMarriage);
        try {
            state.beginPendingDivorce(initiator, overworld.getGameTime(), 0L);
            registry.setDirty();
            IMiningPlayerData data = MiningCapabilities.get(spouse).orElseThrow();
            data.setMarriageId(state.marriageId());
            data.setSpouseUUID(initiator);
            MarriageHistory.get(overworld).queueSettlementClaim(spouse.getUUID(), new ItemStack(Items.DIAMOND, 3));
            EmbeddedChannel channel = (EmbeddedChannel) spouse.connection.connection.channel();
            drainChatKeys(channel);

            try (PlayerLoginGate.ForcedVerdict ignored =
                         PlayerLoginGate.forceVerdictForTest(spouse.getUUID(), PlayerLoginGate.Verdict.NOT_LOGGED_IN)) {
                MinecraftForge.EVENT_BUS.post(new PlayerEvent.PlayerLoggedInEvent(spouse));
                List<String> keys = drainChatKeys(channel);
                helper.assertTrue(!keys.contains(FILED_NOTIFY_KEY) && !keys.contains(CLAIMS_DELIVERED_KEY),
                        "a connection that has not passed the login gate must not be told about the divorce, got "
                                + keys);
                helper.assertTrue(spouse.getInventory().countItem(Items.DIAMOND) == 0,
                        "settlement items must stay queued until the login is confirmed, have "
                                + spouse.getInventory().countItem(Items.DIAMOND));
            }

            List<String> keys = new ArrayList<>();
            helper.succeedWhen(() -> {
                keys.addAll(drainChatKeys(channel));
                helper.assertTrue(keys.contains(FILED_NOTIFY_KEY) && keys.contains(CLAIMS_DELIVERED_KEY),
                        "once the login is confirmed the divorce notice and the claims notice must arrive, got "
                                + keys);
                helper.assertTrue(spouse.getInventory().countItem(Items.DIAMOND) == 3,
                        "once the login is confirmed the queued settlement items must be delivered, have "
                                + spouse.getInventory().countItem(Items.DIAMOND));
                // 收尾: 撤掉这桩测试婚姻, 不把一条悬着的公示期留给后面的到期扫描。
                registry.dissolve(state.marriageId());
                registry.setDirty();
            });
        } catch (RuntimeException failure) {
            // 同步段出口: 这里一抛框架当场判负, 之后不再 tick 本用例, 上面那条定时任务跑不到。
            discardTestMarriage.run();
            throw failure;
        }
    }

    /**
     * 取出动作栏文案的翻译键。1.20.1 的 {@code ServerPlayer.displayClientMessage(c, true)} 发的是
     * overlay=true 的 {@link ClientboundSystemChatPacket}, 不是 ClientboundSetActionBarTextPacket。
     */
    private static List<String> drainActionBarKeys(EmbeddedChannel channel) {
        return drainKeys(channel, true);
    }

    /** 取出聊天栏 (overlay=false) 系统消息的翻译键。 */
    private static List<String> drainChatKeys(EmbeddedChannel channel) {
        return drainKeys(channel, false);
    }

    private static List<String> drainKeys(EmbeddedChannel channel, boolean overlay) {
        List<String> keys = new ArrayList<>();
        Object outbound;
        while ((outbound = channel.readOutbound()) != null) {
            try {
                if (outbound instanceof ClientboundSystemChatPacket packet && packet.overlay() == overlay
                        && packet.content().getContents() instanceof TranslatableContents translatable) {
                    keys.add(translatable.getKey());
                }
            } finally {
                ReferenceCountUtil.release(outbound);
            }
        }
        return keys;
    }
}
