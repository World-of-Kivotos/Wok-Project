package com.miningdim.freshness.mixin.compat;

import com.miningdim.freshness.FreshnessClockData;
import com.miningdim.freshness.FreshnessFdTargets;
import com.miningdim.freshness.FreshnessProcessing;
import com.miningdim.freshness.FreshnessSourceContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * FD 喷物品的统一出口 (Spoilage_Granary_DesignSpec「可选配置」#11): 有来源上下文就按来源盖章。
 *
 * <p>FD 的砧板、煎锅、炉灶、厨锅的产物最后都从这里变成掉落物, 所以"按来源盖章"只要接这一处就够了 ——
 * 各台机器各自算一遍继承才是漏口子的写法。没有上下文时(玩家丢东西、别的模组调它)什么都不做。</p>
 */
@Pseudo
@Mixin(targets = FreshnessFdTargets.ITEM_UTILS, remap = false)
public abstract class ItemUtilsMixin {

    @Inject(method = FreshnessFdTargets.SPAWN_ITEM_ENTITY, at = @At("HEAD"), remap = false)
    private static void miningdim$inheritFromSource(Level level, ItemStack stack, double x, double y, double z,
                                                    double xSpeed, double ySpeed, double zSpeed,
                                                    CallbackInfo callback) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        FreshnessSourceContext.inheritInto(stack, FreshnessProcessing.NO_BONUS,
                FreshnessClockData.nowMinute(serverLevel));
    }
}
