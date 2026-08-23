package com.miningdim.effect;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;

/**
 * 厨师的窗口型真实效果。数值由活动实例的 amplifier 保存，效果寿命由实体 NBT 保存；各行为只读取该实例，
 * 不以进程内 UUID 表作为效果真源。
 */
public final class ChefWindowEffect extends MobEffect {

    private static final int COLOR = 0xD9A441;

    public ChefWindowEffect() {
        super(MobEffectCategory.BENEFICIAL, COLOR);
    }
}
