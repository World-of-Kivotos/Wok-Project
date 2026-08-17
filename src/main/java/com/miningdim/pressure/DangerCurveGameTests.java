package com.miningdim.pressure;

import com.miningdim.core.Difficulty;
import com.miningdim.core.IMiningConfig;
import com.miningdim.core.MiningConstants;
import com.miningdim.trap.TrapParams;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * danger 曲线回归 (F035): 重配前 Hard 出场 zoneTerm 恒顶格 (ZONE_HARD=1.00), 时间项永远拿不到预算,
 * "越待越危险"的成长曲线名存实亡。重配后 zoneTerm 拉开三档且各自卡在有语义的门下 (Danger.java 头部
 * 常量注释已详列口径), 出场值不再顶格, 且久留仍能爬到满压。
 *
 * 纯逻辑断言: {@link Danger#compose} 无副作用可直接调, config 用本类最小 spec 默认值替身 (照抄
 * PressureGameTests.DangerTestConfig 的写法, 未触达方法抛 UnsupportedOperationException, 异常痛纪律)。
 * template = "empty", 本类不接触世界。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class DangerCurveGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "danger_curve";

    // ============================================================
    // 用例 A: F035 杀手断言 —— Hard 出场不顶格 + 久留有成长区间
    // 把 ZONE_HARD 改回 1.00f (旧值) -> compose(HARD,0,...) = wZone*1.00 = 1.00, 前两条 (< LAVA 阈值
    // / < 0.80) 必挂 (出场即满压, danger 是常数, "危险不会随时间增长"复现)。
    //
    // 复核修正追加断言 (三次独立复核坐实的具体缺陷): 原 ZONE_HARD=0.50 与 TrapParams.DANGER_THRESH_CREEPER
    // 恰好浮点相等, compose(HARD,0,...)>=0.50 恒成立 -> 出生冻结一结束身后刷苦力怕立刻可触发, 零成长
    // 区间。原有断言只核对了 < LAVA(0.70) / < 0.80 两个更高的门, 对 0.50/0.55 这两个更低的门零覆盖,
    // 加下面两条堵死这个回归口子。
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void hardSpawnDangerNotMaxedAndGrowsWithTime(GameTestHelper helper) {
        IMiningConfig cfg = new DangerTestConfig();

        float atSpawn = Danger.compose(Difficulty.HARD, 0, 0.0f, cfg);
        helper.assertTrue(atSpawn < TrapParams.DANGER_THRESH_LAVA,
                "F035: HARD danger on spawn (tWin=0) must stay below the lava gate ("
                        + TrapParams.DANGER_THRESH_LAVA + "), got " + atSpawn);
        helper.assertTrue(atSpawn < 0.80f,
                "F035: HARD danger on spawn must stay below EXTREME's lower bound (0.80), got " + atSpawn);
        helper.assertTrue(SpawnTier.forDanger(atSpawn) != SpawnTier.EXTREME,
                "F035: HARD spawn tier must NOT be EXTREME at tWin=0, got " + SpawnTier.forDanger(atSpawn)
                        + " (danger=" + atSpawn + ")");
        helper.assertTrue(atSpawn < TrapParams.DANGER_THRESH_CREEPER,
                "F035 复核修正: HARD danger on spawn (tWin=0) must stay strictly below DANGER_THRESH_CREEPER ("
                        + TrapParams.DANGER_THRESH_CREEPER + ") — equal-or-above means the behind-creeper trap "
                        + "fires the instant spawn freeze ends, zero growth interval, got " + atSpawn);
        helper.assertTrue(atSpawn < TrapParams.DANGER_THRESH_COLLAPSE,
                "F035 复核修正: HARD danger on spawn (tWin=0) must also stay below DANGER_THRESH_COLLAPSE ("
                        + TrapParams.DANGER_THRESH_COLLAPSE + "), got " + atSpawn);

        float afterGrind = Danger.compose(Difficulty.HARD, 6000, 0.0f, cfg);
        helper.assertTrue(afterGrind >= 0.80f,
                "F035: sustained HARD mining (tWin=6000) must still climb to the EXTREME floor (0.80), got "
                        + afterGrind);

        helper.assertTrue(afterGrind > atSpawn,
                "F035: danger must strictly grow with time (growth curve must exist), spawn=" + atSpawn
                        + " afterGrind=" + afterGrind);
        float delta = afterGrind - atSpawn;
        helper.assertTrue(delta > 0.2f,
                "F035: growth margin must exceed 0.2 (time term must get real budget, not be swallowed by "
                        + "zoneTerm), got delta=" + delta + " (spawn=" + atSpawn + ", afterGrind=" + afterGrind + ")");

        helper.succeed();
    }

    // ============================================================
    // 用例 B: 三档天花板各自卡在有语义的门下 (时间项完全饱和时)
    // 删掉 ZONE_EASY/ZONE_MEDIUM 各自的预留余量 (例如把三档都设回原值使 easy 天花板越过 0.60 或
    // medium 越过 0.80) -> 对应断言必挂。
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void tierCeilingsRespectSemanticGates(GameTestHelper helper) {
        IMiningConfig cfg = new DangerTestConfig();
        int tWin = 200_000; // 足以让 timeTerm 饱和 (软封顶 1200 tick, 200000 已收敛到约 1.0)。

        float easyCeiling = Danger.compose(Difficulty.EASY, tWin, 0.0f, cfg);
        helper.assertTrue(easyCeiling < 0.60f,
                "F035: EASY ceiling must stay below SpawnTier.HIGH's lower bound (0.60), got " + easyCeiling);
        helper.assertTrue(easyCeiling < TrapParams.DANGER_THRESH_LAVA,
                "F035: EASY ceiling must stay below the lava threshold (" + TrapParams.DANGER_THRESH_LAVA
                        + "): Easy never spawns creepers or lava bursts, got " + easyCeiling);
        helper.assertTrue(TrapParams.difficultyFactor(Difficulty.EASY) == 0.0,
                "TrapParams.difficultyFactor(EASY) must be exactly 0.0 (no lethal static traps on Easy), got "
                        + TrapParams.difficultyFactor(Difficulty.EASY));

        float mediumCeiling = Danger.compose(Difficulty.MEDIUM, tWin, 0.0f, cfg);
        helper.assertTrue(mediumCeiling < 0.80f,
                "F035: MEDIUM ceiling must stay below EXTREME's lower bound (0.80): the max-pressure tier is "
                        + "Hard-exclusive, got " + mediumCeiling);

        float hardCeiling = Danger.compose(Difficulty.HARD, tWin, 0.0f, cfg);
        helper.assertTrue(hardCeiling >= 0.80f,
                "F035: HARD ceiling must reach the EXTREME floor (0.80) when time-saturated, got " + hardCeiling);

        helper.succeed();
    }

    // ============================================================
    // 用例 C: 难度序不被打乱 (固定 tWin 下三档严格递增)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void difficultyOrderPreservedAtFixedTWin(GameTestHelper helper) {
        IMiningConfig cfg = new DangerTestConfig();
        int tWin = 1200;

        float easy = Danger.compose(Difficulty.EASY, tWin, 0.0f, cfg);
        float medium = Danger.compose(Difficulty.MEDIUM, tWin, 0.0f, cfg);
        float hard = Danger.compose(Difficulty.HARD, tWin, 0.0f, cfg);

        helper.assertTrue(easy < medium,
                "at tWin=" + tWin + " EASY (" + easy + ") must be strictly less than MEDIUM (" + medium + ")");
        helper.assertTrue(medium < hard,
                "at tWin=" + tWin + " MEDIUM (" + medium + ") must be strictly less than HARD (" + hard + ")");

        helper.succeed();
    }

    // ============================================================
    // 用例 D: 出场值 (tWin=0) 三档可区分, 且分属三个不同 SpawnTier 档位
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void spawnValuesDistinguishableAcrossDifficulties(GameTestHelper helper) {
        IMiningConfig cfg = new DangerTestConfig();

        float easy = Danger.compose(Difficulty.EASY, 0, 0.0f, cfg);
        float medium = Danger.compose(Difficulty.MEDIUM, 0, 0.0f, cfg);
        float hard = Danger.compose(Difficulty.HARD, 0, 0.0f, cfg);

        helper.assertTrue(easy < medium,
                "spawn danger EASY (" + easy + ") must be strictly less than MEDIUM (" + medium + ")");
        helper.assertTrue(medium < hard,
                "spawn danger MEDIUM (" + medium + ") must be strictly less than HARD (" + hard + ")");

        SpawnTier tierEasy = SpawnTier.forDanger(easy);
        SpawnTier tierMedium = SpawnTier.forDanger(medium);
        SpawnTier tierHard = SpawnTier.forDanger(hard);
        helper.assertTrue(tierEasy != tierMedium && tierMedium != tierHard && tierEasy != tierHard,
                "the three difficulties' spawn danger must land in three DISTINCT SpawnTiers, got easy="
                        + tierEasy + " medium=" + tierMedium + " hard=" + tierHard);

        helper.succeed();
    }

    /**
     * 最小 IMiningConfig 替身: 仅 danger 子表方法返回 spec 默认值 (本测试仅触达这些)。
     * 其余方法抛 UnsupportedOperationException —— 本测试不该读它们, 一旦被读即被测路径意外扩散
     * (异常痛纪律, 不静默返默认值)。照抄 PressureGameTests.DangerTestConfig 的写法。
     */
    private static final class DangerTestConfig implements IMiningConfig {
        @Override public double dangerMax() { return 1.0; }
        @Override public double dangerWeightZone() { return 1.0; }
        @Override public double dangerWeightTime() { return 0.5; }
        @Override public double dangerWeightOre() { return 0.3; }
        @Override public double dangerTimeSoftCap() { return 60.0; }
        @Override public double dangerDecayPerEval() { return 0.2; }

        @Override public int globalCap() { throw unused(); }
        @Override public boolean queueOnOverflow() { throw unused(); }
        @Override public boolean sharedByDefault() { throw unused(); }
        @Override public int maxPartySize() { throw unused(); }
        @Override public int shareCap() { throw unused(); }
        @Override public int regionSizeChunks() { throw unused(); }
        @Override public int bufferChunks() { throw unused(); }
        @Override public int oreBaseWeight() { throw unused(); }
        @Override public double oreGlobalDensity() { throw unused(); }
        @Override public boolean useDatapackOreDistribution() { throw unused(); }
        @Override public double difficultyMultiplier(Difficulty difficulty) { throw unused(); }
        @Override public double trapBaseChance() { throw unused(); }
        @Override public double trapLocalRiskMax() { throw unused(); }
        @Override public boolean trapDynamicEnabled() { throw unused(); }
        @Override public int trapMinSpacingBlocks() { throw unused(); }
        @Override public int dangerEvalIntervalTicks() { throw unused(); }
        @Override public int mobSpawnIntervalTicks() { throw unused(); }
        @Override public int mobMaxPerPlayer() { throw unused(); }
        @Override public int mobMaxPerInstance() { throw unused(); }
        @Override public double mobBehindPlayerChance() { throw unused(); }
        @Override public int mobSpawnRadius() { throw unused(); }
        @Override public int spawnHeadroomBlocks() { throw unused(); }
        @Override public boolean spawnRequireSolidFloor() { throw unused(); }
        @Override public int spawnLavaAvoidRadius() { throw unused(); }
        @Override public boolean spawnAvoidTrapZones() { throw unused(); }
        @Override public int spawnPoolSize() { throw unused(); }
        @Override public boolean spawnMustBeMainComponent() { throw unused(); }
        @Override public int resetCooldownSeconds() { throw unused(); }
        @Override public boolean resetRequireEmpty() { throw unused(); }
        @Override public boolean resetKickOnForce() { throw unused(); }
        @Override public int resetConfirmationWindowSeconds() { throw unused(); }
        @Override public int autoResetHours(Difficulty difficulty) { throw unused(); }
        @Override public int autoResetWarnSeconds() { throw unused(); }
        @Override public List<String> placeWhitelist() { throw unused(); }
        @Override public String entryLabel(Difficulty difficulty) { throw unused(); }
        @Override public long entryFee(Difficulty difficulty) { throw unused(); }
        @Override public int loadRadiusChunks() { throw unused(); }
        @Override public int emptyInstanceTtlSeconds() { throw unused(); }
        @Override public int gcGraceSeconds() { throw unused(); }
        @Override public int gcScanIntervalTicks() { throw unused(); }
        @Override public int maxGenWorkers() { throw unused(); }

        private static UnsupportedOperationException unused() {
            return new UnsupportedOperationException("config method not exercised by DangerCurveGameTests");
        }
    }
}
