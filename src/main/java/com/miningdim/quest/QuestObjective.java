package com.miningdim.quest;

/**
 * 一条任务的完成判据。纯逻辑: 吃一个 {@link QuestFacts}, 吐出本次应计入的增量。
 *
 * 无世界写入、无副作用、不持有进度 —— 进度归 {@link QuestProgress}。同一个目标实例被所有玩家共享 (定义池是
 * 静态的), 因此实现<b>必须</b>是不可变的 (record 或 final 字段), 否则会串号。
 *
 * 判据只描述"什么算数", 不描述"要几个" 之外的任何状态。需要跨事件累积的判据 (如"单弹匣内连续命中不脱靶")
 * 不适合本接口, 也刻意未纳入首批实现: 它需要每玩家瞬时状态机, 复杂度与收益不匹配 (YAGNI)。
 */
public interface QuestObjective {

    /** 完成本目标所需的累计计数 (必须 >= 1)。 */
    int requiredCount();

    /** 面向玩家的目标描述 (简体中文, 供命令行与 Web UI 直接展示)。 */
    String describe();

    /**
     * 判定一次事实应计入多少。
     *
     * @param facts 刚发生的事实
     * @return 计入增量; 0 表示与本目标无关 (绝大多数事实都是 0)。返回值恒 >= 0。
     */
    int match(QuestFacts facts);
}
