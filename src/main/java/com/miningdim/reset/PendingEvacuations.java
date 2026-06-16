package com.miningdim.reset;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 待撤离玩家标记 (设计文档 13.3 markPendingEvacuation / 14.6 情况 A)。当实例重置/销毁时, 在场集合里
 * 的离线玩家无在线实体可传送, 标记于此; 其下次登录由登录恢复逻辑 (entry 子系统 14.6) 检出并送回回退点,
 * 然后 {@link #clear} 清标记。
 *
 * 由 reset 子系统写标记, entry 子系统读/清 —— 经本静态门面协作, 不互相 import 实现类 (模块化铁律 2)。
 * 仅存 UUID, 进程内瞬态 (不持久化): 关服时实例视图重建会重新判定, 见 14.6 关键校验。
 */
public final class PendingEvacuations {

    private PendingEvacuations() {
    }

    private static final Set<UUID> MARKED = ConcurrentHashMap.newKeySet();

    /** 标记某离线玩家待撤离 (13.3)。 */
    public static void mark(UUID playerId) {
        MARKED.add(playerId);
    }

    /** 该玩家是否被标记待撤离 (14.6 情况 A)。 */
    public static boolean isMarked(UUID playerId) {
        return MARKED.contains(playerId);
    }

    /** 清除标记 (送回回退点后调用)。 */
    public static void clear(UUID playerId) {
        MARKED.remove(playerId);
    }
}
