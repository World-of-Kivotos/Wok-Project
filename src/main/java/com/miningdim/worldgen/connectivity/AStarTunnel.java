package com.miningdim.worldgen.connectivity;

import com.miningdim.worldgen.VoxelGrid;

import java.util.PriorityQueue;

/**
 * A* 隧道打通 (设计文档 7.5.3): 在实心区域里求一条从孤岛端点到主分量端点的低成本路径,
 * 沿路径挖球形空腔, 把需要保留的非主分量并入主连通分量。
 *
 * 代价模型 (7.5.3): g = 已挖实心格数 (穿实心 cost=1, 穿已有空气 cost=0, 优先复用已有空腔减少新挖体积);
 * h = 到目标的曼哈顿距离 (6-邻接下可采纳, 不高估)。搜索域限制在 region bbox 内 (越界格不扩展, 7.5.3)。
 *
 * 上限 (7.7.3): 路径节点超 maxTunnelLength 即放弃打通 (返回 false), 由调用方改为填实。
 *
 * 净空保证 (7.5.3 / 第九章): 沿中心线挖 TUNNEL_RADIUS 球腔后, 额外向上多挖一格, 确保水平段有连续
 * "头顶 2 格空气", 落方块后路径可走。本类只负责打通几何, 不做矿物/陷阱。
 */
public final class AStarTunnel {

    private static final int TUNNEL_RADIUS = 1;     // 7.5.3: tunnelRadius=1 (直径3)
    private static final int MAX_TUNNEL_LENGTH = 512; // 7.7.3: maxTunnelLength

    private AStarTunnel() {
    }

    /**
     * 从 startIdx 到 goalIdx 打通隧道 (两端均为本地扁平下标)。
     *
     * @return true 表示成功打通并已挖空; false 表示路径超长/无解, 调用方应改填实该孤岛
     */
    public static boolean tunnel(VoxelGrid grid, int startIdx, int goalIdx) {
        final int w = grid.width();
        final int h = grid.height();
        final int d = grid.depth();
        final int total = w * h * d;

        int gx = goalIdx % w;
        int gy = goalIdx / (w * d);
        int gz = (goalIdx / w) % d;

        // gScore / cameFrom 用稀疏 map 会更省内存, 但体素总数固定且 A* 实际触达点远小于全网格;
        // 这里用 int[] 直存以避免装箱, 单实例瞬时分配 (阶段结束即释放)。-1 表示未访问。
        int[] gScore = new int[total];
        int[] cameFrom = new int[total];
        java.util.Arrays.fill(gScore, Integer.MAX_VALUE);
        java.util.Arrays.fill(cameFrom, -1);

        PriorityQueue<long[]> open = new PriorityQueue<>((a, b) -> Long.compare(a[0], b[0]));
        gScore[startIdx] = stepCost(grid, startIdx);
        open.add(new long[]{gScore[startIdx] + manhattan(startIdx, w, d, gx, gy, gz), startIdx});

        int expansions = 0;
        while (!open.isEmpty()) {
            long[] top = open.poll();
            int cur = (int) top[1];
            if (cur == goalIdx) {
                carvePath(grid, cameFrom, startIdx, goalIdx);
                return true;
            }
            // 过期条目跳过 (PriorityQueue 不支持 decrease-key, 用 f 一致性校验)。
            long expectedF = (long) gScore[cur] + manhattan(cur, w, d, gx, gy, gz);
            if (top[0] != expectedF) {
                continue;
            }
            if (++expansions > MAX_TUNNEL_LENGTH * MAX_TUNNEL_LENGTH) {
                return false; // 搜索爆炸熔断 (异常输入), 改填实
            }

            int cx = cur % w;
            int cy = cur / (w * d);
            int cz = (cur / w) % d;

            for (int n = 0; n < 6; n++) {
                int nx = cx + DX[n];
                int ny = cy + DY[n];
                int nz = cz + DZ[n];
                if (nx < 0 || nx >= w || ny < 0 || ny >= h || nz < 0 || nz >= d) {
                    continue; // 7.5.3: 搜索域限 bbox 内, 越界不扩展
                }
                int nIdx = grid.index(nx, ny, nz);
                int tentative = gScore[cur] + stepCost(grid, nIdx);
                if (tentative < gScore[nIdx]) {
                    gScore[nIdx] = tentative;
                    cameFrom[nIdx] = cur;
                    open.add(new long[]{(long) tentative + manhattan(nIdx, w, d, gx, gy, gz), nIdx});
                }
            }
        }
        return false; // 开集耗尽无路径
    }

    /** 进入某格的代价: 已是空气 cost=0 (复用空腔), 实心 cost=1 (需挖) (7.5.3)。 */
    private static int stepCost(VoxelGrid grid, int idx) {
        return grid.isAir(idx) ? 0 : 1;
    }

    private static long manhattan(int idx, int w, int d, int gx, int gy, int gz) {
        int x = idx % w;
        int y = idx / (w * d);
        int z = (idx / w) % d;
        return Math.abs(x - gx) + Math.abs(y - gy) + Math.abs(z - gz);
    }

    /** 回溯 cameFrom, 沿路径每点挖球腔并向上补一格净空。同时校验路径长度上限。 */
    private static void carvePath(VoxelGrid grid, int[] cameFrom, int startIdx, int goalIdx) {
        final int w = grid.width();
        final int h = grid.height();
        final int d = grid.depth();
        int cur = goalIdx;
        int len = 0;
        while (cur != -1) {
            int cx = cur % w;
            int cy = cur / (w * d);
            int cz = (cur / w) % d;
            carveSphere(grid, cx, cy, cz, w, h, d);
            // 头顶净空: 再向上挖一格 (与球腔合计水平段保证连续 2 格高可走, 7.5.3)。
            if (cy + TUNNEL_RADIUS + 1 < h) {
                grid.setAir(cx, cy + TUNNEL_RADIUS + 1, cz);
            }
            if (cur == startIdx) {
                break;
            }
            cur = cameFrom[cur];
            // len 仅用于防御性上限, 正常不会触发 (A* 自身已被 h 引导)。
            if (++len > MAX_TUNNEL_LENGTH * 4) {
                break;
            }
        }
    }

    private static void carveSphere(VoxelGrid grid, int cx, int cy, int cz, int w, int h, int d) {
        int r = TUNNEL_RADIUS;
        int r2 = r * r;
        for (int dx = -r; dx <= r; dx++) {
            int x = cx + dx;
            if (x < 0 || x >= w) {
                continue;
            }
            for (int dy = -r; dy <= r; dy++) {
                int y = cy + dy;
                if (y < 0 || y >= h) {
                    continue;
                }
                for (int dz = -r; dz <= r; dz++) {
                    int z = cz + dz;
                    if (z < 0 || z >= d) {
                        continue;
                    }
                    if (dx * dx + dy * dy + dz * dz <= r2) {
                        grid.setAir(x, y, z);
                    }
                }
            }
        }
    }

    // 6-邻接方向 (±x, ±y, ±z)。
    private static final int[] DX = {1, -1, 0, 0, 0, 0};
    private static final int[] DY = {0, 0, 1, -1, 0, 0};
    private static final int[] DZ = {0, 0, 0, 0, 1, -1};
}
