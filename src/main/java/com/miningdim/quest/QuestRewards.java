package com.miningdim.quest;

import com.miningdim.economy.Currency;
import com.miningdim.economy.EconomyConstants;
import com.miningdim.economy.EconomyServices;
import net.minecraft.server.level.ServerPlayer;

/**
 * 任务系统与货币层之间的唯一接缝: 算奖励金额、发奖、收重摇费。
 *
 * 收成一个类而不是让 {@link QuestService} 直接调货币门面, 是为了让"任务这个 faucet 到底往全服注入了多少钱"
 * 只有一处可查、一处可改 —— 经济尚未做过全局净流入核对 (docs/Economy_Completeness_Audit.md), 将来要么改档位
 * 要么整体下调, 散落调用点会漏。
 *
 * <b>faucet 口径</b>: 发奖走 {@link EconomyConstants#QUEST_DAILY_CREDIT_FAUCET_KEY} 这个独立计数键 +
 * {@link QuestConfig#FAUCET_TIER} 这个正常游玩够不到的档位, 于是<b>实发恒等于名义值, 不过全服衰减主闸</b>
 * (用户决策: 任务是保底收入, 必须可预期)。
 *
 * 这是对"所有信用点 faucet 并入统一软上限"(经济文档 8.5) 的一次<b>有判据的例外</b>, 不是对它的推翻 —— 完整
 * 论证写在 {@link EconomyConstants#QUEST_DAILY_CREDIT_FAUCET_KEY} 的注释里, 一句话: 主闸封的是"产能无上限"的
 * 龙头, 而任务的产能由槽位数硬封, 玩家再肝也变不出第五条日常。
 *
 * <b>援引边界</b>: 后来人给任何新 faucet 开独立键之前, 先回答"它的供给是不是由槽位这类硬计数封死的"。答案不是
 * 斩钉截铁的"是", 就并回 {@link EconomyConstants#GLOBAL_DAILY_CREDIT_FAUCET_KEY} —— 仓库已因各算各的判过一次
 * Major。
 */
public final class QuestRewards {

    private QuestRewards() {
    }

    /**
     * 一条任务完成应发的原始信用点 (未过衰减主闸)。
     *
     * 公式 = 来源基数 x 难度档。难度只有 1-3 三档且线性乘, 不做指数曲线: 任务奖励的区分度应该来自"做不做得完",
     * 而不是让 3 档任务变成唯一值得做的任务。
     */
    public static long creditFor(QuestDefinition definition) {
        long base = switch (definition.source()) {
            case DAILY -> QuestConfig.DAILY_REWARD_BASE.get();
            case WEEKLY -> QuestConfig.WEEKLY_REWARD_BASE.get();
            case SPECIAL -> QuestConfig.SPECIAL_REWARD_BASE.get();
            case HIDDEN -> QuestConfig.HIDDEN_REWARD_BASE.get();
        };
        return base * definition.difficulty();
    }

    /**
     * 重摇一个任务槽的信用点开销 (真 sink, 直接销毁不转移)。
     *
     * 非可重摇来源调用即装配缺陷, 直接抛 —— 返回 0 会让"特殊任务免费重摇"这种越权行为静默通过。
     */
    public static long refreshCost(QuestSource source) {
        return switch (source) {
            case DAILY -> QuestConfig.DAILY_REFRESH_COST.get();
            case WEEKLY -> QuestConfig.WEEKLY_REFRESH_COST.get();
            case SPECIAL, HIDDEN -> throw new UnsupportedOperationException(
                    "quest source " + source + " is not refreshable and has no refresh cost");
        };
    }

    /**
     * 发放一条任务的完成奖励。
     *
     * 仍然走 {@code grantDaily} 而不是裸调 {@code grant}: 前者顺带把这笔钱记进 (玩家, quest_faucet) 的当日累计
     * 计数器, 运营查得到任务这条龙头注入了多少; 后者两样都没有。默认档位高到正常游玩够不到, 故衰减系数恒为 1,
     * 实发 == 名义值。
     *
     * @return 实际入账的信用点。默认配置下恒等于 {@link #creditFor}; 运营把 {@code quest.faucetTier} 调到玩家
     *         真能撞上的量级时才会低于它 (那正是重新给任务上闸的手段)。配置把该来源基数调为 0 时返回 0 且不
     *         触碰货币层
     */
    public static long payout(ServerPlayer player, QuestDefinition definition) {
        long raw = creditFor(definition);
        if (raw <= 0) {
            // 基数被配置调成 0 = 运营方主动关掉该来源的奖励; grantDaily 对 amount<=0 会抛 ILLEGAL_AMOUNT,
            // 故在此短路而不是把一个合法的运营配置变成异常。
            return 0;
        }
        return EconomyServices.economyService().grantDaily(player, raw,
                EconomyConstants.QUEST_DAILY_CREDIT_FAUCET_KEY,
                QuestConfig.FAUCET_TIER.get());
    }

    /**
     * 扣重摇费。
     *
     * @return true = 已扣费 (或该来源重摇免费), 调用方可以继续重摇; false = 余额不足, 不得重摇
     */
    public static boolean chargeRefresh(ServerPlayer player, QuestSource source) {
        long cost = refreshCost(source);
        if (cost <= 0) {
            // 配置成免费; tryCharge 对 amount<=0 抛 ILLEGAL_AMOUNT, 故短路放行而不是让合法配置炸掉。
            return true;
        }
        return EconomyServices.economyService().tryCharge(player, Currency.CREDIT, cost);
    }
}
