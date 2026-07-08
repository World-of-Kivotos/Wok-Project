package com.miningdim.champion;

import com.miningdim.core.MiningConstants;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.RandomSource;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * 体型词条实体白名单 ({@link SizeAffixEligibility}) + roller 装配期资格剔除 ({@link AffixRoller#roll(StarRank,
 * RandomSource, boolean)}) 纯逻辑 GameTest (ChampionStarAffix spec 9A.4 体型词条实体白名单硬校验; 批4 波0)。
 *
 * 核心回归:
 *  - 白名单存在性: 13 条规则碰撞箱人形实体逐一合格, 异形碰撞箱 (蜘蛛/史莱姆/末影龙/苦力怕/末影人) + null 不合格
 *    (删任一白名单条目 -> 对应断言必挂; 误加异形 -> 对应负向断言必挂)。
 *  - roller 剔除: 非白名单上下文重复 roll 永不出 SIZE 族, 而同种子的合格上下文能 roll 出 (删候选过滤的 SIZE 门
 *    -> 非白名单也出体型, inelSize 从 0 跳到 &gt;0 必挂)。
 *  - 同池消费: 合格上下文本会出体型的种子, 换成非白名单上下文后腾出的生存池点数改抽同池 (survival spent &gt; 0),
 *    预算不跨池流失, 缩小化强制机动伙伴天然不触发 (无残留半配对, 由 allocate 恒过间接保证)。
 *
 * template = "empty", batch = "champion_size_eligibility"。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class SizeAffixEligibilityGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "champion_size_eligibility";

    /** 多 seed 扫描量: 与 ChampionSizeAffixRollGameTests 一致, 保证合格上下文必自然 roll 出体型 (确定性 seed)。 */
    private static final int SEEDS = 300;

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void whitelistedHumanoidEntitiesAreEligible(GameTestHelper helper) {
        // 13 条主线裁定白名单逐一断言 (删任一条目 -> 对应断言挂; 逐字对齐 spec 9A.4 裁定表)。
        helper.assertTrue(SizeAffixEligibility.isEligible("minecraft:zombie"), "僵尸合格");
        helper.assertTrue(SizeAffixEligibility.isEligible("minecraft:husk"), "尸壳合格");
        helper.assertTrue(SizeAffixEligibility.isEligible("minecraft:drowned"), "溺尸合格");
        helper.assertTrue(SizeAffixEligibility.isEligible("minecraft:zombified_piglin"), "僵尸猪灵合格");
        helper.assertTrue(SizeAffixEligibility.isEligible("minecraft:skeleton"), "骷髅合格");
        helper.assertTrue(SizeAffixEligibility.isEligible("minecraft:stray"), "流浪者合格");
        helper.assertTrue(SizeAffixEligibility.isEligible("minecraft:wither_skeleton"), "凋灵骷髅合格");
        helper.assertTrue(SizeAffixEligibility.isEligible("minecraft:vindicator"), "卫道士合格");
        helper.assertTrue(SizeAffixEligibility.isEligible("minecraft:pillager"), "掠夺者合格");
        helper.assertTrue(SizeAffixEligibility.isEligible("minecraft:evoker"), "唤魔者合格");
        helper.assertTrue(SizeAffixEligibility.isEligible("minecraft:witch"), "女巫合格");
        helper.assertTrue(SizeAffixEligibility.isEligible("minecraft:piglin"), "猪灵合格");
        helper.assertTrue(SizeAffixEligibility.isEligible("minecraft:piglin_brute"), "猪灵蛮兵合格");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void nonHumanoidHitboxEntitiesAreIneligible(GameTestHelper helper) {
        // 异形碰撞箱 (spec 9A.4 明列禁止): 蜘蛛/史莱姆/末影龙 + 苦力怕/末影人 —— 缩放会穿地/无法命中/悬浮。
        helper.assertFalse(SizeAffixEligibility.isEligible("minecraft:spider"), "蜘蛛不合格 (异形碰撞箱)");
        helper.assertFalse(SizeAffixEligibility.isEligible("minecraft:slime"), "史莱姆不合格 (异形碰撞箱)");
        helper.assertFalse(SizeAffixEligibility.isEligible("minecraft:enderman"), "末影人不合格 (瘦高碰撞箱)");
        helper.assertFalse(SizeAffixEligibility.isEligible("minecraft:creeper"), "苦力怕不合格");
        helper.assertFalse(SizeAffixEligibility.isEligible("minecraft:ender_dragon"), "末影龙不合格 (BOSS 异形)");
        // 装配期反查不到注册 id 的兜底: null 按不合格 (宁可不给体型也不崩)。
        helper.assertFalse(SizeAffixEligibility.isEligible(null), "null id 兜底不合格");
        // 未知/拼错 id 不合格 (白名单语义, 非黑名单)。
        helper.assertFalse(SizeAffixEligibility.isEligible("minecraft:zombi"), "拼错 id 不合格");
        helper.assertFalse(SizeAffixEligibility.isEligible("othermod:custom_humanoid"), "未登记外部实体不合格");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void ineligibleContextNeverRollsSizeYetEligibleDoes(GameTestHelper helper) {
        int eligibleGigantism = 0;
        int eligibleMiniaturization = 0;
        for (int star = 3; star <= 10; star++) {
            StarRank rank = StarRank.ofStar(star);
            for (int seed = 0; seed < SEEDS; seed++) {
                List<AffixSelection> eligible = AffixRoller.roll(rank, RandomSource.create(seed), true);
                List<AffixSelection> ineligible = AffixRoller.roll(rank, RandomSource.create(seed), false);
                // 非白名单上下文的集合仍必合法 (allocate 不抛; SIZE 剔除后无残留半配对 -> 缩小化强制机动约束不触发)。
                PointBudget.allocate(rank, ineligible);
                for (AffixSelection sel : eligible) {
                    if (sel.affix() == AffixDef.GIGANTISM) {
                        eligibleGigantism++;
                    }
                    if (sel.affix() == AffixDef.MINIATURIZATION) {
                        eligibleMiniaturization++;
                    }
                }
                for (AffixSelection sel : ineligible) {
                    // 每只非白名单怪逐条断言无 SIZE 族: 删候选 SIZE 门后非白名单上下文 == 合格上下文, 而合格上下文
                    // 在这些 star/seed 上确会出体型 (下方 eligible>0 基线证明), 故某 seed 本断言必挂。
                    helper.assertTrue(!isSize(sel.affix()),
                            "非白名单上下文不得出现体型词条: " + sel + " (star " + star + " seed " + seed + ")");
                }
            }
        }
        // 合格上下文能 roll 出体型 (存在性基线 = 剔除逻辑的对照组: 证明这些 star/seed 本会出体型, 剔除断言才非真空)。
        helper.assertTrue(eligibleGigantism > 0, "合格上下文巨大化至少 roll 出一次, 实得 " + eligibleGigantism);
        helper.assertTrue(eligibleMiniaturization > 0,
                "合格上下文缩小化至少 roll 出一次, 实得 " + eligibleMiniaturization);
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void ineligibleRedirectsFreedBudgetWithinSurvivalPool(GameTestHelper helper) {
        int comparedSeeds = 0;
        for (int star = 3; star <= 10; star++) {
            StarRank rank = StarRank.ofStar(star);
            for (int seed = 0; seed < SEEDS; seed++) {
                List<AffixSelection> eligible = AffixRoller.roll(rank, RandomSource.create(seed), true);
                boolean eligibleHasSize = false;
                for (AffixSelection sel : eligible) {
                    if (isSize(sel.affix())) {
                        eligibleHasSize = true;
                        break;
                    }
                }
                // 只在"合格上下文本会 roll 出体型"的种子上对比, 才谈得上体型点数被腾出改抽。
                if (!eligibleHasSize) {
                    continue;
                }
                comparedSeeds++;
                List<AffixSelection> ineligible = AffixRoller.roll(rank, RandomSource.create(seed), false);
                for (AffixSelection sel : ineligible) {
                    helper.assertTrue(!isSize(sel.affix()),
                            "剔除后同种子非白名单怪仍含体型词条: " + sel + " (star " + star + " seed " + seed + ")");
                }
                // 体型让出的生存池点数改抽同池 (spent > 0): 预算留在生存池, 未跨池流失/未整池作废。
                int survivalSpent = survivalSpent(ineligible);
                helper.assertTrue(survivalSpent > 0,
                        "剔除体型后生存池点数须改抽同池 (spent>0), star " + star + " seed " + seed + " 实得 " + survivalSpent);
            }
        }
        // 对比非空: 至少一个种子在合格上下文本会 roll 出体型 (防本用例真空通过)。
        helper.assertTrue(comparedSeeds > 0, "至少一个种子合格上下文 roll 出体型供对比, 实得 " + comparedSeeds);
        helper.succeed();
    }

    /** 是否 SIZE 互斥族词条 (巨大化/缩小化)。 */
    private static boolean isSize(AffixDef affix) {
        return affix.mutexFlag() == AffixDef.MutexFlag.SIZE;
    }

    /** 一组选择在生存池的总花费点数 (= 各生存池词条 costAt 之和)。 */
    private static int survivalSpent(List<AffixSelection> selections) {
        int spent = 0;
        for (AffixSelection sel : selections) {
            if (sel.affix().pool() == AffixPool.SURVIVAL) {
                spent += sel.cost();
            }
        }
        return spent;
    }
}
