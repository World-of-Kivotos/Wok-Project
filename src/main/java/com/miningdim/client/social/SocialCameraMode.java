package com.miningdim.client.social;

import com.miningdim.client.webui.WebUiClient;
import com.miningdim.client.webui.WebUiScreen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;

/**
 * 社交平板的相机模式（纯客户端）。
 *
 * <p>入口：页面里的“打开相机”经 {@code client.openCamera} 到 {@link com.miningdim.client.webui.WebUiBridge}，
 * 再由 {@link WebUiClient} 调到 {@link #enter()}。进入后平板 Screen 关掉、不打开任何新 Screen，
 * WASD/鼠标保持正常游戏输入，玩家可以自由走动和转视角；HUD、手和手持物隐藏，只留取景 overlay。</p>
 *
 * <p>Enter 拍照（由 {@link #requestShoot()} 从 {@code SocialClient} 的快门 KeyMapping 触发），
 * Esc 退出（取消暂停界面并回平板）。拍照沿用 {@link WebUiClient#beginCameraShot(Runnable)}
 * 的两帧等待与截图管线；拍完由客户端重开同一个浏览器实例，页面收到
 * {@code miningdimOnPhotoCaptured} 后进入预览/发布。</p>
 */
public final class SocialCameraMode {

    private static boolean active;
    private static boolean capturing;
    private static boolean overlaySuppressed;

    private SocialCameraMode() {
    }

    public static void init(IEventBus forgeBus) {
        forgeBus.addListener(SocialCameraMode::onClientTick);
        forgeBus.addListener(SocialCameraMode::onScreenOpening);
        forgeBus.addListener(SocialCameraMode::onRenderOverlay);
        forgeBus.addListener(SocialCameraMode::onRenderGui);
        forgeBus.addListener(SocialCameraMode::onRenderHand);
    }

    public static boolean isActive() {
        return active;
    }

    /** 从社交平板进入相机模式；Screen 关闭但浏览器与页面保持存活（由 WebUiClient 的关闭清理挂起）。 */
    public static void enter() {
        Minecraft mc = Minecraft.getInstance();
        if (active || capturing) {
            return;
        }
        if (!(mc.screen instanceof WebUiScreen) || !WebUiClient.isSocialPageOpen()) {
            return;
        }
        active = true;
        WebUiClient.setScreenCloseSuspended(true);
        mc.setScreen(null);
    }

    /** 退出相机：reopen=true 回社交平板（无照片），false 只退出（死亡/切维度/被其它界面接替）。 */
    public static void exit(boolean reopen) {
        if (!active) {
            return;
        }
        active = false;
        capturing = false;
        overlaySuppressed = false;
        WebUiClient.setScreenCloseSuspended(false);
        if (reopen) {
            WebUiClient.reopenSocialScreenForCamera();
            WebUiClient.notifyCameraClosed();
        }
    }

    /** 由快门 KeyMapping（默认 Enter）调用。 */
    public static void requestShoot() {
        if (!active || capturing) {
            return;
        }
        capturing = true;
        overlaySuppressed = true;
        if (!WebUiClient.beginCameraShot(SocialCameraMode::onCaptureFinished)) {
            capturing = false;
            overlaySuppressed = false;
        }
    }

    /** 截图管线在重开平面前回调：先退出相机模式，避免 overlay 画到平板上。 */
    private static void onCaptureFinished() {
        active = false;
        capturing = false;
        overlaySuppressed = false;
        WebUiClient.setScreenCloseSuspended(false);
    }

    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !active) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.player.isDeadOrDying()) {
            exit(false);
            return;
        }
        if (mc.screen != null) {
            exit(false);
        }
    }

    /**
     * Esc 打开暂停界面时取消并回平板；背包/聊天等其它界面视为离开相机模式（让界面正常打开）。
     */
    private static void onScreenOpening(ScreenEvent.Opening event) {
        if (!active || event.getNewScreen() == null) {
            return;
        }
        if (event.getNewScreen() instanceof PauseScreen) {
            event.setCanceled(true);
            Minecraft.getInstance().execute(() -> exit(true));
            return;
        }
        exit(false);
    }

    /** 相机模式下隐藏全部原版/模组 GUI overlay（血条、快捷栏、聊天、准星等）。 */
    private static void onRenderOverlay(RenderGuiOverlayEvent.Pre event) {
        if (active) {
            event.setCanceled(true);
        }
    }

    /** 隐藏第一人称手和手持物，取景里只有世界。 */
    private static void onRenderHand(RenderHandEvent event) {
        if (active) {
            event.setCanceled(true);
        }
    }

    private static void onRenderGui(RenderGuiEvent.Post event) {
        if (!active || overlaySuppressed) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        GuiGraphics graphics = event.getGuiGraphics();
        int width = mc.getWindow().getGuiScaledWidth();
        int height = mc.getWindow().getGuiScaledHeight();
        int pad = 30;
        int grid = 0x33FFFFFF;

        for (int i = 1; i <= 2; i++) {
            int x = width * i / 3;
            graphics.fill(x, pad, x + 1, height - pad, grid);
            int y = height * i / 3;
            graphics.fill(pad, y, width - pad, y + 1, grid);
        }

        int len = 28;
        int thick = 2;
        int bracket = 0xCCFFFFFF;
        graphics.fill(pad, pad, pad + len, pad + thick, bracket);
        graphics.fill(pad, pad, pad + thick, pad + len, bracket);
        graphics.fill(width - pad - len, pad, width - pad, pad + thick, bracket);
        graphics.fill(width - pad - thick, pad, width - pad, pad + len, bracket);
        graphics.fill(pad, height - pad - thick, pad + len, height - pad, bracket);
        graphics.fill(pad, height - pad - len, pad + thick, height - pad, bracket);
        graphics.fill(width - pad - len, height - pad - thick, width - pad, height - pad, bracket);
        graphics.fill(width - pad - thick, height - pad - len, width - pad, height - pad, bracket);

        graphics.fill(0, 0, width, 38, 0x66000000);
        graphics.drawString(mc.font, Component.literal("HALOLINK · CAMERA"), 16, 12, 0xFFEAF7FF, true);
        graphics.drawString(mc.font, Component.literal("WASD 移动  ·  鼠标转视角"), 16, 48, 0x99EAF7FF, true);

        String hint = "ENTER 拍照     ESC 退出";
        int textWidth = mc.font.width(hint);
        int boxX = width / 2 - textWidth / 2 - 16;
        int boxY = height - 52;
        graphics.fill(boxX, boxY, boxX + textWidth + 32, height - 16, 0x66000000);
        graphics.drawString(mc.font, Component.literal(hint), width / 2 - textWidth / 2, height - 40,
                0xFFFFFFFF, true);

        graphics.fill(width / 2 - 8, height / 2, width / 2 + 9, height / 2 + 1, 0xAAFFFFFF);
        graphics.fill(width / 2, height / 2 - 8, width / 2 + 1, height / 2 + 9, 0xAAFFFFFF);
    }
}
