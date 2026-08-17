package com.miningdim.client.webui;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cinemamod.mcef.MCEF;
import com.cinemamod.mcef.MCEFBrowser;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;

/**
 * MCEF (Chromium) 浏览器封装 + 把离屏渲染贴图画进 Minecraft GUI (客户端宿主层, 仅 Dist.CLIENT classload)。
 *
 * 抢救自 MiracleBrowser, 去 BridgeAPI/脚本注入耦合 (本桥的 JS<->Java 走 cefQuery 路由, 不再注入 SDK),
 * 并修下列竞态:
 *   - browser 字段 volatile: close 可能在渲染线程外 (界面销毁) 触发, render/getTextureId 在渲染线程读,
 *     无 volatile 则可能读到半失效引用。
 *   - getTextureId / render 守卫 textureId > 0: MCEF 首帧前贴图未就绪返回 0 或 -1, 直接 setShaderTexture(0)
 *     会画出残帧/黑块。
 *   - close 幂等: 多次 onClose / 异常路径重复 close 不应 NPE。
 *
 * 渲染走 1.20.1 即时模式 (RenderSystem + Tesselator + BufferUploader, POSITION_TEX_COLOR), 与 1.20.1
 * GuiGraphics 即时绘制范式一致; 坐标由 {@link WebUiScreen} 以帧缓冲像素传入。
 */
public final class WebBrowser {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/webui");

    // volatile: 生命周期 (close) 与渲染 (render/getTextureId) 可能跨线程时序, 保证可见性, 防贴图竞态。
    @Nullable
    private volatile MCEFBrowser browser;

    private final boolean transparent;
    private int width;
    private int height;

    public WebBrowser(boolean transparent) {
        this.transparent = transparent;
    }

    /**
     * 创建底层 MCEF 浏览器并加载初始 URL。MCEF 未初始化时返回 false (调用方据此降级)。
     * 失败不吞: createBrowser 自身抛出的异常自然冒泡到调用栈最外层 (WebUiClient 开发命令边界)。
     */
    public boolean create(String url, int width, int height) {
        if (!MCEF.isInitialized()) {
            LOGGER.warn("MCEF 未初始化, 无法创建浏览器: {}", url);
            return false;
        }
        this.width = width;
        this.height = height;
        MCEFBrowser created = MCEF.createBrowser(url, transparent, width, height);
        this.browser = created;
        LOGGER.info("WebUI 浏览器已创建: {} ({}x{})", url, width, height);
        return true;
    }

    public void loadURL(String url) {
        MCEFBrowser b = browser;
        if (b != null) {
            b.loadURL(url);
        }
    }

    public void executeJavaScript(String script) {
        MCEFBrowser b = browser;
        if (b != null) {
            // url/line 仅用于 JS 异常栈定位; 传当前 URL + 0 行即可。
            b.executeJavaScript(script, b.getURL(), 0);
        }
    }

    /** 调整离屏渲染尺寸 (帧缓冲像素)。仅尺寸真变化时下发, 避免每帧无谓 resize。 */
    public void resize(int width, int height) {
        MCEFBrowser b = browser;
        if (b != null && (this.width != width || this.height != height)) {
            this.width = width;
            this.height = height;
            b.resize(width, height);
        }
    }

    /**
     * 设置页面缩放百分比 (100 = 原始大小)。
     *
     * CEF 的 zoomLevel 不是倍率而是<b>以 1.2 为底的对数刻度</b> (zoomFactor = 1.2^level, 与 Chrome 工具栏
     * 那一档一档的缩放同一套刻度)。直接把 1.25 当 level 传进去会得到 1.2^1.25 ≈ 1.26 倍 —— 数字看着接近,
     * 纯属巧合; 传 2.0 就会变成 1.44 倍而不是 2 倍。故必须换底。
     *
     * 100% 显式走 setZoomLevel(0) 而不是跳过: 浏览器实例是复用的, 玩家把缩放调回 100 时必须真的把上一次的
     * 缩放清掉。
     *
     * 收 double 而不是 int: 调用方传进来的已经不是配置里那个整数, 而是"配置值 x 离屏降采样比例"
     * (见 {@link WebUiScreen#layout})。降采样必须由 zoom 等比补偿, 否则缩小离屏表面等于同时缩小了页面的
     * CSS 视口 —— 那会挪动响应式断点、改变排版, 而本意只是降低采样密度。
     */
    public void setZoomPercent(double percent) {
        MCEFBrowser b = browser;
        if (b == null) {
            return;
        }
        if (percent <= 0) {
            throw new IllegalArgumentException("zoom percent must be > 0, got " + percent);
        }
        double level = Math.log(percent / 100.0) / Math.log(1.2);
        b.setZoomLevel(level);
    }

    /** 当前离屏贴图 OpenGL 纹理 ID; 浏览器或渲染器未就绪时返回 -1 (调用方据此跳过绘制)。 */
    public int getTextureId() {
        MCEFBrowser b = browser;
        if (b == null) {
            return -1;
        }
        var renderer = b.getRenderer();
        return renderer != null ? renderer.getTextureID() : -1;
    }

    /**
     * 把浏览器贴图绘制到屏幕指定矩形 (GUI 坐标系)。textureId <= 0 (0=无效贴图, -1=未就绪) 时跳过, 防残帧。
     *
     * @param graphics 1.20.1 渲染上下文 (此处不直接用其 API, 仅作为"在 GUI 渲染管线内调用"的契约标记;
     *                 即时模式贴图绘制需在 GuiGraphics.render 链路里, 否则 RenderSystem 状态被原版重置)。
     */
    public void render(GuiGraphics graphics, int x, int y, int w, int h) {
        int textureId = getTextureId();
        if (textureId <= 0) {
            return;
        }

        RenderSystem.enableBlend();
        // 预乘 alpha 混合, 不是 defaultBlendFunc。CEF 的离屏缓冲交出来的 BGRA 是<b>预乘</b>的 (颜色分量已经
        // 乘过 alpha), 而 defaultBlendFunc 是 (SRC_ALPHA, ONE_MINUS_SRC_ALPHA) —— 那套公式假设颜色未预乘,
        // 于是把已经乘过一次的颜色再乘一次 alpha。全不透明处 alpha=1 看不出差别, 但圆角那圈抗锯齿像素
        // (0<alpha<1) 会被压暗成一道黑边, 表现就是"圆角像是描了一圈黑"。
        RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, textureId);

        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        // 顶点序: 左下 -> 右下 -> 右上 -> 左上; UV 上下翻转 (CEF 贴图原点在左上, GL 在左下)。
        buf.vertex(x, y + h, 0).uv(0f, 1f).color(255, 255, 255, 255).endVertex();
        buf.vertex(x + w, y + h, 0).uv(1f, 1f).color(255, 255, 255, 255).endVertex();
        buf.vertex(x + w, y, 0).uv(1f, 0f).color(255, 255, 255, 255).endVertex();
        buf.vertex(x, y, 0).uv(0f, 0f).color(255, 255, 255, 255).endVertex();
        BufferUploader.drawWithShader(buf.end());

        RenderSystem.disableBlend();
    }

    // ---- 输入转发 (坐标已由 WebUiScreen 换算为帧缓冲像素; button 已由 WebUiInput 映射为 CEF 编号) ----

    public void sendMousePress(int x, int y, int cefButton) {
        MCEFBrowser b = browser;
        if (b != null) {
            b.sendMousePress(x, y, cefButton);
        }
    }

    public void sendMouseRelease(int x, int y, int cefButton) {
        MCEFBrowser b = browser;
        if (b != null) {
            b.sendMouseRelease(x, y, cefButton);
        }
    }

    public void sendMouseMove(int x, int y) {
        MCEFBrowser b = browser;
        if (b != null) {
            b.sendMouseMove(x, y);
        }
    }

    public void sendMouseWheel(int x, int y, double delta, int modifiers) {
        MCEFBrowser b = browser;
        if (b != null) {
            b.sendMouseWheel(x, y, delta, modifiers);
        }
    }

    public void sendKeyPress(int keyCode, long scanCode, int modifiers) {
        MCEFBrowser b = browser;
        if (b != null) {
            b.sendKeyPress(keyCode, scanCode, modifiers);
        }
    }

    public void sendKeyRelease(int keyCode, long scanCode, int modifiers) {
        MCEFBrowser b = browser;
        if (b != null) {
            b.sendKeyRelease(keyCode, scanCode, modifiers);
        }
    }

    public void sendKeyTyped(char c, int modifiers) {
        MCEFBrowser b = browser;
        if (b != null) {
            b.sendKeyTyped(c, modifiers);
        }
    }

    public void setFocus(boolean focus) {
        MCEFBrowser b = browser;
        if (b != null) {
            b.setFocus(focus);
        }
    }

    public boolean isReady() {
        return browser != null;
    }

    /** 幂等关闭: 先置空再 close, 渲染线程读到 null 即跳过, 重复调用是 no-op。 */
    public void close() {
        MCEFBrowser b = browser;
        if (b == null) {
            return;
        }
        browser = null;
        b.close();
        LOGGER.info("WebUI 浏览器已关闭");
    }
}
