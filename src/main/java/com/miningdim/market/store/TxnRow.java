package com.miningdim.market.store;

import java.util.UUID;

/**
 * transactions 表一行的服务端值对象 (契约第 4 节, 不可变 record)。
 *
 * 字段一一对应契约第 3 节 transactions 模式 (成交流水审计, 30 天留存): total = unitPrice*count,
 * fee = 手续费 (sink, 不发给任何人), proceeds = total-fee 由交易引擎据本行 total/fee 推导, 不单列。
 * 同时记 buyerUuid 与 sellerUuid 以支持双向历史查询 (买家视角 role=buy / 卖家视角 role=sell)。
 */
public record TxnRow(
        long id,
        long listingId,
        UUID buyerUuid,
        UUID sellerUuid,
        String itemId,
        int count,
        long unitPrice,
        long total,
        long fee,
        long createdAt) {
}
