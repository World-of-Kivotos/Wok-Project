package com.miningdim.district.core;

import java.util.Objects;

/** 自管区的范围: 维度加含两端的整数方块坐标。 */
public record DistrictBounds(String dimension, int minX, int minZ, int maxX, int maxZ) {

    public DistrictBounds {
        Objects.requireNonNull(dimension, "dimension");
    }

    /** 由两个角 (任意顺序) 得到规整的范围。 */
    public static DistrictBounds ofCorners(String dimension, int x1, int z1, int x2, int z2) {
        return new DistrictBounds(dimension, Math.min(x1, x2), Math.min(z1, z2), Math.max(x1, x2), Math.max(z1, z2));
    }

    /** 面积 (格数), 按 long 计算: 大区的面积会超过 int。 */
    public long area() {
        return ((long) maxX - minX + 1) * ((long) maxZ - minZ + 1);
    }

    /** 同一套坐标的地块范围形状 (去掉维度)。 */
    public PlotArea asArea() {
        return new PlotArea(minX, minZ, maxX, maxZ);
    }

    /** 同一维度且水平范围相交 (闭区间)。 */
    public boolean overlaps(DistrictBounds other) {
        return dimension.equals(other.dimension) && asArea().overlaps(other.asArea());
    }
}
