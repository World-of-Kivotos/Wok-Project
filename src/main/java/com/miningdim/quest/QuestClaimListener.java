package com.miningdim.quest;

/**
 * 任务领奖监听 (Achievement_System_DesignSpec 9.10, 成就任务类统计与条件的来源)。
 *
 * 在 {@link QuestService#claim} 返回 {@code CLAIMED} 之前的最后一步调用: 那时奖励已发、领取标记已翻、任务线已推进、
 * 特殊任务已摘牌、存档已标脏。其余结果 (未找到、未达标、已领过) 不调用。经 {@link QuestServices#registerClaimListener}
 * 在 mod 构造期注册, 在服务端主线程上调用。监听器抛出的异常由广播方记录后吞掉, 不影响已经完成的领奖;
 * 监听器仍应自行捕获并记录自己的失败。
 */
@FunctionalInterface
public interface QuestClaimListener {

    /** 一次领奖已完成。 */
    void onQuestClaimed(QuestClaim claim);
}
