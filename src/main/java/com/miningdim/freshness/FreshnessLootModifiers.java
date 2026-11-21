package com.miningdim.freshness;

import com.miningdim.core.MiningConstants;
import com.mojang.serialization.Codec;
import net.minecraftforge.common.loot.IGlobalLootModifier;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 本模块的全局掉落修改器注册。
 *
 * 序列化器 ID {@code miningdim:freshness_stamp} 与 {@code data/miningdim/loot_modifiers/freshness_stamp.json}
 * 的 {@code type} 一一对应, 并且要在共享文件 {@code data/forge/loot_modifiers/global_loot_modifiers.json}
 * 的 entries 里登记 (那份索引按「谁定义谁拥有」分属, 提交只动本模块那一行)。
 */
public final class FreshnessLootModifiers {

    private static final DeferredRegister<Codec<? extends IGlobalLootModifier>> MODIFIERS =
            DeferredRegister.create(ForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS,
                    MiningConstants.MODID);

    @SuppressWarnings("unused")
    public static final RegistryObject<Codec<? extends IGlobalLootModifier>> STAMP =
            MODIFIERS.register("freshness_stamp", () -> FreshnessLootModifier.CODEC);

    private FreshnessLootModifiers() {
    }

    public static void register(IEventBus modBus) {
        MODIFIERS.register(modBus);
    }
}
