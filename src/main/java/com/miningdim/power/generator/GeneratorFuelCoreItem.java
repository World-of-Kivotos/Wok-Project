package com.miningdim.power.generator;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** 不可修复的单档燃料芯；注册耐久固定为该档允许的最大时长。 */
public final class GeneratorFuelCoreItem extends Item {

    private final GeneratorSpec spec;

    public GeneratorFuelCoreItem(GeneratorSpec spec) {
        super(new Item.Properties().stacksTo(1).durability(spec.defaults().coreDurability()).setNoRepair());
        this.spec = spec;
    }

    public GeneratorSpec spec() {
        return spec;
    }

    @Override
    public int getMaxDamage(ItemStack stack) {
        return spec.runtime().coreDurability();
    }

    @Override
    public boolean isRepairable(ItemStack stack) {
        return false;
    }
}
