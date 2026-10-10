package com.miningdim.donation;

import com.miningdim.core.Subsystem;
import com.miningdim.registry.ModCreativeTabs;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * WOK-捐赠箱模块 (wok-donation) 的公开装配入口。
 *
 * 捐赠箱是自管区公共工程的物资池: 玩家把建材只进不出地放进去, 箱主与协管取用, 全程记流水查账。
 * 不产生任何回报 (不接经济模块、不发经验), 因此不经过收支总表的 faucet/sink 登记。
 *
 * register 内完成:
 *  - 本模块的 Block/Item/BlockEntityType DeferredRegister; 两个 MenuType 登记在共享 ModMenus 上 (touch 触发类加载);
 *  - 创造物品栏: 原版"功能方块"页与本 mod 主页;
 *  - Forge 总线: 破坏保护与 /donationbox 指令;
 *  - 模块自有 SimpleChannel (FMLCommonSetupEvent.enqueueWork);
 *  - 客户端界面注册 (FMLClientSetupEvent + DistExecutor 双箭头隔离, 专用服务器不触任何客户端类)。
 */
public final class DonationBoxModule implements Subsystem {

    public static final String MODULE_ID = "wok-donation";

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/donation");

    @Override
    public void register(IEventBus modBus, IEventBus forgeBus) {
        DonationRegistry.register(modBus);
        touch(DonationRegistry.DONOR_MENU);
        touch(DonationRegistry.MANAGER_MENU);

        modBus.addListener(this::onBuildCreativeTabs);
        modBus.addListener(this::onCommonSetup);
        modBus.addListener(this::onClientSetup);
        forgeBus.register(new DonationEvents());

        LOGGER.info("[miningdim] {} registered (donation box + ledger + /donationbox)", MODULE_ID);
    }

    private void onBuildCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (CreativeModeTabs.FUNCTIONAL_BLOCKS.equals(event.getTabKey())
                || ModCreativeTabs.MINING_TAB.getKey().equals(event.getTabKey())) {
            event.accept(DonationRegistry.DONATION_BOX_ITEM.get());
        }
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(DonationNetwork::register);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.miningdim.donation.client.DonationClientSetup.registerScreens()));
    }

    /** 触发 RegistryObject 所在类的静态初始化, 使其 MenuType 登记在 mod 总线注册事件之前进入共享 ModMenus。 */
    private static void touch(Object registryObject) {
        Objects.requireNonNull(registryObject);
    }

    @Override
    public String name() {
        return MODULE_ID;
    }
}
