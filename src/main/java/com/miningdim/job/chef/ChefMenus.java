package com.miningdim.job.chef;

import com.miningdim.menu.ModMenus;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.registries.RegistryObject;

/**
 * 厨师 MenuType holder (Chef_Job_DesignSpec 第四章; 复用共享 {@link ModMenus} DeferredRegister + 方块工厂)。
 *
 * 经 {@link ModMenus#blockMenuType} 注册: extraData 首读 BlockPos (服务端 openScreen 写入), 走客户端
 * {@link SeasoningMenu#SeasoningMenu(int, net.minecraft.world.entity.player.Inventory, net.minecraft.core.BlockPos)}
 * 构造。共享 ModMenus.MENUS 由 JobFrameworkSystem 接 modBus; 厨师只往其上登记自己的 MenuType (无需另接 modBus)。
 */
public final class ChefMenus {

    private ChefMenus() {
    }

    public static final RegistryObject<MenuType<SeasoningMenu>> SEASONING_MENU =
            ModMenus.MENUS.register("seasoning_table",
                    () -> ModMenus.blockMenuType(SeasoningMenu::new));
}
