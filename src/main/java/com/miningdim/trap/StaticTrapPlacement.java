package com.miningdim.trap;

import com.miningdim.core.RegionBox;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 离线静态陷阱布点产物 (设计文档 9.5 末): 不可变 "体素下标 -> 陷阱类型" 表 + 触发体素列表 + regionBox。
 *
 * 与 OrePlacement 同构: 用 int(体素下标) 紧凑表示, 与 RegionBox.voxelIndex 同序, 供 MiningChunkGenerator
 * 区块填充阶段查表放置陷阱方块 (引信块/假矿/承重砂砾) 与运行期触发判定查表。整表构造后不可变 (D2/D3 可复现)。
 */
public final class StaticTrapPlacement {

    private final RegionBox regionBox;
    /** key = RegionBox.voxelIndex; value = 该体素的静态陷阱类型。不可变。 */
    private final Map<Integer, TrapType> trapByVoxel;
    /** 已落地致死陷阱的世界坐标 (9.5 步骤4 间距校验 + 出生半径/身后刷怪禁区查询用)。 */
    private final List<BlockPos> lethalPositions;

    StaticTrapPlacement(RegionBox regionBox, Map<Integer, TrapType> trapByVoxel, List<BlockPos> lethalPositions) {
        this.regionBox = regionBox;
        this.trapByVoxel = Map.copyOf(trapByVoxel);
        this.lethalPositions = List.copyOf(lethalPositions);
    }

    public RegionBox regionBox() {
        return regionBox;
    }

    /** 按世界坐标查静态陷阱类型 (无则 null)。MiningChunkGenerator 据此放置陷阱方块。 */
    public TrapType trapAt(int worldX, int worldY, int worldZ) {
        if (!regionBox.containsWorld(worldX, worldY, worldZ)) {
            return null;
        }
        return trapByVoxel.get(regionBox.worldVoxelIndex(worldX, worldY, worldZ));
    }

    /** 某世界坐标是否落在任一已布致死陷阱的作用半径内 (9.7 身后刷怪禁区 / 11.2 出生非陷阱区判据)。 */
    public boolean inLethalTrapRadius(int worldX, int worldY, int worldZ) {
        for (BlockPos p : lethalPositions) {
            TrapType t = trapAt(p.getX(), p.getY(), p.getZ());
            double r = (t == null ? 0.0 : t.radius());
            double dx = worldX - p.getX();
            double dy = worldY - p.getY();
            double dz = worldZ - p.getZ();
            if (dx * dx + dy * dy + dz * dz <= r * r) {
                return true;
            }
        }
        return false;
    }

    /** 已落地致死陷阱坐标只读列表 (调试/可视化)。 */
    public List<BlockPos> lethalPositions() {
        return lethalPositions;
    }

    /** 静态陷阱总数。 */
    public int trapCount() {
        return trapByVoxel.size();
    }

    /** 全部陷阱条目转 (世界坐标 -> 类型) 列表 (持久化/落方块遍历)。 */
    public List<TrapEntry> entries() {
        List<TrapEntry> out = new ArrayList<>(trapByVoxel.size());
        int sizeX = regionBox.sizeX();
        int sizeZ = regionBox.sizeZ();
        for (Map.Entry<Integer, TrapType> e : trapByVoxel.entrySet()) {
            int idx = e.getKey();
            int localX = idx % sizeX;
            int rest = idx / sizeX;
            int localZ = rest % sizeZ;
            int localY = rest / sizeZ;
            out.add(new TrapEntry(regionBox.localToWorldPos(localX, localY, localZ), e.getValue()));
        }
        return out;
    }

    /** (世界坐标, 陷阱类型) 元组。 */
    public record TrapEntry(BlockPos pos, TrapType type) {
    }
}
