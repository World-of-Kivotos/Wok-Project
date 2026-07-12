package com.miningdim.champion;

import java.util.Map;

/**
 * 点数换血量换算纯逻辑 (ChampionStarAffix spec 第四章"剩余点数换算成基础膨胀"生存轴 + 第六章 6.1
 * "投了减伤词条则裸血更低、有效血量持平")。
 *
 * 星表 {@link StarRank#baseEffectiveHp()} 定义为"生存点几乎全投血量的情形"; 此前 promoter 无视词条花费直接
 * 给满额星表血, 导致"带减伤词条 = 白送有效血"失衡 (7★ 全防御 build 撞 75% 帽 = 4 倍裸怪有效血)。本类把
 * 生存池花费折算回基础血:
 *
 *   hpFraction  = FLOOR + (1 - FLOOR) × (剩余生存点 / 生存池预算)^GAMMA
 *   effectiveHp = baseEffectiveHp × hpFraction × 体型乘数 (巨大化 1+X / 缩小化 1-X)
 *
 * FLOOR 保底防"点全花光 = 纸怪" (词条效果多为条件触发, 全额定价会过罚); GAMMA&gt;1 让重仓生存词条比线性
 * 多扣血 (2026-07-07 用户拍板 FLOOR=0.35 / GAMMA=1.5; 后续 config 暴露属 spec 第十三章 PENDING)。
 *
 * 宽容语义 (与 {@link PointBudget#allocate} 的严格校验刻意不同): 生存池花费超预算时剩余点钳 0 → 保底血量,
 * 不抛异常 —— /mchampion summon 调试可越互斥/预算 (ChampionCommands 类注释), 换算层把"超花"解释为"点全花光",
 * 装配合法性仍由 roll 路径的 PointBudget 终校验守住。纯函数, 无世界引用, GameTest 直接断言 (删曲线必挂)。
 * 战斗池剩余→伤害膨胀 / 机动池剩余→移速膨胀 未接 (批2 只堵生存轴白送血, 另两轴影响面另评)。
 */
public final class ChampionHpConversion {

    /** 血量保底系数: 生存点全花光时仍保留的基础血比例 (2026-07-07 用户拍板 0.35)。 */
    public static final double HP_FLOOR = 0.35D;

    /** 换算曲线指数: 剩余点占比的幂 (&gt;1 = 重仓词条比线性多扣血; 2026-07-07 用户拍板 1.5)。 */
    public static final double GAMMA = 1.5D;

    private ChampionHpConversion() {
    }

    /**
     * 生存池词条总花费 (点数, 全额账面口径供诊断日志): 仅统计 {@link AffixPool#SURVIVAL} 词条, 按各自品质
     * {@link AffixDef#costAt} 求和, 巨大化/缩小化一并计入。注意换血惩罚用的是豁免 SIZE 族的
     * {@code conversionSpent} (见 {@link #hpFraction} 倒挂修复说明), 本法只是账面统计。
     *
     * @param affixes 词条→品质映射 (须非 null; 空映射花费 0)
     * @return 生存池总花费点数 (&gt;=0)
     */
    public static int survivalSpent(Map<AffixDef, AffixQuality> affixes) {
        requireAffixes(affixes);
        int spent = 0;
        for (Map.Entry<AffixDef, AffixQuality> entry : affixes.entrySet()) {
            if (entry.getKey().pool() == AffixPool.SURVIVAL) {
                spent += entry.getKey().costAt(entry.getValue());
            }
        }
        return spent;
    }

    /**
     * 基础血比例 [FLOOR, 1]: FLOOR + (1-FLOOR) × (剩余生存点占比)^GAMMA。无生存词条 = 1.0 (星表满额);
     * 花费超预算 (命令调试) 剩余钳 0 = FLOOR 保底。
     *
     * 体型词条 (SIZE 互斥族: 巨大化/缩小化) 的点数【不】入本惩罚 (对抗审查发现的低星倒挂修复): 其血量效果已由
     * {@link #sizeMultiplier} 完整表达, 再按点扣基础血是对同一效果双重计价 —— 3★ 巨大化 COMMON 会算出
     * 360×0.696×1.3≈326 &lt; 裸怪 360, "+血量词条"净减血且品质越高越亏。豁免后巨大化恒为名义 +X% (spec 6.1
     * "10★ 巨大化闪耀 ≈204,400" 的算术本身即按满额基数), 缩小化恰为名义 -X%。体型词条点数仍占
     * {@link PointBudget} 预算 (挤压其它词条槽位), 只是不参与血量换算。
     *
     * @param rank    星级 (生存池预算取自星表, 恒 &gt;0)
     * @param affixes 词条→品质映射
     * @return 基础血比例 (∈ [HP_FLOOR, 1])
     */
    public static double hpFraction(StarRank rank, Map<AffixDef, AffixQuality> affixes) {
        requireRank(rank);
        requireAffixes(affixes);
        int budget = rank.survivalBudget();
        int remaining = Math.max(0, budget - conversionSpent(affixes));
        double remFrac = (double) remaining / budget;
        return HP_FLOOR + (1.0D - HP_FLOOR) * Math.pow(remFrac, GAMMA);
    }

    /** 参与换血惩罚的生存池花费 = 生存池词条成本和, 豁免 SIZE 互斥族 (体型词条血量效果走 sizeMultiplier)。 */
    private static int conversionSpent(Map<AffixDef, AffixQuality> affixes) {
        int spent = 0;
        for (Map.Entry<AffixDef, AffixQuality> entry : affixes.entrySet()) {
            AffixDef def = entry.getKey();
            if (def.pool() == AffixPool.SURVIVAL && def.mutexFlag() != AffixDef.MutexFlag.SIZE) {
                spent += def.costAt(entry.getValue());
            }
        }
        return spent;
    }

    /**
     * 体型血量乘数 (spec 7.1): 巨大化 +血量 30/50/80/120/180% → ×(1+X); 缩小化 -血量 25/32/40/48/58% → ×(1-X)。
     * 无体型词条 = 1.0。SIZE 互斥族保证合法装配至多一条; 命令调试双持时连乘 (确定性行为, 不抛)。
     *
     * @param affixes 词条→品质映射
     * @return 体型血量乘数 (&gt;0)
     */
    public static double sizeMultiplier(Map<AffixDef, AffixQuality> affixes) {
        requireAffixes(affixes);
        double mult = 1.0D;
        AffixQuality gigantism = affixes.get(AffixDef.GIGANTISM);
        if (gigantism != null) {
            mult *= 1.0D + AffixDef.GIGANTISM.valueFor(gigantism);
        }
        AffixQuality miniaturization = affixes.get(AffixDef.MINIATURIZATION);
        if (miniaturization != null) {
            mult *= 1.0D - AffixDef.MINIATURIZATION.valueFor(miniaturization);
        }
        return mult;
    }

    /**
     * 换算后有效血 = 星表基础血 × {@link #hpFraction} × {@link #sizeMultiplier}。promoter 盖章唯一消费点
     * (capability effectiveHp / vanilla MAX_HEALTH / 6★+ 血池 maxHp 三者同源于本值, 下游口径自动一致)。
     *
     * @param rank    星级
     * @param affixes 词条→品质映射
     * @return 有效血 (&gt;0; FLOOR 保底 + 缩小化 -58% 上限保证恒正)
     */
    public static double convertedEffectiveHp(StarRank rank, Map<AffixDef, AffixQuality> affixes) {
        requireRank(rank);
        return rank.baseEffectiveHp() * hpFraction(rank, affixes) * sizeMultiplier(affixes);
    }

    private static void requireRank(StarRank rank) {
        if (rank == null) {
            throw new IllegalArgumentException("rank must not be null");
        }
    }

    private static void requireAffixes(Map<AffixDef, AffixQuality> affixes) {
        if (affixes == null) {
            throw new IllegalArgumentException("affixes must not be null");
        }
    }
}
