package com.miningdim.freshness.mixin;

import com.miningdim.freshness.FreshnessHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 营火: 掉落之前改产物 (Spoilage_Granary_DesignSpec mixin 清单 #5)。
 *
 * 营火存的是原料, 产物是掉落那一刻按配方现算的, 所以"放置时盖章"没有用 —— 唯一的时机就是这一下
 * {@code Containers.dropItemStack}。用 {@code @Redirect} 而不是 {@code @ModifyArg}: 后者只拿得到产物,
 * 而这里还要读营地实体里那件原料 (`CampfireBlockEntity#getItems` 是公开的)。
 *
 * 没有可继承的原料时原样放行 (产物随后由掉落观察点按现在盖章) —— 不编数据。
 */
@Mixin(CampfireBlockEntity.class)
public abstract class CampfireBlockEntityMixin {

    @Redirect(method = "cookTick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/Containers;dropItemStack(Lnet/minecraft/world/level/Level;DDDLnet/minecraft/world/item/ItemStack;)V"))
    private static void freshness$dropCookedProduct(Level callLevel, double x, double y, double z, ItemStack product,
                                                    Level level, BlockPos pos, BlockState state,
                                                    CampfireBlockEntity campfire) {
        Containers.dropItemStack(callLevel, x, y, z,
                FreshnessHooks.stampCampfireProduct(level == null ? callLevel : level, campfire, product));
    }
}