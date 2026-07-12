package com.miningdim.champion;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 精英怪压轴技能【命定之死 DEATH_MARK】(ChampionStarAffix spec 7.4 命定之死, ★8 超凡+; Stage2 批3) 的滚动 DPS
 * 采样 + 阈值/进度/窗口数学纯逻辑。机制: 标记一名近期有输出的玩家, 限时 (8s) 内该玩家须对本怪打出阈值伤害,
 * 否则处决 (maxHealth 真伤必死)。
 *
 * 数值 (2026-07-07 用户拍板 + spec 7.4): 标记窗 8s=160tick; CD 45s=900tick; DPS 采样窗 10s=200tick;
 * 阈值 = 该玩家近 10s 对本怪的总伤害 / 开火跨度秒 (钳 [2,10]s, 稀释修复见 {@link #SPAN_FLOOR_TICKS}) × 8 (窗口秒)
 * × 1.6 (spec 系数 = AffixDef.DEATH_MARK.valueFor); 标记期该玩家对本怪伤害 ×0.7 (spec "衰减 30% 迫使补刀");
 * 处决 = 玩家 getMaxHealth() × 1.0。
 *
 * 口径一致性 (阈值与进度同口径 = 均为【衰减前名义值】; 对抗审查修正): 阈值的采样 DPS 以标记前名义入伤累计;
 * 标记期进度在 handler 的 NORMAL 衰减点按衰减【前】的名义值累计 —— 若按衰减后累计, 过关需持续 1.6/0.7 ≈ 2.29 倍
 * 采样 DPS, 对采样期已近满输出的玩家不可达 = 必死无反制 (违背红线 3 例外前提)。衰减前口径下自证门槛恒 1.6×
 * (采样含换弹空窗, 爆发可超); ×0.7 仍作用于 BOSS 实际掉血 = 迫使队友补刀。
 *
 * 采样污染防护 (直接暂停采样): 标记期该玩家对本怪的入伤已被 ×0.7 衰减, 若把衰减后条目计入采样账会压低【下一次】
 * 标记的阈值 (变相白嫖)。故 {@link RollingDamageSampler#record} 带 suppressed 标志 —— 标记期该玩家的入伤直接不入账
 * (暂停采样, 见 {@code ChampionDeathMarkHandler} 喂账口); 其它玩家标记期仍正常采样 (是下次标记的候选)。
 *
 * 纯函数 + 滚动账集合, 不碰世界/实体/Champions, GameTest 直接断言 (删被测折算/门槛/采样必挂)。实际标记/衰减/处决/
 * 表现层由 integration 层 {@code ChampionDeathMarkHandler} 施加 (真服验)。
 */
public final class ChampionDeathMarkMath {

    /** 标记窗时长 (tick): 8s (2026-07-07 用户拍板)。窗内未达阈值即处决。 */
    public static final long WINDOW_TICKS = 160L;

    /** 技能 CD (tick): 45s。从上一次标记【结束】(达标/处决/目标丢失) 起算, 就绪判定 = CD 过 + 有合法候选。 */
    public static final long COOLDOWN_TICKS = 900L;

    /** DPS 采样窗 (tick): 10s。滚动账只保留距今 &lt; 本窗的入伤条目, 恰满本窗 (>=) 即过期出窗。 */
    public static final long SAMPLE_WINDOW_TICKS = 200L;

    /** 每秒 tick 数 (剩余秒/进度刷新换算)。 */
    public static final int TICKS_PER_SECOND = 20;

    /** 标记窗秒数 (阈值公式的窗口因子; = WINDOW_TICKS / TICKS_PER_SECOND = 8)。 */
    public static final int WINDOW_SECONDS = (int) (WINDOW_TICKS / TICKS_PER_SECOND);

    /** 采样窗秒数 (采样跨度上钳; = SAMPLE_WINDOW_TICKS / TICKS_PER_SECOND = 10)。 */
    public static final int SAMPLE_WINDOW_SECONDS = (int) (SAMPLE_WINDOW_TICKS / TICKS_PER_SECOND);

    /**
     * 开火跨度下钳 (tick): 2s。真服首验发现: DPS 按固定 10s 除数稀释 —— 标记前只点射 1~2s 的玩家采样 DPS 被
     * 摊薄十倍 (实测 150+ DPS 记成 13.7), 阈值随之形同虚设 ("解除太简单")。改按【实际开火跨度】(首末采样条目
     * 时距) 折算强度, 下钳 2s 防单发除零/瞬时爆发虚高, 上钳采样窗 10s (与滚动过期一致)。
     */
    public static final long SPAN_FLOOR_TICKS = 40L;

    /** 标记期入伤衰减系数 (spec 7.4: 标记期对本怪伤害 ×0.7 迫使补刀)。 */
    public static final double DAMAGE_DECAY_MULTIPLIER = 0.7D;

    /** 处决伤害占玩家 maxHealth 的比例 (1.0 = 满血真伤必死; champion_execution 无视护甲/保护附魔)。 */
    public static final double EXECUTION_MAX_HEALTH_FRACTION = 1.0D;

    private ChampionDeathMarkMath() {
    }

    /**
     * 采样 DPS = 采样窗内名义入伤总和 / 开火跨度秒数 (跨度 = 首末采样条目时距, 钳 [2s, 10s]; 见
     * {@link #SPAN_FLOOR_TICKS} 稀释修复说明)。持续压制者跨度 ≈10s 与旧口径等价; 点射者按真实强度计,
     * 磨蹭不再降低阈值。
     *
     * @param sampledDamageTotal 采样窗内对本怪的名义入伤总和 (&gt;=0)
     * @param activeSpanTicks    开火跨度 tick ({@link RollingDamageSampler#activeSpanTicks}; 须 &gt;=0)
     * @return 每秒名义入伤 (&gt;=0)
     */
    public static double sampledDps(double sampledDamageTotal, long activeSpanTicks) {
        requireNonNegative(sampledDamageTotal, "sampledDamageTotal");
        if (activeSpanTicks < 0L) {
            throw new IllegalArgumentException("activeSpanTicks must be >= 0, got " + activeSpanTicks);
        }
        long clampedSpan = Math.min(SAMPLE_WINDOW_TICKS, Math.max(SPAN_FLOOR_TICKS, activeSpanTicks));
        return sampledDamageTotal / ((double) clampedSpan / TICKS_PER_SECOND);
    }

    /**
     * 标记阈值 = 采样 DPS (按开火跨度) × 窗口秒 (8) × spec 系数 (1.6 = {@link AffixDef#DEATH_MARK} valueFor)。
     * 窗内进度须达此值方可解除, 否则处决。
     *
     * @param quality            命定之死品质 (超凡/闪耀, 系数恒 1.6)
     * @param sampledDamageTotal 标记前采样窗内名义入伤总和 (&gt;=0; 阈值的 DPS 基数)
     * @param activeSpanTicks    开火跨度 tick (钳 [2s,10s] 后作 DPS 除数)
     * @return 阈值 (名义入伤口径; &gt;=0)
     */
    public static double markThreshold(AffixQuality quality, double sampledDamageTotal, long activeSpanTicks) {
        requireQuality(quality);
        requireNonNegative(sampledDamageTotal, "sampledDamageTotal");
        double coefficient = AffixDef.DEATH_MARK.valueFor(quality);
        return sampledDps(sampledDamageTotal, activeSpanTicks) * WINDOW_SECONDS * coefficient;
    }

    /**
     * 标记期入伤衰减: 名义入伤 ×0.7 (spec 7.4)。handler 在衰减侧 (NORMAL 优先级 setAmount) 用本系数, 本法供纯逻辑
     * 口径断言复用。
     *
     * @param nominalDamage 未衰减名义入伤 (&gt;=0)
     * @return 衰减后名义入伤
     */
    public static double decayedDamage(double nominalDamage) {
        requireNonNegative(nominalDamage, "nominalDamage");
        return nominalDamage * DAMAGE_DECAY_MULTIPLIER;
    }

    /**
     * 处决伤害 = 玩家 maxHealth × 1.0 (满血真伤)。champion_execution 无视护甲/保护附魔但不入 bypasses_invulnerability
     * (仍受无敌帧/不死图腾管辖), 故本值即名义处决量, 实际是否致死由无敌帧/图腾在 handler hurt 时裁决。
     *
     * @param playerMaxHealth 玩家最大生命 (&gt;0)
     * @return 处决名义伤害
     */
    public static double executionDamage(double playerMaxHealth) {
        requirePositive(playerMaxHealth, "playerMaxHealth");
        return playerMaxHealth * EXECUTION_MAX_HEALTH_FRACTION;
    }

    /**
     * 进度是否达标 (可解除): 标记期累计进度 &gt;= 阈值。进度与阈值同口径 (均名义入伤; 进度是 ×0.7 衰减后名义)。
     *
     * @param progress  标记期累计名义入伤进度 (&gt;=0)
     * @param threshold 阈值 (&gt;=0)
     * @return 是否达标
     */
    public static boolean thresholdReached(double progress, double threshold) {
        requireNonNegative(progress, "progress");
        requireNonNegative(threshold, "threshold");
        return progress >= threshold;
    }

    /**
     * 是否为合法标记候选: 采样窗内对本怪有名义输出 (&gt;0)。spec 红线"防藏 DPS 白嫖" —— 近 10s 对本怪零输出者不可被标记。
     *
     * @param sampledDamageTotal 该玩家采样窗内名义入伤总和 (&gt;=0)
     * @return 是否有资格被标记
     */
    public static boolean isEligibleCandidate(double sampledDamageTotal) {
        requireNonNegative(sampledDamageTotal, "sampledDamageTotal");
        return sampledDamageTotal > 0.0D;
    }

    /**
     * CD 是否就绪 (距上次标记结束 ≥45s)。lastEndTick = {@link Long#MIN_VALUE} (从未标记过) 视为就绪。
     *
     * @param nowTick     当前 gameTime tick
     * @param lastEndTick 上次标记结束 tick (Long.MIN_VALUE = 从未标记)
     * @return CD 是否过
     */
    public static boolean cooldownReady(long nowTick, long lastEndTick) {
        if (lastEndTick == Long.MIN_VALUE) {
            return true;
        }
        return nowTick - lastEndTick >= COOLDOWN_TICKS;
    }

    /**
     * 标记窗是否耗尽 (距标记起 ≥8s)。markTick 须为有效标记起点 (非 {@link Long#MIN_VALUE} —— 无活动标记不该问窗口,
     * 属调用方 bug 抛不掩盖)。
     *
     * @param nowTick  当前 gameTime tick
     * @param markTick 标记起点 tick (须 != Long.MIN_VALUE)
     * @return 窗是否耗尽
     */
    public static boolean windowExpired(long nowTick, long markTick) {
        requireActiveMark(markTick);
        return nowTick - markTick >= WINDOW_TICKS;
    }

    /**
     * 标记剩余 tick (actionbar 剩余秒换算用): WINDOW_TICKS - 已过 tick, 下钳 0。markTick 须有效。
     *
     * @param nowTick  当前 gameTime tick
     * @param markTick 标记起点 tick (须 != Long.MIN_VALUE)
     * @return 剩余 tick (&gt;=0)
     */
    public static long remainingTicks(long nowTick, long markTick) {
        requireActiveMark(markTick);
        long remaining = WINDOW_TICKS - (nowTick - markTick);
        return Math.max(remaining, 0L);
    }

    /**
     * 标记剩余秒 (actionbar 显示; 向上取整, 保留末秒可读)。
     *
     * @param nowTick  当前 gameTime tick
     * @param markTick 标记起点 tick (须 != Long.MIN_VALUE)
     * @return 剩余秒 (&gt;=0)
     */
    public static int remainingSeconds(long nowTick, long markTick) {
        long remaining = remainingTicks(nowTick, markTick);
        return (int) ((remaining + TICKS_PER_SECOND - 1) / TICKS_PER_SECOND);
    }

    /**
     * 进度百分比 (actionbar 显示; 钳到 [0,100] 整数)。threshold &lt;=0 (理论不出现, 候选恒有正输出) 视为 100%
     * (已达标) 防除零。
     *
     * @param progress  标记期累计进度 (&gt;=0)
     * @param threshold 阈值 (&gt;=0)
     * @return 进度百分比 [0,100]
     */
    public static int progressPercent(double progress, double threshold) {
        requireNonNegative(progress, "progress");
        requireNonNegative(threshold, "threshold");
        if (!(threshold > 0.0D)) {
            return 100;
        }
        int pct = (int) Math.floor(progress / threshold * 100.0D);
        return Math.max(0, Math.min(100, pct));
    }

    /**
     * per-(冠军,玩家) 滚动名义入伤账 (采样 DPS 权威): 按 tick 追加 [tick, amount] 条目, 惰性过期距今 &gt;=10s 的旧条目,
     * 对窗内条目求和即采样总伤。喂账须按【非递减 tick】追加 (handler 侧 gameTime 天然单调), 过期从队首连续剔除即可。
     *
     * suppressed 标志 = 标记期该玩家入伤已 ×0.7 衰减, 计入会压低下次阈值 (白嫖), 故直接不入账 (暂停采样)。
     */
    public static final class RollingDamageSampler {

        /** 一条采样: 发生 tick + 名义入伤量。 */
        private record Sample(long tick, double amount) {
        }

        /** 队首 = 最旧条目 (tick 非递减追加, 过期从队首连续剔除)。 */
        private final Deque<Sample> samples = new ArrayDeque<>();

        /**
         * 记一笔入伤。suppressed=true (标记期该玩家的衰减入伤) 直接丢弃不入账, 防污染下次阈值。
         *
         * @param tick       入伤发生 tick (须 &gt;= 上次追加 tick, 单调非递减)
         * @param amount     名义入伤 (&gt;=0)
         * @param suppressed 是否暂停采样 (标记期该玩家入伤)
         */
        public void record(long tick, double amount, boolean suppressed) {
            requireNonNegative(amount, "amount");
            if (suppressed) {
                return; // 标记期该玩家入伤 (×0.7 衰减后名义): 不入采样账, 防压低下次阈值。
            }
            samples.addLast(new Sample(tick, amount));
        }

        /** 从队首连续剔除距今 &gt;=10s 的过期条目 (恰满采样窗即出窗)。 */
        public void expire(long nowTick) {
            while (!samples.isEmpty() && nowTick - samples.peekFirst().tick() >= SAMPLE_WINDOW_TICKS) {
                samples.removeFirst();
            }
        }

        /**
         * 采样窗内 (近 10s) 名义入伤总和 (先过期再求和; 逐条累加不缓存, 免浮点漂移)。
         *
         * @param nowTick 当前 gameTime tick
         * @return 窗内名义入伤总和 (&gt;=0)
         */
        public double sampledDamage(long nowTick) {
            expire(nowTick);
            double sum = 0.0D;
            for (Sample s : samples) {
                sum += s.amount();
            }
            return sum;
        }

        /**
         * 开火跨度 (tick) = 窗内首末条目时距 (先过期; 空账/单条返 0, 由 {@link #sampledDps} 的 2s 下钳兜底)。
         * DPS 稀释修复的分母基数: 点射者按真实开火时段折强度, 而非固定 10s 摊薄。
         *
         * @param nowTick 当前 gameTime tick
         * @return 开火跨度 tick (&gt;=0)
         */
        public long activeSpanTicks(long nowTick) {
            expire(nowTick);
            if (samples.size() < 2) {
                return 0L;
            }
            return samples.peekLast().tick() - samples.peekFirst().tick();
        }

        /** 是否无在册条目 (过期剔除后; handler 据此回收空账)。 */
        public boolean isEmpty() {
            return samples.isEmpty();
        }

        /** 在册条目数 (测试/诊断)。 */
        public int size() {
            return samples.size();
        }
    }

    private static void requireActiveMark(long markTick) {
        if (markTick == Long.MIN_VALUE) {
            throw new IllegalArgumentException("markTick must be an active mark start, got Long.MIN_VALUE");
        }
    }

    private static void requireQuality(AffixQuality quality) {
        if (quality == null) {
            throw new IllegalArgumentException("quality must not be null");
        }
    }

    private static void requireNonNegative(double v, String name) {
        if (!(v >= 0.0D) || Double.isNaN(v)) {
            throw new IllegalArgumentException(name + " must be >= 0, got " + v);
        }
    }

    private static void requirePositive(double v, String name) {
        if (!(v > 0.0D) || Double.isNaN(v)) {
            throw new IllegalArgumentException(name + " must be > 0, got " + v);
        }
    }
}
