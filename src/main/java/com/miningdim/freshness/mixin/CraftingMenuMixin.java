package com.miningdim.freshness.mixin;

import com.miningdim.freshness.FreshnessHooks;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 合成格: 原料里有过期的就不出产物 (并把过期原料换成腐烂物), 产物按「无加成」继承原料新鲜度
 * (Spoilage_Granary_DesignSpec 反漏洞 #7、#9; 一期加工点第一行)。
 *
 * 只注入 {@code CraftingMenu.slotChangedCraftingGrid} 这一个静态方法就够: 1.20.1 里工作台 3×3 与
 * 背包 2×2 的结果格都由它算 (背包那侧经 {@code InventoryMenu.slotsChanged} 转调), Shift 批量合成
 * 也走它。没有用 {@code ItemCraftedEvent}, 因为 Shift 合成时它在物品进背包**之后**才触发, 拦不住
 * 「老原料续命」。
 */
@Mixin(CraftingMenu.class)
public abstract class CraftingMenuMixin {

    @Inject(method = "slotChangedCraftingGrid", at = @At("HEAD"), cancellable = true)
    private static void freshness$refuseRottenIngredients(AbstractContainerMenu menu, Level level, Player player,
                                                          CraftingContainer craftSlots, ResultContainer resultSlots,
                                                          CallbackInfo ci) {
        if (FreshnessHooks.clearCraftingResultIfRotten(craftSlots, resultSlots, level)) {
            ci.cancel();
        }
    }

    @Inject(method = "slotChangedCraftingGrid", at = @At("RETURN"))
    private static void freshness$inheritCraftedProduct(AbstractContainerMenu menu, Level level, Player player,
                                                        CraftingContainer craftSlots, ResultContainer resultSlots,
                                                        CallbackInfo ci) {
        FreshnessHooks.inheritCraftedProduct(craftSlots, resultSlots, level);
    }
}
