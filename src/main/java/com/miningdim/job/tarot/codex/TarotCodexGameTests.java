package com.miningdim.job.tarot.codex;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.tarot.TarotCardItem;
import com.miningdim.job.tarot.TarotQuality;
import com.miningdim.job.tarot.TarotRegistry;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * 塔罗图鉴 GameTest：记录去重、落盘往返、损坏记录拒绝与登录补扫的绑定资格。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class TarotCodexGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "tarotcodex";

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void loginScanOnlyRecordsCardsBoundToPlayer(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockSurvivalServerPlayerWithChannel(helper);
        try {
            // 丢弃转手的牌和无主直给牌，都能由生存玩家带着重新登录。
            ItemStack foreign = TarotCardItem.create(TarotRegistry.TAROT_CARD.get(), 3,
                    TarotQuality.SHINY, true, UUID.randomUUID());
            ItemStack unbound = foreign.copy();
            unbound.getOrCreateTag().remove("OwnerUUID");
            player.getInventory().items.set(0, foreign);
            player.getInventory().offhand.set(0, unbound);
            TarotCodex.scan(player);
            helper.assertTrue(TarotCodex.summary(player).count() == 0,
                    "foreign and unbound cards must not grant collection progress");

            player.getInventory().offhand.set(0, TarotCardItem.create(TarotRegistry.TAROT_CARD.get(), 3,
                    TarotQuality.SHINY, true, player.getUUID()));
            TarotCodex.scan(player);
            helper.assertTrue(TarotCodex.summary(player).count() == 1,
                    "the player's own bound card must still be backfilled");
        } finally {
            MockGameTestPlayers.logout(player);
        }
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
    public static void malformedSavedRowsFailWithEntryContext(GameTestHelper helper) {
        CompoundTag missingUuid = new CompoundTag();
        missingUuid.putLongArray("Bits", new long[4]);
        assertMalformedRow(helper, missingUuid, "index 0", "invalid Uuid");

        CompoundTag wrongWordCount = new CompoundTag();
        wrongWordCount.putUUID("Uuid", UUID.randomUUID());
        wrongWordCount.putLongArray("Bits", new long[3]);
        assertMalformedRow(helper, wrongWordCount, "Uuid", "Bits must contain 4 longs, found 3");

        CompoundTag outsideFormRange = new CompoundTag();
        outsideFormRange.putUUID("Uuid", UUID.randomUUID());
        outsideFormRange.putLongArray("Bits", new long[]{0L, 0L, 0L, 1L << 28});
        assertMalformedRow(helper, outsideFormRange, "Uuid", "Bits contain forms beyond 220");

        helper.succeed();
    }

    private static void assertMalformedRow(GameTestHelper helper, CompoundTag row, String context,
                                           String detail) {
        ListTag rows = new ListTag();
        rows.add(row);
        CompoundTag saved = new CompoundTag();
        saved.put("Players", rows);
        try {
            TarotCodexSavedData.load(saved);
        } catch (IllegalStateException malformed) {
            helper.assertTrue(malformed.getMessage().contains(context)
                            && malformed.getMessage().contains(detail),
                    "malformed row error must include context and cause: " + malformed.getMessage());
            return;
        }
        helper.assertTrue(false, "malformed row must not be skipped");
    }

}
