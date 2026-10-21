package com.miningdim.social;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 社交数据仓库。服务端单写者：所有公开方法都在对象锁内完成读改写，再做一次原子落盘。
 *
 * <p>落盘格式是 JSON 而不是 SQLite：v0.1 的社交数据量（好友、几百条动态、私聊）远低于 SQLite 的收益门槛，
 * 而 JSON 让存档可读、可手工修复。写入使用同目录临时文件 + 原子替换，避免半截文件。</p>
 *
 * <p>业务拒绝用 {@link SocialException} 携带稳定错误码；文件损坏、写盘失败等真实故障自然抛出，
 * 不静默重置存档。</p>
 */
public final class SocialStore {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/social");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final int MAX_MOMENTS = 300;
    private static final int MAX_CHAT_MESSAGES_PER_THREAD = 200;
    private static final int MAX_WORLD_MESSAGES = 100;
    private static final int MAX_TEXT = 500;
    private static final int MAX_CHAT_TEXT = 300;
    private static final int MAX_COMMENT_TEXT = 200;
    private static final int MAX_SIGNATURE = 80;
    private static final int MAX_ACADEMY = 32;
    private static final int MAX_IMAGE_CHARS = 6000;

    private final Path file;
    private SocialData data;

    public SocialStore(Path file) {
        this.file = file;
    }

    /** 读取存档；文件不存在时创建空存档。损坏文件先备份再抛出，避免静默丢档。 */
    public synchronized void load() {
        if (!Files.exists(file)) {
            data = new SocialData();
            saveLocked();
            return;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            SocialData parsed = GSON.fromJson(json, SocialData.class);
            if (parsed == null) {
                throw new JsonParseException("social data root is null");
            }
            normalize(parsed);
            data = parsed;
        } catch (IOException | JsonParseException e) {
            Path backup = file.resolveSibling(file.getFileName() + ".corrupt-" + System.currentTimeMillis());
            try {
                Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException backupError) {
                e.addSuppressed(backupError);
            }
            LOGGER.error("社交存档无法读取，已备份到 {}，将使用空存档启动", backup, e);
            data = new SocialData();
            saveLocked();
        }
    }

    public synchronized SocialData data() {
        requireLoaded();
        return data;
    }

    public synchronized SocialData.Profile touch(UUID uuid, String name) {
        requireLoaded();
        String key = uuid.toString();
        SocialData.Profile profile = data.profiles.get(key);
        if (profile == null) {
            profile = new SocialData.Profile(key, name, System.currentTimeMillis());
            data.profiles.put(key, profile);
            saveLocked();
            return profile;
        }
        boolean changed = false;
        if (!name.equals(profile.name)) {
            profile.name = name;
            changed = true;
        }
        long now = System.currentTimeMillis();
        if (now - profile.lastSeen > 60_000L) {
            profile.lastSeen = now;
            changed = true;
        }
        if (changed) {
            saveLocked();
        }
        return profile;
    }

    public synchronized SocialData.Profile profile(UUID uuid) {
        requireLoaded();
        return data.profiles.get(uuid.toString());
    }

    public synchronized String nameOf(UUID uuid) {
        requireLoaded();
        SocialData.Profile profile = data.profiles.get(uuid.toString());
        return profile == null ? "Unknown" : profile.name;
    }

    public synchronized List<UUID> friendIds(UUID uuid) {
        requireLoaded();
        return toUuids(data.friends.get(uuid.toString()));
    }

    public synchronized List<UUID> incomingIds(UUID uuid) {
        requireLoaded();
        return toUuids(data.incoming.get(uuid.toString()));
    }

    public synchronized List<UUID> outgoingIds(UUID uuid) {
        requireLoaded();
        return toUuids(data.outgoing.get(uuid.toString()));
    }

    public synchronized boolean areFriends(UUID a, UUID b) {
        requireLoaded();
        List<String> list = data.friends.get(a.toString());
        return list != null && list.contains(b.toString());
    }

    /**
     * 发起好友请求。若对方已经向自己发过请求，直接完成互加，避免双向请求僵持。
     * 返回 true 表示已直接成为好友，false 表示请求已发出。
     */
    public synchronized boolean requestFriend(UUID from, String fromName, UUID to, String toName) {
        requireLoaded();
        if (from.equals(to)) {
            throw new SocialException("SELF_REQUEST", "不能添加自己为好友");
        }
        if (areFriends(from, to)) {
            throw new SocialException("ALREADY_FRIENDS", "你们已经是好友了");
        }
        String fromKey = from.toString();
        String toKey = to.toString();
        if (list(data.incoming, fromKey).contains(toKey)) {
            acceptFriend(to, from);
            return true;
        }
        if (list(data.outgoing, fromKey).contains(toKey)) {
            throw new SocialException("ALREADY_REQUESTED", "好友请求已经发出");
        }
        touchLocked(from, fromName);
        touchLocked(to, toName);
        addUnique(list(data.outgoing, fromKey), toKey);
        addUnique(list(data.incoming, toKey), fromKey);
        saveLocked();
        return false;
    }

    /** {@code requester} 是先前发起请求的一方，{@code accepter} 是当前操作者。 */
    public synchronized void acceptFriend(UUID requester, UUID accepter) {
        requireLoaded();
        String requesterKey = requester.toString();
        String accepterKey = accepter.toString();
        List<String> incoming = list(data.incoming, accepterKey);
        if (!incoming.remove(requesterKey)) {
            throw new SocialException("NOT_REQUESTED", "没有来自该玩家的好友请求");
        }
        list(data.outgoing, requesterKey).remove(accepterKey);
        addUnique(list(data.friends, requesterKey), accepterKey);
        addUnique(list(data.friends, accepterKey), requesterKey);
        saveLocked();
    }

    public synchronized void declineFriend(UUID requester, UUID accepter) {
        requireLoaded();
        String requesterKey = requester.toString();
        String accepterKey = accepter.toString();
        if (!list(data.incoming, accepterKey).remove(requesterKey)) {
            throw new SocialException("NOT_REQUESTED", "没有来自该玩家的好友请求");
        }
        list(data.outgoing, requesterKey).remove(accepterKey);
        saveLocked();
    }

    public synchronized void removeFriend(UUID a, UUID b) {
        requireLoaded();
        String aKey = a.toString();
        String bKey = b.toString();
        if (!list(data.friends, aKey).remove(bKey)) {
            throw new SocialException("NOT_FRIENDS", "你们不是好友");
        }
        list(data.friends, bKey).remove(aKey);
        saveLocked();
    }

    /** 当前主题 id；档案缺失时返回默认主题。 */
    public synchronized String theme(UUID uuid) {
        requireLoaded();
        SocialData.Profile profile = data.profiles.get(uuid.toString());
        if (profile == null || profile.theme == null || profile.theme.isBlank()) {
            return SocialThemes.DEFAULT_THEME;
        }
        return profile.theme;
    }

    /** 已装备主题必须在目录内且已解锁；锁定主题由服务端拒绝，前端只做展示。 */
    public synchronized SocialThemes.Theme selectTheme(UUID uuid, String themeId) {
        requireLoaded();
        SocialData.Profile profile = data.profiles.get(uuid.toString());
        if (profile == null) {
            throw new SocialException("PROFILE_MISSING", "玩家档案尚未建立");
        }
        SocialThemes.Theme theme = SocialThemes.require(themeId);
        if (!SocialThemes.unlocked(profile, theme)) {
            throw new SocialException("THEME_LOCKED", "该主题尚未解锁");
        }
        profile.theme = theme.id();
        saveLocked();
        return theme;
    }

    /**
     * 成就系统的唯一接入口：把某个主题记为已解锁。重复调用幂等。
     * 当前没有调用方；成就系统上线后由服务端成就结算调用，客户端不能直接触发。
     */
    public synchronized void unlockTheme(UUID uuid, String themeId) {
        requireLoaded();
        SocialThemes.Theme theme = SocialThemes.require(themeId);
        SocialData.Profile profile = data.profiles.get(uuid.toString());
        if (profile == null) {
            throw new SocialException("PROFILE_MISSING", "玩家档案尚未建立");
        }
        if (!profile.unlockedThemes.contains(theme.id())) {
            profile.unlockedThemes.add(theme.id());
            saveLocked();
        }
    }

    public synchronized void updateProfile(UUID uuid, String signature, String academy) {
        requireLoaded();
        String cleanSignature = clean(signature, MAX_SIGNATURE, "签名");
        String cleanAcademy = clean(academy, MAX_ACADEMY, "学院");
        SocialData.Profile profile = data.profiles.get(uuid.toString());
        if (profile == null) {
            throw new SocialException("PROFILE_MISSING", "玩家档案尚未建立");
        }
        profile.signature = cleanSignature;
        profile.academy = cleanAcademy;
        saveLocked();
    }

    public synchronized SocialData.Moment postMoment(UUID author, String authorName, String text, String image) {
        requireLoaded();
        String cleanText = clean(text, MAX_TEXT, "说说");
        if (cleanText.isBlank()) {
            throw new SocialException("EMPTY_TEXT", "说说内容不能为空");
        }
        String cleanImage = image == null ? "" : image.trim();
        if (!cleanImage.isEmpty()) {
            if (!cleanImage.startsWith("data:image/jpeg;base64,")) {
                throw new SocialException("BAD_IMAGE", "图片格式不受支持");
            }
            if (cleanImage.length() > MAX_IMAGE_CHARS) {
                throw new SocialException("IMAGE_TOO_LARGE", "缩略图过大，请重新拍摄");
            }
        }
        touchLocked(author, authorName);
        SocialData.Moment moment = new SocialData.Moment();
        moment.id = UUID.randomUUID().toString();
        moment.author = author.toString();
        moment.authorName = authorName;
        moment.text = cleanText;
        moment.image = cleanImage;
        moment.createdAt = System.currentTimeMillis();
        data.moments.add(0, moment);
        trimNewestAtFront(data.moments, MAX_MOMENTS);
        saveLocked();
        return moment;
    }

    public synchronized LikeResult toggleLike(String momentId, UUID player) {
        requireLoaded();
        SocialData.Moment moment = findMoment(momentId);
        String key = player.toString();
        boolean liked;
        if (moment.likes.remove(key)) {
            liked = false;
        } else {
            moment.likes.add(key);
            liked = true;
        }
        saveLocked();
        return new LikeResult(liked, moment.likes.size());
    }

    public synchronized SocialData.Comment comment(String momentId, UUID author, String authorName, String text) {
        requireLoaded();
        SocialData.Moment moment = findMoment(momentId);
        String cleanText = clean(text, MAX_COMMENT_TEXT, "评论");
        if (cleanText.isBlank()) {
            throw new SocialException("EMPTY_TEXT", "评论内容不能为空");
        }
        touchLocked(author, authorName);
        SocialData.Comment comment = new SocialData.Comment(
                author.toString(), authorName, cleanText, System.currentTimeMillis());
        moment.comments.add(comment);
        trimOldestAtFront(moment.comments, 100);
        saveLocked();
        return comment;
    }

    public synchronized SocialData.ChatMessage sendWorld(UUID sender, String senderName, String text) {
        requireLoaded();
        String cleanText = clean(text, MAX_CHAT_TEXT, "消息");
        if (cleanText.isBlank()) {
            throw new SocialException("EMPTY_TEXT", "消息不能为空");
        }
        touchLocked(sender, senderName);
        SocialData.ChatMessage message = new SocialData.ChatMessage(
                UUID.randomUUID().toString(), "world", sender.toString(), senderName,
                cleanText, System.currentTimeMillis());
        data.world.add(message);
        trimOldestAtFront(data.world, MAX_WORLD_MESSAGES);
        saveLocked();
        return message;
    }

    public synchronized SocialData.ChatMessage sendDirect(UUID sender, String senderName, UUID target, String text) {
        requireLoaded();
        if (!areFriends(sender, target)) {
            throw new SocialException("NOT_FRIENDS", "只能给好友发送私聊");
        }
        String cleanText = clean(text, MAX_CHAT_TEXT, "消息");
        if (cleanText.isBlank()) {
            throw new SocialException("EMPTY_TEXT", "消息不能为空");
        }
        touchLocked(sender, senderName);
        String thread = threadKey(sender, target);
        SocialData.ChatMessage message = new SocialData.ChatMessage(
                UUID.randomUUID().toString(), thread, sender.toString(), senderName,
                cleanText, System.currentTimeMillis());
        List<SocialData.ChatMessage> messages =
                data.chats.computeIfAbsent(thread, ignored -> new ArrayList<>());
        messages.add(message);
        trimOldestAtFront(messages, MAX_CHAT_MESSAGES_PER_THREAD);
        saveLocked();
        return message;
    }

    public synchronized List<SocialData.ChatMessage> worldHistory(int limit) {
        requireLoaded();
        int from = Math.max(0, data.world.size() - Math.max(0, limit));
        return new ArrayList<>(data.world.subList(from, data.world.size()));
    }

    public synchronized List<SocialData.ChatMessage> directHistory(UUID a, UUID b, int limit) {
        requireLoaded();
        List<SocialData.ChatMessage> messages = data.chats.get(threadKey(a, b));
        if (messages == null) {
            return List.of();
        }
        int from = Math.max(0, messages.size() - Math.max(0, limit));
        return new ArrayList<>(messages.subList(from, messages.size()));
    }

    /** 按时间倒序取一页动态；before=0 表示从最新开始。 */
    public synchronized List<SocialData.Moment> pageMoments(int limit, long before) {
        requireLoaded();
        List<SocialData.Moment> result = new ArrayList<>();
        int cap = Math.max(0, Math.min(limit, 20));
        for (SocialData.Moment moment : data.moments) {
            if (before > 0L && moment.createdAt >= before) {
                continue;
            }
            result.add(moment);
            if (result.size() >= cap) {
                break;
            }
        }
        return result;
    }

    public synchronized SocialData.Moment findMoment(String id) {
        requireLoaded();
        for (SocialData.Moment moment : data.moments) {
            if (moment.id.equals(id)) {
                return moment;
            }
        }
        throw new SocialException("MOMENT_NOT_FOUND", "该说说不存在或已被删除");
    }

    public synchronized void save() {
        requireLoaded();
        saveLocked();
    }

    private void saveLocked() {
        requireLoaded();
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(data), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("无法保存社交存档: " + file, e);
        }
    }

    private void requireLoaded() {
        if (data == null) {
            throw new IllegalStateException("social store not loaded: " + file);
        }
    }

    private void touchLocked(UUID uuid, String name) {
        String key = uuid.toString();
        SocialData.Profile profile = data.profiles.get(key);
        if (profile == null) {
            data.profiles.put(key, new SocialData.Profile(key, name, System.currentTimeMillis()));
        } else if (name != null && !name.isBlank() && !name.equals(profile.name)) {
            profile.name = name;
        }
    }

    private static String clean(String value, int max, String field) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.length() > max) {
            throw new SocialException("TEXT_TOO_LONG", field + "最多 " + max + " 个字符");
        }
        return trimmed;
    }

    private static List<String> list(java.util.Map<String, List<String>> map, String key) {
        return map.computeIfAbsent(key, ignored -> new ArrayList<>());
    }

    private static List<UUID> toUuids(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<UUID> result = new ArrayList<>(raw.size());
        for (String value : raw) {
            try {
                result.add(UUID.fromString(value));
            } catch (IllegalArgumentException ignored) {
                // 损坏的单条 UUID 不应拖垮整份好友列表；记录后跳过。
                LOGGER.warn("社交存档中出现非法 UUID: {}", value);
            }
        }
        return result;
    }

    private static void addUnique(List<String> list, String value) {
        if (!list.contains(value)) {
            list.add(value);
        }
    }

    private static void trimNewestAtFront(List<?> list, int max) {
        while (list.size() > max) {
            list.remove(list.size() - 1);
        }
    }

    private static void trimOldestAtFront(List<?> list, int max) {
        while (list.size() > max) {
            list.remove(0);
        }
    }

    public static String threadKey(UUID a, UUID b) {
        String first = a.toString();
        String second = b.toString();
        return first.compareTo(second) <= 0 ? "dm:" + first + ":" + second : "dm:" + second + ":" + first;
    }

    private static void normalize(SocialData data) {
        if (data.profiles == null) data.profiles = new java.util.LinkedHashMap<>();
        if (data.friends == null) data.friends = new java.util.LinkedHashMap<>();
        if (data.incoming == null) data.incoming = new java.util.LinkedHashMap<>();
        if (data.outgoing == null) data.outgoing = new java.util.LinkedHashMap<>();
        if (data.moments == null) data.moments = new ArrayList<>();
        if (data.chats == null) data.chats = new java.util.LinkedHashMap<>();
        if (data.world == null) data.world = new ArrayList<>();
        for (SocialData.Moment moment : data.moments) {
            if (moment.likes == null) moment.likes = new ArrayList<>();
            if (moment.comments == null) moment.comments = new ArrayList<>();
        }
        for (SocialData.Profile profile : data.profiles.values()) {
            if (profile.theme == null || profile.theme.isBlank()) {
                profile.theme = SocialThemes.DEFAULT_THEME;
            }
            if (profile.unlockedThemes == null) {
                profile.unlockedThemes = new ArrayList<>();
            }
        }
    }

    public record LikeResult(boolean liked, int likes) {
    }
}
