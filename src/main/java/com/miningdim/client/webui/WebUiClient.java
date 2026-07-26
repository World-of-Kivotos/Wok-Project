package com.miningdim.client.webui;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import javax.annotation.Nullable;

import org.cef.browser.CefMessageRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cinemamod.mcef.MCEF;

import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.ModList;

/**
 * Web UI 客户端总控 (client-only, 仅 Dist.CLIENT classload; 经 WebUiClientSubsystem 的 DistExecutor 关进
 * client lambda, 保证服务端 GameTest 进程不触链 MCEF)。
 *
 * 职责:
 *   - initClient(): MCEF.scheduleForInit -> 成功则注册 miningdimQuery 路由 (绑 WebUiBridge) 并设为
 *     WebUiClientReceiver 的当前桥。MCEF 未装时优雅降级 (ModList 守卫), 不崩。
 *   - openDevScreen(): 创建/复用浏览器加载内联开发测试页, 打开 WebUiScreen。
 *
 * 单例收敛 (共享契约 8 已知必修): 进程内单一 WebUiBridge + 单一 WebBrowser, 静态持有, 不每次新建。
 */
public final class WebUiClient {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/webui");

    // CEF 消息路由品牌 (共享契约 2): 改品牌避免与其它 mod 的 cefQuery 路由冲突。
    private static final String QUERY_FUNCTION = "miningdimQuery";
    private static final String QUERY_CANCEL_FUNCTION = "miningdimQueryCancel";
    private static final String CASE_PAGE_RESOURCE = "/assets/miningdim/web/case-opening.html";

    // 单例桥与浏览器 (进程内唯一)。
    private static final WebUiBridge BRIDGE = new WebUiBridge();
    @Nullable
    private static volatile WebBrowser browser;
    // 路由是否已注册 (MCEF init 回调成功后置真); 避免重复注册。
    private static volatile boolean routerRegistered = false;

    private WebUiClient() {
    }

    /**
     * 客户端初始化 (FMLClientSetup 期经子系统调用)。未装 MCEF 时直接返回, 不触发任何 MCEF 类加载分支
     * (ModList 守卫先于 MCEF.* 引用; MCEF.* 调用仅在确认已装时进入)。
     */
    public static void initClient() {
        if (!ModList.get().isLoaded("mcef")) {
            LOGGER.warn("未检测到 MCEF (mcef), WebUI 开发宿主不可用; /miningdim-webui-dev 将提示安装。");
            return;
        }
        // scheduleForInit 单方法回调: 参数 success 表示 CEF 原生层是否就绪。
        MCEF.scheduleForInit(success -> {
            if (!success) {
                LOGGER.error("MCEF 初始化失败, WebUI 路由未注册。");
                return;
            }
            registerRouter();
        });
    }

    /** 注册 miningdimQuery 路由并把单例桥设为接收器当前桥 (幂等)。 */
    private static synchronized void registerRouter() {
        if (routerRegistered) {
            return;
        }
        CefMessageRouter.CefMessageRouterConfig config =
                new CefMessageRouter.CefMessageRouterConfig(QUERY_FUNCTION, QUERY_CANCEL_FUNCTION);
        CefMessageRouter router = CefMessageRouter.create(config, BRIDGE);
        MCEF.getClient().getHandle().addMessageRouter(router);
        WebUiClientReceiver.setCurrentBridge(BRIDGE);
        routerRegistered = true;
        LOGGER.info("WebUI cefQuery 路由已注册 (品牌={})", QUERY_FUNCTION);
    }

    /**
     * 打开开发宿主界面。MCEF 未装/未就绪时仅提示, 不崩。复用单例浏览器 (首次创建, 之后复用)。
     * 浏览器创建后绑定到桥 (供 onEvent 注入), 再以 WebUiScreen 打开。
     */
    public static void openDevScreen() {
        openScreen(devPageDataUri(), "WebUI");
    }

    /** 打开 jar 内置的武器箱页面。页面仍走统一 cefQuery 桥, 仅展示与动画在客户端执行。 */
    public static void openCaseScreen() {
        String page;
        try {
            page = resourceDataUri(CASE_PAGE_RESOURCE);
        } catch (IOException e) {
            LOGGER.error("读取武器箱 WebUI 资源失败: {}", CASE_PAGE_RESOURCE, e);
            hint(Minecraft.getInstance(), "[WOK] 武器箱界面资源损坏, 请检查客户端模组。");
            return;
        }
        openScreen(page, "WOK Case");
    }

    private static void openScreen(String pageDataUri, String title) {
        Minecraft mc = Minecraft.getInstance();
        if (!ModList.get().isLoaded("mcef") || !MCEF.isInitialized()) {
            hint(mc, "[MiningDim] WebUI 不可用: 未安装或未初始化 MCEF。");
            return;
        }

        // cefQuery 能触发扣费等权威动作, 因此桥只接受宿主本次明确加载的完整 data URI。
        BRIDGE.setAllowedPage(pageDataUri);
        WebBrowser b = browser;
        if (b == null) {
            b = new WebBrowser(false);
            int w = mc.getWindow().getWidth();
            int h = mc.getWindow().getHeight();
            boolean ok = b.create(pageDataUri, w, h);
            if (!ok) {
                hint(mc, "[MiningDim] WebUI 浏览器创建失败 (MCEF 未就绪)。");
                return;
            }
            browser = b;
            BRIDGE.setBrowser(b);
        } else {
            b.loadURL(pageDataUri);
        }
        mc.setScreen(new WebUiScreen(b, net.minecraft.network.chat.Component.literal(title)));
    }

    /** Screen 的 ESC / 被其它界面替换路径统一清理在途回调与 UI 音效。 */
    static void onScreenClosed() {
        BRIDGE.onScreenClosed();
    }

    /** 优雅降级提示: 经本地玩家 actionbar 显示 (与 ClientFeedback 同范式); 玩家未就绪时退回日志。 */
    private static void hint(Minecraft mc, String message) {
        net.minecraft.client.player.LocalPlayer player = mc.player;
        if (player != null) {
            player.displayClientMessage(net.minecraft.network.chat.Component.literal(message), false);
        } else {
            LOGGER.info("{}", message);
        }
    }

    /**
     * DEV 脚手架页 (非生产前端; 生产前端远端托管, 经 loadURL 切换)。极简 HTML: 一个按钮调
     * window.miningdimQuery 发 system.echo, 把响应/失败写回页面。以 data: URI 内联 (base64 编码,
     * 避免 URL 编码歧义)。纯 ASCII, 无 emoji。
     */
    private static String devPageDataUri() {
        String html = "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                + "<style>body{font-family:monospace;background:#1e1e1e;color:#ddd;padding:24px;}"
                + "button{font-size:16px;padding:8px 16px;margin-bottom:12px;}"
                + "#out{white-space:pre-wrap;border:1px solid #444;padding:12px;min-height:60px;}</style>"
                + "<title>MiningDim WebUI Dev</title></head><body>"
                + "<h3>MiningDim WebUI bridge dev page</h3>"
                + "<button id=\"echoBtn\">Send system.echo</button>"
                + "<div id=\"out\">(no response yet)</div>"
                + "<script>"
                // 受控事件入口 (服务端 onEvent 注入处约定调用此函数)。
                + "window.miningdimOnEvent=function(name,dataJson){"
                + "document.getElementById('out').textContent='EVENT '+name+': '+dataJson;};"
                + "document.getElementById('echoBtn').addEventListener('click',function(){"
                + "var out=document.getElementById('out');out.textContent='sending...';"
                + "window.miningdimQuery({"
                + "request:JSON.stringify({action:'system.echo',payload:{msg:'hi from client'}}),"
                + "onSuccess:function(r){out.textContent='OK: '+r;},"
                + "onFailure:function(code,msg){out.textContent='FAIL '+code+': '+msg;}"
                + "});});"
                + "</script></body></html>";
        String b64 = Base64.getEncoder().encodeToString(html.getBytes(StandardCharsets.UTF_8));
        return "data:text/html;base64," + b64;
    }

    private static String resourceDataUri(String resourcePath) throws IOException {
        try (InputStream in = WebUiClient.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("missing classpath resource: " + resourcePath);
            }
            String b64 = Base64.getEncoder().encodeToString(in.readAllBytes());
            return "data:text/html;charset=utf-8;base64," + b64;
        }
    }
}
