package com.miningdim.job.engineer;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.engineer.menu.ProductionTableMenu;
import com.miningdim.menu.ModMenus;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 工程师子系统自有 MenuType DeferredRegister。生产台 menu 经共享地基 {@link ModMenus#blockMenuType} 工厂
 * (extraData 首读 BlockPos, 与 ProductionTableBlock.use 的 buf.writeBlockPos 一一对应) 建类型。
 *
 * 单一 MenuType 服务全六档生产台 (档差异由 menu 据 BlockPos 取到的 BlockEntity/Block 读取, 不需六个 MenuType)。
 */
public final class ModEngineerMenus {

    private ModEngineerMenus() {
    }

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, MiningConstants.MODID);

    public static final RegistryObject<MenuType<ProductionTableMenu>> PRODUCTION_TABLE =
            MENUS.register("production_table",
                    () -> ModMenus.blockMenuType(ProductionTableMenu::new));

    public static void register(IEventBus modBus) {
        MENUS.register(modBus);
    }
}
