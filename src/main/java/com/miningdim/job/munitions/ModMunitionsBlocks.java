package com.miningdim.job.munitions;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.munitions.block.MunitionsBenchBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 军火商子系统自有方块 DeferredRegister (注册铁律: 各子系统在自己 package 持有自己的 DeferredRegister, 严禁改
 * 中央 registry.ModBlocks)。
 *
 * 单档军火台 (Munitions_Job_DesignSpec 五/十章): 与工程师六档生产台不同, 军火台只一种方块 —— 可造口径上限由
 * 军火商职业等级门控 (6.1 口径等级门, 不靠不同方块区分), 制造台拥有数由 {@link MunitionsSavedData} 按等级上限校验。
 * 故无需按档多方块。属性 copy 原版 SMITHING_TABLE (坚固木质台, 不需新 PNG; 模型 JSON 借 vanilla 纹理)。
 */
public final class ModMunitionsBlocks {

    private ModMunitionsBlocks() {
    }

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, MiningConstants.MODID);

    /** 军火台方块。注册名: munitions_bench。 */
    public static final RegistryObject<Block> MUNITIONS_BENCH = BLOCKS.register("munitions_bench",
            () -> new MunitionsBenchBlock(
                    BlockBehaviour.Properties.copy(Blocks.SMITHING_TABLE).noOcclusion(),
                    () -> ModMunitionsBlockEntities.MUNITIONS_BENCH.get()));

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
    }
}
