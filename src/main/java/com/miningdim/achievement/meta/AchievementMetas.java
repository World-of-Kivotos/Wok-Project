package com.miningdim.achievement.meta;

import net.minecraft.resources.ResourceLocation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 当前生效的成就元数据。{@link AchievementMetaLoader} 在每次数据包 (重新) 加载时整表替换; 读取方只拿到不可变快照。
 *
 * 用实例而不是全局静态表: GameTest 可以各建一份互不干扰的元数据, 不必改写服务端真正生效的那一份。
 */
public final class AchievementMetas {

    private volatile Map<ResourceLocation, AchievementMeta> byId = Map.of();

    /** 整表替换 (数据包加载线程即服务端主线程调用)。 */
    public void install(Map<ResourceLocation, AchievementMeta> loaded) {
        this.byId = Collections.unmodifiableMap(new LinkedHashMap<>(loaded));
    }

    /** 服务端停止时清空, 防跨存档沿用上一个世界的元数据。 */
    public void clear() {
        install(Map.of());
    }

    /** 当前全部元数据 (按 id 排序的不可变快照)。 */
    public Map<ResourceLocation, AchievementMeta> snapshot() {
        return byId;
    }

    public int size() {
        return byId.size();
    }
}
