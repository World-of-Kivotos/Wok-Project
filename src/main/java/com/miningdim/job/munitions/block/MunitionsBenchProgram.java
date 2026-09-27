package com.miningdim.job.munitions.block;

/**
 * 军火台 (WIDE 布局) 弹药流水线的运动程序: "循环时间 → 姿态" 的纯数学部分。
 * <p>
 * 刻意不依赖任何 Minecraft 类: 客户端渲染器按它摆运动件 (皮带上的弹、出弹、压弹头冲头、底火冲杆、装药管, 见
 * client/MunitionsBenchParts), 服务端按同一个 {@link #STRIKE_TICK} 播冲压音, 两边读同一份数据才不会音画错位;
 * 也因此能脱离游戏单独编译, 与 tools/munitions_bench/ber.mjs 的 JS 镜像 (sampleProgram) 逐 tick 对拍。
 * <p>
 * 关键帧表由 tools/munitions_bench/generate_munitions_bench.mjs 按方案 B 的 8 帧动作写入, 改动作请改生成器,
 * 不要手改生成区块。坐标系: 朝北放置时的整台局部像素, x 东、y 上、z 南, 原点在主格西北下角
 * (主格 x 0..16 在玩家右手, 副格 x 16..32 在左手); 姿态里的量都是相对待机布局的偏移 (px)。
 */
public final class MunitionsBenchProgram {

    // <generated> 由 tools/munitions_bench/generate_munitions_bench.mjs 写入, 不要手改
    /** 一个生产循环的长度 (tick): 8 帧, 每帧 5 tick。 */
    public static final int CYCLE_TICKS = 40;
    /** 冲头压到底 (弹头落到壳口上) 的时刻: 服务端在程序起点 + STRIKE_TICK + n × CYCLE_TICKS 播冲压音, 渲染器在同一时刻放火花。 */
    public static final int STRIKE_TICK = 10;
    /** 皮带节距 (px): 一个循环皮带正好走一个节距; 循环接缝处 beltX 从 -BELT_PITCH 跳回 0, 同时弹位整体换一次号, 画面不变。 */
    public static final float BELT_PITCH = 4.0F;
    private static final float[][] KEYFRAMES = {
            // tick,   beltX,  primeY, powderY,    ramY,   dropY, dieHeat, ramBulletVisible, powderCharged,  seated
            {   0,    0.0F,   -1.5F,    0.0F,    0.0F,    0.0F,    0.0F,                1,             0,       0}, // f0 底火冲杆下探, 入口位落下一只新壳
            {   5,    0.0F,    0.0F,   -1.5F,  -1.75F,    0.0F,    0.0F,                1,             0,       0}, // f1 装药管下探, 冲头下行
            {  10,    0.0F,    0.0F,    0.0F,  -3.75F,    0.0F,    1.0F,                1,             1,       0}, // f2 冲头到底, 弹头落到壳口上 (压模发热)
            {  15,   -1.0F,    0.0F,    0.0F,    0.0F,    0.0F,    0.0F,                0,             1,       1}, // f3 皮带步进, 冲头回位 (不夹弹头)
            {  20,   -2.5F,    0.0F,    0.0F,    0.0F,    0.0F,    0.0F,                0,             1,       1}, // f4 皮带步进
            {  25,   -4.0F,    0.0F,    0.0F,    0.0F,   -1.0F,    0.0F,                1,             1,       1}, // f5 皮带到位, 冲头夹上下一颗弹头, 出弹沉进箱口
            {  30,   -4.0F,    0.0F,    0.0F,    0.0F,   -2.5F,    0.0F,                1,             1,       1}, // f6 出弹下沉
            {  35,   -4.0F,    0.0F,    0.0F,    0.0F,  -4.75F,    0.0F,                1,             1,       1}, // f7 出弹没入箱中
            {  40,   -4.0F,   -1.5F,    0.0F,    0.0F,  -4.75F,    0.0F,                1,             1,       1}, // 接缝 = 下一轮 f0 的机器姿态; 皮带上的弹仍按这一轮编号 (beltX = f0 - 节距, 箱里那发留在箱里)
    };
    /** 待机布局 (不推进相位, 方块实体不在工作时画它)。 */
    private static final float[] IDLE = {   0,    0.0F,    0.0F,    0.0F,    0.0F,    0.0F,    0.0F,                1,             0,       0};
    // </generated>

    private static final int COL_TICK = 0;
    private static final int COL_BELT_X = 1;
    private static final int COL_PRIME_Y = 2;
    private static final int COL_POWDER_Y = 3;
    private static final int COL_RAM_Y = 4;
    private static final int COL_DROP_Y = 5;
    private static final int COL_DIE_HEAT = 6;
    private static final int COL_RAM_BULLET = 7;
    private static final int COL_POWDER_CHARGED = 8;
    private static final int COL_SEATED = 9;

    static {
        if (KEYFRAMES[0][COL_TICK] != 0.0F || KEYFRAMES[KEYFRAMES.length - 1][COL_TICK] != CYCLE_TICKS) {
            throw new IllegalStateException("munitions bench program must run from tick 0 to exactly " + CYCLE_TICKS);
        }
        for (int i = 1; i < KEYFRAMES.length; i++) {
            if (!(KEYFRAMES[i][COL_TICK] > KEYFRAMES[i - 1][COL_TICK])) {
                throw new IllegalStateException("munitions bench program ticks must increase (row " + i + ")");
            }
        }
    }

    private MunitionsBenchProgram() {
    }

    /** 某一时刻的姿态。连续量都是相对待机布局的偏移 (px), 布尔量决定哪个运动件显示。 */
    public static final class Pose {
        /** 皮带上的弹 (4 个弹位的壳与出弹) 沿 x 的偏移: 负 = 往主格 (玩家右手) 走; 一个循环从 0 走到 -BELT_PITCH。 */
        public float beltX;
        /** 底火冲杆的 y 偏移: 负 = 下探顶到壳口。 */
        public float primeY;
        /** 装药管的 y 偏移: 负 = 下探。 */
        public float powderY;
        /** 压弹头冲头 (连杆、压模、夹着的弹头) 的 y 偏移: 负 = 下压, 最低点在 {@link #STRIKE_TICK}。 */
        public float ramY;
        /** 出弹 (皮带末端那发) 在 beltX 之外的 y 偏移: 负 = 越过皮带尽头掉进弹药箱。 */
        public float dropY;
        /** 压模热度 0..1 (冲头到底 = 1): 到阈值时画自发光的热压模代替冷压模。 */
        public float dieHeat;
        /** 冲头是否夹着下一颗弹头 (刚压完、冲头回位的两帧没有)。 */
        public boolean ramBulletVisible;
        /** 装药位那只壳是否已装药 (壳口是灰色发射药)。 */
        public boolean powderCharged;
        /** 压弹头位那发是否已压上弹头。 */
        public boolean seated;
    }

    /**
     * 循环时间 (tick, 可带小数) 处的姿态。按 {@link #CYCLE_TICKS} 取模 (负数折回, NaN 按 0)。
     * 连续量在相邻两行间线性插值: 帧表本身就是按缓动取样写的 (皮带 -1 / -2.5 / -4, 出弹 -1 / -2.5 / -4.75),
     * 逐帧再做 smoothstep 会让皮带在中间帧各停一下。布尔量取 "到达的那一行" 的值 (例如冲头一离开最低点, 弹头就算压上了)。
     * <p>
     * 末行 (tick = CYCLE_TICKS) 是循环接缝: 机器姿态与首行相同, 皮带上的弹仍按这一轮编号 (beltX = 首行 - BELT_PITCH);
     * 到下一轮首行时弹位整体换一次号, 画面除入口位落下一只新壳外不变。
     */
    public static Pose sample(float cycleTick, Pose out) {
        float t = cycleTick % CYCLE_TICKS;
        if (t < 0.0F) {
            t += CYCLE_TICKS;
        }
        if (!(t > 0.0F)) {
            return copyRow(KEYFRAMES[0], out);
        }
        int i = 1;
        while (KEYFRAMES[i][COL_TICK] < t) {
            i++;
        }
        float[] a = KEYFRAMES[i - 1];
        float[] b = KEYFRAMES[i];
        float s = (t - a[COL_TICK]) / (b[COL_TICK] - a[COL_TICK]);
        out.beltX = lerp(a, b, COL_BELT_X, s);
        out.primeY = lerp(a, b, COL_PRIME_Y, s);
        out.powderY = lerp(a, b, COL_POWDER_Y, s);
        out.ramY = lerp(a, b, COL_RAM_Y, s);
        out.dropY = lerp(a, b, COL_DROP_Y, s);
        out.dieHeat = lerp(a, b, COL_DIE_HEAT, s);
        out.ramBulletVisible = b[COL_RAM_BULLET] != 0.0F;
        out.powderCharged = b[COL_POWDER_CHARGED] != 0.0F;
        out.seated = b[COL_SEATED] != 0.0F;
        return out;
    }

    /**
     * 程序开始后 elapsedTicks 整 tick、再加 partialTick 处的姿态。先在 long 里取模再加小数:
     * 台子连续工作几天后 elapsedTicks 超过 float 能精确表示的范围, 直接转 float 会让动作一顿一顿。
     */
    public static Pose sample(long elapsedTicks, float partialTick, Pose out) {
        return sample(Math.floorMod(elapsedTicks, (long) CYCLE_TICKS) + partialTick, out);
    }

    /** 待机布局 (不在工作时画它, 不推进相位): 与首帧相同, 只是底火冲杆收着。 */
    public static Pose idle(Pose out) {
        return copyRow(IDLE, out);
    }

    /** 程序开始后第 elapsedTicks tick 是否正是冲头压到底的那一刻 (服务端据此播冲压音)。 */
    public static boolean isStrikeTick(long elapsedTicks) {
        return elapsedTicks >= 0L && Math.floorMod(elapsedTicks, (long) CYCLE_TICKS) == STRIKE_TICK;
    }

    /** 程序开始后 elapsedTicks 之后 (严格大于) 的下一次冲压时刻, 同样从程序开始算起。 */
    public static long nextStrikeTickAfter(long elapsedTicks) {
        long cycleStart = Math.floorDiv(elapsedTicks, (long) CYCLE_TICKS) * CYCLE_TICKS;
        long strike = cycleStart + STRIKE_TICK;
        return strike > elapsedTicks ? strike : strike + CYCLE_TICKS;
    }

    private static float lerp(float[] a, float[] b, int col, float s) {
        return a[col] + (b[col] - a[col]) * s;
    }

    private static Pose copyRow(float[] row, Pose out) {
        out.beltX = row[COL_BELT_X];
        out.primeY = row[COL_PRIME_Y];
        out.powderY = row[COL_POWDER_Y];
        out.ramY = row[COL_RAM_Y];
        out.dropY = row[COL_DROP_Y];
        out.dieHeat = row[COL_DIE_HEAT];
        out.ramBulletVisible = row[COL_RAM_BULLET] != 0.0F;
        out.powderCharged = row[COL_POWDER_CHARGED] != 0.0F;
        out.seated = row[COL_SEATED] != 0.0F;
        return out;
    }
}
