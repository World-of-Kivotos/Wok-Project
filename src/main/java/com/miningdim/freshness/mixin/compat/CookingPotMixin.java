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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * FD 厨锅 (Spoilage_Granary_DesignSpec「可选配置」#15): 做好的菜在挪进输出格之前按原料继承 (加热 +25%);
 * 原料里任何一格烂了就不开这一锅。
 *
 * <p>厨锅的产物**不经过** {@code ItemUtils} (菜留在输出格里等玩家取), 所以这一处要自己盖章:
 * 在 {@code moveMealToOutput()} 的 HEAD 拿到成品展示格里的菜, 继承完再写回去, 之后它才被挪到输出格。</p>
 *
 * <p>两个注入点都写成**无参**处理器: 目标方法的参数里带着 FD 自己的类型
 * ({@code CookingPotRecipe}), 处理器签名里出现它会让"没装 FD"的环境连类都加载不了。Mixin 允许处理器
 * 省略目标参数 (只保留 {@code CallbackInfo*}), 所以这里正好绕开。</p>
 */
@Pseudo
@Mixin(targets = FreshnessFdTargets.POT, remap = false)
public abstract class CookingPotMixin {

    @Shadow
    @Final
    private ItemStackHandler inventory;


    /** 菜要挪进输出格了: 先把展示格里那份盖成"按原料继承"的章。 */
    @Inject(method = FreshnessFdTargets.POT_MOVE_MEAL, at = @At("HEAD"), remap = false)
    private void miningdim$stampMeal(CallbackInfo callback) {
        if (!(levelOf() instanceof ServerLevel serverLevel)) {
            return;
        }
        ItemStack meal = inventory.getStackInSlot(FreshnessFdTargets.POT_MEAL_SLOT);
        if (meal.isEmpty()) {
            return;
        }
        long nowMinute = FreshnessClockData.nowMinute(serverLevel);
        FreshnessSourceContext.begin(inputs(), FreshnessProcessing.HEAT_BONUS);
        try {
            FreshnessSourceContext.inheritInto(meal, nowMinute);
        } finally {
            FreshnessSourceContext.end();
        }
        inventory.setStackInSlot(FreshnessFdTargets.POT_MEAL_SLOT, meal);
    }

    /** 开锅前先看原料: 有一格烂了就不做 (返回 false = 这一锅没熟)。 */
    @Inject(method = FreshnessFdTargets.POT_PROCESS, at = @At("HEAD"), cancellable = true, remap = false)
    private void miningdim$refuseRottenInputs(CallbackInfoReturnable<Boolean> callback) {
        if (!(levelOf() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (FreshnessProcessing.anyRotten(inputs(), FreshnessClockData.nowMinute(serverLevel))) {
            callback.setReturnValue(false);
        }
    }

    private List<ItemStack> inputs() {
        List<ItemStack> ingredients = new ArrayList<>(FreshnessFdTargets.POT_INPUT_SLOTS);
        for (int slot = 0; slot < FreshnessFdTargets.POT_INPUT_SLOTS; slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (!stack.isEmpty()) {
                ingredients.add(stack);
            }
        }
        return ingredients;
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
