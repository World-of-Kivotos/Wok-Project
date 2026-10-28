package com.miningdim.job.chef.zhangshao.client;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.chef.pot.client.PotClient;
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
        event.addListener(Button.builder(Component.literal("掌勺"), button -> PotClient.start(screen))
                .bounds(Math.min(screen.width - 52, screen.getGuiLeft() + screen.getXSize() + 4),
                        screen.getGuiTop(), 48, 20).build());
        event.addListener(Button.builder(Component.literal("练习"), button -> {
            Minecraft client = Minecraft.getInstance();
            // 练习没有服务端容器，先正常关闭厨锅，保留 FD 自己的库存和烹饪进度。
            client.player.closeContainer();
            client.setScreen(new ZhangshaoPracticeScreen(0));
        }).bounds(Math.min(screen.width - 52, screen.getGuiLeft() + screen.getXSize() + 4),
                screen.getGuiTop() + 22, 48, 20).build());
    }
    @SubscribeEvent
    public static void renderPot(ScreenEvent.Render.Post event) {
        if (isPot(event.getScreen())) PotClient.render(event.getScreen(), event.getGuiGraphics());
    }

    @SubscribeEvent
    public static void keyPressed(ScreenEvent.KeyPressed.Pre event) {
        if (isPot(event.getScreen()) && PotClient.key(event.getScreen(), event.getKeyCode(), true)) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void keyReleased(ScreenEvent.KeyReleased.Pre event) {
        if (isPot(event.getScreen()) && PotClient.key(event.getScreen(), event.getKeyCode(), false)) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void mousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (isPot(event.getScreen()) && PotClient.mouse(event.getScreen(), event.getButton(), true)) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void mouseReleased(ScreenEvent.MouseButtonReleased.Pre event) {
        if (isPot(event.getScreen()) && PotClient.mouse(event.getScreen(), event.getButton(), false)) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void closed(ScreenEvent.Closing event) {
        if (isPot(event.getScreen())) PotClient.closed(event.getScreen());
    }

    private static boolean isPot(net.minecraft.client.gui.screens.Screen screen) {
        return screen.getClass().getName().equals("vectorwing.farmersdelight.client.gui.CookingPotScreen");
    }
}