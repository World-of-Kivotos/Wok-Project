package com.miningdim.job.munitions;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.munitions.block.MunitionsBenchBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 军火商子系统自有方块实体类型 DeferredRegister。单一 {@link MunitionsBenchBlockEntity} 类型 (valid blocks =
 * 军火台方块)。
 *
 * build(null): 1.20.1 BlockEntityType.Builder.build 的 Type&lt;?&gt; 是 datafixer 类型, mod 方块实体允许 null
 * (无 vanilla datafixer 迁移需求; 与 ModEngineerBlockEntities 同范式)。
 */
public final class ModMunitionsBlockEntities {

    private ModMunitionsBlockEntities() {
    }

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, MiningConstants.MODID);

    public static final RegistryObject<BlockEntityType<MunitionsBenchBlockEntity>> MUNITIONS_BENCH =
            BLOCK_ENTITIES.register("munitions_bench",
                    () -> BlockEntityType.Builder.of(MunitionsBenchBlockEntity::new,
                            ModMunitionsBlocks.MUNITIONS_BENCH.get()).build(null));

    public static void register(IEventBus modBus) {
        BLOCK_ENTITIES.register(modBus);
    }
}
