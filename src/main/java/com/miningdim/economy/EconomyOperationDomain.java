package com.miningdim.economy;

/**
 * 双币幂等操作的业务域。
 *
 * 幂等键必须连同业务域一起判定, 否则不同业务共用同一个 operationId 时会互相串号: 一笔业务的"已付款
 * 事实"会被另一笔业务当作自己的付款凭据, 从而跳过扣款。开箱的 operationId 由客户端提交, 玩家知道自己
 * 全部历史 operationId, 因此这不是理论风险 —— 只要账本里存在一条该玩家的记录而对应业务侧没有对应行,
 * 复用该 ID 即可白拿一次奖励。
 *
 * 新增业务接入双币幂等机制时必须在此登记独立取值, 严禁复用既有域。
 */
public enum EconomyOperationDomain {

    /** CS2 式开箱: 双币扣款 + SQLite 皮肤归属 Saga。 */
    CASE_OPENING;

    /** 持久化用的稳定标识; 改名等同于存档格式变更, 需同步迁移。 */
    public String id() {
        return name();
    }
}
