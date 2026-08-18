package com.miningdim.power.compat.jade;

import com.miningdim.power.GeneratorMultiblockBlock;
import com.miningdim.power.cable.EnergyCableBlock;
import com.miningdim.power.cable.EnergyCableBlockEntity;
import com.miningdim.power.endgame.LowTemperatureControllerBlock;
import com.miningdim.power.endgame.LowTemperatureControllerBlockEntity;
import com.miningdim.power.generator.GeneratorBlockEntity;
import com.miningdim.power.machine.AirSeparationUnitBlockEntity;
import com.miningdim.power.machine.MetallurgicPurifierBlockEntity;
import com.miningdim.power.machine.PowerMachineBlock;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

/** Jade 可选集成仅桥接服务端权威快照，不参与能源系统运行。 */
@WailaPlugin
public final class PowerJadePlugin implements IWailaPlugin {

    private static final CableJadeProvider CABLE = new CableJadeProvider();
    private static final GeneratorJadeProvider GENERATOR = new GeneratorJadeProvider();
    private static final PurifierJadeProvider PURIFIER = new PurifierJadeProvider();
    private static final AirSeparatorJadeProvider AIR_SEPARATOR = new AirSeparatorJadeProvider();
    private static final ControllerJadeProvider CONTROLLER = new ControllerJadeProvider();

    @Override
    public void register(IWailaCommonRegistration registration) {
        registration.registerBlockDataProvider(CABLE, EnergyCableBlockEntity.class);
        registration.registerBlockDataProvider(GENERATOR, GeneratorBlockEntity.class);
        registration.registerBlockDataProvider(PURIFIER, MetallurgicPurifierBlockEntity.class);
        registration.registerBlockDataProvider(AIR_SEPARATOR, AirSeparationUnitBlockEntity.class);
        registration.registerBlockDataProvider(CONTROLLER, LowTemperatureControllerBlockEntity.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerBlockComponent(CABLE, EnergyCableBlock.class);
        registration.registerBlockComponent(GENERATOR, GeneratorMultiblockBlock.class);
        registration.registerBlockComponent(PURIFIER, PowerMachineBlock.class);
        registration.registerBlockComponent(AIR_SEPARATOR, PowerMachineBlock.class);
        registration.registerBlockComponent(CONTROLLER, LowTemperatureControllerBlock.class);
    }
}
