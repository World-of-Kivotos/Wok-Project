package com.miningdim.client.social.tablet;



import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every constant of the "take out the tablet" animation (core 0.5.0-beta.4), copied one to one
 * from the preview's {@code ui-preview/tablet-anim/page/anim-spec.js} (where DESIGN.md and the
 * preview disagree, the preview's numbers win). Times are milliseconds, {@code p} is the progress
 * 0..1, positions are blocks (1 block = 16 MC pixels), angles are degrees.
 *
 * <p>Scheme A's time maps are the round-one ({@code v1}) maps divided by {@link #TEMPO}; scheme B,
 * the quick setting and the 2-second reopen factor are not affected by the tempo. The patched
 * sound table (stow by hand, IMPL_PLAN D3) lives in {@link TabletCues}.
 *
 * <p>Pure data: nothing here touches Minecraft. Colour constants that the D2 shell already owns
 * are inlined compile-time constants of {@link SocialTabletColors}.
 */
public final class TabletAnimationModel {
    /** The core version this animation ships in. */
    public static final String VERSION = "0.5.0-beta.4";
    /** One preview frame (60 fps). */
    public static final double FRAME_MS = 1000.0D / 60.0D;
    /** Modelling unit: 1u = 1/32 block (half an MC pixel). */
    public static final double U = 1.0D / 32.0D;

    // =========================================================================================
    // Scheme A — time maps
    // =========================================================================================

    /** Round four: every A time map is its v1 time divided by this (gun open 400 → 1333 ms). */
    public static final double TEMPO = 0.3D;

    private static final double[][] V1_OPEN_GUN = {{0, 0}, {400, 1}};
    /** Review fix F3: the knee sits at K0 (0.17) while the tablet is still off screen. */
    private static final double[][] V1_OPEN_EMPTY = {{0, 0}, {60, 0.17}, {360, 1}};
    private static final double[][] V1_CLOSE_GUN = {{0, 0.61}, {60, 0.40}, {95, 0.30}, {220, 0}};
    private static final double[][] V1_CLOSE_EMPTY = {{0, 0.61}, {60, 0.40}, {88, 0.30}, {180, 0}};
    /** C1 (screen capture) close: an extra 1 → 0.74 → 0.61 head; review fix F1 slowed the head. */
    private static final double[][] V1_CLOSE_CAPTURE_GUN = {{0, 1}, {65, 0.74}, {85, 0.61},
            {140, 0.40}, {170, 0.30}, {270, 0}};
    private static final double[][] V1_CLOSE_CAPTURE_EMPTY = {{0, 1}, {65, 0.74}, {85, 0.61},
            {140, 0.40}, {163, 0.30}, {230, 0}};

    /** The v1 open map ({@code a.v1.open}). */
    public static double[][] v1Open(boolean emptyHand) {
        return copy(emptyHand ? V1_OPEN_EMPTY : V1_OPEN_GUN);
    }

    /** The v1 close map without screen capture ({@code a.v1.close}). */
    public static double[][] v1Close(boolean emptyHand) {
        return copy(emptyHand ? V1_CLOSE_EMPTY : V1_CLOSE_GUN);
    }

    /** The v1 C1 close map ({@code a.v1.closeCapture}). */
    public static double[][] v1CloseCapture(boolean emptyHand) {
        return copy(emptyHand ? V1_CLOSE_CAPTURE_EMPTY : V1_CLOSE_CAPTURE_GUN);
    }

    /** {@code a.open}: the v1 open map ÷ {@link #TEMPO}. */
    public static double[][] openPts(boolean emptyHand) {
        return tempo(emptyHand ? V1_OPEN_EMPTY : V1_OPEN_GUN);
    }

    /** {@code a.close}: the old close (no capture, starts at READ) ÷ {@link #TEMPO}. */
    public static double[][] closePts(boolean emptyHand) {
        return tempo(emptyHand ? V1_CLOSE_EMPTY : V1_CLOSE_GUN);
    }

    /** {@code a.closeCapture}: the C1 close ÷ {@link #TEMPO}. */
    public static double[][] closeCapturePts(boolean emptyHand) {
        return tempo(emptyHand ? V1_CLOSE_CAPTURE_EMPTY : V1_CLOSE_CAPTURE_GUN);
    }

    /** C1 (the close replays the last screen frame) is on by default. */
    public static final boolean CAPTURE_DEFAULT = true;
    /** Closing during the wake band (0.61 &lt; p &lt; 0.74) does not capture and starts at 0.61. */
    public static final double CAPTURE_SKIP_WAKE_FROM = 0.61D;
    public static final double CAPTURE_SKIP_WAKE_TO = 0.74D;
    /** Close curve (from SHOWN): the zoom segment eases with this, without overshoot or sink. */
    public static final TabletEasing CLOSE_EASE = TabletEasing.MIN_JERK;
    /** Close curve: the raise segment skips these key frames (no K2 bounce). */
    public static final Set<String> CLOSE_SKIP_KEYS = Set.of("K2");

    // ---- progress segments (§3.2)
    public static final double SEG_GUN_FROM = 0.0D;
    public static final double SEG_GUN_TO = 0.30D;
    public static final double SEG_RAISE_FROM = 0.17D;
    /** READ: rotation is exactly 0 from here on. */
    public static final double SEG_RAISE_TO = 0.61D;
    public static final double SEG_WAKE_FROM = 0.61D;
    public static final double SEG_WAKE_TO = 0.74D;
    public static final double SEG_ZOOM_FROM = 0.74D;
    public static final double SEG_ZOOM_TO = 1.0D;

    // ---- device plane (§3.1)
    /** Plane width in blocks; the plane height is width · H / W, capped at {@link #FACE_MAX_HEIGHT}. */
    public static final double FACE_WIDTH = 0.75D;
    public static final double FACE_MAX_HEIGHT = 0.5625D;
    /** The hand pass always projects with this vertical field of view. */
    public static final double HAND_FOV_DEG = 70.0D;
    /** READ: the rectangle's bottom edge sits at 92 % of the screen height. */
    public static final double READ_BOTTOM = 0.92D;
    /** Terminal end rectangle: the device rests in its D region (identity). */
    public static final double FILL_S = 1.0D;
    public static final double FILL_CY = 0.5D;

    // ---- k_read (§3.1, §9.1 item 22)
    public static final double K_READ_MIN = 0.45D;
    public static final double K_READ_MAX = 0.70D;
    public static final double K_READ_TARGET = 0.55D;
    public static final double K_READ_FALLBACK = 0.55D;
    public static final int K_READ_MIN_LP_PX = 2;
    /** Smooth downscale: halve step by step while the ratio is below this. */
    public static final double SMOOTH_HALVE_BELOW = 0.5D;

    // ---- raise key frames (§3.2)

    /**
     * One raise key frame. {@code off*}: offset from READ, x and y in plane heights, z in
     * |z_read| (negative = farther); rotations in degrees, applied translate → rotY(yaw) →
     * rotZ(roll) → rotX(pitch).
     */
    public record Key(String id, double p, double offX, double offY, double offZ,
                      double yaw, double pitch, double roll) {
    }

    public static final List<Key> KEYS = List.of(
            new Key("K0", 0.17D, -0.45D, -1.60D, -0.10D, 8.0D, -65.0D, 12.0D),
            new Key("K1", 0.37D, -0.15D, -0.45D, -0.025D, 3.0D, -28.0D, 5.0D),
            new Key("K2", 0.53D, 0.0D, 0.025D, 0.0D, 0.0D, -2.0D, 0.0D),
            new Key("READ", 0.61D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D));
    /** The close curve's y never rises above READ (a safeguard; that curve skips K2 anyway). */
    public static final boolean CLOSE_NO_OVERSHOOT = true;
    /** pchip start slope = first segment slope × this. */
    public static final double RAISE_START_SLOPE = 1.6D;

    // ---- easing (§9.1)
    public static final TabletEasing EASE_GUN = TabletEasing.EASE_IN_QUAD;
    public static final TabletEasing EASE_EMPTY_ARM = TabletEasing.EASE_IN_QUAD;
    public static final TabletEasing EASE_WAKE_SPREAD = TabletEasing.EASE_OUT_QUAD;
    public static final TabletEasing EASE_MASK = TabletEasing.LINEAR;
    /** Zoom easing used only when the zoom settle is switched off. */
    public static final TabletEasing EASE_ZOOM = TabletEasing.EASE_IN_OUT_SINE;

    // ---- settle (round four, review fixes F2, F6, F7)
    /** Gun anticipation: sin² up to {@code UP_P}, cos² down to {@code TO_P}. */
    public static final double GUN_LIFT_UP_P = 0.035D;
    public static final double GUN_LIFT_TO_P = 0.20D;
    public static final double GUN_LIFT_Y = 0.03D;
    public static final double GUN_LIFT_PITCH = 2.0D;
    /** Held at READ the plane sinks a little (screen fraction, positive = down), open curve only. */
    public static final double BREATH_FROM = 0.61D;
    public static final double BREATH_TO = 0.74D;
    public static final double BREATH_AMP = 0.012D;
    public static final double BREATH_PEAK_AT = 0.655D;

    /** Shared by the motion carry and pose sampling so motion does not depend on frame geometry. */
    public static double breathAt(double p) {
        if (!(BREATH_AMP > 0.0D) || p <= BREATH_FROM || p >= BREATH_TO) {
            return 0.0D;
        }
        if (p < BREATH_PEAK_AT) {
            double s = Math.sin(Math.PI / 2.0D * (p - BREATH_FROM) / (BREATH_PEAK_AT - BREATH_FROM));
            return BREATH_AMP * s * s;
        }
        double c = Math.cos(Math.PI / 2.0D * (p - BREATH_PEAK_AT) / (BREATH_TO - BREATH_PEAK_AT));
        return BREATH_AMP * c * c;
    }

    /** Zoom: minimum jerk to 1 + o, then ease back; o is capped by the tier's edge margin. */
    public static final double ZOOM_OVERSHOOT = 0.015D;
    public static final double ZOOM_PEAK_AT = 0.75D;
    public static final TabletEasing ZOOM_EASE = TabletEasing.MIN_JERK;
    public static final TabletEasing ZOOM_SETTLE_EASE = TabletEasing.EASE_IN_OUT_SINE;
    public static final double ZOOM_EDGE_SAFETY = 0.9D;

    // ---- the gun goes down (§3.2)
    public static final double GUN_OFFSET_X = 0.06D;
    public static final double GUN_OFFSET_Y = -0.70D;
    public static final double GUN_OFFSET_Z = 0.05D;
    public static final double GUN_PITCH = -25.0D;
    public static final double GUN_ROLL = -8.0D;
    /** From here on the gun (or the empty-hand arm) is not drawn. */
    public static final double GUN_HIDE_AT = 0.30D;

    // ---- wake (§3.5): only on the glass opening S
    public static final double WAKE_FROM = 0.61D;
    public static final double WAKE_SPREAD_TO = 0.71D;
    public static final double WAKE_MASK_TO = 0.74D;
    public static final double WAKE_MASK_ALPHA = 0.6D;
    public static final double WAKE_EDGE_ALPHA = 0.6D;
    public static final int WAKE_EDGE_PX = 1;
    /** The 2D device's drop shadow fades in with the wake spread. */
    public static final boolean WAKE_SHADOW_FADE = true;

    // ---- world dim (the D2 backdrop's alpha factor)
    public static final double BACKDROP_FROM = 0.61D;
    public static final double BACKDROP_TO = 1.0D;
    public static final TabletEasing BACKDROP_EASE = TabletEasing.EASE_IN_OUT_SINE;
    /** C1 close: the backdrop alpha runs the same curve back from 1 to 0. */
    public static final boolean BACKDROP_CAPTURE_CLOSE = true;

    // ---- sleep line on the close
    public static final double SLEEP_FROM = 0.61D;
    public static final double SLEEP_TO = 0.47D;
    public static final int SLEEP_LINE_PX = 1;
    public static final double SLEEP_ALPHA = 0.6D;

    // ---- HUD (§9.1 item 10, review fix F4)
    public static final double HUD_RESTORE_CROSSHAIR_AT_CLOSE_P = 0.40D;
    public static final double HUD_RESTORE_ALL_AT_CLOSE_P = 0.17D;
    /** Overlays kept while the tablet hides the HUD (preview ids). */
    public static final List<String> HUD_KEEP = List.of("chat", "title", "subtitles");

    /** Full-bright light for the front face. */
    public static final int FULL_BRIGHT = 0xF000F0;

    // ---- the hands (§3.5)
    public static final double HANDS_DX_WIDE = 0.05D;
    public static final double HANDS_DX_TALL = 0.05D;
    public static final double HANDS_DY_WIDE = 0.11D;
    public static final double HANDS_DY_TALL = 0.07D;
    public static final double HANDS_DZ_WIDE = 0.0D;
    public static final double HANDS_DZ_TALL = 0.0D;
    /** Zoom: both hands let go and leave the picture down and outwards before p 0.97. */
    public static final double HANDS_EXIT_FROM = 0.74D;
    public static final double HANDS_EXIT_TO = 0.97D;
    public static final double HANDS_EXIT_X = 0.16D;
    public static final double HANDS_EXIT_Y = -0.30D;
    public static final double HANDS_EXIT_Z = 0.0D;
    public static final TabletEasing HANDS_EXIT_EASE = TabletEasing.EASE_IN_QUAD;
    /** Margin (blocks) behind the back plate for fist samples inside the device E region. */
    public static final double HANDS_BEHIND_PLATE = 0.005D;
    public static final double HANDS_OUTER_Y = 90.0D;
    public static final double HANDS_CHAIN_ROT_Y = 92.0D;
    public static final double HANDS_CHAIN_ROT_X = 45.0D;
    public static final double HANDS_CHAIN_ROT_Z = -41.0D;
    public static final double HANDS_CHAIN_TX = 0.12D;
    public static final double HANDS_CHAIN_TY = -1.1D;
    public static final double HANDS_CHAIN_TZ = 0.45D;
    /** The chain's rotY is mirrored left / right (right 92°, left 88°). */
    public static final boolean HANDS_MIRROR_YAW = true;
    /** Steve arm ModelPart pivots (model pixels): right (−5, 2, 0), left (5, 2, 0). */
    public static final double HANDS_PIVOT_RIGHT_X = -5.0D;
    public static final double HANDS_PIVOT_LEFT_X = 5.0D;
    public static final double HANDS_PIVOT_Y = 2.0D;
    public static final double HANDS_PIVOT_Z = 0.0D;
    /** {@code bobModelPart} at age 0: zRot ±0.1 radians. */
    public static final double HANDS_BOB_Z_ROT = 0.1D;
    /** Arm cuboid with the 0.25 sleeve (model pixels, y down): x0, y0, z0, x1, y1, z1. */
    private static final double[] ARM_BOX_RIGHT = {-3.25D, -2.25D, -2.25D, 1.25D, 10.25D, 2.25D};
    private static final double[] ARM_BOX_LEFT = {-1.25D, -2.25D, -2.25D, 3.25D, 10.25D, 2.25D};
    public static final double HANDS_SLEEVE = 0.25D;
    /** The fist is the arm cuboid from this y (model pixels) down. */
    public static final double HANDS_FIST_FROM_Y = 6.0D;
    public static final double HOLD_MIN_OVERLAP_LP = 2.0D;
    public static final double HOLD_MIN_OVERLAP_Y = 0.45D;
    public static final double HOLD_MAX_HIDDEN = 0.5D;

    /** The arm cuboid of {@code right} / left, with the sleeve. */
    public static double[] armBox(boolean right) {
        return (right ? ARM_BOX_RIGHT : ARM_BOX_LEFT).clone();
    }

    // ---- vanilla empty hand (renderPlayerArm, swing 0, equip 0), right arm
    public static final double EMPTY_ARM_TX = 0.64000005D;
    public static final double EMPTY_ARM_TY = -0.6D;
    public static final double EMPTY_ARM_TZ = -0.71999997D;
    public static final double EMPTY_ARM_ROT_Y = 45.0D;
    public static final double EMPTY_ARM_T2X = -1.0D;
    public static final double EMPTY_ARM_T2Y = 3.6D;
    public static final double EMPTY_ARM_T2Z = 3.5D;
    public static final double EMPTY_ARM_ROT_Z = 120.0D;
    public static final double EMPTY_ARM_ROT_X = 200.0D;
    public static final double EMPTY_ARM_ROT_Y2 = -135.0D;
    public static final double EMPTY_ARM_T3X = 5.6D;
    public static final double EMPTY_ARM_T3Y = 0.0D;
    public static final double EMPTY_ARM_T3Z = 0.0D;

    // ---- sounds (§3.6; the cue table is TabletCues)
    /** Playback volume (vanilla UI click), times each event's balanced volume in sounds.json. */
    public static final double SFX_VOLUME = 0.25D;
    /** A sound is not repeated within this many milliseconds. */
    public static final double SFX_NO_REPEAT_MS = 150.0D;
    public static final String SFX_SEARCH = "search";
    public static final double SFX_SEARCH_EVERY_MS = 500.0D;
    public static final int SFX_SEARCH_MAX_TICKS = 6;
    /** During a key finish only these sounds play. */
    public static final Set<String> SFX_FINISH_KEEP = Set.of("ready");
    public static final double SFX_CUT_TAU_MS = 12.0D;
    public static final double SFX_CUT_STOP_MS = 60.0D;

    // =========================================================================================
    // Scheme B (§4)
    // =========================================================================================

    private static final double[][] B_OPEN = {{0, 0}, {200, 1}};
    /** Terminal close: falls from 0.55 (lifted) in 120 ms. */
    private static final double[][] B_CLOSE = {{0, 0.55}, {120, 0}};
    public static final double B_LIFT_END = 0.55D;
    public static final TabletEasing B_EASE_LIFT = TabletEasing.EASE_OUT_QUAD;
    public static final double B_SCAN_FROM = 0.55D;
    public static final double B_SCAN_TO = 1.0D;
    public static final double B_SCAN_ALPHA = 0.6D;
    public static final double B_BACKDROP_TO = 0.55D;
    /** Closing, the glass well is a clear GLASS at this alpha. */
    public static final double B_CLOSE_GLASS_ALPHA = 0.20D;

    // ---- B on the tactical map (old B, D2 frame)
    public static final double BM_K0 = 0.80D;
    public static final double BM_START_FRAC = 0.78D;
    public static final double BM_LIFT_END = 0.55D;
    public static final double BM_ZOOM_FROM = 0.60D;
    public static final TabletEasing BM_EASE_LIFT = TabletEasing.EASE_OUT_CUBIC;
    public static final TabletEasing BM_EASE_ZOOM = TabletEasing.EASE_IN_OUT_CUBIC;
    public static final double BM_SLEEP_FROM = 0.2D;
    public static final double BM_SLEEP_SPAN = 0.3D;
    public static final double BM_SCAN_FROM = 0.3D;
    public static final double BM_SCAN_SPAN = 0.25D;
    public static final double BM_CLOSE_GLASS_ALPHA = 0.20D;
    private static final double[][] BM_CLOSE = {{0, 1}, {120, 0}};

    /** {@code b.open} (terminal and map). */
    public static double[][] bOpenPts() {
        return copy(B_OPEN);
    }

    /** {@code b.close} (terminal) or {@code b.map.close} (map and every other full-screen page). */
    public static double[][] bClosePts(boolean terminal) {
        return copy(terminal ? B_CLOSE : BM_CLOSE);
    }

    // ---- quick setting (A and B)
    public static final double QUICK_FROM = 0.60D;
    private static final double[][] QUICK_OPEN = {{0, 0.60}, {80, 1}};
    private static final double[][] QUICK_CLOSE = {{0, 1}, {60, 0.60}};
    public static final TabletEasing QUICK_EASE = TabletEasing.EASE_OUT_CUBIC;

    public static double[][] quickOpenPts() {
        return copy(QUICK_OPEN);
    }

    public static double[][] quickClosePts() {
        return copy(QUICK_CLOSE);
    }

    // ---- input threshold p★ (§2.5)
    public static final double P_STAR_A = 0.61D;
    public static final double P_STAR_B = 0.55D;
    public static final double P_STAR_QUICK = 0.55D;

    /** p★ of {@code path}; NaN for {@link TabletPath#OFF} (no threshold). */
    public static double pStar(TabletPath path) {
        return switch (path) {
            case A3D -> P_STAR_A;
            case B2D -> P_STAR_B;
            case QUICK -> P_STAR_QUICK;
            case OFF -> Double.NaN;
        };
    }

    /**
     * Java patch (IMPL_PLAN D4, review leftover ②): closing in the wake band (skipWake) carries
     * the plane's sink {@code breathAt(p_last)} into the close and lets it fade over this long,
     * instead of jumping up to 13 px in one frame as the preview does.
     */
    public static final double SKIP_WAKE_CARRY_MS = 100.0D;
    public static final TabletEasing SKIP_WAKE_CARRY_EASE = TabletEasing.EASE_IN_OUT_SINE;

    // ---- reopen within 2 s, key finish
    public static final double REOPEN_WINDOW_MS = 2000.0D;
    public static final double REOPEN_SCALE = 0.6D;
    public static final double KEY_FINISH_MS = 100.0D;
    public static final TabletEasing KEY_FINISH_EASE = TabletEasing.EASE_OUT_QUAD;

    // ---- colours: the D2 shell owns them, the animation only reads them
    /** Wake / sleep / scan lines (TacticalBoardTheme LIGHT in the A palette). */
    public static final int LIGHT = 0xFFEEF3F0;
    /** Backlight mask colour (its alpha comes from the animation). */
    public static final int BACKLIGHT = 0xFF000000;
    public static final int GLASS = SocialTabletColors.GLASS;
    public static final int WORLD_DIM = SocialTabletColors.WORLD_DIM;
    public static final int LED_OFF = SocialTabletColors.LED_OFF;
    public static final int SHADOW = SocialTabletColors.SHADOW;
    /** Case thickness of the 3D device, in units. */
    public static final int D2_THICK_U = 2;

    // =========================================================================================
    // Phase names (§5.4 status bar): open by [from, to), close by (from, to]
    // =========================================================================================

    /** One named phase of a direction. */
    public record PhaseSeg(String name, double from, double to) {
    }

    /** Phase names of one path; the empty-hand lists are {@code null} where they do not differ. */
    public record Phases(List<PhaseSeg> open, String openEnd, List<PhaseSeg> close,
                         String closeEnd, List<PhaseSeg> openEmpty, List<PhaseSeg> closeEmpty) {
    }

    private static PhaseSeg seg(String name, double from, double to) {
        return new PhaseSeg(name, from, to);
    }

    public static final String PHASE_READY = "就绪";
    public static final String PHASE_IDLE = "空闲";

    /** Keys: A3D, B2D, B2D_MAP, QUICK, QUICK_MAP, OFF. */
    public static final Map<String, Phases> PHASES = Map.of(
            "A3D", new Phases(
                    List.of(seg("收枪", 0, 0.17), seg("收枪·抽板", 0.17, 0.30), seg("抬板", 0.30, 0.61),
                            seg("开机", 0.61, 0.74), seg("贴近", 0.74, 1)),
                    PHASE_READY,
                    List.of(seg("举枪", 0, 0.30), seg("放下", 0.30, 0.47), seg("息屏", 0.47, 0.74),
                            seg("缩回", 0.74, 1)),
                    PHASE_IDLE,
                    List.of(seg("放下手", 0, 0.17), seg("放下手·抽板", 0.17, 0.30), seg("抬板", 0.30, 0.61),
                            seg("开机", 0.61, 0.74), seg("贴近", 0.74, 1)),
                    List.of(seg("抬手", 0, 0.30), seg("放下", 0.30, 0.47), seg("息屏", 0.47, 0.74),
                            seg("缩回", 0.74, 1))),
            "B2D", new Phases(
                    List.of(seg("抬起", 0, 0.55), seg("扫描亮屏", 0.55, 1)), PHASE_READY,
                    List.of(seg("下坠", 0, 0.55)), PHASE_IDLE, null, null),
            "B2D_MAP", new Phases(
                    List.of(seg("抬起", 0, 0.2), seg("亮屏", 0.2, 0.3), seg("扫描", 0.3, 0.55),
                            seg("停顿", 0.55, 0.6), seg("贴近", 0.6, 1)), PHASE_READY,
                    List.of(seg("下坠", 0, 0.55), seg("退远", 0.55, 1)), PHASE_IDLE, null, null),
            "QUICK", new Phases(List.of(seg("滑入", 0, 1)), PHASE_READY,
                    List.of(seg("滑出", 0, 1)), PHASE_IDLE, null, null),
            "QUICK_MAP", new Phases(List.of(seg("贴近", 0, 1)), PHASE_READY,
                    List.of(seg("退远", 0, 1)), PHASE_IDLE, null, null),
            "OFF", new Phases(List.of(), PHASE_READY, List.of(), PHASE_IDLE, null, null));

    private TabletAnimationModel() {
    }

    private static double[][] copy(double[][] pts) {
        double[][] out = new double[pts.length][];
        for (int i = 0; i < pts.length; i++) {
            out[i] = pts[i].clone();
        }
        return out;
    }

    private static double[][] tempo(double[][] v1) {
        double[][] out = new double[v1.length][];
        for (int i = 0; i < v1.length; i++) {
            out[i] = new double[]{v1[i][0] / TEMPO, v1[i][1]};
        }
        return out;
    }
}
