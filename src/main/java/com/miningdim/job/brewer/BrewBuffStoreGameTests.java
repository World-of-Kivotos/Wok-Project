package com.miningdim.job.brewer;

import com.miningdim.core.MiningConstants;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
        // 烈酒钝感: 5%/层。满 5 层 = 0.25; 满层不重复挂修饰, 受击瞬间现算。
        helper.assertTrue(Math.abs(BrewPermanentBuffs.vodkaReductionRate(0) - 0.0D) < EPS, "0 layers -> 0 reduction");
        helper.assertTrue(Math.abs(BrewPermanentBuffs.vodkaReductionRate(1) - 0.05D) < EPS, "1 layer -> 0.05");
        helper.assertTrue(Math.abs(BrewPermanentBuffs.vodkaReductionRate(5) - 0.25D) < EPS, "full 5 layers -> 0.25");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void periodicHealAmountsScaleWithLayersAndMaxHealth(GameTestHelper helper) {
        // 威士忌 5%/层, 香槟 1%/层; 量 = 最大血 × 每层% × 层数。80 血公服: 威士忌满 5 = 80×0.05×5 = 20;
        // 香槟满 5 = 80×0.01×5 = 4。
        float max = 80.0F;
        helper.assertTrue(Math.abs(BrewPermanentBuffs.periodicHealAmount(max, 5, BrewerConstants.WHISKEY_HEAL_PCT_PER_LAYER) - 20.0F) < 1e-4F,
                "whiskey full 5 layers heals 20 (80*0.05*5) per cycle");
        helper.assertTrue(Math.abs(BrewPermanentBuffs.periodicHealAmount(max, 1, BrewerConstants.WHISKEY_HEAL_PCT_PER_LAYER) - 4.0F) < 1e-4F,
                "whiskey 1 layer heals 4 (80*0.05*1) per cycle");
        helper.assertTrue(Math.abs(BrewPermanentBuffs.periodicHealAmount(max, 5, BrewerConstants.CHAMPAGNE_HEAL_PCT_PER_LAYER) - 4.0F) < 1e-4F,
                "champagne full 5 layers heals 4 (80*0.01*5) per cycle");
        // 周期常量: 威士忌 30 秒, 香槟 1 秒。
        helper.assertTrue(BrewerConstants.WHISKEY_HEAL_INTERVAL_TICKS == 600, "whiskey cycle = 30s = 600 ticks");
        helper.assertTrue(BrewerConstants.CHAMPAGNE_HEAL_INTERVAL_TICKS == 20, "champagne cycle = 1s = 20 ticks");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void brandyHasteAmplifierReachesThreeAtFull(GameTestHelper helper) {
        // 按层放大: 1-2 -> I(0), 3-4 -> II(1), 5 -> III(2)。
        helper.assertTrue(BrewPermanentBuffs.brandyHasteAmplifier(1) == 0, "1 layer -> haste I (amp 0)");
        helper.assertTrue(BrewPermanentBuffs.brandyHasteAmplifier(2) == 0, "2 layers -> haste I (amp 0)");
        helper.assertTrue(BrewPermanentBuffs.brandyHasteAmplifier(3) == 1, "3 layers -> haste II (amp 1)");
        helper.assertTrue(BrewPermanentBuffs.brandyHasteAmplifier(5) == 2, "full 5 layers -> haste III (amp 2)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void maotaiXpMultiplierReachesOnePointFiveAtFull(GameTestHelper helper) {
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
        // base=80, 全局帽 = 80×100% = 80 额外最大血。
        double base = 80.0D;
        // 无其它来源: 金酒满 5 层拟增 = 80×0.10×5 = 40 (远在帽内) -> 全收 40。
        double desiredFull = base * BrewerConstants.GIN_MAX_HEALTH_PCT_PER_LAYER * 5;
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

    // ---- helpers ----

    /** 取 overworld 的共享 store (用随机 UUID 隔离, 不需清表)。 */
    private static BrewBuffStore freshStore(GameTestHelper helper) {
        ServerLevel overworld = helper.getLevel().getServer().overworld();
        return BrewBuffStore.get(overworld);
    }
}
