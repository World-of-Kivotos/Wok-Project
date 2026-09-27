package com.miningdim.job.munitions.client.style;

import com.miningdim.job.munitions.GunsmithUiStyle;
import com.miningdim.job.munitions.MunitionsClientConfig;
import net.minecraft.network.chat.Component;

import java.util.List;

import static com.miningdim.job.munitions.client.style.GsPainter.BOLD;
import static com.miningdim.job.munitions.client.style.GsPainter.RIGHT;

/**
 * 界面右上角的风格切换 (预览 styleButton / stylePopover / styleThumb): 标题栏里一个 15x15 的三色小按钮,
 * 点开是一块列出三种风格的面板。选中某一行立刻写入客户端配置并保存、立刻生效, 面板保持打开方便来回比较;
 * 点面板里的空白处什么也不做, 点面板外面关掉。两台机器共用同一个选择。
 *
 * 本类只管绘制和"这次点击落在哪"; 浮层期间的输入吞吐规则 (吞点击/松开/滚轮/按键、Esc 只关浮层、
 * 下层失焦) 在 {@link GunsmithStyledScreen} 里统一处理。所有坐标都是 GUI 相对坐标。
 */
public final class StylePicker {

    public static final int BTN_X = 234;
    public static final int BTN_Y = 7;
    public static final int BTN_W = 15;
    public static final int BTN_H = 15;

    public static final int POP_X = 196;
    public static final int POP_Y = 25;
    public static final int POP_W = 156;
    public static final int POP_H = 78;

    private static final int ROW_X = POP_X + 5;
    private static final int ROW_Y = POP_Y + 17;
    private static final int ROW_W = POP_W - 10;
    private static final int ROW_H = 17;
    private static final int ROW_STEP = 19;

    private static final int BTN_SHADOW = GsCanvas.rgba(0, 0, 0, 0.35D);
    private static final int POP_SHADOW = GsCanvas.rgba(0, 0, 0, 0.35D);

    private boolean open;

    public boolean isOpen() {
        return open;
    }

    public void open() {
        open = true;
    }

    public void close() {
        open = false;
    }

    public static boolean overButton(double relX, double relY) {
        return GunsmithUi.inRect(relX, relY, BTN_X, BTN_Y, BTN_W, BTN_H);
    }

    public static boolean overPanel(double relX, double relY) {
        return GunsmithUi.inRect(relX, relY, POP_X, POP_Y, POP_W, POP_H);
    }

    /** 该 GUI 坐标落在第几行风格上, 不在任何一行上返回 null。 */
    public static GunsmithUiStyle styleAt(double relX, double relY) {
        GunsmithUiStyle[] styles = GunsmithUiStyle.values();
        for (int i = 0; i < styles.length; i++) {
            if (GunsmithUi.inRect(relX, relY, ROW_X, ROW_Y + i * ROW_STEP, ROW_W, ROW_H)) {
                return styles[i];
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ input

    /**
     * 处理一次点击。浮层打开时一律返回 true (整次点击被浮层吃掉):
     * 左键点在未选中的行上 -> 切换并保存 (保持打开); 点在面板内其它地方 -> 什么也不做; 点在面板外 -> 关闭。
     * 浮层关闭时: 左键点在按钮上 -> 打开并返回 true, 否则返回 false (交给界面自己处理)。
     */
    public boolean mouseClicked(double relX, double relY, int button) {
        if (open) {
            if (!overPanel(relX, relY)) {
                open = false;
                return true;
            }
            if (button == 0) {
                GunsmithUiStyle picked = styleAt(relX, relY);
                if (picked != null && picked != MunitionsClientConfig.gunsmithUiStyle()) {
                    MunitionsClientConfig.setGunsmithUiStyle(picked);
                }
            }
            return true;
        }
        if (button == 0 && overButton(relX, relY)) {
            open = true;
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ render

    /** 标题栏里的三色按钮 (主层, 随界面一起画)。 */
    public void renderButton(GsPainter p, GunsmithTheme theme) {
        GunsmithTheme.State state = open ? GunsmithTheme.State.SEL
                : p.hov(BTN_X, BTN_Y, BTN_W, BTN_H) ? GunsmithTheme.State.HOVER : GunsmithTheme.State.IDLE;
        theme.card(p, BTN_X, BTN_Y, BTN_W, BTN_H, state);
        p.rect(BTN_X + 3, BTN_Y + 4, 3, 7, 0xFF2EA8F2);
        p.rect(BTN_X + 6, BTN_Y + 4, 3, 7, 0xFFF0A33C);
        p.rect(BTN_X + 9, BTN_Y + 4, 3, 7, 0xFF16427A);
        p.box(BTN_X + 9, BTN_Y + 4, 3, 7, 0xFFD6E6FF);
        p.rect(BTN_X + 3, BTN_Y + 11, 9, 1, BTN_SHADOW);
    }

    /** 按钮的悬停提示 (三行: 当前风格 / 点击换一种 / 只改你自己看到的样子)。 */
    public static List<Component> buttonTooltip() {
        GunsmithUiStyle current = MunitionsClientConfig.gunsmithUiStyle();
        return List.of(
                GunsmithUi.tip(Component.translatable("gui.miningdim.gunsmith_ui.picker.tooltip.current",
                        Component.translatable(current.nameKey())), GunsmithUi.TIP_TITLE),
                GunsmithUi.tip(Component.translatable("gui.miningdim.gunsmith_ui.picker.tooltip.click"),
                        GunsmithUi.TIP_GRAY),
                GunsmithUi.tip(Component.translatable("gui.miningdim.gunsmith_ui.picker.tooltip.scope"),
                        GunsmithUi.TIP_GRAY));
    }

    /**
     * 风格面板 (浮层)。调用方负责把它画在物品之上 (z 350) 并传入真实鼠标的画笔。
     */
    public void renderPopover(GsPainter p, GunsmithTheme theme) {
        int x = POP_X;
        int y = POP_Y;
        int w = POP_W;
        int h = POP_H;
        p.rect(x + 2, y + 3, w, h, POP_SHADOW);
        theme.popBackground(p, x, y, w, h);
        theme.panel(p, x, y, w, h, null);
        p.text(Component.translatable("gui.miningdim.gunsmith_ui.picker.title"), x + 8, y + 6, theme.c.text(),
                0.7F, BOLD);
        Component hint = Component.translatable("gui.miningdim.gunsmith_ui.picker.hint");
        p.text(hint, x + w - 8, y + 7, theme.c.dim(), p.fitScale(hint, 80.0F, 0.5F, 0.35F, false), RIGHT);
        GunsmithUiStyle current = MunitionsClientConfig.gunsmithUiStyle();
        GunsmithUiStyle[] styles = GunsmithUiStyle.values();
        for (int i = 0; i < styles.length; i++) {
            GunsmithUiStyle style = styles[i];
            int rx = ROW_X;
            int ry = ROW_Y + i * ROW_STEP;
            boolean sel = style == current;
            GunsmithTheme.State state = sel ? GunsmithTheme.State.SEL
                    : p.hov(rx, ry, ROW_W, ROW_H) ? GunsmithTheme.State.HOVER : GunsmithTheme.State.IDLE;
            theme.row(p, rx, ry, ROW_W, ROW_H, state);
            thumb(p, style, rx + 4, ry + 2);
            Component name = Component.translatable(style.nameKey());
            Component desc = Component.translatable(style.descriptionKey());
            float textMax = sel ? ROW_W - 31 - 24 : ROW_W - 31 - 4;
            p.text(name, rx + 31, ry + 2.5F, theme.rowText(state), p.fitScale(name, textMax, 0.66F, 0.4F, sel),
                    sel ? BOLD : 0);
            p.text(desc, rx + 31, ry + 10.5F, theme.rowSub(state), p.fitScale(desc, textMax, 0.48F, 0.35F, false), 0);
            if (sel) {
                p.text(Component.translatable("gui.miningdim.gunsmith_ui.picker.in_use"), rx + ROW_W - 5, ry + 5.5F,
                        theme.rowText(state), 0.52F, RIGHT);
            }
        }
    }

    /** 预览 styleThumb: 22x14 的风格缩略图。 */
    public static void thumb(GsCanvas c, GunsmithUiStyle style, int x, int y) {
        switch (style) {
            case ACADEMY -> {
                c.rect(x, y, 22, 14, 0xFFAEBACB);
                c.rect(x + 1, y + 1, 20, 12, 0xFFEEF2F7);
                c.rect(x + 1, y + 1, 20, 3, 0xFFFFFFFF);
                c.rect(x + 1, y + 1, 8, 3, 0xFF1E2B4A);
                c.rect(x + 3, y + 6, 7, 6, 0xFFFFFFFF);
                c.rect(x + 3, y + 6, 7, 1, 0xFF2EA8F2);
                c.rect(x + 12, y + 6, 8, 6, 0xFFFFFFFF);
                c.rect(x + 13, y + 9, 6, 2, 0xFFFFD94A);
            }
            case INDUSTRIAL -> {
                c.rect(x, y, 22, 14, 0xFF07090B);
                c.rect(x + 1, y + 1, 20, 12, 0xFF1B1F25);
                c.rect(x + 1, y + 1, 20, 3, 0xFF2A3038);
                c.rect(x + 1, y + 1, 3, 3, 0xFFF0A33C);
                c.rect(x + 3, y + 6, 7, 6, 0xFF101317);
                c.rect(x + 4, y + 7, 5, 1, 0xFFF0A33C);
                c.rect(x + 12, y + 6, 8, 6, 0xFF101317);
                c.rect(x + 13, y + 9, 6, 2, 0xFF2FA35A);
            }
            case BLUEPRINT -> {
                c.rect(x, y, 22, 14, 0xFF16427A);
                for (int i = 4; i < 22; i += 4) {
                    c.rect(x + i, y, 1, 14, 0xFF1F5190);
                }
                c.box(x, y, 22, 14, 0xFFD6E6FF);
                c.box(x + 3, y + 5, 7, 7, 0xFF7FA6D8);
                c.box(x + 12, y + 5, 8, 7, 0xFF7FA6D8);
                c.rect(x + 13, y + 9, 6, 1, 0xFFFFE27A);
            }
        }
    }
}
