package com.miningdim.quest.objective;

import com.miningdim.quest.QuestFacts;
import com.miningdim.quest.QuestObjective;

/**
 * 与村民完成交易 N 次。特殊任务"路过村庄"触发的默认目标。
 *
 * 一次交易记 1, 与交易内容/数量无关: 按成交物品数计会让玩家一次买 64 个物品就瞬间完成, 使"去村庄逛一圈"的
 * 设计意图落空。
 *
 * @param requiredCount 需要完成的交易次数
 */
public record VillagerTradeObjective(int requiredCount) implements QuestObjective {

    public VillagerTradeObjective {
        if (requiredCount < 1) {
            throw new IllegalArgumentException("requiredCount must be >= 1, got " + requiredCount);
        }
    }

    @Override
    public String describe() {
        return "与村民交易 x" + requiredCount;
    }

    @Override
    public int match(QuestFacts facts) {
        return facts instanceof QuestFacts.VillagerTrade ? 1 : 0;
    }
}
