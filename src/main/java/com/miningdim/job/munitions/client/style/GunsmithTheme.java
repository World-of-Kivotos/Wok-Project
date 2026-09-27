package com.miningdim.job.munitions.client.style;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.munitions.GunsmithUiStyle;
import com.miningdim.job.munitions.MunitionsCaliber;
import com.miningdim.job.munitions.MunitionsClientConfig;
import com.miningdim.job.munitions.gunsmith.GunsmithPartQuality;
import com.miningdim.job.munitions.gunsmith.GunsmithPartRarity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;

/**
 * 一种界面风格的全部画法 (设计预览里的 A / B_ / C_ 对象, 方法逐个对应)。三种风格共用同一套布局与数据,
 * 只在材质、选中态和两块展示区的画法上不同, 所以换风格不改变任何按钮、格子的位置。
 *
 * 坐标全部是 GUI 像素 (界面左上角为原点)。颜色是 ARGB。
 * 取当前玩家选中的风格: {@link #current()}。
 */
public abstract class GunsmithTheme {

    /** 控件状态。按钮只用 IDLE / HOVER / OFF; 页签、卡片、行、品质钮用 IDLE / HOVER / SEL / LOCK。 */
    public enum State {
        IDLE, HOVER, SEL, LOCK, OFF
    }

    /** 按钮语义: 开工 (主操作) / 停止 / 次要操作。 */
    public enum ButtonKind {
        GO, STOP, ACC
    }

    /** 进度条语义: 进度 / 电量 / 缓冲 / 告警。 */
    public enum BarKind {
        PROG, FE, BUF, BAD
    }

    /** 状态灯: 待机 / 运行 (闪) / 完成。 */
    public enum LampState {
        IDLE, RUN, DONE
    }

    /** 文字色板 (预览 T.C)。 */
    public record Palette(int text, int muted, int dim, int accent, int good, int bad) {
    }

    /**
     * 标题栏数据 (预览 header(o))。
     *
     * @param title   大标题 (如 "弹药制造")
     * @param en      英文小字副标 (如 "MUNITIONS FACTORY"; 仍走翻译键)
     * @param badge   等级徽章, 冲压机传 null (蓝图风格在该格改显示日期)
     * @param sub     副标题 (如 "军火商专属产线")
     * @param code    图号 (蓝图风格显示, 如 "WOK-MUN-762X39")
     * @param running 产线是否在跑 (工控风格的三色运行灯)
     * @param level   蓝图风格等级格里的 "L6"
     */
    public record Header(Component title, Component en, @Nullable GunsmithUi.Badge badge, Component sub,
                         String code, boolean running, int level) {
    }

    private static final GunsmithTheme ACADEMY = new AcademyTheme();
    private static final GunsmithTheme INDUSTRIAL = new IndustrialTheme();
    private static final GunsmithTheme BLUEPRINT = new BlueprintTheme();

    /** 本玩家当前选择的风格 (读客户端配置; 每帧调用也很便宜)。 */
    public static GunsmithTheme current() {
        return of(MunitionsClientConfig.gunsmithUiStyle());
    }

    public static GunsmithTheme of(GunsmithUiStyle style) {
        return switch (style) {
            case ACADEMY -> ACADEMY;
            case INDUSTRIAL -> INDUSTRIAL;
            case BLUEPRINT -> BLUEPRINT;
        };
    }

    /** 文字色板 {text, muted, dim, accent, good, bad} (预览 T.C)。 */
    public final Palette c;

    @Nullable
    private ResourceLocation backgroundLocation;
    @Nullable
    private DynamicTexture backgroundTexture;

    protected GunsmithTheme(Palette palette) {
        this.c = palette;
    }

    public abstract GunsmithUiStyle style();

    /** 同 {@link #c}。 */
    public final Palette palette() {
        return c;
    }

    // ================================================================== background

    /**
     * 静态底图: 边框、底纹、标题栏的静态底色。只画进 {@link PixelCanvas} 一次 (见 {@link #bg}),
     * 所以这里可以放上万个 1px 点。只允许纯矩形, 不能有文字。
     */
    protected abstract void paintBackground(GsCanvas c);

    /** 预览 bg(): 把缓存好的底图 1:1 贴到界面上 (含标题栏静态底色)。 */
    public final void bg(GsPainter p) {
        ResourceLocation location = backgroundTexture();
        p.blit(location, 0, 0, GunsmithUi.GUI_W, GunsmithUi.GUI_H, 0.0F, 0.0F,
                GunsmithUi.GUI_W, GunsmithUi.GUI_H, GunsmithUi.GUI_W, GunsmithUi.GUI_H);
    }

    private ResourceLocation backgroundTexture() {
        TextureManager textures = Minecraft.getInstance().getTextureManager();
        if (backgroundLocation == null || backgroundTexture == null) {
            PixelCanvas canvas = new PixelCanvas(GunsmithUi.GUI_W, GunsmithUi.GUI_H);
            paintBackground(canvas);
            backgroundTexture = new DynamicTexture(canvas.toNativeImage());
            backgroundLocation = new ResourceLocation(MiningConstants.MODID,
                    "dynamic/gunsmith_ui_bg_" + style().id());
            textures.register(backgroundLocation, backgroundTexture);
        } else {
            // 资源重载等情况下贴图表被清过: 重新登记同一张 (DynamicTexture 本身仍持有像素)。
            AbstractTexture registered = textures.getTexture(backgroundLocation, null);
            if (registered != backgroundTexture) {
                textures.register(backgroundLocation, backgroundTexture);
            }
        }
        return backgroundLocation;
    }

    // ================================================================== chrome

    /** 标题栏动态部分 (文字、徽章、运行灯)。静态底色已烤进底图。 */
    public abstract void header(GsPainter p, Header h);

    /**
     * 右上角操作员块: 头像 + 名字 + 军火商等级 + 本级经验条。
     *
     * @param xp   本级经验进度 0..1 ({@link GunsmithUi#playerXpFraction()})
     * @param skin 玩家皮肤 (null 不画头像)
     */
    public abstract void owner(GsPainter p, int level, float xp, Component name, @Nullable ResourceLocation skin);

    /** 分区面板; title 为 null 时不画标题签。 */
    public abstract void panel(GsPainter p, int x, int y, int w, int h, @Nullable Component title);

    /** 面板内分隔线。 */
    public abstract void sep(GsPainter p, int x, int y, int w);

    /** 类别页签 (IDLE / HOVER / SEL / LOCK)。 */
    public abstract void tab(GsPainter p, int x, int y, int w, int h, Component label, State state);

    /** 卡片底 (口径、部件、风格按钮; IDLE / HOVER / SEL / LOCK)。 */
    public abstract void card(GsPainter p, int x, int y, int w, int h, State state);

    /** 卡片上文字色。 */
    public abstract int cardText(State state);

    /** 列表行底 (平台、型号、风格面板里的行)。 */
    public abstract void row(GsPainter p, int x, int y, int w, int h, State state);

    public abstract int rowText(State state);

    public abstract int rowSub(State state);

    /** 普通槽位框, (x, y) 是槽内 16x16 的左上角 (与 Slot.x/y 相同)。 */
    public abstract void slot(GsPainter p, int x, int y);

    /** 产出槽位框 (带醒目外框)。 */
    public abstract void slotOut(GsPainter p, int x, int y);

    /** 主按钮 (state 只取 IDLE / HOVER / OFF)。h &gt;= 18 用 0.8 字号, 否则 0.6。 */
    public abstract void btn(GsPainter p, int x, int y, int w, int h, Component label, ButtonKind kind, State state);

    /**
     * 分段开关 (如 单次 / 连续)。悬停取 p 的鼠标。
     *
     * @return 每段宽度 bw (命中区: 第 i 段从 x + i * (bw + gap) 起, gap 见各风格, 学园/蓝图 2, 工控 1)
     */
    public abstract int seg(GsPainter p, int x, int y, int w, int h, Component[] options, int selected);

    /** 分段开关两段之间的间距 (计算命中区用)。 */
    public abstract int segGap();

    /** 进度条; animate 为 true 时带流动效果 (运行中)。 */
    public abstract void bar(GsPainter p, int x, int y, int w, int h, float fraction, BarKind kind, boolean animate);

    /** 5x6 小锁图标。 */
    public abstract void lock(GsPainter p, int x, int y);

    /** 5x5 状态灯。 */
    public abstract void lamp(GsPainter p, int x, int y, LampState state);

    /**
     * 冲压机工位画 (液压机), 画在槽位与物品之下。ram 为冲头行程 0..1。
     *
     * @return 冲头底面的 y (预览 ramTop)
     */
    public abstract int stage(GsPainter p, int x, int y, int w, int h, boolean active, float ram);

    /** 冲压机前景 (压下来的冲头、火花), 画在工位物品之上 (z 300 前景层)。 */
    public abstract void stageFront(GsPainter p, int x, int y, int w, int h, boolean active, float ram);

    /** 口径展示区 (学园/工控贴现有弹药展示图, 蓝图画按尺寸的线稿)。预览调用 (100, 74, 162, 42)。 */
    public abstract void showcase(GsPainter p, MunitionsCaliber caliber, int x, int y, int w, int h);

    /** 品质文字色。 */
    public abstract int qColor(GunsmithPartQuality quality);

    /** 品质按钮底 (IDLE / HOVER / SEL / LOCK)。 */
    public abstract void qbtn(GsPainter p, int x, int y, int w, int h, GunsmithPartQuality quality, State state);

    /** 稀有度色标 (约 8x9)。 */
    public abstract void rarTag(GsPainter p, int x, int y, GunsmithPartRarity rarity);

    /** 稀有度文字色。 */
    public abstract int rarText(GunsmithPartRarity rarity);

    /** 模态对话框打开时盖在整个界面 (含物品) 上的遮罩色。 */
    public abstract int shade();

    /** 原版槽位悬停高亮色 (AbstractContainerScreen.getSlotColor)。浅色风格要换成看得见的颜色。 */
    public abstract int slotHighlight();

    /** 风格面板的底 (蓝图风格要先铺图纸 + 网格; 其余风格面板自己就是实心的, 什么都不画)。 */
    public void popBackground(GsPainter p, int x, int y, int w, int h) {
    }

    // ================================================================== shared art

    /** 预览 lockIcon: 5x6 锁, key 为锁孔色 (0 = 不画)。 */
    protected static void lockIcon(GsCanvas c, int x, int y, int color, int key) {
        c.rect(x + 1, y, 3, 1, color);
        c.rect(x, y + 1, 1, 2, color);
        c.rect(x + 4, y + 1, 1, 2, color);
        c.rect(x, y + 3, 5, 3, color);
        if (key != 0) {
            c.rect(x + 2, y + 4, 1, 1, key);
        }
    }

    private static final int SPARK_TAIL = GsCanvas.rgba(255, 216, 122, 0.7D);

    /** 预览 sparks: 冲压落锤时的火花, 两色交替。 */
    public static void sparks(GsCanvas c, int cx, int cy, long t, int color1, int color2) {
        long pulse = t / 90L;
        for (int i = 0; i < 7; i++) {
            int sx = cx - 18 + (int) Math.floorMod(pulse * 7L + i * 11L, 36L);
            int sy = cy - 3 + (int) Math.floorMod(pulse + i * 3L, 7L);
            c.rect(sx, sy, 2, 1, (i % 2) == 0 ? color1 : color2);
            c.rect(sx + 1, sy + 1, 1, 2, SPARK_TAIL);
        }
    }

    /** 共用: 0..1 夹取。 */
    protected static float clamp01(float v) {
        return Math.max(0.0F, Math.min(1.0F, v));
    }

    /** 共用: (t / period) 奇偶, 用于闪烁。 */
    protected static boolean blink(long t, long period) {
        return Math.floorMod(t / period, 2L) == 1L;
    }

    /** 预览里 text 的垂直居中: y + (h - 8 * scale) / 2。 */
    protected static float centerY(int y, int h, float scale) {
        return y + (h - 8.0F * scale) / 2.0F;
    }
}
