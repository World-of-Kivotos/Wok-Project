package com.miningdim.job.tarot;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 恋人闪耀绑定的 "需对方同意" 握手 (TarotReader spec 第六章恋人闪耀: "绑定 1 玩家(需同意)")。
 *
 * 共享生死是会致死对方的强机制, 故绑定前对方必须主动同意: 目标玩家敲 {@code /tarot consent} 开一个短同意窗
 * ({@link #CONSENT_WINDOW_TICKS}); 发起方在窗内用恋人闪耀锁定该玩家即成功绑定, 并立即消费 (一次性, 防同一同意
 * 被多次绑定)。无同意窗或已过期则不绑定 (签名空过 + 提示发起方)。
 *
 * 时钟用 {@link MinecraftServer#getTickCount()} (与 {@link TarotCombatState} 同一全局时钟)。仅服务端命令线程 +
 * tick 主线程访问, ConcurrentHashMap 防并发读写。登出经 {@link #clear} 清理 (防离线残留同意被利用)。
 */
public final class TarotConsentRegistry {

    /** 同意窗时长 (ticks); 10s 给发起方锁定的操作余量。 */
    public static final int CONSENT_WINDOW_TICKS = 200;

    /** playerId -> 同意窗结束 tick (该 tick 前发起方锁定即视为已同意)。 */
    private static final Map<UUID, Long> CONSENTS = new ConcurrentHashMap<>();

    private TarotConsentRegistry() {
    }

    /** 玩家敲 {@code /tarot consent}: 开一个 {@link #CONSENT_WINDOW_TICKS} 的同意窗 (覆盖刷新)。 */
    public static void grantConsent(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            throw new IllegalStateException("ServerPlayer has no MinecraftServer (consent clock unavailable)");
        }
        CONSENTS.put(player.getUUID(), (long) server.getTickCount() + CONSENT_WINDOW_TICKS);
    }

    /**
     * 消费某玩家的同意 (绑定成功时调用): 若该玩家有未过期同意窗则消费并返回 true (一次性: 移除窗), 否则返回 false。
     */
    public static boolean consume(UUID playerId, long now) {
        Long endTick = CONSENTS.get(playerId);
        if (endTick == null || now >= endTick) {
            return false;
        }
        CONSENTS.remove(playerId);
        return true;
    }

    /** 某玩家当前是否有未过期同意窗 (只读, 不消费; 诊断/测试用)。 */
    public static boolean hasConsent(UUID playerId, long now) {
        Long endTick = CONSENTS.get(playerId);
        return endTick != null && now < endTick;
    }

    /** 清某玩家同意窗 (登出防残留)。 */
    public static void clear(UUID playerId) {
        CONSENTS.remove(playerId);
    }

    /** tick: 移除过期同意窗 (防内存累积)。 */
    public static void tick(MinecraftServer server) {
        long now = server.getTickCount();
        CONSENTS.entrySet().removeIf(e -> now >= e.getValue());
    }

    /** 测试钩子: 直接注入一个同意窗结束 tick (绕过 ServerPlayer 时钟依赖; 仅同包可见)。 */
    static void injectConsentForTest(UUID playerId, long endTick) {
        CONSENTS.put(playerId, endTick);
    }
}
