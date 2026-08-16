package com.miningdim.quest.objective;

import com.miningdim.quest.QuestFacts;
import com.miningdim.quest.QuestObjective;
import net.minecraft.world.entity.EntityType;

/**
 * 击杀指定种类生物 N 只。
 *
 * 只认 {@link QuestFacts.EntityKill} —— 它由原版 {@code LivingDeathEvent} 产生, 与武器无关, 因此用枪打死的
 * 僵尸同样计入 (枪械专属判据另见 {@link GunKillObjective})。
 *
 * @param target        目标生物种类 (按 {@link EntityType} 精确比对, 不做父类归并: "杀 10 只僵尸"不应把尸壳算进去)
 * @param requiredCount 需要击杀的只数
 */
public record KillEntityObjective(EntityType<?> target, int requiredCount) implements QuestObjective {

    public KillEntityObjective {
        if (target == null) {
            throw new IllegalArgumentException("target entity type must not be null");
        }
        if (requiredCount < 1) {
            throw new IllegalArgumentException("requiredCount must be >= 1, got " + requiredCount);
        }
    }

    @Override
    public String describe() {
        return "击杀 " + target.getDescription().getString() + " x" + requiredCount;
    }

    @Override
    public int match(QuestFacts facts) {
        if (!(facts instanceof QuestFacts.EntityKill kill)) {
            return 0;
        }
        return kill.victim().getType() == target ? 1 : 0;
    }
}
