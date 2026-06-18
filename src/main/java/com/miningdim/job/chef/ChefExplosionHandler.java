package com.miningdim.job.chef;

import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 凝脂爆炸减伤 (Chef_Job_DesignSpec 第十一章: 仅爆炸减伤, 高品质门控)。
 *
 * 仅对爆炸来源伤害减伤 (按 DamageType 是 explosion / player_explosion 判定), 减比读
 * {@link ChefWindowEffectState} 的窗口快照 (高 30% / 超凡 45% / 闪耀 60%)。
 *
 * 与易伤仲裁的分工: 易伤 ({@link com.miningdim.effect.VulnerabilityHurtHandler}) 是全 mod 单一乘伤点,
 * 凝脂是 "仅爆炸的减伤" —— 不同语义 (定向减伤 vs 全向增伤), 不与易伤同点合并; 但二者都挂 LivingHurtEvent,
 * Forge 按监听优先级顺序执行, 最终金额是各 handler 链式作用结果 (减伤后金额再被易伤放大或反之, 均为乘法
 * 不破单点封顶纪律 —— 凝脂只缩小爆炸入伤, 不参与易伤封顶计算)。本 handler 不放大任何伤害, 只对爆炸缩减,
 * 故不违反 "厨师不得自挂 LivingHurtEvent 各挂一次易伤" 红线 (那条针对易伤乘伤, 此处是减伤)。
 */
public final class ChefExplosionHandler {

    @SubscribeEvent
    public void onLivingHurt(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (!isExplosion(event)) {
            return;
        }
        int reducePerMille = ChefWindowEffectState.greaseReducePerMille(player.getUUID());
        if (reducePerMille <= 0) {
            return;
        }
        float factor = 1.0F - Math.min(1.0F, reducePerMille / 1000.0F);
        event.setAmount(event.getAmount() * factor);
    }

    private static boolean isExplosion(LivingHurtEvent event) {
        return event.getSource().is(DamageTypes.EXPLOSION)
                || event.getSource().is(DamageTypes.PLAYER_EXPLOSION);
    }
}
