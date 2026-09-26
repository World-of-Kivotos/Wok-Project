package com.miningdim.achievement.trigger;

import com.miningdim.champion.AffixDef;

import java.util.Set;

/**
 * 一次精英怪击杀里某一名有效贡献者的视角, 供 {@link ChampionKillTrigger} 判定。由击杀钩子在精英怪死亡时按 6.4 的击杀
 * 过滤组装 (读贡献账本只能 peek, 不能 drain)。
 *
 * @param star       精英怪星级 (1~10)
 * @param affixes    死亡时仍持有的词条
 * @param share      该玩家的记录伤害 / 全部记录伤害 (0~1)
 * @param solo       是否满足 6.4 的"独自击杀": 伤害账本只有他一人、他的记录伤害不少于精英的有效血量, 且精英只攻击过他
 * @param fightTicks 死亡时间 - 首次命中时间 (tick); 不可知时传负数, 此时任何 max_fight_ticks 条件都不满足
 */
public record ChampionKill(int star, Set<AffixDef> affixes, double share, boolean solo, long fightTicks) {

    public ChampionKill {
        affixes = Set.copyOf(affixes);
    }
}
