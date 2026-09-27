package com.miningdim.job.munitions.client.style;

import com.miningdim.job.munitions.MunitionsCaliber;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.Map;

import static com.miningdim.job.munitions.client.style.BlueprintTheme.LD;
import static com.miningdim.job.munitions.client.style.BlueprintTheme.LN;
import static com.miningdim.job.munitions.client.style.BlueprintTheme.PAPER;
import static com.miningdim.job.munitions.client.style.GsPainter.CENTER;
import static com.miningdim.job.munitions.client.style.GsPainter.RIGHT;

/**
 * 蓝图风格的弹药技术线稿 (预览 drawCartridge): 按每个口径的真实尺寸 (弹径、壳长、全长, 单位 mm) 逐列画出
 * 弹壳 + 弹头轮廓, 再标全长、壳长和弹径。不新增贴图, 全部用 fill。
 */
public final class CartridgeDrawing {

    /** 外形: 瓶颈 / 凸缘瓶颈 / 直筒 / 霰弹壳 / 榴弹。 */
    public enum Shape {
        NECK, RIM, STRAIGHT, SHELL, GRENADE
    }

    /**
     * 口径尺寸 (mm)。
     *
     * @param bore        弹径
     * @param caseLength  壳长
     * @param overall     全长
     */
    public record Dims(double bore, double caseLength, double overall, Shape shape) {
    }

    /**
     * 尺寸表按枚举名映射 (数值抄自设计预览 CAL.dim)。表里包含其他分支才有的口径 (如 PISTOL_45ACP),
     * 当前分支没有这些枚举常量时它们只是不会被查到; 表里查不到的口径回退为同类别的代表尺寸。
     */
    private static final Map<String, Dims> DIMS = Map.ofEntries(
            Map.entry("PISTOL", new Dims(9.0, 19.2, 29.7, Shape.STRAIGHT)),
            Map.entry("BIG_PISTOL", new Dims(12.7, 32.6, 40.9, Shape.STRAIGHT)),
            Map.entry("PISTOL_45ACP", new Dims(11.5, 22.8, 32.4, Shape.STRAIGHT)),
            Map.entry("RIFLE", new Dims(7.9, 38.7, 56.0, Shape.NECK)),
            Map.entry("BATTLE", new Dims(7.9, 53.7, 77.2, Shape.RIM)),
            Map.entry("SPECIAL", new Dims(7.0, 51.2, 71.0, Shape.NECK)),
            Map.entry("RIFLE_556", new Dims(5.7, 44.7, 57.4, Shape.NECK)),
            Map.entry("SHOTGUN", new Dims(18.5, 70.0, 70.0, Shape.SHELL)),
            Map.entry("SNIPER", new Dims(8.6, 69.2, 93.5, Shape.NECK)),
            Map.entry("ANTI_MATERIEL", new Dims(12.9, 99.0, 138.0, Shape.NECK)),
            Map.entry("SNIPER_3006", new Dims(7.8, 63.3, 84.8, Shape.NECK)),
            Map.entry("SNIPER_792", new Dims(8.2, 57.0, 80.5, Shape.NECK)),
            Map.entry("SNIPER_303", new Dims(7.9, 56.4, 78.1, Shape.RIM)),
            Map.entry("EXPLOSIVE", new Dims(40.0, 46.0, 99.0, Shape.GRENADE)));

    private static final int BODY = 0xFF1D5496;
    private static final int BULLET = 0xFF245E9E;
    private static final int DIM_LINE = 0xFF9DBBE3;
    private static final int LABEL = 0xFFE8F1FF;
    private static final int CENTER_LINE = GsCanvas.rgba(214, 230, 255, 0.55D);

    private CartridgeDrawing() {
    }

    /** 该口径的线稿尺寸。 */
    public static Dims dims(MunitionsCaliber caliber) {
        Dims d = DIMS.get(caliber.name());
        if (d != null) {
            return d;
        }
        return switch (caliber.category()) {
            case PISTOL -> DIMS.get("PISTOL");
            case SHOTGUN -> DIMS.get("SHOTGUN");
            case SNIPER -> DIMS.get("SNIPER");
            case EXPLOSIVE -> DIMS.get("EXPLOSIVE");
            case RIFLE -> DIMS.get("RIFLE");
        };
    }

    /** 在 GUI 矩形 (x, y, w, h) 内居中画线稿 (预览调用 (100, 74, 162, 42))。 */
    public static void draw(GsPainter p, MunitionsCaliber caliber, int x, int y, int w, int h) {
        Dims d = dims(caliber);
        Profile prof = new Profile(d);
        double maxR = Math.max(prof.rc, Math.max(prof.rim, prof.rb));
        double availW = w - 44;
        double availH = h - 20;
        double k = Math.min(availW / d.overall(), availH / (maxR * 2.0D));
        int length = (int) Math.round(d.overall() * k);
        double cl = d.caseLength() * k;
        int x0 = (int) Math.round(x + (w - length) / 2.0D) - 4;
        int cy = (int) Math.round(y + h / 2.0D) - 1;
        boolean shell = d.shape() == Shape.SHELL;

        p.batch(() -> {
            Integer pt = null;
            Integer pb = null;
            for (int i = 0; i <= length; i++) {
                double mm = Math.min(d.overall(), i / k);
                double rp = Math.max(0.5D, prof.radius(mm) * k);
                int top = (int) Math.round(cy - rp);
                int bot = (int) Math.round(cy + rp);
                boolean bullet = !shell && mm > d.caseLength();
                p.rect(x0 + i, top, 1, bot - top, bullet ? BULLET : BODY);
                if (i == 0 || i == length) {
                    p.rect(x0 + i, top, 1, bot - top + 1, LN);
                }
                int a = pt == null ? top : pt;
                int b = pb == null ? bot : pb;
                p.rect(x0 + i, Math.min(a, top), 1, Math.abs(a - top) + 1, LN);
                p.rect(x0 + i, Math.min(b, bot), 1, Math.abs(b - bot) + 1, LN);
                pt = top;
                pb = bot;
            }
            int mouth = shell ? (int) Math.round(length * 0.22D) : (int) Math.round(cl);
            int mr = (int) Math.round(prof.radius(shell ? d.overall() * 0.22D : d.caseLength()) * k);
            p.rect(x0 + mouth, cy - mr + 1, 1, mr * 2 - 1, LD);
            for (int i = x0 - 5; i < x0 + length + 6; i += 8) {
                p.rect(i, cy, 4, 1, CENTER_LINE);
                p.rect(i + 5, cy, 1, 1, CENTER_LINE);
            }
        });

        int hr = (int) Math.round(maxR * k);
        int yDown = cy + hr + 5;
        int yUp = cy - hr - 5;
        dimension(p, x0, x0 + length, yDown, fmt1(d.overall()), cy + hr, cy + hr);
        dimension(p, x0, x0 + (int) Math.round(shell ? length : cl), yUp, fmt1(d.caseLength()), cy - hr, cy - hr);
        int lx = x0 + length + 4;
        int boreY = cy - (int) Math.round(prof.rb * k);
        p.rect(lx - 2, boreY, 5, 1, LD);
        p.text("Ø" + fmt1(d.bore()), lx + 4, boreY - 2, LABEL, 0.5F, 0);
        p.text(Component.translatable("gui.miningdim.gunsmith_ui.blueprint.unit_mm"), x + w - 4, y + h - 6,
                0xFF5F86BD, 0.45F, RIGHT);
    }

    /** 尺寸线: 两条引出线 + 标注线 + 两端短竖 + 居中数值 (底下垫一块图纸色挡住标注线)。 */
    private static void dimension(GsPainter p, int xa, int xb, int yy, String label, int ext0, int ext1) {
        p.rect(xa, Math.min(ext0, yy), 1, Math.abs(yy - ext0) + 2, LD);
        p.rect(xb, Math.min(ext1, yy), 1, Math.abs(yy - ext1) + 2, LD);
        p.rect(xa, yy, xb - xa + 1, 1, DIM_LINE);
        p.rect(xa + 1, yy - 1, 1, 3, DIM_LINE);
        p.rect(xb - 1, yy - 1, 1, 3, DIM_LINE);
        float lw = p.textWidth(label, 0.5F, false);
        float mx = (xa + xb) / 2.0F;
        p.rectF(mx - lw / 2.0F - 1.0F, yy - 2, lw + 2.0F, 5, PAPER);
        p.text(label, mx, yy - 1.8F, LABEL, 0.5F, CENTER);
    }

    private static String fmt1(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    /** 预览 prof(mm): 距弹底 mm 处的半径 (mm)。 */
    private static final class Profile {
        final Dims d;
        final double rb;
        final double rc;
        final double rim;

        Profile(Dims d) {
            this.d = d;
            this.rb = d.bore() / 2.0D;
            switch (d.shape()) {
                case NECK -> {
                    rc = rb * 1.42D;
                    rim = rc;
                }
                case RIM -> {
                    rc = rb * 1.45D;
                    rim = rc * 1.18D;
                }
                case STRAIGHT -> {
                    rc = rb * 1.08D;
                    rim = rc;
                }
                case SHELL -> {
                    rc = rb * 1.1D;
                    rim = rc * 1.08D;
                }
                default -> {
                    rc = rb;
                    rim = rb * 1.03D;
                }
            }
        }

        double radius(double mm) {
            Shape t = d.shape();
            double oal = d.overall();
            double cl = d.caseLength();
            if (t == Shape.SHELL) {
                if (mm < 0.8D) {
                    return rim;
                }
                return mm > oal - 2.5D ? rc * 0.9D : rc;
            }
            if (t == Shape.GRENADE) {
                if (mm < cl) {
                    return mm < 1.5D ? rim : rc * 0.96D;
                }
                double u = (mm - cl) / (oal - cl);
                double q = (u - 0.3D) / 0.7D;
                return u < 0.3D ? rb : rb * Math.max(0.28D, Math.sqrt(Math.max(0.0D, 1.0D - q * q)));
            }
            if (mm < 0.9D) {
                return rim;
            }
            if (t != Shape.RIM && mm < 1.9D) {
                return rc * 0.82D;
            }
            if (mm <= cl) {
                if (t == Shape.STRAIGHT) {
                    return rc * (1.0D - 0.03D * mm / cl);
                }
                double s0 = cl * 0.8D;
                double s1 = cl * 0.87D;
                double rn = rb * 1.12D;
                if (mm < s0) {
                    return rc * (1.0D - 0.05D * mm / s0);
                }
                if (mm < s1) {
                    double u = (mm - s0) / (s1 - s0);
                    return rc * 0.95D + (rn - rc * 0.95D) * u;
                }
                return rn;
            }
            double u = (mm - cl) / (oal - cl);
            double bear = t == Shape.STRAIGHT ? 0.3D : 0.4D;
            if (u < bear) {
                return rb;
            }
            double v = (u - bear) / (1.0D - bear);
            return rb * Math.max(t == Shape.STRAIGHT ? 0.45D : 0.16D, Math.sqrt(Math.max(0.0D, 1.0D - v * v)));
        }
    }
}
