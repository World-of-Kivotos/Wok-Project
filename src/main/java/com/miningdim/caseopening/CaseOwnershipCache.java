package com.miningdim.caseopening;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 开枪归属判定的进程内正向缓存 (按玩家/资产二维索引)。
 *
 * 三条设计前提 (均为取证事实, 不是随意选择):
 * a) 只缓存正向结论, 反向结论一律不缓存 —— 一是刚结算完的资产必须立刻可用而不是等重登, 二是
 *    assetId 来自客户端可控的物品 NBT, 缓存反向结论等于给了任意 UUID 灌满内存的通道;
 * b) 正向结论一旦成立就是永久事实, 因为 skin_assets 全仓只有 INSERT (CaseDaoSqlite.insertAsset)
 *    没有 UPDATE owner_uuid 也没有 DELETE, 而 case_openings.economy_settled 只被 markEconomySettled
 *    单向写 1;
 * c) 将来若新增皮肤转让/回收/交易路径, 必须在那条路径上调 forget, 否则本缓存会放行已转出的资产。
 *
 * 全部调用点都在服务端主线程 (TACZ 事件 / PlayerTickEvent / 登录登出), 因此用普通 HashMap
 * 即可, 不引入 ConcurrentHashMap 假装并发。
 */
final class CaseOwnershipCache {

    record Grant(String displayId, String gunId) {
        Grant {
            Objects.requireNonNull(displayId, "displayId");
            Objects.requireNonNull(gunId, "gunId");
        }
    }

    @FunctionalInterface
    interface Lookup {
        Grant find(UUID ownerId, UUID assetId);
    }

    private final Map<UUID, Map<UUID, Grant>> byOwner = new HashMap<>();

    Grant get(UUID ownerId, UUID assetId) {
        Map<UUID, Grant> assets = byOwner.get(ownerId);
        return assets == null ? null : assets.get(assetId);
    }

    void put(UUID ownerId, UUID assetId, Grant grant) {
        byOwner.computeIfAbsent(ownerId, key -> new HashMap<>()).put(assetId, grant);
    }

    void forget(UUID ownerId) {
        byOwner.remove(ownerId);
    }

    int size() {
        int total = 0;
        for (Map<UUID, Grant> assets : byOwner.values()) {
            total += assets.size();
        }
        return total;
    }
}
