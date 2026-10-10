package com.miningdim.champion.reward;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 贡献池奖励分配纯逻辑 (ChampionStarAffix spec 第十一章奖励与经济闸 + 第十四章实现拆分 8; 方案 D3/D5)。
 *
 * 伤害口径一律是净伤 ({@link DamageContribution#effectiveDamage()}: 实际扣掉的血), 门槛、份额、特勤门槛都只看它。
 *
 * 结算资格: 在线 + 近期命中 (结算时刻距最近命中 ≤ recencyTicks, 0 = 不查) + 盖章双门槛取一 (个人净伤 ≥ BOSS
 * 总有效血 0.5% 或 ≥ 团队人均 15%)。蹭枪者两道门槛都过不去; 离开战场超过近期窗口的人与离线者份额作废。
 *
 * 分配 (方案 D3): 分母是【全部】净伤 &gt;0 的参战者之和 (含离线、过期、不合格者), 合格者总预算 =
 * round(池 × Σ合格净伤 / Σ全部净伤), 离线/过期/不合格者的份额作废、不回池重分。
 * 预算内按最大余数法取整: 先按 floor 分, 余数从大到小各补 1, 平手按净伤降序、再按 UUID 升序。严禁按人头复制。
 *
 * 本类是纯函数: 不碰 ServerPlayer / IEconomyService / 世界 —— 输出"每个合格玩家应得的原始信用点 (raw)", 由
 * 集成层逐玩家经 grantDaily(player, raw, "credit_faucet", 60000) 并入信用点衰减主闸。本类不复制经济常量、不折算
 * 衰减 (那是 grantDaily 的职责)。
 */
public final class ContributionPool {

    private ContributionPool() {
    }

    /** 盖章门槛一: 个人净伤 ≥ BOSS 总有效血的 0.5% (spec 第十一章)。 */
    public static final double STAMP_THRESHOLD_BOSS_HP_RATIO = 0.005D;

    /** 盖章门槛二: 个人净伤 ≥ 团队人均净伤的 15% (spec 第十一章; 取一即合格)。 */
    public static final double STAMP_THRESHOLD_TEAM_AVG_RATIO = 0.15D;

    /** 近期门槛默认值 (reward.contributionRecencyTicks): 最近命中须在结算前 6000 tick (5 分钟) 内。 */
    public static final long DEFAULT_RECENCY_TICKS = 6_000L;

    /** 特勤门槛的人数口径: 净伤占比 ≥1% 的参战者才计入 N (含离线者, 防靠掉线抬门槛)。 */
    public static final double AGENT_PARTICIPANT_MIN_SHARE = 0.01D;

    /** 特勤门槛下限默认值 (reward.agentShareFloor)。 */
    public static final double DEFAULT_AGENT_SHARE_FLOOR = 0.05D;

    /** 特勤门槛上限默认值 (reward.agentShareCeil)。 */
    public static final double DEFAULT_AGENT_SHARE_CEIL = 0.25D;

    /** 特勤门槛占人均份额的比例默认值 (reward.agentShareFairFraction; 0.5 = 人均份额的一半)。 */
    public static final double DEFAULT_AGENT_SHARE_FAIR_FRACTION = 0.5D;

    /** 不查近期门槛的盖章判定 (等价 recencyTicks = 0)。 */
    public static boolean isQualified(DamageContribution contrib,
                                      double bossTotalEffectiveHp,
                                      double teamAverageEffectiveDamage) {
        return isQualified(contrib, bossTotalEffectiveHp, teamAverageEffectiveDamage, 0L, 0L);
    }

    /**
     * 判定某玩家是否具备结算资格: 离线没收; 最近命中超出近期窗口没收; 否则个人净伤 ≥ BOSS 总有效血 0.5% 或
     * ≥ 团队人均净伤 15% 即合格。
     *
     * @param contrib                    该玩家贡献记录
     * @param bossTotalEffectiveHp       BOSS 总有效血 (必须 &gt;0)
     * @param teamAverageEffectiveDamage 团队 (全部参战者, 含不合格者) 人均净伤 (必须 &gt;=0)
     * @param nowTick                    结算时刻 gameTime
     * @param recencyTicks               近期窗口 (&lt;=0 表示不查)
     * @return 是否合格入账
     */
    public static boolean isQualified(DamageContribution contrib,
                                      double bossTotalEffectiveHp,
                                      double teamAverageEffectiveDamage,
                                      long nowTick,
                                      long recencyTicks) {
        if (bossTotalEffectiveHp <= 0.0D) {
            throw new IllegalArgumentException("bossTotalEffectiveHp must be > 0, got " + bossTotalEffectiveHp);
        }
        if (teamAverageEffectiveDamage < 0.0D) {
            throw new IllegalArgumentException(
                    "teamAverageEffectiveDamage must be >= 0, got " + teamAverageEffectiveDamage);
        }
        if (!contrib.online()) {
            return false; // 离线没收。
        }
        if (!isRecent(contrib, nowTick, recencyTicks)) {
            return false; // 离开战场超出近期窗口: 没收。
        }
        double dmg = contrib.effectiveDamage();
        boolean meetsBossThreshold = dmg >= bossTotalEffectiveHp * STAMP_THRESHOLD_BOSS_HP_RATIO;
        boolean meetsTeamThreshold = dmg >= teamAverageEffectiveDamage * STAMP_THRESHOLD_TEAM_AVG_RATIO;
        return meetsBossThreshold || meetsTeamThreshold;
    }

    /** 最近命中是否仍在近期窗口内 (recencyTicks &lt;=0 视为不查, 恒 true)。 */
    public static boolean isRecent(DamageContribution contrib, long nowTick, long recencyTicks) {
        if (recencyTicks <= 0L) {
            return true;
        }
        return nowTick - contrib.lastHitTick() <= recencyTicks;
    }

    /**
     * 团队人均净伤 = 全部参战者 (含不合格蹭枪者) 净伤之和 / 参战人数 (spec 第十一章门槛二分母口径)。
     * 参战者 = 净伤 &gt;0 的记录。空列表返回 0。
     */
    public static double teamAverageEffectiveDamage(List<DamageContribution> contributions) {
        if (contributions == null) {
            throw new IllegalArgumentException("contributions must not be null");
        }
        double sum = 0.0D;
        int participants = 0;
        for (DamageContribution c : contributions) {
            if (c.effectiveDamage() > 0.0D) {
                sum += c.effectiveDamage();
                participants++;
            }
        }
        if (participants == 0) {
            return 0.0D;
        }
        return sum / participants;
    }

    /** 全部参战者 (净伤 &gt;0) 的净伤之和 = 份额分母。 */
    public static double totalEffectiveDamage(List<DamageContribution> contributions) {
        if (contributions == null) {
            throw new IllegalArgumentException("contributions must not be null");
        }
        double sum = 0.0D;
        for (DamageContribution c : contributions) {
            if (c.effectiveDamage() > 0.0D) {
                sum += c.effectiveDamage();
            }
        }
        return sum;
    }

    /** 不查近期门槛的瓜分 (等价 recencyTicks = 0)。 */
    public static Map<UUID, Long> distribute(List<DamageContribution> contributions,
                                             double bossTotalEffectiveHp,
                                             long fixedPoolRaw) {
        return distribute(contributions, bossTotalEffectiveHp, fixedPoolRaw, 0L, 0L);
    }

    /**
     * 盖章 + 按净伤占比瓜分固定池 (方案 D3)。
     *
     * 合格者 i 的配额 = 预算 × dmgᵢ / Σ合格 dmg, 其中预算 = round(池 × Σ合格 dmg / Σ全部 dmg) —— 等价于每人拿
     * 池 × 本人占全部净伤的比例, 作废者那部分留在池里不发。预算内按最大余数法取整, 保证 Σ应得 恒等于预算、每人
     * 与精确配额相差不足 1。无合格者 / 空池返回空表 (整池不发)。
     *
     * @param contributions        全部贡献记录 (含蹭枪/离线/过期者)
     * @param bossTotalEffectiveHp BOSS 总有效血 (盖章门槛一分母)
     * @param fixedPoolRaw         固定总池 (必须 &gt;=0)
     * @param nowTick              结算时刻 gameTime
     * @param recencyTicks         近期窗口 (&lt;=0 表示不查)
     * @return 合格者 UUID → 应得 raw (可为 0; 迭代序 = 输入序)
     */
    public static Map<UUID, Long> distribute(List<DamageContribution> contributions,
                                             double bossTotalEffectiveHp,
                                             long fixedPoolRaw,
                                             long nowTick,
                                             long recencyTicks) {
        if (contributions == null) {
            throw new IllegalArgumentException("contributions must not be null");
        }
        if (fixedPoolRaw < 0L) {
            throw new IllegalArgumentException("fixedPoolRaw must be >= 0, got " + fixedPoolRaw);
        }

        double teamAvg = teamAverageEffectiveDamage(contributions);
        double totalDamage = totalEffectiveDamage(contributions);

        List<DamageContribution> qualified = new ArrayList<>();
        double qualifiedDamageSum = 0.0D;
        for (DamageContribution c : contributions) {
            if (c.effectiveDamage() > 0.0D
                    && isQualified(c, bossTotalEffectiveHp, teamAvg, nowTick, recencyTicks)) {
                qualified.add(c);
                qualifiedDamageSum += c.effectiveDamage();
            }
        }

        Map<UUID, Long> payout = new LinkedHashMap<>();
        if (qualified.isEmpty() || qualifiedDamageSum <= 0.0D || totalDamage <= 0.0D || fixedPoolRaw == 0L) {
            return payout; // 无合格者/无净伤/空池: 整池不发。
        }

        // 全员合格时两个和按同一顺序累加同一批数, 比值恰为 1.0, 预算即整池。
        long budget = Math.round(fixedPoolRaw * (qualifiedDamageSum / totalDamage));
        long[] shares = largestRemainder(qualified, qualifiedDamageSum, budget);
        for (int i = 0; i < qualified.size(); i++) {
            payout.put(qualified.get(i).playerId(), shares[i]);
        }
        return payout;
    }

    /**
     * 最大余数法: 预算按净伤比例拆成配额, 先各取 floor, 剩余名额按小数部分从大到小各补 1; 小数部分相同则净伤高者
     * 优先, 再同则 UUID 小者优先 (全序, 结果与输入序无关)。
     */
    private static long[] largestRemainder(List<DamageContribution> qualified, double damageSum, long budget) {
        int n = qualified.size();
        long[] shares = new long[n];
        double[] fractions = new double[n];
        long assigned = 0L;
        for (int i = 0; i < n; i++) {
            double quota = budget * (qualified.get(i).effectiveDamage() / damageSum);
            long whole = (long) Math.floor(quota);
            shares[i] = whole;
            fractions[i] = quota - whole;
            assigned += whole;
        }
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> {
            int byFraction = Double.compare(fractions[b], fractions[a]);
            if (byFraction != 0) {
                return byFraction;
            }
            int byDamage = Double.compare(qualified.get(b).effectiveDamage(), qualified.get(a).effectiveDamage());
            if (byDamage != 0) {
                return byDamage;
            }
            return qualified.get(a).playerId().compareTo(qualified.get(b).playerId());
        });
        // 数学上 0 ≤ 剩余名额 < n; 按序循环补足只是防浮点边角, 正常一轮即止。
        long leftover = budget - assigned;
        for (int k = 0; leftover > 0L; k = (k + 1) % n) {
            shares[order[k]]++;
            leftover--;
        }
        return shares;
    }

    // ============================================================
    // 特勤加成门槛 (方案 D5)
    // ============================================================

    /** 某玩家净伤占全部参战者净伤的比例 (无参战者或本人不在表内返 0)。 */
    public static double effectiveShare(UUID playerId, List<DamageContribution> contributions) {
        double total = totalEffectiveDamage(contributions);
        if (total <= 0.0D || playerId == null) {
            return 0.0D;
        }
        for (DamageContribution c : contributions) {
            if (c.playerId().equals(playerId)) {
                return c.effectiveDamage() / total;
            }
        }
        return 0.0D;
    }

    /** 特勤门槛的人数 N: 净伤占比 ≥ {@link #AGENT_PARTICIPANT_MIN_SHARE} 的参战者数 (含离线/过期者)。 */
    public static int agentParticipantCount(List<DamageContribution> contributions) {
        double total = totalEffectiveDamage(contributions);
        if (total <= 0.0D) {
            return 0;
        }
        int n = 0;
        for (DamageContribution c : contributions) {
            if (c.effectiveDamage() > 0.0D && c.effectiveDamage() / total >= AGENT_PARTICIPANT_MIN_SHARE) {
                n++;
            }
        }
        return n;
    }

    /**
     * 特勤加成的占比门槛 T = clamp(fairFraction / N, floor, ceil) (N &lt;1 按 1 算; floor &gt; ceil 时 floor 为准)。
     * 默认参数下 4 人 12.5%、5 人 10%、8 人 6.25%、10 人及以上 5%; 小号灌人数最多把门槛压到 floor。
     */
    public static double agentShareThreshold(int participantCount, double floor, double ceil, double fairFraction) {
        requireUnitInterval(floor, "floor");
        requireUnitInterval(ceil, "ceil");
        requireUnitInterval(fairFraction, "fairFraction");
        int n = Math.max(1, participantCount);
        return Math.max(floor, Math.min(ceil, fairFraction / n));
    }

    /**
     * 某玩家是否达到特勤加成门槛: 本人净伤占比 ≥ T。单人击杀占比恒为 1, 恒满足。这道门只挡"按人头领加成"的
     * 微量蹭枪, 加成数额本身已按本人池份额折算 (见 AgentEnhancedReward)。
     */
    public static boolean meetsAgentShareThreshold(UUID playerId, List<DamageContribution> contributions,
                                                   double floor, double ceil, double fairFraction) {
        double share = effectiveShare(playerId, contributions);
        if (share <= 0.0D) {
            return false;
        }
        return share >= agentShareThreshold(agentParticipantCount(contributions), floor, ceil, fairFraction);
    }

    private static void requireUnitInterval(double v, String name) {
        if (!(v >= 0.0D && v <= 1.0D)) {
            throw new IllegalArgumentException(name + " must be in [0,1], got " + v);
        }
    }
}
