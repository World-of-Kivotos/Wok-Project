package com.miningdim.marriage;

import com.miningdim.menu.ModMenus;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.registries.RegistryObject;

/**
 * 结婚系统 DeferredRegister 持有 (任务铁律: 各子系统自持注册, 严禁改中央 ModMenus 列表)。结婚阶段 2 唯一需注册的
 * 客户端可见对象是共享背包的 {@link MenuType} (远程 menu, 戒指蹲下右键开): 经共享 {@link ModMenus#MENUS} 登记
 * (范式同 {@code AgentRegistry.SCAN_MENU})。
 *
 * 戒指物品在中央 {@link com.miningdim.registry.ModItems} 注册 (阶段 1 已落, 随 modBus 走), 不在此; 婚姻关系
 * SavedData / capability 指针随 entry 唯一权威走, 也不在此; 本类只承载共享背包 MenuType 登记。
 */
public final class MarriageRegistration {

    private MarriageRegistration() {
    }

    /** 共享背包 MenuType (远程 menu; extraData = marriageId(long) + visibleSlots(varInt))。 */
    public static final RegistryObject<MenuType<MarriageBackpackMenu>> BACKPACK_MENU =
            ModMenus.MENUS.register("marriage_backpack",
                    () -> ModMenus.remoteMenuType(MarriageBackpackMenu::new));
}
