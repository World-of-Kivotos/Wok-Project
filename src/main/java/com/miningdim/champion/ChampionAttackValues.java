package com.miningdim.champion;

/**
 * 冠军攻击类词条的【集中单点伤害折算】纯逻辑 (ChampionStarAffix spec 7.2 战斗 + 红线 3 单击 + 9A.2 单点铁律 +
 * 9A.3 #6 玩家侧 LivingHurtEvent 重写冠军近战为 %maxHP)。
 *
 * 单点铁律 (9A.2): 严禁逐词条 onAttack/onHurt 各自改伤害 (串行 setAmount 无全局 clamp, 多源相加穿透红线 3
 * 单击 ≤40%)。冠军近战命中玩家的【全部即时伤害修正源】(重炮 +伤害放大 / 嗜血低血 +伤害放大 / 穿甲无视护甲真伤)
 * 必须在受击单点一次性合并, 经红线钳制后产出【本次单击对玩家的额外伤害】(%playerMaxHP)。本类是该合并的纯函数,
 * 不碰世界/实体/Champions, GameTest 直接断言红线封顶 (删 clamp 必挂)。
 *
 * 伤害模型 (spec 7.2 + 9A.3 #6): 冠军普通近战单击的名义基线 = {@link StarRank#baseSingleHitPct} (该星 4%-20%
 * maxHP)。
 *  - 重炮 (HEAVY_CANNON) / 嗜血 (BLOODLUST, 仅低血激活) 是【伤害放大系数】(+30%..+100% / +15%..+60%), 放大
 *    "普通伤害分量" = base × (1 + heavyCannonAmp + bloodlustAmp)。该普通分量经 {@link ChampionAffixValues#clampNormalHitPct}
 *    夹到该星普通单击上限 (1-5★≤40% / 6-7★≤50% / 8-10★≤60%)。
 *  - 穿甲 (ARMOR_PIERCING) 是【无视护甲真伤】, 不放大普通分量, 而是与已夹断的普通分量【合计】再经
 *    {@link ChampionAffixValues#clampPiercingPlusNormal} 夹到 ≤40% 单击 (真伤不入高星放宽, 保守恒 40%)。
 *
 * 与受击 handler 分工: 本类只算"额外伤害 %playerMaxHP"(纯数学, 含红线钳制); 实际把它折成 HP 加到
 * {@code event.getAmount()} 由 integration 层 {@code ChampionAttackHandler} 在 LivingHurtEvent 单点执行 (真服验)。
 * DoT (燃烧/寒霜冻伤) 不在本类即时伤害口径内 —— 它们经 {@link com.miningdim.champion.aggregate.PlayerDotAccumulator}
 * 每秒 ≤15% maxHP 聚合, 与单击伤害分离 (spec 红线 4)。
 */
public final class ChampionAttackValues {

    private ChampionAttackValues() {
    }

    /**
     * 把冠军本次近战单击对玩家的【全部即时伤害修正源】合并为额外伤害 %playerMaxHP (经红线 3 单击 + 穿甲合计封顶)。
     *
     * 普通分量 = baseHitPct × (1 + heavyCannonAmp + bloodlustAmp), 夹到该星普通单击上限; 穿甲真伤分量与之合计再夹
     * 到 ≤40% 单击。返回的【额外伤害】= 钳后总伤 - 已被 vanilla 近战计入的基线, 由 handler 据其口径折算 (见
     * {@link #bonusOverVanilla})。本法直接返回【钳后单击总名义伤害 %playerMaxHP】, 由 handler 决定是替换还是叠加。
     *
     * @param rank            冠军星级 (普通单击上限随星抬升)
     * @param baseHitPct      该星普通近战单击名义基线 %maxHP (= {@link StarRank#baseSingleHitPct}; &gt;=0)
     * @param heavyCannonAmp  重炮伤害放大系数 (0 = 无重炮; 如高级 0.65); &gt;=0
     * @param bloodlustAmp    嗜血伤害放大系数 (0 = 未激活/无嗜血; 仅冠军低血时由 handler 传入非 0); &gt;=0
     * @param piercingPct     穿甲无视护甲真伤 %maxHP (0 = 无穿甲); &gt;=0
     * @return 钳后本次单击对玩家的总名义伤害 %playerMaxHP (∈ [0, 0.40])
     */
    public static double singleHitTotalPct(StarRank rank, double baseHitPct,
                                           double heavyCannonAmp, double bloodlustAmp, double piercingPct) {
        if (rank == null) {
            throw new IllegalArgumentException("rank must not be null");
        }
        requireNonNeg(baseHitPct, "baseHitPct");
        requireNonNeg(heavyCannonAmp, "heavyCannonAmp");
        requireNonNeg(bloodlustAmp, "bloodlustAmp");
        requireNonNeg(piercingPct, "piercingPct");

        // 普通分量: 基线被重炮/嗜血放大, 夹到该星普通单击上限 (红线 3; 1-5★40/6-7★50/8-10★60%)。
        double amplifiedNormal = baseHitPct * (1.0D + heavyCannonAmp + bloodlustAmp);
        double clampedNormal = ChampionAffixValues.clampNormalHitPct(rank, amplifiedNormal);

        // 无穿甲真伤: 普通单击上限即终值 (高星普通可达 50/60%, 不被 40% 合计封顶误压)。
        // 有穿甲真伤: 真伤无视护甲更危险, 故与普通【合计】夹到恒 ≤40% (红线 3 例外, 真伤不入高星放宽)。
        if (piercingPct <= 0.0D) {
            return clampedNormal;
        }
        return ChampionAffixValues.clampPiercingPlusNormal(clampedNormal, piercingPct);
    }

    /**
     * 把"钳后单击总名义伤害 %playerMaxHP"折算成【叠加到 vanilla 本次近战之上的额外伤害 HP】。冠军近战的 vanilla
     * 伤害分量已由 {@code event.getAmount()} 体现 (Champions growth 后的近战), 本工程把单击重写为 %maxHP 口径:
     * 额外伤害 = max(钳后总伤 %maxHP × playerMaxHp - vanillaAmount, 0) —— 即把这次近战补足到 %maxHP 名义值,
     * 但绝不【减】伤 (若 vanilla 本就高于钳后名义值, 额外为 0, 不削原版伤害, 由减伤红线另管)。
     *
     * 该折算保证: 最终对玩家的近战伤害 ≥ vanillaAmount 且 ≤ 钳后 %maxHP 名义值, 单击红线由
     * {@link #singleHitTotalPct} 已钳的 totalPct 守住 (本法只补差, 不二次放大)。
     *
     * @param totalPct      {@link #singleHitTotalPct} 产出的钳后单击总名义伤害 %playerMaxHP (∈ [0, 0.40])
     * @param playerMaxHp   受击玩家有效最大血量 (&gt;0; %maxHP 折 HP 的基数)
     * @param vanillaAmount vanilla 本次近战已计入的伤害 HP (event.getAmount(); &gt;=0)
     * @return 应叠加到本次伤害之上的额外 HP (≥0; 补足到 %maxHP 名义值, 不削原版)
     */
    public static double bonusOverVanilla(double totalPct, double playerMaxHp, double vanillaAmount) {
        if (totalPct < 0.0D || Double.isNaN(totalPct)) {
            throw new IllegalArgumentException("totalPct must be >= 0, got " + totalPct);
        }
        if (!(playerMaxHp > 0.0D) || Double.isNaN(playerMaxHp)) {
            throw new IllegalArgumentException("playerMaxHp must be > 0, got " + playerMaxHp);
        }
        requireNonNeg(vanillaAmount, "vanillaAmount");

        double nominalHp = totalPct * playerMaxHp;
        double bonus = nominalHp - vanillaAmount;
        return bonus > 0.0D ? bonus : 0.0D;
    }

    /** 嗜血低血激活阈值 (spec 7.2 嗜血: 低血时增益, 低血判定走血池占比)。冠军 hp 占比 ≤本阈值时激活。 */
    public static final double BLOODLUST_LOW_HP_THRESHOLD = 0.35D;

    /**
     * 嗜血伤害放大系数 (spec 7.2 嗜血): 冠军血量占比 ≤{@link #BLOODLUST_LOW_HP_THRESHOLD} 时激活, 返回该品质
     * valueFor (+15%..+60% 伤害); 否则返 0 (未激活)。低血判定按血池占比 (6★+ 影子血池 fraction / 1-5★ vanilla
     * getHealth/getMaxHealth 之比), 由 handler 取占比传入 (spec 6.2 #1: 低血阈值读血池, 非读 vanilla 作判定逻辑)。
     *
     * 攻速放大 (spec 嗜血"+攻速+伤害") 不在本伤害折算口径内, 且【Stage 1 尚未实现】—— 攻速属属性层修正,
     * 设计上须由 handler 在激活时另施瞬态 ATTACK_SPEED 属性修饰 (与伤害分离), 但全库暂无该 handler (Stage 2
     * 待接线, 依赖 Champions 冠军实体真服环境)。本法只负责伤害放大系数, 喂给 {@link #singleHitTotalPct} 的
     * bloodlustAmp, 经普通单击上限钳制 (spec 嗜血: 伤害受单击上限)。
     *
     * @param quality   嗜血品质
     * @param hpFraction 冠军当前血量占比 [0,1] (血池权威: 6★+ fraction / 1-5★ vanilla 比)
     * @return 激活则该品质伤害放大系数 (&gt;0); 未激活 (占比高于阈值) 返 0
     */
    public static double bloodlustDamageAmp(AffixQuality quality, double hpFraction) {
        if (quality == null) {
            throw new IllegalArgumentException("quality must not be null");
        }
        if (hpFraction < 0.0D || hpFraction > 1.0D || Double.isNaN(hpFraction)) {
            throw new IllegalArgumentException("hpFraction must be in [0,1], got " + hpFraction);
        }
        if (hpFraction > BLOODLUST_LOW_HP_THRESHOLD) {
            return 0.0D; // 未低血, 嗜血未激活。
        }
        return AffixDef.BLOODLUST.valueFor(quality);
    }

    /**
     * 强酸本次命中对玩家护甲的耐久损耗点数 (spec 7.2 强酸: +2/4/6/10/15/击)。纯磨损接经济, 不入伤害/DoT 口径。
     * 返回 valueFor 取整 (耐久是整数点)。实际扣耐久由 handler 遍历玩家护甲槽施加。
     *
     * @param quality 强酸品质
     * @return 本次命中护甲耐久损耗点数 (&gt;=0 整数)
     */
    public static int corrosiveArmorDamage(AffixQuality quality) {
        if (quality == null) {
            throw new IllegalArgumentException("quality must not be null");
        }
        return (int) Math.round(AffixDef.CORROSIVE.valueFor(quality));
    }

    /**
     * 燃烧本秒名义 DoT 伤害 (HP): 每层 valueFor %maxHP/s × 当前层数, 折成 HP (spec 7.2 燃烧)。该值【未】经红线 4
     * 的 15% 聚合封顶 —— 封顶在 {@link com.miningdim.champion.aggregate.PlayerDotAccumulator} 把燃烧/寒霜冻伤等多源
     * 合并后统一施加 (本法只算单源本秒名义量, 不自行夹 15%, 与 DoT 单一权威封顶分工)。
     *
     * @param quality     燃烧品质
     * @param stacks      当前燃烧层数 (0-{@link #DOT_MAX_STACKS})
     * @param playerMaxHp 受击玩家有效最大血量 (&gt;0)
     * @return 本秒燃烧名义伤害 HP (&gt;=0)
     */
    public static double burningTickHp(AffixQuality quality, int stacks, double playerMaxHp) {
        return dotTickHp(AffixDef.BURNING, quality, stacks, playerMaxHp);
    }

    /**
     * 寒霜冻伤本秒名义 DoT 伤害 (HP): 每层 valueFor %maxHP/s × 当前层数, 折成 HP (spec 7.2 寒霜)。同燃烧, 未经
     * 15% 封顶 (聚合层统一夹)。寒霜另有减速副效果, 由 {@link #frostSlowPct} 折算 (走控制聚合 ≤50%)。
     *
     * @param quality     寒霜品质
     * @param stacks      当前寒霜层数 (0-{@link #DOT_MAX_STACKS})
     * @param playerMaxHp 受击玩家有效最大血量 (&gt;0)
     * @return 本秒冻伤名义伤害 HP (&gt;=0)
     */
    public static double frostFreezeTickHp(AffixQuality quality, int stacks, double playerMaxHp) {
        return dotTickHp(AffixDef.FROST, quality, stacks, playerMaxHp);
    }

    /**
     * 寒霜减速总量 (spec 7.2 寒霜): 每层 secondaryValueFor 减速% × 当前层数, 经
     * {@link com.miningdim.champion.aggregate.PlayerControlAggregator#clampSlow} 硬封顶 ≤50% (绝不定身)。多源减速
     * 的合计封顶由控制聚合层负责, 本法只算寒霜单源按层减速并先做一次自夹 (兜底, 与聚合层口径一致)。
     *
     * @param quality 寒霜品质
     * @param stacks  当前寒霜层数 (0-{@link #DOT_MAX_STACKS})
     * @return 寒霜减速量 (∈ [0, 0.50])
     */
    public static double frostSlowPct(AffixQuality quality, int stacks) {
        if (quality == null) {
            throw new IllegalArgumentException("quality must not be null");
        }
        requireStacks(stacks);
        double perStack = AffixDef.FROST.secondaryValueFor(quality);
        return com.miningdim.champion.aggregate.PlayerControlAggregator.clampSlow(perStack * stacks);
    }

    /**
     * 撕裂层数 → 易伤 MobEffect amplifier (spec 7.2 撕裂: 复用易伤效果, 封顶走易伤系统 +100%)。撕裂叠的是连续
     * %易伤 ({@link ChampionAffixValues#rendVulnerabilityPct}), 而易伤载体是离散 5 档 MobEffect (I-V =
     * +20/35/50/70/100%, 见 {@link com.miningdim.effect.VulnerabilityEffect#percentForAmplifier})。本法把撕裂连续
     * %【向下取档】映射到不超过它的最高 amplifier (floor): 即撕裂实际放大 ≤ 其名义 %, 绝不越档放大 (保守, 不破
     * 易伤封顶)。撕裂 %低于易伤 I 档 (+20%) 时返 -1 (无可施加档, handler 不挂效果)。
     *
     * 复用易伤单一权威 (9A.2 铁律): 本法只产 amplifier, handler 经 {@code player.addEffect(VULNERABILITY, dur, amp)}
     * 挂【同一个】全局易伤效果, 乘伤由 {@code VulnerabilityHurtHandler} 单点结算, 严禁另挂第二个易伤乘伤。多源
     * (撕裂 + 塔罗易伤) 取最高 amplifier 由原版 MobEffect 系统天然保证。
     *
     * @param rendQuality 撕裂品质
     * @param layers      当前撕裂层数 (&gt;=0)
     * @return 易伤 amplifier (0-4 = 易伤 I-V); 撕裂 %不足 I 档时返 -1 (不挂效果)
     */
    public static int rendAmplifier(AffixQuality rendQuality, int layers) {
        double pct = ChampionAffixValues.rendVulnerabilityPct(rendQuality, layers);
        int amp = -1;
        for (int a = 0; a < VULNERABILITY_LEVEL_COUNT; a++) {
            if (com.miningdim.effect.VulnerabilityEffect.percentForAmplifier(a) <= pct + 1e-9D) {
                amp = a;
            } else {
                break;
            }
        }
        return amp;
    }

    /** 易伤 MobEffect 档位数 (I-V = 5 档; 与 {@link com.miningdim.effect.VulnerabilityEffect} 阶梯一致)。 */
    private static final int VULNERABILITY_LEVEL_COUNT = 5;

    /** DoT 词条 (燃烧/寒霜) 最大层数 (spec 7.2: 最大 5 层 3s 刷新)。 */
    public static final int DOT_MAX_STACKS = 5;

    private static double dotTickHp(AffixDef def, AffixQuality quality, int stacks, double playerMaxHp) {
        if (quality == null) {
            throw new IllegalArgumentException("quality must not be null");
        }
        requireStacks(stacks);
        if (!(playerMaxHp > 0.0D) || Double.isNaN(playerMaxHp)) {
            throw new IllegalArgumentException("playerMaxHp must be > 0, got " + playerMaxHp);
        }
        double perStackPct = def.valueFor(quality);
        return perStackPct * stacks * playerMaxHp;
    }

    private static void requireStacks(int stacks) {
        if (stacks < 0 || stacks > DOT_MAX_STACKS) {
            throw new IllegalArgumentException("dot stacks out of [0," + DOT_MAX_STACKS + "]: " + stacks);
        }
    }

    private static void requireNonNeg(double v, String name) {
        if (v < 0.0D || Double.isNaN(v)) {
            throw new IllegalArgumentException(name + " must be >= 0, got " + v);
        }
    }
}
