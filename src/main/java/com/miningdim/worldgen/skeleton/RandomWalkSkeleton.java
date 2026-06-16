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
 * Easy 区骨架 (设计文档 7.4.1): 多源随机游走隧道, 形态为自然蜿蜒洞穴, 通道宽松、迷路风险低。
 *
 * 连通性内建机制 (7.4.2 Random Walk 行): 所有 walker 起点串联 —— 第 i 个 walker 的起点取自前序
 * 已挖路径上的一个点, 使全部轨迹并集连通。首个 walker 从子盒中心起步。出生锚点取首 walker 起点。
 *
 * 确定性 (7.6.2): 全程只用一个由 skeletonSeed 构造的 RandomSource 串行推进, 不并行、不共享。
 * 越界处理 (6.5): 游走 Y 用 Mth.clamp 收进 CarveBounds, 绝不写隔层/顶板; XZ 同样 clamp。
 */
public final class RandomWalkSkeleton implements SkeletonGenerator {

    // 7.4.1 表 (PENDING 待平衡初值): walkers=6, stepsPerWalker=W*1.5, tunnelRadius=2, branchProb=0.15
    private static final int WALKERS = 6;
    private static final double STEPS_PER_WALKER_FACTOR = 1.5;
    private static final int TUNNEL_RADIUS = 2;
    private static final double BRANCH_PROB = 0.15;

    @Override
    public SkeletonResult generate(VoxelGrid grid, GenContext ctx) {
        RandomSource rng = new WorldgenRandom(new LegacyRandomSource(ctx.stageSeed(GenContext.STAGE_SKELETON)));

        int minX = CarveBounds.minLocalX();
        int maxX = CarveBounds.maxLocalX();
        int minZ = CarveBounds.minLocalZ();
        int maxZ = CarveBounds.maxLocalZ();
        int minY = CarveBounds.minLocalY();
        int maxY = CarveBounds.maxLocalY();

        int steps = (int) (grid.width() * STEPS_PER_WALKER_FACTOR);

        List<SkeletonNode> nodes = new ArrayList<>();
        // 已挖路径点的扁平索引池, 供后续 walker 串联起点 (保证连通)。
        List<int[]> carvedPoints = new ArrayList<>();

        int[] start = {clamp((minX + maxX) / 2, minX, maxX),
                clamp((minY + maxY) / 2, minY, maxY),
                clamp((minZ + maxZ) / 2, minZ, maxZ)};

        int spawnAnchorIdx = grid.index(start[0], start[1], start[2]);

        for (int w = 0; w < WALKERS; w++) {
            int[] cur;
            if (w == 0) {
                cur = start.clone();
            } else {
                // 串联: 从前序已挖点里取一个作起点, 保证新 walker 接入已连通骨架 (7.4.2)。
                cur = carvedPoints.get(rng.nextInt(carvedPoints.size())).clone();
            }
            nodes.add(new SkeletonNode(cur[0], cur[1], cur[2]));

            for (int s = 0; s < steps; s++) {
                carveSphere(grid, cur[0], cur[1], cur[2], TUNNEL_RADIUS, minX, maxX, minY, maxY, minZ, maxZ);
                carvedPoints.add(new int[]{cur[0], cur[1], cur[2]});

                // 6 向随机步进 (轴对齐), Y 步进偏小以维持水平洞穴主导, 避免快速触顶/触底。
                int dir = rng.nextInt(6);
                switch (dir) {
                    case 0 -> cur[0] = clamp(cur[0] + 1, minX, maxX);
                    case 1 -> cur[0] = clamp(cur[0] - 1, minX, maxX);
                    case 2 -> cur[2] = clamp(cur[2] + 1, minZ, maxZ);
                    case 3 -> cur[2] = clamp(cur[2] - 1, minZ, maxZ);
                    case 4 -> cur[1] = clamp(cur[1] + 1, minY, maxY);
                    default -> cur[1] = clamp(cur[1] - 1, minY, maxY);
                }

                // 分支: 以 BRANCH_PROB 从当前点派生一个支线起点 (记入节点池, 后续 walker 可挂载)。
                if (rng.nextDouble() < BRANCH_PROB) {
                    nodes.add(new SkeletonNode(cur[0], cur[1], cur[2]));
                }
            }
        }

        return new SkeletonResult(nodes, spawnAnchorIdx);
    }

    /** 在 (cx,cy,cz) 挖半径 r 的球形空腔, 所有写入坐标都先 clamp 进可雕刻盒 (6.5)。 */
    static void carveSphere(VoxelGrid grid, int cx, int cy, int cz, int r,
                            int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        int r2 = r * r;
        for (int dx = -r; dx <= r; dx++) {
            int x = cx + dx;
            if (x < minX || x > maxX) {
                continue;
            }
            for (int dy = -r; dy <= r; dy++) {
                int y = cy + dy;
                if (y < minY || y > maxY) {
                    continue;
                }
                for (int dz = -r; dz <= r; dz++) {
                    int z = cz + dz;
                    if (z < minZ || z > maxZ) {
                        continue;
                    }
                    if (dx * dx + dy * dy + dz * dz <= r2) {
                        grid.setAir(x, y, z);
                    }
                }
            }
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Mth.clamp(v, lo, hi);
    }
}
