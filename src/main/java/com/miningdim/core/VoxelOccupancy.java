package com.miningdim.core;

/**
 * 离线生成产出的只读体素占用视图 (设计文档 7.2.1 / D2)。
 * 语义: isAir == true 表示该体素为空气 (可通行空腔), false 表示实心。
 * 这是 MiningChunkGenerator 落方块阶段唯一的数据来源 —— 区块回调里只做"查表填石/空气", 不跑任何算法。
 *
 * 注意: 与原版 ChunkGenerator 的"isSolid"语义相反, 本接口以 air 为 true; 全 mod 统一以本接口为准,
 * 下游若需 solid 取 !isAir。维度顺序与 RegionBox.voxelIndex 一致 (idx = (y*depth+z)*width+x)。
 */
public interface VoxelOccupancy {

    /** region 本地 X 跨度 (= RegionBox.sizeX)。 */
    int width();

    /** region 本地 Y 跨度 (= RegionBox.sizeY)。 */
    int height();

    /** region 本地 Z 跨度 (= RegionBox.sizeZ)。 */
    int depth();

    /**
     * 本地坐标 -> 扁平体素下标, 公式与 RegionBox.voxelIndex 完全一致, 不得改序:
     * idx = (y * depth() + z) * width() + x。调用方保证分量在 [0, size) 内。
     */
    default int index(int x, int y, int z) {
        return (y * depth() + z) * width() + x;
    }

    /** 给定扁平下标是否为空气。下标须由 index(...) 产出且落在 [0, width*height*depth)。 */
    boolean isAir(int idx);

    /** 给定本地坐标是否为空气 (默认经 index 转下标; 实现可覆写做边界裁剪)。 */
    default boolean isAir(int x, int y, int z) {
        return isAir(index(x, y, z));
    }
}
