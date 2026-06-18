package com.miningdim.job.chef;

import com.miningdim.core.MiningConstants;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 厨师 5 档调味台方块的 DeferredRegister holder (Chef_Job_DesignSpec 第四章; 厨师包自有, 不碰中央 ModBlocks)。
 *
 * 5 档 (低/中/高/超凡/闪耀) 各一方块, 携带本档品质上限 ({@link SeasoningTableBlock#tierCap})。属性 copy 原版
 * SMITHING_TABLE (工作台观感, 不可活塞推动), 不需新 PNG, 模型 JSON 引用现有 vanilla 纹理。
 */
public final class ChefBlocks {

    private ChefBlocks() {
    }

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, MiningConstants.MODID);

    public static final RegistryObject<Block> SEASONING_TABLE_LOW =
            BLOCKS.register("seasoning_table_low",
                    () -> new SeasoningTableBlock(tableProps(), ChefQuality.LOW));
    public static final RegistryObject<Block> SEASONING_TABLE_MEDIUM =
            BLOCKS.register("seasoning_table_medium",
                    () -> new SeasoningTableBlock(tableProps(), ChefQuality.MEDIUM));
    public static final RegistryObject<Block> SEASONING_TABLE_HIGH =
            BLOCKS.register("seasoning_table_high",
                    () -> new SeasoningTableBlock(tableProps(), ChefQuality.HIGH));
    public static final RegistryObject<Block> SEASONING_TABLE_EXTRAORDINARY =
            BLOCKS.register("seasoning_table_extraordinary",
                    () -> new SeasoningTableBlock(tableProps(), ChefQuality.EXTRAORDINARY));
    public static final RegistryObject<Block> SEASONING_TABLE_RADIANT =
            BLOCKS.register("seasoning_table_radiant",
                    () -> new SeasoningTableBlock(tableProps(), ChefQuality.RADIANT));

    private static BlockBehaviour.Properties tableProps() {
        return BlockBehaviour.Properties.copy(Blocks.SMITHING_TABLE);
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
    }
}
