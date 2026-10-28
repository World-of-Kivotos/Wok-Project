package com.miningdim.job.chef.pot.mixin;

import com.miningdim.job.chef.pot.PotCraftService;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import vectorwing.farmersdelight.common.block.entity.CookingPotBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import vectorwing.farmersdelight.common.block.CookingPotBlock;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CookingPotBlock.class)
public abstract class CookingPotBlockMixin {
    @Inject(method = "onRemove(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)V", at = @At("HEAD"), remap = true)
    private void wok$settleMeal(BlockState oldState, Level level, BlockPos pos, BlockState newState,
                              boolean moving, CallbackInfo callback) {
        if (level instanceof ServerLevel && !oldState.is(newState.getBlock())
                && level.getBlockEntity(pos) instanceof CookingPotBlockEntity pot) PotCraftService.beforeRemoval(pot);
    }
    @Redirect(method = "getCloneItemStack", at = @At(value = "INVOKE",
            target = "Lvectorwing/farmersdelight/common/block/entity/CookingPotBlockEntity;writeMeal(Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/nbt/CompoundTag;",
            remap = false), remap = true)
    private CompoundTag wok$previewMeal(CookingPotBlockEntity pot, CompoundTag tag) {
        return PotCraftService.previewMeal(pot, tag);
    }
    @Inject(method = "setPlacedBy", at = @At("TAIL"), remap = true)
    private void wok$placed(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack, CallbackInfo callback) {
        if (level instanceof ServerLevel && level.getBlockEntity(pos) instanceof CookingPotBlockEntity pot) PotCraftService.placed(pot);
    }
    @Inject(method = "onRemove(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)V", at = @At("TAIL"), remap = true)
    private void wok$clearCheckpoint(BlockState oldState, Level level, BlockPos pos, BlockState newState,
                                     boolean moving, CallbackInfo callback) {
        if (level instanceof ServerLevel server && !oldState.is(newState.getBlock())) {
            PotCraftService.tombstone(server, pos);
        }
    }
}
