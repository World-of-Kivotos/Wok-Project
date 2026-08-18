package com.miningdim.power;

import com.miningdim.core.Subsystem;
import com.miningdim.power.client.PowerGeneratorClient;
import com.miningdim.power.client.PowerMachineClient;
import com.miningdim.power.client.PowerEndgameClient;
import com.miningdim.power.cable.PowerCableColors;
import com.miningdim.power.data.PowerDataGeneration;
import com.miningdim.power.grid.EnergyNetworkManager;
import com.miningdim.power.mineral.PowerMineralColors;
import com.miningdim.power.mineral.PowerMineralRegistry;
import com.miningdim.power.rubber.PowerRubberRegistry;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/** 能源系统入口：基础矿物、三档发电机外壳与有线 FE 网络。 */
public final class PowerSystem implements Subsystem {

    @Override
    public void register(IEventBus modBus, IEventBus forgeBus) {
        PowerRegistry.register(modBus);
        PowerMachineRegistry.register(modBus);
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER,
                PowerGeneratorConfig.SPEC, "miningdim-power.toml");
        PowerMineralRegistry.register(modBus);
        PowerRubberRegistry.register(modBus);
        PowerCreativeTab.register(modBus);
        PowerDataGeneration.register(modBus);
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            PowerMineralColors.register(modBus);
            PowerCableColors.register(modBus);
        });
        modBus.addListener((FMLClientSetupEvent event) ->
                event.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                        () -> () -> {
                            PowerGeneratorClient.registerScreens();
                            PowerMachineClient.registerScreens();
                            PowerEndgameClient.registerScreens();
                        })));
        EnergyNetworkManager.register(forgeBus);
    }
}
