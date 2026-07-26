package com.miningdim.job.tarot.client;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.tarot.TarotCardItem;
import com.miningdim.job.tarot.TarotQuality;
import com.miningdim.job.tarot.TarotRegistry;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientTooltipComponentFactoriesEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * 塔罗师客户端注册 (TarotReader spec 第十一章: FMLClientSetupEvent)。
 *  - {@link ItemProperties} 牌面与品质 model predicate: 卡牌按 NBT cardId + quality 选择分层模型;
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

    /** 卡牌编号 predicate id: 返回 (cardId+1)/100, 供 22 张基础牌面模型选择。 */
    private static final ResourceLocation CARD_PREDICATE =
            new ResourceLocation(MiningConstants.MODID, "tarot_card");

    /** 正位=0.1、逆位=0.2，供物品栏和手持模型选择真实翻转后的卡面。 */
    private static final ResourceLocation ORIENTATION_PREDICATE =
            new ResourceLocation(MiningConstants.MODID, "tarot_orientation");

    /** 在 modBus 挂客户端 setup 监听 (TarotSystem 仅在 Dist.CLIENT 调用)。 */
    public static void register(IEventBus modBus, IEventBus forgeBus) {
        modBus.addListener(TarotClientSetup::onClientSetup);
        modBus.addListener(TarotClientSetup::onRegisterTooltipFactories);
        ClientTarotVisuals.register(forgeBus);
    }

    private static void onRegisterTooltipFactories(RegisterClientTooltipComponentFactoriesEvent event) {
        event.register(com.miningdim.job.tarot.TarotCardTooltip.class, ClientTarotCardTooltip::new);
    }

    private static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            // 两个 predicate 共同选择 22 牌面 x 5 品质的分层模型。加一档让 0.0 专用于缺失 NBT。
            ItemProperties.register(TarotRegistry.TAROT_CARD.get(), CARD_PREDICATE,
                    (stack, level, entity, seed) -> {
                        if (stack.getTag() == null || !stack.getTag().contains("CardId")) {
                            return 0.0F;
                        }
                        return (TarotCardItem.cardId(stack) + 1) / 100.0F;
                    });

            ItemProperties.register(TarotRegistry.TAROT_CARD.get(), QUALITY_PREDICATE,
                    (stack, level, entity, seed) -> {
                        if (stack.getTag() == null || !stack.getTag().contains("Quality")) {
                            return 0.0F;
                        }
                        TarotQuality q = TarotCardItem.quality(stack);
                        return (q.ordinal() + 1) / 10.0F;
                    });

            ItemProperties.register(TarotRegistry.TAROT_CARD.get(), ORIENTATION_PREDICATE,
                    (stack, level, entity, seed) -> {
                        if (stack.getTag() == null || !stack.getTag().contains("Upright")) {
                            return 0.0F;
                        }
                        return TarotCardItem.upright(stack) ? 0.1F : 0.2F;
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
