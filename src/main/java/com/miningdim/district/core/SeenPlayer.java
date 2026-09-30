package com.miningdim.district.core;

import java.util.UUID;

/** 进过服的玩家一行: "有没有进过服"与最后在线时间的唯一来源。source 为 login 或 backfill。 */
public record SeenPlayer(UUID uuid, String name, long firstSeenAt, long lastSeenAt, String source) {
}
