package com.miningdim.job.agent;

import com.miningdim.job.agent.panel.AgentScanMenu;
import com.miningdim.menu.ModMenus;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.registries.RegistryObject;

/**
 * 特勤干员 DeferredRegister 持有 (任务铁律: 各子系统自持注册, 严禁改中央 ModItems/ModBlocks/ModMenus 列表)。
 * 特勤目前唯一需注册的客户端可见对象是战术扫描面板的 {@link MenuType} (远程 menu, 无方块/物品): 经共享
 * {@link ModMenus#MENUS} 登记 (范式同 {@code TarotRegistry.SHINY_SELECT_MENU})。
 *
 * 等级/经验/悬赏数据走自有 capability/SavedData (见 {@link AgentSystem}), 不在此; 本类只承载 MenuType 登记。
 */
public final class AgentRegistry {

    private AgentRegistry() {
    }

    /** 战术扫描面板 MenuType (远程 menu; 无方块/extraData, 快照走独立 S2C)。 */
    public static final RegistryObject<MenuType<AgentScanMenu>> SCAN_MENU =
            ModMenus.MENUS.register("agent_scan",
                    () -> ModMenus.remoteMenuType(AgentScanMenu::new));
}
