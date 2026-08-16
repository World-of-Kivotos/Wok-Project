package com.miningdim.quest.objective;

import com.miningdim.quest.QuestFacts;
import com.miningdim.quest.QuestObjective;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

/**
 * 挖掘指定方块标签下的方块 N 个。
 *
 * 判据走 {@link TagKey} 而非单个 {@link Block}: 原版矿物普遍有深层变体 (铁矿/深层铁矿同属 {@code IRON_ORES}),
 * 按单个方块写会让玩家在深板岩层挖满一天却不计数。
 *
 * @param target        目标方块标签 (如 {@code BlockTags.IRON_ORES})
 * @param displayName   面向玩家的中文名 (标签本身无本地化名, 必须显式给)
 * @param requiredCount 需要挖掘的个数
 */
public record MineBlockObjective(TagKey<Block> target, String displayName, int requiredCount)
        implements QuestObjective {

    public MineBlockObjective {
        if (target == null) {
            throw new IllegalArgumentException("target block tag must not be null");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        if (requiredCount < 1) {
            throw new IllegalArgumentException("requiredCount must be >= 1, got " + requiredCount);
        }
    }

    @Override
    public String describe() {
        return "挖掘 " + displayName + " x" + requiredCount;
    }

    @Override
    public int match(QuestFacts facts) {
        if (!(facts instanceof QuestFacts.BlockMine mine)) {
            return 0;
        }
        return mine.state().is(target) ? 1 : 0;
    }
}
