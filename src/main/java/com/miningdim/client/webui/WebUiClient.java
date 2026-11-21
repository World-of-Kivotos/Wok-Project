package com.miningdim.client.webui;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

import javax.annotation.Nullable;
import javax.imageio.ImageIO;

import org.cef.browser.CefMessageRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cinemamod.mcef.MCEF;
import com.google.gson.Gson;
import com.miningdim.config.MiningClientConfig;
import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
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
    private static final Gson GSON = new Gson();

    // CEF 消息路由品牌 (共享契约 2): 改品牌避免与其它 mod 的 cefQuery 路由冲突。
    private static final String QUERY_FUNCTION = "miningdimQuery";
    private static final String QUERY_CANCEL_FUNCTION = "miningdimQueryCancel";
    private static final String CASE_PAGE_RESOURCE = "/assets/miningdim/web/case-opening.html";
    private static final String SOCIAL_PAGE_RESOURCE = "/assets/miningdim/web/social.html";
    private static final DateTimeFormatter PHOTO_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    // 单例桥与浏览器 (进程内唯一)。
    private static final WebUiBridge BRIDGE = new WebUiBridge();
    @Nullable
    private static volatile WebBrowser browser;
    // 单例浏览器当前已加载的页面 URL: 供 openScreen 在 forceReload=false 时判断能否跳过重载 (决策 J4)。
    @Nullable
    private static volatile String loadedUrl;
    // 路由是否已注册 (MCEF init 回调成功后置真); 避免重复注册。
    private static volatile boolean routerRegistered = false;
    // 社交页是否打开 / 是否正在等一帧世界截图; 截图期间关闭 Screen 不得清桥。
    private static volatile boolean socialPageOpen = false;
    private static volatile boolean captureInProgress = false;
    private static int captureTicks = 0;
    // 社交页开场的掏平板动画是否还在播: 期间 Esc 先跳过动画, 动画结束后才关面板。
    private static volatile boolean socialOpeningActive = false;
    // 相机模式/截图期间挂起 Screen 关闭清理: 页面与浏览器保持存活, 由调用方决定何时重开。
    private static volatile boolean screenCloseSuspended = false;
    @Nullable
    private static Runnable socialCameraOpener;
    @Nullable
    private static Runnable cameraBeforeReopen;
    // 社交平板动画的宿主钩子: 界面打开 / 带动画关闭 / 直接关闭 (被其它界面顶掉、断线)。
    @Nullable
    private static Runnable socialScreenOpenedHook;
    @Nullable
    private static Runnable socialScreenClosedAnimatedHook;
    @Nullable
    private static Runnable socialScreenClosedDirectHook;
    // 本次关闭是否由“收起平板”发起 (决定走动画还是直接复位)。
    private static volatile boolean tabletCloseRequested;

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
        MinecraftForge.EVENT_BUS.addListener(WebUiClient::onClientTick);
        MCEF.scheduleForInit(success -> {
            if (!success) {
                LOGGER.error("MCEF 初始化失败, WebUI 路由未注册。");
                return;
            }
            registerRouter();
        });        // 兜底：MCEF 2.1.6 的 initialize 由 CefDownloadMixin 在下载线程里拉起；离线镜像不可达或
        // 自建客户端会卡在“只注册监听、从不初始化”。仅在显式属性开启时兜底，避免与正常安装竞争。
        if (Boolean.getBoolean("miningdim.mcef.forceInit")) {
            Thread thread = new Thread(() -> {
                try {
                    Thread.sleep(2000L);
                } catch (InterruptedException ignored) {
                    return;
                }
                if (!MCEF.isInitialized()) {
                    LOGGER.info("MCEF forceInit: calling MCEF.initialize()");
                    MCEF.initialize();
                }
            }, "miningdim-mcef-init");
            thread.setDaemon(true);
            thread.start();
        }
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
        openScreen(devPageDataUri(), "WebUI", true, false);
    }

    /**
     * 打开正式前端 (键位入口与后续平板 hub 的唯一路径)。加载 {@link MiningClientConfig#webUiUrl()} 归一化后的
     * 单一 URL; 面板路由由页面自身的 hash router 承担 (决策 J4), Java 侧不按面板换 URL。
     *
     * 与 jar 内置页的关键差异是 {@code forceReload=false}: 内置页是一次性展示 (开箱动画每次都该从头播), 而正式
     * 前端是常驻 SPA —— 每次按键都重载会丢掉已加载的应用状态并把路由弹回首页, 正是架构文档 10.5 要消除的开销。
     */
    public static void openWebUi() {
        openScreen(MiningClientConfig.webUiUrl(), "WOK", false, false);
    }

    /**
     * 打开社交平板页面。全屏、CEF zoom 100%，页面自己按 1600x1000 设计画布做缩放。
     * 页面属于 jar 内置资源，仍走统一 cefQuery 桥，服务端身份取 C2S 的 sender。
     */
    public static void openSocialScreen() {
        String page = socialPageDataUri();
        if (page == null) {
            return;
        }
        // 页面每次打开都会重载并从头播开场; Esc 在动画期间只跳过动画 (见 WebUiScreen#keyPressed)。
        socialOpeningActive = true;
        openScreen(page, "HaloLink", true, true);
        socialPageOpen = Minecraft.getInstance().screen instanceof WebUiScreen && browser != null;
    }

    private static String socialPageDataUri() {
        try {
            return resourceDataUri(SOCIAL_PAGE_RESOURCE);
        } catch (IOException e) {
            LOGGER.error("读取社交平板 WebUI 资源失败: {}", SOCIAL_PAGE_RESOURCE, e);
            hint(Minecraft.getInstance(), "[WOK] 社交平板界面资源损坏，请检查客户端模组。");
            return null;
        }
    }

    /**
     * 掏平板动画的第一段：把浏览器准备好（首次创建即开始加载页面），但不显示 Screen。
     * 动画在 3D 层直接采样这张浏览器贴图，所以玩家在平板还在手里时就能看到页面已经在跑。
     *
     * <p>已加载过同一页面时不重载（保留上次的状态与滚动位置），只补发 panelOpened 让页面恢复刷新。</p>
     */
    public static boolean prepareSocialScreenForAnimation() {
        String page = socialPageDataUri();
        if (page == null) {
            return false;
        }
        WebBrowser b = ensureBrowser(page, false);
        if (b == null) {
            return false;
        }
        // 与 WebUiScreen 的全屏 layout 保持同一套像素/缩放，否则从 3D 画面切到 2D 界面时会跳一下。
        applyFullscreenBrowserMetrics(b);
        socialPageOpen = true;
        socialOpeningActive = false;
        BRIDGE.onScreenOpened();
        BRIDGE.onEvent("panelOpened", "{}");
        return true;
    }

    /**
     * 开场动画被打断（按 Esc 开暂停菜单、死亡、切维度……）：把“已准备但还没显示”的页面重新挂起，
     * 不要让桥保持打开、页面在后台继续刷新。
     */
    public static void cancelPreparedSocialScreen() {
        if (Minecraft.getInstance().screen instanceof WebUiScreen) {
            return;
        }
        socialPageOpen = false;
        BRIDGE.onScreenClosed();
        BRIDGE.onEvent("panelClosed", "{}");
        textInputFocused = false;
    }

    /**
     * 把浏览器贴图调成全屏页在 {@code WebUiScreen.layout} 里会用的那一档：同一尺寸、同一 CEF zoom，
     * 3D 动画里的画面与随后出现的 2D 界面逐像素一致。
     */
    private static void applyFullscreenBrowserMetrics(WebBrowser b) {
        Minecraft mc = Minecraft.getInstance();
        int w = Math.max(1, mc.getWindow().getWidth());
        int h = Math.max(1, mc.getWindow().getHeight());
        int max = Math.max(2560, MiningClientConfig.WEBUI_MAX_RENDER_WIDTH.get());
        double scale = w <= max ? 1.0D : (double) max / w;
        int pw = Math.max(1, (int) Math.round(w * scale));
        int ph = Math.max(1, (int) Math.round(h * scale));
        b.resize(pw, ph);
        b.setZoomPercent(100.0D * scale);
    }

    /** 掏平板动画结束：把准备好的浏览器以全屏界面显示出来（此时 3D 平面正好等于全屏）。 */
    public static boolean showPreparedSocialScreen() {
        Minecraft mc = Minecraft.getInstance();
        WebBrowser b = browser;
        if (b == null) {
            return false;
        }
        socialPageOpen = true;
        socialOpeningActive = false;
        mc.setScreen(new WebUiScreen(b, Component.literal("HaloLink"), true));
        Runnable hook = socialScreenOpenedHook;
        if (hook != null) {
            hook.run();
        }
        return true;
    }

    /** 社交平板动画的宿主钩子（由 social 客户端注入，避免 webui 反向依赖 social）。 */
    public static void setSocialScreenHooks(@Nullable Runnable opened,
                                            @Nullable Runnable closedAnimated,
                                            @Nullable Runnable closedDirect) {
        socialScreenOpenedHook = opened;
        socialScreenClosedAnimatedHook = closedAnimated;
        socialScreenClosedDirectHook = closedDirect;
    }

    /** 当前浏览器贴图 id（&lt;= 0 表示还没建好），供 3D 平板采样。 */
    public static int socialScreenTextureId() {
        WebBrowser b = browser;
        return b == null ? -1 : b.getTextureId();
    }

    /** 浏览器是否已经出过至少一帧。 */
    public static boolean socialScreenPainted() {
        WebBrowser b = browser;
        return b != null && b.hasPainted();
    }

    /**
     * 收起平板：先关掉界面（画面仍留在浏览器贴图上），由动画接管 3D 收起过程。
     * 非社交页返回 {@code false}，调用方退回普通关闭。
     */
    public static boolean requestSocialCloseWithAnimation() {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof WebUiScreen) || !socialPageOpen) {
            return false;
        }
        tabletCloseRequested = true;
        mc.setScreen(null);
        return true;
    }

    /** 页面回报开场动画已播完 (或被跳过): 之后 Esc 恢复为关闭面板。 */
    public static void markSocialOpeningDone() {
        socialOpeningActive = false;
    }

    public static boolean isSocialOpeningActive() {
        return socialOpeningActive;
    }

    /** 社交页是否仍由本客户端持有（相机模式挂起关闭清理期间保持 true）。 */
    public static boolean isSocialPageOpen() {
        return socialPageOpen;
    }

    /**
     * 相机模式挂起/恢复 Screen 关闭清理。挂起时 {@link #onScreenClosed()} 不清桥、不丢浏览器，
     * 供相机模式关闭平板后自由走动，拍完再用同一个浏览器实例重开。
     */
    public static void setScreenCloseSuspended(boolean suspended) {
        screenCloseSuspended = suspended;
    }

    /** 由社交客户端注入“打开相机”的实现，避免 webui 模块反向依赖 social 模块。 */
    public static void setSocialCameraOpener(@Nullable Runnable opener) {
        socialCameraOpener = opener;
    }

    public static void openSocialCamera() {
        Runnable opener = socialCameraOpener;
        if (opener != null) {
            opener.run();
        }
    }

    /**
     * 相机快门：沿用原拍照管线的两帧等待与截图/缩略图/回填逻辑。
     * beforeReopen 在重开屏幕前执行（相机控制器用它退场、恢复状态）。
     */
    public static boolean beginCameraShot(@Nullable Runnable beforeReopen) {
        if (captureInProgress || !socialPageOpen || browser == null) {
            return false;
        }
        captureInProgress = true;
        captureTicks = 2;
        hideGuiBeforeCapture = Minecraft.getInstance().options.hideGui;
        cameraBeforeReopen = beforeReopen;
        return true;
    }

    /** 相机退出/拍完由相机控制器调用：重开同一个浏览器实例，不重播页面开场。 */
    public static void reopenSocialScreenForCamera() {
        reopenSocialScreen();
    }

    /** 相机 Esc 退出、没有照片时通知页面收掉等待态。 */
    public static void notifyCameraClosed() {
        WebBrowser browserRef = browser;
        if (browserRef != null) {
            browserRef.executeJavaScript(
                    "if (typeof window.miningdimOnCameraClosed === 'function') {"
                            + " window.miningdimOnCameraClosed(); }");
        }
    }

    private static void runCameraBeforeReopen() {
        Runnable callback = cameraBeforeReopen;
        cameraBeforeReopen = null;
        if (callback != null) {
            callback.run();
        }
    }

    /** 拍照期间临时关闭平板后，用同一个浏览器实例重开界面，不重载页面。 */
    private static void reopenSocialScreen() {
        Minecraft mc = Minecraft.getInstance();
        WebBrowser b = browser;
        if (b == null || !socialPageOpen) {
            openSocialScreen();
            return;
        }
        // 同一次打开里的回场 (拍照回来) 不重播开场。
        socialOpeningActive = false;
        BRIDGE.onScreenOpened();
        mc.setScreen(new WebUiScreen(b, Component.literal("HaloLink"), true));
        Runnable hook = socialScreenOpenedHook;
        if (hook != null) {
            hook.run();
        }
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
        openScreen(page, "WOK Case", true, false);
    }

    /**
     * 打开宿主界面并确保浏览器停在目标页。
     *
     * @param forceReload 复用已有浏览器时是否无条件重载。jar 内置页传 true (每次打开都该是全新一遍);
     *                    常驻 SPA 传 false —— 目标页与已加载页相同时跳过 loadURL, 保住应用状态与 Chromium 缓存。
     *                    跳过重载不影响安全: 上面的 setAllowedPage 已按本次目标页重新登记, onQuery 仍逐次精确匹配。
     */
    private static void openScreen(String pageUrl, String title, boolean forceReload, boolean fullscreen) {
        socialPageOpen = false;
        WebBrowser b = ensureBrowser(pageUrl, forceReload);
        if (b == null) {
            return;
        }
        BRIDGE.onScreenOpened();
        // 页面是常驻 SPA: 关面板只是隐藏 MC 的 Screen, React 树原样活着, 于是重新打开时看到的还是上次
        // 拉取的那份数据 (玩家在游戏里赚了钱、做完了任务, 面板上一个数都不动)。借既有事件通道通知页面
        // 把缓存全部作废重拉 —— 这条必须在 setScreen 之前发, 否则 onScreenOpened 尚未置位, 桥会以
        // "界面已关闭" 为由把页面随后发起的那批请求全部挡掉。
        BRIDGE.onEvent("panelOpened", "{}");
        Minecraft.getInstance().setScreen(new WebUiScreen(b, Component.literal(title), fullscreen));
    }

    /**
     * 创建或复用单例浏览器并让它停在目标页。返回 {@code null} 表示前置缺失/创建失败（已提示）。
     *
     * <p>抽出来是为了让“掏平板动画”能在不显示 Screen 的情况下先把页面跑起来：动画结束后
     * {@link #showPreparedSocialScreen()} 只需要把同一个浏览器挂到界面上。</p>
     */
    @Nullable
    private static WebBrowser ensureBrowser(String pageUrl, boolean forceReload) {
        Minecraft mc = Minecraft.getInstance();
        if (!ModList.get().isLoaded("mcef") || !MCEF.isInitialized()) {
            hint(mc, "[MiningDim] WebUI 不可用: 未安装或未初始化 MCEF。");
            return null;
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
                return null;
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
        return b;
    }

    /**
     * 拍照：先关掉平板回到世界，等待两帧让 HUD 隐藏后的世界画面进入主帧缓冲，再抓图。
     * 抓到的原图写入 screenshots，同时生成一张小缩略图回填页面；缩略图随后可随说说上传。
     */
    public static void captureSocialPhoto() {
        Minecraft mc = Minecraft.getInstance();
        if (captureInProgress) {
            return;
        }
        if (!socialPageOpen || browser == null || !(mc.screen instanceof WebUiScreen)) {
            return;
        }
        captureInProgress = true;
        captureTicks = 2;
        hideGuiBeforeCapture = mc.options.hideGui;
        cameraBeforeReopen = null;
        mc.options.hideGui = true;
        mc.setScreen(null);
    }

    private static boolean hideGuiBeforeCapture;

    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !captureInProgress) {
            return;
        }
        if (captureTicks > 0) {
            captureTicks--;
            return;
        }
        finishSocialCapture();
    }

    private static void finishSocialCapture() {
        Minecraft mc = Minecraft.getInstance();
        NativeImage image = null;
        try {
            image = Screenshot.takeScreenshot(mc.getMainRenderTarget());
            Path dir = mc.gameDirectory.toPath().resolve("screenshots");
            Files.createDirectories(dir);
            String fileName = "social-" + LocalDateTime.now().format(PHOTO_STAMP) + ".png";
            File file = dir.resolve(fileName).toFile();
            image.writeToFile(file);
            BufferedImage full = ImageIO.read(file);
            String fullUri = jpegDataUri(full, 1280, 720, 0.82f);
            String thumbUri = thumbnailDataUri(full);
            runCameraBeforeReopen();
            reopenSocialScreen();
            String script = "if (typeof window.miningdimOnPhotoCaptured === 'function') {"
                    + " window.miningdimOnPhotoCaptured(" + GSON.toJson(fullUri) + ","
                    + GSON.toJson(thumbUri) + "," + GSON.toJson(fileName) + "); }";
            WebBrowser b = browser;
            if (b != null) {
                b.executeJavaScript(script);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("社交平板拍照失败", e);
            runCameraBeforeReopen();
            reopenSocialScreen();
            WebBrowser b = browser;
            if (b != null) {
                b.executeJavaScript("if (typeof window.miningdimOnPhotoError === 'function') {"
                        + " window.miningdimOnPhotoError(" + GSON.toJson(String.valueOf(e.getMessage())) + "); }");
            }
        } finally {
            if (image != null) {
                image.close();
            }
            mc.options.hideGui = hideGuiBeforeCapture;
            captureInProgress = false;
        }
    }

    /** 将一张世界截图压成 JPEG data URI；页面用它显示原图。 */
    private static String jpegDataUri(BufferedImage source, int maxWidth, int maxHeight, float quality)
            throws IOException {
        BufferedImage scaled = scaleToFit(source, maxWidth, maxHeight);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        var writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        var param = writer.getDefaultWriteParam();
        param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(quality);
        try (var output = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(output);
            writer.write(null, new javax.imageio.IIOImage(scaled, null, null), param);
        } finally {
            writer.dispose();
        }
        return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
    }

    /** 缩略图必须能塞进一次 WebUI JSON 回执 (服务端限 6000 字符)，逐档降尺寸/质量。 */
    private static String thumbnailDataUri(BufferedImage source) throws IOException {
        int[][] sizes = {{320, 180, 55}, {256, 144, 50}, {192, 108, 45}, {160, 90, 42}};
        String last = null;
        for (int[] spec : sizes) {
            last = jpegDataUri(source, spec[0], spec[1], spec[2] / 100.0f);
            if (last.length() <= 5900) {
                return last;
            }
        }
        return last;
    }

    private static BufferedImage scaleToFit(BufferedImage source, int maxWidth, int maxHeight) {
        double scale = Math.min((double) maxWidth / source.getWidth(), (double) maxHeight / source.getHeight());
        scale = Math.min(1.0, scale);
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(source, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /** Screen 的 ESC / 被其它界面替换路径统一清理在途回调与 UI 音效。 */
    static void onScreenClosed() {
        if (captureInProgress || screenCloseSuspended) {
            return;
        }
        socialPageOpen = false;
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
        boolean animated = tabletCloseRequested;
        tabletCloseRequested = false;
        Runnable hook = animated ? socialScreenClosedAnimatedHook : socialScreenClosedDirectHook;
        if (hook != null) {
            hook.run();
        }
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
                if (!requestSocialCloseWithAnimation()) {
                    mc.setScreen(null);
                }
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
