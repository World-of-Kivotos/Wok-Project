package com.miningdim.job.farmer;

/**
 * mod 耕地放置裁决 (FarmingXP_Mod_DesignSpec 表A 方块上限 + 等级门控耕地档位; 仿 economy.AbuseGuard 风格)。
 *
 * 纯裁决: 只比较 "已放置数 / 玩家等级 / 拟放档位" 三者并返回结果码, 不读写世界、不吞异常 (异常纪律)。
 * 两道门 (设计目标 5 / 表B):
 *  1. 档位门控: 拟放耕地档位的 unlockLevel 必须 <= 玩家当前等级 (例: L4 玩家不能放高级地, 高级 L5 解锁)。
 *  2. 方块上限: 玩家全局已放置的 mod 耕地数必须 < 本等级上限 (表A 第5列; 超限拒放, 硬封顶反扩建)。
 *
 * 计数口径 (spec PENDING 裁决): 按玩家 UUID 全局计 (非按区块/区域/维度), 破坏 mod 耕地即回收计数。理由:
 * 上限是 "单玩家吞吐天花板" (表2 巅峰产出按方块上限算), 全局计数最贴合 "限制单人同时维护的耕地总量";
 * 区块/区域口径会让玩家用多地块绕过 (见 foundationGaps 报告其余口径裁决)。
 */
public final class FarmlandPlacementGuard {

    private FarmlandPlacementGuard() {
    }

    /** 放置裁决结果码 (每码对应一条玩家文案; 仿 AbuseGuard.GateResult)。 */
    public enum PlaceResult {
        /** 通过, 可放置 (放置后计数 +1)。 */
        ALLOW,
        /** 玩家等级未解锁该档耕地 (档位门控)。 */
        REJECT_TIER_LOCKED,
        /** 已达本等级 mod 耕地放置上限 (方块上限硬封顶)。 */
        REJECT_CAP_REACHED
    }

    /**
     * 某等级的 mod 耕地放置上限 (表A 第5列)。等级钳制到 [1,10] (越界等级取最近端, 防御性)。
     *
     * @param playerLevel 玩家农夫当前等级
     * @return 该等级允许的 mod 耕地最大放置数
     */
    public static int capForLevel(int playerLevel) {
        int idx = Math.max(0, Math.min(FarmerConstants.FARMLAND_CAP_PER_LEVEL.length - 1, playerLevel - 1));
        return FarmerConstants.FARMLAND_CAP_PER_LEVEL[idx];
    }

    /**
     * 放置裁决: 先档位门控 (拟放档位是否已解锁), 再方块上限 (已放数是否到顶)。
     *
     * @param tier              拟放置的耕地档位
     * @param currentLevel      玩家农夫当前等级
     * @param alreadyPlacedCount 玩家当前全局已放置的 mod 耕地数 (不含本次)
     * @return 裁决结果
     */
    public static PlaceResult checkPlacement(FarmerTier tier, int currentLevel, int alreadyPlacedCount) {
        if (tier == null) {
            throw new IllegalArgumentException("tier must not be null");
        }
        if (alreadyPlacedCount < 0) {
            throw new IllegalArgumentException("alreadyPlacedCount must be >= 0, got " + alreadyPlacedCount);
        }
        if (!tier.isUnlockedAt(currentLevel)) {
            return PlaceResult.REJECT_TIER_LOCKED;
        }
        if (alreadyPlacedCount >= capForLevel(currentLevel)) {
            return PlaceResult.REJECT_CAP_REACHED;
        }
        return PlaceResult.ALLOW;
    }
}
