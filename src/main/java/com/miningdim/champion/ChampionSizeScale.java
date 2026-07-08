package com.miningdim.champion;

/**
 * 体型词条 (巨大化/缩小化) 的尺寸系数 + 移速补偿 + 降档序列纯逻辑 (ChampionStarAffix spec 7.1 体型 + 9A.3 #17
 * 体型渲染 + 9.4 形态守卫)。把 {@link AffixDef} 的体型副数值 (secondaryValues) 折算成【渲染/碰撞箱缩放系数】,
 * 并给出巨大化的移速补偿量与形态守卫降档时用的品质下探序列。
 *
 * <p>真源单一化 (防双表漂移): 体型缩放系数唯一来自 {@link AffixDef#secondaryValueFor} —— 巨大化
 * {@code x(1+sv)} = 1.25/1.40/1.60/1.85/2.20, 缩小化 {@code x(1-sv)} = 0.85/0.75/0.65/0.55/0.45。缩小化的
 * 等效减伤折算 ({@link ChampionDamageReduction#miniaturizationSizePct}) 亦读同一副数值, 二者一致性由
 * {@code ChampionSizeScaleGameTests} 锁死。
 *
 * <p>与血量乘数的分工 (关键): {@link ChampionHpConversion#sizeMultiplier} 读的是体型词条的【主数值】(血量增减
 * 30/50/80/120/180% 与 25/32/40/48/58%), 本类读的是【副数值】(尺寸增减) —— 同一词条两组语义不同的表, 血量走主、
 * 尺寸走副, 互不串用。
 *
 * <p>纯数据/常量, 无世界/实体引用 (与 {@link ChampionHpConversion} 同范式), GameTest 直接断言 (删折算必挂)。
 * 世界侧 (碰撞箱缩放 / 客户端渲染缩放 / 形态守卫 noCollision) 由 {@code integration.ChampionSizeHandler} 与
 * {@code client.ChampionSizeRenderClient} 消费本类系数。
 */
public final class ChampionSizeScale {

    /**
     * 巨大化移速补偿步长 (spec 用户裁定 2026-07-07: +10% × 品质档位序号): COMMON=+10% .. LEGENDARY=+50%。
     * 巨大化把碰撞箱放大后原速会显得迟缓, 按品质线性补偿移速使体感与体型匹配; 仅巨大化, 缩小化不补偿。
     */
    public static final double GIGANTISM_SPEED_STEP = 0.10D;

    private ChampionSizeScale() {
    }

    /**
     * 体型词条的尺寸缩放系数 (渲染 poseStack 与碰撞箱统一乘数): 巨大化 {@code 1 + 副数值}, 缩小化
     * {@code 1 - 副数值}。仅接受 SIZE 互斥族两条词条; 传入非体型词条属调用方 bug, 抛不掩盖。
     *
     * @param def     体型词条 (须为 {@link AffixDef#GIGANTISM} 或 {@link AffixDef#MINIATURIZATION})
     * @param quality 品质
     * @return 尺寸缩放系数 (巨大化 &gt;1, 缩小化 &lt;1)
     */
    public static double sizeMultiplierFor(AffixDef def, AffixQuality quality) {
        if (def == null) {
            throw new IllegalArgumentException("def must not be null");
        }
        if (quality == null) {
            throw new IllegalArgumentException("quality must not be null");
        }
        switch (def) {
            case GIGANTISM:
                return 1.0D + def.secondaryValueFor(quality);
            case MINIATURIZATION:
                return 1.0D - def.secondaryValueFor(quality);
            default:
                throw new IllegalArgumentException("not a size affix (SIZE mutex family): " + def);
        }
    }

    /**
     * 巨大化移速补偿量 (MULTIPLY_TOTAL modifier 的 amount): {@code +0.10 × (品质序号 + 1)}, 即
     * COMMON=+0.10 / UNCOMMON=+0.20 / RARE=+0.30 / EPIC=+0.40 / LEGENDARY=+0.50。仅巨大化调用 (缩小化不补偿);
     * 由 {@code ChampionSizeHandler} 按品质挂瞬态移速修饰。
     *
     * @param quality 巨大化品质
     * @return 移速补偿系数 (∈ [0.10, 0.50])
     */
    public static double speedBonusFor(AffixQuality quality) {
        if (quality == null) {
            throw new IllegalArgumentException("quality must not be null");
        }
        return GIGANTISM_SPEED_STEP * (quality.ordinal() + 1);
    }

    /**
     * 形态守卫降档序列 (spec 9.4 生成期守卫: 放大 AABB 不容纳则体型降一档重验): 品质向下一档
     * (LEGENDARY→EPIC→RARE→UNCOMMON→COMMON), COMMON 到底不再降 (巨大化最低仍是 COMMON 的 1.25×, 不缩回原体型)。
     * 只降体型系数不动词条/点数 (故守卫用降档后的品质只重算尺寸, 不改 capability 里存的实际词条品质)。
     *
     * @param quality 当前品质
     * @return 下一档品质 (COMMON 返回自身)
     */
    public static AffixQuality downgrade(AffixQuality quality) {
        if (quality == null) {
            throw new IllegalArgumentException("quality must not be null");
        }
        int lower = quality.ordinal() - 1;
        if (lower < 0) {
            return quality; // COMMON: 到底不再降 (巨大化恒 ≥ COMMON 1.25×)。
        }
        return AffixQuality.values()[lower];
    }
}
