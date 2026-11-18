package com.miningdim.job.farmer.granary;

import com.miningdim.job.farmer.granary.GranaryStructureScanner.ScanResult;

/**
 * 损坏判定 (Spoilage_Granary_DesignSpec「损坏与宽限」「容量怎么算」)。
 *
 * 判据只有一条界线, 但这条界线是整段规则的关键: **基础要求不满足、漏风、进水、账台冲突才判整座损坏**;
 * 「囤比可支撑的多」不算损坏 (那种情况只把最后入册的几个排成停用, 也走宽限期)。
 *
 * 所以这里看的是「室内格数够不够本档的**基础**要求」, 而**不是**「够不够放下每一个囤」—— 后者是
 * {@link GranaryEnablement} 那条轴。搞混的后果很直接: 少一扇气窗会被判成整座损坏, 于是全仓的囤一起停表。
 *
 * 未加载的区块由调用方先挡掉 (规格: 房子跨到未加载区块时不判, 保持原状态), 这里只吃已经算出来的判定结果。
 */
public final class GranaryDamage {

    private GranaryDamage() {
    }

    /** 是不是整座损坏 (规格列的四条)。 */
    public static boolean structural(GranaryTier tier, ScanResult scan) {
        if (!scan.ok()) {
            // 漏风、进水、太大、超范围、两座账台冲突、账台卡在两间屋之间 —— 都在 failure 里。
            return true;
        }
        return scan.interiorCells() < tier.minInteriorCells();
    }

    /** 坏在哪 (给日志、提醒与结构页用; 没坏返回「成立」)。 */
    public static String describe(GranaryTier tier, ScanResult scan) {
        if (!scan.ok()) {
            return GranaryAcceptance.describe(scan.failure());
        }
        if (scan.interiorCells() < tier.minInteriorCells()) {
            return "室内只剩 " + scan.interiorCells() + " 格, 这一档至少要 " + tier.minInteriorCells() + " 格";
        }
        return "成立";
    }
}
