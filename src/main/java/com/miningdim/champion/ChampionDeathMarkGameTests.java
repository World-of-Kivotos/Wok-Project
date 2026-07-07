package com.miningdim.champion;

import com.miningdim.core.MiningConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageType;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * 精英怪压轴技能【命定之死 DEATH_MARK】(spec 7.4, ★8 超凡+) 纯逻辑 GameTest (TDD)。
 *
 * 严禁触 Champions 加载路径: 只断言 {@link ChampionDeathMarkMath} 的滚动 DPS 采样 + 阈值/进度/窗口数学、
 * {@link ChampionTargetLocks} 的跨冠军互斥、以及 champion_execution 真伤类型的标签成员。所有断言为具体业务结果
 * (删被测采样/阈值/门槛/标签必挂)。真服 (标记/衰减/处决/表现) 由 {@code ChampionDeathMarkHandler} 施加。
 *
 * template = "empty", batch = "champion_death_mark"。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class ChampionDeathMarkGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "champion_death_mark";
    private static final double EPS = 1e-6D;

    // ============================================================
    // 滚动 DPS 采样账 (加账 / 200tick 恰界过期 / DPS 计算)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void rollingSamplerExpiresAtWindowBoundary(GameTestHelper helper) {
        ChampionDeathMarkMath.RollingDamageSampler s = new ChampionDeathMarkMath.RollingDamageSampler();
        s.record(0L, 100.0D, false);
        s.record(50L, 200.0D, false);
        // 199t: 条目@0 (199-0=199 < 200) 与 @50 均在窗内 -> 300。
        helper.assertTrue(Math.abs(s.sampledDamage(199L) - 300.0D) < EPS, "199t 两条均在采样窗内 = 300");
        // 200t: 条目@0 恰满窗 (200-0=200 >= 200) 过期出窗, 仅 @50 (200-50=150) -> 200。
        helper.assertTrue(Math.abs(s.sampledDamage(200L) - 200.0D) < EPS, "200t 条目@0 恰界过期, 仅 @50 = 200");
        // 250t: 条目@50 恰满窗 (250-50=200) 过期 -> 0, 账空。
        helper.assertTrue(Math.abs(s.sampledDamage(250L) - 0.0D) < EPS, "250t 条目@50 恰界过期 = 0");
        helper.assertTrue(s.isEmpty(), "全过期后采样账空");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void sampledDpsUsesActiveFireSpan(GameTestHelper helper) {
        // 稀释修复 (真服验收: 点射 1~2s 的 150+ DPS 被固定 10s 除数摊成 13.7, 阈值形同虚设): DPS 按开火跨度折算。
        // 满跨度 (200tick=10s): 与旧口径等价, 1000/10 = 100 DPS。
        helper.assertTrue(Math.abs(ChampionDeathMarkMath.sampledDps(1000.0D, 200L) - 100.0D) < EPS,
                "满跨度 10s: 1000 -> 100 DPS (与旧口径等价)");
        // 点射 1s (20tick): 跨度钳到 2s 下限, 156 / 2 = 78 DPS (旧口径 15.6 = 稀释十倍, 删跨度钳制必挂)。
        helper.assertTrue(Math.abs(ChampionDeathMarkMath.sampledDps(156.0D, 20L) - 78.0D) < EPS,
                "点射 1s: 156 -> 78 DPS (钳 2s 下限, 不再被 10s 摊薄)");
        // 单发/空跨度 (0): 同钳 2s 下限。
        helper.assertTrue(Math.abs(ChampionDeathMarkMath.sampledDps(140.0D, 0L) - 70.0D) < EPS,
                "单发跨度 0: 140 -> 70 DPS (2s 下限)");
        // 超窗跨度 (理论不出现, 过期已剔): 上钳 10s。
        helper.assertTrue(Math.abs(ChampionDeathMarkMath.sampledDps(1000.0D, 400L) - 100.0D) < EPS,
                "跨度上钳 10s");
        helper.assertTrue(ChampionDeathMarkMath.sampledDps(0.0D, 200L) == 0.0D, "0 采样 = 0 DPS");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void samplerActiveSpanTracksFirstToLast(GameTestHelper helper) {
        ChampionDeathMarkMath.RollingDamageSampler s = new ChampionDeathMarkMath.RollingDamageSampler();
        helper.assertTrue(s.activeSpanTicks(0L) == 0L, "空账跨度 0");
        s.record(0L, 100.0D, false);
        helper.assertTrue(s.activeSpanTicks(10L) == 0L, "单条跨度 0 (由 2s 下钳兜底)");
        s.record(50L, 200.0D, false);
        helper.assertTrue(s.activeSpanTicks(199L) == 50L, "首末条目时距 = 50 tick");
        // 首条 @0 过期 (t=200) 后跨度随窗收缩: 仅剩 @50 单条 -> 0。
        helper.assertTrue(s.activeSpanTicks(200L) == 0L, "首条过期后跨度收缩");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void samplerRejectsNegativeAmount(GameTestHelper helper) {
        // 异常必须痛: 负入伤抛 IllegalArgumentException (不静默吞)。
        ChampionDeathMarkMath.RollingDamageSampler s = new ChampionDeathMarkMath.RollingDamageSampler();
        boolean threw = false;
        try {
            s.record(0L, -1.0D, false);
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        helper.assertTrue(threw, "负入伤须抛 IllegalArgumentException");
        helper.succeed();
    }

    // ============================================================
    // 阈值公式 (采样 DPS × 窗口 8 × 系数 1.6)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void thresholdFormulaWithCoefficient(GameTestHelper helper) {
        // 阈值 = (采样总伤/跨度秒) × 8 × 1.6。满跨度: 1000 over 10s -> DPS 100 -> 100 × 8 × 1.6 = 1280。
        double t = ChampionDeathMarkMath.markThreshold(AffixQuality.EPIC, 1000.0D, 200L);
        helper.assertTrue(Math.abs(t - 1280.0D) < EPS, "阈值 = DPS100 × 8窗 × 1.6系数 = 1280");
        // 闪耀系数同为 1.6: 500 over 10s -> DPS 50 -> 50 × 8 × 1.6 = 640。
        double leg = ChampionDeathMarkMath.markThreshold(AffixQuality.LEGENDARY, 500.0D, 200L);
        helper.assertTrue(Math.abs(leg - 640.0D) < EPS, "闪耀 500采样 -> DPS50 × 8 × 1.6 = 640");
        // 点射稀释修复: 156 over 20tick (钳 2s) -> DPS 78 -> 78 × 8 × 1.6 = 998.4 (旧口径仅 199.7, 白嫖口)。
        double burst = ChampionDeathMarkMath.markThreshold(AffixQuality.EPIC, 156.0D, 20L);
        helper.assertTrue(Math.abs(burst - 998.4D) < EPS, "点射按真实强度计阈值 = 998.4");
        // 系数确实取自词条表 (删 valueFor / 改 {0,0,0,1.6,1.6} 数组必挂)。
        helper.assertTrue(Math.abs(AffixDef.DEATH_MARK.valueFor(AffixQuality.EPIC) - 1.6D) < EPS, "命定系数(超凡) = 1.6");
        helper.assertTrue(Math.abs(AffixDef.DEATH_MARK.valueFor(AffixQuality.LEGENDARY) - 1.6D) < EPS, "命定系数(闪耀) = 1.6");
        helper.succeed();
    }

    // ============================================================
    // 无输出者不可标记 (防藏 DPS 白嫖)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void noOutputCannotBeMarked(GameTestHelper helper) {
        helper.assertTrue(!ChampionDeathMarkMath.isEligibleCandidate(0.0D), "零输出 不可标记");
        helper.assertTrue(ChampionDeathMarkMath.isEligibleCandidate(0.5D), "有输出 (0.5) 可标记");
        // 空账采样 0 -> 不可标记。
        ChampionDeathMarkMath.RollingDamageSampler s = new ChampionDeathMarkMath.RollingDamageSampler();
        helper.assertTrue(!ChampionDeathMarkMath.isEligibleCandidate(s.sampledDamage(0L)), "空采样账 = 0 不可标记");
        // 有输出但全过期 -> 采样 0 -> 不可标记 (藏 DPS 后停手 10s 即失去候选资格)。
        s.record(0L, 300.0D, false);
        helper.assertTrue(!ChampionDeathMarkMath.isEligibleCandidate(s.sampledDamage(200L)), "全过期账 = 0 不可标记");
        helper.succeed();
    }

    // ============================================================
    // ×0.7 口径一致性 + 标记期暂停采样 (防衰减污染下次阈值)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void decayAndSuppressionConsistency(GameTestHelper helper) {
        // ×0.7 只作用于 BOSS 实际掉血 (迫使补刀); 进度口径 = 衰减【前】名义 (对抗审查修正)。
        helper.assertTrue(Math.abs(ChampionDeathMarkMath.decayedDamage(100.0D) - 70.0D) < EPS, "名义 100 ×0.7 = 70 (BOSS 实际掉血)");
        helper.assertTrue(Math.abs(ChampionDeathMarkMath.DAMAGE_DECAY_MULTIPLIER - 0.7D) < EPS, "衰减系数 = 0.7");
        // 口径回归钉死 (对抗审查 major): 进度按衰减前累计 -> 持续 1.6 倍采样强度恰达标; 若误按衰减后累计
        // (×0.7), 同等输出只积累 0.7×1.6=1.12 倍 DPS 分子 < 1.6 倍阈值 -> 反例断言必挂。
        double threshold = ChampionDeathMarkMath.markThreshold(AffixQuality.EPIC, 1000.0D, 200L); // DPS100 -> 1280。
        double nominalAt16x = 100.0D * 1.6D * 8.0D;                                          // 1.6 倍强度 8s 名义 = 1280。
        helper.assertTrue(ChampionDeathMarkMath.thresholdReached(nominalAt16x, threshold),
                "衰减前口径: 1.6 倍采样强度恰达标");
        helper.assertTrue(!ChampionDeathMarkMath.thresholdReached(
                        ChampionDeathMarkMath.decayedDamage(nominalAt16x), threshold),
                "反例: 若按衰减后口径累计, 1.6 倍强度不达标 (1280×0.7=896 < 1280)");
        // 暂停采样: 标记期该玩家的衰减入伤 (suppressed=true) 不入账, 采样不被污染。
        ChampionDeathMarkMath.RollingDamageSampler s = new ChampionDeathMarkMath.RollingDamageSampler();
        s.record(0L, 500.0D, false); // 标记前正常采样。
        double before = s.sampledDamage(10L);
        s.record(10L, 70.0D, true);  // 标记期衰减入伤 -> 暂停采样 (丢弃)。
        s.record(15L, 70.0D, true);
        double after = s.sampledDamage(20L);
        helper.assertTrue(Math.abs(before - 500.0D) < EPS, "标记前采样 = 500");
        helper.assertTrue(Math.abs(after - 500.0D) < EPS, "标记期 suppressed 入伤不入账, 采样仍 500 (不被衰减污染)");
        helper.assertTrue(s.size() == 1, "仅标记前 1 条在账 (2 条衰减入伤被丢弃)");
        helper.succeed();
    }

    // ============================================================
    // 窗口 / CD / actionbar 读数门槛 (含 Long.MIN_VALUE 从未态)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void windowAndCooldownGates(GameTestHelper helper) {
        // 处决伤害 = maxHealth × 1.0。
        helper.assertTrue(Math.abs(ChampionDeathMarkMath.executionDamage(80.0D) - 80.0D) < EPS, "处决 = maxHealth 80 × 1.0");
        // 窗耗尽 160tick: 恰 160 -> 耗尽; 159 -> 未耗尽。
        helper.assertTrue(ChampionDeathMarkMath.windowExpired(1160L, 1000L), "距标记 160t = 窗耗尽");
        helper.assertTrue(!ChampionDeathMarkMath.windowExpired(1159L, 1000L), "距标记 159t != 窗耗尽");
        // CD 900tick: 恰 900 -> 就绪; 899 -> 未就绪; 从未标记 -> 就绪。
        helper.assertTrue(ChampionDeathMarkMath.cooldownReady(1900L, 1000L), "距结束 900t = CD 就绪");
        helper.assertTrue(!ChampionDeathMarkMath.cooldownReady(1899L, 1000L), "距结束 899t != CD 就绪");
        helper.assertTrue(ChampionDeathMarkMath.cooldownReady(500L, Long.MIN_VALUE), "从未标记 = CD 就绪");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void windowExpiredRejectsInactiveMark(GameTestHelper helper) {
        // 无活动标记 (markTick=MIN_VALUE) 问窗口是调用方 bug: 抛不掩盖 (异常必须痛)。
        boolean threw = false;
        try {
            ChampionDeathMarkMath.windowExpired(1000L, Long.MIN_VALUE);
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        helper.assertTrue(threw, "无活动标记问窗口须抛");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void remainingAndProgressReadouts(GameTestHelper helper) {
        // 标记起 (now=mark=1000): 剩余 160t = 8s。
        helper.assertTrue(ChampionDeathMarkMath.remainingTicks(1000L, 1000L) == 160L, "标记起 剩余 160t");
        helper.assertTrue(ChampionDeathMarkMath.remainingSeconds(1000L, 1000L) == 8, "标记起 剩余 8s");
        // 过 140t: 剩余 20t = 1s; 过 141t: 剩余 19t 向上取整仍 1s。
        helper.assertTrue(ChampionDeathMarkMath.remainingSeconds(1140L, 1000L) == 1, "过 140t 剩余 1s");
        helper.assertTrue(ChampionDeathMarkMath.remainingSeconds(1141L, 1000L) == 1, "过 141t 剩余 19t 向上取整 1s");
        // 超窗 remainingTicks 下钳 0。
        helper.assertTrue(ChampionDeathMarkMath.remainingTicks(1200L, 1000L) == 0L, "超窗 剩余下钳 0");
        // 进度百分比: 640/1280=50%; 达标 100%; 超额钳 100%; 零阈值 (理论不出现) 视为 100 防除零。
        helper.assertTrue(ChampionDeathMarkMath.progressPercent(640.0D, 1280.0D) == 50, "640/1280 = 50%");
        helper.assertTrue(ChampionDeathMarkMath.progressPercent(1280.0D, 1280.0D) == 100, "达标 = 100%");
        helper.assertTrue(ChampionDeathMarkMath.progressPercent(2000.0D, 1280.0D) == 100, "超额钳 100%");
        helper.assertTrue(ChampionDeathMarkMath.progressPercent(0.0D, 0.0D) == 100, "零阈值防除零视为 100%");
        helper.succeed();
    }

    // ============================================================
    // 跨冠军互斥 (命定/反击 一玩家一锁, ChampionTargetLocks)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void crossChampionLockMutex(GameTestHelper helper) {
        UUID player = UUID.randomUUID();
        UUID champA = UUID.randomUUID();
        UUID champB = UUID.randomUUID();
        long now = 1000L;
        long dur = ChampionDeathMarkMath.WINDOW_TICKS; // 锁 duration = 标记窗 160。
        // champA 首次抢锁成功。
        helper.assertTrue(ChampionTargetLocks.tryAcquire(player, ChampionTargetLocks.LockKind.DEATH_MARK, champA, now, dur),
                "champA 首次抢玩家锁成功");
        // champB 抢同一玩家锁失败 (一玩家一锁, 两只 8★ 同场不叠锁)。
        helper.assertTrue(!ChampionTargetLocks.tryAcquire(player, ChampionTargetLocks.LockKind.DEATH_MARK, champB, now, dur),
                "champB 抢已占玩家锁失败");
        // 非持锁者 release 不摘 champA 的锁。
        ChampionTargetLocks.release(player, champB);
        helper.assertTrue(!ChampionTargetLocks.tryAcquire(player, ChampionTargetLocks.LockKind.DEATH_MARK, champB, now, dur),
                "错误持锁者 release 无效, 锁仍在");
        // champA 释放后 champB 可抢。
        ChampionTargetLocks.release(player, champA);
        helper.assertTrue(ChampionTargetLocks.tryAcquire(player, ChampionTargetLocks.LockKind.DEATH_MARK, champB, now, dur),
                "champA 释放后 champB 可抢");
        // 清理: 摘本测试玩家锁, 防污染全局静态锁表 (随机 UUID 不与他测冲突, 仍显式清)。
        ChampionTargetLocks.release(player, champB);
        helper.succeed();
    }

    // ============================================================
    // champion_execution 真伤类型标签成员 (照 champion_thorns 范式)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void executionDamageTypeIsTrueDamage(GameTestHelper helper) {
        // 处决真伤: champion_execution 须注册且入 bypasses_armor + bypasses_enchantments (删 damage_type JSON 或标签
        // 条目 -> 此测试必挂, 处决退化回被护甲吃掉不再必死)。
        Registry<DamageType> reg = helper.getLevel().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE);
        Holder<DamageType> holder = reg.getHolderOrThrow(ChampionDamageTypes.CHAMPION_EXECUTION);
        helper.assertTrue(holder.is(DamageTypeTags.BYPASSES_ARMOR),
                "champion_execution must bypass armor (maxHealth true damage lands full)");
        helper.assertTrue(holder.is(DamageTypeTags.BYPASSES_ENCHANTMENTS),
                "champion_execution must bypass protection enchants");
        // 保守边界: 不入 bypasses_invulnerability (保无敌帧/不死图腾可救, 非无条件删档)。
        helper.assertTrue(!holder.is(DamageTypeTags.BYPASSES_INVULNERABILITY),
                "champion_execution must NOT bypass invulnerability (totem/i-frames can still save)");
        helper.succeed();
    }
}
