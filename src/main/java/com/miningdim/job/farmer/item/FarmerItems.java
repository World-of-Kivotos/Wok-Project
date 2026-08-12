package com.miningdim.job.farmer.item;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.farmer.FarmerTier;
import com.miningdim.job.farmer.block.FarmerBlocks;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemNameBlockItem;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.EnumMap;
import java.util.Map;

/**
 * 农夫子系统专属物品 DeferredRegister (实现手册铁律: 不改中央 ModItems, 各子系统自持)。
 *
 * 注册集合:
 *  - {@link #FARMER_SEED}: mod 小麦种子 ({@link ItemNameBlockItem}, 右键 mod 耕地放下 mod 作物);
 *  - {@link #FARMER_WHEAT}: mod 小麦收获物 (新物品而非原版小麦, 经济收购需区分来源 -> 避免原版农业混入农夫 faucet);
 *  - 五档耕地的 {@link BlockItem} (可放置)。
 *
 * 收获物用新 mod 物品 (而非复用原版 minecraft:wheat) 的理由: spec 第八节经济收购按 "农夫小麦" 计当日卖菜量,
 * 若复用原版小麦则无法区分玩家手里的原版小麦与农夫小麦, NPC 收购 faucet 计数会被原版农业污染 (见 notes 裁决)。
 */
public final class FarmerItems {

    private FarmerItems() {
    }

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, MiningConstants.MODID);

    /**
     * mod 小麦种子。用 {@link ItemNameBlockItem}: 它放置 FARMER_CROP 但物品名走 item lang key (不走 block key),
     * 与原版小麦种子 (Items.WHEAT_SEEDS 同为 ItemNameBlockItem) 同范式。
     */
    public static final RegistryObject<Item> FARMER_SEED =
            ITEMS.register("farmer_seed",
                    () -> new ItemNameBlockItem(FarmerBlocks.FARMER_CROP.get(), new Item.Properties()));

    /** mod 小麦收获物 (经济收购计数物)。 */
    public static final RegistryObject<Item> FARMER_WHEAT =
            ITEMS.register("farmer_wheat",
                    () -> new Item(new Item.Properties()));

    private static final Map<FarmerTier, RegistryObject<Item>> FARMLAND_ITEMS = new EnumMap<>(FarmerTier.class);

    static {
        for (FarmerTier tier : FarmerTier.values()) {
            FARMLAND_ITEMS.put(tier, registerFarmlandItem(tier));
        }
    }

    private static RegistryObject<Item> registerFarmlandItem(FarmerTier tier) {
        return ITEMS.register("farmer_farmland_" + tier.id(),
                () -> new FarmerFarmlandItem(
                        FarmerBlocks.farmland(tier).get(), new Item.Properties(), tier));
    }

    /** 取某档耕地的 BlockItem RegistryObject。 */
    public static RegistryObject<Item> farmlandItem(FarmerTier tier) {
        RegistryObject<Item> ro = FARMLAND_ITEMS.get(tier);
        if (ro == null) {
            throw new IllegalStateException("No farmland item registered for tier " + tier);
        }
        return ro;
    }

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
    }
}
