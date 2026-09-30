package com.miningdim.district.service;

import com.miningdim.district.store.DistrictRepository;
import com.miningdim.store.StoreMeta;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * 老玩家回填 (设计文档 10.3): 本模块上线前就进过服的玩家要回填进见过的玩家表, 否则会被误判成"从没进过服"。
 * 只做一次, 以 StoreMeta 键 {@link #META_KEY} 作标记; 标记与回填的行同一个事务提交。
 *
 * 做法: 遍历 playerdata 目录下的 *.dat, 从文件名解析 UUID, 用只查本地的名字查询 (GameProfileCache.get(UUID)) 取名字,
 * 取到就插入一行 backfill, 首次与最后在线时间都取文件的修改时间。取不到名字的玩家跳过: TA 下次登录时会被记上;
 * 在那之前, 按名字解析的第 3 步 (playerdata 兜底) 也能认出 TA。
 */
public final class SeenPlayerBackfill {

    public static final String META_KEY = "district.seenBackfill.v1";

    /** alreadyDone = 标记已在, 这次什么都没做。 */
    public record Result(boolean alreadyDone, int inserted) {
    }

    private SeenPlayerBackfill() {
    }

    /**
     * @param playerDataDir 存档的 playerdata 目录 (不存在时只写标记)
     * @param names         只查本地的 UUID -&gt; 名字
     */
    public static Result runOnce(DistrictRepository repo, Path playerDataDir, Function<UUID, Optional<String>> names,
                                 long now) {
        return repo.inTransaction(() -> {
            if (StoreMeta.get(repo.connection(), META_KEY) != null) {
                return new Result(true, 0);
            }
            int inserted = 0;
            if (Files.isDirectory(playerDataDir)) {
                try (DirectoryStream<Path> files = Files.newDirectoryStream(playerDataDir, "*.dat")) {
                    for (Path file : files) {
                        String fileName = file.getFileName().toString();
                        UUID uuid;
                        try {
                            uuid = UUID.fromString(fileName.substring(0, fileName.length() - ".dat".length()));
                        } catch (IllegalArgumentException notAPlayerFile) {
                            continue;
                        }
                        Optional<String> name = names.apply(uuid);
                        if (name.isEmpty() || name.get().isBlank()) {
                            continue;
                        }
                        long modified = Files.getLastModifiedTime(file).toMillis();
                        if (repo.insertSeenBackfill(uuid, name.get(), modified)) {
                            inserted++;
                        }
                    }
                } catch (IOException exception) {
                    throw new UncheckedIOException("scanning " + playerDataDir + " for the seen-player backfill failed",
                            exception);
                }
            }
            StoreMeta.put(repo.connection(), META_KEY, String.valueOf(now));
            return new Result(false, inserted);
        });
    }
}
