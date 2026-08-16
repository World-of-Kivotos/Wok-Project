package com.miningdim.quest.objective;

import com.miningdim.quest.QuestFacts;
import com.miningdim.quest.QuestObjective;

/**
 * 打出 N 发子弹 (塔科夫"倾泻火力")。
 *
 * 算<b>击发</b>不算命中 (主控 2026-08-16 定, 取塔科夫原味)。判据仍然安全的理由见
 * {@link QuestFacts.GunShot} 的注释: 成本是子弹本身, 而子弹由军火商生产, 打出去就没了 —— 这条任务因此是
 * 弹药 sink 兼军火商需求源, 不是零成本可无限重复的动作。
 *
 * 代价要认: 玩家可以对着墙倒空弹匣完成它, 与实战脱钩。这是取原味的自觉取舍, 不是漏判。
 *
 * @param gunType       限定的 TaCZ 枪械分类; null 表示不限枪型
 * @param requiredCount 需要打出的发数
 */
public record AmmoSpentObjective(String gunType, int requiredCount) implements QuestObjective {

    public AmmoSpentObjective {
        if (gunType != null && gunType.isBlank()) {
            throw new IllegalArgumentException("gunType must be null (any) or a non-blank TaCZ gun type");
        }
        if (requiredCount < 1) {
            throw new IllegalArgumentException("requiredCount must be >= 1, got " + requiredCount);
        }
    }

    /** 用任意枪械打出 N 发。 */
    public static AmmoSpentObjective anyGun(int requiredCount) {
        return new AmmoSpentObjective(null, requiredCount);
    }

    @Override
    public String describe() {
        return "用" + (gunType == null ? "枪械" : GunKillObjective.localizedGunType(gunType))
                + "打出 " + requiredCount + " 发子弹";
    }

    @Override
    public int match(QuestFacts facts) {
        if (!(facts instanceof QuestFacts.GunShot shot)) {
            return 0;
        }
        if (gunType == null) {
            return 1;
        }
        // 同 GunKillObjective: 枪型未知时限定枪型的目标一律不计, 不猜。
        return gunType.equalsIgnoreCase(shot.gunType()) ? 1 : 0;
    }
}
