package com.miningdim.district.service;

import com.miningdim.district.core.DistrictTexts;
import com.miningdim.district.core.SeenPlayer;
import com.miningdim.district.store.DistrictRepository;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * {@link PlayerDirectory} 的服务端实现, 按顺序查:
 * <ol>
 *   <li>在线玩家 (不分大小写) -&gt; 取 GameProfile 的规范名与 UUID;</li>
 *   <li>见过的玩家表 (按小写名, 多行时取最后在线最新的);</li>
 *   <li>旧存档兜底: playerdata/&lt;离线UUID(输入)&gt;.dat 存在 -&gt; 进过服, 规范名就是输入 (离线 UUID 由名字原样大小写算出,
 *       文件存在就说明大小写正确), 并顺手补一行 backfill;</li>
 *   <li>以上都没有 -&gt; 从没进过服: UUID 取 {@code UUIDUtil.createOfflinePlayerUUID(输入)}, 名字按输入原样。</li>
 * </ol>
 * 绝不调用 {@code GameProfileCache.get(String)}: 缓存没命中时它会在主线程上发 Mojang HTTP 请求; 离线模式下还会编一个假档案
 * 写进 usercache, 从而既判断不了"没进过服", 又污染缓存。
 */
public final class ServerPlayerDirectory implements PlayerDirectory {

    private final DistrictRepository repo;
    private final Function<String, GameProfile> onlineByName;
    private final Predicate<UUID> playerDataExists;
    private final LongSupplier clock;

    public ServerPlayerDirectory(DistrictRepository repo, Function<String, GameProfile> onlineByName,
                                 Predicate<UUID> playerDataExists, LongSupplier clock) {
        this.repo = repo;
        this.onlineByName = onlineByName;
        this.playerDataExists = playerDataExists;
        this.clock = clock;
    }

    /** 生产实现: 在线玩家表、见过的玩家表、存档的 playerdata 目录。 */
    public static ServerPlayerDirectory forServer(MinecraftServer server, DistrictRepository repo, LongSupplier clock) {
        Path playerData = server.getWorldPath(LevelResource.PLAYER_DATA_DIR);
        return new ServerPlayerDirectory(repo, name -> {
            ServerPlayer online = server.getPlayerList().getPlayerByName(name);
            return online == null ? null : online.getGameProfile();
        }, uuid -> Files.isRegularFile(playerData.resolve(uuid + ".dat")), clock);
    }

    /** 只查见过的玩家表 (GameTest 夹具用: 测试服的在线玩家与 playerdata 与被测数据无关)。 */
    public static ServerPlayerDirectory seenTableOnly(DistrictRepository repo, LongSupplier clock) {
        return new ServerPlayerDirectory(repo, name -> null, uuid -> false, clock);
    }

    @Override
    public Resolved resolve(String typed) {
        GameProfile online = lookupOnline(typed);
        if (online != null && online.getId() != null && online.getName() != null) {
            return new Resolved(online.getId(), online.getName(), true);
        }
        Optional<SeenPlayer> seen = repo.seenByNameLower(DistrictTexts.lower(typed));
        if (seen.isPresent()) {
            return new Resolved(seen.get().uuid(), seen.get().name(), true);
        }
        UUID offline = UUIDUtil.createOfflinePlayerUUID(typed);
        if (playerDataExists.test(offline)) {
            repo.insertSeenBackfill(offline, typed, clock.getAsLong());
            return new Resolved(offline, typed, true);
        }
        return new Resolved(offline, typed, false);
    }

    @Nullable
    private GameProfile lookupOnline(String typed) {
        return onlineByName.apply(typed);
    }
}
