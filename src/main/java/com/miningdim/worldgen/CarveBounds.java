package com.miningdim.worldgen;

import com.miningdim.core.MiningConstants;

/**
 * 整块 region "可雕刻" local_y 区间 (设计文档 6.5; R2 改: 难度不再分子盒, 整块 384 高都属同一难度,
 * 故可雕刻 Y 区间与难度无关)。骨架与噪声阶段只能在此内收区间挖空, 严禁触及顶板/底板, 否则雕穿 D4 边界。
 *
 * 内边距 SAFE_INSET=4 格 (6.5 建议初值 PENDING待校验): region 全高 local_y 上下各内收 4 格,
 * 保证顶/底封板在任何随机种子下不被噪声边缘咬穿。XZ 方向也内收 EDGE_INSET, 使骨架不贴 region 外壁
 * (留实心边墙, 与缓冲带共同隔离相邻实例)。
 *
 * 竖井不在此约束内: 跨区连通由 ConnectivityFix 的 A* 显式开口 (6.5 表末行)。
 */
public final class CarveBounds {

    private CarveBounds() {
    }

    /** region 全高 local_y 上下各内收的安全格数 (6.5)。 */
    public static final int SAFE_INSET = 4;

    /** region XZ 外壁内收格数, 使骨架不贴 region 边界, 留实心边墙。 */
    public static final int EDGE_INSET = 4;

    /** 可雕刻 local_y 下界 (含) = region 全高下界 + 安全内边距 (R2: 整块同一难度, 不再依赖难度子盒)。 */
    public static int minLocalY() {
        return MiningConstants.REGION_FULL_MIN_LOCAL_Y + SAFE_INSET;
    }

    /** 可雕刻 local_y 上界 (含) = region 全高上界 - 安全内边距 (R2: 整块同一难度, 不再依赖难度子盒)。 */
    public static int maxLocalY() {
        return MiningConstants.REGION_FULL_MAX_LOCAL_Y - SAFE_INSET;
    }

    /** 可雕刻 local_x 下界 (含)。 */
    public static int minLocalX() {
        return EDGE_INSET;
    }

    /** 可雕刻 local_x 上界 (含)。 */
    public static int maxLocalX() {
        return MiningConstants.REGION_SIZE_X - 1 - EDGE_INSET;
    }

    /** 可雕刻 local_z 下界 (含)。 */
    public static int minLocalZ() {
        return EDGE_INSET;
    }

    /** 可雕刻 local_z 上界 (含)。 */
    public static int maxLocalZ() {
        return MiningConstants.REGION_SIZE_Z - 1 - EDGE_INSET;
    }
}
