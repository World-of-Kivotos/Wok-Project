package com.miningdim.job.chef.station;

import net.minecraft.world.level.block.state.BlockBehaviour;

public class FryerStationBlock extends CookingStationBlock {
    FryerStationBlock(BlockBehaviour.Properties properties, Style style, StationShape shape,
                      StationParticles.Emitter particles) {
        super(properties, Kind.DEEP_FRYER, style, shape, particles);
    }
}
