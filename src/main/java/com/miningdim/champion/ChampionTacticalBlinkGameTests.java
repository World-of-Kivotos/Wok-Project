package com.miningdim.champion;

import com.miningdim.core.MiningConstants;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * 精英怪【机动词条·战术传送 TACTICAL_BLINK】(批4 波1; ChampionStarAffix spec 7.2 脱离型) 纯逻辑 GameTest (TDD)。
 *
 * 严禁触 Champions 加载路径 (compileOnly 铁律): 只断言 {@link ChampionTacticalBlinkPlan} 的周期表 / 周期推进到点 /
 * 受击应激冷却过半资格 / 缰绳 24 边界 / 脱离落点几何 (远离方向 4-8 格 + 扇形) / 脱离约束 (更近候选拒) + 参数校验,
 * 全部逐位精确断言 (删被测折算/几何/边界必挂)。落点安全裁决 (KnockbackSafetyGuard) 已由 {@code KnockbackSafetyGuardGameTests}
 * 覆盖, 此处不重复; 真服 (Champions 已加载) 由 {@code ChampionTacticalBlinkHandler} 每秒扫近玩家冠军按周期/受击瞬移。
 *
 * template = "empty", batch = "champion_tactical_blink"。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class ChampionTacticalBlinkGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "champion_tactical_blink";

    /** 几何断言容差 (格): 旋转/开方浮点误差远小于此。 */
    private static final double EPS = 1.0e-6D;

    // ============================================================
    // 施放周期表 (8/7/6/5/4 s = 160/140/120/100/80 tick; 5 档精确)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void cyclePeriodPerQuality(GameTestHelper helper) {
        helper.assertTrue(ChampionTacticalBlinkPlan.cycleTicks(AffixQuality.COMMON) == 160L,
                "战术传送 普通 周期 = 8s = 160 tick");
        helper.assertTrue(ChampionTacticalBlinkPlan.cycleTicks(AffixQuality.UNCOMMON) == 140L,
                "战术传送 中级 周期 = 7s = 140 tick");
        helper.assertTrue(ChampionTacticalBlinkPlan.cycleTicks(AffixQuality.RARE) == 120L,
                "战术传送 高级 周期 = 6s = 120 tick");
        helper.assertTrue(ChampionTacticalBlinkPlan.cycleTicks(AffixQuality.EPIC) == 100L,
                "战术传送 超凡 周期 = 5s = 100 tick");
        helper.assertTrue(ChampionTacticalBlinkPlan.cycleTicks(AffixQuality.LEGENDARY) == 80L,
                "战术传送 闪耀 周期 = 4s = 80 tick");
        helper.succeed();
    }

    // ============================================================
    // 周期推进 (扫描步进累加 -> 到点判定; 到点清零重计)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void advanceCycleStepsByScanInterval(GameTestHelper helper) {
        // 每次推进恰加一个扫描粒度 20 tick (删 +SCAN 步进则此处必挂)。
        helper.assertTrue(ChampionTacticalBlinkPlan.advanceCycle(0L) == 20L, "推进一次 = +20 tick");
        helper.assertTrue(ChampionTacticalBlinkPlan.advanceCycle(140L) == 160L, "推进累加 140 -> 160 tick");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void cycleReadyAtPeriodBoundary(GameTestHelper helper) {
        // 闪耀周期 80 tick: 79 未到点, 80 到点 (删 >= 判定退回 > 则下界必挂)。
        helper.assertTrue(!ChampionTacticalBlinkPlan.cycleReady(79L, AffixQuality.LEGENDARY),
                "累加 79 < 80 (闪耀) 未到周期");
        helper.assertTrue(ChampionTacticalBlinkPlan.cycleReady(80L, AffixQuality.LEGENDARY),
                "累加 80 = 80 (闪耀) 到点");
        // 普通周期 160 tick: 159 未到, 160 到点 (跨品质联动周期表)。
        helper.assertTrue(!ChampionTacticalBlinkPlan.cycleReady(159L, AffixQuality.COMMON),
                "累加 159 < 160 (普通) 未到周期");
        helper.assertTrue(ChampionTacticalBlinkPlan.cycleReady(160L, AffixQuality.COMMON),
                "累加 160 = 160 (普通) 到点");
        helper.succeed();
    }

    // ============================================================
    // 受击应激资格 (内 CD 冷却过半边界; 两路共用 CD 不刷永动)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void hitStressEligibleAtHalfCooldown(GameTestHelper helper) {
        // 普通周期 160, 半程 = 80: 累加 79 未过半 (不触发), 80 恰过半 (触发)。删 *2>= 判定必挂边界。
        helper.assertTrue(!ChampionTacticalBlinkPlan.hitStressEligible(79L, AffixQuality.COMMON),
                "累加 79 < 半程 80 (普通): 受击不触发");
        helper.assertTrue(ChampionTacticalBlinkPlan.hitStressEligible(80L, AffixQuality.COMMON),
                "累加 80 = 半程 80 (普通): 受击触发");
        // 闪耀周期 80, 半程 = 40: 39 未过半, 40 过半。
        helper.assertTrue(!ChampionTacticalBlinkPlan.hitStressEligible(39L, AffixQuality.LEGENDARY),
                "累加 39 < 半程 40 (闪耀): 受击不触发");
        helper.assertTrue(ChampionTacticalBlinkPlan.hitStressEligible(40L, AffixQuality.LEGENDARY),
                "累加 40 = 半程 40 (闪耀): 受击触发");
        // 边界外沿: 刚脱离 (累加 0) 不可受击触发; 满周期 (超半程) 可触发。
        helper.assertTrue(!ChampionTacticalBlinkPlan.hitStressEligible(0L, AffixQuality.COMMON),
                "累加 0 (刚脱离): 受击不触发 (内 CD 未过半)");
        helper.assertTrue(ChampionTacticalBlinkPlan.hitStressEligible(160L, AffixQuality.COMMON),
                "累加 160 (满周期): 受击可触发");
        helper.succeed();
    }

    // ============================================================
    // 缰绳 24 格边界 (超出冻结不耗周期)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void tetherBoundaryAt24Blocks(GameTestHelper helper) {
        // 24² = 576 恰在内; 577 超出 (删 <= 退回 < 则等距边界必挂)。
        helper.assertTrue(ChampionTacticalBlinkPlan.withinTether(576.0D),
                "距离² 576 (= 24 格) 在缰绳内");
        helper.assertTrue(!ChampionTacticalBlinkPlan.withinTether(577.0D),
                "距离² 577 (> 24 格) 超缰绳");
        helper.assertTrue(ChampionTacticalBlinkPlan.withinTether(0.0D), "距离² 0 (贴脸) 在内");
        helper.assertTrue(!ChampionTacticalBlinkPlan.withinTether(1000.0D), "距离² 1000 超缰绳");
        helper.succeed();
    }

    // ============================================================
    // 脱离落点几何 (远离玩家方向 4-8 格 + 左右 30 度扇形; 15 候选)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void candidatesCountAndDistanceRange(GameTestHelper helper) {
        // 玩家原点, 冠军在 +X 10 格处: 远离方向 = +X。
        double px = 0.0D;
        double py = 64.0D;
        double pz = 0.0D;
        double cx = 10.0D;
        double cy = 64.0D;
        double cz = 0.0D;
        List<ChampionTacticalBlinkPlan.Landing> candidates =
                ChampionTacticalBlinkPlan.candidates(px, py, pz, cx, cy, cz);

        helper.assertTrue(candidates.size() == 15, "3 扇向 × 5 档距 = 15 个候选落点");

        // 每档距离 4/5/6/7/8 各出现恰 3 次 (每扇向一次), 且每个候选到冠军的水平位移恰 = 档距。
        int[] distCount = new int[9];
        for (ChampionTacticalBlinkPlan.Landing c : candidates) {
            double dHoriz = Math.sqrt((c.x() - cx) * (c.x() - cx) + (c.z() - cz) * (c.z() - cz));
            long di = Math.round(dHoriz);
            helper.assertTrue(Math.abs(dHoriz - di) < EPS,
                    "候选到冠军水平位移恰为整数档距, 实测 " + dHoriz);
            helper.assertTrue(di >= ChampionTacticalBlinkPlan.MIN_BLINK_DISTANCE
                            && di <= ChampionTacticalBlinkPlan.MAX_BLINK_DISTANCE,
                    "候选档距在 4-8 格内, 实测 " + di);
            distCount[(int) di]++;
            // y 恒取冠军脚下 (脱离水平拉开, 落脚高度由守卫定)。
            helper.assertTrue(Math.abs(c.y() - cy) < EPS, "候选 Y 恒取冠军脚下");
        }
        for (int d = 4; d <= 8; d++) {
            helper.assertTrue(distCount[d] == 3, "档距 " + d + " 格恰出现 3 次 (每扇向一次)");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void candidatesAllFartherFromPlayer(GameTestHelper helper) {
        // 远离方向硬约束: 每个候选都比冠军当前更远离玩家 (脱离语义几何保证)。
        double px = 0.0D;
        double py = 64.0D;
        double pz = 0.0D;
        double cx = 10.0D;
        double cy = 64.0D;
        double cz = 0.0D;
        double champDistSq = (cx - px) * (cx - px) + (cz - pz) * (cz - pz); // = 100
        List<ChampionTacticalBlinkPlan.Landing> candidates =
                ChampionTacticalBlinkPlan.candidates(px, py, pz, cx, cy, cz);
        for (ChampionTacticalBlinkPlan.Landing c : candidates) {
            double candDistSq = (c.x() - px) * (c.x() - px) + (c.z() - pz) * (c.z() - pz);
            helper.assertTrue(candDistSq > champDistSq,
                    "候选须比冠军当前 (距²=" + champDistSq + ") 更远离玩家, 实测 " + candDistSq);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void candidatesOrderingAndSwingGeometry(GameTestHelper helper) {
        // 玩家原点, 冠军 +X 10 格: 远离单位向量 = (1,0)。
        double px = 0.0D;
        double py = 64.0D;
        double pz = 0.0D;
        double cx = 10.0D;
        double cy = 64.0D;
        double cz = 0.0D;
        List<ChampionTacticalBlinkPlan.Landing> c =
                ChampionTacticalBlinkPlan.candidates(px, py, pz, cx, cy, cz);

        // 偏好序: 正前方 (0 度) 优先 + 每扇向内由远 (8) 到近 (4)。首候选 = 正前 8 格 = (18,64,0)。
        assertPoint(helper, c.get(0), 18.0D, 64.0D, 0.0D, "候选[0] = 正前方 8 格 (远优先, 纯远离方向)");
        // 正前方档内末位 = 4 格 = (14,64,0)。
        assertPoint(helper, c.get(4), 14.0D, 64.0D, 0.0D, "候选[4] = 正前方 4 格");
        // 正前方 5 候选恒在 Z=0 延长线上 (远离方向纯 +X)。
        for (int i = 0; i <= 4; i++) {
            helper.assertTrue(Math.abs(c.get(i).z() - 0.0D) < EPS, "正前方候选[" + i + "] 恒在 Z=0 延长线上");
            helper.assertTrue(c.get(i).x() > cx, "正前方候选[" + i + "] X 在冠军之外 (远离玩家)");
        }
        // 次扇向 = +30 度, 首候选 (index 5) = 冠军 + 8×(cos30, sin30) = (10+6.9282, 64, +4.0)。
        double cos30 = Math.cos(Math.toRadians(30.0D));
        assertPoint(helper, c.get(5), 10.0D + 8.0D * cos30, 64.0D, 4.0D, "候选[5] = +30 度扇向 8 格 (右摆 Z=+4)");
        // 第三扇向 = -30 度, 首候选 (index 10) = (10+6.9282, 64, -4.0) 与 +30 关于延长线镜像。
        assertPoint(helper, c.get(10), 10.0D + 8.0D * cos30, 64.0D, -4.0D, "候选[10] = -30 度扇向 8 格 (左摆 Z=-4)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void candidatesDegenerateVerticalFallback(GameTestHelper helper) {
        // 冠军几乎在玩家正上方 (水平重合): 无远离水平方向, 回退 +X, 仍生成 15 个合法候选且档距 4-8。
        double px = 5.0D;
        double py = 64.0D;
        double pz = 5.0D;
        double cx = 5.0D;
        double cy = 70.0D;
        double cz = 5.0D;
        List<ChampionTacticalBlinkPlan.Landing> candidates =
                ChampionTacticalBlinkPlan.candidates(px, py, pz, cx, cy, cz);
        helper.assertTrue(candidates.size() == 15, "退化态仍生成 15 个候选 (回退方向 +X)");
        for (ChampionTacticalBlinkPlan.Landing c : candidates) {
            double dHoriz = Math.sqrt((c.x() - cx) * (c.x() - cx) + (c.z() - cz) * (c.z() - cz));
            helper.assertTrue(dHoriz >= ChampionTacticalBlinkPlan.MIN_BLINK_DISTANCE - EPS
                            && dHoriz <= ChampionTacticalBlinkPlan.MAX_BLINK_DISTANCE + EPS,
                    "退化态候选水平位移仍在 4-8 格, 实测 " + dHoriz);
        }
        helper.succeed();
    }

    // ============================================================
    // 脱离约束 (候选距玩家 > 当前距玩家; 更近候选拒)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void isDisengagingRejectsCloserLanding(GameTestHelper helper) {
        // 玩家原点, 冠军 +X 10 格 (当前距² = 100)。
        double px = 0.0D;
        double py = 64.0D;
        double pz = 0.0D;
        double cx = 10.0D;
        double cy = 64.0D;
        double cz = 0.0D;
        // 更远落点 (14,64,0) 距²=196 > 100: 是脱离。
        helper.assertTrue(ChampionTacticalBlinkPlan.isDisengaging(px, py, pz, cx, cy, cz, 14.0D, 64.0D, 0.0D),
                "更远落点 (距²196 > 当前100): 判为脱离");
        // 更近落点 (5,64,0) 距²=25 < 100: 拒 (非脱离, 反向抵近)。
        helper.assertTrue(!ChampionTacticalBlinkPlan.isDisengaging(px, py, pz, cx, cy, cz, 5.0D, 64.0D, 0.0D),
                "更近落点 (距²25 < 当前100): 拒 (非脱离)");
        // 等距落点 (0,64,10) 距²=100 == 100: 拒 (须严格更远)。
        helper.assertTrue(!ChampionTacticalBlinkPlan.isDisengaging(px, py, pz, cx, cy, cz, 0.0D, 64.0D, 10.0D),
                "等距落点 (距²100 == 当前100): 拒 (脱离须严格更远)");
        helper.succeed();
    }

    // ============================================================
    // 参数校验 (异常必须痛: 空品质 / 负累加 / 负距离)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void invalidArgsRejected(GameTestHelper helper) {
        helper.assertTrue(throwsIae(() -> ChampionTacticalBlinkPlan.cycleTicks(null)),
                "cycleTicks null 品质须抛 IllegalArgumentException");
        helper.assertTrue(throwsIae(() -> ChampionTacticalBlinkPlan.cycleReady(0L, null)),
                "cycleReady null 品质须抛");
        helper.assertTrue(throwsIae(() -> ChampionTacticalBlinkPlan.cycleReady(-1L, AffixQuality.COMMON)),
                "cycleReady 负累加须抛");
        helper.assertTrue(throwsIae(() -> ChampionTacticalBlinkPlan.hitStressEligible(0L, null)),
                "hitStressEligible null 品质须抛");
        helper.assertTrue(throwsIae(() -> ChampionTacticalBlinkPlan.hitStressEligible(-1L, AffixQuality.COMMON)),
                "hitStressEligible 负累加须抛");
        helper.assertTrue(throwsIae(() -> ChampionTacticalBlinkPlan.advanceCycle(-1L)),
                "advanceCycle 负累加须抛");
        helper.assertTrue(throwsIae(() -> ChampionTacticalBlinkPlan.withinTether(-1.0D)),
                "withinTether 负距离²须抛");
        helper.succeed();
    }

    // ---- 私有断言辅助 ----

    private static void assertPoint(GameTestHelper helper, ChampionTacticalBlinkPlan.Landing c,
                                    double ex, double ey, double ez, String msg) {
        helper.assertTrue(Math.abs(c.x() - ex) < EPS && Math.abs(c.y() - ey) < EPS && Math.abs(c.z() - ez) < EPS,
                msg + " (期望 " + ex + "," + ey + "," + ez + " 实测 " + c.x() + "," + c.y() + "," + c.z() + ")");
    }

    private static boolean throwsIae(Runnable r) {
        try {
            r.run();
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }
}
