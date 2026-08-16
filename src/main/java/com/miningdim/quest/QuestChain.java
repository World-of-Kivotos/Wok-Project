package com.miningdim.quest;

import java.util.List;

/**
 * 一条多阶段隐藏任务线的静态定义 (如仿塔科夫神射手线)。
 *
 * 任务线的<b>解锁条件不在本类里</b>: 解锁判定往往依赖可选 mod (神射手线要判"背包里有狙击枪", 需要 TaCZ 的
 * 资源索引才能解析枪械分类), 而核心层必须在 TaCZ 缺失时照常 classload。因此解锁由各自的边界层
 * ({@link QuestTaczHooks}) 判定后调 {@code QuestService.unlockChain}, 本类只描述"解锁之后按什么顺序打"。
 *
 * @param id     任务线唯一标识 (存档持久化本 id, 发布后不可改名)
 * @param title  面向玩家的任务线名
 * @param stages 按顺序排列的阶段, 至少一个。玩家领完第 N 阶段的奖励后自动进入第 N+1 阶段。
 */
public record QuestChain(String id, String title, List<QuestDefinition> stages) {

    public QuestChain {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("chain id must not be blank");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("chain title must not be blank for chain " + id);
        }
        if (stages == null || stages.isEmpty()) {
            throw new IllegalArgumentException("chain " + id + " must declare at least one stage");
        }
        stages = List.copyOf(stages);
        for (QuestDefinition stage : stages) {
            if (stage.source() != QuestSource.HIDDEN) {
                // 阶段来源写错会让它被日常/周常的翻转逻辑当成可重抽任务清掉, 任务线永远走不完。
                throw new IllegalArgumentException("chain " + id + " stage " + stage.id()
                        + " must declare source HIDDEN, got " + stage.source());
            }
        }
    }

    public int stageCount() {
        return stages.size();
    }
}
