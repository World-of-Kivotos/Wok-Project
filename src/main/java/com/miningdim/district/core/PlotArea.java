package com.miningdim.district.core;

/**
 * 地块的水平范围: 含两端的整数方块坐标 (Flan 的领地盒子也是闭区间)。构造不校验 min &lt;= max:
 * 客户端送来的反向坐标要在划地块的校验里按 INVALID_AREA 报出来, 而不是在这里抛。
 */
public record PlotArea(int minX, int minZ, int maxX, int maxZ) {

    /** X 方向的格数。 */
    public long width() {
        return (long) maxX - minX + 1;
    }

    /** Z 方向的格数。 */
    public long depth() {
        return (long) maxZ - minZ + 1;
    }

    /** 面积 (格数), 按 long 计算。 */
    public long area() {
        return width() * depth();
    }

    /** 两个角是否分得开 (min &lt;= max)。 */
    public boolean wellFormed() {
        return minX <= maxX && minZ <= maxZ;
    }

    /** 闭区间相交: 共用一个坐标就算重叠, 贴边 (相邻的两格) 不算。 */
    public boolean overlaps(PlotArea other) {
        return minX <= other.maxX && other.minX <= maxX && minZ <= other.maxZ && other.minZ <= maxZ;
    }

    /** other 整块落在本范围之内 (闭区间, 贴边也算在内)。 */
    public boolean contains(PlotArea other) {
        return minX <= other.minX && other.maxX <= maxX && minZ <= other.minZ && other.maxZ <= maxZ;
    }

    /** "{w} × {d}" (乘号两侧各一个空格)。 */
    public String sideText() {
        return width() + " × " + depth();
    }
}
