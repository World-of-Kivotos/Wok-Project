package com.miningdim.social;

import com.miningdim.achievement.AchievementServices;
import com.miningdim.achievement.reward.AchievementReward;
import com.miningdim.achievement.trigger.AchievementBackfill;
import com.miningdim.achievement.trigger.TarotCodexTrigger.Milestone;
import com.miningdim.core.MiningConstants;
import com.miningdim.job.tarot.TarotArcana;
import com.miningdim.job.tarot.TarotCardItem;
import com.miningdim.job.tarot.TarotConfig;
import com.miningdim.job.tarot.TarotQuality;
import com.miningdim.job.tarot.TarotRegistry;
import com.miningdim.job.tarot.codex.TarotCodex;
import com.miningdim.job.tarot.codex.TarotCodexSavedData;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 社交仓库纯逻辑 GameTest。所有断言都删核心逻辑必挂：好友关系双向写入、动态与私聊的落盘、
 * 好友校验、图片长度边界。测试使用临时目录，不触碰真实存档。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class SocialGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "social";

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void friendRequestAcceptAndRemove(GameTestHelper helper) {
        withStore(helper, (store, dir) -> {
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            store.touch(a, "Alpha");
            store.touch(b, "Beta");

            boolean accepted = store.requestFriend(a, "Alpha", b, "Beta");
            helper.assertTrue(!accepted, "first request must wait for acceptance");
            helper.assertTrue(store.outgoingIds(a).contains(b), "requester must see outgoing request");
            helper.assertTrue(store.incomingIds(b).contains(a), "target must see incoming request");

            store.acceptFriend(a, b);
            helper.assertTrue(store.areFriends(a, b), "friendship must be visible from requester side");
            helper.assertTrue(store.areFriends(b, a), "friendship must be mirrored for target");
            helper.assertTrue(store.incomingIds(b).isEmpty(), "incoming request must be consumed on accept");
            helper.assertTrue(store.outgoingIds(a).isEmpty(), "outgoing request must be consumed on accept");

            store.removeFriend(b, a);
            helper.assertTrue(!store.areFriends(a, b), "removeFriend must clear both directions");
        });
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void mutualRequestAutoAccepts(GameTestHelper helper) {
        withStore(helper, (store, dir) -> {
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            store.touch(a, "Alpha");
            store.touch(b, "Beta");

            store.requestFriend(a, "Alpha", b, "Beta");
            boolean accepted = store.requestFriend(b, "Beta", a, "Alpha");
            helper.assertTrue(accepted, "a mutual request must auto-accept instead of deadlocking");
            helper.assertTrue(store.areFriends(a, b), "mutual request must produce a friendship");
        });
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void selfRequestRejected(GameTestHelper helper) {
        withStore(helper, (store, dir) -> {
            UUID a = UUID.randomUUID();
            store.touch(a, "Alpha");
            assertCode(helper, "SELF_REQUEST", () -> store.requestFriend(a, "Alpha", a, "Alpha"));
        });
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void momentLikeCommentAndReload(GameTestHelper helper) {
        withStore(helper, (store, dir) -> {
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            store.touch(a, "Alpha");
            store.touch(b, "Beta");
            SocialData.Moment moment = store.postMoment(a, "Alpha", "今天的晚霞", "");

            SocialStore.LikeResult first = store.toggleLike(moment.id, b);
            helper.assertTrue(first.liked() && first.likes() == 1, "first like must add one");
            SocialStore.LikeResult second = store.toggleLike(moment.id, b);
            helper.assertTrue(!second.liked() && second.likes() == 0, "second like must toggle off");

            store.comment(moment.id, b, "Beta", "很好看");
            store.save();

            SocialStore reloaded = new SocialStore(dir.resolve("social.json"));
            reloaded.load();
            SocialData.Moment loaded = reloaded.findMoment(moment.id);
            helper.assertTrue(loaded.text.equals("今天的晚霞"), "moment text must survive reload");
            helper.assertTrue(loaded.comments.size() == 1, "comment must survive reload");
            helper.assertTrue(loaded.comments.get(0).text.equals("很好看"), "comment text must survive reload");
        });
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void directChatRequiresFriendshipAndKeepsOrder(GameTestHelper helper) {
        withStore(helper, (store, dir) -> {
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            store.touch(a, "Alpha");
            store.touch(b, "Beta");

            assertCode(helper, "NOT_FRIENDS", () -> store.sendDirect(a, "Alpha", b, "hi"));

            store.requestFriend(a, "Alpha", b, "Beta");
            store.acceptFriend(a, b);
            store.sendDirect(a, "Alpha", b, "one");
            store.sendDirect(b, "Beta", a, "two");
            store.sendDirect(a, "Alpha", b, "three");

            List<SocialData.ChatMessage> history = store.directHistory(a, b, 10);
            helper.assertTrue(history.size() == 3, "direct history must contain three messages");
            helper.assertTrue(history.get(0).text.equals("one")
                    && history.get(1).text.equals("two")
                    && history.get(2).text.equals("three"), "direct history must stay chronological");
            helper.assertTrue(SocialStore.threadKey(a, b).equals(SocialStore.threadKey(b, a)),
                    "thread key must be symmetric");
        });
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void themeSelectPersistsAndLockedThemeRejected(GameTestHelper helper) {
        withStore(helper, (store, dir) -> {
            UUID a = UUID.randomUUID();
            store.touch(a, "Alpha");
            helper.assertTrue(SocialThemes.DEFAULT_THEME.equals(store.theme(a)),
                    "new profile must default to the halolink theme");

            store.selectTheme(a, "abydos");
            store.save();
            SocialStore reloaded = new SocialStore(dir.resolve("social.json"));
            reloaded.load();
            helper.assertTrue("abydos".equals(reloaded.theme(a)),
                    "selected theme must survive a reload");

            assertCode(helper, "THEME_LOCKED", () -> store.selectTheme(a, "starlight"));
            store.unlockTheme(a, "starlight");
            store.selectTheme(a, "starlight");
            helper.assertTrue("starlight".equals(store.theme(a)),
                    "an achievement-unlocked theme must become selectable");
        });
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void tarotThemesStartLockedAndUnlockIdempotently(GameTestHelper helper) {
        withStore(helper, (store, dir) -> {
            UUID a = UUID.randomUUID();
            store.touch(a, "Alpha");
            List<String> tiers = List.of(SocialThemes.STARDUST, SocialThemes.SHINING, SocialThemes.STARMASTER);
            for (String id : tiers) {
                helper.assertTrue(SocialThemes.byId(id).isPresent(), "tarot theme must be in the catalogue: " + id);
                assertCode(helper, "THEME_LOCKED", () -> store.selectTheme(a, id));
            }
            // 里程碑可以重复触发：unlockTheme 幂等，且只有已解锁的主题可选。
            for (String id : tiers) {
                store.unlockTheme(a, id);
                store.unlockTheme(a, id);
            }
            store.selectTheme(a, SocialThemes.STARMASTER);
            helper.assertTrue(SocialThemes.STARMASTER.equals(store.theme(a)), "starmaster must be selectable");
            store.save();
            SocialStore reloaded = new SocialStore(dir.resolve("social.json"));
            reloaded.load();
            SocialData.Profile profile = reloaded.profile(a);
            helper.assertTrue(profile != null && profile.unlockedThemes.containsAll(tiers),
                    "unlocked tarot themes must survive a reload");
            helper.assertTrue(SocialThemes.STARMASTER.equals(reloaded.theme(a)),
                    "equipped tarot theme must survive a reload");
        });
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void codexRecordsUnlockAllThreeTabletThemes(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockSurvivalServerPlayerWithChannel(helper);
        SocialStore previous = SocialService.store();
        try {
            withStore(helper, (store, dir) -> {
                SocialService.installForTests(store);
                UUID id = player.getUUID();
                store.touch(id, player.getName().getString());
                int countBefore = AchievementServices.catalog().countEarned(player.getAdvancements());
                for (int cardId = 0; cardId < TarotArcana.COUNT - 1; cardId++) {
                    TarotCodex.record(player, cardId, TarotQuality.R, true);
                }
                assertCode(helper, "THEME_LOCKED", () -> store.selectTheme(id, SocialThemes.STARDUST));
                helper.assertTrue(!earned(player, Milestone.STARDUST), "21 arcana must not earn the formal achievement");
                TarotCodex.record(player, TarotArcana.COUNT - 1, TarotQuality.R, true);
                helper.assertTrue(earned(player, Milestone.STARDUST), "22 arcana must earn the formal achievement");
                store.selectTheme(id, SocialThemes.STARDUST);
                helper.assertTrue(SocialThemes.STARDUST.equals(store.theme(id)),
                        "the 22nd arcana must unlock stardust through the registered listener");

                assertCode(helper, "THEME_LOCKED", () -> store.selectTheme(id, SocialThemes.SHINING));
                TarotCodex.record(player, 0, TarotQuality.SHINY, true);
                helper.assertTrue(earned(player, Milestone.SHINING), "a shiny form must earn the formal achievement");
                store.selectTheme(id, SocialThemes.SHINING);
                helper.assertTrue(SocialThemes.SHINING.equals(store.theme(id)),
                        "a shiny card must unlock shining through the registered listener");

                for (int cardId = 0; cardId < TarotArcana.COUNT; cardId++) {
                    for (TarotQuality quality : TarotQuality.values()) {
                        for (boolean upright : new boolean[]{true, false}) {
                            if (cardId == TarotArcana.COUNT - 1 && quality == TarotQuality.SHINY && !upright) {
                                continue;
                            }
                            TarotCodex.record(player, cardId, quality, upright);
                        }
                    }
                }
                helper.assertTrue(TarotCodex.summary(player).count() == 219, "one final form must remain");
                helper.assertTrue(!earned(player, Milestone.STARMASTER), "219 forms must not earn the formal achievement");
                assertCode(helper, "THEME_LOCKED", () -> store.selectTheme(id, SocialThemes.STARMASTER));
                TarotCodex.record(player, TarotArcana.COUNT - 1, TarotQuality.SHINY, false);
                helper.assertTrue(earned(player, Milestone.STARMASTER), "220 forms must earn the formal achievement");
                store.selectTheme(id, SocialThemes.STARMASTER);
                helper.assertTrue(SocialThemes.STARMASTER.equals(store.theme(id)),
                        "the 220th form must unlock starmaster through the registered listener");
                helper.assertTrue(!TarotCodex.record(player, 0, TarotQuality.SHINY, true),
                        "recording an owned form again must be a no-op");
                helper.assertTrue(TarotCodex.summary(player).count() == 220
                                && store.profile(id).unlockedThemes.size() == 3,
                        "duplicate records must preserve exactly 220 forms and three theme rewards");
                TarotCodex.notifyChanged(player);
                List<AchievementReward> pending = AchievementServices.rewards().pending(id);
                helper.assertTrue(pending.size() == 3 && pending.stream().mapToInt(AchievementReward::points).sum() == 950,
                        "three formal achievements must create exactly three pending rewards worth 950 points");
                helper.assertTrue(AchievementServices.rewards().points(id).balance() == 0,
                        "skins unlock on completion while points remain pending manual claim");
                helper.assertTrue(AchievementServices.catalog().countEarned(player.getAdvancements()) == countBefore + 3,
                        "all three tarot achievements must count in the main achievement catalogue");
            });
        } finally {
            SocialService.installForTests(previous);
            MockGameTestPlayers.logout(player);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void creativeAcquisitionDoesNotRecordCodex(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        try {
            ItemStack card = TarotCardItem.create(TarotRegistry.TAROT_CARD.get(), 0, TarotQuality.SHINY,
                    true, player.getUUID());
            helper.assertTrue(!TarotCodex.record(player, 0, TarotQuality.SHINY, true),
                    "creative card acquisition must not light a codex form");
            helper.assertTrue(!TarotCodex.record(player, card),
                    "creative pack and craft output must not light a codex form");
            player.getInventory().items.set(0, card);
            TarotCodex.scan(player);
            helper.assertTrue(TarotCodex.summary(player).count() == 0,
                    "creative inventory scanning must leave the codex empty");
            for (Milestone milestone : Milestone.values()) {
                helper.assertTrue(!earned(player, milestone), "creative acquisition must not earn " + milestone.id());
            }
            helper.succeed();
        } finally {
            MockGameTestPlayers.logout(player);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void historicCodexRequiresFormalAchievementBeforeTheme(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockSurvivalServerPlayerWithChannel(helper);
        SocialStore previous = SocialService.store();
        try {
            withStore(helper, (store, dir) -> {
                SocialService.installForTests(store);
                store.touch(player.getUUID(), player.getName().getString());
                // 模拟升级前已经落盘的图鉴，此时没有新获得事件。
                TarotCodexSavedData.get(player.server).record(player.getUUID(), 0, TarotQuality.SHINY, true);
                SocialCodexMilestones.apply(player);
                helper.assertTrue(!earned(player, Milestone.SHINING), "raw historical bits alone must not award a skin");
                assertCode(helper, "THEME_LOCKED", () -> store.selectTheme(player.getUUID(), SocialThemes.SHINING));
                AchievementBackfill.runSilently(player);
                AchievementBackfill.runSilently(player);
                helper.assertTrue(earned(player, Milestone.SHINING), "login backfill must earn the formal achievement");
                store.selectTheme(player.getUUID(), SocialThemes.SHINING);
                helper.assertTrue(AchievementServices.rewards().pending(player.getUUID()).size() == 1,
                        "repeated backfill must create only one pending reward");
            });
        } finally {
            SocialService.installForTests(previous);
            MockGameTestPlayers.logout(player);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void earnedThemeReconcilesAfterSocialProfileCreation(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockSurvivalServerPlayerWithChannel(helper);
        SocialStore previous = SocialService.store();
        try {
            withStore(helper, (store, dir) -> {
                SocialService.installForTests(store);
                TarotCodex.record(player, 0, TarotQuality.SHINY, true);
                helper.assertTrue(earned(player, Milestone.SHINING), "achievement must complete before a social profile exists");
                helper.assertTrue(store.profile(player.getUUID()) == null, "achievement callback must not create a profile");
                store.touch(player.getUUID(), player.getName().getString());
                SocialCodexMilestones.apply(player);
                store.selectTheme(player.getUUID(), SocialThemes.SHINING);
                helper.assertTrue(SocialThemes.SHINING.equals(store.theme(player.getUUID())),
                        "login reconciliation must unlock a theme earned before the profile existed");
            });
        } finally {
            SocialService.installForTests(previous);
            MockGameTestPlayers.logout(player);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void themeSwitchDoesNotDisableFormalAchievements(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockSurvivalServerPlayerWithChannel(helper);
        SocialStore previous = SocialService.store();
        boolean previousEnabled = TarotConfig.CODEX_UNLOCK_THEMES.get();
        try {
            withStore(helper, (store, dir) -> {
                SocialService.installForTests(store);
                store.touch(player.getUUID(), player.getName().getString());
                TarotConfig.CODEX_UNLOCK_THEMES.set(false);
                TarotCodex.record(player, 0, TarotQuality.SHINY, true);
                helper.assertTrue(earned(player, Milestone.SHINING), "theme switch must not disable formal achievements");
                assertCode(helper, "THEME_LOCKED", () -> store.selectTheme(player.getUUID(), SocialThemes.SHINING));
                TarotConfig.CODEX_UNLOCK_THEMES.set(true);
                SocialCodexMilestones.apply(player);
                store.selectTheme(player.getUUID(), SocialThemes.SHINING);
                helper.assertTrue(AchievementServices.rewards().pending(player.getUUID()).size() == 1,
                        "enabling theme unlocks must not duplicate the achievement reward");
            });
        } finally {
            TarotConfig.CODEX_UNLOCK_THEMES.set(previousEnabled);
            SocialService.installForTests(previous);
            MockGameTestPlayers.logout(player);
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void codexProgressFollowsTierBoundaries(GameTestHelper helper) {
        TarotCodex.Summary empty = new TarotCodex.Summary(0, 0, 0, 220);
        TarotCodex.Summary partial = new TarotCodex.Summary(21, 0, 137, 220);
        TarotCodex.Summary full = new TarotCodex.Summary(22, 3, 220, 220);

        SocialCodexMilestones.Progress stardust = SocialCodexMilestones.progress(
                SocialThemes.require(SocialThemes.STARDUST), partial);
        helper.assertTrue(stardust != null && stardust.now() == 21 && stardust.max() == 22,
                "stardust progress must be arcana/22");
        helper.assertTrue(SocialCodexMilestones.progress(SocialThemes.require(SocialThemes.STARDUST), full).percent() == 100,
                "22/22 must read as 100%");

        SocialCodexMilestones.Progress shining = SocialCodexMilestones.progress(
                SocialThemes.require(SocialThemes.SHINING), partial);
        helper.assertTrue(shining != null && shining.now() == 0 && shining.max() == 1,
                "shining progress must be 0/1 while no shiny form is lit");
        helper.assertTrue(SocialCodexMilestones.progress(SocialThemes.require(SocialThemes.SHINING), full).now() == 1,
                "shining progress must reach 1/1 with a shiny form");

        SocialCodexMilestones.Progress starmaster = SocialCodexMilestones.progress(
                SocialThemes.require(SocialThemes.STARMASTER), partial);
        helper.assertTrue(starmaster != null && starmaster.now() == 137 && starmaster.max() == 220,
                "starmaster progress must be codex/220");
        helper.assertTrue(SocialCodexMilestones.progress(
                        SocialThemes.require(SocialThemes.STARMASTER), empty).now() == 0,
                "empty codex must read 0/220");
        helper.assertTrue(SocialCodexMilestones.progress(SocialThemes.require("halolink"), empty) == null,
                "non-tarot themes must not carry codex progress");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void oversizedMomentImageRejected(GameTestHelper helper) {
        withStore(helper, (store, dir) -> {
            UUID a = UUID.randomUUID();
            store.touch(a, "Alpha");
            String huge = "data:image/jpeg;base64," + "A".repeat(7000);
            assertCode(helper, "IMAGE_TOO_LARGE", () -> store.postMoment(a, "Alpha", "with image", huge));
        });
    }

    private static boolean earned(ServerPlayer player, Milestone milestone) {
        var advancement = player.server.getAdvancements().getAdvancement(milestone.advancementId());
        if (advancement == null) {
            throw new IllegalStateException("Missing formal tarot achievement " + milestone.advancementId());
        }
        return player.getAdvancements().getOrStartProgress(advancement).isDone();
    }

    private interface StoreAction {
        void run(SocialStore store, Path dir);
    }

    private static void withStore(GameTestHelper helper, StoreAction action) {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("wok-social-test-");
            SocialStore store = new SocialStore(dir.resolve("social.json"));
            store.load();
            action.run(store, dir);
            helper.succeed();
        } catch (IOException e) {
            helper.fail("failed to create social test store: " + e.getMessage());
        } finally {
            if (dir != null) {
                try (var walk = Files.walk(dir)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                            // 测试目录清理失败不应掩盖业务断言结果。
                        }
                    });
                } catch (IOException ignored) {
                    // 同上。
                }
            }
        }
    }

    private static void assertCode(GameTestHelper helper, String code, Runnable action) {
        try {
            action.run();
        } catch (SocialException e) {
            helper.assertTrue(code.equals(e.errorCode()),
                    "expected social error code " + code + " but got " + e.errorCode());
            return;
        }
        helper.fail("expected SocialException with code " + code + " but no exception was thrown");
    }
}
