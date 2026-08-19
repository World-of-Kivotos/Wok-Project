package com.miningdim.pressure;

import com.miningdim.core.Difficulty;
import com.miningdim.core.IMiningConfig;
import com.miningdim.testutil.MockGameTestPlayers;
import com.miningdim.trap.DynamicTrapEngine;
import com.miningdim.trap.TrapParams;
import com.miningdim.trap.TrapSystem;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.BusBuilder;
import net.minecraftforge.eventbus.api.IEventBus;
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

    // ============================================================
    // C3: PressureSystem.register 注入 DangerSource -> 复活动态陷阱
    // 回归锚点: 注入前 DynamicTrapEngine.dangerSource 恒 (p,i)->0f, danger 恒 0, 三类动态陷阱永不触发;
    // 注入后陷阱引擎经注入源读到真实 danger。把玩家压力态推到 HARD 区满 danger (>= 岩浆阈值 0.70, 最高门),
    // 断言 TrapSystem 注入源对该玩家返回值 = 该 danger (> 0 且 >= LAVA 阈值), 证明岩浆/坍塌/苦力怕三门都会过。
    // 删 PressureSystem.register 末尾的 setDangerSource 注入 -> 引擎保留 0f stub -> injectedDangerOf 恒 0 -> 本测试必挂。
    // ============================================================

    @GameTest(templateNamespace = com.miningdim.core.MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void registerInjectsDangerSourceRevivingDynamicTraps(GameTestHelper helper) {
        // 用全新 TrapSystem + PressureSystem 走真实 register 接线 (而非手搓 lambda), 测的是 register 这一行注入。
        // 事件订阅挂一条临时丢弃总线 (不污染运行服真实 forge 总线; 本测试只读注入源, 不依赖 tick)。
        IEventBus throwaway = BusBuilder.builder().build();
        TrapSystem originalTrap = TrapSystem.get();
        try {
            TrapSystem trap = new TrapSystem();
            trap.register(throwaway, throwaway); // 设 TrapSystem.instance = trap, dangerSource 仍是 0f stub
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);

            // 注入前: 引擎仍是恒 0f stub (此处经新 trap 直接验证 stub 起点)。
            helper.assertTrue(trap.injectedDangerOf(player, 1L) == 0.0f,
                    "before PressureSystem injection the trap engine reads the 0f stub (dynamic traps idle)");

            PressureSystem pressure = new PressureSystem();
            pressure.register(throwaway, throwaway); // register 末尾 setDangerSource: 接 mobPressure.danger()

            // F035 重配后 zone 常量不再让 Hard 出场即顶格 (zone(HARD)=0.50, 留满时间项 0.50 的余量),
            // 本测试要断的是"注入源接线"而不是"出场即满"。先验证正向断言: 出生 (tWin 仍低) 时 danger
            // 严格低于岩浆阈值; 再在同一压力态上持续爬坡, 证明时间项接线仍能把 danger 推过阈值。
            IMiningConfig config = new DangerTestConfig();
            long instanceId = 1L;
            PlayerMiningData data = pressure.mobPressure().danger().onEnter(player.getUUID(), instanceId, 0L);
            long tick = 100L;
            float danger = pressure.mobPressure().danger()
                    .evaluate(data, Difficulty.HARD, true, 0.0f, 1.0f, tick, config);
            helper.assertTrue(danger < TrapParams.DANGER_THRESH_LAVA,
                    "F035: HARD-zone danger on spawn (tWin still low) must NOT reach the lava gate; got " + danger);

            // 爬坡: tWin 每次 evaluate 累积 TWIN_ACCRUE_PER_EVAL (=20), timeSoftCap=60s=1200 tick 下
            // tWin~=620 (danger~=0.50+0.5*0.40=0.70) 即可越过岩浆阈值; 循环 200 次 (tWin 累积到 4000,
            // timeTerm=1-exp(-4000/1200)≈0.964, danger≈0.50+0.48=0.98) 留足冗余, 越线即提前退出。
            for (int i = 0; i < 200 && danger < TrapParams.DANGER_THRESH_LAVA; i++) {
                tick += 20L;
                danger = pressure.mobPressure().danger()
                        .evaluate(data, Difficulty.HARD, true, 0.0f, 1.0f, tick, config);
            }
            helper.assertTrue(danger >= TrapParams.DANGER_THRESH_LAVA,
                    "HARD-zone danger after sustained active mining must still reach the highest (lava) gate; got " + danger);

            // 注入后: 陷阱引擎门控所读的同一注入源对该玩家返回真实 danger (> 0 且 >= 岩浆阈值)。
            float injected = trap.injectedDangerOf(player, instanceId);
            helper.assertTrue(injected == danger,
                    "injected DangerSource must return the player's live danger (" + danger + "), got " + injected);
            helper.assertTrue(injected > 0.0f,
                    "injected danger must be > 0 (regression guard: not the 0f stub)");
            helper.assertTrue(injected >= TrapParams.DANGER_THRESH_LAVA,
                    "injected danger clears the lava danger-threshold (danger-gate side; 节流闸另由 trapCooldownAllowsFirstTriggerNoOverflow 验)");

            // 无压力态玩家 (未进矿洞 / 已离开): 注入源回退 0f, 与 stub 退化语义一致 (不臆造 danger)。
            ServerPlayer noData = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            helper.assertTrue(trap.injectedDangerOf(noData, instanceId) == 0.0f,
                    "player with no pressure state reads 0f (graceful degrade, not fabricated danger)");
        } finally {
            // 还原运行服的真实 TrapSystem 单例 (本测试 register 期把 instance 改成了临时 trap)。
            originalTrap.register(throwaway, throwaway);
        }
        helper.succeed();
    }

    // ============================================================
    // 红队回归: 动态陷阱节流冷却判据不得 now - Long.MIN_VALUE 溢出致首次永不放行 (注入 danger 复活陷阱后此溢出
    // 使三类动态陷阱永久死锁; 用 cooldownAllows 的哨兵判修复)。
    // ============================================================

    @GameTest(templateNamespace = com.miningdim.core.MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void trapCooldownAllowsFirstTriggerNoOverflow(GameTestHelper helper) {
        // 哨兵 Long.MIN_VALUE = "从未触发" 必放行: 若写 now - Long.MIN_VALUE 会整数溢出成大负数, 恒 < 冷却 -> 首次 false -> 陷阱永久死锁。
        helper.assertTrue(DynamicTrapEngine.cooldownAllows(Long.MIN_VALUE, 24000L, 300L),
                "sentinel (never triggered) first call must pass at gameTime 24000 (no overflow)");
        helper.assertTrue(DynamicTrapEngine.cooldownAllows(Long.MIN_VALUE, 0L, 300L),
                "sentinel passes even at gameTime 0");
        helper.assertTrue(DynamicTrapEngine.cooldownAllows(Long.MIN_VALUE, 100_000_000L, 300L),
                "sentinel passes at large gameTime (no overflow)");
        // 已触发后冷却语义保持: 冷却未到不放行, 冷却到放行。
        helper.assertFalse(DynamicTrapEngine.cooldownAllows(24000L, 24000L + 299L, 300L),
                "within cooldown (299 < 300) must NOT pass");
        helper.assertTrue(DynamicTrapEngine.cooldownAllows(24000L, 24000L + 300L, 300L),
                "at cooldown boundary (300 >= 300) must pass");
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
        @Override public long entryFee(Difficulty difficulty) { return 0L; }
        @Override public int loadRadiusChunks() { throw unused(); }
        @Override public int emptyInstanceTtlSeconds() { throw unused(); }
        @Override public int gcGraceSeconds() { throw unused(); }
        @Override public int gcScanIntervalTicks() { throw unused(); }
        @Override public int maxGenWorkers() { throw unused(); }
        @Override public int creativeFlightMaxBlocksPerTick() { throw unused(); }

        private static UnsupportedOperationException unused() {
            return new UnsupportedOperationException("config method not exercised by PressureGameTests");
        }
    }
}
