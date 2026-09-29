package com.miningdim.core.auth;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录结果与连接的核对 (防御性检查; 纯状态机, 不碰 AccessHub, 由 {@link PlayerLoginGate} 驱动)。
 *
 * AccessHub 的 /login 是异步的: 密码校验完成后才更新登录态。本类把登录结果与发出 /login 的那条连接核对,
 * 发出 /login 的连接已经断开时, 这次登录结果不算给同一 UUID 的其它连接, 由调用方撤销 (clearSession) 并要求重新 /login。
 *
 * 做法: 记下每个玩家最近一次在未登录时发出 /login 的连接; 这条连接若在 {@link #WINDOW_TICKS} 内断开, 就把这次
 * /login 记为"孤儿"。孤儿存活期间, 同一 UUID 的<b>另一条</b>连接若被 AccessHub 报告为已登录, 核对不通过。
 * 新连接上再发一次 /login 不会清除这条记录: 核对只认发出那次 /login 的连接。
 *
 * 代价: 玩家在 /login 后不久断线重连并完成登录, 可能被要求再登录一次。
 *
 * 连接用 {@code Object} 按引用比较 (生产里是 {@code net.minecraft.network.Connection}), 时间用服务端 tick 数:
 * 登录结果在服务端主线程写入, 服务端卡顿时写入同样推迟, 用 tick 计的窗口会随之拉长, 而墙钟窗口可能在卡顿里过期。
 *
 * 所有调用都在服务端主线程 (命令、进出服、tick、网络包 handler 经 enqueueWork 之后); 用 ConcurrentHashMap 只为
 * 迭代快照时不抛 ConcurrentModificationException, 不是为跨线程。
 */
final class LoginRaceGuard {

    /**
     * /login 发出后, 登录结果仍可能写入的最长时间 (10 秒 @ 20 TPS)。密码校验正常远短于此; 余量留给线程池排队。
     */
    static final long WINDOW_TICKS = 200L;

    private record Pending(Object connection, long issuedAt) {
    }

    private record Orphan(Object issuer, long deadline) {
    }

    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final Map<UUID, Orphan> orphans = new ConcurrentHashMap<>();

    /** 某玩家在<b>未登录</b>时从 {@code connection} 发出了 /login (或其别名)。 */
    void loginIssued(UUID player, Object connection, long now) {
        pending.put(Objects.requireNonNull(player, "player"),
                new Pending(Objects.requireNonNull(connection, "connection"), now));
    }

    /** 某玩家的 {@code connection} 断开了。它若在窗口内发过 /login, 那次 /login 成为孤儿。 */
    void left(UUID player, Object connection, long now) {
        Pending issued = pending.get(player);
        if (issued == null || issued.connection() != connection) {
            return;
        }
        pending.remove(player, issued);
        long deadline = issued.issuedAt() + WINDOW_TICKS;
        if (now <= deadline) {
            orphans.put(player, new Orphan(connection, deadline));
        }
    }

    /**
     * 此刻若 AccessHub 报告该玩家已登录, 这份登录态是否可能来自一次孤儿 /login (即: 孤儿仍在窗口内, 且当前连接
     * 不是发出那次 /login 的连接)。过期的孤儿顺手清掉。
     */
    boolean suspects(UUID player, Object connection, long now) {
        Orphan orphan = orphans.get(player);
        if (orphan == null) {
            return false;
        }
        if (now > orphan.deadline()) {
            orphans.remove(player, orphan);
            return false;
        }
        return orphan.issuer() != connection;
    }

    /** 已据孤儿撤销过一次: 那次 /login 的结果最多写入一次, 不再追究。 */
    void resolved(UUID player) {
        orphans.remove(player);
    }

    boolean hasOrphans() {
        return !orphans.isEmpty();
    }

    /** 清掉过期孤儿后, 仍在窗口内的孤儿所属玩家 (快照, 供逐 tick 巡检)。 */
    List<UUID> liveOrphans(long now) {
        orphans.values().removeIf(orphan -> now > orphan.deadline());
        return List.copyOf(orphans.keySet());
    }

    /** 停服时清空 (单人存档可以反复开关同一进程里的服务端)。 */
    void clear() {
        pending.clear();
        orphans.clear();
    }
}
