package com.miningdim.freshness.mixin.compat;

import com.miningdim.freshness.FreshnessFdTargets;
import com.miningdim.freshness.FreshnessProcessing;
import com.miningdim.freshness.FreshnessSourceContext;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * FD 砧板 (Spoilage_Granary_DesignSpec「可选配置」#10): 切之前把板上那件原料记进来源上下文, 切完清掉。
 *
 * <p>为什么必须记在这一处: 砧板先按配方算出产物, 再统一交给 {@code ItemUtils.spawnItemEntity} 喷出来 ——
 * 喷的那一刻手里只有产物, 原料已经不在参数里了 (见 {@link FreshnessSourceContext})。</p>
 *
 * <p>砧板是**切配**, 加成 0 (规格「继承规则」的工序表)。</p>
 */
@Pseudo
@Mixin(targets = FreshnessFdTargets.CUTTING_BOARD, remap = false)
public abstract class CuttingBoardMixin {

    @Shadow(remap = false)
    public abstract ItemStack getStoredItem();

    @Inject(method = FreshnessFdTargets.CUTTING_BOARD_PROCESS, at = @At("HEAD"), remap = false)
    private void miningdim$beginSourceContext(ItemStack tool, Player player,
                                              CallbackInfoReturnable<Boolean> callback) {
        FreshnessSourceContext.begin(getStoredItem(), FreshnessProcessing.NO_BONUS);
    }

    @Inject(method = FreshnessFdTargets.CUTTING_BOARD_PROCESS, at = @At("RETURN"), remap = false)
    private void miningdim$endSourceContext(ItemStack tool, Player player,
                                            CallbackInfoReturnable<Boolean> callback) {
        FreshnessSourceContext.end();
    }
}
