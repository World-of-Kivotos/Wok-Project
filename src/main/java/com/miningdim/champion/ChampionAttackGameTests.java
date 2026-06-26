package com.miningdim.champion;

import com.miningdim.champion.aggregate.DotAggregator;
import com.miningdim.champion.aggregate.PlayerDotAccumulator;
import com.miningdim.champion.aggregate.PlayerDotSources;
import com.miningdim.champion.integration.ChampionDotTickHandler;
import com.miningdim.core.MiningConstants;
import com.miningdim.effect.VulnerabilityEffect;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Pose;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * 冠军攻击类词条效果层纯逻辑 GameTest (ChampionStarAffix spec 7.2 战斗 + 红线 3/4/5 + 9A.2 单点铁律 TDD)。
 *
 * 严禁触 Champions 加载路径 (compileOnly 铁律): 只断言 {@link ChampionAttackValues} / {@link ChampionStrikeGate}
 * 纯数值与红线钳制 (单击合并封顶 / DoT 名义量 + 聚合 ≤15% / 寒霜减速 ≤50% / 撕裂折易伤 amplifier / 嗜血低血激活 /
 * 强酸耐久 / on-hit 内 CD + 击飞限频 / 多击分跳)。所有断言为具体业务结果, 删被测核心逻辑必挂 (禁弱校验)。真服
 * (Champions 已加载) 验真词条触发由 {@code ChampionAttackHandler} 在受击事件单点施加。
 *
 * 持续 DoT + DoT 致死两用例 (champion-01) 引用 {@link ChampionDotTickHandler} 与 {@link PlayerDotSources} ——
 * 二者均不 import 任何 top.theillusivec4.champions.*  (纯逻辑 + MC), 故引用不触 Champions 加载路径, 与上铁律不冲突。
 *
 * template = "empty", batch = "champion_attack"。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class ChampionAttackGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "champion_attack";
    private static final double EPS = 1e-6D;

    // ============================================================
    // 即时伤害单点合并 + 红线 3 (重炮/嗜血放大普通分量 + 穿甲真伤合计 ≤40%)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void singleHitMergeClampsNormalThenPiercing(GameTestHelper helper) {
        // 8star 基线 0.16, 重炮闪耀 +1.00 放大 -> 0.16*2.0 = 0.32 (< 8star 上限 0.60, 不夹)。
        StarRank s8 = StarRank.ofStar(8);
        double noPierce = ChampionAttackValues.singleHitTotalPct(s8, 0.16D, 1.00D, 0.0D, 0.0D);
        helper.assertTrue(Math.abs(noPierce - 0.32D) < EPS, "8star base 0.16 x(1+1.00) = 0.32 under 60% cap");

        // 普通分量撑高被夹: 基线 0.40 重炮 +1.00 -> 0.80 夹到 8star 上限 0.60。
        double clampedNormal = ChampionAttackValues.singleHitTotalPct(s8, 0.40D, 1.00D, 0.0D, 0.0D);
        helper.assertTrue(Math.abs(clampedNormal - 0.60D) < EPS, "amplified normal 0.80 clamps to 8star 60%");

        // 穿甲真伤恒并入 40% 合计封顶 (不入高星放宽): 普通 0.30 + 穿甲 0.18 = 0.48 -> 0.40。
        // 1star 基线 0.04 重炮 +6.5 -> 0.30 (1star 上限 0.40 不夹), + 穿甲 0.18 -> 合计夹 0.40。
        double s1 = ChampionAttackValues.singleHitTotalPct(StarRank.ofStar(1), 0.30D, 0.0D, 0.0D, 0.18D);
        helper.assertTrue(Math.abs(s1 - 0.40D) < EPS, "normal 0.30 + piercing 0.18 clamps to 40% combined");

        // 删 clampPiercingPlusNormal 必挂: 即便高星, 真伤合计仍恒 ≤40% (8star 普通 0.60 + 穿甲 0.18 -> 0.40)。
        double s8pierce = ChampionAttackValues.singleHitTotalPct(s8, 0.60D, 0.0D, 0.0D, 0.18D);
        helper.assertTrue(Math.abs(s8pierce - 0.40D) < EPS, "true damage caps total at 40% regardless of star");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void bonusOverVanillaNeverReducesAndCaps(GameTestHelper helper) {
        // totalPct 0.40, 玩家 80 maxHP -> 名义 32 HP; vanilla 已 10 -> 额外 22 (补足到 32)。
        double bonus = ChampionAttackValues.bonusOverVanilla(0.40D, 80.0D, 10.0D);
        helper.assertTrue(Math.abs(bonus - 22.0D) < EPS, "补足到 %maxHP 名义值: 32 - 10 = 22 额外");

        // vanilla 本就高于名义值 -> 额外 0 (绝不削原版伤害)。
        double noBonus = ChampionAttackValues.bonusOverVanilla(0.40D, 80.0D, 40.0D);
        helper.assertTrue(noBonus == 0.0D, "vanilla 40 >= nominal 32: no bonus, never reduces");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void bloodlustActivatesOnlyLowHp(GameTestHelper helper) {
        // 嗜血高级 +0.35: 占比 0.5 (高于阈值 0.35) 未激活 = 0。
        double high = ChampionAttackValues.bloodlustDamageAmp(AffixQuality.RARE, 0.50D);
        helper.assertTrue(high == 0.0D, "bloodlust inactive above 35% hp");
        // 占比 0.20 (≤0.35) 激活 = +0.35。
        double low = ChampionAttackValues.bloodlustDamageAmp(AffixQuality.RARE, 0.20D);
        helper.assertTrue(Math.abs(low - 0.35D) < EPS, "bloodlust active at 20% hp = +35% (RARE)");
        // 阈值边界 0.35 含等于 (≤) 激活。
        double edge = ChampionAttackValues.bloodlustDamageAmp(AffixQuality.RARE, 0.35D);
        helper.assertTrue(Math.abs(edge - 0.35D) < EPS, "bloodlust active at exactly 35% threshold");
        helper.succeed();
    }

    // ============================================================
    // DoT (燃烧/寒霜) 名义量 + 红线 4 聚合 ≤15% + 寒霜减速红线 5 ≤50%
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void dotNominalAndFifteenPctAggregateCap(GameTestHelper helper) {
        double maxHp = 80.0D;
        // 燃烧闪耀每层 0.04, 5 层 = 0.20 maxHP/s -> 16 HP/s 名义 (未夹)。
        double burn = ChampionAttackValues.burningTickHp(AffixQuality.LEGENDARY, 5, maxHp);
        helper.assertTrue(Math.abs(burn - 16.0D) < EPS, "burning 5 stacks legendary = 0.20*80 = 16 HP/s nominal");

        // 寒霜闪耀冻伤每层 0.035, 5 层 = 0.175 maxHP/s -> 14 HP/s 名义。
        double frost = ChampionAttackValues.frostFreezeTickHp(AffixQuality.LEGENDARY, 5, maxHp);
        helper.assertTrue(Math.abs(frost - 14.0D) < EPS, "frost 5 stacks legendary = 0.175*80 = 14 HP/s nominal");

        // 红线 4 聚合: 燃烧 16 + 寒霜 14 = 30 名义 > 15% maxHP (= 12 HP) -> 合计夹到 12, 按贡献比例衰减。
        DotAggregator.Result agg = DotAggregator.aggregate(maxHp, burn, frost);
        helper.assertTrue(Math.abs(agg.total() - 12.0D) < EPS, "burn+frost 30 nominal clamps to 15% maxHP = 12");
        helper.assertTrue(agg.wasCapped(), "two-DoT coexist hits 15% cap");
        // 逐源按贡献衰减: 燃烧 16/30*12 = 6.4, 寒霜末源吸余 = 12-6.4 = 5.6。
        double[] per = agg.perSource();
        helper.assertTrue(Math.abs(per[0] - 6.4D) < EPS, "burning decays to 16/30*12 = 6.4");
        helper.assertTrue(Math.abs((per[0] + per[1]) - 12.0D) < EPS, "decayed sum exactly equals 12 cap");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void frostSlowClampsAtFiftyPct(GameTestHelper helper) {
        // 寒霜闪耀每层减速 0.12, 5 层 = 0.60 -> 夹到 50% (绝不定身/绝不超 50%)。
        double slow5 = ChampionAttackValues.frostSlowPct(AffixQuality.LEGENDARY, 5);
        helper.assertTrue(Math.abs(slow5 - 0.50D) < EPS, "frost 5 stacks legendary slow 0.60 clamps to 50%");
        // 2 层 = 0.24 未夹。
        double slow2 = ChampionAttackValues.frostSlowPct(AffixQuality.LEGENDARY, 2);
        helper.assertTrue(Math.abs(slow2 - 0.24D) < EPS, "frost 2 stacks = 0.24 under cap");
        helper.succeed();
    }

    // ============================================================
    // 撕裂折易伤 amplifier (复用易伤系统, floor 不越档)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void rendMapsToVulnerabilityAmplifierFloor(GameTestHelper helper) {
        // 撕裂闪耀每层 0.20: 1 层 = 0.20 = 易伤 I (amp 0); 易伤档 I=0.20/II=0.35。
        helper.assertTrue(ChampionAttackValues.rendAmplifier(AffixQuality.LEGENDARY, 1) == 0,
                "rend 1 layer (0.20) = vulnerability I (amp 0)");
        // 2 层 = 0.40: 介于 II(0.35) 与 III(0.50), floor 到 II (amp 1)。
        helper.assertTrue(ChampionAttackValues.rendAmplifier(AffixQuality.LEGENDARY, 2) == 1,
                "rend 2 layers (0.40) floors to vulnerability II (amp 1)");
        // 5 层 = 1.00: 易伤 V 封顶 (amp 4)。
        helper.assertTrue(ChampionAttackValues.rendAmplifier(AffixQuality.LEGENDARY, 5) == 4,
                "rend 5 layers (1.00) = vulnerability V cap (amp 4)");
        // 6 层名义 1.20 但 rendVulnerabilityPct 已夹 1.00 -> 仍 amp 4 (不越封顶)。
        helper.assertTrue(ChampionAttackValues.rendAmplifier(AffixQuality.LEGENDARY, 6) == 4,
                "rend 6 layers clamps at vulnerability V (no overshoot past +100%)");
        // 0 层 = 0% -> 无档 (amp -1, 不挂效果)。
        helper.assertTrue(ChampionAttackValues.rendAmplifier(AffixQuality.LEGENDARY, 0) == -1,
                "rend 0 layers = no vulnerability effect");
        // 普通撕裂每层 0.05: 1 层 0.05 < 易伤 I(0.20) -> 无档。
        helper.assertTrue(ChampionAttackValues.rendAmplifier(AffixQuality.COMMON, 1) == -1,
                "rend common 1 layer (0.05) below I threshold = no effect");
        // 高耦合断言: 撕裂折出的 amplifier 经易伤系统的放大 ≤ 撕裂名义 %（floor 保守不越档）。
        double rendPct = ChampionAffixValues.rendVulnerabilityPct(AffixQuality.LEGENDARY, 2); // 0.40
        int amp = ChampionAttackValues.rendAmplifier(AffixQuality.LEGENDARY, 2); // 1 -> II 0.35
        helper.assertTrue(VulnerabilityEffect.percentForAmplifier(amp) <= rendPct + EPS,
                "mapped amplifier never amplifies beyond rend nominal %");
        helper.succeed();
    }

    // ============================================================
    // 强酸耐久 + 多击分跳
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void corrosiveDurabilityAndStrikeJumps(GameTestHelper helper) {
        // 强酸闪耀 15/击, 普通 2/击。
        helper.assertTrue(ChampionAttackValues.corrosiveArmorDamage(AffixQuality.LEGENDARY) == 15,
                "corrosive legendary = 15 durability/hit");
        helper.assertTrue(ChampionAttackValues.corrosiveArmorDamage(AffixQuality.COMMON) == 2,
                "corrosive common = 2 durability/hit");

        // 双倍 = 2 跳, 四倍 = 4 跳, 无多击 = 1 跳。
        helper.assertTrue(ChampionStrikeGate.strikeJumps(AffixDef.DOUBLE_STRIKE, AffixQuality.RARE) == 2,
                "double strike = 2 jumps");
        helper.assertTrue(ChampionStrikeGate.strikeJumps(AffixDef.QUADRUPLE_STRIKE, AffixQuality.RARE) == 4,
                "quadruple strike = 4 jumps");
        helper.assertTrue(ChampionStrikeGate.strikeJumps(null, AffixQuality.RARE) == 1,
                "no multi-strike = 1 jump");
        helper.succeed();
    }

    // ============================================================
    // on-hit 内 CD: DoT 刷新 ≥1s + 混沌击飞 ≥2s + 落地恢复窗
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void dotRefreshInternalCd(GameTestHelper helper) {
        ChampionStrikeGate gate = new ChampionStrikeGate();
        // 首次允许 (从未刷新)。
        helper.assertTrue(gate.canRefreshDot(100L), "first dot refresh allowed");
        gate.markDotRefreshed(100L);
        // 同帧第二跳 (双倍/四倍): diff 0 < 20tick 内 CD -> 拒 (分跳不重复刷层破 ≤15% 聚合)。
        helper.assertTrue(!gate.canRefreshDot(100L), "same-tick second jump blocked by 1s internal CD");
        // 10tick 后仍在内 CD。
        helper.assertTrue(!gate.canRefreshDot(110L), "10 ticks later still within 1s CD");
        // 20tick (1s) 后允许。
        helper.assertTrue(gate.canRefreshDot(120L), "1s later dot refresh allowed again");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void chaosKnockbackCdAndLandingWindow(GameTestHelper helper) {
        ChampionStrikeGate gate = new ChampionStrikeGate();
        // 首次允许。落地预计 = 触发 + 20tick。
        helper.assertTrue(gate.canKnockback(0L), "first knockback allowed");
        gate.markKnockback(0L, 20L);
        // 内 CD ≥2s (40tick): 30tick 后仍拒。
        helper.assertTrue(!gate.canKnockback(30L), "30 ticks later still within 2s knockback CD");
        // 40tick 内 CD 满足, 但落地 20tick + 1s 恢复窗 (到 40tick): 40tick 时恰好出恢复窗边界 (diff 20 >= 20)。
        helper.assertTrue(gate.canKnockback(40L), "after 2s CD and landing-recovery window, knockback allowed");

        // 落地恢复窗内被挡: 落地 20tick, 恢复窗到 40tick; 内 CD 已过但仍在恢复窗 (39tick) -> 拒。
        ChampionStrikeGate g2 = new ChampionStrikeGate();
        g2.markKnockback(0L, 100L); // 落地远在 100tick: 恢复窗到 120tick。
        helper.assertTrue(!g2.canKnockback(60L), "knockback blocked inside landing-recovery window even past CD");
        helper.assertTrue(!g2.canKnockback(119L), "still blocked just before recovery window ends");
        helper.assertTrue(g2.canKnockback(120L), "allowed once landing-recovery window passes");
        helper.succeed();
    }

    // ============================================================
    // champion-01 持续 DoT: 命中后 3s 刷新窗内每秒持续扣血 (不需新命中), 窗口过期停伤
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void burningDotDrainsEachSecondInWindowThenStops(GameTestHelper helper) {
        double maxHp = 80.0D; // 公服初始血量。
        UUID championId = UUID.randomUUID();
        UUID victimId = UUID.randomUUID();
        ChampionEffectRegistries.clearAll(victimId); // 隔离: 清任何残留, 本测试独占该 UUID。
        try {
            // 命中阶段: 在 tick 100 挂 3 层燃烧 (闪耀, 每层 0.04 maxHP/s)。3s 刷新窗末 = 100 + 60 = 160。
            // 之后【不再有任何新命中】—— 仅靠 tick handler 每秒补记驱动持续扣血。
            PlayerDotSources sources = ChampionEffectRegistries.dotSourcesFor(victimId);
            for (int i = 0; i < 3; i++) {
                sources.refresh(championId, AffixDef.BURNING, AffixQuality.LEGENDARY, 100L);
            }
            helper.assertTrue(sources.stacksOf(championId, AffixDef.BURNING) == 3,
                    "one champion built 3 burning stacks at hit tick");

            // 每层每秒 0.04 * 80 = 3.2 HP; 3 层 = 9.6 HP/s (< 15% maxHP = 12, 不撞顶)。
            double expectedPerSecond = 0.04D * 3 * maxHp;
            helper.assertTrue(Math.abs(expectedPerSecond - 9.6D) < EPS, "3-stack legendary burning nominal = 9.6 HP/s");

            PlayerDotAccumulator acc = ChampionEffectRegistries.dotFor(victimId);
            double cumulative = 0.0D;
            // 推进多秒 (无新命中): 窗内每个 flush 边界 (120/140/160, 均 ≤ 窗末 160) 都持续扣 9.6, 证明"不需每秒新命中"。
            long[] inWindowSeconds = {120L, 140L, 160L};
            for (long tick : inWindowSeconds) {
                // tick handler 每秒做的事: 先按当前在册层数补记本秒名义伤害, 再 flush 统一施加。
                ChampionDotTickHandler.recordNominalForSecond(victimId, maxHp, tick);
                double total = acc.flush(maxHp, tick).total();
                helper.assertTrue(Math.abs(total - 9.6D) < EPS,
                        "in-window second @tick " + tick + " drains 9.6 HP (continuous DoT, no new hit), got " + total);
                cumulative += total;
            }
            // 累计 = 9.6 * 3 = 28.8 (层数 0.12 * 80 * 3 秒, 未撞 15% 顶)。删每秒补记则三次 flush 全 0 -> 此断言必挂。
            helper.assertTrue(Math.abs(cumulative - 28.8D) < EPS,
                    "3 in-window seconds cumulative drain = 28.8 HP, got " + cumulative);

            // 窗口过期 (tick 180 > 窗末 160): prune 清源 -> 无活跃源 -> 本秒补记 0 -> flush 0 (持续 DoT 自然停伤)。
            ChampionDotTickHandler.recordNominalForSecond(victimId, maxHp, 180L);
            double afterExpiry = acc.flush(maxHp, 180L).total();
            helper.assertTrue(afterExpiry == 0.0D, "after 3s window expires DoT stops (0 drain), got " + afterExpiry);
            helper.assertTrue(sources.sourceCount() == 0, "expired burning source pruned out of the model");
        } finally {
            ChampionEffectRegistries.clearAll(victimId); // 反泄漏: 即便断言失败也清本测试静态注册表残留。
        }
        helper.succeed();
    }

    // ============================================================
    // champion-01 DoT 致死: 持续 DoT 把低血玩家扣到致死时真触发原版死亡 (非 setHealth(0) 不死)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void lethalDotTriggersRealDeathWhileNonLethalJustDrains(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        // mock 玩家创造模式无敌帧会让 hurt 直接 return false; 关无敌保致死份能走入伤链 (战斗向受击玩家本就非无敌)。
        player.getAbilities().invulnerable = false;

        // 非致死份 (total < health): 走 setHealth 直接扣血, 不死、不进 DYING (规避 i-frame 吞 DoT + 易伤二次放大)。
        player.setHealth(10.0F);
        ChampionDotTickHandler.applyDotDamage(player, 3.0D);
        helper.assertTrue(Math.abs(player.getHealth() - 7.0F) < 1e-4F,
                "non-lethal DoT 3 on 10 HP -> setHealth to 7, got " + player.getHealth());
        helper.assertFalse(player.getPose() == Pose.DYING, "non-lethal DoT does not put player in DYING pose");
        helper.assertTrue(player.isAlive(), "non-lethal DoT leaves player alive");

        // 致死份 (total >= health): setHealth(0) 只夹血量不触发 die() (dead=false, 无死亡序列/无 LivingDeathEvent/
        // 无重生屏), 玩家卡在 0 血假死; 真死须走 hurt 触发 die()。关键判别用 LivingDeathEvent 是否 fire ——
        // 删致死修复退回纯 setHealth(0) 则 die() 不触发、本事件不发, deathFired 恒 false, 断言必挂。
        // (注: die() 在 1.20.1 不置 Pose.DYING, 故不能用 pose 判别真死。)
        boolean[] deathFired = {false};
        Object deathProbe = new Object() {
            @SubscribeEvent
            public void onLethalDotDeath(LivingDeathEvent event) {
                if (event.getEntity() == player) {
                    deathFired[0] = true;
                }
            }
        };
        MinecraftForge.EVENT_BUS.register(deathProbe);
        try {
            player.setHealth(5.0F);
            ChampionDotTickHandler.applyDotDamage(player, 8.0D);
        } finally {
            MinecraftForge.EVENT_BUS.unregister(deathProbe);
        }
        helper.assertTrue(player.getHealth() <= 0.0F, "lethal DoT drops health to 0, got " + player.getHealth());
        helper.assertTrue(deathFired[0],
                "lethal DoT triggers real death (LivingDeathEvent fired by vanilla die(), not stuck at 0 HP via setHealth)");
        helper.assertFalse(player.isAlive(), "lethal DoT makes player no longer alive (real death)");

        helper.succeed();
    }
}
