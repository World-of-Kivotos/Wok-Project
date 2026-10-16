package com.miningdim.job.farmer.granary;

import com.miningdim.job.farmer.granary.block.GranaryBinBlock;
import com.miningdim.job.farmer.granary.block.GranaryCenterBlock;
import com.miningdim.job.farmer.granary.block.GranaryVentBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * 粮仓结构判定 (Spoilage_Granary_DesignSpec「房间判定」「容量怎么算」)。
 *
 * 从账台周围每个能走的格子起洪泛, 把「账台所在的封闭屋子」找出来, 顺带数清屋里有什么:
 * 室内格数、储粮囤 (含「必须整个在屋里」的过滤)、账台数、气窗数、冷源数。
 *
 * 判据逐条对应设计文档:
 * <ol>
 *   <li>哪些算墙: 门、活板门、栅栏门不管开关都算墙; 气窗也算墙 (所以气窗不参与洪泛, 只被计数);</li>
 *   <li>哪些算漏洞: 树叶、脚手架、蜘蛛网、细雪 —— 碰到就判漏风, 立刻失败;</li>
 *   <li>屋里不能有水或岩浆;</li>
 *   <li>技术上限: 室内最多 4096 格、范围以账台为中心水平 ±24 垂直 ±12 (防卡服);</li>
 *   <li>储粮囤四周所有能走的格子都在屋里才算数 (嵌在墙里、或有一面朝外的不算);</li>
 *   <li>一间屋只能有一座账台; 账台两边都是屋子的判冲突 (提示换个位置)。</li>
 * </ol>
 *
 * 只读世界、不改方块, 也不做分帧 (分帧由调用方按「同一座最快每 20 tick 一次」的节流决定)。
 */
public final class GranaryStructureScanner {

    /** 室内格数上限 (技术上限, 防卡服)。 */
    public static final int MAX_INTERIOR_CELLS = 4_096;

    /** 以账台为中心的水平半径与垂直半径。 */
    public static final int HORIZONTAL_RADIUS = 24;
    public static final int VERTICAL_RADIUS = 12;

    /** 每个储粮囤至少要有的室内格数。 */
    public static final int CELLS_PER_BIN = 9;

    /** 一扇气窗能撑几个囤。 */
    public static final int BINS_PER_VENT = 4;

    /** 一个冷源能撑几个囤。 */
    public static final int BINS_PER_COLD_SOURCE = 2;

    /** 判定失败的原因 (每个原因对应一条提示)。 */
    public enum Failure {
        /** 成立。 */
        NONE,
        /** 漏风: 洪泛碰到树叶、脚手架、蜘蛛网或细雪。 */
        LEAK,
        /** 屋里进水或岩浆。 */
        FLUID,
        /** 室内格数超过技术上限。 */
        TOO_LARGE,
        /** 超出以账台为中心的范围。 */
        OUT_OF_RANGE,
        /** 一间屋里有不止一座账台。 */
        DUPLICATE_CENTER,
        /** 账台正好卡在两间屋之间。 */
        CENTER_BETWEEN_ROOMS
    }

    /** 一次判定的结果 (纯数据, 界面直接照它写清单数字)。 */
    public record ScanResult(int interiorCells, int bins, int binsNotEnclosed, int centers,
                             int vents, int coldSources, Failure failure) {

        public boolean ok() {
            return failure == Failure.NONE;
        }

        /** 可支撑囤数: 室内 ÷ 9、气窗 × 4 取小; 冷库与官仓再加「冷源 × 2」这一项。 */
        public int supportedBins(boolean coldTier) {
            int byCells = interiorCells / CELLS_PER_BIN;
            int byVents = vents * BINS_PER_VENT;
            if (!coldTier) {
                return Math.min(byCells, byVents);
            }
            return Math.min(Math.min(byCells, byVents), coldSources * BINS_PER_COLD_SOURCE);
        }
    }

    private GranaryStructureScanner() {
    }

    /** 判定账台所在的屋子。 */
    public static ScanResult scan(Level level, BlockPos centerPos) {
        Set<Long> interior = new HashSet<>();
        Set<Long> countedParts = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        Set<Long> seeds = new HashSet<>();

        // 账台自己占的那一格算室内 (规格: 洪泛时账台、囤、冷源占的格子算室内), 也先记进「已数过的部件」——
        // 否则洪泛从旁边那一格看回来时会把同一座账台再数一次, 于是成立的小屋被判成「一间屋两座账台」。
        interior.add(centerPos.asLong());
        countedParts.add(centerPos.asLong());

        // 洪泛只走空气: 囤、账台、冷源、气窗都是实心方块, 从它们**穿过去**会把屋外的世界也算进来
        // (实测就是这么踩到的 —— 一片嵌在墙里的囤把洪泛引到屋外, 于是判成「漏水」)。
        for (Direction direction : Direction.values()) {
            BlockPos start = centerPos.relative(direction);
            if (level.getBlockState(start).isAir()) {
                seeds.add(start.asLong());
                queue.add(start);
            }
        }

        int centers = 1; // 自己
        int bins = 0;
        int vents = 0;
        int coldSources = 0;

        while (!queue.isEmpty()) {
            BlockPos pos = queue.poll();
            if (!interior.add(pos.asLong())) {
                continue;
            }
            if (interior.size() > MAX_INTERIOR_CELLS) {
                return new ScanResult(interior.size(), bins, 0, centers, vents, coldSources, Failure.TOO_LARGE);
            }
            if (outOfRange(pos, centerPos)) {
                return new ScanResult(interior.size(), bins, 0, centers, vents, coldSources, Failure.OUT_OF_RANGE);
            }
            for (Direction direction : Direction.values()) {
                BlockPos next = pos.relative(direction);
                BlockState nextState = level.getBlockState(next);
                if (isLeak(nextState)) {
                    return new ScanResult(interior.size(), bins, 0, centers, vents, coldSources, Failure.LEAK);
                }
                if (!nextState.getFluidState().isEmpty()) {
                    return new ScanResult(interior.size(), bins, 0, centers, vents, coldSources, Failure.FLUID);
                }
                if (nextState.isAir()) {
                    queue.add(next);
                    continue;
                }
                // 贴着室内的部件: 记账 + 算一格室内, 但不从它继续洪泛 (它是实心的)。
                boolean first = countedParts.add(next.asLong());
                if (nextState.getBlock() instanceof GranaryBinBlock) {
                    if (first) {
                        bins++;
                    }
                    interior.add(next.asLong());
                } else if (nextState.getBlock() instanceof GranaryCenterBlock) {
                    if (first) {
                        centers++;
                    }
                    interior.add(next.asLong());
                } else if (nextState.getBlock() instanceof GranaryVentBlock) {
                    if (first) {
                        vents++;
                    }
                } else if (isColdSource(nextState)) {
                    if (first) {
                        coldSources++;
                    }
                    interior.add(next.asLong());
                }
            }
        }

        if (centers > 1) {
            return new ScanResult(interior.size(), bins, 0, centers, vents, coldSources, Failure.DUPLICATE_CENTER);
        }
        for (long seed : seeds) {
            if (!interior.contains(seed)) {
                // 有一侧的种子没被洪泛到: 账台卡在两间屋之间。
                return new ScanResult(interior.size(), bins, 0, centers, vents, coldSources,
                        Failure.CENTER_BETWEEN_ROOMS);
            }
        }

        int notEnclosed = 0;
        int fullyInside = 0;
        for (long key : interior) {
            BlockPos cell = BlockPos.of(key);
            if (!(level.getBlockState(cell).getBlock() instanceof GranaryBinBlock)) {
                continue;
            }
            if (isFullyInside(level, cell, interior)) {
                fullyInside++;
            } else {
                notEnclosed++;
            }
        }
        return new ScanResult(interior.size(), fullyInside, notEnclosed, centers, vents, coldSources, Failure.NONE);
    }

    /**
     * 储粮囤必须整个在屋里: 四周所有**能走的**格子都得是室内格。
     * 有一面朝外 (邻格是空气却不属于这间屋) 就不算数 —— 嵌在墙里的囤正是这种情况。
     */
    private static boolean isFullyInside(Level level, BlockPos binPos, Set<Long> interior) {
        for (Direction direction : Direction.values()) {
            BlockPos neighbour = binPos.relative(direction);
            if (level.getBlockState(neighbour).isAir() && !interior.contains(neighbour.asLong())) {
                return false;
            }
        }
        return true;
    }

    /** 漏洞: 碰到就判漏风 (树叶、脚手架、蜘蛛网、细雪)。 */
    private static boolean isLeak(BlockState state) {
        return state.is(BlockTags.LEAVES)
                || state.is(Blocks.SCAFFOLDING)
                || state.is(Blocks.COBWEB)
                || state.is(Blocks.POWDER_SNOW);
    }

    /** 冷源: 浮冰、蓝冰 (refurbished 的冰箱/冷柜/冷藏箱随兼容那一批加进来)。 */
    private static boolean isColdSource(BlockState state) {
        return state.is(Blocks.PACKED_ICE) || state.is(Blocks.BLUE_ICE);
    }

    private static boolean outOfRange(BlockPos pos, BlockPos center) {
        return Math.abs(pos.getX() - center.getX()) > HORIZONTAL_RADIUS
                || Math.abs(pos.getZ() - center.getZ()) > HORIZONTAL_RADIUS
                || Math.abs(pos.getY() - center.getY()) > VERTICAL_RADIUS;
    }
}
