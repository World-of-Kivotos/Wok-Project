package com.miningdim.champion;

import java.util.Objects;

/**
 * 一条已盖章的词条选择 (词条 + 实际品质)。spawn 期分配器 {@link PointBudget} 的输入单元, 同步到 Champions
 * 自定义词条实例 NBT (品质无 Champions 对应字段, 须自存)。不可变值对象, 无世界引用。
 *
 * 实际品质已经 {@link AffixQuality#clampTo} 按星级夹断 + 经 {@link AffixDef#minUsableQuality} 抬到最低可用档,
 * 故构造期校验该品质在词条的有效区间 [minUsable, maxQualityOfStar] 内, 越界抛 IllegalArgumentException (不掩盖)。
 */
public final class AffixSelection {

    private final AffixDef affix;
    private final AffixQuality quality;

    public AffixSelection(AffixDef affix, AffixQuality quality) {
        this.affix = Objects.requireNonNull(affix, "affix");
        this.quality = Objects.requireNonNull(quality, "quality");
        if (quality.ordinal() < affix.minUsableQuality().ordinal()) {
            throw new IllegalArgumentException(
                    "quality " + quality + " below min usable " + affix.minUsableQuality() + " for " + affix);
        }
    }

    public AffixDef affix() {
        return affix;
    }

    public AffixQuality quality() {
        return quality;
    }

    /** 本选择的点数成本 (= affix.costAt(quality))。 */
    public int cost() {
        return affix.costAt(quality);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AffixSelection other)) {
            return false;
        }
        return affix == other.affix && quality == other.quality;
    }

    @Override
    public int hashCode() {
        return Objects.hash(affix, quality);
    }

    @Override
    public String toString() {
        return affix.name() + "[" + quality + "]";
    }
}
