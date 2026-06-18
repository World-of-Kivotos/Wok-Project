package com.miningdim.champion;

import com.miningdim.effect.VulnerabilityEffect;

/**
 * 词条数值语义解释纯逻辑 (ChampionStarAffix spec 第七章数值 + 红线 1/3/4)。把 {@link AffixDef} 的原始档位数值
 * 折算成受击结算层要的语义量 (净减伤参与率 / 单击名义伤害 / DoT 每秒伤害 / 撕裂复用易伤的等效层数), 并施加
 * 红线夹断。本类是纯函数集合, 不碰世界/实体/Champions, GameTest 直接断言。
 *
 * 撕裂复用易伤 (spec 7.2 撕裂 + 9A.2 铁律): 撕裂"叠易伤 +5/8/12/16/20%/层", 复用全局唯一
 * {@link VulnerabilityEffect} 系统, 乘伤由全局 {@link com.miningdim.effect.VulnerabilityHurtHandler} 单点结算,
 * champion 受击点严禁再各自挂第二个易伤乘伤 (第五章红线)。本类只把撕裂层数折算成易伤百分比并夹到易伤封顶
 * (+100%), 不自行乘伤。
 */
public final class ChampionAffixValues {

    private ChampionAffixValues() {
    }

    /**
     * 把怪物近战名义伤害按红线 3 单击 %maxHP 上限夹断 (spec 红线 3 普通单击三档): 名义 %maxHP 经星级上限钳制。
     * 普通单击不可躲, 上限随星级 1-5★≤40% / 6-7★≤50% / 8-10★≤60% ({@link StarRank#normalHitCapPct()})。
     *
     * @param rank          星级
     * @param nominalPct    词条/基线给出的名义单击 %maxHP (&gt;=0)
     * @return 夹到该星普通单击上限后的 %maxHP
     */
    public static double clampNormalHitPct(StarRank rank, double nominalPct) {
        if (rank == null) {
            throw new IllegalArgumentException("rank must not be null");
        }
        if (nominalPct < 0.0D || Double.isNaN(nominalPct)) {
            throw new IllegalArgumentException("nominalPct must be >= 0, got " + nominalPct);
        }
        return Math.min(nominalPct, rank.normalHitCapPct());
    }

    /**
     * 穿甲真伤 + 普通伤害合计夹到 40% 单击上限 (spec 7.2 穿甲 + 红线 3 例外): 穿甲是无视护甲真伤, 与普通伤害
     * 合计 ≤40% 单击上限。真伤不在高星放宽之列, 故无论星级合计上限恒 40% (保守封顶)。
     *
     * @param normalPct   普通伤害 %maxHP (&gt;=0)
     * @param piercingPct 穿甲真伤 %maxHP (&gt;=0)
     * @return 合计夹到 40% 后的总 %maxHP
     */
    public static double clampPiercingPlusNormal(double normalPct, double piercingPct) {
        if (normalPct < 0.0D || piercingPct < 0.0D || Double.isNaN(normalPct) || Double.isNaN(piercingPct)) {
            throw new IllegalArgumentException(
                    "normal/piercing pct must be >= 0, got " + normalPct + "/" + piercingPct);
        }
        return Math.min(normalPct + piercingPct, 0.40D);
    }

    /**
     * 把带预兆可躲技能 (蓄力/天雷/核弹) 的名义 %maxHP 夹到红线 3 可躲技能名义上限 ≤90% maxHP (spec 红线 3)。
     *
     * @param nominalPct 技能名义 %maxHP (&gt;=0)
     * @return 夹到 ≤90% 后的 %maxHP
     */
    public static double clampTelegraphedHitPct(double nominalPct) {
        if (nominalPct < 0.0D || Double.isNaN(nominalPct)) {
            throw new IllegalArgumentException("nominalPct must be >= 0, got " + nominalPct);
        }
        return Math.min(nominalPct, ChampionRedlines.TELEGRAPHED_HIT_CAP_PCT);
    }

    /**
     * 把连段 (利刃华尔兹) 整套总伤夹到红线 3 连段总伤上限 ≤60% maxHP (spec 红线 3 / 7.4 利刃华尔兹)。
     *
     * @param totalPct 整套连段名义总 %maxHP (&gt;=0)
     * @return 夹到 ≤60% 后的总 %maxHP
     */
    public static double clampComboTotalPct(double totalPct) {
        if (totalPct < 0.0D || Double.isNaN(totalPct)) {
            throw new IllegalArgumentException("totalPct must be >= 0, got " + totalPct);
        }
        return Math.min(totalPct, ChampionRedlines.COMBO_TOTAL_CAP_PCT);
    }

    /**
     * 撕裂层数 → 易伤百分比, 夹到易伤系统封顶 +100% (spec 7.2 撕裂 + 第五章易伤封顶)。撕裂某品质每层
     * +5/8/12/16/20% 易伤, layers 层后总易伤 = perLayer × layers, 夹到 {@link VulnerabilityEffect#MAX_VULNERABILITY_PCT}。
     * 仅折算百分比, 实际乘伤由全局 VulnerabilityHurtHandler 单点结算 (本类不乘伤)。
     *
     * @param quality 撕裂品质
     * @param layers  当前撕裂层数 (&gt;=0)
     * @return 夹到 +100% 后的易伤百分比
     */
    public static double rendVulnerabilityPct(AffixQuality quality, int layers) {
        if (quality == null) {
            throw new IllegalArgumentException("quality must not be null");
        }
        if (layers < 0) {
            throw new IllegalArgumentException("layers must be >= 0, got " + layers);
        }
        double perLayer = AffixDef.REND.valueFor(quality);
        double total = perLayer * layers;
        return Math.min(total, VulnerabilityEffect.MAX_VULNERABILITY_PCT);
    }

    /**
     * 召唤星级三重封顶之星级钳制 (spec 红线 8): 召唤星级 = clamp(自身星 - 2, 1, 绝对天花板 4★)。
     *
     * @param selfStar 母怪星级 (1-10)
     * @return 召唤物星级 (1-{@link ChampionRedlines#SUMMON_STAR_ABSOLUTE_CEIL})
     */
    public static int summonStar(int selfStar) {
        if (selfStar < StarRank.MIN_STAR || selfStar > StarRank.MAX_STAR) {
            throw new IllegalArgumentException("selfStar out of [1,10]: " + selfStar);
        }
        int raw = selfStar - ChampionRedlines.SUMMON_STAR_OFFSET;
        if (raw < StarRank.MIN_STAR) {
            raw = StarRank.MIN_STAR;
        }
        if (raw > ChampionRedlines.SUMMON_STAR_ABSOLUTE_CEIL) {
            raw = ChampionRedlines.SUMMON_STAR_ABSOLUTE_CEIL;
        }
        return raw;
    }
}
