package com.miningdim.job.tarot.client;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.tarot.TarotCardItem;
import com.miningdim.job.tarot.TarotQuality;
import com.miningdim.job.tarot.TarotRegistry;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * 塔罗师客户端注册 (TarotReader spec 第十一章: FMLClientSetupEvent)。
 *  - {@link ItemProperties} 品质边框 model predicate: 卡牌按 NBT quality ordinal 选不同 override 模型;
 *  - {@link MenuScreens} 屏幕注册: 合成 GUI + 闪耀自选 GUI。
 *
 * 只在客户端 modBus (Dist.CLIENT) 注册; 由 {@link com.miningdim.job.tarot.TarotSystem} 经
 * {@code DistExecutor}/FMLClientSetupEvent 隔离引用本类 (专用服务器不加载客户端类)。
 */
public final class TarotClientSetup {

    private TarotClientSetup() {
    }

    /** 卡牌品质 predicate id (model override 用): 返回 quality.ordinal()/10 的归一化值。 */
    private static final ResourceLocation QUALITY_PREDICATE =
            new ResourceLocation(MiningConstants.MODID, "tarot_quality");

    /** 在 modBus 挂客户端 setup 监听 (TarotSystem 仅在 Dist.CLIENT 调用)。 */
    public static void register(IEventBus modBus) {
        modBus.addListener(TarotClientSetup::onClientSetup);
    }

    private static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            // 品质边框 predicate: 卡牌模型按 quality ordinal 切 override (R/SR/SSR/UR/闪耀)。
            ItemProperties.register(TarotRegistry.TAROT_CARD.get(), QUALITY_PREDICATE,
                    (stack, level, entity, seed) -> {
                        if (stack.getTag() == null || !stack.getTag().contains("Quality")) {
                            return 0.0F;
                        }
                        TarotQuality q = TarotCardItem.quality(stack);
                        // 归一化到 [0,1) 区间, 每品质一档 (模型 override predicate 阈值在 model JSON 对齐)。
                        return q.ordinal() / 10.0F;
                    });

            MenuScreens.register(TarotRegistry.CRAFT_MENU.get(), TarotCraftScreen::new);
            MenuScreens.register(TarotRegistry.SHINY_SELECT_MENU.get(), ShinyPackSelectScreen::new);
        });
    }

    /** 是否客户端 (TarotSystem 调用前判定, 防专用服务器触链)。 */
    public static boolean isClient() {
        return net.minecraftforge.fml.loading.FMLEnvironment.dist == Dist.CLIENT;
    }
}
