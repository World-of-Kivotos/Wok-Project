package com.miningdim.client.title;

import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * 客户端名牌称号缓存 {@code entityId -> 称号 Component}, 由 {@code S2CTitleSync} 写入、{@link TitleNameTagRenderer}
 * 读取。清理时机见 {@link TitleNameTagRenderer}: 实体离开世界、客户端世界卸载 (断线/换维度)、登出。
 *
 * 全部访问都在客户端主线程 (网络包经 enqueueWork 切回主线程, 渲染与实体事件本就在主线程), 因此用普通 HashMap,
 * 不引入 ConcurrentHashMap 假装并发。
 */
public final class TitleClientCache {

    private static final Map<Integer, Component> TITLE_BY_ENTITY = new HashMap<>();

    private TitleClientCache() {
    }

    /** 收到同步包: title 为 null 表示该实体不再佩戴称号。 */
    public static void accept(int entityId, @Nullable Component title) {
        if (title == null) {
            TITLE_BY_ENTITY.remove(entityId);
        } else {
            TITLE_BY_ENTITY.put(entityId, title);
        }
    }

    @Nullable
    public static Component get(int entityId) {
        return TITLE_BY_ENTITY.get(entityId);
    }

    public static void remove(int entityId) {
        TITLE_BY_ENTITY.remove(entityId);
    }

    public static void clear() {
        TITLE_BY_ENTITY.clear();
    }
}
