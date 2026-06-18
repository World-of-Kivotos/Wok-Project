package com.miningdim.job.engineer;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.engineer.block.ProductionTableBlock;
import com.miningdim.job.engineer.block.ProductionTableBlockEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 工程师子系统自有方块 DeferredRegister (注册铁律: 各子系统在自己 package 持有自己的 DeferredRegister,
 * 严禁改中央 registry.ModBlocks)。六档生产台各一个独立方块 (规格 3/4 章按六档机器论述), 机器档构造时绑定。
 *
 * 六档生产台共用同一 {@link ProductionTableBlockEntity} 类型 (经 {@link ModEngineerBlockEntities}), 故同一
 * ticker 适配生效。属性 copy 原版 ANVIL (坚固、需镐), 不需新 PNG (模型 JSON 引用现有 vanilla 纹理)。
 */
public final class ModEngineerBlocks {

    private ModEngineerBlocks() {
    }

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, MiningConstants.MODID);

    /** 各档生产台方块, 按机器档索引。注册名: production_table_<tier>。 */
    private static final Map<NanoTier, RegistryObject<Block>> TABLES = new EnumMap<>(NanoTier.class);

    static {
        for (NanoTier tier : NanoTier.values()) {
            final NanoTier t = tier;
            // BE 类型供给延迟到注册后求值 (ModEngineerBlockEntities.PRODUCTION_TABLE 同窗口注册)。
            Supplier<BlockEntityType<ProductionTableBlockEntity>> beType =
                    () -> ModEngineerBlockEntities.PRODUCTION_TABLE.get();
            // 显式 Supplier<Block> 使 register 推断 I=Block, 返回 RegistryObject<Block> (与 Map 值类型对齐)。
            Supplier<Block> factory = () -> new ProductionTableBlock(
                    BlockBehaviour.Properties.copy(Blocks.ANVIL).noOcclusion(), t, beType);
            TABLES.put(t, BLOCKS.register("production_table_" + t.name().toLowerCase(), factory));
        }
    }

    /** 取某档生产台方块 (注册后)。 */
    public static RegistryObject<Block> table(NanoTier tier) {
        return TABLES.get(tier);
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
    }
}
