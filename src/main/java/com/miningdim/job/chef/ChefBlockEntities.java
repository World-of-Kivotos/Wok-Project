package com.miningdim.job.chef;

import com.miningdim.core.MiningConstants;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 厨师方块实体类型 holder。5 档调味台共用同一 {@link SeasoningTableBlockEntity} 类型 (valid blocks = 5 档),
 * 故同一 ticker 适配生效 (与 ModBlockEntities.ENTRANCE 同范式)。
 *
 * build(null): 1.20.1 datafixer Type 允许 null (mod 方块实体无 vanilla 迁移)。
 */
public final class ChefBlockEntities {

    private ChefBlockEntities() {
    }

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, MiningConstants.MODID);

    public static final RegistryObject<BlockEntityType<SeasoningTableBlockEntity>> SEASONING_TABLE =
            BLOCK_ENTITIES.register("seasoning_table",
                    () -> BlockEntityType.Builder.of(SeasoningTableBlockEntity::new,
                            ChefBlocks.SEASONING_TABLE_LOW.get(),
                            ChefBlocks.SEASONING_TABLE_MEDIUM.get(),
                            ChefBlocks.SEASONING_TABLE_HIGH.get(),
                            ChefBlocks.SEASONING_TABLE_EXTRAORDINARY.get(),
                            ChefBlocks.SEASONING_TABLE_RADIANT.get()).build(null));

    public static void register(IEventBus modBus) {
        BLOCK_ENTITIES.register(modBus);
    }
}
