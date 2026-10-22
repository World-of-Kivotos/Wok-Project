package com.miningdim.client.social.tablet;

import com.miningdim.client.social.tablet.TabletPose3D.Pose;
import com.miningdim.client.social.tablet.TabletPose3D.RectPx;

import java.util.List;

/**
 * Everything one frame of scheme A draws (preview {@code pose3d.frameState}; the fields match
 * {@code vectors.json frames[].frames[]}). Rectangles are physical pixels of the window.
 *
 * @param p         the progress (rounded to 1e-9)
 * @param open      opening direction (false: closing)
 * @param curve     which pose curve this round trip uses
 * @param phase     status-bar phase name
 * @param ctx       the context it was built from
 * @param rect      plane rectangle (s, cy, zoom) for p ≥ 0.61, else {@code null}
 * @param planePx   the plane snapped to physical pixels (the whole terminal picture)
 * @param rectPx    the rectangle the screen draws into: terminal = the plane, map = the map
 *                  rectangle inside P
 * @param devicePx  the D region of the plane (unsnapped)
 * @param glassPx   the glass opening S of the plane, edges snapped
 * @param quadPx    D corners projected (top-left, top-right, bottom-right, bottom-left), physical
 *                  pixels; {@code null} when a corner is behind the near plane
 */
public record TabletFrame(double p, boolean open, TabletMotion.Curve curve, String phase,
                          TabletPose3D.Context ctx, PlaneRect rect, RectPx planePx, RectPx rectPx,
                          RectPx devicePx, RectPx glassPx, Tablet tablet, Hands hands, Held gun,
                          Held emptyArm, Effects fx, Ui ui, Capture capture, Hud hud,
                          double[][] quadPx) {

    /** The plane rectangle: s = height / screen height, centre (0.5, cy), y measured downwards. */
    public record PlaneRect(double s, double cy, double zoom) {
        public double x() {
            return 0.5D - s / 2.0D;
        }

        public double y() {
            return cy - s / 2.0D;
        }
    }

    /**
     * The 3D tablet.
     *
     * @param draw     draw the 3D device this frame
     * @param inWindow inside the 3D time window (hands too)
     * @param covered  the 2D device layer covers it (terminal, p ≥ 0.61)
     * @param pose     its pose (view space, blocks / degrees)
     * @param ops      the PoseStack ops from identity (kf scale first)
     * @param matrix   {@code ops} as a matrix
     */
    public record Tablet(boolean draw, boolean inWindow, boolean covered, Pose pose,
                         List<TabletOp> ops, double[] matrix) {
    }

    /**
     * Both hands. {@code *Ops}: the full local chain (tablet space → arm model space, ModelPart
     * included); {@code *Pre}: tablet matrix × the first six ops (up to translate(s·0.12, −1.1,
     * 0.45)); {@code PlayerRenderer.renderRightHand / renderLeftHand} applies the ModelPart itself.
     */
    public record Hands(boolean draw, double dx, double dy, double exit, List<TabletOp> rightOps,
                        List<TabletOp> leftOps, double[] rightPre, double[] leftPre,
                        double[] rightMatrix, double[] leftMatrix) {
    }

    /**
     * The held item going down (p &lt; 0.30), or the vanilla empty-hand arm.
     *
     * @param draw still drawn this frame
     * @param e    eased progress of the press-down (the four-arm rule only looks at it)
     * @param ops  the PoseStack ops to push before the item / arm renders
     */
    public record Held(boolean draw, double e, List<TabletOp> ops, double[] matrix) {
    }

    /** Screen effects, only on the glass opening S. */
    public record Effects(Wake wake, Mask mask, Backdrop backdrop, double shadowAlpha, Sleep sleep) {
    }

    /** Wake: GLASS bands top and bottom open from the middle; two 1-GUI-pixel LIGHT edges. */
    public record Wake(boolean on, double open, double bandFrac, double edgeAlpha, int edgePx) {
    }

    /** Backlight mask over the opened middle. */
    public record Mask(boolean on, double alpha) {
    }

    /** The D2 backdrop's alpha factor. */
    public record Backdrop(boolean on, double alpha) {
    }

    /** Close: the LIGHT line on S's vertical centre shrinks from full width to 0. */
    public record Sleep(boolean on, double widthFrac, double px, double heightBlocks, double alpha) {
    }

    /**
     * The screen layer.
     *
     * @param draw     draw the screen (opening, p ≥ 0.61)
     * @param identity p = 1: identity transform
     * @param smooth   draw through the smooth-downscale target this frame
     * @param rectPx   where it draws ({@code null} when not drawn)
     */
    public record Ui(boolean draw, boolean identity, boolean smooth, RectPx rectPx) {
    }

    /**
     * The C1 close replay.
     *
     * @param draw         replay the captured frame
     * @param smooth       sample it linearly (smooth tier)
     * @param fromP        the p it was captured at (NaN when not drawn)
     * @param fromRectPx   the screen rectangle of that frame
     * @param fromPlanePx  the plane of that frame
     * @param fromSmooth   that frame was drawn through the smooth target
     * @param rectPx       where it replays now
     */
    public record Capture(boolean draw, boolean smooth, double fromP, RectPx fromRectPx,
                          RectPx fromPlanePx, boolean fromSmooth, RectPx rectPx) {
    }

    /** HUD white-list hiding and the crosshair. */
    public record Hud(boolean hidden, boolean crosshairHidden) {
    }
}
