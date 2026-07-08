package com.miningdim.champion;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 按 tick 到期的时限标记账本 (ChampionStarAffix spec 9.3 / 红线3 与红线6; 批4 波0 服务端权威守卫)。
 *
 * 一次性记录"某 UUID 的某保护/免疫在哪个 gameTime tick 前有效", 供落地抗位移保护 (2.0s) 与大额 AOE 后 2s 伤害
 * 免疫缓冲这类"授予-查询-清扫"场景共用。故意做成实例类而非静态单例: 每个消费方 (落地保护 / 伤害免疫 / ...) 各持
 * 一份独立账本, 互不串号。
 *
 * 线程约束: 不做内部同步。所有位移/免疫写操作在 spec 中都经 server.execute 回服务端主线程, 本账本亦须只在服务端
 * 主线程读写, 与该模型一致。
 */
public final class ExpiryLedger {

    private final Map<UUID, Long> expiries = new HashMap<>();

    /**
     * 授予 id 一个到期 tick。重复授予取更晚者 —— 保护只应被延长不应被缩短 (例如连续两次击退, 第二次的落地保护
     * 更晚到期就覆盖, 更早的旧值不得把已延长的保护提前砍掉)。
     *
     * @param id         被保护实体的 UUID
     * @param expiryTick 到期 gameTime tick (该 tick 起失效)
     */
    public void grant(UUID id, long expiryTick) {
        final Long current = expiries.get(id);
        if (current == null || expiryTick > current) {
            expiries.put(id, expiryTick);
        }
    }

    /**
     * id 在 nowTick 是否仍处于保护期。半开区间: nowTick 严格小于到期 tick 才算有效 (到期 tick 当刻即失效),
     * 与 {@link #sweep(long)} 的清扫判据互补。
     *
     * @param id      查询的 UUID
     * @param nowTick 当前 gameTime tick
     * @return 是否有效 (未授予过或已过期均为 false)
     */
    public boolean isActive(UUID id, long nowTick) {
        final Long expiry = expiries.get(id);
        return expiry != null && nowTick < expiry;
    }

    /**
     * 清除所有已到期项 (nowTick 已达到或越过其到期 tick), 防账本随时间无限膨胀。判据与 {@link #isActive} 互补:
     * isActive 为 false 的项即被清 (nowTick >= expiryTick)。
     *
     * @param nowTick 当前 gameTime tick
     * @return 本次移除的项数
     */
    public int sweep(long nowTick) {
        final int before = expiries.size();
        expiries.values().removeIf(expiry -> nowTick >= expiry);
        return before - expiries.size();
    }

    /**
     * 当前账本中的项数 (含尚未清扫的过期项)。
     *
     * @return 项数
     */
    public int size() {
        return expiries.size();
    }
}
