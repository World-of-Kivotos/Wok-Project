package com.miningdim.freshness;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.List;

/** FD 手持煎锅: 校验锅里的原料, 在本次配方产物交付前继承, 不扫描玩家已有物品。 */
public final class FreshnessFdHandCooking {

    private FreshnessFdHandCooking() {
    }

    public static boolean begin(ItemStack skillet, Level level) {
        return FreshnessConfig.replacementEnabled() && level instanceof ServerLevel serverLevel
                && FreshnessProcessing.anyRotten(List.of(storedFood(skillet)),
                FreshnessClockData.nowMinute(serverLevel));
    }

    public static ItemStack inheritProduct(ItemStack product, ItemStack skillet, Level level) {
        if (FreshnessConfig.stampingEnabled() && level instanceof ServerLevel serverLevel) {
            FreshnessProcessing.inherit(product, List.of(storedFood(skillet)),
                    FreshnessProcessing.HEAT_BONUS, FreshnessClockData.nowMinute(serverLevel));
        }
        return product;
    }

    /** FD 1.2.9 在 finishUsingItem 的配方回调结束后才移除 Cooking。 */
    public static ItemStack storedFood(ItemStack skillet) {
        CompoundTag tag = skillet.getTag();
        if (tag == null || !tag.contains(FreshnessFdTargets.SKILLET_ITEM_COOKING, 10)) {
            return ItemStack.EMPTY;
        }
        return ItemStack.of(tag.getCompound(FreshnessFdTargets.SKILLET_ITEM_COOKING));
    }
}
