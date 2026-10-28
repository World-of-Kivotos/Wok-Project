package com.miningdim.job.chef.pot.mixin;

import com.miningdim.job.chef.fryer.FryerPlayerData;
import com.miningdim.job.chef.pot.PotCraftService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import vectorwing.farmersdelight.common.block.entity.CookingPotBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import vectorwing.farmersdelight.common.block.CookingPotBlock;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CookingPotBlock.class)
public abstract class CookingPotBlockMixin {
    @Inject(method = "onRemove(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)V", at = @At("HEAD"), remap = true)
    private void wok$dropMeal(BlockState oldState, Level level, BlockPos pos, BlockState newState,
                              boolean moving, CallbackInfo callback) {
        if (level instanceof ServerLevel && !oldState.is(newState.getBlock())
                && level.getBlockEntity(pos) instanceof CookingPotBlockEntity pot) PotCraftService.dropMealOnBreak(pot);
    }
    @Inject(method = "onRemove(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)V", at = @At("TAIL"), remap = true)
    private void wok$clearCheckpoint(BlockState oldState, Level level, BlockPos pos, BlockState newState,
                                     boolean moving, CallbackInfo callback) {
        if (level instanceof ServerLevel server && !oldState.is(newState.getBlock())) {
            FryerPlayerData.get(server).clearCheckpoint(server, pos);
        }
    }
}
