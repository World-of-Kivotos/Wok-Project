package com.miningdim.caseopening;

import java.util.UUID;

/**
 * 一次已结算的开箱 (结算锚 economy_settled 已落定: 双币已扣到 COMPLETED、皮肤资产已归属玩家)。
 *
 * @param openingId 开箱事务 id
 * @param ownerId   开箱人
 * @param rarity    开出的品质
 * @param fresh     是否是刚刚完成的这一次正常开箱: 结算与扣款落在同一个事务里, 提交后读到的余额就是"开箱后余额"。
 *                  恢复流程补结算的 (登录恢复、提交结果不明后的对账、启动期对账) 与只读查询给出的历史记录一律为 false
 */
public record SettledOpening(UUID openingId, UUID ownerId, CaseRarity rarity, boolean fresh) {
}
