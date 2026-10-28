package com.miningdim.job.chef.station;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.jetbrains.annotations.Nullable;

public class FryerStationBlock extends CookingStationBlock {
    FryerStationBlock(BlockBehaviour.Properties properties, Style style, StationShape shape,
                      StationParticles.Emitter particles) {
        super(properties, Kind.DEEP_FRYER, style, shape, particles);
    }

    @Nullable
    public static Component fryerBlockedReason(Level level, BlockPos pos) {
        return productionBlockedReason(level, pos);
    }
}
