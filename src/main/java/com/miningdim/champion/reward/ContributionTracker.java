package com.miningdim.champion.reward;

import java.util.ArrayList;
import java.util.Comparator;
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
     * @return 贡献记录列表 (按首伤 tick 升序, 同 tick 按玩家 UUID 兜底保全序; 空表示无人参战)
     */
    public static List<DamageContribution> drain(UUID championId, OnlineResolver onlineResolver) {
        if (championId == null) {
            throw new IllegalArgumentException("championId must not be null");
        }
        if (onlineResolver == null) {
            throw new IllegalArgumentException("onlineResolver must not be null");
        }
        return snapshot(LEDGER.remove(championId), onlineResolver);
    }

    /**
     * 只读地取某冠军的全部贡献记录, <b>不清账</b>。输出与 {@link #drain} 逐字段一致 (同一排序、同一 online 现查)。
     *
     * 存在的理由: 同一次冠军死亡有不止一个消费者 —— 贡献池主结算 ({@code ChampionRewardHandler}) 要按份额发钱,
     * 特勤子系统要在池外叠加自己的加强奖励与悬赏推进。若两者都用 {@link #drain}, 先跑的那个会把账本抽干,
     * 后跑的直接读到空表; 而"谁先跑"取决于 Forge 同优先级下的注册先后, 是个没人能稳定推理的顺序。
     *
     * 因此约定: <b>账本的所有权归主结算</b> —— 只有 {@code ChampionRewardHandler} 调 {@link #drain}, 其余消费者
     * 一律 peek。这条约定一旦破坏, 症状是"某个奖励静默不发", 极难归因 (线上已因此让 F099 的青辉石瓜分修复
     * 空转过一轮: 特勤侧抢先 drain 后按自己那份旧逻辑按人头发, 主结算的按权重瓜分从未执行)。
     *
     * @return 贡献记录列表 (排序同 drain; 无账本返回空表)
     */
    public static List<DamageContribution> peek(UUID championId, OnlineResolver onlineResolver) {
        if (championId == null) {
            throw new IllegalArgumentException("championId must not be null");
        }
        if (onlineResolver == null) {
            throw new IllegalArgumentException("onlineResolver must not be null");
        }
        return snapshot(LEDGER.get(championId), onlineResolver);
    }

    /**
     * 把累计表快照成有序的贡献记录。
     *
     * 真排序保确定性 (F103 修复): perPlayer 是 ConcurrentHashMap, entrySet() 的遍历序是哈希桶序 —— 换一批
     * 玩家 UUID (哈希值不同) 结果就变, 而下游 {@link ContributionPool#distribute} 的 round 余数归属 (末名吸收)
     * 依赖本输出的迭代序, 不排序则"可复现性"不成立。按首伤 tick 升序; 同 tick (理论极罕见, 两玩家同 tick 首次
     * 命中) 按 UUID 兜底保全序, 不依赖排序算法稳定性。
     */
    private static List<DamageContribution> snapshot(Map<UUID, Accum> perPlayer, OnlineResolver onlineResolver) {
        List<DamageContribution> out = new ArrayList<>();
        if (perPlayer == null) {
            return out;
        }
        List<Map.Entry<UUID, Accum>> entries = new ArrayList<>(perPlayer.entrySet());
        entries.sort(Comparator
                .comparingLong((Map.Entry<UUID, Accum> e) -> e.getValue().firstHitTick)
                .thenComparing(Map.Entry::getKey));
        for (Map.Entry<UUID, Accum> e : entries) {
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
