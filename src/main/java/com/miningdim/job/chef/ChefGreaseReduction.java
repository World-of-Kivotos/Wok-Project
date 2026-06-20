package com.miningdim.job.chef;

import com.miningdim.combat.PlayerDamageReduction;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.player.Player;

/**
 * 凝脂 (Chef_Job_DesignSpec 第十一章: 仅爆炸减伤, 高品质门控)。作为一个【独立命名减伤源】接入玩家减伤单点
 * 结算 ({@link PlayerDamageReduction}) —— 不再自挂 LivingHurtEvent 各自 setAmount, 改由结算点统一乘法 + 全局帽
 * (减伤统一: 凝脂/矿脉抗性/烈酒钝感… 各报一个 rate, 单点连乘)。
 *
 * 仅爆炸来源生效 (explosion / player_explosion); 减比读 {@link ChefWindowEffectState} 窗口快照
 * (千分比: 高 30% / 超凡 45% / 闪耀 60%)。非爆炸或未激活返回 0。
 */
public final class ChefGreaseReduction implements PlayerDamageReduction.ReductionSource {

    @Override
    public String name() {
        return "凝脂";
    }

    @Override
    public double rate(Player victim, DamageSource source) {
        if (!isExplosion(source)) {
            return 0.0D;
        }
        int reducePerMille = ChefWindowEffectState.greaseReducePerMille(victim.getUUID());
        if (reducePerMille <= 0) {
            return 0.0D;
        }
        return Math.min(1.0D, reducePerMille / 1000.0D);
    }

    private static boolean isExplosion(DamageSource source) {
        return source.is(DamageTypes.EXPLOSION) || source.is(DamageTypes.PLAYER_EXPLOSION);
    }
}
