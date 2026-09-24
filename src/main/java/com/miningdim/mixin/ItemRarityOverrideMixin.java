package com.miningdim.mixin;

import com.miningdim.core.ItemRarityOverrides;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让业务模块按物品栈覆盖稀有度 (即物品名颜色)。
 *
 * 选 {@code Item.getRarity(ItemStack)} 的 HEAD: 1.20.1 里没有任何子类覆写它, {@code ItemStack.getRarity()} 只是转调,
 * tooltip 首行、快捷栏物品名与聊天栏 [物品] 名全部经过这里。在 HEAD 返回还顺带绕开了原版附魔升档用的
 * {@code Item$1} switch 表: 那张表按类初始化时的 {@code Rarity.values().length} 定长, 运行期扩展出来的稀有度
 * 进去会越界。未被认领的物品原样走原版逻辑。
 */
@Mixin(Item.class)
public abstract class ItemRarityOverrideMixin {
    @Inject(method = "getRarity(Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/item/Rarity;",
            at = @At("HEAD"), cancellable = true)
    private void miningdim$overrideRarity(ItemStack stack, CallbackInfoReturnable<Rarity> callback) {
        Rarity rarity = ItemRarityOverrides.resolve(stack);
        if (rarity != null) {
            callback.setReturnValue(rarity);
        }
    }
}
