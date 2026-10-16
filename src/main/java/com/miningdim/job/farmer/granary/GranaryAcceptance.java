package com.miningdim.job.farmer.granary;

import com.miningdim.job.farmer.granary.GranaryStructureScanner.Failure;
import com.miningdim.job.farmer.granary.GranaryStructureScanner.ScanResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 验收裁决 (Spoilage_Granary_DesignSpec「建造与验收」「容量怎么算」)。
 *
 * 纯函数: 输入一次结构判定的结果与拟验收的档位, 输出「能不能验收」与逐条原因 ——
 * 界面上的检查清单直接照它写数字, 所以原因里带的是实际值而不是「不满足要求」这种空话。
 *
 * 三条硬要求:
 * <ol>
 *   <li>结构本身成立 (封闭、没进水、没超技术上限、没两座账台);</li>
 *   <li>室内格数够本档, 并且每个入册的囤有 9 格空间 (囤比可支撑的多时, 超出的是「待启用」不算入册);</li>
 *   <li>气窗每 4 个囤 1 扇且至少 1 扇; 冷库与官仓再加「每 2 个囤 1 个冷源且至少 2 个」。</li>
 * </ol>
 */
public final class GranaryAcceptance {

    /** 一次验收裁决。 */
    public record Verdict(boolean accepted, GranaryTier tier, List<String> problems) {

        /** 检查清单 (界面直接显示这一串)。 */
        public String checklist() {
            return problems.isEmpty() ? "结构与部件都满足" : String.join("; ", problems);
        }
    }

    private GranaryAcceptance() {
    }

    /**
     * 裁决某一档能不能验收。
     *
     * @param tier        拟验收的档位
     * @param scan        当前结构判定结果
     * @param pendingBins 这座粮仓里「待启用」的囤数 (超出的那部分不计入要求)
     */
    public static Verdict check(GranaryTier tier, ScanResult scan, int pendingBins) {
        List<String> problems = new ArrayList<>();
        if (!scan.ok()) {
            problems.add("结构不成立: " + describe(scan.failure()));
        }
        if (scan.centers() > 1) {
            problems.add("一间屋里有 " + scan.centers() + " 座账台, 只能留一座");
        }
        if (scan.binsNotEnclosed() > 0) {
            problems.add("有 " + scan.binsNotEnclosed() + " 个储粮囤不算数 (嵌在墙里或有一面朝外)");
        }
        int enrolled = Math.max(0, scan.bins() - pendingBins);
        int cellsNeeded = Math.max(tier.minInteriorCells(), enrolled * GranaryStructureScanner.CELLS_PER_BIN);
        if (scan.interiorCells() < cellsNeeded) {
            problems.add("室内 " + scan.interiorCells() + " 格, 这一档至少要 " + cellsNeeded + " 格");
        }
        int ventsNeeded = GranaryTier.requiredVents(scan);
        if (scan.vents() < ventsNeeded) {
            problems.add("气窗 " + scan.vents() + " 扇, 这一档至少要 " + ventsNeeded + " 扇");
        }
        if (tier.needsColdSources()) {
            int coldNeeded = Math.max(2, (int) Math.ceil(enrolled
                    / (double) GranaryStructureScanner.BINS_PER_COLD_SOURCE));
            if (scan.coldSources() < coldNeeded) {
                problems.add("冷源 " + scan.coldSources() + " 个, 这一档至少要 " + coldNeeded + " 个");
            }
        }
        return new Verdict(problems.isEmpty(), tier, List.copyOf(problems));
    }

    /**
     * 服务器在点击那一刻读验收人的等级, 决定这一下能验收的最高档:
     * 结构够、并且**比当前档高**, 才允许 (档位只升不降; 降档只有房主能做, 走另一条路)。
     *
     * @param current      当前已锁定的档位 (还没验收过传 null)
     * @param farmerLevel  点击者的农夫等级
     * @param scan         当前结构判定结果
     * @param pendingBins  待启用的囤数
     */
    public static Optional<Verdict> acceptableNow(GranaryTier current, int farmerLevel, ScanResult scan,
                                                  int pendingBins) {
        GranaryTier candidate = current == null ? GranaryTier.SHED : current.next();
        if (candidate == current) {
            // 已经到顶 (官仓): 没有更高的档位可验收。
            return Optional.empty();
        }
        if (farmerLevel < candidate.unlockLevel()) {
            return Optional.empty();
        }
        Verdict verdict = check(candidate, scan, pendingBins);
        return verdict.accepted() ? Optional.of(verdict) : Optional.empty();
    }

    /** 逐条列出「现在离某一档还差什么」, 供结构页在没有可验收档位时提示。 */
    public static String describe(Failure failure) {
        return switch (failure) {
            case NONE -> "成立";
            case LEAK -> "漏风 (树叶、脚手架、蜘蛛网、细雪都算漏洞)";
            case FLUID -> "屋里有水或岩浆";
            case TOO_LARGE -> "房子太大, 请隔成两间, 各放一座账台";
            case OUT_OF_RANGE -> "超出账台周围 ±" + GranaryStructureScanner.HORIZONTAL_RADIUS
                    + " / ±" + GranaryStructureScanner.VERTICAL_RADIUS + " 的范围";
            case DUPLICATE_CENTER -> "一间屋里有不止一座账台";
            case CENTER_BETWEEN_ROOMS -> "账台卡在两间屋之间, 请换个位置";
        };
    }
}
