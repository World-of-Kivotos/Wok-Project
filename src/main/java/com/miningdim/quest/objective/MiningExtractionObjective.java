package com.miningdim.quest.objective;

import com.miningdim.core.Difficulty;
import com.miningdim.quest.QuestFacts;
import com.miningdim.quest.QuestObjective;

/**
 * 从矿洞成功撤离 N 次 (塔科夫"撤离点")。
 *
 * "成功"的定义是<b>主动经出口离开且本次进入未死亡</b> —— 死亡被抬出去、掉线、传送走都不算。这条判据的全部
 * 意义就在这个区分上: 它衡量的是"进得去也出得来"。
 *
 * 停留门槛不在本类里判, 由事件层在发出 {@link QuestFacts.MiningExtraction} 之前统一过 (配置项
 * {@code extractionMinDwellTicks})。原因是那道门是<b>反刷</b>用的系统级红线 —— 进洞立刻出来是零成本, 不设门
 * 这条判据就退化成可秒刷的无限动作。系统级红线只该有一处判据, 不该由每条任务各写各的。
 * 本类的 {@link #minDwellTicks} 是在系统红线之上<b>再加严</b>的内容级要求 (如"单次驻留 20 分钟以上的撤离"),
 * 默认 0 表示不额外加严。
 *
 * @param difficulty    限定的矿洞难度档; <b>null 表示任意难度</b>
 * @param minDwellTicks 本条任务额外要求的单次最短停留 (tick); 0 表示只走系统红线
 * @param requiredCount 需要成功撤离的次数
 */
public record MiningExtractionObjective(Difficulty difficulty, long minDwellTicks, int requiredCount)
        implements QuestObjective {

    public MiningExtractionObjective {
        if (minDwellTicks < 0) {
            throw new IllegalArgumentException("minDwellTicks must be >= 0, got " + minDwellTicks);
        }
        if (requiredCount < 1) {
            throw new IllegalArgumentException("requiredCount must be >= 1, got " + requiredCount);
        }
    }

    /** 从任意难度的矿洞撤离 N 次。 */
    public static MiningExtractionObjective any(int requiredCount) {
        return new MiningExtractionObjective(null, 0, requiredCount);
    }

    /** 从指定难度的矿洞撤离 N 次。 */
    public static MiningExtractionObjective of(Difficulty difficulty, int requiredCount) {
        return new MiningExtractionObjective(difficulty, 0, requiredCount);
    }

    @Override
    public String describe() {
        String where = difficulty == null ? "矿洞" : localizedDifficulty(difficulty) + "矿洞";
        String dwell = minDwellTicks > 0 ? " (单次驻留 " + (minDwellTicks / 20L / 60L) + " 分钟以上)" : "";
        return "从" + where + "成功撤离 x" + requiredCount + dwell;
    }

    @Override
    public int match(QuestFacts facts) {
        if (!(facts instanceof QuestFacts.MiningExtraction extraction)) {
            return 0;
        }
        if (difficulty != null && extraction.difficulty() != difficulty) {
            return 0;
        }
        return extraction.dwellTicks() >= minDwellTicks ? 1 : 0;
    }

    private static String localizedDifficulty(Difficulty difficulty) {
        return switch (difficulty) {
            case EASY -> "初级";
            case MEDIUM -> "中级";
            case HARD -> "高级";
        };
    }
}
