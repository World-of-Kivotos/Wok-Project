package com.miningdim.job.munitions;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.miningdim.core.MiningConstants;
import com.miningdim.job.munitions.block.MunitionsBenchBlock;
import com.miningdim.job.munitions.block.MunitionsBenchGeometry;
import com.miningdim.job.munitions.block.MunitionsBenchLights;
import com.miningdim.job.munitions.block.MunitionsBenchProgram;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * 军火台运行灯效 ({@link MunitionsBenchLights}) 的契约测试: 脉冲卡在程序真实动作的那一刻 (各档按自己的速度, 冲压闪光落在该档的冲压 tick)、
 * 按档位解锁、任何一档都不超过 3 Hz 的光敏上限、α 永远不落在着色器会丢掉的
 * (0, 0.1) 里、同一块面上从不叠两层、不超过定长数组, 以及每个四边形在四个朝向下都正好贴在静态模型那条灯带的那个面外。
 * <p>
 * 期望值取设计口径本身 (程序帧表里动作到底的时刻、用户拍板的档位阶梯、静态 JSON 里的元素), 不照抄 LIGHT_* 常量;
 * 与 tools/munitions_bench/lights.mjs 的逐值一致由 check_parity.mjs 单独对拍。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class MunitionsBenchLightsGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "munitions_bench_lights";
    /**
     * 很早就开工了 (排除第一轮开工时不画的跨接缝尾巴): 从这么多 tick 之后取样。10080 是六档循环 (40 / 36 / 32 / 28 / 24 / 20) 与
     * 呼吸周期 (80) 的最小公倍数, 所以 LONG_RUNNING + t 在每一档都与开工后第 t tick 同一相位 (测试开头核对)。
     */
    private static final long LONG_RUNNING = 10080L * 4L;
    private static final int TIERS = 6;
    private static final int HIGH = 2;
    private static final int SUPERIOR = 3;
    private static final int RADIANT = 5;

    private MunitionsBenchLightsGameTests() {
    }

    /**
     * 每个脉冲的峰正好在程序里那个动作到底的那一刻 (从 MunitionsBenchProgram 的姿态量出来, 不照抄常量), 六档都是 (档位越高越快,
     * 灯效与运动件按同一个程序时间走): 冲压闪光 / 压弹头工位灯 / 宝石白闪 = 冲头最低 (程序的 STRIKE_TICK), 在游戏时间里正好落在
     * 这一档的冲压 tick (与冲压音、火花同一刻); 底火灯 = 底火冲杆最低; 装药灯 = 装药管最低; 出弹灯 / 落箱脉冲 / 宝石回响 = 出弹没入弹药箱;
     * 入口灯在出弹落定之后、循环接缝 (新壳落进入口位) 之前。第一轮开工时不画上一轮 "没发生过" 的尾巴, 而开工之后已经发生过的拍
     * (任意 float 的 partialTick) 与跑了很久之后同一相位逐位相同。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void benchLightPulsesPeakOnTheProgramBeats(GameTestHelper helper) {
        MunitionsBenchProgram.Pose pose = new MunitionsBenchProgram.Pose();
        float ramLowest = firstLowest(pose, 0);
        float primeLowest = firstLowest(pose, 1);
        float powderLowest = firstLowest(pose, 2);
        float dropLowest = firstLowest(pose, 3);
        helper.assertTrue(ramLowest == MunitionsBenchProgram.STRIKE_TICK,
                "precondition: the ram bottoms out at STRIKE_TICK, got " + ramLowest);
        helper.assertTrue(LONG_RUNNING % MunitionsBenchGeometry.LIGHT_BREATH_PERIOD_TICKS == 0L,
                "precondition: LONG_RUNNING is a whole number of breath periods");
        for (int tier = 0; tier < TIERS; tier++) {
            helper.assertTrue(LONG_RUNNING % MunitionsBenchProgram.cycleTicks(tier) == 0L,
                    "precondition: LONG_RUNNING is a whole number of tier " + tier + " cycles");
        }

        MunitionsBenchLights.Frame frame = new MunitionsBenchLights.Frame();
        float[] slotX = MunitionsBenchGeometry.SLOT_X_PX; // 入口 / 底火 / 装药 / 压弹头 / 出弹
        for (int i = 0; i < MunitionsBenchLights.LED_COUNT; i++) {
            helper.assertTrue(Math.abs(MunitionsBenchGeometry.LIGHT_LED_X_PX[i] - slotX[slotX.length - 1 - i]) < 1.0E-5F,
                    "station light " + i + " sits under its slot x " + slotX[slotX.length - 1 - i]);
        }
        float[] feedPeaks = new float[TIERS];
        for (int tier = 0; tier < TIERS; tier++) {
            // 这一档一个循环, 每 1/4 游戏 tick: 记下每一路最亮的那一刻 (程序时间与游戏时间)
            int cycle = MunitionsBenchProgram.cycleTicks(tier);
            double[] best = new double[4 + MunitionsBenchLights.LED_COUNT];
            double[] bestAt = new double[best.length];
            float[] bestReal = new float[best.length];
            for (int step = 0; step < cycle * 4; step++) {
                long ticks = LONG_RUNNING + step / 4;
                float partial = (step % 4) * 0.25F;
                MunitionsBenchLights.compute(frame, tier, true, false, ticks, 0L, partial, 2.0D);
                for (int i = 0; i < best.length; i++) {
                    double now = beat(frame, i);
                    if (now > best[i] + 1.0E-9D) {
                        best[i] = now;
                        bestAt[i] = frame.cycleTick;
                        bestReal[i] = step * 0.25F;
                    }
                }
            }
            String where = "tier " + tier + " (" + cycle + "-tick cycle)";
            // 冲压: 程序的冲头最低, 游戏时间里正好是这一档的冲压 tick (服务端在这一 tick 播冲压音, 渲染器放火花)
            int strike = MunitionsBenchProgram.strikeTick(tier);
            helper.assertTrue(bestAt[0] == ramLowest && bestReal[0] == strike && Math.abs(best[0] - 1.0D) < 1.0E-9D,
                    where + ": the strike flash must peak (at 1) exactly when the ram bottoms out (program " + ramLowest + ", tick "
                            + strike + "), got " + best[0] + " at program " + bestAt[0] + " / tick " + bestReal[0]);
            helper.assertTrue(bestAt[2] == ramLowest && bestReal[2] == strike,
                    where + ": the gem flashes with the strike, peak at " + bestAt[2] + " / tick " + bestReal[2]);
            helper.assertTrue(bestAt[1] == dropLowest && bestAt[3] == dropLowest,
                    where + ": the drop pulse and the gem echo peak when the finished round lands in the can (" + dropLowest + "), got "
                            + bestAt[1] + " / " + bestAt[3]);
            // 工位灯: 按 x 从小到大 = 出弹 / 压弹头 / 装药 / 底火 / 入口
            float[] expectedPeak = {dropLowest, ramLowest, powderLowest, primeLowest};
            for (int i = 0; i < 4; i++) {
                helper.assertTrue(bestAt[4 + i] == expectedPeak[i] && Math.abs(best[4 + i] - 1.0D) < 1.0E-9D,
                        where + ": station light " + i + " must peak when its station bottoms out (" + expectedPeak[i] + "), got " + bestAt[4 + i]);
            }
            helper.assertTrue(bestReal[4 + 1] == strike, where + ": the press station light peaks on the strike tick " + strike);
            feedPeaks[tier] = (float) bestAt[4 + 4];
            helper.assertTrue(feedPeaks[tier] > dropLowest && feedPeaks[tier] < MunitionsBenchProgram.CYCLE_TICKS,
                    where + ": the feed light blinks after the drop lands and before the seam drops a new case, got " + feedPeaks[tier]);

            // 第一轮开工: 游戏时间 1 tick 时上一轮的落箱尾巴没发生过, 不画; 跑满一轮之后同一相位才有
            MunitionsBenchLights.compute(frame, tier, true, false, 1L, 0L, 0.0F, 2.0D);
            double firstCycle = frame.drop;
            MunitionsBenchLights.compute(frame, tier, true, false, 1L + cycle, 0L, 0.0F, 2.0D);
            helper.assertTrue(firstCycle == 0.0D && frame.drop > 0.0D,
                    where + ": no drop tail in the first cycle after a start (" + firstCycle + "), but one a cycle later (" + frame.drop + ")");
        }

        // 反过来: 开工之后真的发生过的那一拍 (程序时间已过了它的峰), 第一轮里与跑了很久之后同一相位一模一样 (不许误判成没发生过)。
        // partialTick 取任意的 float, 不只 0.25 的倍数: 时钟按 float 加时, 循环 tick 会比 elapsed 大一个 ulp, 底火灯 (峰 0) 在开工后的
        // 头几 tick 里约四分之一的帧闪断; 程序时间按档位映射后 (乘除 40 / 循环) 同样不许。
        float[] partials = {0.0F, 0.1F, 0.15957803F, 0.3F, 0.4065408F, 0.5F, 0.7F, 0.8456556F, 0.9999F};
        MunitionsBenchLights.Frame steady = new MunitionsBenchLights.Frame();
        for (int tier = 0; tier < TIERS; tier++) {
            float[] beats = {ramLowest, dropLowest, ramLowest, dropLowest, dropLowest, ramLowest, powderLowest, primeLowest, feedPeaks[tier]};
            int[] lit = new int[beats.length];
            int cycle = MunitionsBenchProgram.cycleTicks(tier);
            for (long e = 0L; e < cycle; e++) {
                for (float partial : partials) {
                    MunitionsBenchLights.compute(frame, tier, true, false, e, 0L, partial, 2.0D);
                    MunitionsBenchLights.compute(steady, tier, true, false, LONG_RUNNING + e, 0L, partial, 2.0D);
                    if (frame.cycleTick != steady.cycleTick) {
                        helper.fail("tier " + tier + " e " + e + " + " + partial + ": the first cycle and a long-running bench disagree on the phase");
                    }
                    for (int channel = 0; channel < beats.length; channel++) {
                        if (frame.cycleTick < beats[channel]) {
                            continue; // 这一拍在第一轮里还没到 (跨接缝的尾巴属于上一轮, 没发生过)
                        }
                        double first = beat(frame, channel);
                        double later = beat(steady, channel);
                        if (first != later) {
                            helper.fail("tier " + tier + " channel " + channel + " (beat at " + beats[channel] + "), first cycle e " + e + " + "
                                    + partial + ": the beat already happened after the start, so it must draw as it does later (" + later
                                    + "), got " + first);
                        }
                        lit[channel] += later > 0.0D ? 1 : 0;
                    }
                }
            }
            for (int channel = 0; channel < beats.length; channel++) {
                helper.assertTrue(lit[channel] > 0, "precondition: tier " + tier + " channel " + channel + " was compared while lit");
            }
        }
        // 起点比本地时钟快 (elapsed < 0): 与运动件一样停在首帧
        MunitionsBenchLights.compute(frame, RADIANT, true, false, -3L, 0L, 0.5F, 2.0D);
        helper.assertTrue(frame.cycleTick == 0.0D && frame.beltX == 0.0F, "a start tick ahead of the client clock holds the first frame");
        helper.succeed();
    }

    /**
     * 光敏 (档位越高越快, 闪耀 2 倍速, 用户认可的上限 3 Hz): 每一档每一路脉冲 (冲压 / 落箱 / 宝石 (白闪与回响取大的) / 各工位灯段)
     * 每个循环正好亮一次 (宝石两次), 实测的次数 ÷ 秒数都 ≤ 3 Hz; 皮带追光的瞬时频率 = 皮带在游戏时间里的最快速度 × 追光速度 / 节距
     * × 20 tick/s ≤ 3 Hz (闪耀正好 3 Hz, 普通 1.5 Hz)。运行呼吸 (80 tick) 与待机满仓闪烁 (40 tick) 是游戏时间, 与档位无关, 不随之变快。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void benchLightsNeverFlashFasterThanThreeHertzOnAnyTier(GameTestHelper helper) {
        MunitionsBenchLights.Frame frame = new MunitionsBenchLights.Frame();
        MunitionsBenchLights.Frame base = new MunitionsBenchLights.Frame();
        int cycles = 4;
        int channels = 4 + MunitionsBenchLights.LED_COUNT;
        for (int tier = 0; tier < TIERS; tier++) {
            int cycle = MunitionsBenchProgram.cycleTicks(tier);
            int[] rises = new int[channels];
            double[] previous = new double[channels];
            double fastest = 0.0D;
            float previousBelt = 0.0F;
            for (int q = 0; q < cycle * cycles * 8; q++) {
                MunitionsBenchLights.compute(frame, tier, true, false, LONG_RUNNING + q / 8, 0L, (q % 8) * 0.125F, 2.0D);
                for (int channel = 0; channel < channels; channel++) {
                    // 宝石: 白闪与回响取大的 (渲染器画的就是它)
                    double v = channel == 2 ? Math.max(frame.gem, frame.gemEcho) : channel == 3 ? 0.0D : beat(frame, channel);
                    if (q > 0 && previous[channel] < 0.5D && v >= 0.5D) {
                        rises[channel]++;
                    }
                    previous[channel] = v;
                }
                if (q > 0) {
                    // 皮带在接缝处跳回一个节距 (弹位换号, 画面不动): 按节距折回再量速度
                    double step = frame.beltX - previousBelt;
                    double pitch = MunitionsBenchProgram.BELT_PITCH;
                    step -= pitch * Math.rint(step / pitch);
                    fastest = Math.max(fastest, Math.abs(step) / 0.125D);
                }
                previousBelt = frame.beltX;
            }
            double seconds = cycle * cycles / 20.0D;
            String where = "tier " + tier + " (" + cycle + "-tick cycle)";
            helper.assertTrue(rises[0] == cycles && rises[1] == cycles && rises[2] == 2 * cycles,
                    where + ": one strike flash and one drop pulse per cycle, two gem flashes, got " + rises[0] + " / " + rises[1] + " / " + rises[2]);
            for (int channel = 0; channel < channels; channel++) {
                double hz = rises[channel] / seconds;
                if (hz > 3.0D + 1.0E-9D) {
                    helper.fail(where + ": light channel " + channel + " flashes at " + hz + " Hz (> 3 Hz)");
                }
            }
            double chaseHz = MunitionsBenchGeometry.LIGHT_CHASE_SPEED * fastest / MunitionsBenchProgram.BELT_PITCH * 20.0D;
            helper.assertTrue(chaseHz <= 3.0D + 1.0E-3D, where + ": the belt chase sweeps a point at " + chaseHz + " Hz (> 3 Hz)");
            helper.assertTrue(Math.abs(chaseHz - 1.5D * 40.0D / cycle) < 1.0E-3D,
                    where + ": the chase runs with the belt, 1.5 Hz x the tier speed, got " + chaseHz);

            // 游戏时间的效果不随档位变快: 呼吸与满仓闪烁与普通档同一刻逐位相同
            for (int step = 0; step < 80 * 4; step += 3) {
                long ticks = LONG_RUNNING + step / 4;
                float partial = (step % 4) * 0.25F;
                MunitionsBenchLights.compute(frame, tier, true, false, ticks, ticks, partial, 2.0D);
                MunitionsBenchLights.compute(base, 0, true, false, ticks, ticks, partial, 2.0D);
                if (!(frame.breathTick == base.breathTick && frame.breath == base.breath)) {
                    helper.fail(where + ": the running breath keeps its 4 s period at every tier (step " + step + ")");
                }
                MunitionsBenchLights.compute(frame, tier, false, true, ticks, ticks, partial, 2.0D);
                MunitionsBenchLights.compute(base, 0, false, true, ticks, ticks, partial, 2.0D);
                if (!(frame.clockTick == base.clockTick && frame.full == base.full)) {
                    helper.fail(where + ": the idle full-buffer blink keeps its 2 s period at every tier (step " + step + ")");
                }
            }
        }
        helper.succeed();
    }

    /**
     * 用户拍板的档位阶梯 (唯一行为): 工位指示灯 / 冲压闪光 / 落箱脉冲 / 满仓提示 全档都有; 运行呼吸从高级 (档 2) 起;
     * 皮带追光从极品 (档 3) 起; 宝石脉冲只有闪耀 (档 5)。满仓只在待机且满仓时闪, 工作时满仓也不闪; 待机不满仓一个四边形都没有。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void benchLightsUnlockByTier(GameTestHelper helper) {
        MunitionsBenchLights.Frame frame = new MunitionsBenchLights.Frame();
        for (int tier = 0; tier < TIERS; tier++) {
            boolean[] seen = new boolean[MunitionsBenchLights.EFFECT_COUNT];
            for (int step = 0; step < MunitionsBenchGeometry.LIGHT_BREATH_PERIOD_TICKS * 8; step++) {
                long ticks = LONG_RUNNING + step / 4;
                float partial = (step % 4) * 0.25F;
                for (boolean full : new boolean[]{false, true}) {
                    int n = MunitionsBenchLights.compute(frame, tier, true, full, ticks, ticks, partial, 2.0D);
                    for (int q = 0; q < n; q++) {
                        seen[frame.effect[q]] = true;
                        if (frame.effect[q] == MunitionsBenchLights.EFFECT_FULL) {
                            helper.fail("tier " + tier + ": the full blink never shows while the bench is working");
                        }
                    }
                    n = MunitionsBenchLights.compute(frame, tier, false, full, ticks, ticks, partial, 2.0D);
                    for (int q = 0; q < n; q++) {
                        seen[frame.effect[q]] = true;
                        if (!(full && frame.effect[q] == MunitionsBenchLights.EFFECT_FULL)) {
                            helper.fail("tier " + tier + ": an idle bench only ever shows the full blink, and only when full");
                        }
                    }
                }
            }
            boolean[] expected = new boolean[MunitionsBenchLights.EFFECT_COUNT];
            expected[MunitionsBenchLights.EFFECT_LEDS] = true;
            expected[MunitionsBenchLights.EFFECT_STRIKE] = true;
            expected[MunitionsBenchLights.EFFECT_DROP] = true;
            expected[MunitionsBenchLights.EFFECT_FULL] = true;
            expected[MunitionsBenchLights.EFFECT_BREATH] = tier >= HIGH;
            expected[MunitionsBenchLights.EFFECT_CHASE] = tier >= SUPERIOR;
            expected[MunitionsBenchLights.EFFECT_GEM] = tier == RADIANT;
            int mask = 0;
            for (int effect = 0; effect < MunitionsBenchLights.EFFECT_COUNT; effect++) {
                helper.assertTrue(seen[effect] == expected[effect],
                        "tier " + tier + " effect " + effect + ": expected " + (expected[effect] ? "shown" : "never shown")
                                + ", got " + (seen[effect] ? "shown" : "never shown"));
                mask |= expected[effect] ? 1 << effect : 0;
            }
            helper.assertTrue(MunitionsBenchLights.effectMask(tier) == mask,
                    "tier " + tier + ": effectMask " + MunitionsBenchLights.effectMask(tier) + ", expected " + mask);
        }
        helper.assertTrue(MunitionsBenchLights.effectMask(-1) == MunitionsBenchLights.effectMask(0)
                        && MunitionsBenchLights.effectMask(TIERS) == MunitionsBenchLights.effectMask(0),
                "an out-of-range tier falls back to the base tier");
        helper.succeed();
    }

    /**
     * 游戏里的硬约束 (lights.mjs 文件头): 六档 × 工作 / 待机 / 满仓 × 两个呼吸周期每 0.25 tick × 几个相机距离, 每个四边形每个顶点的
     * α 都 ≥ 0.1 (写进顶点的字节 ≥ 26, rendertype_text_background.fsh 不会丢), 不超过定长数组, 同一块面上任何两个四边形不重叠;
     * 24 格外 (渲染器本来就不画) 呼吸已完全淡出。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void benchLightsNeverUnderCutoffNeverOverlapNeverOverflow(GameTestHelper helper) {
        MunitionsBenchLights.Frame frame = new MunitionsBenchLights.Frame();
        float[] corners = new float[MunitionsBenchLights.MAX_QUADS * MunitionsBenchLights.CORNER_FLOATS];
        float[] colours = new float[MunitionsBenchLights.MAX_QUADS * MunitionsBenchLights.COLOUR_FLOATS];
        double[] distances = {0.0D, 12.0D, 21.0D, 23.9D, 24.0D, 30.0D};
        int frames = 0;
        int most = 0;
        for (int tier = 0; tier < TIERS; tier++) {
            for (boolean[] state : new boolean[][]{{true, false}, {false, false}, {false, true}, {true, true}}) {
                for (double distance : distances) {
                    for (int step = 0; step < MunitionsBenchGeometry.LIGHT_BREATH_PERIOD_TICKS * 8; step++) {
                        long ticks = step / 4;
                        float partial = (step % 4) * 0.25F;
                        int n = MunitionsBenchLights.compute(frame, tier, state[0], state[1], ticks, ticks + 13L, partial, distance);
                        frames++;
                        most = Math.max(most, n);
                        // 失败信息只在失败时拼 (九万多帧 × 每个四边形, 通过时不造字符串)
                        if (frame.overflowed || n > MunitionsBenchLights.MAX_QUADS) {
                            helper.fail(where(tier, state, step, distance) + ": " + n + " quads, the fixed array holds " + MunitionsBenchLights.MAX_QUADS);
                        }
                        MunitionsBenchLights.benchCorners(frame, corners, colours);
                        for (int q = 0; q < n; q++) {
                            int c = q * MunitionsBenchLights.COLOUR_FLOATS;
                            for (float a : new float[]{frame.alpha0[q], frame.alpha1[q], colours[c + 3], colours[c + 7], colours[c + 11], colours[c + 15]}) {
                                if (!(a >= 0.1F && a <= 1.0F && MunitionsBenchLights.alphaByte(a) >= 26)) {
                                    helper.fail(where(tier, state, step, distance) + ": quad " + q + " (effect " + frame.effect[q]
                                            + ") has vertex alpha " + a + ", the text_background shader discards alpha < 0.1");
                                }
                            }
                            if (distance >= 24.0D && frame.effect[q] == MunitionsBenchLights.EFFECT_BREATH) {
                                helper.fail(where(tier, state, step, distance) + ": the breath layer must have faded out by 24 blocks");
                            }
                            for (int r = q + 1; r < n; r++) {
                                if (overlapOnFace(frame, q, r)) {
                                    helper.fail(where(tier, state, step, distance) + ": quads " + q + " (effect " + frame.effect[q] + ", target "
                                            + frame.target[q] + ") and " + r + " (effect " + frame.effect[r] + ", target " + frame.target[r]
                                            + ") overlap on one face (textBackground would ghost)");
                                }
                            }
                        }
                    }
                }
            }
        }
        helper.assertTrue(frames > 0 && most > 0, "precondition: some frames drew lights");
        helper.succeed();
    }

    /**
     * 每个四边形在四个朝向下都正好贴在静态模型 (各档工作态的两格 JSON) 里那条灯带的那个面外: 角在元素面所在的平面往外 lift 处、
     * 在元素面胀 lift 的范围里, 顶点顺序从面外看逆时针 (textBackground 剔除背面); blockCorners 与 benchPixelToWorld 摆出来的点相同;
     * worldFace (渲染器取面明暗的方向) 就是这个四边形在世界里朝的方向; 目标的 shade 标志与元素的 shade 相同。
     * 取样覆盖所有目标 (闪耀档一个循环工作 + 待机满仓一个闪烁周期)。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void benchLightQuadsSitOnTheStaticStripsForEveryFacing(GameTestHelper helper) {
        MunitionsBenchLights.Frame frame = new MunitionsBenchLights.Frame();
        float[] corners = new float[MunitionsBenchLights.MAX_QUADS * MunitionsBenchLights.CORNER_FLOATS];
        float[] colours = new float[MunitionsBenchLights.MAX_QUADS * MunitionsBenchLights.COLOUR_FLOATS];
        float[] turned = new float[corners.length];
        int targets = MunitionsBenchGeometry.LIGHT_TARGET_FACES.length;
        for (int tier = 0; tier < TIERS; tier++) {
            List<float[]> elements = new ArrayList<>();
            List<JsonObject> jsons = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (String cell : new String[]{"main", "extension"}) {
                JsonObject model = MunitionsBenchAssets.blockModel(
                        MunitionsBenchAssets.lineModelName(MunitionsBenchAssets.TIER_IDS[tier], cell, true));
                float dx = "extension".equals(cell) ? 16.0F : 0.0F;
                for (JsonElement element : model.getAsJsonArray("elements")) {
                    JsonObject object = element.getAsJsonObject();
                    if (object.has("rotation") && object.getAsJsonObject("rotation").get("angle").getAsFloat() != 0.0F) {
                        continue;
                    }
                    float[] from = floats(object.getAsJsonArray("from"));
                    float[] to = floats(object.getAsJsonArray("to"));
                    elements.add(new float[]{from[0] + dx, from[1], from[2], to[0] + dx, to[1], to[2]});
                    jsons.add(object);
                    names.add(object.has("name") ? object.get("name").getAsString().replaceAll("~.*$", "") : "");
                }
            }
            boolean[] covered = new boolean[targets];
            for (int step = 0; step < MunitionsBenchProgram.CYCLE_TICKS * 4 * 2; step++) {
                boolean active = step < MunitionsBenchProgram.CYCLE_TICKS * 4;
                long ticks = LONG_RUNNING + step / 4;
                int n = MunitionsBenchLights.compute(frame, tier, active, !active, ticks, ticks, (step % 4) * 0.25F, 2.0D);
                MunitionsBenchLights.benchCorners(frame, corners, colours);
                for (int q = 0; q < n; q++) {
                    int target = frame.target[q];
                    covered[target] = true;
                    int o = q * MunitionsBenchLights.CORNER_FLOATS;
                    assertOnElementFace(helper, tier, step, q, target, corners, o, elements, jsons, names);
                    // 方块实体的渲染包围盒 (视锥剔除用) 要高过每个覆盖层, 否则画面里只剩宝石时整台连灯效一起被剔掉
                    for (int k = 0; k < 4; k++) {
                        if (corners[o + k * 3 + 1] > MunitionsBenchGeometry.RENDER_TOP_PX) {
                            helper.fail("tier " + tier + " t " + step * 0.25F + " quad " + q + " target " + target + " reaches y "
                                    + corners[o + k * 3 + 1] + " px, above the render bounding box top RENDER_TOP_PX "
                                    + MunitionsBenchGeometry.RENDER_TOP_PX);
                        }
                    }
                }
                for (Direction facing : Direction.Plane.HORIZONTAL) {
                    float yRotation = MunitionsBenchBlock.partsYRotationDegrees(facing);
                    MunitionsBenchLights.blockCorners(frame, yRotation, turned, colours);
                    for (int q = 0; q < n; q++) {
                        int o = q * MunitionsBenchLights.CORNER_FLOATS;
                        for (int k = 0; k < 4; k++) {
                            int p = o + k * 3;
                            Vec3 expected = MunitionsBenchBlock.benchPixelToWorld(BlockPos.ZERO, facing, corners[p], corners[p + 1], corners[p + 2]);
                            if (!(expected.distanceTo(new Vec3(turned[p], turned[p + 1], turned[p + 2])) < 1.0E-4D)) {
                                helper.fail("tier " + tier + " " + facing + " quad " + q + " corner " + k + ": blockCorners puts it at ("
                                        + turned[p] + ", " + turned[p + 1] + ", " + turned[p + 2] + "), the static model at " + expected);
                            }
                        }
                        Vec3 a = corner(turned, o);
                        Vec3 normal = corner(turned, o + 3).subtract(a).cross(corner(turned, o + 6).subtract(a)).normalize();
                        Direction world = Direction.from3DDataValue(MunitionsBenchLights.worldFace(
                                MunitionsBenchGeometry.LIGHT_TARGET_FACES[frame.target[q]], yRotation));
                        if (!(Vec3.atLowerCornerOf(world.getNormal()).dot(normal) > 0.999D)) {
                            helper.fail("tier " + tier + " " + facing + " quad " + q + ": worldFace says " + world
                                    + " but the quad's winding faces " + normal + " (the renderer would shade it by the wrong face)");
                        }
                    }
                }
            }
            for (int target = 0; target < targets; target++) {
                boolean gem = contains(MunitionsBenchGeometry.LIGHT_GROUPS[MunitionsBenchLights.GROUP_GEM], target);
                boolean rail = contains(MunitionsBenchGeometry.LIGHT_GROUPS[MunitionsBenchLights.GROUP_RAIL], target);
                boolean expected = gem ? tier == RADIANT : !rail || tier >= SUPERIOR;
                helper.assertTrue(covered[target] == expected,
                        "tier " + tier + " target " + target + ": expected " + (expected ? "" : "no ") + "quads on it over a cycle, got "
                                + (covered[target] ? "some" : "none"));
            }
        }
        helper.succeed();
    }

    // ---------------------------------------------------------------- 工具

    /** 失败信息的前缀 (只在失败时拼: 通过时这几个测试每帧每个四边形都不造字符串)。 */
    private static String quadWhere(int tier, int step, int quad, int target) {
        return "tier " + tier + " t " + step * 0.25F + " quad " + quad + " target " + target;
    }

    private static String where(int tier, boolean[] state, int step, double distance) {
        return "tier " + tier + (state[0] ? " working" : " idle") + (state[1] ? " full" : "") + " t " + step * 0.25F + " d " + distance;
    }

    /** 一路脉冲的标量: 0 冲压 / 1 落箱 / 2 宝石白闪 / 3 宝石回响 / 4.. 工位灯 (按 x 从小到大: 出弹 / 压弹头 / 装药 / 底火 / 入口)。 */
    private static double beat(MunitionsBenchLights.Frame frame, int channel) {
        return switch (channel) {
            case 0 -> frame.strike;
            case 1 -> frame.drop;
            case 2 -> frame.gem;
            case 3 -> frame.gemEcho;
            default -> frame.leds[channel - 4];
        };
    }

    /** 程序里某一路 (0 冲头 / 1 底火冲杆 / 2 装药管 / 3 出弹) 第一次到最低点的循环 tick (每 0.25 tick 取样)。 */
    private static float firstLowest(MunitionsBenchProgram.Pose pose, int channel) {
        float lowest = Float.MAX_VALUE;
        float at = -1.0F;
        for (int step = 0; step < MunitionsBenchProgram.CYCLE_TICKS * 4; step++) {
            float t = step * 0.25F;
            MunitionsBenchProgram.sample(t, pose);
            float v = switch (channel) {
                case 0 -> pose.ramY;
                case 1 -> pose.primeY;
                case 2 -> pose.powderY;
                default -> pose.dropY;
            };
            if (v < lowest - 1.0E-6F) {
                lowest = v;
                at = t;
            }
        }
        return at;
    }

    /**
     * 一个四边形的四个角 (整台像素, 朝北) 贴在它目标的元素面外: 同名元素 (两格拼起来) 里有这个面、面所在平面往外 lift 处、
     * 在元素面的范围往外胀 lift 以内; 顶点顺序叉乘出的法线朝面外; 元素的 shade 与目标的 shade 标志相同。
     */
    private static void assertOnElementFace(GameTestHelper helper, int tier, int step, int quad, int target, float[] corners, int o,
                                            List<float[]> elements, List<JsonObject> jsons, List<String> names) {
        String element = elementName(target);
        int face = MunitionsBenchGeometry.LIGHT_TARGET_FACES[target];
        Direction direction = Direction.from3DDataValue(face);
        String faceKey = direction.getName();
        int axis = direction.getAxis().ordinal(); // X 0, Y 1, Z 2
        boolean positive = direction.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        float lift = MunitionsBenchGeometry.LIGHT_TARGET_LIFT_PX[target];
        float plane = MunitionsBenchGeometry.LIGHT_TARGET_RECTS_PX[target][axis];
        float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        boolean found = false;
        for (int i = 0; i < elements.size(); i++) {
            float[] box = elements.get(i);
            JsonObject json = jsons.get(i);
            if (!names.get(i).equals(element) || !json.getAsJsonObject("faces").has(faceKey)
                    || Math.abs(box[positive ? axis + 3 : axis] - plane) > 1.0E-5F) {
                continue;
            }
            found = true;
            boolean shade = !json.has("shade") || json.get("shade").getAsBoolean();
            if (shade != MunitionsBenchGeometry.LIGHT_TARGET_SHADED[target]) {
                helper.fail(quadWhere(tier, step, quad, target) + ": element " + element + " shade " + shade + " but the target says "
                        + MunitionsBenchGeometry.LIGHT_TARGET_SHADED[target]);
            }
            for (int a = 0; a < 3; a++) {
                lo[a] = Math.min(lo[a], box[a]);
                hi[a] = Math.max(hi[a], box[a + 3]);
            }
        }
        if (!found) {
            helper.fail(quadWhere(tier, step, quad, target) + ": the static model has no " + element + "." + faceKey + " at " + plane);
        }
        float out = positive ? plane + lift : plane - lift;
        for (int k = 0; k < 4; k++) {
            int p = o + k * 3;
            for (int a = 0; a < 3; a++) {
                float v = corners[p + a];
                boolean ok = a == axis ? Math.abs(v - out) < 1.0E-4F : v >= lo[a] - lift - 1.0E-4F && v <= hi[a] + lift + 1.0E-4F;
                if (!ok) {
                    helper.fail(quadWhere(tier, step, quad, target) + " corner " + k + ": " + "xyz".charAt(a) + " = " + v + " leaves "
                            + element + "." + faceKey + " (plane " + plane + " + lift " + lift + ", extent " + lo[a] + ".." + hi[a] + ")");
                }
            }
        }
        Vec3 a = corner(corners, o);
        Vec3 normal = corner(corners, o + 3).subtract(a).cross(corner(corners, o + 6).subtract(a)).normalize();
        if (!(Vec3.atLowerCornerOf(direction.getNormal()).dot(normal) > 0.999D)) {
            helper.fail(quadWhere(tier, step, quad, target) + ": the corner order faces " + normal + ", not out of " + faceKey
                    + " (textBackground would cull it)");
        }
    }

    /** 目标下标 → 静态元素名 (取自 Geometry 行尾注释的同一份设计: 按组)。 */
    private static String elementName(int target) {
        if (contains(MunitionsBenchGeometry.LIGHT_GROUPS[MunitionsBenchLights.GROUP_STRIP], target)) {
            return "strip";
        }
        if (contains(MunitionsBenchGeometry.LIGHT_GROUPS[MunitionsBenchLights.GROUP_RAIL], target)) {
            return "rail_b";
        }
        if (contains(MunitionsBenchGeometry.LIGHT_GROUPS[MunitionsBenchLights.GROUP_CROWN], target)) {
            return "crown_light";
        }
        if (contains(MunitionsBenchGeometry.LIGHT_GROUPS[MunitionsBenchLights.GROUP_CAN], target)) {
            return "can_strip";
        }
        return "gem";
    }

    /** 两个四边形在同一块面上 (同一个朝向、同一个平面) 且面内两根轴都有正长度的交叠。 */
    private static boolean overlapOnFace(MunitionsBenchLights.Frame f, int a, int b) {
        int face = MunitionsBenchGeometry.LIGHT_TARGET_FACES[f.target[a]];
        if (face != MunitionsBenchGeometry.LIGHT_TARGET_FACES[f.target[b]]) {
            return false;
        }
        int axis = Direction.from3DDataValue(face).getAxis().ordinal();
        if (Math.abs(f.lo[a * 3 + axis] - f.lo[b * 3 + axis]) > 1.0E-6F) {
            return false;
        }
        for (int k = 0; k < 3; k++) {
            if (k != axis && Math.min(f.hi[a * 3 + k], f.hi[b * 3 + k]) - Math.max(f.lo[a * 3 + k], f.lo[b * 3 + k]) <= 1.0E-6F) {
                return false;
            }
        }
        return true;
    }

    private static boolean contains(int[] values, int value) {
        for (int v : values) {
            if (v == value) {
                return true;
            }
        }
        return false;
    }

    private static Vec3 corner(float[] corners, int i) {
        return new Vec3(corners[i], corners[i + 1], corners[i + 2]);
    }

    private static float[] floats(com.google.gson.JsonArray array) {
        float[] out = new float[array.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = array.get(i).getAsFloat();
        }
        return out;
    }
}
