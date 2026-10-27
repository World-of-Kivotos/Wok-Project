package com.miningdim.job.chef.zhangshao.client;

import com.miningdim.core.MiningConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = MiningConstants.MODID, value = Dist.CLIENT)
public final class ZhangshaoPracticeButtons {
    private ZhangshaoPracticeButtons() {}

    @SubscribeEvent
    public static void addPotPractice(ScreenEvent.Init.Post event) {
        // 可选 MOD 边界只匹配其界面名，未安装 FD 时不解析任何 FD 类型。
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen)
                || !screen.getClass().getName().equals("vectorwing.farmersdelight.client.gui.CookingPotScreen")) {
            return;
        }
        event.addListener(Button.builder(Component.literal("练习"), button -> {
            Minecraft client = Minecraft.getInstance();
            // 练习没有服务端容器，先正常关闭厨锅，保留 FD 自己的库存和烹饪进度。
            client.player.closeContainer();
            client.setScreen(new ZhangshaoPracticeScreen(0));
        }).bounds(Math.min(screen.width - 52, screen.getGuiLeft() + screen.getXSize() + 4),
                screen.getGuiTop(), 48, 20).build());
    }
}
