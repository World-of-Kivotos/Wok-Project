package com.miningdim.job.brewer;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;

/**
 * 喝酒效果结算 (酿酒师 第四节)。把强度 S = 年份 × 品质系数 ({@link WineNbt#strength}) 按酒类型映射为增益。
 *
 * "部分软上限" (设计文档第三节): 战斗类 (抗性/力量) 用收紧的软化曲线 + 低放大上限, 续航/工具/经济类放宽。年份
 * 本身不硬封 (续涨只增市场价), 但喝下去的强度经软化 + 各效果封顶, 杜绝几十年神酒破坏战斗平衡。
 *
 * 闪耀档的"永久 (一条命)"特殊增益在阶段 5 接入; 本阶段闪耀凭系数 5.0 自然给出更强的【临时】增益 (完整行为,
 * 非空壳)。月光为赌博: 加权随机好/坏, 强度越高越可能好。
 *
 * plan() 为纯函数 (月光的 rng 由调用方注入), 便于 GameTest 确定性断言; applyOnDrink 才有副作用。
 */
public final class BrewEffectEngine {

    private BrewEffectEngine() {
    }

    /** 月光好结果效果池 (随机抽一个, 按强度缩放)。 */
    public static final MobEffect[] MOONSHINE_GOOD_POOL = {
            MobEffects.DIG_SPEED, MobEffects.MOVEMENT_SPEED, MobEffects.DAMAGE_BOOST,
            MobEffects.REGENERATION, MobEffects.DAMAGE_RESISTANCE
    };

    /** 月光坏结果效果池 (等级 I 小惩罚, 均不致死 —— 死亡不掉落服上避免送命)。 */
    public static final MobEffect[] MOONSHINE_BAD_POOL = {
            MobEffects.POISON, MobEffects.CONFUSION, MobEffects.WEAKNESS,
            MobEffects.HUNGER, MobEffects.MOVEMENT_SLOWDOWN
    };

    /** 软化强度: S 超过 knee 后只按 diminish 折算 (软上限的数值核心)。tight=战斗类 (收紧)。 */
    public static double softened(double strength, boolean tight) {
        double knee = tight ? BrewerConstants.COMBAT_SOFTCAP_KNEE : BrewerConstants.LOOSE_SOFTCAP_KNEE;
        double diminish = tight ? BrewerConstants.COMBAT_SOFTCAP_DIMINISH : BrewerConstants.LOOSE_SOFTCAP_DIMINISH;
        if (strength <= knee) {
            return strength;
        }
        return knee + (strength - knee) * diminish;
    }

    /** 软化强度 -> 放大等级 (0-indexed), 钳到 [0, cap]。 */
    public static int amplifierFor(double softStrength, int cap) {
        int amp = (int) Math.floor(softStrength / BrewerConstants.AMP_PER_SOFT_STRENGTH);
        return Math.max(0, Math.min(cap, amp));
    }

    /** 软化强度 -> 持续时长 (tick), 钳到 [base, max]。 */
    public static int durationFor(double softStrength) {
        int dur = BrewerConstants.EFFECT_BASE_DURATION_TICKS
                + (int) (softStrength * BrewerConstants.EFFECT_DURATION_PER_SOFT);
        return Math.max(BrewerConstants.EFFECT_BASE_DURATION_TICKS,
                Math.min(BrewerConstants.EFFECT_MAX_DURATION_TICKS, dur));
    }

    /** 月光好结果概率 (强度越高越可能好, 钳到上限)。 */
    public static double moonshineGoodProb(double strength) {
        double p = BrewerConstants.MOONSHINE_GOOD_BASE_PROB
                + strength * BrewerConstants.MOONSHINE_GOOD_PROB_PER_STRENGTH;
        return Math.min(BrewerConstants.MOONSHINE_GOOD_PROB_MAX, Math.max(0.0D, p));
    }

    /** 一个按软化强度缩放的持续效果方案 (tight 决定软化曲线, cap 决定放大封顶)。 */
    private static BrewEffectPlan timed(MobEffect effect, double strength, boolean tight, int cap) {
        double soft = softened(strength, tight);
        int amp = amplifierFor(soft, cap);
        int dur = durationFor(soft);
        return BrewEffectPlan.ofEffect(new MobEffectInstance(effect, dur, amp));
    }

    /**
     * 算出一次喝酒的效果方案。strength<=0 (新酒) 返回 {@link BrewEffectPlan#EMPTY} ("新酒没味", 逼你陈酿)。
     *
     * @param type     酒类型 (决定增益种类)
     * @param strength 强度 S = 年份 × 品质系数
     * @param rng      仅月光赌博使用 (由调用方注入以便确定性测试); 其它类型不消耗 rng
     */
    public static BrewEffectPlan plan(WineType type, double strength, RandomSource rng) {
        if (strength <= 0.0D) {
            return BrewEffectPlan.EMPTY;
        }
        return switch (type) {
            // 续航/工具类 (放宽软上限): 急迫(挖矿酒) / 速度 / 金心 / 生命恢复。
            case BRANDY -> timed(MobEffects.DIG_SPEED, strength, false, BrewerConstants.AMP_CAP_LOOSE);
            case RUM -> timed(MobEffects.MOVEMENT_SPEED, strength, false, BrewerConstants.AMP_CAP_LOOSE);
            case GIN -> timed(MobEffects.ABSORPTION, strength, false, BrewerConstants.AMP_CAP_LOOSE);
            case CHAMPAGNE -> timed(MobEffects.REGENERATION, strength, false, BrewerConstants.AMP_CAP_LOOSE);
            // 战斗类 (收紧软上限 + 低放大封顶): 抗性 / 力量。
            case VODKA -> timed(MobEffects.DAMAGE_RESISTANCE, strength, true, BrewerConstants.AMP_CAP_COMBAT);
            case TEQUILA -> timed(MobEffects.DAMAGE_BOOST, strength, true, BrewerConstants.AMP_CAP_COMBAT);
            // 瞬时类。
            case WHISKEY -> BrewEffectPlan.ofHeal((float) (softened(strength, false) * BrewerConstants.WHISKEY_HEAL_PER_SOFT));
            case MAOTAI -> BrewEffectPlan.ofXp((int) Math.round(softened(strength, false) * BrewerConstants.MAOTAI_XP_PER_SOFT));
            // 赌博类。
            case MOONSHINE -> moonshine(strength, rng);
        };
    }

    /** 月光赌博: 加权随机好/坏。好结果从 GOOD_POOL 抽一个按强度缩放; 坏结果从 BAD_POOL 抽一个等级 I 小惩罚。 */
    private static BrewEffectPlan moonshine(double strength, RandomSource rng) {
        boolean good = rng.nextDouble() < moonshineGoodProb(strength);
        if (good) {
            MobEffect effect = MOONSHINE_GOOD_POOL[rng.nextInt(MOONSHINE_GOOD_POOL.length)];
            double soft = softened(strength, false);
            MobEffectInstance inst = new MobEffectInstance(effect, durationFor(soft),
                    amplifierFor(soft, BrewerConstants.AMP_CAP_LOOSE));
            return new BrewEffectPlan(java.util.List.of(inst), 0.0F, 0, "message.miningdim.brewer.moonshine.good");
        }
        MobEffect effect = MOONSHINE_BAD_POOL[rng.nextInt(MOONSHINE_BAD_POOL.length)];
        MobEffectInstance inst = new MobEffectInstance(effect, BrewerConstants.MOONSHINE_BAD_DURATION_TICKS, 0);
        return new BrewEffectPlan(java.util.List.of(inst), 0.0F, 0, "message.miningdim.brewer.moonshine.bad");
    }

    /** 服务端权威: 读 stack 的强度算方案并落到玩家。闪耀永久增益在阶段 5 于此追加分支。 */
    public static void applyOnDrink(ServerPlayer player, WineType type, ItemStack stack) {
        double strength = WineNbt.strength(stack);
        BrewEffectPlan plan = plan(type, strength, player.getRandom());
        for (MobEffectInstance effect : plan.effects()) {
            player.addEffect(new MobEffectInstance(effect));
        }
        if (plan.instantHeal() > 0.0F) {
            player.heal(plan.instantHeal());
        }
        if (plan.xp() > 0) {
            player.giveExperiencePoints(plan.xp());
        }
        if (plan.messageKey() != null) {
            player.displayClientMessage(Component.translatable(plan.messageKey()), true);
        }
    }
}
