package com.miningdim.job.engineer;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.engineer.block.ProductionTableBlock;
import com.miningdim.job.engineer.block.ProductionTableBlockEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 工程师子系统自有方块实体类型 DeferredRegister。六档生产台共用一个 {@link ProductionTableBlockEntity} 类型
 * (valid blocks = 六档生产台方块), 机器档由所属 {@link ProductionTableBlock} 读取, 不在 BE 另存 (单一真源)。
 *
 * build(null): 1.20.1 BlockEntityType.Builder.build 的 Type<?> 是 datafixer 类型, mod 方块实体允许 null
 * (无 vanilla datafixer 迁移需求; 与 registry.ModBlockEntities 同范式)。
 */
public final class ModEngineerBlockEntities {

    private ModEngineerBlockEntities() {
    }

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, MiningConstants.MODID);

    public static final RegistryObject<BlockEntityType<ProductionTableBlockEntity>> PRODUCTION_TABLE =
            BLOCK_ENTITIES.register("production_table",
                    () -> BlockEntityType.Builder.of(ProductionTableBlockEntity::new, validBlocks())
                            .build(null));

    /** 六档生产台方块均为 valid blocks (注册后求值, 故经 lambda 在 register 时刻收集)。 */
    private static Block[] validBlocks() {
        NanoTier[] tiers = NanoTier.values();
        Block[] blocks = new Block[tiers.length];
        for (int i = 0; i < tiers.length; i++) {
            blocks[i] = ModEngineerBlocks.table(tiers[i]).get();
        }
        return blocks;
    }

    public static void register(IEventBus modBus) {
        BLOCK_ENTITIES.register(modBus);
    }
}
