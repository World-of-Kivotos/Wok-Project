package com.miningdim.mixin;

import com.miningdim.job.fisher.soup.OreSoupEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Limits soup durability reduction to the one hurt call issued by ItemStack.mineBlock. */
@Mixin(ItemStack.class)
public abstract class ItemStackMiningDurabilityMixin {
    @Redirect(method = "mineBlock", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/Item;mineBlock(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/LivingEntity;)Z"))
    private boolean miningdim$scopeMiningDurability(Item item, ItemStack stack, Level level, BlockState state,
                                                    BlockPos pos, LivingEntity entity) {
        if (entity instanceof Player player) {
            return OreSoupEffects.withMiningDurabilityAttempt(stack, player,
                    () -> item.mineBlock(stack, level, state, pos, entity));
        }
        return item.mineBlock(stack, level, state, pos, entity);
    }

    @ModifyArg(method = "hurt", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;setDamageValue(I)V"), index = 0)
    private int miningdim$reduceAfterUnbreaking(int damageAfterUnbreaking) {
        return OreSoupEffects.reduceMiningDamageAfterUnbreaking((ItemStack) (Object) this, damageAfterUnbreaking);
    }
}
