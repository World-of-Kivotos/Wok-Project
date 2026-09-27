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
import static com.miningdim.job.munitions.client.style.GsPainter.SHADOW;

/**
 * 风格 B 产线工控 (预览对象 B_): 深色钢板、铆钉、琥珀色选中和 LED 段码条, 冲压台是带警示条纹的液压机,
 * 标题栏有三色运行灯。
 */
final class IndustrialTheme extends GunsmithTheme {

    private static final int AMBER = 0xFFF0A33C;
    private static final int HAZARD_DARK = 0xFF17191C;
    private static final int PIT = 0xFF0B0D10;
    private static final int STEEL = 0xFF15191E;
    private static final int[] LAMP_X = {200, 211, 222};
    private static final int[] LAMP_DIM = {0xFF5A2724, 0xFF5C4520, 0xFF1F4A2E};
    private static final int[] LAMP_LIT = {0xFFFF5A4E, 0xFFFFC14A, 0xFF4CE08A};
    private static final int LAMP_SHINE_ON = GsCanvas.rgba(255, 255, 255, 0.6D);
    private static final int LAMP_SHINE_OFF = GsCanvas.rgba(255, 255, 255, 0.12D);
    private static final int SCANLINE = GsCanvas.rgba(0, 0, 0, 0.22D);
    private static final int LED_SHINE = GsCanvas.rgba(255, 255, 255, 0.5D);

    IndustrialTheme() {
        super(new Palette(0xFFE6E2D8, 0xFF8C94A0, 0xFF5B6470, AMBER, 0xFF4CD07A, 0xFFFF6B5E));
    }

    @Override
    public GunsmithUiStyle style() {
        return GunsmithUiStyle.INDUSTRIAL;
    }

    @Override
    protected void paintBackground(GsCanvas c) {
        int gw = GunsmithUi.GUI_W;
        int gh = GunsmithUi.GUI_H;
        c.rect(0, 0, gw, gh, 0xFF07090B);
        c.rect(0, 0, gw - 1, gh - 1, 0xFF3A424E);
        c.rect(1, 1, gw - 2, gh - 2, 0xFF1B1F25);
        for (int r = 30; r < gh - 1; r += 3) {
            c.rect(1, r, gw - 2, 1, 0xFF1D2229);
        }
        for (int[] rivet : new int[][]{{4, 232}, {352, 232}}) {
            c.rect(rivet[0], rivet[1], 3, 3, 0xFF4E5763);
            c.rect(rivet[0] + 1, rivet[1] + 1, 1, 1, 0xFF1B1F25);
        }
        // 标题栏静态部分: 钢板底、上下沿、左侧警示条纹、条纹右侧的缝。
        c.rect(1, 1, gw - 2, 27, 0xFF22272E);
        c.rect(1, 1, gw - 2, 1, 0xFF3A424E);
        c.rect(1, 28, gw - 2, 1, PIT);
        c.stripes(1, 1, 8, 27, AMBER, HAZARD_DARK, 6, 3, 0);
        c.rect(9, 1, 1, 27, PIT);
    }

    @Override
    public void header(GsPainter p, Header h) {
        int x = 70;
        float titleScale = p.fitScale(h.title(), 52.0F, 1.25F, 0.6F, true);
        p.text(h.title(), 15, 5, 0xFFECE6D8, titleScale, BOLD | SHADOW);
        p.text(h.en(), 15, 19, 0xFF8C94A0, p.fitScale(h.en(), 52.0F, 0.5F, 0.35F, false), 0);
        if (h.badge() != null) {
            int col = h.badge().color();
            p.rect(x, 8, 26, 11, 0xFF0E1013);
            p.box(x, 8, 26, 11, col);
            p.text(h.badge().name(), x + 13, 10.5F, col, p.fitScale(h.badge().name(), 22.0F, 0.64F, 0.4F, true),
                    CENTER | BOLD);
            x += 32;
        }
        p.text(h.sub(), x, 11, 0xFF8C94A0, p.fitScale(h.sub(), 197 - x, 0.62F, 0.4F, false), 0);
        int act = (int) Math.floorMod(p.now() / 170L, 3L);
        for (int i = 0; i < LAMP_X.length; i++) {
            int lx = LAMP_X[i];
            boolean on = h.running() && i == act;
            boolean idleGreen = !h.running() && i == 2;
            p.rect(lx - 1, 10, 9, 9, PIT);
            p.rect(lx, 11, 7, 7, on ? LAMP_LIT[i] : idleGreen ? 0xFF2F7A4A : LAMP_DIM[i]);
            p.rect(lx + 1, 12, 2, 1, on ? LAMP_SHINE_ON : LAMP_SHINE_OFF);
        }
    }

    @Override
    public void owner(GsPainter p, int level, float xp, Component name, @Nullable ResourceLocation skin) {
        int x = 257;
        int y = 5;
        p.rect(x, y, 96, 19, PIT);
        p.rect(x + 1, y + 1, 94, 17, STEEL);
        p.face(skin, x + 3, y + 3, 13);
        p.text(Component.translatable("gui.miningdim.gunsmith_ui.owner.operator"), x + 20, y + 3, AMBER, 0.45F, 0);
        p.text(name, x + 20, y + 9, 0xFFE6E2D8, p.fitScale(name, 50.0F, 0.62F, 0.4F, false), 0);
        p.text(Component.translatable("gui.miningdim.gunsmith_ui.owner.level_caps"), x + 70, y + 3, 0xFF8C94A0,
                0.45F, 0);
        p.text(level < 10 ? "0" + level : String.valueOf(level), x + 91, y + 7, AMBER, 0.9F, BOLD | RIGHT);
        int segs = 12;
        int lit = Math.round(segs * clamp01(xp));
        p.batch(() -> {
            for (int i = 0; i < segs; i++) {
                p.rect(x + 52 + i * 2, y + 15, 1, 2, i < lit ? AMBER : 0xFF2A2F36);
            }
        });
    }

    @Override
    public void panel(GsPainter p, int x, int y, int w, int h, @Nullable Component title) {
        float titleW = title != null ? p.textWidth(title, 0.6F, true) + 9.0F : 0.0F;
        // 钢板 + 四颗铆钉 + 标题底 全是矩形, 合成一次 draw; 标题字留在合批外面。
        p.batch(() -> {
            p.rect(x, y, w, h, 0xFF2E353F);
            p.rect(x, y, w - 1, h - 1, 0xFF08090B);
            p.rect(x + 1, y + 1, w - 2, h - 2, STEEL);
            int[][] rivets = {{x + 2, y + 2}, {x + w - 4, y + 2}, {x + 2, y + h - 4}, {x + w - 4, y + h - 4}};
            for (int[] r : rivets) {
                p.rect(r[0], r[1], 2, 2, 0xFF48505C);
                p.rect(r[0] + 1, r[1] + 1, 1, 1, 0xFF22272E);
            }
            if (title != null) {
                p.rectF(x + 6, y + 4, titleW, 9, 0xFF232830);
                p.rect(x + 6, y + 4, 2, 9, AMBER);
            }
        });
        if (title != null) {
            p.text(title, x + 10, y + 4.8F, AMBER, 0.6F, BOLD);
        }
    }

    @Override
    public void sep(GsPainter p, int x, int y, int w) {
        p.rect(x, y, w, 1, 0xFF0A0C0F);
        p.rect(x, y + 1, w, 1, 0xFF262C35);
    }

    @Override
    public void tab(GsPainter p, int x, int y, int w, int h, Component label, State state) {
        int[] f = switch (state) {
            case SEL -> new int[]{AMBER, 0xFFB8741E, 0xFF1A1206};
            case HOVER -> new int[]{0xFF303844, 0xFF46505D, 0xFFFFFFFF};
            case LOCK, OFF -> new int[]{0xFF1A1E24, 0xFF22272E, 0xFF565E69};
            default -> new int[]{0xFF262C35, 0xFF353D48, 0xFFC9CED6};
        };
        p.rect(x, y, w, h, f[0]);
        p.rect(x, y, w, 1, state == State.SEL ? 0xFFFFD08A : f[1]);
        if (state == State.SEL) {
            p.rect(x, y + h - 1, w, 1, f[1]);
        }
        p.text(label, x + w / 2.0F, centerY(y, h, 0.64F), f[2], 0.64F, CENTER | (state == State.SEL ? BOLD : 0));
    }

    @Override
    public void card(GsPainter p, int x, int y, int w, int h, State state) {
        int[] f = switch (state) {
            case SEL -> new int[]{AMBER, 0xFF2A2419};
            case HOVER -> new int[]{0xFF6B7584, 0xFF262C35};
            case LOCK, OFF -> new int[]{0xFF262B32, 0xFF171A1F};
            default -> new int[]{0xFF39414C, 0xFF20252C};
        };
        p.rect(x, y, w, h, f[0]);
        p.rect(x + 1, y + 1, w - 2, h - 2, f[1]);
        if (state == State.SEL) {
            p.rect(x + 1, y + h - 3, w - 2, 2, AMBER);
        }
    }

    @Override
    public int cardText(State state) {
        return switch (state) {
            case SEL -> 0xFFFFC66E;
            case HOVER -> 0xFFFFFFFF;
            case LOCK, OFF -> 0xFF565E69;
            default -> 0xFFC9CED6;
        };
    }

    @Override
    public void row(GsPainter p, int x, int y, int w, int h, State state) {
        int f = switch (state) {
            case SEL -> AMBER;
            case HOVER -> 0xFF2A313B;
            case LOCK, OFF -> 0xFF171A1F;
            default -> 0xFF1E232A;
        };
        p.rect(x, y, w, h, f);
        if (state == State.SEL) {
            p.rect(x, y, 2, h, 0xFFFFD08A);
        }
    }

    @Override
    public int rowText(State state) {
        return switch (state) {
            case SEL -> 0xFF1A1206;
            case HOVER -> 0xFFFFFFFF;
            case LOCK, OFF -> 0xFF4D5560;
            default -> 0xFFC9CED6;
        };
    }

    @Override
    public int rowSub(State state) {
        return state == State.SEL ? 0xFF5C3A0E : 0xFF5B6470;
    }

    @Override
    public void slot(GsPainter p, int x, int y) {
        p.rect(x - 1, y - 1, 18, 18, 0xFF3B434F);
        p.rect(x - 1, y - 1, 17, 17, 0xFF050607);
        p.rect(x, y, 16, 16, 0xFF101318);
    }

    @Override
    public void slotOut(GsPainter p, int x, int y) {
        p.rect(x - 4, y - 4, 24, 24, PIT);
        p.box(x - 4, y - 4, 24, 24, AMBER);
        p.rect(x - 3, y - 3, 22, 1, 0xFF3A2A12);
        slot(p, x, y);
    }

    @Override
    public void btn(GsPainter p, int x, int y, int w, int h, Component label, ButtonKind kind, State state) {
        float sc = h >= 18 ? 0.8F : 0.6F;
        float fit = p.fitScale(label, w - 6, sc, 0.4F, true);
        p.rect(x - 1, y - 1, w + 2, h + 2, PIT);
        if (state == State.OFF || state == State.LOCK) {
            p.rect(x, y, w, h, 0xFF2A3038);
            p.rect(x, y, w, 1, 0xFF363D47);
            p.text(label, x + w / 2.0F, y + (h - 8.0F * sc) / 2.0F, 0xFF5E6672, fit, CENTER | BOLD);
            return;
        }
        int[] pal = switch (kind) {
            case GO -> new int[]{0xFF2FA35A, 0xFF3DBB6B, 0xFF62DB8C, 0xFF1B6A3A, 0xFFF2FFF6};
            case STOP -> new int[]{0xFFC4453E, 0xFFD65650, 0xFFEB7A73, 0xFF7F2722, 0xFFFFF1F0};
            case ACC -> new int[]{0xFFC98A2E, 0xFFE09A36, 0xFFFFC66E, 0xFF7A5217, 0xFF1A1206};
        };
        p.rect(x, y, w, h, state == State.HOVER ? pal[1] : pal[0]);
        p.rect(x, y, w, 1, pal[2]);
        p.rect(x, y + h - 2, w, 2, pal[3]);
        p.text(label, x + w / 2.0F, y + (h - 1 - 8.0F * sc) / 2.0F, pal[4], fit,
                CENTER | BOLD | (kind != ButtonKind.ACC ? SHADOW : 0));
    }

    @Override
    public int seg(GsPainter p, int x, int y, int w, int h, Component[] options, int selected) {
        int n = Math.max(1, options.length);
        int bw = (w - 1) / n;
        p.rect(x - 1, y - 1, w + 2, h + 2, PIT);
        for (int i = 0; i < options.length; i++) {
            int bx = x + i * (bw + 1);
            boolean on = i == selected;
            boolean hv = p.hov(bx, y, bw, h);
            p.rect(bx, y, bw, h, on ? AMBER : hv ? 0xFF303844 : 0xFF232830);
            p.text(options[i], bx + bw / 2.0F, centerY(y, h, 0.6F), on ? 0xFF1A1206 : 0xFFC9CED6,
                    p.fitScale(options[i], bw - 4, 0.6F, 0.4F, on), CENTER | (on ? BOLD : 0));
        }
        return bw;
    }

    @Override
    public int segGap() {
        return 1;
    }

    @Override
    public void bar(GsPainter p, int x, int y, int w, int h, float fraction, BarKind kind, boolean animate) {
        int col = switch (kind) {
            case PROG -> AMBER;
            case FE -> 0xFF4CD07A;
            case BUF -> 0xFF56B6F0;
            case BAD -> 0xFFFF5A4E;
        };
        int n = (w - 1) / 4;
        int lit = Math.round(n * clamp01(fraction));
        boolean flash = animate && blink(p.now(), 250L);
        // 底槽 + 十几格 LED, 合成一次 draw。
        p.batch(() -> {
            p.rect(x, y, w, h, 0xFF0A0C0F);
            for (int i = 0; i < n; i++) {
                int c = i < lit ? col : 0xFF1E232A;
                if (flash && i == lit - 1) {
                    c = 0xFFFFFFFF;
                }
                p.rect(x + 1 + i * 4, y + 1, 3, h - 2, c);
            }
        });
    }

    @Override
    public void lock(GsPainter p, int x, int y) {
        lockIcon(p, x, y, 0xFFFF6B5E, STEEL);
    }

    @Override
    public void lamp(GsPainter p, int x, int y, LampState state) {
        int col = switch (state) {
            case RUN -> blink(p.now(), 300L) ? 0xFFFFC14A : 0xFF8A5B16;
            case DONE -> 0xFF4CE08A;
            case IDLE -> 0xFF3A424E;
        };
        p.rect(x - 1, y - 1, 7, 7, PIT);
        p.rect(x, y, 5, 5, col);
        p.rect(x + 1, y + 1, 1, 1, LED_SHINE);
    }

    @Override
    public int stage(GsPainter p, int x, int y, int w, int h, boolean active, float ram) {
        int cx = x + w / 2;
        int drop = Math.round(ram * 7);
        p.batch(() -> {
            p.rect(x, y, w, h, 0xFF2E353F);
            p.rect(x, y, w - 1, h - 1, 0xFF08090B);
            p.rect(x + 1, y + 1, w - 2, h - 2, 0xFF101317);
            p.rect(x + 4, y + h - 4, w - 8, 2, 0xFF262C35);
            for (int colX : new int[]{x + 12, x + w - 20}) {
                p.rect(colX, y + 5, 8, h - 9, 0xFF3A424E);
                p.rect(colX + 1, y + 5, 2, h - 9, 0xFF56606E);
                p.rect(colX + 6, y + 5, 2, h - 9, 0xFF262C35);
            }
            p.rect(x + 8, y + 4, w - 16, 9, 0xFF2A3038);
            p.stripes(x + 12, y + 6, w - 24, 5, AMBER, HAZARD_DARK, 6, 3, 0);
            p.rect(cx - 4, y + 13, 8, 5, 0xFF2A3038);
            p.rect(cx - 2, y + 18, 4, 1 + drop, 0xFFB8C2CF);
            p.rect(cx - 1, y + 18, 1, 1 + drop, 0xFFE6ECF3);
            ramHead(p, cx, y + 19 + drop);
            p.rect(cx - 19, y + h - 7, 38, 4, 0xFF2A3038);
            p.stripes(cx - 19, y + h - 7, 38, 1, AMBER, HAZARD_DARK, 6, 3, 0);
            boolean bl = active && blink(p.now(), 250L);
            p.rect(x + w - 12, y + 16, 5, 4, bl ? 0xFFFFC14A : 0xFF5C4520);
            p.rect(x + w - 11, y + 15, 3, 1, bl ? 0xFFFFC14A : 0xFF5C4520);
        });
        return y + 19 + drop;
    }

    private static void ramHead(GsPainter p, int cx, int top) {
        p.rect(cx - 16, top, 32, 8, 0xFF5A6472);
        p.rect(cx - 16, top, 32, 1, 0xFF8A94A2);
        p.rect(cx - 12, top + 8, 24, 1, 0xFF3A424E);
    }

    @Override
    public void stageFront(GsPainter p, int x, int y, int w, int h, boolean active, float ram) {
        int cx = x + w / 2;
        int drop = Math.round(ram * 7);
        if (active && ram > 0.3F) {
            ramHead(p, cx, y + 19 + drop);
        }
        if (active && ram > 0.9F) {
            sparks(p, cx, y + h - 8, p.now(), 0xFFFFC45C, 0xFFFF7447);
        }
    }

    @Override
    public void showcase(GsPainter p, MunitionsCaliber caliber, int x, int y, int w, int h) {
        p.rect(x + 3, y + 1, w - 6, h - 1, 0xFF0E1318);
        AcademyTheme.blitAmmoProfile(p, caliber, x + 4, y + 2);
        p.batch(() -> {
            for (int r = y + 1; r < y + h; r += 2) {
                p.rect(x + 3, r, w - 6, 1, SCANLINE);
            }
        });
        p.corners(x + 3, y + 1, w - 6, h - 1, AMBER, 4);
    }

    @Override
    public int qColor(GunsmithPartQuality quality) {
        return GunsmithUi.qualityColor(quality);
    }

    @Override
    public void qbtn(GsPainter p, int x, int y, int w, int h, GunsmithPartQuality quality, State state) {
        int qc = GunsmithUi.qualityColor(quality);
        int f = switch (state) {
            case SEL -> 0xFF262C35;
            case HOVER -> 0xFF2A313B;
            case LOCK, OFF -> 0xFF171A1F;
            default -> 0xFF1E232A;
        };
        p.rect(x, y, w, h, state == State.SEL ? qc : PIT);
        p.rect(x + 1, y + 1, w - 2, h - 2, f);
        p.rect(x + 1, y + 1, w - 2, 1, state == State.LOCK || state == State.OFF ? 0xFF262B32 : qc);
    }

    @Override
    public void rarTag(GsPainter p, int x, int y, GunsmithPartRarity rarity) {
        p.rect(x, y, 7, 9, GunsmithUi.rarityMain(rarity));
        int bars = GunsmithUi.rarityBars(rarity);
        for (int i = 0; i < bars; i++) {
            p.rect(x + 1 + i, y + 7 - i, 1, 1, 0xFFFFFFFF);
        }
    }

    @Override
    public int rarText(GunsmithPartRarity rarity) {
        return GunsmithUi.rarityLight(rarity);
    }

    @Override
    public int shade() {
        return GsCanvas.rgba(0, 0, 0, 0.6D);
    }

    @Override
    public int slotHighlight() {
        return GsCanvas.rgba(255, 255, 255, 0.45D);
    }
}
