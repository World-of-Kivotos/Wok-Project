package com.miningdim.job.chef.fryer.mixin;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ChunkMap.class)
public interface FryerChunkMapAccess {
    @Accessor("level")
    ServerLevel wok$fryerLevel();
}
