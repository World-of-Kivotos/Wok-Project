package com.miningdim.job.brewer;

import net.minecraft.world.effect.MobEffectInstance;

import java.util.List;

/**
 * 一次喝酒的效果方案 (纯数据 DTO)。由 {@link BrewEffectEngine#plan} 按酒类型 + 强度 (+ 月光 rng) 算出, 再由
 * {@link BrewEffectEngine#applyOnDrink} 落到玩家身上。做成纯数据是为 GameTest 可直接断言具体数值 (放大等级 /
 * 时长 / 瞬恢 / 经验 / 月光好坏), 无需起世界。
 *
 * @param effects     要施加的持续效果实例 (可空; 可含 ABSORPTION/REGENERATION 等)
 * @param instantHeal 瞬间恢复的生命 (半心=1.0; 0 表示无 —— 威士忌用)
 * @param xp          给予的经验值 (0 表示无 —— 茅台用)
 * @param messageKey  动作栏提示 lang key (null 表示无 —— 月光赌博用于报好/坏)
 */
public record BrewEffectPlan(List<MobEffectInstance> effects, float instantHeal, int xp, String messageKey) {

    /** 新酒 (强度 0) 或无效输入: 什么都不发生。 */
    public static final BrewEffectPlan EMPTY = new BrewEffectPlan(List.of(), 0.0F, 0, null);

    /** 单一持续效果方案 (急迫/抗性/速度/力量/金心/生命恢复)。 */
    public static BrewEffectPlan ofEffect(MobEffectInstance effect) {
        return new BrewEffectPlan(List.of(effect), 0.0F, 0, null);
    }

    /** 瞬间恢复方案 (威士忌)。 */
    public static BrewEffectPlan ofHeal(float heal) {
        return new BrewEffectPlan(List.of(), heal, 0, null);
    }

    /** 经验方案 (茅台)。 */
    public static BrewEffectPlan ofXp(int xp) {
        return new BrewEffectPlan(List.of(), 0.0F, xp, null);
    }

    /** 是否空方案 (无任何效果)。 */
    public boolean isEmpty() {
        return effects.isEmpty() && instantHeal <= 0.0F && xp <= 0;
    }
}
