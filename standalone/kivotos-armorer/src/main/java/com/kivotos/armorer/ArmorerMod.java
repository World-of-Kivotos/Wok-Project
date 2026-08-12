package com.kivotos.armorer;

import com.kivotos.armorer.armor.PlateArmorDamageHandler;
import com.kivotos.armorer.armor.PlateArmorEquipmentHandler;
import com.kivotos.armorer.shield.PlasmaShieldHandler;
import com.kivotos.armorer.shield.network.PlasmaShieldNetwork;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Standalone entry point for the Kivotos armorer armor collection. */
@Mod(ArmorerMod.MODID)
public final class ArmorerMod {

    public static final String MODID = "kivotos_armorer";
    public static final Logger LOGGER = LoggerFactory.getLogger(MODID);

    public ArmorerMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        IEventBus forgeBus = MinecraftForge.EVENT_BUS;

        ArmorerItems.register(modBus);
        ArmorerCreativeTab.register(modBus);
        ArmorerSounds.register(modBus);
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER,
                ArmorerConfig.SPEC, "kivotos-armorer.toml");

        forgeBus.register(new PlateArmorDamageHandler());
        forgeBus.register(new PlateArmorEquipmentHandler());
        forgeBus.register(new PlasmaShieldHandler());
        modBus.addListener(this::commonSetup);
        // 配置载入与重载后立刻校验插板矩阵, 让长度写错的配置在此处失败, 而不是等玩家穿戴时崩服务端 tick。
        modBus.addListener(this::onConfigLoad);
        modBus.addListener(this::onConfigReload);

        if (ModList.get().isLoaded("tacz")) {
            com.kivotos.armorer.armor.integration.PlateArmorTaczIntegrationBootstrap.assemble(forgeBus);
        }

        LOGGER.info("Registered 54 plate armors, 18 plasma shields and 3 compatibility shield aliases");
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(PlasmaShieldNetwork::register);
    }

    private void onConfigLoad(ModConfigEvent.Loading event) {
        validateOwnConfig(event.getConfig());
    }

    private void onConfigReload(ModConfigEvent.Reloading event) {
        validateOwnConfig(event.getConfig());
    }

    /** 只校验本模组自己的 SERVER 配置; 同总线上其它模组的配置事件一律放行。 */
    private void validateOwnConfig(ModConfig config) {
        if (config.getSpec() == ArmorerConfig.SPEC) {
            ArmorerConfig.PLATE_ARMOR.validateMatrices();
        }
    }
}
