package com.miningdim.social;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miningdim.core.MiningConstants;
import com.miningdim.testutil.MockGameTestPlayers;
import com.miningdim.webui.server.WebUiServerDispatcher;
import com.miningdim.webui.server.WebUiBusinessException;
import net.minecraft.core.UUIDUtil;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Social WebUI 回执体积、分页游标和好友目标解析的 GameTest。 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class SocialActionsResponseSizeGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "social_response_size";

    private SocialActionsResponseSizeGameTests() {
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void bootstrapFitsEscapedWorldHistory(GameTestHelper helper) {
        withStore(helper, (store, player) -> {
            SocialData.ChatMessage newest = null;
            for (int i = 0; i < 40; i++) {
                newest = store.sendWorld(player.getUUID(), player.getName().getString(), "<".repeat(300));
            }
            String json = handle("social.bootstrap", player, new JsonObject());
            JsonObject response = JsonParser.parseString(json).getAsJsonObject();
            helper.assertTrue(response.has("self") && response.has("friends")
                            && response.has("incoming") && response.has("outgoing")
                            && response.has("world"),
                    "bootstrap must preserve its fixed response fields");
            helper.assertTrue(response.getAsJsonArray("world").size() < 40,
                    "bootstrap must trim oldest world messages when HTML escaping expands the response");
            JsonObject last = response.getAsJsonArray("world").get(
                    response.getAsJsonArray("world").size() - 1).getAsJsonObject();
            helper.assertTrue(newest != null && newest.id.equals(last.get("id").getAsString()),
                    "bootstrap must preserve the newest world message");
            helper.assertTrue(json.length() <= FriendlyByteBuf.MAX_STRING_LENGTH,
                    "encoded bootstrap response must fit the downstream limit");
        });
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void chatHistoryKeepsNewestEscapedMessages(GameTestHelper helper) {
        withStore(helper, (store, player) -> {
            SocialData.ChatMessage newest = null;
            String text = "A" + "\u0001".repeat(298) + "A";
            for (int i = 0; i < 25; i++) {
                newest = store.sendWorld(player.getUUID(), player.getName().getString(), text);
            }
            JsonObject payload = new JsonObject();
            payload.addProperty("thread", "world");
            String json = handle("social.chat.history", player, payload);
            JsonObject response = JsonParser.parseString(json).getAsJsonObject();
            var messages = response.getAsJsonArray("messages");
            helper.assertTrue(json.length() <= FriendlyByteBuf.MAX_STRING_LENGTH,
                    "encoded chat history must fit the downstream limit");
            helper.assertTrue(messages.size() > 0 && messages.size() < 25,
                    "control-character escaping must trim the oldest history prefix");
            helper.assertTrue(newest != null && newest.id.equals(messages.get(messages.size() - 1)
                            .getAsJsonObject().get("id").getAsString()),
                    "chat history must retain the newest message");
        });
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void momentsPageCursorContinuesAfterEncodedTruncation(GameTestHelper helper) {
        SocialData legacyData = new SocialData();
        List<SocialData.Moment> newestFirst = new ArrayList<>();
        long recentTime = System.currentTimeMillis() - 1_000L;
        String image = "data:image/jpeg;base64," + "A".repeat(6000 - "data:image/jpeg;base64,".length());
        String commentText = "A" + "\\".repeat(50) + "\u0001".repeat(148) + "A";
        for (int i = 0; i < 4; i++) {
            SocialData.Moment moment = new SocialData.Moment();
            moment.id = momentId(i + 1L);
            moment.author = "00000000-0000-0000-0000-000000000001";
            moment.authorName = "Player";
            moment.text = "<".repeat(500);
            moment.image = image;
            moment.createdAt = recentTime + i;
            for (int j = 0; j < 10; j++) {
                SocialData.Comment comment = new SocialData.Comment(
                        moment.author, moment.authorName, commentText, recentTime + i + j);
                comment.id = null;
                moment.comments.add(comment);
            }
            legacyData.moments.add(moment);
            newestFirst.add(0, moment);
        }

        withStore(helper, legacyData, (store, player) -> {
            String firstJson = handle("social.moments.page", player, new JsonObject());
            JsonObject firstPage = JsonParser.parseString(firstJson).getAsJsonObject();
            var firstMoments = firstPage.getAsJsonArray("moments");
            helper.assertTrue(firstPage.get("hasMore").getAsBoolean()
                            && firstMoments.size() > 0 && firstMoments.size() < 3,
                    "truncating newest-first candidates must signal that another page is available");
            helper.assertTrue(firstJson.length() <= FriendlyByteBuf.MAX_STRING_LENGTH,
                    "encoded moments page must fit the downstream limit");
            long cursor = firstMoments.get(firstMoments.size() - 1).getAsJsonObject()
                    .get("createdAt").getAsLong();
            String cursorId = firstMoments.get(firstMoments.size() - 1).getAsJsonObject()
                    .get("id").getAsString();
            JsonObject nextPayload = new JsonObject();
            nextPayload.addProperty("before", cursor);
            nextPayload.addProperty("beforeId", cursorId);
            JsonObject nextPage = JsonParser.parseString(handle(
                    "social.moments.page", player, nextPayload)).getAsJsonObject();
            String omittedMomentId = newestFirst.get(firstMoments.size()).id;
            boolean foundOmittedMoment = false;
            for (var element : nextPage.getAsJsonArray("moments")) {
                foundOmittedMoment |= omittedMomentId.equals(
                        element.getAsJsonObject().get("id").getAsString());
            }
            helper.assertTrue(foundOmittedMoment,
                    "the next cursor must return the first moment omitted from the previous page");
        });
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void sameTimestampMomentCursorHasNoGaps(GameTestHelper helper) {
        long createdAt = System.currentTimeMillis() - 1_000L;
        SocialData legacyData = new SocialData();
        for (long id = 5L; id >= 1L; id--) {
            SocialData.Moment moment = new SocialData.Moment();
            moment.id = momentId(id);
            moment.author = "00000000-0000-0000-0000-000000000001";
            moment.authorName = "Player";
            moment.text = "same millisecond " + id;
            moment.image = "";
            moment.createdAt = createdAt;
            legacyData.moments.add(moment);
        }

        withStore(helper, legacyData, (store, player) -> {
            JsonObject firstPage = JsonParser.parseString(handle(
                    "social.moments.page", player, new JsonObject())).getAsJsonObject();
            var firstMoments = firstPage.getAsJsonArray("moments");
            helper.assertTrue(firstPage.get("hasMore").getAsBoolean() && firstMoments.size() == 3,
                    "the first same-timestamp page must contain three moments and signal continuation");

            JsonObject cursor = firstMoments.get(firstMoments.size() - 1).getAsJsonObject();
            JsonObject nextPayload = new JsonObject();
            nextPayload.addProperty("before", cursor.get("createdAt").getAsLong());
            nextPayload.addProperty("beforeId", cursor.get("id").getAsString());
            JsonObject secondPage = JsonParser.parseString(handle(
                    "social.moments.page", player, nextPayload)).getAsJsonObject();
            var secondMoments = secondPage.getAsJsonArray("moments");
            helper.assertTrue(!secondPage.get("hasMore").getAsBoolean() && secondMoments.size() == 2,
                    "the final same-timestamp page must contain both remaining moments");

            List<String> actualIds = new ArrayList<>();
            for (var element : firstMoments) actualIds.add(element.getAsJsonObject().get("id").getAsString());
            for (var element : secondMoments) actualIds.add(element.getAsJsonObject().get("id").getAsString());
            helper.assertTrue(actualIds.equals(List.of(momentId(5L), momentId(4L), momentId(3L),
                            momentId(2L), momentId(1L))),
                    "composite timestamp/id cursors must return every same-millisecond moment once in id DESC order");
        });
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void friendRequestUsesExactOfflineNameAndRejectsUnknownNames(GameTestHelper helper) {
        withStore(helper, (store, player) -> {
            helper.assertTrue(!player.server.usesAuthentication(), "GameTest must exercise offline identity resolution");
            String name = "Social" + UUID.randomUUID().toString().substring(0, 8);
            UUID target = UUIDUtil.createOfflinePlayerUUID(name);
            Path file = player.server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(target + ".dat");
            Files.createDirectories(file.getParent());
            Files.createFile(file);
            try {
                JsonObject payload = new JsonObject();
                payload.addProperty("name", name);
                handle("social.friend.request", player, payload);
                helper.assertTrue(store.outgoingIds(player.getUUID()).equals(List.of(target))
                                && store.incomingIds(target).equals(List.of(player.getUUID()))
                                && name.equals(store.profile(target).name),
                        "an uncached former player must receive the request at the exact-case offline UUID");
                for (String unknown : List.of(name.toLowerCase(Locale.ROOT), name + "x")) {
                    SocialActions.clearPlayerCooldowns(player.getUUID());
                    payload.addProperty("name", unknown);
                    try {
                        handle("social.friend.request", player, payload);
                        helper.fail("a name without player data must be rejected: " + unknown);
                    } catch (WebUiBusinessException expected) {
                        helper.assertTrue("PLAYER_NOT_FOUND".equals(expected.errorCode()),
                                "unknown names must return PLAYER_NOT_FOUND");
                    }
                    helper.assertTrue(store.profile(UUIDUtil.createOfflinePlayerUUID(unknown)) == null
                                    && store.outgoingIds(player.getUUID()).equals(List.of(target)),
                            "rejected names must not create ghost profiles or friend requests");
                }
            } finally {
                Files.delete(file);
            }
        });
    }

    private static String momentId(long sequence) {
        return new java.util.UUID(0L, sequence).toString();
    }

    private static String handle(String actionName, ServerPlayer player, JsonObject payload) {
        WebUiServerDispatcher.WebUiAction action = WebUiServerDispatcher.resolve(actionName);
        if (action == null) {
            throw new IllegalStateException("social action is not registered: " + actionName);
        }
        return action.handle(player, payload);
    }

    private static void withStore(GameTestHelper helper, StoreAction action) {
        withStore(helper, null, action);
    }

    private static void withStore(GameTestHelper helper, SocialData legacyData, StoreAction action) {
        ServerPlayer player = MockGameTestPlayers.makeMockSurvivalServerPlayerWithChannel(helper);
        SocialStore previous = SocialService.isLoaded() ? SocialService.store() : null;
        Path directory = null;
        try {
            directory = Files.createTempDirectory("wok-social-response-test-");
            Path legacyFile = directory.resolve("social.json");
            if (legacyData != null) {
                Files.writeString(legacyFile, new Gson().toJson(legacyData), StandardCharsets.UTF_8);
            }
            try (SocialStore store = new SocialStore(legacyFile)) {
                store.load();
                SocialService.installForTests(store);
                SocialActions.register();
                action.run(store, player);
                helper.succeed();
            }
        } catch (IOException failure) {
            helper.fail("failed to create social response test store: " + failure.getMessage());
        } finally {
            SocialActions.clearPlayerCooldowns(player.getUUID());
            SocialService.installForTests(previous);
            MockGameTestPlayers.logout(player);
            if (directory != null) {
                try (var walk = Files.walk(directory)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException cleanupFailure) {
                            throw new java.io.UncheckedIOException(cleanupFailure);
                        }
                    });
                } catch (IOException cleanupFailure) {
                    throw new java.io.UncheckedIOException(cleanupFailure);
                }
            }
        }
    }

    @FunctionalInterface
    private interface StoreAction {
        void run(SocialStore store, ServerPlayer player) throws IOException;
    }
}
