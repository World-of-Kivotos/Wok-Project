package com.miningdim.trap;

import com.miningdim.core.Difficulty;
import com.miningdim.core.RegionBox;
import com.miningdim.core.SeedUtil;
import com.miningdim.core.VoxelOccupancy;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 离线静态陷阱布点核心 (设计文档 9.3 - 9.5)。纯计算, 工作线程运行 (TR-4/TR-5: 离线确定布点, 落方块由
 * MiningChunkGenerator 主线程读表; 本类不碰 ServerLevel)。在 ConnectivityFix 之后、矿物铺设同阶段执行,
 * 由 GenerationScheduler 调用, 产出不可变 {@link StaticTrapPlacement}。
 *
 * 不变量落地:
 *  TR-1 每陷阱有可感知线索 + reactionWindow: 线索/窗口随陷阱方块外观与触发逻辑落地 (TrapType + 运行期触发),
 *       布点阶段只决定"哪个体素是哪类陷阱"。
 *  TR-2 出生半径 SPAWN_SAFE_R 内、主干道关键节点禁布致死陷阱: 9.5 过滤步骤 1/2。
 *  TR-3 trapChance = difficultyFactor * localRisk, 受 TRAP_CHANCE_MAX 与每区致死密度上限封顶 (9.3)。
 *  TR-4 静态布点离线确定 (D2), 同 seed 逐方块可复现 (D3): 仅 SeedUtil.hash 派生。
 *
 * localRisk (9.5): 0.5*oreRichnessNorm + 0.3*deadEndNorm + 0.2*depthNorm。
 *   - oreRichnessNorm: 本子系统不 import 矿物实现 (铁律 2), 用"局部可挖壁面密度"作富矿代理 —— 壁面越密的腔体
 *     越可能富矿且玩家停留更久, 与 risk-reward 同向。这是无跨子系统耦合下对 oreRichness 的确定性几何代理。
 *   - deadEndNorm: 候选空气体素的空气邻居越少 (越像死路尽头) 越危险。
 *   - depthNorm: 候选体素 worldY 越低越深越危险, 按 region 高度归一。
 */
public final class StaticTrapGenerator {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/StaticTrapGenerator");

    private static final int FEATURE_TRAP_CHANCE = 0x54_52_50_01; // "TRP" 单格触发概率掷点
    private static final int FEATURE_TRAP_TYPE = 0x54_52_50_02;   // 陷阱类型抽取

    /** 16x16x16 子区边长 (9.3 致死密度上限以子区为单位)。 */
    private static final int SUBZONE = 16;

    private StaticTrapGenerator() {
    }

    /**
     * 生成静态陷阱布点表 (9.5)。陷阱锚定在"贴空气可挖壁面"的实心体素 (玩家挖到才触发, 与 OG-2 同口径),
     * 触发线索方块由该体素本身承载 (引信块/假矿/承重砂砾)。
     *
     * @param seed         实例确定性种子
     * @param difficulty   难度档 (决定 difficultyFactor 与子区致死密度上限)
     * @param box          region 几何
     * @param voxels       离线体素视图 (air=可通行)
     * @param spawnAnchor  出生锚点世界坐标 (主连通分量锚点, 9.5 步骤1 距出生点过滤基准; 由 ConnectivityFix 确定)
     * @return 不可变静态陷阱布点表
     */
    public static StaticTrapPlacement generate(long seed, Difficulty difficulty, RegionBox box,
                                               VoxelOccupancy voxels, BlockPos spawnAnchor) {
        double difficultyFactor = TrapParams.difficultyFactor(difficulty);
        // Easy 难度 difficultyFactor=0: 全程不生成静态陷阱 (9.3/9.4 末)。返回空表, 不浪费扫描。
        if (difficultyFactor <= 0.0) {
            return new StaticTrapPlacement(box, Map.of(), List.of());
        }

        double chanceMax = TrapParams.trapChanceMax(difficulty);
        int lethalPerSubzoneCap = TrapParams.lethalPerSubzoneCap(difficulty);
        int minSpacing = TrapParams.minLethalSpacing(difficulty);

        Map<Integer, TrapType> trapByVoxel = new HashMap<>();
        List<BlockPos> lethalPositions = new ArrayList<>();
        // 子区致死计数: key = 子区线性编号, value = 已布致死陷阱数 (9.3 每子区上限)。
        Map<Long, Integer> lethalPerSubzone = new HashMap<>();

        int w = box.sizeX();
        int h = box.sizeY();
        int d = box.sizeZ();

        for (int y = 0; y < h; y++) {
            for (int z = 0; z < d; z++) {
                for (int x = 0; x < w; x++) {
                    if (voxels.isAir(x, y, z)) {
                        continue; // 陷阱锚在实心壁
                    }
                    if (!hasAirNeighbor(voxels, w, h, d, x, y, z)) {
                        continue; // 9.5 步骤5: 须 ∈ 可达壁面 (贴空气面)
                    }
                    int worldX = box.localToWorldX(x);
                    int worldY = box.localToWorldY(y);
                    int worldZ = box.localToWorldZ(z);

                    double localRisk = localRisk(voxels, box, w, h, d, x, y, z, worldY);
                    double trapChance = clamp(difficultyFactor * localRisk, 0.0, chanceMax);

                    // 9.5 布点确定性: deriveFloat < trapChance 则为陷阱候选。
                    float roll = deriveFloat(seed, FEATURE_TRAP_CHANCE, worldX, worldY, worldZ);
                    if (roll >= trapChance) {
                        continue;
                    }

                    TrapType type = pickTrapType(seed, worldX, worldY, worldZ);

                    // 9.5 过滤:
                    if (type.lethal()) {
                        // 步骤1: 距出生点 <= SPAWN_SAFE_R 剔除致死。
                        if (withinSpawnSafe(spawnAnchor, worldX, worldY, worldZ)) {
                            continue;
                        }
                        // 步骤3: 子区致死上限。
                        long subzoneKey = subzoneKey(x, y, z);
                        if (lethalPerSubzone.getOrDefault(subzoneKey, 0) >= lethalPerSubzoneCap) {
                            continue;
                        }
                        // 步骤4: 与已布致死陷阱最小间距。
                        if (tooCloseToLethal(lethalPositions, worldX, worldY, worldZ, minSpacing)) {
                            continue;
                        }
                        trapByVoxel.put(box.voxelIndex(x, y, z), type);
                        lethalPositions.add(new BlockPos(worldX, worldY, worldZ));
                        lethalPerSubzone.merge(subzoneKey, 1, Integer::sum);
                    } else {
                        // 非致死 (崩塌/假矿): 仅受概率约束, 不占致死子区配额、不参与致死间距。
                        // 步骤2 关键节点禁布只约束致死类, 非致死可保留 (9.5 步骤2 末"保留非致死提示陷阱可选")。
                        trapByVoxel.put(box.voxelIndex(x, y, z), type);
                    }
                }
            }
        }

        LOGGER.info("[miningdim] static traps placed: total={} lethal={} (difficulty={})",
                trapByVoxel.size(), lethalPositions.size(), difficulty.configName());
        return new StaticTrapPlacement(box, trapByVoxel, lethalPositions);
    }

    // ---- localRisk (9.5) ----

    private static double localRisk(VoxelOccupancy voxels, RegionBox box, int w, int h, int d,
                                    int x, int y, int z, int worldY) {
        double oreRichnessNorm = wallDensityNorm(voxels, w, h, d, x, y, z);
        double deadEndNorm = deadEndNorm(voxels, w, h, d, x, y, z);
        double depthNorm = depthNorm(box, worldY);
        double risk = 0.5 * oreRichnessNorm + 0.3 * deadEndNorm + 0.2 * depthNorm;
        return clamp(risk, 0.0, 1.0);
    }

    /**
     * 富矿代理 = 3x3x3 邻域内"贴空气壁面"体素占比 (归一 [0,1])。壁面越密表示腔体表面积大、可挖面多,
     * 与矿物铺设候选池 (8.6 placeable) 正相关, 故作 oreRichnessNorm 的确定性几何代理 (不 import 矿物实现)。
     */
    private static double wallDensityNorm(VoxelOccupancy v, int w, int h, int d, int x, int y, int z) {
        int wall = 0;
        int total = 0;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int nx = x + dx;
                    int ny = y + dy;
                    int nz = z + dz;
                    if (nx < 0 || ny < 0 || nz < 0 || nx >= w || ny >= h || nz >= d) {
                        continue;
                    }
                    total++;
                    if (!v.isAir(nx, ny, nz) && hasAirNeighbor(v, w, h, d, nx, ny, nz)) {
                        wall++;
                    }
                }
            }
        }
        return total == 0 ? 0.0 : (double) wall / total;
    }

    /**
     * 死路代理: 检查与该壁面相邻的空气体素中, 最"封闭"者的开放度。空气邻居的空气邻居越少越像死路尽头。
     * 取相邻空气体素里空气邻居数最小者, 映射: 邻居数 0->1.0(全封闭), 5+->0.0(通透)。
     */
    private static double deadEndNorm(VoxelOccupancy v, int w, int h, int d, int x, int y, int z) {
        int minOpen = 6;
        boolean found = false;
        for (int[] dir : NEIGHBOR_DIRS) {
            int ax = x + dir[0];
            int ay = y + dir[1];
            int az = z + dir[2];
            if (ax < 0 || ay < 0 || az < 0 || ax >= w || ay >= h || az >= d) {
                continue;
            }
            if (!v.isAir(ax, ay, az)) {
                continue;
            }
            found = true;
            int open = countAirNeighbors(v, w, h, d, ax, ay, az);
            minOpen = Math.min(minOpen, open);
        }
        if (!found) {
            return 0.0;
        }
        // 6 个方向: 1 个开口 (死路尽头) -> 高危; >=5 开口 (开阔) -> 低危。
        return clamp((6 - minOpen) / 5.0, 0.0, 1.0);
    }

    /** 深度代理: worldY 越接近 region 底越危险, 线性归一到 [0,1]。 */
    private static double depthNorm(RegionBox box, int worldY) {
        int minY = box.originY();
        int maxY = box.originY() + box.sizeY() - 1;
        if (maxY <= minY) {
            return 0.0;
        }
        double t = (double) (maxY - worldY) / (maxY - minY);
        return clamp(t, 0.0, 1.0);
    }

    // ---- 9.5 过滤辅助 ----

    private static boolean withinSpawnSafe(BlockPos anchor, int wx, int wy, int wz) {
        double dx = wx - anchor.getX();
        double dy = wy - anchor.getY();
        double dz = wz - anchor.getZ();
        double r = TrapParams.SPAWN_SAFE_R;
        return dx * dx + dy * dy + dz * dz <= r * r;
    }

    private static boolean tooCloseToLethal(List<BlockPos> lethal, int wx, int wy, int wz, int minSpacing) {
        long minSq = (long) minSpacing * minSpacing;
        for (BlockPos p : lethal) {
            long dx = wx - p.getX();
            long dy = wy - p.getY();
            long dz = wz - p.getZ();
            if (dx * dx + dy * dy + dz * dz < minSq) {
                return true;
            }
        }
        return false;
    }

    private static long subzoneKey(int localX, int localY, int localZ) {
        long sx = localX / SUBZONE;
        long sy = localY / SUBZONE;
        long sz = localZ / SUBZONE;
        // 子区线性编号: 用足够宽的乘子避免碰撞 (region 单边 <= 384 -> 子区 <= 24, 乘子 1024 足够)。
        return (sy * 1024L + sz) * 1024L + sx;
    }

    /** 致死类与非致死类按 9.4 假矿密度为致死一半的意图分配抽取概率。 */
    private static TrapType pickTrapType(long seed, int wx, int wy, int wz) {
        float pick = deriveFloat(seed, FEATURE_TRAP_TYPE, wx, wy, wz);
        // 致死: TNT_VEIN / LAVA_POCKET; 非致死: COLLAPSING_TUNNEL / FAKE_ORE。
        // 概率分配 (9.4 假矿密度为致死一半的意图): TNT 0.30, 岩浆 0.30, 崩塌 0.25, 假矿 0.15。
        if (pick < 0.30f) {
            return TrapType.TNT_VEIN;
        } else if (pick < 0.60f) {
            return TrapType.LAVA_POCKET;
        } else if (pick < 0.85f) {
            return TrapType.COLLAPSING_TUNNEL;
        } else {
            return TrapType.FAKE_ORE;
        }
    }

    // ---- 体素邻接工具 ----

    private static final int[][] NEIGHBOR_DIRS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    private static boolean hasAirNeighbor(VoxelOccupancy v, int w, int h, int d, int x, int y, int z) {
        return (x + 1 < w && v.isAir(x + 1, y, z))
                || (x - 1 >= 0 && v.isAir(x - 1, y, z))
                || (y + 1 < h && v.isAir(x, y + 1, z))
                || (y - 1 >= 0 && v.isAir(x, y - 1, z))
                || (z + 1 < d && v.isAir(x, y, z + 1))
                || (z - 1 >= 0 && v.isAir(x, y, z - 1));
    }

    private static int countAirNeighbors(VoxelOccupancy v, int w, int h, int d, int x, int y, int z) {
        int c = 0;
        for (int[] dir : NEIGHBOR_DIRS) {
            int nx = x + dir[0];
            int ny = y + dir[1];
            int nz = z + dir[2];
            if (nx < 0 || ny < 0 || nz < 0 || nx >= w || ny >= h || nz >= d) {
                continue;
            }
            if (v.isAir(nx, ny, nz)) {
                c++;
            }
        }
        return c;
    }

    // ---- 确定性派生 ----

    /** 由 (seed, x, z, feature) 派生 [0,1) 浮点 (D3); 把 y 折进 z 维以区分同 XZ 不同高度的格。 */
    private static float deriveFloat(long seed, int featureId, int x, int y, int z) {
        long h = SeedUtil.hash(seed, x, z * 31 + y, featureId);
        return (float) ((h >>> 11) * 0x1.0p-53);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
