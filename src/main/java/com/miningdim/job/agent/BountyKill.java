package com.miningdim.job.agent;

import com.miningdim.champion.AffixDef;
import com.miningdim.champion.AffixPool;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

/**
 * 一次精英击杀对悬赏有意义的全部事实 (纯值, {@link BountyDefinition#countsToward} 的唯一入参)。
 *
 * 词条类别取的是精英<b>本来</b>带的词条 —— 击杀时仍挂着的加上正被封印摘走的。封印只是临时压制, 目标还是那只"带战斗
 * 词条的精英"; 若只看击杀瞬间的词条表, 干员封掉目标词条反而会让自己的悬赏不算数, 等于惩罚正确使用职业技能。
 *
 * @param star      精英出生盖章的初始星级 (封印不改星级, 与贡献池同口径)
 * @param pools     精英词条覆盖到的池 (含封印中的词条)
 * @param worldBoss 是否世界 BOSS ({@code MiningChampionData.isWorldBoss})
 * @param qualified 该玩家是否达贡献池入池门槛 (在线 + 盖章双门槛)
 */
public record BountyKill(int star, Set<AffixPool> pools, boolean worldBoss, boolean qualified) {

    public BountyKill {
        pools = pools.isEmpty() ? Set.of() : Set.copyOf(pools);
    }

    /** 从一组词条折出所覆盖的池集合 (重复词条、同池多条都只记一次)。 */
    public static Set<AffixPool> poolsOf(Collection<AffixDef> affixes) {
        EnumSet<AffixPool> pools = EnumSet.noneOf(AffixPool.class);
        for (AffixDef def : affixes) {
            pools.add(def.pool());
        }
        return pools;
    }
}
