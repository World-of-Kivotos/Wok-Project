package com.miningdim.pressure;

import com.miningdim.core.Difficulty;
import com.miningdim.core.IMiningConfig;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * 动态压力子系统 GameTest。聚焦 Major 缺陷四 (耐压 danger 时间项接线) 的回归断言:
 *  - {@link DangerJobFactor#factorFor} 的接线/降级/钳制不变量 (未接线=1.0, provider 生效, 钳 (0,1]);
 *  - {@link Danger#evaluate} 的 tWin 累积/衰减随时间项系数缩放 (系数 < 1 累积/衰减更慢)。
 *
 * 断言具体业务数额 (删被测核心逻辑测试必挂, 禁 is-not-null 弱校验): 累积量 = round(TWIN_ACCRUE_PER_EVAL * factor),
 * 系数 0.6 时单次累积 12 (= round(20*0.6)), 系数 1.0 时累积 20 —— 若移除 evaluate 的系数缩放, 两者必相等, 测试挂。
 *
 * 纯逻辑断言: Danger / PlayerMiningData 可在内存直接构造; config 用本测试的最小 spec 默认值替身 (未触达方法抛
 * UnsupportedOperationException, 异常痛纪律: 本测试不该读到的配置项一旦被读即编程错)。涉及 ServerPlayer 的 seam
 * 用 MockGameTestPlayers.makeMockServerPlayerWithChannel(helper) 取真实 ServerPlayer。template = "empty"。
 */
@GameTestHolder(com.miningdim.core.MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class PressureGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "pressure";

    // ============================================================
    // DangerJobFactor seam: 未接线降级 / provider 生效 / 钳制 (Major 缺陷四)
    // ============================================================

    @GameTest(templateNamespace = com.miningdim.core.MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void dangerJobFactorUnboundDefaultsToOne(GameTestHelper helper) {
        DangerJobFactor.unbind();
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        helper.assertTrue(DangerJobFactor.factorFor(player) == 1.0f,
                "unbound DangerJobFactor must default to 1.0 (no scaling)");
        helper.succeed();
    }

    @GameTest(templateNamespace = com.miningdim.core.MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void dangerJobFactorReflectsBoundProvider(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        try {
            DangerJobFactor.bind(p -> 0.6f);
            helper.assertTrue(DangerJobFactor.factorFor(player) == 0.6f,
                    "bound provider value (0.6) must be returned verbatim within (0,1]");

            // 钳制: provider 漂移到 >1 截到 1.0 (耐压本意减压不加压)。
            DangerJobFactor.bind(p -> 1.5f);
            helper.assertTrue(DangerJobFactor.factorFor(player) == 1.0f,
                    "provider value > 1 must clamp to 1.0");

            // 钳制: provider 漂移到 <=0 不钳 0 (不实质免疫压力系统), 返回最小正值。
            DangerJobFactor.bind(p -> 0.0f);
            helper.assertTrue(DangerJobFactor.factorFor(player) > 0.0f,
                    "provider value <= 0 must not clamp to 0 (never fully immune to pressure)");
        } finally {
            DangerJobFactor.unbind();
        }
        helper.succeed();
    }

    // ============================================================
    // Danger.evaluate: tWin 累积/衰减随时间项系数缩放 (Major 缺陷四)
    // ============================================================

    @GameTest(templateNamespace = com.miningdim.core.MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void evaluateAccrueScalesWithJobFactor(GameTestHelper helper) {
        IMiningConfig config = new DangerTestConfig();
        Danger danger = new Danger();

        // 系数 1.0: 在区作业单次评估累积 TWIN_ACCRUE_PER_EVAL (= 20)。
        PlayerMiningData full = new PlayerMiningData(1L, 0L);
        danger.evaluate(full, Difficulty.EASY, true, 0.0f, 1.0f, 100L, config);
        helper.assertTrue(full.tWin() == 20,
                "factor 1.0 must accrue full TWIN_ACCRUE_PER_EVAL=20, got " + full.tWin());

        // 系数 0.6 (满级矿工耐压封底): 单次累积 round(20*0.6)=12, 严格少于满累积 (耐压减压生效)。
        PlayerMiningData resisted = new PlayerMiningData(1L, 0L);
        danger.evaluate(resisted, Difficulty.EASY, true, 0.0f, 0.6f, 100L, config);
        helper.assertTrue(resisted.tWin() == 12,
                "factor 0.6 must accrue round(20*0.6)=12, got " + resisted.tWin());
        helper.assertTrue(resisted.tWin() < full.tWin(),
                "resisted accrual must be strictly less than full accrual (pressure resistance must reduce buildup)");

        helper.succeed();
    }

    @GameTest(templateNamespace = com.miningdim.core.MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void evaluateDecayScalesWithJobFactor(GameTestHelper helper) {
        IMiningConfig config = new DangerTestConfig();
        Danger danger = new Danger();

        // 离区衰减: decayTicks 在默认 decayPerEval=0.2 下为基准 8 tick。系数 1.0 衰减 8, 系数 0.6 衰减 round(8*0.6)=5。
        PlayerMiningData full = new PlayerMiningData(1L, 0L);
        full.setTWin(100);
        danger.evaluate(full, Difficulty.EASY, false, 0.0f, 1.0f, 100L, config);
        helper.assertTrue(full.tWin() == 92,
                "factor 1.0 off-region must decay base 8 (100->92), got " + full.tWin());

        PlayerMiningData resisted = new PlayerMiningData(1L, 0L);
        resisted.setTWin(100);
        danger.evaluate(resisted, Difficulty.EASY, false, 0.0f, 0.6f, 100L, config);
        helper.assertTrue(resisted.tWin() == 95,
                "factor 0.6 off-region must decay round(8*0.6)=5 (100->95), got " + resisted.tWin());

        helper.succeed();
    }

    /**
     * 最小 IMiningConfig 替身: 仅 danger 子表方法返回 16.2.6 spec 默认值 (本测试 evaluate 仅触达这些)。
     * 其余方法抛 UnsupportedOperationException —— 本测试不该读它们, 一旦被读即被测路径意外扩散 (异常痛纪律, 不静默返默认值)。
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
        @Override public int loadRadiusChunks() { throw unused(); }
        @Override public int emptyInstanceTtlSeconds() { throw unused(); }
        @Override public int gcGraceSeconds() { throw unused(); }
        @Override public int gcScanIntervalTicks() { throw unused(); }
        @Override public int maxGenWorkers() { throw unused(); }

        private static UnsupportedOperationException unused() {
            return new UnsupportedOperationException("config method not exercised by PressureGameTests");
        }
    }
}
