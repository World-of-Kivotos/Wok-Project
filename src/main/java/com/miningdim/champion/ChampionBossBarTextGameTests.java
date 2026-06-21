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
}
