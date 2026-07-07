package com.miningdim.champion;

import com.miningdim.core.MiningConstants;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.BossEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * 精英怪 BOSS 血条文本/样式纯逻辑 GameTest ({@link ChampionBossBarText}: 标题拼装 + 星级->颜色/分段 + 血量进度钳)。
 * 全为具体值断言, 纯函数直驱, 无需起世界 (与 ChampionGameTests 同 batch="champion")。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class ChampionBossBarTextGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "champion";

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void titleContainsNameStarsAndAffixes(GameTestHelper helper) {
        String s = ChampionBossBarText.title(
                Component.literal("僵尸"), 3,
                List.of(Component.literal("复合装甲"), Component.literal("天雷"))).getString();
        helper.assertTrue(s.contains("僵尸"), "title contains champion name");
        helper.assertTrue(s.contains("★★★"), "title shows 3 stars for tier 3");
        helper.assertTrue(s.contains("复合装甲") && s.contains("天雷"), "title lists both affix names");
        // 无词条: 仅 名字 + 星级, 不带词条分隔符。
        String none = ChampionBossBarText.title(Component.literal("僵尸"), 2, List.of()).getString();
        helper.assertTrue(none.contains("★★") && !none.contains("·"), "no affixes -> name+stars only, no separator");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void starsCompactAboveFive(GameTestHelper helper) {
        helper.assertTrue(ChampionBossBarText.stars(3).getString().equals("★★★"), "tier 3 = ★★★");
        helper.assertTrue(ChampionBossBarText.stars(5).getString().equals("★★★★★"), "tier 5 = 5 glyphs");
        helper.assertTrue(ChampionBossBarText.stars(10).getString().equals("★x10"), "tier 10 = compact ★x10");
        helper.assertTrue(ChampionBossBarText.stars(0).getString().isEmpty(), "tier 0 = no stars");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void colorAndOverlayEscalateWithTier(GameTestHelper helper) {
        helper.assertTrue(ChampionBossBarText.colorForTier(1) == BossEvent.BossBarColor.WHITE, "1★ white");
        helper.assertTrue(ChampionBossBarText.colorForTier(3) == BossEvent.BossBarColor.GREEN, "3★ green");
        helper.assertTrue(ChampionBossBarText.colorForTier(5) == BossEvent.BossBarColor.YELLOW, "5★ yellow");
        helper.assertTrue(ChampionBossBarText.colorForTier(7) == BossEvent.BossBarColor.RED, "7★ red");
        helper.assertTrue(ChampionBossBarText.colorForTier(9) == BossEvent.BossBarColor.PURPLE, "9★ purple");
        helper.assertTrue(ChampionBossBarText.overlayForTier(5) == BossEvent.BossBarOverlay.PROGRESS, "5★ unsegmented");
        helper.assertTrue(ChampionBossBarText.overlayForTier(6) == BossEvent.BossBarOverlay.NOTCHED_6, "6★ 6-seg");
        helper.assertTrue(ChampionBossBarText.overlayForTier(8) == BossEvent.BossBarOverlay.NOTCHED_10, "8★ 10-seg");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void progressClampsToUnitRange(GameTestHelper helper) {
        helper.assertTrue(Math.abs(ChampionBossBarText.progress(50.0D, 100.0D) - 0.5F) < 1e-6F, "half hp = 0.5");
        helper.assertTrue(ChampionBossBarText.progress(150.0D, 100.0D) == 1.0F, "overfull clamps to 1");
        helper.assertTrue(ChampionBossBarText.progress(-5.0D, 100.0D) == 0.0F, "negative clamps to 0");
        helper.assertTrue(ChampionBossBarText.progress(10.0D, 0.0D) == 0.0F, "zero max -> 0 (no div-by-zero)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void nearestBossBarColorMapsRgbToDiscreteColor(GameTestHelper helper) {
        helper.assertTrue(ChampionBossBarText.nearestBossBarColor(0xFF0000) == BossEvent.BossBarColor.RED, "red rgb -> RED");
        helper.assertTrue(ChampionBossBarText.nearestBossBarColor(0x00FF00) == BossEvent.BossBarColor.GREEN, "green rgb -> GREEN");
        helper.assertTrue(ChampionBossBarText.nearestBossBarColor(0xFFFFFF) == BossEvent.BossBarColor.WHITE, "white rgb -> WHITE");
        helper.assertTrue(ChampionBossBarText.nearestBossBarColor(0xAA00FF) == BossEvent.BossBarColor.PURPLE, "purple rgb -> PURPLE");
        helper.assertTrue(ChampionBossBarText.nearestBossBarColor(0xFFFF00) == BossEvent.BossBarColor.YELLOW, "yellow rgb -> YELLOW");
        helper.succeed();
    }

    // ============================================================
    // BA 式多管血条 (批2 用户定向): 每管容量表 / 管数 / 管内进度 / 逐管换色 / 尾缀
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void layerCapacityEqualsBareBaseAtLowStars(GameTestHelper helper) {
        // 1-5★ 每管 = 该星裸怪基础血 (管数语义 = 相当于几只本星裸怪): 裸怪恰 1 管, 3★ 巨大化 COMMON 破管。
        for (int star = 1; star <= 5; star++) {
            double cap = ChampionBossBarText.layerCapacityFor(star);
            double bare = StarRank.ofStar(star).baseEffectiveHp();
            helper.assertTrue(cap == bare, star + "★ 每管容量 = 裸怪基础血 " + bare);
            helper.assertTrue(ChampionBossBarText.layersLeft(bare, cap) == 1, star + "★ 裸怪恰 1 管");
        }
        // 3★ 巨大化 COMMON 468 血 -> 2 管 (用户拍板 "3★ 有巨大化就有管")。
        helper.assertTrue(ChampionBossBarText.layersLeft(468.0D, ChampionBossBarText.layerCapacityFor(3)) == 2,
                "3★ 巨大化 COMMON 468 = 2 管");
        // 高星裸怪排面: 6★ 2 管 / 7★ 3 管 / 8★ 6 管 / 9★ 7 管 / 10★ 10 管。
        helper.assertTrue(ChampionBossBarText.layersLeft(2_700.0D, ChampionBossBarText.layerCapacityFor(6)) == 2, "6★ 裸怪 2 管");
        helper.assertTrue(ChampionBossBarText.layersLeft(6_000.0D, ChampionBossBarText.layerCapacityFor(7)) == 3, "7★ 裸怪 3 管");
        helper.assertTrue(ChampionBossBarText.layersLeft(27_000.0D, ChampionBossBarText.layerCapacityFor(8)) == 6, "8★ 裸怪 6 管");
        helper.assertTrue(ChampionBossBarText.layersLeft(45_000.0D, ChampionBossBarText.layerCapacityFor(9)) == 7, "9★ 裸怪 7 管");
        helper.assertTrue(ChampionBossBarText.layersLeft(73_000.0D, ChampionBossBarText.layerCapacityFor(10)) == 10, "10★ 裸怪 10 管");
        // 10★ 巨大化闪耀 204,400 -> 28 管 (x27 排面)。
        helper.assertTrue(ChampionBossBarText.layersLeft(204_400.0D, ChampionBossBarText.layerCapacityFor(10)) == 28,
                "10★ 巨大化闪耀 204400 = 28 管");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void layerProgressExactAtBoundaries(GameTestHelper helper) {
        double cap = 360.0D; // 3★ 容量。
        // 468 血 (3★ 巨大化 COMMON): 第 2 管, 管内 108/360 = 0.3。
        helper.assertTrue(ChampionBossBarText.layersLeft(468.0D, cap) == 2, "468/360 = 2 管");
        helper.assertTrue(Math.abs(ChampionBossBarText.layerProgress(468.0D, cap) - 0.3F) < 1e-6F,
                "第 2 管内进度 = 108/360 = 0.3");
        // 恰在管界 360: 算 1 管且当前管满 (打掉最后一滴才破管)。
        helper.assertTrue(ChampionBossBarText.layersLeft(360.0D, cap) == 1, "恰 360 = 1 管");
        helper.assertTrue(ChampionBossBarText.layerProgress(360.0D, cap) == 1.0F, "管界进度恰 1.0");
        // 破管后一滴血: 359.9 -> 1 管, 进度 359.9/360。
        helper.assertTrue(Math.abs(ChampionBossBarText.layerProgress(359.9D, cap) - (float) (359.9D / 360.0D)) < 1e-6F,
                "破管后按管内剩余算");
        // 死 -> 0 管 0 进度。
        helper.assertTrue(ChampionBossBarText.layersLeft(0.0D, cap) == 0, "死 = 0 管");
        helper.assertTrue(ChampionBossBarText.layerProgress(0.0D, cap) == 0.0F, "死进度 = 0");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void layerColorAnchorsFinalBarRedAndCycles(GameTestHelper helper) {
        // 锚定尾管: 最后一管恒红, 倒数第二恒黄, 逐管上溯 7 色循环 (相邻必不同)。
        helper.assertTrue(ChampionBossBarText.layerColor(1) == BossEvent.BossBarColor.RED, "最后一管 红");
        helper.assertTrue(ChampionBossBarText.layerColor(2) == BossEvent.BossBarColor.YELLOW, "倒数第 2 管 黄");
        helper.assertTrue(ChampionBossBarText.layerColor(3) == BossEvent.BossBarColor.GREEN, "倒数第 3 管 绿");
        helper.assertTrue(ChampionBossBarText.layerColor(7) == BossEvent.BossBarColor.WHITE, "倒数第 7 管 白");
        helper.assertTrue(ChampionBossBarText.layerColor(8) == BossEvent.BossBarColor.RED, "第 8 管 7 色循环回红");
        // 相邻管换色 (打破一管必换色): 1..14 逐对比较。
        for (int n = 1; n < 14; n++) {
            helper.assertTrue(ChampionBossBarText.layerColor(n) != ChampionBossBarText.layerColor(n + 1),
                    "相邻管 " + n + "/" + (n + 1) + " 必不同色");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void layerSuffixShowsRemainingBars(GameTestHelper helper) {
        // BA 式尾缀 = 当前管之后还剩几管; 最后一管/单管不显。
        helper.assertTrue(ChampionBossBarText.layerSuffix(28).equals(" x27"), "28 管显 x27");
        helper.assertTrue(ChampionBossBarText.layerSuffix(2).equals(" x1"), "2 管显 x1");
        helper.assertTrue(ChampionBossBarText.layerSuffix(1).isEmpty(), "最后一管无尾缀");
        helper.assertTrue(ChampionBossBarText.layerSuffix(0).isEmpty(), "死无尾缀");
        helper.succeed();
    }
}
