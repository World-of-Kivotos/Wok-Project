package com.miningdim.job.munitions.block;

/**
 * 军火台 (WIDE 布局) 弹药流水线的运动程序: "循环时间 → 姿态" 的纯数学部分。
 * <p>
 * 刻意不依赖任何 Minecraft 类: 客户端渲染器按它摆运动件 (皮带上的弹、出弹、压弹头冲头、底火冲杆、装药管, 见
 * client/MunitionsBenchParts), 服务端按同一张表的冲压时刻 ({@link #isStrikeTick}) 播冲压音, 两边读同一份数据才不会音画错位;
 * 也因此能脱离游戏单独编译, 与 tools/munitions_bench/ber.mjs 的 JS 镜像 (sampleProgram / programTick) 逐 tick 对拍。
 * <p>
 * 两种时间: 关键帧表写在 {@link #CYCLE_TICKS} (40) tick 的<b>程序时间</b>里; 游戏里每档一个循环的实际长度是
 * {@link #cycleTicks} (档位越高越快, 闪耀 2 倍速), 游戏时间按 {@link #programTick} 映射过去, 运动件、火花、冲压音与
 * 运行灯效 (MunitionsBenchLights 的脉冲都定义在程序时间里) 一起变快。档位 = MunitionsBenchBlock#tier (注册时给定)。
 * 渲染器每帧的两个决定 (姿态 {@link #sampleRunning}、火花 {@link #strikeBetween}) 也写在这里, GameTest 逐档核对它们与服务端的
 * {@link #isStrikeTick} 同拍。
 * <p>
 * 关键帧表由 tools/munitions_bench/generate_munitions_bench.mjs 按方案 B 的 8 帧动作写入, 改动作请改生成器,
 * 不要手改生成区块。坐标系: 朝北放置时的整台局部像素, x 东、y 上、z 南, 原点在主格西北下角
 * (主格 x 0..16 在玩家右手, 副格 x 16..32 在左手); 姿态里的量都是相对待机布局的偏移 (px)。
 */
public final class MunitionsBenchProgram {

    // <generated> 由 tools/munitions_bench/generate_munitions_bench.mjs 写入, 不要手改
    /** 一个生产循环的程序时间长度 (tick): 8 帧, 每帧 5 tick。关键帧按它写; 普通档按原速放 (= CYCLE_TICKS_BY_TIER[0])。 */
    public static final int CYCLE_TICKS = 40;
    /** 冲头压到底 (弹头落到壳口上) 的程序时刻; 各档在游戏里的冲压时刻见 STRIKE_TICKS_BY_TIER (程序时间映射过去正好是它)。 */
    public static final int STRIKE_TICK = 10;
    /**
     * 各档一个生产循环的实际长度 (游戏 tick), 下标 = 档位 0..5 (普通..闪耀, 与 MunitionsBenchAssets.TIER_IDS 同序; tiers.mjs 的 cycleTicks):
     * 档位越高越快, 均匀阶梯到 2 倍速。游戏时间按 {@link #programTick} 映射到上面的程序时间。
     * 私有 (数组可写): 外面只经 {@link #cycleTicks} / {@link #tierCount} 读, 两端不会被哪一处误写而错拍。
     */
    private static final int[] CYCLE_TICKS_BY_TIER = {40, 36, 32, 28, 24, 20};
    /** 各档的冲压时刻 (游戏 tick, 循环内) = STRIKE_TICK 按比例缩到该档的循环: 服务端在程序起点 + 它 + n × 该档循环长度播冲压音, 渲染器在同一时刻放火花 (经 {@link #strikeTick} 读)。 */
    private static final int[] STRIKE_TICKS_BY_TIER = {10, 9, 8, 7, 6, 5};
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
        // 每档的冲压时刻必须正好是程序的冲压时刻映射过去的整 tick (生成器也核对): 否则冲压音 / 火花与冲头到底错开
        if (CYCLE_TICKS_BY_TIER.length == 0 || CYCLE_TICKS_BY_TIER.length != STRIKE_TICKS_BY_TIER.length) {
            throw new IllegalStateException("munitions bench program needs one cycle length and one strike tick per tier");
        }
        for (int tier = 0; tier < CYCLE_TICKS_BY_TIER.length; tier++) {
            int cycle = CYCLE_TICKS_BY_TIER[tier];
            if (cycle <= 0 || STRIKE_TICKS_BY_TIER[tier] * CYCLE_TICKS != STRIKE_TICK * cycle) {
                throw new IllegalStateException("munitions bench tier " + tier + ": strike tick " + STRIKE_TICKS_BY_TIER[tier]
                        + " is not STRIKE_TICK scaled to a " + cycle + "-tick cycle");
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
     * 程序时间 (tick, 可带小数; 游戏时间先经 {@link #programTick} 按档位映射过来) 处的姿态。按 {@link #CYCLE_TICKS} 取模 (负数折回, NaN 按 0)。
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

    /** 有几档 (每档一个循环长度与冲压时刻; 档位下标 0..tierCount() - 1)。 */
    public static int tierCount() {
        return CYCLE_TICKS_BY_TIER.length;
    }

    /** 这一档一个生产循环的实际长度 (游戏 tick); 档位越界 (不该发生) 按普通档。 */
    public static int cycleTicks(int tier) {
        return CYCLE_TICKS_BY_TIER[tier >= 0 && tier < CYCLE_TICKS_BY_TIER.length ? tier : 0];
    }

    /** 这一档的冲压时刻 (游戏 tick, 循环内, = 程序的 STRIKE_TICK 映射过去); 档位越界按普通档。 */
    public static int strikeTick(int tier) {
        return STRIKE_TICKS_BY_TIER[tier >= 0 && tier < STRIKE_TICKS_BY_TIER.length ? tier : 0];
    }

    /**
     * 游戏时间 → 程序时间: 程序开始后 elapsedTicks 整 tick、再加 partialTick, 在这一档的循环里走到了程序的哪一刻
     * ([0, CYCLE_TICKS) 的 tick) = ((elapsedTicks mod 该档循环) + partialTick) × CYCLE_TICKS / 该档循环。
     * 先在 long 里取模再加小数 (台子连续工作几天后 elapsedTicks 超过 float / double 能精确表示小数的范围, 不取模会让动作一顿一顿),
     * 其余在 double 里按 先加、再乘、再除 的顺序算 (整 tick + float 小数在 double 里是精确的; 与 ber.mjs programTick 逐位相同)。
     * 循环内单调递增, 到该档循环的整数倍正好折回 0; 普通档 (循环 = CYCLE_TICKS) 就是 elapsed mod 40 + partialTick 本身。
     * 运动件 ({@link #sample(long, float, int, Pose)})、运行灯效 (MunitionsBenchLights.compute) 都用它。
     */
    public static double programTick(long elapsedTicks, float partialTick, int tier) {
        int cycle = cycleTicks(tier);
        return (Math.floorMod(elapsedTicks, (long) cycle) + (double) partialTick) * CYCLE_TICKS / cycle;
    }

    /** 程序开始后 elapsedTicks 整 tick、再加 partialTick 处的姿态 (这一档的速度, 见 {@link #programTick})。 */
    public static Pose sample(long elapsedTicks, float partialTick, int tier, Pose out) {
        return sample((float) programTick(elapsedTicks, partialTick, tier), out);
    }

    /**
     * 渲染器工作时每帧的姿态 (client/MunitionsBenchRenderer 直接调它, GameTest 与 check_parity 逐档核对): 开工以来 elapsedTicks 整 tick
     * 再加 partialTick, 按这一档的速度取样; 起点比本地时钟快 (elapsedTicks &lt; 0: 更新标签带来的服务端起点比客户端时钟快一两 tick) 时停在首帧。
     */
    public static Pose sampleRunning(long elapsedTicks, float partialTick, int tier, Pose out) {
        return elapsedTicks < 0L ? sample(0.0F, out) : sample(elapsedTicks, partialTick, tier, out);
    }

    /** 待机布局 (不在工作时画它, 不推进相位): 与首帧相同, 只是底火冲杆收着。 */
    public static Pose idle(Pose out) {
        return copyRow(IDLE, out);
    }

    /** 程序开始后第 elapsedTicks tick 是否正是这一档冲头压到底的那一刻 (起点 + strikeTick(tier) + n × cycleTicks(tier); 服务端据此播冲压音)。 */
    public static boolean isStrikeTick(long elapsedTicks, int tier) {
        return elapsedTicks >= 0L && Math.floorMod(elapsedTicks, (long) cycleTicks(tier)) == strikeTick(tier);
    }

    /** 程序开始后 elapsedTicks 之后 (严格大于) 的下一次冲压时刻 (这一档), 同样从程序开始算起。 */
    public static long nextStrikeTickAfter(long elapsedTicks, int tier) {
        long cycle = cycleTicks(tier);
        long strike = Math.floorDiv(elapsedTicks, cycle) * cycle + strikeTick(tier);
        return strike > elapsedTicks ? strike : strike + cycle;
    }

    /**
     * 上一帧画到开工以来第 sinceTicks tick、这一帧到 elapsedTicks: (sinceTicks, elapsedTicks] 里有没有这一档的冲压 tick
     * (= 其中某一 tick 的 {@link #isStrikeTick} 为真, 起点之前没有)。渲染器据此放火花 (帧率低于 20 时也不漏), 与服务端的冲压音同拍;
     * 隔多久就不补放是渲染器自己的事。
     */
    public static boolean strikeBetween(long sinceTicks, long elapsedTicks, int tier) {
        long from = Math.max(sinceTicks, -1L);
        return elapsedTicks > from && nextStrikeTickAfter(from, tier) <= elapsedTicks;
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
