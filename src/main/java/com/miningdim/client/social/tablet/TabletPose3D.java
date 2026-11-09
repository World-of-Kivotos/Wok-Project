package com.miningdim.client.social.tablet;


import com.miningdim.client.social.tablet.TabletAnimationModel.Key;

import java.util.ArrayList;
import java.util.List;

import static com.miningdim.client.social.tablet.TabletAnimationModel.*;
import static com.miningdim.client.social.tablet.TabletEasing.clamp01;

/**
 * Scheme A ("both hands lift the tablet") geometry, DESIGN §3.1–§3.5, a port of the preview's
 * {@code page/pose3d.js}. Pure logic.
 *
 * <p>Conventions (as MC's hand pass): view space, camera at the origin looking down −Z, +Y up,
 * +X right, unit block. Tablet space: origin = centre of the device plane, +Z = the plane normal
 * towards the eye, the front face at z = 0. The plane is the whole terminal picture (lpW × lpH);
 * the D2 device is its D region. A plane rectangle is (s, cy): s = height / screen height, centre
 * (0.5, cy), y measured downwards. The hand pass projects with a fixed 70° vertical field of view;
 * FOV compensation kf is the first operation of the tablet matrix.
 */
public final class TabletPose3D {
    private static final double DEG = Math.PI / 180.0D;
    /** tan 35°. */
    public static final double TAN_HALF = Math.tan(HAND_FOV_DEG / 2.0D * DEG);
    /** m11 of the 70° hand projection. */
    public static final double P11_HAND = 1.0D / TAN_HALF;

    private TabletPose3D() {
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double round(double value) {
        return (double) Math.round(value);
    }

    // =========================================================================================
    // §3.1 geometry
    // =========================================================================================

    /**
     * The device plane: width {@code wf} = 0.75 block, height {@code hf} = wf / aspect (capped at
     * 0.5625, then wf = hf · aspect); {@code d} = the distance at which it fills the 70° view.
     */
    public record Face(double aspect, double wf, double hf, double d) {
        public static Face of(double aspect) {
            double wf = FACE_WIDTH;
            double hf = wf / aspect;
            if (hf > FACE_MAX_HEIGHT) {
                hf = FACE_MAX_HEIGHT;
                wf = hf * aspect;
            }
            return new Face(aspect, wf, hf, hf / (2.0D * TAN_HALF));
        }

        /** Width in modelling units (1/32 block). */
        public double widthU() {
            return wf / U;
        }

        public double heightU() {
            return hf / U;
        }
    }

    /**
     * k_read and how the read position is sampled: k in [0.45, 0.70] with S·f·k a whole number
     * ≥ 2, closest to 0.55 (ties to the larger); none → 0.55 and the smooth downscale.
     */
    public record ReadInfo(double k, boolean smooth) {
        public static ReadInfo of(int sf) {
            Double best = null;
            int start = Math.max(1, K_READ_MIN_LP_PX);
            int end = (int) Math.ceil(sf * K_READ_MAX) + 1;
            for (int n = start; n <= end; n++) {
                double k = (double) n / sf;
                if (k < K_READ_MIN - 1e-9 || k > K_READ_MAX + 1e-9) {
                    continue;
                }
                if (best == null) {
                    best = k;
                    continue;
                }
                double dk = Math.abs(k - K_READ_TARGET);
                double db = Math.abs(best - K_READ_TARGET);
                if (dk < db - 1e-9 || (Math.abs(dk - db) <= 1e-9 && k > best)) {
                    best = k;
                }
            }
            return best == null ? new ReadInfo(K_READ_FALLBACK, true) : new ReadInfo(best, false);
        }
    }

    /** A plane rectangle (s, cy) with the zoom progress and the sink that went into cy. */
    public record Rect(double s, double cy, double zoom, double sway) {
    }

    /** The end rectangle of an opening. */
    public record End(double s, double cy) {
    }

    /**
     * A rectangle in physical pixels; {@code scale} is the plane's s for a screen rectangle and
     * NaN for a sub-rectangle.
     */
    public record RectPx(double x, double y, double w, double h, double scale) {
        public boolean contains(double px, double py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }

    /** A tablet pose in view space (blocks, degrees); rotations apply yaw → roll → pitch. */
    public record Pose(String id, double x, double y, double z, double yaw, double pitch, double roll) {
        public Pose withY(double value) {
            return new Pose(id, x, value, z, yaw, pitch, roll);
        }
    }

    /** Zoom overshoot of a tier: o (fraction of the end s), peak position, the edge limit. */
    public record ZoomParams(double o, double peakAt, double limit) {
    }

    /**
     * Everything that stays the same for one screen in one window.
     *
     * @param term        the terminal's units (the plane always follows the terminal)
     * @param units       the animated screen's units (the map has f = 1)
     * @param geom        the terminal's D2 geometry
     * @param map         the screen takes the map path (zooms until P fills the screen)
     * @param termSmooth  the terminal's read position is sampled by smooth downscale
     * @param readSmooth  this screen's read position is sampled by smooth downscale
     * @param p11         m11 of the hand projection (70° unless something changed the FOV)
     * @param kf          FOV compensation {@code 1 / (p11 · tan 35°)}
     * @param invisible   the player is invisible: no hands
     */
    public record Context(TabletUnits term, TabletUnits units, TabletD2Geometry geom,
                          TabletScreenKind kind, boolean map, Face face, double kRead,
                          boolean termSmooth, boolean readSmooth, End end, ZoomParams zoom,
                          double p11, double kf, boolean invisible) {
        public static Context of(TabletUnits term, TabletUnits units, TabletScreenKind kind,
                                 double p11, boolean invisible) {
            TabletScreenKind screen = kind == null ? TabletScreenKind.SQUAD : kind;
            TabletD2Geometry g = TabletD2Geometry.of(term.lpW(), term.lpH());
            boolean map = !screen.isTerminal();
            Face face = Face.of(term.aspect());
            ReadInfo ri = ReadInfo.of(term.sf());
            boolean readSmooth = map ? mapSmooth(units, ri.k(), g) : ri.smooth();
            End end = endRect(screen, g);
            ZoomParams zoom = zoomParams(map, g, ri.k(), end);
            double projection = p11 > 0.0D && Double.isFinite(p11) ? p11 : P11_HAND;
            return new Context(term, units, g, screen, map, face, ri.k(), ri.smooth(), readSmooth,
                    end, zoom, projection, TabletPose3D.kf(projection), invisible);
        }
    }

    /**
     * Per-frame input from the motion.
     *
     * @param open         opening direction
     * @param curve        the round trip's pose curve
     * @param capture      this close replays a captured frame (C1)
     * @param captureFromP the p the C1 close started from (NaN or > 1 counts as 1)
     * @param hand         main hand when the tablet came out
     * @param carry        D4 sink carried into a wake-band close (screen fraction)
     */
    public record FrameQuery(boolean open, TabletMotion.Curve curve, boolean capture,
                             double captureFromP, TabletHand hand, double carry) {
        public static FrameQuery opening(TabletHand hand) {
            return new FrameQuery(true, TabletMotion.Curve.OPEN, true, 1.0D, hand, 0.0D);
        }
    }

    /** The map draws into P: smooth unless S·k·P.h/H is a whole number ≥ 2 (none of the tiers). */
    public static boolean mapSmooth(TabletUnits mapUnits, double k, TabletD2Geometry g) {
        double r = mapUnits.sf() * k * g.p().height() / g.h();
        return !(r >= 2.0D - 1e-9 && Math.abs(r - Math.round(r)) < 1e-9);
    }

    /** READ: s = k, bottom edge at 92 % of the height. */
    public static End readRect(double k) {
        return new End(k, READ_BOTTOM - k / 2.0D);
    }

    /** Terminal: s 1, cy 0.5 (the device rests in D); map: P fills the screen, P's centre centred. */
    public static End endRect(TabletScreenKind kind, TabletD2Geometry g) {
        if (kind == null || kind.isTerminal()) {
            return new End(FILL_S, FILL_CY);
        }
        UiRect p = g.p();
        double ph = (double) p.height() / g.h();
        double pcy = (p.top() + p.bottom()) / 2.0D / g.h();
        double s = 1.0D / ph;
        return new End(s, 0.5D - s * (pcy - 0.5D));
    }

    /** Rectangle → pose (no rotation): z = −d/s, y = −(cy − 0.5)·Hf/s. */
    public static Pose poseFromRect(double s, double cy, Face fc) {
        return new Pose("READ", 0.0D, -(cy - 0.5D) * fc.hf() / s, -fc.d() / s, 0.0D, 0.0D, 0.0D);
    }

    /** Snapped physical rectangle → pose (no rotation). */
    public static Pose poseFromRectPx(RectPx px, TabletUnits u, Face fc) {
        double s = px.scale();
        double cx = (px.x() + px.w() / 2.0D) / u.pxW();
        double cy = (px.y() + px.h() / 2.0D) / u.pxH();
        return new Pose("READ", (cx - 0.5D) * fc.wf() / s, -(cy - 0.5D) * fc.hf() / s,
                -fc.d() / s, 0.0D, 0.0D, 0.0D);
    }

    /** Pose (no rotation) → {s, cx, cy}. */
    public static double[] rectFromPose(Pose pose, Face fc) {
        double s = fc.d() / -pose.z();
        return new double[]{s, 0.5D + pose.x() * s / fc.wf(), 0.5D - pose.y() * s / fc.hf()};
    }

    /** FOV compensation {@code 1 / (P11 · tan 35°)}. */
    public static double kf(double p11) {
        double projection = p11 > 0.0D && Double.isFinite(p11) ? p11 : P11_HAND;
        return 1.0D / (projection * TAN_HALF);
    }

    /** View-space point → screen fraction {u, v} (v downwards). */
    public static double[] projectFrac(double[] v, double aspect, double p11) {
        double projection = p11 > 0.0D ? p11 : P11_HAND;
        double w = -v[2];
        return new double[]{0.5D + 0.5D * (projection / aspect) * v[0] / w,
                0.5D - 0.5D * projection * v[1] / w};
    }

    // =========================================================================================
    // zoom, breath
    // =========================================================================================

    /**
     * Zoom progress x in [0, 1] → z: minimum jerk to 1 + ovz before {@code peakAt}, then eased
     * back, exactly 1 at x = 1.
     */
    public static double zoomZ(double x, double ovz, double peakAt) {
        if (x <= 0.0D) {
            return 0.0D;
        }
        if (x >= 1.0D) {
            return 1.0D;
        }
        if (!(ZOOM_OVERSHOOT > 0.0D)) {
            return EASE_ZOOM.apply(x);
        }
        if (x < peakAt) {
            return (1.0D + ovz) * ZOOM_EASE.apply(x / peakAt);
        }
        return 1.0D + ovz * (1.0D - ZOOM_SETTLE_EASE.apply((x - peakAt) / (1.0D - peakAt)));
    }

    /**
     * The tier's overshoot: at the peak the terminal's E region (bumpers included) must stay on
     * screen; o = min(1.5 %, 0.9 × the limit). The map's end fills the screen anyway.
     */
    public static ZoomParams zoomParams(boolean map, TabletD2Geometry g, double kRead, End end) {
        if (!(ZOOM_OVERSHOOT > 0.0D)) {
            return new ZoomParams(0.0D, 1.0D, Double.POSITIVE_INFINITY);
        }
        double lim = Double.POSITIVE_INFINITY;
        if (!map) {
            End r = readRect(kRead);
            UiRect e = g.e();
            double eL = (double) e.left() / g.w();
            double eR = (double) (g.w() - e.right()) / g.w();
            double eT = (double) e.top() / g.h();
            double eB = (double) (g.h() - e.bottom()) / g.h();
            double cc = (end.cy() - r.cy()) / Math.max(1e-9, end.s() - r.s());
            lim = Math.min(Math.min(bound(eL, end.s() / 2.0D - eL), bound(eR, end.s() / 2.0D - eR)),
                    Math.min(bound(eT, end.s() / 2.0D - cc - eT), bound(eB, cc + end.s() / 2.0D - eB)));
        }
        double o = Math.min(ZOOM_OVERSHOOT, ZOOM_EDGE_SAFETY * lim);
        return new ZoomParams(o, ZOOM_PEAK_AT, lim);
    }

    private static double bound(double num, double den) {
        return den > 1e-12 ? num / den : Double.POSITIVE_INFINITY;
    }

    /**
     * Held at READ (0.61 → 0.74) the plane sinks and comes back (screen fraction, positive =
     * down): sin² to the peak at 0.655, cos² back; exactly 0 at both ends.
     */
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

    /**
     * The plane rectangle for p ≥ 0.61: READ through the wake band (plus the sink on the open
     * curve), zoom to the end. The close curve zooms by minimum jerk without overshoot or sink.
     */
    public static Rect rectAt(double p, double k, End end, boolean close, ZoomParams zp) {
        End r = readRect(k);
        End f = end == null ? new End(FILL_S, FILL_CY) : end;
        if (p >= 1.0D) {
            return new Rect(f.s(), f.cy(), 1.0D, 0.0D);
        }
        double x = (p - SEG_ZOOM_FROM) / (SEG_ZOOM_TO - SEG_ZOOM_FROM);
        double z;
        if (p <= SEG_ZOOM_FROM) {
            z = 0.0D;
        } else if (close) {
            z = CLOSE_EASE.apply(x);
        } else {
            z = zoomZ(x, zp.o() * f.s() / Math.max(1e-9, f.s() - r.s()), zp.peakAt());
        }
        double sway = close ? 0.0D : breathAt(p);
        return new Rect(lerp(r.s(), f.s(), z), lerp(r.cy(), f.cy(), z) + sway, z, sway);
    }

    // =========================================================================================
    // §3.2 raise key frames
    // =========================================================================================

    /** The key frames as absolute poses around READ {@code read}. */
    public static List<Pose> keyPoses(Face fc, double k, Pose read) {
        Pose r = read;
        if (r == null) {
            End rr = readRect(k);
            r = poseFromRect(rr.s(), rr.cy(), fc);
        }
        double zr = Math.abs(r.z());
        List<Pose> out = new ArrayList<>(KEYS.size());
        for (Key key : KEYS) {
            out.add(new Pose(key.id(), r.x() + key.offX() * fc.hf(), r.y() + key.offY() * fc.hf(),
                    r.z() + key.offZ() * zr, key.yaw(), key.pitch(), key.roll()));
        }
        return out;
    }

    private static double comp(Pose q, int c) {
        return switch (c) {
            case 0 -> q.x();
            case 1 -> q.y();
            case 2 -> q.z();
            case 3 -> q.yaw();
            case 4 -> q.pitch();
            default -> q.roll();
        };
    }

    /** Fritsch–Carlson slopes; start slope = first segment × {@code startMul}, end slope 0. */
    public static double[] pchipSlopes(double[] ts, double[] vs, double startMul) {
        int n = ts.length;
        double[] h = new double[n - 1];
        double[] d = new double[n - 1];
        double[] m = new double[n];
        for (int i = 0; i < n - 1; i++) {
            h[i] = ts[i + 1] - ts[i];
            d[i] = (vs[i + 1] - vs[i]) / h[i];
        }
        m[0] = d[0] * Math.min(3.0D, Math.max(0.0D, startMul));
        for (int i = 1; i < n - 1; i++) {
            if (d[i - 1] == 0.0D || d[i] == 0.0D || (d[i - 1] > 0.0D) != (d[i] > 0.0D)) {
                m[i] = 0.0D;
                continue;
            }
            double w1 = 2.0D * h[i] + h[i - 1];
            double w2 = h[i] + 2.0D * h[i - 1];
            m[i] = (w1 + w2) / (w1 / d[i - 1] + w2 / d[i]);
        }
        m[n - 1] = 0.0D;
        return m;
    }

    private static double hermite(double t, double v0, double v1, double m0, double m1, double h) {
        double t2 = t * t;
        double t3 = t2 * t;
        return (2 * t3 - 3 * t2 + 1) * v0 + (t3 - 2 * t2 + t) * h * m0 + (-2 * t3 + 3 * t2) * v1
                + (t3 - t2) * h * m1;
    }

    /** Slopes of the open curve for each pose component, at every key frame. */
    private static double[][] openSlopes(List<Pose> keys) {
        int n = keys.size();
        double[] ts = new double[n];
        for (int i = 0; i < n; i++) {
            ts[i] = KEYS.get(i).p();
        }
        double[][] slopes = new double[6][];
        for (int c = 0; c < 6; c++) {
            double[] vs = new double[n];
            for (int i = 0; i < n; i++) {
                vs[i] = comp(keys.get(i), c);
            }
            slopes[c] = pchipSlopes(ts, vs, RAISE_START_SLOPE);
        }
        return slopes;
    }

    private static Pose hermitePose(String id, double p, List<Pose> keys, double[][] slopes,
                                    int ia, int ib) {
        Pose a = keys.get(ia);
        Pose b = keys.get(ib);
        double pa = KEYS.get(ia).p();
        double pb = KEYS.get(ib).p();
        double h = pb - pa;
        double t = (p - pa) / h;
        double[] v = new double[6];
        for (int c = 0; c < 6; c++) {
            v[c] = hermite(t, comp(a, c), comp(b, c), slopes[c][ia], slopes[c][ib], h);
        }
        return new Pose(id, v[0], v[1], v[2], v[3], v[4], v[5]);
    }

    /** The open curve (pchip through K0, K1, K2, READ). */
    public static Pose raisePoseOpen(double p, Face fc, double k, Pose read) {
        List<Pose> keys = keyPoses(fc, k, read);
        Pose first = keys.get(0);
        Pose last = keys.get(keys.size() - 1);
        if (p <= KEYS.get(0).p()) {
            return first;
        }
        if (p >= KEYS.get(keys.size() - 1).p()) {
            return new Pose("READ", last.x(), last.y(), last.z(), 0.0D, 0.0D, 0.0D);
        }
        int i = 0;
        while (i < keys.size() - 2 && p >= KEYS.get(i + 1).p()) {
            i++;
        }
        return hermitePose(keys.get(i).id() + "→" + keys.get(i + 1).id(), p, keys,
                openSlopes(keys), i, i + 1);
    }

    /**
     * The close curve's raise segment: skips K2, one Hermite K1 → READ with the open curve's
     * slopes at both ends; below K1 both curves are identical.
     */
    public static Pose raisePoseClose(double p, Face fc, double k, Pose read) {
        List<Pose> keys = keyPoses(fc, k, read);
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            if (!CLOSE_SKIP_KEYS.contains(keys.get(i).id())) {
                idx.add(i);
            }
        }
        if (CLOSE_SKIP_KEYS.isEmpty() || idx.size() == keys.size()
                || p <= KEYS.get(idx.get(0)).p() || p >= KEYS.get(idx.get(idx.size() - 1)).p()) {
            return raisePoseOpen(p, fc, k, read);
        }
        int j = 0;
        while (j < idx.size() - 2 && p >= KEYS.get(idx.get(j + 1)).p()) {
            j++;
        }
        int ia = idx.get(j);
        int ib = idx.get(j + 1);
        if (ib == ia + 1) {
            return raisePoseOpen(p, fc, k, read);
        }
        return hermitePose(keys.get(ia).id() + "→" + keys.get(ib).id() + "（收起）", p, keys,
                openSlopes(keys), ia, ib);
    }

    /** The raise segment (p in [0.17, 0.61]); the close curve never rises above READ. */
    public static Pose raisePose(double p, Face fc, double k, Pose read, boolean close) {
        Pose out = close ? raisePoseClose(p, fc, k, read) : raisePoseOpen(p, fc, k, read);
        if (close && CLOSE_NO_OVERSHOOT) {
            List<Pose> keys = keyPoses(fc, k, read);
            double ry = keys.get(keys.size() - 1).y();
            if (out.y() > ry) {
                out = out.withY(ry);
            }
        }
        return out;
    }

    /**
     * The tablet pose at any p: raise interpolation below 0.61, otherwise derived from the snapped
     * plane rectangle (no rotation). The key frames are relative to the snapped READ. {@code carry}
     * (D4) moves the plane down by that screen fraction (p &lt; 1 only).
     */
    public static Pose poseAt(double p, Face fc, double k, TabletUnits u, boolean close, End end,
                              ZoomParams zp, double carry) {
        End rr = readRect(k);
        Pose read = poseFromRectPx(rectPx(k, rr.cy(), u), u, fc);
        if (p < SEG_RAISE_TO) {
            Pose pose = raisePose(p, fc, k, read, close);
            if (carry != 0.0D) {
                pose = pose.withY(pose.y() - carry * fc.hf() * Math.abs(pose.z()) / fc.d());
            }
            return pose;
        }
        Rect r = rectAt(p, k, end, close, zp);
        double cy = p < 1.0D ? r.cy() + carry : r.cy();
        Pose pose = poseFromRectPx(rectPx(r.s(), cy, u), u, fc);
        return new Pose(p >= 1.0D ? "FILL" : "READ", pose.x(), pose.y(), pose.z(), 0.0D, 0.0D, 0.0D);
    }

    // =========================================================================================
    // PoseStack operation lists
    // =========================================================================================

    /**
     * The tablet matrix: from identity (no view bobbing, TaCZ camera or stamina sway), kf first,
     * then translate → rotY(yaw) → rotZ(roll) → rotX(pitch).
     */
    public static List<TabletOp> tabletOps(Pose pose, double kfv) {
        return List.of(TabletOp.identity(), TabletOp.scale(kfv, kfv, 1.0D),
                TabletOp.translate(pose.x(), pose.y(), pose.z()), TabletOp.rotY(pose.yaw()),
                TabletOp.rotZ(pose.roll()), TabletOp.rotX(pose.pitch()));
    }

    /** Press-down progress of the held item (or arm) at p: ease(p / 0.30). */
    public static double gunProgress(double p, TabletEasing ease) {
        return (ease == null ? EASE_GUN : ease).apply(clamp01(p / GUN_HIDE_AT));
    }

    /**
     * The gun's anticipation / settle envelope, 0 at p 0 and 0.20 with zero speed: sin² up to
     * 0.035, then a slow cos² down. Both directions use the same function of p.
     */
    public static double gunLift(double p) {
        if (p <= 0.0D || p >= GUN_LIFT_TO_P) {
            return 0.0D;
        }
        if (p < GUN_LIFT_UP_P) {
            double a = Math.sin(Math.PI / 2.0D * p / GUN_LIFT_UP_P);
            return a * a;
        }
        double b = Math.cos(Math.PI / 2.0D * (p - GUN_LIFT_UP_P) / (GUN_LIFT_TO_P - GUN_LIFT_UP_P));
        return b * b;
    }

    /**
     * The press-down pushed before the held item renders: translate(offset·e + lift·L) →
     * rotX(pitch·e + lift pitch·L) → rotZ(roll·e).
     */
    public static List<TabletOp> gunOps(double p, TabletEasing ease) {
        double e = gunProgress(p, ease);
        double l = gunLift(p);
        return List.of(TabletOp.translate(GUN_OFFSET_X * e, GUN_OFFSET_Y * e + GUN_LIFT_Y * l,
                        GUN_OFFSET_Z * e),
                TabletOp.rotX(GUN_PITCH * e + GUN_LIFT_PITCH * l), TabletOp.rotZ(GUN_ROLL * e));
    }

    private static double byAspect(Face fc, double wide, double tall) {
        double t = clamp01((fc.hf() / fc.wf() - 9.0D / 16.0D) / (3.0D / 4.0D - 9.0D / 16.0D));
        return lerp(wide, tall, t);
    }

    public static double handsDx(Face fc) {
        return byAspect(fc, HANDS_DX_WIDE, HANDS_DX_TALL);
    }

    public static double handsDy(Face fc) {
        return byAspect(fc, HANDS_DY_WIDE, HANDS_DY_TALL);
    }

    public static double handsDz(Face fc) {
        return byAspect(fc, HANDS_DZ_WIDE, HANDS_DZ_TALL);
    }

    /** Zoom: the hands let go, 0 → 1 between p 0.74 and 0.97 (easeInQuad). */
    public static double handsExit(double p) {
        return HANDS_EXIT_EASE.apply(clamp01((p - HANDS_EXIT_FROM) / (HANDS_EXIT_TO - HANDS_EXIT_FROM)));
    }

    /** The hands follow the device's lower corners: x inward by mx/w·Wf, y up by mb/h·Hf. */
    public static double[] deviceInset(Face fc, TabletD2Geometry g) {
        if (g == null) {
            return new double[]{0.0D, 0.0D};
        }
        return new double[]{-((double) g.k().marginX() / g.w()) * fc.wf(),
                ((double) g.k().marginBottom() / g.h()) * fc.hf()};
    }

    /**
     * One hand's full chain from tablet space to its arm model space: translate → rotY(90) →
     * rotY(mirrored 92) → rotX(45) → rotZ(s·−41) → translate(s·0.12, −1.1, 0.45) → ModelPart
     * (pivot / 16, zRot s·0.1 rad, scale 1/16).
     */
    public static List<TabletOp> handLocalOps(boolean right, Face fc, double p, TabletD2Geometry g) {
        double s = right ? 1.0D : -1.0D;
        double e = handsExit(p);
        double[] di = deviceInset(fc, g);
        double pivotX = right ? HANDS_PIVOT_RIGHT_X : HANDS_PIVOT_LEFT_X;
        double chainYaw = HANDS_MIRROR_YAW ? 90.0D + s * (HANDS_CHAIN_ROT_Y - 90.0D) : HANDS_CHAIN_ROT_Y;
        return List.of(
                TabletOp.translate(s * (handsDx(fc) + di[0] + HANDS_EXIT_X * e),
                        handsDy(fc) + di[1] + HANDS_EXIT_Y * e, handsDz(fc) + HANDS_EXIT_Z * e),
                TabletOp.rotY(HANDS_OUTER_Y),
                TabletOp.rotY(chainYaw),
                TabletOp.rotX(HANDS_CHAIN_ROT_X),
                TabletOp.rotZ(s * HANDS_CHAIN_ROT_Z),
                TabletOp.translate(s * HANDS_CHAIN_TX, HANDS_CHAIN_TY, HANDS_CHAIN_TZ),
                TabletOp.translate(pivotX / 16.0D, HANDS_PIVOT_Y / 16.0D, HANDS_PIVOT_Z / 16.0D),
                TabletOp.rotZ(s * HANDS_BOB_Z_ROT / DEG),
                TabletOp.scale(1.0D / 16.0D, 1.0D / 16.0D, 1.0D / 16.0D));
    }

    /**
     * The first six ops of {@link #handLocalOps}: everything before the ModelPart, which
     * {@code PlayerRenderer.renderRightHand / renderLeftHand} applies itself.
     */
    public static List<TabletOp> handPreOps(boolean right, Face fc, double p, TabletD2Geometry g) {
        return handLocalOps(right, fc, p, g).subList(0, 6);
    }

    /** The vanilla empty right arm going down: gunOps(easeInQuad) + renderPlayerArm's chain. */
    public static List<TabletOp> emptyArmOps(double p) {
        List<TabletOp> out = new ArrayList<>(gunOps(p, EASE_EMPTY_ARM));
        out.add(TabletOp.translate(EMPTY_ARM_TX, EMPTY_ARM_TY, EMPTY_ARM_TZ));
        out.add(TabletOp.rotY(EMPTY_ARM_ROT_Y));
        out.add(TabletOp.translate(EMPTY_ARM_T2X, EMPTY_ARM_T2Y, EMPTY_ARM_T2Z));
        out.add(TabletOp.rotZ(EMPTY_ARM_ROT_Z));
        out.add(TabletOp.rotX(EMPTY_ARM_ROT_X));
        out.add(TabletOp.rotY(EMPTY_ARM_ROT_Y2));
        out.add(TabletOp.translate(EMPTY_ARM_T3X, EMPTY_ARM_T3Y, EMPTY_ARM_T3Z));
        out.add(TabletOp.translate(HANDS_PIVOT_RIGHT_X / 16.0D, HANDS_PIVOT_Y / 16.0D,
                HANDS_PIVOT_Z / 16.0D));
        out.add(TabletOp.rotZ(HANDS_BOB_Z_ROT / DEG));
        out.add(TabletOp.scale(1.0D / 16.0D, 1.0D / 16.0D, 1.0D / 16.0D));
        return List.copyOf(out);
    }

    // =========================================================================================
    // rectangles in physical pixels
    // =========================================================================================

    /** Plane → physical rectangle: origin snapped to whole pixels, size s × the window. */
    public static RectPx rectPx(double s, double cy, TabletUnits u) {
        if (s == 1.0D && cy == 0.5D) {
            return new RectPx(0, 0, u.pxW(), u.pxH(), 1.0D);
        }
        return new RectPx(round((0.5D - s / 2.0D) * u.pxW()), round((cy - s / 2.0D) * u.pxH()),
                s * u.pxW(), s * u.pxH(), s);
    }

    /** An lp rectangle of the plane on screen (unsnapped). */
    public static RectPx subRectPx(RectPx plane, TabletD2Geometry g, UiRect r) {
        double kx = plane.w() / g.w();
        double ky = plane.h() / g.h();
        return new RectPx(plane.x() + r.left() * kx, plane.y() + r.top() * ky, r.width() * kx,
                r.height() * ky, Double.NaN);
    }

    /** As {@link #subRectPx}, each edge rounded to whole pixels (wake bands, sleep line). */
    public static RectPx subRectSnap(RectPx plane, TabletD2Geometry g, UiRect r) {
        RectPx f = subRectPx(plane, g, r);
        double x0 = round(f.x());
        double y0 = round(f.y());
        double x1 = round(f.x() + f.w());
        double y1 = round(f.y() + f.h());
        return new RectPx(x0, y0, x1 - x0, y1 - y0, Double.NaN);
    }

    /**
     * The map rectangle: the map draws into P, as tall as P, the window's aspect, centred on P;
     * identity (full screen) at p = 1.
     */
    public static RectPx mapRectPx(RectPx plane, TabletD2Geometry g, TabletUnits u, boolean identity) {
        if (identity) {
            return new RectPx(0, 0, u.pxW(), u.pxH(), 1.0D);
        }
        UiRect p = g.p();
        double sc = plane.scale() * p.height() / g.h();
        double w = sc * u.pxW();
        double h = sc * u.pxH();
        double cx = plane.x() + plane.w() * (p.left() + p.right()) / 2.0D / g.w();
        double cy = plane.y() + plane.h() * (p.top() + p.bottom()) / 2.0D / g.h();
        return new RectPx(round(cx - w / 2.0D), round(cy - h / 2.0D), w, h, sc);
    }

    /** An lp point of the plane in tablet space: ((lx/w − 0.5)·Wf, (0.5 − ly/h)·Hf, 0). */
    public static double[] planePoint(Face fc, TabletD2Geometry g, double lx, double ly) {
        return new double[]{(lx / g.w() - 0.5D) * fc.wf(), (0.5D - ly / g.h()) * fc.hf(), 0.0D};
    }

    /** The four D corners projected to physical pixels, or {@code null} behind the near plane. */
    public static double[][] deviceQuadPx(double[] tabletMatrix, Context c) {
        TabletD2Geometry g = c.geom();
        UiRect d = g.d();
        double[][] corners = {{d.left(), d.top()}, {d.right(), d.top()}, {d.right(), d.bottom()},
                {d.left(), d.bottom()}};
        double[][] out = new double[4][];
        for (int i = 0; i < 4; i++) {
            double[] q = planePoint(c.face(), g, corners[i][0], corners[i][1]);
            double[] v = TabletMat4.transformPoint(tabletMatrix, q[0], q[1], q[2]);
            if (-v[2] < 0.05D) {
                return null;
            }
            double[] f = projectFrac(v, c.units().aspect(), c.p11());
            out[i] = new double[]{f[0] * c.units().pxW(), f[1] * c.units().pxH()};
        }
        return out;
    }

    // =========================================================================================
    // frame
    // =========================================================================================

    /** Every drawing parameter of frame p (preview {@code frameState}). */
    public static TabletFrame frame(double progress, Context c, FrameQuery q) {
        Face fc = c.face();
        double k = c.kRead();
        TabletD2Geometry g = c.geom();
        double p = Math.round(clamp01(progress) * 1e9) / 1e9;
        boolean open = q.open();
        boolean closeCurve = q.curve() == TabletMotion.Curve.CLOSE;
        boolean capture = q.capture();
        double carry = p < 1.0D && Double.isFinite(q.carry()) ? q.carry() : 0.0D;

        Pose pose = poseAt(p, fc, k, c.term(), closeCurve, c.end(), c.zoom(), carry);
        List<TabletOp> tabletOps = tabletOps(pose, c.kf());
        double[] tabletMatrix = TabletMat4.ops(tabletOps, null);
        boolean inWindow = p > KEYS.get(0).p() && p < 1.0D && (open || capture || p <= SEG_RAISE_TO);
        boolean hasRect = p >= SEG_RAISE_TO;
        boolean covered = !c.map() && (open ? hasRect : capture && p > SEG_RAISE_TO);
        boolean tabletDraw = inWindow && !covered;

        Rect r = null;
        if (hasRect) {
            Rect raw = rectAt(p, k, c.end(), closeCurve, c.zoom());
            r = carry == 0.0D ? raw : new Rect(raw.s(), raw.cy() + carry, raw.zoom(), raw.sway());
        }
        TabletFrame.PlaneRect rect = r == null ? null : new TabletFrame.PlaneRect(r.s(), r.cy(), r.zoom());
        RectPx planePx = r == null ? null : rectPx(r.s(), r.cy(), c.term());
        boolean identity = p >= 1.0D;
        RectPx uiPx = r == null ? null : c.map() ? mapRectPx(planePx, g, c.units(), identity) : planePx;
        RectPx devicePx = r == null ? null : subRectPx(planePx, g, g.d());
        RectPx glassPx = r == null ? null : subRectSnap(planePx, g, g.s());

        // hands
        List<TabletOp> rightOps = handLocalOps(true, fc, p, g);
        List<TabletOp> leftOps = handLocalOps(false, fc, p, g);
        TabletFrame.Hands hands = new TabletFrame.Hands(inWindow && !c.invisible(), handsDx(fc),
                handsDy(fc), handsExit(p), rightOps, leftOps,
                TabletMat4.ops(rightOps.subList(0, 6), tabletMatrix),
                TabletMat4.ops(leftOps.subList(0, 6), tabletMatrix),
                TabletMat4.ops(rightOps, tabletMatrix), TabletMat4.ops(leftOps, tabletMatrix));

        // the held item (gun or item) / the vanilla empty arm, drawn while p < 0.30
        List<TabletOp> gunOps = gunOps(p, EASE_GUN);
        TabletFrame.Held gun = new TabletFrame.Held(q.hand() != TabletHand.EMPTY && p < GUN_HIDE_AT,
                gunProgress(p, EASE_GUN), gunOps, TabletMat4.ops(gunOps, null));
        List<TabletOp> emptyOps = emptyArmOps(p);
        TabletFrame.Held emptyArm = new TabletFrame.Held(
                q.hand() == TabletHand.EMPTY && p < GUN_HIDE_AT && !c.invisible(),
                gunProgress(p, EASE_EMPTY_ARM), emptyOps, TabletMat4.ops(emptyOps, null));

        // effects (only on the glass opening S)
        boolean fxOn = open || capture;
        boolean wakeOn = fxOn && p >= WAKE_FROM && p < WAKE_MASK_TO;
        double spread = EASE_WAKE_SPREAD.apply((p - WAKE_FROM) / (WAKE_SPREAD_TO - WAKE_FROM));
        double maskA = EASE_MASK.apply((p - WAKE_FROM) / (WAKE_MASK_TO - WAKE_FROM));
        boolean captureBackdrop = !open && capture && BACKDROP_CAPTURE_CLOSE && p > SEG_RAISE_TO;
        double backdropA = !c.map() && ((open && hasRect) || captureBackdrop)
                ? BACKDROP_EASE.apply((p - BACKDROP_FROM) / (BACKDROP_TO - BACKDROP_FROM)) : 0.0D;
        double shadowA = fxOn && WAKE_SHADOW_FADE && p >= WAKE_FROM && p < WAKE_SPREAD_TO ? spread : 1.0D;
        boolean sleepOn = !open && p > SLEEP_TO && p <= SLEEP_FROM + 1e-9;
        int guiScale = c.units().guiScale();
        TabletFrame.Effects fx = new TabletFrame.Effects(
                new TabletFrame.Wake(wakeOn && spread < 1.0D, spread, 1.0D - spread, WAKE_EDGE_ALPHA,
                        WAKE_EDGE_PX),
                new TabletFrame.Mask(wakeOn && maskA < 1.0D, WAKE_MASK_ALPHA * (1.0D - maskA)),
                new TabletFrame.Backdrop(backdropA > 0.0D, backdropA),
                shadowA,
                new TabletFrame.Sleep(sleepOn && tabletDraw,
                        clamp01((p - SLEEP_TO) / (SLEEP_FROM - SLEEP_TO)),
                        (double) SLEEP_LINE_PX * guiScale,
                        fc.hf() / (k * c.term().pxH()) * SLEEP_LINE_PX * guiScale, SLEEP_ALPHA));

        boolean uiDraw = open && hasRect;
        boolean smooth = c.readSmooth() && p < 1.0D;
        boolean captureDraw = !open && capture && p > SEG_RAISE_TO && p < 1.0D + 1e-9;
        TabletFrame.Capture cap;
        if (captureDraw) {
            double fp = !(q.captureFromP() <= 1.0D) ? 1.0D : q.captureFromP();
            boolean fromId = fp >= 1.0D - 1e-9;
            Rect rf = rectAt(Math.min(1.0D, fp), k, c.end(), closeCurve, c.zoom());
            RectPx pf = rectPx(rf.s(), rf.cy(), c.term());
            cap = new TabletFrame.Capture(true, smooth, Math.min(1.0D, fp),
                    c.map() ? mapRectPx(pf, g, c.units(), fromId) : pf, pf, c.readSmooth() && !fromId,
                    uiPx);
        } else {
            cap = new TabletFrame.Capture(false, false, Double.NaN, null, null, false, null);
        }
        TabletFrame.Hud hud = new TabletFrame.Hud(open || p > HUD_RESTORE_ALL_AT_CLOSE_P,
                open || p > HUD_RESTORE_CROSSHAIR_AT_CLOSE_P);
        String phase = TabletMotion.phaseName(TabletPath.A3D, open ? 1 : -1, p, q.hand(), c.kind());
        return new TabletFrame(p, open, q.curve(), phase, c, rect, planePx, uiPx, devicePx, glassPx,
                new TabletFrame.Tablet(tabletDraw, inWindow, covered, pose, tabletOps, tabletMatrix),
                hands, gun, emptyArm, fx,
                new TabletFrame.Ui(uiDraw, identity, uiDraw && smooth, uiDraw ? uiPx : null),
                cap, hud, deviceQuadPx(tabletMatrix, c));
    }

    /**
     * Whether a physical pixel shows the screen on this (opening) frame: nothing before 0.61;
     * outside S always; during the wake spread not on the GLASS bands top and bottom.
     */
    public static boolean contentVisible(TabletFrame f, double px, double py) {
        if (!f.ui().draw()) {
            return false;
        }
        RectPx s = f.glassPx();
        TabletFrame.Wake w = f.fx().wake();
        if (px < s.x() || px >= s.x() + s.w() || py < s.y() || py >= s.y() + s.h() || !w.on()) {
            return true;
        }
        double band = round(w.bandFrac() * s.h() / 2.0D);
        return py >= s.y() + band && py < s.y() + s.h() - band;
    }
}
