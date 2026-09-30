package com.miningdim.district.core;

import org.jetbrains.annotations.Nullable;

import java.util.Collection;

/**
 * 划地块 / 调范围的范围校验 (照 webui/src/mock/district-geometry.ts)。按顺序报第一条不满足的:
 * INVALID_AREA (两个角分不开) / OUT_OF_DISTRICT / TOO_CLOSE_TO_EDGE / SIZE_OUT_OF_RANGE / OVERLAPS_PLOT。
 * 范围一律是含两端的整数方块坐标; 贴边不算重叠, 共用一个坐标就算 (Flan 的盒子是闭区间)。
 */
public final class PlotGeometry {

    private PlotGeometry() {
    }

    /**
     * @param others 要比较重叠的地块 (含冻结中的); 调范围时由调用方去掉被调的那块
     * @throws DistrictRuleException 第一条不满足的规则
     */
    public static void validate(DistrictBounds district, PlotArea area, int edgeGap, int minSide, int maxSide,
                                Collection<PlotRecord> others) {
        DistrictRuleException problem = check(district, area, edgeGap, minSide, maxSide, others);
        if (problem != null) {
            throw problem;
        }
    }

    /** 同 {@link #validate}, 但返回问题而不是抛出; 范围合规返回 null。 */
    @Nullable
    public static DistrictRuleException check(DistrictBounds district, PlotArea area, int edgeGap, int minSide,
                                              int maxSide, Collection<PlotRecord> others) {
        if (!area.wellFormed()) {
            return new DistrictRuleException(DistrictError.INVALID_AREA, "坐标必须是整数，且两个角要分得开");
        }
        if (area.minX() < district.minX() || area.maxX() > district.maxX()
                || area.minZ() < district.minZ() || area.maxZ() > district.maxZ()) {
            return new DistrictRuleException(DistrictError.OUT_OF_DISTRICT,
                    "超出自管区范围（X " + district.minX() + " ~ " + district.maxX() + "，Z " + district.minZ() + " ~ "
                            + district.maxZ() + "）",
                    DistrictRuleException.params("minX", String.valueOf(district.minX()),
                            "maxX", String.valueOf(district.maxX()),
                            "minZ", String.valueOf(district.minZ()),
                            "maxZ", String.valueOf(district.maxZ())));
        }
        if ((long) area.minX() - district.minX() < edgeGap || (long) district.maxX() - area.maxX() < edgeGap
                || (long) area.minZ() - district.minZ() < edgeGap || (long) district.maxZ() - area.maxZ() < edgeGap) {
            return new DistrictRuleException(DistrictError.TOO_CLOSE_TO_EDGE,
                    "离自管区边界太近：四周至少要留 " + edgeGap + " 格公共区域",
                    DistrictRuleException.params("edgeGap", String.valueOf(edgeGap)));
        }
        long width = area.width();
        long depth = area.depth();
        if (width < minSide || depth < minSide || width > maxSide || depth > maxSide) {
            return new DistrictRuleException(DistrictError.SIZE_OUT_OF_RANGE,
                    "每边要在 " + minSide + " 到 " + maxSide + " 格之间（现在 " + width + " × " + depth + "）",
                    DistrictRuleException.params("minSide", String.valueOf(minSide),
                            "maxSide", String.valueOf(maxSide),
                            "width", String.valueOf(width),
                            "depth", String.valueOf(depth)));
        }
        for (PlotRecord other : others) {
            if (area.overlaps(other.area())) {
                return new DistrictRuleException(DistrictError.OVERLAPS_PLOT, "和地块 " + other.code() + " 重叠了",
                        DistrictRuleException.params("plotId", other.plotId(), "code", other.code()));
            }
        }
        return null;
    }
}
