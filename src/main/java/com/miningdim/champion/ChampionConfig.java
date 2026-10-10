package com.miningdim.champion;

import com.miningdim.champion.reward.ChampionReward;
import com.miningdim.champion.reward.ContributionPool;
import net.minecraftforge.common.ForgeConfigSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 精英怪子系统服务端配置 (miningdim-champion.toml, SERVER 类型, 由 {@link ChampionSystem#register} 经标准
 * {@code ModLoadingContext.registerConfig} 注册)。一个 toml 文件只能挂一个 SPEC, 故本类是该文件的唯一 SPEC
 * 持有者; 精英怪其它段的旋钮要进这个文件时在这里追加 push/pop 段, 不要另建第二个 SPEC 指向同名文件。
 *
 * 当前只有 [reward] 段 (奖励与特勤加成的调参旋钮, 方案 D3-D6), 默认值全部取自纯逻辑层常量
 * ({@link ChampionReward} / {@link ContributionPool}), 两边不各写一份数。业务侧一律经本类的访问器实时读, 不缓存;
 * 配置未加载 (启动早期/纯逻辑单测进程) 时访问器回落默认值, 不抛。
 *
 * GameTest 纪律: 用例不得 {@code set} 本类任何值 (Forge 会自写自重载撞 FileWatcher, 见分支协作.md 第四节);
 * 需要非默认参数的断言一律直调纯逻辑层带显式参数的重载。
 */
public final class ChampionConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/champion");

    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.ConfigValue<List<? extends Number>> CREDIT_POOL_BY_STAR;
    public static final ForgeConfigSpec.DoubleValue CREDIT_POOL_SCALE;
    public static final ForgeConfigSpec.IntValue CONTRIBUTION_RECENCY_TICKS;
    public static final ForgeConfigSpec.DoubleValue AGENT_BONUS_RATE;
    public static final ForgeConfigSpec.DoubleValue AGENT_SHARE_FLOOR;
    public static final ForgeConfigSpec.DoubleValue AGENT_SHARE_CEIL;
    public static final ForgeConfigSpec.DoubleValue AGENT_SHARE_FAIR_FRACTION;

    private ChampionConfig() {
    }

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.push("reward");
        CREDIT_POOL_BY_STAR = builder.comment(
                        "Fixed CREDIT pool (raw, before the daily decay gate) for killing a champion of each initial "
                                + "star, 1-star first. Exactly 10 non-negative entries; any other length falls back "
                                + "to the built-in table. 1-5 star stay at 600 x star. 6-10 star are calibrated so a "
                                + "4-player squad with legendary M4A1s earns back about 0.9 of the median ammo + "
                                + "gunsmith wear cost of the kill (reward plan D6, +-1% Monte Carlo noise). The 6-10 "
                                + "star values assume the HE explosion cap (plan C1/C2) and the champion star table "
                                + "and damage fixes (plan B1/A2) are live; until then use "
                                + "[600,1200,1800,2400,3000,3600,4200,4800,5400,6000]")
                .defineList("creditPoolByStar", new ArrayList<>(ChampionReward.DEFAULT_CREDIT_POOL_BY_STAR),
                        o -> o instanceof Number n && n.longValue() >= 0L);
        CREDIT_POOL_SCALE = builder.comment(
                        "Multiplier applied to every creditPoolByStar entry (rounded to a whole CREDIT). Use this to "
                                + "retune all pools at once after a combat balance change instead of editing 10 numbers")
                .defineInRange("creditPoolScale", ChampionReward.DEFAULT_CREDIT_POOL_SCALE, 0.0D, 10.0D);
        CONTRIBUTION_RECENCY_TICKS = builder.comment(
                        "A contributor only qualifies for a share if their last damaging hit landed within this "
                                + "many ticks before the kill (6000 = 5 minutes). Forfeited shares are NOT paid to "
                                + "anyone else. 0 disables the recency check (online check still applies)")
                .defineInRange("contributionRecencyTicks", (int) ContributionPool.DEFAULT_RECENCY_TICKS, 0, 72_000);
        AGENT_BONUS_RATE = builder.comment(
                        "Special agent enhanced reward = the agent's own credit pool payout x this rate x the agent "
                                + "level multiplier (1.0 at L1 .. 3.0 at L10). Paid through the same daily decay gate")
                .defineInRange("agentBonusRate", ChampionReward.DEFAULT_AGENT_BONUS_RATE, 0.0D, 1.0D);
        AGENT_SHARE_FLOOR = builder.comment(
                        "Lower clamp of the agent bonus share threshold. The threshold is "
                                + "clamp(agentShareFairFraction / N, floor, ceil) where N counts contributors holding "
                                + ">= 1% of the net damage (offline ones included). A solo kill always passes")
                .defineInRange("agentShareFloor", ContributionPool.DEFAULT_AGENT_SHARE_FLOOR, 0.0D, 1.0D);
        AGENT_SHARE_CEIL = builder.comment(
                        "Upper clamp of the agent bonus share threshold; if set below agentShareFloor the floor wins")
                .defineInRange("agentShareCeil", ContributionPool.DEFAULT_AGENT_SHARE_CEIL, 0.0D, 1.0D);
        AGENT_SHARE_FAIR_FRACTION = builder.comment(
                        "Fraction of the fair per-head share an agent must reach (0.5 = half of 1/N)")
                .defineInRange("agentShareFairFraction", ContributionPool.DEFAULT_AGENT_SHARE_FAIR_FRACTION, 0.0D, 1.0D);
        builder.pop();

        SPEC = builder.build();
    }

    /**
     * 各星信用点固定池表 (10 项, 下标 0 = 1★)。条目数不为 10 时回落内置表并打 warn —— 列表逐元素校验器管不到
     * 长度, 而少一项会让高星查表越界, 多一项又说明服主抄错了行, 两种都不能静默吞。
     */
    public static List<Long> creditPoolByStar() {
        if (!SPEC.isLoaded()) {
            return ChampionReward.DEFAULT_CREDIT_POOL_BY_STAR;
        }
        List<? extends Number> raw = CREDIT_POOL_BY_STAR.get();
        if (raw.size() != StarRank.MAX_STAR) {
            LOGGER.warn("[champion] reward.creditPoolByStar has {} entries, expected {}; using built-in table",
                    raw.size(), StarRank.MAX_STAR);
            return ChampionReward.DEFAULT_CREDIT_POOL_BY_STAR;
        }
        List<Long> out = new ArrayList<>(raw.size());
        for (Number n : raw) {
            out.add(n.longValue());
        }
        return out;
    }

    public static double creditPoolScale() {
        return SPEC.isLoaded() ? CREDIT_POOL_SCALE.get() : ChampionReward.DEFAULT_CREDIT_POOL_SCALE;
    }

    public static long contributionRecencyTicks() {
        return SPEC.isLoaded() ? CONTRIBUTION_RECENCY_TICKS.get() : ContributionPool.DEFAULT_RECENCY_TICKS;
    }

    public static double agentBonusRate() {
        return SPEC.isLoaded() ? AGENT_BONUS_RATE.get() : ChampionReward.DEFAULT_AGENT_BONUS_RATE;
    }

    public static double agentShareFloor() {
        return SPEC.isLoaded() ? AGENT_SHARE_FLOOR.get() : ContributionPool.DEFAULT_AGENT_SHARE_FLOOR;
    }

    public static double agentShareCeil() {
        return SPEC.isLoaded() ? AGENT_SHARE_CEIL.get() : ContributionPool.DEFAULT_AGENT_SHARE_CEIL;
    }

    public static double agentShareFairFraction() {
        return SPEC.isLoaded() ? AGENT_SHARE_FAIR_FRACTION.get() : ContributionPool.DEFAULT_AGENT_SHARE_FAIR_FRACTION;
    }
}
