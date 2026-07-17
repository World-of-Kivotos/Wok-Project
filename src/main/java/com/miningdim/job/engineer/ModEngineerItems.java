package com.miningdim.job.engineer;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.engineer.armor.PlateArmorVariant;
import com.miningdim.job.engineer.armor.item.PlateArmorItem;
import com.miningdim.job.engineer.item.NanoArmorPlateItem;
import com.miningdim.job.engineer.shield.PlasmaShieldType;
import com.miningdim.job.engineer.shield.PlasmaShieldVariant;
import com.miningdim.job.engineer.shield.item.PlasmaShieldItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 工程师子系统自有物品 DeferredRegister: 六档纳米护甲板 + 六档生产台的 BlockItem (注册铁律: 自有
 * DeferredRegister, 不改中央 registry.ModItems)。
 *
 * 护甲板矿石配方 (4 铁/5 金/3 钻/下界合金锭) 是生产台机器内逻辑 + config, 非原版 data/recipes JSON
 * (绝不出工作台配方, 否则玩家绕过生产台合成护甲板, 破坏经济闸门; 见 NanoProduction)。生产台方块本身有
 * 工作台合成配方 (data/recipes JSON), 让玩家可造机器。
 *
 * BlockItem 构造在 lambda 内 .get() (注册后求值, 不在静态初始化期; 与 registry.ModItems 同范式)。
 */
public final class ModEngineerItems {

    private ModEngineerItems() {
    }

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, MiningConstants.MODID);

    /** 六档纳米护甲板物品, 按档索引。注册名: nano_plate_<tier>。 */
    private static final Map<NanoTier, RegistryObject<Item>> PLATES = new EnumMap<>(NanoTier.class);

    /** 六档生产台 BlockItem, 按档索引。注册名: production_table_<tier> (与方块同名)。 */
    private static final Map<NanoTier, RegistryObject<Item>> TABLE_ITEMS = new EnumMap<>(NanoTier.class);

    /** 54 件插板护甲外观；等级、类型、材料由 PlateArmorVariant 静态绑定。 */
    private static final Map<PlateArmorVariant, RegistryObject<Item>> PLATE_ARMORS =
            new EnumMap<>(PlateArmorVariant.class);

    /** Eighteen formal family/grade shields; registry identity is the trusted variant. */
    private static final Map<PlasmaShieldVariant, RegistryObject<Item>> PLASMA_SHIELDS =
            new EnumMap<>(PlasmaShieldVariant.class);

    /** Hidden compatibility items keep existing worlds, commands and datapacks from losing old IDs. */
    private static final Map<PlasmaShieldType, RegistryObject<Item>> LEGACY_PLASMA_SHIELDS =
            new EnumMap<>(PlasmaShieldType.class);

    static {
        for (NanoTier tier : NanoTier.values()) {
            final NanoTier t = tier;
            // 显式 Supplier<Item> 使 register 推断 I=Item, 返回 RegistryObject<Item> (与 Map 值类型对齐)。
            Supplier<Item> plateFactory = () -> new NanoArmorPlateItem(new Item.Properties().stacksTo(16), t);
            Supplier<Item> tableItemFactory = () -> new BlockItem(ModEngineerBlocks.table(t).get(), new Item.Properties());
            PLATES.put(t, ITEMS.register("nano_plate_" + t.name().toLowerCase(), plateFactory));
            TABLE_ITEMS.put(t, ITEMS.register("production_table_" + t.name().toLowerCase(), tableItemFactory));
        }
        for (PlateArmorVariant variant : PlateArmorVariant.values()) {
            PLATE_ARMORS.put(variant, ITEMS.register(variant.itemId(), () -> new PlateArmorItem(variant)));
        }
        for (PlasmaShieldVariant variant : PlasmaShieldVariant.values()) {
            PLASMA_SHIELDS.put(variant,
                    ITEMS.register(variant.itemId(), () -> new PlasmaShieldItem(variant)));
        }
        for (PlasmaShieldType legacyType : PlasmaShieldType.values()) {
            LEGACY_PLASMA_SHIELDS.put(legacyType,
                    ITEMS.register(legacyType.itemId(), () -> new PlasmaShieldItem(legacyType.variant())));
        }
    }

    /** 取某档护甲板物品 (注册后)。 */
    public static RegistryObject<Item> plate(NanoTier tier) {
        return PLATES.get(tier);
    }

    /** 取某档生产台 BlockItem (注册后)。 */
    public static RegistryObject<Item> tableItem(NanoTier tier) {
        return TABLE_ITEMS.get(tier);
    }

    public static RegistryObject<Item> plateArmor(PlateArmorVariant variant) {
        return PLATE_ARMORS.get(variant);
    }

    public static RegistryObject<Item> plasmaShield(PlasmaShieldVariant variant) {
        return PLASMA_SHIELDS.get(variant);
    }

    public static RegistryObject<Item> legacyPlasmaShield(PlasmaShieldType legacyType) {
        return LEGACY_PLASMA_SHIELDS.get(legacyType);
    }

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
    }
}
