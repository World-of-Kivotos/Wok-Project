package com.miningdim.job.engineer.effect;

import com.miningdim.job.engineer.EngineerConfig;
import com.miningdim.job.engineer.NanoEffect;
import com.miningdim.job.engineer.NanoNbt;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 纳米特效的纯逻辑核心 (MillenniumEngineer_Mod_DesignSpec 6.2)。把 "递减安全阀总系数"、"失效判定" 等
 * 与世界无关的计算抽出, 供 {@link NanoEffectTicker} 调用 + GameTest 用确定值断言 (删核心逻辑测试必挂)。
 *
 * 战斗向铁律: 机能修复递减安全阀 (100/50/25/12.5%) 是防 PvP 间隙滚雪球的核心 —— 四件合计约 1.875 倍单件
 * 而非 4 倍线性 (部署环境 80 血)。本类的 {@link #vitalityDecayFactors} 与 {@link #vitalityTotalHealFraction}
 * 直接编码该安全阀。
 */
public final class NanoEffects {

    private NanoEffects() {
    }

    /** 递减安全阀系数序列 (第 n 件机能修复甲的有效系数; 6.2: 100/50/25/12.5%)。 */
    private static final double[] DECAY_VALVE = {1.0, 0.5, 0.25, 0.125};

    /**
     * 给定 "生效中机能修复甲的件数 n", 返回每件按入场顺序的递减系数 (前 n 个安全阀值)。
     * 件数超过安全阀长度 (>4) 时多出的件系数为 0 (不再叠加, 防越界放大)。
     */
    public static double[] vitalityDecayFactors(int activePieces) {
        int n = Math.max(0, activePieces);
        double[] result = new double[n];
        for (int i = 0; i < n; i++) {
            result[i] = i < DECAY_VALVE.length ? DECAY_VALVE[i] : 0.0;
        }
        return result;
    }

    /**
     * 给定生效机能修复甲件数, 返回每周期回血占最大血量的总比例 (单件比例 * 安全阀系数之和)。
     * 例: 单件 2%, 四件 = 2% * (1+0.5+0.25+0.125) = 2% * 1.875 = 3.75% (非 4 件线性 8%)。
     */
    public static double vitalityTotalHealFraction(int activePieces) {
        double perPiece = EngineerConfig.VITALITY_HEAL_PCT_PER_TICK.get();
        double sum = 0.0;
        for (double f : vitalityDecayFactors(activePieces)) {
            sum += f;
        }
        return perPiece * sum;
    }

    /**
     * 机能修复单件是否仍生效: 带该特效 且 耐久 >= 失效阈值 (6.2: 耐久 < 50% 该件停止回血)。
     * 不可破坏/无最大耐久物品视为耐久充足 (恒生效)。
     */
    public static boolean vitalityActive(ItemStack armor) {
        if (!NanoNbt.hasEffect(armor, NanoEffect.VITALITY)) {
            return false;
        }
        return durabilityFraction(armor) >= EngineerConfig.VITALITY_FAIL_DURABILITY_PCT.get();
    }

    /**
     * 纳米重塑单件是否仍生效: 带该特效 且 损失耐久 <= 失效阈值 (6.2: 损失 > 阈值即停; 默认放宽到 40%)。
     */
    public static boolean reshapeActive(ItemStack armor) {
        if (!NanoNbt.hasEffect(armor, NanoEffect.RESHAPE)) {
            return false;
        }
        double lost = 1.0 - durabilityFraction(armor);
        return lost <= EngineerConfig.RESHAPE_FAIL_DAMAGE_PCT.get();
    }

    /** 物品当前耐久占最大耐久的比例 [0,1]; 不可破坏/无耐久返回 1.0 (恒满)。 */
    public static double durabilityFraction(ItemStack armor) {
        if (!armor.isDamageableItem() || armor.getMaxDamage() <= 0) {
            return 1.0;
        }
        int remaining = armor.getMaxDamage() - armor.getDamageValue();
        return (double) remaining / armor.getMaxDamage();
    }

    /** 统计一组护甲中生效中的机能修复甲件数 (供递减安全阀按 "生效件" 计)。 */
    public static int countActiveVitality(List<ItemStack> armorPieces) {
        int count = 0;
        for (ItemStack piece : armorPieces) {
            if (vitalityActive(piece)) {
                count++;
            }
        }
        return count;
    }

    // ---- 护盾反应式状态机 (6.2; 由 ticker 推进倒计时, 由 hurt handler 受击触发, 单一权威放此供 GameTest 断言) ----

    /**
     * ticker 每周期推进护盾两个倒计时 (不开窗、不耗充能): 免疫窗倒计时递减; 还有充能时再生倒计时递减到 0 即 armed。
     *
     * @param armor    护盾甲
     * @param interval 本周期 tick 数 (EFFECT_TICK_INTERVAL)
     * @return 本周期该件是否处于免疫窗内 (供粒子表现)
     */
    public static boolean advanceShieldTimers(ItemStack armor, int interval) {
        boolean inWindow = false;
        int window = NanoNbt.shieldWindowTick(armor);
        if (window > 0) {
            NanoNbt.setShieldWindowTick(armor, Math.max(0, window - interval));
            inWindow = true;
        }
        int maxCharges = EngineerConfig.SHIELD_MAX_CHARGES.get();
        int charges = Math.min(maxCharges, Math.max(0, NanoNbt.shieldCharges(armor)));
        if (charges >= maxCharges) {
            NanoNbt.setShieldRegenTick(armor, 0);
        } else {
            int regen = NanoNbt.shieldRegenTick(armor);
            if (regen <= 0) {
                regen = EngineerConfig.SHIELD_REGEN_INTERVAL_TICKS.get();
            }
            regen -= interval;
            if (regen <= 0) {
                charges++;
                NanoNbt.setShieldCharges(armor, charges);
                regen = charges < maxCharges
                        ? EngineerConfig.SHIELD_REGEN_INTERVAL_TICKS.get()
                        : 0;
            }
            NanoNbt.setShieldRegenTick(armor, Math.max(0, regen));
        }
        return inWindow;
    }

    /** 护盾甲当前是否处于免疫窗内 (受击免疫判定; 热路径只读单 int)。 */
    public static boolean shieldWindowActive(ItemStack armor) {
        return NanoNbt.shieldWindowTick(armor) > 0;
    }

    /**
     * 受击时尝试反应式触发护盾免疫窗 (6.2): 该件有充能 (charges > 0) 时, 消耗一次充能、开 X 秒免疫窗、重置该件
     * 再生倒计时, 返回 true; 否则不动, 返回 false。仅在无活动窗口时由 handler 调用。
     *
     * charges > 0 即 "带护盾且有充能" 的可靠廉价代理 (K_SHIELD_CHARGES 仅随 SHIELD 特效写入/清除)。
     *
     * 按件独立充能, 无跨件安全阀: MillenniumEngineer_Mod_DesignSpec 6.2 表格对护盾的显式裁决是件级独立资源池,
     * 与机能修复的递减安全阀、图腾的人级共享 CD 分属不同类。
     */
    public static boolean tryReactiveShield(ItemStack armor) {
        int charges = NanoNbt.shieldCharges(armor);
        if (charges <= 0) {
            return false;
        }
        NanoNbt.setShieldWindowTick(armor, EngineerConfig.SHIELD_IMMUNITY_TICKS.get());
        int remaining = charges - 1;
        NanoNbt.setShieldCharges(armor, remaining);
        if (remaining < EngineerConfig.SHIELD_MAX_CHARGES.get()
                && NanoNbt.shieldRegenTick(armor) <= 0) {
            NanoNbt.setShieldRegenTick(armor, EngineerConfig.SHIELD_REGEN_INTERVAL_TICKS.get());
        }
        return true;
    }
}
