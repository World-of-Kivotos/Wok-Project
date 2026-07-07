package com.miningdim.market;

import com.miningdim.market.store.MarketDao;

import java.util.OptionalLong;

/**
 * 基准价值 V0 分层解析器 (偏离费 {@link MarketFee} 的锚来源, 用户设计的分层)。优先级:
 *
 *   1. admin 后台手写覆盖 ({@link MarketDao#getBaseValue}, base_values 表) —— 操纵不动的强锚, OP 逐个 curate;
 *   2. 代码内置预设 ({@link DefaultBaseValues}) —— 高价矿龙头 + 麦, 开服第一天就有;
 *   3. (后续 commit) 市场成交中位数 (带钳制) —— 长尾自税兜底;
 *   4. 空 -&gt; 调用方 ({@link MarketEngine#place}) 退平率费 (无锚的诚实兜底)。
 *
 * 持 {@link MarketDao} 取 admin 覆盖 (每次 place 一次点查, place 低频可接受)。无世界引用, 服务端逻辑层。
 */
public final class BaseValueResolver {

    private final MarketDao dao;

    public BaseValueResolver(MarketDao dao) {
        if (dao == null) {
            throw new IllegalArgumentException("BaseValueResolver requires a non-null MarketDao");
        }
        this.dao = dao;
    }

    /**
     * 解析某物品的基准价值 V0。admin 覆盖 &gt; 代码预设 &gt; 空。空表示无可信锚, 调用方按平率收费。
     *
     * @param itemId 物品注册 id
     * @return 解析到的 V0; 无锚则 {@link OptionalLong#empty()}
     */
    public OptionalLong resolve(String itemId) {
        Long override = dao.getBaseValue(itemId);
        if (override != null) {
            return OptionalLong.of(override);
        }
        return DefaultBaseValues.resolve(itemId);
    }
}
