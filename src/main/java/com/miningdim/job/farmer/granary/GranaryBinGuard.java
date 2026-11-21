package com.miningdim.job.farmer.granary;

/**
 * 储粮囤放置裁决 (Spoilage_Granary_DesignSpec「储粮囤额度（看你自己的农夫等级）」)。
 *
 * 纯裁决: 只比较「放置者农夫等级 / 已放的囤数 / 待领粮单占用的囤数」三者并返回结果码, 不读写世界、不吞异常。
 * 额度是**全服合计**: 放在自家、学院、朋友家、不成立的房间或野外都算数 —— 合建只把囤放到一起,
 * 不增加任何人的额度 (反漏洞 #20)。
 */
public final class GranaryBinGuard {

    /** 放置裁决结果码。 */
    public enum PlaceResult {
        /** 通过。 */
        ALLOW,
        /** 已达本等级储粮囤上限。 */
        REJECT_CAP_REACHED
    }

    private GranaryBinGuard() {
    }

    /** 某等级的储粮囤上限 (查配置表; 等级钳到 [1,10])。 */
    public static int capForLevel(int farmerLevel) {
        return GranaryConfig.binCapForLevel(farmerLevel);
    }

    /**
     * 放置裁决。
     *
     * @param farmerLevel      放置者当前的农夫等级 (没练农夫的人也是 L1)
     * @param alreadyPlaced    当前已放下的囤数 (按坐标记账的派生计数)
     * @param pendingClaimBins 待领粮单占用的囤数 (每 27 格折 1 个, 领完才归还)
     * @return 裁决结果
     */
    public static PlaceResult checkPlacement(int farmerLevel, int alreadyPlaced, int pendingClaimBins) {
        if (alreadyPlaced < 0 || pendingClaimBins < 0) {
            throw new IllegalArgumentException("bin counts must be >= 0, got "
                    + alreadyPlaced + " / " + pendingClaimBins);
        }
        return alreadyPlaced + pendingClaimBins >= capForLevel(farmerLevel)
                ? PlaceResult.REJECT_CAP_REACHED
                : PlaceResult.ALLOW;
    }

    /** 玩家还能再放几个 (提示文案与 OP 排查用)。 */
    public static int remainingFor(int farmerLevel, int alreadyPlaced, int pendingClaimBins) {
        return Math.max(0, capForLevel(farmerLevel) - alreadyPlaced - pendingClaimBins);
    }
}
