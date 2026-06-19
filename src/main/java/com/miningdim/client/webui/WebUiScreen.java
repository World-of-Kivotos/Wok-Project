package com.miningdim.client.webui;

import javax.annotation.Nullable;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Web UI 宿主界面 (客户端宿主, 仅 Dist.CLIENT classload)。抢救自 BrowserScreen, 修下列已知 bug 并对齐 1.20.1:
 *
 * 1) 鼠标按钮映射: 原版本把原始 GLFW button 直传 sendMousePress, 导致右键/中键在网页里互换;
 *    本版本一律经 {@link WebUiInput#toCefMouseButton} 映射为 CEF 编号 (left0/middle1/right2)。
 * 2) DPI/坐标三件套: init 按帧缓冲分辨率 (mc.getWindow().getWidth/Height) resize 浏览器避免模糊;
 *    render 按 GUI scale 全屏铺满; 鼠标坐标 (GUI 坐标系) 经 {@link #toPixelX}/{@link #toPixelY}
 *    线性映射回帧缓冲像素再喂给 CEF。
 * 3) 打开即 setFocus(true); ESC 关闭; isPauseScreen()=false (公服不暂停)。
 *
 * 渲染全屏铺满 (displayScale 固定 1.0): 本步是开发宿主, 不引入配置缩放; 生产前端如需边框/分屏由后续步骤扩展。
 *
 * 中文 IME (step2 接口位): 完整 IME 需叠加一个不可见原版 EditBox 捕获 GLFW IME 组字事件 (preedit/commit),
 * 再把已上屏字符经 {@link WebBrowser#sendKeyTyped} 注入 CEF。本步仅在 {@link #charTyped} 直接转发 BMP 字符
 * (英文/已上屏单字可用); 组字态中文留待 step2 在此叠加 EditBox。见 charTyped 处标注。
 */
public final class WebUiScreen extends Screen {

    @Nullable
    private final WebBrowser browser;

    private final WebUiInput input = new WebUiInput();

    // 帧缓冲实际像素尺寸 (考虑系统 DPI / GUI scale 的真实渲染分辨率); CEF 离屏渲染按此尺寸, 避免模糊。
    private int pixelWidth;
    private int pixelHeight;

    public WebUiScreen(@Nullable WebBrowser browser) {
        super(Component.literal("MiningDim WebUI"));
        this.browser = browser;
    }

    @Override
    protected void init() {
        super.init();
        Minecraft mc = Minecraft.getInstance();
        pixelWidth = mc.getWindow().getWidth();
        pixelHeight = mc.getWindow().getHeight();
        if (browser != null) {
            browser.resize(pixelWidth, pixelHeight);
            // 打开时聚焦, 保证键盘/输入直达网页 (否则首帧无光标/不响应键入)。
            browser.setFocus(true);
        }
    }

    @Override
    public void resize(Minecraft mc, int newWidth, int newHeight) {
        super.resize(mc, newWidth, newHeight);
        pixelWidth = mc.getWindow().getWidth();
        pixelHeight = mc.getWindow().getHeight();
        if (browser != null) {
            browser.resize(pixelWidth, pixelHeight);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (browser != null && browser.isReady()) {
            int textureId = browser.getTextureId();
            if (textureId > 0) {
                // 全屏铺满 (GUI 坐标系 0..width/height); WebBrowser 内部按帧缓冲像素采样贴图。
                browser.render(graphics, 0, 0, this.width, this.height);
            } else {
                graphics.drawCenteredString(this.font, "Loading WebUI...",
                        this.width / 2, this.height / 2, 0xFFFFFF);
            }
        } else {
            graphics.drawCenteredString(this.font, "WebUI browser not ready",
                    this.width / 2, this.height / 2, 0xFFFF00);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    // ---- 坐标换算: GUI 坐标系 (width/height) -> 帧缓冲像素 (pixelWidth/pixelHeight) ----

    private int toPixelX(double guiX) {
        if (this.width <= 0) {
            return 0;
        }
        return (int) (guiX * pixelWidth / this.width);
    }

    private int toPixelY(double guiY) {
        if (this.height <= 0) {
            return 0;
        }
        return (int) (guiY * pixelHeight / this.height);
    }

    // ---- 鼠标事件 (button 一律经 toCefMouseButton 映射) ----

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (browser != null) {
            browser.sendMousePress(toPixelX(mouseX), toPixelY(mouseY), input.toCefMouseButton(button));
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (browser != null) {
            browser.sendMouseRelease(toPixelX(mouseX), toPixelY(mouseY), input.toCefMouseButton(button));
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        if (browser != null) {
            browser.sendMouseMove(toPixelX(mouseX), toPixelY(mouseY));
        }
        super.mouseMoved(mouseX, mouseY);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (browser != null) {
            browser.sendMouseMove(toPixelX(mouseX), toPixelY(mouseY));
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (browser != null) {
            // 放大滚轮步进到像素级 (原版 delta 为 +-1 行, CEF 期望像素偏移); 附带当前修饰键状态。
            double pixelDelta = delta * 40.0;
            browser.sendMouseWheel(toPixelX(mouseX), toPixelY(mouseY), pixelDelta, input.currentModifiers());
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    // ---- 键盘事件 ----

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (browser != null) {
            input.updateModifiers(keyCode, true);
            browser.sendKeyPress(keyCode, scanCode, modifiers);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (browser != null) {
            input.updateModifiers(keyCode, false);
            browser.sendKeyRelease(keyCode, scanCode, modifiers);
            return true;
        }
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (browser != null) {
            // step2 中文 IME 接口位: 完整组字 (preedit) 需在此 Screen 叠加一个隐藏 EditBox, 接 GLFW IME
            // 组字/上屏事件, 再把 commit 的字符序列经 sendKeyTyped 注入。本步仅转发单 BMP 字符 (英文/已上屏单字),
            // 不处理组字中间态。
            browser.sendKeyTyped(codePoint, modifiers);
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    // ---- 生命周期 ----

    @Override
    public void onClose() {
        if (browser != null) {
            // 失焦并清修饰键, 防卡键; 不在此 close 浏览器 (浏览器由 WebUiClient 持有复用, 仅隐藏界面)。
            browser.setFocus(false);
            input.reset();
        }
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
