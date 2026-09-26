package com.miningdim.achievement.trigger;

import com.miningdim.achievement.AchievementIds;
import com.miningdim.core.MiningConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.stats.StatFormatter;
import net.minecraft.stats.Stats;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/**
 * 6.1 里 P2 职业那一行的自定义统计项, 与 P1 各行的 {@link AchievementStats} 并列 (同一个注册表、同一套写法)。
 *
 * 递增一律经 {@link AchievementStats#award}, 它随后重新核对 stat_at_least。Stat 对象在 FMLCommonSetup 里建好
 * ({@link #bindFormatters}), 与 P1 统计项一样在原版"统计信息"界面里出现。
 */
public final class JobStats {

    private static final DeferredRegister<ResourceLocation> CUSTOM_STATS =
            DeferredRegister.create(Registries.CUSTOM_STAT, MiningConstants.MODID);

    /**
     * 在职业耕地上收获: {@code wok-experience} 发放来源为 {@code miningdim:farmer/harvest} 或 {@code miningdim:farmer/pick}
     * 的经验时加 1 (与这笔经验多少、是否被每日衰减削到 0 无关), FakePlayer 不计。
     */
    public static final RegistryObject<ResourceLocation> FARMER_HARVESTS =
            CUSTOM_STATS.register("farmer_harvests", () -> AchievementIds.id("farmer_harvests"));

    private JobStats() {
    }

    /** mod 构造期挂上 DeferredRegister。 */
    static void register(IEventBus modBus) {
        CUSTOM_STATS.register(modBus);
    }

    /** 以默认格式器建好 Stat 对象 (FMLCommonSetup 的 enqueueWork 里调用, 理由见 {@link AchievementStats} 类注释)。 */
    static void bindFormatters() {
        Stats.CUSTOM.get(FARMER_HARVESTS.get(), StatFormatter.DEFAULT);
    }
}
