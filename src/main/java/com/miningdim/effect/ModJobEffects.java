package com.miningdim.effect;

import com.miningdim.core.MiningConstants;
import net.minecraft.world.effect.MobEffect;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 共享自定义效果注册中心 (JobFramework_Shared_Foundation_DesignSpec 第五章)。放 com.miningdim.effect 共享包
 * (非任何单职业包), 登记跨职业自定义效果。
 *
 * 谁先用谁建 (第五章): 本任务先登记易伤 (塔罗/厨师/未来武器共用)。厨师窗口效果组 (余韵/披甲/凝脂/稳膛/耐饥)
 * 待厨师职业实现期在此追加 RegistryObject, 零结构改动 (DeferredRegister 多一行 register)。此处不预置无实现的
 * 空效果壳 (避免无主死效果)。
 *
 * 在拥有者子系统 ({@link com.miningdim.job.JobFrameworkSystem}) 的 register() 内 {@link #register(IEventBus)}
 * 接 modBus (第五章: 共享包持自己的 DeferredRegister)。
 */
public final class ModJobEffects {

    private ModJobEffects() {
    }

    public static final DeferredRegister<MobEffect> EFFECTS =
            DeferredRegister.create(ForgeRegistries.MOB_EFFECTS, MiningConstants.MODID);

    /** 易伤 (第五章 + Tarot 第四章): 受伤放大, 等级 I-V, 由 {@link VulnerabilityHurtHandler} 单点结算。 */
    public static final RegistryObject<MobEffect> VULNERABILITY =
            EFFECTS.register("vulnerability", VulnerabilityEffect::new);

    /** 接 modBus (在 JobFrameworkSystem.register 内调用一次)。 */
    public static void register(IEventBus modBus) {
        EFFECTS.register(modBus);
    }
}
