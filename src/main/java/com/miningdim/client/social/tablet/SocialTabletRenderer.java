package com.miningdim.client.social.tablet;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.util.List;

/**
 * 社交平板的 3D 层：银色机身（正面贴图 + alpha 轮廓挤出的侧壁/背板）、屏幕上的浏览器实时画面，
 * 以及两只手（白手套，或玩家自己的皮肤手臂）。
 *
 * <p>坐标系沿用 {@link TabletPose3D}：原点 = 页面平面中心，+z 朝眼睛，正面在 z = 0。机身比页面
 * 平面大 {@link SocialDeviceArt#deviceScale()} 倍，页面正好铺满机身的屏幕开窗；于是动画推到
 * s = 1 时页面铺满视口，机身边框刚好移出屏幕外，与随后的 2D 页面无缝衔接。</p>
 */
public final class SocialTabletRenderer {
    /** 浏览器画面相对正面的深度偏移，避免与机身正面 z-fighting。 */
    private static final double SCREEN_Z = 0.0004D;
    /** 机身厚度（block）：Slate 11 很薄，2 cm 既够看出侧壁又不会显得像砖。 */
    private static final double THICKNESS = 0.02D;

    private SocialTabletRenderer() {
    }

    /**
     * 画一帧。
     *
     * @param kf         手部投影的 FOV 补偿
     * @param pose       平板位姿
     * @param face       页面平面尺寸
     * @param geom       设备 D2 几何（手套抓握位置用）
     * @param p          动画进度（手的收放）
     * @param hands      是否画手
     * @param appearance 手的外观（白手套 / 玩家皮肤）
     * @param browserTex 浏览器贴图 id（&lt;= 0 时不画屏幕）
     * @param browserReady 浏览器是否已经出过帧
     */
    public static void draw(PoseStack pose, MultiBufferSource buffers, int light, double kf,
                            TabletPose3D.Pose pose3D, TabletPose3D.Face face, TabletD2Geometry geom,
                            double p, boolean hands, HandAppearance appearance, int browserTex,
                            boolean browserReady) {
        pose.pushPose();
        try {
            TabletPoseOps.apply(pose, TabletPose3D.tabletOps(pose3D, kf));
            drawDevice(pose, buffers, light, face, browserTex, browserReady);
            if (hands) {
                HandAppearance shown = appearance == null ? HandAppearance.gloves() : appearance;
                if (shown.usesSkin()) {
                    drawSkinArm(pose, buffers, light, face, p, true, shown);
                    drawSkinArm(pose, buffers, light, face, p, false, shown);
                } else {
                    drawGlove(pose, buffers, light, face, geom, p, true);
                    drawGlove(pose, buffers, light, face, geom, p, false);
                }
            }
        } finally {
            pose.popPose();
        }
    }

    /**
     * 手的外观。
     *
     * @param style 手套 / 皮肤
     * @param skin  皮肤贴图（皮肤模式用；手套模式为 {@code null}）
     * @param slim  slim（3 px 宽）皮肤手臂
     */
    public record HandAppearance(TabletHandStyle style, ResourceLocation skin, boolean slim) {
        public static HandAppearance gloves() {
            return new HandAppearance(TabletHandStyle.GLOVE, null, false);
        }

        public boolean usesSkin() {
            return style == TabletHandStyle.SKIN && skin != null;
        }
    }

    // =========================================================================================
    // device
    // =========================================================================================

    private static void drawDevice(PoseStack pose, MultiBufferSource buffers, int light,
                                   TabletPose3D.Face face, int browserTex, boolean browserReady) {
        Matrix4f m = pose.last().pose();
        Matrix3f n = pose.last().normal();
        double scale = SocialDeviceArt.deviceScale();
        double x0 = -face.wf() * scale / 2.0D;
        double x1 = face.wf() * scale / 2.0D;
        double yTop = face.hf() * scale / 2.0D;
        double yBottom = -face.hf() * scale / 2.0D;

        // 先登记机身（不透明），再登记正面与屏幕（半透明）。BufferSource 按首次 getBuffer 的顺序
        // 出图，正面在机身之后画，圆角透明像素才会露出背板而不是世界背景。
        TabletDeviceModel.Mesh mesh = SocialDeviceArt.mesh();
        VertexConsumer body = mesh == null ? null
                : buffers.getBuffer(SocialTabletRenderTypes.body(SocialDeviceArt.WHITE));

        // The silver shell, full bright: it must match the GUI's colours.
        VertexConsumer front = buffers.getBuffer(SocialTabletRenderTypes.front());
        faceVertex(front, m, x0, yTop, 0.0D, 0.0F, 0.0F, 0xFFFFFFFF);
        faceVertex(front, m, x0, yBottom, 0.0D, 0.0F, 1.0F, 0xFFFFFFFF);
        faceVertex(front, m, x1, yBottom, 0.0D, 1.0F, 1.0F, 0xFFFFFFFF);
        faceVertex(front, m, x1, yTop, 0.0D, 1.0F, 0.0F, 0xFFFFFFFF);

        // The live page, exactly over the glass opening.
        if (browserReady && browserTex > 0) {
            double px0 = -face.wf() / 2.0D;
            double px1 = face.wf() / 2.0D;
            double pyTop = face.hf() / 2.0D;
            double pyBottom = -face.hf() / 2.0D;
            VertexConsumer screen = buffers.getBuffer(SocialTabletRenderTypes.browser(browserTex));
            faceVertex(screen, m, px0, pyTop, SCREEN_Z, 0.0F, 0.0F, 0xFFFFFFFF);
            faceVertex(screen, m, px0, pyBottom, SCREEN_Z, 0.0F, 1.0F, 0xFFFFFFFF);
            faceVertex(screen, m, px1, pyBottom, SCREEN_Z, 1.0F, 1.0F, 0xFFFFFFFF);
            faceVertex(screen, m, px1, pyTop, SCREEN_Z, 1.0F, 0.0F, 0xFFFFFFFF);
        }

        // Extruded walls and back plate from the shell texture's alpha outline.
        if (body == null) {
            return;
        }
        double thick = THICKNESS;
        int wallColor = SocialDeviceArt.caseColor();
        for (TabletDeviceModel.Edge e : mesh.edges()) {
            double ax = SocialDeviceArt.deviceX(face, e.x0());
            double ay = SocialDeviceArt.deviceY(face, e.y0());
            double bx = SocialDeviceArt.deviceX(face, e.x1());
            double by = SocialDeviceArt.deviceY(face, e.y1());
            float nx = e.dy();
            float ny = e.dx();
            bodyVertex(body, m, n, ax, ay, 0.0D, wallColor, light, nx, ny, 0.0F);
            bodyVertex(body, m, n, ax, ay, -thick, wallColor, light, nx, ny, 0.0F);
            bodyVertex(body, m, n, bx, by, -thick, wallColor, light, nx, ny, 0.0F);
            bodyVertex(body, m, n, bx, by, 0.0D, wallColor, light, nx, ny, 0.0F);
        }
        int back = SocialDeviceArt.caseLo();
        for (TabletDeviceModel.BackRect r : mesh.back()) {
            double bx0 = SocialDeviceArt.deviceX(face, r.left());
            double bx1 = SocialDeviceArt.deviceX(face, r.right());
            double by0 = SocialDeviceArt.deviceY(face, r.top());
            double by1 = SocialDeviceArt.deviceY(face, r.bottom());
            bodyVertex(body, m, n, bx0, by0, -thick, back, light, 0.0F, 0.0F, -1.0F);
            bodyVertex(body, m, n, bx1, by0, -thick, back, light, 0.0F, 0.0F, -1.0F);
            bodyVertex(body, m, n, bx1, by1, -thick, back, light, 0.0F, 0.0F, -1.0F);
            bodyVertex(body, m, n, bx0, by1, -thick, back, light, 0.0F, 0.0F, -1.0F);
        }
    }

    // =========================================================================================
    // gloved hands
    // =========================================================================================

    /**
     * 一只手：深色制服袖 + 白色手套，直接从设备下角往下长。
     *
     * <p>不用步战的 {@code handLocalOps}：那条链是为“玩家手臂模型 + 射线式握持”调的，套到我们的
     * 窄边框 Slate 上时前臂会横着甩出画面两侧（实测见 storyboard）。这里改成“腕点钉在设备下角、
     * 前臂向前下方收”的自有变换，外加步战的 {@link TabletPose3D#handsExit} 收手进度：推近时手向下
     * 滑出画面。</p>
     */
    private static void drawGlove(PoseStack pose, MultiBufferSource buffers, int light,
                                  TabletPose3D.Face face, TabletD2Geometry geom, double p,
                                  boolean right) {
        double deviceScale = SocialDeviceArt.deviceScale();
        double halfW = face.wf() * deviceScale / 2.0D;
        double halfH = face.hf() * deviceScale / 2.0D;
        double sx = right ? 1.0D : -1.0D;
        double exit = TabletPose3D.handsExit(p);
        pose.pushPose();
        try {
            // 腕点：钉在设备下角，手背朝外、手指从下缘翻到正面，推近时随手势下移。
            pose.translate(sx * halfW * 0.80D, -halfH * 1.06D - exit * 0.5D, -0.06D);
            pose.mulPose(com.mojang.math.Axis.ZP.rotationDegrees((float) (sx * (-12.0D - exit * 18.0D))));
            pose.mulPose(com.mojang.math.Axis.XP.rotationDegrees((float) (-32.0D - exit * 8.0D)));
            pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees((float) (sx * 8.0D)));
            pose.scale(0.72F, 0.72F, 0.72F);
            pose.scale(1.0F / 16.0F, 1.0F / 16.0F, 1.0F / 16.0F);
            Matrix4f m = pose.last().pose();
            Matrix3f n = pose.last().normal();
            VertexConsumer vc = buffers.getBuffer(SocialTabletRenderTypes.body(SocialDeviceArt.WHITE));
            int sleeve = 0xFF2A3038;
            int cuff = 0xFFE3E7EC;
            int hand = 0xFFF3F5F8;
            int finger = 0xFFE7EBF0;
            // 制服袖（向下延伸，越远越靠近镜头，最后滑出画面）
            box(vc, m, n, light, -2.1D, -14.0D, -2.1D, 2.1D, -4.2D, 2.1D, sleeve);
            // 袖口卷边
            box(vc, m, n, light, -2.35D, -4.6D, -2.35D, 2.35D, -3.0D, 2.35D, cuff);
            // 手掌：大部分在机身后面，只在下缘露出手背
            box(vc, m, n, light, -1.5D, -3.2D, -2.6D, 1.5D, 0.6D, 0.2D, hand);
            // 指节：下缘一道很薄的指背，给出“握”的轮廓但不做夸张的指排
            box(vc, m, n, light, -1.45D, 0.4D, 0.2D, 1.45D, 1.9D, 1.4D, finger);
            // 拇指：从内侧贴住机身
            box(vc, m, n, light, sx * -2.1D, -1.6D, -0.2D, sx * -0.75D, 0.4D, 1.4D, hand);
        } finally {
            pose.popPose();
        }
    }

    // =========================================================================================
    // skinned hands
    // =========================================================================================

    /**
     * 玩家皮肤手臂：一只 4×12×4（slim 3×12×4）的方块。手端顶在设备下角，肩端滑出画面；
     * 前面再补一条指节和一根拇指，保住“握住”的轮廓。
     *
     * <p>UV 用原版 64×64 皮肤的右臂 (40,16) / 左臂 (32,48) 展开。原版手臂是“肩在上、手在下”，
     * 这里倒过来（手朝上），所以侧面的 v 也上下翻转：y0（肩端）取展开图的上沿，y1（手端）取下沿。</p>
     */
    private static void drawSkinArm(PoseStack pose, MultiBufferSource buffers, int light,
                                    TabletPose3D.Face face, double p, boolean right,
                                    HandAppearance appearance) {
        double deviceScale = SocialDeviceArt.deviceScale();
        double halfW = face.wf() * deviceScale / 2.0D;
        double halfH = face.hf() * deviceScale / 2.0D;
        double sx = right ? 1.0D : -1.0D;
        double exit = TabletPose3D.handsExit(p);
        pose.pushPose();
        try {
            pose.translate(sx * halfW * 0.80D, -halfH * 1.06D - exit * 0.5D, -0.06D);
            pose.mulPose(Axis.ZP.rotationDegrees((float) (sx * (-12.0D - exit * 18.0D))));
            pose.mulPose(Axis.XP.rotationDegrees((float) (-32.0D - exit * 8.0D)));
            pose.mulPose(Axis.YP.rotationDegrees((float) (sx * 8.0D)));
            pose.scale(0.72F, 0.72F, 0.72F);
            pose.scale(1.0F / 16.0F, 1.0F / 16.0F, 1.0F / 16.0F);
            Matrix4f m = pose.last().pose();
            Matrix3f n = pose.last().normal();
            VertexConsumer vc = buffers.getBuffer(SocialTabletRenderTypes.body(appearance.skin()));
            int white = 0xFFFFFFFF;
            double width = appearance.slim() ? 3.0D : 4.0D;
            double half = width / 2.0D;
            int uPx = right ? 40 : 32;
            int vPx = right ? 16 : 48;
            // 前臂：+y 手端（贴设备下角），-y 肩端（滑出画面）。
            skinArm(vc, m, n, light, -half, -10.0D, -4.2D, half, 2.0D, -0.2D,
                    uPx, vPx, width, 12.0D, 4.0D, white);
            // 指节：设备下缘前面的一条，给出“握住”的轮廓。指节/拇指取前臂正面最下面 4 行的
            // 肤色，和手臂正面同色；用底面的“手心”块会偏亮（多数皮肤的手心是浅色），拼在
            // 一起像贴了两块纸。
            int handU = (int) (uPx + 4.0D);
            int handV = (int) (vPx + 4.0D + 12.0D - 4.0D);
            skinPatchBox(vc, m, n, light, -half + 0.25D, 0.6D, -0.2D,
                    half - 0.25D, 2.2D, 1.2D, handU, handV, width, 4.0D);
            // 拇指：从内侧贴住机身正面。
            double thumbA = sx * -2.0D;
            double thumbB = sx * -0.7D;
            skinPatchBox(vc, m, n, light, Math.min(thumbA, thumbB), -1.2D, -0.2D,
                    Math.max(thumbA, thumbB), 0.8D, 1.2D, handU, handV, width, 4.0D);
        } finally {
            pose.popPose();
        }
    }

    /**
     * 原版皮肤展开的一条手臂。box 的 +y = 手端、-y = 肩端，与皮肤模型自身的上下相反。
     * u/v 以 64×64 皮肤像素传入；HD 皮肤模组会拉伸，但仍能认出手臂。
     */
    private static void skinArm(VertexConsumer vc, Matrix4f m, Matrix3f n, int light,
                                double x0, double y0, double z0, double x1, double y1, double z1,
                                int uPx, int vPx, double w, double h, double d, int color) {
        final double inv = 1.0D / 64.0D;
        double uTop = (uPx + d) * inv;
        // 展开图是十字形：正面的左边那块是 +x 面，右边那块是 -x 面。
        double uEast = uPx * inv;
        double uWest = (uPx + d + w) * inv;
        double uSouth = (uPx + d) * inv;
        double uNorth = (uPx + d + w + d) * inv;
        double vTop = vPx * inv;
        double vBottom = (vPx + d) * inv;
        double vShoulder = (vPx + d) * inv;
        double vHand = (vPx + d + h) * inv;
        // +y 手端面：用正面的手部肤色。原版手心的 4×4 块通常比手背浅，斜着露出来会像贴了一块亮纸。
        quad(vc, m, n, light, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1,
                uSouth, vHand - d * inv, uSouth + w * inv, vHand, color, 0.0F, 1.0F, 0.0F);
        // -y 肩端面 = 皮肤顶面（通常在画面外）
        quad(vc, m, n, light, x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0,
                uTop, vTop, uTop + w * inv, vBottom, color, 0.0F, -1.0F, 0.0F);
        // +z 正面
        quad(vc, m, n, light, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1,
                uSouth, vShoulder, uSouth + w * inv, vHand, color, 0.0F, 0.0F, 1.0F);
        // -z 背面
        quad(vc, m, n, light, x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0,
                uNorth, vShoulder, uNorth + w * inv, vHand, color, 0.0F, 0.0F, -1.0F);
        // +x 右面
        quad(vc, m, n, light, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0,
                uEast, vShoulder, uEast + d * inv, vHand, color, 1.0F, 0.0F, 0.0F);
        // -x 左面
        quad(vc, m, n, light, x0, y0, z1, x0, y0, z0, x0, y1, z0, x0, y1, z1,
                uWest, vShoulder, uWest + d * inv, vHand, color, -1.0F, 0.0F, 0.0F);
    }

    /** 用皮肤上手部正面那一小块（w×4）贴满整个小方块：指节/拇指。 */
    private static void skinPatchBox(VertexConsumer vc, Matrix4f m, Matrix3f n, int light,
                                     double x0, double y0, double z0, double x1, double y1, double z1,
                                     int uPx, int vPx, double w, double h) {
        final double inv = 1.0D / 64.0D;
        double u0 = uPx * inv;
        double u1 = (uPx + w) * inv;
        double v0 = vPx * inv;
        double v1 = (vPx + h) * inv;
        int color = 0xFFFFFFFF;
        quad(vc, m, n, light, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1,
                u0, v0, u1, v1, color, 0.0F, 1.0F, 0.0F);
        quad(vc, m, n, light, x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0,
                u0, v0, u1, v1, color, 0.0F, -1.0F, 0.0F);
        quad(vc, m, n, light, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1,
                u0, v0, u1, v1, color, 0.0F, 0.0F, 1.0F);
        quad(vc, m, n, light, x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0,
                u0, v0, u1, v1, color, 0.0F, 0.0F, -1.0F);
        quad(vc, m, n, light, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0,
                u0, v0, u1, v1, color, 1.0F, 0.0F, 0.0F);
        quad(vc, m, n, light, x0, y0, z1, x0, y0, z0, x0, y1, z0, x0, y1, z1,
                u0, v0, u1, v1, color, -1.0F, 0.0F, 0.0F);
    }

    private static void quad(VertexConsumer vc, Matrix4f m, Matrix3f n, int light,
                             double x0, double y0, double z0, double x1, double y1, double z1,
                             double x2, double y2, double z2, double x3, double y3, double z3,
                             double u0, double v0, double u1, double v1, int color,
                             float nx, float ny, float nz) {
        skinVertex(vc, m, x0, y0, z0, u0, v0, color, light, n, nx, ny, nz);
        skinVertex(vc, m, x1, y1, z1, u1, v0, color, light, n, nx, ny, nz);
        skinVertex(vc, m, x2, y2, z2, u1, v1, color, light, n, nx, ny, nz);
        skinVertex(vc, m, x3, y3, z3, u0, v1, color, light, n, nx, ny, nz);
    }

    private static void skinVertex(VertexConsumer vc, Matrix4f m, double x, double y, double z,
                                   double u, double v, int color, int light, Matrix3f n,
                                   float nx, float ny, float nz) {
        vc.vertex(m, (float) x, (float) y, (float) z)
                .color((color >>> 16) & 0xFF, (color >>> 8) & 0xFF, color & 0xFF, color >>> 24)
                .uv((float) u, (float) v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(light)
                .normal(n, nx, ny, nz)
                .endVertex();
    }

    /** Axis-aligned box with per-face shading, drawn in block units. */
    private static void box(VertexConsumer vc, Matrix4f m, Matrix3f n, int light,
                            double x0, double y0, double z0, double x1, double y1, double z1,
                            int argb) {
        int top = shade(argb, 1.0F);
        int bottom = shade(argb, 0.55F);
        int east = shade(argb, 0.82F);
        int west = shade(argb, 0.74F);
        int south = shade(argb, 0.9F);
        int north = shade(argb, 0.66F);
        // +y
        bodyVertex(vc, m, n, x0, y1, z0, top, light, 0.0F, 1.0F, 0.0F);
        bodyVertex(vc, m, n, x0, y1, z1, top, light, 0.0F, 1.0F, 0.0F);
        bodyVertex(vc, m, n, x1, y1, z1, top, light, 0.0F, 1.0F, 0.0F);
        bodyVertex(vc, m, n, x1, y1, z0, top, light, 0.0F, 1.0F, 0.0F);
        // -y
        bodyVertex(vc, m, n, x0, y0, z1, bottom, light, 0.0F, -1.0F, 0.0F);
        bodyVertex(vc, m, n, x0, y0, z0, bottom, light, 0.0F, -1.0F, 0.0F);
        bodyVertex(vc, m, n, x1, y0, z0, bottom, light, 0.0F, -1.0F, 0.0F);
        bodyVertex(vc, m, n, x1, y0, z1, bottom, light, 0.0F, -1.0F, 0.0F);
        // +z
        bodyVertex(vc, m, n, x0, y0, z1, south, light, 0.0F, 0.0F, 1.0F);
        bodyVertex(vc, m, n, x1, y0, z1, south, light, 0.0F, 0.0F, 1.0F);
        bodyVertex(vc, m, n, x1, y1, z1, south, light, 0.0F, 0.0F, 1.0F);
        bodyVertex(vc, m, n, x0, y1, z1, south, light, 0.0F, 0.0F, 1.0F);
        // -z
        bodyVertex(vc, m, n, x1, y0, z0, north, light, 0.0F, 0.0F, -1.0F);
        bodyVertex(vc, m, n, x0, y0, z0, north, light, 0.0F, 0.0F, -1.0F);
        bodyVertex(vc, m, n, x0, y1, z0, north, light, 0.0F, 0.0F, -1.0F);
        bodyVertex(vc, m, n, x1, y1, z0, north, light, 0.0F, 0.0F, -1.0F);
        // +x
        bodyVertex(vc, m, n, x1, y0, z0, east, light, 1.0F, 0.0F, 0.0F);
        bodyVertex(vc, m, n, x1, y0, z1, east, light, 1.0F, 0.0F, 0.0F);
        bodyVertex(vc, m, n, x1, y1, z1, east, light, 1.0F, 0.0F, 0.0F);
        bodyVertex(vc, m, n, x1, y1, z0, east, light, 1.0F, 0.0F, 0.0F);
        // -x
        bodyVertex(vc, m, n, x0, y0, z1, west, light, -1.0F, 0.0F, 0.0F);
        bodyVertex(vc, m, n, x0, y0, z0, west, light, -1.0F, 0.0F, 0.0F);
        bodyVertex(vc, m, n, x0, y1, z0, west, light, -1.0F, 0.0F, 0.0F);
        bodyVertex(vc, m, n, x0, y1, z1, west, light, -1.0F, 0.0F, 0.0F);
    }

    private static int shade(int argb, float factor) {
        int a = (argb >>> 24) & 0xFF;
        int r = Math.max(0, Math.min(255, Math.round(((argb >>> 16) & 0xFF) * factor)));
        int g = Math.max(0, Math.min(255, Math.round(((argb >>> 8) & 0xFF) * factor)));
        int b = Math.max(0, Math.min(255, Math.round((argb & 0xFF) * factor)));
        return a << 24 | r << 16 | g << 8 | b;
    }

    // =========================================================================================
    // vertex helpers
    // =========================================================================================

    private static void faceVertex(VertexConsumer vc, Matrix4f m, double x, double y, double z,
                                   float u, float v, int argb) {
        vc.vertex(m, (float) x, (float) y, (float) z)
                .color((argb >>> 16) & 0xFF, (argb >>> 8) & 0xFF, argb & 0xFF, argb >>> 24)
                .uv(u, v).endVertex();
    }

    private static void bodyVertex(VertexConsumer vc, Matrix4f m, Matrix3f n, double x, double y,
                                   double z, int argb, int light, float nx, float ny, float nz) {
        vc.vertex(m, (float) x, (float) y, (float) z)
                .color((argb >>> 16) & 0xFF, (argb >>> 8) & 0xFF, argb & 0xFF, 0xFF)
                .uv(0.5F, 0.5F).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light)
                .normal(n, nx, ny, nz).endVertex();
    }
}
