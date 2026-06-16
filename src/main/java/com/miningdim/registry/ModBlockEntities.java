package com.miningdim.registry;

import com.miningdim.core.MiningConstants;
import com.miningdim.entrance.EntranceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 方块实体类型的 DeferredRegister holder (R4)。阶段1 仅一个: 入口方块的 {@link EntranceBlockEntity},
 * 其 valid blocks 为三个难度入口方块 (Easy/Medium/Hard)。
 *
 * build(null): 1.20.1 BlockEntityType.Builder.build 的 Type<?> 是 datafixer 类型, 允许 null
 * (mod 方块实体无需 vanilla datafixer 迁移)。三入口块共用同一 BlockEntityType, 故同一 ticker 适配生效。
 */
public final class ModBlockEntities {

    private ModBlockEntities() {
    }

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, MiningConstants.MODID);

    /** 入口方块实体 (浮空字 + 触发冷却)。valid blocks = 三难度入口方块。 */
    public static final RegistryObject<BlockEntityType<EntranceBlockEntity>> ENTRANCE =
            BLOCK_ENTITIES.register("entrance",
                    () -> BlockEntityType.Builder.of(EntranceBlockEntity::new,
                            ModBlocks.ENTRANCE_EASY.get(),
                            ModBlocks.ENTRANCE_MEDIUM.get(),
                            ModBlocks.ENTRANCE_HARD.get()).build(null));

    public static void register(IEventBus modBus) {
        BLOCK_ENTITIES.register(modBus);
    }
}
