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

import java.util.List;

/**
 * FD 煎锅 (Spoilage_Granary_DesignSpec「可选配置」#12): 出菜前把锅里那件原料记进来源上下文 (加热 +25%),
 * 出菜后清掉; 原料已经烂了就**不出品** (否则一锅烂肉能做成一份新菜 —— 这是把腐烂当原料的漏洞)。
 *
 * <p>产物是从 {@code ItemUtils.spawnItemEntity} 出来的, 所以盖章由 {@link ItemUtilsMixin} 统一做;
 * 这里只负责"这一趟的原料与加成"和"拦下腐烂原料"。</p>
 */
@Pseudo
@Mixin(targets = FreshnessFdTargets.SKILLET, remap = false)
public abstract class SkilletMixin {

    @Inject(method = FreshnessFdTargets.SKILLET_COOK, at = @At("HEAD"), cancellable = true, remap = false)
    private void miningdim$guardAndBegin(ItemStack cookingStack, Level level, CallbackInfo callback) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        long nowMinute = FreshnessClockData.nowMinute(serverLevel);
        if (FreshnessProcessing.anyRotten(List.of(cookingStack), nowMinute)) {
            callback.cancel();
            return;
        }
        FreshnessSourceContext.begin(cookingStack, FreshnessProcessing.HEAT_BONUS);
    }

    @Inject(method = FreshnessFdTargets.SKILLET_COOK, at = @At("RETURN"), remap = false)
    private void miningdim$end(ItemStack cookingStack, Level level, CallbackInfo callback) {
        FreshnessSourceContext.end();
    }
}
