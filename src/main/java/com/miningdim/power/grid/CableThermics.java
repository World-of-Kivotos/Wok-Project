package com.miningdim.power.grid;

/**
 * 线缆热学纯函数 (设计文档第三章)。无状态、无副作用: 输入当前网温 + 本 settlement 负载 + 材料参数, 输出下一网温 /
 * 有效效率。温度是每张网一个值 (per-network), 本类每 settlement 被调用一次, O(1), 绝不进 per-cable 路径。
 *
 * 现实背书: 焦耳定律 P=I^2R —— 负载 75%->100% 电流仅 +33%, 发热却 +78% (1.33^2), 故过载升温超线性;
 * 金属正温度系数 R(T)=R0[1+α(T-T0)] —— 温升则阻升则效率降, 降到材料 floor 为止。
 *
 * 所有常量为占位 (PENDING), 落码前过经济总表标定; 升温速率取"满载约 1°C/tick"量级, 令过载在可测窗口内显效。
 */
public final class CableThermics {

    /** 环境温度 (°C), 网温的下限与冷却回落目标。 */
    public static final double AMBIENT_C = 20.0;

    /** 安全持续线 = 额定的 75% (用户定, 非 50%): 负载率 <= 此值向环境回落, 高于则升温。 */
    public static final double SAFE_LINE = 0.75;

    /** 满载(负载率-安全线=0.25 时)每 tick 升温幅度基准 (°C)。速率 ∝ (负载率-0.75), 超得越多升越快。 */
    private static final double HEAT_RATE_C = 4.0;

    /** 冷却保留系数: 低于安全线时 T 向环境指数衰减 (每 tick 保留 98% 的高出量), 缓慢回升效率。 */
    private static final double COOL_RETENTION = 0.98;

    /** 网温硬上限 (°C), 防数值失控; 远超任何绝缘耐温, 此时效率已钳在 floor。 */
    private static final double MAX_TEMP_C = 400.0;

    /** 降效起始点 = 绝缘耐温的比例: 温度过此才开始软化降效 (低档 PVC 早, 高档硅橡胶晚)。 */
    private static final double DEGRADE_ONSET_FRAC = 0.7;

    private CableThermics() {
    }

    /**
     * 依本 settlement 实际负载推进网温一 tick。
     *
     * @param currentTempC       当前网温
     * @param loadFe             本 settlement 实际流过的 FE (送达用电端量)
     * @param ratedFe            网的额定吞吐帽 R (混级网木桶值)
     * @return 下一 tick 网温 (钳在 [AMBIENT_C, MAX_TEMP_C])
     */
    public static double advanceTemperature(double currentTempC, int loadFe, int ratedFe) {
        if (ratedFe <= 0) {
            return coolToward(currentTempC);
        }
        double loadRatio = (double) loadFe / ratedFe;
        double overload = loadRatio - SAFE_LINE;
        double next;
        if (overload > 0.0) {
            // 超线性: 升温速率正比于超出安全线的幅度。
            next = currentTempC + HEAT_RATE_C * (overload / (1.0 - SAFE_LINE));
        } else {
            next = coolToward(currentTempC);
        }
        return Math.max(AMBIENT_C, Math.min(next, MAX_TEMP_C));
    }

    private static double coolToward(double currentTempC) {
        return AMBIENT_C + (currentTempC - AMBIENT_C) * COOL_RETENTION;
    }

    /**
     * 网温 -> 有效效率 eff ∈ [floor, 1]。T <= 起始点: eff=1; T >= 绝缘耐温: eff=floor (钳死); 之间线性。
     * 有效吞吐 = ratedFe × eff。绝缘定"何时开始降", 导体温度系数定"最低降到哪 (floor)"。
     *
     * @param tempC               当前网温
     * @param insulationMaxTempC  网内最弱绝缘的耐温档 (混级木桶)
     * @param degradeFloor        网内最弱导体的降效 floor (混级木桶)
     */
    public static double efficiency(double tempC, int insulationMaxTempC, double degradeFloor) {
        double onset = insulationMaxTempC * DEGRADE_ONSET_FRAC;
        double full = insulationMaxTempC;
        if (tempC <= onset || full <= onset) {
            return 1.0;
        }
        if (tempC >= full) {
            return degradeFloor;
        }
        double t = (tempC - onset) / (full - onset);
        return 1.0 - t * (1.0 - degradeFloor);
    }
}
