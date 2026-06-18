package com.miningdim.champion.reward;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每冠军贡献累计器纯逻辑 (ChampionStarAffix spec 第十一章奖励与经济闸: 击杀/伤害事件累计贡献)。
 *
 * 维护 championUUID -> (playerUUID -> 累计有效伤害 + 首伤 tick) 的服务端账本。受击 handler 在玩家对冠军造成
 * 有效伤害时 {@link #record} 累加 (召唤物伤害已在归因层排除, 不进本表); 冠军死亡时 {@link #drain} 取出全部
 * 贡献记录交给 {@link ContributionPool#distribute} 盖章瓜分, 并清该冠军账本。
 *
 * 纯逻辑层: 只持 UUID + double, 不碰 ServerPlayer/IEconomyService/Champions, dev GameTest 触达安全。
 * online 在 drain 时由 handler 现查 (玩家可能中途登出), 故本表只存伤害+首伤 tick, 不存 online 快照。
 *
 * 线程纪律: 累加/结算只在服务端主线程 (受击/死亡串行); ConcurrentHashMap 仅防跨线程读可见性。
 */
public final class ContributionTracker {

    private ContributionTracker() {
    }

    /** championUUID -> (playerUUID -> 累计态)。 */
    private static final ConcurrentHashMap<UUID, Map<UUID, Accum>> LEDGER = new ConcurrentHashMap<>();

    /** 单玩家对单冠军的累计态 (累计有效伤害 + 首次有效伤害 tick)。 */
    private static final class Accum {
        double effectiveDamage;
        final long firstHitTick;

        Accum(long firstHitTick) {
            this.firstHitTick = firstHitTick;
        }
    }

    /**
     * 累加一笔玩家对冠军的有效伤害 (受击 handler 在归因到玩家来源、排除召唤物后调)。
     *
     * @param championId      冠军实体 UUID
     * @param playerId        造成伤害的玩家 UUID
     * @param effectiveDamage 本次有效伤害 (净伤; 必须 &gt;=0; ≤0 不计)
     * @param nowTick         当前 gameTime (首伤记此 tick)
     */
    public static void record(UUID championId, UUID playerId, double effectiveDamage, long nowTick) {
        if (championId == null || playerId == null) {
            throw new IllegalArgumentException("championId/playerId must not be null");
        }
        if (effectiveDamage <= 0.0D || Double.isNaN(effectiveDamage)) {
            return; // 0/负伤不计 (无效贡献)。
        }
        Map<UUID, Accum> perPlayer = LEDGER.computeIfAbsent(championId, id -> new ConcurrentHashMap<>());
        Accum accum = perPlayer.computeIfAbsent(playerId, id -> new Accum(nowTick));
        accum.effectiveDamage += effectiveDamage;
    }

    /**
     * 取出某冠军的全部贡献记录并清账 (冠军死亡结算时调)。online 由调用方 (handler) 现查注入 (玩家可能登出)。
     *
     * @param championId     冠军 UUID
     * @param onlineResolver 现查某玩家是否在线 (handler 经 server.getPlayerList 判)
     * @return 贡献记录列表 (按首次记录顺序; 空表示无人参战)
     */
    public static List<DamageContribution> drain(UUID championId, OnlineResolver onlineResolver) {
        if (championId == null) {
            throw new IllegalArgumentException("championId must not be null");
        }
        if (onlineResolver == null) {
            throw new IllegalArgumentException("onlineResolver must not be null");
        }
        Map<UUID, Accum> perPlayer = LEDGER.remove(championId);
        List<DamageContribution> out = new ArrayList<>();
        if (perPlayer == null) {
            return out;
        }
        // 保持稳定迭代序: LinkedHashMap 重建 (ConcurrentHashMap 无序; 用首伤 tick 排序保确定性)。
        Map<UUID, Accum> ordered = new LinkedHashMap<>(perPlayer);
        for (Map.Entry<UUID, Accum> e : ordered.entrySet()) {
            UUID playerId = e.getKey();
            Accum accum = e.getValue();
            boolean online = onlineResolver.isOnline(playerId);
            out.add(new DamageContribution(playerId, accum.effectiveDamage, accum.firstHitTick, online));
        }
        return out;
    }

    /** 某冠军是否已有贡献记录 (诊断/测试用)。 */
    public static boolean hasLedger(UUID championId) {
        return championId != null && LEDGER.containsKey(championId);
    }

    /** 丢弃某冠军账本 (实例重置定向清除冠军时调, 不结算)。 */
    public static void discard(UUID championId) {
        if (championId != null) {
            LEDGER.remove(championId);
        }
    }

    /** 服务端停止清空, 防跨存档脏引用。 */
    public static void reset() {
        LEDGER.clear();
    }

    /** 在线判定回调 (handler 注入, 解耦纯逻辑层对 server/playerList 的依赖)。 */
    @FunctionalInterface
    public interface OnlineResolver {
        boolean isOnline(UUID playerId);
    }
}
