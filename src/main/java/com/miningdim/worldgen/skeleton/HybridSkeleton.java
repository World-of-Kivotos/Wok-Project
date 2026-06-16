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
 * Medium 区骨架 (设计文档 7.4.1): 随机游走主干 + 稀疏房间挂载, 主干自然 + 若干房间节点, 中等复杂度。
 *
 * 连通性内建机制 (7.4.2 Hybrid 行): 先生成主干 walk (连通), 再把每个房间用一条 L 形走廊接到
 * "已连通节点里最近的一个", 挂载即连通。出生锚点取主干起点。
 *
 * 确定性 (7.6.2): 单一 RandomSource 串行驱动主干步进、房间布点、挂载选点。挖空坐标 clamp 进盒 (6.5)。
 */
public final class HybridSkeleton implements SkeletonGenerator {

    // 7.4.1 表 (PENDING 初值): walkers=4, rooms=8, roomSize=5..9, corridorRadius=1.5
    private static final int WALKERS = 4;
    private static final int ROOMS = 8;
    private static final int ROOM_MIN = 5;
    private static final int ROOM_MAX = 9;
    private static final int TRUNK_RADIUS = 2;
    private static final int CORRIDOR_RADIUS = 1;
    private static final double STEPS_PER_WALKER_FACTOR = 1.2;

    @Override
    public SkeletonResult generate(VoxelGrid grid, GenContext ctx) {
        RandomSource rng = new WorldgenRandom(new LegacyRandomSource(ctx.stageSeed(GenContext.STAGE_SKELETON)));

        int minX = CarveBounds.minLocalX();
        int maxX = CarveBounds.maxLocalX();
        int minZ = CarveBounds.minLocalZ();
        int maxZ = CarveBounds.maxLocalZ();
        int minY = CarveBounds.minLocalY();
        int maxY = CarveBounds.maxLocalY();

        List<SkeletonNode> nodes = new ArrayList<>();
        // 已连通节点 (主干点 + 已挂载房间中心), 房间挂载时从中取最近点。
        List<int[]> connected = new ArrayList<>();

        int[] start = {clamp((minX + maxX) / 2, minX, maxX),
                clamp((minY + maxY) / 2, minY, maxY),
                clamp((minZ + maxZ) / 2, minZ, maxZ)};
        int spawnAnchorIdx = grid.index(start[0], start[1], start[2]);

        // ---- 1. 主干: 多 walker 串联随机游走 (连通) ----
        int steps = (int) (grid.width() * STEPS_PER_WALKER_FACTOR);
        for (int w = 0; w < WALKERS; w++) {
            int[] cur = (w == 0) ? start.clone() : connected.get(rng.nextInt(connected.size())).clone();
            nodes.add(new SkeletonNode(cur[0], cur[1], cur[2]));
            for (int s = 0; s < steps; s++) {
                RandomWalkSkeleton.carveSphere(grid, cur[0], cur[1], cur[2], TRUNK_RADIUS,
                        minX, maxX, minY, maxY, minZ, maxZ);
                connected.add(new int[]{cur[0], cur[1], cur[2]});
                int dir = rng.nextInt(6);
                switch (dir) {
                    case 0 -> cur[0] = clamp(cur[0] + 1, minX, maxX);
                    case 1 -> cur[0] = clamp(cur[0] - 1, minX, maxX);
                    case 2 -> cur[2] = clamp(cur[2] + 1, minZ, maxZ);
                    case 3 -> cur[2] = clamp(cur[2] - 1, minZ, maxZ);
                    case 4 -> cur[1] = clamp(cur[1] + 1, minY, maxY);
                    default -> cur[1] = clamp(cur[1] - 1, minY, maxY);
                }
            }
        }

        // ---- 2. 稀疏房间, 逐个挂载到最近的已连通点 (挂载即连通) ----
        for (int i = 0; i < ROOMS; i++) {
            int hx = (ROOM_MIN + rng.nextInt(ROOM_MAX - ROOM_MIN + 1)) / 2;
            int hy = (ROOM_MIN + rng.nextInt(ROOM_MAX - ROOM_MIN + 1)) / 2;
            int hz = (ROOM_MIN + rng.nextInt(ROOM_MAX - ROOM_MIN + 1)) / 2;
            int cx = randIn(rng, minX + hx, maxX - hx);
            int cy = randIn(rng, minY + hy, maxY - hy);
            int cz = randIn(rng, minZ + hz, maxZ - hz);
            int[] center = {cx, cy, cz};

            // 挖房间矩形腔。
            for (int y = Mth.clamp(cy - hy, minY, maxY); y <= Mth.clamp(cy + hy, minY, maxY); y++) {
                for (int z = Mth.clamp(cz - hz, minZ, maxZ); z <= Mth.clamp(cz + hz, minZ, maxZ); z++) {
                    for (int x = Mth.clamp(cx - hx, minX, maxX); x <= Mth.clamp(cx + hx, minX, maxX); x++) {
                        grid.setAir(x, y, z);
                    }
                }
            }

            // 找最近已连通点并 L 形走廊挂载。
            int[] nearest = nearest(connected, center);
            carryCorridor(grid, center, nearest, minX, maxX, minY, maxY, minZ, maxZ);

            connected.add(center);
            nodes.add(new SkeletonNode(cx, cy, cz));
        }

        return new SkeletonResult(nodes, spawnAnchorIdx);
    }

    /** L 形走廊连接 from -> to (X 段, Z 段, Y 段), 半径 CORRIDOR_RADIUS。 */
    private static void carryCorridor(VoxelGrid grid, int[] from, int[] to,
                                      int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        int[] cur = from.clone();
        walkAxis(grid, cur, 0, to[0], minX, maxX, minY, maxY, minZ, maxZ);
        walkAxis(grid, cur, 2, to[2], minX, maxX, minY, maxY, minZ, maxZ);
        walkAxis(grid, cur, 1, to[1], minX, maxX, minY, maxY, minZ, maxZ);
    }

    private static void walkAxis(VoxelGrid grid, int[] cur, int axis, int target,
                                 int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        int step = Integer.compare(target, cur[axis]);
        while (cur[axis] != target) {
            cur[axis] += step;
            RandomWalkSkeleton.carveSphere(grid, cur[0], cur[1], cur[2], CORRIDOR_RADIUS,
                    minX, maxX, minY, maxY, minZ, maxZ);
        }
    }

    private static int[] nearest(List<int[]> pool, int[] target) {
        int[] best = pool.get(0);
        long bestDist = sqDist(best, target);
        for (int i = 1; i < pool.size(); i++) {
            long dist = sqDist(pool.get(i), target);
            if (dist < bestDist) {
                bestDist = dist;
                best = pool.get(i);
            }
        }
        return best;
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

    private static int clamp(int v, int lo, int hi) {
        return Mth.clamp(v, lo, hi);
    }
}
