package com.miningdim.district.mixin.create;

import com.miningdim.district.guard.create.CreateGuards;
import com.miningdim.district.guard.create.CreateHookTargets;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * C5 (设计文档 22.6): 装置部件的总网。部件位置 (MovementContext.position) 外扩 2 格的框碰到任何在用自管区时, 这个部件
 * 这一 tick 的 visitNewPosition 与 tick 都跳过: 收割机、犁、机械手、装置上的发射器、钻头与锯伤实体、火车上的一切部件。
 */
@Pseudo
@Mixin(targets = CreateHookTargets.CONTRAPTION_ENTITY, remap = false)
public abstract class ContraptionActorMixin {

    @Inject(method = CreateHookTargets.IS_ACTOR_ACTIVE, at = @At("HEAD"), cancellable = true, remap = false)
    private void miningdim$guardDistrict(@Coerce Object context, @Coerce Object behaviour,
                                         CallbackInfoReturnable<Boolean> callback) {
        Entity self = (Entity) (Object) this;
        if (!CreateGuards.actorMayAct(self.level(), context, self.getBoundingBox())) {
            callback.setReturnValue(false);
        }
    }
}
