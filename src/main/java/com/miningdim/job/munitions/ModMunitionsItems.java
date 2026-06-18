package com.miningdim.job.munitions;

import com.miningdim.core.MiningConstants;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 军火商子系统自有物品 DeferredRegister (注册铁律: 自有 DeferredRegister, 不改中央 registry.ModItems)。
 *
 * 物品清单:
 *  - 军火台 BlockItem (放置方块用);
 *  - 发射药中间品 propellant (四章 L6 提炼产物)。本工程把提炼建模为军火台内部数值翻倍 (直造 40 -> 提炼 70 发),
 *    propellant 作为玩家可见的提炼中间品物品出实体: 既是 L6 解锁的视觉标志, 也供未来 P2P/展示用。其堆叠/无功能
 *    (无 use 行为), 产线内部仍走纯数值翻倍 (见 MunitionsProduction.roundsPerBatch), 不强制玩家手搓发射药。
 *
 * 弹药本身不在此注册: 弹药是运行期由 {@link MunitionsAmmoFactory} 经 TACZ AmmoItemBuilder 物化的 tacz:ammo
 * ItemStack (单一 TACZ 物品 + AmmoId NBT), 军火商不注册自己的弹药 Item (与工程师六档护甲板 Item 不同)。
 */
public final class ModMunitionsItems {

    private ModMunitionsItems() {
    }

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, MiningConstants.MODID);

    /** 军火台 BlockItem。注册名: munitions_bench (与方块同名)。 */
    public static final RegistryObject<Item> MUNITIONS_BENCH_ITEM = ITEMS.register("munitions_bench",
            () -> new BlockItem(ModMunitionsBlocks.MUNITIONS_BENCH.get(), new Item.Properties()));

    /** 发射药中间品 (四章 L6 提炼产物; 纯堆叠物品, 无 use 行为)。注册名: propellant。 */
    public static final RegistryObject<Item> PROPELLANT = ITEMS.register("propellant",
            () -> new Item(new Item.Properties().stacksTo(64)));

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
    }
}
