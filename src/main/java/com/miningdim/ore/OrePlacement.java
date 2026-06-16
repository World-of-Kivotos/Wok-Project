package com.miningdim.ore;

import com.miningdim.core.RegionBox;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;

/**
 * 离线铺矿产物 (设计文档 8.8 输出): 不可变的"体素下标 -> 矿种"映射 + 其所属 regionBox。
 *
 * 落地表示选用 int(体素下标) -> OreType, 而非 Map<BlockPos, ...>: 体素下标紧凑、查询零对象分配,
 * 与 RegionBox.worldVoxelIndex / VoxelOccupancy.index 同序 (RegionBox 类注释), 便于 MiningChunkGenerator
 * 在区块填充阶段按世界坐标 O(1) 查表 (8.5 末: 纯读, 无算法)。
 *
 * 线程: 构造发生在工作线程 (离线计算), 构造完成后整表不可变 (内部 map 不再改写), 故主线程读区块时无需同步。
 */
public final class OrePlacement {

    private final RegionBox regionBox;
    /** key = RegionBox.voxelIndex(localX,localY,localZ); value = 该体素铺设的矿种。不可变。 */
    private final Map<Integer, OreType> oreByVoxel;
    /** 各矿种实际铺设体素数 (8.8 失败处理: 实铺量, 供日志/调试)。 */
    private final Map<OreType, Integer> placedCount;

    OrePlacement(RegionBox regionBox, Map<Integer, OreType> oreByVoxel, Map<OreType, Integer> placedCount) {
        this.regionBox = regionBox;
        this.oreByVoxel = Map.copyOf(oreByVoxel);
        this.placedCount = Map.copyOf(placedCount);
    }

    /** 该铺矿表归属的 region 几何。 */
    public RegionBox regionBox() {
        return regionBox;
    }

    /**
     * 按世界坐标查矿石方块状态; 该坐标无铺矿则返回 null (MiningChunkGenerator 据此决定是否替换方块)。
     * 越界坐标视为无铺矿返回 null (不抛: 区块填充会扫到 region 边界外的列, 属正常)。
     */
    public BlockState blockStateAt(int worldX, int worldY, int worldZ) {
        if (!regionBox.containsWorld(worldX, worldY, worldZ)) {
            return null;
        }
        OreType ore = oreByVoxel.get(regionBox.worldVoxelIndex(worldX, worldY, worldZ));
        return ore == null ? null : ore.blockStateAt(worldY);
    }

    /** 按世界坐标查矿种 (无则 null)。供需要原始矿种而非方块态的查询方 (如统计)。 */
    public OreType oreAt(int worldX, int worldY, int worldZ) {
        if (!regionBox.containsWorld(worldX, worldY, worldZ)) {
            return null;
        }
        return oreByVoxel.get(regionBox.worldVoxelIndex(worldX, worldY, worldZ));
    }

    /** 某体素是否铺了矿 (按世界坐标)。供陷阱系统经接口查"富矿"无需暴露具体矿种 (TrapSystem 复用)。 */
    public boolean hasOreAt(int worldX, int worldY, int worldZ) {
        return regionBox.containsWorld(worldX, worldY, worldZ)
                && oreByVoxel.containsKey(regionBox.worldVoxelIndex(worldX, worldY, worldZ));
    }

    /** 铺矿总块数 (所有矿种实铺量求和)。 */
    public int totalOreBlocks() {
        return oreByVoxel.size();
    }

    /** 某矿种实际铺设块数 (8.8 收尾统计; 未铺设矿种返回 0)。 */
    public int placedCount(OreType ore) {
        return placedCount.getOrDefault(ore, 0);
    }

    /** 铺矿表只读快照 (体素下标 -> 矿种), 供持久化/调试遍历。 */
    public Map<Integer, OreType> oreByVoxel() {
        return oreByVoxel;
    }

    /** 把单个铺矿条目转世界 BlockPos (持久化/可视化用; 不在热路径)。 */
    public BlockPos voxelToWorldPos(int voxelIndex) {
        int sizeX = regionBox.sizeX();
        int sizeZ = regionBox.sizeZ();
        int localX = voxelIndex % sizeX;
        int rest = voxelIndex / sizeX;
        int localZ = rest % sizeZ;
        int localY = rest / sizeZ;
        return regionBox.localToWorldPos(localX, localY, localZ);
    }
}
