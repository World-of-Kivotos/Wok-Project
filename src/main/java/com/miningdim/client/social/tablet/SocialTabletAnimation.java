package com.miningdim.client.social.tablet;

import com.miningdim.client.social.SocialCameraMode;
import com.miningdim.client.webui.WebUiClient;
import com.miningdim.client.webui.WebUiScreen;
import com.miningdim.config.MiningClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;

import java.util.Set;

/**
 * 社交平板的“掏出/收起”动画控制器（步战 0.5.0-beta.4 的完整版时间轴，外观换成 Slate 11 +
 * 可切换的手：白手套 / 玩家皮肤）。
 *
 * <p>整个客户端只有一个 {@link TabletMotion}。开场：~ 键 → 浏览器先在后台准备好（页面照常加载），
 * 世界继续渲染，3D 平板与手在 {@link RenderHandEvent} 里画出，推进到 p = 1 的那一帧把
 * {@link WebUiScreen} 显示出来——此时页面平面正好铺满视口，机身边框刚好移出屏幕，和 2D 界面无缝接上。
 * 收起：界面关闭后按 p → 0 播放同一套时间轴（浏览器画面仍在贴图上），结束后回到世界。</p>
 *
 * <p>设置 {@code client.socialTabletAnimation}：{@code full}（完整 3D）/ {@code quick}（80ms 快开）/
 * {@code off}（不播动画，直接开界面）。</p>
 */
public final class SocialTabletAnimation {
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("miningdim/social-tablet");
    private static final TabletMotion MOTION = new TabletMotion();
    /** HUD 在动画期间让位，但这些浮层保留（聊天、标题、玩家列表、调试）。 */
    private static final Set<String> HUD_KEEP = Set.of("minecraft:chat", "minecraft:player_list",
            "minecraft:title", "minecraft:subtitle", "minecraft:debug", "minecraft:fps_graph");

    private static boolean installed;
    /** 开场动画正在播、结束后要把准备好的界面显示出来。 */
    private static boolean pendingShow;
    private static ResourceLocation lastDimension;
    /** 这一帧的手部 pass 是否真的跑到了动画（scheme A 可用性检测）。 */
    private static boolean handPassFrame;
    /** 帧首的状态：用于识别“动画是在这一帧中途才开始的”，避免误判手部 pass 缺席。 */
    private static TabletMotion.State stateAtFrameStart = TabletMotion.State.IDLE;
    private static int lastSlot = -1;
    private static boolean meshPreloaded;
    private static boolean drawWarned;
    /** `/socialanim hands` 的会话内覆盖；{@code null} = 读配置。 */
    private static TabletHandStyle handStyleOverride;

    private SocialTabletAnimation() {
    }

    public static void init(IEventBus forgeBus) {
        if (installed) {
            return;
        }
        installed = true;
        forgeBus.addListener(EventPriority.HIGH, false, RenderHandEvent.class,
                SocialTabletAnimation::onRenderHand);
        forgeBus.addListener(EventPriority.HIGHEST, false, TickEvent.RenderTickEvent.class,
                SocialTabletAnimation::onRenderTick);
        forgeBus.addListener(EventPriority.LOWEST, false, TickEvent.RenderTickEvent.class,
                SocialTabletAnimation::onRenderTickEnd);
        forgeBus.addListener(EventPriority.NORMAL, false, TickEvent.ClientTickEvent.class,
                SocialTabletAnimation::onClientTick);
        forgeBus.addListener(EventPriority.NORMAL, false,
                net.minecraftforge.client.event.InputEvent.InteractionKeyMappingTriggered.class,
                SocialTabletAnimation::onInteraction);
        forgeBus.addListener(EventPriority.HIGHEST, false, RenderGuiOverlayEvent.Pre.class,
                SocialTabletAnimation::onOverlayPre);
        forgeBus.addListener(EventPriority.NORMAL, false, ClientPlayerNetworkEvent.LoggingOut.class,
                event -> reset());
        WebUiClient.setSocialScreenHooks(SocialTabletAnimation::onScreenOpened,
                SocialTabletAnimation::onScreenClosedAnimated,
                SocialTabletAnimation::onScreenClosedDirect);
    }

    // =========================================================================================
    // 查询
    // =========================================================================================

    public static boolean isAnimating() {
        return MOTION.state() == TabletMotion.State.OPENING
                || MOTION.state() == TabletMotion.State.CLOSING;
    }

    public static boolean isShown() {
        return MOTION.state() == TabletMotion.State.SHOWN;
    }

    /** 开场动画还在播（~ 键此时应跳到结尾，而不是再开一个界面）。 */
    public static boolean isOpening() {
        return MOTION.state() == TabletMotion.State.OPENING;
    }

    /** 按键跳过开场：100ms 收尾到 p = 1（步战的 KEY finish）。 */
    public static void finishOpening() {
        if (MOTION.state() != TabletMotion.State.OPENING) {
            return;
        }
        MOTION.finish(now());
        drainEvents();
    }

    /**
     * 验收用：把开场动画冻结在 p（`/socialanim freeze 0.61`），方便对着固定姿态截图。
     * 没有浏览器（未装 MCEF）时也能走这条路径看 3D 机身与手套，只是屏幕是暗玻璃。
     */
    public static void freezeAt(double p) {
        if (!isAnimating()) {
            if (!openFromWorld()) {
                startPreview();
            }
        }
        if (!isAnimating()) {
            return;
        }
        MOTION.freeze(Math.max(0.0D, Math.min(1.0D, p)));
        drainEvents();
    }

    /** 无浏览器的预览开场：只跑动作与设备层，不会去显示界面（验收/调试用）。 */
    private static void startPreview() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        MOTION.configure(new TabletMotion.Config(TabletMode.FULL, false, TabletHand.GUN, true,
                TabletScreenKind.SQUAD));
        MOTION.open(now());
        pendingShow = false;
        handPassFrame = false;
        lastDimension = dimension(minecraft);
        drainEvents();
    }

    /** 验收用：解冻，继续正常播放（`/socialanim resume`）。 */
    public static void unfreeze() {
        if (MOTION.frozen()) {
            MOTION.unfreeze(now());
        }
    }

    private static TabletMode mode() {
        try {
            TabletMode parsed = TabletMode.parse(MiningClientConfig.SOCIAL_TABLET_ANIMATION.get());
            return parsed == null ? TabletMode.FULL : parsed;
        } catch (RuntimeException exception) {
            return TabletMode.FULL;
        }
    }

    private static boolean soundsOn() {
        try {
            return MiningClientConfig.SOCIAL_TABLET_SOUNDS.get();
        } catch (RuntimeException exception) {
            return true;
        }
    }

    /** 当前手部外观：命令覆盖优先，否则读客户端配置。 */
    public static TabletHandStyle handStyle() {
        if (handStyleOverride != null) {
            return handStyleOverride;
        }
        try {
            return TabletHandStyle.parse(MiningClientConfig.SOCIAL_TABLET_HANDS.get());
        } catch (RuntimeException exception) {
            return TabletHandStyle.GLOVE;
        }
    }

    /** `/socialanim hands`：只影响本次客户端会话，不改写配置。 */
    public static void setHandStyle(TabletHandStyle style) {
        handStyleOverride = style;
    }

    private static double now() {
        return System.nanoTime() / 1.0E6D;
    }

    // =========================================================================================
    // 开场 / 收起
    // =========================================================================================

    /**
     * 从世界掏出平板。返回 {@code false} 表示这次没有动画（关闭了动画，或浏览器没准备好），
     * 调用方应退回直接开界面。
     */
    public static boolean openFromWorld() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return false;
        }
        if (MOTION.state() == TabletMotion.State.SHOWN
                || MOTION.state() == TabletMotion.State.OPENING) {
            return false;
        }
        TabletMode mode = mode();
        if (mode == TabletMode.OFF) {
            return false;
        }
        // 第三人称没有手部 pass，3D 动画什么也看不见：直接开界面。
        if (!minecraft.options.getCameraType().isFirstPerson()) {
            return false;
        }
        if (!WebUiClient.prepareSocialScreenForAnimation()) {
            return false;
        }
        // 3D 机身的挤出网格要读贴图，提前在读资源线程安全的时刻建好，别在渲染线程里做 I/O。
        SocialDeviceArt.mesh();
        TabletHand hand = TabletHand.classify(player.getMainHandItem().isEmpty(), false, false);
        // capture = true: 收起时从 p = 1（全屏）连续缩回手里，而不是从 READ 突然出现。
        // 我们不需要 2D 截屏层——浏览器贴图本身就是实时画面，3D 层全程都能画。
        MOTION.configure(new TabletMotion.Config(mode, false, hand, true,
                TabletScreenKind.SQUAD));
        if (!MOTION.open(now())) {
            return false;
        }
        pendingShow = true;
        handPassFrame = false;
        lastDimension = dimension(minecraft);
        drainEvents();
        return true;
    }

    /** 界面被正常关掉（Esc / 页面关闭按钮）：播放收起动画。 */
    public static void onScreenClosedAnimated() {
        pendingShow = false;
        if (MOTION.state() == TabletMotion.State.SHOWN
                || MOTION.state() == TabletMotion.State.OPENING) {
            MOTION.close(now());
            drainEvents();
        }
    }

    /** 界面在没有动画的情况下消失（被别的界面顶掉、断线等）：直接复位。 */
    public static void onScreenClosedDirect() {
        pendingShow = false;
        if (MOTION.state() != TabletMotion.State.IDLE) {
            MOTION.reset(now(), false);
            MOTION.drainEvents();
        }
    }
    /** 界面被直接打开（相机回场、/social 直开）：状态至少是 SHOWN，收起时才有动画可播。 */
    public static void onScreenOpened() {
        if (MOTION.state() == TabletMotion.State.IDLE) {
            configureForShow();
            MOTION.show(now());
            MOTION.drainEvents();
        }
    }

    private static void configureForShow() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        TabletHand hand = player == null ? TabletHand.GUN
                : TabletHand.classify(player.getMainHandItem().isEmpty(), false, false);
        MOTION.configure(new TabletMotion.Config(TabletMode.FULL, false, hand, true,
                TabletScreenKind.SQUAD));
    }

    private static ResourceLocation dimension(Minecraft minecraft) {
        return minecraft.level == null ? null : minecraft.level.dimension().location();
    }

    public static void reset() {
        cancelPrepared();
        handPassFrame = false;
        lastSlot = -1;
        MOTION.reset(now(), false);
        MOTION.drainEvents();
    }

    /** 开场动画没走到显示界面就被打断：让宿主把准备好的页面重新挂起。 */
    private static void cancelPrepared() {
        if (!pendingShow) {
            return;
        }
        pendingShow = false;
        WebUiClient.cancelPreparedSocialScreen();
    }

    // =========================================================================================
    // 帧推进
    // =========================================================================================

    private static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        double now = now();
        handPassFrame = false;
        MOTION.update(now);
        stateAtFrameStart = MOTION.state();
        drainEvents();
        if (pendingShow && MOTION.state() == TabletMotion.State.SHOWN) {
            pendingShow = false;
            if (minecraft.screen == null && !WebUiClient.showPreparedSocialScreen()) {
                WebUiClient.cancelPreparedSocialScreen();
                MOTION.reset(now, false);
            }
        }
        if (MOTION.state() == TabletMotion.State.IDLE) {
            pendingShow = false;
        }
    }

    /**
     * scheme B 兜底（步战 D17 的简化版）：手部 pass 这一帧没跑到（被其它 mod 取消、相机不在
     * 玩家身上、第三人称），就不做没有画面的空转——开场直接显示界面，收起直接结束。
     */
    private static void onRenderTickEnd(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (MOTION.state() == TabletMotion.State.IDLE || handPassFrame) {
            return;
        }
        if (MOTION.state() != stateAtFrameStart) {
            // 动画在这一帧中途才开始（键事件晚于帧首）：这一帧没有手部 pass 是正常的，下一帧再判定。
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen != null) {
            return;
        }
        double now = now();
        if (MOTION.state() == TabletMotion.State.OPENING) {
            MOTION.show(now);
        } else if (MOTION.state() == TabletMotion.State.CLOSING) {
            MOTION.reset(now, true);
        } else {
            return;
        }
        drainEvents();
    }

    /** 收起途中开火/使用物品：直接结束收起；开场途中点左键：跳到全屏（步战 §3.4 / §2.5）。 */
    private static void onInteraction(
            net.minecraftforge.client.event.InputEvent.InteractionKeyMappingTriggered event) {
        if (MOTION.state() == TabletMotion.State.OPENING && event.isAttack()) {
            // 点一下就把平板推到位，和步战的 jumpShown 同语义；这次点击不再打到世界里。
            MOTION.jumpShown(now());
            event.setCanceled(true);
            drainEvents();
            return;
        }
        if (MOTION.state() != TabletMotion.State.CLOSING) {
            return;
        }
        MOTION.interrupt(now(), event.isAttack() ? TabletMotion.Interrupt.FIRE
                : TabletMotion.Interrupt.AIM);
        drainEvents();
    }

    private static void drainEvents() {
        boolean sounds = soundsOn();
        for (TabletMotion.Event event : MOTION.drainEvents()) {
            if (event.type() == TabletMotion.Event.Type.SOUND && sounds) {
                SocialTabletSounds.play(event.name());
            }
        }
    }

    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            if (MOTION.state() != TabletMotion.State.IDLE || pendingShow) {
                reset();
            }
            return;
        }
        if (MOTION.state() == TabletMotion.State.IDLE) {
            if (!meshPreloaded && minecraft.player != null) {
                // 资源已就绪：把机身的挤出网格先建好，别等第一次按 ~ 才卡一下。
                meshPreloaded = true;
                SocialDeviceArt.mesh();
            }
            lastSlot = minecraft.player.getInventory().selected;
            lastDimension = dimension(minecraft);
            return;
        }
        double now = now();
        int slot = minecraft.player.getInventory().selected;
        if (lastSlot >= 0 && slot != lastSlot
                && MOTION.state() == TabletMotion.State.CLOSING) {
            MOTION.interrupt(now, TabletMotion.Interrupt.HOTBAR);
        }
        lastSlot = slot;
        if (minecraft.player.isDeadOrDying() || minecraft.player.isRemoved()) {
            MOTION.interrupt(now, TabletMotion.Interrupt.DEATH);
        } else if (!java.util.Objects.equals(lastDimension, dimension(minecraft))) {
            MOTION.interrupt(now, TabletMotion.Interrupt.DIMENSION);
        } else if (minecraft.player.isPassenger()) {
            MOTION.interrupt(now, TabletMotion.Interrupt.VEHICLE);
        } else if (SocialCameraMode.isActive()) {
            MOTION.interrupt(now, TabletMotion.Interrupt.CAMERA);
        } else if (minecraft.screen != null && !(minecraft.screen instanceof WebUiScreen)) {
            // 被暂停菜单等界面顶掉：动画没有继续的意义。
            MOTION.reset(now, false);
        }
        if (MOTION.state() == TabletMotion.State.IDLE) {
            cancelPrepared();
            lastDimension = dimension(minecraft);
        }
        drainEvents();
    }

    // =========================================================================================
    // 渲染
    // =========================================================================================

    private static void onOverlayPre(RenderGuiOverlayEvent.Pre event) {
        if (!isAnimating()) {
            return;
        }
        ResourceLocation id = event.getOverlay().id();
        if (!HUD_KEEP.contains(id.toString())) {
            event.setCanceled(true);
        }
    }

    private static void onRenderHand(RenderHandEvent event) {
        if (!isAnimating()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.screen != null) {
            return;
        }
        handPassFrame = true;
        if (event.getHand() != InteractionHand.MAIN_HAND) {
            event.setCanceled(true);
            return;
        }
        try {
            drawFrame(event, minecraft);
        } catch (RuntimeException exception) {
            // 第一版真机验证的安全网：3D 层出任何问题都不该把客户端带崩，退回直接开界面。
            event.setCanceled(true);
            if (!drawWarned) {
                drawWarned = true;
                LOGGER.warn("[SocialTablet] 3D 平板绘制失败，本次退回直接开界面", exception);
            }
            double now = now();
            if (MOTION.state() == TabletMotion.State.OPENING) {
                MOTION.show(now);
            } else if (MOTION.state() == TabletMotion.State.CLOSING) {
                MOTION.reset(now, true);
            }
            drainEvents();
        }
    }

    private static void drawFrame(RenderHandEvent event, Minecraft minecraft) {
        TabletPose3D.Context ctx = context(minecraft);
        TabletPose3D.Face face = ctx.face();
        TabletD2Geometry geom = ctx.geom();
        TabletUnits units = ctx.term();
        boolean close = MOTION.curve() == TabletMotion.Curve.CLOSE;
        TabletPose3D.Pose pose = TabletPose3D.poseAt(MOTION.p(), face, ctx.kRead(), units, close,
                ctx.end(), ctx.zoom(), MOTION.carry(now()));
        double kf = TabletPose3D.kf(RenderSystemP11.read());
        SocialTabletRenderer.HandAppearance appearance = new SocialTabletRenderer.HandAppearance(
                handStyle(), minecraft.player.getSkinTextureLocation(),
                "slim".equals(minecraft.player.getModelName()));
        SocialTabletRenderer.draw(event.getPoseStack(), event.getMultiBufferSource(),
                event.getPackedLight(), kf, pose, face, geom, MOTION.p(),
                !minecraft.player.isInvisible(), appearance, WebUiClient.socialScreenTextureId(),
                WebUiClient.socialScreenPainted());
        event.setCanceled(true);
    }

    private static TabletPose3D.Context context(Minecraft minecraft) {
        int guiW = minecraft.getWindow().getGuiScaledWidth();
        int guiH = minecraft.getWindow().getGuiScaledHeight();
        int scale = Math.max(1, (int) minecraft.getWindow().getGuiScale());
        TabletUnits units = TabletUnits.fullscreen(guiW, guiH, scale)
                .withPhysical(minecraft.getWindow().getWidth(), minecraft.getWindow().getHeight());
        return TabletPose3D.Context.of(units, units, TabletScreenKind.SQUAD,
                RenderSystemP11.read(), minecraft.player != null && minecraft.player.isInvisible());
    }

    /** 手部投影的 m11（FOV 补偿），拿不到时退回 70° 手投影。 */
    private static final class RenderSystemP11 {
        private static double read() {
            try {
                double m11 = com.mojang.blaze3d.systems.RenderSystem.getProjectionMatrix().m11();
                return m11 > 0.0D ? m11 : TabletPose3D.P11_HAND;
            } catch (RuntimeException exception) {
                return TabletPose3D.P11_HAND;
            }
        }
    }
}
