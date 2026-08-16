package com.miningdim.quest;

/**
 * 一条任务的静态定义 (内容, 非进度)。定义池是全服共享的不可变数据, 每个玩家的进度另见 {@link QuestProgress}。
 *
 * <b>定义里刻意不含奖励金额。</b> 奖励由 {@link QuestRewards} 按 {@code source + difficulty} 从配置算出 ——
 * 把数值从内容里剥离有两个理由: 一是经济尚未完成全局净流入核对 (见 docs/Economy_Completeness_Audit.md,
 * 信用点 sink 当前全线失效), 奖励数值必须能在一个 TOML 文件里集中调而不是散落在几十条定义里; 二是内容池
 * 将来要扩到上百条, 逐条写死金额必然漂移出不一致的档位。
 *
 * @param id         全局唯一稳定标识。<b>进度存档只持久化本 id</b>, 重启后由 {@link QuestPool} 反查定义, 因此
 *                   一旦发布就不可改名 —— 改名等于让所有在途进度失配被丢弃。
 * @param source     来源 (决定周期与可否重摇)
 * @param title      面向玩家的任务名 (简体中文)
 * @param objective  完成判据
 * @param difficulty 难度档 (1 = 顺手可完成, 2 = 需要专门跑一趟, 3 = 硬指标)。仅用于算奖励, 不影响判定。
 */
public record QuestDefinition(String id, QuestSource source, String title, QuestObjective objective, int difficulty) {

    /** 难度档上限; 超出即定义写错 (奖励曲线只按 1-3 档标定)。 */
    public static final int MAX_DIFFICULTY = 3;

    public QuestDefinition {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("quest id must not be blank");
        }
        if (source == null) {
            throw new IllegalArgumentException("quest source must not be null for quest " + id);
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("quest title must not be blank for quest " + id);
        }
        if (objective == null) {
            throw new IllegalArgumentException("quest objective must not be null for quest " + id);
        }
        if (difficulty < 1 || difficulty > MAX_DIFFICULTY) {
            throw new IllegalArgumentException(
                    "difficulty must be within 1.." + MAX_DIFFICULTY + " for quest " + id + ", got " + difficulty);
        }
    }
}
