package com.miningdim.champion;

/**
 * 1-10★ 精英怪主数据表 (ChampionStarAffix spec 第五章每星主数据表 + 第六章有效血量 + 红线 3 单击上限)。
 *
 * 每星定四池总点数预算 (生存/战斗/机动/技能) + 总词条上限 + 技能数上限 + 最高品质 + 基础有效 HP +
 * 基础单击 %maxHP。对应 champions-ranks.toml 的 10 条自定义 rank (tier = 星级)。纯数据/常量, 不依赖
 * Champions 任何类型; spawn 期分配器 {@link PointBudget} 与血池 {@link com.miningdim.champion.bloodpool.BloodPool}
 * 据此盖章, 但本枚举本身无世界/实体引用。
 *
 * 数值逐行对齐 spec 第五章表 (星|生存池|战斗池|机动池|技能池|总词条上限|技能数上限|最高品质|基础有效HP|基础单击%)。
 * 技能 3★ 才解锁 (1-2★ 杂兵技能数上限 = 0)。基础有效 HP 6★ 起破原版 generic.max_health 1024 上限, 故 6★+
 * 走自定义血池 (spec 6.2)。
 *
 * 红线 3 单击 %maxHP 上限按星级三档抬升 (spec 第三章红线 3): 1-5★ ≤40% / 6-7★ ≤50% / 8-10★ ≤60%。
 * "基础单击%"列 (4%-20%) 是该星普通近战单击的名义基线; 红线 3 是该星任何普通单击 (不可躲) 的硬上限,
 * 二者区分: 基线是设计期望值, 红线是不可越的封顶。
 */
public enum StarRank {

    //         star surv comb mob skill affixCap skillCap  maxQuality            baseEffHp  baseHitPct
    STAR_1(1,   10,   8,   0,    0,     1,      0, AffixQuality.COMMON,      135.0D,    0.04D),
    STAR_2(2,   20,  14,   4,    0,     2,      0, AffixQuality.COMMON,      225.0D,    0.05D),
    STAR_3(3,   35,  24,   8,   15,     3,      1, AffixQuality.UNCOMMON,    360.0D,    0.06D),
    STAR_4(4,   55,  36,  12,   25,     4,      1, AffixQuality.UNCOMMON,    540.0D,    0.08D),
    STAR_5(5,   80,  55,  20,   45,     5,      1, AffixQuality.RARE,        765.0D,    0.10D),
    STAR_6(6,  120,  80,  30,   70,     6,      2, AffixQuality.RARE,      2_700.0D,    0.12D),
    STAR_7(7,  165, 110,  45,  110,     7,      2, AffixQuality.EPIC,      6_000.0D,    0.14D),
    STAR_8(8,  240, 160,  75,  180,     9,      3, AffixQuality.EPIC,     27_000.0D,    0.16D),
    STAR_9(9,  330, 230, 115,  260,    11,      3, AffixQuality.LEGENDARY, 45_000.0D,    0.18D),
    STAR_10(10, 440, 310, 155,  360,    13,      4, AffixQuality.LEGENDARY, 73_000.0D,    0.20D);

    /** 星级数值上界 (10★ 顶级世界 BOSS)。 */
    public static final int MAX_STAR = 10;

    /** 星级数值下界 (1★ 强化杂兵)。 */
    public static final int MIN_STAR = 1;

    /**
     * 自定义血池启用阈值 (spec 6.2): 6★ 起基础有效 HP 破原版 generic.max_health 1024 上限,
     * 一律以自定义 double currentHp/maxHp 为权威。1-5★ 基础有效 HP ≤765 < 1024 仍可走 vanilla。
     */
    public static final int CUSTOM_BLOOD_POOL_MIN_STAR = 6;

    private final int star;
    private final int survivalBudget;
    private final int combatBudget;
    private final int mobilityBudget;
    private final int skillBudget;
    private final int maxAffixes;
    private final int maxSkills;
    private final AffixQuality maxQuality;
    private final double baseEffectiveHp;
    private final double baseSingleHitPct;

    StarRank(int star, int survivalBudget, int combatBudget, int mobilityBudget, int skillBudget,
             int maxAffixes, int maxSkills, AffixQuality maxQuality,
             double baseEffectiveHp, double baseSingleHitPct) {
        this.star = star;
        this.survivalBudget = survivalBudget;
        this.combatBudget = combatBudget;
        this.mobilityBudget = mobilityBudget;
        this.skillBudget = skillBudget;
        this.maxAffixes = maxAffixes;
        this.maxSkills = maxSkills;
        this.maxQuality = maxQuality;
        this.baseEffectiveHp = baseEffectiveHp;
        this.baseSingleHitPct = baseSingleHitPct;
    }

    /** 星级 (1-10)。 */
    public int star() {
        return star;
    }

    /** 指定四池的总点数预算 (spec 第五章)。 */
    public int budgetFor(AffixPool pool) {
        return switch (pool) {
            case SURVIVAL -> survivalBudget;
            case COMBAT -> combatBudget;
            case MOBILITY -> mobilityBudget;
            case SKILL -> skillBudget;
        };
    }

    /** 生存池总点数预算。 */
    public int survivalBudget() {
        return survivalBudget;
    }

    /** 战斗池总点数预算。 */
    public int combatBudget() {
        return combatBudget;
    }

    /** 机动池总点数预算。 */
    public int mobilityBudget() {
        return mobilityBudget;
    }

    /** 技能池总点数预算 (1-2★ 为 0, 技能 3★ 才解锁)。 */
    public int skillBudget() {
        return skillBudget;
    }

    /** 总词条上限 (含技能词条)。 */
    public int maxAffixes() {
        return maxAffixes;
    }

    /** 技能数上限 (体验红线, 卡满屏读条核弹; 1-2★ = 0)。 */
    public int maxSkills() {
        return maxSkills;
    }

    /** 该星允许的最高品质 (品质随星解锁, spec 第四章)。 */
    public AffixQuality maxQuality() {
        return maxQuality;
    }

    /** 基础有效 HP (生存点几乎全投血量的情形; 6★ 起破 1024)。 */
    public double baseEffectiveHp() {
        return baseEffectiveHp;
    }

    /** 基础单击 %maxHP (该星普通近战单击的名义基线, spec 第五章末列)。 */
    public double baseSingleHitPct() {
        return baseSingleHitPct;
    }

    /** 是否走自定义血池 (6★+, spec 6.2: 基础有效 HP 破 1024)。 */
    public boolean usesCustomBloodPool() {
        return star >= CUSTOM_BLOOD_POOL_MIN_STAR;
    }

    /**
     * 红线 3 普通单击 (不可躲) 的 %maxHP 硬上限 (spec 第三章红线 3 三档): 1-5★ ≤0.40 / 6-7★ ≤0.50 /
     * 8-10★ ≤0.60。任何普通单击名义值经此夹断。
     */
    public double normalHitCapPct() {
        if (star <= 5) {
            return 0.40D;
        }
        if (star <= 7) {
            return 0.50D;
        }
        return 0.60D;
    }

    /** 星级数值反查 StarRank (1-10); 越界抛 IllegalArgumentException (不掩盖, 异常自然冒泡)。 */
    public static StarRank ofStar(int star) {
        for (StarRank r : values()) {
            if (r.star == star) {
                return r;
            }
        }
        throw new IllegalArgumentException("star out of [1,10]: " + star);
    }
}
