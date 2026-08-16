package com.miningdim.champion.reward;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 贡献池奖励分配纯逻辑 (ChampionStarAffix spec 第十一章奖励与经济闸 + 第十四章实现拆分 8)。
 *
 * 盖章双门槛 (spec 第十一章 [红队]): 个人有效伤害 ≥ BOSS 总有效血 0.5% 或 ≥ 团队人均 15% (取一) 即合格;
 * 蹭枪玩家 (两门槛都不满足) 被排除。固定总池 (按星级与巨大化后实际有效血标定) → 仅合格者按个人有效伤害
 * 加权瓜分, 严禁按人头复制。离线没收 (online=false 不入账)。召唤物伤害已在贡献采集层排除 (见
 * {@link DamageContribution})。
 *
 * 本类是纯函数: 不碰 ServerPlayer / IEconomyService / 世界 —— 输出"每个合格玩家应得的原始信用点 (raw)",
 * 由 b 阶段逐玩家经 IEconomyService.grantDaily(player, raw, "credit_faucet", 60000) 并入信用点衰减主闸
 * (spec 第十一章: 战斗 faucet 必须并入每人每日统一衰减主闸, 不自开印钞口)。本类绝不直接发钱, 故 GameTest
 * 直接断言瓜分结果 (合格者集合 + 每人 raw 加权占比), 无世界依赖。
 *
 * 经济常量复用纪律 (任务红队核实): 入账主闸的 faucetKey/dailyCap/衰减底数是 economy 包
 * EconomyConstants/AbuseGuard 的真值 (credit_faucet / 60000 / 0.6 衰减), 本类不复制经济常量、不折算衰减 ——
 * 衰减是 grantDaily 的职责。本类只产出"瓜分后的 raw", 把 raw 喂给 grantDaily 即并入主闸。
 */
public final class ContributionPool {

    private ContributionPool() {
    }

    /** 盖章门槛一: 个人有效伤害 ≥ BOSS 总有效血的 0.5% (spec 第十一章)。 */
    public static final double STAMP_THRESHOLD_BOSS_HP_RATIO = 0.005D;

    /** 盖章门槛二: 个人有效伤害 ≥ 团队人均有效伤害的 15% (spec 第十一章; 取一即合格)。 */
    public static final double STAMP_THRESHOLD_TEAM_AVG_RATIO = 0.15D;

    /**
     * 判定某玩家是否通过盖章双门槛 (取一即合格, spec 第十一章): 离线直接没收; 否则个人有效伤害 ≥ BOSS 总有效血
     * 0.5% 或 ≥ 团队人均有效伤害 15% 即合格。
     *
     * @param contrib           该玩家贡献记录
     * @param bossTotalEffectiveHp BOSS 总有效血 (按星级与巨大化后实际有效血标定; 必须 &gt;0)
     * @param teamAverageEffectiveDamage 团队 (全部参战者, 含不合格者) 人均有效伤害 (必须 &gt;=0)
     * @return 是否合格入账
     */
    public static boolean isQualified(DamageContribution contrib,
                                      double bossTotalEffectiveHp,
                                      double teamAverageEffectiveDamage) {
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
        double dmg = contrib.effectiveDamage();
        boolean meetsBossThreshold = dmg >= bossTotalEffectiveHp * STAMP_THRESHOLD_BOSS_HP_RATIO;
        boolean meetsTeamThreshold = dmg >= teamAverageEffectiveDamage * STAMP_THRESHOLD_TEAM_AVG_RATIO;
        return meetsBossThreshold || meetsTeamThreshold;
    }

    /**
     * 团队人均有效伤害 = 全部参战者 (含不合格蹭枪者) 有效伤害之和 / 参战人数 (spec 第十一章门槛二分母口径)。
     * 参战者 = 有效伤害 &gt;0 的记录 (effectiveDamage=0 不算参战, 不进分母)。空列表返回 0。
     *
     * @param contributions 全部贡献记录
     * @return 团队人均有效伤害
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

    /**
     * 盖章 + 按个人有效伤害加权瓜分固定池 (spec 第十一章核心交付物): 先用双门槛筛出合格者, 再按合格者之间
     * 的有效伤害占比瓜分固定总池, 严禁按人头复制。
     *
     * 加权口径: 合格者 i 应得 raw = round(fixedPoolRaw × dmgᵢ / Σ(合格者 dmg)), 但逐笔钳制到"剩余预算"
     * (share = min(round(...), remaining)), remaining 随每笔发放递减; 末名直接吃 remaining 剩余全部
     * (F103 修复: 最大余数法的逐笔累计版, 而非只钳末名一处)。保证 Σ应得 恒等于 fixedPoolRaw (逐笔钳制,
     * 不会因前面 round 上偏累计而超池, 也不会因下钳漏发而少于池——末名兜底吃光剩余预算)。无合格者返回空
     * (整池不发, 防按人头复制)。
     *
     * @param contributions      全部贡献记录 (含蹭枪/离线者; 召唤物伤害已在采集层排除)
     * @param bossTotalEffectiveHp BOSS 总有效血 (盖章门槛一分母)
     * @param fixedPoolRaw       固定总池原始信用点 (按星级与巨大化后实际有效血标定; 必须 &gt;=0)
     * @return 合格者 UUID → 应得原始信用点 raw (b 阶段逐玩家喂 grantDaill 并入主闸); 保持稳定迭代序 (按输入序)
     */
    public static Map<UUID, Long> distribute(List<DamageContribution> contributions,
                                             double bossTotalEffectiveHp,
                                             long fixedPoolRaw) {
        if (contributions == null) {
            throw new IllegalArgumentException("contributions must not be null");
        }
        if (fixedPoolRaw < 0L) {
            throw new IllegalArgumentException("fixedPoolRaw must be >= 0, got " + fixedPoolRaw);
        }

        double teamAvg = teamAverageEffectiveDamage(contributions);

        List<DamageContribution> qualified = new ArrayList<>();
        double qualifiedDamageSum = 0.0D;
        for (DamageContribution c : contributions) {
            if (isQualified(c, bossTotalEffectiveHp, teamAvg)) {
                qualified.add(c);
                qualifiedDamageSum += c.effectiveDamage();
            }
        }

        Map<UUID, Long> payout = new LinkedHashMap<>();
        if (qualified.isEmpty() || qualifiedDamageSum <= 0.0D || fixedPoolRaw == 0L) {
            return payout; // 无合格者/无有效伤害/空池: 整池不发 (防按人头复制)。
        }

        long remaining = fixedPoolRaw;
        for (int i = 0; i < qualified.size(); i++) {
            DamageContribution c = qualified.get(i);
            long share;
            if (i == qualified.size() - 1) {
                // 末名吃光剩余预算 (吸收逐笔 round 的上/下偏差), 恒 >= 0 (remaining 只减不增, 起点 fixedPoolRaw >= 0)。
                share = remaining;
            } else {
                double weight = c.effectiveDamage() / qualifiedDamageSum;
                long raw = Math.round(fixedPoolRaw * weight);
                // 逐笔钳制到剩余预算 (F103): 防前面若干笔的 round 上偏累计推着后续份额把总池发穿。
                share = Math.min(raw, remaining);
                remaining -= share;
            }
            payout.put(c.playerId(), share);
        }
        return payout;
    }
}
