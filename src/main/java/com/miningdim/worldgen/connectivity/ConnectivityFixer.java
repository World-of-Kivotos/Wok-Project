package com.miningdim.worldgen.connectivity;

import com.miningdim.worldgen.VoxelGrid;

import java.util.BitSet;

/**
 * Stage 3 连通性修复 (设计文档 7.5), 三阶段管线的最后一道闸 (7.3.2 铁律)。
 *
 * 流程 (7.5.1):
 *  1. 以 spawnAnchor 为起点做 6-邻接 BFS, 标出主连通分量 (visited bitset)。
 *  2. 扫全网格, 对"空气且未被主分量访问"的格作种子, 局部 BFS 得分量体积与边界。
 *  3. 按 7.5.2 判据处理每个非主分量:
 *       vol < MIN_ISLAND_SIZE  -> 填实 (从可达空间剔除)
 *       vol >= MIN_ISLAND_SIZE -> A* 打通接入主分量 (打通失败则填实兜底)
 *  4. 复核: 重新从 spawnAnchor BFS, 断言所有保留空气都可达 (出生点 ∈ 主分量, 7.5.4)。
 *
 * 邻接固定 6-邻接 (玩家行走可达语义, 7.5.1); BFS 边界即 region bbox (box 外恒实心, 越界邻居跳过,
 * 7.7.1 杜绝无界扩张)。BFS 用 int 索引环形队列降 GC 压力 (7.7.3)。
 *
 * MIN_ISLAND_SIZE 为 worldgen 内部平衡常量 (7.5.2 minIslandSize=64, PENDING), IMiningConfig 未暴露
 * 该项, 故就地常量化; 若后续要配置化, 由配置子系统在 core 契约新增 getter 后再接 (不在本期越界改 core)。
 */
public final class ConnectivityFixer {

    // 7.5.2: minIslandSize=64 (格)。小于此填实, 大于等于此用 A* 打通。
    private static final int MIN_ISLAND_SIZE = 64;

    private final VoxelGrid grid;
    private final int width;
    private final int height;
    private final int depth;
    private final int total;

    public ConnectivityFixer(VoxelGrid grid) {
        this.grid = grid;
        this.width = grid.width();
        this.height = grid.height();
        this.depth = grid.depth();
        this.total = width * height * depth;
    }

    /**
     * 执行修复。返回主连通分量的体素数 (供日志/矿物 wallBudget 上游参考); 若 spawnAnchor 自身不是空气,
     * 说明骨架契约被破坏, 自然抛 IllegalStateException (C9, 不掩盖)。
     *
     * @param spawnAnchorIdx 主分量 BFS 起点 (骨架阶段产出的空气格扁平下标)
     */
    public int fix(int spawnAnchorIdx) {
        if (!grid.isAir(spawnAnchorIdx)) {
            throw new IllegalStateException(
                    "ConnectivityFixer: spawn anchor voxel is solid (skeleton broke its connectivity contract): idx="
                            + spawnAnchorIdx);
        }

        // 1. 主分量 BFS (只取其 visited 集合作 handled 基线; 主分量体积在复核时重算, 此处不留)。
        BitSet mainComponent = new BitSet(total);
        floodFillInto(spawnAnchorIdx, mainComponent, null);

        // 2-3. 扫描其余空气分量并处置。已处理 (填实或并入) 的格记入 handled 避免重复扫描。
        BitSet handled = (BitSet) mainComponent.clone();
        int[] queueBuf = new int[total]; // 复用的 BFS 队列缓冲

        for (int idx = 0; idx < total; idx++) {
            if (!grid.isAir(idx) || handled.get(idx)) {
                continue;
            }
            // 局部 BFS 得该分量所有格。
            BitSet component = new BitSet(total);
            int vol = floodFillInto(idx, component, queueBuf);
            handled.or(component);

            if (vol < MIN_ISLAND_SIZE) {
                fillComponent(component); // 7.5.2: 小岛填实
            } else {
                // 7.5.2/7.5.3: 大岛 A* 打通。端点取该分量任一格与主分量最近格的近似:
                // 用该分量第一个格为起点, 主分量锚点为目标 (A* 的 h 会引导收敛, 无需精确最近点对)。
                int from = component.nextSetBit(0);
                boolean ok = AStarTunnel.tunnel(grid, from, spawnAnchorIdx);
                if (!ok) {
                    fillComponent(component); // 打通失败兜底填实, 保证无悬空可达腔
                }
                // 打通后该分量已与主分量连通; 主分量在最终复核统一重算, 此处不必增量并入。
            }
        }

        // 4. 复核: 重算主分量, 断言无遗漏可达空气 (7.5.4 出生点 ∈ 主分量)。
        BitSet finalMain = new BitSet(total);
        int finalVolume = floodFillInto(spawnAnchorIdx, finalMain, queueBuf);
        int reachableAir = grid.airCount();
        if (finalVolume != reachableAir) {
            // 仍有空气不属主分量 -> A* 后又产生不可达腔 (理论上不应发生)。把残余空气填实,
            // 确保 D4 承诺"可行走空气全连通"在输出上成立, 而非静默放过。
            fillUnreachable(finalMain);
            finalVolume = grid.airCount();
        }
        return finalVolume;
    }

    /** 6-邻接洪泛, 标记访问集并返回分量体积。queueBuf 可为 null (内部新建)。 */
    private int floodFillInto(int seedIdx, BitSet visited, int[] queueBuf) {
        int[] queue = (queueBuf != null) ? queueBuf : new int[total];
        int head = 0;
        int tail = 0;
        queue[tail++] = seedIdx;
        visited.set(seedIdx);
        int volume = 0;

        while (head < tail) {
            int cur = queue[head++];
            volume++;
            int x = cur % width;
            int y = cur / (width * depth);
            int z = (cur / width) % depth;

            for (int n = 0; n < 6; n++) {
                int nx = x + DX[n];
                int ny = y + DY[n];
                int nz = z + DZ[n];
                if (nx < 0 || nx >= width || ny < 0 || ny >= height || nz < 0 || nz >= depth) {
                    continue; // box 边界即墙 (7.7.1)
                }
                int nIdx = grid.index(nx, ny, nz);
                if (!visited.get(nIdx) && grid.isAir(nIdx)) {
                    visited.set(nIdx);
                    queue[tail++] = nIdx;
                }
            }
        }
        return volume;
    }

    private void fillComponent(BitSet component) {
        for (int i = component.nextSetBit(0); i >= 0; i = component.nextSetBit(i + 1)) {
            grid.setSolid(i);
        }
    }

    /** 把不属于 main 的所有空气格填实 (复核兜底)。 */
    private void fillUnreachable(BitSet main) {
        for (int i = 0; i < total; i++) {
            if (grid.isAir(i) && !main.get(i)) {
                grid.setSolid(i);
            }
        }
    }

    // 6-邻接方向。
    private static final int[] DX = {1, -1, 0, 0, 0, 0};
    private static final int[] DY = {0, 0, 1, -1, 0, 0};
    private static final int[] DZ = {0, 0, 0, 0, 1, -1};
}
