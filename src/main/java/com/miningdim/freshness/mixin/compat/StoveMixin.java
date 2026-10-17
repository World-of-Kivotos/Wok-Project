package com.miningdim.freshness.mixin.compat;

import com.miningdim.freshness.FreshnessClockData;
import com.miningdim.freshness.FreshnessFdTargets;
import com.miningdim.freshness.FreshnessProcessing;
import com.miningdim.freshness.FreshnessSourceContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.items.ItemStackHandler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * FD 炉灶 (Spoilage_Granary_DesignSpec「可选配置」#14): 出菜前把灶上那几个格子的原料记进来源上下文
 * (加热 +25%), 出菜后清掉; 任何一格已经烂了就不出这一趟。
 *
 * <p>{@code cookAndOutputItems()} 是无参私有的: 原料从方块实体自己的物品栏里取, 所以这里 {@code @Shadow}
 * 它的 {@code inventory}。逐格"只拦烂的那一格"做不到 (方法自己遍历), 所以按整趟拦 —— 宁可少出一份菜,
 * 也不能让烂原料做成新菜。</p>
 */
@Pseudo
@Mixin(targets = FreshnessFdTargets.STOVE, remap = false)
public abstract class StoveMixin {

    @Shadow
    @Final
    private ItemStackHandler inventory;


    @Inject(method = FreshnessFdTargets.STOVE_COOK, at = @At("HEAD"), cancellable = true, remap = false)
    private void miningdim$guardAndBegin(CallbackInfo callback) {
        if (!(levelOf() instanceof ServerLevel serverLevel)) {
            return;
        }
        List<ItemStack> ingredients = new ArrayList<>();
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (!stack.isEmpty()) {
                ingredients.add(stack);
            }
        }
        long nowMinute = FreshnessClockData.nowMinute(serverLevel);
        if (FreshnessProcessing.anyRotten(ingredients, nowMinute)) {
            callback.cancel();
            return;
        }
        FreshnessSourceContext.begin(ingredients, FreshnessProcessing.HEAT_BONUS);
    }

    @Inject(method = FreshnessFdTargets.STOVE_COOK, at = @At("RETURN"), remap = false)
    private void miningdim$end(CallbackInfo callback) {
        FreshnessSourceContext.end();
    }

    /**
     * 这个方块实体的世界。
     *
     * 不 {@code @Shadow} {@code level} 字段: 它是 {@code BlockEntity} 的字段、**没有**声明在目标类里,
     * Mixin 找不到就会整份 mixin 应用失败 (实测 WARN + 静默不织, 可选配置 defaultRequire = 0 连崩都不会)。
     * 转成 {@code BlockEntity} 走它自己的访问器即可。
     */
    private Level levelOf() {
        return ((net.minecraft.world.level.block.entity.BlockEntity) (Object) this).getLevel();
    }
}
