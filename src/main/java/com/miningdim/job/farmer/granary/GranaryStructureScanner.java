package com.miningdim.job.farmer.granary;

import net.minecraft.core.BlockPos;

import java.util.List;

/** 粮仓验收和损坏判定共用的结构测量值与容量公式。 */
public final class GranaryStructureScanner {

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

    /**
     * 一次判定的结果 (纯数据, 界面直接照它写清单数字)。
     *
     * {@code bins} 是**整个在屋里**的囤数, {@code binPositions} 是它们各自的坐标 (顺序稳定: 按坐标排序),
     * 验收与启用重排都要按坐标把囤连到粮仓上。判定失败时位置列表为空 —— 失败时只有原因有意义。
     */
    public record ScanResult(int interiorCells, int bins, int binsNotEnclosed, int centers,
                             int vents, int coldSources, Failure failure, List<BlockPos> binPositions) {

        /** 只喂数字的调用方 (界面、纯逻辑用例) 用这个: 位置列表留空。 */
        public ScanResult(int interiorCells, int bins, int binsNotEnclosed, int centers,
                          int vents, int coldSources, Failure failure) {
            this(interiorCells, bins, binsNotEnclosed, centers, vents, coldSources, failure, List.of());
        }

        public ScanResult {
            binPositions = List.copyOf(binPositions);
        }

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
}
