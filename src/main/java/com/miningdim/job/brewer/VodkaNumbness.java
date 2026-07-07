package com.miningdim.job.brewer;

import com.miningdim.combat.PlayerDamageReduction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;

/**
 * 烈酒钝感 (伏特加闪耀永久特殊, 阶段 5(iv) 第 2 种)。作为【独立命名减伤源】接入玩家减伤单点结算
 * ({@link PlayerDamageReduction}, 与凝脂/矿脉抗性等连乘 + 全局帽) —— 全伤减伤, 每层 5% (满 5 层 0.25)。
 * 范式照 {@link com.miningdim.job.chef.ChefGreaseReduction} (实现 ReductionSource, 报 name() + rate())。
 *
 * 不在身上挂修饰: 减伤是受击瞬间按当前层数现算 (层存 {@link BrewBuffStore}), 死亡清层即自动失效, 无残留泄漏。
 */
public final class VodkaNumbness implements PlayerDamageReduction.ReductionSource {

    @Override
    public String name() {
        return "烈酒钝感";
    }

    @Override
    public double rate(Player victim, DamageSource source) {
        if (!(victim instanceof ServerPlayer player)) {
            return 0.0D; // 仅服务端权威结算 (客户端无 store)。
        }
        int layers = BrewBuffStore.get(player.server.overworld()).layers(player.getUUID(), WineType.VODKA);
        if (layers <= 0) {
            return 0.0D;
        }
        return Math.min(1.0D, BrewPermanentBuffs.vodkaReductionRate(layers));
    }
}
