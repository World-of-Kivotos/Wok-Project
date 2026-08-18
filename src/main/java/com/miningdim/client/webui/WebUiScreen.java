package com.miningdim.client.webui;

import javax.annotation.Nullable;

import org.lwjgl.glfw.GLFW;

import com.miningdim.config.MiningClientConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Web UI 宿主界面 (客户端宿主, 仅 Dist.CLIENT classload)。抢救自 BrowserScreen, 修下列已知 bug 并对齐 1.20.1:
 *
 * 1) 鼠标按钮映射: 原版本把原始 GLFW button 直传 sendMousePress, 导致右键/中键在网页里互换;
 *    本版本一律经 {@link WebUiInput#toCefMouseButton} 映射为 CEF 编号 (left0/middle1/right2)。
 * 2) DPI/坐标三件套: {@link #layout} 按<b>面板矩形</b>的帧缓冲像素 resize 浏览器避免模糊; 鼠标坐标
 *    (GUI 坐标系) 经 {@link #toPixelX}/{@link #toPixelY} 先减面板原点再线性映射回帧缓冲像素喂给 CEF。
 * 3) 打开即 setFocus(true); ESC 关闭; isPauseScreen()=false (公服不暂停)。
 *
 * 面板<b>居中且不铺满</b>: 边长占屏幕 {@code webui.coveragePercent}% (默认 70), 面板外压一层暗色背景。
 * 页面缩放走 CEF 自己的 zoom ({@code webui.zoomPercent}, 默认 125), 不是 CSS transform —— 理由见
 * {@link WebBrowser#setZoomPercent}。两项都在 {@code config/miningdim-client.toml} 里可调, 改完重开界面即生效。
 *
 * 中文 IME (step2 接口位): 完整 IME 需叠加一个不可见原版 EditBox 捕获 GLFW IME 组字事件 (preedit/commit),
 * 再把已上屏字符经 {@link WebBrowser#sendKeyTyped} 注入 CEF。本步仅在 {@link #charTyped} 直接转发 BMP 字符
 * (英文/已上屏单字可用); 组字态中文留待 step2 在此叠加 EditBox。见 charTyped 处标注。
 */
public final class WebUiScreen extends Screen {

    @Nullable
    private final WebBrowser browser;

    /** 面板之外那圈背景的压暗程度 (ARGB)。原版 Screen 的默认遮罩是全屏渐变, 这里只压面板外的部分。 */
    private static final int BACKDROP_COLOR = 0xB0101014;

    private final WebUiInput input = new WebUiInput();
    private boolean cleanedUp;

    // 帧缓冲实际像素尺寸 (考虑系统 DPI / GUI scale 的真实渲染分辨率); CEF 离屏渲染按此尺寸, 避免模糊。
    private int pixelWidth;
    private int pixelHeight;

    // 面板在 GUI 坐标系里的矩形 (居中, 每条边占屏幕 coveragePercent%)。
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;

    public WebUiScreen(@Nullable WebBrowser browser) {
        this(browser, Component.literal("MiningDim WebUI"));
    }

    public WebUiScreen(@Nullable WebBrowser browser, Component title) {
        super(title);
        this.browser = browser;
    }

    @Override
    protected void init() {
        super.init();
        layout();
        if (browser != null) {
            // 打开时聚焦, 保证键盘/输入直达网页 (否则首帧无光标/不响应键入)。
            browser.setFocus(true);
        }
    }

    @Override
    public void resize(Minecraft mc, int newWidth, int newHeight) {
        super.resize(mc, newWidth, newHeight);
        layout();
    }

    /**
     * 按配置算出面板矩形并把浏览器调到该矩形的<b>帧缓冲像素</b>尺寸。
     *
     * 离屏渲染尺寸必须跟着面板走而不是跟着窗口走: 面板只占七成边长, 若仍按整窗尺寸渲染, 那张贴图会被压进
     * 更小的矩形里显示 —— 页面自以为有一整屏的 CSS 视口, 响应式断点按大屏走, 最后被缩小到看不清。
     * 按面板尺寸渲染, 页面拿到的才是它真正被显示的那个视口。
     *
     * 缩放在每次 layout 都重设一次: 浏览器实例跨界面复用, 玩家改完配置重开界面就该生效, 不必重启游戏。
     *
     * 离屏栅格宽度另有一道上限 ({@code webui.maxRenderWidth}), 见下方 renderScale 处的说明。
     */
    private void layout() {
        Minecraft mc = Minecraft.getInstance();
        double coverage = MiningClientConfig.WEBUI_COVERAGE_PERCENT.get() / 100.0;
        panelWidth = Math.max(1, (int) Math.round(this.width * coverage));
        panelHeight = Math.max(1, (int) Math.round(this.height * coverage));
        panelX = (this.width - panelWidth) / 2;
        panelY = (this.height - panelHeight) / 2;

        // GUI 坐标 -> 帧缓冲像素的换算比例; 用整窗两侧的比值而不是自己猜 GUI scale, 与既有做法一致。
        int windowPixelWidth = mc.getWindow().getWidth();
        int windowPixelHeight = mc.getWindow().getHeight();
        int framebufferWidth = this.width <= 0 ? windowPixelWidth
                : Math.max(1, (int) Math.round((double) panelWidth * windowPixelWidth / this.width));
        int framebufferHeight = this.height <= 0 ? windowPixelHeight
                : Math.max(1, (int) Math.round((double) panelHeight * windowPixelHeight / this.height));

        /*
         * 离屏降采样。上面两个数是面板在屏幕上真正占的帧缓冲像素, 也就是"设备像素比 1:1"这一档; 上限只在
         * 超出时才介入, 未超出时 renderScale 恒为 1, 这条分支等于不存在。
         *
         * 为什么需要它: CEF 的离屏渲染没有共享贴图通道, 整张表面要在 CPU 上栅格与合成完再交出来, 成本随
         * 像素数线性上涨。4K 上这张表面是 2688x1439 (387 万像素), 一帧约 60ms —— 而 CEF 的离屏出帧上限
         * 本来就是 30fps, 超预算就退化成隔一拍出一帧, 于是页面动画只有约 15fps, 哪怕游戏本体跑着 700fps。
         * 这是纯粹的 CEF 侧瓶颈, 与 MC 的渲染线程无关, 因此只能从像素数下手。
         *
         * zoom 必须按同一比例补偿, 这是本段唯一容易写错的地方: CEF 的 CSS 视口 = 表面像素 / zoom 倍率。
         * 只缩表面不缩 zoom, 页面拿到的 CSS 视口就跟着变窄, 响应式断点会挪、排版会变 —— 而本意只是降低
         * 采样密度。两者同比缩放后 CSS 视口逐像素不变, 变的只有那张贴图的清晰度 (贴图按 GL_LINEAR 放大回
         * 面板尺寸, 表现为文字糊一档)。
         */
        int maxRenderWidth = MiningClientConfig.WEBUI_MAX_RENDER_WIDTH.get();
        double renderScale = framebufferWidth <= maxRenderWidth
                ? 1.0
                : (double) maxRenderWidth / framebufferWidth;
        pixelWidth = Math.max(1, (int) Math.round(framebufferWidth * renderScale));
        pixelHeight = Math.max(1, (int) Math.round(framebufferHeight * renderScale));

        if (browser != null) {
            browser.resize(pixelWidth, pixelHeight);
            browser.setZoomPercent(MiningClientConfig.WEBUI_ZOOM_PERCENT.get() * renderScale);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 面板外压暗: 让它读起来是一层浮在游戏之上的模态, 而不是一块贴在画面中间的方形贴图。
        graphics.fill(0, 0, this.width, this.height, BACKDROP_COLOR);
        WebBrowser.LoadFailure failure = browser == null ? null : browser.loadFailure();
        if (failure != null) {
            // 加载失败时不画浏览器贴图: 浏览器是透明底的, CEF 那张内部错误页在这层近黑背板上几乎看不见,
            // 玩家只会看到"黑屏"而拿不到任何线索。改由我们自己把错误码与地址写在面板中央。
            renderLoadFailure(graphics, failure);
        } else if (browser != null && browser.isReady()) {
            int textureId = browser.getTextureId();
            if (textureId > 0) {
                // 后两个参数是宽高, 不是右下角坐标 (见 WebBrowser.render 的顶点拼装)。
                // 传 panelX+panelWidth 会把面板拉成"起点在中心、尺寸按右下角算"的样子, 溢出屏幕右下 ——
                // 全屏时因为起点是 (0,0) 两种口径恰好等价, 所以这个错只在居中之后才看得出来。
                browser.render(graphics, panelX, panelY, panelWidth, panelHeight);
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

    /**
     * 画出加载失败详情。玩家看到"黑屏"时唯一能自救的信息是<b>它到底在连哪个地址</b> —— 最常见的成因是
     * {@code webui.url} 还停在默认或写错, 所以地址必须原样显示出来, 不能只报一个错误码。
     */
    private void renderLoadFailure(GuiGraphics graphics, WebBrowser.LoadFailure failure) {
        int cx = this.width / 2;
        int y = this.height / 2 - this.font.lineHeight * 2;
        graphics.drawCenteredString(this.font,
                Component.translatable("gui.miningdim.webui.load_failed"), cx, y, 0xFFE0525C);
        y += this.font.lineHeight * 2;
        graphics.drawCenteredString(this.font,
                Component.translatable("gui.miningdim.webui.load_failed.code",
                        failure.code(), failure.text()),
                cx, y, 0xFFD6DCE7);
        y += this.font.lineHeight;
        graphics.drawCenteredString(this.font, failure.url(), cx, y, 0xFF8FB4D9);
        y += this.font.lineHeight * 2;
        graphics.drawCenteredString(this.font,
                Component.translatable("gui.miningdim.webui.load_failed.hint"), cx, y, 0xFF9AA3B2);
    }

    // ---- 坐标换算: GUI 坐标系 -> 面板内的帧缓冲像素 ----
    //
    // 先减去面板左上角再按面板尺寸换算。少减这一步的话, 页面收到的坐标会整体偏移 panelX/panelY,
    // 表现是"鼠标在按钮上但点不中, 越靠右下偏得越多" —— 全屏时两者恰好相等, 所以这个 bug 只会在
    // 覆盖比例调到 100 以下时出现。

    private int toPixelX(double guiX) {
        if (panelWidth <= 0) {
            return 0;
        }
        return (int) ((guiX - panelX) * pixelWidth / panelWidth);
    }

    private int toPixelY(double guiY) {
        if (panelHeight <= 0) {
            return 0;
        }
        return (int) ((guiY - panelY) * pixelHeight / panelHeight);
    }

    /**
     * 该 GUI 坐标是否落在面板内。
     *
     * 面板外的点击一律不转发给页面, 也<b>不关闭界面</b>: 平板里有挂单、求婚这类填到一半的表单, 点空白处
     * 就整屏关掉会让人白填一遍。要退出有 ESC。
     */
    private boolean insidePanel(double guiX, double guiY) {
        return guiX >= panelX && guiX < panelX + panelWidth
                && guiY >= panelY && guiY < panelY + panelHeight;
    }

    // ---- 鼠标事件 (button 一律经 toCefMouseButton 映射) ----

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (browser != null && insidePanel(mouseX, mouseY)) {
            browser.sendMousePress(toPixelX(mouseX), toPixelY(mouseY), input.toCefMouseButton(button));
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (browser != null && insidePanel(mouseX, mouseY)) {
            browser.sendMouseRelease(toPixelX(mouseX), toPixelY(mouseY), input.toCefMouseButton(button));
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        if (browser != null && insidePanel(mouseX, mouseY)) {
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
        if (browser != null && insidePanel(mouseX, mouseY)) {
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
        // 打开键兼作关闭键 (默认 G)。判据取 KeyMapping 本身而不是写死 GLFW_KEY_G: 玩家在控制设置里改绑之后,
        // 开和关必须还是同一个键。页面里有输入框持有焦点时让位给打字 —— 那个键同时也是要打进搜索框的字符。
        if (WebUiKeyMappings.OPEN_WEB_UI.matches(keyCode, scanCode) && WebUiClient.canCloseWithOpenKey()) {
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
        cleanup();
        super.onClose();
    }

    @Override
    public void removed() {
        cleanup();
        super.removed();
    }

    private void cleanup() {
        if (cleanedUp) {
            return;
        }
        cleanedUp = true;
        if (browser != null) {
            // 失焦并清修饰键, 防卡键; 不在此 close 浏览器 (浏览器由 WebUiClient 持有复用, 仅隐藏界面)。
            browser.setFocus(false);
            input.reset();
        }
        WebUiClient.onScreenClosed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
