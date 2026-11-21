package com.miningdim.freshness.client;

import com.miningdim.freshness.FreshnessModule;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

/**
 * 客户端装配 (规格「客户端显示」): tooltip、快捷栏提醒、客户端配置与断开清理。
 *
 * 单列一个类、并且**只由 {@link FreshnessModule} 里的 DistExecutor 调用**, 是为了保证专用服务器永远不碰
 * 这些类 (整包 client 类引用全集中在此, 与厨师那套同一纪律)。
 *
 * 断面清理很重要: 客户端缓存里的钟与保质期属于**上一个**服务器, 断线时必须清掉 —— 否则换服之后会拿旧服的
 * 钟去画新服的货, 显示出来的剩余时间全是错的。
 */
public final class FreshnessClientSetup {

    private FreshnessClientSetup() {
    }

    /** 配置必须在构造阶段登记，生命周期 setup 到来时配置加载已完成。 */
    public static void registerConfig() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT,
                FreshnessClientConfig.SPEC, "miningdim-freshness-client.toml");
    }

    public static void register() {
        MinecraftForge.EVENT_BUS.register(FreshnessClientEvents.class);
    }

    /** 客户端事件 (静态内部类, 免得客户端类被拆成十几个文件)。 */
    public static final class FreshnessClientEvents {

        private FreshnessClientEvents() {
        }

        @SubscribeEvent
        public static void onTooltip(ItemTooltipEvent event) {
            FreshnessTooltipHandler.onItemTooltip(event);
        }

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            FreshnessHudReminder.onClientTick(event);
        }

        @SubscribeEvent
        public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
            FreshnessClientState.clear();
        }
    }
}
