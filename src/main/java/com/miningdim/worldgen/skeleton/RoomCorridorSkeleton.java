package com.miningdim.worldgen.skeleton;

import com.miningdim.worldgen.CarveBounds;
import com.miningdim.worldgen.GenContext;
import com.miningdim.worldgen.VoxelGrid;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;

import java.util.ArrayList;
import java.util.List;

/**
 * Hard 区骨架 (设计文档 7.4.1): 地牢式房间 + 走廊, 房间密集、走廊网格化、规整迷宫感。
 *
 * 连通性内建机制 (7.4.2 Room+Corridor 行): 房间作图节点, 先用最小生成树 (Prim) 连成树 (保证连通),
 * 再按 extraEdges 比例加环边防止纯树状; 每条边用 L 形走廊在网格上挖通。出生锚点取第 0 个房间中心。
 *
 * 确定性 (7.6.2): 单一 RandomSource 串行驱动房间布点与加边抽样。所有挖空坐标 clamp 进可雕刻盒 (6.5)。
 */
public final class RoomCorridorSkeleton implements SkeletonGenerator {

    // 7.4.1 表 (PENDING 初值): rooms=18, roomSize=4..8, extraEdges=0.25
    private static final int ROOMS = 18;
    private static final int ROOM_MIN = 4;
    private static final int ROOM_MAX = 8;
    private static final double EXTRA_EDGE_RATIO = 0.25;
    private static final int CORRIDOR_RADIUS = 1;

    @Override
    public SkeletonResult generate(VoxelGrid grid, GenContext ctx) {
        RandomSource rng = new WorldgenRandom(new LegacyRandomSource(ctx.stageSeed(GenContext.STAGE_SKELETON)));

        int minX = CarveBounds.minLocalX();
        int maxX = CarveBounds.maxLocalX();
        int minZ = CarveBounds.minLocalZ();
        int maxZ = CarveBounds.maxLocalZ();
        int minY = CarveBounds.minLocalY();
        int maxY = CarveBounds.maxLocalY();

        // ---- 1. 房间布点 (随机中心 + 随机半尺寸), 房间须整体落在可雕刻盒内 ----
        List<int[]> centers = new ArrayList<>(ROOMS);
        List<int[]> halfSizes = new ArrayList<>(ROOMS);
        List<SkeletonNode> nodes = new ArrayList<>(ROOMS);
        for (int i = 0; i < ROOMS; i++) {
            int hx = (ROOM_MIN + rng.nextInt(ROOM_MAX - ROOM_MIN + 1)) / 2;
            int hy = (ROOM_MIN + rng.nextInt(ROOM_MAX - ROOM_MIN + 1)) / 2;
            int hz = (ROOM_MIN + rng.nextInt(ROOM_MAX - ROOM_MIN + 1)) / 2;
            int cx = randIn(rng, minX + hx, maxX - hx);
            int cy = randIn(rng, minY + hy, maxY - hy);
            int cz = randIn(rng, minZ + hz, maxZ - hz);
            centers.add(new int[]{cx, cy, cz});
            halfSizes.add(new int[]{hx, hy, hz});
            nodes.add(new SkeletonNode(cx, cy, cz));
        }

        // ---- 2. 挖房间 (实心盒挖空成矩形腔) ----
        for (int i = 0; i < ROOMS; i++) {
            carveBox(grid, centers.get(i), halfSizes.get(i), minX, maxX, minY, maxY, minZ, maxZ);
        }

        // ---- 3. Prim MST 连成树 (保证全连通, 7.4.2) ----
        boolean[] inTree = new boolean[ROOMS];
        inTree[0] = true;
        // 记录已成的边 (i,j) 防加环边时重复。
        boolean[][] edge = new boolean[ROOMS][ROOMS];
        for (int added = 1; added < ROOMS; added++) {
            int bestA = -1;
            int bestB = -1;
            long bestDist = Long.MAX_VALUE;
            for (int a = 0; a < ROOMS; a++) {
                if (!inTree[a]) {
                    continue;
                }
                for (int b = 0; b < ROOMS; b++) {
                    if (inTree[b]) {
                        continue;
                    }
                    long dist = sqDist(centers.get(a), centers.get(b));
                    if (dist < bestDist) {
                        bestDist = dist;
                        bestA = a;
                        bestB = b;
                    }
                }
            }
            inTree[bestB] = true;
            edge[bestA][bestB] = true;
            edge[bestB][bestA] = true;
            carveCorridor(grid, centers.get(bestA), centers.get(bestB), rng,
                    minX, maxX, minY, maxY, minZ, maxZ);
        }

        // ---- 4. extraEdges: 按比例随机加环边 (防纯树状, 7.4.2) ----
        int extraEdges = (int) Math.round(ROOMS * EXTRA_EDGE_RATIO);
        for (int e = 0; e < extraEdges; e++) {
            int a = rng.nextInt(ROOMS);
            int b = rng.nextInt(ROOMS);
            if (a == b || edge[a][b]) {
                continue;
            }
            edge[a][b] = true;
            edge[b][a] = true;
            carveCorridor(grid, centers.get(a), centers.get(b), rng,
                    minX, maxX, minY, maxY, minZ, maxZ);
        }

        int[] anchor = centers.get(0);
        return new SkeletonResult(nodes, grid.index(anchor[0], anchor[1], anchor[2]));
    }

    /** 挖一个以 center 为中心、half 为半尺寸的矩形房间, clamp 进盒。 */
    private static void carveBox(VoxelGrid grid, int[] center, int[] half,
                                 int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        int x0 = Mth.clamp(center[0] - half[0], minX, maxX);
        int x1 = Mth.clamp(center[0] + half[0], minX, maxX);
        int y0 = Mth.clamp(center[1] - half[1], minY, maxY);
        int y1 = Mth.clamp(center[1] + half[1], minY, maxY);
        int z0 = Mth.clamp(center[2] - half[2], minZ, maxZ);
        int z1 = Mth.clamp(center[2] + half[2], minZ, maxZ);
        for (int y = y0; y <= y1; y++) {
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) {
                    grid.setAir(x, y, z);
                }
            }
        }
    }

    /**
     * L 形走廊: 沿 X -> Z -> Y 三段轴对齐管道连接两房间中心, 每段挖 CORRIDOR_RADIUS 半径的管。
     * 轴向随机化 (先 X 还是先 Z) 由 rng 决定, 增加形态多样性, 仍保持确定性。
     */
    private static void carveCorridor(VoxelGrid grid, int[] a, int[] b, RandomSource rng,
                                      int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        int[] cur = a.clone();
        boolean xFirst = rng.nextBoolean();
        if (xFirst) {
            walkAxis(grid, cur, 0, b[0], minX, maxX, minY, maxY, minZ, maxZ);
            walkAxis(grid, cur, 2, b[2], minX, maxX, minY, maxY, minZ, maxZ);
        } else {
            walkAxis(grid, cur, 2, b[2], minX, maxX, minY, maxY, minZ, maxZ);
            walkAxis(grid, cur, 0, b[0], minX, maxX, minY, maxY, minZ, maxZ);
        }
        walkAxis(grid, cur, 1, b[1], minX, maxX, minY, maxY, minZ, maxZ);
    }

    /** 沿指定轴 (0=x,1=y,2=z) 从 cur[axis] 直挖到 target, 逐步挖 CORRIDOR_RADIUS 球腔。 */
    private static void walkAxis(VoxelGrid grid, int[] cur, int axis, int target,
                                 int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        int step = Integer.compare(target, cur[axis]);
        while (cur[axis] != target) {
            cur[axis] += step;
            RandomWalkSkeleton.carveSphere(grid, cur[0], cur[1], cur[2], CORRIDOR_RADIUS,
                    minX, maxX, minY, maxY, minZ, maxZ);
        }
    }

    private static long sqDist(int[] a, int[] b) {
        long dx = a[0] - b[0];
        long dy = a[1] - b[1];
        long dz = a[2] - b[2];
        return dx * dx + dy * dy + dz * dz;
    }

    private static int randIn(RandomSource rng, int lo, int hi) {
        if (hi <= lo) {
            return lo;
        }
        return lo + rng.nextInt(hi - lo + 1);
    }
}
