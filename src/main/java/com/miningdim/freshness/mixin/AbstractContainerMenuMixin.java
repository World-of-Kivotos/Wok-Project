package com.miningdim.freshness.mixin;

import com.miningdim.freshness.FreshnessMergeHooks;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Shift 快移的同种合并 (Spoilage_Granary_DesignSpec mixin 清单 #9)。
 *
 * 原版这一段的合并判据是 {@code ItemStack.isSameItemSameTags} —— 同种食物只要新鲜度不同就**不是**"相同",
 * 于是玩家 Shift 一下会得到两个半堆 (而且新货还会被老货的章拖住)。这里在 HEAD 先跑一遍
 * {@link FreshnessMergeHooks#mergeShiftMove}: 整堆装得下、只差新鲜度、且差距在合并窗口内时, 直接按
 * 加权平均并进目标槽并结束这一段 (返回 true, 等价于"整堆搬完")。
 *
 * 不改原版逻辑: 没并上就原样交给 vanilla (同物同 tag 的叠放与找空槽都照旧)。
 * **主 MOD 自己的两个覆写** ({@code AbstractMiningMenu}、{@code MarriageBackpackMenu}) 不走这里
 * (它们整段重写了 moveItemStackTo), 在那两处直接调同一个入口 —— 三处同一份判据。
 */
@Mixin(AbstractContainerMenu.class)
public abstract class AbstractContainerMenuMixin {

    @Inject(method = "moveItemStackTo", at = @At("HEAD"), cancellable = true)
    private void freshness$mergeShiftMove(ItemStack stack, int startIndex, int endIndex, boolean reverseDirection,
                                          CallbackInfoReturnable<Boolean> cir) {
        Boolean merged = FreshnessMergeHooks.mergeShiftMove((AbstractContainerMenu) (Object) this,
                stack, startIndex, endIndex, reverseDirection);
        if (merged != null) {
            cir.setReturnValue(merged);
        }
    }
}