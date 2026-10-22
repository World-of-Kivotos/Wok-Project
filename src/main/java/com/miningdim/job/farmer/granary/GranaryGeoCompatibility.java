package com.miningdim.job.farmer.granary;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.farmer.granary.block.GranaryVentBlockEntity;
import com.miningdim.job.farmer.granary.block.GranaryVentBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Recreates static granary vent entities when loading chunks saved before vents had entities. */
@Mod.EventBusSubscriber(modid = MiningConstants.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class GranaryGeoCompatibility {

    private GranaryGeoCompatibility() {
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getChunk() instanceof LevelChunk chunk)) {
            return;
        }

        int created = hydrateVentEntities(chunk);
        if (created > 0 && !chunk.getLevel().isClientSide) {
            chunk.setUnsaved(true);
        }
    }

    /**
     * Fills missing vent entities using only this chunk's sections and entity table.
     * Returns the number created so callers can mark server chunks dirty exactly when needed.
     */
    public static int hydrateVentEntities(LevelChunk chunk) {
        int created = 0;
        LevelChunkSection[] sections = chunk.getSections();
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
        int minBlockX = chunk.getPos().getMinBlockX();
        int minBlockZ = chunk.getPos().getMinBlockZ();

        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            LevelChunkSection section = sections[sectionIndex];
            if (!section.maybeHas(state -> state.getBlock() instanceof GranaryVentBlock)) {
                continue;
            }

            int minBlockY = chunk.getMinBuildHeight() + (sectionIndex << 4);
            for (int localY = 0; localY < 16; localY++) {
                for (int localZ = 0; localZ < 16; localZ++) {
                    for (int localX = 0; localX < 16; localX++) {
                        var state = section.getBlockState(localX, localY, localZ);
                        if (!(state.getBlock() instanceof GranaryVentBlock)) {
                            continue;
                        }

                        mutablePos.set(minBlockX + localX, minBlockY + localY, minBlockZ + localZ);
                        BlockPos pos = mutablePos.immutable();
                        if (chunk.getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK) != null) {
                            continue;
                        }

                        chunk.addAndRegisterBlockEntity(new GranaryVentBlockEntity(pos, state));
                        created++;
                    }
                }
            }
        }
        return created;
    }
}
