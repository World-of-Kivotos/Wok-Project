package com.miningdim.job.brewer;

import com.miningdim.core.MiningConstants;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * 永久层数系统 + 9 种闪耀永久特殊的纯逻辑 + 在世 GameTest (阶段 5(iii)(iv))。具体业务断言:
 *  - 年份 -> 层映射各阈值点 (&lt;10->0 / 10->1 / 18->2 / 25->3) + 加层封顶 5;
 *  - 死亡清零 (层 + 身上修饰);
 *  - 各特殊满层数值 (金酒 +50% / 伏特加 rate=0.25 / 威士忌香槟周期回血量 / 白兰地满 5=急迫 III / 龙舌兰朗姆满层值);
 *  - 金酒跨职业全局帽钳算; 月光满层确定性抽 5 条不重复 + 登录据存还原。
 * 删任一被测核心逻辑该测必挂。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class BrewBuffStoreGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "brewer";
    private static final double EPS = 1e-9D;

    // ============================================================
    // 年份 -> 层映射 + 加层封顶 (纯逻辑)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void vintageLayerGainMapsThresholds(GameTestHelper helper) {
        BrewerConfig.ensureLoadedForTest();
        // <T1(10) -> +0; [T1,T2) -> +1; [T2,T3) -> +2; >=T3(25) -> +3。各端点精确。
        helper.assertTrue(BrewBuffStore.vintageLayerGain(0.0D) == 0, "vintage 0 -> +0 layers");
        helper.assertTrue(BrewBuffStore.vintageLayerGain(9.99D) == 0, "vintage 9.99 (<10) -> +0 layers");
        helper.assertTrue(BrewBuffStore.vintageLayerGain(10.0D) == 1, "vintage 10 -> +1 layer");
        helper.assertTrue(BrewBuffStore.vintageLayerGain(17.0D) == 1, "vintage 17 (<18) -> +1 layer");
        helper.assertTrue(BrewBuffStore.vintageLayerGain(18.0D) == 2, "vintage 18 -> +2 layers");
        helper.assertTrue(BrewBuffStore.vintageLayerGain(24.99D) == 2, "vintage 24.99 (<25) -> +2 layers");
        helper.assertTrue(BrewBuffStore.vintageLayerGain(25.0D) == 3, "vintage 25 -> +3 layers");
        helper.assertTrue(BrewBuffStore.vintageLayerGain(100.0D) == 3, "vintage 100 -> +3 layers (no extra tier)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void addLayersAccumulatesAndCapsAtFive(GameTestHelper helper) {
        BrewerConfig.ensureLoadedForTest();
        BrewBuffStore store = freshStore(helper);
        UUID id = UUID.randomUUID();
        // 喝 v25 (+3) -> 3; 再 v18 (+2) -> 5 (封顶); 再 v25 (+3) -> 仍 5。
        helper.assertTrue(store.addLayersForVintage(id, WineType.GIN, 25.0D) == 3, "first v25 -> 3 layers");
        helper.assertTrue(store.addLayersForVintage(id, WineType.GIN, 18.0D) == 5, "v18 takes 3->5 (capped exact)");
        helper.assertTrue(store.addLayersForVintage(id, WineType.GIN, 25.0D) == 5, "further drink stays capped at 5");
        // 嫩闪耀酒 (<10) 不加层: 另一类型保持 0。
        helper.assertTrue(store.addLayersForVintage(id, WineType.RUM, 9.0D) == 0, "young brilliant (<10) adds no layer");
        helper.assertTrue(store.layers(id, WineType.RUM) == 0, "rum still 0 after young drink");
        // per-type 独立: gin 5 不影响 vodka。
        helper.assertTrue(store.layers(id, WineType.VODKA) == 0, "vodka independent of gin layers");
        helper.succeed();
    }

    // ============================================================
    // 各特殊满层数值 (纯逻辑)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void vodkaReductionScalesToQuarterAtFull(GameTestHelper helper) {
        BrewerConfig.ensureLoadedForTest();
        // 烈酒钝感: 5%/层。满 5 层 = 0.25; 满层不重复挂修饰, 受击瞬间现算。
        helper.assertTrue(Math.abs(BrewPermanentBuffs.vodkaReductionRate(0) - 0.0D) < EPS, "0 layers -> 0 reduction");
        helper.assertTrue(Math.abs(BrewPermanentBuffs.vodkaReductionRate(1) - 0.05D) < EPS, "1 layer -> 0.05");
        helper.assertTrue(Math.abs(BrewPermanentBuffs.vodkaReductionRate(5) - 0.25D) < EPS, "full 5 layers -> 0.25");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void periodicHealAmountsScaleWithLayersAndMaxHealth(GameTestHelper helper) {
        BrewerConfig.ensureLoadedForTest();
        // 威士忌 5%/层, 香槟 1%/层; 量 = 最大血 × 每层% × 层数。80 血公服: 威士忌满 5 = 80×0.05×5 = 20;
        // 香槟满 5 = 80×0.01×5 = 4。
        float max = 80.0F;
        helper.assertTrue(Math.abs(BrewPermanentBuffs.periodicHealAmount(max, 5, BrewerConfig.WHISKEY_HEAL_PCT_PER_LAYER.get()) - 20.0F) < 1e-4F,
                "whiskey full 5 layers heals 20 (80*0.05*5) per cycle");
        helper.assertTrue(Math.abs(BrewPermanentBuffs.periodicHealAmount(max, 1, BrewerConfig.WHISKEY_HEAL_PCT_PER_LAYER.get()) - 4.0F) < 1e-4F,
                "whiskey 1 layer heals 4 (80*0.05*1) per cycle");
        helper.assertTrue(Math.abs(BrewPermanentBuffs.periodicHealAmount(max, 5, BrewerConfig.CHAMPAGNE_HEAL_PCT_PER_LAYER.get()) - 4.0F) < 1e-4F,
                "champagne full 5 layers heals 4 (80*0.01*5) per cycle");
        // 周期配置默认值未漂移 (locks default; 平衡调整改 toml 不改此断言的含义, 但默认值必须仍是 600/20)。
        helper.assertTrue(BrewerConfig.WHISKEY_HEAL_INTERVAL_TICKS.get() == 600, "whiskey cycle default = 30s = 600 ticks");
        helper.assertTrue(BrewerConfig.CHAMPAGNE_HEAL_INTERVAL_TICKS.get() == 20, "champagne cycle default = 1s = 20 ticks");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void brandyHasteAmplifierReachesThreeAtFull(GameTestHelper helper) {
        BrewerConfig.ensureLoadedForTest();
        // 按层放大: 1-2 -> I(0), 3-4 -> II(1), 5 -> III(2)。
        helper.assertTrue(BrewPermanentBuffs.brandyHasteAmplifier(1) == 0, "1 layer -> haste I (amp 0)");
        helper.assertTrue(BrewPermanentBuffs.brandyHasteAmplifier(2) == 0, "2 layers -> haste I (amp 0)");
        helper.assertTrue(BrewPermanentBuffs.brandyHasteAmplifier(3) == 1, "3 layers -> haste II (amp 1)");
        helper.assertTrue(BrewPermanentBuffs.brandyHasteAmplifier(5) == 2, "full 5 layers -> haste III (amp 2)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void maotaiXpMultiplierReachesOnePointFiveAtFull(GameTestHelper helper) {
        BrewerConfig.ensureLoadedForTest();
        helper.assertTrue(Math.abs(BrewPermanentBuffs.maotaiXpMultiplier(0) - 1.0D) < EPS, "0 layers -> x1.0");
        helper.assertTrue(Math.abs(BrewPermanentBuffs.maotaiXpMultiplier(1) - 1.10D) < EPS, "1 layer -> x1.10");
        helper.assertTrue(Math.abs(BrewPermanentBuffs.maotaiXpMultiplier(5) - 1.50D) < EPS, "full 5 layers -> x1.50 (+50%)");
        helper.succeed();
    }

    // ============================================================
    // 金酒跨职业全局帽钳 (纯逻辑)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void ginGlobalCapClampsAgainstOtherSources(GameTestHelper helper) {
        BrewerConfig.ensureLoadedForTest();
        // base=80, 全局帽 = 80×100% = 80 额外最大血。
        double base = 80.0D;
        // 无其它来源: 金酒满 5 层拟增 = 80×0.10×5 = 40 (远在帽内) -> 全收 40。
        double desiredFull = base * BrewerConfig.GIN_MAX_HEALTH_PCT_PER_LAYER.get() * 5;
        helper.assertTrue(Math.abs(desiredFull - 40.0D) < EPS, "gin full desired = 40 (80*0.10*5)");
        helper.assertTrue(Math.abs(GinMaxHealthManager.clampToGlobalCap(desiredFull, 0.0D, base) - 40.0D) < EPS,
                "no other source: gin keeps full 40");
        // 塔罗已占 60: 帽剩 20 -> 金酒 40 被削到 20。
        helper.assertTrue(Math.abs(GinMaxHealthManager.clampToGlobalCap(40.0D, 60.0D, base) - 20.0D) < EPS,
                "tarot occupies 60 of 80 cap: gin clamped 40->20");
        // 塔罗已占满 80: 金酒被削到 0。
        helper.assertTrue(Math.abs(GinMaxHealthManager.clampToGlobalCap(40.0D, 80.0D, base) - 0.0D) < EPS,
                "tarot fills cap: gin clamped to 0");
        // 塔罗超占 (>帽, 极端): 金酒 0, 不出负。
        helper.assertTrue(GinMaxHealthManager.clampToGlobalCap(40.0D, 100.0D, base) == 0.0D,
                "tarot over cap: gin 0, never negative");
        helper.succeed();
    }

    // ============================================================
    // 月光满层确定性抽 5 条不重复 (纯逻辑)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void moonshineRollsFiveDistinctDeterministically(GameTestHelper helper) {
        BrewerConfig.ensureLoadedForTest();
        UUID id = UUID.randomUUID();
        MoonshinePerk[] a = MoonshinePerk.rollDistinct(id, 5);
        MoonshinePerk[] b = MoonshinePerk.rollDistinct(id, 5);
        helper.assertTrue(a.length == 5, "rolls exactly 5 perks");
        // 确定性: 同 UUID 两次抽完全一致 (登录重挂据存还原, 但抽取本身也必须确定)。
        for (int i = 0; i < 5; i++) {
            helper.assertTrue(a[i] == b[i], "deterministic: same uuid yields same perk order at index " + i);
        }
        // 不重复。
        java.util.Set<MoonshinePerk> seen = new java.util.HashSet<>();
        for (MoonshinePerk p : a) {
            helper.assertTrue(seen.add(p), "no duplicate perk: " + p);
        }
        // 不同 UUID 应大概率得不同组 (至少顺序不同; 用一个明显不同的 id 验证非常量返回)。
        MoonshinePerk[] other = MoonshinePerk.rollDistinct(new UUID(id.getMostSignificantBits() ^ 0xFFFFL,
                id.getLeastSignificantBits() ^ 0x1234L), 5);
        boolean differs = false;
        for (int i = 0; i < 5; i++) {
            if (other[i] != a[i]) {
                differs = true;
                break;
            }
        }
        helper.assertTrue(differs, "different uuid yields a different roll (not a constant)");
        helper.succeed();
    }

    // ============================================================
    // 在世: 金酒满层 +50% 最大血 + 死亡清零 (闭环)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void ginFullLayersRaisesMaxHealthByFiftyPercentThenDeathClears(GameTestHelper helper) {
        BrewerConfig.ensureLoadedForTest();
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        GinMaxHealthManager gin = new GinMaxHealthManager();
        double base = player.getAttribute(Attributes.MAX_HEALTH).getBaseValue();

        // 金酒满 5 层, 无其它来源: 最大血 +50%。
        gin.apply(player, 5, 0.0D);
        double full = player.getAttribute(Attributes.MAX_HEALTH).getValue();
        helper.assertTrue(Math.abs(full - base * 1.5D) < 0.001D,
                "gin full 5 layers -> +50% max health: expected " + (base * 1.5D) + ", got " + full);
        helper.assertTrue(Math.abs(gin.ginDelta(player) - base * 0.5D) < 0.001D,
                "gin delta = 50% of base, got " + gin.ginDelta(player));

        // 死亡清: 移除金酒修饰, 最大血回基线 (无泄漏)。
        gin.remove(player);
        double cleared = player.getAttribute(Attributes.MAX_HEALTH).getValue();
        helper.assertTrue(Math.abs(cleared - base) < 0.001D,
                "after death-clear max health back to base (no leak), got " + cleared);
        helper.assertTrue(gin.ginDelta(player) == 0.0D, "gin delta zeroed after clear");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void deathClearsAllLayersAndAttributesAndEffects(GameTestHelper helper) {
        BrewerConfig.ensureLoadedForTest();
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        UUID id = player.getUUID();
        BrewBuffStore store = freshStore(helper);
        GinMaxHealthManager gin = new GinMaxHealthManager();
        BrewPermanentBuffs buffs = new BrewPermanentBuffs(gin);

        // 攒满多类型层并施加: 朗姆 (移速) + 龙舌兰 (近战) + 白兰地 (急迫)。
        store.addLayersForVintage(id, WineType.RUM, 25.0D);   // +3
        store.addLayersForVintage(id, WineType.RUM, 25.0D);   // +3 -> 5
        store.addLayersForVintage(id, WineType.TEQUILA, 25.0D); // 3
        store.addLayersForVintage(id, WineType.BRANDY, 25.0D);  // 3
        buffs.remountAll(player, store, 0.0D);

        double baseAttack = player.getAttribute(Attributes.ATTACK_DAMAGE).getBaseValue();
        double attackWith = player.getAttribute(Attributes.ATTACK_DAMAGE).getValue();
        helper.assertTrue(Math.abs(attackWith - (baseAttack + 9.0D)) < 0.001D,
                "tequila 3 layers -> +9 attack (3*3), got delta " + (attackWith - baseAttack));
        helper.assertTrue(player.getEffect(MobEffects.DIG_SPEED) != null, "brandy haste present after remount");

        // 死亡: 清层 + 清身上全部修饰/效果。
        store.clearAll(id);
        buffs.clearAll(player);
        helper.assertTrue(store.layers(id, WineType.RUM) == 0, "rum layers cleared on death");
        helper.assertTrue(store.layers(id, WineType.TEQUILA) == 0, "tequila layers cleared on death");
        helper.assertFalse(store.hasAnyLayers(id), "no layers remain after death-clear");
        double attackAfter = player.getAttribute(Attributes.ATTACK_DAMAGE).getValue();
        helper.assertTrue(Math.abs(attackAfter - baseAttack) < 0.001D,
                "attack back to base after death-clear (no leak), got " + attackAfter);
        helper.assertTrue(player.getEffect(MobEffects.DIG_SPEED) == null, "brandy haste removed on death");
        helper.succeed();
    }

    // ============================================================
    // 在世: 登录重挂据存的层数正确重建 (闭环)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void loginRemountsRebuildsFromStoredLayers(GameTestHelper helper) {
        BrewerConfig.ensureLoadedForTest();
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        UUID id = player.getUUID();
        BrewBuffStore store = freshStore(helper);
        GinMaxHealthManager gin = new GinMaxHealthManager();
        BrewPermanentBuffs buffs = new BrewPermanentBuffs(gin);
        double baseSpeed = player.getAttribute(Attributes.MOVEMENT_SPEED).getBaseValue();

        // store 里存朗姆满 5 层 (模拟上一会话已固化), 身上无修饰 (会话不跨); 登录重挂应重建移速。
        store.addLayersForVintage(id, WineType.RUM, 25.0D);
        store.addLayersForVintage(id, WineType.RUM, 25.0D); // -> 5
        helper.assertTrue(Math.abs(player.getAttribute(Attributes.MOVEMENT_SPEED).getValue() - baseSpeed) < 1e-6D,
                "no modifier before remount (attributes don't persist across session)");

        buffs.remountAll(player, store, 0.0D);
        // 朗姆满 5 层 +30% 移速 (MULTIPLY_BASE 0.06*5=0.30)。
        double remounted = player.getAttribute(Attributes.MOVEMENT_SPEED).getValue();
        helper.assertTrue(Math.abs(remounted - baseSpeed * 1.30D) < 1e-6D,
                "rum full 5 layers remounted -> +30% move speed: expected " + (baseSpeed * 1.30D) + ", got " + remounted);

        // 重挂幂等: 再次重挂不叠两份 (先清再挂)。
        buffs.remountAll(player, store, 0.0D);
        double again = player.getAttribute(Attributes.MOVEMENT_SPEED).getValue();
        helper.assertTrue(Math.abs(again - remounted) < 1e-6D, "remount idempotent (no double-stack), got " + again);
        gin.remove(player);
        buffs.clearAll(player);
        helper.succeed();
    }

    // ============================================================
    // F084: 白兰地永久急迫的来源判据 (只删本系统那条, 不误删外来急迫)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void clearAllDoesNotRemoveExternalNonAmbientHaste(GameTestHelper helper) {
        BrewerConfig.ensureLoadedForTest();
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        // 模拟厨师提神/信标/指令那类 ambient=false 的外来急迫来源 (与本系统 ambient=true 的判据互斥)。
        player.addEffect(new MobEffectInstance(MobEffects.DIG_SPEED, 300, 0, false, true));

        new BrewPermanentBuffs(new GinMaxHealthManager()).clearAll(player);

        MobEffectInstance remaining = player.getEffect(MobEffects.DIG_SPEED);
        helper.assertTrue(remaining != null, "external ambient=false haste must survive clearAll (F084), got null");
        helper.assertTrue(remaining.getAmplifier() == 0, "external haste amplifier unchanged, got " + remaining.getAmplifier());
        helper.assertTrue(remaining.getDuration() > 0, "external haste duration unchanged (still counting down), got " + remaining.getDuration());
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void clearAllStillRemovesOwnBrandyHaste(GameTestHelper helper) {
        BrewerConfig.ensureLoadedForTest();
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        BrewPermanentBuffs buffs = new BrewPermanentBuffs(new GinMaxHealthManager());

        buffs.applyBrandyHaste(player, 5);
        MobEffectInstance applied = player.getEffect(MobEffects.DIG_SPEED);
        helper.assertTrue(applied != null, "brandy haste must be applied at 5 layers");
        helper.assertTrue(applied.getAmplifier() == 2, "5 layers -> haste III (amp 2), got " + applied.getAmplifier());
        helper.assertTrue(applied.getDuration() > 72000, "own brandy haste uses the permanent long duration, got " + applied.getDuration());

        buffs.clearAll(player);
        helper.assertTrue(player.getEffect(MobEffects.DIG_SPEED) == null,
                "own brandy haste must be fully removed by clearAll (not left over-permissive), got "
                        + player.getEffect(MobEffects.DIG_SPEED));
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void zeroLayerBrandyHasteOnlyRemovesOwnSource(GameTestHelper helper) {
        BrewerConfig.ensureLoadedForTest();
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        // 玩家身上只有外来急迫 (ambient=false), 无本系统那条。
        player.addEffect(new MobEffectInstance(MobEffects.DIG_SPEED, 300, 0, false, true));

        // 0 层分支 (removeBrandyPermanentHaste) 同样只应删本系统来源: 判据不通过, 不动玩家身上的外来急迫。
        new BrewPermanentBuffs(new GinMaxHealthManager()).applyBrandyHaste(player, 0);

        MobEffectInstance remaining = player.getEffect(MobEffects.DIG_SPEED);
        helper.assertTrue(remaining != null, "0-layer branch must not touch external haste, got null");
        helper.assertTrue(remaining.getAmplifier() == 0, "external haste amplifier untouched, got " + remaining.getAmplifier());
        helper.assertTrue(remaining.getDuration() == 300, "external haste duration untouched (300), got " + remaining.getDuration());
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void ownBrandyHasteSurvivesVanillaMergeWithBeaconStyleExternalHaste(GameTestHelper helper) {
        BrewerConfig.ensureLoadedForTest();
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        BrewPermanentBuffs buffs = new BrewPermanentBuffs(new GinMaxHealthManager());

        // 先施加本系统白兰地永久急迫 (2 层 -> amp 0, ambient=true/visible=false, 见 applyBrandyHaste)。
        buffs.applyBrandyHaste(player, 2);
        MobEffectInstance before = player.getEffect(MobEffects.DIG_SPEED);
        helper.assertTrue(before != null && before.isAmbient() && !before.isVisible(),
                "own brandy haste starts as (ambient=true, visible=false)");

        // 复核指出的真实路径: 原版信标语义 (ambient=true, visible=true, amp<=本系统当前, duration 远短于
        // 72000)。MobEffectInstance.update 对已存在实例的 visible 无条件同步 (:115-118, 与 amplifier/duration
        // 分支无关), 会把【本系统这一条已存在的实例】的 visible 就地改写成 true; amplifier/duration 因外来
        // duration 更短而保持不变 (本系统的 huge duration 恒不小于外来短时长, 不会被覆盖)。
        player.addEffect(new MobEffectInstance(MobEffects.DIG_SPEED, 340, 0, true, true));
        MobEffectInstance corrupted = player.getEffect(MobEffects.DIG_SPEED);
        helper.assertTrue(corrupted.isVisible(),
                "sanity: vanilla merge really did flip visible to true (bug precondition reproduced)");
        helper.assertTrue(corrupted.getDuration() > 72000,
                "duration untouched by the weaker external merge -- the surviving identity signal, got "
                        + corrupted.getDuration());

        // 旧判据 (ambient&&!visible) 此刻会误判"不是本系统的", 从而漏删; 复核修正后的判据只看 duration,
        // 仍能正确识别并清除。
        buffs.clearAll(player);
        helper.assertTrue(player.getEffect(MobEffects.DIG_SPEED) == null,
                "own brandy haste must still be removed after vanilla corrupted its visible flag, got "
                        + player.getEffect(MobEffects.DIG_SPEED));
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void ownBrandyHasteSurvivesVanillaMergeWithChefStyleExternalHaste(GameTestHelper helper) {
        BrewerConfig.ensureLoadedForTest();
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        BrewPermanentBuffs buffs = new BrewPermanentBuffs(new GinMaxHealthManager());

        buffs.applyBrandyHaste(player, 2); // amp 0, ambient=true, visible=false。

        // 厨师提神语义 (ChefConsumeHandler): ambient=false, visible=true, 这里取与本系统当前层数相同的
        // amp0、最长档 12000 tick (仍远小于 72000) 复现"ambient 也被顶掉"的那条分支。
        player.addEffect(new MobEffectInstance(MobEffects.DIG_SPEED, 12000, 0, false, true));
        MobEffectInstance corrupted = player.getEffect(MobEffects.DIG_SPEED);
        helper.assertTrue(!corrupted.isAmbient(), "sanity: vanilla merge flips ambient to false too, got isAmbient=true");
        helper.assertTrue(corrupted.isVisible(), "sanity: and visible flips to true");
        helper.assertTrue(corrupted.getDuration() > 72000,
                "duration still untouched even when both ambient and visible got corrupted, got "
                        + corrupted.getDuration());

        buffs.clearAll(player);
        helper.assertTrue(player.getEffect(MobEffects.DIG_SPEED) == null,
                "still removed after both ambient and visible got corrupted, got "
                        + player.getEffect(MobEffects.DIG_SPEED));
        helper.succeed();
    }

    // ---- helpers ----

    /** 取 overworld 的共享 store (用随机 UUID 隔离, 不需清表)。 */
    private static BrewBuffStore freshStore(GameTestHelper helper) {
        ServerLevel overworld = helper.getLevel().getServer().overworld();
        return BrewBuffStore.get(overworld);
    }
}
