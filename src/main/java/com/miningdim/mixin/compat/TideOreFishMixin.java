package com.miningdim.mixin.compat;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.fisher.ore.OreFishCatchHandler;
import com.miningdim.job.fisher.ore.OreFishType;
import com.miningdim.job.fisher.ore.OreFishingItems;
import com.miningdim.job.fisher.size.FishCatchService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * Optional Tide 1.6.5 bridge. Runs only after its minigame has produced a successful hooked-item list:
 * first the mining-dimension ore-fish replacement, then size measurement of whatever is actually caught.
 * Both steps share one injector so their order is fixed; Tide never posts {@code ItemFishedEvent},
 * so this is the only place Tide catches can be measured.
 */
@Pseudo
@Mixin(targets = "com.li64.tide.registries.entities.misc.fishing.TideFishingHook", remap = false)
public abstract class TideOreFishMixin {
    @Shadow(remap = false)
    private List<ItemStack> hookedItems;

    @Shadow(remap = false)
    private FluidState fluid;

    @Inject(
            method = "retrieve(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/player/Player;)I",
            at = @At(value = "FIELD", target = "Lcom/li64/tide/registries/entities/misc/fishing/TideFishingHook;hookedItems:Ljava/util/List;",
                    opcode = Opcodes.GETFIELD, ordinal = 0),
            require = 1,
            remap = false)
    private void miningdim$settleSuccessfulTideCatch(ItemStack rod, ServerLevel serverLevel, Player player,
                                                      CallbackInfoReturnable<Integer> callback) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        Level level = ((Entity) (Object) this).level();
        if (level.isClientSide) {
            return;
        }
        if (level.dimension().equals(MiningConstants.MINING_LEVEL) && fluid != null && fluid.is(FluidTags.WATER)) {
            OreFishType type = OreFishCatchHandler.typeForRoll(level.random.nextInt(10_000));
            if (type != null) {
                // Tide's empty-table fallbacks hand back an immutable List.of(...); clearing it in place throws,
                // so the replacement is always a fresh mutable list.
                hookedItems = new ArrayList<>(List.of(new ItemStack(OreFishingItems.FISH.get(type).get())));
            }
        }
        if (hookedItems != null) {
            FishCatchService.onSuccessfulCatch(serverPlayer, hookedItems);
        }
    }
}
