package com.miningdim.job.agent;

import com.miningdim.champion.StarRank;

/**
 * 击杀精英干员经验纯逻辑 (SpecialAgent_Job_DesignSpec 8.1 经验 faucet): 达入池门槛的合格击杀给干员的原始经验。
 *
 * 数值模型 (8.1 DECIDED): {@code 原始经验 raw = 初始星级 × 每星基数(60) × 贡献占比}。
 *  - 初始星级是出生盖章的星级 (3 章: 即使被封印削弱也不变, 与贡献池池大小同口径);
 *  - 贡献占比 = 本人净伤占全部参战者净伤的比例 (离线/过期/不合格者的份额作废不回池, 故有队友离线或过期时合格者
 *    的占比之和 &lt;1; 由集成层按 payout / creditPoolRaw 反推, 不重算);
 *  - 每星基数 60 锚定 1-5★ 信用点池的每星基数 {@link com.miningdim.champion.reward.ChampionReward#CREDIT_POOL_PER_STAR}
 *    (600) 的 1/10。6-10★ 信用点池已改查表 (方案 D6, 10★ 45,000), 经验基数按方案 D4 刻意保持 60×星不变, 故高星
 *    段经验与信用点不再同尺度: 经验是职业曲线, 不随战斗成本标定。
 *
 * 关键铁律:
 *  - 与信用点同口径 (占比一致) 但走【经验软上限】而非信用点衰减主闸: raw 由集成层经
 *    {@code AgentLevels.grantRawXp(player, raw)} -> IJobService.grantXp 并入职业框架统一经验软上限/翻日/升级,
 *    不碰信用点闸。本类不直接入账、不折算衰减 (衰减是 grantXp 职责), 故 GameTest 直断言 raw, 无世界依赖。
 *  - 仅合格击杀 (达贡献池入池门槛) 才发: 不合格 (蹭枪/封了没打) 的 share 为 0 -> raw 为 0 -> 不入账。
 *
 * 底值数值口径 (8.1 标 PENDING: 绝对底值留经济 config): 本类 {@link #XP_BASE_PER_STAR} 为 config 暴露前的
 * 唯一权威硬值。一旦 ConfigSystem 暴露 agent.xp.* 应改读 miningdim-agent.toml (留待接线)。
 *
 * 全静态纯函数, 无世界引用, dev GameTest 触达安全。
 */
public final class AgentKillXp {

    private AgentKillXp() {
    }

    /**
     * 经验 faucet 每初始星级基数 (raw, 乘贡献占比前): 60/星 = 1-5★ 信用点池每星基数 (600) 的 1/10; 6-10★ 不随信用点
     * 池表放大 (见类注释)。
     * raw = round(star × XP_BASE_PER_STAR × 贡献占比)。
     */
    public static final long XP_BASE_PER_STAR = 60L;

    /**
     * 某合格击杀给干员的原始经验 (raw): {@code round(star × XP_BASE_PER_STAR × contributionShare)} (8.1 公式)。
     *
     * @param star              精英初始星级 (1-10; 越界抛 IllegalArgumentException 自然冒泡)
     * @param contributionShare 本人净伤占全部参战者净伤的比例 (离线/过期/不合格者份额作废不回池; 由集成层按
     *                          payout / creditPoolRaw 反推; [0,1], 越界抛 IllegalArgumentException)
     * @return 原始经验 raw (&gt;=0; 由集成层喂 grantRawXp 并入经验软上限)
     */
    public static long killXpRaw(int star, double contributionShare) {
        requireStar(star);
        if (contributionShare < 0.0D || contributionShare > 1.0D) {
            throw new IllegalArgumentException("contributionShare out of [0,1]: " + contributionShare);
        }
        double raw = (double) star * XP_BASE_PER_STAR * contributionShare;
        return Math.round(raw);
    }

    private static void requireStar(int star) {
        if (star < StarRank.MIN_STAR || star > StarRank.MAX_STAR) {
            throw new IllegalArgumentException("star out of [1,10]: " + star);
        }
    }
}
