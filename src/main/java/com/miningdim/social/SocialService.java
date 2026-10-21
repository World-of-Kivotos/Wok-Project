package com.miningdim.social;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;

/**
 * 社交子系统的进程级服务定位器。服务端启动时加载，关闭时落盘；WebUI action 在运行期经
 * {@link #store()} 取同一个仓库。测试可经 {@link #installForTests(SocialStore)} 注入临时仓库。
 */
public final class SocialService {

    private static SocialStore store;

    private SocialService() {
    }

    public static void load(MinecraftServer server) {
        Path file = server.getWorldPath(LevelResource.ROOT).resolve("wok_social.json");
        SocialStore loaded = new SocialStore(file);
        loaded.load();
        store = loaded;
    }

    public static boolean isLoaded() {
        return store != null;
    }

    public static SocialStore store() {
        SocialStore current = store;
        if (current == null) {
            throw new IllegalStateException("social store is not loaded");
        }
        return current;
    }

    public static void stop() {
        SocialStore current = store;
        if (current != null) {
            current.save();
            store = null;
        }
    }

    /**
     * 成就系统接入点：解锁一个非默认主题。只允许服务端成就结算调用，不暴露成 WebUI action；
     * 重复调用幂等。
     */
    public static void unlockTheme(java.util.UUID player, String themeId) {
        store().unlockTheme(player, themeId);
    }

    /** GameTest / 离线验收入口；传 null 清除。 */
    public static void installForTests(SocialStore testStore) {
        store = testStore;
    }
}
