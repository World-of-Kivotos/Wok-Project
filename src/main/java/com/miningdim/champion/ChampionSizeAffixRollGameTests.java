package com.miningdim.champion;

import com.miningdim.core.MiningConstants;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.RandomSource;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * 体型词条 (巨大化/缩小化) 入 roll 白名单 + 缩小化强制机动伙伴原子配对 纯逻辑 GameTest
 * (ChampionStarAffix spec 7.1 体型词条 + 第八章互斥矩阵; Stage2 批2)。
 *
 * 核心回归: 生存池先于机动池 roll, 缩小化若不原子配对机动伙伴则被 {@link PointBudget} "须至少一条机动"
 * 硬校验永远拒绝 —— 删 {@code rollMiniaturizationPartner} 配对逻辑, 本类"缩小化可 roll 出"断言必挂。
 * 多 seed 扫描断言 roll 集合恒合法 (allocate 不抛) + 巨大化互斥机动 + 伙伴锁最低档。
 *
 * template = "empty", batch = "champion_size_roll"。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class ChampionSizeAffixRollGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "champion_size_roll";

    /** 多 seed 扫描量: 体型词条最低 3★, 3-10★ x 300 seed 覆盖存在性与不变量 (RandomSource.create 确定性)。 */
    private static final int SEEDS = 300;

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void whitelistContainsSizeAffixes(GameTestHelper helper) {
        helper.assertTrue(AffixRoller.IMPLEMENTED_AFFIXES.contains(AffixDef.GIGANTISM),
                "巨大化已入 roll 白名单 (批2)");
        helper.assertTrue(AffixRoller.IMPLEMENTED_AFFIXES.contains(AffixDef.MINIATURIZATION),
                "缩小化已入 roll 白名单 (批2)");
        helper.assertTrue(AffixRoller.IMPLEMENTED_AFFIXES.contains(AffixDef.OVERDRIVE),
                "超速移动已入 roll 白名单 (批2)");
        helper.assertTrue(AffixRoller.IMPLEMENTED_AFFIXES.size() == 29,
                "白名单 24 -> 29 (批4 波1: 分跳x2/混沌/闪光/战术传送; 总量断言防漏排/误删)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void sizeAffixesActuallyRollableAndSetsStayLegal(GameTestHelper helper) {
        int gigantismSeen = 0;
        int miniaturizationSeen = 0;
        for (int star = 3; star <= 10; star++) {
            StarRank rank = StarRank.ofStar(star);
            for (int seed = 0; seed < SEEDS; seed++) {
                List<AffixSelection> rolled = AffixRoller.roll(rank, RandomSource.create(seed));
                // 终校验恒过 (roll 只产合法集合; 非法组合在此抛 = 配对/预检回归)。
                PointBudget.allocate(rank, rolled);
                for (AffixSelection sel : rolled) {
                    if (sel.affix() == AffixDef.GIGANTISM) {
                        gigantismSeen++;
                    }
                    if (sel.affix() == AffixDef.MINIATURIZATION) {
                        miniaturizationSeen++;
                    }
                }
            }
        }
        // 存在性: 白名单+配对生效后两词条须真能被自然 roll 出 (删配对逻辑 -> 缩小化恒 0 必挂)。
        helper.assertTrue(gigantismSeen > 0, "多 seed 扫描巨大化至少 roll 出一次, 实得 " + gigantismSeen);
        helper.assertTrue(miniaturizationSeen > 0, "多 seed 扫描缩小化至少 roll 出一次, 实得 " + miniaturizationSeen);
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void miniaturizationAlwaysPairedWithLowestTierMobility(GameTestHelper helper) {
        for (int star = 3; star <= 10; star++) {
            StarRank rank = StarRank.ofStar(star);
            for (int seed = 0; seed < SEEDS; seed++) {
                List<AffixSelection> rolled = AffixRoller.roll(rank, RandomSource.create(seed));
                boolean hasMini = rolled.stream().anyMatch(sel -> sel.affix() == AffixDef.MINIATURIZATION);
                if (!hasMini) {
                    continue;
                }
                // 批4 波1 起 BLINK/TACTICAL_BLINK (TELEPORT_FAMILY) 入白名单, 机动池可在强制伙伴之外再贪心
                // 追加传送词条 (与 MOVE_SPEED 不同互斥族, 合法共存且档位随机) —— 故断言从"全部机动皆最低档"
                // 放宽为"至少存在一条最低档机动" (= 强制伙伴; spec 只锁伙伴档位, 不禁额外自然机动)。
                boolean hasLowestTierMobility = false;
                for (AffixSelection sel : rolled) {
                    if (sel.affix().pool() != AffixPool.MOBILITY) {
                        continue;
                    }
                    if (sel.quality() == sel.affix().minUsableQuality()) {
                        hasLowestTierMobility = true;
                        break;
                    }
                }
                helper.assertTrue(hasLowestTierMobility,
                        "缩小化 roll 必含最低档机动伙伴 (star " + star + " seed " + seed + ")");
            }
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void gigantismNeverRollsWithMobility(GameTestHelper helper) {
        for (int star = 3; star <= 10; star++) {
            StarRank rank = StarRank.ofStar(star);
            for (int seed = 0; seed < SEEDS; seed++) {
                List<AffixSelection> rolled = AffixRoller.roll(rank, RandomSource.create(seed));
                boolean hasGigantism = rolled.stream().anyMatch(sel -> sel.affix() == AffixDef.GIGANTISM);
                if (!hasGigantism) {
                    continue;
                }
                for (AffixSelection sel : rolled) {
                    helper.assertTrue(sel.affix().pool() != AffixPool.MOBILITY,
                            "巨大化互斥全部机动 (spec 第八章): " + sel + " (star " + star + " seed " + seed + ")");
                }
            }
        }
        helper.succeed();
    }
}
