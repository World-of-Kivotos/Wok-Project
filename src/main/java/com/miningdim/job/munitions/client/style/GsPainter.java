package com.miningdim.job.munitions.client.style;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * 逐帧绘制助手: 包住 GuiGraphics + Font + 界面原点 (left, top) + 本帧鼠标 + 本帧时间,
 * 让主题和界面代码直接用设计预览里的 GUI 像素坐标 (原点 = 界面左上角, 360x240)。
 *
 * 预览原语对应关系:
 * <ul>
 *   <li>R(x,y,w,h,c) -> {@link #rect}; 小数坐标 (按文字宽度算出来的底块) 用 {@link #rectF}</li>
 *   <li>box / dbox / corners / para / paraFit / paraR / stripes -> {@link GsCanvas} 默认方法 (本类包进 {@link #batch}, 每个图形一次 draw)</li>
 *   <li>text(s,x,y,c,scale,{bold,align,shadow}) -> {@link #text}; tw -> {@link #textWidth}; fitSc -> {@link #fitScale}</li>
 *   <li>blit / 物品 / 玩家头像 -> {@link #blit} / {@link #item} / {@link #face}</li>
 *   <li>hov(x,y,w,h) -> {@link #hov} (鼠标已按"浮层打开时整层失焦"处理, 见 {@link GunsmithStyledScreen})</li>
 * </ul>
 *
 * 每帧新建一个即可 (只是几个字段)。不持有状态, 不跨帧缓存。
 */
public final class GsPainter implements GsCanvas {

    /** 文字: 加粗。 */
    public static final int BOLD = 1;
    /** 文字: 原版投影。 */
    public static final int SHADOW = 1 << 1;
    /** 文字: x 为中心点。 */
    public static final int CENTER = 1 << 2;
    /** 文字: x 为右边缘。 */
    public static final int RIGHT = 1 << 3;

    /** 鼠标被"浮层吞掉"时使用的坐标 (离界面足够远, 任何 hov 都为 false)。 */
    public static final int NO_MOUSE = -10000;

    private final GuiGraphics graphics;
    private final Font font;
    private final int left;
    private final int top;
    private final double mouseX;
    private final double mouseY;
    private final long now;

    /**
     * @param left   界面左上角屏幕 x (通常是 AbstractContainerScreen.leftPos)
     * @param top    界面左上角屏幕 y
     * @param mouseX 屏幕坐标鼠标 x (传 {@link #NO_MOUSE} 表示本层不响应悬停)
     * @param mouseY 屏幕坐标鼠标 y
     * @param now    本帧毫秒时钟 (Util.getMillis()), 驱动所有动画
     */
    public GsPainter(GuiGraphics graphics, Font font, int left, int top, double mouseX, double mouseY, long now) {
        this.graphics = graphics;
        this.font = font;
        this.left = left;
        this.top = top;
        this.mouseX = mouseX;
        this.mouseY = mouseY;
        this.now = now;
    }

    public GuiGraphics graphics() {
        return graphics;
    }

    public Font font() {
        return font;
    }

    public int left() {
        return left;
    }

    public int top() {
        return top;
    }

    /** 本帧毫秒时钟 (预览里的 t)。 */
    public long now() {
        return now;
    }

    /** 鼠标相对界面原点的 x (失焦时是一个极远的负数)。 */
    public double mouseX() {
        return mouseX - left;
    }

    /** 鼠标相对界面原点的 y。 */
    public double mouseY() {
        return mouseY - top;
    }

    /** 预览 hov: 鼠标是否在 GUI 坐标矩形 [x, x+w) x [y, y+h) 内。 */
    public boolean hov(int x, int y, int w, int h) {
        double mx = mouseX();
        double my = mouseY();
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /** 同一个画笔换一组鼠标坐标 (例如浮层要用真实鼠标, 主层用失焦鼠标)。 */
    public GsPainter withMouse(double screenMouseX, double screenMouseY) {
        return new GsPainter(graphics, font, left, top, screenMouseX, screenMouseY, now);
    }

    // ------------------------------------------------------------------ rectangles

    @Override
    public void rect(int x, int y, int w, int h, int argb) {
        if (w <= 0 || h <= 0 || (argb >>> 24) == 0) {
            return;
        }
        int sx = left + x;
        int sy = top + y;
        graphics.fill(sx, sy, sx + w, sy + h, argb);
    }

    /** 小数坐标矩形 (预览 F 在 3 倍画布上按 1/3 像素取整; 这里用矩阵缩放画精确的小数矩形)。 */
    public void rectF(float x, float y, float w, float h, int argb) {
        if (w <= 0.0F || h <= 0.0F || (argb >>> 24) == 0) {
            return;
        }
        graphics.pose().pushPose();
        graphics.pose().translate(left + x, top + y, 0.0F);
        graphics.pose().scale(w, h, 1.0F);
        graphics.fill(0, 0, 1, 1, argb);
        graphics.pose().popPose();
    }

    /**
     * 把一段"只有 fill"的绘制合批成一次 draw (GuiGraphics.drawManaged)。1.20.1 的 fill / drawString 在非托管状态下
     * 每调一次就 endBatch 一次 (一次上传 + 一次 draw call), 所以成片的矩形必须包进这里。
     *
     * <p>可重入: 只有最外层真的进 drawManaged, 内层直接执行 (drawManaged 本身不可嵌套 —— 内层结束时会把
     * managed 置回 false, 外层剩下的 fill 又变回逐个 flush)。深度计数用静态字段: 渲染单线程, 而
     * {@link #withMouse} 会复制出新画笔, 实例字段看不到跨画笔的嵌套。
     *
     * <p>只能包纯矩形代码。贴图 (blit / 头像) 走 Tesselator 立即绘制, 会插到还在排队的矩形前面;
     * 物品渲染自己会 flush。文字混进来顺序仍对 (gui 与 text 都是非固定渲染类型, 换类型即结束上一批), 但一般留在外面。
     */
    public void batch(Runnable fillsOnly) {
        if (batchDepth > 0) {
            fillsOnly.run();
            return;
        }
        batchDepth++;
        try {
            graphics.drawManaged(fillsOnly);
        } finally {
            batchDepth--;
        }
    }

    /** 当前 {@link #batch} 嵌套深度 (0 = 不在合批里)。 */
    private static int batchDepth;

    // 多笔矩形的原语各自合批成一次 draw (嵌在外层 batch 里时并入外层)。

    @Override
    public void box(int x, int y, int w, int h, int argb) {
        batch(() -> GsCanvas.super.box(x, y, w, h, argb));
    }

    @Override
    public void dbox(int x, int y, int w, int h, int argb) {
        batch(() -> GsCanvas.super.dbox(x, y, w, h, argb));
    }

    @Override
    public void para(int x, int y, int w, int h, int argb) {
        batch(() -> GsCanvas.super.para(x, y, w, h, argb));
    }

    @Override
    public void paraFit(int x, int y, int w, int h, int argb) {
        batch(() -> GsCanvas.super.paraFit(x, y, w, h, argb));
    }

    @Override
    public void paraR(int x, int y, int w, int h, int argb) {
        batch(() -> GsCanvas.super.paraR(x, y, w, h, argb));
    }

    @Override
    public void corners(int x, int y, int w, int h, int argb, int n) {
        batch(() -> GsCanvas.super.corners(x, y, w, h, argb, n));
    }

    @Override
    public void stripes(int x, int y, int w, int h, int colorA, int colorB, int period, int on, int phase) {
        batch(() -> GsCanvas.super.stripes(x, y, w, h, colorA, colorB, period, on, phase));
    }

    // ------------------------------------------------------------------ text

    /** 字面文字 (数字、口径代号等数据; 界面文案请走翻译键 Component)。 */
    public float text(String s, float x, float y, int argb, float scale, int flags) {
        return text(Component.literal(s), x, y, argb, scale, flags);
    }

    /**
     * 预览 text(s, x, y, c, scale, {bold, align, shadow})。(x, y) 是 GUI 坐标 (可带小数), 对齐按缩放后的宽度算。
     *
     * @return 缩放后的文字宽度 (GUI 像素)
     */
    public float text(Component s, float x, float y, int argb, float scale, int flags) {
        Component c = styled(s, flags);
        float w = font.width(c) * scale;
        float dx = x;
        if ((flags & CENTER) != 0) {
            dx -= w / 2.0F;
        } else if ((flags & RIGHT) != 0) {
            dx -= w;
        }
        graphics.pose().pushPose();
        graphics.pose().translate(left + dx, top + y, 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        graphics.drawString(font, c, 0, 0, textColor(argb), (flags & SHADOW) != 0);
        graphics.pose().popPose();
        return w;
    }

    /** 预览 tw: 缩放后的宽度 (GUI 像素)。 */
    public float textWidth(Component s, float scale, boolean bold) {
        return font.width(styled(s, bold ? BOLD : 0)) * scale;
    }

    public float textWidth(String s, float scale, boolean bold) {
        return textWidth(Component.literal(s), scale, bold);
    }

    /** 预览 fitSc: 在 [min, pref] 之间取能塞进 maxW 的最大缩放。 */
    public float fitScale(Component s, float maxW, float pref, float min, boolean bold) {
        float raw = Math.max(1.0F, textWidth(s, 1.0F, bold));
        return Math.max(min, Math.min(pref, maxW / raw));
    }

    public float fitScale(String s, float maxW, float pref, float min, boolean bold) {
        return fitScale(Component.literal(s), maxW, pref, min, bold);
    }

    private static Component styled(Component s, int flags) {
        if ((flags & BOLD) == 0) {
            return s;
        }
        return s.copy().withStyle(style -> style.withBold(true));
    }

    /** Font 会把 alpha 为 0 的颜色当不透明; 这里统一保证传进去的是完整 ARGB。 */
    private static int textColor(int argb) {
        return (argb & 0xFC000000) == 0 ? (argb | 0xFF000000) : argb;
    }

    // ------------------------------------------------------------------ textures & items

    /**
     * 贴图: 把贴图上 (u, v) 起 uW x vH 的一块缩放画到 GUI 矩形 (x, y, w, h)。
     * texW/texH 是整张贴图的像素尺寸。
     *
     * <p>显式开混合: 前面的 fill (RenderType.gui) 收尾时会关掉混合, 而 GuiGraphics.blit 不会再打开,
     * position_tex 着色器只丢弃 alpha 恰为 0 的像素 —— 不开混合时半透明像素 (弹药展示图的地面阴影、
     * 零件图标的抗锯齿边) 会被画成不透明。
     */
    public void blit(ResourceLocation texture, int x, int y, int w, int h,
                     float u, float v, int uW, int vH, int texW, int texH) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        graphics.blit(texture, left + x, top + y, w, h, u, v, uW, vH, texW, texH);
        RenderSystem.disableBlend();
    }

    /** 半透明贴图 (预览 blit(..., alpha))。 */
    public void blit(ResourceLocation texture, int x, int y, int w, int h,
                     float u, float v, int uW, int vH, int texW, int texH, float alpha) {
        if (alpha >= 0.999F) {
            blit(texture, x, y, w, h, u, v, uW, vH, texW, texH);
            return;
        }
        if (alpha <= 0.0F) {
            return;
        }
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        graphics.setColor(1.0F, 1.0F, 1.0F, alpha);
        graphics.blit(texture, left + x, top + y, w, h, u, v, uW, vH, texW, texH);
        graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.disableBlend();
    }

    /**
     * 着色贴图: 贴图颜色乘以 rgb (着色器颜色), 整体不透明度 alpha。(x, y) 可带小数 (蓝图风格描边要按 1 个屏幕像素错位)。
     * 用于把 TaCZ 的灰色枪械剪影染成各风格的颜色。
     */
    public void blitTinted(ResourceLocation texture, float x, float y, int w, int h,
                           float u, float v, int uW, int vH, int texW, int texH, int rgb, float alpha) {
        if (alpha <= 0.0F || w <= 0 || h <= 0) {
            return;
        }
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        graphics.setColor(((rgb >> 16) & 0xFF) / 255.0F, ((rgb >> 8) & 0xFF) / 255.0F, (rgb & 0xFF) / 255.0F,
                Math.min(1.0F, alpha));
        graphics.pose().pushPose();
        graphics.pose().translate(left + x, top + y, 0.0F);
        graphics.blit(texture, 0, 0, w, h, u, v, uW, vH, texW, texH);
        graphics.pose().popPose();
        graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.disableBlend();
    }

    /** 一个屏幕像素折合多少 GUI 像素 (1 / 界面缩放)。 */
    public static float screenPixel() {
        double scale = Minecraft.getInstance().getWindow().getGuiScale();
        return scale > 0.0D ? (float) (1.0D / scale) : 1.0F;
    }

    /** 物品图标 (16x16, GUI 坐标)。 */
    public void item(ItemStack stack, int x, int y) {
        if (!stack.isEmpty()) {
            graphics.renderItem(stack, left + x, top + y);
        }
    }

    /** 物品数量/耐久角标; countText 为 null 时按原版规则显示数量。 */
    public void itemDecorations(ItemStack stack, int x, int y, String countText) {
        if (!stack.isEmpty()) {
            graphics.renderItemDecorations(font, stack, left + x, top + y, countText);
        }
    }

    /**
     * "褪色"物品 (预览 blit(..., alpha) 画在卡片上的锁定口径、空料槽的剪影)。物品模型没法直接调透明度,
     * 所以先正常画, 再在物品之上 (z 200) 盖一层 backdrop 色、不透明度 (1 - alpha) 的面纱。
     * backdrop 传物品下面那块底的颜色 (卡片内底色等)。
     */
    public void itemFaded(ItemStack stack, int x, int y, float alpha, int backdropRgb) {
        if (stack.isEmpty() || alpha <= 0.0F) {
            return;
        }
        item(stack, x, y);
        if (alpha >= 0.999F) {
            return;
        }
        int veil = GsCanvas.rgba((backdropRgb >> 16) & 0xFF, (backdropRgb >> 8) & 0xFF, backdropRgb & 0xFF,
                1.0D - alpha);
        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, 200.0F);
        rect(x, y, 16, 16, veil);
        graphics.pose().popPose();
    }

    /** 玩家头像 (含帽子层), size x size。skin 为 null 时不画。 */
    public void face(ResourceLocation skin, int x, int y, int size) {
        if (skin != null) {
            PlayerFaceRenderer.draw(graphics, skin, left + x, top + y, size);
        }
    }

    /** 在更高的 z 层里画一段 (浮层 350、物品之上的前景 300 等)。 */
    public void atZ(float z, Runnable draw) {
        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, z);
        draw.run();
        graphics.pose().popPose();
    }
}
