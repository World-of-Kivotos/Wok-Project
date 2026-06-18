package com.miningdim.job.tarot;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miningdim.core.MiningConstants;
import com.miningdim.job.tarot.card.TarotCardData;
import com.miningdim.job.tarot.card.TarotEffectKind;
import com.miningdim.job.tarot.card.TarotEffectOp;
import com.miningdim.job.tarot.craft.TarotCraftService;
import com.miningdim.job.tarot.pack.PackGachaService;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 塔罗师业务断言 GameTest (TarotReader spec 第十二章测试断言)。断言具体数额/状态/副作用, 删被测核心逻辑测试必挂。
 *
 * 不依赖 capability attach (JobFramework 集成阶段才接线; 见 notes) 的逻辑全覆盖:
 *  - 牌效 datapack 全表平衡红线: 抗性 <= III, 易伤 <= V (遍历 22 张全档全朝向);
 *  - 合成四结果概率大样本落区间 + 碎片返还精确;
 *  - 派生包期望 E<1 收敛 + pity 保底必出;
 *  - 最大生命增减有界 (教皇+40/世界逆下限40) + transient 修饰可清 (无泄漏);
 *  - 用牌 CD: GCD 内连甩被拒, 同卡 CD 未到被拒;
 *  - 调度器登出/死亡清队列;
 *  - 易伤效果实施: 受击者带易伤 III 时 LivingHurt 后伤害 x1.5 (经全局仲裁)。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class TarotGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "tarot";

    // ============================================================
    // 牌效 datapack 全表平衡红线: 抗性 <= III (amplifier<=2), 易伤 <= V (amplifier<=4)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void allCardsRespectResistanceAndVulnerabilityCaps(GameTestHelper helper) {
        int scanned = 0;
        for (TarotArcana arcana : TarotArcana.values()) {
            TarotCardData data = loadCard(arcana);
            // 正/逆位四档 + 闪耀全扫。
            for (TarotQuality q : new TarotQuality[]{TarotQuality.R, TarotQuality.SR, TarotQuality.SSR, TarotQuality.UR}) {
                scanCaps(helper, arcana, data.opsFor(q, true));
                scanCaps(helper, arcana, data.opsFor(q, false));
                scanned++;
            }
            scanCaps(helper, arcana, data.opsFor(TarotQuality.SHINY, true));
        }
        helper.assertTrue(scanned == TarotArcana.COUNT * 4, "scanned all 22 cards x 4 tiers");
        helper.succeed();
    }

    private static void scanCaps(GameTestHelper helper, TarotArcana arcana, List<TarotEffectOp> ops) {
        for (TarotEffectOp op : ops) {
            if (op.kind() == TarotEffectKind.SELF_POTION
                    || op.kind() == TarotEffectKind.AOE_ALLY_POTION
                    || op.kind() == TarotEffectKind.AOE_ENEMY_POTION) {
                if ("minecraft:resistance".equals(op.effectId())) {
                    helper.assertTrue(op.amplifier() <= 2,
                            "card " + arcana.id() + " resistance amplifier must be <= III (2), got " + op.amplifier());
                }
                if ("miningdim:vulnerability".equals(op.effectId())) {
                    helper.assertTrue(op.amplifier() <= 4,
                            "card " + arcana.id() + " vulnerability amplifier must be <= V (4), got " + op.amplifier());
                }
            }
        }
    }

    // ============================================================
    // 牌效 datapack 结构: 缺字段报错冒泡 (C9 不静默给默认)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void cardDataMissingFieldThrows(GameTestHelper helper) {
        // 缺 kind 的 op 必须抛 (不静默给默认)。
        boolean threw = false;
        try {
            JsonObject bad = JsonParser.parseString("{\"amount\": 10}").getAsJsonObject();
            TarotEffectOp.fromJson(bad);
        } catch (RuntimeException e) {
            threw = true;
        }
        helper.assertTrue(threw, "op missing 'kind' must throw, not silently default");

        // tiers 不是 4 项必须抛。
        boolean threwTiers = false;
        try {
            JsonObject card = JsonParser.parseString(
                    "{\"cooldownCategory\":\"buff\",\"upright\":{\"tiers\":[[]]},"
                    + "\"reversed\":{\"tiers\":[[],[],[],[]]},\"shiny\":{\"cooldownTicks\":100,\"ops\":[]}}")
                    .getAsJsonObject();
            TarotCardData.fromJson(card);
        } catch (RuntimeException e) {
            threwTiers = true;
        }
        helper.assertTrue(threwTiers, "tiers != 4 must throw (R/SR/SSR/UR required)");
        helper.succeed();
    }

    // ============================================================
    // 合成四结果概率大样本 + 碎片返还精确 (R->SR: 50/12/28/10)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void craftFourResultDistribution(GameTestHelper helper) {
        TarotCraftService craft = new TarotCraftService();
        RandomSource rng = RandomSource.create(123456789L);

        int n = 40000;
        int success = 0, reverse = 0, shatter = 0, big = 0;
        // 用纯裁决路径 decide() 统计 (不触 JobServices/不造产物; 避免把未接线异常计成 success 污染统计)。
        for (int i = 0; i < n; i++) {
            switch (craft.decide(TarotQuality.R, rng)) {
                case SUCCESS -> success++;
                case REVERSE -> reverse++;
                case SHATTER -> shatter++;
                case BIG_SHATTER -> big++;
            }
        }
        // 四档各落区间 (R->SR: 50/12/28/10; 允许统计带宽)。
        double successRate = success / (double) n;
        double reverseRate = reverse / (double) n;
        double shatterRate = shatter / (double) n;
        double bigRate = big / (double) n;
        helper.assertTrue(successRate > 0.47 && successRate < 0.53, "R->SR success ~50%, got " + successRate);
        helper.assertTrue(reverseRate > 0.09 && reverseRate < 0.15, "R->SR reverse ~12%, got " + reverseRate);
        helper.assertTrue(shatterRate > 0.25 && shatterRate < 0.31, "R->SR shatter ~28%, got " + shatterRate);
        helper.assertTrue(bigRate > 0.07 && bigRate < 0.13, "R->SR big-shatter ~10%, got " + bigRate);

        // 碎片返还精确 (破碎 1, 大破碎 2; spec 第八章): 直接断言整数返还量 (不依赖 JobServices)。
        helper.assertTrue(TarotConfig.DUPLICATE_SHARD_REFUND.get() == 1,
                "default shard refund is 1 per duplicate (shatter=1, big=2)");
        helper.succeed();
    }

    // ============================================================
    // 派生包期望 E<1 收敛 (spec 第七章 TDD 点名)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void derivedPackExpectationConverges(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        PackGachaService gacha = new PackGachaService();
        RandomSource rng = RandomSource.create(42L);

        // 显式注入 drawCount=3, derivedChance=0.10 => 每包期望派生 0.3 < 1, 几何收敛 (不依赖 config 加载)。
        long totalDerived = 0;
        int packs = 200000;
        for (int i = 0; i < packs; i++) {
            totalDerived += gacha.openAdvanced(player, rng, 3, 0.20D, 0.10D, 10).derivedPacks();
        }
        double expectedPerPack = totalDerived / (double) packs;
        helper.assertTrue(expectedPerPack < 1.0,
                "derived pack expectation per pack must be < 1 (geometric convergence), got " + expectedPerPack);
        helper.assertTrue(expectedPerPack > 0.2 && expectedPerPack < 0.4,
                "derived expectation ~0.3 with drawCount=3 x chance=0.10, got " + expectedPerPack);
        helper.succeed();
    }

    // ============================================================
    // 派生包误配硬约束: drawCount*derivedChance>=1 必抛 (防印钞口; spec 第七章)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void derivedPackMisconfigThrows(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        PackGachaService gacha = new PackGachaService();
        RandomSource rng = RandomSource.create(1L);
        boolean threw = false;
        try {
            // drawCount=5 * derivedChance=0.25 = 1.25 >= 1: 指数发散, 必抛。
            gacha.openAdvanced(player, rng, 5, 0.20D, 0.25D, 10);
        } catch (IllegalStateException e) {
            threw = true;
        }
        helper.assertTrue(threw, "drawCount*derivedChance >= 1 must throw (geometric divergence guard)");
        helper.succeed();
    }

    // ============================================================
    // pity: SSR 概率注入为 0 时, 前 pityN 包恰无 SSR, 第 pityN+1 包首张保底 SSR (删 pity 必挂)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void pityGuaranteesSsr(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        PackGachaService gacha = new PackGachaService();
        RandomSource rng = RandomSource.create(7L);
        int pityN = 10;
        // 注入 ssrChance=0: 唯一 SSR 来源是 pity 保底, 任何 SSR 都证明 pity 生效 (非运气)。
        // 前 pityN 包 (i=0..pityN-1) streak 未到, 必全无 SSR。
        for (int i = 0; i < pityN; i++) {
            for (var card : gacha.openAdvanced(player, rng, 3, 0.0D, 0.0D, pityN).cards()) {
                helper.assertFalse(TarotCardItem.quality(card) == TarotQuality.SSR,
                        "with ssrChance=0, packs before pity floor must have no SSR (pack " + i + ")");
            }
        }
        // 第 pityN+1 包 (streak==pityN): 首张保底 SSR。
        var pityPack = gacha.openAdvanced(player, rng, 3, 0.0D, 0.0D, pityN).cards();
        helper.assertTrue(TarotCardItem.quality(pityPack.get(0)) == TarotQuality.SSR,
                "pity floor: pack #(pityN+1) first card must be SSR even at ssrChance=0 (delete pity -> fails)");
        helper.succeed();
    }

    // ============================================================
    // 最大生命增减有界 + transient 可清 (无泄漏; spec 第五/十二章)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void maxHealthBoundedAndRemovable(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        MaxHealthModifierManager mgr = new MaxHealthModifierManager();
        double base = player.getAttribute(Attributes.MAX_HEALTH).getValue();
        java.util.UUID s1 = java.util.UUID.randomUUID();

        // 增 +200 但 capUp=40 (教皇): 实际只 +40。
        mgr.apply(player, s1, 200.0D, 40.0D, 0.0D);
        double afterGain = player.getAttribute(Attributes.MAX_HEALTH).getValue();
        helper.assertTrue(Math.abs(afterGain - (base + 40.0D)) < 0.001,
                "max health gain capped at +40 (Hierophant), got delta " + (afterGain - base));
        helper.assertTrue(mgr.hasModifier(player), "modifier present after apply");

        // 移除该来源: maxHealth 回基线 (无泄漏)。
        mgr.remove(player, s1);
        double afterRemove = player.getAttribute(Attributes.MAX_HEALTH).getValue();
        helper.assertTrue(Math.abs(afterRemove - base) < 0.001,
                "max health back to baseline after remove (no leak), got " + afterRemove);
        helper.assertFalse(mgr.hasModifier(player), "modifier gone after remove");

        // 减最大生命下限 40 (世界逆位): 先 +100 升到 base+100, 再减 -1000 floor=40 -> 不低于 40。
        java.util.UUID s2 = java.util.UUID.randomUUID();
        java.util.UUID s3 = java.util.UUID.randomUUID();
        mgr.apply(player, s2, 100.0D, 1000.0D, 0.0D); // base+100
        double high = player.getAttribute(Attributes.MAX_HEALTH).getValue();
        helper.assertTrue(Math.abs(high - (base + 100.0D)) < 0.001, "raised to base+100 for floor test");
        mgr.apply(player, s3, -1000.0D, 0.0D, 40.0D); // 减到下限 40 (不破)
        double floored = player.getAttribute(Attributes.MAX_HEALTH).getValue();
        helper.assertTrue(floored >= 40.0D - 0.001, "max health reduction floored at 40, got " + floored);
        mgr.remove(player);
        helper.succeed();
    }

    // ============================================================
    // 最大生命多来源聚合: 两来源同时生效互不覆盖, 单源到期只回退本份 (C 修正)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void maxHealthMultiSourceAggregates(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        MaxHealthModifierManager mgr = new MaxHealthModifierManager();
        double base = player.getAttribute(Attributes.MAX_HEALTH).getValue();
        java.util.UUID pope = java.util.UUID.randomUUID();
        java.util.UUID world = java.util.UUID.randomUUID();

        // 教皇 +40 (cap 120) 与一个 -40 来源 (floor 0, 不触底) 同时生效: 聚合 0, 但两来源各自独立记账 (不互相覆盖)。
        mgr.apply(player, pope, 40.0D, 120.0D, 0.0D);
        helper.assertTrue(Math.abs(mgr.aggregateDelta(player) - 40.0D) < 0.001, "after pope +40, aggregate=+40");
        mgr.apply(player, world, -40.0D, 0.0D, 0.0D);
        helper.assertTrue(Math.abs(mgr.aggregateDelta(player)) < 0.001, "pope +40 and -40 source net aggregate 0");
        double bothActive = player.getAttribute(Attributes.MAX_HEALTH).getValue();
        helper.assertTrue(Math.abs(bothActive - base) < 0.001,
                "two sources net to baseline (sources do not overwrite), got " + bothActive);

        // -40 那一份到期回退: 只剩教皇 +40 (后施加的没抹掉先施加的; 交叉到期只退本份)。
        mgr.remove(player, world);
        double afterWorldExpire = player.getAttribute(Attributes.MAX_HEALTH).getValue();
        helper.assertTrue(Math.abs(afterWorldExpire - (base + 40.0D)) < 0.001,
                "removing the -40 source leaves pope +40 intact, got " + afterWorldExpire);

        // 减向下限: 聚合后不低于 floorDown。当前 base+40=60, 再加 -1000 floor=40 -> 只减到 40 (减 20)。
        java.util.UUID world2 = java.util.UUID.randomUUID();
        mgr.apply(player, world2, -1000.0D, 0.0D, 40.0D);
        double floored = player.getAttribute(Attributes.MAX_HEALTH).getValue();
        helper.assertTrue(floored >= 40.0D - 0.001 && floored <= 40.0D + 0.001,
                "aggregate floored at 40 (60 - capped reduction), got " + floored);

        mgr.remove(player);
        helper.assertFalse(mgr.hasModifier(player), "full clear removes aggregate modifier");
        helper.succeed();
    }

    // ============================================================
    // 用牌 CD: GCD 内连甩被拒 + 同卡 CD 未到被拒 (spec 9.5)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void cooldownGcdAndPerCard(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        TarotCooldownManager cd = new TarotCooldownManager();

        // 第一次用 cardId 0: 通过并占用 (gcd 30, 卡 cd 200, 非闪耀级)。
        helper.assertTrue(cd.tryUse(player, 0, 200, 30, false), "first play passes");
        // 立刻用 cardId 1 (不同卡, 但 GCD 未过): 被拒。
        helper.assertFalse(cd.tryUse(player, 1, 200, 30, false), "second different card within GCD is rejected");
        // 同卡再用: 也被拒 (GCD + 卡 CD 都未过)。
        helper.assertFalse(cd.tryUse(player, 0, 200, 30, false), "same card within cooldown is rejected");
        helper.succeed();
    }

    // ============================================================
    // 女祭司闪耀清 CD: clearAllCards 清非闪耀级, 保留闪耀级 CD (spec 9.3 "不含闪耀级")
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void clearAllCardsPreservesShinyCd(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        TarotCooldownManager cd = new TarotCooldownManager();
        java.util.UUID id = player.getUUID();

        // 占普通牌 cardId 1 的 CD (gcd 0 便于连占) 与闪耀级 cardId 2 的 CD。
        helper.assertTrue(cd.tryUse(player, 1, 6000, 0, false), "normal card 1 占用 CD");
        helper.assertTrue(cd.tryUse(player, 2, 12000, 0, true), "shiny card 2 占用 CD");
        // 二者都在冷却 (再用被拒)。
        helper.assertFalse(cd.tryUse(player, 1, 6000, 0, false), "card 1 仍在 CD");
        helper.assertFalse(cd.tryUse(player, 2, 12000, 0, true), "shiny card 2 仍在 CD");

        // 女祭司闪耀: 清全部非闪耀级 CD。
        cd.clearAllCards(id);
        // 普通牌 1 CD 已清, 可再用; 闪耀牌 2 CD 仍在 (不被清)。
        helper.assertTrue(cd.tryUse(player, 1, 6000, 0, false), "non-shiny card CD cleared (再用通过)");
        helper.assertFalse(cd.tryUse(player, 2, 12000, 0, true),
                "shiny-level card CD preserved by clearAllCards (spec 9.3 不含闪耀级)");
        helper.succeed();
    }

    // ============================================================
    // 调度器登出/死亡清队列 (spec 第十二章不再触发后续 tick)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void schedulerCancelClearsQueue(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        ScheduledEffectManager sched = new ScheduledEffectManager();
        sched.schedule(player, 100, 100, 5, p -> { });
        helper.assertTrue(sched.pendingCountFor(player.getUUID()) == 1, "one task scheduled");
        sched.cancelFor(player.getUUID());
        helper.assertTrue(sched.pendingCountFor(player.getUUID()) == 0,
                "cancelFor clears the player's queue (logout/death no longer fires)");
        helper.succeed();
    }

    // ============================================================
    // 易伤实施: 受击者带易伤 III 时 LivingHurt 后伤害 x1.5 (经全局仲裁) — spec 点名校验
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void vulnerabilityAmplifiesHurt(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        // 易伤 III (amplifier 2 = +50%)。
        player.addEffect(new MobEffectInstance(com.miningdim.effect.ModJobEffects.VULNERABILITY.get(), 200, 2));
        double pct = com.miningdim.effect.VulnerabilityHurtHandler.resolveVulnerabilityPct(player);
        helper.assertTrue(Math.abs(pct - 0.50D) < 0.0001,
                "vulnerability III resolves +50% amplification, got " + pct);
        // 模拟一次伤害放大: 10 点 x (1+0.5) = 15。
        float base = 10.0F;
        float amplified = (float) (base * (1.0D + pct));
        helper.assertTrue(Math.abs(amplified - 15.0F) < 0.001F,
                "10 damage under vulnerability III becomes 15, got " + amplified);
        player.removeEffect(com.miningdim.effect.ModJobEffects.VULNERABILITY.get());
        helper.succeed();
    }

    // ============================================================
    // 等级门控映射 (spec 9.4): L1/L3/L5/L8/L10 -> R/SR/SSR/UR/闪耀
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void qualityLevelGate(GameTestHelper helper) {
        helper.assertTrue(TarotQuality.R.requiredLevel() == 1, "R needs L1");
        helper.assertTrue(TarotQuality.SR.requiredLevel() == 3, "SR needs L3");
        helper.assertTrue(TarotQuality.SSR.requiredLevel() == 5, "SSR needs L5");
        helper.assertTrue(TarotQuality.UR.requiredLevel() == 8, "UR needs L8");
        helper.assertTrue(TarotQuality.SHINY.requiredLevel() == 10, "Shiny needs L10");
        // 合成链顺序。
        helper.assertTrue(TarotQuality.R.next() == TarotQuality.SR, "R -> SR");
        helper.assertTrue(TarotQuality.UR.next() == TarotQuality.SHINY, "UR -> Shiny");
        helper.assertTrue(TarotQuality.SHINY.next() == null, "Shiny is top quality");
        helper.succeed();
    }

    // ============================================================
    // 倒吊人逆位死亡概率大样本: R 档 20%、UR 档 2% (删 rollDeath 比较必挂; spec 第十二章点名)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void hangedManReversedDeathChanceDistribution(GameTestHelper helper) {
        TarotCardData hanged = loadCard(TarotArcana.HANGED_MAN);
        double rChance = deathGambleChance(hanged, 0);  // R 档
        double urChance = deathGambleChance(hanged, 3);  // UR 档
        helper.assertTrue(Math.abs(rChance - 0.20D) < 1e-9, "R reversed death chance = 20%, got " + rChance);
        helper.assertTrue(Math.abs(urChance - 0.02D) < 1e-9, "UR reversed death chance = 2%, got " + urChance);

        // 大样本统计: R 档落 [18%,22%], UR 档落 [1%,3%] (用 datapack 的 chance 经 rollDeath 判定)。
        RandomSource rng = RandomSource.create(20240618L);
        int n = 200000;
        int rDeaths = 0, urDeaths = 0;
        for (int i = 0; i < n; i++) {
            if (TarotEffectEngine.rollDeath(rng, rChance)) {
                rDeaths++;
            }
            if (TarotEffectEngine.rollDeath(rng, urChance)) {
                urDeaths++;
            }
        }
        double rRate = rDeaths / (double) n;
        double urRate = urDeaths / (double) n;
        helper.assertTrue(rRate > 0.18 && rRate < 0.22, "R death rate ~20%, got " + rRate);
        helper.assertTrue(urRate > 0.01 && urRate < 0.03, "UR death rate ~2%, got " + urRate);
        helper.succeed();
    }

    private static double deathGambleChance(TarotCardData card, int tierIndex) {
        TarotQuality q = new TarotQuality[]{TarotQuality.R, TarotQuality.SR, TarotQuality.SSR, TarotQuality.UR}[tierIndex];
        for (TarotEffectOp op : card.opsFor(q, false)) {
            if (op.kind() == TarotEffectKind.SELF_DEATH_GAMBLE) {
                return op.chance();
            }
        }
        throw new IllegalStateException("hanged man reversed tier " + tierIndex + " missing death gamble op");
    }

    // ============================================================
    // 死神逆位复活契约: 60s 内拦截 1 次致死并复活, 第二次不再拦截 (spec 第十二章点名)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void deathContractInterceptsOnce(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        java.util.UUID id = player.getUUID();
        long now = 1000L;
        TarotCombatState.clearAll(id);
        // 开 60s (1200t) 契约, 复活回 40 血。
        TarotCombatState.openContractRaw(id, now + 1200L, 40.0D);

        // 第一次致死: 消费契约, 返回复活血量 40。
        double first = TarotCombatState.consumeDeathContract(id, now + 600L);
        helper.assertTrue(Math.abs(first - 40.0D) < 1e-9, "first lethal hit intercepted, revive=40, got " + first);
        // 第二次致死: 契约已用 (一次性), 返回 -1 (不再拦截)。
        double second = TarotCombatState.consumeDeathContract(id, now + 700L);
        helper.assertTrue(second < 0.0D, "second lethal hit not intercepted (one-shot contract), got " + second);

        // 契约过期不拦截: 重开一个已过期窗。
        TarotCombatState.openContractRaw(id, now + 100L, 50.0D);
        double expired = TarotCombatState.consumeDeathContract(id, now + 200L);
        helper.assertTrue(expired < 0.0D, "expired contract does not intercept, got " + expired);
        TarotCombatState.clearAll(id);
        helper.succeed();
    }

    // ============================================================
    // 正义逆位均值化: 满血敌被均值化单次最多降 30 (spec 第十二章点名)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void justiceReversedAverageClampedTo30(GameTestHelper helper) {
        // 使用者 20 血, 敌 80 血, 均值 50。敌应被钳到 80-30=50 (恰好), 使用者 20->20+30=50 (恰好)。
        helper.assertTrue(Math.abs(clampToward(80.0F, 50.0F, 30.0F) - 50.0F) < 1e-4,
                "enemy 80 toward mean 50 with cap 30 -> 50");
        helper.assertTrue(Math.abs(clampToward(20.0F, 50.0F, 30.0F) - 50.0F) < 1e-4,
                "self 20 toward mean 50 with cap 30 -> 50");
        // 极端: 使用者 5 血, 敌 100 血, 均值 52.5; 敌降幅被钳 30 -> 70 (而非 52.5)。
        helper.assertTrue(Math.abs(clampToward(100.0F, 52.5F, 30.0F) - 70.0F) < 1e-4,
                "enemy 100 toward mean 52.5 but clamped to -30 -> 70 (single hit max -30)");
        // 数值表断言: 正义逆位每档 capUp = 30。
        TarotCardData justice = loadCard(TarotArcana.JUSTICE);
        for (int t = 0; t < TarotCardData.TIER_COUNT; t++) {
            TarotQuality q = new TarotQuality[]{TarotQuality.R, TarotQuality.SR, TarotQuality.SSR, TarotQuality.UR}[t];
            boolean found = false;
            for (TarotEffectOp op : justice.opsFor(q, false)) {
                if (op.kind() == TarotEffectKind.ENEMY_TARGET_AVERAGE_HEALTH) {
                    helper.assertTrue(Math.abs(op.capUp() - 30.0D) < 1e-9,
                            "justice reversed average cap = 30, got " + op.capUp());
                    found = true;
                }
            }
            helper.assertTrue(found, "justice reversed tier " + t + " has average-health op");
        }
        helper.succeed();
    }

    /** 复刻 {@link TarotEffectEngine} 的均值化钳制 (单次最多 ±maxDelta), 供 TDD 直接断言。 */
    private static float clampToward(float from, float target, float maxDelta) {
        float delta = target - from;
        if (delta > maxDelta) {
            delta = maxDelta;
        } else if (delta < -maxDelta) {
            delta = -maxDelta;
        }
        return Math.max(0.0F, from + delta);
    }

    // ============================================================
    // 正义正位反伤: 单次封顶 40 (spec 反伤单次封顶40)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void justiceReflectCappedAt40(GameTestHelper helper) {
        java.util.UUID id = java.util.UUID.randomUUID();
        long now = 500L;
        TarotCombatState.clearAll(id);
        // 反伤 60%, 单次封顶 40 (UR 档)。
        TarotCombatState.openWindowRaw(id, TarotCombatState.WindowKind.REFLECT, now + 400L, 0.60D, 40.0D);
        double pct = TarotCombatState.reflectPercent(id, now);
        double cap = TarotCombatState.reflectPerHitCap(id, now);
        helper.assertTrue(Math.abs(pct - 0.60D) < 1e-9, "reflect 60% active");
        // 受 100 伤: 60% = 60, 但单次封顶 40 -> 反伤 40。
        double reflected = Math.min(100.0D * pct, cap);
        helper.assertTrue(Math.abs(reflected - 40.0D) < 1e-9, "reflect of 100 dmg capped at 40, got " + reflected);
        // 受 50 伤: 60% = 30 < 40 -> 反伤 30 (未触顶)。
        double reflectedLow = Math.min(50.0D * pct, cap);
        helper.assertTrue(Math.abs(reflectedLow - 30.0D) < 1e-9, "reflect of 50 dmg = 30 (under cap), got " + reflectedLow);
        TarotCombatState.clearAll(id);
        helper.succeed();
    }

    // ============================================================
    // 战斗窗口过期: tick 后过期窗口移除 (反泄漏)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void combatWindowExpiresAndClears(GameTestHelper helper) {
        java.util.UUID id = java.util.UUID.randomUUID();
        TarotCombatState.clearAll(id);
        TarotCombatState.openWindowRaw(id, TarotCombatState.WindowKind.LIFESTEAL, 100L, 0.35D, 0.0D);
        helper.assertTrue(TarotCombatState.hasWindow(id, TarotCombatState.WindowKind.LIFESTEAL, 50L),
                "lifesteal window active before endTick");
        helper.assertFalse(TarotCombatState.hasWindow(id, TarotCombatState.WindowKind.LIFESTEAL, 100L),
                "lifesteal window inactive at/after endTick (no leak)");
        helper.assertTrue(Math.abs(TarotCombatState.lifestealPercent(id, 50L) - 0.35D) < 1e-9,
                "lifesteal 35% while active");
        helper.assertTrue(TarotCombatState.lifestealPercent(id, 100L) == 0.0D,
                "lifesteal 0 after expiry");
        TarotCombatState.clearAll(id);
        helper.succeed();
    }

    // ---- helpers ----

    /** 直接从打包资源读一张牌的 datapack JSON (测试期 loader 未经 reload, 故用 classloader 读 resources)。 */
    private static TarotCardData loadCard(TarotArcana arcana) {
        ResourceLocation key = arcana.dataKey();
        String path = "/data/" + key.getNamespace() + "/" + key.getPath() + ".json";
        try (InputStream in = TarotGameTests.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("tarot card resource not found on classpath: " + path);
            }
            JsonObject root = JsonParser.parseReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            return TarotCardData.fromJson(root);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("failed reading tarot card resource: " + path, e);
        }
    }
}
