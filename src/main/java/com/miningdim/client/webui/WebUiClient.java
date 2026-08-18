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
import com.miningdim.config.MiningClientConfig;

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
    // 单例浏览器当前已加载的页面 URL: 供 openScreen 在 forceReload=false 时判断能否跳过重载 (决策 J4)。
    @Nullable
    private static volatile String loadedUrl;
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
        openScreen(devPageDataUri(), "WebUI", true);
    }

    /**
     * 打开正式前端 (键位入口与后续平板 hub 的唯一路径)。加载 {@link MiningClientConfig#webUiUrl()} 归一化后的
     * 单一 URL; 面板路由由页面自身的 hash router 承担 (决策 J4), Java 侧不按面板换 URL。
     *
     * 与 jar 内置页的关键差异是 {@code forceReload=false}: 内置页是一次性展示 (开箱动画每次都该从头播), 而正式
     * 前端是常驻 SPA —— 每次按键都重载会丢掉已加载的应用状态并把路由弹回首页, 正是架构文档 10.5 要消除的开销。
     */
    public static void openWebUi() {
        openScreen(MiningClientConfig.webUiUrl(), "WOK", false);
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
        openScreen(page, "WOK Case", true);
    }

    /**
     * 打开宿主界面并确保浏览器停在目标页。
     *
     * @param forceReload 复用已有浏览器时是否无条件重载。jar 内置页传 true (每次打开都该是全新一遍);
     *                    常驻 SPA 传 false —— 目标页与已加载页相同时跳过 loadURL, 保住应用状态与 Chromium 缓存。
     *                    跳过重载不影响安全: 上面的 setAllowedPage 已按本次目标页重新登记, onQuery 仍逐次精确匹配。
     */
    private static void openScreen(String pageUrl, String title, boolean forceReload) {
        Minecraft mc = Minecraft.getInstance();
        if (!ModList.get().isLoaded("mcef") || !MCEF.isInitialized()) {
            hint(mc, "[MiningDim] WebUI 不可用: 未安装或未初始化 MCEF。");
            return;
        }

        // cefQuery 能触发扣费等权威动作, 因此桥只接受宿主本次明确加载的完整页面 URL。
        // 页面可信 (setAllowedPage) 与界面是否显示 (onScreenOpened/onScreenClosed) 是两件事:
        // 前者跟随宿主加载了哪个页面, 后者跟随 Screen 开关, 两者互不覆盖。
        BRIDGE.setAllowedPage(pageUrl);
        WebBrowser b = browser;
        if (b == null) {
            // 透明底: 圆角只能由页面自己画 (离屏贴图是矩形, MC 这边没有裁圆的手段)。页面把四角留成
            // 透明, 浏览器不透明的话那四块会被填成纯色, 看起来还是直角。
            b = new WebBrowser(true);
            int w = mc.getWindow().getWidth();
            int h = mc.getWindow().getHeight();
            boolean ok = b.create(pageUrl, w, h);
            if (!ok) {
                hint(mc, "[MiningDim] WebUI 浏览器创建失败 (MCEF 未就绪)。");
                return;
            }
            browser = b;
            BRIDGE.setBrowser(b);
            loadedUrl = pageUrl;
            // 上次加载失败过就借这次开面板重来一遍: 浏览器是复用的, 地址没变时本不会重新导航, 于是那张
            // 失败页会一直挂着 —— 玩家反复开关面板都还是同一片"黑屏", 网络恢复了也自己好不了。
        } else if (forceReload || !pageUrl.equals(loadedUrl) || b.loadFailure() != null) {
            b.loadURL(pageUrl);
            loadedUrl = pageUrl;
        }
        BRIDGE.onScreenOpened();
        // 页面是常驻 SPA: 关面板只是隐藏 MC 的 Screen, React 树原样活着, 于是重新打开时看到的还是上次
        // 拉取的那份数据 (玩家在游戏里赚了钱、做完了任务, 面板上一个数都不动)。借既有事件通道通知页面
        // 把缓存全部作废重拉 —— 这条必须在 setScreen 之前发, 否则 onScreenOpened 尚未置位, 桥会以
        // "界面已关闭" 为由把页面随后发起的那批请求全部挡掉。
        BRIDGE.onEvent("panelOpened", "{}");
        mc.setScreen(new WebUiScreen(b, net.minecraft.network.chat.Component.literal(title)));
    }

    /** Screen 的 ESC / 被其它界面替换路径统一清理在途回调与 UI 音效。 */
    static void onScreenClosed() {
        BRIDGE.onScreenClosed();
        /*
         * 告诉页面它已经不在屏幕上了 —— panelOpened 的对偶事件。
         *
         * 关面板只隐藏 MC 的 Screen, 这个 SPA 与它那个离屏浏览器原样活着, 而 CEF 不会因为宿主不再采样
         * 贴图就停止出帧: 页面里任何一个还在跑的定时器 (首页 1 秒一次的倒计时、矿洞 3 秒一次的轮询)
         * 都会继续触发重渲染, 于是玩家在野外跑图时, 后台仍在一遍遍栅格一张几百万像素的表面。
         *
         * 桥的 -4 关屏门只挡住了请求出门 (服务端主线程不再被打), 挡不住重绘 —— 那一半只能由页面自己
         * 停下来, 而页面无从得知自己被隐藏了 (CEF 没有这个通知)。故必须由宿主明说。
         *
         * 发在 BRIDGE.onScreenClosed() 之后: 那一步刚把在途请求逐个回 -5, 页面处理完那批失败再收到
         * "停"才不会前脚停后脚又被失败回调唤起。
         */
        BRIDGE.onEvent("panelClosed", "{}");
        // 关屏即复位: 页面里那个输入框的焦点随界面一起消失了, 留着 true 会让下一次开面板按不了关闭键。
        textInputFocused = false;
    }

    /**
     * 页面里是否有可编辑元素正持有焦点 (由页面经 {@code client.textFocus} 上报)。
     *
     * 存在的唯一理由是让打开键能兼作关闭键: 那个键 (默认 G) 同时也是玩家要在市场搜索框里打出来的字符,
     * 而 CEF/MCEF 不暴露"当前焦点是不是可编辑节点"这一信息 (javap 实测 CefRenderHandler 与 MCEFBrowser
     * 都没有), 只有 DOM 自己知道。故由页面在 focusin/focusout 时上报, Java 侧只读这一个布尔。
     *
     * 上报是异步的, 理论上存在"刚点进输入框就立刻按 G"这一帧的滞后。代价是最坏情况下少打一个字母,
     * 而不是关错界面 —— 反过来 (默认可关) 的代价是打字打到一半界面没了, 两者不对称。
     */
    private static volatile boolean textInputFocused;

    /** 页面上报可编辑焦点变化。 */
    static void setTextInputFocused(boolean focused) {
        textInputFocused = focused;
    }

    /** 打开键此刻能不能兼作关闭键。 */
    static boolean canCloseWithOpenKey() {
        return !textInputFocused;
    }

    /**
     * 配置改动后让已打开的界面立刻重排 (设置页拖完滑块调)。
     *
     * 走 Screen.resize 而不是重开界面: 后者会把浏览器 setFocus 走一遍并可能打断玩家正在填的表单,
     * 而 resize 只触发 WebUiScreen.layout —— 那里本来就每次都重读配置。
     */
    static void relayoutOpenScreen() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof WebUiScreen screen) {
            screen.resize(mc, screen.width, screen.height);
        }
    }

    /** 页面自己的关闭按钮 (右上角 X) 请求关界面。必须在主线程执行: 动的是 MC 的 Screen 栈。 */
    static void requestClose() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.screen instanceof WebUiScreen) {
                mc.setScreen(null);
            }
        });
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
