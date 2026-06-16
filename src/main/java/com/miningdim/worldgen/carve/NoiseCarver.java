package com.miningdim.worldgen.carve;

import com.miningdim.core.Difficulty;
import com.miningdim.core.SeedUtil;
import com.miningdim.worldgen.CarveBounds;
import com.miningdim.worldgen.GenContext;
import com.miningdim.worldgen.VoxelGrid;

/**
 * Stage 2 噪声雕刻 (设计文档 7.3.1 / 7.3.2): 在骨架空气掩码上做 3D 噪声扩挖, 让通道边缘更自然。
 *
 * 设计要点:
 *  - 只"扩挖" (solid -> air), 不回填 (air 保持 air)。因此绝不切断骨架已建立的连通性 ——
 *    雕刻只可能新增孤立空腔 (新孤岛), 这些孤岛由其后的 ConnectivityFix 统一处理 (7.3.2 铁律:
 *    连通性是最后一道闸)。本阶段无需自保连通。
 *  - 扩挖条件: 该实心格紧邻至少一个空气格 (沿骨架壁面生长), 且其确定性噪声值超过难度相关阈值。
 *    紧邻判定使雕刻沿现有洞壁扩张而非随机散点, 形态更连贯。
 *  - 严格内收于可雕刻盒 (6.5): 越界格直接跳过, 隔层/顶板/底板恒不被触及。
 *  - 确定性 (7.6 / D3): 噪声值由 SeedUtil.hash(stageSeed, x, z, featureId=y) 派生, 与坐标绑定、
 *    无可变 Random 推进顺序, 同 seed 逐格一致。
 *
 * 难度差异 (7.4 形态目标): Easy 阈值低 -> 扩挖多 -> 通道宽松; Hard 阈值高 -> 扩挖少 -> 规整紧凑。
 * 参数为 PENDING 初值, 待平衡校验。
 */
public final class NoiseCarver {

    // 难度 -> 扩挖概率阈值 (噪声归一化到 [0,1), 值 < 阈值才扩挖)。索引 = Difficulty.id()。
    // Easy 0.55 (多)、Medium 0.40、Hard 0.28 (少)。PENDING 待平衡。
    private static final double[] CARVE_THRESHOLD = {0.55, 0.40, 0.28};

    private NoiseCarver() {
    }

    /**
     * 在 grid 上执行噪声扩挖。读旧空气态决定生长、写新空气态, 故先快照一份"原始空气"判据再扩挖,
     * 避免本轮新挖的格立即又触发邻接扩挖造成雪崩 (单轮 cellular 生长, 确定且有界)。
     */
    public static void apply(VoxelGrid grid, GenContext ctx) {
        Difficulty d = ctx.difficulty();
        long stageSeed = ctx.stageSeed(GenContext.STAGE_CARVE);
        double threshold = CARVE_THRESHOLD[d.id()];

        int minX = CarveBounds.minLocalX();
        int maxX = CarveBounds.maxLocalX();
        int minZ = CarveBounds.minLocalZ();
        int maxZ = CarveBounds.maxLocalZ();
        int minY = CarveBounds.minLocalY();
        int maxY = CarveBounds.maxLocalY();

        // 快照本轮的空气判据 (单轮生长基于扩挖前的洞壁), copy 切断别名。
        VoxelGrid before = grid.copy();

        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    if (before.isAir(x, y, z)) {
                        continue; // 已是空气, 不重复处理
                    }
                    if (!adjacentToAir(before, x, y, z)) {
                        continue; // 只沿现有洞壁扩挖, 不在实心深处凭空开洞
                    }
                    // 坐标派生噪声 ∈ [0,1): featureId 维度放 y, 使三维都进入 hash。
                    long h = SeedUtil.hash(stageSeed, x, z, y);
                    double noise = toUnitFloat(h);
                    if (noise < threshold) {
                        grid.setAir(x, y, z);
                    }
                }
            }
        }
    }

    /** 6-邻接内是否存在空气格 (沿壁面生长判据)。读快照 before, 不读写中网格避免雪崩。 */
    private static boolean adjacentToAir(VoxelGrid g, int x, int y, int z) {
        return (g.inBounds(x + 1, y, z) && g.isAir(x + 1, y, z))
                || (g.inBounds(x - 1, y, z) && g.isAir(x - 1, y, z))
                || (g.inBounds(x, y + 1, z) && g.isAir(x, y + 1, z))
                || (g.inBounds(x, y - 1, z) && g.isAir(x, y - 1, z))
                || (g.inBounds(x, y, z + 1) && g.isAir(x, y, z + 1))
                || (g.inBounds(x, y, z - 1) && g.isAir(x, y, z - 1));
    }

    /** 把 64 位 hash 的高 53 位映射到 [0,1) double (与 SeedUtil 风格一致的确定性浮点)。 */
    private static double toUnitFloat(long h) {
        return (h >>> 11) * 0x1.0p-53;
    }
}
