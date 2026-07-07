package com.miningdim.champion;

import com.miningdim.core.MiningConstants;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.RandomSource;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * 支援召唤 SUMMON_SUPPORT (spec 7.4 + 红线 8 三重封顶) 纯逻辑 GameTest。只断言 {@link ChampionSummonPlan} 的
 * 冷却表/召唤数与存活上限扣减数学/星级钳制/词条过滤 (删被测折算/钳制/过滤必挂)。真服 (召唤实体/落点/经济闸)
 * 由 {@code ChampionSummonHandler} 每秒扫近玩家冠军施加 (真服验)。
 *
 * template = "empty", batch = "champion_summon"。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class ChampionSummonGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "champion_summon";

    // ============================================================
    // 冷却表 (第三重封顶 30/26/22/18/14s = 600/520/440/360/280 tick)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void cooldownTablePerQuality(GameTestHelper helper) {
        helper.assertTrue(ChampionSummonPlan.cooldownTicks(AffixQuality.COMMON) == 600L, "召唤 CD 普通 = 30s = 600 tick");
        helper.assertTrue(ChampionSummonPlan.cooldownTicks(AffixQuality.UNCOMMON) == 520L, "召唤 CD 中级 = 26s = 520 tick");
        helper.assertTrue(ChampionSummonPlan.cooldownTicks(AffixQuality.RARE) == 440L, "召唤 CD 高级 = 22s = 440 tick");
        helper.assertTrue(ChampionSummonPlan.cooldownTicks(AffixQuality.EPIC) == 360L, "召唤 CD 超凡 = 18s = 360 tick");
        helper.assertTrue(ChampionSummonPlan.cooldownTicks(AffixQuality.LEGENDARY) == 280L, "召唤 CD 闪耀 = 14s = 280 tick");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void cooldownElapsedGate(GameTestHelper helper) {
        // 从未召唤 (MIN_VALUE) 恒就绪; 恰满 CD 就绪; 差 1 tick 未就绪 (COMMON=600 tick)。
        helper.assertTrue(ChampionSummonPlan.cooldownElapsed(500L, Long.MIN_VALUE, AffixQuality.COMMON),
                "从未召唤 = 冷却就绪");
        helper.assertTrue(ChampionSummonPlan.cooldownElapsed(600L, 0L, AffixQuality.COMMON),
                "距上次召唤 600tick = 就绪");
        helper.assertTrue(!ChampionSummonPlan.cooldownElapsed(599L, 0L, AffixQuality.COMMON),
                "距上次召唤 599tick = 冷却中");
        helper.succeed();
    }

    // ============================================================
    // 召唤数 / 存活上限 / 实际召唤数扣减 (含上限已满 = 0)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void summonsPerCastPerQuality(GameTestHelper helper) {
        helper.assertTrue(ChampionSummonPlan.summonsPerCast(AffixQuality.COMMON) == 1, "每次召唤数 普通 = 1");
        helper.assertTrue(ChampionSummonPlan.summonsPerCast(AffixQuality.UNCOMMON) == 2, "每次召唤数 中级 = 2");
        helper.assertTrue(ChampionSummonPlan.summonsPerCast(AffixQuality.RARE) == 2, "每次召唤数 高级 = 2");
        helper.assertTrue(ChampionSummonPlan.summonsPerCast(AffixQuality.EPIC) == 3, "每次召唤数 超凡 = 3");
        helper.assertTrue(ChampionSummonPlan.summonsPerCast(AffixQuality.LEGENDARY) == 3, "每次召唤数 闪耀 = 3");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void simultaneousCapPerQuality(GameTestHelper helper) {
        helper.assertTrue(ChampionSummonPlan.simultaneousCap(AffixQuality.COMMON) == 2, "同时存活上限 普通 = 2");
        helper.assertTrue(ChampionSummonPlan.simultaneousCap(AffixQuality.UNCOMMON) == 3, "同时存活上限 中级 = 3");
        helper.assertTrue(ChampionSummonPlan.simultaneousCap(AffixQuality.RARE) == 4, "同时存活上限 高级 = 4");
        helper.assertTrue(ChampionSummonPlan.simultaneousCap(AffixQuality.EPIC) == 5, "同时存活上限 超凡 = 5");
        helper.assertTrue(ChampionSummonPlan.simultaneousCap(AffixQuality.LEGENDARY) == 6, "同时存活上限 闪耀 = 6");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void actualSummonCountDeductsAlive(GameTestHelper helper) {
        // 闪耀: 每次 3, 上限 6。存活 0 -> min(3,6)=3; 存活 4 -> min(3,2)=2; 存活 5 -> min(3,1)=1; 存活 6 -> 0 (满);
        // 存活 10 (调试越上限) -> 0 (负余量钳 0)。
        helper.assertTrue(ChampionSummonPlan.actualSummonCount(AffixQuality.LEGENDARY, 0) == 3, "闪耀 存活0 -> 召 3");
        helper.assertTrue(ChampionSummonPlan.actualSummonCount(AffixQuality.LEGENDARY, 4) == 2, "闪耀 存活4 -> 召 2 (上限6)");
        helper.assertTrue(ChampionSummonPlan.actualSummonCount(AffixQuality.LEGENDARY, 5) == 1, "闪耀 存活5 -> 召 1");
        helper.assertTrue(ChampionSummonPlan.actualSummonCount(AffixQuality.LEGENDARY, 6) == 0, "闪耀 存活6 满 -> 召 0");
        helper.assertTrue(ChampionSummonPlan.actualSummonCount(AffixQuality.LEGENDARY, 10) == 0, "闪耀 存活超上限 -> 召 0");
        // 普通: 每次 1, 上限 2。存活 0->1; 存活 1->min(1,1)=1; 存活 2 满->0。
        helper.assertTrue(ChampionSummonPlan.actualSummonCount(AffixQuality.COMMON, 0) == 1, "普通 存活0 -> 召 1");
        helper.assertTrue(ChampionSummonPlan.actualSummonCount(AffixQuality.COMMON, 1) == 1, "普通 存活1 -> 召 1 (上限2)");
        helper.assertTrue(ChampionSummonPlan.actualSummonCount(AffixQuality.COMMON, 2) == 0, "普通 存活2 满 -> 召 0");
        // 超凡: 每次 3, 上限 5。存活 3 -> min(3,2)=2 (每次数被存活余量夹断)。
        helper.assertTrue(ChampionSummonPlan.actualSummonCount(AffixQuality.EPIC, 3) == 2, "超凡 存活3 -> 召 2 (余量2夹断每次3)");
        helper.assertTrue(ChampionSummonPlan.actualSummonCount(AffixQuality.EPIC, 5) == 0, "超凡 存活5 满 -> 召 0");
        helper.succeed();
    }

    // ============================================================
    // 召唤星级钳制 clamp(主人星-2, 1, 4)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void summonStarClamp(GameTestHelper helper) {
        helper.assertTrue(ChampionSummonPlan.summonStar(3) == 1, "主人3star -> 召1star (3-2=1)");
        helper.assertTrue(ChampionSummonPlan.summonStar(6) == 4, "主人6star -> 召4star (6-2=4)");
        helper.assertTrue(ChampionSummonPlan.summonStar(10) == 4, "主人10star -> 召4star (10-2=8 钳到 4)");
        // 边界: 主人4->2, 5->3, 7->5钳4; 下界 1->1 (1-2=-1 钳1), 2->1。
        helper.assertTrue(ChampionSummonPlan.summonStar(4) == 2, "主人4star -> 召2star");
        helper.assertTrue(ChampionSummonPlan.summonStar(5) == 3, "主人5star -> 召3star");
        helper.assertTrue(ChampionSummonPlan.summonStar(7) == 4, "主人7star -> 召4star (7-2=5 钳到 4)");
        helper.assertTrue(ChampionSummonPlan.summonStar(1) == 1, "主人1star -> 召1star (下界钳)");
        helper.assertTrue(ChampionSummonPlan.summonStar(2) == 1, "主人2star -> 召1star (下界钳)");
        helper.succeed();
    }

    // ============================================================
    // 词条过滤 (剥离技能 + 支援召唤本身, 防递归召唤/技能嵌套)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void filterStripsSkillsAndSummon(GameTestHelper helper) {
        // isSummonable 逐判: 非技能生存/战斗词条可召, 技能词条与支援召唤本身不可召。
        helper.assertTrue(ChampionSummonPlan.isSummonable(AffixDef.BURNING), "燃烧 (非技能) 可赋召唤物");
        helper.assertTrue(ChampionSummonPlan.isSummonable(AffixDef.SPRINT), "高速移动 (非技能) 可赋召唤物");
        helper.assertTrue(!ChampionSummonPlan.isSummonable(AffixDef.THUNDER), "天雷 (技能) 不可赋召唤物");
        helper.assertTrue(!ChampionSummonPlan.isSummonable(AffixDef.SUMMON_SUPPORT), "支援召唤本身不可赋 (防递归)");

        // 合成一组混入技能 (THUNDER) + 支援召唤 (SUMMON_SUPPORT) 的选择, 过滤后须只剩两条非技能词条。
        List<AffixSelection> mixed = new ArrayList<>();
        mixed.add(new AffixSelection(AffixDef.BURNING, AffixQuality.COMMON));
        mixed.add(new AffixSelection(AffixDef.THUNDER, AffixQuality.COMMON));
        mixed.add(new AffixSelection(AffixDef.SUMMON_SUPPORT, AffixQuality.COMMON));
        mixed.add(new AffixSelection(AffixDef.SPRINT, AffixQuality.COMMON));

        List<AffixSelection> kept = ChampionSummonPlan.retainSummonableAffixes(mixed);
        helper.assertTrue(kept.size() == 2, "过滤后剩 2 条 (剥离 THUNDER + SUMMON_SUPPORT)");
        for (AffixSelection sel : kept) {
            helper.assertTrue(!sel.affix().isSkill(), "过滤后无技能词条: " + sel.affix());
            helper.assertTrue(sel.affix() != AffixDef.SUMMON_SUPPORT, "过滤后无支援召唤: " + sel.affix());
        }
        helper.assertTrue(kept.contains(new AffixSelection(AffixDef.BURNING, AffixQuality.COMMON)), "保留燃烧");
        helper.assertTrue(kept.contains(new AffixSelection(AffixDef.SPRINT, AffixQuality.COMMON)), "保留高速移动");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void rolledSummonAffixesNeverSkillOrSummon(GameTestHelper helper) {
        // 召唤星级 1-4★ 的真实 roll 结果过滤后, 恒无技能词条与支援召唤 (多 seed 断言不变量)。
        int[] summonStars = {1, 2, 3, 4};
        for (int star : summonStars) {
            StarRank rank = StarRank.ofStar(star);
            for (long seed = 0; seed < 16; seed++) {
                RandomSource rng = RandomSource.create(seed * 131L + star);
                List<AffixSelection> filtered = ChampionSummonPlan.retainSummonableAffixes(AffixRoller.roll(rank, rng));
                for (AffixSelection sel : filtered) {
                    helper.assertTrue(!sel.affix().isSkill(),
                            "star " + star + " seed " + seed + " 召唤物过滤后仍含技能词条 " + sel.affix());
                    helper.assertTrue(sel.affix() != AffixDef.SUMMON_SUPPORT,
                            "star " + star + " seed " + seed + " 召唤物过滤后仍含支援召唤");
                }
            }
        }
        helper.succeed();
    }

    // ============================================================
    // summonedByAffix 经济闸标记 NBT 往返 (红线 8: 区块卸载重载后排除口径不得丢)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void summonedFlagSurvivesNbtRoundTrip(GameTestHelper helper) {
        // 对抗审查 major: 该标记是 掉落/经验/贡献/血条 四处经济闸的唯一依据, 序列化丢失 = 召唤物跨区块重载后
        // 重新参与结算 (spec 第十一章"经济印钞口"重开)。删 serializeNBT 的 NBT_SUMMONED 分支或 deserializeNBT
        // 的读取, 本断言必挂。
        MiningChampionData original = new MiningChampionData();
        original.promote(2, java.util.Map.of(AffixDef.SPRINT, AffixQuality.COMMON), 225.0D);
        helper.assertTrue(!original.isSummonedByAffix(), "promote 后默认非召唤物");
        original.markSummonedByAffix();
        helper.assertTrue(original.isSummonedByAffix(), "markSummonedByAffix 后为召唤物");

        MiningChampionData restored = new MiningChampionData();
        restored.deserializeNBT(original.serializeNBT());
        helper.assertTrue(restored.isSummonedByAffix(), "NBT 往返后召唤物标记保留 (经济闸不失效)");
        helper.assertTrue(restored.star() == 2 && restored.has(AffixDef.SPRINT),
                "往返后星级/词条同存 (标记不挤掉其它字段)");

        // 普通冠军往返: 不写键 (防全世界怪 NBT 膨胀), 读回恒 false。
        MiningChampionData plain = new MiningChampionData();
        plain.promote(3, java.util.Map.of(AffixDef.BURNING, AffixQuality.COMMON), 360.0D);
        helper.assertTrue(!plain.serializeNBT().contains("summoned_by_affix"),
                "普通冠军不写 summoned_by_affix 键");
        MiningChampionData plainRestored = new MiningChampionData();
        plainRestored.deserializeNBT(plain.serializeNBT());
        helper.assertTrue(!plainRestored.isSummonedByAffix(), "普通冠军往返后仍非召唤物");

        // 重新 promote 复位标记 (召唤物身份不跨盖章残留)。
        original.promote(4, java.util.Map.of(AffixDef.BURNING, AffixQuality.COMMON), 540.0D);
        helper.assertTrue(!original.isSummonedByAffix(), "重新 promote 复位召唤物标记");
        helper.succeed();
    }
}
