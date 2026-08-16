package com.miningdim.quest.objective;

import com.miningdim.quest.QuestFacts;
import com.miningdim.quest.QuestObjective;

/**
 * 击杀原版稀有变体 N 只 (小鸡骑士这类)。
 *
 * @param kind          限定的变体; <b>null 表示任意变体都算</b> —— 这是刻意留的"放宽运气面"档位: 点名单一
 *                      变体时完成与否很大程度取决于刷怪运气 (小鸡骑士约占僵尸 0.25%), 那种手感是"抽不到"
 *                      而不是"难"; 任意变体则把几条独立的低概率并联起来, 仍稀有但可预期。
 * @param requiredCount 需要击杀的只数
 */
public record SpecialMobKillObjective(SpecialMobKind kind, int requiredCount) implements QuestObjective {

    public SpecialMobKillObjective {
        if (requiredCount < 1) {
            throw new IllegalArgumentException("requiredCount must be >= 1, got " + requiredCount);
        }
    }

    /** 击杀任意稀有变体 N 只。 */
    public static SpecialMobKillObjective any(int requiredCount) {
        return new SpecialMobKillObjective(null, requiredCount);
    }

    @Override
    public String describe() {
        return "击杀 " + (kind == null ? "稀有变种怪 (任意)" : kind.displayName()) + " x" + requiredCount;
    }

    @Override
    public int match(QuestFacts facts) {
        if (!(facts instanceof QuestFacts.EntityKill kill)) {
            return 0;
        }
        if (kind == null) {
            return SpecialMobKind.matchesAny(kill.victim()) ? 1 : 0;
        }
        return kind.matches(kill.victim()) ? 1 : 0;
    }
}
