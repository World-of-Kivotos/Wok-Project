package com.miningdim.job.munitions.client.style;

import com.miningdim.job.munitions.GunsmithUiStyle;
import com.miningdim.job.munitions.MunitionsCaliber;
import com.miningdim.job.munitions.gunsmith.GunsmithPartQuality;
import com.miningdim.job.munitions.gunsmith.GunsmithPartRarity;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import static com.miningdim.job.munitions.client.style.GsPainter.BOLD;
import static com.miningdim.job.munitions.client.style.GsPainter.CENTER;
import static com.miningdim.job.munitions.client.style.GsPainter.RIGHT;

/**
 * 风格 C 蓝图工程 (预览对象 C_): 图纸蓝底 + 网格 + 图框, 标题栏是工程图标题栏, 弹药展示是按真实尺寸
 * 画的线稿 ({@link CartridgeDrawing}), 冲压台是带行程标注的线稿。
 */
final class BlueprintTheme extends GunsmithTheme {

    /** 线 (亮)。 */
    static final int LN = 0xFFD6E6FF;
    /** 线 (暗)。 */
    static final int LD = 0xFF7FA6D8;
    /** 图纸底色。 */
    static final int PAPER = 0xFF16427A;
    static final int YELLOW = 0xFFFFE27A;
    static final int GRID_MINOR = 0xFF1C4C88;
    static final int GRID_MAJOR = 0xFF275C9C;

    private static final int LOCK_LINE = 0xFF4F79B3;
    private static final int PANEL_FILL = GsCanvas.rgba(14, 50, 96, 0.62D);
    private static final int SEL_FILL = GsCanvas.rgba(255, 226, 122, 0.16D);
    private static final int CARD_SEL_FILL = GsCanvas.rgba(255, 226, 122, 0.14D);
    private static final int QBTN_SEL_FILL = GsCanvas.rgba(255, 226, 122, 0.12D);
    private static final int TAB_HOVER_FILL = GsCanvas.rgba(214, 230, 255, 0.12D);
    private static final int HOVER_FILL = GsCanvas.rgba(214, 230, 255, 0.1D);
    private static final int STOP_FILL = GsCanvas.rgba(255, 138, 128, 0.16D);
    private static final int RAM_FILL = GsCanvas.rgba(22, 66, 122, 0.9D);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd");
    /** 标题栏"等级"格: 档名起点、"L<n>" 的默认起点、格子右界 (234 起是风格按钮)。 */
    private static final int GRADE_X = 200;
    private static final int GRADE_LEVEL_X = 217;
    private static final int GRADE_RIGHT = 233;
    /** 档名按这个宽度缩字号 (= 默认等级起点前的空间)。 */
    private static final float GRADE_NAME_FIT_W = GRADE_LEVEL_X - GRADE_X - 1;

    BlueprintTheme() {
        super(new Palette(0xFFE8F1FF, 0xFF9DBBE3, 0xFF5F86BD, YELLOW, 0xFF8FF0B2, 0xFFFF8A80));
    }

    @Override
    public GunsmithUiStyle style() {
        return GunsmithUiStyle.BLUEPRINT;
    }

    @Override
    protected void paintBackground(GsCanvas c) {
        int gw = GunsmithUi.GUI_W;
        int gh = GunsmithUi.GUI_H;
        c.rect(0, 0, gw, gh, PAPER);
        for (int x = 0; x < gw; x += 6) {
            c.rect(x, 0, 1, gh, x % 30 == 0 ? GRID_MAJOR : GRID_MINOR);
        }
        for (int y = 0; y < gh; y += 6) {
            c.rect(0, y, gw, 1, y % 30 == 0 ? GRID_MAJOR : GRID_MINOR);
        }
        c.box(1, 1, gw - 2, gh - 2, LD);
        c.box(2, 2, gw - 4, gh - 4, LN);
        // 标题栏图框 (静态): 底、外框、三条分栏线。
        c.rect(3, 3, gw - 6, 24, PAPER);
        c.box(3, 3, gw - 6, 24, LN);
        for (int cx : new int[]{118, 196, 252}) {
            c.rect(cx, 3, 1, 24, LD);
        }
    }

    @Override
    public void header(GsPainter p, Header h) {
        p.text(Component.translatable("gui.miningdim.gunsmith_ui.blueprint.drawing_name"), 7, 6, LD, 0.5F, 0);
        p.text(h.title(), 7, 13, 0xFFE8F1FF, p.fitScale(h.title(), 51.0F, 1.1F, 0.55F, true), BOLD);
        p.text(h.sub(), 60, 16, 0xFF9DBBE3, p.fitScale(h.sub(), 56.0F, 0.5F, 0.35F, false), 0);
        p.text(Component.translatable("gui.miningdim.gunsmith_ui.blueprint.drawing_no"), 122, 6, LD, 0.5F, 0);
        p.text(h.code(), 122, 15, 0xFFE8F1FF, p.fitScale(h.code(), 70.0F, 0.62F, 0.45F, false), 0);
        if (h.badge() != null) {
            p.text(Component.translatable("gui.miningdim.gunsmith_ui.blueprint.grade"), 200, 6, LD, 0.5F, 0);
            // 等级格 x 200..233 (234 起是风格按钮): 档名缩到 0.4 仍比 16px 宽时, "L<n>" 往右让, 但不越过 233;
            // 还放不下 (更长的译名) 就截断档名, 不让两段字叠在一起。
            Component level = GunsmithUi.levelShort(h.level());
            float levelW = p.textWidth(level, 0.55F, false);
            Component name = h.badge().name();
            float nameScale = p.fitScale(name, GRADE_NAME_FIT_W, 0.72F, 0.4F, true);
            float nameMax = GRADE_RIGHT - levelW - 1.0F - GRADE_X;
            Component shownName = truncate(p, name, nameMax, nameScale);
            float nameW = p.textWidth(shownName, nameScale, true);
            p.text(shownName, GRADE_X, 14.5F, h.badge().color(), nameScale, BOLD);
            float levelX = Math.min(GRADE_RIGHT - levelW, Math.max(GRADE_LEVEL_X, GRADE_X + nameW + 1.0F));
            p.text(level, levelX, 15.5F, 0xFF9DBBE3, 0.55F, 0);
        } else {
            p.text(Component.translatable("gui.miningdim.gunsmith_ui.blueprint.date"), 200, 6, LD, 0.5F, 0);
            p.text(LocalDate.now().format(DATE), 200, 15.5F, 0xFFE8F1FF, 0.55F, 0);
        }
    }

    @Override
    public void owner(GsPainter p, int level, float xp, Component name, @Nullable ResourceLocation skin) {
        int x = 256;
        int y = 6;
        p.text(Component.translatable("gui.miningdim.gunsmith_ui.blueprint.drafter"), x, y, LD, 0.5F, 0);
        p.face(skin, x, y + 6, 12);
        p.text(name, x + 15, y + 7, 0xFFE8F1FF, p.fitScale(name, 62.0F, 0.62F, 0.4F, false), 0);
        p.text(GunsmithUi.levelShort(level), x + 96, y + 7, YELLOW, 0.62F, RIGHT | BOLD);
        p.rect(x + 15, y + 15, 81, 1, GRID_MINOR);
        p.rect(x + 15, y + 15, Math.round(81 * clamp01(xp)), 1, YELLOW);
    }

    @Override
    public void panel(GsPainter p, int x, int y, int w, int h, @Nullable Component title) {
        p.rect(x, y, w, h, PANEL_FILL);
        p.box(x, y, w, h, LD);
        p.corners(x, y, w, h, LN, 4);
        if (title != null) {
            float w2 = p.textWidth(title, 0.6F, true) + 6.0F;
            p.rectF(x + 4, y + 3, w2, 8, LN);
            p.text(title, x + 7, y + 3.8F, PAPER, 0.6F, BOLD);
        }
    }

    @Override
    public void sep(GsPainter p, int x, int y, int w) {
        // 虚线二十几段, 合成一次 draw。
        p.batch(() -> {
            for (int i = 0; i < w; i += 3) {
                p.rect(x + i, y, 2, 1, 0xFF2C5E9E);
            }
        });
    }

    /** 缩到 scale 仍超过 maxW 时截断并补省略号; 放得下原样返回。 */
    private static Component truncate(GsPainter p, Component text, float maxW, float scale) {
        if (p.textWidth(text, scale, true) <= maxW) {
            return text;
        }
        String full = text.getString();
        String ellipsis = "…";
        int end = full.length();
        while (end > 0 && p.textWidth(full.substring(0, end) + ellipsis, scale, true) > maxW) {
            end--;
        }
        return Component.literal(full.substring(0, end).trim() + ellipsis);
    }

    @Override
    public void tab(GsPainter p, int x, int y, int w, int h, Component label, State state) {
        switch (state) {
            case SEL -> {
                p.rect(x, y, w, h, SEL_FILL);
                p.box(x, y, w, h, YELLOW);
            }
            case HOVER -> {
                p.rect(x, y, w, h, TAB_HOVER_FILL);
                p.box(x, y, w, h, LN);
            }
            case LOCK, OFF -> p.dbox(x, y, w, h, LOCK_LINE);
            default -> p.box(x, y, w, h, LD);
        }
        int tc = switch (state) {
            case SEL -> YELLOW;
            case HOVER -> 0xFFFFFFFF;
            case LOCK, OFF -> 0xFF5F86BD;
            default -> LN;
        };
        p.text(label, x + w / 2.0F, centerY(y, h, 0.64F), tc, 0.64F, CENTER | (state == State.SEL ? BOLD : 0));
    }

    @Override
    public void card(GsPainter p, int x, int y, int w, int h, State state) {
        switch (state) {
            case SEL -> {
                p.rect(x, y, w, h, CARD_SEL_FILL);
                p.box(x, y, w, h, YELLOW);
                p.corners(x - 1, y - 1, w + 2, h + 2, YELLOW, 3);
            }
            case HOVER -> {
                p.rect(x, y, w, h, HOVER_FILL);
                p.box(x, y, w, h, LN);
            }
            case LOCK, OFF -> p.dbox(x, y, w, h, LOCK_LINE);
            default -> p.box(x, y, w, h, LD);
        }
    }

    @Override
    public int cardText(State state) {
        return switch (state) {
            case SEL -> YELLOW;
            case HOVER -> 0xFFFFFFFF;
            case LOCK, OFF -> 0xFF5F86BD;
            default -> LN;
        };
    }

    @Override
    public void row(GsPainter p, int x, int y, int w, int h, State state) {
        switch (state) {
            case SEL -> {
                p.rect(x, y, w, h, SEL_FILL);
                p.box(x, y, w, h, YELLOW);
            }
            case HOVER -> {
                p.rect(x, y, w, h, HOVER_FILL);
                p.box(x, y, w, h, LD);
            }
            case LOCK, OFF -> {
                // 预览: 锁定行不画底, 只靠文字变暗 ("划掉")。
            }
            default -> p.rect(x, y + h - 1, w, 1, 0xFF2C5E9E);
        }
    }

    @Override
    public int rowText(State state) {
        return switch (state) {
            case SEL -> YELLOW;
            case HOVER -> 0xFFFFFFFF;
            case LOCK, OFF -> LOCK_LINE;
            default -> LN;
        };
    }

    @Override
    public int rowSub(State state) {
        return state == State.SEL ? YELLOW : 0xFF5F86BD;
    }

    @Override
    public void slot(GsPainter p, int x, int y) {
        p.rect(x - 1, y - 1, 18, 18, 0xFF123A6C);
        p.box(x - 1, y - 1, 18, 18, LD);
        p.corners(x - 1, y - 1, 18, 18, LN, 3);
    }

    @Override
    public void slotOut(GsPainter p, int x, int y) {
        p.dbox(x - 5, y - 5, 26, 26, YELLOW);
        slot(p, x, y);
        p.text(Component.translatable("gui.miningdim.gunsmith_ui.blueprint.station"), x + 8, y - 11, YELLOW, 0.5F,
                CENTER);
    }

    @Override
    public void btn(GsPainter p, int x, int y, int w, int h, Component label, ButtonKind kind, State state) {
        float sc = h >= 18 ? 0.8F : 0.6F;
        float fit = p.fitScale(label, w - 6, sc, 0.4F, true);
        float ty = y + (h - 8.0F * sc) / 2.0F;
        if (state == State.OFF || state == State.LOCK) {
            p.dbox(x, y, w, h, 0xFF5F86BD);
            p.text(label, x + w / 2.0F, ty, 0xFF5F86BD, fit, CENTER | BOLD);
            return;
        }
        if (kind == ButtonKind.STOP) {
            p.rect(x, y, w, h, STOP_FILL);
            p.box(x, y, w, h, 0xFFFF8A80);
            p.text(label, x + w / 2.0F, ty, 0xFFFF8A80, fit, CENTER | BOLD);
            return;
        }
        int fill = state == State.HOVER ? YELLOW : kind == ButtonKind.ACC ? 0xFF9DBBE3 : LN;
        p.rect(x, y, w, h, fill);
        p.corners(x - 2, y - 2, w + 4, h + 4, fill, 3);
        p.text(label, x + w / 2.0F, ty, PAPER, fit, CENTER | BOLD);
    }

    @Override
    public int seg(GsPainter p, int x, int y, int w, int h, Component[] options, int selected) {
        int n = Math.max(1, options.length);
        int bw = (w - 2) / n;
        for (int i = 0; i < options.length; i++) {
            int bx = x + i * (bw + 2);
            boolean on = i == selected;
            boolean hv = p.hov(bx, y, bw, h);
            if (on) {
                p.rect(bx, y, bw, h, SEL_FILL);
                p.box(bx, y, bw, h, YELLOW);
            } else {
                p.box(bx, y, bw, h, hv ? LN : LD);
            }
            p.text(options[i], bx + bw / 2.0F, centerY(y, h, 0.6F), on ? YELLOW : LN,
                    p.fitScale(options[i], bw - 4, 0.6F, 0.4F, on), CENTER | (on ? BOLD : 0));
        }
        return bw;
    }

    @Override
    public int segGap() {
        return 2;
    }

    @Override
    public void bar(GsPainter p, int x, int y, int w, int h, float fraction, BarKind kind, boolean animate) {
        p.box(x, y, w, h, LD);
        int fw = Math.round((w - 2) * clamp01(fraction));
        int col = switch (kind) {
            case PROG -> YELLOW;
            case FE -> 0xFF8FF0B2;
            case BUF -> LN;
            case BAD -> 0xFFFF8A80;
        };
        int ph = animate ? (int) Math.floorMod(p.now() / 90L, 4L) : 0;
        // 预览逐像素 (c + r + 4 - ph) % 4 < 2 的斜纹 == stripes(period 4, on 2, phase 4 - ph)。
        p.batch(() -> p.stripes(x + 1, y + 1, fw, h - 2, col, 0, 4, 2, 4 - ph));
        if (fw > 0) {
            p.rect(x + fw, y + 1, 1, h - 2, col);
        }
    }

    @Override
    public void lock(GsPainter p, int x, int y) {
        lockIcon(p, x, y, 0xFFFF8A80, PAPER);
    }

    @Override
    public void lamp(GsPainter p, int x, int y, LampState state) {
        int col = switch (state) {
            case RUN -> blink(p.now(), 300L) ? YELLOW : 0xFF8C7A3A;
            case DONE -> 0xFF8FF0B2;
            case IDLE -> 0xFF5F86BD;
        };
        p.box(x - 1, y - 1, 7, 7, col);
        p.rect(x + 1, y + 1, 3, 3, col);
    }

    @Override
    public int stage(GsPainter p, int x, int y, int w, int h, boolean active, float ram) {
        int cx = x + w / 2;
        int drop = Math.round(ram * 7);
        int dx = x + w - 9;
        p.batch(() -> {
            p.rect(x, y, w, h, PANEL_FILL);
            p.box(x, y, w, h, LD);
            p.corners(x, y, w, h, LN, 4);
            p.rect(x + 5, y + h - 4, w - 10, 1, LD);
            p.box(x + 13, y + 6, 7, h - 10, LN);
            p.box(x + w - 20, y + 6, 7, h - 10, LN);
            p.box(x + 9, y + 5, w - 18, 8, LN);
            for (int c = 2; c < w - 20; c += 3) {
                p.rect(x + 9 + c, y + 7 + (c % 2), 1, 1, LD);
            }
            p.box(cx - 3, y + 13, 6, 6 + drop, LN);
            p.box(cx - 16, y + 19 + drop, 32, 8, LN);
            for (int c = 2; c < 30; c += 3) {
                for (int r = 1; r < 7; r++) {
                    if ((c + r) % 3 == 0) {
                        p.rect(cx - 16 + c, y + 19 + drop + r, 1, 1, LD);
                    }
                }
            }
            p.box(cx - 18, y + h - 7, 36, 3, LN);
            for (int yy = y + 27; yy < y + 34; yy += 2) {
                p.rect(dx, yy, 1, 1, YELLOW);
            }
            p.rect(dx - 1, y + 27, 3, 1, YELLOW);
            p.rect(dx - 1, y + 34, 3, 1, YELLOW);
        });
        // 行程标注 "7" 是冲头的像素行程 (ram * 7), 属于数据不是文案。
        p.text("7", dx + 3, y + 28.5F, YELLOW, 0.5F, 0);
        p.text(Component.translatable("gui.miningdim.gunsmith_ui.blueprint.stroke"), x + 4, y + 3.5F, LD, 0.5F, 0);
        return y + 19 + drop;
    }

    @Override
    public void stageFront(GsPainter p, int x, int y, int w, int h, boolean active, float ram) {
        int cx = x + w / 2;
        int drop = Math.round(ram * 7);
        if (active && ram > 0.3F) {
            p.rect(cx - 16, y + 19 + drop, 32, 8, RAM_FILL);
            p.box(cx - 16, y + 19 + drop, 32, 8, LN);
        }
        if (active) {
            int a = (int) Math.floorMod(p.now() / 120L, 4L);
            for (int i = 0; i < 3; i++) {
                p.rect(cx + 19, y + 18 + ((i * 4 + a) % 12), 1, 2, YELLOW);
            }
        }
    }

    @Override
    public void showcase(GsPainter p, MunitionsCaliber caliber, int x, int y, int w, int h) {
        CartridgeDrawing.draw(p, caliber, x, y, w, h);
    }

    @Override
    public int qColor(GunsmithPartQuality quality) {
        return GunsmithUi.qualityColor(quality);
    }

    @Override
    public void qbtn(GsPainter p, int x, int y, int w, int h, GunsmithPartQuality quality, State state) {
        int qc = GunsmithUi.qualityColor(quality);
        boolean locked = state == State.LOCK || state == State.OFF;
        if (state == State.SEL) {
            p.rect(x, y, w, h, QBTN_SEL_FILL);
            p.box(x, y, w, h, qc);
            p.corners(x - 1, y - 1, w + 2, h + 2, YELLOW, 3);
        } else if (locked) {
            p.dbox(x, y, w, h, LOCK_LINE);
        } else {
            p.box(x, y, w, h, state == State.HOVER ? LN : LD);
        }
        if (!locked) {
            p.rect(x + 3, y + h - 3, w - 6, 1, qc);
        }
    }

    @Override
    public void rarTag(GsPainter p, int x, int y, GunsmithPartRarity rarity) {
        int light = GunsmithUi.rarityLight(rarity);
        p.box(x, y, 7, 9, light);
        int bars = GunsmithUi.rarityBars(rarity);
        for (int i = 0; i < bars; i++) {
            p.rect(x + 1 + i, y + 7 - i, 1, 1, light);
        }
    }

    @Override
    public int rarText(GunsmithPartRarity rarity) {
        return GunsmithUi.rarityLight(rarity);
    }

    @Override
    public int shade() {
        return GsCanvas.rgba(8, 24, 48, 0.7D);
    }

    @Override
    public int slotHighlight() {
        return GsCanvas.rgba(255, 255, 255, 0.45D);
    }

    @Override
    public void popBackground(GsPainter p, int x, int y, int w, int h) {
        p.batch(() -> {
            p.rect(x, y, w, h, PAPER);
            for (int gx = ceilTo6(x); gx < x + w; gx += 6) {
                p.rect(gx, y, 1, h, gx % 30 == 0 ? GRID_MAJOR : GRID_MINOR);
            }
            for (int gy = ceilTo6(y); gy < y + h; gy += 6) {
                p.rect(x, gy, w, 1, gy % 30 == 0 ? GRID_MAJOR : GRID_MINOR);
            }
        });
    }

    private static int ceilTo6(int v) {
        return Math.floorDiv(v + 5, 6) * 6;
    }

    private static final AssemblyColors ASSEMBLY = new AssemblyColors(YELLOW, GsCanvas.rgba(255, 226, 122, 0.5D),
            YELLOW, LN, 0xFF8FF0B2, 0xFF2C5E9E, 0xFFFF8A80, 0xFF0E3262);
    private static final int CENTERLINE = GsCanvas.rgba(214, 230, 255, 0.22D);
    private static final int OUTLINE_FILL = 0x1D5496;

    @Override
    public void gunDisplay(GsPainter p, int x, int y, int w, int h) {
        p.batch(() -> {
            p.rect(x, y, w, h, PANEL_FILL);
            p.box(x, y, w, h, LD);
            p.corners(x, y, w, h, LN, 4);
            int cy = y + h / 2;
            for (int i = x + 5; i < x + w - 5; i += 8) {
                p.rect(i, cy, 4, 1, CENTERLINE);
                p.rect(i + 5, cy, 1, 1, CENTERLINE);
            }
        });
    }

    @Override
    public void gunSilhouette(GsPainter p, ResourceLocation hud, int x, int y, int w, int h, int u, int uW, float alpha) {
        // 描边: 亮线色的剪影上下左右各错 1 个屏幕像素贴一次, 再在正中贴一层图纸蓝盖住内部, 只剩一圈轮廓。
        float o = GsPainter.screenPixel();
        tintedHud(p, hud, x - o, y, w, h, u, uW, LN & 0xFFFFFF, alpha);
        tintedHud(p, hud, x + o, y, w, h, u, uW, LN & 0xFFFFFF, alpha);
        tintedHud(p, hud, x, y - o, w, h, u, uW, LN & 0xFFFFFF, alpha);
        tintedHud(p, hud, x, y + o, w, h, u, uW, LN & 0xFFFFFF, alpha);
        tintedHud(p, hud, x, y, w, h, u, uW, OUTLINE_FILL, alpha);
    }

    @Override
    public int wire(@Nullable GunsmithPartQuality quality, boolean ok) {
        return ok ? YELLOW : LD;
    }

    @Override
    public AssemblyColors assembly() {
        return ASSEMBLY;
    }
}
