package com.miningdim.social;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.miningdim.core.auth.PlayerLoginGate;
import com.miningdim.network.MiningNetwork;
import com.miningdim.network.S2CWebUiEvent;
import com.miningdim.webui.server.WebUiBusinessException;
import com.miningdim.webui.server.WebUiErrorCodes;
import com.miningdim.webui.server.WebUiServerDispatcher;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 社交平板的 WebUI action 注册与 JSON 视图层。所有写操作以 {@link ServerPlayer} 为身份来源，
 * 前端传入的 uuid 只用于选择操作对象，绝不作为作者/发送者身份。
 *
 * <p>防刷：聊天、发说说、加好友、点赞和评论各有一层玩家级冷却。业务拒绝转成 {@link WebUiBusinessException}，
 * 由既有 dispatcher Gateway 回执稳定 errorCode；系统故障不在这里吞。</p>
 */
public final class SocialActions {

    private static final Gson GSON = new Gson();

    private static final long CHAT_COOLDOWN_MS = 1_000L;
    private static final long MOMENT_COOLDOWN_MS = 8_000L;
    private static final long FRIEND_COOLDOWN_MS = 2_000L;
    private static final int MOMENT_PAGE_SIZE = 3;
    private static final int CHAT_HISTORY_SIZE = 60;
    private static final int WORLD_HISTORY_SIZE = 40;
    private static final int MAX_RESPONSE_CHARS = FriendlyByteBuf.MAX_STRING_LENGTH;

    private static final Map<UUID, Long> LAST_CHAT = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_MOMENT = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_FRIEND = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_LIKE = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_COMMENT = new ConcurrentHashMap<>();

    private static boolean registered;

    private SocialActions() {
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        WebUiServerDispatcher.register("social.bootstrap", SocialActions::bootstrap);
        WebUiServerDispatcher.register("social.moments.page", SocialActions::momentsPage);
        WebUiServerDispatcher.register("social.moment.post", SocialActions::postMoment);
        WebUiServerDispatcher.register("social.moment.like", SocialActions::likeMoment);
        WebUiServerDispatcher.register("social.moment.comment", SocialActions::commentMoment);
        WebUiServerDispatcher.register("social.friend.request", SocialActions::requestFriend);
        WebUiServerDispatcher.register("social.friend.accept", SocialActions::acceptFriend);
        WebUiServerDispatcher.register("social.friend.decline", SocialActions::declineFriend);
        WebUiServerDispatcher.register("social.friend.remove", SocialActions::removeFriend);
        WebUiServerDispatcher.register("social.profile.update", SocialActions::updateProfile);
        WebUiServerDispatcher.register("social.theme.select", SocialActions::selectTheme);
        WebUiServerDispatcher.register("social.chat.history", SocialActions::chatHistory);
        WebUiServerDispatcher.register("social.chat.send", SocialActions::sendChat);
    }

    public static void clearPlayerCooldowns(UUID playerId) {
        LAST_CHAT.remove(playerId);
        LAST_MOMENT.remove(playerId);
        LAST_FRIEND.remove(playerId);
        LAST_LIKE.remove(playerId);
        LAST_COMMENT.remove(playerId);
    }

    private static String bootstrap(ServerPlayer sender, JsonObject payload) {
        return guard(() -> {
            SocialStore store = SocialService.store();
            SocialData.Profile self = store.touch(sender.getUUID(), sender.getName().getString());
            // /reload 后新加入的成就也能从持久图鉴静默补发，再核对皮肤奖励。
            com.miningdim.core.AdvancementSilence.run(() ->
                    com.miningdim.job.tarot.codex.TarotCodex.notifyChanged(sender));
            SocialCodexMilestones.apply(sender);
            com.miningdim.job.tarot.codex.TarotCodex.Summary codex =
                    com.miningdim.job.tarot.codex.TarotCodex.summary(sender);
            JsonObject result = new JsonObject();
            result.add("self", profileJson(self, true));
            result.addProperty("theme", store.theme(sender.getUUID()));
            JsonArray themes = new JsonArray();
            for (SocialThemes.Theme theme : SocialThemes.ALL) {
                JsonObject item = new JsonObject();
                item.addProperty("id", theme.id());
                item.addProperty("name", theme.name());
                item.addProperty("subtitle", theme.subtitle());
                item.addProperty("unlockKey", theme.unlockKey());
                item.addProperty("unlocked", SocialThemes.unlocked(self, theme));
                SocialCodexMilestones.Progress progress = SocialCodexMilestones.progress(theme, codex);
                if (progress != null) {
                    JsonObject progressJson = new JsonObject();
                    progressJson.addProperty("now", progress.now());
                    progressJson.addProperty("max", progress.max());
                    progressJson.addProperty("percent", progress.percent());
                    progressJson.addProperty("label", progress.label());
                    item.add("progress", progressJson);
                }
                themes.add(item);
            }
            result.add("themes", themes);

            JsonObject codexJson = new JsonObject();
            codexJson.addProperty("cards", codex.arcana());
            codexJson.addProperty("cardsTotal", codex.arcanaTotal());
            codexJson.addProperty("shiny", codex.shinyForms());
            codexJson.addProperty("codex", codex.count());
            codexJson.addProperty("total", codex.total());
            result.add("tarotCodex", codexJson);

            JsonArray friends = new JsonArray();
            for (UUID friendId : store.friendIds(sender.getUUID())) {
                SocialData.Profile profile = store.profile(friendId);
                if (profile == null) {
                    continue;
                }
                JsonObject item = profileJson(profile, online(sender, friendId));
                item.addProperty("thread", SocialStore.threadKey(sender.getUUID(), friendId));
                List<SocialData.ChatMessage> last = store.directHistory(sender.getUUID(), friendId, 1);
                if (!last.isEmpty()) {
                    SocialData.ChatMessage message = last.get(0);
                    item.addProperty("lastMessage", message.text);
                    item.addProperty("lastTime", message.createdAt);
                }
                friends.add(item);
            }
            result.add("friends", friends);

            JsonArray incoming = new JsonArray();
            for (UUID requester : store.incomingIds(sender.getUUID())) {
                SocialData.Profile profile = store.profile(requester);
                if (profile != null) {
                    incoming.add(profileJson(profile, online(sender, requester)));
                }
            }
            result.add("incoming", incoming);

            JsonArray outgoing = new JsonArray();
            for (UUID target : store.outgoingIds(sender.getUUID())) {
                SocialData.Profile profile = store.profile(target);
                if (profile != null) {
                    outgoing.add(profileJson(profile, online(sender, target)));
                }
            }
            result.add("outgoing", outgoing);

            JsonArray world = new JsonArray();
            for (SocialData.ChatMessage message : store.worldHistory(WORLD_HISTORY_SIZE)) {
                world.add(messageJson(message));
            }
            result.add("world", world);
            return fitResponseByRemovingOldest(result, world);
        });
    }

    private static String momentsPage(ServerPlayer sender, JsonObject payload) {
        return guard(() -> {
            long before = payload.has("before") ? payload.get("before").getAsLong() : 0L;
            String beforeId = payload.has("beforeId") ? payload.get("beforeId").getAsString() : "";
            SocialStore store = SocialService.store();
            List<SocialData.Moment> page = store.pageMoments(MOMENT_PAGE_SIZE, before, beforeId);
            JsonArray moments = new JsonArray();
            JsonObject result = new JsonObject();
            result.add("moments", moments);
            result.addProperty("hasMore", page.size() == MOMENT_PAGE_SIZE);
            for (SocialData.Moment moment : page) {
                moments.add(momentJson(moment, sender.getUUID()));
                if (GSON.toJson(result).length() > MAX_RESPONSE_CHARS) {
                    moments.remove(moments.size() - 1);
                    result.addProperty("hasMore", true);
                    if (moments.isEmpty()) {
                        throw responseTooLarge();
                    }
                    break;
                }
            }
            return fitResponse(result);
        });
    }

    private static String postMoment(ServerPlayer sender, JsonObject payload) {
        return guard(() -> {
            rate(LAST_MOMENT, sender, MOMENT_COOLDOWN_MS, "MOMENT_RATE_LIMITED");
            String text = payload.get("text").getAsString();
            String image = payload.has("image") ? payload.get("image").getAsString() : "";
            SocialStore store = SocialService.store();
            SocialData.Moment moment = store.postMoment(
                    sender.getUUID(), sender.getName().getString(), text, image);
            broadcast(sender.server, "social.moment.new", momentJson(moment, null));
            JsonObject result = new JsonObject();
            result.add("moment", momentJson(moment, sender.getUUID()));
            return GSON.toJson(result);
        });
    }

    private static String likeMoment(ServerPlayer sender, JsonObject payload) {
        return guard(() -> {
            rate(LAST_LIKE, sender, CHAT_COOLDOWN_MS, "LIKE_RATE_LIMITED");
            String id = payload.get("id").getAsString();
            SocialStore store = SocialService.store();
            SocialStore.LikeResult like = store.toggleLike(id, sender.getUUID());
            JsonObject event = new JsonObject();
            event.addProperty("id", id);
            event.addProperty("likes", like.likes());
            broadcast(sender.server, "social.moment.like", event);
            JsonObject result = new JsonObject();
            result.addProperty("id", id);
            result.addProperty("liked", like.liked());
            result.addProperty("likes", like.likes());
            return GSON.toJson(result);
        });
    }

    private static String commentMoment(ServerPlayer sender, JsonObject payload) {
        return guard(() -> {
            rate(LAST_COMMENT, sender, CHAT_COOLDOWN_MS, "COMMENT_RATE_LIMITED");
            String id = payload.get("id").getAsString();
            String text = payload.get("text").getAsString();
            SocialStore store = SocialService.store();
            SocialData.Comment comment = store.comment(
                    id, sender.getUUID(), sender.getName().getString(), text);
            JsonObject event = new JsonObject();
            event.addProperty("id", id);
            event.add("comment", commentJson(comment));
            broadcast(sender.server, "social.moment.comment", event);
            JsonObject result = new JsonObject();
            result.addProperty("id", id);
            result.add("comment", commentJson(comment));
            return GSON.toJson(result);
        });
    }

    private static String requestFriend(ServerPlayer sender, JsonObject payload) {
        return guard(() -> {
            rate(LAST_FRIEND, sender, FRIEND_COOLDOWN_MS, "FRIEND_RATE_LIMITED");
            String name = payload.get("name").getAsString().trim();
            ResolvedTarget target = resolve(sender.server, name);
            SocialStore store = SocialService.store();
            boolean accepted = store.requestFriend(
                    sender.getUUID(), sender.getName().getString(), target.uuid(), target.name());
            if (accepted) {
                notifyFriendChange(sender.server, "social.friend.accepted", sender.getUUID(), target.uuid());
            } else {
                ServerPlayer onlineTarget = sender.server.getPlayerList().getPlayer(target.uuid());
                if (onlineTarget != null) {
                    JsonObject event = new JsonObject();
                    event.add("from", profileJson(store.touch(sender.getUUID(), sender.getName().getString()), true));
                    MiningNetwork.sendWebUiEvent(onlineTarget, new S2CWebUiEvent("social.friend.request", GSON.toJson(event)));
                }
            }
            JsonObject result = new JsonObject();
            result.addProperty("accepted", accepted);
            return GSON.toJson(result);
        });
    }

    private static String acceptFriend(ServerPlayer sender, JsonObject payload) {
        return guard(() -> {
            UUID requester = UUID.fromString(payload.get("uuid").getAsString());
            SocialService.store().acceptFriend(requester, sender.getUUID());
            notifyFriendChange(sender.server, "social.friend.accepted", requester, sender.getUUID());
            return "{\"ok\":true}";
        });
    }

    private static String declineFriend(ServerPlayer sender, JsonObject payload) {
        return guard(() -> {
            UUID requester = UUID.fromString(payload.get("uuid").getAsString());
            SocialService.store().declineFriend(requester, sender.getUUID());
            return "{\"ok\":true}";
        });
    }

    private static String removeFriend(ServerPlayer sender, JsonObject payload) {
        return guard(() -> {
            UUID target = UUID.fromString(payload.get("uuid").getAsString());
            SocialService.store().removeFriend(sender.getUUID(), target);
            notifyFriendChange(sender.server, "social.friend.removed", sender.getUUID(), target);
            return "{\"ok\":true}";
        });
    }

    private static String updateProfile(ServerPlayer sender, JsonObject payload) {
        return guard(() -> {
            String signature = payload.has("signature") ? payload.get("signature").getAsString() : "";
            String academy = payload.has("academy") ? payload.get("academy").getAsString() : "";
            SocialStore store = SocialService.store();
            store.updateProfile(sender.getUUID(), signature, academy);
            SocialData.Profile profile = store.profile(sender.getUUID());
            JsonObject result = new JsonObject();
            result.add("profile", profileJson(profile, true));
            return GSON.toJson(result);
        });
    }

    private static String selectTheme(ServerPlayer sender, JsonObject payload) {
        return guard(() -> {
            String id = payload.get("id").getAsString();
            SocialThemes.Theme theme = SocialService.store().selectTheme(sender.getUUID(), id);
            JsonObject result = new JsonObject();
            result.addProperty("theme", theme.id());
            result.addProperty("name", theme.name());
            return GSON.toJson(result);
        });
    }

    private static String chatHistory(ServerPlayer sender, JsonObject payload) {
        return guard(() -> {
            String thread = payload.get("thread").getAsString();
            JsonArray messages = new JsonArray();
            SocialStore store = SocialService.store();
            if ("world".equals(thread)) {
                for (SocialData.ChatMessage message : store.worldHistory(CHAT_HISTORY_SIZE)) {
                    messages.add(messageJson(message));
                }
            } else {
                UUID target = UUID.fromString(payload.get("target").getAsString());
                if (!store.areFriends(sender.getUUID(), target)) {
                    throw new SocialException("NOT_FRIENDS", "只能读取好友私聊");
                }
                for (SocialData.ChatMessage message : store.directHistory(
                        sender.getUUID(), target, CHAT_HISTORY_SIZE)) {
                    messages.add(messageJson(message));
                }
            }
            JsonObject result = new JsonObject();
            result.add("messages", messages);
            return fitResponseByRemovingOldest(result, messages);
        });
    }

    private static String sendChat(ServerPlayer sender, JsonObject payload) {
        return guard(() -> {
            rate(LAST_CHAT, sender, CHAT_COOLDOWN_MS, "CHAT_RATE_LIMITED");
            String thread = payload.get("thread").getAsString();
            String text = payload.get("text").getAsString();
            SocialStore store = SocialService.store();
            if ("world".equals(thread)) {
                SocialData.ChatMessage message = store.sendWorld(
                        sender.getUUID(), sender.getName().getString(), text);
                JsonObject event = new JsonObject();
                event.add("message", messageJson(message));
                broadcast(sender.server, "social.chat.message", event);
                JsonObject result = new JsonObject();
                result.add("message", messageJson(message));
                return GSON.toJson(result);
            }
            UUID target = UUID.fromString(payload.get("target").getAsString());
            SocialData.ChatMessage message = store.sendDirect(
                    sender.getUUID(), sender.getName().getString(), target, text);
            JsonObject event = new JsonObject();
            event.add("message", messageJson(message));
            event.addProperty("peer", target.toString());
            MiningNetwork.sendWebUiEvent(sender, new S2CWebUiEvent("social.chat.message", GSON.toJson(event)));
            ServerPlayer onlineTarget = sender.server.getPlayerList().getPlayer(target);
            if (onlineTarget != null) {
                JsonObject targetEvent = new JsonObject();
                targetEvent.add("message", messageJson(message));
                targetEvent.addProperty("peer", sender.getUUID().toString());
                MiningNetwork.sendWebUiEvent(onlineTarget, new S2CWebUiEvent("social.chat.message", GSON.toJson(targetEvent)));
            }
            JsonObject result = new JsonObject();
            result.add("message", messageJson(message));
            return GSON.toJson(result);
        });
    }

    private static void notifyFriendChange(MinecraftServer server, String eventName, UUID a, UUID b) {
        SocialStore store = SocialService.store();
        UUID[] ids = {a, b};
        for (UUID id : ids) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null) {
                continue;
            }
            JsonObject event = new JsonObject();
            SocialData.Profile other = store.profile(a.equals(id) ? b : a);
            if (other != null) {
                event.add("friend", profileJson(other, true));
            }
            MiningNetwork.sendWebUiEvent(player, new S2CWebUiEvent(eventName, GSON.toJson(event)));
        }
    }

    private static void broadcast(MinecraftServer server, String eventName, JsonObject payload) {
        String data = GSON.toJson(payload);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            MiningNetwork.sendWebUiEvent(player, new S2CWebUiEvent(eventName, data));
        }
    }

    private static boolean online(ServerPlayer sender, UUID uuid) {
        return sender.server.getPlayerList().getPlayer(uuid) != null;
    }

    private static ResolvedTarget resolve(MinecraftServer server, String name) {
        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        if (online != null && PlayerLoginGate.allows(online)) {
            return new ResolvedTarget(online.getUUID(), online.getName().getString());
        }
        if (server.usesAuthentication()) {
            Optional<GameProfile> cached = server.getProfileCache().get(name);
            if (cached.isPresent() && cached.get().getId() != null) {
                return new ResolvedTarget(cached.get().getId(), cached.get().getName());
            }
        } else {
            // 离线 UUID 区分名字大小写；缓存会转小写并为未进服名字虚构档案。
            UUID uuid = UUIDUtil.createOfflinePlayerUUID(name);
            if (Files.isRegularFile(server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(uuid + ".dat"))) {
                return new ResolvedTarget(uuid, name);
            }
        }
        throw new SocialException("PLAYER_NOT_FOUND", "找不到该玩家");
    }

    private static JsonObject profileJson(SocialData.Profile profile, boolean online) {
        JsonObject json = new JsonObject();
        json.addProperty("uuid", profile.uuid);
        json.addProperty("name", profile.name);
        json.addProperty("signature", profile.signature == null ? "" : profile.signature);
        json.addProperty("academy", profile.academy == null ? "" : profile.academy);
        json.addProperty("online", online);
        json.addProperty("lastSeen", profile.lastSeen);
        return json;
    }

    private static JsonObject momentJson(SocialData.Moment moment, UUID viewer) {
        JsonObject json = new JsonObject();
        json.addProperty("id", moment.id);
        json.addProperty("author", moment.author);
        json.addProperty("authorName", moment.authorName);
        json.addProperty("text", moment.text);
        json.addProperty("image", moment.image == null ? "" : moment.image);
        json.addProperty("createdAt", moment.createdAt);
        json.addProperty("likes", moment.likes.size());
        json.addProperty("liked", viewer != null && moment.likes.contains(viewer.toString()));
        JsonArray comments = new JsonArray();
        int from = Math.max(0, moment.comments.size() - 10);
        for (int i = from; i < moment.comments.size(); i++) {
            comments.add(commentJson(moment.comments.get(i)));
        }
        json.add("comments", comments);
        return json;
    }

    private static JsonObject commentJson(SocialData.Comment comment) {
        JsonObject json = new JsonObject();
        json.addProperty("author", comment.author);
        json.addProperty("authorName", comment.authorName);
        json.addProperty("text", comment.text);
        json.addProperty("createdAt", comment.createdAt);
        return json;
    }

    private static JsonObject messageJson(SocialData.ChatMessage message) {
        JsonObject json = new JsonObject();
        json.addProperty("id", message.id);
        json.addProperty("thread", message.thread);
        json.addProperty("sender", message.sender);
        json.addProperty("senderName", message.senderName);
        json.addProperty("text", message.text);
        json.addProperty("createdAt", message.createdAt);
        return json;
    }

    private static String fitResponseByRemovingOldest(JsonObject result, JsonArray chronologicalItems) {
        while (GSON.toJson(result).length() > MAX_RESPONSE_CHARS && !chronologicalItems.isEmpty()) {
            chronologicalItems.remove(0);
        }
        return fitResponse(result);
    }

    private static String fitResponse(JsonObject result) {
        String json = GSON.toJson(result);
        if (json.length() > MAX_RESPONSE_CHARS) {
            throw responseTooLarge();
        }
        return json;
    }

    private static WebUiBusinessException responseTooLarge() {
        return new WebUiBusinessException(WebUiErrorCodes.RESPONSE_TOO_LARGE,
                "social response cannot fit within the downstream size limit", false);
    }

    private static void rate(Map<UUID, Long> map, ServerPlayer sender, long cooldown, String code) {
        long now = System.currentTimeMillis();
        Long previous = map.put(sender.getUUID(), now);
        if (previous != null && now - previous < cooldown) {
            throw new SocialException(code, "操作太快了，请稍后再试");
        }
    }

    private static String guard(Supplier<String> body) {
        try {
            return body.get();
        } catch (SocialException e) {
            throw new WebUiBusinessException(e.errorCode(), e.getMessage(), false);
        }
    }

    /** 目标玩家解析结果；离线服只接受原样名字对应的既有玩家存档。 */
    private record ResolvedTarget(UUID uuid, String name) {
    }
}
