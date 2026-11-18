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
 * 当前只注册序列化器类型；战利品数据与全局索引留待观察入口接入。
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
