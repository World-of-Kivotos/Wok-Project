package com.miningdim.testutil;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * GameTest 共享测试工具: 构造可被 PlayerList.placeNewPlayer 接受的 mock ServerPlayer。
 *
 * 背景 (Forge GameTest 框架限制, 非本 mod 业务 bug):
 * Forge 的 {@link GameTestHelper#makeMockServerPlayerInLevel()} 用
 * {@code new Connection(PacketFlow.SERVERBOUND)} 造连接后直接 placeNewPlayer。该 Connection 的
 * netty channel 字段为 null, 而 placeNewPlayer -> NetworkHooks.sendMCRegistryPackets ->
 * NetworkFilters.injectIfNecessary 第一句即 {@code connection.channel().pipeline()}, 对 null channel
 * 抛 NPE。所以只要 mod 走到 Forge 网络过滤注入, 凡调用该 helper 的用例都在业务断言执行前就崩。
 *
 * 修复手法 (仅造一个"活动" channel, 不改任何业务逻辑):
 * {@link Connection} 本身是一个 {@code SimpleChannelInboundHandler}。把它加进一个
 * {@link EmbeddedChannel}, netty 会立即触发其 {@code channelActive}, 使 {@code connection.channel()}
 * 返回这个 EmbeddedChannel (非 null), 且其 pipeline 真实可用。NetworkFilters.injectIfNecessary 随后
 * 检查 {@code pipeline.get("packet_handler")} —— EmbeddedChannel 里只有 Connection 这一个 handler、
 * 不含名为 "packet_handler" 的 handler, 该方法因而 early-return, 既不 NPE 也不注入真实网络过滤器。
 * placeNewPlayer 后续大量 listener.send(...) 把登录态封包写入 EmbeddedChannel 出站队列 (mock 环境无害)。
 *
 * 返回的 ServerPlayer 与 Forge 原 helper 行为一致 (isSpectator=false, isCreative=true), 业务断言可照常
 * 调用 addEffect / getEffect / 注入 LivingEntity 事件等。
 */
public final class MockGameTestPlayers {

    private MockGameTestPlayers() {
    }

    /**
     * 仿 {@link GameTestHelper#makeMockServerPlayerInLevel()}, 但给 {@link Connection} 装一个活动的
     * {@link EmbeddedChannel} 后再 placeNewPlayer, 规避 Forge NetworkFilters 对 null channel 的 NPE。
     *
     * @param helper 当前 GameTest 的 helper (提供 ServerLevel 与其 server)
     * @return 已登记进 PlayerList 的 mock ServerPlayer
     */
    public static ServerPlayer makeMockServerPlayerWithChannel(GameTestHelper helper) {
        return makeMockServerPlayerWithChannel(helper, true);
    }

    /**
     * 与 {@link #makeMockServerPlayerWithChannel(GameTestHelper)} 相同, 但 {@code isCreative()=false},
     * 用于需要走生存破坏/工具门路径的用例 (如枪匠组装台从属格破坏的工具门校验)。
     */
    public static ServerPlayer makeMockSurvivalServerPlayerWithChannel(GameTestHelper helper) {
        return makeMockServerPlayerWithChannel(helper, false);
    }

    private static ServerPlayer makeMockServerPlayerWithChannel(GameTestHelper helper, boolean creative) {
        ServerLevel level = helper.getLevel();
        ServerPlayer serverPlayer = new ServerPlayer(
                level.getServer(),
                level,
                new GameProfile(UUID.randomUUID(), "test-mock-player")) {
            @Override
            public boolean isSpectator() {
                return false;
            }

            @Override
            public boolean isCreative() {
                return creative;
            }
        };

        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        // 把 Connection (ChannelHandler) 加进 EmbeddedChannel 会触发 channelActive,
        // 令 connection.channel() 返回非 null channel, 修复 NetworkFilters.injectIfNecessary 的 NPE。
        new EmbeddedChannel(connection);

        level.getServer().getPlayerList().placeNewPlayer(connection, serverPlayer);
        return serverPlayer;
    }
}
