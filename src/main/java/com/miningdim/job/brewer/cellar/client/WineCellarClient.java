package com.miningdim.job.brewer.cellar.client;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.brewer.cellar.WineCellarRegistry;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 酒窖箱客户端 setup (仅客户端逻辑端加载; 集成者经 BrewerSystem 的 FMLClientSetupEvent + DistExecutor 隔离调
 * {@link #registerScreens()}, 范式同 {@link com.miningdim.job.chef.client.ChefClientSetup})。把酒窖箱 MenuType
 * 绑定到 {@link WineCellarScreen}。
 *
 * 单列客户端类 (而非内联 lambda 进系统入口) 以保证客户端类引用集中本客户端包, 专用服务器永不触类。
 */
public final class WineCellarClient {

    private WineCellarClient() {
    }

    public static void registerScreens() {
        MenuScreens.register(WineCellarRegistry.WINE_CELLAR_MENU.get(), WineCellarScreen::new);
    }

    /**
     * 动态酒瓶必须经 MOD bus 的 RegisterRenderers 事件登记; 不能塞进通用 setup 的任意时序里。
     */
    @Mod.EventBusSubscriber(modid = MiningConstants.MODID, value = Dist.CLIENT,
            bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class RendererRegistration {

        private RendererRegistration() {
        }

        @SubscribeEvent
        public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
            event.registerBlockEntityRenderer(WineCellarRegistry.WINE_CELLAR_BE.get(),
                    WineCellarRenderer::new);
        }
    }
}
