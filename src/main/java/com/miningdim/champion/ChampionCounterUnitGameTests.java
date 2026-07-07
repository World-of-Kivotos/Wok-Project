package com.miningdim.champion;

import com.miningdim.core.MiningConstants;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * 反击单元 COUNTER_UNIT (Stage2 批3 技能) 纯逻辑 GameTest (ChampionStarAffix spec 7.4 + 红线 2 TDD)。
 *
 * 严禁触 Champions 加载路径: 只断言 {@link ChampionCounterUnitWindow} 反伤比/名义反伤折算 + 反击窗相位 + 锁定
 * 周期门槛 + 本源私有 20%/s 秒窗封顶, 与 {@link ChampionTargetLocks} 跨冠军互斥。全部断言为具体业务结果 (删被测
 * 折算/相位/封顶/互斥必挂)。真服 (Champions 已加载) 由 {@code ChampionCounterUnitHandler} 每秒扫近玩家冠军维护
 * 锁定周期/反击窗 + 受击点折反伤。
 *
 * template = "empty", batch = "champion_counter_unit"。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class ChampionCounterUnitGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "champion_counter_unit";
    private static final double EPS = 1e-6D;

    // ============================================================
    // 反伤比 5 档 + 名义反伤折算 (反伤比 × 该笔入伤)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void reflectRatioPerQuality(GameTestHelper helper) {
        helper.assertTrue(Math.abs(ChampionCounterUnitWindow.reflectRatio(AffixQuality.COMMON) - 0.40D) < EPS,
                "反伤比 普通 = 40%");
        helper.assertTrue(Math.abs(ChampionCounterUnitWindow.reflectRatio(AffixQuality.UNCOMMON) - 0.55D) < EPS,
                "反伤比 中级 = 55%");
        helper.assertTrue(Math.abs(ChampionCounterUnitWindow.reflectRatio(AffixQuality.RARE) - 0.70D) < EPS,
                "反伤比 高级 = 70%");
        helper.assertTrue(Math.abs(ChampionCounterUnitWindow.reflectRatio(AffixQuality.EPIC) - 0.85D) < EPS,
                "反伤比 超凡 = 85%");
        helper.assertTrue(Math.abs(ChampionCounterUnitWindow.reflectRatio(AffixQuality.LEGENDARY) - 1.00D) < EPS,
                "反伤比 闪耀 = 100%");
        // 名义反伤 = 反伤比 × 该笔入伤: 闪耀 100% × 50 入伤 = 50; 普通 40% × 100 入伤 = 40。
        helper.assertTrue(Math.abs(ChampionCounterUnitWindow.nominalRetaliation(AffixQuality.LEGENDARY, 50.0D) - 50.0D) < EPS,
                "闪耀 100% × 50 入伤 = 50 名义反伤");
        helper.assertTrue(Math.abs(ChampionCounterUnitWindow.nominalRetaliation(AffixQuality.COMMON, 100.0D) - 40.0D) < EPS,
                "普通 40% × 100 入伤 = 40 名义反伤");
        helper.succeed();
    }

    // ============================================================
    // 本源私有 20%/s 秒窗封顶 (80 血玩家 16/s 恰界, 多笔累计剪, 跨秒重置)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void sourcePrivateSecondCapClipsAt20Pct(GameTestHelper helper) {
        // 80 血玩家 -> 本源私有秒窗上限 = 20% × 80 = 16 HP/s。
        ChampionCounterUnitWindow win = new ChampionCounterUnitWindow(80.0D);
        helper.assertTrue(Math.abs(win.perSecondCap() - 16.0D) < EPS, "80 血本源私窗上限 = 16 HP/s");
        // 同一秒多笔累计剪: 10 全过 (累 10) -> 再 10 只过 6 (累恰 16) -> 再 10 过 0 (额度耗尽)。
        helper.assertTrue(Math.abs(win.admit(10.0D, 0L) - 10.0D) < EPS, "首笔 10 全过 (累计 10)");
        helper.assertTrue(Math.abs(win.admit(10.0D, 5L) - 6.0D) < EPS, "次笔剪到 6 (累计恰 16)");
        helper.assertTrue(Math.abs(win.secondAccumulated() - 16.0D) < EPS, "秒累计恰界 16");
        helper.assertTrue(Math.abs(win.admit(10.0D, 19L) - 0.0D) < EPS, "同秒三笔额度耗尽过 0");
        // 跨入新 20tick 秒窗 (tick 20) 额度恢复满 16: 首笔 10 全过。
        helper.assertTrue(Math.abs(win.admit(10.0D, 20L) - 10.0D) < EPS, "新秒窗首笔 10 全过 (额度已重置)");
        helper.succeed();
    }

    // ============================================================
    // 反击窗相位 (100tick 恰界出窗) + 锁定周期 (300tick 恰界就绪)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void windowPhaseBoundaryAt100Tick(GameTestHelper helper) {
        helper.assertTrue(ChampionCounterUnitWindow.isWithinWindow(0L), "距锁定 0tick = 窗内");
        helper.assertTrue(ChampionCounterUnitWindow.isWithinWindow(99L), "距锁定 99tick = 窗内");
        helper.assertTrue(!ChampionCounterUnitWindow.isWithinWindow(100L), "距锁定 100tick = 出窗 (恰界)");
        helper.assertTrue(!ChampionCounterUnitWindow.isWithinWindow(150L), "距锁定 150tick = 出窗");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void lockCycleReadyBoundaryAt300Tick(GameTestHelper helper) {
        helper.assertTrue(ChampionCounterUnitWindow.lockCycleReady(1000L, Long.MIN_VALUE),
                "从未锁定 = 就绪 (首锁即时)");
        helper.assertTrue(!ChampionCounterUnitWindow.lockCycleReady(1299L, 1000L), "距上锁 299tick != 就绪");
        helper.assertTrue(ChampionCounterUnitWindow.lockCycleReady(1300L, 1000L), "距上锁 300tick = 就绪 (恰界)");
        helper.succeed();
    }

    // ============================================================
    // ChampionTargetLocks 跨冠军互斥 (acquire 冲突 / release 复位 / 过期自动失效)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void targetLockMutexAcrossChampions(GameTestHelper helper) {
        // 独占本测试用 UUID (静态锁表跨测试共享, 不 reset 全表以免踩他测); 进入前显式清残留。
        UUID player = UUID.fromString("c0117e20-0000-0000-0000-0000000000f1");
        UUID champA = UUID.fromString("c0117e20-0000-0000-0000-00000000000a");
        UUID champB = UUID.fromString("c0117e20-0000-0000-0000-00000000000b");
        ChampionTargetLocks.release(player, champA);
        ChampionTargetLocks.release(player, champB);

        long now = 1000L;
        boolean first = ChampionTargetLocks.tryAcquire(
                player, ChampionTargetLocks.LockKind.COUNTER_UNIT, champA, now, 100L);
        helper.assertTrue(first, "首次对空闲玩家上锁成功");
        boolean second = ChampionTargetLocks.tryAcquire(
                player, ChampionTargetLocks.LockKind.COUNTER_UNIT, champB, now + 10L, 100L);
        helper.assertTrue(!second, "另一冠军对已锁玩家上锁失败 (跨冠军互斥)");

        // 非持锁冠军 release 不误摘他怪的锁。
        ChampionTargetLocks.release(player, champB);
        helper.assertTrue(ChampionTargetLocks.activeLock(player, now + 20L) != null, "非持锁者 release 不摘锁");

        // 持锁冠军 release 后另一冠军可再上锁。
        ChampionTargetLocks.release(player, champA);
        boolean reacquire = ChampionTargetLocks.tryAcquire(
                player, ChampionTargetLocks.LockKind.COUNTER_UNIT, champB, now + 30L, 100L);
        helper.assertTrue(reacquire, "持锁者 release 后另一冠军可再上锁");

        // 过期自动失效: champB 于 now+30 上锁 100tick -> 到期 tick = now+130; 恰界 activeLock 返 null 且可再 acquire。
        long afterExpiry = now + 30L + 100L;
        helper.assertTrue(ChampionTargetLocks.activeLock(player, afterExpiry) == null,
                "锁到期 (恰 100tick) activeLock 返 null");
        boolean afterExpiryAcquire = ChampionTargetLocks.tryAcquire(
                player, ChampionTargetLocks.LockKind.COUNTER_UNIT, champA, afterExpiry, 100L);
        helper.assertTrue(afterExpiryAcquire, "锁到期后可再上锁");

        // 清理本测试残留, 免污染同批他测的静态锁表。
        ChampionTargetLocks.release(player, champA);
        helper.succeed();
    }

    // ============================================================
    // 参数校验痛失败 (异常必须痛: 非法品质/负入伤/负锚点/非正 maxHP 抛 IAE)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void invalidArgumentsThrow(GameTestHelper helper) {
        helper.assertTrue(throwsIae(() -> ChampionCounterUnitWindow.reflectRatio(null)),
                "null 品质求反伤比须抛 IAE");
        helper.assertTrue(throwsIae(() -> ChampionCounterUnitWindow.nominalRetaliation(AffixQuality.COMMON, -1.0D)),
                "负入伤求名义反伤须抛 IAE");
        helper.assertTrue(throwsIae(() -> ChampionCounterUnitWindow.isWithinWindow(-1L)),
                "负锚点差求窗相位须抛 IAE");
        helper.assertTrue(throwsIae(() -> new ChampionCounterUnitWindow(0.0D)),
                "非正 attackerMaxHp 构造私窗累加器须抛 IAE");
        helper.succeed();
    }

    /** 断言某操作抛 IllegalArgumentException (异常必须痛, 不生吞)。 */
    private static boolean throwsIae(Runnable op) {
        try {
            op.run();
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }
}
