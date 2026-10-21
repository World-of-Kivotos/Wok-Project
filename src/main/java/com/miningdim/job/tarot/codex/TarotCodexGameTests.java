package com.miningdim.job.tarot.codex;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.tarot.TarotArcana;
import com.miningdim.job.tarot.TarotQuality;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 塔罗图鉴纯逻辑 GameTest：位索引、去重、三条里程碑边界（22 / 闪耀 / 220）、落盘往返。
 *
 * <p>直接用裸 {@link TarotCodexSavedData}（不落盘、不建服务端），断言全部落在位运算与边界上：
 * 改回“按持有判定”或把索引写错一位，这些用例必挂。</p>
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class TarotCodexGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "tarotcodex";

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void formIndexCoversEveryFormOnce(GameTestHelper helper) {
        int expected = TarotArcana.COUNT * TarotQuality.values().length * 2;
        helper.assertTrue(TarotCodexSavedData.FORMS == expected, "FORMS must be 22 x 5 x 2 = 220");
        Set<Integer> seen = new HashSet<>();
        for (int cardId = 0; cardId < TarotArcana.COUNT; cardId++) {
            for (TarotQuality quality : TarotQuality.values()) {
                for (boolean upright : new boolean[]{true, false}) {
                    int bit = TarotCodexSavedData.index(cardId, quality, upright);
                    helper.assertTrue(bit >= 0 && bit < TarotCodexSavedData.FORMS,
                            "index out of range: " + bit);
                    helper.assertTrue(seen.add(bit), "duplicate index for cardId=" + cardId
                            + " quality=" + quality + " upright=" + upright);
                }
            }
        }
        helper.assertTrue(seen.size() == TarotCodexSavedData.FORMS,
                "index must cover all 220 forms, got " + seen.size());
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void duplicateRecordOnlyCountsOnce(GameTestHelper helper) {
        TarotCodexSavedData data = new TarotCodexSavedData();
        UUID player = UUID.randomUUID();
        helper.assertTrue(data.record(player, 3, TarotQuality.SSR, true), "first record must light the form");
        helper.assertTrue(!data.record(player, 3, TarotQuality.SSR, true), "second record must be a no-op");
        helper.assertTrue(data.count(player) == 1, "duplicate record must not grow the codex");
        helper.assertTrue(data.record(player, 3, TarotQuality.SSR, false), "reversed is a different form");
        helper.assertTrue(data.count(player) == 2, "upright and reversed are distinct forms");
        helper.assertTrue(data.record(player, 3, TarotQuality.UR, true), "quality is part of the form");
        helper.assertTrue(data.count(player) == 3, "three distinct forms expected");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void outOfRangeInputIsIgnored(GameTestHelper helper) {
        TarotCodexSavedData data = new TarotCodexSavedData();
        UUID player = UUID.randomUUID();
        helper.assertTrue(!data.record(player, -1, TarotQuality.R, true), "negative cardId must be ignored");
        helper.assertTrue(!data.record(player, TarotArcana.COUNT, TarotQuality.R, true), "cardId 22 must be ignored");
        helper.assertTrue(!data.record(null, 0, TarotQuality.R, true), "null uuid must be ignored");
        helper.assertTrue(data.count(player) == 0, "ignored records must not light any form");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void stardustMilestoneAtTwentyTwoArcana(GameTestHelper helper) {
        TarotCodexSavedData data = new TarotCodexSavedData();
        UUID player = UUID.randomUUID();
        for (int cardId = 0; cardId < TarotArcana.COUNT - 1; cardId++) {
            data.record(player, cardId, TarotQuality.R, cardId % 2 == 0);
        }
        helper.assertTrue(data.arcana(player) == 21, "21 arcana expected before the last card");
        helper.assertTrue(!data.hasAllArcana(player), "21/22 must not unlock stardust");
        data.record(player, TarotArcana.COUNT - 1, TarotQuality.R, true);
        helper.assertTrue(data.hasAllArcana(player), "22/22 must unlock stardust");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void shiningMilestoneNeedsShinyQuality(GameTestHelper helper) {
        TarotCodexSavedData data = new TarotCodexSavedData();
        UUID player = UUID.randomUUID();
        for (int cardId = 0; cardId < TarotArcana.COUNT; cardId++) {
            data.record(player, cardId, TarotQuality.UR, true);
        }
        helper.assertTrue(!data.hasShiny(player), "UR only must not unlock shining");
        helper.assertTrue(data.shinyForms(player) == 0, "no shiny form may be lit");
        data.record(player, 7, TarotQuality.SHINY, false);
        helper.assertTrue(data.hasShiny(player), "one shiny form must unlock shining");
        helper.assertTrue(data.shinyForms(player) == 1, "exactly one shiny form expected");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void starmasterMilestoneAtAllForms(GameTestHelper helper) {
        TarotCodexSavedData data = new TarotCodexSavedData();
        UUID player = UUID.randomUUID();
        for (int cardId = 0; cardId < TarotArcana.COUNT; cardId++) {
            for (TarotQuality quality : TarotQuality.values()) {
                for (boolean upright : new boolean[]{true, false}) {
                    if (data.count(player) >= TarotCodexSavedData.FORMS - 1) {
                        continue;
                    }
                    data.record(player, cardId, quality, upright);
                }
            }
        }
        helper.assertTrue(data.count(player) == TarotCodexSavedData.FORMS - 1,
                "219 forms expected before the last one, got " + data.count(player));
        // 找一个还空着的形态补最后一位（上面循环故意留了最后一个）。
        outer:
        for (int cardId = 0; cardId < TarotArcana.COUNT; cardId++) {
            for (TarotQuality quality : TarotQuality.values()) {
                for (boolean upright : new boolean[]{true, false}) {
                    if (!data.has(player, cardId, quality, upright)) {
                        data.record(player, cardId, quality, upright);
                        break outer;
                    }
                }
            }
        }
        helper.assertTrue(data.count(player) == TarotCodexSavedData.FORMS,
                "220 forms must unlock starmaster, got " + data.count(player));
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void saveLoadRoundTripKeepsBits(GameTestHelper helper) {
        TarotCodexSavedData saved = new TarotCodexSavedData();
        UUID player = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        saved.record(player, 0, TarotQuality.SHINY, false);
        saved.record(player, 21, TarotQuality.UR, true);
        saved.record(other, 5, TarotQuality.R, true);

        CompoundTag tag = saved.save(new CompoundTag());
        TarotCodexSavedData loaded = TarotCodexSavedData.load(tag);

        helper.assertTrue(loaded.count(player) == 2, "player bits must survive a save/load round trip");
        helper.assertTrue(loaded.has(player, 0, TarotQuality.SHINY, false), "reversed shiny bit lost");
        helper.assertTrue(loaded.has(player, 21, TarotQuality.UR, true), "upright UR bit lost");
        helper.assertTrue(!loaded.has(player, 21, TarotQuality.UR, false), "unlit sibling form must stay empty");
        helper.assertTrue(loaded.count(other) == 1, "second player must survive independently");
        helper.assertTrue(loaded.shinyForms(player) == 1, "shiny count must survive the round trip");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void emptyCodexIsAllZero(GameTestHelper helper) {
        TarotCodexSavedData data = new TarotCodexSavedData();
        UUID player = UUID.randomUUID();
        helper.assertTrue(data.count(player) == 0, "empty codex count must be 0");
        helper.assertTrue(data.arcana(player) == 0, "empty codex arcana must be 0");
        helper.assertTrue(!data.hasShiny(player), "empty codex has no shiny");
        helper.assertTrue(!data.hasAllArcana(player), "empty codex must not unlock stardust");
        helper.assertTrue(data.count(UUID.randomUUID()) == 0, "unknown player must read as empty");
        helper.succeed();
    }
}
