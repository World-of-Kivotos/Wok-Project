package com.miningdim.job.farmer.block;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.farmer.FarmerTier;
import com.miningdim.job.farmer.item.FarmerItems;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.EnumMap;
import java.util.Map;

/**
 * 农夫子系统专属方块 DeferredRegister (实现手册铁律: 不改中央 ModBlocks, 各子系统自持)。
 *
 * 注册集合:
 *  - 五档 {@link FarmerFarmlandBlock} (low/medium/high/premium/supreme), 各绑定一个 {@link FarmerTier};
 *  - 一个 {@link FarmerCropBlock} (mod 小麦), 成长速率/产量由其下方耕地档位动态决定 (非每档一作物)。
 *
 * 耕地属性: copy 原版 DIRT (硬度/音效合理默认), 但用更高档对应的 MapColor 区分外观 (玩家肉眼分档)。
 * 作物属性: copy 原版 WHEAT (无碰撞、随机刻、空手即破), 保证 CropBlock 成长/破坏链路与原版一致。
 */
public final class FarmerBlocks {

    private FarmerBlocks() {
    }

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, MiningConstants.MODID);

    private static final Map<FarmerTier, RegistryObject<Block>> FARMLAND = new EnumMap<>(FarmerTier.class);

    static {
        // 五档耕地: MapColor 随档位递进 (low 棕 -> supreme 蓝), 与原版 DIRT 物理属性一致。
        FARMLAND.put(FarmerTier.LOW, registerFarmland(FarmerTier.LOW, MapColor.DIRT));
        FARMLAND.put(FarmerTier.MEDIUM, registerFarmland(FarmerTier.MEDIUM, MapColor.COLOR_BROWN));
        FARMLAND.put(FarmerTier.HIGH, registerFarmland(FarmerTier.HIGH, MapColor.COLOR_GREEN));
        FARMLAND.put(FarmerTier.PREMIUM, registerFarmland(FarmerTier.PREMIUM, MapColor.GOLD));
        FARMLAND.put(FarmerTier.SUPREME, registerFarmland(FarmerTier.SUPREME, MapColor.COLOR_LIGHT_BLUE));
    }

    /** mod 小麦作物方块 (单一, 档位化成长)。种子物品延迟取自 {@link FarmerItems#FARMER_SEED}。 */
    public static final RegistryObject<Block> FARMER_CROP =
            BLOCKS.register("farmer_crop",
                    () -> new FarmerCropBlock(
                            BlockBehaviour.Properties.copy(Blocks.WHEAT),
                            FarmerItems.FARMER_SEED));

    private static RegistryObject<Block> registerFarmland(FarmerTier tier, MapColor mapColor) {
        return BLOCKS.register("farmer_farmland_" + tier.id(),
                () -> new FarmerFarmlandBlock(
                        BlockBehaviour.Properties.copy(Blocks.DIRT).mapColor(mapColor),
                        tier));
    }

    /** 取某档耕地的 RegistryObject。 */
    public static RegistryObject<Block> farmland(FarmerTier tier) {
        RegistryObject<Block> ro = FARMLAND.get(tier);
        if (ro == null) {
            throw new IllegalStateException("No farmland block registered for tier " + tier);
        }
        return ro;
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
    }
}
