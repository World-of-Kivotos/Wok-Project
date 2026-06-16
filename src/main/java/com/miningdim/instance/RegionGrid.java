package com.miningdim.instance;

import com.miningdim.core.Difficulty;
import com.miningdim.core.MiningConstants;
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
 */
public final class RegionGrid {

    private final int strideX;
    private final int strideZ;
    private final int originX;
    private final int originZ;

    /** 第 i 位为 1 表示螺旋槽位 i 已占用。位序与 slotToCell 螺旋编号一一对应。 */
    private final BitSet occupancy;

    public RegionGrid() {
        this(MiningConstants.REGION_STRIDE_X, MiningConstants.REGION_STRIDE_Z,
                MiningConstants.REGION_ORIGIN_X, MiningConstants.REGION_ORIGIN_Z);
    }

    public RegionGrid(int strideX, int strideZ, int originX, int originZ) {
        this.strideX = strideX;
        this.strideZ = strideZ;
        this.originX = originX;
        this.originZ = originZ;
        this.occupancy = new BitSet();
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

    /** 给定螺旋槽位号生成 RegionBox (单元几何 = ofDefault, 原点按螺旋格 * stride 平移)。 */
    public RegionBox regionForSlot(int slot) {
        long[] cell = slotToCell(slot);
        int regionOriginX = originX + (int) cell[0] * strideX;
        int regionOriginZ = originZ + (int) cell[1] * strideZ;
        return RegionBox.ofDefault(regionOriginX, regionOriginZ);
    }

    /** 给定网格单元坐标 (cellX, cellZ) 直接生成 RegionBox (固定区域用; 与 regionForSlot 同几何, 跳过螺旋编号)。 */
    public RegionBox regionForCell(int cellX, int cellZ) {
        int regionOriginX = originX + cellX * strideX;
        int regionOriginZ = originZ + cellZ * strideZ;
        return RegionBox.ofDefault(regionOriginX, regionOriginZ);
    }

    /**
     * 某难度的固定区域 RegionBox (R1)。难度 -> 网格单元 X 列 (Difficulty.regionCellX), Z 列恒为
     * MiningConstants.FIXED_REGION_CELL_Z。三难度并排只在 X 轴展开, 经 stride 平移后天然带 REGION_GAP 缓冲带,
     * 满足 C2 不相交 + D1 实心隔离。本映射是三固定实例几何的单一权威 (其余子系统不得自行推算)。
     */
    public RegionBox fixedRegionFor(Difficulty difficulty) {
        return regionForCell(difficulty.regionCellX(), MiningConstants.FIXED_REGION_CELL_Z);
    }

    /** RegionBox -> 螺旋槽位号 (free/markOccupied 用)。box 原点须落在网格上, 否则抛 IAE。 */
    public int slotForRegion(RegionBox box) {
        if ((box.originX() - originX) % strideX != 0 || (box.originZ() - originZ) % strideZ != 0) {
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
