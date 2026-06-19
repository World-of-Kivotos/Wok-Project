package com.miningdim.job.agent;

import com.miningdim.champion.StarRank;
import com.miningdim.champion.reward.ChampionReward;

/**
 * 加强奖励纯逻辑 (SpecialAgent_Job_DesignSpec 7.1 加强奖励): 击杀精英怪本身额外给干员的个人信用点。
 *
 * 数值模型 (7.1 DECIDED): {@code 额外信用点 raw = f(初始星级) × 加强奖励系数(×1.0->×3.0)}。
 *  - f(初始星级) = 按初始星级线性抬升的底值 (低星点缀, 高星更多);
 *  - 加强奖励系数 = 干员等级查 {@link AgentSkillTable#enhancedRewardMultiplier} (×1.0 L1 -> ×3.0 L10)。
 *
 * 关键铁律:
 *  - 不含青辉石 (7.1: 与悬赏无关, 不含青辉石; 青辉石只从周常悬赏出)。本类只产出 CREDIT raw。
 *  - 个人 faucet 从池外给 (9 章: 不挤占他人贡献占比); raw 由集成层经
 *    {@code IEconomyService.grantDaily(player, raw, GLOBAL_DAILY_CREDIT_FAUCET_KEY, GLOBAL_DAILY_CREDIT_FAUCET_TIER)}
 *    并入全服每人每日信用点衰减主闸 (不自开印钞口)。本类不直接发钱、不折算衰减 (衰减是 grantDaily 职责),
 *    故 GameTest 直断言 raw, 无世界依赖。
 *  - 初始星级是出生盖章的星级 (3 章: 即使被封印削弱也不变, 与贡献池池大小同口径)。
 *
 * 底值数值口径 (7.1/十二章标 PENDING: 绝对底值留经济 config): 本类 {@link #CREDIT_BASE_PER_STAR} 为
 * config 暴露前的唯一权威硬值, 复用精英怪固定池每星基数 {@link com.miningdim.champion.reward.ChampionReward#CREDIT_POOL_PER_STAR}
 * 的同源量级 (每星 +600 信用点底值), 保持加强奖励与贡献池池大小同尺度。一旦 ConfigSystem 暴露 agent.reward.*
 * 应改读 miningdim-agent.toml (留待接线)。
 *
 * 全静态纯函数, 无世界引用, dev GameTest 触达安全。
 */
public final class AgentEnhancedReward {

    private AgentEnhancedReward() {
    }

    /**
     * 加强奖励底值: 每初始星级的基础额外信用点 (raw, 乘倍率前)。
     *
     * 复用 {@link com.miningdim.champion.reward.ChampionReward#CREDIT_POOL_PER_STAR} (600/星) 的同源量级:
     * 加强奖励是"击杀精英的个人 faucet", 与贡献池"按伤害瓜分的团队池"同尺度, 故底值同每星 600。
     * f(初始星级) = star × CREDIT_BASE_PER_STAR (1★=600 ... 10★=6000), 再乘等级倍率 (×1.0->×3.0)。
     */
    public static final long CREDIT_BASE_PER_STAR = ChampionReward.CREDIT_POOL_PER_STAR;

    /**
     * 某干员等级击杀某初始星级精英的加强奖励原始信用点 (raw, 乘倍率后, floor 到整数; 不含青辉石)。
     *
     * 公式: raw = floor(star × {@link #CREDIT_BASE_PER_STAR} × {@link AgentSkillTable#enhancedRewardMultiplier}(level))。
     * 该 raw 由集成层逐玩家喂 grantDaily 并入信用点衰减主闸 (本类不直接发钱、不折算衰减)。
     *
     * @param agentLevel 干员等级 (内部经 clampLevel 夹 [1,10]; 越低倍率越小)
     * @param star       精英初始星级 (1-10; 越界抛 IllegalArgumentException 自然冒泡)
     * @return 加强奖励原始信用点 (>=0; 全 CREDIT, 0 azure)
     */
    public static long extraCreditRaw(int agentLevel, int star) {
        requireStar(star);
        double multiplier = AgentSkillTable.enhancedRewardMultiplier(agentLevel);
        double raw = (double) star * CREDIT_BASE_PER_STAR * multiplier;
        return (long) Math.floor(raw);
    }

    private static void requireStar(int star) {
        if (star < StarRank.MIN_STAR || star > StarRank.MAX_STAR) {
            throw new IllegalArgumentException("star out of [1,10]: " + star);
        }
    }
}
