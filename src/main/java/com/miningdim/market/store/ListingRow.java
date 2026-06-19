package com.miningdim.market.store;

import java.util.UUID;

/**
 * listings 表一行的服务端值对象 (契约第 4 节, 不可变 record)。
 *
 * 字段一一对应契约第 3 节 listings 模式:
 * itemNbt = 整个托管 ItemStack 的 NBT 序列化字节 (挂单即从卖家库存移出存这里, 撤单/未售时是物品唯一所在);
 * status = ACTIVE / SOLD / CANCELLED; unitPrice 为 long (信用点单价); createdAt = epoch millis。
 *
 * 仅承载存储层读出的原始数据, 不含业务判定 (是否过期 / 能否购买由交易引擎 B 裁决), 保持 DAO 如实反映表行。
 */
public record ListingRow(
        long id,
        UUID sellerUuid,
        String sellerName,
        String itemId,
        byte[] itemNbt,
        int count,
        long unitPrice,
        String currency,
        long createdAt,
        String status) {
}
