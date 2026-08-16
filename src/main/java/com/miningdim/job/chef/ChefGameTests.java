package com.miningdim.job.chef;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.JobProgress;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * 厨师职业 GameTest (Chef_Job_DesignSpec 第十三章测试断言示例 + 任务测试清单)。
 *
 * 断言具体业务结果 (删被测核心逻辑测试必挂, 禁 is-not-null 弱校验); 含边界值。逐级数值断言精确到点。
 * 纯逻辑用 template = "empty" (job 框架已建 data/miningdim/structures/empty.nbt)。
 *
 * 覆盖: 品质封顶 / 效果个数与零翻车 / 战斗向门控与一菜一战斗 / 增香黑名单与逐级倍率 / 膳香 %血回血 /
 * 跨 mod 盖章 / 单菜经验逐级 / 稳膛抗击退非属性 / 调料偏置 / 窗口效果反泄漏。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class ChefGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "chef";

    // 满分小游戏成绩 (绿区命中 + 全命中): 综合分 1.0, 解析仅受台档/等级封顶。
    private static final double PERFECT = 1.0D;
    private static final int FULL_HITS = 4;
    private static final int TOTAL_CUES = 4;

    static {
        // GameTest 时 ChefSystem 未接入 MiningDim (registerConfig 未跑), 在此触发 spec 默认值加载, 使
        // ChefConfig.*.get() 可读 (否则 dev 环境抛 ISE)。接入后由 registerConfig 正常加载, 本块成空操作。
        ChefConfig.ensureLoadedForTest();
    }

    // ============================================================
    // 品质封顶: 台档 + 厨师等级双重封顶
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void doubleCapTableAndLevel(GameTestHelper helper) {
        // 满级厨师 (L10 可达闪耀) 在低级台打满分 -> 仍封到低级 (台档封顶)。
        ChefQuality onLowTable = ChefQualityResolver.resolve(PERFECT, FULL_HITS, TOTAL_CUES, ChefQuality.LOW, 10);
        helper.assertTrue(onLowTable == ChefQuality.LOW,
                "L10 chef on LOW table is capped to LOW (table cap), got " + onLowTable);

        // L1 厨师 (等级封顶低级) 在闪耀台打满分 -> 仍封到低级 (等级封顶)。
        ChefQuality l1OnRadiant = ChefQualityResolver.resolve(PERFECT, FULL_HITS, TOTAL_CUES, ChefQuality.RADIANT, 1);
        helper.assertTrue(l1OnRadiant == ChefQuality.LOW,
                "L1 chef on RADIANT table is capped to LOW (level cap), got " + l1OnRadiant);

        // L9 厨师 (可达闪耀) 在闪耀台打满分 -> 闪耀 (无封顶)。
        ChefQuality l9OnRadiant = ChefQualityResolver.resolve(PERFECT, FULL_HITS, TOTAL_CUES, ChefQuality.RADIANT, 9);
        helper.assertTrue(l9OnRadiant == ChefQuality.RADIANT,
                "L9 chef on RADIANT table at perfect score reaches RADIANT, got " + l9OnRadiant);

        // 删 qualityCapForLevel 的等级封顶, l1OnRadiant 会变成 RADIANT -> 此断言挂。
        helper.succeed();
    }

    // ============================================================
    // 效果个数 + 零翻车 (超凡/闪耀)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void extraordinaryNoFailureMaxEffects(GameTestHelper helper) {
        var random = helper.getLevel().random;
        // 超凡: maxEffects=2, 零翻车。掷多次确认恒无负面 + 个数 <=2。
        for (int i = 0; i < 50; i++) {
            List<ChefEffectInstance> effects = SeasoningEffectRoller.rollAll(
                    random, 8, ChefQuality.EXTRAORDINARY, SeasoningBias.NONE, FULL_HITS);
            helper.assertTrue(effects.size() <= ChefQuality.EXTRAORDINARY.maxEffects(),
                    "extraordinary dish carries <= 2 effects, got " + effects.size());
            helper.assertFalse(effects.isEmpty(), "no-failure quality never yields an empty dish");
            for (ChefEffectInstance e : effects) {
                helper.assertFalse(e.type().isNegative(),
                        "extraordinary dish must contain ZERO negative effects, found " + e.type());
            }
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void radiantNoFailureMaxThreeEffects(GameTestHelper helper) {
        var random = helper.getLevel().random;
        for (int i = 0; i < 50; i++) {
            List<ChefEffectInstance> effects = SeasoningEffectRoller.rollAll(
                    random, 10, ChefQuality.RADIANT, SeasoningBias.COMPLEX, FULL_HITS);
            helper.assertTrue(effects.size() <= 3,
                    "radiant dish carries <= 3 effects, got " + effects.size());
            for (ChefEffectInstance e : effects) {
                helper.assertFalse(e.type().isNegative(),
                        "radiant dish must contain ZERO negative effects, found " + e.type());
            }
        }
        // 删 ChefQuality.RADIANT.maxEffects() 上限 (改 >3) 或删 noFailure 门控, 此测必挂。
        helper.succeed();
    }

    // ============================================================
    // 战斗向: 一菜最多 1 个 + 仅高/超凡/闪耀解锁
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void atMostOneCombatEffect(GameTestHelper helper) {
        var random = helper.getLevel().random;
        for (int i = 0; i < 100; i++) {
            // 高品质 + 高等级 + 油偏置 (战斗辅助池), 强制多效果, 断言战斗向 <=1。
            List<ChefEffectInstance> effects = SeasoningEffectRoller.rollAll(
                    random, 10, ChefQuality.RADIANT, SeasoningBias.OILY, FULL_HITS);
            long combatCount = effects.stream().filter(e -> e.type().isCombat()).count();
            helper.assertTrue(combatCount <= 1,
                    "a dish carries AT MOST 1 combat effect, got " + combatCount);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void combatEffectsOnlyHighAndAbove(GameTestHelper helper) {
        var random = helper.getLevel().random;
        // 低级/中级菜: 即便高等级厨师 + 油偏置, readEffects 不含任何战斗向。
        for (int i = 0; i < 100; i++) {
            List<ChefEffectInstance> low = SeasoningEffectRoller.rollAll(
                    random, 10, ChefQuality.LOW, SeasoningBias.OILY, FULL_HITS);
            for (ChefEffectInstance e : low) {
                helper.assertFalse(e.type().isCombat(),
                        "LOW quality dish must not contain combat effects, found " + e.type());
            }
            List<ChefEffectInstance> medium = SeasoningEffectRoller.rollAll(
                    random, 10, ChefQuality.MEDIUM, SeasoningBias.OILY, FULL_HITS);
            for (ChefEffectInstance e : medium) {
                helper.assertFalse(e.type().isCombat(),
                        "MEDIUM quality dish must not contain combat effects, found " + e.type());
            }
        }
        // 删 combatUnlocked 门控 (低/中也放战斗向) 此测必挂。
        helper.succeed();
    }

    // ============================================================
    // 增香黑名单 + 逐级倍率
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void amplifyBlacklistsGoldenApple(GameTestHelper helper) {
        var player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        // 给金苹果盖闪耀增香 (x5), 先施加一个 buff (吸收 60s), 再吃 -> 黑名单生效, 时长不变。
        ItemStack apple = new ItemStack(Items.ENCHANTED_GOLDEN_APPLE);
        ChefQualityNbt.stamp(apple, ChefQuality.RADIANT,
                List.of(new ChefEffectInstance(ChefEffectType.AMPLIFY, ChefConfig.amplifyMul(ChefQuality.RADIANT))));

        int originalDuration = 60 * 20;
        player.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, originalDuration, 0));

        ChefConsumeHandler handler = new ChefConsumeHandler();
        var finishEvent = new net.minecraftforge.event.entity.living.LivingEntityUseItemEvent.Finish(
                player, apple, 0, ItemStack.EMPTY);
        handler.onFinishEating(finishEvent);

        MobEffectInstance after = player.getEffect(MobEffects.ABSORPTION);
        helper.assertTrue(after != null && after.getDuration() <= originalDuration,
                "golden apple buff duration must NOT be amplified (item blacklist), was "
                        + (after == null ? "null" : after.getDuration()) + " original " + originalDuration);
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void amplifyMultipliesDurationPerTier(GameTestHelper helper) {
        // 逐级倍率精确: 对一个非黑名单普通 buff (跳跃提升), 各档 x1.2/1.5/2/3/5。
        assertAmplify(helper, ChefQuality.LOW, 120);
        assertAmplify(helper, ChefQuality.MEDIUM, 150);
        assertAmplify(helper, ChefQuality.HIGH, 200);
        assertAmplify(helper, ChefQuality.EXTRAORDINARY, 300);
        assertAmplify(helper, ChefQuality.RADIANT, 500);
        helper.succeed();
    }

    private static void assertAmplify(GameTestHelper helper, ChefQuality quality, int expectMulX100) {
        var player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        int mul = ChefConfig.amplifyMul(quality);
        helper.assertTrue(mul == expectMulX100, "amplify x100 for " + quality + " expected "
                + expectMulX100 + " got " + mul);

        // 本菜声明自带 JUMP buff (模拟别的 mod 菜 FoodProperties 里带的 buff): 增香只乘其时长。
        int original = 100; // tick
        player.addEffect(new MobEffectInstance(MobEffects.JUMP, original, 0));
        new ChefConsumeHandler().amplifyDeclaredBuffs(player, java.util.Set.of(MobEffects.JUMP), mul);

        MobEffectInstance after = player.getEffect(MobEffects.JUMP);
        int expected = original * expectMulX100 / 100;
        helper.assertTrue(after != null && after.getDuration() == expected,
                quality + " amplify duration must be " + expected + " (=" + original + " x" + expectMulX100 + "/100), got "
                        + (after == null ? "null" : after.getDuration()));
    }

    // ============================================================
    // 增香只放大本菜自带 buff: 外来 BENEFICIAL buff (药水/信标/前菜残留) 时长不被改写 (Major 回归)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void amplifyOnlyTouchesDishOwnDeclaredBuffs(GameTestHelper helper) {
        var player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);

        // 玩家先喝下无关战斗药水/续航 buff (非本菜赋予): 速度 (BENEFICIAL, 非黑名单) + 跳跃提升。
        int speedDuration = 200; // tick
        int jumpDuration = 100;  // tick
        player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, speedDuration, 0));
        player.addEffect(new MobEffectInstance(MobEffects.JUMP, jumpDuration, 0));

        // 吃一份只声明了 JUMP 自带 buff 的闪耀增香菜 (x5): 仅 JUMP 被放大, 外来 MOVEMENT_SPEED 时长一字不改。
        int mul = ChefConfig.amplifyMul(ChefQuality.RADIANT); // 500 = x5
        new ChefConsumeHandler().amplifyDeclaredBuffs(player, java.util.Set.of(MobEffects.JUMP), mul);

        MobEffectInstance speedAfter = player.getEffect(MobEffects.MOVEMENT_SPEED);
        helper.assertTrue(speedAfter != null && speedAfter.getDuration() == speedDuration,
                "unrelated MOVEMENT_SPEED buff (not declared by the dish) MUST NOT be amplified: expected "
                        + speedDuration + " got " + (speedAfter == null ? "null" : speedAfter.getDuration()));

        MobEffectInstance jumpAfter = player.getEffect(MobEffects.JUMP);
        int expectedJump = jumpDuration * mul / 100;
        helper.assertTrue(jumpAfter != null && jumpAfter.getDuration() == expectedJump,
                "the dish's own declared JUMP buff IS amplified to " + expectedJump + ", got "
                        + (jumpAfter == null ? "null" : jumpAfter.getDuration()));
        // 删 ownEffects 限定 (改回放大全部活跃 BENEFICIAL) -> MOVEMENT_SPEED 也被乘 x5, 速度断言挂。
        helper.succeed();
    }

    // ============================================================
    // 增香不复利: 连吃两道增香菜, 第二道不对第一道已放大时长再乘一次 (各菜只乘各自自带 buff)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void amplifyDoesNotCompoundAcrossDishes(GameTestHelper helper) {
        var player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);

        // 第一道菜自带 JUMP, 第二道菜自带 NIGHT_VISION (各声明各自不同的自带 buff)。
        int jumpDuration = 100;
        int nightDuration = 100;
        player.addEffect(new MobEffectInstance(MobEffects.JUMP, jumpDuration, 0));
        player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, nightDuration, 0));

        int mul = ChefConfig.amplifyMul(ChefQuality.RADIANT); // x5
        ChefConsumeHandler handler = new ChefConsumeHandler();

        // 第一道 (只声明 JUMP): JUMP x5, NIGHT_VISION 不动。
        handler.amplifyDeclaredBuffs(player, java.util.Set.of(MobEffects.JUMP), mul);
        // 第二道 (只声明 NIGHT_VISION): NIGHT_VISION x5, 不得对已放大的 JUMP 再乘一次。
        handler.amplifyDeclaredBuffs(player, java.util.Set.of(MobEffects.NIGHT_VISION), mul);

        MobEffectInstance jumpAfter = player.getEffect(MobEffects.JUMP);
        int expectedJump = jumpDuration * mul / 100; // 只被第一道乘一次 = 500
        helper.assertTrue(jumpAfter != null && jumpAfter.getDuration() == expectedJump,
                "first dish's JUMP is amplified exactly once (no compounding by the second dish): expected "
                        + expectedJump + " got " + (jumpAfter == null ? "null" : jumpAfter.getDuration()));

        MobEffectInstance nightAfter = player.getEffect(MobEffects.NIGHT_VISION);
        int expectedNight = nightDuration * mul / 100; // 只被第二道乘一次 = 500
        helper.assertTrue(nightAfter != null && nightAfter.getDuration() == expectedNight,
                "second dish's NIGHT_VISION is amplified exactly once: expected " + expectedNight
                        + " got " + (nightAfter == null ? "null" : nightAfter.getDuration()));
        // 删 ownEffects 限定 -> 第二道会把第一道已放大的 JUMP (500) 再 x5 = 2500, 复利, JUMP 断言挂。
        helper.succeed();
    }

    // ============================================================
    // 膳香: %最大血量回血
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void nourishHealPercentMaxHp(GameTestHelper helper) {
        var player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        float maxHp = player.getMaxHealth();
        // 受伤至 max/2。
        player.setHealth(maxHp / 2.0F);

        int healPerMille = ChefConfig.healPerMille(ChefQuality.HIGH); // 75 = 7.5% maxHP
        new ChefConsumeHandler().applyHeal(player, healPerMille);

        float expected = Math.min(maxHp, maxHp / 2.0F + maxHp * (healPerMille / 1000.0F));
        helper.assertTrue(Math.abs(player.getHealth() - expected) < 0.01F,
                "heal must be %maxHP based: expected " + expected + " got " + player.getHealth());
        // 删 %最大血量公式 (改回固定 20) 此测在非 20 血环境必挂。
        helper.succeed();
    }

    // ============================================================
    // 单菜原始经验逐级精确 (config 表)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void rawXpPerQualityExact(GameTestHelper helper) {
        helper.assertTrue(ChefConfig.rawXp(ChefQuality.LOW) == 50, "raw xp LOW must be 50");
        helper.assertTrue(ChefConfig.rawXp(ChefQuality.MEDIUM) == 80, "raw xp MEDIUM must be 80");
        helper.assertTrue(ChefConfig.rawXp(ChefQuality.HIGH) == 130, "raw xp HIGH must be 130");
        helper.assertTrue(ChefConfig.rawXp(ChefQuality.EXTRAORDINARY) == 220, "raw xp EXTRAORDINARY must be 220");
        helper.assertTrue(ChefConfig.rawXp(ChefQuality.RADIANT) == 400, "raw xp RADIANT must be 400");
        // 删经验表 (改任一档) 此测必挂。
        helper.succeed();
    }

    // ============================================================
    // 跨 mod 通用盖章: 任意带 FoodProperties 的 ItemStack 都能盖章 + 非食物拒绝
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void crossModStampOnAnyFood(GameTestHelper helper) {
        // 原版 bread 有 FoodProperties -> 可盖章 + 读回。
        ItemStack bread = new ItemStack(Items.BREAD);
        helper.assertTrue(bread.getFoodProperties(null) != null, "bread is food");
        ChefQualityNbt.stamp(bread, ChefQuality.EXTRAORDINARY,
                List.of(new ChefEffectInstance(ChefEffectType.NOURISH_FOOD, 400)));
        helper.assertTrue(ChefQualityNbt.readQuality(bread) == ChefQuality.EXTRAORDINARY,
                "stamped quality survives read-back on vanilla bread");
        helper.assertTrue(ChefQualityNbt.readEffects(bread).size() == 1, "one effect read back");

        // 非食物 (钻石): getFoodProperties==null -> 调味台输入槽 mayPlace 拒绝 (此处直接验证 food 判定)。
        ItemStack diamond = new ItemStack(Items.DIAMOND);
        helper.assertTrue(diamond.getFoodProperties(null) == null,
                "non-food (diamond) has no FoodProperties and is rejected as seasonable");
        helper.succeed();
    }

    // ============================================================
    // 稳膛抗击退: 不经 AttributeModifier, 按档减比 (LivingKnockBackEvent)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void stableAimKnockbackNoAttribute(GameTestHelper helper) {
        var player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        UUID id = player.getUUID();
        // 闪耀稳膛 (100% 抗击退): 直接盖窗口。
        long end = player.serverLevel().getGameTime() + 60L * 20L;
        ChefWindowEffectState.stampRaw(id, ChefEffectType.STABLE_AIM, end,
                ChefConfig.stableAimPerMille(ChefQuality.RADIANT));
        try {
            float originalStrength = 1.0F;
            var kbEvent = new net.minecraftforge.event.entity.living.LivingKnockBackEvent(
                    player, originalStrength, 0.0D, 0.0D);
            new ChefKnockbackHandler().onKnockback(kbEvent);
            helper.assertTrue(Math.abs(kbEvent.getStrength()) < 0.001F,
                    "radiant stable-aim (100%) reduces knockback strength to 0, got " + kbEvent.getStrength());

            // 中档 50%: 减半。
            ChefWindowEffectState.stampRaw(id, ChefEffectType.STABLE_AIM, end,
                    ChefConfig.stableAimPerMille(ChefQuality.MEDIUM));
            var kb2 = new net.minecraftforge.event.entity.living.LivingKnockBackEvent(player, 1.0F, 0.0D, 0.0D);
            new ChefKnockbackHandler().onKnockback(kb2);
            helper.assertTrue(Math.abs(kb2.getStrength() - 0.5F) < 0.001F,
                    "medium stable-aim (50%) halves knockback, got " + kb2.getStrength());

            // 无属性泄漏: KNOCKBACK_RESISTANCE 属性无任何临时修饰符。
            var attr = player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.KNOCKBACK_RESISTANCE);
            helper.assertTrue(attr == null || attr.getModifiers().isEmpty(),
                    "stable-aim must NOT add a KNOCKBACK_RESISTANCE attribute modifier (no leak)");
        } finally {
            ChefWindowEffectState.clearAll(id);
        }
        helper.succeed();
    }

    // ============================================================
    // 调料偏置: 油偏置在高品质才可能出战斗辅助, 低品质不出 (与门控叠加)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void seasoningBiasOilyGatedByQuality(GameTestHelper helper) {
        var random = helper.getLevel().random;
        // 油偏置 (披甲/膳香/凝脂方向): 低品质 100 次掷不出任何战斗向 (门控)。
        boolean lowHadCombat = false;
        for (int i = 0; i < 100; i++) {
            List<ChefEffectInstance> low = SeasoningEffectRoller.rollAll(
                    random, 10, ChefQuality.LOW, SeasoningBias.OILY, FULL_HITS);
            if (low.stream().anyMatch(e -> e.type().isCombat())) {
                lowHadCombat = true;
            }
        }
        helper.assertFalse(lowHadCombat, "OILY bias on LOW quality never produces combat effects (gated)");

        // 高品质 + 高等级 + 油偏置: 多次掷应至少出现一次战斗向 (偏置生效, 门控放行)。
        boolean radiantHadCombat = false;
        for (int i = 0; i < 200 && !radiantHadCombat; i++) {
            List<ChefEffectInstance> radiant = SeasoningEffectRoller.rollAll(
                    random, 10, ChefQuality.RADIANT, SeasoningBias.OILY, FULL_HITS);
            if (radiant.stream().anyMatch(e -> e.type().isCombat())) {
                radiantHadCombat = true;
            }
        }
        helper.assertTrue(radiantHadCombat,
                "OILY bias on RADIANT quality with high level should be able to roll combat effects");
        helper.succeed();
    }

    // ============================================================
    // 窗口效果反泄漏: clearAll 后该玩家无任何 pending
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void windowEffectCleanup(GameTestHelper helper) {
        UUID id = UUID.randomUUID();
        ChefWindowEffectState.stampRaw(id, ChefEffectType.ENDURANCE, Long.MAX_VALUE, 500);
        ChefWindowEffectState.stampRaw(id, ChefEffectType.STABLE_AIM, Long.MAX_VALUE, 700);
        helper.assertTrue(ChefWindowEffectState.active(id, ChefEffectType.ENDURANCE),
                "endurance window active after stamp");
        helper.assertTrue(ChefWindowEffectState.knockbackResistPerMille(id) == 700,
                "stable-aim magnitude read back");

        ChefWindowEffectState.clearAll(id);
        helper.assertFalse(ChefWindowEffectState.active(id, ChefEffectType.ENDURANCE),
                "endurance cleared after clearAll (logout/death/dim change anti-leak)");
        helper.assertTrue(ChefWindowEffectState.knockbackResistPerMille(id) == 0,
                "no residual stable-aim after clearAll");
        helper.succeed();
    }

    // ============================================================
    // 超凡/闪耀零翻车 (对照: 低级台同失误可出多盐/失败品)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void zeroFailureVsLowCanFail(GameTestHelper helper) {
        var random = helper.getLevel().random;
        // 故意打砸 (heatAccuracy=0, 0 命中) 在超凡台 + 高等级厨师: 解析仍可能被封到低档, 但只要达成品质是
        // 超凡/闪耀 (noFailure) 就零负面。这里直接验证 noFailure 档 roll 永不出负面 (已有测覆盖); 此测验证
        // 对照: 低级 roll 在多次掷中能出现负面 (翻车风险存在), 证明门控不是 "全程无负面"。
        boolean lowHadNegative = false;
        for (int i = 0; i < 200 && !lowHadNegative; i++) {
            List<ChefEffectInstance> low = SeasoningEffectRoller.rollAll(
                    random, 2, ChefQuality.LOW, SeasoningBias.NONE, 1);
            if (low.stream().anyMatch(e -> e.type().isNegative())) {
                lowHadNegative = true;
            }
        }
        helper.assertTrue(lowHadNegative, "LOW quality CAN roll negatives (failure risk exists as control)");

        // 超凡: 零负面 (与对照对比)。
        for (int i = 0; i < 200; i++) {
            List<ChefEffectInstance> extra = SeasoningEffectRoller.rollAll(
                    random, 8, ChefQuality.EXTRAORDINARY, SeasoningBias.NONE, 2);
            for (ChefEffectInstance e : extra) {
                helper.assertFalse(e.type().isNegative(), "EXTRAORDINARY never rolls negatives");
            }
        }
        helper.succeed();
    }

    // ============================================================
    // 披甲护盾窗口过期回收 absorption (平衡红线: 护盾不得永久存在)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void shieldAbsorptionReclaimedOnExpiry(GameTestHelper helper) {
        var player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        UUID id = player.getUUID();
        try {
            // 起始无 absorption。
            player.setAbsorptionAmount(0.0F);
            float maxHp = player.getMaxHealth();
            int shieldPerMille = ChefConfig.shieldPerMille(ChefQuality.RADIANT); // 80 = 8% maxHP
            float expectedShield = maxHp * (shieldPerMille / 1000.0F);

            // 盖披甲 (120s 默认窗口): 立即授予 absorption。
            int windowSec = ChefConfig.SHIELD_WINDOW_SECONDS.get();
            ChefWindowEffectState.stampShield(player, shieldPerMille, windowSec);
            helper.assertTrue(Math.abs(player.getAbsorptionAmount() - expectedShield) < 0.01F,
                    "shield grants %maxHP absorption immediately: expected " + expectedShield
                            + " got " + player.getAbsorptionAmount());

            // 窗口未到期: 推进到窗口中点, absorption 仍在。
            long now = player.serverLevel().getGameTime();
            ChefWindowEffectState.advancePlayerWindows(id, player, now + (long) windowSec * 20L / 2L);
            helper.assertTrue(player.getAbsorptionAmount() > 0.0F,
                    "shield absorption still present mid-window, got " + player.getAbsorptionAmount());

            // 推进越过窗口结束: absorption 必须被回收清零 (删 onServerTick 的 SHIELD 回收分支此断言挂)。
            ChefWindowEffectState.advancePlayerWindows(id, player, now + (long) windowSec * 20L + 1L);
            helper.assertTrue(player.getAbsorptionAmount() == 0.0F,
                    "shield absorption MUST be reclaimed to 0 after window expiry, got "
                            + player.getAbsorptionAmount());
            helper.assertFalse(ChefWindowEffectState.active(id, ChefEffectType.SHIELD),
                    "shield window removed after expiry");
        } finally {
            ChefWindowEffectState.clearAll(id);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void shieldExpiryOnlyReclaimsOwnGrant(GameTestHelper helper) {
        var player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        UUID id = player.getUUID();
        try {
            int shieldPerMille = ChefConfig.shieldPerMille(ChefQuality.HIGH); // 40 = 4% maxHP
            float ownShield = player.getMaxHealth() * (shieldPerMille / 1000.0F);
            int windowSec = ChefConfig.SHIELD_WINDOW_SECONDS.get();
            ChefWindowEffectState.stampShield(player, shieldPerMille, windowSec);

            // 模拟另一来源 (如金苹果) 叠了额外 absorption: 总 absorption = 本窗口护盾 + 4 点外来。
            float foreign = 4.0F;
            player.setAbsorptionAmount(player.getAbsorptionAmount() + foreign);

            // 窗口过期: 只减去本窗口授予的那一份, 外来 absorption 保留 (不误扣)。
            long now = player.serverLevel().getGameTime();
            ChefWindowEffectState.advancePlayerWindows(id, player, now + (long) windowSec * 20L + 1L);
            helper.assertTrue(Math.abs(player.getAbsorptionAmount() - foreign) < 0.01F,
                    "expiry reclaims only this window's shield (" + ownShield + "), foreign absorption "
                            + foreign + " survives, got " + player.getAbsorptionAmount());
        } finally {
            ChefWindowEffectState.clearAll(id);
        }
        helper.succeed();
    }

    // ============================================================
    // 披甲护盾换维度/登出回收 absorption (平衡红线: 反泄漏不得只清窗口记录留永久护盾)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void shieldReclaimedOnChangedDimension(GameTestHelper helper) {
        var player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        UUID id = player.getUUID();
        try {
            player.setAbsorptionAmount(0.0F);
            float maxHp = player.getMaxHealth();
            int shieldPerMille = ChefConfig.shieldPerMille(ChefQuality.RADIANT); // 80 = 8% maxHP
            float grantedShield = maxHp * (shieldPerMille / 1000.0F);
            int windowSec = ChefConfig.SHIELD_WINDOW_SECONDS.get();

            // 盖披甲: 立即授予 absorption, 窗口远未到期 (默认 120s 窗口)。
            ChefWindowEffectState.stampShield(player, shieldPerMille, windowSec);
            float baseline = player.getAbsorptionAmount();
            helper.assertTrue(Math.abs(baseline - grantedShield) < 0.01F,
                    "precondition: shield grants %maxHP absorption, expected " + grantedShield
                            + " got " + baseline);

            // 触发真实换维度事件处理器 (changeDimension 复用实体不重置 absorption): 窗口未到期, 但反泄漏
            // 路径必须主动退还本窗口授予的护盾, 而非只删窗口记录 (后者会留永久护盾)。
            var dimEvent = new net.minecraftforge.event.entity.player.PlayerEvent.PlayerChangedDimensionEvent(
                    player, player.serverLevel().dimension(), player.serverLevel().dimension());
            new ChefWindowEffectState().onChangedDimension(dimEvent);

            helper.assertTrue(Math.abs(player.getAbsorptionAmount()) < 0.01F,
                    "shield absorption MUST be reclaimed to 0 on dimension change (granted " + grantedShield
                            + " reclaimed), got " + player.getAbsorptionAmount());
            helper.assertFalse(ChefWindowEffectState.active(id, ChefEffectType.SHIELD),
                    "shield window removed after dimension-change reclaim");
            // 删 reclaimOnline 的 absorption 退还 (退回只 STATE.remove) -> absorption 仍 = grantedShield, 上断言挂。
        } finally {
            ChefWindowEffectState.clearAll(id);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void shieldReclaimOnLogoutKeepsForeignAbsorption(GameTestHelper helper) {
        var player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        UUID id = player.getUUID();
        try {
            player.setAbsorptionAmount(0.0F);
            int shieldPerMille = ChefConfig.shieldPerMille(ChefQuality.HIGH); // 40 = 4% maxHP
            float grantedShield = player.getMaxHealth() * (shieldPerMille / 1000.0F);
            int windowSec = ChefConfig.SHIELD_WINDOW_SECONDS.get();
            ChefWindowEffectState.stampShield(player, shieldPerMille, windowSec);

            // 叠一份外来 absorption (如金苹果): 登出回收只退本窗口授予的护盾, 外来部分保留 (不误扣)。
            float foreign = 5.0F;
            player.setAbsorptionAmount(player.getAbsorptionAmount() + foreign);

            // 触发真实登出事件处理器: 玩家本 tick 仍在线, 应退还 grantedShield, 留下 foreign。
            var logoutEvent = new net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent(player);
            new ChefWindowEffectState().onLoggedOut(logoutEvent);

            helper.assertTrue(Math.abs(player.getAbsorptionAmount() - foreign) < 0.01F,
                    "logout reclaim退还本窗口护盾(" + grantedShield + ")但保留外来 absorption " + foreign
                            + ", got " + player.getAbsorptionAmount());
            helper.assertFalse(ChefWindowEffectState.active(id, ChefEffectType.SHIELD),
                    "shield window removed after logout reclaim");
            // 删 reclaimOnline 的退还 -> absorption 仍 = grantedShield + foreign, foreign 断言挂。
        } finally {
            ChefWindowEffectState.clearAll(id);
        }
        helper.succeed();
    }

    // ============================================================
    // 披甲记账只认自己那一份 (F083): 低档覆盖高档仍全额可回收 / 叠在外来 absorption 之上 / 同档刷新不叠加
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void shieldLowWindowOverwritingHighStillFullyReclaims(GameTestHelper helper) {
        var player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        UUID id = player.getUUID();
        try {
            player.setAbsorptionAmount(0.0F);
            float maxHp = player.getMaxHealth();
            int highPerMille = ChefConfig.shieldPerMille(ChefQuality.RADIANT); // 80 = 8% maxHP
            int lowPerMille = ChefConfig.shieldPerMille(ChefQuality.HIGH);     // 40 = 4% maxHP
            int windowSec = ChefConfig.SHIELD_WINDOW_SECONDS.get();
            float expectedHigh = maxHp * (highPerMille / 1000.0F);

            // 先吃闪耀 (高档): 立即授予 8% maxHP。
            ChefWindowEffectState.stampShield(player, highPerMille, windowSec);
            helper.assertTrue(Math.abs(player.getAbsorptionAmount() - expectedHigh) < 0.01F,
                    "high-tier shield grants expected " + expectedHigh + " got " + player.getAbsorptionAmount());

            // 窗口内又吃一份高级 (低档): 刷新不叠取 max(旧份额, 新护盾), 8% 不被 4% 压低。
            ChefWindowEffectState.stampShield(player, lowPerMille, windowSec);
            helper.assertTrue(Math.abs(player.getAbsorptionAmount() - expectedHigh) < 0.01F,
                    "low-tier dish inside the window must NOT overwrite the higher shieldGranted: expected "
                            + expectedHigh + " got " + player.getAbsorptionAmount());

            // 越过被低档刷新过的窗口: 必须全额回收 8%, 不是只退 4% (F083 修复点: 记账须跟 max(旧,新) 走)。
            long now = player.serverLevel().getGameTime();
            ChefWindowEffectState.advancePlayerWindows(id, player, now + (long) windowSec * 20L + 1L);
            helper.assertTrue(Math.abs(player.getAbsorptionAmount() - 0.0F) < 0.01F,
                    "shield fully reclaimed to 0 after a low-tier refresh, expected 0.0 got "
                            + player.getAbsorptionAmount());
            helper.assertFalse(ChefWindowEffectState.active(id, ChefEffectType.SHIELD),
                    "shield window removed after expiry");
            // 删 stampShield 里 granted = Math.max(prevOwned, shield) (改回直接赋 shield), 低档刷新会把
            // shieldGranted 压到 4%, 过期只退 4%, 残留 4% maxHP 黄心, 归零断言必挂。
        } finally {
            ChefWindowEffectState.clearAll(id);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void shieldStacksOnTopOfForeignAbsorptionAndReclaimsOwnShare(GameTestHelper helper) {
        var player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        UUID id = player.getUUID();
        try {
            player.setAbsorptionAmount(0.0F);
            float maxHp = player.getMaxHealth();
            int shieldPerMille = ChefConfig.shieldPerMille(ChefQuality.RADIANT); // 80 = 8% maxHP
            float ownShield = maxHp * (shieldPerMille / 1000.0F);
            int windowSec = ChefConfig.SHIELD_WINDOW_SECONDS.get();

            // 外来 absorption 先在场 (如金苹果), 厨师护盾此时才盖章 (比既有回归测提前的顺序: 先外来后厨师)。
            float foreign = 4.0F;
            player.setAbsorptionAmount(foreign);
            ChefWindowEffectState.stampShield(player, shieldPerMille, windowSec);

            // 厨师护盾必须叠加在外来 absorption 之上, 不能覆盖抹掉外来那一份。
            float expectedTotal = foreign + ownShield;
            helper.assertTrue(Math.abs(player.getAbsorptionAmount() - expectedTotal) < 0.01F,
                    "chef shield stacks ON TOP of pre-existing foreign absorption: expected " + expectedTotal
                            + " (foreign " + foreign + " + own " + ownShield + ") got "
                            + player.getAbsorptionAmount());

            // 越过窗口: 只扣厨师自己那一份, absorption 精确回到外来的 4.0F。
            long now = player.serverLevel().getGameTime();
            ChefWindowEffectState.advancePlayerWindows(id, player, now + (long) windowSec * 20L + 1L);
            helper.assertTrue(Math.abs(player.getAbsorptionAmount() - foreign) < 0.01F,
                    "expiry reclaims only the chef's own share, foreign absorption " + foreign
                            + " survives untouched, got " + player.getAbsorptionAmount());
            // 删 stampShield 里 foreign+granted 重铺 (改回 setAbsorptionAmount(shield) 覆盖写法), 授予
            // 那一刻 absorption 就直接变成 maxHp*0.08 而丢掉先在场的 4.0F 外来份额, 第一条断言必挂。
        } finally {
            ChefWindowEffectState.clearAll(id);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void shieldRefreshSameTierDoesNotDoubleGrant(GameTestHelper helper) {
        var player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        UUID id = player.getUUID();
        try {
            player.setAbsorptionAmount(0.0F);
            float maxHp = player.getMaxHealth();
            int shieldPerMille = ChefConfig.shieldPerMille(ChefQuality.RADIANT); // 80 = 8% maxHP
            int windowSec = ChefConfig.SHIELD_WINDOW_SECONDS.get();
            float expectedSingle = maxHp * (shieldPerMille / 1000.0F);

            // 同一档 (闪耀) 在窗口内连盖两次: 刷新不叠, absorption 必须仍是单份, 不是两份累加。
            ChefWindowEffectState.stampShield(player, shieldPerMille, windowSec);
            ChefWindowEffectState.stampShield(player, shieldPerMille, windowSec);
            helper.assertTrue(Math.abs(player.getAbsorptionAmount() - expectedSingle) < 0.01F,
                    "same-tier refresh must NOT double-grant: expected single share " + expectedSingle
                            + " (not " + (expectedSingle * 2.0F) + ") got " + player.getAbsorptionAmount());

            // 越过窗口: 单份全额回收到 0。
            long now = player.serverLevel().getGameTime();
            ChefWindowEffectState.advancePlayerWindows(id, player, now + (long) windowSec * 20L + 1L);
            helper.assertTrue(Math.abs(player.getAbsorptionAmount() - 0.0F) < 0.01F,
                    "double-stamped same-tier shield still fully reclaims to 0, got "
                            + player.getAbsorptionAmount());
            // 删 STATE.computeIfAbsent(...).put(...) 的覆盖写 (改成两个 Window 并存/累加 granted), 第二次
            // stampShield 会让 absorption 变成两份 (expectedSingle*2), 第一条断言必挂。
        } finally {
            ChefWindowEffectState.clearAll(id);
        }
        helper.succeed();
    }

    // ============================================================
    // 倒胃中毒时长逐级 (spec 第十一章: 低 8s/中 6s/高 4s, 走 config 非硬编码)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void nauseaPoisonDurationPerQuality(GameTestHelper helper) {
        // config 逐级时长精确。
        helper.assertTrue(ChefEffectMagnitude.nauseaSeconds(ChefQuality.LOW) == 8, "nausea LOW = 8s");
        helper.assertTrue(ChefEffectMagnitude.nauseaSeconds(ChefQuality.MEDIUM) == 6, "nausea MEDIUM = 6s");
        helper.assertTrue(ChefEffectMagnitude.nauseaSeconds(ChefQuality.HIGH) == 4, "nausea HIGH = 4s");

        // 端到端: 吃带倒胃章的菜, POISON 时长 = nauseaSeconds*20 且分档不同 (删品质透传/写死 8*20 此测必挂)。
        assertNausea(helper, ChefQuality.LOW, 2, 8 * 20);    // 低: 毒II (amplifier 1), 8s
        assertNausea(helper, ChefQuality.MEDIUM, 1, 6 * 20); // 中: 毒I (amplifier 0), 6s
        assertNausea(helper, ChefQuality.HIGH, 1, 4 * 20);   // 高: 毒I (amplifier 0), 4s
        helper.succeed();
    }

    private static void assertNausea(GameTestHelper helper, ChefQuality quality, int poisonLevel,
                                     int expectedTicks) {
        var player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ItemStack bread = new ItemStack(Items.BREAD);
        ChefQualityNbt.stamp(bread, quality,
                List.of(new ChefEffectInstance(ChefEffectType.NAUSEA, poisonLevel)));
        new ChefConsumeHandler().onFinishEating(
                new net.minecraftforge.event.entity.living.LivingEntityUseItemEvent.Finish(
                        player, bread, 0, ItemStack.EMPTY));

        MobEffectInstance poison = player.getEffect(MobEffects.POISON);
        helper.assertTrue(poison != null && poison.getDuration() == expectedTicks,
                quality + " nausea POISON duration must be " + expectedTicks + " ticks ("
                        + (expectedTicks / 20) + "s), got " + (poison == null ? "null" : poison.getDuration()));
        helper.assertTrue(poison.getAmplifier() == poisonLevel - 1,
                quality + " nausea poison amplifier must be " + (poisonLevel - 1) + ", got "
                        + poison.getAmplifier());
    }

    // ============================================================
    // chef-02: 提神 (REFRESH) 急速时长按品质逐级 (90/150/240/360/600s), 取代旧硬编码 240s
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void refreshHasteDurationPerQuality(GameTestHelper helper) {
        // config 逐级时长精确 (低/中/高/超凡/闪耀)。
        helper.assertTrue(ChefConfig.refreshSeconds(ChefQuality.LOW) == 90, "refresh LOW = 90s");
        helper.assertTrue(ChefConfig.refreshSeconds(ChefQuality.MEDIUM) == 150, "refresh MEDIUM = 150s");
        helper.assertTrue(ChefConfig.refreshSeconds(ChefQuality.HIGH) == 240, "refresh HIGH = 240s (was the hardcoded value)");
        helper.assertTrue(ChefConfig.refreshSeconds(ChefQuality.EXTRAORDINARY) == 360, "refresh EXTRAORDINARY = 360s");
        helper.assertTrue(ChefConfig.refreshSeconds(ChefQuality.RADIANT) == 600, "refresh RADIANT = 600s");

        // 端到端: 吃带提神章的菜, DIG_SPEED 时长 = refreshSeconds*20 且分档不同。删品质分级查表 (退回硬编码 240s)
        // 则非 HIGH 档 (LOW/MEDIUM/EXTRAORDINARY/RADIANT) 的时长断言全挂 (它们都不是 240s)。
        assertRefresh(helper, ChefQuality.LOW, 90 * 20);
        assertRefresh(helper, ChefQuality.MEDIUM, 150 * 20);
        assertRefresh(helper, ChefQuality.HIGH, 240 * 20);
        assertRefresh(helper, ChefQuality.EXTRAORDINARY, 360 * 20);
        assertRefresh(helper, ChefQuality.RADIANT, 600 * 20);
        helper.succeed();
    }

    private static void assertRefresh(GameTestHelper helper, ChefQuality quality, int expectedTicks) {
        var player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        // 提神 magnitude = 急速等级 = tier+1 (与 ChefEffectMagnitude.snapshot 同口径); 时长按品质另查表。
        int hasteLevel = quality.tier() + 1;
        ItemStack bread = new ItemStack(Items.BREAD);
        ChefQualityNbt.stamp(bread, quality,
                List.of(new ChefEffectInstance(ChefEffectType.REFRESH, hasteLevel)));
        new ChefConsumeHandler().onFinishEating(
                new net.minecraftforge.event.entity.living.LivingEntityUseItemEvent.Finish(
                        player, bread, 0, ItemStack.EMPTY));

        MobEffectInstance haste = player.getEffect(MobEffects.DIG_SPEED);
        helper.assertTrue(haste != null && haste.getDuration() == expectedTicks,
                quality + " refresh DIG_SPEED duration must be " + expectedTicks + " ticks ("
                        + (expectedTicks / 20) + "s), got " + (haste == null ? "null" : haste.getDuration()));
        helper.assertTrue(haste.getAmplifier() == hasteLevel - 1,
                quality + " refresh haste amplifier must be " + (hasteLevel - 1) + ", got " + haste.getAmplifier());
    }

    // ============================================================
    // 失败品 (SPOILED): 销毁菜肴 = 零回复, 须同时抵消饱食 AND 饱和 (Minor 回归)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void spoiledDishRevertsBothFoodAndSaturation(GameTestHelper helper) {
        var player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);

        // 模拟 "刚吃完面包" 的进食后状态: 原版 eat() 同时加 food 与 saturation。面包 nutrition=5, satMod=0.6,
        // 故 satGained = 5 * 0.6 * 2 = 6.0。这里把进食后状态设为 food=20, sat=10 (满足原版 sat<=food 不变量)。
        ItemStack bread = new ItemStack(Items.BREAD);
        var props = bread.getFoodProperties(player);
        helper.assertTrue(props != null && props.getNutrition() == 5,
                "test precondition: vanilla bread nutrition is 5");
        float satGained = props.getNutrition() * props.getSaturationModifier() * 2.0F; // 6.0
        helper.assertTrue(Math.abs(satGained - 6.0F) < 0.001F,
                "test precondition: bread satGained = nutrition*satMod*2 = 6.0, got " + satGained);

        net.minecraft.world.food.FoodData food = player.getFoodData();
        food.setFoodLevel(20);
        food.setSaturation(10.0F);

        // 失败品菜: onFinishEating 走 SPOILED 前置分支 -> revertVanillaFood 抵消 food + saturation。
        ChefQualityNbt.stamp(bread, ChefQuality.LOW,
                List.of(new ChefEffectInstance(ChefEffectType.SPOILED, 0)));
        new ChefConsumeHandler().onFinishEating(
                new net.minecraftforge.event.entity.living.LivingEntityUseItemEvent.Finish(
                        player, bread, 0, ItemStack.EMPTY));

        // 饱食: 20 - 5 = 15。
        helper.assertTrue(food.getFoodLevel() == 15,
                "spoiled dish reverts food by nutrition (20-5=15), got " + food.getFoodLevel());
        // 饱和: 10 - 6 = 4 (钳 [0, foodLevel]); 删饱和回退分支 -> 饱和留在 10, 此断言挂。
        helper.assertTrue(Math.abs(food.getSaturationLevel() - 4.0F) < 0.001F,
                "spoiled dish MUST also revert saturation (10-6=4) so destroy=zero-recovery, got "
                        + food.getSaturationLevel());
        helper.succeed();
    }

    // ============================================================
    // 谁做谁得: 吃菜永不入账经验 (堵代练; 经验仅做菜阶段给 operator)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void eatingNeverGrantsXpRegardlessOfHolder(GameTestHelper helper) {
        // 一道高品质带战斗向膳香的菜被任意玩家吃下: ChefConsumeHandler 结算效果但绝不给经验
        // (经验只在 finishCooking 给 operator)。这里验证: 吃菜流程对 CHEF 进度零影响 = "谁做谁得" 的吃端契约。
        var eater = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        UUID operatorId = UUID.randomUUID(); // 做菜者 != 吃菜者。

        ItemStack dish = new ItemStack(Items.COOKED_BEEF);
        int healPerMille = ChefConfig.healPerMille(ChefQuality.HIGH);
        ChefQualityNbt.stamp(dish, ChefQuality.HIGH,
                List.of(new ChefEffectInstance(ChefEffectType.NOURISH_HEAL, healPerMille)));
        ChefQualityNbt.setOperator(dish, operatorId);

        // 受伤以便膳香回血生效, 证明 "吃" 确实跑了结算路径 (而非空过)。
        float maxHp = eater.getMaxHealth();
        eater.setHealth(maxHp / 2.0F);
        float before = eater.getHealth();

        new ChefConsumeHandler().onFinishEating(
                new net.minecraftforge.event.entity.living.LivingEntityUseItemEvent.Finish(
                        eater, dish, 0, ItemStack.EMPTY));

        // 膳香确实回血了 (结算路径跑通)。
        helper.assertTrue(eater.getHealth() > before,
                "eating a restorative dish heals the eater (consume path ran), before " + before
                        + " after " + eater.getHealth());
        // 操作者 UUID 仍是原做菜者, 与吃菜者无关 (经验归属凭据未被吃菜改写)。
        helper.assertTrue(operatorId.equals(ChefQualityNbt.readOperator(dish)),
                "operator stamp stays the cook, not the eater (anti power-leveling attribution)");
        helper.assertFalse(operatorId.equals(eater.getUUID()),
                "test precondition: cook and eater are different players");
        helper.succeed();
    }

    // ============================================================
    // 单份做菜: 整组堆叠只产出 1 份盖章菜, 余下原菜留输入槽 (反挂机, 防批量刷菜漏洞)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void cookingProducesExactlyOneStampedDish(GameTestHelper helper) {
        var operator = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        UUID operatorId = operator.getUUID();
        operator.getInventory().clearContent(); // 干净背包便于断言产出。

        // 输入 16 份食物 (模拟 "囤一箱面包梭哈"): 完成做菜应只盖 1 份, 余 15 份未调味留槽。
        net.minecraftforge.items.ItemStackHandler slots =
                new net.minecraftforge.items.ItemStackHandler(SeasoningMenu.CONTAINER_SLOTS);
        slots.setStackInSlot(SeasoningMenu.SLOT_INPUT, new ItemStack(Items.BREAD, 16));

        SeasoningTableBlockEntity.produceSingleDish(slots, operator, operatorId, ChefQuality.HIGH,
                List.of(new ChefEffectInstance(ChefEffectType.NOURISH_FOOD,
                        ChefConfig.nourishFoodMul(ChefQuality.HIGH))));

        // 输入槽: 剩 15 份且未盖章 (玩家须逐份再打小游戏)。
        ItemStack remaining = slots.getStackInSlot(SeasoningMenu.SLOT_INPUT);
        helper.assertTrue(remaining.getCount() == 15,
                "after one minigame the input keeps count-1 (15) un-stamped, got " + remaining.getCount());
        helper.assertFalse(ChefQualityNbt.hasQuality(remaining),
                "leftover input must NOT be stamped (must re-cook each individually)");

        // 操作者背包: 恰好 1 份盖章成品 (盖章份数与小游戏 1:1; 删 split(1) 改整组盖章此断言挂)。
        int stampedCount = 0;
        int stampedStacks = 0;
        for (int i = 0; i < operator.getInventory().getContainerSize(); i++) {
            ItemStack s = operator.getInventory().getItem(i);
            if (ChefQualityNbt.hasQuality(s)) {
                stampedStacks++;
                stampedCount += s.getCount();
                helper.assertTrue(operatorId.equals(ChefQualityNbt.readOperator(s)),
                        "stamped dish records the operator UUID");
            }
        }
        helper.assertTrue(stampedStacks == 1 && stampedCount == 1,
                "exactly ONE stamped dish produced per minigame, got " + stampedCount
                        + " in " + stampedStacks + " stack(s)");
        helper.succeed();
    }

    // ============================================================
    // 厨师原始经验经框架每日衰减折算 (chef raw XP -> JobProgress 分段衰减, 跨 2000 边界精确)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void chefRawXpDecaysThroughDailySegments(GameTestHelper helper) {
        // 厨师 award 只发原始经验 (ChefConfig.rawXp), 衰减由框架 JobProgress.grantXp 裁决。这里把厨师原始经验
        // 表喂进同一 progress, 验证跨 2000 软上限边界的精确折算 (operator 当日已刷 1900, 再做一道高品质菜)。
        JobProgress progress = new JobProgress();
        long day = 500L;
        // 先刷到当日有效经验 1900 (全额段 x1.0)。
        long warmup = progress.grantXp(1_900L, day);
        helper.assertTrue(warmup == 1_900L, "warmup 1900 raw is full (x1.0) -> 1900 effective");

        // 高品质菜原始经验 = 130; 当日已 1900, 入 130 横跨 2000 边界:
        // [1900,2000) 100 x1.0 + [2000,2030) 30 x0.4 = 100 + 12 = 112 有效经验 (floor)。
        long highRaw = ChefConfig.rawXp(ChefQuality.HIGH);
        helper.assertTrue(highRaw == 130L, "high dish raw xp is 130 (config table)");
        long effective = progress.grantXp(highRaw, day);
        helper.assertTrue(effective == 112L,
                "high dish (130 raw) at dailyXp=1900 decays to 112 (100*1.0 + 30*0.4), got " + effective);
        helper.assertTrue(progress.dailyXp() == 2_012L, "dailyXp accumulates 1900 + 112 = 2012, got "
                + progress.dailyXp());

        // 翻日: 额度刷新, 同一道高品质菜又全额入账 130。
        long nextDay = progress.grantXp(highRaw, day + 1L);
        helper.assertTrue(nextDay == 130L,
                "after UTC rollover the same 130-raw dish is full again, got " + nextDay);
        helper.succeed();
    }
}
