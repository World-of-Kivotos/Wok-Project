package com.miningdim.mixin;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.fisher.ore.OreFishCatchHandler;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Replaces only the successful vanilla fishing loot list before Forge can cancel its later drop event. */
@Mixin(FishingHook.class)
public abstract class VanillaOreFishMixin {
    @Redirect(
            method = "retrieve(Lnet/minecraft/world/item/ItemStack;)I",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/storage/loot/LootTable;getRandomItems(Lnet/minecraft/world/level/storage/loot/LootParams;)Lit/unimi/dsi/fastutil/objects/ObjectArrayList;"))
    private ObjectArrayList<ItemStack> miningdim$replaceSuccessfulFishingLoot(LootTable lootTable, LootParams params) {
        ObjectArrayList<ItemStack> drops = lootTable.getRandomItems(params);
        FishingHook hook = (FishingHook) (Object) this;
        Level level = hook.level();
        if (!level.isClientSide && level.dimension().equals(MiningConstants.MINING_LEVEL)
                && level.getFluidState(hook.blockPosition()).is(FluidTags.WATER)) {
            OreFishCatchHandler.replaceDrops(drops, level.random.nextInt(10_000));
        }
        return drops;
    }
}
