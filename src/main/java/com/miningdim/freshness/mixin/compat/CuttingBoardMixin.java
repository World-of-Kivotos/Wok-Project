package com.miningdim.freshness.mixin.compat;

import com.miningdim.freshness.FreshnessFdTargets;
import com.miningdim.freshness.FreshnessProcessing;
import com.miningdim.freshness.FreshnessSourceContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Optional;
import java.util.function.Consumer;

@Pseudo
@Mixin(targets = FreshnessFdTargets.CUTTING_BOARD, remap = false)
public abstract class CuttingBoardMixin {

    @Shadow(remap = false)
    public abstract ItemStack getStoredItem();

    // 包住真正切配的回调, 异常退出也不能让原料上下文污染下一次喷物品。
    @Redirect(method = FreshnessFdTargets.CUTTING_BOARD_PROCESS, at = @At(value = "INVOKE",
            target = "Ljava/util/Optional;ifPresent(Ljava/util/function/Consumer;)V"), remap = false)
    private <T> void miningdim$withCuttingSource(Optional<T> recipe, Consumer<? super T> process) {
        FreshnessSourceContext.begin(getStoredItem(), FreshnessProcessing.NO_BONUS);
        try {
            recipe.ifPresent(process);
        } finally {
            FreshnessSourceContext.end();
        }
    }
}
