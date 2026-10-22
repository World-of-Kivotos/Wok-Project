package com.miningdim.social;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * 社交数据仓库。对象锁保护业务状态；每次只提交变动记录，由 SQLite 后台单写者分批落盘。
 * 历史通过有索引的限量查询读取，不全量加载进内存；未提交记录由后台队列提供即时可见性。
 *
 * <p>业务拒绝用 {@link SocialException} 携带稳定错误码；文件损坏、写盘失败等真实故障自然抛出，
 * 不静默重置存档。</p>
 */
public final class SocialStore implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/social");
    private static final Gson GSON = new Gson();

    private static final int MAX_TEXT = 500;
    private static final int MAX_CHAT_TEXT = 300;
    private static final int MAX_COMMENT_TEXT = 200;
    private static final int MAX_SIGNATURE = 80;
    private static final int MAX_ACADEMY = 32;
    private static final int MAX_IMAGE_CHARS = 6000;

    private final Path file;
    private SocialData data;
    private SocialDatabase database;

    public SocialStore(Path file) {
        this.file = file;
    }

    /** 参数仍是旧 JSON 路径；首次启动将其迁到同名 .db，并保留原件及独立备份。 */
    public synchronized void load() {
        if (database != null) throw new IllegalStateException("social store is already loaded");
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
        } catch (IOException error) {
            throw new UncheckedIOException("无法创建社交存档目录: " + file, error);
        }
        Path dbFile = file.resolveSibling(file.getFileName().toString().replaceFirst("\\.json$", "") + ".db");
        SocialDatabase opened = new SocialDatabase(dbFile);
        try {
            if (!opened.initialized()) {
                data = readLegacy();
                if (Files.exists(file)) {
                    Path backup = file.resolveSibling(file.getFileName() + ".migration-backup-" + System.currentTimeMillis());
                    Files.copy(file, backup);
                    LOGGER.info("迁移社交 JSON 前已备份到 {}，原文件保持不变", backup);
                }
                opened.initialize(migrationRows());
            }
            data = new SocialData();
            for (SocialDatabase.Row row : opened.permanent("profile")) {
                data.profiles.put(row.id(), decode(row, SocialData.Profile.class));
            }
            for (SocialDatabase.Row row : opened.permanent("relations")) {
                Relations relations = decode(row, Relations.class);
                data.friends.put(row.id(), relations.friends());
                data.incoming.put(row.id(), relations.incoming());
                data.outgoing.put(row.id(), relations.outgoing());
            }
            database = opened;
            database.start();
        } catch (IOException | RuntimeException error) {
            data = null;
            database = null;
            try {
                opened.close();
            } catch (RuntimeException closeError) {
                error.addSuppressed(closeError);
            }
            throw new IllegalStateException("社交存档加载失败，未使用空档覆盖原数据: " + file, error);
        }
    }

    private SocialData readLegacy() {
        if (!Files.exists(file)) return new SocialData();
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            var root = JsonParser.parseString(json).getAsJsonObject();
            for (String key : List.of("profiles", "friends", "incoming", "outgoing", "moments", "chats", "world")) {
                if (!root.has(key) || root.get(key).isJsonNull()) throw new JsonParseException("social data is missing " + key);
            }
            SocialData parsed = GSON.fromJson(json, SocialData.class);
            if (parsed == null) throw new JsonParseException("social data root is null");
            normalize(parsed);
            return parsed;
        } catch (IOException | RuntimeException e) {
            Path backup = file.resolveSibling(file.getFileName() + ".corrupt-" + System.currentTimeMillis());
            try {
                Files.copy(file, backup);
                LOGGER.error("社交 JSON 损坏，原件及备份 {} 均保留，停止加载", backup, e);
            } catch (IOException backupError) {
                e.addSuppressed(backupError);
                LOGGER.error("社交 JSON 损坏且备份失败，原文件 {} 保持不变，停止加载", file, e);
            }
            throw new IllegalStateException("无法迁移社交 JSON: " + file, e);
        }
    }

    public synchronized SocialData.Profile touch(UUID uuid, String name) {
        requireWritable();
        String key = uuid.toString();
        SocialData.Profile profile = data.profiles.get(key);
        if (profile == null) {
            profile = new SocialData.Profile(key, name, System.currentTimeMillis());
            data.profiles.put(key, profile);
            saveProfilesLocked(uuid);
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
            saveProfilesLocked(uuid);
        }
        return profile;
    }

    public synchronized SocialData.Profile profile(UUID uuid) {
        requireLoaded();
        return data.profiles.get(uuid.toString());
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
        requireWritable();
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
        savePlayersLocked(from, to);
        return false;
    }

    /** {@code requester} 是先前发起请求的一方，{@code accepter} 是当前操作者。 */
    public synchronized void acceptFriend(UUID requester, UUID accepter) {
        requireWritable();
        String requesterKey = requester.toString();
        String accepterKey = accepter.toString();
        List<String> incoming = list(data.incoming, accepterKey);
        if (!incoming.remove(requesterKey)) {
            throw new SocialException("NOT_REQUESTED", "没有来自该玩家的好友请求");
        }
        list(data.outgoing, requesterKey).remove(accepterKey);
        addUnique(list(data.friends, requesterKey), accepterKey);
        addUnique(list(data.friends, accepterKey), requesterKey);
        savePlayersLocked(requester, accepter);
    }

    public synchronized void declineFriend(UUID requester, UUID accepter) {
        requireWritable();
        String requesterKey = requester.toString();
        String accepterKey = accepter.toString();
        if (!list(data.incoming, accepterKey).remove(requesterKey)) {
            throw new SocialException("NOT_REQUESTED", "没有来自该玩家的好友请求");
        }
        list(data.outgoing, requesterKey).remove(accepterKey);
        savePlayersLocked(requester, accepter);
    }

    public synchronized void removeFriend(UUID a, UUID b) {
        requireWritable();
        String aKey = a.toString();
        String bKey = b.toString();
        if (!list(data.friends, aKey).remove(bKey)) {
            throw new SocialException("NOT_FRIENDS", "你们不是好友");
        }
        list(data.friends, bKey).remove(aKey);
        savePlayersLocked(a, b);
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
        requireWritable();
        SocialData.Profile profile = data.profiles.get(uuid.toString());
        if (profile == null) {
            throw new SocialException("PROFILE_MISSING", "玩家档案尚未建立");
        }
        SocialThemes.Theme theme = SocialThemes.require(themeId);
        if (!SocialThemes.unlocked(profile, theme)) {
            throw new SocialException("THEME_LOCKED", "该主题尚未解锁");
        }
        profile.theme = theme.id();
        saveProfilesLocked(uuid);
        return theme;
    }

    /**
     * 成就系统的唯一接入口：把某个主题记为已解锁。重复调用幂等。
     * 由正式成就完成与登录补发调用，客户端不能直接触发。
     */
    public synchronized void unlockTheme(UUID uuid, String themeId) {
        requireWritable();
        SocialThemes.Theme theme = SocialThemes.require(themeId);
        SocialData.Profile profile = data.profiles.get(uuid.toString());
        if (profile == null) {
            throw new SocialException("PROFILE_MISSING", "玩家档案尚未建立");
        }
        if (!profile.unlockedThemes.contains(theme.id())) {
            profile.unlockedThemes.add(theme.id());
            saveProfilesLocked(uuid);
        }
    }

    public synchronized void updateProfile(UUID uuid, String signature, String academy) {
        requireWritable();
        String cleanSignature = clean(signature, MAX_SIGNATURE, "签名");
        String cleanAcademy = clean(academy, MAX_ACADEMY, "学院");
        SocialData.Profile profile = data.profiles.get(uuid.toString());
        if (profile == null) {
            throw new SocialException("PROFILE_MISSING", "玩家档案尚未建立");
        }
        profile.signature = cleanSignature;
        profile.academy = cleanAcademy;
        saveProfilesLocked(uuid);
    }

    public synchronized SocialData.Moment postMoment(UUID author, String authorName, String text, String image) {
        requireWritable();
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
        List<SocialDatabase.Row> rows = profileRows(author);
        rows.add(momentRow(moment));
        database.enqueue(rows);
        return moment;
    }

    public synchronized LikeResult toggleLike(String momentId, UUID player) {
        requireWritable();
        SocialData.Moment moment = findMoment(momentId);
        String key = player.toString();
        boolean liked;
        if (moment.likes.remove(key)) {
            liked = false;
        } else {
            moment.likes.add(key);
            liked = true;
        }
        database.enqueue(List.of(momentRow(moment)));
        return new LikeResult(liked, moment.likes.size());
    }

    public synchronized SocialData.Comment comment(String momentId, UUID author, String authorName, String text) {
        requireWritable();
        SocialData.Moment moment = findMoment(momentId);
        String cleanText = clean(text, MAX_COMMENT_TEXT, "评论");
        if (cleanText.isBlank()) {
            throw new SocialException("EMPTY_TEXT", "评论内容不能为空");
        }
        touchLocked(author, authorName);
        SocialData.Comment comment = new SocialData.Comment(
                author.toString(), authorName, cleanText, System.currentTimeMillis());
        List<SocialDatabase.Row> rows = profileRows(author);
        rows.add(commentRow(moment.id, comment));
        database.enqueue(rows);
        return comment;
    }

    public synchronized SocialData.ChatMessage sendWorld(UUID sender, String senderName, String text) {
        requireWritable();
        String cleanText = clean(text, MAX_CHAT_TEXT, "消息");
        if (cleanText.isBlank()) {
            throw new SocialException("EMPTY_TEXT", "消息不能为空");
        }
        touchLocked(sender, senderName);
        SocialData.ChatMessage message = new SocialData.ChatMessage(
                UUID.randomUUID().toString(), "world", sender.toString(), senderName,
                cleanText, System.currentTimeMillis());
        saveMessageLocked(message);
        return message;
    }

    public synchronized SocialData.ChatMessage sendDirect(UUID sender, String senderName, UUID target, String text) {
        requireWritable();
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
        saveMessageLocked(message);
        return message;
    }

    public synchronized List<SocialData.ChatMessage> worldHistory(int limit) {
        requireLoaded();
        return messageHistory("world", "world", limit);
    }

    public synchronized List<SocialData.ChatMessage> directHistory(UUID a, UUID b, int limit) {
        requireLoaded();
        return messageHistory("direct", threadKey(a, b), limit);
    }

    /** 按时间倒序取一页动态；before=0 表示从最新开始。 */
    public synchronized List<SocialData.Moment> pageMoments(int limit, long before) {
        return pageMoments(limit, before, "");
    }

    public synchronized List<SocialData.Moment> pageMoments(int limit, long before, String beforeId) {
        requireLoaded();
        List<SocialData.Moment> result = new ArrayList<>();
        for (SocialDatabase.Row row : database.history("moment", "", before, beforeId, limit, System.currentTimeMillis())) {
            result.add(readMoment(row));
        }
        return result;
    }

    public synchronized SocialData.Moment findMoment(String id) {
        requireLoaded();
        SocialDatabase.Row row = database.moment(id, System.currentTimeMillis());
        if (row != null) return readMoment(row);
        throw new SocialException("MOMENT_NOT_FOUND", "该说说不存在或已被删除");
    }

    public synchronized void save() {
        requireLoaded();
        database.flush();
    }

    @Override
    public synchronized void close() {
        if (database == null) return;
        try {
            database.close();
        } finally {
            database = null;
            data = null;
        }
    }

    private List<SocialData.ChatMessage> messageHistory(String kind, String thread, int limit) {
        List<SocialData.ChatMessage> result = new ArrayList<>();
        for (SocialDatabase.Row row : database.history(kind, thread, 0L, limit, System.currentTimeMillis())) {
            result.add(decode(row, SocialData.ChatMessage.class));
        }
        java.util.Collections.reverse(result);
        return result;
    }

    private SocialData.Moment readMoment(SocialDatabase.Row row) {
        SocialData.Moment moment = decode(row, SocialData.Moment.class);
        List<SocialDatabase.Row> comments = database.history("comment", moment.id, 0L, 100, System.currentTimeMillis());
        for (int index = comments.size() - 1; index >= 0; index--) {
            moment.comments.add(decode(comments.get(index), SocialData.Comment.class));
        }
        return moment;
    }

    private void savePlayersLocked(UUID... players) {
        database.enqueue(playerRows(players));
    }

    private List<SocialDatabase.Row> playerRows(UUID... players) {
        List<SocialDatabase.Row> rows = profileRows(players);
        for (UUID uuid : players) rows.add(relationsRow(uuid.toString()));
        return rows;
    }

    private void saveProfilesLocked(UUID... players) {
        database.enqueue(profileRows(players));
    }

    private List<SocialDatabase.Row> profileRows(UUID... players) {
        List<SocialDatabase.Row> rows = new ArrayList<>();
        for (UUID uuid : players) {
            String key = uuid.toString();
            SocialData.Profile profile = data.profiles.get(key);
            rows.add(new SocialDatabase.Row("profile", profile.uuid, "", 0L, GSON.toJson(profile)));
        }
        return rows;
    }

    private SocialDatabase.Row relationsRow(String player) {
        // 无记录表示尚未添加好友或请求，是该集合的初始态。
        Relations relations = new Relations(data.friends.getOrDefault(player, List.of()),
                data.incoming.getOrDefault(player, List.of()), data.outgoing.getOrDefault(player, List.of()));
        return new SocialDatabase.Row("relations", player, "", 0L, GSON.toJson(relations));
    }

    private void saveMessageLocked(SocialData.ChatMessage message) {
        List<SocialDatabase.Row> rows = profileRows(UUID.fromString(message.sender));
        rows.add(messageRow(message));
        database.enqueue(rows);
    }

    private static SocialDatabase.Row messageRow(SocialData.ChatMessage message) {
        return new SocialDatabase.Row("world".equals(message.thread) ? "world" : "direct",
                message.id, message.thread, message.createdAt, GSON.toJson(message));
    }

    private static SocialDatabase.Row momentRow(SocialData.Moment moment) {
        // 评论单独存储、按自身创建时间过期；点赞只改当前动态，不重写其他动态或历史聊天。
        MomentBody body = new MomentBody(moment.id, moment.author, moment.authorName, moment.text,
                moment.image, moment.createdAt, moment.likes);
        return new SocialDatabase.Row("moment", moment.id, "", moment.createdAt, GSON.toJson(body));
    }

    private static SocialDatabase.Row commentRow(String momentId, SocialData.Comment comment) {
        return new SocialDatabase.Row("comment", comment.id, momentId, comment.createdAt, GSON.toJson(comment));
    }

    private List<SocialDatabase.Row> migrationRows() {
        List<SocialDatabase.Row> rows = new ArrayList<>();
        for (SocialData.Profile profile : data.profiles.values()) {
            rows.add(new SocialDatabase.Row("profile", profile.uuid, "", 0L, GSON.toJson(profile)));
        }
        var owners = new LinkedHashSet<>(data.friends.keySet());
        owners.addAll(data.incoming.keySet());
        owners.addAll(data.outgoing.keySet());
        for (String owner : owners) rows.add(relationsRow(owner));
        for (SocialData.Moment moment : data.moments) {
            rows.add(momentRow(moment));
            for (SocialData.Comment comment : moment.comments) rows.add(commentRow(moment.id, comment));
        }
        for (SocialData.ChatMessage message : data.world) rows.add(messageRow(message));
        for (List<SocialData.ChatMessage> messages : data.chats.values()) {
            for (SocialData.ChatMessage message : messages) rows.add(messageRow(message));
        }
        return rows;
    }

    private static <T> T decode(SocialDatabase.Row row, Class<T> type) {
        T parsed = GSON.fromJson(row.json(), type);
        if (parsed == null) throw new IllegalStateException("社交记录损坏: " + row.kind() + "/" + row.id());
        return parsed;
    }

    private record Relations(List<String> friends, List<String> incoming, List<String> outgoing) {}

    private record MomentBody(String id, String author, String authorName, String text, String image,
                              long createdAt, List<String> likes) {}

    private void requireWritable() {
        requireLoaded();
        database.checkWritable();
    }

    private void requireLoaded() {
        if (data == null) {
            throw new IllegalStateException("social store not loaded: " + file);
        }
        database.checkHealthy();
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
            result.add(UUID.fromString(value));
        }
        return result;
    }

    private static void addUnique(List<String> list, String value) {
        if (!list.contains(value)) {
            list.add(value);
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
        validateLegacyRelations("friends", data.friends);
        validateLegacyRelations("incoming", data.incoming);
        validateLegacyRelations("outgoing", data.outgoing);
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

    private static void validateLegacyRelations(String field, java.util.Map<String, List<String>> relations) {
        for (var entry : relations.entrySet()) {
            String owner = entry.getKey();
            validateLegacyUuid(owner, field, owner, "owner");
            List<String> members = entry.getValue();
            if (members == null) {
                throw new IllegalArgumentException("Invalid social " + field + " for owner " + owner
                        + ": UUID list is null");
            }
            for (int index = 0; index < members.size(); index++) {
                validateLegacyUuid(members.get(index), field, owner, "member[" + index + "]");
            }
        }
    }

    private static void validateLegacyUuid(String value, String field, String owner, String position) {
        try {
            UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException error) {
            throw new IllegalArgumentException("Invalid UUID in social " + field + " for owner " + owner
                    + " at " + position + ": " + value, error);
        }
    }

    public record LikeResult(boolean liked, int likes) {
    }
}
