package com.miningdim.job.farmer.granary;

import com.miningdim.core.MiningConstants;
import com.miningdim.menu.ModMenus;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 粮仓的 MenuType 登记 (Spoilage_Granary_DesignSpec「界面」)。
 *
 * 本片只有**储粮囤自己**的 27 格界面 (账台的多页签界面是另一批)。菜单用共享脚手架的
 * {@link ModMenus#blockMenuType} 工厂: 服务端 openScreen 写 BlockPos, 客户端据此重建同一个 menu。
 */
public final class GranaryMenus {

    private static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, MiningConstants.MODID);

    /** 储粮囤的 27 格界面。 */
    public static final RegistryObject<MenuType<GranaryBinMenu>> BIN =
            MENUS.register("granary_bin", () -> ModMenus.blockMenuType(GranaryBinMenu::new));

    private GranaryMenus() {
    }

    /** 农夫模块装配时调用 (GranarySystem.register 内)。 */
    public static void register(IEventBus modBus) {
        MENUS.register(modBus);
    }

    /** 触发本类的静态初始化, 让上面的 RegistryObject 被收集 (范式见 ChefSystem.touch)。 */
    public static void touch() {
        java.util.Objects.requireNonNull(BIN);
    }
}
