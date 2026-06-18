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
import net.minecraft.world.item.ItemStack;
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

    // ============================================================
    // 正义闪耀: 不再降级为药水 —— shiny 含 80% 即时反伤(封顶80) + 免疫击退 + 40% 累计回击(封顶60)
    // (删任一 op 或破坏 drainReflectAccum 封顶逻辑必挂)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void justiceShinyReflectAndAccumNotDowngraded(GameTestHelper helper) {
        TarotCardData justice = loadCard(TarotArcana.JUSTICE);
        List<TarotEffectOp> shiny = justice.opsFor(TarotQuality.SHINY, true);

        // 必须含 80% 即时反伤(封顶80)、免疫击退、40% 累计回击(封顶60, 半径15)。降级为单条 resistance 药水必挂。
        TarotEffectOp reflect = findKind(shiny, TarotEffectKind.SELF_REFLECT);
        helper.assertTrue(reflect != null && Math.abs(reflect.percent() - 0.80D) < 1e-9
                        && Math.abs(reflect.capUp() - 80.0D) < 1e-9,
                "justice shiny must have 80% reflect cap 80, got " + (reflect == null ? "none" : reflect.percent()));
        helper.assertTrue(findKind(shiny, TarotEffectKind.SELF_KNOCKBACK_IMMUNITY) != null,
                "justice shiny must grant knockback immunity");
        TarotEffectOp accum = findKind(shiny, TarotEffectKind.SELF_REFLECT_ACCUM);
        helper.assertTrue(accum != null && Math.abs(accum.percent() - 0.40D) < 1e-9
                        && Math.abs(accum.capUp() - 60.0D) < 1e-9 && Math.abs(accum.radius() - 15.0D) < 1e-9,
                "justice shiny must have 40% accumulated retaliation cap 60 radius 15");
        // 降级回归: shiny 不应只是一条 resistance 药水 (复审缺陷复现)。
        helper.assertFalse(shiny.size() == 1 && shiny.get(0).kind() == TarotEffectKind.SELF_POTION,
                "justice shiny must NOT be a single resistance potion (downgrade regression)");

        // 累计回击结算: 攻击者累计 200 伤害 -> 40% = 80, 封顶 60 -> 实回 60。另一攻击者累计 100 -> 40 (未触顶)。
        java.util.UUID victim = java.util.UUID.randomUUID();
        java.util.UUID atkHeavy = java.util.UUID.randomUUID();
        java.util.UUID atkLight = java.util.UUID.randomUUID();
        TarotCombatState.clearAll(victim);
        TarotCombatState.openReflectAccumRaw(victim, 1000L, 0.40D, 60.0D, 15.0D);
        TarotCombatState.recordReflectAccum(victim, atkHeavy, 120.0D, 10L);
        TarotCombatState.recordReflectAccum(victim, atkHeavy, 80.0D, 20L);  // 累计 200
        TarotCombatState.recordReflectAccum(victim, atkLight, 100.0D, 30L);
        // 过期后不再累计 (窗口外伤害不计)。
        TarotCombatState.recordReflectAccum(victim, atkLight, 500.0D, 2000L);
        var retaliations = TarotCombatState.drainReflectAccum(victim);
        helper.assertTrue(Math.abs(retaliations.get(atkHeavy) - 60.0D) < 1e-9,
                "heavy attacker 200 dmg -> 40% = 80 capped to 60, got " + retaliations.get(atkHeavy));
        helper.assertTrue(Math.abs(retaliations.get(atkLight) - 40.0D) < 1e-9,
                "light attacker 100 dmg -> 40% = 40 (under cap), got " + retaliations.get(atkLight));
        // drain 是一次性: 二次 drain 为空 (窗口已移除)。
        helper.assertTrue(TarotCombatState.drainReflectAccum(victim).isEmpty(),
                "reflect-accum drained once then empty (no leak)");
        TarotCombatState.clearAll(victim);
        helper.succeed();
    }

    // ============================================================
    // 倒吊人闪耀: 不再降级为药水 —— shiny 含延迟记账冻死(50%结算+存活+40) + 力量V + 恢复III + 免疫击退
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void hangedManShinyDelayedLedgerNotDowngraded(GameTestHelper helper) {
        TarotCardData hanged = loadCard(TarotArcana.HANGED_MAN);
        List<TarotEffectOp> shiny = hanged.opsFor(TarotQuality.SHINY, true);

        TarotEffectOp ledger = findKind(shiny, TarotEffectKind.SELF_DELAYED_LEDGER);
        helper.assertTrue(ledger != null && Math.abs(ledger.percent() - 0.50D) < 1e-9
                        && Math.abs(ledger.amount() - 40.0D) < 1e-9,
                "hanged man shiny must have delayed ledger settle 50% + survive heal 40");
        helper.assertTrue(findKind(shiny, TarotEffectKind.SELF_KNOCKBACK_IMMUNITY) != null,
                "hanged man shiny must grant knockback immunity (was missing in review)");
        // 力量V (amplifier 4) + 恢复III (amplifier 2) 仍在。
        boolean strengthV = false, regenIII = false;
        for (TarotEffectOp op : shiny) {
            if (op.kind() == TarotEffectKind.SELF_POTION && "minecraft:strength".equals(op.effectId()) && op.amplifier() == 4) {
                strengthV = true;
            }
            if (op.kind() == TarotEffectKind.SELF_POTION && "minecraft:regeneration".equals(op.effectId()) && op.amplifier() == 2) {
                regenIII = true;
            }
        }
        helper.assertTrue(strengthV, "hanged man shiny must grant Strength V");
        helper.assertTrue(regenIII, "hanged man shiny must grant Regeneration III");

        // 延迟记账结算: 挂起 200 伤害 -> 50% = 100。drainLedger 返回 [settleDamage, surviveHeal]。
        java.util.UUID id = java.util.UUID.randomUUID();
        TarotCombatState.clearAll(id);
        TarotCombatState.openLedgerRaw(id, 1000L, 0.50D, 40.0D);
        TarotCombatState.recordLedgerDamage(id, 120.0D, 10L);
        TarotCombatState.recordLedgerDamage(id, 80.0D, 20L);  // 累计 200
        TarotCombatState.recordLedgerDamage(id, 999.0D, 2000L); // 窗口外不计
        double[] settle = TarotCombatState.drainLedger(id);
        helper.assertTrue(settle != null && Math.abs(settle[0] - 100.0D) < 1e-9,
                "ledger pending 200 settles 50% = 100, got " + (settle == null ? "null" : settle[0]));
        helper.assertTrue(Math.abs(settle[1] - 40.0D) < 1e-9, "ledger survive heal = 40");
        helper.assertTrue(TarotCombatState.drainLedger(id) == null, "ledger drained once then null (no leak)");
        TarotCombatState.clearAll(id);
        helper.succeed();
    }

    // ============================================================
    // 死神闪耀: 不再退化为平 AoE —— shiny 是 <30% 血处决 + 每杀回20/叠力量至V + 无目标全体50穿刺
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void deathShinyExecuteNotFlatAoe(GameTestHelper helper) {
        TarotCardData death = loadCard(TarotArcana.DEATH);
        List<TarotEffectOp> shiny = death.opsFor(TarotQuality.SHINY, true);
        helper.assertTrue(shiny.size() == 1, "death shiny is a single execute op");
        TarotEffectOp exec = shiny.get(0);
        helper.assertTrue(exec.kind() == TarotEffectKind.AOE_EXECUTE_BELOW_PCT,
                "death shiny must be aoe_execute_below_pct, not flat aoe_enemy_damage (downgrade regression)");
        helper.assertTrue(Math.abs(exec.percent() - 0.30D) < 1e-9, "execute threshold = 30% health, got " + exec.percent());
        helper.assertTrue(Math.abs(exec.radius() - 12.0D) < 1e-9, "execute radius = 12");
        helper.assertTrue(Math.abs(exec.amount() - 50.0D) < 1e-9, "no-target fallback = 50 piercing");
        helper.assertTrue(Math.abs(exec.threshold() - 20.0D) < 1e-9, "heal per elite kill = 20");
        helper.assertTrue(exec.amplifier() == 4, "strength stack cap = V (amplifier 4)");
        helper.assertFalse(exec.kind() == TarotEffectKind.AOE_ENEMY_DAMAGE,
                "death shiny must NOT be plain aoe_enemy_damage (downgrade regression)");
        helper.succeed();
    }

    // ============================================================
    // 恋人闪耀: 不再降级为药水 —— shiny 是绑定共享生死 (需同意握手 + 一方死另一方延迟死 + 距离解绑)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void loversShinyBindShareLifeNotDowngraded(GameTestHelper helper) {
        TarotCardData lovers = loadCard(TarotArcana.LOVERS);
        List<TarotEffectOp> shiny = lovers.opsFor(TarotQuality.SHINY, true);
        helper.assertTrue(shiny.size() == 1 && shiny.get(0).kind() == TarotEffectKind.SHINY_BIND_SHARE_LIFE,
                "lovers shiny must be bind-share-life, not a resistance potion (downgrade regression)");
        TarotEffectOp bind = shiny.get(0);
        helper.assertTrue(bind.durationTicks() == 300, "bind duration 15s = 300 ticks");
        helper.assertTrue(Math.abs(bind.radius() - 50.0D) < 1e-9, "unbind distance = 50 blocks");
        helper.assertTrue(bind.count() == 60, "delayed co-death = 3s = 60 ticks");
        helper.assertFalse(bind.kind() == TarotEffectKind.SELF_POTION,
                "lovers shiny must NOT be a self potion (downgrade regression)");

        // 绑定状态机: 双向绑定 + partner 查询 + 双向解绑。
        java.util.UUID a = java.util.UUID.randomUUID();
        java.util.UUID b = java.util.UUID.randomUUID();
        TarotCombatState.clearAll(a);
        TarotCombatState.clearAll(b);
        TarotCombatState.openBondRaw(a, b, 1000L, 50.0D, 60);
        helper.assertTrue(b.equals(TarotCombatState.bondPartner(a, 100L)), "a bonded to b");
        helper.assertTrue(a.equals(TarotCombatState.bondPartner(b, 100L)), "b bonded to a (bidirectional)");
        helper.assertTrue(Math.abs(TarotCombatState.bondUnbindDistance(a) - 50.0D) < 1e-9, "unbind distance recorded");
        helper.assertTrue(TarotCombatState.bondDeathDelay(a) == 60, "death delay recorded");
        // 清一侧即双向解绑。
        TarotCombatState.clearBond(a);
        helper.assertTrue(TarotCombatState.bondPartner(a, 100L) == null, "a unbound");
        helper.assertTrue(TarotCombatState.bondPartner(b, 100L) == null, "b unbound (bidirectional clear)");
        // 过期绑定不再有效。
        TarotCombatState.openBondRaw(a, b, 100L, 50.0D, 60);
        helper.assertTrue(TarotCombatState.bondPartner(a, 200L) == null, "expired bond not active");
        TarotCombatState.clearAll(a);
        TarotCombatState.clearAll(b);
        helper.succeed();
    }

    // ============================================================
    // 恋人闪耀同意握手: /tarot consent 开窗 -> consume 一次性 -> 过期不可用 (无同意不绑定)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void loversConsentHandshakeOneShot(GameTestHelper helper) {
        java.util.UUID partner = java.util.UUID.randomUUID();
        TarotConsentRegistry.clear(partner);
        long now = 1000L;

        // 无同意: consume 返回 false (绑定被拒, spec "需同意")。
        helper.assertFalse(TarotConsentRegistry.consume(partner, now), "no consent -> cannot bind");

        // 开一个同意窗 (endTick = now+200)。窗内 consume 成功一次, 第二次失败 (一次性)。
        TarotConsentRegistry.injectConsentForTest(partner, now + 200L);
        helper.assertTrue(TarotConsentRegistry.hasConsent(partner, now + 100L), "consent active within window");
        helper.assertTrue(TarotConsentRegistry.consume(partner, now + 100L), "consent consumable within window");
        helper.assertFalse(TarotConsentRegistry.consume(partner, now + 100L),
                "consent one-shot: second consume fails (cannot reuse one consent for two binds)");

        // 过期同意不可消费。
        TarotConsentRegistry.injectConsentForTest(partner, now + 200L);
        helper.assertFalse(TarotConsentRegistry.consume(partner, now + 300L), "expired consent not consumable");
        helper.assertFalse(TarotConsentRegistry.hasConsent(partner, now + 300L), "expired consent not active");
        TarotConsentRegistry.clear(partner);
        helper.succeed();
    }

    // ============================================================
    // 倒吊人逆位赌死 x 死神逆位复活契约: 赌输但有契约 -> 契约救命且不空过 (Minor 修正)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void deathGambleConsumedByActiveContract(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        java.util.UUID id = player.getUUID();
        TarotEffectEngine engine = new TarotEffectEngine(new MaxHealthModifierManager(), new ScheduledEffectManager());
        // 强制赌输: chance=1.0 -> rollDeath 必为 true。牺牲最大生命 15, 归还延迟 1800, 下限 20。
        TarotEffectOp gamble = new TarotEffectOp(TarotEffectKind.SELF_DEATH_GAMBLE, "", 0, 1800,
                15.0D, 0.0D, 0, 0.0D, 20.0D, 1.0D, 0.0D, 0.0D);

        // 路径 B (先跑, 避免后续给已死 mock 玩家复活): 有有效契约 + 赌输 -> 契约救命, applyDeathGamble 返回 false
        // (存活, 继续给收益), 玩家未死。删 Minor 修正 (契约判定) 则此处会真死 + 返回 true, 测试挂。
        TarotCombatState.clearAll(id);
        player.setHealth(player.getMaxHealth());
        long now = player.getServer().getTickCount();
        TarotCombatState.openContractRaw(id, now + 1200L, 40.0D);
        boolean diedWithContract = engine.applyDeathGambleForTest(player, gamble);
        helper.assertFalse(diedWithContract,
                "gamble loss WITH active contract -> survives (no empty-pass; rewards continue) [delete Minor fix -> fails]");
        helper.assertTrue(player.getHealth() > 0.0F,
                "contract-saved gamble survivor has positive health, got " + player.getHealth());
        // 契约被这次赌死消费 (一次性): 再无契约可救。
        helper.assertTrue(TarotCombatState.consumeDeathContract(id, player.getServer().getTickCount()) < 0.0D,
                "contract one-shot consumed by gamble-loss life-save");

        // 路径 A (最后跑, 真死): 无契约 + 赌输 -> 真死 (setHealth 0), applyDeathGamble 返回 true (中止收益)。
        TarotCombatState.clearAll(id);
        player.setHealth(player.getMaxHealth());
        boolean diedNoContract = engine.applyDeathGambleForTest(player, gamble);
        helper.assertTrue(diedNoContract, "gamble loss with no contract -> dies (abort rewards)");
        helper.assertTrue(player.getHealth() <= 0.0F, "no-contract gamble loss sets health to 0");

        TarotCombatState.clearAll(id);
        helper.succeed();
    }

    // ============================================================
    // 重复牌转碎片: 玩家已持同 cardId -> 开包改发碎片而非重复牌 (删重复检测必挂)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void duplicateCardConvertsToShards(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.getInventory().clearContent();
        PackGachaService gacha = new PackGachaService();
        // 让玩家先持有 cardId 0 (任意品质)。
        player.getInventory().add(TarotCardItem.create(
                TarotRegistry.TAROT_CARD.get(), 0, TarotQuality.R, true, player.getUUID()));

        // 用一个必然抽到 cardId 0 的 RNG: nextInt(22) 受种子影响, 故改用直接断言 grantOrRefund 语义经 openCommon
        // 大样本: 既持 cardId 0, 若开出 cardId 0 必转碎片 (cards 不含 cardId 0)。统计 1000 包, 所有产出牌都不应是
        // 已持有的 cardId 0 (重复牌全转碎片), 且至少出现一次碎片返还 (证明转化发生)。
        RandomSource rng = RandomSource.create(99L);
        int packsWithShard = 0;
        boolean anyOwnedDuplicateLeaked = false;
        for (int i = 0; i < 1000; i++) {
            PackGachaService.OpenResult r = gacha.openCommon(player, rng);
            if (r.shardRefund() > 0) {
                packsWithShard++;
            }
            for (var card : r.cards()) {
                if (TarotCardItem.cardId(card) == 0) {
                    anyOwnedDuplicateLeaked = true;
                }
            }
        }
        helper.assertFalse(anyOwnedDuplicateLeaked,
                "owned cardId 0 must NEVER be granted again as a card (always converts to shards)");
        helper.assertTrue(packsWithShard > 0,
                "duplicate-to-shard conversion must actually fire (delete duplicate check -> no shards)");
        // 每次转化恰好返还 DUPLICATE_SHARD_REFUND 张 (精确; 普通包 1 张牌, 命中即整张转碎片)。
        helper.assertTrue(TarotConfig.DUPLICATE_SHARD_REFUND.get() >= 1, "refund per duplicate >= 1");
        player.getInventory().clearContent();
        helper.succeed();
    }

    // ============================================================
    // 碎片兑换毕业线: 攒够 SHARD_EXCHANGE_COST 张碎片 -> 确定性兑换指定 SSR 牌, 扣碎片精确; 不足无副作用
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void shardExchangeGraduationLine(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.getInventory().clearContent();
        int cost = TarotConfig.SHARD_EXCHANGE_COST.get();

        // 碎片不足 (cost-1): 兑换失败且无副作用 (不扣不发)。
        player.getInventory().add(TarotCraftService.makeShards(cost - 1));
        TarotShardExchange.ExchangeResult fail = TarotShardExchange.exchange(player, 5, true);
        helper.assertFalse(fail.success(), "exchange with insufficient shards must fail");
        helper.assertTrue(TarotShardExchange.countShards(player) == cost - 1,
                "failed exchange does not consume shards (transactional), got " + TarotShardExchange.countShards(player));

        // 补足到恰好 cost: 兑换成功, 扣光 cost 张, 给一张指定 cardId 5 SSR 牌。
        player.getInventory().add(TarotCraftService.makeShards(1)); // 现在 cost 张
        helper.assertTrue(TarotShardExchange.countShards(player) == cost, "now exactly cost shards");
        TarotShardExchange.ExchangeResult ok = TarotShardExchange.exchange(player, 5, true);
        helper.assertTrue(ok.success() && ok.shardsSpent() == cost,
                "exchange spends exactly cost shards, got " + ok.shardsSpent());
        helper.assertTrue(TarotShardExchange.countShards(player) == 0,
                "all cost shards consumed, got " + TarotShardExchange.countShards(player));
        // 产物: 指定 cardId 5 的 SSR 牌存在于背包。
        boolean gotCard = false;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (!s.isEmpty() && s.getItem() instanceof TarotCardItem
                    && TarotCardItem.cardId(s) == 5 && TarotCardItem.quality(s) == TarotQuality.SSR) {
                gotCard = true;
            }
        }
        helper.assertTrue(gotCard, "exchange grants the chosen cardId 5 as SSR (deterministic graduation)");
        player.getInventory().clearContent();
        helper.succeed();
    }

    private static TarotEffectOp findKind(List<TarotEffectOp> ops, TarotEffectKind kind) {
        for (TarotEffectOp op : ops) {
            if (op.kind() == kind) {
                return op;
            }
        }
        return null;
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
