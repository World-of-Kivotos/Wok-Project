package com.miningdim.job.munitions.client;

import com.miningdim.job.munitions.gunsmith.GunsmithPartRarity;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleUnaryOperator;

/**
 * 枪匠组件稀有度名牌的逐像素绘制：约 14° 斜切的学园标签，左侧实色等级块里 5 根斜条点亮 N 根，
 * 右侧深色名牌宽度随文字变化；整块自发光并带 1~3 圈阶梯像素光晕，动效按五档特效阶梯逐档叠加。
 *
 * <p>只做纯数值计算并输出 ARGB 像素，刻意不引用任何客户端类：GameTest 服务端会直接加载本类，
 * 校验等级条与文字底色的可读性不变量。贴图上传、文字与势力 LOGO 由
 * {@link ClientGunsmithFactionTooltip} 负责。
 */
public final class GunsmithNameplatePainter {

    /** 标签本体高度（GUI 像素）。 */
    public static final int TAG_HEIGHT = 16;
    /** 贴图四周为光晕、光粒与星闪预留的边距。 */
    public static final int MARGIN = 4;
    /** 名牌文字相对标签左上角的偏移。 */
    public static final int LABEL_X = 31;
    public static final int LABEL_Y = 4;

    static final int CHIP_WIDTH = 24;
    static final int PIP_COUNT = 5;
    static final int FIRST_PIP_U = 3;
    static final int PIP_STRIDE = 4;
    static final int PIP_WIDTH = 2;
    static final int PIP_TOP = 4;
    static final int PIP_BOTTOM = 11;

    private static final int H = TAG_HEIGHT;
    private static final int M = MARGIN;
    private static final int CH = CHIP_WIDTH;
    private static final long EFFECT_DELAY_MILLIS = 200L;
    private static final double TAU = Math.PI * 2.0D;
    private static final int[] WHITE = {255, 255, 255};
    private static final double[] RING_ALPHA = {0.45D, 0.22D, 0.10D};
    /** 各档特效幅度，下标为档位 1~5。 */
    private static final double[] EFFECT_STRENGTH = {0.0D, 0.6D, 0.7D, 0.8D, 0.9D, 1.0D};
    private static final int[] SPARK_BIG = {1, 2, 3, 3, 2, 1};
    private static final int[] SPARK_SMALL = {1, 2, 2, 1};
    private static final int[] CHASE_PERIOD_MILLIS = {3600, 3200, 2800, 2400, 2000};

    private static final int PART_CHIP = 0;
    private static final int PART_BODY = 1;
    private static final int PART_TAIL = 2;

    private static final Map<Integer, Geometry> GEOMETRY = new ConcurrentHashMap<>();

    private GunsmithNameplatePainter() {
    }

    /** 五档特效阶梯：每种特效从 {@link #startRank()} 档起出现，高档保留低档的全部特效。 */
    public enum Effect {
        /** 光晕与文字背光缓慢明暗起伏。 */
        BREATHE(1),
        /** 一段段亮光沿名牌霓虹边线顺时针流动。 */
        CURRENT(2),
        /** 尾杠像指示灯一样闪。 */
        BLINK(2),
        /** 一道脉冲依次扫过点亮的等级格。 */
        PULSE(3),
        /** 等级色块里的 45° 斜向光纹缓缓流动。 */
        STRIPES(3),
        /** 每隔几秒扫过一道 45° 高光，原型级为粗细两道。 */
        GLINT(4),
        /** 名牌底有光尘缓缓上浮。 */
        MOTES(4),
        /** 外围光晕沿铭牌从左到右起伏。 */
        HALO_FLOW(4),
        /** 一个光点沿外框绕圈，拖着光尾。 */
        CHASE(5),
        /** 顶边不断飘出光粒。 */
        EMBERS(5),
        /** 流光进出、绕框光过转角时闪出像素星。 */
        SPARKLE(5),
        /** 偶尔一两帧整行横向错位，像不稳定的实验原型。 */
        GLITCH(5);

        private final int startRank;

        Effect(int startRank) {
            this.startRank = startRank;
        }

        public int startRank() {
            return startRank;
        }
    }

    /** 稀有度档位：制式级 1 至原型级 5。 */
    public static int rank(GunsmithPartRarity rarity) {
        return switch (Objects.requireNonNull(rarity, "rarity")) {
            case STANDARD -> 1;
            case MODIFIED -> 2;
            case SPECIAL -> 3;
            case ADVANCED -> 4;
            case PROTOTYPE -> 5;
        };
    }

    public static boolean has(GunsmithPartRarity rarity, Effect effect) {
        return rank(rarity) >= effect.startRank();
    }

    public static Set<Effect> effects(GunsmithPartRarity rarity) {
        Set<Effect> result = EnumSet.noneOf(Effect.class);
        for (Effect effect : Effect.values()) {
            if (has(rarity, effect)) {
                result.add(effect);
            }
        }
        return result;
    }

    /** 名牌文字颜色（0xRRGGBB），与本档配色一致。 */
    public static int labelColor(GunsmithPartRarity rarity) {
        int[] text = palette(rarity).text;
        return (text[0] << 16) | (text[1] << 8) | text[2];
    }

    /** 标签本体宽度（不含光晕边距），随粗体文字宽度变化。 */
    public static int tagWidth(int labelWidth) {
        return bodyWidth(labelWidth) + 31;
    }

    public static int imageWidth(int labelWidth) {
        return tagWidth(labelWidth) + M * 2;
    }

    public static int imageHeight() {
        return H + M * 2;
    }

    /**
     * 绘制一帧名牌。
     *
     * @param labelWidth    粗体名牌文字宽度（GUI 像素）
     * @param elapsedMillis 自悬停开始经过的毫秒数；小于 0 时画静止态，不带任何动效
     * @param argbOut       输出像素，按行存放，尺寸为 {@link #imageWidth} × {@link #imageHeight}
     */
    public static void paint(GunsmithPartRarity rarity, int labelWidth, long elapsedMillis, int[] argbOut) {
        Geometry geo = geometry(labelWidth);
        if (argbOut.length != geo.width * geo.height) {
            throw new IllegalArgumentException("argbOut must hold " + geo.width + "x" + geo.height + " pixels");
        }
        Palette p = palette(rarity);
        Glow glow = glow(rarity);
        int rank = rank(rarity);
        boolean animated = elapsedMillis >= 0L;
        double e = elapsedMillis;
        Canvas c = new Canvas(geo.width, geo.height);

        double pulse = animated && has(rarity, Effect.BREATHE)
                ? 1.0D - glow.breathe * (0.5D - 0.5D * Math.cos(e / 2600.0D * TAU)) : 1.0D;
        boolean flow = animated && has(rarity, Effect.HALO_FLOW);
        double strength = EFFECT_STRENGTH[rank];
        drawHalo(c, p, glow, geo, x -> pulse
                * (flow ? 1.0D + 0.35D * strength * Math.sin((x / 40.0D - e / 2200.0D) * TAU) : 1.0D));
        drawLit(c, p, rank, geo);
        if (animated) {
            if (has(rarity, Effect.STRIPES)) {
                stripes(c, p, rank, geo, e);
            }
            if (has(rarity, Effect.PULSE)) {
                pipPulse(c, p, rank, geo, e);
            }
            if (has(rarity, Effect.CURRENT)) {
                current(c, p, rank, geo, e);
            }
            if (has(rarity, Effect.BLINK)) {
                blink(c, p, rank, geo, e);
            }
            if (has(rarity, Effect.MOTES)) {
                motes(c, p, rank, geo, e);
            }
            if (has(rarity, Effect.EMBERS)) {
                embers(c, p, rank, geo, e);
            }
            if (has(rarity, Effect.CHASE)) {
                chase(c, p, rank, geo, e, has(rarity, Effect.SPARKLE));
            }
            if (has(rarity, Effect.GLINT) && elapsedMillis >= EFFECT_DELAY_MILLIS) {
                boolean prototype = rank == 5;
                int cycle = prototype ? 3000 : 4200;
                glint(c, p, rank, geo, (elapsedMillis - EFFECT_DELAY_MILLIS) % cycle,
                        prototype ? 820 : 900, prototype ? 1.0D : 0.8D, prototype,
                        has(rarity, Effect.SPARKLE));
            }
            if (has(rarity, Effect.GLITCH)) {
                glitch(c, p, e);
            }
        }
        backlight(c, p, glow, geo, pulse);
        c.writeArgb(argbOut);
    }

    // ---------- 几何 ----------

    static int bodyWidth(int labelWidth) {
        return Math.max(34, labelWidth + 11);
    }

    /** 斜切：每 4 行向左错 1 像素，顶行比底行靠右 3 像素。 */
    static int rowOffset(int y) {
        return (H - 1 - y) >> 2;
    }

    static boolean isPip(int u, int y) {
        return u >= FIRST_PIP_U && u < FIRST_PIP_U + PIP_STRIDE * (PIP_COUNT - 1) + PIP_WIDTH
                && (u - FIRST_PIP_U) % PIP_STRIDE < PIP_WIDTH && y >= PIP_TOP && y <= PIP_BOTTOM;
    }

    static int pipIndex(int u) {
        return (u - FIRST_PIP_U) / PIP_STRIDE;
    }

    /** 等级格像素在输出贴图中的横坐标。 */
    static int pipPixelX(int pipIndex, int column, int y) {
        return FIRST_PIP_U + pipIndex * PIP_STRIDE + column + rowOffset(y) + M;
    }

    private static Geometry geometry(int labelWidth) {
        return GEOMETRY.computeIfAbsent(bodyWidth(labelWidth), Geometry::new);
    }

    private static final class Geometry {
        final int body;
        final int end;
        final int tagWidth;
        final int width;
        final int height;
        final int[] cellU;
        final int[] cellY;
        final int[] cellPart;
        final float[] dist;
        final int[][] perimeter;

        Geometry(int body) {
            this.body = body;
            this.end = CH + body;
            this.tagWidth = body + 31;
            this.width = tagWidth + M * 2;
            this.height = H + M * 2;

            int count = 0;
            int[] us = new int[H * (end + 4)];
            int[] ys = new int[us.length];
            int[] parts = new int[us.length];
            for (int y = 0; y < H; y++) {
                for (int u = 0; u <= end + 3; u++) {
                    if (u == CH || u == end + 1 || (u > end + 1 && (y < 2 || y > 13))) {
                        continue;
                    }
                    us[count] = u;
                    ys[count] = y;
                    parts[count] = u < CH ? PART_CHIP : u <= end ? PART_BODY : PART_TAIL;
                    count++;
                }
            }
            this.cellU = Arrays.copyOf(us, count);
            this.cellY = Arrays.copyOf(ys, count);
            this.cellPart = Arrays.copyOf(parts, count);

            boolean[] inside = new boolean[width * height];
            for (int i = 0; i < count; i++) {
                inside[(cellY[i] + M) * width + cellU[i] + rowOffset(cellY[i]) + M] = true;
            }
            this.dist = new float[width * height];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if (inside[y * width + x]) {
                        continue;
                    }
                    double best = 99.0D;
                    for (int dy = -M; dy <= M; dy++) {
                        for (int dx = -M; dx <= M; dx++) {
                            int nx = x + dx;
                            int ny = y + dy;
                            if (nx >= 0 && ny >= 0 && nx < width && ny < height && inside[ny * width + nx]) {
                                best = Math.min(best, Math.hypot(dx, dy));
                            }
                        }
                    }
                    dist[y * width + x] = (float) best;
                }
            }

            int n = (end + 1) + (H - 1) + end + (H - 2);
            this.perimeter = new int[n][];
            int i = 0;
            for (int u = 0; u <= end; u++) {
                perimeter[i++] = new int[]{u, 0, 0, -1};
            }
            for (int y = 1; y < H; y++) {
                perimeter[i++] = new int[]{end, y, 1, 0};
            }
            for (int u = end - 1; u >= 0; u--) {
                perimeter[i++] = new int[]{u, H - 1, 0, 1};
            }
            for (int y = H - 2; y >= 1; y--) {
                perimeter[i++] = new int[]{0, y, -1, 0};
            }
        }
    }

    // ---------- 配色与发光强度 ----------

    private record Palette(int[] deep, int[] dark, int[] mid, int[] bright, int[] text) {
    }

    /** g 总强度、rings 光晕圈数、breathe 呼吸幅度，均随档位递增。 */
    private record Glow(double g, int rings, double breathe) {
    }

    private static final Palette STANDARD = palette(0x16181E, 0x3C414D, 0x8E96A7, 0xE3E8F1, 0xF4F6FA);
    private static final Palette MODIFIED = palette(0x0A1530, 0x173C7A, 0x2F77DD, 0x82BEFF, 0xDDEEFF);
    private static final Palette SPECIAL = palette(0x0A1F14, 0x17502E, 0x2FA85C, 0x7BE89A, 0xDDFBE6);
    private static final Palette ADVANCED = palette(0x160B2C, 0x3F2078, 0x9057EA, 0xCDA8FF, 0xF1E6FF);
    private static final Palette PROTOTYPE = palette(0x280A0E, 0x72161C, 0xDE3B3B, 0xFF8A78, 0xFFE3DD);

    private static Palette palette(int deep, int dark, int mid, int bright, int text) {
        return new Palette(rgb(deep), rgb(dark), rgb(mid), rgb(bright), rgb(text));
    }

    private static Palette palette(GunsmithPartRarity rarity) {
        return switch (rarity) {
            case STANDARD -> STANDARD;
            case MODIFIED -> MODIFIED;
            case SPECIAL -> SPECIAL;
            case ADVANCED -> ADVANCED;
            case PROTOTYPE -> PROTOTYPE;
        };
    }

    private static Glow glow(GunsmithPartRarity rarity) {
        return switch (rarity) {
            case STANDARD -> new Glow(0.55D, 1, 0.20D);
            case MODIFIED -> new Glow(0.75D, 2, 0.20D);
            case SPECIAL -> new Glow(0.85D, 2, 0.22D);
            case ADVANCED -> new Glow(1.00D, 3, 0.26D);
            case PROTOTYPE -> new Glow(1.15D, 3, 0.30D);
        };
    }

    private static int[] rgb(int color) {
        return new int[]{(color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF};
    }

    private static int[] mix(int[] a, int[] b, double t) {
        return new int[]{
                (int) Math.round(a[0] + (b[0] - a[0]) * t),
                (int) Math.round(a[1] + (b[1] - a[1]) * t),
                (int) Math.round(a[2] + (b[2] - a[2]) * t)};
    }

    // ---------- 基底：光晕、发光本体、文字背光 ----------

    /** 像素光晕：按到轮廓的距离分成 1~3 圈阶梯透明度，保持像素画的硬边感。 */
    private static void drawHalo(Canvas c, Palette p, Glow glow, Geometry geo, DoubleUnaryOperator boost) {
        for (int y = 0; y < geo.height; y++) {
            for (int x = 0; x < geo.width; x++) {
                float d = geo.dist[y * geo.width + x];
                if (d == 0.0F) {
                    continue;
                }
                int ring = d <= 1.5F ? 1 : d <= 2.5F ? 2 : d <= 3.5F ? 3 : 0;
                if (ring == 0 || ring > glow.rings) {
                    continue;
                }
                double a = RING_ALPHA[ring - 1] * glow.g * boost.applyAsDouble(x - M);
                c.px(x, y, ring == 1 ? p.bright : p.mid, Math.min(1.0D, a));
            }
        }
    }

    /** 发光本体：色块自发光，名牌四边是霓虹线并向内渗光，尾杠发光。 */
    private static void drawLit(Canvas c, Palette p, int rank, Geometry geo) {
        int[] fill = mix(p.deep, new int[]{10, 9, 16}, 0.25D);
        int[] edge = rank <= 2 ? mix(p.bright, p.mid, 0.4D) : p.bright;
        int[] chipTop = mix(p.bright, p.mid, 0.45D);
        for (int i = 0; i < geo.cellU.length; i++) {
            int u = geo.cellU[i];
            int y = geo.cellY[i];
            int[] col;
            if (geo.cellPart[i] == PART_CHIP) {
                col = y == 0 ? p.text : y == H - 1 ? p.mid : mix(chipTop, p.mid, (y - 1) / (double) (H - 3));
                if (isPip(u, y)) {
                    col = pipIndex(u) < rank
                            ? ((u - FIRST_PIP_U) % PIP_STRIDE == 0 ? p.text : mix(p.text, p.bright, 0.35D))
                            : mix(p.dark, p.deep, 0.3D);
                }
            } else if (geo.cellPart[i] == PART_BODY) {
                int inner = Math.min(Math.min(y, H - 1 - y), Math.min(u - CH - 1, geo.end - u));
                if (inner == 0) {
                    col = y == H - 1 ? mix(p.text, edge, 0.5D) : edge;
                } else if (inner == 1) {
                    col = mix(fill, p.mid, 0.38D);
                } else if (inner == 2) {
                    col = mix(fill, p.mid, 0.14D);
                } else {
                    col = fill;
                }
            } else {
                col = y == 2 ? p.text : edge;
            }
            c.px(u + rowOffset(y) + M, y + M, col, 1.0D);
        }
    }

    /** 文字背光：名牌底在文字所在的几行被“照亮”，字形本身保持清晰。 */
    private static void backlight(Canvas c, Palette p, Glow glow, Geometry geo, double pulse) {
        int u0 = CH + 4;
        int u1 = CH + geo.body - 3;
        double k = glow.g * pulse;
        for (int y = 2; y <= 13; y++) {
            double row = y >= 4 && y <= 11 ? 0.20D : (y == 3 || y == 12) ? 0.11D : 0.05D;
            for (int u = u0; u <= u1; u++) {
                int edgeDistance = Math.min(u - u0, u1 - u);
                double fade = edgeDistance >= 2 ? 1.0D : edgeDistance == 1 ? 0.6D : 0.3D;
                c.add(u + rowOffset(y) + M, y + M, p.mid, row * fade * k);
            }
        }
    }

    // ---------- 特效 ----------

    private static void stripes(Canvas c, Palette p, int rank, Geometry geo, double e) {
        double a = EFFECT_STRENGTH[rank];
        for (int i = 0; i < geo.cellU.length; i++) {
            int u = geo.cellU[i];
            int y = geo.cellY[i];
            if (geo.cellPart[i] != PART_CHIP || isPip(u, y) || y == 0 || y == H - 1) {
                continue;
            }
            int x = u + rowOffset(y);
            double v = Math.sin(((x + y) / 10.0D - e / 1300.0D) * TAU);
            if (v > 0.75D) {
                c.add(x + M, y + M, p.text, 0.22D * a);
            } else if (v > 0.35D) {
                c.add(x + M, y + M, p.bright, 0.10D * a);
            }
        }
    }

    private static double pipWave(int pip, double e) {
        return Math.pow(Math.max(0.0D, Math.cos((e / 1500.0D - pip / 6.0D) * TAU)), 6.0D);
    }

    private static void pipPulse(Canvas c, Palette p, int rank, Geometry geo, double e) {
        double a = EFFECT_STRENGTH[rank];
        for (int i = 0; i < geo.cellU.length; i++) {
            if (geo.cellPart[i] != PART_CHIP) {
                continue;
            }
            int u = geo.cellU[i];
            int y = geo.cellY[i];
            int x = u + rowOffset(y) + M;
            if (isPip(u, y)) {
                if (pipIndex(u) < rank) {
                    c.add(x, y + M, WHITE, 0.5D * pipWave(pipIndex(u), e) * a);
                }
                continue;
            }
            // 正在脉冲的格子把两侧色块也照亮
            int near = (int) Math.round((u - 3.5D) / PIP_STRIDE);
            if (u >= 2 && u <= 21 && y >= 3 && y <= 12 && near >= 0 && near < rank) {
                c.add(x, y + M, p.text, 0.18D * pipWave(near, e) * a);
            }
        }
    }

    private static void current(Canvas c, Palette p, int rank, Geometry geo, double e) {
        double a = EFFECT_STRENGTH[rank];
        int bw = geo.end - CH;
        for (int i = 0; i < geo.cellU.length; i++) {
            int u = geo.cellU[i];
            int y = geo.cellY[i];
            if (geo.cellPart[i] != PART_BODY
                    || Math.min(Math.min(y, H - 1 - y), Math.min(u - CH - 1, geo.end - u)) != 0) {
                continue;
            }
            int pos;
            if (y == 0) {
                pos = u - CH - 1;
            } else if (u == geo.end) {
                pos = bw - 1 + y;
            } else if (y == H - 1) {
                pos = bw + H - 2 + (geo.end - u);
            } else {
                pos = 2 * bw + H - 3 + (H - 1 - y);
            }
            double v = Math.sin((pos / 14.0D - e / 1100.0D) * TAU);
            int x = u + rowOffset(y) + M;
            if (v > 0.8D) {
                c.add(x, y + M, WHITE, 0.35D * a);
            } else if (v > 0.45D) {
                c.add(x, y + M, p.text, 0.15D * a);
            }
        }
    }

    private static void blink(Canvas c, Palette p, int rank, Geometry geo, double e) {
        double b = Math.pow(Math.max(0.0D, Math.sin(e / 1200.0D * TAU)), 4.0D) * EFFECT_STRENGTH[rank];
        for (int i = 0; i < geo.cellU.length; i++) {
            if (geo.cellPart[i] == PART_TAIL) {
                int y = geo.cellY[i];
                c.add(geo.cellU[i] + rowOffset(y) + M, y + M, p.text, 0.6D * b);
            }
        }
    }

    private static double hash(int n) {
        double s = Math.sin(n * 127.1D + 311.7D) * 43758.5453D;
        return s - Math.floor(s);
    }

    private static void motes(Canvas c, Palette p, int rank, Geometry geo, double e) {
        int bw = geo.end - CH;
        for (int i = 0; i < 2 + rank; i++) {
            double phase = (e / (1800.0D + hash(i) * 1400.0D) + hash(i + 9)) % 1.0D;
            int u = CH + 3 + (int) Math.floor(hash(i + 3) * (bw - 5));
            int y = (int) Math.round(13.0D - phase * 11.0D);
            c.add(u + rowOffset(y) + M, y + M, p.bright, Math.sin(phase * Math.PI) * 0.55D * EFFECT_STRENGTH[rank]);
        }
    }

    private static void embers(Canvas c, Palette p, int rank, Geometry geo, double e) {
        for (int i = 0; i < 4; i++) {
            double phase = (e / (1400.0D + hash(i + 20) * 900.0D) + hash(i + 30)) % 1.0D;
            int u = 1 + (int) Math.floor(hash(i + 40) * (geo.end - 2));
            if (u == CH) {
                continue;
            }
            c.px(u + rowOffset(0) + M, M - 1 - (int) Math.floor(phase * 3.5D),
                    phase < 0.3D ? p.text : p.bright, (1.0D - phase) * 0.8D * EFFECT_STRENGTH[rank]);
        }
    }

    private static void chase(Canvas c, Palette p, int rank, Geometry geo, double e, boolean withSparkle) {
        int[][] pts = geo.perimeter;
        int n = pts.length;
        int period = CHASE_PERIOD_MILLIS[rank - 1];
        double trail = 14.0D;
        double head = ((e / period) % 1.0D) * n;
        for (int i = 0; i < n; i++) {
            double d = (head - i + n) % n;
            if (d > trail) {
                continue;
            }
            int u = pts[i][0];
            int y = pts[i][1];
            if (u == CH) {
                continue;
            }
            int nx = pts[i][2];
            int ny = pts[i][3];
            int x = u + rowOffset(y) + M;
            int yy = y + M;
            c.add(x, yy, d < 1.5D ? WHITE : p.bright, (d < 1.0D ? 1.0D : 1.0D - d / trail) * 0.95D);
            if (d < 5.0D) {
                double k = 1.0D - d / 5.0D;
                c.px(x + nx, yy + ny, p.bright, 0.55D * k);
                c.px(x + nx * 2, yy + ny * 2, p.bright, 0.25D * k);
                c.add(x - nx, yy - ny, p.bright, 0.35D * k);
            }
        }
        if (withSparkle) {
            int topRight = geo.end;
            int bottomLeft = 2 * geo.end + H - 1;
            sparkleAt(c, p, geo.end + rowOffset(0), 0, SPARK_BIG, ((head - topRight + n) % n) / n * period);
            sparkleAt(c, p, rowOffset(H - 1), H - 1, SPARK_BIG, ((head - bottomLeft + n) % n) / n * period);
        }
    }

    private static double ease(double q) {
        return -(Math.cos(Math.PI * q) - 1.0D) / 2.0D;
    }

    private static double inverseEase(double v) {
        return Math.acos(1.0D - 2.0D * Math.min(1.0D, Math.max(0.0D, v))) / Math.PI;
    }

    /** 45° 流光：白芯加同色光晕，加色叠加；顶边与底线顺势反光。未点亮的等级格保持暗色，不被照亮。 */
    private static void glint(Canvas c, Palette p, int rank, Geometry geo, long k, int duration,
                              double amp, boolean doubled, boolean withSparkle) {
        double span = geo.tagWidth + 28;
        if (k < duration) {
            int pos = (int) Math.round(-20.0D + ease(k / (double) duration) * span);
            for (int y = 0; y < H; y++) {
                int gx = pos + (H - 1 - y);
                for (int d = -6; d <= 3; d++) {
                    int x = gx + d;
                    int u = x - rowOffset(y);
                    if (u < CH && isPip(u, y) && pipIndex(u) >= rank) {
                        continue;
                    }
                    int xx = x + M;
                    int yy = y + M;
                    if (d == 0 || d == 1) {
                        c.add(xx, yy, p.text, 0.9D * amp);
                    } else if (d == -1 || d == 2) {
                        c.add(xx, yy, p.bright, 0.4D * amp);
                    } else if (doubled && d == -4) {
                        c.add(xx, yy, p.text, 0.7D * amp);
                    } else if (doubled && (d == -3 || d == -5)) {
                        c.add(xx, yy, p.bright, 0.22D * amp);
                    }
                }
            }
            for (int y : new int[]{0, H - 1}) {
                double gx = pos + (H - 1 - y) + 0.5D;
                for (int x = (int) Math.floor(gx - 8.0D); x <= gx + 8.0D; x++) {
                    double f = 1.0D - Math.abs(x - gx) / 8.0D;
                    if (f > 0.0D) {
                        c.add(x + M, y + M, p.bright, f * 0.5D * amp);
                    }
                }
            }
        }
        if (withSparkle) {
            int entryX = rowOffset(0) + 1;
            int exitX = geo.end + rowOffset(0);
            sparkleAt(c, p, entryX, 0, SPARK_SMALL, k - inverseEase((entryX + 5) / span) * duration);
            sparkleAt(c, p, exitX, 0, SPARK_BIG, k - inverseEase((exitX + 5) / span) * duration);
        }
    }

    private static final long GLITCH_CYCLE_MILLIS = 3400L;

    /** 该时刻是否处在错位帧里（每 3.4 秒两次，共约 160 毫秒）。 */
    static boolean isGlitchFrame(long elapsedMillis) {
        long k = elapsedMillis % GLITCH_CYCLE_MILLIS;
        return (k > 2600 && k < 2710) || (k > 2780 && k < 2830);
    }

    /** 错位：偶尔一两帧把两段行整体横移，像不稳定的实验原型。 */
    private static void glitch(Canvas c, Palette p, double e) {
        long elapsed = (long) e;
        if (!isGlitchFrame(elapsed)) {
            return;
        }
        long index = elapsed / GLITCH_CYCLE_MILLIS;
        int first = 3 + (int) ((index * 7) % 8);
        int second = 4 + (int) ((index * 5 + 3) % 8);
        for (int y = first; y < first + 2; y++) {
            c.shiftRow(y + M, 2, p.bright);
        }
        for (int y = second; y < second + 3; y++) {
            c.shiftRow(y + M, -1, p.bright);
        }
    }

    private static void sparkle(Canvas c, Palette p, int cx, int cy, int size) {
        if (size <= 0) {
            return;
        }
        c.px(cx + M, cy + M, WHITE, 1.0D);
        for (int r = 1; r < size; r++) {
            boolean tip = r == size - 1;
            int[] col = tip ? p.bright : p.text;
            double a = tip ? 0.85D : 1.0D;
            c.px(cx + r + M, cy + M, col, a);
            c.px(cx - r + M, cy + M, col, a);
            c.px(cx + M, cy + r + M, col, a);
            c.px(cx + M, cy - r + M, col, a);
        }
    }

    private static void sparkleAt(Canvas c, Palette p, int cx, int cy, int[] frames, double k) {
        int frame = (int) Math.floor(k / 65.0D);
        if (k >= 0.0D && frame < frames.length) {
            sparkle(c, p, cx, cy, frames[frame]);
        }
    }

    // ---------- 画布 ----------

    /** 非预乘 RGBA 浮点画布：px 为普通透明叠加，add 为只提亮已有像素的加色叠加。 */
    private static final class Canvas {
        final int w;
        final int h;
        final float[] d;

        Canvas(int w, int h) {
            this.w = w;
            this.h = h;
            this.d = new float[w * h * 4];
        }

        void px(int x, int y, int[] c, double a) {
            if (x < 0 || y < 0 || x >= w || y >= h || a <= 0.0D) {
                return;
            }
            int i = (y * w + x) * 4;
            double da = d[i + 3];
            double oa = a + da * (1.0D - a);
            for (int k = 0; k < 3; k++) {
                d[i + k] = (float) ((c[k] * a + d[i + k] * da * (1.0D - a)) / oa);
            }
            d[i + 3] = (float) oa;
        }

        void add(int x, int y, int[] c, double a) {
            if (x < 0 || y < 0 || x >= w || y >= h || a <= 0.0D) {
                return;
            }
            int i = (y * w + x) * 4;
            if (d[i + 3] <= 0.0F) {
                return;
            }
            for (int k = 0; k < 3; k++) {
                d[i + k] = (float) Math.min(255.0D, d[i + k] + c[k] * a);
            }
        }

        void shiftRow(int y, int dx, int[] tint) {
            float[] row = Arrays.copyOfRange(d, y * w * 4, (y + 1) * w * 4);
            for (int x = 0; x < w; x++) {
                int sx = x - dx;
                int o = (y * w + x) * 4;
                for (int k = 0; k < 4; k++) {
                    d[o + k] = sx >= 0 && sx < w ? row[sx * 4 + k] : 0.0F;
                }
            }
            for (int x = 0; x < w; x++) {
                add(x, y, tint, 0.25D);
            }
        }

        void writeArgb(int[] out) {
            for (int i = 0; i < w * h; i++) {
                int o = i * 4;
                int a = clamp(Math.round(d[o + 3] * 255.0F));
                int r = clamp(Math.round(d[o]));
                int g = clamp(Math.round(d[o + 1]));
                int b = clamp(Math.round(d[o + 2]));
                out[i] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        }

        private static int clamp(int v) {
            return Math.max(0, Math.min(255, v));
        }
    }
}
