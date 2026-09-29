package com.miningdim.core.auth;

import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * "登录已确认" 的状态与监听器表 (纯状态, 由 {@link PlayerLoginGate} 驱动)。
 *
 * 为什么需要: AccessHub 的 /login 是异步的 (密码校验完成后才更新登录态), 本 mod 没有任何回调能知道"这个人刚登录了"。
 * 于是凡是被登录门拒过的在线玩家都记进 {@link #awaiting}, 由逐 tick 巡检重查; 判定一翻为放行就通知监听器
 * (平板撤掉登录提示、婚姻补发进服时压下的私密通知)。进服那一刻就已放行的玩家 (单人存档、没装 AccessHub)
 * 不进表, 直接以 atJoin=true 通知一次。
 *
 * 所有调用都在服务端主线程 (事件总线、tick、网络包 handler 经 enqueueWork 之后); 用并发容器只为巡检时迭代
 * 快照不抛 ConcurrentModificationException, 以及监听器在回调里再注册也安全。
 */
final class LoginConfirmations {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/login-gate");

    /** 被拒过、还在等判定翻为放行的在线玩家。 */
    private final Set<UUID> awaiting = ConcurrentHashMap.newKeySet();

    private final List<PlayerLoginGate.LoginConfirmedListener> listeners = new CopyOnWriteArrayList<>();

    /** 某玩家刚被拒 (任何入口的 check 返回非放行)。 */
    void await(UUID player) {
        awaiting.add(player);
    }

    /** 某玩家离线, 或已确认: 不再等。 */
    void forget(UUID player) {
        awaiting.remove(player);
    }

    boolean isAwaiting(UUID player) {
        return awaiting.contains(player);
    }

    boolean hasAwaiting() {
        return !awaiting.isEmpty();
    }

    /** 等待表快照, 供巡检遍历 (遍历中会增删)。 */
    List<UUID> awaitingSnapshot() {
        return List.copyOf(awaiting);
    }

    /** 停服时清掉等待表; 监听器是 mod 构造期注册的, 不清 (同一进程可能再开一个存档)。 */
    void clearAwaiting() {
        awaiting.clear();
    }

    PlayerLoginGate.ListenerRegistration add(PlayerLoginGate.LoginConfirmedListener listener) {
        PlayerLoginGate.LoginConfirmedListener checked = Objects.requireNonNull(listener, "listener");
        listeners.add(checked);
        return new PlayerLoginGate.ListenerRegistration(() -> listeners.remove(checked));
    }

    /**
     * 逐个通知监听器。某个监听器抛异常只记 ERROR, 不中断后面的监听器、也不往上抛:
     * 这里的调用方是服务端 tick 与进服事件, 异常冒上去就是整个服务器崩溃; 而一个监听器的 bug (比如婚姻补发失败)
     * 不该让另一个监听器 (撤掉平板的登录提示) 永远等不到这次通知 —— 翻转只发生一次, 不会重发。
     */
    void fire(ServerPlayer player, boolean atJoin) {
        for (PlayerLoginGate.LoginConfirmedListener listener : listeners) {
            try {
                listener.onLoginConfirmed(player, atJoin);
            } catch (RuntimeException e) {
                LOGGER.error("[miningdim] a login-confirmed listener failed for {} (atJoin={}); the other listeners "
                        + "still ran", player.getGameProfile().getName(), atJoin, e);
            }
        }
    }
}
