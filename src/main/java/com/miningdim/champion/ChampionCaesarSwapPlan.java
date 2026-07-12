package com.miningdim.champion;

/**
 * 精英怪【技能词条·凯撒实验型转换器 CAESAR_SWAP】(批4 波2; ChampionStarAffix spec 7.4, ★5 技能) 的 CD 折算 +
 * 缰绳/门控/预兆取消判定 纯逻辑。凯撒转换器与传送家族其它成员 (闪光抵近/战术脱离/利刃突袭) 不同: 它不是自体
 * 瞬移, 而是【与目标玩家换位】—— 冠军把玩家拽到自己脚下、自己挪到玩家原位, 逼近战玩家瞬间失位 (从安全站位被
 * 拉回怪堆)。故它多两道传送家族没有的判定: 换位前 1s 双方预兆 (给玩家反制窗) + 换位落点【双向】安全 (两个格都
 * 得能站人)。
 *
 * 本类把 {@link AffixDef#CAESAR_SWAP} 品质档折算成: 施放 CD (tick) + "扫描步进累加 -> 到点判定"的周期状态推进,
 * 并承载三类硬判定的纯真值: 缰绳 24 格 (超出冻结不耗周期, 与传送家族同口径) / 预兆期取消条件 (目标死亡/离线/
 * 跑出缰绳) / 换位落点双向安全 (玩家目的格 + 冠军目的格分别经守卫裁 SAFE, 两者都过才换)。
 *
 * 纯函数集合, 不碰世界/实体/Champions/net.minecraft, GameTest 直接断言 (删被测折算/门控/取消/落点判定必挂)。
 * 分工: 本类只算 "CD tick + 周期推进到点 + 缰绳/门控 boolean + 预兆取消真值 + 落点双向安全真值"; 实际预兆表现
 * (双方 Glowing/旋环粒子/actionbar/警告音)、逐格 {@code KnockbackSafetyGuard.evaluateLanding} 裁决、双向
 * {@code teleportTo} 换位、换位后 {@code PlayerLandingProtection.grant} 由 integration 层
 * {@code ChampionCaesarSwapHandler} 施加 (真服验)。
 */
public final class ChampionCaesarSwapPlan {

    private ChampionCaesarSwapPlan() {
    }

    /** tick/秒 (CD 秒表 → tick 折算基)。 */
    public static final long TICKS_PER_SECOND = 20L;

    /**
     * 扫描/周期步进粒度 (tick): handler 每 1s 扫一次近玩家冠军, 有存活目标【且在缰绳内】的扫描把充能循环推进一个
     * 此粒度 (与 {@code ChampionCaesarSwapHandler} 的 ServerTick 节流对齐; 与闪光/战术传送同 1s 节奏)。
     */
    public static final long SCAN_INTERVAL_TICKS = 20L;

    /**
     * 预兆时长 (tick): 用户裁定 1s = 20 tick。换位前双方发光 + 脚下旋环粒子 + 目标 actionbar 警告的窗口长度 ——
     * 给近战玩家"即将被拽走"的反制/心理准备窗 (不同于闪光/战术传送的自体瞬移无预兆: 换位强制位移玩家, spec 要求
     * 预兆可感知)。
     */
    public static final long TELEGRAPH_TICKS = 20L;

    /**
     * 缰绳半径 (格; 主线拍板 波2, 与传送家族同口径): 冠军距目标玩家 &lt;= 此值才推进 CD 计时 + 维持预兆, 超出
     * 冻结不耗周期 / 预兆期跑出即取消。
     */
    public static final double TETHER_RANGE = 24.0D;

    /** 缰绳半径平方 (格²): 与 {@code entity.distanceToSqr} 同量纲, 免开方。 */
    public static final double TETHER_RANGE_SQ = TETHER_RANGE * TETHER_RANGE;

    /**
     * 该品质施放 CD (tick) = {@link AffixDef#CAESAR_SWAP} 品质档秒 × 20 (20/17/14/12/10 s = 400/340/280/240/200 tick)。
     * 直接从 {@link AffixDef} 折算而非另立常量表 —— CD 秒值的单一权威在 {@link AffixDef#CAESAR_SWAP} 数值数组, 本法只
     * 折算时间单位, 避免第二份 tick 表随数值调整静默漂移 (与 {@link ChampionBlinkPlan#cycleTicks} 同处理)。CD 秒值
     * 均为整数, {@code Math.round} 仅防浮点表示误差, 折算恒精确。
     *
     * @param quality 凯撒转换器品质
     * @return CD tick (&gt;0)
     */
    public static long cdTicks(AffixQuality quality) {
        requireQuality(quality);
        return Math.round(AffixDef.CAESAR_SWAP.valueFor(quality) * TICKS_PER_SECOND);
    }

    /**
     * 推进充能循环一个扫描粒度 (handler 仅在【有存活目标且在缰绳内】的扫描 tick 调用; 无目标/超缰绳不调 = 不推进 =
     * 冻结不耗周期)。
     *
     * @param elapsedTicks 已累加循环 tick (须 &gt;=0)
     * @return 推进后的累加 tick
     */
    public static long advanceCycle(long elapsedTicks) {
        requireNonNegative(elapsedTicks);
        return elapsedTicks + SCAN_INTERVAL_TICKS;
    }

    /**
     * CD 是否到点 (已累加 ≥ 该品质 CD): 到点则 handler 起一次预兆并把累加清零重计 (无论换位是否最终成行, CD 照走
     * 不补偿, 单一权威在 handler)。
     *
     * @param elapsedTicks 已累加循环 tick (须 &gt;=0)
     * @param quality      凯撒转换器品质
     * @return 是否到点
     */
    public static boolean cycleReady(long elapsedTicks, AffixQuality quality) {
        requireNonNegative(elapsedTicks);
        requireQuality(quality);
        return elapsedTicks >= cdTicks(quality);
    }

    /**
     * 缰绳判定: 冠军到目标玩家的距离平方 &lt;= {@value #TETHER_RANGE} 格² (含边界) 即在缰绳内。超出则 handler 冻结
     * 周期不推进 (充能期) 或取消预兆 (预兆期)。
     *
     * @param distanceSq 冠军到目标玩家的距离平方 (须 &gt;=0)
     * @return 是否在缰绳内
     */
    public static boolean withinTether(double distanceSq) {
        if (distanceSq < 0.0D || Double.isNaN(distanceSq)) {
            throw new IllegalArgumentException("distanceSq must be >= 0, got " + distanceSq);
        }
        return distanceSq <= TETHER_RANGE_SQ;
    }

    /**
     * 充能推进门控真值: 有存活攻击目标玩家 且 在缰绳内 才推进 CD 计时; 任一不满足则冻结不耗周期。
     *
     * @param hasLivingTarget 冠军是否有存活的攻击目标玩家 (handler 侧世界查询)
     * @param distanceSq      冠军到该目标的距离平方 (格²; hasLivingTarget=false 时本参数不参与判定)
     * @return 是否应推进本次 CD 计时
     */
    public static boolean shouldAdvanceCycle(boolean hasLivingTarget, double distanceSq) {
        return hasLivingTarget && withinTether(distanceSq);
    }

    /**
     * 预兆期取消判定真值 (spec 波2: 预兆期目标死亡/离线/跑出缰绳 -> 取消本次, CD 照走不重置预兆)。三条取消条件任一
     * 成立即取消 = 目标不再存活 或 不再在线 或 跑出缰绳。全真 (存活 + 在线 + 在缰绳内) 才继续预兆到点换位。
     *
     * <p>抽成纯逻辑单点 (供 GameTest 8 组合真值表精确断言): 删任一条件都会翻转某组合 —— 删 alive 则目标死亡仍换位;
     * 删 online 则目标离线仍换位 (对空玩家瞬移 NPE); 删 withinLeash 则跑出缰绳仍强拽 (破缰绳语义)。
     *
     * @param targetAlive  目标玩家是否存活
     * @param targetOnline 目标玩家是否仍在线且在本维度可解析
     * @param withinLeash  冠军到目标是否仍在缰绳内 (见 {@link #withinTether})
     * @return 是否应取消本次预兆
     */
    public static boolean telegraphShouldCancel(boolean targetAlive, boolean targetOnline, boolean withinLeash) {
        return !(targetAlive && targetOnline && withinLeash);
    }

    /**
     * 换位落点双向安全真值 (spec 波2: 玩家目的格 = 冠军当前格 + 冠军目的格 = 玩家当前格, 两格分别经守卫
     * {@code KnockbackSafetyGuard.evaluateLanding}, 都 SAFE 才换)。两格任一非 SAFE (岩浆/火/虚空边缘/被填埋) 则放弃
     * 本次换位 (走 CD, trace 日志) —— 换位是双向瞬移, 单侧落点危险就会把玩家或冠军塞进死地, 必须两侧同安全。
     *
     * <p>抽成纯逻辑单点 (供 GameTest 4 组合真值表精确断言): 逐格 SAFE/否由守卫 (世界依赖) 裁定并由
     * {@code KnockbackSafetyGuardGameTests} 覆盖, 本法只裁"两侧都安全"的合取 —— 改 && 为 || 则单侧安全即强换, 真值表必挂。
     *
     * @param playerDestSafe 玩家目的格 (= 冠军当前格) 是否守卫裁 SAFE
     * @param champDestSafe  冠军目的格 (= 玩家当前格) 是否守卫裁 SAFE
     * @return 两侧都安全 (可换位) 才为真
     */
    public static boolean bothLandingsSafe(boolean playerDestSafe, boolean champDestSafe) {
        return playerDestSafe && champDestSafe;
    }

    private static void requireQuality(AffixQuality quality) {
        if (quality == null) {
            throw new IllegalArgumentException("quality must not be null");
        }
    }

    private static void requireNonNegative(long elapsedTicks) {
        if (elapsedTicks < 0L) {
            throw new IllegalArgumentException("elapsedTicks must be >= 0, got " + elapsedTicks);
        }
    }
}
