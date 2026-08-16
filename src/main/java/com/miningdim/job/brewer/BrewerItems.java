package com.miningdim.job.brewer;

import com.miningdim.core.MiningConstants;
import net.minecraft.world.item.Item;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.EnumMap;
import java.util.Map;

/**
 * 酿酒师物品的 DeferredRegister holder (酿酒师包自有, 不碰中央 ModItems)。
 *
 *  - dried_wheat 干小麦: 酒窖箱保鲜燃料 (由小麦烘制);
 *  - 九种酒各一 {@link WineItem} (wine_<type>): 可饮, 品质/年份存 NBT。
 *
 * 物品注册在静态块内按 {@link WineType} 循环登记 (lambda 捕获每轮 effectively-final 的 type); 遵循工程范式禁
 * 静态初始化期 .get() —— 注册在 lambda 内求值。
 */
public final class BrewerItems {

    private BrewerItems() {
    }

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, MiningConstants.MODID);

    /**
     * 干小麦: 酒窖箱保鲜燃料 (要求量大, 联动农夫小麦经济)。stacksTo 顶到
     * {@link BrewerConstants#FUEL_SLOT_CAPACITY} (F027 二段修复): 与酒窖箱燃料槽的 getSlotLimit 覆写配套,
     * 单槽一次即可覆盖满窖 (12 瓶) 停在闪耀主线最低门槛 (vintage 10) 时一个结算步 (1 现实天) 的满额应耗,
     * 见该常量 javadoc 的推导。
     */
    public static final RegistryObject<Item> DRIED_WHEAT =
            ITEMS.register("dried_wheat",
                    () -> new Item(new Item.Properties().stacksTo(BrewerConstants.FUEL_SLOT_CAPACITY)));

    /** 九种酒 item (类型 -> RegistryObject)。 */
    private static final Map<WineType, RegistryObject<Item>> WINES = new EnumMap<>(WineType.class);

    static {
        for (WineType type : WineType.values()) {
            WINES.put(type, ITEMS.register(type.itemRegistryName(), () -> new WineItem(new Item.Properties(), type)));
        }
    }

    /** 取某类型的酒 item (注册后调用; 创造栏/配方产出用)。 */
    public static Item itemFor(WineType type) {
        return WINES.get(type).get();
    }

    /** 某 ItemStack 是哪种酒 (非酒 item 返回 null)。 */
    public static WineType typeOf(net.minecraft.world.item.ItemStack stack) {
        return stack.getItem() instanceof WineItem wine ? wine.wineType() : null;
    }

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
    }
}
