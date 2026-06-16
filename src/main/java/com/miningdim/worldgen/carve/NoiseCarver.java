package com.miningdim.worldgen.carve;

import com.miningdim.core.Difficulty;
import com.miningdim.core.RegionBox;
import com.miningdim.core.SeedUtil;
import com.miningdim.worldgen.CarveBounds;
import com.miningdim.worldgen.GenContext;
import com.miningdim.worldgen.VoxelGrid;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/**
 * Stage 2 噪声雕刻 (设计文档 7.3.1 / 7.3.2): 在骨架空气掩码上做连续噪声扩挖, 让通道边缘自然、
 * 并小概率开出大洞厅。
 *
 * 为何用连续噪声 (NormalNoise) 而非逐格白噪声: 早先版本对每个洞壁格独立 hash 抛硬币, 相邻格不相关,
 * 结果是椒盐状麻点侵蚀。连续噪声相邻坐标值平滑过渡, 挖出的是连片的圆润鼓包, 接近自然洞穴观感。
 *
 * 两层噪声 + 多趟生长:
 *  - 通道扩挖 (widen, 中频): 沿现有洞壁外扩, 决定通道粗细。普通区域阈值偏高, 每趟只削最外一层壁,
 *    多趟累计也只是适度加宽 -> 以"通道型"为主体。
 *  - 大洞掩码 (cavern, 低频 + 高门控): 只有少数大尺度团块区域过门 (门越高越罕见), 在那里下调扩挖阈值,
 *    多趟累计深挖, 邻近通道并腔成厅 -> "小概率大洞"。
 *
 * 连通性: 所有扩挖都是"贴现有空气格生长" (邻接判据读上一趟快照), 故新挖格必与已连通骨架相邻,
 * 不产生飞地; 连通性最终仍由其后的 ConnectivityFix 兜底保证 (7.3.2 铁律)。
 *
 * 确定性 (7.6 / D3): 两路噪声各由 stageSeed 经不同 featureId 派生独立 RandomSource 构造,
 * getValue 为纯函数, 同 instanceSeed 逐格可复现。
 *
 * 雕刻严格内收于 CarveBounds (6.5): 隔层/顶板/底板恒不被触及。参数为 PENDING 初值, 待平衡校验。
 */
public final class NoiseCarver {

    // featureId: 区分两路噪声的随机流, 避免在同坐标撞流 (D3)。
    private static final int FEATURE_WIDEN = 1;
    private static final int FEATURE_CAVERN = 2;

    // 通道扩挖噪声起始倍频: octave -5 基础波长约 32 格, 叠 3 倍频 (-5/-4/-3 => 32/16/8 格特征), 控通道粗细。
    private static final int WIDEN_FIRST_OCTAVE = -5;
    // 大洞掩码噪声起始倍频: octave -7 波长约 128 格的大尺度团块, 叠 2 倍频, 决定大洞在哪成片出现。
    private static final int CAVERN_FIRST_OCTAVE = -7;

    // 每难度通道扩挖阈值 (widen 噪声 > 阈值才挖; 噪声 ∈ ~[-1,1])。阈值越高挖得越少 => 通道越紧凑。
    // Easy 宽松、Medium 居中、Hard 紧凑。索引 = Difficulty.id()。PENDING 待平衡。
    private static final double[] WIDEN_THRESHOLD = {0.00, 0.15, 0.30};

    // 扩挖趟数: 趟数越多, 大洞区累计挖得越深 (普通区因阈值高仍只削外壁, 不会无限扩张)。PENDING。
    private static final int WIDEN_PASSES = 3;

    // 大洞门控: cavern 噪声 > 此门的区域才放宽扩挖阈值。门越高 => 大洞越罕见 ("小概率")。PENDING。
    private static final double CAVERN_GATE = 0.55;
    // 大洞区的扩挖阈值下调量: 越大 => 大洞越空旷。PENDING。
    private static final double CAVERN_WIDEN_BONUS = 0.55;

    private NoiseCarver() {
    }

    /**
     * 在 grid 上执行连续噪声扩挖。每趟读上一趟快照决定"贴空气"判据 (避免本趟新挖格立即又触发邻接扩挖
     * 造成单趟雪崩), 写回当前网格; 多趟之间才逐层推进。
     */
    public static void apply(VoxelGrid grid, GenContext ctx) {
        final Difficulty difficulty = ctx.difficulty();
        final RegionBox box = ctx.box();
        final long stageSeed = ctx.stageSeed(GenContext.STAGE_CARVE);

        final RandomSource widenRng = SeedUtil.fromSeed(SeedUtil.hash(stageSeed, 0, 0, FEATURE_WIDEN));
        final RandomSource cavernRng = SeedUtil.fromSeed(SeedUtil.hash(stageSeed, 0, 0, FEATURE_CAVERN));
        final NormalNoise widen = NormalNoise.create(widenRng, WIDEN_FIRST_OCTAVE, 1.0, 1.0, 1.0);
        final NormalNoise cavern = NormalNoise.create(cavernRng, CAVERN_FIRST_OCTAVE, 1.0, 1.0);

        final int minX = CarveBounds.minLocalX();
        final int maxX = CarveBounds.maxLocalX();
        final int minZ = CarveBounds.minLocalZ();
        final int maxZ = CarveBounds.maxLocalZ();
        final int minY = CarveBounds.minLocalY();
        final int maxY = CarveBounds.maxLocalY();
        final double baseThreshold = WIDEN_THRESHOLD[difficulty.id()];

        for (int pass = 0; pass < WIDEN_PASSES; pass++) {
            final VoxelGrid before = grid.copy();
            for (int y = minY; y <= maxY; y++) {
                final int wy = box.localToWorldY(y);
                for (int z = minZ; z <= maxZ; z++) {
                    final int wz = box.localToWorldZ(z);
                    for (int x = minX; x <= maxX; x++) {
                        if (before.isAir(x, y, z)) {
                            continue; // 已是空气, 不重复处理
                        }
                        if (!adjacentToAir(before, x, y, z)) {
                            continue; // 只沿现有洞壁生长, 不在实心深处凭空开洞 (保连通、防飞地)
                        }
                        final int wx = box.localToWorldX(x);
                        double threshold = baseThreshold;
                        if (cavern.getValue(wx, wy, wz) > CAVERN_GATE) {
                            threshold -= CAVERN_WIDEN_BONUS; // 大洞区: 放宽阈值, 多挖
                        }
                        if (widen.getValue(wx, wy, wz) > threshold) {
                            grid.setAir(x, y, z);
                        }
                    }
                }
            }
        }
    }

    /** 6-邻接内是否存在空气格 (沿壁面生长判据)。读快照 g, 不读写中网格避免雪崩。 */
    private static boolean adjacentToAir(VoxelGrid g, int x, int y, int z) {
        return (g.inBounds(x + 1, y, z) && g.isAir(x + 1, y, z))
                || (g.inBounds(x - 1, y, z) && g.isAir(x - 1, y, z))
                || (g.inBounds(x, y + 1, z) && g.isAir(x, y + 1, z))
                || (g.inBounds(x, y - 1, z) && g.isAir(x, y - 1, z))
                || (g.inBounds(x, y, z + 1) && g.isAir(x, y, z + 1))
                || (g.inBounds(x, y, z - 1) && g.isAir(x, y, z - 1));
    }
}
