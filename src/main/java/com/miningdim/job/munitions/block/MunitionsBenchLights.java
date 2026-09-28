package com.miningdim.job.munitions.block;

import static com.miningdim.job.munitions.block.MunitionsBenchGeometry.LIGHT_ALPHA_CUTOFF;
import static com.miningdim.job.munitions.block.MunitionsBenchGeometry.LIGHT_BREATH_ALPHA;
import static com.miningdim.job.munitions.block.MunitionsBenchGeometry.LIGHT_BREATH_PERIOD_TICKS;
import static com.miningdim.job.munitions.block.MunitionsBenchGeometry.LIGHT_CHASE_ALPHA;
import static com.miningdim.job.munitions.block.MunitionsBenchGeometry.LIGHT_CHASE_SPEED;
import static com.miningdim.job.munitions.block.MunitionsBenchGeometry.LIGHT_CHASE_TAIL_PX;
import static com.miningdim.job.munitions.block.MunitionsBenchGeometry.LIGHT_CHASE_WINDOW;
import static com.miningdim.job.munitions.block.MunitionsBenchGeometry.LIGHT_COLOURS;
import static com.miningdim.job.munitions.block.MunitionsBenchGeometry.LIGHT_DROP_ALPHA;
import static com.miningdim.job.munitions.block.MunitionsBenchGeometry.LIGHT_DROP_PULSE;
import static com.miningdim.job.munitions.block.MunitionsBenchGeometry.LIGHT_FADE_START_BLOCKS;
import static com.miningdim.job.munitions.block.MunitionsBenchGeometry.LIGHT_FULL_ALPHA;
import static com.miningdim.job.munitions.block.MunitionsBenchGeometry.LIGHT_FULL_BLINK_PERIOD_TICKS;
import static com.miningdim.job.munitions.block.MunitionsBenchGeometry.LIGHT_FULL_BLINK_RAMP;
import static com.miningdim.job.munitions.block.MunitionsBenchGeometry.LIGHT_GEM_ECHO_LEVEL;
import static com.miningdim.job.munitions.block.MunitionsBenchGeometry.LIGHT_GEM_ECHO_PULSE;
import static com.miningdim.job.munitions.block.MunitionsBenchGeometry.LIGHT_GEM_PULSE;
import static com.miningdim.job.munitions.block.MunitionsBenchGeometry.LIGHT_GROUPS;
import static com.miningdim.job.munitions.block.MunitionsBenchGeometry.LIGHT_LED_MERGE_EPSILON;
import static com.miningdim.job.munitions.block.MunitionsBenchGeometry.LIGHT_LED_PEAK_TICKS;
import static com.miningdim.job.munitions.block.MunitionsBenchGeometry.LIGHT_LED_PULSE;
import static com.miningdim.job.munitions.block.MunitionsBenchGeometry.LIGHT_LED_WIDTH_PX;
import static com.miningdim.job.munitions.block.MunitionsBenchGeometry.LIGHT_LED_X_PX;
import static com.miningdim.job.munitions.block.MunitionsBenchGeometry.LIGHT_MAX_DISTANCE_BLOCKS;
import static com.miningdim.job.munitions.block.MunitionsBenchGeometry.LIGHT_STRIKE_PULSE;
import static com.miningdim.job.munitions.block.MunitionsBenchGeometry.LIGHT_TARGET_FACES;
import static com.miningdim.job.munitions.block.MunitionsBenchGeometry.LIGHT_TARGET_GROW;
import static com.miningdim.job.munitions.block.MunitionsBenchGeometry.LIGHT_TARGET_LIFT_PX;
import static com.miningdim.job.munitions.block.MunitionsBenchGeometry.LIGHT_TARGET_RECTS_PX;
import static com.miningdim.job.munitions.block.MunitionsBenchGeometry.LIGHT_UNLOCK_TIERS;

/**
 * 军火台 (WIDE 布局, 弹药流水线) 的运行灯效: 工作时在静态模型已有的发光灯带上, 只在程序里真实发生动作的那一刻叠一层短暂的
 * 彩色四边形 (客户端的 MunitionsBenchCounterRenderer 在计数屏的字之后画, 同一个 textBackground 批次)。不加方块状态、不发包、
 * 不改静态模型: 输入全是客户端已有的 (ACTIVE、档位、同步标签里的程序起点与满仓、gameTime、相机距离)。
 * <p>
 * 按档位 (用户拍板, 唯一行为): 工位指示灯 / 冲压闪光 / 落箱脉冲 / 满仓提示 全档都有; 运行呼吸从高级 (档 2) 起, 皮带追光从极品 (档 3) 起,
 * 宝石脉冲只有闪耀 (档 5)。目标面、时间、透明度、每档颜色与解锁档位都是生成器写进 {@link MunitionsBenchGeometry} 的 LIGHT_* 常量。
 * 时间: 脉冲、工位灯、追光包络定义在 40 tick 的程序时间里 ({@link MunitionsBenchProgram#programTick}), 与运动件一起按档位变快
 * (闪耀 2 倍速: 冲压闪光 1 Hz、宝石 2 Hz、追光 3 Hz = 光敏上限); 运行呼吸与满仓闪烁是游戏时间, 不随档位变。
 * <ul>
 *   <li>{@link #compute} 算出这一帧的覆盖矩形 (整台像素, 朝北) 与颜色, 写进调用方预先分配的 {@link Frame} (定长数组, 每帧不分配);</li>
 *   <li>{@link #blockCorners} 把矩形胀成闭合的壳、浮出面外、按台子朝向摆进主格 (与计数屏同一个 {@link MunitionsBenchCounter#benchToBlock}),
 *       顶点顺序从面外看逆时针 (textBackground 剔除背面), 每个顶点带颜色;</li>
 *   <li>渲染器只乘面明暗 ({@code level.getShade(Direction.from3DDataValue(worldFace(...)), LIGHT_TARGET_SHADED[目标])})、
 *       α 按 {@link #alphaByte} 取整, 用 {@code LightTexture.FULL_BRIGHT} 画。</li>
 * </ul>
 * 三条硬约束 (lights.mjs 文件头有细节): 每个顶点的 α 要么 ≥ LIGHT_ALPHA_CUTOFF 要么整块不画 (着色器 discard α &lt; 0.1);
 * 任何两个覆盖四边形在同一块面上不重叠 (textBackground 按距离排序并写深度, 叠层会出鬼影; 呼吸与工位灯在 CPU 上先合成);
 * 颜色乘该面的静态明暗。
 * <p>
 * 刻意不依赖任何 Minecraft 类: 渲染器与 GameTest 直接用, 也能脱离游戏单独编译。
 * <b>与 tools/munitions_bench/lights.mjs 逐行对应 (lightFrame / levels / lightOverlays / overlayCorners / blockCorners / worldFace),
 * 改一边必须改另一边</b>; tools/munitions_bench/check_parity.mjs 单独编译本类与 JS 逐值对拍 (六档 × 工作 / 待机 / 满仓 × 每 0.25 tick,
 * 另加任意 float 的 partialTick)。
 */
public final class MunitionsBenchLights {

    /** 效果 (= 画的顺序 = LIGHT_UNLOCK_TIERS 的下标; {@link #effectMask} 的位 = 1 &lt;&lt; 效果)。 */
    public static final int EFFECT_CHASE = 0;
    public static final int EFFECT_BREATH = 1;
    public static final int EFFECT_LEDS = 2;
    public static final int EFFECT_STRIKE = 3;
    public static final int EFFECT_DROP = 4;
    public static final int EFFECT_FULL = 5;
    public static final int EFFECT_GEM = 6;
    public static final int EFFECT_COUNT = 7;

    /** 目标组 (LIGHT_GROUPS 的行): 前沿灯带 / 后护栏背光 / 压机横梁灯条 / 弹药箱下灯带 / 闪耀宝石。 */
    public static final int GROUP_STRIP = 0;
    public static final int GROUP_RAIL = 1;
    public static final int GROUP_CROWN = 2;
    public static final int GROUP_CAN = 3;
    public static final int GROUP_GEM = 4;

    /** 颜色角色 (LIGHT_COLOURS 的列)。 */
    public static final int ROLE_LED = 0;
    public static final int ROLE_FLASH_LO = 1;
    public static final int ROLE_PULSE = 2;
    public static final int ROLE_AMBER = 3;
    public static final int ROLE_GEM_FLASH = 4;
    public static final int ROLE_GEM_ECHO = 5;
    public static final int ROLE_BREATH_HI = 6;
    public static final int ROLE_HEAD = 7;
    public static final int ROLE_TROUGH = 8;

    /** 面 (LIGHT_TARGET_FACES 的值) = 原版 Direction.get3DDataValue(), 渲染器用 Direction.from3DDataValue 取回。 */
    public static final int FACE_DOWN = 0;
    public static final int FACE_UP = 1;
    public static final int FACE_NORTH = 2;
    public static final int FACE_SOUTH = 3;
    public static final int FACE_WEST = 4;
    public static final int FACE_EAST = 5;

    /** 一帧最多几个覆盖四边形 ({@link Frame} 的数组长度)。 */
    public static final int MAX_QUADS = MunitionsBenchGeometry.LIGHT_MAX_QUADS;
    /** {@link #benchCorners} / {@link #blockCorners} 每个四边形的 float 数: 角 4 × (x, y, z); 颜色 4 × (r, g, b 0..255, a 0..1)。 */
    public static final int CORNER_FLOATS = 12;
    public static final int COLOUR_FLOATS = 16;
    public static final int LED_COUNT = LIGHT_LED_X_PX.length;

    private static final int WHITE = 0xFFFFFF;
    /**
     * 每个面 (下标 = FACE_*) 的四个角在 x / y / z 上取胀过的矩形的 lo (0) 还是 hi (1), 顺序同 {@link #benchCorners} 注释里的表
     * (从面外看逆时针)。
     */
    private static final int[][] CORNER_PICK = {
            {0, 0, 1, 0, 0, 0, 1, 0, 0, 1, 0, 1}, // down
            {0, 1, 0, 0, 1, 1, 1, 1, 1, 1, 1, 0}, // up
            {1, 1, 0, 1, 0, 0, 0, 0, 0, 0, 1, 0}, // north
            {0, 1, 1, 0, 0, 1, 1, 0, 1, 1, 1, 1}, // south
            {0, 1, 0, 0, 0, 0, 0, 0, 1, 0, 1, 1}, // west
            {1, 1, 1, 1, 0, 1, 1, 0, 0, 1, 1, 0}, // east
    };
    /** 面外法线 (下标 = FACE_*)。 */
    private static final int[][] NORMALS = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};
    /** 水平面俯视顺时针一圈: 北 → 东 → 南 → 西。 */
    private static final int[] HORIZONTAL_RING = {FACE_NORTH, FACE_EAST, FACE_SOUTH, FACE_WEST};
    /** 前沿灯带组的第一个目标 = 北面 (切段按它的 x 范围走; lights.mjs 的 TI.strip_n); 后护栏组唯一的目标 (lights.mjs 的 TI.rail_b_n)。 */
    private static final int STRIP_FRONT = LIGHT_GROUPS[GROUP_STRIP][0];
    private static final int RAIL = LIGHT_GROUPS[GROUP_RAIL][0];
    private static final double EDGE_EPSILON = 1.0E-6D;

    private MunitionsBenchLights() {
    }

    /**
     * 一帧的输出与中间量, 调用方预先分配、逐帧复用 (渲染器一个就够: 方块实体渲染在渲染线程上一台一台画)。
     * 第 q 个四边形: 效果 {@code effect[q]}、目标 {@code target[q]}、矩形 {@code lo / hi[q × 3 ..]} (整台像素, 朝北, 胀之前)、
     * 渐变轴 {@code grad[q]} (0 x / 1 y / 2 z, -1 = 纯色; 轴上靠 lo 的一半用颜色 0, 靠 hi 的一半用颜色 1)、颜色 {@code rgb0 / alpha0, rgb1 / alpha1}。
     */
    public static final class Frame {
        public int count;
        /** 这一帧想画的比 MAX_QUADS 多 (多出的丢掉; 生成器与 GameTest 核对永远不会发生)。 */
        public boolean overflowed;
        public final int[] effect = new int[MAX_QUADS];
        public final int[] target = new int[MAX_QUADS];
        public final float[] lo = new float[MAX_QUADS * 3];
        public final float[] hi = new float[MAX_QUADS * 3];
        public final int[] grad = new int[MAX_QUADS];
        public final int[] rgb0 = new int[MAX_QUADS];
        public final int[] rgb1 = new int[MAX_QUADS];
        public final float[] alpha0 = new float[MAX_QUADS];
        public final float[] alpha1 = new float[MAX_QUADS];

        /**
         * 时钟 (tick, 已加 partialTick): 循环内的程序时刻 (0..40, 按档位映射过)、开工以来的程序时间、呼吸相位 (游戏时间)、待机时钟 (游戏时间);
         * 皮带位移 (px, 追光用)。
         */
        public double cycleTick;
        public double elapsed;
        public double breathTick;
        public double clockTick;
        public float beltX;

        /** 各效果此刻的标量 0..1 (与档位无关; breath 是呼吸层的 α): 工位灯 (按 LIGHT_LED_X_PX 的顺序)、冲压、落箱、满仓、宝石、宝石回响、呼吸、追光包络。 */
        public final double[] leds = new double[LED_COUNT];
        public double strike;
        public double drop;
        public double full;
        public double gem;
        public double gemEcho;
        public double breath;
        public double chase;

        private final MunitionsBenchProgram.Pose pose = new MunitionsBenchProgram.Pose();
    }

    // ---------------------------------------------------------------- 档位与颜色

    /** 这一档有的效果 (位 = 1 &lt;&lt; EFFECT_*): 解锁档位 ≤ 这一档; 档位越界按普通档。 */
    public static int effectMask(int tier) {
        int t = tier >= 0 && tier < LIGHT_COLOURS.length ? tier : 0;
        int mask = 0;
        for (int effect = 0; effect < EFFECT_COUNT; effect++) {
            if (t >= LIGHT_UNLOCK_TIERS[effect]) {
                mask |= 1 << effect;
            }
        }
        return mask;
    }

    /** 顶点 α (0..1) → 写进顶点的字节: round(a × 255)。a ≥ LIGHT_ALPHA_CUTOFF 时 ≥ 26 (26 / 255 &gt; 0.1, 不会被着色器丢掉)。 */
    public static int alphaByte(float alpha) {
        return Math.max(0, Math.min(255, Math.round(alpha * 255.0F)));
    }

    /**
     * 面 (FACE_*) 按台子朝向转到世界: 水平面按渲染器的朝向角 (= MunitionsBenchBlock.partsYRotationDegrees(朝向)) 俯视顺时针转
     * round(-yRotationDegrees / 90) 个 90° (北 → 东 → 南 → 西), 上下不变。朝北放的台子原样返回。
     */
    public static int worldFace(int face, float yRotationDegrees) {
        for (int i = 0; i < HORIZONTAL_RING.length; i++) {
            if (HORIZONTAL_RING[i] == face) {
                return HORIZONTAL_RING[Math.floorMod(i + Math.round(-yRotationDegrees / 90.0F), 4)];
            }
        }
        return face;
    }

    // ---------------------------------------------------------------- 一帧 (lights.mjs lightFrame + levels + lightOverlays)

    /**
     * 算这一帧的覆盖层, 写进 out, 返回四边形个数。
     *
     * @param tier           档位下标 0..5 (决定有哪些效果与颜色, 以及循环长度 = MunitionsBenchProgram.cycleTicks(tier); 越界按普通档)
     * @param active         方块状态 ACTIVE (工作)
     * @param full           同步来的满仓 (只在待机时闪)
     * @param elapsedTicks   开工以来的整 tick (游戏 tick − 程序起点, 与运动件相同; 待机时不用; 负数 = 起点比本地时钟快, 停在首帧)
     * @param gameTime       客户端的 level.getGameTime() (待机满仓闪烁的时钟)
     * @param partialTick    渲染的 partialTick
     * @param distanceBlocks 相机到主格中心的距离 (格; 运行呼吸在 LIGHT_FADE_START_BLOCKS..LIGHT_MAX_DISTANCE_BLOCKS 渐隐)
     */
    public static int compute(Frame out, int tier, boolean active, boolean full, long elapsedTicks, long gameTime,
                              float partialTick, double distanceBlocks) {
        long e = active ? elapsedTicks : 0L;
        float p = partialTick;
        if (e < 0L) {
            e = 0L;
            p = 0.0F;
        }
        // 程序时间 = 运动件的 MunitionsBenchProgram.programTick (按档位的循环长度映射到 40 tick 的程序时间, 先在 long 里取模再加小数):
        // 脉冲、工位灯、追光包络都定义在程序时间里, 跟着运动件一起按档位变快。时钟全在 double 里加 (整 tick + float 小数在 double 里是
        // 精确的, = lights.mjs): 若循环 tick 按 float 加, 取整后可能比 elapsed 大一个 ulp, 第一轮开工时底火灯 (峰 0) 的 d ≤ elapsed 就会
        // 误判成 "没发生过" 而闪断; elapsed 与 cycle 按同一个顺序 (先加、再乘、再除) 映射, 第一轮里两者逐位相同。
        // 皮带取样用 (float) cycle, 与运动件的 sample(long, float, tier, Pose) 逐位相同。呼吸与满仓闪烁是游戏时间, 不随档位变快。
        double cycle = MunitionsBenchProgram.programTick(e, p, tier);
        out.cycleTick = cycle;
        out.elapsed = ((double) e + p) * MunitionsBenchProgram.CYCLE_TICKS / MunitionsBenchProgram.cycleTicks(tier);
        out.breathTick = Math.floorMod(e, (long) LIGHT_BREATH_PERIOD_TICKS) + (double) p;
        out.clockTick = Math.floorMod(gameTime, (long) LIGHT_FULL_BLINK_PERIOD_TICKS) + (double) partialTick;
        out.beltX = active ? MunitionsBenchProgram.sample((float) cycle, out.pose).beltX : 0.0F;
        levels(out, active, full);
        overlays(out, tier, distanceBlocks);
        return out.count;
    }

    /** 各效果此刻的标量 (lights.mjs levels)。 */
    private static void levels(Frame f, boolean active, boolean full) {
        double t = f.cycleTick;
        double el = f.elapsed;
        for (int i = 0; i < LED_COUNT; i++) {
            f.leds[i] = 0.0D;
        }
        f.strike = 0.0D;
        f.drop = 0.0D;
        f.full = 0.0D;
        f.gem = 0.0D;
        f.gemEcho = 0.0D;
        f.breath = 0.0D;
        f.chase = 0.0D;
        if (active) {
            for (int i = 0; i < LED_COUNT; i++) {
                f.leds[i] = pulse(t, LIGHT_LED_PEAK_TICKS[i], LIGHT_LED_PULSE[0], LIGHT_LED_PULSE[1], el);
            }
            f.strike = pulse(t, LIGHT_STRIKE_PULSE[0], LIGHT_STRIKE_PULSE[1], LIGHT_STRIKE_PULSE[2], el);
            f.drop = pulse(t, LIGHT_DROP_PULSE[0], LIGHT_DROP_PULSE[1], LIGHT_DROP_PULSE[2], el);
            f.gem = pulse(t, LIGHT_GEM_PULSE[0], LIGHT_GEM_PULSE[1], LIGHT_GEM_PULSE[2], el);
            f.gemEcho = LIGHT_GEM_ECHO_LEVEL * pulse(t, LIGHT_GEM_ECHO_PULSE[0], LIGHT_GEM_ECHO_PULSE[1], LIGHT_GEM_ECHO_PULSE[2], el);
            // 呼吸: 起点最亮, α LIGHT_BREATH_ALPHA[0]..[1] 往 hi 拉 (只往亮的一侧)。StrictMath = fdlibm, 与 JS 的 Math.cos 逐位相同
            f.breath = LIGHT_BREATH_ALPHA[0] + (LIGHT_BREATH_ALPHA[1] - LIGHT_BREATH_ALPHA[0])
                    * (0.5D + 0.5D * StrictMath.cos(2.0D * Math.PI * f.breathTick / LIGHT_BREATH_PERIOD_TICKS));
            // 追光: 只在皮带步进时亮, 前后淡入淡出
            f.chase = ramp(t, LIGHT_CHASE_WINDOW[0], LIGHT_CHASE_WINDOW[1]) * (1.0D - ramp(t, LIGHT_CHASE_WINDOW[2], LIGHT_CHASE_WINDOW[3]));
        } else if (full) {
            // 满仓: 梯形闪
            double c = wrap(f.clockTick, LIGHT_FULL_BLINK_PERIOD_TICKS);
            f.full = ramp(c, LIGHT_FULL_BLINK_RAMP[0], LIGHT_FULL_BLINK_RAMP[1])
                    * (1.0D - ramp(c, LIGHT_FULL_BLINK_RAMP[2], LIGHT_FULL_BLINK_RAMP[3]));
        }
    }

    /** 此刻要画的覆盖层, 顺序固定 (lights.mjs lightOverlays): 追光 → 前沿灯带 (呼吸 + 工位灯) → 冲压 → 落箱 → 满仓 → 宝石。 */
    private static void overlays(Frame f, int tier, double distanceBlocks) {
        f.count = 0;
        f.overflowed = false;
        int row = tier >= 0 && tier < LIGHT_COLOURS.length ? tier : 0;
        int mask = effectMask(row);
        int[] pal = LIGHT_COLOURS[row];
        if (on(mask, EFFECT_CHASE) && f.chase > 0.0D) {
            chaseRects(f, f.chase, f.beltX, pal);
        }
        double b = on(mask, EFFECT_BREATH) ? f.breath * distanceFade(distanceBlocks) : 0.0D;
        if (b < LIGHT_ALPHA_CUTOFF) {
            b = 0.0D;
        }
        stripRects(f, b, on(mask, EFFECT_LEDS), pal);
        if (on(mask, EFFECT_STRIKE)) {
            double a = StrictMath.sqrt(f.strike); // = 6 tick 线性衰减
            if (a >= LIGHT_ALPHA_CUTOFF) {
                group(f, EFFECT_STRIKE, GROUP_CROWN, mix(pal[ROLE_FLASH_LO], WHITE, f.strike), a);
            }
        }
        if (on(mask, EFFECT_DROP) && LIGHT_DROP_ALPHA * f.drop >= LIGHT_ALPHA_CUTOFF) {
            group(f, EFFECT_DROP, GROUP_CAN, pal[ROLE_PULSE], LIGHT_DROP_ALPHA * f.drop);
        }
        if (on(mask, EFFECT_FULL) && LIGHT_FULL_ALPHA * f.full >= LIGHT_ALPHA_CUTOFF) {
            group(f, EFFECT_FULL, GROUP_CAN, pal[ROLE_AMBER], LIGHT_FULL_ALPHA * f.full);
        }
        if (on(mask, EFFECT_GEM)) {
            boolean flash = f.gem >= f.gemEcho;
            double g = flash ? f.gem : f.gemEcho;
            if (g >= LIGHT_ALPHA_CUTOFF) {
                group(f, EFFECT_GEM, GROUP_GEM, flash ? pal[ROLE_GEM_FLASH] : pal[ROLE_GEM_ECHO], g);
            }
        }
    }

    /**
     * 前沿灯带 (北面 + 顶面): 呼吸 α = b (0 = 关), 工位灯 α = 各自的脉冲。按 x 从小到大走, 亮着的工位灯各切一段, 其余是呼吸段 ——
     * 每一点只被一个四边形盖住。工位灯那段 = 呼吸再盖工位灯, 在 CPU 上先合成: A = 1 − (1 − b)(1 − I), rgb = (I·led + (1 − I)·b·breathHi) / A。
     */
    private static void stripRects(Frame f, double b, boolean ledsOn, int[] pal) {
        float[] strip = LIGHT_TARGET_RECTS_PX[STRIP_FRONT];
        double end = strip[3];
        double x = strip[0];
        int led = pal[ROLE_LED];
        int breathHi = pal[ROLE_BREATH_HI];
        for (int i = 0; i < LED_COUNT; i++) {
            double level = ledsOn ? f.leds[i] : 0.0D;
            double a = 1.0D - (1.0D - b) * (1.0D - level);
            if (level < LIGHT_LED_MERGE_EPSILON || a < LIGHT_ALPHA_CUTOFF) {
                continue;
            }
            double x0 = LIGHT_LED_X_PX[i] - LIGHT_LED_WIDTH_PX / 2.0D;
            double x1 = LIGHT_LED_X_PX[i] + LIGHT_LED_WIDTH_PX / 2.0D;
            if (b > 0.0D && x0 > x) {
                stripSegment(f, EFFECT_BREATH, x, x0, breathHi, b);
            }
            int red = (int) Math.round((level * (led >> 16 & 0xFF) + (1.0D - level) * b * (breathHi >> 16 & 0xFF)) / a);
            int green = (int) Math.round((level * (led >> 8 & 0xFF) + (1.0D - level) * b * (breathHi >> 8 & 0xFF)) / a);
            int blue = (int) Math.round((level * (led & 0xFF) + (1.0D - level) * b * (breathHi & 0xFF)) / a);
            stripSegment(f, EFFECT_LEDS, x0, x1, red << 16 | green << 8 | blue, a);
            x = x1;
        }
        if (b > 0.0D && end > x) {
            stripSegment(f, EFFECT_BREATH, x, end, breathHi, b);
        }
    }

    /** 前沿灯带组的每个目标上 x0..x1 一段 (面上的另外两根轴取目标自己的范围)。 */
    private static void stripSegment(Frame f, int effect, double x0, double x1, int rgb, double alpha) {
        for (int t : LIGHT_GROUPS[GROUP_STRIP]) {
            float[] r = LIGHT_TARGET_RECTS_PX[t];
            push(f, effect, t, x0, r[1], r[2], x1, r[4], r[5], -1, rgb, alpha, rgb, alpha);
        }
    }

    /**
     * 追光: 后护栏背光北面的彗星 (头在 -x 端, 尾往 +x 渐暗), 周期 = 皮带节距, 与皮带同速。头与暗槽的 α 都要 ≥ 阈值才画
     * (彗尾是头 → 暗槽的顶点色渐变, 一端低于 0.1 就会被着色器截掉半截), 所以按暗槽 (较小的那个) 判。
     */
    private static void chaseRects(Frame f, double env, float beltX, int[] pal) {
        float[] r = LIGHT_TARGET_RECTS_PX[RAIL];
        double x0r = r[0];
        double x1r = r[3];
        double pitch = MunitionsBenchProgram.BELT_PITCH;
        double tail = LIGHT_CHASE_TAIL_PX;
        double headAlpha = LIGHT_CHASE_ALPHA[0] * env;
        double troughAlpha = LIGHT_CHASE_ALPHA[1] * env;
        if (troughAlpha < LIGHT_ALPHA_CUTOFF) {
            return;
        }
        int head = pal[ROLE_HEAD];
        int trough = pal[ROLE_TROUGH];
        double shift = LIGHT_CHASE_SPEED * beltX; // beltX 0 .. -BELT_PITCH, 图案往 -x 走
        double first = x0r + wrap(shift, pitch) - pitch; // 第一颗彗星的头 (≤ x0r)
        for (double h = first; h < x1r; h += pitch) {
            double g0 = Math.max(h, x0r);
            double g1 = Math.min(h + tail, x1r);
            if (g1 > g0 + EDGE_EPSILON) {
                double k0 = (g0 - h) / tail;
                double k1 = (g1 - h) / tail;
                push(f, EFFECT_CHASE, RAIL, g0, r[1], r[2], g1, r[4], r[5], 0,
                        mix(head, trough, k0), headAlpha + (troughAlpha - headAlpha) * k0,
                        mix(head, trough, k1), headAlpha + (troughAlpha - headAlpha) * k1);
            }
            double t0 = Math.max(h + tail, x0r);
            double t1 = Math.min(h + pitch, x1r);
            if (t1 > t0 + EDGE_EPSILON) {
                push(f, EFFECT_CHASE, RAIL, t0, r[1], r[2], t1, r[4], r[5], -1, trough, troughAlpha, trough, troughAlpha);
            }
        }
    }

    /** 一组目标整块同一个颜色。 */
    private static void group(Frame f, int effect, int group, int rgb, double alpha) {
        for (int t : LIGHT_GROUPS[group]) {
            float[] r = LIGHT_TARGET_RECTS_PX[t];
            push(f, effect, t, r[0], r[1], r[2], r[3], r[4], r[5], -1, rgb, alpha, rgb, alpha);
        }
    }

    private static void push(Frame f, int effect, int target, double x0, double y0, double z0, double x1, double y1, double z1,
                             int grad, int rgb0, double alpha0, int rgb1, double alpha1) {
        if (f.count >= MAX_QUADS) {
            f.overflowed = true;
            return;
        }
        int q = f.count++;
        f.effect[q] = effect;
        f.target[q] = target;
        f.lo[q * 3] = (float) x0;
        f.lo[q * 3 + 1] = (float) y0;
        f.lo[q * 3 + 2] = (float) z0;
        f.hi[q * 3] = (float) x1;
        f.hi[q * 3 + 1] = (float) y1;
        f.hi[q * 3 + 2] = (float) z1;
        f.grad[q] = grad;
        f.rgb0[q] = rgb0;
        f.alpha0[q] = (float) alpha0;
        f.rgb1[q] = rgb1;
        f.alpha1[q] = (float) alpha1;
    }

    private static boolean on(int mask, int effect) {
        return (mask & 1 << effect) != 0;
    }

    // ---------------------------------------------------------------- 曲线 (lights.mjs wrap / ramp / pulse / distanceFade)

    /**
     * t 折回 [0, p)。% (fmod) 本身是精确的: 非负的 t 原样取余, 负数再加一个 p (极小的负数加 p 可能舍入成 p, 调用方都按连续处理)。
     * 不写成 (t % p + p) % p: 按档位映射过的程序时间有满 53 位尾数, 先加 p 会舍掉末位, 第一轮开工时脉冲的 d 比 elapsed 大一个 ulp,
     * 底火灯 (峰 0) 被误判成 "没发生过" 而闪断 (GameTest benchLightPulsesPeakOnTheProgramBeats 在中级档抓到的)。
     */
    public static double wrap(double t, double p) {
        double r = t % p;
        return r < 0.0D ? r + p : r;
    }

    /** smoothstep(a, b, t) (a &lt; b)。 */
    public static double ramp(double t, double a, double b) {
        double k = (t - a) / (b - a);
        k = k < 0.0D ? 0.0D : k > 1.0D ? 1.0D : k;
        return k * k * (3.0D - 2.0D * k);
    }

    /**
     * 一次脉冲, 峰在 peak (循环 tick): 前 attack tick 二次缓入, 后 decay tick 二次衰减; 相位按 CYCLE_TICKS 折回。
     * elapsed = 程序时间: 峰已经过去 d tick 但程序才跑了不到 d tick (第一轮开工时跨接缝的尾巴) → 这一拍没发生过, 返回 0。
     */
    public static double pulse(double t, double peak, double attack, double decay, double elapsed) {
        double d = wrap(t - peak, MunitionsBenchProgram.CYCLE_TICKS);
        if (d <= decay) {
            if (d > elapsed) {
                return 0.0D;
            }
            double k = 1.0D - d / decay;
            return k * k;
        }
        double e = d - MunitionsBenchProgram.CYCLE_TICKS;
        if (attack > 0.0D && e >= -attack) {
            double k = 1.0D + e / attack;
            return k * k;
        }
        return 0.0D;
    }

    /** 呼吸的距离渐隐: LIGHT_FADE_START_BLOCKS 格起淡出, LIGHT_MAX_DISTANCE_BLOCKS 格为 0。 */
    public static double distanceFade(double distanceBlocks) {
        return 1.0D - ramp(distanceBlocks, LIGHT_FADE_START_BLOCKS, LIGHT_MAX_DISTANCE_BLOCKS);
    }

    /** 两个 0xRRGGBB 按 t 线性混合, 每个通道四舍五入 (lights.mjs MIX)。 */
    private static int mix(int a, int b, double t) {
        int red = (int) Math.round((a >> 16 & 0xFF) + ((b >> 16 & 0xFF) - (a >> 16 & 0xFF)) * t);
        int green = (int) Math.round((a >> 8 & 0xFF) + ((b >> 8 & 0xFF) - (a >> 8 & 0xFF)) * t);
        int blue = (int) Math.round((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
        return red << 16 | green << 8 | blue;
    }

    // ---------------------------------------------------------------- 角 (lights.mjs overlayCorners / blockCorners)

    /**
     * 每个四边形的四个角 (整台像素, 朝北) 与四个顶点色, 写进 corners[q × CORNER_FLOATS ..] / colours[q × COLOUR_FLOATS ..]:
     * 面内按目标的 grow 往外胀 lift (相邻两个覆盖面在棱上接成闭合的壳), 再沿面外法线浮出 lift; 顶点顺序从面外看逆时针
     * (textBackground 剔除背面, 反了整块消失):
     * <pre>
     *   north: (x1,y1) (x1,y0) (x0,y0) (x0,y1)   south: (x0,y1) (x0,y0) (x1,y0) (x1,y1)
     *   east:  (z1,y1) (z1,y0) (z0,y0) (z0,y1)   west:  (z0,y1) (z0,y0) (z1,y0) (z1,y1)
     *   up:    (x0,z0) (x0,z1) (x1,z1) (x1,z0)   down:  (x0,z1) (x0,z0) (x1,z0) (x1,z1)
     * </pre>
     * 顶点色: 纯色两套相同; 有渐变轴时, 轴上靠 lo 那一半 (≤ 中点) 的顶点取颜色 0, 另一半取颜色 1。
     */
    public static void benchCorners(Frame f, float[] corners, float[] colours) {
        for (int q = 0; q < f.count; q++) {
            int t = f.target[q];
            int face = LIGHT_TARGET_FACES[t];
            double lift = LIGHT_TARGET_LIFT_PX[t];
            int[] g = LIGHT_TARGET_GROW[t];
            // 胀过的矩形 (lights.mjs: lo = lo − grow[2a] × lift, hi = hi + grow[2a + 1] × lift)
            double x0 = f.lo[q * 3] - g[0] * lift;
            double y0 = f.lo[q * 3 + 1] - g[2] * lift;
            double z0 = f.lo[q * 3 + 2] - g[4] * lift;
            double x1 = f.hi[q * 3] + g[1] * lift;
            double y1 = f.hi[q * 3 + 1] + g[3] * lift;
            double z1 = f.hi[q * 3 + 2] + g[5] * lift;
            int axis = f.grad[q];
            double mid = axis < 0 ? 0.0D : (pick(axis, 0, x0, y0, z0, x1, y1, z1) + pick(axis, 1, x0, y0, z0, x1, y1, z1)) / 2.0D;
            int[] corner = CORNER_PICK[face];
            int[] normal = NORMALS[face];
            for (int k = 0; k < 4; k++) {
                double x = corner[k * 3] == 0 ? x0 : x1;
                double y = corner[k * 3 + 1] == 0 ? y0 : y1;
                double z = corner[k * 3 + 2] == 0 ? z0 : z1;
                boolean first = axis < 0 || pick(axis, corner[k * 3 + axis], x0, y0, z0, x1, y1, z1) <= mid;
                int rgb = first ? f.rgb0[q] : f.rgb1[q];
                int o = q * COLOUR_FLOATS + k * 4;
                colours[o] = rgb >> 16 & 0xFF;
                colours[o + 1] = rgb >> 8 & 0xFF;
                colours[o + 2] = rgb & 0xFF;
                colours[o + 3] = first ? f.alpha0[q] : f.alpha1[q];
                // 沿面外法线浮出
                int p = q * CORNER_FLOATS + k * 3;
                corners[p] = (float) (x + normal[0] * lift);
                corners[p + 1] = (float) (y + normal[1] * lift);
                corners[p + 2] = (float) (z + normal[2] * lift);
            }
        }
    }

    /** 胀过的矩形在 axis 轴上的 lo (side 0) 或 hi (side 1)。 */
    private static double pick(int axis, int side, double x0, double y0, double z0, double x1, double y1, double z1) {
        return switch (axis) {
            case 0 -> side == 0 ? x0 : x1;
            case 1 -> side == 0 ? y0 : y1;
            default -> side == 0 ? z0 : z1;
        };
    }

    /**
     * {@link #benchCorners} 再按台子朝向摆进主格 (方块坐标, 主格西北下角 = 原点; 与计数屏同一个 {@link MunitionsBenchCounter#benchToBlock}),
     * 渲染器原样交给 BER 的 poseStack。corners / colours 至少 count × CORNER_FLOATS / COLOUR_FLOATS 长 (用 MAX_QUADS 预分配)。
     */
    public static void blockCorners(Frame f, float yRotationDegrees, float[] corners, float[] colours) {
        benchCorners(f, corners, colours);
        MunitionsBenchCounter.benchToBlock(corners, 0, f.count * CORNER_FLOATS, yRotationDegrees);
    }

}
