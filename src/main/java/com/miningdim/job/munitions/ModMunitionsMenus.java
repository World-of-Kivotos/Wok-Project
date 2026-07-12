package com.miningdim.job.munitions;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.munitions.menu.GunsmithPressMenu;
import com.miningdim.job.munitions.menu.MunitionsBenchMenu;
import com.miningdim.menu.ModMenus;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 军火商子系统自有 MenuType DeferredRegister。军火台 menu 经共享地基 {@link ModMenus#blockMenuType} 工厂
 * (extraData 首读 BlockPos, 与 MunitionsBenchBlock.use 的 buf.writeBlockPos 一一对应) 建类型。
 */
public final class ModMunitionsMenus {

    private ModMunitionsMenus() {
    }

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, MiningConstants.MODID);

    public static final RegistryObject<MenuType<MunitionsBenchMenu>> MUNITIONS_BENCH =
            MENUS.register("munitions_bench",
                    () -> ModMenus.blockMenuType(MunitionsBenchMenu::new));
    public static final RegistryObject<MenuType<GunsmithPressMenu>> GUNSMITH_PRESS =
            MENUS.register("gunsmith_press",
                    () -> ModMenus.blockMenuType(GunsmithPressMenu::new));

    public static void register(IEventBus modBus) {
        MENUS.register(modBus);
    }
}
