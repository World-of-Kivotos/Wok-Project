package com.miningdim.title;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.GameProfileCache;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;

/** 称号提示、命令反馈与审计日志共用的文字写法: 时间 (赞助到期、下次可修改) 与玩家名。 */
final class TitleText {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private TitleText() {
    }

    /** 服务器本地时区, 精确到分钟。 */
    static String time(long epochMillis) {
        return TIME_FORMAT.format(Instant.ofEpochMilli(epochMillis));
    }

    /** 玩家名: 在线取实名, 否则查用户缓存 (离线玩家), 都查不到时退回 UUID。 */
    static String playerName(MinecraftServer server, UUID player) {
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        if (online != null) {
            return online.getGameProfile().getName();
        }
        GameProfileCache cache = server.getProfileCache();
        Optional<GameProfile> cached = cache == null ? Optional.empty() : cache.get(player);
        return cached.map(GameProfile::getName).orElse(player.toString());
    }
}
