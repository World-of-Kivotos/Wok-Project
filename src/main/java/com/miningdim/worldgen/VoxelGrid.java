package com.miningdim.worldgen;

import com.miningdim.core.RegionBox;
import com.miningdim.core.VoxelOccupancy;

import java.util.BitSet;

/**
 * 离线生成阶段在工作线程上独占的可变体素掩码 (设计文档 7.2.1)。底层 java.util.BitSet 扁平一维,
 * 一个 bit 表示一格, 语义 true=空气 (可通行空腔), 与 core.VoxelOccupancy 一致。
 *
 * 索引公式与 RegionBox.voxelIndex / VoxelOccupancy.index 同一不变量, 不得改序:
 *   idx = (localY * sizeZ + localZ) * sizeX + localX
 * 由 RegionBox 提供尺寸与换算, 本类不自行推算几何 (4.2 单一权威)。
 *
 * 线程契约: 本对象只在单个离线工作线程内串行读写 (三阶段管线), 不跨线程共享; 完成后由
 * OfflineCaveGenerator 调用 freeze() 产出 immutable 的 VoxelOccupancy 视图供主线程查表 (D8)。
 * 因此本类内部无同步, 可变期与冻结视图严格分离。
 */
public final class VoxelGrid {

    private final int width;
    private final int height;
    private final int depth;
    private final BitSet bits;

    /** 以 region 几何分配全实心 (全 false) 的体素网格; 初始无空气, 由 Skeleton 阶段挖空 (7.3.1)。 */
    public VoxelGrid(RegionBox box) {
        this.width = box.sizeX();
        this.height = box.sizeY();
        this.depth = box.sizeZ();
        // BitSet 默认全 0 即全实心, 与 Stage 1 输入"空网格 (全 solid)"语义一致 (7.3.1)。
        this.bits = new BitSet(box.voxelCount());
    }

    private VoxelGrid(int width, int height, int depth, BitSet bits) {
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.bits = bits;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int depth() {
        return depth;
    }

    /** 扁平体素总数 = W*H*D。 */
    public int voxelCount() {
        return width * height * depth;
    }

    /**
     * 本地坐标 -> 扁平下标 (与 VoxelOccupancy.index / RegionBox.voxelIndex 同一公式, 不得改序)。
     * 调用方保证三分量 ∈ [0,size); 越界由调用方的 inBounds 守卫, 热路径不分支。
     */
    public int index(int x, int y, int z) {
        return (y * depth + z) * width + x;
    }

    /** 本地坐标是否在网格盒内 (BFS/雕刻邻居扩展时的硬边界, 7.5/7.7: box 外即墙)。 */
    public boolean inBounds(int x, int y, int z) {
        return x >= 0 && x < width && y >= 0 && y < height && z >= 0 && z < depth;
    }

    /** 该格是否空气。下标须由 index(...) 产出。 */
    public boolean isAir(int idx) {
        return bits.get(idx);
    }

    /** 本地坐标是否空气。 */
    public boolean isAir(int x, int y, int z) {
        return bits.get(index(x, y, z));
    }

    /** 置该格为空气 (挖空)。 */
    public void setAir(int idx) {
        bits.set(idx);
    }

    /** 置本地坐标为空气。 */
    public void setAir(int x, int y, int z) {
        bits.set(index(x, y, z));
    }

    /** 置该格为实心 (填实, 用于孤岛剔除 7.5.2)。 */
    public void setSolid(int idx) {
        bits.clear(idx);
    }

    /** 置本地坐标为实心。 */
    public void setSolid(int x, int y, int z) {
        bits.clear(index(x, y, z));
    }

    /** 当前空气体素数 (用于分量体积统计与日志)。 */
    public int airCount() {
        return bits.cardinality();
    }

    /**
     * 冻结为只读 VoxelOccupancy 视图供 ChunkGenerator 查表 (D8 immutable 输出)。
     * 拷贝底层 BitSet 以彻底切断与本可变网格的别名, 防止冻结后误改污染已发布视图。
     * 维度顺序与本网格、与 RegionBox.voxelIndex 完全一致 (契约要求)。
     */
    public VoxelOccupancy freeze() {
        final long[] words = bits.toLongArray();
        final int fw = width;
        final int fh = height;
        final int fd = depth;
        return new VoxelOccupancy() {
            @Override
            public int width() {
                return fw;
            }

            @Override
            public int height() {
                return fh;
            }

            @Override
            public int depth() {
                return fd;
            }

            @Override
            public boolean isAir(int idx) {
                // BitSet.toLongArray 省略了高位全 0 的 word, 故越过数组末尾的下标即实心 (false)。
                int wordIdx = idx >> 6;
                if (wordIdx >= words.length) {
                    return false;
                }
                return (words[wordIdx] & (1L << (idx & 63))) != 0L;
            }
        };
    }

    /** 调试/回归用: 暴露底层 bit 的 long 视图, 供逐位 XOR 对比同种子双跑一致性 (7.6.3)。 */
    public long[] toLongArray() {
        return bits.toLongArray();
    }

    /** 拷贝当前网格 (降级回退路径可能需要保留中间态); 深拷贝 BitSet。 */
    public VoxelGrid copy() {
        return new VoxelGrid(width, height, depth, (BitSet) bits.clone());
    }
}
