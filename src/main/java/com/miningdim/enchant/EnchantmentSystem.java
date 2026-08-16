package com.miningdim.enchant;

import com.miningdim.core.Subsystem;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 自研附魔子系统 (模块化铁律 3): 附魔注册 + 金钱修补的运行期结算。
 *
 * 运行期依赖货币门面 (扣维修费), 但只在玩家 tick 里触达, register 期不碰经济层, 故对主类 List 顺序不敏感。
 */
public final class EnchantmentSystem implements Subsystem {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/enchant");

    @Override
    public void register(IEventBus modBus, IEventBus forgeBus) {
        ModEnchantments.register(modBus);
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER,
                MoneyMendingConfig.SPEC, "miningdim-money-mending.toml");
        forgeBus.register(new MoneyMendingHandler());
        LOGGER.info("[miningdim] enchantment subsystem registered (money mending)");
    }
}
