package com.miningdim.job.munitions.block;

/**
 * 枪械组装台机械臂的取放 + 点焊程序: "程序时间 → 姿态" 的纯数学部分。
 * <p>
 * 刻意不依赖任何 Minecraft 类: 客户端渲染器按它摆姿态、在零件接触点放焊花, 服务端按同一张表的点焊时刻播焊接音,
 * 两边读同一份数据才不会音画错位; 也因此能脱离游戏单独编译, 与 tools/gunsmith_workstation 的 JS 预览逐 tick 对拍。
 * <p>
 * 关键帧表与几何常量由 tools/gunsmith_workstation/generate_assembly_bench.mjs 按真实模型反解、做完整程序的穿模扫描后写入,
 * 改动作请改生成器, 不要手改生成区块。坐标系: 朝北放置时的整台 (2x2) 局部像素, x 东、y 上、z 南, 原点在主格西北下角。
 */
public final class GunsmithArmProgram {

    /** 一轮程序的长度; 与 {@link GunsmithAssemblyBenchBlockEntity#ASSEMBLY_DURATION_TICKS} 一致, 正常装配/维修只跑一轮。 */
    public static final int CYCLE_TICKS = 160;
    public static final int PAYLOAD_NONE = 0;

    // <generated> 由 tools/gunsmith_workstation/generate_assembly_bench.mjs 写入, 不要手改
    static final float PIVOT_X = 26.5F;
    static final float PIVOT_Y = 11.5F;
    static final float PIVOT_Z = 23.5F;
    static final float JOINT_UP = 4.0F;
    static final float UPPER_ARM_LENGTH = 7.0F;
    static final float FOREARM_LENGTH = 8.0F;
    static final float TIP_DROP = 6.0F;
    public static final int PAYLOAD_BOLT = 1;
    public static final int PAYLOAD_STOCK = 2;
    private static final float[][] KEYFRAMES = {
            // tick,     yaw, upperArm,  forearm, toolSpin,     claw, payload, spark, linear
            {   0,     0.0F,  0.1704F,   1.023F,     0.0F,   -0.26F,       0,     0,      0}, // 待机
            {   2,     0.0F,  0.1704F,   1.023F,     0.0F,   -0.26F,       0,     0,      0}, // 待机
            {   6,     0.0F,  0.2153F,  1.6084F,     0.0F,   -0.26F,       0,     0,      1}, // 抬离换刀座
            {   8,     0.0F,  0.2153F,  1.6084F,     0.0F,   -0.26F,       0,     0,      0}, // 停顿
            {  16, -0.6947F, -0.0035F,  1.8064F, -0.8761F,    0.22F,       0,     0,      2}, // 转向送料盘 · 枪机
            {  18, -0.6947F, -0.0035F,  1.8064F, -0.8761F,    0.22F,       0,     0,      0}, // 停顿
            {  22, -0.6947F, -0.1559F,   1.154F, -0.8761F,    0.22F,       0,     0,      1}, // 下探取件
            {  24, -0.6947F, -0.1559F,   1.154F, -0.8761F,    0.22F,       0,     0,      0}, // 停顿
            {  27, -0.6947F, -0.1559F,   1.154F, -0.8761F,     0.0F,       0,     0,      0}, // 夹取枪机
            {  29, -0.6947F, -0.1559F,   1.154F, -0.8761F,     0.0F,       1,     0,      0}, // 夹紧枪机
            {  33, -0.6947F, -0.0035F,  1.8064F, -0.8761F,     0.0F,       1,     0,      1}, // 竖直抬起
            {  35, -0.6947F, -0.0035F,  1.8064F, -0.8761F,     0.0F,       1,     0,      0}, // 停顿
            {  43, -1.0099F, -0.7129F,  2.7428F, -0.5609F,     0.0F,       1,     0,      2}, // 转运到机匣上方
            {  46, -1.0099F, -0.7129F,  2.7428F, -0.5609F,     0.0F,       1,     0,      0}, // 停顿
            {  50, -1.0099F, -0.5654F,  2.1274F, -0.5609F,     0.0F,       1,     0,      1}, // 下探就位
            {  52, -1.0099F, -0.5654F,  2.1274F, -0.5609F,     0.0F,       1,     0,      0}, // 停顿
            {  56, -1.0099F, -0.5654F,  2.1274F, -0.5609F,     0.0F,       1,     1,      0}, // 点焊
            {  58, -1.0099F, -0.5654F,  2.1274F, -0.5609F,     0.0F,       1,     0,      0}, // 停顿
            {  62, -1.0099F, -0.5654F,  2.1274F, -0.5609F,     0.0F,       1,     1,      0}, // 点焊
            {  64, -1.0099F, -0.5654F,  2.1274F, -0.5609F,     0.0F,       1,     0,      0}, // 停顿
            {  67, -1.0099F, -0.5654F,  2.1274F, -0.5609F,    0.22F,       0,     0,      0}, // 松开 · 枪机已装上
            {  69, -1.0099F, -0.5654F,  2.1274F, -0.5609F,    0.22F,       0,     0,      0}, // 停顿
            {  73, -1.0099F, -0.7129F,  2.7428F, -0.5609F,    0.22F,       0,     0,      1}, // 竖直抬起
            {  75, -1.0099F, -0.7129F,  2.7428F, -0.5609F,    0.22F,       0,     0,      0}, // 停顿
            {  83, -0.9273F,  0.2153F,  1.6084F, -0.6435F,    0.22F,       0,     0,      2}, // 转向送料盘 · 枪托件
            {  85, -0.9273F,  0.2153F,  1.6084F, -0.6435F,    0.22F,       0,     0,      0}, // 停顿
            {  89, -0.9273F,  0.0639F,  0.9281F, -0.6435F,    0.22F,       0,     0,      1}, // 下探取件
            {  91, -0.9273F,  0.0639F,  0.9281F, -0.6435F,    0.22F,       0,     0,      0}, // 停顿
            {  94, -0.9273F,  0.0639F,  0.9281F, -0.6435F,     0.0F,       0,     0,      0}, // 夹取枪托件
            {  96, -0.9273F,  0.0639F,  0.9281F, -0.6435F,     0.0F,       2,     0,      0}, // 夹紧枪托件
            { 100, -0.9273F,  0.2153F,  1.6084F, -0.6435F,     0.0F,       2,     0,      1}, // 竖直抬起
            { 102, -0.9273F,  0.2153F,  1.6084F, -0.6435F,     0.0F,       2,     0,      0}, // 停顿
            { 110, -1.4212F, -0.3482F,  2.2054F, -0.1496F,     0.0F,       2,     0,      2}, // 转运到枪托上方
            { 113, -1.4212F, -0.3482F,  2.2054F, -0.1496F,     0.0F,       2,     0,      0}, // 停顿
            { 117, -1.4212F, -0.3079F,  1.7571F, -0.1496F,     0.0F,       2,     0,      1}, // 下探就位
            { 119, -1.4212F, -0.3079F,  1.7571F, -0.1496F,     0.0F,       2,     0,      0}, // 停顿
            { 123, -1.4212F, -0.3079F,  1.7571F, -0.1496F,     0.0F,       2,     1,      0}, // 点焊
            { 125, -1.4212F, -0.3079F,  1.7571F, -0.1496F,     0.0F,       2,     0,      0}, // 停顿
            { 129, -1.4212F, -0.3079F,  1.7571F, -0.1496F,     0.0F,       2,     1,      0}, // 点焊
            { 131, -1.4212F, -0.3079F,  1.7571F, -0.1496F,     0.0F,       2,     0,      0}, // 停顿
            { 134, -1.4212F, -0.3079F,  1.7571F, -0.1496F,    0.22F,       0,     0,      0}, // 松开 · 枪托件已装上
            { 136, -1.4212F, -0.3079F,  1.7571F, -0.1496F,    0.22F,       0,     0,      0}, // 停顿
            { 140, -1.4212F, -0.3482F,  2.2054F, -0.1496F,    0.22F,       0,     0,      1}, // 竖直抬起
            { 142, -1.4212F, -0.3482F,  2.2054F, -0.1496F,    0.22F,       0,     0,      0}, // 停顿
            { 150,     0.0F,  0.2153F,  1.6084F,     0.0F,   -0.26F,       0,     0,      2}, // 返回换刀座上方
            { 152,     0.0F,  0.2153F,  1.6084F,     0.0F,   -0.26F,       0,     0,      0}, // 停顿
            { 156,     0.0F,  0.1704F,   1.023F,     0.0F,   -0.26F,       0,     0,      1}, // 落回换刀座
            { 160,     0.0F,  0.1704F,   1.023F,     0.0F,   -0.26F,       0,     0,      0}, // 待机
    };
    // </generated>

    private static final int COL_TICK = 0;
    private static final int COL_YAW = 1;
    private static final int COL_UPPER_ARM = 2;
    private static final int COL_FOREARM = 3;
    private static final int COL_TOOL_SPIN = 4;
    private static final int COL_CLAW = 5;
    private static final int COL_PAYLOAD = 6;
    private static final int COL_SPARK = 7;
    private static final int COL_LINEAR = 8;
    private static final int[] WELD_START_TICKS = weldStartTicks();
    /** 从这一 tick 起手臂已停回待机姿态直到一轮结束; 客户端在此之前被切回待机时要缓回去, 之后可以直接切。 */
    public static final int PARKED_TICK = parkedTick();

    private GunsmithArmProgram() {
    }

    /** 某一时刻的姿态。手腕角不在其中: 渲染时按 -(大臂 + 小臂) 推导, 让工具始终竖直。 */
    public static final class Pose {
        public float yaw;
        public float upperArm;
        public float forearm;
        public float toolSpin;
        /** 左爪 zRot, 右爪取反: 负 = 爪尖内收, 0 = 平行夹住零件, 正 = 张开。 */
        public float claw;
        public int payload;
        public boolean spark;
    }

    /**
     * 程序时间 (tick, 可带小数) 处的姿态。超过一轮按 {@link #CYCLE_TICKS} 取模: 正常只跑一轮, 取模只是防御。
     * 连续量在相邻关键帧间按 smoothstep 插值, 两行相同即停顿; 携带件与火花取 "到达的那一行" 的值
     * (生成器把夹取拆成 "爪子合拢" 与 "带件停顿" 两行, 零件在爪子合拢到位后才出现; 松开那一段零件已经算装上)。
     * <p>
     * 关节角直接插值会让腕部走弧线: 转运时先上浮再落回, 下探时边降边往外够, 看起来像在蠕动。
     * 所以凡是手臂真的在动的段落 (linear != 0) 都改在柱坐标里插值: 偏航、水平伸出、高度各自缓动, 每一时刻反解大臂/小臂角。
     * linear = 1 的段落偏航不变, 腕部走竖直直线 (下探/抬起); linear = 2 的段落高度不变, 腕部在安全高度上平移 (转运)。
     * 两种走的是同一套计算, 区别只在生成器给的两端点, 所以这里不再区分。
     */
    public static Pose sample(float programTick, Pose out) {
        float t = programTick % CYCLE_TICKS;
        if (t < 0.0F) {
            t += CYCLE_TICKS;
        }
        if (t <= 0.0F) {
            return copyRow(KEYFRAMES[0], out);
        }
        int i = 1;
        while (KEYFRAMES[i][COL_TICK] < t) {
            i++;
        }
        float[] a = KEYFRAMES[i - 1];
        float[] b = KEYFRAMES[i];
        float s = (t - a[COL_TICK]) / (b[COL_TICK] - a[COL_TICK]);
        float e = s * s * (3.0F - 2.0F * s);
        out.yaw = a[COL_YAW] + (b[COL_YAW] - a[COL_YAW]) * e;
        out.upperArm = a[COL_UPPER_ARM] + (b[COL_UPPER_ARM] - a[COL_UPPER_ARM]) * e;
        out.forearm = a[COL_FOREARM] + (b[COL_FOREARM] - a[COL_FOREARM]) * e;
        out.toolSpin = a[COL_TOOL_SPIN] + (b[COL_TOOL_SPIN] - a[COL_TOOL_SPIN]) * e;
        out.claw = a[COL_CLAW] + (b[COL_CLAW] - a[COL_CLAW]) * e;
        out.payload = (int) b[COL_PAYLOAD];
        out.spark = b[COL_SPARK] != 0.0F;
        if (b[COL_LINEAR] != 0.0F) {
            solveCylindrical(a, b, e, out);
        }
        return out;
    }

    /**
     * 水平伸出与高度在 a、b 两帧之间按比例 e 插值, 再在手臂所在的竖直平面内反解大臂/小臂角。
     * 偏航已由 sample() 按同一个 e 插好, 与这里互不影响: 伸出与高度都是在手臂平面里量的, 不随偏航变化。
     */
    private static void solveCylindrical(float[] a, float[] b, float e, Pose out) {
        double reachA = planarReach(a[COL_UPPER_ARM], a[COL_FOREARM]);
        double heightA = planarHeight(a[COL_UPPER_ARM], a[COL_FOREARM]);
        double reach = reachA + (planarReach(b[COL_UPPER_ARM], b[COL_FOREARM]) - reachA) * e;
        double height = heightA + (planarHeight(b[COL_UPPER_ARM], b[COL_FOREARM]) - heightA) * e;
        // 平面两连杆反解: 肘点 = 以大臂关节为圆心、以腕点为圆心的两圆交点, 取较高的那个 (肘朝上, 与生成器一致)
        double dx = -reach;
        double dy = height;
        double d = Math.sqrt(dx * dx + dy * dy);
        double l1 = UPPER_ARM_LENGTH;
        double l2 = FOREARM_LENGTH;
        if (d > l1 + l2 - 0.05D || d < Math.abs(l1 - l2) + 0.05D) {
            return;   // 不可达时保留关节插值的结果; 生成器已验证两端与中途都可达, 这里只是防御
        }
        double along = (l1 * l1 - l2 * l2 + d * d) / (2.0D * d);
        double across = Math.sqrt(Math.max(0.0D, l1 * l1 - along * along));
        double ux = dx / d;
        double uy = dy / d;
        double ex = along * ux - across * uy;
        double ey = along * uy + across * ux;
        double ex2 = along * ux + across * uy;
        double ey2 = along * uy - across * ux;
        if (ey2 >= ey) {
            ex = ex2;
            ey = ey2;
        }
        double upper = Math.atan2(ex, ey);
        double forearm = Math.atan2(ex - dx, ey - dy) - upper;
        while (forearm > Math.PI) {
            forearm -= 2.0D * Math.PI;
        }
        while (forearm <= -Math.PI) {
            forearm += 2.0D * Math.PI;
        }
        out.upperArm = (float) upper;
        out.forearm = (float) forearm;
    }

    /** 腕部相对大臂关节的水平伸出量 (沿手臂所在竖直平面, 向外为正)。 */
    private static double planarReach(double upper, double forearm) {
        return -(UPPER_ARM_LENGTH * Math.sin(upper) - FOREARM_LENGTH * Math.sin(upper + forearm));
    }

    /** 腕部相对大臂关节的高度。 */
    private static double planarHeight(double upper, double forearm) {
        return UPPER_ARM_LENGTH * Math.cos(upper) - FOREARM_LENGTH * Math.cos(upper + forearm);
    }

    /** 不在装配时的静止姿态 (抓手停在换刀座上方), 即程序首帧。 */
    public static Pose idle(Pose out) {
        return copyRow(KEYFRAMES[0], out);
    }

    /**
     * 动画开始后 elapsed tick 之后 (严格大于) 的下一个点焊起点, 同样以 tick 计、从动画开始算起, 超过一轮顺延到下一轮。
     * 程序里没有点焊时返回 -1。
     */
    public static long nextWeldTickAfter(long elapsed) {
        if (WELD_START_TICKS.length == 0) {
            return -1L;
        }
        long cycleStart = Math.floorDiv(elapsed, (long) CYCLE_TICKS) * CYCLE_TICKS;
        long within = elapsed - cycleStart;
        for (int tick : WELD_START_TICKS) {
            if (tick > within) {
                return cycleStart + tick;
            }
        }
        return cycleStart + CYCLE_TICKS + WELD_START_TICKS[0];
    }

    /** 腕部枢轴位置 (朝北整台局部像素), out = {x, y, z}。与 ModelPart 层级 (肩座偏航 → 大臂 → 小臂) 同一套正向运动学。 */
    public static float[] wristPosition(Pose pose, float[] out) {
        double u = pose.upperArm;
        double uf = pose.upperArm + pose.forearm;
        double reach = UPPER_ARM_LENGTH * Math.sin(u) - FOREARM_LENGTH * Math.sin(uf);
        double drop = -JOINT_UP - UPPER_ARM_LENGTH * Math.cos(u) + FOREARM_LENGTH * Math.cos(uf);
        out[0] = (float) (PIVOT_X + Math.cos(pose.yaw) * reach);
        out[1] = (float) (PIVOT_Y - drop);
        out[2] = (float) (PIVOT_Z - Math.sin(pose.yaw) * reach);
        return out;
    }

    /** 零件底面中心 (= 点焊接触点) 的位置, 工具竖直所以就在腕部正下方。 */
    public static float[] contactPosition(Pose pose, float[] out) {
        wristPosition(pose, out);
        out[1] -= TIP_DROP;
        return out;
    }

    private static Pose copyRow(float[] row, Pose out) {
        out.yaw = row[COL_YAW];
        out.upperArm = row[COL_UPPER_ARM];
        out.forearm = row[COL_FOREARM];
        out.toolSpin = row[COL_TOOL_SPIN];
        out.claw = row[COL_CLAW];
        out.payload = (int) row[COL_PAYLOAD];
        out.spark = row[COL_SPARK] != 0.0F;
        return out;
    }

    private static int parkedTick() {
        float[] idle = KEYFRAMES[0];
        int i = KEYFRAMES.length - 1;
        while (i > 0 && sameRow(KEYFRAMES[i - 1], idle)) {
            i--;
        }
        return (int) KEYFRAMES[i][COL_TICK];
    }

    private static boolean sameRow(float[] a, float[] b) {
        for (int col = COL_YAW; col <= COL_PAYLOAD; col++) {
            if (a[col] != b[col]) {
                return false;
            }
        }
        return true;
    }

    private static int[] weldStartTicks() {
        if (KEYFRAMES[KEYFRAMES.length - 1][COL_TICK] != CYCLE_TICKS) {
            throw new IllegalStateException("gunsmith arm program must last exactly " + CYCLE_TICKS + " ticks");
        }
        int count = 0;
        int[] starts = new int[KEYFRAMES.length];
        for (int i = 1; i < KEYFRAMES.length; i++) {
            if (KEYFRAMES[i][COL_SPARK] != 0.0F && KEYFRAMES[i - 1][COL_SPARK] == 0.0F) {
                starts[count++] = (int) KEYFRAMES[i - 1][COL_TICK];
            }
        }
        int[] out = new int[count];
        System.arraycopy(starts, 0, out, 0, count);
        return out;
    }
}
