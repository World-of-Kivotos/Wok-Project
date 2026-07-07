package com.miningdim.marriage;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 共享背包打开会话登记表 (结婚系统 spec 第四章防 dupe: 服务端唯一权威容器 + 掉线强制结算关闭)。进程级单例,
 * 仅服务端主线程访问 (远程开/关/登出回调均主线程, 与 InstanceManager 同纪律)。
 *
 * 两职责:
 *  1. 每 marriageId 复用同一个 {@link MarriageBackpackContainer} 实例 —— 双方各开/同方多开都操作同一份
 *     {@link MarriageState#sharedInv}, 这是"服务端唯一权威 Container"的落地 (杜绝多实例各自镜像导致 dupe)。
 *  2. 记录每个 marriageId 当前有几个打开窗口 (引用计数 + 打开者集合): 任一方登出/掉线时, 强制关闭该 marriageId
 *     的所有打开窗口 (spec 第四章: 打开期间任一方掉线立即强制结算并关闭双方界面, 防并发 dupe)。
 *
 * "强制结算"在本权威容器模型下即"关闭窗口" —— 因内容自始至终只有 sharedInv 一份, 关窗即停止一切对它的并发操作,
 * 无需额外回收物品 (没有任何离线副本可对账)。关窗用 {@link ServerPlayer#closeContainer()} 让原版正确清理双方 menu。
 */
public final class MarriageBackpackSessions {

    /** 单 marriageId 的会话态: 共享容器单例 + 当前打开此背包的玩家集合。 */
    private static final class Session {
        final MarriageBackpackContainer container;
        final java.util.Set<java.util.UUID> openers = new java.util.HashSet<>();

        Session(MarriageBackpackContainer container) {
            this.container = container;
        }
    }

    private final Map<Long, Session> sessions = new HashMap<>();

    /**
     * 取/建某关系的共享容器单例 (打开背包时调)。首次为该 marriageId 建容器并缓存; 后续复用同一实例。
     *
     * @param state    关系状态 (内容落点)
     * @param registry 关系注册表 (容器标脏用)
     */
    public MarriageBackpackContainer containerFor(MarriageState state, MarriageRegistry registry) {
        Session session = sessions.computeIfAbsent(state.marriageId(),
                id -> new Session(new MarriageBackpackContainer(state, registry)));
        return session.container;
    }

    /** 登记一个打开者 (玩家成功打开共享背包后调)。 */
    public void onOpened(long marriageId, ServerPlayer player) {
        Session session = sessions.get(marriageId);
        if (session != null) {
            session.openers.add(player.getUUID());
        }
    }

    /** 注销一个打开者 (玩家正常关闭窗口后调; 集合空但容器实例保留, 供下次开复用同一权威容器)。 */
    public void onClosed(long marriageId, ServerPlayer player) {
        Session session = sessions.get(marriageId);
        if (session != null) {
            session.openers.remove(player.getUUID());
        }
    }

    /**
     * 某玩家登出/掉线时强制关闭其所在关系的所有共享背包窗口 (spec 第四章防并发 dupe)。无论登出者是否正开着背包,
     * 只要其配偶可能正开着同一 marriageId 的背包, 都一并关 —— 双方界面同时关闭, 杜绝"一方掉线另一方继续操作"的竞态窗口。
     *
     * @param marriageId 登出者所在关系 id (未婚传 {@link com.miningdim.entry.IMiningPlayerData#NO_MARRIAGE} 即 no-op)
     * @param overworld  用于经 PlayerList 按 UUID 取在线玩家关窗
     */
    public void forceCloseAll(long marriageId, ServerLevel overworld) {
        if (marriageId < 0L) {
            return;
        }
        Session session = sessions.get(marriageId);
        if (session == null || session.openers.isEmpty()) {
            return;
        }
        // 关窗会回调 onClosed 改 openers, 故先快照集合再迭代 (避免并发修改)。
        for (java.util.UUID opener : new java.util.ArrayList<>(session.openers)) {
            ServerPlayer p = overworld.getServer().getPlayerList().getPlayer(opener);
            if (p != null) {
                // closeContainer 触发原版 menu.removed -> 本子系统 onClosed 注销; 双方各自的窗口都被关。
                p.closeContainer();
            }
        }
        // 兜底: 离线/取不到的打开者直接从集合移除 (其 menu 随登出已被原版清理)。
        session.openers.clear();
    }

    /** 关系解除 (离婚) 时清掉其会话 (容器实例与打开集合一并丢弃, 防陈旧引用)。调用方须已先关窗。 */
    public void onMarriageDissolved(long marriageId) {
        sessions.remove(marriageId);
    }

    /** 服务端停止清空 (防跨存档脏引用; 与 SealRegistry.reset 同纪律)。 */
    public void reset() {
        sessions.clear();
    }

    /** 当前打开某 marriageId 背包的窗口数 (测试/诊断用)。 */
    public int openerCount(long marriageId) {
        Session session = sessions.get(marriageId);
        return session == null ? 0 : session.openers.size();
    }

    /** 移除所有无人打开的会话缓存 (周期清理可选; 当前不主动调, 保留为诊断/GC 接缝)。 */
    public void pruneIdle() {
        Iterator<Map.Entry<Long, Session>> it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().openers.isEmpty()) {
                it.remove();
            }
        }
    }
}
