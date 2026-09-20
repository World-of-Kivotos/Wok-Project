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
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

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

    /**
     * {@code ItemStack.hurt} 的返回值取的是局部变量 {@code damage + amount}, 不是刚写回去的 damage ——
     * 上面的 @ModifyArg 把磨损挡下时, 这个局部变量仍然是未减免的值。工具剩最后一点耐久时,
     * damage 没涨(耐久条还显示有), 返回的 "已损坏" 却是 true, {@code hurtAndBreak} 照样把工具 shrink 掉。
     * 减免生效反而把工具赔进去, 与翠玉/暗金鱼羹的效果正好相反。这里按实际写回的 damage 重算一次。
     *
     * 只在原返回值为 true 时纠正, 不会把 false 改成 true: 没有触发减免时 {@code getDamageValue()} 恒等于那个
     * 局部变量, 重算结果与原值一致, 对其它物品零影响。
     */
    @Inject(method = "hurt", at = @At("RETURN"), cancellable = true)
    private void miningdim$recomputeBreakageFromStoredDamage(CallbackInfoReturnable<Boolean> callback) {
        if (!callback.getReturnValueZ()) {
            return;
        }
        ItemStack stack = (ItemStack) (Object) this;
        callback.setReturnValue(stack.getDamageValue() >= stack.getMaxDamage());
    }
}
