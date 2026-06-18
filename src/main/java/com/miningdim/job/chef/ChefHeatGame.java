package com.miningdim.job.chef;

/**
 * 火候小游戏的服务端权威状态机 (Chef_Job_DesignSpec 第四章 第 1 层 "火候条")。
 *
 * 模型: 温度 (heat) 随 tick 线性上涨, 玩家点击在 "完美绿区" [{@link #GREEN_LOW}, {@link #GREEN_HIGH}] 内
 * 命中评分高; 太生 (绿区前点) -> 低分; 过火 (越过绿区还没出锅) -> 失败品/多盐风险 (仅低/中/高, 由
 * {@link ChefQuality#noFailure()} 在结算处兜底)。
 *
 * 服务端权威 (第四章, 防作弊): 温度推进与命中评分全在服务端 ({@link SeasoningTableBlockEntity#serverTick}
 * 调 {@link #tick()}, 客户端点击经 C2S 调 {@link #click(int)} 由服务端按当前 heat 评分)。客户端只渲染温度条,
 * 不参与判定。
 *
 * 评分 ({@link #accuracyScore()}): [0,1], 越接近绿区中点越高; 用于与调味命中数综合算品质档。
 */
public final class ChefHeatGame {

    /** 温度上界 (满锅): 到此还没出锅判过火。单位为抽象 "热度点"。 */
    public static final int HEAT_MAX = 200;
    /** 完美绿区下界。 */
    public static final int GREEN_LOW = 120;
    /** 完美绿区上界。 */
    public static final int GREEN_HIGH = 160;
    /** 每 tick 升温 (HEAT_MAX/此值 约= 升满 tick 数; 10 tick/点 -> 升满 ~100 tick=5s)。 */
    private static final int HEAT_PER_TICK = 2;

    private int heat;
    /** 玩家出锅时锁定的命中热度 (-1 = 未点击/未出锅)。 */
    private int lockedHeat = -1;
    private boolean overcooked;

    /** 当前温度 (客户端渲染读取)。 */
    public int heat() {
        return heat;
    }

    public int lockedHeat() {
        return lockedHeat;
    }

    public boolean isLocked() {
        return lockedHeat >= 0;
    }

    /** 是否过火 (越过 HEAT_MAX 仍未出锅)。 */
    public boolean overcooked() {
        return overcooked;
    }

    /** 服务端每 tick 升温; 越过 HEAT_MAX 且未锁定 -> 过火。已锁定则停止升温。 */
    public void tick() {
        if (lockedHeat >= 0) {
            return;
        }
        heat += HEAT_PER_TICK;
        if (heat >= HEAT_MAX) {
            heat = HEAT_MAX;
            overcooked = true;
        }
    }

    /**
     * 玩家点击 "出锅" (服务端按当前 heat 锁定命中热度)。已锁定的重复点击忽略 (防连点刷分)。
     * @param atHeat 服务端当前 heat (调用方传 this.heat, 客户端不传热度防伪造)
     */
    public void click(int atHeat) {
        if (lockedHeat >= 0) {
            return;
        }
        lockedHeat = atHeat;
    }

    /**
     * 火候精度评分 [0,1]: 锁定热度落在绿区中点 (GREEN_LOW+GREEN_HIGH)/2 得满分, 偏离线性衰减;
     * 过火 (未锁定就越界) 评 0; 未锁定 (做菜未点出锅) 评 0。
     */
    public double accuracyScore() {
        if (overcooked || lockedHeat < 0) {
            return 0.0D;
        }
        int mid = (GREEN_LOW + GREEN_HIGH) / 2;
        int halfWidth = (GREEN_HIGH - GREEN_LOW) / 2;
        int dist = Math.abs(lockedHeat - mid);
        if (dist <= halfWidth) {
            // 绿区内: 接近中点满分, 接近边缘 0.7 (绿区命中即不错)。
            double withinFactor = 1.0D - (double) dist / halfWidth;
            return 0.7D + 0.3D * withinFactor;
        }
        // 绿区外但未过火 (太生): 距绿区越远分越低, 远到 mid 距离即 0。
        double outDist = dist - halfWidth;
        double maxOut = mid; // 从绿区边缘到 0 热度的最大可能距离 (粗略归一)。
        double factor = Math.max(0.0D, 1.0D - outDist / maxOut);
        return 0.5D * factor; // 太生最高 0.5 (绿区外不可能高分)。
    }

    /** 重置 (开始一道新菜)。 */
    public void reset() {
        heat = 0;
        lockedHeat = -1;
        overcooked = false;
    }
}
