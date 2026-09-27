package com.miningdim.job.munitions.client.style;

import com.miningdim.job.munitions.GunsmithUiStyle;
import com.miningdim.job.munitions.MunitionsCaliber;
import com.miningdim.job.munitions.gunsmith.GunsmithPartQuality;
import com.miningdim.job.munitions.gunsmith.GunsmithPartRarity;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;

import static com.miningdim.job.munitions.client.style.GsPainter.BOLD;
import static com.miningdim.job.munitions.client.style.GsPainter.CENTER;
import static com.miningdim.job.munitions.client.style.GsPainter.RIGHT;

/**
 * 风格 A 学园终端 (预览对象 A): 白底卡片 + 14° 斜切页签, 选中天蓝、主按钮黄色,
 * 与已定稿的学园标签铭牌同一套语言。
 */
final class AcademyTheme extends GunsmithTheme {

    private static final int NAVY = 0xFF1E2B4A;
    private static final int SKY = 0xFF2EA8F2;
    private static final int SKY_DEEP = 0xFF1A86D0;
    private static final int INK = 0xFF22304F;
    private static final int LINE = 0xFFD3DCE8;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GLOSS = GsCanvas.rgba(255, 255, 255, 0.45D);
    private static final int FLOW = GsCanvas.rgba(255, 255, 255, 0.35D);

    AcademyTheme() {
        super(new Palette(0xFF22304F, 0xFF6B7890, 0xFFA3AEC0, 0xFF1A86D0, 0xFF1F9E57, 0xFFE0393F));
    }

    @Override
    public GunsmithUiStyle style() {
        return GunsmithUiStyle.ACADEMY;
    }

    @Override
    protected void paintBackground(GsCanvas c) {
        int gw = GunsmithUi.GUI_W;
        int gh = GunsmithUi.GUI_H;
        c.rect(0, 0, gw, gh, 0xFFAEBACB);
        c.rect(1, 1, gw - 2, gh - 2, 0xFFEEF2F7);
        for (int k = -gh; k < gw; k += 9) {
            for (int r = 30; r < gh - 1; r++) {
                int x = k + (gh - r);
                if (x > 1 && x < gw - 1) {
                    c.rect(x, r, 1, 1, 0xFFE7ECF3);
                }
            }
        }
        for (int i = 0; i < 26; i++) {
            c.rect(gw - 2 - i, gh - 2 - (26 - i), 1, 26 - i, 0xFFE2EEF9);
        }
        // 标题栏静态底色 (预览 header 的前四笔)。
        c.rect(1, 1, gw - 2, 27, WHITE);
        c.rect(1, 28, gw - 2, 1, LINE);
        c.paraR(1, 1, 94, 27, NAVY);
        c.para(97, 1, 3, 27, SKY);
    }

    @Override
    public void header(GsPainter p, Header h) {
        float titleScale = p.fitScale(h.title(), 84.0F, 1.25F, 0.6F, true);
        p.text(h.title(), 9, 5, WHITE, titleScale, BOLD);
        p.text(h.en(), 9, 19, 0xFF7FA3D6, p.fitScale(h.en(), 84.0F, 0.5F, 0.35F, false), 0);
        int x = 116;
        if (h.badge() != null) {
            p.paraFit(x, 8, 30, 12, h.badge().color());
            p.text(h.badge().name(), x + 15, 10.5F, WHITE, p.fitScale(h.badge().name(), 24.0F, 0.72F, 0.4F, true),
                    CENTER | BOLD);
            x += 36;
        }
        p.text(h.sub(), x, 11, 0xFF6B7890, p.fitScale(h.sub(), 230 - x, 0.62F, 0.4F, false), 0);
    }

    @Override
    public void owner(GsPainter p, int level, float xp, Component name, @Nullable ResourceLocation skin) {
        int x = 257;
        int y = 6;
        p.rect(x, y, 95, 17, LINE);
        p.rect(x + 1, y + 1, 93, 15, 0xFFF4F7FB);
        p.face(skin, x + 3, y + 2, 13);
        p.text(name, x + 20, y + 3, INK, p.fitScale(name, 40.0F, 0.62F, 0.4F, true), BOLD);
        p.text(Component.translatable("gui.miningdim.gunsmith_ui.owner.level"), x + 62, y + 3.5F, SKY_DEEP, 0.55F, BOLD);
        p.text(String.valueOf(level), x + 90, y + 2.5F, INK, 0.7F, BOLD | RIGHT);
        p.rect(x + 20, y + 11, 70, 2, 0xFFDCE3EC);
        p.rect(x + 20, y + 11, Math.round(70 * clamp01(xp)), 2, SKY);
    }

    @Override
    public void panel(GsPainter p, int x, int y, int w, int h, @Nullable Component title) {
        p.rect(x + 1, y + h, w, 1, 0xFFD5DDE8);
        p.rect(x, y, w, h, LINE);
        p.rect(x + 1, y + 1, w - 2, h - 2, WHITE);
        if (title != null) {
            p.para(x + 5, y + 4, 3, 7, SKY);
            p.text(title, x + 12, y + 4.5F, INK, 0.64F, BOLD);
        }
    }

    @Override
    public void sep(GsPainter p, int x, int y, int w) {
        p.rect(x, y, w, 1, 0xFFEDF1F6);
    }

    @Override
    public void tab(GsPainter p, int x, int y, int w, int h, Component label, State state) {
        int fill = switch (state) {
            case SEL -> SKY;
            case HOVER -> 0xFFD6EEFC;
            case LOCK, OFF -> 0xFFF1F3F7;
            default -> 0xFFE8EDF4;
        };
        p.paraFit(x, y, w, h, fill);
        if (state == State.SEL) {
            p.rect(x, y + h - 1, w - GsCanvas.slant(h), 1, SKY_DEEP);
        }
        int tc = switch (state) {
            case SEL -> WHITE;
            case HOVER -> NAVY;
            case LOCK, OFF -> 0xFFA3AEC0;
            default -> 0xFF4A5872;
        };
        p.text(label, x + w / 2.0F, centerY(y, h, 0.64F), tc, 0.64F, CENTER | (state == State.SEL ? BOLD : 0));
    }

    @Override
    public void card(GsPainter p, int x, int y, int w, int h, State state) {
        switch (state) {
            case SEL -> {
                p.rect(x, y, w, h, SKY);
                p.rect(x + 1, y + 1, w - 2, h - 2, 0xFFEAF6FE);
                p.rect(x + 1, y + h - 3, w - 2, 2, SKY);
            }
            case HOVER -> {
                p.rect(x, y, w, h, 0xFF9FD4F7);
                p.rect(x + 1, y + 1, w - 2, h - 2, 0xFFF6FBFF);
            }
            case LOCK, OFF -> {
                p.rect(x, y, w, h, 0xFFE1E6ED);
                p.rect(x + 1, y + 1, w - 2, h - 2, 0xFFF3F5F8);
            }
            default -> {
                p.rect(x, y, w, h, LINE);
                p.rect(x + 1, y + 1, w - 2, h - 2, WHITE);
            }
        }
    }

    @Override
    public int cardText(State state) {
        return switch (state) {
            case SEL -> 0xFF1A6FB0;
            case HOVER -> NAVY;
            case LOCK, OFF -> 0xFFA3AEC0;
            default -> INK;
        };
    }

    @Override
    public void row(GsPainter p, int x, int y, int w, int h, State state) {
        int fill = switch (state) {
            case SEL -> SKY;
            case HOVER -> 0xFFDDF1FD;
            case LOCK, OFF -> 0xFFF6F7F9;
            default -> 0xFFF3F6FA;
        };
        p.paraFit(x, y, w, h, fill);
    }

    @Override
    public int rowText(State state) {
        return switch (state) {
            case SEL -> WHITE;
            case HOVER -> NAVY;
            case LOCK, OFF -> 0xFFB1BACA;
            default -> INK;
        };
    }

    @Override
    public int rowSub(State state) {
        return state == State.SEL ? 0xFFDDF1FD : 0xFFA3AEC0;
    }

    @Override
    public void slot(GsPainter p, int x, int y) {
        p.rect(x - 1, y - 1, 18, 18, WHITE);
        p.rect(x - 1, y - 1, 17, 17, 0xFF9AA7BA);
        p.rect(x, y, 16, 16, 0xFFE4E9F0);
    }

    @Override
    public void slotOut(GsPainter p, int x, int y) {
        p.rect(x - 4, y - 4, 24, 24, SKY);
        p.rect(x - 3, y - 3, 22, 22, 0xFFEAF6FE);
        slot(p, x, y);
    }

    @Override
    public void btn(GsPainter p, int x, int y, int w, int h, Component label, ButtonKind kind, State state) {
        float sc = h >= 18 ? 0.8F : 0.6F;
        float ty = y + (h - 2 - 8.0F * sc) / 2.0F;
        float fit = p.fitScale(label, w - 6, sc, 0.4F, true);
        if (state == State.OFF || state == State.LOCK) {
            p.paraFit(x, y + 2, w, h - 2, 0xFFC9D2DE);
            p.paraFit(x, y, w, h - 2, 0xFFE3E8EF);
            p.text(label, x + w / 2.0F, ty, 0xFF9AA6B8, fit, CENTER | BOLD);
            return;
        }
        int[] pal = switch (kind) {
            case GO -> new int[]{0xFFFFD94A, 0xFFFFE57A, 0xFFD9AE1F, NAVY};
            case STOP -> new int[]{0xFFE5484D, 0xFFEE6A6E, 0xFFB8343A, WHITE};
            case ACC -> new int[]{SKY, 0xFF55BAF5, SKY_DEEP, WHITE};
        };
        p.paraFit(x, y + 2, w, h - 2, pal[2]);
        p.paraFit(x, y, w, h - 2, state == State.HOVER ? pal[1] : pal[0]);
        p.text(label, x + w / 2.0F, ty, pal[3], fit, CENTER | BOLD);
    }

    @Override
    public int seg(GsPainter p, int x, int y, int w, int h, Component[] options, int selected) {
        int n = Math.max(1, options.length);
        int bw = (w - 2) / n;
        for (int i = 0; i < options.length; i++) {
            int bx = x + i * (bw + 2);
            boolean on = i == selected;
            boolean hv = p.hov(bx, y, bw, h);
            p.paraFit(bx, y, bw, h, on ? NAVY : hv ? 0xFFDDF1FD : 0xFFE8EDF4);
            p.text(options[i], bx + bw / 2.0F, centerY(y, h, 0.6F), on ? WHITE : 0xFF4A5872,
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
        p.rect(x, y, w, h, 0xFFE1E7EF);
        int fw = Math.round(w * clamp01(fraction));
        int col = switch (kind) {
            case PROG -> SKY;
            case FE -> 0xFF35B86B;
            case BUF -> 0xFFF2A53C;
            case BAD -> 0xFFE5484D;
        };
        p.rect(x, y, fw, h, col);
        p.rect(x, y, fw, 1, GLOSS);
        if (animate && fw > 2) {
            int ph = (int) Math.floorMod(p.now() / 60L, 8L);
            p.batch(() -> {
                for (int i = -8; i < fw; i += 8) {
                    for (int r = 0; r < h; r++) {
                        int xx = x + i + ph + (h - 1 - r);
                        if (xx >= x && xx + 2 <= x + fw) {
                            p.rect(xx, y + r, 2, 1, FLOW);
                        }
                    }
                }
            });
        }
    }

    @Override
    public void lock(GsPainter p, int x, int y) {
        lockIcon(p, x, y, 0xFFA3AEC0, 0xFFF3F5F8);
    }

    @Override
    public void lamp(GsPainter p, int x, int y, LampState state) {
        int col = switch (state) {
            case RUN -> blink(p.now(), 400L) ? SKY : 0xFF8FD2FA;
            case DONE -> 0xFF35B86B;
            case IDLE -> 0xFFC3CCD9;
        };
        p.rect(x, y + 1, 5, 3, col);
        p.rect(x + 1, y, 3, 5, col);
    }

    @Override
    public int stage(GsPainter p, int x, int y, int w, int h, boolean active, float ram) {
        p.rect(x, y, w, h, LINE);
        p.rect(x + 1, y + 1, w - 2, h - 2, 0xFFF7F9FC);
        int cx = x + w / 2;
        int drop = Math.round(ram * 7);
        p.rect(x + 6, y + h - 4, w - 12, 2, 0xFFC9D2DE);
        p.rect(x + 13, y + 6, 7, h - 10, 0xFFC3CCD9);
        p.rect(x + 14, y + 6, 2, h - 10, 0xFFE3E8EF);
        p.rect(x + w - 20, y + 6, 7, h - 10, 0xFFC3CCD9);
        p.rect(x + w - 19, y + 6, 2, h - 10, 0xFFE3E8EF);
        p.rect(x + 9, y + 5, w - 18, 8, NAVY);
        p.rect(x + 13, y + 9, w - 26, 1, active ? (blink(p.now(), 200L) ? SKY : 0xFF8FD2FA) : 0xFF3A5A8C);
        p.rect(cx - 3, y + 13, 6, 6 + drop, 0xFF9AA7BA);
        p.rect(cx - 2, y + 13, 1, 6 + drop, 0xFFDDE3EB);
        ramHead(p, cx, y + 19 + drop);
        p.rect(cx - 18, y + h - 7, 36, 3, 0xFFAEBACB);
        p.rect(cx - 18, y + h - 7, 36, 1, LINE);
        return y + 19 + drop;
    }

    private static void ramHead(GsPainter p, int cx, int top) {
        p.rect(cx - 16, top, 32, 7, 0xFF2C3B5E);
        p.rect(cx - 16, top, 32, 1, 0xFF5C77A8);
        p.rect(cx - 12, top + 7, 24, 2, NAVY);
    }

    @Override
    public void stageFront(GsPainter p, int x, int y, int w, int h, boolean active, float ram) {
        int cx = x + w / 2;
        int drop = Math.round(ram * 7);
        if (active && ram > 0.3F) {
            ramHead(p, cx, y + 19 + drop);
        }
        if (active && ram > 0.9F) {
            sparks(p, cx, y + h - 8, p.now(), 0xFFFFD94A, SKY);
        }
    }

    @Override
    public void showcase(GsPainter p, MunitionsCaliber caliber, int x, int y, int w, int h) {
        p.paraFit(x + 26, y + 5, w - 52, 34, 0xFFEEF7FE);
        blitAmmoProfile(p, caliber, x + 4, y + 2);
    }

    /** A/B 共用: 贴现有弹药展示图里该口径那一行 (154x40)。 */
    static void blitAmmoProfile(GsPainter p, MunitionsCaliber caliber, int x, int y) {
        int row = GunsmithUi.ammoProfileRow(caliber);
        p.blit(GunsmithUi.AMMO_PROFILES, x, y, GunsmithUi.AMMO_PROFILE_W, GunsmithUi.AMMO_PROFILE_H,
                0.0F, row * GunsmithUi.AMMO_PROFILE_SRC_H,
                GunsmithUi.AMMO_PROFILE_SRC_W, GunsmithUi.AMMO_PROFILE_SRC_H,
                GunsmithUi.AMMO_PROFILE_SRC_W, GunsmithUi.AMMO_PROFILE_SRC_H * GunsmithUi.AMMO_PROFILE_ROWS);
    }

    @Override
    public int qColor(GunsmithPartQuality quality) {
        return GunsmithUi.qualityColorLight(quality);
    }

    @Override
    public void qbtn(GsPainter p, int x, int y, int w, int h, GunsmithPartQuality quality, State state) {
        int ql = GunsmithUi.qualityColorLight(quality);
        boolean sel = state == State.SEL;
        boolean locked = state == State.LOCK || state == State.OFF;
        int fill = switch (state) {
            case SEL -> WHITE;
            case HOVER -> 0xFFEEF7FE;
            case LOCK, OFF -> 0xFFF1F3F7;
            default -> 0xFFF6F8FB;
        };
        p.rect(x, y, w, h, sel ? ql : LINE);
        p.rect(x + 1, y + 1, w - 2, h - 2, fill);
        p.rect(x + 1, y + h - (sel ? 3 : 2), w - 2, sel ? 2 : 1, locked ? LINE : ql);
    }

    @Override
    public void rarTag(GsPainter p, int x, int y, GunsmithPartRarity rarity) {
        p.paraFit(x, y, 8, 9, GunsmithUi.rarityMain(rarity));
        int bars = GunsmithUi.rarityBars(rarity);
        for (int i = 0; i < bars; i++) {
            p.rect(x + 2 + i, y + 6 - i, 1, 1, WHITE);
        }
    }

    @Override
    public int rarText(GunsmithPartRarity rarity) {
        return GunsmithUi.rarityMain(rarity);
    }

    @Override
    public int shade() {
        return GsCanvas.rgba(20, 28, 44, 0.55D);
    }

    @Override
    public int slotHighlight() {
        // 浅色槽底上原版的白色半透明高亮几乎看不见: 换成主题天蓝。
        return GsCanvas.rgba(46, 168, 242, 0.38D);
    }

    private static final AssemblyColors ASSEMBLY = new AssemblyColors(SKY_DEEP, GsCanvas.rgba(46, 168, 242, 0.55D),
            0xFFFFD94A, SKY, 0xFF35B86B, 0xFFE1E7EF, 0xFFE5484D, 0xFFC9D2DE);

    @Override
    public void gunDisplay(GsPainter p, int x, int y, int w, int h) {
        p.batch(() -> {
            p.rect(x, y, w, h, LINE);
            p.rect(x + 1, y + 1, w - 2, h - 2, 0xFFF7F9FC);
            for (int gx = x + 6; gx < x + w - 1; gx += 6) {
                p.rect(gx, y + 1, 1, h - 2, 0xFFEEF2F7);
            }
            for (int gy = y + 6; gy < y + h - 1; gy += 6) {
                p.rect(x + 1, gy, w - 2, 1, 0xFFEEF2F7);
            }
            p.para(x + 4, y + h - 7, 3, 4, SKY);
        });
    }

    @Override
    public void gunSilhouette(GsPainter p, ResourceLocation hud, int x, int y, int w, int h, int u, int uW, float alpha) {
        // 浅底上灰色剪影看不清: 染成深蓝。
        tintedHud(p, hud, x, y, w, h, u, uW, 0x34446C, alpha);
    }

    @Override
    public int wire(@Nullable GunsmithPartQuality quality, boolean ok) {
        return ok && quality != null ? GunsmithUi.qualityColorLight(quality) : 0xFFB7C2D2;
    }

    @Override
    public AssemblyColors assembly() {
        return ASSEMBLY;
    }
}
