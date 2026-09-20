package com.miningdim.mixin;

import com.miningdim.job.fisher.soup.OreSoupPlayerStateAccess;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Mirrors the current ore-soup type to the owning client for break-speed prediction. */
@Mixin(Player.class)
public abstract class PlayerOreSoupStateMixin implements OreSoupPlayerStateAccess {
    @Unique
    private static final EntityDataAccessor<Integer> MININGDIM_ORE_SOUP_STATE =
            SynchedEntityData.defineId(Player.class, EntityDataSerializers.INT);

    @Inject(method = "defineSynchedData", at = @At("TAIL"))
    private void miningdim$defineOreSoupState(CallbackInfo callback) {
        ((Player) (Object) this).getEntityData().define(MININGDIM_ORE_SOUP_STATE, 0);
    }

    @Override
    public int miningdim$getOreSoupState() {
        return ((Player) (Object) this).getEntityData().get(MININGDIM_ORE_SOUP_STATE);
    }

    @Override
    public void miningdim$setOreSoupState(int state) {
        ((Player) (Object) this).getEntityData().set(MININGDIM_ORE_SOUP_STATE, state);
    }
}
