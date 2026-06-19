package com.miningdim.client.webui;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cinemamod.mcef.MCEF;
import com.cinemamod.mcef.MCEFBrowser;
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
        RenderSystem.defaultBlendFunc();
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
