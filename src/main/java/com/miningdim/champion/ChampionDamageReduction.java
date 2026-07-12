package com.miningdim.champion;

/**
 * 6 个减伤词条的数值折算 + 伤害来源分类纯逻辑 (ChampionStarAffix spec 7.1 生存 + 9.2 净减伤单点 + 红线 1)。
 *
 * 把 {@link AffixDef} 的原始档位数值折算成"净减伤连乘参与率 rᵢ"(进 {@link ChampionRedlines#clampNetKeepFactor}),
 * 以及两个非比例的 FLAT 削顶量 (刚毅单次封顶 / 重型近战-爆炸 <T 免疫阈值)。本类是纯函数集合, 不碰世界/实体/
 * Champions —— 受击 handler ({@link com.miningdim.champion.integration.ChampionBloodPoolHandler}) 把
 * {@link net.minecraft.world.damagesource.DamageSource} 的伤害类型 id 拆成 namespace/path 字符串后调本类分类
 * (子弹/近战/爆炸), 再据词条池调本类折算 rate / cap, dev GameTest 直接断言数值与分类 (不加载 Champions/TACZ)。
 *
 * 净减伤单点铁律 (9A.2): 比例类减伤源 (超高分子子弹抗 + 重型子弹抗 + 复合 ramp + 偏斜 EV + 缩小化体型折算) 全部
 * 折算成 rate 收进数组一次性连乘 keep = max(∏(1-rᵢ), 0.25) (2026-07-07 随复合同源适应 49%->75% 抬帽, 数值真源
 * {@link ChampionRedlines}); FLAT 类 (刚毅封顶 / 重型 <T 免疫) 不是固定 rate (与入伤量耦合), 故在连乘 keep 算出
 * 净伤后再削顶 (spec 7.1 刚毅"折算或直接削顶"二选其一, 此处取削顶口径), 削顶只会让冠军更肉 (单向变硬), 不与 75%
 * 净减伤帽冲突 (帽是对"比例源"的保护, FLAT 是额外硬上限)。
 */
public final class ChampionDamageReduction {

    private ChampionDamageReduction() {
    }

    /**
     * 伤害类别 (复合装甲同源适应的分桶维度, spec 7.1 复合装甲 v2): 装甲只适应【当前持续挨的那类】伤害, 换类别
     * 即重置 —— 玩家的真实反制手段 (枪打久了换近战/丢雷)。分类由受击 handler 从 DamageSource 折算 (子弹 =
     * tacz:bullet*; 爆炸 = IS_EXPLOSION 标签; 近战 = MOB/PLAYER_ATTACK; 其余归 OTHER), 本枚举纯逻辑供 tracker 分桶。
     */
    public enum DamageCategory {
        /** TACZ 子弹 (tacz:bullet*)。 */
        BULLET,
        /** 近战 (MOB_ATTACK / MOB_ATTACK_NO_AGGRO / PLAYER_ATTACK)。 */
        MELEE,
        /** 爆炸 (IS_EXPLOSION 标签, 含 TACZ 爆炸弹/手雷)。 */
        EXPLOSION,
        /** 其它 (弹射物/魔法/环境等)。 */
        OTHER
    }

    /** 复合装甲 ramp 分段数 (spec 7.1: 每受同类击 +上限/5, 5 次达上限)。 */
    public static final int COMPOSITE_RAMP_STEPS = 5;

    /** 复合装甲 ramp 无伤重置窗 (spec 7.1: 3s 无伤重置 -> 60 tick)。 */
    public static final long COMPOSITE_RAMP_RESET_TICKS = 60L;

    /** 缩小化体型 -> 等效减伤的折算系数 (spec 7.1 缩小化 / 9.2: 体型按 ≈缩减 ×0.5 折算并入净减伤钳制)。 */
    public static final double MINIATURIZATION_SIZE_TO_REDUCTION = 0.5D;

    /** TACZ 子弹伤害类型 namespace (ModDamageTypes javap 实测: tacz:bullet / bullet_ignore_armor / bullet_void…)。 */
    public static final String TACZ_NAMESPACE = "tacz";

    /** TACZ 子弹伤害类型 path 前缀 (BULLET/BULLET_IGNORE_ARMOR/BULLET_VOID/BULLET_VOID_IGNORE_ARMOR 均以此起)。 */
    public static final String TACZ_BULLET_PATH_PREFIX = "bullet";

    /**
     * 复合装甲当前 ramp 减伤率 (spec 7.1: ramp 每受击 +上限/5, 满 5 次达上限 = valueFor; ramp 目标 = 词条上限不再
     * 额外叠加)。hitCount 0 = 本窗首击前 (rate 0); hitCount n ∈ [0,5] -> n×(上限/5), 夹到上限。
     *
     * @param quality  复合装甲品质
     * @param hitCount 当前 3s 窗内累计受击次数 (&gt;=0; 由 {@link CompositeArmorRampTracker} 维护, 已 reset 过)
     * @return 当前 ramp 减伤率 (∈ [0, valueFor])
     */
    public static double compositeRampRate(AffixQuality quality, int hitCount) {
        if (quality == null) {
            throw new IllegalArgumentException("quality must not be null");
        }
        if (hitCount < 0) {
            throw new IllegalArgumentException("hitCount must be >= 0, got " + hitCount);
        }
        double cap = AffixDef.COMPOSITE_ARMOR.valueFor(quality);
        double step = cap / COMPOSITE_RAMP_STEPS;
        double ramp = step * hitCount;
        return Math.min(ramp, cap);
    }

    /**
     * 超高分子聚乙烯护甲层子弹抗性 (spec 7.1: 仅减子弹, 10/15/22/30/40%); 调用方须先判 isBullet 才纳入。
     *
     * @param quality 品质
     * @return 子弹减伤率
     */
    public static double uhmwpeBulletRate(AffixQuality quality) {
        if (quality == null) {
            throw new IllegalArgumentException("quality must not be null");
        }
        return AffixDef.UHMWPE_ARMOR.valueFor(quality);
    }

    /**
     * 重型护甲子弹抗性 (spec 7.1 B 版: 子弹抗 35/42/49% 高/超/闪); 调用方须先判 isBullet 才纳入。近战/爆炸的
     * &lt;T 免疫是另一路 ({@link #heavyArmorImmunityThreshold} + {@link #applyFlatCaps}), 不在此 rate 内。
     *
     * @param quality 品质 (最低高级; 普通/中级档为 0 占位)
     * @return 子弹减伤率
     */
    public static double heavyArmorBulletRate(AffixQuality quality) {
        if (quality == null) {
            throw new IllegalArgumentException("quality must not be null");
        }
        return AffixDef.HEAVY_ARMOR.valueFor(quality);
    }

    /**
     * 重型护甲近战/爆炸单次免疫阈值 T (spec 7.1 B 版 + PENDING: T=8/14/22 高/超/闪)。这是非比例硬阈值 (不在
     * 数值数组里, 数组装子弹抗率), 故按品质独立映射: 高级(RARE)=8 / 超凡(EPIC)=14 / 闪耀(LEGENDARY)=22。重型最低
     * 高级, 故 COMMON/UNCOMMON 不应出现 (异常自然冒泡, 不静默兜底)。近战/爆炸单次净伤 &lt; T 则该次伤害归 0 免疫。
     *
     * @param quality 品质 (须 &gt;= RARE)
     * @return 免疫阈值 T (HP)
     */
    public static double heavyArmorImmunityThreshold(AffixQuality quality) {
        if (quality == null) {
            throw new IllegalArgumentException("quality must not be null");
        }
        switch (quality) {
            case RARE:
                return 8.0D;
            case EPIC:
                return 14.0D;
            case LEGENDARY:
                return 22.0D;
            default:
                throw new IllegalArgumentException(
                        "heavy armor below min usable quality RARE has no immunity threshold: " + quality);
        }
    }

    /**
     * 偏斜护盾按期望值折算的子弹减伤率 (spec 7.1 / 9.2: 每发子弹闪避 8/12/18/25/35%, 按期望值 = 闪避率折算并入
     * 净减伤钳制)。注释口径: 不做真随机整发免疫, 取 EV = 闪避率作为平滑减 DPS 的等效减伤率 (与 spec "按期望值"
     * 一致, 避免方差/整发归零跳变)。仅子弹生效 (不闪 AOE), 调用方须先判 isBullet。
     *
     * @param quality 品质
     * @return 期望值减伤率 (= 闪避率)
     */
    public static double deflectorBulletEvRate(AffixQuality quality) {
        if (quality == null) {
            throw new IllegalArgumentException("quality must not be null");
        }
        return AffixDef.DEFLECTOR_SHIELD.valueFor(quality);
    }

    /**
     * 缩小化体型 -> 等效减伤率 (spec 7.1 / 9.2: -体型 15/25/35/45/55%, 按 ≈缩减 ×0.5 折算为有效减伤)。缩小化
     * {@link AffixDef} 主数值数组装的是血量惩罚 (25/32/40/48/58%), 体型缩减是另一组语义不同的表, 现已并入
     * {@link AffixDef} 副数值作唯一真源 (见 {@link #miniaturizationSizePct}), 折算率 = 体型缩减 ×0.5。
     *
     * @param quality 品质
     * @return 体型折算减伤率
     */
    public static double miniaturizationReductionRate(AffixQuality quality) {
        if (quality == null) {
            throw new IllegalArgumentException("quality must not be null");
        }
        double sizeReductionPct = miniaturizationSizePct(quality);
        return sizeReductionPct * MINIATURIZATION_SIZE_TO_REDUCTION;
    }

    /**
     * 缩小化体型缩减百分比 (spec 7.1 缩小化体型表: 15/25/35/45/55% 普通/中级/高级/超凡/闪耀)。缩小化主数值数组
     * 装的是血量惩罚 (25/32/40/48/58%), 体型是另一组表, 现存于 {@link AffixDef#secondaryValueFor 缩小化副数值}
     * 作唯一真源 (与 {@link ChampionSizeScale} 尺寸系数同源, 防双表漂移); 二者按品质档对齐 (同 ordinal)。
     *
     * @param quality 品质
     * @return 体型缩减百分比 (∈ [0.15, 0.55])
     */
    public static double miniaturizationSizePct(AffixQuality quality) {
        if (quality == null) {
            throw new IllegalArgumentException("quality must not be null");
        }
        // 统一真源: 直接读 AffixDef 缩小化副数值 (原本类硬编码 15/25/35/45/55% 与之等值, 迁走防双表漂移复发)。
        return AffixDef.MINIATURIZATION.secondaryValueFor(quality);
    }

    /**
     * 刚毅护盾单次伤害 FLAT 封顶 (spec 7.1: 120/80/50 HP 高/超/闪; 品质越高封顶越低 = 越硬)。最低高级, 故
     * COMMON/UNCOMMON 不应出现 (前导 0 占位, 异常自然冒泡)。直接读 {@link AffixDef#valueFor} (主数组即 FLAT HP)。
     *
     * @param quality 品质 (须 &gt;= RARE)
     * @return 单次封顶 HP
     */
    public static double fortitudeSingleHitCap(AffixQuality quality) {
        if (quality == null) {
            throw new IllegalArgumentException("quality must not be null");
        }
        double cap = AffixDef.FORTITUDE_SHIELD.valueFor(quality);
        if (cap <= 0.0D) {
            throw new IllegalArgumentException(
                    "fortitude below min usable quality RARE has no cap (leading-zero tier): " + quality);
        }
        return cap;
    }

    /**
     * 把比例源连乘 keep 算出的净伤再施加两个 FLAT 削顶 (spec 7.1 刚毅削顶 + 重型 <T 免疫): 二者均是"让冠军更肉"的
     * 单向硬上限, 在比例净减伤之后做。顺序: 先重型 <T 整次免疫 (近战/爆炸单次净伤 &lt; T 直接归 0), 再刚毅封顶
     * (单次净伤夹到 ≤cap)。两者可叠 (重型互斥刚毅, 实际不同存, 但本函数对二者独立处理, 缺一传 0/NaN 表示无该词条)。
     *
     * @param netDamage         比例净减伤后的净伤 (&gt;=0)
     * @param fortitudeCap      刚毅单次封顶 HP (&gt;0 表示有刚毅; &lt;=0 表示无, 不削)
     * @param heavyThreshold    重型 <T 免疫阈值 (&gt;0 表示有重型; &lt;=0 表示无)
     * @param meleeOrExplosion  本次伤害是否近战或爆炸 (重型 <T 免疫仅对近战/爆炸大额单击生效, 不对子弹)
     * @return 削顶后的净伤
     */
    public static double applyFlatCaps(double netDamage, double fortitudeCap,
                                       double heavyThreshold, boolean meleeOrExplosion) {
        if (netDamage < 0.0D || Double.isNaN(netDamage)) {
            throw new IllegalArgumentException("netDamage must be >= 0, got " + netDamage);
        }
        double result = netDamage;
        // 重型护甲: 近战/爆炸单次净伤 < T 归 0 (整次免疫); 子弹不享此免疫 (子弹走 bullet_resistance 比例减)。
        if (heavyThreshold > 0.0D && meleeOrExplosion && result < heavyThreshold) {
            result = 0.0D;
        }
        // 刚毅护盾: 单次净伤夹到 ≤ cap (FLAT 封顶, 不分来源)。
        if (fortitudeCap > 0.0D && result > fortitudeCap) {
            result = fortitudeCap;
        }
        return result;
    }

    // ============================================================
    // 伤害来源分类 (纯字符串判定, 不 import TACZ/DamageSource; handler 拆 type-id 的 namespace/path 后传入)
    // ============================================================

    /**
     * 是否 TACZ 子弹伤害 (ModDamageTypes javap 实测: namespace=tacz 且 path 以 bullet 起 ——
     * bullet / bullet_ignore_armor / bullet_void / bullet_void_ignore_armor 四型, 均归子弹抗减伤范畴)。
     * 纯字符串判定避免 import com.tacz.* (compileOnly 隔离铁律), handler 经
     * {@code source.typeHolder().unwrapKey().location()} 取 namespace/path 后调本判定。
     *
     * @param typeNamespace 伤害类型 ResourceLocation namespace
     * @param typePath      伤害类型 ResourceLocation path
     * @return 是否子弹伤害
     */
    public static boolean isBulletDamage(String typeNamespace, String typePath) {
        if (typeNamespace == null || typePath == null) {
            return false;
        }
        return TACZ_NAMESPACE.equals(typeNamespace) && typePath.startsWith(TACZ_BULLET_PATH_PREFIX);
    }
}
