package com.miningdim.market;

import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * 一笔已提交的市场成交 (交给 {@link TradeListener} 的只读事实)。
 *
 * @param buyer  买家 (成交发生时一定在线, 由他发起购买)
 * @param seller 卖家 UUID (卖家可能离线)
 * @param itemId 标的物品 id
 * @param count  本次买入数量
 * @param total  成交额 (信用点, 卖家实收全额)
 */
public record MarketTrade(ServerPlayer buyer, UUID seller, String itemId, int count, long total) {
}
