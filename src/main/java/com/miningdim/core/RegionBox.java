package com.miningdim.core;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * 一个实例独占 region 的几何值对象 (设计文档 4.1/4.2): 世界坐标原点 + 三维尺寸。
 * 内含世界<->本地坐标换算与扁平体素下标计算。坐标换算是 ChunkGenerator/BiomeSource/离线生成器
 * 的单一权威来源, 任何子系统不得自行推算网格 (4.2)。
 *
 * 体素下标公式 (全 mod 统一, 与设计文档 7.2.1 一致, 不得改序):
 *   idx = (localY * sizeZ + localZ) * sizeX + localX
 *   localX in [0, sizeX), localY in [0, sizeY), localZ in [0, sizeZ)
 * Y 作为最高维使同一 Y 层地址连续, 利于层切片缓存局部性。
 *
 * 值语义: 同原点同尺寸即相等 (record 自动提供 equals/hashCode), 适合作 Map 键与 AABB 相交判定。
 */
public record RegionBox(int originX, int originY, int originZ, int sizeX, int sizeY, int sizeZ) {

    /** 用默认网格几何 (MiningConstants) 构造一个以给定世界 XZ 原点起的 region。 */
    public static RegionBox ofDefault(int originX, int originZ) {
        return new RegionBox(originX, MiningConstants.REGION_MIN_Y, originZ,
                MiningConstants.REGION_SIZE_X, MiningConstants.REGION_HEIGHT, MiningConstants.REGION_SIZE_Z);
    }

    /** 世界坐标 (worldX, worldZ) 是否落在本 region 的 XZ 范围内 (Y 不参与, region 占满整列高度)。 */
    public boolean contains(int worldX, int worldZ) {
        return worldX >= originX && worldX < originX + sizeX
                && worldZ >= originZ && worldZ < originZ + sizeZ;
    }

    /** 世界坐标 (worldX, worldY, worldZ) 是否完全落在本 region 体素盒内 (含 Y)。 */
    public boolean containsWorld(int worldX, int worldY, int worldZ) {
        return worldX >= originX && worldX < originX + sizeX
                && worldY >= originY && worldY < originY + sizeY
                && worldZ >= originZ && worldZ < originZ + sizeZ;
    }

    /** 世界 X -> 本地 X (调用方须先确保 contains, 否则得到越界值)。 */
    public int worldToLocalX(int worldX) {
        return worldX - originX;
    }

    /** 世界 Y -> 本地 Y。 */
    public int worldToLocalY(int worldY) {
        return worldY - originY;
    }

    /** 世界 Z -> 本地 Z。 */
    public int worldToLocalZ(int worldZ) {
        return worldZ - originZ;
    }

    /** 本地 X -> 世界 X。 */
    public int localToWorldX(int localX) {
        return originX + localX;
    }

    /** 本地 Y -> 世界 Y。 */
    public int localToWorldY(int localY) {
        return originY + localY;
    }

    /** 本地 Z -> 世界 Z。 */
    public int localToWorldZ(int localZ) {
        return originZ + localZ;
    }

    /** 本地坐标转世界 BlockPos。 */
    public BlockPos localToWorldPos(int localX, int localY, int localZ) {
        return new BlockPos(originX + localX, originY + localY, originZ + localZ);
    }

    /**
     * 本地坐标 -> 扁平体素一维下标。语义见类注释。调用方负责保证三个分量在 [0, size) 内;
     * 越界传入会得到错误下标 (不抛, 由上游 contains 守卫, 避免热路径分支开销)。
     */
    public int voxelIndex(int localX, int localY, int localZ) {
        return (localY * sizeZ + localZ) * sizeX + localX;
    }

    /** 世界坐标直接 -> 体素下标 (= worldToLocal 后 voxelIndex)。 */
    public int worldVoxelIndex(int worldX, int worldY, int worldZ) {
        return voxelIndex(worldX - originX, worldY - originY, worldZ - originZ);
    }

    /** 体素总数 = sizeX * sizeY * sizeZ (用于分配 bitset)。 */
    public int voxelCount() {
        return sizeX * sizeY * sizeZ;
    }

    /** 两 region AABB 是否相交 (C2: 任意两实例 region 相交即 FAIL, 供分配器校验)。 */
    public boolean intersects(RegionBox other) {
        return originX < other.originX + other.sizeX && originX + sizeX > other.originX
                && originY < other.originY + other.sizeY && originY + sizeY > other.originY
                && originZ < other.originZ + other.sizeZ && originZ + sizeZ > other.originZ;
    }

    /** 转 net.minecraft BoundingBox (闭区间, max = origin + size - 1)。 */
    public BoundingBox toBoundingBox() {
        return new BoundingBox(originX, originY, originZ,
                originX + sizeX - 1, originY + sizeY - 1, originZ + sizeZ - 1);
    }

    /** 从 net.minecraft BoundingBox 构造 (BoundingBox 为闭区间, size = max - min + 1)。 */
    public static RegionBox fromBoundingBox(BoundingBox box) {
        return new RegionBox(box.minX(), box.minY(), box.minZ(),
                box.getXSpan(), box.getYSpan(), box.getZSpan());
    }
}
