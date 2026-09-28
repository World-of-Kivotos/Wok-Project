package com.miningdim.job.munitions.block;

import java.util.Arrays;

/**
 * 军火台弹药箱计数屏 (方案 C "弹药箱计数") 的纯逻辑: 屏上的数字格式、字形、排版、满度条、满仓与显示键。
 * <p>
 * 掀开的箱盖内面凹进去一块小窗: 上面一条满度条, 下面 4x7 大字的缓冲发数; 口径用 3x5 黄漆模板字印在箱身正面 (缓冲空时不写)。
 * 显示面、窗、条、字框与每档颜色是生成器写进 {@link MunitionsBenchGeometry} 的 COUNTER_* 常量; 这里管 "画什么" 与 "摆在哪":
 * {@link #rects} 给出两个显示面上的矩形 (qt = 0.25 px), {@link #blockCorners} 按常量与台子朝向把每个矩形的四个角摆进主格
 * (含箱盖的旋转与顶点顺序); 客户端的 MunitionsBenchCounterRenderer 只管上色、交给 BER 的 poseStack。
 * GameTest 与 check_parity.mjs 核对的就是渲染器用的这几个方法。
 * 服务端据 {@link #displayKey} 判断屏上看得见的样子变没变, 变了才发方块更新。
 * <p>
 * 刻意不依赖任何 Minecraft 类: 服务端、渲染器与 GameTest 都直接用, 也能脱离游戏单独编译。
 * <b>与 tools/munitions_bench/counter.mjs 逐行对应, 改一边必须改另一边</b> (字表、格式、排版顺序、满度条取整、显示键的编码、角的摆法):
 * 生成器的校验与预览用那份 JS, tools/munitions_bench/check_parity.mjs 单独编译本类与 MunitionsBenchGeometry 与它逐值对拍。
 */
public final class MunitionsBenchCounter {

    /** 超过这个数按它显示 ("999G"): 保证最多 5 个字、放得进发数框; int 缓冲 (上限 21.4 亿 = "2.14G") 碰不到。 */
    public static final long MAX_ROUNDS = 999_999_999_999L;

    /** 矩形所在的显示面: 箱盖里的窗 (随箱盖旋转) / 箱身正面 (口径模板字)。 */
    public static final int FACE_WINDOW = 0;
    public static final int FACE_STENCIL = 1;
    /** 矩形的颜色角色 (MunitionsBenchGeometry.COUNTER_COLOURS 的列 = 角色 × 2 + (待机 ? 1 : 0))。 */
    public static final int ROLE_COUNT = 0;
    public static final int ROLE_ZERO = 1;
    public static final int ROLE_BAR = 2;
    public static final int ROLE_BAR_FULL = 3;
    public static final int ROLE_STENCIL = 4;
    /** {@link #rects} 里每个矩形占的 int 数: {面, 角色, x0, y0, x1, y1} (qt, 面左上角起, 右下角不含)。 */
    public static final int RECT_INTS = 6;
    /** "还没推过" 的显示键 (方块实体刚加载): 与任何真实的键都不同。 */
    public static final long NO_KEY = Long.MIN_VALUE;
    /** 显示键里屏上字的编码: 每字 4 位 (这串里的下标 + 1), 0 = 没有字。 */
    private static final String KEY_CHARS = "0123456789.KMG";

    /**
     * 点阵字体: 每字一行一个掩码, 最高位 = 最左一列; {@code narrowChar} 是 1 列宽的窄字 ('.'); 字表里没有的字按空格 (全空, 字宽照算)。
     */
    public static final class Font {
        public final int width;
        public final int height;
        private final String chars;
        private final int[][] rows;
        private final char narrowChar;
        private final int[] narrowRows;
        private final int[] blankRows;

        private Font(int width, int height, String chars, int[][] rows, char narrowChar, int[] narrowRows) {
            if (chars.length() != rows.length || narrowRows.length != height) {
                throw new IllegalArgumentException("munitions bench counter font: " + chars.length() + " chars, "
                        + rows.length + " glyphs");
            }
            for (int[] glyph : rows) {
                if (glyph.length != height) {
                    throw new IllegalArgumentException("munitions bench counter font: glyph height " + glyph.length);
                }
            }
            this.width = width;
            this.height = height;
            this.chars = chars;
            this.rows = rows;
            this.narrowChar = narrowChar;
            this.narrowRows = narrowRows;
            this.blankRows = new int[height];
        }

        /** 一个字的宽 (字体像素): 窄字 1, 其余整宽。 */
        public int glyphWidth(char c) {
            return c == narrowChar ? 1 : width;
        }

        /** 一个字第 row 行的掩码 (最高位 = 最左一列)。 */
        public int glyphRow(char c, int row) {
            return glyphRows(c)[row];
        }

        private int[] glyphRows(char c) {
            if (c == narrowChar) {
                return narrowRows;
            }
            int index = chars.indexOf(c);
            return index < 0 ? blankRows : rows[index];
        }

        /** 一串字的宽 (字体像素): 字宽 + 1 列字距, 末尾不留字距。 */
        public int textWidth(String text) {
            int x = 0;
            for (int i = 0; i < text.length(); i++) {
                x += glyphWidth(text.charAt(i)) + 1;
            }
            return Math.max(0, x - 1);
        }

        /** 字表 (不含窄字), 对拍用。 */
        public String chars() {
            return chars;
        }

        public char narrowChar() {
            return narrowChar;
        }
    }

    /** 发数用的 4x7 窄高字 (箱盖只有 8 px 宽, 装不下 0.5 px 的 3x5, 用 0.25 px 的 4x7 把字做高)。 */
    public static final Font FONT_4X7 = new Font(4, 7, "0123456789KMG ", new int[][]{
            {6, 9, 9, 9, 9, 9, 6}, {2, 6, 2, 2, 2, 2, 7}, {6, 9, 1, 2, 4, 8, 15}, {14, 1, 1, 6, 1, 1, 14},
            {2, 6, 10, 10, 15, 2, 2}, {15, 8, 14, 1, 1, 9, 6}, {6, 8, 8, 14, 9, 9, 6}, {15, 1, 2, 2, 4, 4, 4},
            {6, 9, 9, 6, 9, 9, 6}, {6, 9, 9, 7, 1, 1, 6},
            {9, 9, 10, 12, 10, 9, 9}, {9, 15, 15, 9, 9, 9, 9}, {6, 9, 8, 11, 9, 9, 6}, {0, 0, 0, 0, 0, 0, 0},
    }, '.', new int[]{0, 0, 0, 0, 0, 0, 1});

    /** 口径模板字用的 3x5 (与机身上 AMMO 模板字同一套字形, 只留口径标签用得到的字)。 */
    public static final Font FONT_3X5 = new Font(3, 5, "0123456789ABEGMRX ", new int[][]{
            {7, 5, 5, 5, 7}, {2, 6, 2, 2, 7}, {7, 1, 7, 4, 7}, {7, 1, 3, 1, 7}, {5, 5, 7, 1, 1},
            {7, 4, 7, 1, 7}, {7, 4, 7, 5, 7}, {7, 1, 1, 2, 2}, {7, 5, 7, 5, 7}, {7, 5, 7, 1, 7},
            {2, 5, 7, 5, 5}, {6, 5, 6, 5, 6}, {7, 4, 6, 4, 7}, {3, 4, 5, 5, 3}, {5, 7, 7, 5, 5},
            {6, 5, 6, 5, 5}, {5, 5, 2, 5, 5}, {0, 0, 0, 0, 0},
    }, '.', new int[]{0, 0, 0, 0, 1});

    /**
     * 客户端副本上的计数屏状态 (更新标签里的四个值): 发数、缓冲口径的 MunitionsCaliber 序号 (-1 = 空)、缓冲上限、满仓。
     * 服务端按同样四个值算 {@link #displayKey()}。
     */
    public record Shown(int rounds, int caliberIndex, int cap, boolean full) {
        public static final Shown EMPTY = new Shown(0, -1, 0, false);

        public long displayKey() {
            return MunitionsBenchCounter.displayKey(rounds, caliberIndex, cap, full);
        }
    }

    private MunitionsBenchCounter() {
    }

    // ---------------------------------------------------------------- 数字格式 (counter.mjs formatCount)

    /**
     * 缓冲发数 → 屏上的字 (最多 5 个字符, 只舍不入: 屏上的数永远不多报):
     * ≤ 0 → "0" (空箱, 画成暗色); 1..9999 原样; 10,000..999,999 → "12.4K" / "123K"; ≥ 1,000,000 → "1.23M" / "12.3M" / "123M";
     * ≥ 10^9 → "1.23G" .. "999G" (超过 {@link #MAX_ROUNDS} 按它算)。小数末尾的 0 去掉 ("3.20M" → "3.2M", "10.0K" → "10K")。
     * 默认 config 的缓冲上限是 500..4000 发, 所以正常只会看到 1–4 位数; K / M 只在服主把上限调大时出现。
     */
    public static String format(long rounds) {
        if (rounds <= 0L) {
            return "0";
        }
        long n = Math.min(rounds, MAX_ROUNDS);
        if (n <= 9999L) {
            return Long.toString(n);
        }
        long unit = n >= 1_000_000_000L ? 1_000_000_000L : n >= 1_000_000L ? 1_000_000L : 1_000L;
        char suffix = unit == 1_000L ? 'K' : unit == 1_000_000L ? 'M' : 'G';
        long v100 = n / (unit / 100L); // 两位小数, 截断 (= floor(n * 100 / unit), 不会溢出)
        String t;
        if (v100 >= 10000L) {
            t = Long.toString(v100 / 100L);
        } else if (v100 >= 1000L) {
            t = (v100 / 100L) + "." + (v100 / 10L % 10L);
        } else {
            t = (v100 / 100L) + "." + (v100 / 10L % 10L) + (v100 % 10L);
        }
        if (t.indexOf('.') >= 0) {
            int end = t.length();
            while (t.charAt(end - 1) == '0') {
                end--;
            }
            if (t.charAt(end - 1) == '.') {
                end--;
            }
            t = t.substring(0, end);
        }
        return t + suffix;
    }

    // ---------------------------------------------------------------- 满度条 / 满仓 / 显示键

    /** 满度条亮几格 (qt): 空 = 0; 上限 ≤ 0 = 整条; 否则 floor(发数 × 条宽 / 上限), 有弹至少 1 格, 最多整条。 */
    public static int barCells(int rounds, int cap) {
        int w = MunitionsBenchGeometry.COUNTER_BAR_QT[2];
        if (rounds <= 0) {
            return 0;
        }
        if (cap <= 0) {
            return w;
        }
        return (int) Math.max(1L, Math.min(w, (long) rounds * w / cap));
    }

    /**
     * 缓冲装不下一整批: 上限 - 已存 &lt; 一批的发数。方块实体的开工门 (tryStartCraft)、产出后的持续指示灯与计数屏的满仓条
     * 共用这一条, 屏上的琥珀色就是机器停产的那个原因。
     */
    public static boolean cannotTakeBatch(int rounds, int cap, int batchRounds) {
        return (long) cap - rounds < batchRounds;
    }

    /** 满仓 (满度条变琥珀色) = 有弹且装不下下一批。 */
    public static boolean isFull(int rounds, int cap, int batchRounds) {
        return rounds > 0 && cannotTakeBatch(rounds, cap, batchRounds);
    }

    /**
     * 显示键: 屏上看得见的样子 = 发数的字 + 箱身口径 + 满度条格数 + 满仓, 任一变了键才变 (上万以后只在显示的那几位变时才变)。
     * 字 (≤ 5 个, 每字 4 位) 占低 20 位, 口径序号 + 1 (空 = 0) 在第 20 位起, 格数在第 28 位起, 满仓在第 36 位。
     * 空箱不写口径、没有满度条, 所以发数为 0 时口径与满仓不进键。
     */
    public static long displayKey(int rounds, int caliberIndex, int cap, boolean full) {
        String text = format(rounds);
        long key = 0L;
        for (int i = 0; i < text.length(); i++) {
            key = key * 16L + (KEY_CHARS.indexOf(text.charAt(i)) + 1);
        }
        long caliber = rounds > 0 && caliberIndex >= 0 ? Math.min(caliberIndex, 254) + 1 : 0;
        long cells = barCells(rounds, cap);
        long fullBit = rounds > 0 && full ? 1L : 0L;
        return key + (caliber << 20) + (cells << 28) + (fullBit << 36);
    }

    // ---------------------------------------------------------------- 画什么 (counter.mjs counterRects)

    /**
     * 计数屏要画的矩形, 每个 {@link #RECT_INTS} 个 int: {面, 角色, x0, y0, x1, y1} (qt, 各自显示面的左上角起)。
     * 顺序: 发数 (右对齐, 逐行从左到右, 同一行相邻亮点并成一条) → 满度条 → 箱身口径 (水平居中)。
     * 发数 0 画暗色 "0" ({@link #ROLE_ZERO}), 没有满度条也不写口径。caliberLabel = MunitionsCaliber.shortLabel, null / "" = 不写。
     */
    public static int[] rects(int rounds, String caliberLabel, int cap, boolean full) {
        IntBuilder out = new IntBuilder();
        int n = Math.max(0, rounds);
        boolean empty = n <= 0;
        String text = format(n);
        int[] count = MunitionsBenchGeometry.COUNTER_COUNT_QT;
        int countTexel = MunitionsBenchGeometry.COUNTER_COUNT_TEXEL_QT;
        pushText(out, FACE_WINDOW, empty ? ROLE_ZERO : ROLE_COUNT, FONT_4X7, text,
                count[0] + count[2] - FONT_4X7.textWidth(text) * countTexel, count[1], countTexel);
        if (!empty) {
            int[] bar = MunitionsBenchGeometry.COUNTER_BAR_QT;
            int cells = barCells(n, cap);
            out.add(FACE_WINDOW, full ? ROLE_BAR_FULL : ROLE_BAR, bar[0], bar[1], bar[0] + cells, bar[1] + bar[3]);
            if (caliberLabel != null && !caliberLabel.isEmpty()) {
                int texel = MunitionsBenchGeometry.COUNTER_STENCIL_TEXEL_QT;
                int width = FONT_3X5.textWidth(caliberLabel) * texel;
                pushText(out, FACE_STENCIL, ROLE_STENCIL, FONT_3X5, caliberLabel,
                        Math.floorDiv(MunitionsBenchGeometry.COUNTER_STENCIL_FACE_QT[0] - width, 2),
                        MunitionsBenchGeometry.COUNTER_STENCIL_Y_QT, texel);
            }
        }
        return out.toArray();
    }

    /** 一串字 → 矩形: 逐行从左到右, 同一行相邻亮点并成一条 (字距是空列, 不会跨字并)。 */
    private static void pushText(IntBuilder out, int face, int role, Font font, String text, int ox, int oy, int t) {
        for (int r = 0; r < font.height; r++) {
            int x = 0;
            int run = -1;
            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                int w = font.glyphWidth(ch);
                int mask = font.glyphRow(ch, r);
                for (int c = 0; c <= w; c++) { // c = w 是字距 (空列)
                    boolean lit = c < w && (mask & (1 << (w - 1 - c))) != 0;
                    if (lit && run < 0) {
                        run = x + c;
                    }
                    if (!lit && run >= 0) {
                        out.add(face, role, ox + run * t, oy + r * t, ox + (x + c) * t, oy + (r + 1) * t);
                        run = -1;
                    }
                }
                x += w + 1;
            }
        }
    }

    // ---------------------------------------------------------------- 摆在哪 (counter.mjs facePoint / rectCorners / blockCorners)

    /** {@link #benchCorners} / {@link #blockCorners} 里每个矩形占的 float 数: 4 个角 × (x, y, z)。 */
    public static final int CORNER_FLOATS = 12;
    private static final double LID_COS = Math.cos(Math.toRadians(MunitionsBenchGeometry.COUNTER_LID_ROTATION_X_DEGREES));
    private static final double LID_SIN = Math.sin(Math.toRadians(MunitionsBenchGeometry.COUNTER_LID_ROTATION_X_DEGREES));

    /**
     * 显示面上的一点 (u, v qt, 面左上角起) → 整台像素 (朝北, 朝向旋转之前), 写进 out[offset..offset+2]:
     * 从面左上角 (COUNTER_*_FACE_TOP_LEFT) 起 u 向 -x、v 向 -y, 离面 lift px (面外 = -z); 窗面的点再绕
     * COUNTER_LID_ROTATION_ORIGIN 按 x 轴转 COUNTER_LID_ROTATION_X_DEGREES (右手系: 与静态 JSON 的 can_lid 元素、原版 FaceBakery 同一个转法)。
     * 箱身正面不转。
     */
    public static void facePoint(int face, double u, double v, double lift, float[] out, int offset) {
        boolean window = face == FACE_WINDOW;
        float[] tl = window ? MunitionsBenchGeometry.COUNTER_WINDOW_FACE_TOP_LEFT
                : MunitionsBenchGeometry.COUNTER_STENCIL_FACE_TOP_LEFT;
        double qt = MunitionsBenchGeometry.COUNTER_QT_PX;
        double x = tl[0] - u * qt;
        double y = tl[1] - v * qt;
        double z = tl[2] - lift;
        if (window) {
            float[] o = MunitionsBenchGeometry.COUNTER_LID_ROTATION_ORIGIN;
            double dy = y - o[1];
            double dz = z - o[2];
            y = o[1] + dy * LID_COS - dz * LID_SIN;
            z = o[2] + dy * LID_SIN + dz * LID_COS;
        }
        out[offset] = (float) x;
        out[offset + 1] = (float) y;
        out[offset + 2] = (float) z;
    }

    /**
     * {@link #rects} 的每个矩形 → 四个角 (整台像素, 朝北), 每个矩形 {@link #CORNER_FLOATS} 个 float。浮在面外 COUNTER_LIFT_PX
     * (仍在框条前沿之后)。顶点顺序 = 从正面看 左上 → 左下 → 右下 → 右上 (逆时针, 与原版 FaceInfo.NORTH 相同):
     * 渲染器用的 textBackground 剔除背面, 顺序反了字就没了。
     */
    public static float[] benchCorners(int[] rects) {
        int count = rects.length / RECT_INTS;
        float[] out = new float[count * CORNER_FLOATS];
        double lift = MunitionsBenchGeometry.COUNTER_LIFT_PX;
        for (int r = 0; r < count; r++) {
            int i = r * RECT_INTS;
            int face = rects[i];
            int x0 = rects[i + 2];
            int y0 = rects[i + 3];
            int x1 = rects[i + 4];
            int y1 = rects[i + 5];
            int o = r * CORNER_FLOATS;
            facePoint(face, x0, y0, lift, out, o);     // 左上
            facePoint(face, x0, y1, lift, out, o + 3); // 左下
            facePoint(face, x1, y1, lift, out, o + 6); // 右下
            facePoint(face, x1, y0, lift, out, o + 9); // 右上
        }
        return out;
    }

    /**
     * {@link #benchCorners} 再按台子的朝向摆进主格 (方块坐标, 主格西北下角 = 原点), 渲染器原样交给 BER 的 poseStack:
     * 与运动件同一个变换 translate(0.5, 0, 0.5) → 绕 y 转 yRotationDegrees (= MunitionsBenchBlock.partsYRotationDegrees(朝向), 右手系)
     * → translate(-0.5, 0, -0.5) → scale(1/16), 只是不做运动件那种 (s, -s, s) 的 y 翻转 (会把绕序再反一次)。
     */
    public static float[] blockCorners(int[] rects, float yRotationDegrees) {
        float[] out = benchCorners(rects);
        benchToBlock(out, 0, out.length, yRotationDegrees);
        return out;
    }

    /**
     * 整台像素 (朝北) 的点 → 主格方块坐标, 原地改写 points[from..to) 里的每个 (x, y, z) (counter.mjs benchToBlock):
     * translate(0.5, 0, 0.5) → 绕 y 转 yRotationDegrees (右手系, = MunitionsBenchBlock.partsYRotationDegrees(朝向)) →
     * translate(-0.5, 0, -0.5) → scale(1/16)。计数屏 ({@link #blockCorners}) 与运行灯效 ({@link MunitionsBenchLights#blockCorners}) 共用, 不分配。
     */
    public static void benchToBlock(float[] points, int from, int to, float yRotationDegrees) {
        double a = Math.toRadians(yRotationDegrees);
        double cos = Math.cos(a);
        double sin = Math.sin(a);
        for (int i = from; i + 2 < to; i += 3) {
            double x = points[i] / 16.0D - 0.5D;
            double z = points[i + 2] / 16.0D - 0.5D;
            points[i] = (float) (0.5D + x * cos + z * sin);
            points[i + 1] = (float) (points[i + 1] / 16.0D);
            points[i + 2] = (float) (0.5D - x * sin + z * cos);
        }
    }

    /** 某档某角色的颜色 0xRRGGBB (工作 / 待机), 档位越界按普通档。 */
    public static int colour(int tier, int role, boolean working) {
        int[][] table = MunitionsBenchGeometry.COUNTER_COLOURS;
        int[] row = table[tier >= 0 && tier < table.length ? tier : 0];
        return row[role * 2 + (working ? 0 : 1)];
    }

    /** 一个会自己长大的 int 数组 (rects 的输出; 一次最多几十个矩形)。 */
    private static final class IntBuilder {
        private int[] data = new int[RECT_INTS * 32];
        private int size;

        void add(int face, int role, int x0, int y0, int x1, int y1) {
            if (size + RECT_INTS > data.length) {
                data = Arrays.copyOf(data, data.length * 2);
            }
            data[size++] = face;
            data[size++] = role;
            data[size++] = x0;
            data[size++] = y0;
            data[size++] = x1;
            data[size++] = y1;
        }

        int[] toArray() {
            return Arrays.copyOf(data, size);
        }
    }
}
