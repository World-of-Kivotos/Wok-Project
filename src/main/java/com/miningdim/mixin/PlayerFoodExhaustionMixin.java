package com.miningdim.mixin;

import com.miningdim.job.fisher.soup.OreSoupEffects;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Applies ore-soup exhaustion reduction at the player context boundary. */
@Mixin(Player.class)
public abstract class PlayerFoodExhaustionMixin {
    @ModifyVariable(method = "causeFoodExhaustion", at = @At("HEAD"), argsOnly = true)
    private float miningdim$reduceOreSoupExhaustion(float exhaustion) {
        return OreSoupEffects.reduceExhaustion((Player) (Object) this, exhaustion);
    }
}
