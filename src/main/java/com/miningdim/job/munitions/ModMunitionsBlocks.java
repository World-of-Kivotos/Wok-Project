package com.miningdim.job.munitions;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.munitions.block.GunsmithPressBlock;
import com.miningdim.job.munitions.block.MunitionsBenchBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.List;

public final class ModMunitionsBlocks {

    private ModMunitionsBlocks() {
    }

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, MiningConstants.MODID);

    public static final RegistryObject<Block> MUNITIONS_BENCH = registerBench("munitions_bench", 1, 2);
    public static final RegistryObject<Block> MUNITIONS_BENCH_MEDIUM = registerBench("munitions_bench_medium", 3, 4);
    public static final RegistryObject<Block> MUNITIONS_BENCH_HIGH = registerBench("munitions_bench_high", 5, 6);
    public static final RegistryObject<Block> MUNITIONS_BENCH_SUPERIOR = registerBench("munitions_bench_superior", 7, 8);
    public static final RegistryObject<Block> MUNITIONS_BENCH_TRANSCENDENT = registerBench("munitions_bench_transcendent", 9, 9);
    public static final RegistryObject<Block> MUNITIONS_BENCH_RADIANT = registerBench("munitions_bench_radiant", 10, 10);
    public static final RegistryObject<Block> GUNSMITH_PRESS = BLOCKS.register("gunsmith_press",
            () -> new GunsmithPressBlock(
                    BlockBehaviour.Properties.copy(Blocks.IRON_BLOCK)
                            .strength(4.5F, 9.0F)
                            .requiresCorrectToolForDrops()
                            .noOcclusion(),
                    () -> ModMunitionsBlockEntities.GUNSMITH_PRESS.get()));

    public static final List<RegistryObject<Block>> ALL_BENCHES = List.of(
            MUNITIONS_BENCH,
            MUNITIONS_BENCH_MEDIUM,
            MUNITIONS_BENCH_HIGH,
            MUNITIONS_BENCH_SUPERIOR,
            MUNITIONS_BENCH_TRANSCENDENT,
            MUNITIONS_BENCH_RADIANT);

    private static RegistryObject<Block> registerBench(String name, int unlockLevel, int maxEffectiveLevel) {
        return BLOCKS.register(name,
                () -> new MunitionsBenchBlock(
                        BlockBehaviour.Properties.copy(Blocks.SMITHING_TABLE).noOcclusion(),
                        () -> ModMunitionsBlockEntities.MUNITIONS_BENCH.get(),
                        unlockLevel,
                        maxEffectiveLevel));
    }

    public static Block[] allBenchBlocks() {
        return ALL_BENCHES.stream().map(RegistryObject::get).toArray(Block[]::new);
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
    }
}
