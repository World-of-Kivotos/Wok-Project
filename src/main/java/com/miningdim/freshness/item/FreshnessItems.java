package com.miningdim.freshness.item;

import com.miningdim.core.MiningConstants;
import net.minecraft.world.item.Item;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 腐烂底座自己的物品注册 (实现手册铁律: 不动中央 ModItems, 各子系统自持)。
 *
 * 只有一个物品: 腐烂终态 {@code freshness_rot}。
 * <ul>
 *   <li>64 个一组 (默认), 不能吃, 没有市场锚价 —— 所以所有「只认物品种类」的地方 (卖麦、酿酒、交任务、
 *       村民交易、配方) 自动拒收 (规格「为什么不只打标记」);</li>
 *   <li>故意做成**独立物品**而不是给原物品打标记: 卖麦、酿酒、交任务都只看物品种类, 打标记挡不住;</li>
 *   <li>也不直接变成腐肉: 日常任务 {@code daily.turnin.rotten} 收腐肉 ×64, 变腐肉等于白送那个任务。</li>
 * </ul>
 */
public final class FreshnessItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, MiningConstants.MODID);

    /** 腐烂物: 只能堆肥的终态。 */
    public static final RegistryObject<Item> ROT =
            ITEMS.register("freshness_rot", () -> new Item(new Item.Properties()));

    private FreshnessItems() {
    }

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
    }
}
