package com.miningdim.freshness;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;

/**
 * 把时钟与保质期推给客户端 (规格「客户端显示」的数据来源)。
 *
 * 时机两条:
 * <ul>
 *   <li><b>登录时</b>: 玩家一进来就有正确的钟, 不会先画一段错的;</li>
 *   <li><b>每 {@link #INTERVAL_TICKS} tick</b>: 客户端两次同步之间是靠自己流逝的时间往前推的, 定时回来校准
 *       一次, 免得长时间在线之后显示漂移 (挂钟跳变 / 停服补偿都活在服务端的偏移里)。</li>
 * </ul>
 *
 * 包很小 (两个 long + 一个 byte + 六个 varint), 对每个玩家 30 秒一次; 玩家多的时候也就是几百字节每秒。
 */
public final class FreshnessSync {

    private FreshnessSync() {
    }

    /** 定时校准间隔 (tick): 600 = 30 秒。 */
    public static final int INTERVAL_TICKS = 600;

    /** 打包当下的同步内容 (用例直接调它断言"包里就是服务端此刻的真相")。 */
    public static FreshnessSyncS2C payloadFor(ServerLevel level) {
        FreshnessClockData data = FreshnessClockData.get(level);
        return new FreshnessSyncS2C(data.nowMinute(), data.clock().offsetHours(),
                FreshnessConfig.MODE.get().ordinal(), FreshnessShelfLives.currentValues());
    }

    /** 发给一个玩家 (登录时与定时校准都走它)。 */
    public static void sendTo(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        FreshnessNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), payloadFor(level));
    }

    /** 定时校准: 发给所有人 (由模块的服务端 tick 钩子按 {@link #INTERVAL_TICKS} 调)。 */
    public static void broadcast(MinecraftServer server) {
        if (server == null || server.getPlayerList().getPlayers().isEmpty()) {
            return;
        }
        FreshnessSyncS2C payload = payloadFor(server.overworld());
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            FreshnessNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), payload);
        }
    }

    /** 到了该校准的那一 tick 吗。 */
    public static boolean isSyncTick(long gameTime) {
        return gameTime % INTERVAL_TICKS == 0L;
    }
}
