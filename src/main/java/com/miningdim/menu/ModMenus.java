package com.miningdim.menu;

import com.miningdim.core.MiningConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.network.IContainerFactory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * menu 类型注册中心 (JobFramework_Shared_Foundation_DesignSpec 第六章)。持共享
 * {@code DeferredRegister<MenuType<?>>}; 各职业在自己的 register 内经本类的工厂助手登记自己的 MenuType。
 *
 * 第六章要求提供两类工厂变体, 本类以静态助手封装 {@link IForgeMenuType#create} 的两种 extraData 解码:
 *  - {@link #blockMenuType(BlockMenuFactory)}: 方块 menu —— extraData 首读 BlockPos (服务端 openScreen 写入),
 *    传给子类构造 (子类据此建 ContainerLevelAccess + MenuValidity.ofBlock)。工程师/塔罗/厨师方块台用。
 *  - {@link #remoteMenuType(RemoteMenuFactory)}: 非方块 menu (戒指远程开共享背包) —— extraData 无 BlockPos,
 *    透传 FriendlyByteBuf 给子类自解 (MarriageId/虚拟 owner), 子类建 MenuValidity.ofRemote 谓词。
 *
 * 不进 MiningServices (第六章: 各职业 register 内用)。客户端 MenuScreens.register 经 FMLClientSetupEvent
 * .enqueueWork (子类客户端 setup 负责, 见 AbstractMiningScreen 注释)。本类不预置任何具体 MenuType
 * (无主菜单壳), 各职业实现期经 {@code MENUS.register(name, () -> blockMenuType(MyMenu::new))} 登记。
 */
public final class ModMenus {

    private ModMenus() {
    }

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, MiningConstants.MODID);

    /** 接 modBus (在拥有者子系统 register 内调用一次)。 */
    public static void register(IEventBus modBus) {
        MENUS.register(modBus);
    }

    /** 方块 menu 构造工厂: 子类菜单据 windowId/玩家背包/方块坐标构造 (子类内建 ContainerLevelAccess + 校验)。 */
    @FunctionalInterface
    public interface BlockMenuFactory<T extends AbstractMiningMenu> {
        T create(int windowId, Inventory inv, BlockPos pos);
    }

    /** 非方块 (远程) menu 构造工厂: 子类据 windowId/玩家背包/原始 extraData 自解 (owner/MarriageId)。 */
    @FunctionalInterface
    public interface RemoteMenuFactory<T extends AbstractMiningMenu> {
        T create(int windowId, Inventory inv, FriendlyByteBuf extraData);
    }

    /**
     * 建一个方块 menu 的 MenuType (第六章方块工厂变体)。extraData 首读 BlockPos —— 与服务端 openScreen 写入
     * 的 {@code buf -> buf.writeBlockPos(pos)} 一一对应; 客户端据此重建同位置 menu。
     */
    public static <T extends AbstractMiningMenu> MenuType<T> blockMenuType(BlockMenuFactory<T> factory) {
        IContainerFactory<T> containerFactory =
                (windowId, inv, data) -> factory.create(windowId, inv, data.readBlockPos());
        return IForgeMenuType.create(containerFactory);
    }

    /**
     * 建一个非方块 (远程) menu 的 MenuType (第六章非方块工厂变体)。透传 extraData 给子类自解
     * (戒指远程开共享背包: 子类读 MarriageId/虚拟 owner)。
     */
    public static <T extends AbstractMiningMenu> MenuType<T> remoteMenuType(RemoteMenuFactory<T> factory) {
        IContainerFactory<T> containerFactory =
                (windowId, inv, data) -> factory.create(windowId, inv, data);
        return IForgeMenuType.create(containerFactory);
    }
}
