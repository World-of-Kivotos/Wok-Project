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

        if (ModList.get().isLoaded("tacz")) {
            com.kivotos.armorer.armor.integration.PlateArmorTaczIntegrationBootstrap.assemble(forgeBus);
        }

        LOGGER.info("Registered 54 plate armors, 18 plasma shields and 3 compatibility shield aliases");
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(PlasmaShieldNetwork::register);
    }
}
