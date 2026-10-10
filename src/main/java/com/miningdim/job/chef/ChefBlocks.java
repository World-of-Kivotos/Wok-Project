package com.miningdim.job.chef;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.chef.station.CookingStations;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.List;

/**
 * 厨师方块的 DeferredRegister holder (厨师包自有, 不碰中央 ModBlocks): 5 档调味台与 9 台烹饪台。
 *
 * 调味台 (Chef_Job_DesignSpec 第四章) 5 档 (低/中/高/超凡/闪耀) 各一方块, 携带本档品质上限
 * ({@link SeasoningTableBlock#tierCap})。属性 copy 原版 SMITHING_TABLE (工作台观感, 不可活塞推动)，
 * 模型使用本模块五档差异化正式纹理。烹饪台见下方 {@link #COOKING_STATIONS}。
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

    // 九台烹饪台 (炸锅 / 烤炉 / 备餐台 x 田园 rustic / 不锈钢 steel / 中式 chinese): 同一种台子三套外观功能相同。
    // 形状、遮挡、光照与粒子逐台照预览方案的实装说明, 写在 station.CookingStations; 本阶段没有方块实体。
    public static final RegistryObject<Block> DEEP_FRYER_RUSTIC =
            BLOCKS.register("deep_fryer_rustic", CookingStations::deepFryerRustic);
    public static final RegistryObject<Block> DEEP_FRYER_STEEL =
            BLOCKS.register("deep_fryer_steel", CookingStations::deepFryerSteel);
    public static final RegistryObject<Block> DEEP_FRYER_CHINESE =
            BLOCKS.register("deep_fryer_chinese", CookingStations::deepFryerChinese);
    public static final RegistryObject<Block> BAKING_OVEN_RUSTIC =
            BLOCKS.register("baking_oven_rustic", CookingStations::bakingOvenRustic);
    public static final RegistryObject<Block> BAKING_OVEN_STEEL =
            BLOCKS.register("baking_oven_steel", CookingStations::bakingOvenSteel);
    public static final RegistryObject<Block> BAKING_OVEN_CHINESE =
            BLOCKS.register("baking_oven_chinese", CookingStations::bakingOvenChinese);
    public static final RegistryObject<Block> PREP_COUNTER_RUSTIC =
            BLOCKS.register("prep_counter_rustic", CookingStations::prepCounterRustic);
    public static final RegistryObject<Block> PREP_COUNTER_STEEL =
            BLOCKS.register("prep_counter_steel", CookingStations::prepCounterSteel);
    public static final RegistryObject<Block> PREP_COUNTER_CHINESE =
            BLOCKS.register("prep_counter_chinese", CookingStations::prepCounterChinese);

    /** 九台烹饪台, 按 炸锅/烤炉/备餐台 x 田园/不锈钢/中式 排序 (创造标签页与 GameTest 共用这一顺序)。 */
    public static final List<RegistryObject<Block>> COOKING_STATIONS = List.of(
            DEEP_FRYER_RUSTIC, DEEP_FRYER_STEEL, DEEP_FRYER_CHINESE,
            BAKING_OVEN_RUSTIC, BAKING_OVEN_STEEL, BAKING_OVEN_CHINESE,
            PREP_COUNTER_RUSTIC, PREP_COUNTER_STEEL, PREP_COUNTER_CHINESE);

    private static BlockBehaviour.Properties tableProps() {
        return BlockBehaviour.Properties.copy(Blocks.SMITHING_TABLE).noOcclusion();
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
    }
}
