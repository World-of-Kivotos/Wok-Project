package com.miningdim.ore;

import com.miningdim.core.Difficulty;
import com.miningdim.core.MiningServices;
import com.miningdim.core.RegionBox;
import com.miningdim.core.SeedUtil;
import com.miningdim.core.VoxelOccupancy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 离线铺矿核心 (设计文档第八章)。纯计算, 在工作线程运行 (8.8 线程: 不碰 ServerLevel),
 * 在 OfflineGenerator 产出体素 (Skeleton/NoiseCarving/ConnectivityFix 之后) 时由 GenerationScheduler 调用,
 * 产出不可变 {@link OrePlacement} 供 MiningChunkGenerator 在区块填充阶段查表落矿 (8.5)。
 *
 * 不变量落地:
 *  OG-1 每矿种 maxCount 硬封顶 (8.4): 配额满即从轮盘剔除。
 *  OG-2 只铺贴空气可挖面的实心壁体素 (8.6 placeable 定义)。
 *  OG-3 依赖第七章 ConnectivityFix: VoxelOccupancy 的 air 即主连通分量可达空气 (D4 契约),
 *       故"实心体素的 6-邻接存在 air"等价于"可达壁面", 无需额外主分量掩码。
 *  OG-4 同 seed 逐方块一致: 全程仅用 SeedUtil.hash(seed, x, z, FEATURE_*) 派生, 无共享可变 Random (D3)。
 *  OG-5 难度只改权重 (OreType.effectiveWeight) 与配额 (OreType.densityPerK/maxCount), 不改算法。
 *
 * 失败处理 (8.8): 壁面不足导致配额铺不满 -> 按实铺量收尾并记 WARN, 不抛异常、不静默吞数据缺口。
 */
public final class OreGenerator {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/OreGenerator");

    // SeedUtil.hash 的 featureId 命名空间: 不同用途用不同 id, 避免撞同坐标随机流 (SeedUtil 注释)。
    private static final int FEATURE_QUOTA = 0x4F_52_45_01;   // "ORE" 配额抖动 jitter
    private static final int FEATURE_ANCHOR = 0x4F_52_45_02;  // 矿脉锚点选取
    private static final int FEATURE_ROULETTE = 0x4F_52_45_03; // 加权轮盘抽矿种
    private static final int FEATURE_VEINSIZE = 0x4F_52_45_04; // 矿脉尺寸

    private OreGenerator() {
    }

    /**
     * 生成铺矿表 (8.8 入口)。
     *
     * @param seed       实例确定性种子 (InstanceState.seed)
     * @param difficulty 难度档
     * @param regionBox  实例 region 几何
     * @param voxels     离线体素视图 (air=可通行; 主分量已由 ConnectivityFix 保证连通)
     * @return 不可变铺矿表
     */
    public static OrePlacement generate(long seed, Difficulty difficulty, RegionBox regionBox, VoxelOccupancy voxels) {
        // 1) 单遍扫描可铺壁面体素 (8.6): 实心且 6-邻接存在 air 的体素。
        List<Integer> placeable = scanPlaceableWalls(regionBox, voxels);
        int wallBudget = placeable.size();

        // 全局矿物密度缩放 (16.2.3 ore.globalDensity): 配置门面提供, 业务层不读裸常量 (C6)。
        double globalDensity = MiningServices.config().oreGlobalDensity();

        // 2) 各矿种目标产量 targetCount (8.4 配额公式 + jitter, OG-1 受 maxCount 封顶)。
        Map<OreType, Integer> targetCount = computeQuota(seed, difficulty, wallBudget, globalDensity);

        // 3) 矿脉成簇铺设 (8.5): 在 placeable 上加权轮盘抽矿种, 锚点 BFS 落簇。
        Map<Integer, OreType> oreByVoxel = new HashMap<>();
        Map<OreType, Integer> placed = new EnumMap<>(OreType.class);
        placeVeins(seed, difficulty, regionBox, voxels, placeable, targetCount, oreByVoxel, placed);

        // 4) 失败处理 (8.8): 任一矿种实铺 < 目标即记 WARN (壁面耗尽), 不抛异常。
        reportShortfall(targetCount, placed, wallBudget);

        return new OrePlacement(regionBox, oreByVoxel, placed);
    }

    // ---- 8.6 可铺壁面统计 ----

    /**
     * 扫描主连通分量可铺壁面体素 (8.6): 对每个实心 (非 air) 体素, 若其 6-邻接中存在 air 体素, 则标记为 placeable。
     * 越界邻居视为墙 (跳过), 与 7.5.1 边界即墙一致。返回的下标即 RegionBox.voxelIndex (落簇候选池)。
     */
    private static List<Integer> scanPlaceableWalls(RegionBox box, VoxelOccupancy voxels) {
        int w = box.sizeX();
        int h = box.sizeY();
        int d = box.sizeZ();
        List<Integer> placeable = new ArrayList<>();
        for (int y = 0; y < h; y++) {
            for (int z = 0; z < d; z++) {
                for (int x = 0; x < w; x++) {
                    if (voxels.isAir(x, y, z)) {
                        continue; // 只在实心壁铺矿 (OG-2)
                    }
                    if (hasAirNeighbor(voxels, w, h, d, x, y, z)) {
                        placeable.add(box.voxelIndex(x, y, z));
                    }
                }
            }
        }
        return placeable;
    }

    /** 6-邻接是否存在 air 体素 (越界邻居视为墙)。 */
    private static boolean hasAirNeighbor(VoxelOccupancy v, int w, int h, int d, int x, int y, int z) {
        return (x + 1 < w && v.isAir(x + 1, y, z))
                || (x - 1 >= 0 && v.isAir(x - 1, y, z))
                || (y + 1 < h && v.isAir(x, y + 1, z))
                || (y - 1 >= 0 && v.isAir(x, y - 1, z))
                || (z + 1 < d && v.isAir(x, y, z + 1))
                || (z - 1 >= 0 && v.isAir(x, y, z - 1));
    }

    // ---- 8.4 配额 ----

    /**
     * 各矿种目标产量 (8.4):
     *   rawTarget   = densityPerK * wallBudget / 1000 * globalDensity
     *   jitter      = 0.90 + 0.20 * deriveFloat(seed,"quota",ordinal)  -> [0.90, 1.10)
     *   targetCount = clamp(round(rawTarget * jitter), 0, maxCount)
     */
    private static Map<OreType, Integer> computeQuota(long seed, Difficulty difficulty,
                                                      int wallBudget, double globalDensity) {
        Map<OreType, Integer> target = new EnumMap<>(OreType.class);
        for (OreType ore : OreType.values()) {
            double rawTarget = ore.densityPerK(difficulty) * (double) wallBudget / 1000.0 * globalDensity;
            float jitter = 0.90f + 0.20f * deriveFloat(seed, FEATURE_QUOTA, ore.ordinal());
            int count = Math.toIntExact(Math.round(rawTarget * jitter));
            count = clamp(count, 0, ore.maxCount(difficulty));
            target.put(ore, count);
        }
        return target;
    }

    // ---- 8.5 矿脉成簇铺设 ----

    /**
     * 落簇主循环 (8.5): 顺序遍历 placeable 锚点, 每个未占用锚点抽一个未满配额矿种, 从锚点做有界 BFS
     * (只沿 occupied 且贴空气面的 placeable 体素扩展) 取前 veinSize 个写表。直到所有矿种配额满或壁面耗尽。
     */
    private static void placeVeins(long seed, Difficulty difficulty, RegionBox box, VoxelOccupancy voxels,
                                   List<Integer> placeable, Map<OreType, Integer> targetCount,
                                   Map<Integer, OreType> oreByVoxel, Map<OreType, Integer> placed) {
        int w = box.sizeX();
        int d = box.sizeZ();
        // placeable 下标集合, BFS 扩展只在此集合内 (= 主分量可铺壁面)。
        java.util.Set<Integer> placeableSet = new java.util.HashSet<>(placeable);

        int anchorCounter = 0;
        for (int anchorIdx : placeable) {
            if (oreByVoxel.containsKey(anchorIdx)) {
                continue; // 锚点已被前面的矿脉占用
            }
            anchorCounter++;
            OreType ore = rollOre(seed, difficulty, anchorCounter, targetCount, placed);
            if (ore == null) {
                break; // 所有矿种配额已满, 提前收尾
            }
            int remaining = targetCount.get(ore) - placed.getOrDefault(ore, 0);
            if (remaining <= 0) {
                continue;
            }
            int veinSize = rollVeinSize(seed, ore, anchorCounter, remaining);
            int grown = growVein(box, voxels, placeableSet, oreByVoxel, anchorIdx, ore, veinSize, w, d);
            placed.merge(ore, grown, Integer::sum);
        }
    }

    /**
     * 加权轮盘抽矿种 (8.2): 仅对配额未满矿种构造权重 = effectiveWeight 的轮盘, 用 deriveFloat 选中。
     * 全部满配额返回 null (主循环据此收尾)。
     */
    private static OreType rollOre(long seed, Difficulty difficulty, int counter,
                                   Map<OreType, Integer> targetCount, Map<OreType, Integer> placed) {
        double totalWeight = 0.0;
        for (OreType ore : OreType.values()) {
            if (placed.getOrDefault(ore, 0) < targetCount.get(ore)) {
                totalWeight += ore.effectiveWeight(difficulty);
            }
        }
        if (totalWeight <= 0.0) {
            return null;
        }
        double pick = deriveFloat(seed, FEATURE_ROULETTE, counter) * totalWeight;
        double acc = 0.0;
        for (OreType ore : OreType.values()) {
            if (placed.getOrDefault(ore, 0) >= targetCount.get(ore)) {
                continue;
            }
            acc += ore.effectiveWeight(difficulty);
            if (pick < acc) {
                return ore;
            }
        }
        // 浮点累加边界兜底: 返回最后一个未满矿种 (不会 null, 因 totalWeight>0)。
        for (int i = OreType.values().length - 1; i >= 0; i--) {
            OreType ore = OreType.values()[i];
            if (placed.getOrDefault(ore, 0) < targetCount.get(ore)) {
                return ore;
            }
        }
        return null;
    }

    /** 矿脉尺寸 (8.5 步骤3): veinSizeMin + hash%跨度, 但不超过该矿种剩余配额。 */
    private static int rollVeinSize(long seed, OreType ore, int counter, int remaining) {
        int span = ore.veinSizeMax() - ore.veinSizeMin() + 1;
        long h = SeedUtil.hash(seed, counter, ore.ordinal(), FEATURE_VEINSIZE);
        int size = ore.veinSizeMin() + (int) Math.floorMod(h, span);
        return Math.min(size, remaining);
    }

    /**
     * 从锚点有界 BFS 落簇 (8.5 步骤4): 只沿 placeable 集合内、尚未被任何矿脉占用的体素扩展,
     * 取前 veinSize 个写入铺矿表。返回实际写入数 (壁面局部不足时 < veinSize)。
     */
    private static int growVein(RegionBox box, VoxelOccupancy voxels, java.util.Set<Integer> placeableSet,
                                Map<Integer, OreType> oreByVoxel, int anchorIdx, OreType ore,
                                int veinSize, int w, int d) {
        if (veinSize <= 0) {
            return 0;
        }
        Deque<Integer> queue = new ArrayDeque<>();
        queue.add(anchorIdx);
        int written = 0;
        // 用一个本地 visited 防止同一簇内重复入队 (已写 oreByVoxel 的不再扩展)。
        java.util.Set<Integer> visited = new java.util.HashSet<>();
        visited.add(anchorIdx);
        while (!queue.isEmpty() && written < veinSize) {
            int idx = queue.poll();
            if (oreByVoxel.containsKey(idx)) {
                continue; // 已被别的矿脉占用
            }
            oreByVoxel.put(idx, ore);
            written++;
            // 6-邻接扩展: 只入队仍属 placeable 且未铺矿的体素。
            int localX = idx % w;
            int rest = idx / w;
            int localZ = rest % d;
            int localY = rest / d;
            enqueueNeighbor(box, queue, visited, placeableSet, oreByVoxel, localX + 1, localY, localZ);
            enqueueNeighbor(box, queue, visited, placeableSet, oreByVoxel, localX - 1, localY, localZ);
            enqueueNeighbor(box, queue, visited, placeableSet, oreByVoxel, localX, localY + 1, localZ);
            enqueueNeighbor(box, queue, visited, placeableSet, oreByVoxel, localX, localY - 1, localZ);
            enqueueNeighbor(box, queue, visited, placeableSet, oreByVoxel, localX, localY, localZ + 1);
            enqueueNeighbor(box, queue, visited, placeableSet, oreByVoxel, localX, localY, localZ - 1);
        }
        return written;
    }

    private static void enqueueNeighbor(RegionBox box, Deque<Integer> queue, java.util.Set<Integer> visited,
                                        java.util.Set<Integer> placeableSet, Map<Integer, OreType> oreByVoxel,
                                        int lx, int ly, int lz) {
        if (lx < 0 || ly < 0 || lz < 0 || lx >= box.sizeX() || ly >= box.sizeY() || lz >= box.sizeZ()) {
            return;
        }
        int nIdx = box.voxelIndex(lx, ly, lz);
        if (visited.contains(nIdx) || oreByVoxel.containsKey(nIdx) || !placeableSet.contains(nIdx)) {
            return;
        }
        visited.add(nIdx);
        queue.add(nIdx);
    }

    // ---- 失败处理 ----

    private static void reportShortfall(Map<OreType, Integer> targetCount, Map<OreType, Integer> placed,
                                        int wallBudget) {
        for (OreType ore : OreType.values()) {
            int want = targetCount.get(ore);
            int got = placed.getOrDefault(ore, 0);
            if (want > 0 && got < want) {
                LOGGER.warn("[miningdim] ore shortfall: {} placed {}/{} (wallBudget={}); wall exhausted, finalizing at actual count (8.8)",
                        ore, got, want, wallBudget);
            }
        }
    }

    // ---- 确定性派生工具 (D3) ----

    /**
     * 由 hash 派生 [0,1) 浮点 (8.4 deriveFloat): 同 (seed, featureId, salt) 一致 (D3)。
     * SeedUtil.hash(seed, x, z, featureId) 把 salt 放 x 位、featureId 放 featureId 位, 二者共同决定派生值;
     * z 位补 0 即可, 不同 salt/不同 feature 落不同随机流。取 hash 高 53 位归一到 [0,1)。
     */
    private static float deriveFloat(long seed, int featureId, int salt) {
        long h = SeedUtil.hash(seed, salt, 0, featureId);
        return (float) ((h >>> 11) * 0x1.0p-53);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
