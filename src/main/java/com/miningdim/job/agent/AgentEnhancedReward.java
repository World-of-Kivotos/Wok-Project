package com.miningdim.job.agent;

/**
 * 加强奖励纯逻辑 (SpecialAgent_Job_DesignSpec 7.1 加强奖励): 击杀精英怪本身额外给干员的个人信用点。
 *
 * 数值模型 (方案 D4): {@code 额外信用点 raw = floor(本人实际分到的信用点池份额 × 加成系数 × 等级倍率)}。
 *  - 本人池份额 = 贡献池 {@code ContributionPool.distribute} 给该玩家的 raw, 已按净伤占比加权 (蹭枪者份额近 0);
 *  - 加成系数 = miningdim-champion.toml 的 reward.agentBonusRate (默认 0.2);
 *  - 等级倍率 = 干员等级查 {@link AgentSkillTable#enhancedRewardMultiplier} (×1.0 L1 -> ×3.0 L10)。
 * 按池份额折算, 每只怪的加成总额上限恒为 系数 × 3.0 × 池, 与人数无关。
 *
 * 关键铁律:
 *  - 不含青辉石 (7.1: 与悬赏无关; 青辉石只从周常悬赏出)。本类只产出 CREDIT raw。
 *  - 个人 faucet 从池外给 (9 章: 不挤占他人贡献占比); raw 由集成层经
 *    {@code IEconomyService.grantDaily(player, raw, GLOBAL_DAILY_CREDIT_FAUCET_KEY, GLOBAL_DAILY_CREDIT_FAUCET_TIER)}
 *    并入全服每人每日信用点衰减主闸 (不自开印钞口)。本类不直接发钱、不折算衰减, 故 GameTest 直断言 raw。
 *  - 发不发由集成层先过两道门: 入职标志 (7.0) 与特勤占比门槛 ({@code ContributionPool.meetsAgentShareThreshold})。
 *
 * 全静态纯函数, 无世界引用, dev GameTest 触达安全。
 */
public final class AgentEnhancedReward {

    private AgentEnhancedReward() {
    }

    /**
     * floor 前的容差: 0.2 这类系数没有精确的二进制表示, 乘积可能落在整数下方一个 ulp 处, floor 会平白少 1。
     * 1e-9 远小于 1 信用点, 不会让本不该进位的小数进位。
     */
    private static final double FLOOR_EPSILON = 1e-9D;

    /**
     * 某干员按本人池份额拿到的加强奖励原始信用点 (raw, 不含青辉石)。
     *
     * @param agentLevel 干员等级 (内部经 clampLevel 夹 [1,10])
     * @param payoutRaw  该玩家本次从信用点池实际分到的 raw (&gt;=0)
     * @param bonusRate  加成系数 (&gt;=0, 有限)
     * @return 加强奖励 raw (&gt;=0)
     */
    public static long extraCreditRaw(int agentLevel, long payoutRaw, double bonusRate) {
        if (payoutRaw < 0L) {
            throw new IllegalArgumentException("payoutRaw must be >= 0, got " + payoutRaw);
        }
        if (!(bonusRate >= 0.0D) || Double.isInfinite(bonusRate)) {
            throw new IllegalArgumentException("bonusRate must be finite and >= 0, got " + bonusRate);
        }
        double multiplier = AgentSkillTable.enhancedRewardMultiplier(agentLevel);
        double raw = (double) payoutRaw * bonusRate * multiplier;
        return (long) Math.floor(raw + FLOOR_EPSILON);
    }
}
