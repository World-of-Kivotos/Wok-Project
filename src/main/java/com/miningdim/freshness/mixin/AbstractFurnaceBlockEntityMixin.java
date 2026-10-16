package com.miningdim.freshness.mixin;

import com.miningdim.freshness.FreshnessHooks;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 熔炉三件套 (熔炉 / 烟熏炉 / 高炉, 同一个基类): 原料过期就停工并把原料换成腐烂物, 出品按「加热 +25%」
 * 继承原料新鲜度 (Spoilage_Granary_DesignSpec「继承规则」「一期接入的加工点」、反漏洞 #5)。
 *
 * 两个注入点都在 {@code burn} 上: HEAD 判过期与留原料快照, RETURN 给产物盖章。
 * 注入私有实例方法, 签名按 1.20.1 锁定 (RegistryAccess, Recipe, NonNullList, int)。
 */
@Mixin(AbstractFurnaceBlockEntity.class)
public abstract class AbstractFurnaceBlockEntityMixin {

    @Inject(method = "burn", at = @At("HEAD"), cancellable = true)
    private void freshness$refuseRottenInput(RegistryAccess registryAccess, Recipe<?> recipe,
                                             NonNullList<ItemStack> items, int maxStackSize,
                                             CallbackInfoReturnable<Boolean> cir) {
        if (FreshnessHooks.refuseRottenFurnaceInput((AbstractFurnaceBlockEntity) (Object) this, items)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "burn", at = @At("RETURN"))
    private void freshness$inheritProduct(RegistryAccess registryAccess, Recipe<?> recipe,
                                          NonNullList<ItemStack> items, int maxStackSize,
                                          CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) {
            FreshnessHooks.inheritFurnaceProduct((AbstractFurnaceBlockEntity) (Object) this, items);
        }
    }
}
