package com.miningdim.instance;

import com.miningdim.core.Difficulty;
import com.miningdim.core.MiningConstants;
import com.miningdim.core.MiningServices;
import com.miningdim.core.RegionBox;

import java.util.BitSet;

/**
 * 矿山维度的 region 网格分配器 (设计文档 4.2 / 12.3 / 12.8)。把无限平面按 STRIDE = SIZE + GAP 的
 * 步长切成离散槽位, 每槽对应一个不与邻槽重叠 (留 >=1 区块实心缓冲带, 符合 D1/C2) 的 RegionBox。
 *
 * 槽位编号: 从原点螺旋向外枚举 (slot 0 = 原点, 1.. 顺时针环绕)。螺旋使先分配的实例聚在原点附近,
 * 利于强加载区块的空间局部性, 同时网格理论无界 (容量约束由 globalCap 决定, 非网格, 12.3)。
 * 槽位与几何的映射是全 mod 单一权威: 其他子系统不得自行推算网格 (4.2)。
 *
 * 占用状态以 BitSet 持有, 与 MiningSavedData.regionOccupancy 字节数组双向同步 (toByteArray/valueOf);
 * 写操作只在主线程 (由 InstanceManager 串行, 12.4)。本类不依赖任何子系统实现, 只用 core 几何常量。
 *
 * R1 固定区域: 三难度各占一个固定网格单元 (Difficulty.regionCellX, Z=FIXED_REGION_CELL_Z), 经 fixedRegionFor
 * 派生 RegionBox。固定区域沿用同一螺旋槽位映射与占用位图 (markOccupied/slotForRegion), 与动态分配机制共存,
 * 几何与持久化自洽; 本模式下动态 claim 不再被触发 (InstanceManager 只预建三固定实例)。
 *
 * 网格步长现在由 config 的 instance.regionSizeChunks / instance.bufferChunks 派生 (worldRestart, 见
 * IMiningConfig), MiningConstants 里的编译期常量只作默认值镜像, 不再是运行期几何的唯一来源 (F063)。
 *
 * 滑动 region (D3/13.4): regionAtOrigin 按当前 size 几何在任意世界原点直接建盒, 不查也不写占用位图。
 * 原因: 螺旋槽号随网格单元半径平方增长 (cellToSlot ~ (2r)^2), 滑动 region 的坐标会被 MiningSavedData 的
 * 单向游标推到千万级, 一旦 markOccupied 就会把 BitSet 撑到 GB 级内存; 滑动 region 的"永不复用"由该游标
 * 单向推进保证, 与本类的螺旋占用位图机制无关, 两者刻意解耦。
 */
public final class RegionGrid {

    private final int sizeX;
    private final int sizeZ;
    private final int strideX;
    private final int strideZ;
    private final int originX;
    private final int originZ;

    /** 第 i 位为 1 表示螺旋槽位 i 已占用。位序与 slotToCell 螺旋编号一一对应。 */
    private final BitSet occupancy;

    public RegionGrid() {
        this(MiningServices.config().regionSizeChunks(), MiningServices.config().bufferChunks(),
                MiningConstants.REGION_ORIGIN_X, MiningConstants.REGION_ORIGIN_Z);
    }

    /** 显式几何构造器 (测试/运维覆盖用); size/stride 派生公式与无参构造一致, 只是来源换成显式实参而非 config。 */
    public RegionGrid(int regionSizeChunks, int bufferChunks, int originX, int originZ) {
        this.sizeX = regionSizeChunks * 16;
        this.sizeZ = regionSizeChunks * 16;
        int gap = bufferChunks * 16;
        this.strideX = sizeX + gap;
        this.strideZ = sizeZ + gap;
        this.originX = originX;
        this.originZ = originZ;
        this.occupancy = new BitSet();
    }

    /** region 在 X 方向格数 (= regionSizeChunks * 16, 滑动 region 计算 frontier 步进时用)。 */
    public int sizeX() {
        return sizeX;
    }

    /** region 在 Z 方向格数。 */
    public int sizeZ() {
        return sizeZ;
    }

    /** 从持久化字节还原占用位图 (启动重建, 12.8)。 */
    public void loadOccupancy(byte[] bytes) {
        occupancy.clear();
        occupancy.or(BitSet.valueOf(bytes));
    }

    /** 导出占用位图字节供持久化 (claim/free 后由 InstanceManager 写回 SavedData)。 */
    public byte[] saveOccupancy() {
        return occupancy.toByteArray();
    }

    /**
     * 分配下一个空闲槽位并返回其 RegionBox, 标记占用 (12.3 claimNextFreeRegion)。空闲槽优先复用
     * 低编号 (被 GC 释放的槽会被再次取到), 杜绝 region 无限外扩。仅主线程调用。
     */
    public RegionBox claimNextFreeRegion() {
        int slot = occupancy.nextClearBit(0);
        occupancy.set(slot);
        return regionForSlot(slot);
    }

    /**
     * 标记某槽位为空闲 (12.6 free)。按 RegionBox 反推槽位 (几何可逆), 与 claim 对称。
     * 若该 box 不对应任何合法槽位 (数据损坏), 抛 IllegalArgumentException 自然冒泡, 不静默吞 (C9)。
     */
    public void free(RegionBox box) {
        int slot = slotForRegion(box);
        occupancy.clear(slot);
    }

    /** 直接占用某 RegionBox 对应槽位 (启动重建期把 instances 的 regionBox 标占, 12.8 交叉校验)。 */
    public void markOccupied(RegionBox box) {
        occupancy.set(slotForRegion(box));
    }

    /** 某 box 对应槽位是否已占用 (12.8 冲突检测)。 */
    public boolean isOccupied(RegionBox box) {
        return occupancy.get(slotForRegion(box));
    }

    /** 当前已占用槽位数 (诊断/校验)。 */
    public int occupiedCount() {
        return occupancy.cardinality();
    }

    /** 给定螺旋槽位号生成 RegionBox (原点按螺旋格 * stride 平移, 尺寸取本实例的 sizeX/sizeZ)。 */
    public RegionBox regionForSlot(int slot) {
        long[] cell = slotToCell(slot);
        int regionOriginX = originX + (int) cell[0] * strideX;
        int regionOriginZ = originZ + (int) cell[1] * strideZ;
        return box(regionOriginX, regionOriginZ);
    }

    /** 给定网格单元坐标 (cellX, cellZ) 直接生成 RegionBox (固定区域用; 与 regionForSlot 同几何, 跳过螺旋编号)。 */
    public RegionBox regionForCell(int cellX, int cellZ) {
        int regionOriginX = originX + cellX * strideX;
        int regionOriginZ = originZ + cellZ * strideZ;
        return box(regionOriginX, regionOriginZ);
    }

    /**
     * 按当前 size 几何在给定世界原点直接建盒 (滑动 region 专用), 不查也不写占用位图 —— 理由见类注释
     * "滑动 region" 段: 螺旋位图与滑动坐标的"永不复用"保证是两套互不依赖的机制。
     */
    public RegionBox regionAtOrigin(int worldOriginX, int worldOriginZ) {
        return box(worldOriginX, worldOriginZ);
    }

    /** 按本实例几何 (originY/sizeY 固定取 MiningConstants, XZ size 取 config 派生值) 建 RegionBox。 */
    private RegionBox box(int regionOriginX, int regionOriginZ) {
        return new RegionBox(regionOriginX, MiningConstants.REGION_MIN_Y, regionOriginZ,
                sizeX, MiningConstants.REGION_HEIGHT, sizeZ);
    }

    /**
     * 某难度的固定区域 RegionBox (R1)。难度 -> 网格单元 X 列 (Difficulty.regionCellX), Z 列恒为
     * MiningConstants.FIXED_REGION_CELL_Z。三难度并排只在 X 轴展开, 经 stride 平移后天然带 REGION_GAP 缓冲带,
     * 满足 C2 不相交 + D1 实心隔离。本映射是三固定实例几何的单一权威 (其余子系统不得自行推算)。
     */
    public RegionBox fixedRegionFor(Difficulty difficulty) {
        return regionForCell(difficulty.regionCellX(), MiningConstants.FIXED_REGION_CELL_Z);
    }

    /**
     * 某 box 原点是否落在本网格 stride 上 (不抛异常)。供不确定是否为网格成员的调用方先探测 ——
     * 固定/滑动实例的几何独立于网格 (regionAtOrigin 不查也不写位图), 其 box 不保证对齐, 不能直接
     * 走 slotForRegion (会抛 IAE); 见 InstanceManager 对固定/滑动实例的位图豁免用法。
     */
    public boolean isAligned(RegionBox box) {
        return (box.originX() - originX) % strideX == 0 && (box.originZ() - originZ) % strideZ == 0;
    }

    /** RegionBox -> 螺旋槽位号 (free/markOccupied 用)。box 原点须落在网格上, 否则抛 IAE。 */
    public int slotForRegion(RegionBox box) {
        if (!isAligned(box)) {
            throw new IllegalArgumentException(
                    "RegionBox origin (" + box.originX() + "," + box.originZ() + ") not aligned to grid stride");
        }
        int cellX = (box.originX() - originX) / strideX;
        int cellZ = (box.originZ() - originZ) / strideZ;
        return cellToSlot(cellX, cellZ);
    }

    // ---- 螺旋编号 <-> 网格坐标 双向映射 (纯几何, 无状态) ----

    /**
     * 螺旋槽位号 -> 网格单元坐标 (cellX, cellZ)。以 (0,0) 为中心顺时针向外环绕:
     * 0 -> (0,0); 1 -> (1,0); 2 -> (1,1); 3 -> (0,1); 4 -> (-1,1); ... 标准 Ulam 螺旋。
     * 返回 long[2] 避免装箱热路径开销。
     */
    static long[] slotToCell(int slot) {
        if (slot == 0) {
            return new long[]{0, 0};
        }
        // 找到该 slot 所在的环 ring (ring r 覆盖编号区间 [(2r-1)^2, (2r+1)^2)).
        int r = (int) Math.ceil((Math.sqrt(slot + 1.0) - 1.0) / 2.0);
        int ringStart = (2 * r - 1) * (2 * r - 1); // 该环首个 slot 号
        int offset = slot - ringStart;             // 环内偏移 [0, 8r)
        int sideLen = 2 * r;                        // 每边步数
        int side = offset / sideLen;                // 0=右,1=上,2=左,3=下
        int posInSide = offset % sideLen;

        long x;
        long z;
        switch (side) {
            case 0 -> { // 右边: 从 (r, -r+1) 向上到 (r, r)
                x = r;
                z = -r + 1 + posInSide;
            }
            case 1 -> { // 上边: 从 (r-1, r) 向左到 (-r, r)
                x = r - 1 - posInSide;
                z = r;
            }
            case 2 -> { // 左边: 从 (-r, r-1) 向下到 (-r, -r)
                x = -r;
                z = r - 1 - posInSide;
            }
            default -> { // 下边: 从 (-r+1, -r) 向右到 (r, -r)
                x = -r + 1 + posInSide;
                z = -r;
            }
        }
        return new long[]{x, z};
    }

    /** 网格单元坐标 -> 螺旋槽位号 (slotToCell 的逆)。 */
    static int cellToSlot(int x, int z) {
        if (x == 0 && z == 0) {
            return 0;
        }
        int r = Math.max(Math.abs(x), Math.abs(z));
        int ringStart = (2 * r - 1) * (2 * r - 1);
        int sideLen = 2 * r;
        int side;
        int posInSide;
        if (x == r && z > -r) {            // 右边
            side = 0;
            posInSide = z - (-r + 1);
        } else if (z == r && x < r) {      // 上边
            side = 1;
            posInSide = (r - 1) - x;
        } else if (x == -r && z < r) {     // 左边
            side = 2;
            posInSide = (r - 1) - z;
        } else {                           // 下边 (z == -r && x > -r)
            side = 3;
            posInSide = x - (-r + 1);
        }
        return ringStart + side * sideLen + posInSide;
    }
}
